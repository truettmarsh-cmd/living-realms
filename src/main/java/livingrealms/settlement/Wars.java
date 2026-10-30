package livingrealms.settlement;

import livingrealms.LivingRealms;
import livingrealms.settlement.Statecraft.Term;
import livingrealms.settlement.Statecraft.Tribute;
import livingrealms.settlement.Statecraft.War;
import livingrealms.settlement.vx.VxBridge;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.*;

/** The war ledger: war score and exhaustion, peace terms (tribute, a ceded city, truce length) and tribute payments. */
public final class Wars {
    /** Exhaustion every side gains per day at war, per soldier lost, per city lost and per failed raid. */
    public static final double DAILY = 10, PER_SOLDIER = 3, PER_CITY = 12, PER_FAILED_RAID = 5;
    /** War score for taking a city, holding off a raid, and per enemy soldier killed on a raid. */
    public static final double CAPTURE = 15, RAID_WON = 5, RAID_REPELLED = 10, PER_KILL = 1;
    public static final int TRIBUTE_DAYS = 5;
    static final String LOCKED = "Village Expansion allows no peace for a few minutes after an attack; try again shortly";

    // ------------------------------------------------------------------ opening and scoring

    static War open(MinecraftServer server, SettlementManager m, UUID a, UUID b, long now, boolean announce) {
        War w = m.state.war(a, b);
        if (w != null) return w;
        w = new War();
        w.a = a; w.b = b; w.started = now;
        m.state.wars.add(w);
        if (announce) {
            String msg = Statecraft.name(m, a) + " and " + Statecraft.name(m, b) + " are at war.";
            Statecraft.tellKingdom(server, m, a, msg);
            Statecraft.tellKingdom(server, m, b, msg);
            livingrealms.chronicle.Chronicle.war(m, a, b);
        }
        return w;
    }

    static void raidStarted(MinecraftServer server, SettlementManager m, Raids.Raid raid, Settlement source, Settlement target) {
        if (Kingdoms.same(source, target)) return;
        War w = open(server, m, raid.attacker, raid.defender, server.overworld().getGameTime(), true);
        w.marching.put(raid.source, raid.soldiers.size());
    }

    static void raidEnded(MinecraftServer server, SettlementManager m, Raids.Raid raid, boolean captured) {
        War w = m.state.war(raid.attacker, raid.defender);
        if (w == null) return;
        Integer sent = w.marching.remove(raid.source);
        int lost = Math.max(0, (sent == null ? raid.soldiers.size() : sent) - raid.soldiers.size());
        w.tire(raid.attacker, PER_SOLDIER * lost);
        w.addScore(raid.defender, PER_KILL * lost);
        if (captured) w.addScore(raid.attacker, RAID_WON);
        else { w.addScore(raid.defender, RAID_REPELLED); w.tire(raid.attacker, PER_FAILED_RAID); }
    }

    static void captured(MinecraftServer server, SettlementManager m, UUID winner, Statecraft.Prior prior) {
        if (winner.equals(prior.kingdom())) return;
        War w = m.state.war(winner, prior.kingdom());
        if (w == null) return;
        w.addScore(winner, CAPTURE);
        w.tire(prior.kingdom(), PER_CITY);
    }

    // ------------------------------------------------------------------ the passing of days

    static void step(MinecraftServer server, SettlementManager m, long now, double f) {
        for (War w : List.copyOf(m.state.wars)) {
            boolean aAlive = Statecraft.seat(m, w.a) != null, bAlive = Statecraft.seat(m, w.b) != null;
            if (!aAlive || !bAlive) {
                m.state.wars.remove(w);
                UUID survivor = aAlive ? w.a : w.b;
                if (aAlive || bAlive) Statecraft.tellKingdom(server, m, survivor, "The war is over: " + Statecraft.name(m, w.other(survivor)) + " has fallen.");
                continue;
            }
            if (w.offer != null && w.offer.expires <= now) w.offer = null;
            w.tire(w.a, DAILY * f);
            w.tire(w.b, DAILY * f);
            if ((w.exA >= 100 || w.exB >= 100) && !fighting(m, w) && !veLocked(server, m, w)) wearied(server, m, w, now);
        }
    }

