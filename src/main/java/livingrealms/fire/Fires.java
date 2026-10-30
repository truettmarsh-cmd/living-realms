package livingrealms.fire;

import livingrealms.Config;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.chronicle.Chronicle;
import livingrealms.entity.SettlerEntity;
import livingrealms.settlement.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Town fires (docs/fires.md). Wooden buildings sometimes catch fire; lightning, lava, raiders and dragons start real
 * vanilla fires too. While a town burns, fire is kept inside the town's land, townsfolk form a bucket brigade, and
 * once it is out the damaged buildings are queued for repair and the fire goes into the chronicle.
 */
public final class Fires {
    public static final String WELL = "livingrealms:well", RESEARCH = "fire_watch";
    /** Ticks between looks at a burning town; fire within BAND blocks of the town's edge is put out at once. */
    public static final int SCAN = 10, BAND = 2, CHECK = 1200, WATCH = 40;
    /** Largest area watched around one fire, and the most blocks a firefighter reaches. */
    static final int MAX_SPAN = 56, MAX_HEIGHT = 32;
    static final double REACH_H = 5.5, REACH_V = 9;

    public static final class Fire {
        public final UUID town;
        public final String cause;
        public final long started;
        public BoundingBox zone;
        public int burning, peak, byCrew, byTown, contained, scans, quietScans, repairs;
        public final Set<Integer> touched = new LinkedHashSet<>();
        public final Set<UUID> crew = new LinkedHashSet<>();
        public final List<BlockPos> fires = new ArrayList<>();
        public final List<String> damaged = new ArrayList<>();
        public boolean water, done;
        public String story = "";

        public Fire(UUID town, String cause, long started, BoundingBox zone) { this.town = town; this.cause = cause; this.started = started; this.zone = zone; }
    }

    static final Map<UUID, Fire> ACTIVE = new HashMap<>();
    /** The last finished fire of each town (for the City Manager and the self-test). */
    public static final Map<UUID, Fire> LAST = new HashMap<>();
    static final Map<UUID, Integer> WATCH_INDEX = new HashMap<>();
    record Strike(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim, BlockPos pos, long at) {}
    static final List<Strike> STRIKES = new ArrayList<>();
    static final Map<String, Float> WOOD = new HashMap<>();

    public static boolean enabled() { return Config.FIRES.get(); }

    @Nullable public static Fire active(Settlement s) { return ACTIVE.get(s.id); }

    // ------------------------------------------------------------------ risk

    static boolean flammable(BlockState st) {
        return !st.isAir() && (st.ignitedByLava() || st.getFlammability(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP) > 0);
    }

    /** Share of a blueprint's blocks that burn: planks, logs, thatch, wool. */
    public static float woodFraction(@Nullable Blueprint b) {
        if (b == null) return 0;
        return WOOD.computeIfAbsent(b.key, k -> {
            int all = 0, wood = 0;
            for (int i = 0; i < b.size(); i++) {
                BlockState st = b.state(i);
                if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                all++;
                if (flammable(st)) wood++;
            }
            return all == 0 ? 0f : wood / (float) all;
        });
    }

    static boolean burns(Plot p) {
        if (!p.standing() || p.blueprintKey == null || livingrealms.settlement.plan.Walls.isWallPiece(p)) return false;
        BuildingType t = BuildingCatalog.get(p.typeId);
        return t != null && t.category != Category.ROAD && t.category != Category.WALL;
    }

    /** One building's risk: what it is made of, what goes on inside, and how close the neighbours are. */
    public static double risk(Settlement s, Plot p) {
        if (!burns(p)) return 0;
        BuildingType t = BuildingCatalog.get(p.typeId);
        double r = 0.08 + 0.92 * woodFraction(p.blueprint());
        r *= switch (t.category) {
            case SMITHY -> 1.5;
            case TAVERN, WORKSHOP -> 1.25;
            case FARM, DECOR -> 0.4;
            default -> 1.0;
        };
        BoundingBox near = p.box().inflatedBy(4);
        int n = 0;
        for (Plot q : s.plots) if (q != p && burns(q) && q.box().intersects(near)) n++;
        return r * (1 + 0.15 * Math.min(n, 6));
    }

