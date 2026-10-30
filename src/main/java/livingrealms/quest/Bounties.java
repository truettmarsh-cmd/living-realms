package livingrealms.quest;

import livingrealms.Config;
import livingrealms.building.Blueprint;
import livingrealms.quest.QuestData.Board;
import livingrealms.quest.QuestData.Bounty;
import livingrealms.quest.QuestData.Kind;
import livingrealms.settlement.AutoBuild;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Town bounties: each town keeps up to three on its board, chosen from what it needs. Deliveries of what the builders
 * are short of (or food when the granary is low), slaying hostile mobs near the town, and a patrol of its border.
 * Taken from the City Manager, they expire after a few days; rewards are emeralds and grow with the town's rank.
 */
public final class Bounties {
    public static final int BOARD = 3, MAX_TAKEN = 3, PATROL_REACH = 8, SLAY_MARGIN = 32;
    /** Generic stores a town asks for when nothing is short, by rank (Camp first). */
    static final Item[] STORES = {Items.OAK_LOG, Items.OAK_LOG, Items.COBBLESTONE, Items.COBBLESTONE, Items.IRON_INGOT, Items.IRON_INGOT};

    static long day() { return Config.DAY_LENGTH.get(); }

    /** Bounties live this long on the board; a taken one has at least a day left. */
    public static long lifetime() { return 3 * day(); }

    /** Gap after a bounty leaves the board before the town posts another. */
    public static long gap() { return day() / 4; }

    public static boolean offers(Settlement s) { return !s.mirror(); }

    /** Expires old bounties and posts new ones while the board has room. */
    public static void refresh(QuestData data, SettlementManager m, Settlement s, long now, RandomSource rnd, @Nullable MinecraftServer server) {
        if (!offers(s)) return;
        Board b = data.board(s.id);
        for (Iterator<Bounty> it = b.bounties.iterator(); it.hasNext(); ) {
            Bounty x = it.next();
            if (x.expires > now) continue;
            it.remove();
            b.nextPost = Math.max(b.nextPost, now + gap());
            ServerPlayer p = x.taker == null || server == null ? null : server.getPlayerList().getPlayer(x.taker);
            if (p != null) Quests.notify(p, "Bounty expired", title(x) + " for " + s.name, Items.CLOCK, false);
            s.dirty = true;
        }
        while (b.bounties.size() < BOARD && now >= b.nextPost) {
            Bounty x = make(s, b, now, rnd);
            if (x == null) break;
            b.bounties.add(x);
            s.dirty = true;
        }
    }

    @Nullable
    static Bounty make(Settlement s, Board b, long now, RandomSource rnd) {
        int tier = s.tier.number();
        Set<Kind> have = EnumSet.noneOf(Kind.class);
        Set<Item> asked = new HashSet<>();
        for (Bounty x : b.bounties) { have.add(x.kind); if (x.kind == Kind.DELIVER) asked.add(x.item); }
        Bounty x = new Bounty();
        x.posted = now;
        x.expires = now + lifetime();
        boolean second = have.contains(Kind.SLAY) && have.contains(Kind.PATROL);
        if ((!have.contains(Kind.DELIVER) || second) && deliver(s, x, asked)) {
            x.reward = tier + Mth.ceil(x.need / 32.0);
            return x;
        }
        if (second) return null;
        if (!have.contains(Kind.SLAY)) {
            x.kind = Kind.SLAY;
            x.need = 4 + 2 * tier;
            x.reward = 1 + 2 * tier;
            x.reason = "monsters prowl the outskirts";
            return x;
        }
        x.kind = Kind.PATROL;
        int r = Math.max(16, s.radius() - 6);
        int start = rnd.nextInt(4);
        for (int i = 0; i < 4; i++) {
            int q = (start + i) % 4;
            int dx = q == 1 ? r : q == 3 ? -r : 0, dz = q == 0 ? -r : q == 2 ? r : 0;
            x.points.add(s.center.offset(dx, 0, dz));
        }
        x.need = x.points.size();
        x.reward = 1 + tier;
        x.reason = "the watch wants the border walked";
        return x;
    }

