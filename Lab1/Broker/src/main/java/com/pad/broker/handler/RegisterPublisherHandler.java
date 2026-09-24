package com.pad.broker.handler;

import com.pad.broker.dispatch.MessageHandler;
import com.pad.broker.dlq.DeadLetterQueue;
import com.pad.broker.model.Message;
import com.pad.broker.model.OperationResult;
import com.pad.broker.net.ClientSession;
import com.pad.broker.topic.PublisherRegistry;
import com.pad.broker.util.BrokerLogger;
import com.pad.broker.validation.MessageValidator;

public class RegisterPublisherHandler implements MessageHandler {

    private final MessageValidator validator;
    private final PublisherRegistry publisherRegistry;
    private final DeadLetterQueue deadLetterQueue;

    public RegisterPublisherHandler(MessageValidator validator, PublisherRegistry publisherRegistry, DeadLetterQueue deadLetterQueue) {
        this.validator = validator;
        this.publisherRegistry = publisherRegistry;
        this.deadLetterQueue = deadLetterQueue;
    }

    @Override
    public void handle(String rawLine, Message message, ClientSession session) {
        OperationResult validation = validator.validateRegisterPublisher(message);
        if (!validation.success()) {
            deadLetterQueue.add(rawLine, validation.reason());
            session.send(Message.error(validation.reason()));
            return;
        }

        OperationResult registration = publisherRegistry.register(message.publisherId(), message.topic());
        if (!registration.success()) {
            deadLetterQueue.add(rawLine, registration.reason());
            session.send(Message.error(registration.reason()));
            return;
        }

        session.setPublisherId(message.publisherId());
        BrokerLogger.log("Publisher registered", "publisherId=" + message.publisherId() + " topic=" + message.topic());
        session.send(Message.ackRegistration(message.topic(), "Publisher '" + message.publisherId() + "' registered for topic '" + message.topic() + "'"));
    }
}
