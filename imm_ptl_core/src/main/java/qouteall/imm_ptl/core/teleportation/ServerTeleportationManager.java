package qouteall.imm_ptl.core.teleportation;

import net.minecraft.Util;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.players.PlayerList;
import org.apache.commons.lang3.Validate;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.PehkuiInterface;
import qouteall.imm_ptl.core.chunk_loading.NewChunkTrackingGraph;
import qouteall.imm_ptl.core.compat.GravityChangerInterface;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.ducks.IEServerPlayNetworkHandler;
import qouteall.imm_ptl.core.ducks.IEServerPlayerEntity;
import qouteall.imm_ptl.core.platform_specific.IPNetworking;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.MiscHelper;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.dimension.DynamicDimensionsImpl;
import qouteall.q_misc_util.my_util.LimitedLogger;
import qouteall.q_misc_util.my_util.MyTaskList;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ServerTeleportationManager {
    private Set<ServerPlayer> teleportingEntities = new HashSet<>();
    private WeakHashMap<Entity, Long> lastTeleportGameTime = new WeakHashMap<>();
    public boolean isFiringMyChangeDimensionEvent = false;
    public final WeakHashMap<ServerPlayer, Tuple<ResourceKey<Level>, Vec3>> lastPosition =
        new WeakHashMap<>();
    
    // The old teleport way does not recreate the entity
    // It's problematic because some AI-related fields contain world reference
    private static final boolean useOldTeleport = false;
    
    public ServerTeleportationManager() {
        IPGlobal.postServerTickSignal.connectWithWeakRef(this, ServerTeleportationManager::tick);
        Portal.serverPortalTickSignal.connectWithWeakRef(
            this, (this_, portal) -> {
                getEntitiesToTeleport(portal).forEach(entity -> {
                    this_.tryToTeleportRegularEntity(portal, entity);
                });
            }
        );
        
        DynamicDimensionsImpl.beforeRemovingDimensionSignal.connect(this::evacuatePlayersFromDimension);
    }
    
    public static boolean shouldEntityTeleport(Portal portal, Entity entity) {
        return entity.level == portal.level &&
            portal.canTeleportEntity(entity) &&
            portal.isMovedThroughPortal(
                entity.getEyePosition(0),
                entity.getEyePosition(1).add(McHelper.getWorldVelocity(entity))
            );
    }
    
    
    public void tryToTeleportRegularEntity(Portal portal, Entity entity) {
        if (entity instanceof ServerPlayer) {
            return;
        }
        if (entity instanceof Portal) {
            return;
        }
        if (entity.getVehicle() != null || doesEntityClusterContainPlayer(entity)) {
            return;
        }
        if (entity.isRemoved()) {
            return;
        }
        if (!entity.canChangeDimensions()) {
            return;
        }
        if (isJustTeleported(entity, 1)) {
            return;
        }
        //a new born entity may have last tick pos 0 0 0
        double motion = McHelper.lastTickPosOf(entity).distanceToSqr(entity.position());
        if (motion > 20) {
            return;
        }
        IPGlobal.serverTaskList.addTask(() -> {
            try {
                teleportRegularEntity(entity, portal);
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
            return true;
        });
    }
    
    private static Stream<Entity> getEntitiesToTeleport(Portal portal) {
        return portal.level.getEntitiesOfClass(
            Entity.class,
            portal.getBoundingBox().inflate(2),
            e -> true
        ).stream().filter(
            e -> !(e instanceof Portal)
        ).filter(
            entity -> shouldEntityTeleport(portal, entity)
        );
    }
    
    public void onPlayerTeleportedInClient(
        ServerPlayer player,
        ResourceKey<Level> dimensionBefore,
        Vec3 oldEyePos,
        UUID portalId
    ) {
        recordLastPosition(player);
        
        Portal portal = findPortal(dimensionBefore, portalId);
        
        if (!portal.canTeleportEntity(player)) {
            Helper.log("The portal cannot teleport the player, but a teleport packet has been received");
            return;
        }
        
        lastTeleportGameTime.put(player, McHelper.getServerGameTime());
        
        Vec3 oldFeetPos = oldEyePos.subtract(McHelper.getEyeOffset(player));
        if (canPlayerTeleport(player, dimensionBefore, oldFeetPos, portal)) {
            if (isTeleporting(player)) {
                Helper.log(player.toString() + "is teleporting frequently");
            }
            
            notifyChasersForPlayer(player, portal);
            
            ResourceKey<Level> dimensionTo = portal.dimensionTo;
            Vec3 newEyePos = portal.transformPoint(oldEyePos);
            
            teleportPlayer(player, dimensionTo, newEyePos);
            
            portal.onEntityTeleportedOnServer(player);
            
            PehkuiInterface.invoker.onServerEntityTeleported(player, portal);
            
            if (portal.getTeleportChangesGravity()) {
                Direction oldGravityDir = GravityChangerInterface.invoker.getGravityDirection(player);
                GravityChangerInterface.invoker.setGravityDirection(
                    player, portal.getTransformedGravityDirection(oldGravityDir)
                );
            }
            
        }
        else {
            Helper.err(String.format(
                "Player cannot teleport through portal %s %s %s %s",
                player.getName().getContents(),
                player.level.dimension(),
                player.position(),
                portal
            ));
        }
    }
    
    private Portal findPortal(ResourceKey<Level> dimensionBefore, UUID portalId) {
        ServerLevel originalWorld = MiscHelper.getServer().getLevel(dimensionBefore);
        Entity portalEntity = originalWorld.getEntity(portalId);
        if (portalEntity == null) {
            portalEntity = GlobalPortalStorage.get(originalWorld).data
                .stream().filter(
                    p -> p.getUUID().equals(portalId)
                ).findFirst().orElse(null);
        }
        if (portalEntity == null) {
            return null;
        }
        if (portalEntity instanceof Portal) {
            return ((Portal) portalEntity);
        }
        return null;
    }
    
    public void recordLastPosition(ServerPlayer player) {
        lastPosition.put(
            player,
            new Tuple<>(player.level.dimension(), player.position())
        );
    }
    
    private boolean canPlayerTeleport(
        ServerPlayer player,
        ResourceKey<Level> dimensionBefore,
        Vec3 posBefore,
        Entity portalEntity
    ) {
        if (player.getVehicle() != null) {
            return true;
        }
        return canPlayerReachPos(player, dimensionBefore, posBefore) &&
            portalEntity instanceof Portal &&
            ((Portal) portalEntity).getDistanceToPlane(posBefore) < 20;
    }
    
    public static boolean canPlayerReachPos(
        ServerPlayer player,
        ResourceKey<Level> dimension,
        Vec3 pos
    ) {
        Vec3 playerPos = player.position();
        if (player.level.dimension() == dimension) {
            if (playerPos.distanceToSqr(pos) < 256) {
                return true;
            }
        }
        return IPMcHelper.getNearbyPortals(player, 20)
            .filter(portal -> portal.dimensionTo == dimension)
            .map(portal -> portal.transformPoint(playerPos))
            .anyMatch(mappedPos -> mappedPos.distanceToSqr(pos) < 256);
    }
    
    public void teleportPlayer(
        ServerPlayer player,
        ResourceKey<Level> dimensionTo,
        Vec3 newEyePos
    ) {
        MiscHelper.getServer().getProfiler().push("portal_teleport");
        
        ServerLevel fromWorld = (ServerLevel) player.level;
        ServerLevel toWorld = MiscHelper.getServer().getLevel(dimensionTo);
        
        NewChunkTrackingGraph.addAdditionalDirectLoadingTickets(player);
        
        if (player.level.dimension() == dimensionTo) {
            McHelper.setEyePos(player, newEyePos, newEyePos);
            McHelper.updateBoundingBox(player);
        }
        else {
            changePlayerDimension(player, fromWorld, toWorld, newEyePos);
            ((IEServerPlayNetworkHandler) player.connection).cancelTeleportRequest();
        }
        
        McHelper.adjustVehicle(player);
        player.connection.resetPosition();
        
        MiscHelper.getServer().getProfiler().pop();
    }
    
    public void invokeTpmeCommand(
        ServerPlayer player,
        ResourceKey<Level> dimensionTo,
        Vec3 newPos
    ) {
        ServerLevel fromWorld = (ServerLevel) player.level;
        ServerLevel toWorld = MiscHelper.getServer().getLevel(dimensionTo);
        
        if (player.level.dimension() == dimensionTo) {
            player.setPos(newPos.x, newPos.y, newPos.z);
        }
        else {
            changePlayerDimension(player, fromWorld, toWorld, newPos);
            sendPositionConfirmMessage(player);
        }
        
        player.connection.teleport(
            newPos.x,
            newPos.y,
            newPos.z,
            player.getYRot(),
            player.getXRot()
        );
        player.connection.resetPosition();
        ((IEServerPlayNetworkHandler) player.connection).cancelTeleportRequest();
        
    }
    
    /**
     * {@link ServerPlayerEntity#moveToWorld(ServerWorld)}
     */
    private void changePlayerDimension(
        ServerPlayer player,
        ServerLevel fromWorld,
        ServerLevel toWorld,
        Vec3 newEyePos
    ) {
        teleportingEntities.add(player);
        
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            ((IEServerPlayerEntity) player).stopRidingWithoutTeleportRequest();
        }
        
        Vec3 oldPos = player.position();
        
        fromWorld.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION);
        ((IEEntity) player).portal_unsetRemoved();
        
        McHelper.setEyePos(player, newEyePos, newEyePos);
        McHelper.updateBoundingBox(player);
        
        player.setLevel(toWorld);
        
        // adds the player
        toWorld.addDuringPortalTeleport(player);
        
        if (vehicle != null) {
            Vec3 vehiclePos = new Vec3(
                newEyePos.x,
                McHelper.getVehicleY(vehicle, player),
                newEyePos.z
            );
            changeEntityDimension(
                vehicle,
                toWorld.dimension(),
                vehiclePos.add(McHelper.getEyeOffset(vehicle)),
                false
            );
            ((IEServerPlayerEntity) player).startRidingWithoutTeleportRequest(vehicle);
            McHelper.adjustVehicle(player);
        }
        
        Helper.dbg(String.format(
            "%s :: (%s %s %s %s)->(%s %s %s %s)",
            player.getName().getContents(),
            fromWorld.dimension().location(),
            oldPos.x(), oldPos.y(), oldPos.z(),
            toWorld.dimension().location(),
            (int) player.getX(), (int) player.getY(), (int) player.getZ()
        ));
        
        O_O.onPlayerTravelOnServer(
            player,
            fromWorld.dimension(),
            toWorld.dimension()
        );
        
        //update advancements
        ((IEServerPlayerEntity) player).portal_worldChanged(fromWorld);
    }
    
    public static void sendPositionConfirmMessage(ServerPlayer player) {
        Packet packet = IPNetworking.createStcDimensionConfirm(
            player.level.dimension(),
            player.position()
        );
        
        player.connection.send(packet);
    }
    
    private void tick() {
        teleportingEntities = new HashSet<>();
        long tickTimeNow = McHelper.getServerGameTime();
        ArrayList<ServerPlayer> copiedPlayerList =
            McHelper.getCopiedPlayerList();
        if (tickTimeNow % 30 == 7) {
            for (ServerPlayer player : copiedPlayerList) {
                updateForPlayer(tickTimeNow, player);
            }
        }
        copiedPlayerList.forEach(player -> {
            McHelper.getServerEntitiesNearbyWithoutLoadingChunk(
                player.level,
                player.position(),
                Entity.class,
                32
            ).filter(
                entity -> !(entity instanceof ServerPlayer)
            ).forEach(entity -> {
                GlobalPortalStorage.getGlobalPortals(entity.level).stream()
                    .filter(
                        globalPortal -> shouldEntityTeleport(globalPortal, entity)
                    )
                    .findFirst()
                    .ifPresent(
                        globalPortal -> tryToTeleportRegularEntity(globalPortal, entity)
                    );
            });
        });
    }
    
    private void updateForPlayer(long tickTimeNow, ServerPlayer player) {
        // teleporting means dimension change
        // inTeleportationState means syncing position to client
        if (player.wonGame || player.isChangingDimension()) {
            lastTeleportGameTime.put(player, tickTimeNow);
            return;
        }
        Long lastTeleportGameTime =
            this.lastTeleportGameTime.getOrDefault(player, 0L);
        if (tickTimeNow - lastTeleportGameTime > 60) {
            sendPositionConfirmMessage(player);
            
            //for vanilla nether portal cooldown to work normally
            player.hasChangedDimension();
        }
        else {
            ((IEServerPlayNetworkHandler) player.connection).cancelTeleportRequest();
        }
    }
    
    public boolean isTeleporting(ServerPlayer entity) {
        return teleportingEntities.contains(entity);
    }
    
    private void teleportRegularEntity(Entity entity, Portal portal) {
        Validate.isTrue(!(entity instanceof ServerPlayer));
        if (entity.level != portal.level) {
            Helper.err(String.format("Cannot teleport %s from %s through %s", entity, entity.level.dimension(), portal));
            return;
        }
        
        long currGameTime = McHelper.getServerGameTime();
        Long lastTeleportGameTime = this.lastTeleportGameTime.getOrDefault(entity, 0L);
        if (currGameTime - lastTeleportGameTime <= 1) {
            return;
        }
        this.lastTeleportGameTime.put(entity, currGameTime);
        
        if (entity.isPassenger() || doesEntityClusterContainPlayer(entity)) {
            return;
        }
        
        Vec3 velocity = entity.getDeltaMovement();
        
        List<Entity> passengerList = entity.getPassengers();
        
        Vec3 newEyePos = getRegularEntityTeleportedEyePos(entity, portal);
        
        portal.transformVelocity(entity);
        
        if (portal.dimensionTo != entity.level.dimension()) {
            entity = changeEntityDimension(entity, portal.dimensionTo, newEyePos, true);
            
            Entity newEntity = entity;
            
            passengerList.stream().map(
                e -> changeEntityDimension(e, portal.dimensionTo, newEyePos, true)
            ).collect(Collectors.toList()).forEach(e -> {
                e.startRiding(newEntity, true);
            });
        }
        
        McHelper.setEyePos(entity, newEyePos, newEyePos);
        McHelper.updateBoundingBox(entity);
        
        // living entities do position interpolation
        // it may interpolate into unloaded chunks and stuck
        // avoid position interpolation
        McHelper.sendToTrackers(
            entity,
            McRemoteProcedureCall.createPacketToSendToClient(
                "qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos",
                entity.level.dimension(),
                entity.getId(),
                entity.position()
            )
        );
        
        portal.onEntityTeleportedOnServer(entity);
        
        PehkuiInterface.invoker.onServerEntityTeleported(entity, portal);
        
        // a new entity may be created
        this.lastTeleportGameTime.put(entity, currGameTime);
    }
    
    private static Vec3 getRegularEntityTeleportedEyePos(Entity entity, Portal portal) {
        Vec3 eyePosNextTick = McHelper.getEyePos(entity).add(entity.getDeltaMovement());
        if (entity instanceof Projectile) {
            Vec3 collidingPoint = portal.rayTrace(
                eyePosNextTick.subtract(entity.getDeltaMovement().normalize().scale(5)),
                eyePosNextTick
            );
            
            if (collidingPoint == null) {
                collidingPoint = eyePosNextTick;
            }
            
            return portal.transformPoint(collidingPoint).add(portal.getContentDirection().scale(0.01));
        }
        else {
            return portal.transformPoint(eyePosNextTick);
        }
    }
    
    /**
     * {@link Entity#moveToWorld(ServerWorld)}
     * Sometimes resuing the same entity object is problematic
     * because entity's AI related things may have world reference inside
     */
    public Entity changeEntityDimension(
        Entity entity,
        ResourceKey<Level> toDimension,
        Vec3 newEyePos,
        boolean recreateEntity
    ) {
        ServerLevel fromWorld = (ServerLevel) entity.level;
        ServerLevel toWorld = MiscHelper.getServer().getLevel(toDimension);
        entity.unRide();
        
        if (recreateEntity) {
            Entity oldEntity = entity;
            Entity newEntity;
            newEntity = entity.getType().create(toWorld);
            if (newEntity == null) {
                return oldEntity;
            }
            
            newEntity.restoreFrom(oldEntity);
            McHelper.setEyePos(newEntity, newEyePos, newEyePos);
            McHelper.updateBoundingBox(newEntity);
            newEntity.setYHeadRot(oldEntity.getYHeadRot());
            
            // TODO check minecart item duplication
            oldEntity.remove(Entity.RemovalReason.CHANGED_DIMENSION);
            ((IEEntity) oldEntity).portal_unsetRemoved();
            
            toWorld.addDuringTeleport(newEntity);
            
            return newEntity;
        }
        else {
            entity.remove(Entity.RemovalReason.CHANGED_DIMENSION);
            ((IEEntity) entity).portal_unsetRemoved();
            
            McHelper.setEyePos(entity, newEyePos, newEyePos);
            McHelper.updateBoundingBox(entity);
            
            entity.level = toWorld;
            
            toWorld.addDuringTeleport(entity);
            
            return entity;
        }
        
        
    }
    
    private boolean doesEntityClusterContainPlayer(Entity entity) {
        if (entity instanceof Player) {
            return true;
        }
        List<Entity> passengerList = entity.getPassengers();
        if (passengerList.isEmpty()) {
            return false;
        }
        return passengerList.stream().anyMatch(this::doesEntityClusterContainPlayer);
    }
    
    public boolean isJustTeleported(Entity entity, long valveTickTime) {
        long currGameTime = McHelper.getServerGameTime();
        Long lastTeleportGameTime = this.lastTeleportGameTime.getOrDefault(entity, -100000L);
        return currGameTime - lastTeleportGameTime < valveTickTime;
    }
    
    private static final LimitedLogger limitedLogger = new LimitedLogger(20);
    
    public void acceptDubiousMovePacket(
        ServerPlayer player,
        ServerboundMovePlayerPacket packet,
        ResourceKey<Level> dimension
    ) {
        if (player.level.dimension() == dimension) {
            return;
        }
        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        Vec3 newPos = new Vec3(x, y, z);
        if (canPlayerReachPos(player, dimension, newPos)) {
            recordLastPosition(player);
            invokeTpmeCommand(player, dimension, newPos);
            limitedLogger.log(String.format("accepted dubious move packet %s %s %s %s %s %s %s",
                player.level.dimension().location(), x, y, z, player.getX(), player.getY(), player.getZ()
            ));
        }
        else {
            limitedLogger.log(String.format("ignored dubious move packet %s %s %s %s %s %s %s",
                player.level.dimension().location(), x, y, z, player.getX(), player.getY(), player.getZ()
            ));
        }
    }
    
    public static void teleportEntityGeneral(Entity entity, Vec3 targetPos, ServerLevel targetWorld) {
        if (entity instanceof ServerPlayer) {
            IPGlobal.serverTeleportationManager.invokeTpmeCommand(
                (ServerPlayer) entity, targetWorld.dimension(), targetPos
            );
        }
        else {
            teleportRegularEntityTo(entity, targetWorld.dimension(), targetPos);
        }
    }
    
    public static <E extends Entity> E teleportRegularEntityTo(
        E entity, ResourceKey<Level> targetDim, Vec3 targetPos
    ) {
        if (entity.level.dimension() == targetDim) {
            entity.moveTo(
                targetPos.x,
                targetPos.y,
                targetPos.z,
                entity.getYRot(),
                entity.getXRot()
            );
            entity.setYHeadRot(entity.getYRot());
            return entity;
        }
        
        return (E) IPGlobal.serverTeleportationManager.changeEntityDimension(
            entity,
            targetDim,
            targetPos.add(McHelper.getEyeOffset(entity)),
            true
        );
    }
    
    // make the mobs chase the player through portal
    // (only works in simple cases)
    private static void notifyChasersForPlayer(
        ServerPlayer player,
        Portal portal
    ) {
        List<Mob> chasers = McHelper.findEntitiesRough(
            Mob.class,
            player.level,
            player.position(),
            1,
            e -> e.getTarget() == player
        );
        
        for (Mob chaser : chasers) {
            chaser.setTarget(null);
            notifyChaser(player, portal, chaser);
        }
    }
    
    private static void notifyChaser(
        ServerPlayer player,
        Portal portal,
        Mob chaser
    ) {
        Vec3 targetPos = player.position().add(portal.getNormal().scale(-0.1));
        
        UUID chaserId = chaser.getUUID();
        ServerLevel destWorld = ((ServerLevel) portal.getDestinationWorld());
        
        IPGlobal.serverTaskList.addTask(MyTaskList.withRetryNumberLimit(
            140,
            () -> {
                if (chaser.isRemoved()) {
                    // the chaser teleported
                    Entity newChaser = destWorld.getEntity(chaserId);
                    if (newChaser instanceof Mob) {
                        ((Mob) newChaser).setTarget(player);
                        return true;
                    }
                    else {
                        return false;
                    }
                }
                
                if (chaser.position().distanceTo(targetPos) < 2) {
                    chaser.getMoveControl().setWantedPosition(
                        targetPos.x, targetPos.y, targetPos.z, 1
                    );
                }
                else {
                    @Nullable
                    Path path = chaser.getNavigation().createPath(
                        new BlockPos(targetPos), 0
                    );
                    chaser.getNavigation().moveTo(path, 1);
                }
                return false;
            },
            () -> {}
        ));
    }
    
    private void evacuatePlayersFromDimension(ResourceKey<Level> dim) {
        PlayerList playerList = MiscHelper.getServer().getPlayerList();
        for (ServerPlayer player : playerList.getPlayers()) {
            if (player.level.dimension() == dim) {
                ServerLevel overWorld = McHelper.getOverWorldOnServer();
                BlockPos spawnPos = overWorld.getSharedSpawnPos();
                
                invokeTpmeCommand(player, Level.OVERWORLD, Vec3.atCenterOf(spawnPos));
                
                player.sendMessage(new TextComponent(
                    "Teleported to spawn pos because dimension %s had been removed".formatted(dim.location())
                ), Util.NIL_UUID);
            }
        }
    }
}
