<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# 595. Cross Hypervisor DR Failback Route Contract And Terminal Convergence Design

- Date: 2026-08-05
- Status: code-level corrective design, live preflight verified, implementation pending
- Scope: UI, API, Cloud backend, Agent, FTCTL contract, DB/cache, recovery, retest
- Parent data contract: [588](588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md)
- Runtime reconciliation parent: [594](594-cross-hypervisor-dr-live-worker-and-terminal-reconciliation-design-20260805.md)
- Engine companion: `ablestack-qemu-exec-tools/docs/ftctl/452-ftctl-dr-failback-route-envelope-and-cloud-lifecycle-boundary-design-20260805.md`

## 1. Objective

Correct the Failback route contract without weakening the Cloud/FTCTL ownership
boundary. A successful KVM-to-VMware transfer must not be rejected because a
hypervisor direction and a provider pair use different vocabularies. If any
Cloud-owned lifecycle gate fails, FailbackSession, Run, Plan, runtime, cache,
and action availability must converge instead of leaving a 70 percent Run in
`RUNNING` forever.

UI and API remain asynchronous. The UI submits intent and reads Cloud state;
Cloud owns VM lifecycle and authority; Agent relays typed commands; FTCTL owns
data movement and engine terminal evidence.

## 2. Live Incident And Preflight Evidence

The design was verified against Plan
`7889e625-371a-48f9-b553-54e311481170`, Failback Run
`18d0b555-9cdf-41c1-9650-f9620b0ccc36`, and FailbackSession `12`.

| Layer | Verified observation |
|---|---|
| Cloud Plan | `SYNCING`, active side `TARGET`, last Run `139` |
| Cloud Run | `RUNNING`, `runtime-transfer`, no terminal source or completion |
| FailbackSession | `FAILED`, `DR_FAILBACK_DIRECTION_MISMATCH` |
| Session route | `replication_direction=KVM_TO_VMWARE`, `provider_pair=ABLESTACK_TO_VMWARE` |
| FTCTL | authoritative `FAILBACK_DATA_READY`, exit `0`, endpoint drain complete |
| Transfer | reverse checkpoint sequence `14`, payload `96,876,032` bytes |
| KVM target | serving VM remains powered on |
| VMware source | original VM remains powered off |
| Authority | TARGET is retained; no unsafe power transition occurred |

Read-only DB and runtime preflight therefore proves:

1. the reverse data operation reached a successful engine terminal;
2. Cloud rejected the result before target shutdown or VMware power-on;
3. the failure is a route vocabulary defect, not a VDDK, RBD, NBD, guest, or
   power-transition failure;
4. failure handling closed only the session and left the Run and Plan stale.

No live VM, FTCTL state, or DB row was changed during this design preflight.

## 3. Root Cause

### 3.1 Direction and provider pair are collapsed

`DrOrchestratorImpl.createRequestedFailbackSession()` correctly writes:

```text
replicationDirection = KVM_TO_VMWARE
providerPair         = ABLESTACK_TO_VMWARE
```

`DrFailbackDataGateServiceImpl.validate()` incorrectly requires both fields to
equal `ABLESTACK_TO_VMWARE`. The production value is therefore rejected by
construction.

### 3.2 The unit test certifies the wrong contract

`DrFailbackDataGateServiceImplTest.setUp()` seeds
`replicationDirection=ABLESTACK_TO_VMWARE`, so the test passes a value that the
production orchestrator never creates for this path. The test protects the
defect instead of the production contract.

### 3.3 FTCTL uses one legacy key for two meanings

`dr_runtime.sh` builds a reverse profile whose `direction` is
`KVM_TO_VMWARE`, but later writes `reverse_direction` from provider names as
`ABLESTACK_TO_VMWARE`. It writes `provider_pair` internally but does not emit
both canonical fields in the final status JSON. The KVM Agent wrapper then maps
legacy `reverse_direction` directly to `replicationDirection`.

### 3.4 Gate failure is not a terminal convergence operation

`DrFailbackLifecycleServiceImpl.failSession()` updates only
`dr_failback_session`. It does not:

- cancel or terminalize the accepted FTCTL operation;
- terminalize `dr_run`;
- restore the Plan to `FAILED_OVER/TARGET`;
- update replica authority and power projection;
- invalidate the protection-view cache;
- block or re-enable actions from one canonical result.

