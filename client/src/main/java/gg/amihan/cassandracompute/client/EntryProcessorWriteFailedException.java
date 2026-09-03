package gg.amihan.cassandracompute.client;

/**
 * The {@code EntryProcessor} ran and produced a delta, but {@code StorageProxy.mutate(...)} failed to replicate it
 * at the requested consistency level (timeout, unavailable, overloaded, or the per-key lock itself timed out).
 * Corresponds to the server's {@code EntryProcessorResponse.Status.WRITE_FAILURE}.
 */
public final class EntryProcessorWriteFailedException extends EntryProcessorCallException
{
    EntryProcessorWriteFailedException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
