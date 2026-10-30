package livingrealms.hardship;

import livingrealms.Config;
import livingrealms.LivingRealms;
import livingrealms.building.Category;
import livingrealms.building.Procedural;
import livingrealms.chronicle.Chronicle;
import livingrealms.entity.SettlerEntity;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Raids;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Town hardships (docs/hardships.md): sickness, bad harvests and the hunger that follows, and rats in the stores.
 * Each town rolls for them once a minute (staggered, like fires). Their effects reach the rest of the mod through a few
 * small hooks: {@link #workFactor} (builders), {@link #output} (workers and farms), {@link #mood}, {@link #tradeOutput} and
 * {@link #wants} (trade), {@link #routeBlock} and {@link #caravanArrived} (caravans), {@link #bounty} (the bounty board).
 */
public final class Hardships {
    public static final String APOTHECARY = "livingrealms:apothecary", RESEARCH = "herb_lore";
    /** Ticks between a town's rolls; ticks between the sick coughing in the streets. */
    public static final int CHECK = 1200, SHOW = 40;
    /** Remedies the sick are given from the stockpile (plain items: the stockpile keeps no potion NBT). */
    public static final List<Item> MEDICINE = List.of(Items.HONEY_BOTTLE, Items.GOLDEN_APPLE, Items.GOLDEN_CARROT, Items.GLISTERING_MELON_SLICE);
    /** Grain and bread the rats go for (and what counts as food in store for them). */
    public static final List<Item> GRAIN = List.of(Items.WHEAT, Items.BREAD, Items.POTATO, Items.CARROT, Items.BEETROOT);

    public enum Harvest {
        NONE("", 1), DROUGHT("drought", .3), BLIGHT("blight", .4), COLD_SNAP("cold snap", .5);
        public final String label;
        /** Share of farm output left before granaries and storehouses soften it. */
        public final double left;
        Harvest(String label, double left) { this.label = label; this.left = left; }
        static Harvest of(String s) { try { return valueOf(s); } catch (Exception e) { return NONE; } }
    }

    /** What happened, for other features (named citizens, quests) that want to follow a town's troubles. */
    public enum Event { OUTBREAK, DEATH, OUTBREAK_OVER, HARVEST_FAILED, HARVEST_RECOVERED, HUNGER, FED, VERMIN, VERMIN_GONE }

    public interface Listener { void on(Settlement s, Event e, String detail); }

    static final List<Listener> LISTENERS = new ArrayList<>();

    /** Hears every hardship event of every town (e.g. a citizen system picks who died: DEATH's detail is the cause). */
    public static void listen(Listener l) { LISTENERS.add(l); }

    static void fire(Settlement s, Event e, String detail) {
        for (Listener l : LISTENERS) {
            try { l.on(s, e, detail); } catch (RuntimeException ex) { LivingRealms.LOG.error("Hardship listener failed", ex); }
        }
    }

    /** One town's troubles, saved with the settlement under "hardship". Old saves load healthy. */
    public static final class State {
        public double sick, total, deathAccum, medAccum;
        public int dead;
        public long sickSince;
        public String sickName = "", sickCause = "";
        public boolean quarantine, autoQuarantine, medicated;
        public Harvest harvest = Harvest.NONE;
        public long harvestUntil;
        public boolean hungry;
        public long hungrySince;
        public int imported;
        public String importFrom = "", hungerCause = "";
        public long verminUntil;
        public int eaten;
        public double verminAccum;
        public long lastSiege = Long.MIN_VALUE / 2;

        public boolean outbreak() { return sick > 0; }
        public boolean vermin(long now) { return verminUntil > now; }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putDouble("sick", sick); t.putDouble("total", total); t.putDouble("deathAcc", deathAccum); t.putDouble("medAcc", medAccum);
            t.putInt("dead", dead); t.putLong("sickSince", sickSince); t.putString("sickName", sickName); t.putString("sickCause", sickCause);
            t.putBoolean("quarantine", quarantine); t.putBoolean("autoQ", autoQuarantine);
            t.putString("harvest", harvest.name()); t.putLong("harvestUntil", harvestUntil);
            t.putBoolean("hungry", hungry); t.putLong("hungrySince", hungrySince); t.putInt("imported", imported);
            t.putString("importFrom", importFrom); t.putString("hungerCause", hungerCause);
            t.putLong("verminUntil", verminUntil); t.putInt("eaten", eaten); t.putDouble("verminAcc", verminAccum);
            t.putLong("lastSiege", lastSiege);
            return t;
        }

        public void load(CompoundTag t) {
            sick = Math.max(0, t.getDouble("sick")); total = t.getDouble("total"); deathAccum = t.getDouble("deathAcc"); medAccum = t.getDouble("medAcc");
            dead = t.getInt("dead"); sickSince = t.getLong("sickSince"); sickName = t.getString("sickName"); sickCause = t.getString("sickCause");
            quarantine = t.getBoolean("quarantine"); autoQuarantine = t.getBoolean("autoQ");
            harvest = Harvest.of(t.getString("harvest")); harvestUntil = t.getLong("harvestUntil");
            hungry = t.getBoolean("hungry"); hungrySince = t.getLong("hungrySince"); imported = t.getInt("imported");
            importFrom = t.getString("importFrom"); hungerCause = t.getString("hungerCause");
            verminUntil = t.getLong("verminUntil"); eaten = t.getInt("eaten"); verminAccum = t.getDouble("verminAcc");
            lastSiege = t.contains("lastSiege") ? t.getLong("lastSiege") : Long.MIN_VALUE / 2;
        }
    }

    // ------------------------------------------------------------------ switches

    public static boolean enabled() { return HardshipConfig.ENABLED.get(); }
    public static boolean sickness() { return enabled() && HardshipConfig.SICKNESS.get(); }
    public static boolean harvests() { return enabled() && HardshipConfig.HARVEST.get(); }
    public static boolean rats() { return enabled() && HardshipConfig.VERMIN.get(); }

    static long day() { return Config.DAY_LENGTH.get(); }

    // ------------------------------------------------------------------ what the town has against them

    public static boolean hasApothecary(Settlement s) {
        for (Plot p : s.plots) if (p.standing() && healer(p)) return true;
        return false;
    }

    /** The Apothecary, or a pack building that is plainly one (an infirmary, a herbalist's, a healer's hut). */
    static boolean healer(Plot p) {
        if (p.typeId.equals(APOTHECARY)) return true;
        String id = p.typeId.toLowerCase(Locale.ROOT);
        return id.contains("apothecar") || id.contains("infirmar") || id.contains("herbalist") || id.contains("healer") || id.contains("hospital");
    }

    public static boolean hasChapel(Settlement s) { return s.standing(Category.CHAPEL) > 0; }

    public static boolean hasStorehouse(Settlement s) { return s.standing(Category.STORAGE) > 0; }

    public static int medicine(Settlement s) {
        int n = 0;
        for (Item it : MEDICINE) n += s.stock.count(it);
        return n;
    }

    /** Lowers the chance of an outbreak (and of catching one from a caravan). */
    public static double prevention(Settlement s) {
        return (hasChapel(s) ? .75 : 1) * (hasApothecary(s) ? .6 : 1) * (s.has(RESEARCH) ? .6 : 1);
    }

    static double crowd(Settlement s) {
        int housing = s.d == null ? 0 : s.d.housing;
        if (housing <= 0) return 1;
        return s.population > housing ? 1.8 : s.population >= housing * .9 ? 1.3 : 1;
    }

    static int variety(Settlement s) {
        int v = 0;
        for (var e : s.stock.view().object2IntEntrySet()) if (e.getIntValue() > 0 && e.getKey().getFoodProperties() != null) v++;
        return v;
    }

    static boolean npc(Settlement s) { return s.isAi() || s.mirror(); }

    public static boolean besiegedLately(Settlement s, long now) { return now - s.hardship.lastSiege < 3 * day(); }

    /** What makes an outbreak likelier, as (reason, factor) for the City Manager. */
    public static List<Map.Entry<String, Double>> sickFactors(Settlement s, long now) {
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        double crowd = crowd(s);
        if (crowd > 1) out.add(Map.entry(crowd > 1.5 ? "overcrowded" : "crowded", crowd));
        if (besiegedLately(s, now)) out.add(Map.entry("after a siege", 3.0));
        if (!npc(s)) {
            int v = variety(s);
            if (v <= 1) out.add(Map.entry("poor diet", 1.5)); else if (v >= 4) out.add(Map.entry("varied food", .8));
        }
        if (s.hardship.hungry) out.add(Map.entry("hunger", 1.6));
        if (hasChapel(s)) out.add(Map.entry("chapel", .75));
        if (hasApothecary(s)) out.add(Map.entry("apothecary", .6));
        if (s.has(RESEARCH)) out.add(Map.entry("Herb Lore", .6));
        return out;
    }

    /** Chance of an outbreak on a given day. */
    public static double sickChance(Settlement s, long now) {
        if (!sickness() || s.population < 4) return 0;
        double p = HardshipConfig.SICK_CHANCE.get() * Math.min(2.5, s.population / 24.0);
        for (var e : sickFactors(s, now)) p *= e.getValue();
        return Math.min(1, p);
    }

    static boolean farms(Settlement s) {
        if (s.standing(Category.FARM) > 0) return true;
        return npc(s) && Procedural.styleFamily(s.style).equals("plains");
    }

    public static double harvestChance(Settlement s) {
        return !harvests() || !farms(s) ? 0 : HardshipConfig.HARVEST_CHANCE.get();
    }

    /** What a bad harvest in this land is. */
    public static Harvest harvestKind(Settlement s) {
        return switch (Procedural.styleFamily(s.style)) {
            case "desert", "savanna" -> Harvest.DROUGHT;
            case "snowy", "taiga" -> Harvest.COLD_SNAP;
            default -> Harvest.BLIGHT;
        };
    }

    public static int foodInStore(Settlement s) {
        int n = 0;
        for (Item it : GRAIN) n += s.stock.count(it);
        return n;
    }

    public static double verminChance(Settlement s) {
        return !rats() || foodInStore(s) < 128 ? 0 : HardshipConfig.VERMIN_CHANCE.get() * (hasStorehouse(s) ? .5 : 1);
    }

    public static String label(double daily) {
        return daily <= 0 ? "None" : daily < 0.005 ? "Very low" : daily < 0.015 ? "Low" : daily < 0.04 ? "Moderate" : "High";
    }

    // ------------------------------------------------------------------ effects (hooks)

    public static double sickShare(Settlement s) {
        return s.population <= 0 ? 0 : Math.min(1, s.hardship.sick / s.population);
    }

    /** Builders: the sick work slower, the hungry slower still. 1 when nothing is wrong. */
    public static double workFactor(Settlement s) {
        if (!enabled()) return 1;
        return (1 - .6 * sickShare(s)) * (s.hardship.hungry ? .6 : 1);
    }

    /** Share of a farm's harvest left this season: the bad harvest's cut, softened by Granaries and storehouses. */
    public static double harvestLeft(Settlement s) {
        Harvest h = s.hardship.harvest;
        if (!enabled() || h == Harvest.NONE) return 1;
        double loss = (1 - h.left) * (s.has("granaries") ? .7 : 1) * (hasStorehouse(s) ? .8 : 1);
        return 1 - loss;
    }

    /** Workers of a building of this category: slower when sick, and farms yield less in a bad harvest. */
    public static double output(Settlement s, Category c) {
        if (!enabled()) return 1;
        double k = 1 - .6 * sickShare(s);
        return c == Category.FARM ? k * harvestLeft(s) : k;
    }

    /** Mood, with the reason shown under Happiness. */
    public static double mood(Settlement s, List<String> why) {
        if (!enabled()) return 0;
        double h = 0;
        State st = s.hardship;
        if (st.sick > 0) {
            int m = (int) Math.max(2, Math.round(25 * sickShare(s)));
            h -= m; why.add("-" + m + " sickness");
        }
        if (st.harvest != Harvest.NONE) { h -= 3; why.add("-3 poor harvest"); }
        // player towns already lose 30 for starving; NPC towns keep no larder, so their hunger is counted here
        if (st.hungry && npc(s)) { h -= 20; why.add("-20 hunger"); }
        return h;
    }

    /** NPC towns' goods: farms and fields yield less in a bad harvest. */
    public static void tradeOutput(Settlement s, Map<Item, Double> out) {
        double left = harvestLeft(s), work = 1 - .6 * sickShare(s);
        if (left >= 1 && work >= 1) return;
        out.replaceAll((it, n) -> n * work * (GRAIN.contains(it) ? left : 1));
    }

    /** A town in a bad harvest or going hungry wants far more food, and a sick one wants remedies: caravans bring them. */
    public static void wants(Settlement s, Map<Item, Integer> w) {
        if (!enabled()) return;
        State st = s.hardship;
        int pop = Math.max(1, s.population);
        if (st.hungry) { w.merge(Items.BREAD, pop * 6, Integer::sum); w.merge(Items.POTATO, pop * 2, Integer::sum); w.merge(Items.CARROT, pop * 2, Integer::sum); }
        else if (st.harvest != Harvest.NONE) w.merge(Items.BREAD, pop * 3, Integer::sum);
        if (st.sick >= 1) w.merge(Items.HONEY_BOTTLE, 4 + (int) Math.ceil(st.sick), Integer::sum);
    }

    /** Why a route is shut by quarantine, or null. */
    @Nullable
    public static String routeBlock(Settlement a, Settlement b) {
        if (!sickness()) return null;
        if (a.hardship.quarantine) return "Quarantine in " + a.name;
        if (b.hardship.quarantine) return "Quarantine in " + b.name;
        return null;
    }

    static int nutrition(Map<Item, Integer> cargo) {
        int n = 0;
        for (var e : cargo.entrySet()) {
            var fp = e.getKey().getFoodProperties();
            if (fp != null && fp.getNutrition() > 0) n += e.getValue();
        }
        return n;
    }

    /** A caravan reached its market: food counts towards ending the hunger; one from a sick town may carry the sickness. */
    public static void caravanArrived(Settlement from, Settlement to, Map<Item, Integer> cargo, long now, RandomSource rand) {
        if (!enabled() || from == null || to == null) return;
        int food = nutrition(cargo);
        if (food > 0 && (to.hardship.hungry || to.hardship.harvest != Harvest.NONE)) { to.hardship.imported += food; to.hardship.importFrom = from.name; }
        if (sickness() && from.hardship.sick >= 1 && to.hardship.sick <= 0 && !to.hardship.quarantine
                && rand.nextDouble() < HardshipConfig.CARAVAN_CONTAGION.get() * prevention(to))
            outbreak(to, now, "a caravan from " + from.name, rand);
    }

    /** A bounty the board should post now: remedies for the sick, food for the hungry. Null when the town is well. */
    public record Ask(Item item, int need, String reason) {}

    @Nullable
    public static Ask bounty(Settlement s, Set<Item> asked) {
        if (!enabled()) return null;
        State st = s.hardship;
        int pop = Math.max(1, s.population);
        if (st.sick >= 1) {
            if (!asked.contains(Items.HONEY_BOTTLE)) return new Ask(Items.HONEY_BOTTLE, Math.min(32, 8 + (int) Math.ceil(st.sick) / 8 * 8), "the sick need remedies");
            if (!asked.contains(Items.GOLDEN_APPLE)) return new Ask(Items.GOLDEN_APPLE, Math.min(8, 2 + (int) st.sick / 4), "the healers ask for golden apples");
        }
        if ((st.hungry || st.harvest != Harvest.NONE && foodInStore(s) < pop * 4) && !asked.contains(Items.BREAD))
            return new Ask(Items.BREAD, Math.min(128, Math.max(32, pop * 6) / 8 * 8), st.hungry ? "the town is going hungry" : "the " + st.harvest.label + " ruined the harvest");
        return null;
    }

    // ------------------------------------------------------------------ the tick

    public static void tick(MinecraftServer server, SettlementManager m, long tick) {
        long now = server.overworld().getGameTime();
        for (Settlement s : m.all()) {
            int phase = Math.floorMod(s.id.hashCode() * 31, CHECK);
            if (tick % CHECK == phase) {
                ServerLevel level = server.getLevel(s.dim);
                step(server, m, s, CHECK / (double) day(), now, level == null ? server.overworld().random : level.random);
            }
            if (tick % SHOW == phase % SHOW && enabled() && (s.hardship.sick > 0 || s.hardship.hungry)) {
                ServerLevel level = server.getLevel(s.dim);
                if (level != null && level.hasChunkAt(s.center)) show(level, s);
            }
        }
    }

    /** f days pass for one town: its troubles run their course, and new ones may start. */
    public static void step(@Nullable MinecraftServer server, @Nullable SettlementManager m, Settlement s, double f, long now, RandomSource rand) {
        State st = s.hardship;
        if (!enabled()) { clear(s); return; }
        if (m != null) for (Raids.Raid r : m.raids)
            if (r.target.equals(s.id) && (r.siege.walled || r.siege.blockade > 0)) st.lastSiege = now;
        hunger(s, now);
        // sickness
        if (!sickness()) { st.sick = 0; if (st.autoQuarantine) { st.quarantine = false; st.autoQuarantine = false; } }
        else if (st.sick > 0) course(s, f, now, rand);
        else if (rand.nextDouble() < 1 - Math.pow(1 - sickChance(s, now), f)) outbreak(s, now, "", rand);
        if (sickness() && npc(s) && HardshipConfig.AI_QUARANTINE.get()) {
            boolean want = st.sick >= 1 && s.tier.number() >= 2;
            if (want && !st.quarantine) { st.quarantine = true; st.autoQuarantine = true; }
            else if (!want && st.autoQuarantine) { st.quarantine = false; st.autoQuarantine = false; }
        }
        // harvest
        if (st.harvest != Harvest.NONE && (now >= st.harvestUntil || !harvests())) endHarvest(s);
        else if (st.harvest == Harvest.NONE && rand.nextDouble() < 1 - Math.pow(1 - harvestChance(s), f)) badHarvest(s, now, rand);
        // rats
        if (st.verminUntil > 0 && (now >= st.verminUntil || !rats())) endVermin(s);
        else if (st.vermin(now)) gnaw(s, f);
        else if (rand.nextDouble() < 1 - Math.pow(1 - verminChance(s), f)) vermin(s, now);
        s.dirty = true;
    }

    /** Hardships switched off: every trouble ends at once, quietly. */
    static void clear(Settlement s) {
        State st = s.hardship;
        st.sick = 0; st.total = 0; st.dead = 0; st.sickSince = 0;
        st.harvest = Harvest.NONE; st.verminUntil = 0; st.hungry = false;
        if (st.autoQuarantine) { st.quarantine = false; st.autoQuarantine = false; }
    }

    // ------------------------------------------------------------------ sickness

    static final String[] NAMES = {"the sweating sickness", "a spotted fever", "the coughing sickness", "the bloody flux", "a winter ague", "the grey fever"};

    /** Starts an outbreak (cause "" for the town's own bad air). Returns false when it cannot start. */
    public static boolean outbreak(Settlement s, long now, String cause, RandomSource rand) {
        State st = s.hardship;
        if (!sickness() || st.sick > 0 || s.population < 2) return false;
        st.sick = Math.max(1, Math.round(s.population * .08));
        st.total = st.sick;
        st.dead = 0;
        st.deathAccum = 0;
        st.medAccum = .99; // the first remedy goes out at once
        st.sickSince = now;
        st.sickCause = cause;
        st.sickName = NAMES[rand.nextInt(NAMES.length)];
        if (Procedural.styleFamily(s.style).equals("snowy") && rand.nextBoolean()) st.sickName = "a winter ague";
        alarm(s, "Sickness in " + s.name + ": " + st.sickName + (cause.isEmpty() ? "" : ", brought by " + cause) + ". " + (int) st.sick + " are ill.");
        fire(s, Event.OUTBREAK, cause);
        return true;
    }

    /** Rates per day: how fast it spreads, how fast the sick get well, and how many of them die. */
    public static double spread(Settlement s, long now) {
        double age = (now - s.hardship.sickSince) / (double) day();
        return .6 * crowd(s) * Math.max(.2, 1 - age / 6) * (hasChapel(s) ? .8 : 1) * (hasApothecary(s) ? .6 : 1) * (s.has(RESEARCH) ? .6 : 1);
    }

    public static double recovery(Settlement s) {
        return .25 * (hasChapel(s) ? 1.3 : 1) * (hasApothecary(s) ? 1.6 : 1) * (s.has(RESEARCH) ? 1.5 : 1) * (s.hardship.medicated ? 1.5 : 1);
    }

    public static double deaths(Settlement s) {
        return HardshipConfig.DEATH_RATE.get() * (hasChapel(s) ? .75 : 1) * (hasApothecary(s) ? .6 : 1) * (s.has(RESEARCH) ? .6 : 1)
                * (s.hardship.medicated ? .7 : 1) * (s.hardship.hungry ? 2 : 1);
    }

    /** One step of an outbreak. */
    static void course(Settlement s, double f, long now, RandomSource rand) {
        State st = s.hardship;
        int pop = Math.max(1, s.population);
        // remedies from the stockpile: one for every two sick a day
        st.medAccum += st.sick / 2 * f;
        int give = (int) st.medAccum;
        st.medicated = false;
        for (Item it : MEDICINE) {
            if (s.stock.count(it) <= 0) continue;
            st.medicated = true;
            if (give > 0) give -= s.stock.takeUpTo(it, give);
        }
        st.medAccum -= (int) st.medAccum;
        double healthy = Math.max(0, pop - st.sick);
        double caught = spread(s, now) * st.sick * healthy / pop * f;
        double well = recovery(s) * st.sick * f;
        st.deathAccum += deaths(s) * st.sick * f;
        st.sick = Math.max(0, Math.min(pop, st.sick + caught - well));
        st.total += caught;
        while (st.deathAccum >= 1 && st.sick >= 1 && s.population > 1) {
            st.deathAccum -= 1;
            st.sick -= 1;
            st.dead++;
            s.population--;
            s.log("One of the sick died of " + st.sickName);
            fire(s, Event.DEATH, st.sickName);
        }
        if (st.sick < .5) endOutbreak(s, now);
    }

    static void endOutbreak(Settlement s, long now) {
        State st = s.hardship;
        int total = (int) Math.round(st.total), dead = st.dead;
        st.sick = 0;
        st.medicated = false;
        String msg = "The " + plain(st.sickName) + " has passed." + (dead > 0 ? " " + dead + (dead == 1 ? " died." : " died.") : " Nobody died.");
        s.log(msg);
        tell(s, msg, ChatFormatting.GOLD);
        if (total >= 3 || dead > 0) Chronicle.record(s, Chronicle.Kind.DISASTER, sickStory(s, total, dead));
        fire(s, Event.OUTBREAK_OVER, String.valueOf(dead));
        st.total = 0; st.dead = 0; st.sickSince = 0; st.sickCause = "";
    }

    static String plain(String name) { return name.startsWith("the ") ? name.substring(4) : name.startsWith("a ") ? name.substring(2) : name; }

    static String sickStory(Settlement s, int total, int dead) {
        State st = s.hardship;
        int year = Chronicle.year(Chronicle.today());
        String name = plain(st.sickName);
        String cause = st.sickCause.isEmpty() ? "" : " It came with " + st.sickCause + ".";
        String care = hasApothecary(s) ? " The apothecary's remedies saved many."
                : hasChapel(s) ? " The priests of the chapel nursed the sick."
                : s.has(RESEARCH) ? " Healers who knew their herbs went from house to house." : "";
        if (st.quarantine && !st.autoQuarantine) care += " The roads were closed to caravans until it passed.";
        String deaths = dead == 0 ? "none died" : dead == 1 ? "one died" : dead + " died";
        return switch (Math.floorMod(st.sickSince / 7, 3)) {
            case 0 -> "The " + name + " of year " + year + " laid " + total + " of " + s.name + "'s people low; " + deaths + "." + cause + care;
            case 1 -> Character.toUpperCase(name.charAt(0)) + name.substring(1) + " went through " + s.name + " in year " + year + ": " + total + " fell ill and " + deaths + "." + cause + care;
            default -> "In year " + year + " " + s.name + " suffered the " + name + "; " + total + " took to their beds and " + deaths + "." + cause + care;
        };
    }

    /** The owner's quarantine switch: closes the town's trade routes until lifted. */
    @Nullable
    public static String quarantine(Settlement s, boolean on) {
        if (!sickness()) return "Sickness is switched off in the server config";
        s.hardship.quarantine = on;
        s.hardship.autoQuarantine = false;
        s.log(on ? "Quarantine: the roads are closed to caravans" : "The quarantine was lifted");
        s.dirty = true;
        return null;
    }

    // ------------------------------------------------------------------ harvest and hunger

    public static void badHarvest(Settlement s, long now, RandomSource rand) {
        State st = s.hardship;
        Harvest h = harvestKind(s);
        int days = Math.max(1, HardshipConfig.HARVEST_DAYS.get() + rand.nextInt(3) - 1);
        st.harvest = h;
        st.harvestUntil = now + days * day();
        String msg = switch (h) {
            case DROUGHT -> "Drought: the fields of " + s.name + " are parched. Farms yield " + pct(harvestLeft(s)) + " for " + days + " days.";
            case COLD_SNAP -> "A cold snap blackened the crops of " + s.name + ". Farms yield " + pct(harvestLeft(s)) + " for " + days + " days.";
            default -> "Blight in the fields of " + s.name + ". Farms yield " + pct(harvestLeft(s)) + " for " + days + " days.";
        };
        alarm(s, msg);
        fire(s, Event.HARVEST_FAILED, h.label);
    }

    static void endHarvest(Settlement s) {
        State st = s.hardship;
        if (st.hungry && st.hungerCause.isEmpty()) st.hungerCause = st.harvest.label;
        s.log("The " + st.harvest.label + " is over; the fields recover");
        fire(s, Event.HARVEST_RECOVERED, st.harvest.label);
        st.harvest = Harvest.NONE;
        st.harvestUntil = 0;
    }

    static String pct(double v) { return Math.round(v * 100) + "%"; }

    /** Whether the town is going hungry: a player town's larder is empty; an NPC town's stores ran low in a bad harvest. */
    public static boolean starving(Settlement s) {
        if (npc(s)) return s.hardship.harvest != Harvest.NONE && foodInStore(s) < Math.max(8, s.population * 2);
        return s.starving;
    }

    static void hunger(Settlement s, long now) {
        State st = s.hardship;
        boolean h = starving(s);
        if (h && !st.hungry) {
            st.hungry = true;
            st.hungrySince = now;
            st.imported = 0;
            st.importFrom = "";
            st.hungerCause = st.harvest != Harvest.NONE ? st.harvest.label : besiegedLately(s, now) ? "siege" : "";
            alarm(s, s.name + " is going hungry: the stores are empty. Growth stops and the builders slow.");
            fire(s, Event.HUNGER, st.hungerCause);
        } else if (!h && st.hungry) {
            st.hungry = false;
            long lasted = now - st.hungrySince;
            String msg = s.name + " has food again.";
            s.log(msg);
            fire(s, Event.FED, st.importFrom);
            if (lasted >= day() / 4) Chronicle.record(s, Chronicle.Kind.DISASTER, famineStory(s, lasted));
        }
    }

    static String famineStory(Settlement s, long lasted) {
        State st = s.hardship;
        int year = Chronicle.year(Chronicle.today());
        long days = Math.max(1, Math.round(lasted / (double) day()));
        String why = switch (st.hungerCause) {
            case "drought" -> "a drought burned the fields";
            case "cold snap" -> "a cold snap killed the crops";
            case "blight" -> "blight rotted the harvest";
            case "siege" -> "the siege emptied the stores";
            default -> "the stores ran out";
        };
        String end = st.importFrom.isEmpty() ? "until the fields fed them again" : "until caravans from " + st.importFrom + " brought food";
        return switch (Math.floorMod(st.hungrySince / 7, 2)) {
            case 0 -> "The hungry days of year " + year + ": " + why + ", and " + s.name + " went short for " + days + (days == 1 ? " day, " : " days, ") + end + ".";
            default -> "Famine in year " + year + ". " + Character.toUpperCase(why.charAt(0)) + why.substring(1) + "; the people of " + s.name + " tightened their belts for " + days + (days == 1 ? " day, " : " days, ") + end + ".";
        };
    }

    // ------------------------------------------------------------------ rats

    public static void vermin(Settlement s, long now) {
        State st = s.hardship;
        st.verminUntil = now + 2 * day();
        st.eaten = 0;
        st.verminAccum = 0;
        alarm(s, "Rats in the stores of " + s.name + "! They will eat into the grain and bread for a couple of days.");
        fire(s, Event.VERMIN, "");
    }

    /** Rats eat a tenth of the grain and bread a day (half that with a storehouse or Granaries). */
    static void gnaw(Settlement s, double f) {
        State st = s.hardship;
        double rate = .10 * (hasStorehouse(s) ? .5 : 1) * (s.has("granaries") ? .5 : 1);
        st.verminAccum += foodInStore(s) * rate * f;
        int bite = (int) st.verminAccum;
        st.verminAccum -= bite;
        for (Item it : GRAIN) {
            if (bite <= 0) break;
            int n = s.stock.takeUpTo(it, Math.min(bite, (int) Math.ceil(bite * s.stock.count(it) / (double) Math.max(1, foodInStore(s))) + 1));
            bite -= n; st.eaten += n;
        }
    }

    static void endVermin(Settlement s) {
        State st = s.hardship;
        st.verminUntil = 0;
        s.log("The rat-catchers have done their work; the rats ate " + st.eaten + " food");
        if (st.eaten >= 32) Chronicle.record(s, Chronicle.Kind.DISASTER, "Rats got into the stores of " + s.name + " and ate " + st.eaten + " sacks of grain and loaves before the rat-catchers had them.");
        fire(s, Event.VERMIN_GONE, String.valueOf(st.eaten));
    }

    // ------------------------------------------------------------------ in the world

    static void alarm(Settlement s, String msg) {
        s.log(msg);
        tell(s, msg, ChatFormatting.RED);
    }

    static void tell(Settlement s, String msg, ChatFormatting colour) {
        if (s.owner == null) return;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(s.owner);
        if (p != null) p.sendSystemMessage(Component.literal(msg).withStyle(colour));
    }

    /** Whether this settler is one of the sick (a steady share of the townsfolk, by id). */
    public static boolean isSick(Settlement s, UUID id) {
        return (id.getLeastSignificantBits() & 0xFFFF) / 65536.0 < sickShare(s);
    }

    /** The sick cough and shuffle (slowness, sneezes); the hungry grumble. */
    static void show(ServerLevel level, Settlement s) {
        int r = s.radius() + 16;
        for (SettlerEntity e : level.getEntitiesOfClass(SettlerEntity.class, new AABB(s.center).inflate(r, 48, r), e -> s.id.equals(e.settlementId()))) {
            if (s.hardship.sick > 0 && isSick(s, e.getUUID())) {
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, SHOW + 20, 0, true, true));
                level.sendParticles(ParticleTypes.SNEEZE, e.getX(), e.getEyeY(), e.getZ(), 4, .2, .1, .2, .02);
                level.sendParticles(ParticleTypes.ITEM_SLIME, e.getX(), e.getY() + 1, e.getZ(), 2, .2, .3, .2, 0);
            } else if (s.hardship.hungry && level.random.nextInt(4) == 0)
                level.sendParticles(ParticleTypes.ANGRY_VILLAGER, e.getX(), e.getEyeY() + .4, e.getZ(), 1, .2, .1, .2, 0);
        }
    }

    // ------------------------------------------------------------------ for the City Manager

    public static CompoundTag view(Settlement s, long now) {
        CompoundTag t = new CompoundTag();
        State st = s.hardship;
        t.putBoolean("enabled", enabled());
        t.putBoolean("sickOn", sickness());
        t.putBoolean("quarantine", st.quarantine);
        t.putBoolean("autoQuarantine", st.autoQuarantine);
        double sc = sickChance(s, now), hc = harvestChance(s), vc = verminChance(s);
        t.putString("sickRisk", sickness() ? label(sc) : "Off");
        t.putString("harvestRisk", harvests() ? label(hc) : "Off");
        t.putString("ratRisk", rats() ? label(vc) : "Off");
        t.putString("harvestKind", harvestKind(s).label);
        StringBuilder why = new StringBuilder();
        for (var e : sickFactors(s, now)) why.append(why.length() == 0 ? "" : ", ").append(e.getKey()).append(e.getValue() >= 1 ? " ↑" : " ↓");
        t.putString("sickWhy", why.toString());
        t.putBoolean("chapel", hasChapel(s));
        t.putBoolean("apothecary", hasApothecary(s));
        t.putBoolean("herbLore", s.has(RESEARCH));
        t.putBoolean("granaries", s.has("granaries"));
        t.putBoolean("storehouse", hasStorehouse(s));
        t.putInt("medicine", medicine(s));
        ListTag troubles = new ListTag();
        if (st.sick > 0) troubles.add(trouble("Sickness", Character.toUpperCase(st.sickName.charAt(0)) + st.sickName.substring(1) + ": " + (int) Math.ceil(st.sick) + " of " + s.population + " ill"
                + (st.dead > 0 ? ", " + st.dead + " dead" : ""), "Workers −" + pct(.6 * sickShare(s)) + " · mood −" + Math.max(2, Math.round(25 * sickShare(s))), "red"));
        if (st.harvest != Harvest.NONE) troubles.add(trouble("Bad harvest", Character.toUpperCase(st.harvest.label.charAt(0)) + st.harvest.label.substring(1) + ", "
                + Math.max(1, Math.round((st.harvestUntil - now) / (double) day())) + " days left", "Farms yield " + pct(harvestLeft(s)) + " · caravans bring food", "orange"));
        if (st.hungry) troubles.add(trouble("Hunger", "The stores are empty", "No growth · builders −40% · mood falls" + (st.imported > 0 ? " · " + st.imported + " food imported" : ""), "red"));
        if (st.vermin(now)) troubles.add(trouble("Rats", "Rats in the stores, " + st.eaten + " food eaten", "Grain and bread eaten each day", "orange"));
        if (st.quarantine) troubles.add(trouble("Quarantine", st.autoQuarantine ? "The town closed its roads" : "Roads closed by order", "No caravans to or from the town", "blue"));
        t.put("troubles", troubles);
        return t;
    }

    static CompoundTag trouble(String name, String what, String effect, String colour) {
        CompoundTag c = new CompoundTag();
        c.putString("name", name); c.putString("what", what); c.putString("effect", effect); c.putString("color", colour);
        return c;
    }

    private Hardships() {}
}
