package gg.amihan.cassandracompute.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void escapesSingleQuotesInKeyspaceArgument()
    {
        // escape() is applied identically to every argument - this and the next two tests pin that down for the
        // three arguments escapesSingleQuotesInArguments() doesn't cover (it only exercises "key").
        String cql = ComputeMap.buildCallStatement("weird'ks", "tbl", "mykey", "com.example.Processor", ConsistencyLevel.ONE);
        assertEquals("CALL ENTRYPROCESSOR('weird''ks', 'tbl', 'mykey', 'com.example.Processor', 'ONE')", cql);
    }

    @Test
    void escapesSingleQuotesInTableArgument()
    {
        String cql = ComputeMap.buildCallStatement("ks", "weird'tbl", "mykey", "com.example.Processor", ConsistencyLevel.ONE);
        assertEquals("CALL ENTRYPROCESSOR('ks', 'weird''tbl', 'mykey', 'com.example.Processor', 'ONE')", cql);
    }

    @Test
    void escapesSingleQuotesInProcessorClassNameArgument()
    {
        String cql = ComputeMap.buildCallStatement("ks", "tbl", "mykey", "com.example.Weird'Processor", ConsistencyLevel.ONE);
        assertEquals("CALL ENTRYPROCESSOR('ks', 'tbl', 'mykey', 'com.example.Weird''Processor', 'ONE')", cql);
    }

    /**
     * Mirrors {@code EntryProcessorCallStatement.PATTERN} from the server (vendor/cassandra) to confirm a
     * quote-containing argument in *any* position still parses back out correctly, not just that the raw escaped
     * string looks right — a positional bug (e.g. an argument escaped into the wrong slot) could pass the other
     * escaping tests here while still breaking server-side parsing.
     */
    @Test
    void quoteContainingArgumentsRoundTripThroughTheServersParsePattern()
    {
        Pattern serverPattern = Pattern.compile(
            "(?is)^\\s*CALL\\s+ENTRYPROCESSOR\\s*\\(\\s*"
            + "'((?:[^']|'')*)'\\s*,\\s*"
            + "'((?:[^']|'')*)'\\s*,\\s*"
            + "'((?:[^']|'')*)'\\s*,\\s*"
            + "'((?:[^']|'')*)'\\s*"
            + "(?:,\\s*'((?:[^']|'')*)'\\s*)?"
            + "\\)\\s*;?\\s*$");

        String cql = ComputeMap.buildCallStatement("we'ird ks", "we'ird tbl", "we'ird key", "we'ird.Processor", ConsistencyLevel.QUORUM);
        Matcher m = serverPattern.matcher(cql);

        assertTrue(m.matches());
        assertEquals("we'ird ks", m.group(1).replace("''", "'"));
        assertEquals("we'ird tbl", m.group(2).replace("''", "'"));
        assertEquals("we'ird key", m.group(3).replace("''", "'"));
        assertEquals("we'ird.Processor", m.group(4).replace("''", "'"));
        assertEquals("QUORUM", m.group(5));
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
