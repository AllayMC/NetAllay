package org.allaymc.netallay.skin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.allaymc.api.eventbus.EventHandler;
import org.allaymc.api.player.LoginData;
import org.allaymc.api.player.Player;
import org.allaymc.api.player.Skin;
import org.allaymc.api.server.Server;
import org.allaymc.netallay.NetAllay;
import org.allaymc.netallay.config.NetAllayConfig;
import org.allaymc.server.eventbus.event.network.PacketSendEvent;
import org.cloudburstmc.protocol.bedrock.data.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.packet.ConfirmSkinPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerSkinPacket;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NetEase skin anti-cheat (皮肤反作弊) implementation.
 * <p>
 * The NetEase client never renders a player list / player skin entry until the server sends a
 * matching {@link ConfirmSkinPacket}; without a confirm the client keeps showing Steve/Alex. Allay
 * sends that confirm unconditionally, so this manager intercepts the outgoing packet, holds back
 * every skin that needs censorship and validates it against the NetEase web server before
 * forwarding it to that specific viewer.
 * <p>
 * The flow follows the official scheme:
 * <ol>
 *     <li>the server broadcasts the skin through {@code PlayerListPacket} / {@code PlayerSkinPacket}
 *     (handled by Allay, only recorded here for persona detection);</li>
 *     <li>{@code ConfirmSkinPacket} is cancelled and its entries are validated;</li>
 *     <li>entries reported valid are re-sent as a new {@code ConfirmSkinPacket}, invalid ones are
 *     dropped — the client then never displays them.</li>
 * </ol>
 * Validation results are content addressed ({@code uid + skinMd5 + geoMd5}) and are never cached
 * after completion, because the web server also checks whether the player actually owns the skin
 * component.
 *
 * @author YiRanKuma
 */
public class SkinAntiCheatManager {

    /**
     * Production endpoint of the skin check API.
     */
    public static final String PROD_URL = "https://g79mclobt.nie.netease.com/pe/web/skin-md5-valid-inner";

    /**
     * Test endpoint of the skin check API. It always answers "valid".
     */
    public static final String TEST_URL = "https://g79mclexpr1.nie.netease.com/pe/web/skin-md5-valid-inner";

    private static final String CHECK_PATH = "/pe/web/skin-md5-valid-inner";
    private static final String NONSYNC_CUSTOM = ".NonsyncCustom";
    private static final String NONSYNC_CUSTOM_SLIM = ".NonsyncCustomSlim";
    private static final int MAX_METADATA_ENTRIES = 4096;
    private static final long METADATA_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private static final Gson GSON = new GsonBuilder().create();

    private final NetAllay plugin;

    /**
     * Logs through the plugin logger so messages follow the usual {@code [NetAllay]} prefix.
     */
    private final Logger log;

    /**
     * Skin metadata (persona flag and skin id) collected from the packets Allay sends before the
     * confirm packet. Keyed by player UUID.
     */
    private final Map<UUID, SkinMetadata> skinMetadata = new ConcurrentHashMap<>();

    /**
     * In-flight validation results, keyed by {@code uid:skinMd5:geoMd5}. Entries are removed as soon
     * as the request completes, so a passed skin is never trusted twice.
     */
    private final Map<String, CompletableFuture<Boolean>> inFlight = new ConcurrentHashMap<>();

