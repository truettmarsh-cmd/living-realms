package livingrealms.dragon;

import livingrealms.settlement.*;
import livingrealms.settlement.plan.Walls;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Scorpions (docs/dragons.md): with the Scorpions research a town of Town rank or more mounts a heavy crossbow on every
 * wall tower, gatehouse and watchtower. Every three seconds, while a dragon is over the town, hostile creatures are in
 * it or it is raided, up to six towers each loose a straight, heavy bolt at the nearest threat, dragons first. Bolts
 * never hurt the town's own people; a bolt hurts a dragon three times as much as anything else. No siege-mod entity is
 * needed: bolts are server-side arrows.
 */
public final class Scorpions {
    public static final String RESEARCH = "scorpions", BOLT = "livingrealms:bolt";
    public static final int VOLLEY = 60, REACH = 80, TOWERS = 6, LIFE = 60;
    static final float SPEED = 3.5f;
    public static final Set<String> MOUNTS = Set.of(Walls.TOWER, Walls.GATE, "livingrealms:watchtower");
    /** Per town: bolts loosed and bolts that struck; dragons a bolt has struck. */
    public static final Map<UUID, Integer> SHOTS = new HashMap<>(), HITS = new HashMap<>();
    static final Set<UUID> STRUCK = new HashSet<>();
    static final Map<Arrow, Long> LIVE = new HashMap<>();

    public static boolean armed(Settlement s) {
        return DragonConfig.on(DragonConfig.SCORPIONS) && s.has(RESEARCH) && s.tier.ordinal() >= Tier.TOWN.ordinal();
    }

    static boolean hitBy(UUID dragon) { return STRUCK.contains(dragon); }

    public static List<Plot> mounts(ServerLevel level, Settlement s) {
        List<Plot> out = new ArrayList<>();
        for (Plot p : s.plots) if (MOUNTS.contains(p.typeId) && p.standing() && level.hasChunkAt(p.center())) out.add(p);
        return out;
    }

    public static void tick(MinecraftServer server, SettlementManager m, long tick) {
        if (!LIVE.isEmpty()) LIVE.entrySet().removeIf(e -> {
            if (e.getKey().isRemoved()) return true;
            if (e.getKey().level().getGameTime() - e.getValue() > LIFE) { e.getKey().discard(); return true; }
            return false;
        });
        if (tick % VOLLEY != 0) return;
        for (Settlement s : m.all()) {
            if (s.mirror() || !armed(s)) continue;
            ServerLevel level = server.getLevel(s.dim);
            if (level == null || !level.hasChunkAt(s.center)) continue;
            volley(level, m, s);
        }
    }

    /** What the scorpions shoot at: dragons over the town first, then raiders and hostile creatures in it. */
    public static List<LivingEntity> threats(ServerLevel level, SettlementManager m, Settlement s) {
        List<LivingEntity> dragons = new ArrayList<>(), others = new ArrayList<>();
        for (Flight f : Flight.over(s))
            if (level.getEntity(f.dragon) instanceof LivingEntity d && !DragonApi.dead(d)) dragons.add(d);
        int r = s.radius();
        AABB land = new AABB(s.center).inflate(r, 96, r);
        if (DragonApi.installed()) for (Mob d : level.getEntitiesOfClass(Mob.class, land, e -> DragonApi.wild(e) && !DragonApi.dead(e) && s.contains(e.blockPosition())))
            if (!dragons.contains(d)) dragons.add(d);
        if (!dragons.isEmpty()) return dragons;
        for (Raids.Raid raid : m.raids) {
            if (!raid.target.equals(s.id)) continue;
            for (UUID id : raid.soldiers) if (level.getEntity(id) instanceof LivingEntity l && l.isAlive() && s.contains(l.blockPosition())) others.add(l);
        }
        for (Mob mob : level.getEntitiesOfClass(Mob.class, land, e -> e instanceof Enemy && e.isAlive() && s.contains(e.blockPosition())
                && !Flight.ofTown(s, m, e))) others.add(mob);
        return others;
    }

