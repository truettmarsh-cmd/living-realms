package livingrealms;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue SIM_INTERVAL;
    public static final ForgeConfigSpec.IntValue DAY_LENGTH;
    public static final ForgeConfigSpec.IntValue BUILD_INTERVAL;
    public static final ForgeConfigSpec.DoubleValue OFFSCREEN_BUILD_RATE;
    public static final ForgeConfigSpec.BooleanValue AI_VILLAGES;
    public static final ForgeConfigSpec.IntValue AI_MIN_DISTANCE;
    public static final ForgeConfigSpec.IntValue MAX_VISIBLE_TOWNSFOLK;
    public static final ForgeConfigSpec.IntValue MAX_TEMPLATE_FOOTPRINT;
    public static final ForgeConfigSpec.BooleanValue AI_GARRISONS;
    public static final ForgeConfigSpec.BooleanValue NPC_RAIDS;
    public static final ForgeConfigSpec.IntValue RAID_RANGE, RAID_COOLDOWN;
    public static final ForgeConfigSpec.BooleanValue VILLAGE_EXPANSION;
    public static final ForgeConfigSpec.BooleanValue TOWN_CLAIMS;
    public static final ForgeConfigSpec.IntValue CHRONICLE_MAX, CHRONICLE_YEAR;
    public static final ForgeConfigSpec.BooleanValue FIRES;
    public static final ForgeConfigSpec.DoubleValue FIRE_CHANCE, FIRE_RAID_CHANCE;
    public static final ForgeConfigSpec.IntValue FIRE_BRIGADE_DELAY, FIRE_MAX_TICKS;

    public static final ForgeConfigSpec.BooleanValue ORIGIN_NATIVE_SPAWNS;
    public static final ForgeConfigSpec.IntValue ORIGIN_HUMAN, ORIGIN_DWARF, ORIGIN_ELF, ORIGIN_GIANT;

    static {
        var b = new ForgeConfigSpec.Builder();
        b.push("simulation");
        SIM_INTERVAL = b.comment("Ticks between settlement simulation steps (economy, research, population, training).")
                .defineInRange("simInterval", 200, 20, 6000);
        DAY_LENGTH = b.comment("Length of a settlement 'day' in ticks. All per-day rates (production, food, research) are scaled to this.")
                .defineInRange("dayLength", 24000, 1200, 240000);
        BUILD_INTERVAL = b.comment("Ticks between blocks placed by one builder.")
                .defineInRange("buildInterval", 6, 1, 100);
        OFFSCREEN_BUILD_RATE = b.comment("Builders keep working while nobody is near, at this fraction of their normal speed. 0 turns it off.")
                .defineInRange("offscreenBuildRate", 0.5, 0.0, 4.0);
        b.pop();
        b.push("ai");
        AI_VILLAGES = b.comment("Generated villages (vanilla and modded) become AI settlements that grow on their own.")
                .define("aiVillages", true);
        AI_GARRISONS = b.comment("AI settlements keep a garrison of Recruits soldiers.")
                .define("aiGarrisons", true);
        AI_MIN_DISTANCE = b.comment("Minimum distance between two settlements' centers.")
                .defineInRange("minDistance", 160, 48, 2048);
        b.pop();
        b.push("warfare");
        NPC_RAIDS = b.comment("NPC kingdoms can raid nearby rival kingdoms, including online players. Raids use existing soldiers.").define("npcRaids", true);
        RAID_RANGE = b.comment("Maximum distance between attacking and defending city centers. Armies travel through loaded terrain.").defineInRange("raidRange", 1024, 64, 4096);
        RAID_COOLDOWN = b.comment("Ticks between raids; also the grace period for newly discovered NPC kingdoms.").defineInRange("raidCooldown", 24000, 1200, 2400000);
        b.pop();
        b.push("villageExpansion");
        VILLAGE_EXPANSION = b.comment("With Villager Recruits: Village Expansion installed, its village factions own the NPC villages, your kingdom joins its diplomacy and wars, and its city planner lays out your towns. No effect without it.")
                .define("enabled", true);
        b.pop();
        b.push("townClaims");
        TOWN_CLAIMS = b.comment("Your towns hold a Recruits claim owned by your realm's faction. It shows on the Recruits world map and grows with the town: Village Expansion's 7x7-chunk founding claim, then the town's land as it ranks up. Village Expansion's claim raids cannot delete it. Switching this off removes those claims.")
                .define("enabled", true);
        b.pop();
        b.push("misc");
        MAX_VISIBLE_TOWNSFOLK = b.comment("Townsfolk shown walking around a settlement (besides builders). The rest of the population is simulated.")
                .defineInRange("maxVisibleTownsfolk", 8, 0, 40);
        MAX_TEMPLATE_FOOTPRINT = b.comment("Largest width/depth of a structure template that is picked up as a building.")
                .defineInRange("maxTemplateFootprint", 32, 8, 64);
        b.pop();
        b.push("recruitOrigins");
        b.comment("Relative weights for newly trained recruits only. Existing recruits are never rerolled; all zero means human.");
        ORIGIN_NATIVE_SPAWNS = b.comment("Assign origins to genuinely new native Villager Recruits spawns too; never to entities loaded from disk.").define("nativeSpawns", true);
        ORIGIN_HUMAN = b.defineInRange("humanWeight", 80, 0, 10000);
        ORIGIN_DWARF = b.defineInRange("dwarfWeight", 8, 0, 10000);
        ORIGIN_ELF = b.defineInRange("elfWeight", 9, 0, 10000);
        ORIGIN_GIANT = b.defineInRange("giantWeight", 3, 0, 10000);
        b.pop();
        b.push("chronicle");
        CHRONICLE_MAX = b.comment("Entries a town's chronicle keeps; the oldest go first (never the founding).").defineInRange("maxEntries", 120, 8, 1000);
        CHRONICLE_YEAR = b.comment("Minecraft days in a chronicle year.").defineInRange("yearDays", 30, 1, 3650);
        b.pop();
        b.push("fires");
        FIRES = b.comment("Town fires: wooden buildings can catch fire, townsfolk fight it, fire is kept inside the town and damaged buildings are repaired. Off: none of this happens.")
                .define("enabled", true);
        FIRE_CHANCE = b.comment("Chance of an outbreak per day for a town of 8 all-wooden buildings (scales with the town's fire risk, at most x3). Wells and the Fire Watch research lower it.")
                .defineInRange("dailyChance", 0.02, 0.0, 1.0);
        FIRE_RAID_CHANCE = b.comment("Extra chance per minute of a raid that the attackers set a building on fire.").defineInRange("raidChancePerMinute", 0.05, 0.0, 1.0);
        FIRE_BRIGADE_DELAY = b.comment("Ticks before the bucket brigade arrives at a fire.").defineInRange("brigadeDelay", 200, 0, 6000);
        FIRE_MAX_TICKS = b.comment("Ticks after which any fire still burning in a town is put out.").defineInRange("maxFireTicks", 6000, 400, 72000);
        b.pop();
        livingrealms.compat.CompatConfig.define(b);
        SPEC = b.build();
    }

    private Config() {}
}
