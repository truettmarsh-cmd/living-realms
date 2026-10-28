package livingrealms.military;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Battle tactics for raids: who fights whom, where archers stand, who falls back and when defenders close ranks.
 * {@link #plan} is pure apart from reading entities, so the self-tests can check every rule; Raids applies the result.
 * See docs/battle-ai.md.
 */
public final class BattleAI {
    public enum Stance {
        /** Melee: close with the target, or march on the objective. */
        ADVANCE,
        /** Shieldbearer: intercept the enemy nearest the archers. */
        SCREEN,
        /** Archer: hold at bow range from the target and shoot. */
        SKIRMISH,
        /** Defender, outnumbered: hold a ring around the centre and fight only what comes close. */
        REGROUP,
        /** Badly wounded: back to the rally point to bind wounds. */
        FALL_BACK
    }

    public record Order(@Nullable LivingEntity target, BlockPos hold, Stance stance) {}

    /** One side's orders, plus what the status line reports. */
    public record Plan(Map<LivingEntity, Order> orders, int fallingBack, boolean regrouping) {
        public Order of(LivingEntity e) { return orders.get(e); }
    }

    public static final double ENGAGE_RANGE = 48, RANGED_REACH = 28;
    /** Health fractions: fall back below FALL_BACK (or SHAKEN_FALL_BACK when morale is broken), return at RETURN. */
    public static final double FALL_BACK = .3, SHAKEN_FALL_BACK = .45, RETURN = .6, SHAKEN_MORALE = 20;
    /** Defenders close ranks at the centre when the attackers outnumber them this much. */
    public static final double OUTNUMBERED = 1.5;
    public static final double REGROUP_REACH = 10, REGROUP_RING = 3;
    /** Archers keep between ARCHER_MIN and ARCHER_MAX blocks from their target, stepping to ARCHER_HOLD when outside. */
    public static final double ARCHER_MIN = 10, ARCHER_MAX = 22, ARCHER_HOLD = 14;
    /** An enemy this close to a friendly archer is a threat the shieldbearers go for first. */
    public static final double ARCHER_THREAT = 8, RULER_GUARD = 8;
    /** More melee soldiers than this on one enemy are penalised, so the line spreads over the enemy line. */
    public static final int MELEE_PER_FOE = 2;
    public static final String FALLBACK = "livingrealms_fallback";

    /** One side of a battle. Fighters are this side's ordered soldiers; foes every enemy in reach (soldiers and players). */
    public record Side(List<LivingEntity> fighters, List<LivingEntity> foes, BlockPos objective, BlockPos rally,
                       boolean defending, @Nullable LivingEntity ruler) {}

    public static Plan plan(Side side) {
        Map<LivingEntity, Order> orders = new LinkedHashMap<>();
        Set<LivingEntity> back = fallingBack(side);
        List<LivingEntity> fighting = side.fighters.stream().filter(e -> !back.contains(e)).toList();
        boolean regroup = side.defending && side.foes.size() >= 2 && side.foes.size() >= OUTNUMBERED * Math.max(1, fighting.size());
        List<LivingEntity> archers = fighting.stream().filter(RecruitsBridge::ranged).toList();
        // Shieldbearers choose first so they claim the enemies threatening the archers; archers last, they never crowd.
        List<LivingEntity> order = new ArrayList<>(fighting);
        order.sort(Comparator.comparingInt(e -> RecruitsBridge.ranged(e) ? 2 : RecruitsBridge.shielded(e) ? 0 : 1));
        Map<LivingEntity, Integer> claims = new HashMap<>();
        int ring = 0;
        for (LivingEntity e : side.fighters) if (back.contains(e)) orders.put(e, new Order(null, side.rally, Stance.FALL_BACK));
        for (LivingEntity e : order) {
            boolean ranged = RecruitsBridge.ranged(e), shield = !ranged && RecruitsBridge.shielded(e);
            LivingEntity target = choose(e, ranged, shield, side, archers, claims, regroup);
            if (target != null && !ranged) claims.merge(target, 1, Integer::sum);
            BlockPos ringPos = ring(side.objective, ring++, fighting.size());
            Order o;
            if (regroup) o = new Order(target, ringPos, Stance.REGROUP);
            else if (target != null && ranged) o = new Order(target, standoff(e, target, side.rally), Stance.SKIRMISH);
            else if (target != null) o = new Order(target, target.blockPosition(), shield ? Stance.SCREEN : Stance.ADVANCE);
            else if (side.defending) o = new Order(null, ringPos, Stance.ADVANCE);
            else o = new Order(null, ranged ? toward(side.objective, side.rally, 8) : side.objective, ranged ? Stance.SKIRMISH : Stance.ADVANCE);
            orders.put(e, o);
        }
        return new Plan(orders, back.size(), regroup);
    }

    /** The wounded who leave the line. At most half the side, the worst hurt first, and never with no enemy about. */
    static Set<LivingEntity> fallingBack(Side side) {
        Set<LivingEntity> out = new HashSet<>();
        if (side.foes.isEmpty() || side.fighters.size() < 2) return out;
        List<LivingEntity> hurt = new ArrayList<>(side.fighters);
        hurt.sort(Comparator.comparingDouble(BattleAI::health));
        int max = side.fighters.size() / 2;
        for (LivingEntity e : hurt) {
            if (out.size() >= max) break;
            double h = health(e);
            boolean already = e.getPersistentData().getBoolean(FALLBACK);
            double limit = already ? RETURN : RecruitsBridge.isRecruit(e) && RecruitsBridge.morale(e) < SHAKEN_MORALE ? SHAKEN_FALL_BACK : FALL_BACK;
            if (h < limit) out.add(e);
        }
        return out;
    }

    @Nullable
    static LivingEntity choose(LivingEntity self, boolean ranged, boolean shield, Side side, List<LivingEntity> archers,
                               Map<LivingEntity, Integer> claims, boolean regroup) {
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity foe : side.foes) {
            if (!foe.isAlive()) continue;
            double d = self.distanceTo(foe);
            if (d > (ranged ? RANGED_REACH + 20 : ENGAGE_RANGE)) continue;
            if (regroup && (ranged ? d > RANGED_REACH
                    : d > REGROUP_REACH && foe.distanceToSqr(Vec3.atCenterOf(side.objective)) > REGROUP_REACH * REGROUP_REACH)) continue;
            double score = d;
            if (ranged && RecruitsBridge.ranged(foe)) score -= 6;              // counter-battery: shoot their archers
            if (!ranged && threatens(foe, archers)) score -= shield ? 14 : 4;   // screen our archers
            score -= 6 * (1 - health(foe));                                     // finish the wounded
            if (side.ruler != null && foe.distanceTo(side.ruler) <= RULER_GUARD) score -= 14;
            if (!ranged) score += 6 * Math.max(0, claims.getOrDefault(foe, 0) - (MELEE_PER_FOE - 1));
            if (self instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == foe) score -= 3; // no flip-flopping
            if (score < bestScore) { bestScore = score; best = foe; }
        }
        return best;
    }

    static boolean threatens(LivingEntity foe, List<LivingEntity> archers) {
        for (LivingEntity a : archers) if (a.distanceToSqr(foe) <= ARCHER_THREAT * ARCHER_THREAT) return true;
        return false;
    }

    /** Where an archer stands against this target: where it is if already at bow range, else ARCHER_HOLD away from it. */
    public static BlockPos standoff(LivingEntity archer, LivingEntity target, BlockPos rally) {
        double d = archer.distanceTo(target);
        if (d >= ARCHER_MIN && d <= ARCHER_MAX) return archer.blockPosition();
        Vec3 away = archer.position().subtract(target.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < .01) away = Vec3.atCenterOf(rally).subtract(target.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < .01) away = new Vec3(1, 0, 0);
        Vec3 p = target.position().add(away.normalize().scale(ARCHER_HOLD));
        return BlockPos.containing(p.x, archer.getY(), p.z);
    }

    /** n evenly spaced places on a small ring around the centre. */
    static BlockPos ring(BlockPos c, int i, int n) {
        double a = 2 * Math.PI * i / Math.max(1, n);
        return c.offset((int) Math.round(Math.cos(a) * REGROUP_RING), 0, (int) Math.round(Math.sin(a) * REGROUP_RING));
    }

    /** `blocks` from `from` toward `to` (or `to` itself when nearer). */
    public static BlockPos toward(BlockPos from, BlockPos to, double blocks) {
        Vec3 d = Vec3.atCenterOf(to).subtract(Vec3.atCenterOf(from)).multiply(1, 0, 1);
        if (d.lengthSqr() < .01) return from;
        d = d.normalize().scale(Math.min(blocks, d.length()));
        return from.offset((int) Math.round(d.x), 0, (int) Math.round(d.z));
    }

    /** A rally point `blocks` from the centre, on the far side from the enemy (attackers: toward home). */
    public static BlockPos rally(BlockPos centre, Vec3 awayFrom, double blocks) {
        Vec3 d = Vec3.atCenterOf(centre).subtract(awayFrom).multiply(1, 0, 1);
        if (d.lengthSqr() < .01) return centre;
        d = d.normalize().scale(blocks);
        return centre.offset((int) Math.round(d.x), 0, (int) Math.round(d.z));
    }

    public static Vec3 centroid(List<? extends Entity> es, Vec3 fallback) {
        if (es.isEmpty()) return fallback;
        Vec3 sum = Vec3.ZERO;
        for (Entity e : es) sum = sum.add(e.position());
        return sum.scale(1.0 / es.size());
    }

    static double health(LivingEntity e) { return e.getMaxHealth() <= 0 ? 1 : e.getHealth() / e.getMaxHealth(); }

    // ------------------------------------------------------------------ siege fire

    /**
     * Where siege engines should aim: the objective while it is clear of our own troops; once they reach it, the densest
     * group of defenders at least 10 blocks from any attacker; null (cease fire) when there is none.
     */
    @Nullable
    public static BlockPos firePoint(BlockPos objective, List<? extends Entity> attackers, List<? extends Entity> defenders) {
        if (clear(Vec3.atCenterOf(objective), attackers)) return objective;
        Entity best = null;
        int most = 0;
        for (Entity d : defenders) {
            if (!clear(d.position(), attackers)) continue;
            int n = 0;
            for (Entity o : defenders) if (o.distanceToSqr(d) <= 16) n++;
            if (n > most) { most = n; best = d; }
        }
        return best == null ? null : best.blockPosition();
    }

    static boolean clear(Vec3 p, List<? extends Entity> friends) {
        for (Entity a : friends) if (a.position().distanceToSqr(p) < 100) return false;
        return true;
    }

    // ------------------------------------------------------------------ who is fighting whom

    private record Fighter(String battle, boolean attacker) {}
    private static final Map<UUID, Fighter> FIGHTERS = new HashMap<>();

    /** Registers both sides of a battle, so specials and Royal Guards treat enemy soldiers like monsters. */
    public static void enlist(String battle, Collection<? extends Entity> attackers, Collection<? extends Entity> defenders) {
        FIGHTERS.values().removeIf(f -> f.battle.equals(battle));
        for (Entity e : attackers) FIGHTERS.put(e.getUUID(), new Fighter(battle, true));
        for (Entity e : defenders) FIGHTERS.put(e.getUUID(), new Fighter(battle, false));
    }

    public static void release(String battle) { FIGHTERS.values().removeIf(f -> f.battle.equals(battle)); }

    /** True when a and b fight on opposite sides of the same battle. */
    public static boolean hostile(Entity a, Entity b) {
        Fighter x = FIGHTERS.get(a.getUUID()), y = FIGHTERS.get(b.getUUID());
        return x != null && y != null && x.battle.equals(y.battle) && x.attacker != y.attacker;
    }

    public static boolean fighting(Entity e) { return FIGHTERS.containsKey(e.getUUID()); }

    private BattleAI() {}
}
