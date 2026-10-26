package livingrealms.military;

import livingrealms.LivingRealms;
import livingrealms.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ShieldItem;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Server-authoritative damage and practice. Bonus budgets preserve room for rare traits. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class RecruitCombat {
    private static final String SHOT_WEAPON = "livingrealms_weapon";
    public static final double NORMAL_CAP = .15, WEAPON_CAP = .15, LEGENDARY_CAP = .10, DEFENCE_CAP = .25;
    public static long now(LivingEntity entity) { return entity.getServer().overworld().getGameTime(); }

    @SubscribeEvent
    public static void launched(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof Projectile projectile)
                || !(projectile.getOwner() instanceof LivingEntity owner) || !RecruitsBridge.isRecruit(owner)) return;
        // Snapshot the family on launch, so changing equipment cannot turn a flying arrow into spear practice.
        if (projectile.getPersistentData().contains(SHOT_WEAPON)) return;
        WeaponProficiency weapon = WeaponProficiency.classify(owner.getMainHandItem());
        projectile.getPersistentData().putString(SHOT_WEAPON, weapon != null && weapon.ranged() ? weapon.name() : "NONE");
    }

    public static WeaponProficiency weapon(DamageSource source, LivingEntity attacker) {
        if (source.getDirectEntity() instanceof Projectile projectile)
            return WeaponProficiency.byId(projectile.getPersistentData().getString(SHOT_WEAPON));
        return source.getDirectEntity() == attacker ? WeaponProficiency.classify(attacker.getMainHandItem()) : null;
    }

    public static double damageBonus(Settlement.Soldier so, LivingEntity attacker, LivingEntity victim,
                                     WeaponProficiency weapon, boolean projectile, long time) {
        if (weapon == null) return 0;
        WeaponProficiency.initialise(so);
        double normal = 0;
        if (projectile && so.skills.contains(SoldierSkills.DEADEYE)) normal += .08;
        if (victim.getArmorValue() > 0 && so.skills.contains(SoldierSkills.ARMOUR_BREAKER)) normal += .05;
        if (victim.getHealth() < victim.getMaxHealth() * .3 && so.skills.contains(SoldierSkills.FINISHER)) normal += .05;
        if (attacker.getHealth() > attacker.getMaxHealth() * .75 && so.skills.contains(SoldierSkills.BATTLE_FOCUS)) normal += .03;
        if (!projectile && attacker.getLastHurtByMob() != null && attacker.tickCount - attacker.getLastHurtByMobTimestamp() <= 100
                && so.skills.contains(SoldierSkills.RELENTLESS)) normal += .03;
        double rare = 0;
        if (!projectile && weapon == so.preferredWeapon && so.legendary.contains(LegendaryPerk.KEEN_EDGE)) rare += .08;
        if (projectile && attacker.distanceToSqr(victim) > 144 && so.legendary.contains(LegendaryPerk.HAWKEYE)) rare += .08;
        if (!projectile && weapon == WeaponProficiency.SPEAR && attacker.getOffhandItem().getItem() instanceof ShieldItem
                && so.legendary.contains(LegendaryPerk.SPEAR_SENTINEL)) rare += .08;
        if (so.legendary.contains(LegendaryPerk.BATTLE_RHYTHM) && time < so.battleRhythmUntil) rare += .06;
        return Math.min(NORMAL_CAP, normal) + Math.min(WEAPON_CAP, WeaponProficiency.bonus(so, weapon)) + Math.min(LEGENDARY_CAP, rare);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void hurt(LivingHurtEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0 || !(event.getEntity().level() instanceof ServerLevel level)) return;
        LivingEntity victim = event.getEntity();
        var source = event.getSource();
        long time = level.getServer().overworld().getGameTime();
        float damage = event.getAmount();
        if (source.getEntity() instanceof LivingEntity attacker) {
            var record = SoldierProgression.record(level.getServer(), attacker);
            if (record != null) damage *= (float)(1 + damageBonus(record.soldier(), attacker, victim,
                    weapon(source, attacker), source.getDirectEntity() instanceof Projectile, time));
        }
        var defender = SoldierProgression.record(level.getServer(), victim);
        if (defender != null && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            var so = defender.soldier();
            double reduction = 0;
            if (source.getDirectEntity() instanceof Projectile && victim.getOffhandItem().getItem() instanceof ShieldItem
                    && so.skills.contains(SoldierSkills.SHIELD_GUARD)) reduction += .10;
            if (victim.getHealth() < victim.getMaxHealth() * .3 && so.skills.contains(SoldierSkills.LAST_STAND)) reduction += .10;
            if (source.is(DamageTypeTags.IS_FIRE)) {
                if (so.skills.contains(SoldierSkills.FIRE_WARD)) reduction += .15;
                if (so.legendary.contains(LegendaryPerk.DRAGONHEART)) reduction += .15;
            }
            if (source.is(DamageTypeTags.IS_EXPLOSION) && so.skills.contains(SoldierSkills.BLAST_WARD)) reduction += .15;
            if (victim.getHealth() < victim.getMaxHealth() * .4 && so.legendary.contains(LegendaryPerk.STEADFAST)) reduction += .08;
            var owner = defender.settlement().owner == null ? null : level.getPlayerByUUID(defender.settlement().owner);
            if (owner != null && victim.distanceToSqr(owner) <= 100 && so.legendary.contains(LegendaryPerk.WARDEN)) reduction += .06;
            if (victim.getHealth() < victim.getMaxHealth() * .4 && trigger(so, LegendaryPerk.IRON_WILL, time, 1800)) reduction += .20;
            damage *= (float)(1 - Math.min(DEFENCE_CAP, reduction));
        }
        event.setAmount(damage);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void practice(LivingDamageEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0 || !(event.getEntity() instanceof Enemy)
                || !(event.getEntity().level() instanceof ServerLevel level)
                || !(event.getSource().getEntity() instanceof LivingEntity attacker)) return;
        var record = SoldierProgression.record(level.getServer(), attacker);
        if (record == null) return;
        int xp = Math.max(1, Math.min(8, (int)Math.ceil(Math.min(event.getAmount(), event.getEntity().getHealth()))));
        WeaponProficiency.practice(record.soldier(), weapon(event.getSource(), attacker), xp);
        record.settlement().dirty = true;
    }

    public static void onKill(Settlement.Soldier so, LivingEntity attacker, DamageSource source) {
        long time = now(attacker);
        WeaponProficiency.practice(so, weapon(source, attacker), 20);
        if (trigger(so, LegendaryPerk.SECOND_WIND, time, 600)) attacker.heal(2);
        if (trigger(so, LegendaryPerk.BATTLE_RHYTHM, time, 600)) so.battleRhythmUntil = time + 160;
    }
    public static boolean trigger(Settlement.Soldier so, LegendaryPerk perk, long time, int cooldown) {
        if (!so.legendary.contains(perk) || time < so.legendaryCooldowns.getOrDefault(perk, 0L)) return false;
        so.legendaryCooldowns.put(perk, time + cooldown);
        return true;
    }
    private RecruitCombat() {}
}
