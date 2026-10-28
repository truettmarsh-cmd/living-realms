package livingrealms.settlement.vx;

import livingrealms.building.Category;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** A town's street-and-plot layout from Village Expansion's city planner, kept in Living Realms terms. Holds no VE classes. */
public final class VxLayout {
    /** One building slot: centre, footprint, what VE zoned it for, and the street it fronts. */
    public static final class Slot {
        public int x, z, w, d;
        public String kind = "house";
        public long roadA, roadB;
        /** The street is one of VE's main (primary) streets. */
        public boolean primary;
        /** A Living Realms plot now stands on it. */
        public boolean used;

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putIntArray("b", new int[]{x, z, w, d});
            t.putString("k", kind);
            t.putLong("ra", roadA); t.putLong("rb", roadB);
            t.putBoolean("u", used);
            if (primary) t.putBoolean("p", true);
            return t;
        }

        static Slot load(CompoundTag t) {
            Slot s = new Slot();
            int[] b = t.getIntArray("b");
            if (b.length == 4) { s.x = b[0]; s.z = b[1]; s.w = b[2]; s.d = b[3]; }
            s.kind = t.getString("k");
            s.roadA = t.getLong("ra"); s.roadB = t.getLong("rb");
            s.used = t.getBoolean("u");
            s.primary = t.getBoolean("p");
            return s;
        }

        public BlockPos roadA() { return BlockPos.of(roadA); }
        public BlockPos roadB() { return BlockPos.of(roadB); }
        public boolean hasRoad() { return roadA != roadB; }
    }

    public final List<Slot> slots = new ArrayList<>();
    /** Streets already laid out as Living Realms road projects. */
    public final java.util.Set<Long> paved = new java.util.HashSet<>();
    public long seed;
    public int target;

    public boolean isEmpty() { return slots.isEmpty(); }

    public int free() { return (int) slots.stream().filter(s -> !s.used).count(); }

    /** Living Realms categories a VE zone accepts, best first. */
    public static List<Category> accepts(String kind) {
        return switch (kind) {
            case "house" -> List.of(Category.HOUSING, Category.DECOR, Category.TAVERN, Category.CHAPEL);
            case "barracks" -> List.of(Category.BARRACKS, Category.TOWER, Category.STABLE);
            case "lumber" -> List.of(Category.LUMBER);
            case "mine" -> List.of(Category.MINE);
            case "farm", "wool", "cow", "fishery" -> List.of(Category.FARM, Category.STABLE);
            case "forge", "tank_factory" -> List.of(Category.SMITHY, Category.WORKSHOP);
            case "kitchen" -> List.of(Category.TAVERN, Category.FARM);
            default -> List.of(Category.WORKSHOP, Category.MARKET, Category.LIBRARY, Category.STORAGE, Category.CONSTRUCTION,
                    Category.TAVERN, Category.CHAPEL, Category.SMITHY);
        };
    }

    @Nullable
    public CompoundTag save() {
        if (slots.isEmpty()) return null;
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        slots.forEach(s -> l.add(s.save()));
        t.put("slots", l);
        t.putLong("seed", seed);
        t.putInt("target", target);
        t.putLongArray("paved", paved.stream().mapToLong(Long::longValue).toArray());
        return t;
    }

    public void load(CompoundTag t) {
        slots.clear();
        for (Tag e : t.getList("slots", Tag.TAG_COMPOUND)) slots.add(Slot.load((CompoundTag) e));
        seed = t.getLong("seed");
        target = t.getInt("target");
        paved.clear();
        for (long l : t.getLongArray("paved")) paved.add(l);
    }
}
