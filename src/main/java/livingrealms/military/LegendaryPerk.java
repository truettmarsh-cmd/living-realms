package livingrealms.military;

import livingrealms.settlement.Settlement;
import net.minecraft.util.RandomSource;
import java.util.*;

/** Small, contextual veteran distinctions. Rolls happen once per native Recruits level, never per tick/login. */
public enum LegendaryPerk {
    STEADFAST("Steadfast", "10% less damage below 40% health."),
    IRON_WILL("Iron Will", "20% less damage on a hit while below 40% health. 75s cooldown; no resurrection."),
    KEEN_EDGE("Keen Edge", "+8% melee damage with your favourite weapon."),
    HAWKEYE("Hawkeye", "+8% projectile damage beyond 12 blocks."),
    SPEAR_SENTINEL("Spear Sentinel", "+8% spear damage; with a shield also take 5% less damage."),
    BATTLE_RHYTHM("Battle Rhythm", "+8% damage for 10s after a hostile kill. 30s cooldown."),
    SECOND_WIND("Victor's Breath", "Recover 4 HP after a hostile kill. 30s cooldown."),
    WARDEN("Warden", "8% less damage within 10 blocks of your owner."),
    DRAGONHEART("Dragonheart", "20% less fire damage. Does not grant fire immunity."),
    SUREFOOTED("Surefooted", "+0.15 knockback resistance."),
    FAR_SIGHT("Far Sight", "+6 target detection range."),
    ARMS_SAVANT("Arms Savant", "+25% weapon practice XP; no direct damage increase."),
    VANGUARD("Vanguard", "With 2+ town recruits within 8 blocks: +6% damage and 6% less damage."),
    TACTICIAN("Tactician", "Special skill cooldowns are 25% shorter."),
    OPPORTUNIST("Opportunist", "+8% damage against foes attacking someone else."),
    GIANTSLAYER("Giantslayer", "+8% damage against foes with 40+ max health."),
    UNBROKEN("Unbroken", "Below 50% health while fighting: recover 1 HP every 4s.");
    public static final int FIRST_LEVEL = 8, SECOND_SLOT_LEVEL = 14, MAX_PERKS = 2, PITY_LIMIT = 6;
    /** Base chance per newly reached level, plus PITY_STEP per missed roll; the roll after PITY_LIMIT misses always succeeds. */
    public static final double CHANCE = .12, PITY_STEP = .03;
    public final String label, description;
    LegendaryPerk(String label, String description) { this.label = label; this.description = description; }
    public static LegendaryPerk byId(String id) {
        try { return valueOf(id); } catch (IllegalArgumentException e) { return null; }
    }
    public boolean compatible(Settlement.Soldier so) {
        return switch (this) {
            case KEEN_EDGE -> so.preferredWeapon != null && !so.preferredWeapon.ranged();
            case HAWKEYE -> so.preferredWeapon != null && so.preferredWeapon.ranged();
            case SPEAR_SENTINEL -> so.preferredWeapon == WeaponProficiency.SPEAR;
            default -> true;
        };
    }
    public static int slots(int nativeLevel) { return nativeLevel < FIRST_LEVEL ? 0 : nativeLevel < SECOND_SLOT_LEVEL ? 1 : MAX_PERKS; }
    public static double chance(int pity) { return pity >= PITY_LIMIT ? 1 : CHANCE + PITY_STEP * pity; }
    /** Saves from before the pity counter: credit the open-slot levels already missed. */
    public static int estimatePity(Settlement.Soldier so) {
        int checked = so.legendaryLevelChecked;
        if (so.legendary.size() >= slots(checked)) return 0;
        int from = so.legendary.isEmpty() ? FIRST_LEVEL : SECOND_SLOT_LEVEL;
        return Math.max(0, Math.min(PITY_LIMIT, checked - from + 1));
    }
    public static List<LegendaryPerk> levelUp(Settlement.Soldier so, int nativeLevel, RandomSource random) {
        WeaponProficiency.initialise(so);
        int level = Math.max(0, Math.min(100, nativeLevel));
        List<LegendaryPerk> gained = new ArrayList<>();
        for (int next = Math.max(FIRST_LEVEL, so.legendaryLevelChecked + 1); next <= level; next++) {
            if (so.legendary.size() >= slots(next)) continue;
            List<LegendaryPerk> pool = Arrays.stream(values()).filter(p -> p.compatible(so) && !so.legendary.contains(p)).toList();
            if (pool.isEmpty()) continue;
            if (so.legendaryPity < PITY_LIMIT && random.nextDouble() >= chance(so.legendaryPity)) { so.legendaryPity++; continue; }
            LegendaryPerk perk = pool.get(random.nextInt(pool.size()));
            so.legendary.add(perk); gained.add(perk); so.legendaryPity = 0;
        }
        so.legendaryLevelChecked = Math.max(so.legendaryLevelChecked, level);
        return gained;
    }
}
