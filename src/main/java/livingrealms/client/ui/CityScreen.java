package livingrealms.client.ui;

import livingrealms.Registry;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.client.ClientState;
import livingrealms.client.Placement;
import livingrealms.military.Stance;
import livingrealms.military.UnitType;
import livingrealms.net.Net;
import livingrealms.research.ResearchTree;
import livingrealms.settlement.Job;
import livingrealms.settlement.Tier;
import livingrealms.smithing.Piece;
import livingrealms.smithing.Smithing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.*;

import static livingrealms.client.ui.Imm.*;

/** The City Manager: every part of running a settlement, in one window. */
public class CityScreen extends Screen {
    enum Tab {
        OVERVIEW("Overview", Items.BELL), MAP("City Map", Items.FILLED_MAP), BUILD("Build", Items.BRICKS), PROJECTS("Projects", Items.SCAFFOLDING),
        STORAGE("Stockpile", Items.CHEST), RESEARCH("Research", Items.ENCHANTED_BOOK), MILITARY("Military", Items.IRON_SWORD), FORGE("Blacksmith", Items.ANVIL);
        final String label;
        final Item icon;
        Tab(String label, Item icon) { this.label = label; this.icon = icon; }
    }

    private static Tab lastTab = Tab.OVERVIEW;
    private Tab tab;
    private final Imm ui = new Imm();
    private EditBox search;
    private int px, py, pw, ph, cx, cy, cw, ch;
    private long openedAt;

    // selections
    private String selType;
    private String catFilter = "ALL", styleFilter = "ALL";
    private int selPlot = -1;
    private String selResearch;
    private int milTab;
    private String forgePiece = "SET", forgePattern;
    private int forgeQty = 1, trainQty = 1;
    private UnitType selUnit = UnitType.FOOTMAN;
    private String roadMaterial = "path";
    private int roadWidth = 3;
    // map and research canvas
    private float mapZoom = 1, mapPanX, mapPanY, resPanX, resPanY;
    private boolean dragMap, dragRes;
    private int mapX, mapY, mapW, mapH, resX, resY, resW, resH;

    public CityScreen(String tab) {
        super(Component.literal("City Manager"));
        this.tab = switch (tab) {
            case "build" -> Tab.BUILD;
            case "forge" -> Tab.FORGE;
            case "military" -> Tab.MILITARY;
            default -> lastTab;
        };
    }

    // ------------------------------------------------------------------ view access

    static CompoundTag v() { return ClientState.view == null ? new CompoundTag() : ClientState.view; }

    static boolean mine() { return v().getBoolean("mine"); }

    static ListTag list(String k) { return v().getList(k, Tag.TAG_COMPOUND); }

    static List<String> strings(String k) {
        List<String> out = new ArrayList<>();
        v().getList(k, Tag.TAG_STRING).forEach(t -> out.add(t.getAsString()));
        return out;
    }

    static Map<Item, Integer> stock() {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (Tag t : list("stock")) {
            CompoundTag c = (CompoundTag) t;
            m.put(BuiltInRegistries.ITEM.get(new ResourceLocation(c.getString("id"))), c.getInt("n"));
        }
        return m;
    }

    static int have(Map<Item, Integer> stock, String ing) {
        if (ing.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(net.minecraft.core.registries.Registries.ITEM, new ResourceLocation(ing.substring(1)));
            int n = 0;
            for (var e : stock.entrySet()) if (e.getKey().builtInRegistryHolder().is(tag)) n += e.getValue();
            return n;
        }
        return stock.getOrDefault(BuiltInRegistries.ITEM.get(new ResourceLocation(ing)), 0);
    }

