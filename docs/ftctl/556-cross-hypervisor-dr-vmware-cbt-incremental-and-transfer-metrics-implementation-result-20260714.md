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

# Cross Hypervisor DR VMware CBT Incremental And Transfer Metrics Implementation Result

- Date: 2026-07-14
- Scope: VMware source to ABLESTACK target
- Design: `555-cross-hypervisor-dr-vmware-cbt-incremental-and-transfer-metrics-design-20260714.md`
- Result: implementation, build, deployment, package smoke, and retest cleanup PASS
- Live acceptance: initial seed and repeated durable reseed PASS; measured CBT incremental FAIL as of 2026-07-17

## 1. Implemented Flow

```text
RPO cycle starts
  -> create short-lived VMware snapshot
  -> FULL_SEED: capture current changeId and perform full copy
  -> later cycle: QueryChangedDiskAreas(previous changeId)
  -> normalize and validate changed extents
  -> read/write only changed extents through source/target NBD
  -> fsync target
  -> atomically replace local per-disk changeId baseline
  -> remove owned VMware snapshot
  -> publish effective mode and transfer metrics
  -> Agent typed status
  -> Cloud asynchronous projection to dr_restore_point
  -> UI synchronization history
```

The VMware snapshot is not the long-lived baseline. The retained baseline is
the per-disk changeId committed after target durability. Snapshot removal does
not force the next cycle to be a full copy.

## 2. Component Results

| Layer | Before | After |
|---|---|---|
| UI | Sequence and timestamps could not prove incrementality | Effective mode, verification, measured/estimated kind, changed/read/written/payload bytes, extents, duration, and throughput |
| API | Restore-point response had no transfer evidence | Existing response exposes typed cycle metrics without a new blocking API |
| Backend | Latest checkpoint projection persisted timestamps only | Runtime projection idempotently updates the matching typed restore-point history row |
| Agent | FTCTL status stopped at checkpoint identifiers | Status answer/wrapper carry latest-completed mode, verification, metrics, generation, and token |
| FTCTL | Every cycle used full `qemu-img convert` | Full seed once; later cycles query VMware CBT and apply changed extents; no silent full fallback |
| DB | `dr_restore_point` contained checkpoint metadata only | Thirteen typed cycle-evidence columns are present on all create/upgrade schema paths |
| Snapshot | Snapshot deletion was incorrectly treated as baseline loss | Short-lived snapshot cleanup is independent from the atomically retained changeId baseline |

## 3. Build Evidence

- Cloud Maven build root: WSL ext4
  `/home/ablecloud/work/build/cbt-20260714-185411/cloud`
- Successful changed-module build:
  `mvn -pl core,plugins/hypervisors/kvm,plugins/integrations/disaster-recovery -DskipTests install`
- Successful focused test:
  `FtctlDrRuntimeProjectionAdapterTest`, 10 tests, 0 failures, 0 errors
- UI production build: completed with only existing asset-size and Browserslist warnings
- FTCTL shell/Python syntax checks: PASS
- FTCTL extent patch test: PASS

## 4. FTCTL Package Evidence

- Source branch: `feature/ftctl-cloud-integration`
- Source commit: `54b88b8dc8f21573d9f5bbc126de11646292e632`
- GitHub Actions run:
  `https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/29325295248`
- RPM: `ablestack_vm_ftctl-0.9.1-1.noarch.rpm`
- RPM SHA-256:
  `259ef219c7c46bbbd022426097e20cd33211debb9cd25c54b144dd2db17ab86f`

The RPM and changed Agent classes were deployed to `10.10.32.1`, `.2`, and
`.3`. Installed markers for CBT query, extent patch, effective mode, and
transfer metrics were verified on all hosts.

## 5. Cloud And DB Deployment Evidence

- Cloud was deployed by replacing only six changed class entries in the active
  management JAR.
- Agent deployment replaced only the changed core status-answer class and KVM
  status-wrapper class.
- Deployed class entry SHA-256 values match the WSL Maven output on management
  and all three Agent hosts.
