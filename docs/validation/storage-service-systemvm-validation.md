# Storage Service SystemVM Validation Plan

## Purpose

This document is the single validation record for the ABLESTACK Storage Service
SystemVM feature. It must be updated during every verification pass with:

- exact test environment
- executed commands or UI path
- observed result
- defects, regressions, and improvement items
- retest result after fixes

The document covers the current first implementation on `ablestack-diplo`,
including:

1. Storage capacity expansion for file services.
2. Serving NFS and SMB from existing ABLESTACK volumes attached to the Storage
   Service SystemVM.
3. NVMe-oF `KERNEL_NVMET` prerequisite validation.
4. NVMe-oF `SPDK` planned-state behavior, where Storage Service must not
   perform VM-level HugePage, NUMA, CPU pinning, memlock, SR-IOV, or PCI
   passthrough configuration.

## Validation Rules

- Do not store passwords, API keys, API secrets, or SSH credentials in this
  document.
- Record all dates with timezone.
- Record exact commit SHA for every test run.
- Prefer UI-led tests first, then use API, DB, QGA, SystemVM logs, and client
  commands as evidence. Direct API-only tests are allowed only for preparation,
  debug, or backend fault isolation, and must not replace the UI workflow.
- Any failed step must produce one of:
  - a code fix
  - a design update
  - a documented limitation with an operator-visible error message
- SPDK must remain gated until VM Runtime Capability support is implemented.
- Functional test cases `TC-01` through `TC-12` must not be marked `Pass` until
  the required Mold UI workflow has been executed, the resulting async jobs and
  backend state have been verified, and all required preparation stages `P-00`
  through `P-08` are complete. API/DB-only dry runs must be marked `Dry Run`,
  not `Pass`.
- UI look-and-feel validation is part of release acceptance, not a cosmetic
  after-check. A UI-led functional test cannot be marked final `Pass` when the
  same path has unresolved blocking readability, layout, dark-mode, i18n, or
  interaction defects.
- For every UI-led test, record both:
  - functional status: API/job/backend/runtime/client behavior
  - look-and-feel status: layout, readability, theme, i18n, feedback, and
    operator workflow clarity
- A test may be recorded as `Functional Pass / Look-and-feel Defect` when the
  backend behavior is correct but the UI needs correction. The final release
  status remains `Pass With Defect` until the UI defect is fixed and retested.
- For NFS create/update tests, success requires both desired-state persistence
  and a live Ganesha runtime confirmation. In `V4_ONLY` mode the supported
  isolation unit is the NFS listener port group, not an individual
  `listen IP + port` endpoint. If the export or ACL rows exist but the SystemVM
  does not report the configured listener port group or does not expose the
  selected exports through that port, the test must be marked as failed even if
  the DB rows were created.
- NFS runtime verification must distinguish protocol mode and listener scope:
  `V4_ONLY` is verified with `mount -t nfs4` against the selected listener
  port groups, while `V3V4_DUAL` must also verify `rpcbind` registration and v3
  client connectivity. Earlier notes that described NFS as a fixed
  service-wide 2049-only server are historical checkpoints and are superseded
  by the NFSv4-only port-group validation model.
- NFS runtime verification must also include a temporary client-mount probe
  from inside the Storage Service System VM. A listener that accepts TCP but
  does not expose the client-visible export root is a failed runtime apply,
  even when the DB rows and endpoint rows are present.
- The NFS protocol mode selector must be exercised in all three UI entry
  points: SharedFS create, NFS export create, and NFS export edit. Port
  updates remain protocol-listener operations. In `V4_ONLY`, export create/edit
  selects listener port groups. In `V3V4_DUAL`, per-export port group
  selection remains unsupported and must stay disabled or hidden.
- The Korean UI should avoid English text except accepted technical protocol
  names such as `NFS`, `SMB`, `iSCSI`, `NVMe-oF`, `IQN`, `NQN`, `CHAP`,
  `SPDK`, and `Active Directory`.

## UI Look-And-Feel Quality Gates

Apply these quality gates to `TC-01` through `TC-12` whenever the path uses the
Mold UI.

| Gate | Required Checks | Blocking Examples |
| --- | --- | --- |
| Theme | Normal mode and dark mode are both readable; Ant Design Vue states, alerts, disabled labels, radio/checkbox labels, tables, tabs, modals, and tooltips inherit appropriate colors | Text blends into background, alert icon disappears, disabled text is unreadable, dark-mode card border hides section boundaries |
| Layout | Dialogs and tabs fit the viewport; fixed footers do not overlap scrollable content; repeated cards and tables have stable dimensions; long values wrap cleanly | Modal exceeds browser height, `Cancel`/`OK` overlaps sections, review values wrap into unreadable columns |
| Information architecture | Existing SharedFS entry points are preserved; creation, management, and monitoring are separated; service sections are ordered by protocol and do not mix unrelated settings | A legacy `Access` tab exposes only old NFS instructions, service state controls appear in a monitoring-only tab |
| Operator wording | Labels explain operational meaning, not raw API names; unit selectors are clear; generated defaults are visible; dangerous or irreversible actions are explicit | Raw IDs required where a dropdown is available, `quotabytes` or `resize allowed` appears without business meaning |
| i18n | Korean text is shown where translations exist; protocol terms remain intentionally technical; no mojibake or untranslated UI keys appear | Garbled Korean, raw i18n key, unexpected English sentence in Korean UI |
| Interaction feedback | Async jobs, validation errors, retries, partial failures, and post-submit verification are visible and actionable | UI reports success before dependent setup jobs finish, error message lacks the failed phase |
| Sensitive data | Passwords, CHAP secrets, DH-HMAC-CHAP keys, API keys, and secrets are never shown in review panels, tables, logs, or persisted state | AD password remains in the modal after submit, CHAP secret appears in result table |

Use this status format for new UI result rows:

| Run ID | Subcase/Profile | Mode/View | Functional Status | Look-And-Feel Status | Final Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- | --- |
|  |  | Normal + Dark / desktop + narrow viewport when applicable | Not Run | Not Run | Not Run |  |  |

## Validation Flow Overview

The validation is split into preparation stages and UI-led functional plus
look-and-feel test cases.

Preparation stages are mandatory because the current implementation depends on:

- an updated Management Server and API set
- KVM host agent support for `StorageServiceHostCommand`
- a Storage Service SystemVM template that includes QGA, service packages, and
  `/usr/local/bin/ablestack-storagectl`
- a running Storage Service SystemVM or equivalent test VM
- client VMs and data volumes for protocol-level validation

Overall sequence:

| Order | ID | Type | Name | Required Before |
| --- | --- | --- | --- | --- |
| 0 | P-00 | Preparation | Repository and build artifact readiness | Any deployment |
| 1 | P-01 | Preparation | Management Server deployment readiness | API tests |
| 2 | P-02 | Preparation | KVM host agent deployment readiness | QGA/SystemVM tests |
| 3 | P-03 | Preparation | Storage Service SystemVM template build readiness | Storage Service VM creation |
| 4 | P-04 | Preparation | Storage Service SystemVM package verification | Protocol tests |
| 5 | P-05 | Preparation | Cloud environment readiness | API and VM lifecycle tests |
| 6 | P-06 | Preparation | Test volume readiness | Existing-volume and resize tests |
| 7 | P-07 | Preparation | Client VM readiness | NFS/SMB/iSCSI/NVMe-oF client tests |
| 8 | P-08 | Preparation | Observability and rollback readiness | Any destructive or stateful test |
| 9-20 | TC-01..TC-12 | Functional + Look-and-feel | UI-led lifecycle, service-management, monitoring, and UX/theme scenarios | Release readiness |

## Test Environment Record

Create one row per validation pass.

| Run ID | Date/Time | Branch | Commit | Cloud | Zone | SystemVM Template | Tester | Result |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| STATIC-20260526-01 | 2026-05-26 Asia/Seoul | `codex/diplo-storage-service-design` | `610f2bdf78` | local build only | N/A | N/A | Codex | Pass |
| P00-20260526-01 | 2026-05-26 Asia/Seoul | `codex/diplo-storage-service-design` | `1d67d683c609` | local build only | N/A | N/A | Codex | Pass |
| P03-PREP-20260526-01 | 2026-05-26 22:52:05 +09:00 | `codex/europa-storage-service` | `40bd0a8c754` plus working-tree template prep changes | 22.x target, local template build host | Zone-22 | Not built yet | Codex | Ready To Build |
| P03-20260526-01 | 2026-05-26 23:46:19 +09:00 | `codex/europa-storage-service` | `40bd0a8c754` plus working-tree template prep changes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `systemvmtemplate-4.22.0.0-x86_64-kvm-202605262304.qcow2.bz2` | Codex | Build Pass |
| P03-20260527-01 | 2026-05-27 00:56:33 +09:00 | `codex/europa-storage-service` | `40bd0a8c754` plus working-tree template prep changes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605262304` | Codex | Pass |
| P04-20260527-01 | 2026-05-27 01:10:37 +09:00 | `codex/europa-storage-service` | `40bd0a8c754` plus working-tree template prep changes | 22.x target, 10.10.22.10 Management Server, 10.10.22.1 KVM host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605262304` | Codex | Pass |
| P04-FIX-20260527-01 | 2026-05-27 01:32:00 +09:00 | `codex/europa-storage-service` | `40bd0a8c754` plus working-tree improvement fixes | 22.x target, 10.10.22.10 Management Server | Zone-22 | `SystemVM Template Storage Service (KVM) 202605262304` | Codex | Pass |
| P05-20260527-01 | 2026-05-27 01:39:14 +09:00 | `codex/europa-storage-service` | `40bd0a8c754` plus working-tree improvement fixes | 22.x target, 10.10.22.10 Management Server, 10.10.22.1/.2/.3 KVM hosts | Zone-22 | `SystemVM Template Storage Service (KVM) 202605262304` | Codex | Pass With Wanings |
| P06-20260527-01 | 2026-05-27 01:59:58 +09:00 | `codex/europa-storage-service` | `40bd0a8c754` plus working-tree improvement fixes | 22.x target, 10.10.22.10 Management Server, 10.10.22.3 KVM host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605262304` | Codex | Pass With Defect |
| P06-FIX-20260527-01 | 2026-05-27 02:14:00 +09:00 | `codex/europa-storage-service` | `40bd0a8c754` plus working-tree improvement fixes | 22.x target, 10.10.22.10 Management Server, 10.10.22.1/.2/.3 KVM hosts | Zone-22 | `SystemVM Template Storage Service (KVM) 202605262304` | Codex | Pass |
| P07-20260527-01 | 2026-05-27 09:17:10 +09:00 | `codex/europa-storage-service` | `40bd0a8c754` plus working-tree improvement fixes | 22.x target, 10.10.22.10 Management Server, 10.10.22.1/.3 KVM hosts | Zone-22 | `SystemVM Template Storage Service (KVM) 202605262304` | Codex | Pass With Wanings |
| P07-FIX-20260527-01 | 2026-05-27 10:18:32 +09:00 | `codex/europa-storage-service` | `40bd0a8c754` plus working-tree SystemVM DHCP persistence fixes | 22.x target, SystemVM template source and current P04 Storage Service VM | Zone-22 | Next rebuilt Storage Service SystemVM template | Codex | Runtime Pass, Template Rebuild Pending |
| P08-20260527-01 | 2026-05-27 11:35:14 +09:00 | `codex/europa-storage-service` | `40bd0a8c754` plus working-tree validation and DHCP persistence fixes | 22.x target, 10.10.22.10 Management Server, 10.10.22.1/.2/.3 KVM hosts, current P04 Storage Service VM, P07 client VM | Zone-22 | `SystemVM Template Storage Service (KVM) 202605262304` | Codex | Pass With Wanings |
| P03-REBUILD-20260528-01 | 2026-05-28 14:30:00 +09:00 | `codex/europa-storage-service` | `40bd0a8c754e` plus working-tree Storage Service UI/API/SystemVM changes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605281348` | Codex | Pass |
| P03-REBUILD-20260528-02 | 2026-05-28 23:18:07 +09:00 | `codex/europa-storage-service` | `40bd0a8c754e` plus working-tree Storage Service UI/API/SystemVM changes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605282236` | Codex | Pass |
| P03-REBUILD-20260529-01 | 2026-05-29 00:49:22 +09:00 | `codex/europa-storage-service` | `40bd0a8c754e` plus working-tree Storage Service root-name exposure changes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605290014` | Codex | Pass |
| P03-REBUILD-20260529-02 | 2026-05-29 14:10:13 +09:00 | `codex/europa-storage-service` | `40bd0a8c75` plus working-tree Storage Service monitor-cache and UI table changes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605291322` | Codex | Pass |
| P03-REBUILD-20260530-01 | 2026-05-30 01:55:21 +09:00 | `codex/europa-storage-service` | `40bd0a8c75` plus working-tree integrated Storage Service API/UI/SystemVM changes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605300121` | Codex | Pass |
| P03-REBUILD-20260530-02 | 2026-05-30 21:45:00 +09:00 | `codex/europa-storage-service` | working tree with async create, SMB local-account, and SMB ACL fixes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605302125` | Codex | Pass |
| P03-REBUILD-20260531-01 | 2026-05-31 01:29:00 +09:00 | `codex/europa-storage-service` | working tree with TC-03C iSCSI table and file-backed LUN fixes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605310028` | Codex | Pass |
| P03-REBUILD-20260531-02 | 2026-05-31 15:36:18 +09:00 | `codex/europa-storage-service` | working tree with TC-03D NVMe-oF idempotent reconcile, namespace backing, and stale Host ACL cleanup fixes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605311453` | Codex | Pass |
| P03-REBUILD-20260531-03 | 2026-05-31 18:56:00 +09:00 | `codex/europa-storage-service` | working tree with TC-03D-02 NVMe-oF DH-HMAC-CHAP enforcement, runtime secret redaction, and session attribution fixes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `SystemVM Template Storage Service (KVM) 202605311826` | Codex | Pass |
| P03-REBUILD-20260531-04 | 2026-05-31 23:31:00 +09:00 | `codex/europa-storage-service` | working tree with verified NVMe-oF session connected-time and subsystem attribution fix | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `systemvm-template-storage-service-kvm-202605312252` | Codex | Pass |
| P03-REBUILD-20260602-01 | 2026-06-02 19:12:30 +09:00 | `codex/europa-storage-service` | working tree with TC-04A NFS monitor-cache, NFS ACL instance filtering, and action-modal fixes | 22.x target, WSL template build host, 10.10.22.10 registration host | Zone-22 | `SystemVM Template Storage Service (KVM) 202606021910` | Codex | Pass |
| P03-REBUILD-20260618-01 | 2026-06-18 16:58:00 +09:00 | `codex/europa-storage-service` | working tree with NFS session endpoint normalization and Ganesha listener/export attribution | 22.x target, WSL template build host, 10.10.22.10 HTTP template host | Zone-22 | `systemvmtemplate-4.22.0.0-x86_64-kvm-202606181623.qcow2.bz2` | Codex | Build/Upload Pass |

## Current Static Verification Result

| Check | Command | Result | Notes |
| --- | --- | --- | --- |
| Diff whitespace check | `git diff --check` | Pass | CRLF wanings only on Windows checkout |
| SystemVM script syntax | `bash -n systemvm/debian/usr/local/bin/ablestack-storagectl` | Pass | Verified in RockyLinux-9.7 WSL |
| API module build | `mvn -pl api -DskipTests install` | Pass | Verified in WSL ext4 worktree |
| Server/schema build | `mvn -pl engine/schema,server -am -DskipTests install` | Pass | Verified in WSL ext4 worktree |

## Current Readiness Status

As of 2026-05-27, static code/build verification, Europa forward-porting,
Management Server deployment, KVM host agent deployment, Storage Service
SystemVM template registration, and Storage Service SystemVM package
verification have been completed. Cloud readiness and protocol client
preparation are usable for the next preparation steps, with capacity and
Storage Service VM DHCP lease caveats recorded in P-05 and P-07.
No protocol-level functional validation can be marked `Pass` yet because test
volumes and clients are prepared, but protocol desired state has not been
applied yet.

| ID | Area | Current Status | Impact |
| --- | --- | --- | --- |
| P-00 | Repository and build artifact readiness | Complete | `ablestack-europa` is synchronized with upstream and aligned `4.22.0.0-SNAPSHOT` API/server/schema/KVM artifacts were built in the WSL ext4 worktree |
| P-01 | Management Server deployment readiness | Complete | Aligned `4.22.0.0-SNAPSHOT` API/server artifacts were deployed, `cloudstack` jar was repacked to avoid duplicate module definitions while preserving fat-jar resources, DB migration and configuration succeeded, `mold` is running, and authenticated API discovery confirms Storage Service APIs are registered |
| P-02 | KVM host agent deployment readiness | Complete | Storage Service host command classes and KVM QGA wrapper were patched into all three 22.x KVM hosts, `mold-agent.service` restarted successfully, and Management Server reports the hosts `Up` and `Enabled` |
| P-03 | Storage Service SystemVM template build readiness | Complete | Storage Service-ready KVM SystemVM template was built, staged for HTTP download, registered as a cross-zone KVM SYSTEM template, and reached `Download Complete` / `isready=true` in Zone-22 |
| P-04 | Storage Service SystemVM package verification | Complete | A Storage Service-ready SharedFS VM was deployed from the new SYSTEM template, bound to a Storage Service instance, and verified through Engine to host Agent to QGA to `/usr/local/bin/ablestack-storagectl` |
| P-05 | Cloud environment readiness | Complete With Wanings | Zone, cluster, hosts, storage, template, account, service offering, and Storage Service VM network reachability are usable; Pod private IP capacity exhaustion is a general infrastructure observation, not a blocker for the current SharedFS/Storage Service VM because it uses the selected guest network; protocol port checks remain deferred until services and clients are prepared |
| P-06 | Test volume readiness | Complete After Fix | Four isolated 2 GiB RBD test volumes are ready and detached, including blank, XFS, ext4, and resize candidates; the KVM RBD hot attach `StackOverflowError` defect was patched on all three 22.x hosts and retested successfully with attach, libvirt block visibility, detach, and final detached volume state |
| P-07 | Client VM readiness | Complete With Wanings | Rocky 9.4 client VM `p07-protocol-client-20260527-0909` is running with NFS, SMB, iSCSI, and NVMe-oF client tools installed and can reach the Storage Service VM after manually renewing the Storage Service VM DHCP lease; fix persistent DHCP renewal before long-running protocol tests |
| P-08 | Observability and rollback readiness | Complete With Wanings | Log paths, rollback artifact backups, runtime restore points, and test-resource isolation were verified; TC state-changing tests can start; the durable SystemVM DHCP fix, Storage Service session-management commands, monitor-cache service, and latest API/UI integration have been included in rebuilt template `SystemVM Template Storage Service (KVM) 202605300121`, but still require fresh Storage Service VM validation |

## Preparation Stages

### P-00 Repository And Build Artifact Readiness

Goal: verify the source branch can produce deployable artifacts.

Steps:

1. Confirm local `ablestack-diplo`, `origin/ablestack-diplo`, and
   `upstream/ablestack-diplo` are synchronized.
2. Confirm the work branch is based on the updated local `ablestack-diplo`.
3. Build API, schema, server, KVM plugin, and SystemVM package/script artifacts
   needed for deployment.
4. Record artifact paths and commit SHA.

Expected:

- Static build succeeds.
- Deployable jars/scripts are available.
- No unrelated local files are included in deployment.

Result:

| Run ID | Branch | Commit | Artifact | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- |
| STATIC-20260526-01 | `codex/diplo-storage-service-design` | `610f2bdf78` | API/server/schema build | Pass | Maven build passed in WSL ext4 worktree |  |
| P00-20260526-01 | `codex/diplo-storage-service-design` | `1d67d683c609` | Base sync and deployable build artifacts | Pass | `ablestack-diplo`, `origin/ablestack-diplo`, and `upstream/ablestack-diplo` all at `849146ebdecd`; work branch is based on that commit; static checks and Maven builds passed |  |
| P00-20260526-02 | `codex/europa-storage-service` | `a0701701661` | Europa forward-port and deployable `4.22` build artifacts | Pass | `ablestack-europa`, `origin/ablestack-europa`, and `upstream/ablestack-europa` all at `7eb3b6eeaa`; work branch was created from local `ablestack-europa`; Storage Service commits were forward-ported from diplo; `git diff --check` passed; WSL builds passed for API, engine/schema plus server, and KVM plugin | Europa port required one API compatibility fix: `finalyzeAccountId` was corrected to `finalizeAccountId` |

P00-20260526-01 artifact candidates:

| Area | Artifact |
| --- | --- |
| API | `/root/work/ablestack-cloud-p00/api/target/cloud-api-4.21.0.0-SNAPSHOT.jar` |
| Schema | `/root/work/ablestack-cloud-p00/engine/schema/target/cloud-engine-schema-4.21.0.0-SNAPSHOT.jar` |
| Server | `/root/work/ablestack-cloud-p00/server/target/cloud-server-4.21.0.0-SNAPSHOT.jar` |
| Agent | `/root/work/ablestack-cloud-p00/agent/target/cloud-agent-4.21.0.0-SNAPSHOT.jar` |
| KVM plugin | `/root/work/ablestack-cloud-p00/plugins/hypervisors/kvm/target/cloud-plugin-hypervisor-kvm-4.21.0.0-SNAPSHOT.jar` |
| SystemVM control script | `/root/work/ablestack-cloud-p00/systemvm/debian/usr/local/bin/ablestack-storagectl` |
| SystemVM setup script | `/root/work/ablestack-cloud-p00/systemvm/debian/opt/cloud/bin/setup/common.sh` |

P00-20260526-02 artifact candidates:

| Area | Artifact |
| --- | --- |
| API | `/root/work/ablestack-cloud/api/target/cloud-api-4.22.0.0-SNAPSHOT.jar` |
| Schema | `/root/work/ablestack-cloud/engine/schema/target/cloud-engine-schema-4.22.0.0-SNAPSHOT.jar` |
| Server | `/root/work/ablestack-cloud/server/target/cloud-server-4.22.0.0-SNAPSHOT.jar` |
| Agent | `/root/work/ablestack-cloud/agent/target/cloud-agent-4.22.0.0-SNAPSHOT.jar` |
| KVM plugin | `/root/work/ablestack-cloud/plugins/hypervisors/kvm/target/cloud-plugin-hypervisor-kvm-4.22.0.0-SNAPSHOT.jar` |
| SystemVM control script | `/root/work/ablestack-cloud/systemvm/debian/usr/local/bin/ablestack-storagectl` |
| SystemVM setup script | `/root/work/ablestack-cloud/systemvm/debian/opt/cloud/bin/setup/common.sh` |

P00-20260526-01 executed checks:

| Check | Command | Result |
| --- | --- | --- |
| Fetch origin | `git fetch origin` | Pass |
| Fetch upstream | `git fetch upstream` | Pass |
| Local vs upstream base | `git rev-list --left-right --count ablestack-diplo...upstream/ablestack-diplo` | Pass, `0 0` |
| Local vs origin base | `git rev-list --left-right --count ablestack-diplo...origin/ablestack-diplo` | Pass, `0 0` |
| Work branch ancestry | `git merge-base --is-ancestor ablestack-diplo HEAD` | Pass |
| Diff whitespace check | `git diff --check` | Pass |
| Storage control script syntax | `bash -n systemvm/debian/usr/local/bin/ablestack-storagectl` | Pass |
| API build | `mvn -pl api -DskipTests install` | Pass |
| Server/schema build | `mvn -pl engine/schema,server -am -DskipTests install` | Pass |
| KVM plugin build | `mvn -pl plugins/hypervisors/kvm -am -DskipTests install` | Pass |

P00-20260526-02 executed checks:

| Check | Command | Result |
| --- | --- | --- |
| Fetch origin | `git fetch origin` | Pass |
| Fetch upstream | `git fetch upstream` | Pass |
| Local vs upstream base | `git rev-list --left-right --count ablestack-europa...upstream/ablestack-europa` | Pass, `0 0` |
| Local vs origin base | `git rev-list --left-right --count ablestack-europa...origin/ablestack-europa` | Pass, `0 0` |
| Work branch base | `git switch -c codex/europa-storage-service` from local `ablestack-europa` | Pass |
| Diplo forward-port | `git cherry-pick` Storage Service design, implementation, and validation commits | Pass, with Spring context tail conflicts resolved by preserving Europa beans and adding Storage Service beans |
| Diff whitespace check | `git diff --check` | Pass |
| API build | `mvn -pl api -DskipTests install` | Pass |
| Server/schema build | `mvn -pl engine/schema,server -am -DskipTests install` | Pass |
| KVM plugin build | `mvn -pl plugins/hypervisors/kvm -am -DskipTests install` | Pass |

### P-01 Management Server Deployment Readiness

Goal: prepare the Management Server so Storage Service APIs and managers are
available in the target cloud.

Steps:

1. Back up the current Management Server deployment.
2. Deploy updated API/server/schema artifacts.
3. Apply `schema-Diplo-After.sql` changes if the target database does not
   already contain the Storage Service tables.
4. Restart Management Server.
5. Verify API discovery includes:
   - `createStorageServiceInstance`
   - `enableStorageServiceProtocol`
   - `attachStorageVolumeToFileShare`
   - `resizeStorageFileShare`
   - `prepareStorageServiceNvmeOfVm`
6. Set or confirm `storage.service.feature.enabled=true`.

Expected:

- Management Server starts normally.
- New Storage Service APIs are registered.
- Existing SharedFS APIs still exist.
- No `503` or schema-related startup error occurs.

Result:

| Run ID | Host | Artifact/Commit | DB Migration | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  | Not Run |  |  |
| P01-20260526-01 | `10.10.22.10` | local branch `4.21.0.0-SNAPSHOT`; target runtime `4.22.0.0-SNAPSHOT` | Not applied | Blocked | TCP 8080 reachable; TCP 10022 reachable; TCP 22 closed; unauthenticated API retuned HTTP 401; SSH hostname check succeeded for `10.10.22.10` (`ccvm`) and host nodes `10.10.22.1` (`ablecube22-1`), `10.10.22.2` (`ablecube22-2`), `10.10.22.3` (`ablecube22-3`); `mold` and `mold-usage` are active; current management jars are `cloud-api/core/server-4.22.0.0-SNAPSHOT.jar`; DB password decryption and DB connectivity succeeded without printing secrets; expected Storage Service tables count is `0`; expected Storage Service configuration keys are absent; current `4.22` jars do not contain the new Storage Service API/manager classes; backup created at `/root/codex-backups/storage-service-p01-20260526-170947` with SHA-256 records for the existing API/core/server jars | Deployment, DB migration, Management Server restart, and API registration checks were intentionally not executed because replacing or mixing `4.21` artifacts into the shared `4.22` environment is unsafe. Prepare aligned `4.22` build artifacts or approve a minimal class-level patch plan against the existing `4.22` jars before continuing P-01. |
| P01-20260526-02 | `10.10.22.10` | `codex/europa-storage-service` `a0701701661`, `4.22.0.0-SNAPSHOT` artifacts | Not applied | Ready To Retry | Aligned `4.22` API, schema, server, agent, and KVM plugin artifacts are available in `/root/work/ablestack-cloud`; no additional Management Server files, DB schema, or services were changed during this source alignment pass | Continue P-01 by deploying the aligned `4.22` artifacts, applying the Storage Service schema changes, restarting `mold`, and verifying API registration. |
| P01-20260526-03 | `10.10.22.10` | `codex/europa-storage-service` `51f75cddd87`, `4.22.0.0-SNAPSHOT` artifacts | Applied | Deployed, API Discovery Pending | Deployment bundle SHA-256 verified; backup created at `/root/codex-backups/storage-service-p01-deploy-20260526-204045`; `cloud-api-4.22.0.0-SNAPSHOT.jar` and `cloud-server-4.22.0.0-SNAPSHOT.jar` were replaced with aligned artifacts; `cloudstack-4.22.0.0-SNAPSHOT.jar` was patched with Storage Service server/schema classes and Spring/DB resources; `storage_service_instance`, `storage_service_protocol`, `storage_file_share`, `storage_block_target`, `storage_access_rule`, and `storage_identity_domain` tables exist; `storage.service.feature.enabled=true`; `storage.service.command.timeout=300`; `mold` restarted and is active with PID `2448052`; unauthenticated API endpoint returns HTTP `401` as expected; JAR inspection confirms required command and manager classes are present | Authenticated `listApis` could not be completed because available `api_keypair` credentials retuned HTTP `401` for both `apiKey` and `apikey` signing attempts. Confirm a valid API credential path or session-based API access, then verify discovery for `createStorageServiceInstance`, `enableStorageServiceProtocol`, `attachStorageVolumeToFileShare`, `resizeStorageFileShare`, and `prepareStorageServiceNvmeOfVm`. |
| P01-20260526-04 | `10.10.22.10` | `codex/europa-storage-service` `40bd0a8c754` plus runtime XML/resource fixes | Applied | Pass | `mold` is active after restart with a single `org.apache.cloudstack.ServerDaemon` process; unauthenticated API returns HTTP `401`, proving `/client/api` is loaded instead of HTTP `503`; signed `listCapabilities` with the operator-provided API credential retuned HTTP `200`; signed `listApis name=listStorageServiceInstances` retuned one matching command; signed `listApis` showed 34 Storage Service, SharedFS extension, access, SMB, iSCSI, volume attach, resize, and NVMe-oF preparation APIs including `createStorageServiceInstance`, `enableStorageServiceProtocol`, `attachStorageVolumeToFileShare`, `resizeStorageFileShare`, and `prepareStorageServiceNvmeOfVm` | Runtime recovery required repacking the fat `cloudstack` jar to remove only duplicate `module.properties` entries, not all module resources; XML typos were fixed for `External*` DAO/manager classes, Kubernetes helper registry/property names, and Internal Load Balancer registry/property names. Backups were kept under `/root/codex-backups/storage-service-p01-*`. Do not record or persist API secrets. |

### P-02 KVM Host Agent Deployment Readiness

Goal: prepare every target KVM host that may run the Storage Service SystemVM.

Steps:

1. Identify candidate hosts in the target zone/cluster.
2. Back up the deployed KVM plugin or agent jar on each candidate host.
3. Deploy the code that includes `StorageServiceHostCommand` handling and the
   QGA guest-exec wrapper.
4. Restart the host agent service.
5. Verify the agent reconnects to Management Server.

Expected:

- Host agent is running and connected.
- Storage Service QGA command wrapper is available.
- Existing VM operations on the host are not regressed.

Result:

| Run ID | Host | Agent Service | Artifact/Commit | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  | Not Run |  |  |
| P02-20260526-01 | `10.10.22.1` (`ablecube22-1`), `10.10.22.2` (`ablecube22-2`), `10.10.22.3` (`ablecube22-3`) | `mold-agent.service` | `codex/europa-storage-service` `40bd0a8c754`; class-level patch from `cloud-api-4.22.0.0-SNAPSHOT.jar` and `cloud-plugin-hypervisor-kvm-4.22.0.0-SNAPSHOT.jar` | Pass | Backups created at `/root/codex-backups/storage-service-p02-agent-20260526-223001`, `/root/codex-backups/storage-service-p02-agent-20260526-223002`, and `/root/codex-backups/storage-service-p02-agent-20260526-223003`; `StorageServiceHostCommand.class` and `StorageServiceHostAnswer.class` were added to each host `cloud-api` jar; `LibvirtStorageServiceHostCommandWrapper.class` was added to each host KVM plugin jar; all three `mold-agent.service` units restarted and are `active`; Management Server `listHosts type=Routing` reports `ablecube22-1`, `ablecube22-2`, and `ablecube22-3` as `Up` and `Enabled`; agent logs show startup/ReadyCommand processing after restart | No real QGA Storage Service command was executed because P-03/P-04 have not produced a Storage Service-ready SystemVM yet. Existing startup wanings about missing `/etc/iscsi/initiatoname.iscsi` and `virt-v2v` were observed on some hosts and should be tracked separately if later protocol or migration tests require those host packages. |

### P-03 Storage Service SystemVM Template Build Readiness

Goal: produce or identify a SystemVM template that can run Storage Service
protocol services.

Required packages and files:

| Area | Requirement |
| --- | --- |
| QGA | `qemu-guest-agent` installed and enabled |
| Control script | `/usr/local/bin/ablestack-storagectl` installed and executable |
| NFS | `nfs-kernel-server` or equivalent, `exportfs` |
| SMB | `samba`, `smbd`, `nmbd`, `smbpasswd`, `testparm` |
| AD domain join | `winbind`, `net`, Kerberos/Samba AD join dependencies |
| iSCSI | `targetcli-fb` or equivalent `targetcli` |
| NVMe-oF kernel | `nvme-cli`, kernel `configfs`, `nvmet`, `nvmet-tcp` support |
| Filesystem grow | `xfs_growfs`, `resize2fs`, `findmnt`, `lsblk` |
| Diagnostics | `ss`, `systemctl`, useful logging tools |

Steps:

1. Locate the current SystemVM template build process.
2. Add Storage Service package requirements to the template build profile.
3. Add `ablestack-storagectl` to the template.
4. Verify the local template build host has Packer, QEMU, `shar`, archive tools,
   and `/dev/kvm`.
5. Run a Packer template validation before the full build.
6. Build the template.
7. Register the template in the target zone.
8. Record template name, ID, checksum, and build commit.

Expected:

- Template is registered and usable by the target zone.
- Required packages and scripts are present before functional tests begin.

Result:

| Run ID | Template Name | Template ID | Build Commit | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  | Not Run |  |  |
| P03-PREP-20260526-01 | N/A | N/A | `40bd0a8c754` plus working-tree template prep changes | Ready To Build | Located the build path at `tools/appliance/build.sh` and `tools/appliance/systemvmtemplate/template-base_x86_64-target_x86_64.json`; confirmed `systemvm/debian` is bundled through `tools/appliance/shar_cloud_scripts.sh`; corrected the Debian NFS package requirement from `nfs-server` to `nfs-kernel-server`; ensured `/usr/local/bin/ablestack-storagectl` is chmodded during template configuration and `sharedfsvm` boot; prepared `/etc/ablestack-storage` and the storagectl log on `sharedfsvm` boot; added best-effort QGA enablement on `sharedfsvm` boot; made iSCSI service health/start compatible with `target` and `rtslib-fb-targetctl`; installed missing RockyLinux-9.7 build-host packages `qemu-kvm-core` and `sharutils`; created `/usr/local/bin/qemu-system-x86_64` symlink to `/usr/libexec/qemu-kvm`; verified `packer`, `qemu-system-x86_64`, `qemu-img`, `shar`, `jq`, `mvn`, `bzip2`, `zip`, and `/dev/kvm`; synchronized the Windows working-tree changes into `/root/work/ablestack-cloud`; `packer validate template-base_x86_64-target_x86_64.json` passed in the WSL ext4 clone; `bash -n` passed for changed SystemVM shell scripts; `git diff --check` passed in the WSL ext4 clone and in the Windows checkout with CRLF wanings only | Full SystemVM template build and Cloud template registration have not been executed yet. |
| P03-20260526-01 | `systemvmtemplate-4.22.0.0-x86_64-kvm-202605262304.qcow2.bz2` | N/A | `40bd0a8c754` plus working-tree template prep changes | Build Pass | HashiCorp Packer `v1.15.3` and qemu plugin `github.com/hashicorp/qemu v1.1.4` were installed on the RockyLinux-9.7 WSL build host because `/usr/sbin/packer` was `cracklib-packer`; `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed successfully; artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202605262304.qcow2.bz2`; size is 560 MiB; MD5 is `d679252e766f2fa559ea4a63fe5fed56`; SHA256 is `959c947556930cc62cfed38c0d737343c38068a61a57b0e56284dece0b569ea2`; SHA512 is `4fed982b2da7ce320ca55e4b728c79a78f70682fb39789a1c53c12ba79a0b98c09bd676844c0ec7bdfbbdf0128add255c48fb5a44d17e85c38112aaeb592cfd9`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; temporary Python HTTP serving was started on `10.10.22.10:8000`; host `10.10.22.1` retuned HTTP `200 OK` for the artifact URL after opening TCP 8000 in firewalld | Initial registration verification was incorrectly attempted with an HMAC-SHA1-style signer. The 4.22 API server validates API requests with `HmacSHA256`, so signed API verification must use SHA-256. |
| P03-20260527-01 | `SystemVM Template Storage Service (KVM) 202605262304` | `a612959f-c3f4-47c2-9b2f-8b9cb8b25039` | `40bd0a8c754` plus working-tree template prep changes | Pass | SHA-256 signed `listCapabilities` retuned HTTP `200`; `registerTemplate` retuned HTTP `200`; registered URL was `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202605262304.qcow2.bz2`; template parameters were `format=QCOW2`, `hypervisor=KVM`, `zoneids=-1`, `templatetype=SYSTEM`, `ostypeid=33b473d8-8836-45b9-8389-a3b1185617c3`, `arch=x86_64`, `requireshvm=true`, `details[0].rootDiskController=scsi`, and checksum `{SHA-256}959c947556930cc62cfed38c0d737343c38068a61a57b0e56284dece0b569ea2`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, `physicalsize=592911872`, and `downloadPercent=100` on Secondary Storage | Keep the temporary HTTP server and firewalld runtime opening only as long as needed for this validation artifact, or replace them with a durable template repository path before production rollout. |
| P03-REBUILD-20260528-01 | `SystemVM Template Storage Service (KVM) 202605281348` | `a5fc8aaf-027c-4bdf-b19b-2c107f4ce548` | `40bd0a8c754e` plus working-tree Storage Service UI/API/SystemVM changes | Pass | `bash -n` passed for `ablestack-storagectl`, `sharedfsvm.sh`, `configure_systemvm_services.sh`, and `install_systemvm_packages.sh`; `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed successfully in the RockyLinux-9.7 WSL ext4 clone; artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202605281348.qcow2.bz2`; size is 562 MiB; MD5 is `3d506619329cd8446a9b2a2447a79780`; SHA256 is `1af5138859d1c3354047d9183bea537456b222a2b8856186c0997fb7392ef12d`; SHA512 is `a1b7c0f76928f9624d806c683c89d79739c7f27f200d8bd52c757c7c6b269d81d68a1212fc4c8a7642714622c8c7bd8341838e14e078ad9fb6843e0d23b92476`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; both the WSL client and host `10.10.22.1` retuned HTTP `200 OK`; SHA-256 signed `registerTemplate` retuned HTTP `200`; registered URL was `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202605281348.qcow2.bz2`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, `physicalsize=594406400`, and `downloadPercent=100` on Secondary Storage | This rebuilt template includes the current Storage Service runtime package set, `/usr/local/bin/ablestack-storagectl`, DHCP persistence fixes, and the latest protocol-authentication command support. Fresh Storage Service VM deployment from this template is still required before durable P-04/P-07 acceptance can be closed. |
| P03-REBUILD-20260528-02 | `SystemVM Template Storage Service (KVM) 202605282236` | `3660db8f-ca32-4849-88a6-afd17c9a5775` | `40bd0a8c754e` plus working-tree Storage Service UI/API/SystemVM changes | Pass | `bash -n` passed for `ablestack-storagectl`, `sharedfsvm.sh`, `configure_systemvm_services.sh`, and `install_systemvm_packages.sh`; `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed successfully in the RockyLinux-9.7 WSL ext4 clone; artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202605282236.qcow2.bz2`; size is 564 MiB; MD5 is `8f56cd56d9392eae06d9aab7ca0d0de4`; SHA256 is `d4b335d98b11110648cd777ac5d3a8677fba2acab8dd4fbc179ff4919637fece`; SHA512 is `88b4ad4372f162fd0ff9708301c09e3363bf6bd9850beeffcc35fe69e7283745e3ca1b63c72341abcfb10e2e78bacbb148a0a2fe9cccf6f4d0c5f926f3136ab3`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; the WSL client retuned HTTP `200 OK` from `10.10.22.10:8000`; SHA-256 signed `registerTemplate` retuned HTTP `200`; registered URL was `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202605282236.qcow2.bz2`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, and `downloadPercent=100` on Secondary Storage | This rebuilt template includes the current Storage Service runtime package set, `/usr/local/bin/ablestack-storagectl` with session listing and `session disconnect`, DHCP persistence fixes, and the latest file/block service management support. Fresh Storage Service VM deployment from this template is required for the next UI-led lifecycle validation. |
| P03-REBUILD-20260529-01 | `SystemVM Template Storage Service (KVM) 202605290014` | `48d80424-567a-408e-bd24-9eb8a994f51e` | `40bd0a8c754e` plus working-tree Storage Service root-name exposure changes | Pass | `bash -n` passed for `ablestack-storagectl`; `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed successfully in the RockyLinux-9.7 WSL ext4 clone; artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202605290014.qcow2.bz2`; size is 561 MiB; MD5 is `f74502fcccaf11b897320cbeee5eea13`; SHA256 is `dd862f187324eef1a9d28c4263cc4e71978c2c17f9e78293923c4898c5ed792b`; SHA512 is `f4cc9748940907276eef48b7be487f13e2c975b44b0e91fe852d0170b3bef72feddaf2ea97c3963d6e8bb7274df1aa64a9a1263b77db7d882e4450f12720d0db`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; the WSL client retuned HTTP `200 OK` from `10.10.22.10:8000`; SHA-256 signed `registerTemplate` retuned HTTP `200`; registered URL was `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202605290014.qcow2.bz2`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, and checksum `dd862f187324eef1a9d28c4263cc4e71978c2c17f9e78293923c4898c5ed792b` on Secondary Storage | This rebuilt template includes `/usr/local/bin/ablestack-storagectl` root-level NFS export alias support and NFS ACL `0.0.0.0/0` / `::/0` rendering as the Linux exports wildcard. Fresh Storage Service VM creation should use this template for the next TC-03 retest. |
| P03-REBUILD-20260529-02 | `SystemVM Template Storage Service (KVM) 202605291322` | `18e376e4-b71c-4fb2-9f1d-feaff673f0b1` | `40bd0a8c75` plus working-tree Storage Service monitor-cache and UI table changes | Pass | `bash -n` passed for `ablestack-storagectl`, `ablestack-storage-monitor`, and `configure_systemvm_services.sh`; `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed successfully in the RockyLinux-9.7 WSL ext4 clone; artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202605291322.qcow2.bz2`; size is 558 MiB; MD5 is `f9ea54570775e15de7a1ee9e1af770c1`; SHA256 is `ca35899c6a17472a230b94c50dc6a559591b4602649454c0150a8038d7350ca1`; SHA512 is `e8f92ee5ff2d57bcb1fc1d7e55ff59f6498ed15d89109a1e9ae2c5347ebc4f2aa97a5d62945331019f5a8d639971a5ade1229e3a6b383df9b30f7e36f364b238`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; the WSL client retuned HTTP `200 OK` from `10.10.22.10:8000`; SHA-256 signed `registerTemplate` retuned HTTP `200`; registered URL was `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202605291322.qcow2.bz2`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, `physicalsize=590574080`, and checksum `ca35899c6a17472a230b94c50dc6a559591b4602649454c0150a8038d7350ca1` on Secondary Storage | This rebuilt template includes `/usr/local/bin/ablestack-storage-monitor`, `ablestack-storage-monitor.service`, monitor cache support for health, inventory, sessions, and capacity JSON files, and `ablestack-storagectl` cache-first runtime status reads. Fresh Storage Service VM creation should use this template for monitor-cache validation. |
| P03-REBUILD-20260530-01 | `SystemVM Template Storage Service (KVM) 202605300121` | `ad0378ce-cf2d-4425-b98d-170e3395565f` | `40bd0a8c75` plus working-tree integrated Storage Service API/UI/SystemVM changes | Pass | `bash -n` passed for `ablestack-storagectl`, `ablestack-storage-monitor`, `sharedfsvm.sh`, `install_systemvm_packages.sh`, and `configure_systemvm_services.sh`; Java impact modules built successfully with `-DskipTests`; UI built successfully and was deployed as `js/app.4422b6b9.js`; `mold.service` was restarted and `/client/api` retuned the normal unauthenticated HTTP `401`; `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed successfully in the RockyLinux-9.7 WSL ext4 clone; artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202605300121.qcow2.bz2`; size is 559 MiB; MD5 is `daddcf604b09ab6c3d44954920018582`; SHA256 is `481f6af4bfc16e5a5d4dc8dc3002ad0a9e9dbd0f579744110f0ef23091cbf125`; SHA512 is `ad6724127e56f788604cb2cde6cc06d33895990683daedd811955795012b93e8f2ad7db9a0a209c9c7c8837ca29f08281aecf14aa0d806d02090ab3902a6627e`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; HTTP serving retuned `200` with size `585420420`; SHA-256 signed `registerTemplate` retuned HTTP `200`; registered URL was `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202605300121.qcow2.bz2`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, `physicalsize=592006656`, and checksum `481f6af4bfc16e5a5d4dc8dc3002ad0a9e9dbd0f579744110f0ef23091cbf125` on Secondary Storage | This rebuilt template includes the latest Storage Service API/UI integration, `/usr/local/bin/ablestack-storagectl`, `/usr/local/bin/ablestack-storage-monitor`, `ablestack-storage-monitor.service`, SMB/AD/iSCSI/NVMe package set, monitor-cache support, root-level client-visible share naming, and table-oriented service tab UI support. It was registered without the optional `rootDiskController` template detail because the API signing attempt that included `details[0]` was rejected; track only if a controller-specific deployment issue appears. |
| P03-REBUILD-20260530-02 | `SystemVM Template Storage Service (KVM) 202605302125` | `7ace5e78-9158-4fb2-afb2-5694b1bbba51` | `codex/europa-storage-service` working tree with async create flow, SMB local-account idempotency, and SMB ACL error-state handling | Pass | `bash -n` passed for `ablestack-storagectl`, `ablestack-storage-monitor`, `sharedfsvm.sh`, `install_systemvm_packages.sh`, and `configure_systemvm_services.sh`; UI built successfully and was deployed as `js/app.a2fb4c82.js`; `mvn -pl server -am -DskipTests -DskipITs -Pdeveloper install` completed successfully; the updated `StorageServiceManagerImpl.class` was patched into the management server jar and `mold.service` restarted active; the currently running TC-03B VM `i-2-445-VM` was runtime-patched through QGA with the updated `/usr/local/bin/ablestack-storagectl`; `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed successfully in the RockyLinux-9.7 WSL ext4 clone; artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202605302125.qcow2.bz2`; size is 554 MiB; SHA256 is `09737e5fe8d451aac1107645e5f08c132537e35b9aaa325f10031a2a43be23ff`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; HTTP serving retuned `200` with size `580276298`; SHA-256 signed `registerTemplate` retuned HTTP `200`; registered URL was `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202605302125.qcow2.bz2`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, `physicalsize=586864640`, and checksum `09737e5fe8d451aac1107645e5f08c132537e35b9aaa325f10031a2a43be23ff` on Secondary Storage | This rebuilt template includes the SMB local-account idempotency fix so a same-name Linux group no longer breaks local SMB user creation. The create dialog now closes after the create request is accepted and tracks protocol/share/ACL setup through a persistent async notification. Fresh TC-03B retest should create a new SharedFS from this template and verify that SMB share plus local-user ACL reach `Ready` without UI hang. |
| P03-REBUILD-20260531-01 | `SystemVM Template Storage Service (KVM) 202605310028` | `3a92cc50-540b-4f86-9354-72e72f1ca148` | `codex/europa-storage-service` working tree with TC-03C iSCSI service-tab table fixes and file-backed LUN fallback | Pass | `bash -n` passed for `ablestack-storagectl`, `ablestack-storage-monitor`, `sharedfsvm.sh`, `install_systemvm_packages.sh`, and `configure_systemvm_services.sh`; `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed successfully in the RockyLinux-9.7 WSL ext4 clone; artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202605310028.qcow2.bz2`; size is 561 MiB / `587428473` bytes; MD5 is `56c75ce16f57874c937f35c6754fc27a`; SHA256 is `d87544ee7a2e4f4cc94ce8cb8f5042309920199bdf91940d8cd73489f028440c`; SHA512 is `68e55b5e9495b0c53feb080f35870a89c0ae4db5e5edc8612c86acbd4335bccf4e2ec71bf2a9315332a9653f36817aa76b91db099e97bff0ceea67b29d625d83`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; HTTP serving retuned `200` with size `587428473`; the first registration attempt used the raw SHA256 as `checksum`, which the current download path interpreted as MD5 and failed, so the failed SYSTEM template `0a6a94f3-05df-4cff-bb03-f95e8bec208f` was removed with `deleteTemplate issystem=true forced=true`; re-registration with MD5 checksum retuned HTTP `200`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, `physicalsize=593520128`, and checksum `56c75ce16f57874c937f35c6754fc27a` on Secondary Storage | This rebuilt template includes the latest `/usr/local/bin/ablestack-storagectl` iSCSI apply behavior: when the default SharedFS data disk is already mounted under the compatibility `/export` path, iSCSI uses a managed file-backed LUN under `/export/.ablestack-storage/iscsi/<target-uuid>.img` instead of trying to expose the mounted block device directly. It also carries the monitor-cache service and latest Storage Service protocol package set. Fresh TC-03C retest should create a new SharedFS from this template and verify iSCSI target, LUN, ACL, monitor cache, and UI table state without live patching. |
| P03-REBUILD-20260531-02 | `SystemVM Template Storage Service (KVM) 202605311453` | `4de1e925-5ac7-4eb3-bf94-cc38f0661eed` | `codex/europa-storage-service` working tree with TC-03D NVMe-oF idempotent reconcile and stale Host ACL cleanup fixes | Pass | `bash -n` passed for `ablestack-storagectl`; Java impact modules built successfully with `mvn -pl server -am -DskipTests -Dcheckstyle.skip=true -Drat.skip=true install`; UI built successfully and was deployed as `js/app.15b30c22.js`; `/client/api` retuned the normal unauthenticated HTTP `401` after `mold.service` restart; the current TC-03D VM `i-2-449-VM` was runtime-patched through QGA and local WSL `nvme discover` plus `nvme connect` succeeded against `10.10.254.177:4420`; `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed successfully in the RockyLinux-9.7 WSL ext4 clone; artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202605311453.qcow2.bz2`; size is 561 MiB / `588219856` bytes; MD5 is `748a9bd9a785d0d67a98b34c66a6626e`; SHA256 is `d2d01370c8898f888112dab38e5b3a9dbd91bd0173b31528ca906d855fefe687`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; HTTP serving retuned `200`; SHA-256 signed `registerTemplate` retuned HTTP `200`; registered URL was `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202605311453.qcow2.bz2`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, and `downloadPercent=100` on Secondary Storage | This rebuilt template includes the NVMe-oF reconcile fix: configfs port attributes are not rewritten after subsystem links are active, Host ACL symlinks are applied idempotently, desired-state host ACL removals unlink stale configfs entries, and namespaces resolve mounted backing volumes to managed file-backed loop devices under `.ablestack-storage/nvmeof`. Fresh TC-03D retest should create a new SharedFS from this template and verify UI-created Host ACL plus local WSL NVMe-oF connection without live patching. |
| P03-REBUILD-20260531-03 | `SystemVM Template Storage Service (KVM) 202605311826` | `34e52078-9995-4bc2-8bd5-e2fe88c957c9` | `codex/europa-storage-service` working tree with TC-03D-02 authenticated NVMe-oF enforcement and runtime redaction fixes | Pass | Java impact modules built successfully with `mvn -pl server -am -DskipTests -Dcheckstyle.skip=true -Drat.skip=true install`; UI built successfully and is deployed as `js/app.8809e117.js`; management runtime JARs were patched with `StorageServiceManagerImpl.class` only and backed up under `/root/codex-backups/storage-nvme-auth-clean-20260531182546`; `/client/api` retuned the normal unauthenticated HTTP `401` after `mold.service` restart; `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed successfully in the RockyLinux-9.7 WSL ext4 clone; artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202605311826.qcow2.bz2`; size is 560 MiB / `587031104` bytes; MD5 is `d38986bde3b60795d96d831b293fb043`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; HTTP serving retuned `200`; SHA-256 signed `registerTemplate` retuned HTTP `200`; registered URL was `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202605311826.qcow2.bz2`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, and checksum `d38986bde3b60795d96d831b293fb043`; signed `listStorageServiceInventory` smoke check against the existing TC-03D-02 service retuned no password, secret, or DH-HMAC-CHAP key patterns | This rebuilt template includes mandatory DH-HMAC-CHAP enforcement when requested, redaction before SystemVM desired-state and monitor-cache writes, first-seen/last-seen NVMe-oF session tracking, and single-subsystem NQN enrichment. Fresh TC-03D-02 retest should create a new SharedFS from this template and verify that authenticated Host ACLs either apply with kernel-supported configfs attributes or fail explicitly without exposing secrets. |
| P03-REBUILD-20260531-04 | `systemvm-template-storage-service-kvm-202605312252` | `55bd293f-5ca5-4303-988b-5c40ff3d573d` | `codex/europa-storage-service` working tree with verified NVMe-oF session connected-time and subsystem attribution fix | Pass | `bash -n` passed for `ablestack-storagectl`; the current TC-03D-02 VM `i-2-452-VM` was runtime-patched through host QGA with the updated `/usr/local/bin/ablestack-storagectl`; local WSL `nvme connect` created `/dev/nvme0n1`; direct SystemVM `ABLESTACK_STORAGECTL_CACHE=0 ablestack-storagectl sessions` retuned 17 `NVME_OF` sessions with `connectedAt=2026-05-31T13:47:27Z`, `resourceId=0caeb09a-b89e-42a5-973c-f7ae7217cb61`, and `resourceName=nqn.2026-05.local.storage:tc03d02`; the browser UI session table displayed both connection time and subsystem NQN. `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed in the RockyLinux-9.7 WSL ext4 clone; artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202605312252.qcow2.bz2`; size is 564 MiB / `590784680` bytes; MD5 is `72c774615282b3eb40a3d6fd4e9913ac`; SHA256 is `7ad127920fa1808832cbd703f92ecafe41beaec7810fa00b8b5e5843af57aa48`; SHA512 is `6f03ae6f945024eee003c2578080f2303e62fb53fd95d9267fa8f9cbb6c2605e6eb6cac67f4c9c9462a8ea79841d34e563f09ef9553973e61079169efbde8233`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; HTTP serving retuned `200` with size `590784680`; signed `registerTemplate` retuned HTTP `200`; registered URL was `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202605312252.qcow2.bz2`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, `physicalsize=596631040`, and checksum `72c774615282b3eb40a3d6fd4e9913ac` on Secondary Storage | This rebuilt template includes NVMe-oF session enrichment from active subsystem desired-state, `resourceId`/`resourceName`/`subsystemNqn` session fields, and stable first-seen/last-seen cache retention. The display name was registered without parentheses because the current API signature path rejected signed requests containing those characters in this environment. |
| P03-REBUILD-20260601-01 | `SystemVM Template Storage Service (KVM) 202606010104` | `ed624184-80f5-4b87-8fc3-a655fc1450a3` | `codex/europa-storage-service` working tree with NVMe-oF DH-HMAC-CHAP kernel capability gating | Pass | `bash -n` passed for `ablestack-storagectl`; UI built successfully with `NODE_OPTIONS=--openssl-legacy-provider npm run build` and was deployed as `js/app.49017f71.js`; browser verification on `10.10.22.10` confirmed the NVMe-oF tab shows `DH-HMAC-CHAP 지원: 미지원` and a waning with reason `missing nvmet host dhchap_key/dhchap_ctrl_key`, while the Host ACL modal disables both host and controller CHAP switches. The current TC-03D service VM `i-2-453-VM` was runtime-patched through QGA and signed `listStorageServiceHealth` retuned `capabilities.nvmeof.dhChapSupported=false` and `dhChapCtrlSupported=false`. `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed in the RockyLinux-9.7 WSL ext4 clone; normalized artifact path is `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202606010104.qcow2.bz2`; size is 560 MiB / `586404124` bytes; MD5 is `c8db1b5b6db5c664e88c6c3d30556553`; SHA256 is `4801c87ae0652d64e49a1c1a6c3150c3b9cb9a3c8c2e9216e7d2778b2cac868b`; the artifact was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`; HTTP serving retuned `200`; SHA-256 signed `registerTemplate` retuned HTTP `200`; registered URL was `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202606010104.qcow2.bz2`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, `physicalsize=592915968`, and checksum `c8db1b5b6db5c664e88c6c3d30556553` on Secondary Storage | This rebuilt template includes runtime capability reporting for NVMe-oF kernel target/configfs host support and DH-HMAC-CHAP attributes. Current Debian kernel `6.1.0-49-amd64` does not expose `dhchap_key` or `dhchap_ctrl_key`, so the UI must disable DH-HMAC-CHAP controls and clearly show that CHAP authentication is unsupported while Host NQN ACL remains available. |

### P-04 Storage Service SystemVM Package Verification

Goal: verify the running Storage Service SystemVM actually contains and runs the
required components.

Steps:

1. Deploy or start a VM from the Storage Service-ready SystemVM template.
2. Verify QGA is active from the host.
3. Inside the VM, verify:
   - `/usr/local/bin/ablestack-storagectl`
   - `qemu-guest-agent`
   - `exportfs`
   - `smbd`
   - `net`
   - `targetcli`
   - `nvme`
   - `xfs_growfs`
   - `resize2fs`
4. Run `ablestack-storagectl health`.
5. Run `ablestack-storagectl inventory`.

Expected:

- QGA guest-exec works.
- `health` returns structured JSON.
- Missing packages are documented before protocol tests begin.

Result:

| Run ID | VM | Host | QGA | Script | Packages | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  | Not Run |  |  |
| P04-20260527-01 | `30b6a5f3-fa3a-45cd-99f0-af9f3b9ae5d5` / `i-2-428-VM` / `sharedfs-p04-storage-service-20260527-0110-19e650a96cc` | `ablecube22-1` / `10.10.22.1` | Pass | Pass | Pass | Pass | SharedFS VM was deployed from template `a612959f-c3f4-47c2-9b2f-8b9cb8b25039` using SharedFS ID `d85a25e9-289e-44f5-9426-301e1e150580`, service offering `P04 Storage Service 2C2G HA` (`f67ba1f5-f0ab-4559-a96f-177c96666e1a`), disk offering `Custom1`, network `L2-Network-ConfigDrive`, VM IP `10.10.254.180`, and data volume `785ba5bd-dc4a-4230-a2de-f4f55dc3ca36`; VM state is `Running`; Storage Service instance `4c76e04e-8a93-434d-913f-3293b568b977` was bound to the VM and entered `Running`; `listStorageServiceHealth` retuned success with `status=ok`, QGA active, and commands `exportfs`, `net`, `nvme`, `smbd`, and `targetcli` present; `listStorageServiceInventory` retuned success with NFS/iSCSI/NVMe-oF inventories empty and default Samba shares visible; direct host QGA verification confirmed `/usr/local/bin/ablestack-storagectl`, `qemu-ga`, `exportfs`, `smbd`, `nmbd`, `smbpasswd`, `testparm`, `net`, `winbindd`, `targetcli`, `nvme`, `xfs_growfs`, `resize2fs`, `findmnt`, `lsblk`, `systemctl`, and `ss` are present; `qemu-guest-agent` is active | Initial SharedFS creation with custom service offering `NoLimit-HA-WB` failed before VM deployment with HTTP `530` / null CPU NPE, so SharedFS prerequisite validation must reject custom offerings without resolved CPU/RAM values instead of throwing NPE. A second attempt on `L2-Network` failed with async error `431` because the network lacks UserData service; Storage Service/SharedFS deployment should prevalidate UserData support or document that ConfigDrive/UserData-capable networks are required. Failed SharedFS record `910939a4-33e5-46ed-b533-82698a6d5615` remains in `Error` state and should be cleaned during rollback. Protocol services are installed but inactive until desired state is applied, which is acceptable for P-04. |

P04-FIX-20260527-01 improvement handling:

| Improvement | Code Handling | Runtime Deployment | Retest Result |
| --- | --- | --- | --- |
| Custom compute offering with null CPU/RAM caused HTTP `530` NPE before VM deployment | `StorageVmSharedFSLifeCycle.checkPrerequisites()` now rejects missing fixed CPU/RAM with operator-readable `InvalidParameterValueException` messages before deployment | Patched `StorageVmSharedFSLifeCycle.class` into `/usr/share/cloudstack-management/lib/cloudstack-4.22.0.0-SNAPSHOT.jar`; backup kept at `/root/codex-backups/storage-service-p04-improvements-20260527-012857` | Pass. `createSharedFileSystem` with `NoLimit-HA-WB` now returns HTTP `431` and `Service offering must have a fixed CPU count for SharedFS VM. Custom CPU offerings are not supported.` No new SharedFS record was created. |
| Network without UserData caused async deployment failure and left an Error SharedFS record | `SharedFSServiceImpl.allocSharedFS()` now checks `Network.Service.UserData` before persisting the SharedFS record | Patched `SharedFSServiceImpl.class` into `/usr/share/cloudstack-management/lib/cloud-server-4.22.0.0-SNAPSHOT.jar` and `/usr/share/cloudstack-management/lib/cloudstack-4.22.0.0-SNAPSHOT.jar`; `mold` restarted successfully | Pass. `createSharedFileSystem` on `L2-Network` now returns HTTP `431` and `Network ... does not support UserData service...`. No new SharedFS record was created. |
| Ensure P-04 runtime path still works after patch | Re-ran runtime health through API/QGA path | Same Management Server runtime after class patch | Pass. `listStorageServiceHealth instanceid=4c76e04e-8a93-434d-913f-3293b568b977` retuned HTTP `200`, `success=true`, and `status=ok`. |

### P-05 Cloud Environment Readiness

Goal: verify the target cloud can host and manage Storage Service resources.

Steps:

1. Confirm target zone, pod, cluster, and hosts are healthy.
2. Confirm primary storage has enough capacity.
3. Confirm network connectivity from client VMs to the Storage Service SystemVM
   for:
   - NFS TCP/UDP endpoint port as configured for the selected protocol mode
   - SMB TCP 445
   - iSCSI TCP 3260
   - NVMe-oF TCP 4420
4. Confirm suitable service offering exists for the Storage Service SystemVM.
5. Confirm test account/domain/project state is active.

Expected:

- No infrastructure blocker exists before functional tests.
- Network/firewall policy allows protocol-level tests.

Result:

| Run ID | Zone | Cluster | Storage | Network | Service Offering | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  | Not Run |  |  |
| P05-20260527-01 | `Zone-22` (`d5551005-3372-43e5-8a2b-5742057bbabd`), allocation state `Enabled`, advanced networking, HA resource detail present | `Cluster`, KVM, allocation state `Enabled`, managed state `Managed`; `ablecube22-1`, `ablecube22-2`, and `ablecube22-3` are all `Up` and `Enabled` | Primary RBD pool `91cae554-3fce-3f93-89d1-cefaf9bf8122` is `Up`, scope `ZONE`, overprovision factor `2.0`; raw storage capacity is 56.64% used and allocated storage capacity is 74.62% used; secondary storage is 31.08% used; local pools on all three hosts are `Up` | `L2-Network-ConfigDrive` (`2e352b75-962d-485c-b6ca-0674bf802b8c`) is `Setup`, deployable, and provides UserData via ConfigDrive; existing Storage Service VM `30b6a5f3-fa3a-45cd-99f0-af9f3b9ae5d5` has IP `10.10.254.180`; `ablecube22-1` ping to `10.10.254.180` succeeded with 3/3 replies and ARP `REACHABLE`; TCP 2049/445/3260/4420 are `closed_or_filtered` because protocol services remain inactive until desired state is applied | `P04 Storage Service 2C2G HA` (`f67ba1f5-f0ab-4559-a96f-177c96666e1a`) is active, fixed 2 vCPU / 2048 MiB, HA enabled, shared storage, cache mode `writeback` | Pass With Wanings | Signed API checks retuned current zone, pod, cluster, host, pool, capacity, network, service offering, template, VM, account, and domain state; template `a612959f-c3f4-47c2-9b2f-8b9cb8b25039` is `Download Complete` / `isready=true`; Storage Service VM is `Running` on `ablecube22-1`; account `admin` is enabled and domain `ROOT` is active | Pod private IP capacity is exhausted (`2/2`, 100%), but this is not a blocker for the current SharedFS/Storage Service VM because that VM uses `L2-Network-ConfigDrive` guest addressing (`10.10.254.180`) rather than the Pod private IP pool. Treat it only as a general infrastructure capacity observation for future built-in system VM or infrastructure deployments. Protocol port-open checks must be repeated after protocol desired state is enabled and P-07 client VMs are prepared. Failed P-04 SharedFS record `910939a4-33e5-46ed-b533-82698a6d5615` still requires rollback cleanup. |

### P-06 Test Volume Readiness

Goal: prepare volumes that can safely be attached to the Storage Service
SystemVM.

Steps:

1. Create one unused blank data volume for basic attach testing.
2. Create one XFS volume with known test files.
3. Create one ext4 volume with known test files.
4. Create one volume suitable for resize testing.
5. Confirm none of the volumes are attached to another VM before import.
6. Record volume IDs, filesystem type, initial size, and test-file checksum.

Expected:

- Volumes are safe to attach.
- Existing-volume tests can prove non-destructive import.
- Resize tests have known before/after size.

Result:

| Run ID | Volume | Filesystem | Initial Size | Attached To | Test Data | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  | Not Run |  |
| P06-20260527-01 | `p06-blank-attach-20260527-0145` / `00fcf76e-828b-4938-b2c4-7806a2dd99fa` | none | 2 GiB | Detached | N/A | Ready | Created with disk offering `Custom1` on `Primary Storage Glue RBD`; ABLESTACK state is `Ready`, path is `00fcf76e-828b-4938-b2c4-7806a2dd99fa`, and no VM is attached; RBD direct inspection confirmed no filesystem signature; `rbd showmapped` retuned no mapping for the P-06 volumes after preparation. |
| P06-20260527-01 | `p06-xfs-existing-20260527-0145` / `461e45ee-d4f3-48c9-befa-05861b10b3fa` | XFS, label `P06XFS`, UUID `20c22a38-b28c-4307-868e-804c21932e9c` | 2 GiB | Detached | `p06-xfs.txt`, SHA256 `419f68988ad4e1cd62bf847840ca5886a276488971b64e717e9770288499249f` | Ready | Created with disk offering `Custom1` on `Primary Storage Glue RBD`; ABLESTACK state is `Ready`, path is `461e45ee-d4f3-48c9-befa-05861b10b3fa`, and no VM is attached; filesystem and test data were prepared by mapping only this RBD image on `ablecube22-3`, then unmounting and unmapping it. |
| P06-20260527-01 | `p06-ext4-existing-20260527-0145` / `633a29f0-8b1d-482f-bc55-0b7c3f26cd31` | ext4, label `P06EXT4`, UUID `03a6e398-0c29-46ae-be5b-def23ed1f6f8` | 2 GiB | Detached | `p06-ext4.txt`, SHA256 `b54b2f1a6eb3764f45667b7a6ed3c55682a574bbe6c8071b1812f168cbb66bde` | Ready | Created with disk offering `Custom1` on `Primary Storage Glue RBD`; ABLESTACK state is `Ready`, path is `633a29f0-8b1d-482f-bc55-0b7c3f26cd31`, and no VM is attached; filesystem and test data were prepared by mapping only this RBD image on `ablecube22-3`, then unmounting and unmapping it. |
| P06-20260527-01 | `p06-resize-20260527-0145` / `bba43003-fba2-410b-9fc5-62539e570ab0` | ext4, label `P06RSZ`, UUID `ba7bc1f4-a79f-4a88-9358-0ab0375c05fa` | 2 GiB | Detached | `p06-resize.txt`, SHA256 `2c84e79e220d1a2e90dc031a2f1998b590ff5a980d81f73a07a3c7f2c1fb2990` | Ready | Created with disk offering `Custom1` on `Primary Storage Glue RBD`; ABLESTACK state is `Ready`, path is `bba43003-fba2-410b-9fc5-62539e570ab0`, and no VM is attached; this volume is reserved for later capacity expansion from 2 GiB to a larger size. |

P06-20260527-01 defect and workaround:

| Item | Result | Impact | Follow-up |
| --- | --- | --- | --- |
| Standard `attachVolume` to the Storage Service VM | Failed with HTTP async error `431`: `Can't attach a volume to a Shared FileSystem Instance` | Expected policy behavior for the public attach API; Storage Service volume import must use the Storage Service API path instead of generic VM attach | Keep this behavior documented and ensure Storage Service APIs surface operator-readable errors when a generic attach path is attempted |
| Standard `attachVolume` to a temporary User VM | Failed after RBD `CreateObject` succeeded; KVM agent logged `java.lang.StackOverflowError` in `KVMStorageProcessor.attachOrDetachDisk()`, and Management Server surfaced `Answer cannot be cast to AttachAnswer` | This blocks normal hot attach validation and can also block Storage Service attach/import flows that rely on KVM volume hotplug | Fix or patch the KVM RBD attach path before running `TC-03`, `TC-04`, `TC-05`, and `TC-06` as functional pass tests |
| P-06 volume preparation workaround | New P-06 volumes were materialized through the failed attach create-object step, then prepared by direct RBD map/mkfs/write/sync/umount/unmap on `ablecube22-3` | Produces safe detached test volumes without touching production volumes or leaving RBD mappings | Treat these volumes as prepared test inputs, but do not consider the attach/import feature validated until the hot attach defect is fixed |
| Temporary prep VM cleanup | `p06-volume-prep-helper-20260527-0156` / `ae668915-0853-43e6-9fce-367b37ca4ab4` was destroyed with `expunge=true` after use | No helper VM remains running after P-06 | P-07 must prepare fresh protocol client VMs |

P06-FIX-20260527-01 hot attach defect handling:

| Item | Fix/Verification | Result |
| --- | --- | --- |
| Root cause | The KVM plugin class deployed on the 22.x hosts had a recursive bytecode path in the short `KVMStorageProcessor.attachOrDetachDisk(...)` overload: it invoked the same overload instead of the overload with the `waitDetachDevice` parameter. The current `codex/europa-storage-service` source builds the correct `ZZJ...` method descriptor. | Confirmed by `javap` against the deployed host jar before patch and the rebuilt KVM plugin jar after build. |
| Build | Ran `mvn -pl plugins/hypervisors/kvm -am -DskipTests package` in the WSL ext4 worktree. | Pass. Build completed successfully for `cloud-plugin-hypervisor-kvm-4.22.0.0-SNAPSHOT.jar`. |
| Regression test | Added `KVMStorageProcessorTest.validateAttachOrDetachDiskShortOverloadDelegatesToWaitDetachOverload` to prove the short overload delegates to the `waitDetachDevice` overload with `0L` instead of recursively invoking itself. | Pass. Targeted test passed, and the full `KVMStorageProcessorTest` suite passed with 38 tests, 0 failures, 0 errors. |
| Runtime patch | Replaced only `com/cloud/hypervisor/kvm/storage/KVMStorageProcessor.class` in `/usr/share/cloudstack-agent/lib/cloud-plugin-hypervisor-kvm-4.22.0.0-SNAPSHOT.jar` on `10.10.22.1`, `10.10.22.2`, and `10.10.22.3`; backup kept under `/root/codex-backups/storage-service-p06-kvm-attach-fix-20260527-0205`; patched class SHA256 is `1dab67f4933a81ab58f83e9d4cd204cd0014dea501d70404516fb637fe041fa3`. | Pass. `mold-agent.service` restarted and was `active` on all three hosts. |
| Attach retest | Deployed temporary VM `p06-attach-fix-retest-20260527-0212` / `d13c605b-5dc5-4f29-8633-030a696a5146` on `ablecube22-3`; attached volume `00fcf76e-828b-4938-b2c4-7806a2dd99fa`; verified `virsh domblklist` showed `sdb` source `/dev/rbd/rbd/00fcf76e-828b-4938-b2c4-7806a2dd99fa` and `virsh domblkinfo sdb` showed capacity `2147483648`; detached the volume; destroyed the temporary VM with `expunge=true`. | Pass. The volume retuned to ABLESTACK `Ready` with no attached VM. No `StackOverflowError` occurred in the retest path. |
| Post-fix health | `listHosts type=Routing` reports `ablecube22-1`, `ablecube22-2`, and `ablecube22-3` as `Up` and `Enabled`; `listStorageServiceHealth` for instance `4c76e04e-8a93-434d-913f-3293b568b977` retuned `success=true`, `status=ok`, and QGA active. | Pass. P-06 can proceed as completed and later attach/import functional tests may run with this runtime patch in place. |

### P-07 Client VM Readiness

Goal: prepare protocol clients for end-to-end access tests.

Steps:

1. Prepare an NFS client VM with mount utilities.
2. Prepare an SMB client VM with Linux CIFS tools or Windows SMB access.
3. Prepare an iSCSI client VM with `iscsiadm`.
4. Prepare an NVMe-oF client VM with `nvme-cli`.
5. Verify each client can route to the Storage Service network.
6. Record client VM IDs and IPs.

Expected:

- Clients can reach the Storage Service SystemVM IP.
- Required client tools are installed.

Result:

| Run ID | Client | Protocol | Tools | Network Reachability | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P07-20260527-01 | `p07-protocol-client-20260527-0909` / `d148a6ec-9f3d-4aa4-bf72-6480e12da025` / `i-2-432-VM`, IP `10.10.254.195`, host `ablecube22-3` | NFS | `nfs-utils`; `mount.nfs=/usr/sbin/mount.nfs` | Pass after renewing Storage Service VM DHCP lease; ping `10.10.254.180` retuned 3/3 replies from the client | Pass With Wanings | Client was deployed from `Rocky Linux 9.4 Minimal` (`baaced7d-aee8-4117-8371-edc8a78a6971`) on `L2-Network-ConfigDrive` using service offering `P04 Storage Service 2C2G HA`; root SSH access was verified; protocol TCP 2049 remained `closed_or_filtered` because NFS desired state has not been enabled yet |
| P07-20260527-01 | `p07-protocol-client-20260527-0909` / `d148a6ec-9f3d-4aa4-bf72-6480e12da025` / `i-2-432-VM`, IP `10.10.254.195`, host `ablecube22-3` | SMB | `cifs-utils`; `mount.cifs=/usr/sbin/mount.cifs` | Pass after renewing Storage Service VM DHCP lease; ping `10.10.254.180` retuned 3/3 replies from the client | Pass With Wanings | SMB client tooling is installed; protocol TCP 445 remained `closed_or_filtered` because SMB desired state has not been enabled yet |
| P07-20260527-01 | `p07-protocol-client-20260527-0909` / `d148a6ec-9f3d-4aa4-bf72-6480e12da025` / `i-2-432-VM`, IP `10.10.254.195`, host `ablecube22-3` | iSCSI | `iscsi-initiator-utils`; `iscsiadm=/usr/sbin/iscsiadm` | Pass after renewing Storage Service VM DHCP lease; ping `10.10.254.180` retuned 3/3 replies from the client | Pass With Wanings | iSCSI client tooling is installed; protocol TCP 3260 remained `closed_or_filtered` because iSCSI desired state has not been enabled yet |
| P07-20260527-01 | `p07-protocol-client-20260527-0909` / `d148a6ec-9f3d-4aa4-bf72-6480e12da025` / `i-2-432-VM`, IP `10.10.254.195`, host `ablecube22-3` | NVME_OF | `nvme-cli`; `nvme=/usr/sbin/nvme` | Pass after renewing Storage Service VM DHCP lease; ping `10.10.254.180` retuned 3/3 replies from the client | Pass With Wanings | NVMe-oF client tooling is installed; protocol TCP 4420 remained `closed_or_filtered` because NVMe-oF desired state has not been enabled yet |

P-07 defect and improvement notes:

- Storage Service VM `sharedfs-p04-storage-service-20260527-0110-19e650a96cc`
  still appeared `Running` in Cloud and Storage Service health checks, but its
  guest `eth0` had no IP address before this test. QGA showed only the MAC
  `02:01:00:f4:00:0a`; host and client ping to `10.10.254.180` failed.
- Running `cloud-dhclient@eth0.service` inside the Storage Service VM restored
  `10.10.254.180/16`, after which the P-07 client reached the Storage Service
  VM with 3/3 ICMP replies.
- The current SystemVM user-data creates `cloud-dhclient@.service` as a
  one-shot DHCP start helper. Long-running protocol tests need a persistent DHCP
  renewal mechanism or equivalent static network rendering so Storage Service
  VM reachability does not depend on manual lease renewal.

P-07 DHCP persistence remediation plan:

- The fix belongs in the Storage Service SystemVM template and SharedFS
  initialization data, not in client VMs or protocol-specific APIs.
- `systemvm/debian/opt/cloud/bin/setup/sharedfsvm.sh` must start DHCP with an
  explicit PID file and lease file so the first boot address acquisition is
  trackable.
- `plugins/storage/sharedfs/storagevm/src/main/resources/conf/fsvm-init.yml`
  must render `cloud-dhclient@.service` as a persistent `Type=simple` service
  that runs `dhclient -d` in the foreground with `Restart=always`, using
  AppArmor-allowed per-interface PID and lease files such as
  `/run/dhclient.eth0.pid` and `/var/lib/dhcp/dhclient.eth0.leases`.
  `fsvm-setup` must `enable --now` the service for existing `eth*` interfaces
  so lease renewal remains active after cloud-init exits.
- The current P04 Storage Service VM may be patched at runtime only to validate
  the service behavior. The durable acceptance criterion is a rebuilt SystemVM
  template and a newly deployed Storage Service VM that keeps `eth0` addressed
  without manual DHCP renewal.

P07-FIX-20260527-01 implementation and validation:

| Item | Result | Evidence |
| --- | --- | --- |
| Template source update | Pass | `systemvm/debian/opt/cloud/bin/setup/sharedfsvm.sh` now starts initial DHCP with AppArmor-allowed `/run/dhclient.eth0.pid` and `/var/lib/dhcp/dhclient.eth0.leases`; `plugins/storage/sharedfs/storagevm/src/main/resources/conf/fsvm-init.yml` now renders `cloud-dhclient@.service` as a persistent `Type=simple` service running `dhclient -d` with `Restart=always`; `fsvm-setup` enables and starts the service for existing `eth*` interfaces. |
| Static validation | Pass | `bash -n systemvm/debian/opt/cloud/bin/setup/sharedfsvm.sh` passed; Python YAML parsing of `fsvm-init.yml` retuned a document with 6 `write_files` entries. |
| Runtime patch on current P04 VM | Pass | The current Storage Service VM was patched through QGA with the new `cloud-dhclient@.service`; `systemctl is-active cloud-dhclient@eth0.service` retuned `active`; `systemctl show` reported `ActiveState=active`, `SubState=running`, and `MainPID=11578`; `pgrep` showed `/usr/sbin/dhclient -4 -d -v -pf /run/dhclient.eth0.pid -lf /var/lib/dhcp/dhclient.eth0.leases eth0`; `eth0` retained `10.10.254.180/16`. |
| Restart resilience | Pass | After terminating the dhclient MainPID, systemd restarted the service with a new MainPID `11632`; `NRestarts=1`, `ActiveState=active`, and `SubState=running`; `eth0` retained `10.10.254.180/16`. |
| Client reachability after fix | Pass | P-07 client `10.10.254.195` pinged Storage Service VM `10.10.254.180` with 3/3 replies after the DHCP service restart test. |
| Durable acceptance | Partially Complete | Rebuilt and registered Storage Service SystemVM template `SystemVM Template Storage Service (KVM) 202605282236` / `3660db8f-ca32-4849-88a6-afd17c9a5775`; next deploy a fresh Storage Service VM from this template and confirm the persistent DHCP service and Storage Service session-management commands are present without runtime patching. |

### P-08 Observability And Rollback Readiness

Goal: make every state-changing test recoverable.

Steps:

1. Confirm log locations:
   - Management Server log
   - Host agent log
   - SystemVM `/var/log/ablestack-storagectl.log`
   - protocol service logs
2. Prepare rollback plan for:
   - Management Server artifact restore
   - host agent artifact restore
   - SystemVM template rollback
   - test volume detach/recovery
3. Confirm no production workload volumes are used.

Expected:

- Failures can be diagnosed and rolled back.
- Test volumes and client VMs are clearly identified.

Result:

| Run ID | Logs Verified | Rollback Verified | Test Resources Isolated | Status | Evidence |
| --- | --- | --- | --- | --- | --- |
| P08-20260527-01 | Pass | Pass | Pass With Wanings | Pass With Wanings | Management Server `mold` is active on `10.10.22.10` and `/var/log/cloudstack/management/management-server.log` exists; KVM `mold-agent.service` is active on `10.10.22.1`, `10.10.22.2`, and `10.10.22.3`, with `/var/log/cloudstack/agent/agent.log` present on each host; Storage Service VM `i-2-428-VM` exposes `/var/log/ablestack-storagectl.log`, `/var/log/userdata.log`, `/var/log/cloud-init.log`, Samba logs, and protocol journals through QGA; `ablestack-storagectl.log` shows health and inventory commands; protocol service journals are accessible even though NFS, SMB, iSCSI, and NVMe-oF desired state is not enabled yet. Management rollback backups are present under `/root/codex-backups/storage-service-p01-deploy-20260526-204045`, `/root/codex-backups/storage-service-p01-fatjar-repack-20260526-220748`, and `/root/codex-backups/storage-service-p04-improvements-20260527-012857`; host rollback backups are present under `/root/codex-backups/storage-service-p02-agent-20260526-22300{1,2,3}` and `/root/codex-backups/storage-service-p06-kvm-attach-fix-20260527-0205`; these contain the previous `cloud-api`, `cloud-server`, `cloudstack`, and KVM plugin jars plus checksum records where created. P-06 volumes `00fcf76e-828b-4938-b2c4-7806a2dd99fa`, `461e45ee-d4f3-48c9-befa-05861b10b3fa`, `633a29f0-8b1d-482f-bc55-0b7c3f26cd31`, and `bba43003-fba2-410b-9fc5-62539e570ab0` are all `Ready` and detached; P-04 Storage Service VM `30b6a5f3-fa3a-45cd-99f0-af9f3b9ae5d5` is `Running` on `ablecube22-1` with IP `10.10.254.180`; P-07 client VM `d148a6ec-9f3d-4aa4-bf72-6480e12da025` is `Running` on `ablecube22-3` with IP `10.10.254.195`; failed SharedFS ID `910939a4-33e5-46ed-b533-82698a6d5615` no longer appears in `listSharedFileSystems`. Waning: P-07 DHCP persistence is applied to source and runtime-patched into the current P04 VM, but it is not yet validated from a newly rebuilt SystemVM template. |

P-08 rollback map:

| Area | Restore Point | Recovery Action |
| --- | --- | --- |
| Management Server deployment | `/root/codex-backups/storage-service-p01-deploy-20260526-204045` on `10.10.22.10` | Restore the backed-up `cloud-api-4.22.0.0-SNAPSHOT.jar`, `cloud-server-4.22.0.0-SNAPSHOT.jar`, and `cloudstack-4.22.0.0-SNAPSHOT.jar`, then restart `mold` and verify `/client/api` returns HTTP `401` unauthenticated instead of HTTP `503`. |
| Management Server fat jar repack | `/root/codex-backups/storage-service-p01-fatjar-repack-20260526-220748` on `10.10.22.10` | Restore `cloudstack-current-before-repack.jar` if the fat-jar resource layout must be retuned to the pre-repack state. |
| P-04 prerequisite fixes | `/root/codex-backups/storage-service-p04-improvements-20260527-012857` on `10.10.22.10` | Restore the backed-up `cloud-server` and `cloudstack` jars, restart `mold`, and recheck `listStorageServiceHealth`. |
| KVM Storage Service command path | `/root/codex-backups/storage-service-p02-agent-20260526-22300{1,2,3}` on the three KVM hosts | Restore backed-up `cloud-api` and KVM plugin jars on each host, restart `mold-agent.service`, and verify `listHosts type=Routing` reports all hosts `Up` and `Enabled`. |
| KVM hot attach fix | `/root/codex-backups/storage-service-p06-kvm-attach-fix-20260527-0205` on the three KVM hosts | Restore the backed-up KVM plugin jar if the P-06 hot attach class patch must be reverted, restart `mold-agent.service`, and rerun a safe attach/detach smoke test before continuing. |
| Current Storage Service VM runtime DHCP patch | Current P04 VM `i-2-428-VM` through QGA | Reapply the `cloud-dhclient@.service` unit from `fsvm-init.yml` if the VM is restarted before a rebuilt template is available; durable recovery is to rebuild/register a new template and deploy a fresh Storage Service VM. |
| Test volume recovery | P-06 volume IDs recorded above | If a TC fails after attach/import, detach through the Storage Service API first; if necessary, use the fixed KVM attach/detach path and verify the volume returns to ABLESTACK `Ready` before reuse. |

## Required Test Data

Prepare these resources after `P-00` through `P-08` are complete.

| ID | Resource | Requirement | Notes |
| --- | --- | --- | --- |
| TD-01 | Storage Service instance | Existing or newly created instance with `vmid` set | SystemVM must be running and QGA responsive |
| TD-02 | Unused data volume | Ready volume not attached to another VM | For new NFS/SMB share attach |
| TD-03 | Existing XFS volume | Volume with existing XFS filesystem and test files | For non-destructive import |
| TD-04 | Existing ext4 volume | Volume with existing ext4 filesystem and test files | For non-destructive import |
| TD-05 | NFS client | Prefer the local operator workstation or WSL when it can route to the Storage Service IP and has NFS mount tools | Use a Cloud VM only when local/WSL routing or kernel support is insufficient |
| TD-06 | SMB client | Prefer the local operator workstation or WSL for non-AD SMB access validation | AD-domain validation may require a domain-joined client or a dedicated AD test client |
| TD-07 | iSCSI client | Prefer the local operator workstation or WSL with `iscsiadm` when kernel support and routing are available | The P-07 Rocky client VM remains a fallback |
| TD-08 | NVMe-oF client | Prefer the local operator workstation or WSL with `nvme-cli`, `nvme-fabrics`, and `nvme-tcp` support | Current WSL initiator Host NQN for TC-03D: `nqn.2014-08.org.nvmexpress:uuid:be872b70-0df3-4c5f-9e64-56a7955dcd1a`; the P-07 Rocky client VM remains a fallback |
| TD-09 | Mold UI session | Browser login with an account that can create and manage SharedFS resources | Required for every functional TC |
| TD-10 | Deployed UI bundle | Latest SharedFS creation dialog and detail-tab UI deployed to the target Management Server | Verify normal and dark modes |

## UI-Led Functional And Look-And-Feel Test Order

All functional and look-and-feel validation must start from the Mold UI. Direct
signed API calls, CloudMonkey, DB queries, QGA commands, and SystemVM or client
shell commands are evidence channels, not substitutes for the UI workflow.

Do not start this order as a real functional validation until preparation
stages `P-00` through `P-08` are complete. Before then, only UI layout review or
API/DB dry-run checks are allowed and results must be marked as `Dry Run`, not
`Pass`.

1. Open the existing `Shared FileSystems` menu in the Mold UI.
2. Validate list, create action, normal mode, dark mode, i18n text, responsive
   layout, and operator-facing wording.
3. Create a SharedFS-backed Storage Service from the existing creation dialog.
4. Verify the UI drives the complete async lifecycle:
   SharedFS/SystemVM creation, Storage Service mirror creation, protocol
   enablement, export/share/target creation, ACL creation, and final refresh.
5. Open the created SharedFS detail page and use `File Service Management` for
   state-changing work.
6. Use `File Service Status` for health, inventory, sessions, endpoint, and
   SMB domain-state observation.
7. Validate NFS, SMB, iSCSI, and NVMe-oF management from the UI, then verify
   each result through API, QGA/SystemVM, and client behavior.
8. Validate existing-volume import and capacity expansion from the UI.
9. Validate failure, retry, rollback visibility, and operator-facing error
   messages from the UI.
10. Use direct API only to confirm the exact backend state that the UI caused.
11. For every test result, record functional status and look-and-feel status
    separately before assigning the final status.
12. Retest both the functional path and the affected UI path after any code
    change that touches API flow, async polling, labels, styles, theme classes,
    component layout, or i18n strings.

## Test Cases

### TC-01 SharedFS Create Dialog Frame, Common Fields, And Visual Baseline

Goal: verify operators start the workflow from the existing SharedFS menu and
that the creation dialog frame, common fields, review column, validation, and
theme behavior work before protocol-specific creation is tested.

Preparation dependency:

- Requires `P-01` and deployed UI bundle `TD-10`.
- Full creation requires `P-02` through `P-05`.

Scenario matrix:

| Subcase | Area | Required Checks |
| --- | --- | --- |
| TC-01A | Menu entry | Existing SharedFS list loads and the existing create action opens the expanded dialog |
| TC-01B | Owner selection | Account/project owner selection appears only when applicable and changes zone/network/service-offering candidates correctly |
| TC-01C | Basic information | Name, description, zone, network, filesystem, service offering, disk offering, custom size, and custom IOPS fields render and validate correctly |
| TC-01D | Two-column layout | Left review column and right configuration column are visible on desktop; narrow viewport collapses without losing content |
| TC-01E | Viewport height | Dialog height stays inside the browser viewport; central review/config area scrolls; bottom `Cancel` and `OK` buttons never overlap sections |
| TC-01F | Review panel | Review panel updates selected services, name, zone, network, filesystem, SMB identity mode, existing-volume mode, and import mode as inputs change |
| TC-01G | Theme and i18n | Normal mode, dark mode, Korean text, technical English terms, alerts, titles, labels, and disabled text remain readable |
| TC-01H | Cancel behavior | `Cancel` closes the dialog without creating a SharedFS, Storage Service instance, protocol row, export, share, target, or volume attachment |
| TC-01I | Long-value readability | Long SharedFS names, network names, offering names, IQN/NQN-like values, and generated defaults wrap or truncate with tooltip without breaking review or form layout |
| TC-01J | Action feedback | Required-field errors, async-submit readiness, disabled states, and final buttons use readable colors and clear Korean messages in both normal and dark mode |

Steps:

1. Log in to the Mold UI with a suitable account.
2. Open `Storage > Shared FileSystems`.
3. Verify the SharedFS list loads existing resources and exposes the existing
   create action.
4. Click `Create Shared FileSystem`.
5. Execute each TC-01 subcase in the matrix above.
6. Repeat visual checks in normal mode and dark mode. Use desktop width for the
   primary run and a narrow viewport when layout behavior is in scope.
7. Verify no storage operation is submitted while only changing UI fields.
8. Close with `Cancel` and verify no new SharedFS or Storage Service resource
   appears in the list or backend evidence.

Expected:

- The feature is discoverable from the existing SharedFS list.
- No separate Storage Service creation menu is required.
- The modal uses a two-column review/config layout on desktop and remains
  bounded by the browser viewport.
- Common SharedFS fields use the same ABLESTACK owner, zone, network, service
  offering, disk offering, custom size, and custom IOPS behavior as the legacy
  SharedFS flow.
- The review panel reacts immediately to field and service changes.
- Text, alerts, service cards, section headers, and buttons are readable in
  both normal and dark modes.
- Long values do not make labels and values overlap or collapse into unreadable
  columns.
- Validation and disabled states remain visible in both themes.
- Canceling the dialog leaves no backend resources.

Result:

| Run ID | Subcase | Mode/View | Functional Status | Look-And-Feel Status | Final Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TC01-20260527-01 | TC-01A | Dark / desktop | Pass | Pass | Pass | Operator confirmed the existing SharedFS list create action opens the expanded dialog |  |
| TC01-20260527-01 | TC-01B | Dark / desktop | Pass | Pass | Pass | Owner type, domain, and account controls rendered normally |  |
| TC01-20260527-01 | TC-01C | Dark / desktop | Pass | Pass | Pass | Common fields for name, description, zone, network, filesystem, service offering, disk offering, size, and IOPS rendered normally |  |
| TC01-20260527-01 | TC-01D | Dark / desktop | Pass | Pass | Pass | Left review and right configuration layout rendered as intended |  |
| TC01-20260527-01 | TC-01E | Dark / desktop | Pass | Pass | Pass | Dialog stayed within the visible browser area and bottom `Cancel` / `OK` buttons did not overlap sections |  |
| TC01-20260527-01 | TC-01F | Dark / desktop | Pass | Pass | Pass | Operator confirmed review behavior was normal |  |
| TC01-20260527-01 | TC-01G | Dark / desktop | Pass | Pass | Pass | Dark-mode readability is acceptable after retest; SMB authentication radio labels for `로컬 계정` and `Active Directory 도메인` are now readable | `TC01-UI-001` retested by operator after `js/app.6da000ae.js` deployment |
| TC01-20260527-01 | TC-01H | Dark / desktop | Pass | Pass | Pass | DB evidence after cancel showed no new SharedFS lifecycle side effect: `shared_filesystem` latest rows remain existing IDs 3/2/1; `storage_service_instance` latest rows remain existing IDs 2/1; `storage_service_protocol`, `storage_file_share`, `storage_block_target`, and `storage_access_rule` remain 0 rows | DB query used runtime-decrypted local DB credentials on the management server; credentials were not recorded |

### TC-02 SharedFS Create Dialog Service-Type Matrix

Goal: verify the creation dialog can configure every supported service type and
every service-type combination without hidden validation gaps or mixed-up
protocol sections.

Preparation dependency:

- Requires `P-01` and deployed UI bundle `TD-10`.

Service-type subcases:

| Subcase | Selected Services | Required UI Sections | Required Field Checks |
| --- | --- | --- | --- |
| TC-02A | None | Service selection only | `OK` is blocked or returns a clear validation message; review shows no selected service |
| TC-02B | NFS | NFS | Export name, export path, allowed CIDR, read/write mode, root squash, sync, secure, quota/capacity linkage |
| TC-02C | SMB local | SMB | Share name, share path, browse, guest, read-only, local identity mode, ACL intent, password-free review |
| TC-02D | SMB AD | SMB | AD mode, domain, usename, password, DNS servers, OU, workgroup, password clearing after close/submit, no password in review |
| TC-02E | iSCSI | iSCSI | Target IQN, initiator IQN ACL, LUN/backing-volume intent, no file-service-only fields |
| TC-02F | NVMe-oF kernel | NVMe-oF | `KERNEL_NVMET` engine, subsystem NQN, host NQN ACL, transport/prerequisite messaging, no SPDK VM runtime controls |
| TC-02G | NVMe-oF SPDK gated | NVMe-oF | SPDK appears only as disabled/planned/prerequisite-gated; no HugePage, NUMA, CPU pinning, memlock, SR-IOV, or PCI passthrough controls |

Combination subcases:

| Subcase | Selected Services | Required Checks |
| --- | --- | --- |
| TC-02H | NFS + SMB | Both file protocols appear as separate `NFS` and `SMB` sections; SMB identity does not affect NFS validation |
| TC-02I | iSCSI + NVMe-oF | Both block protocols appear as separate `iSCSI` and `NVMe-oF` sections; each ACL field validates independently |
| TC-02J | NFS + iSCSI | File and block settings remain separated; NFS path/CIDR and iSCSI IQN fields do not cross-validate |
| TC-02K | SMB + NVMe-oF | SMB identity/AD fields and NVMe-oF prerequisite fields remain independent |
| TC-02L | NFS + SMB + iSCSI + NVMe-oF | Four services appear as a two-by-two selection grid; four protocol sections are visible and review lists all services |
| TC-02M | Toggle after input | Enter values for all services, deselect one service, reselect it, and verify retained/cleared values and validation behavior are intentional |

Steps:

1. Open the SharedFS creation dialog.
2. Verify `NFS` is selected by default for backward compatibility.
3. Execute each TC-02 service-type subcase.
4. Execute each TC-02 combination subcase.
5. For every subcase, verify protocol-specific sections appear only for
   selected services and are titled exactly by protocol: `NFS`, `SMB`, `iSCSI`,
   and `NVMe-oF`.
6. Verify service cards are shown one or two per row; four services must appear
   as a balanced two-by-two grid.
7. Verify required-field validation is tied only to selected services.
8. Verify the review panel summarizes selected services and service-affecting
   choices without showing sensitive values.
9. Verify each selected protocol section remains readable in normal and dark
   mode, including radio labels, disabled text, alert icons, collapsed section
   headers, field help text, and validation messages.
10. Verify long generated names, IQNs, NQNs, volume names, and service offering
    names wrap cleanly in both the form and review panel.
11. For each subcase that is expected to be submitted later, record the exact
   values that will be used by TC-03 and service-management tests.

Expected:

- At least one service is required.
- NFS, SMB, iSCSI, and NVMe-oF settings are separated into explicit protocol
  sections.
- Hidden services do not block submission with irrelevant validation errors.
- The review panel summarizes the selected services.
- Every service type can be configured alone.
- Every mixed file/block combination can be selected without sections visually
  or logically bleeding into each other.
- SMB AD credential fields are treated as sensitive and are never shown in
  review text.
- SPDK remains visible only as a gated/planned option and never exposes VM
  runtime capability controls.
- Normal mode and dark mode are both readable for all service-type combinations.
- The service-selection grid and protocol sections remain visually separated
  and do not leave excessive empty space or hidden headings.

Result:

| Run ID | Subcase | Selected Services | Mode/View | Functional Status | Look-And-Feel Status | Final Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TC02-20260528-01 | TC-02A | None | Dark / desktop | Pass | Pass | Pass | Operator confirmed `서비스를 하나 이상 선택해야 합니다.` appears when all services are deselected |  |
| TC02-20260528-01 | TC-02B | NFS | Dark / desktop | Pass | Pass With Improvement | Pass With Improvement | Operator confirmed NFS section shows name, path, allowed CIDR, permission, squash, sync, and secure-port options | NFS export `이름` label is ambiguous against the representative SharedFS name; track `TC02-UI-001` |
| TC02-20260528-01 | TC-02C | SMB local | Dark / desktop | Pass | Pass With Improvement | Pass With Improvement | Operator confirmed SMB section shows share path, permission flags, and local/AD authentication mode; dark-mode radio labels are readable after `TC01-UI-001` | SMB share `이름` label is ambiguous against the representative SharedFS name; track `TC02-UI-001` |
| TC02-20260528-01 | TC-02D | SMB AD | Dark / desktop | Pass | Pass | Pass | Operator confirmed AD domain, usename, password, DNS, OU, and workgroup fields appear when `Active Directory 도메인` is selected |  |
| TC02-20260528-01 | TC-02E | iSCSI | Dark / desktop | Pass With Defect | Pass With Defect | Pass With Defect | Operator confirmed target IQN and initiator IQN fields appear | LUN and permission are hidden defaults in the code (`lun=0`, `permission=READ_WRITE`), and the initiator label does not clearly state that it is an allowed initiator ACL; track `TC02-UI-002` |
| TC02-20260528-01 | TC-02F | NVMe-oF kernel | Dark / desktop | Pass | Pass With Improvement | Pass With Improvement | Operator confirmed engine, subsystem NQN, host NQN, and planned-state guidance appear | Transport/port/namespace or backing-volume intent is not visible, and host NQN should be labeled as an allowed host ACL; track `TC02-UI-003` |
| TC02-20260528-01 | TC-02G | NVMe-oF SPDK gated | Dark / desktop | Partially Verified | Partially Verified | Partially Verified | Operator confirmed NVMe-oF guidance is shown and no VM-level HugePage/NUMA/CPU pinning/memlock/SR-IOV/PCI passthrough controls appear | Explicit SPDK gated-option behavior still needs a targeted retest after the NVMe-oF section labels are improved |
| TC02-20260528-01 | TC-02H | NFS + SMB | Dark / desktop | Pass | Pass | Pass | Operator confirmed combination sections stay separated and ordered |  |
| TC02-20260528-01 | TC-02I | iSCSI + NVMe-oF | Dark / desktop | Pass With Defect | Pass With Defect | Pass With Defect | Operator confirmed combination sections stay separated and ordered | Carries `TC02-UI-002` and `TC02-UI-003` |
| TC02-20260528-01 | TC-02J | NFS + iSCSI | Dark / desktop | Pass With Defect | Pass With Defect | Pass With Defect | Operator confirmed file and block sections stay separated and ordered | Carries `TC02-UI-002` |
| TC02-20260528-01 | TC-02K | SMB + NVMe-oF | Dark / desktop | Pass | Pass With Improvement | Pass With Improvement | Operator confirmed SMB and NVMe-oF sections stay separated and ordered | Carries `TC02-UI-003` |
| TC02-20260528-01 | TC-02L | NFS + SMB + iSCSI + NVMe-oF | Dark / desktop | Pass With Defect | Pass With Defect | Pass With Defect | Operator confirmed all selected sections stay separated and ordered | Carries `TC02-UI-001`, `TC02-UI-002`, and `TC02-UI-003` |
| TC02-20260528-01 | TC-02M | Toggle after input | Dark / desktop | Pass With Defect | Pass With Defect | Pass With Defect | Operator confirmed input handling is generally normal and the review panel updates immediately for most fields; DB evidence after cancel showed no new resources: `shared_filesystem=3`, `storage_service_instance=2`, and protocol/share/target/access-rule tables remain `0` rows | The review panel does not make service-specific names/targets/subsystems clear and name updates are ambiguous; track `TC02-UI-004` |

### TC-03 UI Create Lifecycle: SharedFS And Storage Service Mirror

Goal: verify the UI can create a SharedFS-backed Storage Service lifecycle and
refresh to a manageable detail page.

Preparation dependency:

- Requires `P-01` through `P-05`.
- Use the rebuilt Storage Service SystemVM template
  `SystemVM Template Storage Service (KVM) 202605300121`
  (`ad0378ce-cf2d-4425-b98d-170e3395565f`) for new service creation.
- Existing running Storage Service VMs are not part of this test path unless a
  step explicitly says to compare migration or reconciliation behavior.

Steps:

1. Select one creation profile from the adjusted TC-03 matrix below.
2. In the UI, create a SharedFS with a valid name, zone, network, service
   offering, disk offering, filesystem, and the selected service profile.
3. Submit from the dialog.
4. Track UI async progress and job notifications for these phases:
   SharedFS creation, SystemVM deployment, Storage Service instance binding,
   protocol enablement, initial object creation, ACL/authentication creation,
   and final refresh.
5. Verify the SharedFS appears in the list.
6. Open the SharedFS detail page.
7. Verify the `File Service Management` tab locates or reconciles the Storage
   Service mirror for the SharedFS SystemVM.
8. Verify the detail page keeps common status in the detail tab and exposes
   service-specific management/status through protocol-oriented tabs or
   sections without reintroducing the legacy NFS-only `Access` view.
9. Verify create success, partial setup failure, and post-setup verification
   messages identify the exact lifecycle phase in Korean and remain readable in
   normal and dark mode.
10. Use API/DB only as evidence to confirm the SharedFS row, Storage Service
   instance, protocol rows, and VM ID mapping.
11. Verify the `File Service Status` tab loads health/inventory without exposing
   state-changing controls.

Expected:

- UI submits async Mold APIs only.
- SharedFS/SystemVM lifecycle completes or shows a clear operator-facing error.
- The detail page is reachable from the list.
- The Storage Service mirror is bound to the SharedFS VM and is manageable from
  the detail page.
- Protocols selected in the TC-02 profile result in matching Storage Service
  protocol rows and, where requested by the creation dialog, initial
  export/share/target/subsystem records.
- Sensitive values, including SMB AD passwords, iSCSI CHAP secrets, and
  NVMe-oF DH-HMAC-CHAP keys, are sent only as runtime request payload values
  and are not shown in review panels, list tables, logs, or persisted state.
- Post-create detail, management, and status views are readable in normal and
  dark mode, with long names and IDs wrapping safely.
- Monitoring-only views do not contain state-changing action buttons; management
  views use explicit action buttons and dialogs for changes.

Result:

| Run ID | Creation Profile | Required Service Objects | SharedFS | Storage Service Instance | Functional Status | Look-And-Feel Status | Final Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
|  | NFS only | NFS protocol, one export, one CIDR ACL |  |  | Not Run | Not Run | Not Run |  |  |
| TC03-20260528-01 | NFS only | NFS protocol, one export, one CIDR ACL | `tc03-nfs-20260528` / `5c9cbb47-e3df-49bf-bab6-53c88437190a` / VM `5b68e0c6-be6c-499f-b896-ddd66134f086` | `48839b80-9226-45a5-a095-163733f8a85a` | Pass With Defect | Pass | Pass With Defect | Operator confirmed the UI displayed a Storage Service creation success message. API evidence confirms SharedFS state `Ready`, VM state `Running`, VM template `SystemVM Template Storage Service (KVM) 202605281348`, VM IP `10.10.254.98`, host `ablecube22-3`, Storage Service instance state `Running`, `listStorageServiceHealth` success `true` / status `ok`, QGA active, and inventory command success. | Initial NFS export and ACL were not created: `listStorageNfsExports instanceid=48839b80-9226-45a5-a095-163733f8a85a` retuned no rows, `listStorageNfsAcls` retuned no rows, and SystemVM inventory reported `nfsExports=[]` with NFS service `inactive`; track `TC03-UI-001`. |
| TC03-20260528-02 | NFS only retest after `TC03-UI-001` fix | NFS protocol, one export, one CIDR ACL | `tc03-nfs-20260528` / `8d0175a5-0957-412c-a473-02834c7a2e01` / VM `68679eee-e3ff-4382-8941-a497f053d588` | `81ba501d-e49e-4ede-8614-f6cf888ef59a` | Fail | Not Fully Rechecked | Fail | Operator restarted TC-03-01 from the UI and reported the creation flow was submitted. API evidence confirms the SharedFS reached `Ready`, the VM is `Running` on `ablecube22-1` with IP `10.10.254.9`, the VM uses template `SystemVM Template Storage Service (KVM) 202605281348`, the Storage Service instance is `Running`, `listStorageServiceHealth` returns `success=true` and `status=ok`, QGA is active, and NFS service is `active`. | The lifecycle fix is only partial. Runtime inventory shows the legacy compatibility root export `/export 0.0.0.0/0(rw,sync,no_root_squash,secure,no_subtree_check)` as active, but this root export must not be used by the expanded Storage Service design. The user-configured initial export `nfs01` at `/export/nfs01` exists as a `Ready` API row but has no matching exportfs entry and no ACL. `listStorageNfsAcls exportid=<export-uuid>` also fails with HTTP `530` due to missing entity-reference handling. Track `TC03-UI-002`; do not proceed to TC-04 as a functional pass until `/export` root publishing is disabled, the selected child export/ACL is applied, and ACLs are listable by export ID. |
| TC03-20260528-03 | NFS only retest with rebuilt template `202605282236` | NFS protocol, export `nfs01`, one CIDR ACL | `tc03a-nfs-20260528` / `4ac7d411-f801-41e0-96d3-54a20b0d726a` / VM `b1f38d5f-4a7d-41b6-a3bd-dc30888e7799` | `082837e6-62b0-4f7e-b378-5e4d3e65fa77` | Pass With Defect | Pending Operator UI Review | Pass With Defect | Operator reported the UI creation was submitted and noted the export name was `nfs01`. API evidence confirms SharedFS `Ready`, VM `Running`, VM template `SystemVM Template Storage Service (KVM) 202605282236`, VM IP `10.10.254.2`, host `ablecube22-3`, Storage Service instance `Running`, NFS export `nfs01` at `/export/nfs01` `Ready`, ACL row `Ready`, ACL principal `0.0.0.0/0` as intentionally entered by the operator, health `success=true` / `status=ok`, QGA active, and NFS service `active`. Runtime inventory and `showmount -e 10.10.254.2` report only `/export/nfs01 0.0.0.0/0` and do not report the deprecated `/export` root export. | Functional improvement confirmed: the new template is used, the deprecated `/export` root export is no longer published, the selected child export is active, and ACL listing by export ID works. New defect: the client-visible NFS export path is still the internal path `/export/nfs01`. The intended mount format is `<service-ip>:/<exportName-or-exportPath>` such as `10.10.254.2:/nfs01`, not `10.10.254.2:/export/nfs01`. Track `TC03-UI-003`. Look-and-feel status remains pending until the operator confirms the post-create detail, `File Service Management`, and `File Service Status` screens in normal/dark mode. |
| TC03-20260529-01 | Runtime fix retest on current TC03A VM | NFS protocol, export `nfs01`, ACL `0.0.0.0/0` | `tc03a-nfs-20260528` / `4ac7d411-f801-41e0-96d3-54a20b0d726a` / VM `b1f38d5f-4a7d-41b6-a3bd-dc30888e7799` | `082837e6-62b0-4f7e-b378-5e4d3e65fa77` | Runtime Pass | Pending Operator UI Review | Runtime Pass | Current VM was runtime-patched through QGA with the updated `ablestack-storagectl`; applying desired NFS state rendered `/nfs01` as the exported path, translated ACL `0.0.0.0/0` to Linux exports wildcard, and `showmount -e 10.10.254.2` retuned `/nfs01 *`. A host-side mount test from `ablecube22-3` succeeded with `mount -t nfs 10.10.254.2:/nfs01 /tmp/tc03a-nfs01-mount` and was unmounted cleanly. | Runtime defect fixed on the current VM. Durable validation still requires recreating the service from rebuilt template `SystemVM Template Storage Service (KVM) 202605290014` and operator confirmation that UI connection guidance shows `<service-ip>:/nfs01`, not the internal `/export/nfs01` path. |
| TC03-20260529-02 | Fresh template retest after `TC03-UI-003` fix | NFS protocol, export `nfs01`, ACL `0.0.0.0/0` | `tc03a-nfs-20260528` / `0921e19a-6dda-4843-b889-30375e89c683` / VM `ed2b6729-f02e-4a34-84d2-9844b2fd82d9` | `ee49bd5d-1223-4fca-a957-efb7822a9498` | Pass | Pass | Pass | Operator recreated the file system from the UI. API evidence confirms SharedFS `Ready`, VM `Running`, VM template `SystemVM Template Storage Service (KVM) 202605290014`, VM IP `10.10.254.7`, host `ablecube22-3`, Storage Service instance `Running`, NFS export `nfs01` with internal path `/export/nfs01` `Ready`, ACL row `Ready`, ACL principal `0.0.0.0/0`, health `success=true` / `status=ok`, QGA `active`, NFS `active`, and zero active sessions before the mount check. Runtime evidence from `ablecube22-3` confirms `showmount -e 10.10.254.7` returns `/nfs01 *`; `mount -t nfs 10.10.254.7:/nfs01 /tmp/tc03-new-nfs01-mount` succeeded and was unmounted cleanly. UI evidence confirms the NFS tab loads the current deployed bundle, displays the status summary, NFS export, ACL, and backing volume values in dark mode, and exposes the client-visible mount root as `10.10.254.7:/nfs01`. | Durable root-name exposure fix passed on a fresh VM created from template `202605290014`. The internal backing path remains `/export/nfs01`, but the client-visible root is correctly `/nfs01`. NFS tab look-and-feel validation passed after direct browser review and operator confirmation. |
| TC03B-20260530-02 | SMB local only | SMB protocol, one share, local identity mode | `tc03b-smb-20260530` / `f80918ea-72a1-4860-84f1-fa11de85c11d` / VM `de1f3fa4-7a1f-418a-8322-f9cbf77c4c58` | `e47b227f-06fb-479f-8363-0c94b10fa6c0` | Fail / Partial Created | Pending Operator UI Review | Fail / Partial Created | SharedFS, Storage Service instance, SMB protocol, and SMB share `smb01` were created; SystemVM runtime inventory shows the share. | SMB ACL creation and ACL listing failed before the fix because `StorageSmbShareResponse` lacked entity-reference metadata; track `TC03-UI-006`. |
| TC03B-20260530-03 | SMB local only retest after `TC03-UI-006` fix | SMB protocol, one share, local identity mode, one local-user ACL | `tc03b-smb-20260530` / `4a295948-8bd6-44b6-a60f-8f430d7a0e98` / VM `e6c3d9c9-3197-4d56-9d6b-b151a5b5f223` | `94baf0bc-d961-461e-b698-e99849956e3f` | Pass With Defect | Pending Operator UI Review | Pass With Defect | SharedFS is `Ready`, VM is `Running`, Storage Service instance is `Running`, SMB share `smb01` / `cbd542ea-7169-4d08-9825-879c73e23548` is `Ready`, SystemVM runtime inventory shows `smb01`, and `listStorageSmbAcls shareid=<share-id>` returns ACL `5c6da928-57e1-49d8-b8e4-097fba37fd4e` with `principaltype=LOCAL_USER`, `principal=admin`, `permission=READ_WRITE`, and `state=Ready`. | The previous `shareid` EntityReference defect is fixed. New metadata defect: the SharedFS backing volume is `sharedfs-DATA-442` / `72d9ec13-0718-4245-963f-d9bf79cee986`, but the SMB share row references old volume `sharedfs-DATA-24` / `ae6935e3-4ea8-49c6-aeb8-78807a62cc68` attached to another SharedFS VM. Track `TC03-UI-007` before capacity/volume management tests. |
| TC03B-20260530-04 | SMB local only retest after async-create, SMB local-account idempotency, ACL error-state, and backing-volume fixes | SMB protocol, one share, local identity mode, one local-user ACL, fresh SystemVM template `202605302125` | `tc03b-smb-20260530` / `c15fb904-3a85-4122-bd17-c02e23ef699e` / VM `63e2bec5-e215-47fe-8f24-78b569738796` (`i-2-446-VM`) | `31ddbda0-a7f1-4d7c-890b-e5d60b142ed2` | Pass | Pending Operator UI Review | Pass | SharedFS is `Ready`, VM is `Running` on `ablecube22-2` with IP `10.10.254.174`, and the VM was created from `SystemVM Template Storage Service (KVM) 202605302125` / `7ace5e78-9158-4fb2-afb2-5694b1bbba51`. Storage Service instance is `Running`. SMB share `smb01` / `f6adab84-8af3-4119-a030-7bb534c3b3ca` is `Ready`, uses current backing volume `03215a2b-bb6b-4c56-902d-a497f189e8f2`, and exposes internal path `/export/smb01`. SMB ACL `6196ee2a-9b3f-4055-b94b-691a49567ce7` is `Ready` with `principaltype=LOCAL_USER`, `principal=admin`, and `permission=READ_WRITE`. Runtime health reports `success=true`, `status=ok`, `qemuGuestAgent=active`, `smbd=active`, `nmbd=active`, and `winbind=active`. QGA evidence confirms Samba section `[smb01]` has `path=/export/smb01`, `valid users=admin`, `write list=admin`; Linux user `admin` and Samba user `admin` exist; monitor cache files exist under `/run/ablestack-storage/monitor`. | TC-03B backend/runtime acceptance passed. Remaining UI acceptance is operator visual review: create dialog async close/banner behavior and SMB tab display should be confirmed in the browser before closing the UI side of TC-03B. |
|  | SMB AD gated | SMB protocol, one share, AD join request or prerequisite message |  |  | Not Run | Not Run | Not Run |  |  |
| TC03C-20260530-01 | iSCSI no-auth retest after service-tab table and LUN-apply fixes | iSCSI protocol, one target, one LUN, optional no-auth initiator ACL, live-patched SystemVM source pending next template rebuild | `tc03c-iscsi-20260530` / `d52d336f-c048-4722-ad06-f77970536572` / VM `13f8d6fa-5ead-4f3a-85c5-2c670c16402f` (`i-2-447-VM`) | `0a2fe8d0-7645-46d4-942b-3edb7bbd48dd` | Runtime Pass | Ready For Operator UI Retest | Runtime Pass | UI build succeeded and was deployed as `js/app.cb8ea299.js`; server build succeeded and `mold.service` is active after deploying the updated server jar; the running TC-03C SystemVM was patched through QGA with the updated `ablestack-storagectl`; `ablestack-storagectl iscsi target apply /etc/ablestack-storage/iscsi-targets.json` retuned success with one applied target and TCP 3260 listening; host-side `nc -zv 10.10.254.176 3260` connected; `targetcli ls /iscsi` shows target `iqn.2026-05.local.storage:tc03c01`, portal `0.0.0.0:3260`, and LUN 0 backed by fileio path `/export/.ablestack-storage/iscsi/2ff61528-5ec0-44fd-8661-9c056dae93ff.img`; monitor-cache health returns `status=ok`, QGA active, iSCSI target active, and `listenPorts.iscsi=true`. | The iSCSI target/LUN/runtime defect is fixed on the current VM. Because the default SharedFS data disk is already mounted by the legacy `/export` path, the runtime now creates a managed file-backed LUN under `/export/.ablestack-storage/iscsi/` instead of trying to expose the mounted block device directly. Current desired state contains no ACL rows, so ACL display must be judged from the operator-selected create inputs during the UI retest. Source is updated, but this exact fix has not yet been rebuilt into a new SystemVM template in this run. |
| TC03C-20260531-01 | iSCSI no-auth final retest after API, SystemVM, monitor-cache, and UI table fixes | iSCSI protocol, one target, one LUN, no-auth runtime target, current backing-volume metadata, fresh template `202605310233` | `tc03c-iscsi-20260531` / `f2b55380-f85d-4952-bc18-cd99c1d4cbd0` / VM `3cea874c-e6d2-4293-a72d-3181a73cda66` (`i-2-448-VM`) | `7eb59eb9-0a80-4cbf-ac78-bff370b0d445` | Pass | Pass | Pass | Operator confirmed the iSCSI tab displays the expected target information. Direct browser review of `#/sharedfs/f2b55380-f85d-4952-bc18-cd99c1d4cbd0?tab=iscsi` confirmed the page loads in dark mode without raw `label.storage.service` keys and shows target `iqn.2026-05.local.storage:tc03c01`, backing volume `sharedfs-DATA-448`, configured/effective LUN size around `50 GiB`, state `Ready`, and runtime backing path `/export/.ablestack-storage/iscsi/f1d5043c-2206-4062-8df4-586599cb70d7.img`. API/runtime evidence confirms SharedFS `Ready`, VM `Running` on `ablecube22-1`, service IP `10.10.254.175`, data volume `cc6fdc71-805d-419a-bc52-7b7f758a9923` / `53687091200` bytes, and Storage Service instance `Running`. The current template `SystemVM Template Storage Service (KVM) 202605310233` / `ce15f78c-a1fa-4a1f-91c9-4eb72af6036f` is registered, ready, and includes the iSCSI LUN backing and runtime inventory fixes. | TC-03C is accepted. The UI/API/SystemVM values now agree for target IQN, backing volume, LUN size, effective runtime size, and runtime backing path. Managed file-backed LUN placement below the attached data disk is the accepted behavior when the default SharedFS data disk is already mounted at `/export`. |
|  | iSCSI CHAP | iSCSI protocol, one target, one LUN, one CHAP initiator ACL |  |  | Not Run | Not Run | Not Run |  |  |
| TC03D-20260531-01 | NVMe-oF kernel no-auth | NVMe-oF protocol, kernel preparation, one subsystem, one namespace, one host ACL from local WSL host NQN | `tc03d-nvmeof-20260531` / `96a6090d-c252-4728-aa95-6b7d8f776abc` / VM `20db44c4-db9a-4cfe-942a-84810fe63454` (`i-2-449-VM`) | `4fca3921-8d75-416f-80ba-e938c97aa48a` | Partial Created / Fail | Pass With Defect | Fail | Operator created the NVMe-oF-only SharedFS from the UI using local WSL Host NQN `nqn.2014-08.org.nvmexpress:uuid:be872b70-0df3-4c5f-9e64-56a7955dcd1a`. SharedFS reached `Ready`, VM is `Running` on `ablecube22-3` with IP `10.10.254.177`, backing volume is `sharedfs-DATA-449`, Storage Service instance is `Running`, NVMe-oF subsystem `nqn.2026-05.local.storage:tc03d01` is `Ready`, namespace `1` is `Ready`, monitor cache is `ok`, and TCP 4420 is listening. The NVMe-oF tab displays endpoint, subsystem, namespace, and backing volume in dark mode. Local WSL `nvme discover -t tcp -a 10.10.254.177 -s 4420` succeeds. | Host ACL creation failed during initial service setup with `PermissionError: [Erno 13] Permission denied` from the SystemVM NVMe-oF apply path. API host ACL list was empty, SystemVM state had no `hosts`, and local WSL `nvme connect` failed. Track `TC03-UI-010`; this run remains the original failing evidence. |
| TC03D-20260531-02 | NVMe-oF kernel no-auth hot-patch verification | NVMe-oF protocol, kernel preparation, one subsystem, one namespace, one host ACL from local WSL host NQN | Existing `tc03d-nvmeof-20260531` / `96a6090d-c252-4728-aa95-6b7d8f776abc` / VM `20db44c4-db9a-4cfe-942a-84810fe63454` (`i-2-449-VM`) hot-patched through QGA | `4fca3921-8d75-416f-80ba-e938c97aa48a` | Pass | Pass | Pass | Deployed the NVMe-oF reconcile fix to management jars and the running SystemVM; `createStorageNvmeOfHostAcl` for the local WSL Host NQN completed with ACL `279211e3-2db3-463e-8796-107c91c3f988` in `Ready`; SystemVM configfs contains the expected host ACL and namespace device path `/dev/loop0`; local WSL `nvme discover -t tcp -a 10.10.254.177 -s 4420 -q <hostnqn>` and `nvme connect -t tcp -a 10.10.254.177 -s 4420 -n nqn.2026-05.local.storage:tc03d01 -q <hostnqn>` both retuned success; `nvme list-subsys` showed a live TCP connection. | This is a hot-patch verification on an already-created VM. Durable acceptance still requires a fresh UI-led TC-03D retest from template `SystemVM Template Storage Service (KVM) 202605311453`. |
| TC03D-20260531-03 | NVMe-oF kernel no-auth fresh-template retest | NVMe-oF protocol, kernel preparation, one subsystem, one namespace, one host ACL from local WSL host NQN | `tc03d-nvmeof-20260531` / `c8d6ac2c-ac26-4ba9-958b-3beb326071ed` / VM `72445e73-2266-49d8-8e48-15d882ec1672` (`i-2-450-VM`) | `b7f599c1-6eb3-4b87-93a4-90f69977249e` | Pass | Pass With Improvement | Pass | Operator confirmed the NVMe-oF tab looked normal in dark mode. API and VM inspection confirmed the SharedFS is `Ready`, Storage Service VM is `Running` on `ablecube22-1`, template is `SystemVM Template Storage Service (KVM) 202605311453`, service IP is `10.10.254.179`, backing volume is `sharedfs-DATA-450`, subsystem `b103fa95-45b0-42e2-b90f-165f35dfec9c` / `nqn.2026-05.local.storage:tc03d01` is `Ready`, namespace `1` is `Ready` on volume `06657d34-3837-43a3-95b9-0ec3dac62cfa`, Host ACL `8b1260b8-5f85-4389-81e9-539eb6aff588` for the local WSL Host NQN is `Ready`, health cache is `ok`, `qemu-guest-agent` and `ablestack-storage-monitor` are active, and TCP 4420 is listening. Local WSL `nvme discover` and `nvme connect` both succeeded against `10.10.254.179:4420`; `/dev/nvme0n1` appeared as a 53.69 GB namespace; session cache showed 17 established NVMe-oF TCP sessions while connected and retuned to 0 after disconnect. | Functional TC-03D passes. Improvement tracked as `TC03-UI-011`: SystemVM desired-state file persisted the Host ACL object with `state=Creating` even though API state is `Ready` and configfs is correct. This does not block connection, but server-side desired-state generation should persist/apply the final `Ready` state to keep runtime state files consistent with API state. |
| TC03D-20260531-04 | NVMe-oF Host ACL desired-state state consistency retest | Existing fresh-template TC03D service, idempotent Host ACL update after server-side desired-state fix | `tc03d-nvmeof-20260531` / `c8d6ac2c-ac26-4ba9-958b-3beb326071ed` / VM `72445e73-2266-49d8-8e48-15d882ec1672` (`i-2-450-VM`) | `b7f599c1-6eb3-4b87-93a4-90f69977249e` | Pass | Pass | Pass | The server was rebuilt and deployed to 22.x with Host ACL desired-state state override logic. `updateStorageNvmeOfHostAcl id=8b1260b8-5f85-4389-81e9-539eb6aff588` completed successfully and retuned ACL state `Ready`. QGA inspection of `/etc/ablestack-storage/nvmeof-subsystems.json` confirmed the Host ACL object for `nqn.2014-08.org.nvmexpress:uuid:be872b70-0df3-4c5f-9e64-56a7955dcd1a` now stores `state=Ready`, not `Creating` or `Updating`. Local WSL `nvme discover` and `nvme connect` against `10.10.254.179:4420` succeeded and the test connection was disconnected cleanly. | `TC03-UI-011` is fixed. No SystemVM template rebuild was required for this correction because only management-server desired-state generation changed; the current template `SystemVM Template Storage Service (KVM) 202605311453` remains valid. |
| TC03D-20260531-05 | NVMe-oF DH-HMAC-CHAP | NVMe-oF protocol, kernel preparation, one subsystem, one namespace, one Host NQN ACL with host and controller DH-HMAC-CHAP enabled | `tc03d-nvmeof-chap-20260531` / `8962f428-22aa-47a9-9fad-7cca717e5242` / VM `889e84ce-b081-4a76-a763-faa94d08db62` (`i-2-451-VM`) | `e4c270b0-2ee5-4f17-8227-5a31b7775779` | Fail | Fail | Fail | SharedFS is `Ready`, VM is `Running` on `ablecube22-3`, template is `SystemVM Template Storage Service (KVM) 202605311453`, service IP is `10.10.254.176`, Storage Service instance is `Running`, subsystem `nqn.2026-05.local.storage:tc03d02` is `Ready`, namespace `1` is `Ready` on volume `sharedfs-DATA-451`, Host ACL `4d2d737a-d7f8-4c94-8715-b16933b94848` is `Ready`, API ACL config reports `dhChapEnabled=true` and `dhChapCtrlEnabled=true`, health cache is `ok`, TCP 4420 is listening, and the NVMe-oF tab shows endpoint/subsystem/namespace/backing volume/session rows in dark mode. During a no-auth connection test, session cache reported 17 `ESTAB` NVMe-oF sessions and retuned to 0 after disconnect. | Authentication is not actually enforced. SystemVM configfs host directories contain no `dhchap_key` or `dhchap_ctrl_key` attributes, so the target cannot apply CHAP keys; connecting without CHAP secrets succeeds. The monitor inventory/result JSON also includes DH-HMAC-CHAP secret values, violating the runtime-only secret rule. The UI access table shows DH-HMAC-CHAP as `-` even though API config has it enabled, and connection guidance omits authenticated connect options. The session table lists active client sessions, but connection time and connected subsystem NQN are displayed as `-`, so session attribution is incomplete. Track `TC03-UI-012`, `TC03-UI-013`, `TC03-UI-014`, and `TC03-UI-015`; do not pass the authenticated NVMe-oF case until these are fixed. |
| TC03D-20260531-06 | NVMe-oF DH-HMAC-CHAP fix deployment | Management/API redaction, UI auth mode/guidance, SystemVM storagectl/monitor changes, fresh SystemVM template | Existing failing TC03D-02 service for API redaction smoke check | `e4c270b0-2ee5-4f17-8227-5a31b7775779` | Applied | Applied | Pending Retest | Server build passed with `mvn -pl server -am -DskipTests -Dcheckstyle.skip=true -Drat.skip=true install`. UI build passed with `NODE_OPTIONS=--openssl-legacy-provider npm run build` and deployed bundle `js/app.8809e117.js`. Management patch updated `StorageServiceManagerImpl.class` in `cloud-server-4.22.0.0-SNAPSHOT.jar` and `cloudstack-4.22.0.0-SNAPSHOT.jar`; `mold.service` restarted and `/client/api` returns HTTP `401`. Runtime API redaction smoke check for `listStorageServiceInventory instanceid=e4c270b0-2ee5-4f17-8227-5a31b7775779` retuned one successful `ok` response with no DH-HMAC-CHAP secret prefix, `dhChapKey`, `dhChapCtrlKey`, or `secrets` tokens. New SystemVM template `SystemVM Template Storage Service (KVM) 202605311725` / `d60ac2fc-dcbc-4f88-9ba5-33c39077c25b` registered and reached `Download Complete` / `isready=true`; artifact MD5 is `63e577cb0d72bddc9db3ff7d63a1e8c4`. | This is a fix deployment checkpoint, not a functional PASS for authenticated NVMe-oF. Fresh-template TC03D-02 must be rerun. Expected behavior on kernels without configfs DH-HMAC-CHAP attributes is now explicit apply failure/ACL Error, not silent Ready. |
| TC03D-20260601-01 | NVMe-oF DH-HMAC-CHAP capability gating | Current SystemVM kernel/configfs capability check, UI/API behavior, and connection safety | `tc03d-nvmeof-chap-20260531` / `a16bdf5a-5e60-477e-8f39-264c12e81381` / VM `32f2a457-549b-444e-80ed-b1045c69cc54` (`i-2-453-VM`) | `30cdad06-83b3-4a3c-af51-2176863d450d` | Fail | Fail | Fail | Direct VM inspection confirmed SystemVM kernel `6.1.0-49-amd64` exposes `/sys/kernel/config/nvmet/hosts/<hostNQN>/` but does not expose `dhchap_key` or `dhchap_ctrl_key`. The SharedFS and Storage Service instance are `Ready`/`Running`, subsystem `nqn.2026-05.local.storage:tc03d02` and namespace `1` are `Ready`, and TCP 4420 is listening, but the Host ACL with `dhChapEnabled=true` and `dhChapCtrlEnabled=true` is `Error`. WSL client `nvme discover` and `nvme connect` using the matching Host NQN fail and no namespace is attached; `allowed_hosts` is empty, so the target is not left open without authentication. | The correct behavior for the current template is to treat DH-HMAC-CHAP as unsupported, not as a selectable option that fails after apply. The fix must add `capabilities.nvmeof.dhChapSupported=false` and `dhChapCtrlSupported=false` to SystemVM health/inventory, disable DH-HMAC-CHAP controls in the create and service-management UI, and show an explicit unsupported message. Host NQN ACL without CHAP remains supported. |
| TC03D-20260601-02 | NVMe-oF final fresh-template regression | Latest cross-zone SYSTEM template, NVMe-oF no-auth, one subsystem, one namespace, one Host NQN ACL from local WSL initiator, DH-HMAC-CHAP capability gating | `tc03d-final2-20260601` / `734792f2-1df0-48cf-a114-0b5b31db6460` / VM `990244a3-ab95-4fe6-86f5-d9255a08c88c` (`i-2-455-VM`) | `ab4023e2-62bd-4978-a881-50c1e8a2eca5` | Pass | API/runtime Pass; operator UI visual review optional | Pass | Fresh SharedFS creation initially exposed a precondition issue: the latest `202606010104` artifact had also been registered as a zone-scoped `USER` template and was therefore not selected by `findSystemVMReadyTemplate`; SharedFS creation selected older SYSTEM template `sstest0`. The wrong-template test VM was destroyed, the same artifact was re-registered as cross-zone SYSTEM template `SystemVM Template Storage Service (KVM) 202606010104 SYSTEM` / `5d581192-0452-4adf-815a-0ac8b6aff984`, and the final retest VM used that template. SharedFS reached `Ready`; VM is `Running` on `ablecube22-2`; service IP from QGA is `10.10.254.18`; data volume is `sharedfs-DATA-455` / `d6b8751c-7784-448d-934d-2a7fb4e210be`; subsystem `b1bd071d-9ee3-4afc-b4e0-770f702fb7e4` / `nqn.2026-06.local.storage:tc03d-final2`, namespace `1`, and Host ACL `0a04f141-89f7-43a1-bdd6-5c11e1ff6eff` are all `Ready`. Health and inventory report `status=ok`, TCP 4420 listening, kernel `6.1.0-49-amd64`, `dhChapSupported=false`, `dhChapCtrlSupported=false`, and reason `missing nvmet host dhchap_key/dhchap_ctrl_key`. WSL `nvme discover`, `nvme connect`, `/dev/nvme0n1` attach, 50 GiB size check, and direct read all passed; session inventory showed 17 `NVME_OF` `ESTAB` sessions with `connectedAt` and subsystem NQN while connected, and retuned to 0 after disconnect. | TC-03D no-auth/Host NQN ACL path is accepted on the latest template. DH-HMAC-CHAP is accepted only as an environment-unsupported capability-gated path for the current SystemVM kernel. Also note the API-key test haness must sign Cloud API values by URL-encoding first and then lowercasing; lowercasing before URL-encoding causes false 401 errors for values containing `:` such as NQNs. |
|  | All services mixed | NFS, SMB local, iSCSI no-auth, NVMe-oF kernel no-auth |  |  | Not Run | Not Run | Not Run |  |  |

### TC-04 Ganesha NFS Endpoint Criteria

The TC-04 NFS lifecycle tests now use the NFS-Ganesha based serving model.

- Authoritative client mount validation uses NFSv4 and the client-visible
  export name: `mount -t nfs -o vers=4 <endpoint-ip>:/<export-name>
  <mount-point>`.
- If an endpoint uses a non-default port, include `port=<port>` in mount
  options.
- `/export/<export-name>` is an internal SystemVM alias and must not be treated
  as the client mount root.
- An export bound to selected endpoints must be reachable only through those
  endpoints.
- `showmount -e` is a compatibility observation only and is not the pass/fail
  source for Ganesha/NFSv4 pseudo paths.
- SharedFS creation and NFS export new-volume creation must show disk offering
  first and primary storage second. Primary storage choices must be filtered by
  disk offering storage tags, and the selected pool must be reflected in the
  created backing volume or the operation must fail.

### TC-04 UI NFS Service Management Lifecycle

Goal: verify NFS export, ACL, access, and lifecycle operations are controlled
from the UI.

Preparation dependency:

- Requires `P-01` through `P-07`.
- Requires NFS client VM `TD-05`.

Steps:

1. Open the SharedFS detail page and select `File Service Management`.
2. Enable or confirm the NFS protocol from the UI. The protocol enable dialog
   must be vertical and must support both existing listen IP selection and new
   listen IP entry. If a new IP is entered, verify it belongs to the same CIDR
   as an available Storage Service NIC or that the runtime command rejects it.
3. Create an NFS export from the UI with path, permission, root squash, sync,
   secure, capacity, anonymous UID/GID, POSIX owner UID/GID, directory mode,
   and recursive permission settings. The dialog must be vertical.
4. Add or update an NFS CIDR ACL from the UI. The dialog must be vertical, list
   exports by export name only, and show the selected internal backing path and
   client-visible mount root as read-only context.
5. Verify the export and ACL appear in the UI list.
6. From the NFS client, mount the export and perform read/write checks
   according to the UI permission. Root Squash checks must distinguish NFS
   connection success from POSIX write permission failure: client root writes
   should fail on `root:root 0755`, and should pass only when anonymous
   UID/GID plus backing-directory owner/mode are configured for that behavior.
7. Stop/start/restart the SharedFS or service where available, then verify NFS
   state returns to expected UI status.
8. Use API/QGA/SystemVM logs only as evidence.

Expected:

- UI state matches `listStorageNfsExports` and `listStorageNfsAcls`.
- QGA applies the desired NFS state through `ablestack-storagectl`.
- NFS client behavior matches ACL and permission settings.
- Root Squash, All Squash, anonymous UID/GID, owner UID/GID, and directory mode
  are visible in UI/API config and are reflected in SystemVM `exportfs` and
  filesystem permissions.
- Closing any NFS action modal keeps the current `tab=nfs` view and refreshes
  only affected data instead of navigating to Details or masking the full tab.
- Lifecycle transitions are visible in the UI and do not leave stale export
  state.

Result:

| Run ID | Export | ACL | Client Result | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  | Not Run |  |  |

| TC04A-20260602-00 | NFS management UI/API/SystemVM deployment checkpoint | Root Squash POSIX controls, vertical action dialogs, listen IP selection/new-IP validation | Not Run | Ready For Retest | Java/API/server build passed with `mvn -pl api,server -am -DskipTests -Dcheckstyle.skip=true -Drat.skip=true install`; UI build passed with `NODE_OPTIONS=--openssl-legacy-provider npm --prefix ui run build`; `bash -n systemvm/debian/usr/local/bin/ablestack-storagectl` passed. 22.10 management server was hot-patched with the NFS API/server classes and UI dist, `mold` restarted, and `/client/api?command=listCapabilities` returns HTTP `401`. New cross-zone SYSTEM template `SystemVM Template Storage Service (KVM) 202606020028 SYSTEM` / `034be592-d4e7-4f56-ba36-2117140b45fb` registered from `systemvmtemplate-4.22.0.0-x86_64-kvm-202606020028.qcow2.bz2`, checksum `e8bc6d75d586b44cf5ad01e58dd6a7eb`, and reached `Download Complete` / `isready=true`. | This row records deployment readiness only. Functional TC-04A must be rerun from the UI with a fresh or updated Storage Service VM to validate vertical dialogs, current-tab partial refresh, new listen IP behavior, and Root Squash/POSIX read-write behavior. |
| TC04A-20260602-01 | NFS management UI/API/SystemVM action-modal and listen-IP deployment checkpoint | Centered vertical action dialogs, deduplicated existing listen IP list, new listen IP secondary-IP persistence, NFS TCP port opening, compact NFS ACL option sections | Not Run | Ready For Retest | UI build/deploy completed and 22.10 serves `js/app.70eae07d.js`; server/API build completed and 22.10 `mold` is `active` with unauthenticated `listCapabilities` retuning HTTP `401`; `bash -n systemvm/debian/usr/local/bin/ablestack-storagectl` passed. New cross-zone SYSTEM template `SystemVM Template Storage Service (KVM) 202606021554` / `b1f1bf88-f117-4640-98e7-fc5bf7b0741f` registered from `systemvmtemplate-4.22.0.0-x86_64-kvm-202606021554.qcow2.bz2`; size `583998431` bytes; MD5 `c136a2c16e6e2f75fa10cb2bbcb1b195`; SHA256 `2ac0ba69578a6c674af29bb211a8f4d63627e28e62196a02dd69c0bec3eb28de`; polling reached `Download Complete` / `isready=true` / `physicalsize=590135808`. Current TC-04A VM `i-2-458-VM` was also runtime-patched through QGA with the same `ablestack-storagectl`, and guest `grep open_tcp_firewall_port` plus `bash -n /usr/local/bin/ablestack-storagectl` retuned exit code `0`. | This row records deployment readiness only. Functional TC-04A must be rerun from the UI against SharedFS `c9a91907-ed7a-4fea-a69d-4a158adfab31` or a fresh VM from template `202606021554` to validate modal look-and-feel, new listen IP CIDR/conflict behavior, secondary IP persistence, firewall opening, NFS ACL option persistence, and client mount/read-write behavior. |
| TC04A-20260602-02 | NFS management UI/API/SystemVM deployment checkpoint after TC-04A findings | Last-refresh monitor cache, NFS export path defaulting, NFS ACL instance filtering, protocol endpoint visibility, WEB-INF-preserving UI deployment | Not Run | Ready For Retest | Server build passed with `mvn -pl server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true install`; UI build/deploy completed and 22.10 serves `js/app.f89e45ad.js`; management JAR backup paths are `/root/codex-backups/tc04a-nfs-fix-20260602181027` and `/root/codex-backups/tc04a-nfs-acl-filter-20260602182444`. During clean restart, a stale `ServerDaemon` exposed that a previous UI deployment had replaced `webapp` without `WEB-INF`; `WEB-INF` was restored from `/root/codex-backups/ui-storage-smb-tab-webapp-20260530-191519/WEB-INF`, backup path `/root/codex-backups/webapp-webinf-restore-20260602184115`, and `/client/api/?command=listCapabilities` retuned HTTP `401` with one active `ServerDaemon`. Current TC-04A VM `i-2-460-VM` was runtime-patched through QGA with the corrected `ablestack-storage-monitor`; `ablestack-storage-monitor.service` is active and cache files `health.json`, `inventory.json`, `capacity.json`, and `sessions.json` contain fresh `generatedAt` values. API evidence confirms `listStorageNfsAcls instanceid=9cc93e52-264c-455c-b968-41348a440c8a` returns only the current NFS CIDR ACL and excludes SMB local-user ACLs. New cross-zone SYSTEM template `SystemVM Template Storage Service (KVM) 202606021910` / `0bbcc8bf-5801-40b4-8b41-3ffb54bd0c27` registered from `systemvmtemplate-4.22.0.0-x86_64-kvm-202606021910.qcow2.bz2`; size `586692601` bytes; MD5 `c4d1a5ed3d89c8e9968a28dedabe0944`; SHA256 `14bcf4fac4e0b3063dc388c8d591cbda7e9db82fe58b8485b2d52675123c4384`; polling reached `Download Complete` / `isready=true` / `physicalsize=592883712`. | This row records deployment readiness only. Functional TC-04A must be rerun from the UI using a fresh Storage Service VM from template `202606021910` or the runtime-patched current VM to validate visible last-refresh time, secondary/listen IP endpoint display, export creation without null `path`, ACL dialog dark-mode readability, client mount/read-write behavior, and current-tab partial refresh. Future UI deployments must preserve `WEB-INF` and update only static UI assets. |
| TC04A-20260603-00 | NFS creation verification and listen-IP CIDR deployment checkpoint | Client-visible NFS runtime verification, monitor-cache polling, L2/ConfigDrive listen-IP CIDR fallback | Not Run | Ready For Retest | Server build passed with `mvn -pl server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true install`; UI build passed with `NODE_OPTIONS=--openssl-legacy-provider npm --prefix ui run build` and produced `js/app.4e96e451.js`. 22.10 was hot-patched by updating `StorageServiceManagerImpl.class` in both `cloud-server-4.22.0.0-SNAPSHOT.jar` and `cloudstack-4.22.0.0-SNAPSHOT.jar`; both deployed classes have SHA-256 `2232c53d775eda52a893d2d49ee2bda53fdb20c3df4aa7eb6b649baf88a3476c`. UI dist was extracted into `/usr/share/cloudstack-management/webapp` without replacing `WEB-INF`; `mold` restarted, one `ServerDaemon` is active, and unauthenticated `listCapabilities` returns HTTP `401`. Backup path is `/root/codex-backups/tc04a-cidr-runtime-verify-20260603-1254`. | This row records deployment readiness only. SystemVM template rebuild was not required because the existing `ablestack-storagectl` already validates guest NIC prefix, adds the listen IP to the guest NIC, and opens the protocol TCP port during desired-state application. Functional TC-04A must be rerun from the UI to confirm that initial NFS creation no longer shows the false runtime activation waning and that listen IPs in the L2/ConfigDrive/zone CIDR are accepted or rejected with the correct conflict/runtime message. |

### TC-05 UI SMB Service Management And AD Option

Goal: verify SMB share, ACL, local identity mode, and AD-domain option are
managed from the UI without exposing sensitive data.

Preparation dependency:

- Requires `P-01` through `P-07`.
- Requires SMB client VM `TD-06`.
- AD join validation requires an available test AD domain; otherwise mark the
  AD path `Not Run` and validate prerequisite messaging.

Steps:

1. Open `File Service Management`.
2. Enable or confirm the SMB protocol from the UI.
3. Create an SMB share with path, browse, guest, read-only, and capacity
   settings.
4. Configure local identity mode and an ACL from the UI.
5. If an AD test domain is available, run AD join from the UI and then verify
   domain status. If not available, verify the UI clearly shows the required
   inputs without persisting passwords.
6. Mount the SMB share from the SMB client and verify read/write behavior.
7. Verify password fields are cleared after submission and are not shown in
   review, logs, or result tables.

Expected:

- UI state matches SMB share, ACL, and identity-domain API state.
- SMB desired state is applied through QGA and `ablestack-storagectl`.
- SMB client behavior matches configured ACL and share flags.
- AD credentials are submitted only through sensitive async API payloads and
  are not persisted or displayed.

Result:

| Run ID | Share | Identity Mode | Client Result | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- |
|  |  | Local |  | Not Run |  |  |
|  |  | AD |  | Not Run |  |  |

### TC-06 UI iSCSI Service Management And CHAP Lifecycle

Goal: verify iSCSI target, LUN, initiator ACL, CHAP, and mutual CHAP
management from the UI.

Preparation dependency:

- Requires `P-01` through `P-07`.
- Requires iSCSI client VM `TD-07`.

Steps:

1. Open `File Service Management`.
2. Enable or confirm the iSCSI protocol from the UI.
3. Create an iSCSI target with target IQN, visible LUN, backing volume or LUN
   size, and one allowed initiator IQN ACL.
4. Run the no-auth case first and verify the target and ACL appear in the UI
   list.
5. From the iSCSI client, run discovery, login, device visibility check, basic
   read/write smoke test, and logout.
6. Update or recreate the ACL with one-way CHAP enabled. Submit CHAP usename
   and secret from the UI, then verify login succeeds only with the matching
   client CHAP configuration.
7. Update or recreate the ACL with mutual CHAP enabled. Submit mutual CHAP
   usename and secret from the UI, then verify bidirectional authentication.
8. Run one negative authentication case using an intentionally wrong CHAP
   secret and verify login fails while the UI remains healthy.
9. Delete or disable the target from the UI and verify discovery/login behavior
   changes accordingly.
10. Use API/DB/QGA evidence to confirm CHAP secrets are not persisted or shown
   in logs; only non-secret authentication mode and usenames may be recorded.

Expected:

- UI state matches iSCSI target and ACL API state.
- Target desired state is applied inside the SystemVM.
- Client discovery and login follow UI ACL and CHAP settings.
- CHAP and mutual CHAP secrets are runtime-only request values and are never
  displayed in the UI after submission.
- Delete/disable operations cleanly remove access without orphaning target
  state.

Result:

| Run ID | Auth Mode | Target IQN | Initiator IQN | Client Result | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- | --- |
|  | None |  |  |  | Not Run |  |  |
|  | CHAP |  |  |  | Not Run |  |  |
|  | Mutual CHAP |  |  |  | Not Run |  |  |
|  | Negative CHAP |  |  |  | Not Run |  |  |

### TC-07 UI NVMe-oF Kernel Service Management And DH-HMAC-CHAP

Goal: verify NVMe-oF kernel-mode preparation, subsystem, namespace, and host
ACL/authentication workflows from the UI.

Preparation dependency:

- Requires `P-01` through `P-07`.
- Requires NVMe-oF client VM `TD-08`.
- Requires NVMe-oF kernel packages and modules from `P-03` and `P-04`.

Steps:

1. Open `File Service Management`.
2. Enable or confirm `NVMe-oF` with `KERNEL_NVMET` selected.
3. Run or trigger prerequisite validation from the UI.
4. Create a subsystem, namespace or backing-volume mapping, and one allowed
   host NQN ACL from the UI without authentication.
5. From the NVMe-oF client, run discovery, connect, device visibility check,
   basic read/write smoke test, and disconnect.
6. Update or recreate the host ACL with DH-HMAC-CHAP host authentication and
   verify connect succeeds only with the matching host key.
7. Update or recreate the host ACL with controller authentication when exposed
   by the UI/API and verify the bidirectional case.
8. Run one negative authentication case with an intentionally wrong key and
   verify connect fails while the UI remains healthy.
9. Verify `File Service Status` reports endpoint, namespace, host ACL, session,
   and runtime state.
10. Select or attempt `SPDK` mode only as a gated/prerequisite case and verify
   no VM runtime controls are exposed or applied.

Expected:

- Kernel prerequisite validation returns success or a clear UI error.
- Subsystem and host ACL state matches backend API state.
- Client discovery/connect follows host NQN ACL and DH-HMAC-CHAP settings.
- DH-HMAC-CHAP keys are runtime-only request values and are never displayed in
  the UI after submission.
- UI does not expose VM runtime controls such as HugePage, NUMA, CPU pinning,
  memlock, SR-IOV, or PCI passthrough.
- SPDK remains a planned or prerequisite-required state until VM Runtime
  Capability support exists.

Result:

| Run ID | Auth Mode | Subsystem NQN | Host NQN | Client Result | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- | --- |
|  | None |  |  |  | Not Run |  |  |
|  | Host DH-HMAC-CHAP |  |  |  | Not Run |  |  |
|  | Controller DH-HMAC-CHAP |  |  |  | Not Run |  |  |
|  | Negative DH-HMAC-CHAP |  |  |  | Not Run |  |  |
|  | SPDK gated |  |  | N/A | Not Run |  |  |

### TC-08 UI Existing-Volume Import Lifecycle

Goal: verify operators can attach existing ABLESTACK volumes to file services
from the UI without accidental destructive actions.

Preparation dependency:

- Requires `P-01` through `P-08`.
- Requires prepared XFS and ext4 test volumes from `P-06`.

Steps:

1. Create or select an NFS export from the UI.
2. Open the existing-volume selector and verify only safe, unattached,
   `Ready` data volumes are selectable.
3. Attach the prepared XFS volume using `MOUNT_EXISTING` from the UI.
4. Verify the UI shows selected volume name/ID, size, detected filesystem,
   import mode, mount state, and resulting export state.
5. Mount from the NFS client and verify existing test files are preserved.
6. Create or select an SMB share from the UI.
7. Attach the prepared ext4 volume using `MOUNT_EXISTING` from the UI.
8. Mount from the SMB client and verify existing test files are preserved.
9. Run `INSPECT_ONLY` from the UI against a test volume and verify no export or
   share access is created.
10. Verify any `FORMAT_NEW` option requires explicit destructive confirmation.

Expected:

- Existing volume selection is done through a dropdown/list, not manual ID
  entry, when the UI can retrieve safe candidates.
- Existing files are preserved for `MOUNT_EXISTING`.
- `INSPECT_ONLY` records filesystem information without mounting or exposing
  client access.
- Destructive format/new paths are impossible to trigger accidentally.
- UI state matches API, QGA inspection result, and client visibility.

Result:

| Run ID | Protocol | Volume | Import Mode | Client Result | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
|  | NFS |  | MOUNT_EXISTING |  | Not Run |  |
|  | SMB |  | MOUNT_EXISTING |  | Not Run |  |
|  | NFS/SMB |  | INSPECT_ONLY | N/A | Not Run |  |

### TC-09 UI Capacity Expansion Lifecycle

Goal: verify file service capacity expansion is performed from the UI and is
reflected in ABLESTACK volume, SystemVM filesystem, protocol service, and
client views.

Preparation dependency:

- Requires `P-01` through `P-08`.
- Requires a dedicated resize test volume from `P-06`.

Steps:

1. Select an NFS export or SMB share in `File Service Management`.
2. Verify capacity inputs use a number plus IEC unit selector (`B`, `MiB`,
   `GiB`, `TiB`) and are converted to bytes in API evidence.
3. Run share-capacity-limit resize from the UI when supported.
4. Verify backing data disk size does not change and the service quota state
   changes.
5. Run backing data disk resize from the UI with a larger target size.
6. Verify ABLESTACK volume size changes.
7. Verify QGA filesystem grow succeeds inside the SystemVM.
8. Verify NFS/SMB client sees the expanded usable capacity.
9. Verify `File Service Status` inventory reflects the new size/quota.

Expected:

- UI clearly distinguishes file share capacity limit and backing data disk
  resize.
- ABLESTACK volume resize, guest filesystem resize, and service-state refresh
  happen in the correct order.
- Failure in any phase is visible in the UI and does not hide partial state.

Result:

| Run ID | Protocol | Old Size/Quota | New Size/Quota | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- |
|  | NFS |  |  | Not Run |  |  |
|  | SMB |  |  | Not Run |  |  |

### TC-10 UI Failure, Retry, And Rollback Visibility

Goal: verify operators can understand, retry, and recover from lifecycle or
service-management failures from the UI.

Preparation dependency:

- Requires `P-01` through `P-08`.
- Use isolated test resources only.

Steps:

1. Trigger a controlled validation error from the UI, such as missing required
   service-specific input.
2. Trigger a controlled backend failure where safe, such as invalid path,
   invalid ACL principal, or blocked client ACL.
3. Verify the UI displays the failing phase and actionable error text.
4. Fix the input or backend condition from the UI.
5. Retry the operation from the UI.
6. Verify the final state is consistent in the UI, API, DB, QGA logs, and
   client behavior.
7. Verify rollback artifacts and cleanup steps are recorded in this document
   when a failure leaves stateful resources behind.

Expected:

- UI validation catches client-side errors before API submission.
- Backend failures surface as operator-visible async job errors.
- Retry can return the resource to `Ready` without manual DB repair.
- No failed workflow stores secrets or leaves unknown attached volumes.

Result:

| Run ID | Workflow | Failure Trigger | Retry Result | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  | Not Run |  |  |

### TC-11 UI Status And Monitoring Tabs

Goal: verify service observation is separated from service configuration and
matches backend runtime state.

Preparation dependency:

- Requires `P-01` through `P-08`.
- Requires at least one configured protocol service.

Steps:

1. Open a SharedFS detail page.
2. Verify state-changing controls are under `File Service Management`.
3. Verify health, endpoints, inventory, sessions, object counts, and SMB domain
   status are under `File Service Status`.
4. Create active NFS/SMB/iSCSI/NVMe-oF sessions where available.
5. Refresh `File Service Status`.
6. Compare UI values with `listStorageServiceHealth`,
   `listStorageServiceInventory`, `listStorageServiceSessions`, and SystemVM
   logs.
7. For iSCSI and NVMe-oF authenticated sessions, verify the status view shows
   authentication mode or enabled state only, never CHAP secrets or
   DH-HMAC-CHAP keys.

Expected:

- Management and monitoring are separate tabs.
- Status tab does not expose destructive or configuration-changing controls.
- UI health, inventory, sessions, endpoint, and SMB domain-state values match
  backend evidence.
- Authentication status is observable without revealing secret values.
- Empty, loading, and error states are readable in normal and dark modes.

Result:

| Run ID | Tab | Runtime State | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- |
|  | File Service Management |  | Not Run |  |  |
|  | File Service Status |  | Not Run |  |  |

### TC-12 UI Regression And Compatibility

Goal: verify the expanded UI keeps the existing SharedFS behavior compatible
while adding the Storage Service lifecycle.

Preparation dependency:

- Requires deployed UI bundle `TD-10`.
- Full compatibility validation requires `P-01` through `P-08`.

Steps:

1. Create a default NFS-only SharedFS from the UI using the smallest valid
   configuration.
2. Verify existing list, detail, start, stop, restart, update, and delete or
   recover workflows remain usable according to current SharedFS behavior.
3. Verify the expanded creation dialog does not break accounts/projects,
   zones, networks, disk offerings, service offerings, custom size, or custom
   IOPS fields.
4. Verify Korean UI text is used wherever translations exist.
5. Verify protocol names and technical identifiers remain untranslated where
   appropriate.
6. Verify no VM runtime capability controls are exposed for SPDK.
7. Repeat smoke checks in normal and dark modes.

Expected:

- Backward-compatible NFS-only creation remains usable.
- Existing SharedFS lifecycle actions still work.
- Expanded Storage Service controls are additive and scoped to the SharedFS
  workflow.
- UI uses existing Vue, Ant Design Vue, i18n, and async job polling patterns.
- No hard-coded light-only surface or unreadable dark-mode text remains.

Result:

| Run ID | Mode | Workflow | Status | Evidence | Defect/Improvement |
| --- | --- | --- | --- | --- | --- |
|  | Normal | NFS-only compatibility | Not Run |  |  |
|  | Dark | NFS-only compatibility | Not Run |  |  |

## Regression Checklist

Run this after every fix.

| Check | Status | Notes |
| --- | --- | --- |
| Existing SharedFS APIs still follow upstream ABLESTACK behavior | Not Run |  |
| Existing Storage Service APIs still compile and register | Pass | Static build only |
| NFS desired-state apply still works after attach/resize changes | Not Run |  |
| SMB desired-state apply still works after attach/resize changes | Not Run |  |
| iSCSI target apply still works | Not Run |  |
| NVMe-oF kernel apply skips SPDK subsystems | Not Run |  |
| SPDK does not configure VM-level resources | Not Run |  |
| UI normal mode remains usable | Not Run |  |
| UI dark mode remains usable | Not Run |  |
| UI create dialog still starts from the existing SharedFS list | Not Run |  |
| UI management tab performs state-changing service workflows | Not Run |  |
| UI status tab remains monitoring-only | Not Run |  |
| UI normal and dark mode alerts, labels, disabled controls, radio/checkbox text, and table text are readable | Not Run |  |
| UI modals fit the viewport and fixed footers do not overlap scrollable sections | Not Run |  |
| UI review panels and detail cards handle long names, IDs, IQNs, and NQNs without unreadable wrapping | Not Run |  |
| Korean i18n is present where translations exist and no raw i18n keys or mojibake appear | Not Run |  |
| Sensitive fields are cleared after close/submit and are not shown in UI review/status surfaces | Not Run |  |

## Defect And Improvement Log

Use this table for every issue found during validation.

| ID | Date/Time | Run ID | Severity | Area | Symptom | Root Cause | Fix Commit | Retest |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TC01-UI-001 | 2026-05-27 17:14:57 +09:00 | TC01-20260527-01 | Medium | UI / Dark mode / SharedFS create dialog | `SMB 인증` section radio labels for `로컬 계정` and `Active Directory 도메인` were too dark in dark mode and were difficult to read | The first inherited-color fix was present in the CSS bundle but still inherited an unsuitable dark value at runtime; the SMB identity radio group now has a dedicated class and explicit `body.dark-mode` global text-color rules with `!important` for normal and disabled radio labels | Fixed in working tree and deployed to 22.x: `js/app.6da000ae.js` is served from `/client/`, and `css/chunk-07935557.f0129b5c.css` contains the explicit dark-mode SMB radio label rules | Pass after operator retest |
| TC02-UI-001 | 2026-05-28 09:00:00 +09:00 | TC02-20260528-01 | Medium | UI / SharedFS create dialog / Naming | NFS and SMB sections use the generic label `이름`, making it unclear whether the field is the representative SharedFS name, NFS export name, or SMB share name | The UI has separate `form.name`, `form.nfsname`, and `form.smbname`, but labels and review text do not explain their scope; backend falls back to `<SharedFS name>-nfs` and `<SharedFS name>-smb` when service-specific names are omitted | Fixed in working tree and deployed to 22.x with `js/app.711d93be.js`; labels now distinguish SharedFS name, NFS export name, and SMB share name, and the review panel shows effective generated names | Pass after operator dialog retest |
| TC02-UI-002 | 2026-05-28 09:00:00 +09:00 | TC02-20260528-01 | High | UI / SharedFS create dialog / iSCSI | iSCSI creation UI does not expose LUN or permission and the initiator label does not clearly mean allowed initiator ACL | `CreateSharedFS.vue` sends hidden defaults `lun=0` and `permission=READ_WRITE` after target creation, while the UI only shows target IQN and initiator IQN | Fixed in working tree and deployed to 22.x with `js/app.711d93be.js`; LUN and ACL permission are visible inputs, and initiator label now states allowed initiator IQN | Pass after operator dialog retest |
| TC02-UI-003 | 2026-05-28 09:00:00 +09:00 | TC02-20260528-01 | Medium | UI / SharedFS create dialog / NVMe-oF | NVMe-oF creation UI shows engine, subsystem NQN, and host NQN, but does not make transport, port, namespace/backing-volume intent, or host ACL semantics visible | `CreateSharedFS.vue` sends hidden `transport=tcp`, validates VM prerequisites, creates a subsystem, and optionally creates a host ACL, but the UI labels do not expose all operator-relevant defaults | Fixed in working tree and deployed to 22.x with `js/app.711d93be.js`; transport and protocol port are visible, host NQN is labeled as an allowed host ACL, and namespace/backing-volume handling is explained as post-subsystem volume attachment | Pass after operator dialog retest |
| TC02-UI-004 | 2026-05-28 09:00:00 +09:00 | TC02-20260528-01 | Medium | UI / SharedFS create dialog / Review panel | The review panel updates for selected services and common fields, but does not clearly show service-specific names, target/subsystem identifiers, or the distinction between representative SharedFS name and protocol object names | The review panel currently lists only a small common summary and SMB identity mode, not per-protocol object identifiers | Fixed in working tree and deployed to 22.x with `js/app.711d93be.js`; review panel now renders per-protocol summaries for NFS, SMB, iSCSI, and NVMe-oF | Pass after operator dialog retest |
| TC02-UI-005 | 2026-05-28 10:25:00 +09:00 | TC02-20260528-01 | Medium | UI / SharedFS create dialog / Volume and capacity | Existing volume import required manual volume ID entry, and the quota bytes label did not explain what capacity was being limited | Existing volume selection did not query unattached data volumes, and `quotabytes` was exposed as a raw API field rather than as a file-share capacity limit | Fixed in working tree and deployed to 22.x; existing volume now uses a dropdown of Ready unattached DATADISK volumes, and quota wording/help text explains that the byte value limits NFS/SMB share usable capacity | Pass after operator dialog retest |
| TC03-UI-001 | 2026-05-28 15:20:00 +09:00 | TC03-20260528-01 | High | UI/API / SharedFS create lifecycle / Initial NFS desired state | UI reported Storage Service creation success for NFS-only creation, but no initial NFS export or ACL exists and the SystemVM NFS service remains inactive | Two defects converged: Storage Service desired-state rows did not consistently set `created`, so persistence could fail with `Field 'created' doesn't have a default value`; and the create dialog treated Storage Service API submission as complete without polling dependent async jobs and verifying selected objects | Fix applied in working tree and deployed to 22.10: VO/schema timestamp defaults, async polling for initial setup APIs, explicit missing-API errors, post-setup verification for selected NFS/SMB/iSCSI/NVMe-oF objects, Java build success, UI bundle `js/app.aa40c45a.js`, patched class hashes verified in the running management JAR, and `/client/api` restored to expected HTTP `401` unauthenticated behavior | Pending Retest |
| TC03-UI-002 | 2026-05-28 18:30:00 +09:00 | TC03-20260528-02 | High | UI/API/QGA / SharedFS create lifecycle / Initial NFS export and ACL reconciliation | TC-03-01 retest creates a Ready SharedFS and Running Storage Service VM, but the selected initial NFS export `nfs01` at `/export/nfs01` is not active in SystemVM exportfs state and has no ACL; meanwhile the legacy `/export` root is still exported | The compatibility SharedFS mirror or bootstrap path still publishes `/export`, which is the old SharedFS root and must no longer be used as a Storage Service share. Expanded Storage Service must publish only explicit child directories such as `/export/<share-name>` or native managed paths under `/srv/ablestack-storage/...`. Additionally, the UI-selected initial NFS export row is persisted separately but not reconciled into QGA desired state with its ACL, and `listStorageNfsAcls exportid=<export-uuid>` returns HTTP `530` because the export entity type is missing entity-reference handling for that list filter. | Not fixed | Open |
| TC03-UI-003 | 2026-05-28 23:50:00 +09:00 | TC03-20260528-03 / TC03-20260529-01 / TC03-20260529-02 | High | UI/API/QGA / Client-visible storage root name | New template retest correctly publishes only the selected child export and no longer publishes the deprecated `/export` root, but the client-visible NFS mount path was `/export/nfs01` instead of the intended root-level export name/path such as `/nfs01`. The same principle applies across protocols: NFS export name, SMB share name, iSCSI target IQN, and NVMe-oF subsystem NQN are the extenally visible root identifiers. | The previous desired-state model stored one file `path` and `ablestack-storagectl nfs export apply` wrote that path directly to `/etc/exports.d/ablestack-<uuid>.exports`, exposing the internal filesystem path as the external NFS mount path. The fix separates internal backing path from client-visible export name using a controlled root-level bind mount alias, renders only the alias into NFS exports, updates UI labels/help/connection examples to distinguish internal backing path from client root name, and maps operator-entered all-CIDR ACLs `0.0.0.0/0` / `::/0` to Linux exports wildcard `*`. | Fixed and fresh-template retested | Pass |
| TC03-UI-004 | 2026-05-29 11:18:00 +09:00 | TC03-20260529-02 | Medium | API/UI / SharedFS details and overview / Domain display | SharedFS detail and overview surfaces displayed the ROOT domain as `/` instead of `ROOT` | The SharedFS response builder overwrote the `domain` field with the domain path and the deployed runtime also had a stale root-domain path shape. The fix writes `domainpath` separately and normalizes root-domain path-style values so API responses return `domain=ROOT` and `domainpath=/`. Both runtime JAR locations that contained the DAO class were patched. | Fixed in working tree and deployed to 22.10; `listSharedFileSystems` now returns `domain: ROOT`, `domainpath: /` for existing SharedFS rows | Pass |
| TC03-UI-005 | 2026-05-30 15:50:00 +09:00 | TC03B-20260530-01 | High | UI/API / SharedFS create lifecycle / Initial SMB local setup | SMB-only creation for `tc03b-smb-20260530` produced a Ready SharedFS and Running Storage Service VM, and SMB protocol enablement succeeded, but no SMB share or ACL was created. Runtime inventory reported `smbShares: []`. The create dialog also had no local SMB user/password inputs, so the intended local identity path could not be completed from the UI. | The create dialog treated SMB local mode as a selectable authentication mode but did not collect the initial local account credential or permission needed for `LOCAL_USER` ACL creation. Dependent post-create setup also relied on mutable modal form state after modal close and password cleanup, making SMB share/ACL orchestration fragile after the long SharedFS VM creation job. | Fixed in working tree and deployed to 22.x as `js/app.965b2423.js`: the SMB local section now collects local user, password confirmation, and permission; submit takes an immutable setup snapshot; SMB follow-up runs protocol enablement, share creation, local-user ACL creation with one-time password payload, and share/ACL verification before final success. Runtime SystemVM code already supports `LOCAL_USER` SMB user creation through `ablestack-storagectl`, so no SystemVM script/template rebuild is required for this fix. | Pending Retest |
| TC03-UI-006 | 2026-05-30 16:15:00 +09:00 | TC03B-20260530-02 | High | API / SMB ACL UUID parameter conversion | SMB-only retest for `tc03b-smb-20260530` reached a better partial state: SharedFS `f80918ea-72a1-4860-84f1-fa11de85c11d` is `Ready`, VM `de1f3fa4-7a1f-418a-8322-f9cbf77c4c58` is `Running`, Storage Service instance `e47b227f-06fb-479f-8363-0c94b10fa6c0` is `Running`, and SMB share `smb01` / `6ae16f2c-efd1-4f9b-b46d-9fd0cfd8e404` was created and appears in SystemVM runtime inventory. However, `createStorageSmbAcl` and `listStorageSmbAcls shareid=<share-id>` failed with HTTP `530`; the UI reported request failure after share creation. | `StorageSmbShareResponse` was used as the `entityType` for `shareid`, but the response class did not declare `@EntityReference`. The API parameter converter attempted to read `EntityReference.value()` from a missing annotation and threw a null pointer exception before the ACL command could execute. | Fixed in working tree and deployed to 22.x: `StorageSmbShareResponse` now declares `@EntityReference(value = StorageFileShare.class)`. `mvn -pl api -am -DskipTests -Dcheckstyle.skip=true install` passed; patched class was applied to `cloud-api-4.22.0.0-SNAPSHOT.jar` and `cloudstack-4.22.0.0-SNAPSHOT.jar`; backups are under `/root/codex-backups/storage-smb-entityref-20260530-1610`; `mold.service` restarted with new PID; unauthenticated API returns HTTP `401`; signed `listStorageSmbShares id=6ae16f2c-efd1-4f9b-b46d-9fd0cfd8e404` returns HTTP `200`; signed `listStorageSmbAcls shareid=6ae16f2c-efd1-4f9b-b46d-9fd0cfd8e404` now returns HTTP `200` with an empty response instead of HTTP `530`. SystemVM template rebuild was not required. | Fixed / Pending UI Retest |
| TC03-UI-007 | 2026-05-30 16:25:00 +09:00 | TC03B-20260530-03 | High | UI/API / SharedFS create lifecycle / SMB backing volume selection | SMB Local creation after the ACL fix successfully creates SharedFS, Storage Service instance, SMB share, and SMB local ACL, but the SMB share metadata points to a stale backing volume from another SharedFS VM. Latest SharedFS `4a295948-8bd6-44b6-a60f-8f430d7a0e98` uses volume `sharedfs-DATA-442` / `72d9ec13-0718-4245-963f-d9bf79cee986`, while SMB share `cbd542ea-7169-4d08-9825-879c73e23548` references `sharedfs-DATA-24` / `ae6935e3-4ea8-49c6-aeb8-78807a62cc68`, which belongs to older VM `sharedfs-SFS-19c0785efd7`. | `CreateSharedFS.vue` derives the initial protocol backing volume through `initialBackingVolumeId(sharedfs, snapshot)`, which falls back to `this.resource?.volumeid` when the async create result does not include a volume ID. In list/create modal context, `this.resource` can be stale or unrelated, so the UI can submit an old volume ID to `createStorageSmbShare`. The backend also accepts a share volume ID without validating that it is the current SharedFS/Storage Service VM backing volume or an explicitly selected existing volume attached to the same instance. | Fixed in working tree and deployed to 22.x on 2026-05-30 16:41 +09:00. UI now reloads the created SharedFS by ID after `createSharedFileSystem`, removes the stale `this.resource?.volumeid` fallback, and fails setup if the current backing volume cannot be resolved. Backend now rejects NFS, SMB, iSCSI, and NVMe-oF volume-backed create/update requests when the supplied volume is already attached to a different VM. Server build passed, UI build produced `js/app.78310ee4.js`, and deployment backup is `/root/codex-backups/storage-backing-volume-20260530-1641`. | Fixed / Pending TC-03B Retest |
| TC03-UI-008 | 2026-05-30 22:30:00 +09:00 | TC03B-20260530-04 | Medium | UI / SMB tab dark-mode layout and table density | Chrome validation for `http://10.10.22.10:8080/client/#/sharedfs/c15fb904-3a85-4122-bd17-c02e23ef699e?tab=smb` using deployed bundle `js/app.a2fb4c82.js` confirmed that SMB data is present and readable in dark mode: endpoint guidance, status summary, SMB share `smb01`, ACL `admin`, authentication, backing volume, and empty-session NoData state all render. However, the resource info card plus left-positioned detail tabs leave a large vertical blank band between the resource summary and SMB content, which makes the page look broken on a 1669 px wide viewport. Some summary values such as endpoint and daemon state are truncated without an obvious hover affordance, and the wide tables rely on horizontal scrolling but the visible column set/order needs further tuning for operator scanning. | The service detail view still uses the existing resource-detail two-column layout with Ant Design left tabs. The Storage Service protocol tab content is dense and table-oriented, but it is constrained by the remaining width after the resource summary card and tab rail. This produces a visually awkward unused column and early table truncation in dark mode even though the underlying data is correct. | Proposed fix: convert the protocol service tabs into a full-width detail content mode after the resource summary card, or reduce/relocate the tab rail so service tabs can use the full available content width. Keep dark-mode card/table styles from the create modal standard, add tooltips for truncated status values, and tune table fixed columns so name/status/action remain visible while detail columns scroll inside the table. | Open |
| TC03-UI-009 | 2026-05-31 01:05:00 +09:00 | TC03C-20260531-01 | High | API/QGA/SystemVM/UI / iSCSI LUN backing | iSCSI target was created and TCP 3260 listened, but UI did not show LUN size. SystemVM target state used a 510656512-byte file under `/var/lib/ablestack-storage/.ablestack-storage/iscsi/...img` instead of the 50 GiB attached data volume mounted at `/export`. Runtime inventory lacked volume UUID/name/size, runtime backing path, and effective LUN size. | The desired-state payload did not include enough ABLESTACK volume metadata, and SystemVM disk matching accepted broad numeric tokens before falling back to root filesystem storage. The monitor cache retuned the desired JSON without enriching it from target runtime state. UI table columns also consumed only `lunsizebytes` and did not fall back to effective/runtime volume size. | Fixed in working tree and deployed to 22.x: API response and QGA payload now expose volume name, volume size, configured LUN size, effective LUN size, and backing path; SystemVM storagectl resolves disks only by safe volume UUID/name/serial tokens and fails instead of root fallback; target reapply recreates backstores; monitor inventory enriches runtime backing path/type/size; UI iSCSI tab shows configured/effective LUN size and runtime backing path, and backing-volume lookup includes iSCSI/NVMe targets. Current TC03C VM was live-patched and re-applied: targetcli now points to `/export/.ablestack-storage/iscsi/f1d5043c-2206-4062-8df4-586599cb70d7.img`, file size is `53687091200`, runtime inventory reports `state=Ready`, `backstoreType=fileio`, and `effectiveSizeBytes=53687091200`. UI bundle `js/app.15b30c22.js` is served. New template `SystemVM Template Storage Service (KVM) 202605310233` / `ce15f78c-a1fa-4a1f-91c9-4eb72af6036f` is registered and ready with checksum `c2810963a555359d9ed4138f219d25ea`. Final browser retest confirmed the iSCSI tab displays target, backing volume, configured/effective LUN size, runtime backing path, and `Ready` state in dark mode without raw i18n keys. | Pass |
| TC03-UI-010 | 2026-05-31 14:25:00 +09:00 | TC03D-20260531-01 / TC03D-20260531-02 | High | API/QGA/SystemVM/UI / NVMe-oF idempotent apply and host ACL | NVMe-oF-only creation reached a Ready SharedFS, Running Storage Service VM, Ready subsystem, Ready namespace, visible backing volume, and listening TCP 4420, but Host ACL creation failed with `PermissionError: [Erno 13] Permission denied`. The Host ACL API list was empty and local WSL `nvme connect` failed after successful discovery. | The SystemVM `ablestack-storagectl nvmeof subsystem apply` command rewrote configfs port address attributes and namespace device attributes on every apply. This worked for the first subsystem apply, but a later Host ACL apply ran after the port/subsystem link was active, and the kernel rejected rewriting active configfs port attributes. The same path also accepted namespace rows without resolving a concrete kernel `device_path`, so API namespace state could be `Ready` while configfs namespace state was incomplete. | Fixed and deployed to 22.x on 2026-05-31 15:36 +09:00: NVMe-oF apply now uses reconcile semantics, writes port attributes only before subsystem links are active, adds Host ACL symlinks independently and idempotently, unlinks stale desired-state Host ACL symlinks, updates namespace device paths only when needed, creates managed file-backed namespace images below the attached data disk and exposes them through loop devices when the backing volume is mounted, and stores desired-state only after successful apply. The create Host ACL flow changes the ACL to `Ready` only after QGA apply succeeds. Runtime hot-patch verification passed with local WSL `nvme discover` and `nvme connect`; template `SystemVM Template Storage Service (KVM) 202605311453` is registered and ready. | Fixed / Pending fresh-template TC-03D retest |
| TC03-UI-011 | 2026-05-31 15:58:00 +09:00 | TC03D-20260531-03 / TC03D-20260531-04 | Medium | API/QGA/SystemVM / NVMe-oF desired-state status consistency | Fresh-template NVMe-oF creation, API state, configfs state, monitor cache, and client connection all passed, but `/etc/ablestack-storage/nvmeof-subsystems.json` inside the SystemVM stored the Host ACL object with `state=Creating` after the API row had already reached `Ready`. | `createStorageNvmeOfHostAcl` persisted the rule as `Creating`, applied desired state through QGA, and only then updated the API row to `Ready`. Because there was no second desired-state write after the final DB state update, the SystemVM state file preserved the pre-apply lifecycle state even though the configfs symlink was valid. | Fixed in the management server: Host ACL create/update still stages the DB row as `Creating`/`Updating`, but the QGA payload overrides the affected Host ACL to the final runtime state (`Ready` when a System VM exists, `Allocated` before VM mapping). On QGA failure, the API row is persisted as `Error` and the async job returns the failure. Reapplied the existing ACL after deployment and verified the SystemVM state file now stores `Ready`. | Fixed / Verified |
| TC03-UI-012 | 2026-05-31 16:57:00 +09:00 | TC03D-20260531-05 | High | SystemVM / NVMe-oF DH-HMAC-CHAP enforcement | UI-created authenticated NVMe-oF service reached `Ready`, API ACL config reported `dhChapEnabled=true` and `dhChapCtrlEnabled=true`, but WSL connected successfully without CHAP secrets. Authenticated connect with supplied secrets failed with `failed to write to nvme-fabrics device`. | The current SystemVM kernel/configfs surface under `/sys/kernel/config/nvmet/hosts/<hostnqn>` does not expose `dhchap_key` or `dhchap_ctrl_key` attributes. `ablestack-storagectl` used best-effort `safe_write_if_exists`, so it silently skipped CHAP key application while still reporting the Host ACL as `Ready`. | Implemented in `ablestack-storagectl`: requested DH-HMAC-CHAP now requires configfs `dhchap_key`/`dhchap_ctrl_key` attributes and a runtime secret value; missing capability or missing runtime secret fails QGA apply instead of silently reporting `Ready`. New clean SystemVM template `SystemVM Template Storage Service (KVM) 202605311826` is registered and ready for fresh retest. | Fixed / Pending Retest |
| TC03-UI-013 | 2026-05-31 16:57:00 +09:00 | TC03D-20260531-05 | Critical | API/QGA/SystemVM monitor / secret exposure | `listStorageServiceInventory instanceid=<tc03d02-instance>` retuned monitor inventory JSON containing DH-HMAC-CHAP secret values under Host ACL `secrets`. The SystemVM desired-state file also retains secret keys after apply. | The QGA payload secrets were merged into the desired-state model and the monitor inventory retuned that model without sanitizing runtime-only fields. This violated the design rule that DH-HMAC-CHAP secrets may be passed in the one-time QGA payload only and must not be retuned by APIs, UI, state files, or monitor cache. | Implemented storagectl state-file redaction, monitor cache redaction, and management API runtime result redaction. Clean deployment smoke check against the existing service confirmed `listStorageServiceInventory` returns no password, secret, or DH-HMAC-CHAP key patterns. Fresh-template retest must still confirm the SystemVM state file is sanitized at source after apply. | Fixed / Pending Retest |
| TC03-UI-014 | 2026-05-31 16:57:00 +09:00 | TC03D-20260531-05 | Medium | UI / NVMe-oF authenticated ACL display and guidance | In the NVMe-oF tab, the access table shows `DH-HMAC-CHAP 사용` as `-` even though API config has host and controller authentication enabled. The connection guidance shows only no-auth `nvme discover/connect` commands. | The UI table was not deriving the authentication label from both ACL and config JSON key shapes, and the connection guidance was static. | Implemented UI authentication-mode labels: `사용 안 함`, `호스트 인증`, and `상호 인증`. If an authenticated Host ACL exists, the connection card adds guidance that operator-provided DH-HMAC-CHAP secrets must be supplied to the `nvme` command without rendering secret values. | Fixed / Pending Retest |
| TC03-UI-015 | 2026-05-31 17:03:00 +09:00 | TC03D-20260531-05 | Medium | API/SystemVM monitor/UI / NVMe-oF session attribution | The NVMe-oF session table displayed active `ESTAB` sessions and service endpoint `10.10.254.176:4420`, but connection time and connected subsystem NQN were both shown as `-`. | The session collector derived NVMe-oF sessions from TCP state only. That was enough to count live connections, but it did not expose an observed/first-seen timestamp or correlate the session to the subsystem when only one active subsystem exists. | Fixed in `ablestack-storagectl`: NVMe-oF TCP sessions now keep `connectedAt`/`lastSeen` in the monitor session-state cache and are enriched from `/etc/ablestack-storage/nvmeof-subsystems.json` with `resourceId`, `resourceName`, and `subsystemNqn` when the subsystem can be matched by request resource or there is exactly one active subsystem. Runtime patch was applied to TC-03D-02 VM `i-2-452-VM`; WSL `nvme connect` created `/dev/nvme0n1`, direct SystemVM collection retuned 17 `NVME_OF` sessions with `connectedAt=2026-05-31T13:47:27Z` and `resourceName=nqn.2026-05.local.storage:tc03d02`, and the UI session table displayed both connection time and subsystem NQN. Multi-subsystem attribution remains best-effort until kernel/controller-level correlation is added. | Fixed / Verified |
| TC03-UI-016 | 2026-06-01 01:05:00 +09:00 | TC03D-20260601-01 | High | API/QGA/SystemVM/UI / NVMe-oF DH-HMAC-CHAP capability gating | When DH-HMAC-CHAP was selected in a fresh NVMe-oF create workflow, QGA failed with `missing /sys/kernel/config/nvmet/hosts/<hostnqn>/dhchap_key`, revealing that the current SystemVM kernel/configfs does not support NVMe-oF DH-HMAC-CHAP even though the UI allowed the option to be selected. | The previous implementation treated DH-HMAC-CHAP as a protocol feature without first exposing per-SystemVM kernel/configfs capabilities. The kernel target and Host NQN ACL are available, but Debian kernel `6.1.0-49-amd64` in the current SystemVM template does not expose `dhchap_key` or `dhchap_ctrl_key`, so authenticated NVMe-oF cannot be configured in this environment. | Fixed in working tree and deployed to 22.x: `ablestack-storagectl health` and `inventory` now report `capabilities.nvmeof.dhChapSupported` and `dhChapCtrlSupported`; the create dialog disables NVMe-oF CHAP switches for the current template baseline and shows an unsupported waning; the NVMe-oF service tab shows `DH-HMAC-CHAP 지원: 미지원` with the kernel/configfs reason and disables Host ACL CHAP controls; the UI build `js/app.49017f71.js` is deployed; the current SystemVM was runtime-patched and new template `SystemVM Template Storage Service (KVM) 202606010104` / `ed624184-80f5-4b87-8fc3-a655fc1450a3` is registered and ready. | Fixed / Ready For Retest |

| TC03-UI-017 | 2026-06-01 07:30:00 +09:00 | TC03D-20260601-02 | High | Test environment / SystemVM template selection | A fresh SharedFS created for final TC-03D regression selected template `sstest0` instead of the expected latest Storage Service template. | SharedFS VM deployment uses `templateDao.findSystemVMReadyTemplate()`, which only considers ready cross-zone `SYSTEM` templates. The `SystemVM Template Storage Service (KVM) 202606010104` registration was zone-scoped `USER`, so it was not eligible even though it was downloaded and ready. | Re-registered the same `202606010104` artifact as cross-zone `SYSTEM` template `SystemVM Template Storage Service (KVM) 202606010104 SYSTEM` / `5d581192-0452-4adf-815a-0ac8b6aff984`. The wrong-template SharedFS was destroyed, and the final retest VM confirmed the new template was selected. Future P-03 registration must use `zoneid=-1` and `templatetype=SYSTEM`; a zone-scoped user template is insufficient for SharedFS/SystemVM lifecycle tests. | Fixed / Verified |
| TC04A-UI-001 | 2026-06-03 13:35:00 +09:00 | TC04A-20260603-01 | High | API/UI / NFS protocol enablement and action refresh | TC-04A retest no longer failed during initial NFS creation, but protocol activation with new listen IPs such as `10.10.254.201` and `10.10.22.201` was rejected before the SystemVM could validate the actual guest NIC prefix. NFS export/ACL actions also retuned the operator to the detail tab and visually refreshed the full tab area. | The management-server preflight validator depended too heavily on Cloud DB NIC/netmask/CIDR evidence. In L2/ConfigDrive style environments that metadata can be incomplete even when the guest NIC can validate and add the secondary IP. UI action refresh also did not preserve the current protocol tab and wide-layout query state across async job completion. | Fixed in working tree and deployed to 22.10: protocol enablement now resolves a candidate NIC, defers final CIDR validation to `ablestack-storagectl` when DB evidence is insufficient and the Storage Service VM has a single NIC, applies desired state through QGA first, registers the Cloud secondary IP only after successful guest apply, and rolls back protocol desired state on failure. UI action refresh snapshots/restores the active protocol tab and wide-layout state with a non-navigating history update. Server build passed, UI build produced `js/app.ad82b019.js`, both runtime JARs contain `StorageServiceManagerImpl.class` hash `fc9ddf55dd15e2710e8c5ebd731c7a9de94f1761e0cd36174ab240915df64195`, backup is `/root/codex-backups/tc04a-listenip-tabfix-20260603-133500`, `mold.service` is active, one `ServerDaemon` process is running, and unauthenticated `listCapabilities` returns HTTP `401`. No SystemVM template rebuild was required because no SystemVM script or package changed in this fix. | Fixed / Ready For Retest |
| TC04A-UI-002 | 2026-06-03 23:45:00 +09:00 | TC04A-20260603-02 | Medium | API/UI / NFS export endpoint binding and protocol-tab refresh | TC-04A retest found that the NFS export create dialog could not choose which configured endpoints expose the export, the NFS table showed only an inferred service-wide endpoint, connection guidance did not handle multiple endpoints clearly, and post-action refresh still risked parent resource reload and Details-tab fallback. | The NFS export API stored only the internal backing path and protocol object options. Endpoint display was inferred from global service endpoints, so the UI could not distinguish all-endpoint exposure from selected-endpoint exposure. The current SystemVM NFS design uses one kernel nfsd/exportfs surface, so strong per-endpoint export isolation is not available without a later firewall/netns/nfsd policy design; however, the operator intent still must be persisted and displayed. | Fixed in working tree and deployed to 22.10: `createStorageNfsExport` and `updateStorageNfsExport` accept `listenips`, NFS export `config_json` stores normalized `listenIps`, `listStorageNfsExports` returns `listenips`, and the NFS tab create dialog now supports all endpoints or selected endpoint IPs. The NFS export table now renders endpoint-specific `IP:port` and client mount roots, and connection examples use the neutral `<endpoint-ip>:/<export-name>` form when multiple endpoints exist. Async job polling now suppresses parent full-fetch behavior and restores protocol tab/wide-layout route state through router replace. Server/API build passed with `mvn -pl api,server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true install`; UI build passed with `NODE_OPTIONS=--openssl-legacy-provider npm --prefix ui run build` and deployed `js/app.8e9bc0d3.js`; backup path is `/root/codex-backups/tc04a-nfs-endpoints-20260603-233909`; deployed class hashes match local build outputs: `StorageServiceManagerImpl.class` `50e6bbd70541f8ce5a61e34b9f4822347131fb3f7a7901b2bc912628eb4ec3c8`, `CreateStorageNfsExportCmd.class` `4d26279727a2860d0c487e8c113b9bce75b97a4b9d1f5eaf545a4711819d21df`, `UpdateStorageNfsExportCmd.class` `3671ae963e0cb79364c89639f8a87bfdb635f20d3f7077adfd5ba1e6e81ef22c`, and `StorageNfsExportResponse.class` `aa9223511dfe9d40b825a7e7eaf97531c296bec4c714597afb815e2d8bf4be0a`. `mold.service` is active, one `ServerDaemon` process is running, and unauthenticated `listCapabilities` returns HTTP `401`. No SystemVM template rebuild was required because no SystemVM script or package changed in this fix. | Fixed / Ready For Retest |
| TC04A-20260604-01 | 2026-06-04 00:15:00 +09:00 | TC04A retest | High | NFS runtime / endpoint metadata / POSIX write behavior | Operator confirmed the full-tab refresh problem is resolved. Internal API verification found Storage Service instance `tc04a-nfs-20260603` / `21bdf944-41fc-44ef-9491-b74f13a3f966` in `Running` state with VM `623e0cf5-f26c-4659-aebd-8b58e06964bd`. NFS exports `tc04a-nfs-20260603-nfs` and `nfs02` are both `Ready`; `nfs02` persists endpoint metadata as `listenIps=["10.10.22.201"]`; ACLs are `READ_WRITE` CIDR `10.10.0.0/16`; monitor health is cached and `ok`; runtime inventory exposes root-level NFS exports `/tc04a-nfs-20260603-nfs` and `/nfs02`, not internal `/export/...` paths. WSL client `showmount -e 10.10.22.201` listed both exports, and NFSv3 mounts to `/nfs02` and `/tc04a-nfs-20260603-nfs` succeeded. Write tests failed with `Permission denied` because both mounted directories are `root:root 0755` while Root Squash maps client root to the anonymous NFS user. The VM NIC metadata also shows `10.10.22.201` as both primary IP and secondary IP, so listen-IP registration can create a duplicate secondary-IP record when the requested listen IP is already the VM primary IP. | Root Squash plus a root-owned `0755` backing directory is correct Linux NFS behavior but does not satisfy an operator expectation that `READ_WRITE` means a root client can create files. The export permission model must make POSIX ownership/mode operationally explicit and should either require a write-capable POSIX policy when Root Squash is enabled or default to a safe anonymous-write policy when the operator selects read/write. The secondary-IP duplicate is caused by backend listen address registration not skipping registration when the requested IP equals the selected NIC primary IP. | Required fixes: 1. `registerProtocolListenAddress` must skip secondary-IP creation when `listenIp` equals the candidate NIC primary IP and should cleanly tolerate existing same-IP secondary records. 2. NFS export/ACL dialogs should surface a waning or validation when `READ_WRITE + Root Squash` is selected without owner/mode/anonymous mapping that can permit writes. 3. The SystemVM apply path should apply POSIX owner/mode settings reliably for new exports and report the effective directory owner/mode in inventory so the UI can show why writes are allowed or denied. 4. For a friendly default, consider setting read/write Root Squash exports to an explicit anonymous-write mode such as anon UID/GID plus writable directory mode, or require the operator to choose the POSIX policy before submission. | Partial Pass / Fix Required |
| TC04A-20260604-02 | 2026-06-04 01:58:00 +09:00 | TC04A fix deployment checkpoint | High | NFS Root Squash POSIX defaults / listen IP duplicate avoidance / SystemVM template | The TC04A-20260604-01 defects were implemented and deployed. Backend now skips secondary-IP registration when the requested listen IP equals the selected Storage Service VM NIC primary IP. NFS read/write exports with Root Squash now receive an explicit anonymous-write POSIX default when omitted: anonymous UID/GID `65534`, owner UID/GID `65534`, mode `0775`, and non-recursive permission by default. The SystemVM apply path also computes the same effective defaults for older desired-state records before applying owner/mode. UI defaults and NFS table rendering show the effective POSIX policy, and create-dialog help text explains that ABLESTACK applies the anonymous-write profile for read/write Root Squash exports unless the operator overrides it. | The fix was built and deployed to 22.10. Server/API build passed with `mvn -pl api,server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true clean install`; UI build passed with `NODE_OPTIONS=--openssl-legacy-provider npm --prefix ui run build` and deployed `js/app.5c8c3716.js`. Deployed `StorageServiceManagerImpl.class` hash is `dc74432cee1df7e2d93b82334b418a00bee7a45eaa8b4cde5c330d71b90bbf93`; SystemVM `ablestack-storagectl` hash is `ff4e39224a1231bc738202869f6f050b9daf64b7ac3a835b6ba34180c1982c2b`; UI app bundle hash is `4965e6474ff294c5ca12e5bb34d6f4ca26db028c61d4a7a32a7d5b312547c391`. Management server `mold.service` is active and unauthenticated `listCapabilities` returns HTTP `401`. Current VM `i-2-464-VM` was runtime-patched through QGA; direct guest evidence showed `/export/nfs01`, `/export/nfs02`, `/nfs02`, and `/tc04a-nfs-20260603-nfs` owned by `65534:65534` with mode `775`. WSL client mounts to `10.10.22.201:/nfs02` and `10.10.22.201:/tc04a-nfs-20260603-nfs` succeeded and read/write create/remove checks passed. | New cross-zone SYSTEM template `SystemVM Template Storage Service (KVM) 202606040131` / `6a42ba68-013a-413c-8b5d-857b322d08ce` was registered from `systemvmtemplate-4.22.0.0-x86_64-kvm-202606040131.qcow2.bz2`; HTTP artifact SHA-256 is `87ac3dac8a7d02c552d31e24462ba537966075038db90eb67422318905ec2630`; Cloud template checksum is `68088310dbcc125f52ba66fab48d4db3139e8488c93fe8a67c4c493a813f59d3978ca0f3343a1fb2b52a19aa03327b6cee35cb81545d607d88a1e40264eee2d5`; polling reached `status=Download Complete`, `isready=true`, `downloadState=DOWNLOADED`, and `downloadPercent=100`. | Fixed / Ready For TC-04A Retest |
| TC04A-20260604-03 | 2026-06-04 15:42:00 +09:00 | TC04A endpoint/runtime inventory deployment checkpoint | High | NFS runtime JSON escaping / endpoint evidence / duplicate listen IP validation | TC-04A retest found that initial NFS service creation still reported partial initial service failure, protocol enablement accepted duplicate listen IPs without a clear message, and multiple runtime endpoints were not reliably visible in the NFS tab. Internal runtime analysis showed the current Storage Service VM `i-2-466-VM` had guest-visible IPs `10.10.254.163/16` and `10.10.22.201/16`, and NFS runtime entries included Root Squash POSIX options such as `anonuid=65534`, but the API response could be broken by escaped `=` characters in `resultjson`. | The design was updated to require monitor collectors to normalize runtime command output, expose guest-visible network addresses in `health.json` and `inventory.json`, merge runtime endpoint evidence with ABLESTACK VM/NIC metadata in the UI, reject duplicate new listen IPs explicitly, and serialize management-server runtime `resultjson` with HTML escaping disabled. Code changes were built and deployed: `ablestack-storagectl` now normalizes NFS export option strings and emits runtime network evidence; `SharedFSTab.vue` merges Cloud VM/NIC, runtime monitor, and protocol desired-state endpoints and rejects duplicate new listen IPs; `StorageServiceManagerImpl` repeatedly normalizes `\=` and uses non-HTML-escaping runtime JSON serialization; `StorageServiceRuntimeResponse` defensively normalizes runtime result strings. | Validation passed on 22.10. `bash -n systemvm/debian/usr/local/bin/ablestack-storagectl` and locale `jq empty` checks passed earlier in this checkpoint; UI build passed and deployed `js/app.9bb008ef.js`; Server/API build passed with `mvn -pl api,server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true install`. Deployed class hashes are `StorageServiceManagerImpl.class=55bc575729c1c4d1e0250c2c657a3caaa5a64bc96a3defd6820d10924a9ad105` and `StorageServiceRuntimeResponse.class=5fc1b29b0b7aad7c3919def195f121936f0dfc9633d6c7f4c6a0304c132aea3c`; backups are `/root/codex-backups/tc04a-endpoint-inventory-20260604134100`, `/root/codex-backups/tc04a-runtime-json-sanitize-loop-20260604152600`, and `/root/codex-backups/tc04a-runtime-json-disable-html-escape-20260604153600`. The current VM was runtime-patched through QGA, direct inventory showed no bad `\=` escapes, and signed API verification retuned parsable outer inventory/health JSON, `API_RESULT_HAS_BAD_ESCAPE=False`, `HEALTH_STATUS=ok`, NFS export count `2`, NFS entries without backslash-equals, and runtime network evidence `10.10.254.163/16`, `10.10.22.201/16`. Management server `mold.service` is active and unauthenticated `listCapabilities` returns HTTP `401`. Template `SystemVM Template Storage Service (KVM) 202606041504` / `ca34c16a-d809-433e-8d2c-76e4a1ba365e` was already rebuilt and registered with monitor/network normalization content; no additional template rebuild was required for the management-server-only JSON serialization fix. | Fixed / Ready For TC-04A Retest |
| TC04A-20260604-04 | 2026-06-04 Asia/Seoul | NFS lifecycle CRUD design/code checkpoint | High | NFS protocol/export/ACL create-update-delete and dark-mode deletion UX | TC-04A scope is expanded from creation-only management to full lifecycle management. The NFS tab must support protocol deletion, NFS export edit/delete, NFS ACL edit/delete, and ACL creation with multiple comma-separated CIDR/IP values. Destructive actions must require a final confirmation and must preserve the current protocol tab and wide-layout state. | Design updated: protocol deletion is allowed only after dependent resources are removed; NFS listener ports are endpoint-scoped and may be changed from protocol management, while exports inherit the active endpoint port for display rather than owning a fixed 2049 port; NFS export edit reuses the create dialog with row values prefilled; ACL create accepts one or more comma-separated principals and persists one row per principal; ACL edit remains single-row only; delete uses an exact-name confirmation dialog with dark-mode styling. | Implementation pending validation in this checkpoint: API command `deleteStorageServiceProtocol`, NFS ACL multi-principal parameter handling, StorageService manager delete/protocol validation, NFS tab row action buttons, edit/delete modal state, and i18n keys are added in the working tree. Build/deploy evidence must be appended after this checkpoint is packaged and deployed. | In Progress |
| TC04A-20260604-05 | 2026-06-04 23:35:00 +09:00 | NFS endpoint/delete/volume UX checkpoint | High | API/UI/SystemVM / NFS endpoint lifecycle and exportfs duplicate protection | Operator found that protocol deletion did not identify the endpoint being removed, NFS ACL creation could fail unrelated exports with duplicate `exportfs` entries, capacity/volume expansion dialogs were horizontal, NFS export creation could not create a new backing volume from a disk offering, and the `secure` option wording did not explain privileged source port semantics. | The design was updated to split full protocol deletion from endpoint removal, require IP-specific endpoint confirmation, de-duplicate desired-state ACL rendering before `exportfs -ra`, expose new-volume creation from disk offering in the NFS export dialog, keep capacity and volume expansion dialogs on the vertical modal standard, and rename `secure` to privileged-port requirement. NFS listener ports are endpoint-scoped and may be changed from protocol management; exports inherit the active endpoint port for display rather than owning their own port. | Validation passed for packaging and deployment readiness. `jq empty ui/public/locales/en.json ui/public/locales/ko_KR.json` and `bash -n systemvm/debian/usr/local/bin/ablestack-storagectl` passed. Backend/API build passed with `mvn -pl api,server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true install`; UI build passed with `NODE_OPTIONS=--openssl-legacy-provider npm --prefix ui run build` and deployed `js/app.fa919768.js`. Management-server classes were patched into `cloud-api-4.22.0.0-SNAPSHOT.jar`, `cloud-server-4.22.0.0-SNAPSHOT.jar`, and `cloudstack-4.22.0.0-SNAPSHOT.jar`; deployment backup is `/root/codex-backups/tc04a-zip-20260604224449`. `mold.service` restarted successfully and unauthenticated `listCapabilities` returns HTTP `401`. New cross-zone SYSTEM template `SystemVM Template Storage Service (KVM) 202606042247` / `95f24b7c-1a8c-4e85-96ed-eb8c97da2a73` was built from `systemvmtemplate-4.22.0.0-x86_64-kvm-202606042247.qcow2.bz2`, copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`, registered with SHA-256 checksum `b85346f48c482cbef7199e684f9f80e41f01d7dbac86da8b4b406c159769c779`, and polling reached `status=Download Complete`, `isready=true`, `downloadState=DOWNLOADED`, and `downloadPercent=100`, and `physicalsize=596679680`. | Fixed / Ready For TC-04A Retest |
| TC04A-20260605-01 | 2026-06-05 01:52:00 +09:00 | NFS backing volume attach and runtime endpoint role checkpoint | High | API/UI/SystemVM / NFS new-existing volume lifecycle and endpoint authority | TC-04A retest found that an NFS export using an existing or newly created backing volume created the ABLESTACK volume row but did not attach that volume to the Storage Service VM. The export therefore continued to use the default backing path and the new volume was not mounted. Operator-provided guest evidence also showed the actual primary IP as `10.10.254.164/16` and secondary IP as `10.10.22.201/16`, while API/NIC metadata could make `10.10.22.201` look like the primary address. | The UI-created volume workflow stopped after `createVolume` and submitted only metadata, while the NFS create/update backend did not consistently own the full attach-inspect-mount-apply lifecycle. Endpoint lists and removable endpoint decisions also used API NIC metadata before guest runtime evidence. | Design updated and implemented: NFS/SMB file-share create/update with `volumeid` now attaches the volume to the Storage Service VM, calls QGA `volume attach inspect`, mounts the filesystem, persists runtime mount metadata, then applies desired state. New UI-created NFS volumes send `importmode=FORMAT_EMPTY`, which formats only an empty unformatted device; existing volumes send `importmode=MOUNT_EXISTING`. Runtime `ip addr` primary/secondary role is authoritative for endpoint display, duplicate checks, and deletion eligibility; API NIC data is advisory. Build/deploy evidence: `jq empty` and `bash -n ablestack-storagectl` passed; backend/API Maven build passed; UI build passed with existing asset-size wanings and deployed `js/app.fa919768.js`; patched class hashes are `StorageServiceManagerImpl.class=293c400b8228415483e61b2ef294ab4f2bab727e91b447ae5611458db5e0323f`, `CreateStorageNfsExportCmd.class=4d26279727a2860d0c487e8c113b9bce75b97a4b9d1f5eaf545a4711819d21df`, and `UpdateStorageNfsExportCmd.class=3671ae963e0cb79364c89639f8a87bfdb635f20d3f7077adfd5ba1e6e81ef22c`; deployment backup is `/root/codex-backups/tc04a-volume-endpoint-20260605004952`; `mold.service` is active with one `ServerDaemon`; unauthenticated `listCapabilities` returns HTTP `401`. New cross-zone SYSTEM template `SystemVM Template Storage Service (KVM) 202606050121` / `05c9ce0b-6069-43fe-bd68-1225fe73e0b4` was registered from `systemvmtemplate-4.22.0.0-x86_64-kvm-202606050121.qcow2.bz2` with SHA-256 `119cccdd5dd2136ccc69583f1cf7b1404e7b75be7d7f0eb209b30b346c67da51`; polling reached `status=Download Complete`, `isready=true`, `downloadState=DOWNLOADED`, and `downloadPercent=100`. | Fixed / Ready For TC-04A Retest |
| TC04A-20260605-02 | 2026-06-05 12:19:00 +09:00 | NFS runtime cache and backing-volume retest defect | High | API/UI/SystemVM / NFS runtime readiness, volume attach, ACL edit UX | Operator retest still showed the partial initial-service waning, blank NFS status summary monitor fields, an NFS export volume that was created but not attached/mounted, overly bright dark-mode principal placeholder text, and ACL edit accepting multiple principals then failing because update is single-row only. | The System VM template/runtime path did not have a hard quality gate for non-empty `ablestack-storagectl` and an enabled monitor service. The NFS create/update backend must skip attach/inspect for the already mounted initial SharedFS data volume when `importmode` is omitted, but must perform attach/inspect/mount when explicit `MOUNT_EXISTING` or `FORMAT_EMPTY` is supplied. Path collision checks were not strict enough, and the ACL edit modal reused the create modal's multi-value tag input. | Design updated and deployed: template build/setup now rejects empty storage control binaries and unmasks/enables monitor; `FORMAT_EMPTY` refuses devices with existing filesystems; mount targets already used by another device fail before mount; NFS create/update validates duplicate and overlapping share paths; attach/inspect runs only when `importmode` is explicit; ACL create remains multi-CIDR while ACL edit is single-principal; dark-mode placeholder tone is reduced. Backend/API build passed with `mvn -pl api,server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true install`; UI build passed with `NODE_OPTIONS=--openssl-legacy-provider npm --prefix ui run build` and deployed `js/app.0c6c2a43.js`; `mold.service` is active and unauthenticated `listCapabilities` returns HTTP `401`. `bash build.sh systemvmtemplate 4.22.0.0 x86_64` completed successfully; artifact `systemvmtemplate-4.22.0.0-x86_64-kvm-202606051109.qcow2.bz2` was copied to `10.10.22.10:/var/www/html/storage-service-systemvm/` with SHA-256 `9d9d61c29031f69ad776dcebe7bcd43901a187badb355b19b2e6b9f4a6df5db6` and HTTP `200`. Initial plain-checksum registration `abb8f8c0-e85c-4479-b7aa-aa68a9069672` was deleted with `issystem=true, forced=true` after Cloud interpreted it as MD5 and `listTemplates` no longer returns it; final cross-zone SYSTEM template `SystemVM Template Storage Service (KVM) 202606051109 SHA256` / `3f609a90-57d0-4638-9394-a23d45c3ae98` was registered with `{SHA-256}` checksum and reached `Download Complete`, `isready=true`, `downloadState=DOWNLOADED`, `downloadPercent=100`, `physicalsize=595587584`. | Fixed / Ready For TC-04A Retest |
| TC04A-20260605-03 | 2026-06-05 14:35:00 +09:00 | NFS new-volume primary storage and rollback defect | High | API/UI / NFS export new backing volume transaction | TC-04A retest showed that creating an NFS export with a new backing volume failed with `Unable to find suitable primary storage when creating volume`, while the failed volume/export artifacts remained visible in API/UI. | The NFS tab created the ABLESTACK volume before the export API call but did not pass an explicit `storageid`, so the volume could remain pool-unallocated. The export API then persisted the NFS export row before attach/QGA/apply succeeded and changed failed rows to `Error` instead of compensating, so failed artifacts looked like managed service objects. | Design updated and deployed: the NFS new-volume UI now exposes primary storage selection and defaults to the current SharedFS data volume storage pool when available; `createVolume` passes `storageid`; `createStorageNfsExport` accepts `cleanupvolumeonfailure` for UI-created volumes and removes the failed export row on attach/QGA/apply failure while attempting to detach/destroy only the newly created backing volume. Existing-volume failures remain non-destructive. Validation: locale JSON passed with `jq empty`; backend/API build passed with `mvn -pl api,server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true install`; UI build produced `js/app.78071b7e.js`. Deployment backup is `/root/codex-backups/tc04a-newvolume-rollback-20260605142237`. Deployed hashes matched local build outputs: `CreateStorageNfsExportCmd.class=75697d024a1818aaeee3047697092ebc9fa11f4e7eb0df8ce836bd12d5787a8a`, `StorageServiceManagerImpl.class=8e99e1dab2af6d1df78b948e21b6a69fb8d2e5e343e226be33ec3bb14a9ea4d8`, and `app.78071b7e.js=8a3e6073a3e7f08ceaa8eb8b43bbc229e95b1a7c95e200c9fa2eded6faefab6f`. `mold.service` is active and unauthenticated `listCapabilities` returns HTTP `401`. No SystemVM template rebuild was required because no SystemVM script or package changed. | Fixed / Ready For TC-04A Retest |

| TC04A-20260605-04 | 2026-06-05 15:05:00 +09:00 | NFS new-volume async readiness race | High | UI/API / NFS export new backing volume attach timing | TC-04A retest passed initial SharedFS creation and default NFS runtime display, but adding a second NFS export with a newly created backing volume failed with `Volume state must be in Allocated, Ready or in Uploaded state`. The failed `nfs02` export was not left visible and the UI-created `nfs02-data` volume was destroyed by rollback. | `createVolume` is asynchronous. The NFS tab used the retuned volume ID before the volume creation job had completed and before `listVolumes` showed an attachable state. The server then tried to attach the still-transitional volume to the Storage Service VM and hit the core volume state guard. | Fixed and deployed: the NFS new-volume UI now polls `queryAsyncJobResult` for `createVolume`, resolves the final volume ID from the job result, and waits until `listVolumes` reports `Allocated`, `Ready`, or `Uploaded` before calling `createStorageNfsExport`. The engine also re-queries and briefly waits for the same attachable states before calling `attachVolumeToVM`, preserving the existing rollback cleanup for UI-created volumes. Validation: locale JSON passed with `jq empty`; backend/API build passed with `mvn -pl api,server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true install`; UI build produced `js/app.2592b504.js`. Deployment backup is `/root/codex-backups/tc04a-newvolume-async-20260605151334`. Deployed hashes matched local build outputs: `StorageServiceManagerImpl.class=862503869fecd59c749dc75fcaf86c07d379cd42542de1d966c4ec5b2c5b014e`, `app.2592b504.js=fe98225fa693ea51f60eaece0616251c48975544d785099db03fe3b5b23272d4`. `mold.service` is active, 8080 is listening, and unauthenticated `listCapabilities` returns HTTP `401`. No SystemVM template rebuild was required because no SystemVM script or package changed. | Fixed / Ready For TC-04A Retest |
| TC04A-20260605-05 | 2026-06-05 15:30:00 +09:00 | NFS new-volume device identification safety defect | Critical | SystemVM / QGA volume attach inspect and FORMAT_EMPTY safety | TC-04A retest after async readiness fix progressed through volume creation and attach, then failed during SystemVM inspection with `Refusing to format /dev/sda5: filesystem swap already exists`. Direct evidence from VM `i-2-472-VM` showed `/dev/sda` is the ROOT disk with `/dev/sda5` swap and `/dev/sda6` mounted as `/`, while `/dev/sdb` is the default SharedFS data disk mounted at `/export`. The UI-created `nfs02-data` volume was created, attached, detached, and destroyed by rollback. | `ablestack-storagectl volume attach inspect` attempted UUID/by-id matching first, but guest-visible QEMU disk serials can be truncated and hyphenless. When matching failed, the fallback selected a single unmounted filesystem-bearing device. In this VM, that fallback picked the root disk swap partition `/dev/sda5` instead of the newly attached data disk. | Fixed and deployed for new SystemVMs: `volume attach inspect` now reads byte-accurate `lsblk` inventory, compact/prefix-matches UUID and serial evidence, excludes the root disk and every root-disk child, excludes mounted devices and ISO/ROM devices from attach candidates, requires unmounted blank whole disks for `FORMAT_EMPTY`, permits existing filesystems only for non-format import workflows, and fails closed when candidates are ambiguous. Validation passed with `bash -n systemvm/debian/usr/local/bin/ablestack-storagectl`; a dummy `FORMAT_EMPTY` inspect without a safe matching data disk retuned `Unable to identify a safe attached Storage Service volume device` instead of selecting an unsafe partition. SystemVM artifact `systemvmtemplate-4.22.0.0-x86_64-kvm-202606051611.qcow2.bz2` was generated from the rebuilt qcow2 image, verified with `bzip2 -t`, copied to the 22.10 management server, and served at `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202606051611.qcow2.bz2` with HTTP `200`. SHA-256 is `ccfa4e2dc2bab82922aa01caaca5c1acde8074018acc48efaf6d6063d21a6f09`. Cross-zone SYSTEM template `SystemVM Template Storage Service (KVM) 202606051611 SHA256` / `cc61ece7-8ad1-4637-8b14-b2f5c5bf5f30` was registered with `{SHA-256}` checksum and reached `status=Download Complete`, `isready=true`, `physicalsize=743103488`, OS type `Debian GNU/Linux 12 (64-bit)`. Existing already-running Storage Service VMs still need recreation or an explicit runtime patch to receive this SystemVM tool change. | Fixed / Ready For TC-04A Retest With New Storage Service VM |
| TC04A-20260605-06 | 2026-06-05 17:05:00 +09:00 | NFS backing and alias mount persistence defect | High | SystemVM / NFS export mount topology, fstab persistence, monitor capacity cache | TC-04A retest after the safe device-selection template showed that NFS exports could be registered and shown as Ready, but SystemVM runtime mounts were not boot-safe. Direct VM evidence from `i-2-473-VM` showed `/dev/sdb` mounted at both `/export` and `/nfs01`, `/dev/sdc` mounted at both `/export2/nfs02` and `/nfs02`, and `/etc/fstab` contained only the root/boot/default `/export` entry. The monitor `capacity.json` reported only `/export`, so the additional `nfs02` backing volume was missing from runtime capacity data. | The implementation created transient mounts: `volume attach inspect` mounted the backing volume but did not persist the mount in `/etc/fstab`, and `nfs export apply` created client-visible bind aliases but did not persist those aliases. Capacity collection scanned only `/srv/ablestack-storage` and `/export`, so exports backed by other paths were not discoverable after apply or reboot. | Fixed in code and template: `volume attach inspect` now persists backing mounts with UUID-based managed fstab entries, `nfs export apply` persists NFS alias bind mounts with managed fstab entries and removes stale alias entries, both paths reload systemd after fstab changes, and the monitor capacity collector reads `/etc/ablestack-storage/nfs-export-aliases.json` so every backing path and alias path can be represented. Validation passed: `bash -n` for `ablestack-storagectl` and `ablestack-storage-monitor`, Python heredoc compilation for 15 storagectl snippets and 2 monitor snippets, locale JSON validation with `jq empty`, and one-shot monitor execution. Built SystemVM artifact `systemvmtemplate-4.22.0.0-x86_64-kvm-202606051708.qcow2.bz2`, SHA256 `38fb359db55a12bd01924f149b41a4a0a4c46d9c22413f8ad28ae9a6ec535743`, HTTP deployment retuned `200 OK`, and registered template `SystemVM Template Storage Service (KVM) 202606051708 SHA256` as `f944a036-989b-4628-95b3-e25be66a514d` with `status=Download Complete`, `isready=true`. Existing running Storage Service VMs remain on their current template/runtime until recreated or explicitly runtime-patched. | Ready for retest |
| TC04A-20260605-07 | 2026-06-05 18:25:00 +09:00 | `volume attach inspect` fstab helper runtime import failure | High | SystemVM / NFS export creation with attached backing volume | Retest on SharedFS `67162339-4dd2-4a67-b08e-cf8a294c4ce9` failed during NFS export creation with `NameError: name 'tempfile' is not defined` from `persist_backing_mount -> update_fstab`. Direct inspection confirmed the VM was created from template `SystemVM Template Storage Service (KVM) 202606051708 SHA256`, so this was not stale-template usage. | The file-level script had `import tempfile` in another Python heredoc, but the independent `volume attach inspect` Python heredoc did not import it. Previous validation compiled heredocs but did not execute the managed fstab update path, so the runtime missing-name error was not caught. | Fixed: added `import tempfile` to the `volume attach inspect` heredoc and added a heredoc import check that fails when any Python heredoc uses `tempfile` without importing it. Runtime-patched active VM `i-2-474-VM`; QGA verification showed the fixed import in `/usr/local/bin/ablestack-storagectl` and `bash -n` passed in the guest. Current VM state after patch: root disk is untouched, legacy data disk remains mounted at `/export`, and existing alias `/nfs01` remains represented in `/etc/fstab`. Rebuilt and registered SystemVM template `SystemVM Template Storage Service (KVM) 202606051831 SHA256`, template ID `aed51423-667e-4af0-a588-278713bae1ff`, SHA256 `c73d81e7eaed42d4635088cf78fcfb37725d20d39274523d8735a3606d8ca83a`, `status=Download Complete`, `isready=true`. | Ready for retest |
| TC04A-20260605-08 | 2026-06-05 20:30:00 +09:00 | NFS export endpoint mode persistence | High | API/UI / NFS export selected endpoint display and edit default | TC-04A retest showed that an NFS export created with one selected endpoint was displayed as if all endpoints were selected, and the create dialog defaulted to all endpoints. | The NFS export API persisted only an optional `listenIps` list and did not expose an explicit endpoint mode. The UI therefore had to infer selected-versus-all from the retuned IP list and fell back to the service-wide endpoint list after refresh, making selected endpoint intent ambiguous. | Fixed and deployed: `createStorageNfsExport` and `updateStorageNfsExport` now accept `endpointmode=ALL|SELECTED`; NFS export config persists `endpointMode`; list/detail responses return `endpointmode`; selected `listenips` are retuned only for `SELECTED`; legacy rows with `listenIps` and no `endpointMode` are interpreted as `SELECTED`; rows without endpoint metadata are interpreted as `ALL`; the create dialog now defaults to `SELECTED` with no IP preselected and both UI/backend reject `SELECTED` without at least one endpoint. Validation passed with `jq empty`, `git diff --check`, backend/API build `mvn -pl api,server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true install`, and UI build `NODE_OPTIONS=--openssl-legacy-provider npm --prefix ui run build`. Deployment backup is `/root/codex-backups/tc04a-endpointmode-20260605202913`; deployed UI bundle is `js/app.dce1aafb.js`; deployed hashes match local build outputs: `StorageNfsExportResponse.class=d22c303e6207b4ec4ba43294647847aca99813cfaf92770c50c3b76769049a77`, `StorageServiceManagerImpl.class=68f12f99e8ca17aad5df7b4ac24245de0af9dab2f6b83469db85553d8dec0d7c`, and `app.dce1aafb.js=025c04f446ead9caa4c459161b8d7073915b1712c23aa6a789235a9f55495e87`. A webapp `WEB-INF` deletion caused by an unsafe UI `rsync --delete` was detected during verification and immediately restored from `/root/codex-backups/tc04a-newvolume-rollback-20260605142237/webapp/WEB-INF`; final checks show `WEB-INF=present`, `mold.service=active`, and unauthenticated `listCapabilities` returns HTTP `401`. No SystemVM template rebuild was required because no SystemVM script or package changed. | Fixed / Ready For Retest |
| TC04A-20260605-09 | 2026-06-05 21:20:00 +09:00 | NFS selected endpoint config truncation | Critical | API/DB/UI / NFS export endpoint persistence and response correctness | Operator retest in the visible UI showed that even when a single endpoint was selected, NFS export rows still displayed all endpoints. Direct browser review of `#/sharedfs/3edbe504-7292-4215-8ebf-86ab4d84fd15?tab=nfs&wide=true` reproduced the issue. Signed API inspection showed `nfs01` and `nfs02` responses with `config` length exactly `255`, invalid JSON, `endpointmode=ALL`, and `listenips=null`; the raw truncated config still showed partial `endpointMode=SELECTED` and `listenIps` content. | The live 22.10 DB schema still constrained `storage_file_share.config_json` to the default string width even though the source schema intended `mediumtext`. Once the JSON was truncated, the server parsed an empty config and defaulted NFS endpoint mode to `ALL`, making the UI show every service endpoint. | Fixed and deployed: all Storage Service `config_json` columns are required to be `mediumtext`; the 4.22.0 to 4.22.1 schema upgrade includes conditional ALTER guards for `storage_service_protocol`, `storage_file_share`, `storage_block_target`, `storage_access_rule`, and `storage_identity_domain`; NFS export responses now expose `configvalid/configerror`; invalid config no longer silently becomes a normal `ALL` state, and endpoint mode/listen IPs are recovered from raw config when complete values remain. Validation passed with `git diff --check` and backend/API build `mvn -pl api,server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true install`. Runtime deployment patched `StorageNfsExportResponse.class` and `StorageServiceManagerImpl.class` into the management jars; backup is `/root/codex-backups/tc04a-configjson-zip-20260605210551`; deployed hashes match local build outputs. Live DB ALTER completed and verified all five `config_json` columns as `mediumtext`. `mold.service` restarted with a new ServerDaemon process and unauthenticated `listCapabilities` returns HTTP `401`. Signed API now returns `configvalid=false` and recovered `endpointmode=SELECTED` / `listenips=10.10.22.201` for the previously truncated `nfs02`; visible UI review confirms `nfs02` displays only `10.10.22.201` while an `ALL` export still displays both endpoints. No SystemVM template rebuild was required because this is API/DB/UI-response behavior only. Existing truncated rows should still be recreated or edited after the type fix if lost fields are operationally required. | Fixed / Ready For Retest |
| TC04A-20260605-10 | 2026-06-05 22:05:00 +09:00 | NFS reusable backing-volume selection | High | UI/API / NFS export deletion and backing-volume reuse | Operator found that deleting an NFS export correctly removed the guest mount/export state but left the ABLESTACK backing volume attached. Recreating the export was ambiguous because the NFS export dialog could not choose among currently attached backing volumes, even though a retained backing volume can contain multiple export directories. | The UI built its backing-volume candidates mostly from existing export/share rows. After the export row was deleted, the retained attached volume lost its UI association even though it was still attached to the Storage Service VM. The `CURRENT` backing-volume mode also sent no volume ID, so the API could not record which already-attached volume the new export should use. | Implemented and deployed. The NFS export create/edit dialog now lists current backing volumes attached to the Storage Service VM, requires explicit current-volume selection when more than one candidate exists, shows mount/export summary for the selected current volume, and sends the selected volume ID without import mode so the backend records the mapping without attach/format/remount. The NFS backing-volume table now includes attached data volumes even when they have zero exports, with export-only row actions disabled when no share row exists. Validation evidence: locale JSON check passed, `git diff --check` passed, UI build passed with bundle `js/app.9207dbd5.js`, deployed to 22.10 with backup `/root/codex-backups/tc04a-currentvolume-ui-20260605220637`, `WEB-INF` preserved, `mold.service` active, unauthenticated `listCapabilities` retuned 401, and the browser loaded `app.9207dbd5.js`. Browser verification confirmed the NFS export dialog shows `현재 백킹 볼륨` and does not auto-select a current backing volume in the multi-volume case. No backend or SystemVM template rebuild was required for this UI/docs-only refinement. | Fixed / Ready For TC-04A Retest |
| TC04A-20260605-11 | 2026-06-05 22:35:00 +09:00 | Existing backing-volume directory import, filesystem selection, and detach | High | UI/API/SystemVM / NFS backing volume lifecycle | Operator clarified that an existing ABLESTACK volume may already contain directories, and the NFS export workflow must expose one selected directory inside that volume rather than treating the entered path as the volume mount point. Operator also required a backing-volume detach action that is disabled while any export/share/target still uses the volume, and noted that new-volume creation must not silently force XFS without an explicit filesystem choice. | The previous model overloaded `path` as both the SystemVM volume mount point and the export backing directory. New-volume filesystem selection was hidden behind a default `xfs` value. There was no Storage Service-specific detach action for unused backing volumes. | Design/code updated and deployed: existing/current volume workflows now carry `relativepath` and `createdirectory`, SystemVM mounts imported volumes at stable UUID-based roots, validates `xfs/ext4`, resolves the effective backing path below the mount root, and records filesystem/mount/backing details. New-volume workflows expose `xfs/ext4` selection. An unused backing volume can be detached through a guarded API/UI action; detach unmounts/removes fstab markers and never deletes the volume or data. Validation evidence: locale JSON check passed, `ablestack-storagectl` shell syntax check passed, backend compile passed for `api,engine/schema,server`, UI build passed with bundle `js/app.6129145a.js`, runtime jars were patched with backup `/root/codex-backups/tc04a-dir-volume-20260605231908`, UI was deployed with backup `/root/codex-backups/tc04a-dir-volume-ui-20260605231942`, `mold.service` is active and unauthenticated `listCapabilities` returns 401. SystemVM template was rebuilt and registered as `449055d0-ae0b-400e-8a18-5fa3370bd426`, `SystemVM Template Storage Service (KVM) 202606052321 SYSTEM`, type `SYSTEM`, cross-zone, ready, status `Download Complete`. Retest must cover existing XFS and EXT4 volume directory export, missing-directory rejection, create-if-missing, unsupported filesystem failure, detach disabled while referenced, detach enabled after all references are removed, and volume data preservation after detach. | Fixed / Ready For TC-04A Retest |
| TC04A-20260606-01 | 2026-06-06 00:20:00 +09:00 | NFS path unification and QGA-only storage initialization | Critical | UI/API/SystemVM/cloud-init / NFS backing path and initial volume handling | Operator clarified that `internal backing path` and `directory inside backing volume` conflicted. The desired model exposes only `/export/<export-name>` to the operator, forces one-level `/export` children, defaults directory creation to enabled, and moves all Storage Service storage actions away from ConfigDrive/cloud-init into QGA commands. | The previous implementation kept a separate relative directory field, could store private `/srv/ablestack-storage/...` paths as share paths, and `fsvm-init.yml` still formatted/mounted the default data disk at `/export`. That could conflict with per-export bind aliases and made initial NFS behavior depend on cloud-init rather than desired state. | Design/code implemented and deployed: relative directory input was removed from the NFS create/edit flow, `기존 볼륨 사용` was relabeled to `백킹 볼륨 선택`, NFS names are validated with Linux directory-name rules, NFS paths are constrained to exactly `/export/<name>`, and API/server code keeps `share.path` as the client-visible root while storing private `backingPath` only in config/inspection. QGA now mounts selected volumes under private UUID-based roots and bind-mounts `/export/<name>`, current SharedFS backing volume initialization uses `FORMAT_IF_EMPTY`, and `fsvm-init.yml` no longer formats/mounts `/export` or publishes legacy NFS exports from cloud-init. Validation evidence: locale JSON check passed, `ablestack-storagectl` shell syntax check passed, backend Maven compile passed for `api,engine/schema,server,plugins/storage/sharedfs/storagevm`, UI build passed with bundle `js/app.282aa273.js`, runtime jars were patched with backup `/root/codex-backups/storage-service-qga-path-zip-20260606150338`, UI was deployed with backup `/root/codex-backups/webapp.storage-service-ui-20260606150141.bak`, `mold.service` is active, unauthenticated `listCapabilities` returns 401, SystemVM template build passed, bzip2 integrity check passed, and the template was registered as `76d5820d-5a15-43ee-b9b9-367269a6a80a`, `SystemVM Template Storage Service (KVM) 202606061510 SYSTEM`, type `SYSTEM`, cross-zone, ready, status `Download Complete`, checksum `8a66bcd723f61a79592a4f31de6fb8a5`. Retest must create a new SystemVM from this template, verify no cloud-init `/export` fstab entry is produced, create NFS exports from current/existing/new backing volumes, verify `/export/<name>` only, verify invalid names/deep paths are rejected, and verify missing-directory behavior with create-directory on and off. | Fixed / Ready For TC-04A Retest |
| TC04A-20260606-02 | 2026-06-06 18:55:00 +09:00 | NFS backing config truncation and rootfs alias protection | Critical | API/DB/SystemVM/UI / NFS initial export backing integrity | Operator retest showed that the initial NFS export could be listed as created while the service retuned the partial initial-service waning, `/export` was not a data-disk mount, and `/export/nfs01` existed on the root filesystem instead of being backed by the data disk. | Direct inspection found `storage_file_share.config_json` truncated at 255 bytes with `configvalid=false` and `INVALID_JSON_CONFIG`. The truncated JSON lost fields such as `backingPath`, so the System VM apply path could fall back to the client-visible `/export/<name>` alias and create/export a rootfs directory instead of binding the data volume path. | Design/code implemented and deployed: all Storage Service `config_json` columns are now `MEDIUMTEXT` in JPA and the live DB (`storage_service_protocol`, `storage_file_share`, `storage_block_target`, `storage_access_rule`, `storage_identity_domain`); the NFS desired-state path strictly parses export/ACL JSON and fails before QGA if active export backing metadata is invalid or missing; `ablestack-storagectl` now requires `backingPath` and `volumeMountPath`, requires the backing volume mount to be active under `/srv/ablestack-storage/volumes/`, refuses `/`, `/export`, and `/export/*` as backing paths, refuses to hide non-empty rootfs aliases, and verifies `/export/<name>` is a bind mount after apply. The create modal capacity input layout was corrected so the numeric field remains visible next to the unit selector. Validation evidence: server/schema/API build passed, UI build passed with deployed bundle `js/app.2e5f56ed.js` and `css/app.7ff3f668.css`, `mold.service` is active, unauthenticated `listCapabilities` returns 401, the live DB columns report `mediumtext`, and deployment backup is `/root/codex-backups/storage-service-export-fix-20260606172558`. A fresh SystemVM template was rebuilt from the corrected scripts, bzip2-verified, copied to `10.10.22.10:/var/www/html/storage-service-systemvm/`, and registered as `7ecc8cb8-8cb7-4678-8716-5db72cdca0a7`, `SystemVM Template Storage Service (KVM) 202606061813 SHA256`, type `SYSTEM`, cross-zone, ready, status `Download Complete`, physical size `593502720`, checksum `b5926281861bc51f9ccfd8b68d158538301b89a9e114cceecb62b252c0d5e766`. Retest must create a new SharedFS/Storage Service from this template, confirm no partial initial-service waning, confirm each export's `configvalid=true`, and verify in the guest that every `/export/<name>` is a bind mount to a mounted backing volume path rather than a rootfs directory. | Fixed / Ready For TC-04A Retest |
| TC04A-20260606-03 | 2026-06-06 20:20:00 +09:00 | Initial NFS export failure evidence preservation | High | API/UI / NFS initial export failure handling | Retest after the rootfs-alias guard retuned `NFS export <uuid> has invalid Storage Service JSON config; refusing to apply desired state`, but the NFS tab showed no export details because the failed row was deleted during cleanup. | The create path persisted a share row, attempted desired-state apply, then removed the row on failure. This protected the active state but erased the object and its failure reason, leaving the operator with only the toast message and no tab-level diagnostic evidence. | Implemented: generated NFS export JSON is validated before persistence, failed create rows are preserved in `Error` state with valid `config_json.lastError`, NFS desired-state generation skips non-active export/ACL rows, and the NFS tab displays an error alert listing failed exports and their reasons. Retest must create a new NFS SharedFS, confirm no partial setup waning in the normal case, and if an apply failure is forced, confirm the failed export is visible as an error without blocking later NFS reconciliation. | Ready For Retest |
| TC04A-20260606-04 | 2026-06-06 23:25:00 +09:00 | Storage Service `config_json` DAO length truncation | Critical | API/DAO/DB mapping / Storage Service desired-state persistence | Retest on SharedFS `47ada734-d04e-4049-99a9-0410794c3972` still failed with `NFS export <uuid> has invalid Storage Service JSON config; refusing to apply desired state`. Direct DB inspection showed the NFS file-share `config_json` was exactly 255 characters even though the live table column was already `MEDIUMTEXT`. Guest inspection showed the data disk mounted at the private `/srv/ablestack-storage/volumes/<volume-uuid>` path, but `/export` did not exist and the client-visible `/export/<name>` bind alias/export was never applied. | The physical DB type was corrected, but Mold `GenericDaoBase` truncates String values using the JPA `@Column.length` value. Storage Service VO classes used `@Lob` and `columnDefinition = "MEDIUMTEXT"` but one config field still relied on the JPA default length of 255, so desired-state JSON could still be truncated before insert/update despite the DB column being large enough. | Fixed and deployed: every Storage Service `config_json` entity field now uses `@Column(name = "config_json", length = 16777215, columnDefinition = "MEDIUMTEXT")`. Validation passed with `git diff --check` and backend build `mvn -pl engine/schema,server -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true install`. Runtime deployment patched `StorageAccessRuleVO`, `StorageBlockTargetVO`, `StorageFileShareVO`, `StorageIdentityDomainVO`, and `StorageServiceProtocolVO` into `cloudstack-4.22.0.0-SNAPSHOT.jar`; backup is `/root/codex-backups/tc04a-configjson-length-20260606231811`. Deployed hashes match local build outputs, `mold.service` is active/running with MainPID `3680889`, unauthenticated `listCapabilities` returns HTTP `401`, and the live DB still reports all five `config_json` columns as `mediumtext`. Existing damaged exports whose JSON was already truncated must be deleted/recreated or repaired explicitly; they are not trustworthy retest sources. No SystemVM template rebuild is required for this DAO mapping fix. | Fixed / Ready For Retest |
| TC04A-20260607-01 | 2026-06-07 00:35:00 +09:00 | Initial setup false error notification and initial backing storage selection | High | UI/API / SharedFS create lifecycle / Storage placement intent | Operator confirmed that the first SharedFS creation can show `selected NFS export and ACL were created but not activated` even when guest-side QGA apply, DB rows, fstab, bind mounts, and exportfs state are correct. Operator also requested explicit storage selection in the create dialog because the default create path can hit unsuitable primary storage. | The create dialog treated the Storage Service monitor/cache inventory as a blocking correctness gate. That cache can lag immediately after successful QGA apply, creating a false partial-failure toast. The create dialog also collected disk offering and size but did not expose initial primary-storage intent. | Implemented and deployed: NFS runtime inventory polling is now non-blocking, while API object existence and async QGA apply job success remain blocking gates, so stale monitor/cache data can no longer produce the initial partial-failure toast after a successful QGA apply. `createSharedFileSystem` accepts `storageid`; the create dialog requires a primary storage selection for new backing disks, submits `storageid` only when a new backing disk is created, and shows the selected storage in the review panel. Full allocator enforcement still requires a follow-up SharedFS VM deployment-path refactor because the current `createAdvancedVirtualMachine` service method has no pool-placement parameter. Validation passed with `jq empty` for locale JSON, `git diff --check`, UI build `NODE_OPTIONS=--openssl-legacy-provider npm --prefix ui run build` producing `js/app.bbeb9edd.js`, and API build `mvn -pl api -am -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Drat.skip=true install`. Runtime deployment copied the UI dist and patched `CreateSharedFSCmd.class` into `cloud-api-4.22.0.0-SNAPSHOT.jar` and `cloudstack-4.22.0.0-SNAPSHOT.jar`; deployed class MD5 matches local build output `479d5b6710fcb039aa25f7c0bb8f0506`. Deployment backup is `/root/codex-backups/tc04a-create-false-waning-20260607003804`; the final Korean UI wording refresh was redeployed with backup `/root/codex-backups/tc04a-create-false-waning-ui-refresh-20260607004644`. `mold.service` is active and unauthenticated `listCapabilities` returns HTTP `401`. No SystemVM template rebuild was required because no SystemVM package or script changed. | Fixed / Ready For TC-04A Retest |
| P03-REBUILD-20260608-01 | 2026-06-08 10:38:00 +09:00 | `systemvmtemplate-4.22.0.0-x86_64-kvm-202606080908.qcow2.bz2` / `systemvm-storage-service-202606080908` | Pass | SystemVM template build gate / package verification / registration | The first registration attempt for the 202606080908 SystemVM artifact failed because the published URL name did not match the generated artifact filename, and a space-containing template name was not reliable for this API path. This made the build appear healthy while the secondary-storage registration still retuned `HTTP Server retuned 404 (expected 200 OK)` or `401` on earlier attempts. | The template build pipeline needed a deterministic gate to verify the Storage Service runtime package set before publishing, and the publication step needed to use the exact generated filename. The build script was updated to fail fast if `nfs-ganesha`, `nfs-ganesha-vfs`, `rpcbind`, or `ganesha.nfsd` are missing from the image, and the host publish step now serves the corrected versioned filename `systemvmtemplate-4.22.0.0-x86_64-kvm-202606080908.qcow2.bz2`. The final successful registration used a no-space template name to avoid the signature/path handling issue seen with the human-readable name. | Fixed and verified: `/root/work/ablestack-cloud/tools/appliance/dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202606080908.qcow2.bz2` SHA-256 `c0caa2fecd33777d7ffe47271f6fc83bcfbf475d0f55f11843871475a360d821`; the host copy at `/var/www/html/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202606080908.qcow2.bz2` returns HTTP `200`; `registerTemplate` succeeded as `systemvm-storage-service-202606080908` / `ef0caf78-e69e-480a-9aa7-5b0abfdbf588`; polling `listTemplates` reached `status=Download Complete`, `isready=true`, and `physicalsize=598113280`. This template contains the Storage Service package gate and the current `nfs-ganesha` runtime package set, so it is the correct template to use for the next fresh SystemVM retest. | Fixed / Verified |
| TC04A-20260609-01 | 2026-06-09 00:55:00 +09:00 | NFS Ganesha default service collision | Critical | SystemVM / NFS Ganesha runtime / Export visibility | Fresh Storage Service VM i-2-494-VM failed initial NFS apply with nfs-ganesha export visibility probe failed for 0.0.0.0:2049 nfs01 NFSv4 to 127.0.0.1:/nfs01. Guest inspection showed nfs-ganesha.service active/enabled with the distro sample /etc/ganesha/ganesha.conf, no Storage Service export entries, and the managed endpoint log contained fcntl failed, Ganesha already started. | The template and sharedfsvm boot path did not disable the default nfs-ganesha.service, while ablestack-storagectl started managed Ganesha processes without an explicit per-endpoint pidfile and treated an open port as readiness. The default service could therefore bind 2049 and hide the fact that the generated Storage Service export config was not loaded. | Design/code updated in working tree: default nfs-ganesha.service is disabled/masked in the template and boot setup, ablestack-storagectl nfs export apply stops the default service before reconciliation, managed endpoint Ganesha uses explicit per-endpoint pidfiles, and readiness checks require the managed PID plus port before running mount probes. SystemVM template build completed successfully from /root/work/ablestack-cloud/tools/appliance and produced bzip2-verified artifact dist/systemvmtemplate-4.22.0.0-x86_64-kvm-202606 90100.qcow2.bz2 with SHA-256 9a74e2fb9ac699da6473d29d619d6b0535fd6f7584a12e656dee33b76645d096. | Build Pass / Retest Pending |
| TC04A-20260609-02 | 2026-06-09 | Ganesha literal rendering and staged initial NFS ACL apply | Critical | API/UI/SystemVM / NFS initial desired state | Fresh Storage Service VM i-2-495-VM failed with nfs-ganesha endpoints failed to start: 0.0.0.0:2049 (managed ganesha process exited). Guest log evidence showed Ganesha parser errors such as Expected an IP address, got a double quoted string, and API inspection showed the operator-entered initial ACL CIDR 10.10.0.0/16 was not persisted because the export apply failed before the UI could create the ACL. | The Ganesha renderer quoted Bind_addr and CLIENT.Clients, but Ganesha expects raw address/client literals. In addition, initial SharedFS NFS setup applied the export before the ACL existed, so a Ganesha startup failure prevented the ACL create call and dropped the requested CIDR from desired state. | Implemented and deployed: Ganesha Bind_addr and Clients are rendered as raw validated literals, all-CIDR ACLs render as *, rpcbind is started before managed endpoint startup, failed configs are preserved as .failed, createStorageNfsExport supports staged deferapply, and the SharedFS create UI uses staged export apply when an initial ACL principal is present. Backend build passed for api and server, UI build produced app.e45e4ce0.js, runtime deployment patched cloud-api/cloud-server/cloudstack jars and copied UI with backup /root/codex-backups/storage-service-ganesha-20260609064727, mold.service restarted and unauthenticated listCapabilities returned 401. SystemVM template build passed, bzip2 integrity check passed, and the downloadable artifact is http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606090650-ganesha.qcow2.bz2 with SHA-256 14f92a70f64c851f564ae30fd67abf948554a06f84875d1e128ad9667c520133. | Fixed / Ready For Retest |

| TC04A-20260609-03 | 2026-06-09 | Initial NFS deferred export was skipped from runtime apply | Critical | API/server/QGA / SharedFS create with initial NFS export and ACL | Fresh create could show success or partial success while the System VM received an empty exports payload; DB rows existed but no managed Ganesha endpoint was started. Management log evidence showed createStorageNfsExport with deferapply=true stored the export in Allocated state, then createStorageNfsAcl called desired-state apply before the export was promoted to Ready, so the export was filtered out of the payload. | The normal apply filter intentionally ignored Allocated resources, but the initial staged create path needed a controlled finalization mode that includes the staged export and ACL. The API also accepted protocolmode from UI without a command parameter, so it was logged as an unknown parameter. | Fixed in working tree: NFS export create/update APIs now accept protocolmode and responses return it; createStorageNfsAcl finalization calls NFS desired-state apply with staged Allocated resources included; normal reconciles still exclude Allocated; runtime apply fails if requested exports produce zero applied exports or zero endpoints. Backend compile passed with mvn -pl api,server -am -DskipTests -DskipITs compile. UI dark-mode protocol-mode radio styling was aligned to the real .dark-mode root class. UI/API/backend deployment completed on 10.10.22.10 with backup /root/codex-backups/storage-nfs-finalize-20260609111645; mold.service is active, WEB-INF is present, unauthenticated listCapabilities returns HTTP 401, and deployed class hashes match local build outputs. Retest must create a new SharedFS with initial NFS export and ACL, confirm no partial-service toast, confirm QGA payload contains exports and ACLs, confirm Ganesha endpoint is listening, and confirm /export/<name> mount succeeds from the client. | Fixed / Ready For Retest |

| TC04A-20260609-04 | 2026-06-09 | NFS Ganesha Export_Id range rejection | Critical | SystemVM / NFS Ganesha runtime / Export visibility | Fresh Storage Service VM `i-2-500-VM` failed initial NFS apply with `nfs-ganesha endpoints failed to start: 0.0.0.0:2049 (managed ganesha process exited)`. Guest inspection showed the generated Ganesha config contained `Export_Id = 102817299`, and the Ganesha log rejected the export block as `out of range`, leaving the endpoint unusable even though the backing volume and `/export/nfs01` bind mount existed. | The SystemVM renderer generated `Export_Id` by taking a large CRC modulo value that exceeded the deployed Ganesha runtime's accepted range. Startup validation also needed to distinguish a truly accepted managed Ganesha config from a listener/probe path that can mask config rejection. | Implemented in working tree: `ablestack-storagectl` now generates bounded `Export_Id` values in `1000..65535`, resolves collisions per endpoint, copies export render state per endpoint, truncates per-start managed logs, and fails startup when current Ganesha logs show config rejection markers for a config containing exports. SystemVM template rebuild is required because the fix lives inside `/usr/local/bin/ablestack-storagectl`. Build evidence: `bash -n systemvm/debian/usr/local/bin/ablestack-storagectl` and `git diff --check` passed; SystemVM template build completed successfully; bzip2 integrity check passed; downloadable artifact is `http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606091310-exportid.qcow2.bz2`; SHA-256 is `59b3a4106a9504433de0bc87d67c8b630d74f085cc0e46fe19a1fded40e558f2`; HTTP HEAD returned `200 OK` with `Content-Length: 592437331`. | Fixed / Download Ready |

| TC04A-20260609-05 | 2026-06-09 | NFS custom endpoint port rejected before QGA apply | High | API/server / NFS protocol endpoint port | Creating a Storage Service NFS endpoint with port `2050` failed with `NFS uses the service-wide port 2049 in the current Storage Service System VM`. Guest inspection of `i-2-502-VM` showed no `/etc/ganesha/ablestack-storage` directory, no managed Ganesha logs, and no NFS listener, proving the request was rejected before desired state reached QGA. | `StorageServiceManagerImpl.normalizeStorageServiceProtocolPort()` still forced NFS to 2049 even though the current NFS-Ganesha endpoint model treats port as endpoint-owned state. | Fixed in server runtime: NFS now accepts endpoint ports in the normal TCP range and defaults to 2049 only when omitted. The patched class was deployed to the 22.10 management server JARs, `mold.service` was restarted, and unsigned `listCapabilities` returned HTTP 401 after startup. | Ready for Retest |
| TC04A-20260609-06 | 2026-06-09 | NFS Ganesha startup race false failure | High | SystemVM / NFS Ganesha runtime readiness | Creating a Storage Service NFS endpoint with default port `2049` failed with `managed ganesha process exited`, but guest inspection of `i-2-503-VM` showed managed `ganesha.nfsd` running, `*:2049` listening, valid bounded `Export_Id = 41337`, valid `/export/nfs01` bind mount/fstab entries, and WSL NFSv4 mount to `10.10.254.165:/nfs01` succeeded. | `ablestack-storagectl.start_ganesha_endpoints()` treated an early negative `managed_ganesha_process(pid)` check as process exit. This can happen before `/proc/<pid>/cmdline` is fully observable even though the process is alive. The log rejection filter also included broad `CONFIG :CRIT`, which can match non-fatal keytab/GSS warnings under `SecType=sys`. | Fixed in SystemVM template candidate: process liveness now uses `kill(pid, 0)`, managed process confirmation is retried after listener readiness, and fatal Ganesha log markers are limited to parser/export rejection. Template download: `http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606091518-nfs-ganesha-port-race.qcow2.bz2`, SHA256 `4c8ee74ac3f187e85764c951f8fba9f114e3db220a24ba14b7622b062dfce6c0`. | Ready for Retest |
| TC04A-20260610-01 | 2026-06-10 | NFS visibility probe uses loopback outside ACL | High | SystemVM / NFS Ganesha runtime readiness | Creating a Storage Service NFS export on `i-2-505-VM` failed with `127.0.0.1:/nfs01: No such file or directory`. Guest inspection showed `nfs-ganesha` installed, Ganesha config rendered with `CLIENT { Clients = 10.10.0.0/16; }`, endpoint `0.0.0.0:2049`, and Ganesha reached `NFS SERVER INITIALIZED`. Manual probe to `127.0.0.1:/nfs01` failed, while the same config mounted successfully through the service IP `10.10.254.44:/nfs01`. | `probe_nfs_export_visibility()` converted wildcard endpoint `0.0.0.0` to `127.0.0.1`. Loopback was not allowed by the operator-provided ACL, so the readiness check failed a valid export and then stopped Ganesha. | Fixed in SystemVM template candidate: wildcard endpoint probes now enumerate global VM IPv4 addresses and select an address allowed by the export ACL. Loopback is used only for wildcard ACLs, and no matching local address now produces a clear host-selection failure. Template download: `http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606100052.qcow2.bz2`, SHA256 `008ffb1db4104d1fea42fb1188c82c300a74c47b79b27fda75b3b7804cbf37b7`. | Ready for Retest |
| TC04A-20260610-02 | 2026-06-10 | Runtime monitor health and inventory collector helper scope | High | SystemVM / Monitoring cache / NFS status summary | Fresh SharedFS `7abd7889-a1d1-4a65-b4fe-f2bd6b7ba54f` on `i-2-506-VM` was created without a partial-service error. Guest inspection showed Storage Service managed `ganesha.nfsd` running with `/etc/ganesha/ablestack-storage/0.0.0.0_2049.conf`, endpoint `10.10.254.39:2049`, export pseudo `/nfs01`, ACL `10.10.0.0/16`, and WSL NFSv4 mount to `10.10.254.39:/nfs01` succeeded. However the UI status summary showed monitoring cache `error`. | `capacity.json` and `sessions.json` were healthy, but `health.json` and `inventory.json` contained generic `collector failed`. Forcing the collectors to bypass cached files exposed `NameError: name 'rpcbind_active' is not defined` from both standalone Python blocks. The NFS runtime was healthy; only monitor collector helper scope was broken. | Fixed in working tree: `runtime_health()` and `runtime_inventory()` standalone Python blocks now include the NFS protocol-mode parser and `rpcbind_active()` helper used by managed Ganesha status evaluation. The SystemVM template build gate also verifies those standalone helper definitions. Build evidence: `bash -n` and `git diff --check` passed; SystemVM template build completed successfully; bzip2 integrity check passed; downloadable artifact is `http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606100209.qcow2.bz2`; SHA-256 is `5a62341ec5ce849f9c40f1834513cab713f38008656b9c62c2d6635c66b22280`; HTTP HEAD returned `200 OK` with `Content-Length: 588052071`. Retest must create a fresh Storage Service VM from this template and confirm health/inventory/cache status is `ok` while NFS mount still succeeds. | Fixed / Download Ready |

| TC04A-20260610-03 | 2026-06-10 | NFS action modal separation and Root Squash RW identity mapping | High | UI/SystemVM / NFS export workflow / Writable Root Squash | After NFS read/connect passed, the protocol activation modal incorrectly displayed export/backing-volume fields, the NFS export creation modal rendered empty, and writable Root Squash mounts failed writes with permission denied. | The UI had NFS export sections nested under `enableProtocol` instead of a dedicated `nfsExport`/`editNfsExport` block. The System VM prepared the backing directory with effective UID/GID 65534, but Ganesha config did not emit `Anonymous_uid`/`Anonymous_gid`, so root-squashed writes could map to a mismatched anonymous identity. | Implemented and deployed: protocol activation and NFS export modal bodies are separated, `enableStorageServiceProtocol` accepts and returns NFS `protocolmode`, UI bundle `js/app.9d8f3ee8.js` is deployed to both management client roots, runtime JAR classes were patched in `/usr/share/cloudstack-management/lib` and verified by MD5, `mold.service` returned HTTP 401 for unsigned API sanity check, and SystemVM Ganesha rendering now emits `Anonymous_uid`/`Anonymous_gid` at export/client scope. Template artifact: `http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606101057.qcow2.bz2`, SHA-256 `21a5f13c4510d955bb6daed89fbf2a072dbe011e5c005de0d85b2f3fa86e5ad6`, bzip2 integrity OK, HTTP HEAD 200. | Fixed / Ready For Retest |

| TC04A-20260610-04 | 2026-06-10 | NFS endpoint exposure and implicit ACL correction | High | UI/API/SystemVM / NFS endpoint isolation / ACL default behavior | After adding endpoint `10.10.22.201:2050` in dual mode, the existing `nfs01` export appeared attached to the new endpoint even though the operator did not explicitly expose it there. Creating another export failed with `no local IPv4 address is allowed by export clients: none` when no explicit ACL row existed yet. | Existing export endpoint metadata could be interpreted as `ALL`, so new endpoints could appear to expose old exports. The SystemVM Ganesha renderer treated an export without ACL rows as an empty client list instead of the intended default-open policy, causing the visibility probe to fail before the operator could add ACLs. | Implemented and deployed: missing endpoint metadata is no longer expanded to `ALL`, existing implicit `ALL` exports are frozen to the previous endpoint before protocol endpoint changes, and SystemVM desired-state rendering computes a wildcard `CLIENT` entry when an export has no explicit ACL rows. Explicit ACL rows suppress the wildcard entry; deleting all explicit ACL rows restores it. UI now shows the computed wildcard row as non-editable/non-deletable. Backend/API build and UI build passed; UI bundle `js/app.1cc0e46c.js` is deployed; `mold.service` is active; unsigned `listCapabilities` returns HTTP `401`; deployed `StorageServiceManagerImpl.class` MD5 matches local build output `5cb02c438aef53c04e25f0b8ea0dc52d`. SystemVM template build completed and is downloadable at `http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606101403.qcow2.bz2`, SHA-256 `0fc99ddca2e02b4310e063cb8f8974f5c567f0c39ffcd6f1cce1fa54447a20bc`, bzip2 integrity OK, HTTP HEAD 200. | Fixed / Ready For Retest |

| TC04A-20260610-05 | 2026-06-10 | NFS service-level protocol mode and optional initial ACL | High | UI/API/SystemVM / NFS mode policy / ACL default behavior | Manual QGA validation showed that endpoint-specific NFSv4-only Ganesha runtimes work, but NFSv3+NFSv4 dual mode cannot be safely mixed per export or per endpoint because rpcbind/mountd/rquota behavior is VM-global. The create dialog also still required an initial ACL even though the target behavior is implicit open when no ACL is supplied. A fresh Dual Mode create later failed with `NFS protocol mode is fixed when the Storage Service is created. Existing mode is V4_ONLY` even though the UI sent `protocolmode=V3V4_DUAL`. | The SharedFS compatibility sync pre-created an NFS protocol row with no explicit `configJson.protocolMode`. The mode-fixed validation used the display/default resolver, which turns a missing mode into `V4_ONLY`, and therefore misclassified the unconfigured compatibility row as an immutable V4-only decision. This rejected the operator-selected initial Dual Mode before any NFS export could be created. | Design/code target: NFS protocol mode is fixed only after an explicit `protocolMode`/`protocolmode` is stored. A compatibility-created NFS protocol row with no explicit mode is still unconfigured; the first `enableStorageServiceProtocol` call may set it to `V4_ONLY` or `V3V4_DUAL`. Later protocol/export actions may only echo the stored mode. `V4_ONLY` keeps endpoint-specific IP/port isolation. `V3V4_DUAL` is service-wide, uses TCP 2049, collapses exports into one managed Ganesha runtime, enables `mount_path_pseudo`, and probes NFSv3/NFSv4 through client-visible pseudo paths. Initial NFS ACL is optional; blank CIDR creates no DB ACL row and relies on the computed wildcard runtime client. | In Progress |

TC04A-20260610-05 deployment note: backend/API build completed successfully on
`codex/europa-storage-service` with `mvn -pl api,server -am ... install`.
`StorageServiceManagerImpl.class` was hot-patched into both deployed management
server jars on `10.10.22.10`; local and remote MD5 are
`caf0c34a045edb99da3ef377779779c3`. `mold.service` restarted successfully,
ports `8080` and `8250` are listening, and unsigned `listCapabilities` returns
HTTP `401`. No UI or SystemVM template rebuild was required for this fix because
the defect was the management-server mode-resolution policy for an unconfigured
compatibility NFS protocol row. Status: Fixed / Ready For Retest.

TC04A-20260610-06 UI note: Dual Mode runtime validation on SharedFS
`671aba81-c51d-4d08-8c5c-4edb5502708c` showed the SystemVM monitor cache and
Ganesha runtime correctly report `V3V4_DUAL`, but the protocol activation modal
still displayed `NFSv4 only`. Root cause was that `SharedFSTab.vue` read only
legacy `protocols/enabledProtocols` paths and missed
`health.nfsGanesha.protocolMode`, `health.nfsGanesha.endpoints[]`,
`inventory.nfsGaneshaRuntime.protocolMode`, and `inventory.nfsGaneshaExports[]`.
The UI parser now treats those fields as runtime source of truth, displays
`NFSv3 + NFSv4`, fixes the port to `2049`, and disables endpoint-specific
protocol activation in Dual Mode. Status: Fixed / Ready For Retest.

| TC04A-20260610-07 | 2026-06-10 | Dual Mode protocol activation IP registration UI/API correction | High | UI/API/backend / NFS Dual Mode service IP registration | A Dual Mode Storage Service showed broken Korean guidance text, dark-mode alert/disabled-field contrast problems, and disabled listen-IP controls in the protocol activation modal. The backend also rejected a different listen IP in Dual Mode as an unsupported endpoint even though the intended operation is service-IP registration, not independent endpoint creation. | The design now fixes only protocol mode and port in Dual Mode. Listen-IP controls remain editable, the port remains `2049`, and the protocol mode remains read-only. The backend accepts additional listen IPs for an existing Dual Mode NFS protocol row, registers the IP on the Storage Service System VM, keeps the canonical protocol listen IP unchanged, and does not create a separate Ganesha endpoint. Korean locale text is validated to avoid repeated `?` corruption. | Build/deploy evidence: `jq empty` passed for `ko_KR.json` and `en.json`; `git diff --check` passed for the changed UI/backend/docs files; backend/API build passed with `mvn -pl api,server -am -DskipTests -DskipITs ... install`; UI build passed with `NODE_OPTIONS=--openssl-legacy-provider npm --prefix ui run build`; UI was deployed to `/usr/share/cloudstack-management/webapp` and `/usr/share/cloudstack-ui`; `StorageServiceManagerImpl.class` was patched into both management server jars with MD5 `90e235af0c5a93283c1e2a5843c46397`; deployment backup is `/root/codex-backups/storage-dual-mode-ip-20260610230318`; `mold.service` is active and unsigned `listCapabilities` returns HTTP `401`; deployed Korean guidance text is valid UTF-8 and contains no repeated question-mark corruption. No SystemVM template rebuild was required because the change is limited to management server/UI behavior. | Fixed / Ready For Retest |

| TC04A-20260610-08 | 2026-06-10 | NFS mode-aware dialog correction | High | UI/API / NFSv4-only endpoint model and Dual Mode service-wide model | Runtime validation on `i-2-519-VM` confirmed Dual Mode runs one managed Ganesha process bound to `0.0.0.0:2049`, with `Protocols = 3,4` and VM-global rpcbind registrations. Therefore all exports are exposed through all service listen IPs, while export-level ACLs remain client source IP/CIDR rules. | UI design now separates `V4_ONLY` and `V3V4_DUAL`: V4-only dialogs keep endpoint selection and endpoint-specific IP/port controls; Dual Mode dialogs hide per-export endpoint selection, keep protocol mode/port fixed, allow service listen-IP registration, and show a dark-mode-safe service-wide exposure summary. ACL dialogs explain that ACLs are export-scoped client CIDR rules and apply through every service IP in Dual Mode. | UI build passed with app bundle js/app.adf475b2.js, deployed to /usr/share/cloudstack-management/webapp and /usr/share/cloudstack-ui on 10.10.22.10, /client/ returned HTTP 200, and Korean Dual Mode locale keys render without question-mark corruption. Ready for UI retest: V4-only keeps endpoint selector, Dual Mode shows service-wide exposure and omits per-export endpoint selection. | Ready For Retest |

| TC04A-20260611-01 | 2026-06-11 | Dual Mode secondary listen IP exists in DB but not in SystemVM guest | High | UI/API/SystemVM / NFS Dual Mode endpoint visibility | Dual Mode SharedFS `671aba81-c51d-4d08-8c5c-4edb5502708c` showed `10.10.22.201` in the UI export client mount roots because Cloud DB contained a NIC secondary-IP row, but the state summary and endpoint column showed only `10.10.254.55:2049`. Guest inspection via QGA/libvirt showed only `10.10.254.55/16`; TCP and NFS mount checks to `10.10.22.201:2049` failed. | The backend registered the secondary IP in Cloud DB but the SystemVM guest did not receive the IP address. The UI mixed DB-derived endpoint evidence with runtime-derived endpoint evidence and also collapsed a wildcard `0.0.0.0` Ganesha listener to the primary service IP before expansion. | Implemented: protocol activation registers the DB secondary IP, applies the guest address through QGA `network endpoint apply`, then applies protocol desired state; failure rolls back a newly-created secondary-IP row. The SystemVM persists endpoint state and the monitor reconciles it before collecting cache. UI keeps `0.0.0.0` as wildcard evidence and expands it to the merged active service endpoint list. Validation/build evidence: focused `git diff --check`, `bash -n` for `ablestack-storagectl`/`ablestack-storage-monitor`, locale JSON validation, backend Maven `install`, and UI build passed. UI bundle `js/app.77cd7f6a.js` was deployed to both UI roots; `StorageServiceManagerImpl.class` SHA-256 `e27f65e6b07a581fa610d926c61209fbde89ae6980698634f79331811cedcc1f` was patched into both management jars; `WEB-INF` was restored and `/client/api` returns HTTP 401. SystemVM template build succeeded and was uploaded to `http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606110206.qcow2.bz2`, SHA-256 `890232263d1538c1eb73c9a7aac678e2094887c6f8d2bd671da421405d62e93e`, HTTP 200. | Fixed / Download Ready |

| TC04A-20260612-02 | 2026-06-12 | Systemd-owned Ganesha endpoint runtime boot persistence | Critical | SystemVM / boot reconcile / NFS managed Ganesha lifecycle | Running Storage Service VM `i-2-535-VM` showed that `ablestack-storage-reconcile.service` could start NFS successfully during boot, but Ganesha exited immediately after the oneshot unit completed. The Ganesha log showed `NFS SERVER INITIALIZED` followed by `NFS EXIT: regular exit`, and `ss -ltnp` showed no 2049 listener. | `ganesha.nfsd` was launched as a direct child of the reconcile/QGA command. In a systemd oneshot cgroup this process is not a durable service owner; the reconcile command can finish successfully while systemd cleans up the child process. | Hot-patch validation passed on `i-2-535-VM`: added `ablestack-storage-ganesha@.service`, changed `ablestack-storagectl` to restart per-listener systemd units, rebooted the VM, and confirmed `ablestack-storage-ganesha@0.0.0.0_2049.service` remained active with 2049 listening. WSL mounted `10.10.254.217:/nfs01` and write succeeded. After adding endpoint port `2050` and export `nfs02`, a second reboot restored both `0.0.0.0_2049` and `0.0.0.0_2050` units, restored `/export/nfs01` and `/export/nfs02`, monitor cache reported `ok`, and WSL mount/write passed for `nfs01` on 2049 and `nfs02` on 2050. This must be promoted into SystemVM source and template. | Validated / Implementing In Template |

TC04A-20260612-02 template promotion note: the hot-patched systemd-owned
Ganesha runtime was promoted into SystemVM source. blestack-storagectl now
starts each listener through blestack-storage-ganesha@<listener-key>.service
and stops stale endpoint units during desired-state reapply. The template build
gate now verifies the managed Ganesha unit, endpoint unit naming, and systemd
restart path during image provisioning. Build evidence: focused syntax checks
and git diff --check passed for the touched SystemVM/doc files; stale
appliance build state was removed before rebuilding; 	ools/appliance/build.sh
systemvmtemplate 4.22.0.0 x86_64 completed successfully; bzip2 integrity
passed. Download URL:
http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606121159.qcow2.bz2.
SHA256:
688b38078e40d6266b4de0165fda87c97d20a2784b1244e4285fc9d63ce09288.
HTTP HEAD returned 200 OK with Content-Length: 586743942. Status:
Download Ready / Ready for Retest.

| TC04A-20260612-04 | 2026-06-12 | NFS endpoint display and current-instance runtime API scope | High | UI/API / NFS tab status, tables, sessions | A healthy NFSv4-only service showed internal wildcard listeners such as `0.0.0.0:2049` in the status summary, and `listStorageServiceSessions` could return stale Storage Service instances for older SharedFS VMs. Wide NFS tables also showed action buttons with inconsistent alignment and fixed-column scroll overlay. | UI must hide wildcard listener IPs and expand them to real service IPs; session runtime APIs must be scoped to the active Storage Service instance for the viewed SharedFS; NFS action columns must be fixed-right, right-aligned, and dark-mode-safe. | Implemented in API/server/UI. Retest by opening the NFS tab for a service with multiple listener ports and secondary IPs, confirming no `0.0.0.0` endpoint is visible, `listStorageServiceSessions` returns only the current instance, and action buttons remain readable while horizontally scrolling each NFS table. | Ready For Retest |

## Release Readiness Criteria

The feature is ready for the next integration step only when:

1. P-00 through P-08 pass in the target `ablestack-diplo` environment.
2. TC-01 through TC-12 pass as UI-led workflows in at least one real
   `ablestack-diplo` environment, or in the approved `ablestack-europa`
   4.22-compatible development environment before backport/final validation.
3. Each UI-led pass includes backend evidence from async job result, API state,
   QGA/SystemVM state, and client behavior where applicable.
4. Each UI-led pass includes look-and-feel evidence for normal mode, dark mode,
   viewport fit, i18n, long-value handling, and operator-facing feedback where
   the screen or dialog is part of the workflow.
5. All High/Critical functional and look-and-feel defects are fixed and
   retested.
6. Remaining limitations are documented in this file and in operator-facing API
   or UI messages.
7. SPDK remains gated until VM Runtime Capability support is implemented.
| TC04A-20260611-02 | 2026-06-11 | NFSv4-only IP-specific export exposure is not supported by current Ganesha runtime | High | UI/API/SystemVM / NFS listener group model | Runtime checks on `i-2-521-VM` showed an export rendered for `10.10.22.202:2050` was also reachable from `10.10.22.203:2050` and `10.10.254.18:2050`. `nfs01` remained absent from the `2050` export set, so export separation happened by port, not by IP. | The previous UI/API model allowed users to choose per-export listen IPs, but NFS-Ganesha in the current SystemVM does not enforce that isolation with `Bind_addr` alone. This caused automatic exposure on newly added IPs for the same port and misleading UI state. | Standard changed to listener group ports: Dual Mode is service-wide `2049`, and NFSv4-only exports are assigned to listener group ports. New code stores `listenerGroupPorts`, renders Ganesha by port, and updates UI to show accessible endpoints as all active service IPs for each selected port. Retest requires fresh NFSv4-only service with two ports and two service IPs. | Fixed / Retest Required |

### Build and deployment evidence - 2026-06-11 NFS listener group update

- API/server compile: mvn -pl api,server -am ... compile passed.
- UI build: NODE_OPTIONS=--openssl-legacy-provider npm --prefix ui run build passed with existing bundle size warnings only.
- Management UI/API deployment: /client/ returned HTTP 200 and /client/api returned HTTP 401 for an unsigned request, confirming both static UI and API servlet paths are alive.
- SystemVM template build: 	ools/appliance/build.sh systemvmtemplate 4.22.0.0 x86_64 completed successfully.
- Download URL: http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606111104.qcow2.bz2.
- SHA256: c49fd8df80077da5f17c0c21019018c0c0060840afc7cbe7df66588507b1c57e.
- Retest focus: create one NFSv3+v4 dual service and one NFSv4-only service, then verify UI listener group display and mount/write behavior per mode.

| TC04A-20260611-03 | 2026-06-11 | NFS listener port persistence regression | Critical | API/SystemVM / NFSv4-only listener group persistence | In service `c2cb676b-f77a-4fce-987a-671c0fa2e7f1`, adding endpoint `10.10.22.201:2050` caused the original `2049` listener to stop. DB inspection showed only one NFS `storage_service_protocol` row (`10.10.22.201:2050`), while the original wildcard/default `2049` listener was not preserved. Existing export `nfs01` had legacy `endpointMode=SELECTED` without `listenerGroupPorts`, so the SystemVM renderer fell back to the new default port `2050` and rendered only `0.0.0.0_2050.conf`. | Initial and later NFS listener ports must both be first-class persisted endpoint rows. `enableStorageServiceProtocol` must not update an unrelated NFS row selected only by `instance_id + protocol`; `applyNfsDesiredState` must send all enabled listeners; legacy export JSON must be normalized to explicit `listenerGroupPorts`. | Implemented and deployed: DAO now supports protocol lists by instance/protocol; NFS enable finds endpoints by `listenIp + port`; desired-state payload includes all listeners and preserves `2049` as fallback when present; SharedFS compatibility sync preserves the default 2049 row and does not overwrite additional listener rows; legacy `ALL/SELECTED/listenIps` export JSON is normalized to `LISTENER_GROUP` with explicit `listenerGroupPorts` before apply. Build evidence: `git diff --check` passed, API/engine-schema/server Maven compile passed, locale JSON validation passed, UI build passed with existing bundle-size warnings only. Deployment evidence: UI deployed to `/usr/share/cloudstack-management/webapp` and `/usr/share/cloudstack-ui`; `cloud-server`, `cloudstack`, and `cloud-engine-schema` jars were patched and backed up under `/root/codex-backups/storage-listener-persist-20260611143356`; `mold.service` active, `/client/` HTTP 200, unsigned `/client/api` HTTP 401. No SystemVM template rebuild was required because SystemVM runtime code did not change. | Fixed / Ready For Retest |

| TC04A-20260611-04 | 2026-06-11 | NFS listener port group not selectable after protocol activation | High | API/UI / NFSv4-only listener group UI source | A V4-only service had persisted NFS listener rows for `2049` and `2050`, but the export create dialog displayed only the `2049` listener group. Guest runtime showed only `2049` listening because no export was assigned to `2050` yet, which is valid. | UI choices were derived from runtime listeners and export configs, so a valid but unused listener row was invisible. | Validation requirement: after enabling a new NFS listener port, `listStorageServiceProtocols` must show the listener row and the NFS export create/edit dialogs must offer that port group before any export uses it. Creating an export with that port group must then start the matching Ganesha listener and pass NFSv4 mount/write validation. Dual Mode must still hide per-export port group selection and remain service-wide `2049`. | Ready For Retest |

| TC04A-20260611-05 | 2026-06-11 | NFSv4-only multi-port Ganesha auxiliary RPC collision and failed export rollback | Critical | SystemVM/API / NFSv4-only listener group runtime / create rollback | Creating the third export `nfs03` on SharedFS `90d09bba-83d1-48c8-a096-87b09bee2c49` failed while applying the NFS desired state. Guest inspection of `i-2-530-VM` showed `nfs01` on `2049` and `nfs02` on `2050` were running and locally mount/write capable, but `nfs03` was left in DB state `Error`, its new volume was expunged, `/export/nfs03` remained on the root filesystem, and the Ganesha log showed `RQUOTA tcp6 socket ... Address already in use` followed by `Error binding to V6 interface`. | The V4-only per-port Ganesha renderer set the NFS port but did not disable auxiliary RPC services that are unnecessary for NFSv4-only exports. Multiple managed Ganesha processes can therefore collide on shared RQUOTA/NLM/UDP/IPv6 bindings. The NFS export create failure path also preserved an operational Error row instead of rolling back the just-created export and guest mount state. | Implemented and built: V4-only Ganesha configs render TCP-only NFSv4 with `Enable_NLM=false` and `Enable_RQUOTA=false`, while Dual Mode remains unchanged. NFS export create now removes the newly-created export/ACL rows on apply failure and, when cleanup is requested for a newly-created backing volume, runs guest `volume detach prepare` before detaching/destroying the volume. Backend/UI were deployed to `10.10.22.10`; SystemVM template build completed as `systemvmtemplate-4.22.0.0-x86_64-kvm-202606112245.qcow2.bz2` and is downloadable from `http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606112245.qcow2.bz2` with SHA256 `5a545b5c515ef1bdeece314891f9d26234c9641b3abdca38dc34467792c8724b`. Runtime retest still requires a new Storage Service VM created from this template. | Ready for Retest |

| TC04A-20260612-01 | 2026-06-12 | NFS runtime does not recover after Storage Service SystemVM reboot | Critical | SystemVM / boot reconcile / NFS managed Ganesha runtime | `bd09b9f1-d2cb-4a8c-81ce-cea82c25030b` on `i-2-532-VM` was healthy with managed Ganesha processes listening on `2049`, `2050`, and `2051`, and local NFSv4 mount/write probes passed for `nfs01`, `nfs02`, and `nfs03`. `5608dca4-95a6-4fd1-a7c1-f90544ccf3d3` on rebooted `i-2-531-VM` had the backing volume and `/export/nfs01` bind mount restored from fstab, but no managed Ganesha process, no NFS listener, and local mount probe failed. | `ablestack-storage-monitor.service` is enabled and active after reboot, but it only collects runtime cache and reconciles network endpoints. No boot-time path reapplies the last successful NFS desired state, so DB `Ready` can drift from runtime `degraded`. | Implemented and built: successful NFS apply persists the last good payload to `/etc/ablestack-storage/desired-state/nfs-export-apply.json`. New `ablestack-storage-reconcile.service` runs before the monitor service at boot, performs `mount -a`, reconciles network endpoints, reapplies the saved NFS payload through `ablestack-storagectl nfs export apply`, retries during boot settle, and refreshes monitor cache on success. Validation evidence: `bash -n` and `git diff --check` passed. SystemVM template build completed as `systemvmtemplate-4.22.0.0-x86_64-kvm-202606120125.qcow2.bz2`; bzip2 integrity passed; downloadable URL is `http://10.10.22.10:8000/systemvmtemplate-4.22.0.0-x86_64-kvm-202606120125.qcow2.bz2`; SHA256 is `3e6d1a64e0d3f30142cf0069f99c61933363cf57bfe0dfd4c7345b7ca17f9234`. Retest requires creating a fresh Storage Service VM from the new template, verifying mount/write, rebooting the SystemVM, then confirming listeners and mount/write recover without manual QGA apply. | Download Ready / Ready for Retest |
## SMB Empirical Validation - 2026-06-12

### SMB-20260612-01 - Local SMB Coexistence Baseline

- Date: 2026-06-12
- Target: running Storage Service System VM `i-2-536-VM` on host `10.10.22.1`
- Existing service state before test:
  - NFS Ganesha was already running with managed listeners on `*:2049` and `*:2050`.
  - Storage monitor cache was active.
  - Samba binaries were present in the System VM: `smbd`, `smbpasswd`, `pdbedit`, and `smbclient`.
- Test scope:
  - AD domain authentication was intentionally excluded.
  - A temporary local SMB user `smbcodex` and temporary test share `/export/smb-codex-test` were created only for this validation.
  - A standalone temporary Samba config `/tmp/ablestack-smb-test.conf` was used so the existing Storage Service state was not overwritten.
- Results:
  - Samba `smbd` started successfully while NFS Ganesha remained active.
  - SMB listened on `0.0.0.0:445` and `[::]:445`; NFS listeners on `2049` and `2050` remained active.
  - `smbclient -L //127.0.0.1 -U smbcodex%... -m SMB3` listed the temporary share successfully.
  - Initial SMB write failed with `NT_STATUS_ACCESS_DENIED` while the share directory was `root` owned with mode `0775`.
  - After changing the directory owner/group to the SMB local user and applying mode `0770`, SMB write/read succeeded through `smbclient`.
  - NFSv4 mount checks through the actual service IP `10.10.254.225` still succeeded for `nfs01` on port `2049` and `nfs02` on port `2050`.
  - Samba also accepted `smb ports = 445 1445`; both ports listened, and `smbclient -p 1445` could access the share.
  - Test artifacts were cleaned up after validation: temporary smbd process stopped, `smbcodex` removed from Samba/Linux user databases, and the temporary share/config files deleted. NFS listeners remained active after cleanup.
- Design conclusions:
  - SMB can coexist with the current NFS Ganesha runtime in the same Storage Service System VM.
  - Local SMB mode requires both Samba account state and POSIX directory ownership/permission management; share creation must not rely on root-owned backing directories.
  - SMB share create/update workflows must include owner UID/GID or named local user/group mapping, directory mode, and create-directory behavior, similar to the NFS POSIX permission section but centered on SMB users/groups.
  - SMB protocol activation can support additional ports at the Samba level, but the operator UI should treat port customization as an advanced endpoint/service option because normal Windows UNC access does not express a port in `\\host\share`.
  - SMB monitoring must read managed Storage Service SMB config/state, not only distro-default Samba shares such as `homes`, `printers`, or `print$`.
  - The initial implementation should use standalone/local-user SMB first; AD domain join should be added as a separate authentication mode after local mode is stable.

### SMB-AD-20260613-01 - AD Member Server Join And SMB Access Probe

- Date: 2026-06-13
- Target: running Storage Service System VM `i-2-536-VM` on host `10.10.22.1`
- AD test inputs:
  - Domain FQDN: `ablestack.local`
  - NetBIOS domain: `ABLESTACK`
  - AD DNS/DC IP: `10.10.254.227`
  - SRV-discovered DC name: `adsvr.ablestack.local`
  - Requested test user/group: `ablecloud`, `Domain Users`
- Existing service state before test:
  - NFS Ganesha was active on ports `2049` and `2050`.
  - AD-related tools were present in the System VM: `net`, `wbinfo`, `winbindd`, `realm`, `adcli`, `kinit`, `host`, `dig`, and `nslookup`.
- Connectivity results:
  - Direct DNS queries against `10.10.254.227` resolved `ablestack.local` and SRV records for LDAP/Kerberos.
  - The provided `adsvr.ablecloud.local` name resolved to an external address, so the correct AD DC name for implementation is the SRV-discovered `adsvr.ablestack.local`.
  - TCP probes to the DC succeeded for DNS `53`, Kerberos `88/464`, RPC `135`, LDAP `389`, SMB `445`, and Global Catalog `3268`.
  - Time offset reported by Samba AD lookup was about `-2` seconds, which is acceptable for Kerberos.
- Join results:
  - Initial join failed because the System VM hostname exceeded the NetBIOS 15-character limit.
  - A short managed NetBIOS name `STOR536TST` allowed the AD join to proceed.
  - Kerberos `kinit` for the join account succeeded.
  - `net ads join` succeeded after temporarily using the AD DNS server as the resolver.
  - `net ads testjoin` returned `Join is OK`.
  - `wbinfo --ping-dc` and `wbinfo -t` succeeded after applying the AD member config as the active Samba config for the probe.
  - `wbinfo -u` found `ablecloud`; `wbinfo -g` found `domain users`; `wbinfo --user-info=ablecloud` and `wbinfo --group-info="Domain Users"` returned Unix ID mappings.
- SMB access results:
  - Authentication for `ABLESTACK\ablecloud` succeeded with both plaintext and challenge/response checks after the test user's password was supplied at runtime.
  - `wbinfo --user-info=ablecloud` mapped the user to UID `11104` and primary GID `10513`.
  - `wbinfo --group-info="Domain Users"` mapped the group to GID `10513`.
  - A share limited to `@"ABLESTACK\Domain Users"` was writable by `ablecloud` after the share directory was owned by `root:10513` with mode `0770`.
  - `smbclient //127.0.0.1/smbadtest -U ABLESTACK\ablecloud%... -m SMB3` uploaded and downloaded `remote-ablecloud.txt` successfully.
  - An intermediate attempt with fully isolated temporary Samba `private/lock/state/cache/pid` directories joined the domain and resolved identities, but `smbd` did not open TCP `445`; using Samba's normal persistent state directories allowed `smbd` to listen on `0.0.0.0:445` and `[::]:445`. The implementation must therefore manage persistent Samba state deliberately rather than treating AD member state as throwaway per-command state.
- NFS coexistence:
  - After the AD member Samba probe, NFSv4 mount checks still succeeded for `10.10.254.225:/nfs01` on port `2049` and `10.10.254.225:/nfs02` on port `2050`.
- Cleanup:
  - The temporary AD computer account `STOR536TST` was removed with `net ads leave`.
  - Temporary Samba/winbind processes, temporary AD Samba/Kerberos config, and the temporary share directory were removed.
  - `/etc/resolv.conf` and `/etc/samba/smb.conf` were restored.
  - Post-cleanup listener state showed only the original NFS listeners on `2049` and `2050`.
- Design conclusions:
  - AD support is feasible in the current System VM package set, but the implementation must explicitly manage AD DNS resolver behavior during join and runtime.
  - The Storage Service must generate a stable NetBIOS name no longer than 15 characters rather than using the long System VM hostname.
  - AD member mode should use Samba/winbind with explicit idmap ranges and managed keytab/private state.
  - UI/API must separate AD join credentials from SMB share user credentials; join credentials do not prove application-user access.
  - Domain group authorization is viable when the Samba ACL and POSIX group ownership are aligned to the winbind idmap result.
  - AD join/leave and SMB desired-state application can coexist with the existing NFS runtime when applied through isolated Samba/winbind state.

### SMB-AD-20260613-02 - Local And AD SMB Share Coexistence Probe

- Date: 2026-06-13
- Target: running Storage Service System VM `i-2-536-VM` on host `10.10.22.1`
- Purpose:
  - Verify whether local-account SMB shares and AD-authenticated SMB shares can be served by the same Samba runtime.
  - Confirm the operator-visible constraints before implementing the SMB UI/API model.
- Test setup:
  - Samba was configured as an AD member server with managed NetBIOS name `STOR536MIX`.
  - A temporary local Samba/Linux user `smbmixlocal` was created.
  - Temporary local share: `smbmixlocal`, backed by `/export/smb-matrix-local`.
  - Temporary AD shares used variants of AD group/user authorization, backed by `/export/smb-matrix-ad`.
  - `winbindd` and `smbd` were started while existing NFS Ganesha listeners stayed active.
- Results:
  - AD join, `net ads testjoin`, winbind DC ping, trust check, AD user lookup, AD group lookup, and AD user password authentication all succeeded.
  - `smbd` listened on TCP `445` while NFS Ganesha remained active on `2049` and `2050`.
  - Local SMB access with an unqualified username failed: `smbmixlocal` returned `NT_STATUS_LOGON_FAILURE`.
  - Local SMB access succeeded when the local account was qualified with the Samba server NetBIOS name: `STOR536MIX\smbmixlocal`.
  - AD SMB access succeeded for `ABLESTACK\ablecloud` when the share allowed either `@"ABLESTACK\Domain Users"`, `@"domain users"`, or the explicit AD user.
  - AD share writes landed with winbind-mapped ownership `ablecloud:domain users`.
  - Local share writes landed with local ownership `smbmixlocal:smbmixlocal`.
  - The local account was denied access to the AD-only share, which confirms that local and AD authorization boundaries can be preserved in one Samba runtime.
- Cleanup:
  - The temporary AD computer account was removed with `net ads leave`.
  - Temporary Samba/winbind processes, local test user, test shares, resolver changes, and Samba private/config state were restored.
  - Post-cleanup listener state showed only the original NFS listeners on `2049` and `2050`.
- Design conclusions:
  - Local SMB and AD SMB shares can coexist in one Storage Service VM and one Samba runtime.
  - In AD member mode, local SMB users are still usable, but client access must qualify the local account with the managed server NetBIOS name.
  - The UI must show the local-login form as `<server-netbios>\<user>` in AD member mode and should not imply that bare local usernames will authenticate.
  - SMB share ACLs must distinguish local principals from AD principals and render them differently in generated `smb.conf`.
  - Directory ownership and mode must be applied per share according to the selected principal type: local Linux/Samba user or winbind-mapped AD UID/GID.

### SMB-NFS-20260613-01 - Reuse Existing NFS Export Directory As SMB Share

- Date: 2026-06-13
- Target: running Storage Service System VM `i-2-536-VM` on host `10.10.22.1`
- Purpose:
  - Verify whether a directory already exposed as an NFS export can also be exposed as an SMB share.
  - Confirm the permission model required for cross-protocol access to the same backing directory.
- Existing service state before test:
  - NFS Ganesha was active on ports `2049` and `2050`.
  - Existing NFS export directory `/export/nfs01` was mounted from the data volume as `xfs`.
  - `/export/nfs01` was owned by `nobody:nogroup` with mode `0775`.
- First probe:
  - A temporary SMB share was pointed at `/export/nfs01` with `force user = nobody` and `force group = nogroup`.
  - SMB tree connect failed with `NT_STATUS_INVALID_SID`.
  - This confirms that forcing an SMB session to a system account that is not a managed Samba principal is not a valid implementation strategy.
- Successful probe:
  - A temporary local Samba/Linux user `smbnfsreuse` was created.
  - A temporary POSIX ACL granting `smbnfsreuse` write access to `/export/nfs01` was applied.
  - Temporary SMB share `nfs01reuse` was exposed with `path = /export/nfs01`.
  - SMB write to the reused NFS export directory succeeded.
  - The SMB-created file was visible directly on `/export/nfs01`.
  - The same NFS export was mounted through the service IP as `10.10.254.225:/nfs01`, and the SMB-created file was visible through the NFS mount.
  - A file written through the NFS mount was visible on `/export/nfs01` and readable through the SMB share.
  - Existing NFS listeners on `2049` and `2050` remained active while the temporary SMB share was running.
- Cleanup:
  - The temporary SMB process, local test user, POSIX ACL, test files, and temporary Samba config were removed.
  - Post-cleanup listener state showed only the original NFS listeners on `2049` and `2050`.
- Design conclusions:
  - Reusing an NFS export directory as an SMB share is technically feasible.
  - The Storage Service must treat this as an explicit cross-protocol share mode, not as an accidental path collision.
  - SMB must authenticate as a managed local or AD principal and the backend directory must grant that principal POSIX access through ownership, mode, or POSIX ACLs.
  - The implementation must not use `force user = nobody` as the default cross-protocol mapping strategy.
  - UI/API should warn that NFS and SMB clients will see the same files and that ownership/permission behavior is governed by the selected cross-protocol POSIX mapping.
  - Monitoring should show that the same backing path is exported by multiple protocols so operators can understand cross-protocol data visibility.

### SMB-AD-20260613-03 - Create-Time AD Join Failure And Owner Dark-Mode Defect

- Test target:
  - SharedFS UI: `/sharedfs/9a8a3d8d-8c31-45a5-95d2-51b77734100e?tab=nfs` and `?tab=smb`
  - Storage Service VM: `i-2-539-VM` on `10.10.22.1`
  - Selected services: NFS and SMB with AD authentication
- Observed result:
  - SharedFS row reached `Ready` and the Storage Service instance reached `Running`.
  - NFS protocol, SMB protocol, initial NFS export, and initial SMB share rows were present and `Ready` in DB.
  - VM runtime had Ganesha listening on TCP 2049 and Samba `smbd` listening on TCP 445.
  - Samba remained in local `WORKGROUP` / `security = user` mode.
  - `net ads testjoin` and `wbinfo -t` failed because the AD join never completed.
  - Management log reported `NameError: name 're' is not defined` from the AD join command.
  - The create modal owner selector had a dark-mode visual mismatch against the rest of the Storage Service modal.
- Root cause:
  - `smb_domain_join()` uses an embedded Python block that calls `re.sub` and `re.split` but does not import `re`.
  - AD join failure is currently surfaced as a partial initial-service failure, while the SMB protocol/share rows can still appear healthy.
- Required fix:
  - Add the missing `import re` and add a syntax/import validation gate for embedded Python snippets used by `ablestack-storagectl`.
  - Make AD join phase-oriented and verify `testparm -s`, `net ads testjoin`, and `wbinfo -t` before persisting a successful AD state.
  - Expose failed AD trust as an SMB identity warning or failed identity state in the SMB tab and monitor cache.
  - Keep AD join retryable from the SMB tab without deleting the SharedFS or SMB share.
  - Align `.sharedfs-create-owner` dark-mode styles with the main Storage Service collapse section palette.
- Retest requirements:
  - Create a fresh NFS+SMB AD SharedFS from the UI.
  - Confirm no partial-service toast appears after create when AD credentials and DNS are valid.
  - Confirm `/etc/samba/smb.conf` contains `security = ADS`, expected realm/workgroup, and managed NetBIOS name.
  - Confirm `smbd` and `winbind` are active, TCP 445 is listening, `net ads testjoin` succeeds, and `wbinfo -t` succeeds.
  - Confirm AD user or AD group access can read/write the created SMB share according to the configured ACL.
  - Confirm the owner selector and SMB AD fields remain readable in Korean dark mode.

### SMB-AD-STOP-20260613-04 - AD NetBIOS Derivation And SharedFS Stop SQL Guard

- Test target:
  - SharedFS UI: `/sharedfs/60d87642-da40-4819-8549-d6c4e340abad?tab=nfs` and `?tab=smb`
  - Storage Service VM: `i-2-540-VM` on `10.10.22.2`
  - Selected services: NFS and SMB with AD authentication
- Observed result:
  - SharedFS reached `Ready` and the Storage Service instance reached `Running`.
  - NFS Ganesha listened on TCP 2049; Samba `smbd`, `nmbd`, and `winbind` were active and TCP 445 was listening.
  - Kerberos credential validation for the AD join account succeeded and AD DNS SRV lookup resolved the domain controller.
  - Manual `net ads join` failed because Samba had `workgroup = WORKGROUP`, while the AD domain requires the NetBIOS domain `ABLESTACK`.
  - `testparm` also reported an invalid idmap configuration because the default idmap range was missing.
  - Stopping SharedFS from the UI produced an invalid SQL query ending in `shared_filesystem_view.id IN )`.
- Root cause:
  - The backend stored `WORKGROUP` when the operator did not explicitly provide a NetBIOS workgroup, even for AD mode.
  - The SystemVM trusted that value and rendered AD Samba config without a default idmap range.
  - The SharedFS response DAO did not guard empty ID lists before building an `IN` query.
- Required fix:
  - Derive AD NetBIOS workgroup from the AD FQDN first label when the workgroup is blank or still `WORKGROUP`; for example `ablestack.local` becomes `ABLESTACK`.
  - Let the SystemVM re-check `net ads lookup` after AD DNS is applied and prefer the discovered Pre-Windows 2000 domain name before `net ads join`.
  - Render `idmap config * : backend = tdb` and `idmap config * : range = 10000-999999` for AD member mode.
  - Write a minimal Kerberos config before joining.
  - Return an empty response list instead of creating invalid SQL when a SharedFS response lookup receives no IDs.
  - Pass the operator's forced-stop flag through the Storage VM lifecycle stop path.
- Retest requirements:
  - Create a fresh NFS+SMB AD SharedFS with the AD FQDN and no manually edited NetBIOS value.
  - Confirm `smb.conf` contains `workgroup = ABLESTACK`, `realm = ABLESTACK.LOCAL`, and the idmap range.
  - Confirm `net ads testjoin` and `wbinfo -t` succeed.
  - Confirm SMB AD user or group access works.
  - Stop the SharedFS from the UI and confirm no `IN )` SQL error appears.

### SMB-AD-20260613-05 - Kerberos-first AD join and domain-scoped winbind verification

- Test target:
  - SharedFS UI: `/sharedfs/66c51f5a-3b47-42c4-8504-87f9542b072e`
  - Storage Service VM: `i-2-541-VM` on `10.10.22.1`
  - Selected services: NFS and SMB with AD authentication
- Observed result:
  - QGA was available and the VM had `10.10.254.192/16`.
  - `/etc/resolv.conf` pointed to AD DNS `10.10.254.227`.
  - `_ldap._tcp.ablestack.local` resolved to `adsvr.ablestack.local`.
  - `adsvr.ablecloud.local` resolved to an external address and must not be
    treated as the canonical AD DC hostname for this environment.
  - `kinit Administrator@ABLESTACK.LOCAL` succeeded, proving the supplied AD
    join password was valid.
  - Manual `net ads join -U Administrator%<password>` succeeded on the same
    VM, proving the DC, DNS, and account were usable.
  - After joining and restarting winbind, `net ads testjoin` succeeded and
    `wbinfo --domain=ABLESTACK -t` succeeded.
  - Plain `wbinfo -t` may test the wrong local workgroup context and can report
    a false trust failure.
  - `/etc/ablestack-storage/smb-domain-error.json` can remain stale after a
    manual or partially successful join unless the product command removes it.
- Root cause:
  - The SystemVM AD join path attempted `net ads join` directly without a
    Kerberos credential preflight, so credential, DNS, and join failures were
    not separated.
  - The post-join trust check used unscoped `wbinfo -t`, which can check the
    wrong workgroup context.
  - Success and failure state files were not managed as an atomic identity
    operation.
- Required fix:
  - Normalize username to UPN form before Kerberos validation.
  - Run `kinit <user>@<REALM>` first and report `phase=kinit` on failure.
  - Join with `net ads join -k` after Kerberos validation.
  - Restart Samba/winbind and verify with `net ads testjoin` and
    `wbinfo --domain=<WORKGROUP> -t`.
  - Remove stale `smb-domain-error.json` only after final trust verification
    succeeds.
  - Keep AD join retryable from the SMB tab and never store the password.
- Retest requirements:
  - Create a fresh NFS+SMB AD SharedFS from the UI.
  - Confirm no partial-service toast appears when AD credentials are valid.
  - Confirm `smb-domain.json` contains `state=JOINED`, `trustVerified=true`,
    `realm=ABLESTACK.LOCAL`, and `workgroup=ABLESTACK`.
  - Confirm `smb-domain-error.json` is absent after success.
  - Confirm `net ads testjoin`, `wbinfo --domain=ABLESTACK -t`, AD user
    enumeration, and AD user SMB access work.

### SMB ACL/AD 접근 검증 보강

- SMB AD 생성 시 초기 ACL 기본값(`AD_GROUP / Domain Users / READ_WRITE`)이 API 요청에 포함되는지 확인한다.
- SystemVM에서 `/etc/ablestack-storage/smb-access.json`의 `accessState`가 `acl_applied`로 기록되는지 확인한다.
- AD 사용자로 `smbclient` 또는 CIFS 마운트를 수행해 공유 목록 조회, 파일 생성, 읽기, 삭제가 가능한지 확인한다.
- ACL 삭제 후 재적용 시 같은 사용자가 접근 거부되는지 확인해 POSIX ACL 잔류 여부를 검증한다.
- 게스트 접근이 꺼지고 ACL이 없는 공유는 생성 가능하지만 `no_acl` 상태와 접근 실패가 동시에 확인되어야 한다.

### SMB AD User Principal Regression

- Purpose: verify that SMB AD join credentials are not reused as SMB share ACLs
  and that an operator-selected AD user remains an `AD_USER` rule end to end.
- Setup: create or update an SMB share with AD identity mode, AD join account
  credentials, initial access principal type `AD_USER`, principal `ablecloud`,
  and read/write permission.
- Expected API/desired state: the SMB ACL row and SystemVM desired-state JSON
  contain `principalType: AD_USER` and `principal: ablecloud`; `Domain Users`
  appears only when the operator explicitly selected `AD_GROUP`.
- Expected SystemVM runtime: `smb.conf` renders user principals as quoted users
  and group principals as `@` plus a quoted group body, for example
  `valid users = "ABLESTACK\ablecloud"` for a user or
  `valid users = @"ABLESTACK\Domain Users"` for a group.
- Expected client result: an AD user granted by `AD_USER` can list, write, read,
  and delete files on the SMB share. A group ACL is accepted only when the user
  is a member of that group and the ACL type is `AD_GROUP`.

### SMB post-validation checks

- Create an SMB share on a currently attached managed backing volume. PASS requires the backend to reuse the existing managed mount path without attempting unsafe block-device discovery.
- Create SMB shares with an existing detached volume and with a new volume. PASS requires volume attach, mount persistence, Samba share rendering, and no accidental open access when ACLs are absent.
- Mount an ACL-enabled SMB share from the WSL client and keep it mounted while checking the SMB tab. PASS requires the session table to show the authenticated user, share name, SMB dialect, client endpoint, state, and connection time from the monitoring cache.

### NFS Ganesha endpoint runtime regression check

- Create a fresh SharedFS with NFS enabled and confirm no partial service configuration error is shown.
- In the Storage Service VM, confirm `/run/ganesha`, `/run/ablestack-storage/ganesha`, and `/etc/ganesha/ablestack-storage` exist after NFS apply.
- Confirm `systemctl status ablestack-storage-ganesha@<endpoint>.service` is active for every rendered endpoint.
- Confirm `ss -lntp` shows the configured NFS listener port and `ganesha.nfsd` owns the socket.
- Confirm `/run/ablestack-storage/ganesha/<endpoint>.log` does not contain `open(/var/run/ganesha/ganesha.pid` or other fatal pid-file errors.
- Reboot or stop/start the Storage Service VM and confirm boot reconcile restores the same Ganesha endpoint units without manual directory creation.

## iSCSI CHAP and Non-default Port Validation Notes

### Scope

This validation records the runtime assumptions used for the iSCSI implementation alignment.

### Results

| Scenario | Result | Evidence |
| --- | --- | --- |
| Existing iSCSI default port target | PASS | LIO target on TCP 3260 can be discovered and logged in from the WSL initiator. |
| Non-default iSCSI port | PASS with target binding | Temporary `10.10.22.202:3261` LIO portal was created on the running Storage Service VM and discovery/login succeeded from WSL. |
| Candidate endpoint without target binding | Not a valid listener | LIO exposes ports from target TPG portals, not from standalone protocol endpoint records. |
| CHAP with only stored config | FAIL | CHAP target config existed, but LIO had `authentication=0` or missing ACL password. |
| CHAP after runtime correction | PASS | Setting ACL `userid/password` and TPG `authentication=1` allowed WSL CHAP login and ext4 mount/write. |

### Required Regression Checks

1. Create iSCSI target on port 3260 and verify discovery/login.
2. Add candidate endpoint port 3261 and verify it does not require listen until a target selects it.
3. Create/update a target selecting listener port group 3261 and verify LIO portal creation.
4. Create CHAP ACL with username/secret and verify targetcli ACL auth plus `authentication=1`.
5. Verify CHAP login from WSL and block read/write or filesystem mount/write depending on LUN contents.

## iSCSI Runtime Session Cache Regression (2026-06-28)

### Scope

This regression check covers the case where the iSCSI service, targetcli state,
initiator login, and write path are healthy, but the Storage Service UI still
shows no active session because the SystemVM monitoring cache did not preserve
the observed iSCSI TCP sessions.

### Reference Failure

| Item | Observed value |
| --- | --- |
| SharedFS | `779826e0-f5de-4a95-bb0f-00cb40c0cd6c` |
| Storage Service VM | `i-2-566-VM` on `10.10.22.1` |
| Service endpoint | `10.10.254.213/16`, secondary `10.10.22.201/16` |
| Runtime listener | `0.0.0.0:3260` |
| WSL initiator | `iqn.1994-05.com.redhat:b48878fe831c` |
| Non-CHAP target | `iqn.2026-06.local.storage:tc03c01`, LUN `0` and `1` |
| CHAP target | `iqn.2026-05.local.storage:sharedfs-test01-5346`, LUN `0` |
| Functional result | Initiator login and non-destructive last-block write/restore succeeded for both non-CHAP and CHAP targets. |
| UI/runtime defect | `ss` showed established `10.10.22.201:3260` sessions, but `session-state.json` was empty and the UI displayed no sessions. |

### Required Result

- `ablestack-storagectl sessions` must return a non-empty `sessions` array when
  iSCSI TCP sessions exist on enabled listener ports.
- When target/LUN mapping is exact, each row must include `targetIqn`,
  `initiatorIqn`, `lun`, `local`, `peer`, `state`, `connectedAt`, and
  `lastSeen`.
- Exact mapping is verified from `targetcli sessions detail` plus configfs LUN
  symlink resolution, not from TCP socket inference alone.
- When target/LUN mapping is only port-based, the session must still be
  returned with `mappingStatus=candidate`, `possibleTargets`, and
  `possibleInitiators` where available.
- `sessions.json` must include `observedTcpCount`, `status`, and `warnings`
  so the UI can distinguish idle state from degraded session collection.
- The iSCSI tab must not show the normal no-session message when the runtime
  response is degraded or when observed iSCSI TCP sessions are present.

## SystemVM Template Integrity Regression (2026-06-28)

### Reference Failure

| Item | Observed value |
| --- | --- |
| Storage Service VM | `i-2-568-VM` on `10.10.22.2` |
| Template | `SystemVM Template Storage Service 202606281522`, template id `401` |
| Failure | `targetcli` failed with `ImportError: ... gi/_gi...so: invalid ELF header` |
| Root cause | The registered qcow2 in secondary storage had qcow2 refcount errors and selected package files were zero-filled. |
| Corrupted files | `python3-gi` `_gi.so`, `libmagic.so.1`, `/var/lib/dpkg/status` |

### Required Build Gate

1. `qemu-img check <kvm qcow2>` must pass.
2. `tools/appliance/scripts/validate_systemvm_image.sh <kvm qcow2>` must pass.
3. `bunzip2 -c <kvm qcow2.bz2> > <roundtrip qcow2>` must produce a qcow2 that is byte-identical to the source qcow2.
4. `qemu-img check <roundtrip qcow2>` must pass.
5. `tools/appliance/scripts/validate_systemvm_image.sh <roundtrip qcow2>` must pass.

### Required Runtime Behavior

- If a Storage Service SystemVM is somehow booted from a corrupt template,
  iSCSI apply must fail before target creation with an explicit dependency
  message:
  `SystemVM iSCSI runtime dependency is broken`.
- The user-facing error must direct the operator to rebuild and register a
  valid Storage Service SystemVM template.
- A raw `targetcli` traceback caused by broken Python GI or package files is a
  validation failure.

## iSCSI Reboot Authoritative Reconcile Regression (2026-06-29)

### Reference Failure

| Item | Observed value |
| --- | --- |
| SharedFS | `cb04e1af-7c20-4b51-80fa-f3f145851a96` |
| Storage Service VM | `i-2-569-VM` on `10.10.22.1` |
| Protocol | iSCSI only |
| Desired listeners | `0.0.0.0:3260`, `10.10.22.201:3261` |
| Desired targets | `tc03c01` LUN `0` and `1`, plus two CHAP-enabled targets |
| Pre-reboot result | WSL initiator login, mount, and write succeeded for non-CHAP and CHAP targets. |
| Reboot action | Cloud API stop/start of the Storage Service VM. |
| Failure | After boot, `rtslib-fb-targetctl` restored only part of the target set and `ablestack-storage-reconcile.service` failed with `storage object or path not valid`. |
| Root cause | `targetctl` persisted LIO backstores with volatile `/dev/sdX` paths. After reboot, disk order changed and restored backstores no longer matched ABLESTACK volume serials. Desired-state reapply also could not rebuild CHAP targets after clear because CHAP secrets are redacted from DB/API state. |

### Live Injection Validation

| Step | Result |
| --- | --- |
| Clear LIO and apply saved desired state without secrets | Failed with `missing chapSecret`, proving reboot recovery needs a SystemVM-local secret store. |
| Add `acl.secrets.chapSecret` in a root-only temporary desired state and run `targetcli clearconfig confirm=True` followed by `ablestack-storagectl iscsi target apply` | Passed. All four desired target rows were applied. |
| Runtime mapping after validated apply | `tc03c01` LUN0 mapped to serial `15ec190e9d44449bac16`, LUN1 mapped to serial `b20cc090542b465bbb85`, CHAP targets mapped to their expected serials, and ports `3260`/`3261` listened. |

### Required Result

- Boot reconcile must not trust `targetctl` restored `/dev/sdX` mappings.
- On boot, managed iSCSI targets/backstores are rebuilt from ABLESTACK desired
  state using stable volume identifiers.
- CHAP and mutual CHAP secrets are stored only in a root-only SystemVM secret
  store and merged into desired state at apply time.
- Monitor inventory must mark desired rows without matching runtime target/LUN
  mappings as degraded/error.
- Final pass requires Cloud-managed stop/start followed by targetcli, monitor,
  initiator login, mount/read/write, and UI/API consistency checks.

## iSCSI Session Attribution Prototype (2026-06-29)

### Reference VM

| Item | Observed value |
| --- | --- |
| SharedFS | `9af2ab61-9dcb-4bd2-bd40-3f89760780e1` |
| Storage Service VM | `i-2-570-VM` on `10.10.22.2` |
| Protocol | iSCSI only |
| Runtime listeners | `0.0.0.0:3260`, `10.10.22.201:3261` |
| Observed issue | Health showed only `3260`; session rows showed `mappingStatus=degraded/candidate` even though targetcli could identify exact target IQN and LUN. |

### Live Injection Result

| Probe | Result |
| --- | --- |
| Health port prototype | Returned both `3260` and `3261` as healthy listener ports. |
| `targetcli sessions detail` parsing | Returned connected initiator IQN, peer IP, targetcli SID, and mapped LUN backstore names. |
| Configfs LUN symlink mapping | Resolved each targetcli backstore to exact target IQN and LUN under `/sys/kernel/config/target/iscsi/<target>/tpgt_1/lun/lun_*`. |
| Single-portal target | Target IQN, LUN, and endpoint were exact. |
| Multi-portal target | Target IQN and LUN were exact; endpoint was candidate because the target had both `0.0.0.0:3260` and `10.10.22.201:3261`. |

### Required Result

- iSCSI health must report all desired listener ports from `iscsi-targets.json`.
- iSCSI session rows must be created from `targetcli` plus configfs, not from
  raw TCP socket target inference.
- Target/LUN exactness and endpoint exactness must be reported separately:
  `mappingStatus=exact` can coexist with `endpointMappingStatus=candidate`.
- Endpoint candidate state is informational and must not mark the session API
  as degraded unless target/LUN mapping itself is unmapped.

## NVMe-oF iSCSI-Parity Runtime Prototype (2026-06-29)

### Reference VM

| Item | Observed value |
| --- | --- |
| SharedFS | `d4ad7f40-ef61-4b35-b976-ea3ed0e96695` |
| Storage Service instance | `sharedfs-test01` / instance ID `125` |
| Storage Service VM | `i-2-571-VM` on `10.10.22.1` |
| Active persisted protocols | NFS, iSCSI |
| Persisted NVMe-oF rows | None on the active instance before the probe |
| WSL Host NQN | `nqn.2014-08.org.nvmexpress:uuid:be872b70-0df3-4c5f-9e64-56a7955dcd1a` |

### Live Injection Result

The test used QGA to inject a temporary, non-persistent NVMe-oF kernel target
configuration into the running Storage Service VM. The probe was removed after
validation.

| Probe | Result |
| --- | --- |
| Module loading | `modprobe nvmet` and `modprobe nvmet-tcp` succeeded. |
| configfs root | `/sys/kernel/config/nvmet` existed after module load. |
| Temporary subsystem | `nqn.2026-06.local.storage:codex-nvmeof-probe` was created. |
| Temporary namespace | 64 MiB loop-backed namespace `1` was enabled. |
| Listener | TCP `0.0.0.0:4420` listened successfully. |
| WSL discovery | `nvme discover -t tcp -a 10.10.254.226 -s 4420` returned the temporary subsystem. |
| WSL connect | `nvme connect` created `/dev/nvme0n1`. |
| Size check | `/dev/nvme0n1` reported `67108864` bytes. |
| Data path | Direct 4 KiB write and direct 4 KiB read both succeeded. |
| Wrong Host NQN ACL | With `allow_any_host=0` and only a bogus Host NQN allowed, WSL connect was rejected. |
| Correct Host NQN ACL | With the WSL Host NQN allowed, WSL connect and direct read/write succeeded. |
| DH-HMAC-CHAP capability | The current host configfs directory exposed no `dhchap_key` or `dhchap_ctrl_key` attributes, so DH-HMAC-CHAP remains unsupported on this template baseline. |
| Cleanup | The temporary subsystem, namespace, port link, loop device, and backing image were removed; TCP 4420 stopped listening. |

### Required Result

- NVMe-oF `KERNEL_NVMET` support must load kernel modules before capability
  detection and before monitor-cache health decisions.
- Host NQN ACL is valid and should be the supported access-control path for
  the current template baseline.
- DH-HMAC-CHAP must be disabled in the UI and rejected by API/apply unless
  SystemVM health reports `dhChapSupported=true` and the runtime request
  supplies non-persisted secrets.
- NVMe-oF namespaces should follow the iSCSI block-only volume model:
  current attached unused block volume, existing unattached volume, or newly
  created volume.
- UI-led final validation must include subsystem create, namespace create,
  Host NQN ACL create, WSL discover/connect, direct read/write, wrong Host NQN
  rejection, session visibility, and reboot reconcile.

## NVMe-oF Implementation Validation Plan (2026-06-30)

After this implementation is deployed, validate from the UI first.

| Step | Goal | Expected Result |
| --- | --- | --- |
| NVME-01 | Create NVMe-oF-only Storage Service with a new backing volume. | SystemVM reaches Ready; monitor cache reports `ok`; no initial-service warning is shown. |
| NVME-02 | Create an additional NVMe-oF namespace with current/existing/new volume paths. | Namespace table shows subsystem NQN, namespace ID, backing volume, listener port group, endpoint, and Ready state. |
| NVME-03 | Create Host NQN ACL for the WSL client. | Allowed Host NQN appears in Host ACL table; wrong Host NQN is rejected by connect test. |
| NVME-04 | Discover/connect from WSL. | `nvme discover` sees the subsystem; `nvme connect` creates a block device; direct read/write succeeds. |
| NVME-05 | Verify UI state. | Subsystem, namespace, backing volume, Host ACL, session, and status cards match runtime and DB state without raw i18n keys. |
| NVME-06 | Reboot Storage Service VM. | Reconcile restores kernel NVMET config, namespace device binding, Host ACL, listener ports, monitor cache, and UI state. |
| NVME-07 | DH-HMAC-CHAP unsupported path. | Current template disables CHAP controls and returns an explicit unsupported capability error if forced through API. |

## NVMe-oF Boot Reconcile Hot-Patch Validation (2026-06-30)

Validation target:

- SharedFS: `c002c123-ae17-4a08-b073-caa172063481`
- SystemVM: `i-2-572-VM` on `10.10.22.2`
- Service endpoint: `10.10.254.9:4420`
- Subsystem NQN: `nqn.2026-06.local.storage:tc03d01`
- Namespace ID: `1`
- Client Host NQN: `nqn.2014-08.org.nvmexpress:uuid:be872b70-0df3-4c5f-9e64-56a7955dcd1a`

Before the reconcile patch, rebooting the SystemVM left no `4420` listener and
no `/sys/kernel/config/nvmet` runtime state even though
`/etc/ablestack-storage/nvmeof-subsystems.json` was still present. Manual QGA
execution of the following command restored the service:

```bash
/usr/local/bin/ablestack-storagectl nvmeof subsystem apply /etc/ablestack-storage/nvmeof-subsystems.json
```

A hot-patched `ablestack-storage-boot-reconcile` was then installed on the same
SystemVM and the VM was rebooted again. The reconcile log contained:

```text
starting boot reconcile nfs=... smb=... iscsi=... nvmeof=/etc/ablestack-storage/nvmeof-subsystems.json
NVMe-oF desired state reapplied successfully
```

Post-reboot checks passed:

- `0.0.0.0:4420` was listening again;
- configfs subsystem and namespace entries were recreated;
- monitor cache reported `health_status=ok` and `nvmeofPorts {"4420": true}`;
- WSL client `nvme discover`, `nvme connect`, direct block write/read, and
  disconnect succeeded after reboot.

Result: PASS. The hot-patched behavior is the implementation baseline for the
SystemVM template.

## NVMe-oF Multi-Listener Hot-Patch Validation (2026-07-01)

Validation target:

- SharedFS: `1e6741c7-598f-42ef-b797-795ebf6be42d`
- SystemVM: `i-2-573-VM` on `10.10.22.1`
- Subsystem NQN: `nqn.2026-06.local.storage:tc03d01`
- Namespace ID: `1`
- Client Host NQN: local WSL initiator Host NQN

The existing service had NVMe-oF running on TCP `4420`. A QGA hot patch
injected a desired-state payload with two enabled listeners:

- `10.10.22.202:4420`
- `10.10.22.201:4421`

After applying the payload, both listener ports were active. The VM was then
rebooted and boot reconcile restored both listeners from
`/etc/ablestack-storage/nvmeof-subsystems.json`.

Post-reboot checks passed:

- `ss -lntp` showed both `10.10.22.202:4420` and `10.10.22.201:4421`;
- `/run/ablestack-storage/monitor/health.json` reported `status=ok` and
  `nvmeofPorts {"4420": true, "4421": true}`;
- WSL client `nvme discover`, `nvme connect`, direct block write/read, and
  disconnect succeeded against both endpoints.

Root cause for the management-side failure was protocol-row collapse:
`NVME_OF` was not treated as an endpoint protocol, so later protocol activation
could update the single `instance_id + protocol` row and drop previously
enabled listener ports. Fix requirement: handle `NVME_OF` like NFS/iSCSI for
endpoint persistence, desired-state rendering, and protocol deletion.

Result: PASS for runtime feasibility. The management/API/UI implementation must
preserve all `NVME_OF` listener rows and expose them as namespace listener port
groups.

## NVMe-oF Implementation Build and Deployment Evidence (2026-07-01)

Implementation source tree: `/root/work/ablestack-cloud`.

Build checks:

- Backend/API compile passed with `mvn -pl api,server -am -DskipTests -DskipITs compile`.
- UI locale JSON validation passed for `ui/public/locales/en.json` and `ui/public/locales/ko_KR.json`.
- UI production build passed with `NODE_OPTIONS=--openssl-legacy-provider npm run build`.
- Generated UI bundle: `ui/dist/js/app.1fe4fb30.js`.

22.10 management deployment:

- UI deployed to `/usr/share/cloudstack-management/webapp` and `/usr/share/cloudstack-ui`.
- Backend aggregate JAR patched at `/usr/share/cloudstack-management/lib/cloudstack-4.22.0.0-SNAPSHOT.jar`.
- Patched aggregate JAR SHA256: `8e726851fbef46f120e5b18d8855607d12d658d8e719dc4d382a700c7c15b69d`.
- `mold.service` restarted and reported `active`.
- `/client/` health check returned `200`.
- Unauthenticated `/client/api/?command=listCapabilities&response=json` health check returned `401`.

SystemVM template:

- Build command: `tools/appliance/build.sh systemvmtemplate 4.22.0.0 x86_64`.
- Packer build and SystemVM qcow2 validation passed.
- Compressed qcow2 round-trip validation passed.
- Exported artifact: `systemvmtemplate-4.22.0.0-x86_64-kvm-202607011723.qcow2.bz2`.
- Artifact size: `563M`.
- Artifact SHA256: `45b27dbdd4ee994f3a39ff0fa148fe94907399f0fcb5c961d0145b663de9241d`.
- Download URL: `http://10.10.22.10:8000/storage-service-systemvm/systemvmtemplate-4.22.0.0-x86_64-kvm-202607011723.qcow2.bz2`.
- HTTP download check returned `200 OK`.

Result: build and deployment ready for a new NVMe-oF Storage Service runtime
validation cycle.

## NVMe-oF Wildcard Listener Collision Validation (2026-07-01)

Validation target:

- SharedFS: `dee9a5cf-ef32-43c9-96b0-97ad4ae5693c`
- SystemVM: `i-2-575-VM` on `10.10.22.3`
- Existing namespace listener group: TCP `4420`
- Existing protocol listeners: `0.0.0.0:4420`, `10.10.22.201:4421`
- Failed attempted listener: `10.10.22.202:4420`

Observed failure:

- The failed apply left a stale configfs port for `10.10.22.202:4420`.
- `nvme discover -a 10.10.22.202 -s 4420` still succeeded because
  `0.0.0.0:4420` already covered that IP and port.
- Monitor health was degraded because it checked all registered protocol
  endpoint ports, including a registered but unexposed `4421` listener.

Hot-patch validation:

- `ablestack-storagectl` was patched in the running SystemVM to normalize
  listeners, remove stale configfs ports, and configure only namespace-exposed
  listener ports.
- Reapplying a payload with `0.0.0.0:4420`, `10.10.22.201:4421`, and
  `10.10.22.202:4420` succeeded; the specific `10.10.22.202:4420` listener was
  absorbed by `0.0.0.0:4420`.
- Reapplying a payload whose namespace exposed both `4420` and `4421` succeeded
  and `nvme discover` passed on both exposed ports.
- The service was restored to its original desired state after validation.

Result: PASS for the normalized wildcard listener model. The implementation
must reject same-port wildcard/specific conflicts at API/UI level and must keep
SystemVM apply idempotent for legacy or partially failed configfs state.

### NVMe-oF wildcard listener alias validation

Validated on a running Storage Service VM with an existing `0.0.0.0:4420` NVMe-oF listener:

- Added a specific service IP on the same port as an endpoint alias without creating a duplicate configfs port.
- Confirmed the alias IP is assigned to the SystemVM NIC.
- Confirmed NVMe-oF discover/connect and write-back through the alias IP and wildcard port.
- Rebooted the Storage Service VM and confirmed the endpoint alias is restored before NVMe-oF desired state is reapplied.

The product implementation must preserve this behavior: adding `10.10.x.y:4420` while `0.0.0.0:4420` exists is a valid endpoint registration, not a listener conflict.

## NVMe-oF multi-port listener runtime validation (2026-07-02)

Validation target:

- SharedFS: `3ac12cdc-3c4a-4bc2-aefe-57143f4b6b91`
- SystemVM: `i-2-577-VM` on `10.10.22.1`
- Subsystem: `nqn.2026-06.local.storage:tc03d01`
- Desired listeners: `0.0.0.0:4420`, `10.10.22.201:4421`,
  `10.10.22.203:4422`
- Endpoint alias: `10.10.22.202:4420`, covered by `0.0.0.0:4420`

Observed failure:

- The SystemVM had all service IPs assigned, and the desired-state file stored
  the added listeners.
- Configfs still exposed only `0.0.0.0:4420`.
- WSL client `nvme discover` and `nvme connect` succeeded through
  `10.10.22.202:4420`, but `10.10.22.201:4421` and `10.10.22.203:4422`
  returned connection refused.

Injected-code validation:

- Added configfs ports for `10.10.22.201:4421` and `10.10.22.203:4422` and
  linked the existing subsystem to those ports.
- `ss -ltnp` then showed listeners on all three effective endpoints.
- WSL client `nvme discover`, `nvme connect`, direct 4 KiB write/read compare,
  and disconnect succeeded on both added ports.
- A reconcile candidate that reuses existing configfs ports by `(listenIp, port)`
  and creates only missing ports reproduced the success without rewriting active
  port attributes.

Implementation requirement:

- `ablestack-storagectl nvmeof subsystem apply` must configure every real
  listener from the desired-state payload, must apply wildcard-covered aliases
  as NIC/IP state only, and must link active subsystems to every real listener.
- Existing configfs ports must not be rewritten while active. Only missing ports
  are created, and stale links owned by the managed subsystem are removed.

Result: PASS for the implementation direction. The fix is a SystemVM runtime
reconcile change; it is not a kernel or NVMe client limitation.

## NVMe-oF endpoint pre-activation validation (2026-07-07)

Validation target:

- SharedFS: `3cb78bb0-fd74-4239-88f1-6e8bff2086bd`
- SystemVM: `i-2-579-VM`
- Existing listener: `0.0.0.0:4420`
- Added listener under test: `10.10.22.201:4421`

Observed failure:

- Before guest network correction, the SystemVM had no global IPv4 address on
  the storage NIC.
- A direct TCP probe to `10.10.22.201:4421` failed with `Network is unreachable`.

Injected-code validation:

- Injected a network endpoint pre-activation routine through QGA.
- The routine added `10.10.22.201/16` to `eth0` before running
  `ablestack-storagectl nvmeof subsystem apply`.
- The apply command exited with code `0`.
- TCP probes passed for both `10.10.22.201:4421` and the existing
  `0.0.0.0:4420` listener.

Result: PASS. NVMe-oF desired-state apply must activate specific service IPs
before configfs listener readiness probing. The implementation must derive the
prefix from backend-provided network metadata rather than using a hardcoded
prefix.

## NVMe-oF runtime session attribution validation (2026-07-07)

Validation target:

- SharedFS: `1f04fadd-cfd0-455f-96c2-0f576f5166ea`
- SystemVM: `i-2-580-VM` on `10.10.22.1`
- Connected listener: `10.10.22.201:4421`
- Subsystem: `nqn.2026-06.local.storage:tc03d01`
- Client host NQN:
  `nqn.2014-08.org.nvmexpress:uuid:be872b70-0df3-4c5f-9e64-56a7955dcd1a`

Observed behavior before the fix:

- WSL `nvme connect` succeeded and `ss -tn` inside the SystemVM showed
  established TCP rows from the WSL client to `10.10.22.201:4421`.
- The monitor did not expose the session because the runtime collector only
  treated local port `4420` as NVMe-oF.
- The kernel target created multiple TCP queue connections for one logical
  NVMe-oF connection, so exposing raw TCP rows would create duplicate session
  rows.

Injected-code validation:

- Parsed `/sys/kernel/config/nvmet/ports/*` to discover all listener ports.
- Parsed linked subsystems, namespaces, `allowed_hosts`, and
  `attr_allow_any_host` from configfs.
- Grouped TCP rows by `(local endpoint, client address)`.
- The prototype returned one logical session with `queueCount=17`, subsystem
  `nqn.2026-06.local.storage:tc03d01`, namespace `1`, listener port `4421`,
  and the expected host NQN.

Result: PASS only for the single-candidate listener case. NVMe-oF session
collection must be configfs-driven and group TCP queues into one transport
aggregate. The configured Host ACL happened to match the test client, but that
does not prove the Host NQN was observed from the live controller. The
multi-subsystem same-endpoint case and the observed/configured identity split
are covered by the 2026-07-16 regression preflight below.

## NVMe-oF session, namespace, and endpoint identity regression preflight (2026-07-16)

Validation target:

- SharedFS: `8c9ff366-65ef-47eb-9124-cdb43158e04f`
- DB-mapped SystemVM: `i-2-596-VM` on `10.10.22.3`
- Service primary IP: `10.10.254.56`
- Same-endpoint subsystem pair:
  `nqn.2026-06.local.storage:tc03d01` and
  `nqn.2026-06.local.storage:sharedfs-test01`
- Shared listener under test: `10.10.254.56:4420`
- WSL client Host NQN:
  `nqn.2014-08.org.nvmexpress:uuid:be872b70-0df3-4c5f-9e64-56a7955dcd1a`

Read-only/runtime preflight:

- Connected the WSL client to both subsystems through the same listener.
- The target exposed 34 established TCP queues, 17 for each controller, but
  socket tuples were identical at the `ss` layer.
- The current collector grouped all queues into one row keyed by
  `(10.10.254.56:4420, 10.10.21.102)` and returned `queueCount=34` with both
  subsystem candidates.
- `/sys/kernel/debug/nvmet` was unavailable. Available `nvmet` tracepoints
  expose partial controller/request fields but do not join endpoint, client IP,
  Host NQN, subsystem NQN, and controller identity in one queryable record.
- Kernel controller creation messages include controller/subsystem/Host NQN
  fragments, but do not provide a durable state API and cannot safely drive
  polling attribution.

Conclusion: the current stock SystemVM kernel cannot statelessly split a
same-client/same-endpoint transport aggregate into exact logical controllers.
Injecting another userspace parser cannot create the missing kernel join, so no
mutating code injection was performed. The safe correction is fail-closed
candidate reporting, not heuristic attribution.

Observed adjacent defects:

| Area | As-is | Required behavior |
| --- | --- | --- |
| Session identity | `(local endpoint, client)` is labeled as one logical session. | Label it as a transport aggregate with `transportSessionId`; emit `logicalSessionId` only from an exact controller source. |
| Multi-candidate mapping | NQN, namespace, and Host NQN are displayed as `-` or may be inferred from configuration. | Return `mappingStatus=AMBIGUOUS` and candidate lists. Do not invent observed identity. |
| Host identity | Configured ACL identity can be presented as if it were the connected Host NQN. | Separate `observedHostNqn`, `hostPolicy`, and `configuredAllowedHosts`. Allow-any policy must display as policy, not as an anonymous observed host. |
| Disconnect | One aggregate row can expose a terminate action without a controller-specific handle. | Disable disconnect for ambiguous aggregates. Exact-controller termination is enabled only when a stable runtime controller handle exists. |
| Namespace size/path | API reads generic LUN size fields; SystemVM inventory lacks configfs/block-device enrichment. | Merge desired namespace data with observed configfs namespace path, enabled state, and block-device size by canonical NQN/NSID. |
| Primary/alias identity | The last listener alias can appear as the primary IP in endpoint inventory. | Preserve VM NIC primary IP independently from listener aliases and mark aliases explicitly. |

Required post-implementation validation:

| Test | Expected result |
| --- | --- |
| Single subsystem on one endpoint | One transport aggregate, `mappingStatus=EXACT`, exact NQN/namespace, and the real queue count. Host NQN remains unknown unless a runtime controller source reports it. |
| Two subsystems on the same endpoint/client | One transport aggregate with `queueCount=34`, `mappingStatus=AMBIGUOUS`, and both candidate subsystem/namespace values. NQN/NSID/Host NQN are not fabricated. |
| Allow-any subsystem | UI shows host policy `모든 호스트 허용`; observed Host NQN is shown as unavailable unless independently observed. |
| Ambiguous disconnect | Session termination is disabled with an attribution warning. No bulk endpoint disconnect occurs. |
| Namespace runtime enrichment | Each namespace row shows configured size, effective observed size, stable backing volume identity, current runtime device path, enabled state, and mapping status. |
| Reboot device renumbering | A namespace can move from one `/dev/sdX` name to another while volume UUID/serial identity remains stable and UI/runtime mapping stays correct. |
| Primary and aliases | `10.10.254.56` remains primary; secondary service IPs remain aliases regardless of operation order. All effective endpoints remain visible. |
| Monitor health | Ambiguous attribution is a warning while transport is healthy. Missing listener/configfs mapping is degraded/error. |

No database schema migration is required for these corrections. API additions
must remain backward compatible, and the UI must tolerate old responses by
falling back to an explicit unknown/ambiguous state rather than a misleading
dash.

## NVMe-oF canonical subsystem ACL validation (2026-07-07)

Validation target:

- SharedFS: `6f9e0165-ca23-427a-91f2-930abe466aa9`
- SystemVM: `i-2-581-VM` on `10.10.22.3`
- Subsystem under test: `nqn.2026-06.local.storage:tc03d01`
- Namespace: `1`
- Client host NQN:
  `nqn.2014-08.org.nvmexpress:uuid:be872b70-0df3-4c5f-9e64-56a7955dcd1a`

Observed behavior before the fix:

- Configfs had a valid subsystem and namespace:
  `attr_allow_any_host=0`, namespace `1` enabled on `/dev/sdb`.
- The subsystem `allowed_hosts` directory was empty even though the API/UI flow
  had created a host ACL.
- This happens when duplicate DB subsystem rows exist for the same subsystem
  NQN and the desired-state renderer attaches the host ACL to a non-canonical
  duplicate row instead of the single runtime configfs subsystem.

Injected-code validation:

- Linked the WSL client host NQN into
  `/sys/kernel/config/nvmet/subsystems/nqn.2026-06.local.storage:tc03d01/allowed_hosts`.
- WSL `nvme discover` to `10.10.22.202:4420` succeeded and returned the target
  NQN.
- WSL `nvme connect` succeeded and created `/dev/nvme0n1`.
- A direct 4 KiB block read/write round-trip against the namespace succeeded.

Result: PASS for the canonicalization direction. The product implementation
must render one canonical subsystem payload per NQN, merge namespaces by
namespace ID, and merge host ACLs from every duplicate subsystem row before
applying configfs.

## NVMe-oF subsystem/namespace API separation validation (2026-07-07)

Validation target:

- SharedFS: `d2aa3341-0cb6-4878-b101-32a2a63cdf76`
- SystemVM: `i-2-582-VM` on `10.10.22.3`
- Subsystem: `nqn.2026-06.local.storage:tc03d01`
- Namespace: `1`
- Host ACL: `nqn.2014-08.org.nvmexpress:uuid:be872b70-0df3-4c5f-9e64-56a7955dcd1a`

Observed behavior before the fix:

- DB and SystemVM configfs both had the requested host ACL.
- `listStorageNvmeOfSubsystems` returned namespace and subsystem rows together.
- The create dialog verification selected the first returned row, which could be
  a namespace, then queried host ACLs with the namespace ID and falsely reported
  `NVMe-oF host ACL was requested but not created`.

Required validation after the fix:

- `listStorageNvmeOfSubsystems` returns only subsystem rows.
- `listStorageNvmeOfNamespaces` returns only namespace rows.
- `listStorageNvmeOfHostAcls(subsystemid=...)` returns ACLs after canonical
  subsystem NQN resolution, even when legacy duplicate subsystem rows exist.
- Initial SharedFS create verification must pass when the subsystem, namespace,
  and host ACL are present in DB/SystemVM runtime.

## NVMe-oF listener runtime-state validation (2026-07-08)

Validation target:

- SharedFS: `0c5774f2-ca90-42b1-bdcf-00c52db88d5c`
- SystemVM: `i-2-584-VM` on `10.10.22.1`
- Configured listeners:
  - `0.0.0.0:4420` with aliases such as `10.10.22.202:4420`
  - `10.10.22.201:4421`
  - `10.10.22.203:4422`
- Namespaces:
  - `tc03d01` exposed through port group `4420`
  - `tc03d02`/`tc03d03` exposed through port group `4421`
  - no namespace exposed through port group `4422`

Observed behavior before the fix:

- The SystemVM correctly had no configfs subsystem link for `10.10.22.203:4422`,
  so no kernel listener existed on `4422`.
- The monitor cache treated every configured listener port as a required
  listening port, marked `4422=false`, and made the service appear degraded.
- The UI could show the configured endpoint as if it were an active namespace
  endpoint, and the namespace modal could leak the raw
  `label.storage.service.listener.ports` key.

Required validation after the fix:

- Monitor cache reports `4420` and `4421` as `LISTENING`.
- Monitor cache reports `4422` as `UNUSED`, not `ERROR`.
- Overall NVMe-oF health remains OK when the only non-listening port is unused.
- If a namespace is created or updated to use listener port group `4422`,
  configfs links the subsystem to that port and monitor status transitions to
  `LISTENING`.
- The NVMe-oF namespace dialog shows a translated listener-port label and
  displays port group status without raw i18n keys.

## NVMe-oF allow-any-host Host ACL validation (2026-07-09)

Validation target:

- SharedFS: `8a8a7e81-0e28-4798-85aa-e5574aa2e17f`
- SystemVM: `i-2-585-VM` on `10.10.22.2`
- Subsystem under test: `nqn.2026-06.local.storage:tc03d03`
- Host NQN attempted through the UI:
  `nqn.2014-08.org.nvmexpress:uuid:be872b70-0df3-4c5f-9e64-56a7955dcd1a`

Observed behavior before the fix:

- The subsystem was created with all-host access enabled:
  `attr_allow_any_host=1` / `allowAnyHost=true`.
- A UI Host ACL create action still allowed the operator to select that
  subsystem and submit an explicit Host NQN.
- The SystemVM apply path attempted to link
  `/sys/kernel/config/nvmet/hosts/<host-nqn>` into
  `/sys/kernel/config/nvmet/subsystems/<subsystem-nqn>/allowed_hosts/<host-nqn>`.
- The kernel rejected the link with `OSError: [Errno 22] Invalid argument`.
- The failed request left an `Error` state row in `storage_access_rule` even
  though the requested access model is invalid for an all-host subsystem.

Technical conclusion:

- `allowAnyHost=true` is itself the access policy for the subsystem.
- Explicit Host ACL rows are valid only when `allowAnyHost=false`.
- ACL edit/delete is still required for explicit Host ACL rows, but it must not
  be offered for inherited all-host policy rows.

Required validation after the fix:

| Case | Required result |
| --- | --- |
| All-host subsystem, UI create Host ACL | The Host ACL create modal disables or rejects the all-host subsystem before submit and explains that all hosts are already allowed. |
| All-host subsystem, direct API create Host ACL | API returns a clear validation error before persisting a `storage_access_rule` row. |
| All-host subsystem, SystemVM desired state | `ablestack-storagectl` writes `attr_allow_any_host=1`, does not create `allowed_hosts` symlinks, and monitor inventory reports inherited all-host policy. |
| Explicit-ACL subsystem, create Host ACL | API persists the rule, SystemVM links `allowed_hosts/<host-nqn>`, UI shows the row as explicit and Ready. |
| Explicit-ACL subsystem, edit Host ACL | UI edit action calls `updateStorageNvmeOfHostAcl`; Host NQN and supported auth flags update without creating duplicate rows. |
| Explicit-ACL subsystem, delete Host ACL | UI delete action requires confirmation, calls `deleteStorageNvmeOfHostAcl`, removes the configfs symlink, and refreshes the table without navigating away. |
| Reboot reconcile | After a service VM reboot, all-host subsystems remain all-host, explicit ACL subsystems restore their `allowed_hosts` links, and namespace connect behavior matches the UI policy. |

The preflight runtime evidence is the live kernel rejection on `i-2-585-VM`.
No additional DB schema migration is required for the fix, but existing invalid
`Error` ACL rows should be cleaned explicitly rather than silently promoted.
## NVMe-oF Row Action Validation

This validation set covers the NVMe-oF row action model described in
`docs/design/storage-service-systemvm-design.md`. It is focused on UI/API safety
and must be run after the NVMe-oF tab exposes row-level edit/delete actions.

| Test | Expected Result |
| --- | --- |
| Listener action column | The NVMe-oF listener table has a fixed right action column. Buttons stay visible when the table is horizontally scrolled. |
| Listener delete disabled by namespace | A listener port group referenced by any namespace cannot be deleted. The disabled reason identifies the namespace reference. |
| Listener delete exact match | Deleting an unused NVMe-oF listener removes only the exact `listenIp + port` row. Other listeners on the same IP or same port remain. |
| Wildcard listener protection | A `0.0.0.0:<port>` listener cannot be deleted while any namespace uses that port group. |
| Subsystem edit | The subsystem edit dialog opens vertically, in dark mode, with the current subsystem NQN and host policy populated. Engine and transport are read-only after creation. |
| Subsystem delete safety | Subsystem delete is disabled while namespaces, explicit Host ACLs, or active sessions exist. Server-side delete must also reject unsafe deletion. |
| Namespace action consistency | Namespace edit/delete actions remain visible and right aligned. Namespace delete does not delete the backing volume. |
| Backing volume actions | NVMe-oF backing volumes expose volume expansion and detach actions. Detach is disabled while a namespace uses the volume. |
| Backing volume expansion modal | The volume expansion action opens a dedicated backing-volume modal, not the file-share resize modal. The modal shows volume name, volume UUID, current size, storage pool, disk offering, and using resources, and the only editable capacity field is the new volume size in GiB. No `file share` label, file-share selector, target ID, or namespace ID is shown as the expandable object. |
| Backing volume expansion API | The UI calls `resizeStorageServiceBackingVolume` with `instanceid`, `volumeid`, and `size`. It must not call `resizeStorageFileShare` for iSCSI or NVMe-oF backing-volume table actions. |
| Host ACL inherited row | Inherited all-host policy rows remain read-only and never call Host ACL edit/delete APIs. |
| Explicit Host ACL row | Explicit Host ACL rows expose edit/delete actions. Editing is blocked when the subsystem is in all-host mode; delete is allowed only for explicit stale rows after confirmation. |
| Active session protection | Destructive actions that would affect an active session are disabled or require a clear destructive confirmation according to the UI rule. |
| i18n and dark mode | No raw i18n keys appear in NVMe-oF listener, subsystem, namespace, volume, ACL, or action dialogs. Dark mode uses the same table/action styling as NFS/SMB/iSCSI. |

### NVMe-oF Backing Volume Resize Validation

The 2026-07-10 validation used SharedFS
`045ef99f-f95e-42d5-b702-b831e391022e` and Storage Service VM
`i-2-591-VM`.

| Area | Result | Evidence |
| --- | --- | --- |
| DB identity | Pass | `sharedfs-DATA-591` is volume UUID `59121317-293c-45d6-87ac-da70d5f5a1e8`, 50 GiB, Ready, attached to VM 591, and referenced by `nqn.2026-06.local.storage:tc03d01 / Namespace 1`. |
| Runtime mapping | Pass | The namespace used the exact volume serial. After reboot the Linux disk name changed from `/dev/sda` to `/dev/sdf`, and reconcile rebound the namespace to the correct serial-backed device. |
| Protocol runtime | Pass | Listener ports 4420, 4421, 4422, and 4423 were restored and listening; monitor cache returned `ok`. |
| Client data path | Pass | WSL discovery/connect succeeded. Namespace 1 exposed 50 GiB and passed reversible raw-block write/read/restore before and after reboot. The namespace had no filesystem signature, so a filesystem mount was intentionally not performed. |
| UI modal | Fail | The served modal showed the namespace UUID `b331a969-1041-4b44-b577-a62abbe9161b` under `file share`, with no current volume size. |
| UI source/build | Pass | The canonical source and local build contain the dedicated `resizeBackingVolume` modal and `resizeStorageServiceBackingVolume` API call. |
| UI deployment | Fail | The browser received legacy `app.01f5da71.js`; the current local build references `app.1617f362.js`. The latest build was copied to nested/secondary roots, while `/client/` was served from `/usr/share/cloudstack-management/webapp/`. |

Required implementation and deployment tests:

| Test | Expected Result |
| --- | --- |
| Exact row mapping | Each backing-volume action row resolves one canonical volume UUID. Missing or ambiguous mappings disable the action; no default-volume fallback is allowed. |
| Modal identity | The dialog shows `sharedfs-DATA-591`, volume UUID `59121317-293c-45d6-87ac-da70d5f5a1e8`, current 50 GiB, disk offering, storage pool, and `tc03d01 / Namespace 1`. |
| Grow-only validation | Empty, non-integer, 50 GiB, and smaller values cannot be submitted. The first valid value is 51 GiB. |
| API payload | The request is `resizeStorageServiceBackingVolume(instanceid, volumeid, size)` and the `volumeid` equals the canonical volume UUID. No file-share/target/namespace ID is sent. |
| Backend ownership | A volume not attached to the selected Storage Service VM, a ROOT volume, or a DATADISK not represented by a Storage Service resource is rejected before resize. |
| Exact guest rescan | SystemVM resolves exactly one disk by UUID/serial. Zero or multiple matches fail; the implementation never rescans every non-root disk. |
| Observed size | QGA result contains expected and actual byte sizes, and actual size is at least the requested size before desired-state reapply succeeds. |
| NVMe-oF post-resize | Reconnect reports the expanded namespace size and reversible raw-block I/O succeeds. No filesystem is created. |
| Reboot recovery | Reconcile restores listeners, subsystem, namespace, ACL, serial-based device mapping, expanded size, and monitor cache after reboot. |
| Effective webroot hash | Local `ui/dist/index.html`, `/usr/share/cloudstack-management/webapp/index.html`, and `GET /client/` body hashes are identical. |
| Served asset gate | The app entry and all referenced JS/CSS chunks from the served HTML return HTTP 200. The expected current app hash is the one in local `ui/dist/index.html`. |
| Browser modal gate | A cache-disabled browser load opens the dedicated volume summary modal. `file share` and a share selector are absent. |

The modal/data-mapping defect itself does not require service-VM code injection.
The live VM already proves serial-based namespace recovery and raw I/O. A
service-VM preflight becomes mandatory when the fail-closed `volume rescan`
implementation is changed: first run its exact resolver in read-only mode, then
perform one approved grow-only volume expansion and the post-resize/reboot tests
above.

### NVMe-oF Backing Volume Resize Build and Deployment Evidence (2026-07-14)

Changed-component deployment completed against the 22.10 management host. This
scope does not add or change a database schema.

| Gate | Result | Evidence |
| --- | --- | --- |
| UI build | Pass | `NODE_OPTIONS=--openssl-legacy-provider npm run build` produced `app.934debac.js` and `app.d0463f10.css`. |
| API/server build | Pass | `mvn -pl api,server -am -DskipTests package` completed successfully. |
| Aggregate management build | Pass | `mvn -pl client -am -DskipTests package` completed all 140 reactor modules. |
| Active management artifact | Pass | `/usr/share/cloudstack-management/lib/cloudstack-4.22.0.0-SNAPSHOT.jar` SHA-256 is `a23a5eb749847c84cceb51de27c2a076ceb87fde0f41ff517db9eb38721d1872`. |
| Management runtime | Pass | `mold.service` is active, the unauthenticated `listApis` probe returns HTTP 401, and no `NoSuchMethodError`, `AbstractMethodError`, or `NoClassDefFoundError` was logged after activation. |
| Effective UI web root | Pass | Local `ui/dist/index.html`, `/usr/share/cloudstack-management/webapp/index.html`, the compatibility UI roots, and `GET /client/` all have SHA-256 `7024ca80055100f9cd7f3ea2cb438642b6adcc2f6418d7a8ee4d3114e6907ff5`. |
| Served UI assets | Pass | `GET /client/js/app.934debac.js` has SHA-256 `ed04c21cafea876339f1b19e8b81a3aea95e1e9ee364249092e4bff48f66b61a`; the app CSS and Korean locale return HTTP 200, and the locale contains `label.storage.service.new.volume.size.gib`. |
| SystemVM source inclusion | Pass | The appliance build archive included the changed `ablestack-storagectl` and `ablestack-storage-boot-reconcile` files. |
| SystemVM image build | Pass | Packer completed successfully; both the original QCOW2 validation and the compressed BZ2 round-trip validation passed. |
| Published template | Pass | `http://10.10.22.10:8000/systemvmtemplate-storage-service-4.22.0.0-x86_64-kvm-202607141939.qcow2.bz2` returns HTTP 200 with `Content-Length: 589775724`. |
| Published template integrity | Pass | Local artifact, remote file, and HTTP download stream all have SHA-512 `39d06e7d8dc55da397c18c4e9f7a2e53e5d4dc99dcbc4426c6c52cf888c8964617c2d29aff79f0ab450e71feb2a25c5746030a40b2be13144f289460aefdeb80`. |

Runtime acceptance still requires an operator-approved volume expansion against
a newly created Storage Service VM from this template. That test must confirm
the exact-volume rescan result, expanded namespace size, reversible raw-block
I/O, and reboot reconciliation without falling back to another guest disk.

## NVMe-oF namespace/UI consistency preflight (2026-07-17)

Validation target:

- SharedFS: `63efbcd0-65ee-4e73-bf92-bfe09c62703a`
- SystemVM: `i-2-597-VM` on `10.10.22.1`
- Runtime primary IP: `10.10.254.5`
- Listener aliases: `10.10.22.201`, `10.10.22.202`

Read-only evidence:

| Area | Result | Evidence |
| --- | --- | --- |
| Runtime desired state | Pass | Three namespaces were present and enabled after reboot. Canonical runtime mappings were `tc03d01/1 -> /dev/sdd`, `sharedfs-test01/1 -> /dev/sdc`, and `sharedfs-test02/1 -> /dev/sdb`. |
| Stable backing identity | Pass | Linux device names changed across reboot, but serial/volume identity restored every namespace to the correct device. |
| Endpoints | Pass | `10.10.254.5:4420`, `10.10.22.201:4421`, and `10.10.22.202:4422` accepted the intended subsystem connections. |
| Client data path | Pass | WSL discovery/connect and reversible write/read checks passed on all three namespaces before and after reboot. Existing ext4 test data persisted. |
| SystemVM monitor/reconcile | Pass | Monitor cache was healthy and boot reconcile completed successfully. |
| UI namespace mapping | Fail | All namespace rows could show the first runtime path `/dev/sdd` because `runtimeBlockTarget()` matched the globally repeated NSID `1` before checking subsystem NQN. |
| NIC identity | Fail | Runtime primary was `10.10.254.5`, while DB/resource-card metadata presented alias `10.10.22.202` as primary. |
| ACL refresh | Fail | Immediately after reboot, the tab could show an ACL as absent; a later Update action corrected explicit-ACL and allow-any policy rows. The data sources were committed at different refresh times. |
| i18n | Fail | Session columns and policy values exposed raw `label.storage.service.*` keys even though those keys exist in the source locale JSON, proving an effective-webroot/locale release mismatch. |
| Session attribution | Expected limitation | The stock target kernel exposes transport queues but not a durable controller record joining client IP, Host NQN, subsystem NQN, and NSID. Candidate/ambiguous session reporting remains the correct fail-closed behavior. |

Preflight conclusion:

- No mutating SystemVM code injection is justified for the namespace-path defect.
  The runtime inventory is already correct and provides the required composite
  identity and observed path.
- The defect is in management/API/UI observation and release consistency.
  Changing configfs/runtime code would add risk without correcting the faulty
  client-side join.
- A SystemVM injection preflight becomes necessary only if the inventory schema
  or serial-to-device resolver is changed later.

Required post-implementation regression:

| Test | Expected result |
| --- | --- |
| Duplicate NSID isolation | Three subsystems may each use NSID `1`; every UI row resolves its own runtime path by `(NQN, NSID)`. |
| Ambiguous/missing runtime row | The row shows `AMBIGUOUS` or `UNMAPPED` with no borrowed `/dev/sdX` path. |
| Reboot device renumbering | Device names may change, but each namespace follows its volume serial and the UI reflects the new exact runtime path after one coherent refresh. |
| Atomic ACL refresh | The first post-reboot snapshot shows either the previous coherent policy or the new coherent policy. It never briefly reports an absent ACL from partially refreshed arrays. |
| Primary IP preservation | Adding/retrying aliases preserves `10.10.254.5` as primary in DB, API, runtime inventory, and the resource card. |
| Locale deployment | Served app entry, CSS, Korean locale, and English locale hashes match the same build. A browser smoke test finds no raw storage-service i18n key. |
| Data path regression | Discovery, connect, reversible write/read, and reboot persistence continue to pass on ports 4420, 4421, and 4422. |

This correction requires API/server/UI changes and deployment-gate updates. It
does not require a DB migration or a new SystemVM template unless the runtime
inventory contract is changed during implementation.

### Namespace/UI implementation and deployment evidence (2026-07-17)

| Gate | Result | Evidence |
| --- | --- | --- |
| API/server module build | Pass | `mvn -pl api,server -am -DskipTests package` completed successfully. |
| Aggregate management build | Pass | `mvn -pl client -am -DskipTests package` completed all 140 reactor modules. |
| UI build and lint | Pass | Locale JSON validation, `SharedFSTab.vue` lint, and the production UI build completed successfully. |
| Active management artifact | Pass | Deployed aggregate JAR SHA-256 is `177fbbf2319cb92145d63230743dca273490686e30af8401b9cede60b4b4889b`. |
| Management runtime | Pass | `mold.service` is active; `/client/` returns 200 and unauthenticated `listApis` returns 401. |
| Served static closure | Pass | All 476 JS/CSS assets referenced by the served index returned 200. Served Korean and English locale hashes matched the local build. |
| Fresh-browser smoke test | Pass | The NVMe-oF tab rendered the Namespace list, mapping status, and runtime observation column in dark mode; no raw `label.storage.service.*` key and no fresh console error were present. |
| DB migration | Not required | Runtime observations use backward-compatible response fields and the existing desired-state/inventory model. |
| SystemVM template | Not required | The deployed SystemVM inventory already emits the composite NQN/NSID runtime identity used by this correction; no runtime contract changed. |

Deployment incident and permanent guard:

- A static-tree `rsync --delete` removed
  `/usr/share/cloudstack-management/webapp/WEB-INF/web.xml`. Static `/client/`
  remained reachable while `/client/api` returned 404 because the API servlet
  was no longer registered.
- Restoring `WEB-INF/web.xml` from the aligned aggregate JAR and restarting
  `mold.service` recovered the expected API 401 response.
- Future UI deployment must protect `WEB-INF/**` and treat the combined
  static-200/API-401/hash/browser checks as one indivisible release gate.

## Cross-protocol listener inventory and integrated UI preflight (2026-07-18)

Validation target:

- SharedFS: `63efbcd0-65ee-4e73-bf92-bfe09c62703a`
- SystemVM: `i-2-597-VM` on `10.10.22.1`
- Runtime primary IP: `10.10.254.5`
- Runtime aliases: `10.10.22.201`, `10.10.22.202`

Observed protocol data paths:

| Protocol | Runtime result | Listener evidence |
| --- | --- | --- |
| NFS | Pass | `10.10.254.5:2049` mounted and completed reversible read/write. |
| SMB | Pass | TCP 445 authenticated mount and reversible read/write completed. Samba listened on the wildcard service socket. |
| iSCSI | Pass | `10.10.22.202:3260` discovery/login and reversible raw-block I/O completed. |
| NVMe-oF | Pass | `10.10.254.5:4420`, `10.10.22.201:4421`, and `10.10.22.202:4422` connected and completed namespace read/write. |
| Reboot recovery | Pass | Aliases, listeners, backing mounts, targets, namespaces, and monitor cache recovered. The successful oneshot reconcile service returned to inactive state as designed. |

Management-plane defects:

| Area | Result | Evidence |
| --- | --- | --- |
| Port-group UI parity | Fail | NVMe-oF showed its listener-port-group table. NFS, SMB, and iSCSI did not expose equivalent configured-listener tables. |
| NFS tab execution | Fail | Browser console reported `this.nfsRuntimeEndpointDetails is not a function`; connection guidance could not be trusted. |
| Primary/alias identity | Fail | Runtime primary remained `10.10.254.5`, but DB/resource-card metadata presented alias `10.10.22.202` as primary. |
| Active service summary | Fail | Service visibility depended on child resource arrays, so an enabled protocol with no share/target/subsystem could disappear. |
| NVMe-oF management snapshot | Fail | DB/runtime contained subsystem, namespace, and backing-volume data while the UI snapshot could show only listeners and empty child tables. |
| Runtime behavior | Pass | No listener, mount, data-path, or reboot-reconcile defect was found in the SystemVM. |

Preflight decision:

- Do not inject or modify SystemVM code for this correction. All four protocol
  data paths and reboot recovery have already passed on the target VM.
- The failures are in DB identity preservation, API listener representation,
  client-side joins, refresh atomicity, and tab composition.
- A SystemVM injection preflight becomes mandatory only if implementation
  changes the desired-state payload, monitor-cache schema, listener creation,
  or reboot reconcile code.

Required code-level regression matrix:

| Test | Expected result |
| --- | --- |
| NFS listener inventory | NFS tab lists every persisted listener group. `V4_ONLY` groups remain distinct; `V3V4_DUAL` remains service-wide. Connection commands render without a console exception. |
| SMB wildcard inventory | One logical wildcard TCP 445 row expands to the primary and every alias. It does not fabricate one Samba daemon per IP. |
| iSCSI portal inventory | Every persisted LIO portal IP/port is shown with the exact linked target count. |
| NVMe-oF regression | Existing wildcard/dedicated rows, delete constraints, subsystem/namespace tables, and backing volumes remain unchanged except for use of the common adapter. |
| Enabled protocol with no child | The protocol remains visible with linked-resource count `0` and runtime state `UNUSED` or `LISTENING` as observed. |
| Wildcard expansion | `0.0.0.0:<port>` remains the logical listener while effective endpoints list the primary and aliases exactly once. |
| Primary preservation | Adding and retrying aliases never changes `nics.ip4_address`; only `nic_secondary_ips` and the secondary-IP flag may change. |
| Existing identity drift | DB/runtime disagreement produces an explicit warning and never silently labels the latest alias as primary. |
| Atomic refresh | Protocols, resources, ACLs, runtime inventory, sessions, and volumes switch as one generation. No partial empty tables appear during refresh. |
| i18n and dark mode | Four listener tables use translated labels, readable dark-mode colors, fixed-right actions, compact horizontal scroll, and standard no-data icons. |
| Data-path regression | NFS, SMB, iSCSI, and NVMe-oF connection/write checks and reboot recovery remain passing after management/UI changes. |

Build and deployment gates for the later implementation:

1. API/server tests and `mvn -pl api,server -am -DskipTests package` pass.
2. Locale JSON validation, `SharedFSTab.vue` lint/unit tests, and production UI
   build pass.
3. Aggregate management JAR is aligned with the API response classes; no
   `NoSuchMethodError` or `AbstractMethodError` appears after restart.
4. `/client/` returns 200, unauthenticated `/client/api` returns 401, every
   served asset returns 200, and served entry/locale hashes match the local
   build.
5. A cache-disabled browser verifies all four listener tables, NFS command
   rendering, active service summary, dark mode, and absence of raw i18n keys.
6. No DB migration and no SystemVM template build are expected for this scope.
   If implementation changes either contract, stop and run the corresponding
   migration or SystemVM preflight before deployment.

## Cross-Protocol Endpoint Accuracy Follow-up (2026-07-18)

This section supersedes only the unresolved management-plane conclusions in the
preceding preflight. The earlier NFS JavaScript exception was not reproduced in
the final integrated run; the browser completed with no console error. Runtime
and reboot recovery remained passing. The following display/runtime-policy
defects remain release blockers.

### Final integrated evidence

| Check | Result | Evidence |
| --- | --- | --- |
| NFS data path | Pass | `10.10.254.5:/nfs01` mounted and completed reversible read/write. Ganesha listened on one wildcard TCP 2049 socket. |
| SMB data path | Pass | `//10.10.22.201/smb01` authenticated and completed reversible read/write. `smbd` listened on wildcard TCP 445, not only on the selected UI endpoint. |
| iSCSI data path | Pass | `10.10.22.202:3260` discovery/login and reversible raw-block I/O passed. |
| NVMe-oF data path | Pass | Namespaces on ports 4420, 4421, and 4422 connected and completed read/write. |
| Reboot recovery | Pass | Within 23 seconds, aliases, listeners, mounts, block targets, namespaces, and monitor cache recovered. |
| Browser/dark mode | Pass with defects | No console exception and no structural dark-mode failure; endpoint summaries and one locale key were inaccurate. |

### Confirmed defects

| ID | Area | Current evidence | Required result |
| --- | --- | --- | --- |
| EP-01 | SMB listener enforcement | UI/config metadata selected `10.10.22.201:445`; runtime listened on `0.0.0.0:445`, and UI share paths expanded to all service NICs. | Dedicated SMB listeners bind only selected service IPs. Wildcard listeners intentionally expand to all IPs. UI and monitor show the same policy. |
| EP-02 | iSCSI status/commands | Target portal was `10.10.22.202:3260`; status/command helpers used the service primary IP and hard-coded port. | Every status and command derives from target listener groups plus canonical protocol endpoints. |
| EP-03 | NFS wildcard duplication | UI could present wildcard and explicit primary rows for one Ganesha wildcard socket. | One logical wildcard row with deduplicated effective endpoints; distinct listener ports remain separate. |
| EP-04 | NVMe-oF locale | Session policy displayed raw `label.storage.service.nvme.allow.any.host`. | Korean and English labels render; a raw-key test fails the UI build. |
| EP-05 | iSCSI whole-volume semantics | Requested LUN size displayed `-` while effective size was the backing volume capacity. | Requested size displays `백킹 볼륨 전체` / `Entire backing volume`; effective size remains the observed capacity. |
| EP-06 | Systemd unit mode | QGA `stat` returned `700` for `resize-sharedfs@.service`. | Unit file is `0644`; executable helper remains `0700`; `systemd-analyze verify` emits no permission warning. |

### Non-destructive SMB binding preflight

The QGA preflight used a temporary Samba configuration and did not restart or
modify the running service.

| Probe | Result |
| --- | --- |
| Current `interfaces` | Empty |
| Current `bind interfaces only` | `No` |
| Temporary selected-IP configuration | `interfaces = lo 10.10.22.201/32`, `bind interfaces only = Yes` |
| `testparm` | Pass |
| Runtime before implementation | IPv4/IPv6 wildcard TCP 445 |

### Required implementation regression matrix

| Test | Expected result |
| --- | --- |
| Common API adapter | Every protocol row exposes stable logical listener identity, effective endpoints, runtime state, and linked-resource count. |
| Wildcard canonicalization | One wildcard row expands all service IPs once; a covered primary row is not duplicated in the UI. |
| Dedicated SMB binding | TCP 445 succeeds on selected IPs and is refused on unselected service IPs. Existing SMB authentication and write behavior remain passing. |
| SMB wildcard binding | Omitting dedicated listeners restores service-wide TCP 445 access and one logical wildcard row. |
| iSCSI guidance | Status and generated commands use the exact target portal and non-default port when configured. |
| iSCSI size semantics | Whole-volume target shows the semantic label and exact effective capacity. |
| NFS regression | NFSv4-only and dual-mode listener rules, mounts, ACLs, and writes remain unchanged. |
| NVMe-oF regression | Listener groups, subsystems, namespaces, ACLs, sessions, and writes remain unchanged; no raw locale key appears. |
| Unit packaging | SystemVM image contains `resize-sharedfs@.service` as mode `0644`; boot reconcile remains passing. |
| Reboot recovery | All four protocols recover and pass post-reboot connection/write checks. |
| Served UI closure | `/client/` is 200, unauthenticated API is 401, all referenced assets are 200, served locale/entry hashes match the build, and a cache-disabled browser shows the corrected values. |

Implementation impact decision:

- API response shape: unchanged.
- Server behavior: response canonicalization and SMB desired-state payload only.
- UI: common listener adapter, protocol summaries/commands, semantic labels,
  and locale completion.
- DB: no migration.
- SystemVM: SMB listener rendering plus unit permission only; template rebuild
  and upload are mandatory for this correction.

## Integrated Primary Identity and NFS State Closure (2026-07-19)

This section records the latest integrated validation and supersedes the
management-plane identity and NFS state conclusions above. It does not change
the previously passing protocol runtime results.

Target:

- SharedFS: `636bab5d-553b-451e-8f02-f792ea83b8b3`
- Service VM: `i-2-598-VM`
- Runtime host: `10.10.22.2`

### Observed evidence

| Layer | Result | Evidence |
| --- | --- | --- |
| Runtime NIC identity | Pass | Runtime primary is `10.10.254.140`; aliases are `10.10.22.201` and `10.10.22.202`. |
| DB NIC identity | Fail | `nics.ip4_address` is `10.10.22.202`, and the same address also exists in `nic_secondary_ips`. |
| Protocol API | Fail | NFS wildcard effective endpoints contain aliases `.201` and `.202` but omit runtime primary `.140`; the alias is treated as primary. |
| NFS persisted desired state | Fail | The applied export remains serialized as `Updating` although DB/runtime are `Ready`. |
| NFS data path | Pass | Mount, reversible write, read, and cleanup passed. |
| SMB data path | Pass | Local-account mount, reversible write, read, and cleanup passed. |
| iSCSI data path | Pass | CHAP login and reversible raw-block I/O passed. |
| NVMe-oF data path | Pass | Namespace connection, ext4 mount, reversible write, read, and cleanup passed. |
| Reboot reconcile | Pass | All listeners, aliases, mounts, targets, namespaces, and monitor cache recovered. |

Overall result: **Fail for release closure**. Runtime service functionality is
passing, but management identity and persisted NFS desired state are not
trustworthy enough for UI/API correctness or later maintenance.

### Root-cause closure

Source inspection confirmed both identity write hazards:

1. `LibvirtComputingResource` reduces all QGA IPv4 addresses for one MAC into a
   single map value with repeated `put`, making the final address order
   significant.
2. `StatsCollector.VmStatsCollector` writes that single observed value directly
   into `nics.ip4_address` for an L2 NIC without excluding known secondary IPs.
3. Alias registration uses a generic full-row NIC update while intending to set
   only the secondary-IP flag.
4. Protocol response construction trusts the drifted DB primary and therefore
   amplifies the defect into wildcard endpoint output.
5. NFS apply serializes the transient DB workflow state before changing the DB
   object to `Ready`; the SystemVM correctly persists the payload it received.

### Preflight decision

Do not inject code into or modify the running Service VM for this design. The
runtime listener, storage, data-path, cache, and reboot contracts all passed.
The confirmed causes are management-side statistics reconciliation, DB update
scope, API composition, and desired-state production. A Service VM injection
could not validate or correct those producers and would add unnecessary risk.

The current KVM source class was also proven incompatible with the older 22.x
host runtime when applied as an isolated class: it references unrelated helper
scripts that are not present in that runtime. The attempted host patch was
rolled back and `mold-agent.service` recovered. This closure therefore keeps the
legacy agent payload and fixes its interpretation in management instead of
deploying host classes.

SystemVM preflight becomes required only if implementation changes the monitor
schema, `ablestack-storagectl` payload parser, listener apply logic, or boot
reconcile. The current design changes none of those consumers.

### Implementation acceptance matrix

| Test | Expected result |
| --- | --- |
| Legacy agent compatibility | Management accepts the existing single-address map and never overwrites a populated primary from that non-authoritative observation. |
| QGA address ordering | Every permutation of primary plus aliases preserves the existing primary. |
| Stats reconciliation | A known `nic_secondary_ips` value is observation-only and can never replace `nics.ip4_address`. |
| Alias transaction | Only `secondary_ip` and the new secondary row change; optimistic primary guard succeeds exactly once. |
| Identity response | DB/runtime disagreement returns `DRIFT`, both values, and a warning; no alias receives a primary badge. |
| Wildcard endpoint union | Runtime primary and aliases appear exactly once. |
| Dedicated endpoint scope | Drift handling does not add unrelated runtime addresses to a dedicated listener. |
| Explicit repair | Dry-run identifies the correction; apply requires unambiguous MAC/default-NIC/runtime evidence and writes an audit event. |
| NFS final state | Successful create/export/ACL apply persists operational `Ready`; reboot reconcile never consumes `Creating` or `Updating`. |
| Atomic UI refresh | One generation commits instance, protocols, resources, ACLs, runtime, sessions, and volumes together. |
| Four-protocol regression | NFS, SMB, iSCSI, NVMe-oF data paths and reboot recovery remain passing. |

Implementation impact for this closure:

- Management server and schema DAO: guarded field-scoped NIC update, identity
  context, explicit repair, and NFS desired-state normalization.
- API: backward-compatible identity diagnostic fields.
- UI: common identity warning/endpoint adapter and atomic refresh commit.
- Host agents: no deployment. The legacy agent payload remains supported and
  all three `mold-agent.service` instances must remain active.
- DB migration: not required.
- SystemVM/template: not required.

## Existing NIC Drift and iSCSI Authentication Design Closure (2026-07-20)

### Validated evidence

The integrated validation established two residual management-plane defects
without finding a data-path or reboot-recovery regression:

- the Service VM runtime primary address was `10.10.254.140`, while the
  management DB still identified `10.10.22.202` as primary and retained the
  actual runtime primary as an observed address;
- CHAP login and write succeeded, the LIO target/ACL required CHAP, and the
  session was `LOGGED_IN`, while the session payload reported
  `authenticated=false` solely from targetcli's `(NOT AUTHENTICATED)` text.

NFS, SMB, iSCSI, and NVMe-oF listeners, storage mappings, connection/write
tests, monitoring cache, and reboot recovery otherwise passed. This evidence
limits the remediation to management identity closure and iSCSI session
classification.

### Preflight decision

No code was injected into the healthy Service VM during this design pass.

- NIC repair is a management DB/API operation; SystemVM injection cannot
  validate its guarded transaction or UI postcondition.
- The iSCSI defect is a deterministic collector-classification error. The live
  CHAP login/write and LIO policy already provide the required runtime evidence.
- Mutating a passing four-protocol Service VM only to reproduce a known parser
  result would add risk without increasing design confidence.

Before a SystemVM template rebuild, the new classifier must still pass its pure
fixture suite and a read-only runtime preflight against an established CHAP
session. A hot patch is permitted only for that explicitly scoped preflight and
must not change target, ACL, listener, namespace, or backing-volume state.

### Acceptance matrix

| Test | Expected result |
| --- | --- |
| NIC repair dry-run | Returns DB primary, runtime primary, alias evidence, eligibility, and no mutation. |
| NIC repair stale guard | Apply fails when runtime primary differs from the dry-run expectation. |
| NIC repair apply | Only the primary identity field changes; aliases/listeners remain unchanged. |
| NIC repair postcondition | Refetched identity is `CONSISTENT` and the UI remains on the same service tab. |
| Stats regression | QGA address ordering and legacy agent payloads never replace a populated primary. |
| CHAP classification | `LOGGED_IN` plus required/configured CHAP is `VERIFIED` even if targetcli prints `NOT AUTHENTICATED`. |
| No-auth classification | Established no-auth session is `NOT_REQUIRED`, not failed. |
| Unknown classification | Missing policy/ACL evidence is `UNKNOWN`, not false. |
| Secret redaction | Passwords and CHAP keys are absent from cache, API JSON, logs, and UI. |
| UI compatibility | Version 1 session rows render as unknown unless positive evidence exists; version 2 rows show translated badges. |
| Four-protocol regression | NFS, SMB, iSCSI, NVMe-oF connect/write and reboot recovery remain passing. |
| Deployment gate | Served UI hashes match the build; management/API classes are aligned; rebuilt SystemVM contains the tested collector. |

### Component impact

- Management/API: expose the existing explicit repair flow safely and verify
  the post-repair identity result.
- UI: add repair confirmation and truthful iSCSI authentication state.
- SystemVM: update only iSCSI session collection/classification.
- DB schema and host agents: no change.

## Protocol-Scoped UI Consistency Validation (2026-07-20)

### Evidence baseline

The new integrated file service backed by Service VM 599 passed all runtime
acceptance checks before this UI-only closure:

- DB resource IDs, volume IDs, listener endpoints, ACLs, and runtime inventory
  were consistent;
- NFS, SMB, CHAP-protected iSCSI, and NVMe-oF mounted and completed reversible
  write/read/delete tests;
- after an actual Service VM reboot, UUID-backed mounts, the NFS bind alias,
  protocol listeners, monitoring cache, and all four data paths recovered;
- the browser reported no console error, raw locale key, page-level horizontal
  overflow, or dark-mode regression.

The validation nevertheless found two UI correctness failures: cross-protocol
NVMe-oF warning text in the iSCSI tab, and non-NFS/raw-block volumes plus an
incorrect filesystem fallback in the NFS backing-volume table.

### Preflight decision

Do not patch or inject code into the running Service VM for these two defects.
The authoritative runtime evidence is correct and the failure is reproduced by
pure UI projection logic in `SharedFSTab.vue`. The required preflight is a
fixture-based unit test using the captured mixed-protocol session payload and
mixed-protocol attached-volume snapshot.

### Acceptance matrix

| Test | Expected result |
| --- | --- |
| Mixed session warnings | An NVMe-oF mapping warning never appears in the iSCSI tab. |
| iSCSI exact mapping | An exact iSCSI row suppresses the incomplete-session banner. |
| iSCSI incomplete mapping | Observed iSCSI transport without a logical row shows a translated count-only warning. |
| NFS volume ownership | The NFS table lists only volumes referenced by NFS exports. |
| Filesystem truthfulness | Authoritative ext4/xfs is displayed; unknown is `-`; default XFS is never fabricated. |
| Dialog regression | Current-volume selectors continue to show every safe attached DATADISK candidate. |
| Four-protocol regression | Existing listener, ACL, mount/write, session, and reboot-recovery behavior is unchanged. |
| Deployment gate | Served UI asset hashes match the production build and the target page contains the new computed projection. |

Implementation and deployment scope for this closure is UI only. No DB, API,
management server, host agent, SystemVM runtime, or template change is required.

## File-Share Backing Device Reorder Preflight (2026-07-21)

### Read-only runtime evidence

A QGA preflight was executed against the integrated four-protocol Service VM
after reboot. The preflight did not mutate configfs, mounts, filesystems,
listeners, shares, exports, or DB records.

| Resource | Persisted observation | Current stable runtime evidence | Result |
| --- | --- | --- | --- |
| NFS backing volume | `lastInspection.devicePath=/dev/sdb` | ABLESTACK volume UUID/serial and filesystem UUID resolve to `/dev/sde`; stable volume mount and `/export/nfs01` alias are active. | Historical device path is stale; data path is healthy. |
| SMB backing volume | `lastInspection.devicePath=/dev/sdc` | ABLESTACK volume UUID/serial and filesystem UUID resolve to `/dev/sdd`; stable volume mount is active. | Historical device path is stale; data path is healthy. |
| Old kernel names | `/dev/sdb`, `/dev/sdc` | Both names now refer to different valid data disks. | Reusing the cached name could select the wrong volume. |
| Mount persistence | UUID-backed fstab records | Mounts recovered after boot despite device-name changes. | Stable persistence behavior is correct and must remain unchanged. |

This evidence proves that safe-disk filtering is insufficient when a stale
kernel name points to another legitimate data disk. The design must use stable
volume identity and treat `/dev/sdX` strictly as observed telemetry.

### Resolver preflight matrix

Before implementation is accepted, the shared SystemVM resolver must pass the
following pure or isolated fixtures:

| Test | Expected result |
| --- | --- |
| Stale path reused by another data disk | Ignore the path and resolve the expected volume by ABLESTACK UUID/serial. |
| Filesystem UUID corroboration | The by-UUID device agrees with stable volume evidence and is accepted. |
| Filesystem UUID mismatch | Reject before mount, format, resize, or service apply. |
| Reboot device reorder | Resolve the new kernel path and report the current boot ID and observation time. |
| Ambiguous serial/by-id candidates | Fail closed with an actionable ambiguity error. |
| Root/boot/swap/optical candidate | Reject unconditionally. |
| First blank-volume attach | Allow unique size fallback only when no stable/filesystem identity exists and exactly one safe candidate remains. |
| Reconcile/rescan/resize fallback | Never use size-only or cached-path fallback. |

### End-to-end acceptance matrix

| Test | Expected result |
| --- | --- |
| Legacy config normalization | `fsUuid` is read, `filesystemUuid` is written, and legacy `devicePath` remains diagnostic only. |
| Management persistence | Desired volume UUID/mount intent remain unchanged; inspection schema version 2 records observation metadata. |
| API current mapping | Live inventory returns the current device path and `EXACT`; historical paths are never advertised as current. |
| API read behavior | List/read calls do not mutate `config_json`. |
| UI current-device display | The current path is shown only for a live exact mapping; stale or absent runtime evidence displays `-` with translated status. |
| NFS/SMB reboot recovery | UUID mounts, NFS alias, service state, connect, read, and reversible write all recover. |
| Raw-block regression | iSCSI and NVMe-oF listeners, mappings, sessions, and write paths remain passing. |
| Deployment gate | Rebuilt SystemVM contains the tested resolver; management/API classes align; served UI hashes match the production build. |

### Component and rollout scope

- SystemVM/template: required because the resolver and command output change.
- Management/API: required for schema normalization and desired/runtime field
  separation.
- UI: required for truthful current-device and mapping-state presentation.
- DB migration: not required; existing `config_json` is normalized lazily on a
  successful explicit inspect/apply.
- Host agent: no change and no `mold-agent.service` restart.

## Integrated Runtime Observation Preflight (2026-07-21)

### Test target and safety boundary

The preflight used integrated Service VM `i-2-600-VM` on host `10.10.22.2`.
Only read-only QGA commands were executed. No mount, configfs object, listener,
desired-state file, DB row, or guest service was modified.

### Evidence

| Check | Observed result | Design conclusion |
| --- | --- | --- |
| `findmnt -J` top-level traversal | One top-level filesystem and zero managed volume mounts. | The current collector can falsely report an empty healthy inventory. |
| Recursive `findmnt` traversal | 29 nodes; `/dev/sdd` and `/dev/sde` mounted at canonical `/srv/ablestack-storage/volumes/<UUID>` targets. | Recursive traversal is sufficient to recover the two missing NFS/SMB observations. |
| iSCSI runtime tree | The configured IQN, LUN 0, ACL, portal, and `/dev/sdc` block backstore were present. | SystemVM runtime truth exists; the missing UI value is a management merge/projection defect. |
| Generic network unit | `networking.service` failed with `ifup: unknown interface eth0`. | The generic ifupdown unit is not the SharedFS NIC owner. |
| Interface file | `auto lo eth0` with only `iface lo inet loopback`. | The file is internally inconsistent and must not be consumed by the generic unit. |
| Effective network path | Primary/secondary IPs and all protocol listeners were active through persistent DHCP and endpoint reconcile. | Fix unit ownership without changing protocol endpoint desired state. |
| Protocol regression baseline | NFS, SMB, CHAP iSCSI, and NVMe-oF connect/write and reboot recovery passed. | Remediation remains limited to observation and SharedFS boot ownership. |

### Preflight decision

A hot code patch is unnecessary and would add risk to a four-protocol passing
VM. The collector failure is fully reproduced by a pure recursive traversal of
the same live `findmnt` JSON, and the iSCSI omission is visible in the management
call path: the list operation does not pass the already collected runtime target
to the response builder. The networking failure is also deterministic from the
unit status and interface file.

Implementation must therefore be validated first with pure SystemVM fixtures,
focused management tests, and UI projection tests. The rebuilt template then
requires one new-VM reboot acceptance run.

### Acceptance matrix

| Test | Expected result |
| --- | --- |
| Nested mount fixture | Canonical NFS and SMB volume mounts are discovered recursively and reported once. |
| Bind alias fixture | `/export/<name>` does not create a duplicate volume observation. |
| Stable device identity | Reordered `/dev/sdX` paths resolve by ABLESTACK volume identity and filesystem UUID. |
| Inventory unavailable | API/UI show `UNAVAILABLE`, not `UNMAPPED`. |
| Successful missing observation | API/UI show `UNMAPPED` with a current observation timestamp. |
| iSCSI target/LUN join | Exact normalized IQN and LUN return runtime backing path, backstore type, size, boot ID, and timestamp. |
| Cross-target isolation | A runtime LUN is never attached to another IQN or LUN row. |
| SharedFS DHCP ownership | New VM boots with `cloud-dhclient@eth0` active and generic `networking.service` disabled without a failed state. |
| Endpoint recovery | Secondary IPs and NFS/SMB/iSCSI/NVMe-oF listeners recover after reboot. |
| Data-path regression | All four protocols complete connect, read, reversible write, and disconnect tests. |
| UI truthfulness | Current devices and actual backing paths appear only for exact live mappings; dark mode and fixed action columns are unchanged. |
| Deployment integrity | Management/API signatures align, served UI hashes match the build, and the published template checksum matches the tested artifact. |

### Component scope

- SystemVM collector and SharedFS template: required.
- Management/API and UI: required.
- DB schema and host agents: no change.
- Existing Service VM data paths require no hot mutation during design.

## SharedFS Runtime Locale Completeness Preflight (2026-07-22)

### Verified failure boundary

The four-protocol SharedFS acceptance run passed API/DB consistency, SystemVM
runtime configuration, client I/O, and reboot recovery. The remaining failure
is UI-only: four literal message keys used by `SharedFSTab.vue` are absent from
both `ui/public/locales/en.json` and `ui/public/locales/ko_KR.json`.

| Key | Runtime path | Current result |
| --- | --- | --- |
| `message.storage.service.nfs.export.name.help` | NFS export-create name tooltip | Missing in both locales |
| `message.storage.service.nfs.backing.path.help` | NFS export-create backing-path tooltip | Missing in both locales |
| `message.storage.service.iscsi.chap.credential.required` | One-way CHAP validation | Missing in both locales |
| `message.storage.service.iscsi.mutual.chap.credential.required` | Mutual-CHAP validation | Missing in both locales |

The deployed app entry matched the local production build, all referenced
hashed JS/CSS assets returned HTTP 200, and the management service showed no
new ABI linkage errors. This proves the defect is not stale Java deployment or
a SystemVM runtime failure. It is a source-level locale completeness gap that
was faithfully deployed.

### Preflight decision

Do not inject code into the running Service VM. The affected execution paths
are browser tooltip and validation-message rendering, and the guest cannot
provide meaningful evidence for them. Use source/locale extraction tests and a
served-asset smoke test instead.

### Acceptance matrix

| Test | Expected result |
| --- | --- |
| Locale JSON syntax | `jq empty` passes for English and Korean runtime bundles. |
| Literal-key coverage | Every literal storage-service `$t()` key used by `SharedFSTab.vue` and `CreateSharedFS.vue` exists in both runtime bundles. |
| Locale parity | English and Korean storage-service key sets are identical; no value is empty or equal to its key. |
| NFS tooltip smoke | Export-name and backing-path tooltips display translated guidance and no raw key. |
| iSCSI CHAP validation | Missing one-way and mutual credentials produce their distinct translated messages without exposing credentials. |
| UI regression | Existing listener, table, fixed-action-column, vertical-modal, and dark-mode behavior remains unchanged. |
| Production build | Focused Jest tests, lint, and production build pass. |
| Deployment integrity | Served entry, JS/CSS, and both locale JSON hashes match the local build; all assets return HTTP 200. |
| Browser smoke | Korean NFS/iSCSI dialogs contain no `label.storage.service.*` or `message.storage.service.*` text. |
| Runtime regression | No backend, API, DB, SystemVM, template, or host-agent artifact is changed or restarted. |

### Component scope

- UI locale bundles: required.
- UI focused tests and i18n report path: required.
- Vue behavior: only focused validation test coverage; no protocol logic change.
- Management/API, DB, SystemVM/template, and host agent: no change.