    /** A delivery of what the builders are short of, else food when the granary is low, else stores for the rank. */
    static boolean deliver(Settlement s, Bounty x, Set<Item> asked) {
        x.kind = Kind.DELIVER;
        int tier = s.tier.number(), cap = 32 + 32 * tier;
        for (var e : shortages(s)) {
            if (asked.contains(e.getKey())) continue;
            x.item = e.getKey();
            x.need = round8(Mth.clamp(e.getValue(), 16, cap));
            x.reason = e.getKey() == waiting(s) ? "the builders are waiting for it" : "the building queue is short of it";
            return true;
        }
        int food = s.stock.count(Items.BREAD) + s.stock.count(Items.CARROT) + s.stock.count(Items.POTATO);
        if ((s.starving || food < s.population * 4) && !asked.contains(Items.BREAD)) {
            x.item = Items.BREAD;
            x.need = round8(Mth.clamp(s.population * 4 - food, 16, 64));
            x.reason = "the granary is running low";
            return true;
        }
        for (int i = tier - 1; i >= 0; i--) {
            Item it = STORES[Math.min(STORES.length - 1, i)];
            if (asked.contains(it)) continue;
            x.item = it;
            x.need = it == Items.IRON_INGOT ? 16 : 64;
            x.reason = "for the town's stores";
            return true;
        }
        return false;
    }

    static int round8(int n) { return (n + 7) / 8 * 8; }

    /** The item a builder last stopped for ("Waiting for Cobblestone"), or null. */
    @Nullable
    static Item waiting(Settlement s) {
        for (Plot p : s.plots) {
            if (!AutoBuild.pending(p) || !p.status.startsWith("Waiting for ")) continue;
            Blueprint bp = p.blueprint();
            if (bp == null) continue;
            String name = p.status.substring("Waiting for ".length());
            for (Item it : bp.bill().keySet()) if (it.getDescription().getString().equals(name)) return it;
        }
        return null;
    }

    /** What the queued and unfinished projects still need beyond the stockpile, most first (what a builder waits for leads). */
    public static List<Map.Entry<Item, Integer>> shortages(Settlement s) {
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (Plot p : s.plots) {
            if (!AutoBuild.pending(p) || p.state == Plot.State.DEMOLISHING) continue;
            Blueprint bp = p.blueprint();
            if (bp == null) continue;
            double left = 1 - Math.min(1.0, p.paid / (double) Math.max(1, p.totalSteps()));
            bp.bill().forEach((it, n) -> need.merge(it, (int) Math.ceil(n * left), Integer::sum));
        }
        List<Map.Entry<Item, Integer>> out = new ArrayList<>();
        need.forEach((it, n) -> {
            int short_ = n - s.stock.count(it);
            if (short_ >= 8 && it != Items.DIRT) out.add(Map.entry(it, short_));
        });
        out.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        Item w = waiting(s);
        if (w != null) for (int i = 0; i < out.size(); i++) if (out.get(i).getKey() == w) { out.add(0, out.remove(i)); break; }
        return out;
    }

    public static String title(Bounty x) {
        return switch (x.kind) {
            case DELIVER -> "Deliver " + x.need + " " + x.item.getDescription().getString();
            case SLAY -> "Slay " + x.need + " hostile mobs";
            case PATROL -> "Patrol the border";
        };
    }

    public static String detail(Bounty x, Settlement s) {
        return switch (x.kind) {
            case DELIVER -> "Bring it in your inventory and press Deliver: " + x.reason + ".";
            case SLAY -> "Within " + (s.radius() + SLAY_MARGIN) + " blocks of the centre, by you or your soldiers: " + x.reason + ".";
            case PATROL -> "Walk to the four marker posts at the town's edge (N/E/S/W): " + x.reason + ".";
        };
    }

    @Nullable
    static Bounty find(QuestData data, Settlement s, UUID id) {
        for (Bounty x : data.board(s.id).bounties) if (x.id.equals(id)) return x;
        return null;
    }

    // ------------------------------------------------------------------ actions

    @Nullable
    public static String accept(QuestData data, SettlementManager m, Settlement s, ServerPlayer p, UUID id, long now) {
        if (!offers(s)) return "Village Expansion villages post no bounties";
        Bounty x = find(data, s, id);
        if (x == null) return "That bounty is gone";
        if (x.taker != null) return x.taker.equals(p.getUUID()) ? "You already took it" : "Taken by " + x.takerName;
        if (data.taken(p.getUUID()).size() >= MAX_TAKEN) return "You already have " + MAX_TAKEN + " bounties; finish or abandon one";
        if (!p.getUUID().equals(s.owner) && m.state.war(p.getUUID(), s.kingdomId()) != null) return s.name + " is at war with you";
        x.taker = p.getUUID();
        x.takerName = p.getGameProfile().getName();
        x.expires = Math.max(x.expires, now + day());
        s.dirty = true;
        return null;
    }

