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

# Cross-Hypervisor DR Dual-Cluster Test Release Deployment

## Purpose

This document records the test-release build and deployment baseline used to
make the 22 and 32 ABLESTACK clusters available for bidirectional DR tests.
It is an operational handoff document, not a place to store credentials.

## Source Baseline

| Component | Repository | Branch | Build source commit | GitHub Actions run |
|---|---|---|---|---|
| Cloud | `dhslove/ablestack-cloud` | `feature/ftctl-cloud-integration` | `8020879482572b7248111efaf710ef19014676b4` | `31673957028` |
| FTCTL | `dhslove/ablestack-qemu-exec-tools` | `feature/ftctl-cloud-integration` | `84468e78cb8878384902e195ee164ba9d92596f6` | `31671559609` |
| QEMU exec tools and V2K | `dhslove/ablestack-qemu-exec-tools` | `feature/ftctl-cloud-integration` | `84468e78cb8878384902e195ee164ba9d92596f6` | `31671626742` |

The Cloud workflow is `ABLESTACK Branch Development Release`. The FTCTL
workflow is `FTCTL Branch Development Release`. Both releases use the label
`ftctl-cloud-integration-dual-dr`.

## Test Environment

| Environment | Role | Endpoints | SSH port | Credential storage |
|---|---|---|---:|---|
| 22 cluster | Management | `10.10.22.10` | 22 | GitHub `dr-test` environment secrets |
| 22 cluster | Compute | `10.10.22.1`, `.2`, `.3` | 22 | GitHub `dr-test` environment secrets |
| 32 cluster | Management | `10.10.32.10` | 22 | GitHub `dr-test` environment secrets |
| 32 cluster | Compute | `10.10.32.1`, `.2`, `.3` | 22 | GitHub `dr-test` environment secrets |
| VMware | vCenter | `10.10.21.10` | 443 | GitHub `dr-test` environment secrets |

The 22 cluster historically used SSH port `10022`. Live verification on
2026-08-13 showed that port `10022` is refused on all four nodes and port `22`
is the active authenticated SSH path. Always perform a TCP and SSH preflight
before deployment instead of relying on the historical port.

## GitHub Secret Contract

Create the `dr-test` GitHub Environment in both repositories and keep these
values as environment secrets. Secret values must never be printed, checked
into Git, or copied into this document.

| Secret name | Purpose |
|---|---|
| `ABLESTACK_TEST_22_MANAGEMENT_HOST` | 22 cluster management endpoint |
| `ABLESTACK_TEST_22_COMPUTE_HOSTS` | Comma-separated 22 cluster compute endpoints |
| `ABLESTACK_TEST_22_SSH_PORT` | Active 22 cluster SSH port |
| `ABLESTACK_TEST_22_SSH_USER` | 22 cluster SSH account |
| `ABLESTACK_TEST_22_SSH_PASSWORD` | 22 cluster SSH credential |
| `ABLESTACK_TEST_32_MANAGEMENT_HOST` | 32 cluster management endpoint |
| `ABLESTACK_TEST_32_COMPUTE_HOSTS` | Comma-separated 32 cluster compute endpoints |
| `ABLESTACK_TEST_32_SSH_PORT` | Active 32 cluster SSH port |
| `ABLESTACK_TEST_32_SSH_USER` | 32 cluster SSH account |
| `ABLESTACK_TEST_32_SSH_PASSWORD` | 32 cluster SSH credential |
| `ABLESTACK_TEST_VMWARE_VCENTER_HOST` | VMware vCenter endpoint |
| `ABLESTACK_TEST_VMWARE_VCENTER_USER` | VMware vCenter account |
| `ABLESTACK_TEST_VMWARE_VCENTER_PASSWORD` | VMware vCenter credential |
| `ABLESTACK_TEST_DB_USER` | Local management DB account |
| `ABLESTACK_TEST_DB_PASSWORD` | Local management DB credential |
| `ABLESTACK_TEST_DB_NAME` | Cloud DB name |

GitHub secrets are write-only. Future workflows and Codex sessions can use
their names through GitHub Actions, but cannot retrieve or display their values.

## Deployment Safety Contract

1. Build Cloud and FTCTL artifacts only with GitHub Actions.
2. Verify the artifact source SHA and checksum before deployment.
3. Back up management JARs and active web static assets before changing them.
4. Preserve `/usr/share/cloudstack-management/webapp/WEB-INF` and `META-INF`.
5. Never replace the webapp root and never use `rsync --delete` against it.
6. Install FTCTL on all three compute hosts in each cluster.
7. Verify `mold-agent`, `ablestack-vm-ftctl.timer`, installed scripts, and the
   expected source markers after package deployment.
8. Verify `/client/` returns HTTP 200, the management process is healthy, and
   the active UI bundle contains the current DR markers on both clusters.

## Deployment Result

### Cloud build and dual-cluster deployment

GitHub Actions run `31673957028` completed successfully from commit
`8020879482572b7248111efaf710ef19014676b4`. The deployed Cloud package version
on both management servers and all six compute hosts is
`4.23.0.0-Mold.Europa.202608130629.1`.

| Package | SHA256 |
|---|---|
| `cloudstack-common` | `d697fbb62f16977c78c821a757bd9bc56dd1e720a536ad99c0c3ab82af0772cf` |
| `cloudstack-management` | `018674e64b0458138d7e7f311476487f948d8636cbb997f6f40eee0b2e8d0a9a` |
| `cloudstack-ui` | `13a46ed339be8b0b032042d571590607f658e4bcab569ca7930a43ef2a9a3b19` |
| `cloudstack-usage` | `5213577aaa66e9cd5972bed483a1d36c7c97f977697d040e02b6d5f77db23a97` |
| `cloudstack-agent` | `eabb5861ca5355dcf691b7b0bebe7ccee6719c6cf38c6e822d741da64896329e` |

The 22 cluster used native `rpm -Uvh --replacepkgs`. The 32 cluster has direct
RPM usage intentionally masked and used `aspkg -Uvh --replacepkgs`. Do not
interpret the direct `rpm usage is blocked` message on the 32 cluster as a
host or package failure.

Management backups were created before deployment:

- 22 cluster: `/root/cloud-dual-dr-backup-20260813-142848`
- 32 cluster: `/root/cloud-dual-dr-backup-20260813-142901`

