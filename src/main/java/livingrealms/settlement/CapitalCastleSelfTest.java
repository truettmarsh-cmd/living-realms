package livingrealms.settlement;

import livingrealms.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import java.util.UUID;

/** Paid build, central preservation, gates, collision and reload checks in the isolated test world. */
public final class CapitalCastleSelfTest {
    public static int run(ServerLevel level) {
        int n = 0;
        Settlement s = new Settlement(); s.owner = UUID.randomUUID(); s.center = new BlockPos(512, 80, 512);
        s.dim = level.dimension(); s.style = "plains"; s.d.hasTownHall = true;
        var type = BuildingCatalog.get(CapitalCastles.ID);
        check(type != null, "Castle catalog"); n++;
        var bp = BuildingCatalog.blueprint(type.blueprintKey(1,s.style));
        check(bp.sx == 41 && bp.sz == 41 && bp.beds == 4, "Castle dimensions and guard quarters"); n++;
        for (int i=0;i<bp.size();i++) check(!(bp.lx(i)>=8 && bp.lx(i)<=32 && bp.lz(i)>=8 && bp.lz(i)<=32), "No courtyard clearing"); n++;
        check(CapitalCastles.plan(level,s) != null && s.plots.isEmpty(), "Capital rank gate"); n++;
        s.tier=Tier.CAPITAL;
        check(CapitalCastles.plan(level,s) != null && s.plots.isEmpty(), "Royal charter gate"); n++;
        s.research.add("royal_charter");
        for (int x=492;x<=532;x++) for (int z=492;z<=532;z++) {
            level.getChunk(x>>4,z>>4);
            for(int y=79;y<=95;y++) level.setBlock(new BlockPos(x,y,z), (y==79?Blocks.GRASS_BLOCK:Blocks.AIR).defaultBlockState(),2);
        }
        BlockPos obstruction=new BlockPos(492,80,492);
        level.setBlock(obstruction,Blocks.CHEST.defaultBlockState(),2);
        check(CapitalCastles.plan(level,s) != null && s.plots.isEmpty(), "Existing chest protected"); n++;
        level.setBlock(obstruction,Blocks.AIR.defaultBlockState(),2);
        Plot hall=new Plot(); hall.typeId="livingrealms:town_hall"; hall.blueprintKey="proc:town_hall/1/plains";
        hall.origin=s.center.offset(-4,-1,-4); hall.state=Plot.State.BUILT; hall.id=s.nextId++; s.plots.add(hall);
        level.setBlock(s.center,Blocks.GOLD_BLOCK.defaultBlockState(),2);
        String error=CapitalCastles.plan(level,s);
        check(error==null,"Center castle planned around town hall: "+error); n++;
        Plot castle=s.plots.get(s.plots.size()-1);
        check(castle.center().getX()==512 && castle.center().getZ()==512 && castle.active(),"Exact center and active build"); n++;
        check(CapitalCastles.plan(level,s)!=null && s.plots.size()==2,"No duplicate castles"); n++;
        check(Construction.work(level,s,castle)==Construction.Result.MISSING,"Empty stock prevents free castle"); n++;
        bp.bill().forEach((item,amount)->s.stock.force(item,amount+20));
        s.stock.force(Construction.foundationBlock(s).getBlock().asItem(), castle.foundation.length);
        int before=s.stock.total();
        for(int i=0;i<castle.totalSteps()+100;i++) {
            var result=Construction.work(level,s,castle);
            check(result!=Construction.Result.MISSING,"Blueprint and foundation bill covers construction: " + castle.status);
            if(result==Construction.Result.DONE) break;
        }
        check(castle.placed>=castle.totalSteps() && s.stock.total()<before,"Paid construction completes"); n++;
        check(level.getBlockState(s.center).is(Blocks.GOLD_BLOCK),"Village center preserved"); n++;
        check(level.getBlockState(new BlockPos(512,80,532)).isAir(),"Gate remains walkable"); n++;
        check(level.getBlockState(new BlockPos(492,86,512)).is(Blocks.STONE_BRICKS),"Curtain wall built"); n++;
        Plot copy=Plot.load(castle.save());
        check(copy.blueprint().size()==bp.size() && copy.placed==castle.placed,"Castle survives reload"); n++;
        check(!CapitalCastles.overlaps(castle,hall),"Courtyard building remains independently usable"); n++;
        hall.origin=s.center.offset(-20,-1,-20);
        check(CapitalCastles.overlaps(castle,hall),"Perimeter building conflict detected"); n++;
        check(!CapitalCastles.reserved(s,508,508,516,516) && CapitalCastles.reserved(s,492,492,500,500),"AI reserves future castle perimeter only"); n++;
        check(Planning.plan(level,s,type,s.center,Rotation.NONE,false)!=null,"Off-center manual castle rejected"); n++;
        return n;
    }
    private static void check(boolean value,String text) { if(!value) throw new IllegalStateException(text); }
}
