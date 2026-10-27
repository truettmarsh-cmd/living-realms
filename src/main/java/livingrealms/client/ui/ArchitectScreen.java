package livingrealms.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.client.ArchitectView;
import livingrealms.client.ClientSetup;
import livingrealms.settlement.Planning;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.*;

import static livingrealms.client.ui.Imm.*;

/** The Architect view's panel and key legend over the free camera; the world shows through. */
public class ArchitectScreen extends Screen {
    static final int PW = 164;
    private final Imm ui = new Imm();
    private String cat = "ALL";
    private double drag;

    public ArchitectScreen() { super(Component.literal("Architect view")); }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void removed() { ArchitectView.screenClosed(); }

    static CompoundTag v() { return ArchitectView.view(); }

    static Map<Item, Integer> stock() {
        Map<Item, Integer> m = new HashMap<>();
        for (Tag t : v().getList("stock", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            m.put(BuiltInRegistries.ITEM.get(new ResourceLocation(c.getString("id"))), c.getInt("n"));
        }
        return m;
    }

    /** The same catalogue and order as the Build page. */
    List<BuildingType> types() {
        CompoundTag locks = v().getCompound("locks");
        List<BuildingType> out = new ArrayList<>();
        for (BuildingType t : BuildingCatalog.types().values()) if (cat.equals("ALL") || t.category.name().equals(cat)) out.add(t);
        out.sort(Comparator.comparing((BuildingType t) -> locks.contains(t.id)).thenComparing(t -> !t.procedural()).thenComparingInt(t -> t.minTier).thenComparing(t -> t.name));
        return out;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        ui.begin(g, mx, my, partial);
        int ph = height - 12, detailH = Math.min(150, ph / 2);
        panel(6, 6, PW, ph - detailH - 4);
        detail(6, 6 + ph - detailH, PW, detailH);
        banner();
        legend();
        if (ArchitectView.looking) { ui.rect(width / 2 - 4, height / 2, 9, 1, 0xC0FFFFFF); ui.rect(width / 2, height / 2 - 4, 1, 9, 0xC0FFFFFF); }
        else if (mx > PW + 10 && ArchitectView.hoveredPlot >= 0) {
            CompoundTag p = plot(ArchitectView.hoveredPlot);
            if (p != null) ui.tip(plotName(p) + " · " + stateLabel(p), "Click to manage");
        } else if (mx > PW + 10 && ArchitectView.tool != null && !ArchitectView.status.isEmpty()) ui.tip("✖ " + ArchitectView.status);
        ui.end();
    }

    void panel(int x, int y, int w, int h) {
        ui.round(x, y, w, h, 6, 0xF2121519);
        ui.round(x + 6, y + 7, 3, 9, 1, GOLD);
        ui.text("Architect view", x + 13, y + 7, GOLD);
        CompoundTag town = ArchitectView.townTag();
        ui.text(ui.clip(town == null ? "" : town.getString("name"), w - 20), x + 13, y + 18, MUTED);
        List<String> cats = new ArrayList<>(List.of("ALL"));
        for (Category c : Category.values()) if (c != Category.ROAD) cats.add(c.name());
        String label = cat.equals("ALL") ? "All buildings" : Category.valueOf(cat).label;
        ui.button("arch_cat", x + 6, y + 30, w - 12, 14, "◂ " + label + " ▸", Style.SECONDARY, true, () -> {
            int i = cats.indexOf(cat);
            cat = cats.get((i + (hasShiftDown() ? cats.size() - 1 : 1)) % cats.size());
            ui.resetScroll("arch_list");
        });
        if (ui.over(x + 6, y + 30, w - 12, 14)) ui.tip("Click to change category (shift-click goes back)");
        List<BuildingType> types = types();
        CompoundTag locks = v().getCompound("locks");
        int ly = y + 48, lh = h - 52;
        int off = ui.beginScroll("arch_list", x + 3, ly, w - 6, lh, (types.size() + 2) * 21);
        int ry = ly - off;
        ry = row(x, ry, w, null, Items.SPYGLASS, "Select & manage", "Click planned ghosts", 0x98A0AB, false);
        ry = row(x, ry, w, ArchitectView.ROAD, Items.DIRT_PATH, "Road", "Click point to point", Category.ROAD.color, false);
        for (BuildingType t : types) {
            boolean locked = locks.contains(t.id);
            ry = row(x, ry, w, t.id, null, t.name, locked ? locks.getString(t.id) : t.w + "×" + t.d + (t.procedural() ? " · Living Realms" : " · " + t.styleName), t.category.color, locked);
        }
        ui.endScroll();
    }

    int row(int x, int y, int w, String id, Item icon, String name, String sub, int color, boolean locked) {
        boolean sel = Objects.equals(id, ArchitectView.tool), on = ui.over(x + 4, y, w - 8, 20);
        if (sel) ui.round(x + 4, y, w - 8, 20, 4, 0xFF2E3542);
        else if (on) ui.round(x + 4, y, w - 8, 20, 4, 0x18FFFFFF);
        int tx = x + 13;
        if (icon != null) { ui.itemScaled(new ItemStack(icon), x + 7, y + 3, 0.75f); tx = x + 22; }
        else ui.round(x + 7, y + 3, 3, 14, 1, opaque(color));
        ui.text(ui.clip(name, w - (tx - x) - 8), tx, y + 2, locked ? FAINT : sel ? GOLD : TEXT);
        ui.big(ui.clip(sub, (int) ((w - (tx - x) - 8) / 0.8f)), tx, y + 12, locked ? 0xFFB06060 : FAINT, 0.8f);
        ui.area(x + 4, y, w - 8, 20, () -> ArchitectView.select(id));
        return y + 21;
    }

    void detail(int x, int y, int w, int h) {
        ui.round(x, y, w, h, 6, 0xF2121519);
        int cx = x + 8, cw = w - 16, yy = y + 7;
        String tool = ArchitectView.tool;
        if (ArchitectView.ROAD.equals(tool)) {
            ui.text("Road", cx, yy, TEXT);
            ui.text(ui.clip(BuildingCatalog.pretty(ArchitectView.roadMaterial) + " · " + ArchitectView.roadWidth + " wide", cw), cx, yy + 11, MUTED);
            List<String> mats = new ArrayList<>(Planning.ROAD_MATERIALS.keySet());
            ui.button("arch_mat", cx, yy + 24, cw, 14, "Material: " + BuildingCatalog.pretty(ArchitectView.roadMaterial), Style.SECONDARY, true,
                    () -> ArchitectView.roadMaterial = mats.get((mats.indexOf(ArchitectView.roadMaterial) + 1) % mats.size()));
            yy += 44;
            ui.wrap(ArchitectView.roadStart == null ? "Click where the road starts." : "Click where it ends; keep clicking to continue. Right-click to finish.", cx, yy, cw, FAINT);
            status(cx, y + h - 14, cw);
            return;
        }
        BuildingType t = tool == null ? null : BuildingCatalog.get(tool);
        if (t == null) { plotDetail(cx, yy, cw, y + h); return; }
        ui.text(ui.clip(t.name, cw), cx, yy, TEXT);
        String[] facing = {"south", "west", "north", "east"};
        ui.text(ui.clip(t.w + "×" + t.d + "×" + t.h + " · facing " + facing[ArchitectView.rot & 3]
                + (ArchitectView.yOff != 0 ? " · " + (ArchitectView.yOff > 0 ? "+" : "") + ArchitectView.yOff : ""), cw), cx, yy + 11, MUTED);
        yy += 24;
        Blueprint bp = ArchitectView.blueprint();
        if (bp != null) {
            Map<Item, Integer> stock = stock();
            List<Map.Entry<Item, Integer>> bill = new ArrayList<>(bp.bill().entrySet());
            bill.sort(Comparator.comparingInt(e -> -e.getValue()));
            int short_ = 0;
            for (var e : bill) if (stock.getOrDefault(e.getKey(), 0) < e.getValue()) short_++;
            ui.text(short_ == 0 ? "Materials · all in stock" : "Materials · " + short_ + " short", cx, yy, short_ == 0 ? GREEN : ORANGE);
            if (short_ > 0 && ui.over(cx, yy, cw, 10)) ui.tip("Builders take what the stockpile has and wait for the rest.");
            yy += 11;
            int cols = Math.max(1, cw / 50), bh = y + h - 30 - yy, rows = (bill.size() + cols - 1) / cols;
            int off = ui.beginScroll("arch_bill", cx - 2, yy, cw + 4, bh, rows * 17);
            for (int i = 0; i < bill.size(); i++) {
                var e = bill.get(i);
                int bx = cx + (i % cols) * 50, by = yy + (i / cols) * 17 - off;
                int has = stock.getOrDefault(e.getKey(), 0);
                ui.itemTip(new ItemStack(e.getKey()), bx, by);
                ui.big(num(has) + "/" + num(e.getValue()), bx + 17, by + 5, has >= e.getValue() ? GREEN : ORANGE, 0.75f);
            }
            ui.endScroll();
        } else ui.text("Loading blueprint…", cx, yy, FAINT);
        status(cx, y + h - 26, cw);
    }

    void status(int x, int y, int w) {
        String s = ArchitectView.status;
        if (!s.isEmpty()) ui.text(ui.clip("✖ " + s, w), x, y + (ArchitectView.ROAD.equals(ArchitectView.tool) ? 0 : 12), RED);
        else {
            if (!ArchitectView.warning.isEmpty()) ui.text(ui.clip("⚠ " + ArchitectView.warning, w), x, y, ORANGE);
            ui.text(ui.clip("✔ Click to plan it here", w), x, y + (ArchitectView.ROAD.equals(ArchitectView.tool) ? 0 : 12), GREEN);
        }
    }

    CompoundTag plot(int id) {
        for (Tag t : v().getList("plots", Tag.TAG_COMPOUND)) if (((CompoundTag) t).getInt("id") == id) return (CompoundTag) t;
        return null;
    }

    static String plotName(CompoundTag p) {
        if (p.getString("type").equals("road")) return "Road";
        BuildingType t = BuildingCatalog.get(p.getString("type"));
        return t == null ? "Building" : t.name;
    }

    static String stateLabel(CompoundTag p) {
        int total = p.getInt("total");
        int pct = total == 0 ? 0 : Math.min(100, p.getInt("placed") * 100 / total);
        return switch (p.getString("state")) {
            case "BUILT" -> "Standing";
            case "BUILDING" -> pct > 0 ? "Under construction · " + pct + "%" : "Planned";
            case "UPGRADING" -> "Being enlarged · " + pct + "%";
            case "REPAIRING" -> "Under repair · " + pct + "%";
            case "DEMOLISHING" -> "Being torn down";
            default -> p.getString("state");
        };
    }

    void plotDetail(int cx, int y, int cw, int bottom) {
        CompoundTag p = ArchitectView.selectedPlot < 0 ? null : plot(ArchitectView.selectedPlot);
        if (p == null || p.getString("state").equals("BUILT")) {
            int queued = 0;
            for (Tag t : v().getList("plots", Tag.TAG_COMPOUND)) if (!((CompoundTag) t).getString("state").equals("BUILT")) queued++;
            ui.text("Planning", cx, y, TEXT);
            ui.text(queued + " project" + (queued == 1 ? "" : "s") + " under way", cx, y + 11, MUTED);
            ui.wrap("Pick a building or Road above to plan it. Faint ghosts are planned projects: click one to move it up the queue or cancel it.", cx, y + 26, cw, FAINT);
            return;
        }
        int id = p.getInt("id");
        ui.text(ui.clip(plotName(p), cw), cx, y, TEXT);
        ui.text(ui.clip(stateLabel(p), cw), cx, y + 11, MUTED);
        int total = p.getInt("total");
        ui.bar(cx, y + 24, cw, 5, total == 0 ? 0 : p.getInt("placed") / (float) total, GOLD, GOLD_DK);
        ui.text("Priority " + p.getInt("prio"), cx, y + 34, FAINT);
        if (!p.getString("status").isEmpty()) ui.wrap(ui.clip(p.getString("status"), cw * 2), cx, y + 46, cw, ORANGE);
        if (p.getString("state").equals("DEMOLISHING")) return;
        int by = bottom - 44;
        ui.button("arch_up", cx, by, cw / 2 - 2, 16, "▲ Sooner", Style.SECONDARY, true, () -> priority(id, 1));
        ui.button("arch_down", cx + cw / 2 + 2, by, cw / 2 - 2, 16, "▼ Later", Style.SECONDARY, true, () -> priority(id, -1));
        ui.button("arch_cancel", cx, by + 19, cw, 16, "Cancel project", Style.DANGER, true, () -> cancel(id));
    }

    public void priority(int id, int delta) {
        CompoundTag d = new CompoundTag();
        d.putInt("plot", id);
        d.putInt("delta", delta);
        ArchitectView.act("priority", d);
    }

    public void cancel(int id) {
        CompoundTag d = new CompoundTag();
        d.putInt("plot", id);
        ArchitectView.act("demolish", d);
        ArchitectView.selectedPlot = -1;
    }

    void banner() {
        var p = Minecraft.getInstance().player;
        String s = "⚠ Your body is unguarded" + (p == null ? "" : " · ♥ " + (int) Math.ceil(p.getHealth()) + "/" + (int) p.getMaxHealth()) + " · any damage ends this view";
        int w = ui.width(s) + 16, x = PW + 12 + (width - PW - 12 - w) / 2;
        ui.round(x, 6, w, 14, 5, 0xC0301818);
        ui.text(s, x + 8, 9, 0xFFF0A0A0);
    }

    static String key(KeyMapping k) { return k.getTranslatedKeyMessage().getString(); }

    void legend() {
        var o = Minecraft.getInstance().options;
        String arrows = Arrays.stream(new int[]{GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_RIGHT})
                .mapToObj(k -> InputConstants.Type.KEYSYM.getOrCreate(k).getDisplayName().getString().replace(" Arrow", "")).reduce((a, b) -> a + "/" + b).orElse("");
        List<String[]> keys = new ArrayList<>();
        keys.add(new String[]{key(o.keyUp) + key(o.keyLeft) + key(o.keyDown) + key(o.keyRight), "fly"});
        keys.add(new String[]{key(o.keyJump) + "/" + key(o.keyShift), "up/down"});
        keys.add(new String[]{key(o.keySprint), "fast"});
        keys.add(new String[]{"Scroll", "speed " + (int) ArchitectView.SPEEDS[ArchitectView.speed]});
        keys.add(new String[]{"Right drag", "look"});
        if (ArchitectView.ROAD.equals(ArchitectView.tool)) {
            keys.add(new String[]{"Left click", ArchitectView.roadStart == null ? "start road" : "road to here"});
            keys.add(new String[]{key(ClientSetup.RAISE) + "/" + key(ClientSetup.LOWER), "width"});
            keys.add(new String[]{key(ClientSetup.ROTATE), "material"});
        } else if (ArchitectView.tool != null) {
            keys.add(new String[]{"Left click", "plan"});
            keys.add(new String[]{key(ClientSetup.ROTATE), "rotate"});
            keys.add(new String[]{key(ClientSetup.RAISE) + "/" + key(ClientSetup.LOWER), "raise/lower"});
            keys.add(new String[]{arrows, "nudge"});
        } else keys.add(new String[]{"Left click", "pick a ghost"});
        boolean holding = ArchitectView.tool != null || ArchitectView.selectedPlot >= 0;
        keys.add(new String[]{InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_ESCAPE).getDisplayName().getString()
                + (ClientSetup.ARCHITECT.isUnbound() ? "" : "/" + key(ClientSetup.ARCHITECT)), holding ? "deselect" : "leave"});
        int left = PW + 12, avail = width - left - 6, gap = 8;
        List<List<String[]>> lines = new ArrayList<>(List.of(new ArrayList<>()));
        int lw = 0;
        for (String[] k : keys) {
            int kw = chip(k, 0, 0, false) + gap;
            if (lw + kw > avail && !lines.get(lines.size() - 1).isEmpty()) { lines.add(new ArrayList<>()); lw = 0; }
            lines.get(lines.size() - 1).add(k);
            lw += kw;
        }
        int y = height - 6 - lines.size() * 16;
        for (List<String[]> line : lines) {
            int w = -gap;
            for (String[] k : line) w += chip(k, 0, 0, false) + gap;
            int x = left + (avail - w) / 2;
            ui.round(x - 6, y - 2, w + 12, 16, 5, 0xC0121519);
            for (String[] k : line) x += chip(k, x, y, true) + gap;
            y += 16;
        }
    }

