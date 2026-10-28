package livingrealms.settlement;

import livingrealms.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/** All settlements of the world, saved with the overworld. */
public final class SettlementManager extends SavedData {
    private final Map<UUID, Settlement> byId = new LinkedHashMap<>();
    public final List<Raids.Raid> raids = new ArrayList<>();
    public long nextRaidCheck;
    /** Village Expansion: each player kingdom's Recruits faction, and truces (faction pair -> game time) after making peace. */
    public final Map<UUID, String> realms = new HashMap<>();
    public final Map<String, Long> truces = new HashMap<>();
    /** What each kingdom remembers of recent battles (Strategy). */
    public final Map<UUID, Strategy.Memory> memory = new HashMap<>();
    /** Wars, truces, tribute and the loyalty of conquered cities (Statecraft). */
    public final Statecraft state = new Statecraft();

    public static SettlementManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SettlementManager::load, SettlementManager::new, "livingrealms_settlements");
    }

    public Collection<Settlement> all() { return byId.values(); }

    @Nullable
    public Settlement get(UUID id) { return byId.get(id); }

    public void add(Settlement s) {
        byId.put(s.id, s);
        Kingdoms.normalize(this);
        setDirty();
    }

    public void remove(Settlement s) {
        byId.remove(s.id);
        Kingdoms.normalize(this);
        setDirty();
    }

    /** The settlement whose territory contains pos. */
    @Nullable
    public Settlement at(ResourceKey<Level> dim, BlockPos pos) {
        Settlement best = null;
        long bestD = Long.MAX_VALUE;
        for (Settlement s : byId.values()) {
            if (!s.dim.equals(dim) || !s.contains(pos)) continue;
            long d = s.center.distSqr(pos) > 0 ? (long) s.center.distSqr(pos) : 0;
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    @Nullable
    public Settlement nearest(ResourceKey<Level> dim, BlockPos pos, int maxDist) {
        Settlement best = null;
        double bestD = (double) maxDist * maxDist;
        for (Settlement s : byId.values()) {
            if (!s.dim.equals(dim)) continue;
            double d = s.center.distSqr(pos);
            if (d <= bestD) { bestD = d; best = s; }
        }
        return best;
    }

    public List<Settlement> ownedBy(UUID player) {
        List<Settlement> out = new ArrayList<>();
        for (Settlement s : byId.values()) if (player.equals(s.owner)) out.add(s);
        return out;
    }

    /** Is pos far enough from every other settlement to found a new one? */
    public boolean roomFor(ResourceKey<Level> dim, BlockPos pos) {
        return nearest(dim, pos, Config.AI_MIN_DISTANCE.get()) == null;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag l = new ListTag();
        byId.values().forEach(s -> l.add(s.save()));
        tag.put("settlements", l);
        ListTag battles = new ListTag();
        raids.forEach(r -> battles.add(r.save()));
        tag.put("raids", battles);
        tag.putLong("nextRaidCheck", nextRaidCheck);
        if (!realms.isEmpty()) {
            CompoundTag r = new CompoundTag();
            realms.forEach((k, v) -> r.putString(k.toString(), v));
            tag.put("vxRealms", r);
        }
        if (!truces.isEmpty()) {
            CompoundTag r = new CompoundTag();
            truces.forEach(r::putLong);
            tag.put("vxTruces", r);
        }
        if (!memory.isEmpty()) {
            CompoundTag r = new CompoundTag();
            memory.forEach((k, v) -> r.put(k.toString(), v.save()));
            tag.put("warMemory", r);
        }
        if (!state.isEmpty()) tag.put("statecraft", state.save());
        return tag;
    }

    static SettlementManager load(CompoundTag tag) {
        SettlementManager m = new SettlementManager();
        for (Tag t : tag.getList("settlements", Tag.TAG_COMPOUND)) {
            Settlement s = Settlement.load((CompoundTag) t);
            m.byId.put(s.id, s);
        }
        for (Tag t : tag.getList("raids", Tag.TAG_COMPOUND)) m.raids.add(Raids.Raid.load((CompoundTag) t));
        Kingdoms.normalize(m);
        m.nextRaidCheck = tag.getLong("nextRaidCheck");
        CompoundTag r = tag.getCompound("vxRealms");
        for (String k : r.getAllKeys()) try { m.realms.put(UUID.fromString(k), r.getString(k)); } catch (IllegalArgumentException ignored) {}
        CompoundTag tr = tag.getCompound("vxTruces");
        for (String k : tr.getAllKeys()) m.truces.put(k, tr.getLong(k));
        CompoundTag mem = tag.getCompound("warMemory");
        for (String k : mem.getAllKeys()) try { m.memory.put(UUID.fromString(k), Strategy.Memory.load(mem.getCompound(k))); } catch (IllegalArgumentException ignored) {}
        m.state.load(tag.getCompound("statecraft"));
        return m;
    }

    /** Settlements change constantly; save them whenever the world saves. */
    @Override
    public boolean isDirty() { return true; }
}