This produces the observed `Session=FAILED`, `Run=RUNNING`, `Plan=SYNCING`
combination.

## 4. Canonical Route Model

### 4.1 Distinct types

Add constants in `DrConstants.java` and centralize parsing in a new immutable
`DrFailbackRouteContract` value object.

```java
public final class DrFailbackRouteContract {
    private final String replicationDirection; // hypervisor topology
    private final String providerPair;          // product/provider path
    private final String operationIntent;       // FAILBACK_FINAL

    public static DrFailbackRouteContract forPlan(DrPlanVO plan);
    public static DrFailbackRouteContract normalize(
            String replicationDirection,
            String providerPair,
            String legacyReverseDirection);
    public DrFailbackRouteValidation validateFor(DrPlanVO plan);
}
```

Canonical constants:

```java
DIRECTION_VMWARE_TO_KVM = "VMWARE_TO_KVM";
DIRECTION_KVM_TO_VMWARE = "KVM_TO_VMWARE";
PROVIDER_PAIR_VMWARE_TO_ABLESTACK = "VMWARE_TO_ABLESTACK";
PROVIDER_PAIR_ABLESTACK_TO_VMWARE = "ABLESTACK_TO_VMWARE";
OPERATION_INTENT_FAILBACK_FINAL = "FAILBACK_FINAL";
```

Do not compare a `DIRECTION_*` value with a `PROVIDER_PAIR_*` value even if the
human-readable route is the same.

### 4.2 Valid route matrix

| Original Plan direction | Failback replication direction | Provider pair | Result |
|---|---|---|---|
| `VMWARE_TO_KVM` | `KVM_TO_VMWARE` | `ABLESTACK_TO_VMWARE` | valid |
| `KVM_TO_VMWARE` | `VMWARE_TO_KVM` | `VMWARE_TO_ABLESTACK` | valid |
| `VMWARE_TO_KVM` | `ABLESTACK_TO_VMWARE` | `ABLESTACK_TO_VMWARE` | legacy, normalize topology |
| any | forward direction | reverse provider pair | reject |
| any | reverse direction | forward provider pair | reject |

Legacy normalization is accepted only at the Agent/FTCTL boundary. DB and API
persist and expose canonical values.

## 5. End-To-End State Machine

```text
UI FAILBACK submit
  -> Cloud Run + FailbackSession transaction
  -> Agent start acknowledgement
  -> FTCTL reverse transfer
  -> FTCTL FAILBACK_DATA_READY + route v2 + durable evidence
  -> Cloud route normalization and data gate
       -> PASS: source-isolation gate -> target stop -> source start
                -> engine commit -> authority SOURCE -> protection resume
       -> FAIL: lifecycle abort saga -> authority remains TARGET
                -> Session FAILED + Run FAILED + Plan FAILED_OVER
```

No Cloud gate may change VM power or authority before the route and data tuple
is valid.

## 6. Cloud Backend Design

### 6.1 Session creation

Refactor `DrOrchestratorImpl.createRequestedFailbackSession()` to use
`DrFailbackRouteContract.forPlan(plan)`. Persist all three typed values before
Agent dispatch. If the Plan direction has no reverse route, reject before Run
acceptance.

### 6.2 Runtime evidence merge

Replace the current preference in
`DrFailbackLifecycleServiceImpl.updateDataEvidence()`:

```java
session.setReplicationDirection(defaultValue(
        runtime["reverse_direction"], runtime["replication_direction"]));
```

with:

```java
DrFailbackRouteContract route = routeContract.normalize(
        stringValue(runtime, "replication_direction"),
        stringValue(runtime, "provider_pair"),
        stringValue(runtime, "reverse_direction"));
mergeNonBlankCanonicalRoute(session, route);
```

The merge rules are:

1. explicit v2 fields win;
2. legacy `reverse_direction` is normalized by value domain;
3. blank runtime fields never erase the pre-dispatch session route;
4. a conflicting nonblank route records `DR_FAILBACK_ROUTE_EVIDENCE_CONFLICT`
   and enters convergence; it never overwrites good DB evidence silently.

The evidence merge guard must also recognize `replication_direction`,
`reverse_direction`, `provider_pair`, and all baseline/writer fields. The
current guard ignores a payload containing only `baseline_state` or legacy
route evidence.

