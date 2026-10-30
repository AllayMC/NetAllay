package org.allaymc.netallay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for NetAllay plugin.
 *
 * @author YiRanKuma
 */
@Getter
@Setter
public class NetAllayConfig {

    private static final Logger log = LoggerFactory.getLogger(NetAllayConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Shop configuration.
     */
    private ShopConfig shop = new ShopConfig();

    /**
     * Skin anti-cheat configuration.
     */
    private SkinAntiCheatConfig skinAntiCheat = new SkinAntiCheatConfig();

    /**
     * Shop-related configuration options.
     */
    @Getter
    @Setter
    public static class ShopConfig {
        /**
         * The game ID from NetEase developer platform (资源数字ID).
         * This is required for shop functionality.
         */
        private String gameId = "";

        /**
         * The game key for production server (正式服务器签名密钥).
         * Used for signing API requests to NetEase order server.
         */
        private String gameKey = "";

        /**
         * The game key for test server (测试服务器签名密钥).
         * Used when testServer is true.
         */
        private String testGameKey = "";

        /**
         * Whether this is a test server.
         * When true, uses test API endpoints and testGameKey.
         */
        private boolean testServer = false;

        /**
         * Whether to use custom shop mode.
         * <p>
         * When true, the default shop button is hidden.
         * When false, the official NetEase shop UI is shown.
         */
        private boolean useCustomShop = false;

        /**
         * Cache time for shop data (in seconds).
         */
        private int cacheTime = 1;

        /**
         * Custom order server URL (optional).
         * Leave empty to use default: gasproxy.mc.netease.com:60002
         */
        private String shopServerUrl = "";

        /**
         * Custom web server URL (optional).
         * Leave empty to use default: g79mclobt.nie.netease.com
         */
        private String webServerUrl = "";
    }

    /**
     * Skin anti-cheat (皮肤反作弊) configuration.
     * <p>
     * NetEase clients only render a skin after the server sends a {@code ConfirmSkinPacket} for it.
     * When this feature is enabled, every outgoing {@code ConfirmSkinPacket} is intercepted: the
     * skins that need censorship are held back, verified against the NetEase web server
     * ({@code /pe/web/skin-md5-valid-inner}) and only then forwarded to the client. Skins that fail
     * the check are never confirmed, so the client keeps showing the default Steve/Alex skin.
     */
    @Getter
    @Setter
    public static class SkinAntiCheatConfig {
        /**
         * Whether the skin anti-cheat is enabled. Enabled by default.
         */
        private boolean enabled = true;

        /**
         * The fixed sign value required by the skin check API.
         */
        private String sign = "62bd6025d29b17ff";

        /**
         * Whether to use the test environment endpoint.
         * <p>
         * The test endpoint always answers "valid", which is useful for wiring up the feature
         * without breaking skins. Set {@code failOpenOnError = false} and this flag to reproduce
         * illegal-skin behaviour locally.
         */
        private boolean testServer = false;

        /**
         * Custom skin check endpoint (optional).
         * <p>
         * Accepts either a full URL ({@code https://host/pe/web/skin-md5-valid-inner}) or a bare
         * host ({@code host}), in which case the default path is appended.
         */
        private String webServerUrl = "";

        /**
         * HTTP timeout in milliseconds for a single skin check request.
         */
        private int timeoutMillis = 5000;

        /**
         * Maximum number of skins sent in one HTTP request. Larger batches are split.
         */
        private int maxBatchSize = 32;

        /**
         * Whether to send {@code geo_list} (geometry MD5) with the request.
         * <p>
         * The field is required for clients 2.1 and newer. Disable it only for legacy servers.
         */
        private boolean checkGeometry = true;

        /**
         * Whether persona skins and ".NonsyncCustom" skins skip the check, as defined by the
         * official scheme. These skins are rendered locally by the client and must not be withheld.
         */
        private boolean skipPersonaSkin = true;

        /**
         * Whether skins without local metadata (persona flag / skin id unknown) are still checked.
         */
        private boolean checkUnknownSkin = true;

        /**
         * Whether skins are shown when the check cannot be completed (HTTP error, non-zero code,
         * malformed response). {@code false} matches the official scheme: no confirm is sent, so the
         * skin stays hidden.
         */
        private boolean failOpenOnError = false;

        /**
         * How many times a failed request is retried before the {@code failOpenOnError} policy is
         * applied. An explicit "invalid" answer from the web server is never retried.
         */
        private int onErrorRetries = 1;

        /**
         * Whether skins that do not belong to a connected player (NPCs and other fake players) skip
         * the check. Their UIDs are generated locally and their skins are usually not registered on
         * the NetEase side, so checking them would hide every NPC skin.
         */
        private boolean trustNonPlayerSkins = true;

        /**
         * NetEase UIDs that never get checked (for example bot/NPC accounts).
         */
        private List<String> whitelistUids = new ArrayList<>();

        /**
         * Whether rejected skins are logged.
         */
        private boolean logDeniedSkin = true;

        /**
         * Whether passed skins are logged too. Useful while wiring the feature up, noisy in a busy
         * server because every viewer/target pair produces one line.
         */
        private boolean logPassedSkin = false;
    }

    /**
     * Loads the configuration from a file, or creates a default one if it doesn't exist.
     *
     * @param dataFolder the plugin's data folder
     * @return the loaded configuration
     */
    public static NetAllayConfig load(Path dataFolder) {
        Path configFile = dataFolder.resolve("config.json");

        // Create data folder if it doesn't exist
        if (!Files.exists(dataFolder)) {
            try {
                Files.createDirectories(dataFolder);
            } catch (IOException e) {
                log.error("Failed to create data folder", e);
                return new NetAllayConfig();
            }
        }

        // If config doesn't exist, create default
        if (!Files.exists(configFile)) {
            NetAllayConfig defaultConfig = new NetAllayConfig();
            defaultConfig.save(dataFolder);
            return defaultConfig;
        }

        // Load existing config
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            NetAllayConfig config = GSON.fromJson(reader, NetAllayConfig.class);
            if (config == null) {
                config = new NetAllayConfig();
            }
            // Sections may be missing or explicitly null in configs written by older versions.
            if (config.getShop() == null) {
                config.setShop(new ShopConfig());
            }
            if (config.getSkinAntiCheat() == null) {
                config.setSkinAntiCheat(new SkinAntiCheatConfig());
            }
            return config;
        } catch (IOException e) {
            log.error("Failed to load config.json", e);
            return new NetAllayConfig();
        }
    }

    /**
     * Saves the configuration to a file.
     *
     * @param dataFolder the plugin's data folder
     */
    public void save(Path dataFolder) {
        Path configFile = dataFolder.resolve("config.json");

        try {
            // Create data folder if it doesn't exist
            if (!Files.exists(dataFolder)) {
                Files.createDirectories(dataFolder);
            }

            try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            log.error("Failed to save config.json", e);
        }
    }
}