    public static boolean hasWell(Settlement s) {
        for (Plot p : s.plots) if (p.standing() && p.typeId.equals(WELL)) return true;
        return false;
    }

    /** Wells halve the risk, the Fire Watch research halves it again. */
    public static double prevention(Settlement s) {
        return (hasWell(s) ? 0.6 : 1) * (s.has(RESEARCH) ? 0.5 : 1);
    }

    public static double townRisk(Settlement s) {
        double r = 0;
        for (Plot p : s.plots) r += risk(s, p);
        return r * prevention(s);
    }

    /** Chance of an outbreak on a given day (raids add their own). */
    public static double dailyChance(Settlement s) {
        return Config.FIRE_CHANCE.get() * Math.min(3, townRisk(s) / 8);
    }

    public static String label(double daily) {
        return daily <= 0 ? "None" : daily < 0.01 ? "Very low" : daily < 0.025 ? "Low" : daily < 0.05 ? "Moderate" : "High";
    }

    // ------------------------------------------------------------------ the server tick

    /** The server whose saved fires are loaded into ACTIVE (FireData); a new server (a restart) loads its own. */
    private static MinecraftServer resumed;

    public static void tick(MinecraftServer server, SettlementManager m, long tick) {
        if (resumed != server) { resumed = server; ACTIVE.clear(); FireData.get(server); }
        if (!enabled()) { ACTIVE.clear(); return; }
        long now = server.overworld().getGameTime();
        STRIKES.removeIf(st -> now - st.at > 400);
        if (tick % SCAN == 0) for (Fire f : List.copyOf(ACTIVE.values())) {
            Settlement s = m.get(f.town);
            ServerLevel level = s == null ? null : server.getLevel(s.dim);
            if (level == null) { ACTIVE.remove(f.town); continue; }
            step(level, s, f);
        }
        for (Settlement s : m.all()) {
            if (s.mirror()) continue;
            int phase = Math.floorMod(s.id.hashCode(), CHECK);
            boolean check = tick % CHECK == phase, watch = tick % WATCH == phase % WATCH;
            if (!check && !watch) continue;
            ServerLevel level = server.getLevel(s.dim);
            if (level == null || !level.hasChunkAt(s.center)) continue;
            if (watch) watch(level, s);
            if (check) outbreak(level, m, s, level.random);
        }
    }

    /** The chance roll: an ordinary day's risk, or raiders with torches. Returns the fire started, if any. */
    @Nullable
    public static Fire outbreak(ServerLevel level, SettlementManager m, Settlement s, RandomSource rand) {
        if (!enabled() || ACTIVE.containsKey(s.id) || !level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) return null;
        boolean raided = m != null && m.raids.stream().anyMatch(r -> r.target.equals(s.id));
        double p = dailyChance(s) * CHECK / (double) Config.DAY_LENGTH.get() + (raided ? Config.FIRE_RAID_CHANCE.get() * CHECK / 1200.0 : 0);
        if (rand.nextDouble() >= p) return null;
        Plot plot = pick(level, s, rand);
        if (plot == null) return null;
        BuildingType t = BuildingCatalog.get(plot.typeId);
        String cause = raided ? (livingrealms.compat.Compat.loaded("siegeweapons") || livingrealms.compat.Compat.loaded("siegemachines")
                ? "the besiegers' fire-pots" : "raiders' torches")
                : t != null && t.category == Category.SMITHY ? "sparks from the forge"
                : new String[]{"an overturned lamp", "a spark from a hearth", "a careless candle", "embers left in a bread oven"}[rand.nextInt(4)];
        return ignite(level, s, plot, cause, rand);
    }