### 6.3 Data gate

`DrFailbackDataGateServiceImpl` validates the route tuple through
`DrFailbackRouteContract`, then validates baseline, tracker, writer, target
write, and guest compatibility evidence. Return distinct codes:

```text
DR_FAILBACK_ROUTE_DIRECTION_INVALID
DR_FAILBACK_ROUTE_PROVIDER_INVALID
DR_FAILBACK_ROUTE_EVIDENCE_CONFLICT
DR_FAILBACK_BASELINE_NOT_DURABLE
DR_FAILBACK_TARGET_WRITE_UNVERIFIED
DR_FAILBACK_GUEST_COMPATIBILITY_NOT_READY
```

The message shown to the user is a localized operational summary. Raw route
values remain in diagnostics and events, not the primary banner.

### 6.4 Atomic failure convergence

Introduce `DrFailbackTerminalConvergenceService` and call it for every failure
before authority transition, including data-gate and source-isolation-gate
failures.

```java
public interface DrFailbackTerminalConvergenceService {
    DrFailbackConvergenceResult failBeforeAuthorityTransition(
            DrPlanVO plan,
            DrRunVO run,
            DrFailbackSessionVO session,
            String phase,
            String component,
            String code,
            String message);
}
```

The implementation is a recoverable saga:

1. transaction: Session `ABORTING`, Run remains `RUNNING` with
   `projection_state=canceling`, Plan becomes `RECOVERING/TARGET`;
2. external side effect: send idempotent FTCTL `FAILBACK_ABORT prepare` and
   `commit` with the same Plan/Run/session identity;
3. verify `runtime_endpoints_drained=true`, target KVM VM powered on, VMware
   source powered off, and TARGET authority unchanged;
4. transaction: Session `FAILED`, Run `FAILED`, Plan `FAILED_OVER/TARGET`,
   replica `FAILED_OVER/POWERED_ON/TARGET`;
5. persist the same typed error, phase, component, terminal source, terminal
   version, and terminal authority in Session and Run;
6. invalidate the Plan protection-view cache and publish one terminal event.

Use `terminal_source=CLOUD_LIFECYCLE`, increment `terminal_version`, and mark
the Run terminal authoritative only after abort/drain verification. If Agent
acknowledgement is uncertain, keep Plan `RECOVERING/TARGET`, block all mutating
actions except cancel/reconcile, and let the scheduled reconciler resume the
saga after restart.

The durable reverse baseline and checkpoint are preserved. Abort removes only
Run-owned transient endpoints and operation ownership.

### 6.5 Scheduled reconciliation

`DrFailbackSessionDao.listReconcileCandidates()` must include `ABORTING` and
`ROLLBACK_FAILED`/uncertain states. Selection must use indexed DB predicates
for `state`, `last_probe_at`, and `removed`; do not fetch 200 rows and filter
probe time in Java.

`DrFailbackLifecycleServiceImpl.reconcilePendingSessions()` must be idempotent:
one Run ID is admitted once per process and each DB transition uses the
session lifecycle version or equivalent compare-and-set condition.

## 7. Agent And FTCTL Boundary

### 7.1 Agent wrapper

`LibvirtFtctlDrStatusCommandWrapper` reads:

```java
replicationDirection = firstNonBlank(
        payload["replication_direction"],
        normalizeLegacyDirection(payload["reverse_direction"]));
providerPair = firstNonBlank(
        payload["provider_pair"],
        normalizeLegacyProviderPair(payload["reverse_direction"]));
```

Agent relays values losslessly and never decides route validity or Run
terminal state.

### 7.2 Contract version

FTCTL status publishes:

```json
{
  "route_contract_version": 2,
  "replication_direction": "KVM_TO_VMWARE",
  "provider_pair": "ABLESTACK_TO_VMWARE",
  "reverse_direction": "ABLESTACK_TO_VMWARE"
}
```

`reverse_direction` remains temporarily for compatibility and is deprecated.
Cloud uses the explicit fields whenever `route_contract_version >= 2`.

### 7.3 Secret-safe logging

Agent/management debug logging must not serialize `profileJson` credentials.
Add a redacted command summary containing only Plan UUID, Run UUID, action,
direction, route contract version, worker host, and payload size. Replace
password, secret, API key, token, credential, cookie, and session values with
`***` before any diagnostic serialization.

