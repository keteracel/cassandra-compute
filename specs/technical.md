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

**Decided**: extend the CQL native protocol via a custom `QueryHandler` (swappable at JVM startup via the `cassandra.custom_query_handler_class` system property, wired in `service/ClientState.java`), intercepting a purpose-built call syntax (e.g. a scalar function like `SELECT ep_invoke('processorClass', ...)`). Callers keep using a normal CQL driver/connection — only the client-side `cassandra-compute` library needs code to construct the special call and deserialize its result. This was chosen over (a) a bespoke new protocol/port — more ops surface, no reuse of existing drivers/auth/TLS — and (b) Cassandra's trigger mechanism (`ITrigger.augment(Partition)`), ruled out because its API is explicitly beta/unstable upstream and `augment()` only supports contributing additional mutations to an existing write, with no request/response/result-value semantics — `EntryProcessor` needs to return a computed result to the caller, which triggers cannot do.

Scope note: a custom `QueryHandler` only sees requests arriving via the native protocol — not batchlog replay, hints, or view updates. This is acceptable here since `EntryProcessor` submission is always a fresh client-initiated call, never a replayed one.

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

**Failure surface**: `StorageProxy.mutate(...)` throws `UnavailableException` / `WriteTimeoutException` / `WriteFailureException` / `OverloadedException` unmodified — these propagate to the caller as-is (matching the product spec's "same contract as any Cassandra write" decision). A processor that throws is caught by the handler and returned as a distinct `EntryProcessorException` in `EntryProcessorResponse`, so callers can tell "my logic failed" apart from "the write didn't meet its consistency level."

## Implementation Plan

1. **Establish the embedded build** — vendor/patch the Cassandra 5.0.6 source tree as the build basis (this project's build produces a modified Cassandra distribution, not a jar dependency). Decide and document the patch-maintenance approach (source overlay vs. maintained fork branch) so future Cassandra version bumps are tractable. No dependency on other tasks.
2. **`EntryProcessor` / `EntryProcessorContext` interfaces** and the delta-builder wrapper around `Row.SimpleBuilder`. Pure new code, no Cassandra internals touched yet.
3. **Owner-resolution helper** wrapping `AbstractReplicationStrategy.getNaturalReplicasForToken(...).get(0)`, with a test asserting it's re-resolved (not cached) across a simulated topology change. Depends on #1.
4. **New `Verb` constants + `IVersionedSerializer`s for `EntryProcessorRequest`/`Response` + `EntryProcessorRequestHandler`**, wired per the pattern of an existing verb like `MUTATION_REQ`. Depends on #2, #3.
5. **Local fast-path dispatch logic** (the `replicas.get(0).endpoint().isSelf()` check) sitting in front of #4's handler, shared by both the local and remote invocation paths so there's exactly one code path for "run the processor," regardless of how the request arrived. Depends on #4.
6. **Wire the produced delta into `StorageProxy.mutate(...)`** from inside the handler — this is the entire "backup" mechanism; no new replication code. Depends on #4.
7. **Client-facing entry point** — a `QueryHandler` implementation that intercepts the `EntryProcessor` call syntax and delegates everything else to `QueryProcessor.instance` unchanged. Sequence this after #5/#6 are proven with an internal-only test harness, since it's the newest surface area with no existing precedent to copy.
8. **Processor registry** — how a processor class name in a request resolves to an instance on a given node (simple reflective no-arg instantiation, matching the product spec's "class reference, deployed ahead of time" decision — no classloading machinery like triggers use).
9. **Integration tests** against a real multi-node embedded 5.0.6 cluster, verifying: routing lands on `getNaturalReplicasForToken(key).get(0)` (per product spec's acceptance criteria), replicas never invoke processor code (only mutation apply), and CL/timeout/hint behavior matches an equivalent plain CQL write at the same `ConsistencyLevel`. Depends on all above.

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
