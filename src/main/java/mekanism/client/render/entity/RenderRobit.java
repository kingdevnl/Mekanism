package mekanism.client.render.entity;

import javax.annotation.Nonnull;
import mekanism.client.model.ModelRobit;
import mekanism.common.entity.EntityRobit;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class RenderRobit extends MobRenderer<EntityRobit> {

    public RenderRobit(EntityRendererManager renderManager) {
        super(renderManager, new ModelRobit(), 0.5F);
    }

    @Override
    protected ResourceLocation getEntityTexture(@Nonnull EntityRobit robit) {
        if ((Math.abs(robit.posX - robit.prevPosX) + Math.abs(robit.posX - robit.prevPosX)) > 0.001) {
            if (robit.ticksExisted % 3 == 0) {
                robit.texTick = !robit.texTick;
            }
        }
        return MekanismUtils.getResource(ResourceType.RENDER, "Robit" + (robit.texTick ? "2" : "") + ".png");
    }
}