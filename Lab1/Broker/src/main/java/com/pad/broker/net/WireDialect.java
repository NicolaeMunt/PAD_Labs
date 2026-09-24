package com.pad.broker.net;

/**
 * The two envelope flavours the broker understands. Each connection is answered in the
 * dialect it spoke last, so both kinds of clients can share the same broker.
 *
 * <ul>
 *   <li>{@link #NATIVE} — lower-case types, separate {@code publisherId}/{@code subscriberId},
 *       details in {@code message} (used by the Publisher).</li>
 *   <li>{@link #COMPACT} — upper-case types ({@code SUBSCRIBE}/{@code ACK}/{@code MESSAGE}/{@code ERROR}),
 *       a single {@code clientId}, details in {@code payload} (used by the Receiver).</li>
 * </ul>
 */
public enum WireDialect {
    NATIVE,
    COMPACT
}
