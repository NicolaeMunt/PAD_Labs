# Lab 1 — Publish/Subscribe Message Broker

Three independent programs that talk over TCP using newline-delimited JSON (NDJSON):

| Module | Language | Role | Folder |
|---|---|---|---|
| **Broker** | Java 17 | Accepts publishers and subscribers, routes messages per topic, stores unacked messages per subscriber and replays them on reconnect | [`Broker/`](Broker/README.md) |
| **Publisher** | Node.js + TypeScript | Interactive: every line you type is published to one topic | [`Publisher/`](Publisher/README.md) |
| **Receiver** | C# / .NET 8 | Subscribes to one or more topics, prints every message and ACKs it | [`Receiver/`](Receiver/README.md) |

```
Publisher ──publish──►  Broker  ──MESSAGE──► Receiver
          ◄─publish_ack─        ◄───ACK─────
```

The broker listens on port **5000**. Start it first; the Publisher and Receiver can then be
started in any order. Both clients reconnect automatically if the broker restarts.

There are two ways to run the system:

- **Option A: Docker.** You only need Docker Desktop.
- **Option B: native.** You need JDK 17+ with Maven, Node.js 18+, and the .NET 8 SDK.

---

## Option A: Docker (recommended)

Run every command from this `Lab1/` folder. Make sure Docker Desktop is running.

**1. Build all three images** (only needed once, and again after code changes):

```bash
docker compose --profile clients build
```

**2. Start the broker** (terminal 1):

```bash
docker compose up broker
```

**3. Start one or more receivers** (one terminal each):

```bash
docker compose run --rm receiver -t news,sports -c alice
docker compose run --rm receiver -t news -c bob
```

**4. Start one or more publishers** (one terminal each, one topic per publisher):

```bash
docker compose run --rm publisher publisher-news news broker
docker compose run --rm publisher publisher-sports sports broker
```

Publisher arguments: `<publisherId> <topic> [host] [port]`. Inside Docker the broker's host
name is `broker`. Type a line and press Enter to publish it. Type `/quit` to exit.

**5. Stop everything:** press Ctrl+C in each terminal, then run `docker compose down`.

> The broker's port 5000 is also published on the host. This means you can mix modes, for
> example a broker in Docker with a natively-run Publisher or Receiver on `127.0.0.1:5000`.

---

## Option B: native build

**1. Build everything** with the script, which skips any module whose toolchain is missing:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1     # Windows
```
```bash
sh build.sh                                             # Linux / macOS / Git Bash
```

This produces:

| Module | Artifact |
|---|---|
| Broker | `Broker/target/Broker-1.0-SNAPSHOT.jar` |
| Publisher | `Publisher/dist/index.js` |
| Receiver | `Receiver/out/Receiver.dll` |

**2. Start the broker** (terminal 1):

```bash
java -jar Broker/target/Broker-1.0-SNAPSHOT.jar          # port 5000; pass a number to change it
```

**3. Start one or more receivers** (one terminal each):

```bash
dotnet Receiver/out/Receiver.dll -t news,sports -c alice
dotnet Receiver/out/Receiver.dll -t news -c bob
```

Receiver options: `-h host` (default `127.0.0.1`), `-p port` (default `5000`), `-t topics`
(repeat or comma-separate), `-c clientId` (stable id used for replay), `-w workers`,
`-l logfile`, `--verbose`. Run `--help` for the full list.

**4. Start one or more publishers** (one terminal each):

```bash
node Publisher/dist/index.js publisher-news news                  # defaults to 127.0.0.1:5000
node Publisher/dist/index.js publisher-sports sports 127.0.0.1 5000
```

Without the build step, you can also run each module from source: `mvn package` in `Broker/`,
`npm install && npm run dev -- <publisherId> <topic>` in `Publisher/`, and
`dotnet run -- -t news -c alice` in `Receiver/src/`.

---

## What to try (feature walkthrough)

Start the broker. Then start receiver `alice` on `news,sports` and publisher `publisher-news` on
`news`, as shown above.

| # | Feature | How to see it |
|---|---|---|
| 1 | **Basic routing** | Type `hello` in the publisher. It prints `Published <id> to 'news'`, and alice prints `[time] [news] publisher-news #<id>: hello`. |
| 2 | **Topic isolation** | Start `publisher-sports` on `sports` and a receiver `bob` on `news` only. Sports messages reach alice but not bob. |
| 3 | **Fan-out** | With alice and bob both on `news`, each message from `publisher-news` is printed by both. |
| 4 | **Subscribe before publish** | Start a receiver on a topic that has no publisher yet, e.g. `-t weather -c carol`, then start a `weather` publisher. Carol receives its messages. |
| 5 | **Offline storage + replay (at-least-once)** | Stop alice with Ctrl+C. Publish a few `news` messages, then restart alice with the **same** `-c alice`. The missed messages are printed right after she reconnects. The broker log shows `Replaying pending messages`. |
| 6 | **ACKs** | The broker log shows `Ack received - subscriberId=alice messageId=...` for every delivered message. Acked messages are never replayed again. |
| 7 | **One publisher per topic** | Start a second publisher with a different id on `news`: `publisher-x news`. The broker rejects it with `Topic 'news' already has a registered publisher`. |
| 8 | **Publisher reconnect** | Stop the broker and start it again, or stop and restart a publisher with the same id. The publisher reconnects, re-registers, and flushes the messages it queued meanwhile. |
| 9 | **Validation + Dead Letter Queue** | Every rejected frame is logged by the broker as `[DLQ] Message rejected - reason=...`. To send raw frames yourself, use a TCP client such as `ncat 127.0.0.1 5000`, e.g. `{"type":"publish","publisherId":"ghost"}` or `not json`. |
| 10 | **Receiver deduplication** | If the broker redelivers a message the receiver has already handled, the receiver skips it and re-ACKs it. Run with `--verbose` to see `Duplicate ... skipped`. |

> The broker keeps all state **in memory**. Restarting the broker clears topics,
> registrations and pending messages.

---

## How the three modules fit together

The Publisher and Receiver were written against two slightly different envelope formats:

- The **Publisher** uses the broker's native format: `register_publisher`/`publish`,
  `publisherId`, and details in `message`.
- The **Receiver** uses a compact format: `SUBSCRIBE`/`ACK`/`MESSAGE`/`ERROR`, `clientId`, and
  details in `payload`.

The broker accepts **both** formats on the same port. For each connection it detects which
format the client uses and replies in that format. Neither client needs any changes. The
mapping is documented in [Broker README §6](Broker/README.md#6-compatibility-with-the-receiver-compact-dialect).

For details on each module, see its own README:

- [Broker/README.md](Broker/README.md): wire protocol, delivery guarantees, concurrency model
- [Publisher/README.md](Publisher/README.md)
- [Receiver/README.md](Receiver/README.md): options, robustness, design rationale
