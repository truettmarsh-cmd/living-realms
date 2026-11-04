package livingrealms.military;

import livingrealms.settlement.Settlement;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ShieldItem;
import java.util.EnumSet;
import java.util.Set;

/**
 * A recruit's specialist path, chosen once from skill rank 4. Stable IDs are saved on each recruit. Foot paths may
 * change the recruit's Villager Recruits body: a shieldman becomes a shieldbearer, a berserker a plain footman.
 */
public enum SoldierPath {
    SWORDSMAN("Swordsman", "A duellist with sword or dagger: fast, precise strikes and parries.",
            EnumSet.of(UnitType.FOOTMAN, UnitType.SHIELDBEARER), EnumSet.of(WeaponProficiency.SWORD, WeaponProficiency.DAGGER), null),
    SHIELDMAN("Shieldman", "Holds the line behind a shield and keeps the recruits around it alive.",
            EnumSet.of(UnitType.FOOTMAN, UnitType.SHIELDBEARER), null, UnitType.SHIELDBEARER),
    BERSERKER("Berserker", "Axe, greatsword or mace: trades safety for raw damage and grows stronger as it bleeds.",
            EnumSet.of(UnitType.FOOTMAN, UnitType.SHIELDBEARER), EnumSet.of(WeaponProficiency.AXE, WeaponProficiency.GREATSWORD, WeaponProficiency.MACE), UnitType.FOOTMAN),
    SPEARMAN("Spearman", "Spear or polearm: stops charges, keeps foes at a distance and brings down big game.",
            EnumSet.of(UnitType.FOOTMAN, UnitType.SHIELDBEARER), EnumSet.of(WeaponProficiency.SPEAR), null),
    MARKSMAN("Marksman", "Bow or crossbow: long, armour-piercing shots and marks for the rest of the army.",
            EnumSet.of(UnitType.BOWMAN, UnitType.CROSSBOWMAN, UnitType.DRAGON_RIDER), EnumSet.of(WeaponProficiency.BOW, WeaponProficiency.CROSSBOW), null),
    KNIGHT("Knight", "Mounted shock cavalry: charges, an armoured horse and a banner the army rallies to.",
            EnumSet.of(UnitType.HORSEMAN), null, null),
    HEALER("Healer", "Patches up the recruits around it mid-fight, cures poison and keeps the army on its feet.",
            EnumSet.of(UnitType.FOOTMAN, UnitType.SHIELDBEARER, UnitType.BOWMAN, UnitType.CROSSBOWMAN), null, null);

    public static final int CHOOSE_RANK = 4;
    public final String label, description;
    /** Unit types that may take the path, and the weapon families its skills work with (null: any). */
    final Set<UnitType> units;
    final Set<WeaponProficiency> weapons;
    /** The unit this path turns the recruit into, or null to keep its body. */
    public final UnitType becomes;

    SoldierPath(String label, String description, Set<UnitType> units, Set<WeaponProficiency> weapons, UnitType becomes) {
        this.label = label; this.description = description; this.units = units; this.weapons = weapons; this.becomes = becomes;
    }

    public static SoldierPath byId(String id) {
        try { return id == null || id.isEmpty() ? null : valueOf(id); } catch (IllegalArgumentException e) { return null; }
    }

    public boolean fits(UnitType unit) { return units.contains(unit); }

    /** The path's skills only work with its gear: its weapon families, a shield, or a horse. Healers need nothing. */
    public boolean equipped(LivingEntity e, WeaponProficiency weapon) {
        return switch (this) {
            case SHIELDMAN -> e.getOffhandItem().getItem() instanceof ShieldItem;
            case KNIGHT -> e.isPassenger();
            case HEALER -> true;
            default -> weapon != null && weapons.contains(weapon);
        };
    }

    public String gear() {
        return switch (this) {
            case SHIELDMAN -> "a shield in the off hand";
            case KNIGHT -> "a horse to ride";
            case HEALER -> "any weapon";
            default -> {
                StringBuilder b = new StringBuilder();
                int i = 0;
                for (WeaponProficiency w : weapons) b.append(i++ == 0 ? "" : i == weapons.size() ? " or " : ", ").append(w.label.toLowerCase());
                yield b.toString();
            }
        };
    }

    public String fitsWho() {
        return switch (this) {
            case MARKSMAN -> "Bowmen, crossbowmen and dragon riders";
            case KNIGHT -> "Horsemen";
            case HEALER -> "Footmen, shieldbearers, bowmen and crossbowmen";
            default -> "Footmen and shieldbearers";
        };
    }

    /** Why this soldier can't take the path now, or null. */
    public String blocker(Settlement.Soldier so) {
        if (!fits(so.type)) return fitsWho() + " only";
        if (SoldierSkills.earnedPoints(so.skillXp) < CHOOSE_RANK) return "Needs skill rank " + CHOOSE_RANK;
        return null;
    }
}
