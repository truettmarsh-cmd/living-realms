package livingrealms.supply;

import livingrealms.building.Category;
import livingrealms.settlement.Settlement;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/** The supply sources Living Realms knows: a town's stockpile, a real container, the owner's inventory. */
public final class Sources {
    /** A recruit's own Living Realms town: the shared stockpile is its armoury. Smiths mend worn gear for its repair material. */
    public static final class Stockpile implements SupplySource {
        final Settlement town;

        public Stockpile(Settlement town) { this.town = town; }

        public String label() { return "the armoury"; }

        public List<ItemStack> take(Predicate<ItemStack> want, ToDoubleFunction<ItemStack> prefer, int max) {
            List<ItemStack> fits = new ArrayList<>();
            for (var en : town.stock.view().object2IntEntrySet()) {
                if (en.getIntValue() <= 0) continue;
                ItemStack st = new ItemStack(en.getKey());
                if (want.test(st)) fits.add(st);
            }
            fits.sort(Comparator.comparingDouble(prefer).reversed());
            List<ItemStack> out = new ArrayList<>();
            for (ItemStack st : fits) {
                if (max <= 0) break;
                int n = town.stock.takeUpTo(st.getItem(), max);
                max -= n;
                while (n > 0) {
                    int k = Math.min(n, st.getMaxStackSize());
                    out.add(new ItemStack(st.getItem(), k));
                    n -= k;
                }
            }
            if (!out.isEmpty()) town.dirty = true;
            return out;
        }

        public ItemStack putBack(ItemStack stack) {
            if (livingrealms.settlement.Stockpile.canStore(stack)) { town.stock.force(stack.getItem(), stack.getCount()); return ItemStack.EMPTY; }
            return stack;
        }

        /** With a standing smithy, each repair material (an iron ingot for iron gear) mends a quarter, as at an anvil. */
        public boolean repair(ItemStack worn) {
            if (!worn.isDamaged() || town.standing(Category.SMITHY) == 0) return false;
            int units = (int) Math.ceil(worn.getDamageValue() / Math.max(1.0, worn.getMaxDamage() / 4.0));
            for (var en : town.stock.view().object2IntEntrySet()) {
                Item mat = en.getKey();
                if (en.getIntValue() < units || !worn.getItem().isValidRepairItem(worn, new ItemStack(mat))) continue;
                town.stock.take(mat, units);
                worn.setDamageValue(0);
                town.dirty = true;
                return true;
            }
            return false;
        }

        /** How many of a repair material mending would cost, or 0 if the town can't. */
        public static int repairCost(ItemStack worn) {
            return worn.isDamaged() ? (int) Math.ceil(worn.getDamageValue() / Math.max(1.0, worn.getMaxDamage() / 4.0)) : 0;
        }
    }

    /** Slots of a real container: a chest, a cart, a recruit's own pack. */
    public static class Box implements SupplySource {
        final Container box;
        final String label;
        final int from, to;
        final IntPredicate skip;

        public Box(Container box, String label) { this(box, label, 0, box.getContainerSize(), i -> false); }

        public Box(Container box, String label, int from, int to, IntPredicate skip) {
            this.box = box; this.label = label; this.from = from; this.to = Math.min(to, box.getContainerSize()); this.skip = skip;
        }

        public String label() { return label; }

        public List<ItemStack> take(Predicate<ItemStack> want, ToDoubleFunction<ItemStack> prefer, int max) {
            List<Integer> slots = new ArrayList<>();
            for (int i = from; i < to; i++) if (!skip.test(i) && !box.getItem(i).isEmpty() && want.test(box.getItem(i))) slots.add(i);
            slots.sort(Comparator.comparingDouble((Integer i) -> prefer.applyAsDouble(box.getItem(i))).reversed());
            List<ItemStack> out = new ArrayList<>();
            for (int i : slots) {
                if (max <= 0) break;
                ItemStack st = box.removeItem(i, max);
                if (st.isEmpty()) continue;
                max -= st.getCount();
                out.add(st);
            }
            if (!out.isEmpty()) box.setChanged();
            return out;
        }

        public ItemStack putBack(ItemStack stack) {
            ItemStack left = stack.copy();
            for (int pass = 0; pass < 2 && !left.isEmpty(); pass++) {
                for (int i = from; i < to && !left.isEmpty(); i++) {
                    if (skip.test(i)) continue;
                    ItemStack in = box.getItem(i);
                    if (pass == 0 && !in.isEmpty() && ItemStack.isSameItemSameTags(in, left) && in.getCount() < in.getMaxStackSize()) {
                        int m = Math.min(left.getCount(), in.getMaxStackSize() - in.getCount());
                        in.grow(m); left.shrink(m);
                    } else if (pass == 1 && in.isEmpty() && box.canPlaceItem(i, left)) {
                        box.setItem(i, left); left = ItemStack.EMPTY;
                    }
                }
            }
            box.setChanged();
            return left;
        }

        public boolean keepWorn(ItemStack worn) { return putBack(worn).isEmpty(); }
    }

    /** The owner's main inventory and hotbar, never the item in their hand, armour or off hand. Swapped-out worn gear goes back to them. */
    public static final class Owner extends Box {
        public Owner(ServerPlayer p) { super(p.getInventory(), "your inventory", 0, 36, i -> i == p.getInventory().selected); }
    }

    private Sources() {}
}
