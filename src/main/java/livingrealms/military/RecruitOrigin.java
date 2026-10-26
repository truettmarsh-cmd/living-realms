package livingrealms.military;

/** Birth traits are independent of combat training and never rerolled on reload. */
public enum RecruitOrigin {
    HUMAN("Human", "No innate attribute changes", 0, 0, 1, 1),
    DWARF("Dwarf", "+2 base health; -3% movement speed", 2, -.03, .8f, 1.05f),
    ELF("Elf", "-2 base health; +3% movement speed", -2, .03, 1.08f, .92f),
    GIANT("Giant", "+4 base health; -7% movement speed", 4, -.07, 1.45f, 1.2f);
    public final String label, description;
    public final double health, speed;
    public final float height, width;
    RecruitOrigin(String label, String description, double health, double speed, float height, float width) {
        this.label = label; this.description = description; this.health = health; this.speed = speed;
        this.height = height; this.width = width;
    }
    public static RecruitOrigin byId(String id) {
        try { return valueOf(id); } catch (IllegalArgumentException e) { return HUMAN; }
    }
    /** Roll is in [0,total); a zero total deliberately falls back to human. */
    public static RecruitOrigin select(int roll, int human, int dwarf, int elf, int giant) {
        int[] weights = {Math.max(0, human), Math.max(0, dwarf), Math.max(0, elf), Math.max(0, giant)};
        int total = java.util.Arrays.stream(weights).sum();
        if (total == 0) return HUMAN;
        int remaining = Math.floorMod(roll, total);
        for (int i = 0; i < weights.length; i++) { if (remaining < weights[i]) return values()[i]; remaining -= weights[i]; }
        return HUMAN;
    }
}
