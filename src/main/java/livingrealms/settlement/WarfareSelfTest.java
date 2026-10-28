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
        tactics(level, owner);
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
            check(BattleAI.hostile(attacker, defender) && SoldierSpecials.hostile((LivingEntity) attacker, defender)
                    && raid.soldiers.stream().noneMatch(id -> !id.equals(attacker.getUUID()) && BattleAI.hostile(attacker, level.getEntity(id))),
                    "Raid soldiers count the other side, and only the other side, as hostile for specials and perks");
            check(target.raidStatus.contains("holding the centre") && target.log.stream().anyMatch(l -> l.contains("close ranks")),
                    "Outnumbered defenders close ranks at the centre and the city hears of it");
            var hurt = (LivingEntity) raid.soldiers.stream().map(level::getEntity).filter(e -> e != attacker).findFirst().orElseThrow();
            hurt.setHealth(hurt.getMaxHealth() * .2f);
            Raids.tickRaid(server,m,raid);
            check(hurt.getPersistentData().getBoolean(BattleAI.FALLBACK) && ((Mob) hurt).getTarget() == null && RecruitsBridge.aggroState(hurt) == 3
                    && raid.soldiers.contains(hurt.getUUID()) && target.raidStatus.contains("1 falling back"),
                    "A badly wounded attacker falls back, passive, and still belongs to the raid");
            hurt.setHealth(hurt.getMaxHealth() * .5f);
            Raids.tickRaid(server,m,raid);
            check(hurt.getPersistentData().getBoolean(BattleAI.FALLBACK), "A wounded soldier keeps falling back until 60% health");
            hurt.setHealth(hurt.getMaxHealth());
            Raids.tickRaid(server,m,raid);
            check(!hurt.getPersistentData().getBoolean(BattleAI.FALLBACK) && ((Mob) hurt).getTarget() == defender && RecruitsBridge.aggroState(hurt) == 1,
                    "Healed, it rejoins the fight");
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
            check(!BattleAI.fighting(attacker), "A finished battle releases its soldiers");
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

    /** Battle AI rules on real recruits (docs/battle-ai.md). */
    private static void tactics(ServerLevel level, net.minecraft.server.level.ServerPlayer owner) {
        var server = level.getServer(); var m = SettlementManager.get(server);
        List<Entity> spawned = new ArrayList<>(); List<Settlement> towns = new ArrayList<>();
        try {
            BlockPos o = new BlockPos(4096, 100, 2048);
            for (int x = -40; x <= 40; x++) for (int z = -40; z <= 40; z++) {
                level.setBlock(o.offset(x, -1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y < 4; y++) level.setBlock(o.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            }
            UUID blue = UUID.randomUUID(), red = UUID.randomUUID();
            BlockPos rally = o.offset(-30, 0, 0);
            LivingEntity archer = spawn(level, spawned, UnitType.BOWMAN, o, blue, Items.BOW);
            LivingEntity close = spawn(level, spawned, UnitType.FOOTMAN, o.offset(3, 0, 0), red, Items.IRON_SWORD);
            var plan = BattleAI.plan(new BattleAI.Side(List.of(archer), List.of(close), o.offset(20, 0, 0), rally, false, null));
            var order = plan.of(archer);
            check(order.target() == close && order.stance() == BattleAI.Stance.SKIRMISH && dist(order.hold(), close) >= BattleAI.ARCHER_MIN,
                    "An archer engaged at close range steps back to bow range and keeps shooting");
            close.teleportTo(o.getX() + 15.5, o.getY(), o.getZ() + .5);
            order = BattleAI.plan(new BattleAI.Side(List.of(archer), List.of(close), o, rally, false, null)).of(archer);
            check(order.hold().equals(archer.blockPosition()), "An archer already at bow range holds its ground");

            LivingEntity shield = spawn(level, spawned, UnitType.SHIELDBEARER, o.offset(0, 0, 10), blue, Items.IRON_SWORD);
            LivingEntity foot = spawn(level, spawned, UnitType.FOOTMAN, o.offset(0, 0, 10), blue, Items.IRON_SWORD);
            close.teleportTo(o.getX() + 4.5, o.getY(), o.getZ() + .5);
            LivingEntity near = spawn(level, spawned, UnitType.FOOTMAN, o.offset(0, 0, 12), red, Items.IRON_SWORD);
            plan = BattleAI.plan(new BattleAI.Side(List.of(archer, shield), List.of(close, near), o, rally, false, null));
            check(plan.of(shield).target() == close && plan.of(shield).stance() == BattleAI.Stance.SCREEN,
                    "A shieldbearer goes for the enemy at the archers before a nearer one");
            plan = BattleAI.plan(new BattleAI.Side(List.of(archer, foot), List.of(close, near), o, rally, false, null));
            check(plan.of(foot).target() == near, "A footman takes the nearest enemy when no archer is in real danger");

            LivingEntity f2 = spawn(level, spawned, UnitType.FOOTMAN, o.offset(0, 0, 10), blue, Items.IRON_SWORD);
            LivingEntity f3 = spawn(level, spawned, UnitType.FOOTMAN, o.offset(0, 0, 10), blue, Items.IRON_SWORD);
            LivingEntity other = spawn(level, spawned, UnitType.FOOTMAN, o.offset(0, 0, 8), red, Items.IRON_SWORD);
            plan = BattleAI.plan(new BattleAI.Side(List.of(foot, f2, f3), List.of(near, other), o, rally, false, null));
            var finalPlan = plan;
            long onNear = List.of(foot, f2, f3).stream().filter(e -> finalPlan.of(e).target() == near).count();
            check(onNear >= 1 && onNear <= BattleAI.MELEE_PER_FOE, "Melee soldiers spread over the enemy line instead of piling on one foe");
            other.teleportTo(o.getX() + .5, o.getY(), o.getZ() + 15.5);
            other.setHealth(other.getMaxHealth() * .2f);
            order = BattleAI.plan(new BattleAI.Side(List.of(foot), List.of(near, other), o, rally, false, null)).of(foot);
            check(order.target() == other, "A soldier finishes a badly wounded enemy over a fresh one about as close");
            other.setHealth(other.getMaxHealth());

            other.teleportTo(o.getX() + .5, o.getY(), o.getZ() + 20.5);
            owner.moveTo(o.getX() + .5, o.getY(), o.getZ() + 24.5);
            order = BattleAI.plan(new BattleAI.Side(List.of(foot), List.of(near, other), o, rally, true, owner)).of(foot);
            check(order.target() == other, "Defenders go first for an enemy at their ruler");

            foot.setHealth(foot.getMaxHealth() * .2f);
            plan = BattleAI.plan(new BattleAI.Side(List.of(foot, f2, f3, shield), List.of(near), o, rally, false, null));
            check(plan.of(foot).stance() == BattleAI.Stance.FALL_BACK && plan.of(foot).target() == null && plan.of(foot).hold().equals(rally)
                    && plan.fallingBack() == 1, "A badly wounded soldier falls back to the rally point");
            check(BattleAI.plan(new BattleAI.Side(List.of(foot), List.of(near), o, rally, false, null)).of(foot).stance() != BattleAI.Stance.FALL_BACK,
                    "The last soldier standing makes a last stand");
            check(BattleAI.plan(new BattleAI.Side(List.of(foot, f2, f3, shield), List.of(), o, rally, false, null)).fallingBack() == 0,
                    "Nobody falls back with no enemy in sight");
            for (LivingEntity e : List.of(f2, f3, shield)) e.setHealth(e.getMaxHealth() * .2f);
            check(BattleAI.plan(new BattleAI.Side(List.of(foot, f2, f3, shield), List.of(near), o, rally, false, null)).fallingBack() == 2,
                    "At most half a side falls back at once");
            for (LivingEntity e : List.of(foot, f2, f3, shield)) e.setHealth(e.getMaxHealth());

            BlockPos centre = o.offset(0, 0, -20);
            LivingEntity guard = spawn(level, spawned, UnitType.FOOTMAN, centre, blue, Items.IRON_SWORD);
            plan = BattleAI.plan(new BattleAI.Side(List.of(guard), List.of(near, other, close), centre, centre, true, null));
            check(plan.regrouping() && plan.of(guard).stance() == BattleAI.Stance.REGROUP && plan.of(guard).target() == null
                    && dist(plan.of(guard).hold(), centre) <= BattleAI.REGROUP_RING + 1, "Outnumbered defenders hold the centre instead of charging out");
            close.teleportTo(centre.getX() + 4.5, centre.getY(), centre.getZ() + .5);
            plan = BattleAI.plan(new BattleAI.Side(List.of(guard), List.of(near, other, close), centre, centre, true, null));
            check(plan.of(guard).target() == close, "A regrouped defender still fights an enemy that reaches the centre");

            check(centre.equals(BattleAI.firePoint(centre, List.of(near), List.of(guard))), "Siege engines shell the centre while it is clear of attackers");
            check(BattleAI.firePoint(centre, List.of(close), List.of(guard)) == null, "Siege engines cease fire rather than hit their own troops");
            check(near.blockPosition().equals(BattleAI.firePoint(centre, List.of(close), List.of(guard, near))),
                    "Siege engines shift fire to defenders clear of the attackers");

            // A Royal Guard protecting its ruler counts raid soldiers as threats, not only monsters.
            Settlement home = town(m, owner.getUUID(), "Guard test", 4096); towns.add(home);
            Entity royal = soldier(level, home, o.offset(0, 0, 17)); spawned.add(royal);
            home.soldiers.get(0).royalGuard = true;
            online(server, owner, true);
            BattleAI.enlist("guard-test", List.of(other), List.of(royal, owner));
            check(SoldierProgression.escort(home, home.soldiers.get(0), royal) && ((Mob) royal).getTarget() == other,
                    "A Royal Guard engages an enemy soldier closing on its ruler");
            BattleAI.release("guard-test");
            check(!BattleAI.hostile(royal, other), "Released battles no longer make soldiers hostile");
        } finally {
            online(server, owner, false);
            spawned.forEach(Entity::discard); towns.forEach(m::remove);
        }
    }

    private static double dist(BlockPos p, Entity e) { return Math.sqrt(e.distanceToSqr(p.getX() + .5, p.getY(), p.getZ() + .5)); }
    private static double dist(BlockPos a, BlockPos b) { return Math.sqrt(a.distSqr(b)); }

    private static LivingEntity spawn(ServerLevel level, List<Entity> spawned, UnitType type, BlockPos at, UUID owner, Item weapon) {
        Entity e = RecruitsBridge.spawn(level, type, at, owner, "Tactics test " + type);
        if (!(e instanceof LivingEntity l)) throw new IllegalStateException("Recruit spawn failed: " + type);
        spawned.add(e);
        RecruitsBridge.setSlot(e, EquipmentSlot.MAINHAND, new ItemStack(weapon));
        if (type == UnitType.SHIELDBEARER) RecruitsBridge.setSlot(e, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        return l;
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
