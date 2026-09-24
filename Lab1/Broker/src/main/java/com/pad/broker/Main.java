package com.pad.broker;

import com.pad.broker.dispatch.MessageDispatcher;
import com.pad.broker.dispatch.MessageHandler;
import com.pad.broker.dlq.DeadLetterQueue;
import com.pad.broker.handler.AckHandler;
import com.pad.broker.handler.PublishHandler;
import com.pad.broker.handler.RegisterPublisherHandler;
import com.pad.broker.handler.SubscribeHandler;
import com.pad.broker.net.BrokerServer;
import com.pad.broker.net.JsonCodec;
import com.pad.broker.subscription.InMemoryMessageStore;
import com.pad.broker.subscription.MessageStore;
import com.pad.broker.subscription.SubscriberConnectionRegistry;
import com.pad.broker.subscription.SubscriberDeliveryExecutor;
import com.pad.broker.subscription.SubscriptionRegistry;
import com.pad.broker.topic.PublisherRegistry;
import com.pad.broker.topic.TopicRegistry;
import com.pad.broker.topic.TopicWorkerLauncher;
import com.pad.broker.validation.MessageValidator;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Composition root: wires every collaborator together and starts the server. No business logic here. */
public class Main {

    private static final int DEFAULT_PORT = 5000;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        ExecutorService topicWorkerExecutor = Executors.newCachedThreadPool();
        SubscriberDeliveryExecutor deliveryExecutor = new SubscriberDeliveryExecutor();

        SubscriptionRegistry subscriptionRegistry = new SubscriptionRegistry();
        MessageStore messageStore = new InMemoryMessageStore();
        SubscriberConnectionRegistry connectionRegistry = new SubscriberConnectionRegistry();

        TopicWorkerLauncher topicWorkerLauncher = new TopicWorkerLauncher(
                topicWorkerExecutor, subscriptionRegistry, messageStore, connectionRegistry, deliveryExecutor);
        TopicRegistry topicRegistry = new TopicRegistry(topicWorkerLauncher);
        PublisherRegistry publisherRegistry = new PublisherRegistry(topicRegistry);

        MessageValidator validator = new MessageValidator();
        DeadLetterQueue deadLetterQueue = new DeadLetterQueue();
        JsonCodec jsonCodec = new JsonCodec();

        Map<String, MessageHandler> handlersByType = Map.of(
                "register_publisher", new RegisterPublisherHandler(validator, publisherRegistry, deadLetterQueue),
                "publish", new PublishHandler(validator, publisherRegistry, topicRegistry, deadLetterQueue),
                "subscribe", new SubscribeHandler(validator, topicRegistry, subscriptionRegistry, connectionRegistry, messageStore, deliveryExecutor, deadLetterQueue),
                "ack", new AckHandler(validator, messageStore, deadLetterQueue)
        );

        MessageDispatcher dispatcher = new MessageDispatcher(handlersByType, deadLetterQueue);
        BrokerServer server = new BrokerServer(port, dispatcher, jsonCodec, connectionRegistry, deadLetterQueue);
        server.start();
    }
}
