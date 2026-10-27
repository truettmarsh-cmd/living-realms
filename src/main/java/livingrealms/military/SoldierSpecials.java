package livingrealms.military;

import livingrealms.LivingRealms;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** Server-side triggers for equipped special skills. Percent bonuses use the normal/defence budgets in RecruitCombat. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class SoldierSpecials {
    public static final double RALLY_BONUS = .05, CHARGE_BONUS = .10, AIMED_BONUS = .10, BRACE_REDUCTION = .15;
    /** Set while a special deals its own damage, so follow-up hits never chain further specials. */
    private static boolean busy;

    /** UI contract: fills "specials" (list of {id,label,desc,state,req,cooldown,active}) and "specialSlots". */
    public static void describe(Settlement s, Settlement.Soldier so, CompoundTag out) {
        int slots = SpecialSkill.slots(so), rank = SpecialSkill.rank(so);
        ListTag list = new ListTag();
        for (SpecialSkill sp : SpecialSkill.values()) {
            boolean chosen = so.specials.contains(sp);
            if (!sp.available() && !chosen) continue;
            CompoundTag t = new CompoundTag();
            t.putString("id", sp.name()); t.putString("label", sp.label); t.putString("desc", sp.description);
            t.putString("state", chosen ? "chosen" : slots > 0 && sp.eligible(so) ? "available" : "locked");
            t.putString("req", sp.requirement());
            t.putInt("cooldown", (int)(cooldown(so, sp) / 20));
            t.putBoolean("active", sp.active(so));
            list.add(t);
        }
        out.put("specials", list);
        out.putInt("specialSlots", slots);
        out.putInt("specialRank", rank);
        int next = 0;
        for (int r : SpecialSkill.SLOT_RANKS) if (rank < r) { next = r; break; }
        out.putInt("nextSpecialRank", next);
    }

    /** Equips, or unequips an already chosen special. Returns an error or null. */
    public static String choose(Settlement.Soldier so, String id) {
        SpecialSkill sp = SpecialSkill.byId(id);
        if (sp == null) return "Unknown special skill";
        if (so.specials.remove(sp)) return null;
        if (!sp.eligible(so)) return sp.label + " needs: " + sp.requirement();
        int slots = SpecialSkill.slots(so);
        if (slots == 0) return "Special slots open at skill rank " + SpecialSkill.SLOT_RANKS[0];
        if (so.specials.size() >= slots) return "All " + slots + " special slots are in use; unequip one first";
        so.specials.add(sp);
        return null;
    }

    public static long cooldown(Settlement.Soldier so, SpecialSkill sp) {
        return Math.round(sp.cooldown * 20 * (so.legendary.contains(LegendaryPerk.TACTICIAN) ? .75 : 1));
    }
    public static boolean ready(Settlement.Soldier so, SpecialSkill sp, long time) {
        if (!sp.active(so)) return false;
        long at = so.specialCooldowns.getOrDefault(sp, 0L);
        return time >= at || at > time + cooldown(so, sp); // a stale stamp from a rewound clock never locks forever
    }
    public static boolean use(Settlement.Soldier so, SpecialSkill sp, long time) {
        if (!ready(so, sp, time)) return false;
        so.specialCooldowns.put(sp, time + cooldown(so, sp));
        return true;
    }
    static boolean wields(SpecialSkill sp, WeaponProficiency w) { return sp.weapons == null || sp.weapons.contains(w); }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 10 != 0) return;
        MinecraftServer server = event.getServer();
        long time = server.overworld().getGameTime();
        for (Settlement s : SettlementManager.get(server).all()) for (Settlement.Soldier so : s.soldiers) {
            if (so.specials.isEmpty() && !so.legendary.contains(LegendaryPerk.UNBROKEN)) continue;
            Entity e = SoldierProgression.findEntity(server, so.entity);
            if (e instanceof Mob mob && mob.isAlive() && RecruitsBridge.isRecruit(e) && mob.level() instanceof ServerLevel level
                    && act(s, so, mob, level, time)) s.dirty = true;
        }
    }

    /** Tick-driven specials. Returns true when anything fired. */
    public static boolean act(Settlement s, Settlement.Soldier so, Mob mob, ServerLevel level, long time) {
        LivingEntity target = mob.getTarget();
        boolean fighting = target != null && target.isAlive(), used = false;
        float max = mob.getMaxHealth();
        if (fighting && mob.getHealth() < max * .5f && RecruitCombat.trigger(so, LegendaryPerk.UNBROKEN, time, 80)) mob.heal(1);
        if (mob.getHealth() < max * .35f && use(so, SpecialSkill.SECOND_WIND, time)) {
            mob.heal(Math.min(6, max * .25f));
            fx(level, mob, ParticleTypes.HEART, 6, SoundEvents.PLAYER_LEVELUP, 1.6f); used = true;
        }
        if (ready(so, SpecialSkill.FIELD_DRESSING, time)) {
            LivingEntity patient = null;
            List<LivingEntity> near = new ArrayList<>(allies(s, mob, 6));
            var owner = s.owner == null ? null : level.getPlayerByUUID(s.owner);
            if (owner != null && owner.isAlive() && owner.distanceToSqr(mob) <= 36) near.add(owner);
            for (LivingEntity x : near)
                if (x.getHealth() < x.getMaxHealth() * .5f && (patient == null || x.getHealth() / x.getMaxHealth() < patient.getHealth() / patient.getMaxHealth())) patient = x;
            if (patient != null && use(so, SpecialSkill.FIELD_DRESSING, time)) {
                patient.heal(4); mob.swing(InteractionHand.MAIN_HAND);
                fx(level, patient, ParticleTypes.HEART, 4, SoundEvents.ARMOR_EQUIP_LEATHER, 1.2f); used = true;
            }
        }
        if (!fighting) return used;
        double d = mob.distanceTo(target);
        WeaponProficiency held = WeaponProficiency.classify(mob.getMainHandItem());
        if (ready(so, SpecialSkill.RALLY_CRY, time)) {
            List<LivingEntity> allies = allies(s, mob, 10);
            if ((mob.getHealth() < max * .6f || hostiles(mob, 10) >= 3 || allies.stream().anyMatch(a -> a.getHealth() < a.getMaxHealth() * .5f))
                    && use(so, SpecialSkill.RALLY_CRY, time)) {
                Map<UUID, Settlement.Soldier> roster = new HashMap<>();
                for (Settlement.Soldier other : s.soldiers) roster.put(other.entity, other);
                List<LivingEntity> rallied = new ArrayList<>(allies.subList(0, Math.min(5, allies.size()))); rallied.add(mob);
                for (LivingEntity a : rallied) {
                    a.heal(2);
                    Settlement.Soldier r = roster.get(a.getUUID());
                    if (r != null) r.rallyUntil = time + 160;
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, a.getX(), a.getY(1), a.getZ(), 6, .4, .4, .4, 0);
                }
                mob.swing(InteractionHand.MAIN_HAND);
                fx(level, mob, ParticleTypes.NOTE, 3, SoundEvents.PILLAGER_CELEBRATE, .8f); used = true;
            }
        }
        if (ready(so, SpecialSkill.TAUNT, time)) {
            List<Mob> foes = level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(8), m -> m instanceof Enemy && m.isAlive()
                    && m.getTarget() != null && m.getTarget() != mob && protects(s, m.getTarget()));
            if (!foes.isEmpty() && use(so, SpecialSkill.TAUNT, time)) {
                for (Mob foe : foes.subList(0, Math.min(4, foes.size()))) {
                    foe.setTarget(mob);
                    level.sendParticles(ParticleTypes.ANGRY_VILLAGER, foe.getX(), foe.getY(1.1), foe.getZ(), 2, .2, .2, .2, 0);
                }
                mob.swing(InteractionHand.OFF_HAND);
                fx(level, mob, ParticleTypes.CRIT, 6, SoundEvents.RAVAGER_ROAR, 1.5f); used = true;
            }
        }
        if (held != null && wields(SpecialSkill.LUNGE, held) && d >= 3 && d <= 7 && mob.onGround() && mob.hasLineOfSight(target)
                && use(so, SpecialSkill.LUNGE, time)) {
            Vec3 dir = target.position().subtract(mob.position()).normalize();
            mob.setDeltaMovement(dir.x * .9, .3, dir.z * .9); mob.hurtMarked = true;
            strike(mob, target);
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0));
            level.sendParticles(ParticleTypes.CLOUD, mob.getX(), mob.getY(), mob.getZ(), 8, .3, .1, .3, .02);
            fx(level, target, ParticleTypes.SWEEP_ATTACK, 1, SoundEvents.TRIDENT_THROW, 1.2f); used = true;
        }
        if (mob.getVehicle() instanceof LivingEntity mount && !DragonRiders.dragon(mount) && d >= 6 && d <= 20
                && use(so, SpecialSkill.CHARGE, time)) {
            mount.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 60, 1));
            so.chargeUntil = time + 100;
            mob.swing(InteractionHand.MAIN_HAND);
            fx(level, mount, ParticleTypes.CLOUD, 10, SoundEvents.HORSE_ANGRY, 1f); used = true;
        }
        if (held != null && held.ranged() && wields(SpecialSkill.VOLLEY, held) && d >= 6 && d <= 28 && mob.hasLineOfSight(target)
                && use(so, SpecialSkill.VOLLEY, time)) {
            for (int i = 0; i < 3; i++) {
                Arrow arrow = new Arrow(level, mob);
                arrow.setBaseDamage(1.5); arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                double dx = target.getX() - mob.getX(), dy = target.getY(.33) - arrow.getY(), dz = target.getZ() - mob.getZ();
                arrow.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * .2, dz, 1.6f, 6f);
                level.addFreshEntity(arrow);
            }
            mob.swing(InteractionHand.MAIN_HAND);
            fx(level, mob, ParticleTypes.CRIT, 6, held == WeaponProficiency.CROSSBOW ? SoundEvents.CROSSBOW_SHOOT : SoundEvents.ARROW_SHOOT, .9f); used = true;
        }
        if (mob.getVehicle() != null && DragonRiders.dragon(mob.getVehicle()) && d <= 32 && use(so, SpecialSkill.DRAGONFIRE_STRAFE, time)) {
            List<LivingEntity> hit = new ArrayList<>(List.of(target));
            hit.addAll(level.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(4),
                    x -> x != target && x instanceof Enemy && x.isAlive() && !mob.isAlliedTo(x)).stream().limit(3).toList());
            Vec3 from = mob.getEyePosition(), to = target.position();
            for (int i = 0; i <= 12; i++) {
                Vec3 p = from.lerp(to, i / 12.0);
                level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 3, .2, .2, .2, .01);
            }
            busy = true;
            try { for (LivingEntity x : hit) { x.setSecondsOnFire(5); x.hurt(level.damageSources().mobAttack(mob), 4); } }
            finally { busy = false; }
            fx(level, target, ParticleTypes.LAVA, 6, SoundEvents.ENDER_DRAGON_SHOOT, 1f); used = true;
        }
        return used;
    }

    /** Attacker-side triggers. Returns an extra bonus that RecruitCombat adds to the capped normal budget. */
    public static double onAttack(Settlement.Soldier so, LivingEntity attacker, LivingEntity victim, DamageSource source,
                                  WeaponProficiency weapon, float damage, long time) {
        if (!(attacker.level() instanceof ServerLevel level) || victim == attacker) return 0;
        boolean projectile = source.getDirectEntity() instanceof Projectile;
        double extra = 0;
        if (!projectile && time < so.chargeUntil && SpecialSkill.CHARGE.active(so)) {
            so.chargeUntil = 0; extra += CHARGE_BONUS;
            victim.knockback(1, attacker.getX() - victim.getX(), attacker.getZ() - victim.getZ());
            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1));
            fx(level, victim, ParticleTypes.CRIT, 10, SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1f);
        }
        if (busy || weapon == null) return extra;
        if (!projectile && wields(SpecialSkill.STAGGER, weapon) && use(so, SpecialSkill.STAGGER, time)) {
            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0));
            victim.knockback(.5, attacker.getX() - victim.getX(), attacker.getZ() - victim.getZ());
            fx(level, victim, ParticleTypes.CRIT, 10, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, 1.3f);
        }
        if (!projectile && wields(SpecialSkill.DISARM, weapon) && use(so, SpecialSkill.DISARM, time)) {
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 0));
            fx(level, victim, ParticleTypes.ENCHANTED_HIT, 8, SoundEvents.ITEM_BREAK, 1.4f);
        }
        if (!projectile && wields(SpecialSkill.CLEAVE, weapon) && ready(so, SpecialSkill.CLEAVE, time)) {
            List<LivingEntity> others = level.getEntitiesOfClass(LivingEntity.class, victim.getBoundingBox().inflate(3),
                    x -> x != victim && x != attacker && x instanceof Enemy && x.isAlive() && !attacker.isAlliedTo(x));
            if (!others.isEmpty() && use(so, SpecialSkill.CLEAVE, time)) {
                busy = true;
                try { for (LivingEntity x : others.subList(0, Math.min(3, others.size()))) x.hurt(level.damageSources().mobAttack(attacker), damage * .4f); }
                finally { busy = false; }
                fx(level, victim, ParticleTypes.SWEEP_ATTACK, 3, SoundEvents.PLAYER_ATTACK_SWEEP, 1f);
            }
        }
        if (projectile && weapon.ranged() && attacker.distanceTo(victim) > 8 && use(so, SpecialSkill.AIMED_SHOT, time)) {
            extra += AIMED_BONUS;
            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
            fx(level, victim, ParticleTypes.CRIT, 12, SoundEvents.ARROW_HIT_PLAYER, .8f);
        }
        return extra;
    }

    /** Defender-side triggers. Returns extra damage reduction, still clamped by RecruitCombat.DEFENCE_CAP. */
    public static double onDefend(Settlement.Soldier so, LivingEntity victim, DamageSource source, long time) {
        if (!busy && victim.level() instanceof ServerLevel level && source.getEntity() instanceof LivingEntity attacker && attacker != victim) {
            boolean projectile = source.getDirectEntity() instanceof Projectile;
            if (victim.getOffhandItem().getItem() instanceof ShieldItem && (projectile || victim.getHealth() < victim.getMaxHealth() * .6f)
                    && use(so, SpecialSkill.SHIELD_WALL, time)) {
                so.braceUntil = time + 120;
                victim.swing(InteractionHand.OFF_HAND);
                fx(level, victim, ParticleTypes.ENCHANTED_HIT, 10, SoundEvents.SHIELD_BLOCK, 1f);
            }
            WeaponProficiency held = WeaponProficiency.classify(victim.getMainHandItem());
            if (!projectile && source.getDirectEntity() == attacker && victim instanceof Mob mob && held != null && wields(SpecialSkill.RIPOSTE, held)
                    && attacker.isAlive() && mob.distanceTo(attacker) <= 4 && use(so, SpecialSkill.RIPOSTE, time)) {
                strike(mob, attacker);
                fx(level, attacker, ParticleTypes.CRIT, 8, SoundEvents.PLAYER_ATTACK_STRONG, 1.2f);
            }
        }
        return time < so.braceUntil && SpecialSkill.SHIELD_WALL.active(so) ? BRACE_REDUCTION : 0;
    }

    static void strike(Mob mob, LivingEntity target) {
        busy = true;
        try { mob.swing(InteractionHand.MAIN_HAND); mob.doHurtTarget(target); } finally { busy = false; }
    }
    /** Loaded recruits of the same town within r blocks, excluding e. */
    public static List<LivingEntity> allies(Settlement s, LivingEntity e, double r) {
        Set<UUID> ids = new HashSet<>();
        for (Settlement.Soldier so : s.soldiers) ids.add(so.entity);
        return e.level().getEntitiesOfClass(LivingEntity.class, e.getBoundingBox().inflate(r), x -> x != e && x.isAlive() && ids.contains(x.getUUID()));
    }
    static boolean protects(Settlement s, LivingEntity x) {
        if (x.getUUID().equals(s.owner)) return true;
        for (Settlement.Soldier so : s.soldiers) if (so.entity.equals(x.getUUID())) return true;
        return false;
    }
    static int hostiles(LivingEntity e, double r) {
        return e.level().getEntitiesOfClass(LivingEntity.class, e.getBoundingBox().inflate(r), x -> x instanceof Enemy && x.isAlive()).size();
    }
    static void fx(ServerLevel level, Entity at, ParticleOptions particle, int count, SoundEvent sound, float pitch) {
        level.sendParticles(particle, at.getX(), at.getY(.6), at.getZ(), count, .4, .4, .4, .05);
        level.playSound(null, at.getX(), at.getY(), at.getZ(), sound, SoundSource.NEUTRAL, .8f, pitch);
    }
    private SoldierSpecials() {}
}
