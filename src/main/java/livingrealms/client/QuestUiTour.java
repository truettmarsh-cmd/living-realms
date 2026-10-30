package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.building.BuildingCatalog;
import livingrealms.client.ui.CityScreen;
import livingrealms.military.UnitType;
import livingrealms.net.Net;
import livingrealms.quest.Bounties;
import livingrealms.quest.QuestData;
import livingrealms.settlement.Plot;
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
import net.minecraft.world.item.ItemStack;
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

/** Opt-in Quests page capture (./gradlew runClient -PquestUiTest -PrunDir=<fresh dir>): a town midway through chapter 2 with its bounty board. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class QuestUiTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.questUiTest");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static final String SAVE = "livingrealms_quest_ui_" + RUN;
    private static int ticks = -1, waiting;
    private static boolean opened;
    private static volatile UUID townId;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (++waiting > 20 * 300) { LOG.error("Quest UI tour timed out: {}", mc.screen); mc.stop(); return; }
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && waiting > 40) { opened = true; openWorld(mc); }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(SAVE)) return;
        UUID playerId = mc.player.getUUID();
        ticks++;
        switch (ticks) {
            case 40 -> server.execute(() -> setup(server.getPlayerList().getPlayer(playerId)));
            case 75 -> shot(mc, "toast", false);
            case 120 -> server.execute(() -> board(server.getPlayerList().getPlayer(playerId)));
            case 140 -> server.execute(() -> Net.openManager(server.getPlayerList().getPlayer(playerId), SettlementManager.get(server).get(townId), "quests"));
            case 160 -> { if (mc.screen instanceof CityScreen city) city.tour("QUESTS", 0, null); clearHover(mc); }
            case 190 -> shot(mc, "quests", true);
            case 195 -> mc.options.guiScale().set(2);
            case 200 -> { mc.resizeDisplay(); if (mc.screen instanceof CityScreen city) city.tour("QUESTS", 0, null); clearHover(mc); }
            case 230 -> shot(mc, "quests_scale2", true);
            case 240 -> { LOG.info("Quest UI tour complete: screenshot prefix=livingrealms_quests_{}", RUN); mc.stop(); }
            default -> {}
        }
    }

    static Plot plot(Settlement s, String type, Plot.State state) {
        Plot p = new Plot();
        p.id = s.nextId++;
        p.typeId = type;
        var t = BuildingCatalog.get(type);
        p.blueprintKey = t == null ? "" : t.blueprintKey(1, s.style);
        p.origin = s.center.offset(30 + 20 * p.id, 0, 30);
        p.state = state;
        s.plots.add(p);
        return p;
    }

    /** Chapter 1 met (completed by the next quest poll, with its toasts), chapter 2 partly. */
    private static void setup(ServerPlayer player) {
        var level = player.serverLevel();
        level.setDayTime(5000);
        Settlement s = new Settlement();
        s.name = "Ashford"; s.owner = player.getUUID(); s.ownerName = player.getGameProfile().getName();
        s.dim = level.dimension(); s.center = player.blockPosition(); s.tier = Tier.HAMLET; s.population = 9; s.baseHousing = 12;
        s.research.add("scholarship");
        SettlementManager.get(player.server).add(s); townId = s.id;
        plot(s, "livingrealms:town_hall", Plot.State.BUILT);
        for (int i = 0; i < 3; i++) plot(s, "livingrealms:cottage", Plot.State.BUILT);
        plot(s, "livingrealms:library", Plot.State.BUILT);
        Plot waiting = plot(s, "livingrealms:cottage", Plot.State.BUILDING);
        var bill = waiting.blueprint().bill();
        var first = bill.keySet().iterator().next();
        waiting.status = "Waiting for " + first.getDescription().getString();
        for (int i = 0; i < 3; i++) {
            Settlement.Soldier so = new Settlement.Soldier();
            so.entity = UUID.randomUUID(); so.name = "Guard " + i; so.type = UnitType.FOOTMAN;
            s.soldiers.add(so);
        }
        player.setGameMode(GameType.CREATIVE);
        Simulation.recompute(s); s.dirty = true;
        LOG.info("Quest UI tour: seeded {} at {}", s.name, s.center);
    }

    /** Fill the board, take the delivery and the patrol, carry some of the delivery. */
    private static void board(ServerPlayer player) {
        var m = SettlementManager.get(player.server);
        Settlement s = m.get(townId);
        QuestData data = QuestData.get(player.server);
        long now = player.server.overworld().getGameTime();
        Bounties.refresh(data, m, s, now, player.getRandom(), player.server);
        for (var b : data.board(s.id).bounties) {
            if (b.kind == QuestData.Kind.SLAY) continue;
            Bounties.accept(data, m, s, player, b.id, now);
            if (b.kind == QuestData.Kind.DELIVER) player.getInventory().add(new ItemStack(b.item, Math.min(64, b.need / 2)));
            if (b.kind == QuestData.Kind.PATROL) { b.visited = 1; b.progress = 1; }
        }
        s.dirty = true;
    }

    private static void clearHover(Minecraft mc) {
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 10, 10);
        try {
            for (String name : new String[]{"xpos", "ypos"}) {
                var field = mc.mouseHandler.getClass().getDeclaredField(name);
                field.setAccessible(true); field.setDouble(mc.mouseHandler, 10);
            }
        } catch (ReflectiveOperationException ex) { LOG.warn("Quest UI tour could not clear hover: {}", ex.toString()); }
    }

    private static void shot(Minecraft mc, String name, boolean city) {
        if (city && !(mc.screen instanceof CityScreen)) { LOG.error("Quest UI tour: missing CityScreen for {}", name); return; }
        String file = "livingrealms_quests_" + RUN + "_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> LOG.info("Quest UI tour captured {}", file));
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
    private QuestUiTour() {}
}
