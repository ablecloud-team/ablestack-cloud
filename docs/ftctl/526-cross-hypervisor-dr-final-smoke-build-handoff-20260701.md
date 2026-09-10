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

# Cross Hypervisor DR Final Smoke And Build Handoff - 2026-07-01

## Status

Final local implementation gate status: Passed

Completed implementation steps: 12 / 12

Remaining work is operational, not local implementation: commit/push, qemu GitHub Actions RPM build, deployment, and live 4-direction DR retest.

## Scope Confirmed

The local implementation now covers the full UI -> API -> Cloud backend -> Agent -> ftctl -> runtime projection -> API response -> UI refresh loop.

The production DR runtime uses `FTCTL_DR`. V2K remains excluded from production DR data movement. VMware VDDK/CBT support is treated as an FTCTL_DR driver contract and is validated locally through preflight/mock selftests until a live VMware/VDDK environment is available.

## Flow Coverage

1. UI creates and manages DR plans for `KVM_TO_KVM`, `KVM_TO_VMWARE`, `VMWARE_TO_VMWARE`, and `VMWARE_TO_KVM`.
2. UI invokes DR actions through Cloud APIs and never waits synchronously for runtime completion.
3. API/backend records `dr_run`, validates eligibility, dispatches action to Agent, and returns an accepted run response.
4. Agent invokes `ablestack_vm_ftctl dr-* --wait=false --json`.
5. ftctl performs profile application, scheduler checkpoints, ABLESTACK or VMware driver execution, test failover, failover, failback, reprotect, release, status, and cancel handling.
6. Projection imports ftctl runtime state into `dr_plan`, `dr_replica`, `dr_restore_point`, `dr_run_step`, `dr_event`, and terminal `dr_run` state.
7. UI polling refreshes plan/run/progress state and stops when the action is terminal.

## Validation Results

| Area | Command | Result |
| --- | --- | --- |
| ftctl syntax and shellcheck | `bash -n bin/ablestack_vm_ftctl.sh bin/ablestack_vm_ftctl_selftest.sh lib/ftctl/dr_runtime.sh lib/ftctl/dr_scheduler.sh lib/ftctl/dr_ablestack.sh lib/ftctl/dr_vmware.sh lib/ftctl/libvirt_wrap.sh && shellcheck lib/ftctl/dr_runtime.sh lib/ftctl/dr_scheduler.sh lib/ftctl/dr_ablestack.sh lib/ftctl/dr_vmware.sh` | PASS |
| ftctl DR selftest matrix | `FTCTL_SELFTEST_CASES=<19 DR cases> bash bin/ablestack_vm_ftctl_selftest.sh` | PASS |
| ftctl package input | RPM spec installs `lib/ftctl/*`; completion lists `dr-plan-apply`, `dr-sync-start`, `dr-sync-pause`, `dr-sync-resume`, `dr-test-failover`, `dr-test-cleanup`, `dr-failover`, `dr-failback`, `dr-reprotect`, `dr-release`, `dr-status`, and `dr-cancel` | PASS |
| Cloud targeted Maven test | `mvn -pl :cloud-plugin-integrations-disaster-recovery,:cloud-plugin-hypervisor-kvm -am -Dtest=FtctlDrRuntimeProjectionAdapterTest,FtctlDrUnifiedActionAdapterTest,DrPlanServiceImplTest -DfailIfNoTests=false test` | PASS: 16 tests, 0 failures, 0 errors |
| Cloud changed-module build | `mvn -pl :cloud-plugin-integrations-disaster-recovery,:cloud-plugin-hypervisor-kvm -am -DskipTests -DfailIfNoTests=false install` | PASS: `BUILD SUCCESS` |
| Cloud UI lint | `NODE_OPTIONS=--openssl-legacy-provider npx vue-cli-service lint --no-fix src/views/infra/dr/DrPlanList.vue src/views/infra/dr/DrRunsTab.vue src/components/dr/DrRunProgress.vue` | PASS |
| Cloud UI build | `NODE_OPTIONS=--openssl-legacy-provider npm run build` | PASS: `dist` ready to deploy |
| UI runtime marker check | `grep -R -E 'scheduleRuntimePolling|pollRuns|progressValue|stateProgress' dist/js dist/css` | PASS |
| Source diff checks | `git diff --check` on Cloud and qemu changed source/doc files | PASS |

## ftctl DR Selftest Cases