## 8. API And UI Design

### 8.1 API

Reuse existing Run terminal fields and expose the canonical route in Run or
protection details:

```text
replicationdirection
providerpair
routecontractversion
failurephase
failedcomponent
terminalsource
terminalversion
terminalauthoritative
```

The list and detail APIs must derive state from the same Plan/Run snapshot or
cache version. A Session terminal mismatch is projected as a reconciliation
state, not as an unrelated 70 percent running operation.

### 8.2 UI

`DrProtectionInfoTab.vue` and `DrPlanList.vue` use the canonical Run result:

- normal data-ready waiting: `페일백 전환 준비`;
- lifecycle validation failure: `페일백 전환 검증 실패`;
- abort/reconciliation: `상태 정리 중`;
- converged safe failure: Plan remains `페일오버됨 - 보호되지 않음` with
  TARGET active;
- raw `KVM_TO_VMWARE` and `ABLESTACK_TO_VMWARE` are diagnostic details, not
  primary user guidance.

Poll only the Cloud API asynchronously. Invalidate and refetch list/detail
cache after a terminal event. Disable Failback while Run/session/runtime is
active, canceling, or reconciling. Re-enable it only after TARGET power,
authority, endpoint drain, and preflight are all ready.

## 9. DB Design

Existing `dr_failback_session.replication_direction` and `provider_pair`
columns are sufficient. No destructive migration is required.

Add idempotent upgrade coverage only for:

- a composite reconciliation index such as
  `(state, last_probe_at, removed)` after verifying the generated query plan;
- optional `route_contract_version` on the session if runtime version must be
  audited independently from `details_json`.

Do not rewrite historical terminal rows. The cleanup service corrects only the
current nonterminal Run/session and records a repair event. Historical failed
Runs remain immutable audit evidence.

## 10. Test Design

### 10.1 Unit tests

Replace the misleading data-gate fixture with the production tuple:

```text
KVM_TO_VMWARE + ABLESTACK_TO_VMWARE -> PASS
VMWARE_TO_KVM + ABLESTACK_TO_VMWARE -> direction failure
KVM_TO_VMWARE + VMWARE_TO_ABLESTACK -> provider failure
legacy ABLESTACK_TO_VMWARE -> normalize and PASS
blank runtime route -> retain pre-dispatch session route
conflicting explicit route -> evidence conflict
```

Add tests for the wrapper's v2-first/legacy-fallback mapping and ensure FTCTL
status JSON includes both canonical fields.

### 10.2 Backend integration tests

1. `FAILBACK_DATA_READY` plus valid tuple invokes the lifecycle exactly once.
2. Valid data gate stops TARGET only after route validation succeeds.
3. Gate failure invokes abort, leaves TARGET running and VMware off, and
   terminalizes Session/Run/Plan consistently.
4. Crash after `ABORTING` resumes from the scheduled reconciler.
5. Duplicate status polls do not duplicate power or abort commands.
6. Stale cache cannot show Run `RUNNING` after terminal convergence.
7. Credential values are absent from logs, status JSON, DB details, and events.

### 10.3 Real preflight and acceptance

Before deployment, use a no-power-transition preflight against the target
Plan to verify:

- explicit route v2 payload;
- durable sequence `14` checkpoint can be read;
- TARGET KVM VM is running and VMware source is off;
- Run-owned NBD/VDDK endpoints are already drained;
- `DrFailbackDataGateServiceImpl` accepts the captured production tuple in an
  integration fixture.

After implementation, build, paired deployment, and cleanup:

1. abort/converge stuck Run `139` through the official service path;
2. verify Plan `FAILED_OVER/TARGET`, Session/Run terminal, no endpoint residue;
3. verify Failback preflight `READY` without force;
4. operator executes Failback once;
5. verify reverse data, TARGET stop, VMware start, commit, SOURCE authority,
   protection resume, and one canonical terminal result.

## 11. Recommended Implementation Priority

1. P0: canonical route constants/value object and production-tuple unit tests.
2. P0: FTCTL v2 route envelope and Agent explicit-field mapping.
3. P0: data-evidence merge and route-aware data gate.
4. P0: transactional failure-convergence saga and restart reconciliation.
5. P0: current Run cleanup through the official abort/convergence path.
6. P1: API/UI canonical state, action gating, and cache invalidation.
7. P1: credential-safe logging and regression tests.
8. P1: changed Maven modules, UI build, qemu Actions package, paired deploy.
9. P1: live preflight and one operator-triggered Failback acceptance.

