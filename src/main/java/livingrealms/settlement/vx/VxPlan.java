package livingrealms.settlement.vx;

import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.plan.Zoning;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Village Expansion's streets and lots with Living Realms' districts: a lot is chosen by distance from the centre
 * (noisy trades and fields out, shops and services in), not by the order VE emitted it, and the building faces the
 * lot's street. Holds no VE classes.
 */
public final class VxPlan {
    public record Fit(int x, int z, Rotation rot) {}

    /** Free lots fit for this kind of building, best first. */
    public static List<VxLayout.Slot> ranked(Settlement s, BuildingType t) {
        int count = (int) s.plots.stream().filter(p -> { BuildingType o = BuildingCatalog.get(p.typeId); return o != null && o.category == t.category; }).count();
        Zoning.Rule rule = t.category == Category.CIVIC ? new Zoning.Rule(0, 48, 0, true, false, 2, 0) : Zoning.rule(t, s.radius(), count);
        List<double[]> scored = new ArrayList<>();
        List<VxLayout.Slot> slots = new ArrayList<>();
        for (VxLayout.Slot slot : s.layout.slots) {
            if (slot.used || !slot.hasRoad()) continue;
            double d = Math.hypot(slot.x - s.center.getX(), slot.z - s.center.getZ());
            if (d < rule.min() || d > rule.max()) continue;
            if (rule.noHighInCore() && slot.primary && d < 40) continue;
            double score = -Math.abs(d - rule.pref());
            if (VxLayout.accepts(slot.kind).contains(t.category)) score += 8;
            if (rule.wantsHigh() && slot.primary) score += 10;
            scored.add(new double[]{score, slots.size()});
            slots.add(slot);
        }
        scored.sort(Comparator.comparingDouble(a -> -a[0]));
        List<VxLayout.Slot> out = new ArrayList<>();
        for (double[] e : scored) out.add(slots.get((int) e[1]));
        return out;
    }

    /** The building on a lot: door toward the lot's street, front wall just off the road, centred on the lot along it. */
    @Nullable
    public static Fit fit(Settlement s, VxLayout.Slot slot, Blueprint bp) {
        BlockPos a = slot.roadA(), b = slot.roadB();
        boolean alongX = Math.abs(a.getX() - b.getX()) >= Math.abs(a.getZ() - b.getZ());
        Direction front;
        int x, z;
        if (alongX) {
            int roadZ = interpolate(a.getX(), a.getZ(), b.getX(), b.getZ(), slot.x);
            front = slot.z > roadZ ? Direction.NORTH : Direction.SOUTH;
            Rotation rot = bp.facing(front);
            int w = bp.width(rot), d = bp.depth(rot);
            if (Math.max(w, d) > Math.max(slot.w, slot.d) + 4) return null;
            x = slot.x - w / 2;
            z = front == Direction.NORTH ? roadZ + 2 : roadZ - 1 - d;
            return new Fit(x, z, rot);
        }
        int roadX = interpolate(a.getZ(), a.getX(), b.getZ(), b.getX(), slot.z);
        front = slot.x > roadX ? Direction.WEST : Direction.EAST;
        Rotation rot = bp.facing(front);
        int w = bp.width(rot), d = bp.depth(rot);
        if (Math.max(w, d) > Math.max(slot.w, slot.d) + 4) return null;
        z = slot.z - d / 2;
        x = front == Direction.WEST ? roadX + 2 : roadX - 1 - w;
        return new Fit(x, z, rot);
    }

    /** The road's cross coordinate at {@code at} along it (clamped to its ends). */
    static int interpolate(int u0, int v0, int u1, int v1, int at) {
        if (u0 == u1) return v0;
        double t = Math.max(0, Math.min(1, (at - u0) / (double) (u1 - u0)));
        return (int) Math.round(v0 + (v1 - v0) * t);
    }

    private VxPlan() {}
}
