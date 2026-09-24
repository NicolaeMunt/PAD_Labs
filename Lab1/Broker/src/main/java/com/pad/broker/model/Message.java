package com.pad.broker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable DTO mirroring the NDJSON wire schema. A single shape is reused for every
 * {@code type}; fields irrelevant to a given type are simply left null and omitted
 * from the serialized JSON via {@link JsonInclude.Include#NON_NULL}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Message(
        @JsonProperty("type") String type,
        @JsonProperty("messageId") String messageId,
        @JsonProperty("publisherId") String publisherId,
        @JsonProperty("subscriberId") String subscriberId,
        @JsonProperty("topic") String topic,
        @JsonProperty("payload") Object payload,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("message") String detail
) {

    public static Message error(String detail) {
        return new Message("error", null, null, null, null, null, null, detail);
    }

    public static Message ackRegistration(String topic, String detail) {
        return new Message("ack_registration", null, null, null, topic, null, null, detail);
    }

    public static Message publishAck(String messageId, String topic) {
        return new Message("publish_ack", messageId, null, null, topic, null, null, null);
    }

    public static Message ackConfirmed(String messageId) {
        return new Message("ack_confirmed", messageId, null, null, null, null, null, null);
    }

    /** Converts a broker-side {@code publish} message into the {@code message} envelope sent to subscribers. */
    public Message asSubscriberMessage() {
        return new Message("message", messageId, publisherId, null, topic, payload, timestamp, null);
    }
}
