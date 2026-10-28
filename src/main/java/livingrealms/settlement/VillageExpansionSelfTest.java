package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.Config;
import livingrealms.LivingRealms;
import livingrealms.building.*;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import livingrealms.settlement.vx.Vx;
import livingrealms.settlement.vx.VxBridge;
import livingrealms.settlement.vx.VxLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** ./gradlew runServer -PvillageExpansionTest -PvillageExpansionTestDir=<dir> [-PvillageExpansion]: with or without Village Expansion. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class VillageExpansionSelfTest {
    private static int checks;
    static final Map<UUID, String> RULERS = new HashMap<>();
    private static void check(boolean ok, String text) { if (!ok) throw new IllegalStateException(text); checks++; }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.villageExpansionTest")) return;
        String report;
        try {
            if (Vx.present()) withMod(event.getServer().overworld()); else without(event.getServer().overworld());
            report = "PASS: " + checks + " village expansion assertions (" + (Vx.present() ? "Village Expansion loaded" : "Village Expansion absent") + ")";
        } catch (Throwable error) { report = "FAIL: " + error; LivingRealms.LOG.error("VILLAGE EXPANSION TEST FAILED", error); }
        LivingRealms.LOG.info("VILLAGE EXPANSION TEST {}", report);
        try { Files.writeString(Path.of("village-expansion-result.txt"), report); } catch (Exception e) { throw new RuntimeException(e); }
        event.getServer().halt(false);
    }

    static Settlement town(SettlementManager m, UUID owner, String name, BlockPos center) {
        Settlement s = new Settlement(); s.owner = owner; s.ownerName = owner == null ? "" : RULERS.getOrDefault(owner, name); s.name = name; s.center = center;
        m.add(s); return s;
    }

    static void flat(ServerLevel level, BlockPos c, int r) {
        for (int x = c.getX() - r; x <= c.getX() + r; x++) for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
            if ((x & 15) == 0 && (z & 15) == 0 || x == c.getX() - r && z == c.getZ() - r) level.getChunk(x >> 4, z >> 4);
            for (int y = 98; y <= 108; y++) level.setBlock(new BlockPos(x, y, z), (y <= 99 ? Blocks.GRASS_BLOCK : Blocks.AIR).defaultBlockState(), 2);
        }
    }

    /** Living Realms alone: nothing of the integration may show. */
    static void without(ServerLevel level) {
        var server = level.getServer();
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "SoloRuler"));
        var m = SettlementManager.get(server);
        check(!Vx.present() && !Vx.active() && !Realms.on(), "Integration inert without Village Expansion");
        check(Config.VILLAGE_EXPANSION.get(), "villageExpansion.enabled defaults to true");
        Settlement s = town(m, owner.getUUID(), "Solo", new BlockPos(4096, 100, 4096));
        Settlement npc = town(m, null, "Solo NPC", new BlockPos(4400, 100, 4096));
        flat(level, s.center, 40);
        Realms.plan(level, s);
        check(s.layout.isEmpty(), "No Village Expansion layout without the mod");
        CompoundTag saved = s.save();
        check(!saved.contains("vxFaction") && !saved.contains("vxLayout"), "Settlement save format unchanged");
        check(!m.save(new CompoundTag()).contains("vxRealms") && !m.save(new CompoundTag()).contains("vxTruces"), "World save format unchanged");
        check(Realms.raidBlocker(server, m, s, npc) == null && Realms.raidBlocker(server, m, npc, s) == null, "Raids keep their Living Realms rules");
        check(!livingrealms.net.View.of(s, owner).contains("vx"), "City Manager shows no Village Expansion panel");
        check(level.getScoreboard().getPlayerTeam("lr_" + owner.getUUID().toString().replace("-", "").substring(0, 10)) == null, "No Recruits faction created");
        check(Raids.npcTarget(server, m, npc) == null || !Raids.npcTarget(server, m, npc).mirror(), "NPC raids unchanged");
        m.remove(s); m.remove(npc);
    }

    static void withMod(ServerLevel level) {
        MinecraftServer server = level.getServer();
        var m = SettlementManager.get(server);
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "RealmRuler"));
        var stranger = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "Stranger"));
        RULERS.put(owner.getUUID(), owner.getScoreboardName());
        check(Vx.present() && Realms.on(), "Village Expansion detected and switched on");

        // ---- the switch off: Living Realms behaves as without the mod
        Config.VILLAGE_EXPANSION.set(false);
        try {
            check(!Vx.active() && !Realms.on(), "Config switch turns the integration off");
            Settlement solo = town(m, owner.getUUID(), "Switch test", new BlockPos(-4096, 100, -4096));
            flat(level, solo.center, 30);
            Realms.plan(level, solo);
            Realms.tick(server, m, Realms.SYNC);
            check(solo.layout.isEmpty() && m.all().stream().noneMatch(Settlement::mirror), "Switched off: no layouts, no mirrors");
            check(!livingrealms.net.View.of(solo, owner).contains("vx") && !solo.save().contains("vxLayout"), "Switched off: City Manager and saves unchanged");
            check(!m.realms.containsKey(owner.getUUID()), "Switched off: no realm created");
            m.remove(solo);
        } finally { Config.VILLAGE_EXPANSION.set(true); }

        // ---- NPC villages belong to Village Expansion; Living Realms mirrors them once
        BlockPos a = new BlockPos(8192, 100, 8192), b = new BlockPos(8392, 100, 8192);
        flat(level, a, 64); flat(level, b, 64);
        String vf = VxBridge.testVillage(level, "village_8192_100_8192", a);
        VxBridge.testVillage(level, vf, b);
        check(VxBridge.isNpc(vf) && vf.equals(VxBridge.villages(level).get(a)) && vf.equals(VxBridge.villages(level).get(b)), "Test village faction owns two centres");
        BlockPos c = new BlockPos(8192, 100, 8592);
        flat(level, c, 64);
        Settlement legacy = town(m, null, "Old LR village", c.offset(4, 0, 3));
        String other = VxBridge.testVillage(level, "village_8192_100_8592", c);
        int before = m.all().size();
        Realms.sync(server, m);
        Realms.sync(server, m);
        List<Settlement> mirrors = m.all().stream().filter(s -> vf.equals(s.vxFaction)).toList();
        check(mirrors.size() == 2, "Each Village Expansion village mirrored exactly once: " + mirrors.size());
        Settlement ma = m.at(net.minecraft.world.level.Level.OVERWORLD, a), mb = m.at(net.minecraft.world.level.Level.OVERWORLD, b);
        check(ma != null && ma.mirror() && mb != null && mb.mirror() && ma.isAi() && Kingdoms.same(ma, mb), "Mirrors form one NPC kingdom per faction");
        check(ma.capital && !mb.capital && ma.kingdomId().equals(Realms.kingdomFor(vf)), "VE capital is the kingdom capital");
        check(legacy.mirror() && other.equals(legacy.vxFaction) && m.all().size() == before + 2, "Old Living Realms village adopted, never doubled");
        int count = m.all().size();
        ServerEvents.discover(level, m, new net.minecraft.world.level.ChunkPos(a));
        check(m.all().size() == count, "Generated villages are not converted a second time");
        CompoundTag rename = new CompoundTag(); rename.putString("name", "Mine now");
        Actions.handle(owner, ma.id, "rename", rename);
        check(!ma.name.equals("Mine now"), "Mirrors take no Living Realms orders");
        Settlement step = Settlement.load(ma.save());
        check(step.mirror() && step.vxFaction.equals(vf), "Mirror survives reload");

        // ---- your kingdom is a faction in Village Expansion's diplomacy
        Settlement capital = town(m, owner.getUUID(), "Crown", new BlockPos(8192, 100, 8800));
        Settlement province = town(m, owner.getUUID(), "March", new BlockPos(8500, 100, 8800));
        flat(level, capital.center, 20); flat(level, province.center, 20);
        String realm = Realms.realm(server, m, capital);
        check(realm != null && realm.startsWith(VxBridge.PREFIX) && VxBridge.exists(realm) && !VxBridge.isNpc(realm), "Player kingdom gets a Recruits faction");
        check(VxBridge.registered(realm) && owner.getUUID().equals(VxBridge.leader(realm)), "Realm registered in Village Expansion and led by the ruler");
        check(realm.equals(Realms.realm(server, m, province)) && realm.equals(m.realms.get(owner.getUUID())), "One realm per kingdom");
        check(level.getScoreboard().getPlayersTeam(owner.getScoreboardName()) != null
                && realm.equals(level.getScoreboard().getPlayersTeam(owner.getScoreboardName()).getName()), "Ruler joins the realm's team");
        check(Realms.raidBlocker(server, m, capital, ma) != null && Realms.raidBlocker(server, m, capital, ma).startsWith("Not at war"), "No raid on a village without war");
        check(Realms.raidBlocker(server, m, ma, mb) != null, "Living Realms never runs VE-on-VE raids");
        var view = livingrealms.net.View.of(capital, owner).getCompound("vx");
        check(view.getList("factions", 10).size() >= 1 && view.getBoolean("ruler"), "City Manager lists nearby factions");

        // ---- an outmatched faction does not declare war (measured strength, not VE's empty assessment of a claimless realm)
        Realms.NEXT_WAR.clear();
        List<Settlement.Soldier> host = new ArrayList<>();
        for (int i = 0; i < 40; i++) { var g = new Settlement.Soldier(); g.entity = UUID.randomUUID(); g.type = UnitType.FOOTMAN; g.name = "Host"; host.add(g); }
        capital.soldiers.addAll(host);
        var middling = new LegacyRandomSource(1) { @Override public double nextDouble() { return 0.2; } };
        Realms.considerWars(server, m, middling, true);
        check(!VxBridge.atWar(vf, realm) && !VxBridge.atWar(other, realm), "Outmatched factions keep the peace (recruits: " + VxBridge.recruitCount(vf) + ", " + VxBridge.recruitCount(other) + ")");
        capital.soldiers.removeAll(host);

        // ---- they declare war on you
        Realms.NEXT_WAR.clear();
        var always = new LegacyRandomSource(1) { @Override public double nextDouble() { return 0; } };
        Realms.considerWars(server, m, always, true);
        check(VxBridge.atWar(vf, realm) && "ENEMY".equals(VxBridge.relation(realm, vf)), "Village faction declares war on the realm");
        check(capital.log.stream().anyMatch(l -> l.contains("declared war")), "Ruler is told");
        check(Realms.raidBlocker(server, m, capital, ma) == null && Realms.raidBlocker(server, m, ma, capital) == null, "War opens raids both ways");
        CompoundTag who = new CompoundTag(); who.putString("faction", vf);
        Actions.handle(stranger, capital.id, "vxPeace", who);
        check(VxBridge.atWar(vf, realm), "Only the ruler makes peace");
        var listed = livingrealms.net.View.of(capital, owner).getCompound("vx").getList("factions", 10);
        check(listed.stream().anyMatch(t -> ((CompoundTag) t).getBoolean("war") && ((CompoundTag) t).getString("id").equals(vf)), "City Manager shows the war");

        // ---- a village marches on your city: its own recruits, real combat targets
        List<Entity> villagers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Entity e = RecruitsBridge.spawn(level, UnitType.FOOTMAN, a.offset(i, 0, 2), null, "Village soldier");
            check(e != null, "Village soldier spawned");
            VxBridge.surrender(level, e, vf, a);
            villagers.add(e);
        }
        Realms.roster(level, m, ma);
        check(ma.soldiers.size() == 4 && ma.soldiers.stream().allMatch(x -> VxBridge.recruits(vf).contains(x.entity)), "Mirror garrison is the faction's own recruits");
        Entity defender = RecruitsBridge.spawn(level, UnitType.FOOTMAN, capital.center.above(), owner.getUUID(), "Defender");
        var ds = new Settlement.Soldier(); ds.entity = defender.getUUID(); ds.type = UnitType.FOOTMAN; ds.name = "Defender"; capital.soldiers.add(ds);
        check(Raids.start(server, m, ma, capital) == null, "Village raid on your city starts: " + Raids.canStart(server, m, ma, capital));
        var raid = m.raids.get(0);
        for (UUID id : raid.soldiers) level.getEntity(id).teleportTo(capital.center.getX() + 2, 100, capital.center.getZ());
        Raids.tickRaid(server, m, raid);
        Entity marcher = level.getEntity(raid.soldiers.iterator().next());
        check(((net.minecraft.world.entity.Mob) marcher).getTarget() == defender && realm.equals(VxBridge.team(defender)), "Village soldiers and your guard fight each other");
        check(VxBridge.fakeOwner().equals(RecruitsBridge.owner(marcher)) && vf.equals(VxBridge.team(marcher)), "Raid leaves village soldiers with their faction");
        Raids.finish(server, m, raid, "Test over");
        capital.soldiers.remove(ds);
        defender.discard();
        for (Settlement t : m.all()) t.raidCooldown = 0;

        // ---- a beaten, outmatched faction sues for peace; a winning one does not
        check(Strategy.defeats(m, ma.kingdomId(), level.getGameTime()) >= 0.9, "The repelled raid is remembered");
        ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(level.getGameTime() + 4000); // past VE's wartime peace lock
        long now = level.getGameTime();
        Realms.considerWars(server, m, middling, true);
        check(!Realms.offersPeace(vf, realm, now), "A faction stronger than the realm fights on");
        capital.soldiers.addAll(host);
        Realms.considerWars(server, m, middling, true);
        check(Realms.offersPeace(vf, realm, now) && capital.log.stream().anyMatch(l -> l.contains("sues for peace")), "Beaten faction sues for peace");
        check(Realms.suing(m, ma, capital, now) && Raids.npcTarget(server, m, ma) == null, "No raids while suing for peace");
        check(Realms.peace(owner, m, capital, vf) == null && !VxBridge.atWar(vf, realm) && !Realms.offersPeace(vf, realm, now), "The ruler accepts the offer");
        capital.soldiers.removeAll(host);

        // ---- they take your capital, and with it every city
        Entity guard = RecruitsBridge.spawn(level, UnitType.FOOTMAN, province.center.above(), owner.getUUID(), "Guard");
        check(guard != null, "Guard spawned");
        var so = new Settlement.Soldier(); so.entity = guard.getUUID(); so.type = UnitType.FOOTMAN; so.name = "Guard"; province.soldiers.add(so);
        province.stock.force(net.minecraft.world.item.Items.DIAMOND, 3);
        check(Kingdoms.conquer(server, m, ma, capital) == 2, "Capital loss transfers every city");
        check(capital.mirror() && province.mirror() && vf.equals(capital.vxFaction) && capital.owner == null && Kingdoms.same(capital, ma), "Lost cities become the faction's villages");
        check(vf.equals(VxBridge.ownerAt(level, capital.center)) && vf.equals(VxBridge.ownerAt(level, province.center)), "Village Expansion claims follow the conquest");
        check(VxBridge.centers(vf).stream().anyMatch(p -> p.getX() == province.center.getX() && p.getZ() == province.center.getZ()), "Captured city is a centre of the faction");
        check(vf.equals(VxBridge.team(guard)), "Garrison joins the village faction's team: " + VxBridge.team(guard));
        check(VxBridge.fakeOwner().equals(RecruitsBridge.owner(guard)), "Garrison serves the village: " + RecruitsBridge.owner(guard));
        check(VxBridge.recruits(vf).contains(guard.getUUID()), "Garrison enrolled in the village faction");
        check(province.stock.count(net.minecraft.world.item.Items.DIAMOND) == 3 && ma.capital, "City stores kept; winner keeps its capital");
        check(m.ownedBy(owner.getUUID()).isEmpty(), "Ruler has no city left");
        int settled = m.all().size();
        Realms.sync(server, m);
        check(m.all().size() == settled && capital.mirror() && province.mirror(), "Sync agrees with the conquest");

        // ---- you take a village, then their capital and everything with it
        Settlement seat = town(m, owner.getUUID(), "New Seat", new BlockPos(7600, 100, 8192));
        flat(level, seat.center, 20);
        check(seat.capital && realm.equals(Realms.realm(server, m, seat)), "New capital fights under the same realm");
        check(Kingdoms.conquer(server, m, seat, mb) == 1, "Ordinary village taken alone");
        check(!mb.mirror() && owner.getUUID().equals(mb.owner) && realm.equals(VxBridge.ownerAt(level, b)), "Captured village becomes your city, claim and all");
        check(VxBridge.centers(realm).stream().anyMatch(p -> p.getX() == b.getX() && p.getZ() == b.getZ()) && VxBridge.centers(vf).stream().noneMatch(p -> p.getX() == b.getX() && p.getZ() == b.getZ()),
                "Village Expansion centres moved to your realm");
        check(Kingdoms.conquer(server, m, seat, ma) == 3, "Their capital brings every village");
        check(m.all().stream().noneMatch(s -> vf.equals(s.vxFaction)) && m.ownedBy(owner.getUUID()).size() == 5, "Whole faction annexed");
        check(VxBridge.villages(level).entrySet().stream().noneMatch(e -> vf.equals(e.getValue())), "Village Expansion agrees: the faction holds nothing");
        Realms.sync(server, m);
        check(m.ownedBy(owner.getUUID()).size() == 5 && m.all().stream().noneMatch(s -> vf.equals(s.vxFaction)), "Sync keeps the annexation");

        // ---- a Recruits siege (VE's own conquest path) is mirrored too
        BlockPos d = new BlockPos(6900, 100, 8192); // VE refuses new villages within 600 blocks of player-held claims
        flat(level, d, 64);
        String third = VxBridge.testVillage(level, "village_6900_100_8192", d);
        Realms.sync(server, m);
        Settlement md = m.at(net.minecraft.world.level.Level.OVERWORLD, d);
        check(md != null && md.mirror() && third.equals(md.vxFaction), "Third faction mirrored: " + (md == null ? "none" : md.name + " " + md.vxFaction) + " / VE: " + VxBridge.villages(level).get(d));
        VxBridge.conquer(level, d, realm, third);
        check(owner.getUUID().equals(md.owner) && !md.mirror(), "Siege victory through Village Expansion hands the city over");

        // ---- data consistency
        for (var e : VxBridge.villages(level).entrySet()) {
            Settlement s = m.all().stream().filter(t -> t.center.getX() == e.getKey().getX() && t.center.getZ() == e.getKey().getZ()).findFirst().orElse(null);
            check(s != null, "Every VE village has a settlement");
            check(s.mirror() ? e.getValue().equals(s.vxFaction) : e.getValue().equals(m.realms.get(s.owner)), "Settlement owner matches VE claim at " + s.name);
        }

        // ---- Village Expansion's planner lays out your town
        Settlement planned = town(m, owner.getUUID(), "Planned", new BlockPos(9300, 100, 9300));
        flat(level, planned.center, 90);
        planned.population = 10;
        Realms.plan(level, planned);
        Map<String, Integer> zoning = new TreeMap<>();
        planned.layout.slots.forEach(z -> zoning.merge(z.kind, 1, Integer::sum));
        LivingRealms.LOG.info("Village Expansion layout for {}: {} lots {}", planned.name, planned.layout.slots.size(), zoning);
        check(planned.layout.slots.size() >= 6, "Village Expansion layout generated: " + planned.layout.slots.size());
        check(planned.layout.slots.stream().anyMatch(s -> s.kind.equals("house")) && planned.layout.slots.stream().anyMatch(s -> !s.kind.equals("house")), "Layout zones houses and work lots");
        check(planned.layout.slots.stream().allMatch(s -> s.hasRoad()), "Every lot fronts a planned street");
        var hall = BuildingCatalog.get("livingrealms:town_hall");
        var bp = BuildingCatalog.blueprint(hall.blueprintKey(1, planned.style));
        bp.bill().forEach((item, n) -> planned.stock.force(item, n + 500));
        planned.stock.force(Blueprint.costItem(Construction.foundationBlock(planned)), 5000);
        String manual = "no site";
        for (int dx = -24; dx <= 24 && manual != null; dx += 6) for (int dz = -30; dz <= 30 && manual != null; dz += 6) manual = Planning.planMap(level, planned, hall.id, planned.center.getX() + dx, planned.center.getZ() + dz, 0);
        check(manual == null, "Manual plan accepted: " + manual);
        Simulation.recompute(planned);
        AutoBuild.think(level, planned);
        check(planned.plots.size() == 1, "Your plans come first");
        planned.plots.clear();
        Simulation.recompute(planned);
        for (int i = 0; i < 5 && planned.plots.isEmpty(); i++) AutoBuild.think(level, planned);
        Plot first = planned.plots.isEmpty() ? null : planned.plots.get(0);
        check(first != null && first.automatic && first.typeId.equals(hall.id), "Town hall planned automatically");
        var box = first.box();
        check(planned.layout.slots.stream().anyMatch(s -> s.used && s.x >= box.minX() - 2 && s.x <= box.maxX() + 2 && s.z >= box.minZ() - 2 && s.z <= box.maxZ() + 2),
                "Building sits on a Village Expansion lot");
        check(planned.plots.stream().anyMatch(p -> p.typeId.equals("road") && p.automatic) || !planned.layout.paved.isEmpty(), "Its street is queued with it");
        var restored = Settlement.load(planned.save());
        check(restored.layout.slots.size() == planned.layout.slots.size() && restored.layout.slots.stream().filter(s -> s.used).count() >= 1
                && restored.layout.seed == planned.layout.seed, "Layout survives reload");

        // ---- save and load
        var reload = SettlementManager.load(m.save(new CompoundTag()));
        check(realm.equals(reload.realms.get(owner.getUUID())), "Realm id survives reload");
        check(reload.all().stream().filter(Settlement::mirror).count() == m.all().stream().filter(Settlement::mirror).count(), "Mirrors survive reload");
        check(reload.get(mb.id) != null && owner.getUUID().equals(reload.get(mb.id).owner) && !reload.get(mb.id).mirror(), "Conquests survive reload");

        // ---- switched off again: mirrors go dormant
        Config.VILLAGE_EXPANSION.set(false);
        try {
            Settlement anyMirror = m.all().stream().filter(Settlement::mirror).findFirst().orElse(null);
            check(anyMirror != null && Realms.dormant(anyMirror) && Raids.canStart(server, m, seat, anyMirror) != null, "Dormant mirrors cannot be raided");
            check(!livingrealms.net.View.of(seat, owner).contains("vx"), "Diplomacy panel hidden while off");
        } finally { Config.VILLAGE_EXPANSION.set(true); }
    }

    private VillageExpansionSelfTest() {}
}
