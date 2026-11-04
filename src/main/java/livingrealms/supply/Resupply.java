package livingrealms.supply;

import livingrealms.LivingRealms;
import livingrealms.military.BattleAI;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierProgression;
import livingrealms.military.UnitType;
import livingrealms.military.WeaponProficiency;
import livingrealms.settlement.Settlement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Auto-resupply (docs/resupply.md). Out of combat, each owned recruit is looked at once every {@link #PERIOD} ticks,
 * staggered by UUID: it tops up arrows and food, swaps or mends gear worn below a quarter, and replaces a broken weapon,
 * shield or armour piece. Sources in order: its Living Realms town's armoury, the storage Recruits' upkeep command gave it,
 * then its owner's inventory if the owner is near and allows it.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class Resupply {
    public static final int PERIOD = 100, AMMO_TARGET = 64, AMMO_REFILL = 48, FOOD_TARGET = 8, FOOD_REFILL = 4;
    public static final double OWNER_RANGE = 16, STORE_RANGE = 48;
    /** Durability left (fraction) at or below which gear counts as worn. */
    public static final float WORN = 0.25f;
    public static final long WARN_TICKS = 3 * 60 * 20, CALM_TICKS = 200;
    static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static final List<Map<UUID, Entity>> BUCKETS = new ArrayList<>();
    static { for (int i = 0; i < PERIOD; i++) BUCKETS.add(new LinkedHashMap<>()); }
    static final Map<UUID, Long> LAST_WARN = new HashMap<>();
    /** Warnings sent, and the last one, for the self-test. */
    public static int warnings;
    public static String lastWarning = "";

    // ------------------------------------------------------------------ scheduling

    static int bucket(UUID id) { return Math.floorMod(id.hashCode(), PERIOD); }

    /** True on the one tick in each PERIOD when this recruit is checked. */
    public static boolean due(UUID id, long tick) { return Math.floorMod(tick, PERIOD) == bucket(id); }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !RecruitsBridge.isRecruit(e.getEntity()) || RecruitsBridge.isWorker(e.getEntity())) return;
        BUCKETS.get(bucket(e.getEntity().getUUID())).put(e.getEntity().getUUID(), e.getEntity());
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide()) return;
        BUCKETS.get(bucket(e.getEntity().getUUID())).remove(e.getEntity().getUUID(), e.getEntity());
    }

    @SubscribeEvent
    public static void died(LivingDeathEvent e) {
        if (e.getEntity().level() instanceof ServerLevel level && RecruitsBridge.isRecruit(e.getEntity()))
            SupplyData.get(level.getServer()).forget(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent e) { BUCKETS.forEach(Map::clear); LAST_WARN.clear(); }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        Map<UUID, Entity> due = BUCKETS.get(server.getTickCount() % PERIOD);
        if (due.isEmpty()) return;
        long now = server.overworld().getGameTime();
        for (Entity e : new ArrayList<>(due.values())) {
            if (e.isRemoved() || !e.isAlive()) continue;
            UUID owner = RecruitsBridge.owner(e);
            if (owner == null) continue;
            try {
                check(e, server.getPlayerList().getPlayer(owner), now);
            } catch (Exception ex) {
                LivingRealms.LOG.error("Resupply of {} failed", e.getName().getString(), ex);
            }
        }
    }

    // ------------------------------------------------------------------ one recruit

    /** Out of combat: no target, not in a Living Realms battle, not hurt for ten seconds, not eating. */
    public static boolean calm(Entity e) {
        if (BattleAI.fighting(e)) return false;
        if (e instanceof LivingEntity le && le.isUsingItem()) return false;
        if (e instanceof Mob m && m.getTarget() != null) return false;
        return !(e instanceof LivingEntity le) || le.getLastHurtByMob() == null || le.tickCount - le.getLastHurtByMobTimestamp() > CALM_TICKS;
    }

    /** The town record, when it is a player's Living Realms town (not an NPC town or a Village Expansion mirror). */
    @Nullable
    static Settlement town(MinecraftServer server, Entity e) {
        SoldierProgression.Record rec = SoldierProgression.record(server, e);
        if (rec == null || rec.settlement().mirror() || rec.settlement().isAi()) return null;
        return rec.settlement();
    }

    /** The sources this recruit can draw on right now, in priority order. */
    public static List<SupplySource> sources(Entity e, @Nullable ServerPlayer owner) {
        MinecraftServer server = e.getServer();
        List<SupplySource> out = new ArrayList<>();
        Settlement s = town(server, e);
        if (s != null && s.dim.equals(e.level().dimension()) && s.contains(e.blockPosition())) out.add(new Sources.Stockpile(s));
        Container store = upkeep(e);
        if (store != null) out.add(new Sources.Box(store, "its upkeep chest"));
        if (ownerNear(e, owner) && SupplyData.get(server).allowsInventory(owner.getUUID())) out.add(new Sources.Owner(owner));
        return out;
    }

    static boolean ownerNear(Entity e, @Nullable ServerPlayer owner) {
        return owner != null && owner.isAlive() && !owner.isSpectator() && owner.level() == e.level() && owner.distanceToSqr(e) <= OWNER_RANGE * OWNER_RANGE;
    }

    /** Recruits' upkeep storage, when it is loaded and within STORE_RANGE. */
    @Nullable
    static Container upkeep(Entity e) {
        if (!(e.level() instanceof ServerLevel level)) return null;
        BlockPos pos = RecruitsBridge.upkeepPos(e);
        if (pos != null && level.isLoaded(pos) && pos.closerToCenterThan(e.position(), STORE_RANGE)) {
            Container c = HopperBlockEntity.getContainerAt(level, pos);
            if (c != null) return c;
        }
        UUID id = RecruitsBridge.upkeepEntity(e);
        Entity cart = id == null ? null : level.getEntity(id);
        if (cart instanceof Container c && cart.distanceTo(e) <= STORE_RANGE) return c;
        return null;
    }

    /**
     * Checks one recruit and restocks it from its sources. Owner is the online owner or null. Returns true if anything
     * changed hands. The tick handler calls this for each recruit once per PERIOD; the self-test calls it directly.
     */
    public static boolean check(Entity e, @Nullable ServerPlayer owner, long now) {
        if (!RecruitsBridge.isRecruit(e) || !(e instanceof LivingEntity le) || !e.isAlive()) return false;
        MinecraftServer server = e.getServer();
        SoldierProgression.Record rec = SoldierProgression.record(server, e);
        if (rec != null && rec.settlement().isAi()) return false; // NPC towns supply their own guards
        SupplyData data = SupplyData.get(server);
        SupplyData.Rec r = data.rec(e.getUUID());
        remember(data, r, e);
        if (!r.auto || !calm(e)) return false;
        List<SupplySource> sources = sources(e, owner);
        List<String> lacking = new ArrayList<>();
        List<String> took = new ArrayList<>();
        boolean acted = false;
        // ammunition
        if (ranged(e)) {
            Predicate<ItemStack> ammo = ammo(e);
            int have = countPack(e, ammo);
            if (have < AMMO_REFILL) {
                int got = fill(e, sources, ammo, st -> st.is(Items.ARROW) ? 1 : 0, AMMO_TARGET - have, took, ammoName(e));
                acted |= got > 0;
                if (have + got == 0) lacking.add(ammoName(e));
            }
        }
        // food
        Predicate<ItemStack> food = st -> food(e, st);
        int meals = countPack(e, food);
        if (meals < FOOD_REFILL) {
            int got = fill(e, sources, food, Resupply::nourishment, FOOD_TARGET - meals, took, "food");
            acted |= got > 0;
            if (meals + got == 0) lacking.add("food");
        }
        // gear
        List<SupplySource> withPack = new ArrayList<>();
        Container inv = RecruitsBridge.inventory(e);
        if (inv != null) withPack.add(new Sources.Box(inv, "its pack", 6, inv.getContainerSize(), i -> false));
        withPack.addAll(sources);
        for (int i = 0; i < SLOTS.length; i++) {
            String need = gear(e, r, i, withPack, sources, took);
            if (need == null) acted = true;
            else if (!need.isEmpty()) lacking.add(need);
        }
        if (acted) remember(data, r, e);
        r.shortages = lacking;
        if (!took.isEmpty()) { r.note = String.join("; ", took); r.noteAt = now; }
        if (!lacking.isEmpty()) warn(e, owner, lacking, sources, now);
        return acted;
    }

    /** Records sound gear in each slot, so a piece that breaks is replaced in kind. */
    static void remember(SupplyData data, SupplyData.Rec r, Entity e) {
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack st = RecruitsBridge.getSlot(e, SLOTS[i]);
            if (kind(st) == null) continue;
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            if (!id.equals(r.last[i])) { r.last[i] = id; data.setDirty(); }
        }
    }

    /** Moves up to n matching items into the pack. Returns how many arrived. */
    static int fill(Entity e, List<SupplySource> sources, Predicate<ItemStack> want, ToDoubleFunction<ItemStack> prefer, int n, List<String> took, String what) {
        int got = 0;
        for (SupplySource src : sources) {
            if (got >= n) break;
            int from = 0;
            for (ItemStack st : src.take(want, prefer, n - got)) {
                int count = st.getCount();
                ItemStack left = RecruitsBridge.addToPack(e, st);
                from += count - left.getCount();
                if (!left.isEmpty()) {
                    ItemStack lost = src.putBack(left);
                    if (!lost.isEmpty()) e.spawnAtLocation(lost);
                }
            }
            if (from > 0) took.add(from + " " + what + " from " + src.label());
            got += from;
        }
        return got;
    }

    /**
     * One equipment slot. Returns null if it was fixed, "" if nothing was needed, or what is lacking
     * ("a new Iron Sword", "a spare Iron Helmet") for the warning.
     */
    @Nullable
    static String gear(Entity e, SupplyData.Rec r, int i, List<SupplySource> withPack, List<SupplySource> sources, List<String> took) {
        EquipmentSlot slot = SLOTS[i];
        ItemStack cur = RecruitsBridge.getSlot(e, slot);
        if (cur.isEmpty()) {
            Item last = expected(e, r, i);
            if (last == null) return "";
            String k = kind(new ItemStack(last));
            if (k == null) return "";
            for (SupplySource src : withPack) {
                List<ItemStack> got = src.take(st -> k.equals(kind(st)) && !worn(st), Resupply::tier, 1);
                if (got.isEmpty()) continue;
                RecruitsBridge.setSlot(e, slot, got.get(0));
                took.add("a new " + got.get(0).getHoverName().getString() + " from " + src.label());
                return null;
            }
            // a unit's default weapon is only a hint: no warning until it has actually carried one
            return r.last[i].isEmpty() ? "" : "a new " + new ItemStack(last).getHoverName().getString();
        }
        if (!worn(cur) || kind(cur) == null) return "";
        String k = kind(cur);
        double tier = tier(cur);
        // enchanted or named gear is mended first; plain gear is swapped first
        boolean keep = cur.isEnchanted() || cur.hasCustomHoverName();
        if (keep && mend(e, slot, cur, sources, took)) return null;
        for (SupplySource src : withPack) {
            List<ItemStack> got = src.take(st -> k.equals(kind(st)) && !worn(st) && tier(st) >= tier, Resupply::tier, 1);
            if (got.isEmpty()) continue;
            ItemStack old = cur.copy();
            RecruitsBridge.setSlot(e, slot, got.get(0));
            if (!src.keepWorn(old)) {
                ItemStack left = RecruitsBridge.addToPack(e, old);
                if (!left.isEmpty()) e.spawnAtLocation(left);
            }
            took.add("swapped a worn " + old.getHoverName().getString() + " for " + got.get(0).getHoverName().getString() + " from " + src.label());
            return null;
        }
        if (!keep && mend(e, slot, cur, sources, took)) return null;
        return "a spare " + cur.getHoverName().getString();
    }

    static boolean mend(Entity e, EquipmentSlot slot, ItemStack cur, List<SupplySource> sources, List<String> took) {
        ItemStack st = cur.copy();
        for (SupplySource src : sources) {
            if (!src.repair(st)) continue;
            RecruitsBridge.setSlot(e, slot, st);
            took.add("the smiths mended " + st.getHoverName().getString() + " (" + src.label() + ")");
            return true;
        }
        return false;
    }

    /** What belongs in an empty slot: what was there before it broke, or the unit's own weapon and shield. */
    @Nullable
    static Item expected(Entity e, SupplyData.Rec r, int i) {
        if (!r.last[i].isEmpty()) {
            Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(r.last[i]));
            if (it != Items.AIR) return it;
        }
        UnitType u = RecruitsBridge.unitOf(e);
        if (i == 0) return switch (u) { case BOWMAN, DRAGON_RIDER -> Items.BOW; case CROSSBOWMAN -> Items.CROSSBOW; default -> Items.IRON_SWORD; };
        if (i == 1 && u == UnitType.SHIELDBEARER) return Items.SHIELD;
        return null;
    }

    // ------------------------------------------------------------------ what things are

    /** Gear families: a swap or replacement must be of the same one. Null for anything that isn't gear. */
    @Nullable
    public static String kind(ItemStack st) {
        if (st.isEmpty() || !st.isDamageableItem()) return null;
        if (st.getItem() instanceof ArmorItem a) return "armor:" + a.getEquipmentSlot().getName();
        if (st.getItem() instanceof ShieldItem) return "shield";
        WeaponProficiency w = WeaponProficiency.classify(st);
        return w == null ? null : "weapon:" + w.name();
    }

    /** Higher is better within a family: armour points, attack damage, or durability for bows and shields. */
    public static double tier(ItemStack st) {
        if (st.getItem() instanceof ArmorItem a) return a.getDefense() * 100 + a.getToughness() * 10 + a.getMaxDamage() / 1000.0;
        double dmg = st.getItem().getDefaultAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE).stream().mapToDouble(AttributeModifier::getAmount).sum();
        return dmg * 1000 + st.getMaxDamage();
    }

    /** Damageable gear with a quarter or less of its durability left. */
    public static boolean worn(ItemStack st) {
        return st.isDamageableItem() && st.getMaxDamage() > 0 && (st.getMaxDamage() - st.getDamageValue()) <= st.getMaxDamage() * WORN;
    }

    public static boolean ranged(Entity e) {
        UnitType u = RecruitsBridge.unitOf(e);
        return RecruitsBridge.ranged(e) || u == UnitType.BOWMAN || u == UnitType.CROSSBOWMAN;
    }

    /** What the recruit's bow or crossbow shoots, fireworks excepted. */
    static Predicate<ItemStack> ammo(Entity e) {
        ItemStack main = e instanceof LivingEntity le ? le.getMainHandItem() : ItemStack.EMPTY;
        Predicate<ItemStack> base = main.getItem() instanceof ProjectileWeaponItem w ? w.getAllSupportedProjectiles() : ProjectileWeaponItem.ARROW_ONLY;
        return st -> !st.isEmpty() && base.test(st) && !(st.getItem() instanceof FireworkRocketItem);
    }

    static String ammoName(Entity e) {
        boolean crossbow = e instanceof LivingEntity le && le.getMainHandItem().getItem() instanceof CrossbowItem
                || RecruitsBridge.unitOf(e) == UnitType.CROSSBOWMAN;
        return crossbow ? "bolts" : "arrows";
    }

    /** Plain food Recruits will eat: nothing with effects, so no rotten flesh and no golden apples. */
    static boolean food(Entity e, ItemStack st) {
        if (!RecruitsBridge.canEat(e, st)) return false;
        FoodProperties f = st.getItem().getFoodProperties(st, e instanceof LivingEntity le ? le : null);
        return f != null && f.getEffects().isEmpty();
    }

    static double nourishment(ItemStack st) {
        FoodProperties f = st.getItem().getFoodProperties();
        return f == null ? 0 : f.getNutrition() + f.getNutrition() * f.getSaturationModifier() * 2;
    }

    /** Matching items in the pack (slot 6 on; the off hand holds food only while eating). */
    static int countPack(Entity e, Predicate<ItemStack> want) {
        Container inv = RecruitsBridge.inventory(e);
        if (inv == null) return 0;
        int n = 0;
        for (int i = 6; i < inv.getContainerSize(); i++) if (want.test(inv.getItem(i))) n += inv.getItem(i).getCount();
        return n;
    }

    // ------------------------------------------------------------------ warnings

    static String name(Entity e) {
        String n = e.getName().getString(), unit = RecruitsBridge.unitOf(e).label;
        return n.startsWith(unit) ? n : unit + " " + n;
    }

    /** "Bowman Aldric is out of arrows and the armoury has none", at most once per WARN_TICKS per owner. */
    static void warn(Entity e, @Nullable ServerPlayer owner, List<String> shortages, List<SupplySource> tried, long now) {
        if (owner == null) return;
        Long last = LAST_WARN.get(owner.getUUID());
        if (last != null && now - last < WARN_TICKS && now >= last) return;
        LAST_WARN.put(owner.getUUID(), now);
        String msg = name(e) + " " + needs(shortages) + " and " + where(tried);
        warnings++;
        lastWarning = msg;
        owner.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD));
    }

    static String needs(List<String> shortages) {
        List<String> out = new ArrayList<>(), want = new ArrayList<>();
        for (String s : shortages) (s.startsWith("a ") ? want : out).add(s);
        List<String> parts = new ArrayList<>();
        if (!out.isEmpty()) parts.add("is out of " + list(out, "and"));
        if (!want.isEmpty()) parts.add("needs " + list(want, "and"));
        return String.join(" and ", parts);
    }

    static String where(List<SupplySource> tried) {
        List<String> names = tried.stream().map(SupplySource::label).toList();
        if (names.isEmpty()) return "has nothing to restock from (no town armoury, upkeep chest or your inventory nearby)";
        if (names.size() == 1) return names.get(0) + " has none";
        if (names.size() == 2) return "neither " + names.get(0) + " nor " + names.get(1) + " has any";
        return "none of " + list(names, "or") + " has any";
    }

    static String list(List<String> l, String last) {
        if (l.size() == 1) return l.get(0);
        return String.join(", ", l.subList(0, l.size() - 1)) + " " + last + " " + l.get(l.size() - 1);
    }

    // ------------------------------------------------------------------ the screens

    /** The Supply section of the Recruit Command overview. */
    public static CompoundTag describe(ServerPlayer p, Entity e) {
        CompoundTag t = new CompoundTag();
        SupplyData data = SupplyData.get(p.server);
        SupplyData.Rec r = data.peek(e.getUUID());
        t.putBoolean("auto", data.auto(e.getUUID()));
        t.putBoolean("ownerInv", data.allowsInventory(p.getUUID()));
        boolean ranged = ranged(e);
        t.putBoolean("ranged", ranged);
        if (ranged) { t.putInt("ammo", countPack(e, ammo(e))); t.putString("ammoName", ammoName(e)); }
        t.putInt("ammoTarget", AMMO_TARGET);
        t.putInt("food", countPack(e, st -> food(e, st)));
        t.putInt("foodTarget", FOOD_TARGET);
        float worst = 1;
        String piece = "";
        int empty = 0;
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack st = RecruitsBridge.getSlot(e, SLOTS[i]);
            if (st.isEmpty()) { if (r != null && !r.last[i].isEmpty()) empty++; continue; }
            if (!st.isDamageableItem() || st.getMaxDamage() <= 0) continue;
            float f = 1 - st.getDamageValue() / (float) st.getMaxDamage();
            if (f < worst) { worst = f; piece = st.getHoverName().getString(); }
        }
        t.putFloat("gear", worst);
        t.putString("gearPiece", piece);
        t.putInt("broken", empty);
        List<String> from = new ArrayList<>();
        for (SupplySource s : sources(e, p)) from.add(s.label());
        if (!data.allowsInventory(p.getUUID()) && ownerNear(e, p)) from.add("your inventory (off)");
        t.putString("sources", String.join(" · ", from));
        if (r != null) {
            ListTag l = new ListTag();
            for (String s : r.shortages) l.add(StringTag.valueOf(s));
            t.put("short", l);
            if (!r.note.isEmpty()) { t.putString("note", r.note); t.putLong("noteAge", Math.max(0, (p.server.overworld().getGameTime() - r.noteAt) / 20)); }
        }
        return t;
    }

    /** supplyAuto {on} for this recruit, supplyOwner {on} for the player's own inventory. */
    @Nullable
    public static String handle(ServerPlayer p, Entity e, String op, CompoundTag d) {
        SupplyData data = SupplyData.get(p.server);
        if (op.equals("supplyAuto")) data.setAuto(e.getUUID(), d.getBoolean("on"));
        else if (op.equals("supplyOwner")) data.setAllowsInventory(p.getUUID(), d.getBoolean("on"));
        return null;
    }

    /** One line for the City Manager garrison card, or "" when every soldier in town is supplied. */
    public static String townWarning(MinecraftServer server, Settlement s) {
        SupplyData data = SupplyData.get(server);
        int n = 0;
        Set<String> what = new TreeSet<>();
        for (Settlement.Soldier so : s.soldiers) {
            SupplyData.Rec r = data.peek(so.entity);
            if (r == null || r.shortages.isEmpty()) continue;
            n++;
            for (String x : r.shortages) what.add(x.startsWith("a ") ? "gear" : x);
        }
        if (n == 0) return "";
        return n + (n == 1 ? " soldier is" : " soldiers are") + " low on supplies: " + String.join(", ", what);
    }

    private Resupply() {}
}
