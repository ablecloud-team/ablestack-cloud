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

# FTCTL_DR Engine And Driver Implementation Design

> Normative Test Failover update (2026-07-19):
> [562-cross-hypervisor-dr-test-artifact-contract-and-projection-isolation-design-20260719.md](562-cross-hypervisor-dr-test-artifact-contract-and-projection-isolation-design-20260719.md)
> and its FTCTL companion document define strict provider locators and
> operation/protection status separation. Bare `targetDiskRef` inference and
> FTCTL-owned customer test VM lifecycle are superseded.

작성일: 2026-07-01

상위 계획: [520-cross-hypervisor-dr-full-implementation-work-plan-20260701.md](520-cross-hypervisor-dr-full-implementation-work-plan-20260701.md)

관련 설계:

- [521-cross-hypervisor-dr-full-stack-implementation-design-20260701.md](521-cross-hypervisor-dr-full-stack-implementation-design-20260701.md)
- [522-cross-hypervisor-dr-protection-failover-failback-sequence-design-20260701.md](522-cross-hypervisor-dr-protection-failover-failback-sequence-design-20260701.md)

## 1. 목적

이 문서는 `ablestack-qemu-exec-tools`에 포함할 `FTCTL_DR` runtime engine과 source/target driver의 구현 설계를 정의한다.

`FTCTL_DR`은 다음 네 방향을 모두 같은 수준으로 처리해야 한다.

| 방향 | source driver | target driver |
|---|---|---|
| ABLESTACK -> VMware | KVM QMP dirty bitmap | VMware VDDK target |
| VMware -> VMware | VMware CBT/VDDK source | VMware VDDK target |
| ABLESTACK -> ABLESTACK | KVM QMP dirty bitmap 또는 기존 FTCTL/xcolo | ABLESTACK target |
| VMware -> ABLESTACK | VMware CBT/VDDK source | ABLESTACK target |

## 2. Runtime 구조

추가 경로:

```text
bin/ablestack_vm_ftctl.sh
lib/ftctl/dr/common.sh
lib/ftctl/dr/profile.sh
lib/ftctl/dr/session.sh
lib/ftctl/dr/checkpoint.sh
lib/ftctl/dr/metrics.sh
lib/ftctl/dr/source_kvm_qmp.sh
lib/ftctl/dr/source_vmware_cbt.py
lib/ftctl/dr/target_ablestack.sh
lib/ftctl/dr/target_vmware_vddk.py
lib/ftctl/dr/failover.sh
lib/ftctl/dr/failback.sh
lib/ftctl/dr/test_failover.sh
lib/ftctl/dr/cleanup.sh
lib/ftctl/dr/reporter.sh
lib/ftctl/dr/mover.py
```

기존 FTCTL 파일은 가능한 한 안정 경로를 유지한다.

| 기존 파일 | 처리 |
|---|---|
| `lib/ftctl/blockcopy.sh` | ABLESTACK -> ABLESTACK legacy seed 경로에서 재사용 |
| `lib/ftctl/xcolo.sh` | 기존 검증된 KVM-to-KVM 지속 경로 보존 |
| `lib/ftctl/failover.sh` | ABLESTACK target promote 일부 재사용 |
| `lib/ftctl/events.sh` | DR event writer로 확장 |
| `lib/ftctl/profile.sh` | legacy profile 유지, DR profile은 `lib/ftctl/dr/profile.sh`로 분리 |

## 3. CLI 설계

| command | 설명 |
|---|---|
| `dr-plan-apply --profile-json <path>` | profile 저장과 preflight |
| `dr-sync-start --plan <uuid> --run <uuid>` | full seed 또는 incremental loop 시작 |
| `dr-sync-pause --plan <uuid>` | sync pause |
| `dr-sync-resume --plan <uuid>` | sync resume |
| `dr-status --plan <uuid> --json` | state/checkpoint/event offset 출력 |
| `dr-test-failover --plan <uuid> --restore-point <uuid>` | isolated boot |
| `dr-test-cleanup --plan <uuid>` | test 자원 정리 |
| `dr-failover --plan <uuid> --mode planned|disaster` | target promote |
| `dr-failback --plan <uuid>` | reverse sync + original promote |
| `dr-reprotect --plan <uuid>` | active side 기준 reverse protection |
| `dr-release --plan <uuid> [--force]` | profile/session/temp cleanup |

