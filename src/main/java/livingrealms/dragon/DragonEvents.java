package livingrealms.dragon;

import livingrealms.LivingRealms;
import livingrealms.settlement.SettlementManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Drives roosts, dragon flights, wild attacks and scorpions; scorpion bolt damage. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class DragonEvents {
    static long ticks;

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        var server = e.getServer();
        long t = ++ticks;
        SettlementManager m = SettlementManager.get(server);
        Flight.tick(server);
        if (t % Roosts.TICK == 0) Roosts.tick(server, m);
        DragonAttacks.tick(server, m, t);
        Scorpions.tick(server, m, t);
    }

    /** A dragon saved mid-flight gets its own wings back when it loads. */
    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !e.getEntity().getPersistentData().getBoolean(Flight.FLAG) || Flight.flying(e.getEntity())) return;
        Flight.release(e.getEntity());
    }

    @SubscribeEvent
    public static void attacked(LivingAttackEvent e) {
        if (Scorpions.friendly(e.getEntity(), e.getSource())) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void damaged(LivingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        if (victim.level().isClientSide) return;
        float dmg = Scorpions.damage(victim, e.getSource());
        if (dmg >= 0) e.setAmount(dmg);
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent e) {
        Flight.ACTIVE.clear();
        DragonAttacks.LAST.clear();
        Scorpions.LIVE.clear();
    }

    private DragonEvents() {}
}