    /**
     * Guards against re-interception of the confirm packets this manager itself sends.
     */
    private final ThreadLocal<Boolean> forwarding = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "NetAllay-SkinAntiCheat");
        thread.setDaemon(true);
        return thread;
    });

    private volatile NetAllayConfig.SkinAntiCheatConfig config;
    private volatile HttpClient httpClient;
    private volatile boolean shuttingDown;

    public SkinAntiCheatManager(NetAllay plugin) {
        this.plugin = plugin;
        this.log = plugin.getPluginLogger();
    }

    // ==================== Lifecycle ====================

    /**
     * Applies the (possibly reloaded) configuration.
     *
     * @param config the new configuration
     */
    public void applyConfig(NetAllayConfig.SkinAntiCheatConfig config) {
        this.config = config;
        if (config != null && config.isEnabled()) {
            httpClient();
        }
    }

    /**
     * Whether the anti-cheat is currently active.
     */
    public boolean isEnabled() {
        var cfg = this.config;
        return cfg != null && cfg.isEnabled();
    }

    /**
     * Releases HTTP and thread resources. Called when the plugin is disabled.
     */
    public void shutdown() {
        shuttingDown = true;
        retryExecutor.shutdownNow();
        inFlight.clear();
        skinMetadata.clear();
        httpClient = null;
    }

    // ==================== Packet hook ====================

    /**
     * Intercepts every packet Allay sends to a NetEase client.
     *
     * @param event the packet send event
     */
    @EventHandler
    public void onPacketSend(PacketSendEvent event) {
        if (Boolean.TRUE.equals(forwarding.get())) {
            return;
        }
        if (!isEnabled() || shuttingDown) {
            return;
        }

        try {
            handlePacketSend(event);
        } catch (Exception e) {
            // Never let a hook failure break Allay's own packet sending.
            log.error("Failed to process outgoing packet in skin anti-cheat", e);
        }
    }

    private void handlePacketSend(PacketSendEvent event) {
        var packet = event.getPacket();
        if (packet instanceof PlayerListPacket playerListPacket) {
            rememberFromPlayerList(playerListPacket);
            return;
        }
        if (packet instanceof PlayerSkinPacket playerSkinPacket) {
            rememberSkin(playerSkinPacket.getUuid(), playerSkinPacket.getSkin());
            return;
        }
        if (!(packet instanceof ConfirmSkinPacket confirmSkinPacket)) {
            return;
        }

        var viewer = event.getPlayer();
        if (viewer == null || !viewer.isNetEasePlayer()) {
            return;
        }

        var passThrough = new ArrayList<ConfirmSkinPacket.SkinEntry>();
        var toCheck = new ArrayList<ConfirmSkinPacket.SkinEntry>();
        for (var entry : confirmSkinPacket.getEntries()) {
            if (needsCheck(entry)) {
                toCheck.add(entry);
            } else {
                passThrough.add(entry);
            }
        }

        if (toCheck.isEmpty()) {
            // Nothing needs censorship, let Allay's own packet through untouched.
            return;
        }

        event.cancel();
        if (!passThrough.isEmpty()) {
            sendConfirm(viewer, passThrough);
        }
        validate(viewer, toCheck);
    }

    private boolean needsCheck(ConfirmSkinPacket.SkinEntry entry) {
        var cfg = this.config;
        if (entry == null || entry.getUuid() == null) {
            return false;
        }
        if (!entry.isValid()) {
            // Allay marked the entry as invalid already, nothing to confirm.
            return false;
        }
        if (entry.getSkinBytes() == null || entry.getSkinBytes().length == 0) {
            return false;
        }

        var uidStr = uidOf(entry);
        if (isWhitelisted(uidStr)) {
            return false;
        }

        if (cfg.isTrustNonPlayerSkins() && !isConnectedPlayer(entry.getUuid())) {
            // NPCs / fake players: their UID is generated locally and their skin is normally not
            // registered on the NetEase side, so checking them would hide every NPC skin.
            return false;
        }

        var metadata = metadataFor(entry.getUuid(), uidStr);
        if (metadata != null) {
            if (cfg.isSkipPersonaSkin() && (metadata.persona() || isNonsyncSkin(metadata.skinId()))) {
                return false;
            }
        } else if (!cfg.isCheckUnknownSkin()) {
            return false;
        }

        return true;
    }

    // ==================== Skin metadata ====================

    private void rememberFromPlayerList(PlayerListPacket packet) {
        if (packet.getEntries() == null) {
            return;
        }
        for (var entry : packet.getEntries()) {
            if (entry == null) {
                continue;
            }
            rememberSkin(entry.getUuid(), entry.getSkin());
        }
    }

    private void rememberSkin(UUID uuid, SerializedSkin skin) {
        if (uuid == null || skin == null) {
            return;
        }

        skinMetadata.put(uuid, new SkinMetadata(skin.isPersona(), skin.getSkinId(), System.currentTimeMillis()));
        if (skinMetadata.size() > MAX_METADATA_ENTRIES) {
            pruneMetadata();
        }
    }

    private void pruneMetadata() {
        var deadline = System.currentTimeMillis() - METADATA_TTL_MILLIS;
        skinMetadata.entrySet().removeIf(entry -> entry.getValue().seenAt() < deadline);
    }

    /**
     * Resolves the persona flag / skin id of a confirmed entry. Falls back to the online player that
     * owns the NetEase UID, because NPCs and fake players never show up in the player manager.
     */
    private SkinMetadata metadataFor(UUID uuid, String uidStr) {
        var metadata = skinMetadata.get(uuid);
        if (metadata != null) {
            return metadata;
        }

        var uid = parseUid(uidStr);
        if (uid == null) {
            return null;
        }

        var playerManager = Server.getInstance() == null ? null : Server.getInstance().getPlayerManager();
        if (playerManager == null) {
            return null;
        }

        for (var player : playerManager.getPlayers().values()) {
            LoginData loginData = player.getLoginData();
            if (loginData == null || loginData.getNetEaseData() == null) {
                continue;
            }
            if (loginData.getNetEaseData().uid() != uid) {
                continue;
            }

            Skin skin = loginData.getSkin();
            if (skin == null) {
                continue;
            }

            metadata = new SkinMetadata(skin.personaSkin(), skin.skinId(), System.currentTimeMillis());
            skinMetadata.put(uuid, metadata);
            return metadata;
        }

        return null;
    }

    private static Long parseUid(String uidStr) {
        if (uidStr == null || uidStr.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(uidStr.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Whether the UUID belongs to a connected player (as opposed to an NPC / fake player entity).
     */
    private static boolean isConnectedPlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        var server = Server.getInstance();
        if (server == null || server.getPlayerManager() == null) {
            return false;
        }
        return server.getPlayerManager().getPlayers().containsKey(uuid);
    }

    private boolean isWhitelisted(String uidStr) {
        var cfg = this.config;
        var whitelist = cfg == null ? null : cfg.getWhitelistUids();
        return whitelist != null && uidStr != null && whitelist.contains(uidStr);
    }

    private static boolean isNonsyncSkin(String skinId) {
        return skinId != null && (skinId.endsWith(NONSYNC_CUSTOM) || skinId.endsWith(NONSYNC_CUSTOM_SLIM));
    }

    // ==================== Validation ====================

    private void validate(Player viewer, List<ConfirmSkinPacket.SkinEntry> entries) {
        var cfg = this.config;
        if (cfg == null) {
            return;
        }

        var toRequest = new ArrayList<ConfirmSkinPacket.SkinEntry>();
        var seen = new HashSet<String>();

        for (var entry : entries) {
            var key = skinKey(entry);
            if (!seen.add(key)) {
                // Duplicate entry for the same target skin in one confirm packet.
                continue;
            }

            var future = inFlight.get(key);
            if (future == null) {
                var created = new CompletableFuture<Boolean>();
                var existing = inFlight.putIfAbsent(key, created);
                if (existing != null) {
                    future = existing;
                } else {
                    future = created;
                    toRequest.add(entry);
                }
            }

            var result = future;
            result.thenAccept(valid -> {
                if (Boolean.TRUE.equals(valid)) {
                    onPassed(entry);
                    sendConfirm(viewer, List.of(entry));
                } else {
                    onDenied(entry);
                }
            });
        }

        if (toRequest.isEmpty()) {
            return;
        }

        // geo_list must stay index aligned with md5_list, and the field is only mandatory for 2.1+
        // clients, so entries without geometry data are validated in a separate request.
        var withGeometry = new ArrayList<ConfirmSkinPacket.SkinEntry>();
        var withoutGeometry = new ArrayList<ConfirmSkinPacket.SkinEntry>();
        for (var entry : toRequest) {
            if (cfg.isCheckGeometry() && hasGeometry(entry)) {
                withGeometry.add(entry);
            } else {
                withoutGeometry.add(entry);
            }
        }

        var batchSize = Math.max(1, cfg.getMaxBatchSize());
        dispatchInChunks(withGeometry, true, batchSize);
        dispatchInChunks(withoutGeometry, false, batchSize);
    }

    private void dispatchInChunks(List<ConfirmSkinPacket.SkinEntry> entries, boolean withGeometry, int batchSize) {
        for (var start = 0; start < entries.size(); start += batchSize) {
            var end = Math.min(start + batchSize, entries.size());
            dispatch(new ArrayList<>(entries.subList(start, end)), withGeometry, 0);
        }
    }

    private void dispatch(List<ConfirmSkinPacket.SkinEntry> chunk, boolean withGeometry, int attempt) {
        if (shuttingDown) {
            complete(chunk, null, new IllegalStateException("Skin anti-cheat is shutting down"));
            return;
        }

        requestValidation(chunk, withGeometry).whenComplete((results, error) -> {
            var cfg = this.config;
            var retries = cfg == null ? 0 : cfg.getOnErrorRetries();
            if (error != null && attempt < retries && !shuttingDown) {
                log.debug("Skin check failed (attempt {}), retrying {} entr{}",
                        attempt + 1, chunk.size(), chunk.size() == 1 ? "y" : "ies", error);
                retryExecutor.schedule(() -> dispatch(chunk, withGeometry, attempt + 1), 300, TimeUnit.MILLISECONDS);
                return;
            }
            complete(chunk, results, error);
        });
    }

    private void complete(List<ConfirmSkinPacket.SkinEntry> chunk, boolean[] results, Throwable error) {
        var cfg = this.config;
        var failOpen = cfg != null && cfg.isFailOpenOnError();

        if (error != null) {
            log.warn("Skin check request failed, {} to {} entr{}",
                    failOpen ? "falling back" : "hiding",
                    chunk.size(), chunk.size() == 1 ? "y" : "ies", error);
        }

        for (var index = 0; index < chunk.size(); index++) {
            var entry = chunk.get(index);
            var future = inFlight.remove(skinKey(entry));
            if (future == null) {
                continue;
            }

            if (error != null) {
                future.complete(failOpen);
            } else {
                future.complete(index < results.length && results[index]);
            }
        }
    }

    private CompletableFuture<boolean[]> requestValidation(List<ConfirmSkinPacket.SkinEntry> chunk, boolean withGeometry) {
        var cfg = this.config;
        if (cfg == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Skin anti-cheat is not configured"));
        }

        var body = new JsonObject();
        body.addProperty("sign", cfg.getSign());

        var md5List = new JsonArray();
        var uidList = new JsonArray();
        var geoList = new JsonArray();

        for (var entry : chunk) {
            md5List.add(skinMd5(entry));
            uidList.add(uidOf(entry));
            if (withGeometry) {
                geoList.add(geoMd5(entry));
            }
        }

        body.add("md5_list", md5List);
        body.add("uid_list", uidList);
        if (withGeometry) {
            body.add("geo_list", geoList);
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(resolveUrl(cfg)))
                    .timeout(Duration.ofMillis(Math.max(500, cfg.getTimeoutMillis())))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        return httpClient()
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseValidation(chunk.size(), response));
    }

    private static boolean[] parseValidation(int expected, HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Skin check HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Skin check returned no JSON: " + response.body(), e);
        }

        var code = json.has("code") ? json.get("code").getAsInt() : -1;
        if (code != 0) {
            throw new IllegalStateException("Skin check error code " + code + ": " + response.body());
        }

        JsonElement entitiesElement = json.get("entities");
        if (entitiesElement == null || !entitiesElement.isJsonArray()) {
            throw new IllegalStateException("Skin check returned no entities: " + response.body());
        }

        JsonArray entities = entitiesElement.getAsJsonArray();
        var results = new boolean[entities.size()];
        for (var index = 0; index < entities.size(); index++) {
            results[index] = entities.get(index).getAsBoolean();
        }
        if (results.length != expected) {
            throw new IllegalStateException("Skin check returned " + results.length + " results for " + expected + " skins");
        }
        return results;
    }

    private HttpClient httpClient() {
        var client = this.httpClient;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (httpClient == null) {
                var cfg = this.config;
                var timeout = cfg == null ? 5000 : Math.max(500, cfg.getTimeoutMillis());
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(timeout))
                        .build();
            }
            return httpClient;
        }
    }

    private String resolveUrl(NetAllayConfig.SkinAntiCheatConfig cfg) {
        var custom = cfg.getWebServerUrl();
        if (custom != null && !custom.isBlank()) {
            var trimmed = custom.trim();
            if (!trimmed.contains("://")) {
                return "https://" + trimmed + CHECK_PATH;
            }
            return trimmed.contains(CHECK_PATH) ? trimmed : trimmed + CHECK_PATH;
        }
        return cfg.isTestServer() ? TEST_URL : PROD_URL;
    }

    // ==================== Packet sending ====================

    private void sendConfirm(Player viewer, Collection<ConfirmSkinPacket.SkinEntry> entries) {
        if (entries.isEmpty() || viewer == null || !viewer.isNetEasePlayer()) {
            return;
        }

        var packet = new ConfirmSkinPacket();
        packet.setEntries(new ArrayList<>(entries));

        forwarding.set(Boolean.TRUE);
        try {
            viewer.sendPacket(packet);
        } catch (Exception e) {
            log.error("Failed to send ConfirmSkinPacket to {}", viewer.getOriginName(), e);
        } finally {
            forwarding.remove();
        }
    }

    private void onDenied(ConfirmSkinPacket.SkinEntry entry) {
        var cfg = this.config;
        if (cfg != null && cfg.isLogDeniedSkin()) {
            log.info("Skin denied by anti-cheat: uuid={}, uid={}", entry.getUuid(), uidOf(entry));
        }
        log.debug("Skin entry held back: uuid={}, uid={}, md5={}", entry.getUuid(), uidOf(entry), skinMd5(entry));
    }

    private void onPassed(ConfirmSkinPacket.SkinEntry entry) {
        var cfg = this.config;
        if (cfg != null && cfg.isLogPassedSkin()) {
            log.info("Skin passed anti-cheat: uuid={}, uid={}, md5={}", entry.getUuid(), uidOf(entry), skinMd5(entry));
        }
    }

    // ==================== Hashing helpers ====================

    private static boolean hasGeometry(ConfirmSkinPacket.SkinEntry entry) {
        var geometry = entry.getGeoStr();
        return geometry != null && !geometry.isBlank();
    }

    /**
     * Builds the identity of a skin entry: target UUID, skin MD5, geometry MD5 and NetEase UID.
     * <p>
     * The key is used to coalesce identical concurrent requests (the same target skin confirmed to
     * many viewers) and must therefore not contain the viewer.
     */
    static String skinKey(ConfirmSkinPacket.SkinEntry entry) {
        var uuid = entry.getUuid();
        return (uuid == null ? "" : uuid.toString())
                + ":" + skinMd5(entry)
                + ":" + geoMd5(entry)
                + ":" + uidOf(entry);
    }

    /**
     * Skin MD5 as defined by the official scheme: {@code md5(base64(imageBytes))}.
     */
    static String skinMd5(ConfirmSkinPacket.SkinEntry entry) {
        var bytes = entry.getSkinBytes();
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return md5Hex(Base64.getEncoder().encodeToString(bytes));
    }

    /**
     * Geometry MD5 as defined by the official scheme: {@code md5(geometryJson)}.
     */
    static String geoMd5(ConfirmSkinPacket.SkinEntry entry) {
        var geometry = entry.getGeoStr();
        if (geometry == null || geometry.isEmpty()) {
            return "";
        }
        return md5Hex(geometry);
    }

    static String md5Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            var builder = new StringBuilder(32);
            for (var b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 is mandatory for every Java runtime.
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }

    private static String uidOf(ConfirmSkinPacket.SkinEntry entry) {
        var uid = entry.getUidStr();
        return uid == null ? "" : uid;
    }

    /**
     * Persona flag and skin id collected for a player UUID.
     */
    record SkinMetadata(boolean persona, String skinId, long seenAt) {
    }
}
