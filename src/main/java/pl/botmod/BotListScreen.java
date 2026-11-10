package pl.botmod;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import pl.botmod.bot.BotClient;
import pl.botmod.bot.BotManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Ekran 1: dodawanie botow i wybor bota, ktorego chcesz obslugiwac. */
public class BotListScreen extends BaseScreen {
    private static String nickText = "Bot";
    private static String ipText = "";
    private TextFieldWidget nickField, ipField;

    public BotListScreen(Screen parent) {
        super("Boty", parent, 280, 192);
    }

    @Override
    protected void build() {
        nickField = field(px + 8, py + 26, 72, "Nick", 16, nickText, s -> nickText = s);
        ipField = field(px + 84, py + 26, 100, "IP serwera", 120, ipText, s -> ipText = s);
        btn("Mój IP", px + 188, py + 26, 40, 20, this::fillMyIp, null);
        btn("Dodaj", px + 232, py + 26, 40, 20, this::addBot, null);
    }

    private int[] row(int i) {
        return new int[]{px + 8, py + 54 + i * 40, pw - 16, 36};
    }

    private int[] openRect(int[] r) { return new int[]{r[0] + r[2] - 98, r[1] + 9, 46, 18}; }
    private int[] stopRect(int[] r) { return new int[]{r[0] + r[2] - 50, r[1] + 9, 46, 18}; }

    private String myServerAddress() {
        if (client != null && client.getCurrentServerEntry() != null) return client.getCurrentServerEntry().address;
        return null;
    }

    private void fillMyIp() {
        String a = myServerAddress();
        if (a == null) {
            BotManager.log("Nie jesteś na żadnym serwerze - wpisz IP ręcznie.");
            return;
        }
        ipField.setText(a);
    }

    private void addBot() {
        String ip = ipText;
        if (ip.isBlank()) {
            String mine = myServerAddress();
            ip = mine == null ? "" : mine;
        }
        if (BotManager.addBot(nickText, ip)) saveConfig();
    }

    private void open(BotClient b) {
        client.setScreen(new BotScreen(b, this));
    }

    @Override
    public void tick() {
        // gdy serwer otworzy bota okno (np. menu z kompasu), przejdz do tego bota
        for (BotClient b : BotManager.bots()) {
            if (b.consumeWindowOpened()) {
                open(b);
                return;
            }
        }
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        List<BotClient> bots = BotManager.bots();
        for (int i = 0; i < bots.size(); i++) {
            int[] r = row(i);
            if (!in(mx, my, r[0], r[1], r[2], r[3])) continue;
            setFocused(null);
            BotClient b = bots.get(i);
            int[] st = stopRect(r);
            if (in(mx, my, st[0], st[1], st[2], st[3])) {
                if (b.getState() == BotClient.State.DISCONNECTED) BotManager.remove(b);
                else b.disconnect();
            } else {
                open(b);
            }
            return true;
        }
        return false;
    }

    @Override
    protected void drawPanel(DrawContext ctx, int mx, int my) {
        ctx.fill(px + 1, py + 1, px + pw - 1, py + 20, HEADER);
        text(ctx, "Boty", px + 8, py + 6, TEXT);
        String cnt = BotManager.bots().size() + "/" + BotManager.MAX_BOTS;
        text(ctx, cnt, px + pw - 8 - textRenderer.getWidth(cnt), py + 6, MUTED);
        drawFieldFrames(ctx);

        List<BotClient> bots = BotManager.bots();
        if (bots.isEmpty()) {
            centered(ctx, "Wpisz nick i IP, kliknij „Dodaj”.", px + pw / 2, py + 84, MUTED);
            centered(ctx, "Do 3 botów naraz.", px + pw / 2, py + 96, MUTED);
        }
        for (int i = 0; i < bots.size(); i++) {
            BotClient b = bots.get(i);
            int[] r = row(i);
            boolean hover = in(mx, my, r[0], r[1], r[2], r[3]);
            box(ctx, r[0], r[1], r[2], r[3], hover ? BTN_HOVER : HEADER, BORDER);
            int dot = switch (b.getState()) {
                case ONLINE -> GREEN;
                case CONNECTING, IDLE -> YELLOW;
                case DISCONNECTED -> RED;
            };
            avatar(ctx, r[0] + 6, r[1] + 6, 24, b.getName());
            text(ctx, textRenderer.trimToWidth(b.getName(), r[2] - 36 - 104), r[0] + 36, r[1] + 7, TEXT);
            String status = switch (b.getState()) {
                case ONLINE -> "Online";
                case CONNECTING, IDLE -> "Łączenie...";
                case DISCONNECTED -> "Rozłączony";
            };
            ctx.fill(r[0] + 36, r[1] + 22, r[0] + 40, r[1] + 26, dot);
            text(ctx, status, r[0] + 44, r[1] + 20, MUTED);
            int[] o = openRect(r), s = stopRect(r);
            ctx.fill(o[0], o[1], o[0] + o[2], o[1] + o[3], ACCENT);
            centered(ctx, "Otwórz", o[0] + o[2] / 2, o[1] + 5, TEXT);
            boolean hs = in(mx, my, s[0], s[1], s[2], s[3]);
            ctx.fill(s[0], s[1], s[0] + s[2], s[1] + s[3], hs ? RED : BTN);
            centered(ctx, b.getState() == BotClient.State.DISCONNECTED ? "Usuń" : "Rozłącz",
                    s[0] + s[2] / 2, s[1] + 5, TEXT);
        }
        drawBtns(ctx, mx, my);

        String sys = BotManager.lastSystem(6000);
        if (sys != null) text(ctx, textRenderer.trimToWidth(sys, pw - 16), px + 8, py + ph - 12, YELLOW);
        else text(ctx, "Zamknięcie okna nie rozłącza botów.", px + 8, py + ph - 12, MUTED);
    }

    // ------------------------------------------------------------------ config

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("botmod.txt");
    }

    public static void loadConfig() {
        try {
            Path p = configPath();
            if (Files.exists(p)) {
                List<String> l = Files.readAllLines(p);
                if (l.size() > 0) nickText = l.get(0);
                if (l.size() > 1) ipText = l.get(1);
            }
        } catch (IOException ignored) {
        }
    }

    private static void saveConfig() {
        try {
            Files.write(configPath(), List.of(nickText, ipText));
        } catch (IOException ignored) {
        }
    }
}
