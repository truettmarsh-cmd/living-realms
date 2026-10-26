package livingrealms.settlement;

/** A settler's work. The profession is the vanilla villager outfit a visible settler wears. */
public enum Job {
    BUILDER("Builder", "mason"),
    FARMER("Farmer", "farmer"),
    LUMBERJACK("Lumberjack", "fletcher"),
    MINER("Miner", "toolsmith"),
    SMITH("Smith", "armorer"),
    SCHOLAR("Scholar", "librarian"),
    MERCHANT("Merchant", "cartographer"),
    CRAFTSMAN("Craftsman", "leatherworker"),
    INNKEEPER("Innkeeper", "butcher"),
    PRIEST("Priest", "cleric"),
    STABLEHAND("Stablehand", "shepherd"),
    HAULER("Hauler", "nitwit"),
    IDLE("Townsfolk", "none");

    public final String label;
    public final String profession;

    Job(String label, String profession) {
        this.label = label;
        this.profession = profession;
    }
}