    static ItemStack icon(String ing) {
        if (ing.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(net.minecraft.core.registries.Registries.ITEM, new ResourceLocation(ing.substring(1)));
            return BuiltInRegistries.ITEM.getTag(tag).flatMap(h -> h.stream().findFirst()).map(h -> new ItemStack(h.value())).orElse(new ItemStack(Items.BARRIER));
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(ing)));
    }

    static String ingName(String ing) {
        if (ing.startsWith("#")) return BuildingCatalog.pretty(new ResourceLocation(ing.substring(1)).getPath());
        return BuiltInRegistries.ITEM.get(new ResourceLocation(ing)).getDescription().getString();
    }

    void act(String action, CompoundTag data) {
        if (!v().hasUUID("id")) return;
        CompoundTag t = new CompoundTag();
        t.putUUID("id", v().getUUID("id"));
        t.putString("action", action);
        t.put("data", data);
        Net.toServer("action", t);
    }

    void act(String action) { act(action, new CompoundTag()); }

    static CompoundTag tag(Object... kv) {
        CompoundTag t = new CompoundTag();
        for (int i = 0; i < kv.length; i += 2) {
            String k = (String) kv[i];
            Object o = kv[i + 1];
            if (o instanceof Integer n) t.putInt(k, n);
            else if (o instanceof Boolean b) t.putBoolean(k, b);
            else if (o instanceof Long l) t.putLong(k, l);
            else t.putString(k, String.valueOf(o));
        }
        return t;
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void init() {
        pw = Math.min(width - 12, 500);
        ph = Math.min(height - 12, 310);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cx = px + 112;
        cy = py + 10;
        cw = pw - 122;
        ch = ph - 20;
        search = new EditBox(font, 0, 0, 110, 14, Component.literal("Search"));
        search.setHint(Component.literal("Search…").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        search.setBordered(false);
        search.setMaxLength(40);
        addRenderableWidget(search);
        openedAt = System.currentTimeMillis();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        Net.toServer("close", new CompoundTag());
        lastTab = tab == Tab.BUILD ? Tab.OVERVIEW : tab;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        ui.begin(g, mx, my, partial);
        float open = Math.min(1, (System.currentTimeMillis() - openedAt) / 160f);
        g.pose().pushPose();
        g.pose().translate(0, (1 - open) * 8, 0);
        ui.shadow(px, py, pw, ph);
        ui.round(px, py, pw, ph, 8, 0xFF2A313B);
        ui.round(px + 1, py + 1, pw - 2, ph - 2, 7, 0xFF181C22, 0xFF13161B);
        sidebar();
        boolean showSearch = tab == Tab.BUILD || tab == Tab.STORAGE;
        search.visible = showSearch;
        try {
            switch (tab) {
                case OVERVIEW -> overview();
                case MAP -> map();
                case BUILD -> build();
                case PROJECTS -> projects();
                case STORAGE -> storage();
                case RESEARCH -> research();
                case MILITARY -> military();
                case FORGE -> forge();
            }
        } catch (Exception e) {
            ui.text("Something went wrong drawing this page: " + e, cx, cy + 20, RED);
        }
        g.pose().popPose();
        super.render(g, mx, my, partial);
        ui.end();
    }

    void sidebar() {
        int x = px + 8, y = py + 10, w = 96;
        int tier = v().getInt("tier");
        int crest = tierColor(tier);
        ui.round(x, y, 28, 28, 6, brighten(crest, 30), crest);
        String name = v().getString("name");
        ui.center(name.isEmpty() ? "?" : name.substring(0, 1), x + 14, y + 10, 0xFF1A1408);
        ui.text(ui.clip(name, w - 34), x + 34, y + 3, TEXT);
        ui.text(Tier.of(Math.max(1, tier)).label, x + 34, y + 15, crest);
        if (v().getBoolean("alarm")) ui.pill("ALARM", x + 34 + ui.width(Tier.of(Math.max(1, tier)).label) + 4, y + 13, 0xFFB03030, 0xFFFFFFFF);
        y += 36;
        ui.hline(x, y, w, LINE);
        y += 6;
        for (Tab t : Tab.values()) {
            final Tab tt = t;
            ui.iconButton("nav" + t, x - 2, y, w + 4, 20, t.icon, t.label, tab == t, () -> switchTab(tt));
            y += 21;
        }
        // footer: quick figures
        int fy = py + ph - 46;
        ui.hline(x, fy - 6, w, LINE);
        ui.item(Items.PLAYER_HEAD, x, fy - 2);
        ui.text(v().getInt("pop") + " / " + v().getInt("housing"), x + 20, fy + 2, TEXT);
        double happy = v().getDouble("happy");
        ui.text("Mood", x, fy + 18, MUTED);
        ui.bar(x + 30, fy + 19, w - 30, 6, (float) happy / 100f, moodColor(happy), moodColor(happy));
        ui.text(v().getBoolean("ai") ? "Independent town" : "Lord " + v().getString("owner"), x, fy + 30, FAINT);
    }

    void switchTab(Tab t) {
        tab = t;
        search.setValue("");
        if (t == Tab.FORGE) milTab = 2;
    }

    static int tierColor(int tier) {
        return switch (tier) {
            case 1 -> 0xFF9AA3AD;
            case 2 -> 0xFF8CC084;
            case 3 -> 0xFF6AA8F0;
            case 4 -> 0xFFB48CF0;
            case 5 -> 0xFFE8C15A;
            default -> 0xFFF07A50;
        };
    }

    static int moodColor(double h) { return h >= 65 ? GREEN : h >= 40 ? GOLD : RED; }

    void header(String title, String sub) { header(title, sub, cw); }

    /** Title and subtitle, the subtitle clipped to room so it never runs under controls on the right. */
    void header(String title, String sub, int room) {
        ui.big(title, cx, cy, TEXT, 1.4f);
        if (sub != null) ui.text(ui.clip(sub, room - 4), cx, cy + 15, MUTED);
    }

    // ------------------------------------------------------------------ overview

    void overview() {
        String style = BuildingCatalog.pretty(new ResourceLocation(v().getString("style").isEmpty() ? "minecraft:plains" : v().getString("style")).getPath());
        header(v().getString("name"), Tier.of(v().getInt("tier")).label + " · " + style + (v().getBoolean("ai") ? " · independent" : " · held by " + v().getString("owner")));
        int y = cy + 30, tw = (cw - 9) / 4;
        int pop = v().getInt("pop"), housing = v().getInt("housing");
        tile(cx, y, tw, "People", pop + " / " + housing, (float) Math.max(0, v().getDouble("growth")), BLUE,
                pop >= housing ? "Build houses to grow" : v().getBoolean("starving") ? "Starving" : "Growing", Items.PLAYER_HEAD);
        double happy = v().getDouble("happy");
        tile(cx + tw + 3, y, tw, "Happiness", (int) happy + "%", (float) happy / 100, moodColor(happy), "→ " + (int) v().getDouble("happyTarget") + "%", Items.POPPY);
        if (ui.over(cx + tw + 3, y, tw, 44)) {
            List<String> why = new ArrayList<>(List.of("Happiness drifts towards " + (int) v().getDouble("happyTarget") + "%", "Base 50"));
            why.addAll(strings("happyWhy"));
            ui.tip(why);
        }
        double food = v().getDouble("food"), need = v().getDouble("foodNeed");
        tile(cx + 2 * (tw + 3), y, tw, "Food / day", num(food) + " / " + num(need), (float) (need == 0 ? 1 : food / need), food >= need ? GREEN : ORANGE,
                v().getBoolean("starving") ? "Starving!" : food >= need ? "Surplus" : "Living off stores", Items.BREAD);
        Map<Item, Integer> stock = stock();
        tile(cx + 3 * (tw + 3), y, tw, "Treasury", num(stock.getOrDefault(Items.EMERALD, 0)), 0, GOLD, "+" + num(v().getDouble("emeralds")) + " / day", Items.EMERALD);
        y += 50;
        // rank
        int rw = (int) (cw * 0.55), rh = 104;
        ui.card(cx, y, rw, rh);
        Tier cur = Tier.of(v().getInt("tier")), next = cur.next();
        ui.text("Rank", cx + 8, y + 7, MUTED);
        if (next == null) {
            ui.big(cur.label, cx + 8, y + 20, GOLD, 1.3f);
            ui.text("The realm can grow no grander.", cx + 8, y + 40, MUTED);
        } else {
            ui.big(cur.label + "  →  " + next.label, cx + 8, y + 19, TEXT, 1.15f);
            List<String> blockers = strings("blockers");
            int ly = y + 34;
            String[][] reqs = {
                    {next.population + " people", String.valueOf(pop >= next.population)},
                    {next.buildings + " buildings", String.valueOf(v().getInt("buildings") >= next.buildings)},
                    {next.research == null ? null : "Research " + researchName(next.research), String.valueOf(next.research == null || strings("research").contains(next.research))},
                    {v().getBoolean("ai") ? null : "Finished Town Hall", String.valueOf(v().getBoolean("townHall"))},
                    {v().getBoolean("ai") ? null : next.emeralds + " emeralds", String.valueOf(stock.getOrDefault(Items.EMERALD, 0) >= next.emeralds)}};
            for (String[] r : reqs) {
                if (r[0] == null) continue;
                boolean ok = Boolean.parseBoolean(r[1]);
                ui.text(ok ? "✔" : "✖", cx + 10, ly, ok ? GREEN : RED);
                ui.text(r[0], cx + 22, ly, ok ? TEXT : MUTED);
                ly += 11;
            }
            if (mine()) ui.button("advance", cx + rw - 82, y + rh - 22, 74, 16, "Claim rank", Style.PRIMARY, blockers.isEmpty(), () -> act("tier"));
        }
        ui.text("Land: " + v().getInt("radius") + " blocks", cx + rw - 82, y + 7, FAINT);
        // taxes
        int tx = cx + rw + 6, txw = cw - rw - 6;
        ui.card(tx, y, txw, 40);
        ui.text("Taxes", tx + 8, y + 6, MUTED);
        ui.segments("tax", tx + 6, y + 19, txw - 12, 15, livingrealms.settlement.Simulation.TAX_NAMES, v().getInt("tax"),
                i -> { if (mine()) act("tax", tag("level", i)); });
        if (ui.over(tx, y, txw, 40)) ui.tip("Taxes bring in emeralds per settler each day.", "None +6 mood · Low ±0 · Normal −8 · High −20 (needs Taxation)");
        // jobs
        int jy = y + 44, jh = rh - 44;
        ui.card(tx, jy, txw, jh);
        ui.text("Workforce", tx + 8, jy + 6, MUTED);
        ui.right(v().getInt("unemployed") + " idle", tx + txw - 8, jy + 6, FAINT);
        CompoundTag jobs = v().getCompound("jobs");
        List<String> keys = new ArrayList<>(jobs.getAllKeys());
        int off = ui.beginScroll("jobs", tx + 4, jy + 17, txw - 8, jh - 20, keys.size() * 11);
        int yy = jy + 18 - off;
        for (String k : keys) {
            int[] a = jobs.getIntArray(k);
            Job j = Job.valueOf(k);
            ui.text(j.label, tx + 8, yy, TEXT);
            ui.right(a[0] + "/" + a[1], tx + txw - 10, yy, a[0] < a[1] ? ORANGE : MUTED);
            yy += 11;
        }
        ui.endScroll();
        y += rh + 6;
        // chronicle
        int lh = cy + ch - y;
        ui.card(cx, y, cw, lh);
        ui.text("Chronicle", cx + 8, y + 6, MUTED);
        List<String> log = strings("log");
        int off2 = ui.beginScroll("log", cx + 4, y + 18, cw - 8, lh - 22, log.size() * 11);
        int ly = y + 19 - off2;
        for (int i = 0; i < log.size(); i++) {
            ui.round(cx + 10, ly + 3, 3, 3, 1, i == 0 ? GOLD : FAINT);
            ui.text(ui.clip(log.get(i), cw - 30), cx + 18, ly, i == 0 ? TEXT : MUTED);
            ly += 11;
        }
        ui.endScroll();
    }

    void tile(int x, int y, int w, String label, String value, float frac, int color, String sub, Item icon) {
        ui.card(x, y, w, 44);
        ui.item(icon, x + w - 21, y + 5);
        ui.text(label, x + 7, y + 6, MUTED);
        ui.big(value, x + 7, y + 17, TEXT, 1.2f);
        if (frac > 0) ui.bar(x + 7, y + 33, w - 14, 4, frac, color, color);
        else ui.text(ui.clip(sub, w - 14), x + 7, y + 32, FAINT);
        if (frac > 0 && ui.over(x, y, w, 44)) ui.tip(sub);
    }

    static String researchName(String id) {
        var n = ResearchTree.get(id);
        return n == null ? id : n.name();
    }

    // ------------------------------------------------------------------ map

    record PlotView(int id, String type, int level, String state, int x0, int z0, int x1, int z1, float progress, String status, CompoundTag tag) {}

    List<PlotView> plots() {
        List<PlotView> out = new ArrayList<>();
        for (Tag t : list("plots")) {
            CompoundTag p = (CompoundTag) t;
            BlockPos o = NbtUtils.readBlockPos(p.getCompound("origin"));
            var rot = net.minecraft.world.level.block.Rotation.valueOf(p.getString("rot"));
            int w, d;
            Blueprint bp = ClientState.blueprint(p.getString("bp"));
            if (bp != null) { w = bp.width(rot); d = bp.depth(rot); }
            else {
                BuildingType bt = BuildingCatalog.get(p.getString("type"));
                if (bt == null) continue;
                boolean q = rot == net.minecraft.world.level.block.Rotation.CLOCKWISE_90 || rot == net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
                w = q ? bt.d : bt.w;
                d = q ? bt.w : bt.d;
            }
            int total = p.getInt("total");
            float prog = p.getString("state").equals("BUILT") ? 1 : total == 0 ? 0 : Math.min(1, p.getInt("placed") / (float) total);
            out.add(new PlotView(p.getInt("id"), p.getString("type"), p.getInt("level"), p.getString("state"), o.getX(), o.getZ(), o.getX() + w - 1, o.getZ() + d - 1, prog, p.getString("status"), p));
        }
        return out;
    }

    static String plotName(PlotView p) {
        if (p.type.equals("road")) return "Road";
        BuildingType t = BuildingCatalog.get(p.type);
        return t == null ? "Building" : t.name;
    }

    static int plotColor(PlotView p) {
        if (p.type.equals("road")) return Category.ROAD.color;
        BuildingType t = BuildingCatalog.get(p.type);
        return t == null ? 0xAAAAAA : t.category.color;
    }

    void map() {
        BlockPos center = BlockPos.of(v().getLong("center"));
        int radius = v().getInt("radius");
        int side = 150;
        mapW = cw - side - 6;
        mapH = ch;
        mapX = cx;
        mapY = cy;
        CityMap.ensure(center, radius + 12);
        ui.round(mapX - 1, mapY - 1, mapW + 2, mapH + 2, 6, BORDER);
        ui.round(mapX, mapY, mapW, mapH, 5, 0xFF0E1013);
        ui.pushClip(mapX + 1, mapY + 1, mapX + mapW - 1, mapY + mapH - 1);
        float base = Math.min(mapW, mapH) / (float) CityMap.size;
        float s = base * mapZoom;
        float ox = mapX + mapW / 2f - (center.getX() - CityMap.originX) * s + mapPanX;
        float oy = mapY + mapH / 2f - (center.getZ() - CityMap.originZ) * s + mapPanY;
        ui.g.pose().pushPose();
        ui.g.pose().translate(ox, oy, 0);
        ui.g.pose().scale(s, s, 1);
        ui.g.blit(CityMap.TEX, 0, 0, 0, 0, CityMap.size, CityMap.size, CityMap.size, CityMap.size);
        ui.g.pose().popPose();
        // territory ring
        int segs = 120;
        for (int i = 0; i < segs; i++) {
            double a = i * Math.PI * 2 / segs;
            int x = (int) (ox + (center.getX() + 0.5 + Math.cos(a) * radius - CityMap.originX) * s);
            int y = (int) (oy + (center.getZ() + 0.5 + Math.sin(a) * radius - CityMap.originZ) * s);
            ui.rect(x, y, 2, 2, i % 2 == 0 ? GOLD : 0x80E8C15A);
        }
        PlotView hover = null;
        for (PlotView p : plots()) {
            int x0 = (int) (ox + (p.x0 - CityMap.originX) * s), z0 = (int) (oy + (p.z0 - CityMap.originZ) * s);
            int x1 = (int) (ox + (p.x1 + 1 - CityMap.originX) * s), z1 = (int) (oy + (p.z1 + 1 - CityMap.originZ) * s);
            int col = plotColor(p);
            boolean built = p.state.equals("BUILT");
            if (p.type.equals("road")) {
                if (!built) ui.rect(x0, z0, Math.max(1, x1 - x0), Math.max(1, z1 - z0), 0x30FFFFFF);
                continue;
            }
            ui.rect(x0, z0, x1 - x0, z1 - z0, alpha(col, built ? 0x90 : 0x40));
            int oc = p.id == selPlot ? 0xFFFFFFFF : built ? opaque(col) : GOLD;
            ui.hline(x0, z0, x1 - x0, oc); ui.hline(x0, z1 - 1, x1 - x0, oc); ui.vline(x0, z0, z1 - z0, oc); ui.vline(x1 - 1, z0, z1 - z0, oc);
            if (!built && x1 - x0 > 6) ui.rect(x0 + 1, z1 - 3, (int) ((x1 - x0 - 2) * p.progress), 2, GOLD);
            if (ui.over(x0, z0, x1 - x0, z1 - z0)) hover = p;
            final int id = p.id;
            ui.area(x0, z0, x1 - x0, z1 - z0, () -> selPlot = id, false);
        }
        // the player
        var pl = Minecraft.getInstance().player;
        if (pl != null) {
            int x = (int) (ox + (pl.getX() - CityMap.originX) * s), y = (int) (oy + (pl.getZ() - CityMap.originZ) * s);
            ui.round(x - 3, y - 3, 6, 6, 3, 0xFFFFFFFF);
            ui.round(x - 2, y - 2, 4, 4, 2, BLUE);
        }
        ui.popClip();
        if (hover != null) ui.tip(plotName(hover) + (hover.level > 1 ? " (level " + hover.level + ")" : ""), stateLabel(hover));
        ui.text("Scroll to zoom · drag to pan", mapX + 6, mapY + mapH - 12, 0xA0FFFFFF);
        // side panel
        int sx = cx + cw - side;
        ui.card(sx, cy, side, ch);
        PlotView sel = null;
        for (PlotView p : plots()) if (p.id == selPlot) sel = p;
        if (sel == null) {
            ui.text("Legend", sx + 8, cy + 8, MUTED);
            int ly = cy + 22;
            for (Category c : new Category[]{Category.CIVIC, Category.HOUSING, Category.FARM, Category.SMITHY, Category.BARRACKS, Category.TOWER, Category.MARKET, Category.LIBRARY, Category.STORAGE}) {
                ui.round(sx + 9, ly + 1, 7, 7, 2, opaque(c.color));
                ui.text(c.label, sx + 22, ly, TEXT);
                ly += 12;
            }
            ly += 6;
            ui.text("Click a building to manage it.", sx + 8, ly, FAINT);
            ly += 16;
            if (mine()) {
                ui.text("Roads", sx + 8, ly, MUTED);
                ly += 12;
                roadPicker(sx + 6, ly, side - 12);
            }
        } else {
            plotDetail(sel, sx, cy, side, ch);
        }
    }

    void roadPicker(int x, int y, int w) {
        String[] mats = {"path", "gravel", "cobblestone", "stone_bricks", "planks"};
        Item[] icons = {Items.DIRT_PATH, Items.GRAVEL, Items.COBBLESTONE, Items.STONE_BRICKS, Items.SPRUCE_PLANKS};
        for (int i = 0; i < mats.length; i++) {
            final String m = mats[i];
            int bx = x + i * 26;
            boolean on = ui.over(bx, y, 24, 22);
            if (m.equals(roadMaterial)) ui.round(bx, y, 24, 22, 4, 0xFF3A4250);
            else if (on) ui.round(bx, y, 24, 22, 4, 0x20FFFFFF);
            ui.item(icons[i], bx + 4, y + 3);
            if (on) ui.tip(BuildingCatalog.pretty(m) + " road");
            ui.area(bx, y, 24, 22, () -> roadMaterial = m);
        }
        y += 26;
        ui.text("Width", x + 2, y + 3, MUTED);
        ui.button("rw-", x + 40, y, 16, 14, "−", Style.SECONDARY, roadWidth > 1, () -> roadWidth--);
        ui.center(String.valueOf(roadWidth), x + 66, y + 3, TEXT);
        ui.button("rw+", x + 76, y, 16, 14, "+", Style.SECONDARY, roadWidth < 7, () -> roadWidth++);
        y += 18;
        ui.button("road", x, y, w, 16, "Lay out a road", Style.PRIMARY, true,
                () -> Placement.startRoad(v().getUUID("id"), roadMaterial, roadWidth));
    }

    static String stateLabel(PlotView p) {
        return switch (p.state) {
            case "BUILT" -> "Standing";
            case "BUILDING" -> p.progress > 0 ? "Under construction · " + (int) (p.progress * 100) + "%" : "Planned";
            case "UPGRADING" -> "Being enlarged · " + (int) (p.progress * 100) + "%";
            case "REPAIRING" -> "Under repair · " + (int) (p.progress * 100) + "%";
            case "DEMOLISHING" -> "Being torn down";
            default -> p.state;
        };
    }

    void plotDetail(PlotView p, int x, int y, int w, int h) {
        BuildingType t = BuildingCatalog.get(p.type);
        Category cat = p.type.equals("road") ? Category.ROAD : t == null ? Category.DECOR : t.category;
        ui.item(cat.icon, x + 8, y + 8);
        ui.text(ui.clip(plotName(p), w - 34), x + 28, y + 8, TEXT);
        ui.text(cat.label + (p.level > 0 && !p.type.equals("road") ? " · level " + p.level : ""), x + 28, y + 18, opaque(cat.color));
        int yy = y + 34;
        ui.text(stateLabel(p), x + 8, yy, MUTED);
        yy += 11;
        if (!p.state.equals("BUILT")) {
            ui.bar(x + 8, yy, w - 16, 5, p.progress, GOLD, GOLD_DK);
            yy += 9;
            if (!p.status.isEmpty()) { yy += ui.wrap(p.status, x + 8, yy, w - 16, ORANGE); }
        }
        int workers = v().getCompound("plotWorkers").getInt(String.valueOf(p.id));
        if (t != null && t.category.job != null && p.state.equals("BUILT")) {
            ui.text(workers + " " + t.category.job.label.toLowerCase() + (workers == 1 ? "" : "s") + " at work", x + 8, yy, TEXT);
            yy += 11;
        }
        if (t != null && t.beds > 0) { ui.text(t.beds + " beds", x + 8, yy, FAINT); yy += 11; }
        // bill of what's left
        CompoundTag bill = v().getCompound("bills").getCompound(String.valueOf(p.id));
        if (!bill.isEmpty()) {
            yy += 2;
            ui.text("Materials", x + 8, yy, MUTED);
            yy += 11;
            Map<Item, Integer> stock = stock();
            int bh = y + h - yy - 66;
            List<String> keys = new ArrayList<>(bill.getAllKeys());
            keys.sort(Comparator.comparingInt(k -> -bill.getInt(k)));
            int off = ui.beginScroll("bill" + p.id, x + 4, yy, w - 8, Math.max(20, bh), keys.size() * 18);
            int by = yy - off;
            for (String k : keys) {
                ItemStack st = new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(k)));
                ui.itemTip(st, x + 8, by);
                int need = bill.getInt(k), has = stock.getOrDefault(st.getItem(), 0);
                ui.text(ui.clip(st.getHoverName().getString(), w - 70), x + 28, by + 4, TEXT);
                ui.right(num(has) + "/" + num(need), x + w - 10, by + 4, has >= need ? GREEN : ORANGE);
                by += 18;
            }
            ui.endScroll();
        }
        if (!mine()) return;
        int bx = x + 6, bw = w - 12, by = y + h - 60;
        int maxLevel = v().getCompound("maxLevels").getInt(String.valueOf(p.id));
        final int id = p.id;
        if (p.state.equals("BUILT") && t != null) {
            boolean canUp = p.level < maxLevel;
            ui.button("up" + id, bx, by, bw, 16, canUp ? "Upgrade to level " + (p.level + 1) : p.level >= t.maxLevel ? "Fully upgraded" : "Upgrade (needs higher rank)",
                    Style.PRIMARY, canUp, () -> act("upgrade", tag("plot", id)));
            if (canUp && !t.procedural() && ui.over(bx, by, bw, 16)) ui.tip("Renovation: " + 24 * p.level + " planks, " + 24 * p.level + " cobblestone, " + 2 * p.level + " emeralds");
            ui.button("rep" + id, bx, by + 19, bw / 2 - 2, 16, "Repair", Style.SECONDARY, true, () -> act("repair", tag("plot", id)));
            ui.button("dem" + id, bx + bw / 2 + 2, by + 19, bw / 2 - 2, 16, "Demolish", Style.DANGER, true, () -> act("demolish", tag("plot", id)));
        } else if (!p.state.equals("DEMOLISHING")) {
            ui.button("pu" + id, bx, by, bw / 2 - 2, 16, "▲ Sooner", Style.SECONDARY, true, () -> act("priority", tag("plot", id, "delta", 1)));
            ui.button("pd" + id, bx + bw / 2 + 2, by, bw / 2 - 2, 16, "▼ Later", Style.SECONDARY, true, () -> act("priority", tag("plot", id, "delta", -1)));
            ui.button("can" + id, bx, by + 19, bw, 16, "Cancel", Style.DANGER, true, () -> { act("demolish", tag("plot", id)); selPlot = -1; });
        }
        ui.button("close" + id, bx, by + 38, bw, 16, "Back", Style.GHOST, true, () -> selPlot = -1);
    }

    // ------------------------------------------------------------------ build catalogue

    void build() {
        header("Build", mine() ? "Choose a building, then place it with the Architect's Plans." : "This town's buildings.", cw - 124);
        int top = cy + 30;
        // left: categories
        int lw = 96;
        search.setX(cx + cw - 118);
        search.setY(cy + 4);
        search.setWidth(112);
        ui.round(cx + cw - 122, cy, 120, 18, 5, 0xFF0F1216);
        ui.card(cx, top, lw, ch - 30);
        List<String> cats = new ArrayList<>();
        cats.add("ALL");
        for (Category c : Category.values()) cats.add(c.name());
        int off = ui.beginScroll("cats", cx + 2, top + 4, lw - 4, ch - 38, cats.size() * 17);
        int yy = top + 4 - off;
        for (String c : cats) {
            final String cc = c;
            Item ic = c.equals("ALL") ? Items.COMPASS : Category.valueOf(c).icon;
            String label = c.equals("ALL") ? "Everything" : Category.valueOf(c).label;
            boolean sel = catFilter.equals(c);
            boolean on = ui.over(cx + 2, yy, lw - 4, 16);
            if (sel) ui.round(cx + 3, yy, lw - 6, 16, 4, 0xFF2E3542);
            else if (on) ui.round(cx + 3, yy, lw - 6, 16, 4, 0x18FFFFFF);
            ui.itemScaled(new ItemStack(ic), cx + 6, yy + 2, 0.75f);
            ui.text(label, cx + 22, yy + 4, sel ? GOLD : TEXT);
            ui.area(cx + 2, yy, lw - 4, 16, () -> { catFilter = cc; ui.resetScroll("blist"); });
            yy += 17;
        }
        ui.endScroll();
        if (catFilter.equals("ROAD")) {
            int mx = cx + lw + 6;
            ui.card(mx, top, cw - lw - 6, ch - 30);
            ui.text("Roads connect your buildings. Builders lay them block by block,", mx + 10, top + 10, TEXT);
            ui.text("following the ground, and bridge water with planks.", mx + 10, top + 21, TEXT);
            if (mine()) roadPicker(mx + 10, top + 40, 140);
            return;
        }
        // style filter
        Set<String> styles = new TreeSet<>();
        for (BuildingType t : BuildingCatalog.types().values()) styles.add(t.style.equals("any") ? "any" : t.style);
        List<String> styleList = new ArrayList<>();
        styleList.add("ALL");
        styleList.addAll(styles);
        // middle: list
        int mx = cx + lw + 6, mw = 150;
        String styleLabel = styleFilter.equals("ALL") ? "All styles" : styleFilter.equals("any") ? "Living Realms" : prettyStyle(styleFilter);
        ui.button("style", mx, top, mw, 15, "Style: " + ui.clip(styleLabel, mw - 40), Style.SECONDARY, true, () -> {
            int i = styleList.indexOf(styleFilter);
            styleFilter = styleList.get((i + (Screen.hasShiftDown() ? styleList.size() - 1 : 1)) % styleList.size());
            ui.resetScroll("blist");
        });
        if (ui.over(mx, top, mw, 15)) ui.tip("Click to cycle (shift-click goes back)", styleList.size() - 1 + " styles from your mods");
        List<BuildingType> types = new ArrayList<>();
        String q = search.getValue().toLowerCase(Locale.ROOT);
        CompoundTag locks = v().getCompound("locks");
        for (BuildingType t : BuildingCatalog.types().values()) {
            if (!catFilter.equals("ALL") && !t.category.name().equals(catFilter)) continue;
            if (!styleFilter.equals("ALL") && !t.style.equals(styleFilter)) continue;
            if (!q.isEmpty() && !(t.name.toLowerCase(Locale.ROOT).contains(q) || t.styleName.toLowerCase(Locale.ROOT).contains(q) || t.category.label.toLowerCase(Locale.ROOT).contains(q))) continue;
            types.add(t);
        }
        types.sort(Comparator.comparing((BuildingType t) -> locks.contains(t.id)).thenComparing(t -> !t.procedural()).thenComparingInt(t -> t.minTier).thenComparing(t -> t.name));
        int lt = top + 18, lh = ch - 60;
        ui.card(mx, lt, mw, lh);
        int off2 = ui.beginScroll("blist", mx + 2, lt + 2, mw - 4, lh - 4, types.size() * 24);
        int ry = lt + 3 - off2;
        if (selType == null && !types.isEmpty()) selType = types.get(0).id;
        for (BuildingType t : types) {
            final String id = t.id;
            boolean sel = id.equals(selType), on = ui.over(mx + 2, ry, mw - 4, 23);
            if (sel) ui.round(mx + 3, ry, mw - 6, 23, 4, 0xFF2E3542);
            else if (on) ui.round(mx + 3, ry, mw - 6, 23, 4, 0x18FFFFFF);
            ui.round(mx + 6, ry + 4, 3, 15, 1, opaque(t.category.color));
            boolean locked = locks.contains(id);
            ui.text(ui.clip(t.name, mw - 22), mx + 13, ry + 3, locked ? FAINT : TEXT);
            String sub = locked ? locks.getString(id) : t.w + "×" + t.d + (t.procedural() ? " · upgrades to " + t.maxLevel : " · " + t.styleName);
            ui.text(ui.clip(sub, mw - 22), mx + 13, ry + 13, locked ? 0xFFB06060 : FAINT);
            ui.area(mx + 2, ry, mw - 4, 23, () -> selType = id);
            ry += 24;
        }
        if (types.isEmpty()) ui.text("Nothing matches.", mx + 10, lt + 10, FAINT);
        ui.endScroll();
        ui.text(types.size() + " buildings", mx + 4, lt + lh + 3, FAINT);
        // right: detail
        int dx = mx + mw + 6, dw = cx + cw - dx;
        ui.card(dx, top, dw, ch - 30);
        BuildingType t = selType == null ? null : BuildingCatalog.get(selType);
        if (t == null) return;
        String key = t.blueprintKey(1, v().getString("style"));
        Blueprint bp = ClientState.blueprint(key);
        int prevH = Math.min(110, (ch - 30) / 2 - 10);
        ui.round(dx + 4, top + 4, dw - 8, prevH, 4, 0xFF101317, 0xFF1A1F27);
        if (bp != null) {
            ui.pushClip(dx + 4, top + 4, dx + dw - 4, top + 4 + prevH);
            Preview.render(ui.g, bp, dx + 4, top + 4, dw - 8, prevH, (System.currentTimeMillis() % 36000) / 100f);
            ui.popClip();
        } else ui.center("Loading…", dx + dw / 2, top + prevH / 2, FAINT);
        int yy2 = top + prevH + 9;
        ui.text(ui.clip(t.name, dw - 16), dx + 8, yy2, TEXT);
        yy2 += 10;
        ui.text(ui.clip(t.category.label + " · " + t.source, dw - 16), dx + 8, yy2, opaque(t.category.color));
        yy2 += 12;
        String facts = t.w + "×" + t.d + "×" + t.h + " · " + t.blocks + " blocks" + (t.beds > 0 ? " · " + t.beds + " beds" : "");
        ui.text(ui.clip(facts, dw - 16), dx + 8, yy2, MUTED);
        yy2 += 10;
        String perks = perks(t);
        if (!perks.isEmpty()) { yy2 += ui.wrap(perks, dx + 8, yy2, dw - 16, FAINT); }
        String lock = locks.getString(t.id);
        if (!lock.isEmpty()) { ui.text("🔒 " + lock, dx + 8, yy2 + 1, RED); yy2 += 12; }
        // materials
        int bh = top + ch - 30 - yy2 - 26;
        if (bp != null && bh < 18) ui.text(ui.clip(bp.bill().size() + " materials", dw - 16), dx + 8, yy2 + 2, FAINT);
        else if (bp != null) {
            Map<Item, Integer> stock = stock();
            List<Map.Entry<Item, Integer>> bill = new ArrayList<>(bp.bill().entrySet());
            int cols = Math.max(1, (dw - 12) / 58);
            int rows = (bill.size() + cols - 1) / cols;
            int off3 = ui.beginScroll("tbill", dx + 4, yy2 + 2, dw - 8, bh, rows * 18);
            for (int i = 0; i < bill.size(); i++) {
                var e = bill.get(i);
                int bx = dx + 6 + (i % cols) * 58, by = yy2 + 3 + (i / cols) * 18 - off3;
                ItemStack st = new ItemStack(e.getKey());
                ui.itemTip(st, bx, by);
                int has = stock.getOrDefault(e.getKey(), 0);
                ui.text(num(e.getValue()), bx + 18, by + 4, has >= e.getValue() ? GREEN : ORANGE);
            }
            ui.endScroll();
        }
        if (mine()) {
            boolean ok = lock.isEmpty();
            ui.button("place", dx + 6, top + ch - 30 - 22, dw - 12, 17, ok ? "Plan it" : "Locked", Style.PRIMARY, ok, () -> {
                Placement.startBuilding(v().getUUID("id"), v().getString("style"), t.id);
                if (Minecraft.getInstance().player != null && !Placement.holdingPlans())
                    Minecraft.getInstance().player.displayClientMessage(Component.literal("Hold the Architect's Plans to place it.").withStyle(net.minecraft.ChatFormatting.YELLOW), false);
            });
        }
    }

    static String prettyStyle(String s) {
        int i = s.indexOf(':');
        String ns = i < 0 ? "" : s.substring(0, i);
        String src = switch (ns) { case "minecraft" -> ""; case "ctov" -> " (CTOV)"; case "kaisyn" -> " (T&T)"; default -> " (" + ns + ")"; };
        return BuildingCatalog.pretty(i < 0 ? s : s.substring(i + 1)) + src;
    }

    static String perks(BuildingType t) {
        return switch (t.category) {
            case CIVIC -> "Seat of the town. Grows with every rank; gives research, builders and a place to muster.";
            case HOUSING -> "Homes for " + Math.max(3, t.beds * 2 + 1) + " settlers.";
            case CONSTRUCTION -> "Workplace for " + t.category.slotsPerLevel + " more builders per level.";
            case FARM -> "Farmers grow wheat, bread, carrots and potatoes.";
            case LUMBER -> "Lumberjacks bring logs, planks and sticks.";
            case MINE -> "Miners dig stone, coal and ores; Deep Mining finds gold and diamonds.";
            case SMITHY -> "Smiths smelt ore and take your arms and armour orders.";
            case LIBRARY -> "Scholars speed up research.";
            case MARKET -> "Merchants earn emeralds.";
            case WORKSHOP -> "Craftsmen make " + (t.trade == null ? "goods" : t.trade) + " wares.";
            case TAVERN -> "Raises happiness and draws newcomers; turns wheat into bread.";
            case CHAPEL -> "Raises happiness.";
            case STABLE -> "Lets you train horsemen.";
            case STORAGE -> "Holds 3000 more items per level in the stockpile.";
            case BARRACKS -> "Trains soldiers and houses 6 more per level.";
            case TOWER -> "A post for guards; room for 2 more soldiers.";
            case WALL -> "Keeps raiders out; soldiers patrol it.";
            case DECOR -> "Townsfolk love it: a little happiness.";
            case ROAD -> "";
        };
    }

    // ------------------------------------------------------------------ projects

    void projects() {
        header("Projects", "Construction queue and every building in town.");
        List<PlotView> all = plots();
        List<PlotView> active = new ArrayList<>(), built = new ArrayList<>();
        for (PlotView p : all) {
            if (p.state.equals("BUILT")) { if (!p.type.equals("road")) built.add(p); }
            else active.add(p);
        }
        active.sort(Comparator.comparingInt((PlotView p) -> -p.tag.getInt("prio")).thenComparingInt(p -> p.id));
        int lw = cw - 156, top = cy + 30;
        ui.card(cx, top, lw, ch - 30);
        int rows = active.size() + built.size();
        int off = ui.beginScroll("proj", cx + 2, top + 2, lw - 4, ch - 34, rows * 26 + 40);
        int y = top + 6 - off;
        ui.text("Under way (" + active.size() + ")", cx + 8, y, GOLD);
        y += 13;
        if (active.isEmpty()) { ui.text("Nothing being built. Plan something on the Build page.", cx + 8, y, FAINT); y += 14; }
        for (PlotView p : active) y = projectRow(p, y, lw);
        y += 6;
        ui.text("Standing (" + built.size() + ")", cx + 8, y, GOLD);
        y += 13;
        for (PlotView p : built) y = projectRow(p, y, lw);
        ui.endScroll();
        int sx = cx + lw + 6;
        PlotView sel = null;
        for (PlotView p : all) if (p.id == selPlot) sel = p;
        ui.card(sx, top, cw - lw - 6, ch - 30);
        if (sel != null) plotDetail(sel, sx, top, cw - lw - 6, ch - 30);
        else {
            ui.text("Select a project", sx + 8, top + 8, MUTED);
            ui.wrap("Builders take materials straight from the stockpile, wherever they are working. If something is missing the site waits and shows what it needs.",
                    sx + 8, top + 22, cw - lw - 22, FAINT);
        }
    }

    int projectRow(PlotView p, int y, int w) {
        final int id = p.id;
        boolean sel = id == selPlot, on = ui.over(cx + 3, y, w - 6, 24);
        if (sel) ui.round(cx + 3, y, w - 6, 24, 4, 0xFF2E3542);
        else if (on) ui.round(cx + 3, y, w - 6, 24, 4, 0x18FFFFFF);
        BuildingType t = BuildingCatalog.get(p.type);
        Category cat = p.type.equals("road") ? Category.ROAD : t == null ? Category.DECOR : t.category;
        ui.item(cat.icon, cx + 7, y + 4);
        ui.text(ui.clip(plotName(p) + (p.level > 1 ? " · L" + p.level : ""), w - 120), cx + 27, y + 3, TEXT);
        ui.text(ui.clip(p.status.isEmpty() ? stateLabel(p) : p.status, w - 120), cx + 27, y + 13, p.status.isEmpty() ? FAINT : ORANGE);
        if (!p.state.equals("BUILT")) ui.bar(cx + w - 88, y + 9, 80, 5, p.progress, p.state.equals("DEMOLISHING") ? RED : GOLD, p.state.equals("DEMOLISHING") ? RED : GOLD_DK);
        else {
            int workers = v().getCompound("plotWorkers").getInt(String.valueOf(id));
            if (t != null && t.category.job != null) ui.right(workers + " " + t.category.job.label.toLowerCase() + (workers == 1 ? "" : "s"), cx + w - 10, y + 8, MUTED);
        }
        ui.area(cx + 3, y, w - 6, 24, () -> selPlot = id);
        return y + 26;
    }

    // ------------------------------------------------------------------ stockpile

    void storage() {
        Map<Item, Integer> stock = stock();
        int total = stock.values().stream().mapToInt(Integer::intValue).sum(), cap = v().getInt("capacity");
        header("Stockpile", num(total) + " of " + num(cap) + " stored · builders, smiths and soldiers draw from here");
        search.setX(cx + cw - 118);
        search.setY(cy + 4);
        search.setWidth(112);
        ui.round(cx + cw - 122, cy, 120, 18, 5, 0xFF0F1216);
        int top = cy + 30;
        ui.bar(cx, top, cw, 5, cap == 0 ? 0 : total / (float) cap, total > cap * 0.9 ? RED : BLUE, total > cap * 0.9 ? RED : 0xFF4A80C8);
        top += 10;
        int gw = cw - 128;
        ui.card(cx, top, gw, ch - 40);
        String q = search.getValue().toLowerCase(Locale.ROOT);
        List<Map.Entry<Item, Integer>> items = new ArrayList<>();
        for (var e : stock.entrySet()) if (q.isEmpty() || e.getKey().getDescription().getString().toLowerCase(Locale.ROOT).contains(q)) items.add(e);
        items.sort(Comparator.comparingInt((Map.Entry<Item, Integer> e) -> -e.getValue()));
        int cell = 24, cols = Math.max(1, (gw - 10) / cell);
        int rows = (items.size() + cols - 1) / cols;
        int off = ui.beginScroll("stock", cx + 2, top + 2, gw - 4, ch - 44, rows * cell + 6);
        for (int i = 0; i < items.size(); i++) {
            var e = items.get(i);
            int x = cx + 6 + (i % cols) * cell, y = top + 5 + (i / cols) * cell - off;
            boolean on = ui.over(x, y, cell - 2, cell - 2);
            ui.round(x, y, cell - 2, cell - 2, 4, on ? 0xFF323A47 : 0xFF232933);
            ItemStack st = new ItemStack(e.getKey());
            ui.item(st, x + 3, y + 2);
            String n = num(e.getValue());
            ui.g.pose().pushPose();
            ui.g.pose().translate(0, 0, 200);
            ui.textShadow(n, x + cell - 3 - ui.width(n), y + cell - 11, TEXT);
            ui.g.pose().popPose();
            if (on) ui.tip(st.getHoverName().getString(), e.getValue() + " in store", mine() ? "Click: take a stack · Shift-click: take 4 stacks" : "");
            final Item it = e.getKey();
            if (mine()) ui.area(x, y, cell - 2, cell - 2, () -> act("withdraw", tag("item", BuiltInRegistries.ITEM.getKey(it).toString(),
                    "count", it.getMaxStackSize() * (Screen.hasShiftDown() ? 4 : 1))));
        }
        if (items.isEmpty()) ui.text(stock.isEmpty() ? "The stockpile is empty." : "Nothing matches.", cx + 10, top + 10, FAINT);
        ui.endScroll();
        int sx = cx + gw + 6, sw = cw - gw - 6;
        ui.card(sx, top, sw, ch - 40);
        int y = top + 8;
        ui.text("Bring goods in", sx + 8, y, MUTED);
        y += 14;
        if (mine()) {
            ui.button("dep", sx + 6, y, sw - 12, 16, "Deposit my pack", Style.PRIMARY, true, () -> act("deposit", tag("item", "")));
            if (ui.over(sx + 6, y, sw - 12, 16)) ui.tip("Moves everything in your inventory except the hotbar, armour, and named or enchanted items.");
            y += 22;
        }
        y += ui.wrap("A Delivery Barrel anywhere in town carries whatever is put in it into the stockpile: by hand, by hopper, or by Villager Workers.", sx + 8, y, sw - 16, FAINT);
        y += 8;
        ui.text("Food in store", sx + 8, y, MUTED);
        y += 12;
        int food = 0;
        for (var e : stock.entrySet()) {
            var fp = e.getKey().getFoodProperties();
            if (fp != null) food += fp.getNutrition() * e.getValue();
        }
        double need = v().getDouble("foodNeed");
        ui.big(need <= 0 ? "∞" : num(food / need) + " days", sx + 8, y, food / Math.max(1, need) > 2 ? GREEN : ORANGE, 1.2f);
    }

    // ------------------------------------------------------------------ research

    void research() {
        String active = v().getString("activeResearch");
        double rate = v().getDouble("researchRate");
        header("Research", num(rate) + " points a day" + (v().getDouble("researchBank") >= 1 ? " · " + num(v().getDouble("researchBank")) + " banked" : ""));
        int top = cy + 30;
        // current project
        ui.card(cx, top, cw, 34);
        if (active.isEmpty()) {
            ui.text("No research under way", cx + 10, top + 8, MUTED);
            ui.text("Pick a project below. Libraries and scholars speed it up.", cx + 10, top + 19, FAINT);
        } else {
            var n = ResearchTree.get(active);
            if (n != null) {
                ui.item(n.icon(), cx + 8, top + 9);
                ui.text(n.name(), cx + 30, top + 7, TEXT);
                double prog = v().getDouble("researchProgress");
                ui.bar(cx + 30, top + 20, cw - 140, 6, (float) (prog / n.cost()), PURPLE, 0xFF8460C8);
                double eta = rate <= 0 ? -1 : (n.cost() - prog) / rate;
                ui.right(num(prog) + "/" + n.cost() + (eta >= 0 ? " · " + eta(eta) : ""), cx + cw - 10, top + 7, MUTED);
                if (mine()) ui.button("cres", cx + cw - 70, top + 17, 62, 13, "Stop", Style.SECONDARY, true, () -> act("cancelResearch"));
            }
        }
        // the tree
        resX = cx;
        resY = top + 40;
        resW = cw;
        resH = ch - 70;
        ui.round(resX, resY, resW, resH, 5, BORDER);
        ui.round(resX + 1, resY + 1, resW - 2, resH - 2, 4, 0xFF111418);
        Set<String> done = new HashSet<>(strings("research"));
        int colW = 112, rowH = 32;
        int ox = (int) (resX + 10 + resPanX), oy = (int) (resY + 18 + resPanY);
        ui.pushClip(resX + 1, resY + 1, resX + resW - 1, resY + resH - 1);
        // branch labels, each branch pushed down a little so its label has a gap of its own
        Map<String, Integer> branchRow = new LinkedHashMap<>();
        for (var n : ResearchTree.nodes().values()) branchRow.merge(n.branch(), n.y(), Math::min);
        List<String> branches = new ArrayList<>(branchRow.keySet());
        branches.sort(Comparator.comparingInt(branchRow::get));
        Map<String, Integer> gap = new HashMap<>();
        for (int i = 0; i < branches.size(); i++) gap.put(branches.get(i), i * 12);
        java.util.function.ToIntFunction<ResearchTree.Node> ry = n -> n.y() * rowH + gap.getOrDefault(n.branch(), 0);
        for (String b : branches) ui.text(b.toUpperCase(Locale.ROOT), ox - 6, oy + branchRow.get(b) * rowH + gap.get(b) - 12, FAINT);
        // links
        for (var n : ResearchTree.nodes().values()) {
            for (String r : n.requires()) {
                var p = ResearchTree.get(r);
                if (p == null) continue;
                int x0 = ox + p.x() * colW + 96, y0 = oy + ry.applyAsInt(p) + 11, x1 = ox + n.x() * colW, y1 = oy + ry.applyAsInt(n) + 11;
                int c = done.contains(r) ? 0xFF8A7440 : 0xFF3A414C;
                int mid = x0 + (x1 - x0) / 2;
                ui.hline(Math.min(x0, mid), y0, Math.abs(mid - x0) + 1, c);
                ui.vline(mid, Math.min(y0, y1), Math.abs(y1 - y0) + 1, c);
                ui.hline(Math.min(mid, x1), y1, Math.abs(x1 - mid), c);
            }
        }
        for (var n : ResearchTree.nodes().values()) {
            int x = ox + n.x() * colW, y = oy + ry.applyAsInt(n);
            boolean isDone = done.contains(n.id()), isActive = n.id().equals(active);
            boolean open = !isDone && n.requires().stream().allMatch(done::contains);
            boolean on = ui.over(x, y, 96, 22);
            int fill = isDone ? 0xFF3A3220 : isActive ? 0xFF2E2642 : open ? 0xFF232A35 : 0xFF181C22;
            int border = isDone ? GOLD_DK : isActive ? PURPLE : n.id().equals(selResearch) ? 0xFFFFFFFF : open ? 0xFF4A5566 : 0xFF2A3038;
            ui.round(x, y, 96, 22, 5, on ? brighten(border, 30) : border);
            ui.round(x + 1, y + 1, 94, 20, 4, on ? brighten(fill, 12) : fill);
            ui.itemScaled(new ItemStack(n.icon()), x + 4, y + 5, 0.75f);
            ui.text(ui.clip(n.name(), 74), x + 19, y + 4, isDone ? GOLD : open ? TEXT : FAINT);
            ui.text(isDone ? "Known" : n.cost() + " pts", x + 19, y + 13, isDone ? 0xFFA08850 : FAINT);
            if (isActive) ui.rect(x + 2, y + 19, (int) (92 * Math.min(1, v().getDouble("researchProgress") / n.cost())), 1, PURPLE);
            final String id = n.id();
            ui.area(x, y, 96, 22, () -> selResearch = id);
        }
        ui.popClip();
        int hintW = ui.width("Drag to look around") + 8;
        ui.round(resX + resW - hintW - 4, resY + resH - 14, hintW, 12, 3, 0xE0111418);
        ui.text("Drag to look around", resX + resW - hintW, resY + resH - 12, FAINT);
        // detail of the selected project
        var sel = selResearch == null ? null : ResearchTree.get(selResearch);
        if (sel != null) {
            int dw = 200, dh = 92, dx = resX + resW - dw - 6, dy = resY + 6;
            ui.card(dx, dy, dw, dh, 0xF0202630, 0xFF3A4250);
            ui.item(sel.icon(), dx + 8, dy + 7);
            ui.text(sel.name(), dx + 28, dy + 6, TEXT);
            ui.text(sel.branch() + " · " + sel.cost() + " points", dx + 28, dy + 16, MUTED);
            int ty = dy + 28;
            ty += ui.wrap(sel.description(), dx + 8, ty, dw - 16, FAINT);
            if (!sel.items().isEmpty()) {
                Map<Item, Integer> stock = stock();
                StringBuilder sb = new StringBuilder("Needs: ");
                sel.items().forEach((k, n) -> sb.append(n).append(" ").append(ingName(k)).append(have(stock, k) >= n ? " ✔ " : " ✖ "));
                ty += ui.wrap(sb.toString(), dx + 8, ty + 1, dw - 16, ORANGE);
            }
            boolean isDone = done.contains(sel.id());
            boolean open = sel.requires().stream().allMatch(done::contains);
            if (mine() && !isDone && !sel.id().equals(active)) {
                String label = open ? "Study this" : "Needs " + sel.requires().stream().filter(r -> !done.contains(r)).map(CityScreen::researchName).findFirst().orElse("");
                ui.button("sres", dx + 6, dy + dh - 20, dw - 12, 15, ui.clip(label, dw - 20), Style.PRIMARY, open, () -> act("research", tag("id", sel.id())));
            }
            ui.area(dx, dy, dw, dh, () -> {}, false);
        }
    }

    static String eta(double days) {
        long mins = Math.round(days * 20);
        return mins < 60 ? "~" + Math.max(1, mins) + " min" : String.format(Locale.ROOT, "~%.1f h", mins / 60.0);
    }

    // ------------------------------------------------------------------ military

    void milTabs() {
        ui.segments("mil", cx + cw - 250, cy + 2, 250, 16, new String[]{"Garrison", "Recruit", "Blacksmith", "Kits"}, milTab, i -> {
            milTab = i;
            tab = i == 2 ? Tab.FORGE : Tab.MILITARY;
        });
    }

    void military() {
        if (milTab == 2) milTab = 0;
        ListTag soldiers = list("soldiers");
        header("Military", soldiers.size() + " / " + v().getInt("garrisonCap") + " soldiers" + (list("training").isEmpty() ? "" : " · " + list("training").size() + " in training"), cw - 252);
        milTabs();
        switch (milTab) {
            case 1 -> recruit();
            case 3 -> kits();
            default -> garrison();
        }
    }

    void garrison() {
        int top = cy + 30;
        Stance cur;
        try { cur = Stance.valueOf(v().getString("stance")); } catch (Exception e) { cur = Stance.PATROL; }
        Stance[] st = Stance.values();
        int sw = (cw - 80 - (st.length - 1) * 4) / st.length;
        for (int i = 0; i < st.length; i++) {
            final Stance s = st[i];
            int x = cx + i * (sw + 4);
            boolean sel = s == cur, on = ui.over(x, top, sw, 30);
            ui.card(x, top, sw, 30, sel ? 0xFF2E3542 : on ? CARD_HI : CARD, sel ? GOLD : BORDER);
            ui.center(s.label, x + sw / 2, top + 6, sel ? GOLD : TEXT);
            if (sel) ui.center(ui.width("current orders") < sw - 6 ? "current orders" : "active", x + sw / 2, top + 17, FAINT);
            if (on) ui.tip(s.label, s.description);
            if (mine()) ui.area(x, top, sw, 30, () -> act("stance", tag("stance", s.name())));
        }
        boolean alarm = v().getBoolean("alarm"), manual = v().getBoolean("manualAlarm");
        int ax = cx + cw - 74;
        float pulse = alarm ? (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 150.0)) : 0;
        ui.round(ax, top, 74, 30, 5, lerp(0xFF7A2020, 0xFFE04040, pulse));
        ui.round(ax + 1, top + 1, 72, 28, 4, lerp(0xFF3A1A1A, 0xFF902828, pulse));
        ui.item(Items.BELL, ax + 5, top + 7);
        ui.text(alarm ? "ALARM" : "Sound", ax + 25, top + 6, alarm ? 0xFFFFD0D0 : TEXT);
        ui.text(alarm ? (manual ? "click: stand down" : "raid!") : "the alarm", ax + 25, top + 17, 0xFFD09090);
        if (mine()) ui.area(ax, top, 74, 30, () -> act("alarm", tag("on", !manual)));
        if (ui.over(ax, top, 74, 30)) ui.tip("The alarm sends every soldier after the nearest enemy and sends townsfolk to the town hall.", "It also rings by itself when raiders enter the town.");
        // roster
        int ly = top + 36, lh = ch - 66;
        ui.card(cx, ly, cw, lh);
        ListTag soldiers = list("soldiers");
        int off = ui.beginScroll("roster", cx + 2, ly + 2, cw - 4, lh - 26, soldiers.size() * 24 + 4);
        int y = ly + 4 - off;
        for (Tag t : soldiers) {
            CompoundTag s = (CompoundTag) t;
            UnitType u = UnitType.valueOf(s.getString("type"));
            boolean seen = s.getBoolean("seen");
            ui.item(u.icon, cx + 8, y + 3);
            ui.text(ui.clip(s.getString("name"), 120), cx + 28, y + 2, seen ? TEXT : MUTED);
            int lvl = s.getInt("lvl");
            ui.text("★".repeat(Math.max(0, Math.min(5, lvl))) + (lvl > 5 ? "+" : ""), cx + 28, y + 12, GOLD);
            int bx = cx + 160;
            float hp = s.getFloat("max") <= 0 ? 0 : s.getFloat("hp") / s.getFloat("max");
            ui.text("HP", bx, y + 2, FAINT);
            ui.bar(bx + 16, y + 3, 60, 5, hp, hp > 0.5 ? GREEN : hp > 0.25 ? GOLD : RED, hp > 0.5 ? 0xFF4FA860 : hp > 0.25 ? GOLD_DK : 0xFFB84A4A);
            ui.text("Morale " + (int) s.getFloat("morale"), bx, y + 12, FAINT);
            ui.text(ui.clip(seen ? s.getString("gear") : "away from town", cw - 290), cx + 250, y + 2, MUTED);
            ui.text(s.getInt("kills") + " kills", cx + 250, y + 12, FAINT);
            if (mine()) {
                final UUID id = s.getUUID("e");
                ui.button("dis" + id, cx + cw - 30, y + 4, 18, 14, "×", Style.GHOST, true, () -> {
                    CompoundTag d = new CompoundTag();
                    d.putUUID("soldier", id);
                    act("dismiss", d);
                });
                if (ui.over(cx + cw - 30, y + 4, 18, 14)) ui.tip("Release from the garrison. They stay yours as ordinary Recruits.");
            }
            y += 24;
        }
        if (soldiers.isEmpty()) ui.text("No soldiers yet. Build a Barracks and train some on the Recruit page.", cx + 10, ly + 10, FAINT);
        ui.endScroll();
        if (mine()) {
            ui.button("equip", cx + 6, ly + lh - 21, 150, 16, "Equip from the armoury", Style.PRIMARY, !soldiers.isEmpty(), () -> act("equipAll"));
            if (ui.over(cx + 6, ly + lh - 21, 150, 16)) ui.tip("Gives every soldier in town the best kit the armoury holds for them (see Kits). Old gear goes back into the stockpile.");
        }
        String hint = "Fed soldiers heal over time; hungry ones weaken.";
        if (ui.width(hint) < cw - 172) ui.right(hint, cx + cw - 8, ly + lh - 16, FAINT);
    }

    void recruit() {
        int top = cy + 30;
        Map<Item, Integer> stock = stock();
        Set<String> done = new HashSet<>(strings("research"));
        int lw = cw - 150;
        UnitType[] units = UnitType.values();
        int cardH = (ch - 34 - (units.length - 1) * 4) / units.length;
        for (int i = 0; i < units.length; i++) {
            UnitType u = units[i];
            int y = top + i * (cardH + 4);
            boolean sel = u == selUnit, on = ui.over(cx, y, lw, cardH);
            ui.card(cx, y, lw, cardH, sel ? 0xFF2E3542 : on ? CARD_HI : CARD, sel ? GOLD : BORDER);
            ui.item(u.icon, cx + 8, y + (cardH - 16) / 2);
            ui.text(u.label, cx + 30, y + 5, TEXT);
            ui.text(ui.clip(u.description, lw - 150), cx + 30, y + 16, FAINT);
            String lock = null;
            if (u.research != null && !done.contains(u.research)) lock = "Research " + researchName(u.research);
            else if (v().getInt("barracks") == 0) lock = "Build a Barracks";
            else if (u == UnitType.HORSEMAN && !v().getBoolean("stables")) lock = "Build Stables";
            String costLine = u.emeralds + " emeralds · " + Arrays.stream(u.needs).map(p -> p == Piece.SWORD ? "a weapon" : "a " + p.label.toLowerCase(Locale.ROOT)).reduce((a, b) -> a + ", " + b).orElse("");
            ui.right(lock != null ? "🔒 " + lock : costLine, cx + lw - 8, y + 5, lock != null ? RED : stock.getOrDefault(Items.EMERALD, 0) >= u.emeralds ? MUTED : ORANGE);
            ui.right(eta(u.trainDays) + " to train", cx + lw - 8, y + 16, FAINT);
            final UnitType uu = u;
            ui.area(cx, y, lw, cardH, () -> selUnit = uu);
        }
        // order panel
        int sx = cx + lw + 6, sw = cw - lw - 6;
        ui.card(sx, top, sw, 70);
        ui.text("Train " + selUnit.label + "s", sx + 8, top + 7, TEXT);
        ui.button("tq-", sx + 8, top + 22, 18, 16, "−", Style.SECONDARY, trainQty > 1, () -> trainQty--);
        ui.center(String.valueOf(trainQty), sx + 38, top + 26, TEXT);
        ui.button("tq+", sx + 50, top + 22, 18, 16, "+", Style.SECONDARY, trainQty < 10, () -> trainQty++);
        ui.text(selUnit.emeralds * trainQty + " ◆", sx + 76, top + 26, stock.getOrDefault(Items.EMERALD, 0) >= selUnit.emeralds * trainQty ? GREEN : ORANGE);
        if (mine()) ui.button("train", sx + 6, top + 46, sw - 12, 17, "Begin training", Style.PRIMARY, true,
                () -> act("train", tag("unit", selUnit.name(), "qty", trainQty)));
        // queue
        ListTag training = list("training");
        int qy = top + 76;
        ui.card(sx, qy, sw, ch - 76 - 30);
        ui.text("Training yard", sx + 8, qy + 7, MUTED);
        int y = qy + 20;
        for (int i = 0; i < training.size(); i++) {
            CompoundTag t = training.getCompound(i);
            UnitType u = UnitType.valueOf(t.getString("type"));
            ui.itemScaled(new ItemStack(u.icon), sx + 8, y, 0.75f);
            ui.text(u.label, sx + 22, y + 1, TEXT);
            ui.bar(sx + 22, y + 11, sw - 50, 4, t.getFloat("p"), i == 0 ? GOLD : FAINT, i == 0 ? GOLD_DK : FAINT);
            final int idx = i;
            if (mine()) ui.button("ct" + i, sx + sw - 24, y + 2, 16, 13, "×", Style.GHOST, true, () -> act("cancelTraining", tag("index", idx)));
            y += 20;
            if (y > qy + ch - 76 - 30 - 18) break;
        }
        if (training.isEmpty()) ui.wrap("Recruits are drawn from your townsfolk and need their weapon in the armoury.", sx + 8, y, sw - 16, FAINT);
    }

    void kits() {
        int top = cy + 30;
        CompoundTag kits = v().getCompound("kits");
        ui.text("Auto-equip new soldiers", cx + 4, top + 2, TEXT);
        if (mine()) ui.toggle("auto", cx + 140, top + 1, v().getBoolean("autoEquip"), () -> act("autoEquip", tag("on", !v().getBoolean("autoEquip"))));
        if (mine()) ui.button("eqall", cx + cw - 150, top - 2, 150, 15, "Equip everyone now", Style.PRIMARY, true, () -> act("equipAll"));
        List<Smithing.Pattern> armors = new ArrayList<>(), weapons = new ArrayList<>();
        for (var p : Smithing.patterns().values()) {
            if (Arrays.stream(Piece.ARMOR).anyMatch(p.pieces()::containsKey)) armors.add(p);
            if (p.pieces().keySet().stream().anyMatch(k -> k.melee() || k.ranged())) weapons.add(p);
        }
        int y = top + 18;
        int rowH = (ch - 50) / UnitType.values().length;
        for (UnitType u : UnitType.values()) {
            CompoundTag k = kits.getCompound(u.name());
            ui.card(cx, y, cw, rowH - 3);
            ui.item(u.icon, cx + 8, y + (rowH - 19) / 2);
            ui.text(u.label, cx + 28, y + (rowH - 11) / 2, TEXT);
            int x = cx + 120;
            // armour
            var ap = Smithing.get(k.getString("armor"));
            ui.text("Armour", x, y + 4, FAINT);
            cycler("ka" + u, x, y + 13, 120, ap == null ? "None" : ap.name(), ap == null ? null : ap.pieces().getOrDefault(Piece.CHESTPLATE, ap.pieces().values().iterator().next()).item(),
                    () -> act("kit", tag("unit", u.name(), "armor", next(armors, k.getString("armor"), true))));
            // weapon
            var wp = Smithing.get(k.getString("weapon"));
            Piece piece;
            try { piece = Piece.valueOf(k.getString("piece")); } catch (Exception e) { piece = Piece.SWORD; }
            final Piece fp = piece;
            x += 128;
            ui.text("Weapon", x, y + 4, FAINT);
            var wr = wp == null ? null : wp.pieces().get(piece);
            cycler("kw" + u, x, y + 13, 120, wp == null ? "None" : wr == null ? wp.name() + " (n/a)" : wr.item().getDescription().getString(), wr == null ? null : wr.item(),
                    () -> act("kit", tag("unit", u.name(), "weapon", next(weapons, k.getString("weapon"), false))));
            x += 128;
            List<Piece> kinds = (u == UnitType.BOWMAN || u == UnitType.CROSSBOWMAN) ? List.of(u == UnitType.BOWMAN ? Piece.BOW : Piece.CROSSBOW) : List.of(Piece.SWORD, Piece.AXE, Piece.MACE, Piece.LANCE);
            if (kinds.size() > 1 && x + 70 < cx + cw) {
                ui.text("Kind", x, y + 4, FAINT);
                ui.button("kp" + u, x, y + 13, 60, 15, fp.label, Style.SECONDARY, mine(), () -> {
                    int i = kinds.indexOf(fp);
                    act("kit", tag("unit", u.name(), "piece", kinds.get((i + 1) % kinds.size()).name()));
                });
                x += 66;
            }
            if (u == UnitType.SHIELDBEARER || u == UnitType.FOOTMAN || u == UnitType.HORSEMAN) {
                if (x + 40 < cx + cw) {
                    ui.text("Shield", x, y + 4, FAINT);
                    if (mine()) ui.toggle("ks" + u, x, y + 15, k.getBoolean("shield"), () -> act("kit", tag("unit", u.name(), "shield", !k.getBoolean("shield"))));
                }
            }
            y += rowH;
        }
    }

    void cycler(String id, int x, int y, int w, String label, Item icon, Runnable next) {
        boolean on = mine() && ui.over(x, y, w, 15);
        float hv = ui.hover(id, on);
        ui.round(x, y, w, 15, 4, lerp(0xFF2A303B, 0xFF39414E, hv));
        int tx = x + 5;
        if (icon != null) { ui.itemScaled(new ItemStack(icon), x + 3, y, 0.85f); tx = x + 19; }
        ui.text(ui.clip(label, w - (tx - x) - 12), tx, y + 4, TEXT);
        ui.text("›", x + w - 8, y + 4, MUTED);
        if (mine()) ui.area(x, y, w, 15, next);
    }

    static String next(List<Smithing.Pattern> list, String cur, boolean allowNone) {
        List<String> ids = new ArrayList<>();
        if (allowNone) ids.add("");
        list.forEach(p -> ids.add(p.id()));
        int i = ids.indexOf(cur);
        return ids.get((i + (Screen.hasShiftDown() ? ids.size() - 1 : 1)) % ids.size());
    }

    // ------------------------------------------------------------------ blacksmith

    static final String[] FORGE_PIECES = {"SET", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "SWORD", "AXE", "MACE", "LANCE", "SHIELD", "BOW", "CROSSBOW", "ARROWS"};
    static final Item[] FORGE_ICONS = {Items.ARMOR_STAND, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.IRON_SWORD,
            Items.IRON_AXE, Items.IRON_SHOVEL, Items.TRIDENT, Items.SHIELD, Items.BOW, Items.CROSSBOW, Items.ARROW};

    void forge() {
        milTab = 2;
        int smiths = v().getCompound("jobs").contains("SMITH") ? v().getCompound("jobs").getIntArray("SMITH")[0] : 0;
        header("Blacksmith", v().getInt("smithy") == 0 ? "Build a Smithy to take orders" : smiths + " smith" + (smiths == 1 ? "" : "s") + " at the forge", cw - 252);
        milTabs();
        int top = cy + 30;
        Map<Item, Integer> stock = stock();
        Set<String> done = new HashSet<>(strings("research"));
        // pieces
        int lw = 92;
        ui.card(cx, top, lw, ch - 98);
        int y = top + 4;
        for (int i = 0; i < FORGE_PIECES.length; i++) {
            final String p = FORGE_PIECES[i];
            boolean sel = p.equals(forgePiece), on = ui.over(cx + 2, y, lw - 4, 15);
            if (sel) ui.round(cx + 3, y, lw - 6, 15, 4, 0xFF2E3542);
            else if (on) ui.round(cx + 3, y, lw - 6, 15, 4, 0x18FFFFFF);
            ui.itemScaled(new ItemStack(FORGE_ICONS[i]), cx + 6, y + 1, 0.75f);
            ui.text(p.equals("SET") ? "Full armour set" : Piece.valueOf(p).label, cx + 22, y + 4, sel ? GOLD : TEXT);
            ui.area(cx + 2, y, lw - 4, 15, () -> { forgePiece = p; forgePattern = null; });
            y += 15;
            if (y > top + ch - 98 - 15) break;
        }
        // patterns offering it
        int mx = cx + lw + 6, mw = 132;
        ui.card(mx, top, mw, ch - 98);
        List<Smithing.Pattern> pats = new ArrayList<>();
        for (var p : Smithing.patterns().values()) {
            if (forgePiece.equals("SET")) { if (Arrays.stream(Piece.ARMOR).filter(p.pieces()::containsKey).count() >= 3) pats.add(p); }
            else if (p.pieces().containsKey(Piece.valueOf(forgePiece))) pats.add(p);
        }
        if (forgePattern == null || pats.stream().noneMatch(p -> p.id().equals(forgePattern))) {
            forgePattern = pats.stream().filter(p -> p.research() == null || done.contains(p.research())).map(Smithing.Pattern::id).reduce((a, b) -> b)
                    .orElse(pats.isEmpty() ? null : pats.get(0).id());
        }
        int off = ui.beginScroll("pats", mx + 2, top + 2, mw - 4, ch - 102, pats.size() * 20);
        y = top + 3 - off;
        for (var p : pats) {
            final String id = p.id();
            boolean sel = id.equals(forgePattern), on = ui.over(mx + 2, y, mw - 4, 19);
            boolean known = researched(p, done);
            if (sel) ui.round(mx + 3, y, mw - 6, 19, 4, 0xFF2E3542);
            else if (on) ui.round(mx + 3, y, mw - 6, 19, 4, 0x18FFFFFF);
            ui.round(mx + 7, y + 6, 7, 7, 2, opaque(p.color()));
            ui.text(ui.clip(p.name(), mw - 34), mx + 19, y + 5, known ? (sel ? GOLD : TEXT) : FAINT);
            if (!known) ui.text("🔒", mx + mw - 16, y + 5, RED);
            ui.area(mx + 2, y, mw - 4, 19, () -> forgePattern = id);
            y += 20;
        }
        if (pats.isEmpty()) ui.text("None", mx + 8, top + 8, FAINT);
        ui.endScroll();
        // order card
        int dx = mx + mw + 6, dw = cx + cw - dx;
        ui.card(dx, top, dw, ch - 98);
        var pat = forgePattern == null ? null : Smithing.get(forgePattern);
        if (pat != null) {
            List<Piece> pieces = new ArrayList<>();
            if (forgePiece.equals("SET")) { for (Piece p : Piece.ARMOR) if (pat.pieces().containsKey(p)) pieces.add(p); }
            else pieces.add(Piece.valueOf(forgePiece));
            int ix = dx + 8;
            for (Piece p : pieces) {
                var r = pat.pieces().get(p);
                ui.round(ix, top + 8, 26, 26, 5, 0xFF141820);
                ui.itemTip(new ItemStack(r.item(), r.count()), ix + 5, top + 13);
                ix += 30;
            }
            ui.text(ui.clip(pat.name() + (forgePiece.equals("SET") ? " set" : " " + Piece.valueOf(forgePiece).label.toLowerCase(Locale.ROOT)), dw - 16), dx + 8, top + 40, TEXT);
            String lock = null;
            for (Piece p : pieces) {
                var r = pat.pieces().get(p);
                if (r.research() != null && !done.contains(r.research())) { lock = "Research " + researchName(r.research()); break; }
            }
            if (lock == null && v().getInt("smithy") == 0) lock = "Build a Smithy";
            ui.text(lock == null ? "Ready to forge" : "🔒 " + lock, dx + 8, top + 51, lock == null ? GREEN : RED);
            // costs
            Map<String, Integer> cost = new LinkedHashMap<>();
            float work = 0;
            for (Piece p : pieces) { pat.pieces().get(p).cost().forEach((k, n) -> cost.merge(k, n * forgeQty, Integer::sum)); work += p.work * forgeQty; }
            int cy2 = top + 66;
            ui.text("Materials", dx + 8, cy2, MUTED);
            cy2 += 11;
            boolean afford = true;
            for (var e : cost.entrySet()) {
                int has = have(stock, e.getKey());
                boolean ok = has >= e.getValue();
                afford &= ok;
                ui.itemTip(icon(e.getKey()), dx + 8, cy2);
                ui.text(ui.clip(ingName(e.getKey()), dw - 90), dx + 28, cy2 + 4, TEXT);
                ui.right(num(has) + " / " + num(e.getValue()), dx + dw - 10, cy2 + 4, ok ? GREEN : RED);
                cy2 += 18;
            }
            // quantity
            int qy = top + ch - 98 - 44;
            ui.text("Quantity", dx + 8, qy + 4, MUTED);
            int qx = dx + 58;
            ui.button("q-10", qx, qy, 22, 15, "−10", Style.SECONDARY, forgeQty > 1, () -> forgeQty = Math.max(1, forgeQty - 10));
            ui.button("q-1", qx + 24, qy, 16, 15, "−", Style.SECONDARY, forgeQty > 1, () -> forgeQty--);
            ui.center(String.valueOf(forgeQty), qx + 54, qy + 4, TEXT);
            ui.button("q+1", qx + 68, qy, 16, 15, "+", Style.SECONDARY, forgeQty < 64, () -> forgeQty++);
            ui.button("q+10", qx + 86, qy, 22, 15, "+10", Style.SECONDARY, forgeQty < 64, () -> forgeQty = Math.min(64, forgeQty + 10));
            double days = smiths == 0 ? -1 : work / smiths;
            String when = days < 0 ? "no smiths" : eta(days);
            // beside the quantity buttons if there's room, otherwise just above them
            if (dx + dw - 8 - ui.width(when) > qx + 112) ui.right(when, dx + dw - 8, qy + 4, FAINT);
            else ui.right(when, dx + dw - 8, qy - 10, FAINT);
            if (mine()) {
                final boolean ok = lock == null && afford;
                ui.button("order", dx + 6, qy + 21, dw - 12, 17, ok ? "Place order" : lock != null ? "Locked" : "Not enough materials", Style.PRIMARY, ok,
                        () -> act("order", tag("pattern", pat.id(), "piece", forgePiece, "qty", forgeQty)));
            }
        }
        // order book
        int oy = top + ch - 92;
        ui.card(cx, oy, cw, 62);
        ui.text("Order book", cx + 8, oy + 6, MUTED);
        ListTag orders = list("orders");
        int ow = 150, x = cx + 8;
        int off2 = 0;
        for (int i = 0; i < orders.size() && x + ow <= cx + cw; i++) {
            CompoundTag o = orders.getCompound(i);
            var p = Smithing.get(o.getString("pattern"));
            Piece pc = Piece.valueOf(o.getString("piece"));
            var r = p == null ? null : p.pieces().get(pc);
            ui.round(x, oy + 18, ow - 6, 38, 4, 0xFF161A20);
            if (r != null) ui.item(r.item(), x + 4, oy + 22);
            ui.text(ui.clip((p == null ? "?" : p.name()) + " " + pc.label.toLowerCase(Locale.ROOT), ow - 50), x + 24, oy + 21, TEXT);
            ui.text(o.getInt("done") + " / " + o.getInt("qty"), x + 24, oy + 31, MUTED);
            float frac = (o.getInt("done") + o.getFloat("progress") / pc.work) / Math.max(1, o.getInt("qty"));
            ui.bar(x + 4, oy + 46, ow - 14, 4, frac, ORANGE, 0xFFC07030);
            final int id = o.getInt("id");
            if (mine()) ui.button("co" + id, x + ow - 24, oy + 20, 16, 13, "×", Style.GHOST, true, () -> act("cancelOrder", tag("id", id)));
            x += ow;
            off2++;
        }
        if (orders.isEmpty()) ui.text("No orders. Finished pieces go into the stockpile, ready for your soldiers.", cx + 8, oy + 24, FAINT);
        else if (off2 < orders.size()) ui.right("+" + (orders.size() - off2) + " more", cx + cw - 8, oy + 6, FAINT);
    }

    static boolean researched(Smithing.Pattern p, Set<String> done) {
        if (p.research() != null && !done.contains(p.research())) return false;
        return true;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (search.visible && search.mouseClicked(x, y, button)) { setFocused(search); return true; }
        search.setFocused(false);
        if (button == 0 && ui.click(x, y)) return true;
        if (button == 0 && tab == Tab.MAP && x >= mapX && x < mapX + mapW && y >= mapY && y < mapY + mapH) { dragMap = true; return true; }
        if (button == 0 && tab == Tab.RESEARCH && x >= resX && x < resX + resW && y >= resY && y < resY + resH) { dragRes = true; selResearch = null; return true; }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        dragMap = dragRes = false;
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragMap) { mapPanX += dx; mapPanY += dy; return true; }
        if (dragRes) { resPanX = Mth.clamp(resPanX + (float) dx, -900, 40); resPanY = Mth.clamp(resPanY + (float) dy, -400, 40); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (tab == Tab.MAP && x >= mapX && x < mapX + mapW && y >= mapY && y < mapY + mapH) {
            float old = mapZoom;
            mapZoom = Mth.clamp(mapZoom * (delta > 0 ? 1.25f : 0.8f), 0.6f, 6f);
            // zoom around the cursor
            float cxm = mapX + mapW / 2f + mapPanX, cym = mapY + mapH / 2f + mapPanY;
            mapPanX += (float) ((x - cxm) * (1 - mapZoom / old));
            mapPanY += (float) ((y - cym) * (1 - mapZoom / old));
            return true;
        }
        if (tab == Tab.RESEARCH && y >= resY && y < resY + resH) {
            if (Screen.hasShiftDown()) resPanX = Mth.clamp(resPanX + (float) delta * 30, -900, 40);
            else resPanY = Mth.clamp(resPanY + (float) delta * 20, -400, 40);
            return true;
        }
        return ui.scroll(x, y, delta) || super.mouseScrolled(x, y, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (search.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) return search.keyPressed(key, scan, mods) || true;
        if (key == livingrealms.client.ClientSetup.OPEN.getKey().getValue()) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (search.isFocused()) return search.charTyped(c, mods);
        return super.charTyped(c, mods);
    }

    static Item plansItem() { return Registry.PLANNER.get(); }

    /** For the automated test tour. */
    public void tour(String tabName, int sub, String select) {
        tab = Tab.valueOf(tabName);
        milTab = sub;
        if (tab == Tab.BUILD) selType = select;
        if (tab == Tab.RESEARCH) selResearch = select;
        if (tab == Tab.FORGE && select != null) { forgePiece = "SET"; forgePattern = select; forgeQty = 4; }
        if (tab == Tab.MAP || tab == Tab.PROJECTS) selPlot = select == null ? -1 : Integer.parseInt(select);
    }
}
