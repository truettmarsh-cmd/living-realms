package livingrealms.quest;

import livingrealms.building.Category;
import livingrealms.net.Net;
import livingrealms.quest.QuestBook.Chapter;
import livingrealms.quest.QuestBook.Check;
import livingrealms.quest.QuestBook.Reward;
import livingrealms.quest.QuestBook.Step;
import livingrealms.quest.QuestData.Bounty;
import livingrealms.quest.QuestData.Progress;
import livingrealms.settlement.*;
import livingrealms.settlement.plan.Walls;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;

import javax.annotation.Nullable;
import java.util.*;

/**
 * The quest line from a first charter to a crown. Steps are checked against the player's real kingdom (polled every
 * couple of seconds) and a few counters fed by existing events (kills, raids). Only the current chapter's steps can
 * complete; each step's reward is granted exactly once, when it is recorded as done. See docs/quests.md.
 */
public final class Quests {
    public static final Set<String> CHECKS = Set.of("cities", "tier", "building", "category", "buildings", "soldiers", "research",
            "research_count", "walls", "kills", "battles", "raids_led", "cities_taken", "peace", "royal_guard", "castle", "crown", "bounties");

    // ------------------------------------------------------------------ state

    /** Index of the first chapter with an unfinished step; the chapter count when the line is finished. */
    public static int current(Progress pr) {
        List<Chapter> line = QuestBook.chapters();
        for (int i = 0; i < line.size(); i++) if (!finished(pr, line.get(i))) return i;
        return line.size();
    }

    static boolean finished(Progress pr, Chapter c) {
        for (Step s : c.steps()) if (!pr.done.contains(s.id())) return false;
        return true;
    }

    static List<Settlement> realm(SettlementManager m, UUID player) {
        List<Settlement> out = new ArrayList<>();
        for (Settlement s : m.ownedBy(player)) if (!s.mirror()) out.add(s);
        return out;
    }

    /** The kingdom's seat (its capital), where town rewards go. */
    @Nullable
    static Settlement seat(List<Settlement> realm) {
        for (Settlement s : realm) if (s.capital) return s;
        return realm.isEmpty() ? null : realm.get(0);
    }

    /** How far the player is with a check: {have, need}. */
    public static int[] progress(SettlementManager m, Progress pr, UUID player, Check c) {
        List<Settlement> realm = realm(m, player);
        int need = c.count(), have = 0;
        switch (c.type()) {
            case "cities" -> have = realm.size();
            case "tier" -> { for (Settlement s : realm) have = Math.max(have, s.tier.number()); }
            case "building" -> { for (Settlement s : realm) for (Plot p : s.plots) if (p.typeId.equals(c.id()) && p.standing()) have++; }
            case "category" -> {
                Category cat = category(c.id());
                if (cat != null) for (Settlement s : realm) have += s.standing(cat);
            }
            case "buildings" -> { for (Settlement s : realm) have = Math.max(have, s.d.buildings); }
            case "soldiers" -> { for (Settlement s : realm) have += s.soldiers.size(); }
            case "research" -> { need = 1; for (Settlement s : realm) if (s.research.contains(c.id())) have = 1; }
            case "research_count" -> { for (Settlement s : realm) have = Math.max(have, s.research.size()); }
            case "walls" -> {
                for (Settlement s : realm) for (Plot p : s.plots)
                    if (p.standing() && (p.typeId.equals(Walls.WALL) || p.typeId.equals(Walls.TOWER) || p.typeId.equals(Walls.GATE))) have++;
            }
            case "kills" -> have = pr.stat(QuestData.KILLS);
            case "battles" -> have = pr.stat(QuestData.DEFENDED) + pr.stat(QuestData.TAKEN);
            case "raids_led" -> have = pr.stat(QuestData.LED);
            case "cities_taken" -> have = pr.stat(QuestData.TAKEN);
            case "bounties" -> have = pr.stat(QuestData.BOUNTIES);
            case "peace" -> { need = 1; have = peace(m, player) ? 1 : 0; }
            case "royal_guard" -> { for (Settlement s : realm) for (var so : s.soldiers) if (so.royalGuard) have++; }
            case "castle" -> { need = 1; for (Settlement s : realm) for (Plot p : s.plots) if (p.typeId.equals(CapitalCastles.ID) && p.state == Plot.State.BUILT) have = 1; }
            case "crown" -> { need = 1; have = pr.stat(QuestData.CAPITALS) > 0 || realm.size() >= c.count() ? 1 : 0; }
            default -> have = 0;
        }
        return new int[]{Math.min(have, need), need};
    }

