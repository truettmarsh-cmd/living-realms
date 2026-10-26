package livingrealms.client;

import livingrealms.building.BuildingType;
import livingrealms.client.ui.Imm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** The strip at the top of the screen while placing a building or road. */
public final class Hud {
    static final Imm UI = new Imm();

    public static void render(GuiGraphics g, int w, int h) {
        if (Placement.mode == Placement.Mode.NONE || Minecraft.getInstance().screen != null) return;
        UI.begin(g, -1, -1, 0);
        String title, keys;
        if (Placement.mode == Placement.Mode.BUILDING) {
            BuildingType t = Placement.type();
            title = "Placing " + (t == null ? "?" : t.name) + "  ·  facing " + new String[]{"south", "west", "north", "east"}[Placement.rot & 3]
                    + (Placement.yOff != 0 ? "  ·  " + (Placement.yOff > 0 ? "+" : "") + Placement.yOff : "");
            keys = "Right-click place   Scroll rotate   Shift+Scroll raise/lower   Shift+Right-click cancel";
        } else {
            title = "Laying a " + Placement.roadMaterial.replace('_', ' ') + " road  ·  " + Placement.roadWidth + " wide" + (Placement.roadStart == null ? "  ·  click the start" : "");
            keys = "Right-click set point   Scroll width   Shift+Right-click finish";
        }
        int bw = Math.max(UI.width(title), UI.width(keys)) + 24, x = (w - bw) / 2, y = 8;
        UI.round(x, y, bw, Placement.status.isEmpty() ? 30 : 42, 6, 0xD0121519);
        UI.round(x + 6, y + 6, 3, 8, 1, Imm.GOLD);
        UI.text(title, x + 14, y + 6, Imm.TEXT);
        UI.text(keys, x + 12, y + 18, Imm.MUTED);
        if (!Placement.status.isEmpty()) UI.text("⚠ " + Placement.status, x + 12, y + 30, Imm.RED);
        UI.end();
    }

    private Hud() {}
}
