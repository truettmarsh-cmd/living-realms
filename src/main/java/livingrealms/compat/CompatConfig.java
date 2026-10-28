package livingrealms.compat;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server switches under compat.*; each only matters when its mod is installed. */
public final class CompatConfig {
    public static ForgeConfigSpec.BooleanValue FEUDAL_TITLES, FEUDAL_OATHS, RANK_TITLES, SIEGE_TRAINS, NPC_SIEGE_TRAINS,
            RAIDER_ALARM, MORALE, WORKERS_STAFF, CORPSE_SALVAGE, HERALDRY_GEAR;

    public static void define(ForgeConfigSpec.Builder b) {
        b.push("compat");
        FEUDAL_TITLES = b.comment("Vassal & Suzerain: your Recruits faction's feudal title (Count, Duke, King) follows your kingdom's size. A title you set by hand is left alone.").define("feudalTitles", true);
        FEUDAL_OATHS = b.comment("Vassal & Suzerain: kingdoms bound as liege and vassal cannot raid each other, and a liege hears when a vassal is raided.").define("feudalOaths", true);
        RANK_TITLES = b.comment("Ranks and Titles: your faction's Leader title shows your ruler title unless you renamed it.").define("rankTitles", true);
        SIEGE_TRAINS = b.comment("Siege Weapons / Medieval Siege Machines: raids take one crewed siege engine from the attacking city's stockpile.").define("siegeTrains", true);
        NPC_SIEGE_TRAINS = b.comment("NPC kingdoms of City rank and above bring a siege engine to their raids.").define("npcSiegeTrains", true);
        RAIDER_ALARM = b.comment("Siege Overhaul: its raiders ring a town's alarm bell and its garrison engages them.").define("raiderAlarm", true);
        MORALE = b.comment("Recruits Morale addon: honours and refusals change battle morale too.").define("morale", true);
        WORKERS_STAFF = b.comment("Workers: your Workers-mod workers inside a town fill its matching jobs, freeing townsfolk.").define("workersStaffTowns", true);
        CORPSE_SALVAGE = b.comment("Corpse: after a fight, fresh gear on your fallen soldiers' corpses returns to the town armoury; the corpse is laid at the town centre.").define("corpseSalvage", true);
        HERALDRY_GEAR = b.comment("Knights Heraldry: its armour and weapons become smithing patterns and kit choices.").define("heraldryGear", true);
        b.pop();
    }

    private CompatConfig() {}
}
