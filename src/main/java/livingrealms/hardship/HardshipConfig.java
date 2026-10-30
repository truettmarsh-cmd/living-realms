package livingrealms.hardship;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server switches under [hardships] (docs/hardships.md). Everything off: towns never fall sick, harvests never fail. */
public final class HardshipConfig {
    public static ForgeConfigSpec.BooleanValue ENABLED, SICKNESS, HARVEST, VERMIN, AI_QUARANTINE;
    public static ForgeConfigSpec.DoubleValue SICK_CHANCE, CARAVAN_CONTAGION, DEATH_RATE, HARVEST_CHANCE, VERMIN_CHANCE;
    public static ForgeConfigSpec.IntValue HARVEST_DAYS;

    public static void define(ForgeConfigSpec.Builder b) {
        b.push("hardships");
        ENABLED = b.comment("Town hardships: sickness, failed harvests and famine, rats in the stores. Off: none of them happen and nothing changes.").define("enabled", true);
        SICKNESS = b.comment("Sickness outbreaks: crowded, besieged or poorly fed towns fall sick; caravans carry it between towns.").define("sickness", true);
        SICK_CHANCE = b.comment("Base chance per day of an outbreak in a town of 24 (more when crowded, after a siege, on a poor diet, or hungry; less with a chapel, apothecary or Herb Lore).")
                .defineInRange("sicknessDailyChance", 0.01, 0.0, 1.0);
        CARAVAN_CONTAGION = b.comment("Chance that a caravan from a sick town brings the sickness with it.").defineInRange("caravanContagion", 0.35, 0.0, 1.0);
        DEATH_RATE = b.comment("Share of the sick who die per day, before care (a chapel, apothecary and Herb Lore each lower it). 0: sickness never kills.")
                .defineInRange("sicknessDeathRate", 0.03, 0.0, 1.0);
        HARVEST = b.comment("Bad harvests (drought in desert and savanna, cold snaps in snowy and taiga lands, blight elsewhere) cut farm output for a few days.").define("badHarvests", true);
        HARVEST_CHANCE = b.comment("Chance per day of a bad harvest in a town with farms.").defineInRange("harvestDailyChance", 0.02, 0.0, 1.0);
        HARVEST_DAYS = b.comment("How many days a bad harvest lasts.").defineInRange("harvestDays", 3, 1, 30);
        VERMIN = b.comment("Rats get into well-stocked stores and eat grain and bread for a couple of days.").define("vermin", true);
        VERMIN_CHANCE = b.comment("Chance per day of rats in a town holding 128 or more food.").defineInRange("verminDailyChance", 0.01, 0.0, 1.0);
        AI_QUARANTINE = b.comment("NPC towns close their roads to caravans while sick.").define("npcQuarantine", true);
        b.pop();
    }

    private HardshipConfig() {}
}
