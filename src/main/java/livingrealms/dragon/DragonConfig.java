package livingrealms.dragon;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server switches under dragons.* (docs/dragons.md). Everything here needs Ice and Fire except the scorpions. */
public final class DragonConfig {
    public static ForgeConfigSpec.BooleanValue WILD_ATTACKS, NPC_DRAGONS, SORTIES, SCORPIONS;
    public static ForgeConfigSpec.DoubleValue WILD_CHANCE;
    public static ForgeConfigSpec.IntValue WILD_RANGE, ATTACK_SECONDS, MAX_FIRES, HATCH_TICKS, NPC_EGG_DAYS, GROWTH_DAYS;
    public static ForgeConfigSpec.DoubleValue BOLT_DAMAGE, BOLT_DRAGON_DAMAGE;

    public static void define(ForgeConfigSpec.Builder b) {
        b.push("dragons");
        WILD_ATTACKS = b.comment("Ice and Fire: wild dragons near a town sometimes attack it. Off: wild dragons are left to Ice and Fire.").define("wildAttacks", true);
        WILD_CHANCE = b.comment("Chance, per loaded town and minute, that a wild adult dragon within wildAttackRange is drawn to attack it.").defineInRange("wildAttackChance", 0.02, 0.0, 1.0);
        WILD_RANGE = b.comment("How far from a town's centre a wild dragon may be to be drawn to it.").defineInRange("wildAttackRange", 160, 32, 512);
        ATTACK_SECONDS = b.comment("How long a dragon attack or a raid sortie lasts before the dragon flies off.").defineInRange("attackSeconds", 60, 10, 600);
        MAX_FIRES = b.comment("Most buildings one dragon attack or sortie can set alight (its breath still burns people).").defineInRange("maxFiresPerAttack", 2, 0, 10);
        NPC_DRAGONS = b.comment("NPC kingdoms with a Dragon Roost raise a dragon of their own.").define("npcDragons", true);
        SORTIES = b.comment("NPC kingdoms bring their roost dragon and a rider on raids.").define("raidSorties", true);
        HATCH_TICKS = b.comment("Ticks an egg spends in the roost's embers before it hatches.").defineInRange("hatchTicks", 24000, 20, 2400000);
        NPC_EGG_DAYS = b.comment("Days before an NPC roost without a dragon gets a new egg.").defineInRange("npcEggDays", 3, 0, 365);
        GROWTH_DAYS = b.comment("Extra days of growth a roost dragon gains each day it spends at the roost (keepers feed it), until stage 4.").defineInRange("roostGrowthDays", 4, 0, 50);
        SCORPIONS = b.comment("Scorpions (research): towers loose heavy bolts at dragons and other attackers.").define("scorpions", true);
        BOLT_DAMAGE = b.comment("Damage of a scorpion bolt.").defineInRange("boltDamage", 10.0, 0.0, 1000.0);
        BOLT_DRAGON_DAMAGE = b.comment("Damage of a scorpion bolt against a dragon (after armour).").defineInRange("boltDragonDamage", 30.0, 0.0, 1000.0);
        b.pop();
    }

    /** A value, or its default before the config has loaded (tests, early ticks). */
    static boolean on(ForgeConfigSpec.BooleanValue v) {
        try { return v.get(); } catch (IllegalStateException notLoaded) { return v.getDefault(); }
    }

    static int i(ForgeConfigSpec.IntValue v) {
        try { return v.get(); } catch (IllegalStateException notLoaded) { return v.getDefault(); }
    }

    static double d(ForgeConfigSpec.DoubleValue v) {
        try { return v.get(); } catch (IllegalStateException notLoaded) { return v.getDefault(); }
    }

    private DragonConfig() {}
}
