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
            case BOWMAN, DRAGON_RIDER -> ModEntityTypes.BOWMAN.get();
            case CROSSBOWMAN -> ModEntityTypes.CROSSBOWMAN.get();
            case HORSEMAN -> ModEntityTypes.HORSEMAN.get();
        };
    }

    /** Spawns a soldier with no armour, its default gear replaced by the town's. Owner null = an AI town's guard. */
    @Nullable
    public static Entity spawn(ServerLevel level, UnitType unit, BlockPos pos, @Nullable UUID owner, String name) {
        Entity dragon = unit == UnitType.DRAGON_RIDER ? DragonRiders.available(level, pos, owner) : null;
        if (unit == UnitType.DRAGON_RIDER && dragon == null) return null;
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
        RecruitOrigins.born(r);
        r.setHealth(r.getMaxHealth());
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
        if (dragon != null && !DragonRiders.mount(r, dragon)) { r.discard(); return null; }
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

    /** An NPC kingdom uses a stable synthetic owner UUID, keeping its soldiers allied. */
    public static void allegiance(Entity e, UUID owner) {
        if (!(e instanceof AbstractRecruitEntity r) || owner.equals(r.getOwnerUUID())) return;
        r.setOwnerUUID(Optional.of(owner));
        r.setIsOwned(true);
        clearRaidTarget(r);
    }

    public static void raidOrder(Entity e, BlockPos center, @Nullable net.minecraft.world.entity.LivingEntity enemy) {
        if (!(e instanceof AbstractRecruitEntity r)) return;
        aggressive(r);
        holdAt(r, enemy == null ? center : enemy.blockPosition());
        r.setTarget(enemy);
        if (enemy == null) r.getNavigation().moveTo(center.getX() + .5, center.getY(), center.getZ() + .5, 1.0);
    }

    public static void clearRaidTarget(Entity e) {
        if (e instanceof AbstractRecruitEntity r) { r.setTarget(null); r.setLastHurtByMob(null); }
    }

    public static int level(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getXpLevel() : 0; }

    /** Recruits' own XP, so its native level (which gates its promotions) rises too. */
    public static void addNativeXp(Entity e, int xp) {
        if (e instanceof AbstractRecruitEntity r) { r.addXp(xp); r.checkLevel(); }
    }

    public static int xp(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getXp() : 0; }

    public static int kills(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getKills() : 0; }

    public static float morale(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getMorale() : 0; }

    public static void addMorale(Entity e, float delta) { if (e instanceof AbstractRecruitEntity r) r.setMoral(Math.max(0, Math.min(100, r.getMorale() + delta))); }

    public static float hunger(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getHunger() : 0; }

    /** Veteran training: start soldiers at a higher level with Recruits' level buffs. */
    public static void setLevel(Entity e, int level) {
        if (!(e instanceof AbstractRecruitEntity r)) return;
        r.setXpLevel(level);
        r.addLevelBuffsForLevel(level);
    }

    @Nullable
    public static UUID owner(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getOwnerUUID() : null; }

    public static boolean ownedBy(Entity e, UUID player) { return e instanceof AbstractRecruitEntity r && r.isOwned() && player.equals(r.getOwnerUUID()); }


    public static int xpForLevel() { return com.talhanation.recruits.config.RecruitsServerConfig.RecruitsMaxXpForLevelUp.get(); }

    public static int followState(Entity e) { return e instanceof AbstractRecruitEntity r ? r.getFollowState() : 0; }

    public static void wander(Entity e) { if (e instanceof AbstractRecruitEntity r) r.setFollowState(0); }

    public static void openInventory(Entity e, net.minecraft.server.level.ServerPlayer p) { if (e instanceof AbstractRecruitEntity r) r.openGUI(p); }

    /** Workers-mod workers are recruit entities too, but they belong in the fields, not a garrison. */
    public static boolean isWorker(Entity e) { return e.getClass().getName().startsWith("com.talhanation.workers"); }

    /** Already promoted to one of Recruits' professions (messenger, captain, ...). */
    public static boolean isCompanion(Entity e) { return e instanceof com.talhanation.recruits.entities.ICompanion; }

    /** The closest Living Realms unit for a recruit hired the normal Recruits way. */
    public static UnitType unitOf(Entity e) {
        EntityType<?> t = e.getType();
        if (t == ModEntityTypes.RECRUIT_SHIELDMAN.get()) return UnitType.SHIELDBEARER;
        if (t == ModEntityTypes.BOWMAN.get()) return UnitType.BOWMAN;
        if (t == ModEntityTypes.CROSSBOWMAN.get()) return UnitType.CROSSBOWMAN;
        if (t == ModEntityTypes.HORSEMAN.get()) return UnitType.HORSEMAN;
        return UnitType.FOOTMAN;
    }

    /** Recruits' own professions, in its PromoteScreen order (the index is the profession id). */
    public record Profession(String label, int level, String note) {}
    public static final Profession[] PROFESSIONS = {
            new Profession("Messenger", 2, "Carries messages to other players"),
            new Profession("Scout", 2, "Scouts ahead and reports what it sees"),
            new Profession("Siege Engineer", 3, "Crews siege weapons (needs Siege Weapons)"),
            new Profession("Captain", 5, "Commands a ship and its crew (needs Small Ships)"),
            new Profession("Patrol Leader", 5, "Leads a patrol along waypoints"),
            new Profession("Assassin", 0, "Disabled in Villager Recruits"),
            new Profession("Governor", 0, "Disabled in Villager Recruits"),
            new Profession("Spy", 0, "Disabled in Villager Recruits")};

    /** Why a recruit can't take a profession now, or null. Mirrors Recruits' PromoteScreen gates. */
    @Nullable
    public static String promotionBlocker(Entity e, int profession) {
        if (profession < 0 || profession >= PROFESSIONS.length) return "Unknown profession";
        if (profession >= 5) return "Disabled in Villager Recruits";
        if (!(e instanceof AbstractRecruitEntity r)) return "Not a recruit";
        if (isWorker(e)) return "Workers can't take soldier professions";
        if (isCompanion(e)) return "Already promoted";
        if (profession == 2 && !(com.talhanation.recruits.Main.isSiegeWeaponsLoaded && com.talhanation.recruits.Main.isSiegeWeaponsCompatible)) return "Needs the Siege Weapons mod";
        if (profession == 3 && !(com.talhanation.recruits.Main.isSmallShipsLoaded && com.talhanation.recruits.Main.isSmallShipsCompatible)) return "Needs the Small Ships mod";
        int need = PROFESSIONS[profession].level;
        return r.getXpLevel() < need ? "Needs Recruits level " + need : null;
    }

    /** Recruits replaces the entity on promotion; returns the new one, or null. */
    @Nullable
    public static Entity promote(Entity e, int profession, net.minecraft.server.level.ServerPlayer p) {
        if (!(e instanceof AbstractRecruitEntity r) || !(e.level() instanceof ServerLevel level)) return null;
        String name = e.getName().getString();
        Vec3 at = e.position();
        com.talhanation.recruits.RecruitEvents.promoteRecruit(r, profession, name, p);
        if (!e.isRemoved()) return null;
        for (AbstractRecruitEntity n : level.getEntitiesOfClass(AbstractRecruitEntity.class, new net.minecraft.world.phys.AABB(at, at).inflate(2),
                n -> n != e && n.isAlive() && n.getName().getString().equals(name) && n.tickCount < 5)) return n;
        return null;
    }

    private RecruitsBridge() {}
}
