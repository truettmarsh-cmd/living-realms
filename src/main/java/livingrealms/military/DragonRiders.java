package livingrealms.military;

import livingrealms.LivingRealms;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.UUID;

/** Optional Ice and Fire bridge. Uses its public API without requiring the mod on either side. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class DragonRiders {
    public static boolean installed() { return ModList.get().isLoaded("iceandfire"); }

    public static boolean dragon(Entity e) {
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return id.getNamespace().equals("iceandfire") && (id.getPath().equals("fire_dragon") || id.getPath().equals("ice_dragon") || id.getPath().equals("lightning_dragon"));
    }

    public static boolean eligible(Entity e, UUID owner) {
        if (owner == null || !dragon(e) || !(e instanceof TamableAnimal tame) || !e.isAlive() || !tame.isTame()
                || !owner.equals(tame.getOwnerUUID()) || !e.getPassengers().isEmpty() || e.isPassenger()) return false;
        try {
            return (int)e.getClass().getMethod("getDragonStage").invoke(e) >= 3
                    && !(boolean)e.getClass().getMethod("isModelDead").invoke(e);
        } catch (ReflectiveOperationException ex) { return false; }
    }

    @Nullable
    public static Entity available(ServerLevel level, BlockPos at, UUID owner) {
        if (!installed() || owner == null) return null;
        Entity near = level.getEntitiesOfClass(TamableAnimal.class, new AABB(at).inflate(48), e -> eligible(e, owner))
                .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(at.getX(), at.getY(), at.getZ()))).orElse(null);
        // the town's roost dragon answers wherever it perches (docs/dragons.md)
        return near != null ? near : livingrealms.dragon.Roosts.forRider(level, at, owner);
    }

    public static boolean mount(Entity rider, Entity dragon) {
        if (!eligible(dragon, RecruitsBridge.owner(rider))) return false;
        if (!rider.startRiding(dragon, true)) return false;
        command(dragon, 0);
        return true;
    }

    /** Runs on both sides, using the source mod's own animated saddle position. */
    public static boolean positionPassenger(Entity dragon, Entity passenger, Entity.MoveFunction move) {
        if (!RecruitsBridge.isRecruit(passenger) || !(dragon instanceof TamableAnimal tame)
                || !dragon.hasPassenger(passenger) || tame.getOwnerUUID() == null
                || !tame.getOwnerUUID().equals(RecruitsBridge.owner(passenger))) return false;
        try {
            if ((boolean) dragon.getClass().getMethod("isModelDead").invoke(dragon)) {
                passenger.stopRiding(); return true;
            }
            var pos = (net.minecraft.world.phys.Vec3) dragon.getClass().getMethod("getRiderPosition").invoke(dragon);
            move.accept(passenger, pos.x, pos.y + passenger.getBbHeight(), pos.z);
            return true;
        } catch (ReflectiveOperationException ex) {
            // Never let an incompatible dragon API turn an allied soldier into prey.
            passenger.stopRiding();
            return true;
        }
    }

    private static void command(Entity dragon, int command) {
        try {
            if ((int)dragon.getClass().getMethod("getCommand").invoke(dragon) != command)
                dragon.getClass().getMethod("setCommand", int.class).invoke(dragon, command);
        } catch (ReflectiveOperationException ex) {
            LivingRealms.LOG.warn("Cannot command Ice and Fire dragon: {}", ex.toString());
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !installed() || event.getServer().getTickCount() % 20 != 0) return;
        for (Settlement town : SettlementManager.get(event.getServer()).all()) for (Settlement.Soldier soldier : town.soldiers) {
            if (soldier.type != UnitType.DRAGON_RIDER) continue;
            for (ServerLevel level : event.getServer().getAllLevels()) {
                Entity rider = level.getEntity(soldier.entity);
                if (!(rider instanceof Mob recruit) || !(rider.getVehicle() instanceof Mob mount) || !dragon(mount)) continue;
                if (!(mount instanceof TamableAnimal tame) || !java.util.Objects.equals(town.owner, tame.getOwnerUUID())) {
                    rider.stopRiding(); continue;
                }
                var owner = town.owner == null ? null : level.getPlayerByUUID(town.owner);
                boolean following = RecruitsBridge.isFollowing(rider);
                // Ice and Fire's escort command supplies native ground/flight navigation to the owner.
                command(mount, following && owner != null ? 2 : 0);
                var target = recruit.getTarget();
                if (target != null && target.isAlive() && !target.isAlliedTo(tame) && !target.getUUID().equals(town.owner)) mount.setTarget(target);
                if (!following && town.stance != Stance.FREE && target == null) {
                    BlockPos post = town.center;
                    if (mount.distanceToSqr(post.getX(), post.getY(), post.getZ()) > 100)
                        mount.getNavigation().moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, 1);
                    else mount.getNavigation().stop();
                }
            }
        }
    }
    private DragonRiders() {}
}
