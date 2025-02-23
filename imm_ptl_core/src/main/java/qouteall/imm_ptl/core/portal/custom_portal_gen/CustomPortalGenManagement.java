package qouteall.imm_ptl.core.portal.custom_portal_gen;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.RegistryLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.RegistryResourceAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.mixin.common.IERegistryLoader;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.MiscHelper;
import qouteall.q_misc_util.my_util.UCoordinate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CustomPortalGenManagement {
    private static final Multimap<Item, CustomPortalGeneration> useItemGen = HashMultimap.create();
    private static final Multimap<Item, CustomPortalGeneration> throwItemGen = HashMultimap.create();
    
    private static final ArrayList<CustomPortalGeneration> convGen = new ArrayList<>();
    private static final Map<UUID, UCoordinate> playerPosBeforeTravel = new HashMap<>();
    
    /**
     * {@link CreateWorldScreen#tryApplyNewDataPacks(PackRepository)}
     */
    public static void onDatapackReload() {
        useItemGen.clear();
        throwItemGen.clear();
        convGen.clear();
        playerPosBeforeTravel.clear();
        
        if (!IPGlobal.enableDatapackPortalGen) {
            return;
        }
        
        Helper.log("Loading custom portal generation");
        
        Registry<CustomPortalGeneration> result = loadCustomPortalGenerations();
        if (result == null) return;
        
        result.entrySet().forEach((entry) -> {
            CustomPortalGeneration gen = entry.getValue();
            gen.identifier = entry.getKey().location();
            
            if (!gen.initAndCheck()) {
                Helper.log("Custom Portal Gen Is Not Activated " + gen.toString());
                return;
            }
            
            Helper.log("Loaded Custom Portal Gen " + entry.getKey().location());
            
            load(gen);
            
            if (gen.reversible) {
                CustomPortalGeneration reverse = gen.getReverse();
                
                if (reverse != null) {
                    reverse.identifier = entry.getKey().location();
                    if (gen.initAndCheck()) {
                        load(reverse);
                    }
                }
                else {
                    McHelper.sendMessageToFirstLoggedPlayer(new TextComponent(
                        "Cannot create reverse generation of " + gen
                    ));
                }
            }
        });
    }
    
    private static Registry<CustomPortalGeneration> loadCustomPortalGenerations() {
        MinecraftServer server = MiscHelper.getServer();
        
        RegistryAccess registryTracker = server.registryAccess();
        
        ResourceManager resourceManager = server.getResourceManager();
        
        RegistryOps<JsonElement> registryOps = RegistryOps.create(
            JsonOps.INSTANCE,
            registryTracker
        );
        
        MappedRegistry<CustomPortalGeneration> emptyRegistry = new MappedRegistry<>(
            CustomPortalGeneration.registryRegistryKey,
            Lifecycle.stable(), null
        );
        
        RegistryResourceAccess registryResourceAccess = RegistryResourceAccess.forResourceManager(resourceManager);
        
        RegistryLoader registryLoader = IERegistryLoader.construct(registryResourceAccess);
        DataResult<Registry<CustomPortalGeneration>> dataResult =
            ((IERegistryLoader) registryLoader).ip_overrideRegistryFromResources(
                emptyRegistry,
                CustomPortalGeneration.registryRegistryKey,
                CustomPortalGeneration.codec.codec(),
                registryOps
            
            );
        
        Registry<CustomPortalGeneration> result = dataResult.get().left().orElse(null);
        
        if (result == null) {
            DataResult.PartialResult<Registry<CustomPortalGeneration>> r =
                dataResult.get().right().get();
            McHelper.sendMessageToFirstLoggedPlayer(new TextComponent(
                "Error when parsing custom portal generation\n" +
                    r.message()
            ));
            return null;
        }
        return result;
    }
    
    private static void load(CustomPortalGeneration gen) {
        PortalGenTrigger trigger = gen.trigger;
        if (trigger instanceof PortalGenTrigger.UseItemTrigger) {
            useItemGen.put(((PortalGenTrigger.UseItemTrigger) trigger).item, gen);
        }
        else if (trigger instanceof PortalGenTrigger.ThrowItemTrigger) {
            throwItemGen.put(
                ((PortalGenTrigger.ThrowItemTrigger) trigger).item,
                gen
            );
        }
        else if (trigger instanceof PortalGenTrigger.ConventionalDimensionChangeTrigger) {
            convGen.add(gen);
        }
    }
    
    public static void onItemUse(UseOnContext context, InteractionResult actionResult) {
        if (context.getLevel().isClientSide()) {
            return;
        }
        
        Item item = context.getItemInHand().getItem();
        if (useItemGen.containsKey(item)) {
            // perform it in the second tick
            IPGlobal.serverTaskList.addTask(() -> {
                for (CustomPortalGeneration gen : useItemGen.get(item)) {
                    boolean result = gen.perform(
                        ((ServerLevel) context.getLevel()),
                        context.getClickedPos().relative(context.getClickedFace()),
                        context.getPlayer()
                    );
                    if (result) {
                        if (gen.trigger instanceof PortalGenTrigger.UseItemTrigger) {
                            PortalGenTrigger.UseItemTrigger trigger =
                                (PortalGenTrigger.UseItemTrigger) gen.trigger;
                            if (trigger.shouldConsume(context)) {
                                context.getItemInHand().shrink(1);
                            }
                        }
                        break;
                    }
                }
                return true;
            });
        }
    }
    
    public static void onItemTick(ItemEntity entity) {
        if (entity.level.isClientSide()) {
            return;
        }
        if (entity.getThrower() == null) {
            return;
        }
        
        if (entity.hasPickUpDelay()) {
            Item item = entity.getItem().getItem();
            if (throwItemGen.containsKey(item)) {
                IPGlobal.serverTaskList.addTask(() -> {
                    for (CustomPortalGeneration gen : throwItemGen.get(item)) {
                        boolean result = gen.perform(
                            ((ServerLevel) entity.level),
                            entity.blockPosition(),
                            entity
                        );
                        if (result) {
                            entity.getItem().shrink(1);
                            break;
                        }
                    }
                    return true;
                });
            }
        }
    }
    
    public static void onBeforeConventionalDimensionChange(
        ServerPlayer player
    ) {
        playerPosBeforeTravel.put(player.getUUID(), new UCoordinate(player));
    }
    
    public static void onAfterConventionalDimensionChange(
        ServerPlayer player
    ) {
        UUID uuid = player.getUUID();
        if (playerPosBeforeTravel.containsKey(uuid)) {
            UCoordinate startCoord = playerPosBeforeTravel.get(uuid);
            
            ServerLevel startWorld = McHelper.getServerWorld(startCoord.dimension);
            
            BlockPos startPos = new BlockPos(startCoord.pos);
            
            for (CustomPortalGeneration gen : convGen) {
                boolean succeeded = gen.perform(startWorld, startPos, player);
                
                if (succeeded) {
                    playerPosBeforeTravel.remove(uuid);
                    return;
                }
            }
        }
        playerPosBeforeTravel.remove(uuid);
    }
}
