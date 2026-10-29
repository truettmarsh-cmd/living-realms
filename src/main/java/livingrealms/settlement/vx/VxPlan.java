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

    // ------------------------------------------------------------------ walls round a Village Expansion town

    /**
     * Fits the town's wall lines to what stands on VE's lots: the old-town wall at Village rank round every building
     * except fields, woods, mines and outlying towers, the city wall at City rank at least 20 further out; both as square
     * as the town's land allows. Gatehouses go where VE's streets cross a line. Free lots in a wall's band are kept empty.
     */
    public static void walls(Settlement s) {
        var plan = s.plan();
        List<livingrealms.settlement.plan.Walls.Line> have = plan.customWalls == null ? new ArrayList<>() : new ArrayList<>(plan.customWalls);
        livingrealms.settlement.Tier[] tiers = {livingrealms.settlement.Tier.VILLAGE, livingrealms.settlement.Tier.CITY};
        boolean changed = false;
        for (int i = have.size(); i < tiers.length; i++) {
            if (s.tier.number() < tiers[i].number()) break;
            var l = fitLine(s, i, tiers[i], i == 0 ? 0 : have.get(i - 1).a() + 20);
            if (l == null) break;
            have.add(l);
            changed = true;
        }
        if (!changed) return;
        plan.customWalls = have;
        for (VxLayout.Slot slot : s.layout.slots) {
            var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(slot.x - slot.w / 2, 0, slot.z - slot.d / 2, slot.x + slot.w / 2, 0, slot.z + slot.d / 2);
            if (!slot.used && plan.inWallBand(box)) slot.used = true;
        }
    }

    /** Laid street projects and the streets VE planned for its lots, as columns relative to the centre. */
    static List<List<long[]>> streets(Settlement s) {
        List<List<long[]>> out = new ArrayList<>();
        for (var p : s.plots) {
            if (!p.typeId.equals("road")) continue;
            Blueprint bp = p.blueprint();
            if (bp == null) continue;
            List<long[]> cols = new ArrayList<>();
            for (int i = 0; i < bp.size(); i++) if (!bp.state(i).isAir())
                cols.add(new long[]{p.origin.getX() + bp.lx(i) - s.center.getX(), p.origin.getZ() + bp.lz(i) - s.center.getZ()});
            out.add(cols);
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (VxLayout.Slot slot : s.layout.slots) {
            if (!slot.hasRoad() || !seen.add(slot.roadA ^ (slot.roadB * 31))) continue;
            BlockPos a = slot.roadA(), b = slot.roadB();
            int n = Math.max(1, Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ())));
            List<long[]> cols = new ArrayList<>();
            for (int i = 0; i <= n; i++) {
                int x = a.getX() + (b.getX() - a.getX()) * i / n - s.center.getX(), z = a.getZ() + (b.getZ() - a.getZ()) * i / n - s.center.getZ();
                cols.add(new long[]{x, z});
            }
            out.add(cols);
        }
        return out;
    }

    @Nullable
    static livingrealms.settlement.plan.Walls.Line fitLine(Settlement s, int index, livingrealms.settlement.Tier tier, int atLeast) {
        int maxAbs = 0, maxSum = 0;
        for (var p : s.plots) {
            if (p.typeId.equals("road") || livingrealms.settlement.plan.Walls.isWallPiece(p)) continue;
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t != null && (t.category == Category.FARM || t.category == Category.LUMBER || t.category == Category.MINE || t.category == Category.TOWER)) continue;
            var b = p.box();
            int x = Math.max(Math.abs(b.minX() - s.center.getX()), Math.abs(b.maxX() - s.center.getX()));
            int z = Math.max(Math.abs(b.minZ() - s.center.getZ()), Math.abs(b.maxZ() - s.center.getZ()));
            maxAbs = Math.max(maxAbs, Math.max(x, z));
            maxSum = Math.max(maxSum, x + z);
        }
        int a0 = Math.max(Math.max(26, atLeast), maxAbs + livingrealms.settlement.plan.Walls.BAND + 1);
        int r = tier.radius - 4;
        // the town's streets, laid or only planned by VE, each as its columns relative to the centre
        List<List<long[]>> roads = streets(s);
        livingrealms.settlement.plan.Walls.Line probe = null;
        int bestCount = Integer.MAX_VALUE;
        for (int a = a0; a <= a0 + 40 && a < r; a++) {
            int bMax = a + (int) Math.floor(Math.sqrt((double) r * r - (double) a * a));
            int bMin = Math.max(a + 4, maxSum + livingrealms.settlement.plan.Walls.BAND + 1);
            if (bMax < bMin) continue;
            int b = Math.max(bMin, Math.min(bMax, 2 * a - 4));
            var l = new livingrealms.settlement.plan.Walls.Line(index, a, b, tier, List.of());
            // fields and outlying towers may lie outside, but nothing may stand on the line itself
            boolean clash = false;
            for (var p : s.plots)
                if (!p.typeId.equals("road") && !livingrealms.settlement.plan.Walls.isWallPiece(p)
                        && livingrealms.settlement.plan.Walls.inBand(l, s.center, p.box(), 6)) { clash = true; break; }
            if (clash) continue;
            // a street crossing the line is a gate; one running along it would be cut, so those lines are avoided
            int count = 0, crossing = 0;
            for (List<long[]> road : roads) {
                int in = 0;
                boolean inside = false, outside = false;
                for (long[] q : road) {
                    int lv = livingrealms.settlement.plan.Walls.level(l, (int) q[0], (int) q[1]);
                    if (Math.abs(lv) <= 3) in++;
                    else if (lv < 0) inside = true;
                    else outside = true;
                }
                if (in > 0 && inside && outside && in <= 24) crossing++;
                else count += in;
            }
            int score = count * 10 - Math.min(crossing, 4);
            if (score < bestCount) { bestCount = score; probe = l; }
        }
        if (probe == null) return null;
        int a = probe.a(), b = probe.b();
        // gatehouses where laid streets cross the line
        var edges = livingrealms.settlement.plan.Walls.edges(probe);
        java.util.TreeMap<Integer, List<Integer>> hits = new java.util.TreeMap<>();
        for (List<long[]> road : roads) {
            for (long[] q : road) {
                int dx = (int) q[0], dz = (int) q[1];
                if (Math.abs(livingrealms.settlement.plan.Walls.level(probe, dx, dz)) > 1) continue;
                int best = -1, bt = 0, bd = Integer.MAX_VALUE;
                for (var e : edges) for (int t = 0; t <= e.len(); t++) {
                    int d = Math.abs(e.x(t) - dx) + Math.abs(e.z(t) - dz);
                    if (d < bd) { bd = d; best = e.index(); bt = t; }
                }
                hits.computeIfAbsent(best, k -> new ArrayList<>()).add(bt);
            }
        }
        List<Integer> gates = new ArrayList<>();
        for (var en : hits.entrySet()) {
            var e = edges.get(en.getKey());
            List<Integer> ts = new ArrayList<>(en.getValue());
            java.util.Collections.sort(ts);
            int start = 0;
            for (int i = 1; i <= ts.size(); i++) {
                if (i < ts.size() && ts.get(i) - ts.get(i - 1) <= 16) continue;
                int mid = (ts.get(start) + ts.get(i - 1)) / 2;
                if (e.len() >= 20 && (gates.isEmpty() || gates.get(gates.size() - 1) / 10_000 != e.index() || mid - gates.get(gates.size() - 1) % 10_000 >= 18)) gates.add(e.index() * 10_000 + Math.max(10, Math.min(e.len() - 10, mid)));
                start = i;
            }
        }
        if (gates.isEmpty()) gates.add(edges.get(0).len() / 2);
        return new livingrealms.settlement.plan.Walls.Line(index, a, b, tier, gates);
    }

    private VxPlan() {}
}