The final ftctl smoke used the following DR cases:

| Case |
| --- |
| `selftest_case_dr_remote_key_connectivity_args` |
| `selftest_case_dr_remote_failback_maps_reverse_rbd_on_primary` |
| `selftest_case_dr_remote_reverse_plan_stores_rbd_uri_source` |
| `selftest_case_dr_remote_primary_nbd_prepare_maps_unmapped_rbd` |
| `selftest_case_dr_runtime_profile_status_cancel` |
| `selftest_case_dr_runtime_control_actions` |
| `selftest_case_dr_ablestack_target_prepare` |
| `selftest_case_dr_ablestack_full_seed_once` |
| `selftest_case_dr_ablestack_missing_disk_map_waits` |
| `selftest_case_dr_vmware_preflight_missing_vddk` |
| `selftest_case_dr_vmware_contract_ready` |
| `selftest_case_dr_vmware_missing_vddk_blocks_sync` |
| `selftest_case_dr_scheduler_ablestack_checkpoint_loop` |
| `selftest_case_dr_scheduler_vmware_mock_checkpoint_loop` |
| `selftest_case_dr_runtime_test_failover_cleanup` |
| `selftest_case_dr_runtime_planned_failover_promotes_latest_checkpoint` |
| `selftest_case_dr_runtime_failback_restores_source_after_reverse_checkpoint` |
| `selftest_case_dr_runtime_reprotect_starts_reverse_protection_checkpoint` |
| `selftest_case_dr_scheduler_vmware_requires_mover` |

## Direction Matrix

| Direction | Local implementation gate | Evidence |
| --- | --- | --- |
| ABLESTACK -> VMware | PASS | KVM source path plus VMware target driver contract, VMware mock checkpoint loop, VDDK preflight guards, Cloud/UI/API flow. |
| VMware -> VMware | PASS | VMware source/target driver contracts, VDDK/CBT preflight guards, VMware mock checkpoint loop, Cloud/UI/API flow. |
| ABLESTACK -> ABLESTACK | PASS | ABLESTACK target prepare/full seed, ABLESTACK scheduler checkpoint, failover/failback/reprotect selftests, Cloud/UI/API flow. |
| VMware -> ABLESTACK | PASS | VMware source driver contract plus ABLESTACK target path, VMware preflight/mock scheduler, ABLESTACK target writer, Cloud/UI/API flow. |

Live PASS for VMware-backed directions still requires a real VMware/VDDK environment. The local gate proves the implemented control flow, preflight behavior, driver contract, projection loop, and mock data-plane path.

## Artifact Evidence

Cloud artifacts from WSL ext4 clone:

| Artifact | SHA256 |
| --- | --- |
| `core/target/cloud-core-4.22.0.0-SNAPSHOT.jar` | `45cbf51d0d55ed2093308a095a2ed3f9df34b5d133dac83d9523bb39c413de5c` |
| `agent/target/cloud-agent-4.22.0.0-SNAPSHOT.jar` | `1139a593e1b5400788723807de270965d7f2c407fcedf0c6e3716a6c59034ca6` |
| `engine/schema/target/cloud-engine-schema-4.22.0.0-SNAPSHOT.jar` | `0ccd218f4ba508720a91a87c6f62370c8fee33f64a15df2a4da1aea48b4d3aa2` |
| `plugins/hypervisors/kvm/target/cloud-plugin-hypervisor-kvm-4.22.0.0-SNAPSHOT.jar` | `7f2758da15a5d192a62f3cd778c89d9ad246e19d744872afc9dd5dd4945db1cd` |
| `plugins/integrations/disaster-recovery/target/cloud-plugin-integrations-disaster-recovery-4.22.0.0-SNAPSHOT.jar` | `605a04e99f3371013cf26b22c40bbb946055373c9e1f1016830f7eafe983e8ab` |
| `ui/dist/js/app.92737368.js` | `943a965ce7a49dcc2a2f87bffccb1a8ed1c6ac82c3db89429fa5d9997681283c` |
| `ui/dist/css/app.26766d55.css` | `fc69dcfa61146d2b4d99d90df64accc3160ab3af2a74abbe6e4f467fff46173a` |

ftctl RPM artifact was not generated locally. Per repository policy, `ablestack-qemu-exec-tools` package artifacts must be built through GitHub Actions after the source is committed and pushed.

## Packaging Handoff

qemu package build target:

