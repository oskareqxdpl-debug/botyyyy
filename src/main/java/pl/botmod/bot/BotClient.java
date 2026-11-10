package pl.botmod.bot;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Lekki klient protokolu Minecrafta 1.21.4 (protokol 769), tryb offline (nonpremium).
 * Nie uzywa zadnych klas Minecrafta - sam laczy sie z serwerem po TCP.
 *
 * Jesli kiedys cos przestanie dzialac po aktualizacji gry, to ID pakietow ponizej
 * sa jedynym miejscem, ktore trzeba poprawic.
 */
public class BotClient {
    public enum State { IDLE, CONNECTING, ONLINE, DISCONNECTED }

    private enum Phase { LOGIN, CONFIG, PLAY }

    public static final int PROTOCOL = 769;

    // ---- ID pakietow: serverbound (bot -> serwer), faza PLAY ----
    static final int S_CONFIRM_TELEPORT = 0x00;
    static final int S_CHAT_COMMAND = 0x05;
    static final int S_CHAT = 0x07;
    static final int S_CHUNK_BATCH = 0x09;
    static final int S_STATUS = 0x0A;
    static final int S_CLICK = 0x10;
    static final int S_KEEPALIVE = 0x1A;
    static final int S_POS = 0x1C;
    static final int S_POSROT = 0x1D;
    static final int S_PONG = 0x2A;
    static final int S_ACK_CONFIG = 0x0E;
    static final int S_CLOSE_CONTAINER = 0x11;
    static final int S_HELD_ITEM = 0x32;
    static final int S_USE_ITEM = 0x3C;

    // ---- ID pakietow: clientbound (serwer -> bot), faza PLAY ----
    static final int C_BATCH_FINISHED = 0x0C;
    static final int C_CLOSE_CONTAINER = 0x12;
    static final int C_CONTAINER_CONTENT = 0x13;
    static final int C_CONTAINER_SLOT = 0x15;
    static final int C_DISCONNECT = 0x1D;
    static final int C_DISGUISED_CHAT = 0x1E;
    static final int C_KEEPALIVE = 0x27;
    static final int C_PING = 0x37;
    static final int C_START_CONFIG = 0x70;
    static final int C_PLAYER_CHAT = 0x3B;
    static final int C_COMBAT_DEATH = 0x3E;
    static final int C_POSITION = 0x42;
    static final int C_SYSTEM_CHAT = 0x73;

    private final String name;
    private final String host;
    private final int port;
    private final UUID uuid;

    private final List<String> log = new ArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss");

    private volatile State state = State.IDLE;
    private volatile Phase phase = Phase.LOGIN;
    private volatile boolean running;
    private volatile int compressionThreshold = -1;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private final Object writeLock = new Object();
    private ScheduledExecutorService ticker;

    // pozycja i ruch
    private final Object posLock = new Object();
    private double x, y, z;
    private float yaw, pitch;
    private float sentYaw, sentPitch;
    private boolean havePos;
    private boolean onGround = true;
    private double vy, groundY;
    private boolean jumpRequested;
    private int ticksSinceSend;
    private volatile boolean forward, back, left, right;
    private volatile int containerState;
    // otwarte okno serwera (np. menu kompasu)
    private volatile int openWindow;
    private int openState;
    private Object[] openItems;
    private boolean windowOpened;
    private volatile int heldSlot;
    private int sequence;
    private final Set<Integer> seenIds = new HashSet<>();
    private boolean keepAliveSeen;
    private boolean keepAliveWarned;
    private boolean joinedOnce;
    private volatile long traceUntil;

    // ekwipunek (okno 0): 0 wynik craftingu, 1-4 craft, 5-8 pancerz, 9-35 plecak, 36-44 hotbar, 45 offhand
    private final Object[] inventory = new Object[46];
    private boolean decodeErrLogged;

    // ochrona przed kickiem
    private volatile String lastKick = "";
    private long onlineSince;
    private final Random rnd = new Random();
    private int afkTicks, nextAfk = 300 + new Random().nextInt(400), afkWalk;

