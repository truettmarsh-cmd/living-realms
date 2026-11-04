package livingrealms.battle;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/** Each player's last battle reports, newest first. Saved with the overworld, apart from any town. */
public final class BattleData extends SavedData {
    public static final int KEEP = 20;
    private final Map<UUID, List<BattleReport>> players = new HashMap<>();
    long nextId = 1;

    public static BattleData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(BattleData::load, BattleData::new, "livingrealms_battles");
    }

    /** The player's reports, newest first (read-only). */
    public List<BattleReport> of(UUID player) { return Collections.unmodifiableList(players.getOrDefault(player, List.of())); }

    public void add(UUID player, BattleReport r) {
        r.id = nextId++;
        List<BattleReport> list = players.computeIfAbsent(player, k -> new ArrayList<>());
        list.add(0, r);
        while (list.size() > KEEP) list.remove(list.size() - 1);
        setDirty();
    }

    public void clear(UUID player) { if (players.remove(player) != null) setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag pl = new CompoundTag();
        players.forEach((k, v) -> {
            ListTag list = new ListTag();
            v.forEach(r -> list.add(r.save()));
            pl.put(k.toString(), list);
        });
        tag.put("players", pl);
        tag.putLong("next", nextId);
        return tag;
    }

    public static BattleData load(CompoundTag tag) {
        BattleData d = new BattleData();
        CompoundTag pl = tag.getCompound("players");
        for (String k : pl.getAllKeys()) {
            try {
                List<BattleReport> list = new ArrayList<>();
                for (Tag t : pl.getList(k, Tag.TAG_COMPOUND)) if (list.size() < KEEP) list.add(BattleReport.load((CompoundTag) t));
                d.players.put(UUID.fromString(k), list);
            } catch (IllegalArgumentException ignored) {}
        }
        d.nextId = Math.max(1, tag.getLong("next"));
        return d;
    }
}