## 12. AS-IS / TO-BE

| Layer | Error cause | AS-IS | TO-BE |
|---|---|---|---|
| UI | Run and Session disagree | 70 percent running after failure | one reconciliation/terminal result from canonical Run |
| API | route semantics implicit | legacy direction is ambiguous | explicit route v2 fields and typed terminal metadata |
| Backend | topology and provider values compared as one type | valid reverse checkpoint rejected | tuple-based `DrFailbackRouteContract` validation |
| Lifecycle | `failSession()` updates one row | Session failed, Run/Plan remain active | abort saga converges Session, Run, Plan, replica, cache |
| Agent | legacy field mapped directly | provider value becomes topology direction | v2-first typed relay with legacy normalization |
| FTCTL | `reverse_direction` carries provider pair | canonical topology is lost in status | emit topology and provider pair separately |
| DB | valid columns but stale cross-row state | `FAILED/RUNNING/SYNCING` combination | one transaction records canonical terminal state |
| Authority | gate fails before power transition | TARGET is safe but UI is ambiguous | explicit TARGET retention and action gate |
| Security | command profile may enter debug output | credentials risk log exposure | redacted structured command summary |

## 13. Completion And Operator Handoff

Design completion requires Cloud and FTCTL companion documents to define the
same route tuple, legacy normalization, terminal convergence, and acceptance
test.

No operator action is required during design. After the subsequent
implementation/build/deployment/cleanup task reports preflight `READY`, the
operator's next action is exactly one normal Failback execution from Plan
`7889e625-371a-48f9-b553-54e311481170`.

## 14. Implementation And Deployment Result - 2026-08-05

Implementation completed with the route v2 contract, typed Cloud validation,
and pre-authority failure convergence described above.

- Cloud unit tests: 11 failback gate/lifecycle tests passed.
- KVM wrapper tests: 18 tests passed.
- Changed Maven modules `core`, `plugins/hypervisors/kvm`, and
  `plugins/integrations/disaster-recovery` built successfully in the WSL ext4
  build clone.
- UI production build completed and static assets were deployed while
  preserving active `WEB-INF`.
- Management received changed class files only in the active Cloud runtime
  JAR; Agents received changed core/KVM class files only.
- Management `mold` and all three `mold-agent` services are active, and
  `/client/` returns HTTP 200.
- Live schema already contained `idx_dr_failback_session_reconcile`; no new DB
  migration was required for this iteration.

Run `139` was preserved as evidence, moved to `ABORTING`, and converged through
the deployed Cloud lifecycle and FTCTL `FAILBACK_ABORT` prepare/commit path.
The resulting canonical state is:

| Record | Result |
|---|---|
| Plan 41 | `FAILED_OVER / ENABLED / TARGET` |
| Run 139 | `FAILED`, terminal source `CLOUD_LIFECYCLE` |
| Failback Session 12 | `FAILED / rollback COMPLETED / engine ABORTED` |
| FTCTL Plan | `FAILED_OVER / failback-aborted / TARGET` |
| Serving KVM VM | `Running` on host 2 |
| VMware source VM | `poweredOff`, CBT enabled, EFI |

The live `getDrFailbackPreflight` response returned `ready=true`; authority,
source runtime, target runtime, FTCTL transition, and reverse data stages all
returned `READY`. Cloud action availability returned Failback
`applicable=true, enabled=true`. The next operator action is therefore one
normal Failback execution from this Plan; no force option or manual DB change
is required.

## 2026-08-06 Retest Result Correction

The subsequent normal Failback advanced beyond route validation and completed
reverse-final checkpoint sequence 15. It then failed at
`cloud-lifecycle-gate` because FTCTL status omitted reverse durability fields
that existed in the Run state and checkpoint. Therefore the earlier statement
that the Plan was ready for another normal Failback is superseded: operator
retry is blocked until document 596 is implemented and the non-destructive
publication preflight passes.

The failure converged safely to `FAILED_OVER / TARGET`, KVM powered on, VMware
powered off, and rollback completed. Checkpoint 15 is retained for reuse.
