# cassandra-compute — Technical Spec

> Product spec: specs/project.md

## Architecture Overview

`cassandra-compute` is not a client of Cassandra — it is compiled into the Cassandra 5.0.6 server process itself, as a small set of additions alongside Cassandra's existing `net`, `service`, and `locator` packages. There is no separate compute-node process and no separate cluster-membership protocol: a "compute node" is simply a Cassandra node that also carries this code, and it uses Cassandra's own gossip/`TokenMetadata`/replication-strategy state directly, with no independent view of topology to keep in sync.

```
 external caller (JVM app, cassandra-compute client library)
        │
        │  (entry point — see Open Decision below)
        ▼
 ┌─────────────────────────── Cassandra 5.0.6 node (any node) ───────────────────────────┐
 │                                                                                        │
 │  1. Resolve owner: AbstractReplicationStrategy.getNaturalReplicasForToken(key)         │
 │     "primary" := result.get(0)  (project convention, not a Cassandra invariant)        │
 │                                                                                        │
 │  2a. If local node == primary:                    2b. If local node != primary:        │
 │      run EntryProcessor.process() in-process           Message.out(ENTRYPROCESSOR_REQ) │
 │      (no serialization — direct call)                  → MessagingService.sendWithCallback
 │            │                                                     │ (over the wire to primary)
 │            ▼                                                     ▼
 │      builds a delta (Mutation, via                    [ same node runs step 2a locally,
 │       Mutation.SimpleBuilder /                          then replies ENTRYPROCESSOR_RSP ]
 │       PartitionUpdate.SimpleBuilder /
 │       Row.SimpleBuilder.add(col, val))
 │            │
 │            ▼
 │      StorageProxy.mutate(List.of(delta), CL, requestTime)
 │            │
 │            ├─ replica == self  → performLocally() → Mutation.apply()  (no message built)
 │            └─ replica != self  → normal MUTATION_REQ / MutationVerbHandler
 │                                   (Cassandra's existing write path — this IS "BackupEntryProcessor")
 │            │
 │            ▼
 │      result + ack-count against CL → returned to caller (or exception on CL failure)
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

The key architectural claim, confirmed against the actual 5.0.6 source: **there is no `BackupEntryProcessor` class or extension point in this design.** "Backup on every write" is realized entirely by handing the primary's computed delta to `StorageProxy.mutate(...)`, which is Cassandra's unmodified write path — replica resolution, dispatch, CL ack-counting, and hinted handoff are all reused verbatim. This is a simplification versus the original Hazelcast-inspired framing (Hazelcast requires a user-authored `getBackupProcessor()`); here, only `EntryProcessor` is a concept a developer implements.

### Client-facing entry point: CQL native protocol via a custom `QueryHandler`

Internode dispatch (step 2a→2b) reuses `MessagingService`/`Verb`, which is internode-only — it has no facility for an external JVM application to connect. Cassandra's actual client-facing surface is the separate CQL native protocol (`org.apache.cassandra.transport`), unrelated to `net`/`MessagingService`.

**Decided and implemented**: extend the CQL native protocol via a custom `QueryHandler` (swappable at JVM startup via the `cassandra.custom_query_handler_class` system property, wired in `service/ClientState.java`), intercepting a purpose-built call syntax. Callers keep using a normal CQL driver/connection — only the client-side `cassandra-compute` library needs code to construct the special call and deserialize its result. This was chosen over (a) a bespoke new protocol/port — more ops surface, no reuse of existing drivers/auth/TLS — and (b) Cassandra's trigger mechanism (`ITrigger.augment(Partition)`), ruled out because its API is explicitly beta/unstable upstream and `augment()` only supports contributing additional mutations to an existing write, with no request/response/result-value semantics — `EntryProcessor` needs to return a computed result to the caller, which triggers cannot do.

**Actual syntax**: `CALL ENTRYPROCESSOR('keyspace', 'table', 'key', 'processorClassName'[, 'consistencyLevel'])`, hand-parsed via a fixed-arity regex in `EntryProcessorCallStatement`, not added to Cassandra's ANTLR grammar — the grammar is generated and shared by every statement type, and modifying it is a much larger, riskier undertaking than anything else in this project; a regex over a small fixed call form was a deliberate simplification instead (the `SELECT ep_invoke(...)` form originally sketched here was illustrative only and was not what got built — a bare scalar function call has no natural place to carry a write's side effects in CQL's execution model, so a dedicated `CALL` form was used instead). `ComputeQueryHandler` only overrides `parse()`; every other `QueryHandler` method (`process`, `prepare`, `processPrepared`, `processBatch`, `getPrepared`) delegates unconditionally to `QueryProcessor.instance`, since `QueryProcessor.process()` already works generically against any `CQLStatement` via its `authorize`/`validate`/`execute` methods — no per-statement-type special-casing was needed.

v1 scope limits (documented on `EntryProcessorCallStatement`): only `text`/`varchar` partition keys are supported; the result is always returned as a single `blob` column, since the statement has no way to know a processor's actual result type; `authorize()` is a no-op — no permission model exists yet, matching the product spec's security out-of-scope decision; preparing this statement (`PREPARE CALL ENTRYPROCESSOR(...)`) is not supported and fails with a normal CQL parse error — callers use simple/unprepared execution.

Scope note: a custom `QueryHandler` only sees requests arriving via the native protocol — not batchlog replay, hints, or view updates. This is acceptable here since `EntryProcessor` submission is always a fresh client-initiated call, never a replayed one.

**Testing note**: the in-jvm dtest framework's `coordinator().execute(...)` and `executeInternal(...)` both call `QueryProcessor` directly in the 5.0.6-era dtest-api version used here, bypassing `ClientState`'s query-handler swap point entirely — neither can be used to prove the swapped handler actually runs. The integration test instead drives `ClientState.getCQLQueryHandler().parse()`/`.process()` directly inside the target instance, which is what a real native-protocol client connection triggers.

### Per-key mutual exclusion during EntryProcessor execution

**Decided**: no other write to a key may proceed while an `EntryProcessor` invocation for that key is in progress. `EntryProcessorRequestHandler.execute()` and `EntryDispatch.invokeLocally()` hold a per-`(table, key)` lock (`EntryLocks`, a `Striped<Lock>` — `Striped.lazyWeakLock`) for the entire read-modify-write, mirroring Cassandra's own precedent for exactly this problem shape: `CounterMutation`'s striped cell-level locks, which serialize a counter increment's local read-modify-write the same way, for the same reason (a read must happen before the corresponding write, so two concurrent operations on the same partition must not interleave).

**Scope, precisely**: this guarantees mutual exclusion among invocations that go through the compute layer — `EntryProcessor` today, and any future map put/remove built on the same path. It does **not** block a plain CQL write issued directly against the backing table outside the map API; doing so would mean hooking Cassandra's universal write path for every table, not just compute-layer-managed ones — a materially bigger and riskier change than anything else in this project, deliberately not taken. In practice this is not a new gap: the product spec's "distributed map abstraction" was never a guarantee that raw CQL access races safely with it.

**A correctness subtlety this surfaced** (found via the multi-node integration test, not anticipated in the original design): `StorageProxy.mutate(...)`'s local-replica application happens inside `performLocally`, submitted *asynchronously* to the `MUTATION` stage. Its ack races the other natural replicas' acks to satisfy whatever `ConsistencyLevel` the caller requested. Under anything short of `ALL` with more than one replica, `mutate()` can therefore return successfully — satisfied by a **remote** replica's ack — before this node's own local apply has actually run. Since the whole point of `EntryLocks` is "the next invocation for this key must see this write once it acquires the lock," relying on `mutate()`'s return as that signal is unsound at CL < ALL. Fix: `EntryProcessorRequestHandler.execute()` and `EntryDispatch.invokeLocally()` now call `mutation.apply()` synchronously themselves, immediately before handing the same mutation to `StorageProxy.mutate(...)` — the latter's own local re-application becomes a harmless idempotent no-op (same delta, same timestamp), and now performs only its original job of replicating to the other natural replicas at the requested CL. This local-apply guarantee is unconditional — it does not weaken or change the caller-visible CL contract for cluster-wide durability, which is exactly what the caller requested and still gets.

## Data Model

```java
public interface EntryProcessor<R> {
    R process(EntryProcessorContext ctx) throws Exception;
}

