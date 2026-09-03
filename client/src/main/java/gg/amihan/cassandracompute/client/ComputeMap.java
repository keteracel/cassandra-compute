package gg.amihan.cassandracompute.client;

import java.nio.ByteBuffer;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;

/**
 * Client-side handle to a single distributed map — a Cassandra table with schema
 * {@code (key text PRIMARY KEY, value blob)} — plus the ability to submit an {@code EntryProcessor} against a key
 * in that table via the {@code CALL ENTRYPROCESSOR(...)} entry point (see {@code specs/technical.md}).
 * <p>
 * Values are handled as raw {@link ByteBuffer}s: v1 deliberately does not impose a serialization framework on
 * callers, matching the product spec's scope (a bare put/get/remove abstraction, not a general query engine).
 * <p>
 * {@link #submit} builds and sends the {@code CALL ENTRYPROCESSOR(...)} statement directly via
 * {@link SimpleStatement} rather than a {@link PreparedStatement}: the server's {@code EntryProcessorCallStatement}
 * is hand-parsed by regex and explicitly does not support {@code PREPARE} (see the technical spec's v1 scope
 * limits) or bind markers, so every argument is inlined as an escaped CQL string literal.
 */
public final class ComputeMap
{
    private final CqlSession session;
    private final String keyspace;
    private final String table;

    private final String quotedTable;

    // Prepared lazily rather than in the constructor: ensureSchema() creates the backing table, and callers are
    // expected to construct a ComputeMap before calling it (see ensureSchema's javadoc), so preparing eagerly
    // would fail against a table that doesn't exist yet.
    private volatile PreparedStatement putStatement;
    private volatile PreparedStatement getStatement;
    private volatile PreparedStatement removeStatement;

    public ComputeMap(CqlSession session, String keyspace, String table)
    {
        this.session = session;
        this.keyspace = keyspace;
        this.table = table;
        this.quotedTable = quoteIdentifier(keyspace) + "." + quoteIdentifier(table);
    }

    /**
     * Creates this map's backing table if it doesn't already exist. The keyspace itself is assumed to already
     * exist — its replication strategy is a cluster-topology decision this library deliberately doesn't make on a
     * caller's behalf.
     */
    public void ensureSchema()
    {
        session.execute("CREATE TABLE IF NOT EXISTS " + quotedTable + " (key text PRIMARY KEY, value blob)");
    }

    public void put(String key, ByteBuffer value)
    {
        session.execute(putStatement().bind(key, value));
    }

    /**
     * @return the current value for {@code key}, or {@code null} if the entry doesn't exist.
     */
    public ByteBuffer get(String key)
    {
        Row row = session.execute(getStatement().bind(key)).one();
        return row == null ? null : row.getByteBuffer("value");
    }

    public void remove(String key)
    {
        session.execute(removeStatement().bind(key));
    }

    private PreparedStatement putStatement()
    {
        PreparedStatement s = putStatement;
        return s != null ? s : (putStatement = session.prepare("INSERT INTO " + quotedTable + " (key, value) VALUES (?, ?)"));
    }

    private PreparedStatement getStatement()
    {
        PreparedStatement s = getStatement;
        return s != null ? s : (getStatement = session.prepare("SELECT value FROM " + quotedTable + " WHERE key = ?"));
    }

    private PreparedStatement removeStatement()
    {
        PreparedStatement s = removeStatement;
        return s != null ? s : (removeStatement = session.prepare("DELETE FROM " + quotedTable + " WHERE key = ?"));
    }

    /**
     * Submits {@code processorClassName} for execution against {@code key}, at {@link ConsistencyLevel#ONE}. The
     * processor class must already be deployed on every Cassandra node's classpath — see the product spec's
     * "processor identity is a class reference" decision; this library never ships processor code over the wire.
     *
     * @return the processor's result, exactly as returned by {@code EntryProcessorContext}'s delta-producing call —
     * this library has no way to know the processor's actual result type, so the caller decodes the bytes.
     * @throws EntryProcessorFailedException      if the processor itself threw at the primary owner.
     * @throws EntryProcessorWriteFailedException if the processor's delta failed to replicate at the requested CL.
     */
    public ByteBuffer submit(String key, String processorClassName)
    {
        return submit(key, processorClassName, ConsistencyLevel.ONE);
    }

    public ByteBuffer submit(String key, String processorClassName, ConsistencyLevel consistencyLevel)
    {
        String cql = buildCallStatement(keyspace, table, key, processorClassName, consistencyLevel);
        try
        {
            Row row = session.execute(cql).one();
            return row.getByteBuffer("result");
        }
        catch (InvalidQueryException e)
        {
            EntryProcessorCallException translated = translate(e);
            if (translated != null)
                throw translated;
            throw e;
        }
    }

    /**
     * The server reports both processor failures and write failures as a plain {@code InvalidRequestException}
     * (see {@code EntryProcessorCallStatement.doExecute}), distinguished only by a message prefix — there's no
     * separate driver-visible exception type for each, so translation here is necessarily string-based. Returns
     * {@code null} for any other {@link InvalidQueryException} (e.g. a genuinely malformed call), which the caller
     * rethrows unchanged.
     */
    static EntryProcessorCallException translate(InvalidQueryException e)
    {
        String message = e.getMessage();
        if (message != null && message.startsWith("EntryProcessor failed: "))
            return new EntryProcessorFailedException(message.substring("EntryProcessor failed: ".length()), e);
        if (message != null && message.startsWith("Write failed: "))
            return new EntryProcessorWriteFailedException(message.substring("Write failed: ".length()), e);
        return null;
    }

    /**
     * Package-private (not {@code private}) purely so {@code ComputeMapTest} can assert on the exact wire syntax
     * without standing up a live cluster — the server side parses this with a fixed regex (see
     * {@code EntryProcessorCallStatement}), so getting quoting/escaping exactly right here matters.
     */
    static String buildCallStatement(String keyspace, String table, String key, String processorClassName,
                                      ConsistencyLevel consistencyLevel)
    {
        return "CALL ENTRYPROCESSOR('" + escape(keyspace) + "', '" + escape(table) + "', '"
               + escape(key) + "', '" + escape(processorClassName) + "', '"
               + escape(consistencyLevel.name()) + "')";
    }

    static String escape(String s)
    {
        return s.replace("'", "''");
    }

    static String quoteIdentifier(String identifier)
    {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
