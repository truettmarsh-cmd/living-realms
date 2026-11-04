package livingrealms.wonders;

import livingrealms.LivingRealms;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.chronicle.Chronicle;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierProgression;
import livingrealms.military.SoldierSkills;
import livingrealms.research.ResearchTree;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Tier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Wonders (docs/wonders.md): the rules for raising them and every effect they have. A wonder is a standing plot of its
 * building type in any city of a kingdom, so it changes hands with its city when a capital falls (Kingdoms.transfer moves
 * the plots) and its effects follow the new ruler at once. Other features ask {@link #has(UUID, Wonder)}.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class Wonders {
    /** The research every wonder needs first. */
    public static final String RESEARCH = "wonders_of_the_realm";

    // Grand Cathedral
    public static final int CATHEDRAL_MOOD = 10;
    /** Factor on the chance of an outbreak, on its spread and on recovery from it. */
    public static final double CATHEDRAL_OUTBREAK = .4, CATHEDRAL_SPREAD = .7, CATHEDRAL_RECOVERY = 1.5;
    public static final double SPARE_CHANCE = .5;
    /** Chance a falling recruit is spared, while the day's blessing is unspent. Tests may raise it. */
    public static double spareChance = SPARE_CHANCE;
    static final String SPARED_DAY = "wonder:sparedDay";
    // Royal Academy
    public static final double ACADEMY_RESEARCH = 1.5;
    public static final double BANK_CAP = 2000, BANK_POUR = 2;
    // Grand Forge
    public static final double FORGE_SPEED = 1.5, FORGE_COST = .75;
    // Grand Exchange
    public static final double EXCHANGE_CARAVAN = 1.5;
    public static final int EXCHANGE_TRICKLE = 6;
    // Citadel of Heroes
    public static final int CITADEL_LEVELS = 2, CITADEL_SKILL_XP = SoldierSkills.XP_PER_POINT;
    public static final double CITADEL_GARRISON = 1.25;
    public static final int CITADEL_GARRISON_MIN = 4;

    // ------------------------------------------------------------------ who holds what

    @Nullable
    public static Wonder of(@Nullable BuildingType t) { return t == null ? null : Wonder.byType(t.id); }

    public static boolean is(@Nullable BuildingType t) { return of(t) != null; }

    @Nullable
    static SettlementManager manager() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : SettlementManager.get(server);
    }

    /** Whether the kingdom (a player's UUID, or an NPC kingdom's id) holds the standing wonder. The hook for other features. */
    public static boolean has(UUID kingdom, Wonder w) {
        SettlementManager m = manager();
        return m != null && has(m, kingdom, w);
    }

    public static boolean has(SettlementManager m, UUID kingdom, Wonder w) { return seat(m, kingdom, w) != null; }

    /** Whether the town's kingdom holds the wonder; the town's own plots count even if it is not in the world's manager. */
    public static boolean has(Settlement s, Wonder w) {
        if (stands(s, w) != null) return true;
        SettlementManager m = manager();
        return m != null && has(m, s.kingdomId(), w);
    }

    /** The city of the kingdom where the wonder stands, or null. */
    @Nullable
    public static Settlement seat(SettlementManager m, UUID kingdom, Wonder w) {
        for (Settlement s : m.all()) if (s.kingdomId().equals(kingdom) && stands(s, w) != null) return s;
        return null;
    }

    /** The wonder's standing plot in this town, or null. */
    @Nullable
    public static Plot stands(Settlement s, Wonder w) {
        for (Plot p : s.plots) if (p.typeId.equals(w.id()) && p.standing()) return p;
        return null;
    }

    /** Any plot of the wonder in this town but one being torn down: planned, building or standing. */
    @Nullable
    static Plot project(Settlement s, Wonder w) {
        for (Plot p : s.plots) if (p.typeId.equals(w.id()) && p.state != Plot.State.DEMOLISHING) return p;
        return null;
    }

    public static int count(Collection<Settlement> realm) {
        int n = 0;
        for (Wonder w : Wonder.values()) for (Settlement s : realm) if (stands(s, w) != null) { n++; break; }
        return n;
    }

    /** Settlement.cannotBuild's wonder rules: a Capital, the research, and one per kingdom. Null for other buildings. */
    @Nullable
    public static String cannotBuild(Settlement s, BuildingType t) {
        Wonder w = of(t);
        if (w == null) return null;
        if (!s.capital || s.tier != Tier.CAPITAL) return "Wonders rise only in a Capital";
        if (!s.has(RESEARCH)) return "Research " + researchName(RESEARCH);
        if (!s.has(w.research())) return "Research " + researchName(w.research());
        if (project(s, w) != null) return "This capital already has its " + w.label;
        SettlementManager m = manager();
        if (m != null) for (Settlement o : m.all()) {
            if (o == s || !o.kingdomId().equals(s.kingdomId()) || project(o, w) == null) continue;
            return "Your kingdom has its " + w.label + " in " + o.name;
        }
        return null;
    }

    static String researchName(String id) {
        var n = ResearchTree.get(id);
        return n == null ? id : n.name();
    }

    // ------------------------------------------------------------------ effects

    /** Grand Cathedral: a mood reason for every city of the kingdom. */
    public static double mood(Settlement s, List<String> why) {
        if (!has(s, Wonder.CATHEDRAL)) return 0;
        why.add("+" + CATHEDRAL_MOOD + " Grand Cathedral");
        return CATHEDRAL_MOOD;
    }

    /** Grand Cathedral: factor on outbreak chance (listed among the sickness factors). */
    public static double outbreak(Settlement s) { return has(s, Wonder.CATHEDRAL) ? CATHEDRAL_OUTBREAK : 1; }

    public static double spread(Settlement s) { return has(s, Wonder.CATHEDRAL) ? CATHEDRAL_SPREAD : 1; }

    public static double recovery(Settlement s) { return has(s, Wonder.CATHEDRAL) ? CATHEDRAL_RECOVERY : 1; }

    /** Royal Academy: research rate factor. */
    public static double research(Settlement s) { return has(s, Wonder.ACADEMY) ? ACADEMY_RESEARCH : 1; }

    /** Royal Academy: most study points a town may save while it has no project. */
    public static double bankCap(Settlement s) { return has(s, Wonder.ACADEMY) ? BANK_CAP * 2 : BANK_CAP; }

    /** Royal Academy: saved points that flow into a project per step, as a multiple of the step's own points. */
    public static double bankPour(Settlement s) { return has(s, Wonder.ACADEMY) ? BANK_POUR * 2 : BANK_POUR; }

    /** Grand Forge: smiths' work factor. */
    public static double forgeSpeed(Settlement s) { return has(s, Wonder.FORGE) ? FORGE_SPEED : 1; }

    /** Grand Forge: an order's materials, discounted (never below one of each). */
    public static void forgeCost(Settlement s, Map<String, Integer> total) {
        if (has(s, Wonder.FORGE)) total.replaceAll((k, v) -> Math.max(1, (int) Math.ceil(v * FORGE_COST)));
    }

    /** Grand Exchange: what a caravan's sale is worth to the sender. */
    public static double caravan(@Nullable Settlement from) { return from != null && has(from, Wonder.EXCHANGE) ? EXCHANGE_CARAVAN : 1; }

    /** Grand Exchange: emeralds a day for each city's treasury. */
    public static double trickle(Settlement s) { return has(s, Wonder.EXCHANGE) ? EXCHANGE_TRICKLE : 0; }

    /** Citadel of Heroes: the garrison cap after research bonuses. */
    public static int garrison(Settlement s, int cap) {
        if (!has(s, Wonder.CITADEL)) return cap;
        return Math.max(cap + CITADEL_GARRISON_MIN, (int) Math.round(cap * CITADEL_GARRISON));
    }

    /** Citadel of Heroes: a recruit who finished training in the kingdom starts higher. */
    public static void mustered(Settlement s, Entity e, Settlement.Soldier so) {
        if (!has(s, Wonder.CITADEL)) return;
        RecruitsBridge.setLevel(e, RecruitsBridge.level(e) + CITADEL_LEVELS);
        so.skillXp = Math.min(SoldierSkills.MAX_POINTS * SoldierSkills.XP_PER_POINT, so.skillXp + CITADEL_SKILL_XP);
        so.level = RecruitsBridge.level(e);
    }

    // ------------------------------------------------------------------ the Grand Cathedral spares a recruit

    /**
     * A killing blow on a recruit of a cathedral kingdom inside one of its cities: once a day, by chance, it is left on
     * 1 HP and carried to the cathedral. LOW, beside Undying Rage and the Saint, so battle reports and bonds see what lands.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void lethal(LivingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (event.isCanceled() || event.getAmount() < victim.getHealth() || !(victim.level() instanceof ServerLevel level)
                || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY) || !RecruitsBridge.isRecruit(victim)) return;
        if (spare(level, victim, event.getSource(), level.random)) event.setAmount(Math.max(0, victim.getHealth() - 1));
    }

    /** Spends the day's blessing on this recruit if it may be spared; true when it was. */
    static boolean spare(ServerLevel level, LivingEntity victim, net.minecraft.world.damagesource.DamageSource source, RandomSource rand) {
        MinecraftServer server = level.getServer();
        var r = SoldierProgression.record(server, victim);
        if (r == null) return false;
        SettlementManager m = SettlementManager.get(server);
        Settlement seat = seat(m, r.settlement().kingdomId(), Wonder.CATHEDRAL);
        if (seat == null) return false;
        Settlement where = m.at(level.dimension(), victim.blockPosition());
        if (where == null || !where.kingdomId().equals(seat.kingdomId())) return false;
        long day = Chronicle.dayOf(server.overworld().getGameTime());
        if (seat.accum.getOrDefault(SPARED_DAY, -1.0) >= day) return false;
        if (rand.nextDouble() >= spareChance) return false;
        seat.accum.put(SPARED_DAY, (double) day);
        Plot cathedral = stands(seat, Wonder.CATHEDRAL);
        if (cathedral != null && victim.level().dimension().equals(seat.dim)) {
            BlockPos at = cathedral.center();
            victim.stopRiding();
            victim.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        }
        victim.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 4, true, true));
        victim.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1, true, true));
        String msg = r.soldier().name + " fell, and was spared: carried to the Grand Cathedral of " + seat.name + " with one breath left";
        r.settlement().log(msg);
        r.settlement().dirty = true;
        seat.dirty = true;
        livingrealms.battle.BattleReports.saved(victim, source, "Spared by the Grand Cathedral");
        if (r.settlement().owner != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(r.settlement().owner);
            if (p != null) p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD));
        }
        return true;
    }

    /** Whether today's blessing is still unspent. */
    public static boolean spareReady(Settlement seat, long day) { return seat.accum.getOrDefault(SPARED_DAY, -1.0) < day; }

    // ------------------------------------------------------------------ completion

    /**
     * Called by the chronicle when it first sees a building standing. For a wonder: a LEGEND entry in the town's chronicle,
     * the same news in every other city of the kingdom, and a server-wide announcement. Returns false for other buildings.
     */
    public static boolean completed(Settlement s, BuildingType t, long day) {
        Wonder w = of(t);
        if (w == null) return false;
        String ruler = s.isAi() ? "its rulers" : s.ownerName;
        String text = "The " + w.label + " of " + s.name + " was completed, a wonder of the realm. "
                + (ruler.isEmpty() ? "" : "It was raised under " + ruler + "; ") + "people came from every province to see it.";
        Chronicle.recordText(s, day, Chronicle.Kind.LEGEND, text);
        SettlementManager m = manager();
        if (m != null) for (Settlement o : m.all())
            if (o != s && o.kingdomId().equals(s.kingdomId())) Chronicle.recordText(o, day, Chronicle.Kind.LEGEND, "Word came from " + s.name + ": the " + w.label + " is finished.");
        s.log("The " + w.label + " is complete: " + w.effect);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) server.getPlayerList().broadcastSystemMessage(Component.literal("A wonder of the world: the " + w.label + " of " + s.name
                + (s.isAi() || s.ownerName.isEmpty() ? "" : " (" + s.ownerName + ")") + " is complete!").withStyle(ChatFormatting.GOLD), false);
        return true;
    }

    // ------------------------------------------------------------------ City Manager

    /**
     * Kingdom page: {town, style, capital, mine, built, wonders:[{id, kind, name, effect, state, where, progress, reason, here}]}.
     * State is BUILT, BUILDING, AVAILABLE or LOCKED; reason explains a lock.
     */
    public static CompoundTag view(SettlementManager m, Settlement s, boolean ruler) {
        CompoundTag t = new CompoundTag();
        UUID k = s.kingdomId();
        Settlement capital = null;
        for (Settlement o : m.all()) if (o.kingdomId().equals(k) && o.capital) capital = o;
        if (capital == null) capital = s;
        t.putUUID("town", capital.id);
        t.putString("style", capital.style);
        t.putString("capitalName", capital.name);
        t.putBoolean("here", capital == s);
        t.putBoolean("ruler", ruler);
        ListTag l = new ListTag();
        int built = 0;
        for (Wonder w : Wonder.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", w.id());
            c.putString("kind", w.kind);
            c.putString("name", w.label);
            c.putString("effect", w.effect);
            c.putString("icon", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(w.icon).toString());
            Settlement at = seat(m, k, w);
            Plot p = null;
            if (at != null) p = stands(at, w);
            else for (Settlement o : m.all()) if (o.kingdomId().equals(k) && (p = project(o, w)) != null) { at = o; break; }
            if (at != null && p != null && p.standing()) {
                c.putString("state", "BUILT"); c.putString("where", at.name); built++;
            } else if (at != null && p != null) {
                c.putString("state", "BUILDING"); c.putString("where", at.name); c.putFloat("progress", p.progress());
                c.putString("status", p.status == null ? "" : p.status);
            } else {
                BuildingType bt = BuildingCatalog.get(w.id());
                String why = bt == null ? "Blueprint unavailable" : capital.cannotBuild(bt);
                c.putString("state", why == null ? "AVAILABLE" : "LOCKED");
                c.putString("reason", why == null ? "" : why);
                if (bt != null) c.putString("size", bt.w + "×" + bt.d + "×" + bt.h + " · " + bt.blocks + " blocks");
            }
            l.add(c);
        }
        t.put("wonders", l);
        t.putInt("built", built);
        return t;
    }

    private Wonders() {}
}
