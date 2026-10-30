package livingrealms.compat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.BowmanEntity;
import com.talhanation.recruits.entities.IStrategicFire;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import livingrealms.military.RecruitsBridge;
import livingrealms.settlement.Raids;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.Tier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * A raid takes one siege engine along: Talhanation's Siege Weapons crewed by a Recruits Siege Engineer, or a Medieval
 * Siege Machine crewed by a bowman (Recruits Siege Compatibility makes bowmen work it). The engine sets up outside the
 * target and bombards the wall it is breaching (a walled town), then its centre; when the raid ends it is struck down, and a surviving engine goes back to the stockpile.
 */
final class SiegeTrains {
    record Kind(String mod, String entity, String item, boolean engineer, String name, Map<String, Integer> ammo) {}

    static final List<Kind> KINDS = List.of(
            new Kind(Compat.SIEGE_MACHINES, "siegemachines:trebuchet", "siegemachines:trebuchet", false, "trebuchet", Map.of("siegemachines:giant_stone", 16)),
            new Kind(Compat.SIEGE_MACHINES, "siegemachines:mortar", "siegemachines:mortar", false, "mortar", Map.of("siegemachines:cannonball", 16, "minecraft:gunpowder", 16)),
            new Kind(Compat.SIEGE_MACHINES, "siegemachines:culverin", "siegemachines:culverin", false, "culverin", Map.of("siegemachines:cannonball", 16, "minecraft:gunpowder", 16)),
            new Kind(Compat.SIEGE_MACHINES, "siegemachines:catapult", "siegemachines:catapult", false, "catapult", Map.of("siegemachines:stone", 16)),
            new Kind(Compat.SIEGE_MACHINES, "siegemachines:ballista", "siegemachines:ballista", false, "ballista", Map.of("siegemachines:giant_arrow", 16)),
            new Kind(Compat.SIEGE_WEAPONS, "siegeweapons:catapult", "siegeweapons:catapult_item", true, "catapult", Map.of("minecraft:cobblestone", 32)),
            new Kind(Compat.SIEGE_WEAPONS, "siegeweapons:ballista", "siegeweapons:ballista_item", true, "ballista", Map.of("siegeweapons:ballista_projectile_item", 16)));

    static boolean usable(Kind k) {
        return Compat.loaded(k.mod) && (k.engineer || Compat.loaded(Compat.SIEGE_CREWS))
                && BuiltInRegistries.ENTITY_TYPE.containsKey(new ResourceLocation(k.entity)) && item(k.item) != Items.AIR;
    }

    static Item item(String id) { return BuiltInRegistries.ITEM.get(new ResourceLocation(id)); }

    static String key(Raids.Raid raid) { return raid.source + ">" + raid.target; }

    /** The engine a city would take: one from its stockpile, or for NPC cities of City rank, any usable kind. */
    @Nullable
    static Kind pick(Settlement source) {
        for (Kind k : KINDS) if (usable(k) && source.stock.count(item(k.item)) > 0) return k;
        if (source.isAi() && Compat.on(CompatConfig.NPC_SIEGE_TRAINS) && source.tier.number() >= Tier.CITY.number())
            for (Kind k : KINDS) if (usable(k)) return k;
        return null;
    }

