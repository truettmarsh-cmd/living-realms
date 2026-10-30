package livingrealms.dragon;

import livingrealms.chronicle.Chronicle;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import livingrealms.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * NPC kingdoms' dragons on raids (docs/dragons.md). When an NPC kingdom with a grown roost dragon (stage 3+, at least
 * half its health) starts a raid, the dragon flies to the target with a Dragon Rider on its back. It circles over the
 * breach (or the town centre) and breathes on the defenders for one sortie of attackSeconds, setting at most
 * maxFiresPerAttack buildings alight, while the defenders' bowmen and scorpions shoot at it. Then it flies home and the
 * rider dismounts. One dragon per raid.
 */
public final class Sorties {
    public static boolean on() { return DragonApi.installed() && DragonConfig.on(DragonConfig.SORTIES); }

    @Nullable
    public static Flight of(Raids.Raid raid) {
        for (Flight f : Flight.ACTIVE.values())
            if (f.kind == Flight.Kind.SORTIE && raid.source.equals(f.raidSource) && raid.target.equals(f.raidTarget)) return f;
        return null;
    }

    /** A dragon of the attacking kingdom ready to fly, or null. */
    @Nullable
    static TamableAnimal ready(MinecraftServer server, SettlementManager m, Settlement source, List<Settlement> out) {
        for (Settlement c : Kingdoms.cities(m, source)) {
            TamableAnimal d = Roosts.dragon(server, c);
            if (d == null || Flight.flying(d) || !d.getPassengers().isEmpty() || d.isPassenger() || DragonApi.stage(d) < Roosts.RIDER_STAGE
                    || d.getHealth() < d.getMaxHealth() * .5f || d.level() != server.getLevel(source.dim)) continue;
            out.add(c);
            return d;
        }
        return null;
    }

    /** Raids.start: an NPC kingdom brings its dragon. Returns the sortie, if one flies. */
    @Nullable
    public static Flight raidStarted(MinecraftServer server, SettlementManager m, Raids.Raid raid, Settlement source, Settlement target) {
        if (!on() || !source.isAi() || of(raid) != null) return null;
        List<Settlement> homes = new java.util.ArrayList<>();
        TamableAnimal d = ready(server, m, source, homes);
        ServerLevel level = server.getLevel(target.dim);
        if (d == null || level == null) return null;
        Settlement home = homes.get(0);
        BlockPos focus = focus(level, raid, target);
        // it flies in from the attackers' side and starts its circle at the edge of the town
        Vec3 from = Vec3.atCenterOf(source.center).subtract(Vec3.atCenterOf(focus)).multiply(1, 0, 1);
        if (from.lengthSqr() < 1) from = new Vec3(1, 0, 0);
        Vec3 start = Vec3.atCenterOf(focus).add(from.normalize().scale(40)).add(0, Flight.HEIGHT + 6, 0);
        if (level.hasChunkAt(BlockPos.containing(start))) d.teleportTo(start.x, start.y, start.z);
        Flight f = Flight.begin(level, d, target.id, Flight.Kind.SORTIE, focus);
        f.home = home.id;
        f.raidSource = raid.source;
        f.raidTarget = raid.target;
        f.cause = "the dragonfire of " + source.name;
        DragonData.Roost r = Roosts.record(server, home);
        if (r != null) r.sorties++;
        Entity rider = RecruitsBridge.spawn(level, UnitType.BOWMAN, d.blockPosition(), Roosts.owner(home), home.name + " Dragon Rider");
        if (rider != null) {
            RecruitsBridge.setSlot(rider, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            if (rider.startRiding(d, true)) f.rider = rider.getUUID();
            else rider.discard();
        }
        String name = DragonApi.name(d);
        announce(level, target, "A dragon! " + name + " of " + home.name + " flies against " + target.name + (f.rider != null ? " with a rider on its back" : "") + "!");
        announce(level, source, name + " flies with the army against " + target.name + ".");
        return f;
    }

    static void announce(ServerLevel level, Settlement s, String msg) {
        Roosts.tell(level, s, msg, net.minecraft.ChatFormatting.RED);
        if (!s.isAi()) s.away.note(s.away.raids, msg);
    }

    /** Over a walled town still closed, the breach; else the town centre. */
    static BlockPos focus(ServerLevel level, Raids.Raid raid, Settlement target) {
        BlockPos at = raid.siege.walled && !raid.siege.breached && !raid.siege.at.equals(BlockPos.ZERO) ? raid.siege.at : target.center;
        int y = level.hasChunkAt(at) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) : at.getY();
        return new BlockPos(at.getX(), y, at.getZ());
    }

