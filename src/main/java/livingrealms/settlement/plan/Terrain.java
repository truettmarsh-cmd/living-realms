package livingrealms.settlement.plan;

import livingrealms.building.Blueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

/** What the planner needs to know about the ground; the world in play, a synthetic grid in the self-test. */
public interface Terrain {
    /** Surface block height (the block you stand on). */
    int height(int x, int z);
    boolean water(int x, int z);
    boolean loaded(int x0, int z0, int x1, int z1);
    /** No player builds, containers or village houses where the blueprint goes. */
    boolean clear(BlockPos origin, Blueprint bp, Rotation rot);
    /** Depth of the water at a wet column (1 = a puddle, 3 or more = deep enough to defend a wall line). */
    default int waterDepth(int x, int z) { return water(x, z) ? 3 : 0; }
    /** Someone's build (not terrain, not a road) stands on this column. */
    default boolean built(int x, int z) { return false; }

    /** Flat ground everywhere at the given height, optionally with water where {@code water} says. */
    static Terrain grid(java.util.function.IntBinaryOperator height, java.util.function.BiPredicate<Integer, Integer> water) {
        return new Terrain() {
            public int height(int x, int z) { return height.applyAsInt(x, z); }
            public boolean water(int x, int z) { return water.test(x, z); }
            public boolean loaded(int x0, int z0, int x1, int z1) { return true; }
            public boolean clear(BlockPos origin, Blueprint bp, Rotation rot) { return true; }
        };
    }
}
