package gg.amihan.cassandracompute.client;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComputeMapTest
{
    @Test
    void buildsCallStatementWithQuotedArguments()
    {
        String cql = ComputeMap.buildCallStatement("ks", "tbl", "mykey", "com.example.Processor", ConsistencyLevel.QUORUM);
        assertEquals("CALL ENTRYPROCESSOR('ks', 'tbl', 'mykey', 'com.example.Processor', 'QUORUM')", cql);
    }

    @Test
    void escapesSingleQuotesInArguments()
    {
        // Matches EntryProcessorCallStatement's own unescape(): "''" collapses to a literal "'".
        String cql = ComputeMap.buildCallStatement("ks", "tbl", "o'brien", "com.example.Processor", ConsistencyLevel.ONE);
        assertEquals("CALL ENTRYPROCESSOR('ks', 'tbl', 'o''brien', 'com.example.Processor', 'ONE')", cql);
    }

    @Test
    void quotesIdentifiersAndEscapesEmbeddedQuotes()
    {
        assertEquals("\"my_table\"", ComputeMap.quoteIdentifier("my_table"));
        assertEquals("\"weird\"\"name\"", ComputeMap.quoteIdentifier("weird\"name"));
    }

    @Test
    void translatesProcessorFailureMessage()
    {
        InvalidQueryException e = new InvalidQueryException(null, "EntryProcessor failed: boom");
        EntryProcessorCallException translated = ComputeMap.translate(e);
        assertInstanceOf(EntryProcessorFailedException.class, translated);
        assertEquals("boom", translated.getMessage());
    }

    @Test
    void translatesWriteFailureMessage()
    {
        InvalidQueryException e = new InvalidQueryException(null, "Write failed: timed out");
        EntryProcessorCallException translated = ComputeMap.translate(e);
        assertInstanceOf(EntryProcessorWriteFailedException.class, translated);
        assertEquals("timed out", translated.getMessage());
    }

    @Test
    void leavesUnrecognizedInvalidQueryUntranslated()
    {
        InvalidQueryException e = new InvalidQueryException(null, "Unknown table: ks.tbl");
        assertNull(ComputeMap.translate(e));
    }
}
