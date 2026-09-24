package com.pad.broker.net;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pad.broker.model.Message;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Centralizes Jackson usage so the rest of the codebase depends on this class, not on ObjectMapper directly.
 * Also translates between the internal {@link Message} and the {@link WireDialect#COMPACT} envelope, so
 * handlers only ever see the native shape.
 */
public class JsonCodec {

    private static final String BROKER_CLIENT_ID = "broker";

    private final ObjectMapper mapper = new ObjectMapper();

    /** A decoded frame plus the dialect it was written in. */
    public record DecodedFrame(Message message, WireDialect dialect) {
    }

    public String encode(Message message) throws JsonProcessingException {
        return mapper.writeValueAsString(message);
    }

    public String encode(Message message, WireDialect dialect) throws JsonProcessingException {
        return dialect == WireDialect.COMPACT ? mapper.writeValueAsString(toCompact(message)) : encode(message);
    }

    public DecodedFrame decodeFrame(String line) throws JsonProcessingException {
        JsonNode root = mapper.readTree(line);
        if (root == null || !root.isObject()) {
            throw JsonMappingException.from((JsonParser) null, "Expected a JSON object");
        }
        Message message = mapper.treeToValue(root, Message.class);
        if (!isCompact(root)) {
            return new DecodedFrame(message, WireDialect.NATIVE);
        }
        return new DecodedFrame(fromCompact(root, message), WireDialect.COMPACT);
    }

    /** Compact frames use upper-case types and/or a single {@code clientId} instead of publisherId/subscriberId. */
    private static boolean isCompact(JsonNode root) {
        String type = Objects.requireNonNullElse(textOrNull(root, "type"), "");
        boolean upperCaseType = !type.isEmpty() && !type.equals(type.toLowerCase(Locale.ROOT));
        boolean clientIdOnly = root.hasNonNull("clientId") && !root.hasNonNull("publisherId") && !root.hasNonNull("subscriberId");
        return upperCaseType || clientIdOnly;
    }

    private static Message fromCompact(JsonNode root, Message parsed) {
        String type = parsed.type() == null ? null : parsed.type().toLowerCase(Locale.ROOT);
        String clientId = textOrNull(root, "clientId");
        String messageId = firstNonBlank(parsed.messageId(), textOrNull(root, "id"));
        // The Receiver's ACK carries the acked id in payload when it has no messageId field.
        if ("ack".equals(type) && messageId == null && parsed.payload() instanceof String payloadText && !payloadText.isBlank()) {
            messageId = payloadText;
        }
        return new Message(
                type,
                messageId,
                firstNonBlank(parsed.publisherId(), clientId),
                firstNonBlank(parsed.subscriberId(), clientId),
                parsed.topic(),
                parsed.payload(),
                parsed.timestamp(),
                parsed.detail());
    }

    private ObjectNode toCompact(Message message) {
        ObjectNode node = mapper.createObjectNode();
        String type = message.type() == null ? "" : message.type();
        switch (type) {
            case "message" -> {
                node.put("type", "MESSAGE");
                node.put("clientId", message.publisherId());
                node.set("payload", mapper.valueToTree(message.payload()));
            }
            case "error" -> {
                node.put("type", "ERROR");
                node.put("clientId", BROKER_CLIENT_ID);
                node.put("payload", message.detail());
            }
            case "ack_registration" -> {
                node.put("type", "ACK");
                node.put("clientId", BROKER_CLIENT_ID);
                node.put("payload", message.detail());
            }
            case "publish_ack", "ack_confirmed" -> {
                node.put("type", "ACK");
                node.put("clientId", BROKER_CLIENT_ID);
                node.put("payload", message.messageId());
            }
            default -> {
                node.put("type", type.toUpperCase(Locale.ROOT));
                node.put("clientId", BROKER_CLIENT_ID);
                node.put("payload", message.detail());
            }
        }
        if (message.topic() != null) {
            node.put("topic", message.topic());
        }
        node.put("timestamp", message.timestamp() != null ? message.timestamp() : Instant.now().toString());
        if (message.messageId() != null) {
            node.put("messageId", message.messageId());
        }
        return node;
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
