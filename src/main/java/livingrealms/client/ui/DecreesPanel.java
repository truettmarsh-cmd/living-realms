package livingrealms.client.ui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static livingrealms.client.ui.Imm.*;

/** The Decrees card of the Kingdom page: each royal decree, whether it can be issued, its cost and cooldown (settlement.Decrees). */
final class DecreesPanel {
    static final int ROW = 30;

    static int height(CompoundTag dc) {
        return dc.isEmpty() ? 0 : 24 + dc.getList("list", Tag.TAG_COMPOUND).size() * ROW + 4;
    }

    static void draw(Imm ui, int x, int y, int w, CompoundTag dc, boolean ruler, BiConsumer<String, CompoundTag> act) {
        ListTag list = dc.getList("list", Tag.TAG_COMPOUND);
        ui.card(x, y, w, height(dc));
        ui.text("Royal decrees", x + 8, y + 6, GOLD);
        int levies = dc.getInt("levies");
        String head = "kingdom rank " + dc.getString("rank") + (dc.getBoolean("researched") ? "" : " · research Royal Decrees")
                + (levies > 0 ? " · " + levies + (levies == 1 ? " levy" : " levies") + " under arms" : "");
        ui.right(ui.clip(head, w - 100), x + w - 8, y + 6, FAINT);
        int ry = y + 22;
        for (Tag t : list) {
            CompoundTag d = (CompoundTag) t;
            String state = d.getString("state"), why = d.getString("why");
            boolean locked = state.equals("locked");
            ResourceLocation rl = ResourceLocation.tryParse(d.getString("icon"));
            ui.item(rl == null ? Items.PAPER : BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.PAPER), x + 8, ry + 3);
            String name = d.getString("name");
            ui.text(name, x + 28, ry + 2, locked ? FAINT : TEXT);
            ui.text(d.getString("tier"), x + 32 + ui.width(name), ry + 2, d.getString("tier").equals("Capital") ? PURPLE : BLUE);
            ui.text(ui.clip(d.getString("desc"), w - 28 - 170), x + 28, ry + 13, locked ? FAINT : MUTED);
            String status; int col;
            switch (state) {
                case "active" -> { status = "In force · " + d.getInt("activeMin") + " min"; col = GREEN; }
                case "cooldown" -> { status = "Ready in " + time(d.getInt("readyMin")); col = ORANGE; }
                case "locked" -> { status = "Needs a " + d.getString("tier"); col = FAINT; }
                default -> { status = why.isEmpty() ? "Ready" : "Not now"; col = why.isEmpty() ? GOLD : MUTED; }
            }
            ui.right(status, x + w - 82, ry + 2, col);
            ui.right(ui.clip(d.getString("cost"), 150), x + w - 82, ry + 13, FAINT);
            CompoundTag data = new CompoundTag();
            data.putString("decree", d.getString("id"));
            ui.button("decree" + d.getString("id"), x + w - 74, ry + 5, 66, 15, "Decree", Style.PRIMARY, ruler && why.isEmpty(),
                    () -> act.accept("scDecree", data));
            if (ui.over(x + 4, ry, w - 8, ROW - 2)) {
                List<String> tip = new ArrayList<>(List.of(name + " (" + d.getString("tier") + " rank)", d.getString("desc"),
                        "Cost: " + d.getString("cost") + ", from your capital's stockpile.", "Cooldown: " + d.getString("cooldown") + "."));
                if (!why.isEmpty()) tip.add(why + ".");
                else if (!ruler) tip.add("Only the ruler can issue decrees.");
                ui.tip(tip);
            }
            ry += ROW;
        }
    }

    static String time(int minutes) { return minutes >= 120 ? (minutes + 30) / 60 + " h" : minutes + " min"; }

    private DecreesPanel() {}
}
