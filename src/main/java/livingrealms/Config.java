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
        b.push("misc");
        MAX_VISIBLE_TOWNSFOLK = b.comment("Townsfolk shown walking around a settlement (besides builders). The rest of the population is simulated.")
                .defineInRange("maxVisibleTownsfolk", 8, 0, 40);
        MAX_TEMPLATE_FOOTPRINT = b.comment("Largest width/depth of a structure template that is picked up as a building.")
                .defineInRange("maxTemplateFootprint", 32, 8, 64);
        b.pop();
        SPEC = b.build();
    }

    private Config() {}
}
