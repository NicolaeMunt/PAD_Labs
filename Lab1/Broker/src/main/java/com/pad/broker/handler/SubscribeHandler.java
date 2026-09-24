package com.pad.broker.handler;

import com.pad.broker.dispatch.MessageHandler;
import com.pad.broker.dlq.DeadLetterQueue;
import com.pad.broker.model.Message;
import com.pad.broker.model.OperationResult;
import com.pad.broker.net.ClientSession;
import com.pad.broker.subscription.MessageStore;
import com.pad.broker.subscription.SubscriberConnectionRegistry;
import com.pad.broker.subscription.SubscriberDeliveryExecutor;
import com.pad.broker.subscription.SubscriptionRegistry;
import com.pad.broker.topic.TopicRegistry;
import com.pad.broker.util.BrokerLogger;
import com.pad.broker.validation.MessageValidator;

import java.util.List;

public class SubscribeHandler implements MessageHandler {

    private final MessageValidator validator;
    private final TopicRegistry topicRegistry;
    private final SubscriptionRegistry subscriptionRegistry;
    private final SubscriberConnectionRegistry connectionRegistry;
    private final MessageStore messageStore;
    private final SubscriberDeliveryExecutor deliveryExecutor;
    private final DeadLetterQueue deadLetterQueue;

    public SubscribeHandler(MessageValidator validator,
                             TopicRegistry topicRegistry,
                             SubscriptionRegistry subscriptionRegistry,
                             SubscriberConnectionRegistry connectionRegistry,
                             MessageStore messageStore,
                             SubscriberDeliveryExecutor deliveryExecutor,
                             DeadLetterQueue deadLetterQueue) {
        this.validator = validator;
        this.topicRegistry = topicRegistry;
        this.subscriptionRegistry = subscriptionRegistry;
        this.connectionRegistry = connectionRegistry;
        this.messageStore = messageStore;
        this.deliveryExecutor = deliveryExecutor;
        this.deadLetterQueue = deadLetterQueue;
    }

    @Override
    public void handle(String rawLine, Message message, ClientSession session) {
        OperationResult validation = validator.validateSubscribe(message);
        if (!validation.success()) {
            deadLetterQueue.add(rawLine, validation.reason());
            session.send(Message.error(validation.reason()));
            return;
        }

        // Subscribers may connect before the topic's publisher does (and don't retry a failed subscribe),
        // so subscribing creates the topic; the publisher claims it later via register_publisher.
        topicRegistry.getOrCreate(message.topic());
        subscriptionRegistry.subscribe(message.topic(), message.subscriberId());
        connectionRegistry.register(message.subscriberId(), session.sender());
        session.setSubscriberId(message.subscriberId());
        BrokerLogger.log("Subscriber subscribed", "subscriberId=" + message.subscriberId() + " topic=" + message.topic());

        session.send(Message.ackRegistration(message.topic(), "Subscribed to topic '" + message.topic() + "'"));

        // Same subscriberId reconnecting: redeliver what is still unacked for this topic, in original order.
        // Runs on the subscriber's FIFO executor so the replay never interleaves with live fan-out.
        String subscriberId = message.subscriberId();
        String topic = message.topic();
        deliveryExecutor.submit(subscriberId, () -> replayPending(subscriberId, topic, session));
    }

    private void replayPending(String subscriberId, String topic, ClientSession session) {
        List<Message> pending = messageStore.getPending(subscriberId).stream()
                .filter(pendingMessage -> topic.equals(pendingMessage.topic()))
                .toList();
        if (!pending.isEmpty()) {
            BrokerLogger.log("Replaying pending messages", "subscriberId=" + subscriberId + " topic=" + topic + " count=" + pending.size());
        }
        for (Message pendingMessage : pending) {
            session.send(pendingMessage);
        }
    }
}
