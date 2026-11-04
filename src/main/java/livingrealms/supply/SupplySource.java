package livingrealms.supply;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Somewhere a recruit can restock from: its town's armoury, the storage Recruits assigned it, or its owner's pockets.
 * A Village Expansion stock source would be one more of these (see docs/resupply.md).
 */
public interface SupplySource {
    /** How the owner's warning names it: "the armoury", "its upkeep chest", "your inventory". */
    String label();

    /** Removes and returns up to max items matching want, the highest prefer score first. */
    List<ItemStack> take(Predicate<ItemStack> want, ToDoubleFunction<ItemStack> prefer, int max);

    /** Returns items this source gave that didn't fit in the pack; gives back what it couldn't hold. */
    ItemStack putBack(ItemStack stack);

    /** Keeps a worn piece the recruit swapped out. False: the recruit carries it in its pack instead. */
    default boolean keepWorn(ItemStack worn) { return false; }

    /** Mends a worn piece in place for a price, if this source can. */
    default boolean repair(ItemStack worn) { return false; }
}
