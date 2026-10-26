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

        physical(level, owner, other);
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
            online(server, owner, true);
            check(Raids.npcTarget(server,m,source) == target, "NPC target selection includes an online player's capital");
            online(server, owner, false);
            check(Raids.npcTarget(server,m,source) == null, "NPC does not initiate raids against offline players");
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
        } finally {
            online(server, owner, false);
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
