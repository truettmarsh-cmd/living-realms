package livingrealms.compat;

import com.recruitsmoraleaddon.capability.IMorale;
import com.recruitsmoraleaddon.capability.MoraleCapability;
import livingrealms.military.SoldierProgression;
import livingrealms.military.SoldierRanks;
import livingrealms.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Recruits Morale addon: honours move battle morale, and veterans of higher rank hold longer before breaking. */
final class MoraleCompat {
    static final float PER_RANK = 5, MAX_BONUS = 50;

    static IMorale of(Entity e) { return e == null ? null : e.getCapability(MoraleCapability.MORALE_CAPABILITY).orElse(null); }

    static void add(Entity e, float delta) {
        IMorale m = of(e);
        if (m == null) return;
        m.setMorale(m.getMorale() + delta);
        if (delta > 0 && m.isPanicking() && m.getMorale() >= m.getMaxMorale() / 2) m.setPanicking(false);
    }

    /** Max battle morale 100 + 5 per Living Realms rank (up to +50). Returns soldiers updated. */
    static int veterans(ServerLevel level, Settlement s) {
        int n = 0;
        for (Settlement.Soldier so : s.soldiers) {
            IMorale m = of(SoldierProgression.findEntity(level.getServer(), so.entity));
            if (m == null) continue;
            float max = 100 + Math.min(MAX_BONUS, PER_RANK * SoldierRanks.rank(so));
            if (m.getMaxMorale() != max) {
                boolean full = m.getMorale() >= m.getMaxMorale();
                m.setMaxMorale(max);
                if (full || m.getMorale() > max) m.setMorale(max);
                n++;
            }
        }
        return n;
    }

    static float morale(Entity e) { IMorale m = of(e); return m == null ? -1 : m.getMorale(); }

    private MoraleCompat() {}
}