    /** Each tick of a sortie: follow the siege, and let the defenders turn on the dragon. */
    static void step(ServerLevel level, Flight f, Mob d) {
        if (f.retreating) return;
        SettlementManager m = SettlementManager.get(level.getServer());
        Raids.Raid raid = null;
        for (Raids.Raid r : m.raids) if (r.source.equals(f.raidSource) && r.target.equals(f.raidTarget)) raid = r;
        Settlement target = m.get(f.town);
        if (raid == null || target == null) { Flight.retreat(f, d); return; }
        if (level.getGameTime() % 20 == 0) {
            f.focus = focus(level, raid, target);
            DragonAttacks.turnOn(level, target, d);
        }
    }

    /** Raids.finish: the dragon turns for home. */
    public static void raidEnded(Raids.Raid raid) {
        for (Flight f : List.copyOf(Flight.ACTIVE.values()))
            if (f.kind == Flight.Kind.SORTIE && raid.source.equals(f.raidSource) && raid.target.equals(f.raidTarget) && !f.retreating) {
                f.retreating = true;
                f.away = Vec3.atCenterOf(f.focus).add(0, 40, 0);
                f.until = 0;
            }
    }

    /** The sortie is over: the rider dismounts, the dragon goes home, or the chronicles tell of its fall. */
    static void ended(MinecraftServer server, Flight f, @Nullable Entity e) {
        SettlementManager m = SettlementManager.get(server);
        ServerLevel level = server.getLevel(f.dim);
        if (level != null && f.rider != null) {
            Entity rider = level.getEntity(f.rider);
            if (rider != null) { rider.stopRiding(); rider.discard(); }
        }
        Settlement home = f.home == null ? null : m.get(f.home), target = m.get(f.town);
        String name = e != null ? DragonApi.name(e) : "The dragon";
        if (f.slain) {
            if (home != null) Chronicle.record(home, Chronicle.Kind.DRAGON, name + ", the dragon of " + home.name + ", was shot down over "
                    + (target == null ? "the enemy" : target.name) + ".");
            if (target != null) Chronicle.record(target, Chronicle.Kind.DRAGON, "The defenders of " + target.name + " brought down " + name
                    + (home == null ? "" : ", the dragon of " + home.name) + (Scorpions.hitBy(f.dragon) ? ", with scorpion bolts" : "") + ".");
            return;
        }
        if (target != null && f.strafes > 0) Chronicle.record(target, Chronicle.Kind.DRAGON, name + (home == null ? "" : ", the dragon of " + home.name)
                + ", flew over " + target.name + " during the raid" + (f.struck.isEmpty() ? " and scorched its defenders." : " and set " + DragonAttacks.list(f.struck) + " alight."));
        if (e != null && home != null && level != null) {
            var p = Roosts.roost(home);
            if (p != null && level.hasChunkAt(Roosts.perch(p))) {
                BlockPos perch = Roosts.perch(p);
                e.teleportTo(perch.getX() + .5, perch.getY(), perch.getZ() + .5);
                DragonApi.command(e, DragonApi.SIT);
            }
            if (e instanceof Mob mob) mob.setTarget(null);
        }
    }

    private Sorties() {}
}
