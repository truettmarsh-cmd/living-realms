package livingrealms.settlement;

import livingrealms.LivingRealms;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierProgression;
import livingrealms.military.SoldierRanks;
import livingrealms.military.WeaponProficiency;
import livingrealms.smithing.Piece;
import livingrealms.smithing.Smithing;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Merit is a soldier's current form. A hostile kill +10; every 10 damage dealt to hostiles +1 (at most 2 per hit);
 * a raid defended or won +25; Royal Guard duty +6 a day; every day alive in service +2. Merit decays 15% per Minecraft
 * day, so an idle soldier settles near 12 and a Royal Guard near 49. At 60 merit a soldier in a player town may raise
 * one request: their favourite weapon family, or better armour when theirs is 4+ points below the best the town's
 * research allows. At most 3 open requests per town; unanswered ones lapse after 5 days (10 once ordered); a soldier
 * waits 3 days after any answer. Granting lifts Recruits morale by 10, declining drops it by 8.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class Merit {
    public static final float KILL = 10, DAMAGE_PER_POINT = 10, MAX_HIT = 2, RAID = 25, GUARD_DAY = 6, SURVIVE_DAY = 2, DECAY = .85f;
    public static final float THRESHOLD = 60, GRANT_MORALE = 10, DECLINE_MORALE = -8;
    public static final int TOWN_CAP = 3, ARMOUR_GAP = 4, BOARD = 10, TICKS = 100;
    public static final long DAY = SoldierRanks.DAY, EXPIRY = 5 * DAY, COOLDOWN = 3 * DAY;
    static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    public enum Want { NONE, WEAPON, ARMOUR }

    /** Saved inside each soldier's tag; every field defaults sensibly for older saves. */
    public static final class Record {
        public float merit;
        public long updated = -1, killDay, featAt, wantAt, cooldownUntil;
        public final int[] dayKills = new int[7];
        public String feat = "";
        public Want want = Want.NONE;
        public boolean ordered;
    }

    public static void save(Record r, CompoundTag t) {
        t.putFloat("merit", r.merit);
        CompoundTag m = new CompoundTag();
        m.putLong("updated", r.updated); m.putLong("killDay", r.killDay); m.putIntArray("dayKills", r.dayKills);
        m.putString("feat", r.feat); m.putLong("featAt", r.featAt);
        m.putString("want", r.want.name()); m.putLong("wantAt", r.wantAt); m.putLong("cooldownUntil", r.cooldownUntil); m.putBoolean("ordered", r.ordered);
        t.put("meritState", m);
    }

    public static void load(Record r, CompoundTag t) {
        r.merit = Math.max(0, Math.min(1000, t.getFloat("merit")));
        if (!t.contains("meritState")) return;
        CompoundTag m = t.getCompound("meritState");
        r.updated = m.getLong("updated"); r.killDay = m.getLong("killDay");
        int[] k = m.getIntArray("dayKills");
        for (int i = 0; i < Math.min(k.length, 7); i++) r.dayKills[i] = Math.max(0, k[i]);
        r.feat = m.getString("feat"); r.featAt = m.getLong("featAt");
        try { r.want = Want.valueOf(m.getString("want")); } catch (Exception e) { r.want = Want.NONE; }
        r.wantAt = m.getLong("wantAt"); r.cooldownUntil = m.getLong("cooldownUntil"); r.ordered = m.getBoolean("ordered");
    }

    // ------------------------------------------------------------------ earning and decay

    /** Applies decay up to now. */
    public static void decay(Record r, long now) {
        if (r.updated >= 0 && now > r.updated) r.merit *= (float) Math.pow(DECAY, (now - r.updated) / (double) DAY);
        if (r.updated < 0 || now > r.updated) r.updated = now;
    }

    public static void add(Record r, float amount, long now) { decay(r, now); r.merit = Math.min(1000, r.merit + amount); }

    static void rollKills(Record r, long now) {
        long day = now / DAY;
        if (day - r.killDay >= 7) Arrays.fill(r.dayKills, 0);
        else for (long d = r.killDay + 1; d <= day; d++) r.dayKills[(int) (d % 7)] = 0;
        if (day > r.killDay) r.killDay = day;
    }

    public static int weekKills(Record r, long now) { rollKills(r, now); return Arrays.stream(r.dayKills).sum(); }

    public static void kill(Record r, long now) { rollKills(r, now); r.dayKills[(int) (now / DAY % 7)]++; add(r, KILL, now); }

    public static void damage(Record r, float dealt, long now) { if (dealt > 0) add(r, Math.min(MAX_HIT, dealt / DAMAGE_PER_POINT), now); }

    public static void feat(Record r, String feat, long now) { add(r, RAID, now); r.feat = feat; r.featAt = now; }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void killed(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Enemy) || !(event.getEntity().level() instanceof ServerLevel level)) return;
        Entity killer = event.getSource().getEntity();
        var rec = killer == null ? null : SoldierProgression.record(level.getServer(), killer);
        if (rec == null) return;
        kill(rec.soldier().merit, now(level.getServer()));
        rec.settlement().dirty = true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void hurt(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        if (event.isCanceled() || !(target instanceof Enemy) || !(target.level() instanceof ServerLevel level)) return;
        Entity attacker = event.getSource().getEntity();
        if (attacker == null || attacker == target || !RecruitsBridge.isRecruit(attacker)) return;
        var rec = SoldierProgression.record(level.getServer(), attacker);
        if (rec != null) damage(rec.soldier().merit, Math.min(event.getAmount(), target.getHealth()), now(level.getServer()));
    }

    /** Called when a raid ends: survivors on the winning side earn a feat. */
    public static void raidEnded(ServerLevel level, Settlement s, Set<UUID> attackers, boolean defending, boolean taken, @Nullable Settlement other) {
        if (defending == taken) return;
        long now = now(level.getServer());
        String feat = defending ? "defended " + s.name : "raided " + (other == null ? "a rival" : other.name);
        for (Settlement.Soldier so : s.soldiers) {
            Entity e = level.getEntity(so.entity);
            if (e instanceof LivingEntity le && le.isAlive() && (defending ? s.contains(e.blockPosition()) : attackers.contains(so.entity))) {
                feat(so.merit, feat, now);
                s.dirty = true;
            }
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % TICKS != 0) return;
        MinecraftServer server = event.getServer();
        long now = now(server);
        for (Settlement s : SettlementManager.get(server).all())
            for (Settlement.Soldier so : List.copyOf(s.soldiers)) update(s, so, so.seen ? SoldierProgression.findEntity(server, so.entity) : null, now, TICKS);
    }

    /** Duty accrual, decay, and the request lifecycle for one soldier. */
    public static void update(Settlement s, Settlement.Soldier so, @Nullable Entity e, long now, int ticks) {
        Record r = so.merit;
        boolean alive = e instanceof LivingEntity le && le.isAlive();
        add(r, alive ? (SURVIVE_DAY + (so.royalGuard ? GUARD_DAY : 0)) * ticks / (float) DAY : 0, now);
        if (r.want != Want.NONE) {
            if (alive && satisfied(s, so, e, r.want)) {
                close(s, so, now, 0, null);
                s.log(so.name + " is content with " + (r.want == Want.WEAPON ? "a new " + family(so.preferredWeapon) : "the new armour"));
            } else if (now - r.wantAt > (r.ordered ? 2 * EXPIRY : EXPIRY)) {
                close(s, so, now, 0, null);
                s.log(so.name + " withdrew an unanswered request");
            }
            return;
        }
        if (!alive || s.owner == null || r.merit < THRESHOLD || now < r.cooldownUntil || open(s) >= TOWN_CAP) return;
        Want want = desire(s, so, e);
        if (want == Want.NONE) return;
        r.want = want; r.wantAt = now; r.ordered = false;
        String msg = so.name + " (merit " + Math.round(r.merit) + ") asks for "
                + (want == Want.WEAPON ? article(family(so.preferredWeapon)) + ", their favourite weapon" : "better armour");
        s.log(msg);
        tellOwner(s, msg + ". See Military → Honours.");
        s.dirty = true;
    }

    // ------------------------------------------------------------------ what soldiers want

    static int open(Settlement s) { return (int) s.soldiers.stream().filter(so -> so.merit.want != Want.NONE).count(); }

    static Want desire(Settlement s, Settlement.Soldier so, Entity e) {
        WeaponProficiency.initialise(so);
        WeaponProficiency fav = so.preferredWeapon;
        if (fav != null && WeaponProficiency.classify(RecruitsBridge.getSlot(e, EquipmentSlot.MAINHAND)) != fav && obtainable(s, fav)) return Want.WEAPON;
        if (armour(e) + ARMOUR_GAP <= bestArmour(s)) return Want.ARMOUR;
        return Want.NONE;
    }

    static boolean satisfied(Settlement s, Settlement.Soldier so, Entity e, Want want) {
        return want == Want.WEAPON ? WeaponProficiency.classify(RecruitsBridge.getSlot(e, EquipmentSlot.MAINHAND)) == so.preferredWeapon
                : armour(e) + ARMOUR_GAP > bestArmour(s);
    }

    public static String text(Settlement.Soldier so) {
        return switch (so.merit.want) {
            case WEAPON -> "Wants " + article(family(so.preferredWeapon)) + " (favourite weapon)";
            case ARMOUR -> "Wants better armour";
            default -> "";
        };
    }

    static String article(String noun) { return ("aeiou".indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun; }

    public static String family(@Nullable WeaponProficiency w) {
        return w == null ? "weapon" : switch (w) {
            case SWORD -> "sword"; case GREATSWORD -> "greatsword"; case DAGGER -> "dagger"; case SPEAR -> "spear";
            case AXE -> "axe"; case MACE -> "mace"; case BOW -> "bow"; case CROSSBOW -> "crossbow";
        };
    }

    static boolean fits(Item item, WeaponProficiency fav) { return WeaponProficiency.classify(new ItemStack(item)) == fav; }

    static boolean obtainable(Settlement s, WeaponProficiency fav) { return stockWeapon(s, fav) != null || weaponPattern(s, fav) != null; }

    static int defence(ItemStack st) { return st.getItem() instanceof ArmorItem a ? a.getDefense() : 0; }

    static int armour(Entity e) {
        int n = 0;
        for (EquipmentSlot slot : ARMOUR) n += defence(RecruitsBridge.getSlot(e, slot));
        return n;
    }

    /** The best total armour the town's researched smithing patterns could make, slot by slot. */
    static int bestArmour(Settlement s) {
        int total = 0;
        for (Piece p : Piece.ARMOR) {
            int best = 0;
            for (var pat : Smithing.patterns().values()) {
                var r = pat.pieces().get(p);
                if (r != null && s.has(r.research())) best = Math.max(best, defence(new ItemStack(r.item())));
            }
            total += best;
        }
        return total;
    }

    static double attack(Item item) {
        return item.getDefaultAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE).stream().mapToDouble(AttributeModifier::getAmount).sum();
    }

    @Nullable
    static Item stockWeapon(Settlement s, WeaponProficiency fav) {
        Item best = null;
        for (var en : s.stock.view().object2IntEntrySet())
            if (en.getIntValue() > 0 && fits(en.getKey(), fav) && (best == null || attack(en.getKey()) > attack(best))) best = en.getKey();
        return best;
    }

    /** The researched pattern and piece making the hardest-hitting weapon of a family. */
    @Nullable
    static Map.Entry<Smithing.Pattern, Piece> weaponPattern(Settlement s, WeaponProficiency fav) {
        Map.Entry<Smithing.Pattern, Piece> best = null;
        double dmg = -1;
        for (var pat : Smithing.patterns().values())
            for (var en : pat.pieces().entrySet())
                if (!en.getKey().armor() && s.has(en.getValue().research()) && fits(en.getValue().item(), fav) && attack(en.getValue().item()) > dmg) {
                    dmg = attack(en.getValue().item());
                    best = Map.entry(pat, en.getKey());
                }
        return best;
    }

    @Nullable
    static Smithing.Pattern armourPattern(Settlement s) {
        Smithing.Pattern best = null;
        int most = 0;
        for (var pat : Smithing.patterns().values()) {
            int sum = 0;
            for (Piece p : Piece.ARMOR) {
                var r = pat.pieces().get(p);
                if (r != null && s.has(r.research())) sum += defence(new ItemStack(r.item()));
            }
            if (sum > most) { most = sum; best = pat; }
        }
        return best;
    }

    @Nullable
    static Item stockArmour(Settlement s, EquipmentSlot slot, int better) {
        Item best = null;
        for (var en : s.stock.view().object2IntEntrySet())
            if (en.getIntValue() > 0 && en.getKey() instanceof ArmorItem a && a.getEquipmentSlot() == slot && a.getDefense() > better
                    && (best == null || a.getDefense() > ((ArmorItem) best).getDefense())) best = en.getKey();
        return best;
    }

    // ------------------------------------------------------------------ answering requests

    /** grantRequest, orderRequest, declineRequest {soldier: UUID}; owner only. */
    @Nullable
    public static String handle(ServerPlayer player, Settlement s, String action, CompoundTag d) {
        if (!player.getUUID().equals(s.owner)) return "Only this town's owner can answer recruits' requests";
        Settlement.Soldier so = Actions.soldier(s, d);
        if (so == null || so.merit.want == Want.NONE) return "That request is no longer open";
        long now = now(player.server);
        return switch (action) {
            case "grantRequest" -> grant(s, so, SoldierProgression.findEntity(player.server, so.entity), now);
            case "orderRequest" -> order(s, so, now);
            default -> { close(s, so, now, DECLINE_MORALE, SoldierProgression.findEntity(player.server, so.entity)); s.log("You declined " + so.name + "'s request"); yield null; }
        };
    }

    @Nullable
    static String grant(Settlement s, Settlement.Soldier so, @Nullable Entity e, long now) {
        if (!(e instanceof LivingEntity le) || !le.isAlive()) return so.name + " must be loaded to receive gear";
        String given;
        if (so.merit.want == Want.WEAPON) {
            Item item = stockWeapon(s, so.preferredWeapon);
            if (item == null) return "The stockpile has no " + family(so.preferredWeapon) + ". Order one from the smithy.";
            give(s, e, EquipmentSlot.MAINHAND, item);
            given = item.getDescription().getString();
        } else {
            List<String> pieces = new ArrayList<>();
            for (EquipmentSlot slot : ARMOUR) {
                Item item = stockArmour(s, slot, defence(RecruitsBridge.getSlot(e, slot)));
                if (item != null) { give(s, e, slot, item); pieces.add(item.getDescription().getString()); }
            }
            if (pieces.isEmpty()) return "The stockpile has no better armour for " + so.name + ". Order it from the smithy.";
            given = String.join(", ", pieces);
        }
        close(s, so, now, GRANT_MORALE, e);
        s.log(so.name + " received " + given + " from the armoury");
        return null;
    }

    /** Equips like the armoury does; storable old gear returns to stores, worn or named gear goes to the soldier's pack. */
    static void give(Settlement s, Entity e, EquipmentSlot slot, Item item) {
        ItemStack cur = RecruitsBridge.getSlot(e, slot).copy();
        if (!s.stock.take(item, 1)) return;
        RecruitsBridge.setSlot(e, slot, new ItemStack(item));
        if (cur.isEmpty()) return;
        if (Stockpile.canStore(cur)) s.stock.force(cur.getItem(), cur.getCount());
        else {
            ItemStack left = RecruitsBridge.addToPack(e, cur);
            if (!left.isEmpty()) e.spawnAtLocation(left);
        }
    }

    @Nullable
    static String order(Settlement s, Settlement.Soldier so, long now) {
        if (so.merit.ordered) return "Already ordered from the smithy";
        CompoundTag d = new CompoundTag();
        d.putInt("qty", 1);
        if (so.merit.want == Want.WEAPON) {
            var best = weaponPattern(s, so.preferredWeapon);
            if (best == null) return "No researched pattern makes " + article(family(so.preferredWeapon));
            d.putString("pattern", best.getKey().id()); d.putString("piece", best.getValue().name());
        } else {
            var best = armourPattern(s);
            if (best == null) return "No researched armour to order";
            d.putString("pattern", best.id()); d.putString("piece", "SET");
        }
        String err = Actions.order(s, d);
        if (err != null) return err;
        so.merit.ordered = true; so.merit.wantAt = now;
        s.log("The smithy is making it for " + so.name);
        return null;
    }

    static void close(Settlement s, Settlement.Soldier so, long now, float morale, @Nullable Entity e) {
        so.merit.want = Want.NONE; so.merit.ordered = false; so.merit.cooldownUntil = now + COOLDOWN;
        if (morale != 0 && e != null) RecruitsBridge.addMorale(e, morale);
        s.dirty = true;
    }

    // ------------------------------------------------------------------ view

    public static String reason(Settlement.Soldier so, long now) {
        List<String> out = new ArrayList<>();
        int week = weekKills(so.merit, now);
        if (week > 0) out.add(week + (week == 1 ? " kill" : " kills") + " this week");
        if (!so.merit.feat.isEmpty() && now - so.merit.featAt < 7 * DAY) out.add(so.merit.feat);
        if (so.royalGuard) out.add("Royal Guard");
        return out.isEmpty() ? "steady service" : String.join(", ", out.subList(0, Math.min(2, out.size())));
    }

    public static List<Settlement.Soldier> board(Settlement s) {
        List<Settlement.Soldier> all = new ArrayList<>(s.soldiers);
        all.sort(Comparator.comparingDouble((Settlement.Soldier so) -> -so.merit.merit).thenComparingInt(so -> -so.kills));
        return all.subList(0, Math.min(BOARD, all.size()));
    }

    /** Adds "honours" (top 10 by merit) and "requests" (open requests) to the settlement view. */
    public static void describe(Settlement s, CompoundTag out) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        long now = server == null ? 0 : now(server);
        ListTag board = new ListTag();
        for (Settlement.Soldier so : board(s)) {
            decay(so.merit, now);
            CompoundTag t = new CompoundTag();
            t.putUUID("e", so.entity); t.putString("name", so.name); t.putString("type", so.type.name());
            t.putString("title", SoldierRanks.title(SoldierRanks.rank(so))); t.putInt("kills", so.kills);
            t.putString("fav", so.preferredWeapon == null ? "" : so.preferredWeapon.label);
            t.putInt("merit", Math.round(so.merit.merit)); t.putString("reason", reason(so, now));
            t.putBoolean("royalGuard", so.royalGuard); t.putBoolean("wants", so.merit.want != Want.NONE);
            board.add(t);
        }
        out.put("honours", board);
        ListTag reqs = new ListTag();
        for (Settlement.Soldier so : s.soldiers) {
            if (so.merit.want == Want.NONE) continue;
            Entity e = server == null || !so.seen ? null : SoldierProgression.findEntity(server, so.entity);
            CompoundTag t = new CompoundTag();
            t.putUUID("soldier", so.entity); t.putString("name", so.name); t.putString("want", so.merit.want.name());
            t.putString("text", text(so)); t.putString("family", family(so.preferredWeapon)); t.putInt("merit", Math.round(so.merit.merit)); t.putBoolean("ordered", so.merit.ordered);
            t.putInt("daysLeft", (int) Math.max(0, ((so.merit.ordered ? 2 * EXPIRY : EXPIRY) - (now - so.merit.wantAt) + DAY - 1) / DAY));
            boolean stocked = so.merit.want == Want.WEAPON ? stockWeapon(s, so.preferredWeapon) != null
                    : e != null && Arrays.stream(ARMOUR).anyMatch(slot -> stockArmour(s, slot, defence(RecruitsBridge.getSlot(e, slot))) != null);
            t.putBoolean("inStock", stocked);
            var wp = so.merit.want == Want.WEAPON ? weaponPattern(s, so.preferredWeapon) : null;
            var ap = so.merit.want == Want.ARMOUR ? armourPattern(s) : null;
            t.putString("orderAs", wp != null ? wp.getKey().name() + " " + wp.getValue().label.toLowerCase(Locale.ROOT) : ap != null ? ap.name() + " armour set" : "");
            reqs.add(t);
        }
        out.put("requests", reqs);
        out.putInt("meritThreshold", (int) THRESHOLD);
    }

    static void tellOwner(Settlement s, String msg) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer p = server == null || s.owner == null ? null : server.getPlayerList().getPlayer(s.owner);
        if (p != null) p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD));
    }

    static long now(MinecraftServer server) { return server.overworld().getGameTime(); }

    private Merit() {}
}
