package livingrealms.settlement;

import livingrealms.military.UnitType;
import livingrealms.research.ResearchTree;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import livingrealms.military.RecruitsBridge;
import livingrealms.smithing.Piece;

import javax.annotation.Nullable;
import java.util.*;

/** Player towns keep going on their own: research, rank, garrison, re-equipping, and a report for the returning ruler. */
public final class Autonomy {
    public static final long AWAY_MS = 5 * 60 * 1000L, AWAY_TICKS = 24000;
    /** Auto training never spends the treasury below this many emeralds, nor below a quarter of the next rank's fee. */
    public static final int TRAIN_FLOOR = 8;
    /** Auto training leaves at least this many townsfolk. */
    public static final int TRAIN_MIN_POP = 6;

    /** What happened since the owner was last seen at the town. */
    public static final class Away {
        public long seenMs, seenTick;
        public final Map<String, Integer> built = new LinkedHashMap<>(), trained = new LinkedHashMap<>();
        public final List<String> researched = new ArrayList<>(), ranks = new ArrayList<>(), raids = new ArrayList<>();

        public void built(String name) { built.merge(name, 1, Integer::sum); }
        public void trained(UnitType u) { trained.merge(u.name(), 1, Integer::sum); }
        public void note(List<String> l, String s) { if (l.size() < 8 && !l.contains(s)) l.add(s); }
        public boolean empty() { return built.isEmpty() && trained.isEmpty() && researched.isEmpty() && ranks.isEmpty() && raids.isEmpty(); }
        public void clear() { built.clear(); trained.clear(); researched.clear(); ranks.clear(); raids.clear(); }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("ms", seenMs);
            t.putLong("tick", seenTick);
            CompoundTag b = new CompoundTag(), tr = new CompoundTag();
            built.forEach(b::putInt);
            trained.forEach(tr::putInt);
            t.put("built", b);
            t.put("trained", tr);
            t.put("researched", strings(researched));
            t.put("ranks", strings(ranks));
            t.put("raids", strings(raids));
            return t;
        }

        public void load(CompoundTag t) {
            seenMs = t.getLong("ms");
            seenTick = t.getLong("tick");
            CompoundTag b = t.getCompound("built"), tr = t.getCompound("trained");
            for (String k : b.getAllKeys()) built.put(k, b.getInt(k));
            for (String k : tr.getAllKeys()) trained.put(k, tr.getInt(k));
            t.getList("researched", Tag.TAG_STRING).forEach(e -> researched.add(e.getAsString()));
            t.getList("ranks", Tag.TAG_STRING).forEach(e -> ranks.add(e.getAsString()));
            t.getList("raids", Tag.TAG_STRING).forEach(e -> raids.add(e.getAsString()));
        }