    @Nullable
    static Category category(String id) {
        try { return Category.valueOf(id.toUpperCase(Locale.ROOT)); } catch (Exception e) { return null; }
    }

    /** A truce or tribute with another kingdom, or (Village Expansion) a treaty with a faction. */
    static boolean peace(SettlementManager m, UUID player) {
        String k = player.toString();
        if (m.state.truces.keySet().stream().anyMatch(key -> key.contains(k))) return true;
        if (m.state.tributes.stream().anyMatch(t -> player.equals(t.payer) || player.equals(t.payee))) return true;
        String faction = m.realms.get(player);
        return faction != null && m.truces.keySet().stream().anyMatch(key -> key.contains(faction));
    }

    static boolean met(SettlementManager m, Progress pr, UUID player, Check c) {
        int[] h = progress(m, pr, player, c);
        return h[0] >= h[1];
    }

    // ------------------------------------------------------------------ the poll

    /** Completes every step of the current chapter that is now met, moving on chapter by chapter. Returns the steps completed. */
    public static List<Step> poll(SettlementManager m, QuestData data, ServerPlayer p) {
        Progress pr = data.of(p.getUUID());
        List<Step> out = new ArrayList<>();
        List<Chapter> line = QuestBook.chapters();
        if (line.isEmpty()) return out;
        for (int guard = 0; guard < line.size(); guard++) {
            int ci = current(pr);
            if (ci >= line.size()) break;
            Chapter c = line.get(ci);
            for (Step s : c.steps()) {
                if (pr.done.contains(s.id()) || !met(m, pr, p.getUUID(), s.check())) continue;
                pr.done.add(s.id());
                grant(m, p, s.reward());
                out.add(s);
                notify(p, s.title(), s.reward().empty() ? s.objective() : "+" + s.reward().text(), c.icon(), false);
            }
            if (!finished(pr, c)) break;
            if (pr.chapters.add(c.id())) {
                boolean last = ci == line.size() - 1;
                Settlement seat = seat(realm(m, p.getUUID()));
                if (seat != null) livingrealms.chronicle.Chronicle.record(seat, livingrealms.chronicle.Chronicle.Kind.QUEST, last
                        ? p.getName().getString() + " was crowned, and the realm rang its bells for three days."
                        : p.getName().getString() + " saw the realm through " + c.title() + ".");
                notify(p, last ? "Crowned!" : "Chapter complete", last ? "Long live the crown." : c.title(), last ? Items.GOLDEN_HELMET : c.icon(), true);
            }
        }
        if (!out.isEmpty()) for (Settlement s : realm(m, p.getUUID())) s.dirty = true;
        return out;
    }

    /** Emeralds and items to the player; stockpile items, research and merit to the kingdom (stock and research at its seat). */
    static void grant(SettlementManager m, ServerPlayer p, Reward r) {
        if (r.emeralds() > 0) give(p, new ItemStack(Items.EMERALD, r.emeralds()));
        r.items().forEach((it, n) -> give(p, new ItemStack(it, n)));
        List<Settlement> realm = realm(m, p.getUUID());
        Settlement seat = seat(realm);
        if (seat != null) {
            r.stock().forEach(seat.stock::force);
            if (r.research() > 0) seat.researchBank = Math.min(2000, seat.researchBank + r.research());
        }
        if (r.merit() > 0) {
            long now = p.server.overworld().getGameTime();
            for (Settlement s : realm) for (var so : s.soldiers) Merit.add(so.merit, r.merit(), now);
        }
    }

    /** Into the inventory, the rest at the player's feet. */
    static void give(ServerPlayer p, ItemStack stack) {
        Item it = stack.getItem();
        int n = stack.getCount(), max = Math.max(1, it.getMaxStackSize());
        while (n > 0) {
            ItemStack part = new ItemStack(it, Math.min(n, max));
            n -= part.getCount();
            if (!p.getInventory().add(part) && !part.isEmpty()) p.drop(part, false);
        }
    }

