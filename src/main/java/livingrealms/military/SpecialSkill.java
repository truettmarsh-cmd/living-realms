package livingrealms.military;

import livingrealms.settlement.Settlement;
import java.util.EnumSet;
import java.util.Set;

/** Owner-equipped combat abilities that recruits trigger themselves. Stable IDs are saved on each recruit. */
public enum SpecialSkill {
    SHIELD_WALL("Shield Wall", "When struck by a projectile or below 60% health with a shield up: brace for 6s, taking 15% less damage.", 40, EnumSet.of(UnitType.SHIELDBEARER), null),
    TAUNT("Taunt", "Up to 4 hostiles within 8 blocks that are attacking your town's recruits or you turn on this recruit.", 35, EnumSet.of(UnitType.FOOTMAN, UnitType.SHIELDBEARER), null),
    RALLY_CRY("Rally Cry", "In a hard fight: up to 6 town recruits within 10 blocks heal 2 HP and deal +5% damage for 8s.", 90, null, null),
    SECOND_WIND("Second Wind", "Below 35% health: recover 25% of max health (at most 6 HP). Never prevents death.", 120, null, null),
    FIELD_DRESSING("Field Dressing", "Bandage a town recruit or you within 6 blocks below 50% health for 4 HP.", 45, null, null),
    STAGGER("Stagger", "Mace hit: Slowness II and Weakness I for 3s, plus extra knockback.", 20, null, EnumSet.of(WeaponProficiency.MACE)),
    DISARM("Disarm", "Sword or dagger hit knocks the foe's guard aside: Weakness I and Slowness I for 5s.", 25, null, EnumSet.of(WeaponProficiency.SWORD, WeaponProficiency.DAGGER)),
    RIPOSTE("Riposte", "When hit in melee, instantly strike back at the attacker.", 15, null, EnumSet.of(WeaponProficiency.SWORD, WeaponProficiency.DAGGER, WeaponProficiency.GREATSWORD)),
    LUNGE("Lunge", "Spear: dash at a target 3-7 blocks away, thrust and slow it for 2s.", 16, null, EnumSet.of(WeaponProficiency.SPEAR)),
    CLEAVE("Cleave", "Axe or greatsword hit also deals 40% of it to up to 3 other hostiles within 3 blocks.", 12, null, EnumSet.of(WeaponProficiency.AXE, WeaponProficiency.GREATSWORD)),
    CHARGE("Charge", "Mounted, target 6-20 blocks away: spur the horse; the next hit within 5s deals +10% and bowls the foe over.", 30, EnumSet.of(UnitType.HORSEMAN), null),
    VOLLEY("Volley", "Loose 3 extra light arrows at a target 6-28 blocks away.", 30, null, EnumSet.of(WeaponProficiency.BOW, WeaponProficiency.CROSSBOW)),
    AIMED_SHOT("Aimed Shot", "A shot beyond 8 blocks deals +10% and pins the target (Slowness II, 3s).", 20, null, EnumSet.of(WeaponProficiency.BOW, WeaponProficiency.CROSSBOW)),
    DRAGONFIRE_STRAFE("Dragonfire Strafe", "From dragonback: scorch the target and up to 3 hostiles near it for 4 damage and 5s of fire.", 45, EnumSet.of(UnitType.DRAGON_RIDER), null);

    public static final int[] SLOT_RANKS = {3, 8, 14};
    public final String label, description;
    /** Seconds. */
    public final int cooldown;
    final Set<UnitType> units;
    final Set<WeaponProficiency> weapons;

    SpecialSkill(String label, String description, int cooldown, Set<UnitType> units, Set<WeaponProficiency> weapons) {
        this.label = label; this.description = description; this.cooldown = cooldown; this.units = units; this.weapons = weapons;
    }
    public static SpecialSkill byId(String id) {
        try { return valueOf(id); } catch (IllegalArgumentException | NullPointerException e) { return null; }
    }
    public static int rank(Settlement.Soldier so) { return SoldierSkills.earnedPoints(so.skillXp); }
    public static int slots(Settlement.Soldier so) {
        int n = 0, rank = rank(so);
        for (int r : SLOT_RANKS) if (rank >= r) n++;
        return n;
    }
    public boolean available() { return this != DRAGONFIRE_STRAFE || DragonRiders.installed(); }
    public boolean eligible(Settlement.Soldier so) {
        WeaponProficiency.initialise(so);
        return available() && (units == null || units.contains(so.type)) && (weapons == null || weapons.contains(so.preferredWeapon));
    }
    public String requirement() {
        StringBuilder b = new StringBuilder();
        if (units != null) units.forEach(u -> b.append(b.length() > 0 ? " or " : "").append(u.plural()));
        if (weapons != null) {
            b.append(b.length() > 0 ? "; " : "").append("favourite weapon ");
            int i = 0;
            for (WeaponProficiency w : weapons) b.append(i++ > 0 ? " or " : "").append(w.label);
        }
        if (b.length() == 0) b.append("Any recruit");
        if (this == DRAGONFIRE_STRAFE && !DragonRiders.installed()) b.append(" (needs Ice and Fire)");
        return b + " · skill rank " + SLOT_RANKS[0];
    }
    /** Active means equipped within the current slot count and still suited to the recruit. */
    public boolean active(Settlement.Soldier so) {
        int i = so.specials.indexOf(this);
        return i >= 0 && i < slots(so) && eligible(so);
    }
}
