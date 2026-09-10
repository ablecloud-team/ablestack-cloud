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

# QCOW2 DR 12/31 Test Environment Deployment

## Purpose

This document records the test-release build and deployment baseline prepared
for the ABLESTACK local-qcow2 to local-qcow2 DR path between the 12 and 31
clusters. It records authentication profiles and verification results without
storing credential values.

## Source And Build Baseline

| Component | Repository | Branch | Source commit | GitHub Actions run | Result |
|---|---|---|---|---:|---|
| Cloud | `dhslove/ablestack-cloud` | `feature/ftctl-cloud-integration` | `7fbf4d061cbf54ea11d4b1cb6d632ea87b527f46` | `32973565265` | PASS |
| QEMU exec tools, V2K, N2K | `dhslove/ablestack-qemu-exec-tools` | `feature/ftctl-cloud-integration` | `2ca46fb454322f088718caea805aa5cd304a8296` | `32973569975` | PASS |
| FTCTL | `dhslove/ablestack-qemu-exec-tools` | `feature/ftctl-cloud-integration` | `2ca46fb454322f088718caea805aa5cd304a8296` | `32973574881` | PASS |

The deployed Cloud package version is
`4.23.0.0-Mold.Europa.202608261320.1`. The deployed FTCTL/qemu tool package
version is `0.9.5-1`.

## Artifact Integrity

| Artifact | SHA256 |
|---|---|
| Cloud release ZIP | `f24c64256e65cd553d8b717278e5b7af43f2c4c015ad6c7ead232bd0135e1df4` |
| Cloud management bundle | `c745a916bf209a7d3974e6cac3867251870e960d820a620f30f9d73756f6cc1b` |
| Cloud host common/agent bundle | `d0220ff32e7f27c62334938aa31f59561a2bb9bc8143707923601947864181ab` |
| FTCTL RPM | `3d4deb43c75ba0c2abe795ac3d8f3d6e8ac7db860c2e4c5c36ad373cb09bdfdb` |
| QEMU exec tools RPM | `d92fed1546c6958b8535d6f96158efcccda12c38d52d7ef1513256a57e0c34ab` |
| V2K RPM | `8575d421671df387d862f64a575a0e31f67cbc886d561b964ba48dcb40505000` |
| N2K RPM | `332b7ec040e501d24c741abd3212bb1e942039f4ef9c95e33502e8a2ede6a2c4` |
| Hang control RPM | `1e4fda11ed616012797256c85fe2d15aedef605ff897439a9e080b37954267da` |

## Environment And Authentication Profiles

| Cluster | Role | Endpoints | SSH port | Successful profile |
|---|---|---|---:|---|
| 12 | Management | `10.10.12.10` | 22 | `12-management-root`, `12-admin` |
| 12 | Compute | `10.10.12.1`, `.2`, `.3` | 22 | `12-compute-root` |
| 31 | Management | `10.10.31.10` | 22 | `31-management-root`, `31-admin` |
| 31 | Compute | `10.10.31.1`, `.2`, `.3` | 22 | `31-compute-root` |

All eight SSH endpoints and both Cloud administrator logins were tested
successfully. Credential values are stored as GitHub `dr-test` Environment
secrets in both `dhslove` repositories. The secret contract uses separate
management and compute SSH credentials because the 31 cluster does not use one
common password for both roles.

The following secret name families were added without exposing their values:

- `ABLESTACK_TEST_12_*`: management host, compute hosts, SSH port/user,
  management/compute SSH password, administrator user/password.
- `ABLESTACK_TEST_31_*`: management host, compute hosts, SSH port/user,
  management/compute SSH password, administrator user/password.

## Deployment Procedure And Backups

Management-server backups were created under
`/root/qcow2-dr-release-backup-20260826` on both management servers. They
include the installed package list, `cloud` and `cloud_usage` database dumps,
Cloud configuration, management libraries, the active webapp, and checksums.

Compute-host backups were created under
`/root/qcow2-dr-host-backup-20260826` on all six compute hosts.

Cloud packages were installed with the administrator package wrapper `aspkg`.
The active UI was updated without replacing the webapp root, and
`WEB-INF` was preserved before and after deployment.

The 31 compute hosts did not initially contain the `nbd` package required by
the qemu tool release. The exact `nbd-3.25-1.el9.x86_64` dependency from the
validated build dependency set was installed before the DR packages.

The 12.3 Agent had a pre-existing missing cluster license path. The identical
license file already active on 12.1 and 12.2 was installed at the expected
cluster license path with directory mode `0700` and file mode `0600`. The
license API then reported the same non-expiring cluster license and the Agent
connected normally.

## Management Verification

