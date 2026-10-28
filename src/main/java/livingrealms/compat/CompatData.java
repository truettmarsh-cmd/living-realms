package livingrealms.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** What the integrations remember: titles they applied, engines on campaign and gear recovered per town. */
final class CompatData extends SavedData {
    record Engine(UUID engine, UUID crew, String item) {}
    final Map<String, String> feudal = new HashMap<>(), ranks = new HashMap<>();
    final Map<String, List<Engine>> engines = new HashMap<>();
    final Map<UUID, Integer> salvaged = new HashMap<>();

    static CompatData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(CompatData::load, CompatData::new, "livingrealms_compat");
    }

    static CompatData load(CompoundTag t) {
        CompatData d = new CompatData();
        CompoundTag f = t.getCompound("feudal"), r = t.getCompound("ranks"), e = t.getCompound("engines"), s = t.getCompound("salvaged");
        f.getAllKeys().forEach(k -> d.feudal.put(k, f.getString(k)));
        r.getAllKeys().forEach(k -> d.ranks.put(k, r.getString(k)));
        for (String k : e.getAllKeys()) for (Tag x : e.getList(k, Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            d.engines.computeIfAbsent(k, i -> new ArrayList<>()).add(new Engine(c.getUUID("e"), c.getUUID("c"), c.getString("item")));
        }
        s.getAllKeys().forEach(k -> d.salvaged.put(UUID.fromString(k), s.getInt(k)));
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag t) {
        CompoundTag f = new CompoundTag(), r = new CompoundTag(), e = new CompoundTag(), s = new CompoundTag();
        feudal.forEach(f::putString);
        ranks.forEach(r::putString);
        engines.forEach((k, list) -> {
            ListTag l = new ListTag();
            for (Engine x : list) { CompoundTag c = new CompoundTag(); c.putUUID("e", x.engine); c.putUUID("c", x.crew); c.putString("item", x.item); l.add(c); }
            e.put(k, l);
        });
        salvaged.forEach((k, n) -> s.putInt(k.toString(), n));
        t.put("feudal", f); t.put("ranks", r); t.put("engines", e); t.put("salvaged", s);
        return t;
    }

    @Override
    public boolean isDirty() { return true; }
}
