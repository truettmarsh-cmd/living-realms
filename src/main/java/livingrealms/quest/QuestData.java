package livingrealms.quest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/** Each player's progress through the quest line, and every town's bounty board. Saved with the overworld. */
public final class QuestData extends SavedData {
    public static final String KILLS = "kills", DEFENDED = "defended", LED = "led", TAKEN = "taken", CAPITALS = "capitals", BOUNTIES = "bounties";

    public static final class Progress {
        /** Completed step ids, in the order they were completed. A step is rewarded exactly when it enters this list. */
        public final List<String> done = new ArrayList<>();
        public final Map<String, Integer> stats = new HashMap<>();
        /** Chapters whose completion has been announced. */
        public final Set<String> chapters = new LinkedHashSet<>();

        public int stat(String k) { return stats.getOrDefault(k, 0); }

        public void bump(String k, int n) { stats.merge(k, n, Integer::sum); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            ListTag d = new ListTag();
            done.forEach(s -> d.add(StringTag.valueOf(s)));
            t.put("done", d);
            CompoundTag st = new CompoundTag();
            stats.forEach(st::putInt);
            t.put("stats", st);
            ListTag c = new ListTag();
            chapters.forEach(s -> c.add(StringTag.valueOf(s)));
            t.put("chapters", c);
            return t;
        }

        static Progress load(CompoundTag t) {
            Progress p = new Progress();
            for (Tag e : t.getList("done", Tag.TAG_STRING)) if (!p.done.contains(e.getAsString())) p.done.add(e.getAsString());
            CompoundTag st = t.getCompound("stats");
            for (String k : st.getAllKeys()) p.stats.put(k, Math.max(0, st.getInt(k)));
            for (Tag e : t.getList("chapters", Tag.TAG_STRING)) p.chapters.add(e.getAsString());
            return p;
        }
    }

    public enum Kind { DELIVER, SLAY, PATROL, ESCORT }

    public static final class Bounty {
        public UUID id = UUID.randomUUID();
        public Kind kind = Kind.DELIVER;
        public Item item = Items.AIR;
        public int need = 1, progress, reward;
        public long posted, expires;
        /** Why the town wants it ("the builders are short of it"). */
        public String reason = "";
        @Nullable public UUID taker;
        public String takerName = "";
        /** Patrol waypoints and which of them have been visited (bit i). */
        public final List<BlockPos> points = new ArrayList<>();
        public int visited;
        /** Escort: the caravan to walk with, where it goes, seconds on the road with the taker near it, of all its seconds on the road. */
        @Nullable public UUID caravan;
        public String dest = "";
        public int near, road;

        public boolean done() { return progress >= need; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putString("kind", kind.name());
            t.putString("item", BuiltInRegistries.ITEM.getKey(item).toString());
            t.putInt("need", need); t.putInt("progress", progress); t.putInt("reward", reward);
            t.putLong("posted", posted); t.putLong("expires", expires);
            t.putString("reason", reason);
            if (taker != null) t.putUUID("taker", taker);
            t.putString("takerName", takerName);
            t.putLongArray("points", points.stream().mapToLong(BlockPos::asLong).toArray());
            t.putInt("visited", visited);
            if (caravan != null) { t.putUUID("caravan", caravan); t.putString("dest", dest); t.putInt("near", near); t.putInt("road", road); }
            return t;
        }

        @Nullable
        static Bounty load(CompoundTag t) {
            try {
                Bounty b = new Bounty();
                b.id = t.getUUID("id");
                b.kind = Kind.valueOf(t.getString("kind"));
                ResourceLocation rl = ResourceLocation.tryParse(t.getString("item"));
                b.item = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
                if (b.kind == Kind.DELIVER && b.item == Items.AIR) return null;
                b.need = Math.max(1, t.getInt("need")); b.progress = Math.max(0, t.getInt("progress")); b.reward = Math.max(0, t.getInt("reward"));
                b.posted = t.getLong("posted"); b.expires = t.getLong("expires");
                b.reason = t.getString("reason");
                if (t.hasUUID("taker")) b.taker = t.getUUID("taker");
                b.takerName = t.getString("takerName");
                for (long l : t.getLongArray("points")) b.points.add(BlockPos.of(l));
                b.visited = t.getInt("visited");
                if (t.hasUUID("caravan")) { b.caravan = t.getUUID("caravan"); b.dest = t.getString("dest"); b.near = t.getInt("near"); b.road = t.getInt("road"); }
                if (b.kind == Kind.ESCORT && b.caravan == null) return null;
                return b;
            } catch (Exception e) {
                return null;
            }
        }
    }

    public static final class Board {
        public final List<Bounty> bounties = new ArrayList<>();
        /** Game time the town may post its next bounty. */
        public long nextPost;
    }

    public final Map<UUID, Progress> players = new HashMap<>();
    public final Map<UUID, Board> boards = new HashMap<>();

    public static QuestData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(QuestData::load, QuestData::new, "livingrealms_quests");
    }

    public Progress of(UUID player) { return players.computeIfAbsent(player, k -> new Progress()); }

    public Board board(UUID town) { return boards.computeIfAbsent(town, k -> new Board()); }

    /** Every bounty a player has taken, with its town. */
    public List<Map.Entry<UUID, Bounty>> taken(UUID player) {
        List<Map.Entry<UUID, Bounty>> out = new ArrayList<>();
        boards.forEach((town, b) -> b.bounties.forEach(x -> { if (player.equals(x.taker)) out.add(Map.entry(town, x)); }));
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag pl = new CompoundTag();
        players.forEach((k, v) -> pl.put(k.toString(), v.save()));
        tag.put("players", pl);
        CompoundTag bo = new CompoundTag();
        boards.forEach((k, v) -> {
            CompoundTag b = new CompoundTag();
            ListTag l = new ListTag();
            v.bounties.forEach(x -> l.add(x.save()));
            b.put("bounties", l);
            b.putLong("nextPost", v.nextPost);
            bo.put(k.toString(), b);
        });
        tag.put("boards", bo);
        return tag;
    }

    public static QuestData load(CompoundTag tag) {
        QuestData d = new QuestData();
        CompoundTag pl = tag.getCompound("players");
        for (String k : pl.getAllKeys()) try { d.players.put(UUID.fromString(k), Progress.load(pl.getCompound(k))); } catch (IllegalArgumentException ignored) {}
        CompoundTag bo = tag.getCompound("boards");
        for (String k : bo.getAllKeys()) try {
            Board b = new Board();
            CompoundTag bt = bo.getCompound(k);
            for (Tag e : bt.getList("bounties", Tag.TAG_COMPOUND)) { Bounty x = Bounty.load((CompoundTag) e); if (x != null) b.bounties.add(x); }
            b.nextPost = bt.getLong("nextPost");
            d.boards.put(UUID.fromString(k), b);
        } catch (IllegalArgumentException ignored) {}
        return d;
    }

    /** Progress changes in many small steps; save it whenever the world saves. */
    @Override
    public boolean isDirty() { return true; }
}
