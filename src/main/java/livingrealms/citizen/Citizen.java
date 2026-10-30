package livingrealms.citizen;

import livingrealms.settlement.Job;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.EnumSet;
import java.util.Set;

/** One townsperson's record. Ages are in years; a town day is {@link People#YEARS_PER_DAY} years. */
public final class Citizen {
    public enum Stage { CHILD("Child"), ADULT("Adult"), ELDER("Elder");
        public final String label;
        Stage(String label) { this.label = label; }
    }

    public static final int ADULT_AGE = 16, ELDER_AGE = 60;

    public int id;
    public String first = "", family = "";
    public boolean female;
    /** Counted in the town's population: every adult, and a child once they come of age and find room. */
    public boolean grown = true;
    /** Town clock (days) at birth: age is (clock - born) × years per day. */
    public double born;
    public Job job = Job.IDLE;
    /** Plot id of their home; 0 a house that came with the village, -1 none. */
    public int home = -1;
    public int spouse, mother, father;
    public final Set<Trait> traits = EnumSet.noneOf(Trait.class);
    public double mood = 60;
    /** Town clock until which they grieve a close death / are glad of a favour done. */
    public double griefUntil, gladUntil;

    public String name() { return first + (family.isEmpty() ? "" : " " + family); }

    public int age(double clock) { return (int) Math.max(0, (clock - born) * People.YEARS_PER_DAY); }

    public Stage stage(double clock) {
        int a = age(clock);
        return a < ADULT_AGE ? Stage.CHILD : a >= ELDER_AGE ? Stage.ELDER : Stage.ADULT;
    }

    public boolean child(double clock) { return age(clock) < ADULT_AGE; }

    /** "Child", "Adult", "Elder", or "Youth" for one of age still waiting for room to set up on their own. */
    public String stageLabel(double clock) {
        if (!grown) return child(clock) ? "Child" : "Youth";
        return stage(clock).label;
    }

    public boolean has(Trait t) { return traits.contains(t); }

    public String traitText() {
        StringBuilder b = new StringBuilder();
        for (Trait t : traits) b.append(b.length() == 0 ? "" : ", ").append(t.label.toLowerCase(java.util.Locale.ROOT));
        return b.toString();
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("f", first); t.putString("n", family);
        t.putBoolean("w", female);
        t.putBoolean("gr", grown);
        t.putDouble("b", born);
        t.putString("j", job.name());
        t.putInt("h", home);
        t.putInt("sp", spouse); t.putInt("mo", mother); t.putInt("fa", father);
        ListTag tr = new ListTag();
        traits.forEach(x -> tr.add(StringTag.valueOf(x.name())));
        t.put("tr", tr);
        t.putDouble("m", mood);
        t.putDouble("g", griefUntil); t.putDouble("gl", gladUntil);
        return t;
    }

    public static Citizen load(CompoundTag t) {
        Citizen c = new Citizen();
        c.id = t.getInt("id");
        c.first = t.getString("f"); c.family = t.getString("n");
        c.female = t.getBoolean("w");
        c.born = t.getDouble("b");
        c.grown = !t.contains("gr") || t.getBoolean("gr");
        try { c.job = Job.valueOf(t.getString("j")); } catch (Exception e) { c.job = Job.IDLE; }
        c.home = t.contains("h") ? t.getInt("h") : -1;
        c.spouse = t.getInt("sp"); c.mother = t.getInt("mo"); c.father = t.getInt("fa");
        for (Tag x : t.getList("tr", Tag.TAG_STRING)) {
            Trait tr = Trait.byId(x.getAsString());
            if (tr != null && c.traits.size() < 2) c.traits.add(tr);
        }
        c.mood = t.contains("m") ? Math.max(0, Math.min(100, t.getDouble("m"))) : 60;
        c.griefUntil = t.getDouble("g"); c.gladUntil = t.getDouble("gl");
        return c;
    }
}
