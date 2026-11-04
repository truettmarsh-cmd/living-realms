package livingrealms.battle;

import livingrealms.LivingRealms;
import livingrealms.military.*;
import livingrealms.net.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Battle reports (docs/battle-reports.md). A battle is a burst of combat by one player's recruits: it starts at the
 * first blow struck or taken, takes in hits within {@link #JOIN_RANGE} blocks, and ends after {@link #QUIET_TICKS}
 * with none. Its report is saved per player (BattleData), and the owner gets a toast.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class BattleReports {
    public static final int QUIET_TICKS = 400, JOIN_RANGE = 64;
    /** A battle with no kills, no fallen and less damage than this both ways is a scuffle, not worth a report. */
    public static final float MIN_DAMAGE = 10;

    /** A battle still being fought. */
    static final class Battle {
        final UUID owner;
        final ResourceKey<Level> dim;
        final BlockPos pos;
        BlockPos anchor;
        final long start;
        long last;
        final int clock;
        BattleReport.Kind kind = BattleReport.Kind.SKIRMISH;
        String foe = "";
        final Map<UUID, Joined> fighters = new LinkedHashMap<>();
        final Map<String, Integer> kills = new LinkedHashMap<>();

        Battle(UUID owner, ResourceKey<Level> dim, BlockPos pos, long start, int clock) {
            this.owner = owner; this.dim = dim; this.pos = pos; this.anchor = pos; this.start = start; this.last = start; this.clock = clock;
        }

        void raise(BattleReport.Kind k, String foe) {
            if (k.ordinal() > kind.ordinal()) { kind = k; if (!foe.isEmpty()) this.foe = foe; }
            else if (k == kind && this.foe.isEmpty()) this.foe = foe;
        }

        void touch(long now, Entity e) { last = Math.max(last, now); anchor = e.blockPosition(); }
    }

    /** A fighter and what they were when they joined, to tell what they gained. */
    static final class Joined {
        final BattleReport.Fighter f = new BattleReport.Fighter();
        int level, rank = -1;
        @Nullable SoldierPath path;
        Set<LegendaryPerk> perks = EnumSet.noneOf(LegendaryPerk.class);
    }

    static final Map<UUID, List<Battle>> ACTIVE = new HashMap<>();

    static long now(MinecraftServer server) { return server.overworld().getGameTime(); }

    /** The player who owns this recruit, or null: not a recruit, unowned, or owned by an NPC kingdom. */
    @Nullable
    static UUID owner(MinecraftServer server, @Nullable Entity e) {
        if (e == null || !RecruitsBridge.isRecruit(e)) return null;
        UUID o = RecruitsBridge.owner(e);
        return o != null && player(server, o) ? o : null;
    }

    static boolean player(MinecraftServer server, UUID id) {
        if (server.getPlayerList().getPlayer(id) != null) return true;
        var cache = server.getProfileCache();
        return cache != null && cache.get(id).isPresent();
    }

    /** The owner, their recruits and their tamed animals never count as enemies. */
    static boolean friendly(UUID owner, @Nullable Entity e) {
        if (e == null) return false;
        if (e instanceof Player p) return p.getUUID().equals(owner);
        if (RecruitsBridge.isRecruit(e)) return owner.equals(RecruitsBridge.owner(e));
        return e instanceof OwnableEntity o && owner.equals(o.getOwnerUUID());
    }

    @Nullable
    static Battle battle(UUID owner, ServerLevel level, Entity at, long now, boolean create) {
        List<Battle> list = ACTIVE.computeIfAbsent(owner, k -> new ArrayList<>());
        for (Battle b : list) if (b.dim.equals(level.dimension()) && b.anchor.distSqr(at.blockPosition()) <= JOIN_RANGE * JOIN_RANGE) return b;
        if (!create) return null;
        long day = level.getServer().overworld().getDayTime();
        Battle b = new Battle(owner, level.dimension(), at.blockPosition(), now, (int) (Math.floorMod(day + 6000, 24000) * 1440 / 24000));
        list.add(b);
        return b;
    }

    static Joined join(MinecraftServer server, Battle b, Entity e) {
        Joined j = b.fighters.get(e.getUUID());
        if (j != null) return j;
        j = new Joined();
        j.f.id = e.getUUID();
        j.f.name = e.getName().getString();
        j.f.unit = RecruitsBridge.unitOf(e).label;
        if (e instanceof LivingEntity le) { j.f.hp = le.getHealth(); j.f.max = le.getMaxHealth(); }
        j.level = RecruitsBridge.level(e);
        var record = SoldierProgression.record(server, e);
        if (record != null) {
            var so = record.soldier();
            j.rank = SoldierRanks.rank(so);
            j.path = so.path;
            j.perks = so.legendary.isEmpty() ? EnumSet.noneOf(LegendaryPerk.class) : EnumSet.copyOf(so.legendary);
            j.f.royal = so.royalGuard;
        }
        b.fighters.put(e.getUUID(), j);
        return j;
    }

    static String typeName(Entity e) { return e.getType().getDescription().getString(); }

    static boolean dragon(@Nullable Entity e) {
        try { return livingrealms.dragon.DragonApi.dragon(e); } catch (RuntimeException | LinkageError ex) { return false; }
    }

    // ------------------------------------------------------------------ combat hooks

    /** After armour and every Living Realms modifier: the damage that really lands. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void damaged(LivingDamageEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0 || !(event.getEntity().level() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        LivingEntity victim = event.getEntity();
        Entity attacker = event.getSource().getEntity();
        float amount = Math.min(event.getAmount(), victim.getHealth());
        long now = now(server);
        UUID a = owner(server, attacker);
        if (a != null && attacker != victim && !friendly(a, victim)) {
            Battle b = battle(a, level, attacker, now, true);
            join(server, b, attacker).f.dealt += amount;
            b.touch(now, attacker);
            if (dragon(victim)) b.raise(BattleReport.Kind.DRAGON, typeName(victim));
        }
        UUID v = owner(server, victim);
        if (v != null) {
            boolean hostile = attacker != null && attacker != victim && !friendly(v, attacker);
            // fire, falls and drowning count only inside a battle already going on
            Battle b = battle(v, level, victim, now, hostile);
            if (b == null) return;
            join(server, b, victim).f.taken += amount;
            if (hostile) b.touch(now, victim);
            if (dragon(attacker)) b.raise(BattleReport.Kind.DRAGON, typeName(attacker));
        }
    }

    /**
     * A recruit was saved from a killing blow (the Saint, Undying Rage): noted among its advances in the battle. Called
     * from the LOW-priority save itself, since the damage this class then sees at LOWEST is no longer lethal (or zero).
     */
    public static void saved(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source, String what) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        UUID v = owner(server, victim);
        if (v == null) return;
        Entity attacker = source.getEntity();
        boolean hostile = attacker != null && attacker != victim && !friendly(v, attacker);
        long now = now(server);
        Battle b = battle(v, level, victim, now, hostile);
        if (b == null) return;
        Joined j = join(server, b, victim);
        if (!j.f.advances.contains(what)) j.f.advances.add(what);
        if (hostile) b.touch(now, victim);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void died(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        LivingEntity victim = event.getEntity();
        Entity killer = event.getSource().getEntity();
        if (killer == null) killer = victim.getKillCredit();
        long now = now(server);
        UUID k = owner(server, killer);
        if (k != null && killer != victim && !friendly(k, victim)) {
            Battle b = battle(k, level, killer, now, true);
            join(server, b, killer).f.kills++;
            b.kills.merge(typeName(victim), 1, Integer::sum);
            b.touch(now, killer);
            if (dragon(victim)) b.raise(BattleReport.Kind.DRAGON, typeName(victim));
        }
        UUID v = owner(server, victim);
        if (v != null) {
            boolean hostile = killer != null && killer != victim && !friendly(v, killer);
            Battle b = battle(v, level, victim, now, hostile);
            if (b == null) return;
            Joined j = join(server, b, victim);
            j.f.fell = true;
            j.f.hp = 0;
            if (hostile) b.touch(now, victim);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 3 || ACTIVE.isEmpty()) return;
        try { update(event.getServer(), now(event.getServer())); }
        catch (RuntimeException e) { LivingRealms.LOG.error("Battle reports failed", e); }
    }

    @SubscribeEvent
    public static void starting(ServerStartingEvent event) { ACTIVE.clear(); }

    /** A battle still going when the server stops is reported as it stands. */
    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        try { update(event.getServer(), Long.MAX_VALUE); }
        catch (RuntimeException e) { LivingRealms.LOG.error("Battle reports failed on shutdown", e); }
        ACTIVE.clear();
    }

    /** Once a second: what kind each battle is, and which have gone quiet. Returns the reports written. */
    static List<BattleReport> update(MinecraftServer server, long now) {
        List<BattleReport> out = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, List<Battle>>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            List<Battle> list = it.next().getValue();
            for (Iterator<Battle> bi = list.iterator(); bi.hasNext(); ) {
                Battle b = bi.next();
                BattleContext.classify(server, b);
                if (now - b.last < QUIET_TICKS) continue;
                bi.remove();
                BattleReport r = finish(server, b);
                if (r != null) out.add(r);
            }
            if (list.isEmpty()) it.remove();
        }
        return out;
    }

    // ------------------------------------------------------------------ the report

    @Nullable
    static BattleReport finish(MinecraftServer server, Battle b) {
        BattleReport r = new BattleReport();
        r.start = b.start; r.end = b.last; r.clock = b.clock;
        r.dim = b.dim.location().toString(); r.pos = b.pos;
        r.kind = b.kind; r.foe = b.foe;
        r.kills.putAll(b.kills);
        float damage = 0;
        for (Joined j : b.fighters.values()) {
            if (!j.f.fell) survivor(server, j);
            damage += j.f.dealt + j.f.taken;
            r.fighters.add(j.f);
        }
        if (r.kills.isEmpty() && r.fallen().isEmpty() && damage < MIN_DAMAGE) return null;
        r.place = BattleContext.place(server, b.dim, b.pos);
        BattleData.get(server).add(b.owner, r);
        BattleContext.chronicle(server, b.owner, r);
        notify(server, b.owner, r);
        return r;
    }

    /** Health at the end, and what the fight taught them. */
    static void survivor(MinecraftServer server, Joined j) {
        Entity e = SoldierProgression.findEntity(server, j.f.id);
        if (!(e instanceof LivingEntity le) || !le.isAlive()) return;
        j.f.hp = le.getHealth(); j.f.max = le.getMaxHealth();
        j.f.name = e.getName().getString();
        int level = RecruitsBridge.level(e);
        if (level > j.level) j.f.advances.add("Recruits level " + j.level + " → " + level);
        var record = SoldierProgression.record(server, e);
        if (record == null || j.rank < 0) return;
        var so = record.soldier();
        int rank = SoldierRanks.rank(so);
        if (rank > j.rank) {
            j.f.advances.add(SoldierRanks.titleIndex(rank) > SoldierRanks.titleIndex(j.rank)
                    ? "Promoted to " + SoldierRanks.title(rank) + " (skill rank " + rank + ")" : "Skill rank " + j.rank + " → " + rank);
            if (so.path == null && j.rank < SoldierPath.CHOOSE_RANK && rank >= SoldierPath.CHOOSE_RANK) j.f.advances.add("Can now choose a specialist path");
            if (so.path != null) for (int tier = 1; tier < PathSkill.TIER_RANKS.length; tier++) {
                int need = PathSkill.TIER_RANKS[tier];
                if (j.rank < need && rank >= need) j.f.advances.add(tier == 5 ? "Legendary mastery unlocked" : so.path.label + " skill " + tier + " unlocked");
            }
        }
        if (j.path == null && so.path != null) j.f.advances.add("Took up the " + so.path.label + " path");
        for (LegendaryPerk perk : so.legendary) if (!j.perks.contains(perk)) j.f.advances.add("Legendary perk: " + perk.label);
    }

    static void notify(MinecraftServer server, UUID owner, BattleReport r) {
        ServerPlayer p = server.getPlayerList().getPlayer(owner);
        if (p == null) return;
        Net.send(p, "quest", toast(r));
        if (r.kind != BattleReport.Kind.SKIRMISH || !r.fallen().isEmpty()) p.sendSystemMessage(Component.literal(summary(r)).withStyle(ChatFormatting.GOLD));
    }

    /** A QuestToast payload. */
    static CompoundTag toast(BattleReport r) {
        CompoundTag t = new CompoundTag();
        t.putString("title", r.title());
        BattleReport.Fighter mvp = r.mvp();
        t.putString("text", r.tally() + (mvp == null ? "" : " · MVP " + mvp.name));
        t.putString("icon", icon(r.kind));
        t.putBoolean("big", r.kind != BattleReport.Kind.SKIRMISH);
        return t;
    }

    public static String icon(BattleReport.Kind kind) {
        return switch (kind) {
            case DRAGON -> "minecraft:dragon_head";
            case RAID_DEFENDED -> "minecraft:shield";
            case RAID_ATTACKED -> "minecraft:iron_sword";
            case SIEGE_DEFENDED -> "minecraft:stone_bricks";
            case SIEGE_LAID -> "minecraft:tnt";
            default -> "minecraft:stone_sword";
        };
    }

    /** The chat line: "Battle report: Raid defended at Ashford (1m 20s). 5 slain (3 Zombie, 2 Skeleton). Fallen: Bob. MVP: Aldric." */
    static String summary(BattleReport r) {
        StringBuilder s = new StringBuilder("Battle report: ").append(r.title()).append(r.foe.isEmpty() ? "" : " against " + r.foe)
                .append(" (").append(BattleReport.duration(r.seconds())).append("). ").append(r.slain()).append(" slain");
        if (!r.kills.isEmpty()) s.append(" (").append(r.killList()).append(")");
        s.append(".");
        List<BattleReport.Fighter> fallen = r.fallen();
        if (!fallen.isEmpty()) s.append(" Fallen: ").append(BattleContext.names(fallen.stream().map(f -> f.name).toList())).append(".");
        BattleReport.Fighter mvp = r.mvp();
        if (mvp != null) s.append(" MVP: ").append(mvp.name).append(".");
        return s.append(" Recruit Command → Reports has the rest.").toString();
    }

    @Nullable
    static ResourceKey<Level> dimension(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : ResourceKey.create(Registries.DIMENSION, rl);
    }

    // ------------------------------------------------------------------ network

    /** {reports:[BattleReport...], active:n} for the Reports page. */
    public static CompoundTag payload(MinecraftServer server, UUID player) {
        CompoundTag t = new CompoundTag();
        ListTag list = new ListTag();
        for (BattleReport r : BattleData.get(server).of(player)) list.add(r.save());
        t.put("reports", list);
        t.putInt("active", ACTIVE.getOrDefault(player, List.of()).size());
        return t;
    }

    /** The client asked for its reports ("reports" packet). */
    public static void request(ServerPlayer p) { Net.send(p, "reports", payload(p.server, p.getUUID())); }

    private BattleReports() {}
}
