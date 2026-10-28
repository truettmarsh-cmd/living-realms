package livingrealms.compat;

import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsFaction;
import livingrealms.LivingRealms;
import livingrealms.settlement.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Entry points for the pack-mod integrations. Each call checks that its mod is loaded first, so the classes that
 * touch a mod's API are never loaded without it and nothing changes when the mod is absent.
 */
public final class Compat {
    public static final String VASSALS = "vassalsuzerain", RANKS = "ranksandtitles", MORALE = "recruitsmoraleaddon",
            SIEGE_WEAPONS = "siegeweapons", SIEGE_MACHINES = "siegemachines", SIEGE_CREWS = "siege_machine_compat",
            SIEGE_OVERHAUL = "siegeoverhaul", WORKERS = "workers", CORPSE = "corpse", HERALDRY = "knightsheraldry";
    private static final Set<String> FAILED = new HashSet<>();

    /** A ruler's title from the size of their kingdom. */
    public enum Rank {
        COUNT("Count"), DUKE("Duke"), KING("King");
        public final String label;
        Rank(String label) { this.label = label; }
    }

    public static boolean loaded(String mod) { return ModList.get() != null && ModList.get().isLoaded(mod); }

    static boolean on(ForgeConfigSpec.BooleanValue v) {
        try { return v.get(); } catch (IllegalStateException notLoaded) { return true; }
    }

    static void fail(String what, Throwable t) {
        if (FAILED.add(what)) LivingRealms.LOG.warn("Living Realms {} integration failed; it stays off until restart", what, t);
    }

    static boolean ok(String what) { return !FAILED.contains(what); }

    @Nullable
    public static Rank rank(SettlementManager m, @Nullable UUID owner) {
        if (owner == null) return null;
        List<Settlement> towns = m.ownedBy(owner);
        if (towns.isEmpty()) return null;
        int top = towns.stream().mapToInt(s -> s.tier.number()).max().orElse(1);
        if (towns.size() >= 3 || top >= Tier.CAPITAL.number()) return Rank.KING;
        if (towns.size() >= 2 || top >= Tier.CITY.number()) return Rank.DUKE;
        return Rank.COUNT;
    }

    /** The Recruits faction this player leads, if any. Vassal & Suzerain and Ranks and Titles both work on factions. */
    @Nullable
    static RecruitsFaction factionLedBy(@Nullable UUID player) {
        if (player == null || FactionEvents.recruitsFactionManager == null) return null;
        for (RecruitsFaction f : FactionEvents.recruitsFactionManager.getFactions())
            if (player.equals(f.getTeamLeaderUUID())) return f;
        return null;
    }

    // ------------------------------------------------------------------ hooks

    /** Why this raid may not start, or null. */
    @Nullable
    public static String raidBlocked(MinecraftServer server, Settlement source, Settlement target) {
        if (!loaded(VASSALS) || !on(CompatConfig.FEUDAL_OATHS) || !ok(VASSALS) || source.isAi() || target.isAi()) return null;
        try { return VassalCompat.blocked(server, source.owner, target.owner); } catch (Throwable t) { fail(VASSALS, t); return null; }
    }

    public static void raidStarted(MinecraftServer server, Raids.Raid raid, Settlement source, Settlement target) {
        try { if (loaded(VASSALS) && on(CompatConfig.FEUDAL_OATHS) && ok(VASSALS) && !target.isAi()) VassalCompat.warnLiege(server, source, target); }
        catch (Throwable t) { fail(VASSALS, t); }
        try { if (on(CompatConfig.SIEGE_TRAINS) && ok("siege")) SiegeTrains.deploy(server, raid, source, target); }
        catch (Throwable t) { fail("siege", t); }
    }

    public static void raidEnded(MinecraftServer server, Raids.Raid raid, @Nullable Settlement source) {
        try { SiegeTrains.recall(server, raid, source); } catch (Throwable t) { fail("siege", t); }
    }

    /** Each second of an active raid: siege crews shift or lift their fire as the attackers close in. */
    public static void raidTick(MinecraftServer server, Raids.Raid raid, Settlement target,
                                java.util.List<? extends Entity> attackers, java.util.List<? extends Entity> defenders) {
        try { SiegeTrains.aim(server, raid, target, attackers, defenders); } catch (Throwable t) { fail("siege", t); }
    }

    /** Hostile soldiers the town's alarm should treat like monsters. */
    public static boolean hostile(Entity e) {
        return loaded(SIEGE_OVERHAUL) && on(CompatConfig.RAIDER_ALARM) && SiegeOverhaulCompat.raider(e);
    }

    /** Living Realms morale changes (honours, refusals) also move the Morale addon's battle morale. */
    public static void morale(Entity e, float delta) {
        if (!loaded(MORALE) || !on(CompatConfig.MORALE) || !ok(MORALE)) return;
        try { MoraleCompat.add(e, delta); } catch (Throwable t) { fail(MORALE, t); }
    }

    /** Workers-mod workers fill town jobs before townsfolk do. */
    public static void staff(Settlement s, Settlement.Derived d) {
        if (loaded(WORKERS) && on(CompatConfig.WORKERS_STAFF)) WorkersCompat.staff(s, d);
    }

    /** Extra equipment mods whose items become smithing patterns. */
    public static Set<String> equipmentMods() {
        return loaded(HERALDRY) && on(CompatConfig.HERALDRY_GEAR) ? Set.of(HERALDRY) : Set.of();
    }

    /** Pack-mod facts for the City Manager overview; empty when no integration applies. */
    public static CompoundTag view(Settlement s, ServerPlayer p) {
        ListTag lines = new ListTag();
        try {
            SettlementManager m = SettlementManager.get(p.server);
            Rank rank = rank(m, s.owner);
            if (rank != null && loaded(VASSALS) && ok(VASSALS)) VassalCompat.view(p.server, s, rank, lines);
            if (rank != null && loaded(RANKS) && ok(RANKS)) RanksCompat.view(p.server, s, lines);
            SiegeTrains.view(p.server, s, lines);
            if (loaded(WORKERS) && on(CompatConfig.WORKERS_STAFF)) WorkersCompat.view(s, lines);
            if (loaded(CORPSE) && on(CompatConfig.CORPSE_SALVAGE)) CorpseCompat.view(p.server, s, lines);
        } catch (Throwable t) { fail("overview", t); }
        CompoundTag t = new CompoundTag();
        t.put("lines", lines);
        return t;
    }

    static CompoundTag line(String label, String value, String... tip) {
        CompoundTag t = new CompoundTag();
        t.putString("label", label); t.putString("value", value);
        ListTag l = new ListTag();
        for (String s : tip) l.add(net.minecraft.nbt.StringTag.valueOf(s));
        t.put("tip", l);
        return t;
    }

    private Compat() {}
}
