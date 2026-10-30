package livingrealms.settlement;

import livingrealms.Config;
import livingrealms.research.ResearchTree;
import livingrealms.settlement.vx.VxBridge;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Kingdom-level judgement for NPC kingdoms and Village Expansion factions: how strong a kingdom is, what it remembers of
 * recent battles, whom to raid, when to start a war, when to sue for peace and when to regroup. Design notes: docs/kingdom-ai.md.
 */
public final class Strategy {
    public static final long DAY = 24000;
    /** An expedition with lower odds than this is expected to die; NPC kingdoms do not send it. */
    public static final double HOPELESS = 0.35;
    /** A Village Expansion faction this outmatched by a realm it is at war with, and bloodied, sues for peace; below ROUT it always does. */
    public static final double SUE = 0.4, ROUT = 0.25;
    /** How long an attacked NPC town stays on a war footing. */
    public static final long FOOTING = 2 * DAY;
    static final long NEVER = Long.MIN_VALUE / 4;

    // ------------------------------------------------------------------ memory

    /** What a kingdom remembers of recent fighting. Defeats and victories fade by half each day. */
    public static final class Memory {
        public double defeats, victories;
        public long updated, attacked = NEVER;
        /** Kingdoms that repelled this one, and when. */
        public final Map<UUID, Long> repelledBy = new HashMap<>();

        void decay(long now) {
            if (now <= updated) return;
            double k = Math.pow(0.5, (now - updated) / (double) DAY);
            defeats *= k;
            victories *= k;
            updated = now;
        }

        double defeatsAt(long now) { return now <= updated ? defeats : defeats * Math.pow(0.5, (now - updated) / (double) DAY); }

        boolean stale(long now) {
            return defeatsAt(now) < 0.05 && victories < 0.05 && now - attacked > FOOTING && repelledBy.values().stream().allMatch(t -> now - t > FOOTING);
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putDouble("defeats", defeats);
            t.putDouble("victories", victories);
            t.putLong("updated", updated);
            t.putLong("attacked", attacked);
            CompoundTag r = new CompoundTag();
            repelledBy.forEach((k, v) -> r.putLong(k.toString(), v));
            t.put("repelledBy", r);
            return t;
        }

        public static Memory load(CompoundTag t) {
            Memory mem = new Memory();
            mem.defeats = t.getDouble("defeats");
            mem.victories = t.getDouble("victories");
            mem.updated = t.getLong("updated");
            mem.attacked = t.contains("attacked") ? t.getLong("attacked") : NEVER;
            CompoundTag r = t.getCompound("repelledBy");
            for (String k : r.getAllKeys()) try { mem.repelledBy.put(UUID.fromString(k), r.getLong(k)); } catch (IllegalArgumentException ignored) {}
            return mem;
        }
    }

    static Memory memory(SettlementManager m, UUID kingdom, long now) {
        Memory mem = m.memory.computeIfAbsent(kingdom, k -> new Memory());
        mem.decay(now);
        if (mem.updated < now) mem.updated = now;
        return mem;
    }

    /** Recent defeats of a kingdom (decayed), without recording anything. */
    public static double defeats(SettlementManager m, UUID kingdom, long now) {
        Memory mem = m.memory.get(kingdom);
        return mem == null ? 0 : mem.defeatsAt(now);
    }

    static void raidStarted(SettlementManager m, Raids.Raid raid, long now) {
        memory(m, raid.defender, now).attacked = now;
    }

    /** A won raid is a victory for the attacker; a repelled, timed-out or destroyed one is a defeat, worse if no one came back. */
    static void raidEnded(SettlementManager m, Raids.Raid raid, long now) {
        Memory attacker = memory(m, raid.attacker, now), defender = memory(m, raid.defender, now);
        if (raid.held >= Raids.HOLD_TICKS) {
            attacker.victories += 1;
            defender.defeats += 1;
        } else {
            attacker.defeats += raid.soldiers.isEmpty() ? 1.5 : 1;
            attacker.repelledBy.put(raid.defender, now);
            defender.victories += 1;
        }
        prune(m, now);
    }

    /** Losing cities, by raid or by a Village Expansion siege, counts as a defeat per city. */
    static void citiesLost(SettlementManager m, UUID loser, int cities, long now) {
        if (cities > 0) memory(m, loser, now).defeats += cities;
    }

    static void prune(SettlementManager m, long now) {
        m.memory.values().removeIf(mem -> mem.stale(now));
    }

    // ------------------------------------------------------------------ strength

    /** A soldier's weight in battle: veterans count more, wounded ones less. */
    public static double power(Settlement.Soldier so) {
        double hp = so.maxHealth > 0 ? Math.max(0.25, Math.min(1, so.health / so.maxHealth)) : 1;
        return (1 + Math.min(20, Math.max(0, so.level)) * 0.05) * hp;
    }

    public static double power(Collection<Settlement.Soldier> soldiers) {
        double p = 0;
        for (Settlement.Soldier so : soldiers) p += power(so);
        return p;
    }

