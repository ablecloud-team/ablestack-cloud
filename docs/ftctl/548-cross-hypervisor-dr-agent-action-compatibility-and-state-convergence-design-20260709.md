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

# Cross Hypervisor DR Agent Action Compatibility And State Convergence Design

Date: 2026-07-09

Related documents:

- [500-cross-hypervisor-dr-architecture-plan-20260630.md](500-cross-hypervisor-dr-architecture-plan-20260630.md)
- [503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md](503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md)
- [547-cross-hypervisor-dr-target-vm-materialization-contract-design-20260709.md](547-cross-hypervisor-dr-target-vm-materialization-contract-design-20260709.md)

## 1. Purpose

This document defines the structural fix for the DR Plan failure observed after
target VM creation.

Observed plan:

| Item | Value |
| --- | --- |
| Plan UUID | `d7d61d1a-6fc7-4cc5-8ba6-d58b3d029500` |
| Direction | `VMWARE_TO_KVM` |
| Failure phase | `target-materialization` |
| Cloud plan state | `ERROR` |
| Latest run state | `FAILED` |
| Error code | `DR_TARGET_VM_MATERIALIZE_FAILED` |
| Error message | `Failed to notify FTCTL_DR target materialization: Missing FTCTL_DR action` |
| Target VM | created as a stopped KVM VM |
| Target volume | created and linked to the target VM |

The data plane and Cloud target VM materialization progressed beyond the
previous failures. The blocker is now the Cloud-to-Agent-to-FTCTL reference
handshake that tells FTCTL which Cloud VM and volumes were created.

## 2. Error Cause

The active management code creates a command with:

```java
new FtctlDrActionCommand(FtctlDrActionCommand.Action.TARGET_MATERIALIZED,
        plan.getUuid(), run.getUuid());
```

The current source contains `TARGET_MATERIALIZED("dr-target-materialized")` in
`core/src/main/java/com/cloud/agent/api/FtctlDrActionCommand.java`.

The KVM Agent wrapper executes:

```java
if (command.getAction() == null) {
    return new FtctlDrActionAnswer(command, false, "Missing FTCTL_DR action");
}
script.add(command.getAction().getCliCommand());
```

Live preflight on `10.10.32.1` showed:

| Check | Result | Meaning |
| --- | --- | --- |
| `ablestack_vm_ftctl --help` contains `dr-target-materialized` | PASS | FTCTL supports the required runtime command. |
| Agent/common classpath contains `TARGET_MATERIALIZED` | FAIL | Installed Agent classes do not contain the new enum/action marker. |
| Agent/common classpath contains `dr-target-materialized` | FAIL | Installed Agent wrapper/command classes are stale or incomplete. |

Therefore the immediate failure is not VMware CBT, VDDK, target storage, or
target VM creation. It is an Agent action compatibility/deployment gap.

## 3. Design Goals

1. Detect Agent/FTCTL action capability mismatch before sync reaches target
   materialization.
2. Make the action command resilient to enum/string version drift.
3. Keep list and detail UI status consistent by using one primary state source.
4. Recover safely when a target materialization notification failure is later
   proven successful by DB and FTCTL runtime evidence.
5. Preserve existing data-plane behavior and the v2k-compatible VM hardware
   contract from the 547 design.

## 4. Layer Scope

| Layer | Required | Summary |
| --- | --- | --- |
| UI | yes | Use one DR primary state resolver for list/detail; show runtime/readiness as diagnostics only. |
| API | yes | Return capability/readiness blockers and consistent `effectivestate`. |
| Backend | yes | Add Agent/FTCTL capability preflight, target-materialized retry/recovery, and terminal state convergence. |
| Agent | yes | Add action fallback resolution and capability command wrapper. |
| FTCTL | small | Add a read-only JSON capability command; keep `dr-target-materialized` behavior unchanged. |
| DB | no migration | Use existing run steps, events, status JSON, and plan error fields. |

## 5. UI Design

Affected files:

```text
ui/src/views/infra/dr/DrPlanList.vue
ui/src/views/infra/dr/DrPlanOverview.vue
ui/src/utils/dr/state.js
ui/src/components/dr/DrStatusPill.vue
ui/public/locales/en.json
ui/public/locales/ko_KR.json
```

Add a shared resolver:

```js
export function resolveDrPrimaryState (plan = {}) {
  const effective = String(plan.effectivestate || plan.effectiveState || '').toUpperCase()
  if (effective) return effective

  const latestRun = plan.lastrun || plan.latestRun || {}
  const failedRun = String(latestRun.state || '').toUpperCase() === 'FAILED'
  const runtimeError = plan.runtimeerrorcode || latestRun.runtimeerrorcode || (failedRun ? latestRun.errorcode : null)
  const runtime = String(plan.runtimestate || latestRun.runtimestate || '').toUpperCase()
  const worker = String(latestRun.workerstate || '').toUpperCase()
  if (failedRun || runtimeError || runtime === 'ERROR' || runtime === 'FAILED' || worker === 'FAILED') {
    return 'ERROR'
  }

  const readiness = String(plan.readinessstate || plan.readinessState || '').toUpperCase()
  if (readiness === 'TARGET_READY') return 'READY'
  if (readiness === 'TARGET_MATERIALIZING') return 'SYNCING'
  if (readiness === 'ENGINE_ACCEPTED') return 'ACCEPTED'
  if (readiness === 'DEGRADED') return 'ERROR'

  return String(plan.state || readiness || 'UNKNOWN').toUpperCase()
}
```

Apply it consistently:

```js
// DrPlanList.vue
import { resolveDrPrimaryState } from '@/utils/dr/state'

effectivePlanState (plan) {
  return resolveDrPrimaryState(plan)
}

// DrPlanOverview.vue
effectiveState () {
  return resolveDrPrimaryState(this.plan)
}
```

UI display rules:

- The status pill in list and detail uses only `resolveDrPrimaryState()`.
- `runtimeState`, `readinessState`, `targetMaterializationState`,
  `runtimeStep`, and `workerState` are shown as detail diagnostics, not primary
  status.
- After create/update/start sync, the UI must refetch `getDrPlan` or
  `listDrPlans` instead of keeping an optimistic local `effectivestate`.
- When `effectivestate=ERROR` and readiness/runtime still show `SYNCING`, the
  detail page must show the error banner first and the runtime fields under
  diagnostics.

## 6. API Design

Affected files:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/response/DrResponseGenerator.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/response/dr/DrPlanResponse.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/response/dr/DrPlanSpecPreviewResponse.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/response/dr/DrRunResponse.java
```

`effectivestate` remains the only primary state for UI. Add explicit diagnostic
fields if not already present:

```java
@SerializedName("agentcapabilitystate")
private String agentCapabilityState;

@SerializedName("agentcapabilitymessage")
private String agentCapabilityMessage;

@SerializedName("targetmaterializationnotificationstate")
private String targetMaterializationNotificationState;
```

Response generation rule:

```java
private String resolveEffectivePlanState(DrPlanVO plan, DrRunVO latestRun,
        JsonObject runtime, DrPlanReadiness readiness) {
    if (latestRun != null && StringUtils.equals(latestRun.getState(), DrConstants.RUN_STATE_FAILED)) {
        return DrConstants.PLAN_STATE_ERROR;
    }
    if (hasRuntimeTerminalError(runtime)) {
        return DrConstants.PLAN_STATE_ERROR;
    }
    if (readiness != null && DrPlanReadiness.STATE_TARGET_READY.equals(readiness.getState())) {
        return DrConstants.PLAN_STATE_READY;
    }
    if (readiness != null && DrPlanReadiness.STATE_TARGET_MATERIALIZING.equals(readiness.getState())) {
        return DrConstants.PLAN_STATE_SYNCING;
    }
    return plan != null ? plan.getState() : null;
}
```

Do not make `runtimeState` override a failed latest run. Runtime state can be
newer or older than a failed Cloud control-plane operation; it is evidence, not
the UI primary state.

## 7. Backend Design

Affected files:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrConstants.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrProtectionOrchestratorImpl.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrPlanReadinessValidator.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrTargetMaterializationServiceImpl.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/adapter/ftctl/FtctlDrUnifiedActionAdapter.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/adapter/ftctl/FtctlDrRuntimeProjectionAdapter.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/dao/DrRunDao.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/dao/DrRunStepDao.java
```

