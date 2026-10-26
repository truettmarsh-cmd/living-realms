package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierProgression;
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
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import java.util.UUID;

/** Called only by the opt-in isolated development server test runner. */
public final class SoldierProgressionSelfTest {
    public static int run(ServerLevel level) {
        int checks = 0;
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "GuardTestOwner"));
        var stranger = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "GuardTestOther"));
        Settlement s = new Settlement();
        s.name = "Recruit skill self-test"; s.owner = owner.getUUID(); s.dim = level.dimension();
        s.center = new BlockPos(0, 100, 0);
        SettlementManager manager = SettlementManager.get(level.getServer());
        manager.add(s);
        Entity recruit = null;
        try {
            level.getChunkAt(s.center);
            recruit = RecruitsBridge.spawn(level, UnitType.FOOTMAN, s.center, s.owner, "Guard Test");
            check(recruit instanceof LivingEntity, "Recruit spawn"); checks++;
            LivingEntity living = (LivingEntity) recruit;
            Settlement.Soldier so = new Settlement.Soldier();
            so.entity = recruit.getUUID(); so.name = "Guard Test"; so.type = UnitType.FOOTMAN;
            s.soldiers.add(so);
            CompoundTag request = new CompoundTag(); request.putUUID("soldier", so.entity);
            request.putString("skill", "IRONCLAD"); so.skillXp = 100;
            check(Actions.learnSoldierSkill(owner, s, request) != null && so.skills.isEmpty(), "Prerequisite enforcement"); checks++;
            request.putString("skill", "ENDURANCE");
            check(Actions.learnSoldierSkill(stranger, s, request) != null && so.skills.isEmpty(), "Owner authorization"); checks++;
            float hp = living.getMaxHealth();
            check(Actions.learnSoldierSkill(owner, s, request) == null, "Learn root skill"); checks++;
            float learnedHp = living.getMaxHealth();
            SoldierProgression.applySkills(living, so); SoldierProgression.applySkills(living, so);
            var healthAttribute = living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            var bonuses = healthAttribute.getModifiers().stream().filter(modifier -> modifier.getName().equals("Living Realms recruit skill")).toList();
            check(learnedHp > hp && Math.abs(living.getMaxHealth() - learnedHp) < .01
                    && bonuses.size() == 1 && bonuses.get(0).getAmount() == 4
                    && bonuses.get(0).getOperation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION,
                    "Idempotent base health modifier with native Recruits multipliers"); checks++;
            check(Actions.learnSoldierSkill(owner, s, request) != null && so.skills.size() == 1, "Duplicate skill rejected"); checks++;
            request.putString("skill", "IRONCLAD");
            check(Actions.learnSoldierSkill(owner, s, request) == null && living.getArmorValue() >= 2, "Child skill armour effect"); checks++;
            so.skillXp = 150; request.putString("skill", "RESOLVE");
            check(Actions.learnSoldierSkill(owner, s, request) != null && so.skills.size() == 2,
                    "Required skill rank enforced even with a free point and prerequisite"); checks++;
            so.skillXp = 100;
            request.putString("skill", "MARCHING");
            check(Actions.learnSoldierSkill(owner, s, request) != null && so.skills.size() == 2, "No overspending skill points"); checks++;
            request.putString("skill", "NOT_A_SKILL");
            check(Actions.learnSoldierSkill(owner, s, request) != null, "Malformed skill rejected"); checks++;
            request.putBoolean("on", true); s.stock.force(Items.EMERALD, 64);
            check(Actions.royalGuard(owner, level, s, request) != null && !so.royalGuard && s.stock.has(Items.EMERALD, 64), "Promotion research gate and no charge"); checks++;
            s.research.add("command");
            check(Actions.royalGuard(stranger, level, s, request) != null && !so.royalGuard, "Royal Guard owner authorization"); checks++;
            check(Actions.royalGuard(owner, level, s, request) == null && so.royalGuard && s.stock.count(Items.EMERALD) == 32, "Royal Guard promotion and exact cost"); checks++;
            check(Actions.royalGuard(owner, level, s, request) == null && s.stock.count(Items.EMERALD) == 32, "Duplicate promotion does not charge"); checks++;
            Settlement.Soldier saved = Settlement.Soldier.load(so.save());
            check(saved.royalGuard && saved.skillXp == 100 && saved.skills.equals(so.skills), "NBT persistence of guard and progression"); checks++;
            check(SoldierProgression.escort(s, so, recruit), "Royal Guard overrides town orders even when owner offline"); checks++;
            so.skillXp = 0;
            var zombie = EntityType.ZOMBIE.create(level);
            SoldierProgression.killedHostile(new LivingDeathEvent(zombie, level.damageSources().mobAttack(living)));
            check(so.skillXp == SoldierSkills.XP_PER_KILL, "Hostile kill grants recruit XP"); checks++;
            var cow = EntityType.COW.create(level);
            SoldierProgression.killedHostile(new LivingDeathEvent(cow, level.damageSources().mobAttack(living)));
            check(so.skillXp == SoldierSkills.XP_PER_KILL, "Passive animal kills grant no XP"); checks++;
            so.skillXp = SoldierSkills.MAX_POINTS * SoldierSkills.XP_PER_POINT;
            SoldierProgression.killedHostile(new LivingDeathEvent(zombie, level.damageSources().mobAttack(living)));
            check(so.skillXp == SoldierSkills.MAX_POINTS * SoldierSkills.XP_PER_POINT, "XP rank cap"); checks++;
            request.putBoolean("on", false);
            check(Actions.royalGuard(owner, level, s, request) == null && !so.royalGuard, "Return to town duty"); checks++;
            check(Actions.dismissSoldier(owner, level, s, request) == null && s.soldiers.isEmpty()
                    && Math.abs(living.getMaxHealth() - hp) < .01, "Dismiss clears managed modifiers"); checks++;
            return checks;
        } finally {
            if (recruit != null) recruit.discard();
            manager.remove(s);
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Recruit progression self-test failed: " + label);
    }
    private SoldierProgressionSelfTest() {}
}
