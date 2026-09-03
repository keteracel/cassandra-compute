package gg.amihan.cassandracompute.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the product spec's "survives compute-node restart" clause of the put/get/remove acceptance criterion —
 * something {@link ComputeMapLiveIT} deliberately doesn't cover, since it assumes a node is already running as an
 * external precondition and never touches its process lifecycle.
 * <p>
 * Unlike {@link ComputeMapLiveIT}, this test owns the whole node lifecycle itself: it starts a Cassandra process
 * from the already-built {@code vendor/cassandra} jar, puts a value, stops the process cleanly, starts it again
 * against the same (default {@code vendor/cassandra/data}) data directory, and confirms the value is still there —
 * the only way to actually distinguish "durably persisted" from "visible via some in-process cache."
 * <p>
 * This needs sole ownership of {@code vendor/cassandra} for its duration: it uses the standard
 * {@code $CASSANDRA_HOME/data} location (there's no clean way to override it - {@code bin/cassandra} sets
 * {@code -Dcassandra.storagedir} itself, after any {@code JVM_OPTS}, so a {@code JVM_OPTS}-based override is
 * silently ignored), and it needs the packaged jar under {@code build/} to stay stable while a node built from it
 * is running - don't run this concurrently with anything else that starts a node from the same checkout, or with
 * an {@code ant} run that rebuilds that jar (a partial/in-progress rewrite can corrupt an already-running node's
 * classpath reads).
 * <p>
 * Like {@code ComputeMapLiveIT}, this is not run by plain {@code mvn test} (Surefire's default include pattern
 * doesn't match {@code *IT.java}); invoke explicitly with {@code -Dtest=ComputeMapRestartSurvivalIT}.
 */
class ComputeMapRestartSurvivalIT
{
    private static final Path VENDOR_CASSANDRA_HOME = Paths.get("..", "vendor", "cassandra").toAbsolutePath().normalize();
    private static final Path JAVA_17_HOME = Paths.get("/usr/lib/jvm/java-17-amazon-corretto");
    private static final Path DATA_DIR = VENDOR_CASSANDRA_HOME.resolve("data");
    private static final Duration START_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    private static Process nodeProcess;
    private static Path logFile;

    @BeforeAll
    static void checkPreconditions()
    {
        Assumptions.assumeTrue(Files.isDirectory(VENDOR_CASSANDRA_HOME),
            "vendor/cassandra not found at " + VENDOR_CASSANDRA_HOME);
        Assumptions.assumeTrue(Files.isRegularFile(VENDOR_CASSANDRA_HOME.resolve("bin/cassandra")),
            "vendor/cassandra doesn't look built (no bin/cassandra) at " + VENDOR_CASSANDRA_HOME);
        Assumptions.assumeTrue(Files.isDirectory(JAVA_17_HOME),
            "Java 17 not found at " + JAVA_17_HOME + " - Cassandra 5.0 requires Java 11 or 17");
    }

    @AfterAll
    static void cleanUp() throws IOException, InterruptedException
    {
        stopNodeIfRunning();
        deleteRecursively(DATA_DIR);
        deleteRecursively(VENDOR_CASSANDRA_HOME.resolve("logs")); // Cassandra's own default cassandra.logdir
        deleteRecursively(VENDOR_CASSANDRA_HOME.resolve("restart-survival-it-logs")); // this test's own redirected stdout
    }

    @Test
    void putValueSurvivesACleanNodeRestart() throws Exception
    {
        startNode();
        String key = "restart-survival-key";
        String value = "value-before-restart";

        try (CqlSession session = connect())
        {
            session.execute("CREATE KEYSPACE IF NOT EXISTS restart_it "
                             + "WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
            ComputeMap map = new ComputeMap(session, "restart_it", "entries");
            map.ensureSchema();
            map.put(key, StandardCharsets.UTF_8.encode(value));
            assertEquals(value, decode(map.get(key)));
        }

        stopNodeIfRunning();
        startNode();

        try (CqlSession session = connect())
        {
            ComputeMap map = new ComputeMap(session, "restart_it", "entries");
            assertEquals(value, decode(map.get(key)));
        }
    }

    private static CqlSession connect()
    {
        return CqlSession.builder()
                          .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
                          .withLocalDatacenter("datacenter1")
                          .build();
    }

    private static String decode(ByteBuffer buffer)
    {
        return StandardCharsets.UTF_8.decode(buffer.duplicate()).toString();
    }

    private static void startNode() throws IOException, InterruptedException
    {
        Files.createDirectories(DATA_DIR.resolve("data"));
        Files.createDirectories(DATA_DIR.resolve("commitlog"));
        Files.createDirectories(DATA_DIR.resolve("saved_caches"));
        Files.createDirectories(DATA_DIR.resolve("hints"));
        Path logsDir = VENDOR_CASSANDRA_HOME.resolve("restart-survival-it-logs");
        Files.createDirectories(logsDir);
        logFile = logsDir.resolve("node-" + System.nanoTime() + ".log");

        ProcessBuilder pb = new ProcessBuilder(
            VENDOR_CASSANDRA_HOME.resolve("bin/cassandra").toString(), "-f");
        pb.directory(VENDOR_CASSANDRA_HOME.toFile());
        pb.environment().put("JAVA_HOME", JAVA_17_HOME.toString());
        pb.environment().put("PATH", JAVA_17_HOME.resolve("bin") + ":" + pb.environment().getOrDefault("PATH", ""));
        pb.redirectOutput(logFile.toFile());
        pb.redirectErrorStream(true);

        nodeProcess = pb.start();
        waitUntilReady();
    }

    private static void waitUntilReady() throws InterruptedException, IOException
    {
        Instant deadline = Instant.now().plus(START_TIMEOUT);
        while (Instant.now().isBefore(deadline))
        {
            if (!nodeProcess.isAlive())
                throw new IllegalStateException("Cassandra process exited during startup - see " + logFile);

            String log = Files.exists(logFile) ? Files.readString(logFile) : "";
            if (log.contains("Startup complete"))
                return;
            if (log.contains("Exception in thread \"main\""))
                throw new IllegalStateException("Cassandra failed to start - see " + logFile);

            Thread.sleep(1000);
        }
        throw new IllegalStateException("Cassandra did not report \"Startup complete\" within " + START_TIMEOUT + " - see " + logFile);
    }

    private static void stopNodeIfRunning() throws InterruptedException
    {
        if (nodeProcess == null || !nodeProcess.isAlive())
            return;

        nodeProcess.destroy();
        if (!nodeProcess.waitFor(STOP_TIMEOUT.getSeconds(), TimeUnit.SECONDS))
            nodeProcess.destroyForcibly();
        nodeProcess = null;
    }

    private static void deleteRecursively(Path path) throws IOException
    {
        if (!Files.exists(path))
            return;
        try (var stream = Files.walk(path))
        {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try
                {
                    Files.delete(p);
                }
                catch (IOException e)
                {
                    // best-effort cleanup
                }
            });
        }
    }
}