    /** Chat line, plus a toast and sound on the client. */
    static void notify(ServerPlayer p, String title, String text, Item icon, boolean big) {
        p.sendSystemMessage(Component.literal("[" + (big ? "Chapter" : "Quest") + "] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(title).withStyle(big ? ChatFormatting.YELLOW : ChatFormatting.WHITE))
                .append(Component.literal(text.isEmpty() ? "" : " · " + text).withStyle(ChatFormatting.GRAY)));
        if (p instanceof FakePlayer || p.connection == null) return;
        CompoundTag t = new CompoundTag();
        t.putString("title", title);
        t.putString("text", text);
        t.putString("icon", BuiltInRegistries.ITEM.getKey(icon).toString());
        t.putBoolean("big", big);
        Net.send(p, "quest", t);
    }

    // ------------------------------------------------------------------ event hooks

    /** A hostile mob killed by the player or one of their soldiers. */
    public static void killed(QuestData data, SettlementManager m, UUID player, @Nullable ServerPlayer online,
                              net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim, net.minecraft.core.BlockPos pos, long now) {
        data.of(player).bump(QuestData.KILLS, 1);
        if (online != null) Bounties.slain(data, m, online, dim, pos, now);
    }

    static boolean isPlayer(SettlementManager m, UUID kingdom) {
        for (Settlement s : m.all()) if (kingdom.equals(s.owner)) return true;
        return false;
    }

    /** Raids.finish: the defender held (not captured) or the attacker led a raid. */
    public static void raidEnded(MinecraftServer server, SettlementManager m, Raids.Raid raid, boolean captured) {
        raidEnded(QuestData.get(server), m, raid.attacker, raid.defender, captured);
    }

    public static void raidEnded(QuestData data, SettlementManager m, UUID attacker, UUID defender, boolean captured) {
        if (isPlayer(m, attacker)) data.of(attacker).bump(QuestData.LED, 1);
        if (!captured && isPlayer(m, defender)) data.of(defender).bump(QuestData.DEFENDED, 1);
    }

    /** Raids: a city fell to the attacking kingdom (before its owner changes, so a capital is still known as one). */
    public static void cityTaken(MinecraftServer server, SettlementManager m, UUID attacker, boolean capital) {
        cityTaken(QuestData.get(server), m, attacker, capital);
    }

    public static void cityTaken(QuestData data, SettlementManager m, UUID attacker, boolean capital) {
        if (!isPlayer(m, attacker)) return;
        data.of(attacker).bump(QuestData.TAKEN, 1);
        if (capital) data.of(attacker).bump(QuestData.CAPITALS, 1);
    }

    // ------------------------------------------------------------------ City Manager

    /** The Quests page: this player's chapter and steps, and this town's bounty board. */
    public static CompoundTag view(ServerPlayer p, SettlementManager m, Settlement s) {
        QuestData data = QuestData.get(p.server);
        long now = p.server.overworld().getGameTime();
        Bounties.refresh(data, m, s, now, p.getRandom(), p.server);
        return view(data, m, s, p, now);
    }

    public static CompoundTag view(QuestData data, SettlementManager m, Settlement s, ServerPlayer p, long now) {
        CompoundTag t = new CompoundTag();
        Progress pr = data.of(p.getUUID());
        List<Chapter> line = QuestBook.chapters();
        int ci = current(pr);
        t.putInt("chapter", ci);
        t.putInt("chapters", line.size());
        ListTag titles = new ListTag();
        for (Chapter c : line) {
            CompoundTag ct = new CompoundTag();
            ct.putString("title", c.title());
            ct.putString("icon", BuiltInRegistries.ITEM.getKey(c.icon()).toString());
            titles.add(ct);
        }
        t.put("titles", titles);
        int done = 0;
        for (Chapter c : line) for (Step st : c.steps()) if (pr.done.contains(st.id())) done++;
        t.putInt("stepsDone", done);
        t.putInt("stepsAll", QuestBook.steps());
        if (ci < line.size()) {
            Chapter c = line.get(ci);
            t.putString("title", c.title());
            t.putString("intro", c.intro());
            ListTag steps = new ListTag();
            for (Step st : c.steps()) {
                CompoundTag x = new CompoundTag();
                int[] h = progress(m, pr, p.getUUID(), st.check());
                boolean ok = pr.done.contains(st.id());
                x.putString("title", st.title());
                x.putString("flavour", st.flavour());
                x.putString("objective", st.objective());
                x.putString("reward", st.reward().text());
                x.putInt("have", ok ? h[1] : h[0]);
                x.putInt("need", h[1]);
                x.putBoolean("done", ok);
                steps.add(x);
            }
            t.put("steps", steps);
        }
        // bounty board
        t.putBoolean("offers", Bounties.offers(s));
        t.putInt("taken", data.taken(p.getUUID()).size());
        t.putInt("maxTaken", Bounties.MAX_TAKEN);
        t.putInt("completed", pr.stat(QuestData.BOUNTIES));
        ListTag board = new ListTag();
        long day = Math.max(1, Bounties.day());
        for (Bounty x : data.board(s.id).bounties) board.add(bounty(m, x, s, p, now, day));
        t.put("board", board);
        // bounties taken in other towns
        ListTag elsewhere = new ListTag();
        for (var e : data.taken(p.getUUID())) {
            if (e.getKey().equals(s.id)) continue;
            Settlement town = m.get(e.getKey());
            if (town == null) continue;
            CompoundTag b = bounty(m, e.getValue(), town, p, now, day);
            b.putString("town", town.name);
            elsewhere.add(b);
        }
        t.put("elsewhere", elsewhere);
        t.putLong("nextPostIn", Math.max(0, data.board(s.id).nextPost - now) / 1200);
        return t;
    }

    static CompoundTag bounty(SettlementManager m, Bounty x, Settlement s, ServerPlayer p, long now, long day) {
        CompoundTag b = new CompoundTag();
        b.putUUID("id", x.id);
        b.putString("kind", x.kind.name());
        b.putString("title", Bounties.title(x));
        b.putString("detail", Bounties.detail(x, s));
        b.putString("item", BuiltInRegistries.ITEM.getKey(x.kind == QuestData.Kind.DELIVER ? x.item
                : x.kind == QuestData.Kind.SLAY ? Items.IRON_SWORD : x.kind == QuestData.Kind.ESCORT ? Items.LEAD : Items.COMPASS).toString());
        b.putInt("have", x.progress);
        b.putInt("need", x.need);
        b.putInt("reward", x.reward);
        b.putLong("left", Math.max(0, x.expires - now));
        b.putLong("day", day);
        b.putBoolean("mine", p.getUUID().equals(x.taker));
        b.putString("takerName", x.taker == null ? "" : x.takerName);
        if (x.kind == QuestData.Kind.DELIVER) {
            int carried = 0;
            for (ItemStack st : p.getInventory().items) if (st.getItem() == x.item && !st.hasTag()) carried += st.getCount();
            b.putInt("carried", carried);
        }
        if (x.kind == QuestData.Kind.ESCORT) {
            Trade.Caravan c = m.trade.caravan(x.caravan);
            if (c != null) b.putString("next", c.status == Trade.Status.LOADING ? "Loading at the market, sets out in "
                    + Math.max(1, (Caravans.LOAD_TICKS - (now - c.since)) / 20) + " s"
                    : "On the road · with it " + (x.road == 0 ? 0 : x.near * 100 / x.road) + "% of the time (" + (int) (Bounties.ESCORT_SHARE * 100) + "% needed)");
        }
        if (x.kind == QuestData.Kind.PATROL) {
            b.putLongArray("posts", x.points.stream().mapToLong(BlockPos::asLong).toArray());
            b.putInt("visited", x.visited);
            for (int i = 0; i < x.points.size(); i++) if ((x.visited & 1 << i) == 0) {
                var at = x.points.get(i);
                b.putString("next", "Next post " + at.getX() + ", " + at.getZ() + " (" + Walls.side(s, at) + ")");
                break;
            }
        }
        return b;
    }

    /** City Manager actions questAccept / questAbandon / questDeliver. */
    @Nullable
    public static String handle(ServerPlayer p, SettlementManager m, Settlement s, String action, CompoundTag d) {
        if (!d.hasUUID("bounty")) return "No bounty chosen";
        QuestData data = QuestData.get(p.server);
        long now = p.server.overworld().getGameTime();
        UUID id = d.getUUID("bounty");
        // abandoning and delivering work from any town's page, for bounties taken elsewhere
        Settlement town = s;
        for (var e : data.taken(p.getUUID())) if (e.getValue().id.equals(id)) town = m.get(e.getKey());
        if (town == null) return "That town is gone";
        return switch (action) {
            case "questAccept" -> Bounties.accept(data, m, s, p, id, now);
            case "questAbandon" -> Bounties.abandon(data, town, p, id);
            case "questDeliver" -> Bounties.deliver(data, m, town, p, id, now);
            default -> "Unknown quest action";
        };
    }

    private Quests() {}
}
