package com.alopetsiyacraft.inventory;

import com.alopetsiyacraft.auth.AuthHandler;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Мост инвентарей: сохраняет инвентарь игрока на сайте в POST
 * /api/inventory/from-server, чтобы его можно было посмотреть удалённо
 * (страница /inventory) и откатываться к точке истории (админ-панель сайта).
 *
 * Снимки шлются: при входе (после авторизации), при выходе, при остановке
 * сервера и периодически раз в 10 минут онлайн (для истории). Каждый снимок
 * сопровождается причиной (join/leave/stop/periodic) — сайт пишет её в
 * историю. Плюс каждые 60 секунд игроки сохраняются на диск (как раньше).
 *
 * Отложенный откат: админ выбирает точку истории на сайте, там появляется
 * pending-restore. При следующем входе игрока мод спрашивает у сайта
 * GET /api/inventory/pending-restore, ждёт, пока игрок авторизуется
 * (AuthHandler.isAuthorized — пока игрок в pending, память очищена и его
 * спас живёт в бэкапе AlopetsiyaAuth), применяет снимок к живым контейнерам
 * и сразу пишет игрока на диск. После применения — POST .../apply, чтобы
 * сайт убрал pending.
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

    /** Как часто сохранять игроков на диск, в тиках (20 тиков = 1 сек). */
    private static final int SAVE_INTERVAL_TICKS = 20 * 60;
    /** Как часто слать снимки на сайт (история админу), в тиках: 10 минут. */
    private static final int SITE_SEND_INTERVAL_TICKS = 20 * 60 * 10;
    /** Как часто проверять готовность к откату и «joин»-снимку, в тиках: 2 сек. */
    private static final int POST_AUTH_CHECK_TICKS = 20 * 2;
    /** Сколько раз ждать авторизацию (2 сек × 300 = 10 минут), дальше — бросаем. */
    private static final int POST_AUTH_MAX_ATTEMPTS = 300;
    private int tickCounter = 0;

    /** Игроки, для которых найден pending-restore: uuid -> JSON снимка. */
    private final Map<UUID, String> pendingRestores = new ConcurrentHashMap<>();
    /** Количество попыток дождаться авторизации для отката/joин-снимка. */
    private final Map<UUID, Integer> postAuthAttempts = new ConcurrentHashMap<>();
    /** Игроки, которым ещё не отправлен «joин»-снимок (ждём авторизации). */
    private final Set<UUID> joinSnapshotsPending = ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) return;
        tickCounter++;

        if (tickCounter % SAVE_INTERVAL_TICKS == 0 && !server.getPlayerList().getPlayers().isEmpty()) {
            try {
                // Жёсткое выключение сервера (kill процесса/панели) не вызывает
                // "Saving players" — без этого игроки откатываются к последнему
                // сейву (прошлому запуску). Периодический сейв ограничивает потерю
                // примерно одной минутой игры.
                server.getPlayerList().saveAll();
            } catch (Exception e) {
                LOGGER.warn("Periodic player save failed: {}", e.getMessage());
            }
        }

        if (tickCounter % SITE_SEND_INTERVAL_TICKS == 0) {
            // Периодические снимки онлайн — история для админ-отката и свежий
            // инвентарь на сайте, пока игрок в игре (не чаще 10 минут).
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                sendPlayer(player, "periodic");
            }
        }

        if (tickCounter % POST_AUTH_CHECK_TICKS == 0 && (!pendingRestores.isEmpty() || !joinSnapshotsPending.isEmpty())) {
            checkPostAuth(server);
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // «Joин»-снимок пошлём после авторизации (иначе в памяти пустой
        // инвентарь — AlopetsiyaAuth держит игрока в pending до входа).
        joinSnapshotsPending.add(player.getUUID());
        considerPendingRestore(player);
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendPlayer(player, "leave");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        if (server == null) return;
        // Если сервер выключается — выгружаем тех, кто ещё онлайн.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendPlayer(player, "stop");
    }

    // ---------- ожидание авторизации (откат и joин-снимок) ----------

    private void checkPostAuth(MinecraftServer server) {
        for (UUID uuid : Set.copyOf(pendingRestores.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                // Игрок вышел до применения — pending останется на сайте,
                // мод проверит его при следующем входе.
                pendingRestores.remove(uuid);
                postAuthAttempts.remove(uuid);
                continue;
            }
            if (!AuthHandler.isAuthorized(uuid)) {
                if (bumpAttempt(uuid) > POST_AUTH_MAX_ATTEMPTS) {
                    LOGGER.info("[restore] give up waiting for auth of {}", player.getName().getString());
                    pendingRestores.remove(uuid);
                    postAuthAttempts.remove(uuid);
                }
                continue;
            }
            String snapshotJson = pendingRestores.remove(uuid);
            postAuthAttempts.remove(uuid);
            if (snapshotJson != null) {
                try {
                    applyRestore(player, snapshotJson);
                    confirmRestoreApplied(player.getName().getString());
                } catch (Exception e) {
                    LOGGER.warn("[restore] failed to apply for {}: {}", player.getName().getString(), e.getMessage());
                }
            }
        }

        for (UUID uuid : Set.copyOf(joinSnapshotsPending)) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                joinSnapshotsPending.remove(uuid);
                postAuthAttempts.remove(uuid);
                continue;
            }
            if (!AuthHandler.isAuthorized(uuid)) {
                if (bumpAttempt(uuid) > POST_AUTH_MAX_ATTEMPTS) {
                    joinSnapshotsPending.remove(uuid);
                    postAuthAttempts.remove(uuid);
                }
                continue;
            }
            joinSnapshotsPending.remove(uuid);
            postAuthAttempts.remove(uuid);
            // Снимок «вход» шлём после отката, чтобы в историю попал итоговый вид.
            sendPlayer(player, "join");
        }
    }

    private int bumpAttempt(UUID uuid) {
        return postAuthAttempts.merge(uuid, 1, Integer::sum);
    }

    /** Спрашивает у сайта pending-restore для игрока; если есть — ставит в очередь. */
    private void considerPendingRestore(ServerPlayer player) {
        String nickname = player.getName().getString();
        Thread.ofVirtual().start(() -> {
            try {
                String query = "nickname=" + URLEncoder.encode(nickname, StandardCharsets.UTF_8);
                JsonObject resp = httpGetJson("/api/inventory/pending-restore", query);
                if (resp == null || !resp.has("pending") || !resp.get("pending").getAsBoolean()) return;
                JsonElement data = resp.get("data");
                if (data == null || data.isJsonNull() || !data.isJsonObject()) return;
                pendingRestores.put(player.getUUID(), GSON.toJson(data));
                LOGGER.info("[restore] pending restore found for {}", nickname);
            } catch (Exception e) {
                LOGGER.warn("[restore] pending-restore check failed for {}: {}", nickname, e.getMessage());
            }
        });
    }

    // ---------- применение отката ----------

    private void applyRestore(ServerPlayer player, String snapshotJson) {
        JsonObject snap = GSON.fromJson(snapshotJson, JsonObject.class);
        if (snap == null || !snap.has("containers")) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        String nickname = player.getName().getString();

        JsonObject containers = snap.getAsJsonObject("containers");
        fillContainer(player.getInventory().items, MAIN_SIZE, containers.getAsJsonArray("main"), server);
        fillContainer(player.getInventory().armor, ARMOR_SIZE, containers.getAsJsonArray("armor"), server);
        fillContainer(player.getInventory().offhand, OFFHAND_SIZE, containers.getAsJsonArray("offhand"), server);
        fillContainer(player.getEnderChestInventory().getItems(), ENDER_SIZE, containers.getAsJsonArray("enderChest"), server);

        if (snap.has("xpLevel")) player.experienceLevel = snap.get("xpLevel").getAsInt();
        if (snap.has("health")) player.setHealth(snap.get("health").getAsFloat());
        if (snap.has("food")) player.getFoodData().setFoodLevel(snap.get("food").getAsInt());
        if (snap.has("saturation")) player.getFoodData().setSaturation(snap.get("saturation").getAsFloat());

        // Сразу на диск — чтобы откат не пропал при жёстком выключении
        // (save(ServerPlayer) здесь protected, поэтому сохраняем всех).
        server.getPlayerList().saveAll();
        LOGGER.info("[restore] applied inventory restore to {}", nickname);
    }

    private void fillContainer(List<ItemStack> items, int length, JsonArray arr, MinecraftServer server) {
        if (arr == null) return;
        for (int i = 0; i < length && i < arr.size(); i++) {
            if (i < items.size()) items.set(i, parseStack(arr.get(i), server));
        }
    }

    /** Восстанавливает предмет из снимка сайта (null → пустой слот). */
    private ItemStack parseStack(JsonElement el, MinecraftServer server) {
        if (el == null || el.isJsonNull()) return ItemStack.EMPTY;
        JsonObject o = el.getAsJsonObject();
        ResourceLocation key = o.has("id") ? ResourceLocation.tryParse(o.get("id").getAsString()) : null;
        Item item = key != null ? BuiltInRegistries.ITEM.get(key) : Items.AIR;
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;

        ItemStack stack = new ItemStack(item, 1);
        int count = o.has("count") ? Math.max(1, Math.min(o.get("count").getAsInt(), stack.getMaxStackSize())) : 1;
        stack.setCount(count);

        if (o.has("damage") && stack.isDamageableItem()) {
            stack.setDamageValue(Math.max(0, o.get("damage").getAsInt()));
        }

        if (o.has("name")) {
            String name = o.get("name").getAsString();
            if (name != null && !name.isBlank() && !name.equals(stack.getHoverName().getString())) {
                stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            }
        }

        if (o.has("enchantments") && o.get("enchantments").isJsonArray()) {
            var registry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            for (JsonElement e : o.getAsJsonArray("enchantments")) {
                JsonObject ench = e.getAsJsonObject();
                ResourceLocation er = ench.has("id") ? ResourceLocation.tryParse(ench.get("id").getAsString()) : null;
                if (er == null) continue;
                int lvl = ench.has("lvl") ? Math.max(1, Math.min(255, ench.get("lvl").getAsInt())) : 1;
                registry.get(ResourceKey.create(Registries.ENCHANTMENT, er))
                        .ifPresent(holder -> stack.enchant(holder, lvl));
            }
        }

        if (o.has("lore") && o.get("lore").isJsonArray()) {
            List<Component> lore = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("lore")) {
                String s = e.getAsString();
                if (s != null && !s.isBlank()) lore.add(Component.literal(s));
            }
            if (!lore.isEmpty()) stack.set(DataComponents.LORE, new ItemLore(lore));
        }

        return stack;
    }

    private void confirmRestoreApplied(String nickname) {
        Thread.ofVirtual().start(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("nickname", nickname);
                int code = postJson("/api/inventory/pending-restore/apply", body);
                if (code != 200) {
                    LOGGER.warn("[restore] confirm apply returned {} for {}", code, nickname);
                }
            } catch (Exception e) {
                LOGGER.warn("[restore] confirm apply failed for {}: {}", nickname, e.getMessage());
            }
        });
    }

    // ---------- отправка снимков ----------

    private void sendPlayer(ServerPlayer player, String reason) {
        if (player == null || player.getServer() == null) return;
        try {
            JsonObject payload = buildPayload(player, reason);
            Thread.ofVirtual().start(() -> sendToWebsite(payload));
        } catch (Exception e) {
            LOGGER.warn("Failed to gather inventory for {}: {}", player.getName().getString(), e.getMessage());
        }
    }

    private JsonObject buildPayload(ServerPlayer player, String reason) {
        JsonObject root = new JsonObject();
        root.addProperty("nickname", player.getName().getString());
        root.addProperty("reason", reason);

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

        // Подпись предмета (кастомный лор, компонентные данные 1.21).
        ItemLore itemLore = stack.get(DataComponents.LORE);
        if (itemLore != null && !itemLore.lines().isEmpty()) {
            JsonArray loreArr = new JsonArray();
            for (Component line : itemLore.lines()) loreArr.add(line.getString());
            o.add("lore", loreArr);
        }

        return o;
    }

    // ---------- HTTP ----------

    private void sendToWebsite(JsonObject payload) {
        int code = postJson("/api/inventory/from-server", payload);
        if (code != 200) {
            LOGGER.warn("Inventory send to website returned {} for {}", code, payload.get("nickname").getAsString());
        }
    }

    private int postJson(String path, JsonObject payload) {
        HttpURLConnection conn = null;
        try {
            String url = Config.INSTANCE.websiteUrl.get() + path;
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
            return conn.getResponseCode();
        } catch (Exception e) {
            LOGGER.warn("POST {} failed: {}", path, e.getMessage());
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JsonObject httpGetJson(String path, String query) {
        HttpURLConnection conn = null;
        try {
            String url = Config.INSTANCE.websiteUrl.get() + path + "?" + query;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("x-api-key", Config.INSTANCE.chatApiKey.get());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code != 200) {
                LOGGER.warn("GET {} returned {}", path, code);
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return GSON.fromJson(text, JsonObject.class);
            }
        } catch (Exception e) {
            LOGGER.warn("GET {} failed: {}", path, e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}