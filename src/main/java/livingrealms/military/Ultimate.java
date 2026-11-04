package livingrealms.military;

import javax.annotation.Nullable;

/** Each path's ultimate ability, used by a Hero on its own in battle (see Heroes). Stable IDs. */
public enum Ultimate {
    BLADE_STORM(SoldierPath.SWORDSMAN, "Blade Storm", "Spins for 4s: every half second, 3 damage to every hostile within 3.5 blocks"),
    BULWARK(SoldierPath.SHIELDMAN, "Bulwark", "Raises a 5-block dome for 8s: your recruits inside take 50% less damage, and enemy projectiles entering it are stopped"),
    EARTHSHAKER(SoldierPath.BERSERKER, "Earthshaker", "Slams the ground: 6 damage to hostiles within 6 blocks, hurled back and slowed for 5s"),
    IMPALE(SoldierPath.SPEARMAN, "Impale", "Charges up to 12 blocks through the enemy line: 8 damage to each foe in the way, and the first large one is pinned for 3s"),
    RAIN_OF_ARROWS(SoldierPath.MARKSMAN, "Rain of Arrows", "Calls a volley over a 4-block area around its target for 5s: 3 arrows every quarter second"),
    ROYAL_CHARGE(SoldierPath.KNIGHT, "Royal Charge", "The hero and your mounted recruits within 12 blocks charge: their horses gain Speed III and they deal +20% damage for 6s"),
    MIRACLE(SoldierPath.HEALER, "Miracle", "Once a battle: fully heals and cleanses you and your recruits within 10 blocks");

    /** Two minutes between uses; Miracle also only once a battle. */
    public static final int COOLDOWN = 2400;
    public final SoldierPath path;
    public final String label, description;

    Ultimate(SoldierPath path, String label, String description) { this.path = path; this.label = label; this.description = description; }

    @Nullable
    public static Ultimate of(@Nullable SoldierPath path) {
        if (path == null) return null;
        for (Ultimate u : values()) if (u.path == path) return u;
        return null;
    }

    @Nullable
    public static Ultimate byId(String id) {
        try { return id == null || id.isEmpty() ? null : valueOf(id); } catch (IllegalArgumentException e) { return null; }
    }
}
