package org.allaymc.netallay;

import lombok.Getter;
import org.allaymc.api.eventbus.EventHandler;
import org.allaymc.server.eventbus.event.network.PacketReceiveEvent;
import org.allaymc.api.math.location.Location3dc;
import org.allaymc.api.player.Player;
import org.allaymc.api.plugin.Plugin;
import org.allaymc.api.server.Server;
import org.allaymc.api.world.Dimension;
import org.allaymc.api.world.World;
import org.allaymc.netallay.codec.PyRpcCodec;
import org.allaymc.netallay.config.NetAllayConfig;
import org.allaymc.netallay.handler.EventListenerRegistry;
import org.allaymc.netallay.shop.ShopManager;
import org.cloudburstmc.protocol.bedrock.packet.PyRpcPacket;

import java.io.IOException;
import java.util.*;

/**
 * NetAllay - NetEase PyRpc Communication API for AllayMC.
 * <p>
 * This plugin provides a clean API for communicating with NetEase Minecraft clients,
 * similar to NukkitMaster's API.
 * <p>
 * <b>Usage Example:</b>
 * <pre>{@code
 * // Get the NetAllay instance
 * NetAllay netAllay = NetAllay.getInstance();
 * // Or via plugin manager:
 * // NetAllay netAllay = (NetAllay) Server.getInstance().getPluginManager().getPlugin("NetAllay");
 *
 * // Listen for client events
 * netAllay.listenForEvent("MyMod", "MySystem", "MyEvent", (player, data) -> {
 *     // Handle the event
 * });
 *
 * // Send events to clients
 * netAllay.notifyToClient(player, "MyMod", "MySystem", "ResponseEvent", Map.of("result", "success"));
 * }</pre>
 *
 * @author YiRanKuma
 */
public class NetAllay extends Plugin {

    /**
     * The magic message ID required by NetEase's PyRpc protocol.
     */
    public static final long PYRPC_MSG_ID = 9753608L;

    /**
     * Special entity ID that represents the local player on the client side.
     * Use -2 when you want to refer to the receiving player's own entity.
     */
    public static final int LOCAL_PLAYER_ENTITY_ID = -2;

    @Getter
    private static NetAllay instance;

    private final EventListenerRegistry listenerRegistry = new EventListenerRegistry();

    /**
     * The shop manager for handling NetEase shop functionality.
     */
    @Getter
    private ShopManager shopManager;

    /**
     * The plugin configuration.
     */
    @Getter
    private NetAllayConfig config;

    @Override
    public void onLoad() {
        instance = this;
        pluginLogger.info("NetAllay loading...");
    }

    @Override
    public void onEnable() {
        Server.getInstance().getEventBus().registerListener(this);

        // Load configuration
        config = NetAllayConfig.load(pluginContainer.dataFolder());
        pluginLogger.info("Configuration loaded.");

        // Start HTTP client for order API
        org.allaymc.netallay.shop.WebUtil.startHttpClient();

        // Initialize shop manager
        shopManager = new ShopManager(this);

        // Apply config to shop manager
        applyConfig();

        pluginLogger.info("NetAllay enabled! API ready for use.");
        pluginLogger.info("Shop config: gameId={}, testServer={}, customShop={}",
                config.getShop().getGameId().isEmpty() ? "(not set)" : config.getShop().getGameId(),
                config.getShop().isTestServer(),
                config.getShop().isUseCustomShop());
    }

    @Override
    public void onDisable() {
        Server.getInstance().getEventBus().unregisterListener(this);
        listenerRegistry.clear();

        // Clean up shop manager
        if (shopManager != null) {
            shopManager.clearPlayerConfigs();
            shopManager = null;
        }

        // Stop HTTP client
        org.allaymc.netallay.shop.WebUtil.stopHttpClient();

        pluginLogger.info("NetAllay disabled.");
        instance = null;
    }

    // ==================== Event Listening API ====================