### 7.1 New Error Codes

```java
public static final String ERROR_AGENT_CAPABILITY_MISMATCH = "DR_AGENT_CAPABILITY_MISMATCH";
public static final String ERROR_FTCTL_ACTION_UNAVAILABLE = "DR_FTCTL_ACTION_UNAVAILABLE";
public static final String ERROR_TARGET_MATERIALIZED_NOTIFY_FAILED = "DR_TARGET_MATERIALIZED_NOTIFY_FAILED";
public static final String EVENT_AGENT_CAPABILITY_CHECK = "AGENT_CAPABILITY_CHECK";
public static final String EVENT_TARGET_MATERIALIZATION_RECOVERED = "TARGET_MATERIALIZATION_RECOVERED";
```

### 7.2 Capability Preflight

Before `SYNC` dispatch and before `TARGET_MATERIALIZED` notification, validate
the selected coordinator/target worker:

```java
private DrAdapterResult validateAgentCapabilities(DrExecutionContext context,
        Set<FtctlDrActionCommand.Action> requiredActions) {
    Long hostId = resolveCoordinatorHostId(context.getPlan());
    FtctlDrCapabilitiesCommand command = new FtctlDrCapabilitiesCommand(
            context.getPlan().getUuid(),
            context.getRun().getUuid(),
            requiredActions.stream().map(Enum::name).collect(Collectors.toList()),
            requiredActions.stream().map(FtctlDrActionCommand.Action::getCliCommand).collect(Collectors.toList()));

    Answer answer = agentManager.easySend(hostId, command);
    if (!(answer instanceof FtctlDrCapabilitiesAnswer) || !answer.getResult()) {
        return DrAdapterResult.failure(DrConstants.ERROR_AGENT_CAPABILITY_MISMATCH,
                StringUtils.defaultIfBlank(answer != null ? answer.getDetails() : null,
                        "FTCTL_DR Agent capability check failed"),
                serializeCapabilityFailure(hostId, command, answer));
    }
    FtctlDrCapabilitiesAnswer capabilities = (FtctlDrCapabilitiesAnswer) answer;
    if (!capabilities.supportsAllRequiredActions()) {
        return DrAdapterResult.failure(DrConstants.ERROR_AGENT_CAPABILITY_MISMATCH,
                capabilities.getMissingActionsMessage(), capabilities.getCapabilitiesJson());
    }
    return DrAdapterResult.success("FTCTL_DR Agent capability check passed", capabilities.getCapabilitiesJson());
}
```

Required actions by phase:

| Phase | Required action names | Required FTCTL CLI |
| --- | --- | --- |
| Sync start | `SYNC` | `dr-sync-start` |
| Runtime polling | status command | `dr-status` |
| VMware to KVM target materialization | `TARGET_MATERIALIZED` | `dr-target-materialized` |
| Failover | `FAILOVER` | `dr-failover` |
| Failback | `FAILBACK` | `dr-failback` |
| Reprotect | `REPROTECT` | `dr-reprotect` |

### 7.3 Idempotent Target Materialized Notification

`DrTargetMaterializationServiceImpl.notifyFtctlTargetMaterialized()` must become
idempotent:

```java
private void notifyFtctlTargetMaterialized(DrPlanVO plan, DrRunVO run,
        MaterializationResult result) {
    validateTargetMaterializedCapability(plan, run);

    FtctlDrActionCommand command = new FtctlDrActionCommand(
            FtctlDrActionCommand.Action.TARGET_MATERIALIZED,
            plan.getUuid(), run.getUuid());
    command.setActionName(FtctlDrActionCommand.Action.TARGET_MATERIALIZED.name());
    command.setCliCommand(FtctlDrActionCommand.Action.TARGET_MATERIALIZED.getCliCommand());
    command.setContextParam("targetVmId", String.valueOf(result.targetVm.getId()));
    command.setContextParam("targetExternalRef", result.targetVm.getUuid());
    command.setContextParam("targetVmName", result.targetVm.getDisplayName());
    command.setContextParam("targetVolumeMapJson", buildTargetVolumeMapJson(result));
    command.setWaitForCompletion(true);

    Answer answer = agentManager.easySend(resolveCoordinatorHostId(plan), command);
    if (!isSuccessfulFtctlAnswer(answer)) {
        markNotificationFailed(plan, run, answer);
        throw new CloudRuntimeException("Failed to notify FTCTL_DR target materialization: "
                + summarizeAnswer(answer));
    }
}
```

Retry behavior:

- If target VM and volume already exist, do not recreate them.
- If `dr_replica.target_vm_id` and `dr_replica_disk.target_volume_id` are set,
  retry only the `TARGET_MATERIALIZED` notification.
- If ftctl already reports `target_materialized=true` for the same target refs,
  mark the run recovered instead of sending the command again.

### 7.4 Projection Recovery

Current logic:

```java
if (preserveTerminalMaterializationFailure(plan)) {
    return;
}
```

TO-BE:

```java
if (hasTerminalMaterializationFailure(plan)) {
    if (canRecoverMaterializationFailure(plan, status, runtime)) {
        recoverMaterializationFailure(plan, status, runtime);
    } else {
        keepPlanError(plan);
        return;
    }
}
```

Recovery gate:

```java
private boolean canRecoverMaterializationFailure(DrPlanVO plan,
        FtctlDrStatusAnswer status, JsonObject runtime) {
    if (!isTargetMaterializedRuntime(status, runtime)) {
        return false;
    }
    List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
    if (replicas.isEmpty() || replicas.get(0).getTargetVmId() == null) {
        return false;
    }
    List<DrReplicaDiskVO> disks = drReplicaDiskDao.listActiveByReplicaId(replicas.get(0).getId());
    return disks.stream().allMatch(disk -> disk.getTargetVolumeId() != null);
}
```

Recovery update:

```java
private void recoverMaterializationFailure(DrPlanVO plan,
        FtctlDrStatusAnswer status, JsonObject runtime) {
    DrRunVO latestRun = drRunDao.findLatestByPlanId(plan.getId());
    latestRun.setState(DrConstants.RUN_STATE_SUCCEEDED);
    latestRun.setErrorCode(null);
    latestRun.setErrorMessage(null);
    latestRun.setCompleted(new Date());
    drRunDao.update(latestRun.getId(), latestRun);

    recordRunStep(latestRun, "target-materialization-recovered",
            DrConstants.RUN_STEP_STATE_SUCCEEDED, 100,
            "FTCTL runtime and Cloud DB target references converged after retry");

    plan.setState(DrConstants.PLAN_STATE_READY);
    plan.setLastErrorCode(null);
    plan.setLastErrorMessage(null);
    plan.setTargetReadyAt(parseDate(status.getLastTargetDurableAt()));
    plan.setTargetReadyRpoSeconds(status.getTargetReadyRpoSeconds());
    drPlanDao.update(plan.getId(), plan);

    recordEvent(plan.getId(), latestRun.getId(),
            DrConstants.EVENT_TARGET_MATERIALIZATION_RECOVERED,
            DrConstants.EVENT_SEVERITY_INFO,
            "Target materialization notification recovered");
}
```

