package livingrealms.settlement;

import livingrealms.settlement.plan.RoadClass;
import livingrealms.settlement.plan.Street;
import livingrealms.settlement.plan.TownPlan;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The road a caravan takes (docs/trade.md): from its market out along the paved high street that faces the other town
 * (through its gate, where the town is walled), across country, and in along the other town's high street to its market.
 * A town with no paved high street that way is left straight from the market, as before.
 */
public final class CaravanRoad {
    /** A high street must point at least this much toward the other town (cosine) to be taken. */
    static final double FACING = 0.35;
    /** Shorter paved stretches than this are not worth the detour. */
    static final int MIN_PAVED = 8;

    public static List<BlockPos> route(Settlement from, Settlement to) {
        List<BlockPos> out = new ArrayList<>();
        add(out, Trade.market(from, to));
        for (BlockPos p : street(from, to)) add(out, p);
        List<BlockPos> in = street(to, from);
        for (int i = in.size() - 1; i >= 0; i--) add(out, in.get(i));
        add(out, Trade.market(to, from));
        if (out.size() == 1) out.add(out.get(0));
        return out;
    }

    static void add(List<BlockPos> l, BlockPos p) { if (l.isEmpty() || !l.get(l.size() - 1).equals(p)) l.add(p); }

    /** The start and paved end of the town's high street toward the other town, or nothing. */
    public static List<BlockPos> street(Settlement s, Settlement toward) {
        TownPlan plan = s.townPlan;
        if (plan == null || !plan.center.equals(s.center)) return List.of();
        double dx = toward.center.getX() - s.center.getX(), dz = toward.center.getZ() - s.center.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) return List.of();
        Street best = null;
        double bd = FACING;
        for (Street st : plan.streets) {
            if (st.cls != RoadClass.HIGH) continue;
            double dot = (st.dir.getStepX() * dx + st.dir.getStepZ() * dz) / len;
            if (dot > bd) { bd = dot; best = st; }
        }
        if (best == null) return List.of();
        int paved = Math.min(plan.laid.getOrDefault(best.id, -1), plan.open(best, s.radius()));
        if (paved < MIN_PAVED) return List.of();
        int y = s.center.getY();
        return List.of(new BlockPos(best.x(0), y, best.z(0)), new BlockPos(best.x(paved), y, best.z(paved)));
    }

    static double seg(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static double length(List<BlockPos> r) {
        double l = 0;
        for (int i = 1; i < r.size(); i++) l += seg(r.get(i - 1), r.get(i));
        return Math.max(1, l);
    }

    /** The point d blocks along the road (clamped to its ends). */
    public static BlockPos pointAt(List<BlockPos> r, double d) {
        if (d <= 0) return r.get(0);
        for (int i = 1; i < r.size(); i++) {
            BlockPos a = r.get(i - 1), b = r.get(i);
            double l = seg(a, b);
            if (d <= l && l > 0) {
                double t = d / l;
                return new BlockPos((int) Math.round(a.getX() + (b.getX() - a.getX()) * t), a.getY(), (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t));
            }
            d -= l;
        }
        return r.get(r.size() - 1);
    }

    /**
     * How far along the road (x, z) is, looking only from {@code from} blocks on, so a road that doubles back past the
     * market never jumps the caravan ahead.
     */
    public static double project(List<BlockPos> r, double x, double z, double from) {
        double best = Math.max(0, from), bd = Double.MAX_VALUE, start = 0;
        for (int i = 1; i < r.size(); i++) {
            BlockPos a = r.get(i - 1), b = r.get(i);
            double l = seg(a, b);
            if (start + l >= from && l > 0) {
                double vx = b.getX() - a.getX(), vz = b.getZ() - a.getZ();
                double t = Math.max(0, Math.min(1, ((x - a.getX() - .5) * vx + (z - a.getZ() - .5) * vz) / (l * l)));
                double along = Math.max(from, start + t * l);
                BlockPos p = pointAt(r, along);
                double d = (p.getX() + .5 - x) * (p.getX() + .5 - x) + (p.getZ() + .5 - z) * (p.getZ() + .5 - z);
                if (d < bd) { bd = d; best = along; }
            }
            start += l;
        }
        return best;
    }

    private CaravanRoad() {}
}
