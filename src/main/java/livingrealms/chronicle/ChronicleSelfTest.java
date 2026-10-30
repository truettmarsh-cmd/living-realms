package livingrealms.chronicle;

import com.mojang.authlib.GameProfile;
import livingrealms.Config;
import livingrealms.LivingRealms;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.Construction;
import livingrealms.entity.SettlerEntity;
import livingrealms.fire.Fires;
import livingrealms.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** -PchronicleTest: the chronicle (records, cap, summaries, save round trip, written book) and a real town fire. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class ChronicleSelfTest {
    private static int checks;
    private static final List<String> notes = new ArrayList<>();
    private static void check(boolean value, String detail) {
        if (!value) throw new IllegalStateException(detail);
        checks++;
    }

    /** The live fire scenario, advanced by the server tick. */
    private static Settlement town;
    private static Plot cottage;
    private static ServerLevel level;
    private static long t0;
    private static int phase, plankX0, plankX1, trailZ, groundY, maxBurning;
    private static BlockPos bandFire;
    private static boolean running, escaped;

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.chronicleTest")) return;
        try {
            chronicle(event.getServer());
            fireSetup(event.getServer());
            running = true;
        } catch (Throwable error) {
            end(event.getServer(), error);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (!running || e.phase != TickEvent.Phase.END) return;
        try {
            if (fireTick(e.getServer())) end(e.getServer(), null);
        } catch (Throwable error) {
            end(e.getServer(), error);
        }
    }

    private static void end(MinecraftServer server, Throwable error) {
        running = false;
        String report = error == null ? "PASS: " + checks + " chronicle and fire assertions" + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")")
                : "FAIL after " + checks + " assertions: " + error;
        if (error == null) LivingRealms.LOG.info("CHRONICLE SELF TEST {}", report);
        else LivingRealms.LOG.error("CHRONICLE SELF TEST FAILED", error);
        try { Files.writeString(Path.of("chronicle-test-result.txt"), report); } catch (Exception ex) { throw new RuntimeException(ex); }
        server.halt(false);
    }

    static Chronicle.Entry last(Settlement s) { return s.chronicle.entries.get(s.chronicle.entries.size() - 1); }

    // ------------------------------------------------------------------ the chronicle

    static void chronicle(MinecraftServer server) {
        ServerLevel level = server.overworld();
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "Aldric"));
        SettlementManager m = new SettlementManager();
        Settlement s = new Settlement();
        s.owner = owner.getUUID(); s.ownerName = "Aldric"; s.name = "Testford"; s.center = new BlockPos(0, 100, 4096); s.founded = 0;
        s.population = 6;
        m.add(s);
        Simulation.recompute(s);
        Chronicle.tick(server, m, s, 1);
        check(s.chronicle.entries.size() == 1 && s.chronicle.entries.get(0).kind() == Chronicle.Kind.FOUNDING
                && s.chronicle.entries.get(0).text().contains("Testford") && s.chronicle.entries.get(0).text().contains("Aldric"), "Founding written on first look: " + s.chronicle.entries);
        Chronicle.tick(server, m, s, 1);
        check(s.chronicle.entries.size() == 1, "Nothing new, nothing written");
        s.tier = Tier.HAMLET;
        Chronicle.tick(server, m, s, 2);
        check(last(s).kind() == Chronicle.Kind.RANK && last(s).text().contains("Hamlet") && last(s).day() == 2, "Rank-up chronicled: " + last(s));
        Plot hall = plot(s, "livingrealms:town_hall", new BlockPos(0, 100, 4096));
        Chronicle.tick(server, m, s, 3);
        check(last(s).kind() == Chronicle.Kind.BUILDING && last(s).text().contains("Town Hall"), "The town hall is notable: " + last(s));
        int before = s.chronicle.entries.size();
        plot(s, "livingrealms:cottage", new BlockPos(20, 100, 4096));
        plot(s, "livingrealms:cottage", new BlockPos(40, 100, 4096));
        Simulation.recompute(s);
        Chronicle.tick(server, m, s, 4);
        check(s.chronicle.entries.size() == before, "Cottages are not news");
        plot(s, "livingrealms:barracks", new BlockPos(60, 100, 4096));
        Simulation.recompute(s);
        Chronicle.tick(server, m, s, 4);
        check(last(s).text().contains("Barracks"), "The first barracks is news");
        before = s.chronicle.entries.size();
        plot(s, "livingrealms:barracks", new BlockPos(80, 100, 4096));
        Chronicle.tick(server, m, s, 4);
        check(s.chronicle.entries.size() == before, "The second barracks is not");
        // a quiet stretch: summarised once it is long enough
        before = s.chronicle.entries.size();
        s.population = 20;
        Chronicle.tick(server, m, s, 10);
        check(s.chronicle.entries.size() == before, "No summary before a quiet stretch has run its course");
        Chronicle.tick(server, m, s, 4 + Chronicle.QUIET_DAYS);
        Chronicle.Entry g = last(s);
        check(g.kind() == Chronicle.Kind.GROWTH && g.text().startsWith("Days 4–") && g.text().contains("20") && g.text().contains("building"),
                "Quiet stretch summarised: " + g);
        // a short quiet stretch with growth is folded in before the next event
        plot(s, "livingrealms:cottage", new BlockPos(100, 100, 4096));
        s.population = 26;
        Chronicle.tick(server, m, s, 22);
        long d0 = 22 + Chronicle.MIN_SUMMARY_DAYS + 1;
        Chronicle.recordOn(s, d0, Chronicle.Kind.SIEGE, "Ashford", "the walls held");
        Chronicle.Entry siege = last(s), summary = s.chronicle.entries.get(s.chronicle.entries.size() - 2);
        check(siege.text().contains("Ashford") && siege.text().contains("the walls held") && summary.kind() == Chronicle.Kind.GROWTH,
                "Record API with arguments, growth folded in first: " + summary + " / " + siege);
        // templates: optional parts vanish when their argument is empty
        Chronicle.Entry fallen = Chronicle.record(s, Chronicle.Kind.FALLEN, "Wat", "Sergeant", "");
        check(fallen.text().contains("Wat") && fallen.text().contains("Sergeant") && !fallen.text().contains("kill") && !fallen.text().contains("{"), "Empty argument hides its words: " + fallen);
        Chronicle.Entry fallen2 = Chronicle.record(s, Chronicle.Kind.FALLEN, "Hob", "Champion", "31");
        check(fallen2.text().contains("31") && !fallen2.text().contains("{"), "Kills told: " + fallen2);
        // hooks on existing events
        Settlement rival = new Settlement(); rival.name = "Ashford"; rival.center = new BlockPos(400, 100, 4096); m.add(rival);
        Chronicle.raidEnded(rival, s, false, "Raid repelled: attackers failed to take the city in time");
        check(last(s).kind() == Chronicle.Kind.RAID_REPELLED && last(s).text().contains("Ashford"), "Raid held, by name: " + last(s));
        check(last(rival).kind() == Chronicle.Kind.RAID_FAILED && last(rival).text().contains("Testford"), "Raid failed on the attacker's side: " + last(rival));
        int n = s.chronicle.entries.size();
        Chronicle.raidEnded(rival, s, false, "Raid ended because control of a city changed");
        check(s.chronicle.entries.size() == n, "A raid cut short is left out");
        Chronicle.war(m, s.kingdomId(), rival.kingdomId());
        check(last(s).kind() == Chronicle.Kind.WAR && last(rival).kind() == Chronicle.Kind.WAR, "War written in both kingdoms");
        Settlement.Soldier vet = new Settlement.Soldier(); vet.name = "Edith"; vet.kills = 12; vet.entity = UUID.randomUUID();
        Settlement.Soldier recruit = new Settlement.Soldier(); recruit.name = "Ned"; recruit.entity = UUID.randomUUID();
        n = s.chronicle.entries.size();
        Chronicle.fallen(s, recruit);
        check(s.chronicle.entries.size() == n, "A raw recruit's death is not chronicled");
        Chronicle.fallen(s, vet);
        check(last(s).kind() == Chronicle.Kind.FALLEN && last(s).text().contains("Edith") && last(s).text().contains("12"), "A veteran's death is: " + last(s));
        // ruler changes are noticed by comparison
        s.owner = null; s.kingdom = rival.kingdomId();
        Chronicle.tick(server, m, s, 30);
        check(last(s).kind() == Chronicle.Kind.CONQUERED, "Change of rulers noticed: " + last(s));
        s.owner = owner.getUUID();
        Chronicle.tick(server, m, s, 30);

        // save round trip, and an old save without a chronicle
        Settlement r = Settlement.load(s.save());
        check(r.chronicle.entries.equals(s.chronicle.entries), "Entries survive a reload");
        n = r.chronicle.entries.size();
        Chronicle.tick(server, m, r, 30);
        check(r.chronicle.entries.size() == n, "A reloaded town's baseline is kept (no repeat entries)");
        CompoundTag old = s.save(); old.remove("chronicle");
        Settlement legacy = Settlement.load(old);
        check(legacy.chronicle.entries.isEmpty(), "Old saves load with an empty chronicle");
        Simulation.recompute(legacy);
        Chronicle.tick(server, m, legacy, 40);
        check(legacy.chronicle.entries.size() == 1 && legacy.chronicle.entries.get(0).kind() == Chronicle.Kind.FOUNDING,
                "An old town gets its founding, and no backlog of rank or buildings");

        // the written book
        ItemStack book = ChronicleBook.make(s);
        CompoundTag tag = book.getTag();
        ListTag pages = tag == null ? new ListTag() : tag.getList("pages", Tag.TAG_STRING);
        check(book.is(Items.WRITTEN_BOOK) && tag.getString("title").equals("Chronicle of Testford") && tag.getString("title").length() <= 32, "Written book titled");
        check(pages.size() >= 3 && pages.size() <= 100, "Several pages: " + pages.size());
        StringBuilder all = new StringBuilder();
        int longest = 0;
        for (int i = 0; i < pages.size(); i++) {
            Component c = Component.Serializer.fromJson(pages.getString(i));
            check(c != null, "Page " + i + " is valid JSON text");
            String text = c.getString();
            longest = Math.max(longest, text.replace("\n", "").length());
            all.append(text).append('\n');
        }
        check(all.toString().contains("Year 1") && all.toString().contains("Ashford") && all.toString().contains("Chronicle of"), "Book holds the history with year headings");
        check(longest <= 14 * 19 + 40, "Pages fit a book page (" + longest + " chars)");

        // the cap
        for (int i = 0; i < 400; i++) Chronicle.recordOn(s, 50 + i, Chronicle.Kind.NOTE, "Entry " + i);
        check(s.chronicle.entries.size() == Chronicle.cap() && s.chronicle.entries.get(0).kind() == Chronicle.Kind.FOUNDING
                && last(s).text().equals("Entry 399"), "Capped at " + Chronicle.cap() + ", founding kept, newest kept");
        check(Chronicle.year(1) == 1 && Chronicle.year(Chronicle.yearDays() + 1) == 2, "Years of " + Chronicle.yearDays() + " days");
        ItemStack big = ChronicleBook.make(s);
        check(big.getTag().getList("pages", Tag.TAG_STRING).size() <= 100, "A long chronicle still makes a valid book");
        CompoundTag view = Chronicle.view(s);
        check(view.getList("entries", Tag.TAG_COMPOUND).size() == s.chronicle.entries.size(), "City Manager view carries the entries");
        hall.state = Plot.State.BUILT;
    }

    static Plot plot(Settlement s, String type, BlockPos origin) {
        Plot p = new Plot(); p.id = s.nextId++; p.typeId = type; p.blueprintKey = BuildingCatalog.get(type).blueprintKey(1, s.style);
        p.origin = origin; p.state = Plot.State.BUILT; s.plots.add(p);
        return p;
    }

    // ------------------------------------------------------------------ a real fire

    static void build(ServerLevel level, Plot p) {
        Blueprint b = p.blueprint();
        for (int i = 0; i < b.size(); i++) level.setBlock(b.worldPos(i, p.origin, p.rotation), b.worldState(i, p.rotation), Construction.FLAGS);
    }

    static void fireSetup(MinecraftServer server) {
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int cx = spawn.getX(), cz = spawn.getZ();
        for (int x = (cx - 48) >> 4; x <= (cx + 64) >> 4; x++) for (int z = (cz - 48) >> 4; z <= (cz + 48) >> 4; z++) level.setChunkForced(x, z, true);
        groundY = Construction.ground(level, cx, cz);
        SettlementManager m = SettlementManager.get(server);
        Settlement s = new Settlement();
        s.owner = UUID.randomUUID(); s.ownerName = "FireTester"; s.name = "Emberwick"; s.center = new BlockPos(cx, groundY + 1, cz);
        s.dim = level.dimension(); s.population = 20; s.baseHousing = 20; s.founded = level.getGameTime();
        s.autoBuild = false; s.autoWalls = false; s.autoTier = false; s.autoResearch = false; s.autoTrain = false;
        town = s;
        // risk: a wooden cottage, a stone watchtower; a well and the Fire Watch lower the town's risk
        cottage = plot(s, "livingrealms:cottage", new BlockPos(cx, groundY + 1, cz - 4));
        cottage.origin = new BlockPos(cx + s.radius() - 6 - (cottage.blueprint().width(cottage.rotation) - 1), groundY + 1, cz - 4);
        Plot tower = plot(s, "livingrealms:watchtower", new BlockPos(cx - 30, groundY + 1, cz - 30));
        float wood = Fires.woodFraction(cottage.blueprint()), stone = Fires.woodFraction(tower.blueprint());
        notes.add(String.format(Locale.ROOT, "cottage %.0f%% wood, watchtower %.0f%%", wood * 100, stone * 100));
        check(wood > 0.3f && Fires.risk(s, cottage) > 1.5 * Fires.risk(s, tower), "Wooden cottage riskier than the stone tower");
        double base = Fires.townRisk(s);
        s.research.add(Fires.RESEARCH);
        check(Math.abs(Fires.townRisk(s) - base / 2) < 1e-9, "Fire Watch halves the risk");
        s.research.remove(Fires.RESEARCH);
        Plot well = plot(s, Fires.WELL, new BlockPos(cx - 30, groundY + 1, cz + 20));
        check(Fires.townRisk(s) < base && Fires.hasWell(s), "A well lowers it");
        s.plots.remove(well);
        check(Fires.dailyChance(s) > 0 && Fires.dailyChance(s) < 0.05 && !Fires.label(Fires.dailyChance(s)).isEmpty(), "Low base rate: " + Fires.dailyChance(s));
        m.add(s);
        build(level, tower);
        build(level, cottage);
        // a trail of planks from the cottage to beyond the town's edge
        BoundingBox box = cottage.box();
        trailZ = (box.minZ() + box.maxZ()) / 2;
        plankX0 = box.maxX() + 1; plankX1 = cx + s.radius() + 8;
        for (int x = plankX0; x <= plankX1; x++) {
            level.setBlock(new BlockPos(x, groundY, trailZ), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x, groundY + 1, trailZ), Blocks.AIR.defaultBlockState(), 3);
        }
        Simulation.recompute(s);
        // disabled: nothing happens
        Config.FIRES.set(false);
        check(Fires.ignite(level, s, cottage, "test", level.random) == null && Fires.active(s) == null, "Disabled: no fire starts");
        check(fireIn(box.inflatedBy(1)) == 0 && Fires.view(s).getString("label").equals("Off"), "Disabled: no fire block placed, risk shows Off");
        Config.FIRES.set(true);
        // the bucket brigade, already in town
        for (int i = 0; i < 4; i++) SettlerEntity.spawn(level, s, Job.FARMER, new BlockPos(cottage.origin.getX() - 6, groundY + 1, cz + 8));
        // the roll itself, with a die that always comes up fire
        var always = new LegacyRandomSource(7) { @Override public double nextDouble() { return 0; } };
        Fires.Fire f = Fires.outbreak(level, m, s, always);
        check(f != null && Fires.active(s) == f && f.touched.contains(cottage.id), "A sure roll sets the riskiest building alight");
        check(fireIn(box.inflatedBy(1)) > 0, "Real fire blocks on the cottage");
        t0 = level.getGameTime();
    }

    static int fireIn(BoundingBox b) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()))
            if (level.getBlockState(p).getBlock() instanceof BaseFireBlock) n++;
        return n;
    }

    static boolean outside(BlockPos p) {
        long dx = p.getX() - town.center.getX(), dz = p.getZ() - town.center.getZ();
        return dx * dx + dz * dz > (long) town.radius() * town.radius();
    }

    /** True when finished. */
    static boolean fireTick(MinecraftServer server) {
        long age = level.getGameTime() - t0;
        BoundingBox watch = new BoundingBox(town.center.getX() - town.radius() - 12, groundY - 2, town.center.getZ() - 20,
                town.center.getX() + town.radius() + 12, groundY + 16, town.center.getZ() + 20);
        if (age % 5 == 0) {
            int burning = 0;
            for (BlockPos p : BlockPos.betweenClosed(watch.minX(), watch.minY(), watch.minZ(), watch.maxX(), watch.maxY(), watch.maxZ()))
                if (level.getBlockState(p).getBlock() instanceof BaseFireBlock) { burning++; if (outside(p)) escaped = true; }
            maxBurning = Math.max(maxBurning, burning);
        }
        Fires.Fire f = Fires.active(town);
        if (phase == 0 && age >= 60) {
            // fire right on the town's edge: put out at once
            bandFire = new BlockPos(town.center.getX() + town.radius() - 1, groundY + 1, trailZ);
            level.setBlock(bandFire, Blocks.FIRE.defaultBlockState(), 3);
            phase = 1;
        } else if (phase == 1 && age >= 60 + Fires.SCAN + 2) {
            check(!(level.getBlockState(bandFire).getBlock() instanceof BaseFireBlock) && f != null && f.contained > 0, "Fire at the town's edge put out at once");
            phase = 2;
        } else if (phase == 2 && f == null) {
            Fires.Fire done = Fires.LAST.get(town.id);
            check(done != null && done.done, "The fire was declared out");
            check(maxBurning > 0, "It really burned (" + maxBurning + " blocks at most)");
            check(done.byCrew > 0, "Firefighters put out fire blocks themselves (" + done.byCrew + " by the crew, " + done.byTown + " by unseen townsfolk)");
            check(fireIn(new BoundingBox(watch.minX(), watch.minY(), watch.minZ(), watch.maxX(), watch.maxY(), watch.maxZ())) == 0, "No fire left anywhere");
            check(!escaped, "No fire ever burned outside the town's land");
            boolean planksSafe = true;
            for (int x = town.center.getX() + town.radius() + 1; x <= plankX1; x++)
                if (!level.getBlockState(new BlockPos(x, groundY, trailZ)).is(Blocks.OAK_PLANKS)) planksSafe = false;
            check(planksSafe, "The plank trail beyond the edge is untouched");
            notes.add("fire: peak " + done.peak + ", crew " + done.byCrew + ", town " + done.byTown + ", contained " + done.contained
                    + ", " + (level.getGameTime() - t0) + " ticks, damaged " + done.damaged);
            if (!done.damaged.isEmpty()) {
                check(cottage.state == Plot.State.REPAIRING && done.repairs > 0, "Burnt cottage queued for repair");
                check(last(town).kind() == Chronicle.Kind.FIRE && last(town).text().contains("cottage"), "The fire is in the chronicle: " + last(town).text());
                notes.add("chronicle: " + last(town).text());
            } else {
                // the brigade saved everything: burn the roof by hand and check the reckoning
                notes.add("no natural damage; checked the repair path by hand");
                check(Fires.missing(level, cottage) < 2, "Nothing missing when nothing burned");
            }
            // the reckoning on a deliberately burnt building, always
            if (cottage.state == Plot.State.REPAIRING) cottage.state = Plot.State.BUILT;
            Blueprint b = cottage.blueprint();
            int removed = 0;
            for (int i = 0; i < b.size() && removed < 8; i++) {
                var st = b.worldState(i, cottage.rotation);
                if (st.isAir()) continue;
                BlockPos pos = b.worldPos(i, cottage.origin, cottage.rotation);
                if (level.getBlockState(pos).isAir()) continue;
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                removed++;
            }
            Fires.Fire burnt = new Fires.Fire(town.id, "a test", level.getGameTime(), cottage.box());
            burnt.touched.add(cottage.id);
            burnt.peak = 5;
            Fires.finish(level, town, burnt);
            check(burnt.damaged.contains("cottage") && cottage.state == Plot.State.REPAIRING, "Burnt building queued for the builders' repair");
            check(last(town).kind() == Chronicle.Kind.FIRE && last(town).text().contains("cottage"), "Fire chronicled: " + last(town).text());
            check(Fires.missing(level, cottage) >= 8, "Missing blocks counted");
            return true;
        }
        if (age > 7000) throw new IllegalStateException("Fire still active after " + age + " ticks: " + (f == null ? "-" : f.burning + " burning"));
        return false;
    }

    private ChronicleSelfTest() {}
}
