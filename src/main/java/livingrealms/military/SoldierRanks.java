package livingrealms.military;

import livingrealms.settlement.Settlement;
import livingrealms.settlement.Stockpile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.*;

/** Combat XP sources, titles, drill and respec. Kills and drills are uncapped; every trickle source shares one daily cap. */
public final class SoldierRanks {
    public static final int MAX_XP = SoldierSkills.MAX_POINTS * SoldierSkills.XP_PER_POINT, DAY = 24000;
    public static final int DAILY_TRICKLE_CAP = 75, DAMAGE_PER_XP = 5, MAX_HIT_XP = 3;
    public static final int ALARM_XP = 8, RAID_SURVIVE_XP = 15, RAID_DEFEND_XP = 2, RAID_DEFEND_TICKS = 200, ESCORT_XP = 2, ESCORT_TICKS = 1200;
    public static final int DRILL_XP = 40, DRILL_NATIVE_XP = 50, DRILL_COOLDOWN = 12000;
    public static final int[] TITLE_RANKS = {0, 2, 4, 7, 11, 16};
    public static final String[] TITLES = {"Recruit", "Soldier", "Veteran", "Sergeant", "Elite", "Champion"};
    private static final Set<UUID> THREAT_ALARMS = new HashSet<>();

    public static int rank(Settlement.Soldier so) { return SoldierSkills.earnedPoints(so.skillXp); }
    public static int titleIndex(int rank) { int i = 0; while (i + 1 < TITLES.length && rank >= TITLE_RANKS[i + 1]) i++; return i; }
    public static String title(int rank) { return TITLES[titleIndex(rank)]; }
    public static int drillEmeralds(int rank) { return 4 + 2 * rank; }
    public static int drillFood(int rank) { return 10 + 2 * rank; }
    public static int respecEmeralds(int learned) { return 8 + 4 * learned; }

    /** Pure XP bookkeeping; returns the XP actually granted. */
    public static int addXp(Settlement.Soldier so, int amount, boolean trickle, long now) {
        if (amount <= 0) return 0;
        if (trickle) {
            long day = now / DAY;
            if (so.trickleDay != day) { so.trickleDay = day; so.trickleXp = 0; }
            amount = Math.min(amount, DAILY_TRICKLE_CAP - so.trickleXp);
        }
        amount = Math.min(amount, MAX_XP - so.skillXp);
        if (amount <= 0) return 0;
        so.skillXp += amount;
        if (trickle) so.trickleXp += amount;
        return amount;
    }

    public static int grant(Settlement s, Settlement.Soldier so, @Nullable Entity e, int amount, boolean trickle, long now) {
        int before = rank(so), got = addXp(so, amount, trickle, now);
        if (got > 0) s.dirty = true;
        if (rank(so) > before) celebrate(s, so, e, before, rank(so));
        return got;
    }