| Check | 12 cluster | 31 cluster |
|---|---|---|
| `mold` | active | active |
| `mold-usage` | active | active |
| `/client/` | HTTP 200 | HTTP 200 |
| Active `WEB-INF` | present | present |
| DR schema | version `4.23.0.0`, 23 `dr_%` tables | version `4.23.0.0`, 23 `dr_%` tables |
| `cloud.dr.service.enabled` | `true` | `true` |
| `listDrSites` | authenticated empty response | authenticated empty response |
| `listDrPlans` | authenticated empty response | authenticated empty response |

The initial API preflight returned `Unknown API command: listDrSites` even
though the DR module and classes were loaded. The installed configuration had
`cloud.dr.service.enabled=false`, which intentionally makes the DR pluggable
service publish an empty command list. The setting was changed to `true` on
both clusters and both management services were restarted. Authenticated
`listDrSites` and `listDrPlans` then returned valid empty responses.

The active UI bundles contain `blockingLoadingState`, `fetchSyncProgress`, and
`extractJobId`. Package verification found no missing package-owned Cloud JAR,
and the current startup logs contain no `ClassNotFoundException`,
`NoClassDefFoundError`, or schema upgrade failure.

## Compute Host Verification

All six hosts report the following common state:

- `cloudstack-agent-4.23.0.0-Mold.Europa.202608261320.1`
- `ablestack-qemu-exec-tools-0.9.5-1`
- `ablestack_v2k-0.9.5-1`
- `ablestack_n2k-0.9.5-1`
- `ablestack_vm_ftctl-0.9.5-1`
- `ablestack_vm_hangctl-0.9.5-1`
- `nbd-3.25-1.el9`
- active `mold-agent`
- active `ablestack-vm-ftctl.timer`
- loaded NBD module with `nbds_max=32`
- available `qemu-img`, `qemu-nbd`, `nbdkit`, and `virsh`

Installed runtime hashes are identical across the six hosts:

| Runtime file | SHA256 |
|---|---|
| `dr_runtime.sh` | `5c7d1537637df00a25a0a10a0691d95db0a2b3f67bed75ea81ebc6125375ecaa` |
| `dr_scheduler.sh` | `004790aa6f5f35722d395a6cd6e7e9c11e3ae5ad3651e5c72460ab023180c1bf` |

Cloud DB reports all three routing hosts in each cluster as `Up` and
`Enabled`, with the deployed Europa Agent version.

## Network And Clean-State Verification

- 12 compute to 31 management: SSH 22 and Cloud API 8080 are reachable.
- 31 compute to 12 management: SSH 22 and Cloud API 8080 are reachable.
- Both clusters have zero active DR sites and plans.
- Both clusters have zero DR run, resource lease, and sync-cycle rows.

## Readiness Decision

The package, service, API, Agent, FTCTL, NBD, and cross-cluster management
connectivity deployment gate is **PASS** on both clusters.

The 31 cluster currently has an active shared primary-storage record. The 12
cluster currently has no active `storage_pool` or user VM record. Therefore the
software deployment task is complete, but the first local-qcow2 DR functional
test must begin by configuring or discovering 12-cluster local primary storage
and creating/selecting the source qcow2 VM. That infrastructure setup is not a
package deployment failure and must be verified before creating the first DR
plan.

## Next Test Handoff

1. Configure or verify local file-backed primary storage on the 12 cluster.
2. Create or select the 12-cluster source VM whose disk is local qcow2.
3. Verify a local file-backed target storage option on the 31 cluster; the
   currently registered pool is a shared mount point and is not by itself a
   local-qcow2 target.
4. Register the 12 and 31 DR sites in the controller cluster UI.
5. Run inventory discovery and create a single qcow2-to-qcow2 DR plan before
   expanding to multiple VMs.

## 2026-08-28 31-Cluster DR Release Restoration

The 31-cluster management server was found running the older
`4.23.0.0-ABLESTACK.Mold.SNAPSHOT.1` packages. `/client/` returned HTTP 200,
but the active UI contained none of the FTCTL markers or DR locale entries, so
the Disaster Recovery menu was absent. The DR schema and
`cloud.dr.service.enabled=true` configuration were still present; this was a
package/UI baseline regression rather than a disabled DR service.

The retained, checksum-verified 2026-08-26 test release was restored. The
management rollback backup is
`/root/qcow2-dr-release-backup-20260828-005111`. The Cloud common,
management, UI, and usage packages now all report
`4.23.0.0-Mold.Europa.202608261320.1`. Package verification reports only the
expected local configuration and log metadata changes, with no missing
package-owned JAR.

