package livingrealms.settlement;

import livingrealms.Config;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Persistent, physical battles. Missing/unloaded soldiers are never counted as defeated. */
public final class Raids {
    public static final int HOLD_TICKS = 1200, MAX_TICKS = 12000;

    public static final class Raid {
        public UUID source, target, attacker, defender;
        public final Set<UUID> soldiers = new LinkedHashSet<>();
        public int held, elapsed;
        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("source", source); t.putUUID("target", target);
            t.putUUID("attacker", attacker); t.putUUID("defender", defender);
            ListTag ids = new ListTag(); soldiers.forEach(id -> ids.add(StringTag.valueOf(id.toString())));
            t.put("soldiers", ids); t.putInt("held", held); t.putInt("elapsed", elapsed);
            return t;
        }
        static Raid load(CompoundTag t) {
            Raid r = new Raid(); r.source = t.getUUID("source"); r.target = t.getUUID("target");
            r.attacker = t.getUUID("attacker"); r.defender = t.getUUID("defender");
            for (Tag id : t.getList("soldiers", Tag.TAG_STRING)) r.soldiers.add(UUID.fromString(id.getAsString()));
            r.held = Math.max(0, Math.min(HOLD_TICKS, t.getInt("held")));
            r.elapsed = Math.max(0, t.getInt("elapsed")); return r;
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
        if (!source.dim.equals(target.dim)) return "Raids require cities in the same dimension";
        if (source.center.distSqr(target.center) > (double) Config.RAID_RANGE.get() * Config.RAID_RANGE.get()) return "Target is beyond raid range";
        if (engaged(m, source) || engaged(m, target)) return "One of these kingdoms already has a raid in progress";
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
        Raid raid = new Raid(); raid.source = source.id; raid.target = target.id;
        raid.attacker = source.kingdomId(); raid.defender = target.kingdomId();
        var troops = available(server.getLevel(source.dim), source);
        int count = Math.min(12, Math.max(2, troops.size() / 2));
        troops.stream().limit(count).forEach(so -> raid.soldiers.add(so.entity));
        m.raids.add(raid);
        for (Settlement s : Kingdoms.cities(m, source)) s.raidCooldown = server.overworld().getGameTime() + Config.RAID_COOLDOWN.get();
        announce(server, source, "Marching on " + target.name + " with " + count + " soldiers.");
        announce(server, target, source.name + " is raiding " + target.name + (target.capital
                ? "! CAPITAL AT RISK: defeat transfers every city in your kingdom." : "! Defeat transfers this city only."));
        return null;
    }

    public static Settlement playerSource(MinecraftServer server, SettlementManager m, UUID player, Settlement target) {
        return m.ownedBy(player).stream().filter(s -> canStart(server, m, s, target) == null)
                .min(Comparator.comparingDouble(s -> s.center.distSqr(target.center))).orElse(null);
    }

    /** The target is untrusted client data; the attacking kingdom always comes from the authenticated player. */
    public static String playerStart(ServerPlayer player, SettlementManager m, Settlement target) {
        Settlement source = playerSource(player.server, m, player.getUUID(), target);
        if (source == null) return "No eligible city: need 3 soldiers, loaded nearby cities, no active raid, and no recovery cooldown";
        return start(player.server, m, source, target);
    }

    static Settlement npcTarget(MinecraftServer server, SettlementManager m, Settlement source) {
        return m.all().stream().filter(t -> canStart(server, m, source, t) == null)
                .filter(t -> t.isAi() || server.getPlayerList().getPlayer(t.owner) != null)
                .min(Comparator.comparingDouble(t -> t.center.distSqr(source.center))).orElse(null);
    }

    public static void tick(MinecraftServer server, SettlementManager m) {
        for (Raid raid : List.copyOf(m.raids)) tickRaid(server, m, raid);
        long now = server.overworld().getGameTime();
        if (!Config.NPC_RAIDS.get() || now < m.nextRaidCheck) return;
        m.nextRaidCheck = now + 1200;
        for (Settlement s : m.all()) {
            if (!s.isAi() || engaged(m, s) || server.overworld().getGameTime() - s.founded < Config.RAID_COOLDOWN.get()) continue;
            Settlement target = npcTarget(server, m, s);
            if (target != null) start(server, m, s, target);
        }
    }

