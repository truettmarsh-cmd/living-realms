package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.Config;
import livingrealms.LivingRealms;
import livingrealms.Registry;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.Construction;
import livingrealms.client.ui.CityScreen;
import livingrealms.net.Net;
import livingrealms.research.ResearchTree;
import livingrealms.settlement.*;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Rotation;
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
 * Dev-only test run (./gradlew runClient -Ptour): founds a town in a fresh world, plans buildings and roads, lets the
 * builders work, orders armour, trains soldiers, raises the alarm, and screenshots the world and every City Manager
 * page into run/screenshots before quitting.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class DevTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.tour");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String SAVE = "livingrealms_tour";
    private static int t = -1, idle;
    private static boolean opened;
    private static BlockPos home = BlockPos.ZERO;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (!ON || e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (opened && mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen d) {
                LOG.error("livingrealms tour: disconnected: {}", d.getNarrationMessage().getString());
                mc.stop();
                return;
            }
            if (opened && ++idle > 20 * 300) {
                LOG.error("livingrealms tour: timed out on screen {}", mc.screen);
                mc.stop();
                return;
            }
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && ++idle > 40) {
                opened = true;
                openWorld(mc);
            }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(SAVE)) return;
        t++;
        UUID me = mc.player.getUUID();
        switch (t) {
            case 40 -> server.execute(() -> setup(server.getPlayerList().getPlayer(me)));
            case 80 -> server.execute(() -> plan(server.getPlayerList().getPlayer(me)));
            case 100 -> server.execute(() -> Net.openManager(server.getPlayerList().getPlayer(me), town(server)));
            case 130 -> shot(mc, "ui_overview");
            case 132 -> tab(mc, "BUILD", 0, "livingrealms:town_hall");
            case 170 -> shot(mc, "ui_build_townhall");
            case 172 -> tab(mc, "BUILD", 0, "livingrealms:barracks");
            case 210 -> shot(mc, "ui_build_barracks");
            case 212 -> tab(mc, "BUILD", 0, firstTemplate());
            case 250 -> shot(mc, "ui_build_template");
            case 252 -> tab(mc, "PROJECTS", 0, null);
            case 270 -> shot(mc, "ui_projects");
            case 272 -> tab(mc, "MAP", 0, null);
            case 300 -> shot(mc, "ui_map");
            case 302 -> tab(mc, "STORAGE", 0, null);
            case 320 -> shot(mc, "ui_storage");
            case 322 -> tab(mc, "RESEARCH", 0, "plate_armour");
            case 340 -> shot(mc, "ui_research");
            case 342 -> tab(mc, "FORGE", 2, "iron");
            case 360 -> shot(mc, "ui_forge");
            case 362 -> tab(mc, "MILITARY", 1, null);
            case 380 -> shot(mc, "ui_recruit");
            case 382 -> tab(mc, "MILITARY", 3, null);
            case 400 -> shot(mc, "ui_kits");
            case 402 -> {
                mc.setScreen(null);
                mc.options.hideGui = true;
                server.execute(() -> look(server.getPlayerList().getPlayer(me), home.offset(-28, 22, 34), home.offset(0, 0, 4)));
            }
            case 440 -> shot(mc, "world_planned");
            case 1400 -> shot(mc, "world_building");
            case 2600 -> server.execute(() -> report(server, "mid"));
            case 3800 -> shot(mc, "world_built");
            case 3802 -> server.execute(() -> look(server.getPlayerList().getPlayer(me), home.offset(26, 16, -24), home.offset(0, 2, 6)));
            case 3840 -> shot(mc, "world_built_2");
            case 3842 -> server.execute(() -> look(server.getPlayerList().getPlayer(me), home.offset(4, 3, 22), home.offset(0, 3, 6)));
            case 3880 -> shot(mc, "world_street");
            case 3882 -> server.execute(() -> military(server, server.getPlayerList().getPlayer(me)));
            case 4200 -> server.execute(() -> report(server, "military"));
            case 4202 -> server.execute(() -> look(server.getPlayerList().getPlayer(me), home.offset(-6, 6, 30), home.offset(0, 1, 18)));
            case 4240 -> shot(mc, "world_soldiers");
            case 4242 -> {
                mc.options.hideGui = false;
                server.execute(() -> Net.openManager(server.getPlayerList().getPlayer(me), town(server)));
            }
            case 4260 -> tab(mc, "MILITARY", 0, null);
            case 4290 -> shot(mc, "ui_garrison");
            case 4292 -> tab(mc, "OVERVIEW", 0, null);
            case 4310 -> shot(mc, "ui_overview_late");
            case 4312 -> tab(mc, "MAP", 0, null);
            case 4340 -> shot(mc, "ui_map_late");
            case 4342 -> {
                mc.setScreen(null);
                server.execute(() -> placing(server.getPlayerList().getPlayer(me)));
            }
            case 4360 -> Placement.startBuilding(town(server).id, town(server).style, "livingrealms:library");
            case 4400 -> shot(mc, "world_placing");
            case 4402 -> {
                Placement.cancel();
                server.execute(() -> aiTowns(server, server.getPlayerList().getPlayer(me)));
            }
            case 4700 -> shot(mc, "world_ai_town");
            case 4702 -> server.execute(() -> report(server, "end"));
            case 4720 -> {
                LOG.info("livingrealms tour: done");
                mc.stop();
            }
            default -> {}
        }
    }

    static Settlement town(MinecraftServer server) {
        for (Settlement s : SettlementManager.get(server).all()) if (!s.isAi()) return s;
        return null;
    }

    static void tab(Minecraft mc, String tab, int sub, String select) {
        if (mc.screen instanceof CityScreen cs) cs.tour(tab, sub, select);
    }

    static String firstTemplate() {
        for (var t : BuildingCatalog.types().values()) if (!t.procedural() && t.style.equals("minecraft:plains") && t.beds > 0) return t.id;
        return "livingrealms:cottage";
    }

    static void setup(ServerPlayer sp) {
        ServerLevel lvl = sp.serverLevel();
        lvl.setDayTime(5000);
        Config.BUILD_INTERVAL.set(1);
        Config.SIM_INTERVAL.set(100);
        Config.AI_MIN_DISTANCE.set(96); // the flat world puts a village ~150 blocks from spawn
        LOG.info("livingrealms tour: {} building types, {} research, {} smithing patterns", BuildingCatalog.types().size(),
                ResearchTree.nodes().size(), livingrealms.smithing.Smithing.patterns().size());
        java.util.Map<String, Integer> perStyle = new java.util.TreeMap<>();
        BuildingCatalog.types().values().forEach(b -> perStyle.merge(b.style + "/" + b.category, 1, Integer::sum));
        LOG.info("livingrealms tour: catalog {}", perStyle);
        home = sp.blockPosition().offset(0, 0, 0);
        home = new BlockPos(home.getX(), Construction.ground(lvl, home.getX(), home.getZ()) + 1, home.getZ());
        lvl.setBlockAndUpdate(home, Registry.CHARTER.get().defaultBlockState());
        Registry.CHARTER.get().setPlacedBy(lvl, home, lvl.getBlockState(home), sp, new ItemStack(Registry.CHARTER_ITEM.get()));
        Settlement s = town(sp.server);
        LOG.info("livingrealms tour: founded {} style {} center {}", s.name, s.style, s.center);
        // a generous stockpile so the builders have something to work with
        Item[] mats = {Items.OAK_PLANKS, Items.OAK_LOG, Items.COBBLESTONE, Items.STONE_BRICKS, Items.DARK_OAK_STAIRS, Items.DARK_OAK_SLAB,
                Items.DARK_OAK_PLANKS, Items.OAK_STAIRS, Items.OAK_SLAB, Items.OAK_FENCE, Items.GLASS_PANE, Items.WHITE_TERRACOTTA, Items.DIRT,
                Items.STONE_BRICK_STAIRS, Items.STONE_BRICK_SLAB, Items.COBBLESTONE_WALL, Items.OAK_DOOR, Items.TORCH, Items.LANTERN, Items.RED_BED,
                Items.RED_CARPET, Items.BOOKSHELF, Items.BARREL, Items.CHEST, Items.LADDER, Items.HAY_BLOCK, Items.OAK_FENCE_GATE, Items.WHEAT_SEEDS,
                Items.CARROT, Items.POTATO, Items.IRON_INGOT, Items.LEATHER, Items.STICK, Items.STRING, Items.EMERALD, Items.BREAD, Items.GRAVEL,
                Items.STONE_BRICK_WALL, Items.WATER_BUCKET, Items.ANVIL, Items.BLAST_FURNACE, Items.SMOKER, Items.SMITHING_TABLE, Items.GRINDSTONE,
                Items.CAULDRON, Items.CAMPFIRE, Items.CHAIN, Items.LECTERN, Items.RED_BANNER, Items.OAK_PRESSURE_PLATE, Items.CARVED_PUMPKIN,
                Items.TARGET, Items.FLOWER_POT, Items.FURNACE, Items.CRAFTING_TABLE, Items.CARTOGRAPHY_TABLE, Items.STONECUTTER, Items.SCAFFOLDING,
                Items.BELL, Items.COMPOSTER, Items.OAK_TRAPDOOR, Items.FLINT, Items.FEATHER, Items.OAK_SAPLING, Items.WHITE_WOOL};
        s.stock.capacity = 1_000_000;
        for (Item it : mats) s.stock.force(it, 900);
        s.population = 22;
        for (String r : new String[]{"leatherworking", "smithing", "iron_working", "chainmail", "shieldcraft", "drill", "archery", "masonry", "carpentry",
                "architecture", "fortifications", "scholarship"}) s.research.add(r);
        s.tier = Tier.TOWN;
        s.activeResearch = "steelmaking";
        s.researchProgress = 120;
        Simulation.recompute(s);
    }

    static void plan(ServerPlayer sp) {
        ServerLevel lvl = sp.serverLevel();
        Settlement s = town(sp.server);
        int x = home.getX(), z = home.getZ(), y = home.getY() - 1;
        log(Planning.plan(lvl, s, BuildingCatalog.get("livingrealms:town_hall"), new BlockPos(x - 6, y, z + 4), Rotation.NONE, false), "town hall");
        log(Planning.plan(lvl, s, BuildingCatalog.get("livingrealms:barracks"), new BlockPos(x + 12, y, z + 4), Rotation.NONE, false), "barracks");
        log(Planning.plan(lvl, s, BuildingCatalog.get("livingrealms:smithy"), new BlockPos(x - 20, y, z + 4), Rotation.NONE, false), "smithy");
        log(Planning.plan(lvl, s, BuildingCatalog.get("livingrealms:cottage"), new BlockPos(x - 6, y, z + 26), Rotation.CLOCKWISE_180, false), "cottage");
        log(Planning.plan(lvl, s, BuildingCatalog.get(firstTemplate()), new BlockPos(x + 6, y, z + 26), Rotation.CLOCKWISE_180, false), "template house");
        log(Planning.plan(lvl, s, BuildingCatalog.get("livingrealms:watchtower"), new BlockPos(x + 30, y, z + 4), Rotation.NONE, false), "watchtower");
        log(Planning.plan(lvl, s, BuildingCatalog.get("livingrealms:farm_field"), new BlockPos(x - 20, y, z + 26), Rotation.NONE, false), "farm");
        log(Planning.plan(lvl, s, BuildingCatalog.get("livingrealms:storehouse"), new BlockPos(x + 20, y, z + 26), Rotation.CLOCKWISE_180, false), "storehouse");
        log(Planning.road(lvl, s, new BlockPos(x - 24, y, z + 21), new BlockPos(x + 34, y, z + 21), 3, "gravel"), "road");
        for (Plot p : s.plots) p.priority = 0;
        Simulation.recompute(s);
        Simulation.step(sp.server, s);
    }

    static void log(String err, String what) {
        LOG.info("livingrealms tour: plan {} -> {}", what, err == null ? "ok" : err);
    }

    static void report(MinecraftServer server, String when) {
        for (Settlement s : SettlementManager.get(server).all()) {
            StringBuilder sb = new StringBuilder();
            for (Plot p : s.plots) sb.append(p.typeId.replace("livingrealms:", "")).append(':').append(p.state).append(' ')
                    .append((int) (p.progress() * 100)).append("% ").append(p.status.isEmpty() ? "" : "[" + p.status + "] ");
            LOG.info("livingrealms tour [{}]: {} ai={} tier={} pop={} housing={} happy={} soldiers={} training={} orders={} wealth={} plots: {}",
                    when, s.name, s.isAi(), s.tier, s.population, s.d.housing, (int) s.happiness, s.soldiers.size(), s.training.size(),
                    s.orders.size(), (int) s.wealth, sb);
            if (!s.log.isEmpty()) LOG.info("livingrealms tour [{}]: {} log: {}", when, s.name, String.join(" | ", s.log.stream().limit(12).toList()));
        }
    }

    static void military(MinecraftServer server, ServerPlayer sp) {
        Settlement s = town(server);
        CompoundTag order = new CompoundTag();
        order.putString("pattern", "iron");
        order.putString("piece", "SET");
        order.putInt("qty", 3);
        Actions.handle(sp, s.id, "order", order);
        order = new CompoundTag();
        order.putString("pattern", "iron");
        order.putString("piece", "SWORD");
        order.putInt("qty", 4);
        Actions.handle(sp, s.id, "order", order);
        // two iron sets ready already so the soldiers get kitted out
        for (Item it : new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS}) s.stock.force(it, 2);
        s.stock.force(Items.IRON_SWORD, 4);
        s.stock.force(Items.BOW, 2);
        s.stock.force(Items.SHIELD, 2);
        s.stock.force(Items.ARROW, 128);
        for (var kit : livingrealms.military.UnitType.values()) s.kit(kit).armor = "iron";
        CompoundTag train = new CompoundTag();
        train.putString("unit", "FOOTMAN");
        train.putInt("qty", 2);
        Actions.handle(sp, s.id, "train", train);
        train.putString("unit", "BOWMAN");
        train.putInt("qty", 2);
        Actions.handle(sp, s.id, "train", train);
        train.putString("unit", "SHIELDBEARER");
        train.putInt("qty", 1);
        Actions.handle(sp, s.id, "train", train);
        for (Settlement.Training tr : s.training) tr.progress = 0.99f;
        CompoundTag stance = new CompoundTag();
        stance.putString("stance", "MUSTER");
        Actions.handle(sp, s.id, "stance", stance);
        for (int i = 0; i < 3; i++) Simulation.step(server, s);
        ServerLevel lvl = sp.serverLevel();
        for (int i = 0; i < 4; i++) EntityType.ZOMBIE.spawn(lvl, home.offset(-10 + i * 2, 0, 40), MobSpawnType.COMMAND);
        server.setDifficulty(Difficulty.EASY, true);
    }

    static void placing(ServerPlayer sp) {
        sp.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Registry.PLANNER.get()));
        look(sp, home.offset(-34, 8, 8), home.offset(-40, 0, 22));
    }

    static void aiTowns(MinecraftServer server, ServerPlayer sp) {
        Settlement ai = null;
        for (Settlement s : SettlementManager.get(server).all()) if (s.isAi()) { ai = s; break; }
        if (ai == null) {
            // walk towards the nearest village so it loads and is discovered
            BlockPos v = sp.serverLevel().findNearestMapStructure(net.minecraft.tags.StructureTags.VILLAGE, home, 100, false);
            LOG.info("livingrealms tour: nearest village {}", v);
            if (v != null) sp.teleportTo(v.getX() + 0.5, 120, v.getZ() + 0.5);
            return;
        }
        ai.wealth = 2000;
        look(sp, ai.center.offset(-30, 30, -30), ai.center);
    }

    static void look(ServerPlayer sp, BlockPos from, BlockPos at) {
        sp.setGameMode(GameType.SPECTATOR);
        double dx = at.getX() - from.getX(), dy = at.getY() - from.getY(), dz = at.getZ() - from.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        sp.connection.teleport(from.getX() + 0.5, from.getY(), from.getZ() + 0.5, yaw, pitch);
    }

    static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "livingrealms_" + name + ".png", mc.getMainRenderTarget(), c -> LOG.info("livingrealms tour: shot {}", name));
    }

    static void openWorld(Minecraft mc) {
        if (mc.getLevelSource().levelExists(SAVE)) {
            mc.createWorldOpenFlows().loadLevel(mc.screen, SAVE);
            return;
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(SAVE, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(SAVE, settings, new WorldOptions(20261023L, true, false),
                ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }

    private DevTour() {}
}
