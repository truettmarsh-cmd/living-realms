package livingrealms.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The settlement's own buildings, generated from code in the style of the settlement's biome.
 * Unlike structure templates they get a different, bigger blueprint at every level, so upgrades are visible.
 * Keys look like {@code proc:town_hall/3/taiga}. Front doors face south (+z); the planner rotates them.
 */
public final class Procedural {
    /** kind -> max level */
    public static final Map<String, Integer> KINDS = new java.util.LinkedHashMap<>();

    static {
        KINDS.put("town_hall", 6);
        KINDS.put("cottage", 3);
        KINDS.put("builders_yard", 3);
        KINDS.put("storehouse", 3);
        KINDS.put("smithy", 3);
        KINDS.put("library", 3);
        KINDS.put("barracks", 3);
        KINDS.put("watchtower", 2);
        KINDS.put("wall", 1);
        KINDS.put("gatehouse", 1);
        KINDS.put("farm_field", 2);
        KINDS.put("stables", 2);
        KINDS.put("tavern", 2);
        KINDS.put("chapel", 1);
        KINDS.put("market", 2);
        KINDS.put("lumber_camp", 2);
        KINDS.put("mine", 3);
        KINDS.put("well", 1);
        KINDS.put("garden", 1);
    }

    /** Blocks below ground level (mine shafts). */
    public static int sink(String kind) {
        return kind.equals("mine") ? 7 : 0;
    }

    public static Blueprint make(String key) {
        String[] parts = key.substring("proc:".length()).split("/");
        String kind = parts[0];
        int level = Integer.parseInt(parts[1]);
        P p = palette(parts.length > 2 ? parts[2] : "plains");
        B b = new B(p);
        switch (kind) {
            case "town_hall" -> townHall(b, level);
            case "cottage" -> cottage(b, level);
            case "builders_yard" -> buildersYard(b, level);
            case "storehouse" -> storehouse(b, level);
            case "smithy" -> smithy(b, level);
            case "library" -> library(b, level);
            case "barracks" -> barracks(b, level);
            case "watchtower" -> watchtower(b, level);
            case "wall" -> wall(b);
            case "gatehouse" -> gatehouse(b);
            case "farm_field" -> farmField(b, level);
            case "stables" -> stables(b, level);
            case "tavern" -> tavern(b, level);
            case "chapel" -> chapel(b);
            case "market" -> market(b, level);
            case "lumber_camp" -> lumberCamp(b, level);
            case "mine" -> mine(b, level);
            case "well" -> well(b);
            case "garden" -> garden(b);
            default -> cottage(b, 1);
        }
        return b.build(key);
    }

    // ------------------------------------------------------------------ palettes

    record P(BlockState foundation, BlockState floor, BlockState wall, BlockState frame, BlockState upperWall,
             Block roofStairs, BlockState roofFull, Block roofSlab, Block plankStairs, Block plankSlab, Block fence,
             Block fenceGate, Block door, Block trapdoor, BlockState stone, Block stoneStairs, Block stoneSlab,
             Block stoneWall, BlockState path, Block carpet, Block wool, Block bed, Block banner, Block wallBanner,
             BlockState log) {}

    public static String styleFamily(String style) {
        String s = style.toLowerCase();
        if (s.contains("desert") || s.contains("badlands") || s.contains("mesa") || s.contains("pueblo") || s.contains("oasis")) return "desert";
        if (s.contains("snow") || s.contains("ice") || s.contains("igloo") || s.contains("viking")) return "snowy";
        if (s.contains("taiga") || s.contains("spruce") || s.contains("polish") || s.contains("mountain") || s.contains("swiss")) return "taiga";
        if (s.contains("savanna") || s.contains("acacia")) return "savanna";
        return "plains";
    }

    static P palette(String style) {
        return switch (styleFamily(style)) {
            case "desert" -> new P(Blocks.SANDSTONE.defaultBlockState(), Blocks.SMOOTH_SANDSTONE.defaultBlockState(),
                    Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.SMOOTH_SANDSTONE.defaultBlockState(),
                    Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.SMOOTH_SANDSTONE_SLAB,
                    Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_SLAB, Blocks.JUNGLE_FENCE, Blocks.JUNGLE_FENCE_GATE, Blocks.JUNGLE_DOOR,
                    Blocks.JUNGLE_TRAPDOOR, Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.SANDSTONE_STAIRS, Blocks.SANDSTONE_SLAB,
                    Blocks.SANDSTONE_WALL, Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.ORANGE_CARPET, Blocks.ORANGE_WOOL,
                    Blocks.ORANGE_BED, Blocks.ORANGE_BANNER, Blocks.ORANGE_WALL_BANNER, Blocks.STRIPPED_JUNGLE_LOG.defaultBlockState());
            case "snowy" -> new P(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(),
                    Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(),
                    Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_PLANKS.defaultBlockState(), Blocks.DARK_OAK_SLAB,
                    Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE, Blocks.SPRUCE_DOOR,
                    Blocks.SPRUCE_TRAPDOOR, Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB,
                    Blocks.STONE_BRICK_WALL, Blocks.GRAVEL.defaultBlockState(), Blocks.LIGHT_BLUE_CARPET, Blocks.LIGHT_BLUE_WOOL,
                    Blocks.LIGHT_BLUE_BED, Blocks.LIGHT_BLUE_BANNER, Blocks.LIGHT_BLUE_WALL_BANNER, Blocks.SPRUCE_LOG.defaultBlockState());
            case "taiga" -> new P(Blocks.COBBLESTONE.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(),
                    Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(),
                    Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_SLAB,
                    Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE, Blocks.SPRUCE_DOOR,
                    Blocks.SPRUCE_TRAPDOOR, Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB,
                    Blocks.COBBLESTONE_WALL, Blocks.DIRT_PATH.defaultBlockState(), Blocks.GREEN_CARPET, Blocks.GREEN_WOOL,
                    Blocks.GREEN_BED, Blocks.GREEN_BANNER, Blocks.GREEN_WALL_BANNER, Blocks.SPRUCE_LOG.defaultBlockState());
            case "savanna" -> new P(Blocks.COBBLESTONE.defaultBlockState(), Blocks.ACACIA_PLANKS.defaultBlockState(),
                    Blocks.ACACIA_PLANKS.defaultBlockState(), Blocks.ACACIA_LOG.defaultBlockState(), Blocks.TERRACOTTA.defaultBlockState(),
                    Blocks.ACACIA_STAIRS, Blocks.ACACIA_PLANKS.defaultBlockState(), Blocks.ACACIA_SLAB,
                    Blocks.ACACIA_STAIRS, Blocks.ACACIA_SLAB, Blocks.ACACIA_FENCE, Blocks.ACACIA_FENCE_GATE, Blocks.ACACIA_DOOR,
                    Blocks.ACACIA_TRAPDOOR, Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB,
                    Blocks.COBBLESTONE_WALL, Blocks.DIRT_PATH.defaultBlockState(), Blocks.YELLOW_CARPET, Blocks.YELLOW_WOOL,
                    Blocks.YELLOW_BED, Blocks.YELLOW_BANNER, Blocks.YELLOW_WALL_BANNER, Blocks.ACACIA_LOG.defaultBlockState());
            default -> new P(Blocks.COBBLESTONE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(),
                    Blocks.OAK_PLANKS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.WHITE_TERRACOTTA.defaultBlockState(),
                    Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_PLANKS.defaultBlockState(), Blocks.DARK_OAK_SLAB,
                    Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE, Blocks.OAK_DOOR,
                    Blocks.OAK_TRAPDOOR, Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB,
                    Blocks.COBBLESTONE_WALL, Blocks.DIRT_PATH.defaultBlockState(), Blocks.RED_CARPET, Blocks.RED_WOOL,
                    Blocks.RED_BED, Blocks.RED_BANNER, Blocks.RED_WALL_BANNER, Blocks.OAK_LOG.defaultBlockState());
        };
    }

