package livingrealms.supply;

import com.mojang.authlib.GameProfile;
import livingrealms.LivingRealms;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** {@code ./gradlew runServer -PsupplyTest -PsupplyTestDir=<fresh dir>}: auto-resupply, written to supply-test-result.txt. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class SupplySelfTest {
    private static int checks;

    private static void check(boolean value, String detail) {
        if (!value) throw new IllegalStateException(detail);
        checks++;
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.supplyTest")) return;
        event.getServer().execute(() -> {
            String report;
            try {
                run(event.getServer().overworld());
                report = "PASS: " + checks + " supply assertions\n";
                LivingRealms.LOG.info("SUPPLY SELF TEST {}", report);
            } catch (Throwable error) {
                report = "FAIL after " + checks + " assertions: " + error + "\n";
                LivingRealms.LOG.error("SUPPLY SELF TEST FAILED", error);
            }
            try { Files.writeString(Path.of("supply-test-result.txt"), report); } catch (Exception e) { LivingRealms.LOG.error("Cannot write test result", e); }
            event.getServer().halt(false);
        });
    }

    static int pack(Entity e, Item item) {
        Container inv = RecruitsBridge.inventory(e);
        int n = 0;
        for (int i = 6; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
        return n;
    }

    static void emptyPack(Entity e) {
        Container inv = RecruitsBridge.inventory(e);
        for (int i = 6; i < inv.getContainerSize(); i++) inv.setItem(i, ItemStack.EMPTY);
    }

    static int owned(ServerPlayer p, Item item) {
        int n = 0;
        for (ItemStack st : p.getInventory().items) if (st.is(item)) n += st.getCount();
        return n;
    }

    static ItemStack worn(Item item) {
        ItemStack st = new ItemStack(item);
        st.setDamageValue((int) (st.getMaxDamage() * 0.8));
        return st;
    }

    static void run(ServerLevel level) {
        var server = level.getServer();
        SupplyData data = SupplyData.get(server);
        ServerPlayer owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "SupplyOwner"));
        BlockPos spawn = level.getSharedSpawnPos();
        level.getChunkAt(spawn);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
        Settlement s = new Settlement();
        s.name = "Supply test"; s.owner = owner.getUUID(); s.ownerName = "SupplyOwner"; s.dim = level.dimension(); s.center = new BlockPos(spawn.getX(), y, spawn.getZ());
        SettlementManager manager = SettlementManager.get(server);
        manager.add(s);
        // a second spot well outside the town for the recruits that have no town
        BlockPos field = s.center.offset(s.radius() + 80, 0, 0);
        level.getChunkAt(field);
        field = new BlockPos(field.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, field.getX(), field.getZ()), field.getZ());
        List<Entity> spawned = new ArrayList<>();
        long now = level.getGameTime();
        try {
            // scheduling: each recruit once per PERIOD, spread over the ticks
            UUID probe = UUID.randomUUID();
            int hits = 0;
            for (long t = 1000; t < 1000 + Resupply.PERIOD; t++) if (Resupply.due(probe, t)) hits++;
            check(hits == 1, "Checked exactly once per period, got " + hits);
            Set<Integer> buckets = new HashSet<>();
            for (int i = 0; i < 400; i++) buckets.add(Resupply.bucket(UUID.randomUUID()));
            check(buckets.size() > Resupply.PERIOD / 2, "Checks are staggered across the period (" + buckets.size() + " buckets used)");

            // 1. the town stockpile: arrows to 64, plain food to 8, nothing with effects
            Entity bow = enlisted(level, s, UnitType.BOWMAN, s.center, "Aldric", spawned);
            RecruitsBridge.setSlot(bow, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            s.stock.force(Items.ARROW, 100); s.stock.force(Items.BREAD, 20); s.stock.force(Items.ROTTEN_FLESH, 10);
            check(Resupply.sources(bow, null).size() == 1 && Resupply.sources(bow, null).get(0) instanceof Sources.Stockpile, "Town recruit draws on its armoury");
            check(Resupply.check(bow, null, now), "Stockpile resupply acted");
            check(pack(bow, Items.ARROW) == Resupply.AMMO_TARGET && s.stock.count(Items.ARROW) == 100 - Resupply.AMMO_TARGET, "Arrows topped up to 64 from the stockpile: " + pack(bow, Items.ARROW));
            check(pack(bow, Items.BREAD) == Resupply.FOOD_TARGET && s.stock.count(Items.BREAD) == 20 - Resupply.FOOD_TARGET, "Bread topped up to 8 from the stockpile");
            check(pack(bow, Items.ROTTEN_FLESH) == 0 && s.stock.count(Items.ROTTEN_FLESH) == 10, "Food with effects is never taken");
            check(!Resupply.check(bow, null, now + 1) && s.stock.count(Items.ARROW) == 36, "A full recruit takes nothing more");
            // only below the refill mark
            Container inv = RecruitsBridge.inventory(bow);
            for (int i = 6; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.ARROW)) inv.getItem(i).setCount(50);
            check(!Resupply.check(bow, null, now + 2) && pack(bow, Items.ARROW) == 50, "50 arrows is above the refill mark");
            for (int i = 6; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.ARROW)) inv.getItem(i).setCount(10);
            s.stock.force(Items.ARROW, 100);
            check(Resupply.check(bow, null, now + 3) && pack(bow, Items.ARROW) == 64 && s.stock.count(Items.ARROW) == 136 - 54, "10 arrows refilled to 64");
            CompoundTag view = Resupply.describe(owner, bow);
            check(view.getBoolean("ranged") && view.getInt("ammo") == 64 && view.getInt("food") == 8 && view.getString("ammoName").equals("arrows") && view.getString("note").contains("armoury"), "Recruit Command supply view");

            // 2. toggle off: nothing is taken
            emptyPack(bow);
            s.stock.force(Items.ARROW, 64);
            int arrows = s.stock.count(Items.ARROW), bread = s.stock.count(Items.BREAD);
            data.setAuto(bow.getUUID(), false);
            check(!Resupply.check(bow, null, now + 4), "Auto resupply off: no action");
            check(pack(bow, Items.ARROW) == 0 && pack(bow, Items.BREAD) == 0 && s.stock.count(Items.ARROW) == arrows && s.stock.count(Items.BREAD) == bread, "Auto resupply off: nothing taken from the stockpile");
            check(!Resupply.describe(owner, bow).getBoolean("auto"), "Toggle shows off");
            data.setAuto(bow.getUUID(), true);

            // 3. never in combat
            Mob zombie = EntityType.ZOMBIE.create(level);
            ((Mob) bow).setTarget(zombie);
            check(!Resupply.calm(bow) && !Resupply.check(bow, null, now + 5) && pack(bow, Items.ARROW) == 0, "No resupply while it has a target");
            ((Mob) bow).setTarget(null);
            check(Resupply.calm(bow) && Resupply.check(bow, null, now + 6) && pack(bow, Items.ARROW) == 64, "Resupplies once the fight is over");

            // 4. owner's inventory, behind the per-player toggle and the 16 block range
            Entity stray = recruit(level, UnitType.BOWMAN, field, owner.getUUID(), "Brannoc", spawned);
            RecruitsBridge.setSlot(stray, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            owner.moveTo(field.getX() + 4.5, field.getY(), field.getZ() + 0.5);
            owner.getInventory().clearContent();
            owner.getInventory().selected = 0;
            owner.getInventory().setItem(0, new ItemStack(Items.ARROW, 16));
            owner.getInventory().setItem(9, new ItemStack(Items.ARROW, 40));
            owner.getInventory().setItem(10, new ItemStack(Items.COOKED_BEEF, 10));
            data.setAllowsInventory(owner.getUUID(), false);
            Resupply.LAST_WARN.clear();
            check(Resupply.sources(stray, owner).isEmpty(), "Owner's inventory is no source while not allowed");
            Resupply.check(stray, owner, now + 7);
            check(pack(stray, Items.ARROW) == 0 && owned(owner, Items.ARROW) == 56 && owned(owner, Items.COOKED_BEEF) == 10, "Toggle off: nothing taken from the owner");
            data.setAllowsInventory(owner.getUUID(), true);
            check(Resupply.check(stray, owner, now + 8), "Owner inventory resupply acted");
            check(pack(stray, Items.ARROW) == 40 && owner.getInventory().getItem(0).getCount() == 16 && owned(owner, Items.ARROW) == 16, "Took the 40 pack arrows, never the held stack");
            check(pack(stray, Items.COOKED_BEEF) == 8 && owned(owner, Items.COOKED_BEEF) == 2, "Took 8 steak from the owner");
            emptyPack(stray);
            owner.getInventory().setItem(9, new ItemStack(Items.ARROW, 40));
            owner.moveTo(field.getX() + 30.5, field.getY(), field.getZ() + 0.5);
            check(Resupply.sources(stray, owner).isEmpty(), "Owner 30 blocks away is out of reach");
            Resupply.check(stray, owner, now + 9);
            check(pack(stray, Items.ARROW) == 0 && owned(owner, Items.ARROW) == 56, "Nothing taken from an owner out of range");
            owner.moveTo(field.getX() + 4.5, field.getY(), field.getZ() + 0.5);

            // 5. Recruits' upkeep chest
            Entity camp = recruit(level, UnitType.CROSSBOWMAN, field.offset(0, 0, 6), owner.getUUID(), "Cerdic", spawned);
            RecruitsBridge.setSlot(camp, EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            BlockPos chestAt = field.offset(2, 0, 8);
            level.setBlockAndUpdate(chestAt, Blocks.CHEST.defaultBlockState());
            Container chest = (Container) level.getBlockEntity(chestAt);
            chest.setItem(0, new ItemStack(Items.ARROW, 30));
            chest.setItem(1, new ItemStack(Items.BREAD, 5));
            chest.setItem(2, new ItemStack(Items.IRON_HELMET));
            RecruitsBridge.setUpkeepPos(camp, chestAt);
            data.setAllowsInventory(owner.getUUID(), false);
            check(Resupply.check(camp, owner, now + 10) && pack(camp, Items.ARROW) == 30 && chest.getItem(0).isEmpty(), "Bolts from the upkeep chest");
            check(Resupply.describe(owner, camp).getString("ammoName").equals("bolts"), "Crossbowmen carry bolts");
            check(pack(camp, Items.BREAD) == 5, "Food from the upkeep chest");
            RecruitsBridge.setSlot(camp, EquipmentSlot.HEAD, worn(Items.IRON_HELMET));
            check(Resupply.check(camp, owner, now + 11) && RecruitsBridge.getSlot(camp, EquipmentSlot.HEAD).is(Items.IRON_HELMET)
                    && !RecruitsBridge.getSlot(camp, EquipmentSlot.HEAD).isDamaged(), "Worn helmet swapped for the chest's spare");
            boolean wornInChest = false;
            for (int i = 0; i < chest.getContainerSize(); i++) wornInChest |= chest.getItem(i).is(Items.IRON_HELMET) && chest.getItem(i).isDamaged();
            check(wornInChest && pack(camp, Items.IRON_HELMET) == 0, "The worn helmet goes back in the chest");
            level.setBlockAndUpdate(chestAt, Blocks.AIR.defaultBlockState());

            // 6. gear: swap only for the same or better tier, repair at the smithy, replace what broke
            Entity foot = enlisted(level, s, UnitType.FOOTMAN, s.center.offset(2, 0, 0), "Dunstan", spawned);
            RecruitsBridge.setSlot(foot, EquipmentSlot.MAINHAND, worn(Items.IRON_SWORD));
            s.stock.force(Items.STONE_SWORD, 1);
            s.stock.force(Items.BREAD, 20);
            Resupply.check(foot, null, now + 12);
            check(RecruitsBridge.getSlot(foot, EquipmentSlot.MAINHAND).isDamaged() && s.stock.count(Items.STONE_SWORD) == 1, "No swap for a worse sword");
            check(data.peek(foot.getUUID()).shortages.contains("a spare Iron Sword"), "Short of a spare sword: " + data.peek(foot.getUUID()).shortages);
            check(Resupply.townWarning(server, s).contains("1 soldier is low on supplies"), "Garrison card warning: " + Resupply.townWarning(server, s));
            s.stock.force(Items.IRON_SWORD, 1);
            check(Resupply.check(foot, null, now + 13), "Swap acted");
            ItemStack main = RecruitsBridge.getSlot(foot, EquipmentSlot.MAINHAND);
            check(main.is(Items.IRON_SWORD) && !main.isDamaged() && s.stock.count(Items.IRON_SWORD) == 0 && s.stock.count(Items.STONE_SWORD) == 1, "Worn sword swapped for a fresh one");
            check(pack(foot, Items.IRON_SWORD) == 1, "The worn sword goes in the pack");
            check(Resupply.townWarning(server, s).isEmpty(), "Warning clears once supplied");
            RecruitsBridge.setSlot(foot, EquipmentSlot.HEAD, worn(Items.IRON_HELMET));
            s.stock.force(Items.DIAMOND_HELMET, 1);
            Resupply.check(foot, null, now + 14);
            check(RecruitsBridge.getSlot(foot, EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "Swapped up to a better tier");
            // repair: needs a standing smithy and the repair material
            ItemStack chest2 = worn(Items.IRON_CHESTPLATE);
            int cost = Sources.Stockpile.repairCost(chest2);
            RecruitsBridge.setSlot(foot, EquipmentSlot.CHEST, chest2);
            s.stock.force(Items.IRON_INGOT, 10);
            Resupply.check(foot, null, now + 15);
            check(RecruitsBridge.getSlot(foot, EquipmentSlot.CHEST).isDamaged() && s.stock.count(Items.IRON_INGOT) == 10, "No smithy, no repair");
            Plot smithy = new Plot(); smithy.id = s.nextId++; smithy.typeId = "livingrealms:smithy"; smithy.state = Plot.State.BUILT; smithy.origin = s.center; smithy.blueprintKey = "";
            s.plots.add(smithy);
            check(Resupply.check(foot, null, now + 16), "Repair acted");
            check(!RecruitsBridge.getSlot(foot, EquipmentSlot.CHEST).isDamaged() && s.stock.count(Items.IRON_INGOT) == 10 - cost && cost > 0, "Smiths mended the chestplate for " + cost + " iron ingots");
            // broken weapon and shield
            RecruitsBridge.setSlot(foot, EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            s.stock.force(Items.IRON_SWORD, 1);
            check(Resupply.check(foot, null, now + 17) && RecruitsBridge.getSlot(foot, EquipmentSlot.MAINHAND).is(Items.IRON_SWORD) && s.stock.count(Items.IRON_SWORD) == 0, "Broken sword replaced in kind");
            Entity guard = enlisted(level, s, UnitType.SHIELDBEARER, s.center.offset(-2, 0, 0), "Edric", spawned);
            RecruitsBridge.setSlot(guard, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            RecruitsBridge.setSlot(guard, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            Resupply.check(guard, null, now + 18);
            RecruitsBridge.setSlot(guard, EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            RecruitsBridge.setSlot(guard, EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            s.stock.force(Items.SHIELD, 1); s.stock.force(Items.STONE_AXE, 1); s.stock.force(Items.DIAMOND_SWORD, 1);
            check(Resupply.check(guard, null, now + 19) && RecruitsBridge.getSlot(guard, EquipmentSlot.OFFHAND).is(Items.SHIELD), "Broken shield replaced");
            check(RecruitsBridge.getSlot(guard, EquipmentSlot.MAINHAND).is(Items.STONE_AXE) && s.stock.count(Items.DIAMOND_SWORD) == 1, "A broken axe is replaced by an axe, not a sword");

            // 7. out of stock: one warning per owner per few minutes
            Entity dry = enlisted(level, s, UnitType.BOWMAN, s.center.offset(0, 0, 2), "Aldwin", spawned);
            RecruitsBridge.setSlot(dry, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            s.stock.takeUpTo(Items.ARROW, 10000);
            owner.moveTo(s.center.getX() + 0.5, s.center.getY(), s.center.getZ() + 3.5);
            Resupply.LAST_WARN.clear();
            Resupply.warnings = 0;
            Resupply.check(dry, owner, now + 100);
            check(Resupply.warnings == 1 && Resupply.lastWarning.equals("Bowman Aldwin is out of arrows and the armoury has none"), "Out of stock warning: " + Resupply.lastWarning);
            Resupply.check(dry, owner, now + 200);
            Resupply.check(bow, owner, now + 300);
            emptyPack(bow);
            Resupply.check(bow, owner, now + 400);
            check(Resupply.warnings == 1, "Warnings throttled per owner: " + Resupply.warnings);
            Resupply.check(dry, owner, now + 100 + Resupply.WARN_TICKS + 1);
            check(Resupply.warnings == 2, "Warned again after the quiet period");
            Resupply.check(dry, null, now + 100 + 3 * Resupply.WARN_TICKS);
            check(Resupply.warnings == 2, "No warning when the owner is offline");
            data.setAuto(dry.getUUID(), false);
            Resupply.check(dry, owner, now + 100 + 5 * Resupply.WARN_TICKS);
            check(Resupply.warnings == 2, "No warning while auto resupply is off");
            data.setAllowsInventory(owner.getUUID(), true);
            owner.getInventory().clearContent();
            Resupply.LAST_WARN.clear();
            Resupply.check(bow, owner, now + 100 + 7 * Resupply.WARN_TICKS);
            check(Resupply.lastWarning.contains("neither the armoury nor your inventory has any"), "Warning names every source: " + Resupply.lastWarning);

            // NPC towns' guards are left to their town
            Settlement npc = new Settlement();
            npc.name = "Supply NPC"; npc.dim = level.dimension(); npc.center = field.offset(0, 0, -20);
            manager.add(npc);
            Entity npcGuard = enlisted(level, npc, UnitType.BOWMAN, npc.center, "Hrolf", spawned);
            RecruitsBridge.setSlot(npcGuard, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            npc.stock.force(Items.ARROW, 64);
            check(!Resupply.check(npcGuard, null, now + 9000) && npc.stock.count(Items.ARROW) == 64 && pack(npcGuard, Items.ARROW) == 0, "NPC town soldiers are not resupplied");
            manager.remove(npc);

            // 8. settings live in their own saved data, by recruit UUID, and survive the town
            CompoundTag saved = data.save(new CompoundTag());
            SupplyData back = SupplyData.load(saved);
            check(!back.auto(dry.getUUID()) && back.auto(bow.getUUID()) && back.allowsInventory(owner.getUUID()), "Toggles saved");
            check(back.peek(guard.getUUID()).last[1].equals("minecraft:shield"), "Last-carried gear saved");
            manager.remove(s);
            check(!SupplyData.get(server).auto(dry.getUUID()), "Settings outlive the town");
        } finally {
            for (Entity e : spawned) e.discard();
            if (manager.all().contains(s)) manager.remove(s);
        }
    }

    static Entity recruit(ServerLevel level, UnitType u, BlockPos at, UUID owner, String name, List<Entity> spawned) {
        Entity e = RecruitsBridge.spawn(level, u, at, owner, name);
        check(e != null, "Spawned " + u);
        spawned.add(e);
        emptyPack(e);
        return e;
    }

    static Entity enlisted(ServerLevel level, Settlement s, UnitType u, BlockPos at, String name, List<Entity> spawned) {
        Entity e = recruit(level, u, at, s.owner, name, spawned);
        Settlement.Soldier so = new Settlement.Soldier();
        so.entity = e.getUUID(); so.type = u; so.name = name;
        s.soldiers.add(so);
        return e;
    }

    private SupplySelfTest() {}
}
