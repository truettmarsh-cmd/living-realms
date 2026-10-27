package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.building.*;
import livingrealms.military.UnitType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import java.util.*;

/** Player towns advancing on their own: research, rank, training, unloaded building and the away report. */
public final class AutonomySelfTest {
    private static int n;
    private static void check(boolean ok, String text) { if (!ok) throw new IllegalStateException(text); n++; }

    public static int run(ServerLevel level) {
        n = 0;
        saves();
        research(level);
        tier(level);
        training();
        unloaded(level);
        report(level);
        return n;
    }

    static Settlement town(ServerLevel level, BlockPos center, String... built) {
        Settlement s = new Settlement();
        s.owner = UUID.randomUUID(); s.ownerName = "Tester"; s.name = "Auto test"; s.center = center; s.dim = level.dimension(); s.style = "plains";
        for (String id : built) {
            BuildingType t = BuildingCatalog.get(id);
            Plot p = new Plot(); p.id = s.nextId++; p.typeId = id; p.blueprintKey = t.blueprintKey(1, s.style);
            p.origin = center.offset(24 + 12 * s.plots.size(), 0, 24); p.state = Plot.State.BUILT; s.plots.add(p);
        }
        Simulation.recompute(s);
        return s;
    }

    static void saves() {
        Settlement s = new Settlement();
        CompoundTag old = s.save();
        for (String k : List.of("autoResearch", "autoTier", "autoTrain", "garrisonTarget", "researchAuto", "researchSkip", "sites", "away")) old.remove(k);
        Settlement o = Settlement.load(old);
        check(o.autoResearch && o.autoTier && o.autoTrain && o.autoBuild, "Old saves default every autonomy switch on");
        check(o.garrisonTarget == -1 && o.researchSkip.isEmpty() && o.sites.isEmpty() && o.away.empty() && o.away.seenMs == 0, "Old saves default the garrison goal to half capacity");
        s.autoResearch = false; s.autoTier = false; s.autoTrain = false; s.garrisonTarget = 5; s.researchAuto = true;
        s.researchSkip.add("archery"); s.sites.add(new BlockPos(1, 2, 3));
        s.away.seenMs = 1234; s.away.seenTick = 99; s.away.built("Cottage"); s.away.built("Cottage"); s.away.trained(UnitType.BOWMAN);
        s.away.note(s.away.researched, "Masonry"); s.away.note(s.away.ranks, "Hamlet"); s.away.note(s.away.raids, "Raid");
        Settlement r = Settlement.load(s.save());
        check(!r.autoResearch && !r.autoTier && !r.autoTrain && r.garrisonTarget == 5 && r.researchAuto, "Autonomy switches persist");
        check(r.researchSkip.contains("archery") && r.sites.equals(List.of(new BlockPos(1, 2, 3))), "Skips and surveyed sites persist");
        check(r.away.seenMs == 1234 && r.away.seenTick == 99 && r.away.built.get("Cottage") == 2 && r.away.trained.get("BOWMAN") == 1
                && r.away.researched.equals(List.of("Masonry")) && r.away.ranks.equals(List.of("Hamlet")) && r.away.raids.size() == 1, "Away record persists");
        Plot p = new Plot(); p.typeId = "livingrealms:cottage"; p.blueprintKey = "x"; p.origin = BlockPos.ZERO; p.unsited = true; p.deferred = true;
        Plot q = Plot.load(p.save());
        check(q.unsited && q.deferred, "Unsited and deferred plots persist");
        p.unsited = false; p.deferred = false;
        q = Plot.load(p.save());
        check(!q.unsited && !q.deferred, "Plain plots load as sited");
    }

