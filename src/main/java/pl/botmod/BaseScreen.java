package pl.botmod;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Wspolna baza: male okno na srodku, w tle widac gre. */
public abstract class BaseScreen extends Screen {
    static final int PANEL = 0xF2121419, HEADER = 0xFF1B1E25, BORDER = 0xFF2A2D35, ACCENT = 0xFF5865F2;
    static final int TEXT = 0xFFE6E6E6, MUTED = 0xFF8A8F98, GREEN = 0xFF3BA55D, RED = 0xFFED4245, YELLOW = 0xFFFAA61A;
    static final int BTN = 0xFF272B33, BTN_HOVER = 0xFF353A45, SLOT = 0xFF2E3138;

    protected record Btn(String label, int x, int y, int w, int h, Runnable action, BooleanSupplier active,
                         BooleanSupplier visible) {}

    protected final Screen parent;
    private final int wantW, wantH;
    protected int px, py, pw, ph;
    protected final List<Btn> btns = new ArrayList<>();
    protected final List<TextFieldWidget> fields = new ArrayList<>();

    protected BaseScreen(String title, Screen parent, int w, int h) {
        super(Text.literal(title));
        this.parent = parent;
        this.wantW = w;
        this.wantH = h;
    }

    @Override
    protected void init() {
        btns.clear();
        fields.clear();
        pw = Math.min(wantW, width - 6);
        ph = Math.min(wantH, height - 6);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        build();
    }

    protected abstract void build();

    protected abstract void drawPanel(DrawContext ctx, int mx, int my);

    protected void drawTop(DrawContext ctx, int mx, int my) {}

    protected boolean onClick(double mx, double my, int button) { return false; }

    /** Pole tekstowe z wlasna ramka; (x,y,w) to prostokat ramki o wysokosci 20. */
    protected TextFieldWidget field(int x, int y, int w, String hint, int max, String value, Consumer<String> onChange) {
        TextFieldWidget t = new TextFieldWidget(textRenderer, x + 6, y + 6, w - 12, 14, Text.literal(hint));
        t.setDrawsBackground(false);
        t.setMaxLength(max);
        t.setText(value);
        t.setPlaceholder(Text.literal(hint).styled(s -> s.withColor(0x6B7078)));
        if (onChange != null) t.setChangedListener(onChange);
        addDrawableChild(t);
        fields.add(t);
        return t;
    }

    protected void btn(String label, int x, int y, int w, int h, Runnable action, BooleanSupplier active) {
        btns.add(new Btn(label, x, y, w, h, action, active, null));
    }

    protected void btnV(String label, int x, int y, int w, int h, Runnable action, BooleanSupplier active,
                        BooleanSupplier visible) {
        btns.add(new Btn(label, x, y, w, h, action, active, visible));
    }

    protected static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    protected void box(DrawContext ctx, int x, int y, int w, int h, int fill, int border) {
        ctx.fill(x, y, x + w, y + h, border);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    protected void text(DrawContext ctx, String s, int x, int y, int color) {
        ctx.drawTextWithShadow(textRenderer, s, x, y, color);
    }

    protected void centered(DrawContext ctx, String s, int cx, int y, int color) {
        ctx.drawTextWithShadow(textRenderer, s, cx - textRenderer.getWidth(s) / 2, y, color);
    }

    /** Kolorowa plakietka bota z pierwsza litera nicku (zeby latwo go rozpoznac). */
    protected void avatar(DrawContext ctx, int x, int y, int size, String name) {
        int[] pal = {0xFF5865F2, 0xFF3BA55D, 0xFFE67E22, 0xFFE91E63, 0xFF1ABC9C, 0xFF9B59B6, 0xFFF1C40F, 0xFFE74C3C};
        int c = pal[Math.floorMod(name.hashCode(), pal.length)];
        ctx.fill(x, y, x + size, y + size, c);
        String l = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        ctx.drawTextWithShadow(textRenderer, l, x + (size - textRenderer.getWidth(l)) / 2, y + (size - 8) / 2, 0xFFFFFFFF);
    }

    protected void drawBtns(DrawContext ctx, int mx, int my) {
        for (Btn b : btns) {
            if (b.visible != null && !b.visible.getAsBoolean()) continue;
            boolean hover = in(mx, my, b.x, b.y, b.w, b.h);
            boolean act = b.active != null && b.active.getAsBoolean();
            ctx.fill(b.x, b.y, b.x + b.w, b.y + b.h, act ? ACCENT : hover ? BTN_HOVER : BTN);
            centered(ctx, b.label, b.x + b.w / 2, b.y + (b.h - 8) / 2 + 0, TEXT);
        }
    }

    protected void drawFieldFrames(DrawContext ctx) {
        for (TextFieldWidget t : fields) {
            if (!t.visible) continue;
            box(ctx, t.getX() - 6, t.getY() - 6, t.getWidth() + 12, 20, 0xFF0D0E12, t.isFocused() ? ACCENT : BORDER);
        }
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0x55000000);
        box(ctx, px, py, pw, ph, PANEL, BORDER);
        drawPanel(ctx, mx, my);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        drawTop(ctx, mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (onClick(mx, my, button)) return true;
        for (Btn b : btns) {
            if (b.visible != null && !b.visible.getAsBoolean()) continue;
            if (in(mx, my, b.x, b.y, b.w, b.h)) {
                setFocused(null);
                b.action.run();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
