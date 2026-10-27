package livingrealms.client;

import com.mojang.blaze3d.platform.InputConstants;
import livingrealms.LivingRealms;
import livingrealms.Registry;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = LivingRealms.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    public static final KeyMapping OPEN = new KeyMapping("key.livingrealms.open", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_O), "key.categories.livingrealms");
    public static final KeyMapping ROTATE = new KeyMapping("key.livingrealms.rotate", KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_R), "key.categories.livingrealms");
    /** Opens Recruit Command for the recruit under the crosshair; unbound by default. */
    public static final KeyMapping RECRUIT = new KeyMapping("key.livingrealms.recruit", KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN, "key.categories.livingrealms");

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(Registry.SETTLER.get(), SettlerRenderer::new);
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) {
        e.register(OPEN);
        e.register(ROTATE);
        e.register(RECRUIT);
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent e) {
        e.registerAboveAll("placement", (gui, g, partial, w, h) -> Hud.render(g, w, h));
    }

    private ClientSetup() {}
}
