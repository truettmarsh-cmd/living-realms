package livingrealms.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * A small immediate-mode toolkit: each frame the screen draws its widgets and registers their click areas; clicks are
 * matched against the last frame's areas. Hover glows are animated per widget id.
 */
public final class Imm {
    // palette: deep slate with warm gold
    public static final int BG = 0xF0121519, PANEL = 0xFF171B21, CARD = 0xFF1E232B, CARD_HI = 0xFF262C36, BORDER = 0xFF2C333E,
            LINE = 0xFF353D4A, TEXT = 0xFFE8E6E1, MUTED = 0xFF98A0AB, FAINT = 0xFF646C78, GOLD = 0xFFE8C15A, GOLD_DK = 0xFFB98E2E,
            GREEN = 0xFF6FCF7F, RED = 0xFFE56B6B, BLUE = 0xFF6AA8F0, ORANGE = 0xFFF0A050, PURPLE = 0xFFB48CF0;

    public enum Style { PRIMARY, SECONDARY, GHOST, DANGER, SUCCESS }

    record Hit(int x, int y, int w, int h, int cx0, int cy0, int cx1, int cy1, Runnable action, boolean sound) {}

    record Scroll(String id, int x, int y, int w, int h) {}

    public GuiGraphics g;
    /** GUI units per layout unit: below 1 while a screen lays itself out at a finer GUI scale (see {@link #fit}). */
    public float scale = 1;
    /** Off: screens always use the player's GUI scale (the screenshot tours use this to check the smallest layouts). */
    public static boolean fitEnabled = true;
    public Font font;
    public int mx, my;
    public float partial;
    private List<Hit> hits = new ArrayList<>(), building = new ArrayList<>();
    private final List<Scroll> scrolls = new ArrayList<>(), scrollsBuilding = new ArrayList<>();
    private final Map<String, Float> anim = new HashMap<>();
    private final Map<String, Float> scroll = new HashMap<>();
    private final Map<String, Float> scrollMax = new HashMap<>();
    private final Deque<int[]> clip = new ArrayDeque<>();
    private List<FormattedCharSequence> tooltip;
    private ItemStack tooltipStack = ItemStack.EMPTY;

    public void begin(GuiGraphics g, int mx, int my, float partial) {
        this.g = g;
        this.font = Minecraft.getInstance().font;
        this.mx = mx;
        this.my = my;
        this.partial = partial;
        building = new ArrayList<>();
        scrollsBuilding.clear();
        tooltip = null;
        tooltipStack = ItemStack.EMPTY;
    }

    public void end() {
        hits = building;
        scrolls.clear();
        scrolls.addAll(scrollsBuilding);
        if (!tooltipStack.isEmpty()) g.renderTooltip(font, tooltipStack, mx, my);
        else if (tooltip != null) g.renderTooltip(font, tooltip, mx, my);
    }

