package livingrealms.citizen;

import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.chronicle.Chronicle;
import livingrealms.military.SoldierRanks;
import livingrealms.settlement.Job;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.*;

/**
 * The life of a town's people, run on records from the settlement simulation step, loaded or not: newcomers and
 * departures follow the population, couples marry, children are born when there is room and food and come of age
 * through the town's ordinary growth, the old die, and the town picks a mayor and a captain. See docs/citizens.md.
 */
public final class Citizens {
    /** Days between elections; an incumbent is only unseated by someone clearly more respected. */
    public static final int ELECTION_DAYS = 12, CAPTAIN_DAYS = 10, GRIEF_DAYS = 5, MAX_DAYS_PER_STEP = 4;
    public static final double SHOCK_FADE = 5;

    // ------------------------------------------------------------------ the step

    /** Runs after the population step: f is the fraction of a day this step covers. */
    public static void step(Settlement s, double f, RandomSource r) {
        if (s.mirror()) return;
        People p = s.people;
        ensure(s, r);
        reconcile(s, r);
        p.clock += Math.max(0, f);
        long day = (long) Math.floor(p.clock);
        if (day - p.lastDay > MAX_DAYS_PER_STEP) p.lastDay = day - MAX_DAYS_PER_STEP;
        boolean days = p.lastDay < day;
        while (p.lastDay < day) { p.lastDay++; daily(s, r); }
        // a death among many: the population above the rolls fills the place at once
        if (days) reconcile(s, r);
        shocks(s);
        jobs(s);
        homes(s);
        moods(s);
        if (p.mayor() == null) elect(s, false);
        captain(s, false);
    }

    /** First look at a town (new, or from a save before citizens): a record for everyone, a few families, a mayor. */
    static void ensure(Settlement s, RandomSource r) {
        People p = s.people;
        if (p.started) return;
        p.started = true;
        p.all.clear();
        p.changed();
        int n = Math.min(s.population, People.MAX_ADULTS);
        List<Citizen> made = new ArrayList<>();
        for (int i = 0; i < n; i++) made.add(newcomer(s, r, 18, 66));
        // about four in ten marry among themselves; younger couples may have children already
        Collections.shuffle(made, new Random(r.nextLong()));
        for (int i = 0; i + 1 < made.size() && i < made.size() * 2 / 5; i += 2) {
            Citizen a = made.get(i), b = made.get(i + 1);
            marry(a, b);
            int younger = Math.min(a.age(p.clock), b.age(p.clock));
            if (younger < 46 && p.children().size() < childCap(s)) {
                int kids = r.nextInt(3);
                for (int k = 0; k < kids && p.children().size() < childCap(s); k++)
                    born(s, a, b, r, 1 + r.nextInt(Math.max(1, Math.min(14, younger - 17))));
            }
        }
        p.seenEntry = s.chronicle.entries.isEmpty() ? 0 : hash(s.chronicle.entries.get(s.chronicle.entries.size() - 1));
        p.lastDay = (long) Math.floor(p.clock);
        s.dirty = true;
    }

    /** Keeps one record per head of population (up to the cap), whatever changed the count: training, raids, mods. */
    static void reconcile(Settlement s, RandomSource r) {
        People p = s.people;
        int target = Math.min(Math.max(0, s.population), People.MAX_ADULTS);
        List<Citizen> grown = p.adults();
        int have = grown.size();
        while (have < target) { comeOfAgeOrArrive(s, r); have++; }
        while (have > target) {
            Citizen c = leaver(s, grown);
            if (c == null) break;
            depart(s, c);
            grown.remove(c);
            have--;
        }
    }

    /** One more head of population: the eldest youth of age comes of age, otherwise a newcomer. Returns the news. */
    static String comeOfAgeOrArrive(Settlement s, RandomSource r) {
        People p = s.people;
        Citizen youth = null;
        for (Citizen c : p.children()) if (!c.child(p.clock) && (youth == null || c.born < youth.born)) youth = c;
        if (youth != null) {
            youth.grown = true;
            return youth.name() + " came of age and took a place of their own";
        }
        Citizen c = newcomer(s, r, 18, 45);
        return c.name() + (c.has(Trait.HARDWORKING) ? ", a hard worker," : "") + " arrived from the road to settle";
    }

    /** Called by the growth step when the population rises by one: who it was, for the town log. */
    public static String arrived(Settlement s, RandomSource r) {
        if (s.mirror()) return "Settlers arrived from the road";
        ensure(s, r);
        if (s.people.adults().size() >= Math.min(s.population, People.MAX_ADULTS)) return "Settlers arrived from the road";
        return comeOfAgeOrArrive(s, r);
    }

    /** Youths of age waiting for room hurry the town's growth along: +0.5 a day each, at most +1.5. */
    public static double growthBonus(Settlement s) {
        People p = s.people;
        int ready = 0;
        for (Citizen c : p.all) if (!c.grown && !c.child(p.clock)) ready++;
        return Math.min(1.5, ready * 0.5);
    }

