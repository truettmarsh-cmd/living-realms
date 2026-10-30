package livingrealms.dragon;

import livingrealms.chronicle.Chronicle;
import livingrealms.military.RecruitsBridge;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Wild dragons against towns (docs/dragons.md). Now and then a wild Ice and Fire dragon near a loaded town is drawn to
 * it, and one that flies into a town of its own accord attacks it too. The town rings its bell, its bowmen and its own
 * roost dragon turn on the attacker, scorpions shoot, and the dragon's breath burns people and a capped number of
 * buildings (the town fire system then fights the fire and repairs the damage, the dragon named as its cause). After a
 * minute, or badly hurt, the dragon flies off; the chronicle tells how it went.
 */
public final class DragonAttacks {
    /** Ticks between rolls per town, and between looks for dragons that came by themselves. */
    public static final int ROLL = 1200, LOOK = 100;
    /** A town that was just attacked is left alone for a day. */
    public static final long COOLDOWN = 24000;
    static final Map<UUID, Long> LAST = new HashMap<>();
    static final Map<UUID, Integer> SLAIN = new HashMap<>();
    /** Dragons the town's bowmen, and roost dragons, have been turned on (status and tests). */
    public static final Set<UUID> SHOT_AT = new HashSet<>(), ROOST_ENGAGED = new HashSet<>();

    public static boolean on() { return DragonApi.installed() && DragonConfig.on(DragonConfig.WILD_ATTACKS); }

    /** A wild dragon that may attack: untamed, stage 2 or more, awake and alive. */
    public static boolean candidate(Entity e) {
        return DragonApi.wild(e) && !DragonApi.dead(e) && !Flight.flying(e) && DragonApi.stage(e) >= 2
                && !(e instanceof LivingEntity l && l.isSleeping());
    }

    @Nullable
    public static Flight attack(Settlement s) {
        for (Flight f : Flight.over(s)) if (f.kind == Flight.Kind.WILD) return f;
        return null;
    }

    public static void tick(MinecraftServer server, SettlementManager m, long tick) {
        if (!on()) return;
        for (Settlement s : m.all()) {
            if (s.mirror()) continue;
            int phase = Math.floorMod(s.id.hashCode(), ROLL);
            boolean roll = tick % ROLL == phase, look = tick % LOOK == phase % LOOK;
            Flight f = attack(s);
            if (f != null && tick % 20 == 0) { ServerLevel level = server.getLevel(s.dim); if (level != null) defend(level, s, f); }
            if (!roll && !look || f != null) continue;
            ServerLevel level = server.getLevel(s.dim);
            if (level == null || !level.hasChunkAt(s.center)) continue;
            if (look) intruder(level, s);
            if (roll && attack(s) == null) roll(level, s, level.random, DragonConfig.d(DragonConfig.WILD_CHANCE));
        }
    }

    static boolean resting(ServerLevel level, Settlement s) {
        Long last = LAST.get(s.id);
        return last != null && level.getGameTime() - last < COOLDOWN;
    }

    /** A wild dragon inside the town's land attacks it. */
    @Nullable
    public static Flight intruder(ServerLevel level, Settlement s) {
        if (resting(level, s)) return null;
        int r = s.radius();
        for (Mob d : level.getEntitiesOfClass(Mob.class, new AABB(s.center).inflate(r, 96, r), DragonAttacks::candidate))
            if (s.contains(d.blockPosition())) return start(level, s, d, "came over " + s.name);
        return null;
    }

    /** The chance roll: a wild dragon within range is drawn to the town. */
    @Nullable
    public static Flight roll(ServerLevel level, Settlement s, RandomSource rand, double chance) {
        if (!on() || resting(level, s) || rand.nextDouble() >= chance) return null;
        int range = DragonConfig.i(DragonConfig.WILD_RANGE);
        Mob best = null;
        double bd = Double.MAX_VALUE;
        for (Mob d : level.getEntitiesOfClass(Mob.class, new AABB(s.center).inflate(range, 128, range), DragonAttacks::candidate)) {
            double dd = d.distanceToSqr(s.center.getX(), s.center.getY(), s.center.getZ());
            if (dd < bd) { bd = dd; best = d; }
        }
        return best == null ? null : start(level, s, best, "was drawn to " + s.name);
    }