The compute hosts already contained qemu-exec-tools, V2K, N2K, FTCTL, and
HangCTL `0.9.5-1`. Their older Snapshot Agent/common packages were replaced
one host at a time with `4.23.0.0-Mold.Europa.202608261320.1`. Backups are:

- `10.10.31.1`: `/root/qcow2-dr-host-backup-20260828-005211`
- `10.10.31.2`: `/root/qcow2-dr-host-backup-20260828-005218`
- `10.10.31.3`: `/root/qcow2-dr-host-backup-20260828-005224`

Post-deployment verification is PASS:

- `mold` and `mold-usage` are active; `/client/` returns HTTP 200.
- The active webapp preserves `WEB-INF` and contains `blockingLoadingState`,
  `fetchSyncProgress`, and `extractJobId`.
- Korean locale entries for `DR 사이트` and `DR 계획` are present.
- An administrator browser session displays the `재해복구` menu and opens
  both DR site and DR plan list pages successfully.
- All three routing hosts are `Up/Enabled` with the Europa Agent version.
- `mold-agent`, FTCTL, and HangCTL timers are active on all three hosts.
- NBD reports `nbds_max=32`; FTCTL runtime hashes remain identical across the
  three hosts.

The 31 cluster is restored to the documented QCOW2 DR test-release baseline
and is ready for UI-driven DR site and plan configuration.

## Existing 13-to-31 SharedMountPoint Plan Reprotect Contract (2026-08-29)

The authoritative functional target is the existing 31-controller plan
`41886f03-c19e-4382-927d-89bc4d6ce8e9` (`rocky9-vm DR Plan`). Its source
and target disks are both on the registered `/mnt/glue-gfs`
`SharedMountPoint` pools in clusters 13 and 31. Validation must continue on
this plan; creating a substitute plan or using local storage is out of scope.

The first UI Failover completed and promoted the target VM, but UI Reprotect
failed before Agent dispatch because the authority-spec producer emitted
contract `2026-08-26` while the KVM wrapper still hard-coded `2026-07-23`.
This is a Cloud command-contract regression, not a qcow2 checkpoint or
SharedMountPoint transport failure.

The Reprotect authority contract has one canonical version constant in
`FtctlDrActionCommand`. The DR authority-spec producer and the KVM Agent
consumer must both reference it. A release must fail its unit gate if either
side accepts a different literal. This change does not alter transfer,
checkpoint, target materialization, or any previously validated VMware/RBD
provider behavior.

| Area | AS-IS | TO-BE |
|---|---|---|
| Contract owner | Producer and consumer keep separate version literals | Core command owns one shared version constant |
| Reprotect dispatch | Valid `2026-08-26` authority is rejected by Agent wrapper | Producer and consumer validate the same contract |
| Failure timing | UI Run fails after successful preflight but before FTCTL | Valid contract reaches FTCTL and remains observable in UI |
| Regression gate | Adapter tests cover only the producer | Producer and KVM consumer versions are both tested |

The UI PASS gate remains strict: the same plan must show Reprotect
`SUCCEEDED`, reverse protection ready, Failback `SUCCEEDED`, source VM
`Running`, target authority released, and terminal operation history with no
stale active Run. Database or host evidence supplements but does not replace
the UI result.

### Contract Patch Test Release And Deployment

The canonical contract patch was built from Cloud commit `06cdd9feac` by
GitHub Actions run `33221768861`. The release artifact SHA256 is
`0a4a78087779f2af57fd1110af8341fed00538e2ae53dc1cf6859e031797eb55`,
and its package release is `4.23.0.0-Mold.Europa.202608282352.1`.

The Cloud management/common/UI/usage packages were deployed to the 13 and 31
management servers. The Cloud common/Agent packages were deployed one host at
a time to `13.1`, `13.2`, `13.3`, `31.1`, `31.2`, and `31.3`. Post-deployment
verification found both management services active, both `/client/` endpoints
returning HTTP 200, both active webapps retaining `WEB-INF`, and all six Agents
active on the same package release.

The 13 management package cleanup exposed the already documented package-owned
JAR quarantine condition. Only the exact newly installed JARs were restored
from that deployment's `legacy-lib` backup before restarting Mold. This was a
package installation recovery and did not alter DR plan, Run, VM, or storage
state.

The preserved UI regression baseline is Reprotect Run
`2a531906-e9ce-4a3e-b63d-478d927a8c77`, which terminated as `FAILED` with
`DR_ENGINE_ACTION_FAILED` before the contract patch. The plan remains
`FAILED_OVER`, and Failover Run `ed096e21-8bab-4760-b912-7c2c64da501c`
remains the last successful authority transition. The patched UI test must
create a new Reprotect Run rather than modifying or retrying either historical
row directly.

### Capability-Negotiated Reprotect Authority Contract

