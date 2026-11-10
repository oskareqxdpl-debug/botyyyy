package pl.botmod.bot;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Lista botow (max 3) i wspolne ustawienia. */
public final class BotManager {
    public static final int MAX_BOTS = 3;

    /** Dekoder przedmiotow dostarczany przez mod (korzysta z klas Minecrafta). */
    public interface SlotDecoder {
        Object[] decodeContent(byte[] data) throws Exception;

        Object decodeSlot(byte[] data) throws Exception;
    }

    private static final List<BotClient> BOTS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bot-scheduler");
        t.setDaemon(true);
        return t;
    });
    private static volatile SlotDecoder slotDecoder;
    private static volatile boolean debug = false;
    private static volatile String lastSystem = "";
    private static volatile long lastSystemAt = 0;
    private static long nextConnectAt = 0;

    private BotManager() {}

    public static List<BotClient> bots() { return BOTS; }
    public static void setSlotDecoder(SlotDecoder d) { slotDecoder = d; }
    public static SlotDecoder slotDecoder() { return slotDecoder; }
    public static boolean isDebug() { return debug; }
    public static void setDebug(boolean v) { debug = v; }
    public static boolean isProtect() { return true; }

    public static void log(String line) {
        lastSystem = line;
        lastSystemAt = System.currentTimeMillis();
    }

    /** Ostatni komunikat systemowy, jesli jest swiezy. */
    public static String lastSystem(long maxAgeMs) {
        return System.currentTimeMillis() - lastSystemAt <= maxAgeMs ? lastSystem : null;
    }

    public static boolean validNick(String nick) {
        return nick.matches("[A-Za-z0-9_]{3,16}");
    }

    /** Dodaje jednego bota. Zwraca true, jesli sie udalo. */
    public static synchronized boolean addBot(String nick, String address) {
        nick = nick.trim();
        if (BOTS.size() >= MAX_BOTS) {
            log("Maksymalnie " + MAX_BOTS + " boty. Usun jakiegos (x).");
            return false;
        }
        if (!validNick(nick)) {
            log("Zly nick (3-16 znakow: litery, cyfry, _).");
            return false;
        }
        String host = address.trim().replace("http://", "").replace("https://", "");
        if (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) {
            log("Wpisz IP serwera.");
            return false;
        }
        int port = 0; // 0 = automatycznie (rekord SRV albo 25565)
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(host.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                log("Zly adres.");
                return false;
            }
            host = host.substring(0, colon).trim();
        }
        for (BotClient b : BOTS) {
            if (b.getName().equalsIgnoreCase(nick)) {
                log("Bot " + nick + " juz istnieje.");
                return false;
            }
        }
        BotClient bot = new BotClient(nick, host, port);
        BOTS.add(bot);
        // serwery blokuja zbyt szybkie logowania z jednego IP - odstep ok. 4 s
        long now = System.currentTimeMillis();
        long at = Math.max(now, nextConnectAt);
        nextConnectAt = at + 4200;
        long delay = at - now;
        if (delay > 500) log("Bot " + nick + " wejdzie za " + (delay / 1000 + 1) + " s (limit serwera).");
        SCHEDULER.schedule(bot::connect, delay, TimeUnit.MILLISECONDS);
        return true;
    }

    public static void remove(BotClient bot) {
        bot.disconnect();
        BOTS.remove(bot);
    }
}
