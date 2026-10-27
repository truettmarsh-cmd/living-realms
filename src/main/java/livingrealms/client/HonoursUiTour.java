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

/** Opt-in Honours board capture (./gradlew runClient -PhonoursUiTest -PrunDir=run-honours-ui). Seeded showcase values. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class HonoursUiTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.honoursUiTest");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static final String SAVE = "livingrealms_honours_ui_" + RUN;
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
            case 60 -> server.execute(() -> Net.openManager(server.getPlayerList().getPlayer(playerId), SettlementManager.get(server).get(townId), "military"));
            case 80 -> { if (mc.screen instanceof CityScreen city) city.tour("MILITARY", 4, null); clearHover(mc); }
            case 100 -> shot(mc, "honours");
            case 105 -> mc.options.guiScale().set(2);
            case 110 -> { mc.resizeDisplay(); if (mc.screen instanceof CityScreen city) city.tour("MILITARY", 4, null); clearHover(mc); }
            case 130 -> shot(mc, "honours_scale2");
            case 140 -> { LOG.info("Honours UI tour complete: screenshot prefix=livingrealms_honours_{}", RUN); mc.stop(); }
            default -> {}
        }
    }

    private static void setup(ServerPlayer player) {
        var level = player.serverLevel();
        level.setDayTime(5000);
        Settlement s = new Settlement();
        s.name = "Ashford"; s.owner = player.getUUID(); s.ownerName = player.getGameProfile().getName();
        s.dim = level.dimension(); s.center = player.blockPosition(); s.tier = Tier.TOWN; s.population = 30; s.baseHousing = 30;
        s.research.add("iron_working"); s.stock.force(Items.IRON_AXE, 1); s.stock.force(Items.EMERALD, 64);
        SettlementManager.get(player.server).add(s); townId = s.id;
        long now = player.server.overworld().getGameTime();
        String[] names = {"Sir Aldric", "Bryn Ashby", "Maren Holt", "Tobias Reed", "Edda Vale", "Corin Pike", "Hal Thorne", "Isolde Marsh", "Wat Fletcher", "Gwen Hale", "Osric Dunn", "Pell Ward"};
        UnitType[] types = {UnitType.SHIELDBEARER, UnitType.FOOTMAN, UnitType.BOWMAN, UnitType.FOOTMAN, UnitType.CROSSBOWMAN, UnitType.HORSEMAN};
        WeaponProficiency[] favs = {WeaponProficiency.AXE, WeaponProficiency.SPEAR, WeaponProficiency.BOW, WeaponProficiency.SWORD, WeaponProficiency.CROSSBOW, WeaponProficiency.MACE};
        for (int i = 0; i < names.length; i++) {
            Settlement.Soldier so = new Settlement.Soldier();
            so.entity = UUID.randomUUID(); so.name = names[i]; so.type = types[i % types.length]; so.preferredWeapon = favs[i % favs.length];
            so.kills = 40 - i * 3; so.skillXp = Math.max(0, 700 - i * 60); so.royalGuard = i == 0;
            so.merit.merit = Math.max(4, 118 - i * 11); so.merit.updated = now; so.merit.killDay = now / Merit.DAY;
            so.merit.dayKills[(int) (now / Merit.DAY % 7)] = Math.max(0, 12 - i * 2);
            if (i == 1 || i == 3) { so.merit.feat = i == 1 ? "defended Ashford" : "raided Brindle"; so.merit.featAt = now; }
            so.merit.cooldownUntil = now + 100 * Merit.DAY;
            s.soldiers.add(so);
        }
        s.soldiers.get(0).merit.want = Merit.Want.WEAPON; s.soldiers.get(0).merit.wantAt = now;
        s.soldiers.get(1).merit.want = Merit.Want.ARMOUR; s.soldiers.get(1).merit.wantAt = now;
        s.soldiers.get(3).merit.want = Merit.Want.WEAPON; s.soldiers.get(3).merit.wantAt = now; s.soldiers.get(3).merit.ordered = true;
        player.setGameMode(GameType.CREATIVE);
        Simulation.recompute(s); s.dirty = true;
        LOG.info("Honours UI tour: seeded showcase town {} with {} soldiers and 3 requests", s.id, s.soldiers.size());
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
    private HonoursUiTour() {}
}