    /** One volley; returns the bolts loosed. */
    public static int volley(ServerLevel level, SettlementManager m, Settlement s) {
        List<LivingEntity> threats = threats(level, m, s);
        if (threats.isEmpty()) return 0;
        List<Plot> towers = mounts(level, s);
        LivingEntity first = threats.get(0);
        towers.sort(Comparator.comparingDouble(p -> p.center().distSqr(first.blockPosition())));
        int n = 0;
        for (Plot t : towers) {
            if (n >= TOWERS) break;
            Vec3 top = top(level, t);
            LivingEntity foe = null;
            double bd = (double) REACH * REACH;
            for (LivingEntity l : threats) {
                double d = l.distanceToSqr(top);
                if (d < bd) { bd = d; foe = l; }
            }
            if (foe == null) continue;
            shoot(level, s, launch(t, top, foe), foe);
            n++;
        }
        if (n > 0) level.playSound(null, BlockPos.containing(first.position()), SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 2f, .6f);
        return n;
    }

    /** Above the tower's highest block. */
    public static Vec3 top(ServerLevel level, Plot t) {
        BlockPos c = t.center();
        var b = t.box();
        int y = c.getY();
        for (int x = b.minX(); x <= b.maxX(); x++) for (int z = b.minZ(); z <= b.maxZ(); z++)
            y = Math.max(y, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
        return new Vec3(c.getX() + .5, y + 1.5, c.getZ() + .5);
    }

    /** Where the scorpion on the parapet looses at this foe: over the tower's edge on the foe's side, so no bolt catches on its own roof. */
    public static Vec3 launch(Plot t, Vec3 top, LivingEntity foe) {
        Vec3 flat = new Vec3(foe.getX() - top.x, 0, foe.getZ() - top.z);
        if (flat.lengthSqr() < 1e-4) return top;
        var b = t.box();
        double half = Math.max(b.maxX() - b.minX(), b.maxZ() - b.minZ()) / 2.0 + 1.5;
        return top.add(flat.normalize().scale(half));
    }

    /** A heavy bolt flying straight (no drop) from a tower top, led to where the target will be. */
    public static Arrow shoot(ServerLevel level, Settlement s, Vec3 from, LivingEntity foe) {
        Vec3 aim = foe.position().add(0, foe.getBbHeight() * .5, 0);
        Arrow bolt = new Arrow(level, from.x, from.y, from.z);
        double time = aim.distanceTo(from) / SPEED;
        aim = aim.add(foe.getDeltaMovement().scale(time));
        Vec3 dir = aim.subtract(from);
        bolt.setNoGravity(true);
        bolt.shoot(dir.x, dir.y, dir.z, SPEED, .5f);
        bolt.pickup = AbstractArrow.Pickup.DISALLOWED;
        bolt.setCritArrow(true);
        bolt.setBaseDamage(2);
        bolt.getPersistentData().putBoolean(BOLT, true);
        bolt.getPersistentData().putUUID(BOLT + "/town", s.id);
        level.addFreshEntity(bolt);
        level.sendParticles(ParticleTypes.CRIT, from.x, from.y, from.z, 6, .2, .2, .2, .1);
        LIVE.put(bolt, level.getGameTime());
        SHOTS.merge(s.id, 1, Integer::sum);
        return bolt;
    }

    @Nullable
    static UUID boltTown(@Nullable DamageSource src) {
        if (src == null || !(src.getDirectEntity() instanceof AbstractArrow a) || !a.getPersistentData().getBoolean(BOLT)) return null;
        return a.getPersistentData().hasUUID(BOLT + "/town") ? a.getPersistentData().getUUID(BOLT + "/town") : new UUID(0, 0);
    }

    /** A bolt never strikes the town's own people. True to cancel the hit. */
    static boolean friendly(LivingEntity victim, DamageSource src) {
        UUID town = boltTown(src);
        if (town == null || victim.level().isClientSide) return false;
        SettlementManager m = SettlementManager.get(victim.level().getServer());
        Settlement s = m.get(town);
        return s != null && Flight.ofTown(s, m, victim);
    }

    /** The damage a bolt does to this victim (after armour), or -1 when it is no bolt. */
    public static float damage(LivingEntity victim, DamageSource src) {
        UUID town = boltTown(src);
        if (town == null) return -1;
        boolean dragon = DragonApi.dragon(victim);
        HITS.merge(town, 1, Integer::sum);
        if (dragon) STRUCK.add(victim.getUUID());
        return (float) DragonConfig.d(dragon ? DragonConfig.BOLT_DRAGON_DAMAGE : DragonConfig.BOLT_DAMAGE);
    }

    private Scorpions() {}
}