This recovery must not hide evidence. The failed step and recovery event remain
in `dr_run_step` and `dr_event`.

## 8. Agent Design

Affected files:

```text
core/src/main/java/com/cloud/agent/api/FtctlDrActionCommand.java
core/src/main/java/com/cloud/agent/api/FtctlDrActionAnswer.java
core/src/main/java/com/cloud/agent/api/FtctlDrErrorCodes.java
core/src/main/java/com/cloud/agent/api/FtctlDrCapabilitiesCommand.java
core/src/main/java/com/cloud/agent/api/FtctlDrCapabilitiesAnswer.java
plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrActionCommandWrapper.java
plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrCapabilitiesCommandWrapper.java
plugins/hypervisors/kvm/src/test/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlCommandWrappersTest.java
```

### 8.1 Action String Fallback

Add stable action string fields:

```java
private String actionName;
private String cliCommand;

public FtctlDrActionCommand(Action action, String planUuid, String runUuid) {
    this.action = action;
    this.actionName = action != null ? action.name() : null;
    this.cliCommand = action != null ? action.getCliCommand() : null;
    this.planUuid = planUuid;
    this.runUuid = runUuid;
}

public String getActionName() {
    return StringUtils.defaultIfBlank(actionName, action != null ? action.name() : null);
}

public String getCliCommand() {
    return StringUtils.defaultIfBlank(cliCommand, action != null ? action.getCliCommand() : null);
}
```

Add Agent-safe core error constants. Agent wrappers must not depend on the
disaster-recovery plugin's `DrConstants` because KVM hypervisor plugins are
loaded independently from the DR integration module.

```java
public final class FtctlDrErrorCodes {
    public static final String AGENT_CAPABILITY_MISMATCH = "DR_AGENT_CAPABILITY_MISMATCH";
    public static final String FTCTL_ACTION_UNAVAILABLE = "DR_FTCTL_ACTION_UNAVAILABLE";
    private FtctlDrErrorCodes() {
    }
}
```

Wrapper resolution:

```java
private ActionDescriptor resolveAction(FtctlDrActionCommand command) {
    if (command.getAction() != null) {
        return new ActionDescriptor(command.getAction().name(), command.getAction().getCliCommand());
    }
    String cli = StringUtils.trimToNull(command.getCliCommand());
    String name = StringUtils.trimToNull(command.getActionName());
    if (StringUtils.isBlank(cli) && StringUtils.equals(name, "TARGET_MATERIALIZED")) {
        cli = "dr-target-materialized";
    }
    if (StringUtils.isBlank(cli)) {
        return ActionDescriptor.missing();
    }
    return new ActionDescriptor(StringUtils.defaultIfBlank(name, cli), cli);
}
```

Then replace:

```java
if (command.getAction() == null) {
    return new FtctlDrActionAnswer(command, false, "Missing FTCTL_DR action");
}
script.add(command.getAction().getCliCommand());
```

with:

```java
ActionDescriptor action = resolveAction(command);
if (!action.isPresent()) {
    return new FtctlDrActionAnswer(command, false,
            "Missing FTCTL_DR action", null, command.getPlanUuid(), command.getRunUuid(),
            null, false, null, null, null, null, null,
            FtctlDrErrorCodes.AGENT_CAPABILITY_MISMATCH, 20, null, null);
}
script.add(action.getCliCommand());
```

### 8.2 Capabilities Wrapper

New `FtctlDrCapabilitiesCommand` is read-only:

```java
public class FtctlDrCapabilitiesCommand extends Command {
    private String planUuid;
    private String runUuid;
    private List<String> requiredActions;
    private List<String> requiredCliCommands;
    @Override
    public boolean executeInSequence() {
        return false;
    }
}
```

Agent wrapper:

```java
Script script = new Script("ablestack_vm_ftctl", 10000, logger);
script.add("dr-capabilities");
script.add("--json");
String result = script.execute(parser);
JsonObject payload = parseJson(output);

Set<String> supportedCli = parseSupportedCommands(payload);
List<String> missing = requiredCliCommands.stream()
        .filter(cmd -> !supportedCli.contains(cmd))
        .collect(Collectors.toList());

return new FtctlDrCapabilitiesAnswer(command, missing.isEmpty(),
        missing.isEmpty() ? "FTCTL_DR capabilities OK" : "Missing FTCTL_DR commands: " + missing,
        payload.toString(), missing);
```

If `dr-capabilities` is absent, the wrapper may fallback to parsing
`ablestack_vm_ftctl --help`, but `dr-capabilities --json` is the preferred
contract.

## 9. FTCTL Design

Affected files:

```text
bin/ablestack_vm_ftctl.sh
completions/ablestack_vm_ftctl
lib/ftctl/dr_runtime.sh
bin/ablestack_vm_ftctl_selftest.sh
```

Add a read-only command:

```bash
ftctl_dr_capabilities() {
  ftctl_json_begin
  ftctl_json_kv "command" "dr-capabilities"
  ftctl_json_kv "result" "ok"
  ftctl_json_array "supported_commands" \
    "dr-sync-start" \
    "dr-status" \
    "dr-target-materialized" \
    "dr-failover" \
    "dr-failback" \
    "dr-reprotect" \
    "dr-release"
  ftctl_json_kv "runtime_schema_version" "2026-07-09-target-materialized"
  ftctl_json_end
}
```

CLI dispatch:

```bash
dr-capabilities)
  ftctl_dr_capabilities
  ;;
```

This command must:

- be lock-free;
- never mutate runtime state;
- never require a plan/run;
- return JSON with stable command names;
- be safe to run during every sync preflight.

`dr-target-materialized` remains unchanged as the state-mutating Cloud-to-FTCTL
reference handshake.

## 10. DB Design

No schema migration is required for the immediate fix.

Use existing persistence points:

| Table | Existing fields | Rule |
| --- | --- | --- |
| `dr_run` | `state`, `error_code`, `error_message`, `last_status_json` | Record capability mismatch and notification failure as terminal run failures. |
| `dr_run_step` | `name`, `state`, `progress`, `details_json` | Add `agent-capability-check`, `target-materialization-notify`, and `target-materialization-recovered` steps. |
| `dr_event` | `event_type`, `severity`, `details_json` | Persist capability check, notification failure, and recovery events. |
| `dr_plan` | `state`, `last_error_code`, `last_error_message`, `target_ready_at`, `target_ready_rpo_seconds` | Keep plan error until DB and FTCTL runtime converge; clear only through recovery gate. |
| `dr_replica` | `target_vm_id`, `target_external_ref`, `runtime_state_json` | Target VM reference is the Cloud side of the convergence gate. |
| `dr_replica_disk` | `target_volume_id`, `target_disk_ref`, `details_json` | Target volume references must all be present before recovery. |

Optional later migration:

```sql
ALTER TABLE cloud.dr_plan
  ADD COLUMN agent_capability_json text NULL;
```

This is deferred because run steps and events already store the diagnostic
payload without changing schema.

## 11. Deployment Verification Contract

After Cloud and Agent deployment, verify every target/coordinator host:

```bash
grep -RIl 'TARGET_MATERIALIZED' /usr/share/cloudstack-agent /usr/share/cloudstack-common
grep -RIl 'dr-target-materialized' /usr/share/cloudstack-agent /usr/share/cloudstack-common
ablestack_vm_ftctl dr-capabilities --json
```

Expected:

```json
{
  "result": "ok",
  "supported_commands": [
    "dr-sync-start",
    "dr-status",
    "dr-target-materialized"
  ]
}
```

The deployment must fail-fast if:

