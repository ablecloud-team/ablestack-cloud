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

# DR Test Failover Agent Acceptance And UI Completion Contract

## 1. Purpose

`TEST_FAILOVER` is not successful when an API request is merely accepted. It
becomes successful only after Cloud imports isolated disks, creates the test
VM, verifies the configured boot condition, and persists
`dr_test_session.state=ACTIVE`.

This contract prevents a policy field such as `testBootTimeoutSeconds` or
`boot_timeout_seconds` in a healthy Agent JSON response from being mistaken
for a transport timeout.

## 2. Root Cause

The KVM Agent wrapper searched the complete structured response text for the
word `timeout`. A response with process exit code `0`, `result=accepted`,
`accepted=true`, `state=TESTING`, and no payload `error_code` was therefore
decorated with `DR_AGENT_ACCEPT_TIMEOUT`. Cloud treated that synthetic code as
an engine failure before the test VM materializer could run.

## 3. Contract

### 3.1 Agent

1. Do not classify an exit-code-zero JSON response as transport ambiguous.
2. Detect transport timeout only from missing or unstructured process output.
3. Preserve a structured engine error under its original `error_code`.
4. Never attach an error code to a successful Agent answer.

### 3.2 Cloud Backend

1. When an otherwise valid accepted contract carries the legacy synthetic
   `DR_AGENT_ACCEPT_TIMEOUT`, ignore only that synthetic answer field.
2. A payload error, explicit rejection, or failure state remains terminal.
3. Test failover succeeds only when artifact count matches disk mappings,
   Cloud volumes are imported, a test VM exists, that VM is `Running`, and the
   test session is `ACTIVE`.
4. A materialization failure records the same error on the Run and session and
   keeps `cleanup_required=true` so the cleanup workflow can reclaim resources.
5. The first accepted-Run projection watch is scheduled from the accepted
   in-memory Run that was just persisted. It must not depend on an immediate DAO
   re-read, because transaction/cache visibility may still expose the
   pre-acceptance row and silently skip the watch.
6. Subsequent watch iterations re-read the Run and stop only when it is removed
   or terminal. A `TEST_ARTIFACTS_READY` runtime must enqueue Cloud target
   materialization without requiring an operator refresh.
7. Management startup queries unfinished `ACCEPTED / TEST_FAILOVER` Runs and
   restores their bounded projection watches. A management restart must not
   strand a Run after FTCTL has already produced durable test artifacts.
8. For a remote KVM source plan, an active `TEST_FAILOVER` Run owns both its
   `PLAN_AUTHORITY` and `OPERATION` projection on the target coordinator. The
   test artifacts and Cloud test VM are target-site resources, so this polling
   must not depend on source Mold credentials. Protection sync and cutover Runs
   retain the existing remote-source routing contract.
9. For a `SharedMountPoint` FILE target, the FTCTL `qcow2-copy` locator must be
   an absolute file directly under the registered Cloud storage pool root.
   Cloud imports that exact path and the KVM Agent refreshes the libvirt pool
   before resolving the volume by basename. A runtime-private `/run/...`
   locator is rejected as non-materializable even when the file itself exists.
10. FTCTL owns the pool-root test file only when its manifest contains the
    validated `storageRoot`, an `ftctl-dr-test-` basename, and
    `ownedByFtctl=true`. Test Cleanup removes that exact file after Cloud VM
    and volume cleanup; it never removes the pool root or the durable replica.

### 3.3 UI

1. An async action response is shown as request accepted, not completed.
2. The UI separates request acceptance, isolated disk preparation, Cloud
   volume import, test VM creation, boot validation, and completion.
3. Only `SUCCEEDED + testSessionState=ACTIVE + targetVmId` represents completed
   test failover.
4. A failure shows the backend error code and leaves Test Cleanup available
   when cleanup is required.
5. The protection-view snapshot is a fast display cache. On every detail
   refresh, `listDrRuns` is the authority for action gating and terminal Run
   state.
6. If the cached `activeRun` is `ACCEPTED` or `RUNNING` but the matching live
   Run is terminal, the UI clears the active Run immediately and exposes the
   state-appropriate recovery action such as Test Cleanup.
7. If a newer live Run is active, it supersedes a stale terminal snapshot. If
   the live Run query fails, the UI keeps the snapshot for display and reports
   a refresh warning instead of silently changing action availability.
8. Cached plan fields may supply expensive protection presentation data, but
   they must not overwrite live `actionAvailability`, `actionEligibility`, or
   `lastRun` returned by `getDrPlan`. Menu visibility and enablement always use
   those live fields together with the reconciled Run list.

## 4. Firmware Normalization

For CloudStack, `bootType=UEFI, bootMode=LEGACY` is non-secure UEFI, not BIOS.
Use `bootType=UEFI, bootMode=SECURE` only when the source has Secure Boot
enabled. The test materializer must preserve these established KVM target
combinations.

## 5. AS-IS / TO-BE

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Agent timeout | Search all JSON text for `timeout` | Inspect only ambiguous transport output |
| Success answer | Synthetic error may accompany success | Successful contract has no error |
| Cloud Run | Immediately fails on synthetic code | Accepts and tracks materialization |
| Projection watch | Initial DAO re-read can skip scheduling | Persisted accepted Run schedules the first watch; later iterations re-read |
| Remote KVM test projection | Authority polling can be sent to the source Mold and fail before local artifacts are observed | Route authority and operation polling for an active Test Failover Run to the target coordinator |
| SharedMountPoint test disk | `/run/...` artifact exists but is invisible to the Cloud storage pool | Artifact is materialized in the validated pool root and imported by its absolute path |
| Test success | May look successful at request acceptance | Succeeds after VM boot validation |
| UI | Acceptance and completion both look successful | Shows each async lifecycle stage |
| Firmware | Non-secure UEFI can be mistaken for BIOS | Preserve `UEFI/LEGACY` explicitly |
| Compensation | Failed session/artifacts remain pending | Preserve evidence and expose cleanup path |

## 6. Regression Gates

- Timeout policy fields in accepted JSON do not trigger a status probe.
- An unstructured command timeout still triggers a status probe.
- A structured boot timeout retains its original engine failure code.
- Cloud accepts a healthy legacy answer with only the synthetic timeout code.
- UI does not announce `ACCEPTED` as completed.
- UI stage text and final `ACTIVE` success are covered by unit tests.
- A cached accepted Run reconciles to a live failed Run and no longer blocks
  Test Cleanup.
- A newer live active Run supersedes a stale terminal cached Run.
- A stale cached cancel action cannot replace live Test Cleanup or Test
  Failover availability.
- A remote KVM source Test Failover projects both status scopes locally and
  never calls the remote Mold status transport.
- Baseline sync, pause/resume, release, test cleanup, failover, and failback
  action-contract tests remain passing.
