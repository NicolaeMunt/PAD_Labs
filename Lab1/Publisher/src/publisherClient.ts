import { EventEmitter } from "node:events";
import { Socket } from "node:net";
import { randomUUID } from "node:crypto";

export type ConnectionState = "connecting" | "registering" | "registered" | "rejected" | "disconnected" | "stopped";
export type MessageStatus = "queued" | "sent" | "acked" | "rejected";

export type ConnectionStatus = {
  state: ConnectionState;
  detail?: string;
  retryInMs?: number;
};

export type TrackedMessage = {
  messageId: string;
  payload: string;
  timestamp: string;
  status: MessageStatus;
  reason?: string;
  /** A hand-crafted frame (e.g. an intentionally invalid one), not a regular publish. */
  raw?: boolean;
};

type BrokerResponse = {
  type?: string;
  messageId?: string;
  topic?: string;
  message?: string;
};

type OutboundFrame =
  | { kind: "register" }
  | { kind: "message"; message: TrackedMessage; line: string };

/**
 * One publisher connection: registers `publisherId` for `topic`, publishes messages, and
 * reconnects with exponential backoff (1–30 s). Messages published while not registered are
 * queued and flushed after registration.
 *
 * The broker answers every frame on a connection exactly once and in order, so replies are
 * matched to requests with a FIFO of frames awaiting an answer. Messages that were sent but not
 * confirmed when the connection drops are re-queued and resent (same messageId) after reconnecting.
 *
 * Events: `status` (ConnectionStatus) and `message` (TrackedMessage, on every status change).
 */
export class PublisherClient extends EventEmitter {
  private socket: Socket | undefined;
  private registered = false;
  private stopped = false;
  private inputBuffer = "";
  private reconnectDelayMs = 1_000;
  private reconnectTimer: NodeJS.Timeout | undefined;
  private lastError: string | undefined;
  private readonly outbox: Extract<OutboundFrame, { kind: "message" }>[] = [];
  private awaitingReply: OutboundFrame[] = [];
  private currentStatus: ConnectionStatus = { state: "connecting" };

  constructor(
    readonly publisherId: string,
    readonly topic: string,
    readonly host: string,
    readonly port: number
  ) {
    super();
  }

  get status(): ConnectionStatus {
    return this.currentStatus;
  }

  get isRegistered(): boolean {
    return this.registered;
  }

  start(): void {
    this.connect();
  }

  publish(payload: string): TrackedMessage {
    const message: TrackedMessage = {
      messageId: randomUUID(),
      payload,
      timestamp: new Date().toISOString(),
      status: "queued"
    };
    const line = JSON.stringify({
      type: "publish",
      messageId: message.messageId,
      publisherId: this.publisherId,
      topic: this.topic,
      payload,
      timestamp: message.timestamp
    });
    this.outbox.push({ kind: "message", message, line });
    this.emit("message", message);
    this.flush();
    return message;
  }

  /** Writes a frame as-is, bypassing registration, so the broker's validation can be demonstrated. */
  sendRaw(line: string, label: string): TrackedMessage {
    const message: TrackedMessage = {
      messageId: `raw-${randomUUID().slice(0, 8)}`,
      payload: label,
      timestamp: new Date().toISOString(),
      status: "queued",
      raw: true
    };
    if (this.write(line.replace(/\r?\n/g, " "))) {
      message.status = "sent";
      this.awaitingReply.push({ kind: "message", message, line });
    } else {
      message.status = "rejected";
      message.reason = "Not connected to the broker";
    }
    this.emit("message", message);
    return message;
  }

  /** Destroys the socket as if the network failed; the normal reconnect logic takes over. */
  dropConnection(): void {
    if (!this.socket) return;
    this.lastError = "Connection dropped manually";
    this.socket.destroy();
  }

  stop(): void {
    this.stopped = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    this.socket?.end();
    this.setStatus({ state: "stopped" });
  }

