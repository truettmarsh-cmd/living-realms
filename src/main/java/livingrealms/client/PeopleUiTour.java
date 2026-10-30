package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.building.BuildingCatalog;
import livingrealms.client.ui.CityScreen;
import livingrealms.military.UnitType;
import livingrealms.net.Net;
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

/** Opt-in People page capture (./gradlew runClient -PpeopleUiTest -PrunDir=<fresh dir>): a town of named citizens, its mayor and captain, a selected citizen's card. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class PeopleUiTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.peopleUiTest");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static final String SAVE = "livingrealms_people_ui_" + RUN;
    private static int ticks = -1, waiting;
    private static boolean opened;
    private static volatile UUID townId;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (++waiting > 20 * 300) { LOG.error("People UI tour timed out: {}", mc.screen); mc.stop(); return; }
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && waiting > 40) { opened = true; openWorld(mc); }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(SAVE)) return;
        UUID playerId = mc.player.getUUID();
        ticks++;
        switch (ticks) {
            case 40 -> server.execute(() -> setup(server.getPlayerList().getPlayer(playerId)));
            case 80 -> server.execute(() -> Net.openManager(server.getPlayerList().getPlayer(playerId), SettlementManager.get(server).get(townId), "people"));
            case 100 -> { if (mc.screen instanceof CityScreen city) city.tour("PEOPLE", 0, null); clearHover(mc); }
            case 130 -> shot(mc, "list", true);
            case 135 -> livingrealms.client.ui.PeoplePanel.select(selectId);
            case 160 -> shot(mc, "card", true);
            case 165 -> livingrealms.client.ui.PeoplePanel.choose("captain");
            case 190 -> shot(mc, "captain", true);
            case 195 -> { livingrealms.client.ui.PeoplePanel.choose(""); mc.options.guiScale().set(2); }
            case 200 -> { mc.resizeDisplay(); if (mc.screen instanceof CityScreen city) city.tour("PEOPLE", 0, null); clearHover(mc); }
            case 230 -> shot(mc, "card_scale2", true);
            case 240 -> { LOG.info("People UI tour complete: screenshot prefix=livingrealms_people_{}", RUN); mc.stop(); }
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

    private static volatile int selectId;

    /** A Village of 22 with houses, a chapel and a few soldiers; forty days lived on records; a fire just now. */
    private static void setup(ServerPlayer player) {
        var level = player.serverLevel();
        level.setDayTime(5000);
        Settlement s = new Settlement();
        s.name = "Ashford"; s.owner = player.getUUID(); s.ownerName = player.getGameProfile().getName();
        s.dim = level.dimension(); s.center = player.blockPosition().offset(0, 0, 400); s.tier = Tier.VILLAGE; s.population = 22; s.baseHousing = 30;
        s.happiness = 66;
        s.stock.force(Items.BREAD, 300);
        SettlementManager.get(player.server).add(s); townId = s.id;
        plot(s, "livingrealms:town_hall", Plot.State.BUILT);
        for (int i = 0; i < 5; i++) plot(s, "livingrealms:cottage", Plot.State.BUILT);
        plot(s, "livingrealms:farm_field", Plot.State.BUILT);
        plot(s, "livingrealms:library", Plot.State.BUILT);
        String[] names = {"Footman Bran", "Bowman Isolde", "Footman Gareth"};
        for (int i = 0; i < 3; i++) {
            Settlement.Soldier so = new Settlement.Soldier();
            so.entity = UUID.randomUUID(); so.name = names[i]; so.type = i == 1 ? UnitType.BOWMAN : UnitType.FOOTMAN; so.kills = 3 + i * 5; so.skillXp = i * 120;
            s.soldiers.add(so);
        }
        player.setGameMode(GameType.CREATIVE);
        Simulation.recompute(s);
        var r = player.getRandom();
        for (int d = 0; d < 40; d++) {
            livingrealms.citizen.Citizens.step(s, 1, r);
            if (s.population < s.d.housing && d % 2 == 0) { s.population++; livingrealms.citizen.Citizens.arrived(s, r); Simulation.recompute(s); }
        }
        livingrealms.chronicle.Chronicle.record(s, livingrealms.chronicle.Chronicle.Kind.FIRE, "A fire started by a candle took the bakery.");
        livingrealms.citizen.Citizens.step(s, 0, r);
        var p = s.people;
        selectId = p.adults().stream().filter(c -> c.spouse > 0 && !p.childrenOf(c).isEmpty()).findFirst().orElse(p.adults().get(0)).id;
        s.dirty = true;
        LOG.info("People UI tour: seeded {} with {} citizens", s.name, p.all.size());
    }

    private static void clearHover(Minecraft mc) {
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 10, 10);
        try {
            for (String name : new String[]{"xpos", "ypos"}) {
                var field = mc.mouseHandler.getClass().getDeclaredField(name);
                field.setAccessible(true); field.setDouble(mc.mouseHandler, 10);
            }
        } catch (ReflectiveOperationException ex) { LOG.warn("People UI tour could not clear hover: {}", ex.toString()); }
    }

    private static void shot(Minecraft mc, String name, boolean city) {
        if (city && !(mc.screen instanceof CityScreen)) { LOG.error("People UI tour: missing CityScreen for {}", name); return; }
        String file = "livingrealms_people_" + RUN + "_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> LOG.info("People UI tour captured {}", file));
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
    private PeopleUiTour() {}
}
