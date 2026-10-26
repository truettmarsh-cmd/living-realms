package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.client.ui.CityScreen;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.DragonRiders;
import livingrealms.military.Stance;
import livingrealms.military.WeaponProficiency;
import livingrealms.military.LegendaryPerk;
import livingrealms.military.UnitType;
import livingrealms.net.Net;
import livingrealms.settlement.Actions;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Simulation;
import livingrealms.settlement.Tier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
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
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/** Opt-in UI captures. Every run creates its own world and filenames; nothing is deleted. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class MilitaryUiTour {
    private static final boolean CASTLE_ONLY = Boolean.getBoolean("livingrealms.castleUiTest");
    private static final boolean ON = Boolean.getBoolean("livingrealms.militaryUiTest") || CASTLE_ONLY;
    private static volatile boolean castleReady;
    private static final Logger LOG = LogUtils.getLogger();
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static final String SAVE = "livingrealms_military_ui_" + RUN;
    private static int ticks = -1, waiting;
    private static boolean opened;
    private static volatile UUID townId, soldierId;
    private static volatile UUID dragonId, riderId;
    private static volatile double dragonDistanceBefore;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (opened && mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen d) {
                LOG.error("Military UI tour disconnected: {}", d.getNarrationMessage().getString()); mc.stop(); return;
            }
            if (++waiting > 20 * 300) { LOG.error("Military UI tour timed out: {}", mc.screen); mc.stop(); return; }
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && waiting > 40) {
                opened = true; openWorld(mc);
            }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(SAVE)) return;
        UUID playerId = mc.player.getUUID();
        if (ticks >= 740 && !castleReady) return;
        if (CASTLE_ONLY && ticks == -1) ticks = 739;
        ticks++;
        switch (ticks) {
            case 40 -> server.execute(() -> setup(server.getPlayerList().getPlayer(playerId)));
            case 65 -> server.execute(() -> {
                var town = SettlementManager.get(server).get(townId);
                if (town != null) Net.openManager(server.getPlayerList().getPlayer(playerId), town);
            });
            case 85 -> { if (mc.screen instanceof CityScreen city) city.showSoldier(soldierId); }
            case 110, 140, 175, 205, 240, 270, 300, 330 -> clearHover(mc);
            case 115 -> shot(mc, "skills_before_promotion");
            case 120 -> scrollDown(mc);
            case 145 -> shot(mc, "royal_guard_promotion");
            case 150 -> server.execute(() -> {
                CompoundTag data = new CompoundTag(); data.putUUID("soldier", soldierId); data.putBoolean("on", true);
                Actions.handle(server.getPlayerList().getPlayer(playerId), townId, "royalGuard", data);
                var town = SettlementManager.get(server).get(townId);
                if (town == null || town.soldiers.isEmpty() || !town.soldiers.get(0).royalGuard)
                    LOG.error("Military UI tour: Royal Guard promotion FAILED");
                else LOG.info("Military UI tour: Royal Guard promotion succeeded; emeralds={}", town.stock.count(Items.EMERALD));
            });
            case 180 -> shot(mc, "royal_guard_promoted");
            case 185 -> mc.setScreen(new CityScreen("military"));
            case 210 -> shot(mc, "royal_guard_roster");
            case 215 -> {
                if (mc.screen instanceof CityScreen city)
                    city.tour("FORGE", 2, "equipment_set/iceandfire/dragonsteel_fire");
            }
            case 245 -> shot(mc, "dragonsteel_smithing");
            case 250 -> {
                if (mc.screen instanceof CityScreen city) { city.showSoldier(soldierId); city.showSoldierTab(1); }
            }
            case 275 -> shot(mc, "weapon_proficiency");
            case 280 -> { if (mc.screen instanceof CityScreen city) city.showSoldierTab(2); }
            case 305 -> shot(mc, "legendary_perks");
            case 310 -> scrollDown(mc);
            case 335 -> shot(mc, "legendary_perks_more");
            case 340 -> { if (mc.screen instanceof CityScreen city) city.showSoldierTab(3); }
            case 360 -> shot(mc, "recruit_origin");
            case 365 -> {
                mc.setScreen(null);
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    player.teleportTo(player.getX() + 12, player.getY(), player.getZ());
                });
            }
            case 440 -> server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                var recruit = livingrealms.military.SoldierProgression.findEntity(server, soldierId);
                LOG.info("Military UI tour: personal escort following={}, distanceToOwner={}",
                        recruit != null && RecruitsBridge.isFollowing(recruit), recruit == null ? -1 : recruit.distanceTo(player));
            });
            case 450 -> server.execute(() -> setupDragon(server.getPlayerList().getPlayer(playerId)));
            case 490 -> server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (dragonId == null) return;
                var dragon = livingrealms.military.SoldierProgression.findEntity(server, dragonId);
                player.teleportTo(player.getX() + 20, player.getY(), player.getZ());
                dragonDistanceBefore = dragon.distanceTo(player);
                LOG.info("Military UI tour: dragon escort starting distance={}", dragonDistanceBefore);
            });
            case 690 -> server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (dragonId == null || riderId == null) return;
                var dragon = livingrealms.military.SoldierProgression.findEntity(server, dragonId);
                var rider = livingrealms.military.SoldierProgression.findEntity(server, riderId);
                boolean mounted = dragon != null && rider != null && rider.getVehicle() == dragon;
                double after = dragon == null ? -1 : dragon.distanceTo(player);
                LOG.info("Military UI tour: dragon escort mounted={}, following={}, distanceBefore={}, distanceAfter={}, approached={}",
                        mounted, rider != null && RecruitsBridge.isFollowing(rider), dragonDistanceBefore, after,
                        after >= 0 && after < dragonDistanceBefore - 2);
                if (dragon != null) {
                    double x = dragon.getX() - 16, y = dragon.getY() + 10, z = dragon.getZ() + 16;
                    double dx = dragon.getX() - x, dy = dragon.getY() + 3 - y, dz = dragon.getZ() - z;
                    player.setGameMode(GameType.SPECTATOR);
                    player.connection.teleport(x, y, z, (float)Math.toDegrees(Math.atan2(-dx, dz)),
                            (float)-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
                }
            });
            case 715 -> {
                if (dragonId != null) Screenshot.grab(mc.gameDirectory, "livingrealms_military_" + RUN + "_dragon_escort.png",
                        mc.getMainRenderTarget(), message -> LOG.info("Military UI tour captured dragon escort"));
            }
            case 740 -> server.execute(() -> {
                // Explicitly staged castle fixture using the same paid construction test engine.
                try {
                livingrealms.settlement.CapitalCastleSelfTest.run(server.overworld());
                var player = server.getPlayerList().getPlayer(playerId);
                player.setGameMode(GameType.SPECTATOR);
                player.connection.teleport(548, 115, 553, 139, 34);
                LOG.info("Military UI tour: staged paid castle construction at 512,80,512");
                } finally { castleReady = true; }
            });
            case 1140 -> Screenshot.grab(mc.gameDirectory, "livingrealms_military_" + RUN + "_capital_castle.png",
                    mc.getMainRenderTarget(), message -> LOG.info("Military UI tour captured capital castle"));
            case 1165 -> { LOG.info("Military UI tour complete: save={}, screenshot prefix=livingrealms_military_{}", SAVE, RUN); mc.stop(); }
            default -> {}
        }
    }

    private static void setupDragon(ServerPlayer player) {
        if (!DragonRiders.installed()) { LOG.info("Military UI tour: no optional dragon mod; escort capture skipped"); return; }
        try {
            var level = player.serverLevel();
            var entity = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation("iceandfire:fire_dragon")).create(level);
            if (!(entity instanceof TamableAnimal dragon)) throw new IllegalStateException("No tameable fire dragon");
            dragon.moveTo(player.getX() + 5, player.getY(), player.getZ());
            dragon.setTame(true); dragon.setOwnerUUID(player.getUUID());
            dragon.getClass().getMethod("setAgeInDays", int.class).invoke(dragon, 80);
            dragon.setHealth(dragon.getMaxHealth());
            level.addFreshEntity(dragon); dragonId = dragon.getUUID();
            var rider = RecruitsBridge.spawn(level, UnitType.DRAGON_RIDER, dragon.blockPosition(), player.getUUID(), "Dragon Rider Rowan");
            if (!(rider instanceof LivingEntity recruit) || rider.getVehicle() != dragon)
                throw new IllegalStateException("Dragon rider failed to mount");
            riderId = rider.getUUID();
            RecruitsBridge.setSlot(rider, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            RecruitsBridge.addToPack(rider, new ItemStack(Items.ARROW, 64));
            Settlement s = SettlementManager.get(player.server).get(townId);
            Settlement.Soldier soldier = new Settlement.Soldier();
            soldier.entity = riderId; soldier.name = "Dragon Rider Rowan"; soldier.type = UnitType.DRAGON_RIDER;
            soldier.seen = true; soldier.health = recruit.getHealth(); soldier.maxHealth = recruit.getMaxHealth();
            s.soldiers.add(soldier); s.stance = Stance.FOLLOW;
            Simulation.applyStanceAll(level, s); s.dirty = true;
            LOG.info("Military UI tour: adult owned dragon mounted by recruit, town stance FOLLOW");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOG.error("Military UI tour dragon setup failed", failure);
        }
    }

    private static void setup(ServerPlayer player) {
        if (player == null) throw new IllegalStateException("Military UI tour has no player");
        var level = player.serverLevel();
        level.setDayTime(5000);
        BlockPos home = player.blockPosition();
        Settlement s = new Settlement();
        s.name = "Royal Household"; s.owner = player.getUUID(); s.ownerName = player.getGameProfile().getName();
        s.dim = level.dimension(); s.center = home; s.tier = Tier.TOWN; s.population = 16; s.baseHousing = 20;
        s.research.add("command"); s.stock.force(Items.EMERALD, 128); s.stock.force(Items.BREAD, 128);
        SettlementManager.get(player.server).add(s); townId = s.id;
        var entity = RecruitsBridge.spawn(level, UnitType.SHIELDBEARER, home.offset(3, 0, 2), s.owner, "Sir Aldric");
        if (!(entity instanceof LivingEntity recruit)) throw new IllegalStateException("Military UI tour recruit spawn failed");
        RecruitsBridge.setSlot(entity, EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        RecruitsBridge.setSlot(entity, EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        RecruitsBridge.setSlot(entity, EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        RecruitsBridge.setSlot(entity, EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        RecruitsBridge.setSlot(entity, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        RecruitsBridge.setSlot(entity, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        Settlement.Soldier so = new Settlement.Soldier();
        so.entity = entity.getUUID(); so.name = "Sir Aldric"; so.type = UnitType.SHIELDBEARER; so.skillXp = 200;
        so.seen = true; so.health = recruit.getHealth(); so.maxHealth = recruit.getMaxHealth();
        // Seeded showcase values, deliberately not presented as naturally earned test rewards.
        WeaponProficiency.initialise(so);
        so.weaponXp.put(so.preferredWeapon, 600);
        so.legendary.add(LegendaryPerk.SECOND_WIND); so.legendary.add(LegendaryPerk.ARMS_SAVANT);
        so.legendaryLevelChecked = 14;
        RecruitsBridge.setLevel(entity, 14);
        s.soldiers.add(so); soldierId = so.entity;
        for (String skill : new String[]{"ENDURANCE", "WEAPON_DRILLS"}) {
            CompoundTag data = new CompoundTag(); data.putUUID("soldier", so.entity); data.putString("skill", skill);
            Actions.handle(player, s.id, "learnSoldierSkill", data);
        }
        Simulation.recompute(s); Simulation.garrison(level, s, false); s.dirty = true;
        LOG.info("Military UI tour: seeded demonstration XP=200, nativeLevel=14, preferredWeapon={}, proficiencyXP=600, legendary={}, learned={}, town={}, soldier={}", so.preferredWeapon, so.legendary, so.skills, townId, soldierId);
    }

    private static void scrollDown(Minecraft mc) {
        if (mc.screen instanceof CityScreen city)
            city.mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 70, mc.getWindow().getGuiScaledHeight() / 2.0, -100);
    }

    private static void clearHover(Minecraft mc) {
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 10, 10);
        // macOS may ignore native cursor warps while the window lacks focus.
        // These are development-mapped fields; this class is opt-in test tooling only.
        try {
            for (String name : new String[]{"xpos", "ypos"}) {
                var field = mc.mouseHandler.getClass().getDeclaredField(name);
                field.setAccessible(true); field.setDouble(mc.mouseHandler, 10);
            }
        } catch (ReflectiveOperationException ex) {
            LOG.warn("Military UI tour could not clear the inactive-window hover: {}", ex.toString());
        }
    }

    private static void shot(Minecraft mc, String name) {
        if (!(mc.screen instanceof CityScreen)) { LOG.error("Military UI tour: missing CityScreen for {}", name); return; }
        String file = "livingrealms_military_" + RUN + "_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> LOG.info("Military UI tour captured {}", file));
    }

    private static void openWorld(Minecraft mc) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(SAVE, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(SAVE, settings, new WorldOptions(20261026L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }
    private MilitaryUiTour() {}
}
