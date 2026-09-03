package gg.amihan.cassandracompute.client;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * The 2-arg {@code submit(key, processorClassName)} overload is never exercised by the other tests here (they
     * all pass an explicit {@link ConsistencyLevel}) - proves it actually defaults to {@link ConsistencyLevel#ONE}
     * and behaves identically to calling the 3-arg overload with that value explicitly.
     */
    @Test
    void submitDefaultOverloadBehavesLikeExplicitConsistencyLevelOne()
    {
        String key = "submit-default-cl";
        map.put(key, StandardCharsets.UTF_8.encode("hello"));

        ByteBuffer result = map.submit(key, "gg.amihan.cassandracompute.itest.UppercaseValueProcessor");

        assertEquals("HELLO", decode(result));
        assertEquals("HELLO", decode(map.get(key)));
    }

    /**
     * Forces a genuine {@code WRITE_FAILURE} from the server (as opposed to {@link #submitTranslatesProcessorFailure}'s
     * {@code PROCESSOR_FAILURE}): {@code compute_it} is RF=1, so a submission at {@link ConsistencyLevel#TWO}
     * cannot be satisfied - {@code StorageProxy.mutate(...)} throws {@code UnavailableException} (a
     * {@code RequestExecutionException}), which {@code EntryProcessorRequestHandler.execute()} reports as
     * {@code WRITE_FAILURE}, surfaced here as {@code InvalidRequestException("Write failed: ...")} and translated
     * to {@link EntryProcessorWriteFailedException}.
     * <p>
     * Unlike a processor failure, the delta is <em>not</em> rolled back here - {@code EntryProcessorRequestHandler}
     * applies the mutation locally, synchronously, <em>before</em> handing it to {@code StorageProxy.mutate(...)}
     * (see the technical spec's "local-apply-visibility fix" and the inline comment at that call site), so the
     * local replica's value already reflects the processor's delta by the time the caller is told the write
     * didn't meet its requested durability guarantee. That's surprising enough to be worth pinning down explicitly
     * rather than assuming it works like the processor-failure case.
     */
    @Test
    void submitTranslatesWriteFailureAtUnsatisfiableConsistencyLevel()
    {
        String key = "submit-write-failure";
        map.put(key, StandardCharsets.UTF_8.encode("hello"));

        EntryProcessorWriteFailedException e = assertThrows(EntryProcessorWriteFailedException.class,
            () -> map.submit(key, "gg.amihan.cassandracompute.itest.UppercaseValueProcessor", ConsistencyLevel.TWO));
        assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());

        // The processor's delta was applied locally regardless of the unmet consistency level - see javadoc above.
        assertEquals("HELLO", decode(map.get(key)));
    }

    /**
     * {@code ComputeMap.put}/{@code get}/{@code remove} lazily initialize their {@link com.datastax.oss.driver.api.core.cql.PreparedStatement}s
     * on first use without double-checked locking, on the documented assumption that the driver's own
     * {@code session.prepare(...)} is safe to call concurrently from multiple threads (see the comment on
     * {@code ComputeMap}'s statement fields). Exercises a fresh, never-yet-used {@link ComputeMap} instance from
     * many threads at once so that first use races for real, rather than just asserting the assumption in prose.
     */
    @Test
    void concurrentFirstUseOfPutGetRemoveIsSafe() throws Exception
    {
        ComputeMap freshMap = new ComputeMap(session, "compute_it", "entries");
        int concurrency = 20;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try
        {
            CountDownLatch startingGate = new CountDownLatch(1);
            List<Future<?>> futures = IntStream.range(0, concurrency)
                .mapToObj(i -> pool.submit(() -> {
                    String key = "concurrent-first-use-" + i;
                    String value = "value-" + i;
                    try
                    {
                        startingGate.await();
                        freshMap.put(key, StandardCharsets.UTF_8.encode(value));
                        assertEquals(value, decode(freshMap.get(key)));
                        freshMap.remove(key);
                        assertNull(freshMap.get(key));
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }))
                .collect(Collectors.toList());

            startingGate.countDown();
            for (Future<?> f : futures)
                f.get(30, TimeUnit.SECONDS);
        }
        finally
        {
            pool.shutdown();
        }
    }

    private static String decode(ByteBuffer buffer)
    {
        return StandardCharsets.UTF_8.decode(buffer.duplicate()).toString();
    }
}