    /**
     * Registers a listener for client events.
     * <p>
     * When a NetEase client sends an event matching the specified namespace, system, and event name,
     * the handler will be called with the player and event data.
     *
     * @param namespace  the namespace of the client system (e.g., "MyMod")
     * @param systemName the system name of the client system (e.g., "MySystemCS")
     * @param eventName  the event name to listen for (e.g., "OnButtonClick")
     * @param handler    the callback function to handle the event
     */
    public void listenForEvent(String namespace, String systemName, String eventName, PyRpcHandler handler) {
        listenerRegistry.register(namespace, systemName, eventName, handler);
        pluginLogger.debug("Registered listener for {}:{}:{}", namespace, systemName, eventName);
    }

    /**
     * Registers a listener for client engine callbacks.
     * <p>
     * Engine callbacks use a different format: [eventName, [], null]
     * Common engine callbacks: StoreBuySuccServerEvent, UrgeShipEvent
     *
     * @param eventName the engine callback name
     * @param handler   the callback function to handle the event
     */
    public void listenForClientEngineCall(String eventName, PyRpcHandler handler) {
        listenerRegistry.register("engine", "callback", eventName, handler);
        pluginLogger.debug("Registered engine callback listener for: {}", eventName);
    }

    /**
     * Unregisters a specific listener for an event.
     *
     * @param namespace  the namespace
     * @param systemName the system name
     * @param eventName  the event name
     * @param handler    the handler to unregister
     * @return true if the handler was found and removed
     */
    public boolean unlistenForEvent(String namespace, String systemName, String eventName, PyRpcHandler handler) {
        return listenerRegistry.unregister(namespace, systemName, eventName, handler);
    }

    /**
     * Unregisters all listeners for an event.
     *
     * @param namespace  the namespace
     * @param systemName the system name
     * @param eventName  the event name
     */
    public void unlistenAllForEvent(String namespace, String systemName, String eventName) {
        listenerRegistry.unregisterAll(namespace, systemName, eventName);
    }

    // ==================== Send to Single Player ====================

    /**
     * Sends a server event to a specific player.
     * <p>
     * The client should use ListenForEvent with the same namespace, system, and event name to receive this event.
     * <p>
     * <b>Note:</b> Use {@link #LOCAL_PLAYER_ENTITY_ID} (-2) to refer to the receiving player's own entity ID.
     *
     * @param player    the player to receive the event
     * @param namespace the namespace that the client is listening on
     * @param systemName the system name that the client is listening on
     * @param eventName the event name
     * @param data      the event data. Note: use -2 to refer to the local player's entityId
     * @return true if the packet was sent successfully
     */
    public boolean notifyToClient(Player player, String namespace, String systemName, String eventName, Map<String, Object> data) {
        if (player == null || !player.isNetEasePlayer()) {
            return false;
        }

        try {
            byte[] packedData = PyRpcCodec.encode(namespace, systemName, eventName, data);

            PyRpcPacket packet = new PyRpcPacket();
            packet.setData(packedData);
            packet.setMsgId(PYRPC_MSG_ID);

            player.sendPacket(packet);
            return true;
        } catch (IOException e) {
            pluginLogger.error("Failed to send PyRpc packet to {}", player.getOriginName(), e);
            return false;
        }
    }

    /**
     * Sends a server event to a specific player immediately (without buffering).
     *
     * @param player    the player to receive the event
     * @param namespace the namespace that the client is listening on
     * @param systemName the system name that the client is listening on
     * @param eventName the event name
     * @param data      the event data
     * @return true if the packet was sent successfully
     */
    public boolean notifyToClientImmediately(Player player, String namespace, String systemName, String eventName, Map<String, Object> data) {
        if (player == null || !player.isNetEasePlayer()) {
            return false;
        }

        try {
            byte[] packedData = PyRpcCodec.encode(namespace, systemName, eventName, data);

            PyRpcPacket packet = new PyRpcPacket();
            packet.setData(packedData);
            packet.setMsgId(PYRPC_MSG_ID);

            player.sendPacketImmediately(packet);
            return true;
        } catch (IOException e) {
            pluginLogger.error("Failed to send PyRpc packet immediately to {}", player.getOriginName(), e);
            return false;
        }
    }

    // ==================== Send to Multiple Players ====================