## 4. Profile schema

profile은 Cloud에서 생성하고 FTCTL이 검증한다.

```json
{
  "version": 1,
  "engine": "FTCTL_DR",
  "planUuid": "plan-uuid",
  "direction": "VMWARE_TO_KVM",
  "rpoTargetSeconds": 300,
  "rtoTargetSeconds": 900,
  "consistency": "crash-consistent",
  "source": {
    "provider": "VMWARE",
    "driver": "VMWARE_CBT",
    "vmRef": "vm-123",
    "vcenterRef": "credential-ref",
    "disks": []
  },
  "target": {
    "provider": "ABLESTACK",
    "driver": "ABLESTACK_RBD",
    "storage": {},
    "network": {},
    "vm": {}
  },
  "workers": {
    "coordinator": "host-uuid",
    "source": "host-uuid",
    "target": "host-uuid"
  }
}
```

credential material은 영구 profile에 저장하지 않는다. Agent가 action 시점에 temporary secret file을 만들고 FTCTL은 run 종료 후 삭제한다.

## 5. State layout

| 경로 | 용도 |
|---|---|
| `/etc/ablestack/ftctl.d/dr/<plan_uuid>.conf` | durable profile |
| `/run/ablestack-vm-ftctl/dr/<plan_uuid>/session.state` | active run state |
| `/run/ablestack-vm-ftctl/dr/<plan_uuid>/lock` | single writer lock |
| `/var/lib/ablestack-vm-ftctl/dr/<plan_uuid>/checkpoints/` | restore point metadata |
| `/var/lib/ablestack-vm-ftctl/dr/<plan_uuid>/manifests/` | extent manifests |
| `/var/log/ablestack-vm-ftctl/dr/<plan_uuid>/events.log` | append-only events |

## 6. Checkpoint schema

```json
{
  "checkpointUuid": "rp-uuid",
  "sequence": 42,
  "state": "TARGET_READY",
  "sourceCheckpointAt": "2026-07-01T12:00:00Z",
  "targetDurableAt": "2026-07-01T12:00:32Z",
  "targetReadyRpoSeconds": 32,
  "consistency": "crash-consistent",
  "disks": [
    {
      "sourceDiskRef": "disk-1000-0",
      "targetDiskRef": "rbd/pool/image",
      "driverToken": "changeId-or-bitmap-generation",
      "manifest": "manifest-42-disk0.json",
      "bytesTotal": 107374182400,
      "bytesWritten": 10485760,
      "checksum": "sha256:..."
    }
  ]
}
```

checkpoint는 target flush와 validation이 끝난 후에만 `TARGET_READY`가 된다.

## 7. Data mover

`mover.py`는 source/target driver가 제공하는 extent stream을 받아 target에 쓴다.

입력 manifest:

```json
{
  "disk": "disk-0",
  "generation": 42,
  "extents": [
    { "offset": 0, "length": 1048576, "zero": false },
    { "offset": 1048576, "length": 1048576, "zero": true }
  ]
}
```

책임:

- extent ordering
- sparse/zero extent 처리
- partial retry
- checksum sampling
- write throttling
- progress event
- target flush

## 8. KVM source driver

파일: `lib/ftctl/dr/source_kvm_qmp.sh`

### 8.1 Preflight

확인 항목:

- libvirt domain 존재
- QMP command 가능
- disk target/source map
- dirty bitmap 지원 여부
- source disk size 고정 또는 resize 감지 가능 여부
- qemu block export 또는 qemu-nbd export 가능 여부

### 8.2 Full seed

