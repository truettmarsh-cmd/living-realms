package livingrealms.client;

import net.minecraft.client.Screenshot;
import livingrealms.LivingRealms;
import livingrealms.building.*;
import livingrealms.client.ui.CityScreen;
import livingrealms.net.Net;
import livingrealms.settlement.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.File;
import java.util.UUID;

/** Explicit opt-in UI-to-server placement check in a copied test world. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class CityPlanningTour {
    private static final boolean ON=Boolean.getBoolean("livingrealms.cityPlanningTour");
    private static boolean opened,started;
    /** 0: map UI checks; 1: growing the town; 2+: ticks of the grown-town camera stage. */
    private static volatile int grown;
    private static int ticks,idle;
    private static UUID town;
    private static volatile boolean ready,verified;
    private static String shot;
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if(!ON || event.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();
        try {
            if(mc.player==null) {
                if(!opened && mc.screen instanceof TitleScreen title && ++idle>20) {
                    if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().getFileName().toString().startsWith("livingrealms-planning-ui."))
                        throw new IllegalStateException("Requires isolated UI test directory");
                    opened=true;mc.createWorldOpenFlows().loadLevel(title,"demo");
                }
                return;
            }
            if(!started && mc.getSingleplayerServer()!=null) {
                started=true;var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
                server.execute(()->setup(server.getPlayerList().getPlayer(id)));
            }
            if(grown>0) { town(mc); return; }
            if(!ready || !(mc.screen instanceof CityScreen screen))return;
            ticks++;
            if(ticks==20)shot="planning-build.png";
            if(ticks==30)screen.beginMapPlan("livingrealms:cottage");
            if(ticks==50)shot="planning-map.png";
            if(ticks==60) {
                float scale=field(screen,"mapScale"),x=field(screen,"mapOriginX"),y=field(screen,"mapOriginY");
                screen.mouseClicked(x+(20.25-CityMapOrigin.x())*scale,y+(-9.75-CityMapOrigin.z())*scale,0);
            }
            if(ticks==90) {
                var server=mc.getSingleplayerServer();server.execute(()->{
                    Settlement s=SettlementManager.get(server).get(town);
                    verified=s.plots.stream().anyMatch(p->p.typeId.equals("livingrealms:cottage") && p.origin.getX()==20 && p.origin.getZ()==-10 && !p.automatic);
                    LivingRealms.LOG.info("CITY MAP UI {}",verified?"PASS: click sent a server-validated manual construction plan":"FAILED: map click did not queue cottage");
                });
            }
            if(ticks==110)shot="planning-queued.png";
            if(ticks==130) {
                if(!verified)throw new IllegalStateException("City map packet roundtrip failed");
                LivingRealms.LOG.info("CITY PLANNING UI COMPLETE");
                mc.setScreen(null);grown=1;
                var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
                server.execute(()->growTown(server.getPlayerList().getPlayer(id)));
            }
        }catch(Throwable e){LivingRealms.LOG.error("CITY PLANNING UI FAILED",e);mc.stop();}
    }
    /** Grown-town stage: villagers plan a whole town with Auto build, then overhead, street-level and map views. */
    private static void town(Minecraft mc) throws Exception {
        if(grown<2)return;
        int t=grown++;var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
        if(t==2)server.execute(()->camera(server.getPlayerList().getPlayer(id),0.5,185,0.5,180,89.5f));
        if(t==120)shot="planning-town-top.png";
        if(t==130)server.execute(()->camera(server.getPlayerList().getPlayer(id),0,150,-70,0,52));
        if(t==250)shot="planning-town-north.png";
        if(t==260)server.execute(()->camera(server.getPlayerList().getPlayer(id),62,128,62,135,30));
        if(t==380)shot="planning-town-southeast.png";
        if(t==390)server.execute(()->{var p=server.getPlayerList().getPlayer(id);Net.openManager(p,SettlementManager.get(server).get(town),"build");});
        if(t==420 && mc.screen instanceof CityScreen screen) {
            var tab=Class.forName("livingrealms.client.ui.CityScreen$Tab");
            var m=CityScreen.class.getDeclaredMethod("switchTab",tab);m.setAccessible(true);
            m.invoke(screen,Enum.valueOf(tab.asSubclass(Enum.class),"MAP"));
        }
        if(t==460)shot="planning-town-map.png";
        if(t==480){LivingRealms.LOG.info("CITY PLANNING TOUR COMPLETE");mc.stop();}
    }
    private static void camera(ServerPlayer player,double x,double y,double z,float yaw,float pitch){
        player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        player.connection.teleport(x,y,z,yaw,pitch);
    }
    private static void growTown(ServerPlayer player) {
        try {
            var level=player.serverLevel();
            Settlement s=SettlementManager.get(player.server).get(town);
            s.tier=Tier.TOWN;
            int n=CityPlanningSelfTest.grow(level,s,36);
            LivingRealms.LOG.info("CITY LAYOUT {} buildings placed, {} plots in all, radius {}",n,s.plots.size(),s.radius());
            for(Plot p:s.plots){
                var b=p.box();var c=p.center();
                LivingRealms.LOG.info("CITY LAYOUT {} at {},{} size {}x{} rot {} dist {}",p.typeId,b.minX(),b.minZ(),b.getXSpan(),b.getZSpan(),p.rotation,
                        (int)Math.sqrt(c.distSqr(new BlockPos(s.center.getX(),c.getY(),s.center.getZ()))));
            }
            level.setDayTime(6000);
            grown=2;
        }catch(Throwable e){LivingRealms.LOG.error("CITY PLANNING UI FAILED town",e);Minecraft.getInstance().execute(()->Minecraft.getInstance().stop());}
    }
    private static float field(CityScreen s,String name)throws Exception {
        var f=CityScreen.class.getDeclaredField(name);f.setAccessible(true);return f.getFloat(s);
    }
    private static final class CityMapOrigin {
        static int x(){return livingrealms.client.ui.CityMap.originX;}
        static int z(){return livingrealms.client.ui.CityMap.originZ;}
    }
    private static void setup(ServerPlayer player) {
        try {
            var level=player.serverLevel();
            for(int x=-112;x<=112;x++)for(int z=-112;z<=112;z++)for(int y=96;y<=130;y++)
                level.setBlock(new BlockPos(x,y,z),(y<=99?(y==99?Blocks.GRASS_BLOCK:Blocks.DIRT):Blocks.AIR).defaultBlockState(),2);
            level.setDayTime(6000);
            Settlement s=new Settlement();s.owner=player.getUUID();s.ownerName=player.getGameProfile().getName();s.name="City Planning Preview";
            s.center=new BlockPos(0,100,0);s.tier=Tier.TOWN;s.autoBuild=false;
            SettlementManager.get(player.server).add(s);town=s.id;
            for(String id:new String[]{"town_hall","cottage"}){
                var t=BuildingCatalog.get("livingrealms:"+id);var bp=BuildingCatalog.blueprint(t.blueprintKey(1,s.style));
                bp.bill().forEach((item,n)->s.stock.force(item,n+1000));
            }
            String error=Planning.plan(level,s,BuildingCatalog.get("livingrealms:town_hall"),new BlockPos(-10,99,-10),Rotation.NONE,false);
            if(error!=null)throw new IllegalStateException(error);
            Plot hall=s.plots.get(0);
            for(int i=0;i<hall.totalSteps()+100;i++)if(Construction.work(level,s,hall)==Construction.Result.DONE)break;
            hall.state=Plot.State.BUILT;
            Simulation.recompute(s);
            player.teleportTo(level,0,100,20,180,0);
            Net.openManager(player,s,"build");ready=true;
        }catch(Throwable e){LivingRealms.LOG.error("CITY PLANNING UI FAILED setup",e);Minecraft.getInstance().execute(()->Minecraft.getInstance().stop());}
    }
    @SubscribeEvent
    public static void render(TickEvent.RenderTickEvent event){
        if(!ON || event.phase!=TickEvent.Phase.END || shot==null)return;
        var mc=Minecraft.getInstance();String name=shot;shot=null;
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){
            File dir=new File(mc.gameDirectory,"screenshots");dir.mkdirs();image.writeToFile(new File(dir,name));
        }catch(Exception e){LivingRealms.LOG.error("CITY PLANNING UI FAILED screenshot",e);mc.stop();}
    }
}