    /** A building weighted by its risk, among those loaded. */
    @Nullable
    static Plot pick(ServerLevel level, Settlement s, RandomSource rand) {
        double total = 0;
        List<Plot> plots = new ArrayList<>();
        for (Plot p : s.plots) if (risk(s, p) > 0 && livingrealms.building.Construction.loaded(level, p)) { plots.add(p); total += risk(s, p); }
        double roll = rand.nextDouble() * total;
        for (Plot p : plots) { roll -= risk(s, p); if (roll <= 0) return p; }
        return plots.isEmpty() ? null : plots.get(plots.size() - 1);
    }

    /** Sets real fire to a building: up to three of its burnable blocks catch. Null if nothing there burns. */
    @Nullable
    public static Fire ignite(ServerLevel level, Settlement s, Plot p, String cause, RandomSource rand) {
        if (!enabled()) return null;
        Blueprint b = p.blueprint();
        if (b == null || !livingrealms.building.Construction.loaded(level, p)) return null;
        List<BlockPos> wood = new ArrayList<>();
        for (int i = 0; i < b.size(); i++) {
            BlockPos pos = b.worldPos(i, p.origin, p.rotation);
            if (flammable(level.getBlockState(pos))) wood.add(pos);
        }
        Collections.shuffle(wood, new Random(rand.nextLong()));
        int lit = 0;
        for (BlockPos pos : wood) {
            if (lit >= 3) break;
            for (Direction d : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos air = pos.relative(d);
                if (!level.getBlockState(air).isAir() || !inside(s, air)) continue;
                level.setBlock(air, BaseFireBlock.getState(level, air), 11);
                lit++;
                break;
            }
        }
        if (lit == 0) return null;
        Fire f = begin(level, s, p.box(), cause);
        f.touched.add(p.id);
        BuildingType t = BuildingCatalog.get(p.typeId);
        alarm(level, s, "Fire! The " + (t == null ? "building" : t.name.toLowerCase(Locale.ROOT)) + " is burning (" + cause + ")");
        return f;
    }

    /** Starts watching a fire around this box, or widens the one already burning. */
    static Fire begin(ServerLevel level, Settlement s, BoundingBox around, String cause) {
        Fire f = ACTIVE.get(s.id);
        BoundingBox zone = clamp(s, around.inflatedBy(6), around);
        if (f != null) { f.zone = clamp(s, union(f.zone, zone), f.zone); return f; }
        f = new Fire(s.id, cause, level.getGameTime(), zone);
        ACTIVE.put(s.id, f);
        return f;
    }