Compute-host agent backups were created under
`/root/cloud-agent-dual-dr-backup-20260813-142933` or the matching per-host
timestamped directory before package replacement.

The 32 management package post-install cleanup incorrectly classified three
new package-owned JARs as unmanaged and moved them to
`/var/lib/cloudstack/management/legacy-lib/20260813T071239Z`. This caused
`ClassNotFoundException: org.apache.cloudstack.ServerDaemon`. The exact
package files `cloudstack-4.23.0.0-Mold.Europa-202608130629.jar`,
`cloud-plugin-storage-volume-linstor-4.23.0.0-Mold.Europa-202608130629.jar`,
and `cloud-plugin-storage-volume-storpool-4.23.0.0-Mold.Europa-202608130629.jar`
were restored to `/usr/share/cloudstack-management/lib` before restarting
`mold`. For future upgrades, check `aspkg -V cloudstack-management` and
`management-server.err` immediately if the package succeeds but
`org.apache.cloudstack.ServerDaemon` is missing. Restore only package-owned
files of the installed build; never copy a JAR from a different release.

Post-deployment verification passed on both management servers:

- `mold` and `mold-usage` are active.
- `/client/` returns HTTP 200.
- `/usr/share/cloudstack-management/webapp/WEB-INF` remains present.
- Active UI bundles contain `blockingLoadingState`, `fetchSyncProgress`, and
  `extractJobId`.
- Both databases contain 21 DR tables and report all three KVM routing hosts
  connected.

All six compute hosts report the deployed Cloud agent version, active
`mold-agent`, and active `ablestack-vm-ftctl.timer`.

### Build compatibility correction

- The first Cloud branch release run (`31667802778`) generated the System VM artifact but failed while generating the RPM API documentation.
- Cause: the new `Dr*` commands generated files such as `listDrSites.xml`, but `tools/apidoc/gen_toc.py` did not map that command family to an API documentation category.
- Resolution: map `Dr*` API documents to the existing `Disaster Recovery` category and repeat the branch test release build before deployment.
- Deployment preflight then found that the 4.22-to-4.23 and Europa after-upgrade paths re-added failback lifecycle columns and their reconciliation index with a non-idempotent `ALTER TABLE`. This is unsafe for the 32 cluster, whose DR schema had already been applied while it was on 4.22.
- Both schema paths now use the project-standard `IDEMPOTENT_ADD_COLUMN` and `IDEMPOTENT_ADD_KEY` procedures. This applies to the run dispatch fields, view-cache diagnostics, event index, cutover-disk fields, and failback lifecycle fields instead of only the last failback block.
- The complete corrected set was executed twice against six isolated tables on the 32 management DB. The second pass retained the expected schema without duplicate-object errors: run 17 columns/2 indexes, run-step 2 columns/1 index, view-cache 4 columns, event 3 columns/1 index, cutover-disk 5 columns/1 index, and failback-session 23 columns/1 index. The procedure and all six test tables were removed afterward.
- A fresh-schema preflight then found that the restore-point backfill queried
  `dr_run` before that table was created. Both the 4.22-to-4.23 and Europa
  after-upgrade scripts now create and extend `dr_run` and `dr_run_step` before
  executing that backfill. The corrected Europa script was applied twice to
  the 22 management DB, which had no DR schema. Both passes succeeded and
  produced 21 DR tables with the expected 32-column `dr_run` contract.
- The superseded run `31669628452` was cancelled before deployment and replaced with a build from the corrected schema commit.

### 32 cluster login regression and API key repair

After the 4.23 deployment, the `10.10.32.10:8080` login request succeeded but
the UI could not finish initialization. The authenticated `listUsers` request
returned HTTP 530, and the UI subsequently raised a permission initialization
error because no user object was available.

The management log identified the actual failure as an `api_keypair.secret_key`
decryption error. The 4.22.1-to-4.23 schema script had copied the legacy
`cloud.user.secret_key` plaintext directly into a field mapped with `@Encrypt`.
Its idempotence check also compared that plaintext value with an existing
encrypted value, allowing a duplicate active key pair to be inserted.

The live 32-cluster recovery preserved the registered API and Secret values:

- A root-only SQL backup was saved as
  `/root/api_keypair-pre-repair-20260813-210237.sql` with mode `0600`.
- A plaintext duplicate whose decrypted value matched the existing encrypted
  pair was soft-deleted.
- A standalone plaintext Secret was encrypted in place with the management
  server's current CloudStack V2 database encryptor.
- The resulting active set contains three key pairs with three distinct API
  keys and no plaintext Secret candidates.
- Session login, `listUsers`, and `listUserKeys` were rechecked and all returned
  HTTP 200. The administrator still has exactly one active key pair.

The source migration now follows a failure-safe sequence:

1. The prepare SQL creates the key-pair schema but does not copy or drop legacy
   key columns.
2. `Upgrade42210to42300.performDataMigration()` reads legacy key pairs, encrypts
   each Secret with `DBEncryptionUtil`, updates the oldest matching pair, and
   soft-deletes any active duplicates. If no matching pair exists, it inserts
   a new encrypted pair.
3. The cleanup SQL drops `cloud.user.api_key` and `cloud.user.secret_key` only
   after the Java migration completes successfully.

Focused schema-module tests cover encrypted insertion, deterministic duplicate
reconciliation, and retry behavior when the legacy columns are already absent.
The WSL ext4 Maven reactor command
`mvn -pl engine/schema -am -Dtest=Upgrade42210to42300Test -Dsurefire.failIfNoSpecifiedTests=false test`
completed with three tests passed and no failures or errors.

### QEMU, FTCTL, and V2K build and deployment

| Artifact | GitHub Actions evidence | Package | SHA256 |
|---|---|---|---|
| FTCTL branch RPM | run `31671559609`, artifact `ftctl-branch-rpm-31671559609` | `ablestack_vm_ftctl-0.9.5-1.noarch.rpm` | `7b4c4bc293078e66d3344ad95e59947bb27ad876db1c44d15c7fe44f3dc22931` |
| FTCTL Rocky 9.7 RPM | run `31671626742`, artifact `ftctl-rpm-package-rocky9.7` | `ablestack_vm_ftctl-0.9.5-1.noarch.rpm` | `8aad3cda252d598df52edf4fecbd64a3882be8c54e7ef05f6be755a2eae16e08` |
| QEMU exec tools | run `31671626742`, artifact `rpm-package-9.7` | `ablestack-qemu-exec-tools-0.9.5-1.el9.el9.noarch.rpm` | `89fe8e79d8b38dd9088f5280e2d0e8cf55fb1189596dffda06cc06beba246784` |
| V2K | run `31671626742`, artifact `v2k-rpm-package-rocky9.7` | `ablestack_v2k-0.9.5-1.el9.el9.noarch.rpm` | `46c3f7cffdb044d97a4e612f48a76065f0506fe44ae1235f077260edba9aed0e` |

