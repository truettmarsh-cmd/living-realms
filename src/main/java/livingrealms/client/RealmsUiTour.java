package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.client.ui.CityScreen;
import livingrealms.military.UnitType;
import livingrealms.military.WeaponProficiency;
import livingrealms.net.Net;
import livingrealms.settlement.Merit;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Simulation;
import livingrealms.settlement.Tier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.UUID;

/** Opt-in Honours board capture (./gradlew runClient -PrealmsUiTest -PrunDir=run-honours-ui). Seeded showcase values. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class RealmsUiTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.realmsUiTest");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static final String SAVE = "livingrealms_realms_ui_" + RUN;
    private static int ticks = -1, waiting;
    private static boolean opened;
    private static volatile UUID townId;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (++waiting > 20 * 300) { LOG.error("Honours UI tour timed out: {}", mc.screen); mc.stop(); return; }
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && waiting > 40) { opened = true; openWorld(mc); }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(SAVE)) return;
        UUID playerId = mc.player.getUUID();
        ticks++;
        switch (ticks) {
            case 40 -> server.execute(() -> setup(server.getPlayerList().getPlayer(playerId)));
            case 200 -> server.execute(() -> {
                var m = SettlementManager.get(server);
                Settlement s = m.get(townId);
                String realm = livingrealms.settlement.Realms.realm(server, m, s);
                LOG.info("Realms UI tour: realm {}", realm);
                if (realm != null) livingrealms.settlement.vx.VxBridge.declareWar(server.overworld(), "village_160_100_0", realm);
            });
            case 320 -> server.execute(() -> Net.openManager(server.getPlayerList().getPlayer(playerId), SettlementManager.get(server).get(townId), "military"));
            case 340 -> { if (mc.screen instanceof CityScreen city) city.tour("MILITARY", 5, null); clearHover(mc); }
            case 360 -> shot(mc, "realms");
            case 365 -> mc.options.guiScale().set(2);
            case 370 -> { mc.resizeDisplay(); if (mc.screen instanceof CityScreen city) city.tour("MILITARY", 5, null); clearHover(mc); }
            case 390 -> shot(mc, "realms_scale2");
            case 400 -> { LOG.info("Realms UI tour complete: screenshot prefix=livingrealms_realms_{}", RUN); mc.stop(); }
            default -> {}
        }
    }

    private static void setup(ServerPlayer player) {
        var level = player.serverLevel();
        level.setDayTime(5000);
        Settlement s = new Settlement();
        s.name = "Ashford"; s.owner = player.getUUID(); s.ownerName = player.getGameProfile().getName();
        s.dim = level.dimension(); s.center = player.blockPosition(); s.tier = Tier.TOWN; s.population = 30; s.baseHousing = 30;
        s.stock.force(Items.EMERALD, 64);
        SettlementManager.get(player.server).add(s); townId = s.id;
        livingrealms.settlement.vx.VxBridge.testVillage(level, "village_160_100_0", player.blockPosition().offset(160, 0, 0));
        livingrealms.settlement.vx.VxBridge.testVillage(level, "village_0_100_220", player.blockPosition().offset(0, 0, 220));
        player.setGameMode(GameType.CREATIVE);
        Simulation.recompute(s); s.dirty = true;
        LOG.info("Realms UI tour: town {} and two Village Expansion villages", s.id);
    }

    private static void clearHover(Minecraft mc) {
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 10, 10);
        try {
            for (String name : new String[]{"xpos", "ypos"}) {
                var field = mc.mouseHandler.getClass().getDeclaredField(name);
                field.setAccessible(true); field.setDouble(mc.mouseHandler, 10);
            }
        } catch (ReflectiveOperationException ex) { LOG.warn("Honours UI tour could not clear hover: {}", ex.toString()); }
    }

    private static void shot(Minecraft mc, String name) {
        if (!(mc.screen instanceof CityScreen)) { LOG.error("Honours UI tour: missing CityScreen for {}", name); return; }
        String file = "livingrealms_honours_" + RUN + "_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> LOG.info("Honours UI tour captured {}", file));
    }

    private static void openWorld(Minecraft mc) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(SAVE, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(SAVE, settings, new WorldOptions(20261027L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }
    private RealmsUiTour() {}
}