    /** Who leaves when the population falls without a death: single, not the mayor, youngest first. */
    @Nullable
    static Citizen leaver(Settlement s, List<Citizen> grown) {
        People p = s.people;
        Citizen best = null;
        double bestScore = Double.MAX_VALUE;
        for (Citizen c : grown) {
            double score = c.age(p.clock) + (c.spouse > 0 ? 100 : 0) + (c.id == p.mayor ? 1000 : 0) + p.childrenOf(c).size() * 20;
            if (score < bestScore) { bestScore = score; best = c; }
        }
        return best;
    }

    /** Leaves town without dying (the population count has already changed). Their young children go with them. */
    static void depart(Settlement s, Citizen c) {
        People p = s.people;
        remove(s, c);
        for (Citizen k : p.childrenOf(c)) {
            if (k.grown) continue;
            Citizen other = p.get(k.mother == c.id ? k.father : k.mother);
            if (other == null) remove(s, k);
        }
    }

    static void remove(Settlement s, Citizen c) {
        People p = s.people;
        p.all.remove(c);
        p.changed();
        p.shown.remove(c.id);
        Citizen sp = p.get(c.spouse);
        if (sp != null) sp.spouse = 0;
        if (p.mayor == c.id) { p.mayor = 0; p.mayorAppointed = false; }
        if (p.askBy == c.id) Requests.clear(p);
        s.dirty = true;
    }

    static Citizen newcomer(Settlement s, RandomSource r, int minAge, int maxAge) {
        People p = s.people;
        Citizen c = new Citizen();
        c.id = p.nextId++;
        c.female = r.nextBoolean();
        c.first = PersonNames.first(s.style, c.female, r);
        c.family = PersonNames.family(s.style, c.female, r);
        int age = minAge + r.nextInt(Math.max(1, maxAge - minAge + 1));
        c.born = p.clock - age / People.YEARS_PER_DAY - r.nextDouble() / People.YEARS_PER_DAY;
        rollTraits(c, r, null, null);
        c.mood = s.happiness;
        p.all.add(c);
        p.changed();
        return c;
    }

    static void rollTraits(Citizen c, RandomSource r, @Nullable Citizen a, @Nullable Citizen b) {
        // a child takes after a parent half the time
        if (a != null && r.nextBoolean()) {
            Citizen from = b != null && r.nextBoolean() ? b : a;
            if (!from.traits.isEmpty()) {
                List<Trait> l = new ArrayList<>(from.traits);
                c.traits.add(l.get(r.nextInt(l.size())));
            }
        }
        int want = r.nextInt(3) == 0 ? 2 : 1;
        for (int guard = 0; c.traits.size() < want && guard < 8; guard++) {
            int roll = r.nextInt(100), i = 0;
            while (roll >= Trait.WEIGHT[i]) roll -= Trait.WEIGHT[i++];
            Trait t = Trait.values()[i];
            if (t == Trait.SICKLY && c.traits.contains(Trait.CHEERFUL) || t == Trait.CHEERFUL && c.traits.contains(Trait.SICKLY)) continue;
            c.traits.add(t);
        }
    }

    static void marry(Citizen a, Citizen b) { a.spouse = b.id; b.spouse = a.id; }

    static Citizen born(Settlement s, Citizen a, Citizen b, RandomSource r, int age) {
        People p = s.people;
        Citizen c = new Citizen();
        c.id = p.nextId++;
        c.female = r.nextBoolean();
        Citizen mother = a.female && !b.female ? a : b.female && !a.female ? b : a;
        Citizen father = mother == a ? b : a;
        c.mother = mother.id;
        c.father = father.id;
        c.first = PersonNames.first(s.style, c.female, r);
        c.family = PersonNames.child(s.style, r.nextInt(4) == 0 ? mother : father, c.female);
        c.born = p.clock - age / People.YEARS_PER_DAY;
        c.grown = false;
        rollTraits(c, r, a, b);
        c.home = a.home;
        c.mood = s.happiness;
        p.all.add(c);
        p.changed();
        return c;
    }

    static int childCap(Settlement s) {
        return Math.min(People.MAX_CHILDREN, Math.max(2, s.people.adults().size() / 3 + 1));
    }

    // ------------------------------------------------------------------ the day

    static void daily(Settlement s, RandomSource r) {
        People p = s.people;
        p.shock = Math.max(0, p.shock - SHOCK_FADE);
        if (p.shock == 0) p.shockWhy = "";
        age(s, r);
        wed(s, r);
        births(s, r);
        if (p.clock - p.lastElection >= ELECTION_DAYS && !p.mayorAppointed) elect(s, true);
        Requests.daily(s, r);
    }

