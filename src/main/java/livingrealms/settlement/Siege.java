package livingrealms.settlement;

import livingrealms.building.Blueprint;
import livingrealms.building.Procedural;
import livingrealms.military.BattleAI;
import livingrealms.military.RecruitsBridge;
import livingrealms.settlement.plan.Walls;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A raid on a walled town as a siege (docs/sieges.md). The gates of the outermost standing wall line shut with iron-bar
 * portcullises when the raid starts; the attackers march on the weakest point of that line (an unfinished gap, the nearest
 * gate, or a wall section), batter or sap it open over time with a crew, then storm in and must hold the centre as before.
 * Defenders' bowmen man the wall walks and towers on the threatened side, the melee hold the inside of the breach, towers
 * of larger towns loose volleys, a blockade eats the town's food and a starved town may surrender. Afterwards the broken
 * gate or wall is queued for the town's builders to repair. Unwalled towns go straight to the assault, as raids always did.
 */
public final class Siege {
    public enum Phase {
        APPROACH("Approach"), BREACH("Breach"), ASSAULT("Assault"), HOLD("Hold");
        public final String label;
        Phase(String label) { this.label = label; }
        public boolean before(Phase o) { return ordinal() < o.ordinal(); }
    }

    /** Base seconds a three-man crew needs at a gatehouse portcullis or a wall section, before wall strength. */
    public static final int GATE_SECONDS = 40, WALL_SECONDS = 70;
    /** A walled town's raid lasts this long before the attackers give up (an open town's, Raids.MAX_TICKS). */
    public static final int MAX_TICKS = 18000;
    /** Ticks of starvation under blockade before the town surrenders; how often the blockade eats; how often towers shoot. */
    public static final int SURRENDER_TICKS = 1200, DRAIN_EVERY = 400, VOLLEY_EVERY = 60;
    public static final int CREW = 3, CREW_REACH = 6, POSTS = 12;

    public Phase phase = Phase.ASSAULT;
    public boolean walled, breached, surrendered, hungry;
    /** Wall line under siege; plot being breached (-1: an unfinished gap); whether it is a gatehouse. */
    public int line = -1, plot = -1;
    public boolean gate;
    /** Where the breach is made, for messages ("east gate", "gap in the north wall"). */
    public String where = "";
    /** The column on the wall line being breached; y is the wall top (what siege engines aim at). */
    public BlockPos at = BlockPos.ZERO;
    public int progress, needed;
    /** Portcullis blocks placed in the gate passages, and what stood there before. */
    public final Map<Long, BlockState> closed = new LinkedHashMap<>();
    public final Set<Integer> shut = new LinkedHashSet<>(), damaged = new LinkedHashSet<>();
    public int blockade, starved, volleys, drained;
    /** NPC towns keep no food stock: a notional store of food, in nutrition, the blockade eats. -1 for player towns. */
    public double provisions = -1;
    /** Soldiers at the ram this second, and defenders on the walls (status only). */
    transient final Set<UUID> crew = new HashSet<>();
    transient int posted, working;