    /** A kingdom's whole army as Living Realms knows it; a Village Expansion faction also counts recruits outside loaded villages. */
    public static double kingdomPower(SettlementManager m, Settlement any) {
        double p = 0;
        int n = 0;
        for (Settlement c : Kingdoms.cities(m, any)) {
            p += power(c.soldiers);
            n += c.soldiers.size();
        }
        if (any.mirror() && Realms.on()) {
            int all = VxBridge.recruitCount(any.vxFaction);
            if (all > n) p += all - n;
        }
        return p;
    }

    /** Strength of a against b on Village Expansion's scale (0.5 is even), smoothed so empty armies do not divide by zero. */
    public static double ratio(double a, double b) { return (a + 1) / (a + b + 2); }

    // ------------------------------------------------------------------ war and peace (Village Expansion factions)

    /**
     * Village Expansion's odds for a war on a player claim (35% within 600 blocks, otherwise 18%, plus how much stronger the
     * faction is, at most 85%), fed with measured strength. A weaker faction, a war-weary one and one already at war are less eager.
     */
    public static double warChance(double ratio, boolean near, double defeats, boolean otherWar) {
        double p = (near ? 0.35 : 0.18) * Math.min(1, ratio * 2) + Math.max(0, ratio - 0.5);
        p /= 1 + Math.max(0, defeats);
        if (otherWar) p *= 0.5;
        return Math.min(0.85, p);
    }

    /** A faction losing a war sues for peace: badly outmatched, or outmatched and bloodied. */
    public static boolean suesForPeace(double ratio, double defeats) {
        return ratio < ROUT || ratio < SUE && defeats >= 1;
    }

    // ------------------------------------------------------------------ raids

    /** The expedition Raids.start would send from these troops: half of them, 2 to 12. */
    public static double expedition(List<Settlement.Soldier> troops) {
        int count = Math.min(12, Math.max(2, troops.size() / 2));
        return power(troops.subList(0, Math.min(count, troops.size())));
    }

    /** The target's local garrison, and its ruler if online. */
    static double defence(MinecraftServer server, Settlement t) {
        double d = power(t.soldiers);
        if (t.owner != null && server.getPlayerList().getPlayer(t.owner) != null) d += 1;
        return d;
    }

    public static double odds(double attack, double defence) {
        return attack <= 0 ? 0 : attack / (attack + defence);
    }

    /** What taking the city is worth: its size and rank, and for a capital the whole kingdom that falls with it. */
    static double value(SettlementManager m, Settlement t) {
        double v = 1 + t.population / 24.0 + t.tier.ordinal() * 0.25;
        if (t.capital) v *= 1 + 0.5 * (Kingdoms.cities(m, t).size() - 1);
        return v;
    }

    /**
     * How much an NPC kingdom wants this raid: winnable first (odds squared), then valuable, then near. Zero for a hopeless
     * raid. A kingdom that repelled this one in the last two days is avoided unless nothing better is in reach.
     */
    static double raidScore(MinecraftServer server, SettlementManager m, Settlement source, Settlement t, double attack, long now) {
        double odds = odds(attack, defence(server, t) * Siege.wallFactor(t)); // walls make a garrison count for more
        if (odds < HOPELESS) return 0;
        double range = Config.RAID_RANGE.get();
        double score = odds * odds * value(m, t) / (1 + Math.sqrt(source.center.distSqr(t.center)) / range);
        Memory mem = m.memory.get(source.kingdomId());
        Long repelled = mem == null ? null : mem.repelledBy.get(t.kingdomId());
        if (repelled != null && now - repelled < FOOTING) score *= 0.5;
        return score;
    }

    /** Soldiers an NPC city keeps at home before it marches: three, plus two per recent defeat, so a beaten kingdom regroups first. */
    public static int musterNeeded(double defeats) {
        return 3 + (int) Math.round(2 * Math.min(3, Math.max(0, defeats)));
    }

    // ------------------------------------------------------------------ recovery and growth (Living Realms NPC towns)

    public static boolean threatened(SettlementManager m, Settlement s, long now) {
        Memory mem = m.memory.get(s.kingdomId());
        return mem != null && now - mem.attacked < FOOTING;
    }

    /** An NPC town attacked in the last two days keeps half again its usual guard (at least two more). */
    public static int guardGoal(SettlementManager m, Settlement s, int base, long now) {
        return threatened(m, s, now) ? base + Math.max(2, base / 2) : base;
    }

    /** A threatened NPC town short of its guard spends on soldiers before new buildings. */
    public static boolean rearming(SettlementManager m, Settlement s, int base, long now) {
        return Config.AI_GARRISONS.get() && threatened(m, s, now) && s.soldiers.size() < guardGoal(m, s, base, now);
    }

    /** NPC research: the next rank's gate first, then economy and construction, then the rest; cheapest first. */
    @Nullable
    public static String aiResearch(Settlement s) {
        Set<String> gate = Autonomy.gateChain(s);
        return ResearchTree.nodes().values().stream().filter(n -> s.cannotResearch(n.id()) == null)
                .min(Comparator.comparingInt((ResearchTree.Node n) -> Autonomy.rank(n, gate))
                        .thenComparingInt(ResearchTree.Node::cost).thenComparing(ResearchTree.Node::id))
                .map(ResearchTree.Node::id).orElse(null);
    }

    private Strategy() {}
}
