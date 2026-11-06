package livingrealms.military;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Brothers-in-arms, saved per recruit UUID in data/livingrealms_bonds.dat. Nothing here touches a town: any recruit a
 * player owns can hold bonds, whether it was trained in a Living Realms barracks or hired the Villager Recruits way.
 */
public final class BondData extends SavedData {
    /** One recruit: its last known name and owner, and its grief and vengeance after a bonded partner fell. */
    public static final class Member {
        public String name = "";
        @Nullable public UUID owner;
        /** seen: game time it was last loaded (refreshed about every 30 s); removed: discarded, not just unloaded. */
        public long seen, griefUntil, vengeanceUntil, avengeReady, avengeUntil;
        public boolean removed;
        public int griefLevel;
        public String mourned = "", vengeance = "", vengeanceLabel = "";

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("name", name);
            if (owner != null) t.putUUID("owner", owner);
            t.putLong("seen", seen);
            t.putLong("grief", griefUntil);
            t.putInt("griefLevel", griefLevel);
            t.putString("mourned", mourned);
            t.putString("vengeance", vengeance);
            t.putString("vengeanceLabel", vengeanceLabel);
            t.putLong("vengeanceUntil", vengeanceUntil);
            t.putLong("avenge", avengeReady);
            t.putLong("avengeUntil", avengeUntil);
            if (removed) t.putBoolean("removed", true);
            return t;
        }

        static Member load(CompoundTag t) {
            Member m = new Member();
            m.name = t.getString("name");
            if (t.hasUUID("owner")) m.owner = t.getUUID("owner");
            m.seen = t.getLong("seen");
            m.griefUntil = t.getLong("grief");
            m.griefLevel = t.getInt("griefLevel");
            m.mourned = t.getString("mourned");
            m.vengeance = t.getString("vengeance");
            m.vengeanceLabel = t.getString("vengeanceLabel");
            // saved before labels carried their article: "Zombie" reads "the Zombie" (named foes were saved as their name)
            if (!m.vengeanceLabel.isEmpty() && !m.vengeanceLabel.startsWith("the ") && !m.vengeance.isEmpty()
                    && m.vengeance.toLowerCase(java.util.Locale.ROOT).endsWith(m.vengeanceLabel.toLowerCase(java.util.Locale.ROOT).replace(' ', '_')))
                m.vengeanceLabel = "the " + m.vengeanceLabel;
            m.vengeanceUntil = t.getLong("vengeanceUntil");
            m.avengeReady = t.getLong("avenge");
            m.avengeUntil = t.getLong("avengeUntil");
            m.removed = t.getBoolean("removed");
            return m;
        }
    }

    /** Two recruits and the bond between them. Points only grow; the level follows from them (see {@link Bonds.Level}). */
    public static final class Pair {
        public final UUID a, b;
        public int points, today;
        public long day, touched;

        Pair(UUID a, UUID b) { this.a = a; this.b = b; }

        public UUID other(UUID id) { return a.equals(id) ? b : a; }
        public boolean has(UUID id) { return a.equals(id) || b.equals(id); }
        public Bonds.Level level() { return Bonds.Level.of(points); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("a", a); t.putUUID("b", b);
            t.putInt("points", points); t.putInt("today", today);
            t.putLong("day", day); t.putLong("touched", touched);
            return t;
        }

        static Pair load(CompoundTag t) {
            Pair p = new Pair(t.getUUID("a"), t.getUUID("b"));
            p.points = t.getInt("points"); p.today = t.getInt("today");
            p.day = t.getLong("day"); p.touched = t.getLong("touched");
            return p;
        }
    }

    final Map<UUID, Member> members = new HashMap<>();
    private final Map<String, Pair> pairs = new HashMap<>();
    private final Map<UUID, List<Pair>> index = new HashMap<>();

    public static BondData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(BondData::load, BondData::new, "livingrealms_bonds");
    }

    public static String key(UUID x, UUID y) { return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x; }

    @Nullable
    public Member member(UUID id) { return members.get(id); }

    public Member member(UUID id, boolean create) {
        return create ? members.computeIfAbsent(id, k -> { setDirty(); return new Member(); }) : members.get(id);
    }

    @Nullable
    public Pair pair(UUID x, UUID y) { return pairs.get(key(x, y)); }

    public Pair pair(UUID x, UUID y, boolean create) {
        Pair p = pairs.get(key(x, y));
        if (p != null || !create) return p;
        p = x.compareTo(y) < 0 ? new Pair(x, y) : new Pair(y, x);
        add(p);
        setDirty();
        return p;
    }

    private void add(Pair p) {
        pairs.put(key(p.a, p.b), p);
        index.computeIfAbsent(p.a, k -> new ArrayList<>()).add(p);
        index.computeIfAbsent(p.b, k -> new ArrayList<>()).add(p);
    }

    /** Every pair this recruit is in, forming ones included. Do not modify. */
    public List<Pair> pairsOf(UUID id) { return index.getOrDefault(id, List.of()); }

    /** Bonds at Comrades or better, strongest first. */
    public List<Pair> bondsOf(UUID id) {
        List<Pair> out = new ArrayList<>();
        for (Pair p : pairsOf(id)) if (p.level() != Bonds.Level.NONE) out.add(p);
        out.sort(Comparator.comparingInt((Pair p) -> -p.points));
        return out;
    }

    public void removePair(Pair p) {
        if (pairs.remove(key(p.a, p.b)) == null) return;
        for (UUID id : new UUID[]{p.a, p.b}) {
            List<Pair> l = index.get(id);
            if (l == null) continue;
            l.remove(p);
            if (l.isEmpty()) index.remove(id);
        }
        setDirty();
    }

    /** Forgets a recruit and every pair it was in. */
    public void forget(UUID id) {
        for (Pair p : new ArrayList<>(pairsOf(id))) removePair(p);
        if (members.remove(id) != null) setDirty();
    }

    /** A recruit was replaced by a new entity (a Recruits promotion): its bonds and grief move to the new UUID. */
    public void move(UUID from, UUID to) {
        if (from.equals(to)) return;
        Member m = members.remove(from);
        if (m != null) { m.removed = false; members.put(to, m); }
        for (Pair p : new ArrayList<>(pairsOf(from))) {
            removePair(p);
            UUID other = p.other(from);
            if (other.equals(to)) continue;
            Pair n = pair(to, other, true);
            n.points = Math.max(n.points, p.points); n.today = p.today; n.day = p.day; n.touched = p.touched;
        }
        setDirty();
    }

    public Collection<Pair> allPairs() { return pairs.values(); }

    static BondData load(CompoundTag t) {
        BondData d = new BondData();
        CompoundTag m = t.getCompound("members");
        for (String k : m.getAllKeys()) {
            try { d.members.put(UUID.fromString(k), Member.load(m.getCompound(k))); } catch (IllegalArgumentException ignored) {}
        }
        for (Tag x : t.getList("pairs", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            if (c.hasUUID("a") && c.hasUUID("b") && !c.getUUID("a").equals(c.getUUID("b"))) d.add(Pair.load(c));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag t) {
        CompoundTag m = new CompoundTag();
        members.forEach((id, x) -> m.put(id.toString(), x.save()));
        ListTag l = new ListTag();
        for (Pair p : pairs.values()) l.add(p.save());
        t.put("members", m);
        t.put("pairs", l);
        return t;
    }
}