    /** The old die, the sickly sooner; youths who find no room in town for years set off elsewhere. */
    static void age(Settlement s, RandomSource r) {
        People p = s.people;
        int died = 0;
        for (Citizen c : new ArrayList<>(p.all)) {
            int age = c.age(p.clock);
            if (!c.grown) {
                if (age >= 24) { remove(s, c); if (!s.isAi()) s.log(c.name() + " found no room in town and left to seek their fortune"); }
                continue;
            }
            int from = c.has(Trait.SICKLY) ? 54 : 64;
            if (age < from || died >= 2) continue;
            double chance = age >= 95 ? 1 : Math.min(0.6, (age - from + 1) / 50.0);
            if (r.nextDouble() < chance) { die(s, c, "of old age", true); died++; }
        }
    }

    /** At most one wedding a day, between two unmarried adults who are not siblings. */
    static void wed(Settlement s, RandomSource r) {
        People p = s.people;
        List<Citizen> free = new ArrayList<>();
        for (Citizen c : p.adults()) {
            int a = c.age(p.clock);
            if (c.spouse == 0 && a >= 18 && a <= 55) free.add(c);
        }
        if (free.size() < 2 || r.nextDouble() > 0.35) return;
        Citizen a = free.get(r.nextInt(free.size()));
        Citizen b = null;
        int ageA = a.age(p.clock);
        for (int tries = 0; tries < 6 && b == null; tries++) {
            Citizen c = free.get(r.nextInt(free.size()));
            if (c == a || siblings(a, c) || Math.abs(c.age(p.clock) - ageA) > 15) continue;
            b = c;
        }
        if (b == null) return;
        wedding(s, a, b);
    }

    static boolean siblings(Citizen a, Citizen b) {
        return a.mother > 0 && a.mother == b.mother || a.father > 0 && a.father == b.father
                || a.id == b.mother || a.id == b.father || b.id == a.mother || b.id == a.father;
    }

    static void wedding(Settlement s, Citizen a, Citizen b) {
        People p = s.people;
        marry(a, b);
        p.weddings++;
        if (!s.isAi()) s.log(a.name() + " and " + b.name() + " were married");
        if (a.id == p.mayor || b.id == p.mayor) {
            Citizen mayor = a.id == p.mayor ? a : b, other = mayor == a ? b : a;
            Chronicle.record(s, Chronicle.Kind.PEOPLE, "Mayor " + mayor.name() + " married " + other.name() + ", and all " + town(s) + " was invited to the feast.");
        }
        s.dirty = true;
    }

    /** A child is born when the town is fed, content and has room: about one couple in seven a day, one birth a day. */
    static void births(Settlement s, RandomSource r) {
        People p = s.people;
        if (!roomForChildren(s)) return;
        List<Citizen[]> couples = new ArrayList<>();
        for (Citizen a : p.adults()) {
            Citizen b = p.get(a.spouse);
            if (b == null || b.id < a.id) continue;
            if (a.age(p.clock) > 46 || b.age(p.clock) > 46) continue;
            int kids = 0;
            for (Citizen k : p.childrenOf(a)) if (k.mother == b.id || k.father == b.id) kids++;
            if (kids < 4) couples.add(new Citizen[]{a, b});
        }
        Collections.shuffle(couples, new Random(r.nextLong()));
        for (Citizen[] c : couples) {
            if (r.nextDouble() >= 0.15) continue;
            Citizen kid = born(s, c[0], c[1], r, 0);
            p.births++;
            if (!s.isAi()) s.log("A child, " + kid.first + ", was born to " + c[0].first + " and " + c[1].first);
            return;
        }
    }

    /** Fed (not starving, a granary of 2 a head), content (mood 45+), a free bed counting the children, under the cap. */
    public static boolean roomForChildren(Settlement s) {
        People p = s.people;
        int food = s.stock.count(Items.BREAD) + s.stock.count(Items.CARROT) + s.stock.count(Items.POTATO);
        return !s.starving && s.happiness >= 45 && food >= s.population * 2
                && s.population + p.children().size() < s.d.housing && p.children().size() < childCap(s);
    }

    /** A death: recorded in the memorial, mourned by the family; notables go into the chronicle. */
    public static void die(Settlement s, Citizen c, String cause, boolean countDown) {
        People p = s.people;
        if (!p.all.contains(c)) return;
        int age = c.age(p.clock);
        boolean wasMayor = c.id == p.mayor;
        if (countDown && c.grown && s.population > 1) s.population--;
        for (Citizen k : p.all) if (k.id == c.spouse || k.id == c.mother || k.id == c.father || k.mother == c.id || k.father == c.id) k.griefUntil = p.clock + GRIEF_DAYS;
        remove(s, c);
        // orphans are taken in by the town; they stay
        CompoundTag m = new CompoundTag();
        m.putString("n", c.name());
        m.putInt("a", age);
        m.putString("c", cause);
        m.putString("j", c.grown ? c.job.label : "Child");
        m.putLong("d", Chronicle.today());
        m.putBoolean("mayor", wasMayor);
        p.memorial.addFirst(m);
        while (p.memorial.size() > People.MEMORIAL) p.memorial.removeLast();
        p.deaths++;
        if (!s.isAi()) s.log(c.name() + (wasMayor ? ", the mayor," : "") + " died " + cause + ", aged " + age);
        if (wasMayor) Chronicle.record(s, Chronicle.Kind.PEOPLE, "Mayor " + c.name() + " died " + cause + ", aged " + age + ". " + cap(town(s)) + " mourned.");
        s.dirty = true;
    }

