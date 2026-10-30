package livingrealms.settlement;

import livingrealms.LivingRealms;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.Category;
import livingrealms.chronicle.Chronicle;
import livingrealms.hardship.HardshipConfig;
import livingrealms.hardship.Hardships;
import livingrealms.quest.Bounties;
import livingrealms.quest.QuestData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Town hardships (docs/hardships.md): ./gradlew runServer -PhardshipTest -PhardshipTestDir=&lt;fresh dir&gt;; writes
 * hardship-test-result.txt. Outbreaks and what raises them, spread and recovery with and without care, caravans carrying
 * sickness, quarantine shutting routes, bad harvests, hunger and its end by imports, rats, bounties, the chronicle, the
 * config switches and save/load. All on a private settlement manager.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class HardshipSelfTest {
    private static int checks;
    private static void check(boolean value, String detail) {
        if (!value) throw new IllegalStateException(detail);
        checks++;
    }

    static final List<String> events = new ArrayList<>();
    static final long DAY = 24000;

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.hardshipTest")) return;
        MinecraftServer server = event.getServer();
        String report;
        Throwable error = null;
        try {
            Hardships.listen((s, e, d) -> events.add(s.name + ":" + e + ":" + d));
            sickness(server);
            caravans(server);
            famine(server);
            vermin(server);
            switches(server);
            report = "PASS: " + checks + " hardship assertions";
        } catch (Throwable t) {
            error = t;
            report = "FAIL after " + checks + " assertions: " + t;
        }
        defaults();
        if (error == null) LivingRealms.LOG.info("HARDSHIP SELF TEST {}", report);
        else LivingRealms.LOG.error("HARDSHIP SELF TEST FAILED", error);
        try { Files.writeString(Path.of("hardship-test-result.txt"), report); } catch (Exception ex) { throw new RuntimeException(ex); }
        server.halt(false);
    }

    static void defaults() {
        HardshipConfig.ENABLED.set(true); HardshipConfig.SICKNESS.set(true); HardshipConfig.HARVEST.set(true); HardshipConfig.VERMIN.set(true);
        HardshipConfig.SICK_CHANCE.set(0.01); HardshipConfig.CARAVAN_CONTAGION.set(0.35); HardshipConfig.DEATH_RATE.set(0.03);
        HardshipConfig.HARVEST_CHANCE.set(0.02); HardshipConfig.VERMIN_CHANCE.set(0.01); HardshipConfig.AI_QUARANTINE.set(true);
    }

    /** Only sickness rolls: harvests and rats off, so the numbers below are the outbreak's alone. */
    static void onlySickness() {
        defaults();
        HardshipConfig.HARVEST.set(false); HardshipConfig.VERMIN.set(false);
    }

    static Settlement town(SettlementManager m, UUID owner, String name, String style, int x, int pop) {
        Settlement s = new Settlement(); s.owner = owner; s.ownerName = owner == null ? "" : name;
        s.name = name; s.style = style; s.center = new BlockPos(x, 100, 20000); s.population = pop;
        m.add(s);
        Simulation.recompute(s);
        return s;
    }

    static Plot plot(Settlement s, String type) {
        Plot p = new Plot(); p.id = s.nextId++; p.typeId = type; p.blueprintKey = BuildingCatalog.get(type).blueprintKey(1, s.style);
        p.origin = s.center.offset(20 * p.id, 0, 0); p.state = Plot.State.BUILT; s.plots.add(p);
        return p;
    }

    static void empty(Settlement s) {
        for (var it : new ArrayList<>(s.stock.view().keySet())) s.stock.takeUpTo(it, s.stock.count(it));
    }

    static Chronicle.Entry last(Settlement s) { return s.chronicle.entries.isEmpty() ? null : s.chronicle.entries.get(s.chronicle.entries.size() - 1); }

    // ------------------------------------------------------------------ sickness

    static void sickness(MinecraftServer server) {
        onlySickness();
        RandomSource rand = RandomSource.create(7);
        SettlementManager m = new SettlementManager();
        long now = 10 * DAY;
        UUID owner = UUID.randomUUID();
        Settlement a = town(m, owner, "Mudbury", "minecraft:plains", 30000, 30);
        Settlement tiny = town(m, owner, "Hut", "minecraft:plains", 31000, 3);
        check(Hardships.sickChance(tiny, now) == 0, "A camp of three never falls sick");
        check(a.population > a.d.housing, "Mudbury is overcrowded: " + a.population + " in room for " + a.d.housing);
        double base = Hardships.sickChance(a, now);
        check(base > HardshipConfig.SICK_CHANCE.get(), "A crowded town of 30 is likelier than the base to fall sick: " + base);
        a.hardship.lastSiege = now - DAY;
        double sieged = Hardships.sickChance(a, now);
        check(Math.abs(sieged - base * 3) < 1e-9, "A siege in the last three days triples the chance: " + base + " -> " + sieged);
        check(!Hardships.besiegedLately(a, now + 3 * DAY), "The siege is forgotten after three days");
        a.hardship.lastSiege = Long.MIN_VALUE / 2;
        a.stock.force(Items.BREAD, 10); a.stock.force(Items.COOKED_BEEF, 10); a.stock.force(Items.CARROT, 10); a.stock.force(Items.APPLE, 10);
        check(Hardships.sickChance(a, now) < base, "Varied food lowers it");
        a.stock.take(Items.COOKED_BEEF, 10); a.stock.take(Items.CARROT, 10); a.stock.take(Items.APPLE, 10);
        check(Hardships.label(sieged).equals("Moderate") || Hardships.label(sieged).equals("High"), "A sieged crowded town's risk reads Moderate or High: " + Hardships.label(sieged));

        // a raid that is a siege marks the town
        Raids.Raid r = new Raids.Raid();
        r.source = tiny.id; r.target = a.id; r.attacker = UUID.randomUUID(); r.defender = owner; r.siege.walled = true;
        m.raids.add(r);
        Hardships.step(server, m, a, 0.0, now, rand);
        check(a.hardship.lastSiege == now, "A siege under way is remembered for the sickness after it");
        m.raids.clear();
        a.hardship.lastSiege = Long.MIN_VALUE / 2;

        // the roll: a certain chance starts it
        HardshipConfig.SICK_CHANCE.set(1.0);
        events.clear();
        Hardships.step(server, m, a, 1.0, now, rand);
        check(a.hardship.sick >= 1 && !a.hardship.sickName.isEmpty(), "A certain outbreak starts: " + a.hardship.sick + " sick");
        check(events.stream().anyMatch(e -> e.startsWith("Mudbury:OUTBREAK")), "Listeners hear of the outbreak: " + events);
        check(a.log.stream().anyMatch(l -> l.startsWith("Sickness in Mudbury")), "The town log has it");
        HardshipConfig.SICK_CHANCE.set(0.01);
        a.hardship.sick = 0; a.hardship.total = 0;

        // ---- course of an outbreak, without care and with every care
        HardshipConfig.DEATH_RATE.set(0.15);
        Settlement bare = town(m, owner, "Bareford", "minecraft:plains", 32000, 30);
        Settlement cared = town(m, owner, "Saintwell", "minecraft:plains", 33000, 30);
        plot(cared, "livingrealms:chapel");
        Plot apo = plot(cared, Hardships.APOTHECARY);
        check(BuildingCatalog.get(Hardships.APOTHECARY) != null && BuildingCatalog.get(Hardships.APOTHECARY).category == Category.DECOR
                && "herb_lore".equals(BuildingCatalog.get(Hardships.APOTHECARY).research), "The Apothecary exists and needs Herb Lore");
        check(apo.blueprint() != null && apo.blueprint().size() > 50, "The Apothecary has a blueprint");
        cared.research.add(Hardships.RESEARCH);
        Simulation.recompute(bare); Simulation.recompute(cared);
        check(Hardships.prevention(cared) < .3 && Hardships.prevention(bare) == 1, "Chapel, apothecary and Herb Lore cut the risk: " + Hardships.prevention(cared));
        check(Hardships.sickChance(cared, now) < Hardships.sickChance(bare, now) * .3, "A cared-for town is far less likely to fall sick");
        Hardships.outbreak(bare, now, "", rand);
        Hardships.outbreak(cared, now, "", rand);
        check(bare.hardship.sick == cared.hardship.sick && bare.hardship.sick >= 2, "Both start with the same sick: " + bare.hardship.sick);
        check(Hardships.spread(cared, now) < Hardships.spread(bare, now) * .5, "Care slows the spread");
        check(Hardships.recovery(cared) > Hardships.recovery(bare) * 2, "Care speeds recovery");
        check(Hardships.deaths(cared) < Hardships.deaths(bare) * .5, "Care saves lives");
        // effects while sick
        double work = Hardships.workFactor(bare), speed = Simulation.speed(bare);
        check(work < 1 && Hardships.output(bare, Category.MINE) < 1, "The sick work slower: " + work);
        Simulation.recompute(bare);
        check(bare.d.happinessReasons.stream().anyMatch(w -> w.contains("sickness")), "Sickness shows under happiness: " + bare.d.happinessReasons);
        CompoundTag v = Hardships.view(bare, now);
        check(v.getList("troubles", 10).size() == 1 && v.getList("troubles", 10).getCompound(0).getString("name").equals("Sickness"), "The City Manager lists the sickness");
        // remedies in store are used
        cared.stock.force(Items.HONEY_BOTTLE, 20);
        int popBare = bare.population, popCared = cared.population;
        int bareDays = -1, caredDays = -1;
        double barePeak = 0, caredPeak = 0;
        events.clear();
        for (int i = 1; i <= 120 && (bareDays < 0 || caredDays < 0); i++) {
            long t = now + i * DAY / 4;
            if (bareDays < 0) { Hardships.step(server, m, bare, .25, t, rand); barePeak = Math.max(barePeak, bare.hardship.sick); if (bare.hardship.sick == 0) bareDays = i; }
            if (caredDays < 0) { Hardships.step(server, m, cared, .25, t, rand); caredPeak = Math.max(caredPeak, cared.hardship.sick); if (cared.hardship.sick == 0) caredDays = i; }
        }
        check(bareDays > 0 && caredDays > 0, "Both outbreaks end: " + bareDays + " / " + caredDays + " quarter-days");
        check(caredDays < bareDays, "With care it is over sooner: " + caredDays / 4.0 + " days against " + bareDays / 4.0);
        check(barePeak > caredPeak, "Without care more fall ill at once: peak " + barePeak + " against " + caredPeak);
        int deadBare = popBare - bare.population, deadCared = popCared - cared.population;
        check(deadBare > deadCared && deadBare >= 1, "Without care more die: " + deadBare + " against " + deadCared);
        check(events.stream().filter(e -> e.startsWith("Bareford:DEATH")).count() == deadBare, "Each death is announced to listeners: " + events);
        check(cared.stock.count(Items.HONEY_BOTTLE) < 20, "Remedies were taken from the stockpile: " + cared.stock.count(Items.HONEY_BOTTLE) + " honey left");
        Chronicle.Entry e = last(bare);
        check(e != null && e.kind() == Chronicle.Kind.DISASTER && e.text().contains("died") && e.text().contains("Bareford"), "The outbreak is chronicled: " + e);
        Chronicle.Entry ec = last(cared);
        check(ec == null || ec.text().contains("apothecary"), "A small outbreak is not news; a big one credits the apothecary: " + ec);
        LivingRealms.LOG.info("Hardship test: bare outbreak {} days, peak {}, {} dead; cared {} days, peak {}, {} dead",
                bareDays / 4.0, barePeak, deadBare, caredDays / 4.0, caredPeak, deadCared);
        check(Hardships.workFactor(bare) == 1 && Hardships.mood(bare, new ArrayList<>()) == 0, "Once over, work and mood are back to normal");
        HardshipConfig.DEATH_RATE.set(0.03);

        // bounties for medicine
        QuestData qd = new QuestData();
        Hardships.outbreak(bare, now, "", rand);
        Bounties.refresh(qd, m, bare, now, rand, null);
        check(qd.board(bare.id).bounties.stream().anyMatch(x -> x.item == Items.HONEY_BOTTLE && x.reason.contains("remedies")),
                "A sick town's bounty board asks for honey: " + qd.board(bare.id).bounties.stream().map(Bounties::title).toList());
        check(Trade.wants(bare).getOrDefault(Items.HONEY_BOTTLE, 0) > 0, "A sick town wants remedies by caravan");
        bare.hardship.sick = 0;

        // NPC towns close their roads while sick
        Settlement npc = town(m, null, "Greyholm", "minecraft:plains", 34000, 20);
        npc.tier = Tier.HAMLET;
        Hardships.outbreak(npc, now, "", rand);
        Hardships.step(server, m, npc, 0.01, now, rand);
        check(npc.hardship.quarantine && npc.hardship.autoQuarantine, "A sick NPC town quarantines itself");
        npc.hardship.sick = 0.1;
        Hardships.step(server, m, npc, 0.01, now, rand);
        check(npc.hardship.sick == 0 && !npc.hardship.quarantine, "And opens its roads when well");
    }

    // ------------------------------------------------------------------ caravans and quarantine

    static void caravans(MinecraftServer server) {
        onlySickness();
        RandomSource rand = RandomSource.create(11);
        SettlementManager m = new SettlementManager();
        long now = 20 * DAY;
        Settlement plains = town(m, UUID.randomUUID(), "Wheatford", "minecraft:plains", 40000, 10);
        Settlement desert = town(m, null, "Sandmouth", "minecraft:desert", 40300, 10);
        for (int i = 0; i < 6; i++) Trade.produce(desert, 1.0);
        Trade.routes(server, m, now);
        Trade.Route r = m.trade.route(plains.id, desert.id);
        check(r != null && r.active, "The two towns trade");
        check(Hardships.quarantine(plains, true) == null && plains.hardship.quarantine, "The ruler orders a quarantine");
        Trade.routes(server, m, now);
        check(!r.active && r.blocked.equals("Quarantine in Wheatford"), "Quarantine shuts the route: " + r.blocked);
        Trade.step(server, m, now, Trade.STEP / 24000.0);
        check(m.trade.caravans.isEmpty(), "No caravan sets out to or from a quarantined town");
        Hardships.quarantine(plains, false);
        Trade.routes(server, m, now);
        check(r.active, "Lifting the quarantine reopens the road");
        desert.stock.force(Items.GLASS, 150);
        Trade.Caravan c = Trade.dispatch(server, m, r, now);
        check(c != null && c.to.equals(plains.id), "And caravans go again: " + (c == null ? null : Trade.summary(c.cargo)));

        // sickness on the road
        HardshipConfig.CARAVAN_CONTAGION.set(1.0);
        Hardships.outbreak(desert, now, "", rand);
        plains.hardship.quarantine = true;
        Hardships.caravanArrived(desert, plains, c.cargo, now, rand);
        check(plains.hardship.sick == 0, "A quarantined town does not catch it");
        plains.hardship.quarantine = false;
        HardshipConfig.CARAVAN_CONTAGION.set(0.0);
        Hardships.caravanArrived(desert, plains, c.cargo, now, rand);
        check(plains.hardship.sick == 0, "With no contagion chance nothing is carried");
        HardshipConfig.CARAVAN_CONTAGION.set(1.0);
        Trade.deliver(server, m, c, now);
        check(plains.hardship.sick >= 1 && plains.hardship.sickCause.equals("a caravan from Sandmouth"),
                "A caravan from a sick town brings the sickness: " + plains.hardship.sick + " (" + plains.hardship.sickCause + ")");
        check(c.status == Trade.Status.ARRIVED, "The caravan still delivered");
        HardshipConfig.CARAVAN_CONTAGION.set(0.35);
    }

    // ------------------------------------------------------------------ harvest and hunger

    static void famine(MinecraftServer server) {
        defaults();
        HardshipConfig.SICKNESS.set(false); HardshipConfig.VERMIN.set(false); HardshipConfig.HARVEST_CHANCE.set(0.0);
        RandomSource rand = RandomSource.create(3);
        SettlementManager m = new SettlementManager();
        long now = 40 * DAY;
        UUID owner = UUID.randomUUID();
        Settlement f = town(m, owner, "Dustwell", "minecraft:desert", 50000, 10);
        plot(f, "livingrealms:farm_field");
        plot(f, "livingrealms:farm_field");
        Simulation.recompute(f);
        check(f.d.workers.getOrDefault(Job.FARMER, 0) > 0, "Dustwell has farmers");
        check(Hardships.harvestKind(f) == Hardships.Harvest.DROUGHT && Hardships.harvestKind(town(m, owner, "Pinecold", "minecraft:snowy_plains", 51000, 5)) == Hardships.Harvest.COLD_SNAP
                && Hardships.harvestKind(town(m, owner, "Greenmead", "minecraft:plains", 52000, 5)) == Hardships.Harvest.BLIGHT, "Drought in the desert, cold snaps in the snow, blight elsewhere");
        Simulation.produce(f, f.d, 1.0);
        int good = f.stock.count(Items.BREAD) + f.stock.count(Items.CARROT) + f.stock.count(Items.POTATO);
        empty(f);
        HardshipConfig.HARVEST_CHANCE.set(1.0);
        events.clear();
        Hardships.step(server, m, f, 1.0, now, rand);
        check(f.hardship.harvest == Hardships.Harvest.DROUGHT && f.hardship.harvestUntil > now, "A certain chance brings a drought");
        check(events.contains("Dustwell:HARVEST_FAILED:drought"), "Listeners hear of it: " + events);
        HardshipConfig.HARVEST_CHANCE.set(0.0);
        Simulation.produce(f, f.d, 1.0);
        int bad = f.stock.count(Items.BREAD) + f.stock.count(Items.CARROT) + f.stock.count(Items.POTATO);
        check(bad < good * .45 && bad > 0, "The drought cuts the harvest: " + good + " -> " + bad);
        double plain = Hardships.harvestLeft(f);
        f.research.add("granaries");
        plot(f, "livingrealms:storehouse");
        check(Hardships.harvestLeft(f) > plain + .2, "Granaries and a storehouse soften it: " + plain + " -> " + Hardships.harvestLeft(f));
        f.research.remove("granaries");
        f.plots.removeIf(p -> p.typeId.equals("livingrealms:storehouse"));
        Simulation.recompute(f);
        // NPC towns' trade goods too
        Settlement npc = town(m, null, "Oakvale", "minecraft:plains", 53000, 10);
        double bread = Trade.production(npc).get(Items.BREAD);
        npc.hardship.harvest = Hardships.Harvest.BLIGHT;
        check(Trade.production(npc).get(Items.BREAD) < bread * .5 && Trade.production(npc).get(Items.WHITE_WOOL) > 0, "Blight cuts an NPC town's bread, not its wool");
        npc.hardship.harvest = Hardships.Harvest.NONE;

        // the stores run out: hunger
        int wantBefore = Trade.wants(f).get(Items.BREAD);
        empty(f);
        Simulation.eat(f, f.d, 1.0);
        check(f.starving, "With nothing in store Dustwell starves");
        long hungryAt = now + DAY / 2;
        Hardships.step(server, m, f, 0.01, hungryAt, rand);
        check(f.hardship.hungry && f.hardship.hungerCause.equals("drought"), "Hunger begins, blamed on the drought");
        check(events.contains("Dustwell:HUNGER:drought"), "Listeners hear of the hunger");
        check(Hardships.workFactor(f) <= .6 && Simulation.speed(f) < 1, "The hungry build slower: " + Simulation.speed(f));
        Simulation.recompute(f);
        check(f.d.happinessTarget < 30 && f.d.happinessReasons.stream().anyMatch(w -> w.contains("starving")), "Mood falls: " + f.d.happinessReasons);
        check(Trade.wants(f).get(Items.BREAD) >= wantBefore + f.population * 3, "A hungry town's need for bread spikes: " + wantBefore + " -> " + Trade.wants(f).get(Items.BREAD));
        QuestData qd = new QuestData();
        Bounties.refresh(qd, m, f, now, rand, null);
        check(qd.board(f.id).bounties.stream().anyMatch(x -> x.item == Items.BREAD && x.reason.contains("hungry")), "The bounty board asks for bread");
        CompoundTag v = Hardships.view(f, hungryAt);
        check(v.getList("troubles", 10).size() == 2, "The City Manager lists the bad harvest and the hunger");

        // a caravan of bread ends it
        Settlement seller = town(m, null, "Breadly", "minecraft:plains", 50300, 10);
        Trade.Caravan c = new Trade.Caravan();
        c.from = seller.id; c.to = f.id; c.cargo.put(Items.BREAD, 96); c.value = 20; c.status = Trade.Status.ROAD;
        m.trade.caravans.add(c);
        Trade.deliver(server, m, c, hungryAt + DAY);
        check(f.hardship.imported == 96 && f.hardship.importFrom.equals("Breadly"), "The bread is counted as an import");
        Simulation.eat(f, f.d, 0.5);
        check(!f.starving, "Dustwell eats again");
        int before = f.chronicle.entries.size();
        Hardships.step(server, m, f, 0.01, hungryAt + DAY, rand);
        check(!f.hardship.hungry && events.contains("Dustwell:FED:Breadly"), "The hunger ends");
        Chronicle.Entry e = last(f);
        check(f.chronicle.entries.size() == before + 1 && e.kind() == Chronicle.Kind.DISASTER && e.text().contains("Breadly") && e.text().contains("drought"),
                "The famine is chronicled, with the caravans that ended it: " + e);
        // the drought passes
        Hardships.step(server, m, f, 0.01, f.hardship.harvestUntil + 1, rand);
        check(f.hardship.harvest == Hardships.Harvest.NONE && Hardships.harvestLeft(f) == 1, "The drought passes and the fields recover");
    }

    // ------------------------------------------------------------------ rats

    static void vermin(MinecraftServer server) {
        defaults();
        HardshipConfig.SICKNESS.set(false); HardshipConfig.HARVEST.set(false);
        RandomSource rand = RandomSource.create(5);
        SettlementManager m = new SettlementManager();
        long now = 60 * DAY;
        UUID owner = UUID.randomUUID();
        Settlement a = town(m, owner, "Ratley", "minecraft:plains", 60000, 10);
        Settlement b = town(m, owner, "Stonestore", "minecraft:plains", 61000, 10);
        plot(b, "livingrealms:storehouse");
        Simulation.recompute(b);
        check(Hardships.verminChance(a) == 0, "No rats where nothing is stored");
        a.stock.force(Items.WHEAT, 300); b.stock.force(Items.WHEAT, 300);
        check(Hardships.verminChance(b) == Hardships.verminChance(a) * .5 && Hardships.verminChance(a) > 0, "A storehouse halves the chance of rats");
        Hardships.vermin(a, now); Hardships.vermin(b, now);
        Hardships.step(server, m, a, 1.0, now + DAY, rand);
        Hardships.step(server, m, b, 1.0, now + DAY, rand);
        check(a.hardship.eaten > 20 && a.stock.count(Items.WHEAT) == 300 - a.hardship.eaten, "Rats eat the grain: " + a.hardship.eaten);
        check(b.hardship.eaten < a.hardship.eaten, "Less with a storehouse: " + b.hardship.eaten);
        Hardships.step(server, m, a, 1.0, now + 2 * DAY - 1, rand);
        Hardships.step(server, m, a, 1.0, now + 2 * DAY + 1, rand);
        check(a.hardship.verminUntil == 0, "The rats are dealt with after two days");
        Chronicle.Entry e = last(a);
        check(e != null && e.text().contains("Rats"), "Rats that ate this much are chronicled: " + e);
    }

    // ------------------------------------------------------------------ switches and saves

    static void switches(MinecraftServer server) {
        defaults();
        RandomSource rand = RandomSource.create(9);
        SettlementManager m = new SettlementManager();
        long now = 80 * DAY;
        Settlement s = town(m, UUID.randomUUID(), "Savehaven", "minecraft:taiga", 70000, 20);
        Hardships.outbreak(s, now, "a caravan from Elsewhere", rand);
        s.hardship.harvest = Hardships.Harvest.COLD_SNAP; s.hardship.harvestUntil = now + DAY;
        s.hardship.quarantine = true; s.hardship.hungry = true; s.hardship.hungrySince = now - 5; s.hardship.imported = 12;
        s.hardship.verminUntil = now + DAY; s.hardship.eaten = 7; s.hardship.dead = 2; s.hardship.lastSiege = now - 100;
        Settlement back = Settlement.load(s.save());
        Hardships.State a = s.hardship, b = back.hardship;
        check(b.sick == a.sick && b.sickName.equals(a.sickName) && b.sickCause.equals(a.sickCause) && b.sickSince == a.sickSince && b.dead == 2,
                "Sickness survives a save");
        check(b.harvest == Hardships.Harvest.COLD_SNAP && b.harvestUntil == a.harvestUntil && b.quarantine && b.hungry && b.hungrySince == a.hungrySince
                && b.imported == 12 && b.verminUntil == a.verminUntil && b.eaten == 7 && b.lastSiege == a.lastSiege, "Harvest, hunger, rats and quarantine survive a save");
        CompoundTag old = s.save();
        old.remove("hardship");
        Settlement fresh = Settlement.load(old);
        check(fresh.hardship.sick == 0 && fresh.hardship.harvest == Hardships.Harvest.NONE && !fresh.hardship.quarantine && !fresh.hardship.hungry
                && !Hardships.besiegedLately(fresh, now), "An old save loads healthy");

        // everything off
        HardshipConfig.ENABLED.set(false);
        check(Hardships.workFactor(s) == 1 && Hardships.output(s, Category.FARM) == 1 && Hardships.mood(s, new ArrayList<>()) == 0
                && Hardships.sickChance(s, now) == 0 && Hardships.routeBlock(s, fresh) == null && Hardships.bounty(s, Set.of()) == null,
                "Switched off, nothing a hardship does applies");
        Map<net.minecraft.world.item.Item, Integer> w = new HashMap<>();
        Hardships.wants(s, w);
        check(w.isEmpty(), "Switched off, trade wants are untouched");
        Hardships.step(server, m, s, 1.0, now, rand);
        check(s.hardship.sick == 0 && s.hardship.harvest == Hardships.Harvest.NONE && s.hardship.verminUntil == 0 && !s.hardship.hungry,
                "Switched off, the town's troubles end at once");
        CompoundTag v = Hardships.view(s, now);
        check(!v.getBoolean("enabled") && v.getString("sickRisk").equals("Off"), "The City Manager shows it off");
        HardshipConfig.ENABLED.set(true);
        HardshipConfig.SICKNESS.set(false);
        s.hardship.quarantine = true;
        check(Hardships.sickChance(s, now) == 0 && Hardships.routeBlock(s, fresh) == null && Hardships.quarantine(s, true) != null,
                "With sickness off there are no outbreaks and no quarantine");
        defaults();
    }

    private HardshipSelfTest() {}
}
