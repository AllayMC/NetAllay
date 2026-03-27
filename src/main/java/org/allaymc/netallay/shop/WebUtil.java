package org.allaymc.netallay.shop;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Utility class for communicating with NetEase shop order servers.
 * <p>
 * Handles HMAC-SHA256 signed HTTP requests for order retrieval and completion.
 *
 * @author YiRanKuma
 */
public class WebUtil {

    private static final Logger log = LoggerFactory.getLogger(WebUtil.class);
    private static final Gson GSON = new Gson();

    public static final String GAS_SERVER_BASE_URL = "http://gasproxy.mc.netease.com:60002";
    public static final String TEST_GAS_SERVER_BASE_URL = "http://gasproxy.mc.netease.com:60001";

    public static final String GET_ITEM_ORDER_LIST_PATH = "/get-mc-item-order-list";
    public static final String SHIP_ITEM_ORDER_PATH = "/ship-mc-item-order";

    private static HttpClient httpClient;

    /**
     * Starts the HTTP client.
     */
    public static void startHttpClient() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("HTTP client started");
    }

    /**
     * Stops the HTTP client.
     */
    public static void stopHttpClient() {
        httpClient = null;
        log.info("HTTP client stopped");
    }

    /**
     * Selects the appropriate base URL for shop order requests.
     */
    public static String getShopBaseUrl(String customUrl, boolean isTestServer) {
        if (customUrl != null && !customUrl.isEmpty()) {
            return customUrl;
        }
        return isTestServer ? TEST_GAS_SERVER_BASE_URL : GAS_SERVER_BASE_URL;
    }

    /**
     * Gets the player's order list from NetEase order server.
     *
     * @param gameId       the game ID
     * @param playerUuid   the player's UUID string
     * @param signKey      the game key for signing (testGameKey or gameKey)
     * @param shopBaseUrl  the base URL for the order server
     * @return CompletableFuture with the parsed JSON response
     */
    public static CompletableFuture<JsonObject> getPlayerOrderList(
            String gameId, String playerUuid, String signKey, String shopBaseUrl) {

        JsonObject payload = new JsonObject();
        payload.addProperty("gameid", gameId);
        payload.addProperty("uuid", playerUuid);
        String json = GSON.toJson(payload);

        return postSigned(shopBaseUrl + GET_ITEM_ORDER_LIST_PATH, GET_ITEM_ORDER_LIST_PATH, json, signKey);
    }

    /**
     * Notifies the NetEase order server that orders have been shipped.
     *
     * @param gameId       the game ID
     * @param playerUuid   the player's UUID string
     * @param orderIds     list of completed order IDs
     * @param signKey      the game key for signing
     * @param shopBaseUrl  the base URL for the order server
     * @return CompletableFuture with the parsed JSON response
     */
    public static CompletableFuture<JsonObject> finishPlayerOrder(
            String gameId, String playerUuid, List<String> orderIds, String signKey, String shopBaseUrl) {

        JsonObject payload = new JsonObject();
        payload.addProperty("gameid", gameId);
        payload.addProperty("uuid", playerUuid);
        payload.add("orderid_list", GSON.toJsonTree(orderIds));
        String json = GSON.toJson(payload);

        return postSigned(shopBaseUrl + SHIP_ITEM_ORDER_PATH, SHIP_ITEM_ORDER_PATH, json, signKey);
    }

    /**
     * Sends a signed POST request to the NetEase server.
     */
    private static CompletableFuture<JsonObject> postSigned(String url, String path, String jsonBody, String signKey) {
        if (httpClient == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("HTTP client not started"));
        }

        String signature;
        try {
            signature = getServerSign(signKey, "POST", path, jsonBody);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Netease-Server-Sign", signature)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        log.debug("Sending POST to {}", url);

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                    }
                    JsonObject jsonResult = JsonParser.parseString(response.body()).getAsJsonObject();
                    int code = jsonResult.has("code") ? jsonResult.get("code").getAsInt() : -1;
                    if (code != 0) {
                        throw new RuntimeException("API error code: " + code + ", response: " + response.body());
                    }
                    return jsonResult;
                });
    }

    /**
     * Generates HMAC-SHA256 signature for NetEase API requests.
     * <p>
     * Sign string format: METHOD + PATH + BODY
     */
    public static String getServerSign(String signKey, String method, String path, String httpBody) throws Exception {
        String str2sign = method + path + httpBody;
        String signStr = hmacSHA256(str2sign, signKey);
        // Pad to 64 characters with leading zeros if needed
        if (signStr.length() < 64) {
            int padLen = 64 - signStr.length();
            signStr = String.join("", Collections.nCopies(padLen, "0")) + signStr;
        }
        return signStr;
    }

    private static String hmacSHA256(String data, String key) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(secretKey);
        mac.update(data.getBytes(StandardCharsets.UTF_8));
        byte[] result = mac.doFinal();
        return new BigInteger(1, result).toString(16);
    }
}
