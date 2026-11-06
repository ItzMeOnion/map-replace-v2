package dev.maptooltip.client.mixin;

import dev.maptooltip.client.ReplacementManager;
import net.minecraft.client.render.MapRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Just before Minecraft draws a map, hand it to the replacement manager, which swaps in our
 * image if this map ID has a rule. Only this client sees the change; the server and the saved
 * world are untouched.
 *
 * If the map IS a replacement but its pixels are the same no matter what, nothing else changes.
 */
@Mixin(MapRenderer.class)
public abstract class MapRendererMixin {

    @Inject(
            method = "draw(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/component/type/MapIdComponent;Lnet/minecraft/item/map/MapState;ZI)V",
            at = @At("HEAD"),
            require = 0
    )
    private void maptooltip$applyReplacement(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                             MapIdComponent mapId, MapState state, boolean hidden, int light,
                                             CallbackInfo ci) {
        if (mapId != null && state != null) {
            ReplacementManager.get().applyTo(mapId.id(), state);
        }
    }
}
