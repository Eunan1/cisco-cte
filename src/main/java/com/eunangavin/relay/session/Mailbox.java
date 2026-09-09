package com.eunangavin.relay.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * One client's messages, split by whether they have been sent yet.
 *
 * <pre>
 *   ┌──────────────────────────────┬──────────────────────────────────┐
 *   │ pending: ArrayDeque&lt;Message&gt; │ inflight: LinkedHashMap&lt;Id,Msg&gt;  │
 *   │  never sent                  │  sent, awaiting ACK              │
 *   └──────────────────────────────┴──────────────────────────────────┘
 *         ▲                    │                        │
 *         │  requeue on        │ takeForDelivery        │ ack
 *         │  disconnect        ▼                        ▼
 *         └──────────────── inflight ──────────────▶ removed
 * </pre>
 *
 * <h2>Why two structures</h2>
 * A mailbox must answer two different questions — what has not been sent, and what has been
 * sent but not confirmed. A single list with a cursor ("everything before index n is sent")
 * breaks the moment an ack removes an element from the middle, which is normal because acks
 * may arrive out of order.
 *
 * <p>{@link LinkedHashMap} gives both properties the inflight side needs at once: O(1)
 * lookup by id for the ack path, and insertion order for the requeue path. A plain
 * {@code HashMap} would lose the ordering that FIFO redelivery depends on.
 *
 * <p>{@link ArrayDeque} gives O(1) append at the tail for new messages and O(1) prepend at
 * the head for requeued ones. Choosing a deque now is what makes STORY-4's FIFO guarantee
 * nearly free rather than a restructure.
 *
 * <h2>Threading</h2>
 * <b>This class is not thread-safe and does not try to be.</b> Every method is called under
 * the owning {@link ClientSession}'s lock, which is also what makes the enqueue-then-pump
 * sequence atomic. Adding internal synchronisation here would give the illusion of safety
 * without the atomicity that actually matters.
 */
final class Mailbox {

    /** Why an enqueue was refused, or that it was accepted. */
    enum EnqueueResult {
        ACCEPTED,
        MAILBOX_FULL,
        DUPLICATE_ID
    }

    private final int maxMessages;
    private final ArrayDeque<Message> pending = new ArrayDeque<>();
    private final LinkedHashMap<String, Message> inflight = new LinkedHashMap<>();

    Mailbox(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    /**
     * Adds a message to the back of the queue.
     *
     * <p>The bound counts <b>pending + inflight</b>. Inflight has to count: it is retained
     * state, and a client that never acks would otherwise grow the mailbox without limit
     * while the pending count looked healthy.
     *
     * <p>Full means <b>reject the newest</b>, not evict the oldest. Evicting would silently
     * discard a message we already told a sender was {@code ACCEPTED}, breaking the one
     * promise that frame makes; rejecting tells the sender something they can act on.
     */
    EnqueueResult offer(Message message) {
        if (contains(message.id())) {
            return EnqueueResult.DUPLICATE_ID;
        }
        if (size() >= maxMessages) {
            return EnqueueResult.MAILBOX_FULL;
        }
        pending.addLast(message);
        return EnqueueResult.ACCEPTED;
    }

    /** Moves the head of pending into inflight and returns it, or null if nothing pending. */
    Message takeForDelivery() {
        Message message = pending.pollFirst();
        if (message != null) {
            inflight.put(message.id(), message);
        }
        return message;
    }

    /**
     * Puts a message back at the head, used when the recipient's outbound queue refused it.
     * The message was never actually delivered, so it must not stay inflight.
     */
    void returnToFront(Message message) {
        inflight.remove(message.id());
        pending.addFirst(message);
    }

    /**
     * Removes an acknowledged message.
     *
     * <p>Returns false for an id this mailbox never held — which covers both a repeated ack
     * and an ack for someone else's message. Both are treated identically and both are
     * no-ops, because at-least-once delivery <em>guarantees</em> clients will sometimes ack
     * twice: ack, connection drops before it lands, reconnect, redelivered, ack again.
     * Erroring would punish a client for behaviour our own guarantee forces on it.
     */
    boolean ack(String messageId) {
        return inflight.remove(messageId) != null;
    }

    /**
     * Returns everything delivered-but-unacknowledged to the <b>front</b> of pending, in its
     * original relative order. Called when the connection those messages were delivered to
     * goes away — which is requirement 7 of the brief, and the basis of FIFO in STORY-4.
     */
    void requeueInflight() {
        if (inflight.isEmpty()) {
            return;
        }
        List<Message> toRequeue = new ArrayList<>(inflight.values());   // insertion order
        inflight.clear();
        // Iterate in REVERSE. Walking forwards and calling addFirst each time would reverse
        // the batch - a bug that two messages would hide and three would expose.
        for (int i = toRequeue.size() - 1; i >= 0; i--) {
            pending.addFirst(toRequeue.get(i));
        }
    }

    int size() {
        return pending.size() + inflight.size();
    }

    int pendingCount() {
        return pending.size();
    }

    int inflightCount() {
        return inflight.size();
    }

    /**
     * Whether an id is live here, in either state.
     *
     * <p>A linear scan of pending. At the default bound of 1000 messages that is
     * inexpensive, and it avoids a third structure to keep in sync. A {@code HashSet} of
     * live ids would make it O(1) at the cost of another thing that can drift — a trade
     * worth naming rather than making silently.
     *
     * <p>Note this only knows about <em>live</em> ids. Once a message is acked and evicted,
     * the same id would be accepted again as a new message. Unbounded dedupe history is a
     * memory leak, so that gap is deliberate; a bounded LRU of recently-acked ids is the
     * documented next step.
     */
    private boolean contains(String id) {
        if (inflight.containsKey(id)) {
            return true;
        }
        for (Message message : pending) {
            if (message.id().equals(id)) {
                return true;
            }
        }
        return false;
    }
}
