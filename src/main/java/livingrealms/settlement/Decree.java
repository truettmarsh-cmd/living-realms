package livingrealms.settlement;

import livingrealms.Config;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

/**
 * The royal decrees a kingdom can issue from the Kingdom page (docs/decrees.md). Each needs a kingdom rank (its highest
 * city) and the Royal Decrees research in one of its cities; the military ones also need Command. Durations and
 * cooldowns given in days follow the settlement day ({@code simulation.dayLength}).
 */
public enum Decree {
    WAR_HORN("War Horn", Tier.CITY, Items.GOAT_HORN, true, 0, 1, 24,
            "Every recruit you own deals +15% damage for 3 minutes, gains 20 morale and does not drop below 50 morale."),
    ROYAL_FEAST("Royal Feast", Tier.CITY, Items.CAKE, false, 2, 4, 32,
            "+10 mood in every town and +4 loyalty a day in conquered cities for 2 days. Crowds: sickness 1.5x likelier for half a day."),
    TAX_HOLIDAY("Tax Holiday", Tier.CITY, Items.GOLD_NUGGET, false, 2, 3, 0,
            "No head tax in any town for 2 days (merchants still pay): +8 mood and +3 loyalty a day."),
    ROYAL_LEVY("Royal Levy", Tier.CITY, Items.EMERALD, false, 2, 3, 0,
            "Every town pays a day of high taxes at once (1 emerald per person) into its stockpile. -10 mood and -3 loyalty a day for 2 days."),
    AMNESTY("Amnesty", Tier.CITY, Items.WHITE_BANNER, false, 1, 3, 96,
            "This conquered city's loyalty rises to at least 55, ending any rebellion. For a day NPC kingdoms weigh your peace offers +15."),
    CALL_THE_BANNERS("Call the Banners", Tier.CAPITAL, Items.RED_BANNER, true, 1, 5, 48,
            "Every town sends militia levies (1 to 6 by rank, at most 20) to this city for a day. They disband when it ends."),
    MARTIAL_LAW("Martial Law", Tier.CAPITAL, Items.IRON_BARS, false, 0, 2, 40,
            "While a city of yours is raided: breaches go 40% slower, its garrison heals 1 HP every 3 s, and every town loses 10 mood.");

    public static final String RESEARCH = "royal_decrees", MILITARY_RESEARCH = "command";
    /** War Horn: its own damage term, outside the skill, weapon, legendary, path and bond budgets. */
    public static final double WAR_HORN_BONUS = .15;
    public static final int WAR_HORN_TICKS = 3600, WAR_HORN_MORALE = 20, WAR_HORN_FLOOR = 50;
    public static final double FEAST_MOOD = 10, FEAST_LOYALTY = 4, FEAST_SICKNESS = 1.5, HOLIDAY_MOOD = 8, HOLIDAY_LOYALTY = 3,
            LEVY_MOOD = -10, LEVY_LOYALTY = -3, AMNESTY_LOYALTY = 55, AMNESTY_PEACE = 15, MARTIAL_MOOD = -10, MARTIAL_BREACH = .6;
    /** Feast food: nutrition per person in the kingdom (at least 60); Amnesty gold; levies per town by rank and in all. */
    public static final int FEAST_FOOD_PER_HEAD = 2, FEAST_FOOD_MIN = 60, AMNESTY_GOLD = 8, LEVY_EMERALDS = 4, LEVY_FOOD = 10, MAX_LEVIES = 20;
    static final int[] LEVIES_BY_TIER = {1, 1, 2, 3, 4, 6};

    public final String label;
    public final Tier tier;
    public final Item icon;
    public final boolean military;
    /** Duration in settlement days (War Horn and Martial Law have their own), cooldown in days, base emerald cost. */
    final int days, cooldownDays, emeralds;
    public final String description;

    Decree(String label, Tier tier, Item icon, boolean military, int days, int cooldownDays, int emeralds, String description) {
        this.label = label; this.tier = tier; this.icon = icon; this.military = military;
        this.days = days; this.cooldownDays = cooldownDays; this.emeralds = emeralds; this.description = description;
    }

    static long day() { return Config.DAY_LENGTH.get(); }

    /** How long the decree lasts, in ticks. */
    public long duration() {
        return switch (this) {
            case WAR_HORN -> WAR_HORN_TICKS;
            case MARTIAL_LAW -> Siege.MAX_TICKS;
            default -> days * day();
        };
    }

    public long cooldown() { return cooldownDays * day(); }

    /** Royal Feast: the crowds' sickness lasts the first half day. */
    static long feastCrowds() { return day() / 2; }

    /** Whether the decree is chronicled in every town (the major ones). */
    public boolean chronicled() { return this == CALL_THE_BANNERS || this == AMNESTY; }

    @Nullable
    public static Decree of(String id) {
        try { return valueOf(id); } catch (IllegalArgumentException | NullPointerException e) { return null; }
    }

    static int leviesOf(Tier t) { return LEVIES_BY_TIER[t.ordinal()]; }
}
