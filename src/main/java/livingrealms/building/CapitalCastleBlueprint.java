package livingrealms.building;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;

/** Original courtyard castle inspired by Bodiam's published ground plan. */
final class CapitalCastleBlueprint {
    static void make(Procedural.B b) {
        var stone = Blocks.STONE_BRICKS.defaultBlockState();
        // The central 25 x 25 court has NO entries, including air: preserve the village center.
        b.fill(0, 0, 0, 40, 6, 2, stone);
        b.fill(0, 0, 38, 40, 6, 40, stone);
        b.fill(0, 0, 3, 2, 6, 37, stone);
        b.fill(38, 0, 3, 40, 6, 37, stone);
        for (int i = 0; i <= 40; i += 2) {
            b.set(i, 7, 0, stone); b.set(i, 7, 40, stone);
            b.set(0, 7, i, stone); b.set(40, 7, i, stone);
        }
        // Corner towers with accessible ladders, roof hatches and crenellations.
        for (int x : new int[]{0, 34}) for (int z : new int[]{0, 34}) {
            b.body(x, z, x + 6, z + 6, 11, stone, false);
            b.fill(x, 10, z, x + 6, 10, z + 6, stone);
            b.clear(x + 3, 1, z + (z == 0 ? 6 : 0), x + 3, 3, z + (z == 0 ? 6 : 0));
            for (int y = 1; y <= 10; y++) b.set(x + 1, y, z + 3,
                    Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST));
            b.set(x + 1, 11, z + 3, Blocks.OAK_TRAPDOOR.defaultBlockState());
            for (int i = 0; i <= 6; i += 2) {
                b.set(x + i, 12, z, stone); b.set(x + i, 12, z + 6, stone);
                b.set(x, 12, z + i, stone); b.set(x + 6, 12, z + i, stone);
            }
            b.set(x + 3, 1, z + 3, Blocks.LANTERN);
        }
        // Great hall along the northern curtain; guard rooms beside the southern gate.
        room(b, 8, 1, 32, 7, 7);
        b.clear(19, 1, 7, 21, 3, 7);
        for (int x = 11; x <= 29; x += 3) {
            b.set(x, 1, 4, b.p.floor());
            b.stairs(x, 1, 5, b.p.plankStairs(), Direction.NORTH, net.minecraft.world.level.block.state.properties.Half.BOTTOM);
        }
        room(b, 8, 34, 16, 40, 5); room(b, 24, 34, 32, 40, 5);
        b.door(12, 1, 34, Direction.NORTH); b.door(28, 1, 34, Direction.NORTH);
        for (int x : new int[]{10, 14, 26, 30}) b.bed(x, 1, 37, Direction.SOUTH);
        b.fill(17, 5, 36, 23, 8, 40, stone);
        b.path(18, 33, 22, 40);
        b.clear(18, 1, 33, 22, 4, 40);
        // Rear postern keeps the central village's paths usable in both directions.
        b.clear(19, 1, 0, 21, 3, 1);
        for (int x = 17; x <= 23; x += 2) b.set(x, 9, 40, stone);
    }

    private static void room(Procedural.B b, int x, int z, int xx, int zz, int h) {
        b.body(x, z, xx, zz, h, Blocks.STONE_BRICKS.defaultBlockState(), false);
        b.fill(x, h, z, xx, h, zz, b.p.floor());
        b.windows(x, z, xx, zz, 3, 4);
        b.set(x + 2, 1, z + 2, Blocks.LANTERN);
    }
}
