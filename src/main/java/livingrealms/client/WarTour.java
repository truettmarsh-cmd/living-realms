package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.client.ui.CityScreen;
import livingrealms.net.Net;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.WarShowcase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
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

/**
 * War walkthrough (./gradlew runClient -PwarTour -PvillageExpansion -PpackMods -PrunDir=<fresh dir>): a village faction
 * declares war, marches on your capital and is beaten off; you raid back with a trebuchet and take the village.
 * Screenshots: <runDir>/screenshots/war_NN_*.png.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class WarTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.warTour");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String SAVE = "livingrealms_war_" + System.currentTimeMillis();
    private static int ticks = -1, waiting, stage, since, shots;
    private static boolean opened;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (++waiting > 20 * 300) { LOG.error("War tour timed out on {}", mc.screen); mc.stop(); return; }
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && waiting > 40) { opened = true; openWorld(mc); }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(SAVE)) return;
        UUID me = mc.player.getUUID();
        ticks++;
        since++;
        if (ticks % 100 == 0 && stage > 0) server.execute(() -> LOG.info("War tour [{} t{}]: {}", stage, ticks, WarShowcase.status(server)));
        if (ticks > 20 * 60 * 25) { LOG.error("War tour ran out of time at stage {}", stage); mc.stop(); return; }
        switch (stage) {
            case 0 -> { if (ticks == 40) { server.execute(() -> WarShowcase.setup(player(server, me))); next(); } }
            // the scene: your capital, the village that will go to war
            case 1 -> { if (since == 2 && WarShowcase.neighbourFaction == null) since = 0; if (since == 100) { look(server, me, WarShowcase.home.offset(-34, 26, -40), WarShowcase.home.offset(0, 0, 8)); } if (since == 140) { shot(mc, "kingdom"); next(); } }
            case 2 -> { if (since == 2) look(server, me, WarShowcase.enemy.offset(-40, 30, -40), WarShowcase.enemy); if (since == 60) { shot(mc, "village"); next(); } }
            // Military → Realms, at peace
            case 3 -> realms(mc, server, me, "realms_peace");
            // their war roll comes up
            case 4 -> {
                if (since == 2) { look(server, me, WarShowcase.home.offset(-20, 14, -24), WarShowcase.home); server.execute(() -> WarShowcase.villageDeclares(server)); }
                if (since == 40) { shot(mc, "war_declared"); next(); }
            }
            case 5 -> realms(mc, server, me, "realms_war");
            // they march on Ashford
            case 6 -> {
                if (since == 2) server.execute(() -> LOG.info("War tour: enemy raid -> {}", WarShowcase.enemyRaid(server)));
                if (since == 30) shot(mc, "enemy_raid_called");
                if (since > 200 && since % 20 == 0) follow(server, me);
                if (since == 260) shot(mc, "enemy_march");
                if (since > 260 && near(server, WarShowcase.home, 36)) next();
                if (since > 2400) next();
            }
            case 7 -> {
                if (since % 20 == 0) look(server, me, WarShowcase.home.offset(-16, 12, -30), WarShowcase.home.offset(0, 0, -6));
                if (since == 80) shot(mc, "defence");
                if (since == 82) server.execute(() -> Net.openManager(player(server, me), WarShowcase.town(server), "military"));
                if (since == 110) { if (mc.screen instanceof CityScreen c) c.tour("MILITARY", 0, null); }
                if (since == 130) { shot(mc, "defence_status"); mc.setScreen(null); }
                if (since > 140 && WarShowcase.raid(server) == null) { LOG.info("War tour: defence over, log {}", WarShowcase.log(server)); next(); }
                if (since > 6000) next();
            }
            case 8 -> { if (since == 20) shot(mc, "defence_result"); if (since == 40) next(); }
            // you raid back, with a trebuchet from the stockpile
            case 9 -> {
                if (since == 2) server.execute(() -> WarShowcase.counterRaid(player(server, me)));
                if (since == 30) shot(mc, "your_raid_called");
                if (since > 200 && since % 20 == 0) follow(server, me);
                if (since == 300) shot(mc, "your_march");
                if (since > 300 && near(server, WarShowcase.enemy, 50)) next();
                if (since > 2400 || since > 40 && WarShowcase.raid(server) == null) next();
            }
            case 10 -> {
                if (since == 2) look(server, me, WarShowcase.enemy.offset(-42, 18, -10), WarShowcase.enemy);
                if (since == 60) shot(mc, "siege_engine");
                if (since > 70 && since % 20 == 0) look(server, me, WarShowcase.enemy.offset(-18, 12, -18), WarShowcase.enemy);
                if (since == 220) shot(mc, "assault");
                if (since > 230 && WarShowcase.status(server).startsWith("City center occupied") && shots < 13) shot(mc, "holding");
                if (since > 230 && WarShowcase.raid(server) == null) { LOG.info("War tour: raid over, log {}", WarShowcase.log(server)); next(); }
                if (since > 8000) next();
            }
            case 11 -> { if (since == 20) shot(mc, "conquest"); if (since == 30) next(); }
            case 12 -> {
                if (since == 2) server.execute(() -> Net.openManager(player(server, me), WarShowcase.town(server), "map"));
                if (since == 25 && mc.screen instanceof CityScreen c) c.tour("MAP", 0, null);
                if (since == 45) { shot(mc, "map_after"); mc.setScreen(null); }
                if (since == 55) next();
            }
            case 13 -> realms(mc, server, me, "realms_after");
            default -> { LOG.info("War tour complete: {} screenshots", shots); mc.stop(); }
        }
    }

    private static void realms(Minecraft mc, MinecraftServer server, UUID me, String name) {
        if (since == 2) server.execute(() -> Net.openManager(player(server, me), WarShowcase.town(server), "military"));
        if (since == 30 && mc.screen instanceof CityScreen c) c.tour("MILITARY", 5, null);
        if (since == 50) shot(mc, name);
        if (since == 55) { mc.setScreen(null); next(); }
    }

    private static void next() { stage++; since = 0; }

    private static ServerPlayer player(MinecraftServer server, UUID me) { return server.getPlayerList().getPlayer(me); }

    private static boolean near(MinecraftServer server, BlockPos at, int r) {
        BlockPos a = WarShowcase.army(server);
        return a != null && a.distSqr(new BlockPos(at.getX(), a.getY(), at.getZ())) < r * r;
    }

    /** Hangs the camera above and behind the marching army. */
    private static void follow(MinecraftServer server, UUID me) {
        server.execute(() -> {
            BlockPos a = WarShowcase.army(server);
            if (a == null) return;
            var raid = WarShowcase.raid(server);
            var target = raid == null ? null : SettlementManager.get(server).get(raid.target);
            BlockPos goal = target == null ? a : target.center;
            double dx = goal.getX() - a.getX(), dz = goal.getZ() - a.getZ(), len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            BlockPos from = a.offset((int) (-dx / len * 16), 10, (int) (-dz / len * 16));
            DevTour.look(player(server, me), from, a);
        });
    }

    private static void look(MinecraftServer server, UUID me, BlockPos from, BlockPos at) {
        server.execute(() -> DevTour.look(player(server, me), from, at));
    }

    private static void shot(Minecraft mc, String name) {
        String file = String.format("war_%02d_%s.png", ++shots, name);
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), c -> LOG.info("War tour captured {}", file));
    }

    private static void openWorld(Minecraft mc) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(SAVE, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(SAVE, settings, new WorldOptions(20261028L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }

    private WarTour() {}
}
