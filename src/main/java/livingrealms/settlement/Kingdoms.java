package livingrealms.settlement;

import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierProgression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import java.util.*;

/** Sovereignty is separate from settlement size. Even a new kingdom has one seat. */
public final class Kingdoms {
    public static boolean same(Settlement a, Settlement b) { return a.kingdomId().equals(b.kingdomId()); }

    public static List<Settlement> cities(SettlementManager m, Settlement s) {
        return m.all().stream().filter(t -> same(t, s)).toList();
    }

    /** Deterministic legacy migration and repair: retain one seat, preserving buildings and stockpiles. */
    public static void normalize(SettlementManager m) {
        Map<UUID, List<Settlement>> groups = new LinkedHashMap<>();
        for (Settlement s : m.all()) groups.computeIfAbsent(s.kingdomId(), k -> new ArrayList<>()).add(s);
        for (var towns : groups.values()) {
            Settlement seat = towns.stream().min(Comparator
                    .comparingInt((Settlement s) -> s.capital ? 0 : s.tier == Tier.CAPITAL ? 1 : 2)
                    .thenComparingLong(s -> s.founded).thenComparing(s -> s.id)).orElseThrow();
            for (Settlement s : towns) {
                boolean capital = s == seat;
                if (s.capital != capital) s.dirty = true;
                s.capital = capital;
                if (!capital && s.tier == Tier.CAPITAL) {
                    s.tier = Tier.CITY;
                    s.log("This city is no longer the kingdom capital; its buildings and stores remain.");
                }
            }
        }
    }

    public static String designate(MinecraftServer server, SettlementManager m, Settlement s) {
        if (s.capital) return "This is already your capital";
        if (Raids.engaged(m, s)) return "Cannot move the capital during a raid";
        long now = server.overworld().getGameTime();
        if (cities(m, s).stream().anyMatch(t -> t.capitalMovedAt > now)) return "Wait one Minecraft day before moving the capital again";
        for (Settlement t : cities(m, s)) {
            t.capital = t == s;
            t.capitalMovedAt = now + 24000;
            t.log(s.name + " is now the kingdom capital. Losing it transfers every city.");
        }
        normalize(m);
        return null;
    }

    /** Only invoked by a completed server-side raid, never by a client 'victory' claim. */
    static int conquer(MinecraftServer server, SettlementManager m, Settlement attacker, Settlement target) {
        if (same(attacker, target)) return 0;
        return transfer(server, m, attacker, target, attacker.isAi() ? attacker.name + "'s kingdom" : attacker.ownerName);
    }

    /** Hands the target to the winner's kingdom; a capital takes every city of its kingdom with it. */
    static int transfer(MinecraftServer server, SettlementManager m, Settlement winner, Settlement target, String by) {
        if (same(winner, target)) return 0;
        List<Settlement> captured = target.capital ? new ArrayList<>(cities(m, target)) : new ArrayList<>(List.of(target));
        // Village Expansion records change first: ordinary cities before the capital, so it never splits the losing faction.
        captured.sort(Comparator.comparing((Settlement s) -> s.capital));
        Strategy.citiesLost(m, target.kingdomId(), captured.size(), server.overworld().getGameTime());
        Map<Settlement, String> losers = Realms.before(server, m, winner, captured);
        for (Settlement s : captured) {
            s.owner = winner.owner;
            s.ownerName = winner.ownerName;
            s.kingdom = winner.kingdomId();
            s.capital = false; // The winner keeps its existing capital.
            s.manualAlarm = false;
            s.alarmUntil = 0;
            s.stance = livingrealms.military.Stance.GARRISON;
            s.raidCooldown = server.overworld().getGameTime() + livingrealms.Config.RAID_COOLDOWN.get();
            Realms.handOver(server, m, winner, s, losers.get(s));
            if (!s.mirror()) for (var soldier : s.soldiers) {
                soldier.royalGuard = false;
                Entity e = SoldierProgression.findEntity(server, soldier.entity);
                if (e != null) {
                    // Captured mounts retain their own ownership; the surrendered rider dismounts.
                    e.stopRiding();
                    RecruitsBridge.allegiance(e, s.kingdomId());
                }
            }
            s.log("Captured by " + by);
        }
        normalize(m);
        return captured.size();
    }

    private Kingdoms() {}
}
