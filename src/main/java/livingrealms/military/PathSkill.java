package livingrealms.military;

import java.util.Arrays;
import java.util.List;

/**
 * Each path's four skills, learned in order, then one legendary mastery out of two. Skills cost one skill point, a
 * mastery two, from the same pool as the general tree. Stable IDs are saved on each recruit.
 */
public enum PathSkill {
    BLADE_DRILL(SoldierPath.SWORDSMAN, 1, "Blade Drill", "+8% damage with swords and daggers"),
    PARRY(SoldierPath.SWORDSMAN, 2, "Parry", "Sword or dagger in hand: take 15% less melee damage"),
    DUELIST(SoldierPath.SWORDSMAN, 3, "Duelist", "+12% damage against the foe that last struck this recruit"),
    FLURRY(SoldierPath.SWORDSMAN, 4, "Flurry", "Every third sword hit strikes twice: +50% damage"),
    BLADEMASTER(SoldierPath.SWORDSMAN, 5, "Blademaster", "+30% sword and dagger damage, and every melee hit taken is answered at once: the attacker takes 30% of it back"),
    WHIRLWIND(SoldierPath.SWORDSMAN, 5, "Whirlwind", "+15% damage, and every hit also slashes up to 4 other hostiles within 3 blocks for 60% of it"),

    SHIELD_DRILL(SoldierPath.SHIELDMAN, 1, "Shield Drill", "Shield in hand: +3 armour"),
    BRACE(SoldierPath.SHIELDMAN, 2, "Brace", "Shield in hand: take 15% less melee damage"),
    GUARDIAN(SoldierPath.SHIELDMAN, 3, "Guardian", "Town recruits within 4 blocks take 10% less damage"),
    IMMOVABLE(SoldierPath.SHIELDMAN, 4, "Immovable", "+60% knockback resistance; shield in hand: arrows deal 30% less"),
    COLOSSUS(SoldierPath.SHIELDMAN, 5, "Colossus", "Grows into a giant: 1.6x size, +20 max health, +4 armour, cannot be knocked back, +15% damage"),
    AEGIS(SoldierPath.SHIELDMAN, 5, "Aegis", "This recruit and every town recruit within 6 blocks take 20% less damage"),

    FURY(SoldierPath.BERSERKER, 1, "Fury", "Up to +18% damage as health drops"),
    BLOODLUST(SoldierPath.BERSERKER, 2, "Bloodlust", "Heal 3 HP on every kill"),
    RECKLESS(SoldierPath.BERSERKER, 3, "Reckless", "+12% damage, but take 5% more"),
    RAMPAGE(SoldierPath.BERSERKER, 4, "Rampage", "A kill gives +20% speed and +10% damage for 6s"),
    TITAN(SoldierPath.BERSERKER, 5, "Titan", "Grows into a giant: 1.8x size, +30 max health, +30% damage, cannot be knocked back, 10% slower"),
    UNDYING_RAGE(SoldierPath.BERSERKER, 5, "Undying Rage", "Once every 3 minutes a killing blow leaves it on 1 HP instead, and it deals +40% damage for 10s"),

    PIKE_DRILL(SoldierPath.SPEARMAN, 1, "Pike Drill", "+8% damage with spears and polearms"),
    SET_FOR_CHARGE(SoldierPath.SPEARMAN, 2, "Set for Charge", "+20% damage against riders and large creatures"),
    KEEP_BACK(SoldierPath.SPEARMAN, 3, "Keep Back", "Spear hits shove foes back hard"),
    PHALANX(SoldierPath.SPEARMAN, 4, "Phalanx", "With 2+ town recruits within 4 blocks: +10% damage and 10% less damage taken"),
    DRAGONBANE(SoldierPath.SPEARMAN, 5, "Dragonbane", "+50% damage against foes with 60+ max health: dragons, bosses, giants"),
    HEDGEHOG(SoldierPath.SPEARMAN, 5, "Hedgehog", "+15% damage; melee attackers take 4 damage every time they strike it"),

    STEADY_AIM(SoldierPath.MARKSMAN, 1, "Steady Aim", "+8% damage with bows and crossbows"),
    EAGLE_EYE(SoldierPath.MARKSMAN, 2, "Eagle Eye", "Spots targets 12 blocks further away"),
    PIERCING_SHOT(SoldierPath.MARKSMAN, 3, "Piercing Shot", "+15% damage against armoured targets"),
    HEADHUNTER(SoldierPath.MARKSMAN, 4, "Headhunter", "+15% damage against targets beyond 16 blocks"),
    DEADSHOT(SoldierPath.MARKSMAN, 5, "Deadshot", "Every fourth shot hits for triple damage"),
    HUNTERS_MARK(SoldierPath.MARKSMAN, 5, "Hunter's Mark", "Its hits mark the target for 8s: marked foes take +25% damage from all your town recruits"),

    MOUNTED_DRILL(SoldierPath.KNIGHT, 1, "Mounted Drill", "+10% damage while riding"),
    BARDING(SoldierPath.KNIGHT, 2, "Barding", "Its horse gains +10 max health and +4 armour"),
    LANCE_CHARGE(SoldierPath.KNIGHT, 3, "Lance Charge", "+20% damage on hits struck at a gallop"),
    BANNER(SoldierPath.KNIGHT, 4, "Banner", "Town recruits within 8 blocks deal +5% damage"),
    WARLORD(SoldierPath.KNIGHT, 5, "Warlord", "Town recruits within 12 blocks deal +15% damage and take 10% less"),
    JUGGERNAUT(SoldierPath.KNIGHT, 5, "Juggernaut", "Rider and horse grow into giants: 1.4x size, +20 max health each, +20% damage, cannot be knocked back");

    public static final int[] TIER_RANKS = {0, 5, 7, 10, 13, 16};
    public static final int MASTERY_COST = 2;
    public final SoldierPath path;
    /** 1-4 are the path's skills in order; 5 is a legendary mastery. */
    public final int tier;
    public final String label, description;

    PathSkill(SoldierPath path, int tier, String label, String description) {
        this.path = path; this.tier = tier; this.label = label; this.description = description;
    }

    public static PathSkill byId(String id) {
        try { return valueOf(id); } catch (IllegalArgumentException | NullPointerException e) { return null; }
    }

    public boolean mastery() { return tier == 5; }
    public int cost() { return mastery() ? MASTERY_COST : 1; }
    public int requiredRank() { return TIER_RANKS[tier]; }
    public boolean giant() { return this == COLOSSUS || this == TITAN || this == JUGGERNAUT; }

    public static List<PathSkill> of(SoldierPath path) { return Arrays.stream(values()).filter(s -> s.path == path).toList(); }

    /** The skill that must come first: the previous tier, and for a mastery the fourth skill. */
    public PathSkill prerequisite() {
        for (PathSkill s : values()) if (s.path == path && s.tier == Math.min(4, tier - 1)) return s;
        return null;
    }
}
