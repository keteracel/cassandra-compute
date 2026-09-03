package gg.amihan.cassandracompute.client;

/**
 * The submitted {@code EntryProcessor} itself threw, at the primary owner — as opposed to the write path failing
 * to replicate its delta (see {@link EntryProcessorWriteFailedException}). Corresponds to the server's
 * {@code EntryProcessorResponse.Status.PROCESSOR_FAILURE}.
 */
public final class EntryProcessorFailedException extends EntryProcessorCallException
{
    EntryProcessorFailedException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
