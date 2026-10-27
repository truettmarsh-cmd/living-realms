package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import livingrealms.military.WeaponProficiency;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.*;

/** Merit, requests and the honours board; called only by the opt-in military self-test server. */
public final class MeritSelfTest {
    public static int run(ServerLevel level) {
        int checks = 0;
        final long D = Merit.DAY;
        // pure merit arithmetic
        Merit.Record r = new Merit.Record();
        long t0 = 50 * D;
        Merit.kill(r, t0);
        check(near(r.merit, 10) && Merit.weekKills(r, t0) == 1, "Kill merit"); checks++;
        Merit.damage(r, 25, t0);
        check(near(r.merit, 12), "Damage merit capped per hit"); checks++;
        Merit.damage(r, 5, t0);
        check(near(r.merit, 12.5f), "Damage merit 1 per 10"); checks++;
        Merit.decay(r, t0 + D);
        check(near(r.merit, 12.5f * .85f), "Daily decay 15%"); checks++;
        Merit.decay(r, t0 + 11 * D);
        check(r.merit < 3 && Merit.weekKills(r, t0 + 11 * D) == 0, "Old form fades and the week rolls over"); checks++;
        Merit.Record w = new Merit.Record();
        Merit.kill(w, t0); Merit.kill(w, t0 + D); Merit.kill(w, t0 + 7 * D);
        check(Merit.weekKills(w, t0 + 7 * D) == 2, "Kills this week is a rolling seven days"); checks++;

        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "MeritOwner"));
        var stranger = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "MeritOther"));
        Settlement s = new Settlement();
        s.name = "Ashford"; s.owner = owner.getUUID(); s.dim = level.dimension(); s.center = new BlockPos(0, 100, 0); s.population = 20;
        SettlementManager manager = SettlementManager.get(level.getServer());
        manager.add(s);
        Entity recruit = null;
        try {
            level.getChunkAt(s.center);
            recruit = RecruitsBridge.spawn(level, UnitType.FOOTMAN, s.center, s.owner, "Merit Test");
            check(recruit instanceof LivingEntity, "Recruit spawn"); checks++;
            LivingEntity living = (LivingEntity) recruit;
            Settlement.Soldier so = new Settlement.Soldier();
            so.entity = recruit.getUUID(); so.name = "Merit Test"; so.type = UnitType.FOOTMAN; so.seen = true;
            s.soldiers.add(so);
            long now = level.getServer().overworld().getGameTime();
            // real combat events
            var zombie = EntityType.ZOMBIE.create(level);
            Merit.killed(new LivingDeathEvent(zombie, level.damageSources().mobAttack(living)));
            check(near(so.merit.merit, Merit.KILL) && Merit.weekKills(so.merit, now) == 1, "Hostile kill event grants merit"); checks++;
            Merit.killed(new LivingDeathEvent(EntityType.COW.create(level), level.damageSources().mobAttack(living)));
            check(near(so.merit.merit, Merit.KILL), "Passive kills grant no merit"); checks++;
            Merit.hurt(new LivingHurtEvent(zombie, level.damageSources().mobAttack(living), 30));
            check(near(so.merit.merit, Merit.KILL + Merit.MAX_HIT), "Damage event grants merit"); checks++;
            // duty, survival and raids
            so.merit.merit = 0; so.royalGuard = true;
            Merit.update(s, so, recruit, now, (int) D);
            check(near(so.merit.merit, Merit.SURVIVE_DAY + Merit.GUARD_DAY), "A day of Royal Guard service and survival"); checks++;
            so.royalGuard = false; so.merit.merit = 0;
            Merit.raidEnded(level, s, Set.of(), true, true, null);
            check(so.merit.merit == 0, "A lost defence earns nothing"); checks++;
            Merit.raidEnded(level, s, Set.of(), true, false, null);
            check(near(so.merit.merit, Merit.RAID) && Merit.reason(so, now).contains("defended Ashford"), "Raid defended feat"); checks++;
            // request triggering
            for (var slot : Merit.ARMOUR) RecruitsBridge.setSlot(recruit, slot, ItemStack.EMPTY);
            RecruitsBridge.setSlot(recruit, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            so.preferredWeapon = WeaponProficiency.DAGGER;
            so.merit.merit = 50;
            Merit.update(s, so, recruit, now, 0);
            check(so.merit.want == Merit.Want.NONE, "Below threshold, no request"); checks++;
            so.merit.merit = 70;
            check(!Merit.obtainable(s, WeaponProficiency.DAGGER), "Fixture: daggers not obtainable"); checks++;
            Merit.update(s, so, recruit, now, 0);
            check(so.merit.want == Merit.Want.NONE, "No request for gear the town cannot provide"); checks++;
            so.preferredWeapon = WeaponProficiency.AXE;
            int logs = s.log.size();
            Merit.update(s, so, recruit, now, 0);
            check(so.merit.want == Merit.Want.WEAPON && s.log.size() == logs + 1 && s.log.peekFirst().contains("asks for an axe"), "Favourite weapon request and log"); checks++;
            check(Merit.text(so).equals("Wants an axe (favourite weapon)"), "Request text"); checks++;
            Merit.update(s, so, recruit, now, 0);
            check(s.log.size() == logs + 1, "One request at a time"); checks++;
            // grant from stockpile
            s.stock.force(Items.IRON_AXE, 1);
            CompoundTag who = new CompoundTag(); who.putUUID("soldier", so.entity);
            check(Merit.handle(stranger, s, "grantRequest", who) != null && so.merit.want == Merit.Want.WEAPON, "Owner-only answers"); checks++;
            RecruitsBridge.addMorale(recruit, -100); RecruitsBridge.addMorale(recruit, 50);
            check(Merit.handle(owner, s, "grantRequest", who) == null && living.getMainHandItem().is(Items.IRON_AXE)
                    && s.stock.count(Items.IRON_SWORD) == 1 && s.stock.count(Items.IRON_AXE) == 0, "Grant equips and returns old weapon to stores"); checks++;
            check(so.merit.want == Merit.Want.NONE && so.merit.cooldownUntil == now + Merit.COOLDOWN
                    && near(RecruitsBridge.morale(recruit), 50 + Merit.GRANT_MORALE), "Grant closes with cooldown and morale boost"); checks++;
            check(Merit.handle(owner, s, "grantRequest", who) != null, "Closed request cannot be granted twice"); checks++;
            // armour request and grant, worn gear to the pack
            s.research.add("iron_working");
            ItemStack worn = new ItemStack(Items.LEATHER_HELMET); worn.setDamageValue(10);
            RecruitsBridge.setSlot(recruit, EquipmentSlot.HEAD, worn);
            so.merit.cooldownUntil = 0; so.merit.merit = 70;
            Merit.update(s, so, recruit, now, 0);
            check(so.merit.want == Merit.Want.ARMOUR && Merit.text(so).equals("Wants better armour"), "Armour request below researched best"); checks++;
            check(Merit.handle(owner, s, "grantRequest", who) != null && so.merit.want == Merit.Want.ARMOUR, "No armour in stock: grant refused, request open"); checks++;
            s.stock.force(Items.IRON_HELMET, 1); s.stock.force(Items.IRON_CHESTPLATE, 1); s.stock.force(Items.LEATHER_BOOTS, 1);
            check(Merit.handle(owner, s, "grantRequest", who) == null && living.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
                    && living.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE) && living.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS)
                    && s.stock.count(Items.LEATHER_HELMET) == 0 && RecruitsBridge.countInPack(recruit, Items.LEATHER_HELMET) == 1, "Armour grant; worn gear kept in pack, not stores"); checks++;
            // cap per town
            List<Settlement.Soldier> dummies = new ArrayList<>();
            for (int i = 0; i < Merit.TOWN_CAP; i++) {
                Settlement.Soldier d = new Settlement.Soldier();
                d.entity = UUID.randomUUID(); d.name = "Dummy " + i; d.type = UnitType.FOOTMAN; d.merit.want = Merit.Want.ARMOUR; d.merit.wantAt = now;
                dummies.add(d); s.soldiers.add(d);
            }
            so.preferredWeapon = WeaponProficiency.SWORD; so.merit.cooldownUntil = 0; so.merit.merit = 70;
            Merit.update(s, so, recruit, now, 0);
            check(so.merit.want == Merit.Want.NONE, "Open requests capped per town"); checks++;
            s.soldiers.removeAll(dummies);
            Merit.update(s, so, recruit, now, 0);
            check(so.merit.want == Merit.Want.WEAPON, "Request once a slot frees"); checks++;
            // order from the smithy
            var best = Merit.weaponPattern(s, WeaponProficiency.SWORD);
            check(best != null, "A researched sword pattern"); checks++;
            var cost = best.getKey().pieces().get(best.getValue()).cost();
            cost.forEach((k, v) -> Actions.refund(s, k, v));
            int stockBefore = s.stock.total();
            check(Merit.handle(owner, s, "orderRequest", who) != null && !so.merit.ordered && s.stock.total() == stockBefore, "Order needs a smithy"); checks++;
            Plot smith = new Plot(); smith.typeId = "livingrealms:smithy"; smith.state = Plot.State.BUILT; smith.origin = s.center; smith.blueprintKey = "";
            s.plots.add(smith);
            check(Merit.handle(owner, s, "orderRequest", who) == null && so.merit.ordered && s.orders.size() == 1
                    && s.orders.get(0).pattern.equals(best.getKey().id()) && s.orders.get(0).piece == best.getValue() && s.orders.get(0).quantity == 1, "Order queues a smith order"); checks++;
            check(Merit.handle(owner, s, "orderRequest", who) != null && s.orders.size() == 1, "No double order"); checks++;
            CompoundTag view = new CompoundTag(); Merit.describe(s, view);
            CompoundTag req = view.getList("requests", Tag.TAG_COMPOUND).getCompound(0);
            check(view.getList("requests", Tag.TAG_COMPOUND).size() == 1 && req.getBoolean("ordered") && req.getString("want").equals("WEAPON"), "Requests exposed to the view"); checks++;
            // decline and cooldown
            float morale = RecruitsBridge.morale(recruit);
            check(Merit.handle(owner, s, "declineRequest", who) == null && so.merit.want == Merit.Want.NONE
                    && near(RecruitsBridge.morale(recruit), morale + Merit.DECLINE_MORALE), "Decline closes with morale dip"); checks++;
            so.merit.merit = 500;
            Merit.update(s, so, recruit, now + D, 0);
            check(so.merit.want == Merit.Want.NONE, "Cooldown after an answer"); checks++;
            Merit.update(s, so, recruit, now + Merit.COOLDOWN + 1, 0);
            check(so.merit.want == Merit.Want.WEAPON, "Asks again after cooldown"); checks++;
            Merit.update(s, so, recruit, now + Merit.COOLDOWN + Merit.EXPIRY + 2, 0);
            check(so.merit.want == Merit.Want.NONE && s.log.peekFirst().contains("withdrew"), "Unanswered requests lapse"); checks++;
            // old saves and persistence
            CompoundTag tag = so.save();
            check(tag.contains("merit"), "Merit key on soldier view"); checks++;
            so.merit.want = Merit.Want.ARMOUR; so.merit.ordered = true; so.merit.feat = "raided Brindle"; so.merit.merit = 42;
            Settlement.Soldier back = Settlement.Soldier.load(so.save());
            check(back.merit.want == Merit.Want.ARMOUR && back.merit.ordered && back.merit.feat.equals("raided Brindle") && near(back.merit.merit, 42)
                    && back.merit.cooldownUntil == so.merit.cooldownUntil, "Merit persistence"); checks++;
            CompoundTag old = so.save(); old.remove("merit"); old.remove("meritState");
            Settlement.Soldier legacy = Settlement.Soldier.load(old);
            check(legacy.merit.merit == 0 && legacy.merit.want == Merit.Want.NONE && legacy.merit.updated == -1 && legacy.name.equals(so.name), "Old save loads with defaults"); checks++;
            so.merit.want = Merit.Want.NONE; so.merit.ordered = false;
            // leaderboard
            Settlement board = new Settlement();
            for (int i = 0; i < 12; i++) {
                Settlement.Soldier b = new Settlement.Soldier();
                b.entity = UUID.randomUUID(); b.name = "B" + i; b.type = UnitType.BOWMAN; b.merit.merit = i * 10; b.kills = i;
                board.soldiers.add(b);
            }
            board.soldiers.get(0).merit.merit = 110; // ties with B11; more kills ranks higher
            CompoundTag bv = new CompoundTag(); Merit.describe(board, bv);
            var honours = bv.getList("honours", Tag.TAG_COMPOUND);
            boolean sorted = honours.size() == Merit.BOARD;
            for (int i = 1; i < honours.size(); i++) sorted &= honours.getCompound(i - 1).getInt("merit") >= honours.getCompound(i).getInt("merit");
            check(sorted && honours.getCompound(0).getString("name").equals("B11") && honours.getCompound(1).getString("name").equals("B0")
                    && honours.getCompound(0).getString("title").equals("Recruit") && honours.getCompound(0).getString("fav").isEmpty(), "Leaderboard top 10 by merit, ties by kills"); checks++;
            return checks;
        } finally {
            if (recruit != null) recruit.discard();
            manager.remove(s);
        }
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < .01; }

    private static void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Merit self-test failed: " + label);
    }
    private MeritSelfTest() {}
}