    static void alarm(ServerLevel level, Settlement s, String msg) {
        s.log(msg);
        level.playSound(null, s.center, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3f, 1.2f);
        if (s.owner != null) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(s.owner);
            if (p != null) p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.RED));
        }
    }

    /** Looks over one building per call for fire the town did not start: lightning, lava, dragons. */
    static void watch(ServerLevel level, Settlement s) {
        if (ACTIVE.containsKey(s.id) || s.plots.isEmpty()) return;
        int i = WATCH_INDEX.merge(s.id, 1, Integer::sum) % s.plots.size();
        Plot p = s.plots.get(i);
        if (!burns(p) || !livingrealms.building.Construction.loaded(level, p)) return;
        List<BlockPos> found = scan(level, p.box().inflatedBy(1), 1);
        if (found.isEmpty()) return;
        Fire f = begin(level, s, p.box(), guessCause(level, found.get(0)));
        f.touched.add(p.id);
        BuildingType t = BuildingCatalog.get(p.typeId);
        alarm(level, s, "Fire! The " + (t == null ? "building" : t.name.toLowerCase(Locale.ROOT)) + " is burning");
    }

    static String guessCause(ServerLevel level, BlockPos at) {
        for (Strike st : STRIKES) if (st.dim == level.dimension() && st.pos.distSqr(at) < 24 * 24) return "lightning";
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-3, -3, -3), at.offset(3, 3, 3)))
            if (level.getFluidState(q).is(FluidTags.LAVA)) return "lava";
        boolean dragon = !level.getEntitiesOfClass(LivingEntity.class, new AABB(at).inflate(40),
                e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().contains("dragon")).isEmpty();
        return dragon ? "dragonfire" : "an unknown spark";
    }

    /** Lightning in a town: watch round the strike (the bolt lights its fire a tick later). */
    public static void lightning(ServerLevel level, SettlementManager m, BlockPos at) {
        if (!enabled()) return;
        STRIKES.add(new Strike(level.dimension(), at, level.getGameTime()));
        Settlement s = m.at(level.dimension(), at);
        if (s == null || s.mirror()) return;
        begin(level, s, new BoundingBox(at).inflatedBy(3), "lightning");
    }

    // ------------------------------------------------------------------ while it burns

    static boolean inside(Settlement s, BlockPos pos) {
        long dx = pos.getX() - s.center.getX(), dz = pos.getZ() - s.center.getZ();
        long r = Math.max(4, s.radius() - BAND);
        return dx * dx + dz * dz <= r * r;
    }

    static BoundingBox union(BoundingBox a, BoundingBox b) {
        return new BoundingBox(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
    }

    /** Keeps a watched area within the town's land (plus the band, so fire there is seen and put out) and a sane size. */
    static BoundingBox clamp(Settlement s, BoundingBox b, BoundingBox core) {
        int r = s.radius() + 2, cx = s.center.getX(), cz = s.center.getZ();
        int mx = (core.minX() + core.maxX()) / 2, mz = (core.minZ() + core.maxZ()) / 2, my = (core.minY() + core.maxY()) / 2;
        int x0 = Math.max(Math.max(b.minX(), cx - r), mx - MAX_SPAN / 2), x1 = Math.min(Math.min(b.maxX(), cx + r), mx + MAX_SPAN / 2);
        int z0 = Math.max(Math.max(b.minZ(), cz - r), mz - MAX_SPAN / 2), z1 = Math.min(Math.min(b.maxZ(), cz + r), mz + MAX_SPAN / 2);
        int y0 = Math.max(b.minY(), my - MAX_HEIGHT / 2), y1 = Math.min(b.maxY(), my + MAX_HEIGHT / 2);
        return new BoundingBox(x0, y0, z0, Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1));
    }

    /** Fire blocks in a box, skipping chunk sections that hold none. */
    static List<BlockPos> scan(ServerLevel level, BoundingBox b, int limit) {
        List<BlockPos> out = new ArrayList<>();
        for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;
            for (int sy = b.minY() >> 4; sy <= b.maxY() >> 4; sy++) {
                int idx = chunk.getSectionIndexFromSectionY(sy);
                if (idx < 0 || idx >= chunk.getSectionsCount()) continue;
                LevelChunkSection sec = chunk.getSection(idx);
                if (sec.hasOnlyAir() || !sec.getStates().maybeHas(st -> st.getBlock() instanceof BaseFireBlock)) continue;
                int x0 = Math.max(b.minX(), cx << 4), x1 = Math.min(b.maxX(), (cx << 4) + 15);
                int z0 = Math.max(b.minZ(), cz << 4), z1 = Math.min(b.maxZ(), (cz << 4) + 15);
                int y0 = Math.max(b.minY(), sy << 4), y1 = Math.min(b.maxY(), (sy << 4) + 15);
                for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++)
                    if (sec.getBlockState(x & 15, y & 15, z & 15).getBlock() instanceof BaseFireBlock) {
                        out.add(new BlockPos(x, y, z));
                        if (out.size() >= limit) return out;
                    }
            }
        }
        return out;
    }

    static void douse(ServerLevel level, BlockPos pos) {
        level.removeBlock(pos, false);
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1.4f);
        level.sendParticles(ParticleTypes.SPLASH, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 12, .4, .3, .4, .1);
        level.sendParticles(ParticleTypes.CLOUD, pos.getX() + .5, pos.getY() + .6, pos.getZ() + .5, 3, .2, .2, .2, .02);
    }

    /** One look at a burning town: keep it inside the town, follow it, send the brigade, and see if it is out. */
    static void step(ServerLevel level, Settlement s, Fire f) {
        f.scans++;
        List<BlockPos> found = scan(level, f.zone, 4000);
        f.fires.clear();
        boolean edge = false;
        for (BlockPos pos : found) {
            if (!inside(s, pos)) { level.removeBlock(pos, false); f.contained++; continue; }
            f.fires.add(pos);
            if (pos.getX() - f.zone.minX() < 3 || f.zone.maxX() - pos.getX() < 3 || pos.getZ() - f.zone.minZ() < 3
                    || f.zone.maxZ() - pos.getZ() < 3 || f.zone.maxY() - pos.getY() < 3 || pos.getY() - f.zone.minY() < 2) edge = true;
        }
        if (edge) f.zone = clamp(s, f.zone.inflatedBy(6), f.zone);
        f.burning = f.fires.size();
        f.peak = Math.max(f.peak, f.burning);
        if (!f.fires.isEmpty()) for (Plot p : s.plots) {
            if (!burns(p) || f.touched.contains(p.id)) continue;
            BoundingBox box = p.box().inflatedBy(1);
            for (BlockPos pos : f.fires) if (box.isInside(pos)) { f.touched.add(p.id); break; }
        }
        long age = level.getGameTime() - f.started;
        if (age >= Config.FIRE_BRIGADE_DELAY.get()) brigade(level, s, f);
        if (age >= Config.FIRE_MAX_TICKS.get()) {
            for (BlockPos pos : f.fires) { level.removeBlock(pos, false); f.byTown++; }
            f.fires.clear();
            f.burning = 0;
            f.quietScans = 3;
        }
        f.quietScans = f.burning == 0 ? f.quietScans + 1 : 0;
        if (f.quietScans >= 3) finish(level, s, f);
        s.dirty = true;
    }

    /** Townsfolk run to the fire; anyone of the town in reach throws water on it. A well or water nearby doubles the pace. */
    static void brigade(ServerLevel level, Settlement s, Fire f) {
        if (f.fires.isEmpty()) return;
        if (f.scans % 20 == 1 || f.crew.isEmpty()) {
            f.water = waterNear(level, s, f);
            int size = 4 + (hasWell(s) ? 2 : 0) + (s.has(RESEARCH) ? 2 : 0);
            BlockPos at = f.fires.get(0);
            List<SettlerEntity> folk = new ArrayList<>(level.getEntitiesOfClass(SettlerEntity.class, AABB.of(f.zone).inflate(64, 16, 64),
                    e -> s.id.equals(e.settlementId()) && e.isAlive()));
            folk.sort(Comparator.comparingDouble(e -> e.distanceToSqr(at.getX(), at.getY(), at.getZ())));
            f.crew.clear();
            folk.stream().limit(size).forEach(e -> f.crew.add(e.getUUID()));
        }
        List<Entity> hands = new ArrayList<>();
        for (UUID id : f.crew) { Entity e = level.getEntity(id); if (e != null && e.isAlive()) hands.add(e); }
        for (Settlement.Soldier so : s.soldiers) {
            Entity e = level.getEntity(so.entity);
            if (e != null && e.isAlive() && f.zone.inflatedBy(4).isInside(e.blockPosition())) hands.add(e);
        }
        int per = f.water ? 2 : 1;
        boolean any = false;
        for (Entity e : hands) {
            for (int k = 0; k < per && !f.fires.isEmpty(); k++) {
                BlockPos best = null;
                double bd = Double.MAX_VALUE;
                for (BlockPos pos : f.fires) {
                    double dx = pos.getX() + .5 - e.getX(), dz = pos.getZ() + .5 - e.getZ(), dy = pos.getY() - e.getY();
                    double h = dx * dx + dz * dz;
                    if (h <= REACH_H * REACH_H && Math.abs(dy) <= REACH_V && h < bd) { bd = h; best = pos; }
                }
                if (best == null) break;
                douse(level, best);
                f.fires.remove(best);
                f.byCrew++;
                any = true;
                if (e instanceof LivingEntity le) le.swing(InteractionHand.MAIN_HAND, true);
            }
        }
        // nobody of the town is near: the unseen townsfolk still pass buckets, slowly
        if (!any && hands.isEmpty() && f.scans % 4 == 0 && !f.fires.isEmpty()) {
            douse(level, f.fires.remove(0));
            f.byTown++;
        }
        f.burning = f.fires.size();
    }

    static boolean waterNear(ServerLevel level, Settlement s, Fire f) {
        BlockPos c = new BlockPos((f.zone.minX() + f.zone.maxX()) / 2, (f.zone.minY() + f.zone.maxY()) / 2, (f.zone.minZ() + f.zone.maxZ()) / 2);
        for (Plot p : s.plots) if (p.standing() && p.typeId.equals(WELL) && p.center().distSqr(c) <= 40 * 40) return true;
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-8, -4, -8), c.offset(8, 4, 8)))
            if (level.getFluidState(q).is(FluidTags.WATER)) return true;
        return false;
    }

    /** Where a firefighter should go, or null when they are not needed. */
    @Nullable
    public static BlockPos targetFor(Entity mob) {
        for (Fire f : ACTIVE.values()) {
            if (!f.crew.contains(mob.getUUID()) || f.fires.isEmpty()) continue;
            return f.fires.stream().min(Comparator.comparingDouble(p -> mob.distanceToSqr(p.getX() + .5, p.getY(), p.getZ() + .5))).orElse(null);
        }
        return null;
    }

    // ------------------------------------------------------------------ afterwards

    /** Blueprint blocks of a building that are gone (burnt away, or still fire). */
    public static int missing(ServerLevel level, Plot p) {
        Blueprint b = p.blueprint();
        if (b == null) return 0;
        int n = 0;
        for (int i = 0; i < b.size(); i++) {
            BlockState want = b.worldState(i, p.rotation);
            if (want.isAir() || !want.getFluidState().isEmpty()) continue;
            BlockState cur = level.getBlockState(b.worldPos(i, p.origin, p.rotation));
            if (cur.isAir() || cur.getBlock() instanceof BaseFireBlock) n++;
        }
        return n;
    }

    static int solid(Plot p) {
        Blueprint b = p.blueprint();
        return b == null ? 0 : b.solidCount;
    }

    /** Damaged buildings go into the builders' queue as repairs; the fire goes into the chronicle. */
    public static void finish(ServerLevel level, Settlement s, Fire f) {
        ACTIVE.remove(f.town);
        f.done = true;
        f.crew.clear();
        LAST.put(f.town, f);
        assess(level, s, f);
        if (f.peak == 0 && f.damaged.isEmpty()) return;
        if (f.damaged.isEmpty()) { s.log("The fire is out; the brigade saved every building"); return; }
        f.story = story(s, f);
        Chronicle.record(s, Chronicle.Kind.FIRE, f.story);
        String msg = "The fire is out. " + f.damaged.size() + (f.damaged.size() == 1 ? " building needs" : " buildings need") + " repair.";
        s.log(msg);
        if (!s.isAi()) s.away.note(s.away.raids, "a fire damaged " + list(f.damaged));
        if (s.owner != null) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(s.owner);
            if (p != null) p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD));
        }
    }

    public static void assess(ServerLevel level, Settlement s, Fire f) {
        f.damaged.clear();
        for (int id : f.touched) {
            Plot p = s.plot(id);
            if (p == null || !p.standing() || p.deferred || !livingrealms.building.Construction.loaded(level, p)) continue;
            int gone = missing(level, p);
            if (gone < Math.max(2, solid(p) / 100)) continue;
            BuildingType t = BuildingCatalog.get(p.typeId);
            f.damaged.add(t == null ? "building" : t.name.toLowerCase(Locale.ROOT));
            if (p.state == Plot.State.BUILT) { Planning.repair(s, p); f.repairs++; }
        }
        s.dirty = true;
    }

    static String story(Settlement s, Fire f) {
        int year = Chronicle.year(Chronicle.dayOf(f.started));
        String what = list(f.damaged);
        int pick = Math.floorMod(f.started, 3);
        if (f.damaged.size() >= 3 || f.peak >= 30)
            return switch (pick) {
                case 0 -> "The Great Fire of year " + year + ", started by " + f.cause + ", took " + what + ".";
                case 1 -> "In the Great Fire of year " + year + " " + what + " burned; " + f.cause + " was blamed.";
                default -> "Fire swept through " + s.name + " in year " + year + " (" + f.cause + "), taking " + what + ".";
            };
        return switch (pick) {
            case 0 -> "A fire started by " + f.cause + " damaged " + what + "; the bucket brigade saved the rest.";
            case 1 -> "Fire broke out in " + what + " (" + f.cause + "). The townsfolk put it out with buckets from the well.";
            default -> what.substring(0, 1).toUpperCase(Locale.ROOT) + what.substring(1) + " caught fire from " + f.cause + ", and was saved by a line of buckets.";
        };
    }

    /** "the smithy and three cottages" */
    static String list(List<String> names) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (String s : names) n.merge(s, 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        String[] words = {"", "", "two", "three", "four", "five", "six", "seven", "eight", "nine"};
        n.forEach((name, k) -> parts.add(k == 1 ? "the " + name : (k < words.length ? words[k] : String.valueOf(k)) + " " + name + (name.endsWith("s") ? "" : "s")));
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    // ------------------------------------------------------------------ for the City Manager

    public static CompoundTag view(Settlement s) {
        CompoundTag t = new CompoundTag();
        t.putBoolean("enabled", enabled());
        double daily = dailyChance(s);
        t.putString("label", enabled() ? label(daily) : "Off");
        t.putDouble("daily", daily);
        int wooden = 0, all = 0;
        List<Plot> risky = new ArrayList<>();
        for (Plot p : s.plots) if (burns(p)) {
            all++;
            if (woodFraction(p.blueprint()) >= 0.4f) wooden++;
            risky.add(p);
        }
        risky.sort(Comparator.comparingDouble(p -> -risk(s, p)));
        t.putInt("wooden", wooden);
        t.putInt("buildings", all);
        t.putBoolean("well", hasWell(s));
        t.putBoolean("watch", s.has(RESEARCH));
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < Math.min(3, risky.size()); i++) {
            BuildingType bt = BuildingCatalog.get(risky.get(i).typeId);
            if (i > 0) top.append(", ");
            top.append(bt == null ? "?" : bt.name);
        }
        t.putString("riskiest", top.toString());
        Fire f = ACTIVE.get(s.id);
        if (f != null) { t.putInt("burning", f.burning); t.putInt("crew", f.crew.size()); t.putString("cause", f.cause); }
        Fire last = LAST.get(s.id);
        if (last != null && !last.story.isEmpty()) t.putString("last", last.story);
        return t;
    }

    // ------------------------------------------------------------------ saving fires in progress (FireData)

    /** Every fire still burning, for the world save. */
    public static net.minecraft.nbt.ListTag saveAll() {
        net.minecraft.nbt.ListTag l = new net.minecraft.nbt.ListTag();
        for (Fire f : ACTIVE.values()) if (!f.done) l.add(FireData.tag(f));
        return l;
    }

    /** Fires saved burning: watched again from the next look; the brigade re-forms round the fire still found there. */
    public static void loadAll(net.minecraft.nbt.ListTag l) {
        ACTIVE.clear();
        for (int i = 0; i < l.size(); i++) {
            Fire f = FireData.fire(l.getCompound(i));
            if (f != null) ACTIVE.put(f.town, f);
        }
    }

    private Fires() {}
}