- Agent classpath does not contain `TARGET_MATERIALIZED`;
- Agent classpath does not contain `dr-target-materialized`;
- FTCTL capabilities do not include `dr-target-materialized`;
- Cloud UI active bundle still uses divergent list/detail status logic.

## 12. Tests

Cloud unit tests:

```java
@Test
public void actionCommandCarriesEnumAndStringFallback() {}

@Test
public void actionWrapperUsesCliCommandWhenEnumIsNull() {}

@Test
public void capabilityPreflightFailsWhenTargetMaterializedIsMissing() {}

@Test
public void syncStartBlocksBeforeDispatchWhenAgentCapabilitiesAreStale() {}

@Test
public void materializationRetryDoesNotRecreateExistingTargetVm() {}

@Test
public void projectionKeepsTerminalFailureUntilRuntimeAndDbConverge() {}

@Test
public void projectionRecoversMaterializationFailureWhenRuntimeAndDbConverge() {}

@Test
public void responseEffectiveStatePrefersLatestRunFailureOverRuntimeSyncing() {}
```

UI tests:

```js
it('uses the same primary state resolver in list and detail')
it('shows ERROR when effectiveState is ERROR even if runtimeState is SYNCING')
it('shows runtime/readiness as diagnostics instead of primary status')
```

FTCTL selftests:

```bash
selftest_dr_capabilities_json_contains_target_materialized
selftest_dr_capabilities_is_lock_free
selftest_dr_target_materialized_still_updates_runtime_refs
```

## 13. Release And Changed-Class Deployment Closure

The deployment and cleanup test exposed two additional compatibility edges:

1. Cloud changed-class deployment must include nested wrapper classes. For this
   feature that means the following classes are deployed together:
   - `LibvirtFtctlDrActionCommandWrapper.class`
   - `LibvirtFtctlDrActionCommandWrapper$ActionDescriptor.class`
   - `LibvirtFtctlDrCapabilitiesCommandWrapper.class`
   - `LibvirtFtctlDrCapabilitiesCommandWrapper$CapabilitySnapshot.class`
2. `RELEASE` is a terminal cleanup action. When ftctl reports a released
   runtime, Cloud projection must remove active target projection rows so a
   deleted plan is not blocked by stale DR resources.

Release projection handling:

```java
if (isReleasedRuntime(status)) {
    cleanupReleasedRuntimeProjection(plan);
    reconcileAcceptedRun(run, status);
    return;
}
```

`cleanupReleasedRuntimeProjection(plan)` uses existing soft-delete semantics:

```java
softDeleteActiveReplicaDisks(plan.getId());
softDeleteActiveReplicas(plan.getId());
softDeleteActiveRestorePoints(plan.getId());
plan.setState(DrConstants.PLAN_STATE_NEW);
plan.setActiveSide(DrConstants.ACTIVE_SIDE_SOURCE);
plan.setTargetReadyAt(null);
plan.setTargetReadyRpoSeconds(null);
plan.setLastTargetDurableAt(null);
plan.setLastErrorCode(null);
plan.setLastErrorMessage(null);
planDao.update(plan.getId(), plan);
```

No DB schema change is required. The existing `removed` columns on
`dr_replica`, `dr_replica_disk`, and `dr_restore_point` are the persistence
boundary for release cleanup.

## 14. AS-IS / TO-BE Summary

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Root cause | Cloud can create `TARGET_MATERIALIZED`, but installed Agent may not understand it. | Sync preflight checks Agent and FTCTL capabilities before dispatch/materialization. |
| Agent action contract | Wrapper fails immediately when enum action is null. | Command carries enum plus string fallback; wrapper resolves action by enum, action name, or CLI command. |
| Changed-class deployment | Outer wrapper class can be deployed without required nested classes, causing `NoClassDefFoundError`. | Changed-class payload includes wrapper outer and nested classes as one deployment unit. |
| FTCTL capability | Capability is inferred manually from help/grep during troubleshooting. | `ablestack_vm_ftctl dr-capabilities --json` exposes supported commands. |
| Failure timing | Mismatch is found after seed transfer and target VM creation. | Mismatch is blocked before sync or before target materialization notification. |
| Target notification retry | Existing target VM can remain created while run is failed. | Retry reuses existing target VM/volumes and only repeats `dr-target-materialized`. |
| Projection recovery | `preserveTerminalMaterializationFailure()` keeps plan in ERROR forever. | Recovery gate clears the error only when Cloud DB refs and FTCTL runtime refs both prove target ready. |
| Release cleanup | `RELEASE` can succeed in ftctl while active Cloud projection rows still block `deleteDrPlan`. | Released runtime projection soft-deletes active replica/disk/restore rows and resets the plan to source-side `NEW`. |
| UI list/detail | List can show `ERROR` while detail appears `SYNCING`. | List and detail share one `effectivestate`-based primary state resolver. |
| API state | Runtime/readiness and failed run can compete in UI interpretation. | API exposes `effectivestate` as primary and runtime/readiness as diagnostics. |
| DB | No schema issue; failure/recovery evidence may be scattered. | Existing run steps and events store capability, notification, and recovery evidence. |
| ftctl data plane | Works; `dr-target-materialized` command is present. | Data plane stays unchanged; a read-only capability command is added. |

## 15. Implementation Order

1. Add FTCTL `dr-capabilities --json` and selftests.
2. Add Cloud Agent command/answer classes for capabilities.
3. Add KVM Agent capability wrapper and action string fallback.
4. Add backend capability preflight before sync dispatch and target notification.
5. Make target materialization notification idempotent and retry-safe.
6. Add projection recovery gate for notification-only materialization failure.
7. Add release projection cleanup for released ftctl runtime.
8. Unify UI list/detail primary state resolver.
9. Add unit/UI/selftests.
10. Build changed Cloud modules and qemu package.
11. Deploy Cloud changed classes/UI and qemu/Agent changes, then run deployment
    verification commands from section 11.

## 16. 2026-07-10 Corrective Design: Classpath Authority, Source Hardware, And Monotonic State

### 16.1 Scope And Live Preflight Evidence

This section supersedes any earlier assumption that the action compatibility
and source hardware propagation problems were closed by the 2026-07-09
deployment alone.

Read-only preflight for plan
`0de0b865-8642-4cac-a5d7-fc864633d062` proved three independent defects:

| Evidence | Observed value | Result |
| --- | --- | --- |
| `dr_plan` | `state=ERROR`, `last_run_id=28`, `DR_TARGET_VM_MATERIALIZE_FAILED` | FAIL |
| `dr_run` | `state=FAILED`, `current_step_name=target-materialization` | FAIL |
| `dr_run_step` | two `runtime-projection` rows still `RUNNING`; `target-materialization=FAILED` | INCONSISTENT |
| Target VM | VM id `240`, state `Stopped`, `boot.mode=LEGACY` | FAIL |
| Target VM I/O | `io.policy=io_uring`, `iothreads=true` | PASS |
| FTCTL package | `dr-capabilities` contains `dr-target-materialized` | PASS |
| Agent `cloud-core` JAR | action enum contains `TARGET_MATERIALIZED`; string fallback present | PASS |
| Agent KVM plugin JAR | duplicate action enum is stale; string fallback absent | FAIL |
| Source VMware VM | `vm-4486`, `firmware=efi`, `efiSecureBootEnabled=true` | PASS at source |
| Plan mapping | no canonical `source.hardware`; target resolved to BIOS/LEGACY | FAIL |

The vCenter check used the vCenter account only. No ESXi host account was
required. The source result also matches the proven v2k inventory mapping in
`LibvirtAblestackV2KListVmwareVmsCommandWrapper`:

