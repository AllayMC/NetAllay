package org.allaymc.netallay.codec;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.msgpack.value.ValueType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Codec for encoding and decoding PyRpc messages using MessagePack.
 * <p>
 * NetEase's PyRpc protocol uses a specific MessagePack format:
 * <ul>
 *   <li>All strings are encoded as BINARY (not STRING)</li>
 *   <li>Message format is a 3-element array: [eventType, [namespace, system, event, data], null]</li>
 *   <li>Event type is "ModEventS2C" for server-to-client, "ModEventC2S" for client-to-server</li>
 * </ul>
 *
 * @author YiRanKuma
 */
public final class PyRpcCodec {

    private static final Logger log = LoggerFactory.getLogger(PyRpcCodec.class);

    /**
     * Server to Client event type identifier.
     */
    public static final String EVENT_TYPE_S2C = "ModEventS2C";

    /**
     * Client to Server event type identifier.
     */
    public static final String EVENT_TYPE_C2S = "ModEventC2S";

    private PyRpcCodec() {
        // Utility class
    }

    /**
     * Encodes a PyRpc message for sending to a client.
     *
     * @param namespace  the namespace of the event
     * @param systemName the system name
     * @param eventName  the event name
     * @param data       the event data
     * @return the encoded message as a byte array
     * @throws IOException if encoding fails
     */
    public static byte[] encode(String namespace, String systemName, String eventName, Map<String, Object> data) throws IOException {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            // Format: [eventType, [namespace, systemName, eventName, data], null]
            packer.packArrayHeader(3);

            // [0] Event type
            packBinaryString(packer, EVENT_TYPE_S2C);

            // [1] Event details array
            packer.packArrayHeader(4);
            packBinaryString(packer, namespace);
            packBinaryString(packer, systemName);
            packBinaryString(packer, eventName);
            packMap(packer, data != null ? data : Collections.emptyMap());

            // [2] Extra data (null)
            packer.packNil();

            return packer.toByteArray();
        }
    }

    /**
     * Decodes a PyRpc message received from a client.
     *
     * @param data the raw message data
     * @return a ParsedEvent containing the decoded information, or null if decoding fails
     */
    public static ParsedEvent decode(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data)) {
            if (!unpacker.hasNext()) {
                return null;
            }

            Value value = unpacker.unpackValue();
            if (!value.isArrayValue()) {
                log.warn("[Decode] Root is not array: {}", value.getValueType());
                return null;
            }

            List<Value> array = value.asArrayValue().list();
            if (array.size() < 2) {
                log.warn("[Decode] Root array too short: size={}", array.size());
                return null;
            }

            // Parse event type
            String eventType = valueToString(array.get(0));

            // Parse event details - can be array or map depending on packet type
            Value detailsValue = array.get(1);

            // Standard format: [eventType, [namespace, system, event, data], ...]
            if (detailsValue.isArrayValue()) {
                List<Value> details = detailsValue.asArrayValue().list();
                if (details.isEmpty()) {
                    // Engine callback format: [eventType, [], null]
                    // eventType IS the event name (e.g. StoreBuySuccServerEvent, UrgeShipEvent)
                    return new ParsedEvent(eventType, "engine", "callback", eventType, new HashMap<>());
                }

                if (details.size() < 3) {
                    log.warn("[Decode] Details array too short: size={}, eventType={}", details.size(), eventType);
                    return null;
                }

                String namespace = valueToString(details.get(0));
                String systemName = valueToString(details.get(1));
                String eventName = valueToString(details.get(2));
                Object eventData = details.size() > 3 ? valueToObject(details.get(3)) : null;

                Map<String, Object> dataMap = toDataMap(eventData);
                return new ParsedEvent(eventType, namespace, systemName, eventName, dataMap);
            }

            // Alternative format: [eventType, {data}, ...] (engine callbacks etc.)
            if (detailsValue.isMapValue() || detailsValue.isBinaryValue() || detailsValue.isStringValue()) {
                Object eventData = valueToObject(detailsValue);
                Map<String, Object> dataMap = toDataMap(eventData);
                // Use eventType as both namespace and event name for non-standard formats
                return new ParsedEvent(eventType, "engine", "callback", eventType, dataMap);
            }

            log.warn("[Decode] Unexpected details type: {}, eventType={}", detailsValue.getValueType(), eventType);
            return null;

        } catch (Exception e) {
            log.warn("[Decode] Failed to decode PyRpc packet: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Converts event data to a Map.
     */
    private static Map<String, Object> toDataMap(Object eventData) {
        if (eventData instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> temp = (Map<String, Object>) eventData;
            return temp;
        }
        Map<String, Object> dataMap = new HashMap<>();
        if (eventData != null) {
            dataMap.put("rawData", eventData);
        }
        return dataMap;
    }

    /**
     * Packs a string as binary (NetEase format requirement).
     */
    private static void packBinaryString(MessageBufferPacker packer, String str) throws IOException {
        if (str == null) {
            packer.packNil();
            return;
        }
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        packer.packBinaryHeader(bytes.length);
        packer.writePayload(bytes);
    }

    /**
     * Packs a Map into MessagePack format.
     */
    private static void packMap(MessageBufferPacker packer, Map<String, Object> map) throws IOException {
        packer.packMapHeader(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            packBinaryString(packer, entry.getKey());
            packValue(packer, entry.getValue());
        }
    }

    /**
     * Packs any supported value type.
     */
    private static void packValue(MessageBufferPacker packer, Object value) throws IOException {
        if (value == null) {
            packer.packNil();
        } else if (value instanceof Boolean b) {
            packer.packBoolean(b);
        } else if (value instanceof Integer i) {
            packer.packInt(i);
        } else if (value instanceof Long l) {
            packer.packLong(l);
        } else if (value instanceof Float f) {
            packer.packFloat(f);
        } else if (value instanceof Double d) {
            packer.packDouble(d);
        } else if (value instanceof String s) {
            packBinaryString(packer, s);
        } else if (value instanceof byte[] bytes) {
            packer.packBinaryHeader(bytes.length);
            packer.writePayload(bytes);
        } else if (value instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) m;
            packMap(packer, map);
        } else if (value instanceof Iterable<?> iterable) {
            List<Object> list = new ArrayList<>();
            for (Object item : iterable) {
                list.add(item);
            }
            packer.packArrayHeader(list.size());
            for (Object item : list) {
                packValue(packer, item);
            }
        } else if (value.getClass().isArray()) {
            Object[] arr = (Object[]) value;
            packer.packArrayHeader(arr.length);
            for (Object item : arr) {
                packValue(packer, item);
            }
        } else {
            // Fallback: convert to string
            packBinaryString(packer, value.toString());
        }
    }

    /**
     * Converts a MessagePack Value to a String.
     */
    private static String valueToString(Value value) {
        if (value == null || value.isNilValue()) {
            return null;
        }
        if (value.isStringValue()) {
            return value.asStringValue().asString();
        }
        if (value.isBinaryValue()) {
            return new String(value.asBinaryValue().asByteArray(), StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    /**
     * Converts a MessagePack Value to a Java Object.
     */
    private static Object valueToObject(Value value) {
        if (value == null || value.isNilValue()) {
            return null;
        }

        ValueType type = value.getValueType();

        return switch (type) {
            case BOOLEAN -> value.asBooleanValue().getBoolean();
            case INTEGER -> value.asIntegerValue().toLong();
            case FLOAT -> value.asFloatValue().toDouble();
            case STRING -> value.asStringValue().asString();
            case BINARY -> new String(value.asBinaryValue().asByteArray(), StandardCharsets.UTF_8);
            case ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (Value item : value.asArrayValue()) {
                    list.add(valueToObject(item));
                }
                yield list;
            }
            case MAP -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Map.Entry<Value, Value> entry : value.asMapValue().entrySet()) {
                    String key = valueToString(entry.getKey());
                    if (key != null) {
                        map.put(key, valueToObject(entry.getValue()));
                    }
                }
                yield map;
            }
            default -> value.toString();
        };
    }

    /**
     * Represents a parsed PyRpc event.
     */
    public record ParsedEvent(
            String eventType,
            String namespace,
            String systemName,
            String eventName,
            Map<String, Object> eventData
    ) {
        /**
         * Creates a unique key for identifying this event type.
         */
        public String getEventKey() {
            return namespace + ":" + systemName + ":" + eventName;
        }
    }
}