    int chip(String[] k, int x, int y, boolean draw) {
        int kw = ui.width(k[0]) + 8;
        if (draw) {
            ui.round(x, y, kw, 12, 3, 0xFF3A4250);
            ui.text(k[0], x + 4, y + 2, GOLD);
            ui.text(k[1], x + kw + 4, y + 2, MUTED);
        }
        return kw + 4 + ui.width(k[1]);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && ui.click(x, y)) return true;
        if (x < PW + 6) return true;
        if (button == 0) ArchitectView.click();
        if (button == 1) { drag = 0; ArchitectView.setLooking(true); }
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button != 1 || !ArchitectView.looking) return false;
        var w = Minecraft.getInstance().getWindow();
        double sx = w.getScreenWidth() / (double) w.getGuiScaledWidth(), sy = w.getScreenHeight() / (double) w.getGuiScaledHeight();
        drag += Math.abs(dx) + Math.abs(dy);
        ArchitectView.look(dx * sx, dy * sy);
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == 1 && ArchitectView.looking) {
            ArchitectView.setLooking(false);
            // a right click without looking around steps back
            if (drag < 3) {
                if (ArchitectView.roadStart != null) ArchitectView.roadStart = null;
                else if (ArchitectView.tool != null) ArchitectView.select(null);
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (x < PW + 6) return ui.scroll(x, y, delta);
        ArchitectView.speed = Math.max(0, Math.min(ArchitectView.SPEEDS.length - 1, ArchitectView.speed + (delta > 0 ? 1 : -1)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean road = ArchitectView.ROAD.equals(ArchitectView.tool), building = ArchitectView.tool != null && !road;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (ArchitectView.roadStart != null) ArchitectView.roadStart = null;
            else if (ArchitectView.tool != null || ArchitectView.selectedPlot >= 0) ArchitectView.select(null);
            else ArchitectView.exit(null);
        } else if (ClientSetup.ARCHITECT.matches(key, scan)) ArchitectView.exit(null);
        else if (ClientSetup.ROTATE.matches(key, scan)) {
            if (building) ArchitectView.rot = (ArchitectView.rot + 1) & 3;
            else if (road) {
                List<String> mats = new ArrayList<>(Planning.ROAD_MATERIALS.keySet());
                ArchitectView.roadMaterial = mats.get((mats.indexOf(ArchitectView.roadMaterial) + 1) % mats.size());
            }
        } else if (ClientSetup.RAISE.matches(key, scan) || ClientSetup.LOWER.matches(key, scan)) {
            int d = ClientSetup.RAISE.matches(key, scan) ? 1 : -1;
            if (building) ArchitectView.yOff = Math.max(-16, Math.min(16, ArchitectView.yOff + d));
            else if (road) ArchitectView.roadWidth = Math.max(1, Math.min(7, ArchitectView.roadWidth + d));
        } else if (building && key == GLFW.GLFW_KEY_UP) ArchitectView.nudge(1, 0);
        else if (building && key == GLFW.GLFW_KEY_DOWN) ArchitectView.nudge(-1, 0);
        else if (building && key == GLFW.GLFW_KEY_RIGHT) ArchitectView.nudge(0, 1);
        else if (building && key == GLFW.GLFW_KEY_LEFT) ArchitectView.nudge(0, -1);
        return true;
    }
}
