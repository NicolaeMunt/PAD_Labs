package com.pad.broker.net;

import com.pad.broker.dispatch.MessageDispatcher;
import com.pad.broker.dlq.DeadLetterQueue;
import com.pad.broker.model.Message;
import com.pad.broker.subscription.SubscriberConnectionRegistry;
import com.pad.broker.util.BrokerLogger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Per-connection I/O loop: reads NDJSON line by line, decodes, and delegates to the
 * dispatcher. Knows nothing about topics/queues — only about framing and routing.
 */
public class ClientConnectionHandler implements Runnable, MessageSender {

    private final Socket socket;
    private final MessageDispatcher dispatcher;
    private final JsonCodec jsonCodec;
    private final SubscriberConnectionRegistry connectionRegistry;
    private final DeadLetterQueue deadLetterQueue;
    private final ClientSession session;
    private final Object writeLock = new Object();
    private volatile BufferedWriter writer;
    // Replies go out in whichever dialect the client last spoke; see WireDialect.
    private volatile WireDialect dialect = WireDialect.NATIVE;

    public ClientConnectionHandler(Socket socket,
                                    MessageDispatcher dispatcher,
                                    JsonCodec jsonCodec,
                                    SubscriberConnectionRegistry connectionRegistry,
                                    DeadLetterQueue deadLetterQueue) {
        this.socket = socket;
        this.dispatcher = dispatcher;
        this.jsonCodec = jsonCodec;
        this.connectionRegistry = connectionRegistry;
        this.deadLetterQueue = deadLetterQueue;
        this.session = new ClientSession(this);
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    handleLine(line);
                }
            }
        } catch (IOException e) {
            BrokerLogger.log("Connection error", e.getMessage());
        } finally {
            disconnect();
        }
    }

    private void handleLine(String line) {
        JsonCodec.DecodedFrame frame;
        try {
            frame = jsonCodec.decodeFrame(line);
        } catch (Exception e) {
            String reason = "Invalid JSON: " + e.getMessage();
            deadLetterQueue.add(line, reason);
            trySend(Message.error(reason));
            return;
        }
        dialect = frame.dialect();
        dispatcher.dispatch(line, frame.message(), session);
    }

    @Override
    public void send(Message message) throws IOException {
        String json = jsonCodec.encode(message, dialect);
        synchronized (writeLock) {
            if (writer == null) {
                throw new IOException("Connection not yet established");
            }
            writer.write(json);
            writer.write("\n");
            writer.flush();
        }
    }

    private void trySend(Message message) {
        try {
            send(message);
        } catch (IOException ignored) {
            // Best-effort: the connection is already going away.
        }
    }

    private void disconnect() {
        if (session.getSubscriberId() != null) {
            connectionRegistry.unregister(session.getSubscriberId(), this);
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        BrokerLogger.log("Client disconnected", socket.getRemoteSocketAddress() + " publisherId=" + session.getPublisherId() + " subscriberId=" + session.getSubscriberId());
    }
}
