package livingrealms.settlement.vx;

import livingrealms.Config;
import net.minecraftforge.fml.ModList;

/** Village Expansion (village_recruits) integration switch. Safe to call without the mod: nothing here touches its classes. */
public final class Vx {
    public static final String MODID = "village_recruits";
    private static Boolean present;

    public static boolean present() {
        if (present == null) present = ModList.get() != null && ModList.get().isLoaded(MODID);
        return present;
    }

    /** Installed and switched on in the server config. */
    public static boolean active() {
        return present() && Config.VILLAGE_EXPANSION.get();
    }

    private Vx() {}
}