```text
config.firmware                         -> bootType
config.bootOptions.efiSecureBootEnabled -> bootMode=secure
config.guestId                          -> operatingSystemId
config.hardware.numCPU                  -> cpuCores
config.hardware.memoryMB                -> memory
```

The failure therefore is not a vCenter data problem. It is a Cloud DR
collection, persistence, classpath, and state-authority problem.

### 16.2 Root Cause Decomposition

| Root cause | Code-level mechanism | User-visible symptom |
| --- | --- | --- |
| Agent API class shadowing | The KVM plugin JAR contains an older copy of `FtctlDrActionCommand` and its nested `Action` enum. The wrapper resolves actions from that local enum instead of the newer `cloud-core` class. | FTCTL supports `dr-target-materialized`, but Cloud reports `missing actions=[TARGET_MATERIALIZED]`. |
| Enum-derived capability mapping | `LibvirtFtctlDrCapabilitiesCommandWrapper.toActionNames()` loops over `Action.values()`. A stale enum cannot map a valid CLI command that it does not know. | False-negative capability result after target VM creation. |
| Source hardware not collected | `DrVmwareInventoryClient.toVirtualMachineOptions()` only stores VM ref, name, power, CPU, and memory. It never obtains the VMware config object or Secure Boot flag. | Source EFI/Secure is absent from preview and persisted Plan JSON. |
| Target resolver has unsafe fallback | `DrTargetHardwareResolver` defaults missing source firmware to `BIOS` and missing Secure Boot to `LEGACY`. | A valid EFI/Secure source silently becomes a LEGACY target. |
| Mapping builder is target-only | `DrPlanGuidedSpecBuilder.buildMapping()` writes `target.hardware` but does not write canonical `source.hardware`. | Materialization cannot distinguish unknown hardware from BIOS/LEGACY. |
| Split state writers | Executor, runtime projector, materializer, readiness validator, and response generator independently interpret or mutate state. | Plan list can transiently show `NEW -> ERROR -> SYNCING`; terminal steps remain RUNNING. |
| Runtime is not strictly correlated | Runtime contains a run UUID, but projection does not consistently reject stale or mismatched runtime before changing current state. | Old `SYNCING` data can compete with a failed current run. |

### 16.3 Design Invariants

The implementation must enforce all of the following:

1. CLI command support is the wire-level authority. Java enum names are
   diagnostics and compile-time convenience, not the capability truth source.
2. A VMware-to-KVM Plan cannot become execution-ready until source firmware
   and Secure Boot are known from vCenter or explicitly rejected as
   unsupported.
3. The UI never owns source hardware truth. Preview/create/update re-resolve
   source hardware server-side from the selected site and VM reference.
4. Missing source boot metadata is `UNKNOWN`, never implicit BIOS/LEGACY.
5. The exact canonical source/target hardware contract used for preview is
   persisted and is the contract used by materialization.
6. Runtime projection applies only when `plan_uuid` and `run_uuid` match the
   currently projected Plan/run.
7. A terminal run is immutable. Recovery creates a new reconcile/retry run and
   never rewrites an earlier failed run to `SUCCEEDED`.
8. When a run becomes terminal, no step in that run may remain `RUNNING`.
9. List and detail views consume the same backend `effectivestate`; runtime
   state remains diagnostic data.

### 16.4 Canonical Hardware Contract

Add a backend-owned DTO:

```java
public final class DrSourceVmHardware {
    private String sourceVmRef;
    private String firmware;             // EFI or BIOS
    private Boolean secureBootEnabled;
    private String guestId;
    private Integer cpuCount;
    private Long memoryMiB;
    private String rootDiskController;
    private String dataDiskController;
    private Date observedAt;
    private String inventorySource;       // VCENTER_VIM or MOLD_API
    private String fingerprint;           // SHA-256 of canonical JSON
}
```

Canonical JSON persisted in `dr_plan.mapping_json`:

```json
{
  "schemaVersion": "DR_PLAN_GUIDED_SPEC_V2",
  "source": {
    "vm": {
      "externalRef": "vm-4486",
      "guestId": "rockylinux_64Guest"
    },
    "hardware": {
      "firmware": "EFI",
      "secureBoot": true,
      "cpuCount": 2,
      "memoryMiB": 4096,
      "rootDiskController": "scsi",
      "dataDiskController": "scsi",
      "observedAt": "2026-07-10T00:00:00Z",
      "inventorySource": "VCENTER_VIM",
      "fingerprint": "sha256:<canonical-json-digest>"
    }
  },
  "target": {
    "hardware": {
      "bootType": "UEFI",
      "bootMode": "SECURE",
      "rootDiskController": "scsi",
      "dataDiskController": "scsi",
      "ioThreadsEnabled": true,
      "ioPolicy": "io_uring"
    }
  }
}
```

The fingerprint excludes `observedAt` so a refresh with unchanged hardware
does not change the execution contract.

### 16.5 VMware Inventory Implementation

Affected files:

```text
plugins/integrations/disaster-recovery/pom.xml
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/inventory/DrVmwareInventoryClient.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/inventory/DrPlanInventoryServiceImpl.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/inventory/DrSourceVmHardware.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/inventory/DrSourceHardwareInventoryService.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/inventory/DrSourceHardwareInventoryServiceImpl.java
```

Use the existing Cloud VMware SDK model from `cloud-vmware-base` and
`vmware-vim25`. Do not invoke the v2k migration engine and do not request ESXi
credentials. The selected DR site vCenter endpoint and credential are the only
source authority.

The implementation should reuse the same property mapping already proven by
`VmwareHelper.getUnmanagedInstance()` and the v2k Agent inventory wrapper:

```java
ManagedObjectReference vmMor = new ManagedObjectReference();
vmMor.setType("VirtualMachine");
vmMor.setValue(vmRef);

VirtualMachineConfigInfo config =
        (VirtualMachineConfigInfo) vimClient.getDynamicProperty(vmMor, "config");
VirtualMachineBootOptions boot = config != null ? config.getBootOptions() : null;

hardware.setFirmware(normalizeFirmware(config != null ? config.getFirmware() : null));
hardware.setSecureBootEnabled(boot != null
        ? Boolean.TRUE.equals(boot.isEfiSecureBootEnabled()) : null);
hardware.setGuestId(config != null ? config.getGuestId() : null);
hardware.setCpuCount(config != null && config.getHardware() != null
        ? config.getHardware().getNumCPU() : null);
hardware.setMemoryMiB(config != null && config.getHardware() != null
        ? config.getHardware().getMemoryMB() : null);
```

The SDK connection must honor `dr_site_credential.tls_verify`. It must not
install a process-wide trust-all verifier. If the existing `VmwareClient`
cannot honor per-site TLS policy, introduce a DR-scoped client factory instead
of changing JVM-global HTTPS behavior.

`DrVmwareInventoryClient` keeps REST for the fast VM list and disk/NIC list.
After a source VM is selected, `DrSourceHardwareInventoryService` performs the
detailed VIM query. The result is added to the selected `SOURCE_WORKLOAD`
option details and returned as a first-class `sourcehardware` object in
`DrPlanInventoryResponse`.

### 16.6 API Contract

Affected files:

```text
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/DiscoverDrPlanInventoryCmd.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/PreviewDrPlanSpecCmd.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/CreateDrPlanCmd.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/UpdateDrPlanCmd.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/response/dr/DrPlanInventoryResponse.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/response/dr/DrPlanSpecPreviewResponse.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/response/dr/DrPlanResponse.java
```

Response additions:

