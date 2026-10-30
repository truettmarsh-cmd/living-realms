package livingrealms.hardship;

import livingrealms.LivingRealms;
import livingrealms.settlement.SettlementManager;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Drives town hardships from the server tick. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class HardshipEvents {
    private static long tick;

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tick++;
        try { Hardships.tick(e.getServer(), SettlementManager.get(e.getServer()), tick); }
        catch (RuntimeException ex) { LivingRealms.LOG.error("Town hardships failed", ex); }
    }

    private HardshipEvents() {}
}
