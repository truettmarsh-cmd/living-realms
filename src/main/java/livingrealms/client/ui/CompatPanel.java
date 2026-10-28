package livingrealms.client.ui;

import livingrealms.smithing.Piece;
import livingrealms.smithing.Smithing;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

import static livingrealms.client.ui.Imm.*;

/** Overview strip for the pack-mod integrations (feudal title, siege train, Workers staff, recovered gear). */
final class CompatPanel {
    /** Draws one row when there is anything to show; returns the height used. */
    static int draw(Imm ui, int x, int y, int w, CompoundTag v) {
        var lines = v.getList("lines", Tag.TAG_COMPOUND);
        if (lines.isEmpty()) return 0;
        ui.card(x, y, w, 18);
        ui.text("Pack", x + 6, y + 5, FAINT);
        int px = x + 8 + ui.width("Pack"), cell = (w - (px - x) - 4) / lines.size();
        for (Tag t : lines) {
            CompoundTag l = (CompoundTag) t;
            String label = l.getString("label") + ": ";
            ui.text(ui.clip(label, cell / 2), px + 4, y + 5, MUTED);
            int lw = Math.min(ui.width(label), cell / 2);
            ui.text(ui.clip(l.getString("value"), cell - lw - 8), px + 4 + lw, y + 5, TEXT);
            if (ui.over(px, y, cell, 18)) {
                List<String> tip = new ArrayList<>(List.of(l.getString("label") + ": " + l.getString("value")));
                for (Tag s : l.getList("tip", Tag.TAG_STRING)) if (!s.getAsString().isEmpty()) tip.add(s.getAsString());
                ui.tip(tip);
            }
            px += cell;
        }
        return 22;
    }

    /** The War machines forge tab (always last) only appears when a siege or ship mod supplies patterns. */
    static int forgePieces(int all) {
        return Smithing.patterns().values().stream().anyMatch(p -> p.pieces().containsKey(Piece.WAR_MACHINE)) ? all : all - 1;
    }

    private CompatPanel() {}
}