- UI deployment preserved
  `/usr/share/cloudstack-management/webapp/WEB-INF` and updated static assets
  only.
- `/client/` returned HTTP 200 and active bundle/locale markers were present.
- DB backup was created before migration.
- Added `dr_restore_point` columns:
  `effective_mode`, `incremental_verified`, `metrics_estimated`,
  `virtual_bytes`, `changed_bytes`, `source_read_bytes`,
  `target_written_bytes`, `transfer_payload_bytes`, `changed_extent_count`,
  `duration_ms`, `throughput_bps`, `baseline_generation`, and `cycle_token`.

## 6. Service And Package Smoke

- `mold.service`: active
- `mold-agent.service`: active on all three hosts
- `ablestack-vm-ftctl.timer`: active on all three hosts
- Cloud hosts `1`, `2`, and `3`: `Up / Enabled`
- Recent management/Agent fatal class or schema error count: 0
- Installed extent-patch smoke: two extents, 192 bytes; source-read, target-write,
  and payload counters all matched 192 bytes; target content comparison PASS
- vCenter source VM snapshot tree had no remaining FTCTL-owned snapshot

## 7. Retest Cleanup

- Previous Plan `410be8ad-1b40-4405-bcd5-b0840fa7caba`:
  Release Run succeeded, then `deleteDrPlan` succeeded.
- Active Plan count: 0
- Previous target VM `245`: expunging and removed
- Previous target volume `471`: expunged and removed
- FTCTL Plan runtime directory removed after process/lock verification
- FTCTL global lock clear; no attached NBD device remained

## 8. Acceptance Boundary

Build, deployment, schema, service, and installed package smoke are PASS. The
next live test must prove the end-to-end data path:

1. Create a new Plan and complete sequence 1 as `FULL_SEED`.
2. Make a small known write in the VMware guest.
3. Wait for sequence 2.
4. Confirm `effectiveMode=CBT_INCREMENTAL`,
   `incrementalVerified=true`, and `metricsEstimated=false`.
5. Confirm changed/read/written bytes are materially smaller than virtual disk
   bytes and the target content is correct.
6. Confirm the FTCTL-owned snapshot count returns to zero after completion.

Until sequence 2 passes these checks, build/package evidence remains valid but
runtime deployment readiness is FAIL.

## 9. Live Acceptance Failure And Corrective Handoff

Plan `538befc6-0efb-4304-ba1a-5243311de4fb` proved source CBT query and full
target copy, but the newly added per-disk result jq expression failed on the
reserved `$label` keyword. The target RBD retained valid partition/filesystem
signatures, while no restore point, target VM, or committed changeId existed.

`listDrPlans` and `getDrPlan` also became invalid JSON because the Backend used
the complete FTCTL status payload as `last_error_message`, exposing nested
Unicode escapes through the generic API serializer.

Corrective implementation, recovery states, typed error transport, UI
last-good-data behavior, and retest gates are defined in:

- [557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md](557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md)

The corrective implementation was subsequently built and deployed. The failed
Plan was released and soft-deleted, its uncommitted RBD/runtime was removed,
and the environment is ready for a fresh full-seed plus incremental acceptance
run. Deployment evidence and the remaining gate are recorded in section 16 of
the linked document.

## 10. 2026-07-17 Live Incremental Reassessment

New Linux and Windows Plans completed initial seed, target materialization, and
multiple durable cycles. Nevertheless every later cycle was `FULL_RESEED`,
with transferred bytes equal to the complete virtual disk set and
`incrementalVerified=false`.

The committed disk maps are healthy and advance changeId/generation. The
failure occurs when `ftctl_vmware_mover_disk_plan()` omits the committed
baseline state and generation from its execution rows. The mode resolver then
promotes every incremental request to reseed. Cloud has an additional race in
which a completed cycle can remain `TRANSFERRING` if the next cycle starts
before the status poll.

Therefore build/deployment evidence remains valid, but functional continuous
DR acceptance is still FAIL. The next implementation and live gate are defined
in
`559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md`.
