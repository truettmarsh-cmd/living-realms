package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.LivingRealms;
import livingrealms.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class CityPlanningSelfTest {
    private static int checks;
    private static void check(boolean ok, String text) { if (!ok) throw new IllegalStateException(text); checks++; }
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.cityPlanningTest")) return;
        String report;
        try { run(event.getServer().overworld()); report = "PASS: " + checks + " city planning assertions"; }
        catch (Throwable error) { report = "FAIL: " + error; LivingRealms.LOG.error("CITY PLANNING TEST FAILED", error); }
        LivingRealms.LOG.info("CITY PLANNING TEST {}", report);
        try { Files.writeString(Path.of("city-planning-result.txt"), report); } catch (Exception e) { throw new RuntimeException(e); }
        event.getServer().halt(false);
    }

    public static void run(ServerLevel level) {
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "CityPlanner"));
        var stranger = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "OtherPlanner"));
        Settlement s = new Settlement(); s.owner = owner.getUUID(); s.center = new BlockPos(1024,100,1024); s.name="Planning test";
        var m = SettlementManager.get(level.getServer()); m.add(s);
        try {
            for (int x=976;x<=1072;x++) for(int z=976;z<=1072;z++) {
                level.getChunk(x>>4,z>>4);
                for (int y=98;y<=112;y++) level.setBlock(new BlockPos(x,y,z), (y<=99?Blocks.GRASS_BLOCK:Blocks.AIR).defaultBlockState(),2);
            }
            Simulation.recompute(s);
            AutoBuild.think(level,s);
            check(s.plots.isEmpty(),"No autonomous project without materials");
            var hall = BuildingCatalog.get("livingrealms:town_hall");
            var bp = BuildingCatalog.blueprint(hall.blueprintKey(1,s.style));
            bp.bill().forEach((item,n)->s.stock.force(item,n+500));
            s.stock.force(Blueprint.costItem(Construction.foundationBlock(s)),5000);
            s.autoBuild=false; AutoBuild.think(level,s);
            check(s.plots.isEmpty(),"Toggle prevents automatic planning");
            s.autoBuild=true;
            for(int i=0;i<10 && s.plots.isEmpty();i++) AutoBuild.think(level,s);
            check(buildings(s)==1,"Empty queue automatically gains one building");
            Plot auto=s.plots.get(0);
            check(s.plots.stream().filter(p->p.typeId.equals("road") && p.automatic).count()>=4,"The market place and the first stretch of the high streets are queued with the hall");
            check(auto.automatic && !auto.ai && auto.typeId.equals(hall.id),"Founders choose a stockpile-funded Town Hall");
            check(auto.placed==0 && auto.paid==0,"Automatic planning does not instantly construct buildings");
            int queued=s.plots.size(); AutoBuild.think(level,s); check(s.plots.size()==queued,"No runaway automatic queue");
            var cottage=BuildingCatalog.get("livingrealms:cottage");
            var cottageBp=BuildingCatalog.blueprint(cottage.blueprintKey(1,s.style));
            cottageBp.bill().forEach((item,n)->s.stock.force(item,n+500));
            String err="no site";
            for(int dx=-30;dx<=25 && err!=null;dx+=11) for(int dz=-30;dz<=25 && err!=null;dz+=11)
                err=Planning.planMap(level,s,cottage.id,1024+dx,1024+dz,1);
            check(err==null,"Map plan accepted on clear terrain: "+err);
            Plot manual=s.plots.get(s.plots.size()-1);
            check(!manual.automatic && manual.rotation==net.minecraft.world.level.block.Rotation.CLOCKWISE_90,"Map placement retains manual priority and rotation");
            check(manual.origin.getY()==99-cottage.sink,"Server selects ground height");
            check(manual.placed==0,"Map placement only queues construction");
            s.tier=Tier.CAPITAL;
            int beforeCastle=s.plots.size();
            CapitalCastles.tick(level,s);
            check(s.plots.size()==beforeCastle && s.castleStatus.isEmpty(),"Automatic castle planning waits behind the existing queue");
            s.tier=Tier.CAMP;
            check(!AutoBuild.mayWork(s,auto) && Simulation.activePlots(s).equals(List.of(manual)),"Manual queue preempts automatic project");
            int stock=s.stock.total();
            check(Construction.work(level,s,auto)==Construction.Result.MISSING && auto.placed==0 && s.stock.total()==stock,"Paused automatic work spends no resources");
            check(Construction.payAhead(s,auto,100)==0 && auto.paid==0,"Offscreen work respects manual priority");
            var restored=Settlement.load(s.save());
            check(restored.autoBuild && restored.plots.get(0).automatic && !restored.plots.get(s.plots.indexOf(manual)).automatic
                    && !AutoBuild.mayWork(restored,restored.plots.get(0)),"Priority and settings survive reload");
            int count=s.plots.size();
            check(Planning.planMap(level,s,cottage.id,Integer.MAX_VALUE,1024,0)!=null && s.plots.size()==count,"Out-of-territory map click rejected");
            check(Planning.planMap(level,s,cottage.id,Integer.MIN_VALUE,Integer.MIN_VALUE,0)!=null,"Extreme coordinates cannot overflow territory checks");
            Settlement distant=new Settlement();distant.owner=owner.getUUID();distant.center=new BlockPos(1000000,100,1000000);
            check(Planning.planMap(level,distant,hall.id,1000000,1000000,0).contains("loaded")
                    && !level.hasChunkAt(distant.center),"Map planning never force-loads remote terrain");
            check(Planning.planMap(level,s,cottage.id,1024,1024,9)!=null,"Invalid map rotation rejected");
            check(Planning.planMap(level,s,"missing:building",1024,1024,0)!=null,"Unknown building rejected");
            check(Planning.planMap(level,s,cottage.id,manual.origin.getX(),manual.origin.getZ(),1)!=null,"Overlapping queued footprint rejected");
            CompoundTag request=new CompoundTag();request.putString("type",cottage.id);request.putInt("x",1024);request.putInt("z",1024);
            Actions.handle(stranger,s.id,"planMap",request);
            check(s.plots.size()==count,"Another player cannot queue construction");
            request.putBoolean("on",false);Actions.handle(stranger,s.id,"autoBuild",request);
            check(s.autoBuild,"Another player cannot toggle automatic growth");
            Actions.handle(owner,s.id,"autoBuild",request);
            check(!s.autoBuild,"Owner controls automatic growth");
            request.putBoolean("on",true); Actions.handle(owner,s.id,"autoBuild",request);
            int before=s.stock.total();
            for(int i=0;i<manual.totalSteps()+100;i++) { var result=Construction.work(level,s,manual); if(result==Construction.Result.DONE) break;
                if (result==Construction.Result.MISSING) throw new IllegalStateException("Manual project missing materials: "+manual.status); }
            check(manual.placed>=manual.totalSteps() && s.stock.total()<before,"Manual project physically builds and consumes resources");
            Simulation.fast(level,s);
            check(manual.state==Plot.State.BUILT && AutoBuild.mayWork(s,auto),"Automatic project resumes when manual queue finishes");
            before=s.stock.total();
            for(int i=0;i<30 && s.stock.total()==before;i++) Construction.work(level,s,auto);
            check(s.stock.total()<before,"Automatic builders consume normal town stockpile");
            s.autoBuild=false;
            check(!AutoBuild.mayWork(s,auto),"Turning off pauses existing automatic work");
            CompoundTag legacy=s.save();legacy.remove("autoBuild");
            check(Settlement.load(legacy).autoBuild,"Existing player towns default to automatic growth");
            BlockPos probe=new BlockPos(976,100,976);
            Blueprint empty=new Blueprint("test:clearance",1,4,1,List.of(new Blueprint.Entry(0,0,0,Blocks.AIR.defaultBlockState())));
            check(AutoBuild.clear(level,probe,empty,net.minecraft.world.level.block.Rotation.NONE),"Clearance accepts empty site");
            level.setBlock(probe.above(3),Blocks.CHEST.defaultBlockState(),2);
            check(!AutoBuild.clear(level,probe,empty,net.minecraft.world.level.block.Rotation.NONE),"Automatic siting checks upper containers across the full height");
        } finally { m.remove(s); }
        plans(level);
        grownTown(level, owner, stranger);
    }

    /** A paved road block within 4 blocks straight out from the building's front wall at its door. */
    public static boolean roadAtDoor(ServerLevel level, Plot p) {
        Blueprint bp = p.blueprint();
        BlockPos door = bp.doorWorld(p.origin, p.rotation);
        var front = bp.front(p.rotation);
        var box = p.box();
        BlockPos edge = switch (front) {
            case NORTH -> new BlockPos(door.getX(), 0, box.minZ()); case SOUTH -> new BlockPos(door.getX(), 0, box.maxZ());
            case WEST -> new BlockPos(box.minX(), 0, door.getZ()); default -> new BlockPos(box.maxX(), 0, door.getZ());
        };
        for (int i = 1; i <= 4; i++) {
            BlockPos q = edge.relative(front, i);
            if (Planning.ROAD_MATERIALS.containsValue(level.getBlockState(new BlockPos(q.getX(), Construction.ground(level, q.getX(), q.getZ()), q.getZ())))) return true;
        }
        return false;
    }

    static long buildings(Settlement s) { return s.plots.stream().filter(p -> !p.typeId.equals("road")).count(); }

    // ------------------------------------------------------------------ the town plan on synthetic ground

    static final String[] MIX = {"cottage", "cottage", "farm_field", "cottage", "storehouse", "lumber_camp", "cottage", "builders_yard",
            "smithy", "tavern", "cottage", "barracks", "farm_field", "library", "cottage", "mine", "chapel", "market", "cottage",
            "watchtower", "stables", "well", "cottage", "garden", "farm_field", "cottage"};

    /** Grows a planned town on ground that is never loaded into a world, as far as City rank; returns what it built. */
    static List<String> plannedTown(livingrealms.settlement.plan.Terrain ground, BlockPos center, UUID owner, int perTier) {
        Settlement s = new Settlement(); s.id = owner; s.owner = owner; s.center = center; s.name = "Plan test";
        s.research.addAll(livingrealms.research.ResearchTree.nodes().keySet());
        var plan = s.plan();
        var hall = BuildingCatalog.get("livingrealms:town_hall");
        var at = plan.place(ground, s, hall);
        check(at != null, "Plan: a site for the town hall");
        check(Planning.plan(null, s, hall, at.origin(), at.rot(), false) == null, "Plan: town hall accepted");
        plan.frontage.put(s.plots.get(0).id, at.street());
        plan.founding(s.radius()).forEach(plan::paved);
        plan.connect(at.street(), at.along(), s.radius()).forEach(plan::paved);
        int k = 0, placed = 1, wallPieces = 0;
        for (Tier tier : new Tier[]{Tier.CAMP, Tier.HAMLET, Tier.VILLAGE, Tier.TOWN, Tier.CITY}) {
            s.tier = tier;
            for (int i = 0; i < perTier; i++) {
                var t = BuildingCatalog.get("livingrealms:" + MIX[k++ % MIX.length]);
                if (t == null || s.cannotBuild(t) != null) continue;
                var p = plan.place(ground, s, t);
                if (p == null) continue;
                String err = Planning.plan(null, s, t, p.origin(), p.rot(), false);
                check(err == null, "Plan: " + t.name + " placement accepted: " + err);
                plan.frontage.put(s.plots.get(s.plots.size() - 1).id, p.street());
                plan.connect(p.street(), p.along(), s.radius()).forEach(plan::paved);
                placed++;
            }
            wallPieces += planWalls(ground, s);
        }
        var bad = livingrealms.settlement.plan.PlanAudit.audit(s, plan, ground);
        check(bad.isEmpty(), "Plan audit: " + bad);
        var badWalls = livingrealms.settlement.plan.PlanAudit.walls(s, plan, ground);
        check(badWalls.isEmpty(), "Wall audit: " + badWalls);
        for (var line : livingrealms.settlement.plan.Walls.unlocked(s))
            check(livingrealms.settlement.plan.Walls.pieces(line).stream().allMatch(pc -> s.plots.stream().anyMatch(p -> p.blueprintKey.equals(pc.key(s)))),
                    "Plan: every piece of wall line " + line.index() + " is planned");
        check(livingrealms.settlement.plan.Walls.unlocked(s).size() == 2 && wallPieces > 40, "Plan: old-town and city walls by City rank: " + wallPieces + " pieces");
        List<String> out = new ArrayList<>();
        for (Plot p : s.plots) out.add(p.typeId + "@" + p.origin.toShortString() + "/" + p.rotation);
        LivingRealms.LOG.info("City plan test: {} buildings, {} streets paved, e.g. {}", placed, plan.laid.size(), out.subList(0, Math.min(6, out.size())));
        return out;
    }

    /** Plans every wall piece the town has unlocked, paving high streets out through their gatehouses. */
    static int planWalls(livingrealms.settlement.plan.Terrain ground, Settlement s) {
        int n = 0;
        for (int w = 0; w < 400; w++) {
            var wp = livingrealms.settlement.plan.Walls.next(ground, s);
            if (wp == null) break;
            String err = livingrealms.settlement.plan.Walls.queue(s, wp, true);
            check(err == null, "Plan: wall piece " + wp.piece() + " accepted: " + err);
            int[] street = livingrealms.settlement.plan.Walls.gateStreet(s, s.plan().wallLines().get(wp.piece().line()), wp.piece());
            if (street != null) s.plan().connect(street[0], street[1], s.radius()).forEach(s.plan()::paved);
            n++;
        }
        return n;
    }

    static void plans(ServerLevel level) {
        UUID owner = UUID.randomUUID();
        BlockPos c = new BlockPos(5000, 64, 5000);
        var flat = livingrealms.settlement.plan.Terrain.grid((x, z) -> 64, (x, z) -> false);
        long t0 = System.nanoTime();
        List<String> a = plannedTown(flat, c, owner, 8);
        LivingRealms.LOG.info("City plan test: flat town in {} ms", (System.nanoTime() - t0) / 1_000_000);
        check(a.size() >= 30, "Plan: a flat town grows to 30+ buildings: " + a.size());
        check(a.equals(plannedTown(flat, c, owner, 8)), "Plan: the same town comes out the same every time");
        // gentle slope with a steep hill to the north-east
        plannedTown(livingrealms.settlement.plan.Terrain.grid((x, z) -> 64 + (x - 5000) / 6 + Math.max(0, Math.min(x - 5040, 5000 - z - 40)) / 2,
                (x, z) -> false), c, owner, 8);
        // a 7-wide river 30 blocks south
        List<String> river = plannedTown(livingrealms.settlement.plan.Terrain.grid((x, z) -> 64, (x, z) -> Math.abs(z - 5030) <= 3), c, owner, 8);
        check(river.size() >= 20, "Plan: a river town still grows: " + river.size());
        // a lake 50 blocks east
        plannedTown(livingrealms.settlement.plan.Terrain.grid((x, z) -> 64, (x, z) -> (x - 5050) * (x - 5050) + (z - 5000) * (z - 5000) <= 625), c, owner, 8);
        // fronts: a procedural cottage's door is on its south (+z) wall and turns with it; a vanilla house knows its entrance
        var cottage = BuildingCatalog.blueprint(BuildingCatalog.get("livingrealms:cottage").blueprintKey(1, "minecraft:plains"));
        check(cottage.front() == net.minecraft.core.Direction.SOUTH, "Procedural front is south");
        for (var side : new net.minecraft.core.Direction[]{net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
                net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST}) {
            var rot = cottage.facing(side);
            BlockPos door = cottage.doorWorld(BlockPos.ZERO, rot);
            int w = cottage.width(rot), d = cottage.depth(rot);
            boolean ok = switch (side) {
                case NORTH -> door.getZ() <= 3; case SOUTH -> door.getZ() >= d - 4; case WEST -> door.getX() <= 3; default -> door.getX() >= w - 4;
            };
            check(ok, "Cottage turned to face " + side + " has its door on that side: " + door);
        }
        for (BuildingType t : BuildingCatalog.types().values()) {
            if (!t.id.contains("plains_small_house_1")) continue;
            var bp = BuildingCatalog.blueprint(t.blueprintKey(1, "minecraft:plains"));
            check(bp != null && bp.front() == net.minecraft.core.Direction.WEST, "Vanilla plains_small_house_1 entrance faces west: " + (bp == null ? null : bp.front()));
        }
    }

    // ------------------------------------------------------------------ a planned town in the world

    static void grownTown(ServerLevel level, net.minecraft.world.entity.player.Player owner, net.minecraft.world.entity.player.Player stranger) {
        BlockPos c = new BlockPos(2048, 100, 2048);
        for (int x = c.getX() - 64; x <= c.getX() + 64; x++) for (int z = c.getZ() - 64; z <= c.getZ() + 64; z++) {
            level.getChunk(x >> 4, z >> 4);
            for (int y = 96; y <= 116; y++) level.setBlock(new BlockPos(x, y, z), (y <= 99 ? y == 99 ? Blocks.GRASS_BLOCK : Blocks.DIRT : Blocks.AIR).defaultBlockState(), 2);
        }
        Settlement s = new Settlement(); s.owner = owner.getUUID(); s.center = c; s.name = "Planned town"; s.tier = Tier.HAMLET;
        var m = SettlementManager.get(level.getServer()); m.add(s);
        try {
            int n = grow(level, s, 16);
            check(n >= 12, "Planned town grows: " + n);
            if (Realms.on()) {
                // Village Expansion lays this town out instead (VillageExpansionSelfTest checks its lots in depth)
                check(!s.layout.isEmpty() && (s.townPlan == null || s.townPlan.laid.isEmpty()), "With Village Expansion its layout is used, not ours");
                for (Plot p : s.plots) if (!p.typeId.equals("road")) check(roadAtDoor(level, p), "Road at the door of " + p.typeId + " on a Village Expansion lot");
                return;
            }
            Plot hall = s.plots.stream().filter(p -> p.typeId.equals("livingrealms:town_hall")).findFirst().orElseThrow();
            check(Math.sqrt(hall.center().distSqr(new BlockPos(c.getX(), hall.center().getY(), c.getZ()))) <= 12, "Town hall stands in the middle: " + hall.center());
            check(hall.blueprint().front(hall.rotation) == net.minecraft.core.Direction.SOUTH, "Town hall faces south over the market place");
            check(Planning.ROAD_MATERIALS.containsValue(level.getBlockState(new BlockPos(c.getX(), Construction.ground(level, c.getX(), c.getZ() + 15), c.getZ() + 15))),
                    "Market place in front of the hall is paved");
            // sites were dry when chosen; built village templates bring their own water (farms, smiths' troughs), so no water check here
            var bad = livingrealms.settlement.plan.PlanAudit.audit(s, s.plan(), livingrealms.settlement.plan.Terrain.grid((x, z) -> 0, (x, z) -> false));
            check(bad.isEmpty(), "Planned town audit: " + bad);
            int doors = 0, roads = 0, fronted = 0;
            for (Plot p : s.plots) {
                if (!s.plan().frontage.containsKey(p.id)) continue;
                fronted++;
                Blueprint bp = p.blueprint();
                BlockPos door = bp.doorWorld(p.origin, p.rotation);
                if (!p.typeId.startsWith("livingrealms:") || level.getBlockState(door).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) doors++;
                if (roadAtDoor(level, p)) roads++;
            }
            LivingRealms.LOG.info("Planned town: {} street-fronted buildings, {} with a door in place, {} with a road at the door", fronted, doors, roads);
            check(fronted >= 10 && roads == fronted, "Every street-fronted building has a paved road outside its front: " + roads + "/" + fronted);
            check(doors * 2 >= fronted, "Doors stand where the plan expects them: " + doors + "/" + fronted);
            // a building on a paved street is refused
            Plot road = s.plots.stream().filter(p -> p.typeId.equals("road")).findFirst().orElseThrow();
            var cottage = BuildingCatalog.get("livingrealms:cottage");
            BlockPos on = road.box().getCenter();
            String err = Planning.planMap(level, s, cottage.id, on.getX() - 3, on.getZ() - 3, 0);
            check(err != null, "Map plan on a street is refused: " + err);
            // upgrades keep the front wall on the street
            s.tier = Tier.CITY;
            Plot home = s.plots.stream().filter(p -> { var t = BuildingCatalog.get(p.typeId); return t != null && t.procedural() && t.maxLevel > 1
                    && p.state == Plot.State.BUILT; }).min(Comparator.comparing(p -> p.typeId.equals("livingrealms:town_hall"))).orElseThrow();
            var f = home.blueprint().front(home.rotation);
            var before = home.box();
            String grownErr = Planning.upgrade(level, s, home);
            check(grownErr == null, home.typeId + " can be enlarged: " + grownErr);
            var after = home.box();
            int edgeBefore = switch (f) { case NORTH -> before.minZ(); case SOUTH -> before.maxZ(); case WEST -> before.minX(); default -> before.maxX(); };
            int edgeAfter = switch (f) { case NORTH -> after.minZ(); case SOUTH -> after.maxZ(); case WEST -> after.minX(); default -> after.maxX(); };
            check(edgeBefore == edgeAfter && after.getXSpan() * after.getZSpan() > before.getXSpan() * before.getZSpan(), "Enlarged " + home.typeId + " keeps its front on the street");
            // off-screen planning uses lots along the streets, facing them
            for (int i = 0; i < 6; i++) AutoBuild.survey(level, s);
            check(!s.plan().sites.isEmpty(), "Lots are surveyed for planning while away");
            var site = s.plan().sites.get(0);
            s.plots.removeIf(p -> AutoBuild.pending(p) && p != home);
            home.state = Plot.State.BUILT;
            int count = s.plots.size();
            for (int i = 0; i < 20 && s.plots.size() == count; i++) AutoBuild.away(s, level.random);
            check(s.plots.size() == count + 1, "A building is planned on a surveyed lot while away");
            Plot away = s.plots.get(s.plots.size() - 1);
            check(away.blueprint().front(away.rotation) == livingrealms.settlement.plan.PlanAudit.towardStreet(
                    s.plan().streets.get(s.plan().frontage.get(away.id)), away.box()), "The away building faces its street");
            check(!s.plan().owed.isEmpty(), "Its street is owed until the lot loads");
            var restored = Settlement.load(s.save());
            check(restored.townPlan != null && restored.townPlan.laid.equals(s.plan().laid) && restored.townPlan.frontage.equals(s.plan().frontage)
                    && restored.townPlan.owed.equals(s.plan().owed), "Town plan survives reload");
            walledTown(level, s);
        } finally { m.remove(s); }
    }

    /** Builds every queued project at once (test and tour support). */
    public static void buildQueued(ServerLevel level, Settlement s) {
        for (Plot p : new ArrayList<>(s.plots)) {
            if (!AutoBuild.pending(p) || !AutoBuild.mayWork(s, p)) continue;
            if (p.state == Plot.State.PLANNED) p.state = Plot.State.BUILDING;
            for (int k = 0; k < p.totalSteps() + 200; k++) if (Construction.work(level, s, p) != Construction.Result.PLACED) break;
            Simulation.finish(level, s, p);
        }
    }

    /** Stocks a town with plenty of everything its buildings, roads and walls use. */
    public static void stock(Settlement s) {
        for (BuildingType t : BuildingCatalog.types().values()) {
            Blueprint b = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
            if (b != null) b.bill().forEach((item, n) -> { if (s.stock.count(item) < n + 200) s.stock.force(item, n + 400); });
        }
        s.stock.force(Blueprint.costItem(Construction.foundationBlock(s)), 5000);
        var masonry = Blueprint.costItem(Procedural.masonry(s.style));
        if (masonry != null && s.stock.count(masonry) < 20000) s.stock.force(masonry, 20000);
        var parapet = Blueprint.costItem(Procedural.parapet(s.style));
        if (parapet != null && s.stock.count(parapet) < 2000) s.stock.force(parapet, 2000);
        for (var it : new net.minecraft.world.item.Item[]{net.minecraft.world.item.Items.LADDER, net.minecraft.world.item.Items.SPRUCE_PLANKS, net.minecraft.world.item.Items.GRAVEL,
                net.minecraft.world.item.Items.COBBLESTONE, net.minecraft.world.item.Items.STONE_BRICKS})
            if (s.stock.count(it) < 2000) s.stock.force(it, 2000);
    }

    /** Village rank with Fortifications: the old-town wall, its gatehouses and towers are queued, paid for and built. */
    static void walledTown(ServerLevel level, Settlement s) {
        s.tier = Tier.VILLAGE;
        s.plots.removeIf(p -> AutoBuild.pending(p));
        s.autoWalls = false;
        stock(s);
        for (int i = 0; i < 6; i++) { AutoBuild.think(level, s); buildQueued(level, s); }
        check(s.plots.stream().noneMatch(livingrealms.settlement.plan.Walls::isWallPiece), "Auto walls off: no wall is planned");
        check(livingrealms.settlement.plan.Walls.status(s).contains("off"), "Wall status says they are off");
        s.autoWalls = true;
        var line = livingrealms.settlement.plan.Walls.unlocked(s).get(0);
        var all = livingrealms.settlement.plan.Walls.pieces(line);
        int buildingsBefore = (int) s.plots.stream().filter(p -> !p.typeId.equals("road") && !livingrealms.settlement.plan.Walls.isWallPiece(p)).count();
        boolean queuedSeen = false;
        for (int i = 0; i < 300; i++) {
            stock(s);
            Simulation.recompute(s);
            s.population = Math.max(1, s.d.housing - 1);
            AutoBuild.think(level, s);
            for (Plot p : s.plots) if (livingrealms.settlement.plan.Walls.isWallPiece(p) && p.state == Plot.State.BUILDING && p.automatic) {
                queuedSeen = true;
                check(!p.blueprint().bill().isEmpty() || p.totalSteps() <= 1, "A queued wall piece has a bill of materials");
            }
            buildQueued(level, s);
            if (all.stream().allMatch(pc -> s.plots.stream().anyMatch(p -> p.blueprintKey.equals(pc.key(s)) && p.state == Plot.State.BUILT))) break;
        }
        check(queuedSeen, "Wall pieces are queued as ordinary construction projects");
        long builtPieces = all.stream().filter(pc -> s.plots.stream().anyMatch(p -> p.blueprintKey.equals(pc.key(s)) && p.state == Plot.State.BUILT)).count();
        check(builtPieces == all.size(), "The whole old-town wall is built: " + builtPieces + "/" + all.size() + " · " + livingrealms.settlement.plan.Walls.status(s));
        int buildingsAfter = (int) s.plots.stream().filter(p -> !p.typeId.equals("road") && !livingrealms.settlement.plan.Walls.isWallPiece(p)).count();
        check(buildingsAfter > buildingsBefore, "Houses and workshops keep coming between wall sections: " + buildingsBefore + " -> " + buildingsAfter);
        Simulation.recompute(s);
        check(s.d.buildings == buildingsAfter - s.plots.stream().filter(p -> !p.typeId.equals("road") && !livingrealms.settlement.plan.Walls.isWallPiece(p) && !p.standing()).count(),
                "Wall pieces do not count as buildings for rank: " + s.d.buildings);
        var flat = livingrealms.settlement.plan.Terrain.grid((x, z) -> 99, (x, z) -> false);
        var bad = livingrealms.settlement.plan.PlanAudit.walls(s, s.plan(), flat);
        check(bad.isEmpty(), "Built wall audit: " + bad);
        // gates are open, paved and on the high streets; the walk is up on the wall; towers have ladders
        int gates = 0;
        for (Plot g : livingrealms.settlement.plan.Walls.gates(s)) {
            gates++;
            BlockPos mid = null;
            for (var st : s.plan().streets) if (st.cls == livingrealms.settlement.plan.RoadClass.HIGH) {
                int t = line.a() - livingrealms.settlement.plan.TownPlan.PLAZA;
                BlockPos q = new BlockPos(st.x(t), 99, st.z(t));
                if (g.columns().contains(BlockPos.asLong(q.getX(), 0, q.getZ()))) mid = q;
            }
            check(mid != null, "Gatehouse " + g.id + " stands on a high street");
            check(Planning.ROAD_MATERIALS.containsValue(level.getBlockState(mid)), "Gate passage is paved: " + level.getBlockState(mid));
            for (int y = 100; y <= 103; y++) check(level.getBlockState(mid.atY(y)).isAir(), "Gate passage is open at y " + y);
            boolean arch = false;
            for (int y = 104; y <= 106; y++) arch |= !level.getBlockState(mid.atY(y)).isAir();
            check(arch, "The wall walk crosses over the gate");
        }
        check(gates == 4, "Four gatehouses, one per high street: " + gates);
        Plot section = s.plots.stream().filter(p -> p.typeId.equals(livingrealms.settlement.plan.Walls.WALL) && p.columns().size() > 20).findFirst().orElseThrow();
        long top = section.columns().stream().filter(k -> !level.getBlockState(new BlockPos(BlockPos.getX(k), 99 + livingrealms.settlement.plan.Walls.H, BlockPos.getZ(k))).isAir()).count();
        check(top * 2 >= section.columns().size(), "The wall stands " + livingrealms.settlement.plan.Walls.H + " high");
        boolean ladder = false;
        for (Plot t : s.plots) if (t.typeId.equals(livingrealms.settlement.plan.Walls.TOWER)) {
            var bp = t.blueprint();
            for (int i = 0; i < bp.size() && !ladder; i++) if (bp.state(i).is(Blocks.LADDER) && level.getBlockState(bp.worldPos(i, t.origin, t.rotation)).is(Blocks.LADDER)) ladder = true;
        }
        check(ladder, "Towers have ladders up to the wall walk");
        // raids: attackers gathered outside the east gate meet the defenders there
        Plot east = livingrealms.settlement.plan.Walls.gateFacing(s, s.center.getX() + 90, s.center.getZ());
        check(east != null && livingrealms.settlement.plan.Walls.side(s, east.center()).equals("east"), "Defenders hold the gate facing the attackers");
        check(livingrealms.settlement.plan.Walls.gateFacing(s, s.center.getX() + 10, s.center.getZ()) == null, "Inside the walls the defenders hold the centre");
        var restored = Settlement.load(s.save());
        check(restored.autoWalls, "Auto walls setting survives reload");
        long before = s.plots.stream().filter(livingrealms.settlement.plan.Walls::isWallPiece).count(), after = restored.plots.stream().filter(livingrealms.settlement.plan.Walls::isWallPiece).count();
        check(before == after, "Wall pieces survive reload: " + before + " / " + after);
        for (Plot p : restored.plots) if (livingrealms.settlement.plan.Walls.isWallPiece(p))
            check(p.blueprint() != null && (!p.columns().isEmpty() || p.blueprint().size() == 1), "Wall piece " + p.id + " keeps its blueprint: " + p.blueprintKey + " " + (p.blueprint() == null ? "none" : p.blueprint().size()));
        LivingRealms.LOG.info("Walled town: {} wall pieces, {} gates · {}", all.size(), gates, livingrealms.settlement.plan.Walls.status(s));
    }

    /** Test and tour support: grows a player town with automatic building, finishing each project at once. Returns plots placed. */
    public static int grow(ServerLevel level, Settlement s, int projects) {
        s.research.addAll(livingrealms.research.ResearchTree.nodes().keySet());
        s.autoBuild = true;
        if (s.plan().laid.isEmpty() && s.count("livingrealms:town_hall") > 0) AutoBuild.pave(level, s, s.plan().founding(s.radius()), true);
        int placed = 0;
        for (int i = 0; i < projects * 4 && placed < projects; i++) {
            for (BuildingType t : BuildingCatalog.types().values()) {
                Blueprint b = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
                if (b != null) b.bill().forEach((item, n) -> { if (s.stock.count(item) < n + 200) s.stock.force(item, n + 400); });
            }
            s.stock.force(Blueprint.costItem(Construction.foundationBlock(s)), 5000);
            Simulation.recompute(s);
            s.population = Math.max(1, s.d.housing - (i % 3 == 0 ? 0 : 6));
            Realms.plan(level, s);
            long before = s.plots.stream().filter(p -> !p.typeId.equals("road")).count();
            AutoBuild.think(level, s);
            for (Plot p : new ArrayList<>(s.plots)) {
                if (!AutoBuild.pending(p)) continue;
                if (p.state == Plot.State.PLANNED) p.state = Plot.State.BUILDING;
                for (int k = 0; k < p.totalSteps() + 200; k++) if (Construction.work(level, s, p) != Construction.Result.PLACED) break;
                Simulation.finish(level, s, p);
            }
            placed += (int) (s.plots.stream().filter(p -> !p.typeId.equals("road")).count() - before);
        }
        Simulation.recompute(s);
        return placed;
    }
}
