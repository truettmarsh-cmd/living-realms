package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.Registry;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Tier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Opt-in capture of the town ring and the town's claim on the Recruits world map, before and after a rank-up
 * (./gradlew runClient -PtownRingTour -PrunDir=<dir> [-PvillageExpansion]). Places a real charter.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class TownRingTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.townRingTour");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static final String SAVE = "livingrealms_town_ring_" + RUN;
    private static int ticks = -1, waiting, grewAt = -1;
    private static boolean opened;
    private static volatile UUID townId;
    private static volatile BlockPos center;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (++waiting > 20 * 300) { LOG.error("Town ring tour timed out: {}", mc.screen); mc.stop(); return; }
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && waiting > 40) { opened = true; openWorld(mc); }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(SAVE)) return;
        UUID playerId = mc.player.getUUID();
        ticks++;
        if (ticks == 40) server.execute(() -> found(server.getPlayerList().getPlayer(playerId)));
        else if (ticks == 80) { mc.setScreen(null); server.execute(() -> view(server.getPlayerList().getPlayer(playerId), 70, 60, 50f)); }
        else if (ticks == 190) mc.gui.getChat().clearMessages(false);
        else if (ticks == 200) { towns("camp"); shot(mc, "ring_camp"); }
        else if (ticks == 205) server.execute(() -> above(server.getPlayerList().getPlayer(playerId)));
        else if (ticks == 225) map(mc);
        else if (ticks == 280) shot(mc, "map_camp");
        else if (ticks == 300) {
            mc.setScreen(null);
            mc.gui.getChat().clearMessages(false);
            server.execute(() -> view(server.getPlayerList().getPlayer(playerId), 96, 52, 38f));
            server.execute(() -> {
                Settlement s = SettlementManager.get(server).get(townId);
                s.tier = Tier.VILLAGE;
                s.log(s.name + " is now a " + Tier.VILLAGE.label + "! (tour)");
                s.dirty = true;
            });
        } else if (ticks > 300 && grewAt < 0) {
            for (CompoundTag t : ClientState.towns) if (t.getUUID("id").equals(townId) && t.getInt("radius") >= Tier.VILLAGE.radius) grewAt = ticks;
            if (ticks > 900) { towns("timeout"); LOG.error("Town ring tour: the town never grew on the client"); mc.stop(); }
        } else if (grewAt > 0) {
            int t = ticks - grewAt;
            if (t == 22) shot(mc, "ring_expanding");
            else if (t == 100) shot(mc, "ring_village");
            else if (t == 105) server.execute(() -> above(server.getPlayerList().getPlayer(playerId)));
            else if (t == 125) map(mc);
            else if (t == 195) shot(mc, "map_village");
            else if (t == 215) { LOG.info("Town ring tour complete: screenshot prefix=livingrealms_townring_{}", RUN); mc.stop(); }
        }
    }

    /** Places the charter as a player would, next to a Village Expansion village when that mod is loaded. */
    private static void found(ServerPlayer player) {
        var level = player.serverLevel();
        level.setDayTime(6000);
        BlockPos p = player.blockPosition();
        if (livingrealms.settlement.vx.Vx.active()) {
            BlockPos v = p.offset(-150, 0, 190);
            livingrealms.settlement.vx.VxBridge.testVillage(level, "village_tour", level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, v));
        }
        BlockPos c = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.offset(6, 0, 6));
        var state = Registry.CHARTER.get().defaultBlockState();
        level.setBlock(c, state, 3);
        state.getBlock().setPlacedBy(level, c, state, player, new ItemStack(Registry.CHARTER_ITEM.get()));
        Settlement s = SettlementManager.get(player.server).at(level.dimension(), c);
        if (s == null) { LOG.error("Town ring tour: charter founded nothing"); return; }
        townId = s.id; center = c;
        LOG.info("Town ring tour: founded {} at {} (claim {})", s.name, c, s.claim);
    }

    /** Hovers south of the town, inside the ring's range, looking north and down at it. */
    private static void view(ServerPlayer player, int back, int up, float pitch) {
        if (center == null) return;
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.getInventory().clearContent();
        player.teleportTo(player.serverLevel(), center.getX() + 0.5, center.getY() + up, center.getZ() + back + 0.5, 180f, pitch);
    }

    /** Over the town centre, so the Recruits world map opens on it. */
    private static void above(ServerPlayer player) {
        if (center != null) player.teleportTo(player.serverLevel(), center.getX() + 0.5, center.getY() + 46, center.getZ() + 0.5, 180f, 24f);
    }

    private static void towns(String when) {
        StringBuilder b = new StringBuilder();
        for (CompoundTag t : ClientState.towns) b.append(t.getString("name")).append(" r=").append(t.getInt("radius")).append(" tier=").append(t.getInt("tier")).append("; ");
        LOG.info("Town ring tour ({}): client towns {}", when, b);
    }

    private static void map(Minecraft mc) {
        try {
            mc.setScreen(new com.talhanation.recruits.client.gui.worldmap.WorldMapScreen());
        } catch (RuntimeException ex) { LOG.error("Town ring tour: Recruits world map failed", ex); }
    }

    private static void shot(Minecraft mc, String name) {
        String file = "livingrealms_townring_" + RUN + "_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> LOG.info("Town ring tour captured {}", file));
    }

    private static void openWorld(Minecraft mc) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(SAVE, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(SAVE, settings, new WorldOptions(20261028L, true, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }

    private TownRingTour() {}
}
