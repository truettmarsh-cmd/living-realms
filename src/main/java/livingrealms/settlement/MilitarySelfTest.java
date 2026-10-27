package livingrealms.settlement;

import livingrealms.LivingRealms;
import livingrealms.military.*;
import livingrealms.research.ResearchTree;
import livingrealms.smithing.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;

/** Opt-in real-server integration assertions; never runs in ordinary worlds. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class MilitarySelfTest {
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.militaryTest")) return;
        event.getServer().execute(() -> {
            String report;
            try {
                Smithing.discover(event.getServer());
                int checks = SoldierProgressionSelfTest.run(event.getServer().overworld());
                checks += RecruitProgressionSelfTest.run(event.getServer().overworld());
                checks += SoldierRanksSelfTest.run(event.getServer().overworld());
                checks += livingrealms.military.RecruitOriginSelfTest.run(event.getServer().overworld());
                checks += CapitalCastleSelfTest.run(event.getServer().overworld());
                checks += equipment(event.getServer().overworld());
                checks += dragons(event.getServer().overworld());
                report = "PASS: " + checks + " assertions; " + Smithing.patterns().size() + " smithing patterns\n";
                LivingRealms.LOG.info("MILITARY SELF TEST {}", report);
            } catch (Throwable failure) {
                report = "FAIL: " + failure + "\n";
                LivingRealms.LOG.error("MILITARY SELF TEST FAILED", failure);
            }
            try { Files.writeString(Path.of("military-test-result.txt"), report); } catch (Exception ex) { LivingRealms.LOG.error("Cannot write test result", ex); }
            event.getServer().halt(false);
        });
    }

    private static int equipment(ServerLevel level) {
        int checks = 0;
        check(ResearchTree.get("dragon_riders") != null && ResearchTree.get("dragonsteel_forging") != null, "Research loaded"); checks++;
        var fresh = new net.minecraft.world.item.ItemStack(Items.IRON_SWORD);
        check(Stockpile.canStore(fresh), "Fresh weapons accepted by stockpile"); checks++;
        fresh.setDamageValue(1);
        check(!Stockpile.canStore(fresh), "Worn weapons cannot be laundered through stockpile"); checks++;
        fresh.setDamageValue(0); fresh.setHoverName(net.minecraft.network.chat.Component.literal("Keep me"));
        check(!Stockpile.canStore(fresh), "Custom gear remains in player inventory"); checks++;
        int original = Smithing.patterns().size();
        Smithing.discover(level.getServer());
        check(Smithing.patterns().size() == original, "Discovery idempotency"); checks++;
        for (var item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            if (!EquipmentDiscovery.namespaces().contains(id.getNamespace()) || EquipmentDiscovery.piece(item) == null) continue;
            var pattern = Smithing.get("equipment/" + id.getNamespace() + "/" + id.getPath());
            check(pattern != null, "Equipment coverage " + id); checks++;
        }
        for (String item : List.of("magistuarmory:steel_longsword", "magistuarmory:ceremonial_boots", "iceandfire:dragonsteel_fire_sword", "iceandfire:dragonbone_sword")) {
            var id = new ResourceLocation(item);
            if (!BuiltInRegistries.ITEM.containsKey(id)) continue;
            var pattern = Smithing.get("equipment/" + id.getNamespace() + "/" + id.getPath());
            var entry = pattern.pieces().entrySet().iterator().next();
            if (item.equals("magistuarmory:ceremonial_boots")) {
                check(entry.getValue().cost().containsKey("magistuarmory:gilding_template")
                        && entry.getValue().cost().containsKey("magistuarmory:knight_boots"), "Smithing template and base both charged"); checks++;
            }
            Settlement town = new Settlement();
            town.center = new BlockPos(0, 100, 0); town.dim = level.dimension(); town.population = 20;
            Plot smith = new Plot(); smith.typeId = "livingrealms:smithy"; smith.state = Plot.State.BUILT;
            smith.origin = town.center; smith.blueprintKey = ""; town.plots.add(smith);
            CompoundTag order = new CompoundTag(); order.putString("pattern", pattern.id()); order.putString("piece", entry.getKey().name()); order.putInt("qty", 2);
            for (var cost : entry.getValue().cost().entrySet()) town.stock.force(BuiltInRegistries.ITEM.get(new ResourceLocation(cost.getKey())), cost.getValue() * 2);
            int before = town.stock.total();
            check(Actions.order(town, order) != null && town.stock.total() == before, "Research gate " + item); checks++;
            town.research.add(entry.getValue().research());
            check(Actions.order(town, order) == null && town.stock.total() == 0, "Exact order charge " + item); checks++;
            var saved = ForgeOrder.load(town.orders.get(0).save());
            check(saved.pattern.equals(pattern.id()) && saved.quantity == 2, "Order persistence"); checks++;
            town.d.workers.put(Job.SMITH, 1); town.d.smithyLevels = 1;
            Simulation.forge(town, town.d, 5);
            check(town.orders.isEmpty() && town.stock.count(entry.getValue().item()) == 2 * entry.getValue().count(), "Forge completion " + item); checks++;
            town.stock.load(new net.minecraft.nbt.ListTag());
            check(Actions.order(town, order) != null && town.orders.isEmpty(), "Insufficient stock denied"); checks++;
        }
        return checks;
    }

    private static int dragons(ServerLevel level) throws Exception {
        UUID owner = UUID.randomUUID();
        BlockPos at = new BlockPos(0, 100, 0); level.getChunkAt(at);
        if (!DragonRiders.installed()) {
            check(RecruitsBridge.spawn(level, UnitType.DRAGON_RIDER, at, owner, "Missing mod") == null, "Missing optional dragon mod handled");
            return 1;
        }
        int checks = 0;
        var type = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation("iceandfire:fire_dragon"));
        Entity entity = type.create(level);
        check(entity instanceof TamableAnimal, "Dragon type available"); checks++;
        TamableAnimal dragon = (TamableAnimal)entity;
        dragon.moveTo(0.5, 100, 0.5); dragon.setTame(true); dragon.setOwnerUUID(owner);
        dragon.getClass().getMethod("setAgeInDays", int.class).invoke(dragon, 80);
        level.addFreshEntity(dragon);
        Entity rider = null;
        try {
            check(DragonRiders.eligible(dragon, owner), "Adult owned dragon eligible"); checks++;
            check(!DragonRiders.eligible(dragon, UUID.randomUUID()), "Cannot take another player's dragon"); checks++;
            rider = RecruitsBridge.spawn(level, UnitType.DRAGON_RIDER, at, owner, "Dragon test");
            check(rider != null && rider.getVehicle() == dragon, "Real recruit mounts real Ice and Fire dragon"); checks++;
            check(!DragonRiders.eligible(dragon, owner), "Occupied dragon excluded"); checks++;
            check(RecruitsBridge.spawn(level, UnitType.DRAGON_RIDER, at, owner, "Second rider") == null, "No double assignment"); checks++;
            rider.stopRiding();
            dragon.getClass().getMethod("setAgeInDays", int.class).invoke(dragon, 5);
            check(!DragonRiders.eligible(dragon, owner), "Baby dragon excluded"); checks++;
            dragon.getClass().getMethod("setAgeInDays", int.class).invoke(dragon, 80);
            dragon.getClass().getMethod("setModelDead", boolean.class).invoke(dragon, true);
            check(!DragonRiders.eligible(dragon, owner), "Dead dragon excluded"); checks++;
        } finally { if (rider != null) rider.discard(); dragon.discard(); }
        return checks;
    }
    private static void check(boolean ok, String label) { if (!ok) throw new IllegalStateException(label); }
    private MilitarySelfTest() {}
}
