package livingrealms.chronicle;

import livingrealms.LivingRealms;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.fire.Fires;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Drives the chronicle and the fires from the server tick, and the few game events they listen to. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class ChronicleEvents {
    private static long tick;

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = e.getServer();
        SettlementManager m = SettlementManager.get(server);
        tick++;
        if (tick % 20 == 7) {
            long day = Chronicle.dayOf(server.overworld().getGameTime());
            for (Settlement s : m.all()) {
                try { Chronicle.tick(server, m, s, day); }
                catch (RuntimeException ex) { LivingRealms.LOG.error("Chronicle of {} failed", s.name, ex); }
            }
        }
        try { Fires.tick(server, m, tick); }
        catch (RuntimeException ex) { LivingRealms.LOG.error("Town fires failed", ex); }
    }

    /** Before ServerEvents forgets the soldier: remember the notable ones. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity().level() instanceof ServerLevel level)) return;
        var id = e.getEntity().getUUID();
        for (Settlement s : SettlementManager.get(level.getServer()).all())
            for (Settlement.Soldier so : s.soldiers) if (so.entity.equals(id)) { Chronicle.fallen(s, so); return; }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getEntity() instanceof LightningBolt bolt && e.getLevel() instanceof ServerLevel level && !e.loadedFromDisk())
            Fires.lightning(level, SettlementManager.get(level.getServer()), bolt.blockPosition());
    }

    /** A blank book used on the Town Hall comes back as the town's chronicle. */
    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer player) || !(e.getLevel() instanceof ServerLevel level)) return;
        ItemStack held = e.getItemStack();
        if (!held.is(Items.BOOK)) return;
        Settlement s = SettlementManager.get(level.getServer()).at(level.dimension(), e.getPos());
        if (s == null) return;
        for (Plot p : s.plots) {
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t == null || t.category != Category.CIVIC || !p.standing() || !p.box().isInside(e.getPos())) continue;
            if (!player.isCreative()) held.shrink(1);
            ItemStack book = ChronicleBook.make(s);
            if (!player.getInventory().add(book)) player.drop(book, false);
            player.displayClientMessage(Component.literal("The town scribe copies out the Chronicle of " + s.name).withStyle(ChatFormatting.GOLD), true);
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
    }

    private ChronicleEvents() {}
}