    // ------------------------------------------------------------------ save

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("phase", phase.name());
        t.putBoolean("walled", walled); t.putBoolean("breached", breached); t.putBoolean("surrendered", surrendered); t.putBoolean("hungry", hungry);
        t.putInt("line", line); t.putInt("plot", plot); t.putBoolean("gate", gate); t.putString("where", where);
        t.putLong("at", at.asLong()); t.putInt("progress", progress); t.putInt("needed", needed);
        ListTag bars = new ListTag();
        closed.forEach((p, st) -> { CompoundTag b = new CompoundTag(); b.putLong("p", p); b.put("s", NbtUtils.writeBlockState(st)); bars.add(b); });
        t.put("closed", bars);
        t.putIntArray("shut", shut.stream().mapToInt(Integer::intValue).toArray());
        t.putIntArray("damaged", damaged.stream().mapToInt(Integer::intValue).toArray());
        t.putInt("blockade", blockade); t.putInt("starved", starved); t.putInt("volleys", volleys); t.putInt("drained", drained);
        t.putDouble("provisions", provisions);
        return t;
    }

    /** A raid saved before sieges existed has no tag: it carries on as an open-field assault. */
    public static Siege load(@Nullable CompoundTag t) {
        Siege s = new Siege();
        if (t == null || t.isEmpty()) return s;
        try { s.phase = Phase.valueOf(t.getString("phase")); } catch (IllegalArgumentException e) { s.phase = Phase.ASSAULT; }
        s.walled = t.getBoolean("walled"); s.breached = t.getBoolean("breached"); s.surrendered = t.getBoolean("surrendered"); s.hungry = t.getBoolean("hungry");
        s.line = t.contains("line") ? t.getInt("line") : -1; s.plot = t.contains("plot") ? t.getInt("plot") : -1;
        s.gate = t.getBoolean("gate"); s.where = t.getString("where");
        s.at = BlockPos.of(t.getLong("at")); s.progress = Math.max(0, t.getInt("progress")); s.needed = Math.max(0, t.getInt("needed"));
        for (Tag b : t.getList("closed", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) b;
            s.closed.put(c.getLong("p"), NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), c.getCompound("s")));
        }
        for (int i : t.getIntArray("shut")) s.shut.add(i);
        for (int i : t.getIntArray("damaged")) s.damaged.add(i);
        s.blockade = t.getInt("blockade"); s.starved = t.getInt("starved"); s.volleys = t.getInt("volleys"); s.drained = t.getInt("drained");
        s.provisions = t.contains("provisions") ? t.getDouble("provisions") : -1;
        if (!s.walled) s.phase = s.phase == Phase.HOLD ? Phase.HOLD : Phase.ASSAULT;
        return s;
    }

    // ------------------------------------------------------------------ start: is it a siege, and where to break in

    /** A wall piece that is more than a marker (water and cliffs are recorded as pieces with nothing built). */
    static boolean real(Plot p) { Blueprint b = p.blueprint(); return b != null && b.size() > 1; }

    /** True when the town has a standing gatehouse or wall section: raids on it are sieges. */
    public static boolean walled(Settlement s) {
        for (Plot p : s.plots) if (Walls.isWallPiece(p) && p.standing() && !p.typeId.equals(Walls.TOWER) && real(p)) return true;
        return false;
    }

    /** NPC kingdoms weigh a walled town's garrison more heavily when choosing whom to raid. */
    public static double wallFactor(Settlement s) { return walled(s) ? 1.3 : 1; }

    @Nullable
    static int[] keyOf(Plot p) {
        if (!Walls.isWallPiece(p)) return null;
        String[] k = p.blueprintKey.split(":");
        if (k.length != 4) return null;
        try { return new int[]{Integer.parseInt(k[2]), Integer.parseInt(k[3])}; } catch (NumberFormatException e) { return null; }
    }

    @Nullable
    static Walls.Line lineOf(Settlement s, int index) {
        if (index < 0 || s.townPlan == null && s.plots.stream().noneMatch(Walls::isWallPiece)) return null;
        for (Walls.Line l : s.plan().wallLines()) if (l.index() == index) return l;
        return null;
    }

    @Nullable
    static Walls.Piece pieceOf(Walls.Line l, Plot p) {
        int[] k = keyOf(p);
        if (k == null || k[0] != l.index()) return null;
        List<Walls.Piece> all = Walls.pieces(l);
        return k[1] >= 0 && k[1] < all.size() ? all.get(k[1]) : null;
    }

    /** Sets up the siege of a raid that is starting: an open-field assault for an unwalled town. */
    static Siege begin(@Nullable ServerLevel level, Settlement source, Settlement target) {
        Siege sg = new Siege();
        if (!walled(target)) return sg;
        // the outermost line with a standing gatehouse or wall section is the one the attackers meet
        Walls.Line l = null;
        for (Walls.Line c : target.plan().wallLines()) {
            boolean stands = false;
            for (Plot p : target.plots) {
                int[] k = keyOf(p);
                if (k != null && k[0] == c.index() && p.standing() && !p.typeId.equals(Walls.TOWER) && real(p)) stands = true;
            }
            if (stands && (l == null || c.a() > l.a())) l = c;
        }
        if (l == null) return sg;
        sg.walled = true;
        sg.line = l.index();
        sg.phase = Phase.APPROACH;
        sg.choose(level, target, l, source.center);
        if (target.isAi()) sg.provisions = dayFood(target) * (1 + target.tier.ordinal());
        if (level != null) sg.closeGates(level, target);
        return sg;
    }

    /** How long a crew takes at this piece (x1 = a plain village wall of stone bricks): masonry, rank, capital, towers, line. */
    static double strength(Settlement s, Walls.Line l, @Nullable Plot p, int x, int z) {
        double m = Math.max(.6, Math.min(2.5, Procedural.masonry(s.style).getBlock().defaultDestroyTime() / 1.5));
        double tier = 1 + .15 * Math.max(0, s.tier.ordinal() - Tier.VILLAGE.ordinal());
        double seat = s.capital ? 1.4 : 1;
        int towers = 0;
        for (Plot o : s.plots) if (o != p && o.standing() && (o.typeId.equals(Walls.TOWER) || o.typeId.equals(Walls.GATE)) && real(o)) {
            BlockPos c = o.center();
            if ((c.getX() - x) * (c.getX() - x) + (c.getZ() - z) * (c.getZ() - z) <= 24 * 24) towers++;
        }
        return m * tier * seat * (1 + .1 * towers) * (l.index() > 0 ? 1.25 : 1);
    }

    /** The weak point: least of half the distance from where the attackers arrive plus the seconds it takes to break. */
    void choose(@Nullable ServerLevel level, Settlement s, Walls.Line l, BlockPos from) {
        int cx = s.center.getX(), cz = s.center.getZ();
        Vec3 dir = new Vec3(from.getX() - cx, 0, from.getZ() - cz);
        dir = dir.lengthSqr() < 1 ? new Vec3(1, 0, 0) : dir.normalize();
        double ax = cx + dir.x * l.a(), az = cz + dir.z * l.a();
        Map<String, Plot> byKey = new HashMap<>();
        for (Plot p : s.plots) if (Walls.isWallPiece(p)) byKey.put(p.blueprintKey, p);
        List<Walls.Edge> edges = Walls.edges(l);
        double best = Double.MAX_VALUE;
        for (Walls.Piece pc : Walls.pieces(l)) {
            Plot p = byKey.get(pc.key(s));
            if (p != null && p.standing() && !real(p)) continue; // deep water or a cliff: no way in there
            boolean gap = p == null || !p.standing();
            if (!gap && pc.kind() == Walls.Kind.TOWER) continue;  // towers are not attacked, only the stretches between
            Walls.Edge e = edges.get(pc.edge());
            int x = cx + e.x(pc.tc()), z = cz + e.z(pc.tc());
            double secs = gap ? 0 : (pc.kind() == Walls.Kind.GATE ? GATE_SECONDS : WALL_SECONDS) * strength(s, l, p, x, z);
            double score = Math.sqrt((x - ax) * (x - ax) + (z - az) * (z - az)) / 2 + secs;
            if (score >= best) continue;
            best = score;
            plot = gap ? -1 : p.id;
            gate = !gap && pc.kind() == Walls.Kind.GATE;
            breached = gap;
            needed = (int) Math.round(secs * 20);
            BlockPos col = new BlockPos(x, s.center.getY(), z);
            // a wall that bowed round a building: break in where it actually stands
            if (!gap && pc.kind() == Walls.Kind.WALL && p.columns() != null) col = nearest(p.columns(), x, z, s.center.getY());
            at = col;
            String side = Walls.side(s, col);
            where = gap ? "gap in the " + side + " wall" : gate ? side + " gate" : side + " wall";
        }
        if (level != null && level.hasChunkAt(at)) at = at.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()));
    }

    static BlockPos nearest(Set<Long> cols, int x, int z, int y) {
        long best = 0;
        double bd = Double.MAX_VALUE;
        for (long c : cols) {
            double d = Math.pow(BlockPos.getX(c) - x, 2) + Math.pow(BlockPos.getZ(c) - z, 2);
            if (d < bd) { bd = d; best = c; }
        }
        return bd == Double.MAX_VALUE ? new BlockPos(x, y, z) : new BlockPos(BlockPos.getX(best), y, BlockPos.getZ(best));
    }

    // ------------------------------------------------------------------ places round the breach

    Vec3 out(Settlement s) {
        Vec3 d = new Vec3(at.getX() - s.center.getX(), 0, at.getZ() - s.center.getZ());
        return d.lengthSqr() < 1 ? new Vec3(1, 0, 0) : d.normalize();
    }

    BlockPos off(Settlement s, double blocks) {
        Vec3 o = out(s);
        return new BlockPos(at.getX() + (int) Math.round(o.x * blocks), s.center.getY(), at.getZ() + (int) Math.round(o.z * blocks));
    }

    /** Where the ram crew works, where the defenders hold inside, and where the attackers form up. */
    public BlockPos outside(Settlement s) { return off(s, 4); }
    public BlockPos inside(Settlement s) { return off(s, -5); }
    public BlockPos staging(Settlement s) { return off(s, 14); }

    /** Where siege engines aim: the stretch being breached until it is open, then the centre. */
    public BlockPos aim(Settlement s) { return bombarding() ? at : s.center; }
    public boolean bombarding() { return walled && !breached && phase.before(Phase.ASSAULT); }

    /** Blocks round a siege engine's hit that our own soldiers must keep clear of. */
    public static final int SPLASH = 10;
    /** Priority of a siege repair in the builders' queue (player projects are 0 to 10). */
    public static final int REPAIR_PRIORITY = 15;

    /**
     * Where siege engines aim while the wall stands: the breach, unless some of our own troops (the ram crew) are within
     * splash of it; then the same wall a little to either side, clear of them; null (hold fire) when nowhere is clear.
     */
    @Nullable
    public BlockPos bombardPoint(Settlement s, List<? extends net.minecraft.world.entity.Entity> friends) {
        if (!bombarding()) return null;
        Vec3 o = out(s);
        double ax = -o.z, az = o.x; // along the wall
        for (int d : new int[]{0, 11, -11, 14, -14, 18, -18}) {
            BlockPos p = new BlockPos(at.getX() + (int) Math.round(ax * d), at.getY(), at.getZ() + (int) Math.round(az * d));
            boolean clear = true;
            for (var e : friends) {
                double dx = e.getX() - (p.getX() + .5), dz = e.getZ() - (p.getZ() + .5);
                if (e.isAlive() && dx * dx + dz * dz < SPLASH * SPLASH) { clear = false; break; }
            }
            if (clear) return p;
        }
        return null;
    }

    static boolean insideLine(Walls.Line l, Settlement s, LivingEntity e) {
        return Walls.level(l, e.getBlockX() - s.center.getX(), e.getBlockZ() - s.center.getZ()) < -1;
    }

    // ------------------------------------------------------------------ gates

    /** The passage of a gatehouse: the open blocks across its middle row (the portcullis), and the arch stones over its middle. */
    record Passage(List<BlockPos> bars, List<BlockPos> arch) {}

    static Passage passage(Settlement s, Walls.Line l, Plot g) {
        List<BlockPos> bars = new ArrayList<>(), arch = new ArrayList<>();
        Walls.Piece pc = pieceOf(l, g);
        Blueprint bp = g.blueprint();
        if (pc == null || bp == null) return new Passage(bars, arch);
        Walls.Edge e = Walls.edges(l).get(pc.edge());
        Map<Long, Integer> mid = new LinkedHashMap<>();
        for (int t = pc.tc() - 2; t <= pc.tc() + 2; t++) mid.put(BlockPos.asLong(s.center.getX() + e.x(t), 0, s.center.getZ() + e.z(t)), t - pc.tc());
        Map<Long, TreeMap<Integer, BlockState>> cols = new HashMap<>();
        for (int i = 0; i < bp.size(); i++) {
            BlockPos w = bp.worldPos(i, g.origin, g.rotation);
            long k = BlockPos.asLong(w.getX(), 0, w.getZ());
            if (mid.containsKey(k)) cols.computeIfAbsent(k, x -> new TreeMap<>()).put(w.getY(), bp.state(i));
        }
        for (var en : mid.entrySet()) {
            TreeMap<Integer, BlockState> col = cols.get(en.getKey());
            if (col == null) continue;
            int x = BlockPos.getX(en.getKey()), z = BlockPos.getZ(en.getKey());
            Integer floor = null;
            for (var c : col.entrySet()) {
                if (floor == null) { if (!c.getValue().isAir() && col.containsKey(c.getKey() + 1) && col.get(c.getKey() + 1).isAir()) floor = c.getKey(); continue; }
                if (c.getKey() <= floor) continue;
                if (c.getValue().isAir()) bars.add(new BlockPos(x, c.getKey(), z));
                else { if (Math.abs(en.getValue()) <= 1) arch.add(new BlockPos(x, c.getKey(), z)); break; }
            }
        }
        return new Passage(bars, arch);
    }

    /** Lowers a portcullis in every loaded gatehouse of the line, remembering what was there. */
    void closeGates(ServerLevel level, Settlement s) {
        Walls.Line l = lineOf(s, line);
        if (l == null) return;
        int n = 0;
        for (Plot g : Walls.gates(s)) {
            int[] k = keyOf(g);
            if (k == null || k[0] != line || shut.contains(g.id) || !level.hasChunkAt(g.center())) continue;
            // the gate being battered is lowered too: the ram has to break it
            for (BlockPos p : passage(s, l, g).bars()) {
                BlockState cur = level.getBlockState(p);
                if (!cur.isAir() && !cur.canBeReplaced()) continue;
                closed.put(p.asLong(), cur);
                level.setBlock(p, Blocks.IRON_BARS.defaultBlockState(), 3);
            }
            shut.add(g.id);
            n++;
        }
        if (n > 0) {
            s.log("The gates of " + s.name + " are shut: " + n + (n == 1 ? " portcullis" : " portcullises") + " lowered");
            level.playSound(null, s.center, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1, .6f);
        }
    }

    /** Raises every portcullis that is still standing; gates in unloaded ground are queued for repair, which clears them. */
    void openGates(@Nullable ServerLevel level, Settlement s) {
        boolean opened = false;
        for (var en : closed.entrySet()) {
            BlockPos p = BlockPos.of(en.getKey());
            if (level != null && level.hasChunkAt(p)) {
                if (level.getBlockState(p).is(Blocks.IRON_BARS)) { level.setBlock(p, en.getValue(), 3); opened = true; }
                continue;
            }
            long col = BlockPos.asLong(p.getX(), 0, p.getZ());
            for (Plot g : Walls.gates(s)) if (g.columns() != null && g.columns().contains(col)) damaged.add(g.id);
        }
        closed.clear();
        if (opened) s.log("The gates of " + s.name + " are opened again");
    }

    // ------------------------------------------------------------------ the breach

    /** The blocks that come down when the breach is made: the portcullis and the arch over the passage, or a 3-5 wide gap in the wall. */
    List<BlockPos> breachBlocks(Settlement s) {
        Plot p = s.plot(plot);
        Walls.Line l = lineOf(s, line);
        if (p == null || l == null) return List.of();
        if (gate) {
            Passage ps = passage(s, l, p);
            List<BlockPos> out = new ArrayList<>(ps.bars());
            out.addAll(ps.arch());
            return out;
        }
        Blueprint bp = p.blueprint();
        if (bp == null) return List.of();
        Map<Long, TreeMap<Integer, BlockPos>> cols = new HashMap<>();
        for (int i = 0; i < bp.size(); i++) {
            if (bp.state(i).isAir()) continue;
            BlockPos w = bp.worldPos(i, p.origin, p.rotation);
            if ((w.getX() - at.getX()) * (w.getX() - at.getX()) + (w.getZ() - at.getZ()) * (w.getZ() - at.getZ()) > 5) continue;
            cols.computeIfAbsent(BlockPos.asLong(w.getX(), 0, w.getZ()), x -> new TreeMap<>()).put(w.getY(), w);
        }
        List<BlockPos> out = new ArrayList<>();
        // everything above the bottom course comes down, leaving a floor level with the ground to walk over
        for (TreeMap<Integer, BlockPos> col : cols.values()) col.tailMap(col.firstKey(), false).values().forEach(out::add);
        out.sort(Comparator.comparingInt((BlockPos b) -> b.getY()).reversed());
        return out;
    }

    /** Makes the breach: real block changes, the piece noted for repair, the assault begins. */
    void breach(ServerLevel level, Settlement s) {
        List<BlockPos> blocks = breachBlocks(s);
        crumble(level, blocks, -1);
        for (BlockPos p : blocks) {
            if (level.getBlockState(p).isAir()) continue;
            level.destroyBlock(p, false);
            closed.remove(p.asLong());
        }
        if (plot >= 0) damaged.add(plot);
        breached = true;
        phase = Phase.ASSAULT;
        level.playSound(null, at, gate ? SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR : SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 2, .7f);
    }

    /** Cracks on the blocks being broken, so the breach shows coming; -1 clears them. */
    static void crumble(ServerLevel level, List<BlockPos> blocks, int stage) {
        for (int i = 0; i < Math.min(12, blocks.size()); i++) level.destroyBlockProgress(0x5E6E0000 + i + blocks.get(i).hashCode() % 997 * 16, blocks.get(i), stage);
    }

    // ------------------------------------------------------------------ the defenders' places on the walls

    /** Wall walks and tower tops on the threatened side, nearest the breach first. */
    List<BlockPos> posts(ServerLevel level, Settlement s, Walls.Line l) {
        List<Plot> near = new ArrayList<>();
        for (Plot p : s.plots) {
            int[] k = keyOf(p);
            if (k == null || k[0] != l.index() || !p.standing() || !real(p) || !level.hasChunkAt(p.center())) continue;
            if (p.center().distSqr(at.atY(p.center().getY())) <= 36 * 36) near.add(p);
        }
        near.sort(Comparator.comparingDouble(p -> p.center().distSqr(at.atY(p.center().getY()))));
        List<BlockPos> out = new ArrayList<>();
        for (Plot p : near) {
            if (out.size() >= POSTS) break;
            if (!p.typeId.equals(Walls.WALL)) {
                BlockPos c = p.center();
                out.add(new BlockPos(c.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()), c.getZ()));
                continue;
            }
            // inner columns of the walk (the town side of the parapet), at least 4 apart and off the breach itself
            Set<Long> cols = p.columns();
            if (cols == null) continue;
            List<long[]> inner = new ArrayList<>();
            for (long c : cols) {
                int x = BlockPos.getX(c), z = BlockPos.getZ(c);
                int ix = -Integer.signum(x - s.center.getX()), iz = -Integer.signum(z - s.center.getZ());
                boolean edge = !cols.contains(BlockPos.asLong(x + (Math.abs(x - s.center.getX()) >= Math.abs(z - s.center.getZ()) ? ix : 0), 0,
                        z + (Math.abs(x - s.center.getX()) >= Math.abs(z - s.center.getZ()) ? 0 : iz)));
                double d = (x - at.getX()) * (double) (x - at.getX()) + (z - at.getZ()) * (double) (z - at.getZ());
                if (edge && d >= 16) inner.add(new long[]{x, z, (long) d});
            }
            inner.sort(Comparator.comparingLong(a -> a[2]));
            int taken = 0;
            for (long[] c : inner) {
                if (taken >= 3 || out.size() >= POSTS) break;
                BlockPos q = new BlockPos((int) c[0], 0, (int) c[1]);
                if (out.stream().anyMatch(o -> Math.abs(o.getX() - q.getX()) + Math.abs(o.getZ() - q.getZ()) < 4)) continue;
                out.add(q.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, q.getX(), q.getZ())));
                taken++;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ one second of the siege

    /** Advances the phase and returns {attack, defence} orders for a walled town. Raids applies them. */
    BattleAI.Plan[] plan(ServerLevel level, Raids.Raid raid, Settlement source, Settlement target, List<LivingEntity> attackers,
                         List<LivingEntity> defenders, List<LivingEntity> holding, @Nullable LivingEntity ruler, Vec3 front, double reach) {
        MinecraftServer server = level.getServer();
        Walls.Line l = lineOf(target, line);
        if (l == null) { walled = false; phase = Phase.ASSAULT; return null; }
        if (phase.before(Phase.ASSAULT)) {
            closeGates(level, target);
            BlockPos out = outside(target);
            int there = 0;
            for (LivingEntity a : attackers) if (horizontal(a, out) <= 16) there++;
            if (attackers.stream().anyMatch(a -> insideLine(l, target, a))) {
                phase = Phase.ASSAULT;
                breached = true;
                Raids.announce(server, target, "The attackers are inside the walls of " + target.name + "!");
            } else if (phase == Phase.APPROACH && there * 2 >= Math.max(1, attackers.size())) {
                phase = breached ? Phase.ASSAULT : Phase.BREACH;
                Raids.announce(server, target, breached ? "The attackers have found the " + where + " of " + target.name + " and pour through!"
                        : gate ? "A battering ram is brought up against the " + where + " of " + target.name + "!"
                        : "Sappers set to work under the " + where + " of " + target.name + "!");
            }
        }
        BlockPos home = BattleAI.toward(target.center, source.center, reach);
        BlockPos in = inside(target), outPos = outside(target);
        boolean outsideWar = phase.before(Phase.ASSAULT);
        // the attackers
        BattleAI.Plan atk;
        crew.clear();
        if (outsideWar) {
            List<LivingEntity> reachable = defenders.stream().filter(d -> !insideLine(l, target, d)).toList();
            atk = BattleAI.plan(new BattleAI.Side(attackers, reachable, phase == Phase.APPROACH ? staging(target) : outPos, home, false, null));
            Map<LivingEntity, BattleAI.Order> orders = new LinkedHashMap<>(atk.orders());
            if (phase == Phase.BREACH && !breached) {
                List<LivingEntity> melee = new ArrayList<>(attackers.stream().filter(a -> !RecruitsBridge.ranged(a)
                        && orders.get(a) != null && orders.get(a).stance() != BattleAI.Stance.FALL_BACK).toList());
                melee.sort(Comparator.comparingDouble(a -> horizontal(a, outPos)));
                for (LivingEntity c : melee.subList(0, Math.min(CREW, melee.size()))) {
                    crew.add(c.getUUID());
                    orders.put(c, new BattleAI.Order(null, outPos, BattleAI.Stance.ADVANCE));
                }
            }
            // the attackers' archers shoot at the walls
            for (LivingEntity a : attackers) {
                BattleAI.Order o = orders.get(a);
                if (o == null || o.target() != null || o.stance() == BattleAI.Stance.FALL_BACK || !RecruitsBridge.ranged(a)) continue;
                LivingEntity foe = nearest(defenders, a.position(), BattleAI.RANGED_REACH + 4);
                if (foe != null) orders.put(a, new BattleAI.Order(foe, BattleAI.standoff(a, foe, home), BattleAI.Stance.SKIRMISH));
            }
            atk = new BattleAI.Plan(orders, atk.fallingBack(), false);
        } else {
            atk = BattleAI.plan(new BattleAI.Side(attackers, defenders, target.center, home, false, null));
            Map<LivingEntity, BattleAI.Order> orders = new LinkedHashMap<>(atk.orders());
            // those still outside go in through the breach
            for (LivingEntity a : attackers) {
                BattleAI.Order o = orders.get(a);
                if (o == null || o.stance() == BattleAI.Stance.FALL_BACK || insideLine(l, target, a)) continue;
                if (o.target() == null || insideLine(l, target, o.target())) orders.put(a, new BattleAI.Order(o.target(), in, o.stance()));
            }
            atk = new BattleAI.Plan(orders, atk.fallingBack(), false);
        }
        // the defenders: bowmen on the walls, the rest behind the gate or breach while the enemy is outside
        long outsiders = attackers.stream().filter(a -> !insideLine(l, target, a)).count();
        boolean onWalls = outsideWar || outsiders * 2 > attackers.size();
        List<LivingEntity> foes = outsideWar ? attackers.stream().filter(a -> insideLine(l, target, a) || horizontal(a, in) <= 6).toList() : attackers;
        BlockPos stand = onWalls ? in : target.center;
        BattleAI.Plan def = BattleAI.plan(new BattleAI.Side(holding, foes, stand, BattleAI.rally(stand, front, 8), true, ruler));
        posted = 0;
        if (onWalls) {
            Map<LivingEntity, BattleAI.Order> orders = new LinkedHashMap<>(def.orders());
            List<BlockPos> posts = new ArrayList<>(posts(level, target, l));
            for (LivingEntity d : holding) {
                BattleAI.Order o = orders.get(d);
                if (posts.isEmpty() || o == null || o.stance() == BattleAI.Stance.FALL_BACK || !RecruitsBridge.ranged(d)) continue;
                BlockPos post = posts.stream().min(Comparator.comparingDouble(p -> horizontal(d, p))).orElseThrow();
                posts.remove(post);
                // up the tower ladder: the last few blocks of a climb Recruits' pathfinding does not plan
                if (horizontal(d, post) <= 6 && d.getY() < post.getY() - 1.5) d.teleportTo(post.getX() + .5, post.getY(), post.getZ() + .5);
                LivingEntity foe = nearest(attackers, Vec3.atCenterOf(post), BattleAI.RANGED_REACH + 4);
                orders.put(d, new BattleAI.Order(foe, post, BattleAI.Stance.SKIRMISH));
                posted++;
            }
            def = new BattleAI.Plan(orders, def.fallingBack(), def.regrouping());
        }
        return new BattleAI.Plan[]{atk, def};
    }

    /** After the orders: the ram works, the towers shoot, the blockade bites. Returns true when the town surrenders. */
    boolean after(ServerLevel level, Raids.Raid raid, Settlement source, Settlement target, List<LivingEntity> attackers,
                  List<LivingEntity> defenders, boolean rulerHome, int engines) {
        MinecraftServer server = level.getServer();
        Walls.Line l = lineOf(target, line);
        if (l == null) return false;
        if (phase == Phase.BREACH && !breached) {
            BlockPos out = outside(target);
            working = 0;
            for (LivingEntity a : attackers) if (crew.contains(a.getUUID()) && horizontal(a, out) <= CREW_REACH) working++;
            double rate = working == 0 ? 0 : (.5 + .5 * Math.min(CREW, working));
            rate += engines; // siege engines pound the same stretch
            progress += (int) Math.round(20 * rate);
            if (rate > 0) {
                level.playSound(null, at, gate ? SoundEvents.ZOMBIE_ATTACK_IRON_DOOR : SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1, .7f);
                crumble(level, breachBlocks(target), Math.min(9, progress * 10 / Math.max(1, needed)));
            }
            if (progress >= needed) {
                breach(level, target);
                String what = gate ? "The ram has broken the " + where + " of " + target.name + "!"
                        : "The sappers have brought down a stretch of the " + where + " of " + target.name + "!";
                Raids.announce(server, target, what);
                Raids.announce(server, source, what);
            }
        }
        // towers on the threatened side shoot at the attackers outside (Town rank and above)
        if (phase != Phase.HOLD && target.tier.ordinal() >= Tier.TOWN.ordinal() && raid.elapsed % VOLLEY_EVERY == 0) volley(level, target, l, attackers);
        // blockade: attackers camped round a closed town eat into its stores; starving, it may give up
        if (phase.before(Phase.ASSAULT) && surrounded(l, target, attackers)) {
            blockade += 20;
            if (blockade % DRAIN_EVERY == 0) {
                boolean was = hungry;
                drain(target);
                if (hungry && !was) Raids.announce(server, target, "The stores of " + target.name + " are empty: the besieged go hungry");
            }
            if (hungry) {
                starved += 20;
                target.starving = true;
                if (starved % 100 == 0) for (LivingEntity d : defenders) {
                    d.removeEffect(MobEffects.REGENERATION);
                    d.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 140, 0, true, true));
                    if (RecruitsBridge.isRecruit(d)) { RecruitsBridge.addMorale(d, -2); livingrealms.compat.Compat.morale(d, -2); }
                }
                if (starved >= SURRENDER_TICKS && !rulerHome) { surrendered = true; return true; }
            }
        }
        return false;
    }

    static boolean surrounded(Walls.Line l, Settlement s, List<LivingEntity> attackers) {
        if (attackers.size() < 3) return false;
        int n = 0;
        for (LivingEntity a : attackers) {
            int lv = Walls.level(l, a.getBlockX() - s.center.getX(), a.getBlockZ() - s.center.getZ());
            if (lv > 1 && lv <= 48) n++;
        }
        return n * 3 >= attackers.size() * 2;
    }

    /** A day's food for the town and its garrison, in nutrition (as Simulation counts it). */
    static double dayFood(Settlement s) { return s.population * 4 + s.soldiers.size() * 5; }

    /** One bite of the blockade: a sixth of a day's food, so a closed town eats a day's stores every two minutes. */
    void drain(Settlement s) {
        double need = dayFood(s) / 6;
        if (provisions >= 0) {
            provisions = Math.max(0, provisions - need);
            drained += (int) Math.round(need);
            hungry = provisions <= 0;
            return;
        }
        List<Item> foods = new ArrayList<>();
        for (var e : s.stock.view().object2IntEntrySet()) {
            FoodProperties fp = e.getKey().getFoodProperties();
            if (fp != null && fp.getNutrition() > 0 && fp.getEffects().stream().allMatch(p -> p.getFirst().getEffect().isBeneficial())) foods.add(e.getKey());
        }
        foods.sort(Comparator.comparingInt(i -> -s.stock.count(i)));
        for (Item it : foods) {
            if (need <= 0) break;
            int nut = it.getFoodProperties().getNutrition();
            int n = Math.min(s.stock.count(it), (int) Math.ceil(need / nut));
            s.stock.take(it, n);
            need -= n * nut;
            drained += n * nut;
        }
        hungry = need > .5;
        s.dirty = true;
    }

    /** Two arrows from each of up to four towers near the breach, at the nearest attacker outside the walls. */
    void volley(ServerLevel level, Settlement s, Walls.Line l, List<LivingEntity> attackers) {
        List<Plot> towers = new ArrayList<>();
        for (Plot p : s.plots) {
            int[] k = keyOf(p);
            if (k == null || k[0] != l.index() || !p.standing() || p.typeId.equals(Walls.WALL) || !real(p) || !level.hasChunkAt(p.center())) continue;
            if (p.center().distSqr(at.atY(p.center().getY())) <= 40 * 40) towers.add(p);
        }
        towers.sort(Comparator.comparingDouble(p -> p.center().distSqr(at.atY(p.center().getY()))));
        for (Plot t : towers.subList(0, Math.min(4, towers.size()))) {
            BlockPos c = t.center();
            Vec3 top = new Vec3(c.getX() + .5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()) + 1.2, c.getZ() + .5);
            LivingEntity foe = null;
            double bd = 40 * 40;
            for (LivingEntity a : attackers) {
                if (insideLine(l, s, a)) continue;
                double d = a.distanceToSqr(top);
                if (d < bd) { bd = d; foe = a; }
            }
            if (foe == null) continue;
            for (int i = 0; i < 2; i++) {
                Arrow arrow = new Arrow(level, top.x, top.y, top.z);
                double dx = foe.getX() - top.x, dz = foe.getZ() - top.z, dy = foe.getY(.5) - top.y;
                arrow.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * .12, dz, 2.2f, 5f);
                arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                arrow.setBaseDamage(3);
                level.addFreshEntity(arrow);
            }
            volleys++;
        }
        if (!towers.isEmpty()) level.playSound(null, at, SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.5f, .8f);
    }

    // ------------------------------------------------------------------ the end

    /** When the raid ends, however it ends: gates open again, and the broken gate or wall is queued for repair. */
    static void end(MinecraftServer server, Raids.Raid raid, @Nullable Settlement target) {
        Siege sg = raid.siege;
        if (!sg.walled || target == null) return;
        ServerLevel level = server.getLevel(target.dim);
        if (level != null && !sg.breached && sg.phase == Phase.BREACH) crumble(level, sg.breachBlocks(target), -1);
        sg.openGates(level, target);
        List<String> names = new ArrayList<>();
        for (int id : sg.damaged) {
            Plot p = target.plot(id);
            if (p == null || p.state != Plot.State.BUILT) continue;
            // the town's own work, first in the builders' queue: it neither waits for nor holds up the ruler's projects
            p.state = Plot.State.REPAIRING;
            p.automatic = true;
            p.priority = REPAIR_PRIORITY;
            p.placed = 0;
            p.paid = 0;
            names.add(Walls.side(target, p.center()) + (p.typeId.equals(Walls.GATE) ? " gate" : p.typeId.equals(Walls.TOWER) ? " tower" : " wall"));
        }
        if (!names.isEmpty()) {
            target.log("Masons are set to repair the " + String.join(", ", names) + " after the siege");
            target.dirty = true;
        }
    }

    // ------------------------------------------------------------------ for Raids' status line and the City Manager

    String status(int attackers, String back, int defenders) {
        String what = switch (phase) {
            case APPROACH -> "marching on the " + where;
            case BREACH -> (gate ? "ram at the " : "sappers at the ") + where + " " + Math.min(100, progress * 100 / Math.max(1, needed)) + "%"
                    + (working == 0 ? " (no crew at work)" : "");
            default -> "";
        };
        String supply = hungry ? " · starving" : blockade > 0 ? " · blockaded" : "";
        return phase.label + " · " + what + " · " + attackers + " attackers" + back + "; " + defenders + " defenders"
                + (posted > 0 ? " (" + posted + " on the walls)" : "") + supply;
    }

    public float progressFraction() { return breached ? 1 : needed <= 0 ? 0 : Math.min(1f, progress / (float) needed); }

    static double horizontal(LivingEntity e, BlockPos p) {
        double dx = e.getX() - (p.getX() + .5), dz = e.getZ() - (p.getZ() + .5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Nullable
    static LivingEntity nearest(List<LivingEntity> es, Vec3 from, double reach) {
        LivingEntity best = null;
        double bd = reach * reach;
        for (LivingEntity e : es) {
            if (!e.isAlive()) continue;
            double d = e.distanceToSqr(from);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }
}