The Rocky 9.7 packages were installed on `10.10.22.1` through `.3` and
`10.10.32.1` through `.3`. All six hosts reported an active `mold-agent`, an
active `ablestack-vm-ftctl.timer`, the V2K `govc` runtime, and the VMware mover
`--device-key` marker from commit `84468e7`.

The package restart preflight used the existing 32-cluster plan
`ef73f5f3-9740-4bbd-8c9a-74a972e5f19f`. The prior package rejected its legacy
numeric `cbtDiskId=2000`. The corrected package resolved the persisted
`sourceDiskKey=2000`, completed checkpoint 763 as `CBT_INCREMENTAL` with
206,241,792 changed and written bytes, and completed checkpoint 765 after the
V2K update with 6,946,816 changed bytes. The scheduler remained `RUNNING` and
`HEALTHY`, protection returned to `READY`, and no FTCTL error remained.

After the final Cloud agent deployment and scheduler restart, checkpoint 776
also completed as `CBT_INCREMENTAL` with 9,240,576 changed and written bytes.
Cloud DB state was `READY`/`ENABLED` without an error, while `dr-status`
reported scheduler `RUNNING`, a live scheduler PID, durable target data,
materialized target resources, and one valid target disk. This is the final
runtime regression baseline for the 32 cluster.

### GitHub environment registration

- Environment `dr-test` exists in both repositories.
- All secret names in the GitHub Secret Contract are present in both environments.
- Authenticated SSH was verified for all eight ABLESTACK endpoints and an authenticated vCenter session was verified over HTTPS.

## Retest Handoff

Both clusters are ready for DR test-plan creation and execution. The 32
cluster also retains the running regression plan above. Before starting a new
test, verify the selected plan has no active run or stale lock and confirm the
target VM and storage names do not collide with the retained plan.

The next operator action is to create or select a DR plan in the desired
cluster UI and start the test from that UI. Build, package, service, schema,
credential-registration, vCenter-connectivity, and one live incremental-cycle
verification are complete; no additional server-side preparation is required.

## 2026-08-16 Upstream-Aligned Dual-Cluster Test Release

### Source alignment and release builds

The feature branches were first synchronized with their current base branches,
committed, and pushed before any package was built.

| Repository | Feature source used by build | Synchronized base | Merge commit |
|---|---|---|---|
| `dhslove/ablestack-cloud` | `c85cf95d242bcbe7cfbea2e5338f41bb26076e1e` | `upstream/ablestack-europa` at `6a617e5ce3039d0636b5d519af1dff11497885df` | `c85cf95d242bcbe7cfbea2e5338f41bb26076e1e` |
| `dhslove/ablestack-qemu-exec-tools` | `a30584e3b287b28e596b6362df4b4977cf1c4156` | `upstream/main` and `origin/main` at `72854d47a5355752ffb98d37682e9aeecb177795` | `a30584e3b287b28e596b6362df4b4977cf1c4156` |

GitHub Actions completed successfully from those exact feature commits:

