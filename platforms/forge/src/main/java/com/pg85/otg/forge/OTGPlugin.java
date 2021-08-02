package com.pg85.otg.forge;

import java.nio.file.Path;

import com.pg85.otg.OTG;
import com.pg85.otg.config.dimensions.DimensionConfig;
import com.pg85.otg.constants.Constants;
import com.pg85.otg.forge.biome.OTGBiomeProvider;
import com.pg85.otg.forge.client.MultipleColorHandler;
import com.pg85.otg.forge.commands.OTGCommand;
import com.pg85.otg.forge.dimensions.OTGDimensionType;
import com.pg85.otg.forge.dimensions.OTGWorldType;
import com.pg85.otg.forge.dimensions.portals.OTGPortalBlocks;
import com.pg85.otg.forge.dimensions.portals.OTGCapabilities;
import com.pg85.otg.forge.dimensions.portals.OTGPortalPois;
import com.pg85.otg.forge.gen.OTGNoiseChunkGenerator;
import com.pg85.otg.forge.gui.OTGGui;
import com.pg85.otg.forge.network.OTGClientSyncManager;
import com.pg85.otg.presets.Preset;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.GameRules;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.FolderName;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ForgeWorldType;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.event.world.WorldEvent.Save;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(Constants.MOD_ID_SHORT) // Should match META-INF/mods.toml
@Mod.EventBusSubscriber(modid = Constants.MOD_ID_SHORT, bus = Mod.EventBusSubscriber.Bus.MOD) 
public class OTGPlugin
{
	public OTGPlugin()
	{
		IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

		// Register the clientSetup method for client-side initialisation logic (GUI etc).
		modEventBus.addListener(this::clientSetup);
		modEventBus.addListener(this::commonSetup);

		// Register self for server and other game events we are interested in
		MinecraftForge.EVENT_BUS.register(this);

		// Let MC know about our chunk generator and biome provider. 
		// If they're not added, we get errors and MC does not save properly.
		Registry.register(Registry.BIOME_SOURCE, new ResourceLocation(Constants.MOD_ID_SHORT, "default"), OTGBiomeProvider.CODEC);
		Registry.register(Registry.CHUNK_GENERATOR, new ResourceLocation(Constants.MOD_ID_SHORT, "default"), OTGNoiseChunkGenerator.CODEC);
		RegistryKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(Constants.MOD_ID_SHORT, "default"));

		// Deferred registers
		OTGPortalPois.poi.register(modEventBus);
		OTGPortalBlocks.blocks.register(modEventBus);
		
