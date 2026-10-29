package livingrealms.building;

import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a plot into blocks. Every step costs its block from the town stockpile (or wealth, for AI towns); steps
 * whose block is already right are skipped for free, which is also how repairs and upgrades reuse what stands.
 */
public final class Construction {
    public static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** AI towns pay this much wealth per block instead of materials. */
    public static final double AI_COST = 0.04;

    public enum Result { DONE, PLACED, SKIPPED, MISSING, UNLOADED }

    public record Step(BlockPos pos, BlockState state) {}

    @Nullable
    public static Step step(Settlement s, Plot p, int i) {
        Blueprint bp = p.blueprint();
        if (bp == null) return null;
        int c = p.cut.length, f = c + p.foundation.length;
        if (p.state == Plot.State.DEMOLISHING) {
            // tear down from the top: walk the blueprint backwards, skipping clearing steps
            int j = bp.size() - 1 - i;
            if (j < 0) return null;
            return new Step(bp.worldPos(j, p.origin, p.rotation), Blocks.AIR.defaultBlockState());
        }
        if (i < c) return new Step(BlockPos.of(p.cut[i]), Blocks.AIR.defaultBlockState());
        if (i < f) return new Step(BlockPos.of(p.foundation[i - c]), foundationBlock(s));
        int j = i - f;
        if (j >= bp.size()) return null;
        return new Step(bp.worldPos(j, p.origin, p.rotation), bp.worldState(j, p.rotation));
    }

    public static BlockState foundationBlock(Settlement s) {
        String fam = Procedural.styleFamily(s.style);
        return fam.equals("desert") ? Blocks.SANDSTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
    }

    public static boolean loaded(ServerLevel level, Plot p) {
        BoundingBox b = p.box();
        return level.hasChunksAt(b.minX(), b.minZ(), b.maxX(), b.maxZ());
    }

    /** Advances past steps that need no work. Returns the next step that does, or null when the plot is finished. */
    @Nullable
    public static Step nextWork(ServerLevel level, Settlement s, Plot p, int budget) {
        site(level, s, p);
        int total = p.totalSteps();
        while (p.placed < total && budget-- > 0) {
            Step st = step(s, p, p.placed);
            if (st == null) return null;
            BlockState cur = level.getBlockState(st.pos);
            if (!needsWork(p, cur, st.state)) {
                p.placed++;
                if (p.paid < p.placed) p.paid = p.placed;
                continue;
            }
            return st;
        }
        return p.placed >= total ? null : step(s, p, p.placed);
    }

    static boolean needsWork(Plot p, BlockState cur, BlockState want) {
        if (p.state == Plot.State.DEMOLISHING) return !cur.isAir() && !cur.is(BlockTags.DIRT) && !cur.is(Blocks.GRASS_BLOCK) && !(cur.getBlock() instanceof LiquidBlock);
        if (want.isAir()) return !cur.isAir() && !(cur.getBlock() instanceof LiquidBlock && p.state != Plot.State.BUILDING);
        return cur != want;
    }

