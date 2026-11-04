package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.military.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import java.util.*;

/** Specialist paths: choice gates, body changes, learning order, shared points, combat effects, giants and saves. */
public final class PathsSelfTest {
    /** Why some checks didn't run in this world, or "". */
    public static String skipped = "";

    public static int run(ServerLevel level) {
        skipped = "";
        int checks = 0;
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "PathTestOwner"));
        Settlement s = new Settlement(); s.name = "Path test"; s.owner = owner.getUUID(); s.dim = level.dimension(); s.center = level.getSharedSpawnPos().offset(6, 0, 6);
        SettlementManager manager = SettlementManager.get(level.getServer());
        manager.add(s); level.getChunkAt(s.center);
        List<Entity> spawned = new ArrayList<>();
        try {
            // definitions
            for (SoldierPath p : SoldierPath.values()) {
                List<PathSkill> tree = PathSkill.of(p);
                check(tree.size() == 6 && tree.stream().filter(PathSkill::mastery).count() == 2, "Six skills, two masteries: " + p); checks++;
                for (PathSkill sk : tree) if (sk.tier > 1) { check(sk.prerequisite() != null && sk.prerequisite().tier == Math.min(4, sk.tier - 1), "Prerequisite " + sk); checks++; }
            }
            check(Arrays.stream(PathSkill.values()).filter(PathSkill::giant).count() == 3, "Three giant masteries"); checks++;
            // the Saint and Undying Rage change the damage at LOW, so battle reports and bonds (LOWEST) see what lands
            try {
                var lowest = net.minecraftforge.eventbus.api.EventPriority.LOWEST;
                check(SoldierPaths.class.getMethod("lethal", LivingDamageEvent.class).getAnnotation(net.minecraftforge.eventbus.api.SubscribeEvent.class).priority() == net.minecraftforge.eventbus.api.EventPriority.LOW
                        && livingrealms.battle.BattleReports.class.getMethod("damaged", LivingDamageEvent.class).getAnnotation(net.minecraftforge.eventbus.api.SubscribeEvent.class).priority() == lowest
                        && Bonds.class.getMethod("struck", LivingDamageEvent.class).getAnnotation(net.minecraftforge.eventbus.api.SubscribeEvent.class).priority() == lowest, "Saves at LOW, observers at LOWEST"); checks++;
            } catch (NoSuchMethodException ex) { throw new IllegalStateException("Paths: damage hooks missing", ex); }

            // choice gates and the shieldman's new body
            LivingEntity foot = spawn(level, UnitType.FOOTMAN, s, "Path Footman", spawned);
            RecruitsBridge.setSlot(foot, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            Settlement.Soldier so = soldier(s, foot, UnitType.FOOTMAN, WeaponProficiency.SWORD);
            UUID id = foot.getUUID();
            Entity[] out = new Entity[1];
            so.skillXp = 150;
            check(SoldierPaths.choose(s, so, SoldierPath.SHIELDMAN, foot, out) != null && so.path == null, "Path needs rank 4"); checks++;
            so.skillXp = 900;
            check(SoldierPaths.choose(s, so, SoldierPath.MARKSMAN, foot, out) != null, "Footmen cannot be marksmen"); checks++;
            check(SoldierPaths.choose(s, so, null, foot, out) != null, "Unknown path rejected"); checks++;
            check(SoldierPaths.choose(s, so, SoldierPath.SHIELDMAN, foot, out) == null && so.path == SoldierPath.SHIELDMAN, "Choose shieldman"); checks++;
            Entity body = out[0];
            spawned.add(body);
            check(body != null && body != foot && foot.isRemoved() && RecruitsBridge.unitOf(body) == UnitType.SHIELDBEARER
                    && so.type == UnitType.SHIELDBEARER && so.entity.equals(body.getUUID()), "Footman retrained as a shieldbearer"); checks++;
            check(body.getUUID().equals(id) && s.owner.equals(RecruitsBridge.owner(body))
                    && RecruitsBridge.getSlot(body, EquipmentSlot.MAINHAND).is(Items.IRON_SWORD), "Same recruit: UUID, owner and sword kept"); checks++;
            var rec = SoldierProgression.record(level.getServer(), body);
            check(rec != null && rec.soldier() == so, "Record follows the new body"); checks++;
            LivingEntity guard = (LivingEntity) body;

            // learning order, ranks and shared points
            check(SoldierPaths.learn(s, so, PathSkill.BRACE, guard) != null, "Tier 2 before tier 1 rejected"); checks++;
            check(SoldierPaths.learn(s, so, PathSkill.BLADE_DRILL, guard) != null, "Another path's skill rejected"); checks++;
            so.skillXp = 4 * 50;
            check(SoldierPaths.learn(s, so, PathSkill.SHIELD_DRILL, guard) != null, "Tier 1 needs rank 5"); checks++;
            so.skillXp = 900;
            so.skills.addAll(EnumSet.allOf(SoldierSkills.class));
            check(SoldierPaths.available(so) == 0 && SoldierPaths.learn(s, so, PathSkill.SHIELD_DRILL, guard) != null, "Points are shared with the general tree"); checks++;
            so.skills.clear();
            for (PathSkill sk : List.of(PathSkill.SHIELD_DRILL, PathSkill.BRACE, PathSkill.GUARDIAN, PathSkill.IMMOVABLE)) {
                check(SoldierPaths.learn(s, so, sk, guard) == null, "Learn " + sk); checks++;
            }
            check(SoldierPaths.available(so) == 14 && SoldierPaths.spent(so) == 4, "One point per path skill"); checks++;
            check(SoldierPaths.choose(s, so, SoldierPath.SWORDSMAN, guard, out) != null, "No path change once skills are learned"); checks++;

            // shield skills
            RecruitsBridge.setSlot(guard, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            SoldierProgression.applySkills(guard, so);
            double armour = guard.getArmorValue();
            RecruitsBridge.setSlot(guard, EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            SoldierProgression.applySkills(guard, so);
            check(armour - guard.getArmorValue() == 3, "Shield Drill: +3 armour with a shield only"); checks++;
            RecruitsBridge.setSlot(guard, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            var zombie = EntityType.ZOMBIE.create(level);
            zombie.moveTo(s.center.getX() + 1, s.center.getY(), s.center.getZ()); level.addFreshEntity(zombie); spawned.add(zombie);
            long now = RecruitCombat.now(guard);
            check(Math.abs(SoldierPaths.defend(s, so, guard, level.damageSources().mobAttack(zombie), now) - .15) < 1e-9, "Brace: 15% less melee damage"); checks++;

            // mastery: Colossus grows a giant; a second mastery is refused
            float hp = guard.getMaxHealth();
            check(SoldierPaths.learn(s, so, PathSkill.COLOSSUS, guard) == null && SoldierPaths.available(so) == 12, "Colossus costs two points"); checks++;
            SoldierProgression.applySkills(guard, so);
            check(guard.getMaxHealth() - hp >= 20 - .01 && guard.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE) >= 1, "Colossus: +20 health, no knockback: " + hp + " -> " + guard.getMaxHealth() + ", kb " + guard.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE)); checks++;
            if (PehkuiBridge.installed()) { check(Math.abs(PehkuiBridge.scale(guard, true) - SoldierPaths.COLOSSUS_SIZE * RecruitOrigins.origin(guard).height) < .01, "Colossus is a giant: " + PehkuiBridge.scale(guard, true)); checks++; }
            check(SoldierPaths.learn(s, so, PathSkill.AEGIS, guard) != null, "Only one mastery"); checks++;
            check(Math.abs(SoldierPaths.attack(s, so, guard, zombie, WeaponProficiency.SWORD, false, now) - .15) < 1e-9, "Colossus: +15% damage"); checks++;

            // saves
            Settlement.Soldier copy = Settlement.Soldier.load(so.save());
            check(copy.path == SoldierPath.SHIELDMAN && copy.pathSkills.equals(so.pathSkills), "Path persistence"); checks++;
            CompoundTag tampered = so.save();
            ListTag bad = new ListTag();
            for (String x : List.of("BLADE_DRILL", "AEGIS", "COLOSSUS", "NOPE", "SHIELD_DRILL")) bad.add(StringTag.valueOf(x));
            tampered.put("pathSkills", bad);
            copy = Settlement.Soldier.load(tampered);
            check(copy.pathSkills.equals(EnumSet.of(PathSkill.AEGIS, PathSkill.SHIELD_DRILL)), "Foreign skills and second masteries never load: " + copy.pathSkills); checks++;
            CompoundTag legacy = so.save(); legacy.remove("path"); legacy.remove("pathSkills");
            copy = Settlement.Soldier.load(legacy);
            check(copy.path == null && copy.pathSkills.isEmpty(), "Old saves load without a path"); checks++;

            // UI contract
            CompoundTag view = new CompoundTag(); SoldierPaths.describe(so, view);
            Map<String, String> states = new HashMap<>();
            for (Tag t : view.getList("pathTree", Tag.TAG_COMPOUND)) states.put(((CompoundTag) t).getString("id"), ((CompoundTag) t).getString("state"));
            check(view.getList("paths", Tag.TAG_COMPOUND).size() == SoldierPath.values().length && "learned".equals(states.get("COLOSSUS")) && "locked".equals(states.get("AEGIS"))
                    && view.getInt("points") == 12 && view.getString("path").equals("SHIELDMAN"), "Describe " + states); checks++;

            // respec refunds the path, keeps it, then a new path can be taken
            s.stock.add(Items.EMERALD, 500);
            check(SoldierRanks.respec(s, so, guard) == null && so.pathSkills.isEmpty() && so.path == SoldierPath.SHIELDMAN && SoldierPaths.available(so) == 18, "Respec refunds path skills"); checks++;
            check(Math.abs(guard.getMaxHealth() - hp) < .01, "Respec takes the giant's health away"); checks++;
            if (PehkuiBridge.installed()) { check(Math.abs(PehkuiBridge.scale(guard, true) - RecruitOrigins.origin(guard).height) < .01, "Respec shrinks the giant"); checks++; }
            check(SoldierPaths.choose(s, so, SoldierPath.BERSERKER, guard, out) == null && RecruitsBridge.unitOf(out[0]) == UnitType.FOOTMAN
                    && so.type == UnitType.FOOTMAN && so.preferredWeapon != WeaponProficiency.SWORD, "Shieldman back to a berserker footman with a berserker weapon"); checks++;
            LivingEntity bers = (LivingEntity) out[0];
            spawned.add(bers);

            // berserker damage and Undying Rage
            for (PathSkill sk : List.of(PathSkill.FURY, PathSkill.BLOODLUST, PathSkill.RECKLESS, PathSkill.RAMPAGE)) SoldierPaths.learn(s, so, sk, bers);
            bers.setHealth(bers.getMaxHealth());
            check(Math.abs(SoldierPaths.attack(s, so, bers, zombie, WeaponProficiency.AXE, false, now) - .12) < 1e-9, "Reckless at full health"); checks++;
            check(SoldierPaths.attack(s, so, bers, zombie, WeaponProficiency.SWORD, false, now) == 0, "Berserker skills need an axe, greatsword or mace"); checks++;
            bers.setHealth(bers.getMaxHealth() / 2);
            check(Math.abs(SoldierPaths.attack(s, so, bers, zombie, WeaponProficiency.AXE, false, now) - .21) < 1e-6, "Fury grows as health drops"); checks++;
            float healed = bers.getHealth();
            SoldierPaths.onKill(so, bers, now);
            check(Math.abs(bers.getHealth() - healed - 3) < .01 && so.rampageUntil == now + SoldierPaths.RAMPAGE_TICKS, "Bloodlust heals, Rampage starts: " + healed + " -> " + bers.getHealth() + " of " + bers.getMaxHealth() + ", rampage " + so.rampageUntil + " vs " + now); checks++;
            bers.setHealth(1);
            check(Math.abs(SoldierPaths.attack(s, so, bers, zombie, WeaponProficiency.AXE, false, now) - SoldierPaths.PATH_CAP) < 1e-6, "Path bonuses capped at 30%"); checks++;
            check(Math.abs(SoldierPaths.defend(s, so, bers, level.damageSources().mobAttack(zombie), now) + SoldierPaths.RECKLESS_PENALTY) < 1e-9, "Reckless takes 5% more"); checks++;
            check(SoldierPaths.learn(s, so, PathSkill.UNDYING_RAGE, bers) == null, "Learn Undying Rage"); checks++;
            bers.setHealth(5);
            var lethal = new LivingDamageEvent(bers, level.damageSources().mobAttack(zombie), 50);
            SoldierPaths.lethal(lethal);
            check(Math.abs(lethal.getAmount() - 4) < 1e-6 && so.rageUntil > now, "Undying Rage leaves 1 HP"); checks++;
            lethal = new LivingDamageEvent(bers, level.damageSources().mobAttack(zombie), 50);
            SoldierPaths.lethal(lethal);
            check(lethal.getAmount() == 50, "Undying Rage cooldown"); checks++;
            check(SoldierPaths.attack(s, so, bers, zombie, WeaponProficiency.AXE, false, RecruitCombat.now(bers)) >= .40, "Rage: +40% damage"); checks++;

            // swordsman flurry and marksman deadshot and mark, in isolated records
            Settlement.Soldier sw = new Settlement.Soldier(); sw.path = SoldierPath.SWORDSMAN; sw.skillXp = 900; sw.type = UnitType.FOOTMAN; sw.entity = UUID.randomUUID(); sw.name = "Sword";
            sw.pathSkills.addAll(List.of(PathSkill.BLADE_DRILL, PathSkill.PARRY, PathSkill.DUELIST, PathSkill.FLURRY));
            double[] hits = new double[3];
            for (int i = 0; i < 3; i++) hits[i] = SoldierPaths.attack(null, sw, bers, zombie, WeaponProficiency.SWORD, false, now);
            check(Math.abs(hits[0] - .08) < 1e-9 && Math.abs(hits[1] - .08) < 1e-9 && Math.abs(hits[2] - .58) < 1e-9, "Flurry every third hit: " + Arrays.toString(hits)); checks++;
            Settlement.Soldier mk = new Settlement.Soldier(); mk.path = SoldierPath.MARKSMAN; mk.skillXp = 900; mk.type = UnitType.BOWMAN; mk.entity = UUID.randomUUID(); mk.name = "Bow";
            mk.pathSkills.add(PathSkill.DEADSHOT);
            int triple = 0;
            for (int i = 0; i < 8; i++) if (SoldierPaths.attack(null, mk, bers, zombie, WeaponProficiency.BOW, true, now) >= 2) triple++;
            check(triple == 2, "Deadshot: every fourth shot triples"); checks++;
            mk.pathSkills.clear(); mk.pathSkills.add(PathSkill.HUNTERS_MARK);
            SoldierPaths.afterHit(s, mk, bers, zombie, WeaponProficiency.BOW, true, 4, now);
            check(Math.abs(SoldierPaths.attack(s, so, bers, zombie, WeaponProficiency.SWORD, false, now) - .25) < 1e-9, "Hunter's Mark: +25% for the whole town"); checks++;
            check(SoldierPaths.attack(s, so, bers, zombie, WeaponProficiency.SWORD, false, now + SoldierPaths.MARK_TICKS) == 0, "Mark fades"); checks++;

            // missing gear shows on the Path page
            RecruitsBridge.setSlot(bers, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            SoldierProgression.applySkills(bers, so);
            CompoundTag warn = new CompoundTag(); so.seen = true; SoldierPaths.describe(so, warn); so.seen = false;
            check(!so.pathReady && warn.getString("pathWarning").contains("axe"), "Missing gear warning: " + warn.getString("pathWarning")); checks++;
            RecruitsBridge.setSlot(bers, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            SoldierProgression.applySkills(bers, so);
            check(so.pathReady, "Warning clears with the right weapon"); checks++;

            // healer: bandage, triage, cleanse, chaplain, saint, mentoring
            LivingEntity medic = spawn(level, UnitType.BOWMAN, s, "Path Healer", spawned);
            Settlement.Soldier hl = soldier(s, medic, UnitType.BOWMAN, WeaponProficiency.BOW);
            hl.skillXp = 900;
            check(SoldierPaths.choose(s, hl, SoldierPath.HEALER, medic, out) == null && out[0] == medic && hl.type == UnitType.BOWMAN, "A bowman becomes a healer in its own body"); checks++;
            for (PathSkill sk : List.of(PathSkill.BANDAGE, PathSkill.TRIAGE, PathSkill.CLEANSE, PathSkill.SURGEON)) { check(SoldierPaths.learn(s, hl, sk, medic) == null, "Learn " + sk); checks++; }
            // the healer treats itself here; allies are found by area, which needs entity-ticking chunks (checked below)
            medic.setHealth(4);
            medic.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 400));
            SoldierPaths.pulse(s, hl, medic, 40 * 20);
            check(Math.abs(medic.getHealth() - 8) < .01, "Bandage with Triage heals a badly wounded recruit 4: " + medic.getHealth()); checks++;
            check(!medic.hasEffect(net.minecraft.world.effect.MobEffects.POISON), "Cleanse cures poison"); checks++;
            SoldierPaths.pulse(s, hl, medic, 41 * 20);
            check(Math.abs(medic.getHealth() - 8) < .01, "Bandage only every 4s"); checks++;
            int legends = (int) s.chronicle.entries.stream().filter(x -> x.kind() == livingrealms.chronicle.Chronicle.Kind.LEGEND).count();
            check(SoldierPaths.learn(s, hl, PathSkill.WAR_CHAPLAIN, medic) == null, "Learn War Chaplain"); checks++;
            check(s.chronicle.entries.stream().filter(x -> x.kind() == livingrealms.chronicle.Chronicle.Kind.LEGEND).count() == legends + 1
                    && s.chronicle.entries.get(s.chronicle.entries.size() - 1).text().contains("the Chaplain"), "A mastery is written in the chronicle"); checks++;
            CompoundTag hv = new CompoundTag(); SoldierPaths.describe(hl, hv);
            check(hv.getString("epithet").equals("the Chaplain") && hv.getString("pathLabel").equals("Healer"), "Epithet in the view"); checks++;
            SoldierPaths.pulse(s, hl, medic, 3 * 20);
            check(Math.abs(medic.getHealth() - 9) < .01, "War Chaplain regenerates: " + medic.getHealth()); checks++;
            hl.pathSkills.remove(PathSkill.WAR_CHAPLAIN); hl.pathSkills.add(PathSkill.SAINT);
            medic.setHealth(3);
            lethal = new LivingDamageEvent(medic, level.damageSources().mobAttack(zombie), 50);
            SoldierPaths.lethal(lethal);
            check(lethal.getAmount() == 0 && Math.abs(medic.getHealth() - 6) < .01, "Saint saves a falling recruit on 6 HP"); checks++;
            lethal = new LivingDamageEvent(medic, level.damageSources().mobAttack(zombie), 50);
            SoldierPaths.lethal(lethal);
            check(lethal.getAmount() == 50, "Saint cooldown"); checks++;
            Settlement.Soldier junior = new Settlement.Soldier(); junior.entity = UUID.randomUUID(); junior.name = "Junior"; junior.type = UnitType.CROSSBOWMAN; junior.seen = true; junior.skillXp = 100;
            Settlement.Soldier other = new Settlement.Soldier(); other.entity = UUID.randomUUID(); other.name = "Rider"; other.type = UnitType.HORSEMAN; other.seen = true; other.skillXp = 100;
            s.soldiers.add(junior); s.soldiers.add(other);
            long day5 = 5 * 24000L;
            check(SoldierPaths.mentor(s, hl, day5) == 1 && junior.skillXp == 100 + SoldierPaths.MENTOR_XP && other.skillXp == 100, "A master mentors juniors who could follow its path"); checks++;
            check(SoldierPaths.mentor(s, hl, day5 + 100) == 0 && junior.skillXp == 100 + SoldierPaths.MENTOR_XP, "Mentoring once a day"); checks++;
            s.soldiers.remove(junior); s.soldiers.remove(other);
            check(Settlement.Soldier.load(hl.save()).mentorDay == 5, "Mentor day saved"); checks++;

            // auras through the real damage hook; they look recruits up, which needs entity-ticking chunks
            if (!level.isPositionEntityTicking(s.center)) {
                skipped = "aura checks skipped: the test site isn't entity-ticking";
                return checks;
            }
            LivingEntity shield = spawn(level, UnitType.SHIELDBEARER, s, "Path Aegis", spawned);
            Settlement.Soldier ae = soldier(s, shield, UnitType.SHIELDBEARER, WeaponProficiency.SWORD);
            ae.path = SoldierPath.SHIELDMAN; ae.skillXp = 900; ae.pathSkills.addAll(List.of(PathSkill.SHIELD_DRILL, PathSkill.BRACE, PathSkill.GUARDIAN, PathSkill.IMMOVABLE, PathSkill.AEGIS));
            so.pathSkills.clear(); so.path = SoldierPath.BERSERKER;
            bers.setHealth(bers.getMaxHealth());
            var hurt = new LivingHurtEvent(bers, level.damageSources().mobAttack(zombie), 10);
            RecruitCombat.hurt(hurt);
            check(Math.abs(hurt.getAmount() - 8) < .01, "Aegis: allies within 6 blocks take 20% less: " + hurt.getAmount()); checks++;
            shield.teleportTo(s.center.getX() + 20, s.center.getY(), s.center.getZ() + 20);
            hurt = new LivingHurtEvent(bers, level.damageSources().mobAttack(zombie), 10);
            RecruitCombat.hurt(hurt);
            check(Math.abs(hurt.getAmount() - 10) < .01, "Aegis has a range"); checks++;
            hl.pathSkills.remove(PathSkill.SAINT); hl.pathSkills.add(PathSkill.WAR_CHAPLAIN);
            medic.teleportTo(bers.getX() + 2, bers.getY(), bers.getZ());
            medic.setHealth(medic.getMaxHealth()); bers.setHealth(4);
            SoldierPaths.pulse(s, hl, medic, 44 * 20);
            check(Math.abs(bers.getHealth() - 8) < .01, "Bandage heals the most wounded ally nearby: " + bers.getHealth()); checks++;
            check(Math.abs(SoldierPaths.attack(s, so, bers, zombie, WeaponProficiency.AXE, false, now + SoldierPaths.MARK_TICKS) - .10) < 1e-9, "War Chaplain: +10% damage nearby"); checks++;
            // the chaplain's +10% is its own term beyond the path budget, as the README promises
            so.pathSkills.add(PathSkill.RECKLESS);
            bers.setHealth(bers.getMaxHealth());
            zombie.getPersistentData().putUUID("LivingRealmsMarkedBy", s.id);
            zombie.getPersistentData().putLong("LivingRealmsMarkedUntil", now + 10);
            check(Math.abs(SoldierPaths.attack(s, so, bers, zombie, WeaponProficiency.AXE, false, now) - (SoldierPaths.PATH_CAP + SoldierPaths.WAR_CHAPLAIN_BONUS)) < 1e-9,
                    "Mark +25% and Reckless +12% fill the 30% path budget; the chaplain still adds 10%: " + SoldierPaths.attack(s, so, bers, zombie, WeaponProficiency.AXE, false, now)); checks++;
            medic.teleportTo(bers.getX() + 20, bers.getY(), bers.getZ());
            check(Math.abs(SoldierPaths.attack(s, so, bers, zombie, WeaponProficiency.AXE, false, now) - SoldierPaths.PATH_CAP) < 1e-9, "No chaplain within 8 blocks: just the capped path bonus"); checks++;
            so.pathSkills.remove(PathSkill.RECKLESS);
        } finally {
            for (Entity e : spawned) if (e != null) e.discard();
            manager.remove(s);
        }
        return checks;
    }

    private static LivingEntity spawn(ServerLevel level, UnitType unit, Settlement s, String name, List<Entity> spawned) {
        LivingEntity e = (LivingEntity) RecruitsBridge.spawn(level, unit, s.center, s.owner, name);
        check(e != null, "Spawn " + unit);
        spawned.add(e);
        return e;
    }

    private static Settlement.Soldier soldier(Settlement s, Entity e, UnitType type, WeaponProficiency weapon) {
        var so = new Settlement.Soldier(); so.entity = e.getUUID(); so.type = type; so.name = e.getName().getString(); so.preferredWeapon = weapon;
        s.soldiers.add(so);
        return so;
    }

    private static void check(boolean ok, String message) { if (!ok) throw new IllegalStateException("Paths: " + message); }
    private PathsSelfTest() {}
}
