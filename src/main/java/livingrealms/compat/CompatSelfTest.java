package livingrealms.compat;

import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.BowmanEntity;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import livingrealms.LivingRealms;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierRanks;
import livingrealms.military.UnitType;
import livingrealms.settlement.*;
import livingrealms.smithing.Piece;
import livingrealms.smithing.Smithing;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * ./gradlew runServer -PcompatTest -PcompatTestDir=dir [-PpackMods]: each pack-mod integration does its job when its
 * mod is loaded and changes nothing when it is not. Writes compat-test-result.txt and stops the server.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class CompatSelfTest {
    private static final List<String> LOG = new ArrayList<>();
    private static int checks;

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.compatTest")) return;
        MinecraftServer server = event.getServer();
        server.execute(() -> {
            String report;
            try {
                run(server.overworld());
                report = "PASS: " + checks + " assertions\n" + String.join("\n", LOG) + "\n";
                LivingRealms.LOG.info("COMPAT SELF TEST {}", report);
            } catch (Throwable failure) {
                report = "FAIL: " + failure + "\n" + String.join("\n", LOG) + "\n";
                LivingRealms.LOG.error("COMPAT SELF TEST FAILED", failure);
            }
            try { Files.writeString(Path.of("compat-test-result.txt"), report); } catch (Exception ex) { LivingRealms.LOG.error("Cannot write test result", ex); }
            server.halt(false);
        });
    }

    static void run(ServerLevel level) throws Exception {
        for (String mod : List.of(Compat.VASSALS, Compat.RANKS, Compat.MORALE, Compat.SIEGE_WEAPONS, Compat.SIEGE_MACHINES, Compat.SIEGE_CREWS,
                Compat.SIEGE_OVERHAUL, Compat.WORKERS, Compat.CORPSE, Compat.HERALDRY, "kingdomsieges", "smallships"))
            LOG.add((Compat.loaded(mod) ? "loaded  " : "absent  ") + mod);
        ranks(level);
        feudal(level);
        smithing();
        siege(level);
        raiders(level);
        morale(level);
        workers(level);
        corpse(level);
        check(Compat.equipmentMods().isEmpty() != Compat.loaded(Compat.HERALDRY), "Heraldry equipment only when Knights Heraldry is loaded");
    }

    // ------------------------------------------------------------------ fixtures

    static Settlement town(ServerLevel level, String name, UUID owner, int x, Tier tier) {
        Settlement s = new Settlement();
        s.name = name; s.owner = owner; s.ownerName = owner == null ? "" : name + " Ruler"; s.dim = level.dimension(); s.tier = tier;
        level.getChunkAt(new BlockPos(x, 64, 0));
        s.center = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, 0), 0);
        SettlementManager.get(level.getServer()).add(s);
        return s;
    }

    static void drop(ServerLevel level, Settlement... towns) {
        for (Settlement s : towns) SettlementManager.get(level.getServer()).remove(s);
    }

    static void check(boolean ok, String label) {
        if (!ok) throw new IllegalStateException(label);
        checks++;
    }

    static boolean logged(Settlement s, String text) { return s.log.stream().anyMatch(l -> l.contains(text)); }

    // ------------------------------------------------------------------ titles

    static void ranks(ServerLevel level) {
        SettlementManager m = SettlementManager.get(level.getServer());
        UUID a = UUID.randomUUID();
        Settlement one = town(level, "Rank One", a, 3000, Tier.VILLAGE);
        try {
            check(Compat.rank(m, a) == Compat.Rank.COUNT, "One village: Count");
            Settlement two = town(level, "Rank Two", a, 3300, Tier.HAMLET);
            check(Compat.rank(m, a) == Compat.Rank.DUKE, "Two cities: Duke");
            two.tier = Tier.CAPITAL;
            check(Compat.rank(m, a) == Compat.Rank.KING, "A Capital: King");
            check(Compat.rank(m, null) == null && Compat.rank(m, UUID.randomUUID()) == null, "No kingdom, no rank");
            drop(level, two);
        } finally { drop(level, one); }
    }

    static void feudal(ServerLevel level) {
        MinecraftServer server = level.getServer();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Settlement liege = town(level, "Liege Seat", a, 4000, Tier.CAPITAL), vassal = town(level, "Vassal Hold", b, 4400, Tier.VILLAGE);
        var player = FakePlayerFactory.get(level, new GameProfile(a, "CompatRuler"));
        try {
            if (!Compat.loaded(Compat.VASSALS)) {
                check(Compat.raidBlocked(server, vassal, liege) == null, "No feudal oaths without Vassal & Suzerain");
                boolean any = Compat.loaded(Compat.WORKERS) || Compat.loaded(Compat.CORPSE) || SiegeTrains.KINDS.stream().anyMatch(SiegeTrains::usable);
                check(Compat.view(liege, player).getList("lines", 10).isEmpty() != any, "Overview strip only shows loaded pack mods");
                return;
            }
            var factions = FactionEvents.recruitsFactionManager;
            check(factions != null, "Recruits faction manager running");
            factions.addTeam("lr_liege", "Liege Realm", a, "Liege", new CompoundTag(), (byte) 0, ChatFormatting.GOLD);
            factions.addTeam("lr_vassal", "Vassal Realm", b, "Vassal", new CompoundTag(), (byte) 0, ChatFormatting.BLUE);
            try {
                var f = VassalCompat.data(server);
                CompatData mine = CompatData.get(server);
                f.setSuzerain("lr_vassal", "lr_liege");
                CompatEvents.feudal(server, SettlementManager.get(server));
                check(f.getTitle("lr_liege").name().equals("KING"), "Capital kingdom's faction becomes King");
                check(f.getTitle("lr_vassal").name().equals("DUKE"), "A King's vassal sits one rank below (Duke), not its own size");
                check(Compat.raidBlocked(server, vassal, liege) != null && Compat.raidBlocked(server, liege, vassal) != null, "Oath blocks raids both ways");
                check(Raids.canStart(server, SettlementManager.get(server), liege, vassal) != null, "Raids.canStart honours the oath");
                Compat.raidStarted(server, new Raids.Raid(), liege, vassal);
                check(logged(liege, "Your vassal Vassal Realm is raided"), "Liege's capital hears of the raid on its vassal");
                f.clearSuzerain("lr_vassal");
                check(Compat.raidBlocked(server, vassal, liege) == null, "Released oath allows raids");
                f.setTitle("lr_liege", com.knightmare.vassalsuzerain.data.FeudalTitle.DUKE);
                CompatEvents.feudal(server, SettlementManager.get(server));
                check(f.getTitle("lr_liege").name().equals("DUKE"), "A title changed by hand is left alone");
                var lines = Compat.view(liege, player).getList("lines", 10);
                check(lines.stream().anyMatch(t -> ((CompoundTag) t).getString("label").equals("Title")), "Overview shows the feudal title");
                if (Compat.loaded(Compat.RANKS)) {
                    check(RanksCompat.title(server, "lr_liege").equals("King") && RanksCompat.title(server, "lr_vassal").equals("Count"), "Ranks and Titles leader title follows kingdom size");
                    com.knightmare.ranksandtitles.data.FactionRoleSavedData.get(level).setRoleTitle("lr_vassal",
                            com.knightmare.ranksandtitles.faction.FactionRole.LEADER, "Jarl");
                    vassal.tier = Tier.CAPITAL;
                    CompatEvents.feudal(server, SettlementManager.get(server));
                    check(RanksCompat.title(server, "lr_vassal").equals("Jarl"), "A renamed leader title is kept");
                    check(lines.stream().anyMatch(t -> ((CompoundTag) t).getString("value").startsWith("King ")), "Overview shows the ruler title");
                }
                mine.feudal.remove("lr_liege"); mine.feudal.remove("lr_vassal"); mine.ranks.remove("lr_liege"); mine.ranks.remove("lr_vassal");
                f.clearFaction("lr_liege"); f.clearFaction("lr_vassal");
            } finally { factions.removeTeam("lr_liege"); factions.removeTeam("lr_vassal"); }
        } finally { drop(level, liege, vassal); }
    }

    // ------------------------------------------------------------------ siege

    static void smithing() {
        Map<String, String> patterns = Map.of("war_sw_catapult", Compat.SIEGE_WEAPONS, "war_sm_trebuchet", Compat.SIEGE_MACHINES,
                "war_ks_cannon", "kingdomsieges", "war_ship_oak_cog", "smallships");
        LOG.add("war machine patterns: " + Smithing.patterns().keySet().stream().filter(k -> k.startsWith("war_")).sorted().toList());
        patterns.forEach((id, mod) -> {
            var p = Smithing.get(id);
            check((p != null) == Compat.loaded(mod), "Smithy pattern " + id + " only with " + mod);
            if (p != null) check(p.pieces().containsKey(Piece.WAR_MACHINE), "War machine piece " + id);
        });
    }

    static void siege(ServerLevel level) throws Exception {
        MinecraftServer server = level.getServer();
        UUID a = UUID.randomUUID();
        Settlement source = town(level, "Siege Source", a, -100, Tier.TOWN), target = town(level, "Siege Target", null, 100, Tier.TOWN);
        Settlement npc = town(level, "Siege NPC", null, -40, Tier.CITY);
        for (int x = -112; x <= 112; x += 16) level.getChunkAt(new BlockPos(x, 64, 0));
        try {
            Raids.Raid raid = new Raids.Raid();
            raid.source = source.id; raid.target = target.id; raid.attacker = source.kingdomId(); raid.defender = target.kingdomId();
            boolean sw = SiegeTrains.KINDS.stream().anyMatch(k -> k.engineer() && SiegeTrains.usable(k)),
                    sm = SiegeTrains.KINDS.stream().anyMatch(k -> !k.engineer() && SiegeTrains.usable(k));
            check(sw == (Compat.loaded(Compat.SIEGE_WEAPONS)), "Siege Weapons engines usable only with Siege Weapons");
            check(sm == (Compat.loaded(Compat.SIEGE_MACHINES) && Compat.loaded(Compat.SIEGE_CREWS)), "Siege machines need Siege Machines and its Recruits crews");
            check(SiegeTrains.deploy(server, raid, source, target) == null, "No engine in stock, no siege train");
            if (sm) {
                source.stock.force(SiegeTrains.item("siegemachines:trebuchet"), 1);
                Entity engine = SiegeTrains.deploy(server, raid, source, target);
                check(engine != null && BuiltInRegistries.ENTITY_TYPE.getKey(engine.getType()).toString().equals("siegemachines:trebuchet"), "Trebuchet deployed from stock");
                check(source.stock.count(SiegeTrains.item("siegemachines:trebuchet")) == 0, "Engine taken from the stockpile");
                Entity crew = engine.getPassengers().isEmpty() ? null : engine.getPassengers().get(0);
                check(crew instanceof BowmanEntity b && b.getShouldStrategicFire() && target.center.equals(b.StrategicFirePos())
                        && source.kingdomId().equals(b.getOwnerUUID()), "Bowman crew aims the trebuchet at the target centre");
                check(RecruitsBridge.countInPack(crew, SiegeTrains.item("siegemachines:giant_stone")) == 16, "Crew carries ammunition");
                // Fire control: attackers at the centre lift the fire onto clear defenders, or stop it.
                Entity ours = RecruitsBridge.spawn(level, UnitType.FOOTMAN, target.center, a, "Siege test attacker");
                Entity theirs = RecruitsBridge.spawn(level, UnitType.FOOTMAN, target.center.offset(0, 0, 20), null, "Siege test defender");
                try {
                    SiegeTrains.aim(server, raid, target, List.of(ours), List.of(theirs));
                    BowmanEntity b = (BowmanEntity) crew;
                    check(b.getShouldStrategicFire() && theirs.blockPosition().equals(b.StrategicFirePos())
                            && logged(target, "shifts its fire"), "With attackers at the centre the crew shifts fire onto clear defenders");
                    SiegeTrains.aim(server, raid, target, List.of(ours), List.of());
                    check(!b.getShouldStrategicFire() && logged(target, "holds its fire"), "With nothing safe to hit the crew holds its fire");
                    ours.teleportTo(target.center.getX() - 40, target.center.getY(), target.center.getZ());
                    SiegeTrains.aim(server, raid, target, List.of(ours), List.of(theirs));
                    check(b.getShouldStrategicFire() && target.center.equals(b.StrategicFirePos()), "Once the centre is clear again the crew shells it");
                } finally { ours.discard(); theirs.discard(); }
                check(engine.distanceToSqr(target.center.getX(), engine.getY(), target.center.getZ()) < 60 * 60
                        && engine.getX() < target.center.getX(), "Set up outside the centre, on the attacker's side");
                check(SiegeTrains.recall(server, raid, source) == 1 && source.stock.count(SiegeTrains.item("siegemachines:trebuchet")) == 1
                        && !engine.isAlive() && !crew.isAlive(), "Surviving engine returns to stock when the raid ends");
                source.stock.take(SiegeTrains.item("siegemachines:trebuchet"), 1);
            }
            if (sw) {
                source.stock.force(SiegeTrains.item("siegeweapons:catapult_item"), 1);
                Entity engine = SiegeTrains.deploy(server, raid, source, target);
                check(engine != null && BuiltInRegistries.ENTITY_TYPE.getKey(engine.getType()).toString().equals("siegeweapons:catapult"), "Siege Weapons catapult deployed");
                Entity crew = engine.getPassengers().isEmpty() ? null : engine.getPassengers().get(0);
                check(crew instanceof SiegeEngineerEntity se && se.getShouldStrategicFire() && target.center.equals(se.getStrategicFirePos()),
                        "Recruits Siege Engineer crews the catapult and bombards the target");
                check(logged(source, "catapult and its crew march") && logged(target, "setting up outside"), "Both chronicles record the siege train");
                engine.discard();
                check(SiegeTrains.recall(server, raid, source) == 0 && !crew.isAlive(), "A destroyed engine is not refunded; its crew leaves");
                var promo = RecruitsBridge.spawn(level, UnitType.FOOTMAN, source.center, a, "Engineer Candidate");
                check(!"Needs the Siege Weapons mod".equals(RecruitsBridge.promotionBlocker(promo, 2)), "Recruit Command offers Siege Engineer");
                promo.discard();
            }
            raid.source = npc.id; raid.attacker = npc.kingdomId();
            Entity free = SiegeTrains.deploy(server, raid, npc, target);
            check((free != null) == (sw || sm), "NPC City brings an engine only when a siege mod is loaded");
            npc.tier = Tier.TOWN;
            SiegeTrains.recall(server, raid, npc);
            check(SiegeTrains.deploy(server, raid, npc, target) == null, "NPC towns below City rank bring none");
        } finally { drop(level, source, target, npc); }
    }

    static void raiders(ServerLevel level) throws Exception {
        UUID a = UUID.randomUUID();
        Settlement s = town(level, "Raider Watch", a, 30, Tier.VILLAGE);
        List<Entity> spawned = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                Entity r = RecruitsBridge.spawn(level, UnitType.FOOTMAN, s.center.offset(2 + i, 0, 2), UUID.randomUUID(), "Raider " + i);
                spawned.add(r);
                r.getPersistentData().putString("FactionRaidsTeam", "factionraids_raiders");
                if (Compat.loaded(Compat.SIEGE_OVERHAUL)) {
                    Class<?> bridge = Class.forName("com.devfarinsky.siegeoverhaul.RecruitsBridge");
                    bridge.getMethod("ensureRaidersFaction", MinecraftServer.class).invoke(null, level.getServer());
                    bridge.getMethod("assignToRaidersFaction", Mob.class).invoke(null, r);
                }
            }
            check(Compat.hostile(spawned.get(0)) == Compat.loaded(Compat.SIEGE_OVERHAUL), "Siege Overhaul raiders count as hostile only with Siege Overhaul");
            Simulation.watch(level, s);
            boolean alarm = s.alarmUntil > level.getGameTime();
            check(alarm == Compat.loaded(Compat.SIEGE_OVERHAUL), "Raiders ring the town alarm (and nothing else does)");
            if (alarm) {
                Entity guard = RecruitsBridge.spawn(level, UnitType.FOOTMAN, s.center.offset(-3, 0, 0), a, "Watchman");
                spawned.add(guard);
                Settlement.Soldier so = new Settlement.Soldier();
                so.entity = guard.getUUID(); so.name = "Watchman"; so.type = UnitType.FOOTMAN; s.soldiers.add(so);
                ((Mob) guard).setTarget(null);
                check(SiegeOverhaulCompat.engage(level, s) == 1 && spawned.contains(((Mob) guard).getTarget()), "Idle defender engages a raider");
                LOG.add("raider team: " + (spawned.get(0).getTeam() == null ? "none (tag)" : spawned.get(0).getTeam().getName()));
            }
        } finally { spawned.forEach(Entity::discard); drop(level, s); }
    }

    // ------------------------------------------------------------------ soldiers

    static void morale(ServerLevel level) {
        UUID a = UUID.randomUUID();
        Settlement s = town(level, "Morale Hall", a, -40, Tier.VILLAGE);
        Entity e = RecruitsBridge.spawn(level, UnitType.FOOTMAN, s.center, a, "Morale Test");
        try {
            boolean loaded = Compat.loaded(Compat.MORALE);
            if (!loaded) {
                Compat.morale(e, -10);
                check(true, "Morale hook is a no-op without the addon");
                return;
            }
            var m = MoraleCompat.of(e);
            check(m != null, "Morale addon capability on recruits");
            m.setMorale(50);
            RecruitsBridge.addMorale(e, 10);
            check(Math.abs(MoraleCompat.morale(e) - 60) < .01, "Honours move battle morale (+10)");
            RecruitsBridge.addMorale(e, -8);
            check(Math.abs(MoraleCompat.morale(e) - 52) < .01, "Refusals move battle morale (-8)");
            m.setPanicking(true); m.setMorale(40);
            RecruitsBridge.addMorale(e, 20);
            check(!m.isPanicking(), "Restored morale ends a panic");
            Settlement.Soldier so = new Settlement.Soldier();
            so.entity = e.getUUID(); so.name = "Morale Test"; so.type = UnitType.FOOTMAN; so.skillXp = SoldierRanks.MAX_XP;
            s.soldiers.add(so);
            float want = 100 + Math.min(MoraleCompat.MAX_BONUS, MoraleCompat.PER_RANK * SoldierRanks.rank(so));
            check(MoraleCompat.veterans(level, s) == 1 && m.getMaxMorale() == want && want > 100, "Veterans gain max battle morale by rank");
        } finally { e.discard(); drop(level, s); }
    }

    static void workers(ServerLevel level) {
        UUID a = UUID.randomUUID();
        Settlement s = town(level, "Worker Town", a, 60, Tier.VILLAGE);
        s.population = 10;
        Plot farm = new Plot(); farm.typeId = "livingrealms:farm_field"; farm.state = Plot.State.BUILT; farm.origin = s.center; farm.blueprintKey = "";
        s.plots.add(farm);
        Entity w = null;
        try {
            Simulation.recompute(s);
            int idle = s.d.unemployed, farmers = s.d.workers.getOrDefault(Job.FARMER, 0);
            if (!Compat.loaded(Compat.WORKERS)) {
                WorkersCompat.STAFF.put(s.id, new EnumMap<>(Map.of(Job.FARMER, 1)));
                Simulation.recompute(s);
                check(s.d.unemployed == idle, "Workers staffing is a no-op without Workers");
                WorkersCompat.STAFF.remove(s.id);
                return;
            }
            var type = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation("workers:farmer"));
            w = type.create(level);
            w.moveTo(s.center.getX() + 4.5, s.center.getY(), s.center.getZ() + .5);
            level.addFreshEntity(w);
            RecruitsBridge.allegiance(w, a);
            check(WorkersCompat.scan(level, s) == 1, "The owner's Workers-mod farmer is counted");
            Simulation.recompute(s);
            check(s.d.workers.getOrDefault(Job.FARMER, 0) == farmers && s.d.unemployed == idle + 1, "The farmer fills a farm job and frees a townsperson");
            RecruitsBridge.allegiance(w, UUID.randomUUID());
            check(WorkersCompat.scan(level, s) == 0, "Someone else's worker does not count");
        } finally { if (w != null) w.discard(); WorkersCompat.STAFF.remove(s.id); drop(level, s); }
    }

    static void corpse(ServerLevel level) {
        UUID a = UUID.randomUUID();
        Settlement s = town(level, "Corpse Keep", a, -60, Tier.VILLAGE);
        Entity e = RecruitsBridge.spawn(level, UnitType.FOOTMAN, s.center.offset(6, 0, 6), a, "Fallen Test");
        try {
            Settlement.Soldier so = new Settlement.Soldier();
            so.entity = e.getUUID(); so.name = "Fallen Test"; so.type = UnitType.FOOTMAN; s.soldiers.add(so);
            RecruitsBridge.setSlot(e, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            ItemStack worn = new ItemStack(Items.IRON_HELMET); worn.setDamageValue(30);
            RecruitsBridge.setSlot(e, EquipmentSlot.HEAD, worn);
            CorpseCompat.PENDING.clear();
            ((LivingEntity) e).hurt(level.damageSources().fellOutOfWorld(), 10000);
            check(s.soldiers.isEmpty(), "Fallen soldier leaves the roll");
            if (!Compat.loaded(Compat.CORPSE)) {
                check(CorpseCompat.PENDING.isEmpty(), "No corpse tracking without Corpse");
                return;
            }
            check(CorpseCompat.PENDING.size() == 1, "Death traced to the town before its record is dropped");
            var f = CorpseCompat.PENDING.remove(0);
            CorpseCompat.PENDING.add(new CorpseCompat.Fallen(f.settlement(), f.name(), f.dim(), f.pos(), f.time() - 100));
            var bodies = level.getEntitiesOfClass(de.maxhenkel.corpse.entities.CorpseEntity.class, new net.minecraft.world.phys.AABB(f.pos()).inflate(4));
            check(bodies.size() == 1, "Recruits left a corpse");
            int sword = s.stock.count(Items.IRON_SWORD);
            check(CorpseCompat.tick(level.getServer()) >= 1 && s.stock.count(Items.IRON_SWORD) == sword + 1, "Fresh sword returns to the armoury");
            var body = bodies.get(0);
            check(body.isAlive() && body.blockPosition().getX() == s.center.getX() && body.blockPosition().getZ() == s.center.getZ(),
                    "Body with the worn helmet is laid at the town centre");
            check(logged(s, "carried to the town centre") && CompatData.get(level.getServer()).salvaged.getOrDefault(s.id, 0) >= 1, "Chronicle and tally record it");
            body.discard();
        } finally { if (e.isAlive()) e.discard(); CompatData.get(level.getServer()).salvaged.remove(s.id); drop(level, s); }
    }

    private CompatSelfTest() {}
}
