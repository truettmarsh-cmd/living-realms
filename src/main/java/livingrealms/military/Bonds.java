package livingrealms.military;

import livingrealms.LivingRealms;
import livingrealms.chronicle.Chronicle;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Brothers-in-arms. Two recruits of the same owner who fight within 12 blocks of each other and both live through the
 * battle earn bond points (at most 3 a battle and 6 a day per pair). Bonds give small buffs while the two fight within
 * 10 blocks, from their own budget (BOND_CAP damage, BOND_DEFENCE_CAP reduction) on top of the skill and path budgets.
 * When a bonded recruit dies its partners grieve, then seek vengeance on the killer's kind. Works for any owned recruit;
 * nothing depends on a town, except that a town soldier's bonds and falls are also written in its town's chronicle.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class Bonds {
    public enum Level {
        NONE(0, "Forming", 0, 0), COMRADES(10, "Comrades", .03, 5), SWORN(30, "Sworn Kin", .06, 10), BLOOD_OATH(60, "Blood Oath", .10, 15);
        public final int points;
        public final String label;
        public final double damage;
        /** Morale a partner loses when this recruit falls. */
        public final int grief;
        Level(int points, String label, double damage, int grief) { this.points = points; this.label = label; this.damage = damage; this.grief = grief; }

        public static Level of(int points) {
            Level out = NONE;
            for (Level l : values()) if (points >= l.points) out = l;
            return out;
        }
        @Nullable public Level next() { return this == BLOOD_OATH ? null : values()[ordinal() + 1]; }
        public String effect() {
            return switch (this) {
                case NONE -> "No effect until they are Comrades";
                case COMRADES -> "+3% damage while fighting within 10 blocks of each other";
                case SWORN -> "+6% damage within 10 blocks; a hard hit on one gives the other a burst of Strength";
                case BLOOD_OATH -> "+10% damage and 10% less damage taken within 10 blocks; avenges hard hits with Strength";
            };
        }
    }

    public static final int MAX_BONDS = 3, MAX_BLOOD_OATHS = 1;
    public static final double FORM_RANGE = 12, AID_RANGE = 10;
    public static final int PER_BATTLE = 3, PER_DAY = 6, PULSE_TICKS = 100, ENGAGED_TICKS = 200, BATTLE_QUIET = 600;
    public static final double BOND_CAP = .15, BOND_DEFENCE_CAP = .10, BLOOD_OATH_DEFENCE = .10, VENGEANCE = .08, GRIEF_PENALTY = .10;
    public static final int GRIEF_TICKS = 3600, VENGEANCE_TICKS = 12000, AVENGE_TICKS = 100, AVENGE_COOLDOWN = 600;
    /** A hit taking at least this share of a recruit's max health is a hard hit. */
    public static final float HARD_HIT = .25f;
    public static final long FORMING_FORGOTTEN = 10 * 24000L;

    /** In memory only: when each recruit last fought, and each pair's battle in progress. */
    private static final Map<UUID, Long> ENGAGED = new HashMap<>(), SCANNED = new HashMap<>();
    private static final Map<String, Battle> BATTLES = new HashMap<>();
    private static final class Battle { UUID a, b; int score; long pulse, last; }

    // ------------------------------------------------------------------ forming bonds

    static boolean active(Entity e) { return RecruitsBridge.isRecruit(e) && !RecruitsBridge.isWorker(e) && RecruitsBridge.owner(e) != null; }

    /** True when a recruit hitting (or hit by) this entity is in a fight: not its owner and not one of the owner's recruits. */
    static boolean foe(Entity recruit, Entity other) {
        UUID owner = RecruitsBridge.owner(recruit);
        if (other == recruit || owner == null || owner.equals(other.getUUID())) return false;
        return !(RecruitsBridge.isRecruit(other) && owner.equals(RecruitsBridge.owner(other)));
    }

    /** The recruit is fighting now: pairs it up with its owner's recruits fighting within 12 blocks. */
    public static void engage(LivingEntity e, long time) {
        if (!active(e) || !(e.level() instanceof ServerLevel level)) return;
        UUID id = e.getUUID(), owner = RecruitsBridge.owner(e);
        BondData data = BondData.get(level.getServer());
        BondData.Member m = data.member(id, true);
        m.name = e.getName().getString(); m.owner = owner; m.seen = time;
        data.setDirty();
        ENGAGED.put(id, time);
        Long scanned = SCANNED.get(id);
        if (scanned != null && time - scanned >= 0 && time - scanned < 40) return;
        SCANNED.put(id, time);
        // only recruits that fought in the last 10 seconds can pair up, so look those up rather than scan the area
        for (Map.Entry<UUID, Long> fought : ENGAGED.entrySet()) {
            if (fought.getKey().equals(id) || time - fought.getValue() > ENGAGED_TICKS || time < fought.getValue()) continue;
            Entity other = level.getEntity(fought.getKey());
            if (!(other instanceof LivingEntity) || !other.isAlive() || !active(other) || !owner.equals(RecruitsBridge.owner(other))
                    || other.distanceToSqr(e) > FORM_RANGE * FORM_RANGE) continue;
            Battle b = BATTLES.computeIfAbsent(BondData.key(id, other.getUUID()), k -> new Battle());
            b.a = id; b.b = other.getUUID(); b.last = time;
            if (b.score == 0 || time - b.pulse >= PULSE_TICKS) { b.pulse = time; b.score = Math.min(PER_BATTLE, b.score + 1); }
        }
    }

    /** Battles where the pair hasn't fought together for 30 seconds are over: both lived, so the bond grows. */
    public static void settle(MinecraftServer server, long time) {
        if (BATTLES.isEmpty()) return;
        BondData data = BondData.get(server);
        for (Iterator<Battle> it = BATTLES.values().iterator(); it.hasNext(); ) {
            Battle b = it.next();
            if (time - b.last <= BATTLE_QUIET) continue;
            it.remove();
            award(server, data, b.a, b.b, b.score, time);
        }
    }

    /** How many points this pair may hold: a fourth bond or a second Blood Oath can't form. */
    static int ceiling(BondData data, BondData.Pair p) {
        Level now = p.level();
        if (now == Level.NONE && (bondCount(data, p.a, p) >= MAX_BONDS || bondCount(data, p.b, p) >= MAX_BONDS)) return Level.COMRADES.points - 1;
        if (now != Level.BLOOD_OATH && (oaths(data, p.a, p) >= MAX_BLOOD_OATHS || oaths(data, p.b, p) >= MAX_BLOOD_OATHS)) return Level.BLOOD_OATH.points - 1;
        return Level.BLOOD_OATH.points;
    }

    static int bondCount(BondData data, UUID id, @Nullable BondData.Pair except) {
        int n = 0;
        for (BondData.Pair p : data.pairsOf(id)) if (p != except && p.level() != Level.NONE) n++;
        return n;
    }

    static int oaths(BondData data, UUID id, @Nullable BondData.Pair except) {
        int n = 0;
        for (BondData.Pair p : data.pairsOf(id)) if (p != except && p.level() == Level.BLOOD_OATH) n++;
        return n;
    }

    /** Adds a battle's points to a pair, within the daily limit and the bond caps. Returns the points gained. */
    public static int award(MinecraftServer server, BondData data, UUID a, UUID b, int points, long time) {
        if (points <= 0 || a.equals(b)) return 0;
        BondData.Pair p = data.pair(a, b, true);
        long day = time / 24000;
        if (p.day != day) { p.day = day; p.today = 0; }
        int gain = Math.max(0, Math.min(points, PER_DAY - p.today));
        int before = p.points;
        p.points = Math.max(before, Math.min(ceiling(data, p), before + gain));
        p.today += gain;
        p.touched = time;
        data.setDirty();
        Level was = Level.of(before), now = p.level();
        if (now.ordinal() > was.ordinal()) announce(server, data, p, now);
        return p.points - before;
    }

    static String name(BondData data, UUID id) {
        BondData.Member m = data.member(id);
        return m == null || m.name.isEmpty() ? "a recruit" : m.name;
    }

    static void announce(MinecraftServer server, BondData data, BondData.Pair p, Level level) {
        String a = name(data, p.a), b = name(data, p.b);
        String text = switch (level) {
            case COMRADES -> a + " and " + b + " are now Comrades";
            case SWORN -> a + " and " + b + " have become Sworn Kin";
            default -> a + " and " + b + " swore a Blood Oath";
        };
        tell(server, data, p.a, "⚔ " + text + ".");
        if (level.ordinal() >= Level.SWORN.ordinal()) {
            Settlement town = townOf(server, p.a);
            if (town == null) town = townOf(server, p.b);
            if (town != null) Chronicle.record(town, Chronicle.Kind.NOTE, level == Level.SWORN
                    ? a + " and " + b + " fought side by side so often that they swore themselves kin."
                    : a + " and " + b + " swore a Blood Oath: where one stands, the other will stand.");
        }
    }

    static void tell(MinecraftServer server, BondData data, UUID recruit, String text) {
        BondData.Member m = data.member(recruit);
        ServerPlayer owner = m == null || m.owner == null ? null : server.getPlayerList().getPlayer(m.owner);
        if (owner != null) owner.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
    }

    @Nullable
    static Settlement townOf(MinecraftServer server, UUID recruit) {
        for (Settlement s : SettlementManager.get(server).all())
            for (Settlement.Soldier so : s.soldiers) if (recruit.equals(so.entity)) return s;
        return null;
    }

    // ------------------------------------------------------------------ fighting together

    /** The partner, if it is loaded, alive, still the same owner's and within 10 blocks. */
    @Nullable
    static LivingEntity beside(LivingEntity e, UUID partner) {
        if (!(e.level() instanceof ServerLevel level)) return null;
        Entity o = level.getEntity(partner);
        UUID owner = RecruitsBridge.owner(e);
        if (!(o instanceof LivingEntity le) || !le.isAlive() || owner == null || !owner.equals(RecruitsBridge.owner(o))
                || o.distanceToSqr(e) > AID_RANGE * AID_RANGE) return null;
        return le;
    }

    /** The strongest bond whose partner fights beside this recruit now. */
    public static Level strongestBeside(BondData data, LivingEntity e) {
        Level best = Level.NONE;
        for (BondData.Pair p : data.pairsOf(e.getUUID())) {
            Level l = p.level();
            if (l.ordinal() > best.ordinal() && beside(e, p.other(e.getUUID())) != null) best = l;
        }
        return best;
    }

    static String typeId(Entity e) { return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())); }

    /** Damage bonus for a recruit's hit: its best bond beside it plus vengeance, capped; grief takes 10% off after the cap. */
    public static double attack(BondData data, LivingEntity attacker, LivingEntity victim, long time) {
        BondData.Member m = data.member(attacker.getUUID());
        if (m == null && data.pairsOf(attacker.getUUID()).isEmpty()) return 0;
        double bonus = strongestBeside(data, attacker).damage;
        if (m != null && time >= m.griefUntil && time < m.vengeanceUntil && m.vengeance.equals(typeId(victim))) bonus += VENGEANCE;
        bonus = Math.min(BOND_CAP, bonus);
        if (m != null && time < m.griefUntil) bonus -= GRIEF_PENALTY;
        return bonus;
    }

    /** Damage reduction for a recruit with its Blood Oath partner beside it. */
    public static double defend(BondData data, LivingEntity victim) {
        return Math.min(BOND_DEFENCE_CAP, strongestBeside(data, victim) == Level.BLOOD_OATH ? BLOOD_OATH_DEFENCE : 0);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void hurt(LivingHurtEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0 || !(event.getEntity().level() instanceof ServerLevel level)) return;
        LivingEntity victim = event.getEntity();
        var source = event.getSource();
        long time = RecruitCombat.now(victim);
        BondData data = BondData.get(level.getServer());
        double factor = 1;
        if (source.getEntity() instanceof LivingEntity attacker && attacker != victim && active(attacker) && foe(attacker, victim)) {
            engage(attacker, time);
            factor *= 1 + attack(data, attacker, victim, time);
        }
        if (active(victim) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            if (source.getEntity() instanceof LivingEntity attacker && foe(victim, attacker)) engage(victim, time);
            factor *= 1 - defend(data, victim);
        }
        if (factor != 1) event.setAmount((float) (event.getAmount() * factor));
    }

    /** A hard hit on a Sworn Kin or Blood Oath partner: the other gets Strength I for 5 seconds (30-second cooldown). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void struck(LivingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (event.isCanceled() || !(victim.level() instanceof ServerLevel level) || !active(victim)
                || event.getAmount() < victim.getMaxHealth() * HARD_HIT) return;
        avenge(BondData.get(level.getServer()), victim, RecruitCombat.now(victim));
    }

    /** Returns how many partners were roused. */
    public static int avenge(BondData data, LivingEntity victim, long time) {
        int n = 0;
        for (BondData.Pair p : data.pairsOf(victim.getUUID())) {
            if (p.level().ordinal() < Level.SWORN.ordinal()) continue;
            LivingEntity partner = beside(victim, p.other(victim.getUUID()));
            if (partner == null) continue;
            BondData.Member m = data.member(partner.getUUID(), true);
            if (time < m.avengeReady) continue;
            m.avengeReady = time + AVENGE_COOLDOWN;
            partner.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, AVENGE_TICKS, 0));
            data.setDirty();
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ loss

    /** Looked up before ServerEvents forgets the soldier, used after the death is certain. */
    private static UUID dyingId;
    @Nullable private static Settlement dyingTown;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void dying(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !RecruitsBridge.isRecruit(event.getEntity())) return;
        dyingId = event.getEntity().getUUID();
        dyingTown = townOf(level.getServer(), dyingId);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void died(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel level) || !RecruitsBridge.isRecruit(event.getEntity())) return;
        LivingEntity dead = event.getEntity();
        Settlement town = dead.getUUID().equals(dyingId) ? dyingTown : null;
        dyingId = null; dyingTown = null;
        fell(level.getServer(), dead, event.getSource().getEntity(), town, RecruitCombat.now(dead));
    }

    /** A recruit died: its partners grieve, and the bonds and the recruit are forgotten. */
    public static void fell(MinecraftServer server, LivingEntity dead, @Nullable Entity killer, @Nullable Settlement town, long time) {
        UUID id = dead.getUUID();
        ENGAGED.remove(id); SCANNED.remove(id);
        BATTLES.values().removeIf(b -> b.a.equals(id) || b.b.equals(id));
        BondData data = BondData.get(server);
        String name = dead.getName().getString();
        for (BondData.Pair p : data.bondsOf(id)) {
            UUID partner = p.other(id);
            Level level = p.level();
            BondData.Member m = data.member(partner, true);
            // the deeper loss wins while an earlier grief is still fresh
            if (time >= m.griefUntil || level.ordinal() >= m.griefLevel) {
                m.griefUntil = time + GRIEF_TICKS;
                m.griefLevel = level.ordinal();
                m.mourned = name;
                if (killer != null && killer != dead && foe(dead, killer) && !(m.owner != null && m.owner.equals(killer.getUUID()))) {
                    m.vengeance = typeId(killer);
                    m.vengeanceLabel = killer.getType().getDescription().getString();
                    m.vengeanceUntil = m.griefUntil + VENGEANCE_TICKS;
                } else { m.vengeance = ""; m.vengeanceLabel = ""; m.vengeanceUntil = 0; }
            }
            Entity body = SoldierProgression.findEntity(server, partner);
            if (body != null && body.isAlive()) RecruitsBridge.addMorale(body, -level.grief);
            String who = name(data, partner);
            tell(server, data, partner, "✝ " + who + " grieves for " + name + (level == Level.COMRADES ? ", a comrade" : level == Level.SWORN ? ", their sworn kin" : ", their blood-oath partner")
                    + (m.vengeance.isEmpty() ? "." : ", and will seek vengeance on the " + m.vengeanceLabel + "."));
            if (level.ordinal() >= Level.SWORN.ordinal()) {
                Settlement t = town != null ? town : townOf(server, partner);
                if (t != null) Chronicle.record(t, Chronicle.Kind.NOTE, name + " fell" + (m.vengeance.isEmpty() ? "" : " to a " + m.vengeanceLabel)
                        + ". " + who + ", " + (level == Level.SWORN ? "their sworn kin" : "bound to them by a Blood Oath") + ", "
                        + (m.vengeance.isEmpty() ? "mourned them." : "swore vengeance."));
            }
        }
        data.forget(id);
    }

    /** A recruit was replaced by a new entity (a promotion): bonds follow it. */
    public static void moved(MinecraftServer server, UUID from, UUID to) {
        BondData.get(server).move(from, to);
        Long e = ENGAGED.remove(from);
        if (e != null) ENGAGED.put(to, e);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 0) return;
        long time = event.getServer().overworld().getGameTime();
        settle(event.getServer(), time);
        if (event.getServer().getTickCount() % 6000 == 0) sweep(event.getServer(), time);
    }

    /** Drops in-memory marks for recruits not fighting, and forming pairs nobody has fought in for 10 days. */
    static void sweep(MinecraftServer server, long time) {
        ENGAGED.values().removeIf(t -> time - t > 6000);
        SCANNED.values().removeIf(t -> time - t > 6000);
        BondData data = BondData.get(server);
        for (BondData.Pair p : new ArrayList<>(data.allPairs()))
            if (p.level() == Level.NONE && time - p.touched > FORMING_FORGOTTEN) data.removePair(p);
        data.members.entrySet().removeIf(e -> data.pairsOf(e.getKey()).isEmpty() && time > e.getValue().vengeanceUntil && time > e.getValue().griefUntil);
    }

    // ------------------------------------------------------------------ Recruit Command

    /** The "bonds" part of the Recruit Command packet: bonds strongest first, a forming tie, and grief or vengeance. */
    public static CompoundTag view(MinecraftServer server, Entity e) {
        CompoundTag t = new CompoundTag();
        BondData data = BondData.get(server);
        UUID id = e.getUUID();
        long time = server.overworld().getGameTime();
        List<BondData.Pair> bonds = data.bondsOf(id);
        ListTag list = new ListTag();
        for (BondData.Pair p : bonds) list.add(row(data, e, p, false));
        if (bonds.size() < MAX_BONDS) {
            BondData.Pair forming = null;
            for (BondData.Pair p : data.pairsOf(id))
                if (p.level() == Level.NONE && p.points > 0 && (forming == null || p.points > forming.points)) forming = p;
            if (forming != null) list.add(row(data, e, forming, true));
        }
        t.put("list", list);
        t.putInt("count", bonds.size());
        t.putInt("max", MAX_BONDS);
        BondData.Member m = data.member(id);
        if (m != null && time < m.griefUntil) {
            t.putString("status", "Grieving for " + m.mourned + " · " + minutes(m.griefUntil - time) + " left");
            t.putString("statusTip", "−" + (int) Math.round(GRIEF_PENALTY * 100) + "% damage while grieving"
                    + (m.vengeance.isEmpty() ? "" : "; then vengeance on the " + m.vengeanceLabel));
            t.putBoolean("grief", true);
        } else if (m != null && time < m.vengeanceUntil && !m.vengeance.isEmpty()) {
            t.putString("status", "Vengeance on the " + m.vengeanceLabel + " · " + minutes(m.vengeanceUntil - time) + " left");
            t.putString("statusTip", "+" + (int) Math.round(VENGEANCE * 100) + "% damage against them, for " + m.mourned);
        }
        return t;
    }

    static CompoundTag row(BondData data, Entity e, BondData.Pair p, boolean forming) {
        UUID partner = p.other(e.getUUID());
        Level level = p.level(), next = level.next();
        CompoundTag r = new CompoundTag();
        String name = name(data, partner);
        if (e.level() instanceof ServerLevel sl && sl.getEntity(partner) != null) name = sl.getEntity(partner).getName().getString();
        r.putString("name", name);
        r.putInt("level", level.ordinal());
        r.putString("label", level.label);
        r.putString("effect", level.effect());
        r.putInt("points", p.points);
        r.putInt("from", level.points);
        r.putInt("to", next == null ? 0 : next.points);
        r.putString("nextLabel", next == null ? "" : next.label);
        r.putBoolean("forming", forming);
        r.putBoolean("near", e instanceof LivingEntity le && beside(le, partner) != null);
        int ceiling = ceiling(data, p);
        if (next != null && ceiling < next.points)
            r.putString("held", level == Level.NONE ? "One of them already has " + MAX_BONDS + " bonds" : "One of them already has a Blood Oath");
        return r;
    }

    static String minutes(long ticks) {
        long s = Math.max(0, ticks) / 20;
        return s >= 60 ? (s + 59) / 60 + "m" : s + "s";
    }

    /** For the self-test: forget fights in progress. */
    static void resetBattles() { ENGAGED.clear(); SCANNED.clear(); BATTLES.clear(); }

    private Bonds() {}
}
