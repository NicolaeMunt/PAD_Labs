import { createInterface } from "node:readline";
import { Socket } from "node:net";
import { randomUUID } from "node:crypto";

type BrokerResponse = {
  type?: string;
  messageId?: string;
  topic?: string;
  message?: string;
};

type PublisherMessage = {
  type: "publish";
  messageId: string;
  publisherId: string;
  topic: string;
  payload: string;
  timestamp: string;
};

const [publisherId, topic, host = "127.0.0.1", portArgument = "5000"] = process.argv.slice(2);
const port = Number(portArgument);

if (!publisherId || !topic || !Number.isInteger(port) || port < 1 || port > 65535) {
  console.error("Usage: npm run dev -- <publisherId> <topic> [host] [port]");
  process.exit(1);
}

let socket: Socket | undefined;
let registered = false;
let reconnectDelayMs = 1_000;
let reconnectTimer: NodeJS.Timeout | undefined;
let stopping = false;
let inputBuffer = "";
const queuedMessages: PublisherMessage[] = [];

function send(message: object): boolean {
  if (!socket || socket.destroyed) return false;
  return socket.write(`${JSON.stringify(message)}\n`);
}

function register(): void {
  send({ type: "register_publisher", publisherId, topic });
}

function flushQueue(): void {
  if (!registered) return;
  while (queuedMessages.length > 0) {
    const message = queuedMessages.shift();
    if (message && !send(message)) {
      queuedMessages.unshift(message);
      return;
    }
  }
}

function connect(): void {
  if (stopping) return;
  registered = false;
  const nextSocket = new Socket();
  socket = nextSocket;
  nextSocket.setEncoding("utf8");

  nextSocket.on("connect", () => {
    console.log(`Connected to broker at ${host}:${port}; registering '${publisherId}' for '${topic}'.`);
    reconnectDelayMs = 1_000;
    register();
  });

  nextSocket.on("data", (chunk: string) => {
    inputBuffer += chunk;
    let newline: number;
    while ((newline = inputBuffer.indexOf("\n")) !== -1) {
      const line = inputBuffer.slice(0, newline).trim();
      inputBuffer = inputBuffer.slice(newline + 1);
      if (line) handleResponse(line);
    }
  });

  nextSocket.on("error", (error) => {
    console.error(`Broker connection error: ${error.message}`);
  });

  nextSocket.on("close", () => {
    if (socket === nextSocket) socket = undefined;
    registered = false;
    if (!stopping) scheduleReconnect();
  });

  nextSocket.connect(port, host);
}

function handleResponse(line: string): void {
  let response: BrokerResponse;
  try {
    response = JSON.parse(line) as BrokerResponse;
  } catch {
    console.error(`Broker sent invalid JSON: ${line}`);
    return;
  }

  switch (response.type) {
    case "ack_registration":
      registered = true;
      console.log(response.message ?? "Publisher registration accepted.");
      flushQueue();
      break;
    case "publish_ack":
      console.log(`Published ${response.messageId ?? "message"} to '${response.topic ?? topic}'.`);
      break;
    case "error":
      console.error(`Broker rejected request: ${response.message ?? "unknown error"}`);
      break;
    default:
      console.log(`Broker response: ${line}`);
  }
}

function scheduleReconnect(): void {
  if (reconnectTimer) return;
  const delay = reconnectDelayMs;
  reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30_000);
  console.log(`Disconnected. Reconnecting in ${delay / 1_000}s...`);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = undefined;
    connect();
  }, delay);
}

function queuePayload(payload: string): void {
  const message: PublisherMessage = {
    type: "publish",
    messageId: randomUUID(),
    publisherId,
    topic,
    payload,
    timestamp: new Date().toISOString()
  };
  queuedMessages.push(message);
  flushQueue();
  if (!registered) console.log("Message queued until publisher registration succeeds.");
}

const readline = createInterface({ input: process.stdin, output: process.stdout, prompt: "> " });
readline.on("line", (line) => {
  if (line.trim() === "/quit") {
    stopping = true;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    socket?.end();
    readline.close();
    return;
  }
  if (line.trim()) queuePayload(line);
  readline.prompt();
});
readline.on("close", () => process.exit(0));

process.on("SIGINT", () => readline.close());
console.log("Type a message and press Enter. Type /quit to exit.");
readline.prompt();
connect();
