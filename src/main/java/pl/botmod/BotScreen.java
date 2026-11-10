package pl.botmod;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import pl.botmod.bot.BotClient;
import pl.botmod.bot.BotManager;

import java.util.ArrayList;
import java.util.List;

/** Ekran 2: obsluga jednego bota (konsola / podglad / GUI / ruch). */
public class BotScreen extends BaseScreen {
    private static final String[] TABS = {"Konsola", "Podgląd", "GUI", "Ruch"};

    private final BotClient bot;
    private int tab = 0;
    private int scroll = 0;
    private boolean overlay;
    private ItemStack hovered;
    private TextFieldWidget cmdField;

    private record Cell(int idx, int x, int y, Object stack) {}

    private record Overlay(int px, int py, int pw, int ph, List<Cell> cells) {}

    public BotScreen(BotClient bot, Screen parent) {
        super(bot.getName(), parent, 320, 212);
        this.bot = bot;
        this.overlay = bot.getOpenWindow() != 0;
    }

    @Override
    protected void build() {
        btn("< Boty", px + 6, py + 4, 44, 14, this::close, null);
        btn("Rozłącz", px + pw - 56, py + 4, 50, 14, () -> {
            bot.disconnect();
        }, null);
        for (int i = 0; i < TABS.length; i++) {
            final int k = i;
            btn(TABS[i], px + 8 + i * 78, py + 25, 76, 16, () -> setTab(k), () -> tab == k);
        }
        int cx = px + 8, cy = py + 48;

        // --- konsola
        cmdField = field(cx, py + ph - 28, pw - 16 - 52 - 32 - 8, "Wiadomość lub /komenda", 256, "", null);
        btnV("Wyślij", px + pw - 8 - 52 - 32 - 4, py + ph - 28, 52, 20, this::sendCmd, null, () -> tab == 0);
        btnV("dbg", px + pw - 8 - 32, py + ph - 28, 32, 20,
                () -> BotManager.setDebug(!BotManager.isDebug()), BotManager::isDebug, () -> tab == 0);
        cmdField.visible = tab == 0;

        // --- podglad
        btnV("Oznacz (świeci)", cx, py + ph - 30, 96, 20, this::mark, null, () -> tab == 1);
        btnV("TP do bota", cx + 100, py + ph - 30, 96, 20, this::tpTo, null, () -> tab == 1);
        btnV("Obserwuj", cx + 200, py + ph - 30, 96, 20, this::observe, null, () -> tab == 1);

        // --- GUI
        int bx = px + 10 + 9 * 18 + 10, bw = pw - (bx - px) - 8;
        btnV("Prawy klik (PPM)", bx, cy, bw, 20, bot::useItem, null, () -> tab == 2);
        btnV("Wyrzuć wszystko", bx, cy + 24, bw, 20, bot::dropAll, null, () -> tab == 2);
        btnV("Zamknij okno serwera", bx, cy + 48, bw, 20, bot::closeWindow, null, () -> tab == 2);

        // --- ruch
        int mx0 = cx + 14, my0 = cy + 4;
        btnV("W", mx0 + 30, my0, 28, 24, () -> bot.setForward(!bot.isForward()), bot::isForward, () -> tab == 3);
        btnV("A", mx0, my0 + 28, 28, 24, () -> bot.setLeft(!bot.isLeft()), bot::isLeft, () -> tab == 3);
        btnV("S", mx0 + 30, my0 + 28, 28, 24, () -> bot.setBack(!bot.isBack()), bot::isBack, () -> tab == 3);
        btnV("D", mx0 + 60, my0 + 28, 28, 24, () -> bot.setRight(!bot.isRight()), bot::isRight, () -> tab == 3);
        btnV("Skok", mx0 + 110, my0, 56, 24, bot::jump, null, () -> tab == 3);
        btnV("Stop", mx0 + 110, my0 + 28, 56, 24, bot::stopMoving, null, () -> tab == 3);
        btnV("<<", mx0, my0 + 68, 40, 22, () -> bot.turn(-30f, 0f), null, () -> tab == 3);
        btnV(">>", mx0 + 44, my0 + 68, 40, 22, () -> bot.turn(30f, 0f), null, () -> tab == 3);
        btnV("Góra", mx0 + 110, my0 + 68, 40, 22, () -> bot.turn(0f, -15f), null, () -> tab == 3);
        btnV("Dół", mx0 + 154, my0 + 68, 40, 22, () -> bot.turn(0f, 15f), null, () -> tab == 3);
    }