1. disk별 tracking bitmap 생성
2. source disk 전체 extent manifest 생성
3. mover로 target write
4. target validation
5. initial checkpoint 기록

### 8.3 Incremental

1. current bitmap freeze 또는 generation rotate
2. dirty extent manifest 생성
3. target write
4. target flush/checksum
5. checkpoint 기록
6. 다음 generation bitmap 준비

bitmap continuity가 깨지면 incremental을 진행하지 않고 `FULL_RESYNC_REQUIRED`를 반환한다.

## 9. VMware source driver

파일: `lib/ftctl/dr/source_vmware_cbt.py`

VMware source는 VDDK와 vSphere CBT를 사용한다. Broadcom VDDK는 VMware virtual disk 접근용 라이브러리이고, CBT는 변경된 disk sector 추적 기능이다.

### 9.1 Preflight

확인 항목:

- vCenter endpoint 접근
- VM power/state 조회
- disk inventory
- CBT enabled
- snapshot 권한
- VDDK 또는 nbdkit-vddk 설치
- worker host에서 datastore 접근 가능

### 9.2 Full seed

1. snapshot 생성
2. disk별 base `changeId` 확보
3. VDDK reader open
4. allocated extent 또는 full extent manifest 생성
5. mover로 target write
6. snapshot cleanup
7. checkpoint 저장

### 9.3 Incremental

1. snapshot 생성
2. 이전 checkpoint의 `changeId` 기준으로 changed extents 조회
3. VDDK reader로 changed extents read
4. target write/flush
5. 새 `changeId` 저장
6. snapshot cleanup
7. target-ready checkpoint 저장

CBT query가 실패하거나 changeId가 invalid이면 full resync로 전환한다.

## 10. ABLESTACK target driver

파일: `lib/ftctl/dr/target_ablestack.sh`

### 10.1 Target types

| target | writer |
|---|---|
| RBD | librbd-capable qemu/qemu-io 우선 |
| QCOW2 | file sparse pwrite/qemu-io |
| raw/block | block pwrite |

### 10.2 Target preparation

1. Cloud가 target volume/VM skeleton이 아니라 실제 powered-off standby VM을 만든다.
2. FTCTL은 disk target path와 size를 검증한다.
3. RBD/QCOW2 target을 write 가능한 상태로 연다.
4. checkpoint마다 flush한다.
5. test failover는 snapshot/clone/overlay만 사용한다.

## 11. VMware target driver

파일: `lib/ftctl/dr/target_vmware_vddk.py`

### 11.1 Preflight

확인 항목:

- vCenter endpoint 접근
- datacenter/folder/resource pool/datastore/network mapping
- VDDK writer 사용 가능
- target VM inventory 생성/갱신 가능
- target datastore free capacity

### 11.2 Target preparation

1. powered-off target VM 생성
2. disk별 VMDK 생성
3. source disk geometry/adapter type 반영
4. NIC mapping 반영
5. VM은 failover 전까지 powered-off 유지

### 11.3 Write/flush

1. VDDK writer open
2. manifest extents write
3. zero extent는 punch/sparse 처리 가능 시 sparse 유지
4. flush/close
5. checkpoint metadata에 VMDK ref 기록

## 12. Test failover implementation

| target | 구현 |
|---|---|
| ABLESTACK RBD | RBD snapshot/clone 생성, isolated VM에 attach |
| ABLESTACK QCOW2 | qcow2 overlay 생성, isolated VM에 attach |
| VMware | linked clone 또는 snapshot 기반 test VM 생성, isolated portgroup 연결 |

test cleanup은 생성한 clone/overlay/test VM만 삭제한다. durable checkpoint는 삭제하지 않는다.

## 13. Failover implementation

### 13.1 Planned

1. sync loop pause
2. source quiesce
3. final changed extent capture
4. final target write
5. target validation
6. source fence/stop
7. target promote/power-on
8. active side update

### 13.2 Disaster

