package livingrealms.citizen;

import com.mojang.authlib.GameProfile;
import livingrealms.LivingRealms;
import livingrealms.building.BuildingCatalog;
import livingrealms.chronicle.Chronicle;
import livingrealms.entity.SettlerEntity;
import livingrealms.military.UnitType;
import livingrealms.quest.Bounties;
import livingrealms.quest.QuestData;
import livingrealms.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * -PcitizenTest: citizens created (also lazily for old saves) and saved, jobs and homes, marriage, birth, coming of
 * age, death, fear after a fire or raid, mayor and captain elections and appointments, chronicle entries without spam,
 * bounded counts, a citizen's request on the bounty board, and a visible settler carrying a citizen's name.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class CitizenSelfTest {
    private static int checks;
    private static final List<String> notes = new ArrayList<>();

    private static void check(boolean value, String detail) {
        if (!value) throw new IllegalStateException(detail);
        checks++;
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.citizenTest")) return;
        MinecraftServer server = event.getServer();
        Throwable error = null;
        try {
            run(server);
        } catch (Throwable t) {
            error = t;
        }
        String report = error == null ? "PASS: " + checks + " citizen assertions" + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")")
                : "FAIL after " + checks + " assertions: " + error;
        if (error == null) LivingRealms.LOG.info("CITIZEN SELF TEST {}", report);
        else LivingRealms.LOG.error("CITIZEN SELF TEST FAILED", error);
        try { Files.writeString(Path.of("citizen-test-result.txt"), report); } catch (Exception ex) { throw new RuntimeException(ex); }
        server.halt(false);
    }

    static Plot plot(Settlement s, String type, BlockPos origin) {
        Plot p = new Plot(); p.id = s.nextId++; p.typeId = type; p.blueprintKey = BuildingCatalog.get(type).blueprintKey(1, s.style);
        p.origin = origin; p.state = Plot.State.BUILT; s.plots.add(p);
        return p;
    }

    static Settlement town(String name, int pop, BlockPos center) {
        Settlement s = new Settlement();
        s.owner = UUID.randomUUID(); s.ownerName = "Aldric"; s.name = name; s.center = center;
        s.population = pop; s.baseHousing = 12; s.happiness = 70;
        s.tier = Tier.VILLAGE;
        s.stock.force(Items.BREAD, 400);
        plot(s, "livingrealms:town_hall", center);
        for (int i = 0; i < 4; i++) plot(s, "livingrealms:cottage", center.offset(20 + i * 14, 0, 0));
        plot(s, "livingrealms:farm_field", center.offset(0, 0, 30));
        Simulation.recompute(s);
        return s;
    }

    static long count(Settlement s, String text) {
        return s.chronicle.entries.stream().filter(e -> e.kind() == Chronicle.Kind.PEOPLE && e.text().contains(text)).count();
    }

    static long people(Settlement s) { return s.chronicle.entries.stream().filter(e -> e.kind() == Chronicle.Kind.PEOPLE).count(); }

    static void run(MinecraftServer server) {
        RandomSource r = new LegacyRandomSource(42);
        Settlement s = town("Testford", 12, new BlockPos(0, 100, 8192));
        People p = s.people;

        // ---- identities, created lazily
        check(!p.started && p.all.isEmpty(), "No citizens before the first step");
        Citizens.step(s, 0, r);
        check(p.started && p.adults().size() == 12, "A record per head of population: " + p.adults().size());
        Set<Integer> ids = new HashSet<>();
        for (Citizen c : p.all) {
            check(ids.add(c.id), "Unique ids");
            check(!c.first.isEmpty() && !c.family.isEmpty(), "Named: " + c.name());
            check(c.grown ? c.age(p.clock) >= 18 : c.child(p.clock), "Adults are of age, children are young: " + c.name() + " " + c.age(p.clock));
            check(!c.traits.isEmpty() && c.traits.size() <= 2, "One or two traits");
            check(c.mood >= 0 && c.mood <= 100, "Mood in range");
        }
        long couples = p.adults().stream().filter(c -> c.spouse > 0).count();
        check(couples >= 2 && couples % 2 == 0, "Some couples, each linked both ways: " + couples);
        for (Citizen c : p.adults()) if (c.spouse > 0) check(p.get(c.spouse) != null && p.get(c.spouse).spouse == c.id, "Spouse link both ways");
        for (Citizen c : p.children()) check(p.get(c.mother) != null || p.get(c.father) != null, "Children have parents in town");
        check(p.mayor() != null && p.mayor().grown, "A mayor was chosen");
        check(count(s, "as their mayor") == 1, "The first mayor is chronicled once");
        notes.add("first mayor: " + p.mayor().name());

        // ---- jobs follow the simulation's worker counts; homes
        Map<Job, Integer> jobs = new EnumMap<>(Job.class);
        for (Citizen c : p.adults()) if (c.job != Job.IDLE) jobs.merge(c.job, 1, Integer::sum);
        for (var e : s.d.workers.entrySet()) check(jobs.getOrDefault(e.getKey(), 0).intValue() == e.getValue(), "Workers match for " + e.getKey() + ": " + jobs + " vs " + s.d.workers);
        check(jobs.values().stream().mapToInt(Integer::intValue).sum() + s.d.unemployed == 12, "Everyone has a job or is counted idle");
        for (Citizen c : p.all) check(c.home >= 0, "Everyone housed while there is room: " + c.name());
        for (Citizen c : p.adults()) if (c.spouse > 0) check(p.get(c.spouse).home == c.home, "Couples share a home");
        check(p.all.stream().anyMatch(c -> c.home > 0) && p.all.stream().anyMatch(c -> !Citizens.homeName(s, c.home).equals("no home")), "Homes are real houses");

        // ---- save and load; older saves
        Settlement back = Settlement.load(s.save());
        check(back.people.started && back.people.all.size() == p.all.size(), "Citizens survive save and load");
        for (Citizen c : p.all) {
            Citizen b = back.people.get(c.id);
            check(b != null && b.name().equals(c.name()) && b.spouse == c.spouse && b.traits.equals(c.traits) && b.job == c.job && b.home == c.home
                    && b.grown == c.grown && Math.abs(b.born - c.born) < 1e-9, "Round trip of " + c.name());
        }
        check(back.people.mayor == p.mayor && back.people.clock == p.clock && back.people.nextId == p.nextId, "Mayor, clock and ids round trip");
        CompoundTag old = s.save();
        old.remove("people");
        Settlement legacy = Settlement.load(old);
        check(!legacy.people.started && legacy.people.all.isEmpty(), "An old save loads with no citizens");
        Citizens.step(legacy, 0, r);
        check(legacy.people.started && legacy.people.adults().size() == legacy.population && legacy.people.mayor() != null, "...and gets them on its first step");
        Settlement blank = Settlement.load(new Settlement().save());
        check(!blank.people.started, "A town saved without people loads safely");

        // ---- the count follows the population, whatever changes it
        int mayor = p.mayor;
        s.population += 3;
        Citizens.step(s, 0, r);
        check(p.adults().size() == 15, "Newcomers when the population rises");
        s.population -= 6;
        Citizens.step(s, 0, r);
        check(p.adults().size() == 9 && p.mayor == mayor, "Leavers when it falls, never the mayor: " + p.adults().size());

        // ---- a wedding, and a mayor's wedding is chronicled
        Citizen m = p.mayor();
        Citizen sp = p.get(m.spouse);
        if (sp != null) { sp.spouse = 0; m.spouse = 0; }
        Citizen single = null;
        for (Citizen c : p.adults()) if (c != m && c.spouse == 0 && !Citizens.siblings(c, m)) { single = c; break; }
        if (single == null) { s.population++; Citizens.step(s, 0, r); final Citizen mm = m; single = p.adults().stream().filter(c -> c != mm && c.spouse == 0).findFirst().orElseThrow(); }
        long wedBefore = p.weddings;
        Citizens.wedding(s, m, single);
        check(m.spouse == single.id && single.spouse == m.id && p.weddings == wedBefore + 1, "Married");
        check(count(s, "married") == 1, "The mayor's wedding is in the chronicle");
        // weddings happen on their own among the unmarried
        s.population = 16;
        Citizens.step(s, 0, r);
        long before = p.weddings;
        for (int i = 0; i < 60 && p.weddings == before; i++) Citizens.wed(s, r);
        check(p.weddings > before, "Unmarried adults marry over the days");

        // ---- births when there is room and food; none when starving
        s.population = 24;
        s.baseHousing = 60;
        Simulation.recompute(s);
        Citizens.step(s, 0, r);
        List<Citizen> young = p.adults().stream().filter(x -> x.spouse == 0 && x.id != p.mayor).limit(2).toList();
        check(young.size() == 2, "Two unmarried adults for a young couple");
        for (Citizen x : young) x.born = p.clock - 25 / People.YEARS_PER_DAY;
        Citizens.wedding(s, young.get(0), young.get(1));
        check(Citizens.roomForChildren(s), "Room for children: housing " + s.d.housing + ", children " + p.children().size());
        int born = p.births;
        for (int i = 0; i < 40 && p.births == born; i++) Citizens.births(s, r);
        check(p.births > born, "A child is born");
        Citizen baby = p.children().stream().max(Comparator.comparingDouble(c -> c.born)).orElseThrow();
        check(baby.age(p.clock) == 0 && !baby.grown && (p.get(baby.mother) != null && p.get(baby.father) != null), "The newborn has two parents in town");
        check(p.get(baby.father).family.equals(baby.family) || p.get(baby.mother).family.equals(baby.family), "The child takes a parent's family name");
        s.starving = true;
        check(!Citizens.roomForChildren(s), "No births while starving");
        int frozen = p.births;
        for (int i = 0; i < 40; i++) Citizens.births(s, r);
        check(p.births == frozen, "...none at all");
        s.starving = false;

        // ---- growing up through the town's growth
        int pop = s.population;
        baby.born = p.clock - 17 / People.YEARS_PER_DAY;
        check(Citizens.growthBonus(s) > 0, "A youth of age speeds growth");
        s.population++;
        String news = Citizens.arrived(s, r);
        check(baby.grown && news.contains(baby.name()) && news.contains("came of age"), "The youth comes of age when the town grows: " + news);
        check(p.adults().size() == s.population && s.population == pop + 1, "...and is counted in the population");
        Citizens.step(s, 0, r);
        check(baby.job != null && p.adults().contains(baby), "The grown child takes a place in the jobs list");
        Citizen newcomer;
        s.population++;
        news = Citizens.arrived(s, r);
        newcomer = p.all.get(p.all.size() - 1);
        check(news.contains("arrived") && newcomer.grown && newcomer.age(p.clock) >= 18, "With no youth waiting, a newcomer arrives: " + news);

        // ---- old age, mourning, the mayor's death and the next election
        for (Citizen x : p.adults()) { x.traits.remove(Trait.SICKLY); if (x.age(p.clock) > 50) x.born = p.clock - 40 / People.YEARS_PER_DAY; }
        m = p.mayor();
        Citizen widow = p.get(m.spouse);
        m.born = p.clock - 96 / People.YEARS_PER_DAY;
        pop = s.population;
        String mayorName = m.name();
        Citizens.age(s, r);
        check(!p.all.contains(m) && s.population == pop - 1, "The very old die and leave the population");
        check(p.memorial.stream().anyMatch(x -> x.getString("n").equals(mayorName) && x.getString("c").equals("of old age")), "Remembered");
        check(widow != null && widow.spouse == 0 && widow.griefUntil > p.clock, "The widow grieves");
        List<String> why = new ArrayList<>();
        Citizens.mood(s, widow, why);
        check(why.stream().anyMatch(w -> w.contains("grieving")), "Grief shows in their mood: " + why);
        check(count(s, "Mayor " + mayorName + " died") == 1, "The mayor's death is chronicled");
        check(p.mayor == 0, "The office is vacant");
        Citizens.step(s, 0, r);
        check(p.mayor() != null && p.mayor().id != m.id && count(s, "as their mayor") == 2, "A new mayor is chosen at once");
        // ordinary deaths are not chronicled
        long chron = people(s);
        Citizen old1 = p.adults().stream().filter(c -> c.id != p.mayor).findFirst().orElseThrow();
        old1.born = p.clock - 97 / People.YEARS_PER_DAY;
        Citizens.age(s, r);
        check(!p.all.contains(old1) && people(s) == chron, "An ordinary death is not chronicled");
        // a death in a fire (the settler hook)
        Citizen burnt = p.adults().stream().filter(c -> c.id != p.mayor).findFirst().orElseThrow();
        pop = s.population;
        Citizens.killed(s, burnt.id, "in a fire");
        check(!p.all.contains(burnt) && p.memorial.peekFirst().getString("c").equals("in a fire") && s.population == pop, "A settler's death in a fire is recorded (the settler lowers the count)");

        // ---- fear after a fire and a raid
        Citizens.step(s, 0, r);
        double calm = Citizens.average(s);
        Chronicle.record(s, Chronicle.Kind.FIRE, "A fire started by a candle took the bakery.");
        Citizens.step(s, 0, r);
        check(p.shock == 20 && p.shockWhy.equals("the fire"), "A fire frightens the town: " + p.shock);
        double scared = Citizens.average(s);
        check(scared <= calm - 9, "Moods fall after the fire: " + calm + " -> " + scared);
        Citizen brave = null, timid = null;
        for (Citizen c : p.all) { if (c.has(Trait.BRAVE) && brave == null) brave = c; if (!c.has(Trait.BRAVE) && timid == null) timid = c; }
        if (brave == null) { brave = p.all.get(0); brave.traits.clear(); brave.traits.add(Trait.BRAVE); timid = p.all.get(1); timid.traits.remove(Trait.BRAVE); }
        why.clear();
        Citizens.mood(s, brave, why);
        check(why.stream().anyMatch(w -> w.equals("-10 fear after the fire")), "The brave feel half the fear: " + why);
        why.clear();
        Citizens.mood(s, timid, why);
        check(why.stream().anyMatch(w -> w.equals("-20 fear after the fire")), "Others feel all of it: " + why);
        Simulation.recompute(s);
        check(s.d.happinessReasons.stream().anyMatch(w -> w.contains("fear after the fire")), "The town's mood counts the fear: " + s.d.happinessReasons);
        for (int i = 0; i < 4; i++) Citizens.step(s, 1, r);
        check(p.shock == 0, "The fear fades in four days: " + p.shock);
        Settlement rival = new Settlement(); rival.name = "Ashford";
        Chronicle.raidEnded(rival, s, false, "The raid was repelled");
        Citizens.step(s, 0, r);
        check(p.shock == 12 && p.shockWhy.equals("the raid"), "A raid held off frightens the town too: " + p.shock);
        Citizens.step(s, 0, r);
        check(p.shock == 12, "...once, not every step");

        // ---- elections and appointments
        Citizen star = p.adults().stream().filter(c -> c.id != p.mayor).findFirst().orElseThrow();
        star.born = p.clock - 58 / People.YEARS_PER_DAY;
        star.traits.clear(); star.traits.add(Trait.HARDWORKING); star.traits.add(Trait.CHEERFUL);
        star.job = Job.PRIEST;
        Citizen incumbent = p.mayor();
        incumbent.traits.clear(); incumbent.traits.add(Trait.SICKLY);
        incumbent.job = Job.IDLE;
        check(Citizens.respect(s, star) > Citizens.respect(s, incumbent) + 5, "The challenger is more respected");
        Citizens.elect(s, true);
        Citizen top = p.adults().stream().max(Comparator.comparingDouble(x -> Citizens.respect(s, x))).orElseThrow();
        check(p.mayor == top.id && p.mayor != incumbent.id && count(s, "in place of " + incumbent.name()) == 1, "The most respected citizen wins the election");
        notes.add("elected " + top.name() + " (respect " + Math.round(Citizens.respect(s, top)) + ")");
        long elections = people(s);
        Citizens.elect(s, true);
        check(p.mayor == star.id && people(s) == elections, "Re-electing the incumbent writes nothing");
        Citizen pick = p.adults().stream().filter(c -> c.id != p.mayor).findFirst().orElseThrow();
        check(Citizens.appointMayor(s, pick.id, "Aldric") == null && p.mayor == pick.id && p.mayorAppointed, "The ruler appoints a mayor");
        check(count(s, "By decree of Aldric, " + pick.name()) == 1, "The appointment is chronicled");
        Citizens.elect(s, true);
        check(p.mayor == pick.id, "No election unseats an appointed mayor");
        Citizen kid = p.children().stream().filter(c -> c.child(p.clock)).findFirst().orElse(null);
        if (kid != null) check(Citizens.appointMayor(s, kid.id, "Aldric") != null && p.mayor == pick.id, "A child cannot be mayor");
        Citizens.holdElection(s);
        check(!p.mayorAppointed && p.mayor() != null, "Calling an election hands the choice back");
        for (int i = 0; i < Citizens.ELECTION_DAYS + 1; i++) Citizens.step(s, 1, r);
        check(p.clock - p.lastElection < Citizens.ELECTION_DAYS, "Elections come round every " + Citizens.ELECTION_DAYS + " days");

        // ---- the mayor's bonus and loyalty
        Citizen mayorNow = p.mayor();
        mayorNow.traits.clear(); mayorNow.traits.add(Trait.GREEDY);
        check(Citizens.taxBonus(s) == 1.15, "A greedy mayor squeezes taxes");
        Simulation.recompute(s);
        check(s.d.happinessReasons.contains("-2 greedy mayor"), "...and the town feels it");
        List<Map.Entry<String, Double>> loyalty = new ArrayList<>();
        double keep = s.happiness;
        s.happiness = 10;
        Citizens.step(s, 0, r);
        Citizens.loyalty(s, loyalty);
        check(loyalty.stream().anyMatch(e -> e.getKey().equals("a hated mayor") && e.getValue() < 0), "A greedy mayor of a miserable town costs loyalty: " + loyalty);
        s.happiness = keep;
        mayorNow.traits.clear(); mayorNow.traits.add(Trait.HARDWORKING); mayorNow.traits.add(Trait.CHEERFUL);
        check(Citizens.buildBonus(s) >= 1.08 && Citizens.taxBonus(s) == 1, "A hardworking mayor speeds the builders");
        Simulation.recompute(s);
        check(s.d.happinessReasons.contains("+4 cheerful mayor"), "A cheerful mayor cheers the town");
        Citizens.step(s, 0, r);
        loyalty.clear();
        Citizens.loyalty(s, loyalty);
        check(loyalty.stream().noneMatch(e -> e.getValue() < 0), "A good mayor of a content town costs nothing: " + loyalty);

        // ---- the captain: the best soldier, or the ruler's choice
        Settlement.Soldier a = soldier(s, "Footman Bran", 2, 0), b = soldier(s, "Footman Gareth", 14, 300), c = soldier(s, "Bowman Isolde", 5, 0);
        Citizens.captain(s, false);
        check(b.entity.equals(p.captain) && !p.captainAppointed && count(s, "Footman Gareth") == 1, "The best soldier captains the guard");
        Citizens.captain(s, false);
        check(count(s, "captain of the guard") == 1, "...named once");
        check(Citizens.appointCaptain(s, c.entity, "Aldric") == null && c.entity.equals(p.captain) && p.captainAppointed, "The ruler appoints a captain");
        Citizens.captain(s, true);
        check(c.entity.equals(p.captain), "An appointed captain is kept");
        check(Citizens.appointCaptain(s, UUID.randomUUID(), "Aldric") != null, "Unknown soldiers cannot be appointed");
        s.soldiers.remove(c);
        Citizens.captain(s, false);
        check(b.entity.equals(p.captain) && !p.captainAppointed, "When the captain falls the best soldier takes over");

        // ---- a citizen's request on the bounty board
        Citizen smith = p.adults().get(0);
        smith.job = Job.SMITH;
        Requests.open(p, smith, Items.IRON_INGOT, 16, "the smith wants iron for a new anvil");
        QuestData data = new QuestData();
        long now = server.overworld().getGameTime();
        Bounties.refresh(data, new SettlementManager(), s, now, r, null);
        QuestData.Bounty ask = data.board(s.id).bounties.stream().filter(x -> x.reason.contains(smith.name())).findFirst().orElse(null);
        check(ask != null && ask.kind == QuestData.Kind.DELIVER && ask.item == Items.IRON_INGOT && ask.need == 16 && ask.reward > 0, "The request is posted as a delivery bounty");
        check(data.board(s.id).bounties.stream().filter(x -> x.reason.contains(" asks: ")).count() == 1, "...once");
        check(Requests.bounty(s, now, 1000) == null, "Not posted twice");
        Requests.done(s, ask);
        check(smith.gladUntil > p.clock && p.askBy == 0, "Granting it gladdens the citizen");
        why.clear();
        Citizens.mood(s, smith, why);
        check(why.contains("+12 a favour granted"), "...as their mood says: " + why);
        int asked = 0;
        for (int i = 0; i < 200; i++) { Requests.daily(s, r); if (p.askBy > 0) { asked++; Requests.clear(p); } }
        check(asked > 10 && asked < 90, "Requests come now and then: " + asked + " in 200 days");

        // ---- many days on records only: bounded, no chronicle spam, life goes on
        long before60 = people(s);
        int births0 = p.births, deaths0 = p.deaths;
        s.population = 30;
        s.baseHousing = 80;
        Simulation.recompute(s);
        int roomDays = 0, coupleDays = 0;
        for (int day = 0; day < 120; day++) {
            if (Citizens.roomForChildren(s)) roomDays++;
            if (p.adults().stream().anyMatch(x -> x.spouse > 0 && x.age(p.clock) <= 46 && p.get(x.spouse) != null && p.get(x.spouse).age(p.clock) <= 46)) coupleDays++;
            Citizens.step(s, 1, r);
            // the town's own growth still moves people in
            if (s.population < s.d.housing) { s.population++; Citizens.arrived(s, r); }
        }
        check(p.births > births0 && p.deaths > deaths0, "Over 120 days children are born and the old die: +" + (p.births - births0) + " / -" + (p.deaths - deaths0));
        check(p.adults().size() == Math.min(s.population, People.MAX_ADULTS), "Records still match the population");
        check(p.children().size() <= People.MAX_CHILDREN && p.memorial.size() <= People.MEMORIAL, "Children and memorial bounded");
        long entries = people(s) - before60, events = (p.births - births0) + (p.deaths - deaths0);
        notes.add("people entries: " + count(s, "died") + " deaths, " + count(s, "elected") + " elections, " + count(s, "chose") + " vacancies filled, "
                + count(s, "married") + " weddings, " + count(s, "captain") + " captains");
        check(entries <= 30 && entries * 3 < events, "The chronicle is not flooded: " + entries + " people entries for " + events + " births and deaths in 120 days");
        notes.add(entries + " chronicle entries in 120 days");
        check(p.all.stream().filter(x -> x.grown).allMatch(x -> x.age(p.clock) >= Citizen.ADULT_AGE), "Only the grown count");
        notes.add("room on " + roomDays + " days, young couples on " + coupleDays + ", children cap " + Citizens.childCap(s) + ", kids " + p.children().size() + ", pop " + s.population + ", housing " + s.d.housing);
        notes.add("120 days: " + (p.births - births0) + " born, " + (p.deaths - deaths0) + " died, " + p.weddings + " weddings in all");

        Settlement big = town("Bigtown", 500, new BlockPos(4096, 100, 8192));
        big.baseHousing = 2000;
        Simulation.recompute(big);
        Citizens.step(big, 0, r);
        check(big.people.adults().size() == People.MAX_ADULTS, "Records are capped at " + People.MAX_ADULTS);
        for (int day = 0; day < 40; day++) Citizens.step(big, 1, r);
        check(big.people.children().size() <= People.MAX_CHILDREN && big.people.adults().size() == People.MAX_ADULTS, "Big towns stay bounded: " + big.people.children().size());
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) Citizens.step(big, 0.05, r);
        long micros = (System.nanoTime() - t0) / 20_000;
        check(micros < 20_000, "A step of a 500-soul town is cheap: " + micros + " us");
        notes.add("500-soul step " + micros + " us");
        check(Citizens.view(big, FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.randomUUID(), "Viewer"))).getList("people", 10).size() == big.people.all.size(), "The City Manager lists everyone");

        settler(server);
    }

    static Settlement.Soldier soldier(Settlement s, String name, int kills, int skillXp) {
        Settlement.Soldier so = new Settlement.Soldier();
        so.entity = UUID.randomUUID(); so.type = UnitType.FOOTMAN; so.name = name; so.kills = kills; so.skillXp = skillXp;
        s.soldiers.add(so);
        return so;
    }

    /** A visible settler takes a citizen's name (shown on hover), and its death is that citizen's. */
    static void settler(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos at = level.getSharedSpawnPos();
        at = new BlockPos(at.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()), at.getZ());
        SettlementManager m = SettlementManager.get(server);
        Settlement s = town("Spawnwick", 6, at);
        s.dim = level.dimension();
        m.add(s);
        try {
            Citizens.step(s, 0, new LegacyRandomSource(7));
            SettlerEntity.spawn(level, s, Job.FARMER, at);
            List<SettlerEntity> found = level.getEntitiesOfClass(SettlerEntity.class, new AABB(at).inflate(8), e -> s.id.equals(e.settlementId()));
            check(found.size() == 1, "A settler was spawned");
            SettlerEntity e = found.get(0);
            Citizen who = s.people.get(e.citizen());
            check(who != null && e.getCustomName() != null && e.getCustomName().getString().equals(who.name()), "The settler carries a citizen's name");
            check(!e.isCustomNameVisible(), "The name tag shows only when looked at");
            check(s.people.shown.contains(who.id), "That citizen is marked as out and about");
            int pop = s.population;
            e.hurt(level.damageSources().inFire(), 1000);
            check(s.people.get(who.id) == null && s.population == pop - 1, "The settler's death is the citizen's death");
            check(s.people.memorial.peekFirst().getString("c").equals("in a fire"), "Died in a fire: " + s.people.memorial.peekFirst());
            // the real simulation step: growth brings someone named, and the rolls follow
            s.happiness = 80; s.growth = 0.9999;
            pop = s.population;
            Simulation.step(server, s);
            check(s.population == pop + 1 && s.log.stream().limit(6).anyMatch(l -> l.contains("arrived from the road") || l.contains("came of age")),
                    "Growth in the simulation names who came: " + s.log);
            check(s.people.adults().size() == s.population, "The simulation keeps the rolls in step");
            check(s.d.happinessReasons != null && Simulation.speed(s) > 0, "Simulation figures still compute with citizens");
        } finally {
            m.remove(s);
            for (SettlerEntity e : level.getEntitiesOfClass(SettlerEntity.class, new AABB(at).inflate(16))) e.discard();
        }
    }

    private CitizenSelfTest() {}
}
