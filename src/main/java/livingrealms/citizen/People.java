package livingrealms.citizen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A town's people: a record per adult (up to {@link #MAX_ADULTS}) and per child, the dead remembered, the mayor and
 * captain, and the town's shared fear after a raid or fire. Saved with the settlement as "people"; towns from older
 * saves load empty and are peopled on their next simulation step.
 */
public final class People {
    public static final int MAX_ADULTS = 200, MAX_CHILDREN = 40, MEMORIAL = 12;
    public static final double YEARS_PER_DAY = 0.5;

    public final List<Citizen> all = new ArrayList<>();
    public final Deque<CompoundTag> memorial = new ArrayDeque<>();
    public int nextId = 1;
    /** Town days simulated (fractional). */
    public double clock;
    /** Whole days already handled by the daily step. */
    public long lastDay;
    public boolean started;
    public int mayor;
    public boolean mayorAppointed;
    public double lastElection = -1000;
    @Nullable public UUID captain;
    public boolean captainAppointed;
    public double captainSince = -1000;
    /** Fear after a raid, fire or siege (0–30), fading a few points a day, and what caused it. */
    public double shock;
    public String shockWhy = "";
    /** Hash of the newest chronicle entry already looked at for shocks. */
    public int seenEntry;
    public int births, deaths, weddings;
    /** An open citizen's request (see Requests): who, what, how many, and until when (town clock). */
    public int askBy, askCount;
    public String askItem = "", askWhy = "";
    public double askUntil;
    @Nullable public UUID askBounty;

    /** Not saved: citizens walking about as settlers right now. */
    public transient final Set<Integer> shown = new HashSet<>();
    private transient Map<Integer, Citizen> index;

    @Nullable
    public Citizen get(int id) {
        if (id <= 0) return null;
        if (index == null || index.size() != all.size()) reindex();
        Citizen c = index.get(id);
        if (c == null || !all.contains(c)) { reindex(); c = index.get(id); }
        return c;
    }

    void reindex() {
        index = new HashMap<>();
        for (Citizen c : all) index.put(c.id, c);
    }

    void changed() { index = null; }

    /** Everyone counted in the population (adults and elders). */
    public List<Citizen> adults() {
        List<Citizen> out = new ArrayList<>();
        for (Citizen c : all) if (c.grown) out.add(c);
        return out;
    }

    /** Children, and youths of age waiting for room. Not counted in the population. */
    public List<Citizen> children() {
        List<Citizen> out = new ArrayList<>();
        for (Citizen c : all) if (!c.grown) out.add(c);
        return out;
    }

    public List<Citizen> childrenOf(Citizen p) {
        List<Citizen> out = new ArrayList<>();
        for (Citizen c : all) if (c.mother == p.id || c.father == p.id) out.add(c);
        return out;
    }

    @Nullable
    public Citizen mayor() { return get(mayor); }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        all.forEach(c -> l.add(c.save()));
        t.put("all", l);
        ListTag mem = new ListTag();
        mem.addAll(memorial);
        t.put("memorial", mem);
        t.putInt("next", nextId);
        t.putDouble("clock", clock);
        t.putLong("lastDay", lastDay);
        t.putBoolean("started", started);
        t.putInt("mayor", mayor);
        t.putBoolean("mayorAppointed", mayorAppointed);
        t.putDouble("lastElection", lastElection);
        if (captain != null) t.putUUID("captain", captain);
        t.putBoolean("captainAppointed", captainAppointed);
        t.putDouble("captainSince", captainSince);
        t.putDouble("shock", shock);
        t.putString("shockWhy", shockWhy);
        t.putInt("seen", seenEntry);
        t.putInt("births", births); t.putInt("deaths", deaths); t.putInt("weddings", weddings);
        t.putInt("askBy", askBy); t.putInt("askCount", askCount); t.putString("askItem", askItem); t.putString("askWhy", askWhy);
        t.putDouble("askUntil", askUntil);
        if (askBounty != null) t.putUUID("askBounty", askBounty);
        return t;
    }

    public void load(CompoundTag t) {
        all.clear();
        memorial.clear();
        for (Tag e : t.getList("all", Tag.TAG_COMPOUND)) {
            Citizen c = Citizen.load((CompoundTag) e);
            if (c.id > 0 && all.size() < MAX_ADULTS + MAX_CHILDREN) all.add(c);
        }
        for (Tag e : t.getList("memorial", Tag.TAG_COMPOUND)) if (memorial.size() < MEMORIAL) memorial.addLast((CompoundTag) e);
        nextId = Math.max(1, t.getInt("next"));
        for (Citizen c : all) nextId = Math.max(nextId, c.id + 1);
        clock = Math.max(0, t.getDouble("clock"));
        lastDay = t.getLong("lastDay");
        started = t.getBoolean("started") && !all.isEmpty();
        mayor = t.getInt("mayor");
        mayorAppointed = t.getBoolean("mayorAppointed");
        lastElection = t.contains("lastElection") ? t.getDouble("lastElection") : -1000;
        captain = t.hasUUID("captain") ? t.getUUID("captain") : null;
        captainAppointed = t.getBoolean("captainAppointed");
        captainSince = t.contains("captainSince") ? t.getDouble("captainSince") : -1000;
        shock = Math.max(0, Math.min(30, t.getDouble("shock")));
        shockWhy = t.getString("shockWhy");
        seenEntry = t.getInt("seen");
        births = t.getInt("births"); deaths = t.getInt("deaths"); weddings = t.getInt("weddings");
        askBy = t.getInt("askBy"); askCount = t.getInt("askCount"); askItem = t.getString("askItem"); askWhy = t.getString("askWhy");
        askUntil = t.getDouble("askUntil");
        askBounty = t.hasUUID("askBounty") ? t.getUUID("askBounty") : null;
        changed();
    }
}