- Cloud full branch development release: run
  [`31917005031`](https://github.com/dhslove/ablestack-cloud/actions/runs/31917005031)
- QEMU/V2K/N2K/HangCTL full package build: run
  [`31917006777`](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/31917006777)
- FTCTL branch package build: run
  [`31917008164`](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/31917008164)

The Cloud test package version is
`4.23.0.0-Mold.Europa.202608160021.1`; the release metadata display version is
`v4.10.0-Europa-20260816-ALPHA1`.

Focused pre-release verification also passed from clean WSL ext4 clones:

- 44 DR Maven tests covering admission, protection-group execution, and FTCTL
  runtime projection.
- Four schema-upgrade tests covering the 4.22.1-to-4.23.0 contract.
- Production UI build with the asynchronous DR progress and protection-group
  action markers.
- FTCTL 10/30/100-plan fleet admission smoke, CBT pagination smoke, V2K CPU
  offering smoke, and controller CBT smoke.

### Artifact identity

| Package | SHA256 |
|---|---|
| `cloudstack-agent` | `42c8bea8e4b1b1b13f2c15f3ed365c8a59ed958241448d3747648256c5833ed1` |
| `cloudstack-common` | `a35643c781194f55beba7eb8ba3b9e2eb080850582410f8ec7e19cc3b99678ed` |
| `cloudstack-management` | `668dd2ac59756671ac9c2ec36a145a0597753f3caa0cb360faacfa246c6273a0` |
| `cloudstack-ui` | `319bdafc3c7674c042bb8fa8e479462a35f817f2dbc6ee2a5c94ed8821be27a5` |
| `cloudstack-usage` | `3ce45e805069ce926bdeb5cc1aa3f08da6d4cf5b5eb14d3414810c9def4cf190` |
| `ablestack_n2k-0.9.5-1.el9.el9` | `3fc8475ecf5970efb1e7303cebcc5b578071220dabaa5ac279841f12f84f4748` |
| `ablestack-qemu-exec-tools-0.9.5-1.el9.el9` | `0da113e90757e7f216ece3d349b53fc3b2412ec429b2d2ad36813469fad4153d` |
| `ablestack_v2k-0.9.5-1.el9.el9` | `c02399fb632d572a23ba6096fbfea1760ab791ec3829a63d552323663e391f82` |
| `ablestack_vm_ftctl-0.9.5-1` | `5fe01c40435422ad18b9715d24a9809b9e6b0aa7c296b3cab1989d7a5d89b9d9` |
| `ablestack_vm_hangctl-0.9.5-1` | `64f3aa4a5ab24ad91bc7e0d1c7a4f01a08b341e68e1147f6708005ac5448317a` |

### Deployment matrix and rollback evidence

The same Cloud package set was installed on management servers
`10.10.22.10` and `10.10.32.10`. The same Cloud agent and five first-party
QEMU/FTCTL packages were installed on all six compute hosts. Native `rpm` was
used on the 22 cluster and the administrator `aspkg` wrapper was used on the
32 cluster.

Pre-deployment backups exist at
`/root/dual-dr-release-backup-20260816-092705` on both management servers and
all six compute hosts. Management backups include the active webapp, Cloud
JARs, configuration, installed package list, and DB schema. Host backups
include package lists, Agent state, and installed FTCTL/QEMU/V2K trees.

The 22-cluster HangCTL timers were already intentionally masked and remained
masked. FTCTL timers are active on all six hosts; HangCTL remains active and
enabled on the 32 cluster.

### 32-cluster package cleanup recovery

During the 32 management upgrade, the package cleanup script again moved three
package-owned JARs from the new build into
`/var/lib/cloudstack/management/legacy-lib/20260816T010712Z`:

- `cloudstack-4.23.0.0-Mold.Europa-202608160021.jar`
- `cloud-plugin-storage-volume-linstor-4.23.0.0-Mold.Europa-202608160021.jar`
- `cloud-plugin-storage-volume-storpool-4.23.0.0-Mold.Europa-202608160021.jar`

The exact same-build files were restored before service startup. Subsequent
`aspkg -V cloudstack-management` reported no missing package-owned JAR, the
remaining timestamp-only differences matched those restored files, and
`ClassNotFoundException: org.apache.cloudstack.ServerDaemon` did not occur.
`WEB-INF` remained present throughout deployment.

After the Agent rollout, hosts 2 and 3 on the 32 cluster initially remained in
`Connecting`. The management log showed that the two-second DR projection
scheduler was dispatching to the reconnecting hosts while their host-join
locks were being acquired. Deployment recovery temporarily set
`dr.projection.scheduler.enabled=false`, restarted management so all three
Agents could attach, and restored the setting to `true`. All three hosts then
converged to `Up/Enabled`, and no new host-lock or FTCTL status-answer warning
was observed in the post-recovery interval.

### Final verification result

Both clusters passed the same final checks:

- `mold` and `mold-usage` are active; `/client/` returns HTTP 200.
- The active webapp retains `WEB-INF` and contains `blockingLoadingState`,
  `fetchSyncProgress`, `extractJobId`, and `startDrProtectionGroupAction`.
- Administrator session login and `startDrProtectionGroupAction` API discovery
  succeed on both management servers.
- Each DB has 23 `dr_%` tables, including `dr_run`, `dr_resource_lease`, and
  `dr_group_run`; accepted-cycle and protection-group columns are present.
- Active DR Run, resource lease, and protection-group Run counts are all zero.
- All six routing hosts are `Up/Enabled` and report Agent version
  `4.23.0.0-Mold.Europa-202608160021`.
- `mold-agent` and `ablestack-vm-ftctl.timer` are active on all six hosts.
- NBD is loaded with `nbds_max=32` and the persistent module configuration is
  present on all six hosts.
- Installed FTCTL scripts contain separate Full Seed and Incremental slots,
  retryable `WAITING_RESOURCE`, and VMware `--device-key` handling. Installed
  V2K scripts also contain `--device-key` handling.

The paired 22/32 environments are therefore aligned to the same source-built
test release and are ready for UI-driven single-plan and protection-group DR
retesting. No additional server-side deployment step is required before the
operator starts the next test.

## 2026-08-18 Requested-Cycle Terminal Race Patch

### Scope and source identity

This deployment closes the race in which a completed Full Seed was followed by
the next incremental scheduler cycle before Cloud had durably terminated the
accepted protection-group child Run. The patch preserves the existing
VMware-to-ABLESTACK RBD data path and changes only terminal evidence,
canonical-cycle ownership, lease convergence, and the operator-facing
consistency state.

| Repository | Branch | Deployed commit |
|---|---|---|
| `dhslove/ablestack-qemu-exec-tools` | `feature/ftctl-cloud-integration` | `73147967a5f394386ef43a80833d506f6626fd14` |
| `dhslove/ablestack-cloud` | `feature/ftctl-cloud-integration` | `3847172799` |

The FTCTL package was produced by GitHub Actions run
[`32079201628`](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/32079201628).
The resulting `ablestack_vm_ftctl-0.9.5-1.noarch.rpm` SHA256 is
`dc1c429651cb7f222192e02589ea8f18365210696d922b16ffa6b3c33e1579ca`.

Cloud was built as the changed disaster-recovery Maven module from a clean WSL
ext4 clone. The 14 changed classes were injected into the installed aggregate
Cloud JAR, following the changed-class deployment rule. The deployed aggregate
JAR SHA256 on both management servers is
`244c513b6ee484eebe66c75f600a1b8f833d3d7742ea89232fd0b30f66c9cecb`.
The UI overlay archive SHA256 is
`8ac3226fa3e80d0e1f761f226c5b673a1112a706871029039f5fe37a8bf4a453`.

### Build and smoke verification

- The requested-cycle terminal repair passed the one-disk, two-disk, and
  Windows test matrix.
- `DrProtectionGroupServiceImplTest` and
  `FtctlDrRuntimeProjectionAdapterTest` passed 44 tests with no failures or
  errors.
- The production UI build passed and contains the light/dark result-finalizing
  state and localized transfer-complete/result-verification labels.
- The full FTCTL self-test runner reached a pre-existing reconcile/fencing
  harness wait; the two terminal-race cases were therefore rerun directly and
  passed, and the GitHub Actions package build completed successfully.

### Dual-cluster deployment result

The FTCTL RPM was installed on `10.10.22.1`, `.2`, `.3` with native `rpm` and
on `10.10.32.1`, `.2`, `.3` with `aspkg`. All six hosts report
`ablestack_vm_ftctl-0.9.5-1.noarch`, an active FTCTL timer, and the
`dr-requested-cycle-terminal-v1` capability. Installed script SHA256 values are
identical across the six hosts:

- `dr_runtime.sh`:
  `085c35c2dfdc3adc7f8b418cdd66ee2a3e839f31ea28c04dd66fbbb09a077fd8`
- `dr_scheduler.sh`:
  `bbba6519137ff905c5baca4b25d2ebae0926d20f90e800687c434e4070566822`

Cloud changed classes and the static UI overlay were deployed to both
management servers while preserving `WEB-INF`. Rollback backups are stored at
`/root/ftctl-terminal-race-20260818-081700` on `10.10.32.10` and
`/root/ftctl-terminal-race-20260818-081809` on `10.10.22.10`.

Both management servers passed the post-deployment checks: `mold` is active,
`/client/` returns HTTP 200, `WEB-INF` exists, the aggregate JAR and changed
class hashes match, and no new startup linkage failure was observed. Installed
terminal-race self-tests also passed on one compute host in each cluster.

### 32-cluster retest cleanup

Three child Runs from protection-group Run 5 predated this patch and had no
live parent monitor. Their cancel requests were accepted by the public API but
could not naturally leave `CANCEL_REQUESTED`. The cleanup was therefore bounded
to Run IDs 189, 190, and 191 and their exact UUIDs; only their open
`runtime-projection` steps were changed to `CANCELED`. All resource leases are
`RELEASED`, and no current Run is active for the Windows, Rocky, or Ubuntu
plans. Historical group Run 5 remains `FAILED` as an audit record.

Windows and Ubuntu are `READY`. Rocky is `DEGRADED` only because its current
RPO is stale; this is the expected starting condition for a Full Seed recovery
test and does not represent an active Run or resource conflict. The operator
can now select the three plans and run **Protection Group Action > Full
Synchronization** to verify concurrent terminal convergence and immediate
next-incremental scheduling.

## 2026-08-20 Scheduler Terminal Publication Barrier

### Scope and source identity

This release makes terminal publication a required scheduler barrier. It also
allows `dr-status --run` to repair a missing terminal journal only when the Run
owner, requested and accepted sequence, latest completed sequence and token,
Full Seed mode, durable evidence, and 100 percent transfer evidence agree.
The existing VMware-to-ABLESTACK RBD copy path is unchanged.

| Repository | Branch | Deployed commit |
|---|---|---|
| `dhslove/ablestack-qemu-exec-tools` | `feature/ftctl-cloud-integration` | `4205f331996ddf3f02522172d07777f750aa35c3` |
| `dhslove/ablestack-cloud` | `feature/ftctl-cloud-integration` | `25f3232d924fdf6a977ac142e39bda4eae49d822` |

The FTCTL package was produced by GitHub Actions run
[`32346785309`](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/32346785309).
The resulting `ablestack_vm_ftctl-0.9.5-1.noarch.rpm` SHA256 is
`9e7bc8e7bb0d225174bbb7d67213f42936ba26c7a5b5f26f52fcaf778fbbcd0a`.

Cloud was built as the changed disaster-recovery Maven module from the clean
WSL ext4 clone `/home/ablecloud/work/builds/terminal-barrier-20260820/cloud`.
Only the four `DrProtectionGroupServiceImpl` classes were injected into the
installed aggregate JAR. The deployed aggregate JAR SHA256 on both management
servers is
`6cded8f32b19c0a0eec2151b201eaa7270b990f3ec731c4c388d37362af386dc`.
The static UI overlay SHA256 is
`e56625d5d0d51ca8c997a0425214710a8570c3154510e7d000b552a8b0a28382`.

### Build and smoke verification

- FTCTL one-shot, terminal-repair matrix, terminal-barrier retry, and scheduler
  systemd launch self-tests passed.
- `DrProtectionGroupServiceImplTest` passed eight tests with no failures or
  errors.
- `DrPlanOverview.spec.js` and `DrProtectionInfoTab.spec.js` passed.
- The production UI build passed. Its bundle contains
  `resultVerificationState` and `consistencyWarningCount`, including dark-mode
  consistency-warning styling and localized member-result labels.

### Dual-cluster deployment and scheduler reload

The same FTCTL RPM was installed on all six compute hosts. The 22 cluster used
native `rpm`; the 32 cluster used the `aspkg` administrator wrapper. Installed
script SHA256 values are identical across the clusters:

- `dr_runtime.sh`:
  `8774fa5175c2ece2a96a202c049e874448645041315fb46e3a1232a3256063e0`
- `dr_scheduler.sh`:
  `8ec2e16485fc93a0aed79c9311e198a411af617b7aefa23f88189c1e9839c262`
- `ablestack_vm_ftctl_dr_rolling_reload`:
  `3439404b0658578d4b41dfafa49761ba15cfdb1de32de777d64fbf65837e5f95`

The rolling reload tool restarted the three idle DR schedulers on the 32
cluster and verified that each running process reports the installed scheduler
hash. The 22 cluster had no active DR schedulers, so no process restart was
required. There were no deferred or failed reloads.

Cloud changed classes and the static UI overlay were deployed to both
management servers while preserving `WEB-INF`. Backups are stored at
`/root/ftctl-terminal-barrier-20260820-171927` on `10.10.32.10` and
`/root/ftctl-terminal-barrier-20260820-172046` on `10.10.22.10`. Both `mold`
services are active, both `/client/` endpoints return HTTP 200, and both active
UI bundles contain the new group-result consistency markers.

### Existing group convergence and retest gate

Protection-group Run `b1c1e089-8e57-4867-b1f2-705e3ec54ead` recovered through
the terminal-journal repair path without a direct DB state update. Its final
result is `SUCCEEDED` with three successful members and zero failures. All
three child Runs are `SUCCEEDED`, have `terminal_authoritative=1`, and have no
error code. All 14 resource leases are `RELEASED`; the active lease count is
zero.

This is the required clean retest gate. The next operator action is to select
the Ubuntu, Rocky, and Windows plans in the 32-cluster DR Plan list and run
**Protection Group Action > Full Synchronization** once. The expected result is
group `3/3`, no member left in result finalization, and zero active leases.

## 2026-08-21 VMware Snapshot Cleanup Durability Update

Cloud completion projection was updated at commit `be91849631ccaf0576cce3010b78c65674744b81`
to select transfer summaries from the latest completed cycle and to refresh
only automatically managed vCenter thumbprints. The changed Cloud class was
deployed to both management servers. Both `mold` services are active,
`/client/` returns HTTP 200, and `WEB-INF` is preserved.

FTCTL commit `4900bd78ad3dae46b3bf5ca2318de4bf367f7eb8` was built by GitHub Actions
run `32469879056`. The resulting `ablestack_vm_ftctl-0.9.5-1.noarch.rpm` has
SHA256 `651a2508ae0bd69d1aee4d9add9b0adb68a2013d897792daedd5094e7c014cc2`
and was installed on all six compute hosts. The installed mover SHA256 is
`5262a2862cc3c93375d63d336892f01a0d14d60874a309f71244149961534fe6`
on every host.

After scheduler reload, the three 32-cluster plans automatically completed
incremental cycles and projected `RUNNING / HEALTHY / IDLE`, `READY`,
`WITHIN_RPO`, and `CONSISTENT`. Their source snapshot evidence is `CLEANED`,
direct vCenter queries show no remaining snapshots, and successful durable
cycles cleared all stale source-open failure evidence.

## 2026-08-24 Origin Branch Full Test Release Deployment

### Source and build identity

The complete test release was built from the exact tips of the two origin work
branches. The Cloud source contains the latest `upstream/ablestack-europa`
baseline, and the qemu source contains the latest `upstream/main` baseline that
was available when the release was started.

| Repository | Branch | Built commit | GitHub Actions run |
|---|---|---|---|
| `dhslove/ablestack-cloud` | `feature/ftctl-cloud-integration` | `0d6ed725b4f7f000939b7f226c3b101321e5592d` | [`32645230251`](https://github.com/dhslove/ablestack-cloud/actions/runs/32645230251) |
| `dhslove/ablestack-qemu-exec-tools` | `feature/ftctl-cloud-integration` | `d1701561adf2e4aca5aa780e7e6e87bed1133ef4` | [`32645236471`](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/32645236471) |
| FTCTL branch release | `feature/ftctl-cloud-integration` | `d1701561adf2e4aca5aa780e7e6e87bed1133ef4` | [`32645243261`](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/32645243261) |

All three workflows completed successfully. The Cloud product version is
`4.23.0.0-Mold.Europa.202608231423.1`; the FTCTL/qemu/v2k/N2K/HangCTL version
is `0.9.5-1`. The management package SHA256 values are:

- `cloudstack-common`: `77924102fa58525947bf33ab1e6118a10691f4142e031d9423967fdd802dbf04`
- `cloudstack-management`: `b4d052c0f4304f715b6823c4d4df392ed459a573a08e5c89097ad3395335d533`
- `cloudstack-ui`: `47f771edb8b0159b68b95a4fea05fb0acb380b0186d16b56eab081406832fa2b`
- `cloudstack-usage`: `2d394ea06ae1befce9c0d7a69be5ccb1e8c47fc6606a724f112a380ba928c34e`
- `ablestack_vm_ftctl`: `f9e412711171f0fa56a1ebb17ca4312b82778a10c47127349da7dad1a3dc1942`

The Rocky 9.6 host artifacts were used for the 22 cluster and the Rocky 9.7
artifacts for the 32 cluster. Remote SHA256 verification passed on both
management servers and all six compute hosts before installation.

### Dual-cluster installation result

The four Cloud packages were installed on `10.10.22.10` and `10.10.32.10`.
The six compute hosts received the matching `cloudstack-agent` package plus the
qemu-exec-tools, FTCTL, HangCTL, v2k, and N2K packages. The 22 cluster used
native `rpm`; the 32 cluster used the required `aspkg` wrapper.

Management rollback backups are stored at:

- `10.10.22.10`: `/root/dual-dr-release-backup-20260824-000951`
- `10.10.32.10`: `/root/dual-dr-release-backup-20260824-001712`

Per-host backups are under `/root/dual-dr-host-backup-*`. Host installation was
performed one host at a time within each cluster. The 22-cluster HangCTL timer
remained intentionally masked/inactive, while the 32-cluster HangCTL timer
remained active.

The 32-cluster package cleanup hook reproduced the known same-build JAR
quarantine problem. It moved three RPM-owned JARs, including the aggregate
`cloudstack` JAR, into `legacy-lib` and temporarily prevented `ServerDaemon`
startup. Each quarantined file was compared with the newly installed RPM
payload and had an identical SHA256. The exact RPM-owned files and metadata
were restored, after which `aspkg -V cloudstack-management` reported no missing
JAR and `mold` returned to service. No package from another build was used.

### Post-deployment verification

Both management servers passed the following checks:

- `mold` and `mold-usage` are active.
- `/client/` returns HTTP 200 and `WEB-INF` is present.
- The active UI bundle contains `blockingLoadingState`, `fetchSyncProgress`,
  `extractJobId`, and `startDrProtectionGroupAction`.
- All DR schema tables are present.
- Active DR Runs, protection-group Runs, and resource leases are all zero.

All six compute hosts report `Up / Enabled` in Cloud with Agent version
`4.23.0.0-Mold.Europa-202608231423`. Their Agent and FTCTL timers are active,
NBD `nbds_max` is 32, and no transfer worker remained active after deployment.
The installed runtime files are identical on all six hosts:

- `dr_runtime.sh`: `187bd4040e26df676bc5f96e0e30709ad4dd09e0a1013ebede9f7a3c2f39c28c`
- `dr_scheduler.sh`: `9c441088b91de0071c03859af67c527cc339d528ef52b8de621fdf377fb3f6b5`

The installed scripts contain the resource-wait and VMware device-key paths,
and the obsolete cloud-managed `reverse_sync_timeout` behavior is absent. The
two clusters are therefore aligned to the same origin-built test release and
are ready for UI-driven DR regression testing.

## 2026-08-27 Terminal Cycle Reprojection And 32-Cluster Alignment

### Change and build identity

The Cloud terminal projection was hardened for a scheduler advance that occurs
after a Full Seed has already become durable. A Run-owned accepted Full Seed
Cycle remains immutable completion evidence when the current scheduler Run UUID
has advanced to the next incremental producer. The accepted sequence and token,
Run ownership, terminal Cycle state, and durable commit state must all match.

For Cloud-managed KVM replicas, Cloud's active `dr_replica` binding is now the
authority for target VM and network presence. FTCTL remains authoritative for
target storage durability, restore-point publication, and checkpoint evidence.
This avoids treating non-owning FTCTL VM/network flags as a failed target while
preserving strict storage and durability checks.

The changed-module build was run from the WSL ext4 clone
`/home/ablecloud/work/verify/cloud-cycle-20260827` and completed successfully.
`FtctlDrRuntimeProjectionAdapterTest` ran 64 tests with no failures or errors.
The final Cloud class overlay SHA256 was
`742159a5ce6fe1c5fe2b7467a9a2a23a8b8b3a3947d293365aa6a6736de4858b`.

The paired FTCTL source is commit
`0b528b6594833c2549ffe667e27e79a90152a9ef` and GitHub Actions run
[`33040777611`](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/33040777611).
The deployed `ablestack_vm_ftctl-0.9.5-1.noarch.rpm` artifact SHA256 is
`a3c3c8325657d4e9172a6812ad07f6e63bd56c67f33d1688b2c26e2fe00183d6`.

### 32-cluster deployment result

The management server `10.10.32.10` is aligned to Cloud build
`4.23.0.0-Mold.Europa.202608261320.1` for common, management, UI, and usage.
`mold` and `mold-usage` are active, `/client/` returns HTTP 200, and the active
webapp still contains `WEB-INF`.

Hosts `10.10.32.1`, `10.10.32.2`, and `10.10.32.3` are aligned to:

- `cloudstack-agent-4.23.0.0-Mold.Europa.202608261320.1`
- `ablestack_vm_ftctl-0.9.5-1`
- qemu-exec-tools, v2k, N2K, and HangCTL `0.9.5-1`

On all three hosts, `mold-agent`, `ablestack-vm-ftctl.timer`, and
`ablestack-vm-hangctl.timer` are active.

### Automatic reprojection evidence

Plan `7ec74483-8554-415d-ac56-f62f8b17fbd0` was refreshed through Cloud's
asynchronous `refreshDrProtectionView` path. The job succeeded and no direct DB
repair was performed. The accepted Run and canonical Cycle converged as follows:

- Run `379` / `b8fff36f-3e00-4064-b79c-907a1c154523`: `SYNC / SUCCEEDED`
- accepted sequence/token: `1208` /
  `7ec74483-8554-415d-ac56-f62f8b17fbd0:311`
- terminal source/authority: authoritative terminal projection
- canonical Full Seed Cycle `16612`: `READY / LOCAL_DURABLE`
- plan `51`: `READY / ENABLED / SOURCE`, with no current error

After terminal convergence, later incremental Cycles continued to complete as
`CBT_INCREMENTAL / READY / LOCAL_DURABLE`. The latest observed Cycle was
sequence `1216` with `5,087,232` transferred bytes. The Cloud API reported a
consistent projection, healthy running scheduler, idle replication activity,
target readiness, and usable update/sync/pause/release actions. This validates
the required automatic path: `Run SUCCEEDED -> plan READY -> UI action state`
without manual DB state changes.

## Cloud-managed KVM readiness projection overlay - 2026-08-27

The 32-cluster management server received a changed-class overlay for
`FtctlDrRuntimeProjectionAdapter` and a matching UI static bundle. The Cloud
package itself remains the aligned test release
`4.23.0.0-Mold.Europa.202608261320.1`; no unrelated package files were
replaced.

- deployed class SHA256:
  `19d78aa2d7ab639f77d5093161fd7ef870eeae34c8f5915d548cae2d90b7c934`
- deployed UI archive SHA256:
  `99b51248ade9e4396dbe415679daa8f3f59ac56b2aaaf145755803d574ba1a43`
- active UI entry bundle: `js/app.a5c45800.js`
- deployment backup:
  `/root/dr-kvm-ready-deploy-20260827-160750`

The deployment preserved `/usr/share/cloudstack-management/webapp/WEB-INF`,
left `mold` active, and returned HTTP 200 from `/client/`. The deployed class
hash was verified from the active aggregate Cloud JAR.

Plan `7ec74483-8554-415d-ac56-f62f8b17fbd0` converged without direct DB repair
to plan `READY / ENABLED / SOURCE` and runtime
`READY / RUNNING / HEALTHY / IDLE`. Its current Cycle is `COMPLETED`, latest
completed sequence is `348`, active replica `51` is `READY`, and Cloud target
VM id `287` remains bound. This confirms that Cloud-owned KVM VM/network
materialization and FTCTL-owned storage/checkpoint durability now converge to
one READY runtime projection.

## 2026-08-31 Three-cluster Test Release Alignment

The `feature/ftctl-cloud-integration` branches were built by GitHub Actions
from Cloud commit `912a66ec4baa3a895fef36efaf0f2be55ad482c5` and qemu tools
commit `469dbda3902d79256625dab9a3ab36f9d28df2f8`. The successful build Runs
were [Cloud 33376001864](https://github.com/dhslove/ablestack-cloud/actions/runs/33376001864)
and [qemu tools 33376444887](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/33376444887).

The Cloud package release is
`4.23.0.0-Mold.Europa.202608310915.1`; qemu-exec-tools, V2K, N2K, FTCTL,
and HangCTL are `0.9.5-1`. The same Cloud management/common/UI/usage release
was installed on `10.10.13.10`, `10.10.31.10`, and `10.10.32.10`. The same
Cloud common/Agent release and matching Rocky 9.7 or 9.8 qemu tool set were
installed on all nine compute hosts. The `13.2` SSH exception remains port
`10022`; all other deployment endpoints use port `22`.

Independent postflight verification, separate from the deployment script,
confirmed the following:

- all three Mold services are active and all three `/client/` endpoints return
  HTTP 200;
- every active webapp retains `WEB-INF` and contains the expected FTCTL UI
  markers;
- all nine routing hosts are `Up / Enabled` in their controller database;
- all nine Agents and FTCTL timers are active, with no VM-list change across
  package replacement;
- every host has `dr_runtime.sh` SHA256
  `82c20687082fe0385119fba93a91c7249ae1b89116a7faf79f543eedf588a3a5`
  and `dr_scheduler.sh` SHA256
  `1170968add33f0464e520889b63a280a48446707b71786e41201bc1c670c85f0`;
- Chrome rendered the ABLESTACK login view from the DR Plan URL on all three
  management servers.

### 32-cluster Mold incident and correction

`10.10.32.10` was responsive at the process level but its root filesystem was
100% full with about 20 KiB free. Old generated deployment/test artifacts
under `/root` and accumulated MySQL binary logs were the immediate availability
cause. Only generated, superseded deployment artifacts were removed; the Cloud
database, active webapp, package-owned runtime, DR profiles, and VM data were
preserved. Test-cluster binlog expiry was set persistently to two days after
confirming that no replication channel was configured.

After cleanup and exact-package deployment, the 32 management root filesystem
is 51% used with about 41 GiB free. Mold is active, `/client/` returns HTTP
200, `WEB-INF` is present, and no new `ServerDaemon` class-load or
`No space left on device` error was recorded after deployment. The 13 and 31
management roots are respectively 52% and 62% used after the same retention
and stale-artifact review.

## 2026-09-01 Five-cluster Current Branch Test Release

The 22 and 32 clusters were realigned together with the 12, 13, and 31 test
clusters. Cloud was built from commit
`46c5b483bbd821008bf362e9a656d56b8b369410` by successful Actions Run
[`33500908830`](https://github.com/dhslove/ablestack-cloud/actions/runs/33500908830).
The first RPM attempt encountered a transient Maven Central HTTP 403; the
failed job was rerun and the RPM, System VM, and release publication jobs all
completed successfully. The deployed Cloud release is
`4.23.0.0-Mold.Europa.202609011108.1`, and the downloaded RPM artifact SHA256
is `2d4a297c48564d5bd54cfecb6920f9f4c12f47bfa137d36318c0b742c777615a`.

qemu-exec-tools was built from commit
`469dbda3902d79256625dab9a3ab36f9d28df2f8` by successful Actions Run
[`33500674589`](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/33500674589).
The five deployed host packages are `ablestack-qemu-exec-tools`, V2K, N2K,
FTCTL, and HangCTL, all release `0.9.5-1`.

Postflight verification on both clusters confirmed:

- management, common, UI, usage, common/Agent, and all DR tool packages are
  from the paired release above;
- Mold and usage are active, `/client/` returns HTTP 200, `WEB-INF` is
  preserved, and the active UI contains the DR action/progress markers;
- the aggregate Cloud JAR reports implementation revision
  `46c5b483bbd821008bf362e9a656d56b8b369410`;
- both databases contain 23 DR tables, `cloud.dr.service.enabled=true`, all
  three routing hosts are `Up`, active DR Runs are zero, and active leases are
  zero;
- all six Agents and FTCTL timers are active, at least 32 NBD devices exist,
  and VM inventory did not change across Agent replacement;
- installed `dr_runtime.sh` SHA256 is
  `82c20687082fe0385119fba93a91c7249ae1b89116a7faf79f543eedf588a3a5`
  and `dr_scheduler.sh` SHA256 is
  `1170968add33f0464e520889b63a280a48446707b71786e41201bc1c670c85f0`.

The 22-cluster HangCTL timers remain masked by their existing host policy;
FTCTL timers and the DR execution path are active. Browser verification after
clearing stale client cache rendered the current release string
`v4.10.0-Europa-20260901-ALPHA1` from both `/drplan` entry points. Deployment
staging directories were removed after checksum and postflight verification;
the pre-deployment backup remains at
`/root/dr-test-release-backup-20260901-2010` on each node.

## 2026-09-08 Four-cluster Upstream-aligned Test Release

The Origin feature branches were first aligned with the latest upstream base
and pushed before release creation. Cloud commit
`5616f85f282f61a566d84a3b9c2e89437862166a` is zero commits behind
`upstream/ablestack-europa`; qemu tools commit
`fc61961d39053be076584b6d32b98fecc8c651fe` is zero commits behind
`upstream/main`. The upstream pull requests are
[Cloud #940](https://github.com/ablecloud-team/ablestack-cloud/pull/940) and
[qemu tools #54](https://github.com/ablecloud-team/ablestack-qemu-exec-tools/pull/54).

The successful full test release Runs were
[Cloud 34198202015](https://github.com/dhslove/ablestack-cloud/actions/runs/34198202015),
[qemu tools 34193785518](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/34193785518),
and [FTCTL 34193788559](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/34193788559).
The Cloud release is `4.23.0.0-Mold.Europa.202609080713.1`; all five qemu
tool packages are `0.9.5-1`.

Fresh-install and update DB paths were checked from the built management RPM.
`create-schema.sql`, `schema-42210to42300.sql`, and
`schema-Europa-After.sql` all contain the DR resource lease, group Run, and
canonical Cycle identity contracts. After deployment, every management DB
contained both expected tables and the two-column
`uk_dr_sync_cycle__plan_sequence` index.

The same management/common/UI/usage release was installed on
`10.10.13.10`, `10.10.31.10`, `10.10.32.10`, and `10.10.22.10`. The same
common/Agent release and qemu tool set were installed on all twelve compute
hosts. Postflight verification confirmed:

- all four Mold and usage services are active, all `/client/` endpoints return
  HTTP 200, `WEB-INF` is preserved, and the active UI exposes the DR menu;
- all management classpath JARs pass archive integrity checks and the active
  aggregate JAR reports Cloud revision `5616f85f282f61a566d84a3b9c2e89437862166a`;
- the UI static index matches the packaged UI and contains
  `blockingLoadingState`, `fetchSyncProgress`, `extractJobId`, and
  `getDrVmProtectionView`;
- all twelve Agents and FTCTL timers are active, with Cloud Agent release
  `4.23.0.0-Mold.Europa.202609080713.1` and FTCTL release `0.9.5-1`;
- installed `dr_runtime.sh` SHA256 is
  `816e5434689a24570f1dacaec37d1e4a49d40c6c7e343b092cd47c7424ddcdc2`
  and `dr_scheduler.sh` SHA256 is
  `f2d6a9d0d3ec311ca155c22cc231fd679b4a7740df99558708ec931a4c479cc9`
  on every compute host;
- active DR resource leases and nonterminal DR Runs are zero on all four
  controllers; 31, 32, and 22 each report three `Up / Enabled` routing hosts.

Cluster 13 reports two `Up / Enabled` hosts and one `Up /
ErrorInMaintenance` host. The latter state existed before this deployment;
the Agent and FTCTL service on that host are active and at the aligned package
versions, so it is retained as a separate infrastructure maintenance item.

### Deployment transaction safeguards

Package replacement exposed two deployment-runner defects that must not be
treated as product failures. First, starting Mold before all replacement JARs
were stable could produce a one-time `ZipException: zip file is empty`.
Deployment must finish the RPM transaction, overlay the packaged library,
run `sync`, validate every classpath JAR, and only then start Mold. HTTP 200 and
an error-free second startup are required before proceeding.

Second, a nested SSH command can consume a streamed shell script from standard
input and silently skip the remaining Agent copy/install steps. Host deployment
must use a local script file or `ssh -n`, then assert the exact installed
common/Agent version on every host. This release was re-deployed with direct
per-host transfer and verification; VM inventories were unchanged across the
Agent replacements.
