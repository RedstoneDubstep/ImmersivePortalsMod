package qouteall.imm_ptl.core.commands;

import com.google.common.collect.Streams;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.GeometryPortalShape;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.core.portal.PortalManipulation;
import qouteall.imm_ptl.core.portal.global_portals.BorderBarrierFiller;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;
import qouteall.imm_ptl.core.portal.global_portals.WorldWrappingPortal;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;
import qouteall.imm_ptl.core.portal.nether_portal.NetherPortalMatcher;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.MiscHelper;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.my_util.DQuaternion;
import qouteall.q_misc_util.my_util.IntBox;
import qouteall.q_misc_util.my_util.MyTaskList;
import qouteall.q_misc_util.my_util.SignalBiArged;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PortalCommand {
    // it needs to invoke the outer mod but the core does not have outer mod dependency
    public static final SignalBiArged<ServerPlayer, String>
        createCommandStickCommandSignal = new SignalBiArged<>();
    
    public static void register(
        CommandDispatcher<CommandSourceStack> dispatcher
    ) {
        
        LiteralArgumentBuilder<CommandSourceStack> builder = Commands
            .literal("portal")
            .requires(PortalCommand::canUsePortalCommand);
        
        registerPortalTargetedCommands(builder);
        
        registerCBPortalCommands(builder);
        
        registerUtilityCommands(builder);
        
        LiteralArgumentBuilder<CommandSourceStack> global =
            Commands.literal("global")
                .requires(commandSource -> commandSource.hasPermission(2));
        registerGlobalPortalCommands(global);
        builder.then(global);
        
        LiteralArgumentBuilder<CommandSourceStack> debugBuilder = Commands.literal("debug")
            .requires(PortalCommand::canUsePortalCommand);
        PortalDebugCommands.registerDebugCommands(debugBuilder);
        builder.then(debugBuilder);
        
        dispatcher.register(builder);
    }
    
    public static boolean canUsePortalCommand(CommandSourceStack commandSource) {
        Entity entity = commandSource.getEntity();
        if (entity instanceof ServerPlayer) {
            if (IPGlobal.easeCreativePermission) {
                if (((ServerPlayer) entity).isCreative()) {
                    return true;
                }
            }
        }
        
        return commandSource.hasPermission(2);
    }
    
    private static void registerGlobalPortalCommands(
        LiteralArgumentBuilder<CommandSourceStack> builder
    ) {
        builder.then(Commands.literal("create_inward_wrapping")
            .then(Commands.argument("p1", ColumnPosArgument.columnPos())
                .then(Commands.argument("p2", ColumnPosArgument.columnPos())
                    .executes(context -> {
                        ColumnPos p1 = ColumnPosArgument.getColumnPos(context, "p1");
                        ColumnPos p2 = ColumnPosArgument.getColumnPos(context, "p2");
                        WorldWrappingPortal.invokeAddWrappingZone(
                            context.getSource().getLevel(),
                            p1.x, p1.z, p2.x, p2.z,
                            true,
                            text -> context.getSource().sendSuccess(text, false)
                        );
                        return 0;
                    })
                )
            )
        );
        
        builder.then(Commands.literal("create_outward_wrapping")
            .then(Commands.argument("p1", ColumnPosArgument.columnPos())
                .then(Commands.argument("p2", ColumnPosArgument.columnPos())
                    .executes(context -> {
                        ColumnPos p1 = ColumnPosArgument.getColumnPos(context, "p1");
                        ColumnPos p2 = ColumnPosArgument.getColumnPos(context, "p2");
                        WorldWrappingPortal.invokeAddWrappingZone(
                            context.getSource().getLevel(),
                            p1.x, p1.z, p2.x, p2.z,
                            false,
                            text -> context.getSource().sendSuccess(text, false)
                        );
                        return 0;
                    })
                )
            )
        );
        
        builder.then(Commands.literal("remove_wrapping_zone")
            .executes(context -> {
                WorldWrappingPortal.invokeRemoveWrappingZone(
                    context.getSource().getLevel(),
                    context.getSource().getPosition(),
                    text -> context.getSource().sendSuccess(text, false)
                );
                return 0;
            })
            .then(Commands.argument("id", IntegerArgumentType.integer())
                .executes(context -> {
                    int id = IntegerArgumentType.getInteger(context, "id");
                    WorldWrappingPortal.invokeRemoveWrappingZone(
                        context.getSource().getLevel(),
                        id,
                        text -> context.getSource().sendSuccess(text, false)
                    );
                    return 0;
                })
            )
        );
        
        builder.then(Commands.literal("view_wrapping_zones")
            .executes(context -> {
                WorldWrappingPortal.invokeViewWrappingZones(
                    context.getSource().getLevel(),
                    text -> context.getSource().sendSuccess(text, false)
                );
                return 0;
            })
        );
        
        builder.then(Commands.literal("clear_wrapping_border")
            .executes(context -> {
                BorderBarrierFiller.onCommandExecuted(
                    context.getSource().getPlayerOrException()
                );
                return 0;
            })
            .then(Commands.argument("id", IntegerArgumentType.integer())
                .executes(context -> {
                    int id = IntegerArgumentType.getInteger(context, "id");
                    BorderBarrierFiller.onCommandExecuted(
                        context.getSource().getPlayerOrException(),
                        id
                    );
                    return 0;
                })
            )
        );
        
        builder.then(Commands.literal("connect_floor")
            .then(Commands.argument("from", DimensionArgument.dimension())
                .then(
                    Commands.argument(
                        "to",
                        DimensionArgument.dimension()
                    ).executes(
                        context -> {
                            ResourceKey<Level> from = DimensionArgument.getDimension(
                                context, "from"
                            ).dimension();
                            ResourceKey<Level> to = DimensionArgument.getDimension(
                                context, "to"
                            ).dimension();
                            
                            VerticalConnectingPortal.connect(
                                from, VerticalConnectingPortal.ConnectorType.floor, to
                            );
                            return 0;
                        }
                    )
                )
            )
        );
        
        builder.then(Commands.literal("connect_ceil")
            .then(Commands.argument("from", DimensionArgument.dimension())
                .then(Commands.argument("to", DimensionArgument.dimension())
                    .executes(
                        context -> {
                            ResourceKey<Level> from = DimensionArgument.getDimension(
                                context, "from"
                            ).dimension();
                            ResourceKey<Level> to = DimensionArgument.getDimension(
                                context, "to"
                            ).dimension();
                            
                            VerticalConnectingPortal.connect(
                                from, VerticalConnectingPortal.ConnectorType.ceil, to
                            );
                            return 0;
                        }
                    )
                )
            )
        );
        
        builder.then(Commands
            .literal("connection_floor_remove")
            .then(Commands.argument("dim", DimensionArgument.dimension())
                .executes(
                    context -> {
                        ResourceKey<Level> dim = DimensionArgument.getDimension(
                            context, "dim"
                        ).dimension();
                        
                        VerticalConnectingPortal.removeConnectingPortal(
                            VerticalConnectingPortal.ConnectorType.floor, dim
                        );
                        return 0;
                    }
                )
            )
        );
        
        builder.then(Commands
            .literal("connection_ceil_remove")
            .then(Commands.argument("dim", DimensionArgument.dimension())
                .executes(
                    context -> {
                        ResourceKey<Level> dim = DimensionArgument.getDimension(
                            context, "dim"
                        ).dimension();
                        
                        VerticalConnectingPortal.removeConnectingPortal(
                            VerticalConnectingPortal.ConnectorType.ceil, dim
                        );
                        return 0;
                    }
                )
            )
        );
        
        builder.then(Commands.literal("view_global_portals")
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                sendMessage(
                    context,
                    Helper.myToString(GlobalPortalStorage.getGlobalPortals(player.level).stream())
                );
                return 0;
            })
        );
        
        builder.then(Commands.literal("convert_normal_portal_to_global_portal")
            .executes(context -> processPortalTargetedCommand(
                context,
                GlobalPortalStorage::convertNormalPortalIntoGlobalPortal
            ))
        );
        
        builder.then(Commands.literal("convert_global_portal_to_normal_portal")
            .executes(context -> {
                final ServerPlayer player = context.getSource().getPlayerOrException();
                final Portal portal = getPlayerPointingPortal(player, true);
                
                if (portal == null) {
                    context.getSource().sendSuccess(
                        new TextComponent("You are not pointing to any portal"),
                        false
                    );
                    return 0;
                }
                
                if (!portal.getIsGlobal()) {
                    context.getSource().sendSuccess(
                        new TextComponent("You are not pointing to a global portal"),
                        false
                    );
                    return 0;
                }
                
                if (player.position().distanceTo(portal.getOriginPos()) > 64) {
                    context.getSource().sendSuccess(
                        new TextComponent("You are too far away from the portal's center " + portal),
                        false
                    );
                    return 0;
                }
                
                GlobalPortalStorage.convertGlobalPortalIntoNormalPortal(portal);
                
                return 0;
            })
        );
        
        builder.then(Commands.literal("delete_global_portal")
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                Portal portal = getPlayerPointingPortal(player, true);
                
                if (portal == null) {
                    context.getSource().sendSuccess(
                        new TextComponent("You are not pointing to any portal"),
                        false
                    );
                    return 0;
                }
                
                if (!portal.getIsGlobal()) {
                    context.getSource().sendSuccess(
                        new TextComponent("You are not pointing to a global portal"),
                        false
                    );
                    return 0;
                }
                
                GlobalPortalStorage.get((ServerLevel) portal.level).removePortal(portal);
                
                return 0;
            })
        );
    }
    
    private static void registerPortalTargetedCommands(
        LiteralArgumentBuilder<CommandSourceStack> builder
    ) {
        builder.then(Commands.literal("view_portal_data")
            .executes(context -> processPortalTargetedCommand(
                context,
                (portal) -> {
                    sendPortalInfo(context, portal);
                }
            ))
        );
        
        builder.then(Commands.literal("set_portal_custom_name")
            .then(Commands
                .argument("name", ComponentArgument.textComponent())
                .executes(context -> processPortalTargetedCommand(
                    context,
                    portal -> {
                        Component name = ComponentArgument.getComponent(context, "name");
                        portal.setCustomName(name);
                    }
                ))
            )
        );
        
        builder.then(Commands
            .literal("delete_portal")
            .executes(context -> processPortalTargetedCommand(
                context,
                portal -> {
                    sendMessage(context, "deleted " + portal);
                    portal.remove(Entity.RemovalReason.KILLED);
                }
            ))
        );
        
        builder.then(Commands.literal("set_portal_nbt")
            .then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
                .executes(context -> processPortalTargetedCommand(
                    context,
                    portal -> {
                        CompoundTag newNbt = CompoundTagArgument.getCompoundTag(
                            context, "nbt"
                        );
                        
                        if (newNbt.contains("commandsOnTeleported")) {
                            if (!context.getSource().hasPermission(2)) {
                                context.getSource().sendFailure(new TextComponent(
                                    "You do not have the permission to set commandsOnTeleported"
                                ));
                                return;
                            }
                        }
                        
                        if (newNbt.contains("dimensionTo")) {
                            context.getSource().sendFailure(new TextComponent(
                                "Cannot change tag dimensionTo. use command /portal set_portal_destination"
                            ));
                            return;
                        }
                        
                        setPortalNbt(portal, newNbt);
                        
                        sendPortalInfo(context, portal);
                    }
                ))
            )
        );
        
        builder.then(Commands.literal("set_portal_destination")
            .then(Commands.argument("dim", DimensionArgument.dimension())
                .then(Commands.argument("dest", Vec3Argument.vec3(false))
                    .executes(context -> processPortalTargetedCommand(
                        context, portal -> {
                            sendEditBiWayPortalWarning(context, portal);
                            
                            portal.dimensionTo = DimensionArgument.getDimension(
                                context, "dim"
                            ).dimension();
                            portal.setDestination(Vec3Argument.getVec3(
                                context, "dest"
                            ));
                            
                            reloadPortal(portal);
                            
                            sendMessage(context, portal.toString());
                        }
                    ))
                )
            )
        );
        
        registerPortalTargetedCommandWithRotationArgument(
            builder, "set_portal_rotation",
            (p, r) -> {
                p.rotation = r;
            }
        );
        
        registerPortalTargetedCommandWithRotationArgument(
            builder, "rotate_portal_body",
            (p, r) -> {
                if (r != null) {
                    PortalManipulation.rotatePortalBody(p, r);
                }
            }
        );
        
        registerPortalTargetedCommandWithRotationArgument(
            builder, "rotate_portal_rotation",
            (portal, rot) -> {
                if (rot != null) {
                    if (portal.rotation == null) {
                        portal.rotation = rot;
                    }
                    else {
                        portal.rotation.mul(rot);
                    }
                }
            }
        );
        
        builder.then(Commands.literal("complete_bi_way_portal")
            .executes(context -> processPortalTargetedCommand(
                context,
                portal -> {
                    invokeCompleteBiWayPortal(context, portal);
                }
            ))
        );
        
        builder.then(Commands.literal("complete_bi_faced_portal")
            .executes(context -> processPortalTargetedCommand(
                context,
                portal -> {
                    invokeCompleteBiFacedPortal(context, portal);
                }
            ))
        );
        
        builder.then(Commands.literal("complete_bi_way_bi_faced_portal")
            .executes(context -> processPortalTargetedCommand(
                context,
                portal ->
                    invokeCompleteBiWayBiFacedPortal(context, portal)
            ))
        );
        
        builder.then(Commands.literal("remove_connected_portals")
            .executes(context -> processPortalTargetedCommand(
                context,
                portal -> {
                    PortalManipulation.removeConnectedPortals(
                        portal,
                        p -> sendMessage(context, "Removed " + p)
                    );
                }
            ))
        );
        
        builder.then(Commands.literal("eradicate_portal_clutter")
            .executes(context -> processPortalTargetedCommand(
                context,
                portal -> {
                    PortalManipulation.removeConnectedPortals(
                        portal,
                        p -> sendMessage(context, "Removed " + p)
                    );
                    portal.remove(Entity.RemovalReason.KILLED);
                    sendMessage(context, "Deleted " + portal);
                }
            ))
        );
        
        builder.then(Commands.literal("eradicate_portal_cluster")
            .executes(context -> processPortalTargetedCommand(
                context,
                portal -> {
                    PortalManipulation.removeConnectedPortals(
                        portal,
                        p -> sendMessage(context, "Removed " + p)
                    );
                    portal.remove(Entity.RemovalReason.KILLED);
                    sendMessage(context, "Deleted " + portal);
                }
            ))
        );
        
        builder.then(Commands.literal("move_portal")
            .then(Commands.argument("distance", DoubleArgumentType.doubleArg())
                .executes(context -> processPortalTargetedCommand(
                    context, portal -> {
                        try {
                            sendEditBiWayPortalWarning(context, portal);
                            
                            double distance =
                                DoubleArgumentType.getDouble(context, "distance");
                            
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            Vec3 viewVector = player.getLookAngle();
                            Direction facing = Direction.getNearest(
                                viewVector.x, viewVector.y, viewVector.z
                            );
                            Vec3 offset = Vec3.atLowerCornerOf(facing.getNormal()).scale(distance);
                            portal.setPos(
                                portal.getX() + offset.x,
                                portal.getY() + offset.y,
                                portal.getZ() + offset.z
                            );
                            reloadPortal(portal);
                        }
                        catch (CommandSyntaxException e) {
                            sendMessage(context, "This command can only be invoked by player");
                        }
                    }
                ))
            )
        );
        
        builder.then(Commands.literal("move_portal_destination")
            .then(Commands.argument("distance", DoubleArgumentType.doubleArg())
                .executes(context -> processPortalTargetedCommand(
                    context, portal -> {
                        try {
                            sendEditBiWayPortalWarning(context, portal);
                            
                            double distance =
                                DoubleArgumentType.getDouble(context, "distance");
                            
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            Vec3 viewVector = player.getLookAngle();
                            Direction facing = Direction.getNearest(
                                viewVector.x, viewVector.y, viewVector.z
                            );
                            Vec3 offset = Vec3.atLowerCornerOf(facing.getNormal()).scale(distance);
                            
                            portal.setDestination(portal.getDestPos().add(
                                portal.transformLocalVecNonScale(offset)
                            ));
                            reloadPortal(portal);
                        }
                        catch (CommandSyntaxException e) {
                            sendMessage(context, "This command can only be invoked by player");
                        }
                    }
                ))
            )
        );
        
        
        builder.then(Commands.literal("set_portal_specific_accessor")
            .executes(context -> processPortalTargetedCommand(
                context,
                portal -> {
                    removeSpecificAccessor(context, portal);
                }
            ))
            .then(Commands.argument("player", EntityArgument.player())
                .executes(context -> processPortalTargetedCommand(
                    context,
                    portal -> {
                        setSpecificAccessor(context, portal,
                            EntityArgument.getEntity(context, "player")
                        );
                    }
                ))
            )
        );
        
        
        builder.then(Commands.literal("multidest")
            .then(Commands.argument("player", EntityArgument.player())
                .executes(context -> processPortalTargetedCommand(
                    context,
                    portal -> {
                        removeMultidestEntry(
                            context, portal, EntityArgument.getPlayer(context, "player")
                        );
                    }
                ))
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                    .then(Commands.argument("destination", Vec3Argument.vec3(false))
                        .then(Commands.argument("isBiFaced", BoolArgumentType.bool())
                            .then(Commands.argument("isBiWay", BoolArgumentType.bool())
                                .executes(context -> processPortalTargetedCommand(
                                    context,
                                    portal -> {
                                        setMultidestEntry(
                                            context,
                                            portal,
                                            EntityArgument.getPlayer(context, "player"),
                                            DimensionArgument.getDimension(
                                                context,
                                                "dimension"
                                            ).dimension(),
                                            Vec3Argument.getVec3(context, "destination"),
                                            BoolArgumentType.getBool(context, "isBiFaced"),
                                            BoolArgumentType.getBool(context, "isBiWay")
                                        );
                                    }
                                ))
                            )
                        )
                    )
                )
            )
        );
        
        builder.then(Commands.literal("make_portal_round")
            .executes(context -> processPortalTargetedCommand(
                context,
                portal -> {
                    makePortalRound(portal);
                    reloadPortal(portal);
                }
            ))
        );
        
        builder.then(Commands.literal("set_portal_scale")
            .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0))
                .executes(context -> processPortalTargetedCommand(
                    context, portal -> {
                        double scale = DoubleArgumentType.getDouble(context, "scale");
                        
                        portal.scaling = scale;
                        
                        reloadPortal(portal);
                    }
                ))
            )
        );
        
        builder.then(Commands.literal("multiply_portal_scale")
            .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0))
                .executes(context -> processPortalTargetedCommand(
                    context, portal -> {
                        double scale = DoubleArgumentType.getDouble(context, "scale");
                        
                        portal.scaling = portal.scaling * scale;
                        
                        reloadPortal(portal);
                    }
                ))
            )
        );
        
        builder.then(Commands.literal("divide_portal_scale")
            .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0))
                .executes(context -> processPortalTargetedCommand(
                    context, portal -> {
                        double scale = DoubleArgumentType.getDouble(context, "scale");
                        
                        portal.scaling = portal.scaling / scale;
                        
                        reloadPortal(portal);
                    }
                ))
            )
        );
        
        builder.then(Commands.literal("set_portal_destination_to")
            .then(Commands.argument("entity", EntityArgument.entity())
                .executes(context -> processPortalTargetedCommand(context, portal -> {
                    Entity entity = EntityArgument.getEntity(context, "entity");
                    portal.dimensionTo = entity.level.dimension();
                    portal.setDestination(entity.position());
                    reloadPortal(portal);
                }))
            )
        );
        
        builder.then(Commands.literal("set_portal_position")
            .then(Commands.argument("dim", DimensionArgument.dimension())
                .then(Commands.argument("pos", Vec3Argument.vec3(false))
                    .executes(context -> processPortalTargetedCommand(context, portal -> {
                        ServerLevel targetWorld =
                            DimensionArgument.getDimension(context, "dim");
                        
                        Vec3 pos = Vec3Argument.getVec3(context, "pos");
                        
                        if (targetWorld == portal.level) {
                            portal.setOriginPos(pos);
                            reloadPortal(portal);
                        }
                        else {
                            ServerTeleportationManager.teleportEntityGeneral(
                                portal, pos, targetWorld
                            );
                        }
                        
                        sendMessage(context, portal.toString());
                        
                    }))
                )
            )
        );
        
        builder.then(Commands.literal("set_portal_position_to")
            .then(Commands.argument("targetEntity", EntityArgument.entity())
                .executes(context -> processPortalTargetedCommand(context, portal -> {
                    Entity targetEntity = EntityArgument.getEntity(context, "targetEntity");
                    
                    if (targetEntity.level == portal.level) {
                        portal.setOriginPos(targetEntity.position());
                        reloadPortal(portal);
                    }
                    else {
                        ServerTeleportationManager.teleportEntityGeneral(
                            portal, targetEntity.position(), ((ServerLevel) targetEntity.level)
                        );
                    }
                    
                    sendMessage(context, portal.toString());
                }))
            )
        );
        
        builder.then(Commands.literal("reset_portal_orientation")
            .executes(context -> processPortalTargetedCommand(
                context, portal -> {
                    portal.axisW = new Vec3(1, 0, 0);
                    portal.axisH = new Vec3(0, 1, 0);
                    reloadPortal(portal);
                }
            ))
        );
        
        builder.then(Commands.literal("relatively_move_portal")
            .then(Commands.argument("offset", Vec3Argument.vec3(false))
                .executes(context -> processPortalTargetedCommand(context, portal -> {
                    Vec3 offset = Vec3Argument.getVec3(context, "offset");
                    portal.setOriginPos(
                        portal.getOriginPos().add(
                            portal.axisW.scale(offset.x)
                        ).add(
                            portal.axisH.scale(offset.y)
                        ).add(
                            portal.getNormal().scale(offset.z)
                        )
                    );
                    reloadPortal(portal);
                }))
            )
        );
        
        builder.then(Commands.literal("relatively_move_portal_destination")
            .then(Commands.argument("offset", Vec3Argument.vec3(false))
                .executes(context -> processPortalTargetedCommand(context, portal -> {
                    Vec3 offset = Vec3Argument.getVec3(context, "offset");
                    portal.setDestination(
                        portal.getDestPos().add(
                            portal.transformLocalVec(portal.axisW).scale(offset.x)
                        ).add(
                            portal.transformLocalVec(portal.axisH).scale(offset.y)
                        ).add(
                            portal.transformLocalVec(portal.getNormal()).scale(offset.z)
                        )
                    );
                    reloadPortal(portal);
                }))
            )
        );
        
        builder.then(Commands.literal("set_portal_size")
            .then(Commands.argument("width", DoubleArgumentType.doubleArg(0))
                .then(Commands.argument("height", DoubleArgumentType.doubleArg(0))
                    .executes(context -> processPortalTargetedCommand(context, portal -> {
                        double width = DoubleArgumentType.getDouble(context, "width");
                        double height = DoubleArgumentType.getDouble(context, "height");
                        
                        portal.width = width;
                        portal.height = height;
                        portal.specialShape = null;
                        
                        reloadPortal(portal);
                    }))
                )
            )
        );
        
        builder.then(Commands.literal("adjust_portal_to_fit_square_frame")
            .executes(context -> processPortalTargetedCommand(context, portal -> {
                adjustPortalAreaToFitFrame(portal);
                
                reloadPortal(portal);
            }))
        );
        
        builder.then(Commands.literal("rotate_portals_around")
            .then(Commands.argument("portals", EntityArgument.entities())
                .then(Commands.argument("origin", Vec3Argument.vec3())
                    .then(Commands.argument("axis", Vec3Argument.vec3(false))
                        .then(Commands.argument("angle", DoubleArgumentType.doubleArg())
                            .executes(context -> {
                                Collection<? extends Entity> portals = EntityArgument.getEntities(context, "portals");
                                Vec3 origin = Vec3Argument.getVec3(context, "origin");
                                Vec3 axis = Vec3Argument.getVec3(context, "axis");
                                double angle = DoubleArgumentType.getDouble(context, "angle");
                                
                                DQuaternion quaternion = DQuaternion.rotationByDegrees(axis.normalize(), angle);
                                
                                for (Entity entity : portals) {
                                    if (entity instanceof Portal portal) {
                                        Vec3 offset = portal.getOriginPos().subtract(origin);
                                        Vec3 offsetRotated = quaternion.rotate(offset);
                                        portal.setOriginPos(offsetRotated.add(origin));
                                        portal.axisW = quaternion.rotate(portal.axisW);
                                        portal.axisH = quaternion.rotate(portal.axisH);
                                        
                                        portal.setRotationTransformationD(portal.getRotationD().hamiltonProduct(quaternion.getConjugated()));
                                    }
                                    else {
                                        context.getSource().sendFailure(new TextComponent("the entity is not a portal"));
                                    }
                                }
                                
                                for (Entity entity : portals) {
                                    if (entity instanceof Portal portal) {
                                        reloadPortal(portal);
                                    }
                                }
                                
                                return 0;
                            })
                        )
                    )
                )
            )
        );
    }
    
    private static void adjustPortalAreaToFitFrame(Portal portal) {
        BlockPos origin = new BlockPos(portal.getOriginPos());
        
        Direction portalNormalDirection =
            Direction.getNearest(portal.getNormal().x, portal.getNormal().y, portal.getNormal().z);
        
        Level world = portal.level;
        
        IntBox boxArea = Helper.expandRectangle(
            origin, pos -> world.getBlockState(pos).isAir(),
            portalNormalDirection.getAxis()
        );
        
        AABB portalBox = new AABB(0, 0, 0, 0, 0, 0);
        for (Direction direction : Direction.values()) {
            IntBox outerSurface = boxArea.getSurfaceLayer(direction).getMoved(direction.getNormal());
            AABB collisionBox = McHelper.getWallBox(world, outerSurface);
            if (collisionBox == null) {
                collisionBox = outerSurface.toRealNumberBox();
            }
            portalBox = Helper.replaceBoxCoordinate(
                portalBox, direction,
                Helper.getBoxCoordinate(collisionBox, direction.getOpposite())
            );
        }
        
        double portalNormalCoordinate = Helper.getCoordinate(portal.getOriginPos(), portalNormalDirection.getAxis());
        
        portalBox = Helper.replaceBoxCoordinate(portalBox, portalNormalDirection, portalNormalCoordinate);
        portalBox = Helper.replaceBoxCoordinate(portalBox, portalNormalDirection.getOpposite(), portalNormalCoordinate);
        
        portal.specialShape = null;
        PortalAPI.setPortalOrthodoxShape(portal, portalNormalDirection, portalBox);
    }
    
    public static void reloadPortal(Portal portal) {
        portal.rectifyClusterPortals();
        portal.reloadAndSyncToClient();
    }
    
    private static void registerPortalTargetedCommandWithRotationArgument(
        LiteralArgumentBuilder<CommandSourceStack> builder,
        String literal,
        BiConsumer<Portal, Quaternion> func
    ) {
        builder.then(Commands.literal(literal)
            .then(Commands.argument("rotatingAxis", Vec3Argument.vec3(false))
                .then(Commands.argument("angleDegrees", DoubleArgumentType.doubleArg())
                    .executes(context -> processPortalTargetedCommand(
                        context,
                        portal -> {
                            sendEditBiWayPortalWarning(context, portal);
                            
                            Vec3 rotatingAxis = Vec3Argument.getVec3(
                                context, "rotatingAxis"
                            ).normalize();
                            
                            double angleDegrees = DoubleArgumentType.getDouble(
                                context, "angleDegrees"
                            );
                            
                            Quaternion rot = angleDegrees != 0 ? new Quaternion(
                                new Vector3f(rotatingAxis),
                                (float) angleDegrees,
                                true
                            ) : null;
                            
                            func.accept(portal, rot);
                            
                            reloadPortal(portal);
                        }
                    ))
                )
            )
        );
        
        builder.then(Commands.literal(literal + "_along")
            .then(Commands.literal("x")
                .then(Commands.argument("angleDegrees", DoubleArgumentType.doubleArg())
                    .executes(context -> processPortalTargetedCommand(
                        context,
                        portal -> {
                            sendEditBiWayPortalWarning(context, portal);
                            
                            Vector3f axis = new Vector3f(1, 0, 0);
                            
                            double angleDegrees =
                                DoubleArgumentType.getDouble(context, "angleDegrees");
                            
                            Quaternion rot = angleDegrees != 0 ? new Quaternion(
                                axis,
                                (float) angleDegrees,
                                true
                            ) : null;
                            
                            func.accept(portal, rot);
                            
                            reloadPortal(portal);
                        }
                    ))
                )
            )
            .then(Commands.literal("y")
                .then(Commands.argument("angleDegrees", DoubleArgumentType.doubleArg())
                    .executes(context -> processPortalTargetedCommand(
                        context,
                        portal -> {
                            sendEditBiWayPortalWarning(context, portal);
                            
                            Vector3f axis = new Vector3f(0, 1, 0);
                            
                            double angleDegrees =
                                DoubleArgumentType.getDouble(context, "angleDegrees");
                            
                            Quaternion rot = angleDegrees != 0 ? new Quaternion(
                                axis,
                                (float) angleDegrees,
                                true
                            ) : null;
                            
                            func.accept(portal, rot);
                            
                            reloadPortal(portal);
                        }
                    ))
                )
            )
            .then(Commands.literal("z")
                .then(Commands.argument("angleDegrees", DoubleArgumentType.doubleArg())
                    .executes(context -> processPortalTargetedCommand(
                        context,
                        portal -> {
                            sendEditBiWayPortalWarning(context, portal);
                            
                            Vector3f axis = new Vector3f(0, 0, 1);
                            
                            double angleDegrees =
                                DoubleArgumentType.getDouble(context, "angleDegrees");
                            
                            Quaternion rot = angleDegrees != 0 ? new Quaternion(
                                axis,
                                (float) angleDegrees,
                                true
                            ) : null;
                            
                            func.accept(portal, rot);
                            
                            reloadPortal(portal);
                        }
                    ))
                )
            )
        );
    }
    
    private static void setPortalRotation(Portal portal, Vector3f axis, double angleDegrees) {
        if (angleDegrees != 0) {
            portal.rotation = new Quaternion(
                axis, (float) angleDegrees, true
            );
        }
        else {
            portal.rotation = null;
        }
        
        reloadPortal(portal);
    }
    
    // only portal custom data can be changed
    private static void setPortalNbt(Portal portal, CompoundTag newNbt) {
        CompoundTag data = portal.writePortalDataToNbt();
        
        newNbt.getAllKeys().forEach(
            key -> data.put(key, newNbt.get(key))
        );
        
        portal.readPortalDataFromNbt(data);
        
        reloadPortal(portal);
    }
    
    private static void registerCBPortalCommands(
        LiteralArgumentBuilder<CommandSourceStack> builder
    ) {
        builder.then(Commands.literal("cb_make_portal")
            .then(Commands.argument("width", DoubleArgumentType.doubleArg())
                .then(Commands.argument("height", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("from", EntityArgument.entity())
                        .then(Commands.argument("to", EntityArgument.entity())
                            .executes(context -> {
                                double width = DoubleArgumentType.getDouble(context, "width");
                                double height = DoubleArgumentType.getDouble(context, "height");
                                
                                Entity fromEntity = EntityArgument.getEntity(context, "from");
                                Entity toEntity = EntityArgument.getEntity(context, "to");
                                
                                invokeCbMakePortal(width, height, fromEntity, toEntity, "");
                                
                                return 0;
                            })
                            .then(Commands.argument("portalName", StringArgumentType.string())
                                .executes(context -> {
                                    double width = DoubleArgumentType.getDouble(context, "width");
                                    double height = DoubleArgumentType.getDouble(context, "height");
                                    
                                    Entity fromEntity = EntityArgument.getEntity(context, "from");
                                    Entity toEntity = EntityArgument.getEntity(context, "to");
                                    
                                    String portalName = StringArgumentType.getString(context, "portalName");
                                    
                                    invokeCbMakePortal(width, height, fromEntity, toEntity, portalName);
                                    
                                    return 0;
                                })
                            )
                        )
                    )
                )
            )
        );
    }
    
    private static void invokeCbMakePortal(
        double width, double height, Entity fromEntity, Entity toEntity,
        String portalName
    ) {
        Portal portal = Portal.entityType.create(fromEntity.level);
        
        portal.setPos(fromEntity.getX(), fromEntity.getY(), fromEntity.getZ());
        
        portal.dimensionTo = toEntity.level.dimension();
        portal.setDestination(toEntity.position());
        portal.width = width;
        portal.height = height;
        
        Vec3 normal = fromEntity.getViewVector(1);
        Vec3 rightVec = getRightVec(fromEntity);
        
        Vec3 axisH = rightVec.cross(normal).normalize();
        
        portal.axisW = rightVec;
        portal.axisH = axisH;
        
        portal.setCustomName(new TextComponent(portalName));
        
        McHelper.spawnServerEntity(portal);
    }
    
    private static void registerUtilityCommands(
        LiteralArgumentBuilder<CommandSourceStack> builder
    ) {
        builder.then(Commands.literal("tpme")
            .then(Commands.argument("target", EntityArgument.entity())
                .executes(context -> {
                    Entity entity = EntityArgument.getEntity(context, "target");
                    
                    IPGlobal.serverTeleportationManager.invokeTpmeCommand(
                        context.getSource().getPlayerOrException(),
                        entity.level.dimension(),
                        entity.position()
                    );
                    
                    context.getSource().sendSuccess(
                        new TranslatableComponent(
                            "imm_ptl.command.tpme.success",
                            entity.getDisplayName()
                        ),
                        true
                    );
                    
                    return 1;
                })
            )
            .then(Commands.argument("dest", Vec3Argument.vec3())
                .executes(context -> {
                    Vec3 dest = Vec3Argument.getVec3(context, "dest");
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    
                    IPGlobal.serverTeleportationManager.invokeTpmeCommand(
                        player,
                        player.level.dimension(),
                        dest
                    );
                    
                    context.getSource().sendSuccess(
                        new TranslatableComponent(
                            "imm_ptl.command.tpme.success",
                            dest.toString()
                        ),
                        true
                    );
                    
                    return 1;
                })
            )
            .then(Commands.argument("dim", DimensionArgument.dimension())
                .then(Commands.argument("dest", Vec3Argument.vec3())
                    .executes(context -> {
                        ResourceKey<Level> dim = DimensionArgument.getDimension(
                            context,
                            "dim"
                        ).dimension();
                        Vec3 dest = Vec3Argument.getVec3(context, "dest");
                        
                        IPGlobal.serverTeleportationManager.invokeTpmeCommand(
                            context.getSource().getPlayerOrException(),
                            dim,
                            dest
                        );
                        
                        context.getSource().sendSuccess(
                            new TranslatableComponent(
                                "imm_ptl.command.tpme.success",
                                McHelper.dimensionTypeId(dim).toString() + dest.toString()
                            ),
                            true
                        );
                        
                        return 1;
                    })
                )
            )
        );
        
        builder.then(Commands.literal("tp")
            .requires(commandSource -> commandSource.hasPermission(2))
            .then(Commands.argument("from", EntityArgument.entities())
                .then(Commands.argument("to", EntityArgument.entity())
                    .executes(context -> {
                        Collection<? extends Entity> entities =
                            EntityArgument.getEntities(context, "from");
                        Entity target = EntityArgument.getEntity(context, "to");
                        
                        int numTeleported = teleport(
                            entities,
                            target.level.dimension(),
                            target.position()
                        );
                        
                        context.getSource().sendSuccess(
                            new TranslatableComponent(
                                "imm_ptl.command.tp.success",
                                numTeleported,
                                target.getDisplayName()
                            ),
                            true
                        );
                        
                        return numTeleported;
                    })
                )
                .then(Commands.argument("dest", Vec3Argument.vec3())
                    .executes(context -> {
                        Collection<? extends Entity> entities =
                            EntityArgument.getEntities(context, "from");
                        Vec3 dest = Vec3Argument.getVec3(context, "dest");
                        
                        int numTeleported = teleport(
                            entities,
                            context.getSource().getLevel().dimension(),
                            dest
                        );
                        
                        context.getSource().sendSuccess(
                            new TranslatableComponent(
                                "imm_ptl.command.tp.success",
                                numTeleported,
                                dest.toString()
                            ),
                            true
                        );
                        
                        return numTeleported;
                    })
                )
                .then(Commands.argument("dim", DimensionArgument.dimension())
                    .then(Commands.argument("dest", Vec3Argument.vec3())
                        .executes(context -> {
                            Collection<? extends Entity> entities =
                                EntityArgument.getEntities(context, "from");
                            ResourceKey<Level> dim = DimensionArgument.getDimension(
                                context,
                                "dim"
                            ).dimension();
                            Vec3 dest = Vec3Argument.getVec3(context, "dest");
                            
                            int numTeleported = teleport(
                                entities,
                                context.getSource().getLevel().dimension(),
                                dest
                            );
                            
                            context.getSource().sendSuccess(
                                new TranslatableComponent(
                                    "imm_ptl.command.tp.success",
                                    numTeleported,
                                    McHelper.dimensionTypeId(dim).toString() + dest.toString()
                                ),
                                true
                            );
                            
                            return numTeleported;
                        })
                    )
                )
            )
        );
        
        
        // By LoganDark :D
        builder.then(Commands.literal("make_portal")
            .then(Commands.argument("width", DoubleArgumentType.doubleArg())
                .then(Commands.argument("height", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("to", DimensionArgument.dimension())
                        .then(Commands.argument("dest", Vec3Argument.vec3(false))
                            .executes(PortalCommand::placePortalAbsolute)
                        )
                        .then(Commands.literal("shift")
                            .then(Commands.argument("dist", DoubleArgumentType.doubleArg())
                                .executes(PortalCommand::placePortalShift)
                            )
                        )
                    )
                )
            )
        );
        
        builder.then(Commands.literal("goback")
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                net.minecraft.util.Tuple<ResourceKey<Level>, Vec3> lastPos =
                    IPGlobal.serverTeleportationManager.lastPosition.get(player);
                if (lastPos == null) {
                    sendMessage(context, "You haven't teleported");
                }
                else {
                    IPGlobal.serverTeleportationManager.invokeTpmeCommand(
                        player, lastPos.getA(), lastPos.getB()
                    );
                }
                return 0;
            })
        );
        
        builder.then(Commands.literal("create_small_inward_wrapping")
            .then(Commands.argument("p1", Vec3Argument.vec3(false))
                .then(Commands.argument("p2", Vec3Argument.vec3(false))
                    .executes(context -> {
                        Vec3 p1 = Vec3Argument.getVec3(context, "p1");
                        Vec3 p2 = Vec3Argument.getVec3(context, "p2");
                        AABB box = new AABB(p1, p2);
                        ServerLevel world = context.getSource().getLevel();
                        addSmallWorldWrappingPortals(box, world, true);
                        return 0;
                    })
                )
            )
        );
        
        builder.then(Commands.literal("create_small_outward_wrapping")
            .then(Commands.argument("p1", Vec3Argument.vec3(false))
                .then(Commands.argument("p2", Vec3Argument.vec3(false))
                    .executes(context -> {
                        Vec3 p1 = Vec3Argument.getVec3(context, "p1");
                        Vec3 p2 = Vec3Argument.getVec3(context, "p2");
                        AABB box = new AABB(p1, p2);
                        ServerLevel world = context.getSource().getLevel();
                        addSmallWorldWrappingPortals(box, world, false);
                        return 0;
                    })
                )
            )
        );
        
        builder.then(Commands.literal("create_scaled_box_view")
            .then(Commands.argument("p1", BlockPosArgument.blockPos())
                .then(Commands.argument("p2", BlockPosArgument.blockPos())
                    .then(Commands.argument("scale", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("placeTargetEntity", EntityArgument.entity())
                            .then(Commands.argument("biWay", BoolArgumentType.bool())
                                .executes(context -> {
                                    invokeCreateScaledViewCommand(
                                        context,
                                        false,
                                        false,
                                        false,
                                        false,
                                        BoolArgumentType.getBool(context, "biWay")
                                    );
                                    return 0;
                                })
                                .then(Commands.argument("teleportChangesScale", BoolArgumentType.bool())
                                    .executes(context -> {
                                        boolean teleportChangesScale = BoolArgumentType.getBool(context, "teleportChangesScale");
                                        invokeCreateScaledViewCommand(
                                            context,
                                            teleportChangesScale,
                                            false,
                                            false,
                                            false,
                                            BoolArgumentType.getBool(context, "biWay")
                                        );
                                        return 0;
                                    })
                                )
                            )
                        )
                    )
                )
            )
        );
        
        builder.then(Commands.literal("create_scaled_box_view_optimized")
            .then(Commands.argument("p1", BlockPosArgument.blockPos())
                .then(Commands.argument("p2", BlockPosArgument.blockPos())
                    .then(Commands.argument("scale", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("placeTargetEntity", EntityArgument.entity())
                            .executes(context -> {
                                invokeCreateScaledViewCommand(
                                    context,
                                    false,
                                    true,
                                    true,
                                    true,
                                    true
                                );
                                return 0;
                            })
                        )
                    )
                )
            )
        );
        
        builder.then(Commands
            .literal("create_connected_rooms")
            .then(Commands
                .literal("roomSize")
                .then(Commands
                    .argument("roomSize", BlockPosArgument.blockPos())
                    .then(Commands
                        .literal("roomNumber")
                        .then(Commands
                            .argument("roomNumber", IntegerArgumentType.integer(2, 500))
                            .executes(context -> {
                                BlockPos roomSize =
                                    BlockPosArgument.getSpawnablePos(context, "roomSize");
                                int roomNumber = IntegerArgumentType.getInteger(context, "roomNumber");
                                
                                createConnectedRooms(
                                    context.getSource().getLevel(),
                                    new BlockPos(context.getSource().getPosition()),
                                    roomSize,
                                    roomNumber,
                                    text -> context.getSource().sendSuccess(text, false)
                                );
                                
                                return 0;
                            })
                        )
                    )
                )
            )
        );
        
        builder.then(Commands.literal("adjust_rotation_to_connect")
            .then(Commands.argument("portal1", EntityArgument.entity())
                .then(Commands.argument("portal2", EntityArgument.entity())
                    .executes(context -> {
                        Entity e1 = EntityArgument.getEntity(context, "portal1");
                        Entity e2 = EntityArgument.getEntity(context, "portal2");
                        
                        if (!(e1 instanceof Portal)) {
                            context.getSource().sendFailure(
                                new TextComponent("portal1 is not a portal entity"));
                            return 0;
                        }
                        
                        if (!(e2 instanceof Portal)) {
                            context.getSource().sendFailure(
                                new TextComponent("portal2 is not a portal entity"));
                            return 0;
                        }
                        
                        Portal portal1 = (Portal) e1;
                        Portal portal2 = (Portal) e2;
                        
                        portal1.setDestination(portal2.getOriginPos());
                        portal2.setDestination(portal1.getOriginPos());
                        
                        PortalManipulation.adjustRotationToConnect(portal1, portal2);
                        
                        portal1.reloadAndSyncToClient();
                        portal2.reloadAndSyncToClient();
                        
                        return 0;
                    })
                )
            )
        );
        
        builder.then(Commands
            .literal("dimension_stack")
            .requires(commandSource -> commandSource.hasPermission(2))
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                
                List<String> dimIdList = new ArrayList<>();
                for (ServerLevel world : MiscHelper.getServer().getAllLevels()) {
                    dimIdList.add(world.dimension().location().toString());
                }
                
                McRemoteProcedureCall.tellClientToInvoke(
                    player,
                    "qouteall.imm_ptl.peripheral.altius_world.AltiusManagement.RemoteCallables.clientOpenScreen",
                    dimIdList
                );
                
                return 0;
            })
        );
        
        builder.then(Commands
            .literal("wiki")
            .executes(context -> {
                McRemoteProcedureCall.tellClientToInvoke(
                    context.getSource().getPlayerOrException(),
                    "qouteall.imm_ptl.peripheral.guide.IPGuide.RemoteCallables.showWiki"
                );
                return 0;
            })
        );
    }
    
    private static void createConnectedRooms(
        ServerLevel world, BlockPos startingPos,
        BlockPos roomSize, int roomNumber,
        Consumer<Component> feedbackSender
    ) {
        BlockPos roomAreaSize = roomSize.offset(2, 2, 2);
        
        List<IntBox> roomAreaList = new ArrayList<>();
        
        Helper.SimpleBox<BlockPos> currentSearchingCenter =
            new Helper.SimpleBox<>(startingPos);
        
        IPGlobal.serverTaskList.addTask(MyTaskList.chainTask(
            MyTaskList.repeat(
                roomNumber,
                () -> MyTaskList.withDelay(20, MyTaskList.oneShotTask(() -> {
                    
                    currentSearchingCenter.obj = currentSearchingCenter.obj.offset(getRandomShift(20));
                    
                    IntBox airCube = NetherPortalMatcher.findCubeAirAreaAtAnywhere(
                        roomAreaSize.offset(6, 6, 6),
                        world,
                        currentSearchingCenter.obj,
                        128
                    );
                    if (airCube == null) {
                        feedbackSender.accept(new TextComponent("Cannot find space for placing room"));
                        return;
                    }
                    airCube = airCube.getSubBoxInCenter(roomAreaSize);
                    
                    fillRoomFrame(world, airCube, getRandomBlock());
                    roomAreaList.add(airCube);
                }))
            ),
            MyTaskList.oneShotTask(() -> {
                Stream<IntBox> roomsStream = Stream.concat(
                    roomAreaList.stream(),
                    Stream.of(roomAreaList.get(0))
                );
                Helper.wrapAdjacentAndMap(
                    roomsStream, Pair::new
                ).forEach(pair -> {
                    
                    IntBox room1Area = pair.getFirst();
                    IntBox room2Area = pair.getSecond();
                    IntBox room1 = room1Area.getAdjusted(1, 1, 1, -1, -1, -1);
                    IntBox room2 = room2Area.getAdjusted(1, 1, 1, -1, -1, -1);
                    
                    Portal portal = Portal.entityType.create(world);
                    Validate.notNull(portal);
                    portal.setOriginPos(room1.getCenterVec().add(
                        roomSize.getX() / 4.0, 0, 0
                    ));
                    portal.setDestinationDimension(world.dimension());
                    portal.setDestination(room2.getCenterVec().add(
                        roomSize.getX() / 4.0, 0, 0
                    ));
                    portal.setOrientationAndSize(
                        new Vec3(1, 0, 0),
                        new Vec3(0, 1, 0),
                        roomSize.getX() / 2.0,
                        roomSize.getY()
                    );
                    portal.portalTag = "imm_ptl:room_connection";
                    
                    McHelper.spawnServerEntity(portal);
                    
                    Portal reversePortal = PortalAPI.createReversePortal(portal);
                    McHelper.spawnServerEntity(reversePortal);
                });
                
                feedbackSender.accept(new TextComponent("finished"));
            })
        ));
    }
    
    private static BlockState getRandomBlock() {
        Random random = new Random();
        
        for (; ; ) {
            Block block = Registry.BLOCK.getRandom(random).get().value();
            BlockState state = block.defaultBlockState();
            Material material = state.getMaterial();
            if (material.blocksMotion() && material.getPushReaction() == PushReaction.NORMAL
                && !material.isLiquid()
            ) {
                return state;
            }
        }
    }
    
    private static void fillRoomFrame(
        ServerLevel world, IntBox roomArea, BlockState blockState
    ) {
        for (Direction direction : Direction.values()) {
            IntBox surface = roomArea.getSurfaceLayer(direction);
            surface.fastStream().forEach(blockPos -> {
                world.setBlockAndUpdate(blockPos, blockState);
            });
        }
    }
    
    
    private static void invokeCreateScaledViewCommand(
        CommandContext<CommandSourceStack> context,
        boolean teleportChangesScale,
        boolean outerFuseView,
        boolean outerRenderingMergable,
        boolean innerRenderingMergable,
        boolean isBiWay
    ) throws CommandSyntaxException {
        
        BlockPos bp1 = BlockPosArgument.getSpawnablePos(context, "p1");
        BlockPos bp2 = BlockPosArgument.getSpawnablePos(context, "p2");
        IntBox intBox = new IntBox(bp1, bp2);
        
        Entity placeTargetEntity =
            EntityArgument.getEntity(context, "placeTargetEntity");
        
        ServerLevel boxWorld = ((ServerLevel) placeTargetEntity.level);
        Vec3 boxBottomCenter = placeTargetEntity.position();
        AABB area = intBox.toRealNumberBox();
        ServerLevel areaWorld = context.getSource().getLevel();
        
        double scale = DoubleArgumentType.getDouble(context, "scale");
        
        
        PortalManipulation.createScaledBoxView(
            areaWorld, area, boxWorld, boxBottomCenter, scale,
            isBiWay, teleportChangesScale,
            outerFuseView, outerRenderingMergable, innerRenderingMergable,
            false
        );
    }
    
    
    private static void addSmallWorldWrappingPortals(AABB box, ServerLevel world, boolean isInward) {
        for (Direction direction : Direction.values()) {
            Portal portal = Portal.entityType.create(world);
            WorldWrappingPortal.initWrappingPortal(
                world, box, direction, isInward, portal
            );
            McHelper.spawnServerEntity(portal);
        }
    }
    
    private static int processPortalArgumentedCBCommand(
        CommandContext<CommandSourceStack> context,
        PortalConsumerThrowsCommandSyntaxException invoker
    ) throws CommandSyntaxException {
        Collection<? extends Entity> entities = EntityArgument.getEntities(
            context, "portal"
        );
        
        for (Entity portalEntity : entities) {
            if (portalEntity instanceof Portal) {
                Portal portal = (Portal) portalEntity;
                
                invoker.accept(portal);
            }
            else {
                sendMessage(context, "The target should be portal");
            }
        }
        
        return 0;
    }
    
    private static void sendEditBiWayPortalWarning(
        CommandContext<CommandSourceStack> context, Portal portal
    ) {
        if (!PortalExtension.get(portal).bindCluster) {
            if (PortalManipulation.findReversePortal(portal) != null) {
                sendMessage(context, "You are editing a bi-way portal." +
                    " It's recommended to enable bindCluster or you will get unlinked portal entities." +
                    " Use command /portal set_portal_nbt {bindCluster:true}"
                );
            }
        }
        
        if (portal instanceof BreakablePortalEntity breakablePortalEntity) {
            if (!breakablePortalEntity.unbreakable) {
                sendMessage(context, "You are editing a breakable portal. " +
                    "It may break if its state is abnormal. " +
                    "It's recommended to make it unbreakable by /portal set_portal_nbt {unbreakable:true}"
                );
            }
        }
    }
    
    private static void invokeCompleteBiWayBiFacedPortal(
        CommandContext<CommandSourceStack> context,
        Portal portal
    ) {
        PortalManipulation.completeBiWayBiFacedPortal(
            portal,
            p -> sendMessage(context, "Removed " + p),
            p -> sendMessage(context, "Added " + p), Portal.entityType
        );
    }
    
    private static void invokeCompleteBiFacedPortal(
        CommandContext<CommandSourceStack> context,
        Portal portal
    ) {
        PortalManipulation.removeOverlappedPortals(
            ((ServerLevel) portal.level),
            portal.getOriginPos(),
            portal.getNormal().scale(-1),
            p -> Objects.equals(portal.specificPlayerId, p.specificPlayerId),
            p -> sendMessage(context, "Removed " + p)
        );
        
        Portal result = PortalManipulation.completeBiFacedPortal(
            portal,
            Portal.entityType
        );
        sendMessage(context, "Added " + result);
    }
    
    private static void invokeCompleteBiWayPortal(
        CommandContext<CommandSourceStack> context,
        Portal portal
    ) {
        PortalManipulation.removeOverlappedPortals(
            MiscHelper.getServer().getLevel(portal.dimensionTo),
            portal.getDestPos(),
            portal.transformLocalVecNonScale(portal.getNormal().scale(-1)),
            p -> Objects.equals(portal.specificPlayerId, p.specificPlayerId),
            p -> sendMessage(context, "Removed " + p)
        );
        
        Portal result = PortalManipulation.completeBiWayPortal(
            portal,
            Portal.entityType
        );
        sendMessage(context, "Added " + result);
    }
    
    private static void removeSpecificAccessor(
        CommandContext<CommandSourceStack> context,
        Portal portal
    ) {
        portal.specificPlayerId = null;
        sendMessage(context, "This portal can be accessed by all players now");
        sendMessage(context, portal.toString());
    }
    
    private static void setSpecificAccessor(
        CommandContext<CommandSourceStack> context,
        Portal portal, Entity player
    ) {
        
        portal.specificPlayerId = player.getUUID();
        
        sendMessage(
            context,
            "This portal can only be accessed by " +
                player.getName().getContents() + " now"
        );
        sendMessage(context, portal.toString());
    }
    
    private static void removeMultidestEntry(
        CommandContext<CommandSourceStack> context,
        Portal pointedPortal,
        ServerPlayer player
    ) {
        PortalManipulation.getPortalCluster(
            pointedPortal.level,
            pointedPortal.getOriginPos(),
            pointedPortal.getNormal(),
            p -> true
        ).stream().filter(
            portal -> player.getUUID().equals(portal.specificPlayerId) || portal.specificPlayerId == null
        ).forEach(
            portal -> {
                PortalManipulation.removeConnectedPortals(
                    portal,
                    (p) -> sendMessage(context, "removed " + p.toString())
                );
                sendMessage(context, "removed " + portal.toString());
                portal.remove(Entity.RemovalReason.KILLED);
            }
        );
    }
    
    private static void setMultidestEntry(
        CommandContext<CommandSourceStack> context,
        Portal pointedPortal,
        ServerPlayer player,
        ResourceKey<Level> dimension,
        Vec3 destination,
        boolean biFaced,
        boolean biWay
    ) {
        Portal newPortal = PortalManipulation.copyPortal(
            pointedPortal, Portal.entityType
        );
        
        removeMultidestEntry(context, pointedPortal, player);
        
        newPortal.dimensionTo = dimension;
        newPortal.setDestination(destination);
        newPortal.specificPlayerId = player.getUUID();
        
        McHelper.spawnServerEntity(newPortal);
        
        configureBiWayBiFaced(newPortal, biWay, biFaced);
    }
    
    private static void configureBiWayBiFaced(Portal newPortal, boolean biWay, boolean biFaced) {
        if (biFaced && biWay) {
            PortalManipulation.completeBiWayBiFacedPortal(
                newPortal,
                p -> {
                },
                p -> {
                },
                Portal.entityType
            );
        }
        else if (biFaced) {
            PortalManipulation.completeBiFacedPortal(newPortal, Portal.entityType);
        }
        else if (biWay) {
            PortalManipulation.completeBiWayPortal(newPortal, Portal.entityType);
        }
    }
    
    public static void sendPortalInfo(CommandContext<CommandSourceStack> context, Portal portal) {
        context.getSource().sendSuccess(
            McHelper.compoundTagToTextSorted(
                portal.saveWithoutId(new CompoundTag()),
                " ",
                0
            ),
            false
        );
        
        sendMessage(
            context,
            portal.toString()
        );
        
        sendMessage(context,
            String.format("Orientation: %s", PortalAPI.getPortalOrientationQuaternion(portal))
        );
        
        if (portal.getRotation() != null) {
            sendMessage(context,
                String.format("Rotating Transformation: %s",
                    DQuaternion.fromMcQuaternion(portal.getRotation())
                )
            );
        }
    }
    
    public static void sendMessage(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(
            new TextComponent(message),
            false
        );
    }
    
    /**
     * Gets success message based on the portal {@code portal}.
     *
     * @param portal The portal to send the success message for.
     * @return The success message, as a {@link Component}.
     * @author LoganDark
     */
    private static Component getMakePortalSuccess(Portal portal) {
        return new TranslatableComponent(
            "imm_ptl.command.make_portal.success",
            Double.toString(portal.width),
            Double.toString(portal.height),
            McHelper.dimensionTypeId(portal.level.dimension()).toString(),
            portal.getOriginPos().toString(),
            McHelper.dimensionTypeId(portal.dimensionTo).toString(),
            portal.getDestPos().toString()
        );
    }
    
    // By LoganDark :D
    private static int placePortalAbsolute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double width = DoubleArgumentType.getDouble(context, "width");
        double height = DoubleArgumentType.getDouble(context, "height");
        ResourceKey<Level> to = DimensionArgument.getDimension(context, "to").dimension();
        Vec3 dest = Vec3Argument.getVec3(context, "dest");
        
        Portal portal = PortalManipulation.placePortal(width, height, context.getSource().getPlayerOrException());
        
        if (portal == null) {
            return 0;
        }
        
        portal.dimensionTo = to;
        portal.setDestination(dest);
        McHelper.spawnServerEntity(portal);
        
        context.getSource().sendSuccess(getMakePortalSuccess(portal), true);
        
        return 1;
    }
    
    // By LoganDark :D
    private static int placePortalShift(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double width = DoubleArgumentType.getDouble(context, "width");
        double height = DoubleArgumentType.getDouble(context, "height");
        ResourceKey<Level> to = DimensionArgument.getDimension(context, "to").dimension();
        double dist = DoubleArgumentType.getDouble(context, "dist");
        
        Portal portal = PortalManipulation.placePortal(width, height, context.getSource().getPlayerOrException());
        
        if (portal == null) {
            return 0;
        }
        
        // unsafe to use getContentDirection before the destination is fully set
        portal.dimensionTo = to;
        portal.setDestination(portal.getOriginPos().add(portal.axisW.cross(portal.axisH).scale(-dist)));
        
        McHelper.spawnServerEntity(portal);
        
        context.getSource().sendSuccess(getMakePortalSuccess(portal), true);
        
        return 1;
    }
    
    public static int teleport(
        Collection<? extends Entity> entities,
        ResourceKey<Level> targetDim,
        Vec3 targetPos
    ) {
        ServerLevel targetWorld = MiscHelper.getServer().getLevel(targetDim);
        
        int numTeleported = 0;
        
        for (Entity entity : entities) {
            ServerTeleportationManager.teleportEntityGeneral(entity, targetPos, targetWorld);
            
            numTeleported++;
        }
        
        return numTeleported;
    }
    
    public static BlockPos getRandomShift(int len) {
        Random rand = new Random();
        return new BlockPos(
            (rand.nextDouble() * 2 - 1) * len,
            (rand.nextDouble() * 2 - 1) * len,
            (rand.nextDouble() * 2 - 1) * len
        );
    }
    
    public static interface PortalConsumerThrowsCommandSyntaxException {
        void accept(Portal portal) throws CommandSyntaxException;
    }
    
    public static int processPortalTargetedCommand(
        CommandContext<CommandSourceStack> context,
        PortalConsumerThrowsCommandSyntaxException processCommand
    ) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Entity entity = source.getEntity();
        
        if (entity instanceof ServerPlayer) {
            ServerPlayer player = ((ServerPlayer) entity);
            
            Portal portal = getPlayerPointingPortal(player, false);
            
            if (portal == null) {
                source.sendSuccess(
                    new TextComponent("You are not pointing to any non-global portal." +
                        " (This command cannot process global portals)"),
                    false
                );
                return 0;
            }
            else {
                processCommand.accept(portal);
            }
        }
        else if (entity instanceof Portal) {
            processCommand.accept(((Portal) entity));
        }
        else {
            source.sendSuccess(
                new TextComponent(
                    "The command executor should be either a player or a portal entity"
                ),
                false
            );
        }
        
        return 0;
    }
    
    public static Portal getPlayerPointingPortal(
        ServerPlayer player, boolean includeGlobalPortal
    ) {
        return getPlayerPointingPortalRaw(player, 1, 100, includeGlobalPortal)
            .map(Pair::getFirst).orElse(null);
    }
    
    public static Optional<Pair<Portal, Vec3>> getPlayerPointingPortalRaw(
        Player player, float tickDelta, double maxDistance, boolean includeGlobalPortal
    ) {
        Vec3 from = player.getEyePosition(tickDelta);
        Vec3 to = from.add(player.getViewVector(tickDelta).scale(maxDistance));
        Level world = player.level;
        return raytracePortals(world, from, to, includeGlobalPortal);
    }
    
    public static Optional<Pair<Portal, Vec3>> raytracePortals(
        Level world, Vec3 from, Vec3 to, boolean includeGlobalPortal
    ) {
        Stream<Portal> portalStream = McHelper.getEntitiesNearby(
            world,
            from,
            Portal.class,
            from.distanceTo(to)
        ).stream();
        if (includeGlobalPortal) {
            List<Portal> globalPortals = GlobalPortalStorage.getGlobalPortals(world);
            portalStream = Streams.concat(
                portalStream,
                globalPortals.stream()
            );
        }
        return portalStream.map(
            portal -> new Pair<Portal, Vec3>(
                portal, portal.rayTrace(from, to)
            )
        ).filter(
            portalAndHitPos -> portalAndHitPos.getSecond() != null
        ).min(
            Comparator.comparingDouble(
                portalAndHitPos -> portalAndHitPos.getSecond().distanceToSqr(from)
            )
        );
    }
    
    private static void makePortalRound(Portal portal) {
        GeometryPortalShape shape = new GeometryPortalShape();
        final int triangleNum = 30;
        double twoPi = Math.PI * 2;
        shape.triangles = IntStream.range(0, triangleNum)
            .mapToObj(i -> new GeometryPortalShape.TriangleInPlane(
                0, 0,
                portal.width * 0.5 * Math.cos(twoPi * ((double) i) / triangleNum),
                portal.height * 0.5 * Math.sin(twoPi * ((double) i) / triangleNum),
                portal.width * 0.5 * Math.cos(twoPi * ((double) i + 1) / triangleNum),
                portal.height * 0.5 * Math.sin(twoPi * ((double) i + 1) / triangleNum)
            )).collect(Collectors.toList());
        portal.specialShape = shape;
        portal.cullableXStart = 0;
        portal.cullableXEnd = 0;
        portal.cullableYStart = 0;
        portal.cullableYEnd = 0;
    }
    
    /**
     * {@link Entity#getRotationVector()}
     */
    private static Vec3 getRightVec(Entity entity) {
        float yaw = entity.getYRot() + 90;
        float radians = -yaw * 0.017453292F;
        
        return new Vec3(
            Math.sin(radians), 0, Math.cos(radians)
        );
    }
    
    public static class RemoteCallables {
        @Environment(EnvType.CLIENT)
        public static void clientAccelerate(double v) {
            Minecraft client = Minecraft.getInstance();
            
            LocalPlayer player = client.player;
            
            McHelper.setWorldVelocity(
                player,
                player.getViewVector(1).scale(v / 20)
            );
        }
    }
    
}
