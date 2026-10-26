package livingrealms.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import livingrealms.LivingRealms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** A top-down terrain picture of the town, sampled from the chunks the client has loaded. */
public final class CityMap {
    public static final ResourceLocation TEX = LivingRealms.id("city_map");
    private static DynamicTexture texture;
    public static int size, originX, originZ;
    private static long builtAt;
    private static BlockPos builtFor = BlockPos.ZERO;

    /** (Re)builds the picture for a square of the given radius around center, at most every few seconds. */
    public static void ensure(BlockPos center, int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        int s = Math.min(radius * 2 + 1, 352);
        long now = mc.level.getGameTime();
        if (texture != null && size == s && builtFor.equals(center) && now - builtAt < 100) return;
        builtAt = now;
        builtFor = center;
        if (texture == null || size != s) {
            if (texture != null) texture.close();
            texture = new DynamicTexture(s, s, false);
            mc.getTextureManager().register(TEX, texture);
            size = s;
        }
        originX = center.getX() - s / 2;
        originZ = center.getZ() - s / 2;
        NativeImage img = texture.getPixels();
        if (img == null) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int[] prevRow = new int[s];
        for (int pz = 0; pz < s; pz++) {
            for (int px = 0; px < s; px++) {
                int x = originX + px, z = originZ + pz;
                if (!mc.level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
                    img.setPixelRGBA(px, pz, 0xFF1A1714);
                    prevRow[px] = Integer.MIN_VALUE;
                    continue;
                }
                int y = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                m.set(x, y, z);
                var state = mc.level.getBlockState(m);
                MapColor mcol = state.getMapColor(mc.level, m);
                MapColor.Brightness b = MapColor.Brightness.NORMAL;
                if (mcol == MapColor.WATER) {
                    int depth = 0;
                    while (depth < 10 && mc.level.getBlockState(m.move(0, -1, 0)).getFluidState().isSource()) depth++;
                    b = depth > 6 ? MapColor.Brightness.LOW : depth > 2 ? MapColor.Brightness.NORMAL : MapColor.Brightness.HIGH;
                } else if (prevRow[px] != Integer.MIN_VALUE && pz > 0) {
                    b = y > prevRow[px] ? MapColor.Brightness.HIGH : y < prevRow[px] ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                }
                prevRow[px] = y;
                int col = mcol == MapColor.NONE ? 0xFF201C18 : mcol.calculateRGBColor(b);
                img.setPixelRGBA(px, pz, col | 0xFF000000);
            }
        }
        texture.upload();
    }

    private CityMap() {}
}
