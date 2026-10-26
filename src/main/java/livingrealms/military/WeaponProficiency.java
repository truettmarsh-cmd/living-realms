package livingrealms.military;

import livingrealms.settlement.Settlement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import java.util.Locale;

/** Saved weapon families, not individual registry IDs: switching spear materials preserves practice. */
public enum WeaponProficiency {
    SWORD("Swords"), GREATSWORD("Greatswords"), DAGGER("Daggers"), SPEAR("Spears & polearms"),
    AXE("Axes"), MACE("Maces & hammers"), BOW("Bows"), CROSSBOW("Crossbows");
    public static final int MAX_XP = 1500;
    public static final int[] THRESHOLDS = {0, 100, 300, 600, 1000, MAX_XP};
    public final String label;
    WeaponProficiency(String label) { this.label = label; }
    public static int rank(int xp) {
        int result = 0;
        for (int i = 1; i < THRESHOLDS.length; i++) if (xp >= THRESHOLDS[i]) result = i;
        return result;
    }
    public boolean ranged() { return this == BOW || this == CROSSBOW; }
    public static WeaponProficiency byId(String id) {
        try { return valueOf(id); } catch (IllegalArgumentException e) { return null; }
    }
    public static WeaponProficiency classify(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Item item = stack.getItem();
        if (item instanceof CrossbowItem) return CROSSBOW;
        if (item instanceof BowItem) return BOW;
        var piece = livingrealms.smithing.EquipmentDiscovery.piece(item);
        if (piece == null || !piece.melee()) return null;
        String name = BuiltInRegistries.ITEM.getKey(item).getPath().toLowerCase(Locale.ROOT);
        if (name.matches(".*(spear|pike|lance|glaive|halberd|ranseur|ahlspiess|billhook|poleaxe|trident).*")) return SPEAR;
        if (name.matches(".*(greatsword|zweihander|claymore|bastardsword).*")) return GREATSWORD;
        if (name.matches(".*(dagger|stylet|stiletto).*")) return DAGGER;
        if (item instanceof AxeItem || name.contains("axe")) return AXE;
        if (name.matches(".*(mace|hammer|club|morgenstern|morningstar|flail).*")) return MACE;
        return SWORD;
    }
    public static void initialise(Settlement.Soldier soldier) {
        if (soldier.preferredWeapon != null) return;
        soldier.preferredWeapon = switch (soldier.type) {
            case BOWMAN, DRAGON_RIDER -> BOW;
            case CROSSBOWMAN -> CROSSBOW;
            default -> values()[Math.floorMod(soldier.entity.hashCode(), 6)];
        };
    }
    public static double bonus(Settlement.Soldier soldier, WeaponProficiency weapon) {
        if (weapon == null) return 0;
        return (weapon == soldier.preferredWeapon ? .05 : 0) + .02 * rank(soldier.weaponXp.getOrDefault(weapon, 0));
    }
    public static void practice(Settlement.Soldier soldier, WeaponProficiency weapon, int xp) {
        if (weapon == null || xp <= 0) return;
        initialise(soldier);
        double multiplier = (weapon == soldier.preferredWeapon ? 1.25 : 1)
                * (soldier.legendary.contains(LegendaryPerk.ARMS_SAVANT) ? 1.25 : 1);
        int earned = Math.max(1, (int)Math.round(xp * multiplier));
        soldier.weaponXp.merge(weapon, earned, (a, b) -> Math.min(MAX_XP, a + b));
        soldier.weaponXp.computeIfPresent(weapon, (k, v) -> Math.min(MAX_XP, v));
    }
}