    private void setTab(int t) {
        tab = t;
        cmdField.visible = t == 0;
        if (t != 0) setFocused(null);
    }

    private void sendCmd() {
        String t = cmdField.getText();
        if (t.isEmpty()) return;
        bot.say(t);
        cmdField.setText("");
    }

    private boolean inGame() {
        if (client == null || client.player == null) {
            BotManager.log("Wejdź swoim klientem na ten sam serwer co bot (to wymaga też OP).");
            return false;
        }
        return true;
    }

    /** Daje botowi efekt swiecenia, wiec widzisz go w grze nawet przez sciany. */
    private void mark() {
        if (!inGame()) return;
        client.player.networkHandler.sendChatCommand("effect give " + bot.getName() + " minecraft:glowing 3600 0 true");
        BotManager.log("Bot " + bot.getName() + " świeci się w grze.");
    }

    private void tpTo() {
        if (!inGame()) return;
        client.player.networkHandler.sendChatCommand("tp @s " + bot.getName());
    }

    private void observe() {
        if (client == null || client.player == null) {
            BotManager.log("Wejdź swoim klientem na ten sam serwer, żeby obserwować bota.");
            return;
        }
        client.player.networkHandler.sendChatCommand("gamemode spectator");
        client.player.networkHandler.sendChatCommand("spectate " + bot.getName());
        client.setScreen(null);
    }

    @Override
    public void tick() {
        if (bot.consumeWindowOpened()) overlay = true;
        if (overlay && bot.getOpenWindow() == 0) overlay = false;
    }

    private boolean typing() {
        return tab == 0 && cmdField.isFocused();
    }

    // ------------------------------------------------------------------ geometria

    private List<Cell> invCells() {
        Object[] inv = bot.getInventory();
        List<Cell> cells = new ArrayList<>();
        int ox = px + 10, oy = py + 48;
        for (int i = 5; i <= 8; i++) cells.add(new Cell(i, ox + (i - 5) * 18, oy, inv[i]));
        cells.add(new Cell(45, ox + 4 * 18 + 12, oy, inv[45]));
        for (int k = 0; k < 27; k++) cells.add(new Cell(9 + k, ox + (k % 9) * 18, oy + 22 + (k / 9) * 18, inv[9 + k]));
        for (int k = 0; k < 9; k++) cells.add(new Cell(36 + k, ox + k * 18, oy + 22 + 54 + 4, inv[36 + k]));
        return cells;
    }

