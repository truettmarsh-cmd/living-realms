package livingrealms.entity;

import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.building.Construction;
import livingrealms.settlement.Job;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Townsfolk walk to their workplace and potter about in it; at night and during alarms they head for the town hall. */
public class TownsfolkGoal extends Goal {
    private final SettlerEntity mob;
    @Nullable private BlockPos target;
    private int linger;

    public TownsfolkGoal(SettlerEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.getRandom().nextInt(40) != 0) return false;
        Settlement s = mob.settlement();
        if (s == null) return false;
        target = pick(s);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() { return target != null && linger > 0; }

    @Override
    public void start() {
        linger = 200 + mob.getRandom().nextInt(400);
        mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, mob.alarm() ? 0.75 : 0.5);
    }

    @Override
    public void tick() {
        linger--;
        if (mob.getNavigation().isDone() && target != null && mob.distanceToSqr(target.getX(), target.getY(), target.getZ()) < 9 && mob.getRandom().nextInt(80) == 0) {
            mob.getNavigation().moveTo(target.getX() + mob.getRandom().nextInt(5) - 2, target.getY(), target.getZ() + mob.getRandom().nextInt(5) - 2, 0.45);
        }
    }

    @Nullable
    BlockPos pick(Settlement s) {
        ServerLevel level = (ServerLevel) mob.level();
        long time = level.getDayTime() % 24000;
        boolean night = time > 12500 && time < 23500;
        if (mob.alarm() || night || mob.job() == Job.IDLE && mob.getRandom().nextBoolean()) return mob.townCenter(s);
        List<Plot> work = new ArrayList<>();
        for (Plot p : s.plots) {
            if (!p.standing()) continue;
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t == null) continue;
            if (t.category.job == mob.job() || (mob.job() == Job.IDLE && (t.category == Category.MARKET || t.category == Category.DECOR || t.category == Category.TAVERN)))
                work.add(p);
        }
        if (work.isEmpty()) {
            int r = s.radius() / 2;
            int x = s.center.getX() + mob.getRandom().nextInt(r * 2 + 1) - r, z = s.center.getZ() + mob.getRandom().nextInt(r * 2 + 1) - r;
            return level.hasChunkAt(new BlockPos(x, 0, z)) ? new BlockPos(x, Construction.ground(level, x, z) + 1, z) : null;
        }
        BoundingBox b = work.get(mob.getRandom().nextInt(work.size())).box();
        int x = b.minX() + mob.getRandom().nextInt(b.getXSpan()), z = b.minZ() + mob.getRandom().nextInt(b.getZSpan());
        return new BlockPos(x, b.minY() + 1, z);
    }
}
