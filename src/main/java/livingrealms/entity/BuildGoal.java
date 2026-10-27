package livingrealms.entity;

import livingrealms.Config;
import livingrealms.building.Construction;
import livingrealms.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.EnumSet;

/** A builder walks to the next block of its plot, takes the material from the town stockpile and places it. */
public class BuildGoal extends Goal {
    private final SettlerEntity mob;
    @Nullable private Plot plot;
    private int cooldown, stuck, repath, hops;
    private double lastDist;

    public BuildGoal(SettlerEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.job() != Job.BUILDER || mob.alarm()) return false;
        Settlement s = mob.settlement();
        if (s == null) return false;
        plot = claim(s);
        return plot != null;
    }

    @Override
    public boolean canContinueToUse() {
        Settlement s = mob.settlement();
        return s != null && plot != null && plot.active() && s.plots.contains(plot) && !mob.alarm()
                && mob.getUUID().equals(plot.builder) && AutoBuild.mayWork(s, plot);
    }

    /** Takes the most urgent loaded plot no other builder is on. */
    @Nullable
    Plot claim(Settlement s) {
        ServerLevel level = (ServerLevel) mob.level();
        for (Plot p : s.plots) if (mob.getUUID().equals(p.builder) && p.active() && AutoBuild.mayWork(s, p)) return p;
        for (Plot p : Simulation.activePlots(s)) {
            if (p.builder != null && level.getEntity(p.builder) != null) continue;
            if (!Construction.loaded(level, p)) continue;
            if (p.status.startsWith("Waiting") && mob.getRandom().nextInt(10) > 0) continue;
            p.builder = mob.getUUID();
            return p;
        }
        return null;
    }

    @Override
    public void start() {
        stuck = 0;
        cooldown = 10;
    }

    @Override
    public void stop() {
        if (plot != null && mob.getUUID().equals(plot.builder)) plot.builder = null;
        plot = null;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        Settlement s = mob.settlement();
        if (s == null || plot == null) return;
        ServerLevel level = (ServerLevel) mob.level();
        if (plot.paid > plot.placed) { Construction.catchUp(level, s, plot, 2000); return; }
        Construction.Step st = Construction.nextWork(level, s, plot, 96);
        if (st == null) return;
        BlockPos target = st.pos();
        mob.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        double dist = mob.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (dist > 20) {
            if (--repath <= 0) {
                repath = 20;
                mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.65);
            }
            if (Math.abs(dist - lastDist) < 0.05) stuck++; else stuck = Math.max(0, stuck - 1);
            lastDist = dist;
            if (stuck > 60) { hop(level, target); stuck = 0; }
            // builders reach up to 8 blocks, so walls and roofs get done from the ground
            // after a couple of hops (tall towers, roofs over water) they work from where they stand
            if (dist > 64 && !(hops >= 2 && dist < 24 * 24)) return;
        }
        if (--cooldown > 0) return;
        cooldown = Math.max(1, (int) Math.round(Config.BUILD_INTERVAL.get() / Simulation.speed(s)));
        Construction.Result r = Construction.work(level, s, plot);
        if (r == Construction.Result.PLACED) {
            if (dist <= 64) hops = 0;
            mob.swing(InteractionHand.MAIN_HAND);
            var state = level.getBlockState(target);
            if (!state.isAir())
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0);
            s.dirty = true;
        } else if (r == Construction.Result.MISSING) {
            cooldown = 100;
            plot.builder = null;
            s.dirty = true;
        }
    }

    /** Teleports next to the site, on top of whatever stands there. */
    void hop(ServerLevel level, BlockPos target) {
        BoundingBox b = plot.box();
        int[][] spots = {{b.minX() - 1, b.minZ() - 1}, {b.maxX() + 1, b.maxZ() + 1}, {target.getX(), b.maxZ() + 1}, {b.minX() - 1, target.getZ()}};
        int[] sp = spots[mob.getRandom().nextInt(spots.length)];
        int y = Construction.ground(level, sp[0], sp[1]) + 1;
        if (Math.abs(y - target.getY()) > 12) y = target.getY() + 1;
        mob.teleportTo(sp[0] + 0.5, y, sp[1] + 0.5);
        hops++;
        mob.getNavigation().stop();
    }
}
