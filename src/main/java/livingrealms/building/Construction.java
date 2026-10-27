package livingrealms.building;

import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import net.minecraft.core.BlockPos;
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
        int f = p.foundation.length;
        if (p.state == Plot.State.DEMOLISHING) {
            // tear down from the top: walk the blueprint backwards, skipping clearing steps
            int j = bp.size() - 1 - i;
            if (j < 0) return null;
            return new Step(bp.worldPos(j, p.origin, p.rotation), Blocks.AIR.defaultBlockState());
        }
        if (i < f) return new Step(BlockPos.of(p.foundation[i]), foundationBlock(s));
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

    /** Places what was paid for while the area was unloaded. */
    public static void catchUp(ServerLevel level, Settlement s, Plot p, int max) {
        if (!livingrealms.settlement.AutoBuild.mayWork(s, p)) return;
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
