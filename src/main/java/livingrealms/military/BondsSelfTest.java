package livingrealms.military;

import livingrealms.LivingRealms;
import livingrealms.chronicle.Chronicle;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * -PbondsTest: brothers-in-arms on real Villager Recruits soldiers hired straight to a player (no town), then one town
 * soldier for the chronicle. Forming in battle, limits, levels and caps, buffs and their budget, avenging, grief and
 * vengeance, promotion, the save round trip and the Recruit Command view.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class BondsSelfTest {
    private static int checks;

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.bondsTest")) return;
        event.getServer().execute(() -> {
            String report;
            try {
                checks = 0;
                run(event.getServer().overworld());
                report = "PASS: " + checks + " bond assertions\n";
                LivingRealms.LOG.info("BONDS SELF TEST {}", report);
            } catch (Throwable failure) {
                report = "FAIL after " + checks + " assertions: " + failure + "\n";
                LivingRealms.LOG.error("BONDS SELF TEST FAILED", failure);
            }
            try { Files.writeString(Path.of("bonds-test-result.txt"), report); } catch (Exception ex) { LivingRealms.LOG.error("Cannot write test result", ex); }
            event.getServer().halt(false);
        });
    }

    static void run(ServerLevel level) throws Exception {
        var server = level.getServer();
        BlockPos at = level.getSharedSpawnPos().offset(4, 0, 4);
        at = RecruitsBridge.surface(level, at);
        level.getChunkAt(at);
        UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
        List<Entity> spawned = new ArrayList<>();
        BondData data = BondData.get(server);
        Bonds.resetBattles();
        Settlement town = null;
        try {
            // definitions
            check(Bonds.Level.of(0) == Bonds.Level.NONE && Bonds.Level.of(9) == Bonds.Level.NONE && Bonds.Level.of(10) == Bonds.Level.COMRADES
                    && Bonds.Level.of(30) == Bonds.Level.SWORN && Bonds.Level.of(60) == Bonds.Level.BLOOD_OATH && Bonds.Level.of(999) == Bonds.Level.BLOOD_OATH, "Level thresholds 10/30/60");
            check(Bonds.Level.COMRADES.damage == .03 && Bonds.Level.SWORN.damage == .06 && Bonds.Level.BLOOD_OATH.damage == .10, "Damage +3/+6/+10%");

            LivingEntity a = spawn(level, UnitType.FOOTMAN, at, owner, "Aldric", spawned);
            LivingEntity b = spawn(level, UnitType.FOOTMAN, at.offset(3, 0, 0), owner, "Bera", spawned);
            LivingEntity far = spawn(level, UnitType.FOOTMAN, at.offset(30, 0, 0), owner, "Faraway", spawned);
            LivingEntity other = spawn(level, UnitType.FOOTMAN, at.offset(0, 0, 3), stranger, "Stranger", spawned);
            check(SoldierProgression.record(server, a) == null, "Test recruits are hired directly, not town soldiers");
            UUID ia = a.getUUID(), ib = b.getUUID();

            // forming in battle: shared fighting pulses, at most 3 a battle, only at the battle's end, only with both alive
            long t = 1_000_000;
            for (int i = 0; i < 6; i++) {
                long now = t + i * Bonds.PULSE_TICKS;
                for (LivingEntity e : List.of(a, b, far, other)) Bonds.engage(e, now);
            }
            check(data.pair(ia, ib) == null, "No points before the battle ends");
            Bonds.settle(server, t + 5 * Bonds.PULSE_TICKS + Bonds.BATTLE_QUIET / 2);
            check(data.pair(ia, ib) == null, "Battle still on while they fought recently");
            Bonds.settle(server, t + 5 * Bonds.PULSE_TICKS + Bonds.BATTLE_QUIET + 1);
            check(data.pair(ia, ib) != null && data.pair(ia, ib).points == Bonds.PER_BATTLE, "Three points for one battle together: " + pts(data, ia, ib));
            check(data.pair(ia, far.getUUID()) == null, "No bond across 30 blocks");
            check(data.pair(ia, other.getUUID()) == null && data.pair(ib, other.getUUID()) == null, "No bond with another player's recruit");
            check(data.member(ia).owner.equals(owner) && data.member(ia).name.equals("Aldric"), "Member remembers owner and name");

            // second battle the same day reaches the daily limit; a third gives nothing; next day counts again
            battle(server, a, b, t + 2000, 6);
            check(data.pair(ia, ib).points == 6, "Second battle: 6");
            battle(server, a, b, t + 4000, 6);
            check(data.pair(ia, ib).points == Bonds.PER_DAY, "Daily limit of 6: " + pts(data, ia, ib));
            long day2 = (t / 24000 + 1) * 24000 + 100;
            battle(server, a, b, day2, 1);
            check(data.pair(ia, ib).points == 7, "Single shared fight next day: +1");

            // a battle where one of them dies gives the pair nothing
            LivingEntity c = spawn(level, UnitType.FOOTMAN, at.offset(-3, 0, 0), owner, "Corin", spawned);
            Bonds.engage(a, day2 + 3000); Bonds.engage(c, day2 + 3000);
            c.kill();
            Bonds.settle(server, day2 + 3000 + Bonds.BATTLE_QUIET + 1);
            check(data.pair(ia, c.getUUID()) == null && data.member(c.getUUID()) == null, "Fallen comrade earns nothing and is forgotten");

            // levels and the owner's announcement path
            long t3 = day2 + 24000;
            check(Bonds.award(server, data, ia, ib, 3, t3) == 3 && data.pair(ia, ib).level() == Bonds.Level.COMRADES, "Comrades at 10");

            // caps: three bonds at most, one Blood Oath at most
            LivingEntity d = spawn(level, UnitType.FOOTMAN, at.offset(0, 0, -3), owner, "Dunn", spawned);
            LivingEntity e = spawn(level, UnitType.FOOTMAN, at.offset(-3, 0, -3), owner, "Edda", spawned);
            LivingEntity f = spawn(level, UnitType.FOOTMAN, at.offset(3, 0, -3), owner, "Fenn", spawned);
            for (LivingEntity x : List.of(d, e, f)) Bonds.engage(x, t3);
            data.pair(ia, d.getUUID(), true).points = 10;
            data.pair(ia, e.getUUID(), true).points = 10;
            check(Bonds.bondCount(data, ia, null, t3) == 3, "Aldric has three bonds");
            BondData.Pair af = data.pair(ia, f.getUUID(), true);
            af.points = 8;
            Bonds.award(server, data, ia, f.getUUID(), 3, t3);
            check(af.points == Bonds.Level.COMRADES.points - 1 && af.level() == Bonds.Level.NONE, "No fourth bond: held at 9");
            data.pair(ia, ib).points = 60;
            check(data.pair(ia, ib).level() == Bonds.Level.BLOOD_OATH, "Blood Oath at 60");
            BondData.Pair ad = data.pair(ia, d.getUUID());
            ad.points = 58;
            Bonds.award(server, data, ia, d.getUUID(), 3, t3);
            check(ad.points == Bonds.Level.BLOOD_OATH.points - 1, "No second Blood Oath: held at 59");
            BondData.Pair de = data.pair(d.getUUID(), e.getUUID(), true);
            de.points = 58;
            Bonds.award(server, data, d.getUUID(), e.getUUID(), 3, t3);
            check(de.level() == Bonds.Level.BLOOD_OATH, "Dunn and Edda may still swear their own Blood Oath");
            check(data.bondsOf(ia).get(0).other(ia).equals(ib), "Strongest bond listed first");
            ad.points = 10;
            // gone partners hold no place: discarded, given to another player, or unseen for 5 days
            BondData.Member me = data.member(e.getUUID());
            data.member(ib).seen = data.member(d.getUUID()).seen = me.seen;
            check(Bonds.ceiling(data, af, t3) == Bonds.Level.COMRADES.points - 1, "Fenn still held back by Aldric's three bonds");
            me.removed = true;
            check(Bonds.bondCount(data, ia, null, t3) == 2 && Bonds.ceiling(data, af, t3) > Bonds.Level.COMRADES.points, "A removed partner frees its place");
            me.removed = false; me.owner = stranger;
            check(Bonds.bondCount(data, ia, null, t3) == 2, "A partner now another player's frees its place");
            me.owner = owner;
            check(Bonds.bondCount(data, ia, null, me.seen + Bonds.GONE_TICKS - 1) == 3, "Unseen for under 5 days still counts");
            check(Bonds.bondCount(data, ia, null, me.seen + Bonds.GONE_TICKS) < 3 && Bonds.ceiling(data, af, me.seen + Bonds.GONE_TICKS) == Bonds.Level.BLOOD_OATH.points,
                    "Partners unseen for 5 days hold no place");

            // buffs: best bond beside, 10-block range, the bond budget; Blood Oath defence
            Entity zed = EntityType.ZOMBIE.spawn(level, at.offset(1, 0, 1), MobSpawnType.COMMAND);
            check(zed instanceof LivingEntity, "Spawn a zombie"); spawned.add(zed);
            LivingEntity zombie = (LivingEntity) zed;
            long now = RecruitCombat.now(a);
            d.teleportTo(at.getX() + 40, at.getY(), at.getZ()); e.teleportTo(at.getX() + 40, at.getY(), at.getZ() + 2);
            check(near(Bonds.attack(data, a, zombie, now), .10), "Blood Oath partner beside: +10%: " + Bonds.attack(data, a, zombie, now));
            check(near(Bonds.defend(data, a), .10), "Blood Oath beside: 10% less damage");
            var hit = new LivingHurtEvent(zombie, level.damageSources().mobAttack(a), 10);
            Bonds.hurt(hit);
            check(near(hit.getAmount(), 11), "Real hurt hook: 10 becomes 11: " + hit.getAmount());
            hit = new LivingHurtEvent(a, level.damageSources().mobAttack(zombie), 10);
            Bonds.hurt(hit);
            check(near(hit.getAmount(), 9), "Real hurt hook: 10 taken becomes 9: " + hit.getAmount());
            b.teleportTo(at.getX() + 15, at.getY(), at.getZ());
            check(Bonds.attack(data, a, zombie, now) == 0 && Bonds.defend(data, a) == 0, "Out of 10 blocks: no bonus");
            d.teleportTo(at.getX() + 2, at.getY(), at.getZ() + 2);
            check(near(Bonds.attack(data, a, zombie, now), .03) && Bonds.defend(data, a) == 0, "Comrade beside: +3%, no defence");
            b.teleportTo(at.getX() + 3, at.getY(), at.getZ());
            check(near(Bonds.attack(data, a, zombie, now), .10), "Bonds don't add up: the strongest beside counts");
            BondData.Member ma = data.member(ia);
            ma.vengeance = "minecraft:zombie"; ma.vengeanceLabel = "Zombie"; ma.griefUntil = now - 1; ma.vengeanceUntil = now + 1000;
            check(near(Bonds.attack(data, a, zombie, now), Bonds.BOND_CAP), "Blood Oath + vengeance capped at 15%");
            check(near(Bonds.attack(data, a, b, now), .10), "Vengeance only against the killer's kind");
            ma.griefUntil = now + 100;
            check(near(Bonds.attack(data, a, zombie, now), .10 - Bonds.GRIEF_PENALTY), "Grief: -10%, and no vengeance yet");
            ma.griefUntil = 0; ma.vengeanceUntil = 0; ma.vengeance = "";
            BondData.Member stray = data.member(other.getUUID());
            check(stray == null || data.pairsOf(other.getUUID()).isEmpty(), "Stranger has no bonds");
            check(Bonds.attack(data, other, zombie, now) == 0, "No bonus for an unbonded recruit");

            // avenge: a hard hit on Bera rouses Aldric (Blood Oath) but not a mere comrade
            BondData.Pair bd = data.pair(ib, d.getUUID(), true); bd.points = 10;
            check(Bonds.avenge(data, b, now) == 1 && data.member(ia).avengeUntil == now + Bonds.AVENGE_TICKS
                    && (data.member(d.getUUID()) == null || data.member(d.getUUID()).avengeUntil <= now), "Hard hit: Aldric avenges, a mere comrade doesn't");
            check(!a.hasEffect(MobEffects.DAMAGE_BOOST), "Avenge is no potion");
            check(near(Bonds.attack(data, a, zombie, now), Bonds.BOND_CAP), "Avenge +10% on top of the Blood Oath, within the 15% bond budget: " + Bonds.attack(data, a, zombie, now));
            b.teleportTo(at.getX() + 15, at.getY(), at.getZ());
            check(near(Bonds.attack(data, a, zombie, now), Bonds.Level.COMRADES.damage + Bonds.AVENGE)
                    && near(Bonds.attack(data, a, zombie, now + Bonds.AVENGE_TICKS), Bonds.Level.COMRADES.damage), "Avenge: +10% for 5s");
            b.teleportTo(at.getX() + 3, at.getY(), at.getZ());
            check(Bonds.avenge(data, b, now + 20) == 0, "Avenge cooldown");
            data.member(ia).avengeReady = 0; data.member(ia).avengeUntil = 0;
            var small = new LivingDamageEvent(b, level.damageSources().mobAttack(zombie), 1);
            Bonds.struck(small);
            check(data.member(ia).avengeUntil == 0, "A light hit rouses nobody");
            var hard = new LivingDamageEvent(b, level.damageSources().mobAttack(zombie), b.getMaxHealth() * .3f);
            Bonds.struck(hard);
            check(data.member(ia).avengeUntil > now, "Real damage hook: hard hit avenged");
            data.member(ia).avengeUntil = 0;

            // view for Recruit Command
            CompoundTag v = Bonds.view(server, a);
            check(v.getInt("count") == 3 && v.getList("list", 10).size() == 3, "View: three bonds, no forming row when full");
            CompoundTag top = v.getList("list", 10).getCompound(0);
            check(top.getString("name").equals("Bera") && top.getString("label").equals("Blood Oath") && top.getInt("to") == 0 && top.getBoolean("near"), "View: Bera, Blood Oath, beside");
            CompoundTag fv = Bonds.view(server, f);
            check(fv.getInt("count") == 0 && fv.getList("list", 10).size() == 1 && fv.getList("list", 10).getCompound(0).getBoolean("forming")
                    && fv.getList("list", 10).getCompound(0).contains("held"), "View: Fenn's forming tie, held by Aldric's three bonds");

            // promotion moves bonds to the new entity
            UUID fresh = UUID.randomUUID();
            Bonds.moved(server, ib, fresh);
            check(data.pair(ia, fresh) != null && data.pair(ia, fresh).points == 60 && data.pair(ia, ib) == null && data.member(fresh) != null, "Bonds follow a promotion");
            Bonds.moved(server, fresh, ib);
            check(data.pair(ia, ib).points == 60, "And back");

            // save round trip
            BondData copy = BondData.load(data.save(new CompoundTag()));
            check(copy.pair(ia, ib).points == 60 && copy.bondsOf(ia).size() == 3 && copy.member(ia).name.equals("Aldric") && copy.pairsOf(ia).size() == data.pairsOf(ia).size(), "Save round trip");

            // loss: Bera dies to a zombie through the real death event; Aldric grieves, loses morale, then vengeance
            float morale = RecruitsBridge.morale(a);
            RecruitsBridge.addMorale(a, 50 - morale);
            morale = RecruitsBridge.morale(a);
            b.hurt(level.damageSources().mobAttack(zombie), 1000);
            check(!b.isAlive(), "Bera fell");
            long when = RecruitCombat.now(a);
            BondData.Member grief = data.member(ia);
            check(data.pair(ia, ib) == null && data.member(ib) == null && Bonds.bondCount(data, ia, null, when) == 2, "Fallen partner's bonds are gone");
            check(Bonds.lastGrief.equals("✝ Aldric and Dunn grieve for Bera, and will seek vengeance on the Zombie."), "One grief line names every partner: " + Bonds.lastGrief);
            check(Bonds.griefLine("Cade", List.of("Bob"), EnumSet.of(Bonds.Level.COMRADES), "").equals("✝ Bob grieves for Cade, a comrade.")
                    && Bonds.griefLine("Cade", List.of("Bob", "Ann"), EnumSet.of(Bonds.Level.SWORN), "").equals("✝ Bob and Ann grieve for Cade, their sworn kin."), "Grief wording");
            check(grief.griefUntil > when && grief.griefUntil <= when + Bonds.GRIEF_TICKS && grief.mourned.equals("Bera"), "Aldric grieves for 3 minutes");
            check(grief.vengeance.equals("minecraft:zombie") && grief.vengeanceUntil == grief.griefUntil + Bonds.VENGEANCE_TICKS, "Then 10 minutes of vengeance on zombies");
            check(RecruitsBridge.morale(a) <= morale - Bonds.Level.BLOOD_OATH.grief + 1e-3, "Blood Oath grief: -15 morale: " + morale + " -> " + RecruitsBridge.morale(a));
            check(data.member(d.getUUID()).griefUntil > when && data.member(d.getUUID()).griefLevel == Bonds.Level.COMRADES.ordinal(), "Comrade Dunn grieves too");
            check(near(Bonds.attack(data, a, zombie, when), .03 - Bonds.GRIEF_PENALTY), "Grieving: comrade bonus minus 10%");
            check(near(Bonds.attack(data, a, zombie, grief.griefUntil), .03 + Bonds.VENGEANCE), "Vengeance: +3% +8%");
            check(Bonds.attack(data, a, zombie, grief.vengeanceUntil) == .03, "Vengeance ends");
            check(Bonds.view(server, a).getString("status").startsWith("Grieving for Bera"), "View shows grief");

            // a town soldier: Sworn Kin and a fall go into its town's chronicle
            town = new Settlement(); town.name = "Bond test"; town.owner = owner; town.dim = level.dimension(); town.center = at;
            SettlementManager.get(server).add(town);
            Settlement.Soldier so = new Settlement.Soldier(); so.entity = d.getUUID(); so.type = UnitType.FOOTMAN; so.name = "Dunn";
            town.soldiers.add(so);
            BondData.Pair dd = data.pair(d.getUUID(), ia); dd.points = 28; dd.day = 0;
            Bonds.award(server, data, d.getUUID(), ia, 3, when + 48000);
            check(dd.level() == Bonds.Level.SWORN && !town.chronicle.entries.isEmpty()
                    && town.chronicle.entries.get(town.chronicle.entries.size() - 1).text().contains("swore themselves kin"), "Sworn Kin chronicled for a town soldier");
            data.member(e.getUUID()).seen = data.member(ia).seen = when;
            d.hurt(level.damageSources().mobAttack(zombie), 1000);
            check(town.chronicle.entries.get(town.chronicle.entries.size() - 1).text().contains("Dunn fell"), "Fall of sworn kin chronicled: "
                    + town.chronicle.entries.get(town.chronicle.entries.size() - 1).text());
            check(town.chronicle.entries.stream().filter(x -> x.text().contains("Dunn fell")).count() == 1
                    && town.chronicle.entries.get(town.chronicle.entries.size() - 1).text().contains("Edda and Aldric"), "One chronicle line for both sworn partners: "
                    + town.chronicle.entries.get(town.chronicle.entries.size() - 1).text());
            check(Chronicle.fallChronicled(d.getUUID()), "The fall is marked as chronicled, so the battle report leaves it out");
            check(town.soldiers.isEmpty(), "Town still forgets its fallen soldier");
            long late = Math.max(t3, when) + 48000 + Bonds.FORMING_FORGOTTEN + Bonds.VENGEANCE_TICKS * 4L;
            // Aldric and Edda were both away the whole time: that alone doesn't end their bond
            data.member(ia).seen = data.member(e.getUUID()).seen = late - Bonds.GONE_TICKS * 3;
            // ghosts: a partner discarded (a promotion through Villager Recruits' own screen), one gone to another player, one unseen for 5 days
            LivingEntity g = spawn(level, UnitType.FOOTMAN, at.offset(-2, 0, 2), owner, "Gort", spawned);
            Bonds.engage(g, when);
            data.pair(ia, g.getUUID(), true).points = 10;
            g.discard();
            check(data.member(g.getUUID()).removed, "A discarded recruit is known removed");
            UUID given = UUID.randomUUID(), lost = UUID.randomUUID();
            data.member(given, true).owner = stranger; data.member(given).seen = late;
            data.pair(ia, given, true).points = 10;
            data.member(lost, true).owner = owner; data.member(lost).seen = data.member(ia).seen - Bonds.GONE_TICKS;
            data.pair(ia, lost, true).points = 10;
            Bonds.sweep(server, late);
            check(data.pair(ia, f.getUUID()) == null && data.member(f.getUUID()) == null, "Old forming tie and its idle recruit forgotten");
            check(data.pair(ia, e.getUUID()) != null, "Real bonds are never forgotten");
            check(data.pair(ia, g.getUUID()) == null && data.pair(ia, given) == null && data.pair(ia, lost) == null, "Ghost bonds are pruned in the sweep");
            data.forget(given); data.forget(lost);

            // the server stopping clears fights in progress
            Bonds.engage(a, late); Bonds.engage(f, late);
            check(Bonds.battlesInProgress() > 0, "A shared fight in progress");
            check(Bonds.class.getMethod("stopped", net.minecraftforge.event.server.ServerStoppedEvent.class).isAnnotationPresent(SubscribeEvent.class), "Stop handler subscribed");
            Bonds.stopped(null);
            check(Bonds.battlesInProgress() == 0 && CombatClock.all().isEmpty(), "Server stop clears battles and combat marks");
        } finally {
            for (Entity x : spawned) if (x != null) { data.forget(x.getUUID()); x.discard(); }
            if (town != null) SettlementManager.get(server).remove(town);
            Bonds.resetBattles();
        }
    }

    /** One battle of n shared fights, 5 seconds apart, then 30 quiet seconds. */
    private static void battle(net.minecraft.server.MinecraftServer server, LivingEntity a, LivingEntity b, long start, int fights) {
        for (int i = 0; i < fights; i++) { Bonds.engage(a, start + i * Bonds.PULSE_TICKS); Bonds.engage(b, start + i * Bonds.PULSE_TICKS); }
        Bonds.settle(server, start + (fights - 1) * Bonds.PULSE_TICKS + Bonds.BATTLE_QUIET + 1);
    }

    private static String pts(BondData data, UUID a, UUID b) { var p = data.pair(a, b); return p == null ? "none" : String.valueOf(p.points); }

    private static LivingEntity spawn(ServerLevel level, UnitType unit, BlockPos pos, UUID owner, String name, List<Entity> spawned) {
        Entity e = RecruitsBridge.spawn(level, unit, RecruitsBridge.surface(level, pos), owner, name);
        check(e instanceof LivingEntity, "Spawn " + name);
        spawned.add(e);
        return (LivingEntity) e;
    }

    private static boolean near(double x, double y) { return Math.abs(x - y) < 1e-4; }

    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException("Bonds: " + message);
        checks++;
    }

    private BondsSelfTest() {}
}
