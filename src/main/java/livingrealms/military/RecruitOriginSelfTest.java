package livingrealms.military;

import livingrealms.settlement.Settlement;

import livingrealms.military.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Opt-in server verification, with or without Pehkui. Creates no persistent world records. */
public final class RecruitOriginSelfTest {
    public static int run(ServerLevel level) {
        int checks = 0;
        int[] counts = new int[4];
        for (int i = 0; i < 100; i++) counts[RecruitOrigin.select(i, 80, 8, 9, 3).ordinal()]++;
        check(java.util.Arrays.equals(counts, new int[]{80, 8, 9, 3}), "Exact default weighted boundaries"); checks++;
        check(RecruitOrigin.select(0, 0, 0, 0, 0) == RecruitOrigin.HUMAN, "Zero weights fall back to human"); checks++;
        for (RecruitOrigin origin : RecruitOrigin.values()) {
            LivingEntity recruit = RecruitsBridge.type(UnitType.FOOTMAN).create(level);
            check(recruit != null, "Create recruit"); checks++;
            try {
                RecruitOrigins.assign(recruit, origin);
                float health = recruit.getMaxHealth();
                double speed = recruit.getAttributeValue(Attributes.MOVEMENT_SPEED);
                RecruitOrigins.born(recruit); RecruitOrigins.apply(recruit, origin); RecruitOrigins.apply(recruit, origin);
                check(RecruitOrigins.origin(recruit) == origin, "Birth assignment never rerolls"); checks++;
                check(Math.abs(health - recruit.getMaxHealth()) < .001 && Math.abs(speed - recruit.getAttributeValue(Attributes.MOVEMENT_SPEED)) < .000001, "Origin modifiers are idempotent"); checks++;
                var modifier = recruit.getAttribute(Attributes.MAX_HEALTH).getModifier(RecruitOrigins.HEALTH);
                check(origin.health == 0 ? modifier == null : modifier != null && modifier.getAmount() == origin.health, "Exact separate innate health modifier"); checks++;
                Settlement.Soldier so = new Settlement.Soldier();
                so.entity = recruit.getUUID(); so.name = "Origin test"; so.type = UnitType.FOOTMAN;
                RecruitOrigins.sync(so, recruit);
                check(Settlement.Soldier.load(so.save()).origin == origin, "Town origin persistence"); checks++;
                so.skills.add(SoldierSkills.ENDURANCE); so.skills.add(SoldierSkills.MARCHING);
                SoldierProgression.applySkills(recruit, so);
                SoldierProgression.clearSkills(recruit);
                check(Math.abs(health - recruit.getMaxHealth()) < .001 && Math.abs(speed - recruit.getAttributeValue(Attributes.MOVEMENT_SPEED)) < .000001, "Dismissal clears training but retains origin traits"); checks++;
                CompoundTag saved = new CompoundTag(); recruit.saveWithoutId(saved);
                LivingEntity loaded = RecruitsBridge.type(UnitType.FOOTMAN).create(level);
                try {
                    loaded.load(saved);
                    RecruitOrigins.apply(loaded, RecruitOrigins.origin(loaded));
                    check(RecruitOrigins.origin(loaded) == origin && Math.abs(health - loaded.getMaxHealth()) < .001, "Entity reload preserves origin and attributes"); checks++;
                    if (PehkuiBridge.installed()) {
                        check(Math.abs(PehkuiBridge.scale(loaded, true) - origin.height) < .001 && Math.abs(PehkuiBridge.scale(loaded, false) - origin.width) < .001, "Pehkui dimensions persist without compounding"); checks++;
                        var types = Class.forName("virtuoel.pehkui.api.ScaleTypes");
                        var type = Class.forName("virtuoel.pehkui.api.ScaleType");
                        var data = Class.forName("virtuoel.pehkui.api.ScaleData");
                        Object base = type.getMethod("getScaleData", net.minecraft.world.entity.Entity.class).invoke(types.getField("BASE").get(null), loaded);
                        check(Math.abs(((Number)data.getMethod("getBaseScale").invoke(base)).floatValue() - 1) < .001, "BASE damage/reach scale untouched"); checks++;
                        Object heightData = type.getMethod("getScaleData", net.minecraft.world.entity.Entity.class).invoke(types.getField("HEIGHT").get(null), loaded);
                        data.getMethod("setScale", float.class).invoke(heightData, origin.height * 1.2f);
                        RecruitOrigins.apply(loaded, origin);
                        check(Math.abs(PehkuiBridge.scale(loaded, true) - origin.height * 1.2f) < .001, "External height changes preserved"); checks++;
                    }
                } catch (ReflectiveOperationException e) { throw new IllegalStateException("Origin scale API test", e); }
                finally { loaded.discard(); }
            } finally { recruit.discard(); }
        }
        Settlement.Soldier legacy = new Settlement.Soldier(); legacy.entity = java.util.UUID.randomUUID(); legacy.type = UnitType.FOOTMAN; legacy.name = "Legacy";
        CompoundTag old = legacy.save(); old.remove("origin");
        check(Settlement.Soldier.load(old).origin == RecruitOrigin.HUMAN, "Legacy records default human"); checks++;
        old.putString("origin", "INVALID");
        check(Settlement.Soldier.load(old).origin == RecruitOrigin.HUMAN, "Invalid origin safely defaults human"); checks++;
        return checks;
    }
    private static void check(boolean success, String message) { if (!success) throw new IllegalStateException("Origin self-test: " + message); }
    private RecruitOriginSelfTest() {}
}