    public BotClient(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ API

    public String getName() { return name; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public State getState() { return state; }
    public boolean isForward() { return forward; }
    public boolean isBack() { return back; }
    public boolean isLeft() { return left; }
    public boolean isRight() { return right; }

    public void setForward(boolean v) { forward = v; }
    public void setBack(boolean v) { back = v; }
    public void setLeft(boolean v) { left = v; }
    public void setRight(boolean v) { right = v; }

    public void stopMoving() {
        forward = back = left = right = false;
    }

    public void jump() { jumpRequested = true; }

    public void turn(float dYaw, float dPitch) {
        synchronized (posLock) {
            yaw += dYaw;
            while (yaw > 180f) yaw -= 360f;
            while (yaw < -180f) yaw += 360f;
            pitch = Math.max(-90f, Math.min(90f, pitch + dPitch));
        }
    }

    public boolean hasPosition() {
        synchronized (posLock) { return havePos; }
    }

    /** {x, y, z, yaw, pitch} */
    public double[] getPosition() {
        synchronized (posLock) { return new double[]{x, y, z, yaw, pitch}; }
    }

    public List<String> getLog() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    public synchronized void connect() {
        if (state == State.CONNECTING || state == State.ONLINE) return;
        state = State.CONNECTING;
        running = true;
        compressionThreshold = -1;
        traceUntil = System.currentTimeMillis() + 8000;
        phase = Phase.LOGIN;
        lastKick = "";
        synchronized (posLock) {
            havePos = false;
        }
        Thread t = new Thread(this::run, "bot-" + name);
        t.setDaemon(true);
        t.start();
    }

    public Object[] getInventory() {
        synchronized (inventory) {
            return inventory.clone();
        }
    }

    public int getHeldSlot() { return heldSlot; }
    public int getOpenWindow() { return openWindow; }

    public Object[] getOpenItems() {
        synchronized (inventory) {
            return openItems == null ? null : openItems.clone();
        }
    }

    /** true tylko raz po otwarciu nowego okna przez serwer. */
    public boolean consumeWindowOpened() {
        synchronized (inventory) {
            boolean r = windowOpened;
            windowOpened = false;
            return r;
        }
    }

    /** Wyrzuca jedna sztuke (all=false) albo caly stack (all=true) z danego slotu ekwipunku. */
    public void dropSlot(int slot, boolean all) {
        if (slot < 1 || slot > 45) return;
        click(0, containerState, slot, all ? 1 : 0, 4);
    }

    /** Klik w slot otwartego okna serwera (mode 0 = zwykly, 1 = shift). */
    public void clickWindow(int slot, int button, int mode) {
        int w = openWindow;
        if (w == 0) return;
        click(w, openState, slot, button, mode);
        log("Klik w slot " + slot + " okna serwera.");
    }

    private void click(int window, int stateId, int slot, int button, int mode) {
        if (state != State.ONLINE) return;
        try {
            Out o = new Out();
            o.varInt(window);
            o.varInt(stateId);
            o.i16(slot);
            o.i8(button);
            o.varInt(mode);
            o.varInt(0);
            o.varInt(0);
            send(S_CLICK, o);
        } catch (IOException e) {
            log("Blad: " + e.getMessage());
        }
    }

    public void closeWindow() {
        int w = openWindow;
        if (w == 0) return;
        synchronized (inventory) {
            openWindow = 0;
            openItems = null;
        }
        try {
            Out o = new Out();
            o.varInt(w);
            send(S_CLOSE_CONTAINER, o);
        } catch (IOException ignored) {
        }
    }

    public void selectHotbar(int slot) {
        if (state != State.ONLINE || slot < 0 || slot > 8) return;
        heldSlot = slot;
        try {
            Out o = new Out();
            o.i16(slot);
            send(S_HELD_ITEM, o);
        } catch (IOException e) {
            log("Blad: " + e.getMessage());
        }
    }

    /** Prawy klik z przedmiotem w rece (np. kompas). */
    public void useItem() {
        if (state != State.ONLINE) {
            log("Bot nie jest na serwerze.");
            return;
        }
        try {
            float yw, pt;
            synchronized (posLock) {
                yw = yaw;
                pt = pitch;
            }
            Out o = new Out();
            o.varInt(0);
            o.varInt(++sequence);
            o.f32(yw);
            o.f32(pt);
            send(S_USE_ITEM, o);
            log("Prawy klik (slot " + (heldSlot + 1) + ").");
        } catch (IOException e) {
            log("Blad: " + e.getMessage());
        }
    }

    public void disconnect() {
        running = false;
        closeQuietly();
    }

    /** Wysyla wiadomosc na czat; tekst zaczynajacy sie od / jest komenda. */
    public void say(String text) {
        if (state != State.ONLINE) {
            log("Bot nie jest na serwerze.");
            return;
        }
        try {
            if (text.startsWith("/")) {
                Out o = new Out();
                o.string(text.substring(1));
                send(S_CHAT_COMMAND, o);
            } else {
                Out o = new Out();
                o.string(text);
                o.i64(System.currentTimeMillis());
                o.i64(new Random().nextLong());
                o.bool(false);
                o.varInt(0);
                o.bytes(new byte[3]);
                send(S_CHAT, o);
            }
            log("> " + text);
        } catch (IOException e) {
            log("Blad wysylania: " + e.getMessage());
        }
    }

    public void respawn() {
        if (state != State.ONLINE) return;
        try {
            Out o = new Out();
            o.varInt(0);
            send(S_STATUS, o);
        } catch (IOException e) {
            log("Blad: " + e.getMessage());
        }
    }

    /** Wyrzuca wszystkie przedmioty z ekwipunku (pancerz, plecak, hotbar, druga reka). */
    public void dropAll() {
        if (state != State.ONLINE) {
            log("Bot nie jest na serwerze.");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                log("Oprozniam ekwipunek...");
                for (int slot = 1; slot <= 45; slot++) {
                    if (!running) return;
                    Out o = new Out();
                    o.varInt(0);                 // okno 0 = ekwipunek gracza
                    o.varInt(containerState);    // state id
                    o.i16(slot);
                    o.i8(1);                     // przycisk 1 = caly stack (Ctrl+Q)
                    o.varInt(4);                 // tryb 4 = wyrzucanie
                    o.varInt(0);                 // brak zmienionych slotow
                    o.varInt(0);                 // pusty kursor
                    send(S_CLICK, o);
                    Thread.sleep(25);
                }
                log("Ekwipunek oprozniony.");
            } catch (Exception e) {
                log("Blad podczas oprozniania: " + e.getMessage());
            }
        }, "bot-drop-" + name);
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ polaczenie

    private void run() {
        try {
            log("Lacze z " + host + " jako " + name + " ...");
            String connectHost = host;
            int connectPort = port > 0 ? port : 25565;
            int handshakePort = connectPort;
            if (port == 0) {
                String[] srv = lookupSrv(host);
                if (srv != null) {
                    connectHost = srv[0];
                    connectPort = Integer.parseInt(srv[1]);
                    dbg("rekord SRV: " + connectHost + ":" + connectPort);
                }
            }
            InetSocketAddress addr = new InetSocketAddress(connectHost, connectPort);
            if (addr.isUnresolved()) {
                log("Nie znaleziono serwera '" + connectHost + "' (sprawdz IP).");
                return;
            }
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(addr, 8000);
            socket.setSoTimeout(60000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new BufferedOutputStream(socket.getOutputStream());

            Out hs = new Out();
            hs.varInt(PROTOCOL);
            hs.string(host);
            hs.u16(handshakePort);
            hs.varInt(2); // nastepny stan: login
            send(0x00, hs);

            Out ls = new Out();
            ls.string(name);
            ls.i64(uuid.getMostSignificantBits());
            ls.i64(uuid.getLeastSignificantBits());
            send(0x00, ls);
            phase = Phase.LOGIN;

            while (running) {
                Buf p = readPacket();
                int id = p.varInt();
                noteDebug(id, p.remaining());
                switch (phase) {
                    case LOGIN -> onLogin(id, p);
                    case CONFIG -> onConfig(id, p);
                    case PLAY -> onPlay(id, p);
                }
            }
        } catch (EOFException e) {
            if (running) log("Serwer zamknal polaczenie.");
        } catch (SocketTimeoutException e) {
            if (running) log("Przekroczono czas oczekiwania na serwer.");
        } catch (Exception e) {
            if (running) log("Blad: " + e);
        } finally {
            shutdown();
        }
    }

    private void onLogin(int id, Buf p) throws IOException {
        switch (id) {
            case 0x00 -> {
                lastKick = p.string();
                log("Serwer odrzucil logowanie: " + lastKick);
                running = false;
            }
            case 0x01 -> {
                log("Serwer jest w trybie ONLINE (premium) - bot nonpremium nie wejdzie.");
                running = false;
            }
            case 0x02 -> {
                send(0x03, new Out()); // Login Acknowledged
                phase = Phase.CONFIG;
                sendClientInfo();
                log("Zalogowano jako " + name + ", trwa konfiguracja...");
            }
            case 0x03 -> compressionThreshold = p.varInt();
            case 0x04 -> {
                int mid = p.varInt();
                Out o = new Out();
                o.varInt(mid);
                o.bool(false);
                send(0x02, o);
            }
            default -> { }
        }
    }

    private void sendBrand() throws IOException {
        Out b = new Out();
        b.string("minecraft:brand");
        b.string("vanilla");
        send(0x02, b);
    }

    private void sendClientInfo() throws IOException {
        sendBrand();
        Out ci = new Out();
        ci.string("pl_pl");
        ci.i8(8);          // view distance
        ci.varInt(0);      // chat: wlaczony
        ci.bool(true);     // kolory czatu
        ci.u8(0x7F);       // czesci skina
        ci.varInt(1);      // glowna reka: prawa
        ci.bool(false);    // filtrowanie tekstu
        ci.bool(true);     // pokazuj na liscie serwerow
        ci.varInt(0);      // particle status: all
        send(0x00, ci);
    }

    private void onConfig(int id, Buf p) throws IOException {
        switch (id) {
            case 0x00 -> { // cookie request
                String ident = p.string();
                Out o = new Out();
                o.string(ident);
                o.bool(false);
                send(0x01, o);
            }
            case 0x02 -> {
                lastKick = readText(p);
                log("Wyrzucony: " + lastKick);
                running = false;
            }
            case 0x03 -> {
                send(0x03, new Out());
                phase = Phase.PLAY;
                state = State.ONLINE;
                onlineSince = System.currentTimeMillis();
                log(joinedOnce ? "Bot wszedl na nowy serwer." : "Bot wszedl na serwer.");
                joinedOnce = true;
                if (ticker == null || ticker.isShutdown()) startTicker();
            }
            case 0x04 -> {
                long k = p.i64();
                Out o = new Out();
                o.i64(k);
                send(0x04, o);
            }
            case 0x05 -> {
                int v = p.i32();
                Out o = new Out();
                o.i32(v);
                send(0x05, o);
            }
            case 0x09 -> { // resource pack
                long hi = p.i64();
                long lo = p.i64();
                for (int result : new int[]{3, 4, 0}) {
                    Out o = new Out();
                    o.i64(hi);
                    o.i64(lo);
                    o.varInt(result);
                    send(0x06, o);
                }
            }
            case 0x0B -> log("Serwer probuje przekierowac bota (transfer) - nieobslugiwane.");
            case 0x0E -> { // known packs -> nie znamy zadnych
                Out o = new Out();
                o.varInt(0);
                send(0x07, o);
            }
            default -> { }
        }
    }

    private void onPlay(int id, Buf p) throws IOException {
        switch (id) {
            case C_KEEPALIVE -> {
                keepAliveSeen = true;
                long k = p.i64();
                Out o = new Out();
                o.i64(k);
                send(S_KEEPALIVE, o);
            }
            case C_START_CONFIG -> {
                log("Serwer przenosi bota (zmiana serwera)...");
                traceUntil = System.currentTimeMillis() + 8000;
                seenIds.clear();
                synchronized (posLock) {
                    havePos = false;
                }
                synchronized (inventory) {
                    openWindow = 0;
                    openItems = null;
                    Arrays.fill(inventory, null);
                }
                synchronized (writeLock) {
                    phase = Phase.CONFIG;
                    send(S_ACK_CONFIG, new Out());
                }
                sendClientInfo();
            }
            case C_PING -> {
                int v = p.i32();
                Out o = new Out();
                o.i32(v);
                send(S_PONG, o);
            }
            case C_DISCONNECT -> {
                lastKick = readText(p);
                log("Wyrzucony z serwera: " + lastKick);
                running = false;
            }
            case C_SYSTEM_CHAT -> {
                Object raw = nbt(p, p.u8(), 0);
                String t = comp(raw);
                if (t.isBlank() || t.trim().endsWith(":")) {
                    String d = String.valueOf(raw);
                    t = t + " [" + (d.length() > 300 ? d.substring(0, 300) : d) + "]";
                }
                boolean overlay = p.bool();
                if (!overlay && !t.isBlank()) log(t);
            }
            case C_DISGUISED_CHAT -> {
                String t = readText(p);
                if (!t.isBlank()) log(t);
            }
            case C_PLAYER_CHAT -> onPlayerChat(p);
            case C_POSITION -> onPosition(p);
            case C_BATCH_FINISHED -> {
                p.varInt();
                Out o = new Out();
                o.f32(20f);
                send(S_CHUNK_BATCH, o);
            }
            case C_COMBAT_DEATH -> {
                log("Bot zginal - respawn.");
                respawn();
            }
            case C_CLOSE_CONTAINER -> {
                int w = p.varInt();
                if (w != 0 && w == openWindow) {
                    synchronized (inventory) {
                        openWindow = 0;
                        openItems = null;
                    }
                }
            }
            case C_CONTAINER_CONTENT -> {
                int window = p.varInt();
                int st = p.varInt();
                if (window == 0) containerState = st;
                BotManager.SlotDecoder dec = BotManager.slotDecoder();
                if (dec != null && window >= 0) {
                    try {
                        Object[] arr = dec.decodeContent(p.rest());
                        synchronized (inventory) {
                            if (window == 0) {
                                for (int i = 0; i < Math.min(arr.length, inventory.length); i++) inventory[i] = arr[i];
                            } else {
                                openWindow = window;
                                openState = st;
                                openItems = arr;
                                windowOpened = true;
                            }
                        }
                        if (window != 0) log("Serwer otworzyl okno (" + arr.length + " slotow).");
                    } catch (Throwable t) {
                        decodeError(t);
                    }
                }
            }
            case C_CONTAINER_SLOT -> {
                int window = p.varInt();
                int st = p.varInt();
                int slot = p.i16();
                BotManager.SlotDecoder dec = BotManager.slotDecoder();
                if (dec != null && window >= 0 && slot >= 0) {
                    try {
                        if (window == 0) {
                            containerState = st;
                            if (slot < inventory.length) {
                                Object it = dec.decodeSlot(p.rest());
                                synchronized (inventory) {
                                    inventory[slot] = it;
                                }
                            }
                        } else if (window == openWindow) {
                            Object it = dec.decodeSlot(p.rest());
                            synchronized (inventory) {
                                openState = st;
                                if (openItems != null && slot < openItems.length) openItems[slot] = it;
                            }
                        }
                    } catch (Throwable t) {
                        decodeError(t);
                    }
                }
            }
            default -> { }
        }
    }

    private void decodeError(Throwable t) {
        if (decodeErrLogged) return;
        decodeErrLogged = true;
        log("Nie udalo sie odczytac przedmiotow (" + t + "). Wejdz swoim klientem na ten sam serwer, "
                + "wtedy rejestry sie zgodza.");
    }

    private void onPosition(Buf p) throws IOException {
        int tid = p.varInt();
        double nx = p.f64(), ny = p.f64(), nz = p.f64();
        p.f64();
        p.f64();
        p.f64();
        float nyaw = p.f32(), npitch = p.f32();
        int flags = p.i32();
        double sx, sy, sz;
        float syaw, spitch;
        synchronized (posLock) {
            if ((flags & 0x01) != 0) nx += x;
            if ((flags & 0x02) != 0) ny += y;
            if ((flags & 0x04) != 0) nz += z;
            if ((flags & 0x08) != 0) nyaw += yaw;
            if ((flags & 0x10) != 0) npitch += pitch;
            x = nx;
            y = ny;
            z = nz;
            yaw = nyaw;
            pitch = npitch;
            sentYaw = yaw;
            sentPitch = pitch;
            groundY = y;
            vy = 0;
            onGround = true;
            havePos = true;
            sx = x;
            sy = y;
            sz = z;
            syaw = yaw;
            spitch = pitch;
        }
        Out c = new Out();
        c.varInt(tid);
        send(S_CONFIRM_TELEPORT, c);
        Out r = new Out();
        r.f64(sx);
        r.f64(sy);
        r.f64(sz);
        r.f32(syaw);
        r.f32(spitch);
        r.i8(1);
        send(S_POSROT, r);
    }

    private void onPlayerChat(Buf p) {
        p.skip(16);   // uuid nadawcy
        p.varInt();   // index
        if (p.bool()) p.skip(256);
        String msg = p.string();
        String sender = "?";
        try {
            p.i64();
            p.i64();
            int n = p.varInt();
            for (int i = 0; i < n; i++) {
                if (p.varInt() == 0) p.skip(256);
            }
            if (p.bool()) readText(p);
            int filter = p.varInt();
            if (filter == 2) {
                int l = p.varInt();
                p.skip(l * 8);
            }
            int chatType = p.varInt();
            if (chatType != 0) sender = readText(p);
        } catch (Exception ignored) {
            sender = "?";
        }
        log("<" + sender + "> " + msg);
    }

    // ------------------------------------------------------------------ ruch (tick co 50 ms)

    private void startTicker() {
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bot-tick-" + name);
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(this::tick, 50, 50, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            if (phase != Phase.PLAY || state != State.ONLINE) return;
            if (!keepAliveSeen && !keepAliveWarned && onlineSince > 0
                    && System.currentTimeMillis() - onlineSince > 25000) {
                keepAliveWarned = true;
                log("[dbg] Serwer nie wyslal keep-alive - ID pakietow moze sie roznic. Wyslij mi ta konsole.");
            }
            double px, py, pz;
            float pyaw, ppitch;
            boolean sendRot, moved = false;
            synchronized (posLock) {
                if (!havePos) return;
                double fwd = (forward ? 1 : 0) - (back ? 1 : 0);
                if (afkWalk > 0) {
                    fwd += afkWalk > 3 ? 1 : -1;
                    afkWalk--;
                }
                double lef = (left ? 1 : 0) - (right ? 1 : 0);
                if (fwd != 0 || lef != 0) {
                    double len = Math.hypot(fwd, lef);
                    double speed = 0.2158;
                    double rad = Math.toRadians(yaw);
                    x += (-Math.sin(rad) * fwd + Math.cos(rad) * lef) / len * speed;
                    z += (Math.cos(rad) * fwd + Math.sin(rad) * lef) / len * speed;
                    moved = true;
                }
                if (BotManager.isProtect() && !forward && !back && !left && !right && onGround) {
                    if (++afkTicks >= nextAfk) {
                        afkTicks = 0;
                        nextAfk = 300 + rnd.nextInt(500);
                        yaw += (rnd.nextFloat() - 0.5f) * 40f;
                        pitch = Math.max(-60f, Math.min(60f, pitch + (rnd.nextFloat() - 0.5f) * 10f));
                        afkWalk = 6;
                        if (rnd.nextInt(4) == 0) jumpRequested = true;
                    }
                }
                if (jumpRequested && onGround) {
                    vy = 0.42;
                    onGround = false;
                    groundY = y;
                }
                jumpRequested = false;
                if (!onGround) {
                    y += vy;
                    vy = (vy - 0.08) * 0.98;
                    moved = true;
                    if (y <= groundY && vy < 0) {
                        y = groundY;
                        vy = 0;
                        onGround = true;
                    }
                }
                sendRot = yaw != sentYaw || pitch != sentPitch;
                sentYaw = yaw;
                sentPitch = pitch;
                px = x;
                py = y;
                pz = z;
                pyaw = yaw;
                ppitch = pitch;
                ticksSinceSend++;
                if (!moved && !sendRot && ticksSinceSend < 20) return;
                ticksSinceSend = 0;
            }
            if (phase != Phase.PLAY) return;
            Out o = new Out();
            o.f64(px);
            o.f64(py);
            o.f64(pz);
            if (moved || sendRot) {
                o.f32(pyaw);
                o.f32(ppitch);
            }
            o.i8(onGround ? 1 : 0);
            synchronized (writeLock) {
                if (phase != Phase.PLAY) return;
                send(moved || sendRot ? S_POSROT : S_POS, o);
            }
        } catch (IOException e) {
            if (running) log("Blad: " + e.getMessage());
            running = false;
            closeQuietly();
        }
    }

    // ------------------------------------------------------------------ niski poziom

    private void log(String line) {
        String full = "[" + timeFmt.format(new Date()) + "] " + line;
        synchronized (log) {
            log.add(full);
            while (log.size() > 1000) log.remove(0);
        }
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private void shutdown() {
        running = false;
        closeQuietly();
        if (ticker != null) ticker.shutdownNow();
        if (state != State.DISCONNECTED) {
            log("Rozlaczono." + (lastKick.isEmpty() ? "" : " Powod: " + lastKick));
        }
        state = State.DISCONNECTED;
    }

    private boolean tracing() {
        return BotManager.isDebug() || System.currentTimeMillis() < traceUntil;
    }

    private void dbg(String line) {
        if (tracing()) log("[dbg] " + line);
    }

    private void noteDebug(int id, int size) {
        if (!tracing()) return;
        if (phase != Phase.PLAY) {
            dbg(phase + " pakiet 0x" + Integer.toHexString(id) + " (" + size + " B)");
        } else if (seenIds.size() < 60 && seenIds.add(id)) {
            dbg("PLAY nowy pakiet 0x" + Integer.toHexString(id) + " (" + size + " B)");
        }
    }

    /** Szuka rekordu SRV (_minecraft._tcp) tak jak robi to normalny klient. */
    private static String[] lookupSrv(String host) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            env.put("com.sun.jndi.dns.timeout.initial", "4000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            InitialDirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
            Attribute a = attrs.get("srv");
            if (a != null && a.size() > 0) {
                String[] parts = a.get(0).toString().split(" ");
                String target = parts[3];
                if (target.endsWith(".")) target = target.substring(0, target.length() - 1);
                return new String[]{target, parts[2]};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Buf readPacket() throws IOException {
        int len = 0;
        for (int i = 0; ; i++) {
            if (i > 4) throw new IOException("zly VarInt");
            int b = in.readUnsignedByte();
            len |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) break;
        }
        if (len < 0 || len > 8 * 1024 * 1024) throw new IOException("zla dlugosc pakietu: " + len);
        byte[] data = new byte[len];
        in.readFully(data);
        Buf b = new Buf(data);
        if (compressionThreshold >= 0) {
            int dataLen = b.varInt();
            if (dataLen != 0) {
                Inflater inf = new Inflater();
                try {
                    inf.setInput(b.rest());
                    byte[] dec = new byte[dataLen];
                    int n = 0;
                    while (n < dataLen) {
                        int r = inf.inflate(dec, n, dataLen - n);
                        if (r == 0 && (inf.finished() || inf.needsInput())) break;
                        n += r;
                    }
                    return new Buf(dec);
                } catch (DataFormatException e) {
                    throw new IOException("blad dekompresji", e);
                } finally {
                    inf.end();
                }
            }
        }
        return b;
    }

    private void send(int id, Out body) throws IOException {
        if (tracing() && phase != Phase.LOGIN) dbg(phase + " wyslano 0x" + Integer.toHexString(id));
        Out pkt = new Out();
        pkt.varInt(id);
        pkt.bytes(body.toBytes());
        byte[] payload = pkt.toBytes();
        Out frame = new Out();
        int th = compressionThreshold;
        if (th >= 0) {
            if (payload.length >= th) {
                Deflater d = new Deflater();
                d.setInput(payload);
                d.finish();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                while (!d.finished()) {
                    int n = d.deflate(buf);
                    bo.write(buf, 0, n);
                }
                d.end();
                frame.varInt(payload.length);
                frame.bytes(bo.toByteArray());
            } else {
                frame.varInt(0);
                frame.bytes(payload);
            }
        } else {
            frame.bytes(payload);
        }
        byte[] f = frame.toBytes();
        Out len = new Out();
        len.varInt(f.length);
        synchronized (writeLock) {
            if (out == null) throw new IOException("brak polaczenia");
            out.write(len.toBytes());
            out.write(f);
            out.flush();
        }
    }

    // ------------------------------------------------------------------ tekst (NBT)

    /** Czyta komponent tekstowy zapisany jako NBT (od 1.20.3) i zamienia na zwykly tekst. */
    private static String readText(Buf p) {
        int type = p.u8();
        return comp(nbt(p, type, 0));
    }

    private static Object nbt(Buf p, int type, int depth) {
        if (depth > 64) throw new IllegalStateException("NBT zbyt gleboki");
        switch (type) {
            case 1: return p.i8();
            case 2: return p.i16();
            case 3: return p.i32();
            case 4: return p.i64();
            case 5: return p.f32();
            case 6: return p.f64();
            case 7: p.skip(p.i32()); return "";
            case 8: return p.nbtString();
            case 9: {
                int t = p.u8();
                int n = p.i32();
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(nbt(p, t, depth + 1));
                return l;
            }
            case 10: {
                Map<String, Object> m = new HashMap<>();
                while (true) {
                    int t = p.u8();
                    if (t == 0) break;
                    String k = p.nbtString();
                    m.put(k, nbt(p, t, depth + 1));
                }
                return m;
            }
            case 11: p.skip(p.i32() * 4); return "";
            case 12: p.skip(p.i32() * 8); return "";
            default: throw new IllegalStateException("zly typ NBT: " + type);
        }
    }

    private static String comp(Object o) {
        if (o == null) return "";
        if (o instanceof String s) return s;
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder();
            for (Object e : l) sb.append(comp(e));
            return sb.toString();
        }
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder();
            if (m.get("text") != null) {
                sb.append(comp(m.get("text")));
            } else if (m.get("translate") != null) {
                String key = comp(m.get("translate"));
                List<String> args = new ArrayList<>();
                if (m.get("with") instanceof List<?> w) {
                    for (Object a : w) args.add(comp(a));
                }
                if (key.equals("chat.type.text") && args.size() >= 2) {
                    sb.append("<").append(args.get(0)).append("> ").append(args.get(1));
                } else {
                    sb.append(key);
                    if (!args.isEmpty()) sb.append(" ").append(args);
                }
            }
            if (m.get("text") == null && m.get("translate") == null) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (e.getValue() instanceof String v && !k.equals("color") && !k.equals("font")
                            && !k.equals("insertion") && !k.equals("type")) {
                        sb.append(v);
                    }
                }
            }
            if (m.get("extra") != null) sb.append(comp(m.get("extra")));
            return sb.toString();
        }
        return String.valueOf(o);
    }

