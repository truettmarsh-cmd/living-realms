package livingrealms.quest;

import livingrealms.LivingRealms;
import livingrealms.military.SoldierProgression;
import livingrealms.settlement.SettlementManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/** Loads the quest line, polls each online player's progress and patrols, counts kills for quests and bounties. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class QuestEvents {
    private static long tick;

    @SubscribeEvent
    public static void reload(AddReloadListenerEvent e) { e.addListener(QuestBook.INSTANCE); }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tick++;
        if (tick % 20 != 7) return;
        MinecraftServer server = e.getServer();
        SettlementManager m = SettlementManager.get(server);
        QuestData data = QuestData.get(server);
        long now = server.overworld().getGameTime();
        try { Bounties.escorts(data, m, server, now); } catch (Exception ex) { LivingRealms.LOG.error("Escort bounties failed", ex); }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            try {
                Bounties.patrol(data, m, p, now);
                Bounties.beams(data, m, p);
                if (tick % 40 == 7) Quests.poll(m, data, p);
            } catch (Exception ex) {
                LivingRealms.LOG.error("Quest update for {} failed", p.getGameProfile().getName(), ex);
            }
        }
        // bounties taken but not looked at still expire
        if (tick % 1200 == 7) for (var town : m.all()) if (data.boards.containsKey(town.id))
            Bounties.refresh(data, m, town, now, server.overworld().random, server);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void killed(LivingDeathEvent e) {
        if (e.isCanceled() || !(e.getEntity() instanceof Enemy) || !(e.getEntity().level() instanceof ServerLevel level)) return;
        Entity killer = e.getSource().getEntity();
        if (killer == null) return;
        MinecraftServer server = level.getServer();
        UUID player = null;
        if (killer instanceof ServerPlayer sp) { if (sp instanceof net.minecraftforge.common.util.FakePlayer) return; player = sp.getUUID(); }
        else {
            var rec = SoldierProgression.record(server, killer);
            if (rec != null) player = rec.settlement().owner;
        }
        if (player == null) return;
        Quests.killed(QuestData.get(server), SettlementManager.get(server), player, server.getPlayerList().getPlayer(player),
                level.dimension(), e.getEntity().blockPosition(), server.overworld().getGameTime());
    }

    private QuestEvents() {}
}
