package livingrealms.client.ui;

import livingrealms.military.UnitType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.function.BiConsumer;

import static livingrealms.client.ui.Imm.*;

/** Military → Honours: open recruit requests and the top ten recruits by merit. */
final class HonoursPanel {
    static void draw(Imm ui, int x, int y, int w, int h, CompoundTag v, boolean mine, BiConsumer<String, CompoundTag> act) {
        ListTag reqs = v.getList("requests", Tag.TAG_COMPOUND), board = v.getList("honours", Tag.TAG_COMPOUND);
        int threshold = v.getInt("meritThreshold");
        int reqH = 18 + Math.max(1, reqs.size()) * 30 + 4, boardH = 34 + Math.max(1, board.size()) * 26 + 4;
        int off = ui.beginScroll("honours", x, y, w, h, reqH + 6 + boardH);
        int top = y - off;
        // requests
        ui.card(x, top, w - 4, reqH);
        ui.text("Requests", x + 8, top + 6, GOLD);
        ui.right(reqs.size() + " / 3 open", x + w - 12, top + 6, FAINT);
        int ry = top + 20;
        if (reqs.isEmpty()) ui.text(ui.clip("None open. Recruits with " + threshold + "+ merit ask for their favourite weapon or better armour.", w - 24), x + 10, ry + 9, FAINT);
        for (Tag t : reqs) {
            CompoundTag r = (CompoundTag) t;
            boolean weapon = r.getString("want").equals("WEAPON"), stocked = r.getBoolean("inStock"), ordered = r.getBoolean("ordered");
            String orderAs = r.getString("orderAs");
            ui.round(x + 4, ry, w - 12, 27, 4, CARD_HI);
            ui.item(weapon ? Items.IRON_SWORD : Items.IRON_CHESTPLATE, x + 9, ry + 5);
            int bx = x + w - 10 - 136, room = bx - (x + 30) - 6;
            String name = ui.clip(r.getString("name"), room / 2), want = weapon ? "favourite " + r.getString("family") : "better armour";
            ui.text(name, x + 30, ry + 4, TEXT);
            ui.text(ui.clip("wants " + want, room - ui.width(name) - 4), x + 34 + ui.width(name), ry + 4, GOLD);
            String status = "Merit " + r.getInt("merit") + " · " + (stocked ? "in stock" : ordered ? "ordered" : "not in stock") + " · " + r.getInt("daysLeft") + "d left";
            ui.text(ui.clip(status, room), x + 30, ry + 15, stocked ? GREEN : ordered ? BLUE : FAINT);
            if (ui.over(x + 4, ry, room + 26, 27)) ui.tip(r.getString("name"), r.getString("text"), "Merit " + r.getInt("merit") + " · lapses in " + r.getInt("daysLeft") + " days if unanswered");
            CompoundTag who = new CompoundTag();
            who.putUUID("soldier", r.getUUID("soldier"));
            String id = r.getUUID("soldier").toString();
            ui.button("grant" + id, bx, ry + 6, 42, 15, "Grant", Style.SUCCESS, mine && stocked, () -> act.accept("grantRequest", who));
            ui.button("order" + id, bx + 45, ry + 6, 42, 15, "Order", Style.SECONDARY, mine && !ordered && !orderAs.isEmpty(), () -> act.accept("orderRequest", who));
            ui.button("decline" + id, bx + 90, ry + 6, 46, 15, "Decline", Style.GHOST, mine, () -> act.accept("declineRequest", who));
            if (ui.over(bx, ry + 6, 42, 15)) ui.tip("Grant from the stockpile", stocked ? "Equips it now; old gear returns to stores. +10 morale." : "Nothing suitable in the stockpile yet.");
            if (ui.over(bx + 45, ry + 6, 42, 15)) ui.tip("Order from the smithy", orderAs.isEmpty() ? "No researched pattern makes it." : ordered ? "Already ordered. Grant it once forged." : "Queues 1× " + orderAs + " with the blacksmith.");
            if (ui.over(bx + 90, ry + 6, 46, 15)) ui.tip("Decline", "-8 morale. They won't ask again for 3 days.");
            ry += 30;
        }
        // leaderboard
        int by = top + reqH + 6;
        ui.card(x, by, w - 4, boardH);
        ui.text("Honours board", x + 8, by + 6, GOLD);
        int legendRoom = w - 28 - ui.width("Honours board");
        ui.right(ui.clip("Merit: kills, damage, raids, guard duty, survival · fades 15% a day", legendRoom), x + w - 12, by + 6, FAINT);
        if (ui.over(x + 4, by + 2, w - 12, 14)) ui.tip("Merit", "+10 per hostile kill, +1 per 10 damage, +25 per raid defended or won,", "+6 a day on Royal Guard duty, +2 a day alive. Fades 15% a day.", "At " + threshold + "+ a recruit may ask for gear (3 open per town).");
        ui.hline(x + 6, by + 18, w - 16, LINE);
        int col = Math.max(150, (w - 4) * 2 / 5);
        int top10 = 0;
        for (Tag t : board) top10 = Math.max(top10, ((CompoundTag) t).getInt("merit"));
        int y0 = by + 22;
        if (board.isEmpty()) ui.text("No soldiers yet.", x + 10, y0 + 6, FAINT);
        int i = 0;
        for (Tag t : board) {
            CompoundTag s = (CompoundTag) t;
            int ty = y0 + i * 26, merit = s.getInt("merit");
            if (i % 2 == 0) ui.rect(x + 4, ty, w - 12, 25, 0x10FFFFFF);
            int place = i + 1, pc = place == 1 ? GOLD : place == 2 ? 0xFFC8CCD4 : place == 3 ? 0xFFD0915A : FAINT;
            ui.right(place + ".", x + 22, ty + 8, pc);
            Item icon;
            try { icon = UnitType.valueOf(s.getString("type")).icon; } catch (Exception e) { icon = Items.IRON_SWORD; }
            ui.item(icon, x + 26, ty + 4);
            String name = (s.getBoolean("royalGuard") ? "♛ " : "") + s.getString("name");
            ui.text(ui.clip(name, col - 50), x + 46, ty + 3, place <= 3 ? pc : TEXT);
            ui.text(ui.clip(s.getString("title") + " · " + s.getInt("kills") + " kills", col - 50), x + 46, ty + 14, MUTED);
            int mx = x + col;
            ui.text(String.valueOf(merit), mx, ty + 3, merit >= threshold ? GOLD : TEXT);
            ui.bar(mx + 24, ty + 5, 50, 5, top10 <= 0 ? 0 : merit / (float) Math.max(top10, threshold), merit >= threshold ? GOLD : BLUE, merit >= threshold ? GOLD_DK : 0xFF3F6FA8);
            ui.text(ui.clip("♥ " + (s.getString("fav").isEmpty() ? "no favourite yet" : s.getString("fav")), x + w - 12 - (mx + 82)), mx + 82, ty + 3, MUTED);
            ui.text(ui.clip(s.getString("reason"), x + w - 12 - mx), mx, ty + 14, s.getBoolean("wants") ? GOLD_DK : FAINT);
            if (ui.over(x + 4, ty, w - 12, 25)) ui.tip(s.getString("name"), s.getString("title") + " · merit " + merit + " (requests from " + threshold + ")",
                    s.getInt("kills") + " kills in service · favourite: " + (s.getString("fav").isEmpty() ? "none yet" : s.getString("fav")), s.getString("reason"));
            i++;
        }
        ui.endScroll();
    }

    private HonoursPanel() {}
}