- Repository: `dhslove/ablestack-qemu-exec-tools`
- Branch: `feature/ftctl-cloud-integration`
- Workflows available: `ci.yml`, `build.yml`, `branch-ftctl-release.yml`
- Package inclusion evidence: `rpm/ablestack_vm_ftctl.spec` copies `lib/ftctl/*` into `/usr/local/lib/ablestack-qemu-exec-tools/ftctl/`
- DR runtime package inputs verified: `dr_ablestack.sh`, `dr_key.sh`, `dr_runtime.sh`, `dr_scheduler.sh`, `dr_vmware.sh`

Cloud deployment target artifacts:

- Changed classes should be deployed from the Maven-built module artifacts, not from a full Cloud rebuild.
- UI deployment should use the generated `ui/dist` static assets while preserving `/usr/share/cloudstack-management/webapp/WEB-INF`.

## Next Operational Phase

1. Commit and push Cloud and ftctl repositories.
2. Run qemu GitHub Actions package build and download the ftctl RPM artifact.
3. Deploy changed Cloud classes/JARs and static UI assets.
4. Deploy the ftctl RPM to the worker hosts.
5. Verify services and active UI bundle markers.
6. Execute the live 4-direction DR retest matrix.

## Known Boundaries

- No Cloud full build was run because full Cloud builds must be explicitly requested and should use GitHub Actions.
- No local qemu RPM was built because qemu/ftctl package artifacts must be built by GitHub Actions.
- VMware live validation remains pending until a VMware/VDDK test environment is available.
- Existing UI build warnings remain: Browserslist data age and asset-size warnings.

## Operational Hand-off Deployment - 2026-07-01

Source baselines:

| Repository | Branch | Commit | Result |
| --- | --- | --- | --- |
| `dhslove/ablestack-cloud` | `feature/ftctl-cloud-integration` | `b51a8a9697` | Pushed to origin. |
| `dhslove/ablestack-qemu-exec-tools` | `feature/ftctl-cloud-integration` | `a25443d16d` | Pushed to origin. |

qemu FTCTL package build:

| Item | Value |
| --- | --- |
| Workflow | `FTCTL Branch Development Release` |
| Run | `https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/28510151119` |
| Source commit | `a25443d16de6ac2a08fbc9d949be8ca802b35a50` |
| RPM | `ablestack_vm_ftctl-0.9.1-1.noarch.rpm` |
| RPM SHA256 | `baa6d035746a558f2a8fb6f5756582d91936694004253eba224da7bb8b172837` |
| Integrity | `SHA256SUMS` verification PASS. |

Cloud deployment target:

| Item | Value |
| --- | --- |
| Management host | `10.10.32.10` |
| Service | `mold.service` |
| Runtime JAR | `/usr/share/cloudstack-management/lib/cloudstack-4.22.0.0-Mold.Europa-202606280754.jar` |
| Active UI path | `/usr/share/cloudstack-management/webapp` |
| Deployment bundle | `/root/cloud-dr-handoff-b51a8a9697/` |
| Backup path | `/root/cloud-dr-handoff-b51a8a9697/backup-20260701-1915/` |
| Runtime patch SHA256 | `1b21f56e445477bec72775d2f65310edbefc994da9e50568f6db58f7309061fe` |
| UI dist SHA256 | `0f70a6295b9c20433f542cc0d92df77eb473d08e91885ad4664a84e5eee171f8` |
| Schema patch SHA256 | `d1870ed333bc5ba45ac21d9af0fc47b9cc32d1d5de878b4d8a02384a814aa46a` |

Deployment verification:

| Check | Result |
| --- | --- |
| `mold.service` | `active` after restart. |
| `/client/` | HTTP `200` after restart. |
| `WEB-INF` preservation | PASS. |
| DR DB tables | `10` `dr_%` tables present in `cloud`. |
| Runtime JAR markers | `FtctlDrActionCommand`, `LibvirtFtctlDrActionCommandWrapper`, `DrRunExecutorImpl`, and `spring-disaster-recovery-context.xml` present. |
| Active UI markers | `pollRuns` and `cross-dr` present under active webapp assets. |
| Journal warning review | Only expected `mold.service` stop-result warning from controlled restart; service recovered to active. |

This hand-off intentionally deployed Cloud as changed runtime class/resource overlay plus static UI assets, not a full Cloud package replacement. The ftctl RPM was built and verified by GitHub Actions, but it was not installed on worker hosts in this hand-off step.
