package livingrealms.military;

import livingrealms.LivingRealms;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/** Loaded recruits progress and escort independently of whether their town is loaded. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class SoldierProgression {
    private static final UUID HEALTH = UUID.fromString("90ce70cb-bd00-4ef0-9331-610936330b01");
    private static final UUID ARMOUR = UUID.fromString("90ce70cb-bd00-4ef0-9331-610936330b02");
    private static final UUID ATTACK = UUID.fromString("90ce70cb-bd00-4ef0-9331-610936330b03");
    private static final UUID SPEED = UUID.fromString("90ce70cb-bd00-4ef0-9331-610936330b04");
    public record Record(Settlement settlement, Settlement.Soldier soldier) {}

    public static Entity findEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    public static Record record(MinecraftServer server, Entity entity) {
        if (!RecruitsBridge.isRecruit(entity)) return null;
        for (Settlement s : SettlementManager.get(server).all())
            for (Settlement.Soldier so : s.soldiers)
                if (so.entity.equals(entity.getUUID())) return new Record(s, so);
        return null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void killedHostile(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Enemy)
                || !(event.getEntity().level() instanceof ServerLevel level)) return;
        Entity killer = event.getSource().getEntity();
        if (killer == null) return;
        Record r = record(level.getServer(), killer);
        if (r == null || !killer.isAlive()) return;
        SoldierRanks.grant(r.settlement, r.soldier, killer, SoldierSkills.XP_PER_KILL, false, level.getServer().overworld().getGameTime());
        if (killer instanceof LivingEntity living) RecruitCombat.onKill(r.soldier, living, event.getSource());
        r.settlement.dirty = true;
    }

    /** Damage to hostiles trickles XP: 1 per 5 real damage, at most 3 per hit, inside the daily cap. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void damagedHostile(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        if (event.isCanceled() || !(target instanceof Enemy) || !(target.level() instanceof ServerLevel level)) return;
        Entity attacker = event.getSource().getEntity();
        if (attacker == null || attacker == target || !attacker.isAlive() || !RecruitsBridge.isRecruit(attacker)) return;
        Record r = record(level.getServer(), attacker);
        if (r == null) return;
        r.soldier.damageBank += Math.min(Math.min(event.getAmount(), target.getHealth()), SoldierRanks.DAMAGE_PER_XP * SoldierRanks.MAX_HIT_XP);
        int xp = (int) (r.soldier.damageBank / SoldierRanks.DAMAGE_PER_XP);
        if (xp <= 0) return;
        r.soldier.damageBank -= xp * SoldierRanks.DAMAGE_PER_XP;
        SoldierRanks.grant(r.settlement, r.soldier, attacker, xp, true, level.getServer().overworld().getGameTime());
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 0) return;
        MinecraftServer server = event.getServer();
        for (Settlement s : SettlementManager.get(server).all()) for (Settlement.Soldier so : s.soldiers) {
            Entity e = findEntity(server, so.entity);
            if (!(e instanceof LivingEntity le) || !le.isAlive() || !RecruitsBridge.isRecruit(e)) {
                if (so.seen) { so.seen = false; s.dirty = true; }
                continue;
            }
            RecruitsBridge.allegiance(e, s.kingdomId());
            WeaponProficiency.initialise(so);
            int previous = so.legendaryLevelChecked;
            for (LegendaryPerk perk : LegendaryPerk.levelUp(so, RecruitsBridge.level(e), le.getRandom()))
                s.log(so.name + " developed the legendary perk " + perk.label);
            if (previous != so.legendaryLevelChecked) s.dirty = true;
            applySkills(le, so);
            if (!so.seen || so.health != le.getHealth() || so.maxHealth != le.getMaxHealth()) s.dirty = true;
            so.seen = true;
            so.health = le.getHealth(); so.maxHealth = le.getMaxHealth();
            so.level = RecruitsBridge.level(e); so.kills = RecruitsBridge.kills(e); so.morale = RecruitsBridge.morale(e);
            if (so.royalGuard && escort(s, so, e) && server.getTickCount() % SoldierRanks.ESCORT_TICKS == 0) {
                ServerPlayer owner = server.getPlayerList().getPlayer(s.owner);
                if (owner != null && owner.isAlive() && owner.level() == e.level() && owner.distanceToSqr(e) < 32 * 32)
                    SoldierRanks.grant(s, so, e, SoldierRanks.ESCORT_XP, true, server.overworld().getGameTime());
            }
            if (so.skills.contains(SoldierSkills.FIELD_MEDIC) && server.getTickCount() % 100 == 0
                    && !s.starving && (!(le instanceof Mob mob) || mob.getTarget() == null)
                    && le.tickCount - le.getLastHurtByMobTimestamp() > 100) le.heal(so.skills.contains(SoldierSkills.RECOVERY) ? 2 : 1);
        }
    }

    /** Returns true only while the personal escort order supersedes town orders. */
    public static boolean escort(Settlement s, Settlement.Soldier so, Entity e) {
        if (!so.royalGuard || s.owner == null || !s.owner.equals(RecruitsBridge.owner(e))) return false;
        ServerPlayer owner = e.getServer().getPlayerList().getPlayer(s.owner);
        if (owner == null || !owner.isAlive() || owner.level() != e.level()) {
            // Do not send an unattended guard back across the world or force-load chunks.
            if (e instanceof Mob guard) guard.setTarget(null);
            RecruitsBridge.holdAt(e, e.blockPosition());
            return true;
        }
        if (!s.starving) RecruitsBridge.supply(e);
        RecruitsBridge.follow(e, owner.getUUID());
        RecruitsBridge.aggressive(e);
        if (e instanceof Mob guard) {
            LivingEntity threat = owner.getLastHurtByMob();
            if (threat == null || !threat.isAlive() || !guard.canAttack(threat)
                    || threat.distanceToSqr(owner) > 32 * 32 || guard.isAlliedTo(threat) || owner.isAlliedTo(threat)
                    || (threat instanceof net.minecraft.world.entity.player.Player other
                    && (!owner.server.isPvpAllowed() || !owner.canHarmPlayer(other)))) threat = null;
            if (threat == null) {
                double nearest = 12 * 12;
                for (Mob candidate : owner.level().getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(12),
                        mob -> mob instanceof Enemy && mob.isAlive() && !guard.isAlliedTo(mob))) {
                    double d = candidate.distanceToSqr(owner);
                    if (d < nearest) { nearest = d; threat = candidate; }
                }
            }
            if (threat != null) guard.setTarget(threat);
            else if (guard.getTarget() != null && guard.getTarget().distanceToSqr(owner) > 32 * 32) guard.setTarget(null);
        }
        return true;
    }

    private static final UUID RESIST = UUID.fromString("90ce70cb-bd00-4ef0-9331-610936330b05");
    private static final UUID TOUGH = UUID.fromString("90ce70cb-bd00-4ef0-9331-610936330b06");
    private static final UUID RANGE = UUID.fromString("90ce70cb-bd00-4ef0-9331-610936330b07");

    public static void applySkills(LivingEntity e, Settlement.Soldier so) {
        RecruitOrigins.sync(so, e);
        modifier(e, Attributes.KNOCKBACK_RESISTANCE, RESIST,
                (so.skills.contains(SoldierSkills.RESOLVE) ? .1 : 0) + (so.legendary.contains(LegendaryPerk.SUREFOOTED) ? .15 : 0), AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.ARMOR_TOUGHNESS, TOUGH, so.skills.contains(SoldierSkills.BULWARK) ? 1 : 0, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.FOLLOW_RANGE, RANGE,
                (so.skills.contains(SoldierSkills.SCOUT) ? 8 : 0) + (so.legendary.contains(LegendaryPerk.FAR_SIGHT) ? 6 : 0), AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.MAX_HEALTH, HEALTH, so.skills.contains(SoldierSkills.ENDURANCE) ? 4 : 0, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.ARMOR, ARMOUR, so.skills.contains(SoldierSkills.IRONCLAD) ? 2 : 0, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.ATTACK_DAMAGE, ATTACK, so.skills.contains(SoldierSkills.WEAPON_DRILLS) ? 2 : 0, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.MOVEMENT_SPEED, SPEED, so.skills.contains(SoldierSkills.MARCHING) ? .1 : 0, AttributeModifier.Operation.MULTIPLY_TOTAL);
        if (e.getHealth() > e.getMaxHealth()) e.setHealth(e.getMaxHealth());
    }

    public static void clearSkills(LivingEntity e) {
        modifier(e, Attributes.KNOCKBACK_RESISTANCE, RESIST, 0, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.ARMOR_TOUGHNESS, TOUGH, 0, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.FOLLOW_RANGE, RANGE, 0, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.MAX_HEALTH, HEALTH, 0, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.ARMOR, ARMOUR, 0, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.ATTACK_DAMAGE, ATTACK, 0, AttributeModifier.Operation.ADDITION);
        modifier(e, Attributes.MOVEMENT_SPEED, SPEED, 0, AttributeModifier.Operation.MULTIPLY_TOTAL);
        if (e.getHealth() > e.getMaxHealth()) e.setHealth(e.getMaxHealth());
    }

    private static void modifier(LivingEntity e, Attribute attribute, UUID id, double amount, AttributeModifier.Operation op) {
        var instance = e.getAttribute(attribute);
        if (instance == null) return;
        var old = instance.getModifier(id);
        if (old != null && old.getAmount() == amount && old.getOperation() == op) return;
        if (old != null) instance.removeModifier(id);
        if (amount != 0) instance.addTransientModifier(new AttributeModifier(id, "Living Realms recruit skill", amount, op));
    }

    private SoldierProgression() {}
}
