package livingrealms.client.ui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

import static livingrealms.client.ui.Imm.*;

/** Military → Realms: your realm's standing with nearby Village Expansion factions, war and peace, and raids on their villages. */
final class RealmsPanel {
    static int statusColor(String status) { return switch (status) { case "Allied" -> GREEN; case "At war" -> RED; default -> MUTED; }; }

    static void draw(Imm ui, int x, int y, int w, int h, CompoundTag vx, BiConsumer<String, CompoundTag> act) {
        ListTag rows = vx.getList("factions", Tag.TAG_COMPOUND);
        boolean ruler = vx.getBoolean("ruler");
        // wars first, then allies, then the rest, so the factions that can march on you are at the top
        List<CompoundTag> factions = new ArrayList<>();
        for (Tag t : rows) factions.add((CompoundTag) t);
        factions.sort(Comparator.comparingInt((CompoundTag f) -> f.getBoolean("war") ? 0 : f.getString("status").equals("Allied") ? 1 : 2));
        int wars = 0, allies = 0;
        for (CompoundTag f : factions) { if (f.getBoolean("war")) wars++; else if (f.getString("status").equals("Allied")) allies++; }
        // standing at a glance
        String realm = vx.contains("realm") ? vx.getString("realm") : "";
        int sx = x + 2;
        if (factions.isEmpty()) ui.text("Village Expansion factions within raid range", sx, y, GOLD);
        else {
            String[] parts = {wars == 0 ? "At peace" : "At war with " + wars, allies + " allied", (factions.size() - wars - allies) + " neutral"};
            int[] colors = {wars == 0 ? GREEN : RED, GREEN, MUTED};
            for (int i = 0; i < parts.length; i++) {
                if (i > 0 && (allies == 0 && i == 1)) continue;
                ui.round(sx, y + 1, 6, 6, 3, colors[i]);
                ui.text(parts[i], sx + 9, y, i == 0 ? colors[i] : MUTED);
                sx += 9 + ui.width(parts[i]) + 10;
            }
            if (ui.over(x, y - 1, sx - x, 10)) ui.tip("Village Expansion factions within raid range",
                    wars == 0 ? "No faction is at war with your realm." : wars + (wars == 1 ? " faction is" : " factions are") + " at war with your realm: their villages can march on your cities, and you can raid theirs.");
        }
        if (!realm.isEmpty()) ui.right(ui.clip("Realm: " + realm, Math.max(40, x + w - 6 - sx - 8)), x + w - 6, y, FAINT);
        if (factions.isEmpty()) { ui.text(ui.clip("No Village Expansion village lies within raid range of this city.", w - 8), x + 4, y + 18, FAINT); return; }
        int total = 0;
        for (CompoundTag f : factions) total += 46 + f.getList("villages", Tag.TAG_COMPOUND).size() * 18 + 6;
        int off = ui.beginScroll("realms", x, y + 14, w, h - 14, total);
        int ry = y + 14 - off;
        for (CompoundTag f : factions) {
            ListTag villages = f.getList("villages", Tag.TAG_COMPOUND);
            int ch = 44 + villages.size() * 18;
            String status = f.getString("status");
            boolean war = f.getBoolean("war");
            int sc = statusColor(status);
            ui.card(x, ry, w - 4, ch, war ? 0xFF2A1E22 : CARD, war ? 0xFF7A3434 : BORDER);
            ui.round(x + 1, ry + 4, 3, ch - 8, 1, sc);
            Item banner = war ? Items.RED_BANNER : status.equals("Allied") ? Items.LIME_BANNER : Items.WHITE_BANNER;
            ui.item(banner, x + 8, ry + 5);
            // one diplomacy button: the move that changes the current state
            int bw = 84, bx = x + w - 10 - bw;
            ui.text(ui.clip(f.getString("name"), bx - (x + 28) - 6), x + 28, ry + 4, TEXT);
            ui.pill(status, x + 28, ry + 15, alpha(sc, 0x50), sc);
            int px = x + 32 + ui.pillWidth(status);
            if (f.contains("truce")) {
                String truce = "Truce " + f.getInt("truce") + " min";
                ui.pill(truce, px, ry + 15, alpha(BLUE, 0x40), BLUE);
                if (ui.over(px, ry + 15, ui.pillWidth(truce), 12)) ui.tip("Truce", f.getString("name") + " will not declare war on your realm for " + f.getInt("truce") + " more minutes.");
                px += ui.pillWidth(truce) + 4;
            }
            String sub = f.getInt("villageCount") + " villages · " + f.getInt("recruits") + " recruits";
            ui.text(ui.clip(sub, bx - px - 6), px, ry + 17, FAINT);
            if (ui.over(x, ry, bx - x - 4, 30)) ui.tip(f.getString("name") + ": " + status, sub,
                    f.getBoolean("npc") ? "Village Expansion decides its own wars; nearby factions may declare war on your realm." : "Another player's realm.",
                    "At war, their villages can march on your cities and you can raid theirs.");
            CompoundTag who = new CompoundTag();
            who.putString("faction", f.getString("id"));
            String rulerOnly = ruler ? null : "Only the ruler of the realm can change war and peace.";
            if (war) {
                ui.button("vxPeace" + f.getString("id"), bx, ry + 6, bw, 16, "Make peace", Style.SUCCESS, ruler, () -> act.accept("vxPeace", who));
                if (ui.over(bx, ry + 6, bw, 16)) ui.tip("Make peace", "They must agree; Village Expansion weighs their strength and grudges.", "Peace brings a one-day truce.", rulerOnly == null ? "" : rulerOnly);
            } else {
                boolean truce = f.contains("truce");
                ui.button("vxWar" + f.getString("id"), bx, ry + 6, bw, 16, "Declare war", Style.DANGER, ruler, () -> act.accept("vxWar", who));
                if (ui.over(bx, ry + 6, bw, 16)) ui.tip("Declare war", "Their allies may join them. Your soldiers and theirs fight on sight.",
                        truce ? "This ends the truce made at the last peace." : "", rulerOnly == null ? "" : rulerOnly);
            }
            int vy = ry + 42;
            for (Tag vt : villages) {
                CompoundTag v = (CompoundTag) vt;
                ui.round(x + 4, vy - 2, w - 12, 17, 3, CARD_HI);
                String facts = v.getInt("dist") + " blocks · " + v.getInt("recruits") + " soldiers";
                int raidX = x + w - 72, factsX = raidX - 8 - ui.width(facts);
                String name = (v.getBoolean("capital") ? "♛ " : "") + v.getString("name");
                ui.text(ui.clip(name, factsX - (x + 10) - 6), x + 10, vy + 2, v.getBoolean("capital") ? GOLD : TEXT);
                ui.text(facts, factsX, vy + 2, FAINT);
                String blocker = v.getString("blocker");
                CompoundTag target = new CompoundTag();
                target.putUUID("village", v.getUUID("id"));
                ui.button("vxRaid" + v.getUUID("id"), raidX, vy - 1, 60, 15, "Raid", Style.PRIMARY, blocker.isEmpty(), () -> act.accept("vxRaid", target));
                if (ui.over(raidX, vy - 1, 60, 15)) ui.tip(blocker.isEmpty() ? "Raid " + v.getString("name") : "Can't raid: " + blocker,
                        "Half the nearest eligible city's soldiers (2–12) march. Defeat the garrison and hold the centre 60 s.",
                        v.getBoolean("capital") ? "Their capital: taking it brings every village of the faction." : "Taking it makes it a city of your kingdom.");
                else if (ui.over(x + 4, vy - 2, raidX - x - 8, 17)) ui.tip(name, facts, blocker.isEmpty() ? "Open to a raid" : "Can't raid: " + blocker);
                vy += 18;
            }
            ry += ch + 6;
        }
        ui.endScroll();
    }

    private RealmsPanel() {}
}