    /** A visible settler was killed (the settler's own death handling lowers the population). */
    public static void killed(Settlement s, int citizen, String cause) {
        Citizen c = s.people.get(citizen);
        if (c != null) die(s, c, cause, false);
    }

    // ------------------------------------------------------------------ fear after raids and fires

    static int hash(Chronicle.Entry e) { return Objects.hash(e.day(), e.kind(), e.text()) | 1; }

    /** New chronicle entries of raids, sieges, fires and the like frighten the town for a few days. */
    static void shocks(Settlement s) {
        People p = s.people;
        List<Chronicle.Entry> l = s.chronicle.entries;
        if (l.isEmpty()) return;
        int newest = hash(l.get(l.size() - 1));
        if (p.seenEntry == 0) { p.seenEntry = newest; return; }
        if (newest == p.seenEntry) return;
        for (int i = l.size() - 1, n = 0; i >= 0 && n < 12; i--, n++) {
            Chronicle.Entry e = l.get(i);
            if (hash(e) == p.seenEntry) break;
            double amount;
            String why;
            switch (e.kind()) {
                case RAID_LOST -> { amount = 25; why = "the fall of the town"; }
                case FIRE -> { amount = 20; why = "the fire"; }
                case SIEGE -> { amount = 18; why = "the siege"; }
                case DISASTER -> { amount = 15; why = "the disaster"; }
                case RAID_REPELLED -> { amount = 12; why = "the raid"; }
                case CONQUERED -> { amount = 10; why = "the conquest"; }
                case REBELLION -> { amount = 8; why = "the uprising"; }
                case FALLEN -> { amount = 6; why = "the soldiers' deaths"; }
                case WAR -> { amount = 5; why = "the war"; }
                default -> { amount = 0; why = ""; }
            }
            shock(s, amount, why);
        }
        p.seenEntry = newest;
    }

    /** Frightens the town (other features may call this directly); the larger fear wins, up to 30. */
    public static void shock(Settlement s, double amount, String why) {
        People p = s.people;
        if (amount <= 0) return;
        if (amount >= p.shock) p.shockWhy = why;
        p.shock = Math.min(30, Math.max(p.shock, amount));
        s.dirty = true;
    }

    // ------------------------------------------------------------------ jobs, homes, moods

    /** Hands the town's jobs (the simulation's worker counts) to its adults, keeping each in their job where possible. */
    static void jobs(Settlement s) {
        People p = s.people;
        EnumMap<Job, Integer> want = new EnumMap<>(s.d.workers);
        List<Citizen> loose = new ArrayList<>();
        for (Citizen c : p.all) {
            if (!c.grown) { c.job = Job.IDLE; continue; }
            int left = c.job == Job.IDLE ? 0 : want.getOrDefault(c.job, 0);
            if (left > 0) want.put(c.job, left - 1);
            else loose.add(c);
        }
        // the young take new work before the old; the pious are drawn to the chapel
        loose.sort(Comparator.comparingInt((Citizen c) -> c.age(p.clock)));
        for (Citizen c : loose) {
            Job pick = null;
            if (c.has(Trait.PIOUS) && want.getOrDefault(Job.PRIEST, 0) > 0) pick = Job.PRIEST;
            if (pick == null) for (var e : want.entrySet()) if (e.getValue() > 0) { pick = e.getKey(); break; }
            if (pick == null) { c.job = Job.IDLE; continue; }
            want.merge(pick, -1, Integer::sum);
            c.job = pick;
        }
    }

    /** Beds a standing building offers, as the simulation counts housing. */
    static int beds(Plot pl) {
        if (!pl.standing()) return 0;
        BuildingType t = BuildingCatalog.get(pl.typeId);
        if (t == null) return 0;
        Blueprint bp = pl.blueprint();
        int beds = bp == null ? 0 : bp.beds;
        return switch (t.category) {
            case HOUSING -> Math.max(3, beds * 2 + 1) + (!t.procedural() ? (pl.level - 1) * 2 : 0);
            case CIVIC -> beds * 2;
            case TAVERN -> beds;
            default -> 0;
        };
    }

    static int head(People p, Citizen c, int depth) {
        if (!c.grown && depth < 3) {
            Citizen parent = p.get(c.father);
            if (parent == null) parent = p.get(c.mother);
            if (parent != null) return head(p, parent, depth + 1);
        }
        return c.spouse > 0 && p.get(c.spouse) != null ? Math.min(c.id, c.spouse) : c.id;
    }

