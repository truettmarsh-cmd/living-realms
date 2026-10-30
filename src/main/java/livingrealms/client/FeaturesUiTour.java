package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.chronicle.Chronicle;
import livingrealms.client.ui.CityScreen;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import livingrealms.net.Net;
import livingrealms.quest.Bounties;
import livingrealms.quest.QuestData;
import livingrealms.settlement.*;
import livingrealms.settlement.plan.RoadClass;
import livingrealms.settlement.plan.Street;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
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

/**
 * Screenshots of the features linked together (./gradlew runClient -PfeaturesUiTour -PrunDir=<fresh dir>): a walled town
 * with a paved high street trading with a desert town, an escort and a patrol bounty taken, its chronicle; the Quests,
 * Trade, Chronicle and City Map pages at several window sizes and GUI scales; the caravan leaving by the high street; then
 * an NPC kingdom besieges the town and the raid card shows the breach bar. Screenshots go to the run dir's screenshots/.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class FeaturesUiTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.featuresUiTour");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static final String SAVE = "livingrealms_features_ui_" + RUN;
    private static int ticks = -1, waiting;
    private static boolean opened;
    private static volatile UUID homeId, partnerId, enemyId;
    private static volatile BlockPos streetEnd = BlockPos.ZERO;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (++waiting > 20 * 300) { LOG.error("Features UI tour timed out: {}", mc.screen); mc.stop(); return; }
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && waiting > 40) { opened = true; openWorld(mc); }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(SAVE)) return;
        UUID id = mc.player.getUUID();
        ticks++;
        switch (ticks) {
            case 20 -> { window(mc, 1280, 720); mc.options.guiScale().set(2); mc.resizeDisplay(); }
            case 40 -> server.execute(() -> setup(server.getPlayerList().getPlayer(id)));
            case 120, 150, 170 -> server.execute(() -> takeEscort(server, server.getPlayerList().getPlayer(id)));
            case 160 -> open(server, id);
            // 1280x720, GUI scale 2
            case 180 -> page(mc, "QUESTS");
            case 195 -> shot(mc, "720p_gs2_quests");
            case 200 -> page(mc, "TRADE");
            case 215 -> shot(mc, "720p_gs2_trade");
            case 220 -> page(mc, "CHRONICLE");
            case 235 -> shot(mc, "720p_gs2_chronicle");
            case 240 -> { page(mc, "MAP"); if (mc.screen instanceof CityScreen c) c.tourMap(0.75f, 0, 0); }
            case 260 -> shot(mc, "720p_gs2_map");
            // 1024x640 at GUI scale 2 and 3: the tabs must still fit
            case 270 -> { window(mc, 1024, 640); mc.options.guiScale().set(2); mc.resizeDisplay(); page(mc, "QUESTS"); }
            case 290 -> shot(mc, "1024_gs2_quests");
            case 295 -> page(mc, "TRADE");
            case 310 -> shot(mc, "1024_gs2_trade");
            case 315 -> page(mc, "CHRONICLE");
            case 330 -> shot(mc, "1024_gs2_chronicle");
            case 335 -> { mc.options.guiScale().set(3); mc.resizeDisplay(); page(mc, "QUESTS"); }
            case 355 -> shot(mc, "1024_gs3_quests");
            case 360 -> page(mc, "CHRONICLE");
            case 375 -> shot(mc, "1024_gs3_chronicle");
            case 380 -> { page(mc, "MAP"); if (mc.screen instanceof CityScreen c) c.tourMap(0.75f, 0, 0); }
            case 395 -> shot(mc, "1024_gs3_map");
            // the smallest window the City Manager allows: the tabs become a grid of icons
            case 400 -> { window(mc, 854, 480); mc.options.guiScale().set(2); mc.resizeDisplay(); page(mc, "TRADE"); }
            case 420 -> shot(mc, "854_gs2_trade");
            case 425 -> page(mc, "OVERVIEW");
            case 440 -> shot(mc, "854_gs2_overview");
            case 450 -> { window(mc, 1280, 720); mc.options.guiScale().set(2); mc.resizeDisplay(); }
            // the caravan leaves by the high street
            case 470 -> server.execute(() -> watchRoad(server.getPlayerList().getPlayer(id)));
            case 480 -> mc.setScreen(null);
            case 760, 840, 920, 1000, 1100, 1200 -> worldShot(mc, "caravan_high_street_" + ticks);
            case 1300 -> worldShot(mc, "caravan_road");
            case 1310 -> open(server, id);
            case 1330 -> { page(mc, "MAP"); if (mc.screen instanceof CityScreen c) c.tourMap(0.75f, 0, 0); }
            case 1350 -> shot(mc, "map_caravan");
            case 1355 -> page(mc, "QUESTS");
            case 1370 -> shot(mc, "quests_escorting");
            case 1380 -> mc.setScreen(null);
            // a siege
            case 1400 -> server.execute(() -> besiege(server, server.getPlayerList().getPlayer(id)));
            case 1460 -> server.execute(() -> march(server, false));
            case 1500 -> server.execute(() -> march(server, true));
            case 1980 -> worldShot(mc, "siege_hud_breach");
            case 1990 -> open(server, id);
            case 2010 -> page(mc, "OVERVIEW");
            case 2030 -> shot(mc, "siege_overview_card");
            case 2040 -> { LOG.info("Features UI tour complete: screenshots livingrealms_features_{}_*", RUN); mc.stop(); }
            default -> {}
        }
    }

    static void open(MinecraftServer server, UUID id) {
        server.execute(() -> Net.openManager(server.getPlayerList().getPlayer(id), SettlementManager.get(server).get(homeId)));
    }

    static void page(Minecraft mc, String tab) {
        if (mc.screen instanceof CityScreen city) city.tour(tab, 0, null);
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 2, 2);
    }

    private static void setup(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        level.setDayTime(5000);
        var m = SettlementManager.get(player.server);
        BlockPos c = player.blockPosition();
        Settlement home = new Settlement();
        home.name = "Wheatford"; home.owner = player.getUUID(); home.ownerName = player.getGameProfile().getName();
        home.dim = level.dimension(); home.center = c; home.tier = Tier.VILLAGE; home.population = 18; home.baseHousing = 24;
        home.research.addAll(livingrealms.research.ResearchTree.nodes().keySet());
        home.stock.force(Items.WHEAT, 120); home.stock.force(Items.BREAD, 60);
        m.add(home); homeId = home.id;
        try { CityPlanningSelfTest.buildWalls(level, home); } catch (RuntimeException e) { LOG.error("Features UI tour: walls failed", e); }
        home.tier = Tier.TOWN;
        // the east high street paved as far as the town's land reaches
        for (Street st : home.plan().streets) {
            if (st.cls != RoadClass.HIGH || st.dir != Direction.EAST) continue;
            int len = home.plan().open(st, home.radius());
            for (int t = 0; t <= len; t++) for (int w = -st.half(); w <= st.half(); w++) {
                int x = st.x(t), z = st.z(t) + w;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.GRASS_BLOCK) || level.getBlockState(new BlockPos(x, y, z)).is(Blocks.DIRT))
                    level.setBlock(new BlockPos(x, y, z), Blocks.DIRT_PATH.defaultBlockState(), 3);
            }
            home.plan().laid.put(st.id, len);
            streetEnd = new BlockPos(st.x(len), c.getY(), st.z(len));
        }
        Settlement partner = new Settlement();
        partner.name = "Sandmouth"; partner.style = "minecraft:desert"; partner.tier = Tier.HAMLET; partner.population = 12;
        partner.dim = level.dimension(); partner.center = c.offset(230, 0, 20);
        partner.stock.force(Items.GLASS, 120); partner.stock.force(Items.SAND, 150); partner.stock.force(Items.SANDSTONE, 60);
        m.add(partner); partnerId = partner.id;
        Settlement enemy = new Settlement();
        enemy.name = "Ironhold"; enemy.style = "minecraft:taiga"; enemy.tier = Tier.HAMLET; enemy.population = 10;
        enemy.dim = level.dimension(); enemy.center = c.offset(-20, 0, -110); enemy.founded = level.getGameTime();
        m.add(enemy); enemyId = enemy.id;
        player.setGameMode(GameType.CREATIVE);
        Simulation.recompute(home); Simulation.recompute(partner); Simulation.recompute(enemy);
        // a little history
        Chronicle.record(home, Chronicle.Kind.BUILDING, "Town Hall");
        Chronicle.record(home, Chronicle.Kind.WALLS);
        Chronicle.record(home, Chronicle.Kind.RAID_REPELLED, "Ironhold");
        Chronicle.record(home, Chronicle.Kind.FIRE, "A fire started by sparks from the forge damaged the smithy; the bucket brigade saved the rest.");
        // a patrol bounty taken from the board
        QuestData data = QuestData.get(player.server);
        long now = level.getGameTime();
        Bounties.refresh(data, m, home, now, level.random, player.server);
        data.board(home.id).bounties.stream().filter(b -> b.kind == QuestData.Kind.PATROL).findFirst()
                .ifPresent(b -> Bounties.accept(data, m, home, player, b.id, now));
        m.trade.nextStep = 0;
        home.dirty = true;
        LOG.info("Features UI tour: {} walled ({}), trading with {}", home.name, livingrealms.settlement.plan.Walls.status(home), partner.name);
    }

    private static void takeEscort(MinecraftServer server, ServerPlayer player) {
        var m = SettlementManager.get(server);
        Settlement home = m.get(homeId);
        QuestData data = QuestData.get(server);
        long now = server.overworld().getGameTime();
        var escort = data.board(homeId).bounties.stream().filter(b -> b.kind == QuestData.Kind.ESCORT && b.taker == null).findFirst().orElse(null);
        LOG.info("Features UI tour: caravans {}, escort {}", m.trade.caravans.size(), escort == null ? "none" : Bounties.title(escort));
        if (escort != null) LOG.info("Features UI tour: accept escort -> {}", Bounties.accept(data, m, home, player, escort.id, now));
    }

    private static void watchRoad(ServerPlayer p) {
        Settlement home = SettlementManager.get(p.server).get(homeId);
        BlockPos at = streetEnd.equals(BlockPos.ZERO) ? home.center.offset(40, 0, 0) : streetEnd;
        // beside the paved high street, a little past the market, looking back toward the hall
        p.teleportTo(p.serverLevel(), at.getX() - 6.5, p.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX() - 7, at.getZ() - 9) + 4, at.getZ() - 8.5, 120, 20);
    }

    private static void besiege(MinecraftServer server, ServerPlayer p) {
        var m = SettlementManager.get(server);
        Settlement home = m.get(homeId), enemy = m.get(enemyId);
        ServerLevel level = p.serverLevel();
        for (int i = 0; i < 8; i++) {
            BlockPos at = enemy.center.offset(i % 4, 0, i / 4);
            at = at.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()));
            Entity e = RecruitsBridge.spawn(level, UnitType.FOOTMAN, at, enemy.kingdomId(), "Ironhold footman");
            if (e == null) continue;
            RecruitsBridge.setSlot(e, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            var so = new Settlement.Soldier(); so.entity = e.getUUID(); so.type = UnitType.FOOTMAN; so.name = "Ironhold footman " + i;
            enemy.soldiers.add(so);
        }
        enemy.raidCooldown = 0;
        String err = Raids.start(server, m, enemy, home);
        LOG.info("Features UI tour: siege started: {} · {}", err == null ? "ok" : err, m.raids.isEmpty() ? "-" : m.raids.get(0).siege.where);
        // the player watches from the wall walk inside the threatened gate
        if (!m.raids.isEmpty()) {
            BlockPos in = m.raids.get(0).siege.inside(home);
            p.teleportTo(level, in.getX() + .5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, in.getX(), in.getZ()) + 6, in.getZ() + .5, 0, 25);
        }
    }

    /** Brings the army up to the walls (the tour cannot wait for the long march): staging first, then the ram at the gate. */
    private static void march(MinecraftServer server, boolean ram) {
        var m = SettlementManager.get(server);
        if (m.raids.isEmpty()) return;
        Raids.Raid r = m.raids.get(0);
        Settlement home = m.get(homeId);
        ServerLevel level = server.getLevel(home.dim);
        BlockPos to = ram ? r.siege.outside(home) : r.siege.staging(home);
        int i = 0;
        for (UUID id : r.soldiers) {
            Entity e = level.getEntity(id);
            if (e == null) continue;
            int x = to.getX() + i % 3 - 1, z = to.getZ() + i / 3 % 3 - 1;
            e.teleportTo(x + .5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + .5);
            i++;
        }
        LOG.info("Features UI tour: {} soldiers at the {} ({})", i, ram ? "gate" : "staging ground", home.raidStatus);
    }

    private static void shot(Minecraft mc, String name) {
        if (!(mc.screen instanceof CityScreen)) { LOG.error("Features UI tour: missing CityScreen for {}", name); return; }
        worldShot(mc, name);
    }

    private static void worldShot(Minecraft mc, String name) {
        String file = "livingrealms_features_" + RUN + "_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> LOG.info("Features UI tour captured {}", file));
    }

    private static void window(Minecraft mc, int w, int h) {
        var win = mc.getWindow();
        double k = win.getScreenWidth() <= 0 ? 1 : win.getWidth() / (double) win.getScreenWidth();
        GLFW.glfwSetWindowSize(win.getWindow(), (int) Math.round(w / k), (int) Math.round(h / k));
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

    private FeaturesUiTour() {}
}
