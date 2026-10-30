package livingrealms.client;

import com.talhanation.recruits.client.api.ClientOverlayEvent;
import com.talhanation.recruits.client.gui.overlay.ClaimOverlayManager;
import livingrealms.LivingRealms;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Villager Recruits draws its claim panel (the town and realm name, 15 or 45 pixels tall) at the top centre of the
 * screen while the player is in a claim. Our HUD (raid cards, placement strip) starts below it instead of on top of it.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class ClaimPanelGap {
    private static volatile long seenAt;
    private static volatile int height;

    @SubscribeEvent
    public static void drawn(ClientOverlayEvent.RenderPost e) {
        ClaimOverlayManager.OverlayState st = e.getState();
        if (st == null || st == ClaimOverlayManager.OverlayState.HIDDEN || e.getAlpha() <= 0.05f) return;
        height = st == ClaimOverlayManager.OverlayState.FULL ? 45 : 15;
        seenAt = System.currentTimeMillis();
    }

    /** Pixels the HUD should leave free at the top: the claim panel's height and a gap, while it was drawn this moment. */
    public static int top() { return System.currentTimeMillis() - seenAt < 250 ? height + 6 : 0; }

    private ClaimPanelGap() {}
}