public interface EntryProcessorContext {
    DecoratedKey key();
    Row currentRow();                 // read-only view of current local state (local read, no CL — see product spec)
    Row.SimpleBuilder delta();        // Row.SimpleBuilder.add(columnName, value) — partial/delta update only
}
```

There is deliberately no `BackupEntryProcessor` interface (see Architecture Overview). No `EntryProcessorResult`/backup-result type either — the delta produced via `ctx.delta()` is exactly the `Mutation` that gets handed to `StorageProxy.mutate(...)`.

| Concept | Cassandra type reused | Notes |
|---|---|---|
| Processor delta | `org.apache.cassandra.db.Mutation` (built via `Mutation.simpleBuilder(ks, key)` → `PartitionUpdate.SimpleBuilder` → `Row.SimpleBuilder`) | Only touched columns are included — `Row.SimpleBuilder.add(col, val)`, never a full-row replace |
| Owner resolution | `AbstractReplicationStrategy.getNaturalReplicasForToken(token)` | Project convention: primary = `.get(0)`; must be re-resolved per call, never cached, since ordering can change with topology |
| Consistency | `org.apache.cassandra.db.ConsistencyLevel` | Passed straight through to `StorageProxy.mutate(mutations, consistencyLevel, requestTime)`, unmodified |
| Wire payload (internode) | New `EntryProcessorRequest` / `EntryProcessorResponse` records, serialized via `IVersionedSerializer<T>` (`serialize`/`deserialize`/`serializedSize`, matching every other `Verb` payload) | Request carries: table id, partition key bytes, processor class name, small init-arg bytes, `ConsistencyLevel` ordinal. Response carries: result bytes or a typed failure |

## API / Interface Contract

**New `Verb` constants** (added directly in `net/Verb.java`, `Kind.NORMAL` — this project is a source-embedded build, not a downstream plugin jar, so there's no reason to use the `Kind.CUSTOM`/`CUSTOM_VERB_START` id range reserved for external forks):

```java
ENTRYPROCESSOR_REQ (id, P3, writeTimeout, Stage.MUTATION,
    () -> EntryProcessorRequest.serializer,
    () -> EntryProcessorRequestHandler.instance,
    ENTRYPROCESSOR_RSP)
