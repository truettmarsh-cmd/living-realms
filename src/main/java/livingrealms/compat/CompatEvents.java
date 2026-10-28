package livingrealms.compat;

import livingrealms.LivingRealms;
import livingrealms.military.SoldierProgression;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** The integrations' own server ticks and events; nothing here runs unless its mod is loaded. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class CompatEvents {
    private static long tick;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tick++;
        if (tick % 20 == 0) run(e.getServer(), tick);
    }

    static void run(MinecraftServer server, long tick) {
        SettlementManager m = SettlementManager.get(server);
        boolean raiders = Compat.loaded(Compat.SIEGE_OVERHAUL) && Compat.on(CompatConfig.RAIDER_ALARM),
                workers = Compat.loaded(Compat.WORKERS) && Compat.on(CompatConfig.WORKERS_STAFF) && tick % 100 == 0,
                morale = Compat.loaded(Compat.MORALE) && Compat.on(CompatConfig.MORALE) && Compat.ok(Compat.MORALE) && tick % 200 == 0;
        long now = server.overworld().getGameTime();
        for (Settlement s : m.all()) {
            ServerLevel level = server.getLevel(s.dim);
            if (level == null || !level.hasChunkAt(s.center)) continue;
            if (raiders && s.alarmUntil > now) SiegeOverhaulCompat.engage(level, s);
            if (workers) WorkersCompat.scan(level, s);
            if (morale) try { MoraleCompat.veterans(level, s); } catch (Throwable t) { Compat.fail(Compat.MORALE, t); }
        }
        if (tick % 40 == 0 && Compat.loaded(Compat.CORPSE) && Compat.ok(Compat.CORPSE))
            try { CorpseCompat.tick(server); } catch (Throwable t) { Compat.fail(Compat.CORPSE, t); }
        if (tick % 200 == 0) feudal(server, m);
    }

    static void feudal(MinecraftServer server, SettlementManager m) {
        CompatData data = CompatData.get(server);
        if (Compat.loaded(Compat.VASSALS) && Compat.on(CompatConfig.FEUDAL_TITLES) && Compat.ok(Compat.VASSALS))
            try { VassalCompat.tick(server, m, data); } catch (Throwable t) { Compat.fail(Compat.VASSALS, t); }
        if (Compat.loaded(Compat.RANKS) && Compat.on(CompatConfig.RANK_TITLES) && Compat.ok(Compat.RANKS))
            try { RanksCompat.tick(server, m, data); } catch (Throwable t) { Compat.fail(Compat.RANKS, t); }
    }

    /** Runs before Living Realms drops the soldier's record, so the fallen can still be traced to their town. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity().level() instanceof ServerLevel level) || !Compat.loaded(Compat.CORPSE) || !Compat.on(CompatConfig.CORPSE_SALVAGE)) return;
        var record = SoldierProgression.record(level.getServer(), e.getEntity());
        if (record != null) CorpseCompat.fallen(record.settlement(), e.getEntity().getName().getString(), level.dimension(),
                e.getEntity().blockPosition(), level.getServer().overworld().getGameTime());
    }

    private CompatEvents() {}
}