    static void tickRaid(MinecraftServer server, SettlementManager m, Raid raid) {
        Settlement source = m.get(raid.source), target = m.get(raid.target);
        if (source == null || target == null || !source.kingdomId().equals(raid.attacker) || !target.kingdomId().equals(raid.defender)) {
            finish(server, m, raid, "Raid ended because control of a city changed"); return;
        }
        ServerLevel level = server.getLevel(target.dim);
        if (level == null || !level.hasChunkAt(source.center) || !level.hasChunkAt(target.center)) {
            status(source, target, "Raid paused: waiting for both cities to load"); return;
        }
        // Death removes the soldier record; unload does not. Never turn an unloaded army into a defeat.
        raid.soldiers.removeIf(id -> source.soldiers.stream().noneMatch(so -> so.entity.equals(id)));
        if (raid.soldiers.isEmpty()) { finish(server, m, raid, "Raid defeated: the attacking force was lost"); return; }
        List<LivingEntity> attackers = new ArrayList<>(), defenders = new ArrayList<>();
        for (UUID id : raid.soldiers) {
            Entity e = level.getEntity(id);
            if (!(e instanceof LivingEntity living) || !e.isAlive()) {
                status(source, target, "Raid paused: attacking soldiers are unloaded"); return;
            }
            attackers.add(living);
        }
        for (Settlement town : Kingdoms.cities(m, target)) for (var so : town.soldiers) {
            Entity e = livingrealms.military.SoldierProgression.findEntity(server, so.entity);
            if (e == null && town == target) {
                status(source, target, "Raid paused: defending soldiers are unloaded"); return;
            }
            if (e instanceof LivingEntity living && e.isAlive() && e.level() == level && target.contains(e.blockPosition())) defenders.add(living);
        }
        for (ServerPlayer p : level.players()) if (p.getUUID().equals(target.owner) && !p.isCreative() && !p.isSpectator()
                && p.isAlive() && target.contains(p.blockPosition())) defenders.add(p);
        raid.elapsed += 20;
        if (raid.elapsed >= MAX_TICKS) { finish(server, m, raid, "Raid repelled: attackers failed to take the city in time"); return; }
        target.alarmUntil = level.getGameTime() + 100;
        for (LivingEntity attacker : attackers) {
            RecruitsBridge.allegiance(attacker, source.kingdomId());
            RecruitsBridge.raidOrder(attacker, target.center, nearest(attacker, defenders));
        }
        for (LivingEntity defender : defenders) if (RecruitsBridge.isRecruit(defender)) {
            RecruitsBridge.allegiance(defender, target.kingdomId());
            RecruitsBridge.raidOrder(defender, target.center, nearest(defender, attackers));
        }
        boolean atCenter = attackers.stream().anyMatch(e -> e.blockPosition().distSqr(target.center) <= 12 * 12);
        raid.held = defenders.isEmpty() && atCenter ? raid.held + 20 : 0;
        status(source, target, defenders.isEmpty() && atCenter ? "City center occupied: " + raid.held / 20 + "/60 seconds"
                : "Raid in progress: " + attackers.size() + " attackers; " + defenders.size() + " local defenders");
        if (raid.held >= HOLD_TICKS) {
            boolean capital = target.capital;
            UUID oldOwner = target.owner;
            int count = Kingdoms.conquer(server, m, source, target);
            String result = target.name + " fell. " + (capital ? "Capital captured: " + count + " cities transferred." : "Only this city transferred.");
            if (oldOwner != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(oldOwner);
                if (player != null) player.sendSystemMessage(Component.literal(result));
            }
            finish(server, m, raid, result);
        }
    }

    private static LivingEntity nearest(LivingEntity from, List<LivingEntity> candidates) {
        return candidates.stream().filter(e -> e.distanceToSqr(from) <= 48 * 48)
                .min(Comparator.comparingDouble(from::distanceToSqr)).orElse(null);
    }

    private static void status(Settlement source, Settlement target, String message) {
        for (Settlement s : List.of(source, target)) if (!message.equals(s.raidStatus)) { s.raidStatus = message; s.dirty = true; }
    }

    private static void announce(MinecraftServer server, Settlement s, String message) {
        s.log(message);
        if (!s.isAi()) s.away.note(s.away.raids, message);
        if (s.owner != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(s.owner);
            if (p != null) p.sendSystemMessage(Component.literal(message));
        }
    }

    static void finish(MinecraftServer server, SettlementManager m, Raid raid, String message) {
        m.raids.remove(raid);
        Settlement source = m.get(raid.source), target = m.get(raid.target);
        for (Settlement s : new Settlement[]{source, target}) if (s != null) {
            s.raidStatus = "";
            s.raidCooldown = server.overworld().getGameTime() + Config.RAID_COOLDOWN.get();
            announce(server, s, message);
            ServerLevel level = server.getLevel(s.dim);
            if (level != null) {
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
