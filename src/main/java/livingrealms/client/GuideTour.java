package livingrealms.client;

import com.mojang.logging.LogUtils;
import livingrealms.LivingRealms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/** Opt-in guide book capture (./gradlew runClient -PguideTest -Ppatchouli): every entry spread, screenshot per spread. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class GuideTour {
    private static final boolean ON = Boolean.getBoolean("livingrealms.guideTest");
    private static final Logger LOG = LogUtils.getLogger();
    private static final String SAVE = "livingrealms_guide_" + System.currentTimeMillis();
    private static final ResourceLocation BOOK = LivingRealms.id("guide");
    private static final List<Object[]> SPREADS = new ArrayList<>();
    private static int ticks = -1, waiting, next;
    private static boolean opened;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (!opened && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && ++waiting > 40) {
                opened = true;
                GameRules rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                mc.createWorldOpenFlows().createFreshLevel(SAVE, new LevelSettings(SAVE, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules,
                        WorldDataConfiguration.DEFAULT), new WorldOptions(1L, false, false),
                        access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            }
            return;
        }
        ticks++;
        if (ticks == 60) {
            boolean gotBook = mc.player.getInventory().items.stream().anyMatch(s -> s.hasTag() && BOOK.toString().equals(s.getTag().getString("patchouli:book")));
            LOG.info("Guide tour: book given on join = {}", gotBook);
            mc.getResourceManager().listResources("patchouli_books/guide/en_us/entries", p -> p.getPath().endsWith(".json")).forEach((id, res) -> {
                String path = id.getPath().substring("patchouli_books/guide/en_us/entries/".length()).replace(".json", "");
                int pages = 1;
                try (var r = res.openAsReader()) { pages = com.google.gson.JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("pages").size(); }
                catch (Exception e) { LOG.error("Guide tour: unreadable {}", id, e); }
                for (int p = 0; p < pages; p += 2) SPREADS.add(new Object[]{path, p});
            });
            SPREADS.sort((a, b) -> ((String) a[0]).compareTo((String) b[0]) * 100 + Integer.compare((int) a[1], (int) b[1]));
            LOG.info("Guide tour: {} spreads", SPREADS.size());
        }
        if (ticks > 60 && (ticks - 60) % 30 == 0) {
            int i = (ticks - 60) / 30 - 1;
            if (i > 0) shot(mc, (Object[]) SPREADS.get(i - 1));
            if (i >= SPREADS.size()) { LOG.info("Guide tour complete"); mc.stop(); return; }
            Object[] s = SPREADS.get(i);
            try {
                Object api = Class.forName("vazkii.patchouli.api.PatchouliAPI").getMethod("get").invoke(null);
                api.getClass().getMethod("openBookEntry", ResourceLocation.class, ResourceLocation.class, int.class)
                        .invoke(api, BOOK, LivingRealms.id((String) s[0]), (int) s[1]);
            } catch (ReflectiveOperationException e) { LOG.error("Guide tour: cannot open {}", s[0], e); }
        }
    }

    private static void shot(Minecraft mc, Object[] s) {
        String name = "guide_" + ((String) s[0]).replace('/', '_') + "_p" + s[1] + ".png";
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> {});
    }

    private GuideTour() {}
}