    /**
     * Sends a server event to multiple players.
     * <p>
     * <b>Warning:</b> Do not use {@link #LOCAL_PLAYER_ENTITY_ID} (-2) in multi-player broadcasts,
     * as it refers to different entities for each player.
     *
     * @param players   the list of players to receive the event
     * @param namespace the namespace that the clients are listening on
     * @param systemName the system name that the clients are listening on
     * @param eventName the event name
     * @param data      the event data. Do NOT use -2 entityId in broadcasts
     * @return the number of players the event was successfully sent to
     */
    public int notifyToMultiClients(Collection<? extends Player> players, String namespace, String systemName, String eventName, Map<String, Object> data) {
        if (players == null || players.isEmpty()) {
            return 0;
        }

        byte[] packedData;
        try {
            packedData = PyRpcCodec.encode(namespace, systemName, eventName, data);
        } catch (IOException e) {
            pluginLogger.error("Failed to encode PyRpc packet", e);
            return 0;
        }

        int successCount = 0;
        for (Player player : players) {
            if (player != null && player.isNetEasePlayer()) {
                PyRpcPacket packet = new PyRpcPacket();
                packet.setData(packedData);
                packet.setMsgId(PYRPC_MSG_ID);
                player.sendPacket(packet);
                successCount++;
            }
        }
        return successCount;
    }

    // ==================== Send to Players Nearby ====================

    /**
     * Sends a server event to all players within a certain distance of a location.
     * <p>
     * <b>Warning:</b> Do not use {@link #LOCAL_PLAYER_ENTITY_ID} (-2) in broadcasts,
     * as it refers to different entities for each player.
     *
     * @param except    a player to exclude from receiving the event (can be null)
     * @param location  the center location
     * @param distance  the maximum distance from the location
     * @param namespace the namespace that the clients are listening on
     * @param systemName the system name that the clients are listening on
     * @param eventName the event name
     * @param data      the event data. Do NOT use -2 entityId in broadcasts
     * @return the number of players the event was successfully sent to
     */
    public int notifyToClientsNearby(Player except, Location3dc location, double distance, String namespace, String systemName, String eventName, Map<String, Object> data) {
        if (location == null || location.dimension() == null) {
            return 0;
        }

        Dimension dimension = location.dimension();
        Set<Player> players = dimension.getPlayers();

        if (players.isEmpty()) {
            return 0;
        }

        double distanceSquared = distance * distance;
        List<Player> nearbyPlayers = new ArrayList<>();

        for (Player player : players) {
            if (player == except) {
                continue;
            }
            if (!player.isNetEasePlayer()) {
                continue;
            }

            var controlledEntity = player.getControlledEntity();
            if (controlledEntity == null) {
                continue;
            }

            var playerLoc = controlledEntity.getLocation();
            double dx = playerLoc.x() - location.x();
            double dy = playerLoc.y() - location.y();
            double dz = playerLoc.z() - location.z();
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= distanceSquared) {
                nearbyPlayers.add(player);
            }
        }

