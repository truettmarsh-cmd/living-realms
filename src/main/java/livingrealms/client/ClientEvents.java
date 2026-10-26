package livingrealms.client;

import livingrealms.LivingRealms;
import livingrealms.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void render(RenderLevelStageEvent e) { GhostRenderer.render(e); }

    @SubscribeEvent
    public static void scroll(InputEvent.MouseScrollingEvent e) {
        if (Placement.mode == Placement.Mode.NONE || !Placement.holdingPlans() || Minecraft.getInstance().screen != null) return;
        Placement.scroll(e.getScrollDelta(), Screen.hasShiftDown());
        e.setCanceled(true);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        while (ClientSetup.OPEN.consumeClick()) {
            if (mc.player != null) Net.toServer("open", new CompoundTag());
        }
        if (Placement.mode != Placement.Mode.NONE && mc.player != null && !Placement.holdingPlans() && mc.player.tickCount % 20 == 0) {
            // put the plans away to stop placing
            Placement.cancel();
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        ClientState.clear();
        Placement.cancel();
    }

    private ClientEvents() {}
}