    /** Households (a couple or single and their children) keep their home while it stands, else move into the roomiest. */
    static void homes(Settlement s) {
        People p = s.people;
        Map<Integer, Integer> room = new LinkedHashMap<>();
        if (s.baseHousing > 0) room.put(0, s.baseHousing);
        for (Plot pl : s.plots) { int b = beds(pl); if (b > 0) room.put(pl.id, b); }
        Map<Integer, List<Citizen>> households = new TreeMap<>();
        for (Citizen c : p.all) households.computeIfAbsent(head(p, c, 0), k -> new ArrayList<>()).add(c);
        List<List<Citizen>> moving = new ArrayList<>();
        for (List<Citizen> h : households.values()) {
            int home = -1;
            for (Citizen c : h) if (c.grown && c.home >= 0) { home = c.home; break; }
            if (home < 0) home = h.get(0).home;
            int left = room.getOrDefault(home, -1);
            if (home >= 0 && left >= h.size()) { room.put(home, left - h.size()); for (Citizen c : h) c.home = home; }
            else moving.add(h);
        }
        for (List<Citizen> h : moving) {
            int best = -1, bestRoom = 0;
            for (var e : room.entrySet()) if (e.getValue() > bestRoom) { best = e.getKey(); bestRoom = e.getValue(); }
            for (Citizen c : h) {
                if (best >= 0 && room.get(best) > 0) { c.home = best; room.merge(best, -1, Integer::sum); }
                else {
                    best = -1; bestRoom = 0;
                    for (var e : room.entrySet()) if (e.getValue() > bestRoom) { best = e.getKey(); bestRoom = e.getValue(); }
                    if (best >= 0) { c.home = best; room.merge(best, -1, Integer::sum); } else c.home = -1;
                }
            }
        }
    }

    public static String homeName(Settlement s, int home) {
        if (home == 0) return "a village house";
        if (home < 0) return "no home";
        Plot pl = s.plot(home);
        BuildingType t = pl == null ? null : BuildingCatalog.get(pl.typeId);
        return t == null ? "no home" : t.name + " #" + home;
    }

    static void moods(Settlement s) {
        for (Citizen c : s.people.all) c.mood = mood(s, c, null);
    }

    /** A citizen's mood: the town's, then their home, work, family, temperament and fears. */
    public static double mood(Settlement s, Citizen c, @Nullable List<String> why) {
        People p = s.people;
        double m = s.happiness;
        if (why != null) why.add(Math.round(s.happiness) + " town mood");
        if (c.home > 0) m += add(why, 4, "a house of their own");
        else if (c.home < 0) m += add(why, -10, "no home");
        if (c.grown) {
            if (c.job == Job.IDLE) m += add(why, c.has(Trait.HARDWORKING) ? -8 : -4, "no work");
            else if (c.has(Trait.HARDWORKING)) m += add(why, 3, "good work");
        }
        if (c.spouse > 0) m += add(why, 3, "married");
        if (c.has(Trait.PIOUS)) m += s.standing(Category.CHAPEL) > 0 ? add(why, 5, "a chapel") : add(why, -3, "no chapel");
        if (c.has(Trait.GREEDY)) m += add(why, (1 - s.taxLevel) * 4, "taxes (greedy)");
        if (c.has(Trait.CHEERFUL)) m += add(why, 6, "cheerful");
        if (c.has(Trait.SICKLY)) m += add(why, -4, "sickly");
        if (c.griefUntil > p.clock) m += add(why, -15, "grieving");
        if (c.gladUntil > p.clock) m += add(why, 12, "a favour granted");
        if (p.shock > 0) {
            double fear = p.shock * (c.has(Trait.BRAVE) ? 0.5 : 1);
            m += add(why, -fear, "fear after " + (p.shockWhy.isEmpty() ? "the trouble" : p.shockWhy));
        }
        return Math.max(0, Math.min(100, m));
    }

    static double add(@Nullable List<String> why, double v, String reason) {
        long r = Math.round(v);
        if (why != null && r != 0) why.add((r > 0 ? "+" : "") + r + " " + reason);
        return v;
    }

    public static double average(Settlement s) {
        List<Citizen> l = s.people.all;
        if (l.isEmpty()) return s.happiness;
        double sum = 0;
        for (Citizen c : l) sum += c.mood;
        return sum / l.size();
    }

    public static String moodLabel(double m) {
        return m >= 75 ? "Joyful" : m >= 55 ? "Content" : m >= 40 ? "Uneasy" : m >= 25 ? "Unhappy" : "Miserable";
    }

    // ------------------------------------------------------------------ effects on the town

