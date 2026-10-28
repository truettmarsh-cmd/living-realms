package livingrealms.settlement.plan;

import livingrealms.building.*;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/** Checks that a town laid out by its plan makes sense; returns what is wrong (empty when all is well). */
public final class PlanAudit {
    public static List<String> audit(Settlement s, TownPlan plan, Terrain terrain) {
        List<String> bad = new ArrayList<>();
        List<Plot> buildings = new ArrayList<>();
        for (Plot p : s.plots) if (!p.typeId.equals("road") && p.state != Plot.State.DEMOLISHING) buildings.add(p);
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
