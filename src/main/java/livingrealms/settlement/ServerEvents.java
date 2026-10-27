package livingrealms.settlement;

import livingrealms.Config;
import livingrealms.LivingRealms;
import livingrealms.net.Net;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class ServerEvents {
    private static long tick;
    private record Pending(ResourceKey<Level> dim, ChunkPos pos) {}
    private static final Deque<Pending> PENDING = new ArrayDeque<>();
    private static final Set<Long> SEEN = new HashSet<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = e.getServer();
        SettlementManager m = SettlementManager.get(server);
        tick++;
        for (int i = 0; i < 4 && !PENDING.isEmpty(); i++) {
            Pending p = PENDING.poll();
            ServerLevel level = server.getLevel(p.dim);
            if (level != null) discover(level, m, p.pos);
        }
        if (tick % 20 == 0) {
            for (Settlement s : m.all()) {
                ServerLevel level = server.getLevel(s.dim);
                if (level == null || !level.hasChunkAt(s.center)) continue;
                Simulation.fast(level, s);
                if (tick % 40 == 0) Simulation.watch(level, s);
                if (tick % 100 == 0) Simulation.garrison(level, s, false);
            }
        }
        if (tick % 20 == 0) Raids.tick(server, m);
        if (tick % Config.SIM_INTERVAL.get() == 0) {
            for (Settlement s : m.all()) {
                try {
                    Simulation.step(server, s);
                } catch (Exception ex) {
                    LivingRealms.LOG.error("Settlement {} step failed", s.name, ex);
                }
            }
        }
        if (tick % 10 == 0) Net.syncWatchers(server, m);
        if (tick % 40 == 5) Net.syncPlots(server, m);
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getChunk() instanceof LevelChunk chunk)) return;
        if (!Config.AI_VILLAGES.get() || chunk.getAllStarts().isEmpty()) return;
        long key = chunk.getPos().toLong() ^ ((long) level.dimension().location().hashCode() << 40);
        if (!SEEN.add(key)) return;
        PENDING.add(new Pending(level.dimension(), chunk.getPos()));
    }

    /** A generated village becomes an AI settlement the first time its starting chunk loads. */
    static void discover(ServerLevel level, SettlementManager m, ChunkPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) return;
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        chunk.getAllStarts().forEach((structure, start) -> {
            if (!start.isValid() || !start.getChunkPos().equals(pos)) return;
            if (!registry.wrapAsHolder(structure).is(StructureTags.VILLAGE)) return;
            BoundingBox bb = start.getBoundingBox();
            int x = (bb.minX() + bb.maxX()) / 2, z = (bb.minZ() + bb.maxZ()) / 2;
            int y = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
            var center = new net.minecraft.core.BlockPos(x, y, z);
            if (!m.roomFor(level.dimension(), center)) return;
            var key = registry.getKey(structure);
            Settlement s = new Settlement();
            s.name = Names.town(level.random);
            s.dim = level.dimension();
            s.center = center;
            s.structureKey = key == null ? "" : key.toString();
            s.style = key == null ? "minecraft:plains" : key.getNamespace() + ":" + key.getPath().replace("village_", "").replace("_village", "");
            int pieces = start.getPieces().size();
            s.population = Math.max(4, Math.min(24, pieces / 2 + 2));
            s.baseHousing = s.population + 2;
            s.tier = s.population >= 16 ? Tier.VILLAGE : s.population >= 8 ? Tier.HAMLET : Tier.CAMP;
            s.wealth = 150;
            s.founded = level.getGameTime();
            if (s.tier.number() >= 3) s.research.add("masonry");
            s.log(s.name + " was founded long ago");
            m.add(s);
            LivingRealms.LOG.info("Living Realms: {} ({}) at {}", s.name, s.structureKey, center);
        });
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity().level().isClientSide || !(e.getEntity().level() instanceof ServerLevel level)) return;
        var id = e.getEntity().getUUID();
        for (Settlement s : SettlementManager.get(level.getServer()).all()) {
            if (s.soldiers.removeIf(so -> so.entity.equals(id))) {
                s.log(e.getEntity().getName().getString() + " fell in battle");
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) { Net.sendCatalog(sp); Autonomy.login(sp); }
    }

    @SubscribeEvent
    public static void onEntityLoad(net.minecraftforge.event.entity.EntityJoinLevelEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        var record = livingrealms.military.SoldierProgression.record(level.getServer(), e.getEntity());
        if (record != null) livingrealms.military.RecruitsBridge.allegiance(e.getEntity(), record.settlement().kingdomId());
    }
}
