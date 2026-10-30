package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.client.ui.CityScreen;
import livingrealms.net.Net;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Simulation;
import livingrealms.settlement.Tier;
import livingrealms.settlement.Trade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
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
import org.slf4j.Logger;

import java.util.UUID;

/** Opt-in Trade page and caravan capture (./gradlew runClient -PtradeUiTest -PrunDir=<fresh dir>). */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class TradeUiTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.tradeUiTest");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static final String SAVE = "livingrealms_trade_ui_" + RUN;
    private static int ticks = -1, waiting;
    private static boolean opened;
    private static volatile UUID homeId, partnerId;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (++waiting > 20 * 300) { LOG.error("Trade UI tour timed out: {}", mc.screen); mc.stop(); return; }
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && waiting > 40) { opened = true; openWorld(mc); }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(SAVE)) return;
        UUID playerId = mc.player.getUUID();
        ticks++;
        switch (ticks) {
            case 40 -> server.execute(() -> setup(server.getPlayerList().getPlayer(playerId)));
            case 160 -> server.execute(() -> Net.openManager(server.getPlayerList().getPlayer(playerId), SettlementManager.get(server).get(homeId)));
            case 180 -> { if (mc.screen instanceof CityScreen city) { city.tour("TRADE", 0, null); city.tourMouse(4, 4); } }
            case 200 -> shot(mc, "trade_loading");
            case 210 -> server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                p.closeContainer();
                Settlement home = SettlementManager.get(server).get(homeId), partner = SettlementManager.get(server).get(partnerId);
                BlockPos at = partner.center.offset(-75, 0, 3);
                p.teleportTo(p.serverLevel(), at.getX() + .5, at.getY() + 2, at.getZ() + .5, -90, 10);
                LOG.info("Trade UI tour: watching the road out of {} toward {}", partner.name, home.name);
            });
            case 215 -> mc.setScreen(null);
            case 1000 -> worldShot(mc, "caravan_road");
            case 1010 -> server.execute(() -> Net.openManager(server.getPlayerList().getPlayer(playerId), SettlementManager.get(server).get(homeId)));
            case 1030 -> { if (mc.screen instanceof CityScreen city) { city.tour("TRADE", 0, null); city.tourMouse(4, 4); } }
            case 1050 -> shot(mc, "trade_road");
            case 1060 -> { LOG.info("Trade UI tour complete: screenshot prefix=livingrealms_trade_{}", RUN); mc.stop(); }
            default -> {}
        }
    }

    private static void setup(ServerPlayer player) {
        var level = player.serverLevel();
        level.setDayTime(5000);
        var m = SettlementManager.get(player.server);
        Settlement home = new Settlement();
        home.name = "Wheatford"; home.owner = player.getUUID(); home.ownerName = player.getGameProfile().getName();
        home.dim = level.dimension(); home.center = player.blockPosition(); home.tier = Tier.VILLAGE; home.population = 18; home.baseHousing = 24;
        home.stock.force(Items.WHEAT, 120); home.stock.force(Items.BREAD, 20);
        m.add(home); homeId = home.id;
        Settlement partner = new Settlement();
        partner.name = "Sandmouth"; partner.style = "minecraft:desert"; partner.tier = Tier.HAMLET; partner.population = 12;
        partner.dim = level.dimension(); partner.center = player.blockPosition().offset(120, 0, 0);
        partner.stock.force(Items.GLASS, 120); partner.stock.force(Items.SAND, 150); partner.stock.force(Items.SANDSTONE, 60);
        m.add(partner); partnerId = partner.id;
        player.setGameMode(GameType.CREATIVE);
        Simulation.recompute(home); Simulation.recompute(partner);
        m.trade.nextStep = 0;
        home.dirty = true;
        LOG.info("Trade UI tour: {} and {}", home.name, partner.name);
    }

    private static void shot(Minecraft mc, String name) {
        if (!(mc.screen instanceof CityScreen)) { LOG.error("Trade UI tour: missing CityScreen for {}", name); return; }
        worldShot(mc, name);
    }

    private static void worldShot(Minecraft mc, String name) {
        String file = "livingrealms_trade_" + RUN + "_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> LOG.info("Trade UI tour captured {}", file));
    }

    private static void openWorld(Minecraft mc) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(SAVE, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(SAVE, settings, new WorldOptions(20261030L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }
    private TradeUiTour() {}
}
