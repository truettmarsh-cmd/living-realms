package livingrealms.settlement.plan;

import livingrealms.building.*;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.*;

/** Checks that a town laid out by its plan makes sense; returns what is wrong (empty when all is well). */
public final class PlanAudit {
    public static List<String> audit(Settlement s, TownPlan plan, Terrain terrain) {
        List<String> bad = new ArrayList<>();
        List<Plot> buildings = new ArrayList<>();
        // wall pieces are checked by walls()
        for (Plot p : s.plots) if (!p.typeId.equals("road") && p.state != Plot.State.DEMOLISHING && !Walls.isWallPiece(p)) buildings.add(p);
        int homes = 0, noisyHomes = 0;
        for (Plot p : buildings) {
            BuildingType t = BuildingCatalog.get(p.typeId);
            Blueprint bp = p.blueprint();
            if (t == null || bp == null) continue;
            BoundingBox box = p.box();
            String name = t.id.replace("livingrealms:", "") + "@" + box.minX() + "," + box.minZ();
            double d = plan.dist(box);
            Direction front = bp.front(p.rotation);
            // core: the hall in the market place, facing south; nothing else inside the castle ring except plaza fronts
            if (t.id.equals("livingrealms:town_hall")) {
                if (d > 12) bad.add(name + " is " + (int) d + " from the centre");
                if (front != Direction.SOUTH) bad.add(name + " faces " + front);
            } else if (plan.inCore(box) && !plazaFront(plan, p)) bad.add(name + " is inside the market place / castle ring");
            // territory
            if (!TownPlan.inside(s, box)) bad.add(name + " is outside the town's land");
            // frontage: door side toward its street, front wall within 3 of the paved road, which reaches the door
            Integer sid = plan.frontage.get(p.id);
            if (sid != null) {
                Street st = plan.streets.get(sid);
                Direction toward = towardStreet(st, box);
                if (front != toward) bad.add(name + " faces " + front + " but its street is " + toward);
                int gap = gap(st, box, toward);
                if (gap < 0 || gap > 3) bad.add(name + " stands " + gap + " from its street");
                BlockPos door = bp.doorWorld(p.origin, p.rotation);
                int along = Math.max(0, st.along(door.getX(), door.getZ()));
                if (plan.laid.getOrDefault(st.id, -1) < Math.min(along, plan.open(st, s.radius()))) bad.add(name + ": its street is not paved to its door");
            }
            // streets stay clear
            for (Street st : plan.streets) {
                int laid = plan.laid.getOrDefault(st.id, -1);
                if (laid >= 0 && TownPlan.overlap(st.corridor(0, laid), box)) bad.add(name + " stands on street " + st.id);
            }
            // overlaps, including the room each building keeps to be enlarged
            BoundingBox grown = TownPlan.grownBox(s, p);
            for (Plot o : buildings) {
                if (o == p || o.id < p.id) continue;
                if (TownPlan.overlap(box, o.box())) bad.add(name + " overlaps plot " + o.id);
                else if (TownPlan.overlap(grown, TownPlan.grownBox(s, o))) bad.add(name + " has no room to grow beside plot " + o.id);
            }
            // districts
            Category c = t.category;
            if ((c == Category.SMITHY || c == Category.CONSTRUCTION || Zoning.smelly(t)) && d < 24) bad.add(name + " (noisy) is " + (int) d + " from the centre");
            if ((c == Category.MINE || c == Category.LUMBER) && d < 32) bad.add(name + " (mine/lumber) is " + (int) d + " from the centre");
            if (c == Category.FARM && d < Zoning.CORE) bad.add(name + " (farm) is " + (int) d + " from the centre");
            if (c == Category.HOUSING) {
                homes++;
                for (Plot o : buildings) {
                    BuildingType ot = BuildingCatalog.get(o.typeId);
                    if (ot != null && ot.category == Category.SMITHY && TownPlan.overlap(box.inflatedBy(12), o.box())) { noisyHomes++; break; }
                }
            }
            // dry ground (fields bring their own irrigation channels)
            if (c != Category.FARM) for (int x = box.minX(); x <= box.maxX(); x += 2) for (int z = box.minZ(); z <= box.maxZ(); z += 2)
                if (terrain.water(x, z)) { bad.add(name + " stands in water"); x = box.maxX() + 1; break; }
        }
        if (homes >= 5 && noisyHomes * 4 > homes) bad.add(noisyHomes + " of " + homes + " houses are within 12 of a smithy");
        // connectivity: every paved street joins a paved parent at its start
        for (Street st : plan.streets) {
            if (plan.laid.getOrDefault(st.id, -1) < 0 || st.parent < 0) continue;
            if (plan.laid.getOrDefault(st.parent, -1) < st.parentAt) bad.add("street " + st.id + " is paved but cut off from street " + st.parent);
        }
        return bad;
    }