    public boolean click(double x, double y) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (x >= h.x && x < h.x + h.w && y >= h.y && y < h.y + h.h && x >= h.cx0 && x < h.cx1 && y >= h.cy0 && y < h.cy1) {
                if (h.sound) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 0.6f));
                h.action.run();
                return true;
            }
        }
        return false;
    }

    public boolean scroll(double x, double y, double delta) {
        for (int i = scrolls.size() - 1; i >= 0; i--) {
            Scroll s = scrolls.get(i);
            if (x >= s.x && x < s.x + s.w && y >= s.y && y < s.y + s.h) {
                float max = scrollMax.getOrDefault(s.id, 0f);
                if (max <= 0) continue; // nothing to scroll here: let an enclosing region have it
                scroll.put(s.id, Mth.clamp(scroll.getOrDefault(s.id, 0f) - (float) delta * 18, 0, max));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ primitives

    public boolean over(int x, int y, int w, int h) {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
        int[] c = clip.peek();
        return c == null || (mx >= c[0] && mx < c[2] && my >= c[1] && my < c[3]);
    }

    public void area(int x, int y, int w, int h, Runnable action) { area(x, y, w, h, action, true); }

    public void area(int x, int y, int w, int h, Runnable action, boolean sound) {
        int[] c = clip.peek();
        building.add(c == null ? new Hit(x, y, w, h, -99999, -99999, 99999, 99999, action, sound) : new Hit(x, y, w, h, c[0], c[1], c[2], c[3], action, sound));
    }

    /** 0..1, eased towards 1 while hovered. */
    public float hover(String id, boolean on) {
        float v = anim.getOrDefault(id, 0f);
        v = Mth.clamp(v + (on ? 0.25f : -0.15f), 0, 1);
        anim.put(id, v);
        return v * v * (3 - 2 * v);
    }

    public float ease(String id, float target, float speed) {
        float v = anim.getOrDefault(id, target);
        v += (target - v) * speed;
        anim.put(id, v);
        return v;
    }

    public void rect(int x, int y, int w, int h, int c) { g.fill(x, y, x + w, y + h, c); }

    public void round(int x, int y, int w, int h, int r, int c) { round(x, y, w, h, r, c, c); }

    /** Rounded rectangle with a vertical gradient. */
    public void round(int x, int y, int w, int h, int r, int top, int bottom) {
        r = Math.min(r, Math.min(w, h) / 2);
        for (int i = 0; i < h; i++) {
            int inset = 0;
            if (i < r) inset = insetFor(r, i);
            else if (i >= h - r) inset = insetFor(r, h - 1 - i);
            int c = h <= 1 ? top : lerp(top, bottom, i / (float) (h - 1));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, c);
        }
    }

    static int insetFor(int r, int i) {
        double dy = r - i - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
    }

    public void card(int x, int y, int w, int h) { card(x, y, w, h, CARD, BORDER); }

    public void card(int x, int y, int w, int h, int fill, int border) {
        round(x, y, w, h, 5, border);
        round(x + 1, y + 1, w - 2, h - 2, 4, brighten(fill, 6), fill);
    }

    public void shadow(int x, int y, int w, int h) {
        for (int i = 1; i <= 6; i++) round(x - i, y - i + 3, w + 2 * i, h + 2 * i, 6 + i, (0x0E - i * 2 << 24));
    }

    public void hline(int x, int y, int w, int c) { g.fill(x, y, x + w, y + 1, c); }

    public void vline(int x, int y, int h, int c) { g.fill(x, y, x + 1, y + h, c); }

    public void text(String s, int x, int y, int c) { g.drawString(font, s, x, y, c, false); }

    public void text(Component s, int x, int y, int c) { g.drawString(font, s, x, y, c, false); }

    public void textShadow(String s, int x, int y, int c) { g.drawString(font, s, x, y, c, true); }

    public void center(String s, int cx, int y, int c) { g.drawString(font, s, cx - font.width(s) / 2, y, c, false); }

    public void right(String s, int rx, int y, int c) { g.drawString(font, s, rx - font.width(s), y, c, false); }

    public void big(String s, int x, int y, int c, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, s, 0, 0, c, false);
        g.pose().popPose();
    }

    public int width(String s) { return font.width(s); }

    public String clip(String s, int w) {
        if (font.width(s) <= w) return s;
        return font.plainSubstrByWidth(s, w - font.width("…")) + "…";
    }

    /** Wraps text into lines; returns the height used. */
    public int wrap(String s, int x, int y, int w, int c) {
        int yy = y;
        for (FormattedCharSequence line : font.split(Component.literal(s), w)) {
            g.drawString(font, line, x, yy, c, false);
            yy += 10;
        }
        return yy - y;
    }

    public int wrapHeight(String s, int w) { return font.split(Component.literal(s), w).size() * 10; }

    public void item(ItemStack st, int x, int y) {
        g.renderItem(st, x, y);
    }

    public void item(Item it, int x, int y) { item(new ItemStack(it), x, y); }

    public void itemScaled(ItemStack st, int x, int y, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.renderItem(st, 0, 0);
        g.pose().popPose();
    }

    public void itemTip(ItemStack st, int x, int y) {
        item(st, x, y);
        if (over(x, y, 16, 16)) tooltipStack = st;
    }

    public void tip(String... lines) {
        List<FormattedCharSequence> l = new ArrayList<>();
        for (String s : lines) if (s != null && !s.isEmpty()) l.addAll(font.split(Component.literal(s), 220));
        if (l.isEmpty()) return;
        tooltip = l;
    }

    public void tip(List<String> lines) { tip(lines.toArray(new String[0])); }

    public void bar(int x, int y, int w, int h, float frac, int c0, int c1) {
        round(x, y, w, h, h / 2, 0xFF0D1014);
        int fw = (int) (w * Mth.clamp(frac, 0, 1));
        if (fw > 0) round(x, y, Math.max(fw, Math.min(h, w)), h, h / 2, brighten(c0, 25), c1);
    }

    public void pill(String s, int x, int y, int fill, int textColor) {
        int w = width(s) + 10;
        round(x, y, w, 12, 6, fill);
        text(s, x + 5, y + 2, textColor);
    }

    public int pillWidth(String s) { return width(s) + 10; }

    // ------------------------------------------------------------------ widgets

    public boolean button(String id, int x, int y, int w, int h, String label, Style style, boolean enabled, Runnable action) {
        boolean on = enabled && over(x, y, w, h);
        float hv = hover(id, on);
        int top, bot, tc, border;
        switch (style) {
            case PRIMARY -> { top = 0xFFF2CF6E; bot = 0xFFD4A63E; tc = 0xFF1A1408; border = 0xFF8C6A22; }
            case DANGER -> { top = 0xFFE07A7A; bot = 0xFFB84A4A; tc = 0xFFFFFFFF; border = 0xFF7A2A2A; }
            case SUCCESS -> { top = 0xFF7FD48C; bot = 0xFF4FA860; tc = 0xFF0C1A0E; border = 0xFF2E6A38; }
            case GHOST -> { top = 0x00000000; bot = 0x00000000; tc = TEXT; border = 0x00000000; }
            default -> { top = 0xFF323946; bot = 0xFF2A303B; tc = TEXT; border = 0xFF414A58; }
        }
        if (!enabled) { top = 0xFF262B33; bot = 0xFF22262D; tc = FAINT; border = 0xFF2C323B; }
        if (style == Style.GHOST) {
            if (hv > 0) round(x, y, w, h, 4, (int) (hv * 0x30) << 24 | 0xFFFFFF);
        } else {
            int lift = (int) (hv * 18);
            round(x, y, w, h, 4, border);
            round(x + 1, y + 1, w - 2, h - 2, 3, brighten(top, lift), brighten(bot, lift));
        }
        center(label, x + w / 2, y + (h - 8) / 2, tc);
        if (enabled) area(x, y, w, h, action);
        return on;
    }

    public boolean iconButton(String id, int x, int y, int w, int h, Item icon, String label, boolean selected, Runnable action) {
        boolean on = over(x, y, w, h);
        float hv = hover(id, on);
        if (selected) round(x, y, w, h, 4, 0xFF2E3542, 0xFF272D38);
        else if (hv > 0) round(x, y, w, h, 4, (int) (hv * 0x28) << 24 | 0xFFFFFF);
        if (selected) round(x, y + 3, 2, h - 6, 1, GOLD);
        item(icon, x + 4, y + (h - 16) / 2);
        text(label, x + 24, y + (h - 8) / 2, selected ? GOLD : on ? TEXT : MUTED);
        area(x, y, w, h, action);
        return on;
    }

    /** Segmented control; returns the index clicked via action. */
    public void segments(String id, int x, int y, int w, int h, String[] labels, int selected, java.util.function.IntConsumer action) {
        round(x, y, w, h, 5, 0xFF0F1216);
        int sw = w / labels.length;
        for (int i = 0; i < labels.length; i++) {
            int sx = x + i * sw;
            boolean on = over(sx, y, sw, h);
            float hv = hover(id + i, on);
            if (i == selected) round(sx + 1, y + 1, sw - 2, h - 2, 4, 0xFF3A4250, 0xFF313845);
            else if (hv > 0) round(sx + 1, y + 1, sw - 2, h - 2, 4, (int) (hv * 0x20) << 24 | 0xFFFFFF);
            center(clip(labels[i], sw - 4), sx + sw / 2, y + (h - 8) / 2, i == selected ? GOLD : MUTED);
            final int idx = i;
            area(sx, y, sw, h, () -> action.accept(idx));
        }
    }

    public void toggle(String id, int x, int y, boolean on, Runnable action) {
        float t = ease(id, on ? 1 : 0, 0.3f);
        round(x, y, 22, 12, 6, lerp(0xFF2C323C, 0xFF4FA860, t));
        round(x + 1 + (int) (t * 10), y + 1, 10, 10, 5, 0xFFF4F4F4);
        area(x, y, 22, 12, action);
    }

    /** Starts a scrolling, clipped region; returns the scroll offset to subtract from content y. */
    public int beginScroll(String id, int x, int y, int w, int h, int contentHeight) {
        float max = Math.max(0, contentHeight - h);
        scrollMax.put(id, max);
        float off = Mth.clamp(scroll.getOrDefault(id, 0f), 0, max);
        scroll.put(id, off);
        scrollsBuilding.add(new Scroll(id, x, y, w, h));
        pushClip(x, y, x + w, y + h);
        if (max > 0) {
            int bh = Math.max(16, (int) (h * h / (float) contentHeight));
            int by = y + (int) ((h - bh) * (off / max));
            round(x + w - 3, by, 2, bh, 1, 0x60FFFFFF);
        }
        return (int) off;
    }

    public void endScroll() { popClip(); }

    public void resetScroll(String id) { scroll.put(id, 0f); }

    public void pushClip(int x0, int y0, int x1, int y1) {
        int[] c = clip.peek();
        if (c != null) { x0 = Math.max(x0, c[0]); y0 = Math.max(y0, c[1]); x1 = Math.min(x1, c[2]); y1 = Math.min(y1, c[3]); }
        clip.push(new int[]{x0, y0, x1, y1});
        scissor(clip.peek());
    }

    public void popClip() {
        clip.pop();
        if (clip.isEmpty()) RenderSystem.disableScissor();
        else scissor(clip.peek());
    }

    /** Scissor in window pixels, so clipping stays right when the screen is drawn at a finer scale than the GUI's. */
    private void scissor(int[] c) {
        var win = Minecraft.getInstance().getWindow();
        double k = scale * win.getGuiScale();
        int x0 = (int) Math.round(c[0] * k), y0 = (int) Math.round(c[1] * k), x1 = (int) Math.round(c[2] * k), y1 = (int) Math.round(c[3] * k);
        RenderSystem.enableScissor(x0, win.getHeight() - Math.max(y0, y1), Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    /**
     * The scale a screen should draw at so a layout of at least {@code minW} × {@code minH} fits: 1 when the player's GUI
     * scale leaves room, otherwise the ratio of the largest smaller whole GUI scale that does (never below 2, so text
     * stays crisp and readable). Whole scales keep every font pixel on whole window pixels.
     */
    public static float fit(int minW, int minH) {
        if (!fitEnabled) return 1;
        var win = Minecraft.getInstance().getWindow();
        int gs = Math.max(1, (int) Math.round(win.getGuiScale())), s = gs;
        while (s > 2 && (win.getWidth() / s < minW || win.getHeight() / s < minH)) s--;
        return s / (float) gs;
    }

    // ------------------------------------------------------------------ colour

    public static int lerp(int a, int b, float t) {
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return (int) (aa + (ba - aa) * t) << 24 | (int) (ar + (br - ar) * t) << 16 | (int) (ag + (bg - ag) * t) << 8 | (int) (ab + (bb - ab) * t);
    }

    public static int brighten(int c, int amt) {
        int a = c >>> 24, r = Math.min(255, (c >> 16 & 255) + amt), g = Math.min(255, (c >> 8 & 255) + amt), b = Math.min(255, (c & 255) + amt);
        return a << 24 | r << 16 | g << 8 | b;
    }

    public static int alpha(int c, int a) { return (a & 255) << 24 | (c & 0xFFFFFF); }

    public static int opaque(int rgb) { return 0xFF000000 | rgb; }

    public static String num(double v) {
        if (Math.abs(v) >= 10000) return String.format(Locale.ROOT, "%.0fk", v / 1000);
        if (Math.abs(v) >= 1000) return String.format(Locale.ROOT, "%.1fk", v / 1000);
        if (Math.abs(v) < 10 && v != Math.floor(v)) return String.format(Locale.ROOT, "%.1f", v);
        return String.valueOf((int) Math.round(v));
    }

    public static void blend() { RenderSystem.enableBlend(); }
}
