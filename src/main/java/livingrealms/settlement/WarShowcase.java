package livingrealms.settlement;

import livingrealms.LivingRealms;
import livingrealms.Registry;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.Construction;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import livingrealms.settlement.vx.VxBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Server half of the war walkthrough (client/WarTour, -PwarTour): a walled-off scene with your capital, a Village Expansion
 * faction to fight and a second one to watch. Everything after the setup goes through the ordinary war and raid code.
 */
public final class WarShowcase {
    public static BlockPos home, enemy, neighbour;
    public static String enemyFaction, neighbourFaction;

    public static Settlement town(MinecraftServer server) {
        return SettlementManager.get(server).all().stream().filter(s -> !s.isAi()).findFirst().orElse(null);
    }

    @Nullable
    public static Settlement village(MinecraftServer server, BlockPos at) {
        return SettlementManager.get(server).all().stream().filter(s -> s.center.getX() == at.getX() && s.center.getZ() == at.getZ()).findFirst().orElse(null);
    }

    static BlockPos ground(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return new BlockPos(x, Construction.ground(level, x, z) + 1, z);
    }

    public static void setup(ServerPlayer sp) {
        ServerLevel level = sp.serverLevel();
        MinecraftServer server = sp.server;
        level.setDayTime(3000);
        home = ground(level, sp.getBlockX(), sp.getBlockZ());
        level.setBlockAndUpdate(home, Registry.CHARTER.get().defaultBlockState());
        Registry.CHARTER.get().setPlacedBy(level, home, level.getBlockState(home), sp, new ItemStack(Registry.CHARTER_ITEM.get()));
        Settlement s = town(server);
        s.name = "Ashford";
        s.tier = Tier.CITY;
        s.population = 48;
        s.stock.capacity = 1_000_000;
        s.stock.force(Items.BREAD, 400);
        s.stock.force(Items.EMERALD, 200);
        Item trebuchet = BuiltInRegistries.ITEM.get(new ResourceLocation("siegemachines", "trebuchet"));
        if (trebuchet != Items.AIR) s.stock.force(trebuchet, 1);
        int x = home.getX(), z = home.getZ(), y = home.getY() - 1;
        raise(level, s, "livingrealms:town_hall", new BlockPos(x - 6, y, z + 4), Rotation.NONE);
        raise(level, s, "livingrealms:barracks", new BlockPos(x + 12, y, z + 4), Rotation.NONE);
        raise(level, s, "livingrealms:smithy", new BlockPos(x - 20, y, z + 4), Rotation.NONE);
        raise(level, s, "livingrealms:watchtower", new BlockPos(x + 30, y, z + 4), Rotation.NONE);
        raise(level, s, "livingrealms:cottage", new BlockPos(x - 6, y, z + 26), Rotation.CLOCKWISE_180);
        raise(level, s, "livingrealms:cottage", new BlockPos(x + 6, y, z + 26), Rotation.CLOCKWISE_180);
        raise(level, s, "livingrealms:storehouse", new BlockPos(x + 20, y, z + 26), Rotation.CLOCKWISE_180);
        Simulation.recompute(s);
        for (int i = 0; i < 14; i++) {
            UnitType u = i < 8 ? UnitType.FOOTMAN : i < 12 ? UnitType.BOWMAN : UnitType.SHIELDBEARER;
            soldier(level, s, u, home.offset(-7 + (i % 7) * 2, 0, -6 - (i / 7) * 2));
        }

        enemy = ground(level, x + 150, z + 30);
        neighbour = ground(level, x - 40, z - 210);
        place(server, "minecraft:village_plains", enemy);
        place(server, "minecraft:village_taiga", neighbour);
        enemy = ground(level, enemy.getX(), enemy.getZ());
        neighbour = ground(level, neighbour.getX(), neighbour.getZ());
        enemyFaction = VxBridge.testVillage(level, "village_" + enemy.getX() + "_" + enemy.getY() + "_" + enemy.getZ(), enemy);
        neighbourFaction = VxBridge.testVillage(level, "village_" + neighbour.getX() + "_" + neighbour.getY() + "_" + neighbour.getZ(), neighbour);
        garrison(level, enemyFaction, enemy, 7);
        garrison(level, neighbourFaction, neighbour, 4);
        Realms.sync(server, SettlementManager.get(server));
        Realms.realm(server, SettlementManager.get(server), s);
        s.dirty = true;
        LivingRealms.LOG.info("War tour: {} at {}, enemy {} at {}, neighbour {} at {}", s.name, home, enemyFaction, enemy, neighbourFaction, neighbour);
    }

    /** A building put up in one go for the scene: planned like any other, then every paid step placed. */
    static void raise(ServerLevel level, Settlement s, String type, BlockPos origin, Rotation rot) {
        var t = BuildingCatalog.get(type);
        Blueprint bp = t == null ? null : BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
        if (bp == null) return;
        bp.bill().forEach((item, n) -> s.stock.force(item, s.stock.count(item) + n));
        s.stock.force(Blueprint.costItem(Construction.foundationBlock(s)), 4000);
        String err = Planning.plan(level, s, t, origin, rot, false);
        if (err != null) { LivingRealms.LOG.warn("War tour: {} not planned: {}", type, err); return; }
        Plot p = s.plots.get(s.plots.size() - 1);
        p.deferred = true;
        Construction.site(level, s, p);
        p.paid = p.totalSteps();
        Construction.catchUp(level, s, p, Integer.MAX_VALUE);
        p.state = Plot.State.BUILT;
        p.deferred = false;
    }

