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

# #963 QGA-required test boot validation

Branch codex/fix-963-qga-validation starts at Cloud 1bccb2bc14, preserving #960/#961/#965. No separate qemu-exec-tools source change is needed; Cloud core and KVM Agent implement the bounded guest-ping command.

## Implemented contract

The materializer persists a validation record immediately before VM start. QGA_REQUIRED moves to CLOUD_VM_VALIDATING/QGA_PENDING after power-on; existing runtime projection re-enqueues a nonblocking reconciliation instead of recreating VM/volumes. Only correlated RESPONDED evidence can transition to ACTIVE/QGA_VALIDATED and complete the Run. Both materializer completion and isTestTargetActive require the same durable proof. POWER_STATE_ONLY keeps power-only semantics.

DrTestBootValidationService uses two bounded workers and a periodic DB due scan. Each attempt claims a 20-second token lease, makes one guest-ping with a maximum five-second libvirt timeout (10-second command wait), resolves current VM placement and rechecks it on return. Invalid tokens/UUIDs or changed hosts cannot validate. Deadline enforcement uses DB UTC and includes VM start time. No network collector/cache, guest-exec or arbitrary QGA command is used by production validation.

A separate dr_test_boot_validation table stores immutable Session/Run/VM binding, start/deadline, lease, retries, final evidence and validated_at. Runtime details JSON cannot overwrite it. SQL conditional updates reject late replies after cleanup/cancel/Run completion; expired leases are recoverable by another process. Schema is additive and CREATE IF NOT EXISTS is included in fresh and current Europa/4.23 upgrade scripts. No historical success is fabricated; QGA_REQUIRED with old POWER_STATE_VALIDATED is displayed as LEGACY_QGA_UNVERIFIED without changing stored Run results.

Implementation refinement from the issue design: the small validation table is accessed by a dedicated JDBC store rather than adding ORM VO/DAO pairs. The validation service independently re-enqueues completion from durable DB state; runtime projection can also wake reconciliation. Terminal evidence with a still-validating Session is rescanned after restart, without requiring source runtime reachability. Cleanup suppresses late callbacks. Mode/boot result use existing/additive Run response fields and UI metadata. Detailed attempt/deadline evidence remains in the dedicated table. No live physical multi-manager or all-provider matrix is inferred from unit tests.

## Build and deployment

WSL ext4 changed module builds: core, KVM, disaster-recovery, schema. DR suite 407 tests; dedicated core command test; KVM new ping parser and existing guest-network wrapper regressions. UI build completed. Deployment applies changed classes/resources into existing JARs after backups and checks unchanged entries byte-for-byte. This is a test module overlay, not a full distribution package build.

31.1/2/3 Agent core/KVM overlays deployed before management. Management JAR retains #960/#961/#965, final SHA256 16aff9ca907363d429f35093ea6cfdd5d641abd1395828a8ca2e9e0cf989752a. Backups/manifests on each host /root/issue963/backup and /root/issue963/final-backup; final management overlay adds /root/issue963/completion-backup and /root/issue963/recovery-backup. UI static deployment preserves WEB-INF/META-INF, HTTP 200 and new testbootvalidationmode bundle marker. UI backup /root/issue963/ui-before.tgz. Never replace the webapp root during rollback; restore only matching static assets and saved JARs. Additive validation table may remain on code rollback.

## Physical UI evidence (KST)

13 -> 31 SharedMountPoint qcow2, u26-base DR Plan 9a20b190-d202-4b66-9358-b509756f9751, root 50GiB + data 100GiB. Source and protected replica guest settings were not modified.