```json
{
  "sourcehardware": {
    "firmware": "EFI",
    "secureboot": true,
    "guestid": "rockylinux_64Guest",
    "cpucount": 2,
    "memorymib": 4096,
    "rootdiskcontroller": "scsi",
    "datadiskcontroller": "scsi",
    "fingerprint": "sha256:...",
    "observedat": "..."
  },
  "resolvedtargethardware": {
    "boottype": "UEFI",
    "bootmode": "SECURE",
    "iothreadsenabled": true,
    "iopolicy": "io_uring"
  }
}
```

Client-provided `targetboottype` and `targetbootmode` remain optional override
requests, but the backend validates them against the freshly collected source
contract. The client cannot submit `sourcehardware` as trusted input.

`previewDrPlanSpec`, `createDrPlan`, and `updateDrPlan` call the same method:

```java
DrSourceVmHardware source = sourceHardwareInventory.resolve(
        sourceSite, sourceCredential, sourceExternalRef);
DrResolvedTargetHardware target = targetHardwareResolver.resolve(
        source, guidedSpec, placement);
readinessValidator.validateSourceAndTargetHardware(source, target, placement);
```

New blocker/error codes:

| Code | Meaning |
| --- | --- |
| `SOURCE_HARDWARE_INVENTORY_REQUIRED` | Detailed source hardware was not collected. |
| `SOURCE_FIRMWARE_UNRESOLVED` | Source firmware is neither EFI nor BIOS. |
| `SOURCE_SECURE_BOOT_UNRESOLVED` | EFI source Secure Boot state could not be read. |
| `SOURCE_HARDWARE_CHANGED` | Source fingerprint changed after Plan creation and requires re-preview. |
| `TARGET_BOOT_CONTRACT_INCOMPATIBLE` | Requested target boot mode contradicts the source or target capability. |

### 16.7 Guided Spec And Materialization

Affected files:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrPlanGuidedSpecBuilder.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrTargetHardwareResolver.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrPlanReadinessValidator.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrTargetMaterializationServiceImpl.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrReplicaDeployVMVolumeCmd.java
```

`DrPlanGuidedSpecBuilder.buildMapping()` must add `source` before resolving
`target`:

```java
JsonObject source = sourceHardware.toJsonObject();
mapping.add("source", source);

DrResolvedTargetHardware targetHardware =
        targetHardwareResolver.resolve(sourceHardware, spec, placement);
target.add("hardware", targetHardware.toJsonObject());
```

Resolver rules:

```java
if (source.getFirmware() == null || source.getSecureBootEnabled() == null) {
    addBlockingReason(...);
    return unresolvedHardware();
}
BootType bootType = source.isEfi() || source.isSecureBootEnabled()
        ? BootType.UEFI : BootType.BIOS;
BootMode bootMode = bootType == BootType.UEFI && source.isSecureBootEnabled()
        ? BootMode.SECURE : BootMode.LEGACY;
```

Delete the implicit `BIOS` fallback for VMware sources. BIOS/LEGACY is valid
only when vCenter explicitly reports BIOS or when an ABLESTACK source explicitly
contains that boot detail.

Before creating or reusing a target VM, materialization compares the current
source fingerprint with the persisted fingerprint. If it changed, fail before
VM creation with `SOURCE_HARDWARE_CHANGED` and require Plan preview/update.

The target VM deploy request must carry:

```text
boottype=UEFI
bootmode=SECURE
details[UEFI]=SECURE
details[boot.mode]=SECURE
details[io.policy]=io_uring
details[iothreads]=true
```

Materialization then verifies the persisted target VM details before sending
`TARGET_MATERIALIZED`. A mismatched existing target VM is not reusable and must
produce `TARGET_VM_HARDWARE_MISMATCH` instead of silently continuing.

### 16.8 Agent Capability Contract And Classpath Guard

Affected files:

```text
core/src/main/java/com/cloud/agent/api/FtctlDrActionCommand.java
core/src/main/java/com/cloud/agent/api/FtctlDrCapabilitiesAnswer.java
plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrCapabilitiesCommandWrapper.java
plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrActionCommandWrapper.java
plugins/hypervisors/kvm/src/test/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlCommandWrappersTest.java
```

Replace enum-derived capability resolution with a stable wire registry:

```java
private static final Map<String, String> ACTION_TO_CLI;
static {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("SYNC", "dr-sync-start");
    map.put("PAUSE_SYNC", "dr-sync-pause");
    map.put("RESUME_SYNC", "dr-sync-resume");
    map.put("TARGET_MATERIALIZED", "dr-target-materialized");
    map.put("RELEASE", "dr-release");
    ACTION_TO_CLI = Collections.unmodifiableMap(map);
}
```

Capability success uses required CLI commands as the authoritative check:

```java
boolean actionSupported(String action, Set<String> supportedCli) {
    String cli = ACTION_TO_CLI.get(normalize(action));
    return cli != null && containsIgnoreCase(supportedCli, cli);
}
```

`requiredActions` remains in the response for diagnostics. It must not turn a
supported required CLI command into failure merely because a locally loaded
enum is stale.

The answer must expose class provenance:

```java
answer.setActionContractVersion(FtctlDrActionCommand.ACTION_CONTRACT_VERSION);
answer.setActionCommandCodeSource(codeSource(FtctlDrActionCommand.class));
answer.setWrapperCodeSource(codeSource(getClass()));
```

At Agent startup or first capability call, log a warning when the API command
class is loaded from the KVM plugin JAR instead of `cloud-core`.

Build/deployment invariant:

- `FtctlDrActionCommand*.class` has one authoritative packaged owner:
  `cloud-core`.
- The KVM plugin JAR must not package or retain stale copies under
  `com/cloud/agent/api/`.
- Until a full package removes duplicates, changed-class deployment must update
  every classpath copy of the outer and nested classes atomically.
- A deployment verification script scans every Agent JAR and fails when class
  hashes differ.

### 16.9 FTCTL Contract

FTCTL already passes the required CLI capability preflight. Preserve the
existing commands and add contract metadata only:

```json
{
  "command": "dr-capabilities",
  "action_contract_version": "2026-07-10",
  "runtime_schema_version": "20260710",
  "supported_commands": [
    "dr-sync-start",
    "dr-target-materialized",
    "dr-status",
    "dr-release"
  ]
}
```

`dr-plan-apply` receives the canonical non-secret hardware contract and stores
it in the plan runtime profile:

```json
{
  "source": {
    "hardware": {
      "firmware": "EFI",
      "secureBoot": true,
      "fingerprint": "sha256:..."
    }
  },
  "target": {
    "hardware": {
      "bootType": "UEFI",
      "bootMode": "SECURE",
      "ioPolicy": "io_uring",
      "ioThreadsEnabled": true
    }
  }
}
```

`dr-status` echoes the fingerprint and resolved target hardware. It does not
create the Cloud VM and does not invoke v2k. The v2k migration lifecycle remains
untouched; only its validated VMware field semantics are reused.

### 16.10 Monotonic Run And Plan State

Add one backend state authority:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/state/DrStateTransitionPolicy.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/state/DrPlanStateProjector.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/state/DrRunTerminalizer.java
```

All executor, projection, and materialization code calls this service instead
of directly setting `dr_plan.state` or terminal `dr_run.state`.

Effective-state precedence:

| Priority | Condition | Effective state |
| --- | --- | --- |
| 1 | Plan removed | `REMOVED` |
| 2 | Current run is non-terminal and correlated | `QUEUED`, `PREPARING`, `SYNCING`, or `TARGET_MATERIALIZING` |
| 3 | Current/latest run is terminal `FAILED` | `ERROR` |
| 4 | Correlated runtime has terminal error | `ERROR` |
| 5 | Cloud target refs and FTCTL target refs converge | `READY` |
| 6 | No run exists | persisted Plan state, normally `NEW` |

Runtime correlation gate:

