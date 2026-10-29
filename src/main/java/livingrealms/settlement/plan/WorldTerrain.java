package livingrealms.settlement.plan;

import livingrealms.building.Blueprint;
import livingrealms.building.Construction;
import livingrealms.settlement.AutoBuild;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;

/** The planner's view of a loaded world. Never loads chunks. */
public record WorldTerrain(ServerLevel level) implements Terrain {
    public int height(int x, int z) { return Construction.ground(level, x, z); }

    public boolean water(int x, int z) {
        return !level.getBlockState(new BlockPos(x, Construction.ground(level, x, z), z)).getFluidState().isEmpty();
    }

    public int waterDepth(int x, int z) {
        int y = Construction.ground(level, x, z), d = 0;
        while (d < 8 && !level.getBlockState(new BlockPos(x, y - d, z)).getFluidState().isEmpty()) d++;
        return d;
    }

    public boolean built(int x, int z) {
        int y = Construction.ground(level, x, z);
        for (int dy = 0; dy <= 3; dy++) {
            var st = level.getBlockState(new BlockPos(x, y + dy, z));
            if (!Construction.natural(st) && !livingrealms.settlement.Planning.ROAD_MATERIALS.containsValue(st) && !st.is(net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS)) return true;
        }
        return false;
    }

    public boolean loaded(int x0, int z0, int x1, int z1) { return level.hasChunksAt(x0, z0, x1, z1); }

    public boolean clear(BlockPos origin, Blueprint bp, Rotation rot) { return AutoBuild.clear(level, origin, bp, rot); }
}
