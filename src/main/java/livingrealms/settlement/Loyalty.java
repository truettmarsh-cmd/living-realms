package livingrealms.settlement;

import livingrealms.building.Category;
import livingrealms.settlement.vx.VxBridge;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loyalty of conquered cities. A city you take starts restless; garrison, good mood, low taxes, faith and a near capital
 * win it over. Below {@link #UNREST} it loses mood; left under {@link #REBEL} for half a day it rebels: it returns to
 * its former lords if they still stand, or declares itself free. At {@link #LOYAL} it is part of your realm for good.
 */
public final class Loyalty {
    public static final double UNREST = 40, REBEL = 10, LOYAL = 90, PACIFY_GAIN = 12;
    public static final int PACIFY_COST = 24;
    /** Mood penalty by settlement id, for the simulation (which has no manager at hand). */
    static final Map<UUID, Double> UNREST_BY_CITY = new ConcurrentHashMap<>();

    static void unrest(UUID city, @Nullable Statecraft.Fealty l) {
        double u = l == null || l.value >= UNREST ? 0 : (UNREST - l.value) / 4;
        if (u <= 0) UNREST_BY_CITY.remove(city); else UNREST_BY_CITY.put(city, u);
    }

    /** A city changed hands: conquered by a player, it starts restless; taken by anyone else, it forgets. */
    static void transferred(MinecraftServer server, SettlementManager m, Settlement s, Statecraft.Prior prior) {
        if (s.owner == null || s.mirror() || s.kingdomId().equals(prior.kingdom())) {
            m.state.loyalty.remove(s.id);
            unrest(s.id, null);
            return;
        }
        Statecraft.Fealty l = new Statecraft.Fealty();
        l.value = Statecraft.Fealty.START;
        l.former = prior.kingdom();
        l.formerName = prior.name();
        l.formerFaction = prior.faction();
        l.since = server.overworld().getGameTime();
        m.state.loyalty.put(s.id, l);
        unrest(s.id, l);
        s.log(s.name + " resents its new rulers (loyalty " + (int) l.value + "). Garrison it, keep it fed and lightly taxed.");
    }

    /** What moves a conquered city's loyalty per day, as (reason, amount). */
    static List<Map.Entry<String, Double>> factors(MinecraftServer server, SettlementManager m, Settlement s, Statecraft.Fealty l) {
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        out.add(Map.entry("settling under your rule", 4.0));
        int garrison = s.soldiers.size();
        out.add(garrison > 0 ? Map.entry("garrison of " + garrison, (double) Math.min(6, garrison)) : Map.entry("no garrison", -3.0));
        double mood = (s.happiness - 50) / 8;
        if (Math.abs(mood) >= 0.5) out.add(Map.entry(s.happiness >= 50 ? "content people" : "unhappy people", mood));
        double tax = switch (s.taxLevel) { case 0 -> 3; case 2 -> -2; case 3 -> -5; default -> 0; };
        if (tax != 0) out.add(Map.entry(tax > 0 ? "no taxes" : "taxes", tax));
        if (s.starving) out.add(Map.entry("starving", -6.0));
        int chapel = Math.min(3, s.levels(Category.CHAPEL));
        if (chapel > 0) out.add(Map.entry("faith", (double) chapel));
        Settlement seat = Statecraft.seat(m, s.kingdomId());
        if (seat != null && seat != s) {
            double far = !seat.dim.equals(s.dim) ? 4 : Math.min(4, Math.max(0, (Math.sqrt(seat.center.distSqr(s.center)) - 300) / 250));
            if (far >= 0.5) out.add(Map.entry("far from the capital", -far));
        }
        if (l.former != null && Statecraft.seat(m, l.former) != null && m.state.war(s.kingdomId(), l.former) != null)
            out.add(Map.entry(l.formerName + " wants it back", -4.0));
        double weary = 0;
        for (Statecraft.War w : m.state.wars) if (w.involves(s.kingdomId())) weary = Math.max(weary, w.exhaustion(s.kingdomId()) / 25);
        if (weary >= 0.5) out.add(Map.entry("war-weariness", -weary));
        return out;
    }

    static double perDay(MinecraftServer server, SettlementManager m, Settlement s, Statecraft.Fealty l) {
        double sum = 0;
        for (var e : factors(server, m, s, l)) sum += e.getValue();
        return sum;
    }

    static void step(MinecraftServer server, SettlementManager m, long now, double f) {
        int rebelSteps = Math.max(1, (int) Math.round(Statecraft.dayLength() / 2.0 / Statecraft.STEP));
        for (var e : List.copyOf(m.state.loyalty.entrySet())) {
            Settlement s = m.get(e.getKey());
            Statecraft.Fealty l = e.getValue();
            if (s == null || s.owner == null || s.mirror()) { m.state.loyalty.remove(e.getKey()); unrest(e.getKey(), null); continue; }
            l.value = Statecraft.clamp(l.value + perDay(server, m, s, l) * f, 0, 100);
            unrest(s.id, l);
            if (l.value >= LOYAL) {
                m.state.loyalty.remove(s.id);
                unrest(s.id, null);
                Statecraft.tellKingdom(server, m, s.kingdomId(), s.name + " is now loyal to your crown.");
                continue;
            }
            if (l.value < 20 && !l.warned) {
                l.warned = true;
                Statecraft.tellKingdom(server, m, s.kingdomId(), s.name + " is close to rebellion (loyalty " + (int) l.value + ").");
            } else if (l.value >= 30) l.warned = false;
            if (l.value < REBEL && !s.capital && !Raids.engaged(m, s)) {
                if (++l.lowSteps >= rebelSteps) rebel(server, m, s, l);
            } else l.lowSteps = 0;
        }
    }

    /** The city breaks away: back to its former lords if they still stand, otherwise free. */
    static void rebel(MinecraftServer server, SettlementManager m, Settlement s, Statecraft.Fealty l) {
        UUID ruler = s.kingdomId();
        String rulerName = Statecraft.name(m, ruler);
        Settlement to = null;
        if (!l.formerFaction.isEmpty() && Realms.on() && VxBridge.exists(l.formerFaction)) to = Realms.kingdomOf(m, l.formerFaction);
        if (to == null && l.former != null && !Statecraft.isPlayer(m, l.former)) {
            Settlement seat = Statecraft.seat(m, l.former);
            if (seat != null && (!seat.mirror() || Realms.on())) to = seat;
        }
        String freeName = null;
        if (to == null) {
            to = new Settlement();
            to.kingdom = UUID.nameUUIDFromBytes(("livingrealms:rebels:" + s.id + ":" + server.overworld().getGameTime()).getBytes(StandardCharsets.UTF_8));
            freeName = "the Free City of " + s.name;
            to.name = freeName;
            m.state.names.put(to.kingdom, freeName);
        }
        String by = freeName != null ? "its own people" : Statecraft.name(m, to.kingdomId());
        Kingdoms.transfer(server, m, to, s, by);
        m.state.loyalty.remove(s.id);
        unrest(s.id, null);
        String msg = s.name + " rose in rebellion against " + rulerName + (freeName != null ? " and declared itself free." : " and returned to " + by + ".");
        s.log(msg);
        Statecraft.tellKingdom(server, m, ruler, msg);
        Wars.open(server, m, ruler, s.kingdomId(), server.overworld().getGameTime(), false);
    }

    /** Largesse: emeralds from the city's stockpile buy goodwill, once a day. */
    @Nullable
    static String pacify(MinecraftServer server, SettlementManager m, Settlement s) {
        Statecraft.Fealty l = m.state.loyalty.get(s.id);
        if (l == null) return s.name + " is already loyal";
        long now = server.overworld().getGameTime();
        if (l.pacifiedUntil > now) return "The people still remember your last largesse: wait " + Math.max(1, (l.pacifiedUntil - now) / 1200) + " min";
        if (s.stock.count(Items.EMERALD) < PACIFY_COST) return "Needs " + PACIFY_COST + " emeralds in this city's stockpile";
        s.stock.take(Items.EMERALD, PACIFY_COST);
        l.value = Statecraft.clamp(l.value + PACIFY_GAIN, 0, 100);
        l.pacifiedUntil = now + Statecraft.dayLength();
        unrest(s.id, l);
        s.log("Largesse for the people of " + s.name + ": loyalty " + (int) l.value);
        return null;
    }

    static CompoundTag describe(MinecraftServer server, SettlementManager m, Settlement s, long now) {
        CompoundTag t = new CompoundTag();
        Statecraft.Fealty l = m.state.loyalty.get(s.id);
        t.putBoolean("conquered", l != null);
        if (l == null) return t;
        t.putInt("value", (int) Math.round(l.value));
        t.putString("former", l.formerName);
        t.putDouble("perDay", perDay(server, m, s, l));
        ListTag why = new ListTag();
        for (var e : factors(server, m, s, l)) {
            double v = e.getValue();
            why.add(StringTag.valueOf((v >= 0 ? "+" : "") + (Math.abs(v - Math.round(v)) < 0.05 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v)) + " " + e.getKey()));
        }
        t.put("why", why);
        t.putInt("unrest", (int) Math.round(UNREST_BY_CITY.getOrDefault(s.id, 0.0)));
        t.putBoolean("danger", l.value < REBEL);
        t.putInt("pacifyCost", PACIFY_COST);
        t.putInt("pacifyIn", (int) Math.max(0, (l.pacifiedUntil - now) / 1200));
        return t;
    }

    private Loyalty() {}
}