```java
boolean isCurrentRuntime(DrPlanVO plan, DrRunVO run, JsonObject runtime) {
    return plan != null && run != null
            && plan.getId().equals(run.getPlanId())
            && plan.getLastRunId().equals(run.getId())
            && plan.getUuid().equals(firstString(runtime, "plan_uuid"))
            && run.getUuid().equals(firstString(runtime, "run_uuid"));
}
```

Mismatched runtime is recorded as `STALE_RUNTIME_IGNORED` and cannot mutate
Plan/run state.

Terminalization:

```java
@DB
public void failRun(long runId, String code, String message) {
    DrRunVO run = lockRun(runId);
    if (run.isTerminal()) {
        return;
    }
    run.fail(code, message, now());
    drRunDao.update(run.getId(), run);
    drRunStepDao.closeRunningSteps(runId, STEP_STATE_FAILED,
            "Run terminated before step completion", now());
    projectPlanFromLatestRun(run.getPlanId());
}
```

Do not recover by changing a failed run to success. Notification-only recovery
creates a new run with type `RECONCILE_TARGET_MATERIALIZATION`, reuses existing
target VM/volume references, sends `dr-target-materialized`, and finishes that
new run. The failed run and its steps remain immutable evidence.

Required DAO additions:

```java
DrRunStepVO findLatestByRunIdAndStepName(long runId, String stepName);
int closeRunningSteps(long runId, String terminalState, String message, Date completed);
```

`runtime-projection` is upserted by `(run_id, step_name)` for the current
attempt. Repeated polling updates one row instead of creating duplicates.

### 16.11 UI Design

Affected files:

```text
ui/src/utils/dr/planState.js
ui/src/views/infra/dr/DrPlanList.vue
ui/src/views/infra/dr/DrPlanOverview.vue
ui/src/components/dr/DrRunProgress.vue
ui/public/locales/en.json
ui/public/locales/ko_KR.json
```

The Plan dialog review panel shows read-only server-projected values:

```text
Source boot       EFI / Secure Boot
Target boot       UEFI / Secure
Disk controller   SCSI
I/O policy        io_uring
Hardware checked  <timestamp>
```

If source hardware is unresolved, the confirm/start-sync action is disabled and
the relevant blocker is shown in the source workload section. The UI does not
offer a free-form Secure Boot guess.

List and detail primary state use only:

```js
export function resolveDrPlanState (plan = {}) {
  return String(plan.effectivestate || plan.state || 'UNKNOWN').toUpperCase()
}
```

`runtimestate`, `readinessstate`, and `targetmaterializationstate` are displayed
as secondary diagnostics. An active retry run may show `Preparing` or
`Synchronizing`; a previous Plan error must not flash between `NEW` and the
accepted current run state.

### 16.12 DB Persistence And Migration Decision

The hardware fix requires no new table. Persist the canonical source and target
hardware in existing `dr_plan.mapping_json` and the execution evidence in
existing run step/event JSON.

| Persistence | Content |
| --- | --- |
| `dr_plan.mapping_json.source.hardware` | vCenter-observed source hardware and fingerprint |
| `dr_plan.mapping_json.target.hardware` | resolved target boot/controller/I/O contract |
| `dr_run_step.details_json` | capability provenance, class sources, and applied hardware fingerprint |
| `dr_event.details_json` | stale runtime rejection, hardware-change block, and reconcile result |
| `dr_replica.runtime_state_json` | correlated FTCTL runtime and target hardware fingerprint |
| `vm_instance_details` | actual `boot.mode`, `UEFI`, `io.policy`, and `iothreads` applied to the target VM |

No schema migration is required for this corrective pass. A later migration may
add a uniqueness constraint for `(run_id, step_name, attempt_no)`, but it must
first preserve historical duplicate rows and define explicit attempt semantics.

### 16.13 Validation Matrix

Unit and module tests:

```text
DrVmwareInventoryClientTest.efiSecureBootMapsFromVimConfig
DrPlanGuidedSpecBuilderTest.persistsCanonicalSourceAndTargetHardware
DrTargetHardwareResolverTest.missingVmwareFirmwareBlocksInsteadOfDefaultingBios
DrTargetHardwareResolverTest.efiSecureMapsToUefiSecure
DrTargetMaterializationServiceTest.rejectsChangedHardwareFingerprint
LibvirtFtctlCommandWrappersTest.cliCapabilityPassesWithStaleEnumSimulation
DrStateTransitionPolicyTest.activeCurrentRunWinsOverPreviousPlanError
DrStateTransitionPolicyTest.failedRunWinsOverRuntimeSyncing
DrRunTerminalizerTest.closesAllRunningSteps
DrRuntimeProjectionAdapterTest.ignoresMismatchedRunUuid
```

Live preflight before the next full sync:

1. vCenter inventory for `vm-4486` returns `EFI`, Secure Boot `true`, CPU `2`,
   and memory `4096 MiB`.
2. `previewDrPlanSpec` returns the same source values and target
   `UEFI/SECURE`.
3. Persisted `mapping_json.source.hardware.fingerprint` equals the preview
   fingerprint.
4. Every Agent JAR containing `FtctlDrActionCommand` has the same class hash,
   or only `cloud-core` contains it.
5. `dr-capabilities` and Agent capability response both report
   `dr-target-materialized` supported.
6. Start sync returns asynchronously and creates one non-terminal current run.
7. During polling, exactly one current `runtime-projection` step is RUNNING.
8. After target VM creation, DB details contain `boot.mode=SECURE`,
   `io.policy=io_uring`, and `iothreads=true`.
9. The KVM domain, when started, contains the secure OVMF loader and expected
   disk I/O settings.
10. `TARGET_MATERIALIZED` succeeds and Plan/API/UI converge to `READY` without
    an intermediate false `ERROR`.

### 16.14 AS-IS / TO-BE Summary

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Failure cause | FTCTL supports the command, but a stale Agent enum shadows the new contract. | CLI command registry is authoritative and Agent reports class provenance. |
| Agent packaging | `cloud-core` and KVM plugin contain different versions of the same API class. | One packaged owner in `cloud-core`; deployment rejects divergent duplicate hashes. |
| Capability check | Supported commands are converted through local `Action.values()`. | Required CLI commands are checked directly; action names are diagnostics. |
| VMware authority | Source VM list is REST-only and omits firmware/Secure Boot. | Detailed VIM query uses vCenter credentials and v2k-compatible property mapping. |
| Source credentials | Risk of confusing vCenter and ESXi credentials. | Only vCenter endpoint/account is used; no ESXi account is requested or stored. |
| Source hardware persistence | `mapping_json` has no canonical source hardware. | `mapping_json.source.hardware` stores observed values and SHA-256 fingerprint. |
| Boot fallback | Missing metadata silently becomes BIOS/LEGACY. | Missing VMware boot metadata blocks preview/start; EFI/Secure maps to UEFI/SECURE. |
| Target VM reuse | Existing target may be reused without hardware verification. | Target details/fingerprint must match before reuse or notification. |
| v2k relationship | DR behavior may duplicate or invoke migration semantics. | v2k engine remains untouched; only its proven inventory field semantics are reused. |
| ftctl role | FTCTL has transfer/checkpoint state but no explicit hardware contract echo. | FTCTL stores and echoes non-secret hardware fingerprint; Cloud still creates the VM. |
| Run terminality | Failed run may be rewritten during recovery and steps may stay RUNNING. | Terminal runs are immutable; retry/reconcile is a new run and terminalization closes steps. |
| State display | Multiple state writers produce `NEW -> ERROR -> SYNCING` flashes. | One state projector applies correlated, monotonic precedence; list/detail use `effectivestate`. |
| DB | Evidence is present but source contract and correlation are incomplete. | Existing JSON fields persist source/target hardware, provenance, and correlated runtime evidence. |

