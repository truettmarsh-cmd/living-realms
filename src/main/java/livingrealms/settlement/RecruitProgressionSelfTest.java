package livingrealms.settlement;

import livingrealms.military.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import java.util.*;

/** Behaviour assertions for favorite weapons, independent practice, roll persistence and bounded bonuses. */
public final class RecruitProgressionSelfTest {
    public static int run(ServerLevel level) {
        int checks = 0;
        Settlement.Soldier so = soldier(); so.preferredWeapon = WeaponProficiency.SWORD;
        check(Math.abs(WeaponProficiency.bonus(so, WeaponProficiency.SWORD) - .05) < 1e-9, "Favourite damage bonus"); checks++;
        check(WeaponProficiency.bonus(so, WeaponProficiency.AXE) == 0, "Other weapon starts neutral"); checks++;
        WeaponProficiency.practice(so, WeaponProficiency.SWORD, 80);
        check(so.weaponXp.get(WeaponProficiency.SWORD) == 100 && WeaponProficiency.rank(100) == 1, "Favourite practice bonus and rank"); checks++;
        check(so.weaponXp.getOrDefault(WeaponProficiency.AXE, 0) == 0, "Practice does not leak across families"); checks++;
        WeaponProficiency.practice(so, WeaponProficiency.SWORD, 10000);
        check(so.weaponXp.get(WeaponProficiency.SWORD) == 1500 && Math.abs(WeaponProficiency.bonus(so, WeaponProficiency.SWORD) - .15) < 1e-9, "Mastery and damage caps"); checks++;
        check(WeaponProficiency.classify(new ItemStack(Items.IRON_SWORD)) == WeaponProficiency.SWORD
                && WeaponProficiency.classify(new ItemStack(Items.IRON_AXE)) == WeaponProficiency.AXE
                && WeaponProficiency.classify(new ItemStack(Items.BOW)) == WeaponProficiency.BOW
                && WeaponProficiency.classify(ItemStack.EMPTY) == null, "Weapon classification"); checks++;
        RandomSource always = new LegacyRandomSource(0) {
            @Override public double nextDouble() { return 0; }
            @Override public int nextInt(int bound) { return 0; }
        };
        check(LegendaryPerk.levelUp(so, 7, always).isEmpty(), "No legendary before level8"); checks++;
        check(LegendaryPerk.levelUp(so, 8, always).size() == 1 && so.legendary.size() == 1, "First legendary opportunity"); checks++;
        check(LegendaryPerk.levelUp(so, 8, always).isEmpty(), "Same level cannot reroll"); checks++;
        check(LegendaryPerk.levelUp(so, 13, always).isEmpty() && so.legendary.size() == 1, "Second slot locked before14"); checks++;
        check(LegendaryPerk.levelUp(so, 14, always).size() == 1 && so.legendary.size() == 2, "Second slot unlocks14"); checks++;
        check(LegendaryPerk.levelUp(so, 20, always).isEmpty(), "Maximum two legendary perks"); checks++;
        so.legendaryCooldowns.put(LegendaryPerk.IRON_WILL, 9000L); so.battleRhythmUntil = 8000;
        Settlement.Soldier copy = Settlement.Soldier.load(so.save());
        check(copy.preferredWeapon == so.preferredWeapon && copy.weaponXp.equals(so.weaponXp)
                && copy.legendary.equals(so.legendary) && copy.legendaryLevelChecked == 20
                && copy.legendaryCooldowns.get(LegendaryPerk.IRON_WILL) == 9000 && copy.battleRhythmUntil == 8000,
                "Progression and cooldown persistence"); checks++;
        check(LegendaryPerk.levelUp(copy, 20, always).isEmpty(), "Reload does not reroll"); checks++;
        check(!LegendaryPerk.HAWKEYE.compatible(so), "Ranged legendary not rolled for melee preference"); checks++;
        RandomSource chance = RandomSource.create(892367);
        int lucky = 0;
        for (int i = 0; i < 2000; i++) if (!LegendaryPerk.levelUp(soldier(), 8, chance).isEmpty()) lucky++;
        check(lucky >= 170 && lucky <= 310, "12 percent chance stays rare: " + lucky + "/2000"); checks++;
        var entity = RecruitsBridge.spawn(level, UnitType.FOOTMAN, new BlockPos(20, 100, 20), UUID.randomUUID(), "Weapon Test");
        check(entity instanceof LivingEntity, "Test recruit spawn"); checks++;
        LivingEntity attacker = (LivingEntity)entity;
        try {
            var target = EntityType.ZOMBIE.create(level); target.moveTo(50, 100, 20); target.setHealth(2);
            target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            so.preferredWeapon = WeaponProficiency.BOW; so.weaponXp.put(WeaponProficiency.BOW, 1500);
            so.skills.addAll(EnumSet.allOf(SoldierSkills.class)); so.legendary.clear();
            so.legendary.addAll(Set.of(LegendaryPerk.HAWKEYE, LegendaryPerk.BATTLE_RHYTHM)); so.battleRhythmUntil = 200;
            attacker.setHealth(attacker.getMaxHealth());
            double bonus = RecruitCombat.damageBonus(so, attacker, target, WeaponProficiency.BOW, true, 100);
            check(Math.abs(bonus - .40) < 1e-6, "Stacking ceiling preserves legendary headroom"); checks++;
            RecruitsBridge.setSlot(attacker, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            Arrow arrow = new Arrow(level, attacker);
            RecruitCombat.launched(new EntityJoinLevelEvent(arrow, level));
            RecruitsBridge.setSlot(attacker, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            check(RecruitCombat.weapon(level.damageSources().arrow(arrow, attacker), attacker) == WeaponProficiency.BOW, "Projectile keeps launch weapon after gear swap"); checks++;
            so.legendary.clear(); so.legendary.add(LegendaryPerk.SECOND_WIND); so.legendaryCooldowns.clear();
            check(RecruitCombat.trigger(so, LegendaryPerk.SECOND_WIND, 100, 600)
                    && !RecruitCombat.trigger(so, LegendaryPerk.SECOND_WIND, 699, 600)
                    && RecruitCombat.trigger(so, LegendaryPerk.SECOND_WIND, 700, 600), "Rare proc cooldown enforced"); checks++;
            Settlement town = new Settlement(); town.name = "Defence test"; town.dim = level.dimension(); town.center = attacker.blockPosition();
            so.entity = attacker.getUUID(); town.soldiers.add(so); SettlementManager.get(level.getServer()).add(town);
            try {
                so.skills.addAll(EnumSet.allOf(SoldierSkills.class)); so.legendary.clear();
                so.legendary.addAll(Set.of(LegendaryPerk.STEADFAST, LegendaryPerk.DRAGONHEART));
                attacker.setHealth(attacker.getMaxHealth() * .2f);
                var hurt = new LivingHurtEvent(attacker, level.damageSources().onFire(), 10);
                RecruitCombat.hurt(hurt);
                check(Math.abs(hurt.getAmount() - 7.5) < .001, "Defensive stacking capped25 percent"); checks++;
            } finally { SettlementManager.get(level.getServer()).remove(town); }
        } finally { entity.discard(); }
        return checks;
    }
    private static Settlement.Soldier soldier() {
        var so = new Settlement.Soldier(); so.entity = UUID.randomUUID(); so.type = UnitType.FOOTMAN; so.name = "Test"; return so;
    }
    private static void check(boolean ok, String message) { if (!ok) throw new IllegalStateException("Progression: " + message); }
    private RecruitProgressionSelfTest() {}
}
