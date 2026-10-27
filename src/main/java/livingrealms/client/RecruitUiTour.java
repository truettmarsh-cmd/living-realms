package livingrealms.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.client.ui.CityScreen;
import livingrealms.client.ui.RecruitScreen;
import livingrealms.military.LegendaryPerk;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierProgression;
import livingrealms.military.UnitType;
import livingrealms.military.WeaponProficiency;
import livingrealms.net.Net;
import livingrealms.settlement.Actions;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Simulation;
import livingrealms.settlement.Tier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.UUID;

/** Opt-in Recruit Command captures (./gradlew runClient -PrecruitUiTest). Each run makes its own world and filenames. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class RecruitUiTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.recruitUiTest");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static final String SAVE = "livingrealms_recruit_ui_" + RUN;
    private static int ticks = -1, waiting;
    private static boolean opened, fakeContract, keyPressed;
    private static volatile UUID townId, soldierId, freeId;
    private static volatile int followBefore;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (++waiting > 20 * 300) { LOG.error("Recruit UI tour timed out: {}", mc.screen); mc.stop(); return; }
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && waiting > 40) { opened = true; openWorld(mc); }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(SAVE)) return;
        UUID playerId = mc.player.getUUID();
        if (fakeContract && mc.screen instanceof RecruitScreen r) injectContract(r);
        ticks++;
        // aim at the recruit until the crosshair finds it, then press the Recruit Command key once
        if (ticks > 670 && ticks < 700 && !(mc.screen instanceof RecruitScreen)) {
            Entity e = clientEntity(mc, freeId);
            if (e != null) mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, e.position().add(0, e.getBbHeight() * 0.6, 0));
            if (e != null && mc.crosshairPickEntity == e && !keyPressed) {
                LOG.info("Recruit UI tour: pressing the Recruit Command key at {}", e);
                KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_KP_9));
                keyPressed = true;
            } else if (keyPressed && ticks % 5 == 0) keyPressed = false;
        }
        switch (ticks) {
            case 40 -> server.execute(() -> setup(server.getPlayerList().getPlayer(playerId)));
            case 80 -> shiftClick(mc, soldierId, true);
            case 81 -> unsneak(mc, server, playerId);
            case 100 -> { check(mc.screen instanceof RecruitScreen, "shift + right-click opened Recruit Command"); clearHover(mc); }
            case 110 -> shot(mc, "overview");
            case 115 -> page(mc, RecruitScreen.Page.PROMOTIONS);
            case 135 -> shot(mc, "promotions");
            case 140 -> page(mc, RecruitScreen.Page.SKILLS);
            case 160 -> shot(mc, "skills");
            case 165 -> scroll(mc);
            case 185 -> shot(mc, "skills_scrolled");
            case 190 -> page(mc, RecruitScreen.Page.WEAPONS);
            case 210 -> shot(mc, "weapons");
            case 215 -> page(mc, RecruitScreen.Page.LEGENDARY);
            case 235 -> shot(mc, "legendary");
            // the other agents' keys, faked client-side to check this screen renders them
            case 240 -> { fakeContract = true; page(mc, RecruitScreen.Page.SKILLS); }
            case 260 -> shot(mc, "contract_skills");
            case 265 -> page(mc, RecruitScreen.Page.SPECIAL);
            case 285 -> shot(mc, "contract_special");
            case 290 -> page(mc, RecruitScreen.Page.OVERVIEW);
            case 310 -> { shot(mc, "contract_overview"); fakeContract = false; }
            case 315 -> op(soldierId, "inventory");
            case 345 -> { check(mc.screen != null && mc.screen.getClass().getSimpleName().contains("Inventory"), "Inventory button opened " + (mc.screen == null ? "nothing" : mc.screen.getClass().getName())); shot(mc, "inventory"); }
            case 350 -> mc.setScreen(null);
            case 360 -> server.execute(() -> Net.openManager(server.getPlayerList().getPlayer(playerId), SettlementManager.get(server).get(townId), "military"));
            case 385 -> { clearHover(mc); }
            case 390 -> shot(mc, "garrison_roster");
            case 395 -> { if (mc.screen instanceof CityScreen city) city.showSoldier(soldierId); }
            case 408 -> shot(mc, "city_soldier_detail");
            case 410 -> CityScreen.recruitOp(soldierId, "open");
            case 418 -> { check(mc.screen instanceof RecruitScreen, "roster Command opened Recruit Command"); shot(mc, "from_roster"); }
            case 420 -> mc.setScreen(null);
            // a recruit hired the Recruits way: no town record
            case 430 -> shiftClick(mc, freeId, true);
            case 431 -> unsneak(mc, server, playerId);
            case 450 -> { page(mc, RecruitScreen.Page.OVERVIEW); clearHover(mc); }
            case 465 -> shot(mc, "unenlisted_overview");
            case 470 -> page(mc, RecruitScreen.Page.SKILLS);
            case 490 -> shot(mc, "unenlisted_skills");
            case 495 -> page(mc, RecruitScreen.Page.PROMOTIONS);
            case 515 -> shot(mc, "unenlisted_promotions");
            case 520 -> op(freeId, "enlist");
            case 525 -> page(mc, RecruitScreen.Page.SKILLS);
            case 545 -> {
                shot(mc, "enlisted_skills");
                server.execute(() -> check(SettlementManager.get(server).get(townId).soldiers.stream().anyMatch(s -> s.entity.equals(freeId)), "Enlist added a garrison record"));
            }
            case 550 -> { CompoundTag t = new CompoundTag(); t.putString("name", "Wren the Bold"); t.putUUID("e", freeId); t.putString("op", "rename"); Net.toServer("recruit", t); }
            case 570 -> server.execute(() -> {
                Entity e = SoldierProgression.findEntity(server, freeId);
                check(e != null && e.getName().getString().equals("Wren the Bold"), "Rename reached the entity");
            });
            // promote the garrison soldier to Scout (Recruits replaces the entity)
            case 575 -> mc.setScreen(null);
            case 580 -> shiftClick(mc, soldierId, true);
            case 581 -> unsneak(mc, server, playerId);
            case 600 -> { CompoundTag t = new CompoundTag(); t.putInt("profession", 1); t.putUUID("e", soldierId); t.putString("op", "promote"); Net.toServer("recruit", t); }
            case 625 -> {
                UUID now = mc.screen instanceof RecruitScreen r ? r.entity() : null;
                check(now != null && !now.equals(soldierId), "Screen follows the promoted recruit");
                UUID old = soldierId;
                server.execute(() -> {
                    Settlement s = SettlementManager.get(server).get(townId);
                    boolean moved = s.soldiers.stream().noneMatch(so -> so.entity.equals(old)) && s.soldiers.stream().anyMatch(so -> so.entity.equals(now) && so.skills.size() == 2);
                    Entity n = now == null ? null : SoldierProgression.findEntity(server, now);
                    check(moved, "Garrison record and skills moved to the promoted entity");
                    LOG.info("Recruit UI tour: promoted entity {} type={}", now, n == null ? null : n.getType().getDescriptionId());
                });
                if (now != null) soldierId = now;
                page(mc, RecruitScreen.Page.PROMOTIONS);
                clearHover(mc);
            }
            case 645 -> shot(mc, "promoted");
            // plain right-click still reaches Villager Recruits
            case 650 -> mc.setScreen(null);
            case 655 -> { followBefore = followState(server, freeId); shiftClick(mc, freeId, false); }
            case 665 -> {
                int after = followState(server, freeId);
                check(!(mc.screen instanceof RecruitScreen) && after != followBefore, "Plain right-click left Recruits' behaviour alone (follow state " + followBefore + " -> " + after + ", screen " + mc.screen + ")");
                mc.setScreen(null);
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    Entity e = SoldierProgression.findEntity(server, freeId);
                    RecruitsBridge.holdAt(e, e.blockPosition());
                    player.teleportTo(e.getX(), e.getY(), e.getZ() - 2.5);
                });
            }
            case 670 -> {
                ClientSetup.RECRUIT.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_KP_9));
                KeyMapping.resetMapping();
            }
            case 705 -> {
                check(mc.screen instanceof RecruitScreen r && r.entity().equals(freeId), "Key binding opened Recruit Command for the recruit under the crosshair (screen " + mc.screen + ")");
                ClientSetup.RECRUIT.setKey(InputConstants.UNKNOWN);
                KeyMapping.resetMapping();
            }
            case 710 -> { LOG.info("Recruit UI tour complete: save={}, screenshot prefix=livingrealms_recruit_{}", SAVE, RUN); mc.stop(); }
            default -> {}
        }
    }

    /** A real sneak release sends RELEASE_SHIFT_KEY; the one-tick emulated sneak never does, so clear it on the server too. */
    static void unsneak(Minecraft mc, MinecraftServer server, UUID playerId) {
        mc.player.input.shiftKeyDown = false;
        server.execute(() -> server.getPlayerList().getPlayer(playerId).setShiftKeyDown(false));
    }

    static int followState(MinecraftServer server, UUID id) {
        Entity e = SoldierProgression.findEntity(server, id);
        return e == null ? -1 : RecruitsBridge.followState(e);
    }

    static void check(boolean ok, String what) {
        if (ok) LOG.info("Recruit UI tour PASS: {}", what);
        else LOG.error("Recruit UI tour FAIL: {}", what);
    }

    static Entity clientEntity(Minecraft mc, UUID id) {
        for (Entity e : mc.level.entitiesForRendering()) if (e.getUUID().equals(id)) return e;
        return null;
    }

    /** The same calls Minecraft makes for a right-click on an entity, with sneak held or not. */
    static void shiftClick(Minecraft mc, UUID id, boolean sneak) {
        Entity e = clientEntity(mc, id);
        if (e == null) { LOG.error("Recruit UI tour: recruit {} not on the client", id); return; }
        // LocalPlayer reads sneaking from its input, which is also what the interact packet carries
        mc.player.input.shiftKeyDown = sneak;
        var hit = new EntityHitResult(e, e.position().add(0, 1, 0));
        var r = mc.gameMode.interactAt(mc.player, e, hit, InteractionHand.MAIN_HAND);
        if (!r.consumesAction()) r = mc.gameMode.interact(mc.player, e, InteractionHand.MAIN_HAND);
        LOG.info("Recruit UI tour: right-click sneak={} result={}", sneak, r);
    }

    static void op(UUID id, String op) {
        CompoundTag t = new CompoundTag();
        t.putUUID("e", id);
        t.putString("op", op);
        Net.toServer("recruit", t);
    }

    static void page(Minecraft mc, RecruitScreen.Page p) { if (mc.screen instanceof RecruitScreen r) r.showPage(p); }

    static void injectContract(RecruitScreen r) {
        CompoundTag d = r.data();
        if (!d.contains("soldier") || d.getCompound("soldier").contains("specials")) return;
        CompoundTag c = d.copy(), so = c.getCompound("soldier");
        so.putInt("rank", 4); so.putString("rankTitle", "Sergeant"); so.putInt("xpInto", 30); so.putInt("xpNeeded", 80); so.putInt("maxPoints", 18);
        so.putString("drillCost", "16 emeralds, 4 bread"); so.putBoolean("drillReady", true); so.putInt("drillCooldown", 0); so.putString("respecCost", "8 emeralds");
        ListTag specials = new ListTag();
        String[][] rows = {{"shield_wall", "Shield Wall", "Allies within 4 blocks take 10% less damage while this recruit blocks.", "chosen", ""},
                {"rally", "Rallying Cry", "Restores morale to nearby recruits once per battle.", "available", "Rank 3"},
                {"cleave", "Cleave", "Melee hits strike a second enemy for half damage.", "locked", "Rank 6 and Weapon Drills"},
                {"vigil", "Night Vigil", "Sees hostiles through darkness on watch.", "locked", "Rank 8"}};
        for (String[] row : rows) {
            CompoundTag t = new CompoundTag();
            t.putString("id", row[0]); t.putString("label", row[1]); t.putString("desc", row[2]); t.putString("state", row[3]); t.putString("req", row[4]);
            specials.add(t);
        }
        so.put("specials", specials); so.putInt("specialSlots", 2);
        r.update(c);
    }

    private static void setup(ServerPlayer player) {
        var level = player.serverLevel();
        level.setDayTime(5000);
        BlockPos home = player.blockPosition();
        player.setYRot(0); player.setXRot(10);
        Settlement s = new Settlement();
        s.name = "Royal Household"; s.owner = player.getUUID(); s.ownerName = player.getGameProfile().getName();
        s.dim = level.dimension(); s.center = home; s.tier = Tier.TOWN; s.population = 16; s.baseHousing = 20;
        s.research.add("command"); s.stance = livingrealms.military.Stance.FREE; s.stock.force(Items.EMERALD, 128); s.stock.force(Items.BREAD, 128);
        SettlementManager.get(player.server).add(s); townId = s.id;
        Entity entity = RecruitsBridge.spawn(level, UnitType.SHIELDBEARER, home.offset(1, 0, 3), s.owner, "Sir Aldric");
        if (!(entity instanceof LivingEntity recruit)) throw new IllegalStateException("Recruit UI tour spawn failed");
        RecruitsBridge.setSlot(entity, EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        RecruitsBridge.setSlot(entity, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        RecruitsBridge.setSlot(entity, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        RecruitsBridge.holdAt(entity, entity.blockPosition());
        Settlement.Soldier so = new Settlement.Soldier();
        so.entity = entity.getUUID(); so.name = "Sir Aldric"; so.type = UnitType.SHIELDBEARER; so.skillXp = 200;
        so.seen = true; so.health = recruit.getHealth(); so.maxHealth = recruit.getMaxHealth();
        WeaponProficiency.initialise(so);
        so.weaponXp.put(so.preferredWeapon, 600);
        so.legendary.add(LegendaryPerk.SECOND_WIND);
        so.legendaryLevelChecked = 14;
        RecruitsBridge.setLevel(entity, 14);
        s.soldiers.add(so); soldierId = so.entity;
        for (String skill : new String[]{"ENDURANCE", "WEAPON_DRILLS"}) {
            CompoundTag data = new CompoundTag(); data.putUUID("soldier", so.entity); data.putString("skill", skill);
            Actions.handle(player, s.id, "learnSoldierSkill", data);
        }
        Entity free = RecruitsBridge.spawn(level, UnitType.BOWMAN, home.offset(-2, 0, 3), s.owner, "Bowman Wren");
        if (free == null) throw new IllegalStateException("Recruit UI tour free recruit spawn failed");
        RecruitsBridge.setSlot(free, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        RecruitsBridge.setLevel(free, 3);
        RecruitsBridge.holdAt(free, free.blockPosition());
        freeId = free.getUUID();
        Simulation.recompute(s); s.dirty = true;
        LOG.info("Recruit UI tour: town={}, garrison soldier={}, free recruit={}", townId, soldierId, freeId);
    }

    private static void scroll(Minecraft mc) {
        if (mc.screen != null) mc.screen.mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 60, mc.getWindow().getGuiScaledHeight() / 2.0, -100);
    }

    private static void clearHover(Minecraft mc) {
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 10, 10);
        try {
            for (String name : new String[]{"xpos", "ypos"}) {
                var field = mc.mouseHandler.getClass().getDeclaredField(name);
                field.setAccessible(true); field.setDouble(mc.mouseHandler, 10);
            }
        } catch (ReflectiveOperationException ex) {
            LOG.warn("Recruit UI tour could not clear hover: {}", ex.toString());
        }
    }

    private static void shot(Minecraft mc, String name) {
        String file = "livingrealms_recruit_" + RUN + "_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> LOG.info("Recruit UI tour captured {} ({})", file, mc.screen == null ? "no screen" : mc.screen.getClass().getSimpleName()));
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

    private RecruitUiTour() {}
}
