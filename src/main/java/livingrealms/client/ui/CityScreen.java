package livingrealms.client.ui;

import livingrealms.Registry;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.client.ClientState;
import livingrealms.client.Placement;
import livingrealms.military.Stance;
import livingrealms.military.SoldierRanks;
import livingrealms.military.SoldierSkills;
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
        STORAGE("Stockpile", Items.CHEST), RESEARCH("Research", Items.ENCHANTED_BOOK), MILITARY("Military", Items.IRON_SWORD), FORGE("Blacksmith", Items.ANVIL),
        KINGDOM("Kingdom", Items.GOLDEN_HELMET), TRADE("Trade", Items.EMERALD), QUESTS("Quests", Items.WRITABLE_BOOK),
        CHRONICLE("Chronicle", Items.WRITTEN_BOOK), PEOPLE("People", Items.PLAYER_HEAD);
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
    /** Layout size the City Manager wants; smaller windows draw at a finer GUI scale (see Imm.fit). */
    static final int MIN_W = 480, MIN_H = 300;
    /** Largest panel; big GUIs get a roomier window rather than a sea of empty screen. */
    static final int MAX_W = 600, MAX_H = 360;
    private float fit = 1;

    // selections
    private String selType;
    private String catFilter = "ALL", styleFilter = "ALL";
    private int selPlot = -1;
    private String selResearch;
    private int milTab;
    private UUID selectedSoldier;
    private String soldierTab = "Skills";
    private final SoldierPanels panels = new SoldierPanels(ui, new SoldierPanels.Host() {
        public boolean canCommand() { return mine(); }
        public void act(String action, CompoundTag data) { CityScreen.this.act(action, data); }
        public boolean hasResearch(String id) { return strings("research").contains(id); }
        public int emeralds() { return stock().getOrDefault(Items.EMERALD, 0); }
        public void dismissed() { selectedSoldier = null; }
    });
    private String forgePiece = "SET", forgePattern;
    private int forgeQty = 1, trainQty = 1;
    private UnitType selUnit = UnitType.FOOTMAN;
    private String roadMaterial = "path";
    private int roadWidth = 3;
    // map and research canvas
    private float mapZoom = 1, mapPanX, mapPanY, resPanX, resPanY;
    private boolean dragMap, dragRes;
    private String mapPlan;
    private int mapRotation;
    private float mapScale, mapOriginX, mapOriginY;
    private int mapX, mapY, mapW, mapH, resX, resY, resW, resH;
    /** City Map: the category picked out in the legend (others dimmed), a plot to centre on next frame, the zoom controls. */
    private Category mapFocus;
    private int mapCenterOn = -1, mapCtlX, mapCtlY, mapCtlW, mapCtlH;

    public CityScreen(String tab) {
        super(Component.literal("City Manager"));
        this.tab = switch (tab) {
            case "build" -> Tab.BUILD;
            case "forge" -> Tab.FORGE;
            case "military" -> Tab.MILITARY;
            default -> lastTab;
        };
    }

    public void beginMapPlan(String type) {
        mapPlan = type; mapRotation = 0; selPlot = -1; dragMap = false; switchTab(Tab.MAP);
    }

    public void showSoldierTab(int index) {
        soldierTab = SoldierPanels.TABS[Math.max(0, Math.min(SoldierPanels.TABS.length - 1, index))];
        panels.resetScroll();
    }

    /** Opens an individual recruit from the roster or a development tour. */
    public void showSoldier(UUID id) {
        tab = Tab.MILITARY;
        milTab = 0;
        selectedSoldier = id;
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
        // lay out in finer units when the GUI scale leaves too little room (common on high-DPI screens at Auto)
        fit = ui.scale = Imm.fit(MIN_W, MIN_H);
        width = Math.round(minecraft.getWindow().getGuiScaledWidth() / fit);
        height = Math.round(minecraft.getWindow().getGuiScaledHeight() / fit);
        pw = Math.min(width - 12, MAX_W);
        ph = Math.min(height - 12, MAX_H);
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
    public void render(GuiGraphics g, int rawX, int rawY, float partial) {
        int mx = (int) (rawX / fit), my = (int) (rawY / fit);
        g.pose().pushPose();
        g.pose().scale(fit, fit, 1);
        renderBackground(g);
        ui.begin(g, mx, my, partial);
        float open = Math.min(1, (System.currentTimeMillis() - openedAt) / 160f);
        g.pose().pushPose();
        g.pose().translate(0, (1 - open) * 8, 0);
        ui.shadow(px, py, pw, ph);
        ui.round(px, py, pw, ph, 8, 0xFF2A313B);
        ui.round(px + 1, py + 1, pw - 2, ph - 2, 7, 0xFF181C22, 0xFF13161B);
        sidebar();
        boolean showSearch = tab == Tab.BUILD || tab == Tab.STORAGE || tab == Tab.FORGE || tab == Tab.PEOPLE;
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
                case KINGDOM -> KingdomPanel.draw(ui, cx, cy, cw, ch, v().getCompound("statecraft"), this::act);
                case TRADE -> TradePanel.draw(ui, cx, cy, cw, ch, v().getCompound("trade"), this::act);
                case QUESTS -> QuestPanel.draw(ui, cx, cy, cw, ch, v().getCompound("quests"), this::act);
                case CHRONICLE -> ChroniclePanel.draw(ui, cx, cy, cw, ch, v(), mine(), this::act);
                case PEOPLE -> PeoplePanel.draw(ui, cx, cy, cw, ch, v().getCompound("people"), mine(), search, this::act);
            }
        } catch (Exception e) {
            ui.text("Something went wrong drawing this page: " + e, cx, cy + 20, RED);
        }
        g.pose().popPose();
        super.render(g, mx, my, partial);
        ui.end();
        g.pose().popPose();
    }

    void sidebar() {
        int x = px + 8, y = py + 10, w = 96;
        int tier = v().getInt("tier");
        int crest = tierColor(tier);
        ui.round(x, y, 28, 28, 6, brighten(crest, 30), crest);
        String name = v().getString("name");
        ui.center(name.isEmpty() ? "?" : name.substring(0, 1), x + 14, y + 10, 0xFF1A1408);
        ui.text(ui.clip(name, w - 34), x + 34, y + 3, TEXT);
        String tierLabel = Tier.of(Math.max(1, tier)).label;
        ui.text(ui.clip(tierLabel, w - 34), x + 34, y + 15, crest);
        if (v().getBoolean("alarm")) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 150.0));
            int ax = x + 34 + ui.width(tierLabel) + 4;
            // the pill when it fits beside the rank, otherwise a pulsing badge on the crest
            if (ax + ui.pillWidth("ALARM") <= x + w) ui.pill("ALARM", ax, y + 13, lerp(0xFF8A2020, 0xFFC83838, pulse), 0xFFFFFFFF);
            else { ui.round(x + 20, y - 2, 10, 10, 5, 0xFF121519); ui.round(x + 21, y - 1, 8, 8, 4, lerp(0xFF8A2020, 0xFFE04040, pulse)); }
            if (ui.over(x, y - 2, w, 30)) ui.tip("Alarm raised", v().getString("raidStatus").isEmpty() ? "Soldiers rally to defend the town." : v().getString("raidStatus"));
        }
        y += 36;
        ui.hline(x, y, w, LINE);
        y += 6;
        // short windows: a one-line footer and tighter tabs, so the tabs never run into it
        Tab[] tabs = Tab.values();
        boolean compact = py + ph - 46 - 6 - y < tabs.length * 21;
        int fy = py + ph - (compact ? 20 : 46), pitch = Math.max(17, Math.min(21, (fy - 6 - y) / tabs.length));
        var hotbar = Minecraft.getInstance().options.keyHotbarSlots;
        for (int i = 0; i < tabs.length; i++) {
            final Tab tt = tabs[i];
            if (ui.iconButton("nav" + tt, x - 2, y, w + 4, pitch - 1, tt.icon, tt.label, tab == tt, () -> switchTab(tt)) && i < hotbar.length)
                ui.tip(tt.label, "Shortcut: " + hotbar[i].getTranslatedKeyMessage().getString() + "  ·  Tab / Shift+Tab cycles pages");
            y += pitch;
        }
        // footer: quick figures
        ui.hline(x, fy - 6, w, LINE);
        double happy = v().getDouble("happy");
        String owner = !v().getBoolean("ai") ? "Lord " + v().getString("owner") : v().getString("owner").isEmpty() ? "Independent town" : "Held by " + v().getString("owner");
        String people = v().getInt("pop") + " / " + v().getInt("housing");
        if (compact) {
            ui.itemScaled(new ItemStack(Items.PLAYER_HEAD), x, fy, 0.75f);
            ui.text(people, x + 15, fy + 2, TEXT);
            int bx = x + 19 + ui.width(people);
            ui.bar(bx, fy + 3, x + w - bx, 5, (float) happy / 100f, moodColor(happy), moodColor(happy));
            if (ui.over(x, fy - 2, w, 14)) ui.tip("People " + people + " (housing)", "Mood " + (int) happy + "%", owner);
            return;
        }
        ui.item(Items.PLAYER_HEAD, x, fy - 2);
        ui.text(people, x + 20, fy + 2, TEXT);
        ui.text("Mood", x, fy + 18, MUTED);
        ui.bar(x + 30, fy + 19, w - 30, 6, (float) happy / 100f, moodColor(happy), moodColor(happy));
        if (ui.over(x, fy + 16, w, 10)) ui.tip("Mood " + (int) happy + "%");
        ui.text(ui.clip(owner, w), x, fy + 30, FAINT);
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
        header(v().getString("name"), Tier.of(v().getInt("tier")).label + " · " + style + (v().getBoolean("ai") && v().getString("owner").isEmpty() ? " · independent" : " · held by " + v().getString("owner"))
                + " · land " + v().getInt("radius") + " blocks");
        int warY = cy + 30;
        int cities = v().getInt("kingdomCities");
        ui.text(ui.clip((v().getBoolean("capital") ? "KINGDOM CAPITAL" : "Kingdom city") + " · " + cities + (cities == 1 ? " city" : " cities"), cw - 116), cx, warY, GOLD);
        if (ui.over(cx, warY, cw - 112, 13)) ui.tip("Each kingdom has one capital, even before Capital rank.",
                "Capture a capital to take all its cities. Other victories take only the attacked city.");
        // the seat of the kingdom has nothing to move: no greyed-out "Make capital" there
        boolean seat = v().getBoolean("ruler") && v().getBoolean("capital");
        if (seat) {}
        else if (v().getBoolean("ruler")) ui.button("capital", cx + cw - 108, warY - 2, 108, 16, "Make capital", Style.PRIMARY,
                v().getBoolean("canMoveCapital"), () -> act("capital"));
        else ui.button("raid", cx + cw - 108, warY - 2, 108, 16, "Raid this city", Style.PRIMARY,
                v().getBoolean("canRaid"), () -> act("raid"));
        if (!seat && ui.over(cx + cw - 108, warY - 2, 108, 16)) ui.tip(v().getBoolean("ruler")
                ? "Move the seat of your kingdom here. Old capital returns to City rank if needed."
                : "Dispatch half the nearest eligible city's soldiers (2–12); at least 3 must be at home.",
                v().getBoolean("ruler") ? "Unavailable during raids or within a day of moving it."
                : "Defeat local defenders, then hold the center for 60 seconds. Both cities and armies must stay loaded.");
        String raid = v().getString("raidStatus");
        CompoundTag vx = v().getCompound("vx");
        if (!v().getCompound("raid").isEmpty()) raid = ""; // the raid card below shows it with progress
        if (raid.isEmpty() && vx.getBoolean("mirror")) raid = "A Village Expansion village of " + vx.getString("factionName") + " · " + vx.getString("status");
        String line = raid.isEmpty() ? "Capital loss transfers the kingdom; other losses transfer one city." : raid;
        ui.text(ui.clip(line, mine() ? cw - 120 : cw), cx, warY + 18, raid.isEmpty() ? MUTED : ORANGE);
        if (ui.over(cx, warY + 16, mine() ? cw - 120 : cw, 12) && ui.width(line) > (mine() ? cw - 120 : cw)) ui.tip(line);
        if (mine()) {
            ui.button("autoBuild", cx + cw - 112, warY + 16, 112, 16, "Auto build: " + (v().getBoolean("autoBuild") ? "ON" : "OFF"),
                    Style.SECONDARY, true, () -> act("autoBuild", tag("on", !v().getBoolean("autoBuild"))));
            if (ui.over(cx + cw - 112, warY + 16, 112, 16)) ui.tip(v().getString("autoBuildStatus"),
                    "Villagers choose one affordable building when your queue is empty.", "Your plans pause automatic work. All construction uses town materials.");
        }
        // the body scrolls when the window is too short for it; the chronicle then gets a fixed height
        CompoundTag raidTag = v().getCompound("raid");
        int raidH = raidTag.isEmpty() ? 0 : 46;
        int top = cy + 64, bodyH = raidH + 50 + 110 + (mine() && !v().getBoolean("ai") ? 22 : 0) + (v().getCompound("compat").getList("lines", Tag.TAG_COMPOUND).isEmpty() ? 0 : 22)
                + HardshipPanel.height(v().getCompound("hardship")) + DragonPanel.height(v().getCompound("dragons")) + 70;
        boolean scrolls = top + bodyH > cy + ch;
        int bodyOff = scrolls ? ui.beginScroll("overview", cx, top, cw + 4, cy + ch - top, bodyH + 2) : 0;
        int y = top + 2 - bodyOff, tw = (cw - 9) / 4;
        if (raidH > 0) { livingrealms.client.Hud.raidCard(ui, raidTag, cx, y, cw, false); y += raidH; }
        int pop = v().getInt("pop"), housing = v().getInt("housing");
        tile(cx, y, tw, "People", pop + " / " + housing, (float) Math.max(0, v().getDouble("growth")), BLUE,
                pop >= housing ? "Housing full" : v().getBoolean("starving") ? "Starving" : "Growing", Items.PLAYER_HEAD);
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
        // rank: progress towards the next one, or the capital at a glance
        int rw = (int) (cw * 0.55), rh = 104;
        ui.card(cx, y, rw, rh);
        Tier cur = Tier.of(v().getInt("tier")), next = cur.next();
        ui.text("Rank", cx + 8, y + 7, MUTED);
        if (next == null) {
            ui.big(cur.label, cx + 8, y + 18, GOLD, 1.3f);
            ui.right("highest rank", cx + rw - 8, y + 7, FAINT);
            String castle = v().getString("castleStatus");
            if (castle.isEmpty()) castle = "Builders will survey the central district.";
            ui.text(ui.clip(castle, rw - 16), cx + 8, y + 34, MUTED);
            if (ui.over(cx, y + 32, rw, 12)) ui.tip(castle, "41 × 41 perimeter; central 25 × 25 courtyard stays intact.", "Builders use town materials. Clear blocked perimeter plots first.");
            glance(cx + 6, y + 48, rw - 12, stock);
        } else {
            ui.big(cur.label + "  →  " + next.label, cx + 8, y + 19, TEXT, 1.15f);
            List<String> blockers = strings("blockers");
            boolean ai = v().getBoolean("ai");
            int em = stock.getOrDefault(Items.EMERALD, 0), ly = y + 34;
            // label, have, need (need 0: a yes/no requirement with have 1 for yes)
            List<Object[]> reqs = new ArrayList<>();
            if (next == Tier.CAPITAL && !v().getBoolean("capital")) reqs.add(new Object[]{"Be the kingdom capital", 0, 0});
            reqs.add(new Object[]{"People", pop, next.population});
            reqs.add(new Object[]{"Buildings", v().getInt("buildings"), next.buildings});
            if (next.research != null) reqs.add(new Object[]{"Research " + researchName(next.research), strings("research").contains(next.research) ? 1 : 0, 0});
            if (!ai) reqs.add(new Object[]{"Finished Town Hall", v().getBoolean("townHall") ? 1 : 0, 0});
            if (!ai) reqs.add(new Object[]{"Emeralds", em, next.emeralds});
            int barW = Math.min(60, rw / 4), valR = cx + rw - 12 - barW;
            for (Object[] r : reqs) {
                int have = (Integer) r[1], goal = (Integer) r[2];
                boolean ok = goal > 0 ? have >= goal : have > 0;
                ui.text(ok ? "✔" : "✖", cx + 10, ly, ok ? GREEN : RED);
                if (goal > 0) {
                    String val = num(have) + " / " + num(goal);
                    ui.text(ui.clip((String) r[0], valR - ui.width(val) - 6 - cx - 22), cx + 22, ly, ok ? TEXT : MUTED);
                    ui.right(val, valR, ly, ok ? MUTED : ORANGE);
                    ui.bar(valR + 4, ly + 2, barW, 4, have / (float) goal, ok ? GREEN : GOLD, ok ? GREEN : GOLD_DK);
                } else ui.text(ui.clip((String) r[0], rw - 30), cx + 22, ly, ok ? TEXT : MUTED);
                ly += 11;
            }
            if (mine()) {
                ui.button("advance", cx + rw - 80, y + 4, 74, 15, "Claim rank", Style.PRIMARY, blockers.isEmpty(), () -> act("tier"));
                if (ui.over(cx + rw - 80, y + 4, 74, 15)) {
                    List<String> tip = new ArrayList<>(List.of(blockers.isEmpty() ? "Every requirement is met: rise to " + next.label + "." : "Still needed:"));
                    blockers.forEach(b -> tip.add("· " + b));
                    ui.tip(tip);
                }
            }
        }
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
        int off = ui.beginScroll("jobs", tx + 4, jy + 17, txw - 8, Math.max(11, (jh - 20) / 11 * 11), keys.size() * 11);
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
        if (mine() && !v().getBoolean("ai")) { autonomy(y); y += 22; }
        y += CompatPanel.draw(ui, cx, y, cw, v().getCompound("compat"));
        y += HardshipPanel.draw(ui, cx, y, cw, v().getCompound("hardship"), mine() && !v().getBoolean("ai"), this::act);
        y += DragonPanel.draw(ui, cx, y, cw, v().getCompound("dragons"));
        // chronicle
        int lh = scrolls ? 70 : cy + ch - y;
        ui.card(cx, y, cw, lh);
        ui.text("Town log", cx + 8, y + 6, MUTED);
        List<String> log = strings("log");
        int off2 = ui.beginScroll("log", cx + 4, y + 18, cw - 8, Math.max(11, (lh - 22) / 11 * 11), log.size() * 11);
        int ly = y + 19 - off2;
        for (int i = 0; i < log.size(); i++) {
            ui.round(cx + 10, ly + 3, 3, 3, 1, i == 0 ? GOLD : FAINT);
            ui.text(ui.clip(log.get(i), cw - 30), cx + 18, ly, i == 0 ? TEXT : MUTED);
            ly += 11;
        }
        ui.endScroll();
        if (scrolls) ui.endScroll();
    }

    /** Switches for what the town does on its own while you are away. */
    void autonomy(int y) {
        ui.card(cx, y, cw, 18);
        // the garrison stepper keeps a fixed width (with its name when the switches can spare the room)
        boolean named = (cw - 12 - 122) / 3 >= 88;
        int x = cx + 6, garW = named ? 122 : 78, gw = (cw - 12 - garW) / 3, skips = v().getInt("researchSkips");
        String active = v().getString("activeResearch"), next = v().getString("nextResearch");
        String now = !active.isEmpty() ? (v().getBoolean("researchAuto") ? "Auto pick: " : "Your pick: ") + researchName(active)
                : !next.isEmpty() ? "Next pick: " + researchName(next) : "Nothing affordable to start";
        String[][] sw = {{"autoResearch", "Research" + (skips > 0 ? " (" + skips + ")" : "")}, {"autoTier", "Rank"}, {"autoTrain", "Training"}};
        String[][] tips = {
                {"Auto research. " + now, "Rank research once the town is half way there, then economy and construction,",
                        "then smithing and military. Your choice always wins; cancelling an auto pick skips it.",
                        skips > 0 ? "Click to allow the " + skips + " skipped projects again." : "No projects skipped."},
                {"Auto rank: claims the next rank once every requirement is met", "and " + v().getInt("tierFunds") + " emeralds are in the treasury, so a 25% reserve stays."},
                {"Auto training: one recruit at a time up to the garrison goal, a third of them ranged.",
                        "Best researched units the armoury can arm; keeps 6 townsfolk and an emerald reserve.",
                        "With auto-equip on, soldiers at home with empty slots are re-equipped."}};
        for (int i = 0; i < sw.length; i++) {
            String key = sw[i][0];
            boolean on = v().getBoolean(key);
            int gx = x + i * gw;
            ui.toggle(key, gx, y + 3, on, () -> act(key, tag("on", !on)));
            ui.text(sw[i][1], gx + 26, y + 5, on ? TEXT : MUTED);
            if (i == 0 && skips > 0) ui.area(gx + 24, y + 2, gw - 28, 14, () -> act("clearSkips"));
            if (ui.over(gx, y + 2, gw - 4, 14)) ui.tip(tips[i]);
        }
        int gx = cx + cw - 6 - garW, goal = v().getInt("garrisonGoal"), cap = v().getInt("garrisonCap");
        boolean fixed = v().getInt("garrisonTarget") >= 0;
        if (named) { ui.text("Garrison", gx, y + 5, MUTED); gx += garW - 74; }
        ui.button("garMinus", gx, y + 2, 14, 14, "-", Style.SECONDARY, goal > 0, () -> act("garrisonTarget", tag("value", Math.max(0, goal - 1))));
        ui.center(v().getInt("garrisonNow") + "/" + goal, gx + 36, y + 5, fixed ? GOLD : TEXT);
        if (fixed) ui.area(gx + 16, y + 2, 40, 14, () -> act("garrisonTarget", tag("value", -1)));
        ui.button("garPlus", gx + 58, y + 2, 14, 14, "+", Style.SECONDARY, goal < cap, () -> act("garrisonTarget", tag("value", Math.min(cap, goal + 1))));
        if (ui.over(cx + cw - 6 - garW, y + 2, garW, 14)) ui.tip("Garrison goal: soldiers and recruits now / goal (" + cap + " places).",
                fixed ? "Set by you; click the number to follow half the capacity again." : "Follows half the garrison capacity.");
    }

    void tile(int x, int y, int w, String label, String value, float frac, int color, String sub, Item icon) {
        ui.card(x, y, w, 44);
        ui.item(icon, x + w - 21, y + 5);
        ui.text(ui.clip(label, w - 30), x + 7, y + 6, MUTED);
        if (ui.over(x, y, w, 44) && ui.width(label) > w - 30) ui.tip(label, sub);
        ui.big(ui.width(value) * 1.2f <= w - 12 ? value : ui.clip(value, (int) ((w - 12) / 1.2f)), x + 7, y + 17, TEXT, 1.2f);
        if (frac > 0) ui.bar(x + 7, y + 33, w - 14, 4, frac, color, color);
        else ui.text(ui.clip(sub, w - 14), x + 7, y + 32, FAINT);
        if (frac > 0 && ui.over(x, y, w, 44)) ui.tip(sub);
    }

    /** The capital at a glance: six figures in two rows, each with a tooltip. */
    void glance(int x, int y, int w, Map<Item, Integer> stock) {
        int built = 0, queued = 0, wallUp = 0, wallAll = 0;
        for (PlotView p : plots()) {
            // the queue counts roads as the Projects page does
            if (!p.state.equals("BUILT")) queued++;
            if (p.type.equals("road")) continue;
            boolean wall = isWall(p);
            if (wall) { wallAll++; if (p.state.equals("BUILT")) wallUp++; }
            else if (p.state.equals("BUILT")) built++;
        }
        int known = strings("research").size(), all = ResearchTree.nodes().size();
        String[][] cells = {
                {"Buildings", String.valueOf(v().getInt("buildings")), built + " standing, not counting walls"},
                {"Walls", wallAll == 0 ? "none" : wallUp + " / " + wallAll, v().getString("wallStatus")},
                {"Queue", String.valueOf(queued), queued == 0 ? "Nothing being built" : queued + " projects under way or waiting (Projects page)"},
                {"Garrison", v().getInt("garrisonNow") + " / " + v().getInt("garrisonCap"), "Soldiers and recruits / places in barracks and towers"},
                {"Research", known >= all ? "all " + all : known + " / " + all, known >= all ? "Every project is known" : (all - known) + " projects still to study"},
                {"Land", v().getInt("radius") + " blocks", "Radius of the town's land from its centre"}};
        int cwid = w / 3;
        for (int i = 0; i < cells.length; i++) {
            int gx = x + (i % 3) * cwid, gy = y + (i / 3) * 25;
            ui.round(gx, gy, cwid - 3, 23, 4, 0xFF181C22);
            ui.text(ui.clip(cells[i][0], cwid - 10), gx + 5, gy + 3, FAINT);
            ui.text(ui.clip(cells[i][1], cwid - 10), gx + 5, gy + 13, TEXT);
            if (ui.over(gx, gy, cwid - 3, 23)) ui.tip(cells[i][0] + ": " + cells[i][1], cells[i][2]);
        }
    }

    static boolean isWall(PlotView p) { return p.tag.getString("bp").startsWith("wall:"); }

    /** "Old-town wall" or "City wall" from a wall piece's key (wall:town:line:index). */
    static String wallLine(PlotView p) {
        String[] k = p.tag.getString("bp").split(":");
        return k.length > 2 && k[2].equals("0") ? "Old-town wall" : "City wall";
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

    static Category plotCategory(PlotView p) {
        if (p.type.equals("road")) return Category.ROAD;
        BuildingType t = BuildingCatalog.get(p.type);
        return t == null ? Category.DECOR : t.category;
    }

    /** Zooms the City Map by a factor around a point on screen, keeping that point still. */
    void zoomMap(float factor, float ax, float ay) {
        float old = mapZoom;
        mapZoom = Mth.clamp(mapZoom * factor, 0.6f, 6f);
        float cxm = mapX + mapW / 2f + mapPanX, cym = mapY + mapH / 2f + mapPanY;
        mapPanX += (ax - cxm) * (1 - mapZoom / old);
        mapPanY += (ay - cym) * (1 - mapZoom / old);
    }

    boolean overMapControls(double x, double y) {
        return x >= mapCtlX && x < mapCtlX + mapCtlW && y >= mapCtlY && y < mapCtlY + mapCtlH;
    }

    static void outline(Imm ui, int x0, int z0, int x1, int z1, int c) {
        ui.hline(x0, z0, x1 - x0, c); ui.hline(x0, z1 - 1, x1 - x0, c); ui.vline(x0, z0, z1 - z0, c); ui.vline(x1 - 1, z0, z1 - z0, c);
    }

    void map() {
        BlockPos center = BlockPos.of(v().getLong("center"));
        int radius = v().getInt("radius");
        int side = Math.max(124, Math.min(150, cw * 2 / 5));
        mapW = cw - side - 6;
        mapH = ch;
        mapX = cx;
        mapY = cy;
        CityMap.ensure(center, radius + 12);
        List<PlotView> plots = plots();
        ui.round(mapX - 1, mapY - 1, mapW + 2, mapH + 2, 6, BORDER);
        ui.round(mapX, mapY, mapW, mapH, 5, 0xFF0E1013);
        ui.pushClip(mapX + 1, mapY + 1, mapX + mapW - 1, mapY + mapH - 1);
        // the terrain fills the frame (a non-square frame crops a little of the outer land rather than leaving bands)
        float base = Math.max(mapW, mapH) / (float) CityMap.size;
        float s = base * mapZoom;
        if (mapCenterOn >= 0) {
            for (PlotView p : plots) if (p.id == mapCenterOn) {
                mapPanX = -((p.x0 + p.x1 + 1) / 2f - center.getX() - 0.5f) * s;
                mapPanY = -((p.z0 + p.z1 + 1) / 2f - center.getZ() - 0.5f) * s;
            }
            mapCenterOn = -1;
        }
        // never pan the town right out of the frame
        float lim = CityMap.size * s / 2f;
        mapPanX = Mth.clamp(mapPanX, -lim, lim);
        mapPanY = Mth.clamp(mapPanY, -lim, lim);
        float ox = mapX + mapW / 2f - (center.getX() - CityMap.originX) * s + mapPanX;
        float oy = mapY + mapH / 2f - (center.getZ() - CityMap.originZ) * s + mapPanY;
        mapScale = s; mapOriginX = ox; mapOriginY = oy;
        ui.g.pose().pushPose();
        ui.g.pose().translate(ox, oy, 0);
        ui.g.pose().scale(s, s, 1);
        ui.g.blit(CityMap.TEX, 0, 0, 0, 0, CityMap.size, CityMap.size, CityMap.size, CityMap.size);
        ui.g.pose().popPose();
        // the ground a shade darker, so the coloured plots stand out on grass of the same green
        ui.rect(mapX, mapY, mapW, mapH, mapFocus == null ? 0x48000000 : 0x90000000);
        // territory ring
        int segs = 120;
        for (int i = 0; i < segs; i++) {
            double a = i * Math.PI * 2 / segs;
            int x = (int) (ox + (center.getX() + 0.5 + Math.cos(a) * radius - CityMap.originX) * s);
            int y = (int) (oy + (center.getZ() + 0.5 + Math.sin(a) * radius - CityMap.originZ) * s);
            ui.rect(x, y, 2, 2, i % 2 == 0 ? GOLD : 0x80E8C15A);
        }
        // planned wall lines: dashed stone-grey outlines (the pieces themselves appear as plots once queued)
        for (Tag wt : v().getList("walls", Tag.TAG_INT_ARRAY)) {
            int[] wv = ((net.minecraft.nbt.IntArrayTag) wt).getAsIntArray();
            for (int i = 0; i + 1 < wv.length; i += 2) {
                int j = (i + 2) % wv.length;
                float ax = ox + (wv[i] + 0.5f - CityMap.originX) * s, ay = oy + (wv[i + 1] + 0.5f - CityMap.originZ) * s;
                float bx = ox + (wv[j] + 0.5f - CityMap.originX) * s, by = oy + (wv[j + 1] + 0.5f - CityMap.originZ) * s;
                int n = Math.max(1, (int) (Math.hypot(bx - ax, by - ay) / 3));
                for (int k = 0; k < n; k++) {
                    if (k % 3 == 2) continue;
                    ui.rect((int) (ax + (bx - ax) * k / n) - 1, (int) (ay + (by - ay) * k / n) - 1, 2, 2, 0xD0C8C2B6);
                }
            }
        }
        PlotView hover = null, selected = null;
        for (PlotView p : plots) {
            int x0 = (int) (ox + (p.x0 - CityMap.originX) * s), z0 = (int) (oy + (p.z0 - CityMap.originZ) * s);
            int x1 = (int) (ox + (p.x1 + 1 - CityMap.originX) * s), z1 = (int) (oy + (p.z1 + 1 - CityMap.originZ) * s);
            Category cat = plotCategory(p);
            int col = cat.color;
            boolean built = p.state.equals("BUILT");
            if (p.type.equals("road")) {
                if (!built) ui.rect(x0, z0, Math.max(1, x1 - x0), Math.max(1, z1 - z0), 0x30FFFFFF);
                continue;
            }
            x1 = Math.max(x1, x0 + 1); z1 = Math.max(z1, z0 + 1);
            boolean dim = mapFocus != null && cat != mapFocus;
            if (!dim) ui.rect(x0, z0, x1 - x0, z1 - z0, alpha(col, mapFocus != null ? 0xD0 : built ? 0xA8 : 0x50));
            outline(ui, x0, z0, x1, z1, dim ? alpha(col, 0x40) : built ? opaque(col) : GOLD);
            if (!built && !dim && x1 - x0 > 6) ui.rect(x0 + 1, z1 - 3, (int) ((x1 - x0 - 2) * p.progress), 2, GOLD);
            if (ui.over(x0, z0, x1 - x0, z1 - z0)) hover = p;
            if (p.id == selPlot) selected = p;
            final int id = p.id;
            if (mapPlan == null) ui.area(x0, z0, x1 - x0, z1 - z0, () -> selPlot = id, false);
        }
        // names on plots big enough to hold them once zoomed in
        if (s >= 2.2f) for (PlotView p : plots) {
            if (p.type.equals("road") || isWall(p) || mapFocus != null && plotCategory(p) != mapFocus) continue;
            int x0 = (int) (ox + (p.x0 - CityMap.originX) * s), z0 = (int) (oy + (p.z0 - CityMap.originZ) * s);
            int x1 = (int) (ox + (p.x1 + 1 - CityMap.originX) * s), z1 = (int) (oy + (p.z1 + 1 - CityMap.originZ) * s);
            // whole names only, on plots wholly in the frame; at the top of the plot so a castle's name clears its hall's
            String label = plotName(p);
            if (z1 - z0 < 12 || x1 - x0 < ui.width(label) + 4 || x0 < mapX || x1 > mapX + mapW || z0 < mapY || z1 > mapY + mapH) continue;
            ui.textShadow(label, x0 + (x1 - x0 - ui.width(label)) / 2, z1 - z0 >= 24 ? z0 + 3 : z0 + (z1 - z0 - 8) / 2, 0xFFF4F1EA);
        }
        // the selection on top of everything: a dark halo and a pulsing white frame that read on any ground
        if (selected != null) {
            int x0 = (int) (ox + (selected.x0 - CityMap.originX) * s), z0 = (int) (oy + (selected.z0 - CityMap.originZ) * s);
            int x1 = Math.max((int) (ox + (selected.x1 + 1 - CityMap.originX) * s), x0 + 1), z1 = Math.max((int) (oy + (selected.z1 + 1 - CityMap.originZ) * s), z0 + 1);
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 220.0));
            outline(ui, x0 - 2, z0 - 2, x1 + 2, z1 + 2, 0xC0000000);
            outline(ui, x0 - 1, z0 - 1, x1 + 1, z1 + 1, lerp(0xFFB0B0B0, 0xFFFFFFFF, pulse));
            outline(ui, x0, z0, x1, z1, lerp(0xFFB0B0B0, 0xFFFFFFFF, pulse));
        }
        // the player
        var pl = Minecraft.getInstance().player;
        if (pl != null) {
            int x = (int) (ox + (pl.getX() - CityMap.originX) * s), y = (int) (oy + (pl.getZ() - CityMap.originZ) * s);
            ui.round(x - 3, y - 3, 6, 6, 3, 0xFFFFFFFF);
            ui.round(x - 2, y - 2, 4, 4, 2, BLUE);
            if (ui.over(x - 3, y - 3, 6, 6)) hover = null;
        }
        if (mapPlan != null && ui.over(mapX, mapY, mapW, mapH)) {
            BuildingType planned = BuildingCatalog.get(mapPlan);
            if (planned != null) {
                int wx = (int)Math.floor((ui.mx - ox) / s) + CityMap.originX;
                int wz = (int)Math.floor((ui.my - oy) / s) + CityMap.originZ;
                Blueprint bp = ClientState.blueprint(planned.blueprintKey(1, v().getString("style")));
                int w = bp == null ? (mapRotation % 2 == 0 ? planned.w : planned.d) : bp.width(net.minecraft.world.level.block.Rotation.values()[mapRotation]);
                int d = bp == null ? (mapRotation % 2 == 0 ? planned.d : planned.w) : bp.depth(net.minecraft.world.level.block.Rotation.values()[mapRotation]);
                int xx = (int)(ox + (wx - CityMap.originX) * s), zz = (int)(oy + (wz - CityMap.originZ) * s);
                ui.rect(xx, zz, Math.max(1, (int)(w*s)), Math.max(1, (int)(d*s)), 0x806FCF7F);
            }
        }
        ui.popClip();
        // zoom controls in the top right corner of the map
        String pct = Math.round(mapZoom * 100) + "%";
        int pctW = ui.width("600%");
        mapCtlW = pctW + 8 + 15 + 1 + 15 + 1 + 22 + 3;
        mapCtlH = 18;
        mapCtlX = mapX + mapW - mapCtlW - 3;
        mapCtlY = mapY + 3;
        ui.round(mapCtlX, mapCtlY, mapCtlW, mapCtlH, 4, 0xE8101317);
        ui.right(pct, mapCtlX + 4 + pctW, mapCtlY + 5, MUTED);
        int bx = mapCtlX + pctW + 8;
        float mcx = mapX + mapW / 2f, mcy = mapY + mapH / 2f;
        ui.button("mapOut", bx, mapCtlY + 2, 15, 14, "−", Style.SECONDARY, mapZoom > 0.61f, () -> zoomMap(0.8f, mcx, mcy));
        if (ui.over(bx, mapCtlY + 2, 15, 14)) ui.tip("Zoom out", "or scroll down over the map");
        ui.button("mapIn", bx + 16, mapCtlY + 2, 15, 14, "+", Style.SECONDARY, mapZoom < 5.99f, () -> zoomMap(1.25f, mcx, mcy));
        if (ui.over(bx + 16, mapCtlY + 2, 15, 14)) ui.tip("Zoom in", "or scroll up over the map");
        ui.button("mapFit", bx + 32, mapCtlY + 2, 22, 14, "Fit", Style.SECONDARY, mapZoom != 1 || mapPanX != 0 || mapPanY != 0, () -> { mapZoom = 1; mapPanX = mapPanY = 0; });
        if (ui.over(bx + 32, mapCtlY + 2, 22, 14)) ui.tip("Whole town in view");
        if (hover != null && !ui.over(mapCtlX, mapCtlY, mapCtlW, mapCtlH)) {
            List<String> tip = new ArrayList<>();
            Category cat = plotCategory(hover);
            tip.add(plotName(hover) + (hover.level > 1 ? " · level " + hover.level : ""));
            tip.add((isWall(hover) ? wallLine(hover) : cat.label) + " · " + stateLabel(hover));
            BuildingType ht = BuildingCatalog.get(hover.type);
            int workers = v().getCompound("plotWorkers").getInt(String.valueOf(hover.id));
            if (ht != null && ht.category.job != null && hover.state.equals("BUILT")) tip.add(workers + " " + ht.category.job.label.toLowerCase(Locale.ROOT) + (workers == 1 ? "" : "s") + " at work");
            if (ht != null && ht.beds > 0) tip.add(ht.beds + (ht.beds == 1 ? " bed" : " beds"));
            if (!hover.status.isEmpty()) tip.add(hover.status);
            if (mapPlan == null) tip.add(hover.id == selPlot ? "Selected" : "Click for details");
            ui.tip(tip);
        }
        String hint = mapPlan == null ? "Scroll or +/− to zoom · drag to pan"
                : "Click to queue · " + livingrealms.client.ClientSetup.ROTATE.getTranslatedKeyMessage().getString() + " rotates · right-drag pans";
        ui.round(mapX + 3, mapY + mapH - 15, Math.min(mapW - 6, ui.width(hint) + 8), 12, 3, 0xE0101317);
        ui.text(ui.clip(hint, mapW - 14), mapX + 7, mapY + mapH - 13, 0xD0FFFFFF);
        // side panel
        int sx = cx + cw - side;
        ui.card(sx, cy, side, ch);
        if (mapPlan != null) {
            BuildingType planned = BuildingCatalog.get(mapPlan);
            ui.text("Plan on City Map", sx + 8, cy + 8, GOLD);
            if (planned != null) ui.wrap(planned.name, sx + 8, cy + 25, side - 16, TEXT);
            int ty = cy + 25 + (planned == null ? 0 : ui.wrapHeight(planned.name, side - 16)) + 6;
            ui.pushClip(sx, ty, sx + side, cy + ch - 50);
            ui.wrap("Click clear terrain to queue this building. Villagers use your stockpile and build it block by block.", sx + 8, ty, side - 16, MUTED);
            ui.popClip();
            ui.button("rotateMap", sx + 8, cy + ch - 46, side - 16, 18, "Rotate: " + mapRotation * 90 + "°", Style.SECONDARY, true, () -> mapRotation = (mapRotation + 1) % 4);
            if (ui.over(sx + 8, cy + ch - 46, side - 16, 18)) ui.tip("Rotate (" + livingrealms.client.ClientSetup.ROTATE.getTranslatedKeyMessage().getString() + ")");
            ui.button("finishMap", sx + 8, cy + ch - 24, side - 16, 18, "Done planning", Style.PRIMARY, true, () -> mapPlan = null);
            return;
        }
        if (selected == null) for (PlotView p : plots) if (p.id == selPlot) selected = p;
        if (selected != null) { plotDetail(selected, sx, cy, side, ch); return; }
        legend(plots, sx, side);
    }

    /** The City Map's legend: the categories this town has, most common first, each clickable to pick it out. */
    void legend(List<PlotView> plots, int sx, int side) {
        ui.text("Legend", sx + 8, cy + 8, MUTED);
        if (mapFocus != null) {
            ui.button("legendAll", sx + side - 52, cy + 5, 46, 13, "Show all", Style.GHOST, true, () -> mapFocus = null);
        }
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        for (PlotView p : plots) if (!p.type.equals("road")) counts.merge(plotCategory(p), 1, Integer::sum);
        List<Category> cats = new ArrayList<>(counts.keySet());
        cats.sort(Comparator.comparingInt((Category c) -> -counts.get(c)).thenComparing(Enum::ordinal));
        int roads = mine() ? 12 + 64 + 20 : 0;
        int ly = cy + 22, rowH = 11;
        String hint = cats.isEmpty() ? "Nothing built yet. Plan buildings on the Build page." : mapFocus == null
                ? "Click a building for details, a category to pick it out." : "Showing " + mapFocus.label.toLowerCase(Locale.ROOT) + ". Click it again for all.";
        int hintH = ui.wrapHeight(hint, side - 16);
        int avail = cy + ch - 6 - roads - hintH - 6 - ly;
        if (cats.size() * rowH > avail && cats.size() * 10 <= avail) rowH = 10;
        int cols = cats.size() * rowH > avail ? 2 : 1, colW = (side - 14) / cols, rowsN = (cats.size() + cols - 1) / cols;
        for (int i = 0; i < cats.size(); i++) {
            Category c = cats.get(i);
            int n = counts.get(c);
            int lx = sx + 7 + (i / rowsN) * colW, yy = ly + (i % rowsN) * rowH;
            boolean focus = c == mapFocus, on = ui.over(lx, yy - 1, colW - 1, rowH);
            if (focus) ui.round(lx, yy - 1, colW - 1, rowH, 3, 0xFF2E3542);
            else if (on) ui.round(lx, yy - 1, colW - 1, rowH, 3, 0x18FFFFFF);
            ui.round(lx + 2, yy + 1, 7, 7, 2, mapFocus != null && !focus ? alpha(c.color, 0x60) : opaque(c.color));
            String count = String.valueOf(n);
            int room = colW - 16 - (cols == 1 ? ui.width(count) + 6 : 0);
            ui.text(ui.clip(c.label, room), lx + 13, yy, focus ? GOLD : mapFocus != null ? MUTED : TEXT);
            if (cols == 1) ui.right(count, lx + colW - 4, yy, FAINT);
            ui.area(lx, yy - 1, colW - 1, rowH, () -> mapFocus = focus ? null : c);
            if (on) ui.tip(c.label + ": " + n + (n == 1 ? " building" : " buildings"), focus ? "Click to show every category" : "Click to pick these out on the map");
        }
        ly += rowsN * rowH + 4;
        ly += ui.wrap(hint, sx + 8, ly, side - 16, FAINT) + 6;
        if (mine()) {
            ui.text("Roads", sx + 8, ly, MUTED);
            ly += 12;
            roadPicker(sx + 6, ly, side - 12);
            ly += 64;
            if (ly + 16 <= cy + ch - 4) {
                ui.button("autoWalls", sx + 6, ly, side - 12, 16, "Auto walls: " + (v().getBoolean("autoWalls") ? "ON" : "OFF"),
                        Style.SECONDARY, true, () -> act("autoWalls", tag("on", !v().getBoolean("autoWalls"))));
                if (ui.over(sx + 6, ly, side - 12, 16)) ui.tip(v().getString("wallStatus"),
                        "Villagers build the dashed wall line: gatehouses on the high streets, towers, then the wall, from the stockpile.",
                        "Old-town wall at Village rank with Fortifications; city wall at City rank. Your own plans come first.");
                ly += 20;
            }
            if (ly + 16 <= cy + ch - 4) {
                // the look villagers build in: cycles the families, then back to whatever the biome says
                String[] families = {"plains", "desert", "savanna", "taiga", "snowy", "biome"};
                String cur = new ResourceLocation(v().getString("style").isEmpty() ? "minecraft:plains" : v().getString("style")).getPath();
                int at = Arrays.asList(families).indexOf(cur);
                String next = families[(at + (Screen.hasShiftDown() ? families.length - 1 : 1) + families.length) % families.length];
                ui.button("townStyle", sx + 6, ly, side - 12, 16, ui.clip("Town style: " + BuildingCatalog.pretty(cur), side - 20),
                        Style.SECONDARY, true, () -> act("style", tag("family", next)));
                if (ui.over(sx + 6, ly, side - 12, 16)) ui.tip("Town style: " + BuildingCatalog.pretty(cur),
                        "The look villagers build in: houses, walls and street paving. Set from the biome when the town was founded.",
                        "Click for " + (next.equals("biome") ? "the biome's own style" : BuildingCatalog.pretty(next)) + " (shift-click goes back). Standing buildings keep their look.");
            }
        }
    }

    void roadPicker(int x, int y, int w) {
        String[] mats = {"path", "gravel", "cobblestone", "stone_bricks", "planks"};
        Item[] icons = {Items.DIRT_PATH, Items.GRAVEL, Items.COBBLESTONE, Items.STONE_BRICKS, Items.SPRUCE_PLANKS};
        for (int i = 0; i < mats.length; i++) {
            final String m = mats[i];
            int pitch = Math.min(26, w / mats.length), bw = pitch - 2, bx = x + i * pitch;
            boolean on = ui.over(bx, y, bw, 22);
            if (m.equals(roadMaterial)) ui.round(bx, y, bw, 22, 4, 0xFF3A4250);
            else if (on) ui.round(bx, y, bw, 22, 4, 0x20FFFFFF);
            ui.item(icons[i], bx + (bw - 16) / 2, y + 3);
            if (on) ui.tip(BuildingCatalog.pretty(m) + " road");
            ui.area(bx, y, bw, 22, () -> roadMaterial = m);
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

    /** Where a plot stands from the town centre: "north-east, 64 blocks out" or "in the centre". */
    static String quarter(PlotView p) {
        BlockPos c = BlockPos.of(v().getLong("center"));
        double dx = (p.x0 + p.x1 + 1) / 2.0 - c.getX(), dz = (p.z0 + p.z1 + 1) / 2.0 - c.getZ(), dist = Math.hypot(dx, dz);
        if (dist < 16) return "in the centre";
        String[] dirs = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        int i = (int) Math.floorMod(Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8);
        return dirs[i] + ", " + (int) dist + " blocks out";
    }

    void plotDetail(PlotView p, int x, int y, int w, int h) {
        BuildingType t = BuildingCatalog.get(p.type);
        Category cat = plotCategory(p);
        boolean road = p.type.equals("road"), built = p.state.equals("BUILT"), wall = isWall(p);
        ui.item(cat.icon, x + 8, y + 8);
        ui.text(ui.clip(plotName(p), w - 34), x + 28, y + 8, TEXT);
        if (ui.over(x, y + 6, w, 12) && ui.width(plotName(p)) > w - 34) ui.tip(plotName(p));
        String kind = wall ? wallLine(p) : cat.label + (p.level > 0 && !road ? " · level " + p.level : "");
        ui.text(ui.clip(kind, w - 34), x + 28, y + 18, opaque(cat.color));
        int yy = y + 34;
        ui.text(ui.clip(stateLabel(p), w - 16), x + 8, yy, built ? GREEN : p.state.equals("DEMOLISHING") ? RED : GOLD);
        yy += 11;
        if (!built) {
            ui.bar(x + 8, yy, w - 16, 5, p.progress, GOLD, GOLD_DK);
            yy += 9;
            if (!p.status.isEmpty()) { yy += ui.wrap(p.status, x + 8, yy, w - 16, ORANGE); }
            boolean auto = p.tag.getBoolean("automatic");
            int prio = p.tag.getInt("prio");
            String who = (auto ? "Chosen by the villagers" : "Your plan") + (!auto && prio != 0 ? " · priority " + (prio > 0 ? "+" : "") + prio : "");
            ui.text(ui.clip(who, w - 16), x + 8, yy, FAINT);
            yy += 11;
        }
        yy += ui.wrap((p.x1 - p.x0 + 1) + "×" + (p.z1 - p.z0 + 1) + " · " + quarter(p), x + 8, yy, w - 16, FAINT) + 1;
        int workers = v().getCompound("plotWorkers").getInt(String.valueOf(p.id));
        if (t != null && t.category.job != null && built) {
            ui.text(workers + " " + t.category.job.label.toLowerCase(Locale.ROOT) + (workers == 1 ? "" : "s") + " at work", x + 8, yy, TEXT);
            yy += 11;
        }
        if (t != null && t.beds > 0) { ui.text(t.beds + (t.beds == 1 ? " bed" : " beds"), x + 8, yy, MUTED); yy += 11; }
        // the buttons stack up from the bottom; single-level buildings (walls) have no upgrade row
        int maxLevel = v().getCompound("maxLevels").getInt(String.valueOf(p.id));
        boolean upgradable = built && t != null && t.maxLevel > 1;
        int rows = 1 + (!mine() ? 0 : built && t != null ? (upgradable ? 2 : 1) : p.state.equals("DEMOLISHING") ? 0 : 2);
        int bx = x + 6, bw = w - 12, by = y + h - 4 - rows * 19;
        // bill of what's left
        CompoundTag bill = v().getCompound("bills").getCompound(String.valueOf(p.id));
        if (!bill.isEmpty() && by - yy > 30) {
            yy += 2;
            ui.text("Materials", x + 8, yy, MUTED);
            yy += 11;
            Map<Item, Integer> stock = stock();
            int bh = by - yy - 4;
            List<String> keys = new ArrayList<>(bill.getAllKeys());
            keys.sort(Comparator.comparingInt(k -> -bill.getInt(k)));
            int off = ui.beginScroll("bill" + p.id, x + 4, yy, w - 8, Math.max(18, bh / 18 * 18), keys.size() * 18);
            int ry = yy - off;
            for (String k : keys) {
                ItemStack st = new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(k)));
                ui.itemTip(st, x + 8, ry);
                int need = bill.getInt(k), has = stock.getOrDefault(st.getItem(), 0);
                String n = num(has) + "/" + num(need);
                ui.text(ui.clip(st.getHoverName().getString(), w - 40 - ui.width(n)), x + 28, ry + 4, TEXT);
                ui.right(n, x + w - 10, ry + 4, has >= need ? GREEN : ORANGE);
                ry += 18;
            }
            ui.endScroll();
        } else if (!road && by - yy > 50) {
            // a standing building: its model, turning, in the room above the buttons
            Blueprint bp = ClientState.blueprint(p.tag.getString("bp"));
            int ph = Math.min(120, by - yy - 8);
            ui.round(x + 6, yy + 2, w - 12, ph, 4, 0xFF101317, 0xFF1A1F27);
            if (bp != null && bp.size() > 1) {
                ui.pushClip(x + 6, yy + 2, x + w - 6, yy + 2 + ph);
                Preview.render(ui.g, bp, x + 6, yy + 2, w - 12, ph, (System.currentTimeMillis() % 36000) / 100f);
                ui.popClip();
            } else ui.center(bp == null ? "Loading…" : "Nothing to show", x + w / 2, yy + 2 + ph / 2 - 4, FAINT);
        }
        final int id = p.id;
        if (mine()) {
            if (built && t != null) {
                if (upgradable) {
                    boolean canUp = p.level < maxLevel;
                    ui.button("up" + id, bx, by, bw, 16, canUp ? "Upgrade to level " + (p.level + 1) : p.level >= t.maxLevel ? "Fully upgraded" : "Upgrade (needs higher rank)",
                            Style.PRIMARY, canUp, () -> act("upgrade", tag("plot", id)));
                    if (canUp && !t.procedural() && ui.over(bx, by, bw, 16)) ui.tip("Renovation: " + 24 * p.level + " planks, " + 24 * p.level + " cobblestone, " + 2 * p.level + " emeralds");
                    by += 19;
                }
                ui.button("rep" + id, bx, by, bw / 2 - 2, 16, "Repair", Style.SECONDARY, true, () -> act("repair", tag("plot", id)));
                ui.button("dem" + id, bx + bw / 2 + 2, by, bw / 2 - 2, 16, "Demolish", Style.DANGER, true, () -> act("demolish", tag("plot", id)));
                by += 19;
            } else if (!p.state.equals("DEMOLISHING")) {
                ui.button("pu" + id, bx, by, bw / 2 - 2, 16, "▲ Sooner", Style.SECONDARY, true, () -> act("priority", tag("plot", id, "delta", 1)));
                if (ui.over(bx, by, bw / 2 - 2, 16)) ui.tip("Move up the queue", "Builders take the highest priority first.");
                ui.button("pd" + id, bx + bw / 2 + 2, by, bw / 2 - 2, 16, "▼ Later", Style.SECONDARY, true, () -> act("priority", tag("plot", id, "delta", -1)));
                ui.button("can" + id, bx, by + 19, bw, 16, "Cancel", Style.DANGER, true, () -> { act("demolish", tag("plot", id)); selPlot = -1; });
                by += 38;
            }
        }
        // find it on the map (or centre it there), and back to the list or legend
        boolean onMap = tab == Tab.MAP;
        ui.button("find" + id, bx, by, bw / 2 - 2, 16, onMap ? "Centre" : "On map", Style.SECONDARY, true, () -> {
            mapCenterOn = id;
            mapZoom = Math.max(mapZoom, 2);
            if (!onMap) switchTab(Tab.MAP);
        });
        if (ui.over(bx, by, bw / 2 - 2, 16)) ui.tip(onMap ? "Centre the map on it" : "Show it on the City Map");
        ui.button("close" + id, bx + bw / 2 + 2, by, bw / 2 - 2, 16, "Back", Style.GHOST, true, () -> selPlot = -1);
    }

    // ------------------------------------------------------------------ build catalogue

    void build() {
        header("Build", mine() ? "Choose a building, then plan on the City Map or in the world." : "This town's buildings.", cw - 124);
        int top = cy + 30;
        // left: categories, shrinking to an icon column in narrow windows
        boolean icons = cw < 340;
        int lw = icons ? 24 : 108;
        search.setX(cx + cw - 118);
        search.setY(cy + 4);
        search.setWidth(112);
        ui.round(cx + cw - 122, cy, 120, 18, 5, 0xFF0F1216);
        ui.card(cx, top, lw, ch - 30);
        List<String> cats = new ArrayList<>();
        cats.add("ALL");
        for (Category c : Category.values()) cats.add(c.name());
        // how many buildings each category offers under the style filter and search, and how many of those are locked
        String q = search.getValue().toLowerCase(Locale.ROOT);
        CompoundTag locks = v().getCompound("locks");
        Map<String, int[]> catCount = new HashMap<>();
        for (BuildingType t : BuildingCatalog.types().values()) {
            if (!styleFilter.equals("ALL") && !t.style.equals(styleFilter)) continue;
            if (!q.isEmpty() && !(t.name.toLowerCase(Locale.ROOT).contains(q) || t.styleName.toLowerCase(Locale.ROOT).contains(q) || t.category.label.toLowerCase(Locale.ROOT).contains(q))) continue;
            boolean locked = locks.contains(t.id);
            for (String key : new String[]{"ALL", t.category.name()}) {
                int[] n = catCount.computeIfAbsent(key, k -> new int[2]);
                n[0]++;
                if (locked) n[1]++;
            }
        }
        int off = ui.beginScroll("cats", cx + 2, top + 4, lw - 4, ch - 38 - (mine() ? 22 : 0), cats.size() * 17);
        int yy = top + 4 - off;
        for (String c : cats) {
            final String cc = c;
            Item ic = c.equals("ALL") ? Items.COMPASS : Category.valueOf(c).icon;
            String label = c.equals("ALL") ? "All" : Category.valueOf(c).label;
            boolean sel = catFilter.equals(c);
            boolean on = ui.over(cx + 2, yy, lw - 4, 16);
            int[] n = catCount.getOrDefault(c, new int[2]);
            if (sel) ui.round(cx + 3, yy, lw - 6, 16, 4, 0xFF2E3542);
            else if (on) ui.round(cx + 3, yy, lw - 6, 16, 4, 0x18FFFFFF);
            ui.itemScaled(new ItemStack(ic), cx + 6, yy + 2, 0.75f);
            String count = c.equals("ROAD") ? "" : String.valueOf(n[0]);
            if (!icons) {
                ui.text(ui.clip(label, lw - 30 - ui.width(count)), cx + 22, yy + 4, sel ? GOLD : n[0] == 0 && !c.equals("ROAD") ? FAINT : TEXT);
                // a category with nothing to build yet shows its count in the lock colour
                ui.right(count, cx + lw - 8, yy + 4, n[0] > 0 && n[1] == n[0] ? 0xFFB06060 : FAINT);
            }
            if (on) ui.tip(label + (count.isEmpty() ? "" : ": " + n[0] + (n[0] == 1 ? " building" : " buildings")),
                    n[1] == 0 ? "" : n[1] == n[0] ? "All locked for now" : n[1] + " locked for now");
            ui.area(cx + 2, yy, lw - 4, 16, () -> { catFilter = cc; ui.resetScroll("blist"); });
            yy += 17;
        }
        ui.endScroll();
        if (mine()) {
            Runnable enter = () -> livingrealms.client.ArchitectView.enter(v().getUUID("id"), true);
            if (icons) {
                boolean on = ui.over(cx + 2, top + ch - 52, lw - 4, 18);
                ui.round(cx + 2, top + ch - 52, lw - 4, 18, 4, on ? 0xFFF2CF6E : 0xFFD4A63E);
                ui.itemScaled(new ItemStack(Items.SPYGLASS), cx + 6, top + ch - 49, 0.75f);
                ui.area(cx + 2, top + ch - 52, lw - 4, 18, enter);
            } else ui.button("architect", cx + 4, top + ch - 30 - 22, lw - 8, 17, "Architect view", Style.PRIMARY, true, enter);
            String archKey = livingrealms.client.ClientSetup.ARCHITECT.isUnbound() ? "" : " (" + livingrealms.client.ClientSetup.ARCHITECT.getTranslatedKeyMessage().getString() + ")";
            if (ui.over(cx + 2, top + ch - 52, lw - 4, 18)) ui.tip("Architect view" + archKey, "Fly a free camera over the town and plan buildings where they will stand.", "Your body stays here and can be hurt.");
        }
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
        int mx = cx + lw + 6, mw = Math.min(150, (cw - lw - 12) / 2);
        String styleLabel = styleFilter.equals("ALL") ? "All styles" : styleFilter.equals("any") ? "Living Realms" : prettyStyle(styleFilter);
        ui.button("style", mx, top, mw, 15, ui.clip("Style: " + styleLabel, mw - 8), Style.SECONDARY, true, () -> {
            int i = styleList.indexOf(styleFilter);
            styleFilter = styleList.get((i + (Screen.hasShiftDown() ? styleList.size() - 1 : 1)) % styleList.size());
            ui.resetScroll("blist");
        });
        if (ui.over(mx, top, mw, 15)) ui.tip("Style: " + styleLabel, "Click to cycle (shift-click goes back)", styleList.size() - 1 + " styles from your mods");
        List<BuildingType> types = new ArrayList<>();
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
            int have = v().getCompound("counts").getInt(id);
            String sub = locked ? locks.getString(id) : t.w + "×" + t.d + (t.procedural() ? t.maxLevel > 1 ? " · " + t.maxLevel + " levels" : "" : " · " + t.styleName);
            // how many the town already has, on the name line
            String owned = have > 0 ? "×" + have : "";
            if (!owned.isEmpty()) ui.right(owned, mx + mw - 8, ry + 3, MUTED);
            ui.text(ui.clip(t.name, mw - 22 - (owned.isEmpty() ? 0 : ui.width(owned) + 4)), mx + 13, ry + 3, locked ? MUTED : TEXT);
            ui.text(ui.clip(sub, mw - 22), mx + 13, ry + 13, locked ? 0xFFB06060 : FAINT);
            if (on) ui.tip(t.name, locked ? "Locked: " + sub : sub, have > 0 ? have + " in town" : "None in town yet");
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
        String facts = t.w + "×" + t.d + "×" + t.h + " · " + t.blocks + " blocks" + (t.beds > 0 ? " · " + t.beds + (t.beds == 1 ? " bed" : " beds") : "")
                + (t.maxLevel > 1 ? " · " + t.maxLevel + " levels" : "");
        yy2 += ui.wrap(facts, dx + 8, yy2, dw - 16, MUTED);
        String perks = perks(t);
        if (!perks.isEmpty()) { yy2 += ui.wrap(perks, dx + 8, yy2, dw - 16, FAINT); }
        String lock = locks.getString(t.id);
        if (!lock.isEmpty()) { yy2 += ui.wrap("Locked: " + lock, dx + 8, yy2 + 1, dw - 16, RED) + 2; }
        // materials, with a one-line verdict on what the stockpile covers
        if (bp != null) {
            Map<Item, Integer> stock0 = stock();
            int shortKinds = 0;
            for (var e : bp.bill().entrySet()) if (stock0.getOrDefault(e.getKey(), 0) < e.getValue()) shortKinds++;
            String verdict = shortKinds == 0 ? "Materials: all in stock" : "Materials: " + shortKinds + " short";
            ui.text(ui.clip(verdict, dw - 16), dx + 8, yy2 + 2, shortKinds == 0 ? GREEN : ORANGE);
            if (ui.over(dx + 8, yy2 + 1, dw - 16, 10)) ui.tip("Level 1 bill. Green: enough in the stockpile; orange: short.", "Builders wait at the site for anything missing.");
            yy2 += 12;
        }
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
            ui.button("mapPlace", dx + 6, top + ch - 30 - 22, (dw - 18) / 2, 17, "City Map", Style.PRIMARY, ok, () -> {
                beginMapPlan(t.id);
            });
            ui.button("place", dx + 12 + (dw - 18) / 2, top + ch - 30 - 22, (dw - 18) / 2, 17, "In world", Style.SECONDARY, ok, () -> {
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

    /** Projects: which groups are open (wall lines in the queue, categories of the standing list). */
    private final Set<String> projOpen = new HashSet<>(), projClosed = new HashSet<>();

    /** One line of the Projects list: a section title, a foldable group, or a plot. */
    record ProjRow(String title, String key, int count, List<PlotView> members, PlotView plot, int position, boolean indent, boolean open, boolean byDefault) {}

    boolean projGroupOpen(String key, boolean byDefault) { return byDefault ? !projClosed.contains(key) : projOpen.contains(key); }

    void toggleProjGroup(String key, boolean byDefault) {
        if (byDefault) { if (!projClosed.remove(key)) projClosed.add(key); }
        else if (!projOpen.remove(key)) projOpen.add(key);
    }

    void projects() {
        List<PlotView> all = plots();
        List<PlotView> active = new ArrayList<>(), built = new ArrayList<>();
        for (PlotView p : all) {
            if (p.state.equals("BUILT")) { if (!p.type.equals("road")) built.add(p); }
            else active.add(p);
        }
        // the order builders take them in: highest priority first, then oldest
        active.sort(Comparator.comparingInt((PlotView p) -> -p.tag.getInt("prio")).thenComparingInt(p -> p.id));
        int waiting = 0;
        for (PlotView p : active) if (!p.status.isEmpty()) waiting++;
        header("Projects", active.isEmpty() ? built.size() + " standing · nothing under way"
                : active.size() + " under way" + (waiting > 0 ? " · " + waiting + " waiting" : "") + " · " + built.size() + " standing");
        // rows: the queue with each wall line folded into one group, then the standing buildings by category
        List<ProjRow> rows = new ArrayList<>();
        rows.add(new ProjRow("Under way", null, active.size(), null, null, 0, false, false, false));
        Map<String, List<PlotView>> wallQueue = new LinkedHashMap<>();
        for (PlotView p : active) if (isWall(p)) wallQueue.computeIfAbsent(wallLine(p), k -> new ArrayList<>()).add(p);
        int pos = 0;
        Set<String> placed = new HashSet<>();
        for (PlotView p : active) {
            pos++;
            if (!isWall(p)) { rows.add(new ProjRow(null, null, 0, null, p, pos, false, false, false)); continue; }
            String line = wallLine(p);
            if (!placed.add(line)) continue;
            List<PlotView> members = wallQueue.get(line);
            // a lone piece needs no group
            if (members.size() == 1) { rows.add(new ProjRow(null, null, 0, null, p, pos, false, false, false)); continue; }
            String key = "q:" + line;
            boolean open = projGroupOpen(key, false);
            rows.add(new ProjRow(null, key, members.size(), members, null, pos, false, open, false));
            if (open) for (PlotView m : members) rows.add(new ProjRow(null, null, 0, null, m, 0, true, false, false));
        }
        rows.add(new ProjRow("Standing", null, built.size(), null, null, 0, false, false, false));
        Map<Category, List<PlotView>> byCat = new EnumMap<>(Category.class);
        for (PlotView p : built) byCat.computeIfAbsent(plotCategory(p), k -> new ArrayList<>()).add(p);
        // a small town lists everything; a big one folds each category until it is opened
        boolean foldAll = built.size() > 24;
        for (var e : byCat.entrySet()) {
            String key = "c:" + e.getKey().name();
            List<PlotView> members = e.getValue();
            members.sort(Comparator.comparingInt((PlotView p) -> -p.level).thenComparing(CityScreen::plotName));
            boolean byDefault = !foldAll || e.getKey() != Category.WALL && members.size() <= 3, open = projGroupOpen(key, byDefault);
            rows.add(new ProjRow(null, key, members.size(), members, null, 0, false, open, byDefault));
            if (open) for (PlotView m : members) rows.add(new ProjRow(null, null, 0, null, m, 0, true, false, false));
        }
        int side = Math.min(150, cw * 2 / 5), lw = cw - side - 6, top = cy + 30;
        ui.card(cx, top, lw, ch - 30);
        int height = 8;
        for (ProjRow r : rows) height += r.title != null ? 16 : r.plot != null ? 26 : 20;
        int off = ui.beginScroll("proj", cx + 2, top + 2, lw - 4, ch - 34, height);
        int y = top + 6 - off;
        for (ProjRow r : rows) {
            if (r.title != null) {
                if (y > top) y += 2;
                ui.text(r.title, cx + 8, y + 1, GOLD);
                ui.text(String.valueOf(r.count), cx + 12 + ui.width(r.title), y + 1, FAINT);
                ui.hline(cx + 18 + ui.width(r.title) + ui.width(String.valueOf(r.count)), y + 5, lw - 30 - ui.width(r.title) - ui.width(String.valueOf(r.count)), LINE);
                y += 14;
                if (r.title.equals("Under way") && r.count == 0) {
                    ui.text(ui.clip("Nothing being built. Plan something on the Build page.", lw - 20), cx + 10, y, FAINT);
                    y += 12;
                }
                continue;
            }
            if (r.plot != null) { y = projectRow(r.plot, y, lw, r.position, r.indent); continue; }
            y = groupRow(r, y, lw);
        }
        ui.endScroll();
        int sx = cx + lw + 6;
        PlotView sel = null;
        for (PlotView p : all) if (p.id == selPlot) sel = p;
        ui.card(sx, top, cw - lw - 6, ch - 30);
        if (sel != null) plotDetail(sel, sx, top, cw - lw - 6, ch - 30);
        else queueSummary(active, sx, top, cw - lw - 6, ch - 30);
    }

    /** A foldable group: a wall line in the queue (with its overall progress) or a category of standing buildings. */
    int groupRow(ProjRow r, int y, int w) {
        boolean queue = r.key.startsWith("q:"), open = r.open;
        boolean on = ui.over(cx + 3, y, w - 6, 18);
        ui.round(cx + 3, y, w - 6, 18, 4, on ? 0xFF2A303B : 0xFF222831);
        ui.text(open ? "▾" : "▸", cx + 9, y + 5, MUTED);
        Category cat = queue ? Category.WALL : Category.valueOf(r.key.substring(2));
        ui.itemScaled(new ItemStack(cat.icon), cx + 18, y + 3, 0.75f);
        int right = cx + w - 10;
        if (queue) {
            float prog = 0;
            int waiting = 0;
            for (PlotView m : r.members) { prog += m.progress; if (!m.status.isEmpty()) waiting++; }
            prog /= r.members.size();
            int barW = Math.max(30, Math.min(80, (w - 40) / 4));
            ui.bar(right - barW, y + 7, barW, 4, prog, GOLD, GOLD_DK);
            String pct = (int) (prog * 100) + "%";
            ui.right(pct, right - barW - 4, y + 5, MUTED);
            // the places in the queue the pieces take, the line's name, then how many (when it fits)
            String label = (r.position > 0 ? r.position + "–" + (r.position + r.count - 1) + ". " : "") + r.key.substring(2);
            String more = " · " + r.count + " pieces" + (waiting > 0 ? ", " + waiting + " waiting" : "");
            int room = right - barW - 10 - ui.width(pct) - (cx + 32);
            ui.text(ui.clip(label, room), cx + 32, y + 5, TEXT);
            if (ui.width(label + more) <= room) ui.text(more, cx + 32 + ui.width(label), y + 5, FAINT);
            if (on) ui.tip(r.key.substring(2) + ": " + r.count + " gatehouses, towers and wall sections queued", "Villagers build them in turn from the stockpile, gatehouses first.",
                    open ? "Click to fold" : "Click to list every piece");
        } else {
            ui.text(ui.clip(cat.label, w - 80), cx + 32, y + 5, TEXT);
            ui.text(String.valueOf(r.count), cx + 36 + ui.width(ui.clip(cat.label, w - 80)), y + 5, FAINT);
            int workers = 0;
            for (PlotView m : r.members) workers += v().getCompound("plotWorkers").getInt(String.valueOf(m.id));
            if (cat.job != null) ui.right(workers + " " + cat.job.label.toLowerCase(Locale.ROOT) + (workers == 1 ? "" : "s") + " at work", right, y + 5, MUTED);
            if (on) ui.tip(cat.label + ": " + r.count + (r.count == 1 ? " building" : " buildings"), open ? "Click to fold" : "Click to list them");
        }
        ui.area(cx + 3, y, w - 6, 18, () -> toggleProjGroup(r.key, r.byDefault));
        return y + 20;
    }

    int projectRow(PlotView p, int y, int w, int position, boolean indent) {
        final int id = p.id;
        int x = cx + (indent ? 10 : 0);
        w -= indent ? 10 : 0;
        boolean sel = id == selPlot, on = ui.over(x + 3, y, w - 6, 24);
        if (sel) ui.round(x + 3, y, w - 6, 24, 4, 0xFF2E3542);
        else if (on) ui.round(x + 3, y, w - 6, 24, 4, 0x18FFFFFF);
        BuildingType t = BuildingCatalog.get(p.type);
        Category cat = plotCategory(p);
        ui.item(cat.icon, x + 7, y + 4);
        boolean built = p.state.equals("BUILT"), auto = p.tag.getBoolean("automatic");
        int prio = p.tag.getInt("prio");
        int barW = Math.max(30, Math.min(80, (w - 40) / 4)), right = x + w - 10;
        // the right-hand side: progress for the queue, workers or level for standing buildings
        int workers = v().getCompound("plotWorkers").getInt(String.valueOf(id));
        String side = "";
        if (!built) {
            ui.bar(right - barW, y + 9, barW, 5, p.progress, p.state.equals("DEMOLISHING") ? RED : GOLD, p.state.equals("DEMOLISHING") ? RED : GOLD_DK);
            String pct = (int) (p.progress * 100) + "%";
            ui.right(pct, right - barW - 4, y + 8, MUTED);
            side = pct;
            // who asked for it, and a bumped priority
            String tagText = !auto && prio > 0 ? "▲" + prio : auto && !(indent && isWall(p)) ? "auto" : "";
            if (!tagText.isEmpty()) {
                int px0 = right - barW - 10 - ui.width(pct) - ui.pillWidth(tagText);
                ui.pill(tagText, px0, y + 6, auto ? 0xFF2A3038 : 0xFF5A4A1E, auto ? FAINT : GOLD);
                side = tagText + "  " + pct;
            }
        } else if (t != null && t.category.job != null) {
            side = workers + " " + t.category.job.label.toLowerCase(Locale.ROOT) + (workers == 1 ? "" : "s");
            ui.right(side, right, y + 8, MUTED);
        }
        int room = Math.max(40, (built ? right - ui.width(side) - 8 : right - barW - 14 - ui.width(side)) - (x + 27));
        String name = (position > 0 ? position + ". " : "") + plotName(p) + (p.level > 1 ? " · L" + p.level : "");
        String state = p.status.isEmpty() ? (built ? quarter(p) : stateLabel(p)) : p.status;
        ui.text(ui.clip(name, room), x + 27, y + 3, TEXT);
        ui.text(ui.clip(state, room), x + 27, y + 13, p.status.isEmpty() ? FAINT : ORANGE);
        if (on) {
            List<String> tip = new ArrayList<>(List.of(plotName(p) + (p.level > 1 ? " · level " + p.level : ""),
                    (isWall(p) ? wallLine(p) : cat.label) + " · " + stateLabel(p)));
            if (!built) tip.add((auto ? "Chosen by the villagers" : "Your plan") + (prio != 0 && !auto ? " · priority " + (prio > 0 ? "+" : "") + prio : ""));
            if (!p.status.isEmpty()) tip.add(p.status);
            ui.tip(tip);
        }
        ui.area(x + 3, y, w - 6, 24, () -> selPlot = id);
        return y + 26;
    }

    /** With nothing selected: what the builders are up to and what the queue still needs from the stockpile. */
    void queueSummary(List<PlotView> active, int x, int y, int w, int h) {
        ui.text("Construction", x + 8, y + 8, MUTED);
        int yy = y + 22;
        int[] builders = v().getCompound("jobs").getIntArray("BUILDER");
        ui.text(ui.clip("Builders: " + (builders.length > 1 ? builders[0] + " of " + builders[1] + " places" : "none"), w - 16), x + 8, yy, TEXT);
        yy += 11;
        int player = 0;
        for (PlotView p : active) if (!p.tag.getBoolean("automatic")) player++;
        String mix = player == 0 ? "All chosen by the villagers" : player == active.size() ? "All your own plans" : player + " yours, " + (active.size() - player) + " the villagers'";
        yy += ui.wrap(active.isEmpty() ? "The queue is empty" : mix, x + 8, yy, w - 16, MUTED) + 4;
        // a rough bill of what is still to come: each plot's bill less the share already built
        Map<String, Integer> owed = new HashMap<>();
        CompoundTag bills = v().getCompound("bills");
        for (PlotView p : active) {
            if (p.state.equals("DEMOLISHING")) continue;
            CompoundTag b = bills.getCompound(String.valueOf(p.id));
            for (String k : b.getAllKeys()) owed.merge(k, (int) Math.ceil(b.getInt(k) * (1 - p.progress)), Integer::sum);
        }
        Map<Item, Integer> stock = stock();
        List<Map.Entry<String, Integer>> short_ = new ArrayList<>();
        for (var e : owed.entrySet()) {
            int has = stock.getOrDefault(BuiltInRegistries.ITEM.get(new ResourceLocation(e.getKey())), 0);
            if (has < e.getValue()) short_.add(Map.entry(e.getKey(), e.getValue() - has));
        }
        short_.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> -e.getValue()));
        if (!active.isEmpty()) {
            ui.text(short_.isEmpty() ? "Stockpile covers the queue" : "Still to bring in", x + 8, yy, short_.isEmpty() ? GREEN : ORANGE);
            if (ui.over(x, yy - 1, w, 11)) ui.tip("An estimate: what the queued projects still need beyond what the stockpile holds.");
            yy += 12;
            int max = Math.max(0, (y + h - 70 - yy) / 18);
            for (int i = 0; i < Math.min(max, short_.size()); i++) {
                var e = short_.get(i);
                ItemStack st = new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(e.getKey())));
                ui.itemTip(st, x + 8, yy);
                String n = num(e.getValue());
                ui.text(ui.clip(st.getHoverName().getString(), w - 40 - ui.width(n)), x + 28, yy + 4, TEXT);
                ui.right(n, x + w - 10, yy + 4, ORANGE);
                yy += 18;
            }
            if (short_.size() > max && max > 0) { ui.text("and " + (short_.size() - max) + " more", x + 28, yy, FAINT); yy += 12; }
            yy += 4;
        }
        ui.pushClip(x, yy, x + w, y + h - 4);
        ui.wrap("Click a project for details. Builders take materials straight from the stockpile; a site that lacks something waits and says what.",
                x + 8, Math.max(yy, y + h - 4 - ui.wrapHeight("Click a project for details. Builders take materials straight from the stockpile; a site that lacks something waits and says what.", w - 16)), w - 16, FAINT);
        ui.popClip();
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
        boolean allKnown = strings("research").containsAll(ResearchTree.nodes().keySet());
        if (active.isEmpty() && allKnown) {
            ui.text("Every project is known", cx + 10, top + 8, GOLD);
            ui.text(ui.clip("All " + ResearchTree.nodes().size() + " projects of the tree have been studied.", cw - 20), cx + 10, top + 19, FAINT);
        } else if (active.isEmpty()) {
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
        // above the tree's item icons, which draw at a raised depth
        ui.g.pose().pushPose();
        ui.g.pose().translate(0, 0, 300);
        int hintW = ui.width("Drag to look around") + 8;
        ui.round(resX + resW - hintW - 4, resY + resH - 14, hintW, 12, 3, 0xE0111418);
        ui.text("Drag to look around", resX + resW - hintW, resY + resH - 12, FAINT);
        // detail of the selected project
        var sel = selResearch == null ? null : ResearchTree.get(selResearch);
        if (sel != null) {
            int dw = Math.min(200, resW - 12), dx = resX + resW - dw - 6, dy = resY + 6;
            Map<Item, Integer> stock0 = stock();
            StringBuilder needs = new StringBuilder();
            if (!sel.items().isEmpty()) {
                needs.append("Needs: ");
                sel.items().forEach((k, n) -> needs.append(n).append(" ").append(ingName(k)).append(have(stock0, k) >= n ? " ✔ " : " ✖ "));
            }
            int dh = Math.min(resH - 12, Math.max(92, 52 + ui.wrapHeight(sel.description(), dw - 16) + (needs.length() == 0 ? 0 : ui.wrapHeight(needs.toString(), dw - 16) + 1)));
            ui.card(dx, dy, dw, dh, 0xF0202630, 0xFF3A4250);
            ui.item(sel.icon(), dx + 8, dy + 7);
            ui.text(ui.clip(sel.name(), dw - 36), dx + 28, dy + 6, TEXT);
            ui.text(ui.clip(sel.branch() + " · " + sel.cost() + " points", dw - 36), dx + 28, dy + 16, MUTED);
            int ty = dy + 28;
            ui.pushClip(dx, ty, dx + dw, dy + dh - 22);
            ty += ui.wrap(sel.description(), dx + 8, ty, dw - 16, FAINT);
            if (needs.length() > 0) ty += ui.wrap(needs.toString(), dx + 8, ty + 1, dw - 16, ORANGE);
            ui.popClip();
            boolean isDone = done.contains(sel.id());
            boolean open = sel.requires().stream().allMatch(done::contains);
            if (mine() && !isDone && !sel.id().equals(active)) {
                String label = open ? "Study this" : "Needs " + sel.requires().stream().filter(r -> !done.contains(r)).map(CityScreen::researchName).findFirst().orElse("");
                ui.button("sres", dx + 6, dy + dh - 20, dw - 12, 15, ui.clip(label, dw - 20), Style.PRIMARY, open, () -> act("research", tag("id", sel.id())));
            }
            ui.area(dx, dy, dw, dh, () -> {}, false);
        }
        ui.g.pose().popPose();
    }

    static String eta(double days) {
        long mins = Math.round(days * 20);
        return mins < 60 ? "~" + Math.max(1, mins) + " min" : String.format(Locale.ROOT, "~%.1f h", mins / 60.0);
    }

    // ------------------------------------------------------------------ military

    static boolean realms() { return v().getCompound("vx").contains("factions"); }

    static String[] milLabels() {
        return realms() ? new String[]{"Garrison", "Recruit", "Smithy", "Kits", "Honours", "Realms"} : new String[]{"Garrison", "Recruit", "Blacksmith", "Kits", "Honours"};
    }

    void milTabs() { milTabs(cx + cw - milTabsWidth(), cy + 2, milTabsWidth()); }

    void milTabs(int x, int y, int w) {
        ui.segments("mil", x, y, w, 16, milLabels(), milTab, i -> {
            milTab = i;
            tab = i == 2 ? Tab.FORGE : Tab.MILITARY;
        });
    }

    /**
     * The Military and Blacksmith title row: the sub-tabs beside the title, or, when they would not fit there, across the
     * full width with the title and figures on one small line beneath.
     */
    void milHeader(String title, String sub) {
        String[] labels = milLabels();
        int widest = 0;
        for (String l : labels) widest = Math.max(widest, ui.width(l));
        if (milTabsWidth() / labels.length >= widest + 8) { header(title, sub, cw - milTabsWidth() - 2); milTabs(); return; }
        milTabs(cx, cy, cw);
        ui.text(title, cx + 2, cy + 19, TEXT);
        ui.text(ui.clip(sub, cw - ui.width(title) - 14), cx + ui.width(title) + 10, cy + 19, MUTED);
    }

    /** Room for the Military/Blacksmith tabs, leaving the page title readable. */
    int milTabsWidth() { return Math.min(realms() ? 330 : 290, cw - (int) (ui.width("Blacksmith") * 1.4f) - 10); }

    void military() {
        if (milTab == 2 || milTab == 5 && !realms()) milTab = 0;
        ListTag soldiers = list("soldiers");
        milHeader("Military", soldiers.size() + " / " + v().getInt("garrisonCap") + " soldiers" + (list("training").isEmpty() ? "" : " · " + list("training").size() + " in training"));
        switch (milTab) {
            case 1 -> recruit();
            case 3 -> kits();
            case 4 -> HonoursPanel.draw(ui, cx, cy + 30, cw, ch - 30, v(), mine(), this::act);
            case 5 -> RealmsPanel.draw(ui, cx, cy + 30, cw, ch - 30, v().getCompound("vx"), this::act);
            default -> garrison();
        }
    }

    void garrison() {
        if (selectedSoldier != null) { soldierDetails(); return; }
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
            ui.center(ui.clip(s.label, sw - 4), x + sw / 2, top + 6, sel ? GOLD : TEXT);
            if (sel) ui.center(ui.width("current orders") < sw - 6 ? "current orders" : "active", x + sw / 2, top + 17, FAINT);
            if (on) ui.tip(s.label + (sel ? " (current orders)" : ""), s.description);
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
        if (ui.over(ax, top, 74, 30)) ui.tip("The alarm rallies town soldiers. Royal Guards keep protecting their owner.", "It also rings by itself when raiders enter the town.");
        // roster
        int ly = top + 36, lh = ch - 66;
        ui.card(cx, ly, cw, lh);
        ListTag all = list("soldiers");
        List<CompoundTag> soldiers = rosterView(all);
        // sort and filter bar
        int segW = Math.min(170, (cw - 22) / 2);
        ui.segments("rosterSort", cx + 6, ly + 5, segW, 13, ROSTER_SORTS, rosterSort, i -> { rosterSort = i; ui.resetScroll("roster"); });
        if (ui.over(cx + 6, ly + 5, segW, 13)) ui.tip("Sort by " + ROSTER_SORTS[rosterSort].toLowerCase(Locale.ROOT), "Rank: skill rank, then Recruits level · Health: most hurt first · Unit: by kind");
        String[] filters = new String[ROSTER_FILTERS.length];
        // counts beside the filters when they fit
        int widest = 0;
        for (int i = 0; i < filters.length; i++) widest = Math.max(widest, ui.width(ROSTER_FILTERS[i] + " " + rosterCount(all, i)));
        boolean counts = segW / filters.length >= widest + 6;
        for (int i = 0; i < filters.length; i++) filters[i] = ROSTER_FILTERS[i] + (counts ? " " + rosterCount(all, i) : "");
        ui.segments("rosterFilter", cx + cw - 6 - segW, ly + 5, segW, 13, filters, rosterFilter, i -> { rosterFilter = i; ui.resetScroll("roster"); });
        if (ui.over(cx + cw - 6 - segW, ly + 5, segW, 13)) ui.tip("Show: all soldiers, those in town, those away, or those under 60% health",
                "All " + rosterCount(all, 0) + " · home " + rosterCount(all, 1) + " · away " + rosterCount(all, 2) + " · hurt " + rosterCount(all, 3));
        int listTop = ly + 21;
        ui.hline(cx + 6, listTop - 1, cw - 12, LINE);
        int off = ui.beginScroll("roster", cx + 2, listTop, cw - 4, lh - 21 - 24, soldiers.size() * 24 + 4);
        int y = listTop + 2 - off;
        for (CompoundTag s : soldiers) {
            UnitType u = UnitType.valueOf(s.getString("type"));
            boolean seen = s.getBoolean("seen");
            final UUID id = s.getUUID("e");
            boolean row = ui.over(cx + 2, y, cw - 4, 24);
            if (row) ui.round(cx + 4, y, cw - 8, 23, 4, 0x14FFFFFF);
            ui.area(cx + 2, y, cw - 4, 24, () -> selectedSoldier = id);
            ui.item(u.icon, cx + 8, y + 3);
            float hp0 = s.getFloat("max") <= 0 ? 0 : s.getFloat("hp") / s.getFloat("max");
            ui.bar(cx + 8, y + 20, 16, 2, hp0, hp0 > 0.5 ? GREEN : hp0 > 0.25 ? GOLD : RED, hp0 > 0.5 ? GREEN : hp0 > 0.25 ? GOLD : RED);
            ui.text(ui.clip((s.getBoolean("royalGuard") ? "♛ " : "") + s.getString("name"), 116), cx + 28, y + 2, s.getBoolean("royalGuard") ? GOLD : seen ? TEXT : MUTED);
            int lvl = s.getInt("lvl");
            String stars = "★".repeat(Math.max(0, Math.min(5, lvl))) + (lvl > 5 ? "+" : "");
            ui.text(stars, cx + 28, y + 12, GOLD);
            ui.text(ui.clip("Rank " + (s.contains("rank") ? s.getInt("rank") : SoldierSkills.earnedPoints(s.getInt("skillXp"))), 116 - ui.width(stars) - 4), cx + 32 + ui.width(stars), y + 12, FAINT);
            int bx = cx + 152, rx = cx + cw - 60;
            float hp = s.getFloat("max") <= 0 ? 0 : s.getFloat("hp") / s.getFloat("max");
            if (rx - 56 >= bx + 70) {
                ui.text("HP", bx, y + 2, FAINT);
                ui.bar(bx + 16, y + 3, 50, 5, hp, hp > 0.5 ? GREEN : hp > 0.25 ? GOLD : RED, hp > 0.5 ? 0xFF4FA860 : hp > 0.25 ? GOLD_DK : 0xFFB84A4A);
                ui.text("Morale " + (int) s.getFloat("morale"), bx, y + 12, FAINT);
            }
            ui.button("command" + id, rx, y + 4, 50, 14, "Command", Style.SECONDARY, seen, () -> recruitOp(id, "open"));
            rx -= 56;
            ui.button("inventory" + id, rx, y + 4, 54, 14, "Inventory", Style.GHOST, seen && mine(), () -> recruitOp(id, "inventory"));
            if (rx - 42 >= bx + 68) ui.button("skills" + id, rx - 42, y + 4, 40, 14, "Skills", Style.GHOST, true, () -> selectedSoldier = id);
            if (row && !ui.over(rx - 42, y + 4, cx + cw - rx + 34, 14))
                ui.tip(s.getString("name"), seen ? s.getString("gear") : "Away from town", "HP " + Math.round(hp * 100) + "% · morale " + (int) s.getFloat("morale"),
                        s.getInt("kills") + " kills · Recruits level " + lvl, "Click for skills, weapons and legendary perks");
            y += 24;
        }
        if (all.isEmpty()) ui.text(ui.clip("No soldiers yet. Build a Barracks and train some on the Recruit page.", cw - 20), cx + 10, listTop + 6, FAINT);
        else if (soldiers.isEmpty()) ui.text("No soldiers match this filter.", cx + 10, listTop + 6, FAINT);
        ui.endScroll();
        if (mine()) {
            ui.button("equip", cx + 6, ly + lh - 21, 150, 16, "Equip from the armoury", Style.PRIMARY, !all.isEmpty(), () -> act("equipAll"));
            if (ui.over(cx + 6, ly + lh - 21, 150, 16)) ui.tip("Gives every soldier in town the best kit the armoury holds for them (see Kits). Old gear goes back into the stockpile.");
        }
        String hint = "Fed soldiers heal over time; hungry ones weaken.";
        if (ui.width(hint) < cw - 172) ui.right(hint, cx + cw - 8, ly + lh - 16, FAINT);
    }

    static final String[] ROSTER_SORTS = {"Rank", "Name", "Health", "Unit"}, ROSTER_FILTERS = {"All", "Home", "Away", "Hurt"};
    private int rosterSort, rosterFilter;

    static float hpFrac(CompoundTag s) { return s.getFloat("max") <= 0 ? 0 : s.getFloat("hp") / s.getFloat("max"); }

    static int skillRank(CompoundTag s) { return s.contains("rank") ? s.getInt("rank") : SoldierSkills.earnedPoints(s.getInt("skillXp")); }

    static boolean rosterShows(CompoundTag s, int filter) {
        return switch (filter) {
            case 1 -> s.getBoolean("seen");
            case 2 -> !s.getBoolean("seen");
            case 3 -> hpFrac(s) < 0.6f;
            default -> true;
        };
    }

    static int rosterCount(ListTag all, int filter) {
        int n = 0;
        for (Tag t : all) if (rosterShows((CompoundTag) t, filter)) n++;
        return n;
    }

    /** The garrison as the roster lists it: filtered, then sorted; Royal Guards lead within equal keys. */
    List<CompoundTag> rosterView(ListTag all) {
        List<CompoundTag> out = new ArrayList<>();
        for (Tag t : all) if (rosterShows((CompoundTag) t, rosterFilter)) out.add((CompoundTag) t);
        Comparator<CompoundTag> byName = Comparator.comparing(s -> s.getString("name").toLowerCase(Locale.ROOT));
        Comparator<CompoundTag> order = switch (rosterSort) {
            case 1 -> byName;
            case 2 -> Comparator.comparingDouble(CityScreen::hpFrac).thenComparing(byName);
            case 3 -> Comparator.comparingInt((CompoundTag s) -> { try { return UnitType.valueOf(s.getString("type")).ordinal(); } catch (Exception e) { return 99; } })
                    .thenComparing(Comparator.comparingInt(CityScreen::skillRank).reversed()).thenComparing(byName);
            default -> Comparator.comparing((CompoundTag s) -> !s.getBoolean("royalGuard")).thenComparing(Comparator.comparingInt(CityScreen::skillRank).reversed())
                    .thenComparing(Comparator.comparingInt((CompoundTag s) -> s.getInt("lvl")).reversed()).thenComparing(byName);
        };
        out.sort(order);
        return out;
    }

    void soldierDetails() {
        CompoundTag soldier = null;
        for (Tag value : list("soldiers")) {
            CompoundTag candidate = (CompoundTag) value;
            if (candidate.hasUUID("e") && candidate.getUUID("e").equals(selectedSoldier)) { soldier = candidate; break; }
        }
        if (soldier == null) { selectedSoldier = null; garrison(); return; }
        int top = cy + 30;
        final UUID id = selectedSoldier;
        ui.button("backRoster", cx, top, 76, 16, "← Garrison", Style.GHOST, true, () -> selectedSoldier = null);
        ui.text(ui.clip(soldier.getString("name"), cw - 230), cx + 86, top + 4, soldier.getBoolean("royalGuard") ? GOLD : TEXT);
        ui.button("detailInv", cx + cw - 128, top, 60, 16, "Inventory", Style.SECONDARY, soldier.getBoolean("seen") && mine(), () -> recruitOp(id, "inventory"));
        ui.button("detailCmd", cx + cw - 64, top, 60, 16, "Command", Style.PRIMARY, soldier.getBoolean("seen"), () -> recruitOp(id, "open"));
        String[] tabs = SoldierPanels.tabs(soldier);
        int sel = Math.max(0, Arrays.asList(tabs).indexOf(soldierTab));
        ui.segments("soldierTabs", cx, top + 22, cw - 4, 16, tabs, sel, i -> soldierTab = tabs[i]);
        panels.draw(soldier, sel, cx, cw, top + 45, ch - 75);
    }

    /** Asks the server about one recruit: open its Recruit Command screen, its inventory, and so on. */
    public static void recruitOp(UUID entity, String op) {
        CompoundTag t = new CompoundTag();
        t.putUUID("e", entity);
        t.putString("op", op);
        Net.toServer("recruit", t);
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
            String lock = null;
            if (u.research != null && !done.contains(u.research)) lock = "Research " + researchName(u.research);
            else if (v().getInt("barracks") == 0) lock = "Build a Barracks";
            else if ((u == UnitType.HORSEMAN || u == UnitType.DRAGON_RIDER) && !v().getBoolean("stables")) lock = "Build Stables";
            if (u == UnitType.DRAGON_RIDER && !livingrealms.military.DragonRiders.installed()) lock = "Install Ice and Fire";
            String costLine = u.emeralds + " emeralds · " + Arrays.stream(u.needs).map(p -> p == Piece.SWORD ? "a weapon" : "a " + p.label.toLowerCase(Locale.ROOT)).reduce((a, b) -> a + ", " + b).orElse("");
            // name and training time on top; what stops it, or what it costs, beneath
            String time = eta(u.trainDays) + " to train";
            boolean roomy = lw - 38 - ui.width(u.label) - ui.width(time) > 12;
            ui.text(ui.clip(u.label, lw - 40), cx + 30, y + 5, sel ? GOLD : TEXT);
            if (roomy) ui.right(time, cx + lw - 8, y + 5, FAINT);
            ui.text(ui.clip(lock != null ? "🔒 " + lock : costLine, lw - 38), cx + 30, y + 16, lock != null ? RED : stock.getOrDefault(Items.EMERALD, 0) >= u.emeralds ? MUTED : ORANGE);
            if (on) ui.tip(u.label, u.description, costLine + " · " + time, lock != null ? "Locked: " + lock : "Ready to train");
            final UnitType uu = u;
            ui.area(cx, y, lw, cardH, () -> selUnit = uu);
        }
        // order panel
        int sx = cx + lw + 6, sw = cw - lw - 6;
        ui.card(sx, top, sw, 70);
        ui.text(ui.clip("Train " + plural(selUnit.label), sw - 16), sx + 8, top + 7, TEXT);
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
            ui.text(u == UnitType.DRAGON_RIDER && t.getFloat("p") >= 1 ? "Waiting for dragon" : u.label, sx + 22, y + 1, TEXT);
            if (u == UnitType.DRAGON_RIDER && ui.over(sx, y, sw, 20)) ui.tip("Needs your unoccupied stage 3+ tamed dragon within 48 blocks of the barracks.", "You can cancel to refund the recruit and training materials.");
            ui.bar(sx + 22, y + 11, sw - 50, 4, t.getFloat("p"), i == 0 ? GOLD : FAINT, i == 0 ? GOLD_DK : FAINT);
            final int idx = i;
            if (mine()) ui.button("ct" + i, sx + sw - 24, y + 2, 16, 13, "×", Style.GHOST, true, () -> act("cancelTraining", tag("index", idx)));
            y += 20;
            if (y > qy + ch - 76 - 30 - 18) break;
        }
        if (training.isEmpty()) ui.wrap("Recruits are drawn from your townsfolk and need their weapon in the armoury.", sx + 8, y, sw - 16, FAINT);
    }

    static String plural(String label) { return label.endsWith("man") ? label.substring(0, label.length() - 3) + "men" : label + "s"; }

    void kits() {
        int top = cy + 30;
        CompoundTag kits = v().getCompound("kits");
        String autoLabel = cw < 340 ? "Auto-equip" : "Auto-equip new soldiers";
        ui.text(autoLabel, cx + 4, top + 2, TEXT);
        int tx = cx + 10 + ui.width(autoLabel);
        if (mine()) ui.toggle("auto", tx, top + 1, v().getBoolean("autoEquip"), () -> act("autoEquip", tag("on", !v().getBoolean("autoEquip"))));
        if (ui.over(cx, top, tx + 24 - cx, 12)) ui.tip("Auto-equip", "New soldiers, and soldiers at home with empty slots, take the kit below from the armoury.");
        int ew = Math.min(150, cx + cw - tx - 32);
        if (mine()) ui.button("eqall", cx + cw - ew, top - 2, ew, 15, ui.clip("Equip everyone now", ew - 8), Style.PRIMARY, true, () -> act("equipAll"));
        List<Smithing.Pattern> armors = new ArrayList<>(), weapons = new ArrayList<>();
        for (var p : Smithing.patterns().values()) {
            if (Arrays.stream(Piece.ARMOR).anyMatch(p.pieces()::containsKey)) armors.add(p);
            if (p.pieces().keySet().stream().anyMatch(k -> k.melee() || k.ranged())) weapons.add(p);
        }
        int y = top + 18;
        int rowH = (ch - 50) / UnitType.values().length;
        // narrow windows: the unit's name moves into a tooltip on its icon, the cyclers share what is left
        boolean names = cw >= 360, labels = rowH >= 32;
        int nameW = names ? 96 : 28, extras = 104, cyc = Math.max(70, Math.min(120, (cw - nameW - extras - 16) / 2));
        int cyY = labels ? 13 : (rowH - 3 - 15) / 2;
        for (UnitType u : UnitType.values()) {
            CompoundTag k = kits.getCompound(u.name());
            ui.card(cx, y, cw, rowH - 3);
            ui.item(u.icon, cx + 8, y + (rowH - 19) / 2);
            if (names) ui.text(ui.clip(u.label, nameW - 30), cx + 28, y + (rowH - 11) / 2, TEXT);
            if (ui.over(cx, y, nameW, rowH - 3)) ui.tip(u.label, u.description);
            int x = cx + nameW + 4;
            // armour
            var ap = Smithing.get(k.getString("armor"));
            if (labels) ui.text("Armour", x, y + 4, FAINT);
            if (ui.over(x, y + cyY, cyc, 15)) ui.tip("Armour for " + plural(u.label).toLowerCase(Locale.ROOT) + ": " + (ap == null ? "none" : ap.name()), mine() ? "Click for the next pattern, shift-click for the previous" : "");
            cycler("ka" + u, x, y + cyY, cyc, ap == null ? "None" : ap.name(), ap == null ? null : ap.pieces().getOrDefault(Piece.CHESTPLATE, ap.pieces().values().iterator().next()).item(),
                    () -> act("kit", tag("unit", u.name(), "armor", next(armors, k.getString("armor"), true))));
            // weapon
            var wp = Smithing.get(k.getString("weapon"));
            Piece piece;
            try { piece = Piece.valueOf(k.getString("piece")); } catch (Exception e) { piece = Piece.SWORD; }
            final Piece fp = piece;
            x += cyc + 6;
            if (labels) ui.text("Weapon", x, y + 4, FAINT);
            var wr = wp == null ? null : wp.pieces().get(piece);
            if (ui.over(x, y + cyY, cyc, 15)) ui.tip("Weapon for " + plural(u.label).toLowerCase(Locale.ROOT) + ": " + (wp == null ? "none" : wr == null ? wp.name() + " (n/a)" : wr.item().getDescription().getString()),
                    mine() ? "Click for the next pattern, shift-click for the previous" : "");
            cycler("kw" + u, x, y + cyY, cyc, wp == null ? "None" : wr == null ? wp.name() + " (n/a)" : wr.item().getDescription().getString(), wr == null ? null : wr.item(),
                    () -> act("kit", tag("unit", u.name(), "weapon", next(weapons, k.getString("weapon"), false))));
            x += cyc + 6;
            List<Piece> kinds = (u == UnitType.BOWMAN || u == UnitType.CROSSBOWMAN || u == UnitType.DRAGON_RIDER) ? List.of(u != UnitType.CROSSBOWMAN ? Piece.BOW : Piece.CROSSBOW) : List.of(Piece.SWORD, Piece.AXE, Piece.MACE, Piece.LANCE);
            if (kinds.size() > 1 && x + 70 < cx + cw) {
                if (labels) ui.text("Kind", x, y + 4, FAINT);
                if (ui.over(x, y + cyY, 60, 15)) ui.tip("Kind of melee weapon", "Click to cycle");
                ui.button("kp" + u, x, y + cyY, 60, 15, fp.label, Style.SECONDARY, mine(), () -> {
                    int i = kinds.indexOf(fp);
                    act("kit", tag("unit", u.name(), "piece", kinds.get((i + 1) % kinds.size()).name()));
                });
                x += 66;
            }
            if (u == UnitType.SHIELDBEARER || u == UnitType.FOOTMAN || u == UnitType.HORSEMAN) {
                if (x + 30 < cx + cw) {
                    if (labels) ui.text("Shield", x, y + 4, FAINT);
                    if (ui.over(x, y + cyY, 24, 15)) ui.tip("Shield", "Carry a shield in the off hand");
                    if (mine()) ui.toggle("ks" + u, x, y + cyY + (labels ? 2 : 1), k.getBoolean("shield"), () -> act("kit", tag("unit", u.name(), "shield", !k.getBoolean("shield"))));
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

    static final String[] FORGE_PIECES = {"SET", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "SWORD", "AXE", "MACE", "LANCE", "SHIELD", "BOW", "CROSSBOW", "ARROWS", "DRAGON_ARMOR", "SPECIAL_WEAPON", "WAR_MACHINE"};
    static final Item[] FORGE_ICONS = {Items.ARMOR_STAND, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.IRON_SWORD,
            Items.IRON_AXE, Items.IRON_SHOVEL, Items.TRIDENT, Items.SHIELD, Items.BOW, Items.CROSSBOW, Items.ARROW, Items.IRON_HORSE_ARMOR, Items.BLAZE_ROD, Items.DISPENSER};

    void forge() {
        milTab = 2;
        int smiths = v().getCompound("jobs").contains("SMITH") ? v().getCompound("jobs").getIntArray("SMITH")[0] : 0;
        milHeader("Blacksmith", v().getInt("smithy") == 0 ? "Build a Smithy to take orders" : smiths + " smith" + (smiths == 1 ? "" : "s") + " at the forge");
        int top = cy + 30;
        Map<Item, Integer> stock = stock();
        Set<String> done = new HashSet<>(strings("research"));
        // pieces: an icon column in narrow windows
        boolean icons = cw < 340;
        int lw = icons ? 24 : 92;
        ui.card(cx, top, lw, ch - 98);
        int shown = CompatPanel.forgePieces(FORGE_PIECES.length);
        int pieceScroll = ui.beginScroll("forge-pieces", cx + 2, top + 2, lw - 4, ch - 102, shown * 15);
        int y = top + 4 - pieceScroll;
        for (int i = 0; i < shown; i++) {
            final String p = FORGE_PIECES[i];
            boolean sel = p.equals(forgePiece), on = ui.over(cx + 2, y, lw - 4, 15);
            if (sel) ui.round(cx + 3, y, lw - 6, 15, 4, 0xFF2E3542);
            else if (on) ui.round(cx + 3, y, lw - 6, 15, 4, 0x18FFFFFF);
            ui.itemScaled(new ItemStack(FORGE_ICONS[i]), cx + 6, y + 1, 0.75f);
            String pieceLabel = p.equals("SET") ? "Full armour set" : Piece.valueOf(p).label;
            if (!icons) ui.text(ui.clip(pieceLabel, lw - 26), cx + 22, y + 4, sel ? GOLD : TEXT);
            if (on && (icons || ui.width(pieceLabel) > lw - 26)) ui.tip(pieceLabel);
            ui.area(cx + 2, y, lw - 4, 15, () -> { forgePiece = p; forgePattern = null; });
            y += 15;
        }
        ui.endScroll();
        // patterns offering it
        int mx = cx + lw + 6, mw = Math.min(132, (cw - lw - 12) / 2);
        ui.card(mx, top, mw, ch - 98);
        search.setX(mx + 6); search.setY(top + 6); search.setWidth(mw - 12);
        ui.round(mx + 3, top + 3, mw - 6, 17, 3, 0xFF10151E);
        String forgeQuery = search.getValue().toLowerCase(Locale.ROOT);
        List<Smithing.Pattern> pats = new ArrayList<>();
        for (var p : Smithing.patterns().values()) {
            if (!forgeQuery.isBlank() && !(p.name() + " " + p.id()).toLowerCase(Locale.ROOT).contains(forgeQuery)) continue;
            if (forgePiece.equals("SET")) { if (Arrays.stream(Piece.ARMOR).filter(p.pieces()::containsKey).count() >= 3) pats.add(p); }
            else if (p.pieces().containsKey(Piece.valueOf(forgePiece))) pats.add(p);
        }
        if (forgePattern == null || pats.stream().noneMatch(p -> p.id().equals(forgePattern))) {
            forgePattern = pats.stream().filter(p -> p.research() == null || done.contains(p.research())).map(Smithing.Pattern::id).reduce((a, b) -> b)
                    .orElse(pats.isEmpty() ? null : pats.get(0).id());
        }
        int off = ui.beginScroll("pats", mx + 2, top + 23, mw - 4, ch - 123, pats.size() * 20);
        y = top + 24 - off;
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
            String orderName = pat.name() + (forgePiece.equals("SET") ? " set" : " " + Piece.valueOf(forgePiece).label.toLowerCase(Locale.ROOT));
            ui.text(ui.clip(orderName, dw - 16), dx + 8, top + 40, TEXT);
            if (ui.over(dx + 8, top + 39, dw - 16, 10) && ui.width(orderName) > dw - 16) ui.tip(orderName);
            String lock = null;
            for (Piece p : pieces) {
                var r = pat.pieces().get(p);
                if (r.research() != null && !done.contains(r.research())) { lock = "Research " + researchName(r.research()); break; }
            }
            if (lock == null && v().getInt("smithy") == 0) lock = "Build a Smithy";
            String forgeStatus = lock == null ? "Ready to forge" : "🔒 " + lock;
            ui.text(ui.clip(forgeStatus, dw - 16), dx + 8, top + 51, lock == null ? GREEN : RED);
            if (ui.over(dx + 8, top + 50, dw - 16, 11)) ui.tip(forgeStatus);
            // costs
            Map<String, Integer> cost = new LinkedHashMap<>();
            float work = 0;
            for (Piece p : pieces) { pat.pieces().get(p).cost().forEach((k, n) -> cost.merge(k, n * forgeQty, Integer::sum)); work += p.work * forgeQty; }
            // short cards: materials as one row of icons, quantity and order on one line
            boolean shortCard = ch - 98 < 150;
            int cy2 = top + 66;
            if (!shortCard) { ui.text("Materials", dx + 8, cy2, MUTED); cy2 += 11; }
            boolean afford = true;
            int matBottom = top + ch - 98 - 44 - 12, hidden = 0, mx2 = dx + 8;
            List<String> hiddenTip = new ArrayList<>();
            for (var e : cost.entrySet()) {
                int has = have(stock, e.getKey());
                boolean ok = has >= e.getValue();
                afford &= ok;
                String count = num(has) + "/" + num(e.getValue());
                int cellW = 20 + (int) (ui.width(count) * 0.75f) + 6;
                if (shortCard ? hidden > 0 || mx2 + cellW > dx + dw - 26 : cy2 + 16 > matBottom) {
                    hidden++; hiddenTip.add(ingName(e.getKey()) + ": " + num(has) + " / " + num(e.getValue())); continue;
                }
                if (shortCard) {
                    ui.itemTip(icon(e.getKey()), mx2, cy2 - 4);
                    ui.big(count, mx2 + 18, cy2 + 2, ok ? GREEN : RED, 0.75f);
                    mx2 += cellW;
                    continue;
                }
                ui.itemTip(icon(e.getKey()), dx + 8, cy2);
                ui.text(ui.clip(ingName(e.getKey()), dw - 90), dx + 28, cy2 + 4, TEXT);
                ui.right(num(has) + " / " + num(e.getValue()), dx + dw - 10, cy2 + 4, ok ? GREEN : RED);
                cy2 += 18;
            }
            if (hidden > 0) {
                String more = shortCard ? "+" + hidden : "+" + hidden + " more";
                int hy = shortCard ? cy2 + 1 : top + 66;
                ui.right(more, dx + dw - 10, hy, FAINT);
                if (ui.over(dx + dw - 12 - ui.width(more), hy - 1, ui.width(more) + 4, 10)) ui.tip(hiddenTip);
            }
            double days = smiths == 0 ? -1 : work / smiths;
            String when = days < 0 ? "no smiths" : eta(days);
            final boolean ok = lock == null && afford;
            Runnable order = () -> act("order", tag("pattern", pat.id(), "piece", forgePiece, "qty", forgeQty));
            String orderLabel = ok ? "Place order" : lock != null ? "Locked" : "Not enough materials";
            if (shortCard) {
                int qy = top + ch - 98 - 22, qx = dx + 6;
                ui.button("q-1", qx, qy, 16, 16, "−", Style.SECONDARY, forgeQty > 1, () -> forgeQty = Math.max(1, forgeQty - (Screen.hasShiftDown() ? 10 : 1)));
                ui.center(String.valueOf(forgeQty), qx + 27, qy + 4, TEXT);
                ui.button("q+1", qx + 38, qy, 16, 16, "+", Style.SECONDARY, forgeQty < 64, () -> forgeQty = Math.min(64, forgeQty + (Screen.hasShiftDown() ? 10 : 1)));
                if (ui.over(qx, qy, 54, 16)) ui.tip("Quantity " + forgeQty, "Shift-click steps by 10", "Time: " + when);
                int bx = qx + 58;
                if (mine()) ui.button("order", bx, qy, dx + dw - 6 - bx, 16, ui.clip(ok ? "Order" : orderLabel, dx + dw - 6 - bx - 6), Style.PRIMARY, ok, order);
                if (ui.over(bx, qy, dx + dw - 6 - bx, 16)) ui.tip(orderLabel, forgeQty + " × " + orderName + " · " + when);
            } else {
                int qy = top + ch - 98 - 44;
                // the label and the ±10 steps give way in a narrow card; shift-click steps by 10 either way
                boolean full = dw >= 180;
                if (full) ui.text("Quantity", dx + 8, qy + 4, MUTED);
                int qx = full ? dx + 58 : dx + 6 - 24;
                if (full) ui.button("q-10", qx, qy, 22, 15, "−10", Style.SECONDARY, forgeQty > 1, () -> forgeQty = Math.max(1, forgeQty - 10));
                ui.button("q-1", qx + 24, qy, 16, 15, "−", Style.SECONDARY, forgeQty > 1, () -> forgeQty = Math.max(1, forgeQty - (Screen.hasShiftDown() ? 10 : 1)));
                ui.center(String.valueOf(forgeQty), qx + 54, qy + 4, TEXT);
                ui.button("q+1", qx + 68, qy, 16, 15, "+", Style.SECONDARY, forgeQty < 64, () -> forgeQty = Math.min(64, forgeQty + (Screen.hasShiftDown() ? 10 : 1)));
                if (ui.over(qx + 24, qy, 60, 15)) ui.tip("Quantity " + forgeQty, "Shift-click steps by 10");
                if (full) ui.button("q+10", qx + 86, qy, 22, 15, "+10", Style.SECONDARY, forgeQty < 64, () -> forgeQty = Math.min(64, forgeQty + 10));
                // beside the quantity buttons if there's room, otherwise just above them
                if (dx + dw - 8 - ui.width(when) > qx + (full ? 112 : 88)) ui.right(when, dx + dw - 8, qy + 4, FAINT);
                else ui.right(when, dx + dw - 8, qy - 10, FAINT);
                if (mine()) ui.button("order", dx + 6, qy + 21, dw - 12, 17, orderLabel, Style.PRIMARY, ok, order);
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
        x /= fit; y /= fit;
        if (search.visible && search.mouseClicked(x, y, button)) { setFocused(search); return true; }
        search.setFocused(false);
        if (tab == Tab.MAP && mapPlan != null && x >= mapX && x < mapX + mapW && y >= mapY && y < mapY + mapH && !overMapControls(x, y)) {
            if (button == 1) { dragMap = true; return true; }
            if (button == 0 && mine() && mapScale > 0) {
                int wx = (int)Math.floor((x - mapOriginX) / mapScale) + CityMap.originX;
                int wz = (int)Math.floor((y - mapOriginY) / mapScale) + CityMap.originZ;
                act("planMap", tag("type", mapPlan, "x", wx, "z", wz, "rot", mapRotation));
                return true;
            }
        }
        if (button == 0 && ui.click(x, y)) return true;
        if (button == 0 && tab == Tab.MAP && x >= mapX && x < mapX + mapW && y >= mapY && y < mapY + mapH) { dragMap = true; return true; }
        if (button == 0 && tab == Tab.RESEARCH && x >= resX && x < resX + resW && y >= resY && y < resY + resH) { dragRes = true; selResearch = null; return true; }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        x /= fit; y /= fit;
        dragMap = dragRes = false;
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        x /= fit; y /= fit; dx /= fit; dy /= fit;
        if (dragMap) { mapPanX += dx; mapPanY += dy; return true; }
        if (dragRes) { resPanX = Mth.clamp(resPanX + (float) dx, -900, 40); resPanY = Mth.clamp(resPanY + (float) dy, -400, 40); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        x /= fit; y /= fit;
        if (tab == Tab.MAP && x >= mapX && x < mapX + mapW && y >= mapY && y < mapY + mapH) {
            // zoom around the cursor
            zoomMap(delta > 0 ? 1.25f : 0.8f, (float) x, (float) y);
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
        if (tab == Tab.MAP && mapPlan != null && livingrealms.client.ClientSetup.ROTATE.matches(key, scan)) { mapRotation = (mapRotation + 1) % 4; return true; }
        if (tab == Tab.MAP && mapPlan != null && key == GLFW.GLFW_KEY_ESCAPE) { mapPlan = null; return true; }
        if (livingrealms.client.ClientSetup.OPEN.matches(key, scan)) { onClose(); return true; }
        // page shortcuts follow the hotbar keybinds (1–8 by default); Tab and Shift+Tab step through the pages
        var hotbar = Minecraft.getInstance().options.keyHotbarSlots;
        for (int i = 0; i < Math.min(hotbar.length, Tab.values().length); i++)
            if (hotbar[i].matches(key, scan)) { switchTab(Tab.values()[i]); return true; }
        if (key == GLFW.GLFW_KEY_TAB) {
            Tab[] all = Tab.values();
            switchTab(all[(tab.ordinal() + (Screen.hasShiftDown() ? all.length - 1 : 1)) % all.length]);
            return true;
        }
        if (search.visible && key == GLFW.GLFW_KEY_F && Screen.hasControlDown()) { setFocused(search); search.setFocused(true); return true; }
        if (selectedSoldier != null && tab == Tab.MILITARY && key == GLFW.GLFW_KEY_BACKSPACE) { selectedSoldier = null; return true; }
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

    /** Tour support: the City Map's zoom and pan. */
    public void tourMap(float zoom, float panX, float panY) { mapZoom = zoom; mapPanX = panX; mapPanY = panY; }

    /** Tour support: a scrolling region's offset ("proj", "blist", "overview", ...). */
    public void tourScroll(String id, float off) { ui.setScroll(id, off); }

    /** Tour support: the Build page's category ("ALL" or a Category name). */
    public void tourCategory(String cat) { catFilter = cat; selType = null; ui.resetScroll("blist"); }

    /** Tour support: the City Map legend's picked-out category (null for all). */
    public void tourFocus(String cat) { mapFocus = cat == null ? null : Category.valueOf(cat); }

    /** Tour support: opens a Projects group ("q:City wall", "c:HOUSING", ...). */
    public void tourOpenGroup(String key) { projOpen.add(key); projClosed.remove(key); }

    /** Tour support: selects the first plot whose type id contains the text (-1 when none); returns its id. */
    public int tourSelectPlot(String type) {
        selPlot = -1;
        for (PlotView p : plots()) if (p.type.contains(type) && !p.type.equals("road")) { selPlot = p.id; break; }
        return selPlot;
    }

    /** Tour support: puts the mouse over a point in this screen's layout units. */
    public void tourMouse(double lx, double ly) {
        var win = Minecraft.getInstance().getWindow();
        double sx = lx * fit * win.getScreenWidth() / win.getGuiScaledWidth(), sy = ly * fit * win.getScreenHeight() / win.getGuiScaledHeight();
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(win.getWindow(), sx, sy);
        try {
            var mh = Minecraft.getInstance().mouseHandler;
            for (String[] f : new String[][]{{"xpos", String.valueOf(sx)}, {"ypos", String.valueOf(sy)}}) {
                var field = mh.getClass().getDeclaredField(f[0]);
                field.setAccessible(true);
                field.setDouble(mh, Double.parseDouble(f[1]));
            }
        } catch (ReflectiveOperationException ignored) {}
    }

    /** Tour support: puts the mouse over the middle of the first plot of a type on the City Map (drawn at least once). */
    public boolean tourHoverPlot(String type) {
        if (mapScale <= 0) return false;
        for (PlotView p : plots()) if (p.type.contains(type) && !p.type.equals("road")) {
            tourMouse(mapOriginX + ((p.x0 + p.x1 + 1) / 2f - CityMap.originX) * mapScale, mapOriginY + ((p.z0 + p.z1 + 1) / 2f - CityMap.originZ) * mapScale);
            return true;
        }
        return false;
    }
}