    static void research(ServerLevel level) {
        Settlement s = town(level, new BlockPos(0, 100, 0), "livingrealms:town_hall");
        check(s.d.researchPerDay > 0, "Town hall gives research");
        check("carpentry".equals(Autonomy.pickResearch(s)), "Economy and construction before cheaper smithing: " + Autonomy.pickResearch(s));
        s.research.add("carpentry");
        s.tier = Tier.HAMLET; s.population = 6; s.d.buildings = 5;
        check("masonry".equals(Autonomy.pickResearch(s)), "Close rank gate comes first: " + Autonomy.pickResearch(s));
        s.population = 5;
        check(!"masonry".equals(Autonomy.pickResearch(s)), "Distant rank gate is not rushed");
        s.population = 6;
        s.researchSkip.add("masonry");
        check(!"masonry".equals(Autonomy.pickResearch(s)), "Excluded projects are never picked");
        s.researchSkip.clear();
        check(!"scholarship".equals(Autonomy.pickResearch(s)) && s.cannotResearch("scholarship") == null, "Unaffordable projects wait for their items");
        Autonomy.research(s);
        check(s.activeResearch.equals("masonry") && s.researchAuto, "Idle scholars start the chosen project");
        String err = Actions.research(s, "forestry");
        check(err == null && s.activeResearch.equals("forestry") && !s.researchAuto, "Manual choice replaces an automatic one");
        Autonomy.research(s);
        check(s.activeResearch.equals("forestry"), "Auto research never overrides a manual choice");
        Actions.cancelResearch(s);
        check(s.researchSkip.isEmpty() && s.activeResearch.isEmpty(), "Cancelling a manual choice skips nothing");
        Autonomy.research(s);
        String auto = s.activeResearch;
        Actions.cancelResearch(s);
        check(!auto.isEmpty() && s.researchSkip.contains(auto) && s.activeResearch.isEmpty(), "Cancelling an automatic pick skips it");
        Autonomy.research(s);
        check(!s.activeResearch.equals(auto), "Skipped project stays skipped");
        s.activeResearch = ""; s.autoResearch = false;
        Autonomy.think(level, s);
        check(s.activeResearch.isEmpty(), "Auto research switch respected");
    }

    static void tier(ServerLevel level) {
        Settlement s = town(level, new BlockPos(0, 100, 0), "livingrealms:town_hall");
        s.population = 6; s.d.buildings = 4;
        s.stock.force(Items.EMERALD, 16);
        check(s.tierBlockers().isEmpty() && Autonomy.tier(s) != null && s.tier == Tier.CAMP, "Fee alone is not enough without the reserve");
        check(Autonomy.tierFunds(Tier.HAMLET) == 22, "Hamlet needs 16 emeralds plus a 25% reserve");
        s.stock.force(Items.EMERALD, 5);
        check(Autonomy.tier(s) != null && s.tier == Tier.CAMP, "21 emeralds still short of the reserve");
        s.stock.force(Items.EMERALD, 1);
        s.autoTier = false;
        Autonomy.think(level, s);
        check(s.tier == Tier.CAMP, "Auto rank switch respected");
        s.autoTier = true;
        Autonomy.think(level, s);
        check(s.tier == Tier.HAMLET && s.stock.count(Items.EMERALD) == 6 && s.away.ranks.contains("Hamlet"), "Rank claimed, fee paid, reserve kept");
        s.alarmUntil = level.getGameTime() + 1000;
        s.population = 12; s.d.buildings = 9; s.research.add("masonry"); s.stock.force(Items.EMERALD, 1000);
        Autonomy.think(level, s);
        check(s.tier == Tier.HAMLET, "Nothing advances while under attack");
        s.alarmUntil = 0;
        s.tier = Tier.CITY; s.population = 70; s.d.buildings = 40; s.research.add("royal_charter");
        Autonomy.tier(s);
        check(s.tier == Tier.CITY, "Only the kingdom capital may become a Capital");
        s.capital = true;
        Autonomy.tier(s);
        check(s.tier == Tier.CAPITAL, "Capital claims Capital rank");
    }