    private Overlay layoutOverlay() {
        Object[] items = bot.getOpenItems();
        if (items == null) return null;
        int w = 9 * 18 + 24;
        int n = Math.max(0, items.length - 36);
        int rows = (n + 8) / 9;
        int h = 20 + rows * 18 + 6 + 54 + 4 + 18 + 22;
        int ox = (width - w) / 2, oy = Math.max(4, (height - h) / 2);
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) cells.add(new Cell(i, ox + 12 + (i % 9) * 18, oy + 20 + (i / 9) * 18, items[i]));
        int m0 = oy + 20 + rows * 18 + 6;
        for (int k = 0; k < 27 && n + k < items.length; k++)
            cells.add(new Cell(n + k, ox + 12 + (k % 9) * 18, m0 + (k / 9) * 18, items[n + k]));
        for (int k = 0; k < 9 && n + 27 + k < items.length; k++)
            cells.add(new Cell(n + 27 + k, ox + 12 + k * 18, m0 + 58, items[n + 27 + k]));
        return new Overlay(ox, oy, w, h, cells);
    }

    private int[] consoleRect() {
        return new int[]{px + 8, py + 46, pw - 16, ph - 46 - 36};
    }

    // ------------------------------------------------------------------ wejscie

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (overlay) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                overlay = false;
                bot.closeWindow();
            }
            return true;
        }
        if (typing() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            sendCmd();
            return true;
        }
        if (!typing() && handleKey(key, true)) return true;
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (!overlay && !typing()) handleKey(key, false);
        return super.keyReleased(key, scan, mods);
    }

    private boolean handleKey(int key, boolean down) {
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            if (down) bot.selectHotbar(key - GLFW.GLFW_KEY_1);
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_W -> bot.setForward(down);
            case GLFW.GLFW_KEY_S -> bot.setBack(down);
            case GLFW.GLFW_KEY_A -> bot.setLeft(down);
            case GLFW.GLFW_KEY_D -> bot.setRight(down);
            case GLFW.GLFW_KEY_SPACE -> {
                if (down) bot.jump();
            }
            case GLFW.GLFW_KEY_R -> {
                if (down) bot.useItem();
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (down) bot.turn(-15f, 0f);
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (down) bot.turn(15f, 0f);
            }
            case GLFW.GLFW_KEY_UP -> {
                if (down) bot.turn(0f, -10f);
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (down) bot.turn(0f, 10f);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (overlay) {
            Overlay o = layoutOverlay();
            if (o == null) {
                overlay = false;
                return true;
            }
            if (in(mx, my, o.px + o.pw - 18, o.py + 4, 14, 14)) {
                overlay = false;
                bot.closeWindow();
                return true;
            }
            for (Cell c : o.cells) {
                if (in(mx, my, c.x, c.y, 18, 18)) {
                    bot.clickWindow(c.idx, button == 1 ? 1 : 0, hasShiftDown() ? 1 : 0);
                    return true;
                }
            }
            return true;
        }
        if (tab == 2) {
            for (Cell c : invCells()) {
                if (!in(mx, my, c.x, c.y, 18, 18)) continue;
                setFocused(null);
                if (c.idx >= 36 && c.idx <= 44 && !hasShiftDown()) {
                    bot.selectHotbar(c.idx - 36);
                    if (button == 1) bot.useItem();
                } else {
                    bot.dropSlot(c.idx, hasShiftDown());
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        int[] c = consoleRect();
        if (tab == 0 && in(mx, my, c[0], c[1], c[2], c[3])) {
            scroll = Math.max(0, scroll + (int) Math.signum(v) * 3);
            return true;
        }
        return super.mouseScrolled(mx, my, h, v);
    }

    // ------------------------------------------------------------------ rysowanie

    @Override
    protected void drawPanel(DrawContext ctx, int mx, int my) {
        hovered = null;
        ctx.fill(px + 1, py + 1, px + pw - 1, py + 22, HEADER);
        int dot = switch (bot.getState()) {
            case ONLINE -> GREEN;
            case CONNECTING, IDLE -> YELLOW;
            case DISCONNECTED -> RED;
        };
        String name = bot.getName();
        int nx = px + pw / 2 - textRenderer.getWidth(name) / 2;
        avatar(ctx, nx - 20, py + 4, 14, name);
        int nw = textRenderer.getWidth(name);
        ctx.fill(nx + nw + 6, py + 9, nx + nw + 11, py + 14, dot);
        text(ctx, name, nx, py + 7, TEXT);

        switch (tab) {
            case 0 -> drawConsole(ctx);
            case 1 -> drawPreview(ctx);
            case 2 -> drawGui(ctx, mx, my);
            default -> drawMove(ctx);
        }
        if (tab == 0) drawFieldFrames(ctx);
        drawBtns(ctx, mx, my);
    }

    private void drawConsole(DrawContext ctx) {
        int[] c = consoleRect();
        box(ctx, c[0], c[1], c[2], c[3], 0xFF0D0E12, BORDER);
        int ax = c[0] + 4, ay = c[1] + 3, aw = c[2] - 8, ah = c[3] - 6;
        List<String> lines = bot.getLog();
        List<OrderedText> rows = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        for (String s : lines.subList(Math.max(0, lines.size() - 200), lines.size())) {
            int color = s.contains("[dbg]") ? MUTED : s.contains("] > ") ? 0xFF9DB1FF : TEXT;
            for (OrderedText o : textRenderer.wrapLines(Text.literal(s), aw)) {
                rows.add(o);
                cols.add(color);
            }
        }
        int visible = Math.max(1, ah / 10);
        scroll = Math.min(scroll, Math.max(0, rows.size() - visible));
        int end = rows.size() - scroll;
        int start = Math.max(0, end - visible);
        ctx.enableScissor(ax, ay, ax + aw, ay + ah);
        for (int k = start; k < end; k++) {
            ctx.drawTextWithShadow(textRenderer, rows.get(k), ax, ay + (k - start) * 10, cols.get(k));
        }
        ctx.disableScissor();
    }

    private void drawPreview(DrawContext ctx) {
        int cx = px + 10, cy = py + 50;
        if (bot.hasPosition()) {
            double[] p = bot.getPosition();
            text(ctx, String.format("Pozycja:  X %.1f   Y %.1f   Z %.1f", p[0], p[1], p[2]), cx, cy, TEXT);
            text(ctx, String.format("Kierunek:  yaw %.0f°   pitch %.0f°", p[3], p[4]), cx, cy + 12, TEXT);
        } else {
            text(ctx, "Brak pozycji (bot nie jest na serwerze).", cx, cy, MUTED);
        }
        text(ctx, "Slot w ręce:  " + (bot.getHeldSlot() + 1), cx, cy + 24, TEXT);
        String note = "Przyciski poniżej działają, gdy sam jesteś na tym serwerze i masz OP: "
                + "Oznacz = bot świeci się w grze, TP = teleport do bota, Obserwuj = widok z jego oczu (wyjście: Shift).";
        int y = cy + 44;
        for (OrderedText o : textRenderer.wrapLines(Text.literal(note), pw - 20)) {
            ctx.drawTextWithShadow(textRenderer, o, cx, y, MUTED);
            y += 10;
        }
    }

    private void drawGui(DrawContext ctx, int mx, int my) {
        for (Cell c : invCells()) {
            box(ctx, c.x, c.y, 18, 18, SLOT, BORDER);
            boolean hover = in(mx, my, c.x, c.y, 18, 18);
            if (c.idx >= 36 && c.idx <= 44 && bot.getHeldSlot() == c.idx - 36) {
                ctx.drawBorder(c.x, c.y, 18, 18, ACCENT);
            }
            if (c.stack instanceof ItemStack st && !st.isEmpty()) {
                ctx.drawItem(st, c.x + 1, c.y + 1);
                ctx.drawStackOverlay(textRenderer, st, c.x + 1, c.y + 1);
                if (hover) hovered = st;
            }
            if (hover) ctx.fill(c.x + 1, c.y + 1, c.x + 17, c.y + 17, 0x55FFFFFF);
        }
        int bx = px + 10 + 9 * 18 + 10;
        String hint = "Hotbar: klik = wybierz slot, PPM = prawy klik. Inne sloty: klik = wyrzuć 1, Shift = stack.";
        int y = py + 48 + 76;
        for (OrderedText o : textRenderer.wrapLines(Text.literal(hint), pw - (bx - px) - 8)) {
            ctx.drawTextWithShadow(textRenderer, o, bx, y, MUTED);
            y += 10;
        }
        if (bot.getOpenWindow() != 0) {
            text(ctx, "Serwer otworzył okno!", px + 10, py + ph - 20, YELLOW);
            // przycisk do ponownego otwarcia
        }
    }

    private void drawMove(DrawContext ctx) {
        int cx = px + 22, cy = py + 52;
        text(ctx, "Obrót", cx, cy + 56, MUTED);
        text(ctx, "Patrzenie", cx + 110, cy + 56, MUTED);
        text(ctx, "Klawiatura: W A S D, Spacja, strzałki (poza polem tekstowym).", px + 10, py + ph - 24, MUTED);
    }

    @Override
    protected void drawTop(DrawContext ctx, int mx, int my) {
        if (hovered != null && !overlay) ctx.drawItemTooltip(textRenderer, hovered, mx, my);
        if (!overlay) return;
        Overlay o = layoutOverlay();
        if (o == null) return;
        ctx.fill(0, 0, width, height, 0xB0000000);
        box(ctx, o.px, o.py, o.pw, o.ph, PANEL, ACCENT);
        text(ctx, "Okno serwera", o.px + 12, o.py + 6, TEXT);
        boolean hc = in(mx, my, o.px + o.pw - 18, o.py + 4, 14, 14);
        ctx.fill(o.px + o.pw - 18, o.py + 4, o.px + o.pw - 4, o.py + 18, hc ? RED : BTN);
        centered(ctx, "x", o.px + o.pw - 11, o.py + 7, TEXT);
        ItemStack tip = null;
        for (Cell c : o.cells) {
            box(ctx, c.x, c.y, 18, 18, SLOT, BORDER);
            boolean hover = in(mx, my, c.x, c.y, 18, 18);
            if (c.stack instanceof ItemStack st && !st.isEmpty()) {
                ctx.drawItem(st, c.x + 1, c.y + 1);
                ctx.drawStackOverlay(textRenderer, st, c.x + 1, c.y + 1);
                if (hover) tip = st;
            }
            if (hover) ctx.fill(c.x + 1, c.y + 1, c.x + 17, c.y + 17, 0x66FFFFFF);
        }
        text(ctx, textRenderer.trimToWidth("Klik = wybierz. Esc = zamknij", o.pw - 16), o.px + 8, o.py + o.ph - 14, MUTED);
        if (tip != null) ctx.drawItemTooltip(textRenderer, tip, mx, my);
    }
}