ENTRYPROCESSOR_RSP (id, P3, writeTimeout, Stage.INTERNAL_RESPONSE,
    () -> EntryProcessorResponse.serializer,
    () -> ResponseVerbHandler.instance,   // reuse Cassandra's existing generic response handler
    null)
```

**Dispatch handler** (`EntryProcessorRequestHandler implements IVerbHandler<EntryProcessorRequest>`):
```java
void doVerb(Message<EntryProcessorRequest> message) throws IOException {
    // 1. look up processor by class name (must already be on classpath — no dynamic loading)
    // 2. run EntryProcessor.process(ctx) against local storage
    // 3. build Mutation from ctx.delta()
    // 4. StorageProxy.mutate(List.of(mutation), request.consistencyLevel, requestTime)
    // 5. MessagingService.instance().respond(new EntryProcessorResponse(result), message)
}
```

**Local fast path** (invoked from the client-facing entry point, before ever touching `MessagingService`):
```java
EndpointsForToken replicas = strategy.getNaturalReplicasForToken(token);
if (replicas.get(0).endpoint().isSelf()) {
    // run EntryProcessorRequestHandler's logic in-process directly — no Message built, no serializer invoked
} else {
    Message<EntryProcessorRequest> msg = Message.out(Verb.ENTRYPROCESSOR_REQ, request);
    MessagingService.instance().sendWithCallback(msg, replicas.get(0).endpoint(), callback);
}
```

**Failure surface**: `StorageProxy.mutate(...)` throws `UnavailableException` / `WriteTimeoutException` / `WriteFailureException` / `OverloadedException` (all `RequestExecutionException`), which `EntryProcessorRequestHandler.execute()` catches and reports as `EntryProcessorResponse.Status.WRITE_FAILURE` rather than letting the raw exception type propagate — necessary because the response crosses `MessagingService` (an `IVersionedSerializer`-based wire format, not a Java exception channel) whenever the primary isn't the local node. At the CQL entry point, `EntryProcessorCallStatement.doExecute()` in turn surfaces both `WRITE_FAILURE` and `PROCESSOR_FAILURE` as a plain `InvalidRequestException`, distinguished only by a `"Write failed: "` / `"EntryProcessor failed: "` message prefix — so "my logic failed" and "the write didn't meet its consistency level" remain distinguishable to a caller (matching the product spec's acceptance criterion), but as a message-prefix convention rather than as distinct driver-visible exception types. The `cassandra-compute-client` library's `ComputeMap.submit()` translates these prefixes back into `EntryProcessorFailedException` / `EntryProcessorWriteFailedException` on the client side.

## Implementation Plan

Status: all nine steps are implemented and committed on the `cassandra-compute` branch of the `vendor/cassandra` fork (plus per-key locking, not originally listed here — see "Per-key mutual exclusion" above). Integration tests (`EntryProcessorDistributedTest`, three cases) pass consistently against a real 3-node cluster, including the CQL entry point end-to-end.

1. **Establish the embedded build** — vendor/patch the Cassandra 5.0.6 source tree as the build basis (this project's build produces a modified Cassandra distribution, not a jar dependency). Decide and document the patch-maintenance approach (source overlay vs. maintained fork branch) so future Cassandra version bumps are tractable. No dependency on other tasks.
2. **`EntryProcessor` / `EntryProcessorContext` interfaces** and the delta-builder wrapper around `Row.SimpleBuilder`. Pure new code, no Cassandra internals touched yet.
3. **Owner-resolution helper** wrapping `AbstractReplicationStrategy.getNaturalReplicasForToken(...).get(0)`, with a test asserting it's re-resolved (not cached) across a simulated topology change. Depends on #1.
4. **New `Verb` constants + `IVersionedSerializer`s for `EntryProcessorRequest`/`Response` + `EntryProcessorRequestHandler`**, wired per the pattern of an existing verb like `MUTATION_REQ`. Depends on #2, #3.
5. **Local fast-path dispatch logic** (the `replicas.get(0).endpoint().isSelf()` check) sitting in front of #4's handler, shared by both the local and remote invocation paths so there's exactly one code path for "run the processor," regardless of how the request arrived. Depends on #4.
6. **Wire the produced delta into `StorageProxy.mutate(...)`** from inside the handler — this is the entire "backup" mechanism; no new replication code. Depends on #4.
7. **Client-facing entry point** — a `QueryHandler` implementation that intercepts the `EntryProcessor` call syntax and delegates everything else to `QueryProcessor.instance` unchanged. Sequence this after #5/#6 are proven with an internal-only test harness, since it's the newest surface area with no existing precedent to copy.
8. **Processor registry** — how a processor class name in a request resolves to an instance on a given node (simple reflective no-arg instantiation, matching the product spec's "class reference, deployed ahead of time" decision — no classloading machinery like triggers use).
9. **Integration tests** against a real multi-node embedded 5.0.6 cluster, verifying: routing lands on `getNaturalReplicasForToken(key).get(0)` (per product spec's acceptance criteria), replicas never invoke processor code (only mutation apply), and CL/timeout/hint behavior matches an equivalent plain CQL write at the same `ConsistencyLevel`. Depends on all above.

## Client Library

Everything above is server-side (compiled into `vendor/cassandra`). The product spec's user-facing surface — "a client can `put(key, value)`, `get(key)`, and `remove(key)`" and "submit an `EntryProcessor`" — is a separate artifact: an ordinary CQL driver application, not more forked Cassandra source, since the whole point of the CQL entry point (over a bespoke protocol) was that callers keep using a normal driver connection.

**Decided**: a new Maven module, `client/` (`gg.amihan.cassandracompute:cassandra-compute-client`), depending on `org.apache.cassandra:java-driver-core` (the now-ASF-maintained driver; group id changed from `com.datastax.oss` at 4.18, packages did not — still `com.datastax.oss.driver.*`), targeting Java 17. Maven over Gradle purely as the path of least resistance for a single-module library with one real dependency — this project used Maven before the 2011 spike was removed (`dbd2179`), and nothing about that removal was a rejection of Maven itself.

`ComputeMap` (`client/src/main/java/gg/amihan/cassandracompute/client/ComputeMap.java`) is the whole v1 surface:
- `put`/`get`/`remove` are plain prepared CQL (`INSERT`/`SELECT`/`DELETE`) against a `(key text PRIMARY KEY, value blob)` table — this needs nothing compute-specific; it's here so the map abstraction has one client-side handle rather than two.
- Values are raw `ByteBuffer` — v1 deliberately imposes no serialization framework (matches the product spec's "not a general query engine" framing); typed (de)serialization is left to the caller.
- `submit(key, processorClassName[, consistencyLevel])` builds `CALL ENTRYPROCESSOR(...)` as a `SimpleStatement` (not prepared — the server-side statement can't be, see the v1 scope limits above) with all arguments escaped per the server's `''`-doubling convention, executes it, and returns the `result` blob column.
- Failures are translated from the server's message-prefix convention (see "Failure surface" above) into `EntryProcessorFailedException` / `EntryProcessorWriteFailedException` (both extend `EntryProcessorCallException`), so a caller can `catch` the specific failure mode instead of string-matching a driver exception itself. Anything else (a genuinely malformed call, a routing/network failure) is left as whatever the driver already throws — no invented wrapper for cases the driver already models well.

**Not yet built** (remaining product-spec acceptance criteria this doesn't address): `BackupEntryProcessor` as a *user-facing* submission API — today `BackupEntryProcessor` isn't a thing a client submits at all, since the technical spec's "no `BackupEntryProcessor` class" simplification (see Architecture Overview) means backup behavior is implicit in every `EntryProcessor` call, not a second call a developer makes; topology-change re-routing and primary-unreachable failover are exercised by the server-side integration test but have no client-side verification yet; and there's no live-cluster integration test for this module (`ComputeMapTest` covers CQL-string construction and failure translation only, no network I/O).

## Technical Risks & Trade-offs

- **This is a Cassandra source fork, not a plugin.** `Verb` is a closed compile-time enum (`Kind.CUSTOM`/`CUSTOM_VERB_START` exists precisely so *downstream* forks can add verbs without colliding with core ones — but adding any verb still means editing and recompiling `Verb.java`). There is no runtime verb-registration API in 5.0.6. Every future Cassandra version bump requires re-verifying/re-applying this against the new source — accepted as the cost of "stay as close to vanilla as possible" plus the hard pin to 5.0.6 already decided in the product spec.
- **"Primary" is a project convention, not a Cassandra guarantee.** `replicas.get(0)` is only special-cased inside Cassandra itself for repair-range bookkeeping (`StorageService.getPrimaryRangesForEndpoint`) — nothing enforces its stability beyond what `AbstractReplicationStrategy.calculateNaturalReplicas` already guarantees (deterministic given the same `TokenMetadata` snapshot). Must always be resolved fresh, never cached across a topology change.
- **The client-facing entry point is the one piece of this design without a direct precedent to copy.** Everything else (verb dispatch, local-apply, write-path reuse) has a direct analog already in Cassandra's own code; a custom `QueryHandler` intercepting a purpose-built call syntax does not — the syntax and its parsing are new surface area this project owns outright, and `QueryHandler` is a single JVM-wide swap point (one implementation per process), so it must also delegate every non-`EntryProcessor` statement straight through to `QueryProcessor.instance` to avoid breaking normal CQL.
- **No dynamic processor loading.** Deliberately excluded — see Out of Scope.

## Out of Scope (Technical)

- Dynamic/hot-deployment of processor code (Cassandra's own `triggers` package has a `CustomClassLoader`-based mechanism for this; not reused here since the product spec decided processors are class references deployed ahead of time to every node).
- Reworking `Verb`'s `Kind.CUSTOM` numbering scheme for further downstream forks-of-this-fork.
- Any client-facing entry point work beyond deciding and implementing one option from the three above — e.g. no multi-protocol support, no fallback.
- Multi-datacenter-aware processor routing beyond whatever `DatacenterWriteResponseHandler` already provides for the underlying write.
