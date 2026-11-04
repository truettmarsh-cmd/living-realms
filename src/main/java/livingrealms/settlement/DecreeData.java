package livingrealms.settlement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Royal decrees per kingdom (owner UUID for a player, the kingdom id for an NPC kingdom), in data/livingrealms_decrees.dat:
 * what is in force and until when, cooldowns, and the militia levies Call the Banners raised.
 */
public final class DecreeData extends SavedData {
    /** One kingdom's decrees. */
    public static final class Realm {
        public final EnumMap<Decree, Long> until = new EnumMap<>(Decree.class), ready = new EnumMap<>(Decree.class);
        /** Amnesty and Call the Banners remember their city. */
        public final EnumMap<Decree, UUID> city = new EnumMap<>(Decree.class);

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            CompoundTag u = new CompoundTag(), r = new CompoundTag(), c = new CompoundTag();
            until.forEach((d, v) -> u.putLong(d.name(), v));
            ready.forEach((d, v) -> r.putLong(d.name(), v));
            city.forEach((d, v) -> c.putUUID(d.name(), v));
            t.put("until", u); t.put("ready", r); t.put("city", c);
            return t;
        }

        static Realm load(CompoundTag t) {
            Realm k = new Realm();
            CompoundTag u = t.getCompound("until"), r = t.getCompound("ready"), c = t.getCompound("city");
            for (String key : u.getAllKeys()) { Decree d = Decree.of(key); if (d != null) k.until.put(d, u.getLong(key)); }
            for (String key : r.getAllKeys()) { Decree d = Decree.of(key); if (d != null) k.ready.put(d, r.getLong(key)); }
            for (String key : c.getAllKeys()) { Decree d = Decree.of(key); if (d != null && c.hasUUID(key)) k.city.put(d, c.getUUID(key)); }
            return k;
        }
    }

    /** A militia levy: its kingdom, the city it serves, when it goes home. */
    public static final class Levy {
        public UUID kingdom, city;
        public long until;

        CompoundTag save(UUID id) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id); t.putUUID("kingdom", kingdom); t.putUUID("city", city); t.putLong("until", until);
            return t;
        }
    }

    /** Levies Call the Banners still has to raise because their city was not loaded: (kingdom, city, count, until). */
    public static final class Muster {
        public UUID kingdom, city;
        public int count;
        public long until;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("kingdom", kingdom); t.putUUID("city", city); t.putInt("count", count); t.putLong("until", until);
            return t;
        }
    }

    final Map<UUID, Realm> realms = new HashMap<>();
    final Map<UUID, Levy> levies = new LinkedHashMap<>();
    final List<Muster> musters = new ArrayList<>();
    /** Levies whose time is up but who were in unloaded ground: removed the moment they load (game time noted, dropped after 10 days). */
    final Map<UUID, Long> disband = new LinkedHashMap<>();

    public static DecreeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(DecreeData::load, DecreeData::new, "livingrealms_decrees");
    }

    public Realm realm(UUID kingdom) { return realms.computeIfAbsent(kingdom, k -> new Realm()); }

    @Nullable
    public Realm peek(UUID kingdom) { return realms.get(kingdom); }

    public Map<UUID, Levy> levies() { return Collections.unmodifiableMap(levies); }

    public Set<UUID> disbanding() { return Collections.unmodifiableSet(disband.keySet()); }

    public List<Muster> musters() { return Collections.unmodifiableList(musters); }

    @Override
    public CompoundTag save(CompoundTag t) {
        CompoundTag r = new CompoundTag();
        realms.forEach((k, v) -> r.put(k.toString(), v.save()));
        t.put("realms", r);
        ListTag l = new ListTag();
        levies.forEach((id, v) -> l.add(v.save(id)));
        t.put("levies", l);
        ListTag ms = new ListTag();
        musters.forEach(x -> ms.add(x.save()));
        t.put("musters", ms);
        CompoundTag dis = new CompoundTag();
        disband.forEach((k, v) -> dis.putLong(k.toString(), v));
        t.put("disband", dis);
        return t;
    }

    public static DecreeData load(CompoundTag t) {
        DecreeData d = new DecreeData();
        CompoundTag r = t.getCompound("realms");
        for (String k : r.getAllKeys()) try { d.realms.put(UUID.fromString(k), Realm.load(r.getCompound(k))); } catch (IllegalArgumentException ignored) {}
        for (Tag x : t.getList("levies", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            if (!c.hasUUID("id") || !c.hasUUID("kingdom") || !c.hasUUID("city")) continue;
            Levy v = new Levy();
            v.kingdom = c.getUUID("kingdom"); v.city = c.getUUID("city"); v.until = c.getLong("until");
            d.levies.put(c.getUUID("id"), v);
        }
        for (Tag x : t.getList("musters", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            if (!c.hasUUID("kingdom") || !c.hasUUID("city")) continue;
            Muster mu = new Muster();
            mu.kingdom = c.getUUID("kingdom"); mu.city = c.getUUID("city"); mu.count = Math.max(0, c.getInt("count")); mu.until = c.getLong("until");
            d.musters.add(mu);
        }
        CompoundTag dis = t.getCompound("disband");
        for (String k : dis.getAllKeys()) try { d.disband.put(UUID.fromString(k), dis.getLong(k)); } catch (IllegalArgumentException ignored) {}
        return d;
    }
}
