package livingrealms.settlement;

import livingrealms.Config;
import livingrealms.military.BattleAI;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierRanks;
import livingrealms.military.UnitType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Persistent, physical battles. Missing/unloaded soldiers are never counted as defeated. */
public final class Raids {
    public static final int HOLD_TICKS = 1200, MAX_TICKS = 12000;
    /** A raid stalled this long in a row (cities or armies unloaded) is called off, so no raid can hang forever. */
    public static final int PAUSE_LIMIT = 6000;
    /** A defender seen this long before the raid began is still expected at the fight. */
    public static final int DEFENDER_GRACE = 1200;

    public static final class Raid {
        public UUID source, target, attacker, defender;
        public final Set<UUID> soldiers = new LinkedHashSet<>();
        public int held, elapsed;
        /** Game time the raid began; defenders seen within a minute before it are waited for if they unload. */
        public long started;
        /** Ticks in a row the raid has been paused; reaching PAUSE_LIMIT calls it off. */
        public int paused;
        /** Defending soldiers of the target kingdom who died while the raid lasted (war exhaustion and score). */
        public int defendersLost;
        /** Whether the defenders were closing ranks last second; only for announcing the change. */
        boolean regrouped;
        /** The siege of a walled town (docs/sieges.md); an open-field assault for an unwalled one. */
        public Siege siege = new Siege();
        /** A walled town is given longer before the attackers give up. */
        public int maxTicks() { return siege.walled ? Siege.MAX_TICKS : MAX_TICKS; }
        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("source", source); t.putUUID("target", target);
            t.putUUID("attacker", attacker); t.putUUID("defender", defender);
            ListTag ids = new ListTag(); soldiers.forEach(id -> ids.add(StringTag.valueOf(id.toString())));
            t.put("soldiers", ids); t.putInt("held", held); t.putInt("elapsed", elapsed); t.putInt("paused", paused); t.putInt("defendersLost", defendersLost); t.putLong("started", started);
            t.put("siege", siege.save());
            return t;
        }
        static Raid load(CompoundTag t) {
            Raid r = new Raid(); r.source = t.getUUID("source"); r.target = t.getUUID("target");
            r.attacker = t.getUUID("attacker"); r.defender = t.getUUID("defender");
            for (Tag id : t.getList("soldiers", Tag.TAG_STRING)) r.soldiers.add(UUID.fromString(id.getAsString()));
            r.held = Math.max(0, Math.min(HOLD_TICKS, t.getInt("held")));
            r.elapsed = Math.max(0, t.getInt("elapsed"));
            r.paused = Math.max(0, t.getInt("paused"));
            r.defendersLost = Math.max(0, t.getInt("defendersLost"));
            r.started = t.getLong("started");
            r.siege = Siege.load(t.getCompound("siege")); return r;
        }
    }

    public static boolean engaged(SettlementManager m, Settlement s) {
        return m.raids.stream().anyMatch(r -> r.attacker.equals(s.kingdomId()) || r.defender.equals(s.kingdomId()));
    }

    public static boolean deployed(SettlementManager m, UUID soldier) {
        return m.raids.stream().anyMatch(r -> r.soldiers.contains(soldier));
    }

    static List<Settlement.Soldier> available(ServerLevel level, Settlement s) {
        return s.soldiers.stream().filter(so -> !so.royalGuard && so.type != UnitType.DRAGON_RIDER && so.type != UnitType.HORSEMAN)
                .filter(so -> { Entity e = level.getEntity(so.entity); return e instanceof LivingEntity && e.isAlive()
                        && !e.isPassenger() && s.contains(e.blockPosition()); }).toList();
    }

    public static String canStart(MinecraftServer server, SettlementManager m, Settlement source, Settlement target) {
        if (source == null || target == null || Kingdoms.same(source, target)) return "Choose a city belonging to another kingdom";
        String realms = Realms.raidBlocker(server, m, source, target);
        if (realms != null) return realms;
        if (!source.dim.equals(target.dim)) return "Raids require cities in the same dimension";
        if (!source.isAi() && !target.isAi() && target.owner != null && !Config.RAID_OFFLINE.get()
                && server.getPlayerList().getPlayer(target.owner) == null) return target.ownerName + " is offline: their cities can be raided only while they play";
        if (source.center.distSqr(target.center) > (double) Config.RAID_RANGE.get() * Config.RAID_RANGE.get()) return "Target is beyond raid range";
        if (engaged(m, source) || engaged(m, target)) return "One of these kingdoms already has a raid in progress";
        String peace = Statecraft.raidBlocker(server, m, source, target);
        if (peace != null) return peace;
        String oath = livingrealms.compat.Compat.raidBlocked(server, source, target);
        if (oath != null) return oath;
        long now = server.overworld().getGameTime();
        if (Kingdoms.cities(m, source).stream().anyMatch(t -> t.raidCooldown > now)
                || Kingdoms.cities(m, target).stream().anyMatch(t -> t.raidCooldown > now)) return "The kingdom is recovering from its last raid";
        ServerLevel level = server.getLevel(source.dim);
        if (level == null || !level.hasChunkAt(source.center) || !level.hasChunkAt(target.center)) return "Both cities must be loaded";
        if (available(level, source).size() < 3) return "Gather at least 3 unmounted town soldiers (not Royal Guards) in the attacking city";
        return null;
    }

    public static String start(MinecraftServer server, SettlementManager m, Settlement source, Settlement target) {
        String error = canStart(server, m, source, target);
        if (error != null) return error;
        Raid raid = new Raid(); raid.source = source.id; raid.target = target.id; raid.started = server.overworld().getGameTime();
        raid.attacker = source.kingdomId(); raid.defender = target.kingdomId();
        var troops = available(server.getLevel(source.dim), source);
        int count = Math.min(12, Math.max(2, troops.size() / 2));
        troops.stream().limit(count).forEach(so -> raid.soldiers.add(so.entity));
        raid.siege = Siege.begin(server.getLevel(target.dim), source, target);
        m.raids.add(raid);
        Strategy.raidStarted(m, raid, server.overworld().getGameTime());
        Strategy.decrees(server, m, source, target);
        Statecraft.raidStarted(server, m, raid, source, target);
        livingrealms.compat.Compat.raidStarted(server, raid, source, target);
        livingrealms.dragon.Sorties.raidStarted(server, m, raid, source, target);
        Realms.onRaid(server, m, source, target);
        for (Settlement s : Kingdoms.cities(m, source)) s.raidCooldown = server.overworld().getGameTime() + Config.RAID_COOLDOWN.get();
        announce(server, source, "Marching on " + target.name + " with " + count + " soldiers.");
        announce(server, target, source.name + " is raiding " + target.name + (target.capital
                ? "! CAPITAL AT RISK: defeat transfers every city in your kingdom." : "! Defeat transfers this city only."));
        if (raid.siege.walled) {
            announce(server, target, "The gates of " + target.name + " are shut. The enemy makes for the " + raid.siege.where + ".");
            announce(server, source, target.name + " is walled: the army makes for the " + raid.siege.where + ".");
        }
        return null;
    }

    public static Settlement playerSource(MinecraftServer server, SettlementManager m, UUID player, Settlement target) {
        return m.ownedBy(player).stream().filter(s -> canStart(server, m, s, target) == null)
                .min(Comparator.comparingDouble(s -> s.center.distSqr(target.center))).orElse(null);
    }

    /** The target is untrusted client data; the attacking kingdom always comes from the authenticated player. */
    public static String playerStart(ServerPlayer player, SettlementManager m, Settlement target) {
        Settlement source = playerSource(player.server, m, player.getUUID(), target);
        if (source == null) {
            // say why the nearest city of the player's own can't march, not a catch-all list
            Settlement near = m.ownedBy(player.getUUID()).stream().filter(t -> t.dim.equals(target.dim))
                    .min(Comparator.comparingDouble(t -> t.center.distSqr(target.center))).orElse(null);
            if (near == null) return m.ownedBy(player.getUUID()).isEmpty() ? "You rule no city to raid from" : "Raids require cities in the same dimension";
            String why = canStart(player.server, m, near, target);
            return why == null ? "No city of yours can march on " + target.name : near.name + ": " + why;
        }
        return start(player.server, m, source, target);
    }

    /** The raid an NPC kingdom wants most: winnable, valuable and near (Strategy.raidScore); none when every raid in reach is hopeless. */
    static Settlement npcTarget(MinecraftServer server, SettlementManager m, Settlement source) {
        ServerLevel level = server.getLevel(source.dim);
        if (level == null) return null;
        double attack = Strategy.expedition(available(level, source));
        long now = server.overworld().getGameTime();
        Settlement best = null;
        double bestScore = 0;
        for (Settlement t : m.all()) {
            if (source.mirror() ? t.isAi() : t.mirror()) continue;
            if (!t.isAi() && server.getPlayerList().getPlayer(t.owner) == null) continue;
            if (canStart(server, m, source, t) != null || Realms.suing(m, source, t, now)) continue;
            double score = Strategy.raidScore(server, m, source, t, attack, now);
            if (score > bestScore || score == bestScore && score > 0 && t.center.distSqr(source.center) < best.center.distSqr(source.center)) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    public static void tick(MinecraftServer server, SettlementManager m) {
        for (Raid raid : List.copyOf(m.raids)) tickRaid(server, m, raid);
        long now = server.overworld().getGameTime();
        if (!Config.NPC_RAIDS.get() || now < m.nextRaidCheck) return;
        m.nextRaidCheck = now + 1200;
        for (Settlement s : m.all()) {
            if (!s.isAi() || engaged(m, s) || server.overworld().getGameTime() - s.founded < Config.RAID_COOLDOWN.get()) continue;
            if (s.mirror() && !Realms.mayMarch()) continue;
            ServerLevel level = server.getLevel(s.dim);
            if (level == null || !level.hasChunkAt(s.center)) continue;
            // A beaten kingdom regroups before it marches again.
            if (available(level, s).size() < Strategy.musterNeeded(Strategy.defeats(m, s.kingdomId(), now))) continue;
            Settlement target = npcTarget(server, m, s);
            if (target != null) start(server, m, s, target);
        }
    }

    static void tickRaid(MinecraftServer server, SettlementManager m, Raid raid) {
        Settlement source = m.get(raid.source), target = m.get(raid.target);
        if (source == null || target == null || !source.kingdomId().equals(raid.attacker) || !target.kingdomId().equals(raid.defender)) {
            finish(server, m, raid, "Raid ended because control of a city changed", true); return;
        }
        if (raid.started == 0) raid.started = server.overworld().getGameTime();
        ServerLevel level = server.getLevel(target.dim);
        // only the fight's city has to be loaded: the attacking city plays no part once its army has marched
        if (level == null || !level.hasChunkAt(target.center)) { pause(server, m, raid, source, target, "waiting for " + target.name + " to load"); return; }
        // Death removes the soldier record; unload does not. Never turn an unloaded army into a defeat.
        raid.soldiers.removeIf(id -> source.soldiers.stream().noneMatch(so -> so.entity.equals(id)));
        if (raid.soldiers.isEmpty()) { finish(server, m, raid, "Raid defeated: the attacking force was lost"); return; }
        List<LivingEntity> attackers = new ArrayList<>(), defenders = new ArrayList<>();
        for (UUID id : raid.soldiers) {
            Entity e = level.getEntity(id);
            if (!(e instanceof LivingEntity living) || !e.isAlive()) { pause(server, m, raid, source, target, "attacking soldiers are unloaded"); return; }
            attackers.add(living);
        }
        for (Settlement town : Kingdoms.cities(m, target)) for (var so : town.soldiers) {
            // a defender that unloaded around the raid's start or during it is waited for; one long gone (a far post,
            // or vanished without dying) is not
            Entity e = livingrealms.military.SoldierProgression.findEntity(server, so.entity);
            if (e == null && town == target && so.seenAt > 0 && so.seenAt >= raid.started - DEFENDER_GRACE) { pause(server, m, raid, source, target, "defending soldiers are unloaded"); return; }
            if (e instanceof LivingEntity living && e.isAlive() && e.level() == level && target.contains(e.blockPosition())) {
                defenders.add(living);
                if ((raid.elapsed + 20) % SoldierRanks.RAID_DEFEND_TICKS == 0)
                    SoldierRanks.grant(town, so, e, SoldierRanks.RAID_DEFEND_XP, true, server.overworld().getGameTime());
            }
        }
        for (ServerPlayer p : level.players()) if (p.getUUID().equals(target.owner) && !p.isCreative() && !p.isSpectator()
                && p.isAlive() && target.contains(p.blockPosition())) defenders.add(p);
        raid.paused = 0;
        raid.elapsed += 20;
        if (raid.elapsed >= raid.maxTicks()) { finish(server, m, raid, "Raid repelled: attackers failed to take the city in time"); return; }
        target.alarmUntil = level.getGameTime() + 100;
        // Sides first (owner, and the Recruits team Village Expansion reads), then orders, so each side sees the other as an enemy.
        for (LivingEntity attacker : attackers) {
            if (!source.mirror()) RecruitsBridge.allegiance(attacker, source.kingdomId());
            Realms.enlist(server, m, source, attacker);
        }
        for (LivingEntity defender : defenders) if (RecruitsBridge.isRecruit(defender)) {
            if (!target.mirror()) RecruitsBridge.allegiance(defender, target.kingdomId());
            Realms.enlist(server, m, target, defender);
        }
        // Per-soldier orders come from the battle AI (docs/battle-ai.md). Royal Guards keep escorting their ruler.
        Set<UUID> guards = new HashSet<>();
        for (Settlement town : Kingdoms.cities(m, target)) for (var so : town.soldiers) if (so.royalGuard) guards.add(so.entity);
        LivingEntity ruler = defenders.stream().filter(d -> d instanceof ServerPlayer).findFirst().orElse(null);
        BattleAI.enlist(key(raid), attackers, defenders);
        Vec3 front = BattleAI.centroid(attackers, Vec3.atCenterOf(source.center));
        double reach = Math.min(target.radius() + 8, 56);
        List<LivingEntity> holding = defenders.stream().filter(d -> RecruitsBridge.isRecruit(d) && !guards.contains(d.getUUID())).toList();
        Siege sg = raid.siege;
        BattleAI.Plan[] siege = sg.walled ? sg.plan(level, raid, source, target, attackers, defenders, holding, ruler, front, reach) : null;
        BattleAI.Plan attack, defence;
        Plot gate = null;
        if (siege != null) { attack = siege[0]; defence = siege[1]; }
        else {
            attack = BattleAI.plan(new BattleAI.Side(attackers, defenders, target.center, BattleAI.toward(target.center, source.center, reach), false, null));
            // behind town walls the defenders hold the gate the attackers are gathering at, until they are through it
            gate = livingrealms.settlement.plan.Walls.gateFacing(target, front.x, front.z);
            BlockPos stand = gate == null ? target.center : BattleAI.toward(gate.center(), target.center, 5);
            defence = BattleAI.plan(new BattleAI.Side(holding, attackers, stand, BattleAI.rally(stand, front, 8), true, ruler));
        }
        for (var plan : List.of(attack, defence)) plan.orders().forEach((soldier, order) -> command(level, soldier, order));
        String held = siege != null ? "the " + sg.where : gate != null ? "the " + livingrealms.settlement.plan.Walls.side(target, gate.center()) + " gate" : null;
        if (defence.regrouping() && !raid.regrouped) announce(server, target, held != null
                ? "Outnumbered, the defenders of " + target.name + " close ranks at " + held + "."
                : "Outnumbered, the defenders of " + target.name + " close ranks at the centre.");
        raid.regrouped = defence.regrouping();
        livingrealms.compat.Compat.raidTick(server, raid, target, attackers, defenders);
        boolean rulerHome = ruler != null;
        if (siege != null && sg.after(level, raid, source, target, attackers, defenders, rulerHome, livingrealms.compat.Compat.engines(server, raid))) {
            fall(server, m, raid, source, target, target.name + ", starved under siege, surrendered to " + source.name + ".");
            return;
        }
        boolean atCenter = attackers.stream().anyMatch(e -> e.blockPosition().distSqr(target.center) <= 12 * 12);
        raid.held = defenders.isEmpty() && atCenter && !(sg.walled && sg.phase.before(Siege.Phase.ASSAULT)) ? raid.held + 20 : 0;
        if (!sg.phase.before(Siege.Phase.ASSAULT)) sg.phase = raid.held > 0 ? Siege.Phase.HOLD : Siege.Phase.ASSAULT;
        String back = attack.fallingBack() > 0 ? " (" + attack.fallingBack() + " falling back)" : "";
        String hold = held != null ? ", holding " + held
                : defence.regrouping() ? ", holding the centre" : defence.fallingBack() > 0 ? " (" + defence.fallingBack() + " falling back)" : "";
        status(source, target, raid.held > 0 ? "City center occupied: " + raid.held / 20 + "/60 seconds"
                : siege != null && sg.phase.before(Siege.Phase.ASSAULT) ? sg.status(attackers.size(), back, defenders.size())
                : "Assault · Raid in progress: " + attackers.size() + " attackers" + back + "; " + defenders.size() + " local defenders" + hold);
        if (raid.held >= HOLD_TICKS) {
            fall(server, m, raid, source, target, null);
        }
    }

    /** The town falls to the attackers: taken by storm (how == null), or by surrender. */
    static void fall(MinecraftServer server, SettlementManager m, Raid raid, Settlement source, Settlement target, String how) {
        raid.held = HOLD_TICKS;
        boolean capital = target.capital;
        UUID oldOwner = target.owner;
        livingrealms.quest.Quests.cityTaken(server, m, raid.attacker, capital);
        int count = Kingdoms.conquer(server, m, source, target);
        String result = (how != null ? how + " " : target.name + " fell. ") + (capital ? "Capital captured: " + count + " cities transferred." : "Only this city transferred.");
        if (oldOwner != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(oldOwner);
            if (player != null) player.sendSystemMessage(Component.literal(result));
        }
        finish(server, m, raid, result);
    }

    static String key(Raid raid) { return raid.source + ">" + raid.target; }

    /** Carries out one battle order. Village Expansion hears about the target first, so its stickiness keeps it. */
    static void command(ServerLevel level, LivingEntity soldier, BattleAI.Order order) {
        BlockPos hold = level.hasChunkAt(order.hold()) ? RecruitsBridge.surface(level, order.hold()) : order.hold();
        if (order.stance() == BattleAI.Stance.FALL_BACK) {
            soldier.getPersistentData().putBoolean(BattleAI.FALLBACK, true);
            RecruitsBridge.fallBack(soldier, hold);
            // At the rally point the wounded bind their wounds, so they can rejoin the line.
            if (soldier.blockPosition().distSqr(hold) <= 36)
                soldier.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 0, true, true));
            return;
        }
        soldier.getPersistentData().remove(BattleAI.FALLBACK);
        Realms.assign(soldier, order.target());
        RecruitsBridge.raidOrder(soldier, hold, order.target());
    }

    private static void status(Settlement source, Settlement target, String message) {
        for (Settlement s : List.of(source, target)) if (!message.equals(s.raidStatus)) { s.raidStatus = message; s.dirty = true; }
    }

    static void announce(MinecraftServer server, Settlement s, String message) {
        s.log(message);
        if (!s.isAi()) s.away.note(s.away.raids, message);
        if (s.owner != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(s.owner);
            if (p != null) p.sendSystemMessage(Component.literal(message));
        }
    }

    /** The raid can't be fought this second; one stalled for PAUSE_LIMIT in a row is called off with no winner. */
    static void pause(MinecraftServer server, SettlementManager m, Raid raid, Settlement source, Settlement target, String why) {
        raid.paused += 20;
        if (raid.paused >= PAUSE_LIMIT) { finish(server, m, raid, "Raid called off: the armies never met (" + why + ")", true); return; }
        status(source, target, "Raid paused: " + why + " · called off in " + livingrealms.TimeText.span(PAUSE_LIMIT - raid.paused, Statecraft.dayLength()));
    }

    static void finish(MinecraftServer server, SettlementManager m, Raid raid, String message) { finish(server, m, raid, message, false); }

    /** Ends a raid. A raid called off (never fought out, or a city changed hands) has no winner: no score, honours or quest credit. */
    static void finish(MinecraftServer server, SettlementManager m, Raid raid, String message, boolean calledOff) {
        m.raids.remove(raid);
        BattleAI.release(key(raid));
        Siege.end(server, raid, m.get(raid.target));
        livingrealms.dragon.Sorties.raidEnded(raid);
        Settlement source = m.get(raid.source), target = m.get(raid.target);
        if (!calledOff) {
            Strategy.raidEnded(m, raid, server.overworld().getGameTime());
            Statecraft.raidEnded(server, m, raid, raid.held >= HOLD_TICKS);
            livingrealms.quest.Quests.raidEnded(server, m, raid, raid.held >= HOLD_TICKS);
            livingrealms.chronicle.Chronicle.raidEnded(source, target, raid.held >= HOLD_TICKS, message);
        } else if (source != null && target != null) {
            String line = "The raid by " + source.name + " on " + target.name + " was called off.";
            livingrealms.chronicle.Chronicle.record(source, livingrealms.chronicle.Chronicle.Kind.NOTE, line);
            livingrealms.chronicle.Chronicle.record(target, livingrealms.chronicle.Chronicle.Kind.NOTE, line);
        }
        livingrealms.compat.Compat.raidEnded(server, raid, source);
        for (Settlement s : new Settlement[]{source, target}) if (s != null) {
            s.raidStatus = "";
            s.raidCooldown = server.overworld().getGameTime() + Config.RAID_COOLDOWN.get();
            announce(server, s, message);
            ServerLevel level = server.getLevel(s.dim);
            if (level != null) {
                if (!calledOff) {
                    SoldierRanks.raidSurvived(level, s, raid.soldiers, s == target);
                    Merit.raidEnded(level, s, raid.soldiers, s == target, raid.held >= HOLD_TICKS, s == target ? source : target);
                }
                for (var so : s.soldiers) {
                    Entity e = level.getEntity(so.entity);
                    RecruitsBridge.clearRaidTarget(e);
                    if (e != null && raid.soldiers.contains(so.entity)) RecruitsBridge.holdAt(e, s.center);
                }
            }
        }
    }

    private Raids() {}
}
