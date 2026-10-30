package livingrealms.settlement;

import livingrealms.LivingRealms;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.building.Procedural;
import livingrealms.research.ResearchTree;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Trade between towns (docs/trade.md). Every town makes goods from its land and its workshops; towns in reach of one
 * another and at peace keep a trade route; when one side has a surplus of what the other is short of, a caravan sets
 * out. Prices follow supply and need. Saved with the settlements; the caravans themselves are in {@link Caravans}.
 */
public final class Trade {
    /** How often production, routes and dispatch advance, in ticks. */
    public static final int STEP = 1200;
    /** Farthest two town centres can be apart and still trade. */
    public static final int RANGE = 768;
    /** Each town trades with at most this many neighbours, nearest first. */
    public static final int MAX_ROUTES = 3;
    /** Goods one caravan carries, and the least worth sending. */
    public static final int CARGO = 96, MIN_LOAD = 8;
    /** A town keeps this much over its own needs before it exports. */
    public static final int RESERVE = 16;
    /** Price damping: with this much stock and need a good costs its base price. */
    public static final int REF = 32;
    /** Production stops at this much of a good in stock, so towns do not flood. */
    public static final int SOFT_CAP = 192;
    /** Ticks a route waits between caravans. */
    public static final int COOLDOWN = 2 * STEP;

    // ------------------------------------------------------------------ goods

    /** The goods towns make and trade, with a base price in emeralds per unit. Anything a town's builders need also trades. */
    public static final Map<Item, Double> BASE = new LinkedHashMap<>();
    static {
        BASE.put(Items.WHEAT, .1); BASE.put(Items.BREAD, .25); BASE.put(Items.CARROT, .1); BASE.put(Items.POTATO, .1);
        BASE.put(Items.WHITE_WOOL, .3); BASE.put(Items.LEATHER, .5); BASE.put(Items.RABBIT_HIDE, .3);
        BASE.put(Items.OAK_LOG, .25); BASE.put(Items.SPRUCE_LOG, .25); BASE.put(Items.ACACIA_LOG, .25); BASE.put(Items.JUNGLE_LOG, .25);
        BASE.put(Items.SAND, .05); BASE.put(Items.SANDSTONE, .1); BASE.put(Items.GLASS, .2); BASE.put(Items.TERRACOTTA, .15);
        BASE.put(Items.YELLOW_DYE, .3); BASE.put(Items.GREEN_DYE, .3); BASE.put(Items.PACKED_ICE, .3);
        BASE.put(Items.COBBLESTONE, .03); BASE.put(Items.COAL, .2); BASE.put(Items.IRON_INGOT, 1.0);
    }

    public static double base(Item it) { return BASE.getOrDefault(it, .25); }

    /** What a biome family makes, per day, before the town's size is counted. */
    static Map<Item, Double> specialties(String family) {
        Map<Item, Double> m = new LinkedHashMap<>();
        switch (family) {
            case "taiga" -> { m.put(Items.SPRUCE_LOG, 10.0); m.put(Items.LEATHER, 2.0); m.put(Items.RABBIT_HIDE, 3.0); }
            case "snowy" -> { m.put(Items.SPRUCE_LOG, 6.0); m.put(Items.RABBIT_HIDE, 4.0); m.put(Items.PACKED_ICE, 4.0); m.put(Items.LEATHER, 1.0); }
            case "desert" -> { m.put(Items.SAND, 16.0); m.put(Items.GLASS, 6.0); m.put(Items.SANDSTONE, 6.0); m.put(Items.YELLOW_DYE, 2.0); m.put(Items.GREEN_DYE, 2.0); }
            case "savanna" -> { m.put(Items.ACACIA_LOG, 10.0); m.put(Items.TERRACOTTA, 6.0); m.put(Items.WHITE_WOOL, 2.0); }
            default -> { m.put(Items.WHEAT, 12.0); m.put(Items.BREAD, 4.0); m.put(Items.WHITE_WOOL, 3.0); m.put(Items.OAK_LOG, 4.0); }
        }
        return m;
    }

    static Item log(Settlement s) {
        return switch (Procedural.styleFamily(s.style)) {
            case "taiga", "snowy" -> Items.SPRUCE_LOG;
            case "savanna" -> Items.ACACIA_LOG;
            case "desert" -> Items.JUNGLE_LOG;
            default -> Items.OAK_LOG;
        };
    }

    /** NPC towns and Village Expansion villages have no worked buildings in the simulation, so their buildings yield here. */
    static boolean npcEconomy(Settlement s) { return s.isAi() || s.mirror(); }

