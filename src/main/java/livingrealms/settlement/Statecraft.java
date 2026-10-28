package livingrealms.settlement;

import livingrealms.Config;
import livingrealms.LivingRealms;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.*;

/**
 * The kingdom's affairs beyond one city: the war ledger (score, exhaustion, peace terms), tribute and truces between
 * kingdoms, and the loyalty of conquered cities. Saved with the settlements; works with and without Village Expansion.
 */
public final class Statecraft {
    /** How often the ledger advances, in ticks. */
    public static final int STEP = 1200;

    public enum Term {
        WHITE("White peace"), TRIBUTE("Demand tribute"), CITY("Demand a city"), PAY("Offer tribute");
        public final String label;
        Term(String label) { this.label = label; }
        static Term of(String s) { try { return valueOf(s); } catch (Exception e) { return WHITE; } }
    }
    public static final int[] TRUCE_DAYS = {1, 3, 7};

    public static final class Offer {
        public UUID from;
        public Term term = Term.WHITE;
        public int days = 1;
        public long expires;
    }

    public static final class War {
        public UUID a, b;
        public long started;
        /** In favour of a; -100..100. */
        public double score;
        /** War exhaustion of a and b, 0..100. At 100 a side must make peace. */
        public double exA, exB;
        /** Soldiers each marching raid set out with, by attacking city. */
        public final Map<UUID, Integer> marching = new HashMap<>();
        @Nullable public Offer offer;

        public boolean involves(UUID k) { return a.equals(k) || b.equals(k); }
        public UUID other(UUID k) { return a.equals(k) ? b : a; }
        public double scoreFor(UUID k) { return a.equals(k) ? score : -score; }
        public double exhaustion(UUID k) { return a.equals(k) ? exA : exB; }
        void addScore(UUID k, double v) { score = clamp(score + (a.equals(k) ? v : -v), -100, 100); }
        void tire(UUID k, double v) { if (a.equals(k)) exA = clamp(exA + v, 0, 100); else exB = clamp(exB + v, 0, 100); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("a", a); t.putUUID("b", b); t.putLong("started", started);
            t.putDouble("score", score); t.putDouble("exA", exA); t.putDouble("exB", exB);
            CompoundTag mt = new CompoundTag();
            marching.forEach((k, v) -> mt.putInt(k.toString(), v));
            t.put("marching", mt);
            if (offer != null) {
                CompoundTag o = new CompoundTag();
                o.putUUID("from", offer.from); o.putString("term", offer.term.name()); o.putInt("days", offer.days); o.putLong("expires", offer.expires);
                t.put("offer", o);
            }
            return t;
        }

        static War load(CompoundTag t) {
            War w = new War();
            w.a = t.getUUID("a"); w.b = t.getUUID("b"); w.started = t.getLong("started");
            w.score = clamp(t.getDouble("score"), -100, 100);
            w.exA = clamp(t.getDouble("exA"), 0, 100); w.exB = clamp(t.getDouble("exB"), 0, 100);
            CompoundTag mt = t.getCompound("marching");
            for (String k : mt.getAllKeys()) try { w.marching.put(UUID.fromString(k), mt.getInt(k)); } catch (IllegalArgumentException ignored) {}
            if (t.contains("offer")) {
                CompoundTag o = t.getCompound("offer");
                Offer offer = new Offer();
                offer.from = o.getUUID("from"); offer.term = Term.of(o.getString("term"));
                offer.days = Math.max(1, Math.min(7, o.getInt("days"))); offer.expires = o.getLong("expires");
                w.offer = offer;
            }
            return w;
        }
    }

    public static final class Tribute {
        public UUID payer, payee;
        public int perDay, daysLeft, missed;
        public long nextDue;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("payer", payer); t.putUUID("payee", payee);
            t.putInt("perDay", perDay); t.putInt("daysLeft", daysLeft); t.putInt("missed", missed); t.putLong("nextDue", nextDue);
            return t;
        }

