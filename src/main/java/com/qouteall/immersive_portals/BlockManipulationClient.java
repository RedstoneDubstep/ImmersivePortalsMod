package com.qouteall.immersive_portals;

import com.qouteall.immersive_portals.commands.MyCommandServer;
import com.qouteall.immersive_portals.portal.Portal;
import com.qouteall.immersive_portals.portal.PortalPlaceholderBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.dimension.DimensionType;

public class BlockManipulationClient {
    public static DimensionType remotePointedDim;
    public static HitResult remoteHitResult;
    public static boolean isContextSwitched = false;
    
    public static boolean isPointingToRemoteBlock() {
        return remotePointedDim != null;
    }
    
    public static void onPointedBlockUpdated(float partialTicks) {
        remotePointedDim = null;
        remoteHitResult = null;
        
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        
        float reachDistance = mc.interactionManager.getReachDistance();
        
        MyCommandServer.getPlayerPointingPortalRaw(
            mc.player, partialTicks, reachDistance, true
        ).ifPresent(pair -> {
            double distanceToPortalPointing = pair.getSecond().distanceTo(cameraPos);
            if (distanceToPortalPointing < getCurrentTargetDistane()) {
                updateTargetedBlockThroughPortal(
                    cameraPos,
                    mc.player.getRotationVec(partialTicks),
                    mc.player.dimension,
                    distanceToPortalPointing,
                    reachDistance,
                    pair.getFirst()
                );
            }
        });
    }
    
    private static double getCurrentTargetDistane() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        
        if (mc.crosshairTarget == null) {
            return 23333;
        }
        
        //pointing to placeholder block does not count
        if (mc.crosshairTarget instanceof BlockHitResult) {
            BlockHitResult hitResult = (BlockHitResult) mc.crosshairTarget;
            BlockPos hitPos = hitResult.getBlockPos();
            if (mc.world.getBlockState(hitPos).getBlock() == PortalPlaceholderBlock.instance) {
                return 23333;
            }
        }
        
        return cameraPos.distanceTo(mc.crosshairTarget.getPos());
    }
    
    private static void updateTargetedBlockThroughPortal(
        Vec3d cameraPos,
        Vec3d viewVector,
        DimensionType playerDimension,
        double beginDistance,
        double endDistance,
        Portal portal
    ) {
        MinecraftClient mc = MinecraftClient.getInstance();
        
        Vec3d from = portal.applyTransformationToPoint(
            cameraPos.add(viewVector.multiply(beginDistance))
        );
        Vec3d to = portal.applyTransformationToPoint(
            cameraPos.add(viewVector.multiply(endDistance))
        );
        
        RayTraceContext context = new RayTraceContext(
            from,
            to,
            RayTraceContext.ShapeType.OUTLINE,
            RayTraceContext.FluidHandling.NONE,
            mc.player
        );
        
        ClientWorld world = CGlobal.clientWorldLoader.getOrCreateFakedWorld(
            portal.dimensionTo
        );
        
        remoteHitResult = BlockView.rayTrace(
            context,
            (rayTraceContext, blockPos) -> {
                BlockState blockState = world.getBlockState(blockPos);
                
                //don't stop at placeholder block
                if (blockState.getBlock() == PortalPlaceholderBlock.instance) {
                    return null;
                }
                
                FluidState fluidState = world.getFluidState(blockPos);
                Vec3d vec3d = rayTraceContext.getStart();
                Vec3d vec3d2 = rayTraceContext.getEnd();
                VoxelShape solidShape = rayTraceContext.getBlockShape(blockState, world, blockPos);
                BlockHitResult blockHitResult = world.rayTraceBlock(
                    vec3d, vec3d2, blockPos, solidShape, blockState
                );
                VoxelShape fluidShape = rayTraceContext.getFluidShape(fluidState, world, blockPos);
                BlockHitResult blockHitResult2 = fluidShape.rayTrace(vec3d, vec3d2, blockPos);
                double d = blockHitResult == null ? Double.MAX_VALUE :
                    rayTraceContext.getStart().squaredDistanceTo(blockHitResult.getPos());
                double e = blockHitResult2 == null ? Double.MAX_VALUE :
                    rayTraceContext.getStart().squaredDistanceTo(blockHitResult2.getPos());
                return d <= e ? blockHitResult : blockHitResult2;
            },
            (rayTraceContext) -> {
                Vec3d vec3d = rayTraceContext.getStart().subtract(rayTraceContext.getEnd());
                return BlockHitResult.createMissed(
                    rayTraceContext.getEnd(),
                    Direction.getFacing(vec3d.x, vec3d.y, vec3d.z),
                    new BlockPos(rayTraceContext.getEnd())
                );
            }
        );
        if (remoteHitResult != null) {
            mc.crosshairTarget = null;
            remotePointedDim = portal.dimensionTo;
        }
    }
    
    public static void myHandleBlockBreaking(boolean isKeyPressed) {
        MinecraftClient mc = MinecraftClient.getInstance();
        
        if (!mc.player.isUsingItem()) {
            if (isKeyPressed && isPointingToRemoteBlock()) {
                BlockHitResult blockHitResult = (BlockHitResult) remoteHitResult;
                BlockPos blockPos = blockHitResult.getBlockPos();
                ClientWorld remoteWorld =
                    CGlobal.clientWorldLoader.getOrCreateFakedWorld(remotePointedDim);
                if (!remoteWorld.getBlockState(blockPos).isAir()) {
                    Direction direction = blockHitResult.getSide();
                    if (myUpdateBlockBreakingProgress(mc, blockPos, direction)) {
                        mc.particleManager.addBlockBreakingParticles(blockPos, direction);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                }
                
            }
            else {
                mc.interactionManager.cancelBlockBreaking();
            }
        }
    }
    
    //hacky switch
    public static boolean myUpdateBlockBreakingProgress(
        MinecraftClient mc,
        BlockPos blockPos,
        Direction direction
    ) {
        ClientWorld oldWorld = mc.world;
        mc.world = CGlobal.clientWorldLoader.getOrCreateFakedWorld(remotePointedDim);
        isContextSwitched = true;
        boolean result = mc.interactionManager.updateBlockBreakingProgress(blockPos, direction);
        mc.world = oldWorld;
        isContextSwitched = false;
        return result;
    }
    
    public static void myAttackBlock() {
        MinecraftClient mc = MinecraftClient.getInstance();
        
        ClientWorld targetWorld =
            CGlobal.clientWorldLoader.getOrCreateFakedWorld(remotePointedDim);
        BlockPos blockPos = ((BlockHitResult) remoteHitResult).getBlockPos();
        
        if (targetWorld.isAir(blockPos)) {
            return;
        }
        
        ClientWorld oldWorld = mc.world;
        
        mc.world = targetWorld;
        isContextSwitched = true;
        mc.interactionManager.attackBlock(blockPos, ((BlockHitResult) remoteHitResult).getSide());
        mc.world = oldWorld;
        isContextSwitched = false;
    }
}
