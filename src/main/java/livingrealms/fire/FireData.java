package livingrealms.fire;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;


/**
 * Fires still burning when the world is saved (docs/fires.md), kept with the overworld as data/livingrealms_fires.dat:
 * on the next start each is watched again and its brigade re-formed, so a restart neither forgets a blaze nor lets it
 * leave the town. Nothing is added to the settlement save; a missing or damaged file loads empty.
 */
public final class FireData extends SavedData {
    public static FireData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FireData::load, FireData::new, "livingrealms_fires");
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("fires", Fires.saveAll());
        return tag;
    }

    public static FireData load(CompoundTag tag) {
        Fires.loadAll(tag.getList("fires", Tag.TAG_COMPOUND));
        return new FireData();
    }

    static CompoundTag tag(Fires.Fire f) {
        CompoundTag t = new CompoundTag();
        t.putUUID("town", f.town);
        t.putString("cause", f.cause);
        t.putLong("started", f.started);
        BoundingBox z = f.zone;
        t.putIntArray("zone", new int[]{z.minX(), z.minY(), z.minZ(), z.maxX(), z.maxY(), z.maxZ()});
        t.putIntArray("counts", new int[]{f.peak, f.byCrew, f.byTown, f.contained, f.scans, f.repairs});
        t.putIntArray("touched", f.touched.stream().mapToInt(Integer::intValue).toArray());
        return t;
    }

    static Fires.Fire fire(CompoundTag t) {
        if (!t.hasUUID("town")) return null;
        int[] z = t.getIntArray("zone");
        if (z.length != 6) return null;
        Fires.Fire f = new Fires.Fire(t.getUUID("town"), t.getString("cause"), t.getLong("started"), new BoundingBox(z[0], z[1], z[2], z[3], z[4], z[5]));
        int[] c = t.getIntArray("counts");
        if (c.length == 6) { f.peak = c[0]; f.byCrew = c[1]; f.byTown = c[2]; f.contained = c[3]; f.scans = c[4]; f.repairs = c[5]; }
        for (int id : t.getIntArray("touched")) f.touched.add(id);
        return f;
    }

    /** Burning fires change every half second; save them whenever the world saves. */
    @Override
    public boolean isDirty() { return true; }

}
