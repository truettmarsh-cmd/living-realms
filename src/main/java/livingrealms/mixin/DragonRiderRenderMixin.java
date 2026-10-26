package livingrealms.mixin;

import livingrealms.military.RecruitsBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Select Ice and Fire's existing saddle rendering path without making a recruit control dragon movement. */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.client.render.entity.layer.LayerDragonRider", remap = false)
public abstract class DragonRiderRenderMixin {
    @Group(name = "livingrealms$renderRider", min = 1)
    @Redirect(method = {"render", "m_6494_"}, at = @At(value = "INVOKE",
            target = "Lcom/github/alexthe666/iceandfire/entity/EntityDragonBase;getControllingPassenger()Lnet/minecraft/world/entity/LivingEntity;", remap = false), remap = false, require = 0)
    private LivingEntity livingrealms$developmentRider(@Coerce Entity dragon) { return livingrealms$rider(dragon); }

    @Group(name = "livingrealms$renderRider", min = 1)
    @Redirect(method = {"render", "m_6494_"}, at = @At(value = "INVOKE",
            target = "Lcom/github/alexthe666/iceandfire/entity/EntityDragonBase;m_6688_()Lnet/minecraft/world/entity/LivingEntity;", remap = false), remap = false, require = 0)
    private LivingEntity livingrealms$productionRider(@Coerce Entity dragon) { return livingrealms$rider(dragon); }

    private static LivingEntity livingrealms$rider(Entity dragon) {
        LivingEntity controller = dragon.getControllingPassenger();
        if (controller != null) return controller;
        if (!(dragon instanceof TamableAnimal tame) || tame.getOwnerUUID() == null) return null;
        for (Entity passenger : dragon.getPassengers())
            if (passenger instanceof LivingEntity living && RecruitsBridge.isRecruit(passenger)
                    && tame.getOwnerUUID().equals(RecruitsBridge.owner(passenger))) return living;
        return null;
    }
}
