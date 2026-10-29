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
        slopeLot(level, owner.getUUID());
        grownTown(level, owner, stranger);
        styles(level, owner);
    }

    // ------------------------------------------------------------------ the town's look follows its biome

    static void styles(ServerLevel level, net.minecraft.server.level.ServerPlayer owner) {
        String[][] map = {{"minecraft:plains", "plains"}, {"minecraft:sunflower_plains", "plains"}, {"minecraft:forest", "plains"},
                {"minecraft:desert", "desert"}, {"minecraft:badlands", "desert"}, {"minecraft:eroded_badlands", "desert"},
                {"minecraft:savanna", "savanna"}, {"minecraft:windswept_savanna", "savanna"}, {"minecraft:jungle", "savanna"},
                {"minecraft:taiga", "taiga"}, {"minecraft:old_growth_spruce_taiga", "taiga"}, {"minecraft:swamp", "taiga"},
                {"minecraft:windswept_hills", "taiga"}, {"minecraft:snowy_taiga", "snowy"}, {"minecraft:snowy_plains", "snowy"},
                {"minecraft:ice_spikes", "snowy"}, {"minecraft:grove", "snowy"}, {"minecraft:frozen_peaks", "snowy"}};
        for (String[] b : map) check(Procedural.styleFamily(b[0]).equals(b[1]), b[0] + " builds in the " + b[1] + " style, not " + Procedural.styleFamily(b[0]));
        // founding: the charter takes the style of the biome at its centre
        var m = SettlementManager.get(level.getServer());
        BlockPos at = new BlockPos(6000, 0, -6000);
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        at = new BlockPos(at.getX(), Construction.ground(level, at.getX(), at.getZ()) + 1, at.getZ());
        level.setBlock(at, livingrealms.Registry.CHARTER.get().defaultBlockState(), 3);
        livingrealms.Registry.CHARTER.get().setPlacedBy(level, at, level.getBlockState(at), owner, new net.minecraft.world.item.ItemStack(livingrealms.Registry.CHARTER_ITEM.get()));
        Settlement founded = m.at(level.dimension(), at);
        check(founded != null && founded.center.equals(at), "A placed charter founds a town");
        String biome = level.getBiome(at).unwrapKey().map(k -> k.location().toString()).orElse("?");
        check(founded.style.equals(Procedural.biomeStyle(level, at)) && Procedural.FAMILIES.contains(Procedural.styleFamily(founded.style))
                && founded.style.equals("minecraft:" + Procedural.styleFamily(founded.style)), "Founding takes the biome's style: " + founded.style + " on " + biome);
        if (!biome.contains("river") && !biome.contains("beach") && !biome.contains("ocean"))
            check(Procedural.styleFamily(founded.style).equals(Procedural.styleFamily(biome)), "The centre's biome decides: " + founded.style + " on " + biome);
        m.remove(founded);
        level.removeBlock(at, false);
        // a snowy-taiga town only ever considers its own style's templates (or, failing that, taiga ones), never the desert's
        long desertTemplates = BuildingCatalog.types().values().stream().filter(t -> !t.procedural() && Procedural.styleFamily(t.style).equals("desert")).count();
        check(desertTemplates > 0, "The catalog has desert templates to keep out");
        Map<String, Integer> byStyle = new TreeMap<>();
        for (BuildingType t : BuildingCatalog.types().values()) if (!t.procedural())
            byStyle.merge((BuildingCatalog.excludedStyle(t.style) ? "excluded" : Procedural.styleFamily(t.style)) + " " + t.style, 1, Integer::sum);
        LivingRealms.LOG.info("City planning test: templates by family and style {}", byStyle);
        for (String style : new String[]{"minecraft:snowy_taiga", "minecraft:snowy"}) {
            Settlement snowy = new Settlement(); snowy.owner = owner.getUUID(); snowy.center = new BlockPos(6000, 100, 6000); snowy.name = "Snow test";
            snowy.style = style; snowy.tier = Tier.CAPITAL; snowy.d.hasTownHall = true;
            snowy.research.addAll(livingrealms.research.ResearchTree.nodes().keySet());
            Set<String> seen = new HashSet<>(), wrong = new TreeSet<>();
            int templates = 0;
            for (int i = 0; i < 200; i++) for (BuildingType t : AutoBuild.candidates(snowy, level.random)) {
                String fam = Procedural.styleFamily(t.style);
                if (!t.procedural() && !fam.equals("snowy") && !fam.equals("taiga")) wrong.add(t.id);
                if (!t.procedural()) { templates++; seen.add(fam); }
            }
            check(wrong.isEmpty(), style + " town considered " + wrong);
            check(templates > 0 && seen.contains("snowy"), "A " + style + " town still builds snowy village houses");
            String cottage = BuildingCatalog.get("livingrealms:cottage").blueprintKey(1, snowy.style);
            check(cottage.endsWith("/snowy"), "Procedural buildings take the snowy palette: " + cottage);
        }
        // the style map: mods' styles filed by data, festive and far-off styles never chosen by the villagers
        check(Procedural.styleFamily("kaisyn:meadow_swiss").equals("taiga") && Procedural.styleFamily("ctov:mountain_alpine").equals("snowy")
                && Procedural.styleFamily("ctov:mesa").equals("desert") && Procedural.styleFamily("minecraft:cherry_grove").equals("plains"),
                "The style map files mods' styles");
        BuildingType festive = new BuildingType("tpl/ctov/test", "Test", Category.HOUSING, "ctov:halloween", "Halloween", List.of("tpl:none"), 1, 1, null, -1, 0, "Test", null);
        BuildingType plainsCtov = new BuildingType("tpl/ctov/test2", "Test", Category.HOUSING, "ctov:plains", "Plains", List.of("tpl:none"), 1, 1, null, -1, 0, "Test", null);
        check(!BuildingCatalog.fits(festive, "minecraft:plains") && BuildingCatalog.fits(plainsCtov, "minecraft:plains")
                && !BuildingCatalog.fits(plainsCtov, "minecraft:snowy"), "Excluded styles are never auto-built; other mods' styles are, in their family");
        // template safety: spawners are never copied, a pool-less edge jigsaw is the entrance
        CompoundTag tpl = new CompoundTag();
        var size = new net.minecraft.nbt.ListTag(); for (int v : new int[]{5, 3, 5}) size.add(net.minecraft.nbt.IntTag.valueOf(v)); tpl.put("size", size);
        var pal = new net.minecraft.nbt.ListTag();
        pal.add(net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.SPAWNER.defaultBlockState()));
        pal.add(net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.JIGSAW.defaultBlockState().setValue(net.minecraft.world.level.block.JigsawBlock.ORIENTATION, net.minecraft.core.FrontAndTop.WEST_UP)));
        pal.add(net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.COBBLESTONE.defaultBlockState()));
        tpl.put("palette", pal);
        var blocks = new net.minecraft.nbt.ListTag();
        int[][] cells = {{2, 1, 2, 0}, {0, 1, 3, 1}, {0, 0, 0, 2}, {4, 0, 4, 2}};
        for (int[] b : cells) {
            CompoundTag bt = new CompoundTag(); var pos = new net.minecraft.nbt.ListTag();
            for (int k = 0; k < 3; k++) pos.add(net.minecraft.nbt.IntTag.valueOf(b[k]));
            bt.put("pos", pos); bt.putInt("state", b[3]);
            if (b[3] == 1) { CompoundTag jn = new CompoundTag(); jn.putString("pool", "minecraft:empty"); jn.putString("name", "minecraft:jungle_house"); jn.putString("final_state", "minecraft:air"); bt.put("nbt", jn); }
            blocks.add(bt);
        }
        tpl.put("blocks", blocks);
        Blueprint read = Blueprint.fromTemplate("tpl:test", tpl);
        boolean spawner = false;
        for (int i = 0; i < read.size(); i++) spawner |= read.state(i).is(Blocks.SPAWNER);
        check(!spawner, "Template spawners are not copied");
        check(read.front(net.minecraft.world.level.block.Rotation.NONE) == net.minecraft.core.Direction.WEST, "An edge jigsaw to an empty pool marks the entrance: " + read.front(net.minecraft.world.level.block.Rotation.NONE));
        check(Procedural.masonry("minecraft:desert").is(Blocks.CUT_SANDSTONE) && Procedural.masonry("minecraft:snowy").is(Blocks.STONE_BRICKS),
                "Walls are of the style's masonry");
        check(livingrealms.settlement.plan.RoadClass.HIGH.material(Tier.CITY, "minecraft:desert").equals("sandstone")
                && livingrealms.settlement.plan.RoadClass.STREET.material(Tier.CITY, "minecraft:snowy").equals("gravel")
                && Planning.ROAD_MATERIALS.containsKey("sandstone"), "Streets are paved in the style's materials");
        // saves from before the style was kept, and those that kept a biome id, still load
        Settlement old = new Settlement(); old.owner = owner.getUUID(); old.center = new BlockPos(6000, 100, 6000); old.name = "Old save";
        CompoundTag tag = old.save(); tag.remove("style");
        Settlement loaded = Settlement.load(tag);
        check(loaded.style.equals("minecraft:plains") && Procedural.styleFamily(loaded.style).equals("plains"), "A save without a style loads as plains: " + loaded.style);
        old.style = "minecraft:snowy_taiga";
        check(Settlement.load(old.save()).style.equals("minecraft:snowy_taiga"), "A saved biome style survives reload");
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

    static long buildings(Settlement s) { return s.plots.stream().filter(p -> !p.typeId.equals("road") && !livingrealms.settlement.plan.Walls.isWallPiece(p)).count(); }

    /** Plans and builds every wall piece the town has unlocked, one after another (tour support). Returns pieces planned. */
    public static int buildWalls(ServerLevel level, Settlement s) { return buildWalls(level, s, Integer.MAX_VALUE); }

    /** As {@link #buildWalls(ServerLevel, Settlement)}, but only the first {@code built} pieces are built; the rest stay queued. */
    public static int buildWalls(ServerLevel level, Settlement s, int built) {
        int n = 0;
        s.autoWalls = true;
        for (int i = 0; i < 400; i++) {
            stock(s);
            if (!AutoBuild.walls(level, s)) break;
            if (n < built) buildQueued(level, s);
            n++;
        }
        if (n <= built) buildQueued(level, s);
        Simulation.recompute(s);
        return n;
    }

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
        // hills: lots of up to 6 blocks' relief are levelled (cut and fill), the hall levels the middle, streets stop short of cliffs
        var steep = livingrealms.settlement.plan.Terrain.grid((x, z) -> 64 + (x - 5000) / 3, (x, z) -> false);
        List<String> hill = plannedTown(steep, c, owner, 8);
        long hillBuildings = hill.stream().filter(e -> { var t = BuildingCatalog.get(e.substring(0, e.indexOf('@'))); return t != null && t.category != Category.WALL; }).count();
        long flatBuildings = a.stream().filter(e -> { var t = BuildingCatalog.get(e.substring(0, e.indexOf('@'))); return t != null && t.category != Category.WALL; }).count();
        LivingRealms.LOG.info("City plan test: {} buildings on the 1-in-3 slope, {} on the flat", hillBuildings, flatBuildings);
        check(hillBuildings >= 20 && hillBuildings * 10 >= flatBuildings * 7, "Plan: a town on a 1-in-3 slope still fills its streets: " + hillBuildings + " of " + flatBuildings + " on the flat");
        check(hill.get(0).startsWith("livingrealms:town_hall"), "Plan: the hall levels the sloping middle");
        check(hill.equals(plannedTown(steep, c, owner, 8)), "Plan: the same slope town every time");
        var probe = new livingrealms.settlement.plan.TownPlan(c, 1);
        var cliff = livingrealms.settlement.plan.Terrain.grid((x, z) -> x >= 5060 ? 94 : 64, (x, z) -> false);
        int east = probe.reach(cliff, probe.streets.get(7), Tier.CAPITAL.radius, new HashMap<>()), west = probe.reach(cliff, probe.streets.get(6), Tier.CAPITAL.radius, new HashMap<>());
        check(east > 20 && east < 45, "Plan: the east high street stops short of a 30-block cliff: " + east);
        check(west == probe.open(probe.streets.get(6), Tier.CAPITAL.radius), "Plan: the west high street runs on: " + west);
        var lake = livingrealms.settlement.plan.Terrain.grid((x, z) -> 64, (x, z) -> x >= 5030 && x <= 5070);
        var memo = new HashMap<Integer, Integer>();
        int toLake = probe.reach(lake, probe.streets.get(7), Tier.CAPITAL.radius, memo);
        check(toLake >= 0 && toLake < 15, "Plan: a high street stops at a lake: " + toLake);
        boolean ringSkipped = false;
        for (var st : probe.streets) if (st.parent == 7 && st.parentAt > toLake) { check(probe.reach(lake, st, Tier.CAPITAL.radius, memo) == -1, "Plan: ring street " + st.id + " beyond the lake is skipped"); ringSkipped = true; }
        check(ringSkipped, "Plan: ring streets off a cut-short high street are skipped");
        int[] profile = new int[40];
        for (int i = 0; i < 40; i++) profile[i] = i < 20 ? 64 : i < 34 ? 70 : 70 + (i - 33) / 2;
        profile[8] = 69;
        int[] graded = livingrealms.settlement.plan.Street.grade(profile);
        int worst = 0;
        for (int i = 0; i < 40; i++) {
            if (i > 0) check(Math.abs(graded[i] - graded[i - 1]) <= 1, "Graded road steps one block at most: " + Arrays.toString(graded));
            if (i != 8) worst = Math.max(worst, Math.abs(graded[i] - profile[i]));
        }
        check(worst <= 3 && graded[0] == 64 && graded[8] <= 65 && graded[39] == profile[39] && graded[30] == 70,
                "Graded road: a 6-block cliff becomes a ramp, a lone bump is levelled, gentle ground is followed: " + Arrays.toString(graded));
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

    /**
     * A cottage on a lot with 4 blocks of relief in the world: accepted, dug out on the high side, built up on the low
     * side; built, it stands on solid ground with its door clear and level with the ground outside; a road over a
     * 4-block cliff steps one block at a time.
     */
    static void slopeLot(ServerLevel level, UUID owner) {
        BlockPos c = new BlockPos(1400, 100, 1400);
        java.util.function.IntUnaryOperator gy = x -> 96 + Math.max(0, Math.min(8, (x - 1396) / 2)) + (x >= 1408 ? 4 : 0);
        for (int x = c.getX() - 30; x <= c.getX() + 30; x++) for (int z = c.getZ() - 30; z <= c.getZ() + 30; z++) {
            level.getChunk(x >> 4, z >> 4);
            int g = gy.applyAsInt(x);
            for (int y = 88; y <= 120; y++) level.setBlock(new BlockPos(x, y, z), (y < g ? Blocks.DIRT : y == g ? Blocks.GRASS_BLOCK : Blocks.AIR).defaultBlockState(), 2);
        }
        Settlement s = new Settlement(); s.owner = owner; s.center = c; s.name = "Slope test";
        s.research.addAll(livingrealms.research.ResearchTree.nodes().keySet());
        var m = SettlementManager.get(level.getServer()); m.add(s);
        try {
            stock(s);
            s.d.hasTownHall = true;
            var cottage = BuildingCatalog.get("livingrealms:cottage");
            var bp = BuildingCatalog.blueprint(cottage.blueprintKey(1, s.style));
            var rot = bp.facing(net.minecraft.core.Direction.SOUTH);
            int w = bp.width(rot), d = bp.depth(rot), x0 = 1398, z0 = 1395;
            var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(x0, 0, z0, x0 + w - 1, 0, z0 + d - 1);
            int relief = gy.applyAsInt(box.maxX()) - gy.applyAsInt(box.minX());
            check(relief >= 4, "Slope lot has 4+ blocks of relief: " + relief);
            BlockPos at = s.plan().ground(new livingrealms.settlement.plan.WorldTerrain(level), cottage, bp, rot, box);
            check(at != null, "A lot with " + relief + " blocks of relief is accepted for levelling");
            check(at.getY() > gy.applyAsInt(box.minX()) && at.getY() < gy.applyAsInt(box.maxX()), "The floor goes in between the low and high sides: " + at.getY());
            String err = Planning.plan(level, s, cottage, at, rot, false);
            check(err == null, "Slope lot plan accepted: " + err);
            Plot p = s.plots.get(s.plots.size() - 1);
            check(p.cut.length > 0 && p.foundation.length > 0, "Levelling is part of the project: " + p.cut.length + " dug, " + p.foundation.length + " built up");
            var restored = Settlement.load(s.save());
            check(restored.plots.get(restored.plots.size() - 1).cut.length == p.cut.length, "Earthworks survive reload");
            int y = p.origin.getY(), dug = p.cut.length, built = p.foundation.length;
            buildQueued(level, s);
            check(p.state == Plot.State.BUILT, "Slope cottage built: " + p.state);
            var b = p.box();
            for (int[] k : new int[][]{{b.minX(), b.minZ()}, {b.maxX(), b.minZ()}, {b.minX(), b.maxZ()}, {b.maxX(), b.maxZ()}})
                check(!level.getBlockState(new BlockPos(k[0], y - 1, k[1])).canBeReplaced(), "No floating corner at " + k[0] + "," + k[1]);
            for (int x = b.minX() - 1; x <= b.maxX() + 1; x += b.getXSpan() + 1) for (int z = b.minZ(); z <= b.maxZ(); z++) {
                check(level.getBlockState(new BlockPos(x, y + 1, z)).isAir() && level.getBlockState(new BlockPos(x, y + 2, z)).isAir(), "Walls not buried at " + x + "," + z);
                check(!level.getBlockState(new BlockPos(x, y, z)).canBeReplaced(), "Margin level with the floor at " + x + "," + z);
            }
            BlockPos door = bp.doorWorld(p.origin, p.rotation), out = door.relative(bp.front(p.rotation));
            check(level.getBlockState(door).getBlock() instanceof net.minecraft.world.level.block.DoorBlock, "Door stands at " + door);
            check(level.getBlockState(out).isAir() && level.getBlockState(out.above()).isAir() && !level.getBlockState(out.below()).canBeReplaced(), "Door opens onto a level apron at " + out);
            check(level.getBlockState(new BlockPos(b.maxX() + 2, y + 1, (b.minZ() + b.maxZ()) / 2)).is(Construction.foundationBlock(s).getBlock()), "Retaining wall on the high side");
            // a lane over the ramp and a 4-block cliff
            check(Planning.road(level, s, new BlockPos(1390, 0, 1389), new BlockPos(1414, 0, 1389), 1, "cobblestone") == null, "Lane over the cliff accepted");
            buildQueued(level, s);
            int prev = Integer.MIN_VALUE;
            for (int x = 1390; x <= 1414; x++) {
                int top = Construction.ground(level, x, 1389);
                check(level.getBlockState(new BlockPos(x, top, 1389)).is(Blocks.COBBLESTONE), "Lane paved at " + x);
                if (prev != Integer.MIN_VALUE) check(Math.abs(top - prev) <= 1, "Lane steps one block at most at " + x + ": " + prev + " -> " + top);
                prev = top;
            }
            LivingRealms.LOG.info("Slope lot: relief {}, floor {}, {} dug, {} built up", relief, y, dug, built);
        } finally { m.remove(s); }
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
            stock(s);
            Simulation.recompute(s);
            s.population = Math.max(1, s.d.housing - (i % 3 == 0 ? 0 : 6));
            Realms.plan(level, s);
            long before = buildings(s);
            AutoBuild.think(level, s);
            buildQueued(level, s);
            placed += (int) (buildings(s) - before);
        }
        Simulation.recompute(s);
        return placed;
    }
}
