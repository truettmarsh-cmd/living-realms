package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.settlement.vx.VxBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.*;

/**
 * Statecraft assertions: war ledger, peace terms, tribute, truces, loyalty and rebellion, save and load.
 * Run from the warfare test, and from the Village Expansion test with and without the mod.
 */
final class StatecraftSelfTest {
    private int checks;
    private void check(boolean ok, String text) { if (!ok) throw new IllegalStateException("Statecraft: " + text); checks++; }

    /** Core rules on an isolated manager; returns the number of assertions. */
    static int run(ServerLevel level) {
        StatecraftSelfTest t = new StatecraftSelfTest();
        t.core(level);
        return t.checks;
    }

    /** Village Expansion: its wars enter the ledger, treaties move its claims and its diplomacy. */
    static int withVillageExpansion(ServerLevel level) {
        StatecraftSelfTest t = new StatecraftSelfTest();
        t.vx(level);
        return t.checks;
    }

    private static Settlement town(SettlementManager m, UUID owner, String ruler, String name, int x, int z) {
        Settlement s = new Settlement();
        s.owner = owner; s.ownerName = owner == null ? "" : ruler; s.name = name;
        s.center = new BlockPos(x, 100, z); s.founded = 0;
        m.add(s);
        return s;
    }

    private static CompoundTag terms(UUID enemy, String term, int days) {
        CompoundTag d = new CompoundTag();
        d.putUUID("enemy", enemy); d.putString("term", term); d.putInt("days", days);
        return d;
    }

