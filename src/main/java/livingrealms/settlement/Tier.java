package livingrealms.settlement;

/** Settlement ranks. Each needs people, buildings and a research before the town can petition for it. */
public enum Tier {
    CAMP("Camp", 0, 0, null, 0, 40, 1),
    HAMLET("Hamlet", 6, 4, null, 16, 56, 1),
    VILLAGE("Village", 12, 9, "masonry", 48, 72, 2),
    TOWN("Town", 24, 16, "architecture", 96, 96, 2),
    CITY("City", 42, 26, "urban_planning", 192, 128, 3),
    CAPITAL("Capital", 70, 40, "royal_charter", 384, 160, 3);

    public final String label;
    public final int population, buildings;
    public final String research;
    public final int emeralds;
    public final int radius;
    /** Highest level any building may be upgraded to at this rank. */
    public final int maxBuildingLevel;

    Tier(String label, int population, int buildings, String research, int emeralds, int radius, int maxBuildingLevel) {
        this.label = label;
        this.population = population;
        this.buildings = buildings;
        this.research = research;
        this.emeralds = emeralds;
        this.radius = radius;
        this.maxBuildingLevel = maxBuildingLevel;
    }

    public int number() { return ordinal() + 1; }

    public static Tier of(int number) {
        return values()[Math.max(0, Math.min(values().length - 1, number - 1))];
    }

    public Tier next() { return this == CAPITAL ? null : values()[ordinal() + 1]; }
}
