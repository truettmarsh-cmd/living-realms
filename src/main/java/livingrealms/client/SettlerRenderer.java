package livingrealms.client;

import com.mojang.blaze3d.vertex.PoseStack;
import livingrealms.entity.SettlerEntity;
import livingrealms.settlement.Job;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CrossedArmsItemLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

/** Settlers look like villagers dressed for their job. */
public class SettlerRenderer extends MobRenderer<SettlerEntity, VillagerModel<SettlerEntity>> {
    static final ResourceLocation BASE = new ResourceLocation("textures/entity/villager/villager.png");
    static final ResourceLocation TYPE = new ResourceLocation("textures/entity/villager/type/plains.png");

    public SettlerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new VillagerModel<>(ctx.bakeLayer(ModelLayers.VILLAGER)), 0.5f);
        addLayer(new Outfit(this));
        addLayer(new CustomHeadLayer<>(this, ctx.getModelSet(), ctx.getItemInHandRenderer()));
        addLayer(new CrossedArmsItemLayer<>(this, ctx.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(SettlerEntity e) { return BASE; }

    static class Outfit extends RenderLayer<SettlerEntity, VillagerModel<SettlerEntity>> {
        Outfit(RenderLayerParent<SettlerEntity, VillagerModel<SettlerEntity>> parent) { super(parent); }

        @Override
        public void render(PoseStack ps, MultiBufferSource buf, int light, SettlerEntity e, float limb, float limbAmt, float partial, float age, float yaw, float pitch) {
            if (e.isInvisible()) return;
            renderColoredCutoutModel(getParentModel(), TYPE, ps, buf, light, e, 1, 1, 1);
            Job j = e.job();
            if (!j.profession.equals("none"))
                renderColoredCutoutModel(getParentModel(), new ResourceLocation("textures/entity/villager/profession/" + j.profession + ".png"), ps, buf, light, e, 1, 1, 1);
        }
    }
}
