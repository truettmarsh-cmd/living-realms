package livingrealms.dragon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Each town's roost: its dragon (or egg) and what was last seen of it. Saved apart from the settlements as livingrealms_dragons. */
public final class DragonData extends SavedData {
    public static final long UNSET = Long.MIN_VALUE;

    public static final class Roost {
        @Nullable public UUID dragon;
        /** Hatched here (the dragon belongs to the town and follows it to a new ruler) or adopted (it stays its owner's). */
        public boolean hatched;
        public String name = "", element = "", state = "";
        public int stage, ageDays;
        public float health, maxHealth;
        /** An egg in the embers: its element and colour (-1: none) and when it hatches. */
        public int eggElement = -1, eggVariant;
        public long hatchAt;
        /** Last game day the keepers fed it; when an NPC roost may get its next egg; the roost's completion was chronicled. */
        public long fedDay, nextEgg = UNSET;
        public boolean announced;
        public int hatchings, sorties;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            if (dragon != null) t.putUUID("dragon", dragon);
            t.putBoolean("hatched", hatched);
            t.putString("name", name); t.putString("element", element); t.putString("state", state);
            t.putInt("stage", stage); t.putInt("age", ageDays); t.putFloat("hp", health); t.putFloat("max", maxHealth);
            t.putInt("eggElement", eggElement); t.putInt("eggVariant", eggVariant); t.putLong("hatchAt", hatchAt);
            t.putLong("fed", fedDay); t.putLong("nextEgg", nextEgg); t.putBoolean("announced", announced);
            t.putInt("hatchings", hatchings); t.putInt("sorties", sorties);
            return t;
        }

        static Roost load(CompoundTag t) {
            Roost r = new Roost();
            if (t.hasUUID("dragon")) r.dragon = t.getUUID("dragon");
            r.hatched = t.getBoolean("hatched");
            r.name = t.getString("name"); r.element = t.getString("element"); r.state = t.getString("state");
            r.stage = t.getInt("stage"); r.ageDays = t.getInt("age"); r.health = t.getFloat("hp"); r.maxHealth = t.getFloat("max");
            r.eggElement = t.contains("eggElement") ? t.getInt("eggElement") : -1;
            r.eggVariant = t.getInt("eggVariant"); r.hatchAt = t.getLong("hatchAt");
            r.fedDay = t.getLong("fed"); r.nextEgg = t.contains("nextEgg") ? t.getLong("nextEgg") : UNSET; r.announced = t.getBoolean("announced");
            r.hatchings = t.getInt("hatchings"); r.sorties = t.getInt("sorties");
            return r;
        }

        public boolean hasDragon() { return dragon != null; }
        public boolean incubating() { return dragon == null && eggElement >= 0; }

        void clearDragon() { dragon = null; hatched = false; name = ""; element = ""; stage = 0; ageDays = 0; health = 0; maxHealth = 0; }
    }

    public final Map<UUID, Roost> roosts = new HashMap<>();

    public static DragonData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(DragonData::load, DragonData::new, "livingrealms_dragons");
    }

    public static DragonData load(CompoundTag t) {
        DragonData d = new DragonData();
        CompoundTag r = t.getCompound("roosts");
        for (String k : r.getAllKeys()) {
            try { d.roosts.put(UUID.fromString(k), Roost.load(r.getCompound(k))); } catch (IllegalArgumentException ignored) {}
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag t) {
        CompoundTag r = new CompoundTag();
        roosts.forEach((k, v) -> r.put(k.toString(), v.save()));
        t.put("roosts", r);
        return t;
    }

    @Override
    public boolean isDirty() { return true; }
}