    // ------------------------------------------------------------------ bufory

    static final class Buf {
        private final ByteBuffer bb;

        Buf(byte[] d) { bb = ByteBuffer.wrap(d); }

        int u8() { return bb.get() & 0xFF; }
        byte i8() { return bb.get(); }
        boolean bool() { return bb.get() != 0; }
        short i16() { return bb.getShort(); }
        int i32() { return bb.getInt(); }
        long i64() { return bb.getLong(); }
        float f32() { return bb.getFloat(); }
        double f64() { return bb.getDouble(); }
        void skip(int n) { bb.position(bb.position() + n); }
        int remaining() { return bb.remaining(); }

        int varInt() {
            int v = 0;
            for (int i = 0; ; i++) {
                if (i > 4) throw new IllegalStateException("zly VarInt");
                int b = bb.get() & 0xFF;
                v |= (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) return v;
            }
        }

        String string() {
            int n = varInt();
            byte[] s = new byte[n];
            bb.get(s);
            return new String(s, StandardCharsets.UTF_8);
        }

        String nbtString() {
            int n = bb.getShort() & 0xFFFF;
            byte[] s = new byte[n];
            bb.get(s);
            return new String(s, StandardCharsets.UTF_8);
        }

        byte[] rest() {
            byte[] r = new byte[bb.remaining()];
            bb.get(r);
            return r;
        }
    }

    static final class Out {
        private final ByteArrayOutputStream bo = new ByteArrayOutputStream();

        Out varInt(int v) {
            while ((v & ~0x7F) != 0) {
                bo.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            bo.write(v);
            return this;
        }

        Out u8(int v) { bo.write(v & 0xFF); return this; }
        Out i8(int v) { bo.write(v & 0xFF); return this; }
        Out bool(boolean v) { bo.write(v ? 1 : 0); return this; }
        Out u16(int v) { bo.write((v >> 8) & 0xFF); bo.write(v & 0xFF); return this; }
        Out i16(int v) { return u16(v); }
        Out i32(int v) { return bytes(ByteBuffer.allocate(4).putInt(v).array()); }
        Out i64(long v) { return bytes(ByteBuffer.allocate(8).putLong(v).array()); }
        Out f32(float v) { return bytes(ByteBuffer.allocate(4).putFloat(v).array()); }
        Out f64(double v) { return bytes(ByteBuffer.allocate(8).putDouble(v).array()); }

        Out string(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            varInt(b.length);
            return bytes(b);
        }

        Out bytes(byte[] b) {
            bo.write(b, 0, b.length);
            return this;
        }

        byte[] toBytes() { return bo.toByteArray(); }
    }
}
