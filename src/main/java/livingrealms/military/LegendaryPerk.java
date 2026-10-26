package livingrealms.military;

import livingrealms.settlement.Settlement;
import net.minecraft.util.RandomSource;
import java.util.*;

/** Small, contextual veteran distinctions. Rolls happen once per native Recruits level, never per tick/login. */
public enum LegendaryPerk {
    STEADFAST("Steadfast", "8% less damage below 40% health."),
    IRON_WILL("Iron Will", "20% less damage on a hit while below 40% health. 90s cooldown; no resurrection."),
    KEEN_EDGE("Keen Edge", "+8% melee damage with your favourite weapon."),
    HAWKEYE("Hawkeye", "+8% projectile damage beyond 12 blocks."),
    SPEAR_SENTINEL("Spear Sentinel", "+8% spear damage while also carrying a shield."),
    BATTLE_RHYTHM("Battle Rhythm", "+6% damage for 8s after a hostile kill. 30s cooldown."),
    SECOND_WIND("Second Wind", "Recover 2 HP after a hostile kill. 30s cooldown."),
    WARDEN("Warden", "6% less damage within 10 blocks of your owner."),
    DRAGONHEART("Dragonheart", "15% less fire damage. Does not grant fire immunity."),
    SUREFOOTED("Surefooted", "+0.08 knockback resistance."),
    FAR_SIGHT("Far Sight", "+4 target detection range."),
    ARMS_SAVANT("Arms Savant", "+25% weapon practice XP; no direct damage increase.");
    public static final int FIRST_LEVEL = 8, SECOND_SLOT_LEVEL = 14, MAX_PERKS = 2;
    public static final double CHANCE = .12;
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
    public static List<LegendaryPerk> levelUp(Settlement.Soldier so, int nativeLevel, RandomSource random) {
        WeaponProficiency.initialise(so);
        int level = Math.max(0, Math.min(100, nativeLevel));
        List<LegendaryPerk> gained = new ArrayList<>();
        for (int next = Math.max(FIRST_LEVEL, so.legendaryLevelChecked + 1); next <= level; next++) {
            int slots = next >= SECOND_SLOT_LEVEL ? MAX_PERKS : 1;
            if (so.legendary.size() >= slots || random.nextDouble() >= CHANCE) continue;
            List<LegendaryPerk> pool = Arrays.stream(values()).filter(p -> p.compatible(so) && !so.legendary.contains(p)).toList();
            if (pool.isEmpty()) continue;
            LegendaryPerk perk = pool.get(random.nextInt(pool.size()));
            so.legendary.add(perk); gained.add(perk);
        }
        so.legendaryLevelChecked = Math.max(so.legendaryLevelChecked, level);
        return gained;
    }
}
