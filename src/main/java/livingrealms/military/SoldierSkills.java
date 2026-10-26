package livingrealms.military;

import java.util.Set;

/** Stable IDs are saved with individual recruits; each node costs one earned point. */
public enum SoldierSkills {
    ENDURANCE("Endurance", "Defence", "+4 base maximum health", null, 1),
    IRONCLAD("Ironclad", "Defence", "+2 armour", ENDURANCE, 2),
    RESOLVE("Resolve", "Defence", "+10% knockback resistance", IRONCLAD, 4),
    BULWARK("Bulwark", "Defence", "+1 armour toughness", RESOLVE, 7),
    SHIELD_GUARD("Shield guard", "Defence", "Shield equipped: take 10% less projectile damage", BULWARK, 11),
    LAST_STAND("Last stand", "Defence", "Below 30% health: take 10% less damage", SHIELD_GUARD, 16),
    WEAPON_DRILLS("Weapon drills", "Combat", "+2 base melee damage", null, 1),
    DEADEYE("Deadeye", "Combat", "+8% projectile damage", WEAPON_DRILLS, 2),
    ARMOUR_BREAKER("Armour breaker", "Combat", "+5% damage against armoured targets", DEADEYE, 4),
    FINISHER("Finisher", "Combat", "+5% damage to targets below 30% health", ARMOUR_BREAKER, 7),
    BATTLE_FOCUS("Battle focus", "Combat", "+3% damage while above 75% health", FINISHER, 11),
    RELENTLESS("Relentless", "Combat", "+3% melee damage for 5s after taking damage", BATTLE_FOCUS, 16),
    MARCHING("Marching", "Support", "+10% movement speed", null, 1),
    FIELD_MEDIC("Field medic", "Support", "Heal 1 HP / 5s when idle", MARCHING, 2),
    SCOUT("Scout", "Support", "+8 blocks of target detection range", FIELD_MEDIC, 4),
    FIRE_WARD("Fire ward", "Support", "Take 15% less fire damage", SCOUT, 7),
    BLAST_WARD("Blast ward", "Support", "Take 15% less explosion damage", FIRE_WARD, 11),
    RECOVERY("Recovery", "Support", "Field medic heals 1 extra HP / 5s", BLAST_WARD, 16);

    public static final int XP_PER_KILL = 25, XP_PER_POINT = 50, MAX_POINTS = 18;
    public final String label, branch, description;
    public final SoldierSkills prerequisite;
    public final int requiredRank;

    SoldierSkills(String label, String branch, String description, SoldierSkills prerequisite, int requiredRank) {
        this.label = label; this.branch = branch; this.description = description; this.prerequisite = prerequisite;
        this.requiredRank = requiredRank;
    }

    public static int earnedPoints(int xp) { return Math.min(MAX_POINTS, Math.max(0, xp) / XP_PER_POINT); }
    public static int availablePoints(int xp, Set<SoldierSkills> learned) { return Math.max(0, earnedPoints(xp) - learned.size()); }
    public boolean unlocked(Set<SoldierSkills> learned) { return prerequisite == null || learned.contains(prerequisite); }
    public static SoldierSkills byId(String id) {
        try { return valueOf(id); } catch (IllegalArgumentException ex) { return null; }
    }
}
