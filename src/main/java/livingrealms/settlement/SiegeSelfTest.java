package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.LivingRealms;
import livingrealms.military.BattleAI;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.UnitType;
import livingrealms.settlement.plan.Walls;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Sieges in a real world (docs/sieges.md): a walled town is raided by an NPC kingdom's scheduler, shuts its gates, mans its
 * walls, is breached by a ram, stormed, taken and its gate queued and rebuilt; a wall section is sapped and repaired; an NPC
 * town starves under blockade and surrenders; an unwalled town is still taken by holding its centre.
 * {@code ./gradlew runServer -PsiegeTest -PsiegeTestDir=<fresh dir>}; the result goes to siege-test-result.txt.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class SiegeSelfTest {
    private static int checks;
    private static void check(boolean ok, String text) {
        if (!ok) throw new IllegalStateException(text);
        checks++;
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.siegeTest")) return;
        String report;
        try { run(event.getServer().overworld()); report = "PASS: " + checks + " siege assertions"; }
        catch (Throwable error) { report = "FAIL: " + error; LivingRealms.LOG.error("SIEGE SELF TEST FAILED", error); }
        LivingRealms.LOG.info("SIEGE SELF TEST {}", report);
        try { Files.writeString(Path.of("siege-test-result.txt"), report); } catch (Exception e) { throw new RuntimeException(e); }
        event.getServer().halt(false);
    }

    static void ground(ServerLevel level, BlockPos c, int r) {
        for (int x = c.getX() - r; x <= c.getX() + r; x++) for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
            level.getChunk(x >> 4, z >> 4);
            for (int y = 96; y <= 116; y++)
                level.setBlock(new BlockPos(x, y, z), (y < 99 ? Blocks.DIRT : y == 99 ? Blocks.GRASS_BLOCK : Blocks.AIR).defaultBlockState(), 2);
        }
    }

    static final List<long[]> FORCED = new ArrayList<>();

    /** Keeps the chunks round a camp loaded (and their entities reachable) for the test. */
    static void force(ServerLevel level, BlockPos p, int r) {
        for (int x = (p.getX() >> 4) - r; x <= (p.getX() >> 4) + r; x++) for (int z = (p.getZ() >> 4) - r; z <= (p.getZ() >> 4) + r; z++) {
            level.setChunkForced(x, z, true);
            FORCED.add(new long[]{x, z});
        }
        level.getChunkSource().tick(() -> true, true);
    }

    /** Waits (at most ~20 s) for a forced chunk to reach entity-ticking, running the chunk system's main-thread work meanwhile. */
    static void ready(ServerLevel level, BlockPos p) {
        for (int i = 0; i < 20000 && !level.isPositionEntityTicking(p); i++) {
            level.getChunkSource().tick(() -> true, true);
            if (!level.getChunkSource().pollTask()) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
    }

    static Settlement town(SettlementManager m, UUID owner, String name, BlockPos c) {
        Settlement s = new Settlement(); s.owner = owner; s.ownerName = owner == null ? "" : name; s.name = name; s.center = c; s.founded = -48000;
        m.add(s);
        return s;
    }

    static LivingEntity soldier(ServerLevel level, Settlement s, BlockPos at, UnitType type, List<Entity> spawned) {
        ready(level, at);
        Entity e = RecruitsBridge.spawn(level, type, at, s.kingdomId(), "Siege test " + type);
        if (!(e instanceof LivingEntity l)) throw new IllegalStateException("Recruit spawn failed");
        if (level.getEntity(e.getUUID()) == null) throw new IllegalStateException("Recruit not in the level at " + at + ": removed " + e.isRemoved() + " " + e.getRemovalReason() + " chunk " + level.hasChunkAt(at) + " " + level.isPositionEntityTicking(at));
        RecruitsBridge.setSlot(e, EquipmentSlot.MAINHAND, new ItemStack(type == UnitType.BOWMAN ? Items.BOW : Items.IRON_SWORD));
        var so = new Settlement.Soldier(); so.entity = e.getUUID(); so.type = type; so.name = "Siege test " + type;
        s.soldiers.add(so);
        spawned.add(e);
        return l;
    }

    static List<LivingEntity> attackers(ServerLevel level, Raids.Raid r) {
        List<LivingEntity> out = new ArrayList<>();
        for (UUID id : r.soldiers) if (level.getEntity(id) instanceof LivingEntity l) out.add(l);
        return out;
    }

    static void moveAll(List<LivingEntity> es, BlockPos p) {
        int i = 0;
        for (LivingEntity e : es) { e.teleportTo(p.getX() + .5 + (i % 2), 100, p.getZ() + .5 + (i / 2 % 2)); i++; }
    }

    static boolean all(ServerLevel level, List<BlockPos> ps, net.minecraft.world.level.block.Block b) {
        if (ps.isEmpty()) return false;
        for (BlockPos p : ps) if (!level.getBlockState(p).is(b)) return false;
        return true;
    }

    private static void online(MinecraftServer server, ServerPlayer player, boolean present) {
        Map<UUID, ServerPlayer> players = net.minecraftforge.fml.util.ObfuscationReflectionHelper.getPrivateValue(
                net.minecraft.server.players.PlayerList.class, server.getPlayerList(), "f_11197_");
        if (players == null) throw new IllegalStateException("Test player lookup unavailable");
        if (present) players.put(player.getUUID(), player); else players.remove(player.getUUID());
    }

    static void run(ServerLevel level) {
        MinecraftServer server = level.getServer();
        SettlementManager m = SettlementManager.get(server);
        ServerPlayer owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "SiegeOwner"));
        List<Settlement> towns = new ArrayList<>();
        List<Entity> spawned = new ArrayList<>();
        BlockPos c = new BlockPos(4096, 100, 4096);
        try {
            // ------------------------------------------------------------ a walled player town
            // every camp's ground first: its chunks (and their entity sections) finish loading while the walls are built
            BlockPos hc = c.offset(170, 0, 0), nc = c.offset(0, 0, -170), oc = new BlockPos(8192, 100, 4096), ac = oc.offset(160, 0, 0);
            force(level, c, 5); force(level, hc, 1); force(level, nc, 1); force(level, oc, 1); force(level, ac, 1);
            ground(level, c, 62); ground(level, hc, 8); ground(level, nc, 8); ground(level, oc, 14); ground(level, ac, 8);
            Settlement town = town(m, owner.getUUID(), "Siegeburg", c); towns.add(town);
            town.founded = 0;
            town.research.addAll(livingrealms.research.ResearchTree.nodes().keySet());
            town.tier = Tier.VILLAGE;
            int pieces = CityPlanningSelfTest.buildWalls(level, town);
            Walls.Line line = Walls.unlocked(town).get(0);
            check(Siege.walled(town) && Walls.gates(town).size() == 4 && pieces >= Walls.pieces(line).size(),
                    "The town is walled with four gatehouses: " + pieces + " pieces, " + Walls.status(town));
            town.tier = Tier.TOWN; // towers shoot from Town rank
            town.stock.force(Items.BREAD, 40);

            // a gap in the west wall is the weak point for an army from the west; a whole wall sends it to a gate
            Plot west = town.plots.stream().filter(p -> p.typeId.equals(Walls.WALL) && p.state == Plot.State.BUILT && Siege.real(p)
                    && Walls.side(town, p.center()).equals("west")).findFirst().orElseThrow();
            Siege probe = new Siege();
            probe.choose(level, town, line, c.offset(-200, 0, 0));
            check(probe.gate && probe.where.equals("west gate") && probe.needed > Siege.GATE_SECONDS * 20, "A closed wall: the attackers make for the nearest gate: " + probe.where + " " + probe.needed);
            west.state = Plot.State.BUILDING;
            probe = new Siege();
            probe.choose(level, town, line, c.offset(-200, 0, 0));
            check(probe.plot == -1 && probe.breached && probe.needed == 0 && probe.where.startsWith("gap in the west"),
                    "An unfinished stretch is the weak point: " + probe.where);
            west.state = Plot.State.BUILT;
            Settlement weak = new Settlement(); weak.style = town.style; weak.tier = Tier.VILLAGE;
            check(Siege.strength(town, line, null, c.getX() + 38, c.getZ()) > Siege.strength(weak, line, null, c.getX() + 38, c.getZ()),
                    "A larger capital's walls take longer to break than a plain village's");

            // ------------------------------------------------------------ a wall section sapped and repaired
            Siege sap = new Siege();
            sap.walled = true; sap.line = line.index(); sap.plot = west.id; sap.gate = false; sap.where = "west wall";
            BlockPos wc = west.center();
            sap.at = Siege.nearest(west.columns(), wc.getX(), wc.getZ(), 100);
            List<BlockPos> sapped = sap.breachBlocks(town);
            check(sapped.size() >= 12 && sapped.stream().allMatch(p -> !level.getBlockState(p).isAir()), "A wall breach takes down real wall: " + sapped.size() + " blocks");
            sap.breach(level, town);
            check(sapped.stream().allMatch(p -> level.getBlockState(p).isAir()) && level.getBlockState(sap.at.atY(100)).isAir()
                    && !level.getBlockState(sap.at.atY(99)).isAir(), "The breach is open to the ground, its floor left to walk on");
            Raids.Raid dummy = new Raids.Raid(); dummy.siege = sap;
            Siege.end(server, dummy, town);
            check(west.state == Plot.State.REPAIRING && town.log.stream().anyMatch(l -> l.contains("repair the west wall")), "The broken wall is queued for repair");
            CityPlanningSelfTest.stock(town);
            CityPlanningSelfTest.buildQueued(level, town);
            check(west.state == Plot.State.BUILT && sapped.stream().noneMatch(p -> level.getBlockState(p).isAir()), "The builders rebuild the breach");

            // ------------------------------------------------------------ defenders and an NPC army from the east
            LivingEntity bowman = soldier(level, town, c.offset(2, 0, 2), UnitType.BOWMAN, spawned);
            LivingEntity guard = soldier(level, town, c.offset(-2, 0, 2), UnitType.FOOTMAN, spawned);
            Settlement host = town(m, null, "Eastern Host", hc); towns.add(host);
            UnitType[] kinds = {UnitType.FOOTMAN, UnitType.BOWMAN, UnitType.FOOTMAN, UnitType.FOOTMAN, UnitType.FOOTMAN, UnitType.FOOTMAN, UnitType.FOOTMAN, UnitType.FOOTMAN};
            for (int i = 0; i < kinds.length; i++) soldier(level, host, hc.offset(i % 4, 0, i / 4), kinds[i], spawned);
            online(server, owner, true);
            m.nextRaidCheck = 0;
            Raids.tick(server, m);
            check(m.raids.size() == 1, "The NPC kingdom's scheduler raids the walled town");
            Raids.Raid r = m.raids.get(0);
            Siege sg = r.siege;
            check(sg.walled && sg.phase == Siege.Phase.APPROACH && sg.gate && sg.where.equals("east gate") && r.maxTicks() == Siege.MAX_TICKS,
                    "A raid on a walled town is a siege of its east gate: " + sg.phase + " " + sg.where);
            check(sg.shut.size() == 4 && !sg.closed.isEmpty(), "Every gatehouse closes: " + sg.shut.size() + " gates, " + sg.closed.size() + " bars");
            Plot eastGate = town.plot(sg.plot);
            for (Plot g : Walls.gates(town))
                check(all(level, Siege.passage(town, line, g).bars(), Blocks.IRON_BARS), "A portcullis of iron bars fills the " + Walls.side(town, g.center()) + " gate");
            check(town.log.stream().anyMatch(l -> l.contains("gates of Siegeburg are shut")), "The town hears its gates shut");
            List<LivingEntity> army = attackers(level, r);
            check(army.size() == 4, "Half the host marches: " + army.size());
            Raids.tickRaid(server, m, r);
            check(sg.phase == Siege.Phase.APPROACH && town.raidStatus.startsWith("Approach") && town.raidStatus.contains("east gate"), "Status shows the approach: " + town.raidStatus);
            CompoundTag view = livingrealms.net.View.raid(m, town);
            check(view != null && view.getString("phase").equals("Approach") && view.getInt("max") == Siege.MAX_TICKS, "The City Manager raid card hears the phase");

            // they form up before the gate: the ram is brought up
            moveAll(army, sg.staging(town));
            Raids.tickRaid(server, m, r);
            check(sg.phase == Siege.Phase.BREACH && town.raidStatus.startsWith("Breach"), "At the walls the breach begins: " + town.raidStatus);
            check(sg.progress == 0 && town.raidStatus.contains("(no crew at work)"), "No progress while the crew stands off: " + sg.progress + " " + town.raidStatus + " " + sg.outside(town) + " " + army.get(0).blockPosition());
            var post = RecruitsBridge.holdPos(bowman);
            check(post != null && post.y >= 100 + Walls.H - 1 && town.raidStatus.contains("1 on the walls"), "The bowman is posted on the wall walk: " + post + " · " + town.raidStatus);
            var stand = RecruitsBridge.holdPos(guard);
            BlockPos in = sg.inside(town);
            check(stand != null && Math.abs(stand.x - in.getX()) + Math.abs(stand.z - in.getZ()) <= 8, "The footman holds the inside of the gate: " + stand + " vs " + in);
            // the archer climbs when it reaches the foot of its tower
            BlockPos postPos = BlockPos.containing(post);
            bowman.teleportTo(postPos.getX() + .5, 100, postPos.getZ() + 2.5);
            Raids.tickRaid(server, m, r);
            check(bowman.getY() >= 100 + Walls.H - 1, "At the foot of the wall the bowman climbs up: y " + bowman.getY());

            // save and reload mid-siege; a raid saved before sieges loads as an open assault
            var restored = SettlementManager.load(m.save(new CompoundTag()));
            Siege back = restored.raids.get(0).siege;
            check(back.walled && back.phase == Siege.Phase.BREACH && back.closed.equals(sg.closed) && back.where.equals(sg.where)
                    && back.needed == sg.needed && back.plot == sg.plot && back.shut.equals(sg.shut), "The siege survives save and reload");
            CompoundTag old = r.save(); old.remove("siege");
            Raids.Raid legacy = Raids.Raid.load(old);
            check(!legacy.siege.walled && legacy.siege.phase == Siege.Phase.ASSAULT && legacy.maxTicks() == Raids.MAX_TICKS, "An old raid without a siege loads as an assault");

            // the ram crew at the gate breaks it over time
            BlockPos ram = sg.outside(town);
            List<BlockPos> gateBars = Siege.passage(town, line, eastGate).bars(), arch = Siege.passage(town, line, eastGate).arch();
            check(!arch.isEmpty() && arch.stream().noneMatch(p -> level.getBlockState(p).isAir()), "The gate arch stands over the passage");
            int loops = 0, firstProgress = -1;
            while (!sg.breached && loops++ < 400) {
                moveAll(army, ram);
                Raids.tickRaid(server, m, r);
                if (firstProgress < 0) firstProgress = sg.progress;
            }
            check(firstProgress > 0 && firstProgress <= 40, "The ram makes progress each second: " + firstProgress);
            check(sg.breached && sg.phase == Siege.Phase.ASSAULT && loops > 10, "The east gate is broken after " + loops + " seconds");
            check(gateBars.stream().allMatch(p -> level.getBlockState(p).isAir()) && arch.stream().allMatch(p -> level.getBlockState(p).isAir()),
                    "The portcullis and arch of the broken gate are down");
            check(sg.damaged.contains(eastGate.id), "The broken gate is marked for repair");
            for (Plot g : Walls.gates(town)) if (g != eastGate)
                check(all(level, Siege.passage(town, line, g).bars(), Blocks.IRON_BARS), "The other gates stay shut");
            check(sg.volleys > 0 && !level.getEntitiesOfClass(Arrow.class, new AABB(c).inflate(80)).isEmpty(), "The towers loose volleys at the attackers: " + sg.volleys);
            check(sg.blockade > 0 && sg.drained > 0, "The blockade eats the town's stores: " + sg.drained + " food");
            check(town.log.stream().anyMatch(l -> l.contains("ram has broken the east gate")), "The town hears the gate fall");

            // the assault: the defenders fall, the attackers hold the centre and take the town
            Raids.tickRaid(server, m, r);
            check(town.raidStatus.startsWith("Assault"), "Then the assault: " + town.raidStatus);
            for (LivingEntity d : List.of(bowman, guard)) d.hurt(level.damageSources().genericKill(), 10000);
            moveAll(army, c.offset(2, 0, 0));
            Raids.tickRaid(server, m, r);
            check(sg.phase == Siege.Phase.HOLD && town.raidStatus.startsWith("City center occupied"), "Holding the centre: " + town.raidStatus);
            for (int i = 0; i < 80 && m.raids.contains(r); i++) { moveAll(army, c.offset(2, 0, 0)); Raids.tickRaid(server, m, r); }
            check(!m.raids.contains(r) && Kingdoms.same(town, host), "The stormed town falls to the host");
            for (Plot g : Walls.gates(town)) if (g != eastGate)
                check(Siege.passage(town, line, g).bars().stream().allMatch(p -> level.getBlockState(p).isAir()), "The gates open when the siege ends");
            check(eastGate.state == Plot.State.REPAIRING && town.log.stream().anyMatch(l -> l.contains("repair the east gate")), "The broken gate is queued for the builders");
            town.wealth = 1e6;
            CityPlanningSelfTest.stock(town);
            CityPlanningSelfTest.buildQueued(level, town);
            check(eastGate.state == Plot.State.BUILT && arch.stream().noneMatch(p -> level.getBlockState(p).isAir())
                    && gateBars.stream().allMatch(p -> level.getBlockState(p).isAir()), "The gate is rebuilt, its passage open");
            online(server, owner, false);

            // ------------------------------------------------------------ starved into surrender
            for (Entity e : List.copyOf(spawned)) if (host.soldiers.stream().anyMatch(so -> so.entity.equals(e.getUUID()))) e.teleportTo(hc.getX() + .5, 100, hc.getZ() + .5);
            Settlement north = town(m, null, "Northern Host", nc); towns.add(north);
            for (int i = 0; i < 6; i++) soldier(level, north, nc.offset(i % 3, 0, i / 3), UnitType.FOOTMAN, spawned);
            for (Settlement s : m.all()) s.raidCooldown = 0;
            String err = Raids.start(server, m, north, town);
            check(err == null, "A second kingdom besieges the town: " + err);
            Raids.Raid r2 = m.raids.get(0);
            check(r2.siege.walled && r2.siege.where.equals("north gate") && r2.siege.provisions > 0, "An NPC town has provisions to eat: " + r2.siege.provisions);
            r2.siege.provisions = 1;
            List<LivingEntity> army2 = attackers(level, r2);
            for (int i = 0; i < 400 && m.raids.contains(r2); i++) { moveAll(army2, r2.siege.staging(town)); Raids.tickRaid(server, m, r2); }
            check(!m.raids.contains(r2) && r2.siege.surrendered && !r2.siege.breached && Kingdoms.same(town, north),
                    "Starved under blockade, the town surrenders: " + r2.siege.starved + " ticks starving");
            check(town.log.stream().anyMatch(l -> l.contains("surrendered to Northern Host")) && town.log.stream().anyMatch(l -> l.contains("go hungry")),
                    "The chronicle tells of the hunger and the surrender");
            check(town.plots.stream().noneMatch(p -> Walls.isWallPiece(p) && p.state == Plot.State.REPAIRING)
                    && Walls.gates(town).stream().allMatch(g -> Siege.passage(town, line, g).bars().stream().allMatch(p -> level.getBlockState(p).isAir())),
                    "A surrender leaves the walls whole and the gates open");

            // ------------------------------------------------------------ an open town is still taken at its centre
            Settlement open = town(m, null, "Open Town", oc); towns.add(open);
            Settlement raiders = town(m, null, "Open Raiders", ac); towns.add(raiders);
            for (int i = 0; i < 6; i++) soldier(level, raiders, ac.offset(i % 3, 0, i / 3), UnitType.FOOTMAN, spawned);
            err = Raids.start(server, m, raiders, open);
            check(err == null, "An open town can be raided: " + err);
            Raids.Raid r3 = m.raids.get(0);
            check(!r3.siege.walled && r3.siege.phase == Siege.Phase.ASSAULT && r3.maxTicks() == Raids.MAX_TICKS && r3.siege.closed.isEmpty(),
                    "An unwalled town skips straight to the assault");
            List<LivingEntity> army3 = attackers(level, r3);
            moveAll(army3, oc);
            Raids.tickRaid(server, m, r3);
            check(open.raidStatus.startsWith("City center occupied") && r3.siege.phase == Siege.Phase.HOLD, "Its centre is held: " + open.raidStatus);
            for (int i = 0; i < 80 && m.raids.contains(r3); i++) { moveAll(army3, oc); Raids.tickRaid(server, m, r3); }
            check(!m.raids.contains(r3) && Kingdoms.same(open, raiders), "The unwalled raid resolves as before");
            check(Siege.wallFactor(open) == 1 && Siege.wallFactor(town) > 1, "NPC kingdoms weigh walls when choosing targets");
            LivingRealms.LOG.info("Siege test: {} wall pieces, gate broken in {} s, {} volleys, {} food eaten", pieces, loops, sg.volleys, sg.drained);
        } finally {
            online(server, owner, false);
            for (Raids.Raid r : List.copyOf(m.raids)) m.raids.remove(r);
            for (Entity e : spawned) e.discard();
            for (Settlement s : towns) m.remove(s);
            for (long[] f : FORCED) level.setChunkForced((int) f[0], (int) f[1], false);
        }
    }
}
