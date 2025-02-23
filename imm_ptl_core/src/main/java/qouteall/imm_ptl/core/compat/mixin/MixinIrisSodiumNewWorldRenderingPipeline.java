package qouteall.imm_ptl.core.compat.mixin;

import net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.ExperimentalIrisPortalRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Mixin(NewWorldRenderingPipeline.class)
public class MixinIrisSodiumNewWorldRenderingPipeline {
    @Inject(
        method = "finalizeLevelRendering", at = @At("HEAD"), cancellable = true
    )
    private void onFinalizeLevelRendering(CallbackInfo ci) {
        if (IPCGlobal.renderer instanceof ExperimentalIrisPortalRenderer) {
            if (PortalRendering.isRendering()) {
                ci.cancel();
            }
        }
    }
    
//    @Inject(
//        method = "beginTranslucents",
//        at = @At("HEAD")
//    )
//    private void onBeginTranslucents(CallbackInfo ci) {
//        System.out.println("Iris injection");
//    }
}
