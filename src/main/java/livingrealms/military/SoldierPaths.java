package livingrealms.military;

import livingrealms.LivingRealms;
import livingrealms.settlement.Settlement;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ShieldItem;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;

/**
 * Specialist paths: choosing one, learning its skills, and what they do in a fight. Path skills have their own
 * budgets on top of the general ones (PATH_CAP damage, PATH_DEFENCE_CAP reduction); a legendary mastery adds its
 * own fixed bonus beyond that, since it is the end of a recruit's training.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class SoldierPaths {
    public static final double PATH_CAP = .30, PATH_DEFENCE_CAP = .40, RECKLESS_PENALTY = .05;
    public static final float COLOSSUS_SIZE = 1.6f, TITAN_SIZE = 1.8f, JUGGERNAUT_SIZE = 1.4f;
    public static final int UNDYING_COOLDOWN = 3600, RAGE_TICKS = 200, RAMPAGE_TICKS = 120, MARK_TICKS = 160;
    static final String MARK = "LivingRealmsMarkedBy", MARK_UNTIL = "LivingRealmsMarkedUntil", SIZE_KEY = "LivingRealmsPath";
    private static final UUID ARMOUR = UUID.fromString("5b0e2c4d-6a1f-4c55-9e1e-2f3a7d5c0a01");
    private static final UUID RESIST = UUID.fromString("5b0e2c4d-6a1f-4c55-9e1e-2f3a7d5c0a02");
    private static final UUID RANGE = UUID.fromString("5b0e2c4d-6a1f-4c55-9e1e-2f3a7d5c0a03");
    private static final UUID HEALTH = UUID.fromString("5b0e2c4d-6a1f-4c55-9e1e-2f3a7d5c0a04");
    private static final UUID SPEED = UUID.fromString("5b0e2c4d-6a1f-4c55-9e1e-2f3a7d5c0a05");
    private static final UUID MOUNT_HEALTH = UUID.fromString("5b0e2c4d-6a1f-4c55-9e1e-2f3a7d5c0a06");
    private static final UUID MOUNT_ARMOUR = UUID.fromString("5b0e2c4d-6a1f-4c55-9e1e-2f3a7d5c0a07");
    private static final UUID MOUNT_RESIST = UUID.fromString("5b0e2c4d-6a1f-4c55-9e1e-2f3a7d5c0a08");
    /** Splash, reflect and thorns damage must not trigger further path effects. */
    private static boolean busy;

    // ------------------------------------------------------------------ points, choice and learning

    /** Points spent across the general tree and the path. */
    public static int spent(Settlement.Soldier so) {
        int n = so.skills.size();
        for (PathSkill sk : so.pathSkills) n += sk.cost();
        return n;
    }

    public static int available(Settlement.Soldier so) { return Math.max(0, SoldierSkills.earnedPoints(so.skillXp) - spent(so)); }

    public static boolean has(Settlement.Soldier so, PathSkill sk) { return so.pathSkills.contains(sk); }

    @Nullable
    public static PathSkill mastery(Settlement.Soldier so) {
        for (PathSkill sk : so.pathSkills) if (sk.mastery()) return sk;
        return null;
    }

    /**
     * Sets the recruit on a path, changing its Villager Recruits body when the path needs one. Returns the entity
     * that now carries the record (the old one, or its replacement) through {@code out}, or an error.
     */
    @Nullable
    public static String choose(Settlement s, Settlement.Soldier so, @Nullable SoldierPath path, @Nullable Entity e, Entity[] out) {
        if (path == null) return "Unknown path";
        if (so.path == path) return so.name + " already follows the " + path.label + " path";
        if (so.path != null && !so.pathSkills.isEmpty()) return "Respec " + so.name + " first: a new path starts from its first skill";
        String why = path.blocker(so);
        if (why != null) return why;
        if (e == null || !e.isAlive()) return "Your recruit must be loaded and still belong to you";
        Entity body = e;
        if (path.becomes != null && so.type != path.becomes && RecruitsBridge.unitOf(e) != path.becomes) {
            body = RecruitsBridge.convert(e, path.becomes);
            if (body == null) return so.name + " must be on foot and loaded to retrain as a " + path.label;
            so.entity = body.getUUID();
            so.type = path.becomes;
            // a new favourite only if the old one doesn't suit the path
            if (path.weapons != null && !path.weapons.contains(so.preferredWeapon)) so.preferredWeapon = path.weapons.iterator().next();
        }
        so.path = path;
        so.pathHits = 0;
        if (out != null && out.length > 0) out[0] = body;
        if (body instanceof LivingEntity le) apply(le, so);
        s.log(so.name + " took up the " + path.label + " path");
        s.dirty = true;
        return null;
    }

    @Nullable
    public static String learn(Settlement s, Settlement.Soldier so, @Nullable PathSkill sk, @Nullable Entity e) {
        if (sk == null) return "Unknown path skill";
        if (so.path == null) return "Choose a path first";
        if (sk.path != so.path) return sk.label + " belongs to the " + sk.path.label + " path";
        if (has(so, sk)) return so.name + " already knows " + sk.label;
        if (sk.mastery() && mastery(so) != null) return so.name + " already mastered " + mastery(so).label + "; respec to choose another";
        PathSkill before = sk.prerequisite();
        if (before != null && !has(so, before)) return "Learn " + before.label + " first";
        if (SoldierSkills.earnedPoints(so.skillXp) < sk.requiredRank()) return sk.label + " needs skill rank " + sk.requiredRank();
        if (available(so) < sk.cost()) return sk.label + " costs " + sk.cost() + (sk.cost() == 1 ? " point" : " points") + "; earn more through combat or drill";
        if (e == null || !e.isAlive()) return "Your recruit must be loaded and still belong to you";
        so.pathSkills.add(sk);
        if (e instanceof LivingEntity le) apply(le, so);
        s.log(so.name + (sk.mastery() ? " attained the legendary mastery " : " learned ") + sk.label);
        s.dirty = true;
        return null;
    }

    // ------------------------------------------------------------------ attributes and size

    /** Called every second with the other skills: armour, range, giant bodies and the knight's horse. */
    public static void apply(LivingEntity e, Settlement.Soldier so) {
        boolean shield = e.getOffhandItem().getItem() instanceof ShieldItem;
        long now = e.getServer() == null ? 0 : e.getServer().overworld().getGameTime();
        PathSkill m = mastery(so);
        double armour = (has(so, PathSkill.SHIELD_DRILL) && shield ? 3 : 0) + (m == PathSkill.COLOSSUS ? 4 : 0);
        double resist = (has(so, PathSkill.IMMOVABLE) ? .6 : 0) + (m != null && m.giant() ? 1 : 0);
        double health = m == PathSkill.COLOSSUS ? 20 : m == PathSkill.TITAN ? 30 : m == PathSkill.JUGGERNAUT ? 20 : 0;
        double speed = (m == PathSkill.TITAN ? -.10 : 0) + (has(so, PathSkill.RAMPAGE) && now < so.rampageUntil ? .20 : 0);
        modifier(e, Attributes.ARMOR, ARMOUR, armour, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.KNOCKBACK_RESISTANCE, RESIST, Math.min(1, resist), AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.FOLLOW_RANGE, RANGE, has(so, PathSkill.EAGLE_EYE) ? 12 : 0, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.MAX_HEALTH, HEALTH, health, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.MOVEMENT_SPEED, SPEED, speed, AttributeModifier.Operation.MULTIPLY_TOTAL);
        float size = m == PathSkill.COLOSSUS ? COLOSSUS_SIZE : m == PathSkill.TITAN ? TITAN_SIZE : m == PathSkill.JUGGERNAUT ? JUGGERNAUT_SIZE : 1;
        PehkuiBridge.scale(e, SIZE_KEY, size);
        if (e.getVehicle() instanceof LivingEntity horse && so.path == SoldierPath.KNIGHT) {
            boolean big = m == PathSkill.JUGGERNAUT;
            modifier(horse, Attributes.MAX_HEALTH, MOUNT_HEALTH, (has(so, PathSkill.BARDING) ? 10 : 0) + (big ? 20 : 0), AttributeModifier.Operation.ADDITION);
            modifier(horse, Attributes.ARMOR, MOUNT_ARMOUR, has(so, PathSkill.BARDING) ? 4 : 0, AttributeModifier.Operation.ADDITION);
            modifier(horse, Attributes.KNOCKBACK_RESISTANCE, MOUNT_RESIST, big ? 1 : 0, AttributeModifier.Operation.ADDITION);
            PehkuiBridge.scale(horse, SIZE_KEY, big ? JUGGERNAUT_SIZE : 1);
            if (horse.getHealth() > horse.getMaxHealth()) horse.setHealth(horse.getMaxHealth());
        }
        if (e.getHealth() > e.getMaxHealth()) e.setHealth(e.getMaxHealth());
    }

    public static void clear(LivingEntity e) {
        for (UUID id : new UUID[]{ARMOUR, RESIST, RANGE, HEALTH, SPEED}) for (Attribute a : new Attribute[]{Attributes.ARMOR,
                Attributes.KNOCKBACK_RESISTANCE, Attributes.FOLLOW_RANGE, Attributes.MAX_HEALTH, Attributes.MOVEMENT_SPEED}) {
            var instance = e.getAttribute(a);
            if (instance != null) instance.removeModifier(id);
        }
        PehkuiBridge.scale(e, SIZE_KEY, 1);
        if (e.getVehicle() instanceof LivingEntity horse) {
            modifier(horse, Attributes.MAX_HEALTH, MOUNT_HEALTH, 0, AttributeModifier.Operation.ADDITION);
            modifier(horse, Attributes.ARMOR, MOUNT_ARMOUR, 0, AttributeModifier.Operation.ADDITION);
            modifier(horse, Attributes.KNOCKBACK_RESISTANCE, MOUNT_RESIST, 0, AttributeModifier.Operation.ADDITION);
            PehkuiBridge.scale(horse, SIZE_KEY, 1);
        }
        if (e.getHealth() > e.getMaxHealth()) e.setHealth(e.getMaxHealth());
    }

    private static void modifier(LivingEntity e, Attribute attribute, UUID id, double amount, AttributeModifier.Operation op) {
        var instance = e.getAttribute(attribute);
        if (instance == null) return;
        var old = instance.getModifier(id);
        if (old != null && old.getAmount() == amount && old.getOperation() == op) return;
        if (old != null) instance.removeModifier(id);
        if (amount != 0) instance.addTransientModifier(new AttributeModifier(id, "Living Realms path", amount, op));
    }

    // ------------------------------------------------------------------ combat

    /** Extra damage for a town recruit's hit: path skills (capped at PATH_CAP), auras and marks, then mastery. */
    public static double attack(@Nullable Settlement s, Settlement.Soldier so, LivingEntity attacker, LivingEntity victim,
                                @Nullable WeaponProficiency weapon, boolean projectile, long time) {
        if (busy) return 0;
        double path = 0, mastery = 0;
        if (s != null) path += aura(s, attacker, victim, time);
        if (so.path != null && so.path.equipped(attacker, weapon)) {
            PathSkill m = mastery(so);
            switch (so.path) {
                case SWORDSMAN -> {
                    if (has(so, PathSkill.BLADE_DRILL)) path += .08;
                    if (has(so, PathSkill.DUELIST) && attacker.getLastHurtByMob() == victim) path += .12;
                    if (has(so, PathSkill.FLURRY) && !projectile && ++so.pathHits % 3 == 0) mastery += .50;
                    if (m == PathSkill.BLADEMASTER) mastery += .30;
                    if (m == PathSkill.WHIRLWIND) mastery += .15;
                }
                case SHIELDMAN -> { if (m == PathSkill.COLOSSUS) mastery += .15; }
                case BERSERKER -> {
                    if (has(so, PathSkill.FURY)) path += .18 * (1 - attacker.getHealth() / Math.max(1, attacker.getMaxHealth()));
                    if (has(so, PathSkill.RECKLESS)) path += .12;
                    if (has(so, PathSkill.RAMPAGE) && time < so.rampageUntil) path += .10;
                    if (m == PathSkill.TITAN) mastery += .30;
                    if (m == PathSkill.UNDYING_RAGE && time < so.rageUntil) mastery += .40;
                }
                case SPEARMAN -> {
                    if (has(so, PathSkill.PIKE_DRILL)) path += .08;
                    if (has(so, PathSkill.SET_FOR_CHARGE) && (victim.isPassenger() || victim.getBbWidth() > 1.2f)) path += .20;
                    if (has(so, PathSkill.PHALANX) && s != null && SoldierSpecials.allies(s, attacker, 4).size() >= 2) path += .10;
                    if (m == PathSkill.DRAGONBANE && victim.getMaxHealth() >= 60) mastery += .50;
                    if (m == PathSkill.HEDGEHOG) mastery += .15;
                }
                case MARKSMAN -> {
                    if (has(so, PathSkill.STEADY_AIM)) path += .08;
                    if (has(so, PathSkill.PIERCING_SHOT) && victim.getArmorValue() > 0) path += .15;
                    if (has(so, PathSkill.HEADHUNTER) && attacker.distanceToSqr(victim) > 16 * 16) path += .15;
                    if (m == PathSkill.DEADSHOT && projectile && ++so.pathHits % 4 == 0) mastery += 2;
                }
                case KNIGHT -> {
                    if (has(so, PathSkill.MOUNTED_DRILL)) path += .10;
                    if (has(so, PathSkill.LANCE_CHARGE) && attacker.getVehicle() != null
                            && attacker.getVehicle().getDeltaMovement().horizontalDistanceSqr() > .15 * .15) path += .20;
                    if (m == PathSkill.JUGGERNAUT) mastery += .20;
                }
            }
        }
        return Math.min(PATH_CAP, path) + mastery;
    }

    /** Hunter's mark and the banner auras of other recruits of the same town. */
    static double aura(Settlement s, LivingEntity attacker, LivingEntity victim, long time) {
        double bonus = 0;
        CompoundTag tag = victim.getPersistentData();
        if (tag.hasUUID(MARK) && tag.getUUID(MARK).equals(s.id) && time < tag.getLong(MARK_UNTIL)) bonus += .25;
        double banner = 0;
        for (Settlement.Soldier other : s.soldiers) {
            if (other.path != SoldierPath.KNIGHT) continue;
            PathSkill m = mastery(other);
            double radius = m == PathSkill.WARLORD ? 12 : has(other, PathSkill.BANNER) ? 8 : 0;
            if (radius == 0) continue;
            Entity e = attacker.level() instanceof ServerLevel level ? level.getEntity(other.entity) : null;
            if (e == null || !e.isAlive() || e.distanceToSqr(attacker) > radius * radius) continue;
            banner = Math.max(banner, m == PathSkill.WARLORD ? .15 : .05);
        }
        return bonus + banner;
    }

    /** Damage reduction for a town recruit: its own path and the shield and banner auras around it. May be negative. */
    public static double defend(@Nullable Settlement s, Settlement.Soldier so, LivingEntity victim, DamageSource source, long time) {
        boolean projectile = source.getDirectEntity() instanceof Projectile, melee = !projectile && source.getEntity() != null && source.getDirectEntity() == source.getEntity();
        boolean shield = victim.getOffhandItem().getItem() instanceof ShieldItem;
        double r = 0;
        PathSkill m = mastery(so);
        if (so.path == SoldierPath.SWORDSMAN && has(so, PathSkill.PARRY) && melee
                && SoldierPath.SWORDSMAN.weapons.contains(WeaponProficiency.classify(victim.getMainHandItem()))) r += .15;
        if (so.path == SoldierPath.SHIELDMAN && shield) {
            if (has(so, PathSkill.BRACE) && melee) r += .15;
            if (has(so, PathSkill.IMMOVABLE) && projectile) r += .30;
        }
        if (has(so, PathSkill.RECKLESS)) r -= RECKLESS_PENALTY;
        if (has(so, PathSkill.PHALANX) && s != null && SoldierSpecials.allies(s, victim, 4).size() >= 2) r += .10;
        if (m == PathSkill.AEGIS) r += .20;
        if (s != null) r += guarded(s, so, victim);
        return Math.max(-RECKLESS_PENALTY, Math.min(PATH_DEFENCE_CAP, r));
    }

    /** The best shield aura (Guardian 10%, Aegis 20%) and banner (Warlord 10%) protecting this recruit. */
    static double guarded(Settlement s, Settlement.Soldier so, LivingEntity victim) {
        double shield = 0, banner = 0;
        for (Settlement.Soldier other : s.soldiers) {
            if (other == so || other.path == null) continue;
            PathSkill m = mastery(other);
            double radius = m == PathSkill.AEGIS ? 6 : m == PathSkill.WARLORD ? 12 : has(other, PathSkill.GUARDIAN) ? 4 : 0;
            if (radius == 0) continue;
            Entity e = victim.level() instanceof ServerLevel level ? level.getEntity(other.entity) : null;
            if (e == null || !e.isAlive() || e.distanceToSqr(victim) > radius * radius) continue;
            if (m == PathSkill.WARLORD) banner = .10;
            else shield = Math.max(shield, m == PathSkill.AEGIS ? .20 : .10);
        }
        return shield + banner;
    }

    /** Effects after a recruit's hit lands: splash, shove and marks. */
    public static void afterHit(Settlement s, Settlement.Soldier so, LivingEntity attacker, LivingEntity victim,
                                @Nullable WeaponProficiency weapon, boolean projectile, float damage, long time) {
        if (busy || so.path == null || !(attacker.level() instanceof ServerLevel level)) return;
        PathSkill m = mastery(so);
        if (so.path == SoldierPath.MARKSMAN && m == PathSkill.HUNTERS_MARK) {
            victim.getPersistentData().putUUID(MARK, s.id);
            victim.getPersistentData().putLong(MARK_UNTIL, time + MARK_TICKS);
        }
        if (!so.path.equipped(attacker, weapon) || projectile) return;
        if (so.path == SoldierPath.SPEARMAN && has(so, PathSkill.KEEP_BACK))
            victim.knockback(.9, attacker.getX() - victim.getX(), attacker.getZ() - victim.getZ());
        if (m == PathSkill.WHIRLWIND) {
            busy = true;
            try {
                int n = 0;
                for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, victim.getBoundingBox().inflate(3),
                        x -> x != victim && x != attacker && x.isAlive() && SoldierSpecials.hostile(attacker, x))) {
                    if (n++ >= 4) break;
                    other.hurt(attacker.damageSources().mobAttack(attacker), damage * .6f);
                }
            } finally { busy = false; }
        }
    }

    /** Effects when a recruit is struck in melee: the blademaster's answer and the hedgehog's spines. */
    public static void struck(Settlement.Soldier so, LivingEntity victim, DamageSource source, float damage) {
        if (busy || so.path == null || !(source.getEntity() instanceof LivingEntity attacker) || source.getDirectEntity() != attacker
                || attacker == victim || !attacker.isAlive()) return;
        PathSkill m = mastery(so);
        float back = m == PathSkill.BLADEMASTER ? damage * .3f : m == PathSkill.HEDGEHOG ? 4 : 0;
        if (back <= 0) return;
        busy = true;
        try { attacker.hurt(victim.damageSources().thorns(victim), back); } finally { busy = false; }
    }

    public static void onKill(Settlement.Soldier so, LivingEntity attacker, long time) {
        if (so.path != SoldierPath.BERSERKER) return;
        if (has(so, PathSkill.BLOODLUST)) attacker.heal(3);
        if (has(so, PathSkill.RAMPAGE)) { so.rampageUntil = time + RAMPAGE_TICKS; apply(attacker, so); }
    }

    /** Undying Rage: a killing blow leaves the berserker on 1 HP, once every three minutes. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void lethal(LivingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (event.isCanceled() || event.getAmount() < victim.getHealth() || !(victim.level() instanceof ServerLevel level)
                || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY) || !RecruitsBridge.isRecruit(victim)) return;
        var r = SoldierProgression.record(level.getServer(), victim);
        if (r == null || mastery(r.soldier()) != PathSkill.UNDYING_RAGE) return;
        long now = level.getServer().overworld().getGameTime();
        if (!undying(r.soldier(), now)) return;
        event.setAmount(Math.max(0, victim.getHealth() - 1));
        r.settlement().log(r.soldier().name + " refused to fall");
        r.settlement().dirty = true;
    }

    /** Spends the berserker's undying charge if it's ready. */
    public static boolean undying(Settlement.Soldier so, long now) {
        if (now < so.undyingReadyAt && so.undyingReadyAt - now <= UNDYING_COOLDOWN) return false;
        so.undyingReadyAt = now + UNDYING_COOLDOWN;
        so.rageUntil = now + RAGE_TICKS;
        return true;
    }

    // ------------------------------------------------------------------ UI contract

    /** {path, paths:[{id,label,desc,gear,state,req,becomes}], pathTree:[{id,label,desc,tier,cost,rank,state,req}], points} */
    public static void describe(Settlement.Soldier so, CompoundTag out) {
        int rank = SoldierSkills.earnedPoints(so.skillXp), points = available(so);
        out.putInt("points", points);
        out.putString("path", so.path == null ? "" : so.path.name());
        out.putInt("pathRank", SoldierPath.CHOOSE_RANK);
        ListTag paths = new ListTag();
        for (SoldierPath p : SoldierPath.values()) {
            CompoundTag t = new CompoundTag();
            String why = so.path == p ? null : so.path != null && !so.pathSkills.isEmpty() ? "Respec to change path" : p.blocker(so);
            t.putString("id", p.name()); t.putString("label", p.label); t.putString("desc", p.description);
            t.putString("gear", "Needs " + p.gear()); t.putString("fits", p.fitsWho());
            t.putString("becomes", p.becomes != null && p.becomes != so.type ? "Retrains as a " + p.becomes.label.toLowerCase() : "");
            t.putString("state", so.path == p ? "chosen" : why == null ? "available" : "locked");
            t.putString("req", why == null ? "" : why);
            paths.add(t);
        }
        out.put("paths", paths);
        ListTag skills = new ListTag();
        if (so.path != null) for (PathSkill sk : PathSkill.of(so.path)) {
            CompoundTag t = new CompoundTag();
            PathSkill before = sk.prerequisite(), m = mastery(so);
            String why = has(so, sk) ? "" : sk.mastery() && m != null ? "Mastered " + m.label
                    : before != null && !has(so, before) ? "Needs " + before.label
                    : rank < sk.requiredRank() ? "Needs skill rank " + sk.requiredRank()
                    : points < sk.cost() ? "Needs " + sk.cost() + (sk.cost() == 1 ? " point" : " points") : "";
            t.putString("id", sk.name()); t.putString("label", sk.label); t.putString("desc", sk.description);
            t.putInt("tier", sk.tier); t.putInt("cost", sk.cost()); t.putInt("rank", sk.requiredRank());
            t.putString("state", has(so, sk) ? "learned" : why.isEmpty() ? "available" : "locked");
            t.putString("req", why);
            skills.add(t);
        }
        out.put("pathTree", skills);
    }

    private SoldierPaths() {}
}