The first patched UI retry reached FTCTL but exposed a third independent
contract literal in the runtime authority persistence gate. Run
`2c5b1863-52b9-4918-b425-be7c3cd86d17` terminated as
`DR_REPROTECT_AUTHORITY_INVALID` because Cloud produced `2026-08-26` while
FTCTL accepted only `2026-07-23`.

The structural correction keeps one current writer version in Cloud and one
supported-reader list in FTCTL. FTCTL publishes that list through
`dr-capabilities`. The KVM capability wrapper projects it as structured Agent
data, and the unified action adapter refuses Reprotect before dispatch unless
the current Cloud writer version is advertised. The action wrapper validates
that the command and immutable authority JSON declare the same version; it no
longer owns a separate date literal.

The FTCTL reader list is used by both `dr-capabilities` and authority-spec
persistence. Its release smoke consumes the advertised list, validates every
advertised version, and rejects an unknown version. VMware-to-RBD final
checkpoint/guest-preparation smokes and ABLESTACK RBD-to-RBD reverse transport
smokes remain mandatory in the same release workflow. This turns a future
cross-repository version skew into a capability-preflight failure rather than
a runtime Reprotect failure.

## 2026-08-31 Current Branch Test Release

Cluster 31 was aligned with Cloud Actions Run
[`33376001864`](https://github.com/dhslove/ablestack-cloud/actions/runs/33376001864)
and qemu tools Run
[`33376444887`](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/33376444887).
The installed Cloud release is
`4.23.0.0-Mold.Europa.202608310915.1`, and the qemu-exec-tools, V2K, N2K,
FTCTL, and HangCTL packages are `0.9.5-1` from that qemu Run.

The management root filesystem was 95% used before deployment. Superseded
generated deployment artifacts were removed and, after confirming no MySQL
replication channel, test binlog expiry was set to two days. The current root
usage is 62% with about 32 GiB free. Mold is active, `/client/` returns HTTP
200, `WEB-INF` and the FTCTL UI markers are present, and no post-deployment
ServerDaemon class-load error is present.

All three routing hosts are `Up / Enabled`; all Agents and FTCTL timers are
active. The VM list was unchanged across package replacement and the installed
runtime/scheduler hashes match the three-cluster release record. Host `31.1`
was historically deployed with native `rpm`; the current 2026-09-01 release
uses the administrator `aspkg` wrapper consistently on all 31-cluster nodes.

## 2026-09-01 Five-cluster Current Branch Test Release

Clusters 12 and 31 are now aligned with Cloud commit
`46c5b483bbd821008bf362e9a656d56b8b369410` from successful Actions Run
[`33500908830`](https://github.com/dhslove/ablestack-cloud/actions/runs/33500908830)
and qemu tools commit `469dbda3902d79256625dab9a3ab36f9d28df2f8`
from successful Run
[`33500674589`](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/33500674589).
The Cloud RPM artifact SHA256 is
`2d4a297c48564d5bd54cfecb6920f9f4c12f47bfa137d36318c0b742c777615a`.

The deployed Cloud package release is
`4.23.0.0-Mold.Europa.202609011108.1`; qemu-exec-tools, V2K, N2K, FTCTL, and
HangCTL are `0.9.5-1`. Cluster 12 was upgraded from Cloud 4.21 and its DR
schema was created by the normal management startup path. Both clusters now
have 23 DR tables and `cloud.dr.service.enabled=true`.

Postflight results are PASS on both management servers and all six hosts:

- Mold and usage are active, `/client/` returns HTTP 200, `WEB-INF` is
  present, no package-owned management JAR is missing, and no new class-load,
  disk-full, or schema-upgrade error was found;
- the active Cloud JAR implementation revision is
  `46c5b483bbd821008bf362e9a656d56b8b369410`, and the UI exposes the current
  DR action/progress bundle markers;
- all routing hosts are `Up` on Agent release
  `4.23.0.0-Mold.Europa-202609011108`, with no VM-list change during Agent
  replacement;
- FTCTL and HangCTL timers are active, at least 32 NBD devices exist, and the
  five DR tool packages are present on every host;
- runtime SHA256 is
  `82c20687082fe0385119fba93a91c7249ae1b89116a7faf79f543eedf588a3a5`
  and scheduler SHA256 is
  `1170968add33f0464e520889b63a280a48446707b71786e41201bc1c670c85f0`;
- active DR Runs, stale active Runs, and active resource leases are all zero.

Browser verification rendered the current release string
`v4.10.0-Europa-20260901-ALPHA1` from both `/drplan` entry points. Deployment
staging was removed after verification; the per-node backup remains at
`/root/dr-test-release-backup-20260901-2010`.
