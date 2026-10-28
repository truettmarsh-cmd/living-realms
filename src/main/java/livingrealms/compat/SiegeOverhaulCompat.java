package livingrealms.compat;

import livingrealms.military.SoldierProgression;
import livingrealms.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import java.util.Comparator;
import java.util.List;

/**
 * Siege Overhaul's raiders are Recruits soldiers in its "factionraids_raiders" team, not monsters, so a town's watch
 * would not see them. They now ring the alarm, and idle defenders turn on the nearest one.
 */
final class SiegeOverhaulCompat {
    static final String TEAM = "factionraids_raiders";

    static boolean raider(Entity e) {
        return e.isAlive() && (e.getTeam() != null && TEAM.equals(e.getTeam().getName())
                || e.getPersistentData().contains("FactionRaidsTeam") || e.getPersistentData().contains("FactionRaidsSiegeTeam"));
    }

    /** Called each second while a town's alarm rings. Returns the number of defenders given a target. */
    static int engage(ServerLevel level, Settlement s) {
        int r = s.radius();
        List<Mob> raiders = level.getEntitiesOfClass(Mob.class, new AABB(s.center).inflate(r, 32, r), m -> raider(m) && s.contains(m.blockPosition()));
        if (raiders.isEmpty()) return 0;
        int n = 0;
        for (Settlement.Soldier so : s.soldiers) {
            Entity e = SoldierProgression.findEntity(level.getServer(), so.entity);
            if (!(e instanceof Mob mob) || !mob.isAlive() || mob.level() != level) continue;
            LivingEntity cur = mob.getTarget();
            if (cur != null && cur.isAlive()) continue;
            Mob near = raiders.stream().filter(x -> x.distanceToSqr(mob) < 40 * 40).min(Comparator.comparingDouble(x -> x.distanceToSqr(mob))).orElse(null);
            if (near != null) { livingrealms.settlement.Realms.engage(level.getServer(), s, mob, near); mob.setTarget(near); n++; }
        }
        return n;
    }

    private SiegeOverhaulCompat() {}
}