    static void training() {
        Settlement s = new Settlement();
        s.owner = UUID.randomUUID(); s.style = "plains"; s.population = 20; s.center = BlockPos.ZERO;
        for (String id : List.of("livingrealms:town_hall", "livingrealms:barracks")) {
            Plot p = new Plot(); p.id = s.nextId++; p.typeId = id; p.blueprintKey = BuildingCatalog.get(id).blueprintKey(1, s.style);
            p.origin = new BlockPos(30 * s.nextId, 100, 0); p.state = Plot.State.BUILT; s.plots.add(p);
        }
        Simulation.recompute(s);
        check(s.d.garrisonCap == 10 && Autonomy.target(s) == 5, "Default goal is half the capacity, rounded down");
        s.research.add("archery");
        s.stock.force(Items.EMERALD, 200); s.stock.force(Items.IRON_SWORD, 10); s.stock.force(Items.BOW, 10);
        List<UnitType> trained = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UnitType u = Autonomy.train(s);
            if (u != null) trained.add(u);
            if (s.training.size() >= 2 || u == null && !s.training.isEmpty()) {
                Settlement.Training t = s.training.remove(0);
                Settlement.Soldier so = new Settlement.Soldier(); so.entity = UUID.randomUUID(); so.type = t.type; so.name = t.type.label;
                s.soldiers.add(so);
            }
        }
        check(s.soldiers.size() + s.training.size() == 5 && trained.size() == 5, "Garrison filled exactly to the goal: " + trained);
        check(trained.get(0) == UnitType.BOWMAN && trained.get(1) == UnitType.FOOTMAN, "Ranged first, then the best melee researched");
        check(trained.stream().filter(u -> u == UnitType.BOWMAN).count() >= 2, "A third of the garrison ranged");
        check(s.population == 15 && s.stock.count(Items.EMERALD) == 200 - trained.stream().mapToInt(u -> u.emeralds).sum(), "Recruits cost people and emeralds");
        check(Autonomy.train(s) == null, "No training beyond the goal");
        s.garrisonTarget = 7;
        check(Autonomy.target(s) == 7 && Autonomy.train(s) != null, "Player goal raises the garrison");
        s.garrisonTarget = 50;
        check(Autonomy.target(s) == 10, "Goal never exceeds capacity");
        s.training.clear();
        s.stock.take(Items.EMERALD, s.stock.count(Items.EMERALD)); s.stock.force(Items.EMERALD, 10);
        check(Autonomy.train(s) == null && s.stock.count(Items.EMERALD) == 10, "Emerald floor protects the treasury");
        s.stock.force(Items.EMERALD, 100); s.starving = true;
        check(Autonomy.train(s) == null, "No recruiting while starving");
        s.starving = false; s.population = Autonomy.TRAIN_MIN_POP - 1;
        check(Autonomy.train(s) == null, "Small towns keep their people");
    }

    static void unloaded(ServerLevel level) {
        int cx = 48_000, cz = 48_000, gy = Construction.ground(level, 0, 0);
        BlockPos center = new BlockPos(cx, gy, cz);
        check(!level.hasChunkAt(center), "Test town starts unloaded");
        Settlement s = town(level, center, "livingrealms:town_hall");
        s.population = 10;
        Simulation.recompute(s);
        s.sites.add(new BlockPos(cx - 8, gy, cz - 8));
        for (BuildingType t : BuildingCatalog.types().values()) {
            if (s.cannotBuild(t) != null || !t.procedural()) continue;
            Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
            if (bp != null) bp.bill().forEach((it, k) -> s.stock.force(it, k));
        }
        s.stock.force(Items.COBBLESTONE, 2000);
        s.autoBuild = false;
        AutoBuild.think(level, s);
        check(s.plots.size() == 1, "Auto build switch respected while away");
        s.autoBuild = true;
        AutoBuild.think(level, s);
        check(s.plots.size() == 2 && s.sites.isEmpty(), "Villagers plan on a surveyed site while nobody is near");
        Plot p = s.plots.get(1);
        check(p.automatic && p.unsited && p.state == Plot.State.BUILDING && p.foundation.length == 0, "Away plan is unsited and automatic");
        check(!level.hasChunkAt(center) && !Construction.loaded(level, p), "Planning never loads the chunks");
        AutoBuild.think(level, s);
        check(s.plots.size() == 2, "One automatic project at a time");
        int before = s.stock.total();
        for (int i = 0; i < 2000 && !p.deferred; i++) Simulation.offscreenBuild(level, s, s.d);
        check(p.deferred && p.standing() && p.paid == p.totalSteps() && p.placed == 0, "Paid offscreen construction completes the building");
        check(s.stock.total() < before && !s.away.built.isEmpty(), "Materials were spent and the work recorded");
        check(!level.hasChunkAt(center) && !Construction.loaded(level, p), "Offscreen construction never loads the chunks");
        Blueprint bp = p.blueprint();
        int beforeHousing = s.d.housing;
        Simulation.recompute(s);
        check(s.d.housing >= beforeHousing && s.d.buildings == 2, "Finished building counts before anyone returns");
        var box = p.box();
        for (int x = box.minX() - 16; x <= box.maxX() + 16; x += 16) for (int z = box.minZ() - 16; z <= box.maxZ() + 16; z += 16) level.getChunk(x >> 4, z >> 4);
        check(Construction.loaded(level, p), "Site loaded by the visit");
        for (int i = 0; i < 50 && p.deferred; i++) Simulation.fast(level, s);
        check(!p.deferred && !p.unsited && p.paid == 0 && p.placed == 0 && p.state == Plot.State.BUILT, "Catch-up finishes when the chunks load");
        int want = 0, match = 0;
        for (int i = 0; i < bp.size(); i++) {
            var st = bp.worldState(i, p.rotation);
            if (st.isAir()) continue;
            want++;
            if (level.getBlockState(bp.worldPos(i, p.origin, p.rotation)).getBlock() == st.getBlock()) match++;
        }
        check(want > 0 && match * 100 >= want * 95, "Blocks really appear on load: " + match + "/" + want);
        check(Planning.plan(null, s, BuildingCatalog.get(CapitalCastles.ID), center, net.minecraft.world.level.block.Rotation.NONE, false) != null, "Castle never planned unsited");
    }

    static void report(ServerLevel level) {
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "AwayOwner"));
        Settlement s = new Settlement(); s.owner = owner.getUUID(); s.name = "Homestead";
        long now = System.currentTimeMillis(), tick = level.getServer().overworld().getGameTime();
        Autonomy.back(owner, s);
        check(s.away.seenMs >= now && s.log.isEmpty(), "First sighting starts tracking without a report");
        s.away.built("Cottage"); s.away.built("Cottage"); s.away.built("Farm Field");
        s.away.note(s.away.researched, "Masonry"); s.away.trained(UnitType.FOOTMAN); s.away.trained(UnitType.FOOTMAN);
        s.away.note(s.away.ranks, "Village"); s.away.note(s.away.raids, "Oakvale is raiding Homestead!");
        List<String> lines = Autonomy.report(s, 10 * 60_000L, 100);
        check(lines.get(0).equals("While you were away from Homestead (10 min):"), "Report heading: " + lines.get(0));
        check(lines.contains("Built: Cottage ×2, Farm Field") && lines.contains("Researched: Masonry") && lines.contains("Trained: Footman ×2")
                && lines.contains("Rose to Village") && lines.contains("Raids: Oakvale is raiding Homestead!"), "Report contents: " + lines);
        check(Autonomy.report(s, 0, 48_000).get(0).contains("2.0 days"), "Long absences counted in days");
        Autonomy.back(owner, s);
        check(s.log.isEmpty() && s.away.empty(), "A short absence brings no report");
        s.away.built("Well");
        s.away.seenMs = now - Autonomy.AWAY_MS - 1000;
        Autonomy.back(owner, s);
        check(s.log.size() == 2 && s.log.peekFirst().startsWith("While you were away") && s.log.contains("Built: Well") && s.away.empty(), "Five real minutes away brings the report to the log");
        s.away.note(s.away.researched, "Trade");
        s.away.seenTick = tick - Autonomy.AWAY_TICKS - 1;
        Autonomy.back(owner, s);
        check(s.log.contains("Researched: Trade"), "A town day away brings the report");
        s.away.seenMs = 1;
        Autonomy.back(owner, s);
        check(s.log.size() == 4, "Nothing to report, nothing sent");
        var stranger = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "Visitor"));
        s.away.built("Tavern"); s.away.seenMs = 1;
        Autonomy.back(stranger, s);
        check(s.away.built.containsKey("Tavern") && s.log.size() == 4, "Only the owner's return resets the report");
    }

    private AutonomySelfTest() {}
}
