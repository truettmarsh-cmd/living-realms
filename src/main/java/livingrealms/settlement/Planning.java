package livingrealms.settlement;

import livingrealms.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** City planning: placing, upgrading, repairing and tearing down buildings, and laying roads. */
public final class Planning {
    /** Map clicks choose X/Z; server terrain and normal permissions determine the real foundation height. */
    public static String planMap(ServerLevel level, Settlement s, String id, int x, int z, int rotation) {
        BuildingType t = BuildingCatalog.get(id);
        if (t == null) return "Unknown building";
        if (rotation < 0 || rotation > 3) return "Invalid rotation";
        if (Math.abs((long)x - s.center.getX()) > s.radius() || Math.abs((long)z - s.center.getZ()) > s.radius()
                || !s.contains(new BlockPos(x, s.center.getY(), z))) return "Outside the town's land";
        Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
        if (bp == null) return "Blueprint missing";
        Rotation rot = Rotation.values()[rotation];
        if (!level.hasChunksAt(x, z, x + bp.width(rot), z + bp.depth(rot))) return "Visit this area so its terrain is loaded";
        int y = Construction.ground(level, x + bp.width(rot) / 2, z + bp.depth(rot) / 2);
        BlockPos at = new BlockPos(x, y, z);
        if (!AutoBuild.clear(level, at.below(t.sink), bp, rot)) return "Choose a clear site; existing buildings or containers block this footprint";
        return plan(level, s, t, at, rot, false);
    }
    public static final Map<String, BlockState> ROAD_MATERIALS = new LinkedHashMap<>();

    static {
        ROAD_MATERIALS.put("path", Blocks.DIRT_PATH.defaultBlockState());
        ROAD_MATERIALS.put("gravel", Blocks.GRAVEL.defaultBlockState());
        ROAD_MATERIALS.put("cobblestone", Blocks.COBBLESTONE.defaultBlockState());
        ROAD_MATERIALS.put("stone_bricks", Blocks.STONE_BRICKS.defaultBlockState());
        ROAD_MATERIALS.put("planks", Blocks.SPRUCE_PLANKS.defaultBlockState());
    }

    /** Plans a building with its bounding box corner at origin (before sinking). Returns an error or null. A null level
     * plans an unsited building on a surveyed site whose foundation is measured once it loads. */
    @Nullable
    public static String plan(@Nullable ServerLevel level, Settlement s, BuildingType t, BlockPos origin, Rotation rot, boolean ai) {
        if (t.id.equals(CapitalCastles.ID)) {
            if (level == null) return "The castle needs a loaded site";
            String site = CapitalCastles.site(level, s, t, origin, rot);
            if (site != null) return site;
        }
        String why = s.cannotBuild(t);
        if (why != null) return why;
        String key = t.blueprintKey(1, s.style);
        Blueprint bp = BuildingCatalog.blueprint(key);
        if (bp == null) return "Blueprint missing";
        BlockPos o = origin.below(t.sink);
        Plot p = new Plot();
        p.typeId = t.id;
        p.blueprintKey = key;
        p.origin = o;
        p.rotation = rot;
        p.ai = ai;
        String err = checkSite(s, p, null);
        if (err != null) return err;
        p.id = s.nextId++;
        p.state = Plot.State.BUILDING;
        if (level != null) {
            // the lot is levelled as part of the project: dug out, built up, an apron at the door
            Construction.Earthworks works = Construction.earthworks(level, s, t, bp, o, rot);
            p.foundation = works.fill();
            p.cut = works.cut();
        }
        p.unsited = level == null;
        p.priority = t.category == Category.CIVIC ? 10 : 0;
        s.plots.add(p);
        if (!ai) s.log("Planned a " + t.name);
        s.dirty = true;
        return null;
    }