    /** One step of work by a builder (paid now). */
    public static Result work(ServerLevel level, Settlement s, Plot p) {
        if (!livingrealms.settlement.AutoBuild.mayWork(s, p)) { p.status = "Waiting for your queued projects or automatic building to resume"; return Result.MISSING; }
        Step st = nextWork(level, s, p, 64);
        if (st == null) return Result.DONE;
        BlockState cur = level.getBlockState(st.pos);
        if (p.state == Plot.State.DEMOLISHING) {
            refund(s, cur);
            level.setBlock(st.pos, Blocks.AIR.defaultBlockState(), FLAGS);
            p.placed++;
            p.paid = Math.max(p.paid, p.placed);
            return Result.PLACED;
        }
        if (!st.state.isAir()) {
            Item cost = Blueprint.costItem(st.state);
            if (cost != null && !pay(s, cost)) {
                p.status = "Waiting for " + cost.getDescription().getString();
                return Result.MISSING;
            }
        }
        salvage(s, cur);
        level.setBlock(st.pos, st.state, FLAGS);
        if (!st.state.isAir()) {
            var sound = st.state.getSoundType();
            level.playSound(null, st.pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1f) / 4f, sound.getPitch() * 0.8f);
        }
        p.placed++;
        p.paid = Math.max(p.paid, p.placed);
        p.status = "";
        return Result.PLACED;
    }

    static boolean pay(Settlement s, Item cost) {
        if (s.isAi()) {
            if (s.wealth < AI_COST) return false;
            s.wealth -= AI_COST;
            return true;
        }
        return s.stock.take(cost, 1);
    }

    /** Pays for steps while the plot is out of loaded range. They are placed when it loads. */
    public static int payAhead(Settlement s, Plot p, int steps) {
        if (!livingrealms.settlement.AutoBuild.mayWork(s, p)) return 0;
        int total = p.totalSteps(), done = 0;
        while (done < steps && p.paid < total) {
            Step st = step(s, p, p.paid);
            if (st == null) break;
            if (!st.state.isAir() && p.state != Plot.State.DEMOLISHING) {
                Item cost = Blueprint.costItem(st.state);
                if (cost != null && !pay(s, cost)) {
                    p.status = "Waiting for " + cost.getDescription().getString();
                    break;
                }
            }
            p.paid++;
            done++;
        }
        return done;
    }

    /** Measures the foundation of a plot planned while unloaded, once its site loads; paid from stock as far as it goes. */
    public static void site(ServerLevel level, Settlement s, Plot p) {
        if (!p.unsited || !loaded(level, p)) return;
        Blueprint bp = p.blueprint();
        Earthworks e = bp == null ? new Earthworks(new long[0], new long[0]) : earthworks(level, s, BuildingCatalog.get(p.typeId), bp, p.origin, p.rotation);
        long[] f = e.fill();
        Item base = Blueprint.costItem(foundationBlock(s));
        if (base != null && !s.isAi()) s.stock.takeUpTo(base, f.length);
        p.foundation = f;
        p.cut = e.cut();
        int n = f.length + e.cut().length;
        if (p.placed > 0) p.placed += n;
        p.paid += n;
        p.unsited = false;
    }

    /** Places what was paid for while the area was unloaded. Returns the steps used. */
    public static int catchUp(ServerLevel level, Settlement s, Plot p, int max) {
        if (!p.deferred && !livingrealms.settlement.AutoBuild.mayWork(s, p)) return 0;
        site(level, s, p);
        int start = max;
        while (p.placed < p.paid && max-- > 0) {
            Step st = step(s, p, p.placed);
            if (st == null) break;
            BlockState cur = level.getBlockState(st.pos);
            if (p.state == Plot.State.DEMOLISHING) {
                if (needsWork(p, cur, st.state)) { refund(s, cur); level.setBlock(st.pos, Blocks.AIR.defaultBlockState(), FLAGS); }
            } else if (cur != st.state) {
                salvage(s, cur);
                level.setBlock(st.pos, st.state, FLAGS);
            }
            p.placed++;
        }
        return start - Math.max(0, max);
    }

    /** Blocks cleared off a site go into the stockpile if they are worth keeping. */
    static void salvage(Settlement s, BlockState cur) {
        if (cur.isAir() || cur.getBlock() instanceof LiquidBlock || cur.is(BlockTags.LEAVES) || cur.getBlock() instanceof BushBlock
                || cur.getBlock() instanceof SnowLayerBlock || cur.canBeReplaced()) return;
        Item it = Blueprint.costItem(cur);
        if (it == null || s.isAi()) return;
        if (cur.is(Blocks.STONE)) it = Items.COBBLESTONE;
        if (cur.is(Blocks.DEEPSLATE)) it = Items.COBBLED_DEEPSLATE;
        s.stock.add(it, 1);
    }

    static void refund(Settlement s, BlockState cur) {
        Item it = Blueprint.costItem(cur);
        if (it != null && !s.isAi()) s.stock.add(it, 1);
    }

    // ------------------------------------------------------------------ siting

    /** Fills under the bottom layer of the building down to solid ground, so it doesn't float on slopes. */
    public static long[] foundation(ServerLevel level, Blueprint bp, BlockPos origin, Rotation rot) {
        Set<Long> cols = new HashSet<>();
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < bp.size(); i++) {
            if (bp.ly(i) != 0 || bp.state(i).isAir()) continue;
            BlockPos w = bp.worldPos(i, origin, rot);
            if (!cols.add(BlockPos.asLong(w.getX(), 0, w.getZ()))) continue;
            for (int y = w.getY() - 1; y >= w.getY() - 12 && y > level.getMinBuildHeight(); y--) {
                BlockPos p = new BlockPos(w.getX(), y, w.getZ());
                BlockState st = level.getBlockState(p);
                if (!(st.canBeReplaced() || st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || st.getBlock() instanceof LiquidBlock)) break;
                out.add(p.asLong());
            }
        }
        // bottom-up so each block has something under it
        out.sort((a, b) -> Integer.compare(BlockPos.getY(a), BlockPos.getY(b)));
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Dug out (air) and built up (foundation block) to level a lot, in building order. */
    public record Earthworks(long[] cut, long[] fill) {}

    /** How far above its floor a building's lot and margin are dug out (whole trees in the margin go). */
    static final int DIG = 6;

    /**
     * Levels a building's lot for the floor at {@code origin}: the foundation under it; everything natural above the
     * floor dug out of its footprint and a one-block margin; a plinth of the foundation block on that margin where the
     * ground is lower; a retaining wall of it one further out where the ground rises behind; and a level apron at the door
     * that steps a block at a time down (or up) to the ground toward the street. Streets, roads and other plots are left
     * alone. Buildings sunk into the ground (mines) and wall pieces only get the foundation. The Capital Castle keeps its
     * courtyard as it is: only its own walls and the margin outside them are levelled.
     */
    public static Earthworks earthworks(ServerLevel level, Settlement s, @Nullable BuildingType t, Blueprint bp, BlockPos origin, Rotation rot) {
        return earthworks(level, s, t, bp, origin, rot, null);
    }

    /** With {@code standing}, the ground a building being enlarged already stands on is left as it is. */
    public static Earthworks earthworks(ServerLevel level, Settlement s, @Nullable BuildingType t, Blueprint bp, BlockPos origin, Rotation rot, @Nullable BoundingBox standing) {
        long[] base = foundation(level, bp, origin, rot);
        if (t == null || t.sink > 0 || t.category == Category.WALL || t.category == Category.ROAD) return new Earthworks(new long[0], base);
        boolean castle = t.id.equals(livingrealms.settlement.CapitalCastles.ID);
        int y = origin.getY(), w = bp.width(rot), d = bp.depth(rot);
        BoundingBox box = new BoundingBox(origin.getX(), y, origin.getZ(), origin.getX() + w - 1, y, origin.getZ() + d - 1);
        Set<Long> roads = livingrealms.settlement.Planning.roadColumns(s, box.inflatedBy(3));
        // Village Expansion towns keep to their own streets, not our skeleton
        boolean skeleton = !(livingrealms.settlement.Realms.on() && !s.layout.isEmpty());
        java.util.function.BiPredicate<Integer, Integer> free = (x, z) -> !roads.contains(BlockPos.asLong(x, 0, z))
                && !(skeleton && s.plan().onStreet(new BoundingBox(x, 0, z, x, 0, z), 0)) && !livingrealms.settlement.Planning.insideBuilding(s, x, z);
        Set<Long> cut = new HashSet<>(), fill = new HashSet<>(), apron = new HashSet<>();
        for (long l : base) fill.add(l);
        // footprint: the castle's own wall columns, else the whole box
        Set<Long> foot = new HashSet<>();
        if (castle) { for (int i = 0; i < bp.size(); i++) if (!bp.state(i).isAir()) { BlockPos q = bp.worldPos(i, origin, rot); foot.add(BlockPos.asLong(q.getX(), 0, q.getZ())); } }
        else for (int x = box.minX(); x <= box.maxX(); x++) for (int z = box.minZ(); z <= box.maxZ(); z++) foot.add(BlockPos.asLong(x, 0, z));
        // (a building's box is levelled right through: some leave the ground bare between their walls and their edge)
        Set<Long> floored = new HashSet<>();
        for (int i = 0; i < bp.size(); i++) if (bp.ly(i) == 0 && !bp.state(i).isAir()) { BlockPos q = bp.worldPos(i, origin, rot); floored.add(BlockPos.asLong(q.getX(), 0, q.getZ())); }
        for (long c : foot) {
            int x = BlockPos.getX(c), z = BlockPos.getZ(c);
            if (standing != null && standing.isInside(x, standing.minY(), z)) continue;
            if (castle || floored.contains(c)) dig(level, x, z, y + 1, cut); else level(level, x, z, y, cut, fill);
        }
        // the apron: level with the floor outside the door, then a block at a time toward the ground
        Direction front = bp.front(rot);
        BlockPos door = bp.doorWorld(origin, rot);
        int floor = door.getY() - 1, half = (front.getAxis() == Direction.Axis.Z ? w : d) >= 13 ? 2 : 1;
        Direction lat = front.getClockWise();
        int ex = switch (front) { case WEST -> box.minX(); case EAST -> box.maxX(); default -> door.getX(); };
        int ez = switch (front) { case NORTH -> box.minZ(); case SOUTH -> box.maxZ(); default -> door.getZ(); };
        int h = floor;
        apronLoop:
        for (int k = 1; k <= 8; k++) {
            int cx = ex + front.getStepX() * k, cz = ez + front.getStepZ() * k;
            if (!free.test(cx, cz)) break;
            int g = soil(level, cx, cz);
            if (k >= 2) { if (Math.abs(g - h) <= 1) break; h += Integer.signum(g - h); }
            for (int l = -half; l <= half; l++) {
                int x = cx + lat.getStepX() * l, z = cz + lat.getStepZ() * l;
                if (!free.test(x, z)) { if (l == 0) break apronLoop; continue; }
                apron.add(BlockPos.asLong(x, 0, z));
                level(level, x, z, h, cut, fill);
            }
        }
        // the margin: dug or built up to the floor; a retaining wall where the ground rises beyond it
        for (int x = box.minX() - 2; x <= box.maxX() + 2; x++) for (int z = box.minZ() - 2; z <= box.maxZ() + 2; z++) {
            if (box.isInside(x, y, z)) continue;
            long c = BlockPos.asLong(x, 0, z);
            if (apron.contains(c) || !free.test(x, z)) continue;
            boolean inner = x >= box.minX() - 1 && x <= box.maxX() + 1 && z >= box.minZ() - 1 && z <= box.maxZ() + 1;
            if (inner) { level(level, x, z, y, cut, fill); continue; }
            int g = soil(level, x, z);
            if (g >= y + 2) for (int yy = y + 1; yy <= Math.min(g, y + DIG); yy++) {
                BlockState st = level.getBlockState(new BlockPos(x, yy, z));
                if (ground(st) && !st.isAir() && !st.canBeReplaced() && st.getFluidState().isEmpty()) fill.add(BlockPos.asLong(x, yy, z));
            }
        }
        fill.removeAll(cut);
        return new Earthworks(sorted(cut, false), sorted(fill, true));
    }

    /** A column dug out above {@code y} or built up to it with the foundation block (at most 12 deep). */
    static void level(ServerLevel level, int x, int z, int y, Set<Long> cut, Set<Long> fill) {
        int g = soil(level, x, z);
        if (g > y || level.getBlockState(new BlockPos(x, g + 1, z)).is(BlockTags.LOGS)) dig(level, x, z, y + 1, cut);
        for (int yy = Math.max(g + 1, y - 12); yy <= y; yy++) {
            BlockState st = level.getBlockState(new BlockPos(x, yy, z));
            if (st.canBeReplaced() || st.is(BlockTags.LEAVES) || st.getBlock() instanceof LiquidBlock) fill.add(BlockPos.asLong(x, yy, z));
        }
    }

    /** Bare ground from {@code from} up, {@link #DIG} high, and the whole trunk of a tree (logs that end in leaves). */
    static void dig(ServerLevel level, int x, int z, int from, Set<Long> cut) {
        int top = ground(level, x, z) + 1, to = Math.min(top, from + DIG - 1);
        boolean tree = false;
        for (int yy = from; yy <= top + 1 && !tree; yy++) {
            if (!level.getBlockState(new BlockPos(x, yy, z)).is(BlockTags.LOGS)) continue;
            int up = yy;
            while (level.getBlockState(new BlockPos(x, up, z)).is(BlockTags.LOGS)) up++;
            tree = level.getBlockState(new BlockPos(x, up, z)).is(BlockTags.LEAVES);
            if (!tree) return;
        }
        if (tree) to = top;
        for (int yy = from; yy <= to; yy++) {
            BlockState st = level.getBlockState(new BlockPos(x, yy, z));
            if (!st.isAir() && st.getFluidState().isEmpty() && !st.hasBlockEntity() && (ground(st) || tree && st.is(BlockTags.LOGS))) cut.add(BlockPos.asLong(x, yy, z));
        }
    }

    /** Bare ground that may be dug away or faced to level a lot: soil, rock, sand, gravel, snow and plants, never what
     * anyone built (village houses use sandstone, terracotta, ice and logs, so those stay). */
    public static boolean ground(BlockState s) {
        if (s.canBeReplaced() || s.getBlock() instanceof BushBlock || s.is(BlockTags.LEAVES)) return true;
        return s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY)
                || s.is(BlockTags.SNOW) || s.is(Blocks.SNOW_BLOCK);
    }

    /** Top of the ground itself at x,z: below any tree, plants or snow. */
    public static int soil(ServerLevel level, int x, int z) {
        int y = ground(level, x, z);
        while (y > level.getMinBuildHeight()) {
            BlockState st = level.getBlockState(new BlockPos(x, y, z));
            if (!(st.isAir() || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.canBeReplaced() && st.getFluidState().isEmpty())) break;
            y--;
        }
        return y;
    }

    /** Positions ordered bottom-up (each block has something under it) or top-down (nothing left hanging over a dig). */
    static long[] sorted(Set<Long> set, boolean up) {
        long[] a = set.stream().sorted((p, q) -> up ? Integer.compare(BlockPos.getY(p), BlockPos.getY(q)) : Integer.compare(BlockPos.getY(q), BlockPos.getY(p)))
                .mapToLong(Long::longValue).toArray();
        return a;
    }

    /** Ground height (y of the top solid block) at x,z. */
    public static int ground(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    /** Blocks that may be built over: terrain and plants, but no one's walls or floors. */
    public static boolean natural(BlockState s) {
        if (s.isAir() || s.canBeReplaced() || s.getBlock() instanceof BushBlock || s.getBlock() instanceof LiquidBlock) return true;
        return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY)
                || s.is(BlockTags.CORAL_PLANTS) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(BlockTags.SAPLINGS)
                || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.BAMBOO) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.CACTUS)
                || s.is(BlockTags.FLOWERS) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.MUD) || s.is(Blocks.POINTED_DRIPSTONE);
    }

    private Construction() {}
}
