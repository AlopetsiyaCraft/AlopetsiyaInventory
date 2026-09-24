package com.alopetsiyacraft.inventory;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

@Mod(AlopetsiyaInventoryMod.MODID)
public class AlopetsiyaInventoryMod {
    public static final String MODID = "alopetsiyainventory";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AlopetsiyaInventoryMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(new InventoryHandler());
        LOGGER.info("AlopetsiyaInventory mod loaded");
    }
}