    /** Goods a town makes in a day: its land's specialties for every town, and for NPC towns their buildings' too. */
    public static Map<Item, Double> production(Settlement s) {
        double size = 1 + s.population / 10.0;
        Map<Item, Double> out = new LinkedHashMap<>();
        specialties(Procedural.styleFamily(s.style)).forEach((it, n) -> out.put(it, n * size));
        if (npcEconomy(s)) for (Plot p : s.plots) {
            if (!p.standing()) continue;
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t == null) continue;
            int lv = Math.max(1, p.level);
            switch (t.category) {
                case FARM -> { out.merge(Items.WHEAT, 6.0 * lv, Double::sum); out.merge(Items.BREAD, 2.0 * lv, Double::sum); out.merge(Items.CARROT, 3.0 * lv, Double::sum); }
                case LUMBER -> out.merge(log(s), 6.0 * lv, Double::sum);
                case MINE -> { out.merge(Items.COBBLESTONE, 12.0 * lv, Double::sum); out.merge(Items.COAL, 3.0 * lv, Double::sum); out.merge(Items.IRON_INGOT, 1.0 * lv, Double::sum); }
                case SMITHY -> out.merge(Items.IRON_INGOT, 2.0 * lv, Double::sum);
                case STABLE -> out.merge(Items.LEATHER, 2.0 * lv, Double::sum);
                default -> {}
            }
        }
        return out;
    }

    /** One step of f days of making (and, for NPC towns, using) goods. */
    static void produce(Settlement s, double f) {
        Ledger l = null;
        for (var e : production(s).entrySet()) {
            int room = SOFT_CAP - s.stock.count(e.getKey());
            if (room <= 0) continue;
            String key = "trade:" + BuiltInRegistries.ITEM.getKey(e.getKey());
            double v = s.accum.getOrDefault(key, 0.0) + e.getValue() * f;
            int whole = Math.min((int) v, room);
            if (whole > 0) s.stock.add(e.getKey(), whole);
            s.accum.put(key, v - (int) v);
        }
        // NPC households use a quarter of their goods a day: their stores turn over and they keep wanting more
        if (npcEconomy(s)) for (Item it : BASE.keySet()) {
            double key = s.accum.getOrDefault("trade-use:" + BuiltInRegistries.ITEM.getKey(it), 0.0) + s.stock.count(it) * 0.25 * f;
            int n = (int) key;
            if (n > 0) s.stock.takeUpTo(it, n);
            s.accum.put("trade-use:" + BuiltInRegistries.ITEM.getKey(it), key - n);
        }
    }

    /** What the town wants on hand: what is left to pay for its building sites and research, and what every household keeps. */
    public static Map<Item, Integer> wants(Settlement s) {
        Map<Item, Integer> w = new LinkedHashMap<>();
        if (!s.isAi()) for (Plot p : s.plots) {
            if (!AutoBuild.pending(p) || p.state == Plot.State.DEMOLISHING) continue;
            Blueprint bp = p.blueprint();
            if (bp == null) continue;
            double left = Math.max(0, 1 - Math.max(p.placed, p.paid) / (double) Math.max(1, p.totalSteps()));
            for (var e : bp.bill().entrySet()) {
                int n = (int) Math.ceil(e.getValue() * left);
                if (n > 0) w.merge(e.getKey(), n, Integer::sum);
            }
        }
        var r = s.activeResearch.isEmpty() ? null : ResearchTree.get(s.activeResearch);
        if (r != null) for (var e : r.items().entrySet()) {
            if (e.getKey().startsWith("#")) continue;
            Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(e.getKey()));
            if (it != Items.AIR) w.merge(it, e.getValue(), Integer::sum);
        }
        int pop = s.population;
        w.merge(Items.BREAD, Math.max(8, pop * 2), Integer::sum);
        w.merge(log(s), 16 + pop * 2, Integer::sum);
        w.merge(Items.GLASS, 4 + pop, Integer::sum);
        w.merge(Items.IRON_INGOT, 4 + pop / 2, Integer::sum);
        w.merge(Items.LEATHER, 4 + pop / 2, Integer::sum);
        w.merge(Items.WHITE_WOOL, 4 + pop / 2, Integer::sum);
        return w;
    }

    /** How many the town is short of. */
    public static int need(Settlement s, Map<Item, Integer> wants, Item it) {
        return Math.max(0, wants.getOrDefault(it, 0) - s.stock.count(it));
    }

    /** Price in emeralds: dear where stocks are low against what the town wants, cheap where they pile up. */
    public static double price(Settlement s, Map<Item, Integer> wants, Item it) {
        double ratio = (wants.getOrDefault(it, 0) + REF) / (double) (s.stock.count(it) + REF);
        return base(it) * Math.max(0.25, Math.min(4, ratio));
    }

    /** What the town can spare of a good: stock past its wants and reserve, unless its ruler banned the export. */
    public static int surplus(SettlementManager m, Settlement s, Map<Item, Integer> wants, Item it) {
        if (it == Items.EMERALD || m.trade.ledger(s.id).bans.contains(it)) return 0;
        return Math.max(0, s.stock.count(it) - wants.getOrDefault(it, 0) - RESERVE);
    }

    // ------------------------------------------------------------------ state

    /** A town's trade book. */
    public static final class Ledger {
        public final Set<Item> bans = new LinkedHashSet<>();
        /** 0..100: rises with every caravan, fades by a tenth a day. */
        public double prosperity;
        public int sent, received, lost;
        public double earned;

        boolean isEmpty() { return bans.isEmpty() && prosperity == 0 && sent == 0 && received == 0 && lost == 0; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            ListTag b = new ListTag();
            bans.forEach(it -> b.add(StringTag.valueOf(BuiltInRegistries.ITEM.getKey(it).toString())));
            t.put("bans", b);
            t.putDouble("prosperity", prosperity);
            t.putInt("sent", sent); t.putInt("received", received); t.putInt("lost", lost); t.putDouble("earned", earned);
            return t;
        }

        static Ledger load(CompoundTag t) {
            Ledger l = new Ledger();
            for (Tag x : t.getList("bans", Tag.TAG_STRING)) {
                Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(x.getAsString()));
                if (it != Items.AIR) l.bans.add(it);
            }
            l.prosperity = Statecraft.clamp(t.getDouble("prosperity"), 0, 100);
            l.sent = t.getInt("sent"); l.received = t.getInt("received"); l.lost = t.getInt("lost"); l.earned = t.getDouble("earned");
            return l;
        }
    }

    /** A road two towns trade along. Either town's ruler may close it. */
    public static final class Route {
        public UUID a, b;
        public final Set<UUID> closedBy = new HashSet<>();
        public int trips, lost;
        public long lastSent;
        /** Why no caravan runs, or "" when the route is open; recomputed each step. */
        public String blocked = "";
        public boolean active;

        public boolean involves(UUID s) { return a.equals(s) || b.equals(s); }
        public UUID other(UUID s) { return a.equals(s) ? b : a; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("a", a); t.putUUID("b", b);
            ListTag c = new ListTag();
            closedBy.forEach(id -> c.add(StringTag.valueOf(id.toString())));
            t.put("closedBy", c);
            t.putInt("trips", trips); t.putInt("lost", lost); t.putLong("lastSent", lastSent);
            t.putString("blocked", blocked); t.putBoolean("active", active);
            return t;
        }

        static Route load(CompoundTag t) {
            Route r = new Route();
            r.a = t.getUUID("a"); r.b = t.getUUID("b");
            for (Tag x : t.getList("closedBy", Tag.TAG_STRING)) try { r.closedBy.add(UUID.fromString(x.getAsString())); } catch (IllegalArgumentException ignored) {}
            r.trips = t.getInt("trips"); r.lost = t.getInt("lost"); r.lastSent = t.getLong("lastSent");
            r.blocked = t.getString("blocked"); r.active = t.getBoolean("active");
            return r;
        }
    }

    public enum Status {
        LOADING("Loading"), ROAD("On the road"), ARRIVED("Arrived"), LOST("Lost");
        public final String label;
        Status(String label) { this.label = label; }
        static Status of(String s) { try { return valueOf(s); } catch (Exception e) { return LOST; } }
    }

    /** One caravan: a trader with a pack animal and guards, walking when someone is near, simulated when not. */
    public static final class Caravan {
        public UUID id = UUID.randomUUID();
        public UUID from, to;
        public final Map<Item, Integer> cargo = new LinkedHashMap<>();
        public Status status = Status.LOADING;
        /** Game time the current status began. */
        public long since;
        /** 0..1 along the road. */
        public double progress;
        public int guards, ambushes;
        public double value;
        public String note = "";
        /** True while its trader walks in the world; otherwise travel is simulated. */
        public boolean physical;
        @Nullable public UUID trader, beast;
        public final List<UUID> guardIds = new ArrayList<>();
        // runtime: last place the trader was seen and how long it has not moved
        public double lastX, lastZ;
        public int stuck;
        public long walkingSince;
        /** Players walking with it for an escort bounty this second (they count as an escort, like the ruler). */
        public final transient Set<UUID> escorts = new HashSet<>();

        public boolean done() { return status == Status.ARRIVED || status == Status.LOST; }

        public int load() { return cargo.values().stream().mapToInt(Integer::intValue).sum(); }

        public boolean body(UUID e) { return e.equals(trader) || e.equals(beast) || guardIds.contains(e); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id); t.putUUID("from", from); t.putUUID("to", to);
            ListTag c = new ListTag();
            cargo.forEach((it, n) -> { CompoundTag x = new CompoundTag(); x.putString("id", BuiltInRegistries.ITEM.getKey(it).toString()); x.putInt("n", n); c.add(x); });
            t.put("cargo", c);
            t.putString("status", status.name()); t.putLong("since", since); t.putDouble("progress", progress);
            t.putInt("guards", guards); t.putInt("ambushes", ambushes); t.putDouble("value", value); t.putString("note", note);
            t.putBoolean("physical", physical);
            if (trader != null) t.putUUID("trader", trader);
            if (beast != null) t.putUUID("beast", beast);
            ListTag g = new ListTag();
            guardIds.forEach(id -> g.add(StringTag.valueOf(id.toString())));
            t.put("guardIds", g);
            t.putDouble("lastX", lastX); t.putDouble("lastZ", lastZ); t.putLong("walkingSince", walkingSince);
            return t;
        }

        static Caravan load(CompoundTag t) {
            Caravan c = new Caravan();
            c.id = t.getUUID("id"); c.from = t.getUUID("from"); c.to = t.getUUID("to");
            for (Tag x : t.getList("cargo", Tag.TAG_COMPOUND)) {
                CompoundTag ct = (CompoundTag) x;
                Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(ct.getString("id")));
                if (it != Items.AIR && ct.getInt("n") > 0) c.cargo.put(it, ct.getInt("n"));
            }
            c.status = Status.of(t.getString("status")); c.since = t.getLong("since");
            c.progress = Statecraft.clamp(t.getDouble("progress"), 0, 1);
            c.guards = Math.max(0, Math.min(2, t.getInt("guards"))); c.ambushes = t.getInt("ambushes");
            c.value = t.getDouble("value"); c.note = t.getString("note");
            c.physical = t.getBoolean("physical");
            if (t.hasUUID("trader")) c.trader = t.getUUID("trader");
            if (t.hasUUID("beast")) c.beast = t.getUUID("beast");
            for (Tag x : t.getList("guardIds", Tag.TAG_STRING)) try { c.guardIds.add(UUID.fromString(x.getAsString())); } catch (IllegalArgumentException ignored) {}
            c.lastX = t.getDouble("lastX"); c.lastZ = t.getDouble("lastZ"); c.walkingSince = t.getLong("walkingSince");
            return c;
        }
    }

    public final Map<UUID, Ledger> towns = new HashMap<>();
    /** Routes by town pair key. */
    public final Map<String, Route> routes = new LinkedHashMap<>();
    /** Caravans under way, and the last few that arrived or were lost. */
    public final List<Caravan> caravans = new ArrayList<>();
    /** Kingdom pair -> game time until which trade between them is suspended after a caravan was attacked. */
    public final Map<String, Long> grievances = new HashMap<>();
    public long nextStep;

    /** Prosperity by town, for the growth and building hooks in Simulation (which know only the town). */
    static final Map<UUID, Double> PROSPERITY = new HashMap<>();

    public Ledger ledger(UUID town) { return towns.computeIfAbsent(town, k -> new Ledger()); }

    @Nullable
    public Route route(UUID x, UUID y) { return routes.get(Statecraft.pair(x, y)); }

    @Nullable
    public Caravan caravan(UUID id) {
        for (Caravan c : caravans) if (c.id.equals(id)) return c;
        return null;
    }

    public boolean isEmpty() { return routes.isEmpty() && caravans.isEmpty() && grievances.isEmpty() && towns.values().stream().allMatch(Ledger::isEmpty); }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        CompoundTag tw = new CompoundTag();
        towns.forEach((k, v) -> { if (!v.isEmpty()) tw.put(k.toString(), v.save()); });
        t.put("towns", tw);
        ListTag r = new ListTag();
        routes.values().forEach(x -> r.add(x.save()));
        t.put("routes", r);
        ListTag c = new ListTag();
        caravans.forEach(x -> c.add(x.save()));
        t.put("caravans", c);
        CompoundTag g = new CompoundTag();
        grievances.forEach(g::putLong);
        t.put("grievances", g);
        t.putLong("nextStep", nextStep);
        return t;
    }

    public void load(CompoundTag t) {
        CompoundTag tw = t.getCompound("towns");
        for (String k : tw.getAllKeys()) try {
            UUID id = UUID.fromString(k);
            Ledger l = Ledger.load(tw.getCompound(k));
            towns.put(id, l);
            PROSPERITY.put(id, l.prosperity);
        } catch (IllegalArgumentException ignored) {}
        for (Tag x : t.getList("routes", Tag.TAG_COMPOUND)) try {
            Route rt = Route.load((CompoundTag) x);
            routes.put(Statecraft.pair(rt.a, rt.b), rt);
        } catch (RuntimeException e) { LivingRealms.LOG.warn("Dropped a bad trade route", e); }
        for (Tag x : t.getList("caravans", Tag.TAG_COMPOUND)) try { caravans.add(Caravan.load((CompoundTag) x)); } catch (RuntimeException e) { LivingRealms.LOG.warn("Dropped a bad caravan", e); }
        CompoundTag g = t.getCompound("grievances");
        for (String k : g.getAllKeys()) grievances.put(k, g.getLong(k));
        nextStep = t.getLong("nextStep");
    }

    // ------------------------------------------------------------------ prosperity

    /** A trading town grows faster: up to +0.5 growth a day (base growth is 1.5). */
    public static double growthBonus(Settlement s) { return PROSPERITY.getOrDefault(s.id, 0.0) / 200.0; }

    /** And builds a little faster: up to +10%. */
    public static double buildBonus(Settlement s) { return 1 + PROSPERITY.getOrDefault(s.id, 0.0) / 1000.0; }

    static void prosper(SettlementManager m, Settlement s, double v) {
        Ledger l = m.trade.ledger(s.id);
        l.prosperity = Statecraft.clamp(l.prosperity + v, 0, 100);
        PROSPERITY.put(s.id, l.prosperity);
    }

    // ------------------------------------------------------------------ relations

    /** Why two towns may not trade (war, or a caravan lately attacked), or null when they are at peace. */
    @Nullable
    public static String hostility(SettlementManager m, Settlement a, Settlement b, long now) {
        if (Kingdoms.same(a, b)) return null;
        UUID x = a.kingdomId(), y = b.kingdomId();
        if (m.state.war(x, y) != null) return "At war";
        if (Realms.on()) {
            String rx = Realms.knownRealm(m, a), ry = Realms.knownRealm(m, b);
            if (rx != null && ry != null && livingrealms.settlement.vx.VxBridge.atWar(rx, ry)) return "At war";
        }
        Long g = m.trade.grievances.get(Statecraft.pair(x, y));
        if (g != null && g > now) return "Suspended: a caravan was attacked";
        return null;
    }

    /** How the two towns stand, for the Trade page. */
    static String standing(SettlementManager m, Settlement a, Settlement b) {
        if (Kingdoms.same(a, b)) return "Same kingdom";
        if (Realms.on()) {
            String rx = Realms.knownRealm(m, a), ry = Realms.knownRealm(m, b);
            if (rx != null && ry != null && "ALLY".equals(livingrealms.settlement.vx.VxBridge.relation(rx, ry))) return "Allied";
        }
        return "Neutral";
    }

    /** A town that takes part in trade: not a dormant Village Expansion mirror. */
    static boolean trades(Settlement s) { return !Realms.dormant(s); }

    // ------------------------------------------------------------------ the step

    /** Called by the server tick; advances trade once every {@link #STEP} ticks. */
    public static void tick(MinecraftServer server, SettlementManager m) {
        long now = server.overworld().getGameTime();
        if (now < m.trade.nextStep) return;
        m.trade.nextStep = now + STEP;
        try {
            step(server, m, now, STEP / (double) Statecraft.dayLength());
        } catch (RuntimeException e) {
            LivingRealms.LOG.error("Living Realms trade step failed", e);
        }
    }

    /** One step of f days: towns make goods, routes form or close, and loaded routes send caravans. */
    static void step(MinecraftServer server, SettlementManager m, long now, double f) {
        Trade tr = m.trade;
        for (Settlement s : m.all()) if (trades(s)) produce(s, f);
        routes(server, m, now);
        for (Route r : tr.routes.values()) if (r.active) dispatch(server, m, r, now);
        for (Ledger l : tr.towns.values()) l.prosperity *= Math.pow(0.9, f);
        tr.towns.forEach((id, l) -> PROSPERITY.put(id, l.prosperity));
        tr.grievances.values().removeIf(until -> until <= now);
        // keep the last few finished caravans for the Trade page
        List<Caravan> done = tr.caravans.stream().filter(Caravan::done).toList();
        if (done.size() > 12) tr.caravans.removeAll(done.subList(0, done.size() - 12));
    }

    /** Pairs of towns in reach form routes, each town with its nearest few; war, attacks and closures stop them. */
    static void routes(MinecraftServer server, SettlementManager m, long now) {
        Trade tr = m.trade;
        tr.routes.values().removeIf(r -> m.get(r.a) == null || m.get(r.b) == null);
        Map<UUID, List<Settlement>> near = new HashMap<>();
        List<Settlement> all = m.all().stream().filter(Trade::trades).toList();
        for (Settlement s : all) {
            List<Settlement> l = new ArrayList<>();
            for (Settlement o : all) if (o != s && o.dim.equals(s.dim) && o.center.distSqr(s.center) <= (double) RANGE * RANGE) l.add(o);
            l.sort(Comparator.comparingDouble(o -> o.center.distSqr(s.center)));
            near.put(s.id, l.size() > MAX_ROUTES ? l.subList(0, MAX_ROUTES) : l);
        }
        for (Settlement s : all) for (Settlement o : near.get(s.id)) {
            String key = Statecraft.pair(s.id, o.id);
            if (tr.routes.containsKey(key)) continue;
            Route r = new Route();
            r.a = s.id; r.b = o.id;
            tr.routes.put(key, r);
            String msg = "A trade route opened between " + s.name + " and " + o.name + ".";
            s.log(msg); o.log(msg);
        }
        for (Route r : tr.routes.values()) {
            Settlement a = m.get(r.a), b = m.get(r.b);
            boolean inReach = trades(a) && trades(b) && (near.getOrDefault(a.id, List.of()).contains(b) || near.getOrDefault(b.id, List.of()).contains(a));
            String why = !inReach ? "Out of reach" : hostility(m, a, b, now);
            if (why == null && !r.closedBy.isEmpty()) why = "Closed by " + r.closedBy.stream().map(m::get).filter(Objects::nonNull).map(t -> t.name).findFirst().orElse("a ruler");
            r.blocked = why == null ? "" : why;
            boolean was = r.active;
            r.active = why == null;
            if (was != r.active) { a.dirty = true; b.dirty = true; }
        }
    }

    static boolean busy(Trade tr, Route r) {
        for (Caravan c : tr.caravans) if (!c.done() && r.involves(c.from) && r.involves(c.to)) return true;
        return false;
    }

    /** Sends a caravan along the route when one side has a surplus of what the other is short of. */
    @Nullable
    static Caravan dispatch(MinecraftServer server, SettlementManager m, Route r, long now) {
        Trade tr = m.trade;
        if (busy(tr, r) || now - r.lastSent < COOLDOWN && r.lastSent > 0) return null;
        Settlement a = m.get(r.a), b = m.get(r.b);
        if (a == null || b == null) return null;
        // turn about: the side that did not send last goes first
        boolean aFirst = r.trips % 2 == 0;
        Caravan c = load(server, m, aFirst ? a : b, aFirst ? b : a, now);
        if (c == null) c = load(server, m, aFirst ? b : a, aFirst ? a : b, now);
        if (c != null) { r.lastSent = now; a.dirty = true; b.dirty = true; }
        return c;
    }

    /** Fills a caravan at from with what to is short of and from can spare, dearest margin first; null when not worth it. */
    @Nullable
    static Caravan load(MinecraftServer server, SettlementManager m, Settlement from, Settlement to, long now) {
        Map<Item, Integer> fw = wants(from), tw = wants(to);
        Map<Item, Integer> inbound = new HashMap<>();
        for (Caravan o : m.trade.caravans) if (!o.done() && o.to.equals(to.id)) o.cargo.forEach((it, n) -> inbound.merge(it, n, Integer::sum));
        record Pick(Item item, int n, double margin) {}
        List<Pick> picks = new ArrayList<>();
        for (Item it : tw.keySet()) {
            int short_ = need(to, tw, it) - inbound.getOrDefault(it, 0);
            int spare = surplus(m, from, fw, it);
            if (short_ <= 0 || spare <= 0) continue;
            double there = price(to, tw, it), here = price(from, fw, it);
            if (there < here * 1.2) continue;
            picks.add(new Pick(it, Math.min(short_, spare), there - here));
        }
        picks.sort(Comparator.comparingDouble((Pick p) -> -p.margin()));
        Caravan c = new Caravan();
        c.from = from.id; c.to = to.id; c.since = now;
        int room = CARGO;
        for (Pick p : picks) {
            int n = Math.min(p.n(), room);
            if (n <= 0) break;
            c.cargo.put(p.item(), n);
            c.value += n * (price(to, tw, p.item()) + price(from, fw, p.item())) / 2;
            room -= n;
        }
        if (c.load() < MIN_LOAD) return null;
        c.cargo.forEach(from.stock::take);
        // guards from the garrison's strength: towns that keep soldiers send two, small ones one, camps none
        c.guards = from.soldiers.size() >= 6 ? 2 : from.soldiers.size() >= 2 || from.isAi() && from.tier.number() >= 2 ? 1 : 0;
        m.trade.caravans.add(c);
        m.trade.ledger(from.id).sent++;
        from.log("A caravan is loading for " + to.name + ": " + summary(c.cargo));
        return c;
    }

    public static String summary(Map<Item, Integer> cargo) {
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (var e : cargo.entrySet()) {
            if (shown++ == 3) { b.append(" and more"); break; }
            if (b.length() > 0) b.append(", ");
            b.append(e.getValue()).append(' ').append(e.getKey().getDescription().getString().toLowerCase(Locale.ROOT));
        }
        return b.length() == 0 ? "nothing" : b.toString();
    }

    /** The caravan reached its market: goods into the stockpile, coin to the sender, prosperity to both. */
    static void deliver(MinecraftServer server, SettlementManager m, Caravan c, long now) {
        Settlement from = m.get(c.from), to = m.get(c.to);
        c.status = Status.ARRIVED; c.since = now; c.progress = 1;
        if (to != null) c.cargo.forEach(to.stock::force);
        if (from != null) {
            int coin = (int) Math.round(c.value * 0.5);
            if (from.isAi() || from.mirror()) from.wealth += c.value;
            else if (coin > 0) from.stock.force(Items.EMERALD, coin);
            Ledger l = m.trade.ledger(from.id);
            l.earned += c.value;
            prosper(m, from, 3 + c.value / 10);
            from.log("Our caravan reached " + (to == null ? "its market" : to.name) + " and sold its goods for " + coin + " emeralds.");
            from.dirty = true;
        }
        if (to != null) {
            m.trade.ledger(to.id).received++;
            prosper(m, to, 3 + c.value / 10);
            to.log("A caravan from " + (from == null ? "afar" : from.name) + " arrived with " + summary(c.cargo) + ".");
            to.dirty = true;
        }
        Route r = from == null || to == null ? null : m.trade.route(from.id, to.id);
        if (r != null && r.trips == 0 && from != null && to != null) {
            livingrealms.chronicle.Chronicle.record(from, livingrealms.chronicle.Chronicle.Kind.TRADE, "The first caravan to " + to.name + " sold its goods, opening the road between the two towns.");
            livingrealms.chronicle.Chronicle.record(to, livingrealms.chronicle.Chronicle.Kind.TRADE, "The first caravan from " + from.name + " arrived with " + summary(c.cargo) + ".");
        }
        if (r != null) r.trips++;
    }

    /** The caravan was lost on the road. Its goods are gone (or lie where it fell, if it walked). */
    static void lose(MinecraftServer server, SettlementManager m, Caravan c, String why, long now) {
        if (c.done()) return;
        c.status = Status.LOST; c.since = now; c.note = why;
        Settlement from = m.get(c.from), to = m.get(c.to);
        if (from != null) {
            m.trade.ledger(from.id).lost++;
            Raids.announce(server, from, "Our caravan to " + (to == null ? "market" : to.name) + " was lost: " + why);
        }
        if (to != null) { to.log("The caravan from " + (from == null ? "afar" : from.name) + " never arrived: " + why); to.dirty = true; }
        Route r = from == null || to == null ? null : m.trade.route(from.id, to.id);
        if (r != null) { r.lost++; r.trips++; }
    }

    /**
     * Someone of another kingdom attacked a caravan. A first attack suspends trade between the two kingdoms for three
     * days; a second while that lasts is war.
     */
    static void hostileAct(MinecraftServer server, SettlementManager m, Caravan c, UUID attacker, long now) {
        Settlement from = m.get(c.from);
        if (from == null || attacker.equals(from.kingdomId())) return;
        UUID victim = from.kingdomId();
        String key = Statecraft.pair(attacker, victim);
        Long until = m.trade.grievances.get(key);
        boolean hasCities = m.all().stream().anyMatch(s -> s.kingdomId().equals(attacker));
        if (until != null && until > now && hasCities && m.state.war(attacker, victim) == null) {
            Wars.open(server, m, attacker, victim, now, true);
            Statecraft.tellKingdom(server, m, victim, Statecraft.name(m, attacker) + " attacked our caravans again. This means war.");
            return;
        }
        m.trade.grievances.put(key, now + 3L * Statecraft.dayLength());
        String msg = Statecraft.name(m, attacker) + " attacked a caravan of " + Statecraft.name(m, victim) + ". Trade between them is suspended for three days; another attack means war.";
        Statecraft.tellKingdom(server, m, victim, msg);
        if (hasCities) Statecraft.tellKingdom(server, m, attacker, msg);
    }

    // ------------------------------------------------------------------ City Manager

    /** Trade page: stock and prices, routes and their caravans, prosperity. */
    public static CompoundTag view(ServerPlayer p, SettlementManager m, Settlement s) {
        CompoundTag t = new CompoundTag();
        long now = p.server.overworld().getGameTime();
        Ledger l = m.trade.ledger(s.id);
        t.putBoolean("mine", !s.mirror() && (p.getUUID().equals(s.owner) || p.hasPermissions(2)));
        t.putDouble("prosperity", l.prosperity);
        t.putInt("sent", l.sent); t.putInt("received", l.received); t.putInt("lost", l.lost);
        t.putInt("earned", (int) Math.round(l.earned));
        t.putString("family", Procedural.styleFamily(s.style));
        Map<Item, Integer> w = wants(s);
        Map<Item, Double> made = production(s);
        Set<Item> shown = new LinkedHashSet<>(made.keySet());
        for (Item it : BASE.keySet()) if (s.stock.count(it) > 0 || need(s, w, it) > 0) shown.add(it);
        for (Item it : w.keySet()) if (need(s, w, it) > 0) shown.add(it);
        shown.addAll(l.bans);
        ListTag goods = new ListTag();
        for (Item it : shown) {
            CompoundTag g = new CompoundTag();
            g.putString("id", BuiltInRegistries.ITEM.getKey(it).toString());
            g.putInt("stock", s.stock.count(it));
            g.putInt("need", need(s, w, it));
            g.putInt("spare", Math.max(0, s.stock.count(it) - w.getOrDefault(it, 0) - RESERVE));
            g.putDouble("price", price(s, w, it));
            g.putDouble("base", base(it));
            g.putDouble("made", made.getOrDefault(it, 0.0));
            g.putBoolean("ban", l.bans.contains(it));
            goods.add(g);
        }
        t.put("goods", goods);
        ListTag routes = new ListTag();
        for (Route r : m.trade.routes.values()) {
            if (!r.involves(s.id)) continue;
            Settlement o = m.get(r.other(s.id));
            if (o == null) continue;
            CompoundTag rt = new CompoundTag();
            rt.putUUID("partner", o.id);
            rt.putString("name", o.name);
            rt.putString("standing", standing(m, s, o));
            rt.putInt("distance", (int) Math.sqrt(o.center.distSqr(s.center)));
            rt.putBoolean("active", r.active);
            rt.putString("blocked", r.blocked);
            rt.putBoolean("closedHere", r.closedBy.contains(s.id));
            rt.putInt("trips", r.trips); rt.putInt("lost", r.lost);
            Caravan c = null;
            for (Caravan x : m.trade.caravans) if (r.involves(x.from) && r.involves(x.to) && (c == null || x.since >= c.since)) c = x;
            if (c != null) {
                CompoundTag ct = describe(m, c, s, now);
                // for the City Map: where the caravan is now
                ServerLevel lv = p.server.getLevel(s.dim);
                BlockPos at = lv == null || c.done() ? null : Caravans.position(lv, m, c);
                if (at != null) { ct.putInt("x", at.getX()); ct.putInt("z", at.getZ()); }
                rt.put("caravan", ct);
            }
            // for the City Map: the road toward the partner, as x,z pairs
            List<BlockPos> road = CaravanRoad.route(s, o);
            int[] path = new int[road.size() * 2 + 2];
            for (int i = 0; i < road.size(); i++) { path[2 * i] = road.get(i).getX(); path[2 * i + 1] = road.get(i).getZ(); }
            path[path.length - 2] = o.center.getX(); path[path.length - 1] = o.center.getZ();
            rt.putIntArray("path", path);
            routes.add(rt);
        }
        t.put("routes", routes);
        ListTag recent = new ListTag();
        for (int i = m.trade.caravans.size() - 1; i >= 0 && recent.size() < 6; i--) {
            Caravan c = m.trade.caravans.get(i);
            if (c.from.equals(s.id) || c.to.equals(s.id)) recent.add(describe(m, c, s, now));
        }
        t.put("caravans", recent);
        return t;
    }

    static CompoundTag describe(SettlementManager m, Caravan c, Settlement here, long now) {
        CompoundTag t = new CompoundTag();
        boolean out = c.from.equals(here.id);
        Settlement o = m.get(out ? c.to : c.from);
        t.putBoolean("out", out);
        t.putString("other", o == null ? "?" : o.name);
        t.putString("status", c.status.label);
        t.putString("state", c.status.name());
        t.putDouble("progress", c.status == Status.LOADING ? Math.min(1, (now - c.since) / (double) Caravans.LOAD_TICKS) : c.progress);
        t.putString("cargo", summary(c.cargo));
        t.putInt("load", c.load());
        t.putInt("value", (int) Math.round(c.value));
        t.putInt("guards", c.guards);
        t.putBoolean("walking", c.physical);
        t.putString("note", c.note);
        t.putInt("ago", (int) Math.max(0, (now - c.since) / 1200));
        return t;
    }

    /** Trade page actions: open or close a route from this town, ban or allow the export of a good. */
    @Nullable
    static String handle(ServerPlayer player, SettlementManager m, Settlement s, String action, CompoundTag d) {
        switch (action) {
            case "tradeRoute" -> {
                Route r = d.hasUUID("partner") ? m.trade.route(s.id, d.getUUID("partner")) : null;
                if (r == null) return "No such trade route";
                Settlement o = m.get(r.other(s.id));
                if (d.getBoolean("open")) r.closedBy.remove(s.id); else r.closedBy.add(s.id);
                String msg = s.name + (d.getBoolean("open") ? " reopened" : " closed") + " its trade route with " + (o == null ? "a neighbour" : o.name) + ".";
                s.log(msg);
                if (o != null) o.log(msg);
                routes(player.server, m, player.server.overworld().getGameTime());
                return null;
            }
            case "tradeBan" -> {
                Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(d.getString("item")));
                if (it == Items.AIR) return "Unknown good";
                if (d.getBoolean("ban")) m.trade.ledger(s.id).bans.add(it); else m.trade.ledger(s.id).bans.remove(it);
                return null;
            }
            default -> { return "Unknown action"; }
        }
    }

    /** Where caravans load and unload: the market place round the hall, on the side facing the other town. */
    static BlockPos market(Settlement s, Settlement toward) {
        double dx = toward.center.getX() - s.center.getX(), dz = toward.center.getZ() - s.center.getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        double off = Math.min(15, len / 4);
        return new BlockPos((int) Math.round(s.center.getX() + dx / len * off), s.center.getY(), (int) Math.round(s.center.getZ() + dz / len * off));
    }

    @Nullable
    static ServerLevel level(MinecraftServer server, Settlement s) { return server.getLevel(s.dim); }

    public Trade() {}
}
