package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierProgression;
import livingrealms.military.SoldierRanks;
import livingrealms.military.SoldierSkills;
import livingrealms.military.UnitType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.Set;
import java.util.UUID;

/** XP sources, caps, drill, respec, titles and old saves. Called only by the opt-in military test runner. */
public final class SoldierRanksSelfTest {
    public static int run(ServerLevel level) {
        int checks = 0;
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "RankTestOwner"));
        var stranger = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "RankTestOther"));
        Settlement s = new Settlement();
        s.name = "Recruit rank self-test"; s.owner = owner.getUUID(); s.dim = level.dimension();
        s.center = new BlockPos(64, 100, 64);
        SettlementManager manager = SettlementManager.get(level.getServer());
        manager.add(s);
        Entity recruit = null, zombie = null;
        try {
            level.getChunkAt(s.center);
            long now = level.getServer().overworld().getGameTime(), day = now / SoldierRanks.DAY;
            int[] ranks = {0, 1, 2, 3, 4, 6, 7, 10, 11, 15, 16, 18};
            String[] titles = {"Recruit", "Recruit", "Soldier", "Soldier", "Veteran", "Veteran", "Sergeant", "Sergeant", "Elite", "Elite", "Champion", "Champion"};
            for (int i = 0; i < ranks.length; i++) check(SoldierRanks.title(ranks[i]).equals(titles[i]), "Title at rank " + ranks[i]);
            checks++;

            Settlement.Soldier pure = new Settlement.Soldier();
            check(SoldierRanks.addXp(pure, 50, true, now) == 50 && SoldierRanks.addXp(pure, 50, true, now) == 25
                    && SoldierRanks.addXp(pure, 5, true, now) == 0 && pure.trickleXp == SoldierRanks.DAILY_TRICKLE_CAP, "Daily trickle cap"); checks++;
            check(SoldierRanks.addXp(pure, SoldierSkills.XP_PER_KILL, false, now) == SoldierSkills.XP_PER_KILL, "Kill XP ignores trickle cap"); checks++;
            check(SoldierRanks.addXp(pure, 10, true, now + SoldierRanks.DAY) == 10 && pure.trickleXp == 10, "Trickle cap resets next day"); checks++;
            pure.skillXp = SoldierRanks.MAX_XP - 5;
            check(SoldierRanks.addXp(pure, 40, false, now) == 5 && pure.skillXp == SoldierRanks.MAX_XP, "Max XP cap"); checks++;

            recruit = RecruitsBridge.spawn(level, UnitType.FOOTMAN, s.center, s.owner, "Rank Test");
            check(recruit instanceof LivingEntity, "Recruit spawn"); checks++;
            LivingEntity living = (LivingEntity) recruit;
            Settlement.Soldier so = new Settlement.Soldier();
            so.entity = recruit.getUUID(); so.name = "Rank Test"; so.type = UnitType.FOOTMAN;
            s.soldiers.add(so);

            zombie = EntityType.ZOMBIE.create(level);
            zombie.moveTo(s.center.getX() + 3, s.center.getY(), s.center.getZ());
            LivingEntity z = (LivingEntity) zombie;
            SoldierProgression.damagedHostile(new LivingHurtEvent(z, level.damageSources().mobAttack(living), 7));
            check(so.skillXp == 1 && Math.abs(so.damageBank - 2) < .01, "Damage XP banks remainder"); checks++;
            SoldierProgression.damagedHostile(new LivingHurtEvent(z, level.damageSources().mobAttack(living), 500));
            check(so.skillXp == 1 + SoldierRanks.MAX_HIT_XP, "Damage XP per hit is capped"); checks++;
            var cow = EntityType.COW.create(level);
            SoldierProgression.damagedHostile(new LivingHurtEvent(cow, level.damageSources().mobAttack(living), 10));
            check(so.skillXp == 1 + SoldierRanks.MAX_HIT_XP, "Damaging passive animals grants nothing"); checks++;

            so.skillXp = 0; so.trickleXp = 0; so.damageBank = 0;
            SoldierRanks.alarmEnded(level, s);
            check(so.skillXp == 0, "Manual alarm without hostiles grants nothing"); checks++;
            SoldierRanks.threatSighted(s); SoldierRanks.alarmEnded(level, s);
            check(so.skillXp == SoldierRanks.ALARM_XP, "Surviving a hostile alarm"); checks++;
            SoldierRanks.alarmEnded(level, s);
            check(so.skillXp == SoldierRanks.ALARM_XP, "Alarm survival grants once"); checks++;
            SoldierRanks.raidSurvived(level, s, Set.of(), true);
            check(so.skillXp == SoldierRanks.ALARM_XP + SoldierRanks.RAID_SURVIVE_XP, "Surviving a raid as defender"); checks++;
            SoldierRanks.raidSurvived(level, s, Set.of(), false);
            check(so.skillXp == SoldierRanks.ALARM_XP + SoldierRanks.RAID_SURVIVE_XP, "Attacker survival needs deployment"); checks++;

            so.skillXp = 45; s.log.clear();
            SoldierRanks.grant(s, so, recruit, 10, false, now);
            check(s.log.peekFirst() != null && s.log.peekFirst().contains("earned a skill point"), "Point feedback logged"); checks++;
            SoldierRanks.grant(s, so, recruit, 50, false, now);
            check(s.log.peekFirst() != null && s.log.peekFirst().contains("promoted to Soldier"), "Title feedback logged"); checks++;

            so.skillXp = 0; so.drillReadyAt = 0;
            CompoundTag request = new CompoundTag(); request.putUUID("soldier", so.entity);
            check(SoldierRanks.drillEmeralds(0) == 4 && SoldierRanks.drillFood(0) == 10 && SoldierRanks.drillEmeralds(10) == 24
                    && SoldierRanks.drillFood(10) == 30, "Drill cost scales with rank"); checks++;
            check(Actions.drillSoldier(owner, s, request) != null && so.skillXp == 0, "Drill needs stock"); checks++;
            s.stock.force(Items.EMERALD, 40); s.stock.force(Items.BREAD, 10);
            check(Actions.drillSoldier(stranger, s, request) != null && s.stock.count(Items.EMERALD) == 40, "Drill owner authorization"); checks++;
            CompoundTag view = new CompoundTag(); SoldierRanks.describe(s, so, view);
            check(view.getBoolean("drillReady") && view.getInt("drillCooldown") == 0, "Drill ready before use"); checks++;
            int nativeXp = RecruitsBridge.xp(recruit), nativeLevel = RecruitsBridge.level(recruit);
            check(Actions.drillSoldier(owner, s, request) == null && so.skillXp == SoldierRanks.DRILL_XP
                    && s.stock.count(Items.EMERALD) == 36 && s.stock.count(Items.BREAD) == 8, "Drill charges and grants XP"); checks++;
            check(RecruitsBridge.xp(recruit) > nativeXp || RecruitsBridge.level(recruit) > nativeLevel, "Drill grants native Recruits XP"); checks++;
            check(Actions.drillSoldier(owner, s, request) != null && so.skillXp == SoldierRanks.DRILL_XP && s.stock.count(Items.EMERALD) == 36, "Drill cooldown"); checks++;
            view = new CompoundTag(); SoldierRanks.describe(s, so, view);
            check(!view.getBoolean("drillReady") && view.getInt("drillCooldown") > 0 && view.getInt("drillCooldown") <= SoldierRanks.DRILL_COOLDOWN / 20,
                    "Cooldown described"); checks++;
            so.drillReadyAt = 0; so.skillXp = 250;
            check(Actions.drillSoldier(owner, s, request) == null && s.stock.count(Items.EMERALD) == 36 - 14, "Rank 5 drill cost"); checks++;

            so.skillXp = 100; s.stock.take(Items.EMERALD, s.stock.count(Items.EMERALD));
            float hp = living.getMaxHealth();
            request.putString("skill", "ENDURANCE"); Actions.learnSoldierSkill(owner, s, request);
            request.putString("skill", "IRONCLAD"); Actions.learnSoldierSkill(owner, s, request);
            check(so.skills.size() == 2 && living.getMaxHealth() > hp && SoldierSkills.availablePoints(so.skillXp, so.skills) == 0, "Skills learned"); checks++;
            check(Actions.respecSoldier(owner, s, request) != null && so.skills.size() == 2, "Respec needs emeralds"); checks++;
            s.stock.force(Items.EMERALD, 20);
            check(Actions.respecSoldier(stranger, s, request) != null && so.skills.size() == 2, "Respec owner authorization"); checks++;
            check(Actions.respecSoldier(owner, s, request) == null && so.skills.isEmpty() && s.stock.count(Items.EMERALD) == 20 - 16
                    && Math.abs(living.getMaxHealth() - hp) < .01 && SoldierSkills.availablePoints(so.skillXp, so.skills) == 2, "Respec refunds points and modifiers"); checks++;
            check(Actions.respecSoldier(owner, s, request) != null && s.stock.count(Items.EMERALD) == 4, "Empty respec does not charge"); checks++;

            view = new CompoundTag(); so.skillXp = 230; SoldierRanks.describe(s, so, view);
            check(view.getInt("rank") == 4 && view.getString("rankTitle").equals("Veteran") && view.getInt("xpInto") == 30
                    && view.getInt("xpNeeded") == 50 && view.getInt("maxPoints") == 18 && view.getString("drillCost").equals("12 emeralds + 18 food")
                    && view.getString("respecCost").equals("8 emeralds"), "UI describe contract"); checks++;

            so.trickleDay = day; so.trickleXp = 30; so.drillReadyAt = now + 99;
            Settlement.Soldier round = Settlement.Soldier.load(so.save());
            check(round.trickleDay == day && round.trickleXp == 30 && round.drillReadyAt == now + 99, "New fields persist"); checks++;
            CompoundTag old = so.save();
            old.remove("drillReadyAt"); old.remove("trickleDay"); old.remove("trickleXp");
            Settlement.Soldier legacy = Settlement.Soldier.load(old);
            check(legacy.skillXp == 230 && legacy.drillReadyAt == 0 && legacy.trickleXp == 0 && legacy.trickleDay == 0, "Old save loads with defaults"); checks++;
            return checks;
        } finally {
            if (recruit != null) recruit.discard();
            if (zombie != null) zombie.discard();
            manager.remove(s);
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Recruit rank self-test failed: " + label);
    }
    private SoldierRanksSelfTest() {}
}
