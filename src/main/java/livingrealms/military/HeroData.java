package livingrealms.military;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Heroes, saved per recruit UUID in data/livingrealms_heroes.dat, apart from any town (like BondData and SupplyData).
 * Also counts the reported battles each recruit lived through, which is one road to ascension.
 */
public final class HeroData extends SavedData {
    /** One Hero. Name, path and epithet are copied at ascension so a hero out of its garrison is still known. */
    public static final class Hero {
        public UUID owner;
        public String name = "", epithet = "", mastery = "";
        @Nullable public SoldierPath path;
        /** ascended: game time; ultimateReady: game time the ultimate is ready again; seen: last loaded. */
        public long ascended, ultimateReady, seen;
        public int valorXp;
        /** Miracle was used in the battle still going on. */
        public boolean miracleSpent;
        /** Discarded for good (not just unloaded): holds no place under the cap. */
        public boolean removed;

        public int valor() { return Heroes.valorLevel(valorXp); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            if (owner != null) t.putUUID("owner", owner);
            t.putString("name", name); t.putString("epithet", epithet); t.putString("mastery", mastery);
            t.putString("path", path == null ? "" : path.name());
            t.putLong("ascended", ascended); t.putLong("ultimate", ultimateReady); t.putLong("seen", seen);
            t.putInt("valorXp", valorXp);
            if (miracleSpent) t.putBoolean("miracle", true);
            if (removed) t.putBoolean("removed", true);
            return t;
        }

        static Hero load(CompoundTag t) {
            Hero h = new Hero();
            h.owner = t.hasUUID("owner") ? t.getUUID("owner") : null;
            h.name = t.getString("name"); h.epithet = t.getString("epithet"); h.mastery = t.getString("mastery");
            h.path = SoldierPath.byId(t.getString("path"));
            h.ascended = t.getLong("ascended"); h.ultimateReady = t.getLong("ultimate"); h.seen = t.getLong("seen");
            h.valorXp = Math.max(0, t.getInt("valorXp"));
            h.miracleSpent = t.getBoolean("miracle");
            h.removed = t.getBoolean("removed");
            return h;
        }
    }

    final Map<UUID, Hero> heroes = new HashMap<>();
    /** Reported battles each recruit came out of alive (Heroes.survived). */
    final Map<UUID, Integer> battles = new HashMap<>();

    public static HeroData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(HeroData::load, HeroData::new, "livingrealms_heroes");
    }

    @Nullable
    public Hero hero(UUID id) { return heroes.get(id); }

    public Hero add(UUID id) { Hero h = heroes.computeIfAbsent(id, k -> new Hero()); setDirty(); return h; }

    public Collection<Map.Entry<UUID, Hero>> all() { return heroes.entrySet(); }

    public int battles(UUID id) { return battles.getOrDefault(id, 0); }

    public void survived(UUID id) { battles.merge(id, 1, Integer::sum); setDirty(); }

    /** Forgets a recruit: its hero record and its battle count. */
    public void forget(UUID id) {
        boolean changed = heroes.remove(id) != null;
        changed |= battles.remove(id) != null;
        if (changed) setDirty();
    }

    /** A recruit replaced by a new entity (a promotion through Recruit Command): its hero record and battles follow. */
    public void move(UUID from, UUID to) {
        if (from.equals(to)) return;
        Hero h = heroes.remove(from);
        if (h != null) { h.removed = false; heroes.put(to, h); }
        Integer b = battles.remove(from);
        if (b != null) battles.merge(to, b, Math::max);
        setDirty();
    }

    /** Ticks a hero discarded for good is remembered (a retrained recruit comes back at once with its UUID). */
    static final long FORGET_REMOVED = 7 * 24000L;

    /**
     * Keeps the file from growing without end: heroes discarded for good a week ago, and battle counts of recruits
     * that are in no town's garrison and aren't Heroes (only a town soldier can ascend).
     */
    void prune(MinecraftServer server, long now) {
        boolean changed = heroes.values().removeIf(h -> h.removed && now - h.seen > FORGET_REMOVED && now >= h.seen);
        if (!battles.isEmpty()) {
            Set<UUID> soldiers = new HashSet<>();
            for (var s : livingrealms.settlement.SettlementManager.get(server).all()) for (var so : s.soldiers) if (so.entity != null) soldiers.add(so.entity);
            changed |= battles.keySet().removeIf(id -> !soldiers.contains(id) && !heroes.containsKey(id));
        }
        if (changed) setDirty();
    }

    static HeroData load(CompoundTag t) {
        HeroData d = new HeroData();
        CompoundTag h = t.getCompound("heroes");
        for (String k : h.getAllKeys()) {
            try { d.heroes.put(UUID.fromString(k), Hero.load(h.getCompound(k))); } catch (IllegalArgumentException ignored) {}
        }
        for (Tag x : t.getList("battles", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            if (c.hasUUID("id")) d.battles.put(c.getUUID("id"), Math.max(0, c.getInt("n")));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag t) {
        CompoundTag h = new CompoundTag();
        heroes.forEach((id, x) -> h.put(id.toString(), x.save()));
        ListTag b = new ListTag();
        battles.forEach((id, n) -> { CompoundTag c = new CompoundTag(); c.putUUID("id", id); c.putInt("n", n); b.add(c); });
        t.put("heroes", h);
        t.put("battles", b);
        return t;
    }
}
