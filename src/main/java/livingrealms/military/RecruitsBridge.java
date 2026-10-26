package livingrealms.military;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/** Every call into Villager Recruits goes through here. */
public final class RecruitsBridge {
    /** Recruits follow states: 0 wander, 1 follow owner, 2 hold here, 3 back to hold position. */
    static final int FOLLOW = 1, HOLD_POS = 3;
    /** Recruits aggro states: 0 neutral, 1 aggressive, 2 raid, 3 passive. */
    static final int AGGRESSIVE = 1;

    public static boolean isRecruit(Entity e) { return e instanceof AbstractRecruitEntity; }

    static EntityType<? extends AbstractRecruitEntity> type(UnitType u) {
        return switch (u) {
            case FOOTMAN -> ModEntityTypes.RECRUIT.get();
            case SHIELDBEARER -> ModEntityTypes.RECRUIT_SHIELDMAN.get();
            case BOWMAN -> ModEntityTypes.BOWMAN.get();
            case CROSSBOWMAN -> ModEntityTypes.CROSSBOWMAN.get();
            case HORSEMAN -> ModEntityTypes.HORSEMAN.get();
        };
    }

    /** Spawns a soldier with no armour, its default gear replaced by the town's. Owner null = an AI town's guard. */
    @Nullable
    public static Entity spawn(ServerLevel level, UnitType unit, BlockPos pos, @Nullable UUID owner, String name) {
        AbstractRecruitEntity r = type(unit).create(level);
        if (r == null) return null;
        r.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360f, 0);
        r.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.MOB_SUMMONED, null, null);
        for (EquipmentSlot s : EquipmentSlot.values()) setSlot(r, s, ItemStack.EMPTY);
        if (owner != null) {
            r.setOwnerUUID(Optional.of(owner));
            r.setIsOwned(true);
        }
        r.setCustomName(net.minecraft.network.chat.Component.literal(name));
        r.setPersistenceRequired();
        r.setAggroState(AGGRESSIVE);
        r.setHoldPos(Vec3.atBottomCenterOf(pos));
        r.setFollowState(HOLD_POS);
        level.addFreshEntity(r);
        if (unit == UnitType.HORSEMAN) {
            Horse horse = EntityType.HORSE.create(level);
            if (horse != null) {
                horse.moveTo(r.getX(), r.getY(), r.getZ(), r.getYRot(), 0);
                horse.setTamed(true);
                horse.equipSaddle(null);
                horse.setPersistenceRequired();
                level.addFreshEntity(horse);
                r.startRiding(horse, true);
            }
        }
        return r;
    }

    public static ItemStack getSlot(Entity e, EquipmentSlot slot) {
        return e instanceof AbstractRecruitEntity r ? r.getItemBySlot(slot) : ItemStack.EMPTY;
    }

    /** Puts a stack in an equipment slot, keeping Recruits' own inventory in step. */
    public static void setSlot(Entity e, EquipmentSlot slot, ItemStack stack) {
        if (!(e instanceof AbstractRecruitEntity r)) return;
        int idx = r.getInventorySlotIndex(slot);
        if (idx >= 0 && idx < r.getInventory().getContainerSize()) r.getInventory().setItem(idx, stack.copy());
        r.setItemSlot(slot, stack.copy());
    }

    /** Puts extra items (arrows, food) in the soldier's pack. Returns what didn't fit. */
    public static ItemStack addToPack(Entity e, ItemStack stack) {
        if (!(e instanceof AbstractRecruitEntity r)) return stack;
        var inv = r.getInventory();
        ItemStack left = stack.copy();
        for (int i = 6; i < inv.getContainerSize() && !left.isEmpty(); i++) {
            ItemStack in = inv.getItem(i);
            if (in.isEmpty()) { inv.setItem(i, left); return ItemStack.EMPTY; }
            if (ItemStack.isSameItemSameTags(in, left) && in.getCount() < in.getMaxStackSize()) {
                int m = Math.min(left.getCount(), in.getMaxStackSize() - in.getCount());
                in.grow(m);
                left.shrink(m);
            }
        }
        return left;
    }

    public static int countInPack(Entity e, net.minecraft.world.item.Item item) {
        if (!(e instanceof AbstractRecruitEntity r)) return 0;
        int n = 0;
        for (int i = 0; i < r.getInventory().getContainerSize(); i++) if (r.getInventory().getItem(i).is(item)) n += r.getInventory().getItem(i).getCount();
        return n;
    }

    public static void follow(Entity e, UUID player) {
        if (!(e instanceof AbstractRecruitEntity r)) return;
        r.setFollowState(FOLLOW);
    }

    public static void holdAt(Entity e, BlockPos pos) {
        if (!(e instanceof AbstractRecruitEntity r)) return;
        r.setHoldPos(Vec3.atBottomCenterOf(pos));
        r.setFollowState(HOLD_POS);
    }

    public static void aggressive(Entity e) {
        if (e instanceof AbstractRecruitEntity r) r.setAggroState(AGGRESSIVE);
    }

    /** The town feeds and pays its garrison, so Recruits' own hunger and wages are kept satisfied. */
    public static void supply(Entity e) {
        if (!(e instanceof AbstractRecruitEntity r)) return;
        r.setHunger(100f);
        r.resetPaymentTimer();
    }

    public static boolean isFollowing(Entity e) { return e instanceof AbstractRecruitEntity r && r.getFollowState() == FOLLOW; }

    public static int level(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getXpLevel() : 0; }

    public static int kills(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getKills() : 0; }

    public static float morale(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getMorale() : 0; }

    public static float hunger(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getHunger() : 0; }

    /** Veteran training: start soldiers at a higher level with Recruits' level buffs. */
    public static void setLevel(Entity e, int level) {
        if (!(e instanceof AbstractRecruitEntity r)) return;
        r.setXpLevel(level);
        r.addLevelBuffsForLevel(level);
    }

    @Nullable
    public static UUID owner(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getOwnerUUID() : null; }

    private RecruitsBridge() {}
}