### 16.15 Implementation Order

1. Remove/fix Agent API class duplication and make CLI capability checks
   independent of the local enum.
2. Add VMware VIM source hardware collection and canonical DTO/fingerprint.
3. Add server-side source re-resolution to discover/preview/create/update.
4. Persist `mapping_json.source.hardware` and resolve target hardware without
   unsafe defaults.
5. Add target VM hardware mismatch guard before reuse and notification.
6. Add FTCTL hardware contract/fingerprint echo without invoking v2k.
7. Centralize Plan/run state transitions, runtime correlation, and step
   terminalization.
8. Reduce UI state resolution to backend `effectivestate` and add read-only
   source/target hardware review.
9. Run module/unit/UI/FTCTL tests, then perform changed-class and package
   deployment verification.
10. Clean the failed Plan and mismatched LEGACY target VM only after preserving
    evidence, then create a fresh Plan for end-to-end validation.

## 17. Implementation And Deployment Result (2026-07-10)

This section records the implementation that replaced the failed
`0de0b865-8642-4cac-a5d7-fc864633d062` run path. It is the implementation
baseline for the next clean Plan test.

### 17.1 Implemented Contract

| Layer | Implemented behavior |
| --- | --- |
| UI | Preview renders source boot, target boot, and target I/O policy from server-projected fields. Active sync state takes precedence over a stale Plan error. |
| API | `discoverDrPlanInventory` and `previewDrPlanSpec` expose canonical source and resolved target hardware. VMware source hardware is re-read server-side. |
| Backend | `DrSourceVmHardware` computes a SHA-256 fingerprint from stable hardware fields. Guided mapping, readiness, materialization, and runtime projection reject missing, changed, or stale hardware contracts. |
| Agent | `FtctlDrSourceHardwareCommand` runs a vCenter-only `govc vm.info -json` query on the selected KVM worker. API and secret values are supplied through the process environment and command logging is disabled. |
| Agent packaging | `FtctlDrActionCommand` is owned by `cloud-core`. Stale duplicate FTCTL API classes are removed from the deployed KVM plugin JAR. Capability checks use the stable CLI command registry and report class provenance. |
| FTCTL | Capability schema `20260710`, action contract `2026-07-10`, and `hardware-contract-projection` are reported. `dr-status` echoes source firmware, Secure Boot, hardware fingerprint, target boot mode, `io_uring`, and iothreads. |
| DB | Existing `mapping_json`, run step JSON, replica runtime JSON, and VM details remain the persistence contract. No schema migration is required. |

The DR path does not invoke the v2k migration engine and does not request ESXi
host credentials. It reuses the v2k-compatible `govc` binary and its validated
vCenter property semantics only.

### 17.2 Live Preflight Evidence

The deployed inventory path was called with source site, target site, and
`sourceexternalref=vm-4486`. The management server sent
`FtctlDrSourceHardwareCommand` to `ablecube32-1`, and the Agent returned:

```json
{
  "sourceVmRef": "vm-4486",
  "firmware": "EFI",
  "secureBoot": true,
  "guestId": "rockylinux_64Guest",
  "cpuCount": 2,
  "memoryMiB": 4096,
  "rootDiskController": "scsi",
  "dataDiskController": "scsi",
  "inventorySource": "VCENTER_GOVC_AGENT",
  "fingerprint": "sha256:97644be81c916013a1d01d1fe7c5dd1c332cb1174aa1d645995ee010da726c8f"
}
```

The API result contained no blocking reasons. This proves the current deployed
path resolves EFI and Secure Boot through vCenter before Plan execution rather
than falling back to LEGACY.

### 17.3 Build And Test Evidence

| Scope | Result |
| --- | --- |
| Maven changed modules | `core`, KVM hypervisor plugin, and disaster-recovery integration compiled and packaged successfully from the WSL ext4 clone. |
| DR unit tests | 12 passed: source hardware fingerprint and runtime projection coverage. |
| KVM wrapper tests | 9 passed, including capability compatibility. |
| FTCTL self-test | Targeted DR runtime profile/status/cancel case passed. |
| UI | Production build succeeded; deployed bundle contains hardware preview and asynchronous progress markers. |
| FTCTL package | GitHub Actions run `29067846997` produced `ablestack_vm_ftctl-0.9.1-1.noarch`. |

### 17.4 Deployment Evidence

- Management `mold` and all three `mold-agent` services are active.
- `/client/` returns HTTP 200 and the active webapp still contains `WEB-INF`.
- Hosts `10.10.32.1`, `10.10.32.2`, and `10.10.32.3` run FTCTL package
  `0.9.1-1` and report the new capability schema and hardware feature.
- The management JAR, Agent core JAR, and KVM plugin JAR contain the expected
  new classes; the KVM plugin no longer contains shadow FTCTL API classes.

### 17.5 Retest Cleanup Evidence

The failed Plan was cleaned through supported control paths before removing
host residue:

1. `releaseDrProtection` completed with a successful RELEASE run.
2. Active replicas and restore points were soft-deleted by runtime projection.
3. Target VM `3f9186ac-75f5-4ea2-8a21-e12871cf170a` was destroyed with
   immediate expunge; its active volume count became zero.
4. `deleteDrPlan` soft-deleted the Plan.
5. Two historical RUNNING steps whose parent run was already FAILED were
   terminalized as FAILED. New materialization failures use the implemented
   step-terminalization path automatically.
6. The scheduler PID was confirmed inactive before the exact Plan runtime
   directory on `10.10.32.1` was removed. No matching runtime existed on hosts
   2 or 3.

### 17.6 Final AS-IS / TO-BE

| Area | AS-IS failure | Deployed TO-BE |
| --- | --- | --- |
| Boot metadata | Source EFI/Secure Boot was absent and target creation could fall back to LEGACY. | Agent reads vCenter hardware; unresolved firmware/Secure Boot blocks execution and EFI/Secure maps to UEFI/SECURE. |
| Capability | Stale plugin enum reported `TARGET_MATERIALIZED` unsupported. | Stable CLI registry is authoritative and duplicate API classes are removed. |
| Hardware drift | Preview and execution could use different implicit hardware assumptions. | Canonical SHA-256 fingerprint is recomputed and compared before materialization and projection. |
| State | Stale runtime or an earlier Plan error could overwrite the current run state. | Plan/run UUID correlation and current active run precedence prevent false state rewrites. |
| Failure terminality | Parent run could be FAILED while steps remained RUNNING. | Materialization failure terminalizes open steps; the pre-deployment residue was repaired during cleanup. |
| FTCTL status | Transfer status did not expose the Cloud hardware contract. | Status projects source and target boot/I/O contract fields without secrets. |
| Retest state | Failed Plan, target VM, replica, synchronization checkpoint, and host runtime residue existed. | Supported release, expunge, Plan delete, step terminalization, and UUID-scoped runtime cleanup leave a clean test baseline. |

## 18. Normative Checkpoint And RPO Follow-Up (2026-07-10)

Section 16 and 17 references to `restore point` describe the legacy entity and
runtime compatibility field. They do not define point-in-time recovery.

The deployed success Plan proved initial sync, target materialization, CBT,
incremental transfer, and source snapshot cleanup. It also exposed four next
contract fixes:

1. user-facing terminology must be `Synchronization Checkpoint`;
2. checkpoint selection must be internal and latest-only;
3. duplicate checkpoint rows and projection event amplification must be
   prevented;
4. scheduler cadence must be start-to-start to satisfy the configured RPO.

The normative UI/API/backend/Agent/FTCTL/DB design is
`549-cross-hypervisor-dr-checkpoint-history-event-rpo-design-20260710.md`.