    // ------------------------------------------------------------------ block set builder

    static final class B {
        final P p;
        final Map<BlockPos, BlockState> m = new HashMap<>();
        int sx, sy, sz;
        static final BlockState AIR = Blocks.AIR.defaultBlockState();

        B(P p) { this.p = p; }

        void set(int x, int y, int z, BlockState s) {
            if (x < 0 || y < 0 || z < 0) return;
            m.put(new BlockPos(x, y, z), s);
            sx = Math.max(sx, x + 1);
            sy = Math.max(sy, y + 1);
            sz = Math.max(sz, z + 1);
        }

        void set(int x, int y, int z, Block b) { set(x, y, z, b.defaultBlockState()); }

        BlockState get(int x, int y, int z) { return m.get(new BlockPos(x, y, z)); }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
                for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                    for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, s);
        }

        /** Clears the space so trees and terrain inside the plot are removed. */
        void clear(int x0, int y0, int z0, int x1, int y1, int z1) { fill(x0, y0, z0, x1, y1, z1, AIR); }

        /** Walls on the rectangle's edge from y=1 to y=h, foundation and floor at y=0, log corner posts, air inside. */
        void body(int x0, int z0, int x1, int z1, int h, BlockState wall, boolean corners) {
            fill(x0, 0, z0, x1, 0, z1, p.foundation);
            fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, p.floor);
            clear(x0 + 1, 1, z0 + 1, x1 - 1, h, z1 - 1);
            for (int y = 1; y <= h; y++) {
                for (int x = x0; x <= x1; x++) { set(x, y, z0, wall); set(x, y, z1, wall); }
                for (int z = z0; z <= z1; z++) { set(x0, y, z, wall); set(x1, y, z, wall); }
                if (corners) {
                    set(x0, y, z0, p.frame); set(x1, y, z0, p.frame); set(x0, y, z1, p.frame); set(x1, y, z1, p.frame);
                }
            }
        }

        /** A wooden upper storey band: frame beam at y and the upper wall material above it. */
        void upperWalls(int x0, int z0, int x1, int z1, int y0, int y1) {
            for (int y = y0; y <= y1; y++) {
                BlockState s = y == y0 ? p.floor : p.upperWall;
                for (int x = x0; x <= x1; x++) { set(x, y, z0, s); set(x, y, z1, s); }
                for (int z = z0; z <= z1; z++) { set(x0, y, z, s); set(x1, y, z, s); }
                set(x0, y, z0, p.frame); set(x1, y, z0, p.frame); set(x0, y, z1, p.frame); set(x1, y, z1, p.frame);
            }
            fill(x0 + 1, y0, z0 + 1, x1 - 1, y0, z1 - 1, p.floor);
        }

        void windows(int x0, int z0, int x1, int z1, int y, int spacing) {
            BlockState ns = Blocks.GLASS_PANE.defaultBlockState().setValue(CrossCollisionBlock.EAST, true).setValue(CrossCollisionBlock.WEST, true);
            BlockState ew = Blocks.GLASS_PANE.defaultBlockState().setValue(CrossCollisionBlock.NORTH, true).setValue(CrossCollisionBlock.SOUTH, true);
            for (int x = x0 + 2; x <= x1 - 2; x += spacing) {
                set(x, y, z0, ns);
                if (get(x, y, z1) != null && !(get(x, y, z1).getBlock() instanceof DoorBlock)) set(x, y, z1, ns);
            }
            for (int z = z0 + 2; z <= z1 - 2; z += spacing) { set(x0, y, z, ew); set(x1, y, z, ew); }
        }

        void door(int x, int y, int z, Direction facing) {
            BlockState d = p.door.defaultBlockState().setValue(DoorBlock.FACING, facing);
            set(x, y, z, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            set(x, y + 1, z, d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }

        void stairs(int x, int y, int z, Block block, Direction facing, Half half) {
            set(x, y, z, block.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half));
        }

        void slab(int x, int y, int z, Block block, SlabType type) {
            set(x, y, z, block.defaultBlockState().setValue(SlabBlock.TYPE, type));
        }

        /** Gable roof with a one block overhang. The ridge runs along x when alongX. Gable ends are filled with the frame wood. */
        void gable(int x0, int z0, int x1, int z1, int y, boolean alongX) {
            int a0 = alongX ? z0 : x0, a1 = alongX ? z1 : x1;   // across the slope
            int b0 = alongX ? x0 : z0, b1 = alongX ? x1 : z1;   // along the ridge
            Direction up0 = alongX ? Direction.SOUTH : Direction.EAST, up1 = up0.getOpposite();
            for (int i = 0; ; i++) {
                int lo = a0 - 1 + i, hi = a1 + 1 - i, yy = y + i;
                if (lo > hi) break;
                for (int b = b0 - 1; b <= b1 + 1; b++) {
                    if (lo == hi) {
                        put(alongX, b, yy, lo, p.roofSlab.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                    } else {
                        put(alongX, b, yy, lo, p.roofStairs.defaultBlockState().setValue(StairBlock.FACING, up0));
                        put(alongX, b, yy, hi, p.roofStairs.defaultBlockState().setValue(StairBlock.FACING, up1));
                    }
                }
                for (int a = lo + 1; a <= hi - 1; a++) {
                    if (a < a0 || a > a1) continue;
                    for (int b = b0 + 1; b <= b1 - 1; b++) put(alongX, b, yy, a, AIR);
                    put(alongX, b0, yy, a, p.upperWall);
                    put(alongX, b1, yy, a, p.upperWall);
                }
                if (hi - lo <= 1) break;
            }
        }

        private void put(boolean alongX, int b, int y, int a, BlockState s) {
            if (alongX) set(b, y, a, s); else set(a, y, b, s);
        }

        /** Battlements: wall blocks on every other edge block. */
        void crenels(int x0, int z0, int x1, int z1, int y) {
            for (int x = x0; x <= x1; x++) {
                if ((x - x0) % 2 == 0) { set(x, y, z0, p.stone); set(x, y, z1, p.stone); }
            }
            for (int z = z0; z <= z1; z++) {
                if ((z - z0) % 2 == 0) { set(x0, y, z, p.stone); set(x1, y, z, p.stone); }
            }
        }

        void bed(int x, int y, int z, Direction toHead) {
            BlockState s = p.bed.defaultBlockState().setValue(BedBlock.FACING, toHead);
            set(x, y, z, s.setValue(BedBlock.PART, BedPart.FOOT));
            set(x + toHead.getStepX(), y, z + toHead.getStepZ(), s.setValue(BedBlock.PART, BedPart.HEAD));
        }

        void lantern(int x, int y, int z, boolean hanging) {
            set(x, y, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, hanging));
        }

        void wallTorch(int x, int y, int z, Direction facing) {
            set(x, y, z, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, facing));
        }

        void ladder(int x, int y0, int y1, int z, Direction facing) {
            for (int y = y0; y <= y1; y++) set(x, y, z, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing));
        }

        void table(int x, int y, int z) {
            set(x, y, z, p.fence);
            set(x, y + 1, z, Blocks.OAK_PRESSURE_PLATE);
        }

        void chair(int x, int y, int z, Direction facing) { stairs(x, y, z, p.plankStairs, facing, Half.BOTTOM); }

        void wallBanner(int x, int y, int z, Direction facing) {
            set(x, y, z, p.wallBanner.defaultBlockState().setValue(WallBannerBlock.FACING, facing));
        }

        void barrel(int x, int y, int z) { set(x, y, z, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP)); }

        void chest(int x, int y, int z, Direction facing) { set(x, y, z, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing)); }

        void path(int x0, int z0, int x1, int z1) { fill(x0, 0, z0, x1, 0, z1, p.path); clear(x0, 1, z0, x1, 3, z1); }

        Blueprint build(String key) {
            List<Blueprint.Entry> list = new ArrayList<>(m.size());
            m.forEach((pos, s) -> list.add(new Blueprint.Entry(pos.getX(), pos.getY(), pos.getZ(), s)));
            return new Blueprint(key, sx, sy, sz, list);
        }
    }

    // ------------------------------------------------------------------ buildings

    static void townHall(B b, int level) {
        int[] ws = {0, 9, 11, 13, 15, 17, 19}, ds = {0, 9, 9, 11, 11, 13, 15};
        int w = ws[level], d = ds[level], floors = level >= 6 ? 3 : level >= 3 ? 2 : 1;
        int x0 = 1, z0 = 1, x1 = x0 + w - 1, z1 = z0 + d - 1, mid = (x0 + x1) / 2;
        int fh = 4, h = floors * fh;
        b.clear(0, 1, 0, x1 + 1, h + d / 2 + 3, z1 + 5);
        b.body(x0, z0, x1, z1, fh, b.p.stone, true);
        for (int f = 1; f < floors; f++) b.upperWalls(x0, z0, x1, z1, f * fh + 1, (f + 1) * fh);
        for (int f = 0; f < floors; f++) b.windows(x0, z0, x1, z1, f * fh + 2, 2);
        if (w >= 13) { b.door(mid - 1, 1, z1, Direction.SOUTH); b.door(mid + 1, 1, z1, Direction.SOUTH); b.set(mid, 1, z1, b.p.frame); b.set(mid, 2, z1, b.p.frame); }
        else b.door(mid, 1, z1, Direction.SOUTH);
        b.gable(x0, z0, x1, z1, h + 1, true);
        // hall: carpet aisle, long table with chairs, lectern and banners at the back
        for (int z = z0 + 2; z <= z1 - 1; z++) b.set(mid, 1, z, b.p.carpet);
        b.set(mid, 1, z0 + 1, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.SOUTH));
        b.wallBanner(mid - 1, 3, z0 + 1, Direction.SOUTH);
        b.wallBanner(mid + 1, 3, z0 + 1, Direction.SOUTH);
        for (int x = x0 + 2; x <= mid - 2; x++) { b.table(x, 1, z0 + 3); b.chair(x, 1, z0 + 4, Direction.NORTH); }
        for (int x = mid + 2; x <= x1 - 2; x++) { b.table(x, 1, z0 + 3); b.chair(x, 1, z0 + 4, Direction.NORTH); }
        for (int f = 0; f < floors; f++) {
            for (int x = x0 + 2; x <= x1 - 2; x += 4) b.lantern(x, (f + 1) * fh, (z0 + z1) / 2, true);
        }
        b.ladder(x0 + 1, 1, h, z0 + 1, Direction.SOUTH);
        for (int f = 1; f < floors; f++) b.set(x0 + 1, f * fh + 1, z0 + 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        if (floors >= 2) {
            int fy = fh + 2;
            for (int x = x1 - 4; x <= x1 - 1; x++) b.set(x, fy, z0 + 1, Blocks.BOOKSHELF);
            b.set(x1 - 2, fy, z0 + 3, Blocks.CARTOGRAPHY_TABLE);
            b.chest(x1 - 1, fy, z1 - 1, Direction.WEST);
            b.bed(x0 + 3, fy, z1 - 2, Direction.NORTH);
        }
        // forecourt with a bell
        b.path(x0, z1 + 1, x1, z1 + 4);
        b.set(mid + 3, 1, z1 + 3, b.p.stone);
        b.set(mid + 3, 2, z1 + 3, Blocks.BELL.defaultBlockState().setValue(BellBlock.ATTACHMENT, BellAttachType.FLOOR).setValue(BellBlock.FACING, Direction.SOUTH));
        b.set(mid - 3, 1, z1 + 3, b.p.fence); b.lantern(mid - 3, 2, z1 + 3, false);
        if (level >= 4) {
            int th = h + 6;
            tower(b, x1 - 1, z0 - 0, 5, th);
            if (level >= 5) tower(b, x0 - 3, z0, 5, th);
            if (level >= 6) { tower(b, x0 - 3, z1 - 4, 5, th - 2); tower(b, x1 - 1, z1 - 4, 5, th - 2); }
        }
    }

    /** Square stone tower with its west/north corner at (x,z), size s, ladder up and battlements. */
    static void tower(B b, int x, int z, int s, int h) {
        x = Math.max(x, 0);
        int x1 = x + s - 1, z1 = z + s - 1;
        b.fill(x, 0, z, x1, h, z1, b.p.stone);
        b.clear(x + 1, 1, z + 1, x1 - 1, h - 1, z1 - 1);
        b.fill(x + 1, h, z + 1, x1 - 1, h, z1 - 1, b.p.floor);
        b.ladder(x + 1, 1, h, z + 1, Direction.SOUTH);
        b.set(x + 1, h, z + 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        b.crenels(x, z, x1, z1, h + 1);
        b.set((x + x1) / 2, 2, z1, Blocks.AIR.defaultBlockState());
        for (int y = 4; y < h - 1; y += 4) b.set((x + x1) / 2, y, z, Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.EAST, true).setValue(IronBarsBlock.WEST, true));
        b.lantern(x + 2, h + 1, z + 2, false);
    }

    static void cottage(B b, int level) {
        int w = level == 1 ? 7 : 9, d = level == 1 ? 6 : level == 2 ? 7 : 9, floors = level == 3 ? 2 : 1;
        int x0 = 1, z0 = 1, x1 = x0 + w - 1, z1 = z0 + d - 1, mid = (x0 + x1) / 2, fh = 4, h = floors * fh;
        b.clear(0, 1, 0, x1 + 1, h + d / 2 + 3, z1 + 2);
        b.body(x0, z0, x1, z1, fh, b.p.wall, true);
        if (floors == 2) b.upperWalls(x0, z0, x1, z1, fh + 1, 2 * fh);
        b.door(mid, 1, z1, Direction.SOUTH);
        b.windows(x0, z0, x1, z1, 2, 2);
        if (floors == 2) b.windows(x0, z0, x1, z1, fh + 2, 2);
        b.gable(x0, z0, x1, z1, h + 1, w >= d);
        b.bed(x0 + 1, 1, z0 + 2, Direction.NORTH);
        if (level >= 2) b.bed(x1 - 1, 1, z0 + 2, Direction.NORTH);
        b.set(mid, 1, z0 + 1, Blocks.CRAFTING_TABLE);
        b.chest(mid + 1, 1, z0 + 1, Direction.SOUTH);
        b.set(x1 - 1, 1, z1 - 1, Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, Direction.WEST));
        b.lantern(mid, fh, (z0 + z1) / 2, true);
        if (floors == 2) {
            b.ladder(x0 + 1, 1, fh + 1, z1 - 1, Direction.NORTH);
            b.bed(x0 + 2, fh + 2, z0 + 2, Direction.NORTH);
            b.bed(x1 - 2, fh + 2, z0 + 2, Direction.NORTH);
            b.lantern(mid, 2 * fh, (z0 + z1) / 2, true);
        }
        b.set(x0 + 1, 1, z1 + 1, Blocks.FLOWER_POT);
        b.path(mid, z1 + 1, mid, z1 + 2);
    }

    static void buildersYard(B b, int level) {
        int w = 7 + level * 2, d = 7 + level;
        int x0 = 1, z0 = 1, x1 = x0 + w - 1, z1 = z0 + d - 1;
        b.clear(0, 1, 0, x1 + 1, 8, z1 + 1);
        b.fill(x0, 0, z0, x1, 0, z1, b.p.path);
        // open shed against the back wall
        int sz1 = z0 + 3;
        for (int x = x0; x <= x1; x++) for (int y = 1; y <= 3; y++) b.set(x, y, z0, b.p.wall);
        for (int y = 1; y <= 3; y++) { b.set(x0, y, sz1, b.p.frame); b.set(x1, y, sz1, b.p.frame); b.set(x0, y, z0, b.p.frame); b.set(x1, y, z0, b.p.frame); }
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= sz1 + 1; z++) b.slab(x, 4, z, b.p.roofSlab, SlabType.BOTTOM);
        b.set(x0 + 1, 1, z0 + 1, Blocks.CRAFTING_TABLE);
        b.set(x0 + 2, 1, z0 + 1, Blocks.STONECUTTER);
        b.chest(x0 + 3, 1, z0 + 1, Direction.SOUTH);
        b.barrel(x0 + 4, 1, z0 + 1);
        b.lantern((x0 + x1) / 2, 3, z0 + 2, true);
        // material piles in the yard
        b.fill(x1 - 2, 1, z1 - 2, x1 - 1, 1, z1 - 1, b.p.stone);
        b.set(x1 - 2, 2, z1 - 2, b.p.stone);
        for (int x = x0 + 1; x <= x0 + 3; x++) b.set(x, 1, z1 - 1, b.p.log.hasProperty(RotatedPillarBlock.AXIS) ? b.p.log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z) : b.p.log);
        for (int y = 1; y <= level; y++) b.set(x1 - 1, y, z0 + 5 > z1 ? z1 : z0 + 5, Blocks.SCAFFOLDING);
        // fence around the yard with a gate
        for (int x = x0; x <= x1; x++) if (x != (x0 + x1) / 2) b.set(x, 1, z1, b.p.fence);
        for (int z = sz1 + 1; z < z1; z++) { b.set(x0, 1, z, b.p.fence); b.set(x1, 1, z, b.p.fence); }
        b.set((x0 + x1) / 2, 1, z1, b.p.fenceGate.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.SOUTH));
    }

    static void storehouse(B b, int level) {
        int w = 7 + level * 2, d = 6 + level, floors = level == 3 ? 2 : 1;
        int x0 = 1, z0 = 1, x1 = x0 + w - 1, z1 = z0 + d - 1, mid = (x0 + x1) / 2, fh = 5, h = floors * fh;
        b.clear(0, 1, 0, x1 + 1, h + d / 2 + 3, z1 + 2);
        b.body(x0, z0, x1, z1, fh, b.p.wall, true);
        if (floors == 2) b.upperWalls(x0, z0, x1, z1, fh + 1, 2 * fh);
        b.door(mid, 1, z1, Direction.SOUTH);
        b.set(mid - 1, 1, z1, b.p.frame); b.set(mid + 1, 1, z1, b.p.frame);
        b.windows(x0, z0, x1, z1, 3, 3);
        b.gable(x0, z0, x1, z1, h + 1, true);
        for (int x = x0 + 1; x <= x1 - 1; x++) {
            b.barrel(x, 1, z0 + 1);
            if (x % 2 == 0) b.barrel(x, 2, z0 + 1);
        }
        for (int z = z0 + 2; z <= z1 - 2; z++) { b.chest(x0 + 1, 1, z, Direction.EAST); b.chest(x1 - 1, 1, z, Direction.WEST); }
        for (int z = z0 + 2; z <= z1 - 2; z += 2) { b.set(x0 + 1, 2, z, Blocks.HAY_BLOCK); b.set(x1 - 1, 2, z, Blocks.BARREL.defaultBlockState()); }
        b.lantern(mid, fh, (z0 + z1) / 2, true);
        if (floors == 2) {
            b.ladder(x1 - 1, 3, fh + 1, z1 - 1, Direction.NORTH);
            b.set(x1 - 1, 1, z1 - 1, b.p.floor); b.set(x1 - 1, 2, z1 - 1, b.p.floor);
            for (int x = x0 + 1; x <= x1 - 3; x++) b.barrel(x, fh + 2, z0 + 1);
        }
        b.path(mid - 1, z1 + 1, mid + 1, z1 + 1);
    }

    static void smithy(B b, int level) {
        int w = 6 + level * 2, d = 6 + level;
        int x0 = 1, z0 = 1, x1 = x0 + w - 1, z1 = z0 + d - 1, h = 4;
        b.clear(0, 1, 0, x1 + 1, h + d / 2 + 4, z1 + 1);
        b.fill(x0, 0, z0, x1, 0, z1, b.p.stone);
        // back and west walls of stone; open front on posts
        for (int y = 1; y <= h; y++) {
            for (int x = x0; x <= x1; x++) b.set(x, y, z0, b.p.stone);
            for (int z = z0; z <= z1; z++) b.set(x0, y, z, b.p.stone);
            b.set(x1, y, z0, b.p.frame); b.set(x1, y, z1, b.p.frame); b.set(x0, y, z1, b.p.frame);
            if (level >= 2) b.set(x1, y, (z0 + z1) / 2, b.p.frame);
        }
        for (int z = z0 + 1; z <= z1 - 1; z++) b.set(x1, 1, z, b.p.fence);
        b.gable(x0, z0, x1, z1, h + 1, true);
        // forge: blast furnace and a fenced lava cauldron by the chimney
        b.set(x0 + 1, 1, z0 + 1, Blocks.BLAST_FURNACE.defaultBlockState().setValue(BlastFurnaceBlock.FACING, Direction.SOUTH));
        b.set(x0 + 2, 1, z0 + 1, Blocks.LAVA_CAULDRON);
        b.set(x0 + 3, 1, z0 + 1, Blocks.SMOKER.defaultBlockState().setValue(SmokerBlock.FACING, Direction.SOUTH));
        int cy = h + d / 2 + 3;
        for (int y = 1; y <= cy; y++) b.set(x0 + 2, y, z0, b.p.stone);
        b.set(x0 + 2, cy + 1, z0,Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, false));
        b.set(x0 + 2, 1, z0 + 2, Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.EAST));
        b.set(x0 + 4, 1, z0 + 3, Blocks.SMITHING_TABLE);
        b.set(x0 + 1, 1, z0 + 3, Blocks.GRINDSTONE.defaultBlockState().setValue(GrindstoneBlock.FACE, AttachFace.FLOOR));
        b.chest(x0 + 1, 1, z1 - 1, Direction.EAST);
        b.barrel(x0 + 1, 1, z1 - 2);
        b.set(x0 + 1, 2, z1 - 2, Blocks.CHAIN);
        if (level >= 2) b.set(x0 + 4, 1, z0 + 1, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        if (level >= 3) { b.set(x0 + 5, 1, z0 + 3, Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.EAST)); b.set(x0 + 6, 1, z0 + 1, Blocks.FLETCHING_TABLE); }
        b.lantern((x0 + x1) / 2, h, (z0 + z1) / 2, true);
    }

    static void library(B b, int level) {
        int w = 7 + level * 2, d = 5 + level * 2, floors = level == 3 ? 2 : 1;
        int x0 = 1, z0 = 1, x1 = x0 + w - 1, z1 = z0 + d - 1, mid = (x0 + x1) / 2, fh = 5, h = floors * fh;
        b.clear(0, 1, 0, x1 + 1, h + d / 2 + 3, z1 + 2);
        b.body(x0, z0, x1, z1, fh, b.p.stone, true);
        if (floors == 2) b.upperWalls(x0, z0, x1, z1, fh + 1, 2 * fh);
        b.door(mid, 1, z1, Direction.SOUTH);
        b.windows(x0, z0, x1, z1, 3, 2);
        b.gable(x0, z0, x1, z1, h + 1, true);
        for (int x = x0 + 1; x <= x1 - 1; x++) for (int y = 1; y <= 3; y++) b.set(x, y, z0 + 1, Blocks.BOOKSHELF);
        for (int z = z0 + 2; z <= z1 - 2; z++) for (int y = 1; y <= 2; y++) { b.set(x0 + 1, y, z, Blocks.BOOKSHELF); b.set(x1 - 1, y, z, Blocks.BOOKSHELF); }
        b.set(mid - 2, 1, z0 + 3, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.SOUTH));
        b.set(mid + 2, 1, z0 + 3, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.SOUTH));
        b.table(mid, 1, z0 + 4); b.chair(mid, 1, z0 + 5, Direction.NORTH);
        if (level >= 3) b.set(mid, 1, z0 + 2, Blocks.ENCHANTING_TABLE);
        b.set(mid, 1, z1 - 1, b.p.carpet);
        b.lantern(mid, fh, (z0 + z1) / 2, true);
        if (floors == 2) {
            b.ladder(x1 - 2, 1, fh + 1, z1 - 1, Direction.NORTH);
            for (int x = x0 + 1; x <= x1 - 1; x++) b.set(x, fh + 2, z0 + 1, Blocks.BOOKSHELF);
            b.set(mid, fh + 2, z0 + 3, Blocks.CARTOGRAPHY_TABLE);
            b.lantern(mid, 2 * fh, (z0 + z1) / 2, true);
        }
    }

    static void barracks(B b, int level) {
        int w = 7 + level * 4, d = 7 + (level - 1) * 2;
        int x0 = 1, z0 = 1, x1 = x0 + w - 1, z1 = z0 + d - 1, mid = (x0 + x1) / 2, h = 4;
        int yard = 6;
        b.clear(0, 1, 0, x1 + 1, h + d / 2 + 3, z1 + yard + 1);
        b.body(x0, z0, x1, z1, h, b.p.stone, true);
        b.door(mid, 1, z1, Direction.SOUTH);
        b.windows(x0, z0, x1, z1, 2, 3);
        b.gable(x0, z0, x1, z1, h + 1, true);
        for (int x = x0 + 1; x <= x1 - 1; x += 2) { b.bed(x, 1, z0 + 2, Direction.NORTH); b.chest(x, 1, z0 + 3, Direction.SOUTH); }
        b.set(x1 - 1, 1, z1 - 1, Blocks.SMITHING_TABLE);
        b.set(x0 + 1, 1, z1 - 1, Blocks.GRINDSTONE.defaultBlockState().setValue(GrindstoneBlock.FACE, AttachFace.FLOOR));
        b.wallBanner(mid - 2, 3, z1 - 1, Direction.NORTH);
        b.wallBanner(mid + 2, 3, z1 - 1, Direction.NORTH);
        for (int x = x0 + 2; x <= x1 - 2; x += 4) b.lantern(x, h, (z0 + z1) / 2, true);
        // training yard: fence, targets, straw dummies
        int y0 = z1 + 1, y1 = z1 + yard;
        b.fill(x0, 0, y0, x1, 0, y1, b.p.path);
        for (int x = x0; x <= x1; x++) b.set(x, 1, y1, b.p.fence);
        for (int z = y0; z <= y1; z++) { b.set(x0, 1, z, b.p.fence); b.set(x1, 1, z, b.p.fence); }
        b.set(mid, 1, y1, b.p.fenceGate.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.SOUTH));
        for (int x = x0 + 2; x <= x1 - 2; x += 3) {
            b.set(x, 1, y1 - 2, b.p.fence);
            b.set(x, 2, y1 - 2, Blocks.HAY_BLOCK);
            b.set(x, 3, y1 - 2, Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(CarvedPumpkinBlock.FACING, Direction.NORTH));
        }
        b.set(x1 - 1, 1, y0 + 1, Blocks.TARGET);
        b.set(x1 - 1, 2, y0 + 1, Blocks.TARGET);
        b.set(x0 + 1, 1, y0 + 1, Blocks.HAY_BLOCK);
    }

    static void watchtower(B b, int level) {
        int s = level == 1 ? 5 : 7, h = level == 1 ? 10 : 14;
        b.clear(0, 1, 0, s + 1, h + 6, s + 1);
        tower(b, 1, 1, s, h);
        b.set(1 + s / 2, 1, s, Blocks.AIR.defaultBlockState());
        b.door(1 + s / 2, 1, s, Direction.SOUTH);
        if (level == 2) {
            for (int y = 1; y <= 2; y++) for (int x = 2; x <= s - 1; x += s - 3) for (int z = 2; z <= s - 1; z += s - 3) b.set(x + (x == 2 ? 0 : 1), h + y, z + (z == 2 ? 0 : 1), b.p.fence);
            b.gable(2, 2, s - 1 + 1, s - 1 + 1, h + 3, true);
        }
    }

    static void wall(B b) {
        // 9 long, 3 thick, walk on top, battlements on the outer (north) side
        b.clear(0, 1, 0, 8, 7, 2);
        b.fill(0, 0, 0, 8, 5, 2, b.p.stone);
        for (int x = 0; x <= 8; x += 2) b.set(x, 6, 0, b.p.stone);
        for (int x = 0; x <= 8; x++) b.set(x, 6, 2, b.p.stoneWall.defaultBlockState());
        b.set(4, 7, 2, Blocks.LANTERN.defaultBlockState());
    }

    static void gatehouse(B b) {
        b.clear(0, 1, 0, 12, 12, 6);
        tower(b, 0, 0, 4, 10);
        tower(b, 9, 0, 4, 10);
        b.fill(4, 0, 0, 8, 7, 4, b.p.stone);
        b.clear(5, 1, 0, 7, 4, 4);
        b.fill(5, 0, 0, 7, 0, 4, b.p.path);
        for (int x = 4; x <= 8; x += 2) b.set(x, 8, 0, b.p.stone);
        for (int x = 4; x <= 8; x++) b.set(x, 8, 4, b.p.stoneWall.defaultBlockState());
        b.wallBanner(4, 5, 5, Direction.SOUTH);
        b.wallBanner(8, 5, 5, Direction.SOUTH);
        b.set(4, 5, 5, Blocks.AIR.defaultBlockState());
        b.set(8, 5, 5, Blocks.AIR.defaultBlockState());
        b.wallBanner(4, 5, 5, Direction.SOUTH);
        b.wallBanner(8, 5, 5, Direction.SOUTH);
        b.wallTorch(4, 3, 5, Direction.SOUTH);
        b.wallTorch(8, 3, 5, Direction.SOUTH);
    }

    static void farmField(B b, int level) {
        int s = level == 1 ? 9 : 13;
        b.clear(0, 1, 0, s + 1, 3, s + 1);
        b.fill(0, 0, 0, s + 1, 0, s + 1, b.p.log.hasProperty(RotatedPillarBlock.AXIS) ? b.p.log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.X) : b.p.log);
        BlockState farmland = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7);
        b.fill(1, 0, 1, s, 0, s, farmland);
        int c = (s + 1) / 2;
        b.set(c, 0, c, Blocks.WATER);
        if (level == 2) { b.set(c - 4, 0, c, Blocks.WATER); b.set(c + 4, 0, c, Blocks.WATER); }
        for (int x = 1; x <= s; x++) for (int z = 1; z <= s; z++) {
            if (b.get(x, 0, z) != null && b.get(x, 0, z).is(Blocks.WATER)) { b.set(x, 1, z, Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.BOTTOM)); continue; }
            Block crop = z < c ? Blocks.WHEAT : x < c ? Blocks.CARROTS : Blocks.POTATOES;
            b.set(x, 1, z, crop.defaultBlockState().setValue(CropBlock.AGE, 7));
        }
        for (int x = 0; x <= s + 1; x++) { b.set(x, 1, 0, b.p.fence); b.set(x, 1, s + 1, b.p.fence); }
        for (int z = 0; z <= s + 1; z++) { b.set(0, 1, z, b.p.fence); b.set(s + 1, 1, z, b.p.fence); }
        b.set(c, 1, s + 1, b.p.fenceGate.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.SOUTH));
        b.set(s + 1, 1, s + 1, Blocks.COMPOSTER);
        b.set(0, 2, 0, Blocks.TORCH); b.set(s + 1, 2, 0, Blocks.TORCH); b.set(0, 2, s + 1, Blocks.TORCH);
    }

    static void stables(B b, int level) {
        int w = level == 1 ? 9 : 13, d = level == 1 ? 7 : 9, h = 4;
        int x0 = 1, z0 = 1, x1 = x0 + w - 1, z1 = z0 + d - 1;
        b.clear(0, 1, 0, x1 + 1, h + d / 2 + 3, z1 + 1);
        b.fill(x0, 0, z0, x1, 0, z1, b.p.path);
        for (int x = x0; x <= x1; x++) for (int y = 1; y <= h; y++) b.set(x, y, z0, b.p.wall);
        for (int x = x0; x <= x1; x += 4) for (int y = 1; y <= h; y++) { b.set(x, y, z1, b.p.frame); b.set(x, y, z0, b.p.frame); }
        b.gable(x0, z0, x1, z1, h + 1, true);
        for (int x = x0; x <= x1; x += 4) for (int z = z0 + 1; z <= z1 - 2; z++) b.set(x, 1, z, b.p.fence);
        for (int x = x0 + 1; x <= x1 - 1; x++) {
            if (x % 4 == 1) continue;
            b.set(x, 1, z0 + 1, (x % 4 == 2) ? Blocks.HAY_BLOCK.defaultBlockState() : Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        }
        for (int x = x0 + 2; x <= x1 - 2; x += 4) b.lantern(x, h, (z0 + z1) / 2, true);
    }

    static void tavern(B b, int level) {
        int w = level == 1 ? 11 : 13, d = level == 1 ? 9 : 11, fh = 4, h = 2 * fh;
        int x0 = 1, z0 = 1, x1 = x0 + w - 1, z1 = z0 + d - 1, mid = (x0 + x1) / 2;
        b.clear(0, 1, 0, x1 + 1, h + d / 2 + 3, z1 + 2);
        b.body(x0, z0, x1, z1, fh, b.p.stone, true);
        b.upperWalls(x0, z0, x1, z1, fh + 1, h);
        b.door(mid, 1, z1, Direction.SOUTH);
        b.windows(x0, z0, x1, z1, 2, 2);
        b.windows(x0, z0, x1, z1, fh + 2, 2);
        b.gable(x0, z0, x1, z1, h + 1, true);
        // bar counter along the back, barrels behind it
        for (int x = x0 + 2; x <= x1 - 2; x++) { b.slab(x, 1, z0 + 2, b.p.plankSlab, SlabType.DOUBLE); b.barrel(x, 1, z0 + 1); }
        for (int x = x0 + 2; x <= x1 - 2; x += 3) b.set(x, 2, z0 + 2, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true));
        for (int x = x0 + 2; x <= x1 - 2; x += 4) { b.table(x, 1, z1 - 3); b.chair(x - 1, 1, z1 - 3, Direction.EAST); b.chair(x + 1, 1, z1 - 3, Direction.WEST); }
        b.set(x1 - 1, 1, z1 - 1, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, false));
        b.lantern(mid, fh, (z0 + z1) / 2, true);
        b.ladder(x0 + 1, 1, fh + 1, z1 - 1, Direction.NORTH);
        for (int x = x0 + 2; x <= x1 - 2; x += 3) b.bed(x, fh + 2, z0 + 2, Direction.NORTH);
        if (level == 2) for (int x = x0 + 3; x <= x1 - 3; x += 3) b.bed(x, fh + 2, z1 - 2, Direction.SOUTH);
        b.lantern(mid, h, (z0 + z1) / 2, true);
        b.set(mid + 2, 3, z1 + 1, Blocks.AIR.defaultBlockState());
    }

    static void chapel(B b) {
        int x0 = 1, z0 = 1, x1 = 9, z1 = 14, mid = 5, h = 6;
        b.clear(0, 1, 0, 10, 18, 15);
        b.body(x0, z0, x1, z1, h, b.p.stone, false);
        b.door(mid, 1, z1, Direction.SOUTH);
        b.windows(x0, z0, x1, z1, 3, 2);
        b.windows(x0, z0, x1, z1, 4, 2);
        b.gable(x0, z0, x1, z1, h + 1, false);
        for (int z = z0 + 4; z <= z1 - 2; z += 2) {
            for (int x = x0 + 1; x <= mid - 1; x++) b.chair(x, 1, z, Direction.SOUTH);
            for (int x = mid + 1; x <= x1 - 1; x++) b.chair(x, 1, z, Direction.SOUTH);
        }
        for (int z = z0 + 2; z <= z1 - 1; z++) b.set(mid, 1, z, b.p.carpet);
        b.set(mid, 1, z0 + 1, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.SOUTH));
        b.set(mid - 1, 1, z0 + 1, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true).setValue(CandleBlock.CANDLES, 3));
        b.set(mid + 1, 1, z0 + 1, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true).setValue(CandleBlock.CANDLES, 3));
        for (int z = z0 + 3; z <= z1 - 2; z += 4) b.lantern(mid, h + 2, z, true);
        // bell tower over the back
        tower(b, mid - 2, z0 - 1 < 0 ? 0 : 0, 5, h + 8);
        b.set(mid, h + 7, 2, Blocks.BELL.defaultBlockState().setValue(BellBlock.ATTACHMENT, BellAttachType.FLOOR));
    }

    static void market(B b, int level) {
        int s = level == 1 ? 11 : 15, c = s / 2;
        b.clear(0, 1, 0, s - 1, 5, s - 1);
        b.fill(0, 0, 0, s - 1, 0, s - 1, b.p.path);
        Block[] wools = {Blocks.RED_WOOL, Blocks.YELLOW_WOOL, Blocks.BLUE_WOOL, Blocks.GREEN_WOOL, Blocks.ORANGE_WOOL, Blocks.PURPLE_WOOL, Blocks.WHITE_WOOL, Blocks.CYAN_WOOL};
        int[][] spots = level == 1 ? new int[][]{{1, 1}, {s - 4, 1}, {1, s - 4}, {s - 4, s - 4}}
                : new int[][]{{1, 1}, {c - 1, 1}, {s - 4, 1}, {1, c - 1}, {s - 4, c - 1}, {1, s - 4}, {c - 1, s - 4}, {s - 4, s - 4}};
        for (int i = 0; i < spots.length; i++) stall(b, spots[i][0], spots[i][1], wools[i % wools.length]);
        if (level == 2) well(b, c - 2, c - 2);
        else { b.set(c, 1, c, b.p.fence); b.lantern(c, 2, c, false); }
    }

    static void stall(B b, int x, int z, Block wool) {
        for (int dx = 0; dx <= 2; dx += 2) for (int dz = 0; dz <= 2; dz += 2) for (int y = 1; y <= 2; y++) b.set(x + dx, y, z + dz, b.p.fence);
        for (int dx = 0; dx <= 2; dx++) for (int dz = 0; dz <= 2; dz++) b.set(x + dx, 3, z + dz, wool);
        b.slab(x + 1, 1, z + 1, b.p.plankSlab, SlabType.DOUBLE);
        b.barrel(x + 1, 1, z);
    }

    static void lumberCamp(B b, int level) {
        int w = level == 1 ? 9 : 12, d = 7;
        int x0 = 1, z0 = 1, x1 = x0 + w - 1, z1 = z0 + d - 1, h = 4;
        b.clear(0, 1, 0, x1 + 1, h + 6, z1 + 1);
        b.fill(x0, 0, z0, x1, 0, z1, b.p.path);
        for (int y = 1; y <= h; y++) { b.set(x0, y, z0, b.p.frame); b.set(x1, y, z0, b.p.frame); b.set(x0, y, z0 + 3, b.p.frame); b.set(x1, y, z0 + 3, b.p.frame); }
        for (int x = x0; x <= x1; x++) for (int y = 1; y <= 2; y++) b.set(x, y, z0, b.p.wall);
        for (int x = x0 - 1; x <= x1 + 1; x++) for (int z = z0 - 1; z <= z0 + 4; z++) b.slab(x, h + 1, z, b.p.roofSlab, SlabType.BOTTOM);
        BlockState logX = b.p.log.hasProperty(RotatedPillarBlock.AXIS) ? b.p.log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.X) : b.p.log;
        for (int x = x0 + 1; x <= x1 - 1; x++) { b.set(x, 1, z0 + 1, logX); b.set(x, 1, z0 + 2, logX); if (x % 2 == 0) b.set(x, 2, z0 + 1, logX); }
        b.set(x0 + 2, 1, z1 - 1, Blocks.STRIPPED_OAK_LOG);
        b.set(x0 + 4, 1, z1 - 1, Blocks.STONECUTTER);
        b.chest(x1 - 1, 1, z1 - 1, Direction.WEST);
        b.lantern((x0 + x1) / 2, h, z0 + 2, true);
        if (level == 2) for (int x = x0 + 1; x <= x1 - 1; x += 2) b.set(x, 1, z1, Blocks.OAK_SAPLING);
    }

    static void mine(B b, int level) {
        int sink = sink("mine"), g = sink; // ground level inside the blueprint
        int w = 7, d = 7, x0 = 1, z0 = 1, x1 = x0 + w - 1, z1 = z0 + d - 1, mid = 4;
        // hut on top
        b.clear(0, g + 1, 0, x1 + 1, g + 9, z1 + 1);
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) b.set(x, g, z, b.p.stone);
        for (int y = g + 1; y <= g + 4; y++) {
            for (int x = x0; x <= x1; x++) { b.set(x, y, z0, b.p.stone); b.set(x, y, z1, b.p.stone); }
            for (int z = z0; z <= z1; z++) { b.set(x0, y, z, b.p.stone); b.set(x1, y, z, b.p.stone); }
        }
        b.door(mid, g + 1, z1, Direction.SOUTH);
        b.gable(x0, z0, x1, z1, g + 5, true);
        b.set(x0 + 1, g + 1, z0 + 1, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        b.set(x1 - 1, g + 1, z0 + 1, Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, Direction.SOUTH));
        b.lantern(mid, g + 4, mid, true);
        // shaft and chamber
        b.clear(mid - 1, 1, mid - 1, mid + 1, g, mid + 1);
        b.ladder(mid, 1, g, mid - 1, Direction.SOUTH);
        b.set(mid, g, mid - 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        int r = level + 1;
        b.clear(mid - r, 1, mid - r, mid + r, 3, mid + r);
        b.fill(mid - r, 0, mid - r, mid + r, 0, mid + r, Blocks.COBBLESTONE.defaultBlockState());
        for (int x = mid - r; x <= mid + r; x += 2) { b.set(x, 1, mid + r, b.p.fence); b.set(x, 2, mid + r, b.p.fence); b.set(x, 3, mid + r, b.p.floor); }
        b.lantern(mid - r + 1, 3, mid - r + 1, true);
        b.lantern(mid + r - 1, 3, mid + r - 1, true);
        b.set(mid + 1, 1, mid + 1, Blocks.RAIL);
    }

    static void well(B b) { well(b, 0, 0); }

    static void well(B b, int x, int z) {
        b.clear(x, 1, z, x + 4, 5, z + 4);
        b.fill(x, 0, z, x + 4, 0, z + 4, b.p.stone);
        b.fill(x + 1, 0, z + 1, x + 3, 0, z + 3, Blocks.WATER.defaultBlockState());
        for (int i = 0; i <= 4; i++) { b.set(x + i, 1, z, b.p.stone); b.set(x + i, 1, z + 4, b.p.stone); b.set(x, 1, z + i, b.p.stone); b.set(x + 4, 1, z + i, b.p.stone); }
        for (int y = 2; y <= 3; y++) { b.set(x, y, z, b.p.fence); b.set(x + 4, y, z, b.p.fence); b.set(x, y, z + 4, b.p.fence); b.set(x + 4, y, z + 4, b.p.fence); }
        for (int i = 0; i <= 4; i++) for (int j = 0; j <= 4; j++) b.slab(x + i, 4, z + j, b.p.roofSlab, SlabType.BOTTOM);
        b.lantern(x + 2, 3, z + 2, true);
    }

    static void garden(B b) {
        b.clear(0, 1, 0, 8, 6, 8);
        b.fill(0, 0, 0, 8, 0, 8, Blocks.GRASS_BLOCK.defaultBlockState());
        for (int i = 0; i <= 8; i++) { b.set(4, 0, i, b.p.path); b.set(i, 0, 4, b.p.path); }
        Block[] flowers = {Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.ALLIUM, Blocks.OXEYE_DAISY, Blocks.AZURE_BLUET};
        int f = 0;
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) {
            if (x == 4 || z == 4) continue;
            if ((x + z) % 3 == 0) b.set(x, 1, z, flowers[f++ % flowers.length]);
        }
        b.set(1, 1, 1, Blocks.OAK_LOG); b.set(1, 2, 1, Blocks.OAK_LOG); b.set(1, 3, 1, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        b.set(7, 1, 7, Blocks.OAK_LOG); b.set(7, 2, 7, Blocks.OAK_LOG); b.set(7, 3, 7, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        b.chair(3, 1, 3, Direction.NORTH); b.chair(5, 1, 5, Direction.SOUTH);
        b.set(4, 1, 4, b.p.fence); b.set(4, 2, 4, b.p.fence); b.lantern(4, 3, 4, false);
    }
}
