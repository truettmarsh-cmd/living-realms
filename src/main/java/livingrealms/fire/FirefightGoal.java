package livingrealms.fire;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.EnumSet;

/** A townsperson called to the bucket brigade runs to the nearest fire; Fires.brigade does the dousing. */
public class FirefightGoal extends Goal {
    private final PathfinderMob mob;
    @Nullable private BlockPos target;
    private int repath;

    public FirefightGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.level().isClientSide || mob.tickCount % 10 != 0) return false;
        target = Fires.targetFor(mob);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() { return target != null; }

    @Override
    public void start() { repath = 0; }

    @Override
    public void tick() {
        if (--repath > 0) { if (target != null) mob.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5); return; }
        repath = 20;
        target = Fires.targetFor(mob);
        if (target == null) return;
        double dx = target.getX() + .5 - mob.getX(), dz = target.getZ() + .5 - mob.getZ();
        if (dx * dx + dz * dz < 9) { mob.getNavigation().stop(); return; }
        Path path = mob.getNavigation().createPath(target, 3);
        if (path == null) path = mob.getNavigation().createPath(target.below(Math.max(0, (int) (target.getY() - mob.getY()))), 4);
        mob.getNavigation().moveTo(path, 0.8);
    }

    @Override
    public void stop() { target = null; mob.getNavigation().stop(); }
}
