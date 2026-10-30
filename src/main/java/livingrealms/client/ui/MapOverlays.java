package livingrealms.client.ui;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

import static livingrealms.client.ui.Imm.*;

/**
 * What the City Map draws over the town besides its plots: trade routes toward the partner towns (the road the caravans
 * take, out along the high street), caravans on the road, and the posts of the player's patrol bounties.
 */
final class MapOverlays {
    static final int ROUTE = 0xFF5FD38A, SHUT = 0xFFB05050, POST = 0xFFE8C15A, VISITED = 0xFF6FCF7F;

    /** ox, oy: screen position of the map's origin column; s: pixels per block. */
    static void draw(Imm ui, CompoundTag v, float ox, float oy, float s, int mx, int my, int mw, int mh) {
        List<String> tip = null;
        for (Tag t : v.getCompound("trade").getList("routes", Tag.TAG_COMPOUND)) {
            CompoundTag r = (CompoundTag) t;
            int[] path = r.getIntArray("path");
            if (path.length < 4) continue;
            boolean open = r.getBoolean("active");
            int col = open ? ROUTE : SHUT;
            float lx = Float.NaN, ly = Float.NaN;
            for (int i = 0; i + 3 < path.length; i += 2) {
                float ax = sx(ox, s, path[i]), ay = sy(oy, s, path[i + 1]), bx = sx(ox, s, path[i + 2]), by = sy(oy, s, path[i + 3]);
                int n = Math.max(1, (int) (Math.hypot(bx - ax, by - ay) / 3));
                for (int k = 0; k <= n; k++) {
                    float x = ax + (bx - ax) * k / n, y = ay + (by - ay) * k / n;
                    if (x < mx + 4 || y < my + 4 || x > mx + mw - 4 || y > my + mh - 4) continue;
                    lx = x; ly = y;
                    if (k % 2 == 0) ui.rect((int) x - 1, (int) y - 1, 2, 2, alpha(col, 0xE0));
                }
            }
            // the partner's name where its road leaves the map
            if (!Float.isNaN(lx)) {
                String name = "→ " + r.getString("name");
                int w = ui.width(name) + 6;
                int x = (int) Math.max(mx + 3, Math.min(mx + mw - w - 3, lx - w / 2f)), y = (int) Math.max(my + 24, Math.min(my + mh - 30, ly - 5));
                ui.round(x, y, w, 11, 3, 0xD0101317);
                ui.text(name, x + 3, y + 2, col);
                if (ui.over(x, y, w, 11)) tip = List.of("Trade route to " + r.getString("name"), r.getString("standing") + " · " + r.getInt("distance") + " blocks"
                        + " · " + r.getInt("trips") + " trips", open ? "Caravans take the high street out of town" : "Shut: " + r.getString("blocked"));
            }
            CompoundTag c = r.getCompound("caravan");
            if (c.contains("x") && !c.getString("state").equals("ARRIVED") && !c.getString("state").equals("LOST")) {
                int x = (int) sx(ox, s, c.getInt("x")), y = (int) sy(oy, s, c.getInt("z"));
                if (x > mx + 2 && y > my + 2 && x < mx + mw - 2 && y < my + mh - 2) {
                    ui.round(x - 6, y - 6, 12, 12, 6, 0xE0101317);
                    ui.itemScaled(new ItemStack(Items.CHEST), x - 5, y - 5, 0.625f);
                    if (ui.over(x - 6, y - 6, 12, 12)) tip = List.of("Caravan " + (c.getBoolean("out") ? "to " : "from ") + c.getString("other"),
                            c.getString("status") + " · " + Math.round(c.getDouble("progress") * 100) + "%" + (c.getBoolean("walking") ? " · walking" : ""),
                            c.getString("cargo"));
                }
            }
        }
        // patrol posts of the bounties the player took here
        for (Tag t : v.getCompound("quests").getList("board", Tag.TAG_COMPOUND)) {
            CompoundTag b = (CompoundTag) t;
            if (!b.getBoolean("mine") || !b.getString("kind").equals("PATROL")) continue;
            long[] posts = b.getLongArray("posts");
            int visited = b.getInt("visited");
            for (int i = 0; i < posts.length; i++) {
                BlockPos p = BlockPos.of(posts[i]);
                boolean done = (visited & 1 << i) != 0;
                int x = (int) sx(ox, s, p.getX()), y = (int) sy(oy, s, p.getZ());
                x = Math.max(mx + 5, Math.min(mx + mw - 5, x)); y = Math.max(my + 5, Math.min(my + mh - 5, y));
                ui.round(x - 4, y - 4, 9, 9, 2, 0xE0101317);
                ui.round(x - 3, y - 3, 7, 7, 2, done ? VISITED : POST);
                if (ui.over(x - 4, y - 4, 9, 9)) tip = List.of("Patrol post " + (i + 1) + (done ? " · visited" : ""), p.getX() + ", " + p.getZ(),
                        done ? "Walk to the others" : "Walk within 8 blocks of it; a beam of light marks it");
            }
        }
        if (tip != null) ui.tip(new ArrayList<>(tip));
    }

    static float sx(float ox, float s, int x) { return ox + (x + 0.5f - CityMap.originX) * s; }

    static float sy(float oy, float s, int z) { return oy + (z + 0.5f - CityMap.originZ) * s; }

    private MapOverlays() {}
}
