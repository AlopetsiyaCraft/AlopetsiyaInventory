package com.alopetsiyacraft.inventory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Мост инвентарей: сохраняет инвентарь игрока на сайте в POST
 * /api/inventory/from-server, чтобы его можно было посмотреть удалённо
 * (страница /inventory). Снимок делается при выходе игрока (PlayerLoggedOutEvent)
 * и при остановке сервера (ServerStoppingEvent) — пока игрок в игре, сайт не
 * дёргается, как договаривались. Дополнительно шлём снимок при входе, чтобы
 * данные появились даже если игрок никогда не «нормально» вышел.
 *
 * Передаётся полный набор контейнеров: main (36 слотов), броня (4), оффхенд (1)
 * и эндер-сундук (27). Каждый контейнер — массив фиксированной длины, где
 * пустой слот — null, а предмет — { id, count, name, [damage, maxDamage],
 * [enchantments], [lore] }.
 */
public class InventoryHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** Длина контейнеров: как в игре. */
    private static final int MAIN_SIZE = 36;
    private static final int ARMOR_SIZE = 4;
    private static final int OFFHAND_SIZE = 1;
    private static final int ENDER_SIZE = 27;

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendPlayer(player);
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendPlayer(player);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        if (server == null) return;
        // Если сервер выключается — выгружаем тех, кто ещё онлайн.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendPlayer(player);
    }

    private void sendPlayer(ServerPlayer player) {
        if (player == null || player.getServer() == null) return;
        try {
            JsonObject payload = buildPayload(player);
            Thread.ofVirtual().start(() -> sendToWebsite(payload));
        } catch (Exception e) {
            LOGGER.warn("Failed to gather inventory for {}: {}", player.getName().getString(), e.getMessage());
        }
    }

    private JsonObject buildPayload(ServerPlayer player) {
        JsonObject root = new JsonObject();
        root.addProperty("nickname", player.getName().getString());

        JsonObject containers = new JsonObject();
        containers.add("main", container(player.getInventory().items, MAIN_SIZE));
        containers.add("armor", container(player.getInventory().armor, ARMOR_SIZE));
        containers.add("offhand", container(player.getInventory().offhand, OFFHAND_SIZE));
        containers.add("enderChest", container(player.getEnderChestInventory().getItems(), ENDER_SIZE));
        root.add("containers", containers);

        root.addProperty("xpLevel", player.experienceLevel);
        root.addProperty("health", (int) Math.ceil(player.getHealth()));
        root.addProperty("healthMax", (int) Math.ceil(player.getMaxHealth()));
        root.addProperty("food", player.getFoodData().getFoodLevel());
        root.addProperty("saturation", player.getFoodData().getSaturationLevel());
        root.addProperty("updatedAt", System.currentTimeMillis());
        return root;
    }

    /** Контейнер фиксированной длины: на i-м месте предмет слота i или null. */
    private JsonArray container(List<ItemStack> items, int length) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < length; i++) {
            ItemStack stack = i < items.size() ? items.get(i) : ItemStack.EMPTY;
            JsonObject o = serializeStack(stack);
            arr.add(o != null ? o : JsonNull.INSTANCE);
        }
        return arr;
    }

    /** null для пустого слота; иначе — сериализованный предмет. */
    private JsonObject serializeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        JsonObject o = new JsonObject();
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        o.addProperty("id", key != null ? key.toString() : "minecraft:air");
        o.addProperty("count", stack.getCount());
        // Отображаемое имя (кастомное имя предмета, если задано).
        o.addProperty("name", stack.getHoverName().getString());

        // Прочность для инструментов/брони.
        if (stack.isDamageableItem()) {
            o.addProperty("damage", stack.getDamageValue());
            o.addProperty("maxDamage", stack.getMaxDamage());
        }

        // Заклинания (1.21: data-driven enchantments).
        JsonArray ench = new JsonArray();
        for (var entry : stack.getEnchantments().entrySet()) {
            String id = entry.getKey().getRegisteredName();
            if (id == null || id.isEmpty()) continue;
            JsonObject e = new JsonObject();
            e.addProperty("id", id);
            e.addProperty("lvl", entry.getIntValue());
            ench.add(e);
        }
        if (ench.size() > 0) o.add("enchantments", ench);

        return o;
    }

    private void sendToWebsite(JsonObject payload) {
        HttpURLConnection conn = null;
        try {
            String url = Config.INSTANCE.websiteUrl.get() + "/api/inventory/from-server";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", Config.INSTANCE.chatApiKey.get());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            byte[] jsonBytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                LOGGER.warn("Inventory send to website returned {} for {}", code, payload.get("nickname").getAsString());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to send inventory to website: {}", e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}