    /** Territory and overlap checks. */
    @Nullable
    static String checkSite(Settlement s, Plot p, @Nullable Plot self) {
        BoundingBox box = p.box();
        int r = s.radius() + 4;
        for (int[] c : new int[][]{{box.minX(), box.minZ()}, {box.maxX(), box.minZ()}, {box.minX(), box.maxZ()}, {box.maxX(), box.maxZ()}}) {
            long dx = c[0] - s.center.getX(), dz = c[1] - s.center.getZ();
            if (dx * dx + dz * dz > (long) r * r) return "Outside the town's land";
        }
        // roads never block (they skip buildings), but buildings keep off laid streets
        if (p.typeId.equals("road")) return null;
        if (!p.typeId.equals(CapitalCastles.ID) && onRoad(s, box)) return "Blocks a street";
        for (Plot o : s.plots) {
            if (o == self || o.state == Plot.State.DEMOLISHING || o.typeId.equals("road")) continue;
            BoundingBox ob = o.box();
            if (CapitalCastles.overlaps(p, o)) {
                BuildingType ot = BuildingCatalog.get(o.typeId);
                return "Overlaps " + (o.typeId.equals("road") ? "a road" : ot == null ? "a building" : ot.name);
            }
        }
        return null;
    }

    /** Most a road digs down into a hillside before it climbs over instead. */
    static final int ROAD_DIG = 10;

