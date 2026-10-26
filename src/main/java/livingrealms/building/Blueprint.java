package livingrealms.building;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.*;

/**
 * A building's blocks in build order: clearing (air, top-down), then solid blocks, then the rest,
 * then blocks that hang on others (torches, doors, beds, plants), each bottom-up.
 */
public final class Blueprint {
    public final String key;
    public final int sx, sy, sz;
    private final int[] packed;
    private final BlockState[] states;
    public final int beds, workstations, solidCount;
    private Map<Item, Integer> bill;

    public record Entry(int x, int y, int z, BlockState state) {}

    public Blueprint(String key, int sx, int sy, int sz, List<Entry> entries) {
        this.key = key;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.<Entry>comparingInt(e -> phase(e.state))
                .thenComparingInt(e -> phase(e.state) == 0 ? -e.y : e.y)
                .thenComparingInt(Entry::x).thenComparingInt(Entry::z));
        packed = new int[sorted.size()];
        states = new BlockState[sorted.size()];
        int bedCount = 0, work = 0, solid = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Entry e = sorted.get(i);
            packed[i] = e.x | e.y << 10 | e.z << 20;
            states[i] = e.state;
            if (!e.state.isAir()) solid++;
            if (e.state.getBlock() instanceof BedBlock && e.state.getValue(BedBlock.PART) == BedPart.FOOT) bedCount++;
            else if (!(e.state.getBlock() instanceof BedBlock) && !(e.state.getBlock() instanceof BellBlock)
                    && PoiTypes.forState(e.state).isPresent()) work++;
        }
        beds = bedCount;
        workstations = work;
        solidCount = solid;
    }

    public int size() { return states.length; }
    public BlockState state(int i) { return states[i]; }
    public int lx(int i) { return packed[i] & 1023; }
    public int ly(int i) { return packed[i] >> 10 & 1023; }
    public int lz(int i) { return packed[i] >> 20 & 1023; }

    /** Rotated footprint: x and z swap for quarter turns. */
    public int width(Rotation r) { return quarter(r) ? sz : sx; }
    public int depth(Rotation r) { return quarter(r) ? sx : sz; }

    static boolean quarter(Rotation r) { return r == Rotation.CLOCKWISE_90 || r == Rotation.COUNTERCLOCKWISE_90; }

    /** World position of entry i for a building whose rotated bounding box starts at origin. */
    public BlockPos worldPos(int i, BlockPos origin, Rotation r) {
        return origin.offset(rotate(lx(i), ly(i), lz(i), r));
    }

    public BlockPos rotate(int x, int y, int z, Rotation r) {
        return switch (r) {
            case NONE -> new BlockPos(x, y, z);
            case CLOCKWISE_90 -> new BlockPos(sz - 1 - z, y, x);
            case CLOCKWISE_180 -> new BlockPos(sx - 1 - x, y, sz - 1 - z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, sx - 1 - x);
        };
    }

    @SuppressWarnings("deprecation")
    public BlockState worldState(int i, Rotation r) {
        return states[i].rotate(r);
    }

    /** Materials needed to build the whole thing. */
    public Map<Item, Integer> bill() {
        if (bill == null) {
            Map<Item, Integer> m = new LinkedHashMap<>();
            for (BlockState s : states) {
                Item it = costItem(s);
                if (it != null) m.merge(it, 1, Integer::sum);
            }
            List<Map.Entry<Item, Integer>> l = new ArrayList<>(m.entrySet());
            l.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
            Map<Item, Integer> ordered = new LinkedHashMap<>();
            for (var e : l) ordered.put(e.getKey(), e.getValue());
            bill = Collections.unmodifiableMap(ordered);
        }
        return bill;
    }

    /** The item a builder spends on this block, or null if it is free (air, water, upper door halves...). */
    public static Item costItem(BlockState s) {
        if (s.isAir()) return null;
        Block b = s.getBlock();
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return null;
        if (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return null;
        if (b instanceof LiquidBlock || b instanceof SpawnerBlock || b instanceof FireBlock) return null;
        if (b == Blocks.GRASS_BLOCK || b == Blocks.DIRT_PATH || b == Blocks.FARMLAND || b == Blocks.PODZOL
                || b == Blocks.MYCELIUM || b == Blocks.ROOTED_DIRT) return Items.DIRT;
        if (b instanceof InfestedBlock inf) return inf.getHostBlock().asItem();
        if (b instanceof FlowerPotBlock pot && pot.getContent() != Blocks.AIR) return Items.FLOWER_POT;
        Item i = b.asItem();
        return i == Items.AIR ? null : i;
    }

    static int phase(BlockState s) {
        if (s.isAir()) return 0;
        Block b = s.getBlock();
        if (b instanceof DoorBlock || b instanceof BedBlock || b instanceof TorchBlock || b instanceof LanternBlock
                || b instanceof BellBlock || b instanceof BannerBlock || b instanceof WallBannerBlock || b instanceof SignBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof CarpetBlock || b instanceof FlowerPotBlock
                || b instanceof BushBlock || b instanceof LadderBlock || b instanceof TrapDoorBlock || b instanceof BaseRailBlock
                || b instanceof VineBlock || b instanceof LiquidBlock || b instanceof PressurePlateBlock || b instanceof SnowLayerBlock)
            return 3;
        try {
            if (s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return 3;
        } catch (Exception ignored) {
            return 3;
        }
        return s.canOcclude() ? 1 : 2;
    }

    // ---- loading ----

    private static final HolderLookup<Block> BLOCKS = BuiltInRegistries.BLOCK.asLookup();

    /** Reads a saved structure template (.nbt). Jigsaws become their final state, structure voids keep the terrain. */
    public static Blueprint fromTemplate(String key, CompoundTag tag) {
        ListTag size = tag.getList("size", Tag.TAG_INT);
        int sx = size.getInt(0), sy = size.getInt(1), sz = size.getInt(2);
        ListTag paletteTag = tag.contains("palette", Tag.TAG_LIST) ? tag.getList("palette", Tag.TAG_COMPOUND)
                : tag.getList("palettes", Tag.TAG_LIST).getList(0);
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) palette[i] = NbtUtils.readBlockState(BLOCKS, paletteTag.getCompound(i));
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        List<Entry> entries = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag bt = blocks.getCompound(i);
            int idx = bt.getInt("state");
            if (idx < 0 || idx >= palette.length) continue;
            BlockState s = palette[idx];
            if (s.is(Blocks.STRUCTURE_VOID)) continue;
            if (s.is(Blocks.STRUCTURE_BLOCK)) s = Blocks.AIR.defaultBlockState();
            if (s.is(Blocks.JIGSAW)) {
                s = Blocks.AIR.defaultBlockState();
                String fin = bt.getCompound("nbt").getString("final_state");
                if (!fin.isEmpty()) {
                    try {
                        s = BlockStateParser.parseForBlock(BLOCKS, fin, false).blockState();
                    } catch (Exception ignored) {
                    }
                }
                if (s.is(Blocks.STRUCTURE_VOID)) continue;
            }
            ListTag p = bt.getList("pos", Tag.TAG_INT);
            entries.add(new Entry(p.getInt(0), p.getInt(1), p.getInt(2), s));
        }
        return new Blueprint(key, sx, sy, sz, entries);
    }

    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putString("k", key);
        t.putIntArray("size", new int[]{sx, sy, sz});
        Map<BlockState, Integer> pal = new LinkedHashMap<>();
        int[] idx = new int[states.length];
        for (int i = 0; i < states.length; i++) idx[i] = pal.computeIfAbsent(states[i], s -> pal.size());
        ListTag pl = new ListTag();
        for (BlockState s : pal.keySet()) pl.add(NbtUtils.writeBlockState(s));
        t.put("pal", pl);
        t.putIntArray("p", packed);
        t.putIntArray("s", idx);
        return t;
    }

    public static Blueprint fromTag(CompoundTag t) {
        int[] size = t.getIntArray("size");
        ListTag pl = t.getList("pal", Tag.TAG_COMPOUND);
        BlockState[] pal = new BlockState[pl.size()];
        for (int i = 0; i < pal.length; i++) pal[i] = NbtUtils.readBlockState(BLOCKS, pl.getCompound(i));
        int[] p = t.getIntArray("p"), s = t.getIntArray("s");
        List<Entry> entries = new ArrayList<>(p.length);
        for (int i = 0; i < p.length; i++)
            entries.add(new Entry(p[i] & 1023, p[i] >> 10 & 1023, p[i] >> 20 & 1023, pal[s[i]]));
        return new Blueprint(t.getString("k"), size[0], size[1], size[2], entries);
    }
}
