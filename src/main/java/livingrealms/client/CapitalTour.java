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
    private static final boolean ON = TERRAIN || Boolean.getBoolean("livingrealms.capitalTour");
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
            n += CityPlanningSelfTest.grow(level, s, 110);
            int walls = CityPlanningSelfTest.buildWalls(level, s);
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
        int gaps = 0, pieces = 0, buildings = 0, onStreets = 0;
        var plan = s.plan();
        for (Plot p : s.plots) {
            if (Walls.isWallPiece(p)) { pieces++; if (p.blueprint() != null && p.blueprint().size() <= 1) gaps++; continue; }
            if (p.typeId.equals("road") || p.state != Plot.State.BUILT) continue;
            buildings++;
            if (plan.frontage.containsKey(p.id)) onStreets++;
        }
        // steps of more than one block between neighbouring paved road columns
        int cliffs = 0, roadCols = 0;
        java.util.Map<Long, Integer> road = new java.util.HashMap<>();
        for (Plot p : s.plots) {
            if (!p.typeId.equals("road") || p.blueprint() == null) continue;
            Blueprint bp = p.blueprint();
            for (int i = 0; i < bp.size(); i++) if (!bp.state(i).isAir()) {
                BlockPos w = bp.worldPos(i, p.origin, p.rotation);
                road.merge(BlockPos.asLong(w.getX(), 0, w.getZ()), w.getY(), Math::max);
            }
        }
        for (var e : road.entrySet()) {
            int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
            roadCols++;
            for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                Integer o = road.get(BlockPos.asLong(x + d[0], 0, z + d[1]));
                if (o != null && Math.abs(o - e.getValue()) > 1) { cliffs++; break; }
            }
        }
        LOG.info("Capital tour terrain: ground {} to {} within the city wall; {} buildings, {} on its streets, {} off-street; lots refused {} steep, {} wet, {} built on; {} levelled; road columns {}, {} with a step over 1; {} wall pieces, {} left open for water or cliff; {}",
                lo, hi, buildings, onStreets, buildings - onStreets, plan.refusedSteep, plan.refusedWet, plan.refusedBlocked, plan.levelled, roadCols, cliffs, pieces, gaps, Walls.status(s));
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
