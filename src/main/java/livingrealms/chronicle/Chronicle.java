package livingrealms.chronicle;

import livingrealms.Config;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.settlement.CapitalCastles;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Statecraft;
import livingrealms.settlement.plan.Walls;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A town's history: a capped, dated list of notable events in short prose (docs/chronicle.md). Other features add
 * entries with {@link #record(Settlement, Kind, String...)}; the arguments of each kind are listed on the kind.
 * Quiet stretches between events are folded into one summary line instead of logging every cottage.
 */
public final class Chronicle {
    /** What happened. {0}, {1}, {2} are the record() arguments, {town} the town's name. */
    public enum Kind {
        /** (founder or "") */
        FOUNDING("Founding", "{town} was founded{0? by }{0}, a handful of tents round a charter stone.",
                "{town} was founded{0? by }{0}. The first fire was lit and the first well dug.",
                "{town} began with a charter stone{0? planted by }{0} and a ring of tents."),
        /** (new rank label) */
        RANK("Rank", "{town} rose to the rank of {0}, and the bells were rung all day.",
                "By charter {town} became a {0}. The townsfolk feasted.", "{town} was proclaimed a {0}; criers carried the news to every farm."),
        /** (building name) */
        BUILDING("Building", "The {0} was finished, and the townsfolk came out to admire it.",
                "Builders laid the last stone of the {0}.", "{town} gained its {0}; the masons drank to it that night."),
        /** () the wall circuit is complete; {0} is "" for the first wall, "second" for the next */
        WALLS("Walls", "The walls of {town} were closed at last; the gates were hung and the watch set.",
                "The last stretch of wall was finished, and {town} slept behind stone.", "Masons finished the walls of {town}; travellers now pass under its gatehouses."),
        /** () */
        CASTLE("Castle", "The Capital Castle was completed, its towers seen for miles.", "The great castle of {town} was finished, the seat of a kingdom."),
        /** (attacking town) the town held */
        RAID_REPELLED("Raid", "Raiders from {0} came over the fields, and were driven off.",
                "{0} sent its soldiers against {town}; the garrison held.", "An army from {0} attacked {town} and was thrown back from the gates."),
        /** (attacking town) the town fell */
        RAID_LOST("Raid", "{town} fell to the soldiers of {0}.", "Raiders from {0} took the town square of {town} and held it.",
                "The defenders of {town} were overwhelmed by {0}."),
        /** (target town) our army took it */
        RAID_WON("Raid", "Soldiers of {town} marched on {0} and took it.", "The army of {town} stormed {0} and raised its banner there."),
        /** (target town) our army was beaten */
        RAID_FAILED("Raid", "Soldiers of {town} marched on {0}, and came home beaten.", "The raid on {0} failed; {town} counted its dead."),
        /** (attacker, outcome) for sieges, e.g. ("Ashford", "the walls held") */
        SIEGE("Siege", "{0} laid siege to {town}; {1}.", "The siege of {town} by {0}: {1}."),
        /** (new ruler) the town changed hands */
        CONQUERED("Ruler", "{town} passed into the hands of {0}.", "The banners of {0} now fly over {town}.", "{town} bent the knee to {0}."),
        /** (former ruler) */
        REBELLION("Rebellion", "{town} rose in rebellion against {0}.", "The people of {town} threw off the rule of {0}."),
        /** (enemy kingdom) */
        WAR("War", "War was declared with {0}.", "The kingdom went to war with {0}; the smiths worked through the night.",
                "Heralds brought word of war with {0}."),
        /** (enemy kingdom, terms or "") */
        PEACE("Peace", "Peace was made with {0}{1?: }{1}.", "The war with {0} ended{1? on these terms: }{1}.", "Envoys signed a peace with {0}{1?: }{1}."),
        /** (soldier name, title) */
        HERO("Hero", "{0} was raised to {1}, and their name is spoken in the taverns.", "For valour, {0} was made {1}.",
                "{0} earned the rank of {1}."),
        /** (soldier name, title, kills or "") */
        FALLEN("Fallen", "{1} {0} fell in battle{2?, after }{2}{2? kills}. The town mourned.", "{town} lost {1} {0}{2?, slayer of }{2}{2? foes}.",
                "{1} {0} died defending the realm{2?, with }{2}{2? kills to their name}."),
        /** () became the seat of the kingdom */
        CAPITAL("Capital", "{town} was made the capital of the kingdom.", "The court moved to {town}, now the seat of the realm."),
        /** (new ruler) a ruler changed without conquest */
        RULER("Ruler", "{0} took up the rule of {town}.", "{town} came under {0}."),
        /** (text) composed by the fire system */
        FIRE("Fire", "{0}"),
        /** (text) */
        DISASTER("Disaster", "{0}"),
        /** (text) for quests */
        QUEST("Quest", "{0}"),
        /** (text) townsfolk: mayors, captains, notable weddings and deaths (livingrealms.citizen) */
        PEOPLE("People", "{0}"),
        /** (text) for trade */
        TRADE("Trade", "{0}"),
        /** (text) summary of a quiet stretch, written by the chronicle itself */
        GROWTH("Growth", "{0}"),
        /** (text) anything else, verbatim */
        NOTE("Note", "{0}");

        public final String label;
        final String[] templates;
        Kind(String label, String... templates) { this.label = label; this.templates = templates; }
        static Kind of(String s) { try { return valueOf(s); } catch (Exception e) { return NOTE; } }
    }

    public record Entry(long day, Kind kind, String text) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("d", day); t.putString("k", kind.name()); t.putString("t", text);
            return t;
        }
        static Entry load(CompoundTag t) { return new Entry(Math.max(1, t.getLong("d")), Kind.of(t.getString("k")), t.getString("t")); }
    }

    /** One town's chronicle, saved with the settlement. Old saves load empty and are started on the next tick. */
    public static final class Log {
        public final List<Entry> entries = new ArrayList<>();
        /** Baseline for detecting changes; -1 until the first look at the town. */
        int lastTier = -1, wallPieces, quietBuilt, quietBuildings = -1, quietPop = -1;
        long quietFrom;
        String lastKingdom = "";
        boolean capital;
        final Set<Integer> seen = new HashSet<>();

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            ListTag l = new ListTag();
            entries.forEach(e -> l.add(e.save()));
            t.put("entries", l);
            t.putInt("tier", lastTier); t.putInt("walls", wallPieces); t.putInt("qBuilt", quietBuilt);
            t.putInt("qBuildings", quietBuildings); t.putInt("qPop", quietPop); t.putLong("qFrom", quietFrom);
            t.putString("kingdom", lastKingdom); t.putBoolean("capital", capital);
            t.putIntArray("seen", seen.stream().mapToInt(Integer::intValue).toArray());
            return t;
        }

        public void load(CompoundTag t) {
            entries.clear();
            for (Tag e : t.getList("entries", Tag.TAG_COMPOUND)) entries.add(Entry.load((CompoundTag) e));
            lastTier = t.contains("tier") ? t.getInt("tier") : -1;
            wallPieces = t.getInt("walls"); quietBuilt = t.getInt("qBuilt");
            quietBuildings = t.contains("qBuildings") ? t.getInt("qBuildings") : -1;
            quietPop = t.contains("qPop") ? t.getInt("qPop") : -1;
            quietFrom = t.getLong("qFrom");
            lastKingdom = t.getString("kingdom"); capital = t.getBoolean("capital");
            seen.clear();
            for (int i : t.getIntArray("seen")) seen.add(i);
        }
    }

    /** Days a quiet stretch must last before it gets its own summary line. */
    public static final int QUIET_DAYS = 12, MIN_SUMMARY_DAYS = 4;

    public static int cap() { return Config.CHRONICLE_MAX.get(); }

    public static int yearDays() { return Config.CHRONICLE_YEAR.get(); }

    /** The chronicle's calendar: day 1 is the world's first day; game time, so /time set never rewinds history. */
    public static long today() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? 1 : server.overworld().getGameTime() / 24000 + 1;
    }

    public static long dayOf(long gameTime) { return Math.max(0, gameTime) / 24000 + 1; }

    public static int year(long day) { return (int) ((Math.max(1, day) - 1) / yearDays()) + 1; }

    public static String date(long day) { return "Day " + day + " · Year " + year(day); }

    // ------------------------------------------------------------------ recording

    /** Adds an entry for today. Arguments fill the kind's template (see each {@link Kind}). */
    public static Entry record(Settlement s, Kind kind, String... args) { return recordOn(s, today(), kind, args); }

    /** Adds an entry on the given chronicle day. */
    public static Entry recordOn(Settlement s, long day, Kind kind, String... args) {
        Log log = s.chronicle;
        if (kind != Kind.GROWTH && kind != Kind.FOUNDING) summarise(s, day, false);
        String text = compose(s, kind, log.entries.size() + day * 31, args);
        Entry e = new Entry(Math.max(1, day), kind, text);
        log.entries.add(e);
        // the next quiet stretch starts after this event
        if (kind != Kind.GROWTH && log.quietBuildings >= 0) log.quietFrom = Math.max(1, day);
        trim(log);
        s.dirty = true;
        return e;
    }

    /** Records the same event in every city of a kingdom. */
    public static void kingdom(SettlementManager m, UUID kingdom, Kind kind, String... args) {
        for (Settlement s : m.all()) if (s.kingdomId().equals(kingdom)) record(s, kind, args);
    }

    /** Fills a template: {0}.. arguments, {town}, and {0?text} which shows text only when argument 0 is not empty. */
    static String compose(Settlement s, Kind kind, long seed, String... args) {
        String tpl = kind.templates[(int) Math.floorMod(seed * 0x9E3779B97F4A7C15L + s.id.getLeastSignificantBits(), (long) kind.templates.length)];
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tpl.length(); i++) {
            char c = tpl.charAt(i);
            int close = c == '{' ? tpl.indexOf('}', i) : -1;
            if (close < 0) { out.append(c); continue; }
            String key = tpl.substring(i + 1, close);
            i = close;
            if (key.equals("town")) { out.append(s.name.isEmpty() ? "the town" : s.name); continue; }
            int q = key.indexOf('?');
            int n = key.charAt(0) - '0';
            String arg = n >= 0 && n < args.length && args[n] != null ? args[n].trim() : "";
            if (q > 0) { if (!arg.isEmpty()) out.append(key.substring(q + 1)); }
            else out.append(arg);
        }
        String text = out.toString().replace("  ", " ").trim();
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Keeps the newest entries, never the founding. */
    static void trim(Log log) {
        int cap = Math.max(8, cap());
        while (log.entries.size() > cap) {
            int drop = log.entries.get(0).kind == Kind.FOUNDING && log.entries.size() > 1 ? 1 : 0;
            log.entries.remove(drop);
        }
    }

    // ------------------------------------------------------------------ hooks for existing events

    /** A raid ended (Raids.finish). Records both sides; a raid cut short by a change of rulers is left out. */
    public static void raidEnded(@Nullable Settlement source, @Nullable Settlement target, boolean taken, String message) {
        if (message != null && message.startsWith("Raid ended because")) return;
        if (target != null) {
            record(target, taken ? Kind.RAID_LOST : Kind.RAID_REPELLED, source == null ? "a rival" : source.name);
            if (taken) target.chronicle.lastKingdom = target.kingdomId().toString();
        }
        if (source != null) record(source, taken ? Kind.RAID_WON : Kind.RAID_FAILED, target == null ? "a rival" : target.name);
    }

    /** Wars opened or ended between two kingdoms (Wars, Realms). */
    public static void war(SettlementManager m, UUID a, UUID b) {
        kingdom(m, a, Kind.WAR, Statecraft.name(m, b));
        kingdom(m, b, Kind.WAR, Statecraft.name(m, a));
    }

    public static void peace(SettlementManager m, UUID a, UUID b, String terms) {
        kingdom(m, a, Kind.PEACE, Statecraft.name(m, b), terms);
        kingdom(m, b, Kind.PEACE, Statecraft.name(m, a), terms);
    }

    public static void rebellion(Settlement s, String formerRuler) {
        record(s, Kind.REBELLION, formerRuler);
        s.chronicle.lastKingdom = s.kingdomId().toString();
    }

    /** A soldier reached a high title (SoldierRanks): Elite and Champion are chronicled. */
    public static void promoted(Settlement s, Settlement.Soldier so, int titleIndex, String title) {
        if (titleIndex >= 4) record(s, Kind.HERO, so.name, title);
    }

    /** Veterans, killers and the decorated are remembered when they fall. */
    public static boolean notable(Settlement.Soldier so) {
        return livingrealms.military.SoldierRanks.titleIndex(livingrealms.military.SoldierRanks.rank(so)) >= 2 || so.kills >= 10
                || !so.legendary.isEmpty() || so.royalGuard || so.merit.merit >= livingrealms.settlement.Merit.THRESHOLD;
    }

    public static void fallen(Settlement s, Settlement.Soldier so) {
        if (!notable(so)) return;
        String title = so.royalGuard ? "Royal Guard" : livingrealms.military.SoldierRanks.title(livingrealms.military.SoldierRanks.rank(so));
        record(s, Kind.FALLEN, so.name, title, so.kills > 0 ? String.valueOf(so.kills) : "");
    }

    // ------------------------------------------------------------------ watching the town

    /** Called about once a second: founding, rank, buildings, walls, rulers and quiet stretches, found by comparison. */
    public static void tick(MinecraftServer server, SettlementManager m, Settlement s, long day) {
        Log log = s.chronicle;
        if (log.entries.isEmpty()) {
            long founded = dayOf(s.founded);
            recordOn(s, founded, Kind.FOUNDING, s.isAi() ? "" : s.ownerName);
        }
        if (log.lastTier < 0) { baseline(m, s, day); return; }
        int tier = s.tier.number();
        if (tier > log.lastTier) recordOn(s, day, Kind.RANK, s.tier.label);
        log.lastTier = tier;
        String kingdom = s.kingdomId().toString();
        if (!kingdom.equals(log.lastKingdom)) {
            if (!log.lastKingdom.isEmpty()) recordOn(s, day, Kind.CONQUERED, s.isAi() ? Statecraft.name(m, s.kingdomId()) : s.ownerName);
            log.lastKingdom = kingdom;
        }
        if (s.capital != log.capital) {
            if (s.capital && cities(m, s) > 1) recordOn(s, day, Kind.CAPITAL);
            log.capital = s.capital;
        }
        buildings(s, day);
        if (day - log.quietFrom >= QUIET_DAYS) summarise(s, day, true);
    }

    static int cities(SettlementManager m, Settlement s) {
        int n = 0;
        for (Settlement t : m.all()) if (t.kingdomId().equals(s.kingdomId())) n++;
        return n;
    }

    /** First look at a town (new, or from an older save): remember how it stands without writing anything. */
    static void baseline(SettlementManager m, Settlement s, long day) {
        Log log = s.chronicle;
        log.lastTier = s.tier.number();
        log.lastKingdom = s.kingdomId().toString();
        log.capital = s.capital;
        log.seen.clear();
        for (Plot p : s.plots) if (p.standing()) log.seen.add(p.id);
        log.wallPieces = wallsComplete(s);
        log.quietFrom = day;
        log.quietBuilt = 0;
        log.quietBuildings = s.d.buildings;
        log.quietPop = s.population;
        s.dirty = true;
    }

    /** Finished buildings: the notable ones get an entry, the rest count towards the next quiet-stretch summary. */
    static void buildings(Settlement s, long day) {
        Log log = s.chronicle;
        Set<Category> had = new HashSet<>();
        for (Plot p : s.plots) if (log.seen.contains(p.id)) {
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t != null) had.add(t.category);
        }
        for (Plot p : s.plots) {
            if (!p.standing() || p.deferred && p.state != Plot.State.BUILT || !log.seen.add(p.id)) continue;
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t == null || Walls.isWallPiece(p) || t.category == Category.ROAD) continue;
            if (t.id.equals(CapitalCastles.ID)) recordOn(s, day, Kind.CASTLE);
            else if (notableBuilding(t, had)) recordOn(s, day, Kind.BUILDING, t.name);
            else log.quietBuilt++;
            had.add(t.category);
        }
        log.seen.removeIf(id -> s.plot(id) == null);
        int walls = wallsComplete(s);
        if (walls > log.wallPieces + 3) recordOn(s, day, Kind.WALLS, log.wallPieces > 0 ? "second" : "");
        if (walls > 0 || log.wallPieces == 0) log.wallPieces = Math.max(log.wallPieces, walls);
    }

    /** The town hall, and the first building of each kind that changes a town's life. */
    static boolean notableBuilding(BuildingType t, Set<Category> had) {
        return switch (t.category) {
            case CIVIC -> true;
            case CHAPEL, LIBRARY, MARKET, TAVERN, BARRACKS, SMITHY, STABLE, TOWER -> !had.contains(t.category);
            default -> false;
        };
    }

    /** Wall pieces standing when every planned piece is done, else 0. */
    static int wallsComplete(Settlement s) {
        int all = 0, done = 0;
        for (Plot p : s.plots) if (Walls.isWallPiece(p)) { all++; if (p.state == Plot.State.BUILT && !p.deferred) done++; }
        return all > 0 && done == all ? done : 0;
    }

    /**
     * Folds the quiet days since the last event into one line ("Days 12–30: ...") when something changed. With
     * {@code always} the stretch is closed even if nothing happened, so very long peaceful stretches still show.
     */
    static void summarise(Settlement s, long day, boolean always) {
        Log log = s.chronicle;
        if (log.quietBuildings < 0) return;
        long from = Math.max(log.quietFrom, 1), to = day - 1;
        int buildings = s.d.buildings, pop = s.population;
        boolean grew = log.quietBuilt > 0 || buildings != log.quietBuildings || pop != log.quietPop;
        if (to - from + 1 < MIN_SUMMARY_DAYS || !grew && !always) return;
        String span = from == to ? "Day " + from : "Days " + from + "–" + to;
        String name = s.name.isEmpty() ? "the town" : s.name;
        String text;
        int seed = (int) Math.floorMod(day + log.entries.size(), 3L);
        if (pop > log.quietPop + 2 || buildings > log.quietBuildings) {
            text = switch (seed) {
                case 0 -> span + ": " + name + " grew quietly to " + plural(buildings, "building") + " and " + plural(pop, "soul") + ".";
                case 1 -> span + ": quiet years of work. " + cap(name) + " counted " + plural(buildings, "building") + " and " + plural(pop, "inhabitant") + ".";
                default -> span + ": nothing troubled " + name + ", which grew to " + plural(buildings, "building") + " and " + plural(pop, "soul") + ".";
            };
        } else if (pop < log.quietPop - 2) {
            text = span + ": hard times. " + cap(name) + " shrank to " + plural(pop, "soul") + ".";
        } else {
            text = switch (seed) {
                case 0 -> span + ": the days passed in peace; little changed in " + name + ".";
                case 1 -> span + ": harvests came in, and nothing worth the ink happened.";
                default -> span + ": " + name + " went about its business, " + plural(buildings, "building") + " strong.";
            };
        }
        log.entries.add(new Entry(Math.max(1, to), Kind.GROWTH, text));
        trim(log);
        log.quietFrom = day;
        log.quietBuilt = 0;
        log.quietBuildings = buildings;
        log.quietPop = pop;
        s.dirty = true;
    }

    static String plural(int n, String word) { return n + " " + word + (n == 1 ? "" : "s"); }

    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    // ------------------------------------------------------------------ for the City Manager

    public static CompoundTag view(Settlement s) {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        for (Entry e : s.chronicle.entries) {
            CompoundTag c = e.save();
            c.putInt("y", year(e.day));
            c.putString("label", e.kind.label);
            l.add(c);
        }
        t.put("entries", l);
        t.putLong("today", today());
        t.putInt("year", year(today()));
        t.putInt("yearDays", yearDays());
        return t;
    }

    private Chronicle() {}
}
