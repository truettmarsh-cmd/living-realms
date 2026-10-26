package livingrealms.military;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;
import java.lang.reflect.Method;

/** Optional public API only. HEIGHT/WIDTH do not change BASE damage or reach scaling. */
public final class PehkuiBridge {
    private static boolean failed;
    private static Object height, width;
    private static Method data, setScale, getScale, persistence, getPersistence;
    public static boolean installed() { return ModList.get().isLoaded("pehkui"); }
    private static boolean ready() {
        if (!installed() || failed) return false;
        if (data != null) return true;
        try {
            Class<?> types = Class.forName("virtuoel.pehkui.api.ScaleTypes");
            Class<?> type = Class.forName("virtuoel.pehkui.api.ScaleType");
            Class<?> scale = Class.forName("virtuoel.pehkui.api.ScaleData");
            height = types.getField("HEIGHT").get(null); width = types.getField("WIDTH").get(null);
            data = type.getMethod("getScaleData", Entity.class);
            setScale = scale.getMethod("setScale", float.class);
            getScale = scale.getMethod("getBaseScale");
            persistence = scale.getMethod("setPersistence", Boolean.class);
            getPersistence = scale.getMethod("getPersistence");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) { fail(e); return false; }
    }
    public static boolean apply(Entity entity, RecruitOrigin origin) {
        if (!ready()) return false;
        try {
            applyScale(entity, data.invoke(height, entity), "LivingRealmsOriginHeight", origin.height);
            applyScale(entity, data.invoke(width, entity), "LivingRealmsOriginWidth", origin.width);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) { fail(e); return false; }
    }
    private static void applyScale(Entity entity, Object scale, String key, float factor) throws ReflectiveOperationException {
        var tag = entity.getPersistentData();
        float previous = tag.contains(key) ? tag.getFloat(key) : 1;
        if (!Float.isFinite(previous) || previous <= 0) previous = 1;
        // Replace only our factor: command/mod supplied scale changes remain intact.
        if (Math.abs(previous - factor) > .0001f) {
            float current = ((Number)getScale.invoke(scale)).floatValue();
            setScale.invoke(scale, current / previous * factor);
        }
        if (factor != 1 && !Boolean.TRUE.equals(getPersistence.invoke(scale))) persistence.invoke(scale, Boolean.TRUE);
        tag.putFloat(key, factor);
    }
    public static float scale(Entity entity, boolean vertical) {
        if (!ready()) return 1;
        try { return ((Number)getScale.invoke(data.invoke(vertical ? height : width, entity))).floatValue(); }
        catch (ReflectiveOperationException | LinkageError e) { fail(e); return 1; }
    }
    private static void fail(Throwable e) { if (!failed) LogUtils.getLogger().warn("Pehkui origin size integration unavailable; innate traits remain active", e); failed = true; }
    private PehkuiBridge() {}
}
