package livingrealms.supply;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/**
 * Resupply settings by recruit and player UUID, kept apart from any Settlement so they outlive Living Realms towns.
 * A recruit's record also remembers what it last wielded and wore, so a broken piece is replaced in kind.
 */
public final class SupplyData extends SavedData {
    public static final class Rec {
        public boolean auto = true;
        /** Item ids last seen in MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET ("" = nothing yet). */
        public final String[] last = {"", "", "", "", "", ""};
        /** Durability left (fraction) of each of those at the last check: an empty slot only counts as broken from low wear. */
        public final float[] wear = {1, 1, 1, 1, 1, 1};
        /** Game time of the last check; a record unseen for FORGET_TICKS is dropped. */
        public long seen;
        // the last check, for the Recruit Command screen and the garrison card; not saved
        public transient List<String> shortages = List.of();
        public transient String note = "";
        public transient long noteAt;
        /** The owner was already warned about the shortage going on now. */
        public transient boolean warned;

        boolean plain() { return auto && Arrays.stream(last).allMatch(String::isEmpty); }
    }

    /** Records of recruits not checked (loaded) for this long are dropped: 60 in-game days. */
    public static final long FORGET_TICKS = 60 * 24000L;

    final Map<UUID, Rec> recruits = new HashMap<>();
    final Set<UUID> ownerInventory = new HashSet<>();

    public static SupplyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SupplyData::load, SupplyData::new, "livingrealms_supply");
    }

    public Rec rec(UUID recruit) { return recruits.computeIfAbsent(recruit, k -> new Rec()); }

    public Rec peek(UUID recruit) { return recruits.get(recruit); }

    public boolean auto(UUID recruit) { Rec r = recruits.get(recruit); return r == null || r.auto; }

    public void setAuto(UUID recruit, boolean on) { rec(recruit).auto = on; setDirty(); }

    /** The per-player switch: may this player's recruits take from their inventory? Off unless they turned it on. */
    public boolean allowsInventory(UUID player) { return ownerInventory.contains(player); }

    public void setAllowsInventory(UUID player, boolean on) {
        if (on) ownerInventory.add(player); else ownerInventory.remove(player);
        setDirty();
    }

    public void forget(UUID recruit) { if (recruits.remove(recruit) != null) setDirty(); }

    /** A recruit was replaced by a new entity (a promotion): its settings and remembered gear follow it. */
    public void move(UUID from, UUID to) {
        if (from.equals(to)) return;
        Rec r = recruits.remove(from);
        if (r != null) { recruits.put(to, r); setDirty(); }
    }

    /** Drops records not seen for FORGET_TICKS; records from before "seen" was kept start counting now. Returns how many went. */
    public int prune(long now) {
        int before = recruits.size();
        for (Rec r : recruits.values()) if (r.seen == 0 || r.seen > now) r.seen = now;
        recruits.values().removeIf(r -> now - r.seen > FORGET_TICKS);
        int gone = before - recruits.size();
        if (gone > 0) setDirty();
        return gone;
    }

    public static SupplyData load(CompoundTag t) {
        SupplyData d = new SupplyData();
        CompoundTag r = t.getCompound("recruits");
        for (String k : r.getAllKeys()) {
            CompoundTag c = r.getCompound(k);
            Rec rec = new Rec();
            rec.auto = !c.contains("auto") || c.getBoolean("auto");
            ListTag last = c.getList("last", Tag.TAG_STRING);
            for (int i = 0; i < Math.min(last.size(), rec.last.length); i++) rec.last[i] = last.getString(i);
            ListTag wear = c.getList("wear", Tag.TAG_FLOAT);
            for (int i = 0; i < Math.min(wear.size(), rec.wear.length); i++) rec.wear[i] = wear.getFloat(i);
            rec.seen = c.getLong("seen");
            try { d.recruits.put(UUID.fromString(k), rec); } catch (IllegalArgumentException ignored) {}
        }
        for (Tag x : t.getList("ownerInventory", Tag.TAG_INT_ARRAY)) d.ownerInventory.add(net.minecraft.nbt.NbtUtils.loadUUID(x));
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag t) {
        CompoundTag r = new CompoundTag();
        recruits.forEach((k, rec) -> {
            if (rec.plain()) return;
            CompoundTag c = new CompoundTag();
            c.putBoolean("auto", rec.auto);
            ListTag last = new ListTag();
            for (String s : rec.last) last.add(StringTag.valueOf(s));
            c.put("last", last);
            ListTag wear = new ListTag();
            for (float f : rec.wear) wear.add(net.minecraft.nbt.FloatTag.valueOf(f));
            c.put("wear", wear);
            c.putLong("seen", rec.seen);
            r.put(k.toString(), c);
        });
        t.put("recruits", r);
        ListTag owners = new ListTag();
        for (UUID u : ownerInventory) owners.add(net.minecraft.nbt.NbtUtils.createUUID(u));
        t.put("ownerInventory", owners);
        return t;
    }
}
