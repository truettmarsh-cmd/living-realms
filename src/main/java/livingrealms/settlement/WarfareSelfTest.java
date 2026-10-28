package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.LivingRealms;
import livingrealms.military.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;

/** Opt-in integration coverage in an isolated test world. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class WarfareSelfTest {
    private static int checks;
    private static void check(boolean value, String detail) {
        if (!value) throw new IllegalStateException(detail);
        checks++;
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.warfareTest")) return;
        String report;
        try {
            run(event.getServer().overworld());
            report = "PASS: " + checks + " warfare assertions";
            LivingRealms.LOG.info("WARFARE SELF TEST {}", report);
        } catch (Throwable error) {
            report = "FAIL: " + error;
            LivingRealms.LOG.error("WARFARE SELF TEST FAILED", error);
        }
        try { Files.writeString(Path.of("warfare-test-result.txt"), report); } catch (Exception e) { throw new RuntimeException(e); }
        event.getServer().halt(false);
    }

    private static Settlement town(SettlementManager m, UUID owner, String name, int x) {
        Settlement s = new Settlement(); s.owner = owner; s.ownerName = owner == null ? "" : name;
        s.name = name; s.center = new BlockPos(x, 100, 2048); s.founded = 0;
        m.add(s); return s;
    }

    private static void run(ServerLevel level) {
        var server = level.getServer();
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "WarTestOwner"));
        var other = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "WarTestOther"));
        var m = new SettlementManager();
        var seat = town(m, owner.getUUID(), "Seat", 0);
        var province = town(m, owner.getUUID(), "Province", 160);
        var colony = town(m, owner.getUUID(), "Colony", 320); colony.dim = Level.NETHER;
        var invader = town(m, other.getUUID(), "Invader", -160);
        var npc = town(m, null, "NPC A", -320);
        var unrelated = town(m, null, "NPC B", -480);
        check(seat.capital && !province.capital && !colony.capital, "One capital per player kingdom from founding");
        check(npc.capital && unrelated.capital && !Kingdoms.same(npc, unrelated), "Independent NPC kingdoms remain distinct");
        province.tier = Tier.CITY;
        check(province.tierBlockers().stream().anyMatch(s -> s.contains("kingdom capital")), "Second Capital rank blocked");
        province.tier = Tier.CAPITAL; seat.tier = Tier.CAPITAL;
        province.stock.force(Items.DIAMOND, 7);
        Plot building = new Plot(); building.id = 1; province.plots.add(building);
        Kingdoms.normalize(m);
        check(province.tier == Tier.CITY && province.stock.count(Items.DIAMOND) == 7 && province.plots.size() == 1,
                "Duplicate capitals repaired without losing buildings or stock");
        province.plots.clear();
        CompoundTag saved = m.save(new CompoundTag());
        for (Tag entry : saved.getList("settlements", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry; t.remove("kingdom"); t.remove("capital");
        }
        var migrated = SettlementManager.load(saved);
        check(migrated.get(seat.id).capital && !migrated.get(province.id).capital, "Legacy capitals migrated deterministically");
        check(!Kingdoms.same(migrated.get(npc.id), migrated.get(unrelated.id)), "Legacy NPC identities never collapse into one kingdom");
        check(Kingdoms.conquer(server, m, invader, province) == 1, "Ordinary victory transfers one city");
        check(province.owner.equals(other.getUUID()) && seat.owner.equals(owner.getUUID()) && colony.owner.equals(owner.getUUID()), "Ordinary victory leaves other cities alone");
        check(invader.capital && !province.capital, "Winner keeps one original capital");
        check(Kingdoms.conquer(server, m, npc, seat) == 2, "NPC capital capture transfers every remaining city across dimensions");
        check(seat.owner == null && colony.owner == null && Kingdoms.same(seat, npc) && Kingdoms.same(colony, npc), "NPC takes actual control");
        check(!Kingdoms.same(unrelated, npc) && unrelated.capital, "Other NPC kingdom untouched");
        check(Kingdoms.conquer(server, m, invader, npc) == 3, "Player can conquer NPC kingdom through its capital");
        check(Kingdoms.cities(m, invader).stream().filter(t -> t.capital).count() == 1, "One capital after repeated annexation");
        check(Kingdoms.conquer(server, m, invader, seat) == 0, "Friendly capture rejected");
        check(Kingdoms.designate(server, m, province) == null && province.capital && !invader.capital, "Capital designation switches one seat");
        check(Kingdoms.designate(server, m, invader) != null, "Capital movement cooldown enforced");
        var reload = SettlementManager.load(m.save(new CompoundTag()));
        check(reload.get(province.id).capital && Kingdoms.same(reload.get(colony.id), reload.get(invader.id)), "Conquest and capital survive reload");

        strategy();
        physical(level, owner, other);
    }

    /** Kingdom-level judgement (Strategy): odds, memory, peace, research. */
    private static void strategy() {
        check(Math.abs(Strategy.warChance(0.7, true, 0, false) - 0.55) < 1e-9 && Math.abs(Strategy.warChance(0.5, false, 0, false) - 0.18) < 1e-9
                && Strategy.warChance(1, true, 0, false) == 0.85, "War odds equal Village Expansion's when the faction is at least even");
        check(Strategy.warChance(0.2, true, 0, false) < Strategy.warChance(0.5, true, 0, false) / 2 && Strategy.warChance(Strategy.ratio(0, 12), true, 0, false) < 0.06,
                "A weaker faction is far less eager for war");
        check(Math.abs(Strategy.warChance(0.6, true, 1, false) - Strategy.warChance(0.6, true, 0, false) / 2) < 1e-9
                && Math.abs(Strategy.warChance(0.6, true, 0, true) - Strategy.warChance(0.6, true, 0, false) / 2) < 1e-9, "War weariness and a second front halve the odds");
        check(Strategy.ratio(0, 0) == 0.5 && Strategy.ratio(10, 2) > 0.7, "Strength ratio on Village Expansion's scale");
        check(Strategy.suesForPeace(0.2, 0) && Strategy.suesForPeace(0.35, 1) && !Strategy.suesForPeace(0.35, 0.5) && !Strategy.suesForPeace(0.6, 3),
                "Peace is sought when outmatched and bloodied, not when winning");
        check(Strategy.odds(2, 8) < Strategy.HOPELESS && Strategy.odds(2, 2) >= Strategy.HOPELESS, "Hopeless raids recognised");
        check(Strategy.musterNeeded(0) == 3 && Strategy.musterNeeded(1.5) == 6 && Strategy.musterNeeded(9) == 9, "A beaten kingdom needs more soldiers before marching");
        var vet = new Settlement.Soldier(); vet.level = 10; vet.health = 20; vet.maxHealth = 20;
        var hurt = new Settlement.Soldier(); hurt.health = 5; hurt.maxHealth = 20;
        check(Math.abs(Strategy.power(vet) - 1.5) < 1e-9 && Strategy.power(hurt) == 0.25 && Strategy.power(new Settlement.Soldier()) == 1, "Veterans count more, the wounded less");

        var m = new SettlementManager();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var raid = new Raids.Raid(); raid.attacker = a; raid.defender = b; raid.source = UUID.randomUUID(); raid.target = UUID.randomUUID();
        Strategy.raidStarted(m, raid, 1000);
        Strategy.raidEnded(m, raid, 1000);
        check(Strategy.defeats(m, a, 1000) == 1.5 && m.memory.get(b).victories == 1 && m.memory.get(a).repelledBy.get(b) == 1000, "A destroyed expedition is remembered as a heavy defeat");
        check(Math.abs(Strategy.defeats(m, a, 1000 + Strategy.DAY) - 0.75) < 1e-9, "Defeats fade by half a day");
        var town = new Settlement(); town.kingdom = b;
        check(Strategy.threatened(m, town, 2000) && Strategy.guardGoal(m, town, 5, 2000) == 7 && Strategy.guardGoal(m, town, 12, 2000) == 18,
                "An attacked town goes on a war footing");
        check(!Strategy.threatened(m, town, 1000 + Strategy.FOOTING + 1) && Strategy.guardGoal(m, town, 5, 1000 + Strategy.FOOTING + 1) == 5, "The war footing ends after two days");
        var back = SettlementManager.load(m.save(new CompoundTag()));
        check(back.memory.size() == 2 && Strategy.defeats(back, a, 1000) == 1.5 && back.memory.get(b).attacked == 1000, "War memory survives reload");
        check(!new SettlementManager().save(new CompoundTag()).contains("warMemory"), "No war memory, no save change");

        var ai = new Settlement(); ai.tier = Tier.HAMLET; ai.population = 6; ai.d.buildings = 5;
        check("carpentry".equals(Strategy.aiResearch(ai)), "NPC town researches towards its next rank: " + Strategy.aiResearch(ai));
        ai.research.add("carpentry");
        check("masonry".equals(Strategy.aiResearch(ai)), "NPC town follows the rank's research chain: " + Strategy.aiResearch(ai));
    }

    private static void physical(ServerLevel level, net.minecraft.server.level.ServerPlayer owner, net.minecraft.server.level.ServerPlayer stranger) {
        var server = level.getServer(); var m = SettlementManager.get(server);
        List<Settlement> towns = new ArrayList<>(); List<Entity> entities = new ArrayList<>();
        try {
            Settlement source = town(m, null, "NPC Raiders", 2048); towns.add(source);
            Settlement target = town(m, owner.getUUID(), "Player Capital", 2208); towns.add(target);
            Settlement province = town(m, owner.getUUID(), "Player Province", 2368); towns.add(province);
            for (Settlement s : towns) level.getChunkAt(s.center);
            check(!livingrealms.net.View.of(source, owner).getBoolean("ruler")
                    && livingrealms.net.View.of(target, owner).getBoolean("ruler"), "War controls distinguish ruler from administrator access");
            for (int x = 2045; x <= 2212; x++) for (int z = 2045; z <= 2051; z++) {
                level.setBlock(new BlockPos(x, 99, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
                for (int y = 100; y < 104; y++) level.setBlock(new BlockPos(x,y,z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            }
            for (int i = 0; i < 4; i++) entities.add(soldier(level, source, source.center.offset(i,0,0)));
            Entity defender = soldier(level, target, target.center); entities.add(defender);
            Entity survivor = soldier(level, province, province.center); entities.add(survivor);
            province.soldiers.get(0).royalGuard = true;
            // A nearer NPC fortress is too strong for a two-soldier expedition: the weaker, farther player capital is chosen.
            Settlement fortress = town(m, null, "NPC Fortress", 1900); towns.add(fortress); level.getChunkAt(fortress.center);
            for (int i = 0; i < 8; i++) { var g = new Settlement.Soldier(); g.entity = UUID.randomUUID(); g.type = UnitType.FOOTMAN; g.name = "Fortress guard"; fortress.soldiers.add(g); }
            check(Raids.canStart(server, m, source, fortress) == null, "Fortress is a legal target: " + Raids.canStart(server, m, source, fortress));
            online(server, owner, true);
            check(Raids.npcTarget(server,m,source) == target, "NPC target selection includes an online player's capital, past a nearer fortress");
            online(server, owner, false);
            check(Raids.npcTarget(server,m,source) == null, "NPC does not initiate raids against offline players, nor hopeless ones");
            fortress.soldiers.subList(1, 8).clear();
            check(Raids.npcTarget(server,m,source) == fortress, "A weakly held NPC town is raided");
            m.remove(fortress); towns.remove(fortress);
            check(Raids.playerStart(stranger,m,target) != null && m.raids.isEmpty(), "Stranger cannot dispatch another kingdom's troops");
            source.founded = -48000;
            online(server, owner, true);
            m.nextRaidCheck = 0;
            Raids.tick(server,m);
            check(m.raids.size() == 1, "Autonomous NPC scheduler actually starts player raid");
            var raid = m.raids.get(0);
            check(raid.soldiers.size() == 2 && source.soldiers.size() == 4, "Raid dispatches existing half-garrison; no free troops");
            check(target.capital && target.owner.equals(owner.getUUID()), "Starting a raid does not capture anything");
            check(Raids.start(server,m,source,target) != null, "Overlapping raid rejected");
            province.capitalMovedAt = 0;
            check(Kingdoms.designate(server,m,province) != null, "Cannot evade capital raid by relocating capital");
            for (UUID id : raid.soldiers) level.getEntity(id).teleportTo(target.center.getX()+2,100,target.center.getZ());
            Raids.tickRaid(server,m,raid);
            Entity attacker = level.getEntity(raid.soldiers.iterator().next());
            check(((Mob) attacker).getTarget() == defender && ((Mob) defender).getTarget() != null, "NPC attackers and town guards receive real mutual combat targets");
            float hp = ((LivingEntity) defender).getHealth();
            ((Mob) attacker).doHurtTarget(defender);
            check(((LivingEntity) defender).getHealth() < hp, "Raid recruit deals real damage to defender");
            check(raid.held == 0, "Live defender blocks occupation");
            var unknown = new Settlement.Soldier(); unknown.entity=UUID.randomUUID(); unknown.type=UnitType.FOOTMAN; unknown.name="Unloaded guard";
            target.soldiers.add(unknown);
            int before = raid.elapsed;
            Raids.tickRaid(server,m,raid);
            check(raid.elapsed == before && target.raidStatus.contains("unloaded"), "Unknown/unloaded defenders pause raid rather than lose city");
            target.soldiers.remove(unknown);
            ((LivingEntity) defender).hurt(level.damageSources().genericKill(), 10000);
            check(target.soldiers.isEmpty(), "Real soldier death removes defending record");
            for (int i=0;i<30;i++) Raids.tickRaid(server,m,raid);
            check(raid.held == 600 && target.owner.equals(owner.getUUID()), "No capture before 60 second occupation");
            var restored = SettlementManager.load(m.save(new CompoundTag()));
            check(restored.raids.size()==1 && restored.raids.get(0).held==600 && restored.raids.get(0).soldiers.equals(raid.soldiers), "Raid progress and troop identity survive save/reload");
            for (int i=0;i<30;i++) Raids.tickRaid(server,m,raid);
            check(m.raids.isEmpty() && target.owner == null && province.owner == null && Kingdoms.same(source, province), "Physical NPC victory captures player's capital and all cities");
            check(source.capital && !target.capital && !province.capital, "Captured capital demoted; winner keeps one seat");
            check(RecruitsBridge.owner(survivor).equals(source.kingdomId()) && !province.soldiers.get(0).royalGuard,
                    "Surviving provincial guard changes owner and loses personal escort status");
            check(target.raidCooldown > server.overworld().getGameTime(), "Post-raid recovery persisted");
            check(source.soldiers.size()==4, "No attacker troops duplicated or consumed by capture");
            // Loss: a second independent target; killing every deployed attacker must end without transfer.
            Settlement enemy = town(m, stranger.getUUID(), "Second enemy", 2528); towns.add(enemy); level.getChunkAt(enemy.center);
            for (Settlement s : Kingdoms.cities(m,source)) s.raidCooldown=0;
            for (Entity e : entities) if (e.isAlive() && RecruitsBridge.owner(e).equals(source.kingdomId())) e.teleportTo(2048,100,2048);
            check(Raids.start(server,m,source,enemy)==null,"New raid can start after cooldown");
            var loss = m.raids.get(0);
            for (UUID id : List.copyOf(loss.soldiers)) ((LivingEntity)level.getEntity(id)).hurt(level.damageSources().genericKill(),10000);
            Raids.tickRaid(server,m,loss);
            check(m.raids.isEmpty() && enemy.owner.equals(stranger.getUUID()), "Defeated NPC army does not conquer target");
            // Regroup: the beaten kingdom does not march with a token force.
            long now = server.overworld().getGameTime();
            check(Strategy.defeats(m, source.kingdomId(), now) >= 1.4 && m.memory.get(source.kingdomId()).repelledBy.containsKey(enemy.kingdomId()), "Defeat remembered");
            check(Strategy.threatened(m, enemy, now), "Raided town is on a war footing");
            for (int i = 0; i < 2; i++) entities.add(soldier(level, source, source.center.offset(i, 0, 1)));
            for (Settlement s : Kingdoms.cities(m,source)) s.raidCooldown=0;
            enemy.raidCooldown = 0;
            online(server, stranger, true);
            int ready = Raids.available(level, source).size();
            check(ready >= 3 && ready < Strategy.musterNeeded(Strategy.defeats(m, source.kingdomId(), now)) && Raids.npcTarget(server, m, source) == enemy, "Enough for a raid, not enough to regroup: " + ready);
            m.nextRaidCheck = 0;
            Raids.tick(server, m);
            check(m.raids.isEmpty(), "Beaten NPC kingdom regroups instead of marching again");
            m.memory.remove(source.kingdomId());
            m.nextRaidCheck = 0;
            Raids.tick(server, m);
            check(m.raids.size() == 1 && m.raids.get(0).target.equals(enemy.id), "Once recovered it marches");
        } finally {
            online(server, owner, false);
            online(server, stranger, false);
            m.raids.removeIf(r -> towns.stream().anyMatch(t -> t.id.equals(r.source) || t.id.equals(r.target)));
            entities.forEach(Entity::discard); towns.forEach(m::remove);
        }
    }

    private static void online(net.minecraft.server.MinecraftServer server, net.minecraft.server.level.ServerPlayer player, boolean present) {
        Map<UUID, net.minecraft.server.level.ServerPlayer> players = net.minecraftforge.fml.util.ObfuscationReflectionHelper.getPrivateValue(
                net.minecraft.server.players.PlayerList.class, server.getPlayerList(), "f_11197_");
        if (players == null) throw new IllegalStateException("Test player lookup unavailable");
        if (present) players.put(player.getUUID(), player); else players.remove(player.getUUID());
    }

    private static Entity soldier(ServerLevel level, Settlement s, BlockPos at) {
        Entity e = RecruitsBridge.spawn(level, UnitType.FOOTMAN, at, s.kingdomId(), "War test soldier");
        if (e == null) throw new IllegalStateException("Recruit spawn failed");
        RecruitsBridge.setSlot(e, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        var so = new Settlement.Soldier(); so.entity=e.getUUID(); so.type=UnitType.FOOTMAN; so.name="War test soldier";
        s.soldiers.add(so); return e;
    }
}
