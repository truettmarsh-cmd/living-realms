package livingrealms;

import com.mojang.logging.LogUtils;
import livingrealms.building.BuildingCatalog;
import livingrealms.net.Net;
import livingrealms.research.ResearchTree;
import livingrealms.smithing.Smithing;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(LivingRealms.MODID)
public class LivingRealms {
    public static final String MODID = "livingrealms";
    public static final Logger LOG = LogUtils.getLogger();

    public LivingRealms() {
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.OnDatapackSyncEvent e) -> {
            if (e.getPlayer() == null) {
                Smithing.discover(e.getPlayerList().getServer());
                Net.sendCatalogToAll(e.getPlayerList().getServer());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStartedEvent e) -> Smithing.discover(e.getServer()));
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        Registry.register(modBus);
        modBus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(Net::register));
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        MinecraftForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> {
            e.addListener(ResearchTree.INSTANCE);
            e.addListener(Smithing.INSTANCE);
            e.addListener(BuildingCatalog.RELOADER);
        });
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
}
