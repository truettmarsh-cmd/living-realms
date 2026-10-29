package livingrealms.settlement;

import livingrealms.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Capital projects use ordinary paid villager construction, never instant placement. */
public final class CapitalCastles {
    public static final String ID = "livingrealms:capital_castle";

    public static void tick(ServerLevel level, Settlement s) {
        if (!s.capital || s.tier != Tier.CAPITAL || !level.hasChunkAt(s.center)) return;
        for (Plot p : s.plots) if (p.typeId.equals(ID)) {
            s.castleStatus = p.state == Plot.State.BUILT ? "Capital Castle complete" :
                    "Capital Castle: " + p.state.name().toLowerCase(java.util.Locale.ROOT) + " · " + (int)(100*p.progress()) + "%";
            return;
        }
        if (!s.isAi() && (!s.autoBuild || s.plots.stream().anyMatch(AutoBuild::pending))) return;
        String reason = plan(level, s);
        if (reason == null && !s.isAi()) s.plots.get(s.plots.size() - 1).automatic = true;
        String status = reason == null ? "Builders have planned the central Capital Castle" : "Capital Castle: " + reason;
        if (!status.equals(s.castleStatus)) { s.castleStatus = status; s.log(status); s.dirty = true; }
    }

    public static String plan(ServerLevel level, Settlement s) {
        if (s.tier != Tier.CAPITAL) return "Requires Capital rank";
        BuildingType t = BuildingCatalog.get(ID);
        if (t == null) return "Blueprint unavailable";
        String why = s.cannotBuild(t);
        if (why != null) return why;
        int x = s.center.getX() - 20, z = s.center.getZ() - 20;
        if (!level.hasChunksAt(x, z, x + 40, z + 40)) return "Waiting for the central district to load";
        int[] heights = {Construction.ground(level, x, z), Construction.ground(level, x+40,z),
                Construction.ground(level,x,z+40), Construction.ground(level,x+40,z+40)};
        java.util.Arrays.sort(heights);
        if (heights[3] - heights[0] > 4) return "Level the four corners of the central 41 x 41 district";
        BlockPos origin = new BlockPos(x, heights[2], z);
        why = site(level, s, t, origin, Rotation.NONE);
        if (why != null) return why;
        why = Planning.plan(level, s, t, origin, Rotation.NONE, s.isAi());
        if (why == null) s.plots.get(s.plots.size()-1).priority = 9;
        return why;
    }

    /** Also applied to manually planned castles; checks every touched block, not sparse samples. */
    public static String site(ServerLevel level, Settlement s, BuildingType t, BlockPos origin, Rotation rotation) {
        if (origin.getX() + 20 != s.center.getX() || origin.getZ() + 20 != s.center.getZ())
            return "The castle courtyard must be centered on the village";
        Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1,s.style));
        Plot probe = new Plot(); probe.typeId = ID; probe.blueprintKey = bp.key; probe.origin = origin; probe.rotation = rotation;
        String why = Planning.checkSite(s, probe, null);
        if (why != null) return "Clear the castle perimeter: " + why;
        if (!Construction.loaded(level, probe)) return "Waiting for the central district to load";
        for (int i = 0; i < bp.size(); i++) {
            BlockPos pos = bp.worldPos(i, origin, rotation);
            if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) return "Outside build height";
            var state = level.getBlockState(pos);
            if (level.getBlockEntity(pos) != null || (!Construction.natural(state) && !Planning.ROAD_MATERIALS.containsValue(state)))
                return "Clear the castle perimeter at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        }
        return null;
    }

    /** Courtyard is intentionally absent from the blueprint and remains usable town land. */
    public static boolean inCourtyard(Plot castle, BoundingBox other) {
        var b = castle.box();
        return other.minX() >= b.minX()+8 && other.maxX() <= b.minX()+32
                && other.minZ() >= b.minZ()+8 && other.maxZ() <= b.minZ()+32;
    }

    public static boolean overlaps(Plot a, Plot b) {
        var aa=a.box(); var bb=b.box();
        if (aa.minX()>bb.maxX() || aa.maxX()<bb.minX() || aa.minZ()>bb.maxZ() || aa.maxZ()<bb.minZ()) return false;
        // wall pieces take only their own columns
        var ca=a.columns(); var cb=b.columns();
        if (ca!=null && cb!=null) { for (long c : ca) if (cb.contains(c)) return true; return false; }
        if (ca!=null) return a.covers(bb);
        if (cb!=null) return b.covers(aa);
        return !(a.typeId.equals(ID) && inCourtyard(a,bb)) && !(b.typeId.equals(ID) && inCourtyard(b,aa));
    }

    /** Keep the future perimeter free when autonomous villages choose ordinary building sites. */
    public static boolean reserved(Settlement s, int x0, int z0, int x1, int z1) {
        int x=s.center.getX(), z=s.center.getZ();
        if (x1<x-20 || x0>x+20 || z1<z-20 || z0>z+20) return false;
        return !(x0>=x-12 && x1<=x+12 && z0>=z-12 && z1<=z+12);
    }
    private CapitalCastles() {}
}
