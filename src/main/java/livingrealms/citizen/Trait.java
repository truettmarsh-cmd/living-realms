package livingrealms.citizen;

import javax.annotation.Nullable;

/** A citizen's temperament: one or two each, with small effects on their mood and, for a mayor, on the town. */
public enum Trait {
    HARDWORKING("Hardworking", "Content at work, restless when idle. As mayor: builders work 8% faster."),
    PIOUS("Pious", "Happier with a chapel in town, unsettled without one. As mayor: +3 town mood."),
    BRAVE("Brave", "Shrugs off half the fear after raids and fires. As mayor: the whole town does."),
    GREEDY("Greedy", "Feels every tax twice over. As mayor: +15% taxes, -2 town mood."),
    SICKLY("Sickly", "Often unwell (-4 mood) and dies younger. As mayor: no bonus."),
    CHEERFUL("Cheerful", "+6 mood. As mayor: +4 town mood.");

    public final String label, effect;
    /** Out of 100 traits drawn, how many are this one. */
    static final int[] WEIGHT = {22, 18, 18, 14, 10, 18};

    Trait(String label, String effect) { this.label = label; this.effect = effect; }

    @Nullable
    public static Trait byId(String id) { try { return valueOf(id); } catch (Exception e) { return null; } }
}
