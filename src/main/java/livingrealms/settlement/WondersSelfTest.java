package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.LivingRealms;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Construction;
import livingrealms.building.Procedural;
import livingrealms.chronicle.Chronicle;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierSkills;
import livingrealms.military.UnitType;
import livingrealms.research.ResearchTree;
import livingrealms.smithing.Smithing;
import livingrealms.wonders.Masterwork;
import livingrealms.wonders.Wonder;
import livingrealms.wonders.Wonders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * -PwondersTest -PwondersTestDir=<fresh dir>: the Wonders (docs/wonders.md). Catalogue and blueprints, the research gate,
 * Capital-only and once-per-kingdom rules, a real paid build of the Grand Cathedral to completion with its LEGEND entry,
 * every effect in a second city of the kingdom, transfer to a conqueror, and a save round trip.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class WondersSelfTest {
    private static int checks;
    private static final List<String> notes = new ArrayList<>();

    private static void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("Wonders: " + what);
        checks++;
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.wondersTest")) return;
        MinecraftServer server = event.getServer();
        server.execute(() -> {
            String report;
            List<Entity> spawned = new ArrayList<>();
            List<Settlement> towns = new ArrayList<>();
            try {
                Smithing.discover(server);
                run(server, spawned, towns);
                report = "PASS: " + checks + " wonder assertions" + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")") + "\n";
                LivingRealms.LOG.info("WONDERS SELF TEST {}", report);
            } catch (Throwable failure) {
                report = "FAIL after " + checks + " assertions: " + failure + "\n";
                LivingRealms.LOG.error("WONDERS SELF TEST FAILED", failure);
            } finally {
                for (Entity e : spawned) if (e != null) e.discard();
                SettlementManager m = SettlementManager.get(server);
                for (Settlement s : towns) if (m.get(s.id) != null) m.remove(s);
                Wonders.spareChance = Wonders.SPARE_CHANCE;
            }
            try { Files.writeString(Path.of("wonders-test-result.txt"), report); } catch (Exception ex) { LivingRealms.LOG.error("Cannot write test result", ex); }
            server.halt(false);
        });
    }

    static void run(MinecraftServer server, List<Entity> spawned, List<Settlement> towns) {
        ServerLevel level = server.overworld();
        SettlementManager m = SettlementManager.get(server);
        catalogue();

        // ---- a kingdom: a capital and a second city; and a rival kingdom
        var ruler = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "Aethelred"));
        var rival = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "Cnut"));
        Settlement cap = town("Wondermere", ruler.getUUID(), "Aethelred", new BlockPos(1024, 80, 1024), Tier.CAPITAL, true);
        Settlement city = town("Ashby", ruler.getUUID(), "Aethelred", new BlockPos(1024, 80, 1424), Tier.CITY, false);
        Settlement enemy = town("Danesford", rival.getUUID(), "Cnut", new BlockPos(1424, 80, 1024), Tier.CAPITAL, true);
        for (Settlement s : List.of(cap, city, enemy)) { m.add(s); towns.add(s); }
        check(cap.capital && !city.capital && enemy.capital, "One seat per kingdom after normalize");
        for (Settlement s : List.of(cap, city, enemy)) hall(s);
        BuildingType cathedral = BuildingCatalog.get(Wonder.CATHEDRAL.id());

        // ---- the research gate
        check(cap.cannotBuild(cathedral) != null && cap.cannotBuild(cathedral).contains("Wonders of the Realm"), "Needs Wonders of the Realm: " + cap.cannotBuild(cathedral));
        cap.research.add(Wonders.RESEARCH);
        check(cap.cannotBuild(cathedral) != null && cap.cannotBuild(cathedral).contains("Grand Cathedral"), "Needs its own research too: " + cap.cannotBuild(cathedral));
        check(cap.cannotResearch(Wonder.CATHEDRAL.research()) == null, "The wonder's research opens after Wonders of the Realm");
        for (Wonder w : Wonder.values()) { cap.research.add(w.research()); city.research.add(w.research()); enemy.research.add(w.research()); }
        city.research.add(Wonders.RESEARCH); enemy.research.add(Wonders.RESEARCH);
        for (Wonder w : Wonder.values()) check(cap.cannotBuild(BuildingCatalog.get(w.id())) == null, w.label + " may be planned in the capital: " + cap.cannotBuild(BuildingCatalog.get(w.id())));
        check(!AutoBuild.autoWanted(cathedral), "Villagers never choose a wonder on their own");

        // ---- Capital only
        check(city.cannotBuild(cathedral) != null && city.cannotBuild(cathedral).contains("Capital"), "A City may not raise a wonder: " + city.cannotBuild(cathedral));
        city.tier = Tier.CAPITAL;
        check(city.cannotBuild(cathedral) != null && city.cannotBuild(cathedral).contains("Capital"), "Nor a Capital-rank city that is not the kingdom seat");
        city.tier = Tier.CITY;
        cap.tier = Tier.CITY;
        check(cap.cannotBuild(cathedral) != null, "The seat needs Capital rank");
        cap.tier = Tier.CAPITAL;

        // ---- before: the second city's figures without wonders
        city.population = 30; city.baseHousing = 40; enemy.population = 30; enemy.baseHousing = 40;
        Simulation.recompute(city);
        double mood0 = city.d.happinessTarget, research0 = city.d.researchPerDay, emeralds0 = city.d.emeraldsPerDay;
        int garrison0 = city.d.garrisonCap;
        long now = level.getGameTime();
        double spread0 = livingrealms.hardship.Hardships.spread(city, now), recovery0 = livingrealms.hardship.Hardships.recovery(city);
        double sick0 = livingrealms.hardship.Hardships.sickChance(city, now);
        check(Wonders.forgeSpeed(city) == 1 && Wonders.caravan(city) == 1 && Wonders.bankCap(city) == Wonders.BANK_CAP, "No wonder, no effect");
        check(!Masterwork.is(Masterwork.forged(city, new ItemStack(Items.IRON_SWORD))), "No Grand Forge, plain gear");
        Chronicle.tick(server, m, cap, Chronicle.today());
        Chronicle.tick(server, m, city, Chronicle.today());

        // ---- a real build of the Grand Cathedral, paid block by block
        flatten(level, 1030, 1030, 1080, 1090, 79);
        BlockPos origin = new BlockPos(1044, 79, 1040);
        String err = Planning.plan(level, cap, cathedral, origin, Rotation.NONE, false);
        check(err == null, "Cathedral planned in the capital: " + err);
        Plot plot = cap.plots.get(cap.plots.size() - 1);
        check(plot.typeId.equals(cathedral.id) && plot.state == Plot.State.BUILDING, "A building project");
        // once per kingdom, even while it is building
        check(cap.cannotBuild(cathedral) != null && cap.cannotBuild(cathedral).contains("already"), "No second cathedral in the capital: " + cap.cannotBuild(cathedral));
        city.capital = true; city.tier = Tier.CAPITAL;
        check(city.cannotBuild(cathedral) != null && city.cannotBuild(cathedral).contains("Wondermere"), "No second cathedral anywhere in the kingdom: " + city.cannotBuild(cathedral));
        city.capital = false; city.tier = Tier.CITY;
        check(enemy.cannotBuild(cathedral) == null, "Another kingdom may raise its own");
        check(!Wonders.has(ruler.getUUID(), Wonder.CATHEDRAL), "Not a wonder until it stands");
        Construction.Result first = null;
        for (int i = 0; i < 20000 && first == null; i++) { var r = Construction.work(level, cap, plot); if (r != Construction.Result.PLACED) first = r; }
        check(first == Construction.Result.MISSING, "An empty stockpile stops the build once the earthworks are done: " + first);
        Blueprint bp = plot.blueprint();
        bp.bill().forEach((item, n) -> cap.stock.force(item, n + 16));
        cap.stock.force(Construction.foundationBlock(cap).getBlock().asItem(), plot.foundation.length + 64);
        int steps = 0, total = plot.totalSteps();
        boolean sawBuilding = false;
        for (; steps < total + 200; steps++) {
            var r = Construction.work(level, cap, plot);
            check(r != Construction.Result.MISSING, "The bill covers the build: " + plot.status);
            if (r == Construction.Result.DONE) break;
            if (!sawBuilding && plot.placed > total / 2) {
                CompoundTag v = view(m, cap, Wonder.CATHEDRAL);
                check(v.getString("state").equals("BUILDING") && v.getFloat("progress") > .4f && v.getFloat("progress") < 1, "City Manager shows it building: " + v);
                sawBuilding = true;
            }
        }
        check(sawBuilding, "Seen half built");
        Simulation.finish(level, cap, plot);
        check(plot.state == Plot.State.BUILT, "The cathedral stands");
        notes.add("cathedral " + bp.sx + "×" + bp.sz + "×" + bp.sy + ", " + bp.solidCount + " blocks, " + steps + " build steps");
        check(level.getBlockState(origin.offset(12, 3, 3)).is(Blocks.GOLD_BLOCK) || level.getBlockState(origin.offset(12, 2, 3)).is(Blocks.GOLD_BLOCK), "The gold altar was placed");
        check(Wonders.has(ruler.getUUID(), Wonder.CATHEDRAL) && Wonders.has(city, Wonder.CATHEDRAL), "The kingdom holds it, the second city too");
        Chronicle.tick(server, m, cap, Chronicle.today());
        Chronicle.tick(server, m, city, Chronicle.today());
        var legend = last(cap);
        check(legend.kind() == Chronicle.Kind.LEGEND && legend.text().contains("Grand Cathedral") && legend.text().contains("Wondermere"), "LEGEND in the capital's chronicle: " + legend);
        check(last(city).kind() == Chronicle.Kind.LEGEND && last(city).text().contains("Grand Cathedral"), "The news in the second city");
        int entries = cap.chronicle.entries.size();
        Chronicle.tick(server, m, cap, Chronicle.today());
        check(cap.chronicle.entries.size() == entries, "Chronicled once");

        // ---- the other four: built outright (the build path is the cathedral's)
        int px = 1100;
        for (Wonder w : List.of(Wonder.ACADEMY, Wonder.FORGE, Wonder.EXCHANGE, Wonder.CITADEL)) {
            Plot p = new Plot(); p.id = cap.nextId++; p.typeId = w.id(); p.blueprintKey = BuildingCatalog.get(w.id()).blueprintKey(1, cap.style);
            p.origin = new BlockPos(px, 80, 1000); p.state = Plot.State.BUILT; cap.plots.add(p);
            px += 40;
            check(Wonders.has(ruler.getUUID(), w), w.label + " held");
        }
        check(Wonders.count(Kingdoms.cities(m, cap)) == 5, "Five wonders count for the quest line");

        // ---- every effect, measured in the second city
        Simulation.recompute(city);
        check(city.d.happinessReasons.contains("+" + Wonders.CATHEDRAL_MOOD + " Grand Cathedral") && Math.abs(city.d.happinessTarget - Math.min(100, mood0 + Wonders.CATHEDRAL_MOOD)) < 1e-6,
                "Cathedral: +10 mood in the other city: " + mood0 + " -> " + city.d.happinessTarget);
        double sick1 = livingrealms.hardship.Hardships.sickChance(city, now);
        check(livingrealms.hardship.Hardships.sickFactors(city, now).stream().anyMatch(e -> e.getKey().equals("Grand Cathedral") && e.getValue() == Wonders.CATHEDRAL_OUTBREAK),
                "Cathedral listed among the sickness factors");
        if (sick0 > 0) check(Math.abs(sick1 - sick0 * Wonders.CATHEDRAL_OUTBREAK) < 1e-9 || sick0 >= 1, "Outbreaks 40% as likely: " + sick0 + " -> " + sick1);
        else notes.add("sickness off in this world: chance not compared");
        check(Math.abs(livingrealms.hardship.Hardships.spread(city, now) - spread0 * Wonders.CATHEDRAL_SPREAD) < 1e-9, "Outbreaks spread slower");
        check(Math.abs(livingrealms.hardship.Hardships.recovery(city) - recovery0 * Wonders.CATHEDRAL_RECOVERY) < 1e-9, "The sick get well faster");
        check(Math.abs(city.d.researchPerDay - research0 * Wonders.ACADEMY_RESEARCH) < 1e-6 && research0 > 0, "Academy: research ×1.5: " + research0 + " -> " + city.d.researchPerDay);
        check(Wonders.bankCap(city) == 2 * Wonders.BANK_CAP && Wonders.bankPour(city) == 2 * Wonders.BANK_POUR, "Academy: the study bank holds and pours twice as much");
        check(Wonders.forgeSpeed(city) == Wonders.FORGE_SPEED, "Forge: smiths ×1.5");
        Map<String, Integer> bill = new LinkedHashMap<>(Map.of("minecraft:iron_ingot", 24, "minecraft:leather", 1));
        Wonders.forgeCost(city, bill);
        check(bill.get("minecraft:iron_ingot") == 18 && bill.get("minecraft:leather") == 1, "Forge: orders cost a quarter less, never nothing: " + bill);
        check(Math.abs(city.d.emeraldsPerDay - emeralds0 - Wonders.EXCHANGE_TRICKLE) < 1e-6, "Exchange: +6 emeralds a day: " + emeralds0 + " -> " + city.d.emeraldsPerDay);
        check(Wonders.caravan(city) == Wonders.EXCHANGE_CARAVAN, "Exchange: caravans ×1.5");
        int expect = Math.max(garrison0 + Wonders.CITADEL_GARRISON_MIN, (int) Math.round(garrison0 * Wonders.CITADEL_GARRISON));
        check(city.d.garrisonCap == expect, "Citadel: garrison " + garrison0 + " -> " + city.d.garrisonCap + " (want " + expect + ")");
        check(Wonders.has(ruler.getUUID(), Wonder.CITADEL), "The hero hook answers");

        // masterwork gear
        ItemStack sword = Masterwork.forged(city, new ItemStack(Items.IRON_SWORD)), chest = Masterwork.forged(city, new ItemStack(Items.IRON_CHESTPLATE));
        check(Masterwork.is(sword) && Masterwork.is(chest) && !Masterwork.is(Masterwork.forged(city, new ItemStack(Items.BREAD))), "Forge: weapons and armour are Masterwork, bread is not");
        check(Math.abs(damage(sword) - damage(new ItemStack(Items.IRON_SWORD)) - Masterwork.DAMAGE) < 1e-6, "Masterwork sword +1.5 damage: " + damage(sword));
        check(Math.abs(sum(chest, EquipmentSlot.CHEST, Attributes.ARMOR) - 6 - Masterwork.ARMOR) < 1e-6
                && Math.abs(sum(chest, EquipmentSlot.CHEST, Attributes.ARMOR_TOUGHNESS) - Masterwork.TOUGHNESS) < 1e-6
                && sum(chest, EquipmentSlot.HEAD, Attributes.ARMOR) == 0, "Masterwork chestplate +1 armour +1 toughness, only when worn");
        check(Stockpile.canStore(Masterwork.plain(sword)) && !Stockpile.canStore(sword), "Unworn masterwork returns to the stockpile as plain");
        ItemStack worn = sword.copy(); worn.setDamageValue(5);
        check(!Stockpile.canStore(Masterwork.plain(worn)), "Worn masterwork stays with its recruit");

        // recruits: the armoury, the citadel's muster, the cathedral's blessing
        level.getChunkAt(city.center);
        flatten(level, 1014, 1414, 1034, 1434, 79);
        LivingEntity foot = (LivingEntity) RecruitsBridge.spawn(level, UnitType.FOOTMAN, city.center, city.owner, "Footman Wonder");
        check(foot != null, "Recruit spawned");
        spawned.add(foot);
        Settlement.Soldier so = new Settlement.Soldier(); so.entity = foot.getUUID(); so.type = UnitType.FOOTMAN; so.name = "Footman Wonder";
        city.soldiers.add(so);
        RecruitsBridge.setSlot(foot, EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        city.stock.force(Items.IRON_SWORD, 2);
        Simulation.equip(city, foot, UnitType.FOOTMAN);
        ItemStack held = RecruitsBridge.getSlot(foot, EquipmentSlot.MAINHAND);
        check(held.is(Items.IRON_SWORD) && Masterwork.is(held), "The armoury hands out Masterwork: " + held + " " + held.getTag());
        int level0 = RecruitsBridge.level(foot), xp0 = so.skillXp;
        Wonders.mustered(city, foot, so);
        check(RecruitsBridge.level(foot) == level0 + Wonders.CITADEL_LEVELS && so.skillXp == xp0 + SoldierSkills.XP_PER_POINT, "Citadel: +2 levels and a skill point at muster");

        Wonders.spareChance = 1;
        foot.setHealth(foot.getMaxHealth());
        LivingDamageEvent blow = new LivingDamageEvent(foot, level.damageSources().generic(), 1000);
        Wonders.lethal(blow);
        BlockPos at = plot.center();
        check(Math.abs(blow.getAmount() - (foot.getMaxHealth() - 1)) < 1e-4, "Cathedral: the killing blow leaves 1 HP: " + blow.getAmount());
        check(foot.blockPosition().distManhattan(at) <= 3, "Carried to the cathedral: " + foot.blockPosition() + " vs " + at);
        check(!Wonders.spareReady(cap, Chronicle.dayOf(server.overworld().getGameTime())), "The day's blessing is spent");
        foot.teleportTo(city.center.getX() + .5, city.center.getY() + 1, city.center.getZ() + .5);
        LivingDamageEvent again = new LivingDamageEvent(foot, level.damageSources().generic(), 1000);
        Wonders.lethal(again);
        check(again.getAmount() == 1000, "Only once a day");
        cap.accum.remove("wonder:sparedDay");
        foot.teleportTo(3000.5, 90, 3000.5);
        LivingDamageEvent outside = new LivingDamageEvent(foot, level.damageSources().generic(), 1000);
        Wonders.lethal(outside);
        check(outside.getAmount() == 1000, "Not outside the kingdom's cities");
        foot.teleportTo(city.center.getX() + .5, city.center.getY() + 1, city.center.getZ() + .5);
        LivingDamageEvent small = new LivingDamageEvent(foot, level.damageSources().generic(), 1);
        Wonders.lethal(small);
        check(small.getAmount() == 1 && Wonders.spareReady(cap, Chronicle.dayOf(server.overworld().getGameTime())), "A blow that does not kill spends nothing");

        // ---- the Kingdom page
        CompoundTag page = Wonders.view(m, city, true);
        check(page.getInt("built") == 5 && page.getUUID("town").equals(cap.id) && !page.getBoolean("here"), "Page: five built, planned from the capital");
        CompoundTag rivalPage = Wonders.view(m, enemy, true);
        check(rivalPage.getInt("built") == 0 && rivalPage.getList("wonders", Tag.TAG_COMPOUND).getCompound(0).getString("state").equals("AVAILABLE"), "Rival page: available");
        enemy.research.remove(Wonder.CITADEL.research());
        CompoundTag locked = rivalPage = Wonders.view(m, enemy, true);
        check(locked.getList("wonders", Tag.TAG_COMPOUND).getCompound(4).getString("state").equals("LOCKED")
                && locked.getList("wonders", Tag.TAG_COMPOUND).getCompound(4).getString("reason").contains("Citadel"), "Rival page: locked with the reason");
        enemy.research.add(Wonder.CITADEL.research());

        // ---- save and load
        Settlement back = Settlement.load(cap.save());
        SettlementManager fresh = new SettlementManager();
        fresh.add(back);
        for (Wonder w : Wonder.values()) check(Wonders.has(fresh, ruler.getUUID(), w), w.label + " survives a save");
        check(back.chronicle.entries.stream().anyMatch(e -> e.kind() == Chronicle.Kind.LEGEND && e.text().contains("Grand Cathedral")), "The legend survives a save");
        cap.accum.put("wonder:sparedDay", 7.0);
        check(Settlement.load(cap.save()).accum.getOrDefault("wonder:sparedDay", 0.0) == 7.0, "The blessing's day survives a save");
        cap.accum.remove("wonder:sparedDay");

        // ---- conquest: the capital falls, the wonders and their effects go with it
        Simulation.recompute(enemy);
        double enemyMood0 = enemy.d.happinessTarget;
        int moved = Kingdoms.transfer(server, m, enemy, cap, "Cnut");
        check(moved == 2 && cap.kingdomId().equals(rival.getUUID()) && city.kingdomId().equals(rival.getUUID()), "The capital took its cities with it");
        check(stands(cap, Wonder.CATHEDRAL) && !cap.capital, "The wonder stands in the captured city");
        for (Wonder w : Wonder.values()) check(Wonders.has(rival.getUUID(), w) && !Wonders.has(ruler.getUUID(), w), w.label + " now serves the conqueror");
        Simulation.recompute(enemy);
        check(enemy.d.happinessReasons.contains("+" + Wonders.CATHEDRAL_MOOD + " Grand Cathedral") && enemy.d.happinessTarget > enemyMood0 - 1e-6, "The conqueror's own capital gains the mood");
        check(Masterwork.is(Masterwork.forged(enemy, new ItemStack(Items.IRON_SWORD))), "And the Masterwork");
        check(enemy.cannotBuild(cathedral) != null && enemy.cannotBuild(cathedral).contains("Wondermere"), "Once per kingdom holds for the conqueror too: " + enemy.cannotBuild(cathedral));
        Wonders.spareChance = 1;
        foot.setHealth(foot.getMaxHealth());
        LivingDamageEvent theirs = new LivingDamageEvent(foot, level.damageSources().generic(), 1000);
        Wonders.lethal(theirs);
        check(theirs.getAmount() < 1000, "The captured city's recruits are spared by the cathedral they now serve");
        // the old ruler, now without a city, holds nothing; a new capital of theirs may raise one again
        Settlement exile = town("Newhope", ruler.getUUID(), "Aethelred", new BlockPos(1824, 80, 1024), Tier.CAPITAL, true);
        exile.research.addAll(cap.research);
        m.add(exile); towns.add(exile); hall(exile);
        check(exile.cannotBuild(cathedral) == null, "A kingdom that lost its wonder may raise it again elsewhere");
    }

    // ------------------------------------------------------------------ catalogue and blueprints

    static void catalogue() {
        var root = ResearchTree.get(Wonders.RESEARCH);
        check(root != null && root.requires().contains("royal_charter") && root.requires().contains("education"), "Wonders of the Realm research after Royal Charter and Education");
        for (Wonder w : Wonder.values()) {
            var n = ResearchTree.get(w.research());
            check(n != null && n.requires().equals(List.of(Wonders.RESEARCH)) && n.branch().equals("Wonders"), "Research for " + w.label);
            BuildingType t = BuildingCatalog.get(w.id());
            check(t != null && t.procedural() && t.minTier == 6 && t.maxCount == 1 && t.category == w.category && w.research().equals(t.research), "Catalogue entry " + w.id());
            for (String family : Procedural.FAMILIES) {
                Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, "minecraft:" + family));
                check(bp != null && bp.solidCount >= 2500, w.label + " is a great work in " + family + ": " + (bp == null ? 0 : bp.solidCount));
                check(bp.sx <= 32 && bp.sz <= 46 && bp.sy <= 40, w.label + " fits a capital lot: " + bp.sx + "×" + bp.sz + "×" + bp.sy);
                check(bp.door() != null && bp.front() == Direction.SOUTH, w.label + " has a front door to the south");
                supported(w, bp);
            }
            Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, "minecraft:plains"));
            notes.add(w.kind + " " + bp.sx + "×" + bp.sz + "×" + bp.sy + "/" + bp.solidCount);
        }
        check(bill(Wonder.CATHEDRAL, Items.GOLD_BLOCK) >= 5 && bill(Wonder.ACADEMY, Items.BOOKSHELF) >= 60 && bill(Wonder.ACADEMY, Items.ENCHANTING_TABLE) == 1
                && bill(Wonder.FORGE, Items.ANVIL) >= 6 && bill(Wonder.FORGE, Items.IRON_BLOCK) >= 4 && bill(Wonder.EXCHANGE, Items.EMERALD_BLOCK) >= 2
                && bill(Wonder.CITADEL, Items.IRON_BLOCK) >= 8, "Each wonder costs precious materials");
    }

    static int bill(Wonder w, net.minecraft.world.item.Item it) {
        return BuildingCatalog.blueprint(BuildingCatalog.get(w.id()).blueprintKey(1, "minecraft:plains")).bill().getOrDefault(it, 0);
    }

    /** Lanterns, banners, ladders, candles and doors in the blueprint hang on, stand on or lean against a block of it (or the ground at y=1). */
    static void supported(Wonder w, Blueprint bp) {
        Map<Long, BlockState> at = new HashMap<>();
        for (int i = 0; i < bp.size(); i++) at.put(BlockPos.asLong(bp.lx(i), bp.ly(i), bp.lz(i)), bp.state(i));
        for (int i = 0; i < bp.size(); i++) {
            BlockState s = bp.state(i);
            int x = bp.lx(i), y = bp.ly(i), z = bp.lz(i);
            BlockPos need = null;
            if (s.getBlock() instanceof LanternBlock) need = s.getValue(LanternBlock.HANGING) ? new BlockPos(x, y + 1, z) : new BlockPos(x, y - 1, z);
            else if (s.getBlock() instanceof WallBannerBlock) need = new BlockPos(x, y, z).relative(s.getValue(WallBannerBlock.FACING).getOpposite());
            else if (s.getBlock() instanceof LadderBlock) need = new BlockPos(x, y, z).relative(s.getValue(LadderBlock.FACING).getOpposite());
            else if (s.getBlock() instanceof CandleBlock || s.getBlock() instanceof DoorBlock && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                    || s.getBlock() instanceof BedBlock || s.getBlock() instanceof CarpetBlock) need = new BlockPos(x, y - 1, z);
            if (need == null || need.getY() == 0 && !at.containsKey(need.asLong())) continue;
            BlockState under = at.get(need.asLong());
            check(under != null && !under.isAir(), w.label + ": " + s.getBlock().getName().getString() + " at " + x + "," + y + "," + z + " has nothing to hold it");
        }
    }

    // ------------------------------------------------------------------ helpers

    static Settlement town(String name, UUID owner, String ownerName, BlockPos center, Tier tier, boolean capital) {
        Settlement s = new Settlement();
        s.name = name; s.owner = owner; s.ownerName = ownerName; s.center = center; s.tier = tier; s.capital = capital;
        s.style = "minecraft:plains"; s.founded = 0;
        return s;
    }

    static void hall(Settlement s) {
        Plot hall = new Plot(); hall.id = s.nextId++; hall.typeId = "livingrealms:town_hall"; hall.blueprintKey = "proc:town_hall/1/plains";
        hall.origin = s.center.offset(-4, 0, -4); hall.state = Plot.State.BUILT; s.plots.add(hall);
        Simulation.recompute(s);
    }

    static void flatten(ServerLevel level, int x0, int z0, int x1, int z1, int ground) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            level.getChunk(x >> 4, z >> 4);
            for (int y = ground - 2; y <= ground + 44; y++)
                level.setBlock(new BlockPos(x, y, z), (y < ground ? Blocks.STONE : y == ground ? Blocks.GRASS_BLOCK : Blocks.AIR).defaultBlockState(), 2);
        }
    }

    static CompoundTag view(SettlementManager m, Settlement s, Wonder w) {
        for (Tag t : Wonders.view(m, s, true).getList("wonders", Tag.TAG_COMPOUND))
            if (((CompoundTag) t).getString("kind").equals(w.kind)) return (CompoundTag) t;
        return new CompoundTag();
    }

    static Chronicle.Entry last(Settlement s) { return s.chronicle.entries.get(s.chronicle.entries.size() - 1); }

    static boolean stands(Settlement s, Wonder w) { return Wonders.stands(s, w) != null; }

    static double damage(ItemStack st) { return sum(st, EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE); }

    static double sum(ItemStack st, EquipmentSlot slot, net.minecraft.world.entity.ai.attributes.Attribute a) {
        double v = 0;
        for (var mod : st.getAttributeModifiers(slot).get(a)) v += mod.getAmount();
        return v;
    }

    private WondersSelfTest() {}
}