    /**
     * A road of the given width from a to b following the ground, graded so it never steps more than one block between
     * neighbouring rows: each row is level across, cut into the ground or built up on the foundation block where the
     * ground is too steep, with plank bridges over water.
     */
    @Nullable
    public static String road(ServerLevel level, Settlement s, BlockPos a, BlockPos b, int width, String material) {
        BlockState surface = ROAD_MATERIALS.getOrDefault(material, Blocks.DIRT_PATH.defaultBlockState());
        int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        int len = Math.max(Math.abs(dx), Math.abs(dz));
        if (len < 1) return "Too short";
        if (len > 128) return "Too long (128 blocks at most)";
        if (!s.contains(a) || !s.contains(b)) return "Outside the town's land";
        width = Math.max(1, Math.min(7, width));
        // column -> {ground, row}
        Map<Long, int[]> cols = new LinkedHashMap<>();
        double nx = -dz / Math.hypot(dx, dz), nz = dx / Math.hypot(dx, dz);
        for (int i = 0; i <= len; i++) {
            double cx = a.getX() + dx * (double) i / len, cz = a.getZ() + dz * (double) i / len;
            for (int w = 0; w < width; w++) {
                double off = w - (width - 1) / 2.0;
                for (double sub = 0; sub <= 0.5; sub += 0.5) {
                    int x = (int) Math.floor(cx + nx * (off + sub) + 0.5), z = (int) Math.floor(cz + nz * (off + sub) + 0.5);
                    if (insideBuilding(s, x, z)) continue;
                    int row = i;
                    cols.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> new int[]{Construction.soil(level, x, z), row});
                }
            }
        }
        // keep off generated villages' houses and anything else built
        cols.entrySet().removeIf(e -> {
            BlockState g = level.getBlockState(new BlockPos(BlockPos.getX(e.getKey()), e.getValue()[0], BlockPos.getZ(e.getKey())));
            return !Construction.natural(g) && !ROAD_MATERIALS.containsValue(g) && !g.is(Blocks.SPRUCE_PLANKS);
        });
        if (cols.isEmpty()) return "Nothing to pave";
        // the ground along the road (each row's middle height), then graded
        List<List<Integer>> rows = new ArrayList<>();
        for (int i = 0; i <= len; i++) rows.add(new ArrayList<>());
        for (int[] v : cols.values()) rows.get(v[1]).add(v[0]);
        int[] g = new int[len + 1];
        int last = Integer.MIN_VALUE;
        for (int i = 0; i <= len; i++) {
            List<Integer> r = rows.get(i);
            if (r.isEmpty()) { g[i] = last; continue; }
            r.sort(null);
            g[i] = last = r.get(r.size() / 2);
        }
        for (int i = len; i >= 0; i--) if (g[i] == Integer.MIN_VALUE) g[i] = i < len ? g[i + 1] : last;
        int[] grade = livingrealms.settlement.plan.Street.grade(g);
        BlockState bank = Construction.foundationBlock(s), air = Blocks.AIR.defaultBlockState();
        List<int[]> abs = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        for (var e : cols.entrySet()) {
            int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey()), gy = e.getValue()[0], h = grade[e.getValue()[1]];
            boolean wet = !level.getBlockState(new BlockPos(x, gy, z)).getFluidState().isEmpty();
            // water crossings become plank bridges, never dams
            if (wet) h = Math.max(h, gy);
            else for (int y = gy + 1; y < h; y++) { abs.add(new int[]{x, y, z}); states.add(bank); }
            abs.add(new int[]{x, h, z});
            states.add(wet ? Blocks.SPRUCE_PLANKS.defaultBlockState() : surface);
            int top = Math.max(h + 3, Math.min(gy + 1, h + ROAD_DIG));
            for (int y = h + 1; y <= top; y++) { abs.add(new int[]{x, y, z}); states.add(air); }
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] q : abs) {
            minX = Math.min(minX, q[0]); maxX = Math.max(maxX, q[0]);
            minY = Math.min(minY, q[1]); maxY = Math.max(maxY, q[1]);
            minZ = Math.min(minZ, q[2]); maxZ = Math.max(maxZ, q[2]);
        }
        List<Blueprint.Entry> entries = new ArrayList<>();
        for (int i = 0; i < abs.size(); i++) entries.add(new Blueprint.Entry(abs.get(i)[0] - minX, abs.get(i)[1] - minY, abs.get(i)[2] - minZ, states.get(i)));
        Plot p = new Plot();
        p.id = s.nextId;
        p.typeId = "road";
        p.blueprintKey = "road:" + s.id + ":" + p.id;
        Blueprint bp = new Blueprint(p.blueprintKey, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, entries);
        BuildingCatalog.putBlueprint(bp);
        p.customBlueprint = bp.toTag();
        p.origin = new BlockPos(minX, minY, minZ);
        String err = checkSite(s, p, null);
        if (err != null) return err;
        s.nextId++;
        p.state = Plot.State.BUILDING;
        p.priority = -1;
        s.plots.add(p);
        s.log("Laid out a road (" + (len + 1) + " blocks)");
        s.dirty = true;
        return null;
    }

    /** Territory and overlap checks for a wall piece: its own columns inside the land, clear of every other plot. */
    @Nullable
    public static String checkWall(Settlement s, Plot p) {
        var cols = p.columns();
        long r = s.radius() + 6;
        for (long c : cols) {
            long dx = BlockPos.getX(c) - s.center.getX(), dz = BlockPos.getZ(c) - s.center.getZ();
            if (dx * dx + dz * dz > r * r) return "Outside the town's land";
        }
        for (Plot o : s.plots) {
            if (o.state == Plot.State.DEMOLISHING || o.typeId.equals("road")) continue;
            if (CapitalCastles.overlaps(p, o)) {
                BuildingType ot = BuildingCatalog.get(o.typeId);
                return "Overlaps " + (ot == null ? "a building" : ot.name);
            }
        }
        return null;
    }

    /** True if any paved column of a road project lies inside the footprint. */
    static boolean onRoad(Settlement s, BoundingBox box) {
        for (Plot o : s.plots) {
            if (!o.typeId.equals("road") || o.state == Plot.State.DEMOLISHING) continue;
            BoundingBox rb = o.box();
            if (rb.maxX() < box.minX() || rb.minX() > box.maxX() || rb.maxZ() < box.minZ() || rb.minZ() > box.maxZ()) continue;
            Blueprint bp = o.blueprint();
            if (bp == null) continue;
            for (int i = 0; i < bp.size(); i++) {
                if (bp.state(i).isAir()) continue;
                int x = o.origin.getX() + bp.lx(i), z = o.origin.getZ() + bp.lz(i);
                if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) return true;
            }
        }
        return false;
    }

    /** Columns paved by road projects overlapping the area. */
    public static java.util.Set<Long> roadColumns(Settlement s, BoundingBox area) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (Plot o : s.plots) {
            if (!o.typeId.equals("road") || o.state == Plot.State.DEMOLISHING) continue;
            BoundingBox rb = o.box();
            if (rb.maxX() < area.minX() || rb.minX() > area.maxX() || rb.maxZ() < area.minZ() || rb.minZ() > area.maxZ()) continue;
            Blueprint bp = o.blueprint();
            if (bp == null) continue;
            for (int i = 0; i < bp.size(); i++) if (!bp.state(i).isAir()) out.add(BlockPos.asLong(o.origin.getX() + bp.lx(i), 0, o.origin.getZ() + bp.lz(i)));
        }
        return out;
    }

    public static boolean insideBuilding(Settlement s, int x, int z) {
        for (Plot o : s.plots) {
            if (o.typeId.equals("road")) continue;
            BoundingBox b = o.box();
            if (o.typeId.equals(CapitalCastles.ID) && CapitalCastles.inCourtyard(o, new BoundingBox(x, 0, z, x, 0, z))) continue;
            var cols = o.columns();
            if (cols != null) { if (cols.contains(BlockPos.asLong(x, 0, z))) return true; continue; }
            if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) return true;
        }
        return false;
    }

    @Nullable
    public static String upgrade(ServerLevel level, Settlement s, Plot p) {
        BuildingType t = BuildingCatalog.get(p.typeId);
        if (t == null) return "Unknown building";
        if (p.state != Plot.State.BUILT) return "Finish it first";
        if (p.level >= s.maxLevel(t)) return p.level >= t.maxLevel ? "Already at its best" : "Needs a higher town rank";
        if (t.procedural()) {
            String key = t.blueprintKey(p.level + 1, s.style);
            Blueprint bp = BuildingCatalog.blueprint(key);
            if (bp == null) return "Blueprint missing";
            // the front wall stays on its street and the building stays centred along it
            Blueprint cur = p.blueprint();
            BlockPos grown = cur == null ? p.origin : livingrealms.settlement.plan.TownPlan.grownOrigin(p.origin, p.rotation, cur, bp);
            Plot probe = new Plot();
            probe.typeId = p.typeId;
            probe.blueprintKey = key;
            probe.origin = grown;
            probe.rotation = p.rotation;
            String err = checkSite(s, probe, p);
            if (err != null) return err;
            BoundingBox standing = p.box();
            p.origin = grown;
            p.blueprintKey = key;
            p.automatic = false;
            p.state = Plot.State.UPGRADING;
            p.placed = 0;
            p.paid = 0;
            Construction.Earthworks works = Construction.earthworks(level, s, t, bp, p.origin, p.rotation, standing);
            p.foundation = works.fill();
            p.cut = works.cut();
            s.log("Builders are enlarging the " + t.name + " to level " + (p.level + 1));
        } else {
            Map<net.minecraft.world.item.Item, Integer> fee = renovationFee(p.level);
            if (!s.isAi()) {
                for (var e : fee.entrySet()) if (!s.stock.has(e.getKey(), e.getValue()))
                    return "Needs " + e.getValue() + " " + e.getKey().getDescription().getString();
                fee.forEach((it, n) -> s.stock.take(it, n));
            }
            p.level++;
            s.log("Renovated the " + t.name + " to level " + p.level);
        }
        s.dirty = true;
        return null;
    }

    public static Map<net.minecraft.world.item.Item, Integer> renovationFee(int level) {
        Map<net.minecraft.world.item.Item, Integer> m = new LinkedHashMap<>();
        m.put(Items.OAK_PLANKS, 24 * level);
        m.put(Items.COBBLESTONE, 24 * level);
        m.put(Items.EMERALD, 2 * level);
        return m;
    }

    public static void repair(Settlement s, Plot p) {
        if (p.state != Plot.State.BUILT) return;
        p.automatic = false;
        p.state = Plot.State.REPAIRING;
        p.placed = 0;
        p.paid = 0;
        s.dirty = true;
    }

    public static void demolish(Settlement s, Plot p) {
        p.deferred = false;
        if (p.state == Plot.State.BUILDING && p.placed == 0) {
            s.plots.remove(p);
        } else {
            p.automatic = false;
        p.state = Plot.State.DEMOLISHING;
            p.placed = 0;
            p.paid = 0;
        }
        s.dirty = true;
    }

    private Planning() {}
}
