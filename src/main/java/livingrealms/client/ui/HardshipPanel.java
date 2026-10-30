package livingrealms.client.ui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static livingrealms.client.ui.Imm.*;

/** Overview card for the town's hardships: risks of sickness, bad harvests and rats, current troubles, the quarantine switch. */
final class HardshipPanel {
    static final int ROW = 12;

    static int height(CompoundTag h) {
        if (h.isEmpty() || !h.getBoolean("enabled")) return 0;
        return 22 + h.getList("troubles", Tag.TAG_COMPOUND).size() * ROW + (h.getList("troubles", Tag.TAG_COMPOUND).isEmpty() ? 0 : 4);
    }

    /** Draws the card; returns the height used (0 when hardships are switched off). */
    static int draw(Imm ui, int x, int y, int w, CompoundTag h, boolean mine, BiConsumer<String, CompoundTag> act) {
        int hh = height(h);
        if (hh == 0) return 0;
        ListTag troubles = h.getList("troubles", Tag.TAG_COMPOUND);
        ui.card(x, y, w, hh - 4);
        ui.item(troubles.isEmpty() ? Items.HONEY_BOTTLE : Items.SPIDER_EYE, x + 4, y + 1);
        ui.text("Hardships", x + 24, y + 5, troubles.isEmpty() ? MUTED : RED);
        // risks
        int rx = x + 30 + ui.width("Hardships"), qw = mine && h.getBoolean("sickOn") ? 92 : 0;
        String[][] risks = {{"Sickness", h.getString("sickRisk")}, {"Harvest", h.getString("harvestRisk")}, {"Rats", h.getString("ratRisk")}};
        int cell = (x + w - qw - 6 - rx) / 3;
        for (String[] r : risks) {
            String label = r[0] + " ";
            ui.text(ui.clip(label, cell / 2), rx, y + 5, FAINT);
            ui.text(ui.clip(r[1], cell - Math.min(ui.width(label), cell / 2) - 4), rx + Math.min(ui.width(label), cell / 2), y + 5, riskColor(r[1]));
            if (ui.over(rx, y + 2, cell, 12)) ui.tip(tip(r[0], h));
            rx += cell;
        }
        if (qw > 0) {
            boolean on = h.getBoolean("quarantine");
            int qx = x + w - qw;
            ui.toggle("quarantine", qx, y + 3, on, () -> { CompoundTag d = new CompoundTag(); d.putBoolean("on", !on); act.accept("quarantine", d); });
            ui.text("Quarantine", qx + 26, y + 5, on ? GOLD : MUTED);
            if (ui.over(qx, y + 2, qw, 12)) ui.tip("Quarantine: close the town's roads to caravans.",
                    "Keeps out sickness carried by caravans from sick towns,", "but no trade comes in or goes out while it lasts.");
        }
        int ty = y + 19;
        for (Tag t : troubles) {
            CompoundTag c = (CompoundTag) t;
            int col = switch (c.getString("color")) { case "red" -> RED; case "orange" -> ORANGE; case "blue" -> BLUE; default -> GOLD; };
            ui.round(x + 10, ty + 3, 4, 4, 2, col);
            ui.text(c.getString("name"), x + 18, ty, col);
            int nx = x + 18 + Math.max(70, ui.width(c.getString("name")) + 6), half = (x + w - nx) / 2;
            ui.text(ui.clip(c.getString("what"), half - 6), nx, ty, TEXT);
            ui.text(ui.clip(c.getString("effect"), half - 8), nx + half, ty, MUTED);
            if (ui.over(x, ty - 1, w, ROW)) ui.tip(c.getString("name") + ": " + c.getString("what"), c.getString("effect"));
            ty += ROW;
        }
        return hh;
    }

    static int riskColor(String label) {
        return switch (label) { case "High" -> RED; case "Moderate" -> ORANGE; case "Low" -> GOLD; case "Off" -> FAINT; default -> GREEN; };
    }

    static List<String> tip(String which, CompoundTag h) {
        List<String> l = new ArrayList<>();
        switch (which) {
            case "Sickness" -> {
                l.add("Sickness risk: " + h.getString("sickRisk"));
                if (!h.getString("sickWhy").isEmpty()) l.add(h.getString("sickWhy"));
                l.add("Crowding, sieges, a poor diet and hunger raise it; caravans from sick towns carry it.");
                l.add("Chapel " + mark(h, "chapel") + " · Apothecary " + mark(h, "apothecary") + " · Herb Lore " + mark(h, "herbLore")
                        + " · remedies in store: " + h.getInt("medicine"));
                l.add("Each lowers the risk, slows the spread, and helps the sick recover. Honey, golden apples and carrots are remedies.");
            }
            case "Harvest" -> {
                l.add("Bad harvest risk: " + h.getString("harvestRisk") + " (here: " + h.getString("harvestKind") + ")");
                l.add("Farms yield far less for a few days; a town that runs out of food goes hungry.");
                l.add("Granaries " + mark(h, "granaries") + " · Storehouse " + mark(h, "storehouse") + " soften the loss; caravans bring food.");
            }
            default -> {
                l.add("Rat risk: " + h.getString("ratRisk"));
                l.add("Rats come for big stores of grain and bread and eat into them for two days.");
                l.add("A storehouse halves the risk and what they eat; Granaries halve what they eat again.");
            }
        }
        return l;
    }

    static String mark(CompoundTag h, String key) { return h.getBoolean(key) ? "✔" : "✖"; }

    private HardshipPanel() {}
}