    /** Added to the town's mood target (Simulation.happinessTarget): the mayor's temperament and fear after trouble. */
    public static double townMood(Settlement s, List<String> why) {
        People p = s.people;
        if (!p.started) return 0;
        double h = 0;
        Citizen mayor = p.mayor();
        if (mayor != null) {
            if (mayor.has(Trait.PIOUS)) { h += 3; why.add("+3 pious mayor"); }
            if (mayor.has(Trait.CHEERFUL)) { h += 4; why.add("+4 cheerful mayor"); }
            if (mayor.has(Trait.GREEDY)) { h -= 2; why.add("-2 greedy mayor"); }
        }
        int fear = (int) Math.round(p.shock / 2 * (mayor != null && mayor.has(Trait.BRAVE) ? 0.5 : 1));
        if (fear > 0) { h -= fear; why.add("-" + fear + " fear after " + (p.shockWhy.isEmpty() ? "the trouble" : p.shockWhy)); }
        return h;
    }

    /** Builder speed: a hardworking mayor +8%, hardworking builders +2% each (up to +10%). */
    public static double buildBonus(Settlement s) {
        People p = s.people;
        double b = 1;
        Citizen mayor = p.mayor();
        if (mayor != null && mayor.has(Trait.HARDWORKING)) b *= 1.08;
        int keen = 0;
        for (Citizen c : p.all) if (c.job == Job.BUILDER && c.has(Trait.HARDWORKING)) keen++;
        return b * (1 + Math.min(0.1, keen * 0.02));
    }

    /** Taxes: a greedy mayor squeezes out 15% more. */
    public static double taxBonus(Settlement s) {
        Citizen mayor = s.people.mayor();
        return mayor != null && mayor.has(Trait.GREEDY) ? 1.15 : 1;
    }

    /** A bad mayor (greedy, or the most disliked) of a very unhappy town costs a conquered city loyalty; a well-liked one helps. */
    public static void loyalty(Settlement s, List<Map.Entry<String, Double>> out) {
        Citizen mayor = s.people.mayor();
        if (mayor == null) return;
        double avg = average(s);
        if (avg < 30 && (mayor.has(Trait.GREEDY) || mayor.mood < 30)) out.add(Map.entry("a hated mayor", -3.0));
        else if (avg >= 65 && !mayor.has(Trait.GREEDY)) out.add(Map.entry("a well-liked mayor", 1.0));
    }

    public static String mayorBonus(@Nullable Citizen mayor) {
        if (mayor == null) return "No mayor";
        List<String> l = new ArrayList<>();
        for (Trait t : mayor.traits) {
            switch (t) {
                case HARDWORKING -> l.add("builders +8%");
                case PIOUS -> l.add("+3 mood");
                case CHEERFUL -> l.add("+4 mood");
                case GREEDY -> l.add("taxes +15%, -2 mood");
                case BRAVE -> l.add("fear halved");
                default -> {}
            }
        }
        return l.isEmpty() ? "No bonus" : String.join(", ", l);
    }

    // ------------------------------------------------------------------ notables

    static final Map<Job, Integer> PRESTIGE = new EnumMap<>(Map.of(Job.PRIEST, 10, Job.SCHOLAR, 10, Job.MERCHANT, 9, Job.SMITH, 8,
            Job.INNKEEPER, 6, Job.CRAFTSMAN, 5, Job.BUILDER, 4, Job.FARMER, 3, Job.IDLE, -10));

    /** How much the town looks up to someone: age and wisdom, work, family, temperament and good cheer. */
    public static double respect(Settlement s, Citizen c) {
        People p = s.people;
        if (!c.grown) return 0;
        int age = c.age(p.clock);
        // age brings standing up to fifty; the elders are honoured, but the frail are not asked to lead
        double r = Math.min(age, 50) * 0.4 + (age >= Citizen.ELDER_AGE ? age >= 68 ? -6 : 3 : 0) + PRESTIGE.getOrDefault(c.job, 2);
        if (c.has(Trait.HARDWORKING)) r += 8;
        if (c.has(Trait.PIOUS)) r += 6;
        if (c.has(Trait.BRAVE)) r += 5;
        if (c.has(Trait.CHEERFUL)) r += 6;
        if (c.has(Trait.GREEDY)) r -= 4;
        if (c.has(Trait.SICKLY)) r -= 6;
        if (c.spouse > 0) r += 5;
        r += Math.min(9, p.childrenOf(c).size() * 3);
        return r + c.mood / 10;
    }

    /** Elects the most respected adult; an elected incumbent stays unless beaten by 5. Vacancies are filled at once. */
    public static void elect(Settlement s, boolean scheduled) {
        People p = s.people;
        Citizen cur = p.mayor();
        if (cur != null && p.mayorAppointed) return;
        Citizen best = null;
        double bestR = -1e9;
        for (Citizen c : p.adults()) { double r = respect(s, c); if (r > bestR) { bestR = r; best = c; } }
        p.lastElection = p.clock;
        if (best == null || best == cur) return;
        if (cur != null && bestR < respect(s, cur) + 5) return;
        p.mayor = best.id;
        p.mayorAppointed = false;
        String what = best.name() + ", " + article(best.job == Job.IDLE ? "townsman" : best.job.label.toLowerCase(Locale.ROOT));
        Chronicle.record(s, Chronicle.Kind.PEOPLE, cur != null ? "The people of " + town(s) + " elected " + what + ", as mayor in place of " + cur.name() + "."
                : "The people of " + town(s) + " chose " + what + ", as their mayor.");
        if (!s.isAi()) s.log(best.name() + " was elected mayor");
        s.dirty = true;
    }