    static void celebrate(Settlement s, Settlement.Soldier so, @Nullable Entity e, int before, int after) {
        boolean promoted = titleIndex(after) > titleIndex(before);
        String msg = promoted ? so.name + " was promoted to " + title(after) + " (skill rank " + after + ")"
                : so.name + " reached skill rank " + after + " and earned a skill point";
        s.log(msg);
        if (promoted) livingrealms.chronicle.Chronicle.promoted(s, so, titleIndex(after), title(after));
        MinecraftServer server = e != null && e.getServer() != null ? e.getServer() : ServerLifecycleHooks.getCurrentServer();
        ServerPlayer owner = server == null || s.owner == null ? null : server.getPlayerList().getPlayer(s.owner);
        if (owner != null) owner.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD));
        if (e != null && e.level() instanceof ServerLevel level) {
            level.playSound(null, e.blockPosition(), promoted ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP,
                    SoundSource.NEUTRAL, promoted ? .7f : .6f, 1.1f);
            level.sendParticles(promoted ? ParticleTypes.TOTEM_OF_UNDYING : ParticleTypes.HAPPY_VILLAGER,
                    e.getX(), e.getY() + e.getBbHeight() * .7, e.getZ(), promoted ? 30 : 12, .4, .5, .4, promoted ? .25 : .05);
        }
    }

    // ------------------------------------------------------------------ trickle sources

    /** Remembers alarms raised by real hostiles so a manually rung bell earns nothing. */
    public static void threatSighted(Settlement s) { THREAT_ALARMS.add(s.id); }

    public static void alarmEnded(ServerLevel level, Settlement s) {
        if (!THREAT_ALARMS.remove(s.id)) return;
        long now = level.getServer().overworld().getGameTime();
        for (Settlement.Soldier so : s.soldiers) {
            Entity e = level.getEntity(so.entity);
            if (e instanceof LivingEntity le && le.isAlive() && s.contains(e.blockPosition())) grant(s, so, e, ALARM_XP, true, now);
        }
    }

    public static void raidSurvived(ServerLevel level, Settlement s, Set<UUID> attackers, boolean defending) {
        long now = level.getServer().overworld().getGameTime();
        for (Settlement.Soldier so : s.soldiers) {
            Entity e = level.getEntity(so.entity);
            if (e instanceof LivingEntity le && le.isAlive() && (defending ? s.contains(e.blockPosition()) : attackers.contains(so.entity)))
                grant(s, so, e, RAID_SURVIVE_XP, true, now);
        }
    }

    // ------------------------------------------------------------------ drill and respec

    public static int foodValue(Stockpile stock) {
        int n = 0;
        for (Item item : rations(stock)) n += stock.count(item) * item.getFoodProperties().getNutrition();
        return n;
    }

    static List<Item> rations(Stockpile stock) {
        List<Item> foods = new ArrayList<>();
        for (var entry : stock.view().object2IntEntrySet()) {
            FoodProperties fp = entry.getKey().getFoodProperties();
            if (entry.getIntValue() > 0 && fp != null && fp.getNutrition() > 0
                    && fp.getEffects().stream().allMatch(p -> p.getFirst().getEffect().isBeneficial())) foods.add(entry.getKey());
        }
        foods.sort(Comparator.comparingInt(i -> -stock.count(i)));
        return foods;
    }

    static void takeFood(Stockpile stock, int need) {
        for (Item item : rations(stock)) {
            if (need <= 0) return;
            int nut = item.getFoodProperties().getNutrition(), n = Math.min(stock.count(item), (need + nut - 1) / nut);
            stock.take(item, n);
            need -= n * nut;
        }
    }

    @Nullable
    static String loaded(Settlement s, @Nullable Entity e) {
        return e == null || !e.isAlive() || s.owner == null || !s.owner.equals(RecruitsBridge.owner(e)) ? "Your recruit must be loaded and still belong to you" : null;
    }

    @Nullable
    public static String drill(Settlement s, Settlement.Soldier so, @Nullable Entity e, long now) {
        String err = loaded(s, e);
        if (err != null) return err;
        int rank = rank(so), emeralds = drillEmeralds(rank), food = drillFood(rank);
        if (so.skillXp >= MAX_XP) return so.name + " has nothing left to learn from drill";
        if (now < so.drillReadyAt) return so.name + " can drill again in " + (so.drillReadyAt - now + 19) / 20 + "s";
        if (!s.stock.has(Items.EMERALD, emeralds) || foodValue(s.stock) < food) return "Drill costs " + drillCost(rank) + " from town stock";
        s.stock.take(Items.EMERALD, emeralds);
        takeFood(s.stock, food);
        so.drillReadyAt = now + DRILL_COOLDOWN;
        RecruitsBridge.addNativeXp(e, DRILL_NATIVE_XP);
        s.log(so.name + " drilled with the garrison (+" + DRILL_XP + " combat XP)");
        grant(s, so, e, DRILL_XP, false, now);
        return null;
    }

    @Nullable
    public static String respec(Settlement s, Settlement.Soldier so, @Nullable Entity e) {
        String err = loaded(s, e);
        if (err != null) return err;
        if (so.skills.isEmpty()) return so.name + " has no learned skills to unlearn";
        int cost = respecEmeralds(so.skills.size());
        if (!s.stock.has(Items.EMERALD, cost)) return "Respec costs " + cost + " emeralds from town stock";
        s.stock.take(Items.EMERALD, cost);
        int refunded = so.skills.size();
        so.skills.clear();
        if (e instanceof LivingEntity le) { SoldierProgression.clearSkills(le); SoldierProgression.applySkills(le, so); }
        s.log(so.name + " unlearned " + refunded + " skills; the points are free to spend again");
        return null;
    }

    public static String drillCost(int rank) { return drillEmeralds(rank) + " emeralds + " + drillFood(rank) + " food"; }
    public static String respecCost(int learned) { return respecEmeralds(learned) + " emeralds"; }

    // ------------------------------------------------------------------ UI contract

    public static void describe(Settlement s, Settlement.Soldier so, CompoundTag out) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        long now = server == null ? 0 : server.overworld().getGameTime();
        int rank = rank(so), title = titleIndex(rank);
        boolean maxed = so.skillXp >= MAX_XP;
        out.putInt("rank", rank);
        out.putString("rankTitle", TITLES[title]);
        out.putString("nextTitle", title + 1 < TITLES.length ? TITLES[title + 1] : "");
        out.putInt("nextTitleRank", title + 1 < TITLES.length ? TITLE_RANKS[title + 1] : rank);
        out.putInt("xpInto", maxed ? SoldierSkills.XP_PER_POINT : so.skillXp % SoldierSkills.XP_PER_POINT);
        out.putInt("xpNeeded", SoldierSkills.XP_PER_POINT);
        out.putInt("maxPoints", SoldierSkills.MAX_POINTS);
        out.putInt("dailyXpLeft", so.trickleDay == now / DAY ? Math.max(0, DAILY_TRICKLE_CAP - so.trickleXp) : DAILY_TRICKLE_CAP);
        int cooldown = (int) Math.max(0, (so.drillReadyAt - now + 19) / 20);
        out.putString("drillCost", drillCost(rank));
        out.putInt("drillCooldown", cooldown);
        out.putBoolean("drillReady", !maxed && cooldown == 0 && s.stock.has(Items.EMERALD, drillEmeralds(rank)) && foodValue(s.stock) >= drillFood(rank));
        out.putString("respecCost", respecCost(so.skills.size()));
    }

    private SoldierRanks() {}
}