1. source reachability 확인 실패 또는 operator disaster acknowledgement
2. latest target-ready checkpoint lock
3. target promote/power-on
4. RPO evidence 출력
5. active side update

## 14. Failback/reprotect implementation

failback은 reverse direction의 protection setup + planned failover이다.

| failed-over active side | reverse source driver |
|---|---|
| VMware target | VMware CBT/VDDK |
| ABLESTACK target | KVM QMP/FTCTL |

original site는 reverse target이 된다.

구현 단계:

1. reverse preflight
2. reverse full seed 또는 checkpoint reuse
3. reverse incremental sync
4. reverse target-ready checkpoint
5. planned cutback
6. active side restore
7. reprotect profile 정리

## 15. RPO/RTO metrics

### 15.1 RPO

```text
target_ready_rpo_seconds = now - checkpoint.targetDurableAt
```

RPO satisfied 조건:

```text
target_ready_rpo_seconds <= profile.rpoTargetSeconds
```

### 15.2 RTO

FTCTL은 failover run에서 다음 timestamp를 기록한다.

| timestamp | 의미 |
|---|---|
| `failoverRequestedAt` | Cloud run 생성 |
| `targetPromoteStartedAt` | target promote 시작 |
| `targetPowerOnAt` | target power on |
| `guestReadyAt` | guest/network validation 완료 |
| `failoverCompletedAt` | Cloud state update 완료 |

RTO actual:

```text
rto_actual_seconds = failoverCompletedAt - failoverRequestedAt
```

## 16. Error codes

| code | 의미 |
|---|---|
| `DR_MISSING_VDDK` | VDDK/nbdkit-vddk 없음 |
| `DR_CBT_DISABLED` | VMware CBT 비활성 |
| `DR_CBT_TOKEN_INVALID` | changeId invalid, full resync 필요 |
| `DR_QMP_BITMAP_UNSUPPORTED` | QMP dirty bitmap 불가 |
| `DR_FULL_RESYNC_REQUIRED` | incremental continuity 손실 |
| `DR_TARGET_CAPACITY_INSUFFICIENT` | target storage 부족 |
| `DR_TARGET_VALIDATION_FAILED` | checksum/manifest/flush 실패 |
| `DR_TEST_CLEANUP_FAILED` | test 자원 정리 실패 |
| `DR_SOURCE_FENCE_REQUIRED` | failover 전 source fence 필요 |

## 17. Selftest 설계

`bin/ablestack_vm_ftctl_selftest.sh`에 DR test를 추가한다.

| test | 설명 |
|---|---|
| `dr-profile-parse` | profile schema validation |
| `dr-checkpoint-write` | checkpoint JSON write/read |
| `dr-mover-manifest` | extent manifest 처리 |
| `dr-kvm-dirty-bitmap-mock` | QMP bitmap mock |
| `dr-vmware-cbt-mock` | CBT changed extent mock |
| `dr-target-ablestack-qcow2-mock` | qcow2 target writer mock |
| `dr-target-vmware-vmdk-mock` | VMDK target writer mock |
| `dr-test-failover-cleanup` | clone/overlay cleanup |

## 18. 외부 기술 참고

