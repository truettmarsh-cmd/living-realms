package livingrealms.client.ui;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;

import static livingrealms.client.ui.Imm.*;

/** Overview row for the Dragon Roost (docs/dragons.md): the roost's dragon or egg, and any dragon over the town. */
final class DragonPanel {
    static boolean shown(CompoundTag v) { return v.getBoolean("roost") || v.contains("attack"); }

    static int height(CompoundTag v) { return shown(v) ? 22 : 0; }

    static int draw(Imm ui, int x, int y, int w, CompoundTag v) {
        if (!shown(v)) return 0;
        ui.card(x, y, w, 18);
        boolean attack = v.contains("attack");
        ui.text(attack ? "Dragon!" : "Roost", x + 6, y + 5, attack ? RED : FAINT);
        int px = x + 12 + ui.width("Dragon!");
        List<String> tip = new ArrayList<>();
        if (attack) {
            String a = v.getString("attack");
            ui.text(ui.clip(a, w - (px - x) - 8), px, y + 5, ORANGE);
            tip.add(a);
            tip.add("Bowmen, scorpions and the roost's dragon fight it. Its fires go to the bucket brigade.");
        } else if (!v.getBoolean("installed")) {
            ui.text(ui.clip("Ice and Fire is not installed: the roost stands empty", w - (px - x) - 8), px, y + 5, MUTED);
            tip.add("Dragons come from Ice and Fire. Without it the roost is only a building.");
        } else if (v.contains("name")) {
            String head = v.getString("name") + " · " + v.getString("element") + " dragon, stage " + v.getInt("stage");
            float hp = v.getFloat("hp"), max = Math.max(1, v.getFloat("max"));
            String state = v.getString("state");
            int barW = Math.min(60, w / 6), stateW = Math.min(ui.width(state), w / 3);
            int room = w - (px - x) - barW - stateW - 24;
            ui.text(ui.clip(head, room), px, y + 5, TEXT);
            int bx = px + Math.min(ui.width(head), room) + 8;
            ui.bar(bx, y + 7, barW, 4, hp / max, hp / max > .5f ? GREEN : ORANGE, GOLD_DK);
            ui.right(ui.clip(state, stateW), x + w - 8, y + 5, MUTED);
            tip.add(head);
            tip.add("Health " + (int) hp + " / " + (int) max + " · " + state);
            if (v.contains("bond")) tip.add("Dragon Bond level " + v.getInt("bond") + "; the roost's nest is its home");
            tip.add(v.getBoolean("rider") ? "Grown enough to carry a Dragon Rider trained here." : "Carries a Dragon Rider from stage 3; the keepers' food makes it grow faster.");
            tip.add(v.getBoolean("hatched") ? "Hatched here: it belongs to the town." : "Your own dragon, living at the roost.");
        } else {
            String state = v.getString("state").isEmpty() ? "Empty" : v.getString("state");
            ui.text(ui.clip(state, w - (px - x) - 8), px, y + 5, v.contains("egg") ? GOLD : MUTED);
            tip.add(state);
            tip.add("Put an Ice and Fire dragon egg in the stockpile (delivery chest), or order your tamed dragon to sit in the roost.");
        }
        if (v.getBoolean("scorpions")) tip.add("Scorpions are mounted on the towers.");
        if (ui.over(x, y, w, 18)) ui.tip(tip);
        return 22;
    }

    private DragonPanel() {}
}
