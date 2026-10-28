package livingrealms.client;

import livingrealms.building.BuildingType;
import livingrealms.client.ui.Imm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;

/** The strip at the top of the screen while placing a building or road, and the progress of any raid your towns fight. */
public final class Hud {
    static final Imm UI = new Imm();

    public static void render(GuiGraphics g, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui) return;
        boolean placing = Placement.mode != Placement.Mode.NONE;
        if (!placing && ClientState.raids.isEmpty()) return;
        UI.begin(g, -1, -1, 0);
        int y = 8;
        if (placing) y += placement(w, y) + 4;
        for (CompoundTag raid : ClientState.raids) {
            int rw = Math.min(260, w - 16);
            y += raidCard(UI, raid, (w - rw) / 2, y, rw, true) + 4;
        }
        UI.end();
    }

    static int placement(int w, int y) {
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
        // the key hints wrap onto more lines in narrow windows rather than running off the screen
        java.util.List<String> lines = new java.util.ArrayList<>(java.util.List.of(""));
        for (String k : keys.split(" {3}")) {
            String cur = lines.get(lines.size() - 1), next = cur.isEmpty() ? k : cur + "   " + k;
            if (!cur.isEmpty() && UI.width(next) > w - 40) lines.add(k);
            else lines.set(lines.size() - 1, next);
        }
        int textW = UI.width(title);
        for (String l : lines) textW = Math.max(textW, UI.width(l));
        int bw = Math.min(w - 8, textW + 24), x = (w - bw) / 2, bh = 18 + lines.size() * 12 + (Placement.status.isEmpty() ? 0 : 12);
        UI.round(x, y, bw, bh, 6, 0xD0121519);
        UI.round(x + 6, y + 6, 3, 8, 1, Imm.GOLD);
        UI.text(UI.clip(title, bw - 20), x + 14, y + 6, Imm.TEXT);
        int ly = y + 18;
        for (String l : lines) { UI.text(UI.clip(l, bw - 20), x + 12, ly, Imm.MUTED); ly += 12; }
        if (!Placement.status.isEmpty()) UI.text(UI.clip("⚠ " + Placement.status, bw - 20), x + 12, ly, Imm.RED);
        return bh;
    }

    /**
     * One raid: who attacks whom, how long the attackers have held the centre (60 s takes the city) and how long until the
     * attack fails. Shared by the HUD and the City Manager overview. Returns the height used.
     */
    public static int raidCard(Imm ui, CompoundTag r, int x, int y, int w, boolean hud) {
        boolean attacking = r.getBoolean("attacking");
        int accent = attacking ? Imm.ORANGE : Imm.RED, h = 40;
        ui.round(x, y, w, h, 6, hud ? 0xD0121519 : 0xFF1E232B);
        float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 220.0));
        ui.round(x + 6, y + 6, 3, 8, 1, Imm.lerp(accent, 0xFFFFFFFF, attacking ? 0 : pulse * 0.4f));
        String title = attacking ? "Your raid on " + r.getString("target") : r.getString("target") + " is under attack from " + r.getString("source");
        int secsLeft = Math.max(0, (r.getInt("max") - r.getInt("elapsed")) / 20);
        String left = secsLeft / 60 + ":" + String.format("%02d", secsLeft % 60) + " left";
        ui.text(ui.clip("⚔ " + title, w - 26 - ui.width(left)), x + 14, y + 6, accent);
        ui.right(left, x + w - 8, y + 6, Imm.MUTED);
        int held = r.getInt("held"), hold = Math.max(1, r.getInt("hold"));
        String centre = held > 0 ? "Centre held " + held / 20 + "/" + hold / 20 + " s" : "Centre contested";
        int lw = ui.width("Centre held 60/60 s") + 6;
        ui.text(centre, x + 14, y + 18, held > 0 ? Imm.TEXT : Imm.FAINT);
        // the bar fills as the attackers hold the centre: good news when attacking, bad when defending
        int c0 = attacking ? Imm.GREEN : Imm.RED, c1 = attacking ? 0xFF4FA860 : 0xFFB84A4A;
        ui.bar(x + 14 + lw, y + 20, w - 22 - lw, 5, held / (float) hold, c0, c1);
        String status = r.getString("status");
        // the server's line repeats the hold count once the centre is taken; say what it means instead
        if (status.isEmpty() || status.startsWith("City center occupied"))
            status = held > 0 ? (attacking ? "Defenders cleared: hold the centre to take the city" : "Defenders down: retake the centre before the minute is up")
                    : r.getInt("soldiers") + " soldiers marching";
        ui.text(ui.clip(status, w - 22), x + 14, y + 29, Imm.FAINT);
        return h;
    }

    private Hud() {}
}