- [Broadcom VDDK latest overview](https://developer.broadcom.com/sdks/vmware-virtual-disk-development-kit-vddk/latest)
- [Broadcom VDDK programming guide - changed block tracking](https://techdocs.broadcom.com/us/en/vmware-cis/vsphere/vsphere-sdks-tools/8-0/virtual-disk-development-kit-programming-guide/backing-up-virtual-disks-in-vsphere/low-level-backup-procedures/changed-block-tracking-on-virtual-disks.html)

## 19. 2026-07-06 추가 보강: source 정상과 target readiness 분리

상세 기준은 [534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md](534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md)를 따른다.

VMware source VM이 vCenter에서 정상 조회되고 전원이 켜져 있어도, DR Plan이 target-ready라는 뜻은 아니다. FTCTL_DR 엔진은 source validation, data transfer acceptance, target materialization, restore point creation을 별도 상태로 기록해야 한다.

FTCTL event 단계:

| event | 의미 | Cloud 해석 |
|---|---|---|
| `source-validated` | VMware/KVM source inventory 확인 | source 정상 |
| `sync-start-accepted` | worker 시작 요청 접수 | 엔진 접수 |
| `target-volume-created` | target disk 생성 또는 attach 준비 | 대상 생성 중 |
| `target-vm-created` | target VM skeleton/materialized | 대상 VM 확인 |
| `restore-point-created` | durable checkpoint 생성 | RPO 산정 가능 |
| `target-ready` | Failover 가능한 target 준비 완료 | Plan READY 후보 |

`dr-status`는 위 이벤트를 요약해 `target_materialized`, `restore_point_present`, `last_target_durable_at`를 반환한다. 단, `dr-status` 자체가 remote inventory probe를 수행하지 않고 worker가 기록한 bounded state만 읽는다.

lock 설계 보강:

- action parent process는 accepted state 기록 후 global lock을 해제한다.
- worker는 plan/run lock으로 실행한다.
- worker가 lock에 막히면 `retryable=true`로 기록하고 `target-ready` 이벤트를 만들지 않는다.

완료 기준:

- `sync-start-accepted`만 있는 run은 Cloud에서 `SUCCEEDED`가 될 수 없다.
- `target-ready` event에는 target reference와 durable checkpoint가 포함된다.
- target writer가 실패하거나 lock에 막힌 경우 마지막 정상 restore point가 유지되고 신규 restore point가 생성된 것처럼 보고하지 않는다.
## 20. 2026-07-07 Update: VMware Source Disk Size And ABLESTACK Target Map Guard

The VMware to ABLESTACK validation for plan
`05527cbe-974e-4ca8-b65e-f844cb3420e7` failed correctly in FTCTL with
`DR_TARGET_DISK_SIZE_UNRESOLVED`. Both `vmware-disks.json` and
`ablestack-disks.json` contained disk `2000` with `sizeBytes=0`.

The FTCTL_DR engine contract is tightened:

- VMware source disk ids such as `2000` are inventory references, not local file
  paths.
- Source disk size must come from Cloud guided inventory, VMware/VDDK metadata,
  or an explicit validated override.
- For `VMWARE_TO_KVM`, missing or zero disk size is terminal and must be
  reported as a specific source/target disk readiness error.
- ABLESTACK RBD target storage must normalize to `targetType=rbd` and canonical
  raw block target semantics before materialization.
- `dr-status --plan <plan> --run <run> --json` must expose terminal error,
  worker state, source/target disk-map paths, and invalid disk counts.
- Selftests must include both unresolved source disk size and positive-size
  success paths.

The paired Cloud-side detailed design is
`538-cross-hypervisor-dr-vmware-to-kvm-disk-size-and-projection-hardening-design-20260707.md`.

## 21. 2026-07-07 Update: VMware Source VDDK Resolver

The VMware source driver must resolve and validate the actual VDDK library
directory before it starts nbdkit. Detailed layer design:
[543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md](543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md).

Implementation notes for `ablestack-qemu-exec-tools`:

- Add a reusable resolver in `lib/ftctl/dr_vddk.sh`.
- Candidate order:
  1. `credentials.source.vddkLibdir` or `credentials.source.libdir`
  2. `FTCTL_DR_VMWARE_VDDK_LIBDIR`
  3. `VDDK_LIBDIR`
  4. `/etc/profile.d/v2k-vddk.sh`
  5. `/opt/vmware-vix-disklib-distrib`
  6. `/usr/share/ablestack/v2k/compat/vsphere80/vddk`
  7. `/usr/share/ablestack/v2k/compat/vsphere67/vddk`
  8. `/usr/share/ablestack/v2k/compat/vsphere60/vddk`
- Validate a candidate with both file checks and
  `nbdkit --dump-plugin vddk libdir=<candidate>`.
- `ftctl_dr_vmware_write_capability()` must not mark `vddkReady=true` merely
  because `nbdkit vddk --help` succeeds.
- `dr_vmware_mover.sh` must pass `libdir=<resolved>` explicitly to nbdkit.
- The DR engine may reuse the installed VDDK asset layout that v2k installs,
  but it must not call v2k as the DR mover.

New FTCTL error mapping:

| Exit | Error code |
| --- | --- |
| 70 | `DR_VDDK_LIBDIR_UNRESOLVED` |
| 71 | `DR_VDDK_LIBRARY_LOAD_FAILED` |
| 69 | `DR_VMWARE_NBDKIT_FAILED` |

The scheduler must run VMware data-plane preflight before target storage
preparation so a missing VDDK library cannot create a partial target disk.

## 22. 2026-07-07 Update: VMware Mover NBD Source Graph

The VMware source driver now has an additional runtime contract after VDDK
libdir resolution and nbdkit startup. The VDDK-backed NBD socket must be passed
to `qemu-img` as a valid raw-over-NBD graph, not as a direct NBD JSON node with
`-f raw`.

Detailed design:
[544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md](544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md).

FTCTL implementation rules:

- Add `ftctl_vmware_mover_source_image_opts()` in
  `lib/ftctl/dr_vmware_mover.sh`.
- Use `qemu-img info --force-share --image-opts <source_opts>` as a bounded
  source graph preflight.
- Use `qemu-img convert --force-share -p -n --image-opts -O raw <source_opts>
  <target_uri>` for the VMware to ABLESTACK RBD full seed path.
- Remove the previous direct `json:{"driver":"nbd"...}` plus `-f raw` source
  form.
- Map source graph preflight failure to exit 72 and
  `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID`.

Error-code boundary:

| Code | Layer |
| --- | --- |
| `DR_VDDK_LIBDIR_UNRESOLVED` | VDDK path discovery |
| `DR_VDDK_LIBRARY_LOAD_FAILED` | VDDK library loadability |
| `DR_VMWARE_NBDKIT_FAILED` | nbdkit process/socket startup |
| `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` | QEMU source image graph |
| `DR_VMWARE_MOVER_FAILED` | convert/data copy after graph validation |

## 2026-07-14 Cutover Driver Addendum

VMware mover의 durable checkpoint 이후에 별도 cutover driver를 둔다.
`dr_cutover_storage.sh`는 qcow2 overlay와 RBD snapshot/clone을 provider별로
처리하고, `dr_cutover.sh`는 guest inspection, VirtIO preparation, isolated
test domain, boot validation 상태머신을 소유한다.

V2K 전체 Phase2는 호출하지 않는다. `lib/guestprep` shared library를 만들고
기존 V2K public function은 compatibility wrapper로 유지한다. FTCTL capability는
guest preparation, test domain, qcow2/RBD layer, QGA validation을 개별 feature로
광고한다. 상세 설계:
`554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md`.

### 2026-07-14 VMware Driver Correction

The old statement "invalid changeId switches to full resync" is not an
automatic fallback. FTCTL must return `RESEED_REQUIRED`; only an explicit,
audited action may execute `FULL_RESEED`. Shared CBT query/extent/patch
primitives, local journal, and Cloud commit acknowledgement follow
`555-cross-hypervisor-dr-vmware-cbt-incremental-and-transfer-metrics-design-20260714.md`.

### 2026-07-17 VMware Driver Mode-Decision Correction

The driver must not overwrite Scheduler intent. It builds a complete execution
row containing changeId, `LOCAL_DURABLE` state, baseline generation, last
sequence, and disk identity; then returns a structured requested/effective mode
decision.

One typed automatic whole-VM reseed may repair a missing baseline. A repeated
identical automatic reseed fails with `DR_CBT_RESEED_LOOP_DETECTED` before
opening source or target data. Empty or malformed helper JSON is a typed error,
not a raw traceback. Full-copy duration is measured, and incremental metrics
must be non-estimated.

Normative functions and tests:
`559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md`.
