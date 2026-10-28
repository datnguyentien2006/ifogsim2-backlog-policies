package backfill.policy;

/**
 * The state snapshot one policy sees per decision tick. For each edge server:
 * connectivity, the size of both queues, and the generation time of the head unit
 * (null when the queue is empty). Policies may only make decisions from this view
 * plus their own configuration; execution goes exclusively through the
 * {@link AllocationSink} handed to {@link SchedulingPolicy#allocate}.
 */
public interface EdgeQueueView {

    int edgeIndex();

    /** true = the edge's link to the center is up at this tick. Disconnected edges must never be granted. */
    boolean connected();

    /** Units stored that were generated while connected ("new data"). */
    int newCount();

    /** Units accumulated while the link was down ("accumulated data" / backfill backlog). */
    int backCount();

    /** Generation time of the head new-data unit, or null if the queue is empty. */
    Double newHeadGenTime();

    /** Generation time of the head accumulated-data unit, or null if the queue is empty. */
    Double backHeadGenTime();

    default boolean hasAny() { return newCount() + backCount() > 0; }
}