        return notifyToMultiClients(nearbyPlayers, namespace, systemName, eventName, data);
    }

    // ==================== Broadcast to World/Dimension ====================

    /**
     * Broadcasts a server event to all players in a specific world (all dimensions).
     * <p>
     * <b>Warning:</b> Do not use {@link #LOCAL_PLAYER_ENTITY_ID} (-2) in broadcasts.
     *
     * @param except    a player to exclude from receiving the event (can be null)
     * @param world     the world to broadcast to
     * @param namespace the namespace that the clients are listening on
     * @param systemName the system name that the clients are listening on
     * @param eventName the event name
     * @param data      the event data. Do NOT use -2 entityId in broadcasts
     * @return the number of players the event was successfully sent to
     */
    public int broadcastToAllClient(Player except, World world, String namespace, String systemName, String eventName, Map<String, Object> data) {
        if (world == null) {
            return 0;
        }

        Collection<Player> players = world.getPlayers();
        if (players.isEmpty()) {
            return 0;
        }

        List<Player> targetPlayers = new ArrayList<>();
        for (Player player : players) {
            if (player != except && player.isNetEasePlayer()) {
                targetPlayers.add(player);
            }
        }

        return notifyToMultiClients(targetPlayers, namespace, systemName, eventName, data);
    }

    /**
     * Broadcasts a server event to all players in a specific dimension.
     * <p>
     * <b>Warning:</b> Do not use {@link #LOCAL_PLAYER_ENTITY_ID} (-2) in broadcasts.
     *
     * @param except    a player to exclude from receiving the event (can be null)
     * @param dimension the dimension to broadcast to
     * @param namespace the namespace that the clients are listening on
     * @param systemName the system name that the clients are listening on
     * @param eventName the event name
     * @param data      the event data. Do NOT use -2 entityId in broadcasts
     * @return the number of players the event was successfully sent to
     */
    public int broadcastToAllClient(Player except, Dimension dimension, String namespace, String systemName, String eventName, Map<String, Object> data) {
        if (dimension == null) {
            return 0;
        }

        Set<Player> players = dimension.getPlayers();
        if (players.isEmpty()) {
            return 0;
        }

        List<Player> targetPlayers = new ArrayList<>();
        for (Player player : players) {
            if (player != except && player.isNetEasePlayer()) {
                targetPlayers.add(player);
            }
        }

        return notifyToMultiClients(targetPlayers, namespace, systemName, eventName, data);
    }

    /**
     * Broadcasts a server event to all NetEase players on the server.
     * <p>
     * <b>Warning:</b> Do not use {@link #LOCAL_PLAYER_ENTITY_ID} (-2) in broadcasts.
     *
     * @param except    a player to exclude from receiving the event (can be null)
     * @param namespace the namespace that the clients are listening on
     * @param systemName the system name that the clients are listening on
     * @param eventName the event name
     * @param data      the event data. Do NOT use -2 entityId in broadcasts
     * @return the number of players the event was successfully sent to
     */
    public int broadcastToAllClient(Player except, String namespace, String systemName, String eventName, Map<String, Object> data) {
        Collection<Player> allPlayers = Server.getInstance().getPlayerManager().getPlayers().values();

        if (allPlayers.isEmpty()) {
            return 0;
        }

        List<Player> targetPlayers = new ArrayList<>();
        for (Player player : allPlayers) {
            if (player != except && player.isNetEasePlayer()) {
                targetPlayers.add(player);
            }
        }

        return notifyToMultiClients(targetPlayers, namespace, systemName, eventName, data);
    }

    // ==================== Utility Methods ====================

    /**
     * Checks if a player is a NetEase client.
     *
     * @param player the player to check
     * @return true if the player is using a NetEase client
     */
    public boolean isNetEasePlayer(Player player) {
        return player != null && player.isNetEasePlayer();
    }

    /**
     * Gets the number of registered event listeners.
     *
     * @return the count of unique event types being listened to
     */
    public int getRegisteredEventCount() {
        return listenerRegistry.getRegisteredEventCount();
    }

    // ==================== Configuration Methods ====================

    /**
     * Applies the current configuration to the shop manager.
     */
    private void applyConfig() {
        if (shopManager != null && config != null) {
            NetAllayConfig.ShopConfig shopConfig = config.getShop();
            shopManager.setGameId(shopConfig.getGameId());
            shopManager.setGameKey(shopConfig.getGameKey());
            shopManager.setTestGameKey(shopConfig.getTestGameKey());
            shopManager.setTestServer(shopConfig.isTestServer());
            shopManager.setUseCustomShop(shopConfig.isUseCustomShop());
            shopManager.setCacheTime(shopConfig.getCacheTime());
            shopManager.setShopServerUrl(shopConfig.getShopServerUrl());
            shopManager.setWebServerUrl(shopConfig.getWebServerUrl());

            // Validate required fields
            if (shopConfig.getGameId().isEmpty()) {
                pluginLogger.warn("shop.gameId is not configured!");
            }
            if (!shopConfig.isTestServer() && shopConfig.getGameKey().isEmpty()) {
                pluginLogger.warn("shop.gameKey is not configured for production server!");
            }
            if (shopConfig.isTestServer() && shopConfig.getTestGameKey().isEmpty()) {
                pluginLogger.warn("shop.testGameKey is not configured for test server!");
            }
        }
    }

    /**
     * Reloads the configuration from disk and applies it.
     */
    public void reloadConfig() {
        config = NetAllayConfig.load(pluginContainer.dataFolder());
        applyConfig();
        pluginLogger.info("Configuration reloaded. Shop button visible: {}", !config.getShop().isUseCustomShop());
    }

    /**
     * Saves the current configuration to disk.
     */
    public void saveConfig() {
        if (config != null) {
            config.save(pluginContainer.dataFolder());
            pluginLogger.info("Configuration saved.");
        }
    }

    // ==================== Shop Convenience Methods ====================

    /**
     * Sets whether to use custom shop mode.
     * <p>
     * When true, the default shop button is hidden and you control the shop UI yourself.
     * When false, the official NetEase shop UI is shown.
     * <p>
     * This is a shortcut for {@code getShopManager().setUseCustomShop(useCustomShop)}.
     *
     * @param useCustomShop true to enable custom shop mode
     */
    public void enableCustomShopEntry(boolean useCustomShop) {
        if (shopManager != null) {
            shopManager.setUseCustomShop(useCustomShop);
        }
    }

    /**
     * Opens the shop UI for a player.
     * <p>
     * This is a shortcut for {@code getShopManager().openShop(player)}.
     *
     * @param player the player
     * @return true if the packet was sent successfully
     */
    public boolean openShop(Player player) {
        return shopManager != null && shopManager.openShop(player);
    }

    /**
     * Closes the shop UI for a player.
     * <p>
     * This is a shortcut for {@code getShopManager().closeShop(player)}.
     *
     * @param player the player
     * @return true if the packet was sent successfully
     */
    public boolean closeShop(Player player) {
        return shopManager != null && shopManager.closeShop(player);
    }

    /**
     * Shows a hint message to a player.
     * <p>
     * This is a shortcut for {@code getShopManager().showHint(player, text)}.
     *
     * @param player the player
     * @param text   the hint text
     * @return true if the packet was sent successfully
     */
    public boolean showHint(Player player, String text) {
        return shopManager != null && shopManager.showHint(player, text);
    }

    /**
     * Shows a two-line hint message to a player.
     * <p>
     * This is a shortcut for {@code getShopManager().showHint(player, head, tail)}.
     *
     * @param player the player
     * @param head   the first line
     * @param tail   the second line
     * @return true if the packet was sent successfully
     */
    public boolean showHint(Player player, String head, String tail) {
        return shopManager != null && shopManager.showHint(player, head, tail);
    }

    // ==================== Internal Event Handler ====================

    @EventHandler
    private void onPacketReceive(PacketReceiveEvent event) {
        Object packet = event.getPacket();

        if (!(packet instanceof PyRpcPacket pyRpcPacket)) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null || !player.isNetEasePlayer()) {
            return;
        }

        byte[] data = pyRpcPacket.getData();
        PyRpcCodec.ParsedEvent parsedEvent = PyRpcCodec.decode(data);

        if (parsedEvent == null) {
            pluginLogger.debug("Failed to decode PyRpc packet from {}", player.getOriginName());
            return;
        }

        pluginLogger.debug("Received PyRpc event from {}: {}:{}:{}",
                player.getOriginName(),
                parsedEvent.namespace(),
                parsedEvent.systemName(),
                parsedEvent.eventName());

        // Dispatch to registered handlers
        List<PyRpcHandler> handlers = listenerRegistry.getHandlers(
                parsedEvent.namespace(),
                parsedEvent.systemName(),
                parsedEvent.eventName()
        );

        for (PyRpcHandler handler : handlers) {
            try {
                handler.handle(player, parsedEvent.eventData());
            } catch (Exception e) {
                pluginLogger.error("Error in PyRpc handler for {}:{}:{}",
                        parsedEvent.namespace(),
                        parsedEvent.systemName(),
                        parsedEvent.eventName(),
                        e);
            }
        }
    }
}
