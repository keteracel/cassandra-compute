package gg.amihan.cassandracompute.client;

/**
 * Base type for a failure reported by the server-side {@code CALL ENTRYPROCESSOR(...)} entry point, as opposed to
 * a routing/network failure surfaced directly by the driver (e.g. {@code NoNodeAvailableException}). See
 * {@link EntryProcessorFailedException} and {@link EntryProcessorWriteFailedException} for the two cases the
 * server distinguishes — this split mirrors the product spec's acceptance criterion that a processor exception and
 * a write-path failure must be distinguishable in the API, not just both surfacing as "the call failed."
 */
public abstract class EntryProcessorCallException extends RuntimeException
{
    EntryProcessorCallException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