    @Nullable
    public static String abandon(QuestData data, Settlement s, ServerPlayer p, UUID id) {
        Bounty x = find(data, s, id);
        if (x == null || !p.getUUID().equals(x.taker)) return "Not your bounty";
        x.taker = null;
        x.takerName = "";
        x.progress = 0;
        x.visited = 0;
        s.dirty = true;
        return null;
    }

    /** Hands over matching items (plain stacks only, as the stockpile keeps no NBT) from the player's inventory. */
    @Nullable
    public static String deliver(QuestData data, SettlementManager m, Settlement s, ServerPlayer p, UUID id, long now) {
        Bounty x = find(data, s, id);
        if (x == null || x.kind != Kind.DELIVER || !p.getUUID().equals(x.taker)) return "Not your delivery";
        int want = x.need - x.progress, given = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && given < want; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || st.getItem() != x.item || st.hasTag()) continue;
            int n = Math.min(st.getCount(), want - given);
            st.shrink(n);
            given += n;
        }
        if (given == 0) return "You carry no " + x.item.getDescription().getString();
        s.stock.force(x.item, given);
        x.progress += given;
        s.dirty = true;
        if (x.done()) complete(data, m, s, x, p, now);
        else p.displayClientMessage(net.minecraft.network.chat.Component.literal("Delivered " + given + " · " + x.progress + "/" + x.need), true);
        return null;
    }

    static void complete(QuestData data, SettlementManager m, Settlement s, Bounty x, ServerPlayer p, long now) {
        Board b = data.board(s.id);
        if (!b.bounties.remove(x)) return;
        b.nextPost = Math.max(b.nextPost, now + gap());
        Quests.give(p, new ItemStack(Items.EMERALD, x.reward));
        data.of(p.getUUID()).bump(QuestData.BOUNTIES, 1);
        s.log(p.getGameProfile().getName() + " completed a bounty: " + title(x));
        s.dirty = true;
        Quests.notify(p, "Bounty complete", title(x) + " · " + x.reward + " emeralds", Items.EMERALD, false);
    }

    // ------------------------------------------------------------------ progress from the world

    /** A hostile mob died at pos, killed by this player or one of their soldiers. */
    public static void slain(QuestData data, SettlementManager m, ServerPlayer p, ResourceKey<Level> dim, BlockPos pos, long now) {
        for (var e : data.taken(p.getUUID())) {
            Bounty x = e.getValue();
            if (x.kind != Kind.SLAY) continue;
            Settlement s = m.get(e.getKey());
            if (s == null || !s.dim.equals(dim)) continue;
            long r = s.radius() + SLAY_MARGIN, dx = pos.getX() - s.center.getX(), dz = pos.getZ() - s.center.getZ();
            if (dx * dx + dz * dz > r * r) continue;
            x.progress++;
            s.dirty = true;
            if (x.done()) complete(data, m, s, x, p, now);
            return;
        }
    }

    /** Marks patrol posts the player stands near. */
    public static void patrol(QuestData data, SettlementManager m, ServerPlayer p, long now) {
        for (var e : data.taken(p.getUUID())) {
            Bounty x = e.getValue();
            if (x.kind != Kind.PATROL) continue;
            Settlement s = m.get(e.getKey());
            if (s == null || !s.dim.equals(p.level().dimension())) continue;
            for (int i = 0; i < x.points.size(); i++) {
                if ((x.visited & 1 << i) != 0) continue;
                BlockPos at = x.points.get(i);
                double dx = p.getX() - (at.getX() + .5), dz = p.getZ() - (at.getZ() + .5);
                if (dx * dx + dz * dz > PATROL_REACH * PATROL_REACH) continue;
                x.visited |= 1 << i;
                x.progress = Integer.bitCount(x.visited);
                s.dirty = true;
                if (x.done()) { complete(data, m, s, x, p, now); break; }
                p.displayClientMessage(net.minecraft.network.chat.Component.literal("Patrol post " + x.progress + "/" + x.need + " · " + s.name), true);
            }
        }
    }

    private Bounties() {}
}