    /** The dragon attacks: the bell rings, the town is told, the dragon is flown at the town. */
    public static Flight start(ServerLevel level, Settlement s, Mob d, String how) {
        int y = level.hasChunkAt(s.center) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s.center.getX(), s.center.getZ()) : s.center.getY();
        Flight f = Flight.begin(level, d, s.id, Flight.Kind.WILD, new BlockPos(s.center.getX(), y, s.center.getZ()));
        String element = DragonApi.element(d);
        f.cause = "a wild " + element + " dragon's " + (element.equals("lightning") ? "lightning" : "breath");
        LAST.put(s.id, level.getGameTime());
        s.alarmUntil = level.getGameTime() + 400;
        level.playSound(null, s.center, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3f, 1f);
        level.playSound(null, d.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 4f, 1f);
        Roosts.tell(level, s, "Dragon! A wild " + element + " dragon " + how + " and is attacking. The bell rings!", ChatFormatting.RED);
        if (!s.isAi()) s.away.note(s.away.raids, "a wild " + element + " dragon attacked");
        livingrealms.settlement.Simulation.applyStanceAll(level, s);
        s.dirty = true;
        return f;
    }

    /** Every second of an attack: the alarm stays up, bowmen and the roost's dragon turn on the attacker. */
    static void defend(ServerLevel level, Settlement s, Flight f) {
        Entity e = level.getEntity(f.dragon);
        if (!(e instanceof LivingEntity dragon) || f.retreating) return;
        s.alarmUntil = Math.max(s.alarmUntil, level.getGameTime() + 100);
        turnOn(level, s, dragon);
    }

    /** The town's ranged soldiers and its roost dragon fight this attacker (also used against sortie dragons). */
    static int turnOn(ServerLevel level, Settlement s, LivingEntity dragon) {
        int n = 0;
        for (Settlement.Soldier so : s.soldiers) {
            if (so.type != livingrealms.military.UnitType.BOWMAN && so.type != livingrealms.military.UnitType.CROSSBOWMAN && so.type != livingrealms.military.UnitType.DRAGON_RIDER) continue;
            if (level.getEntity(so.entity) instanceof Mob bow && bow.isAlive() && bow.distanceToSqr(dragon) < 72 * 72) {
                bow.setTarget(dragon);
                RecruitsBridge.aggressive(bow);
                SHOT_AT.add(dragon.getUUID());
                n++;
            }
        }
        TamableAnimal own = Roosts.dragon(level.getServer(), s);
        if (own != null && own != dragon && !Flight.flying(own) && own.getPassengers().isEmpty()
                && own.distanceToSqr(dragon) < 96 * 96 && DragonApi.stage(own) >= 2) {
            DragonApi.command(own, DragonApi.WANDER);
            own.setTarget(dragon);
            ROOST_ENGAGED.add(own.getUUID());
            n++;
        }
        return n;
    }

    /** The flight is over: the chronicle tells how the attack went. */
    static void ended(MinecraftServer server, Flight f, @Nullable Entity e) {
        Settlement s = SettlementManager.get(server).get(f.town);
        if (s == null) return;
        String element = e != null ? DragonApi.element(e) : "wild";
        String what = "A wild " + element + " dragon fell upon " + s.name;
        if (!f.struck.isEmpty()) what += " and set " + list(f.struck) + " alight";
        if (f.slain) {
            SLAIN.merge(s.id, 1, Integer::sum);
            what += (Scorpions.hitBy(f.dragon) ? ". The scorpions brought it down" : ". It was slain over the town") + ".";
        } else what += f.strafes > 0 ? ", then flew off." : ", but was driven off before it could do harm.";
        Chronicle.record(s, Chronicle.Kind.DRAGON, what);
        ServerLevel level = server.getLevel(s.dim);
        if (level != null) Roosts.tell(level, s, f.slain ? "The dragon is slain!" : "The dragon has flown off", f.slain ? ChatFormatting.GOLD : ChatFormatting.YELLOW);
        s.dirty = true;
    }

    /** "the bakery and two cottages" */
    static String list(List<String> names) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (String x : names) n.merge(x, 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        String[] words = {"", "", "two", "three", "four", "five"};
        n.forEach((name, k) -> parts.add(k == 1 ? "the " + name : (k < words.length ? words[k] : String.valueOf(k)) + " " + name + (name.endsWith("s") ? "" : "s")));
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** One line for the City Manager while a dragon is over the town. */
    @Nullable
    public static String status(Settlement s) {
        for (Flight f : Flight.over(s)) {
            String who = f.kind == Flight.Kind.WILD ? "A wild dragon" : "An enemy dragon";
            return who + (f.retreating ? " is flying off" : " is attacking") + " · " + f.strafes + " breaths, " + f.fires + " fires"
                    + (Scorpions.armed(s) ? " · scorpions loosing" : "");
        }
        return null;
    }

    private DragonAttacks() {}
}
