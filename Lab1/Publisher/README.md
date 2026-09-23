# Publisher (Node.js + TypeScript)

This program is an interactive publisher for the project broker. It creates one TCP connection, registers a fixed `publisherId` and `topic`, and publishes each line typed in the terminal as an NDJSON `publish` message.

It generates a UUID `messageId` and ISO-8601 timestamp for every message, displays the broker's registration/publication confirmations and errors, and reconnects automatically with exponential backoff (1–30 seconds). Messages entered while it is disconnected are held in memory and sent after a successful registration.

## Commands

From this `Publisher` directory:

```bash
npm install
npm run dev -- publisher-fotbal fotbal
```

The optional broker host and port follow the topic:

```bash
npm run dev -- publisher-fotbal fotbal 127.0.0.1 5000
```

For a compiled run:

```bash
npm run build
npm start -- publisher-fotbal fotbal 127.0.0.1 5000
```

Type any non-empty line to publish it. Type `/quit` (or press `Ctrl+C`) to close the publisher.

The broker must already be running. By default, this publisher uses `127.0.0.1:5000`.