    static void place(MinecraftServer server, String structure, BlockPos at) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4).withSuppressedOutput(),
                "place structure " + structure + " " + at.getX() + " " + at.getY() + " " + at.getZ());
    }

    public static Entity soldier(ServerLevel level, Settlement s, UnitType u, BlockPos at) {
        Entity e = RecruitsBridge.spawn(level, u, at, s.kingdomId(), s.name + " " + u.label);
        if (e == null) return null;
        RecruitsBridge.setSlot(e, EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        RecruitsBridge.setSlot(e, EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        RecruitsBridge.setSlot(e, EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        RecruitsBridge.setSlot(e, EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        RecruitsBridge.setSlot(e, EquipmentSlot.MAINHAND, new ItemStack(u == UnitType.BOWMAN ? Items.BOW : Items.IRON_SWORD));
        if (u == UnitType.SHIELDBEARER) RecruitsBridge.setSlot(e, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        if (u == UnitType.BOWMAN) RecruitsBridge.addToPack(e, new ItemStack(Items.ARROW, 64));
        Settlement.Soldier so = new Settlement.Soldier();
        so.entity = e.getUUID(); so.type = u; so.name = e.getName().getString();
        s.soldiers.add(so);
        return e;
    }

    /** The faction's own recruits, as Village Expansion keeps them: owned by its fake owner, on its team. */
    public static void garrison(ServerLevel level, String faction, BlockPos center, int n) {
        for (int i = 0; i < n; i++) {
            UnitType u = i % 3 == 2 ? UnitType.BOWMAN : UnitType.FOOTMAN;
            Entity e = RecruitsBridge.spawn(level, u, center.offset(-3 + i, 0, 3), null, "Village " + u.label);
            if (e == null) continue;
            RecruitsBridge.setSlot(e, EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
            RecruitsBridge.setSlot(e, EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
            RecruitsBridge.setSlot(e, EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            RecruitsBridge.setSlot(e, EquipmentSlot.MAINHAND, new ItemStack(u == UnitType.BOWMAN ? Items.BOW : Items.STONE_SWORD));
            if (u == UnitType.BOWMAN) RecruitsBridge.addToPack(e, new ItemStack(Items.ARROW, 64));
            VxBridge.surrender(level, e, faction, center);
        }
    }

    /** What Village Expansion's war roll does when it comes up: war, and word to every city of the realm. */
    public static void villageDeclares(MinecraftServer server) {
        var m = SettlementManager.get(server);
        Settlement s = town(server);
        String realm = Realms.realm(server, m, s);
        Realms.NEXT_WAR.clear();
        // Only the faction we fight in the tour rolls a war; the other keeps its peace.
        var rolls = new net.minecraft.world.level.levelgen.LegacyRandomSource(1) {
            @Override public double nextDouble() { return 0; }
        };
        Realms.NEXT_WAR.put(neighbourFaction, Long.MAX_VALUE);
        Realms.considerWars(server, m, rolls, false);
        LivingRealms.LOG.info("War tour: {} vs {} -> {}", enemyFaction, realm, VxBridge.relation(enemyFaction, realm));
    }

    public static String enemyRaid(MinecraftServer server) {
        var m = SettlementManager.get(server);
        Settlement v = village(server, enemy);
        if (v == null) return "no mirror";
        Realms.roster(server.overworld(), m, v);
        return Raids.start(server, m, v, town(server));
    }

    /** Fresh troops for the counter-attack and a fresh garrison for the village, then the Raid button. */
    public static void counterRaid(ServerPlayer sp) {
        MinecraftServer server = sp.server;
        ServerLevel level = sp.serverLevel();
        var m = SettlementManager.get(server);
        Settlement s = town(server), v = village(server, enemy);
        for (Settlement t : m.all()) t.raidCooldown = 0;
        while (s.soldiers.size() < 16) {
            int i = s.soldiers.size();
            soldier(level, s, i % 4 == 3 ? UnitType.BOWMAN : UnitType.FOOTMAN, home.offset(-7 + (i % 7) * 2, 0, -8));
        }
        garrison(level, enemyFaction, enemy, 5);
        Realms.roster(level, m, v);
        CompoundTag target = new CompoundTag();
        target.putUUID("village", v.id);
        Actions.handle(sp, s.id, "vxRaid", target);
    }

    @Nullable
    public static Raids.Raid raid(MinecraftServer server) {
        var raids = SettlementManager.get(server).raids;
        return raids.isEmpty() ? null : raids.get(0);
    }

    /** Where the marching army is: the middle of the raid's living soldiers. */
    @Nullable
    public static BlockPos army(MinecraftServer server) {
        Raids.Raid r = raid(server);
        if (r == null) return null;
        double x = 0, y = 0, z = 0; int n = 0;
        for (UUID id : r.soldiers) {
            Entity e = server.overworld().getEntity(id);
            if (e == null) continue;
            x += e.getX(); y += e.getY(); z += e.getZ(); n++;
        }
        return n == 0 ? null : BlockPos.containing(x / n, y / n, z / n);
    }

    public static String status(MinecraftServer server) {
        Settlement s = town(server);
        return s == null ? "" : s.raidStatus;
    }

    public static List<String> log(MinecraftServer server) {
        Settlement s = town(server);
        return s == null ? List.of() : s.log.stream().limit(8).toList();
    }

    private WarShowcase() {}
}
