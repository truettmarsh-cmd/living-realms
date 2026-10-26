package livingrealms.military;

import livingrealms.Config;
import livingrealms.LivingRealms;
import livingrealms.settlement.Settlement;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class RecruitOrigins {
    public static final String KEY = "LivingRealmsOrigin";
    public static final UUID HEALTH = UUID.fromString("f5026bc4-6246-4475-9a6d-31cb5e288d01");
    public static final UUID SPEED = UUID.fromString("f5026bc4-6246-4475-9a6d-31cb5e288d02");
    public static RecruitOrigin origin(Entity entity) { return RecruitOrigin.byId(entity.getPersistentData().getString(KEY)); }
    public static void born(Entity entity) {
        if (!RecruitsBridge.isRecruit(entity) || entity.level().isClientSide || entity.getPersistentData().contains(KEY)) return;
        int h = Config.ORIGIN_HUMAN.get(), d = Config.ORIGIN_DWARF.get(), e = Config.ORIGIN_ELF.get(), g = Config.ORIGIN_GIANT.get();
        int total = h + d + e + g;
        assign(entity, RecruitOrigin.select(total == 0 ? 0 : entity.level().random.nextInt(total), h, d, e, g));
        if (entity instanceof LivingEntity living) living.setHealth(living.getMaxHealth());
    }
    /** Explicit assignment for spawn/test; normal gameplay calls born only for newly trained recruits. */
    public static void assign(Entity entity, RecruitOrigin origin) {
        if (!RecruitsBridge.isRecruit(entity) || entity.level().isClientSide) return;
        entity.getPersistentData().putString(KEY, origin.name());
        apply(entity, origin);
    }
    public static void sync(Settlement.Soldier soldier, Entity entity) {
        if (!entity.getPersistentData().contains(KEY)) assign(entity, soldier.origin);
        soldier.origin = origin(entity);
        apply(entity, soldier.origin);
    }
    public static void apply(Entity entity, RecruitOrigin origin) {
        if (!(entity instanceof LivingEntity living) || entity.level().isClientSide) return;
        modifier(living, Attributes.MAX_HEALTH, HEALTH, origin.health, AttributeModifier.Operation.ADDITION);
        modifier(living, Attributes.MOVEMENT_SPEED, SPEED, origin.speed, AttributeModifier.Operation.MULTIPLY_BASE);
        if (living.getHealth() > living.getMaxHealth()) living.setHealth(living.getMaxHealth());
        PehkuiBridge.apply(entity, origin);
    }
    private static void modifier(LivingEntity entity, Attribute attribute, UUID id, double amount, AttributeModifier.Operation operation) {
        var instance = entity.getAttribute(attribute);
        if (instance == null) return;
        var old = instance.getModifier(id);
        if (old != null && old.getAmount() == amount && old.getOperation() == operation) return;
        instance.removeModifier(id);
        if (amount != 0) instance.addPermanentModifier(new AttributeModifier(id, "Living Realms origin", amount, operation));
    }
    @SubscribeEvent public static void joined(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (event.getLevel().isClientSide || !RecruitsBridge.isRecruit(entity)) return;
        if (entity.getPersistentData().contains(KEY)) apply(entity, origin(entity));
        else if (!event.loadedFromDisk() && Config.ORIGIN_NATIVE_SPAWNS.get()) born(entity);
    }
    private RecruitOrigins() {}
}
