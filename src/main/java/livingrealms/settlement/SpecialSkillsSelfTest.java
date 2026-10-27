package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.military.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import java.util.*;

/** Special skill slots, choice, triggers and caps, plus the legendary pity counter and old-save loading. */
public final class SpecialSkillsSelfTest {
    public static int run(ServerLevel level) {
        int checks = 0;
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "SpecialTestOwner"));
        var stranger = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "SpecialTestOther"));
        Settlement.Soldier so = soldier(UnitType.FOOTMAN, WeaponProficiency.SWORD);
        int[][] slots = {{0, 0}, {149, 0}, {150, 1}, {399, 1}, {400, 2}, {699, 2}, {700, 3}, {900, 3}};
        for (int[] c : slots) { so.skillXp = c[0]; check(SpecialSkill.slots(so) == c[1], "Slots at xp " + c[0]); checks++; }
        Settlement s = new Settlement(); s.name = "Special test"; s.owner = owner.getUUID(); s.dim = level.dimension(); s.center = new BlockPos(40, 100, 40);
        s.soldiers.add(so);
        CompoundTag req = new CompoundTag(); req.putUUID("soldier", so.entity); req.putString("special", "RALLY_CRY");
        so.skillXp = 0;
        check(Actions.chooseSpecial(owner, s, req) != null && so.specials.isEmpty(), "No slot before rank 3"); checks++;
        so.skillXp = 150;
        check(Actions.chooseSpecial(stranger, s, req) != null && so.specials.isEmpty(), "Owner authorization"); checks++;
        check(Actions.chooseSpecial(owner, s, req) == null && so.specials.equals(List.of(SpecialSkill.RALLY_CRY)), "Choose special"); checks++;
        req.putString("special", "SECOND_WIND");
        check(Actions.chooseSpecial(owner, s, req) != null && so.specials.size() == 1, "Cannot exceed slots"); checks++;
        req.putString("special", "SHIELD_WALL"); so.skillXp = 900;
        check(Actions.chooseSpecial(owner, s, req) != null && !so.specials.contains(SpecialSkill.SHIELD_WALL), "Unit fit enforced"); checks++;
        req.putString("special", "STAGGER");
        check(Actions.chooseSpecial(owner, s, req) != null, "Weapon fit enforced"); checks++;
        req.putString("special", "NOPE");
        check(Actions.chooseSpecial(owner, s, req) != null, "Malformed special rejected"); checks++;
        for (String id : List.of("DISARM", "SECOND_WIND")) { req.putString("special", id); check(Actions.chooseSpecial(owner, s, req) == null, "Fill slot " + id); checks++; }
        req.putString("special", "TAUNT");
        check(Actions.chooseSpecial(owner, s, req) != null && so.specials.size() == 3, "Three slots maximum"); checks++;
        req.putString("special", "DISARM");
        check(Actions.chooseSpecial(owner, s, req) == null && so.specials.equals(List.of(SpecialSkill.RALLY_CRY, SpecialSkill.SECOND_WIND)), "Choosing again unequips"); checks++;
        CompoundTag view = new CompoundTag(); SoldierSpecials.describe(s, so, view);
        Map<String, String> states = new HashMap<>();
        for (Tag t : view.getList("specials", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag)t; states.put(c.getString("id"), c.getString("state"));
            check(!c.getString("label").isEmpty() && !c.getString("desc").isEmpty() && !c.getString("req").isEmpty(), "Describe fields " + c.getString("id")); checks++;
        }
        check(view.getInt("specialSlots") == 3 && "chosen".equals(states.get("RALLY_CRY")) && "available".equals(states.get("DISARM"))
                && "locked".equals(states.get("SHIELD_WALL")) && states.size() >= 13, "Describe states " + states); checks++;
        check(states.size() >= 12 && SpecialSkill.values().length >= 12 && SpecialSkill.values().length <= 16, "Special count"); checks++;
        so.skillXp = 150;
        check(SpecialSkill.RALLY_CRY.active(so) && !SpecialSkill.SECOND_WIND.active(so), "Lost slots deactivate later choices"); checks++;
        so.skillXp = 900;

        // cooldowns
        so.specialCooldowns.clear();
        check(SoldierSpecials.use(so, SpecialSkill.RALLY_CRY, 1000) && !SoldierSpecials.use(so, SpecialSkill.RALLY_CRY, 1000 + 90 * 20 - 1)
                && SoldierSpecials.use(so, SpecialSkill.RALLY_CRY, 1000 + 90 * 20), "Special cooldown enforced"); checks++;
        check(!SoldierSpecials.use(so, SpecialSkill.TAUNT, 5000), "Unequipped special never fires"); checks++;
        so.legendary.add(LegendaryPerk.TACTICIAN);
        check(SoldierSpecials.cooldown(so, SpecialSkill.RALLY_CRY) == 90 * 15, "Tactician shortens cooldowns 25%"); checks++;
        so.legendary.clear();
        so.specialCooldowns.put(SpecialSkill.SECOND_WIND, 1_000_000L);
        check(SoldierSpecials.ready(so, SpecialSkill.SECOND_WIND, 100), "Rewound clock cannot lock a special forever"); checks++;
        so.specialCooldowns.clear();

        // legendary pity
        RandomSource never = new LegacyRandomSource(0) { @Override public double nextDouble() { return .999; } @Override public int nextInt(int b) { return 0; } };
        Settlement.Soldier unlucky = soldier(UnitType.FOOTMAN, WeaponProficiency.SWORD);
        check(LegendaryPerk.levelUp(unlucky, 13, never).isEmpty() && unlucky.legendaryPity == 6, "Misses build pity"); checks++;
        check(Math.abs(LegendaryPerk.chance(0) - .12) < 1e-9 && Math.abs(LegendaryPerk.chance(3) - .21) < 1e-9 && LegendaryPerk.chance(6) == 1, "Pity chance curve"); checks++;
        check(LegendaryPerk.levelUp(unlucky, 14, never).size() == 1 && unlucky.legendaryPity == 0, "First perk guaranteed by level 14"); checks++;
        check(LegendaryPerk.levelUp(unlucky, 20, never).isEmpty() && unlucky.legendaryPity == 6
                && LegendaryPerk.levelUp(unlucky, 21, never).size() == 1 && unlucky.legendary.size() == 2, "Second perk guaranteed by level 21"); checks++;
        check(LegendaryPerk.levelUp(unlucky, 40, never).isEmpty() && unlucky.legendaryPity == 0, "No pity once slots are full"); checks++;
        check(LegendaryPerk.values().length >= 16 && LegendaryPerk.values().length <= 18, "Legendary count"); checks++;

        // old saves
        CompoundTag old = so.save();
        for (String k : List.of("specials", "specialCooldowns", "legendaryPity", "braceUntil", "rallyUntil", "chargeUntil")) old.remove(k);
        old.putInt("legendaryLevelChecked", 12); old.put("legendary", new ListTag());
        Settlement.Soldier legacy = Settlement.Soldier.load(old);
        check(legacy.specials.isEmpty() && legacy.specialCooldowns.isEmpty() && legacy.legendaryPity == 5 && legacy.braceUntil == 0, "Old save loads with credited pity"); checks++;
        ListTag bad = new ListTag(); bad.add(StringTag.valueOf("REMOVED_SPECIAL")); bad.add(StringTag.valueOf("RALLY_CRY")); bad.add(StringTag.valueOf("RALLY_CRY"));
        old.put("specials", bad); old.putInt("legendaryPity", 99);
        legacy = Settlement.Soldier.load(old);
        check(legacy.specials.equals(List.of(SpecialSkill.RALLY_CRY)) && legacy.legendaryPity == LegendaryPerk.PITY_LIMIT, "Unknown IDs and bad values sanitised"); checks++;
        so.specialCooldowns.put(SpecialSkill.RALLY_CRY, 777L); so.braceUntil = 5; so.rallyUntil = 6; so.chargeUntil = 7; so.legendaryPity = 3;
        Settlement.Soldier copy = Settlement.Soldier.load(so.save());
        check(copy.specials.equals(so.specials) && copy.specialCooldowns.equals(so.specialCooldowns) && copy.legendaryPity == 3
                && copy.braceUntil == 5 && copy.rallyUntil == 6 && copy.chargeUntil == 7, "Special state persistence"); checks++;

        // live triggers and caps
        SettlementManager manager = SettlementManager.get(level.getServer());
        manager.add(s); level.getChunkAt(s.center);
        LivingEntity guard = (LivingEntity)RecruitsBridge.spawn(level, UnitType.SHIELDBEARER, s.center, s.owner, "Shield Test");
        var zombie = EntityType.ZOMBIE.create(level);
        try {
            check(guard != null, "Shieldbearer spawn"); checks++;
            Settlement.Soldier sb = soldier(UnitType.SHIELDBEARER, WeaponProficiency.MACE);
            sb.entity = guard.getUUID(); sb.skillXp = 900; s.soldiers.add(sb);
            RecruitsBridge.setSlot(guard, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            for (String id : List.of("SHIELD_WALL", "SECOND_WIND", "STAGGER")) check(SoldierSpecials.choose(sb, id) == null, "Equip " + id);
            checks++;
            sb.skills.addAll(EnumSet.allOf(SoldierSkills.class));
            zombie.moveTo(41, 100, 40); level.addFreshEntity(zombie);
            long now = RecruitCombat.now(guard);
            sb.legendary.add(LegendaryPerk.STEADFAST);
            guard.setHealth(guard.getMaxHealth() * .25f);
            var hurt = new LivingHurtEvent(guard, level.damageSources().mobAttack(zombie), 10);
            RecruitCombat.hurt(hurt);
            check(sb.braceUntil == now + 120 && sb.specialCooldowns.get(SpecialSkill.SHIELD_WALL) == now + 800, "Shield Wall triggers and starts cooldown"); checks++;
            check(Math.abs(hurt.getAmount() - 7.5) < .001, "Brace + Last stand + Steadfast capped at 25%: " + hurt.getAmount()); checks++;
            hurt = new LivingHurtEvent(guard, level.damageSources().mobAttack(zombie), 10);
            RecruitCombat.hurt(hurt);
            check(sb.specialCooldowns.get(SpecialSkill.SHIELD_WALL) == now + 800, "Shield Wall does not retrigger on cooldown"); checks++;
            sb.braceUntil = 0; guard.setHealth(guard.getMaxHealth() * .9f);
            hurt = new LivingHurtEvent(guard, level.damageSources().mobAttack(zombie), 10);
            RecruitCombat.hurt(hurt);
            check(hurt.getAmount() > 7.5f, "Brace expires"); checks++;
            double bonus = RecruitCombat.damageBonus(s, sb, guard, zombie, WeaponProficiency.MACE, false, now, SoldierSpecials.CHARGE_BONUS + SoldierSpecials.AIMED_BONUS);
            sb.rallyUntil = now + 100;
            double rallied = RecruitCombat.damageBonus(s, sb, guard, zombie, WeaponProficiency.MACE, false, now, .3);
            check(bonus <= RecruitCombat.NORMAL_CAP + RecruitCombat.WEAPON_CAP + 1e-9 && Math.abs(bonus - rallied) < 1e-9, "Special bonuses share the 15% normal cap"); checks++;
            sb.legendary.clear(); sb.legendary.addAll(Set.of(LegendaryPerk.GIANTSLAYER, LegendaryPerk.OPPORTUNIST, LegendaryPerk.KEEN_EDGE)); sb.weaponXp.put(WeaponProficiency.MACE, 1500);
            zombie.setTarget(owner);
            var golem = EntityType.IRON_GOLEM.create(level);
            check(Math.abs(RecruitCombat.damageBonus(s, sb, guard, zombie, WeaponProficiency.MACE, false, now, .3) - .40) < 1e-9
                    && RecruitCombat.damageBonus(s, sb, guard, golem, WeaponProficiency.MACE, false, now, 0) <= .40 + 1e-9, "Legendary 10% cap and 40% combined"); checks++;
            golem.discard(); sb.legendary.clear();
            zombie.removeAllEffects();
            double extra = SoldierSpecials.onAttack(sb, guard, zombie, level.damageSources().mobAttack(guard), WeaponProficiency.MACE, 5, now);
            check(extra == 0 && zombie.hasEffect(MobEffects.MOVEMENT_SLOWDOWN) && zombie.hasEffect(MobEffects.WEAKNESS)
                    && sb.specialCooldowns.get(SpecialSkill.STAGGER) == now + 400, "Stagger on mace hit"); checks++;
            zombie.removeAllEffects();
            SoldierSpecials.onAttack(sb, guard, zombie, level.damageSources().mobAttack(guard), WeaponProficiency.MACE, 5, now + 399);
            check(!zombie.hasEffect(MobEffects.WEAKNESS), "Stagger cooldown"); checks++;
            SoldierSpecials.onAttack(sb, guard, zombie, level.damageSources().mobAttack(guard), WeaponProficiency.SWORD, 5, now + 400);
            check(!zombie.hasEffect(MobEffects.WEAKNESS), "Stagger needs the mace in hand"); checks++;
            guard.setHealth(guard.getMaxHealth() * .2f); float before = guard.getHealth();
            check(SoldierSpecials.act(s, sb, (Mob)guard, level, now) && guard.getHealth() > before
                    && guard.getHealth() - before <= 6.001f, "Second Wind heals at most 6 HP"); checks++;
            guard.setHealth(guard.getMaxHealth() * .2f); before = guard.getHealth();
            SoldierSpecials.act(s, sb, (Mob)guard, level, now + 20);
            check(guard.getHealth() == before, "Second Wind cooldown"); checks++;
            guard.setHealth(guard.getMaxHealth() * .9f); sb.specialCooldowns.clear(); sb.braceUntil = 0;
            var lethal = new LivingHurtEvent(guard, level.damageSources().mobAttack(zombie), 1000);
            RecruitCombat.hurt(lethal);
            check(!lethal.isCanceled() && lethal.getAmount() >= 750 - .01, "No invulnerability: lethal hits stay lethal"); checks++;
        } finally {
            if (guard != null) guard.discard();
            zombie.discard();
            manager.remove(s);
        }
        return checks;
    }
    private static Settlement.Soldier soldier(UnitType type, WeaponProficiency weapon) {
        var so = new Settlement.Soldier(); so.entity = UUID.randomUUID(); so.type = type; so.name = "Special Test"; so.preferredWeapon = weapon; return so;
    }
    private static void check(boolean ok, String message) { if (!ok) throw new IllegalStateException("Specials: " + message); }
    private SpecialSkillsSelfTest() {}
}
