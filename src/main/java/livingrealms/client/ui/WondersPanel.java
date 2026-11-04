package livingrealms.client.ui;

import livingrealms.client.Placement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import static livingrealms.client.ui.Imm.*;

/** The Wonders card on the Kingdom page (docs/wonders.md): each wonder locked, available, building or built, with its effect. */
final class WondersPanel {
    static final int ROW = 34;

    static int height(CompoundTag wd) {
        ListTag l = wd.getList("wonders", Tag.TAG_COMPOUND);
        return l.isEmpty() ? 0 : 22 + l.size() * ROW + 4;
    }

    static void draw(Imm ui, int x, int y, int w, CompoundTag wd) {
        ListTag l = wd.getList("wonders", Tag.TAG_COMPOUND);
        if (l.isEmpty()) return;
        ui.card(x, y, w, height(wd));
        ui.text("Wonders", x + 8, y + 6, GOLD);
        ui.right(wd.getInt("built") + " of " + l.size() + " built · raised in " + wd.getString("capitalName"), x + w - 8, y + 6, FAINT);
        int ry = y + 21;
        boolean canPlan = wd.getBoolean("here") && wd.getBoolean("ruler");
        for (Tag t : l) {
            CompoundTag c = (CompoundTag) t;
            String state = c.getString("state");
            boolean built = state.equals("BUILT"), building = state.equals("BUILDING"), open = state.equals("AVAILABLE");
            if (built) ui.round(x + 4, ry - 2, w - 8, ROW - 2, 3, alpha(GOLD_DK, 0x30));
            ui.item(BuiltInRegistries.ITEM.get(new ResourceLocation(c.getString("icon"))), x + 8, ry + 6);
            ui.text(ui.clip(c.getString("name"), w / 2 - 40), x + 28, ry + 1, built ? GOLD : open || building ? TEXT : FAINT);
            String pill = built ? "BUILT · " + c.getString("where") : building ? "BUILDING · " + Math.round(c.getFloat("progress") * 100) + "%"
                    : open ? "AVAILABLE" : "LOCKED";
            int pc = built ? GOLD : building ? BLUE : open ? GREEN : FAINT;
            ui.pill(pill, x + 32 + ui.width(ui.clip(c.getString("name"), w / 2 - 40)), ry, alpha(pc, 0x40), pc);
            ui.text(ui.clip(c.getString("effect"), w - (open && canPlan ? 110 : 40)), x + 28, ry + 12, built ? MUTED : FAINT);
            String sub = building ? "In " + c.getString("where") + (c.getString("status").isEmpty() ? "" : " · " + c.getString("status"))
                    : open ? c.getString("size") : built ? "Its effects reach every city of the kingdom" : c.getString("reason");
            ui.text(ui.clip(sub, w - (open && canPlan ? 110 : 40)), x + 28, ry + 22, open ? GREEN : building ? BLUE : built ? FAINT : ORANGE);
            if (building) ui.bar(x + w - 80, ry + 4, 70, 5, c.getFloat("progress"), BLUE, BLUE);
            if (open && canPlan) {
                String id = c.getString("id");
                ui.button("wonder" + id, x + w - 78, ry + 8, 70, 15, "Plan it", Style.PRIMARY, true, () -> {
                    Placement.startBuilding(wd.getUUID("town"), wd.getString("style"), id);
                    if (Minecraft.getInstance().player != null && !Placement.holdingPlans())
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("Hold the Architect's Plans to place it.").withStyle(net.minecraft.ChatFormatting.YELLOW), false);
                });
            }
            if (ui.over(x + 4, ry - 2, w - 90, ROW - 2)) ui.tip(c.getString("name"), c.getString("effect"),
                    "Raised in the Capital, one per kingdom. If the Capital falls, the wonder and its effects go to the conqueror.");
            ry += ROW;
        }
    }

    private WondersPanel() {}
}
