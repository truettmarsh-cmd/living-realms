package livingrealms.battle;

import livingrealms.LivingRealms;
import livingrealms.chronicle.Chronicle;
import livingrealms.dragon.DragonAttacks;
import livingrealms.settlement.Raids;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Everything battle reports learn from Living Realms towns: the place name, raids and sieges, town dragon attacks and
 * the chronicle. Optional by design: reports are complete without it, so this one class is all a town-less Living
 * Realms would need to drop.
 */
final class BattleContext {
    /** How far outside a town's land a fight still takes its name. */
    static final int NEAR = 48;

    /** The nearest town's name, or "". */
    static String place(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos) {
        try {
            Settlement s = town(server, dim, pos);
            return s == null ? "" : s.name;
        } catch (RuntimeException e) { return ""; }
    }

    static Settlement town(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos) {
        SettlementManager m = SettlementManager.get(server);
        Settlement in = m.at(dim, pos);
        if (in != null) return in;
        Settlement near = m.nearest(dim, pos, 256);
        return near != null && near.center.distSqr(pos) <= Math.pow(near.radius() + NEAR, 2) ? near : null;
    }

    /** Raises the battle's kind from what is going on around it: a raid or siege it is part of, a dragon over the town. */
    static void classify(MinecraftServer server, BattleReports.Battle b) {
        try {
            SettlementManager m = SettlementManager.get(server);
            for (Raids.Raid r : m.raids) {
                Settlement source = m.get(r.source), target = m.get(r.target);
                if (target == null) continue;
                boolean ours = r.soldiers.stream().anyMatch(b.fighters::containsKey);
                if (ours || b.owner.equals(r.attacker) && near(target, b)) {
                    b.raise(r.siege.walled ? BattleReport.Kind.SIEGE_LAID : BattleReport.Kind.RAID_ATTACKED, target.name);
                } else if ((b.owner.equals(r.defender) || b.owner.equals(target.owner)) && near(target, b)) {
                    b.raise(r.siege.walled ? BattleReport.Kind.SIEGE_DEFENDED : BattleReport.Kind.RAID_DEFENDED, source == null ? "" : source.name);
                }
            }
            if (b.kind.ordinal() < BattleReport.Kind.DRAGON.ordinal()) {
                Settlement t = town(server, b.dim, b.pos);
                if (t != null && DragonAttacks.on() && DragonAttacks.attack(t) != null) b.raise(BattleReport.Kind.DRAGON, "");
            }
        } catch (RuntimeException e) {
            LivingRealms.LOG.debug("Battle report: could not read raids", e);
        }
    }

    private static boolean near(Settlement t, BattleReports.Battle b) {
        return t.dim.equals(b.dim) && (t.contains(b.pos) || t.center.distSqr(b.pos) <= Math.pow(t.radius() + NEAR, 2));
    }

    /**
     * A skirmish or dragon attack in which recruits fell is written in the owner's town chronicle. Raids and sieges are
     * chronicled already, and notable fallen get their own entry (Chronicle.fallen); this adds the battle around them.
     */
    static void chronicle(MinecraftServer server, UUID owner, BattleReport r) {
        if (r.kind != BattleReport.Kind.SKIRMISH && r.kind != BattleReport.Kind.DRAGON) return;
        List<BattleReport.Fighter> fallen = r.fallen();
        if (fallen.isEmpty() || r.place.isEmpty()) return;
        try {
            ResourceKey<Level> dim = BattleReports.dimension(r.dim);
            Settlement s = dim == null ? null : town(server, dim, r.pos);
            if (s == null || !owner.equals(s.owner)) return;
            String names = names(fallen.stream().map(f -> f.name).collect(Collectors.toList()));
            String foe = r.kills.isEmpty() ? "" : " against " + r.killList().toLowerCase(java.util.Locale.ROOT);
            String what = r.kind == BattleReport.Kind.DRAGON ? "When a dragon came over " + s.name : "In a skirmish" + foe + " near " + s.name;
            Chronicle.record(s, Chronicle.Kind.NOTE, what + ", " + names + (fallen.size() == 1 ? " fell" : " fell together") + ".");
        } catch (RuntimeException e) {
            LivingRealms.LOG.debug("Battle report: chronicle entry failed", e);
        }
    }

    /** "A", "A and B", "A, B and C". */
    static String names(List<String> names) {
        if (names.size() <= 1) return names.isEmpty() ? "" : names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private BattleContext() {}
}
