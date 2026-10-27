package livingrealms.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.Registry;
import livingrealms.client.ui.ArchitectScreen;
import livingrealms.client.ui.CityScreen;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.UUID;

/** Opt-in Architect view walkthrough (./gradlew runClient -ParchitectTest -PrunDir=...): found, fly, plan, refuse, manage, leave. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class ArchitectTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.architectTest");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static final String SAVE = "livingrealms_architect_" + RUN;
    private static int ticks = -1, waiting, fails, plotsBefore;
    private static boolean opened;
    private static volatile UUID townId;
    private static volatile BlockPos charter, site, spare;
    private static volatile int hallPrio = Integer.MIN_VALUE, plotCount = -1;
    private static volatile boolean hallOk;
    private static Vec3 bodyAt, camAt;
    private static float bodyYaw;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (++waiting > 20 * 300) { LOG.error("Architect tour timed out: {}", mc.screen); mc.stop(); return; }
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && waiting > 40) { opened = true; openWorld(mc); }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(SAVE)) return;
        UUID pid = mc.player.getUUID();
        ticks++;
        try {
            switch (ticks) {
                case 40 -> server.execute(() -> found(server.getPlayerList().getPlayer(pid)));
                case 100 -> { check(mc.screen instanceof CityScreen, "placing the charter founded a town and opened the City Manager"); if (mc.screen instanceof CityScreen c) c.tour("BUILD", 0, "livingrealms:town_hall"); }
                case 115 -> shot(mc, "1_manager_build");
                case 120 -> {
                    bodyAt = mc.player.position(); bodyYaw = mc.player.getYRot();
                    // what the Build page's Architect view button does
                    ArchitectView.enter(townId, true);
                    check(ArchitectView.active() && mc.screen instanceof ArchitectScreen && mc.getCameraEntity() != mc.player, "entered the Architect view from the City Manager");
                    camAt = ArchitectView.camera();
                }
                case 140 -> shot(mc, "2_entered");
                case 150 -> server.execute(() -> pickSites(SettlementManager.get(server).get(townId)));
                case 141 -> ArchitectView.testInput = new float[]{1, 0, 0.4f};
                case 181 -> {
                    ArchitectView.testInput = null;
                    check(ArchitectView.camera().distanceTo(camAt) > 3, "camera flew " + String.format("%.1f", ArchitectView.camera().distanceTo(camAt)) + " blocks");
                    check(mc.player.position().distanceTo(bodyAt) < 0.01, "player body stayed put while flying");
                }
                case 186 -> shot(mc, "3_flown");
                case 190 -> {
                    ArchitectView.select("livingrealms:cottage");
                    aim(site);
                }
                case 195 -> screen(mc).keyPressed(ClientSetup.ROTATE.getKey().getValue(), 0, 0);
                case 215 -> {
                    check(ArchitectView.rot == 1, "rotate key turned the building");
                    check(ArchitectView.status.isEmpty() && ArchitectView.origin != null, "cottage site shows valid (status '" + ArchitectView.status + "')");
                    shot(mc, "4_ghost_valid");
                }
                case 220 -> screen(mc).mouseClicked(mc.getWindow().getGuiScaledWidth() / 2.0, mc.getWindow().getGuiScaledHeight() / 2.0, 0);
                case 250 -> server.execute(() -> {
                    Settlement s = SettlementManager.get(server).get(townId);
                    hallOk = s.plots.stream().anyMatch(p -> p.typeId.equals("livingrealms:cottage") && p.rotation == Rotation.CLOCKWISE_90 && !p.automatic && p.state == Plot.State.BUILDING);
                    plotCount = s.plots.size();
                });
                case 255 -> check(hallOk, "click queued a rotated Cottage plot on the server");
                case 290 -> {
                    ArchitectView.select("livingrealms:cottage");
                    ArchitectView.rot = 1;
                    aim(site);
                }
                case 305 -> {
                    check(!ArchitectView.status.isEmpty(), "a second cottage over the first turns red: " + ArchitectView.status);
                    shot(mc, "5_invalid_red");
                    plotsBefore = plotCount;
                }
                case 310 -> screen(mc).mouseClicked(mc.getWindow().getGuiScaledWidth() / 2.0, mc.getWindow().getGuiScaledHeight() / 2.0, 0);
                case 312 -> aim(spare);
                case 330 -> {
                    check(ArchitectView.status.isEmpty(), "cottage in open ground is valid (status '" + ArchitectView.status + "')");
                    shot(mc, "6_cottage_valid_bill");
                    server.execute(() -> plotCount = SettlementManager.get(server).get(townId).plots.size());
                }
                case 335 -> check(plotCount == plotsBefore, "clicking the red ghost sent nothing (" + plotsBefore + " -> " + plotCount + " plots)");
                case 340 -> {
                    ArchitectView.select(null);
                    aim(site.above(3));
                }
                case 350 -> {
                    check(ArchitectView.hoveredPlot >= 0, "the queued cottage's ghost is under the cursor");
                    screen(mc).mouseClicked(mc.getWindow().getGuiScaledWidth() / 2.0, mc.getWindow().getGuiScaledHeight() / 2.0, 0);
                }
                case 360 -> {
                    check(ArchitectView.selectedPlot >= 0, "clicking the ghost selected the project");
                    server.execute(() -> hallPrio = SettlementManager.get(server).get(townId).plot(ArchitectView.selectedPlot).priority);
                    shot(mc, "7_queued_selected");
                }
                case 365 -> screen(mc).priority(ArchitectView.selectedPlot, 1);
                case 385 -> {
                    int before = hallPrio, id = ArchitectView.selectedPlot;
                    server.execute(() -> check(SettlementManager.get(server).get(townId).plot(id).priority == before + 1, "Sooner raised the project's priority from " + before));
                }
                case 390 -> screen(mc).keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                case 392 -> { check(ArchitectView.active() && ArchitectView.selectedPlot < 0, "first Esc only deselects"); screen(mc).keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); }
                case 395 -> {
                    check(!ArchitectView.active() && mc.getCameraEntity() == mc.player, "Esc left the view and gave the camera back to the player");
                    check(mc.player.position().distanceTo(bodyAt) < 0.01 && mc.player.getYRot() == bodyYaw, "player position and facing unchanged");
                }
                case 420 -> { check(mc.screen instanceof CityScreen, "returned to the City Manager"); shot(mc, "8_exited"); }
                case 425 -> {
                    mc.setScreen(null);
                    ClientSetup.ARCHITECT.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_KP_8));
                    KeyMapping.resetMapping();
                }
                case 430 -> KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_KP_8));
                case 440 -> {
                    check(ArchitectView.active(), "the Architect view key entered the view");
                    shot(mc, "9_key_entered");
                }
                case 450 -> server.execute(() -> { ServerPlayer p = server.getPlayerList().getPlayer(pid); p.hurt(p.damageSources().generic(), 3); });
                case 465 -> {
                    check(!ArchitectView.active() && mc.getCameraEntity() == mc.player && mc.screen == null, "taking damage closed the view");
                    shot(mc, "10_hurt_exit");
                }
                case 470 -> {
                    ClientSetup.ARCHITECT.setKey(InputConstants.UNKNOWN);
                    KeyMapping.resetMapping();
                    LOG.info("Architect tour {}: save={}, screenshots livingrealms_architect_{}_*", fails == 0 ? "COMPLETE" : "FAILED (" + fails + ")", SAVE, RUN);
                    mc.stop();
                }
                default -> {}
            }
        } catch (Throwable e) {
            LOG.error("Architect tour FAILED at tick " + ticks, e);
            mc.stop();
        }
    }

    static ArchitectScreen screen(Minecraft mc) {
        if (mc.screen instanceof ArchitectScreen a) return a;
        throw new IllegalStateException("Architect screen not open: " + mc.screen);
    }

    /** Hovers the camera above and south-east of a spot and looks at it through the screen centre. */
    static void aim(BlockPos ground) {
        ArchitectView.aimCenter = true;
        ArchitectView.moveTo(Vec3.atCenterOf(ground).add(6, 16, 16));
        ArchitectView.lookAt(Vec3.atCenterOf(ground.below()));
    }

    static void found(ServerPlayer player) {
        try {
            var level = player.serverLevel();
            level.setDayTime(5000);
            player.setYRot(0);
            player.setXRot(10);
            charter = player.blockPosition().offset(0, 0, 3);
            var state = Registry.CHARTER.get().defaultBlockState();
            level.setBlock(charter, state, 3);
            Registry.CHARTER.get().setPlacedBy(level, charter, state, player, new ItemStack(Registry.CHARTER_ITEM.get()));
            Settlement s = SettlementManager.get(player.server).at(level.dimension(), charter);
            townId = s.id;
            LOG.info("Architect tour: founded {} at {}", s.name, charter);
        } catch (Throwable e) { LOG.error("Architect tour FAILED founding", e); }
    }

    /** Open ground away from whatever the town already planned on its own. */
    static void pickSites(Settlement s) {
        double dx = 0, dz = 0;
        for (Plot p : s.plots) { dx += p.center().getX() - charter.getX(); dz += p.center().getZ() - charter.getZ(); }
        double len = Math.hypot(dx, dz);
        if (len < 1) { dx = -1; dz = 0; len = 1; }
        dx /= -len; dz /= -len;
        site = charter.offset((int) Math.round(dx * 16), 0, (int) Math.round(dz * 16));
        spare = charter.offset((int) Math.round(-dz * 18), 0, (int) Math.round(dx * 18));
        LOG.info("Architect tour: {} plots planned already; cottage site {}, spare {}", s.plots.size(), site, spare);
    }

    static void check(boolean ok, String what) {
        if (ok) LOG.info("Architect tour PASS: {}", what);
        else { fails++; LOG.error("Architect tour FAIL: {}", what); }
    }

    static void shot(Minecraft mc, String name) {
        String file = "livingrealms_architect_" + RUN + "_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), m -> LOG.info("Architect tour captured {}", file));
    }

    static void openWorld(Minecraft mc) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(SAVE, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(SAVE, settings, new WorldOptions(20261027L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }

    private ArchitectTour() {}
}
