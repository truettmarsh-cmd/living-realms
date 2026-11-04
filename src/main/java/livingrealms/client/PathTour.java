package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import livingrealms.client.ui.RecruitScreen;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import livingrealms.military.WeaponProficiency;
import livingrealms.settlement.Actions;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Simulation;
import livingrealms.settlement.Tier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Specialist path captures (./gradlew runClient -PpathTour [-Ppehkui] -PrunDir=<fresh dir>): the path choice, a
 * berserker's tree up to Titan, and the Titan beside an untrained recruit.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class PathTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.pathTour");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String SAVE = "livingrealms_paths_" + System.currentTimeMillis();
    private static int ticks = -1, waiting;
    private static boolean opened;
    private static volatile UUID townId, heroId, plainId;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && ++waiting > 40) { opened = true; openWorld(mc); }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID playerId = mc.player.getUUID();
        switch (++ticks) {
            case 40 -> server.execute(() -> setup(server.getPlayerList().getPlayer(playerId)));
            case 80 -> RecruitUiTour.op(heroId, "open");
            case 100 -> RecruitUiTour.page(mc, RecruitScreen.Page.PATH);
            case 120 -> shot(mc, "choose");
            case 125 -> server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                act(p, "choosePath", "path", "BERSERKER");
                for (String sk : new String[]{"FURY", "BLOODLUST", "RECKLESS"}) act(p, "learnPathSkill", "skill", sk);
            });
            case 130 -> RecruitUiTour.op(heroId, "refresh");
            case 150 -> shot(mc, "berserker");
            case 155 -> server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                act(p, "learnPathSkill", "skill", "RAMPAGE");
                act(p, "learnPathSkill", "skill", "TITAN");
            });
            case 160 -> RecruitUiTour.op(heroId, "refresh");
            case 180 -> shot(mc, "titan_tree");
            case 182 -> { if (mc.screen != null) mc.screen.mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 60, mc.getWindow().getGuiScaledHeight() / 2.0, -400); }
            case 196 -> shot(mc, "ascension");
            case 198 -> mc.setScreen(null);
            case 200 -> server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                Entity hero = p.serverLevel().getEntity(heroId);
                if (hero != null) p.connection.teleport(hero.getX() + 1, hero.getY() + 2, hero.getZ() + 8, 180, 8);
            });
            case 240 -> shot(mc, "titan_world");
            case 245 -> { LOG.info("PATH TOUR COMPLETE: screenshots livingrealms_paths_*.png"); mc.stop(); }
            default -> {}
        }
    }

    private static void act(ServerPlayer p, String action, String key, String value) {
        CompoundTag d = new CompoundTag();
        d.putUUID("soldier", heroId);
        d.putString(key, value);
        Actions.handle(p, townId, action, d);
        LOG.info("Path tour {} {}", action, value);
    }

    private static void setup(ServerPlayer player) {
        var level = player.serverLevel();
        level.setDayTime(5000);
        BlockPos home = player.blockPosition();
        Settlement s = new Settlement();
        s.name = "Ironhold"; s.owner = player.getUUID(); s.ownerName = player.getGameProfile().getName();
        s.dim = level.dimension(); s.center = home; s.tier = Tier.TOWN; s.population = 16; s.baseHousing = 20;
        s.research.add("command"); s.stance = livingrealms.military.Stance.FREE; s.stock.force(Items.EMERALD, 128);
        SettlementManager.get(player.server).add(s); townId = s.id;
        Entity hero = recruit(level, s, home.offset(-1, 0, 3), "Bjorn Ironside", Items.IRON_AXE);
        Entity plain = recruit(level, s, home.offset(2, 0, 3), "Recruit Tam", Items.IRON_SWORD);
        heroId = hero.getUUID(); plainId = plain.getUUID();
        Settlement.Soldier so = s.soldiers.get(0);
        so.skillXp = 900;
        so.preferredWeapon = WeaponProficiency.AXE;
        for (String skill : new String[]{"ENDURANCE", "IRONCLAD", "WEAPON_DRILLS", "MARCHING"}) {
            CompoundTag d = new CompoundTag(); d.putUUID("soldier", heroId); d.putString("skill", skill);
            Actions.handle(player, s.id, "learnSoldierSkill", d);
        }
        Simulation.recompute(s); s.dirty = true;
    }

    private static Entity recruit(net.minecraft.server.level.ServerLevel level, Settlement s, BlockPos at, String name, net.minecraft.world.item.Item weapon) {
        Entity e = RecruitsBridge.spawn(level, UnitType.FOOTMAN, at, s.owner, name);
        if (e == null) throw new IllegalStateException("Path tour spawn failed");
        RecruitsBridge.setSlot(e, EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        RecruitsBridge.setSlot(e, EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        RecruitsBridge.setSlot(e, EquipmentSlot.MAINHAND, new ItemStack(weapon));
        RecruitsBridge.holdAt(e, e.blockPosition());
        Settlement.Soldier so = new Settlement.Soldier();
        so.entity = e.getUUID(); so.name = name; so.type = UnitType.FOOTMAN; so.seen = true;
        WeaponProficiency.initialise(so);
        s.soldiers.add(so);
        return e;
    }

    private static void shot(Minecraft mc, String name) {
        String file = "livingrealms_paths_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> LOG.info("Path tour captured {}", file));
    }

    private static void openWorld(Minecraft mc) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(SAVE, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(SAVE, settings, new WorldOptions(20261103L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }

    private PathTour() {}
}
