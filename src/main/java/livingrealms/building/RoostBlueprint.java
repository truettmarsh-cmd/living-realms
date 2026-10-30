package livingrealms.building;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * The Dragon Roost (docs/dragons.md): a 21 x 21 stone court open to the sky, with a raised nest of hay and a gold hoard in
 * the middle, four lantern pillars hung with banners and a roofed keepers' lean-to along the back. The front (south)
 * is open for the keepers. Mostly stone, so it hardly burns; the nest is a Dragon Bond nest when that mod is installed.
 */
public final class RoostBlueprint {
    public static final String KIND = "dragon_roost";
    public static final int SIZE = 21, MID = 10;

    static void make(Procedural.B b) {
        var stone = b.p.stone();
        int s = SIZE - 1;
        b.clear(0, 1, 0, s, 12, s);
        b.fill(0, 0, 0, s, 0, s, b.p.foundation());
        b.fill(1, 0, 1, s - 1, 0, s - 1, stone);
        // a low parapet, open at the front
        for (int i = 0; i <= s; i++) {
            if (i < MID - 2 || i > MID + 2) b.set(i, 1, s, b.p.stoneWall().defaultBlockState());
            b.set(i, 1, 0, b.p.stoneWall().defaultBlockState());
            b.set(0, 1, i, b.p.stoneWall().defaultBlockState());
            b.set(s, 1, i, b.p.stoneWall().defaultBlockState());
        }
        // lantern pillars with banners facing the court
        for (int[] c : new int[][]{{0, 0}, {s, 0}, {0, s}, {s, s}}) {
            b.fill(c[0], 1, c[1], c[0], 6, c[1], stone);
            b.slab(c[0], 7, c[1], b.p.stoneSlab(), SlabType.BOTTOM);
            b.lantern(c[0], 8, c[1], false);
        }
        b.wallBanner(1, 5, 0 + 1, Direction.SOUTH);
        b.wallBanner(s - 1, 5, 1, Direction.SOUTH);
        b.wallBanner(1, 5, s - 1, Direction.NORTH);
        b.wallBanner(s - 1, 5, s - 1, Direction.NORTH);
        // keepers' lean-to along the back wall: stone pillars, a slab roof, barrels, hay and a cauldron
        int x0 = 3, x1 = s - 3;
        for (int x = x0; x <= x1; x += 4) b.fill(x, 1, 3, x, 4, 3, b.p.frame());
        for (int x = x0 - 1; x <= x1 + 1; x++) for (int z = 1; z <= 4; z++) b.set(x, 5, z, b.p.stoneSlab().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        for (int x = x0 + 1; x <= x1 - 1; x += 2) b.barrel(x, 1, 1);
        b.set(x0 + 2, 1, 2, Blocks.HAY_BLOCK);
        b.set(x1 - 2, 1, 2, Blocks.HAY_BLOCK);
        b.set(MID, 1, 2, Blocks.CAULDRON);
        b.lantern(MID, 4, 2, true);
        // the nest: a raised stone ring, hay bedding, a gold hoard at the corners, the nest block in the middle
        b.fill(MID - 3, 1, MID - 3, MID + 3, 1, MID + 3, stone);
        b.fill(MID - 2, 2, MID - 2, MID + 2, 2, MID + 2, Blocks.HAY_BLOCK.defaultBlockState());
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) b.set(MID + c[0], 2, MID + c[1], Blocks.GOLD_BLOCK);
        b.set(MID, 2, MID, livingrealms.dragon.DragonApi.nest());
        // a path in from the open front
        for (int z = MID + 4; z <= s; z++) for (int x = MID - 1; x <= MID + 1; x++) b.set(x, 0, z, b.p.path());
    }

    private RoostBlueprint() {}
}