		OTGCommand.registerArguments();
	}

	// OTG World Type MP: Register the OTG world type. 
	// For MP we use server.properties level-type:otg + generatorSettings:presetFolderName
	@SubscribeEvent
	@OnlyIn(Dist.DEDICATED_SERVER)
	public static void registerWorldType(RegistryEvent.Register<ForgeWorldType> event)
	{
		ForgeRegistries.WORLD_TYPES.register(new OTGWorldType());
	}

	// Register player capabilities for dimension portal timer.
	public void commonSetup(FMLCommonSetupEvent event)
	{
		OTGCapabilities.register();
	}

	// OTG World Type SP: We use our own world type registration logic so we can add a "customise"
	// button to the world creation gui that shows OTG preset selection and customisation screens.
	private void clientSetup(final FMLClientSetupEvent event)
	{
		// Register the OTG world type and any OTG GUI's for the world creation screen.
		OTGGui.init();
	}

	@SubscribeEvent
	public static void registerBiomes(RegistryEvent.Register<Biome> event)
	{
		// Start OpenTerrainGenerator engine, loads all presets.
		// Done here so that file indexing happens after presetpacker has unpacked its preset
		OTG.startEngine(new ForgeEngine());

		// Register all biomes
		// TODO: Use proper Forge way of registering biomes, we're not using
		// deferredregister (wasn't working before) or event.getRegistry().register atm.
		OTG.getEngine().getPresetLoader().registerBiomes();

		// Fog & colors networking/handlers
		OTGClientSyncManager.setup();
		MultipleColorHandler.setup();
	}

	@SubscribeEvent
	public void onCommandRegister(RegisterCommandsEvent event)
	{
		OTGCommand.register(event.getDispatcher());
	}

	@SubscribeEvent
	public void onSave(Save event)
	{
		// Save OTG DimensionTypes to world save folder as datapack json files so they're picked up on world load.
		// Unfortunately there doesn't appear to be a way to persist them via code(?)
		if(!event.getWorld().isClientSide())
		{			
			if(((ServerWorld)event.getWorld()).getLevel().getChunkSource().generator instanceof OTGNoiseChunkGenerator)
			{
				Path datapackDir = ((ServerWorld)event.getWorld()).getLevel().getServer().getWorldPath(FolderName.DATAPACK_DIR);
				Preset preset = ((OTGNoiseChunkGenerator)((ServerWorld)event.getWorld()).getLevel().getChunkSource().generator).getPreset();
				String dimName = ((ServerWorld)event.getWorld()).getWorldServer().dimension().location().getPath();
				OTGDimensionType.saveDataPackFile(datapackDir, dimName, preset.getWorldConfig(), preset.getFolderName());
			}
		}
		((ForgeEngine)OTG.getEngine()).onSave(event.getWorld());
	}

	@SubscribeEvent
	public void onUnload(WorldEvent.Unload event)
	{
		((ForgeEngine)OTG.getEngine()).onUnload(event.getWorld());
	}

	@SubscribeEvent
	public void onSetSpawn(WorldEvent.CreateSpawnPosition event)
	{
		// If a modpack config is being used, apply the configured gamerules (if any).
		// TODO: Only needed for MP?
		if(event.getWorld() instanceof ServerWorld)
		{
			DimensionConfig modpackConfig = DimensionConfig.fromDisk("Modpack");
			if(modpackConfig != null && modpackConfig.GameRules != null)
			{
				GameRules gameRules = ((ServerWorld)event.getWorld()).getGameRules();
				//. TOOD: tryDeserialize does not call onUpdate, which may cause problems here.
				gameRules.getRule(GameRules.RULE_DOFIRETICK).set(modpackConfig.GameRules.doFireTick, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_MOBGRIEFING).set(modpackConfig.GameRules.mobGriefing, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_KEEPINVENTORY).set(modpackConfig.GameRules.keepInventory, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(modpackConfig.GameRules.doMobSpawning, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_DOMOBLOOT).set(modpackConfig.GameRules.doMobLoot, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_DOBLOCKDROPS).set(modpackConfig.GameRules.doTileDrops, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_DOENTITYDROPS).set(modpackConfig.GameRules.doEntityDrops, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_COMMANDBLOCKOUTPUT).set(modpackConfig.GameRules.commandBlockOutput, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_NATURAL_REGENERATION).set(modpackConfig.GameRules.naturalRegeneration, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_DAYLIGHT).set(modpackConfig.GameRules.doDaylightCycle, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_LOGADMINCOMMANDS).set(modpackConfig.GameRules.logAdminCommands, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(modpackConfig.GameRules.showDeathMessages, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_RANDOMTICKING).tryDeserialize(modpackConfig.GameRules.randomTickSpeed + "");
				gameRules.getRule(GameRules.RULE_SENDCOMMANDFEEDBACK).set(modpackConfig.GameRules.sendCommandFeedback, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_REDUCEDDEBUGINFO).set(modpackConfig.GameRules.reducedDebugInfo, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_SPECTATORSGENERATECHUNKS).set(modpackConfig.GameRules.spectatorsGenerateChunks, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_SPAWN_RADIUS).tryDeserialize(modpackConfig.GameRules.spawnRadius + "");
				gameRules.getRule(GameRules.RULE_DISABLE_ELYTRA_MOVEMENT_CHECK).set(modpackConfig.GameRules.disableElytraMovementCheck, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).tryDeserialize(modpackConfig.GameRules.maxEntityCramming + "");
				gameRules.getRule(GameRules.RULE_WEATHER_CYCLE).set(modpackConfig.GameRules.doWeatherCycle, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_LIMITED_CRAFTING).set(modpackConfig.GameRules.doLimitedCrafting, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_MAX_COMMAND_CHAIN_LENGTH).tryDeserialize(modpackConfig.GameRules.maxCommandChainLength + "");
				gameRules.getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS).set(modpackConfig.GameRules.announceAdvancements, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_DISABLE_RAIDS).set(modpackConfig.GameRules.disableRaids, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_DOINSOMNIA).set(modpackConfig.GameRules.doInsomnia, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(modpackConfig.GameRules.doImmediateRespawn, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_DROWNING_DAMAGE).set(modpackConfig.GameRules.drowningDamage, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_FALL_DAMAGE).set(modpackConfig.GameRules.fallDamage, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_FIRE_DAMAGE).set(modpackConfig.GameRules.fireDamage, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(modpackConfig.GameRules.doPatrolSpawning, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_DO_TRADER_SPAWNING).set(modpackConfig.GameRules.doTraderSpawning, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_FORGIVE_DEAD_PLAYERS).set(modpackConfig.GameRules.forgiveDeadPlayers, (MinecraftServer)null);
				gameRules.getRule(GameRules.RULE_UNIVERSAL_ANGER).set(modpackConfig.GameRules.universalAnger, (MinecraftServer)null);
			}
		}
	}	
}
