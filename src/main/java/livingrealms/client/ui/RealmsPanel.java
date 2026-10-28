package livingrealms.client.ui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;

import java.util.function.BiConsumer;

import static livingrealms.client.ui.Imm.*;

/** Military → Realms: your realm's standing with nearby Village Expansion factions, war and peace, and raids on their villages. */
final class RealmsPanel {
    static void draw(Imm ui, int x, int y, int w, int h, CompoundTag vx, BiConsumer<String, CompoundTag> act) {
        ListTag rows = vx.getList("factions", Tag.TAG_COMPOUND);
        boolean ruler = vx.getBoolean("ruler");
        ui.text("Village Expansion factions within raid range", x + 2, y, GOLD);
        if (vx.contains("realm")) ui.right(ui.clip("Your realm: " + vx.getString("realm"), 150), x + w - 6, y, FAINT);
        if (rows.isEmpty()) { ui.text(ui.clip("No Village Expansion village lies within raid range of this city.", w - 8), x + 4, y + 18, FAINT); return; }
        int total = 0;
        for (Tag t : rows) total += 46 + ((CompoundTag) t).getList("villages", Tag.TAG_COMPOUND).size() * 18 + 6;
        int off = ui.beginScroll("realms", x, y + 14, w, h - 14, total);
        int ry = y + 14 - off;
        for (Tag t : rows) {
            CompoundTag f = (CompoundTag) t;
            ListTag villages = f.getList("villages", Tag.TAG_COMPOUND);
            int ch = 44 + villages.size() * 18;
            ui.card(x, ry, w - 4, ch);
            String status = f.getString("status");
            int sc = switch (status) { case "Allied" -> GREEN; case "At war" -> RED; default -> MUTED; };
            ui.item(Items.WHITE_BANNER, x + 6, ry + 5);
            ui.text(ui.clip(f.getString("name"), w - 220), x + 26, ry + 4, TEXT);
            ui.pill(status, x + 26, ry + 15, alpha(sc, 0x50), sc);
            String sub = f.getInt("villageCount") + " villages · " + f.getInt("recruits") + " recruits" + (f.contains("truce") ? " · truce " + f.getInt("truce") + " min" : "");
            ui.text(ui.clip(sub, w - 230 - ui.pillWidth(status)), x + 30 + ui.pillWidth(status), ry + 17, FAINT);
            if (ui.over(x, ry, w - 190, 30)) ui.tip(f.getString("name") + ": " + status,
                    f.getBoolean("npc") ? "Village Expansion decides its own wars; nearby factions may declare war on your realm." : "Another player's realm.",
                    "At war, their villages can march on your cities and you can raid theirs.");
            CompoundTag who = new CompoundTag();
            who.putString("faction", f.getString("id"));
            boolean war = f.getBoolean("war");
            ui.button("vxWar" + f.getString("id"), x + w - 186, ry + 6, 84, 16, "Declare war", Style.DANGER, ruler && !war, () -> act.accept("vxWar", who));
            ui.button("vxPeace" + f.getString("id"), x + w - 98, ry + 6, 88, 16, "Make peace", Style.SUCCESS, ruler && war, () -> act.accept("vxPeace", who));
            if (ui.over(x + w - 186, ry + 6, 84, 16)) ui.tip("Declare war", "Their allies may join them. Your soldiers and theirs fight on sight.");
            if (ui.over(x + w - 98, ry + 6, 88, 16)) ui.tip("Make peace", "They must agree; Village Expansion weighs their strength and grudges.", "Peace brings a one-day truce.");
            int vy = ry + 42;
            for (Tag vt : villages) {
                CompoundTag v = (CompoundTag) vt;
                ui.round(x + 4, vy - 2, w - 12, 17, 3, CARD_HI);
                ui.text(ui.clip((v.getBoolean("capital") ? "♛ " : "") + v.getString("name"), w - 240), x + 10, vy + 2, v.getBoolean("capital") ? GOLD : TEXT);
                ui.text(v.getInt("dist") + " blocks · " + v.getInt("recruits") + " soldiers", x + w - 220, vy + 2, FAINT);
                String blocker = v.getString("blocker");
                CompoundTag target = new CompoundTag();
                target.putUUID("village", v.getUUID("id"));
                ui.button("vxRaid" + v.getUUID("id"), x + w - 72, vy - 1, 60, 15, "Raid", Style.PRIMARY, blocker.isEmpty(), () -> act.accept("vxRaid", target));
                if (ui.over(x + w - 72, vy - 1, 60, 15)) ui.tip(blocker.isEmpty() ? "Raid " + v.getString("name") : blocker,
                        "Half the nearest eligible city's soldiers (2–12) march. Defeat the garrison and hold the centre 60 s.",
                        v.getBoolean("capital") ? "Their capital: taking it brings every village of the faction." : "Taking it makes it a city of your kingdom.");
                vy += 18;
            }
            ry += ch + 6;
        }
        ui.endScroll();
    }

    private RealmsPanel() {}
}
