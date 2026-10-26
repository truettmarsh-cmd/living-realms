package livingrealms.settlement;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The town's shared store. Builders, smiths and the quartermaster draw from it from anywhere in the settlement;
 * no chest has to be near the work. Items keep no NBT here; enchanted or named items stay in real containers.
 */
public final class Stockpile {
    private final Object2IntOpenHashMap<Item> items = new Object2IntOpenHashMap<>();
    private int total;
    public int capacity = 2000;

    public int count(Item item) { return items.getInt(item); }

    public int total() { return total; }

    public int free() { return Math.max(0, capacity - total); }

    public boolean has(Item item, int n) { return items.getInt(item) >= n; }

    /** Adds up to n, limited by free space. Returns how many fit. */
    public int add(Item item, int n) {
        if (item == Items.AIR || n <= 0) return 0;
        int fit = Math.min(n, free());
        if (fit > 0) {
            items.addTo(item, fit);
            total += fit;
        }
        return fit;
    }

    /** Adds ignoring capacity (refunds). */
    public void force(Item item, int n) {
        if (item == Items.AIR || n <= 0) return;
        items.addTo(item, n);
        total += n;
    }

    public boolean take(Item item, int n) {
        int have = items.getInt(item);
        if (have < n) return false;
        if (have == n) items.removeInt(item); else items.put(item, have - n);
        total -= n;
        return true;
    }

    /** Takes up to n and returns how many were taken. */
    public int takeUpTo(Item item, int n) {
        int t = Math.min(n, items.getInt(item));
        if (t > 0) take(item, t);
        return t;
    }

    public int count(TagKey<Item> tag) {
        int n = 0;
        for (Object2IntMap.Entry<Item> e : items.object2IntEntrySet())
            if (e.getKey().builtInRegistryHolder().is(tag)) n += e.getIntValue();
        return n;
    }

    public boolean take(TagKey<Item> tag, int n) {
        if (count(tag) < n) return false;
        for (Item it : new ArrayList<>(items.keySet())) {
            if (n <= 0) break;
            if (it.builtInRegistryHolder().is(tag)) n -= takeUpTo(it, n);
        }
        return true;
    }

    /** Ingredient id is an item id or a {@code #tag}. */
    public int countIngredient(String ing) {
        if (ing.startsWith("#")) return count(TagKey.create(net.minecraft.core.registries.Registries.ITEM, new ResourceLocation(ing.substring(1))));
        Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(ing));
        return it == Items.AIR ? 0 : count(it);
    }

    public boolean takeIngredient(String ing, int n) {
        if (ing.startsWith("#")) return take(TagKey.create(net.minecraft.core.registries.Registries.ITEM, new ResourceLocation(ing.substring(1))), n);
        Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(ing));
        return it != Items.AIR && take(it, n);
    }

    public Object2IntOpenHashMap<Item> view() { return items; }

    public List<ItemStack> asStacks() {
        List<ItemStack> l = new ArrayList<>();
        for (Object2IntMap.Entry<Item> e : items.object2IntEntrySet()) l.add(new ItemStack(e.getKey(), e.getIntValue()));
        return l;
    }

    public ListTag save() {
        ListTag l = new ListTag();
        for (Object2IntMap.Entry<Item> e : items.object2IntEntrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
            t.putInt("n", e.getIntValue());
            l.add(t);
        }
        return l;
    }

    public void load(ListTag l) {
        items.clear();
        total = 0;
        for (Tag tag : l) {
            CompoundTag t = (CompoundTag) tag;
            Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(t.getString("id")));
            if (it != Items.AIR) force(it, t.getInt("n"));
        }
    }
}
