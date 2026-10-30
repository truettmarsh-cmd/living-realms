package livingrealms.dragon;

import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.entity.SettlerEntity;
import livingrealms.fire.Fires;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A dragon flown by Living Realms for a short, bounded attack: a wild dragon drawn to a town, or an NPC kingdom's
 * dragon on a raid sortie. While flown its own AI is off: it circles above the focus (the town centre or a siege
 * breach), and every few seconds breathes on the town's people (a few of them at most) or, while the fire allowance
 * lasts, on a building, which goes to the town fire system. When the time is up, or it is badly hurt, it flies off and
 * its own AI comes back. A flag on the dragon brings its AI back after a restart.
 */
public final class Flight {
    public enum Kind { WILD, SORTIE }
    public static final String FLAG = "livingrealms:flown";
    public static final int STRAFE_EVERY = 60, RADIUS = 14, HEIGHT = 16;
    static final double SPEED = 1.1, RETREAT_AT = 0.3;

    public final UUID dragon, town;
    public final Kind kind;
    public final ResourceKey<Level> dim;
    public BlockPos focus;
    public long started, until, lastStrafe, breathOff;
    public double angle;
    public int fireBudget, fires, strafes, burned, missing;
    public boolean retreating, slain;
    @Nullable public Vec3 away;
    public String cause = "dragonfire";
    /** Sorties: the rider, the dragon's town, the raid's two towns. */
    @Nullable public UUID rider, home, raidSource, raidTarget;
    public final List<String> struck = new ArrayList<>();
    final Set<Integer> struckPlots = new HashSet<>();

    Flight(UUID dragon, UUID town, Kind kind, ResourceKey<Level> dim, BlockPos focus, long now) {
        this.dragon = dragon; this.town = town; this.kind = kind; this.dim = dim; this.focus = focus;
        this.started = now; this.lastStrafe = now;
        this.until = now + DragonConfig.i(DragonConfig.ATTACK_SECONDS) * 20L;
        this.fireBudget = DragonConfig.i(DragonConfig.MAX_FIRES);
    }

    public static final Map<UUID, Flight> ACTIVE = new LinkedHashMap<>();

    public static boolean flying(Entity e) { return ACTIVE.containsKey(e.getUUID()); }

    /** Takes the dragon's wings: its AI goes off until the flight ends. */
    static Flight begin(ServerLevel level, Mob d, UUID town, Kind kind, BlockPos focus) {
        Flight f = new Flight(d.getUUID(), town, kind, level.dimension(), focus, level.getGameTime());
        f.angle = Math.atan2(d.getZ() - focus.getZ(), d.getX() - focus.getX());
        ACTIVE.put(d.getUUID(), f);
        d.getPersistentData().putBoolean(FLAG, true);
        d.setNoAi(true);
        d.setNoGravity(true);
        d.setTarget(null);
        DragonApi.command(d, DragonApi.WANDER);
        DragonApi.flying(d, true);
        return f;
    }

    /** Gives the dragon its own AI back. */
    static void release(Entity e) {
        e.getPersistentData().remove(FLAG);
        if (e instanceof Mob m) { m.setNoAi(false); m.setNoGravity(false); }
        DragonApi.breathing(e, false);
    }

