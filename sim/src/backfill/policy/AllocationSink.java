package backfill.policy;

/**
 * Hand-off channel between a policy and the scheduler. A policy specifies HOW MANY units
 * from WHICH edge and queue bucket go out; the scheduler pops the units, accounts for them
 * and hands them to the edge device. Policies never touch device internals directly.
 */
public interface AllocationSink {

    /**
     * Grant up to {@code count} units from edge {@code edgeIndex}'s queue.
     * @param isNew true = pop from the new-data queue; false = pop from the accumulated backlog.
     * @throws IllegalStateException if {@code count} exceeds the units currently held by that
     *              queue (grants must be sized to what the view showed: other grants within the
     *              same tick already popped those units).
     */
    void grant(int edgeIndex, boolean isNew, int count);
}
