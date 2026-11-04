package livingrealms.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.*;

/**
 * One finished battle: an after-action summary kept per player (BattleData). Keyed by recruit UUID, never by town:
 * the town name is only a label, so reports outlive the town they were fought at.
 */
public final class BattleReport {
    /** What kind of fight it was. Ordered by precedence: a skirmish that turns into a raid is reported as the raid. */
    public enum Kind {
        SKIRMISH("Skirmish"), DRAGON("Dragon attack"), RAID_DEFENDED("Raid defended"), RAID_ATTACKED("Raid attacked"),
        SIEGE_DEFENDED("Siege withstood"), SIEGE_LAID("Siege laid");
        public final String label;
        Kind(String label) { this.label = label; }
        public static Kind of(String s) { try { return valueOf(s); } catch (Exception e) { return SKIRMISH; } }
        public static Kind max(Kind a, Kind b) { return a.ordinal() >= b.ordinal() ? a : b; }
    }

    /** Merit's weights (10 per kill, 1 per 10 damage) score the battle's most valuable recruit. */
    public static final float KILL_SCORE = 10, DAMAGE_PER_SCORE = 10;

    /** One of the owner's recruits who took part. */
    public static final class Fighter {
        public UUID id;
        public String name = "", unit = "";
        public float dealt, taken, hp, max;
        public int kills;
        public boolean fell, royal;
        /** Skill ranks, path or mastery unlocks, legendary perks and Recruits levels gained in this battle. */
        public final List<String> advances = new ArrayList<>();

        public float score() { return kills * KILL_SCORE + dealt / DAMAGE_PER_SCORE; }
        public boolean wounded() { return !fell && taken > 0; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("u", id); t.putString("n", name); t.putString("unit", unit);
            t.putFloat("dmg", dealt); t.putFloat("tk", taken); t.putFloat("hp", hp); t.putFloat("max", max);
            t.putInt("k", kills); t.putBoolean("fell", fell); t.putBoolean("royal", royal);
            ListTag adv = new ListTag();
            advances.forEach(a -> adv.add(StringTag.valueOf(a)));
            t.put("adv", adv);
            return t;
        }

        static Fighter load(CompoundTag t) {
            Fighter f = new Fighter();
            f.id = t.hasUUID("u") ? t.getUUID("u") : new UUID(0, 0);
            f.name = t.getString("n"); f.unit = t.getString("unit");
            f.dealt = Math.max(0, t.getFloat("dmg")); f.taken = Math.max(0, t.getFloat("tk"));
            f.hp = Math.max(0, t.getFloat("hp")); f.max = Math.max(0, t.getFloat("max"));
            f.kills = Math.max(0, t.getInt("k")); f.fell = t.getBoolean("fell"); f.royal = t.getBoolean("royal");
            for (Tag a : t.getList("adv", Tag.TAG_STRING)) f.advances.add(a.getAsString());
            return f;
        }
    }

    public long id, start, end;
    /** Minutes past midnight, in-game, when the first blow fell. */
    public int clock;
    public String dim = "minecraft:overworld";
    public BlockPos pos = BlockPos.ZERO;
    /** Nearest town's name, or "". */
    public String place = "";
    /** The other side, when known: the raiding or raided town, or the dragon. */
    public String foe = "";
    public Kind kind = Kind.SKIRMISH;
    /** Enemies slain, by type name, in the order first killed. */
    public final Map<String, Integer> kills = new LinkedHashMap<>();
    public final List<Fighter> fighters = new ArrayList<>();

    public long day() { return Math.max(0, start) / 24000 + 1; }
    public int seconds() { return (int) Math.max(0, (end - start) / 20); }
    public int slain() { return kills.values().stream().mapToInt(Integer::intValue).sum(); }
    public List<Fighter> fallen() { return fighters.stream().filter(f -> f.fell).toList(); }
    /** Survivors who were hurt, worst first. */
    public List<Fighter> wounded() {
        return fighters.stream().filter(Fighter::wounded).sorted(Comparator.comparingDouble((Fighter f) -> -f.taken)).toList();
    }
    public List<Fighter> advanced() { return fighters.stream().filter(f -> !f.advances.isEmpty()).toList(); }

    /** Most valuable recruit by Merit's weights; null when nobody dealt damage or killed. */
    public Fighter mvp() {
        Fighter best = null;
        for (Fighter f : fighters) if (f.score() > 0 && (best == null || f.score() > best.score())) best = f;
        return best;
    }

    public Fighter fighter(UUID id) {
        for (Fighter f : fighters) if (f.id.equals(id)) return f;
        return null;
    }

    /** "Raid defended at Ashford" / "Skirmish at 120, 64, -40". */
    public String title() { return kind.label + (place.isEmpty() ? " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() : " at " + place); }

    /** "5 slain · 1 fallen · 2 wounded". */
    public String tally() {
        int fallen = fallen().size(), wounded = wounded().size();
        return slain() + " slain" + (fallen > 0 ? " · " + fallen + " fallen" : "") + (wounded > 0 ? " · " + wounded + " wounded" : "");
    }

    /** "3 Zombie, 2 Skeleton", most first. */
    public String killList() {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(kills.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        StringJoiner j = new StringJoiner(", ");
        for (var e : list) j.add(e.getValue() + " " + e.getKey());
        return j.toString();
    }

    public static String duration(int seconds) { return seconds >= 60 ? seconds / 60 + "m " + seconds % 60 + "s" : seconds + "s"; }

    public static String clockText(int minutes) { return String.format("%02d:%02d", minutes / 60 % 24, minutes % 60); }

    // ------------------------------------------------------------------ save

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("id", id); t.putLong("t0", start); t.putLong("t1", end); t.putInt("clock", clock);
        t.putString("dim", dim); t.putLong("pos", pos.asLong());
        t.putString("place", place); t.putString("foe", foe); t.putString("kind", kind.name());
        CompoundTag k = new CompoundTag();
        ListTag order = new ListTag();
        kills.forEach((type, n) -> { k.putInt(type, n); order.add(StringTag.valueOf(type)); });
        t.put("kills", k); t.put("order", order);
        ListTag fs = new ListTag();
        fighters.forEach(f -> fs.add(f.save()));
        t.put("f", fs);
        return t;
    }

    public static BattleReport load(CompoundTag t) {
        BattleReport r = new BattleReport();
        r.id = t.getLong("id"); r.start = t.getLong("t0"); r.end = Math.max(r.start, t.getLong("t1")); r.clock = Math.floorMod(t.getInt("clock"), 1440);
        r.dim = t.getString("dim"); r.pos = BlockPos.of(t.getLong("pos"));
        r.place = t.getString("place"); r.foe = t.getString("foe"); r.kind = Kind.of(t.getString("kind"));
        CompoundTag k = t.getCompound("kills");
        for (Tag o : t.getList("order", Tag.TAG_STRING)) if (k.contains(o.getAsString())) r.kills.put(o.getAsString(), Math.max(0, k.getInt(o.getAsString())));
        for (String type : k.getAllKeys()) r.kills.putIfAbsent(type, Math.max(0, k.getInt(type)));
        for (Tag f : t.getList("f", Tag.TAG_COMPOUND)) r.fighters.add(Fighter.load((CompoundTag) f));
        return r;
    }
}
