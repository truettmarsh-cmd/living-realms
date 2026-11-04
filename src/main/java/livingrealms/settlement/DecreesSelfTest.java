package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.LivingRealms;
import livingrealms.chronicle.Chronicle;
import livingrealms.military.RecruitCombat;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * -PdecreesTest: royal decrees on a real kingdom in the test world. Rank and research gates, costs, cooldowns and expiry,
 * every effect through the hooks the rest of the mod calls, War Horn as its own capped damage term on a real hit, levies
 * raised as real Recruits soldiers and sent home (loaded, unloaded, across a save and reload), and NPC kingdoms' decrees.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class DecreesSelfTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        if (!ok) throw new IllegalStateException(label);
        checks++;
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.decreesTest")) return;
        event.getServer().execute(() -> {
            String report;
            try {
                checks = 0;
                run(event.getServer().overworld());
                report = "PASS: " + checks + " decree assertions\n";
                LivingRealms.LOG.info("DECREES SELF TEST {}", report);
            } catch (Throwable failure) {
                report = "FAIL after " + checks + " assertions: " + failure + "\n";
                LivingRealms.LOG.error("DECREES SELF TEST FAILED", failure);
            }
            try { Files.writeString(Path.of("decrees-test-result.txt"), report); } catch (Exception ex) { LivingRealms.LOG.error("Cannot write test result", ex); }
            event.getServer().halt(false);
        });
    }

    static Settlement town(ServerLevel level, SettlementManager m, String name, UUID owner, BlockPos at, Tier tier, int pop, long founded) {
        Settlement s = new Settlement();
        s.name = name; s.owner = owner; s.ownerName = owner == null ? "" : "Decree Ruler";
        s.dim = level.dimension(); s.tier = tier; s.population = pop; s.founded = founded;
        if (owner == null) s.wealth = 1000;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunkAt(at.offset(dx * 16, 0, dz * 16));
        s.center = RecruitsBridge.surface(level, at);
        m.add(s);
        return s;
    }

    static void expire(MinecraftServer server, UUID k, Decree d) {
        DecreeData data = DecreeData.get(server);
        long now = server.overworld().getGameTime();
        data.realm(k).until.put(d, now - 1);
        data.realm(k).ready.put(d, now - 1);
    }

    static float hit(LivingEntity attacker, LivingEntity victim, float amount) {
        LivingHurtEvent ev = new LivingHurtEvent(victim, victim.level().damageSources().mobAttack(attacker), amount);
        RecruitCombat.hurt(ev);
        return ev.getAmount();
    }

    static void run(ServerLevel level) {
        MinecraftServer server = level.getServer();
        SettlementManager m = SettlementManager.get(server);
        UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
        var ruler = FakePlayerFactory.get(level, new GameProfile(owner, "DecreeRuler"));
        var other = FakePlayerFactory.get(level, new GameProfile(stranger, "DecreeStranger"));
        BlockPos spawn = level.getSharedSpawnPos();
        List<Settlement> towns = new ArrayList<>();
        List<Entity> spawned = new ArrayList<>();
        Raids.Raid fake = null;
        try {
            Settlement crown = town(level, m, "Crown", owner, spawn.offset(8, 0, 8), Tier.TOWN, 30, 0);
            Settlement shire = town(level, m, "Shire", owner, spawn.offset(160, 0, 8), Tier.VILLAGE, 12, 10);
            Settlement march = town(level, m, "March", owner, spawn.offset(8, 0, 160), Tier.TOWN, 10, 20);
            Settlement outpost = town(level, m, "Outpost", owner, BlockPos.ZERO, Tier.CAMP, 2, 30);
            outpost.center = new BlockPos(200_000, 70, 200_000); // never loaded
            Settlement rival = town(level, m, "Rival", null, spawn.offset(-160, 0, 8), Tier.CITY, 20, 40);
            Settlement bastion = town(level, m, "Bastion", null, spawn.offset(-160, 0, 160), Tier.CAPITAL, 30, 50);
            towns.addAll(List.of(crown, shire, march, outpost, rival, bastion));
            check(crown.capital && !shire.capital && rival.capital && bastion.capital && !Kingdoms.same(rival, bastion), "One seat per kingdom");
            Statecraft.Fealty fealty = new Statecraft.Fealty();
            fealty.value = 8; fealty.lowSteps = 5; fealty.formerName = "the old crown"; fealty.former = rival.kingdomId();
            m.state.loyalty.put(march.id, fealty);
            DecreeData data = DecreeData.get(server);
            long now = server.overworld().getGameTime();

            // ---- definitions
            check(Decree.values().length == 7 && Decree.WAR_HORN.tier == Tier.CITY && Decree.ROYAL_FEAST.tier == Tier.CITY
                    && Decree.CALL_THE_BANNERS.tier == Tier.CAPITAL && Decree.MARTIAL_LAW.tier == Tier.CAPITAL, "Seven decrees, City and Capital ranks");
            check(Decree.WAR_HORN.duration() == 3600 && Decree.WAR_HORN.cooldown() == Decree.day() && Decree.CALL_THE_BANNERS.duration() == Decree.day()
                    && Decree.CALL_THE_BANNERS.cooldown() == 5 * Decree.day() && Decree.ROYAL_FEAST.duration() == 2 * Decree.day(), "Durations and cooldowns");
            check(livingrealms.research.ResearchTree.get(Decree.RESEARCH) != null
                    && livingrealms.research.ResearchTree.get(Decree.RESEARCH).requires().contains("urban_planning"), "Royal Decrees research after Urban Planning");

            // ---- rank and research gates
            check(Decrees.rank(m, owner) == Tier.TOWN, "Kingdom rank is its highest city");
            check(Decrees.blocker(server, m, crown, Decree.WAR_HORN).contains("City"), "A Town kingdom has no decrees");
            crown.tier = Tier.CITY;
            check(Decrees.blocker(server, m, crown, Decree.WAR_HORN).contains("Royal Decrees"), "Royal Decrees research needed");
            shire.research.add(Decree.RESEARCH);
            check(Decrees.researched(m, owner, Decree.RESEARCH), "Research in any city of the kingdom counts");
            check(Decrees.blocker(server, m, crown, Decree.WAR_HORN).contains("Command"), "Military decrees also need Command");
            check(Decrees.blocker(server, m, crown, Decree.CALL_THE_BANNERS).contains("Capital"), "Call the Banners needs a Capital");
            check(Decrees.blocker(server, m, crown, Decree.MARTIAL_LAW).contains("Capital"), "Martial Law needs a Capital");
            crown.research.add(Decree.MILITARY_RESEARCH);

            // ---- War Horn: cost, ruler only, effect, cap, morale, expiry, cooldown
            LivingEntity guard = (LivingEntity) RecruitsBridge.spawn(level, UnitType.FOOTMAN, crown.center.offset(2, 0, 2), owner, "Guard Hale");
            LivingEntity hired = (LivingEntity) RecruitsBridge.spawn(level, UnitType.FOOTMAN, crown.center.offset(-2, 0, 2), owner, "Hired Bran");
            LivingEntity foreign = (LivingEntity) RecruitsBridge.spawn(level, UnitType.FOOTMAN, crown.center.offset(2, 0, -2), stranger, "Foreign Ivo");
            net.minecraft.world.entity.monster.Zombie zombie = EntityType.ZOMBIE.create(level);
            zombie.moveTo(crown.center.getX() + 4.5, crown.center.getY(), crown.center.getZ() + 4.5);
            zombie.setNoAi(true);
            level.addFreshEntity(zombie);
            spawned.addAll(List.of(guard, hired, foreign, zombie));
            Settlement.Soldier gso = new Settlement.Soldier();
            gso.entity = guard.getUUID(); gso.type = UnitType.FOOTMAN; gso.name = "Guard Hale";
            crown.soldiers.add(gso);
            check(Math.abs(hit(hired, zombie, 10) - 10) < 1e-4, "No War Horn: a hired recruit's hit is unchanged");
            check(Decrees.cost(m, owner, Decree.WAR_HORN).emeralds() == 24, "War Horn costs 24 emeralds");
            crown.stock.force(Items.EMERALD, 23);
            check(Decrees.blocker(server, m, crown, Decree.WAR_HORN).contains("24 emeralds"), "Short of emeralds: refused");
            crown.stock.force(Items.EMERALD, 1);
            check(Decrees.issue(other, m, crown, Decree.WAR_HORN) != null, "Only the ruler issues decrees");
            CompoundTag bad = new CompoundTag(); bad.putString("decree", "WAR_HORN");
            check(Statecraft.handle(other, m, crown, "scDecree", bad) != null, "Kingdom page action refuses a stranger");
            RecruitsBridge.addMorale(guard, 30 - RecruitsBridge.morale(guard));
            check(Statecraft.handle(ruler, m, crown, "scDecree", bad) == null, "Ruler sounds the War Horn from the Kingdom page");
            check(crown.stock.count(Items.EMERALD) == 0, "Paid from the capital's stockpile");
            check(Decrees.active(owner, Decree.WAR_HORN) && Decrees.until(server, owner, Decree.WAR_HORN) == now + 3600, "War Horn in force for 3 minutes");
            check(Math.abs(RecruitsBridge.morale(guard) - 50) < .01, "War Horn: +20 morale");
            check(Decrees.warHorn(guard) == .15 && Decrees.warHorn(hired) == .15 && Decrees.warHorn(foreign) == 0, "+15% for every recruit you own, none for others");
            check(Math.abs(hit(hired, zombie, 10) - 11.5) < 1e-4, "A real hit deals +15%");
            check(Math.abs(hit(foreign, zombie, 10) - 10) < 1e-4, "A stranger's recruit gets nothing");
            check(Decrees.warHorn(guard) <= Decree.WAR_HORN_BONUS && Decree.WAR_HORN_BONUS == .15, "War Horn is one term capped at +15%");
            RecruitsBridge.addMorale(guard, 10 - RecruitsBridge.morale(guard));
            Decrees.tick(server, m, 1);
            check(RecruitsBridge.morale(guard) >= Decree.WAR_HORN_FLOOR - .01, "War Horn holds morale at 50");
            check(Decrees.blocker(server, m, crown, Decree.WAR_HORN).startsWith("In force"), "Cannot sound it twice");
            data.realm(owner).until.put(Decree.WAR_HORN, now - 1);
            Decrees.tick(server, m, 2);
            check(!Decrees.active(owner, Decree.WAR_HORN) && !data.realm(owner).until.containsKey(Decree.WAR_HORN) && Decrees.warHorn(hired) == 0,
                    "War Horn ends");
            check(Math.abs(hit(hired, zombie, 10) - 10) < 1e-4, "After it ends, hits are plain again");
            check(crown.log.stream().anyMatch(x -> x.contains("War Horn has ended")), "Its end is announced");
            crown.stock.force(Items.EMERALD, 24);
            check(Decrees.blocker(server, m, crown, Decree.WAR_HORN).startsWith("Ready again"), "Cooldown of a day");
            data.realm(owner).ready.put(Decree.WAR_HORN, now - 1);
            check(Decrees.blocker(server, m, crown, Decree.WAR_HORN) == null, "Ready after the cooldown");
            crown.stock.take(Items.EMERALD, 24);

            // ---- Royal Feast: food cost, mood, loyalty, sickness while the crowds last
            int people = 30 + 12 + 10 + 2;
            Decrees.Cost feast = Decrees.cost(m, owner, Decree.ROYAL_FEAST);
            check(feast.emeralds() == 32 && feast.food() == Math.max(60, 2 * people), "Feast costs 32 emeralds and 2 food per person");
            crown.stock.force(Items.EMERALD, 32);
            crown.stock.force(Items.BREAD, 10);
            check(Decrees.blocker(server, m, crown, Decree.ROYAL_FEAST).contains("food"), "Not enough food: refused");
            crown.stock.force(Items.BREAD, 30);
            int foodBefore = Decrees.food(crown.stock);
            check(Decrees.issue(server, m, crown, Decree.ROYAL_FEAST) == null, "Royal Feast proclaimed");
            check(foodBefore - Decrees.food(crown.stock) >= feast.food() && foodBefore - Decrees.food(crown.stock) < feast.food() + 5
                    && crown.stock.count(Items.EMERALD) == 0, "Feast food and emeralds taken");
            List<String> why = new ArrayList<>();
            check(Decrees.mood(shire, why) == 10 && why.contains("+10 royal feast"), "Feast: +10 mood in every town");
            Simulation.recompute(shire);
            check(shire.d.happinessReasons.contains("+10 royal feast"), "Feast shows in the town's mood");
            check(Loyalty.factors(server, m, march, fealty).stream().anyMatch(e -> e.getKey().equals("royal feast") && e.getValue() == 4), "Feast: +4 loyalty a day");
            check(livingrealms.hardship.Hardships.sickFactors(shire, now).stream().anyMatch(e -> e.getKey().equals("feast crowds") && e.getValue() == 1.5),
                    "Feast crowds: sickness 1.5x");
            long late = now + Decree.feastCrowds() + 1;
            check(livingrealms.hardship.Hardships.sickFactors(shire, late).stream().noneMatch(e -> e.getKey().equals("feast crowds")), "Crowds gone after half a day");
            expire(server, owner, Decree.ROYAL_FEAST);
            Decrees.tick(server, m, 3);
            why.clear();
            check(Decrees.mood(shire, why) == 0 && why.isEmpty(), "Feast over: no mood");

            // ---- Tax Holiday and Royal Levy
            Simulation.recompute(crown);
            double taxed = crown.d.emeraldsPerDay;
            check(taxed > 0, "The capital pays head tax");
            check(Decrees.issue(server, m, crown, Decree.TAX_HOLIDAY) == null, "Tax Holiday proclaimed (free)");
            Simulation.recompute(crown);
            check(crown.d.emeraldsPerDay < taxed && Decrees.taxFactor(crown) == 0, "No head tax during the holiday");
            check(crown.d.happinessReasons.contains("+8 tax holiday"), "Holiday: +8 mood");
            check(Loyalty.factors(server, m, march, fealty).stream().anyMatch(e -> e.getKey().equals("tax holiday") && e.getValue() == 3), "Holiday: +3 loyalty a day");
            check(Decrees.blocker(server, m, crown, Decree.ROYAL_LEVY).contains("Tax Holiday"), "No Royal Levy during a Tax Holiday");
            expire(server, owner, Decree.TAX_HOLIDAY);
            Decrees.tick(server, m, 4);
            check(Decrees.taxFactor(crown) == 1, "Taxes again after the holiday");
            int crownEm = crown.stock.count(Items.EMERALD), shireEm = shire.stock.count(Items.EMERALD);
            check(Decrees.issue(server, m, crown, Decree.ROYAL_LEVY) == null, "Royal Levy proclaimed");
            check(crown.stock.count(Items.EMERALD) - crownEm == 30 && shire.stock.count(Items.EMERALD) - shireEm == 12, "Levy: 1 emerald per person, into each town");
            why.clear();
            check(Decrees.mood(crown, why) == -10 && why.contains("-10 royal levy"), "Levy: -10 mood");
            check(Loyalty.factors(server, m, march, fealty).stream().anyMatch(e -> e.getKey().equals("royal levy") && e.getValue() == -3), "Levy: -3 loyalty a day");
            check(Decrees.blocker(server, m, crown, Decree.TAX_HOLIDAY).contains("Royal Levy"), "No Tax Holiday while the levy is felt");
            expire(server, owner, Decree.ROYAL_LEVY);
            Decrees.tick(server, m, 5);
            crown.stock.take(Items.EMERALD, crown.stock.count(Items.EMERALD));

            // ---- Amnesty: a restless conquered city, and peace offers to an NPC kingdom
            check(Decrees.blocker(server, m, crown, Decree.AMNESTY).contains("conquered"), "Amnesty needs a conquered city or an NPC war");
            Decrees.Cost am = Decrees.cost(m, owner, Decree.AMNESTY);
            check(am.emeralds() == 96 && am.gold() == 8, "Amnesty costs 96 emeralds and 8 gold");
            Statecraft.War war = new Statecraft.War();
            war.a = owner; war.b = rival.kingdomId(); war.started = now;
            m.state.wars.add(war);
            check(!Wars.accepts(server, m, war, owner, Statecraft.Term.WHITE, 1), "Without Amnesty the rival refuses white peace");
            crown.stock.force(Items.EMERALD, 96);
            check(Decrees.blocker(server, m, march, Decree.AMNESTY).contains("gold"), "Short of gold: refused");
            crown.stock.force(Items.GOLD_INGOT, 8);
            int entries = crown.chronicle.entries.size();
            check(Decrees.issue(server, m, march, Decree.AMNESTY) == null, "Amnesty for March");
            check(fealty.value >= 55 && fealty.lowSteps == 0 && Loyalty.UNREST_BY_CITY.get(march.id) == null, "Rebellion threat ended: loyalty 55, no unrest");
            check(crown.stock.count(Items.EMERALD) == 0 && crown.stock.count(Items.GOLD_INGOT) == 0, "Amnesty paid");
            check(Decrees.peaceBonus(owner) == 15 && Wars.accepts(server, m, war, owner, Statecraft.Term.WHITE, 1), "Amnesty: the rival now takes white peace");
            check(crown.chronicle.entries.size() > entries && crown.chronicle.entries.get(crown.chronicle.entries.size() - 1).kind() == Chronicle.Kind.DECREE
                    && march.chronicle.entries.stream().anyMatch(e -> e.text().contains("Amnesty")), "Amnesty chronicled in every city");
            expire(server, owner, Decree.AMNESTY);
            Decrees.tick(server, m, 6);
            check(Decrees.peaceBonus(owner) == 0, "Amnesty's peace bonus ends");
            m.state.wars.remove(war);

            // ---- Martial Law: only under siege; slower breach, the garrison healed, mood lost; lifted with the siege
            crown.tier = Tier.CAPITAL;
            check(Decrees.rank(m, owner) == Tier.CAPITAL, "Capital rank");
            check(Decrees.blocker(server, m, crown, Decree.MARTIAL_LAW).contains("raided"), "Martial Law only during a siege");
            fake = new Raids.Raid();
            fake.source = rival.id; fake.target = crown.id; fake.attacker = rival.kingdomId(); fake.defender = owner;
            m.raids.add(fake);
            crown.stock.force(Items.EMERALD, 40);
            check(Decrees.breachFactor(crown) == 1, "No martial law: breach at full speed");
            check(Decrees.issue(server, m, crown, Decree.MARTIAL_LAW) == null, "Martial Law declared");
            check(Decrees.breachFactor(crown) == .6, "The gates hold: breach 40% slower");
            why.clear();
            check(Decrees.mood(shire, why) == -10 && why.contains("-10 martial law"), "Martial Law: -10 mood everywhere");
            guard.setHealth(guard.getMaxHealth() - 5);
            float hp = guard.getHealth();
            Decrees.tick(server, m, 3);
            check(guard.getHealth() > hp, "The garrison regenerates");
            m.raids.remove(fake);
            fake = null;
            Decrees.tick(server, m, 7);
            check(!Decrees.active(owner, Decree.MARTIAL_LAW) && Decrees.breachFactor(crown) == 1, "Lifted when the siege ends");

            // ---- Call the Banners: levies from every town, real recruits, cleaned up loaded, unloaded and across a reload
            int expect = 6 + 2 + 3; // Capital, Village, Town; the Outpost has too few people
            check(Decrees.levies(m, owner) == expect, "Levies by rank: " + Decrees.levies(m, owner));
            Decrees.Cost banners = Decrees.cost(m, owner, Decree.CALL_THE_BANNERS);
            check(banners.emeralds() == 48 + 4 * expect && banners.food() == 10 * expect, "Banners cost 48 + 4 a levy, 10 food a levy");
            crown.stock.force(Items.EMERALD, banners.emeralds());
            crown.stock.force(Items.BREAD, 30);
            // first to a town in unloaded ground: they wait to muster
            check(Decrees.issue(server, m, outpost, Decree.CALL_THE_BANNERS) == null, "Banners called to the Outpost");
            check(data.musters().size() == 1 && data.musters().get(0).count == expect && outpost.soldiers.isEmpty(), "Unloaded town: levies wait to muster");
            expire(server, owner, Decree.CALL_THE_BANNERS);
            data.musters.get(0).until = now - 1;
            Decrees.tick(server, m, 8);
            check(data.musters().isEmpty(), "A muster that never loaded lapses with the decree");
            crown.stock.force(Items.EMERALD, banners.emeralds());
            crown.stock.force(Items.BREAD, 30);
            int garrison = shire.soldiers.size(), chron = shire.chronicle.entries.size();
            check(Decrees.issue(server, m, shire, Decree.CALL_THE_BANNERS) == null, "Banners called to Shire");
            List<UUID> levies = new ArrayList<>();
            for (var e : data.levies().entrySet()) if (e.getValue().kingdom.equals(owner)) levies.add(e.getKey());
            check(levies.size() == expect && shire.soldiers.size() == garrison + expect, "Every levy mustered at Shire: " + levies.size());
            for (UUID id : levies) {
                Entity e = level.getEntity(id);
                check(e != null && RecruitsBridge.isRecruit(e) && RecruitsBridge.ownedBy(e, owner) && Decrees.isLevy(e)
                        && e.getPersistentData().getLong(Decrees.LEVY_TAG) == now + Decree.day() && shire.contains(e.blockPosition()), "Levy is a real recruit at Shire");
                spawned.add(e);
            }
            check(level.getEntity(levies.get(2)) != null && RecruitsBridge.ranged(level.getEntity(levies.get(2))), "Every third levy is a bowman");
            check(shire.chronicle.entries.size() > chron && shire.chronicle.entries.get(shire.chronicle.entries.size() - 1).text().contains("Call the Banners"),
                    "Call the Banners chronicled");
            check(Decrees.view(ruler, m, shire).getInt("levies") == expect, "Kingdom page counts the levies");
            // one levy goes home while loaded
            UUID first = levies.get(0);
            data.levies.get(first).until = now - 1;
            Decrees.tick(server, m, 9);
            check(level.getEntity(first) == null && shire.soldiers.stream().noneMatch(so -> so.entity.equals(first)) && !data.levies().containsKey(first),
                    "A loaded levy disbands cleanly");
            // one is in unloaded ground when its time is up, and the server restarts
            UUID away = levies.get(1);
            Entity awayE = level.getEntity(away);
            CompoundTag saved = new CompoundTag();
            check(awayE.save(saved) && saved.getCompound("ForgeData").contains(Decrees.LEVY_TAG), "The levy mark is saved with the entity");
            awayE.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            DecreeData reloaded = DecreeData.load(data.save(new CompoundTag()));
            check(reloaded.levies().size() == data.levies().size() && reloaded.realm(owner).until.get(Decree.CALL_THE_BANNERS).equals(data.realm(owner).until.get(Decree.CALL_THE_BANNERS))
                    && reloaded.realm(owner).ready.get(Decree.CALL_THE_BANNERS).equals(data.realm(owner).ready.get(Decree.CALL_THE_BANNERS)), "Decrees and levies survive a save");
            SettlementManager mReload = SettlementManager.load(m.save(new CompoundTag()));
            check(mReload.get(shire.id).soldiers.size() == shire.soldiers.size(), "Levies' garrison places survive a save");
            server.overworld().getDataStorage().set("livingrealms_decrees", reloaded);
            data = DecreeData.get(server);
            check(data == reloaded, "Reloaded decree data in use");
            data.realm(owner).until.put(Decree.CALL_THE_BANNERS, now - 1);
            for (DecreeData.Levy v : data.levies.values()) v.until = now - 1;
            Decrees.tick(server, m, 10);
            check(data.levies().isEmpty() && data.disbanding().contains(away), "After the reload, the decree ends: the unloaded levy is marked to go home");
            check(shire.soldiers.size() == garrison, "Garrison back to its own soldiers");
            for (UUID id : levies) if (!id.equals(away)) check(level.getEntity(id) == null, "Loaded levies are gone");
            check(!Decrees.active(owner, Decree.CALL_THE_BANNERS) && shire.log.stream().anyMatch(x -> x.contains("Call the Banners has ended")), "Banners end announced");
            Entity back = EntityType.loadEntityRecursive(saved, level, x -> x);
            check(back != null && !level.addFreshEntity(back) && level.getEntity(away) == null && !data.disbanding().contains(away), "The unloaded levy never comes back when it loads");
            // a stray levy the data no longer knows, with its time up
            Entity stray = RecruitsBridge.spawn(level, UnitType.FOOTMAN, shire.center, owner, "Stray Levy");
            CompoundTag st = new CompoundTag();
            stray.getPersistentData().putLong(Decrees.LEVY_TAG, now - 1);
            stray.save(st);
            stray.discard();
            Entity stray2 = EntityType.loadEntityRecursive(st, level, x -> x);
            check(stray2 != null && !level.addFreshEntity(stray2), "An expired levy is turned away even if the data forgot it");
            check(Decrees.blocker(server, m, shire, Decree.CALL_THE_BANNERS).startsWith("Ready again"), "Banners cooldown 5 days");

            // ---- view
            CompoundTag view = Decrees.view(ruler, m, crown);
            check(view.getList("list", 10).size() == 7 && view.getString("rank").equals("Capital") && view.getBoolean("researched"), "Kingdom page lists the seven decrees");
            CompoundTag sc = Statecraft.view(ruler, m, crown);
            check(sc.contains("decrees"), "Decrees ride with the Kingdom page view");

            // ---- NPC kingdoms in battle
            double wealth = rival.wealth;
            Strategy.decrees(server, m, rival, crown);
            check(Decrees.active(rival.kingdomId(), Decree.WAR_HORN) && rival.wealth == wealth - 48, "An attacking NPC City sounds the War Horn from its treasury");
            check(!Decrees.active(rival.kingdomId(), Decree.CALL_THE_BANNERS), "A City-rank NPC kingdom cannot call the banners");
            int bg = bastion.soldiers.size();
            Strategy.decrees(server, m, crown, bastion);
            check(Decrees.active(bastion.kingdomId(), Decree.WAR_HORN) && Decrees.active(bastion.kingdomId(), Decree.CALL_THE_BANNERS)
                    && bastion.soldiers.size() == bg + 6, "A besieged NPC Capital sounds the horn and calls its banners");
            check(!Decrees.active(owner, Decree.WAR_HORN), "The player's horn is untouched");
            for (var e : data.levies().entrySet()) if (e.getValue().kingdom.equals(bastion.kingdomId())) spawned.add(level.getEntity(e.getKey()));
            double w2 = bastion.wealth;
            Strategy.decrees(server, m, crown, bastion);
            check(bastion.wealth == w2, "NPC decrees respect cooldowns");
            expire(server, bastion.kingdomId(), Decree.CALL_THE_BANNERS);
            for (DecreeData.Levy v : data.levies.values()) v.until = now - 1;
            Decrees.tick(server, m, 11);
            check(bastion.soldiers.size() == bg && data.levies().isEmpty(), "NPC levies go home too");
        } finally {
            if (fake != null) m.raids.remove(fake);
            for (Entity e : spawned) if (e != null && !e.isRemoved()) e.discard();
            DecreeData data = DecreeData.get(server);
            for (Settlement s : towns) { data.realms.remove(s.kingdomId()); m.state.loyalty.remove(s.id); Loyalty.unrest(s.id, null); m.remove(s); }
            data.realms.remove(owner);
            data.levies.clear();
            data.musters.clear();
            data.disband.clear();
        }
    }

    private DecreesSelfTest() {}
}