    /**
     * Wall checks for every line the town has unlocked: once all its pieces are planned the circuit is closed (bar deep
     * water and cliffs), every high street that reaches it passes through an open gatehouse paved with the street, no
     * piece overlaps a building, a street or the castle ring, buildings keep out of the band, and the walk never steps
     * more than one block between neighbouring columns.
     */
    public static List<String> walls(Settlement s, TownPlan plan, Terrain terrain) {
        List<String> bad = new ArrayList<>();
        BlockPos c = s.center;
        List<Plot> pieces = new ArrayList<>(), others = new ArrayList<>();
        for (Plot p : s.plots) if (Walls.isWallPiece(p)) pieces.add(p); else if (!p.typeId.equals("road")) others.add(p);
        Map<Long, net.minecraft.world.level.block.state.BlockState> world = new HashMap<>();
        Map<Long, Plot> owner = new HashMap<>();
        for (Plot p : pieces) {
            Blueprint bp = p.blueprint();
            for (int i = 0; i < bp.size(); i++) {
                BlockPos w = bp.worldPos(i, p.origin, p.rotation);
                world.put(w.asLong(), bp.state(i));
                if (!bp.state(i).isAir()) {
                    Plot was = owner.put(BlockPos.asLong(w.getX(), 0, w.getZ()), p);
                    if (was != null && was != p) bad.add("wall pieces " + was.id + " and " + p.id + " share column " + w.getX() + "," + w.getZ());
                }
            }
        }
        for (Plot o : others) {
            BoundingBox b = o.box();
            if (o.typeId.equals(livingrealms.settlement.CapitalCastles.ID)) continue;
            for (Plot p : pieces) if (p.covers(b)) { bad.add("wall piece " + p.id + " overlaps " + o.typeId + " " + o.id); break; }
            if (plan.frontage.containsKey(o.id) && plan.inWallBand(b)) bad.add(o.typeId + " " + o.id + " stands in a wall's band");
        }
        for (Plot p : pieces) {
            if (p.columns().stream().anyMatch(k -> Math.abs(BlockPos.getX(k) - c.getX()) <= Zoning.CORE && Math.abs(BlockPos.getZ(k) - c.getZ()) <= Zoning.CORE))
                bad.add("wall piece " + p.id + " is inside the castle ring");
        }
        Set<String> keys = new HashSet<>();
        for (Plot p : pieces) keys.add(p.blueprintKey);
        for (Walls.Line l : Walls.unlocked(s)) {
            List<Walls.Piece> all = Walls.pieces(l);
            boolean complete = all.stream().allMatch(p -> keys.contains(p.key(s)));
            List<Walls.Edge> edges = Walls.edges(l);
            // streets: only high streets, and only through gate passages
            for (Street st : plan.streets) {
                int laid = plan.laid.getOrDefault(st.id, -1);
                if (laid < 0) continue;
                BoundingBox cor = st.corridor(0, laid);
                for (int x = cor.minX(); x <= cor.maxX(); x++) for (int z = cor.minZ(); z <= cor.maxZ(); z++) {
                    Plot w = owner.get(BlockPos.asLong(x, 0, z));
                    if (w != null && !w.typeId.equals(Walls.GATE)) { bad.add("street " + st.id + " runs into wall piece " + w.id + " at " + x + "," + z); x = cor.maxX() + 1; break; }
                }
            }
            if (!complete) continue;
            // closed: every station along the line has wall (or the piece that stands for it) unless water or a cliff defends it
            for (Walls.Edge e : edges) for (int t = 0; t <= e.len(); t++) {
                int x = c.getX() + e.x(t), z = c.getZ() + e.z(t);
                boolean covered = false;
                for (int o = 0; o <= 12 && !covered; o++) for (int k = -2; k <= 2; k++)
                    if (owner.containsKey(BlockPos.asLong(x + (o + k) * e.ux(), 0, z + (o + k) * e.uz()))) { covered = true; break; }
                boolean water = terrain.water(x, z) && terrain.waterDepth(x, z) >= 3;
                boolean cliff = terrain.height(x - 3 * e.ux(), z - 3 * e.uz()) - terrain.height(x + 4 * e.ux(), z + 4 * e.uz()) >= 6;
                if (!covered && !water && !cliff) { bad.add("line " + l.index() + " is open at " + x + "," + z); break; }
            }
            // gatehouses: every high street reaching the line passes through one, open to the sky for PASSAGE blocks
            for (Street st : plan.streets) {
                if (st.cls != RoadClass.HIGH || plan.laid.getOrDefault(st.id, -1) < l.a() - TownPlan.PLAZA + 2) continue;
                int t = l.a() - TownPlan.PLAZA;
                int gx = st.x(t), gz = st.z(t);
                Plot g = owner.get(BlockPos.asLong(gx, 0, gz));
                if (g == null || !g.typeId.equals(Walls.GATE)) { bad.add("high street " + st.id + " has no gatehouse where it meets line " + l.index()); continue; }
                for (int w = -st.half(); w <= st.cls.width - 1 - st.half(); w++) for (int k = -1; k <= 1; k++) {
                    int x = gx + (st.alongX() ? k : w), z = gz + (st.alongX() ? w : k);
                    int ground = terrain.height(x, z);
                    var floor = world.get(BlockPos.asLong(x, ground, z));
                    if (floor == null || floor.isAir()) bad.add("gate on street " + st.id + " is not paved at " + x + "," + z);
                    for (int y = ground + 1; y <= ground + Walls.PASSAGE; y++) {
                        var st2 = world.get(BlockPos.asLong(x, y, z));
                        if (st2 != null && !st2.isAir()) { bad.add("gate on street " + st.id + " is blocked at " + x + "," + y + "," + z); y = 9999; w = 99; k = 99; }
                    }
                }
            }
            // the walk steps at most one block between neighbouring columns of a wall section
            for (Walls.Edge e : edges) {
                Integer prev = null;
                for (int t = 0; t <= e.len(); t++) {
                    long col = BlockPos.asLong(c.getX() + e.x(t), 0, c.getZ() + e.z(t));
                    Plot w = owner.get(col);
                    if (w == null || !w.typeId.equals(Walls.WALL)) { prev = null; continue; }
                    int top = Integer.MIN_VALUE, low = Integer.MAX_VALUE;
                    for (int y = -64; y < 320; y++) {
                        var st2 = world.get(BlockPos.asLong(BlockPos.getX(col), y, BlockPos.getZ(col)));
                        if (st2 != null && !st2.isAir()) { top = Math.max(top, y); low = Math.min(low, y); }
                    }
                    int ground = terrain.height(BlockPos.getX(col), BlockPos.getZ(col));
                    if (top < ground + Walls.H || low > ground) bad.add("wall at " + BlockPos.getX(col) + "," + BlockPos.getZ(col) + " floats or is buried (" + low + ".." + top + ", ground " + ground + ")");
                    if (prev != null && Math.abs(top - prev) > 1) bad.add("wall walk steps " + Math.abs(top - prev) + " at " + BlockPos.getX(col) + "," + BlockPos.getZ(col));
                    prev = top;
                }
            }
        }
        return bad;
    }

    static boolean plazaFront(TownPlan plan, Plot p) {
        Integer sid = plan.frontage.get(p.id);
        return sid != null && plan.streets.get(sid).cls == RoadClass.PLAZA;
    }

    /** Which way a lot beside a street looks to face it. */
    public static Direction towardStreet(Street st, BoundingBox box) {
        if (st.alongX()) return (box.minZ() + box.maxZ()) / 2.0 > st.az ? Direction.NORTH : Direction.SOUTH;
        return (box.minX() + box.maxX()) / 2.0 > st.ax ? Direction.WEST : Direction.EAST;
    }

    /** Blocks between the road's edge and the building's front wall. */
    static int gap(Street st, BoundingBox box, Direction front) {
        int h = st.half(), lo = st.cls.width - 1 - h;
        return switch (front) {
            case NORTH -> box.minZ() - (st.az + lo) - 1;
            case SOUTH -> (st.az - h) - box.maxZ() - 1;
            case WEST -> box.minX() - (st.ax + lo) - 1;
            default -> (st.ax - h) - box.maxX() - 1;
        };
    }

    private PlanAudit() {}
}