    // ------------------------------------------------------------------ every tick

    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        SettlementManager m = SettlementManager.get(server);
        for (Flight f : List.copyOf(ACTIVE.values())) {
            ServerLevel level = server.getLevel(f.dim);
            Entity e = level == null ? null : level.getEntity(f.dragon);
            if (e == null) {
                // unloaded or gone: give up after a while (the flag restores its AI when it loads again)
                if (++f.missing > 400) end(server, f, null);
                continue;
            }
            f.missing = 0;
            if (DragonApi.dead(e)) { f.slain = true; end(server, f, e); continue; }
            step(level, m, f, (Mob) e);
        }
    }

    static void step(ServerLevel level, SettlementManager m, Flight f, Mob d) {
        long now = level.getGameTime();
        Settlement s = m.get(f.town);
        if (!f.retreating && (now >= f.until || s == null || d.getHealth() < d.getMaxHealth() * RETREAT_AT)) retreat(f, d);
        if (f.breathOff > 0 && now >= f.breathOff) { DragonApi.breathing(d, false); f.breathOff = 0; }
        Vec3 want;
        if (f.retreating) {
            want = f.away;
            if (d.position().distanceTo(want) < 3 || now > f.until + 400) { end(level.getServer(), f, d); return; }
        } else {
            f.angle += SPEED / RADIUS;
            double ground = level.hasChunkAt(f.focus) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, f.focus.getX(), f.focus.getZ()) : f.focus.getY();
            want = new Vec3(f.focus.getX() + .5 + Math.cos(f.angle) * RADIUS, ground + HEIGHT, f.focus.getZ() + .5 + Math.sin(f.angle) * RADIUS);
        }
        Vec3 to = want.subtract(d.position());
        double len = to.length();
        Vec3 move = len < 1e-4 ? Vec3.ZERO : to.scale(Math.min(len, SPEED * 1.4) / len);
        BlockPos next = BlockPos.containing(d.position().add(move));
        if (level.hasChunkAt(next)) d.setPos(d.position().add(move));
        d.setDeltaMovement(move);
        if (move.lengthSqr() > 1e-4) {
            float yaw = (float) (Math.atan2(move.z, move.x) * 180 / Math.PI) - 90;
            d.setYRot(yaw); d.setYHeadRot(yaw); d.setYBodyRot(yaw);
        }
        d.fallDistance = 0;
        d.hasImpulse = true;
        if (now % 20 == 0) DragonApi.flying(d, true);
        if (!f.retreating && s != null && now - f.lastStrafe >= STRAFE_EVERY) strafe(level, s, f, d);
        if (f.kind == Kind.SORTIE) Sorties.step(level, f, d);
    }

    static void retreat(Flight f, Mob d) {
        f.retreating = true;
        Vec3 out = d.position().subtract(Vec3.atCenterOf(f.focus)).multiply(1, 0, 1);
        if (out.lengthSqr() < 1) out = new Vec3(1, 0, 0);
        f.away = Vec3.atCenterOf(f.focus).add(out.normalize().scale(96)).add(0, HEIGHT + 8, 0);
        DragonApi.breathing(d, false);
    }

    static void end(MinecraftServer server, Flight f, @Nullable Entity e) {
        ACTIVE.remove(f.dragon);
        if (e != null) release(e);
        if (f.kind == Kind.WILD) DragonAttacks.ended(server, f, e);
        else Sorties.ended(server, f, e);
    }

    // ------------------------------------------------------------------ the breath

    /** People of the town, its soldiers and its ruler. */
    static boolean ofTown(Settlement s, SettlementManager m, Entity e) {
        if (e instanceof SettlerEntity st) return s.id.equals(st.settlementId());
        if (e instanceof ServerPlayer p) return p.getUUID().equals(s.owner) && !p.isCreative() && !p.isSpectator();
        for (Settlement c : livingrealms.settlement.Kingdoms.cities(m, s))
            for (Settlement.Soldier so : c.soldiers) if (so.entity.equals(e.getUUID())) return true;
        return false;
    }

    static void strafe(ServerLevel level, Settlement s, Flight f, Mob d) {
        f.lastStrafe = level.getGameTime();
        SettlementManager m = SettlementManager.get(level.getServer());
        String element = DragonApi.element(d);
        boolean burns = !element.equals("ice") && f.fireBudget > 0 && Fires.enabled();
        // every other breath goes at a building while the dragon may still set fires
        Plot building = burns && f.strafes % 2 == 1 ? building(level, s, f.focus) : null;
        LivingEntity victim = null;
        if (building == null) {
            double best = Double.MAX_VALUE;
            for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, new AABB(f.focus).inflate(RADIUS + 16, 32, RADIUS + 16),
                    l -> l.isAlive() && l != d && !l.getUUID().equals(f.rider) && ofTown(s, m, l))) {
                double dd = l.distanceToSqr(d);
                if (dd < best) { best = dd; victim = l; }
            }
            if (victim == null && burns) building = building(level, s, f.focus);
        }
        Vec3 at = victim != null ? victim.position() : building != null ? Vec3.atCenterOf(building.center()) : null;
        if (at == null) return;
        f.strafes++;
        Vec3 from = d.position().add(0, d.getBbHeight() * .6, 0);
        ParticleOptions fx = element.equals("ice") ? ParticleTypes.SNOWFLAKE : element.equals("lightning") ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.FLAME;
        for (int i = 0; i <= 16; i++) {
            Vec3 p = from.lerp(at, i / 16.0);
            level.sendParticles(fx, p.x, p.y, p.z, 4, .3, .3, .3, .02);
        }
        level.playSound(null, BlockPos.containing(at), element.equals("lightning") ? SoundEvents.LIGHTNING_BOLT_THUNDER : SoundEvents.ENDER_DRAGON_SHOOT,
                SoundSource.HOSTILE, 3f, .8f);
        DragonApi.breathing(d, true);
        f.breathOff = level.getGameTime() + 20;
        // at most three people in the blast
        int hit = 0;
        for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, new AABB(BlockPos.containing(at)).inflate(3.5),
                l -> l.isAlive() && l != d && !l.getUUID().equals(f.rider) && ofTown(s, m, l))) {
            if (hit++ >= 3) break;
            l.hurt(level.damageSources().mobAttack(d), element.equals("lightning") ? 7 : 5);
            if (element.equals("fire")) l.setSecondsOnFire(4);
            if (element.equals("ice")) l.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 2));
        }
        if (burns) {
            Plot target = building != null ? building : near(level, s, BlockPos.containing(at), 10);
            if (target != null && Fires.ignite(level, s, target, f.cause, level.random) != null) {
                f.fireBudget--;
                f.fires++;
                BuildingType t = BuildingCatalog.get(target.typeId);
                if (f.struckPlots.add(target.id)) f.struck.add(t == null ? "building" : t.name.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** The building nearest the focus that can burn, among the loaded ones. */
    @Nullable
    static Plot building(ServerLevel level, Settlement s, BlockPos focus) { return near(level, s, focus, 48); }

    @Nullable
    static Plot near(ServerLevel level, Settlement s, BlockPos at, int reach) {
        Plot best = null;
        double bd = (double) reach * reach;
        for (Plot p : s.plots) {
            if (Fires.risk(s, p) <= 0 || !livingrealms.building.Construction.loaded(level, p)) continue;
            double dd = p.center().distSqr(at.atY(p.center().getY()));
            if (dd < bd) { bd = dd; best = p; }
        }
        return best;
    }

    /** Flights over a town (attackers its defences shoot at). */
    public static List<Flight> over(Settlement s) {
        List<Flight> out = new ArrayList<>();
        for (Flight f : ACTIVE.values()) if (f.town.equals(s.id)) out.add(f);
        return out;
    }
}
