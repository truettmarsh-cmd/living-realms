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
            check(s.plots.size()==1,"Empty queue automatically gains one building");
            Plot auto=s.plots.get(0);
            check(auto.automatic && !auto.ai && auto.typeId.equals(hall.id),"Founders choose a stockpile-funded Town Hall");
            check(auto.placed==0 && auto.paid==0,"Automatic planning does not instantly construct buildings");
            AutoBuild.think(level,s); check(s.plots.size()==1,"No runaway automatic queue");
            var cottage=BuildingCatalog.get("livingrealms:cottage");
            var cottageBp=BuildingCatalog.blueprint(cottage.blueprintKey(1,s.style));
            cottageBp.bill().forEach((item,n)->s.stock.force(item,n+500));
            String err="no site";
            for(int dx=-30;dx<=25 && err!=null;dx+=11) for(int dz=-30;dz<=25 && err!=null;dz+=11)
                err=Planning.planMap(level,s,cottage.id,1024+dx,1024+dz,1);
            check(err==null,"Map plan accepted on clear terrain: "+err);
            Plot manual=s.plots.get(1);
            check(!manual.automatic && manual.rotation==net.minecraft.world.level.block.Rotation.CLOCKWISE_90,"Map placement retains manual priority and rotation");
            check(manual.origin.getY()==99-cottage.sink,"Server selects ground height");
            check(manual.placed==0,"Map placement only queues construction");
            s.tier=Tier.CAPITAL;
            CapitalCastles.tick(level,s);
            check(s.plots.size()==2 && s.castleStatus.isEmpty(),"Automatic castle planning waits behind the existing queue");
            s.tier=Tier.CAMP;
            check(!AutoBuild.mayWork(s,auto) && Simulation.activePlots(s).equals(List.of(manual)),"Manual queue preempts automatic project");
            int stock=s.stock.total();
            check(Construction.work(level,s,auto)==Construction.Result.MISSING && auto.placed==0 && s.stock.total()==stock,"Paused automatic work spends no resources");
            check(Construction.payAhead(s,auto,100)==0 && auto.paid==0,"Offscreen work respects manual priority");
            var restored=Settlement.load(s.save());
            check(restored.autoBuild && restored.plots.get(0).automatic && !restored.plots.get(1).automatic
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
    }

    /** Test and tour support: grows a player town with automatic building, finishing each project at once. Returns plots placed. */
    public static int grow(ServerLevel level, Settlement s, int projects) {
        s.research.addAll(livingrealms.research.ResearchTree.nodes().keySet());
        s.autoBuild = true;
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
