package livingrealms.mixin;

import livingrealms.military.DragonRiders;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ice and Fire normally treats every non-player passenger as prey. Leave all other passengers untouched. */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.entity.EntityDragonBase", remap = false)
public abstract class DragonPassengerMixin {
    // Both official development and Forge production method names; this optional target is never remapped by AP.
    @Inject(method = {"positionRider", "m_19956_"}, at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void livingrealms$seatRecruit(Entity passenger, Entity.MoveFunction move, CallbackInfo ci) {
        if (DragonRiders.positionPassenger((Entity)(Object)this, passenger, move)) ci.cancel();
    }
}