    private void core(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ServerPlayer owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "StateRuler"));
        ServerPlayer rival = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "StateRival"));
        SettlementManager m = new SettlementManager();
        int z = 16000 + level.random.nextInt(2000);
        Settlement seat = town(m, owner.getUUID(), "StateRuler", "Crownhold", 0, z);
        Settlement npcCap = town(m, null, "", "Ironvale", -200, z);
        Settlement npcA = town(m, null, "", "Ashby", -400, z), npcB = town(m, null, "", "Brook", -600, z);
        npcA.kingdom = npcCap.kingdomId(); npcB.kingdom = npcCap.kingdomId();
        npcA.founded = 5; npcB.founded = 5;
        Kingdoms.normalize(m);
        UUID P = owner.getUUID(), N = npcCap.kingdomId();
        check(npcCap.capital && !npcA.capital && Kingdoms.same(npcA, npcCap), "NPC kingdom of three cities");
        long now = server.overworld().getGameTime();
        int day = Statecraft.dayLength();

        // ---- a raid opens a war and scores it
        Raids.Raid raid = new Raids.Raid();
        raid.source = npcA.id; raid.target = seat.id; raid.attacker = N; raid.defender = P;
        for (int i = 0; i < 4; i++) raid.soldiers.add(UUID.randomUUID());
        Statecraft.raidStarted(server, m, raid, npcA, seat);
        Statecraft.War war = m.state.war(P, N);
        check(war != null && war.marching.get(npcA.id) == 4, "Raid opens a war and records the marching force");
        Iterator<UUID> it = raid.soldiers.iterator(); it.next(); it.remove(); it.next(); it.remove();
        Statecraft.raidEnded(server, m, raid, false);
        check(Math.round(war.scoreFor(P)) == 12 && Math.round(war.exhaustion(N)) == 11 && war.marching.isEmpty(),
                "Repelled raid: +10 and +1 per kill to the defender, exhaustion to the attacker: " + war.scoreFor(P) + " / " + war.exhaustion(N));
        CompoundTag view = Statecraft.view(owner, m, seat);
        check(view.getList("wars", 10).size() == 1 && view.getList("wars", 10).getCompound(0).getInt("score") == 12, "Kingdom page lists the war and its score");

        // ---- peace terms: a city is refused until the war is won
        check(Statecraft.handle(owner, m, seat, "scPropose", terms(N, "CITY", 1)) != null && m.state.war(P, N) != null, "NPC refuses to cede a city on a small lead");
        check(Statecraft.handle(rival, m, seat, "scPropose", terms(N, "WHITE", 1)) != null, "Only the ruler proposes terms");
        war.addScore(P, 40);
        check(Statecraft.handle(owner, m, seat, "scPropose", terms(N, "CITY", 1)) == null, "NPC cedes a city when beaten");
        check(m.state.war(P, N) == null && P.equals(npcA.owner) && !Kingdoms.same(npcA, npcCap), "Ceded city (nearest non-capital) joins your kingdom");
        check(npcCap.kingdomId().equals(N) && N.equals(npcB.kingdomId()), "Rest of their kingdom kept");
        Statecraft.Fealty fa = m.state.loyalty.get(npcA.id);
        check(fa != null && fa.value == Statecraft.Fealty.START && N.equals(fa.former), "Ceded city starts restless, remembering its former lords");
        check(m.state.truceUntil(P, N) > now, "Treaty brings a truce");
        String blocked = Raids.canStart(server, m, npcCap, seat);
        check(blocked != null && blocked.startsWith("Truce"), "Truce blocks raids: " + blocked);

        // ---- tribute
        npcCap.wealth = 1000; npcB.wealth = 0;
        war = Wars.open(server, m, P, N, now, false);
        war.addScore(P, 30); war.tire(N, 20);
        check(Statecraft.handle(owner, m, seat, "scPropose", terms(N, "TRIBUTE", 1)) == null, "Beaten NPC agrees to pay tribute");
        Statecraft.Tribute tr = m.state.tribute(P, N);
        check(tr != null && tr.payer.equals(N) && tr.perDay == 8 && tr.daysLeft == Wars.TRIBUTE_DAYS - 1, "Tribute recorded and first day paid");
        check(seat.stock.count(Items.EMERALD) == 8 && Math.abs(npcCap.wealth - 960) < 0.01, "Emeralds reach your capital from their treasury");
        check(Statecraft.raidBlocker(server, m, npcCap, seat) != null, "Raids barred while tribute flows");
        Wars.payTributes(server, m, now + day);
        check(tr.daysLeft == Wars.TRIBUTE_DAYS - 2 && seat.stock.count(Items.EMERALD) == 16, "Tribute paid daily");
        npcCap.wealth = 0;
        Wars.payTributes(server, m, now + 2L * day);
        check(m.state.tribute(P, N) == null && m.state.truceUntil(P, N) == 0, "Default ends tribute and breaks the truce");
        check(Statecraft.raidBlocker(server, m, npcCap, seat) == null, "Raids possible again after a default");

        // ---- exhaustion forces peace
        war = Wars.open(server, m, P, N, now, false);
        war.addScore(P, 25); war.tire(N, 200);
        npcCap.wealth = 1000;
        Wars.step(server, m, now, 0);
        check(m.state.war(P, N) == null && m.state.tribute(P, N) != null && m.state.tribute(P, N).payer.equals(N), "Exhausted NPC sues for peace and pays the winner");
        m.state.tributes.clear(); m.state.truces.clear();
        war = Wars.open(server, m, P, N, now, false);
        war.tire(P, 10);
        Wars.step(server, m, now, 0.5);
        check(Math.round(war.exhaustion(P)) == 15 && Math.round(war.exhaustion(N)) == 5, "Exhaustion grows every day at war");
        m.state.wars.remove(war);

        // ---- between two players: an offer and an answer
        Settlement rivalTown = town(m, rival.getUUID(), "StateRival", "Rivalton", 400, z);
        UUID R = rival.getUUID();
        Wars.open(server, m, P, R, now, false);
        check(Statecraft.handle(owner, m, seat, "scPropose", terms(R, "WHITE", 3)) == null && m.state.war(P, R).offer != null, "Offer to a player waits for an answer");
        check(Statecraft.handle(owner, m, seat, "scAccept", terms(R, "WHITE", 3)) != null, "You cannot accept your own offer");
        check(Statecraft.handle(rival, m, rivalTown, "scAccept", terms(P, "WHITE", 3)) == null && m.state.war(P, R) == null, "Other ruler accepts");
        check(m.state.truceUntil(P, R) >= now + 3L * day, "Agreed truce length");

        // ---- loyalty, unrest and largesse
        List<String> why = new ArrayList<>();
        check(Statecraft.mood(npcA, why) < 0 && why.stream().anyMatch(w -> w.contains("unrest")), "Unrest lowers a restless city's mood");
        check(Statecraft.mood(seat, new ArrayList<>()) == 0, "Founded cities know no unrest");
        npcA.stock.force(Items.EMERALD, 30);
        check(Statecraft.handle(owner, m, npcA, "scPacify", new CompoundTag()) == null && Math.round(fa.value) == 42 && npcA.stock.count(Items.EMERALD) == 6, "Largesse buys loyalty");
        check(Statecraft.handle(owner, m, npcA, "scPacify", new CompoundTag()) != null, "Largesse once a day");
        CompoundTag lv = Statecraft.view(owner, m, npcA).getCompound("loyalty");
        check(lv.getBoolean("conquered") && lv.getInt("value") == 42 && !lv.getList("why", 8).isEmpty(), "Kingdom page explains loyalty");

        // ---- rebellion: back to the former lords
        npcA.taxLevel = 3; npcA.happiness = 0; npcA.starving = true; npcA.soldiers.clear();
        check(Loyalty.perDay(server, m, npcA, fa) < 0, "Misrule loses loyalty");
        for (int i = 0; i < 80 && P.equals(npcA.owner); i++) Loyalty.step(server, m, now + i * 1200L, 0.25);
        check(npcA.owner == null && Kingdoms.same(npcA, npcCap) && !m.state.loyalty.containsKey(npcA.id), "Rebellious city returns to its former kingdom");
        check(m.state.war(P, N) != null && npcA.log.stream().anyMatch(l -> l.contains("rebellion")), "Rebellion is war, and it is chronicled");
        m.state.wars.clear();

        // ---- rebellion: a free city when the former lords are gone
        check(Kingdoms.conquer(server, m, seat, npcCap) == 3, "Their capital brings the rest");
        check(m.state.loyalty.size() == 3 && Statecraft.cities(m, N).isEmpty(), "Each conquered city starts restless");
        Statecraft.Fealty fb = m.state.loyalty.get(npcB.id);
        npcB.taxLevel = 3; npcB.happiness = 0; npcB.starving = true; fb.value = 12;
        for (int i = 0; i < 80 && P.equals(npcB.owner); i++) Loyalty.step(server, m, now + i * 1200L, 0.25);
        UUID free = npcB.kingdomId();
        check(npcB.owner == null && !free.equals(N) && !free.equals(P) && npcB.capital, "With no former kingdom left, the city declares itself free");
        check(Statecraft.name(m, free).startsWith("the Free City of"), "Free city named: " + Statecraft.name(m, free));
        check(m.state.war(P, free) != null, "The free city is at war with you");

        // ---- integration: loyal for good
        Statecraft.Fealty fc = m.state.loyalty.get(npcCap.id);
        check(fc != null, "Former capital is a restless province");
        fc.value = 89.9; npcCap.happiness = 100; npcCap.taxLevel = 0; npcCap.starving = false;
        Loyalty.step(server, m, now, 0.1);
        check(!m.state.loyalty.containsKey(npcCap.id) && Statecraft.mood(npcCap, new ArrayList<>()) == 0, "At 90 a city is loyal for good");

        // ---- save and load
        war = m.state.war(P, free);
        war.addScore(P, 7); war.tire(free, 33);
        Statecraft.Offer o = new Statecraft.Offer(); o.from = P; o.term = Statecraft.Term.TRIBUTE; o.days = 7; o.expires = now + day;
        war.offer = o;
        rivalTown.stock.force(Items.EMERALD, 100);
        Wars.tribute(server, m, R, P, now);
        m.state.truces.put(Statecraft.pair(P, R), now + 5000);
        CompoundTag saved = m.save(new CompoundTag());
        check(saved.contains("statecraft"), "Statecraft saved with the settlements");
        SettlementManager back = SettlementManager.load(saved);
        Statecraft.War bw = back.state.war(P, free);
        check(bw != null && Math.round(bw.scoreFor(P)) == Math.round(war.scoreFor(P)) && Math.round(bw.exhaustion(free)) == Math.round(war.exhaustion(free)), "War survives reload");
        check(bw.offer != null && bw.offer.term == Statecraft.Term.TRIBUTE && bw.offer.days == 7 && P.equals(bw.offer.from), "Pending offer survives reload");
        check(back.state.truceUntil(P, R) == now + 5000 && back.state.tribute(P, R) != null && back.state.tribute(P, R).daysLeft == m.state.tribute(P, R).daysLeft, "Truces and tribute survive reload");
        check(back.state.loyalty.size() == m.state.loyalty.size() && !m.state.loyalty.isEmpty()
                && m.state.loyalty.entrySet().stream().allMatch(e -> back.state.loyalty.containsKey(e.getKey())
                && Math.abs(back.state.loyalty.get(e.getKey()).value - e.getValue().value) < 1e-6), "Loyalty survives reload");
        check(Statecraft.name(back, free).equals(Statecraft.name(m, free)), "Free city's name survives reload");
        check(!new SettlementManager().save(new CompoundTag()).contains("statecraft"), "Nothing saved for a world without wars or conquests");
        for (UUID id : List.of(seat.id, npcCap.id, npcA.id, npcB.id, rivalTown.id)) Loyalty.unrest(id, null);
    }

    private void vx(ServerLevel level) {
        MinecraftServer server = level.getServer();
        SettlementManager m = SettlementManager.get(server);
        ServerPlayer owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "TreatyRuler"));
        VillageExpansionSelfTest.RULERS.put(owner.getUUID(), owner.getScoreboardName());
        BlockPos a = new BlockPos(12000, 100, 12000), b = new BlockPos(12200, 100, 12000);
        VillageExpansionSelfTest.flat(level, a, 64);
        VillageExpansionSelfTest.flat(level, b, 64);
        String vf = VxBridge.testVillage(level, "village_12000_100_12000", a);
        VxBridge.testVillage(level, vf, b);
        Realms.sync(server, m);
        Settlement va = m.at(net.minecraft.world.level.Level.OVERWORLD, a), vb = m.at(net.minecraft.world.level.Level.OVERWORLD, b);
        check(va != null && vb != null && va.mirror() && vb.mirror() && Kingdoms.same(va, vb), "Two villages of one faction mirrored");
        Settlement home = VillageExpansionSelfTest.town(m, owner.getUUID(), "Treatyhold", new BlockPos(12000, 100, 13000));
        VillageExpansionSelfTest.flat(level, home.center, 20);
        String realm = Realms.realm(server, m, home);
        UUID P = owner.getUUID(), F = va.kingdomId();
        long now = server.overworld().getGameTime();
        VxBridge.declareWar(level, realm, vf);
        Wars.syncVillageExpansion(server, m, now);
        Statecraft.War war = m.state.war(P, F);
        check(war != null, "A Village Expansion war enters the ledger");
        check(Statecraft.handle(owner, m, home, "scPropose", terms(F, "WHITE", 1)) != null && m.state.war(P, F) != null, "No treaty during Village Expansion's peace lock after an attack");
        com.example.villagerecruits.diplomacy.AssaultManager.recordAttack(realm, vf, now - 100000);
        check(!VxBridge.peaceLocked(level, realm, vf), "Peace lock lapses");
        check(Statecraft.handle(owner, m, home, "scPropose", terms(F, "CITY", 3)) != null && m.state.war(P, F) != null, "Village faction refuses to cede on an even war");
        war.addScore(P, 70);
        Settlement ceded = Wars.cedable(m, F, P);
        check(ceded != null && !ceded.capital, "A village to cede");
        BlockPos at = ceded.center;
        check(Statecraft.handle(owner, m, home, "scPropose", terms(F, "CITY", 3)) == null, "Beaten faction cedes a village");
        check(P.equals(ceded.owner) && !ceded.mirror() && realm.equals(VxBridge.ownerAt(level, at)), "Ceded village is your city, Village Expansion claim and all");
        check(!VxBridge.atWar(realm, vf) && Realms.truce(m, realm, vf) > now, "Village Expansion makes peace and keeps the truce: war "
                + VxBridge.atWar(realm, vf) + " truce " + Realms.truce(m, realm, vf) + " now " + now + " realms " + Wars.realmOf(server, m, P, false) + "/" + Wars.realmOf(server, m, F, false)
                + " vs " + realm + "/" + vf);
        Settlement theirs = ceded == va ? vb : va; // the village they kept
        check(m.state.war(P, F) == null && Statecraft.raidBlocker(server, m, home, theirs) != null, "Treaty truce bars raids on their villages");
        Wars.syncVillageExpansion(server, m, now);
        check(m.state.war(P, F) == null, "Peace holds after a sync");
        Statecraft.Fealty f = m.state.loyalty.get(ceded.id);
        check(f != null && vf.equals(f.formerFaction), "Ceded village remembers its faction");
        Loyalty.rebel(server, m, ceded, f);
        check(ceded.mirror() && vf.equals(ceded.vxFaction) && vf.equals(VxBridge.ownerAt(level, at)) && ceded.owner == null, "A rebelling village returns to its Village Expansion faction");
        Realms.sync(server, m);
        check(ceded.mirror() && vf.equals(ceded.vxFaction), "Sync agrees with the rebellion");
        // a war Village Expansion ends itself leaves the ledger
        m.state.truces.clear();
        m.truces.clear();
        VxBridge.declareWar(level, realm, vf);
        Wars.syncVillageExpansion(server, m, now);
        check(m.state.war(P, F) != null, "Renewed war recorded");
        com.example.villagerecruits.diplomacy.AssaultManager.recordAttack(realm, vf, now - 100000);
        VxBridge.makePeace(level, realm, vf, 1200);
        Wars.syncVillageExpansion(server, m, now);
        check(m.state.war(P, F) == null, "Village Expansion's own peace ends the war");
        var reload = SettlementManager.load(m.save(new CompoundTag()));
        check(reload.state.wars.size() == m.state.wars.size() && reload.state.loyalty.size() == m.state.loyalty.size(), "Ledger survives reload alongside Village Expansion");
    }

    private StatecraftSelfTest() {}
}