- Positive Run 261 / 1109cc9c-94e7-4894-9a70-60a26ffab21e. Session 39, VM 240. QGA_REQUIRED remained pending through two NOT_READY responses; third guest-ping RESPONDED at 23:33:20 and Run SUCCEEDED at 23:33:21. Direct QGA corroborated guest response. The initial deployed build used local CURRENT_TIMESTAMP for started_at while deadline was UTC; final build explicitly inserts UTC for both (no existing evidence was rewritten).
- Positive cleanup Run 262 / d02e29a3-99b0-44d0-add9-b54e2c3a679c SUCCEEDED. Existing #964 target-export waiting recurred; UI full sync used to recover, without DB/runtime repair. Not an automatic-resume PASS.
- Negative Run 264 / 658c98ae-12ac-4928-8fa8-14477dd969ce, Session 40, VM 241. Stopped QGA only inside the newly created test VM. VM remained Running; 12 attempts reached the original 60-second deadline and Run FAILED with DR_TEST_QGA_TIMEOUT at 23:42:21. UI displayed QGA_REQUIRED/QGA_TIMEOUT; no success was fabricated. Cleanup Run 265 / 89180ccb-23fc-425d-b494-8b3ced2984e2 SUCCEEDED and removed the fault-injected VM.
- Final API regression confirms legacy DR cluster APIs remain absent and direct calls rejected, while current DR APIs and cloud.dr.service.enabled=true remain available.

- Restart regression Run 267 / 9cf4575e-7d2f-4927-9086-79ecf31acfb0, Session 41, VM 242. QGA stopped only in new test VM. Management restarted during PENDING (attempt 5); resumed at attempt 9 with identical UTC start 2026-09-09 14:59:53 and deadline 15:02:53. After 19 attempts, FAILED/DR_TEST_QGA_TIMEOUT at 15:02:56 UTC. UI correctly showed CLOUD_VM_VALIDATING/QGA_PENDING and successful materialization while waiting, then boot-validation FAILED. Cleanup Run 268 / 5d00d808-ae88-4436-b848-9df6c4a0fffe succeeded, Session CLEANED/cleanup_required=0.
- POWER_STATE_ONLY regression Run 270 / 3f82445d-a597-40a1-b6ad-48f97acf68e8, Session 42, VM 243. SUCCEEDED at 2026-09-10 00:06:55 KST; UI confirmed ACTIVE/POWER_STATE_VALIDATED and no QGA validation record is required.
- Independent UI terminal-step precedence issue is upstream #966 (P2). Requested FULL_RESEED Run premature completion is upstream #967 (P1); do not equate Run success with durable full reseed. Existing cleanup/export waiting is #964. These are separate from QGA response validation.

Recovery refinement: due scanning also replays ACTIVE/FAILED validation Sessions with an incomplete Run. This closes the crash window between Session transition and Run completion. Immutable evidence and conditional Session transitions are preserved. Physical multi-manager, VM live migration, and all-provider/storage matrices remain unexecuted; unit coverage is not reported as physical PASS.

## Final cleanup and scope

Cleanup 271 / 218c51de-ced0-48ca-b5c3-a6de0aec29e9 succeeded. All four test Sessions 39-42 are CLEANED with cleanup_required=0; VM 240-243 removed and absent on all three target hosts. POWER_STATE_ONLY Session 42 has zero QGA validation rows. Protected VM225 remains Stopped. All five Plans (6-10) are READY; inventory matches baseline: 16 Running, 8 Stopped, 1 Expunging active rows. Management/three Agents are active, WEB-INF preserved, HTTP 200.

Automatic RECOVER_SYNC 272 / 1dd2c9b6-6b7a-454f-a475-14fbabf57f84 recovered the final cleanup without manual Full Sync. Source runtime at 00:08:30 KST reports a fresh durable checkpoint, scheduler RUNNING, protection READY, baseline/data_commit LOCAL_DURABLE, NBD teardown DRAINED. #964 includes this recovery counterexample rather than claiming every cleanup permanently stalls.

Run269's requested full-reseed later failed DR_NBD_DEVICE_BUSY while Cloud retained SUCCEEDED; this is explicitly tracked in #967. Final healthy incremental readiness is not a PASS for that Full Seed. New work order: #964, #967, #962 (P1), then #966 (P2) and feature guards. No upstream merge or full distribution build was performed.