    @Nullable
    static Entity deploy(MinecraftServer server, Raids.Raid raid, Settlement source, Settlement target) throws ReflectiveOperationException {
        Kind k = pick(source);
        ServerLevel level = server.getLevel(target.dim);
        if (k == null || level == null) return null;
        BlockPos at = muster(level, source, target);
        if (at == null) return null;
        Entity engine = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(k.entity)).create(level);
        EntityType<? extends AbstractRecruitEntity> crewType = k.engineer ? ModEntityTypes.SIEGE_ENGINEER.get() : ModEntityTypes.BOWMAN.get();
        AbstractRecruitEntity crew = crewType.create(level);
        if (engine == null || crew == null) return null;
        float yaw = (float) (Math.toDegrees(Math.atan2(target.center.getZ() - at.getZ(), target.center.getX() - at.getX())) - 90);
        engine.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, yaw, 0);
        level.addFreshEntity(engine);
        crew.moveTo(at.getX() + .5, at.getY(), at.getZ() + 2.5, yaw, 0);
        prepare(level, crew, at);
        crew.setOwnerUUID(Optional.of(source.kingdomId()));
        crew.setIsOwned(true);
        crew.setPersistenceRequired();
        crew.setCustomName(Component.literal(source.name + " siege crew"));
        level.addFreshEntity(crew);
        k.ammo.forEach((id, n) -> RecruitsBridge.addToPack(crew, new ItemStack(item(id), n)));
        crew.startRiding(engine, true);
        if (crew instanceof SiegeEngineerEntity se) { se.setStrategicFirePos(target.center); se.setShouldStrategicFire(true); }
        else if (crew instanceof BowmanEntity b) { b.setStrategicFirePos(target.center); b.setShouldStrategicFire(true); }
        boolean stocked = source.stock.take(item(k.item), 1);
        CompatData.get(server).engines.computeIfAbsent(key(raid), x -> new ArrayList<>())
                .add(new CompatData.Engine(engine.getUUID(), crew.getUUID(), stocked ? k.item : ""));
        source.log("A " + k.name + " and its crew march with the army on " + target.name);
        target.log("A siege " + k.name + " is setting up outside " + target.name + "!");
        return engine;
    }

    /** Recruits' engineer spawn setup expects ground navigation; lend it one for the call, as Siege Overhaul does. */
    static void prepare(ServerLevel level, AbstractRecruitEntity crew, BlockPos at) throws ReflectiveOperationException {
        Field nav = null;
        if (crew instanceof SiegeEngineerEntity) for (Field f : Mob.class.getDeclaredFields())
            if (f.getType() == PathNavigation.class && !Modifier.isStatic(f.getModifiers())) nav = f;
        Object old = null;
        if (nav != null) { nav.setAccessible(true); old = nav.get(crew); nav.set(crew, new GroundPathNavigation(crew, level)); }
        try { crew.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.MOB_SUMMONED, null, null); }
        finally { if (nav != null) nav.set(crew, old); }
    }

    /** Outside the target's centre, on the side facing the attacker, where the ground is loaded. */
    @Nullable
    static BlockPos muster(ServerLevel level, Settlement source, Settlement target) {
        Vec3 dir = Vec3.atCenterOf(source.center).subtract(Vec3.atCenterOf(target.center)).multiply(1, 0, 1);
        dir = dir.lengthSqr() < 1 ? new Vec3(1, 0, 0) : dir.normalize();
        for (int d : new int[]{Math.min(target.radius() + 8, 56), 32, 20}) {
            int x = target.center.getX() + (int) Math.round(dir.x * d), z = target.center.getZ() + (int) Math.round(dir.z * d);
            BlockPos p = new BlockPos(x, target.center.getY(), z);
            if (level.hasChunkAt(p)) return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        }
        return null;
    }

    /**
     * Keeps the bombardment off our own troops: the crews shell the centre until attackers reach it, then the densest
     * defenders clear of them, and cease fire when there is nothing safe to hit. Changes are told once in the chronicle.
     */
    static void aim(MinecraftServer server, Raids.Raid raid, Settlement target, List<? extends Entity> attackers, List<? extends Entity> defenders) {
        List<CompatData.Engine> list = CompatData.get(server).engines.get(key(raid));
        ServerLevel level = server.getLevel(target.dim);
        if (list == null || level == null) return;
        // a siege: the stretch being breached first (docs/sieges.md), then the centre, keeping off our own troops
        BlockPos objective = raid.siege.aim(target);
        BlockPos at = raid.siege.bombarding() ? objective : livingrealms.military.BattleAI.firePoint(objective, attackers, defenders);
        for (CompatData.Engine x : list) {
            Entity crew = level.getEntity(x.crew());
            if (!(crew instanceof IStrategicFire fire)) continue;
            boolean was = crew instanceof BowmanEntity b ? b.getShouldStrategicFire() : crew instanceof SiegeEngineerEntity se && se.getShouldStrategicFire();
            BlockPos old = crew instanceof BowmanEntity b ? b.StrategicFirePos() : crew instanceof SiegeEngineerEntity se ? se.getStrategicFirePos() : null;
            if (at == null) {
                fire.setShouldStrategicFire(false);
                if (was) target.log("The siege engine outside " + target.name + " holds its fire: the attackers are in the way");
            } else {
                fire.setStrategicFirePos(at);
                fire.setShouldStrategicFire(true);
                if (!was || (old != null && (old.equals(target.center) != at.equals(target.center) || old.equals(objective) != at.equals(objective))))
                    target.log(raid.siege.bombarding() ? "The siege engine pounds the " + raid.siege.where + " of " + target.name
                            : at.equals(target.center) ? "The siege engine shells the centre of " + target.name
                            : "The siege engine shifts its fire onto the defenders, away from the attackers");
            }
        }
    }

    /** Strikes the engines of a finished raid; an attacker's surviving engine returns to its stockpile. */
    static int recall(MinecraftServer server, Raids.Raid raid, @Nullable Settlement source) {
        List<CompatData.Engine> list = CompatData.get(server).engines.remove(key(raid));
        if (list == null) return 0;
        int returned = 0;
        for (CompatData.Engine x : list) for (ServerLevel level : server.getAllLevels()) {
            Entity engine = level.getEntity(x.engine()), crew = level.getEntity(x.crew());
            if (engine == null && crew == null) continue;
            if (engine != null && engine.isAlive() && !x.item().isEmpty() && source != null && source.kingdomId().equals(raid.attacker)) {
                source.stock.force(item(x.item()), 1);
                returned++;
            }
            if (crew != null) crew.discard();
            if (engine != null) engine.discard();
        }
        if (returned > 0 && source != null) source.log("The siege engine came home to the stockpile");
        return returned;
    }

    static void view(MinecraftServer server, Settlement s, ListTag lines) {
        List<Kind> kinds = KINDS.stream().filter(SiegeTrains::usable).toList();
        if (kinds.isEmpty()) return;
        int ready = kinds.stream().map(k -> item(k.item)).distinct().mapToInt(s.stock::count).sum();
        long out = CompatData.get(server).engines.entrySet().stream().filter(e -> e.getKey().startsWith(s.id + ">")).mapToLong(e -> e.getValue().size()).sum();
        lines.add(Compat.line("Siege", ready + " ready" + (out > 0 ? " · " + out + " in the field" : ""),
                "Forge siege engines at the Smithy (War machines).",
                "Each raid takes one, crewed, to bombard the target's centre.",
                "If it survives, it returns to the stockpile when the raid ends.",
                s.isAi() ? "NPC kingdoms of City rank bring one anyway." : ""));
    }

    private SiegeTrains() {}
}
