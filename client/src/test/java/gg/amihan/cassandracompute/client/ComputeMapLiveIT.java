package gg.amihan.cassandracompute.client;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Live end-to-end coverage against a real, single-node embedded Cassandra 5.0.6 process built from
 * {@code vendor/cassandra} with {@code -Dcassandra.custom_query_handler_class=org.apache.cassandra.compute.ComputeQueryHandler}
 * and {@code UppercaseValueProcessor}/{@code ThrowingProcessor} (from this file's sibling scratch build) deployed
 * onto its classpath.
 * <p>
 * This exists specifically because the in-JVM dtest harness used by {@code EntryProcessorDistributedTest} in
 * {@code vendor/cassandra} cannot prove the swapped query handler runs at all — {@code coordinator().execute()}
 * and {@code executeInternal()} both call {@code QueryProcessor} directly, bypassing {@code ClientState}'s
 * handler-swap point (see the technical spec's "Testing note"). This test drives a genuine native-protocol driver
 * connection instead, which is the only way to actually exercise that swap point.
 * <p>
 * Not run as part of the normal {@code mvn test} build (Surefire's default include pattern doesn't match
 * {@code *IT.java}) — it requires a real Cassandra node listening on {@code localhost:9042} with the compute
 * query handler and test processors installed, which is a manual/CI-orchestrated precondition, not something this
 * module can stand up itself.
 */
class ComputeMapLiveIT
{
    private static CqlSession session;
    private static ComputeMap map;

    @BeforeAll
    static void setUp()
    {
        session = CqlSession.builder()
                             .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
                             .withLocalDatacenter("datacenter1")
                             .build();
        session.execute("CREATE KEYSPACE IF NOT EXISTS compute_it "
                         + "WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
        map = new ComputeMap(session, "compute_it", "entries");
        map.ensureSchema();
    }

    @AfterAll
    static void tearDown()
    {
        if (session != null)
            session.close();
    }

    @Test
    void putGetRemoveRoundTrip()
    {
        String key = "round-trip";
        ByteBuffer value = StandardCharsets.UTF_8.encode("hello");

        map.put(key, value);
        assertEquals("hello", decode(map.get(key)));

        map.remove(key);
        assertNull(map.get(key));
    }

    @Test
    void submitRunsRealProcessorThroughTheSwappedQueryHandler()
    {
        String key = "submit-happy-path";
        map.put(key, StandardCharsets.UTF_8.encode("hello"));

        ByteBuffer result = map.submit(key, "gg.amihan.cassandracompute.itest.UppercaseValueProcessor", ConsistencyLevel.ONE);

        assertEquals("HELLO", decode(result));
        // The delta must actually have been applied via StorageProxy.mutate - confirm by reading it back.
        assertEquals("HELLO", decode(map.get(key)));
    }

    @Test
    void submitTranslatesProcessorFailure()
    {
        String key = "submit-processor-failure";
        map.put(key, StandardCharsets.UTF_8.encode("hello"));

        EntryProcessorFailedException e = assertThrows(EntryProcessorFailedException.class,
            () -> map.submit(key, "gg.amihan.cassandracompute.itest.ThrowingProcessor", ConsistencyLevel.ONE));
        assertEquals(true, e.getMessage().contains("intentional test failure"));

        // A throwing processor must not have applied any delta.
        assertEquals("hello", decode(map.get(key)));
    }

    private static String decode(ByteBuffer buffer)
    {
        return StandardCharsets.UTF_8.decode(buffer.duplicate()).toString();
    }
}