  private connect(): void {
    if (this.stopped) return;
    this.registered = false;
    this.inputBuffer = "";
    this.lastError = undefined;
    const socket = new Socket();
    this.socket = socket;
    socket.setEncoding("utf8");
    this.setStatus({ state: "connecting", detail: `${this.host}:${this.port}` });

    socket.on("connect", () => {
      this.reconnectDelayMs = 1_000;
      this.setStatus({ state: "registering", detail: `Registering '${this.publisherId}' for '${this.topic}'` });
      this.write(JSON.stringify({ type: "register_publisher", publisherId: this.publisherId, topic: this.topic }));
      this.awaitingReply.push({ kind: "register" });
    });

    socket.on("data", (chunk: string) => {
      this.inputBuffer += chunk;
      let newline: number;
      while ((newline = this.inputBuffer.indexOf("\n")) !== -1) {
        const line = this.inputBuffer.slice(0, newline).trim();
        this.inputBuffer = this.inputBuffer.slice(newline + 1);
        if (line) this.handleResponse(line);
      }
    });

    socket.on("error", (error) => {
      this.lastError = error.message;
    });

    socket.on("close", () => {
      if (this.socket === socket) this.socket = undefined;
      this.registered = false;
      this.requeueUnconfirmed();
      if (!this.stopped) this.scheduleReconnect();
    });

    socket.connect(this.port, this.host);
  }

  private handleResponse(line: string): void {
    let response: BrokerResponse;
    try {
      response = JSON.parse(line) as BrokerResponse;
    } catch {
      this.emit("notice", `Broker sent invalid JSON: ${line}`);
      return;
    }

    const request = this.awaitingReply.shift();
    switch (response.type) {
      case "ack_registration":
        this.registered = true;
        this.setStatus({ state: "registered", detail: response.message ?? "Publisher registration accepted." });
        this.flush();
        break;
      case "publish_ack":
        if (request?.kind === "message") this.updateMessage(request.message, "acked");
        break;
      case "error":
        if (request?.kind === "register") {
          this.setStatus({ state: "rejected", detail: response.message ?? "Registration rejected" });
        } else if (request?.kind === "message") {
          this.updateMessage(request.message, "rejected", response.message ?? "unknown error");
        } else {
          this.emit("notice", `Broker error: ${response.message ?? "unknown error"}`);
        }
        break;
      default:
        this.emit("notice", `Broker response: ${line}`);
    }
  }

  private flush(): void {
    if (!this.registered) return;
    while (this.outbox.length > 0) {
      const entry = this.outbox[0];
      if (!this.write(entry.line)) return;
      this.outbox.shift();
      this.awaitingReply.push(entry);
      this.updateMessage(entry.message, "sent");
    }
  }

  /** Sent-but-unconfirmed publishes go back to the front of the outbox, in their original order. */
  private requeueUnconfirmed(): void {
    const unconfirmed = this.awaitingReply.filter(
      (frame): frame is Extract<OutboundFrame, { kind: "message" }> => frame.kind === "message"
    );
    this.awaitingReply = [];
    const resend = unconfirmed.filter((frame) => !frame.message.raw);
    for (const frame of unconfirmed) {
      if (frame.message.raw) this.updateMessage(frame.message, "rejected", "Connection closed before the broker replied");
    }
    this.outbox.unshift(...resend);
    for (const frame of resend) this.updateMessage(frame.message, "queued");
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) return;
    const delay = this.reconnectDelayMs;
    this.reconnectDelayMs = Math.min(this.reconnectDelayMs * 2, 30_000);
    this.setStatus({ state: "disconnected", detail: this.lastError ?? "Connection closed", retryInMs: delay });
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.connect();
    }, delay);
  }

  /** Returns false when there is no open socket. A `false` from socket.write only means buffered, not failed. */
  private write(line: string): boolean {
    if (!this.socket || this.socket.destroyed || this.socket.connecting) return false;
    this.socket.write(`${line}\n`);
    return true;
  }

  private updateMessage(message: TrackedMessage, status: MessageStatus, reason?: string): void {
    message.status = status;
    message.reason = reason;
    this.emit("message", message);
  }

  private setStatus(status: ConnectionStatus): void {
    this.currentStatus = status;
    this.emit("status", status);
  }
}
