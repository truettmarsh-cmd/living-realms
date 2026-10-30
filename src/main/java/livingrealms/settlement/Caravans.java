package livingrealms.settlement;

import livingrealms.LivingRealms;
import livingrealms.building.Procedural;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Caravans on the road (docs/trade.md). Where someone is near, a caravan is a real wandering trader leading a pack
 * animal, with a guard or two, walking from market to market; elsewhere its travel is simulated. A caravan that walks
 * out of loaded land carries on simulated and is never lost for it; its bodies are dropped when their chunks load again.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class Caravans {
    /** Ticks a caravan spends loading at the market before it sets out. */
    public static final int LOAD_TICKS = 600;
    /** Simulated travel, in blocks a tick (a loaded caravan walks at about this pace). */
    public static final double SPEED = 0.15;
    /** Chance of an ambush per 500 blocks of road, before guards and escorts. */
    public static final double AMBUSH = 0.10;
    /** Extra chance per trip when the road passes near a town at war with the caravan's kingdom. */
    public static final double RAIDERS = 0.25;
    /** Persistent-data key marking a caravan's trader, pack animal and guards with their caravan's id. */
    public static final String TAG = "livingrealmsCaravan";
    static final int WALK_RANGE = 64;
    /** Scales every ambush chance; the self-test sets it to 0 for a predictable road. */
    static double riskScale = 1;
    private static long tick;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (++tick % 20 != 15) return;
        MinecraftServer server = e.getServer();
        SettlementManager m = SettlementManager.get(server);
        Trade.tick(server, m);
        tickAll(server, m, server.overworld().getGameTime(), 20);
    }

    /** Moves every caravan on by dt ticks. */
    static void tickAll(MinecraftServer server, SettlementManager m, long now, int dt) {
        for (Trade.Caravan c : List.copyOf(m.trade.caravans)) {
            if (c.done()) continue;
            try {
                advance(server, m, c, now, dt);
            } catch (RuntimeException ex) {
                LivingRealms.LOG.error("Caravan {} failed to advance", c.id, ex);
            }
        }
    }

    static void advance(MinecraftServer server, SettlementManager m, Trade.Caravan c, long now, int dt) {
        Settlement from = m.get(c.from), to = m.get(c.to);
        if (from == null || to == null) {
            ServerLevel any = from != null ? server.getLevel(from.dim) : to != null ? server.getLevel(to.dim) : null;
            if (any != null) dismiss(any, c);
            if (from != null && c.status == Trade.Status.LOADING) { c.cargo.forEach(from.stock::force); c.cargo.clear(); }
            Trade.lose(server, m, c, "its market is gone", now);
            return;
        }
        ServerLevel level = server.getLevel(from.dim);
        if (level == null) return;
        if (c.status == Trade.Status.LOADING) {
            if (now - c.since < LOAD_TICKS) return;
            c.status = Trade.Status.ROAD;
            c.since = now;
            from.log("The caravan for " + to.name + " set out" + (c.guards == 0 ? ", unguarded." : " with " + c.guards + (c.guards == 1 ? " guard." : " guards.")));
            from.dirty = true; to.dirty = true;
            tryWalk(level, m, c, from, to, now);
            return;
        }
        double dist = distance(from, to);
        if (c.physical) { walk(server, level, m, c, from, to, now, dist); return; }
        double before = c.progress;
        c.progress = Math.min(1, c.progress + SPEED * dt / dist);
        danger(server, level, m, c, from, to, (c.progress - before) * dist, false, now);
        if (c.done()) return;
        if (c.progress >= 1) { Trade.deliver(server, m, c, now); return; }
        tryWalk(level, m, c, from, to, now);
    }

    /** Length of the road: along the towns' high streets where they are paved, straight across country (CaravanRoad). */
    static double distance(Settlement from, Settlement to) {
        return CaravanRoad.length(CaravanRoad.route(from, to));
    }

    /** Where along the road the caravan is. */
    static BlockPos at(Settlement from, Settlement to, double progress) {
        List<BlockPos> r = CaravanRoad.route(from, to);
        return CaravanRoad.pointAt(r, progress * CaravanRoad.length(r));
    }

    /** Where the caravan is now: its trader while it walks, else the point its simulated travel has reached. */
    @Nullable
    public static BlockPos position(ServerLevel level, SettlementManager m, Trade.Caravan c) {
        Settlement from = m.get(c.from), to = m.get(c.to);
        if (from == null || to == null) return null;
        Entity t = c.physical && c.trader != null ? level.getEntity(c.trader) : null;
        return t != null ? t.blockPosition() : at(from, to, c.progress);
    }

    static boolean watched(ServerLevel level, BlockPos p) {
        for (ServerPlayer pl : level.players()) if (!pl.isSpectator() && pl.distanceToSqr(p.getX(), pl.getY(), p.getZ()) <= WALK_RANGE * WALK_RANGE) return true;
        return false;
    }

    /** A simulated caravan someone can see takes to the road for real. */
    static void tryWalk(ServerLevel level, SettlementManager m, Trade.Caravan c, Settlement from, Settlement to, long now) {
        BlockPos p = at(from, to, c.progress);
        if (level.isPositionEntityTicking(p) && watched(level, p)) materialize(level, c, from, to, p, now);
    }

    static BlockPos ground(ServerLevel level, BlockPos p) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p);
    }

    static void tag(Entity e, Trade.Caravan c) { e.getPersistentData().putUUID(TAG, c.id); }

    /** Spawns the trader, its pack animal and its guards at p. */
    public static boolean materialize(ServerLevel level, Trade.Caravan c, Settlement from, Settlement to, BlockPos p, long now) {
        if (c.physical || c.done()) return false;
        BlockPos g = ground(level, p);
        WanderingTrader t = EntityType.WANDERING_TRADER.create(level);
        String family = Procedural.styleFamily(from.style);
        AbstractChestedHorse beast = (family.equals("plains") || family.equals("desert") ? EntityType.DONKEY : EntityType.LLAMA).create(level);
        if (t == null || beast == null) return false;
        c.physical = true;
        c.trader = t.getUUID();
        c.beast = beast.getUUID();
        c.guardIds.clear();
        c.lastX = g.getX() + .5; c.lastZ = g.getZ() + .5;
        c.stuck = 0;
        c.walkingSince = now;
        t.moveTo(g.getX() + .5, g.getY(), g.getZ() + .5, level.random.nextFloat() * 360, 0);
        t.setDespawnDelay(0);
        List<BlockPos> road = CaravanRoad.route(from, to);
        t.setWanderTarget(CaravanRoad.pointAt(road, c.progress * CaravanRoad.length(road) + 12));
        t.setCustomName(Component.literal("Caravan of " + from.name));
        t.setPersistenceRequired();
        tag(t, c);
        level.addFreshEntity(t);
        beast.moveTo(g.getX() + 1.5, g.getY(), g.getZ() + .5, 0, 0);
        beast.setTamed(true);
        beast.setChest(true);
        beast.setPersistenceRequired();
        beast.setCustomName(Component.literal(Trade.summary(c.cargo)));
        tag(beast, c);
        level.addFreshEntity(beast);
        beast.setLeashedTo(t, true);
        for (int i = 0; i < c.guards; i++) {
            Entity guard = RecruitsBridge.spawn(level, UnitType.FOOTMAN, g.offset(i == 0 ? -1 : 0, 0, i == 0 ? 0 : -1),
                    from.mirror() ? null : from.kingdomId(), "Caravan guard of " + from.name);
            if (guard == null) continue;
            RecruitsBridge.setSlot(guard, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            tag(guard, c);
            c.guardIds.add(guard.getUUID());
        }
        from.dirty = true;
        return true;
    }

    /** Takes the caravan's bodies out of the world; travel goes on simulated. Bodies in unloaded land are dropped when they load. */
    static void dismiss(ServerLevel level, Trade.Caravan c) {
        List<UUID> ids = new ArrayList<>(c.guardIds);
        if (c.trader != null) ids.add(c.trader);
        if (c.beast != null) ids.add(c.beast);
        c.physical = false;
        c.trader = null; c.beast = null;
        c.guardIds.clear();
        for (UUID id : ids) {
            Entity e = level.getEntity(id);
            if (e != null) e.discard();
        }
    }

    /** How far along the road a point is, never more than a few blocks behind the caravan's progress so far. */
    static double progressAt(Settlement from, Settlement to, double x, double z, double progress) {
        List<BlockPos> r = CaravanRoad.route(from, to);
        double len = CaravanRoad.length(r);
        return Math.max(0, Math.min(1, CaravanRoad.project(r, x, z, progress * len - 6) / len));
    }

    /** One second of a walking caravan. */
    static void walk(MinecraftServer server, ServerLevel level, SettlementManager m, Trade.Caravan c, Settlement from, Settlement to, long now, double dist) {
        Entity e = c.trader == null ? null : level.getEntity(c.trader);
        if (!(e instanceof WanderingTrader t) || !t.isAlive() || !level.isPositionEntityTicking(t.blockPosition())) {
            // out of loaded land (or gone without a death): it travels on, simulated, from where it was last seen
            c.progress = Math.max(c.progress, progressAt(from, to, c.lastX, c.lastZ, c.progress));
            dismiss(level, c);
            return;
        }
        BlockPos dest = Trade.market(to, from);
        double fx = dest.getX() + .5 - t.getX(), fz = dest.getZ() + .5 - t.getZ();
        double moved = Math.sqrt((t.getX() - c.lastX) * (t.getX() - c.lastX) + (t.getZ() - c.lastZ) * (t.getZ() - c.lastZ));
        c.lastX = t.getX(); c.lastZ = t.getZ();
        // along the road: out by the high street, across country, in by the other town's high street
        List<BlockPos> road = CaravanRoad.route(from, to);
        double along = CaravanRoad.project(road, t.getX(), t.getZ(), c.progress * dist - 6);
        c.progress = Math.max(0, Math.min(1, along / dist));
        BlockPos ahead = CaravanRoad.pointAt(road, along + 12);
        double dx = ahead.getX() + .5 - t.getX(), dz = ahead.getZ() + .5 - t.getZ(), left = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        if (fx * fx + fz * fz <= 16) {
            Trade.deliver(server, m, c, now);
            dismiss(level, c);
            return;
        }
        danger(server, level, m, c, from, to, moved, true, now);
        if (c.done()) return;
        boolean threat = threatened(level, t);
        // lead the way: a leg of a dozen blocks at a time, like a wandering trader heading for its target
        t.setWanderTarget(ahead);
        if (t.getNavigation().isDone() && !threat) {
            BlockPos leg = ground(level, ahead);
            t.getNavigation().moveTo(leg.getX() + .5, leg.getY(), leg.getZ() + .5, 0.7);
        }
        // stuck on a river bank or a cliff: hop a few blocks on
        if (moved < 0.5 && !threat) {
            if (++c.stuck >= 15) {
                c.stuck = 0;
                double step = Math.min(8, left);
                BlockPos hop = ground(level, BlockPos.containing(t.getX() + dx / left * step, t.getY(), t.getZ() + dz / left * step));
                if (level.isPositionEntityTicking(hop)) t.teleportTo(hop.getX() + .5, hop.getY(), hop.getZ() + .5);
            }
        } else c.stuck = 0;
        Entity b = c.beast == null ? null : level.getEntity(c.beast);
        if (b instanceof Mob beast && beast.isAlive()) {
            if (beast.distanceToSqr(t) > 100) beast.teleportTo(t.getX() + 1, t.getY(), t.getZ());
            if (!beast.isLeashed()) beast.setLeashedTo(t, true);
        }
        for (UUID id : List.copyOf(c.guardIds)) {
            Entity g = level.getEntity(id);
            if (g == null || !g.isAlive()) continue;
            if (g.distanceToSqr(t) > 24 * 24) g.teleportTo(t.getX(), t.getY(), t.getZ());
            if (g instanceof Mob mob && mob.getTarget() == null)
                RecruitsBridge.holdAt(g, BlockPos.containing(t.getX() - dx / left * 2, t.getY(), t.getZ() - dz / left * 2));
        }
        // far slower than walking pace: carry on simulated
        if (now - c.walkingSince > (long) (3 * dist / SPEED) + 2400) dismiss(level, c);
    }

    static boolean threatened(ServerLevel level, Entity t) {
        return !level.getEntitiesOfClass(Mob.class, t.getBoundingBox().inflate(12),
                x -> x.isAlive() && (x instanceof net.minecraft.world.entity.monster.Enemy) && x.getTarget() != null).isEmpty();
    }

    // ------------------------------------------------------------------ danger

    /** Escorted: soldiers of the caravan's own kingdom (not its guards), or its ruler, walking with it. */
    static boolean escorted(ServerLevel level, Trade.Caravan c, Settlement from) {
        Entity t = c.trader == null ? null : level.getEntity(c.trader);
        if (t == null) return false;
        UUID k = from.kingdomId();
        for (Entity e : level.getEntities(t, t.getBoundingBox().inflate(16))) {
            if (c.body(e.getUUID()) || !e.isAlive()) continue;
            if (e instanceof ServerPlayer p && (k.equals(p.getUUID()) || c.escorts.contains(p.getUUID()))) return true;
            if (RecruitsBridge.isRecruit(e) && k.equals(RecruitsBridge.owner(e))) return true;
        }
        return false;
    }

    /** The town at war with the caravan's kingdom whose land lies near the road, or null. */
    @Nullable
    static Settlement raiders(SettlementManager m, Settlement from, Settlement to) {
        BlockPos a = from.center, b = to.center;
        for (Settlement s : m.all()) {
            if (!s.dim.equals(from.dim) || Kingdoms.same(s, from) || m.state.war(s.kingdomId(), from.kingdomId()) == null) continue;
            if (segmentDist(a, b, s.center) <= 256) return s;
        }
        return null;
    }

    static double segmentDist(BlockPos a, BlockPos b, BlockPos p) {
        double vx = b.getX() - a.getX(), vz = b.getZ() - a.getZ(), wx = p.getX() - a.getX(), wz = p.getZ() - a.getZ();
        double len = vx * vx + vz * vz, t = len == 0 ? 0 : Math.max(0, Math.min(1, (wx * vx + wz * vz) / len));
        double x = a.getX() + vx * t - p.getX(), z = a.getZ() + vz * t - p.getZ();
        return Math.sqrt(x * x + z * z);
    }

    /** Chance the whole trip meets an ambush: longer roads and roads past enemies are riskier; guards and an escort make it safer. */
    public static double tripRisk(SettlementManager m, Trade.Caravan c, Settlement from, Settlement to, boolean escort) {
        double r = AMBUSH * distance(from, to) / 500;
        if (raiders(m, from, to) != null) r += RAIDERS;
        r *= 1 - 0.25 * c.guards;
        if (escort) r *= 0.5;
        return Math.min(0.9, r);
    }

    static void danger(MinecraftServer server, ServerLevel level, SettlementManager m, Trade.Caravan c, Settlement from, Settlement to, double blocks, boolean physical, long now) {
        if (blocks <= 0 || c.ambushes >= (physical ? 1 : 2)) return;
        double risk = riskScale * tripRisk(m, c, from, to, physical && escorted(level, c, from)) * blocks / distance(from, to);
        if (level.random.nextDouble() < risk) ambush(server, level, m, c, physical, level.random.nextDouble(), now);
    }

    /**
     * An ambush. On the road for real, a small band attacks and the caravan's guards fight it; simulated, the roll
     * decides: guards make a loss less likely, and a caravan that survives loses a quarter of its goods.
     */
    static void ambush(MinecraftServer server, ServerLevel level, SettlementManager m, Trade.Caravan c, boolean physical, double roll, long now) {
        Settlement from = m.get(c.from), to = m.get(c.to);
        if (from == null || to == null || c.done()) return;
        c.ambushes++;
        Settlement enemy = raiders(m, from, to);
        String who = enemy != null ? "raiders from " + enemy.name : "bandits";
        if (physical) {
            Entity t = c.trader == null ? null : level.getEntity(c.trader);
            if (t == null) return;
            boolean night = level.isNight();
            String family = Procedural.styleFamily(from.style);
            EntityType<? extends Mob> type = enemy == null && night ? (family.equals("desert") ? EntityType.HUSK : EntityType.ZOMBIE) : EntityType.PILLAGER;
            if (type != EntityType.PILLAGER) who = "the dead";
            int n = 2 + (c.guards > 0 ? 1 : 0);
            for (int i = 0; i < n; i++) {
                double a = level.random.nextDouble() * Math.PI * 2;
                BlockPos at = ground(level, BlockPos.containing(t.getX() + Math.cos(a) * 14, t.getY(), t.getZ() + Math.sin(a) * 14));
                if (!level.isPositionEntityTicking(at)) continue;
                Mob mob = type.create(level);
                if (mob == null) continue;
                mob.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null, null);
                level.addFreshEntity(mob);
                if (t instanceof LivingEntity victim) mob.setTarget(victim);
            }
            c.note = "Ambushed by " + who;
            Raids.announce(server, from, "Our caravan to " + to.name + " is under attack by " + who + "!");
            return;
        }
        double loss = Math.max(0.1, 0.6 - 0.2 * c.guards);
        if (roll < loss) {
            c.cargo.clear();
            Trade.lose(server, m, c, who + " ambushed it on the road", now);
            if (enemy != null) Trade.hostileAct(server, m, c, enemy.kingdomId(), now);
            return;
        }
        int before = c.load();
        for (Item it : List.copyOf(c.cargo.keySet())) {
            int left = c.cargo.get(it) * 3 / 4;
            if (left <= 0) c.cargo.remove(it); else c.cargo.put(it, left);
        }
        c.value *= before == 0 ? 0 : c.load() / (double) before;
        c.note = "Fought off " + who + "; lost " + (before - c.load()) + " goods";
        Raids.announce(server, from, "Our caravan to " + to.name + " fought off " + who + " but lost some goods.");
    }

    // ------------------------------------------------------------------ events

    /** Killing the trader or its pack animal ends the caravan and spills its goods; by another kingdom, it is a hostile act. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity().level() instanceof ServerLevel level) || !e.getEntity().getPersistentData().hasUUID(TAG)) return;
        MinecraftServer server = level.getServer();
        SettlementManager m = SettlementManager.get(server);
        Trade.Caravan c = m.trade.caravan(e.getEntity().getPersistentData().getUUID(TAG));
        if (c == null || c.done()) return;
        UUID id = e.getEntity().getUUID();
        if (c.guardIds.remove(id)) { c.guards = Math.max(0, c.guards - 1); return; }
        if (!id.equals(c.trader) && !id.equals(c.beast)) return;
        long now = server.overworld().getGameTime();
        Entity killer = e.getSource().getEntity();
        spill(level, c, e.getEntity().getX(), e.getEntity().getY(), e.getEntity().getZ());
        String why = killer == null ? "it fell on the road" : "attacked by " + killer.getName().getString();
        Trade.lose(server, m, c, why, now);
        UUID attacker = killer instanceof ServerPlayer p ? p.getUUID() : killer != null && RecruitsBridge.isRecruit(killer) ? RecruitsBridge.owner(killer) : null;
        if (attacker != null) Trade.hostileAct(server, m, c, attacker, now);
        // the guards and the other half of the caravan go home
        List<UUID> rest = new ArrayList<>(c.guardIds);
        if (c.trader != null && !c.trader.equals(id)) rest.add(c.trader);
        if (c.beast != null && !c.beast.equals(id)) rest.add(c.beast);
        c.physical = false; c.trader = null; c.beast = null; c.guardIds.clear();
        for (UUID r : rest) { Entity x = level.getEntity(r); if (x != null) x.discard(); }
    }

    static void spill(ServerLevel level, Trade.Caravan c, double x, double y, double z) {
        c.cargo.forEach((it, n) -> {
            while (n > 0) {
                int k = Math.min(n, it.getMaxStackSize());
                ItemEntity drop = new ItemEntity(level, x, y + .5, z, new ItemStack(it, k));
                drop.setDefaultPickUpDelay();
                level.addFreshEntity(drop);
                n -= k;
            }
        });
        c.cargo.clear();
    }

    /** A caravan's body whose caravan travels on simulated, arrived or was lost is dropped as its chunk loads. */
    /** Caravan traders carry the town's goods, not a shop: no vanilla trading screen. */
    @SubscribeEvent
    public static void onInteract(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract e) {
        if (e.getTarget() instanceof net.minecraft.world.entity.npc.WanderingTrader && e.getTarget().getPersistentData().hasUUID(TAG)) {
            e.setCanceled(true);
            e.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !e.getEntity().getPersistentData().hasUUID(TAG)) return;
        Trade.Caravan c = SettlementManager.get(level.getServer()).trade.caravan(e.getEntity().getPersistentData().getUUID(TAG));
        if (c == null || c.done() || !c.physical || !c.body(e.getEntity().getUUID())) {
            // guards spawned by RecruitsBridge join before they are tagged, so a tagged entity here is always a stale one
            e.setCanceled(true);
        }
    }

    private Caravans() {}
}
