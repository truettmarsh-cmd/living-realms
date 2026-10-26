package livingrealms.entity;

import livingrealms.Registry;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Construction;
import livingrealms.settlement.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A visible member of a settlement: builders who raise its buildings, and townsfolk who go about their work.
 * They are not saved with the chunk; the settlement respawns them, so no duplicates pile up.
 */
public class SettlerEntity extends PathfinderMob {
    private static final EntityDataAccessor<Integer> JOB = SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
    private static final String[] NAMES = {"Alys", "Bertram", "Cuthbert", "Dunstan", "Edith", "Fenwick", "Godric", "Hilda", "Ingram",
            "Jocelyn", "Kenric", "Lettice", "Mabel", "Ned", "Odo", "Petronel", "Ralf", "Sybil", "Tybalt", "Wat", "Agnes", "Hob", "Elric", "Rowena"};
    @Nullable private UUID settlement;
    private boolean leaving;

    public SettlerEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanPassDoors(true);
        }
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.5).add(Attributes.FOLLOW_RANGE, 64);
    }

    public static void spawn(ServerLevel level, Settlement s, Job job, BlockPos pos) {
        SettlerEntity e = Registry.SETTLER.get().create(level);
        if (e == null) return;
        e.settlement = s.id;
        e.setJob(job);
        e.moveTo(pos.getX() + 0.5 + level.random.nextInt(5) - 2, pos.getY(), pos.getZ() + 0.5 + level.random.nextInt(5) - 2, level.random.nextFloat() * 360, 0);
        e.setCustomName(Component.literal(NAMES[level.random.nextInt(NAMES.length)]));
        e.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(switch (job) {
            case BUILDER -> Items.STONE_PICKAXE;
            case FARMER -> Items.IRON_HOE;
            case LUMBERJACK -> Items.IRON_AXE;
            case MINER -> Items.IRON_PICKAXE;
            case SMITH -> Items.IRON_INGOT;
            case SCHOLAR -> Items.BOOK;
            case MERCHANT -> Items.EMERALD;
            case INNKEEPER -> Items.HONEY_BOTTLE;
            case PRIEST -> Items.CANDLE;
            default -> Items.AIR;
        }));
        level.addFreshEntity(e);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(JOB, Job.IDLE.ordinal());
    }

    public Job job() { return Job.values()[Math.max(0, Math.min(Job.values().length - 1, entityData.get(JOB)))]; }

    public void setJob(Job j) { entityData.set(JOB, j.ordinal()); }

    @Nullable
    public UUID settlementId() { return settlement; }

    @Nullable
    public Settlement settlement() {
        if (settlement == null || level().isClientSide) return null;
        return SettlementManager.get(level().getServer()).get(settlement);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Monster.class, 10f, 0.6, 0.75));
        goalSelector.addGoal(2, new OpenDoorGoal(this, true));
        goalSelector.addGoal(3, new BuildGoal(this));
        goalSelector.addGoal(4, new TownsfolkGoal(this));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && tickCount % 100 == 0 && settlement() == null) {
            leaving = true;
            discard();
        }
    }

    /** Settlers are respawned by their settlement rather than saved. */
    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    public boolean removeWhenFarAway(double d) { return false; }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        Settlement s = settlement();
        if (s != null && !leaving) {
            s.population = Math.max(1, s.population - 1);
            s.log(getName().getString() + " the " + job().label.toLowerCase() + " was killed");
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide && hand == InteractionHand.MAIN_HAND) {
            Settlement s = settlement();
            String where = s == null ? "" : " of " + s.name;
            String doing = "";
            if (s != null && job() == Job.BUILDER) {
                for (Plot p : s.plots) if (getUUID().equals(p.builder)) {
                    BuildingType t = BuildingCatalog.get(p.typeId);
                    doing = " — working on the " + (p.typeId.equals("road") ? "road" : t == null ? "site" : t.name)
                            + " (" + (int) (p.progress() * 100) + "%)" + (p.status.isEmpty() ? "" : ", " + p.status.toLowerCase());
                }
                if (doing.isEmpty()) doing = " — waiting for plans";
            }
            player.displayClientMessage(Component.literal(getName().getString() + ", " + job().label.toLowerCase() + where + doing)
                    .withStyle(ChatFormatting.YELLOW), true);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        if (settlement != null) t.putUUID("settlement", settlement);
        t.putInt("job", job().ordinal());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        if (t.hasUUID("settlement")) settlement = t.getUUID("settlement");
        entityData.set(JOB, t.getInt("job"));
    }

    @Override
    protected SoundEvent getAmbientSound() { return level().random.nextInt(4) == 0 ? SoundEvents.VILLAGER_AMBIENT : null; }

    @Override
    protected SoundEvent getHurtSound(DamageSource s) { return SoundEvents.VILLAGER_HURT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.VILLAGER_DEATH; }

    boolean alarm() {
        Settlement s = settlement();
        return s != null && s.alarmUntil > level().getGameTime();
    }

    BlockPos townCenter(Settlement s) {
        return new BlockPos(s.center.getX(), Construction.ground((ServerLevel) level(), s.center.getX(), s.center.getZ()) + 1, s.center.getZ());
    }
}
