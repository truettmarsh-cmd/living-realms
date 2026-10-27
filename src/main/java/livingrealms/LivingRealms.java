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
        MinecraftForge.EVENT_BUS.addListener(LivingRealms::giveGuide);
    }

    /** Every player gets the Living Realms Handbook (a Patchouli guide book) once, on first join. */
    private static void giveGuide(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("patchouli")) return;
        var tag = e.getEntity().getPersistentData();
        if (tag.getBoolean("livingrealms_guide")) return;
        tag.putBoolean("livingrealms_guide", true);
        var book = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation("patchouli", "guide_book"));
        if (book == null) return;
        var s = new net.minecraft.world.item.ItemStack(book);
        s.getOrCreateTag().putString("patchouli:book", MODID + ":guide");
        if (!e.getEntity().getInventory().add(s)) e.getEntity().drop(s, false);
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
}
