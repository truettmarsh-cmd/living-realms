package livingrealms.settlement;

import com.talhanation.recruits.SiegeEvent;
import livingrealms.Config;
import livingrealms.LivingRealms;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierProgression;
import livingrealms.settlement.vx.Vx;
import livingrealms.settlement.vx.VxBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Village Expansion integration. Its villages appear here as mirror settlements that Living Realms never simulates;
 * player kingdoms fight under a Recruits faction (their realm) inside its diplomacy; every conquest updates both mods.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class Realms {
    public static final int SYNC = 100, WAR_RANGE = 1000, WAR_NEAR = 600, WAR_DECISION = 12000, TRUCE = 24000;
    static final Map<String, Long> NEXT_WAR = new HashMap<>();
    /** Set while Living Realms itself moves villages, so its own siege-success echoes are ignored. */
    static boolean busy;

    public static boolean on() { return Vx.active() && VxBridge.ready(); }

    /** Mirrors stay dormant while Village Expansion is missing or switched off. */
    public static boolean dormant(Settlement s) { return s.mirror() && !on(); }

    public static UUID kingdomFor(String faction) {
        return UUID.nameUUIDFromBytes(("village_recruits:" + faction).getBytes(StandardCharsets.UTF_8));
    }

    /** The Recruits faction a kingdom fights under, created on demand for player and legacy NPC kingdoms. */
    @Nullable
    public static String realm(MinecraftServer server, SettlementManager m, Settlement s) {
        if (!on()) return null;
        if (s.mirror()) return s.vxFaction;
        ServerLevel level = server.overworld();
        if (s.isAi()) return VxBridge.npcRealm(level, s.kingdomId(), s.name);
        String id = VxBridge.realm(level, s.owner, s.ownerName, m.realms.get(s.owner));
        m.realms.put(s.owner, id);
        return id;
    }

    /** A player's realm id without creating one. */
    @Nullable
    static String knownRealm(SettlementManager m, Settlement s) {
        return s.mirror() ? s.vxFaction : s.owner == null ? null : m.realms.get(s.owner);
    }

    /** Any settlement of the kingdom behind a faction id, or null. */
    @Nullable
    static Settlement kingdomOf(SettlementManager m, String faction) {
        for (var e : m.realms.entrySet()) if (e.getValue().equals(faction)) {
            var cities = m.ownedBy(e.getKey());
            if (!cities.isEmpty()) return cities.get(0);
        }
        for (Settlement s : m.all()) if (faction.equals(s.vxFaction)) return s;
        return null;
    }

    /** A stand-in winner for a faction Living Realms has not mirrored yet. */
    static Settlement standIn(String faction) {
        Settlement w = new Settlement();
        w.vxFaction = faction;
        w.kingdom = kingdomFor(faction);
        w.name = VxBridge.name(faction);
        return w;
    }

    public static boolean atWar(MinecraftServer server, SettlementManager m, Settlement a, Settlement b) {
        String x = realm(server, m, a), y = realm(server, m, b);
        return x != null && y != null && VxBridge.atWar(x, y);
    }

    // ------------------------------------------------------------------ mirrors

    /** Keeps one mirror per Village Expansion village, and Living Realms ownership in step with its claims. */
    static void sync(MinecraftServer server, SettlementManager m) {
        ServerLevel level = server.overworld();
        Map<BlockPos, String> villages = VxBridge.villages(level);
        Map<Long, Settlement> byColumn = new HashMap<>();
        for (Settlement s : m.all()) if (s.dim.equals(Level.OVERWORLD)) byColumn.put(column(s.center), s);
        Set<Settlement> live = new HashSet<>();
        for (var e : villages.entrySet()) {
            BlockPos c = e.getKey();
            String owner = e.getValue();
            if (owner == null || owner.isEmpty()) continue;
            Settlement s = byColumn.get(column(c));
            Settlement holder = kingdomOf(m, owner);
            boolean playerRealm = holder != null && !holder.isAi();
            if (s == null) {
                Settlement near = m.at(Level.OVERWORLD, c);
                if (near != null && near.isAi() && !near.mirror() && !playerRealm) s = adopt(near, c, owner);
            }
            if (s == null) {
                if (playerRealm) continue; // a player's village without a city: created when it is conquered through Living Realms
                s = mirror(level, c, owner);
                m.add(s);
            } else if (playerRealm && s.mirror()) {
                // Taken by a Recruits siege rather than a Living Realms raid.
                Kingdoms.transfer(server, m, holder, s, holder.ownerName);
            } else if (!playerRealm && !s.mirror() && !s.isAi() && !Raids.engaged(m, s)) {
                String mine = knownRealm(m, s);
                if (mine == null || !mine.equals(owner)) Kingdoms.transfer(server, m, holder != null ? holder : standIn(owner), s, VxBridge.name(owner));
            }
            if (s.mirror()) {
                refresh(s, c, owner);
                live.add(s);
            }
        }
        for (Settlement s : List.copyOf(m.all()))
            if (s.mirror() && !live.contains(s) && !Raids.engaged(m, s)) m.remove(s);
        Kingdoms.normalize(m);
    }

    static long column(BlockPos p) { return BlockPos.asLong(p.getX(), 0, p.getZ()); }

    static Settlement mirror(ServerLevel level, BlockPos c, String owner) {
        Settlement s = new Settlement();
        s.name = VxBridge.villageName(c);
        s.dim = Level.OVERWORLD;
        s.center = c;
        s.founded = level.getGameTime();
        s.structureKey = "village_recruits:village";
        s.vxFaction = owner;
        s.kingdom = kingdomFor(owner);
        s.log(s.name + " is a village of " + VxBridge.name(owner) + " (Village Expansion)");
        return s;
    }

    /** A Living Realms NPC town standing on a Village Expansion village becomes its mirror instead of a second owner. */
    static Settlement adopt(Settlement s, BlockPos c, String owner) {
        s.vxFaction = owner;
        s.kingdom = kingdomFor(owner);
        s.center = c;
        s.plots.removeIf(p -> p.state != Plot.State.BUILT);
        s.log("Village Expansion's " + VxBridge.name(owner) + " now governs this town");
        return s;
    }

    static void refresh(Settlement s, BlockPos c, String owner) {
        if (!owner.equals(s.vxFaction)) {
            s.log("Now held by " + VxBridge.name(owner));
            s.vxFaction = owner;
        }
        s.kingdom = kingdomFor(owner);
        s.owner = null;
        s.ownerName = VxBridge.name(owner);
        BlockPos cap = VxBridge.capital(owner);
        s.capital = cap != null && cap.getX() == c.getX() && cap.getZ() == c.getZ();
        s.population = Math.max(4, Math.min(120, VxBridge.population(owner, c)));
        s.tier = s.capital ? Tier.VILLAGE : Tier.HAMLET;
        s.dirty = true;
    }

    /** A mirror's soldiers are its faction's recruits in its land, plus any still marching in a raid. */
    static void roster(ServerLevel level, SettlementManager m, Settlement s) {
        Set<UUID> ids = VxBridge.recruits(s.vxFaction);
        Map<UUID, Settlement.Soldier> had = new HashMap<>();
        s.soldiers.forEach(so -> had.put(so.entity, so));
        List<Settlement.Soldier> now = new ArrayList<>();
        for (UUID id : ids) {
            Entity e = level.getEntity(id);
            if (!(e instanceof LivingEntity living) || !e.isAlive() || !RecruitsBridge.isRecruit(e) || RecruitsBridge.isWorker(e)) continue;
            if (!s.contains(e.blockPosition()) && !Raids.deployed(m, id)) continue;
            Settlement.Soldier so = had.get(id);
            if (so == null) {
                so = new Settlement.Soldier();
                so.entity = id;
                so.type = RecruitsBridge.unitOf(e);
                so.name = e.getName().getString();
            }
            so.health = living.getHealth();
            so.maxHealth = living.getMaxHealth();
            so.level = RecruitsBridge.level(e);
            so.seen = true;
            now.add(so);
        }
        if (!now.equals(s.soldiers)) { s.soldiers.clear(); s.soldiers.addAll(now); s.dirty = true; }
    }

    // ------------------------------------------------------------------ war

    /** Puts a soldier in its kingdom's team, so Recruits and Village Expansion targeting tell friend from foe. */
    static void enlist(MinecraftServer server, SettlementManager m, Settlement s, Entity e) {
        if (!on() || s.mirror()) return;
        String id = realm(server, m, s);
        if (id != null) VxBridge.enlist(server.overworld(), e, id);
    }

    /** With Village Expansion's faction targeting, a town soldier may only engage an enemy faction: make the attacker's team one. */
    public static void engage(MinecraftServer server, Settlement s, Entity defender, Entity enemy) {
        if (!on() || s.mirror()) return;
        SettlementManager m = SettlementManager.get(server);
        String mine = realm(server, m, s), theirs = VxBridge.team(enemy);
        if (mine == null) return;
        VxBridge.enlist(server.overworld(), defender, mine);
        if (theirs != null && !theirs.equals(mine) && !VxBridge.atWar(mine, theirs)) VxBridge.hostile(server.overworld(), mine, theirs);
        assign(defender, enemy);
    }

    /** An ordered target, which Village Expansion's target stickiness then respects instead of re-picking. */
    public static void assign(Entity soldier, @Nullable Entity enemy) {
        if (on() && enemy instanceof LivingEntity living) VxBridge.assign(soldier, living);
    }

    /** Why a raid involving a Village Expansion village cannot start, or null. */
    @Nullable
    static String raidBlocker(MinecraftServer server, SettlementManager m, Settlement source, Settlement target) {
        if (!source.mirror() && !target.mirror()) return null;
        if (!on()) return "Village Expansion is off";
        if (source.mirror() && target.mirror()) return "Village Expansion fights its own wars";
        if (!atWar(server, m, source, target))
            return "Not at war with " + VxBridge.name(target.mirror() ? target.vxFaction : realm(server, m, target)) + ": declare war first";
        return null;
    }

    /** Raids are wars: both kingdoms become enemies in Recruits diplomacy, which is what lets their soldiers fight. */
    static void onRaid(MinecraftServer server, SettlementManager m, Settlement source, Settlement target) {
        if (!on()) return;
        String a = realm(server, m, source), b = realm(server, m, target);
        if (a != null && b != null && !a.equals(b) && !VxBridge.atWar(a, b)) VxBridge.declareWar(server.overworld(), a, b);
    }

    /** Village Expansion factions declare war on nearby player kingdoms, with its own odds for player claims. */
    static void considerWars(MinecraftServer server, SettlementManager m, RandomSource random, boolean force) {
        if (!VxBridge.diplomacyEnabled()) return;
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        Map<String, List<Settlement>> factions = new LinkedHashMap<>();
        for (Settlement s : m.all()) if (s.mirror() && VxBridge.isNpc(s.vxFaction)) factions.computeIfAbsent(s.vxFaction, k -> new ArrayList<>()).add(s);
        for (var e : factions.entrySet()) {
            String f = e.getKey();
            if (!force && now < NEXT_WAR.getOrDefault(f, 0L)) continue;
            NEXT_WAR.put(f, now + WAR_DECISION);
            Settlement best = null;
            double bestD = (double) WAR_RANGE * WAR_RANGE;
            for (Settlement v : e.getValue()) for (Settlement c : m.all()) {
                if (c.isAi() || !c.dim.equals(v.dim)) continue;
                double d = c.center.distSqr(v.center);
                if (d < bestD) { bestD = d; best = c; }
            }
            if (best == null) continue;
            String r = realm(server, m, best);
            if (r == null || !"NEUTRAL".equals(VxBridge.relation(f, r)) || truce(m, f, r) > now || VxBridge.peaceLocked(level, f, r)) continue;
            double p = Math.min(0.85, (Math.sqrt(bestD) <= WAR_NEAR ? 0.35 : 0.18) + Math.max(0, VxBridge.strength(f, r) - 0.5));
            if (random.nextDouble() >= p) continue;
            VxBridge.declareWar(level, f, r);
            String msg = VxBridge.name(f) + " has declared war on your realm. Their villages may march on " + best.name + ".";
            for (Settlement c : Kingdoms.cities(m, best)) Raids.announce(server, c, msg);
        }
    }

    static long truce(SettlementManager m, String a, String b) {
        return m.truces.getOrDefault(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a, 0L);
    }

    /** The ruler declares war on a Village Expansion faction from the City Manager. */
    @Nullable
    static String declare(ServerPlayer player, SettlementManager m, Settlement s, String faction) {
        if (!on()) return "Village Expansion is off";
        if (!player.getUUID().equals(s.owner)) return "Only the ruler declares war";
        if (!VxBridge.exists(faction) || kingdomOf(m, faction) == null) return "Unknown faction";
        String mine = realm(player.server, m, s);
        if (mine == null || mine.equals(faction)) return "That is your own realm";
        if (VxBridge.atWar(mine, faction)) return "Already at war";
        VxBridge.declareWar(player.server.overworld(), mine, faction);
        for (Settlement c : Kingdoms.cities(m, s)) c.log("You declared war on " + VxBridge.name(faction));
        return null;
    }

    /** Peace needs Village Expansion's consent for its own factions; a truce follows. */
    @Nullable
    static String peace(ServerPlayer player, SettlementManager m, Settlement s, String faction) {
        if (!on()) return "Village Expansion is off";
        if (!player.getUUID().equals(s.owner)) return "Only the ruler makes peace";
        String mine = realm(player.server, m, s);
        if (mine == null || !VxBridge.atWar(mine, faction)) return "You are not at war with them";
        ServerLevel level = player.server.overworld();
        if (m.raids.stream().anyMatch(r -> r.attacker.equals(kingdomFor(faction)) || r.defender.equals(kingdomFor(faction)))) return "Not while a raid is under way";
        if (VxBridge.isNpc(faction) && !VxBridge.wouldAcceptPeace(level, faction, mine)) return VxBridge.name(faction) + " refuses peace for now";
        VxBridge.makePeace(level, mine, faction, TRUCE);
        m.truces.put(mine.compareTo(faction) < 0 ? mine + "|" + faction : faction + "|" + mine, level.getGameTime() + TRUCE);
        for (Settlement c : Kingdoms.cities(m, s)) c.log("Peace with " + VxBridge.name(faction) + "; a truce of one day");
        return null;
    }

    // ------------------------------------------------------------------ conquest, both ways

    /** Before Living Realms transfers cities: move their Village Expansion claims, ordinary villages first. */
    static Map<Settlement, String> before(MinecraftServer server, SettlementManager m, Settlement winner, List<Settlement> captured) {
        Map<Settlement, String> losers = new HashMap<>();
        if (!on()) return losers;
        String win = realm(server, m, winner);
        if (win == null) return losers;
        ServerLevel level = server.overworld();
        busy = true;
        try {
            for (Settlement s : captured) {
                String lose = realm(server, m, s);
                losers.put(s, lose);
                if (lose == null || lose.equals(win) || !s.dim.equals(Level.OVERWORLD)) continue;
                String holder = VxBridge.claimed(level, s.center) ? VxBridge.ownerAt(level, s.center) : null;
                if (holder != null) { if (!holder.equals(win)) VxBridge.conquer(level, s.center, win, holder); }
                else if (winner.mirror()) VxBridge.annex(level, s.center, win);
            }
        } catch (RuntimeException e) {
            LivingRealms.LOG.error("Village Expansion conquest bookkeeping failed", e);
        } finally {
            busy = false;
        }
        return losers;
    }

    /** After a city changed hands: mirrors become real cities for players, and captured cities become village-run mirrors. */
    static void handOver(MinecraftServer server, SettlementManager m, Settlement winner, Settlement s, @Nullable String loser) {
        if (winner.mirror()) {
            ServerLevel level = server.getLevel(s.dim);
            if (on() && level != null && !s.mirror())
                for (var so : s.soldiers) {
                    Entity e = SoldierProgression.findEntity(server, so.entity);
                    if (e != null && e.isAlive()) VxBridge.surrender(level, e, winner.vxFaction, s.center);
                }
            s.vxFaction = winner.vxFaction;
            s.ownerName = winner.name;
            s.soldiers.clear();
            s.training.clear();
            s.layout.slots.clear();
        } else if (s.mirror()) {
            s.vxFaction = "";
            s.structureKey = "village_recruits:captured";
            s.baseHousing = Math.max(s.baseHousing, s.population + 2);
            s.wealth = 0;
        }
    }

    /** A Recruits siege (Village Expansion's armies, or yours) took a claim: apply Living Realms' capital rule before VE splits factions. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onSiegeWon(SiegeEvent.Success e) {
        if (busy || !on() || e.getClaim() == null || e.getClaim().getCenter() == null) return;
        try {
            MinecraftServer server = e.getLevel().getServer();
            SettlementManager m = SettlementManager.get(server);
            ChunkPos chunk = e.getClaim().getCenter();
            Settlement s = m.all().stream().filter(t -> t.dim.equals(Level.OVERWORLD) && new ChunkPos(t.center).equals(chunk)).findFirst().orElse(null);
            String win = e.getClaim().getOwnerFactionStringID();
            if (s == null || win == null) return;
            String lose = knownRealm(m, s);
            if (lose == null || lose.equals(win)) return;
            Settlement w = kingdomOf(m, win);
            if (w == null) w = standIn(win);
            Kingdoms.transfer(server, m, w, s, w.isAi() ? VxBridge.name(win) : w.ownerName);
            if (s.owner != null) {
                ServerPlayer p = server.getPlayerList().getPlayer(s.owner);
                if (p != null) p.sendSystemMessage(Component.literal(s.name + " was taken by siege."));
            }
        } catch (RuntimeException ex) {
            LivingRealms.LOG.error("Village Expansion siege bookkeeping failed", ex);
        }
    }

    // ------------------------------------------------------------------ City Manager

    /** Relations, war status and the villages within raid range, for one player's view of a city. */
    public static net.minecraft.nbt.CompoundTag view(ServerPlayer p, SettlementManager m, Settlement s) {
        var t = new net.minecraft.nbt.CompoundTag();
        MinecraftServer server = p.server;
        Settlement home = s.mirror() ? m.ownedBy(p.getUUID()).stream().min(Comparator.comparingDouble(c -> c.center.distSqr(s.center))).orElse(null) : s;
        boolean ruler = home != null && p.getUUID().equals(home.owner);
        t.putBoolean("ruler", ruler);
        String mine = home == null || home.isAi() ? null : ruler ? realm(server, m, home) : knownRealm(m, home);
        if (mine != null) t.putString("realm", VxBridge.name(mine));
        if (s.mirror()) {
            t.putBoolean("mirror", true);
            t.putString("faction", s.vxFaction);
            t.putString("factionName", VxBridge.name(s.vxFaction));
            t.putString("status", mine == null ? "Neutral" : status(VxBridge.relation(mine, s.vxFaction)));
            t.putInt("recruits", s.soldiers.size());
            return t;
        }
        if (home == null || home.isAi()) return t;
        long now = server.overworld().getGameTime();
        Map<String, net.minecraft.nbt.ListTag> villages = new LinkedHashMap<>();
        double range = (double) Config.RAID_RANGE.get() * Config.RAID_RANGE.get();
        List<Settlement> near = new ArrayList<>(m.all().stream().filter(v -> v.mirror() && v.dim.equals(s.dim) && v.center.distSqr(s.center) <= range).toList());
        near.sort(Comparator.comparingDouble(v -> v.center.distSqr(s.center)));
        for (Settlement v : near) {
            var vt = new net.minecraft.nbt.CompoundTag();
            vt.putUUID("id", v.id);
            vt.putString("name", v.name);
            vt.putInt("dist", (int) Math.sqrt(v.center.distSqr(s.center)));
            vt.putInt("recruits", v.soldiers.size());
            vt.putBoolean("capital", v.capital);
            Settlement src = Raids.playerSource(server, m, p.getUUID(), v);
            vt.putString("blocker", src != null ? "" : ruler ? blocker(server, m, home, v) : "Only the ruler can raid");
            villages.computeIfAbsent(v.vxFaction, k -> new net.minecraft.nbt.ListTag()).add(vt);
        }
        var list = new net.minecraft.nbt.ListTag();
        for (var e : villages.entrySet()) {
            var f = new net.minecraft.nbt.CompoundTag();
            String rel = mine == null ? "NEUTRAL" : VxBridge.relation(mine, e.getKey());
            f.putString("id", e.getKey());
            f.putString("name", VxBridge.name(e.getKey()));
            f.putString("status", status(rel));
            f.putBoolean("war", "ENEMY".equals(rel));
            f.putBoolean("npc", VxBridge.isNpc(e.getKey()));
            f.putInt("villageCount", (int) m.all().stream().filter(v -> e.getKey().equals(v.vxFaction)).count());
            f.putInt("recruits", VxBridge.recruitCount(e.getKey()));
            long truce = mine == null ? 0 : truce(m, mine, e.getKey()) - now;
            if (truce > 0) f.putInt("truce", (int) (truce / 1200));
            f.put("villages", e.getValue());
            list.add(f);
        }
        t.put("factions", list);
        return t;
    }

    static String status(String relation) {
        return switch (relation) { case "ENEMY" -> "At war"; case "ALLY" -> "Allied"; default -> "Neutral"; };
    }

    /** The first reason none of the ruler's cities can raid this village. */
    static String blocker(MinecraftServer server, SettlementManager m, Settlement home, Settlement v) {
        String first = null;
        for (Settlement c : Kingdoms.cities(m, home)) {
            String why = Raids.canStart(server, m, c, v);
            if (why == null) return "";
            if (first == null || why.startsWith("Not at war")) first = why;
        }
        return first == null ? "No city of yours can raid it" : first;
    }

    // ------------------------------------------------------------------ ticking

    static void tick(MinecraftServer server, SettlementManager m, long tick) {
        if (!on()) return;
        try {
            if (tick % SYNC == 0) sync(server, m);
            if (tick % 20 == 0) {
                for (Settlement s : m.all()) {
                    if (!s.mirror()) continue;
                    ServerLevel level = server.getLevel(s.dim);
                    if (level != null && level.hasChunkAt(s.center)) roster(level, m, s);
                }
            }
            if (tick % 600 == 0) considerWars(server, m, server.overworld().random, false);
        } catch (RuntimeException e) {
            LivingRealms.LOG.error("Village Expansion sync failed", e);
        }
    }

    /** Grows a player town's Village Expansion layout with its people, like VE's population-capped plans. */
    static void plan(ServerLevel level, Settlement s) {
        if (!on() || s.isAi() || !level.hasChunkAt(s.center)) return;
        int target = Math.min(120, Math.max(12, s.population + 8));
        if (!s.layout.isEmpty() && target < s.layout.target + 4 && s.layout.free() > 2) return;
        try {
            VxBridge.layout(level, "livingrealms:" + s.id, s.center, Math.max(target, s.layout.target + (s.layout.free() <= 2 ? 6 : 0)), s.layout);
        } catch (RuntimeException e) {
            LivingRealms.LOG.error("Village Expansion layout for {} failed", s.name, e);
        }
    }

    /** Village Expansion villages only march while its own attacks are switched on. */
    static boolean mayMarch() { return on() && VxBridge.attacksEnabled(); }

    private Realms() {}
}
