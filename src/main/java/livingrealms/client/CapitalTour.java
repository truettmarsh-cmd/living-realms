package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.building.*;
import livingrealms.military.UnitType;
import livingrealms.settlement.*;
import livingrealms.settlement.plan.Walls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Opt-in showcase of a fully grown Capital (./gradlew runClient -PcapitalTour -PrunDir=<fresh dir>): a town grown with
 * Auto build through the ordinary planner, both wall rings, the Capital Castle and every building at its highest level,
 * then screenshots from the air, the gates and the streets. The game stays open in creative afterwards to look around.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class CapitalTour {
    /** -PcapitalTerrain: the same capital on generated hills (a site picked for slopes, a stream or two), not superflat. */
    private static final boolean TERRAIN = Boolean.getBoolean("livingrealms.capitalTerrain");
    /**
     * -PcapitalUiTour: the same capital, but with part of the city wall and a few plans still queued, then the City Manager
     * screenshotted on every page and a few interactions, at several window sizes and GUI scales (livingrealms_capitalui_*.png).
     */
    private static final boolean UI = Boolean.getBoolean("livingrealms.capitalUiTour");
    private static final boolean ON = TERRAIN || UI || Boolean.getBoolean("livingrealms.capitalTour");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String SAVE = (TERRAIN ? "livingrealms_capital_hills_" : "livingrealms_capital_") + System.currentTimeMillis();
    private static int waiting, ticks = -1;
    private static boolean opened;
    private static volatile boolean built;
    private static volatile UUID town;
    private static volatile BlockPos center;
    private static volatile int outer = 100, inner = 40;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (!opened && mc.screen instanceof TitleScreen && ++waiting > 40) { opened = true; openWorld(mc); }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID id = mc.player.getUUID();
        ticks++;
        if (ticks == 40) {
            mc.options.renderDistance().set(20);
            server.execute(() -> build(server.getPlayerList().getPlayer(id)));
        }
        if (!built || center == null) return;
        if (ticks < 100000 && ticks > 40) { ticks = 100000; mc.gui.getChat().clearMessages(false); }
        int t = ticks - 100000, cx = center.getX(), cy = center.getY(), cz = center.getZ();
        if (UI) { uiTour(mc, server, id, t); return; }
        // overhead, the quarters from the air, a gate from the road, the castle, the wall walk, then the map
        String[] names = TERRAIN ? new String[]{"top", "southeast", "northwest", "northeast", "southwest", "south-gate", "high-street", "castle", "wall-walk"}
                : new String[]{"top", "southeast", "northwest", "south-gate", "high-street", "castle", "wall-walk"};
        int step = (t - 1) / 180, at = (t - 1) % 180;
        if (t >= 1 && step < names.length) {
            if (at == 0) switch (names[step]) {
                case "top" -> cam(server, id, cx + 0.5, cy + 260, cz + 0.5, 180, 90, false);
                case "southeast" -> cam(server, id, cx + outer + 60, cy + 95, cz + outer + 60, 135, 28, false);
                case "northwest" -> cam(server, id, cx - outer - 60, cy + 95, cz - outer - 60, -45, 28, false);
                case "northeast" -> cam(server, id, cx + outer + 60, cy + 95, cz - outer - 60, 45, 28, false);
                case "southwest" -> cam(server, id, cx - outer - 60, cy + 95, cz + outer + 60, -135, 28, false);
                case "south-gate" -> cam(server, id, cx + 0.5, 6, cz + outer + 26, 180, -2, true);
                case "high-street" -> cam(server, id, cx + 0.5, 9, cz + inner - 12, 180, 6, true);
                case "castle" -> cam(server, id, cx + 46, cy + 34, cz + 46, 135, 24, false);
                default -> cam(server, id, cx + outer + 4, 12, cz + 8, 160, 14, true);
            }
            if (at == 170) shot(mc, names[step]);
            return;
        }
        t -= names.length * 180 - 1400;
        if (t == 1410) server.execute(() -> livingrealms.net.Net.openManager(server.getPlayerList().getPlayer(id), SettlementManager.get(server).get(town), "build"));
        if (t == 1440 && mc.screen instanceof livingrealms.client.ui.CityScreen screen) tab(screen, "MAP");
        if (t == 1500) shot(mc, "map");
        if (t == 1510) tabIfOpen(mc, "OVERVIEW");
        if (t == 1560) shot(mc, "overview");
        if (t == 1570) {
            mc.setScreen(null);
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                p.setGameMode(GameType.CREATIVE);
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
                p.connection.teleport(cx + 0.5, cy + 4, cz + outer + 30, 180, 0);
            });
            LOG.info("CAPITAL TOUR COMPLETE: screenshots livingrealms_capital_*.png; the world {} stays open to explore", SAVE);
        }
    }

    /** Grows the capital through the ordinary planner and builders, paying each project from a full stockpile. */
    private static void build(ServerPlayer player) {
        try {
            ServerLevel level = player.serverLevel();
            level.setDayTime(5000);
            BlockPos site = TERRAIN ? hills(level) : BlockPos.ZERO;
            for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) level.getChunk((site.getX() >> 4) + x, (site.getZ() >> 4) + z);
            center = new BlockPos(site.getX(), Construction.ground(level, site.getX(), site.getZ()) + 1, site.getZ());
            player.setGameMode(GameType.SPECTATOR);
            player.connection.teleport(center.getX() + 0.5, center.getY() + 200, center.getZ() + 0.5, 180, 90);
            Settlement s = new Settlement();
            s.owner = player.getUUID(); s.ownerName = player.getGameProfile().getName();
            s.name = "Highcrown"; s.center = center; s.tier = Tier.CAPITAL; s.capital = true;
            s.style = livingrealms.building.Procedural.biomeStyle(level, center); // as a charter founds it
            s.research.addAll(livingrealms.research.ResearchTree.nodes().keySet());
            s.stock.capacity = 10_000_000;
            SettlementManager.get(player.server).add(s);
            town = s.id;
            CityPlanningSelfTest.stock(s);
            // the hall in the middle of the market place, then the castle around it while the middle is still open
            var hall = BuildingCatalog.get("livingrealms:town_hall");
            var at = s.plan().place(new livingrealms.settlement.plan.WorldTerrain(level), s, hall);
            String err = at == null ? "no level site in the middle" : Planning.plan(level, s, hall, at.origin(), at.rot(), false);
            int n = 0;
            if (err == null) {
                s.plan().frontage.put(s.plots.get(0).id, at.street());
                CityPlanningSelfTest.buildQueued(level, s);
            } else if (TERRAIN) {
                // as in play: the villagers find the hall a site themselves
                LOG.info("Capital tour: town hall left to the villagers ({})", err);
                n = CityPlanningSelfTest.grow(level, s, 1);
            } else throw new IllegalStateException("Town hall: " + err);
            LOG.info("Capital tour: castle {}", String.valueOf(CapitalCastles.plan(level, s)));
            CityPlanningSelfTest.buildQueued(level, s);
            // the UI tour keeps the walls back for a long queue at the end
            if (UI) s.autoWalls = false;
            n += CityPlanningSelfTest.grow(level, s, 110);
            int walls = UI ? 0 : CityPlanningSelfTest.buildWalls(level, s);
            // every building enlarged to the best its type allows
            int upgrades = 0;
            for (int round = 0; round < 3; round++) {
                for (Plot p : new ArrayList<>(s.plots)) {
                    if (p.typeId.equals("road") || Walls.isWallPiece(p) || p.state != Plot.State.BUILT) continue;
                    CityPlanningSelfTest.stock(s);
                    if (Planning.upgrade(level, s, p) == null) upgrades++;
                }
                CityPlanningSelfTest.stock(s);
                CityPlanningSelfTest.buildQueued(level, s);
            }
            if (UI) {
                // a long queue for the Projects page: the old-town wall and part of the city wall stand, the rest is queued,
                // and a few of the player's own plans wait behind it
                walls = CityPlanningSelfTest.buildWalls(level, s, 25);
                int plans = 0;
                for (String type : new String[]{"cottage", "market", "library", "cottage", "tavern", "storehouse"}) {
                    var bt = BuildingCatalog.get("livingrealms:" + type);
                    if (bt == null) continue;
                    var spot = s.plan().place(new livingrealms.settlement.plan.WorldTerrain(level), s, bt);
                    if (spot != null && Planning.plan(level, s, bt, spot.origin(), spot.rot(), false) == null) plans++;
                }
                LOG.info("Capital UI tour: {} plans queued behind the walls", plans);
            }
            Simulation.recompute(s);
            s.population = Math.max(s.population, s.d.housing);
            Simulation.recompute(s);
            var lines = Walls.unlocked(s);
            if (!lines.isEmpty()) { inner = lines.get(0).a(); outer = lines.get(lines.size() - 1).a(); }
            for (int i = 0; i < 16; i++)
                WarShowcase.soldier(level, s, i % 4 == 3 ? UnitType.BOWMAN : i % 4 == 2 ? UnitType.SHIELDBEARER : UnitType.FOOTMAN,
                        new BlockPos(center.getX() - 7 + (i % 8) * 2, center.getY(), center.getZ() + 18 + (i / 8) * 2));
            s.dirty = true;
            long buildings = s.plots.stream().filter(p -> !p.typeId.equals("road") && !Walls.isWallPiece(p) && p.state == Plot.State.BUILT).count();
            LOG.info("Capital tour: {} buildings ({} grown, {} upgrades), {} wall pieces, walls at {} and {}, population {}, radius {} · {} · {}",
                    buildings, n, upgrades, walls, inner, outer, s.population, s.radius(), Walls.status(s), s.castleStatus);
            if (TERRAIN) terrainReport(level, s);
            built = true;
        } catch (Throwable e) {
            LOG.error("CAPITAL TOUR FAILED", e);
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
        }
    }

    /** With {@code overGround}, y is a height above the ground under the camera (hills would bury a fixed height). */
    private static void cam(MinecraftServer server, UUID id, double x, double yIn, double z, float yaw, float pitch, boolean overGround) {
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            double y = overGround ? Construction.ground(p.serverLevel(), (int) Math.floor(x), (int) Math.floor(z)) + 1 + yIn : yIn;
            p.setGameMode(GameType.SPECTATOR);
            p.connection.teleport(x, y, z, yaw, pitch);
        });
    }

    /**
     * A hilly site near spawn from the generator's own height estimate (no chunks generated): real slopes across the town,
     * little open water, and a middle gentle enough for the castle (its corners within 4 blocks of each other).
     */
    private static BlockPos hills(ServerLevel level) {
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        BlockPos spawn = level.getSharedSpawnPos(), best = spawn;
        double bestScore = -1e9;
        for (int gx = -10; gx <= 10; gx++) for (int gz = -10; gz <= 10; gz++) {
            int ox = spawn.getX() + gx * 192, oz = spawn.getZ() + gz * 192;
            double sum = 0, sq = 0; int n = 0, wet = 0;
            for (int i = -4; i <= 4; i++) for (int j = -4; j <= 4; j++) {
                int x = ox + i * 26, z = oz + j * 26;
                int floor = gen.getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
                int top = gen.getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                if (top > floor) wet++;
                sum += floor; sq += (double) floor * floor; n++;
            }
            double mean = sum / n, sd = Math.sqrt(Math.max(0, sq / n - mean * mean));
            int[] corner = new int[4]; int k = 0;
            for (int dx = -20; dx <= 20; dx += 40) for (int dz = -20; dz <= 20; dz += 40)
                corner[k++] = gen.getBaseHeight(ox + dx, oz + dz, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
            java.util.Arrays.sort(corner);
            if (corner[3] - corner[0] > 3 || mean < level.getSeaLevel() + 2) continue;
            double score = -Math.abs(sd - 8) - Math.max(0, wet - 4) * 3 + Math.min(wet, 3);
            if (score > bestScore) { bestScore = score; best = new BlockPos(ox, 0, oz); }
        }
        LOG.info("Capital tour: hills site {} (score {})", best, bestScore);
        return best;
    }

    /** What the ground was like and how the plan coped: relief, foundations, wall gaps and refusals. */
    private static void terrainReport(ServerLevel level, Settlement s) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int x = -100; x <= 100; x += 4) for (int z = -100; z <= 100; z += 4) {
            int y = Construction.ground(level, s.center.getX() + x, s.center.getZ() + z);
            lo = Math.min(lo, y); hi = Math.max(hi, y);
        }
        int gaps = 0, pieces = 0, raised = 0, buildings = 0;
        for (Plot p : s.plots) {
            if (Walls.isWallPiece(p)) { pieces++; if (p.blueprint() != null && p.blueprint().size() <= 1) gaps++; continue; }
            if (p.typeId.equals("road") || p.state != Plot.State.BUILT) continue;
            buildings++;
            if (p.foundation != null && java.util.Arrays.stream(p.foundation).sum() > 0) raised++;
        }
        LOG.info("Capital tour terrain: ground {} to {} within the city wall; {} buildings, {} on built-up foundations; {} wall pieces, {} left open for water or cliff; {}",
                lo, hi, buildings, raised, pieces, gaps, Walls.status(s));
    }

    /** Window size (in framebuffer pixels) and GUI scale for each pass, and which pages each pass shows. */
    private static final Object[][] UI_PASSES = {
            {"1080p-gs3", 1920, 1080, 3, true}, {"1080p-gs2", 1920, 1080, 2, false}, {"720p-gs2", 1280, 720, 2, false}, {"small-gs2", 1024, 640, 2, false}};
    private static List<Object[]> uiSteps;
    private static int uiStart = -1;

    /** The City Manager on every page at each window size and GUI scale, one step every 40 ticks. */
    private static void uiTour(Minecraft mc, MinecraftServer server, UUID id, int t) {
        if (t == 1) cam(server, id, center.getX() + 0.5, center.getY() + 200, center.getZ() + 0.5, 180, 90, false);
        if (t < 400) return; // chunks under the town load for the map
        if (uiSteps == null) {
            uiSteps = new ArrayList<>();
            for (Object[] pass : UI_PASSES) {
                String tag = (String) pass[0];
                int w = (Integer) pass[1], h = (Integer) pass[2], gs = (Integer) pass[3];
                boolean all = (Boolean) pass[4];
                step(tag + "-setup", c -> {}, () -> { window(mc, w, h); mc.options.guiScale().set(gs); mc.resizeDisplay(); }, false);
                step(tag + "-overview", c -> { c.tour("OVERVIEW", 0, null); c.tourScroll("overview", 0); }, null, true);
                if (all) step(tag + "-overview-scrolled", c -> { c.tour("OVERVIEW", 0, null); c.tourScroll("overview", 999); }, null, true);
                step(tag + "-map", c -> { c.tour("MAP", 0, null); c.tourMap(1, 0, 0); c.tourFocus(null); }, null, true);
                if (all) {
                    step(tag + "-map-hover", c -> { c.tour("MAP", 0, null); c.tourMap(1, 0, 0); }, null, false);
                    step(tag + "-map-hover", c -> c.tourHoverPlot("town_hall"), null, true);
                    step(tag + "-map-zoom", c -> { c.tour("MAP", 0, null); c.tourMap(3, 0, 0); }, null, false);
                    step(tag + "-map-zoom", c -> c.tourHoverPlot("cottage"), null, true);
                    step(tag + "-map-selected", c -> { c.tour("MAP", 0, null); c.tourMap(1.6f, 0, 0); c.tourSelectPlot("town_hall"); c.tourMouse(0, 0); }, null, true);
                    step(tag + "-map-wall", c -> { c.tour("MAP", 0, null); c.tourMap(1, 0, 0); c.tourSelectPlot("gatehouse"); }, null, true);
                    step(tag + "-map-focus", c -> { c.tour("MAP", 0, null); c.tourMap(1.25f, 0, 0); c.tourFocus("HOUSING"); }, null, true);
                }
                step(tag + "-build", c -> { c.tour("BUILD", 0, null); c.tourCategory("ALL"); }, null, true);
                if (all) {
                    step(tag + "-build-housing", c -> { c.tour("BUILD", 0, null); c.tourCategory("HOUSING"); }, null, true);
                    step(tag + "-build-wall", c -> { c.tour("BUILD", 0, null); c.tourCategory("WALL"); }, null, true);
                    step(tag + "-build-scrolled", c -> { c.tour("BUILD", 0, null); c.tourCategory("ALL"); c.tourScroll("blist", 3000); }, null, true);
                }
                step(tag + "-projects", c -> { c.tour("PROJECTS", 0, null); c.tourScroll("proj", 0); }, null, true);
                if (all) {
                    step(tag + "-projects-scrolled", c -> { c.tour("PROJECTS", 0, null); c.tourFocus(null); c.tourScroll("proj", 900); }, null, true);
                    step(tag + "-projects-open", c -> { c.tour("PROJECTS", 0, null); c.tourOpenGroup("q:City wall"); c.tourOpenGroup("c:HOUSING"); c.tourScroll("proj", 0); }, null, true);
                    step(tag + "-projects-selected", c -> { c.tour("PROJECTS", 0, null); c.tourScroll("proj", 0); c.tourSelectPlot("wall"); }, null, true);
                    step(tag + "-stockpile", c -> c.tour("STORAGE", 0, null), null, true);
                    step(tag + "-research", c -> c.tour("RESEARCH", 0, null), null, true);
                    step(tag + "-military", c -> c.tour("MILITARY", 0, null), null, true);
                    step(tag + "-blacksmith", c -> c.tour("FORGE", 2, null), null, true);
                    step(tag + "-kingdom", c -> c.tour("KINGDOM", 0, null), null, true);
                    // a moment back at City rank with some research unlearnt: the rank card's progress and locked buildings
                    String[] unlearn = {"royal_charter", "banking", "engineering", "logistics", "cavalry", "deep_mining", "goldsmithing"};
                    step(tag + "-city-setup", c -> c.tour("OVERVIEW", 0, null), () -> server.execute(() -> {
                        Settlement s = SettlementManager.get(server).get(town);
                        s.tier = Tier.CITY;
                        for (String r : unlearn) s.research.remove(r);
                        Simulation.recompute(s);
                        livingrealms.net.Net.openManager(server.getPlayerList().getPlayer(id), s, "");
                    }), false);
                    step(tag + "-city-overview", c -> { c.tour("OVERVIEW", 0, null); c.tourScroll("overview", 0); }, null, true);
                    step(tag + "-city-build", c -> { c.tour("BUILD", 0, null); c.tourCategory("ALL"); c.tourScroll("blist", 99999); }, null, true);
                    step(tag + "-city-build-locked", c -> { c.tour("BUILD", 0, null); c.tourCategory("STABLE"); }, null, true);
                    step(tag + "-city-restore", c -> c.tour("OVERVIEW", 0, null), () -> server.execute(() -> {
                        Settlement s = SettlementManager.get(server).get(town);
                        s.tier = Tier.CAPITAL;
                        s.research.addAll(livingrealms.research.ResearchTree.nodes().keySet());
                        Simulation.recompute(s);
                        livingrealms.net.Net.openManager(server.getPlayerList().getPlayer(id), s, "");
                    }), false);
                }
            }
            server.execute(() -> livingrealms.net.Net.openManager(server.getPlayerList().getPlayer(id), SettlementManager.get(server).get(town), "build"));
            uiStart = t + 40;
        }
        int k = (t - uiStart) / 40, at = (t - uiStart) % 40;
        if (t < uiStart) return;
        if (k >= uiSteps.size()) {
            if (at == 0 && k == uiSteps.size()) LOG.info("CAPITAL UI TOUR COMPLETE: {} steps, screenshots livingrealms_capitalui_*.png", uiSteps.size());
            return;
        }
        Object[] st = uiSteps.get(k);
        if (at == 0) {
            if (st[2] != null) ((Runnable) st[2]).run();
            if (mc.screen instanceof livingrealms.client.ui.CityScreen screen) {
                screen.tourMouse(-50, -50);
                @SuppressWarnings("unchecked") var f = (java.util.function.Consumer<livingrealms.client.ui.CityScreen>) st[1];
                f.accept(screen);
            }
        }
        // hover steps put the mouse on the map after the page has drawn once
        if (at == 30 && (Boolean) st[3]) {
            String file = "livingrealms_capitalui_" + st[0] + ".png";
            Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> LOG.info("Capital UI tour captured {}", file));
        }
    }

    private static void step(String name, java.util.function.Consumer<livingrealms.client.ui.CityScreen> screen, Runnable before, boolean shot) {
        uiSteps.add(new Object[]{name, screen, before, shot});
    }

    /** Resizes the window so its framebuffer is w × h pixels (on high-DPI screens the window itself is smaller). */
    private static void window(Minecraft mc, int w, int h) {
        var win = mc.getWindow();
        double k = win.getScreenWidth() <= 0 ? 1 : win.getWidth() / (double) win.getScreenWidth();
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(win.getWindow(), (int) Math.round(w / k), (int) Math.round(h / k));
    }

    private static void tabIfOpen(Minecraft mc, String name) {
        if (mc.screen instanceof livingrealms.client.ui.CityScreen screen) tab(screen, name);
    }

    private static void tab(livingrealms.client.ui.CityScreen screen, String name) {
        try {
            var tab = Class.forName("livingrealms.client.ui.CityScreen$Tab");
            var m = livingrealms.client.ui.CityScreen.class.getDeclaredMethod("switchTab", tab);
            m.setAccessible(true);
            for (Object v : tab.getEnumConstants()) if (((Enum<?>) v).name().equals(name)) m.invoke(screen, v);
        } catch (ReflectiveOperationException e) { LOG.warn("Capital tour: no {} tab", name, e); }
    }

    private static void shot(Minecraft mc, String name) {
        String file = "livingrealms_capital_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> LOG.info("Capital tour captured {}", file));
    }

    private static void openWorld(Minecraft mc) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(SAVE, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(SAVE, settings, new WorldOptions(20261029L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(TERRAIN ? WorldPresets.NORMAL : WorldPresets.FLAT).value().createWorldDimensions());
    }

    private CapitalTour() {}
}