        static ListTag strings(List<String> l) {
            ListTag out = new ListTag();
            l.forEach(s -> out.add(StringTag.valueOf(s)));
            return out;
        }
    }

    /** One slow step for a player town: runs loaded or not, never touches unloaded terrain. */
    public static void think(ServerLevel level, Settlement s) {
        if (s.isAi()) return;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(s.owner);
        if (owner != null && owner.level() == level && s.contains(owner.blockPosition())) back(owner, s);
        if (underAttack(level, s)) return;
        if (s.autoTier) tier(s);
        if (s.autoResearch) research(s);
        if (s.autoTrain) train(s);
        if (s.autoEquip && level.hasChunkAt(s.center)) reequip(level, s);
    }

    /** Raided towns, and towns with the alarm ringing, make no autonomous moves. */
    public static boolean underAttack(ServerLevel level, Settlement s) {
        if (s.alarmUntil > level.getGameTime()) return true;
        return SettlementManager.get(level.getServer()).raids.stream().anyMatch(r -> r.target.equals(s.id));
    }

    // ------------------------------------------------------------------ rank

    /** Emeralds needed in the treasury before auto advance pays the fee: the fee plus a 25% reserve left over. */
    public static int tierFunds(Tier n) { return (n.emeralds * 4 + 2) / 3; }

    @Nullable
    static String tier(Settlement s) {
        Tier n = s.tier.next();
        if (n == null || !s.tierBlockers().isEmpty()) return "Not ready";
        if (s.stock.count(Items.EMERALD) < tierFunds(n)) return "Keeping a reserve";
        String err = Actions.advance(s);
        if (err == null) s.away.note(s.away.ranks, n.label);
        return err;
    }

    // ------------------------------------------------------------------ research

    static void research(Settlement s) {
        if (!s.activeResearch.isEmpty() || s.d.researchPerDay <= 0) return;
        String id = pickResearch(s);
        if (id != null && Actions.research(s, id) == null) s.researchAuto = true;
    }

    /** Tier gates the town is close to, then economy and construction, then smithing and military; cheapest first. */
    @Nullable
    public static String pickResearch(Settlement s) {
        Set<String> gate = gateChain(s);
        List<ResearchTree.Node> open = new ArrayList<>();
        for (var n : ResearchTree.nodes().values()) {
            if (s.cannotResearch(n.id()) != null || s.researchSkip.contains(n.id())) continue;
            if (n.items().entrySet().stream().anyMatch(e -> s.stock.countIngredient(e.getKey()) < e.getValue())) continue;
            open.add(n);
        }
        open.sort(Comparator.comparingInt((ResearchTree.Node n) -> rank(n, gate)).thenComparingInt(ResearchTree.Node::cost).thenComparing(ResearchTree.Node::id));
        return open.isEmpty() ? null : open.get(0).id();
    }

    static int rank(ResearchTree.Node n, Set<String> gate) {
        if (gate.contains(n.id())) return 0;
        return switch (n.branch()) {
            case "Economy", "Construction", "Scholarship" -> 1;
            case "Smithing", "Military" -> 2;
            default -> 3;
        };
    }

    /** The next rank's research and its prerequisites, once the town has half the people and buildings it needs. */
    static Set<String> gateChain(Settlement s) {
        Set<String> out = new HashSet<>();
        Tier n = s.tier.next();
        if (n == null || n.research == null || s.has(n.research)) return out;
        if (s.population * 2 < n.population || s.d.buildings * 2 < n.buildings) return out;
        Deque<String> todo = new ArrayDeque<>(List.of(n.research));
        while (!todo.isEmpty()) {
            String id = todo.pop();
            var node = ResearchTree.get(id);
            if (node == null || !out.add(id)) continue;
            todo.addAll(node.requires());
        }
        return out;
    }

    // ------------------------------------------------------------------ garrison

    public static int target(Settlement s) {
        int cap = s.d.garrisonCap;
        return s.garrisonTarget < 0 ? cap / 2 : Math.min(cap, s.garrisonTarget);
    }

    static boolean ranged(UnitType u) { return u == UnitType.BOWMAN || u == UnitType.CROSSBOWMAN || u == UnitType.DRAGON_RIDER; }

    /** Queues one recruit per step towards the target, keeping a third of the garrison ranged. */
    @Nullable
    static UnitType train(Settlement s) {
        if (s.starving || s.population < TRAIN_MIN_POP || s.training.size() >= 2) return null;
        int have = s.soldiers.size() + s.training.size();
        if (have >= target(s)) return null;
        int ranged = (int) (s.soldiers.stream().filter(so -> ranged(so.type)).count() + s.training.stream().filter(t -> ranged(t.type)).count());
        boolean wantRanged = ranged * 3 < have + 1;
        Tier next = s.tier.next();
        int floor = Math.max(TRAIN_FLOOR, next == null ? 0 : next.emeralds / 4);
        List<UnitType> order = new ArrayList<>(List.of(UnitType.values()));
        order.remove(UnitType.DRAGON_RIDER);
        order.sort(Comparator.comparingInt((UnitType u) -> ranged(u) == wantRanged ? 0 : 1).thenComparingInt(u -> -u.emeralds));
        for (UnitType u : order) {
            if (s.stock.count(Items.EMERALD) - u.emeralds < floor || !s.has(u.research)) continue;
            CompoundTag d = new CompoundTag();
            d.putString("unit", u.name());
            d.putInt("qty", 1);
            if (Actions.train(s, d) == null) return u;
        }
        return null;
    }

    /** Soldiers at home with an empty slot or few arrows get their kit from the armoury. */
    static void reequip(ServerLevel level, Settlement s) {
        SettlementManager m = SettlementManager.get(level.getServer());
        for (Settlement.Soldier so : s.soldiers) {
            Entity e = level.getEntity(so.entity);
            if (e == null || !e.isAlive() || !s.contains(e.blockPosition()) || Raids.deployed(m, so.entity)) continue;
            boolean lacking = RecruitsBridge.getSlot(e, EquipmentSlot.MAINHAND).isEmpty()
                    || Arrays.stream(Piece.ARMOR).anyMatch(p -> RecruitsBridge.getSlot(e, p.slot).isEmpty())
                    || ranged(so.type) && RecruitsBridge.countInPack(e, Items.ARROW) < 16;
            if (lacking) Simulation.equip(s, e, so.type);
        }
    }

    // ------------------------------------------------------------------ away report

    public static void login(ServerPlayer p) {
        for (Settlement s : SettlementManager.get(p.server).ownedBy(p.getUUID())) back(p, s);
    }

    /** The owner is here (or logged in, or opened the City Manager): report what happened if they were away long enough. */
    public static void back(ServerPlayer p, Settlement s) {
        if (!p.getUUID().equals(s.owner)) return;
        long ms = System.currentTimeMillis(), tick = p.server.overworld().getGameTime();
        Away a = s.away;
        if (a.seenMs > 0 && (ms - a.seenMs > AWAY_MS || tick - a.seenTick > AWAY_TICKS) && !a.empty()) {
            List<String> lines = report(s, ms - a.seenMs, tick - a.seenTick);
            p.sendSystemMessage(Component.literal(String.join("\n", lines)).withStyle(ChatFormatting.GOLD));
            for (int i = lines.size() - 1; i >= 0; i--) s.log(lines.get(i));
        }
        a.clear();
        a.seenMs = ms;
        a.seenTick = tick;
    }

    public static List<String> report(Settlement s, long ms, long ticks) {
        Away a = s.away;
        List<String> out = new ArrayList<>();
        String days = ticks >= AWAY_TICKS ? String.format(Locale.ROOT, "%.1f days", ticks / 24000.0) : (ms / 60000) + " min";
        out.add("While you were away from " + s.name + " (" + days + "):");
        if (!a.built.isEmpty()) out.add("Built: " + counted(a.built, k -> k));
        if (!a.researched.isEmpty()) out.add("Researched: " + String.join(", ", a.researched));
        if (!a.trained.isEmpty()) out.add("Trained: " + counted(a.trained, k -> UnitType.valueOf(k).label));
        if (!a.ranks.isEmpty()) out.add("Rose to " + String.join(", then ", a.ranks));
        if (!a.raids.isEmpty()) out.add("Raids: " + String.join("; ", a.raids));
        return out;
    }

    static String counted(Map<String, Integer> m, java.util.function.Function<String, String> name) {
        List<String> parts = new ArrayList<>();
        m.forEach((k, n) -> parts.add(name.apply(k) + (n > 1 ? " ×" + n : "")));
        return String.join(", ", parts);
    }

    private Autonomy() {}
}
