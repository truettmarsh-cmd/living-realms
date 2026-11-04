package livingrealms.military;

import com.mojang.authlib.GameProfile;
import livingrealms.LivingRealms;
import livingrealms.chronicle.Chronicle;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Tier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * -PheroesTest: Heroes on real Villager Recruits soldiers. Ascension requirements, price and cap, the perks, every
 * ultimate through the real damage events with its cooldown and its battle report line, Valor, a hero's death (server
 * message, chronicle LEGEND, memorial, heirloom), a promotion move and the save round trip.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class HeroesSelfTest {
    private static int checks;

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.heroesTest")) return;
        event.getServer().execute(() -> {
            String report;
            try {
                checks = 0;
                run(event.getServer().overworld());
                report = "PASS: " + checks + " hero assertions\n";
                LivingRealms.LOG.info("HEROES SELF TEST {}", report);
            } catch (Throwable failure) {
                report = "FAIL after " + checks + " assertions: " + failure + "\n";
                LivingRealms.LOG.error("HEROES SELF TEST FAILED", failure);
            }
            try { Files.writeString(Path.of("heroes-test-result.txt"), report); } catch (Exception ex) { LivingRealms.LOG.error("Cannot write test result", ex); }
            event.getServer().halt(false);
        });
    }

    /** The damage a LivingHurtEvent ends with (after every Living Realms modifier), for one victim. */
    private static final class Capture implements Consumer<LivingHurtEvent> {
        LivingEntity who; float amount = -1;
        public void accept(LivingHurtEvent e) { if (e.getEntity() == who && !e.isCanceled()) amount = e.getAmount(); }
        float hit(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source, float damage) {
            who = victim; amount = -1;
            victim.invulnerableTime = 0;
            victim.hurt(source, damage);
            return amount;
        }
    }

    static void run(ServerLevel level) throws Exception {
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "HeroOwner");
        FakePlayer player = FakePlayerFactory.get(level, profile);
        server.getProfileCache().add(profile);
        UUID owner = profile.getId();
        SettlementManager m = SettlementManager.get(server);
        HeroData data = HeroData.get(server);
        Heroes.reset();
        flushReports(server);
        // a flat, clear stone floor high above spawn
        BlockPos base = level.getSharedSpawnPos().atY(Math.min(level.getMaxBuildHeight() - 20, 200));
        for (int x = -24; x <= 40; x += 16) for (int z = -24; z <= 24; z += 16) level.getChunkAt(base.offset(x, 0, z));
        for (int x = -24; x <= 40; x++) for (int z = -24; z <= 24; z++) {
            level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
            for (int y = 0; y < 4; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        List<Entity> spawned = new ArrayList<>();
        List<Settlement> towns = new ArrayList<>();
        Capture capture = new Capture();
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingHurtEvent.class, capture);
        try {
            Settlement town = town(m, towns, owner, "Herofold", base, Tier.CAMP);
            long now = server.overworld().getGameTime();

            // ---------------------------------------------------------------- definitions
            check(Heroes.valorNeeded(1) == 100 && Heroes.valorNeeded(2) == 300 && Heroes.valorNeeded(10) == 5500, "Valor thresholds 100/300/…/5500");
            check(Heroes.valorLevel(99) == 0 && Heroes.valorLevel(100) == 1 && Heroes.valorLevel(5499) == 9 && Heroes.valorLevel(99999) == 10, "Valor levels 0–10");
            check(Heroes.stars(3).equals("★★★☆☆☆☆☆☆☆"), "Valor stars");
            check(Heroes.capBonus(owner) == 0, "capBonus hook is 0 for now");
            for (SoldierPath p : SoldierPath.values()) check(Ultimate.of(p) != null && Ultimate.of(p).path == p, "Every path has an ultimate: " + p);
            check(Ultimate.COOLDOWN == 2400, "Two-minute cooldown");

            // ---------------------------------------------------------------- ascension requirements and price
            LivingEntity bjorn = spawn(level, UnitType.FOOTMAN, base, owner, "Bjorn", spawned);
            Settlement.Soldier sb = soldier(town, bjorn, SoldierPath.SWORDSMAN);
            for (PathSkill sk : PathSkill.of(SoldierPath.SWORDSMAN)) if (!sk.mastery()) sb.pathSkills.add(sk);
            sb.skillXp = SoldierRanks.MAX_XP - 50;
            check(Heroes.blocker(server, town, sb, now).contains("legendary mastery"), "Needs a mastery: " + Heroes.blocker(server, town, sb, now));
            sb.pathSkills.add(PathSkill.BLADEMASTER);
            check(Heroes.blocker(server, town, sb, now).contains("every skill point"), "Needs every skill point: " + Heroes.blocker(server, town, sb, now));
            sb.skillXp = SoldierRanks.MAX_XP;
            check(Heroes.blocker(server, town, sb, now).contains("proven in battle"), "Needs merit or battles: " + Heroes.blocker(server, town, sb, now));
            for (int i = 0; i < Heroes.BATTLES_NEEDED - 1; i++) data.survived(bjorn.getUUID());
            check(Heroes.blocker(server, town, sb, now) != null, "Nine battles are not enough");
            var report = new livingrealms.battle.BattleReport();
            var f = new livingrealms.battle.BattleReport.Fighter(); f.id = bjorn.getUUID(); report.fighters.add(f);
            var fallen = new livingrealms.battle.BattleReport.Fighter(); fallen.id = UUID.randomUUID(); fallen.fell = true; report.fighters.add(fallen);
            Heroes.survived(server, report);
            check(data.battles(bjorn.getUUID()) == Heroes.BATTLES_NEEDED && data.battles(fallen.id) == 0, "A written report counts a survived battle, not a fall");
            check(Heroes.blocker(server, town, sb, now) == null, "Ten battles survived qualify");
            data.forget(bjorn.getUUID());
            sb.merit.merit = Heroes.MERIT_NEEDED; sb.merit.updated = now;
            check(Heroes.blocker(server, town, sb, now) == null, "Or 60 merit");
            CompoundTag view = livingrealms.net.View.soldier(town, sb).getCompound("hero");
            check(!view.getBoolean("hero") && view.getList("needs", 10).size() == 3 && view.getBoolean("can") && view.getString("ultimate").equals("Blade Storm"), "Path page: requirements and the ultimate to come");
            check(Heroes.ascend(server, null, town, sb, null, now) != null, "Must be loaded");
            check(Heroes.ascend(server, null, town, sb, bjorn, now).contains("emeralds"), "Emeralds missing");
            town.stock.force(Items.EMERALD, 300);
            check(Heroes.ascend(server, null, town, sb, bjorn, now).contains("rare item"), "Rare item missing");
            check(data.hero(bjorn.getUUID()) == null && town.stock.count(Items.EMERALD) == 300, "Nothing taken on a failed ascension");
            town.stock.force(Items.NETHER_STAR, 1);
            float before = bjorn.getMaxHealth();
            check(Heroes.ascend(server, null, town, sb, bjorn, now) == null, "Bjorn ascends");
            check(town.stock.count(Items.EMERALD) == 300 - Heroes.ASCEND_EMERALDS && town.stock.count(Items.NETHER_STAR) == 0, "Price paid: 128 emeralds and the nether star");
            HeroData.Hero hb = data.hero(bjorn.getUUID());
            check(hb != null && hb.owner.equals(owner) && hb.path == SoldierPath.SWORDSMAN && Heroes.fullName(hb).equals("Bjorn the Blademaster"), "Hero record");
            check(Heroes.lastAscension.contains("Bjorn the Blademaster of Herofold has ascended"), "Server-wide ascension message: " + Heroes.lastAscension);
            check(last(town).kind() == Chronicle.Kind.LEGEND && last(town).text().contains("raised up as a Hero"), "Chronicle LEGEND: " + last(town));
            check(Heroes.ascend(server, null, town, sb, bjorn, now).contains("already a Hero"), "Only once");

            // ---------------------------------------------------------------- perks
            check(Math.abs(bjorn.getMaxHealth() - before * 1.2f) < .01f && bjorn.getHealth() == bjorn.getMaxHealth(), "+20% max health, healed: " + before + " -> " + bjorn.getMaxHealth());
            check(bjorn.getCustomName() != null && TextColor.fromLegacyFormat(ChatFormatting.GOLD).equals(bjorn.getCustomName().getStyle().getColor())
                    && bjorn.isCustomNameVisible() && bjorn.getName().getString().equals("Bjorn"), "Gold nameplate, same name");
            bjorn.setCustomName(net.minecraft.network.chat.Component.literal("Bjorn"));
            Heroes.pulse(server, bjorn, hb, now);
            check(TextColor.fromLegacyFormat(ChatFormatting.GOLD).equals(bjorn.getCustomName().getStyle().getColor()), "A rename gets the gold back");
            bjorn.setDeltaMovement(Vec3.ZERO);
            bjorn.knockback(2, 1, 0);
            check(bjorn.getDeltaMovement().lengthSqr() < 1e-6, "No knockback");
            LivingEntity ally = spawn(level, UnitType.FOOTMAN, base.offset(3, 0, 0), owner, "Ally", spawned);
            ally.setDeltaMovement(Vec3.ZERO);
            ally.knockback(2, 1, 0);
            check(ally.getDeltaMovement().lengthSqr() > 1e-3, "Other recruits are knocked back");
            RecruitsBridge.addMorale(bjorn, -100);
            Heroes.pulse(server, bjorn, hb, now);
            check(RecruitsBridge.morale(bjorn) >= Heroes.HERO_MORALE, "No fear: morale back to 60: " + RecruitsBridge.morale(bjorn));
            RecruitsBridge.addMorale(ally, -100); RecruitsBridge.addMorale(ally, 20);
            check(Heroes.aura(bjorn, hb, now) >= 1 && Math.abs(RecruitsBridge.morale(ally) - 25) < .01, "Aura: +5 morale to a shaken recruit nearby: " + RecruitsBridge.morale(ally));
            LivingEntity far = spawn(level, UnitType.FOOTMAN, base.offset(30, 0, 0), owner, "Faraway", spawned);
            LivingEntity stranger = spawn(level, UnitType.FOOTMAN, base.offset(2, 0, 2), UUID.randomUUID(), "Stranger", spawned);
            check(Heroes.inspired(ally) && Heroes.inspired(bjorn) && !Heroes.inspired(far) && !Heroes.inspired(stranger), "Inspired within 12 blocks, own side only");
            Mob foe = zombie(level, base.offset(6, 0, 6), spawned);
            ally.setHealth(ally.getMaxHealth() * .25f); far.setHealth(far.getMaxHealth() * .25f);
            Set<LivingEntity> back = BattleAI.fallingBack(new BattleAI.Side(List.of(ally, far), List.of(foe), base, base, true, null));
            check(!back.contains(ally) && back.contains(far), "Near a hero, a recruit at 25% health holds the line; elsewhere it falls back");
            RecruitsBridge.addMorale(ally, -100);
            ally.setHealth(ally.getMaxHealth() * .4f); far.setHealth(far.getMaxHealth());
            check(!BattleAI.fallingBack(new BattleAI.Side(List.of(ally, far), List.of(foe), base, base, true, null)).contains(ally), "Even shaken (45% rule) it holds near a hero");
            ally.setHealth(ally.getMaxHealth()); far.setHealth(far.getMaxHealth());

            // ---------------------------------------------------------------- cap
            LivingEntity dain = spawn(level, UnitType.FOOTMAN, base.offset(-3, 0, 0), owner, "Dain", spawned);
            Settlement.Soldier sd = soldier(town, dain, SoldierPath.SHIELDMAN);
            for (PathSkill sk : PathSkill.of(SoldierPath.SHIELDMAN)) if (sk != PathSkill.COLOSSUS) sd.pathSkills.add(sk);
            sd.skillXp = SoldierRanks.MAX_XP; sd.merit.merit = 80; sd.merit.updated = now;
            check(Heroes.cap(server, owner) == 1 && Heroes.count(server, owner) == 1, "No city: cap 1, and Bjorn holds it");
            check(Heroes.blocker(server, town, sd, now).contains("already have 1 Hero"), "Cap reached: " + Heroes.blocker(server, town, sd, now));
            town.tier = Tier.CITY;
            check(Heroes.cap(server, owner) == 1, "One city: cap 1");
            town(m, towns, owner, "Second", base.offset(800, 0, 0), Tier.CAPITAL);
            check(Heroes.cities(server, owner) == 2 && Heroes.cap(server, owner) == 2, "Two cities: cap 2");
            for (int i = 0; i < 5; i++) town(m, towns, owner, "City " + i, base.offset(1600 + i * 400, 0, 0), Tier.CITY);
            check(Heroes.cap(server, owner) == Heroes.MAX_CAP, "At most 5: " + Heroes.cap(server, owner));
            for (Settlement x : new ArrayList<>(towns)) if (x != town && !x.name.equals("Second")) { m.remove(x); towns.remove(x); }
            player.getInventory().add(new ItemStack(Items.NETHER_STAR));
            town.stock.force(Items.NETHER_STAR, 1);
            check(Heroes.ascend(server, player, town, sd, dain, now) == null, "Dain ascends under the cap of 2");
            check(player.getInventory().countItem(Items.NETHER_STAR) == 0 && town.stock.count(Items.NETHER_STAR) == 1, "The rare item comes from the payer's inventory first");
            check(Heroes.count(server, owner) == 2, "Two heroes");
            // a promotion through Recruit Command replaces the entity: the hero record and battles follow
            data.survived(dain.getUUID());
            LivingEntity dain2 = spawn(level, UnitType.SHIELDBEARER, base.offset(-4, 0, 0), owner, "Dain", spawned);
            Heroes.moved(server, dain.getUUID(), dain2.getUUID());
            sd.entity = dain2.getUUID();
            check(data.hero(dain.getUUID()) == null && data.hero(dain2.getUUID()) != null && data.battles(dain2.getUUID()) == 1
                    && Heroes.fullName(data.hero(dain2.getUUID())).equals("Dain the Unbroken") && Heroes.inspired(dain2), "Promotion moves the hero");
            dain.discard();
            dain2.discard();
            check(data.hero(dain2.getUUID()).removed && Heroes.count(server, owner) == 1, "A discarded hero holds no place under the cap");
            town.soldiers.remove(sd);
            data.forget(dain2.getUUID());

            // ---------------------------------------------------------------- valor
            int xp = sb.skillXp;
            SoldierRanks.grant(town, sb, bjorn, 100, false, now);
            check(sb.skillXp == xp && hb.valorXp == 100 && hb.valor() == 1, "XP past the top rank becomes Valor: " + hb.valorXp);
            check(Math.abs(bjorn.getMaxHealth() - before * 1.21f) < .01f, "Valor 1: +1% max health: " + before + " -> " + bjorn.getMaxHealth() + ", valor " + hb.valor());
            check(Math.abs(Heroes.attack(data, bjorn, now) - .01) < 1e-9, "Valor 1: +1% damage");
            sb.trickleDay = now / SoldierRanks.DAY; sb.trickleXp = 0;
            SoldierRanks.grant(town, sb, bjorn, 200, true, now);
            check(hb.valorXp == 100 + SoldierRanks.DAILY_TRICKLE_CAP, "Trickle Valor obeys the daily cap: " + hb.valorXp);
            hb.valorXp = Heroes.valorNeeded(Heroes.VALOR_MAX) - 10;
            SoldierRanks.grant(town, sb, bjorn, 100, false, now);
            check(hb.valorXp == Heroes.valorNeeded(Heroes.VALOR_MAX) && hb.valor() == 10, "Valor stops at 10");
            check(Math.abs(Heroes.attack(data, bjorn, now) - .10) < 1e-9, "Valor 10: +10%");
            Mob dummy = zombie(level, base.offset(1, 0, 0), spawned);
            float dealt = capture.hit(dummy, level.damageSources().mobAttack(bjorn), 10);
            check(Math.abs(dealt - 11) < .01, "Through the real hurt event, Valor 10 adds 10%: " + dealt);
            CompoundTag heroView = livingrealms.net.View.soldier(town, sb).getCompound("hero");
            check(heroView.getBoolean("hero") && heroView.getInt("valor") == 10 && heroView.getString("ultimate").equals("Blade Storm") && heroView.getInt("cooldown") == 0, "Path page: Valor and the ultimate");
            dummy.discard();

            // ---------------------------------------------------------------- ultimates
            long t = now;
            // Blade Storm
            Mob z1 = zombie(level, base.offset(2, 0, 1), spawned), z2 = zombie(level, base.offset(-1, 0, 2), spawned);
            ((Mob) bjorn).setTarget(z1);
            check(Heroes.act(server, (Mob) bjorn, hb, t) == Ultimate.BLADE_STORM && Heroes.running(Ultimate.BLADE_STORM, bjorn.getUUID()), "Blade Storm fires with two foes close");
            for (long k = 0; k <= Heroes.BLADE_STORM_TICKS + 1; k++) Heroes.run(server, t + k);
            check(hurt(z1) && hurt(z2) && !Heroes.running(Ultimate.BLADE_STORM, bjorn.getUUID()), "Blade Storm cut both for 4s, then ended: " + z1.getHealth() + ", " + z2.getHealth());
            cooldown(server, (Mob) bjorn, hb, t, Ultimate.BLADE_STORM);
            clear(z1, z2);

            // Bulwark
            LivingEntity shield = hero(level, data, UnitType.SHIELDBEARER, base.offset(0, 0, 10), owner, "Hild", SoldierPath.SHIELDMAN, spawned);
            HeroData.Hero hs = data.hero(shield.getUUID());
            List<Mob> pack = List.of(zombie(level, base.offset(4, 0, 10), spawned), zombie(level, base.offset(-4, 0, 12), spawned), zombie(level, base.offset(0, 0, 16), spawned));
            ((Mob) shield).setTarget(pack.get(0));
            LivingEntity inside = spawn(level, UnitType.FOOTMAN, base.offset(2, 0, 11), owner, "Inside", spawned);
            LivingEntity outside = spawn(level, UnitType.FOOTMAN, base.offset(14, 0, 10), owner, "Outside", spawned);
            check(Heroes.act(server, (Mob) shield, hs, t) == Ultimate.BULWARK, "Bulwark fires against three foes");
            float in = capture.hit(inside, level.damageSources().mobAttack(pack.get(0)), 8), out = capture.hit(outside, level.damageSources().mobAttack(pack.get(0)), 8);
            check(Math.abs(in - out * .5f) < .01 && out > 0, "Inside the dome: 50% less damage (" + in + " vs " + out + ")");
            float foeHit = capture.hit(pack.get(1), level.damageSources().mobAttack(shield), 8);
            check(Math.abs(foeHit - 8) < .01, "Enemies in the dome are not protected: " + foeHit);
            Arrow incoming = new Arrow(level, pack.get(2));
            incoming.setPos(shield.getX() + 2, shield.getY() + 1, shield.getZ());
            level.addFreshEntity(incoming);
            spawned.add(incoming);
            Arrow friendly = new Arrow(level, inside);
            friendly.setPos(shield.getX() - 2, shield.getY() + 1, shield.getZ());
            level.addFreshEntity(friendly);
            spawned.add(friendly);
            int blocked = Heroes.blocked;
            Heroes.run(server, t + 1);
            check(incoming.isRemoved() && !friendly.isRemoved() && Heroes.blocked == blocked + 1, "Enemy arrows entering the dome are stopped, ours are not");
            check(Heroes.defend(inside, t + Heroes.BULWARK_TICKS + 1) == 0 || !Heroes.running(Ultimate.BULWARK, shield.getUUID()), "Dome lasts 8s");
            for (long k = 2; k <= Heroes.BULWARK_TICKS + 2; k++) Heroes.run(server, t + k);
            check(!Heroes.running(Ultimate.BULWARK, shield.getUUID()) && Heroes.defend(inside, t + Heroes.BULWARK_TICKS + 2) == 0, "Then it falls");
            cooldown(server, (Mob) shield, hs, t, Ultimate.BULWARK);
            clear(pack.toArray(new Mob[0]));
            clear(incoming, friendly, inside, outside);

            // Earthshaker
            LivingEntity berserk = hero(level, data, UnitType.FOOTMAN, base.offset(0, 0, -10), owner, "Ulfa", SoldierPath.BERSERKER, spawned);
            HeroData.Hero hr = data.hero(berserk.getUUID());
            List<Mob> ring = List.of(zombie(level, base.offset(3, 0, -10), spawned), zombie(level, base.offset(-3, 0, -10), spawned), zombie(level, base.offset(0, 0, -7), spawned));
            Mob distant = zombie(level, base.offset(0, 0, -20), spawned);
            ((Mob) berserk).setTarget(ring.get(0));
            check(Heroes.act(server, (Mob) berserk, hr, t) == Ultimate.EARTHSHAKER, "Earthshaker fires with three foes around");
            for (Mob z : ring) check(hurt(z) && z.hasEffect(MobEffects.MOVEMENT_SLOWDOWN) && z.getDeltaMovement().y > 0, "Slammed, slowed and thrown: " + z.getHealth());
            check(!hurt(distant), "Beyond 6 blocks: untouched");
            cooldown(server, (Mob) berserk, hr, t, Ultimate.EARTHSHAKER);
            for (Mob z : ring) { z.setHealth(z.getMaxHealth()); z.removeAllEffects(); }
            check(Heroes.act(server, (Mob) berserk, hr, t + Ultimate.COOLDOWN) == Ultimate.EARTHSHAKER && hurt(ring.get(1)), "Two minutes later it fires again");
            clear(ring.toArray(new Mob[0])); clear(distant);

            // Impale
            LivingEntity spear = hero(level, data, UnitType.FOOTMAN, base.offset(-20, 0, 0), owner, "Tor", SoldierPath.SPEARMAN, spawned);
            HeroData.Hero hp = data.hero(spear.getUUID());
            Mob inLine = zombie(level, base.offset(-16, 0, 0), spawned);
            Mob ravager = EntityType.RAVAGER.create(level);
            ravager.moveTo(base.getX() - 11 + .5, base.getY(), base.getZ() + .5, 0, 0);
            ravager.finalizeSpawn(level, level.getCurrentDifficultyAt(base), MobSpawnType.MOB_SUMMONED, null, null);
            level.addFreshEntity(ravager);
            spawned.add(ravager);
            Mob aside = zombie(level, base.offset(-16, 0, 6), spawned);
            spear.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, ravager.position());
            ((Mob) spear).setTarget(ravager);
            check(Heroes.act(server, (Mob) spear, hp, t) == Ultimate.IMPALE, "Impale fires at a target 9 blocks off: " + spear.distanceTo(ravager));
            check(hurt(inLine) && hurt(ravager) && !hurt(aside), "Impale runs through the line, not beside it");
            check(ravager.getUUID().equals(Heroes.lastPinned) && ravager.getEffect(MobEffects.MOVEMENT_SLOWDOWN) != null
                    && ravager.getEffect(MobEffects.MOVEMENT_SLOWDOWN).getAmplifier() >= 6, "The first large foe is pinned");
            check(spear.getDeltaMovement().horizontalDistanceSqr() > 1, "The hero charges");
            cooldown(server, (Mob) spear, hp, t, Ultimate.IMPALE);
            clear(inLine, ravager, aside);

            // Rain of Arrows
            LivingEntity archer = hero(level, data, UnitType.BOWMAN, base.offset(20, 0, -15), owner, "Wren", SoldierPath.MARKSMAN, spawned);
            HeroData.Hero ha = data.hero(archer.getUUID());
            Mob mark = zombie(level, base.offset(20, 0, 3), spawned);
            List<Mob> around = List.of(mark, zombie(level, base.offset(22, 0, 3), spawned), zombie(level, base.offset(20, 0, 5), spawned));
            LivingEntity friend = spawn(level, UnitType.FOOTMAN, base.offset(18, 0, 3), owner, "Friend", spawned);
            ((Mob) archer).setTarget(mark);
            int shot = Heroes.shot;
            check(Heroes.act(server, (Mob) archer, ha, t) == Ultimate.RAIN_OF_ARROWS, "Rain of Arrows fires at a group 18 blocks off");
            for (long k = 0; k <= Heroes.RAIN_TICKS + 1; k++) Heroes.run(server, t + k);
            check(Heroes.shot - shot >= 60 && !Heroes.running(Ultimate.RAIN_OF_ARROWS, archer.getUUID()), "3 arrows every quarter second for 5s: " + (Heroes.shot - shot));
            List<Arrow> rain = level.getEntitiesOfClass(Arrow.class, new AABB(mark.blockPosition()).inflate(8, 20, 8), a -> a.getPersistentData().getBoolean(Heroes.ULTIMATE_SHOT));
            check(!rain.isEmpty() && rain.stream().allMatch(a -> a.getOwner() == archer && a.position().distanceToSqr(mark.position().add(0, 12, 0)) < 40), "Volleys fall from above the target area");
            float rained = capture.hit(mark, level.damageSources().arrow(rain.get(0), archer), Heroes.RAIN_DAMAGE);
            check(Math.abs(rained - Heroes.RAIN_DAMAGE) < .01 && hurt(mark), "A rain arrow's hit lands on a foe through the real hurt event: " + rained);
            float before2 = friend.getHealth();
            check(!friend.hurt(level.damageSources().arrow(rain.get(1), archer), 5) && friend.getHealth() == before2, "The rain never hurts our own");
            for (Arrow a : rain) a.discard();
            cooldown(server, (Mob) archer, ha, t, Ultimate.RAIN_OF_ARROWS);
            clear(around.toArray(new Mob[0])); clear(friend);

            // Royal Charge
            LivingEntity knight = hero(level, data, UnitType.HORSEMAN, base.offset(-15, 0, -15), owner, "Edric", SoldierPath.KNIGHT, spawned);
            LivingEntity rider = spawn(level, UnitType.HORSEMAN, base.offset(-12, 0, -15), owner, "Squire", spawned);
            LivingEntity walker = spawn(level, UnitType.FOOTMAN, base.offset(-13, 0, -13), owner, "Walker", spawned);
            HeroData.Hero hk = data.hero(knight.getUUID());
            Mob charge = zombie(level, base.offset(-5, 0, -15), spawned);
            ((Mob) knight).setTarget(charge);
            check(knight.getVehicle() instanceof LivingEntity && rider.getVehicle() instanceof LivingEntity, "Both knights are mounted");
            check(Heroes.act(server, (Mob) knight, hk, t) == Ultimate.ROYAL_CHARGE, "Royal Charge fires at a foe 10 blocks off");
            var speed = ((LivingEntity) rider.getVehicle()).getEffect(MobEffects.MOVEMENT_SPEED);
            check(speed != null && speed.getAmplifier() == 2 && ((LivingEntity) knight.getVehicle()).hasEffect(MobEffects.MOVEMENT_SPEED), "Horses gain Speed III");
            long realNow = server.overworld().getGameTime();
            check(Math.abs(Heroes.attack(data, rider, t) - Heroes.CHARGE_BONUS) < 1e-9 && Heroes.attack(data, walker, t) == 0, "Mounted riders charge at +20%, those on foot don't");
            float charged = capture.hit(charge, level.damageSources().mobAttack(rider), 10);
            check(t != realNow || Math.abs(charged - 12) < .01, "Through the real hurt event: +20%: " + charged);
            hk.valorXp = Heroes.valorNeeded(10);
            check(Heroes.attack(data, knight, t) <= Heroes.HERO_CAP + 1e-9, "Valor and the charge share the +30% hero budget");
            Heroes.run(server, t + Heroes.CHARGE_TICKS + 1);
            check(Heroes.attack(data, rider, t + Heroes.CHARGE_TICKS + 1) == 0, "The charge ends after 6s");
            cooldown(server, (Mob) knight, hk, t, Ultimate.ROYAL_CHARGE);
            clear(charge);

            // Miracle
            LivingEntity healer = hero(level, data, UnitType.FOOTMAN, base.offset(10, 0, 15), owner, "Brigid", SoldierPath.HEALER, spawned);
            HeroData.Hero hh = data.hero(healer.getUUID());
            LivingEntity patient = spawn(level, UnitType.FOOTMAN, base.offset(13, 0, 15), owner, "Patient", spawned);
            Mob raider = zombie(level, base.offset(10, 0, 19), spawned);
            ((Mob) healer).setTarget(raider);
            check(Heroes.act(server, (Mob) healer, hh, t) == null, "Miracle waits while nobody is badly hurt");
            patient.setHealth(3);
            patient.addEffect(new MobEffectInstance(MobEffects.POISON, 400, 1));
            patient.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 400, 0));
            CombatClock.mark(healer.getUUID(), t);
            check(Heroes.act(server, (Mob) healer, hh, t) == Ultimate.MIRACLE, "Miracle fires when a recruit nearby is near death");
            check(patient.getHealth() == patient.getMaxHealth() && !patient.hasEffect(MobEffects.POISON) && patient.hasEffect(MobEffects.DAMAGE_RESISTANCE), "Full heal and cleanse (good effects stay)");
            check(hh.miracleSpent, "Spent for this battle");
            patient.setHealth(3);
            CombatClock.mark(healer.getUUID(), t + Ultimate.COOLDOWN);
            Heroes.pulse(server, healer, hh, t + Ultimate.COOLDOWN);
            check(Heroes.act(server, (Mob) healer, hh, t + Ultimate.COOLDOWN) == null, "Once a battle, even after the cooldown");
            Heroes.pulse(server, healer, hh, t + Ultimate.COOLDOWN + Heroes.BATTLE_QUIET + 1);
            check(!hh.miracleSpent && Heroes.act(server, (Mob) healer, hh, t + Ultimate.COOLDOWN + Heroes.BATTLE_QUIET + 1) == Ultimate.MIRACLE, "A new battle, a new Miracle");
            clear(raider);

            // every ultimate is in the battle report
            List<?> written = flushReports(server);
            Set<String> lines = new HashSet<>();
            for (var r : livingrealms.battle.BattleData.get(server).of(owner)) for (var fi : r.fighters) lines.addAll(fi.advances);
            for (Ultimate u : Ultimate.values()) check(lines.contains("Ultimate: " + u.label), "Battle report mentions " + u.label + ": " + lines + " (" + written.size() + " written)");
            check(data.battles(healer.getUUID()) >= 1, "Living heroes survived the battle");

            // the tick handler drives the same thing
            check(Heroes.class.getMethod("tick", net.minecraftforge.event.TickEvent.ServerTickEvent.class).isAnnotationPresent(SubscribeEvent.class)
                    && Heroes.class.getMethod("knockback", net.minecraftforge.event.entity.living.LivingKnockBackEvent.class).isAnnotationPresent(SubscribeEvent.class), "Handlers subscribed");

            // ---------------------------------------------------------------- save and load
            hb.miracleSpent = true; hb.ultimateReady = t + 1234;
            HeroData copy = HeroData.load(data.save(new CompoundTag()));
            HeroData.Hero hc = copy.hero(bjorn.getUUID());
            check(hc != null && hc.owner.equals(owner) && hc.name.equals("Bjorn") && hc.epithet.equals("the Blademaster") && hc.path == SoldierPath.SWORDSMAN
                    && hc.valorXp == hb.valorXp && hc.ultimateReady == t + 1234 && hc.miracleSpent && hc.ascended == hb.ascended, "Hero survives the save");
            check(copy.battles(healer.getUUID()) == data.battles(healer.getUUID()), "Battle counts survive the save");
            hb.miracleSpent = false;

            // ---------------------------------------------------------------- death
            bjorn.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            ItemStack sword = bjorn.getMainHandItem();
            Mob killer = zombie(level, base.offset(1, 0, 1), spawned);
            int entries = town.chronicle.entries.size(), memorial = town.people.memorial.size(), heroesBefore = Heroes.count(server, owner);
            bjorn.invulnerableTime = 0;
            bjorn.hurt(level.damageSources().mobAttack(killer), 10000);
            check(!bjorn.isAlive(), "Bjorn falls");
            check(Heroes.lastFall.equals("✝ The hero Bjorn the Blademaster of Herofold has fallen, slain by a Zombie."), "Server-wide message: " + Heroes.lastFall);
            check(town.chronicle.entries.stream().skip(entries).anyMatch(e -> e.kind() == Chronicle.Kind.LEGEND && e.text().startsWith("The hero Bjorn the Blademaster fell to a Zombie")),
                    "Chronicle LEGEND for the fall: " + town.chronicle.entries.subList(entries, town.chronicle.entries.size()));
            check(town.chronicle.entries.stream().skip(entries).noneMatch(e -> e.kind() == Chronicle.Kind.FALLEN) && Chronicle.fallChronicled(bjorn.getUUID()), "No second FALLEN line; the report leaves it out");
            CompoundTag stone = town.people.memorial.peekFirst();
            check(town.people.memorial.size() == Math.min(memorial + 1, livingrealms.citizen.People.MEMORIAL) && stone.getBoolean("hero")
                    && stone.getString("n").equals("Bjorn the Blademaster") && stone.getString("j").equals("Hero · Swordsman") && stone.getInt("a") == 10, "Memorial: " + stone);
            check(data.hero(bjorn.getUUID()) == null && Heroes.count(server, owner) == heroesBefore - 1, "The fallen hero frees its place");
            check(Heroes.heirloom(sword) && sword.getHoverName().getString().equals("Bjorn the Blademaster's Iron Sword"), "Heirloom: " + sword.getHoverName().getString());

            // ---------------------------------------------------------------- heirloom
            LivingEntity cade = spawn(level, UnitType.FOOTMAN, base.offset(-6, 0, 6), owner, "Cade", spawned);
            Settlement.Soldier sc = soldier(town, cade, null);
            cade.setItemSlot(EquipmentSlot.MAINHAND, sword.copy());
            cade.tick();
            check(sc.skillXp == Heroes.HEIRLOOM_XP && cade.getMainHandItem().getTagElement(Heroes.HEIRLOOM).getBoolean("claimed"), "Arming a recruit with it grants 150 skill XP once (real equipment event): " + sc.skillXp);
            check(Heroes.lastClaim.startsWith("Cade took up Bjorn the Blademaster's Iron Sword"), "Claim told: " + Heroes.lastClaim);
            check(Heroes.claim(server, cade, cade.getMainHandItem()) == 0 && sc.skillXp == Heroes.HEIRLOOM_XP, "Only once");
            clear(killer);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(capture);
            for (Entity x : spawned) if (x != null) {
                if (x.getVehicle() != null) x.getVehicle().discard();
                data.forget(x.getUUID());
                x.discard();
            }
            for (Settlement s : towns) m.remove(s);
            livingrealms.battle.BattleData.get(server).clear(owner);
            Heroes.reset();
        }
    }

    /** Fired, then on cooldown: not again a tick later, nor a tick short of two minutes; ready at two minutes. */
    static void cooldown(MinecraftServer server, Mob hero, HeroData.Hero h, long t, Ultimate u) {
        check(h.ultimateReady == t + Ultimate.COOLDOWN, u.label + ": two-minute cooldown");
        check(Heroes.act(server, hero, h, t + 20) == null, u.label + ": not again a second later");
        check(!Heroes.ready(h, u, t + Ultimate.COOLDOWN - 1) && (u == Ultimate.MIRACLE || Heroes.ready(h, u, t + Ultimate.COOLDOWN)), u.label + ": ready after two minutes");
    }

    /** The real battle reports in progress are finished now (BattleReports.update is package-private). */
    static List<?> flushReports(MinecraftServer server) throws Exception {
        var m = livingrealms.battle.BattleReports.class.getDeclaredMethod("update", MinecraftServer.class, long.class);
        m.setAccessible(true);
        return (List<?>) m.invoke(null, server, Long.MAX_VALUE);
    }

    static boolean hurt(LivingEntity e) { return !e.isAlive() || e.getHealth() < e.getMaxHealth(); }

    static void clear(Entity... es) { for (Entity e : es) if (e != null) e.discard(); }

    static Chronicle.Entry last(Settlement s) { return s.chronicle.entries.get(s.chronicle.entries.size() - 1); }

    static Settlement town(SettlementManager m, List<Settlement> towns, UUID owner, String name, BlockPos at, Tier tier) {
        Settlement s = new Settlement();
        s.name = name; s.owner = owner; s.dim = net.minecraft.world.level.Level.OVERWORLD; s.center = at; s.tier = tier;
        m.add(s);
        towns.add(s);
        return s;
    }

    static Settlement.Soldier soldier(Settlement s, LivingEntity e, SoldierPath path) {
        Settlement.Soldier so = new Settlement.Soldier();
        so.entity = e.getUUID(); so.type = RecruitsBridge.unitOf(e); so.name = e.getName().getString(); so.path = path; so.seen = true;
        s.soldiers.add(so);
        return so;
    }

    /** A hero made directly (ascension is tested above), for one path. */
    static LivingEntity hero(ServerLevel level, HeroData data, UnitType unit, BlockPos at, UUID owner, String name, SoldierPath path, List<Entity> spawned) {
        LivingEntity e = spawn(level, unit, at, owner, name, spawned);
        HeroData.Hero h = data.add(e.getUUID());
        h.owner = owner; h.name = name; h.path = path;
        Heroes.track(e);
        Heroes.perks(e, h);
        return e;
    }

    static LivingEntity spawn(ServerLevel level, UnitType unit, BlockPos pos, UUID owner, String name, List<Entity> spawned) {
        Entity e = RecruitsBridge.spawn(level, unit, pos, owner, name);
        check(e instanceof LivingEntity, "Spawn " + name);
        spawned.add(e);
        return (LivingEntity) e;
    }

    static Mob zombie(ServerLevel level, BlockPos pos, List<Entity> spawned) {
        var z = EntityType.ZOMBIE.create(level);
        z.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        z.setPersistenceRequired();
        level.addFreshEntity(z);
        spawned.add(z);
        return z;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException("Heroes: " + message);
        checks++;
    }

    private HeroesSelfTest() {}
}
