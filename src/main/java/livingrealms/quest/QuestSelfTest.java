package livingrealms.quest;

import com.mojang.authlib.GameProfile;
import livingrealms.LivingRealms;
import livingrealms.building.BuildingCatalog;
import livingrealms.military.UnitType;
import livingrealms.quest.QuestBook.Chapter;
import livingrealms.quest.QuestBook.Step;
import livingrealms.quest.QuestData.Bounty;
import livingrealms.quest.QuestData.Kind;
import livingrealms.settlement.*;
import livingrealms.settlement.plan.Walls;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** {@code ./gradlew runServer -PquestTest -PquestTestDir=<fresh dir>}: the quest line and bounties, written to quest-test-result.txt. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class QuestSelfTest {
    private static int checks;

    private static void check(boolean value, String detail) {
        if (!value) throw new IllegalStateException(detail);
        checks++;
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.questTest")) return;
        String report;
        try {
            run(event.getServer().overworld());
            report = "PASS: " + checks + " quest assertions";
            LivingRealms.LOG.info("QUEST SELF TEST {}", report);
        } catch (Throwable error) {
            report = "FAIL after " + checks + " assertions: " + error;
            LivingRealms.LOG.error("QUEST SELF TEST FAILED", error);
        }
        try { Files.writeString(Path.of("quest-test-result.txt"), report); } catch (Exception e) { throw new RuntimeException(e); }
        event.getServer().halt(false);
    }

    static int count(ServerPlayer p, Item item) {
        int n = 0;
        for (ItemStack st : p.getInventory().items) if (st.getItem() == item) n += st.getCount();
        return n;
    }

    static Plot plot(Settlement s, String type, Plot.State state) {
        Plot p = new Plot();
        p.id = s.nextId++;
        p.typeId = type;
        var t = BuildingCatalog.get(type);
        p.blueprintKey = t == null ? "" : t.blueprintKey(1, s.style);
        p.origin = s.center.offset(20 * p.id, 0, 0);
        p.state = state;
        s.plots.add(p);
        return p;
    }

    static void soldiers(Settlement s, int n) {
        for (int i = 0; i < n; i++) {
            var so = new Settlement.Soldier();
            so.entity = UUID.randomUUID(); so.type = UnitType.FOOTMAN; so.name = "Guard " + s.soldiers.size();
            s.soldiers.add(so);
        }
    }

    static Settlement town(SettlementManager m, ServerPlayer owner, String name, int x) {
        Settlement s = new Settlement();
        s.owner = owner == null ? null : owner.getUUID();
        s.ownerName = owner == null ? "" : owner.getGameProfile().getName();
        s.name = name;
        s.center = new BlockPos(x, 100, 4096);
        m.add(s);
        return s;
    }

    static List<String> ids(List<Step> steps) { return steps.stream().map(Step::id).toList(); }

    static int emeraldRewards(List<Chapter> line) {
        int n = 0;
        for (Chapter c : line) for (Step s : c.steps()) n += s.reward().emeralds();
        return n;
    }

    private static void run(ServerLevel level) {
        // ---- the line as shipped
        List<Chapter> line = QuestBook.chapters();
        check(line.size() == 5, "Five chapters loaded from data/livingrealms/livingrealms_quests, got " + line.size());
        check(QuestBook.steps() >= 20, "At least 20 steps, got " + QuestBook.steps());
        for (Chapter c : line) {
            check(c.steps().size() >= 3 && c.steps().size() <= 6, "Chapter " + c.id() + " has 3-6 steps");
            for (Step s : c.steps()) {
                check(Quests.CHECKS.contains(s.check().type()), "Step " + s.id() + " uses a known check");
                check(!s.title().isEmpty() && !s.objective().isEmpty() && !s.flavour().isEmpty(), "Step " + s.id() + " has title, objective and flavour");
                check(!s.reward().empty(), "Step " + s.id() + " has a reward");
                check(s.reward().emeralds() <= 48, "Step " + s.id() + " reward stays modest");
            }
        }

        var server = level.getServer();
        ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "QuestTester"));
        p.getInventory().clearContent();
        UUID me = p.getUUID();
        SettlementManager m = new SettlementManager();
        QuestData data = new QuestData();
        RandomSource rnd = RandomSource.create(42);
        List<String> order = new ArrayList<>();

        // ---- nothing before a charter
        check(Quests.poll(m, data, p).isEmpty() && Quests.current(data.of(me)) == 0, "No step completes without a settlement");

        // ---- chapter 1
        Settlement home = town(m, p, "Questhold", 0);
        home.research.add("masonry"); // a later chapter's goal, met early
        var first = Quests.poll(m, data, p);
        order.addAll(ids(first));
        check(ids(first).equals(List.of("hearth/found")), "Founding completes only the first step: " + ids(first));
        check(count(p, Items.EMERALD) == 4 && count(p, Items.BREAD) == 8, "Founding reward granted: 4 emeralds, 8 bread");
        check(Quests.poll(m, data, p).isEmpty() && count(p, Items.EMERALD) == 4, "A reward is granted once, not on every poll");
        check(!data.of(me).done.contains("stone/masonry"), "A later chapter's step waits for its chapter");

        plot(home, "livingrealms:town_hall", Plot.State.BUILT);
        for (int i = 0; i < 3; i++) plot(home, "livingrealms:cottage", Plot.State.BUILT);
        Simulation.recompute(home);
        check(home.d.buildings == 4, "Four standing buildings counted, got " + home.d.buildings);
        int cobble = home.stock.count(Items.COBBLESTONE);
        var second = Quests.poll(m, data, p);
        order.addAll(ids(second));
        check(new HashSet<>(ids(second)).equals(Set.of("hearth/hall", "hearth/roofs")), "Hall and buildings steps complete: " + ids(second));
        check(home.stock.count(Items.COBBLESTONE) == cobble + 32 && home.stock.count(Items.OAK_LOG) == 32, "Stockpile rewards reach the seat");

        // view mid-chapter
        CompoundTag v = Quests.view(data, m, home, p, 0);
        check(v.getInt("chapter") == 0 && v.getList("steps", Tag.TAG_COMPOUND).size() == 5, "View shows chapter 1 with 5 steps");
        CompoundTag swords = v.getList("steps", Tag.TAG_COMPOUND).getCompound(3);
        check(swords.getInt("have") == 0 && swords.getInt("need") == 3 && !swords.getBoolean("done"), "Soldier step shows 0/3");

        soldiers(home, 2);
        check(Quests.poll(m, data, p).isEmpty(), "Two soldiers are not three");
        check(Quests.view(data, m, home, p, 0).getList("steps", Tag.TAG_COMPOUND).getCompound(3).getInt("have") == 2, "Progress shows 2/3");
        soldiers(home, 1);
        home.tier = Tier.HAMLET;
        var third = Quests.poll(m, data, p);
        order.addAll(ids(third));
        check(ids(third).containsAll(List.of("hearth/swords", "hearth/hamlet", "stone/masonry")), "Chapter 1 finishes and chapter 2 opens in order: " + ids(third));
        check(ids(third).indexOf("hearth/hamlet") < ids(third).indexOf("stone/masonry"), "Chapter 1 steps complete before chapter 2's");
        check(home.researchBank == 30, "Research reward banked: " + home.researchBank);
        check(Quests.current(data.of(me)) == 1 && data.of(me).chapters.contains("hearth"), "Chapter 2 is current");

        // ---- bounties
        long now = 1000, day = Bounties.day();
        Plot cottage = plot(home, "livingrealms:cottage", Plot.State.PLANNED);
        var bill = cottage.blueprint().bill();
        check(!bill.isEmpty(), "A planned cottage has a bill of materials");
        Item short_ = null;
        for (var e : bill.entrySet()) if (e.getValue() >= 8 && e.getKey() != Items.DIRT) { short_ = e.getKey(); break; }
        check(short_ != null, "The cottage needs some material in quantity");
        home.stock.takeUpTo(short_, 100000);
        cottage.status = "Waiting for " + short_.getDescription().getString();
        Bounties.refresh(data, m, home, now, rnd, null);
        List<Bounty> board = data.board(home.id).bounties;
        check(board.size() == Bounties.BOARD, "A town posts " + Bounties.BOARD + " bounties, got " + board.size());
        Bounty deliver = board.stream().filter(b -> b.kind == Kind.DELIVER).findFirst().orElse(null);
        Bounty slay = board.stream().filter(b -> b.kind == Kind.SLAY).findFirst().orElse(null);
        Bounty patrol = board.stream().filter(b -> b.kind == Kind.PATROL).findFirst().orElse(null);
        check(deliver != null && slay != null && patrol != null, "One delivery, one hunt, one patrol");
        check(deliver.item == short_, "The delivery asks for what the builders wait for: " + deliver.item + " vs " + short_);
        check(deliver.need >= 16 && deliver.need % 8 == 0, "Delivery amount is sensible: " + deliver.need);
        check(patrol.points.size() == 4 && patrol.need == 4, "Patrol has four posts");
        check(deliver.expires == now + Bounties.lifetime(), "Bounties expire after " + Bounties.lifetime() + " ticks");

        // rewards scale with rank
        Settlement camp = town(m, null, "Campton", 3000), grand = town(m, null, "Grandton", 6000);
        grand.tier = Tier.CITY;
        Bounties.refresh(data, m, camp, now, rnd, null);
        Bounties.refresh(data, m, grand, now, rnd, null);
        int campSlay = data.board(camp.id).bounties.stream().filter(b -> b.kind == Kind.SLAY).findFirst().orElseThrow().reward;
        int grandSlay = data.board(grand.id).bounties.stream().filter(b -> b.kind == Kind.SLAY).findFirst().orElseThrow().reward;
        check(grandSlay > campSlay, "A City pays more than a Camp: " + grandSlay + " vs " + campSlay);
        Settlement hungry = town(m, null, "Hungerford", 9000);
        hungry.population = 12;
        Bounties.refresh(data, m, hungry, now, rnd, null);
        check(data.board(hungry.id).bounties.stream().anyMatch(b -> b.kind == Kind.DELIVER && b.item == Items.BREAD), "A hungry town asks for bread");

        // deliver
        check(Bounties.deliver(data, m, home, p, deliver.id, now) != null, "Cannot deliver before accepting");
        check(Bounties.accept(data, m, home, p, deliver.id, now) == null, "Accept the delivery");
        check(deliver.taker.equals(me) && deliver.expires >= now + day, "Taken, with at least a day left");
        check(Bounties.accept(data, m, home, p, deliver.id, now) != null, "Cannot take it twice");
        check(Bounties.deliver(data, m, home, p, deliver.id, now) != null, "Delivering with empty pockets is refused");
        int half = deliver.need / 2;
        Quests.give(p, new ItemStack(short_, half));
        check(Bounties.deliver(data, m, home, p, deliver.id, now) == null && deliver.progress == half && count(p, short_) == 0, "Partial delivery counts");
        int emeralds = count(p, Items.EMERALD), stocked = home.stock.count(short_);
        Quests.give(p, new ItemStack(short_, deliver.need));
        check(Bounties.deliver(data, m, home, p, deliver.id, now) == null, "Finish the delivery");
        check(count(p, short_) == half && home.stock.count(short_) == stocked + deliver.need - half, "Only what was owed is taken, into the stockpile");
        check(count(p, Items.EMERALD) == emeralds + deliver.reward && !board.contains(deliver), "Delivery paid and removed from the board");
        check(Bounties.deliver(data, m, home, p, deliver.id, now) != null && count(p, Items.EMERALD) == emeralds + deliver.reward, "A finished bounty pays once");
        check(data.of(me).stat(QuestData.BOUNTIES) == 1, "Bounty counted for the quest line");

        // slay
        check(Bounties.accept(data, m, home, p, slay.id, now) == null, "Accept the hunt");
        Bounties.slain(data, m, p, level.dimension(), home.center.offset(home.radius() + Bounties.SLAY_MARGIN + 50, 0, 0), now);
        check(slay.progress == 0, "Kills far from town do not count");
        Bounties.slain(data, m, p, net.minecraft.world.level.Level.NETHER, home.center, now);
        check(slay.progress == 0, "Kills in another dimension do not count");
        emeralds = count(p, Items.EMERALD);
        for (int i = 0; i < slay.need; i++) Quests.killed(data, m, me, p, level.dimension(), home.center.offset(5, 0, 5), now);
        check(!board.contains(slay) && count(p, Items.EMERALD) == emeralds + slay.reward, "Hunt complete and paid");
        check(data.of(me).stat(QuestData.KILLS) == slay.need, "Kills counted for the quest line");

        // patrol
        check(Bounties.accept(data, m, home, p, patrol.id, now) == null, "Accept the patrol");
        emeralds = count(p, Items.EMERALD);
        BlockPos far = home.center.offset(500, 0, 500);
        p.setPos(far.getX(), far.getY(), far.getZ());
        Bounties.patrol(data, m, p, now);
        check(patrol.progress == 0, "Standing far away visits no post");
        for (int i = 0; i < 3; i++) {
            BlockPos at = patrol.points.get(i);
            p.setPos(at.getX() + 3, at.getY(), at.getZ() - 2);
            Bounties.patrol(data, m, p, now);
            Bounties.patrol(data, m, p, now);
        }
        check(patrol.progress == 3 && board.contains(patrol), "Three of four posts visited, each counted once");
        CompoundTag pv = Quests.view(data, m, home, p, now).getList("board", Tag.TAG_COMPOUND).getCompound(0);
        check(pv.getString("next").contains(String.valueOf(patrol.points.get(3).getX())), "The view names the next post");
        BlockPos last = patrol.points.get(3);
        p.setPos(last.getX(), last.getY(), last.getZ());
        Bounties.patrol(data, m, p, now);
        check(!board.contains(patrol) && count(p, Items.EMERALD) == emeralds + patrol.reward, "Patrol complete and paid");
        check(data.of(me).stat(QuestData.BOUNTIES) == 3, "Three bounties completed");

        // the board refills after a gap, then old bounties expire
        Bounties.refresh(data, m, home, now + 1, rnd, null);
        check(board.isEmpty(), "No new bounty before the posting gap");
        long later = now + Bounties.gap();
        Bounties.refresh(data, m, home, later, rnd, null);
        check(board.size() == Bounties.BOARD, "The board refills after the gap");
        Bounty kept = board.get(0), left = board.get(1);
        check(Bounties.accept(data, m, home, p, kept.id, later + Bounties.lifetime() - 10) == null, "Accept a bounty late in its life");
        check(kept.expires >= later + Bounties.lifetime() - 10 + day, "Taking it late extends it to a day");
        check(Bounties.abandon(data, home, p, left.id) != null, "Cannot abandon a bounty you did not take");
        Bounties.refresh(data, m, home, later + Bounties.lifetime(), rnd, null);
        check(!board.contains(left) && board.contains(kept), "Untaken bounties expire; the taken one has its extra day");
        Bounties.refresh(data, m, home, kept.expires, rnd, null);
        check(!board.contains(kept) && data.taken(me).isEmpty(), "A taken bounty expires too");

        // limit on bounties taken at once
        long t2 = later + Bounties.lifetime() + day;
        for (long at : new long[]{t2, t2 + Bounties.gap()}) { Bounties.refresh(data, m, camp, at, rnd, null); Bounties.refresh(data, m, grand, at, rnd, null); }
        List<Bounty> pool = new ArrayList<>(data.board(camp.id).bounties);
        for (Bounty b : pool) check(Bounties.accept(data, m, camp, p, b.id, now) == null, "Accept up to " + Bounties.MAX_TAKEN);
        check(Bounties.accept(data, m, grand, p, data.board(grand.id).bounties.get(0).id, now) != null, "No more than " + Bounties.MAX_TAKEN + " at once");
        check(Bounties.abandon(data, camp, p, pool.get(0).id) == null && pool.get(0).taker == null, "Abandon returns it to the board");

        // ---- raid hooks: only player kingdoms keep progress
        UUID npcKingdom = camp.kingdomId();
        Quests.raidEnded(data, m, npcKingdom, me, false);
        Quests.raidEnded(data, m, me, npcKingdom, false);
        check(data.of(me).stat(QuestData.DEFENDED) == 1 && data.of(me).stat(QuestData.LED) == 1, "Raid held and raid led counted");
        check(!data.players.containsKey(npcKingdom), "NPC kingdoms get no quest record");
        Quests.raidEnded(data, m, npcKingdom, me, true);
        check(data.of(me).stat(QuestData.DEFENDED) == 1, "A lost defence is not a held one");

        // ---- save / load round trip mid-line
        CompoundTag saved = data.save(new CompoundTag());
        QuestData back = QuestData.load(saved);
        check(back.of(me).done.equals(data.of(me).done), "Completed steps and their order survive a reload");
        check(back.of(me).stats.equals(data.of(me).stats) && back.of(me).chapters.equals(data.of(me).chapters), "Counters and chapters survive a reload");
        check(back.board(camp.id).bounties.size() == data.board(camp.id).bounties.size(), "Bounty boards survive a reload");
        for (int i = 0; i < pool.size(); i++) {
            Bounty a = data.board(camp.id).bounties.get(i), b = back.board(camp.id).bounties.get(i);
            check(a.id.equals(b.id) && a.kind == b.kind && a.item == b.item && a.need == b.need && a.reward == b.reward
                    && a.expires == b.expires && Objects.equals(a.taker, b.taker) && a.points.equals(b.points), "Bounty " + i + " round-trips");
        }
        check(back.board(camp.id).nextPost == data.board(camp.id).nextPost, "Posting timer survives a reload");
        QuestData empty = QuestData.load(new CompoundTag());
        check(empty.players.isEmpty() && empty.boards.isEmpty(), "A world without quest data loads empty");
        CompoundTag broken = saved.copy();
        broken.getCompound("boards").getCompound(camp.id.toString()).getList("bounties", Tag.TAG_COMPOUND).getCompound(0).putString("kind", "NONSENSE");
        check(QuestData.load(broken).board(camp.id).bounties.size() == pool.size() - 1, "A damaged bounty is dropped, not fatal");
        data = back;

        // ---- chapter 2
        plot(home, "livingrealms:library", Plot.State.BUILT);
        home.research.addAll(List.of("scholarship", "agriculture"));
        home.tier = Tier.VILLAGE;
        order.addAll(ids(Quests.poll(m, data, p)));
        check(data.of(me).chapters.contains("stone"), "Chapter 2 complete");

        // ---- chapter 3: kills so far count; defended raid counts
        home.research.add("fortifications");
        for (int i = 0; i < 4; i++) plot(home, i == 0 ? Walls.GATE : i == 1 ? Walls.TOWER : Walls.WALL, Plot.State.BUILT);
        soldiers(home, 5);
        for (int i = 0; i < 20; i++) Quests.killed(data, m, me, null, level.dimension(), home.center, now);
        order.addAll(ids(Quests.poll(m, data, p)));
        check(!data.of(me).done.contains("walls/town") && data.of(me).done.contains("walls/gate"), "Chapter 3 waits for Town rank only");
        check(home.soldiers.get(0).merit.merit > 0, "Merit reward reached the soldiers");
        home.tier = Tier.TOWN;
        order.addAll(ids(Quests.poll(m, data, p)));
        check(data.of(me).chapters.contains("walls"), "Chapter 3 complete");

        // ---- chapter 4
        Settlement colony = town(m, p, "Newhold", 1500);
        check(!colony.capital && home.capital, "The first town stays the capital");
        String other = UUID.randomUUID().toString();
        m.state.truces.put(me.toString().compareTo(other) < 0 ? me + "|" + other : other + "|" + me, now + day);
        home.soldiers.get(0).royalGuard = true;
        order.addAll(ids(Quests.poll(m, data, p)));
        check(data.of(me).done.containsAll(List.of("realm/second", "realm/march", "realm/peace", "realm/guard")) && !data.of(me).done.contains("realm/city"), "Chapter 4 waits for City rank");
        home.tier = Tier.CITY;
        order.addAll(ids(Quests.poll(m, data, p)));

        // ---- chapter 5
        home.research.add("royal_charter");
        home.tier = Tier.CAPITAL;
        plot(home, CapitalCastles.ID, Plot.State.BUILT);
        town(m, p, "Thirdhold", -1500);
        order.addAll(ids(Quests.poll(m, data, p)));
        check(!data.of(me).done.contains("crown/crowned") && Quests.current(data.of(me)) == 4, "The crown waits for a rival capital or five cities");
        Quests.cityTaken(data, m, npcKingdom, true);
        check(!data.players.containsKey(npcKingdom), "An NPC taking a capital records nothing");
        Quests.cityTaken(data, m, me, true);
        int helmets = count(p, Items.GOLDEN_HELMET) + (p.getInventory().armor.get(3).is(Items.GOLDEN_HELMET) ? 1 : 0);
        order.addAll(ids(Quests.poll(m, data, p)));
        int helmetsAfter = count(p, Items.GOLDEN_HELMET) + (p.getInventory().armor.get(3).is(Items.GOLDEN_HELMET) ? 1 : 0);
        check(data.of(me).done.contains("crown/crowned") && helmetsAfter == helmets + 1, "Crowned, with the crown");
        check(Quests.current(data.of(me)) == line.size(), "The line is finished");
        check(Quests.poll(m, data, p).isEmpty() && Quests.view(data, m, home, p, now).getInt("chapter") == line.size(), "Nothing more completes; the view shows the end");

        // ---- order and once-only over the whole line
        List<String> all = new ArrayList<>();
        for (Chapter c : line) for (Step s : c.steps()) all.add(s.id());
        check(data.of(me).done.size() == all.size() && new HashSet<>(data.of(me).done).equals(new HashSet<>(all)), "Every step completed exactly once");
        check(order.equals(data.of(me).done), "Recorded order matches the completions seen");
        for (int i = 0; i < order.size(); i++) for (int j = i + 1; j < order.size(); j++) {
            int ci = chapterOf(line, order.get(i)), cj = chapterOf(line, order.get(j));
            if (ci > cj) throw new IllegalStateException("Step " + order.get(j) + " completed after a later chapter's " + order.get(i));
        }
        checks++;
        int bountyEmeralds = deliver.reward + slay.reward + patrol.reward;
        check(count(p, Items.EMERALD) == emeraldRewards(line) + bountyEmeralds, "Total emeralds = quest rewards + bounty rewards: "
                + count(p, Items.EMERALD) + " vs " + (emeraldRewards(line) + bountyEmeralds));
        CompoundTag end = QuestData.load(data.save(new CompoundTag())).save(new CompoundTag());
        check(QuestData.load(end).of(me).done.size() == all.size(), "The finished line survives a second reload");
        ListTag titles = Quests.view(data, m, home, p, now).getList("titles", Tag.TAG_COMPOUND);
        check(titles.size() == line.size(), "View lists every chapter");
        if (server == null) throw new IllegalStateException("no server");
    }

    static int chapterOf(List<Chapter> line, String id) {
        for (int i = 0; i < line.size(); i++) for (Step s : line.get(i).steps()) if (s.id().equals(id)) return i;
        return -1;
    }

    private QuestSelfTest() {}
}