    /** A side worn down to nothing must make peace; the other side's war score sets the terms. */
    static void wearied(MinecraftServer server, SettlementManager m, War w, long now) {
        UUID tired = w.exA >= w.exB ? w.a : w.b, other = w.other(tired);
        Term t = Term.WHITE;
        if (w.exhaustion(other) < 100) t = w.scoreFor(other) >= (Statecraft.isPlayer(m, tired) ? 30 : 20) ? Term.TRIBUTE : Term.WHITE;
        conclude(server, m, w, other, t, 3, "war-weariness: " + Statecraft.name(m, tired) + " can fight no longer");
    }

    /** Village Expansion forbids peace for a few minutes after an attack between two factions. */
    static boolean veLocked(MinecraftServer server, SettlementManager m, War w) {
        if (!Realms.on()) return false;
        try {
            String ra = realmOf(server, m, w.a, false), rb = realmOf(server, m, w.b, false);
            return ra != null && rb != null && VxBridge.atWar(ra, rb) && VxBridge.peaceLocked(server.overworld(), ra, rb);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean fighting(SettlementManager m, War w) {
        return m.raids.stream().anyMatch(r -> w.involves(r.attacker) && w.involves(r.defender));
    }

    /** Village Expansion wars are wars here too, and its peace ends them. */
    static void syncVillageExpansion(MinecraftServer server, SettlementManager m, long now) {
        if (!Realms.on()) return;
        Map<String, UUID> factions = new LinkedHashMap<>();
        for (Settlement s : m.all()) if (s.mirror()) factions.putIfAbsent(s.vxFaction, s.kingdomId());
        for (var r : m.realms.entrySet()) {
            if (Statecraft.seat(m, r.getKey()) == null) continue;
            for (var f : factions.entrySet()) {
                if (!VxBridge.atWar(r.getValue(), f.getKey())) continue;
                if (m.state.war(r.getKey(), f.getValue()) == null && m.state.truceUntil(r.getKey(), f.getValue()) <= now)
                    open(server, m, r.getKey(), f.getValue(), now, false);
            }
        }
        for (War w : List.copyOf(m.state.wars)) {
            String ra = realmOf(server, m, w.a, false), rb = realmOf(server, m, w.b, false);
            boolean veSide = factions.containsValue(w.a) || factions.containsValue(w.b);
            if (veSide && ra != null && rb != null && !VxBridge.atWar(ra, rb) && !fighting(m, w)) {
                m.state.wars.remove(w);
                String msg = "Village Expansion's diplomacy ended the war between " + Statecraft.name(m, w.a) + " and " + Statecraft.name(m, w.b) + ".";
                Statecraft.tellKingdom(server, m, w.a, msg);
                Statecraft.tellKingdom(server, m, w.b, msg);
            }
        }
    }

    /** The Recruits faction of a kingdom; created for Living Realms kingdoms only when asked to. */
    @Nullable
    static String realmOf(MinecraftServer server, SettlementManager m, UUID kingdom, boolean create) {
        Settlement s = Statecraft.seat(m, kingdom);
        if (s == null || !Realms.on()) return null;
        if (s.mirror()) return s.vxFaction;
        return create ? Realms.realm(server, m, s) : Realms.knownRealm(m, s);
    }

    // ------------------------------------------------------------------ peace terms

    static double willingness(SettlementManager m, War w, UUID proposer) {
        UUID enemy = w.other(proposer);
        return w.scoreFor(proposer) + 0.6 * w.exhaustion(enemy) - 0.3 * w.exhaustion(proposer);
    }

    static double cost(Term t, int days) {
        double base = switch (t) { case WHITE -> 10; case TRIBUTE -> 30; case CITY -> 50; case PAY -> -25; };
        return base + (days >= 7 ? 10 : days >= 3 ? 5 : 0);
    }

    static int truceDays(int days) { return days >= 7 ? 7 : days >= 3 ? 3 : 1; }

    /** Would this NPC kingdom accept? Village Expansion's own peace odds count for its factions. */
    static boolean accepts(MinecraftServer server, SettlementManager m, War w, UUID proposer, Term t, int days) {
        double will = willingness(m, w, proposer);
        UUID enemy = w.other(proposer);
        Settlement es = Statecraft.seat(m, enemy);
        if (es != null && es.mirror() && Realms.on()) {
            try {
                String mine = realmOf(server, m, proposer, false);
                if (mine != null && VxBridge.wouldAcceptPeace(server.overworld(), es.vxFaction, mine)) will += 10;
                // a faction that has sued for peace (Strategy.suesForPeace) takes white peace or modest tribute
                if (mine != null && Realms.offersPeace(es.vxFaction, mine, server.overworld().getGameTime())) will += 25;
            } catch (RuntimeException ignored) {}
        }
        return will >= cost(t, days);
    }

    /** The city an enemy would cede: its non-capital city nearest your seat. */
    @Nullable
    static Settlement cedable(SettlementManager m, UUID enemy, UUID proposer) {
        Settlement seat = Statecraft.seat(m, proposer);
        if (seat == null) return null;
        return Statecraft.cities(m, enemy).stream().filter(c -> !c.capital && c.dim.equals(seat.dim) && !Raids.engaged(m, c))
                .min(Comparator.comparingDouble(c -> c.center.distSqr(seat.center))).orElse(null);
    }

    static int tributeFor(SettlementManager m, UUID payer) {
        return Math.max(4, Math.min(24, 4 + 2 * Statecraft.cities(m, payer).size()));
    }

    @Nullable
    static String propose(MinecraftServer server, SettlementManager m, UUID proposer, @Nullable UUID enemy, Term t, int days) {
        if (enemy == null) return "No such war";
        War w = m.state.war(proposer, enemy);
        if (w == null) return "You are not at war with them";
        if (fighting(m, w)) return "Not while a raid is under way";
        if (veLocked(server, m, w)) return LOCKED;
        days = truceDays(days);
        if (t == Term.CITY && cedable(m, enemy, proposer) == null) return Statecraft.name(m, enemy) + " has no city to cede but its capital";
        long now = server.overworld().getGameTime();
        if (Statecraft.isPlayer(m, enemy)) {
            Statecraft.Offer o = new Statecraft.Offer();
            o.from = proposer; o.term = t; o.days = days; o.expires = now + Statecraft.dayLength();
            w.offer = o;
            Statecraft.tellKingdom(server, m, enemy, Statecraft.name(m, proposer) + " offers peace: " + terms(m, w, proposer, t, days) + ". Answer on the Kingdom page.");
            Statecraft.tellKingdom(server, m, proposer, "Peace offered to " + Statecraft.name(m, enemy) + ": " + terms(m, w, proposer, t, days) + ".");
            return null;
        }
        if (!accepts(server, m, w, proposer, t, days))
            return Statecraft.name(m, enemy) + " refuses " + t.label.toLowerCase(Locale.ROOT) + ": win more of the war or wear them down first";
        conclude(server, m, w, proposer, t, days, null);
        return null;
    }

    @Nullable
    static String answer(MinecraftServer server, SettlementManager m, UUID me, @Nullable UUID enemy, boolean accept) {
        if (enemy == null) return "No such war";
        War w = m.state.war(me, enemy);
        if (w == null || w.offer == null || !w.offer.from.equals(enemy)) return "They have made no offer";
        Statecraft.Offer o = w.offer;
        w.offer = null;
        if (!accept) {
            Statecraft.tellKingdom(server, m, enemy, Statecraft.name(m, me) + " rejected your peace offer.");
            return null;
        }
        if (fighting(m, w)) { w.offer = o; return "Not while a raid is under way"; }
        if (veLocked(server, m, w)) { w.offer = o; return LOCKED; }
        if (o.term == Term.CITY && cedable(m, me, enemy) == null) return "You have no city to cede but your capital";
        conclude(server, m, w, enemy, o.term, o.days, null);
        return null;
    }

    static String terms(SettlementManager m, War w, UUID proposer, Term t, int days) {
        UUID enemy = w.other(proposer);
        String truce = "a truce of " + days + (days == 1 ? " day" : " days");
        return switch (t) {
            case WHITE -> "white peace and " + truce;
            case TRIBUTE -> Statecraft.name(m, enemy) + " pays " + tributeFor(m, enemy) + " emeralds a day for " + TRIBUTE_DAYS + " days, and " + truce;
            case PAY -> Statecraft.name(m, proposer) + " pays " + tributeFor(m, proposer) + " emeralds a day for " + TRIBUTE_DAYS + " days, and " + truce;
            case CITY -> {
                Settlement c = cedable(m, enemy, proposer);
                yield Statecraft.name(m, enemy) + " cedes " + (c == null ? "a city" : c.name) + ", and " + truce;
            }
        };
    }

    /** Ends the war on these terms; the proposer is the side the terms favour (or the one paying, for PAY). */
    static void conclude(MinecraftServer server, SettlementManager m, War w, UUID proposer, Term t, int days, @Nullable String why) {
        UUID enemy = w.other(proposer);
        String text = terms(m, w, proposer, t, days);
        Settlement ceded = t == Term.CITY ? cedable(m, enemy, proposer) : null;
        m.state.wars.remove(w);
        long now = server.overworld().getGameTime();
        long truce = (long) days * Statecraft.dayLength();
        m.state.truces.put(Statecraft.pair(proposer, enemy), now + truce);
        switch (t) {
            case TRIBUTE -> tribute(server, m, enemy, proposer, now);
            case PAY -> tribute(server, m, proposer, enemy, now);
            case CITY -> {
                Settlement seat = Statecraft.seat(m, proposer);
                if (ceded != null && seat != null) {
                    Kingdoms.transfer(server, m, seat, ceded, Statecraft.name(m, proposer));
                    ceded.log("Ceded to " + Statecraft.name(m, proposer) + " by treaty");
                }
            }
            default -> {}
        }
        // After any ceded village moved: Village Expansion's conquest bookkeeping may set the two factions enemies again.
        vePeace(server, m, proposer, enemy, truce, now);
        String msg = "Peace between " + Statecraft.name(m, proposer) + " and " + Statecraft.name(m, enemy) + (why == null ? "" : ", forced by " + why) + ": " + text + ".";
        Statecraft.tellKingdom(server, m, proposer, msg);
        Statecraft.tellKingdom(server, m, enemy, msg);
        livingrealms.chronicle.Chronicle.peace(m, proposer, enemy, text);
    }

    /** Village Expansion records the peace too, so its factions stop marching and honour the truce. */
    static void vePeace(MinecraftServer server, SettlementManager m, UUID x, UUID y, long ticks, long now) {
        if (!Realms.on()) return;
        try {
            String rx = realmOf(server, m, x, false), ry = realmOf(server, m, y, false);
            if (rx == null || ry == null || rx.equals(ry)) return;
            ServerLevel level = server.overworld();
            if (VxBridge.atWar(rx, ry)) VxBridge.makePeace(level, rx, ry, ticks);
            m.truces.put(rx.compareTo(ry) < 0 ? rx + "|" + ry : ry + "|" + rx, now + ticks);
        } catch (RuntimeException e) {
            LivingRealms.LOG.error("Village Expansion peace failed", e);
        }
    }

    // ------------------------------------------------------------------ tribute

    static Tribute tribute(MinecraftServer server, SettlementManager m, UUID payer, UUID payee, long now) {
        m.state.tributes.removeIf(r -> r.payer.equals(payer) && r.payee.equals(payee) || r.payer.equals(payee) && r.payee.equals(payer));
        Tribute r = new Tribute();
        r.payer = payer; r.payee = payee; r.perDay = tributeFor(m, payer); r.daysLeft = TRIBUTE_DAYS; r.nextDue = now;
        m.state.tributes.add(r);
        pay(server, m, r, now);
        return r;
    }

    static void payTributes(MinecraftServer server, SettlementManager m, long now) {
        for (Tribute r : List.copyOf(m.state.tributes)) if (r.nextDue <= now) pay(server, m, r, now);
    }

    /** One day's tribute: emeralds from a player's stockpiles, treasury from an NPC town; Village Expansion villages levy theirs. */
    static void pay(MinecraftServer server, SettlementManager m, Tribute r, long now) {
        Settlement from = Statecraft.seat(m, r.payer), to = Statecraft.seat(m, r.payee);
        if (from == null || to == null) { m.state.tributes.remove(r); return; }
        int paid = 0;
        if (Statecraft.isPlayer(m, r.payer)) {
            List<Settlement> cs = new ArrayList<>(Statecraft.cities(m, r.payer));
            cs.sort(Comparator.comparing((Settlement c) -> !c.capital));
            for (Settlement c : cs) if (paid < r.perDay) paid += c.stock.takeUpTo(Items.EMERALD, r.perDay - paid);
        } else if (from.mirror()) {
            paid = r.perDay;
        } else {
            double need = r.perDay * 5.0, took = 0;
            for (Settlement c : Statecraft.cities(m, r.payer)) {
                double t = Math.min(c.wealth, need - took);
                if (t > 0) { c.wealth -= t; took += t; }
            }
            paid = (int) Math.floor(took / 5 + 1e-6);
        }
        String payer = Statecraft.name(m, r.payer), payee = Statecraft.name(m, r.payee);
        if (paid <= 0 && r.perDay > 0) {
            m.state.tributes.remove(r);
            m.state.truces.remove(Statecraft.pair(r.payer, r.payee));
            String msg = payer + " defaulted on its tribute to " + payee + ". The truce is broken.";
            Statecraft.tellKingdom(server, m, r.payer, msg);
            Statecraft.tellKingdom(server, m, r.payee, msg);
            return;
        }
        if (to.owner != null) to.stock.force(Items.EMERALD, paid);
        else if (!to.mirror()) to.wealth = Math.min(5000, to.wealth + paid * 5.0);
        if (paid < r.perDay) r.missed++;
        r.daysLeft--;
        r.nextDue = now + Statecraft.dayLength();
        String msg = payer + " paid " + paid + (paid < r.perDay ? " of " + r.perDay : "") + " emeralds tribute to " + payee
                + (r.daysLeft <= 0 ? " (final payment)" : " (" + r.daysLeft + " more days)");
        to.log(msg);
        from.log(msg);
        if (r.daysLeft <= 0) m.state.tributes.remove(r);
    }

    // ------------------------------------------------------------------ City Manager

    static CompoundTag describe(MinecraftServer server, SettlementManager m, War w, UUID k, long now) {
        UUID enemy = w.other(k);
        CompoundTag t = new CompoundTag();
        t.putUUID("enemy", enemy);
        t.putString("name", Statecraft.name(m, enemy));
        t.putInt("score", (int) Math.round(w.scoreFor(k)));
        t.putInt("mine", (int) Math.round(w.exhaustion(k)));
        t.putInt("theirs", (int) Math.round(w.exhaustion(enemy)));
        t.putInt("days", (int) Math.max(0, (now - w.started) / Statecraft.dayLength()));
        t.putBoolean("player", Statecraft.isPlayer(m, enemy));
        t.putBoolean("fighting", fighting(m, w));
        t.putBoolean("locked", veLocked(server, m, w));
        int[] accept = new int[Term.values().length * 3];
        for (Term term : Term.values()) for (int i = 0; i < 3; i++)
            accept[term.ordinal() * 3 + i] = accepts(server, m, w, k, term, Statecraft.TRUCE_DAYS[i]) ? 1 : 0;
        t.putIntArray("accept", accept);
        t.putInt("will", (int) Math.round(willingness(m, w, k)));
        Settlement c = cedable(m, enemy, k);
        t.putString("cede", c == null ? "" : c.name);
        t.putInt("tributeIn", tributeFor(m, enemy));
        t.putInt("tributeOut", tributeFor(m, k));
        if (w.offer != null) {
            t.putBoolean("offerMine", w.offer.from.equals(k));
            t.putString("offer", terms(m, w, w.offer.from, w.offer.term, w.offer.days));
        }
        return t;
    }

    private Wars() {}
}
