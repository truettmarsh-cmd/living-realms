package livingrealms.building;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/**
 * The five Wonders (docs/wonders.md), drawn block by block in the town's palette with a few fixed accents (stained glass,
 * gold, iron, bookshelves). Fronts face south (+z) like every procedural building. They are deliberately large and dear:
 * thousands of blocks, and precious ones a town does not make for itself.
 */
final class WonderBlueprints {
    static final Block[] GLASS = {Blocks.YELLOW_STAINED_GLASS, Blocks.RED_STAINED_GLASS, Blocks.BLUE_STAINED_GLASS, Blocks.WHITE_STAINED_GLASS};

    static void make(String kind, Procedural.B b) {
        switch (kind) {
            case "grand_cathedral" -> cathedral(b);
            case "royal_academy" -> academy(b);
            case "grand_forge" -> forge(b);
            case "grand_exchange" -> exchange(b);
            case "citadel_of_heroes" -> citadel(b);
            default -> throw new IllegalArgumentException(kind);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Hollow walls on the rectangle's edge from y0 to y1. */
    static void walls(Procedural.B b, int x0, int z0, int x1, int z1, int y0, int y1, BlockState s) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) { b.set(x, y, z0, s); b.set(x, y, z1, s); }
            for (int z = z0; z <= z1; z++) { b.set(x0, y, z, s); b.set(x1, y, z, s); }
        }
    }

    /** A floor slab of the foundation at y=0 with the given floor inside, and the room cleared to height h. */
    static void base(Procedural.B b, int x0, int z0, int x1, int z1, int h, BlockState floor) {
        b.fill(x0, 0, z0, x1, 0, z1, b.p.foundation());
        b.fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, floor);
        b.clear(x0 + 1, 1, z0 + 1, x1 - 1, h, z1 - 1);
    }

    /** A pitched roof like {@code B.gable} but of the given stairs, slab and gable-end block. alongX: ridge runs along x. */
    static void roof(Procedural.B b, int x0, int z0, int x1, int z1, int y, boolean alongX, Block stairs, Block slab, BlockState end) {
        int a0 = alongX ? z0 : x0, a1 = alongX ? z1 : x1;
        int b0 = alongX ? x0 : z0, b1 = alongX ? x1 : z1;
        Direction up0 = alongX ? Direction.SOUTH : Direction.EAST, up1 = up0.getOpposite();
        for (int i = 0; ; i++) {
            int lo = a0 - 1 + i, hi = a1 + 1 - i, yy = y + i;
            if (lo > hi) break;
            for (int k = b0 - 1; k <= b1 + 1; k++) {
                if (lo == hi) put(b, alongX, k, yy, lo, slab.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                else {
                    put(b, alongX, k, yy, lo, stairs.defaultBlockState().setValue(StairBlock.FACING, up0));
                    put(b, alongX, k, yy, hi, stairs.defaultBlockState().setValue(StairBlock.FACING, up1));
                }
            }
            for (int a = lo + 1; a <= hi - 1; a++) {
                if (a < a0 || a > a1) continue;
                for (int k = b0 + 1; k <= b1 - 1; k++) put(b, alongX, k, yy, a, Procedural.B.AIR);
                put(b, alongX, b0, yy, a, end);
                put(b, alongX, b1, yy, a, end);
            }
            if (hi - lo <= 1) break;
        }
    }

    private static void put(Procedural.B b, boolean alongX, int k, int y, int a, BlockState s) {
        if (alongX) b.set(k, y, a, s); else b.set(a, y, k, s);
    }

    /** A solid stepped spire over a square of side n with its corner at (x, z), from y upward, topped by a block. */
    static void spire(Procedural.B b, int x, int z, int n, int y, BlockState s, BlockState top) {
        int i = 0;
        for (; n - 2 * i > 0; i++) {
            int lo = i, hi = n - 1 - i;
            walls(b, x + lo, z + lo, x + hi, z + hi, y + i * 2, y + i * 2 + 1, s);
        }
        int c = n / 2;
        b.set(x + c, y + i * 2, z + c, top);
    }

    /** A column of stained glass, a tall church window. */
    static void lancet(Procedural.B b, int x, int z, int y0, int y1, int colour) {
        for (int y = y0; y <= y1; y++) b.set(x, y, z, GLASS[Math.floorMod(colour + y, GLASS.length)]);
    }

    static BlockState candles(int n) {
        return Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true).setValue(CandleBlock.CANDLES, n);
    }

    static BlockState facing(Block block, Direction d) {
        return block.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, d);
    }

    // ------------------------------------------------------------------ Grand Cathedral (25 x 45)

    /**
     * A cruciform church: a tall nave with buttresses and lancet windows, a transept, an apse with a gold altar, a rose
     * window over a double door, and two bell towers with spires flanking the west front (here the south).
     */
    static void cathedral(Procedural.B b) {
        BlockState stone = b.p.stone(), floor = Blocks.POLISHED_ANDESITE.defaultBlockState();
        int mid = 12;
        b.clear(0, 1, 0, 24, 15, 44);
        // nave x 6..18, z 8..42
        base(b, 6, 8, 18, 42, 15, floor);
        walls(b, 6, 8, 18, 42, 1, 14, stone);
        // transept x 0..24, z 18..25, and the apse x 8..16, z 1..8
        base(b, 0, 18, 24, 25, 12, floor);
        walls(b, 0, 18, 5, 25, 1, 12, stone); walls(b, 19, 18, 24, 25, 1, 12, stone);
        b.clear(5, 1, 19, 5, 11, 24); b.clear(19, 1, 19, 19, 11, 24);
        base(b, 8, 1, 16, 8, 12, floor);
        walls(b, 8, 1, 16, 8, 1, 11, stone);
        b.clear(9, 1, 8, 15, 10, 8);
        // buttresses down both sides of the nave, lancets between them
        for (int z = 10; z <= 34; z += 4) {
            if (z >= 17 && z <= 26) continue;
            for (int x : new int[]{5, 19}) {
                b.fill(x, 0, z, x, 9, z, stone);
                b.stairs(x, 10, z, b.p.stoneStairs(), x == 5 ? Direction.EAST : Direction.WEST, Half.BOTTOM);
            }
            if (z + 2 <= 40 && !(z + 2 >= 17 && z + 2 <= 26)) {
                lancet(b, 6, z + 2, 4, 10, z);
                lancet(b, 18, z + 2, 4, 10, z + 1);
            }
        }
        for (int z = 20; z <= 23; z += 3) { lancet(b, 0, z, 3, 9, z); lancet(b, 24, z, 3, 9, z + 2); }
        for (int x = 10; x <= 14; x += 2) lancet(b, x, 1, 3, 9, x);
        // roofs: transept arms, then the nave and the apse
        roof(b, 0, 18, 5, 25, 13, true, b.p.stoneStairs(), b.p.stoneSlab(), stone);
        roof(b, 19, 18, 24, 25, 13, true, b.p.stoneStairs(), b.p.stoneSlab(), stone);
        roof(b, 6, 8, 18, 42, 15, false, b.p.stoneStairs(), b.p.stoneSlab(), stone);
        roof(b, 8, 1, 16, 7, 12, false, b.p.stoneStairs(), b.p.stoneSlab(), stone);
        // the front: a double door under a rose window, and the bell towers
        b.door(mid - 1, 1, 42, Direction.SOUTH);
        b.door(mid + 1, 1, 42, Direction.SOUTH);
        b.set(mid, 1, 42, stone); b.set(mid, 2, 42, stone);
        b.fill(mid - 1, 3, 42, mid + 1, 3, 42, stone);
        for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++)
            if (dx * dx + dy * dy <= 10) b.set(mid + dx, 9 + dy, 42, dx == 0 && dy == 0 ? Blocks.YELLOW_STAINED_GLASS.defaultBlockState()
                    : (dx * dx + dy * dy <= 3 ? Blocks.RED_STAINED_GLASS : Blocks.BLUE_STAINED_GLASS).defaultBlockState());
        for (int x0 : new int[]{1, 18}) {
            int x1 = x0 + 5;
            base(b, x0, 37, x1, 42, 26, floor);
            walls(b, x0, 37, x1, 42, 1, 26, stone);
            b.fill(x0 + 1, 13, 38, x1 - 1, 13, 41, b.p.floor());
            b.fill(x0 + 1, 22, 38, x1 - 1, 22, 41, b.p.floor());
            b.ladder(x0 + 1, 1, 26, 38, Direction.SOUTH);
            b.set(x0 + 1, 13, 38, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
            b.set(x0 + 1, 22, 38, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
            // belfry openings and the bell
            for (int y = 23; y <= 25; y++) { b.set(x0 + 2, y, 42, Procedural.B.AIR); b.set(x0 + 3, y, 42, Procedural.B.AIR); b.set(x0 + 2, y, 37, Procedural.B.AIR); b.set(x0 + 3, y, 37, Procedural.B.AIR); }
            b.set(x0 + 3, 23, 40, Blocks.BELL.defaultBlockState().setValue(BellBlock.ATTACHMENT, BellAttachType.FLOOR));
            lancet(b, x0 + 2, 42, 5, 10, x0); lancet(b, x0 + 3, 42, 5, 10, x0 + 1);
            b.clear(x0 == 1 ? x1 : x0, 1, 39, x0 == 1 ? x1 : x0, 2, 39);
            b.fill(x0, 27, 37, x1, 27, 42, stone);
            spire(b, x0, 37, 6, 28, stone, Blocks.GOLD_BLOCK.defaultBlockState());
            b.lantern(x0 + 4, 14, 40, false);
            b.lantern(x0 + 4, 23, 38, false);
        }
        // the nave: columns with lanterns, pews either side of a carpet aisle
        for (int z = 11; z <= 39; z += 4) {
            if (z >= 17 && z <= 26) continue;
            for (int x : new int[]{8, 16}) { b.fill(x, 1, z, x, 12, z, stone); b.lantern(x, 13, z, false); }
        }
        for (int z = 27; z <= 40; z += 2) {
            for (int x = 9; x <= mid - 1; x++) b.chair(x, 1, z, Direction.SOUTH);
            for (int x = mid + 1; x <= 15; x++) b.chair(x, 1, z, Direction.SOUTH);
        }
        for (int z = 9; z <= 41; z++) b.set(mid, 1, z, b.p.carpet());
        // the crossing and the transept chapels
        for (int x : new int[]{2, 22}) { b.set(x, 1, 21, Blocks.LECTERN.defaultBlockState()); b.set(x, 1, 22, candles(3)); b.lantern(x, 1, 19, false); b.lantern(x, 1, 24, false); }
        b.lantern(mid - 3, 1, 21, false); b.lantern(mid + 3, 1, 22, false);
        // the apse: a raised gold altar
        b.fill(9, 1, 2, 15, 1, 5, stone);
        b.fill(10, 2, 3, 14, 2, 3, Blocks.GOLD_BLOCK.defaultBlockState());
        b.set(mid, 3, 3, candles(4));
        b.set(10, 3, 3, candles(2)); b.set(14, 3, 3, candles(2));
        b.set(mid, 2, 5, facing(Blocks.LECTERN, Direction.SOUTH));
        b.wallBanner(mid - 2, 6, 2, Direction.SOUTH); b.wallBanner(mid + 2, 6, 2, Direction.SOUTH);
        b.stairs(mid, 1, 6, b.p.stoneStairs(), Direction.NORTH, Half.BOTTOM);
        b.lantern(9, 1, 6, false); b.lantern(15, 1, 6, false);
        // the forecourt
        b.path(4, 43, 20, 44);
    }

    // ------------------------------------------------------------------ Royal Academy (31 x 29)

    /**
     * A two-storey college of stone round a domed reading room: lecture benches and an enchanting table below, a library
     * of bookshelves above, an observatory tower at the back and a columned portico at the front.
     */
    static void academy(Procedural.B b) {
        BlockState stone = b.p.stone(), floor = b.p.floor();
        int mid = 15, x0 = 0, x1 = 30, z0 = 5, z1 = 25;
        b.clear(0, 1, 0, 30, 14, 28);
        base(b, x0, z0, x1, z1, 12, Blocks.POLISHED_ANDESITE.defaultBlockState());
        walls(b, x0, z0, x1, z1, 1, 12, stone);
        b.fill(x0 + 1, 6, z0 + 1, x1 - 1, 6, z1 - 1, floor);
        b.windows(x0, z0, x1, z1, 3, 3);
        b.windows(x0, z0, x1, z1, 4, 3);
        b.windows(x0, z0, x1, z1, 9, 3);
        b.windows(x0, z0, x1, z1, 10, 3);
        // flat roof with a parapet and a stepped dome over the reading room
        b.fill(x0, 13, z0, x1, 13, z1, stone);
        b.crenels(x0, z0, x1, z1, 14);
        b.clear(mid - 4, 6, 11, mid + 4, 13, 19);
        for (int i = 0; i <= 4; i++) {
            int r = 5 - i;
            walls(b, mid - r, 15 - r, mid + r, 15 + r, 14 + i, 14 + i, i == 4 ? Blocks.GLASS.defaultBlockState() : stone);
        }
        b.set(mid, 18, 15, Blocks.GLASS);
        // ground floor: the reading room round the enchanting table, benches to either side
        b.set(mid, 1, 15, Blocks.ENCHANTING_TABLE);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            if (Math.max(Math.abs(dx), Math.abs(dz)) == 2 && (dx == 0 || dz == 0 || Math.abs(dx) == Math.abs(dz))) b.set(mid + dx, 1, 15 + dz, Blocks.BOOKSHELF);
        b.set(mid, 1, 17, Procedural.B.AIR);
        for (int x : new int[]{3, 6, 24, 27}) for (int z = 9; z <= 21; z += 3) { b.table(x, 1, z); b.chair(x, 1, z + 1, Direction.NORTH); }
        for (int z = z0 + 1; z <= z1 - 1; z += 2) { b.set(x0 + 1, 1, z, Blocks.BOOKSHELF); b.set(x1 - 1, 1, z, Blocks.BOOKSHELF); }
        for (int x : new int[]{3, 9, 21, 27}) { b.lantern(x, 5, 12, true); b.lantern(x, 5, 19, true); }
        b.set(mid - 3, 1, z0 + 1, facing(Blocks.LECTERN, Direction.SOUTH));
        b.set(mid + 3, 1, z0 + 1, facing(Blocks.LECTERN, Direction.SOUTH));
        b.set(mid - 5, 1, z0 + 1, Blocks.CARTOGRAPHY_TABLE);
        // upper floor: the library stacks round the dome's gallery
        for (int x : new int[]{2, 4, 26, 28}) for (int z = 8; z <= 22; z++) if (z != 15) b.fill(x, 7, z, x, 9, z, Blocks.BOOKSHELF.defaultBlockState());
        for (int x = 9; x <= 21; x += 12) for (int z = 7; z <= 23; z += 16) b.set(x, 7, z, facing(Blocks.LECTERN, Direction.SOUTH));
        for (int x : new int[]{6, 12, 18, 24}) { b.lantern(x, 7, 7, false); b.lantern(x, 7, 23, false); }
        b.ladder(x0 + 1, 1, 6, z1 - 1, Direction.NORTH);
        b.set(x0 + 1, 6, z1 - 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        // the observatory tower at the back
        Procedural.tower(b, mid - 2, 0, 5, 22);
        b.clear(mid, 1, z0, mid, 2, z0);
        b.clear(mid, 1, 4, mid, 2, 4);
        b.set(mid, 23, 2, Blocks.LIGHTNING_ROD);
        // the portico: columns, a pediment of stairs, the doors and steps
        b.door(mid - 1, 1, z1, Direction.SOUTH);
        b.door(mid + 1, 1, z1, Direction.SOUTH);
        b.set(mid, 1, z1, stone); b.set(mid, 2, z1, stone);
        b.fill(mid - 8, 0, z1 + 1, mid + 8, 0, z1 + 3, stone);
        for (int x : new int[]{mid - 8, mid - 5, mid + 5, mid + 8}) b.fill(x, 1, z1 + 3, x, 7, z1 + 3, Blocks.QUARTZ_PILLAR.defaultBlockState());
        b.fill(mid - 9, 8, z1 + 1, mid + 9, 8, z1 + 3, stone);
        for (int i = 0; i <= 4; i++) {
            b.stairs(mid - 9 + i, 9 + i / 2, z1 + 3, b.p.stoneStairs(), Direction.EAST, Half.BOTTOM);
            b.stairs(mid + 9 - i, 9 + i / 2, z1 + 3, b.p.stoneStairs(), Direction.WEST, Half.BOTTOM);
        }
        b.fill(mid - 4, 11, z1 + 3, mid + 4, 11, z1 + 3, stone);
        b.lantern(mid - 2, 7, z1 + 2, true); b.lantern(mid + 2, 7, z1 + 2, true);
        b.wallBanner(mid - 3, 6, z1 + 1, Direction.SOUTH); b.wallBanner(mid + 3, 6, z1 + 1, Direction.SOUTH);
    }

    // ------------------------------------------------------------------ Grand Forge (29 x 27)

    /**
     * A long smithing hall under a stone roof with three brick chimneys over their hearths, anvils and smithing tables on
     * the floor, blast furnaces along the back, iron-capped piers and a wide arched front open to the yard.
     */
    static void forge(Procedural.B b) {
        BlockState stone = b.p.stone(), brick = Blocks.BRICKS.defaultBlockState();
        int x0 = 0, x1 = 28, z0 = 2, z1 = 22, mid = 14;
        b.clear(0, 1, 0, 28, 10, 26);
        base(b, x0, z0, x1, z1, 10, stone);
        walls(b, x0, z0, x1, z1, 1, 9, stone);
        b.fill(x0, 1, z0, x0, 9, z0, b.p.frame()); b.fill(x1, 1, z0, x1, 9, z0, b.p.frame());
        b.fill(x0, 1, z1, x0, 9, z1, b.p.frame()); b.fill(x1, 1, z1, x1, 9, z1, b.p.frame());
        b.windows(x0, z0, x1, z1, 7, 3);
        roof(b, x0, z0, x1, z1, 10, true, b.p.stoneStairs(), b.p.stoneSlab(), stone);
        // the arched front, open to the yard
        b.clear(mid - 5, 1, z1, mid + 5, 5, z1);
        b.stairs(mid - 5, 5, z1, b.p.stoneStairs(), Direction.EAST, Half.TOP);
        b.stairs(mid + 5, 5, z1, b.p.stoneStairs(), Direction.WEST, Half.TOP);
        b.clear(mid - 4, 6, z1, mid + 4, 6, z1);
        b.fill(mid - 4, 6, z1, mid + 4, 6, z1, stone);
        // three hearths: brick chimneys through the roof, a campfire in each, a blast furnace and furnace beside it
        for (int cx : new int[]{6, mid, 22}) {
            b.fill(cx - 1, 1, z0 + 1, cx + 1, 24, z0 + 3, brick);
            b.clear(cx, 2, z0 + 2, cx, 24, z0 + 2);
            b.clear(cx, 1, z0 + 3, cx, 2, z0 + 3);
            b.set(cx, 1, z0 + 2, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.FACING, Direction.SOUTH));
            b.set(cx - 2, 1, z0 + 1, facing(Blocks.BLAST_FURNACE, Direction.SOUTH));
            b.set(cx + 2, 1, z0 + 1, facing(Blocks.FURNACE, Direction.SOUTH));
            b.lantern(cx + 1, 1, z0 + 4, false);
        }
        // the floor: anvils, smithing tables, grindstones, a quench tub and racks of barrels
        for (int i = 0; i < 6; i++) {
            int x = 4 + i * 4;
            b.set(x, 1, 10, Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.EAST));
            b.set(x, 1, 13, i % 2 == 0 ? Blocks.SMITHING_TABLE.defaultBlockState() : Blocks.GRINDSTONE.defaultBlockState()
                    .setValue(GrindstoneBlock.FACE, AttachFace.FLOOR).setValue(GrindstoneBlock.FACING, Direction.SOUTH));
        }
        b.set(2, 1, 16, Blocks.CAULDRON); b.set(26, 1, 16, Blocks.CAULDRON);
        for (int z = 15; z <= 20; z++) { b.barrel(1, 1, z); b.barrel(27, 1, z); }
        b.chest(1, 2, 17, Direction.EAST); b.chest(27, 2, 17, Direction.WEST);
        // iron-capped piers carrying the roof
        for (int x : new int[]{7, 21}) for (int z : new int[]{8, 16}) {
            b.fill(x, 1, z, x, 8, z, stone);
            b.set(x, 9, z, Blocks.IRON_BLOCK);
            b.lantern(x, 1, z + 1, false);
        }
        for (int x = 4; x <= 24; x += 5) b.lantern(x, 1, 19, false);
        // the yard in front
        b.path(mid - 6, z1 + 1, mid + 6, 26);
        b.set(mid - 7, 1, 25, Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.SOUTH));
        b.set(mid + 7, 1, 25, Blocks.SMITHING_TABLE);
    }

    // ------------------------------------------------------------------ Grand Exchange (31 x 31)

    /**
     * A colonnaded market hall: a covered walk round a court of stalls, and in the middle the domed counting house with
     * its treasury of gold and emerald blocks, crowned by a lighthouse lamp that guides the caravans in.
     */
    static void exchange(Procedural.B b) {
        BlockState stone = b.p.stone();
        int n = 30, mid = 15;
        b.clear(0, 1, 0, n, 10, n);
        b.fill(0, 0, 0, n, 0, n, b.p.foundation());
        b.fill(4, 0, 4, n - 4, 0, n - 4, b.p.path());
        // the colonnade: a covered walk three wide round the court
        for (int i = 1; i <= n - 1; i += 4) for (int[] c : new int[][]{{i, 1}, {i, n - 1}, {1, i}, {n - 1, i}, {i, 3}, {i, n - 3}, {3, i}, {n - 3, i}}) {
            if (c[0] < 1 || c[1] < 1 || c[0] > n - 1 || c[1] > n - 1) continue;
            if (Math.abs(c[0] - mid) <= 1 && (c[1] >= n - 3)) continue; // the main entrance
            b.fill(c[0], 1, c[1], c[0], 6, c[1], Blocks.QUARTZ_PILLAR.defaultBlockState());
        }
        for (int x = 0; x <= n; x++) for (int z = 0; z <= n; z++)
            if (x <= 3 || z <= 3 || x >= n - 3 || z >= n - 3) b.set(x, 7, z, stone);
        b.crenels(0, 0, n, n, 8);
        for (int x = 5; x <= n - 5; x += 5) { b.lantern(x, 6, 2, true); b.lantern(x, 6, n - 2, true); b.lantern(2, 6, x, true); b.lantern(n - 2, 6, x, true); }
        // the court of stalls
        Block[] wools = {Blocks.RED_WOOL, Blocks.YELLOW_WOOL, Blocks.BLUE_WOOL, Blocks.GREEN_WOOL, Blocks.ORANGE_WOOL, Blocks.PURPLE_WOOL, Blocks.CYAN_WOOL, Blocks.WHITE_WOOL};
        int[][] stalls = {{5, 5}, {12, 5}, {19, 5}, {23, 5}, {5, 12}, {23, 12}, {5, 19}, {23, 19}};
        for (int i = 0; i < stalls.length; i++) Procedural.stall(b, stalls[i][0], stalls[i][1], wools[i]);
        // the counting house
        int x0 = 10, z0 = 10, x1 = 20, z1 = 20;
        base(b, x0, z0, x1, z1, 7, Blocks.POLISHED_ANDESITE.defaultBlockState());
        walls(b, x0, z0, x1, z1, 1, 7, stone);
        b.windows(x0, z0, x1, z1, 3, 2);
        b.door(mid, 1, z1, Direction.SOUTH);
        b.door(mid, 1, z0, Direction.NORTH);
        // the treasury: gold and emerald on a stone counter, ledgers and coffers
        b.fill(mid - 3, 1, 14, mid + 3, 1, 14, stone);
        b.set(mid - 2, 2, 14, Blocks.GOLD_BLOCK); b.set(mid + 2, 2, 14, Blocks.GOLD_BLOCK);
        b.set(mid - 1, 2, 14, Blocks.EMERALD_BLOCK); b.set(mid + 1, 2, 14, Blocks.EMERALD_BLOCK);
        b.set(mid, 2, 14, Blocks.GOLD_BLOCK);
        b.set(mid - 3, 2, 14, facing(Blocks.LECTERN, Direction.SOUTH)); b.set(mid + 3, 2, 14, facing(Blocks.LECTERN, Direction.SOUTH));
        for (int x = x0 + 1; x <= x1 - 1; x += 2) { b.barrel(x, 1, z0 + 1); b.chest(x, 1, z1 - 2, Direction.NORTH); }
        b.set(mid, 1, z1 - 2, Procedural.B.AIR);
        b.set(mid, 1, z0 + 1, Procedural.B.AIR);
        b.lantern(x0 + 2, 1, 17, false); b.lantern(x1 - 2, 1, 17, false);
        // the stepped dome and the lighthouse
        for (int i = 0; i <= 4; i++) walls(b, x0 + i, z0 + i, x1 - i, z1 - i, 8 + i, 8 + i, stone);
        b.fill(x0 + 5, 12, z0 + 5, x1 - 5, 12, z1 - 5, stone);
        b.fill(mid - 1, 13, mid - 1, mid + 1, 26, mid + 1, stone);
        b.fill(mid - 2, 27, mid - 2, mid + 2, 27, mid + 2, stone);
        b.crenels(mid - 2, mid - 2, mid + 2, mid + 2, 28);
        b.set(mid, 28, mid, Blocks.GLOWSTONE);
        for (int[] c : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) b.set(mid + c[0], 28, mid + c[1], Blocks.GLASS);
        b.set(mid, 29, mid, Blocks.GLASS);
        b.set(mid, 30, mid, Blocks.GOLD_BLOCK);
        b.lantern(mid - 1, 28, mid - 1, false); b.lantern(mid + 1, 28, mid + 1, false);
        b.lantern(mid + 1, 28, mid - 1, false); b.lantern(mid - 1, 28, mid + 1, false);
    }

    // ------------------------------------------------------------------ Citadel of Heroes (31 x 31)

    /**
     * A walled ward with four corner towers and a gatehouse, a tall keep at the back, the Hall of Heroes before it with
     * plinths for the realm's champions, a training yard of targets and the veterans' quarters.
     */
    static void citadel(Procedural.B b) {
        BlockState stone = b.p.stone();
        int n = 30, mid = 15;
        b.clear(0, 1, 0, n, 8, n);
        b.fill(0, 0, 0, n, 0, n, b.p.foundation());
        b.fill(2, 0, 2, n - 2, 0, n - 2, b.p.path());
        // the curtain wall with a walkway and battlements
        walls(b, 0, 0, n, n, 1, 6, stone);
        walls(b, 1, 1, n - 1, n - 1, 6, 6, stone);
        b.crenels(0, 0, n, n, 7);
        for (int[] c : new int[][]{{0, 0}, {n - 4, 0}, {0, n - 4}, {n - 4, n - 4}}) Procedural.tower(b, c[0], c[1], 5, 12);
        // the gatehouse
        b.fill(mid - 4, 0, n - 3, mid + 4, 9, n, stone);
        b.clear(mid - 1, 1, n - 3, mid + 1, 4, n);
        b.crenels(mid - 4, n - 3, mid + 4, n, 10);
        b.wallBanner(mid - 3, 7, n + 1, Direction.SOUTH);
        b.wallBanner(mid + 3, 7, n + 1, Direction.SOUTH);
        b.lantern(mid - 2, 1, n - 4, false); b.lantern(mid + 2, 1, n - 4, false);
        // the Hall of Heroes, then the keep against its back wall
        int hx0 = 7, hz0 = 12, hx1 = 23, hz1 = 20;
        base(b, hx0, hz0, hx1, hz1, 8, Blocks.POLISHED_ANDESITE.defaultBlockState());
        walls(b, hx0, hz0, hx1, hz1, 1, 8, stone);
        b.windows(hx0, hz0, hx1, hz1, 4, 2);
        roof(b, hx0, hz0, hx1, hz1, 9, true, b.p.stoneStairs(), b.p.stoneSlab(), stone);
        b.door(mid, 1, hz1, Direction.SOUTH);
        for (int z = hz0 + 1; z <= hz1 - 1; z++) b.set(mid, 1, z, b.p.carpet());
        // champions' plinths: stone, iron for the helm, the first of them gilded; banners between
        for (int i = 0; i < 6; i++) {
            int x = hx0 + 2 + (i % 3) * 2 + (i >= 3 ? 8 : 0);
            for (int z : new int[]{hz0 + 2, hz1 - 2}) {
                b.set(x, 1, z, Blocks.POLISHED_ANDESITE);
                b.set(x, 2, z, stone);
                b.set(x, 3, z, i == 0 && z == hz0 + 2 ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState());
            }
        }
        for (int x = hx0 + 2; x <= hx1 - 2; x += 4) { b.wallBanner(x, 5, hz0 + 1, Direction.SOUTH); b.lantern(x, 1, (hz0 + hz1) / 2, false); }
        // the keep: three floors, battlements, a lantern on the roof
        int kx0 = 10, kz0 = 3, kx1 = 20, kz1 = 12;
        base(b, kx0, kz0, kx1, kz1, 20, b.p.floor());
        walls(b, kx0, kz0, kx1, kz1, 1, 20, stone);
        for (int y : new int[]{7, 14}) b.fill(kx0 + 1, y, kz0 + 1, kx1 - 1, y, kz1 - 1, b.p.floor());
        b.fill(kx0, 21, kz0, kx1, 21, kz1, stone);
        b.crenels(kx0, kz0, kx1, kz1, 22);
        b.ladder(kx0 + 1, 1, 21, kz0 + 1, Direction.SOUTH);
        for (int y : new int[]{7, 14, 21}) b.set(kx0 + 1, y, kz0 + 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        for (int y : new int[]{3, 10, 17}) b.windows(kx0, kz0, kx1, kz1, y, 3);
        b.lantern(mid, 22, 7, false);
        for (int y : new int[]{4, 11, 18}) { b.lantern(kx0 + 3, y + 2, kz0 + 3, true); b.lantern(kx1 - 3, y + 2, kz1 - 3, true); }
        for (int x = kx0 + 3; x <= kx1 - 3; x += 2) b.bed(x, 8, kz0 + 1, Direction.SOUTH);
        b.chest(kx1 - 1, 15, kz0 + 1, Direction.WEST);
        b.set(mid, 15, kz0 + 2, Blocks.CARTOGRAPHY_TABLE);
        // the passage from the hall into the keep
        b.clear(mid - 1, 1, kz1, mid + 1, 3, kz1);
        // the training yard: targets and hay on the east, the veterans' quarters on the west
        for (int z = 8; z <= 24; z += 4) { b.set(n - 3, 1, z, Blocks.HAY_BLOCK); b.set(n - 3, 2, z, Blocks.TARGET); }
        for (int z = 22; z <= 26; z += 2) b.set(n - 6, 1, z, Blocks.HAY_BLOCK);
        for (int z = 7; z <= 25; z += 3) b.bed(2, 1, z, Direction.WEST);
        for (int z = 6; z <= 25; z++) for (int x = 2; x <= 4; x++) b.slab(x, 4, z, b.p.stoneSlab(), SlabType.BOTTOM);
        for (int z = 6; z <= 21; z += 5) b.fill(4, 1, z, 4, 3, z, b.p.fence().defaultBlockState());
        b.lantern(6, 1, 9, false); b.lantern(6, 1, 23, false); b.lantern(n - 6, 1, 9, false);
    }

    private WonderBlueprints() {}
}