    public static String appointMayor(Settlement s, int id, String by) {
        People p = s.people;
        Citizen c = p.get(id);
        if (c == null || !c.grown) return "No such adult in " + s.name;
        if (c.id == p.mayor && p.mayorAppointed) return null;
        p.mayor = c.id;
        p.mayorAppointed = true;
        p.lastElection = p.clock;
        Chronicle.record(s, Chronicle.Kind.PEOPLE, "By decree of " + by + ", " + c.name() + " was appointed mayor of " + town(s) + ".");
        s.log(c.name() + " was appointed mayor");
        s.dirty = true;
        return null;
    }

    /** Hands the choice back to the people: an election is held at once. */
    public static String holdElection(Settlement s) {
        People p = s.people;
        p.mayorAppointed = false;
        Citizen before = p.mayor();
        elect(s, true);
        if (before == p.mayor()) s.log("The people kept " + (before == null ? "no one" : before.name()) + " as mayor");
        s.dirty = true;
        return null;
    }

    public static double soldierScore(Settlement.Soldier so) {
        return SoldierRanks.rank(so) * 10 + so.kills * 2 + so.merit.merit * 0.5 + so.level + (so.royalGuard ? 15 : 0);
    }

    @Nullable
    public static Settlement.Soldier captain(Settlement s) {
        UUID id = s.people.captain;
        if (id == null) return null;
        for (Settlement.Soldier so : s.soldiers) if (id.equals(so.entity)) return so;
        return null;
    }

    /** The best soldier (rank, kills, merit) captains the guard; replaced when gone, or after 10 days by a clearly better one. */
    static void captain(Settlement s, boolean force) {
        People p = s.people;
        Settlement.Soldier cur = captain(s);
        if (cur == null) { p.captain = null; p.captainAppointed = false; }
        if (cur != null && (p.captainAppointed || !force && p.clock - p.captainSince < CAPTAIN_DAYS)) return;
        Settlement.Soldier best = null;
        double bestScore = -1;
        for (Settlement.Soldier so : s.soldiers) { double sc = soldierScore(so); if (sc > bestScore) { bestScore = sc; best = so; } }
        if (best == null || best == cur) return;
        if (cur != null && bestScore < soldierScore(cur) * 1.5 + 10) { p.captainSince = p.clock; return; }
        name(s, best, null);
    }

    static void name(Settlement s, Settlement.Soldier so, @Nullable String by) {
        People p = s.people;
        p.captain = so.entity;
        p.captainSince = p.clock;
        p.captainAppointed = by != null;
        String title = SoldierRanks.title(SoldierRanks.rank(so)).toLowerCase(Locale.ROOT);
        Chronicle.record(s, Chronicle.Kind.PEOPLE, by != null ? "By decree of " + by + ", " + so.name + " was made captain of the guard of " + town(s) + "."
                : so.name + ", " + article(title) + " of " + so.kills + " kills, was named captain of the guard of " + town(s) + ".");
        if (!s.isAi()) s.log(so.name + " now captains the guard");
        s.dirty = true;
    }

    public static String appointCaptain(Settlement s, UUID soldier, String by) {
        for (Settlement.Soldier so : s.soldiers) if (so.entity.equals(soldier)) {
            if (so.entity.equals(s.people.captain) && s.people.captainAppointed) return null;
            name(s, so, by);
            return null;
        }
        return "No such soldier in " + s.name;
    }

    // ------------------------------------------------------------------ settlers in the world

    /** Settlers currently walking about (Simulation.settlers), so a new one takes someone not yet shown. */
    public static void seen(Settlement s, Collection<Integer> ids) {
        s.people.shown.clear();
        s.people.shown.addAll(ids);
    }

    /** Someone with this job to walk about as a settler, or any adult not yet shown. */
    @Nullable
    public static Citizen claim(Settlement s, Job job, RandomSource r) {
        People p = s.people;
        if (!p.started) return null;
        List<Citizen> match = new ArrayList<>(), any = new ArrayList<>();
        for (Citizen c : p.all) {
            if (!c.grown || p.shown.contains(c.id)) continue;
            any.add(c);
            if (c.job == job) match.add(c);
        }
        List<Citizen> from = !match.isEmpty() ? match : any;
        if (from.isEmpty()) return null;
        Citizen c = from.get(r.nextInt(from.size()));
        p.shown.add(c.id);
        return c;
    }

