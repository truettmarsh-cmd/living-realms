package livingrealms.settlement;

import livingrealms.LivingRealms;
import livingrealms.chronicle.Chronicle;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierProgression;
import livingrealms.military.UnitType;
import livingrealms.net.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Royal decrees (docs/decrees.md): active abilities of a kingdom, issued from the Kingdom page. State lives in
 * {@link DecreeData}; other code asks {@link #active(UUID, Decree)}. Effects reach the rest of the mod through small hooks:
 * mood and tax ({@link Simulation}), loyalty ({@link Loyalty}), sickness (Hardships), peace offers ({@link Wars}), breaches
 * ({@link Siege}) and damage (RecruitCombat).
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class Decrees {
    /** Persistent-data tag on a levy: game time it goes home. */
    public static final String LEVY_TAG = "livingrealms_levy";
    static final long DISBAND_FORGET = 10L * 24000;
    private static long tick;

    // ------------------------------------------------------------------ query API

    public static boolean active(UUID kingdom, Decree d) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && active(server, kingdom, d);
    }

    public static boolean active(MinecraftServer server, @Nullable UUID kingdom, Decree d) {
        return kingdom != null && until(server, kingdom, d) > server.overworld().getGameTime();
    }

    /** Game time the decree ends (0 if it is not in force). */
    public static long until(MinecraftServer server, UUID kingdom, Decree d) {
        DecreeData.Realm r = DecreeData.get(server).peek(kingdom);
        return r == null ? 0 : r.until.getOrDefault(d, 0L);
    }

    /** Game time the decree can be issued again. */
    public static long readyAt(MinecraftServer server, UUID kingdom, Decree d) {
        DecreeData.Realm r = DecreeData.get(server).peek(kingdom);
        return r == null ? 0 : r.ready.getOrDefault(d, 0L);
    }

    public static boolean isLevy(Entity e) { return e.getPersistentData().contains(LEVY_TAG); }

    // ------------------------------------------------------------------ gates and costs

    /** The kingdom's rank is its highest city's. */
    public static Tier rank(SettlementManager m, UUID kingdom) {
        Tier best = Tier.CAMP;
        for (Settlement c : Statecraft.cities(m, kingdom)) if (!c.mirror() && c.tier.ordinal() > best.ordinal()) best = c.tier;
        return best;
    }

    public static boolean researched(SettlementManager m, UUID kingdom, String id) {
        for (Settlement c : Statecraft.cities(m, kingdom)) if (!c.mirror() && c.research.contains(id)) return true;
        return false;
    }

    public record Cost(int emeralds, int food, int gold) {
        String text() {
            List<String> p = new ArrayList<>();
            if (emeralds > 0) p.add(emeralds + " emeralds");
            if (gold > 0) p.add(gold + " gold ingots");
            if (food > 0) p.add(food + " food");
            return p.isEmpty() ? "free" : String.join(", ", p);
        }
    }

    /** Levies Call the Banners would raise: 1 to 6 from each town of at least 4 people, by rank, at most 20. */
    static int levies(SettlementManager m, UUID kingdom) {
        int n = 0;
        for (Settlement c : Statecraft.cities(m, kingdom)) if (!c.mirror() && c.population >= 4) n += Decree.leviesOf(c.tier);
        return Math.min(Decree.MAX_LEVIES, n);
    }

    static int people(SettlementManager m, UUID kingdom) {
        int n = 0;
        for (Settlement c : Statecraft.cities(m, kingdom)) if (!c.mirror()) n += c.population;
        return n;
    }

    public static Cost cost(SettlementManager m, UUID kingdom, Decree d) {
        return switch (d) {
            case ROYAL_FEAST -> new Cost(d.emeralds, Math.max(Decree.FEAST_FOOD_MIN, Decree.FEAST_FOOD_PER_HEAD * people(m, kingdom)), 0);
            case AMNESTY -> new Cost(d.emeralds, 0, Decree.AMNESTY_GOLD);
            case CALL_THE_BANNERS -> { int n = levies(m, kingdom); yield new Cost(d.emeralds + Decree.LEVY_EMERALDS * n, Decree.LEVY_FOOD * n, 0); }
            default -> new Cost(d.emeralds, 0, 0);
        };
    }

    /** Nutrition of the plain food in a stockpile (what the town itself would eat). */
    public static int food(Stockpile st) {
        int n = 0;
        for (Item it : foods(st)) n += st.count(it) * it.getFoodProperties().getNutrition();
        return n;
    }

    static List<Item> foods(Stockpile st) {
        List<Item> out = new ArrayList<>();
        for (var e : st.view().object2IntEntrySet()) {
            FoodProperties fp = e.getKey().getFoodProperties();
            if (e.getIntValue() > 0 && fp != null && fp.getNutrition() > 0 && fp.getEffects().stream().allMatch(p -> p.getFirst().getEffect().isBeneficial()))
                out.add(e.getKey());
        }
        out.sort(Comparator.comparingInt((Item i) -> -st.count(i)).thenComparing(i -> BuiltInRegistries.ITEM.getKey(i).toString()));
        return out;
    }

    static void takeFood(Stockpile st, int nutrition) {
        for (Item it : foods(st)) {
            if (nutrition <= 0) return;
            int nut = it.getFoodProperties().getNutrition();
            int n = Math.min(st.count(it), (int) Math.ceil(nutrition / (double) nut));
            st.take(it, n);
            nutrition -= n * nut;
        }
    }

    /** NPC kingdoms pay from their seat's treasury, 2 per emerald (food and gold are notional). */
    static final int NPC_WEALTH = 2;

    @Nullable
    static Raids.Raid raidOn(SettlementManager m, UUID kingdom) {
        for (Raids.Raid r : m.raids) if (r.defender.equals(kingdom)) return r;
        return null;
    }

    static boolean atWarWithNpc(SettlementManager m, UUID kingdom) {
        for (Statecraft.War w : m.state.wars) if (w.involves(kingdom) && !Statecraft.isPlayer(m, w.other(kingdom))) return true;
        return false;
    }

    /** Why this kingdom cannot issue the decree from this city now, or null. */
    @Nullable
    public static String blocker(MinecraftServer server, SettlementManager m, Settlement s, Decree d) {
        UUID k = s.kingdomId();
        long now = server.overworld().getGameTime();
        boolean npc = s.owner == null;
        if (s.mirror()) return "Village Expansion runs this village";
        if (rank(m, k).ordinal() < d.tier.ordinal()) return "Needs a " + d.tier.label + " in the kingdom";
        if (!npc && !researched(m, k, Decree.RESEARCH)) return "Research Royal Decrees in one of your cities";
        if (!npc && d.military && !researched(m, k, Decree.MILITARY_RESEARCH)) return "Research Command in one of your cities";
        long until = until(server, k, d);
        if (until > now) return "In force for " + minutes(until - now);
        long ready = readyAt(server, k, d);
        if (ready > now) return "Ready again in " + minutes(ready - now);
        switch (d) {
            case TAX_HOLIDAY -> { if (active(server, k, Decree.ROYAL_LEVY)) return "Not while the Royal Levy is felt"; }
            case ROYAL_LEVY -> { if (active(server, k, Decree.TAX_HOLIDAY)) return "Not during a Tax Holiday"; }
            case AMNESTY -> {
                if (!m.state.loyalty.containsKey(s.id) && !atWarWithNpc(m, k))
                    return "Use it in a conquered city, or while at war with an NPC kingdom";
            }
            case MARTIAL_LAW -> { if (raidOn(m, k) == null) return "Only while one of your cities is raided or besieged"; }
            case CALL_THE_BANNERS -> { if (levies(m, k) == 0) return "No town has people to spare (4 or more)"; }
            default -> {}
        }
        Cost c = cost(m, k, d);
        Settlement seat = Statecraft.seat(m, k);
        if (seat == null) return "The kingdom has no seat";
        if (npc) return seat.wealth < c.emeralds * NPC_WEALTH ? "The treasury is short" : null;
        if (seat.stock.count(Items.EMERALD) < c.emeralds) return "Needs " + c.emeralds + " emeralds in " + seat.name + "'s stockpile";
        if (seat.stock.count(Items.GOLD_INGOT) < c.gold) return "Needs " + c.gold + " gold ingots in " + seat.name + "'s stockpile";
        if (food(seat.stock) < c.food) return "Needs " + c.food + " food in " + seat.name + "'s stockpile (has " + food(seat.stock) + ")";
        return null;
    }

    static String minutes(long ticks) {
        long min = Math.max(1, (ticks + 1199) / 1200);
        return min >= 120 ? (min + 30) / 60 + " h" : min + " min";
    }

    // ------------------------------------------------------------------ issuing

    /** A ruler issues a decree from the Kingdom page of city s. Returns an error, or null. */
    @Nullable
    public static String issue(ServerPlayer player, SettlementManager m, Settlement s, @Nullable Decree d) {
        if (d == null) return "Unknown decree";
        if (s.owner == null || !player.getUUID().equals(s.owner)) return "Only this kingdom's ruler can issue decrees";
        return issue(player.server, m, s, d);
    }

    /** Issues a decree for the kingdom of s (a player's or an NPC's), paying for it. Returns an error, or null. */
    @Nullable
    public static String issue(MinecraftServer server, SettlementManager m, Settlement s, Decree d) {
        String why = blocker(server, m, s, d);
        if (why != null) return why;
        UUID k = s.kingdomId();
        long now = server.overworld().getGameTime();
        Cost c = cost(m, k, d);
        Settlement seat = Statecraft.seat(m, k);
        if (s.owner == null) seat.wealth -= c.emeralds * NPC_WEALTH;
        else {
            seat.stock.take(Items.EMERALD, c.emeralds);
            seat.stock.take(Items.GOLD_INGOT, c.gold);
            takeFood(seat.stock, c.food);
        }
        seat.dirty = true;
        DecreeData data = DecreeData.get(server);
        DecreeData.Realm r = data.realm(k);
        r.until.put(d, now + d.duration());
        r.ready.put(d, now + d.cooldown());
        r.city.put(d, s.id);
        data.setDirty();
        String what = apply(server, m, data, s, d, now);
        announce(server, m, k, d, what, true);
        if (d.chronicled()) Chronicle.kingdom(m, k, Chronicle.Kind.DECREE, d.label + " was proclaimed: " + what);
        for (Settlement c2 : Statecraft.cities(m, k)) c2.dirty = true;
        LivingRealms.LOG.info("Living Realms: {} issued {} ({})", Statecraft.name(m, k), d.label, what);
        return null;
    }

    /** NPC kingdoms (Strategy): issues the decree if their rank allows and the treasury pays. */
    public static boolean npc(MinecraftServer server, SettlementManager m, Settlement s, Decree d) {
        if (s.owner != null || s.mirror()) return false;
        return issue(server, m, s, d) == null;
    }

    /** The decree's immediate effect; returns what happened, for the announcement. */
    static String apply(MinecraftServer server, SettlementManager m, DecreeData data, Settlement s, Decree d, long now) {
        UUID k = s.kingdomId();
        switch (d) {
            case WAR_HORN -> {
                List<LivingEntity> all = recruits(server, m, k);
                for (LivingEntity e : all) RecruitsBridge.addMorale(e, Decree.WAR_HORN_MORALE);
                return all.size() + (all.size() == 1 ? " recruit takes" : " recruits take") + " heart: +15% damage for 3 minutes";
            }
            case ROYAL_FEAST -> { return "every town feasts for two days"; }
            case TAX_HOLIDAY -> { return "no head tax for two days"; }
            case ROYAL_LEVY -> {
                int total = 0;
                for (Settlement c : Statecraft.cities(m, k)) {
                    if (c.mirror() || c.owner == null || c.population <= 0) continue;
                    c.stock.force(Items.EMERALD, c.population);
                    total += c.population;
                }
                return total + " emeralds collected; the people grumble";
            }
            case AMNESTY -> {
                Statecraft.Fealty l = m.state.loyalty.get(s.id);
                String peace = atWarWithNpc(m, k) ? "; envoys are heard more kindly for a day" : "";
                if (l == null) return "a pardon for the enemy's people" + peace;
                l.value = Math.max(l.value, Decree.AMNESTY_LOYALTY);
                l.lowSteps = 0;
                l.warned = false;
                Loyalty.unrest(s.id, l);
                return s.name + " is pardoned (loyalty " + (int) l.value + ")" + peace;
            }
            case CALL_THE_BANNERS -> {
                int n = levies(m, k);
                long until = now + d.duration();
                ServerLevel level = server.getLevel(s.dim);
                int raised = level != null && level.hasChunkAt(s.center) ? muster(level, data, s, k, n, until) : 0;
                if (raised < n) {
                    DecreeData.Muster mu = new DecreeData.Muster();
                    mu.kingdom = k; mu.city = s.id; mu.count = n - raised; mu.until = until;
                    data.musters.add(mu);
                }
                return n + " levies muster at " + s.name + " for a day";
            }
            case MARTIAL_LAW -> {
                Raids.Raid raid = raidOn(m, k);
                Settlement t = raid == null ? null : m.get(raid.target);
                return (t == null ? "the cities" : t.name) + " under martial law: the gates hold, the garrison is tended";
            }
        }
        return "";
    }

    /** Raises up to n militia levies at the city's muster point; returns how many stood up. */
    static int muster(ServerLevel level, DecreeData data, Settlement s, UUID k, int n, long until) {
        int raised = 0;
        for (int i = 0; i < n; i++) {
            UnitType u = i % 3 == 2 ? UnitType.BOWMAN : UnitType.FOOTMAN;
            BlockPos at = Simulation.musterPoint(level, s, s.soldiers.size());
            String name = "Levy " + Simulation.FIRST[level.random.nextInt(Simulation.FIRST.length)];
            Entity e = RecruitsBridge.spawn(level, u, at, k, name);
            if (e == null) break;
            e.getPersistentData().putLong(LEVY_TAG, until);
            RecruitsBridge.setSlot(e, EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            RecruitsBridge.setSlot(e, EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            RecruitsBridge.setSlot(e, EquipmentSlot.MAINHAND, new ItemStack(u == UnitType.BOWMAN ? Items.BOW : Items.STONE_SWORD));
            if (u == UnitType.BOWMAN) RecruitsBridge.addToPack(e, new ItemStack(Items.ARROW, 24));
            Settlement.Soldier so = new Settlement.Soldier();
            so.entity = e.getUUID();
            so.type = u;
            so.name = name;
            livingrealms.military.RecruitOrigins.sync(so, e);
            s.soldiers.add(so);
            Simulation.applyStance(level, s, e, so, s.soldiers.size() - 1);
            DecreeData.Levy v = new DecreeData.Levy();
            v.kingdom = k; v.city = s.id; v.until = until;
            data.levies.put(e.getUUID(), v);
            raised++;
        }
        if (raised > 0) { s.dirty = true; data.setDirty(); s.log(raised + (raised == 1 ? " levy has" : " levies have") + " answered the call to arms"); }
        return raised;
    }

    /** Every recruit the kingdom owns that is loaded: its towns' soldiers, and for a player the recruits hired the Recruits way near them. */
    static List<LivingEntity> recruits(MinecraftServer server, SettlementManager m, UUID k) {
        Set<UUID> seen = new HashSet<>();
        List<LivingEntity> out = new ArrayList<>();
        for (Settlement c : Statecraft.cities(m, k)) for (Settlement.Soldier so : c.soldiers) {
            Entity e = SoldierProgression.findEntity(server, so.entity);
            if (e instanceof LivingEntity le && e.isAlive() && seen.add(e.getUUID())) out.add(le);
        }
        ServerPlayer p = server.getPlayerList().getPlayer(k);
        if (p != null) for (LivingEntity e : p.serverLevel().getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(96),
                e -> RecruitsBridge.ownedBy(e, k) && !RecruitsBridge.isWorker(e))) if (seen.add(e.getUUID())) out.add(e);
        return out;
    }

    // ------------------------------------------------------------------ messages

    /** Chat line and toast for the ruler (an NPC kingdom's decree goes to the players at war with it), and a line in each city's log. */
    static void announce(MinecraftServer server, SettlementManager m, UUID k, Decree d, String what, boolean start) {
        String head = start ? d.label : d.label + " ends";
        for (Settlement c : Statecraft.cities(m, k)) c.log((start ? "Royal decree: " + d.label : d.label + " has ended") + (what.isEmpty() ? "" : " (" + what + ")"));
        List<ServerPlayer> to = new ArrayList<>();
        ServerPlayer ruler = server.getPlayerList().getPlayer(k);
        if (ruler != null) to.add(ruler);
        else if (start) for (Statecraft.War w : m.state.wars) if (w.involves(k)) {
            ServerPlayer enemy = server.getPlayerList().getPlayer(w.other(k));
            if (enemy != null) to.add(enemy);
        }
        String text = ruler == null && start ? Statecraft.name(m, k) + ": " + what : what;
        for (ServerPlayer p : to) {
            p.sendSystemMessage(Component.literal("[Decree] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(head).withStyle(start ? ChatFormatting.YELLOW : ChatFormatting.WHITE))
                    .append(Component.literal(text.isEmpty() ? "" : " · " + text).withStyle(ChatFormatting.GRAY)));
            if (p instanceof FakePlayer || p.connection == null) continue;
            CompoundTag t = new CompoundTag();
            t.putString("title", start ? "Royal decree: " + d.label : d.label + " has ended");
            t.putString("text", text);
            t.putString("icon", BuiltInRegistries.ITEM.getKey(d.icon).toString());
            t.putBoolean("big", start && d.tier == Tier.CAPITAL);
            Net.send(p, "quest", t);
        }
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++tick % 20 != 7) return;
        try { tick(e.getServer(), SettlementManager.get(e.getServer()), tick / 20); }
        catch (RuntimeException ex) { LivingRealms.LOG.error("Living Realms decrees step failed", ex); }
    }

    /** Once a second: decrees run out, War Horn steadies morale, Martial Law tends the garrison, levies muster and go home. */
    public static void tick(MinecraftServer server, SettlementManager m, long second) {
        DecreeData data = DecreeData.get(server);
        long now = server.overworld().getGameTime();
        for (var re : List.copyOf(data.realms.entrySet())) {
            UUID k = re.getKey();
            DecreeData.Realm r = re.getValue();
            Long martial = r.until.get(Decree.MARTIAL_LAW);
            if (martial != null && martial > now && raidOn(m, k) == null) r.until.put(Decree.MARTIAL_LAW, now);
            for (var de : List.copyOf(r.until.entrySet())) {
                if (de.getValue() > now) continue;
                r.until.remove(de.getKey());
                data.setDirty();
                announce(server, m, k, de.getKey(), "", false);
                for (Settlement c : Statecraft.cities(m, k)) c.dirty = true;
            }
            if (r.until.containsKey(Decree.WAR_HORN))
                for (LivingEntity e : recruits(server, m, k)) {
                    float morale = RecruitsBridge.morale(e);
                    if (morale < Decree.WAR_HORN_FLOOR) RecruitsBridge.addMorale(e, Decree.WAR_HORN_FLOOR - morale);
                }
            if (r.until.containsKey(Decree.MARTIAL_LAW) && second % 3 == 0) tend(server, m, k);
            r.ready.values().removeIf(t -> t <= now);
            if (r.until.isEmpty() && r.ready.isEmpty()) { data.realms.remove(k); data.setDirty(); }
        }
        for (Iterator<DecreeData.Muster> it = data.musters.iterator(); it.hasNext(); ) {
            DecreeData.Muster mu = it.next();
            Settlement s = m.get(mu.city);
            if (s == null || mu.until <= now || mu.count <= 0) { it.remove(); data.setDirty(); continue; }
            ServerLevel level = server.getLevel(s.dim);
            if (level == null || !level.hasChunkAt(s.center)) continue;
            mu.count -= muster(level, data, s, mu.kingdom, mu.count, mu.until);
            if (mu.count <= 0) it.remove();
            data.setDirty();
        }
        for (var le : List.copyOf(data.levies.entrySet())) {
            if (le.getValue().until > now) continue;
            disband(server, m, data, le.getKey(), now);
        }
        if (data.disband.values().removeIf(t -> now - t > DISBAND_FORGET)) data.setDirty();
    }

    /** Martial Law: the garrison of each raided city heals 1 HP (every 3 s). */
    static void tend(MinecraftServer server, SettlementManager m, UUID k) {
        for (Raids.Raid raid : m.raids) {
            if (!raid.defender.equals(k)) continue;
            Settlement t = m.get(raid.target);
            if (t == null) continue;
            for (Settlement.Soldier so : t.soldiers)
                if (SoldierProgression.findEntity(server, so.entity) instanceof LivingEntity le && le.isAlive() && le.getHealth() < le.getMaxHealth()) le.heal(1);
        }
    }

    /** A levy goes home: gone at once if loaded, otherwise the moment it loads. Its place in the garrison is freed now. */
    static void disband(MinecraftServer server, SettlementManager m, DecreeData data, UUID id, long now) {
        DecreeData.Levy v = data.levies.remove(id);
        Entity e = SoldierProgression.findEntity(server, id);
        if (e != null) {
            if (e.getVehicle() != null) e.stopRiding();
            e.discard();
        } else data.disband.put(id, now);
        forget(m, id, v == null ? null : v.city);
        data.setDirty();
    }

    static void forget(SettlementManager m, UUID id, @Nullable UUID city) {
        Settlement home = city == null ? null : m.get(city);
        if (home != null && home.soldiers.removeIf(so -> so.entity.equals(id))) { home.dirty = true; return; }
        for (Settlement s : m.all()) if (s.soldiers.removeIf(so -> so.entity.equals(id))) { s.dirty = true; return; }
    }

    /** A levy whose time is up never comes back: after a restart or from unloaded ground it is turned away as it loads. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onJoin(EntityJoinLevelEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !e.getEntity().getPersistentData().contains(LEVY_TAG)) return;
        MinecraftServer server = level.getServer();
        DecreeData data = DecreeData.get(server);
        UUID id = e.getEntity().getUUID();
        long now = server.overworld().getGameTime();
        if (!data.disband.containsKey(id) && e.getEntity().getPersistentData().getLong(LEVY_TAG) > now) return;
        e.setCanceled(true);
        e.getEntity().discard();
        data.disband.remove(id);
        data.levies.remove(id);
        data.setDirty();
        forget(SettlementManager.get(server), id, null);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity().level() instanceof ServerLevel level) || !e.getEntity().getPersistentData().contains(LEVY_TAG)) return;
        DecreeData data = DecreeData.get(level.getServer());
        if (data.levies.remove(e.getEntity().getUUID()) != null) data.setDirty();
    }

    // ------------------------------------------------------------------ hooks for the rest of the mod

    @Nullable
    static MinecraftServer server() { return ServerLifecycleHooks.getCurrentServer(); }

    /** War Horn damage for a recruit's hit: its own term, capped at +15%. */
    public static double warHorn(LivingEntity attacker) {
        if (!RecruitsBridge.isRecruit(attacker) || attacker.getServer() == null) return 0;
        UUID owner = RecruitsBridge.owner(attacker);
        return active(attacker.getServer(), owner, Decree.WAR_HORN) ? Decree.WAR_HORN_BONUS : 0;
    }

    /** Town mood from decrees in force (Simulation.happinessTarget). */
    public static double mood(Settlement s, List<String> why) {
        MinecraftServer server = server();
        if (server == null || s.mirror()) return 0;
        UUID k = s.kingdomId();
        double h = 0;
        if (active(server, k, Decree.ROYAL_FEAST)) { h += Decree.FEAST_MOOD; why.add("+" + (int) Decree.FEAST_MOOD + " royal feast"); }
        if (active(server, k, Decree.TAX_HOLIDAY)) { h += Decree.HOLIDAY_MOOD; why.add("+" + (int) Decree.HOLIDAY_MOOD + " tax holiday"); }
        if (active(server, k, Decree.ROYAL_LEVY)) { h += Decree.LEVY_MOOD; why.add((int) Decree.LEVY_MOOD + " royal levy"); }
        if (active(server, k, Decree.MARTIAL_LAW)) { h += Decree.MARTIAL_MOOD; why.add((int) Decree.MARTIAL_MOOD + " martial law"); }
        return h;
    }

    /** Head tax multiplier: none during a Tax Holiday. */
    public static double taxFactor(Settlement s) {
        MinecraftServer server = server();
        return server != null && active(server, s.kingdomId(), Decree.TAX_HOLIDAY) ? 0 : 1;
    }

    /** Daily loyalty of a conquered city (Loyalty.factors). */
    static void loyalty(Settlement s, List<Map.Entry<String, Double>> out) {
        MinecraftServer server = server();
        if (server == null) return;
        UUID k = s.kingdomId();
        if (active(server, k, Decree.ROYAL_FEAST)) out.add(Map.entry("royal feast", Decree.FEAST_LOYALTY));
        if (active(server, k, Decree.TAX_HOLIDAY)) out.add(Map.entry("tax holiday", Decree.HOLIDAY_LOYALTY));
        if (active(server, k, Decree.ROYAL_LEVY)) out.add(Map.entry("royal levy", Decree.LEVY_LOYALTY));
    }

    /** Royal Feast crowds: sickness likelier for its first half day (Hardships.sickFactors). */
    public static void sickness(Settlement s, long now, List<Map.Entry<String, Double>> out) {
        MinecraftServer server = server();
        if (server == null || s.mirror()) return;
        long until = until(server, s.kingdomId(), Decree.ROYAL_FEAST);
        if (until > now && now < until - Decree.ROYAL_FEAST.duration() + Decree.feastCrowds()) out.add(Map.entry("feast crowds", Decree.FEAST_SICKNESS));
    }

    /** Amnesty: NPC kingdoms weigh this kingdom's peace offers higher (Wars.accepts). */
    static double peaceBonus(UUID proposer) { return active(proposer, Decree.AMNESTY) ? Decree.AMNESTY_PEACE : 0; }

    /** Martial Law: the breach of a city of this kingdom goes slower (Siege.after). */
    static double breachFactor(Settlement target) { return active(target.kingdomId(), Decree.MARTIAL_LAW) ? Decree.MARTIAL_BREACH : 1; }

    // ------------------------------------------------------------------ City Manager

    /** The Decrees card of the Kingdom page. */
    public static CompoundTag view(ServerPlayer p, SettlementManager m, Settlement s) {
        CompoundTag t = new CompoundTag();
        MinecraftServer server = p.server;
        UUID k = s.kingdomId();
        long now = server.overworld().getGameTime();
        t.putString("rank", rank(m, k).label);
        t.putBoolean("researched", researched(m, k, Decree.RESEARCH));
        ListTag list = new ListTag();
        for (Decree d : Decree.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", d.name());
            c.putString("name", d.label);
            c.putString("desc", d.description);
            c.putString("tier", d.tier.label);
            c.putString("icon", BuiltInRegistries.ITEM.getKey(d.icon).toString());
            c.putString("cost", cost(m, k, d).text());
            c.putString("cooldown", minutes(d.cooldown()));
            long until = until(server, k, d), ready = readyAt(server, k, d);
            boolean locked = rank(m, k).ordinal() < d.tier.ordinal();
            c.putString("state", until > now ? "active" : locked ? "locked" : ready > now ? "cooldown" : "ready");
            c.putInt("activeMin", (int) Math.max(0, (until - now + 1199) / 1200));
            c.putInt("readyMin", (int) Math.max(0, (ready - now + 1199) / 1200));
            String why = s.mirror() ? "Village Expansion runs this village" : blocker(server, m, s, d);
            c.putString("why", why == null ? "" : why);
            list.add(c);
        }
        t.put("list", list);
        int levies = 0;
        for (DecreeData.Levy v : DecreeData.get(server).levies.values()) if (v.kingdom.equals(k)) levies++;
        t.putInt("levies", levies);
        return t;
    }

    private Decrees() {}
}
