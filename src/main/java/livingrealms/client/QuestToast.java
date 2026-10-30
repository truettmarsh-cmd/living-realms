package livingrealms.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A quest step, chapter or bounty finished: an advancement-style toast and a sound. */
public final class QuestToast implements Toast {
    private final String title, text;
    private final ItemStack icon;
    private final boolean big;

    QuestToast(String title, String text, ItemStack icon, boolean big) {
        this.title = title;
        this.text = text;
        this.icon = icon;
        this.big = big;
    }

    public static void show(CompoundTag t) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation rl = ResourceLocation.tryParse(t.getString("icon"));
        var item = rl == null ? Items.WRITABLE_BOOK : BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.WRITABLE_BOOK);
        boolean big = t.getBoolean("big");
        mc.getToasts().addToast(new QuestToast(t.getString("title"), t.getString("text"), new ItemStack(item), big));
        mc.getSoundManager().play(big ? SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f)
                : SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.2f, 0.5f));
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long time) {
        g.blit(TEXTURE, 0, 0, 0, 0, width(), height());
        Font font = toasts.getMinecraft().font;
        g.drawString(font, font.plainSubstrByWidth(title, 125), 30, 7, big ? 0xFFFF88FF : 0xFFFFFF00, false);
        g.drawString(font, font.plainSubstrByWidth(text, 125), 30, 18, 0xFFFFFFFF, false);
        g.renderFakeItem(icon, 8, 8);
        return time >= (big ? 8000 : 5000) ? Visibility.HIDE : Visibility.SHOW;
    }
}
