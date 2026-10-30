package livingrealms.dragon;

import com.mojang.authlib.GameProfile;
import livingrealms.Config;
import livingrealms.LivingRealms;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.building.Construction;
import livingrealms.chronicle.Chronicle;
import livingrealms.fire.Fires;
import livingrealms.military.DragonRiders;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import livingrealms.research.ResearchTree;
import livingrealms.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Dragons in kingdom play (docs/dragons.md). Without Ice and Fire: the roost is gated and buildable but inert, wild
 * attacks and sorties never start, scorpions still shoot. With it (-Pintegrations): an egg hatches in the roost and grows,
 * a Dragon Rider takes the roost dragon, a tamed dragon moves into a second roost, a scorpion bolt hurts a dragon, and
 * then, live on the server tick, a wild dragon attacks a town (alarm, bowmen, roost dragon, scorpions, a capped fire
 * fought and chronicled with the dragon as its cause) while an NPC kingdom raids another town with its dragon and rider.
 * {@code ./gradlew runServer -PdragonTest -PdragonTestDir=<fresh dir> [-Pintegrations]}; result in dragon-test-result.txt.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class DragonSelfTest {
    private static int checks;
    private static final List<String> notes = new ArrayList<>();
    private static void check(boolean ok, String text) {
        if (!ok) throw new IllegalStateException(text);
        checks++;
    }

    // live scenario state
    static boolean running;
    static ServerLevel level;
    static SettlementManager m;
    static Settlement home, eyrie, drakmoor, lowmere;
    static Mob wild;
    static TamableAnimal homeDragon, npcDragon;
    static Flight attack;
    static Raids.Raid raid;
    static Flight sortie;
    static LivingEntity bowman;
    static long t0;
    static int phase, homeShots0, lowShots0, maxFires, maxSortieFires, sortieStrafes;
    static boolean archersOn, roostOn, alarmed;
    static final List<Entity> spawned = new ArrayList<>();
    static final List<Settlement> towns = new ArrayList<>();

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.dragonTest")) return;
        MinecraftServer server = event.getServer();
        try {
            setup(server);
            if (DragonApi.installed()) { running = true; t0 = level.getGameTime(); return; }
            end(server, null);
        } catch (Throwable t) { end(server, t); }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (!running || e.phase != TickEvent.Phase.END) return;
        try { if (live(e.getServer())) end(e.getServer(), null); }
        catch (Throwable t) { end(e.getServer(), t); }
    }

    static void end(MinecraftServer server, Throwable error) {
        running = false;
        String report = error == null ? "PASS: " + checks + " dragon assertions (" + (DragonApi.installed() ? "with Ice and Fire" : "without Ice and Fire")
                + (notes.isEmpty() ? "" : "; " + String.join("; ", notes)) + ")" : "FAIL after " + checks + " assertions: " + error;
        if (error == null) LivingRealms.LOG.info("DRAGON SELF TEST {}", report);
        else LivingRealms.LOG.error("DRAGON SELF TEST FAILED", error);
        try {
            for (Raids.Raid r : List.copyOf(m.raids)) { m.raids.remove(r); Sorties.raidEnded(r); }
            for (Entity x : spawned) if (x != null) x.discard();
            for (Settlement s : towns) m.remove(s);
        } catch (Throwable ignored) {}
        try { Files.writeString(Path.of("dragon-test-result.txt"), report); } catch (Exception ex) { throw new RuntimeException(ex); }
        server.halt(false);
    }

    // ------------------------------------------------------------------ helpers

    static void ground(BlockPos c, int r) {
        for (int x = c.getX() - r; x <= c.getX() + r; x++) for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
            for (int y = 96; y <= 120; y++)
                level.setBlock(new BlockPos(x, y, z), (y < 99 ? Blocks.DIRT : y == 99 ? Blocks.GRASS_BLOCK : Blocks.AIR).defaultBlockState(), 2);
        }
    }

    static void force(BlockPos p, int r) {
        for (int x = (p.getX() >> 4) - r; x <= (p.getX() >> 4) + r; x++) for (int z = (p.getZ() >> 4) - r; z <= (p.getZ() >> 4) + r; z++)
            level.setChunkForced(x, z, true);
        level.getChunkSource().tick(() -> true, true);
        for (int i = 0; i < 20000 && !level.isPositionEntityTicking(p); i++) {
            level.getChunkSource().tick(() -> true, true);
            if (!level.getChunkSource().pollTask()) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
    }

    static Settlement town(UUID owner, String name, BlockPos c, Tier tier) {
        Settlement s = new Settlement(); s.owner = owner; s.ownerName = owner == null ? "" : name + " Lord"; s.name = name; s.center = c;
        s.founded = level.getGameTime(); s.tier = tier;
        m.add(s);
        towns.add(s);
        return s;
    }

    /** A standing building, its blocks set at once. */
    static Plot building(Settlement s, String type, BlockPos origin) {
        Plot p = new Plot(); p.id = s.nextId++; p.typeId = type; p.blueprintKey = BuildingCatalog.get(type).blueprintKey(1, s.style);
        p.origin = origin; p.state = Plot.State.BUILT; s.plots.add(p);
        Blueprint b = p.blueprint();
        for (int i = 0; i < b.size(); i++) level.setBlock(b.worldPos(i, p.origin, p.rotation), b.worldState(i, p.rotation), Construction.FLAGS);
        return p;
    }

    static LivingEntity soldier(Settlement s, BlockPos at, UnitType type) {
        Entity e = RecruitsBridge.spawn(level, type, at, s.kingdomId(), s.name + " " + type.label);
        if (!(e instanceof LivingEntity l)) throw new IllegalStateException("Recruit spawn failed at " + at);
        RecruitsBridge.setSlot(e, EquipmentSlot.MAINHAND, new ItemStack(type == UnitType.BOWMAN ? Items.BOW : Items.IRON_SWORD));
        if (type == UnitType.BOWMAN) RecruitsBridge.addToPack(e, new ItemStack(Items.ARROW, 64));
        var so = new Settlement.Soldier(); so.entity = e.getUUID(); so.type = type; so.name = s.name + " " + type.label;
        s.soldiers.add(so);
        spawned.add(e);
        return l;
    }

    static boolean chronicled(Settlement s, Chronicle.Kind kind, String text) {
        return s.chronicle.entries.stream().anyMatch(e -> e.kind() == kind && e.text().contains(text));
    }

    /** Plans a roost the way the player does, then the builders raise it. */
    static Plot roost(Settlement s, BlockPos origin, boolean ai) {
        s.d.hasTownHall = true;
        String err = Planning.plan(level, s, BuildingCatalog.get(Roosts.ID), origin, Rotation.NONE, ai);
        check(err == null, "The roost can be planned in " + s.name + ": " + err);
        CityPlanningSelfTest.stock(s);
        s.stock.force(Items.GOLD_BLOCK, 64);
        s.stock.force(Items.HAY_BLOCK, 256);
        CityPlanningSelfTest.buildQueued(level, s);
        Plot p = Roosts.roost(s);
        check(p != null && p.state == Plot.State.BUILT, "The roost is built in " + s.name);
        return p;
    }

    // ------------------------------------------------------------------ the synchronous part

    static void setup(MinecraftServer server) {
        level = server.overworld();
        m = SettlementManager.get(server);
        level.setDayTime(1000);
        DragonConfig.ATTACK_SECONDS.set(20);
        Config.FIRE_MAX_TICKS.set(1600);
        UUID lord = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "DragonLord")).getUUID();
        BlockPos c = new BlockPos(12288, 100, 12288);
        force(c, 8);
        ground(c, 64);

        // ------------------------------------------------------------ the roost: a type, gated by rank and research
        BuildingType type = BuildingCatalog.get(Roosts.ID);
        check(type != null && type.category == Category.STABLE && type.minTier == Tier.CITY.number() && Roosts.RESEARCH.equals(type.research)
                && type.maxCount == 1, "The Dragon Roost is a City building behind Dragonry");
        check(ResearchTree.get(Roosts.RESEARCH) != null && ResearchTree.get(Scorpions.RESEARCH) != null, "Dragonry and Scorpions research load");
        check(type.w == 21 && type.d == 21, "The roost is a big open court: " + type.w + " x " + type.d);
        home = town(lord, "Wyrmhold", c, Tier.TOWN);
        check(home.cannotBuild(type) != null && home.cannotBuild(type).contains("City"), "A Town cannot build a roost: " + home.cannotBuild(type));
        home.tier = Tier.CITY;
        check(home.cannotBuild(type) != null && home.cannotBuild(type).contains("Dragonry"), "A City needs Dragonry first: " + home.cannotBuild(type));
        home.research.addAll(ResearchTree.nodes().keySet());
        Plot roost = roost(home, c.offset(30, 0, -12), false);
        BlockPos nest = Roosts.nest(roost);
        check(level.getBlockState(nest).is(DragonApi.nest()) && level.getBlockState(nest.offset(3, 0, 3)).is(Blocks.GOLD_BLOCK)
                && level.getBlockState(Roosts.perch(roost)).isAir() && level.getBlockState(Roosts.perch(roost).above(6)).isAir(),
                "The nest stands in the open court, the hoard round it");
        Plot cottage = building(home, "livingrealms:cottage", c.offset(-14, 0, 4));
        Plot tower = building(home, "livingrealms:watchtower", c.offset(-6, 0, -34));
        check(Fires.risk(home, roost) < Fires.risk(home, cottage) / 2, "The stone roost hardly burns: " + Fires.risk(home, roost) + " vs " + Fires.risk(home, cottage));
        Roosts.tick(server, m);
        DragonData.Roost rec = Roosts.record(server, home);
        check(rec != null && chronicled(home, Chronicle.Kind.DRAGON, "Dragon Roost of Wyrmhold was finished"), "The finished roost is chronicled");
        CompoundTag view = Roosts.view(server, home);
        check(view.getBoolean("roost") && view.getBoolean("scorpions") && view.getBoolean("installed") == DragonApi.installed(), "The City Manager sees the roost and the scorpions");

        // ------------------------------------------------------------ scorpions (with or without Ice and Fire)
        check(Scorpions.armed(home) && Scorpions.mounts(level, home).contains(tower), "Scorpions mount on the watchtower");
        Zombie zombie = EntityType.ZOMBIE.create(level);
        zombie.moveTo(tower.center().getX() + 12.5, 100, tower.center().getZ() + .5, 0, 0);
        zombie.setNoAi(true);
        level.addFreshEntity(zombie);
        spawned.add(zombie);
        int shots = Scorpions.volley(level, m, home);
        check(shots == 1 && Scorpions.SHOTS.getOrDefault(home.id, 0) == 1, "A threat in town: the tower looses a bolt (" + shots + ")");
        Arrow bolt = (Arrow) Scorpions.LIVE.keySet().iterator().next();
        float z0 = zombie.getHealth();
        for (int i = 0; i < 40 && !bolt.isRemoved() && zombie.getHealth() == z0; i++) bolt.tick();
        check(zombie.getHealth() <= z0 - 9.5f || zombie.isDeadOrDying(), "The flying bolt strikes the zombie for a scorpion's damage: " + z0 + " -> " + zombie.getHealth() + " bolt " + bolt.position() + " removed " + bolt.isRemoved() + " ground " + bolt.getDeltaMovement() + " zombie " + zombie.position() + " tower " + tower.center());
        LivingEntity guard = soldier(home, c.offset(2, 0, 2), UnitType.FOOTMAN);
        Arrow probe = Scorpions.shoot(level, home, Vec3.atCenterOf(c.above(20)), guard);
        probe.discard();
        float g0 = guard.getHealth();
        guard.hurt(level.damageSources().arrow(probe, null), 5);
        check(guard.getHealth() == g0, "A bolt never strikes the town's own soldier");
        var restored = DragonData.load(DragonData.get(server).save(new CompoundTag()));
        check(restored.roosts.containsKey(home.id) && restored.roosts.get(home.id).announced, "Roosts survive save and reload");
        check(DragonData.Roost.load(new CompoundTag()).eggElement == -1, "An empty record loads with no egg");

        if (!DragonApi.installed()) {
            // ------------------------------------------------------------ without Ice and Fire nothing happens
            check(rec.state.equals("Needs Ice and Fire") && !rec.hasDragon(), "Without Ice and Fire the roost stays empty: " + rec.state);
            check(!DragonAttacks.on() && DragonAttacks.roll(level, home, level.random, 1.0) == null && DragonAttacks.intruder(level, home) == null,
                    "Without Ice and Fire no wild dragon attacks");
            check(DragonApi.egg(Items.EGG) == null && DragonApi.eggItem(0, 0) == null && DragonApi.hatch(level, c, 0, 0, lord, 1) == null
                    && DragonApi.spawnWild(level, c, 0, 60) == null, "Without Ice and Fire no dragon is made");
            check(DragonRiders.available(level, c, lord) == null && Roosts.forRider(level, c, lord) == null, "No rider mount without Ice and Fire");
            Settlement npc = town(null, "Drakmoor", c.offset(300, 0, 0), Tier.CITY);
            Raids.Raid fake = new Raids.Raid(); fake.source = npc.id; fake.target = home.id; fake.attacker = npc.kingdomId(); fake.defender = home.kingdomId();
            check(Sorties.raidStarted(server, m, fake, npc, home) == null && Flight.ACTIVE.isEmpty(), "No sortie without Ice and Fire");
            for (int i = 0; i < 3; i++) Roosts.tick(server, m);
            DragonEvents.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END, () -> true, server));
            check(Flight.ACTIVE.isEmpty() && !Roosts.view(server, home).contains("attack") && !Roosts.view(server, home).getBoolean("installed"),
                    "Ticks pass quietly; the panel says Ice and Fire is missing");
            return;
        }

        // ------------------------------------------------------------ an egg hatches in the roost
        rec.state = "";
        home.stock.force(DragonApi.eggItem(0, 0), 1);
        Roosts.tick(server, m);
        check(rec.incubating() && home.stock.count(DragonApi.eggItem(0, 0)) == 0 && home.log.stream().anyMatch(l -> l.contains("red dragon egg")),
                "The keepers take the red egg from the stockpile: " + rec.state);
        rec.hatchAt = level.getGameTime();
        Roosts.tick(server, m);
        homeDragon = Roosts.dragon(server, home);
        check(homeDragon != null && homeDragon.isTame() && lord.equals(homeDragon.getOwnerUUID()) && DragonApi.element(homeDragon).equals("fire")
                && rec.hatched && homeDragon.distanceToSqr(Vec3.atCenterOf(Roosts.perch(roost))) < 9, "A tamed fire dragon hatches on the nest");
        spawned.add(homeDragon);
        check(chronicled(home, Chronicle.Kind.DRAGON, "hatched in the roost of Wyrmhold") && !rec.name.isEmpty(), "The hatching is chronicled: " + rec.name);
        int age = DragonApi.ageDays(homeDragon);
        rec.fedDay -= 1;
        Roosts.tick(server, m);
        check(DragonApi.ageDays(homeDragon) == age + DragonConfig.i(DragonConfig.GROWTH_DAYS), "The keepers' food makes it grow: " + age + " -> " + DragonApi.ageDays(homeDragon));
        check(rec.state.equals("Perched") && DragonApi.command(homeDragon) == DragonApi.SIT, "It perches: " + rec.state);
        homeDragon.teleportTo(c.getX() - 55.5, 110, c.getZ());
        Roosts.tick(server, m);
        check(homeDragon.distanceToSqr(Vec3.atCenterOf(Roosts.perch(roost))) < 9, "Strayed far, it is brought home");

        // ------------------------------------------------------------ it carries a Dragon Rider trained in the town
        DragonApi.setAgeDays(homeDragon, 60);
        BlockPos barracks = c.offset(-40, 0, 20);
        check(homeDragon.distanceToSqr(Vec3.atCenterOf(barracks)) > 48 * 48 && DragonRiders.available(level, barracks, lord) == homeDragon,
                "The roost dragon answers a rider trained across town");
        Entity rider = RecruitsBridge.spawn(level, UnitType.DRAGON_RIDER, barracks, lord, "Wyrmhold Dragon Rider");
        check(rider != null && rider.getVehicle() == homeDragon, "The Dragon Rider mounts the roost dragon");
        Roosts.tick(server, m);
        check(rec.state.startsWith("Carrying"), "The roost shows its dragon carrying the rider: " + rec.state);
        rider.stopRiding(); rider.discard();
        homeDragon.teleportTo(Roosts.perch(roost).getX() + .5, Roosts.perch(roost).getY(), Roosts.perch(roost).getZ() + .5);
        Roosts.tick(server, m);

        // ------------------------------------------------------------ a tamed dragon moves into a second roost
        UUID other = UUID.randomUUID();
        BlockPos ec = c.offset(0, 0, 400);
        force(ec, 2);
        ground(ec, 30);
        eyrie = town(other, "Eyrie", ec, Tier.CITY);
        eyrie.research.add(Roosts.RESEARCH);
        Plot eroost = roost(eyrie, ec.offset(-10, 0, -10), false);
        LivingEntity pet = DragonApi.spawnWild(level, Roosts.perch(eroost).offset(4, 0, 0), 1, 40);
        spawned.add(pet);
        ((TamableAnimal) pet).setTame(true); ((TamableAnimal) pet).setOwnerUUID(other);
        pet.setCustomName(net.minecraft.network.chat.Component.literal("Frostbite"));
        Roosts.tick(server, m);
        check(Roosts.record(server, eyrie).dragon == null, "A dragon that is not told to sit does not move in");
        DragonApi.command(pet, DragonApi.SIT);
        Roosts.tick(server, m);
        check(pet.getUUID().equals(Roosts.record(server, eyrie).dragon) && !Roosts.record(server, eyrie).hatched
                && chronicled(eyrie, Chronicle.Kind.DRAGON, "Frostbite"), "Sat in the roost, the ruler's ice dragon moves in");
        check(Roosts.forRider(level, ec, lord) == null, "Another ruler cannot take it as a mount");

        // ------------------------------------------------------------ a bolt against a dragon
        LivingEntity target = DragonApi.spawnWild(level, tower.center().offset(14, 4, 0), 0, 60);
        spawned.add(target);
        ((Mob) target).setNoAi(true);
        Arrow heavy = Scorpions.shoot(level, home, Vec3.atCenterOf(tower.center().above(12)), target);
        heavy.discard();
        float h0 = target.getHealth();
        target.hurt(level.damageSources().arrow(heavy, null), 1);
        float drop = h0 - target.getHealth();
        check(Math.abs(drop - DragonConfig.d(DragonConfig.BOLT_DRAGON_DAMAGE)) < .6, "A scorpion bolt hurts a dragon for " + drop + " (an arrow's 1)");
        target.invulnerableTime = 0;
        float h1 = target.getHealth();
        int before = Scorpions.HITS.getOrDefault(home.id, 0);
        Scorpions.volley(level, m, home);
        Arrow flying = null;
        for (Arrow a : Scorpions.LIVE.keySet()) if (!a.isRemoved()) flying = a;
        for (int i = 0; flying != null && i < 40 && !flying.isRemoved() && target.getHealth() == h1; i++) flying.tick();
        check(target.getHealth() < h1 && Scorpions.HITS.getOrDefault(home.id, 0) > before && Scorpions.hitBy(target.getUUID()),
                "A real bolt from the tower strikes the dragon: " + h1 + " -> " + target.getHealth());
        notes.add("bolt vs dragon " + drop + ", flying bolt " + (h1 - target.getHealth()));
        target.discard();

        // ------------------------------------------------------------ set up the live part: a wild dragon, an NPC raid
        bowman = soldier(home, c.offset(-8, 0, 10), UnitType.BOWMAN);
        soldier(home, c.offset(-10, 0, 12), UnitType.BOWMAN);
        soldier(home, c.offset(-12, 0, 10), UnitType.FOOTMAN);
        wild = (Mob) DragonApi.spawnWild(level, c.offset(20, 30, 100), 0, 60);
        spawned.add(wild);
        check(DragonAttacks.candidate(wild) && DragonAttacks.roll(level, home, level.random, 0) == null, "A roll that fails starts nothing");
        attack = DragonAttacks.roll(level, home, level.random, 1);
        check(attack != null && attack.dragon.equals(wild.getUUID()) && wild.isNoAi() && Flight.flying(wild), "A sure roll draws the wild dragon to Wyrmhold");
        alarmed = home.alarmUntil > level.getGameTime() && home.log.stream().anyMatch(l -> l.startsWith("Dragon!"));
        check(alarmed, "The bell rings and the town hears of the dragon");
        check(DragonAttacks.intruder(level, home) == null && DragonAttacks.roll(level, home, level.random, 1) == null, "One attack at a time, then the town rests");
        check(Roosts.view(server, home).getString("attack").contains("attacking"), "The City Manager shows the attack");
        homeShots0 = Scorpions.SHOTS.getOrDefault(home.id, 0);

        BlockPos dc = c.offset(600, 0, 0), lc = c.offset(760, 0, 0);
        force(dc, 3); force(lc, 3);
        ground(dc, 40); ground(lc, 40);
        drakmoor = town(null, "Drakmoor", dc, Tier.CITY);
        drakmoor.research.addAll(ResearchTree.nodes().keySet());
        Plot droost = roost(drakmoor, dc.offset(-30, 0, -10), true);
        Roosts.tick(server, m);
        DragonData.Roost drec = Roosts.record(server, drakmoor);
        check(drec != null && drec.nextEgg > level.getGameTime() && !drec.incubating(), "An NPC roost waits for its egg");
        drec.nextEgg = level.getGameTime();
        Roosts.tick(server, m);
        check(drec.incubating(), "The NPC keepers get an egg");
        drec.hatchAt = 0;
        Roosts.tick(server, m);
        npcDragon = Roosts.dragon(server, drakmoor);
        check(npcDragon != null && Roosts.owner(drakmoor).equals(npcDragon.getOwnerUUID()) && drec.hatched, "The NPC kingdom's dragon hatches, tamed to the kingdom");
        spawned.add(npcDragon);
        DragonApi.setAgeDays(npcDragon, 60);
        npcDragon.setHealth(npcDragon.getMaxHealth());
        for (int i = 0; i < 6; i++) soldier(drakmoor, dc.offset(-4 + i % 3, 0, 4 + i / 3), UnitType.FOOTMAN);
        lowmere = town(null, "Lowmere", lc, Tier.TOWN);
        lowmere.research.add(Scorpions.RESEARCH);
        building(lowmere, "livingrealms:watchtower", lc.offset(-20, 0, -4));
        building(lowmere, "livingrealms:cottage", lc.offset(6, 0, 6));
        soldier(lowmere, lc.offset(2, 0, -2), UnitType.BOWMAN);
        soldier(lowmere, lc.offset(-2, 0, 2), UnitType.FOOTMAN);
        for (Settlement s : m.all()) s.raidCooldown = 0;
        String err = Raids.start(server, m, drakmoor, lowmere);
        check(err == null && !m.raids.isEmpty(), "Drakmoor raids Lowmere: " + err);
        raid = m.raids.stream().filter(r -> r.source.equals(drakmoor.id)).findFirst().orElseThrow();
        sortie = Sorties.of(raid);
        check(sortie != null && sortie.dragon.equals(npcDragon.getUUID()) && npcDragon.isNoAi(), "The NPC kingdom sends its dragon with the raid");
        Entity npcRider = level.getEntity(sortie.rider);
        check(npcRider != null && npcRider.getVehicle() == npcDragon && RecruitsBridge.isRecruit(npcRider), "A Dragon Rider is on its back");
        spawned.add(npcRider);
        check(npcDragon.distanceToSqr(Vec3.atCenterOf(lc)) < 70 * 70, "It flies in over Lowmere");
        check(lowmere.log.stream().anyMatch(l -> l.startsWith("A dragon!")), "Lowmere hears the dragon coming");
        check(Sorties.raidStarted(server, m, raid, drakmoor, lowmere) == null, "One dragon per raid");
        lowShots0 = Scorpions.SHOTS.getOrDefault(lowmere.id, 0);
        notes.add("roost dragon " + rec.name + ", NPC dragon " + DragonApi.name(npcDragon));
    }

    // ------------------------------------------------------------------ the live part

    /** True when finished. */
    static boolean live(MinecraftServer server) {
        long age = level.getGameTime() - t0;
        if (age > 9000) throw new IllegalStateException("Dragon scenario still running after " + age + " ticks (phase " + phase + ")");
        if (DragonAttacks.SHOT_AT.contains(wild.getUUID())) archersOn = true;
        if (DragonAttacks.ROOST_ENGAGED.contains(homeDragon.getUUID())) roostOn = true;
        if (Flight.flying(wild)) maxFires = Math.max(maxFires, attack.fires);
        if (Flight.flying(npcDragon)) { maxSortieFires = Math.max(maxSortieFires, sortie.fires); sortieStrafes = Math.max(sortieStrafes, sortie.strafes); }
        if (phase == 0) {
            // both dragons fly their sorties
            if (Flight.flying(wild) || Flight.flying(npcDragon)) return false;
            check(attack.strafes > 0, "The wild dragon breathed on the town: " + attack.strafes + " times");
            check(maxFires >= 1 && maxFires <= DragonConfig.i(DragonConfig.MAX_FIRES), "Its breath set buildings alight, no more than allowed: " + maxFires);
            check(Scorpions.SHOTS.getOrDefault(home.id, 0) > homeShots0, "The scorpions shot at it: " + (Scorpions.SHOTS.get(home.id) - homeShots0) + " bolts");
            check(archersOn, "The bowmen turned on the dragon");
            check(roostOn, "The roost's own dragon rose against it");
            check(attack.slain || !wild.isNoAi() && !wild.getPersistentData().getBoolean(Flight.FLAG), "Afterwards the dragon has its own wings back (or is dead)");
            check(chronicled(home, Chronicle.Kind.DRAGON, "A wild fire dragon fell upon Wyrmhold"), "The attack is chronicled");
            check(sortieStrafes > 0 && maxSortieFires <= DragonConfig.i(DragonConfig.MAX_FIRES), "The NPC dragon breathed on Lowmere " + sortieStrafes + " times, " + maxSortieFires + " fires");
            check(Scorpions.SHOTS.getOrDefault(lowmere.id, 0) > lowShots0, "Lowmere's scorpions shot at the raiding dragon");
            check(sortie.rider == null || level.getEntity(sortie.rider) == null, "The rider dismounts and is gone when the sortie ends");
            Plot dp = Roosts.roost(drakmoor);
            if (sortie.slain) check(chronicled(lowmere, Chronicle.Kind.DRAGON, "brought down"), "The fall of the NPC dragon is chronicled");
            else {
                check(npcDragon.distanceToSqr(Vec3.atCenterOf(Roosts.perch(dp))) < 24 * 24 && !npcDragon.isNoAi(), "The NPC dragon flies home to its roost: "
                        + Math.sqrt(npcDragon.distanceToSqr(Vec3.atCenterOf(Roosts.perch(dp)))) + " from the perch, noAi " + npcDragon.isNoAi() + ", " + Roosts.record(server, drakmoor).state);
                check(chronicled(lowmere, Chronicle.Kind.DRAGON, "flew over Lowmere during the raid"), "Lowmere's chronicle tells of the dragon");
            }
            notes.add("wild attack: " + attack.strafes + " breaths, " + maxFires + " fires " + attack.struck + (attack.slain ? ", slain" : ", flew off")
                    + "; sortie: " + sortieStrafes + " breaths, " + maxSortieFires + " fires" + (sortie.slain ? ", shot down" : ", home"));
            m.raids.remove(raid);
            phase = 1;
            return false;
        }
        // the fire the dragon started is fought and chronicled
        if (Fires.active(home) != null) return false;
        Fires.Fire done = Fires.LAST.get(home.id);
        check(done != null && done.cause.contains("dragon"), "The town fire names the dragon as its cause: " + (done == null ? "-" : done.cause));
        check(done.byCrew + done.byTown > 0 || done.peak == 0, "The bucket brigade fought it: " + done.byCrew + " by the crew, " + done.byTown + " by the town");
        if (!done.damaged.isEmpty()) {
            check(done.repairs > 0 && chronicled(home, Chronicle.Kind.FIRE, "dragon"), "The burnt buildings are queued for repair and the fire chronicled: " + done.story);
        } else {
            // the brigade saved everything: check the reckoning on a dragon fire by hand
            Plot cottage = home.plots.stream().filter(p -> p.typeId.equals("livingrealms:cottage")).findFirst().orElseThrow();
            Blueprint b = cottage.blueprint();
            int removed = 0;
            for (int i = 0; i < b.size() && removed < 8; i++) {
                if (b.worldState(i, cottage.rotation).isAir()) continue;
                BlockPos pos = b.worldPos(i, cottage.origin, cottage.rotation);
                if (level.getBlockState(pos).isAir()) continue;
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                removed++;
            }
            Fires.Fire burnt = new Fires.Fire(home.id, done.cause, level.getGameTime(), cottage.box());
            burnt.touched.add(cottage.id);
            burnt.peak = 5;
            Fires.finish(level, home, burnt);
            check(cottage.state == Plot.State.REPAIRING && chronicled(home, Chronicle.Kind.FIRE, "dragon"), "A dragon fire's damage is repaired and chronicled with its cause");
            notes.add("brigade saved every building; repair path checked by hand");
        }
        notes.add("fire: peak " + done.peak + ", " + done.damaged);
        return true;
    }

    private DragonSelfTest() {}
}