    /** One line about a citizen for the action bar. */
    public static String describe(Settlement s, Citizen c) {
        People p = s.people;
        String role = c.id == p.mayor ? "mayor and " + c.job.label.toLowerCase(Locale.ROOT) : c.job.label.toLowerCase(Locale.ROOT);
        String traits = c.traitText();
        return c.name() + ", " + c.age(p.clock) + ", " + role + " of " + s.name + (traits.isEmpty() ? "" : " · " + traits)
                + " · " + moodLabel(c.mood).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ City Manager

    public static CompoundTag view(Settlement s, ServerPlayer viewer) {
        People p = s.people;
        CompoundTag t = new CompoundTag();
        t.putBoolean("started", p.started);
        t.putInt("population", s.population);
        t.putInt("recorded", p.adults().size());
        t.putInt("children", p.children().size());
        t.putInt("mood", (int) Math.round(average(s)));
        t.putInt("births", p.births); t.putInt("deaths", p.deaths); t.putInt("weddings", p.weddings);
        t.putInt("shock", (int) Math.round(p.shock));
        t.putString("shockWhy", p.shockWhy);
        t.putBoolean("roomForChildren", roomForChildren(s));
        ListTag list = new ListTag();
        for (Citizen c : p.all) {
            CompoundTag e = new CompoundTag();
            e.putInt("id", c.id);
            e.putString("n", c.name());
            e.putBoolean("w", c.female);
            e.putString("j", c.grown ? c.job.label : c.child(p.clock) ? "Child" : "Youth");
            e.putInt("a", c.age(p.clock));
            e.putString("st", c.stageLabel(p.clock));
            e.putInt("m", (int) Math.round(c.mood));
            ListTag tr = new ListTag();
            for (Trait x : c.traits) tr.add(StringTag.valueOf(x.name()));
            e.put("tr", tr);
            e.putString("h", homeName(s, c.home));
            e.putInt("sp", c.spouse); e.putInt("mo", c.mother); e.putInt("fa", c.father);
            e.put("k", new IntArrayTag(p.childrenOf(c).stream().mapToInt(k -> k.id).toArray()));
            e.putInt("r", (int) Math.round(respect(s, c)));
            List<String> why = new ArrayList<>();
            mood(s, c, why);
            e.putString("why", String.join("\n", why));
            e.putBoolean("shown", p.shown.contains(c.id));
            list.add(e);
        }
        t.put("people", list);
        Citizen mayor = p.mayor();
        t.putInt("mayor", mayor == null ? 0 : mayor.id);
        t.putBoolean("mayorAppointed", p.mayorAppointed);
        t.putString("mayorBonus", mayorBonus(mayor));
        t.putInt("nextElection", (int) Math.max(0, Math.ceil(p.lastElection + ELECTION_DAYS - p.clock)));
        Settlement.Soldier cap = captain(s);
        if (cap != null) {
            t.putUUID("captain", cap.entity);
            t.putString("captainName", cap.name);
            t.putString("captainTitle", SoldierRanks.title(SoldierRanks.rank(cap)));
            t.putInt("captainKills", cap.kills);
        }
        t.putBoolean("captainAppointed", p.captainAppointed);
        ListTag soldiers = new ListTag();
        List<Settlement.Soldier> ranked = new ArrayList<>(s.soldiers);
        ranked.sort(Comparator.comparingDouble(so -> -soldierScore(so)));
        for (Settlement.Soldier so : ranked.subList(0, Math.min(40, ranked.size()))) {
            CompoundTag e = new CompoundTag();
            e.putUUID("e", so.entity);
            e.putString("n", so.name);
            e.putString("t", SoldierRanks.title(SoldierRanks.rank(so)));
            e.putInt("k", so.kills);
            e.putInt("m", Math.round(so.merit.merit));
            soldiers.add(e);
        }
        t.put("soldiers", soldiers);
        ListTag mem = new ListTag();
        mem.addAll(p.memorial);
        t.put("memorial", mem);
        if (p.askBy > 0) {
            Citizen asker = p.get(p.askBy);
            t.putString("ask", (asker == null ? "Someone" : asker.name()) + " asks for " + p.askCount + " " + Requests.itemName(p.askItem) + ": " + p.askWhy);
        }
        return t;
    }

    /** City Manager actions (the ruler only; Actions checks that). */
    @Nullable
    public static String handle(ServerPlayer player, Settlement s, String action, CompoundTag d) {
        String by = player.getGameProfile().getName();
        return switch (action) {
            case "appointMayor" -> appointMayor(s, d.getInt("citizen"), by);
            case "appointCaptain" -> d.hasUUID("soldier") ? appointCaptain(s, d.getUUID("soldier"), by) : "No soldier chosen";
            case "holdElection" -> holdElection(s);
            case "freeCaptain" -> { s.people.captainAppointed = false; captain(s, true); s.dirty = true; yield null; }
            default -> "Unknown action " + action;
        };
    }

    static String town(Settlement s) { return s.name.isEmpty() ? "the town" : s.name; }

    static String cap(String x) { return x.isEmpty() ? x : Character.toUpperCase(x.charAt(0)) + x.substring(1); }

    static String article(String noun) {
        return (noun.isEmpty() || "aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) < 0 ? "a " : "an ") + noun;
    }

    private Citizens() {}
}