        static Tribute load(CompoundTag t) {
            Tribute r = new Tribute();
            r.payer = t.getUUID("payer"); r.payee = t.getUUID("payee");
            r.perDay = Math.max(0, t.getInt("perDay")); r.daysLeft = Math.max(0, t.getInt("daysLeft"));
            r.missed = t.getInt("missed"); r.nextDue = t.getLong("nextDue");
            return r;
        }
    }

    public static final class Fealty {
        public double value = START;
        public UUID former;
        public String formerName = "", formerFaction = "";
        public long since, pacifiedUntil;
        public int lowSteps;
        public boolean warned;
        public static final double START = 30;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putDouble("value", value);
            if (former != null) t.putUUID("former", former);
            t.putString("formerName", formerName); t.putString("formerFaction", formerFaction);
            t.putLong("since", since); t.putLong("pacifiedUntil", pacifiedUntil);
            t.putInt("lowSteps", lowSteps); t.putBoolean("warned", warned);
            return t;
        }

        static Fealty load(CompoundTag t) {
            Fealty l = new Fealty();
            l.value = clamp(t.getDouble("value"), 0, 100);
            if (t.hasUUID("former")) l.former = t.getUUID("former");
            l.formerName = t.getString("formerName"); l.formerFaction = t.getString("formerFaction");
            l.since = t.getLong("since"); l.pacifiedUntil = t.getLong("pacifiedUntil");
            l.lowSteps = Math.max(0, t.getInt("lowSteps")); l.warned = t.getBoolean("warned");
            return l;
        }
    }

    public final List<War> wars = new ArrayList<>();
    /** Kingdom pair key -> game time the truce ends. */
    public final Map<String, Long> truces = new HashMap<>();
    public final List<Tribute> tributes = new ArrayList<>();
    /** Conquered cities by settlement id. Cities you founded have no entry: they are loyal. */
    public final Map<UUID, Fealty> loyalty = new HashMap<>();
    /** Names of kingdoms born of rebellion. */
    public final Map<UUID, String> names = new HashMap<>();
    public long nextStep;

    public boolean isEmpty() { return wars.isEmpty() && truces.isEmpty() && tributes.isEmpty() && loyalty.isEmpty() && names.isEmpty(); }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag w = new ListTag();
        wars.forEach(x -> w.add(x.save()));
        t.put("wars", w);
        CompoundTag tr = new CompoundTag();
        truces.forEach(tr::putLong);
        t.put("truces", tr);
        ListTag tb = new ListTag();
        tributes.forEach(x -> tb.add(x.save()));
        t.put("tributes", tb);
        CompoundTag lo = new CompoundTag();
        loyalty.forEach((k, v) -> lo.put(k.toString(), v.save()));
        t.put("loyalty", lo);
        CompoundTag nm = new CompoundTag();
        names.forEach((k, v) -> nm.putString(k.toString(), v));
        t.put("names", nm);
        t.putLong("nextStep", nextStep);
        return t;
    }

    public void load(CompoundTag t) {
        for (Tag x : t.getList("wars", Tag.TAG_COMPOUND)) try { wars.add(War.load((CompoundTag) x)); } catch (RuntimeException e) { LivingRealms.LOG.warn("Dropped a bad war record", e); }
        CompoundTag tr = t.getCompound("truces");
        for (String k : tr.getAllKeys()) truces.put(k, tr.getLong(k));
        for (Tag x : t.getList("tributes", Tag.TAG_COMPOUND)) try { tributes.add(Tribute.load((CompoundTag) x)); } catch (RuntimeException ignored) {}
        CompoundTag lo = t.getCompound("loyalty");
        for (String k : lo.getAllKeys()) try {
            UUID id = UUID.fromString(k);
            Fealty l = Fealty.load(lo.getCompound(k));
            loyalty.put(id, l);
            Loyalty.unrest(id, l);
        } catch (IllegalArgumentException ignored) {}
        CompoundTag nm = t.getCompound("names");
        for (String k : nm.getAllKeys()) try { names.put(UUID.fromString(k), nm.getString(k)); } catch (IllegalArgumentException ignored) {}
        nextStep = t.getLong("nextStep");
    }

    // ------------------------------------------------------------------ helpers

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    static String pair(UUID a, UUID b) {
        String x = a.toString(), y = b.toString();
        return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
    }

    @Nullable
    public War war(UUID x, UUID y) {
        for (War w : wars) if (w.involves(x) && w.involves(y) && !x.equals(y)) return w;
        return null;
    }

    public long truceUntil(UUID x, UUID y) { return truces.getOrDefault(pair(x, y), 0L); }

    @Nullable
    public Tribute tribute(UUID x, UUID y) {
        for (Tribute t : tributes) if (t.payer.equals(x) && t.payee.equals(y) || t.payer.equals(y) && t.payee.equals(x)) return t;
        return null;
    }

    static List<Settlement> cities(SettlementManager m, UUID kingdom) {
        List<Settlement> out = new ArrayList<>();
        for (Settlement s : m.all()) if (s.kingdomId().equals(kingdom)) out.add(s);
        return out;
    }

    @Nullable
    static Settlement seat(SettlementManager m, UUID kingdom) {
        Settlement any = null;
        for (Settlement s : m.all()) if (s.kingdomId().equals(kingdom)) { if (s.capital) return s; if (any == null) any = s; }
        return any;
    }

    static boolean isPlayer(SettlementManager m, UUID kingdom) {
        for (Settlement s : m.all()) if (kingdom.equals(s.owner)) return true;
        return false;
    }

    /** A kingdom's name as its rivals know it. */
    public static String name(SettlementManager m, UUID kingdom) {
        String known = m.state.names.get(kingdom);
        Settlement s = seat(m, kingdom);
        if (s == null) return known != null ? known : "a fallen kingdom";
        if (s.mirror()) return Realms.on() ? livingrealms.settlement.vx.VxBridge.name(s.vxFaction) : s.ownerName.isEmpty() ? s.name : s.ownerName;
        if (s.owner != null) return s.ownerName.isEmpty() ? "the realm of " + s.name : s.ownerName;
        return known != null ? known : "the Kingdom of " + s.name;
    }

    static void tellKingdom(MinecraftServer server, SettlementManager m, UUID kingdom, String message) {
        for (Settlement s : cities(m, kingdom)) s.log(message);
        ServerPlayer p = server.getPlayerList().getPlayer(kingdom);
        if (p != null) p.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
    }

    static int dayLength() { return Config.DAY_LENGTH.get(); }

    // ------------------------------------------------------------------ hooks

    /** Called by the server tick; advances the ledger once every {@link #STEP} ticks. */
    public static void tick(MinecraftServer server, SettlementManager m) {
        long now = server.overworld().getGameTime();
        Statecraft st = m.state;
        if (now < st.nextStep) return;
        st.nextStep = now + STEP;
        try {
            step(server, m, now, STEP / (double) dayLength());
        } catch (RuntimeException e) {
            LivingRealms.LOG.error("Living Realms statecraft step failed", e);
        }
    }

    /** One step of f days: wars wear on, tribute falls due, conquered cities settle or stir. */
    static void step(MinecraftServer server, SettlementManager m, long now, double f) {
        Wars.syncVillageExpansion(server, m, now);
        Wars.step(server, m, now, f);
        Wars.payTributes(server, m, now);
        m.state.truces.values().removeIf(until -> until <= now);
        Loyalty.step(server, m, now, f);
    }

    /** Raids are acts of war: a raid between two kingdoms opens (or feeds) their war. */
    static void raidStarted(MinecraftServer server, SettlementManager m, Raids.Raid raid, Settlement source, Settlement target) {
        try { Wars.raidStarted(server, m, raid, source, target); } catch (RuntimeException e) { LivingRealms.LOG.error("War ledger failed", e); }
    }

    static void raidEnded(MinecraftServer server, SettlementManager m, Raids.Raid raid, boolean captured) {
        try { Wars.raidEnded(server, m, raid, captured); } catch (RuntimeException e) { LivingRealms.LOG.error("War ledger failed", e); }
    }

    /** Why a raid may not start because of a truce or tribute, or null. */
    @Nullable
    static String raidBlocker(MinecraftServer server, SettlementManager m, Settlement source, Settlement target) {
        UUID a = source.kingdomId(), b = target.kingdomId();
        long now = server.overworld().getGameTime();
        long until = m.state.truceUntil(a, b);
        if (until > now) return "Truce between " + name(m, a) + " and " + name(m, b) + ": " + Math.max(1, (until - now) / 1200) + " min left";
        Tribute t = m.state.tribute(a, b);
        if (t != null) return name(m, t.payer) + " pays tribute to " + name(m, t.payee) + "; the peace holds while it does";
        return null;
    }

    public record Prior(UUID kingdom, String faction, String name) {}

    static Prior prior(SettlementManager m, Settlement s) {
        return new Prior(s.kingdomId(), s.vxFaction, name(m, s.kingdomId()));
    }

    /** A city changed hands (raid, siege, ceded or rebellion). */
    static void transferred(MinecraftServer server, SettlementManager m, Settlement winner, Settlement s, Prior prior) {
        try {
            Wars.captured(server, m, winner.kingdomId(), prior);
            Loyalty.transferred(server, m, s, prior);
        } catch (RuntimeException e) {
            LivingRealms.LOG.error("Statecraft bookkeeping after a conquest failed", e);
        }
    }

    /** Mood from unrest in a conquered city, with its reason. */
    static double mood(Settlement s, List<String> why) {
        Double u = Loyalty.UNREST_BY_CITY.get(s.id);
        if (u == null || u == 0 || s.isAi()) return 0;
        why.add("-" + Math.round(u) + " unrest");
        return -u;
    }

    // ------------------------------------------------------------------ City Manager

    /** Kingdom page: loyalty of this city and the provinces, wars and terms, tribute and truces. */
    public static CompoundTag view(ServerPlayer p, SettlementManager m, Settlement s) {
        CompoundTag t = new CompoundTag();
        long now = p.server.overworld().getGameTime();
        UUID k = s.kingdomId();
        boolean ruler = p.getUUID().equals(s.owner);
        t.putBoolean("ruler", ruler);
        t.putString("kingdom", name(m, k));
        t.putInt("cities", cities(m, k).size());
        if (!s.mirror()) t.put("loyalty", Loyalty.describe(p.server, m, s, now));
        ListTag provinces = new ListTag();
        for (Settlement c : cities(m, k)) {
            Fealty l = m.state.loyalty.get(c.id);
            if (l == null) continue;
            CompoundTag pt = new CompoundTag();
            pt.putString("name", c.name);
            pt.putInt("value", (int) Math.round(l.value));
            pt.putString("former", l.formerName);
            pt.putBoolean("here", c == s);
            provinces.add(pt);
        }
        t.put("provinces", provinces);
        ListTag wars = new ListTag();
        for (War w : m.state.wars) if (w.involves(k)) wars.add(Wars.describe(p.server, m, w, k, now));
        t.put("wars", wars);
        ListTag tribute = new ListTag();
        for (Tribute r : m.state.tributes) if (r.payer.equals(k) || r.payee.equals(k)) {
            CompoundTag rt = new CompoundTag();
            boolean paying = r.payer.equals(k);
            rt.putBoolean("paying", paying);
            rt.putString("other", name(m, paying ? r.payee : r.payer));
            rt.putInt("perDay", r.perDay);
            rt.putInt("daysLeft", r.daysLeft);
            rt.putInt("missed", r.missed);
            rt.putInt("dueIn", (int) Math.max(0, (r.nextDue - now) / 1200));
            tribute.add(rt);
        }
        t.put("tribute", tribute);
        ListTag truces = new ListTag();
        for (var e : m.state.truces.entrySet()) {
            if (e.getValue() <= now) continue;
            String[] ids = e.getKey().split("\\|");
            if (ids.length != 2) continue;
            String mine = k.toString();
            if (!ids[0].equals(mine) && !ids[1].equals(mine)) continue;
            CompoundTag tt = new CompoundTag();
            tt.putString("other", name(m, UUID.fromString(ids[0].equals(mine) ? ids[1] : ids[0])));
            tt.putInt("minutes", (int) Math.max(1, (e.getValue() - now) / 1200));
            truces.add(tt);
        }
        t.put("truces", truces);
        return t;
    }

    /** Kingdom page actions; only the ruler of the city may take them. */
    @Nullable
    static String handle(ServerPlayer player, SettlementManager m, Settlement s, String action, CompoundTag d) {
        if (s.mirror() || !player.getUUID().equals(s.owner)) return "Only this kingdom's ruler can do that";
        return switch (action) {
            case "scPropose" -> Wars.propose(player.server, m, s.kingdomId(), d.hasUUID("enemy") ? d.getUUID("enemy") : null,
                    Term.of(d.getString("term")), d.getInt("days"));
            case "scAccept" -> Wars.answer(player.server, m, s.kingdomId(), d.hasUUID("enemy") ? d.getUUID("enemy") : null, true);
            case "scReject" -> Wars.answer(player.server, m, s.kingdomId(), d.hasUUID("enemy") ? d.getUUID("enemy") : null, false);
            case "scPacify" -> Loyalty.pacify(player.server, m, s);
            default -> "Unknown action";
        };
    }

    // test access
    static ListTag strings(Collection<String> list) {
        ListTag l = new ListTag();
        list.forEach(x -> l.add(StringTag.valueOf(x)));
        return l;
    }
}
