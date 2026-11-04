package livingrealms.battle;

import com.mojang.authlib.GameProfile;
import livingrealms.LivingRealms;
import livingrealms.chronicle.Chronicle;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import livingrealms.settlement.Raids;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** {@code ./gradlew runServer -PreportsTest -PreportsTestDir=<fresh dir>}: battle reports, written to reports-test-result.txt. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class BattleReportsSelfTest {
    private static int checks;

    private static void check(boolean value, String detail) {
        if (!value) throw new IllegalStateException(detail);
        checks++;
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.reportsTest")) return;
        event.getServer().execute(() -> {
            String report;
            try {
                run(event.getServer().overworld());
                report = "PASS: " + checks + " battle report assertions\n";
                LivingRealms.LOG.info("REPORTS SELF TEST {}", report);
            } catch (Throwable failure) {
                report = "FAIL after " + checks + " assertions: " + failure + "\n";
                LivingRealms.LOG.error("REPORTS SELF TEST FAILED", failure);
            }
            try { Files.writeString(Path.of("reports-test-result.txt"), report); } catch (Exception e) { LivingRealms.LOG.error("Cannot write test result", e); }
            event.getServer().halt(false);
        });
    }

    static void run(ServerLevel level) throws Exception {
        checks = 0;
        MinecraftServer server = level.getServer();
        BattleReports.ACTIVE.clear();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "ReportOwner");
        FakePlayerFactory.get(level, profile);
        server.getProfileCache().add(profile);
        UUID owner = profile.getId();
        SettlementManager m = SettlementManager.get(server);
        BlockPos base = level.getSharedSpawnPos();
        level.getChunkAt(base);
        Settlement town = new Settlement(); town.name = "Reportford"; town.owner = owner; town.dim = level.dimension(); town.center = base;
        Settlement raiders = new Settlement(); raiders.name = "Raiderton"; raiders.dim = level.dimension(); raiders.center = base.offset(2000, 0, 0);
        m.add(town); m.add(raiders);
        List<Entity> spawned = new ArrayList<>();
        Raids.Raid raid = null;
        try {
            // who counts
            LivingEntity a = recruit(level, UnitType.FOOTMAN, base.offset(2, 0, 0), owner, "Aldric", spawned);
            LivingEntity b = recruit(level, UnitType.BOWMAN, base.offset(3, 0, 0), owner, "Bryn", spawned);
            LivingEntity c = recruit(level, UnitType.FOOTMAN, base.offset(4, 0, 0), owner, "Cade", spawned);
            LivingEntity npc = recruit(level, UnitType.FOOTMAN, base.offset(5, 0, 0), UUID.randomUUID(), "Kingdom guard", spawned);
            check(owner.equals(BattleReports.owner(server, a)), "A player's recruit belongs to its owner");
            check(BattleReports.owner(server, npc) == null, "An NPC kingdom's recruit has no player owner");
            Settlement.Soldier sa = soldier(town, a, UnitType.FOOTMAN), sb = soldier(town, b, UnitType.BOWMAN);
            sa.skillXp = 190;
            check(livingrealms.military.SoldierRanks.rank(sa) == 3, "Aldric starts at skill rank 3");

            Zombie z1 = zombie(level, base.offset(2, 0, 2), spawned), z2 = zombie(level, base.offset(3, 0, 2), spawned), z3 = zombie(level, base.offset(4, 0, 2), spawned);
            hit(npc, z1, 4);
            check(!BattleReports.ACTIVE.containsKey(owner) && BattleReports.ACTIVE.values().stream().allMatch(List::isEmpty), "An NPC kingdom's fight is not reported");
            c.hurt(level.damageSources().fall(), 1);
            check(BattleReports.ACTIVE.getOrDefault(owner, List.of()).isEmpty(), "A fall outside battle starts nothing");

            // the battle
            long t0 = BattleReports.now(server);
            hit(a, z1, 6);
            List<BattleReports.Battle> battles = BattleReports.ACTIVE.get(owner);
            check(battles != null && battles.size() == 1, "The first blow starts a battle");
            BattleReports.Battle battle = battles.get(0);
            check(battle.fighters.get(a.getUUID()).f.dealt > 0, "Damage dealt is counted: " + battle.fighters.get(a.getUUID()).f.dealt);
            hit(a, z1, 100);
            hit(b, z2, 100);
            hit(c, z3, 5);
            hit(z3, b, 4);
            hit(z3, c, 1000);
            hit(a, z3, 100);
            check(!c.isAlive() && !z1.isAlive() && !z2.isAlive() && !z3.isAlive(), "Cade and three zombies are dead");
            float before = battle.fighters.get(a.getUUID()).f.dealt;
            hit(a, b, 1);
            check(battle.fighters.get(a.getUUID()).f.dealt == before, "Friendly fire is not damage dealt");
            check(battle.fighters.size() == 3 && battle.kills.getOrDefault("Zombie", 0) == 3, "Three fighters, three zombie kills: " + battle.kills);

            // a second fight far away is its own battle, and a scuffle is not reported
            BlockPos far = base.offset(300, 0, 0);
            level.getChunkAt(far);
            LivingEntity e = recruit(level, UnitType.FOOTMAN, far, owner, "Edda", spawned);
            Zombie z4 = zombie(level, far.offset(1, 0, 1), spawned);
            hit(e, z4, 3);
            check(BattleReports.ACTIVE.get(owner).size() == 2, "A fight 300 blocks away is a separate battle");
            long now = BattleReports.now(server);
            check(BattleReports.update(server, now + BattleReports.QUIET_TICKS - 1).isEmpty() && BattleReports.ACTIVE.get(owner).size() == 2, "Still being fought until 20 quiet seconds");
            List<BattleReport> done = BattleReports.update(server, now + BattleReports.QUIET_TICKS);
            check(done.size() == 1 && !BattleReports.ACTIVE.containsKey(owner), "One report; the three-damage scuffle is dropped: " + done.size());
            BattleReport r = done.get(0);
            List<BattleReport> saved = BattleData.get(server).of(owner);
            check(saved.size() == 1 && saved.get(0) == r, "Saved for the owner");
            check(r.kind == BattleReport.Kind.SKIRMISH && r.place.equals("Reportford"), "A skirmish at Reportford: " + r.kind + " " + r.place);
            check(r.start == t0 && r.end >= r.start && r.day() == t0 / 24000 + 1, "Start and duration");
            check(r.slain() == 3 && r.killList().equals("3 Zombie"), "Enemies by type: " + r.killList());
            check(r.fallen().size() == 1 && r.fallen().get(0).name.equals("Cade") && r.fallen().get(0).id.equals(c.getUUID()), "Cade fell, by name and UUID");
            check(r.wounded().size() == 1 && r.wounded().get(0).name.equals("Bryn") && r.wounded().get(0).taken > 0, "Bryn is wounded");
            BattleReport.Fighter mvp = r.mvp();
            check(mvp != null && mvp.name.equals("Aldric") && mvp.kills == 2, "Aldric is MVP with two kills");
            BattleReport.Fighter fa = r.fighter(a.getUUID());
            check(fa.advances.stream().anyMatch(s -> s.contains("specialist path")) && fa.advances.stream().anyMatch(s -> s.contains("rank")),
                    "Aldric's rank-up and path unlock are noted: " + fa.advances);
            check(r.fighter(c.getUUID()).advances.isEmpty(), "A recruit outside any town is reported without ranks");
            check(town.chronicle.entries.stream().anyMatch(en -> en.kind() == Chronicle.Kind.NOTE && en.text().contains("Cade")), "The fallen reach Reportford's chronicle");
            CompoundTag toast = BattleReports.toast(r);
            check(toast.getString("title").equals("Skirmish at Reportford") && toast.getString("text").contains("MVP Aldric") && toast.getString("text").contains("1 fallen"),
                    "Toast: " + toast);
            String chat = BattleReports.summary(r);
            check(chat.contains("Fallen: Cade") && chat.contains("3 Zombie") && chat.contains("MVP: Aldric"), "Chat summary: " + chat);

            // raids and sieges
            raid = new Raids.Raid();
            raid.source = raiders.id; raid.target = town.id; raid.attacker = UUID.randomUUID(); raid.defender = owner;
            m.raids.add(raid);
            Zombie z5 = zombie(level, base.offset(2, 0, -2), spawned);
            hit(a, z5, 100);
            now = BattleReports.now(server);
            BattleReports.update(server, now);
            battle = BattleReports.ACTIVE.get(owner).get(0);
            check(battle.kind == BattleReport.Kind.RAID_DEFENDED && battle.foe.equals("Raiderton"), "Fighting in a raided town is a raid defended: " + battle.kind);
            raid.siege.walled = true;
            BattleReports.update(server, now);
            check(battle.kind == BattleReport.Kind.SIEGE_DEFENDED, "Behind walls it is a siege");
            m.raids.remove(raid);
            r = BattleReports.update(server, now + BattleReports.QUIET_TICKS).get(0);
            check(r.kind == BattleReport.Kind.SIEGE_DEFENDED && r.title().equals("Siege withstood at Reportford"), "The siege outlives the raid's end in the report: " + r.title());
            raid = new Raids.Raid();
            raid.source = town.id; raid.target = raiders.id; raid.attacker = owner; raid.defender = UUID.randomUUID();
            raid.soldiers.add(a.getUUID());
            m.raids.add(raid);
            Zombie z6 = zombie(level, base.offset(1, 0, -3), spawned);
            hit(a, z6, 100);
            r = BattleReports.update(server, BattleReports.now(server) + BattleReports.QUIET_TICKS).get(0);
            check(r.kind == BattleReport.Kind.RAID_ATTACKED && r.foe.equals("Raiderton"), "A deployed recruit is attacking: " + r.kind + " " + r.foe);
            m.raids.remove(raid);
            raid = null;
            check(BattleReport.Kind.max(BattleReport.Kind.DRAGON, BattleReport.Kind.SKIRMISH) == BattleReport.Kind.DRAGON
                    && BattleReport.Kind.max(BattleReport.Kind.DRAGON, BattleReport.Kind.RAID_DEFENDED) == BattleReport.Kind.RAID_DEFENDED, "Kind precedence");

            // a fight still going at shutdown is written as it stands
            Zombie z7 = zombie(level, base.offset(-2, 0, 2), spawned);
            hit(b, z7, 100);
            check(BattleReports.update(server, Long.MAX_VALUE).size() == 1 && BattleData.get(server).of(owner).size() == 4, "Shutdown flush");

            // the page's payload, saves and the cap
            CompoundTag payload = BattleReports.payload(server, owner);
            check(payload.getList("reports", Tag.TAG_COMPOUND).size() == 4 && payload.getInt("active") == 0, "Payload lists every report");
            BattleReport first = BattleReport.load(payload.getList("reports", Tag.TAG_COMPOUND).getCompound(3));
            check(first.kills.equals(Map.of("Zombie", 3)) && first.fighters.size() == 3 && first.fallen().get(0).name.equals("Cade")
                    && first.fighter(a.getUUID()).advances.equals(fa.advances) && first.place.equals("Reportford") && first.clock == done.get(0).clock, "Report survives the round trip");
            BattleData copy = BattleData.load(BattleData.get(server).save(new CompoundTag()));
            check(copy.of(owner).size() == 4 && copy.of(owner).get(0).id == BattleData.get(server).of(owner).get(0).id, "BattleData persistence");
            UUID other = UUID.randomUUID();
            for (int i = 0; i < 25; i++) { BattleReport x = new BattleReport(); x.start = i; copy.add(other, x); }
            List<BattleReport> capped = copy.of(other);
            check(capped.size() == BattleData.KEEP && capped.get(0).start == 24 && capped.get(capped.size() - 1).start == 5, "Last 20 kept, newest first");
            check(BattleData.load(new CompoundTag()).of(owner).isEmpty(), "Empty data loads");
            check(BattleReport.load(new CompoundTag()).kind == BattleReport.Kind.SKIRMISH, "A blank report loads as a skirmish");
            check(BattleReport.duration(95).equals("1m 35s") && BattleReport.clockText(14 * 60 + 5).equals("14:05"), "Duration and clock text");
            check(BattleContext.names(List.of("A", "B", "C")).equals("A, B and C"), "Name lists");

            // saved from a killing blow: Undying Rage, then the Saint, noted as advances; the observers see what really lands
            check(net.minecraftforge.eventbus.api.EventPriority.LOW == livingrealms.military.SoldierPaths.class
                    .getMethod("lethal", net.minecraftforge.event.entity.living.LivingDamageEvent.class).getAnnotation(SubscribeEvent.class).priority(), "Saves run at LOW, before the LOWEST observers");
            sa.path = livingrealms.military.SoldierPath.BERSERKER; sa.pathSkills.add(livingrealms.military.PathSkill.UNDYING_RAGE);
            sb.path = livingrealms.military.SoldierPath.HEALER; sb.pathSkills.add(livingrealms.military.PathSkill.SAINT);
            a.setHealth(a.getMaxHealth()); b.setHealth(b.getMaxHealth());
            Zombie z9 = zombie(level, base.offset(2, 0, 1), spawned);
            hit(a, z9, 2);
            battle = BattleReports.ACTIVE.get(owner).get(0);
            float hp = a.getHealth(), taken = battle.fighters.get(a.getUUID()).f.taken;
            hit(z9, a, 1000);
            check(a.isAlive() && Math.abs(a.getHealth() - 1) < .01, "Undying Rage holds Aldric on 1 HP: " + a.getHealth());
            check(Math.abs(battle.fighters.get(a.getUUID()).f.taken - taken - (hp - 1)) < .01, "The report counts the damage that landed, not the killing blow: "
                    + (battle.fighters.get(a.getUUID()).f.taken - taken));
            hit(z9, a, 1000);
            check(a.isAlive() && Math.abs(a.getHealth() - 6) < .01, "Bryn the Saint pulls Aldric back on 6 HP: " + a.getHealth());
            r = BattleReports.update(server, BattleReports.now(server) + BattleReports.QUIET_TICKS).get(0);
            List<String> adv = r.fighter(a.getUUID()).advances;
            check(adv.contains("Undying Rage held") && adv.stream().anyMatch(x -> x.startsWith("Saved by Bryn")), "Both saves are in the report: " + adv);
            check(r.fallen().isEmpty(), "Nobody fell");
            sa.pathSkills.clear(); sa.path = null; sb.pathSkills.clear(); sb.path = null;

            // a fall already chronicled on its own (a notable soldier) is left out of the skirmish line
            LivingEntity dorn = recruit(level, UnitType.FOOTMAN, base.offset(-4, 0, 0), owner, "Dorn", spawned);
            LivingEntity ewan = recruit(level, UnitType.FOOTMAN, base.offset(-5, 0, 0), owner, "Ewan", spawned);
            Settlement.Soldier sd = soldier(town, dorn, UnitType.FOOTMAN);
            sd.kills = 10;
            check(Chronicle.notable(sd), "Dorn is a notable soldier");
            Zombie z10 = zombie(level, base.offset(-4, 0, 2), spawned);
            hit(dorn, z10, 3);
            hit(z10, dorn, 1000);
            hit(z10, ewan, 1000);
            check(!dorn.isAlive() && !ewan.isAlive() && Chronicle.fallChronicled(dorn.getUUID()), "Dorn and Ewan fell; Dorn has his own chronicle entry");
            r = BattleReports.update(server, BattleReports.now(server) + BattleReports.QUIET_TICKS).get(0);
            check(r.fallen().size() == 2, "Both are in the report's fallen");
            var notes = town.chronicle.entries.stream().filter(en -> en.kind() == Chronicle.Kind.NOTE && en.text().contains("Ewan")).toList();
            check(notes.size() == 1 && !notes.get(0).text().contains("Dorn") && notes.get(0).text().contains("Ewan fell"), "The skirmish line names only Ewan: " + notes);

            // without the town, the report still stands
            m.remove(town);
            Zombie z8 = zombie(level, base.offset(-3, 0, -2), spawned);
            hit(a, z8, 100);
            r = BattleReports.update(server, BattleReports.now(server) + BattleReports.QUIET_TICKS).get(0);
            check(r.place.isEmpty() && r.title().startsWith("Skirmish at " + r.pos.getX()) && r.slain() == 1, "No town: coordinates instead of a name: " + r.title());
        } finally {
            if (raid != null) m.raids.remove(raid);
            for (Entity x : spawned) if (x != null && !x.isRemoved()) x.discard();
            m.remove(town); m.remove(raiders);
            BattleReports.ACTIVE.clear();
            BattleData.get(server).clear(owner);
        }
    }

    static void hit(LivingEntity attacker, LivingEntity victim, float amount) {
        victim.invulnerableTime = 0;
        victim.hurt(victim.level().damageSources().mobAttack(attacker), amount);
    }

    static LivingEntity recruit(ServerLevel level, UnitType unit, BlockPos at, UUID owner, String name, List<Entity> spawned) {
        LivingEntity e = (LivingEntity) RecruitsBridge.spawn(level, unit, at, owner, name);
        check(e != null, "Spawn " + name);
        spawned.add(e);
        return e;
    }

    static Zombie zombie(ServerLevel level, BlockPos at, List<Entity> spawned) {
        Zombie z = EntityType.ZOMBIE.create(level);
        z.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        level.addFreshEntity(z);
        spawned.add(z);
        return z;
    }

    static Settlement.Soldier soldier(Settlement s, Entity e, UnitType type) {
        Settlement.Soldier so = new Settlement.Soldier();
        so.entity = e.getUUID(); so.type = type; so.name = e.getName().getString();
        s.soldiers.add(so);
        return so;
    }

    private BattleReportsSelfTest() {}
}
