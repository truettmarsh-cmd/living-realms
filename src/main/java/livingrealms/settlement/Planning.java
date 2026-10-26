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
    public static final Map<String, BlockState> ROAD_MATERIALS = new LinkedHashMap<>();

    static {
        ROAD_MATERIALS.put("path", Blocks.DIRT_PATH.defaultBlockState());
        ROAD_MATERIALS.put("gravel", Blocks.GRAVEL.defaultBlockState());
        ROAD_MATERIALS.put("cobblestone", Blocks.COBBLESTONE.defaultBlockState());
        ROAD_MATERIALS.put("stone_bricks", Blocks.STONE_BRICKS.defaultBlockState());
        ROAD_MATERIALS.put("planks", Blocks.SPRUCE_PLANKS.defaultBlockState());
    }

    /** Plans a building with its bounding box corner at origin (before sinking). Returns an error or null. */
    @Nullable
    public static String plan(ServerLevel level, Settlement s, BuildingType t, BlockPos origin, Rotation rot, boolean ai) {
        if (t.id.equals(CapitalCastles.ID)) {
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
        p.foundation = Construction.foundation(level, bp, o, rot);
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
        // roads never block: buildings replace road blocks under them, and roads skip buildings
        if (p.typeId.equals("road")) return null;
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

    /** A road of the given width from a to b following the ground. */
    @Nullable
    public static String road(ServerLevel level, Settlement s, BlockPos a, BlockPos b, int width, String material) {
        BlockState surface = ROAD_MATERIALS.getOrDefault(material, Blocks.DIRT_PATH.defaultBlockState());
        int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        int len = Math.max(Math.abs(dx), Math.abs(dz));
        if (len < 1) return "Too short";
        if (len > 128) return "Too long (128 blocks at most)";
        if (!s.contains(a) || !s.contains(b)) return "Outside the town's land";
        width = Math.max(1, Math.min(7, width));
        Map<Long, Integer> cols = new LinkedHashMap<>();
        double nx = -dz / Math.hypot(dx, dz), nz = dx / Math.hypot(dx, dz);
        for (int i = 0; i <= len; i++) {
            double cx = a.getX() + dx * (double) i / len, cz = a.getZ() + dz * (double) i / len;
            for (int w = 0; w < width; w++) {
                double off = w - (width - 1) / 2.0;
                for (double sub = 0; sub <= 0.5; sub += 0.5) {
                    int x = (int) Math.floor(cx + nx * (off + sub) + 0.5), z = (int) Math.floor(cz + nz * (off + sub) + 0.5);
                    if (insideBuilding(s, x, z)) continue;
                    cols.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> Construction.ground(level, x, z));
                }
            }
        }
        // keep off generated villages' houses and anything else built
        cols.entrySet().removeIf(e -> {
            BlockState g = level.getBlockState(new BlockPos(BlockPos.getX(e.getKey()), e.getValue(), BlockPos.getZ(e.getKey())));
            return !Construction.natural(g) && !ROAD_MATERIALS.containsValue(g) && !g.is(Blocks.SPRUCE_PLANKS);
        });
        if (cols.isEmpty()) return "Nothing to pave";
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var e : cols.entrySet()) {
            int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey()), y = e.getValue();
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        List<Blueprint.Entry> entries = new ArrayList<>();
        for (var e : cols.entrySet()) {
            int x = BlockPos.getX(e.getKey()) - minX, z = BlockPos.getZ(e.getKey()) - minZ, y = e.getValue() - minY;
            BlockPos ground = new BlockPos(x + minX, e.getValue(), z + minZ);
            BlockState below = level.getBlockState(ground);
            // water crossings become plank bridges
            BlockState here = below.getFluidState().isEmpty() ? surface : Blocks.SPRUCE_PLANKS.defaultBlockState();
            entries.add(new Blueprint.Entry(x, y, z, here));
            for (int up = 1; up <= 3; up++) entries.add(new Blueprint.Entry(x, y + up, z, Blocks.AIR.defaultBlockState()));
        }
        Plot p = new Plot();
        p.id = s.nextId;
        p.typeId = "road";
        p.blueprintKey = "road:" + s.id + ":" + p.id;
        Blueprint bp = new Blueprint(p.blueprintKey, maxX - minX + 1, maxY - minY + 4, maxZ - minZ + 1, entries);
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

    static boolean insideBuilding(Settlement s, int x, int z) {
        for (Plot o : s.plots) {
            if (o.typeId.equals("road")) continue;
            BoundingBox b = o.box();
            if (o.typeId.equals(CapitalCastles.ID) && CapitalCastles.inCourtyard(o, new BoundingBox(x, 0, z, x, 0, z))) continue;
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
            Plot probe = new Plot();
            probe.typeId = p.typeId;
            probe.blueprintKey = key;
            probe.origin = p.origin;
            probe.rotation = p.rotation;
            String err = checkSite(s, probe, p);
            if (err != null) return err;
            p.blueprintKey = key;
            p.state = Plot.State.UPGRADING;
            p.placed = 0;
            p.paid = 0;
            p.foundation = Construction.foundation(level, bp, p.origin, p.rotation);
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
        p.state = Plot.State.REPAIRING;
        p.placed = 0;
        p.paid = 0;
        s.dirty = true;
    }

    public static void demolish(Settlement s, Plot p) {
        if (p.state == Plot.State.BUILDING && p.placed == 0) {
            s.plots.remove(p);
        } else {
            p.state = Plot.State.DEMOLISHING;
            p.placed = 0;
            p.paid = 0;
        }
        s.dirty = true;
    }

    private Planning() {}
}
