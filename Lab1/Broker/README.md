# Broker — Java 17

The message broker of the PAD message-broker project. It accepts TCP connections from
publishers and subscribers, keeps a queue per topic and a pending-message queue per
subscriber, and guarantees at-least-once delivery via explicit ACKs. Everything on the
wire is UTF-8 NDJSON (one JSON object per line), so any client that speaks that framing
can talk to it, regardless of language.

- Java 17, single runtime dependency: `jackson-databind` (JSON only, nothing else)
- Thread-per-connection networking, one dedicated worker thread per topic, one delivery
  thread per subscriber — see [§4 Concurrency model](#4-concurrency-model)
- Invalid or rejected messages are never silently dropped: they go to an in-memory
  **Dead Letter Queue** with a reason
- Packaged as a self-contained shaded JAR; a `Dockerfile` + `docker-compose.yml` are
  included for hosting

---

## 1. Running it

Requires JDK 17+ and Maven (or just Docker).

```bash
cd Lab1/Broker
mvn -q package -DskipTests
java -jar target/Broker-1.0-SNAPSHOT.jar          # listens on 0.0.0.0:5000
java -jar target/Broker-1.0-SNAPSHOT.jar 6000     # optional: override the port
```

Or with Docker:

```bash
cd Lab1/Broker
docker compose up --build     # builds the shaded jar inside a maven image, then runs it
```

This exposes port **5000** on the host (see `docker-compose.yml`) and attaches the
container to a `pad-net` bridge network, so other containers (a dockerized Sender/Receiver)
can reach it by service name (`broker:5000`) instead of `localhost`.

There is no config file: the only setting is the listen port, taken from `args[0]`
(default `5000`, see `Main.java`).

**Logging:** every notable event (connection opened/closed, publisher registered, topic
created, message routed, DLQ rejection, …) is printed to **stdout** as
`[<ISO-8601 instant>] <event> - <details>`. There is no separate log file.

---

## 2. Wire protocol

### 2.1 Transport and framing

| Item | Rule |
|---|---|
| Transport | TCP. Each client opens **one** connection and keeps it open; the broker pushes messages into that same connection. |
| Framing | **NDJSON**: exactly one JSON object per line, terminated by `\n`. |
| Encoding | UTF-8. |
| Reads | The broker reads with `BufferedReader.readLine()`; frames may be split or coalesced across TCP segments, that's transparent either way. |
| Unknown fields | Ignored on decode (`@JsonIgnoreProperties(ignoreUnknown = true)`), so a client may send extra fields safely. |

### 2.2 Envelope

Every frame, in both directions, is one shape (`Message.java`). Fields that don't apply
to a given `type` are simply omitted (`@JsonInclude(NON_NULL)`), not sent as `null`:

```json
{
  "type":         "publish",
  "messageId":    "m-1",
  "publisherId":  "pub-1",
  "subscriberId": "sub-1",
  "topic":        "news",
  "payload":      "Hello, world",
  "timestamp":    "2026-09-16T14:03:07.123Z",
  "message":      "human-readable detail, used by ack_registration/error"
}
```

| Field | Notes |
|---|---|
| `type` | Lower-case, exact match required (`register_publisher`, `publish`, `subscribe`, `ack`, `message`, `publish_ack`, `ack_registration`, `ack_confirmed`, `error`). |
| `messageId` | Caller-assigned, unique per published message. Required on `publish`/`ack`; echoed back on `publish_ack`/`ack_confirmed`/`message`. |
| `publisherId` | Set once via `register_publisher`, then required on every `publish` from that connection. |
| `subscriberId` | Required on `subscribe`/`ack`. Stable across reconnects — see [§3.4](#34-reconnect-and-redelivery). |
| `topic` | Required on `register_publisher`/`publish`/`subscribe`. |
| `payload` | Any JSON value (object, string, number, …), required on `publish`. Passed through verbatim to subscribers. |
| `timestamp` | Free-form string set by the publisher, required on `publish`, forwarded unchanged in `message`. |
| `message` | Human-readable detail on `ack_registration` and `error` only. |

### 2.3 Frames — client → broker

| `type` | Who sends it | Required fields | Effect |
|---|---|---|---|
| `register_publisher` | Publisher, once, right after connecting | `publisherId`, `topic` | Claims the topic for this publisher (1 publisher per topic — see [§3.1](#31-topics-and-publishers)). Creates the topic if it doesn't exist yet. |
| `publish` | Publisher, after registering | `messageId`, `publisherId`, `topic`, `payload`, `timestamp` | Enqueues the message on the topic's queue for fan-out to subscribers. |
| `subscribe` | Subscriber, once per topic, right after connecting | `subscriberId`, `topic` | Subscribes this connection to the topic and immediately replays anything still unacked for `subscriberId` (see [§3.4](#34-reconnect-and-redelivery)). |
| `ack` | Subscriber, after handling a `message` | `subscriberId`, `messageId` | Marks that message delivered; it is removed from the pending store and won't be redelivered. |

### 2.4 Frames — broker → client

| `type` | Sent in reply to | Meaning |
|---|---|---|
| `ack_registration` | `register_publisher`, `subscribe` | Confirms the registration/subscription; `message` holds a human-readable detail. |
| `publish_ack` | `publish` | Confirms the message was accepted onto the topic queue. Carries `messageId`, `topic`. |
| `ack_confirmed` | `ack` | Confirms the ack was processed. Carries `messageId`. |
| `message` | — (pushed asynchronously) | An actual published message, fanned out to every subscriber of its topic. Carries `messageId`, `publisherId`, `topic`, `payload`, `timestamp`. |
| `error` | Any invalid or rejected frame | `message` holds the rejection reason. The connection stays open — the client may retry. |

### 2.5 Example session

```
→ {"type":"register_publisher","publisherId":"pub-1","topic":"news"}
← {"type":"ack_registration","message":"Publisher 'pub-1' registered for topic 'news'"}

→ {"type":"subscribe","subscriberId":"sub-1","topic":"news"}          (on a second connection)
← {"type":"ack_registration","message":"Subscribed to topic 'news'"}

→ {"type":"publish","messageId":"m-1","publisherId":"pub-1","topic":"news","payload":"Hello","timestamp":"2026-09-16T14:00:05.000Z"}
← {"type":"publish_ack","messageId":"m-1","topic":"news"}

                                                                        (pushed to sub-1's connection)
← {"type":"message","messageId":"m-1","publisherId":"pub-1","topic":"news","payload":"Hello","timestamp":"2026-09-16T14:00:05.000Z"}

→ {"type":"ack","subscriberId":"sub-1","messageId":"m-1"}             (on sub-1's connection)
← {"type":"ack_confirmed","messageId":"m-1"}
```

(`→` client to broker, `←` broker to client; each line ends with `\n`.)

---

## 3. Behaviour and delivery guarantees

### 3.1 Topics and publishers

- A topic is created lazily, on the **first** `register_publisher` **or** `subscribe` for that
  name. Subscribers may therefore connect before the publisher; the publisher claims the
  topic when it registers.
- Exactly **one publisher per topic** is allowed (`Topic.trySetPublisher`, enforced
  atomically). A `register_publisher` from a *different* publisherId for an owned topic gets an `error`.
- Re-registering the **same** `publisherId` for the **same** topic is accepted (idempotent),
  so a publisher that reconnects after a network drop can register again and keep publishing.
- A publisher may only `publish` to the topic it registered for; anything else is
  rejected with an `error` and logged to the DLQ.

### 3.2 Fan-out

Each topic has its own `TopicQueueWorker` thread that pulls one published message at a
time from that topic's `BlockingQueue` and fans it out to every currently-subscribed
`subscriberId` (`SubscriptionRegistry`). Publishing to different topics is fully
parallel; within one topic, messages are delivered in publish order.

### 3.3 At-least-once delivery

For every subscriber, delivery always **persists first, then attempts a live send**
(`TopicQueueWorker.deliverTo`):

1. The message is added to that subscriber's pending store (`InMemoryMessageStore`),
   keyed by `messageId` so redelivery never duplicates an entry.
2. If the subscriber currently has a live connection, the broker tries to write to it.
3. The pending entry is removed **only** when an explicit `ack` for that `messageId`
   arrives — never just because the live send "succeeded" (TCP write success doesn't
   mean the peer processed it).

So a message is never lost because a subscriber was offline, and never considered
delivered without an ack. It can be delivered more than once (e.g. the ack itself is
lost) — consumers should treat `messageId` as a dedup key.

### 3.4 Reconnect and redelivery

`subscriberId` is meant to be **stable across reconnects** (chosen by the client, e.g. a
hostname or fixed string). On every `subscribe`, right after acking the subscription,
the broker replays every still-unacked message for that `subscriberId` **on that topic**, in
original order — this is how a subscriber that was offline catches up.

Delivery to one subscriber is strictly FIFO (`SubscriberDeliveryExecutor`: one
single-thread executor per `subscriberId`), so replay order and live order never
interleave incorrectly. Different subscribers are fully independent — a slow or
unreachable one never blocks delivery to the rest.

### 3.5 Validation and the Dead Letter Queue

Every incoming frame passes structural validation first (`MessageValidator`: required
fields only). Business-rule failures (unregistered publisher, topic doesn't exist,
duplicate registration, …) are checked next in the relevant handler. Either kind of
rejection:

- sends an `error` back to the client with the reason, and
- adds the **original raw JSON line** (not the parsed object — malformed JSON can't be
  parsed at all) to the in-memory `DeadLetterQueue`, together with the reason and a
  timestamp.

Nothing is ever silently dropped. The DLQ is currently in-memory only and has no
consumer/API yet; `DeadLetterQueue.getAll()`/`.size()` are there for that purpose.

---

## 4. Concurrency model

| Pool | Sizing | Purpose |
|---|---|---|
| Connection executor | Cached, one task per live connection | `BrokerServer.accept()` loop hands each socket to its own `ClientConnectionHandler` |
| Topic worker executor | Cached, one task per topic, started on first use | `TopicQueueWorker` — drains one topic's queue and fans out |
| Delivery executor | One single-thread executor per subscriber, created lazily | `SubscriberDeliveryExecutor` — keeps per-subscriber delivery ordered without blocking other subscribers |

There is no shared mutable state without a thread-safe collection behind it:
`ConcurrentHashMap` for the topic/publisher/connection registries, `CopyOnWriteArraySet`
for topic subscriber sets (reads far outnumber writes), a `synchronized` method for the
publisher-registration critical section, and a `BlockingQueue` per topic.

---

## 5. Project structure

```
Broker/
├── Dockerfile / docker-compose.yml
├── pom.xml
└── src/main/java/com/pad/broker/
    ├── Main.java                       composition root: wires everything, starts the server
    ├── model/
    │   ├── Message.java                the wire envelope (one record, every type)
    │   └── OperationResult.java        success/failure + reason, used by validators & registries
    ├── net/
    │   ├── BrokerServer.java           accept() loop → one ClientConnectionHandler per socket
    │   ├── ClientConnectionHandler.java NDJSON read loop, decode, dispatch; implements MessageSender
    │   ├── ClientSession.java          per-connection identity (publisherId/subscriberId once known)
    │   ├── JsonCodec.java              Jackson encode/decode + native⇄compact dialect translation
    │   ├── MessageSender.java          "write this message to this client" abstraction
    │   └── WireDialect.java            NATIVE (Publisher) / COMPACT (Receiver) envelope flavour
    ├── dispatch/
    │   ├── MessageDispatcher.java      routes a decoded Message to its MessageHandler by type
    │   └── MessageHandler.java         one implementation per type
    ├── handler/
    │   ├── RegisterPublisherHandler.java
    │   ├── PublishHandler.java
    │   ├── SubscribeHandler.java
    │   └── AckHandler.java
    ├── topic/
    │   ├── Topic.java                  name + owning publisher + its TopicQueue
    │   ├── TopicQueue.java             BlockingQueue of published messages awaiting fan-out
    │   ├── TopicQueueWorker.java       one thread per topic: take() → fan-out → persist + send
    │   ├── TopicRegistry.java          topic name → Topic, race-free creation
    │   ├── TopicWorkerLauncher.java    spins up a TopicQueueWorker when a topic is created
    │   └── PublisherRegistry.java      publisherId → topic, enforces 1 publisher per topic
    ├── subscription/
    │   ├── SubscriptionRegistry.java   topic → subscriberIds
    │   ├── SubscriberConnectionRegistry.java  subscriberId → live connection (absent = offline)
    │   ├── SubscriberDeliveryExecutor.java    one FIFO executor per subscriber
    │   ├── MessageStore.java / InMemoryMessageStore.java  pending-message storage per subscriber
    │   └── SubscriberQueue.java        one subscriber's pending messages, keyed by messageId
    ├── dlq/
    │   ├── DeadLetterQueue.java
    │   └── DeadLetterEntry.java
    ├── validation/
    │   └── MessageValidator.java       structural validation per type
    └── util/
        └── BrokerLogger.java           timestamped stdout logging
```

### Data flow

```
publisher ──TCP──► ClientConnectionHandler ──► MessageDispatcher ──► PublishHandler
                                                                          │
                                                                    Topic.getQueue().enqueue()
                                                                          │
                                                                 TopicQueueWorker (1/topic)
                                                                          │
                                                    persist (MessageStore) + live send, per subscriber
                                                                          │
                                                              SubscriberDeliveryExecutor (1/subscriber)
                                                                          │
subscriber ◄──TCP── ClientConnectionHandler ◄────────────────────────────┘
                │
                └── ack ──► MessageDispatcher ──► AckHandler ──► MessageStore.ack() (removes pending entry)
```

---

## 6. Compatibility with the Receiver (compact dialect)

The `Receiver` (C#) speaks a *compact* variant of the envelope: upper-case `type` values
(`SUBSCRIBE`/`ACK`/`MESSAGE`/`ERROR`), a single `clientId` field instead of
`publisherId`/`subscriberId`, and human-readable details in `payload` instead of `message`
(see `Lab1/Receiver/README.md`, §3). The broker accepts both dialects on the same port:

- `JsonCodec.decodeFrame` detects the compact dialect (upper-case `type`, or `clientId` without
  `publisherId`/`subscriberId`) and normalises it to the native `Message` — `type` is lower-cased,
  `clientId` fills `publisherId`/`subscriberId`, and an `ACK` without `messageId` takes the id from
  `payload`. Handlers only ever see the native shape.
- Each connection remembers the dialect it last spoke (`WireDialect`), and every reply on that
  connection is encoded in the same dialect:

| Native (internal) | Sent to a compact client as |
|---|---|
| `message` | `MESSAGE`, `clientId` = publisherId, plus `topic`, `payload`, `timestamp`, `messageId` |
| `ack_registration` | `ACK`, `payload` = detail, `topic` |
| `publish_ack` / `ack_confirmed` | `ACK`, `payload` = `messageId` = the id |
| `error` | `ERROR`, `payload` = reason |

Compact example session:

```
→ {"type":"SUBSCRIBE","topic":"news","clientId":"alice","payload":"","timestamp":"..."}
← {"type":"ACK","clientId":"broker","payload":"Subscribed to topic 'news'","topic":"news","timestamp":"..."}
← {"type":"MESSAGE","clientId":"pub-1","payload":"Hello","topic":"news","timestamp":"...","messageId":"m-1"}
→ {"type":"ACK","topic":"news","clientId":"alice","payload":"m-1","timestamp":"...","messageId":"m-1"}
← {"type":"ACK","clientId":"broker","payload":"m-1","timestamp":"...","messageId":"m-1"}
```
