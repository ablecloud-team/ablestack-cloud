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

# Cross Hypervisor DR FTCTL Runtime Contract Design

> 2026-08-06 forward cutover commit V2 correction: Cloud document
> [599](599-cross-hypervisor-dr-forward-cutover-commit-contract-and-authority-convergence-design-20260806.md)
> and FTCTL document 455 define the typed envelope, distinct Cloud/engine session
> identities, durable journal, and idempotent commit-status recovery.

> 2026-08-05 route-contract v2 correction: Cloud document
> [595](595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md)
> and FTCTL document 452 publish topology direction and provider pair as
> separate fields while retaining a normalized legacy alias.

> 2026-08-05 live-worker correction: document
> [594](594-cross-hypervisor-dr-live-worker-and-terminal-reconciliation-design-20260805.md)
> and FTCTL document 451 replace shared mutable worker state with typed,
> owner-specific evidence and a pure status merge.

> 2026-08-04 normative live-observation boundary update:
> Cloud document 592 and qemu document 449 define FTCTL power fields as
> projection-only, keep live KVM power under Mold Agent authority, and require
> a live source domain before KVM-to-VMware reverse data preflight can pass.
>
> 2026-08-04 normative initial reverse seed update:
> Cloud document 591 revision 2 and qemu document 448 revision 2 require a
> baseline-aware `AUTO` selector and read-only reverse preflight. The
> `failback-final` operation intent must not force `REVERSE_FINAL`.
>
> 2026-08-03 normative target materialization update:
> Cloud document 590 and qemu document 447 require a versioned ownership
> manifest, Agent-observed power/disk state, and monotonic generation/digest
> validation before FTCTL records target READY.

> 2026-07-31 latest correction: FTCTL_DR uses read-only transition preflight
> and no standalone fence-clear mutation. See Cloud 587 and qemu 444.

> Normative Failover projection update (2026-07-27):
> [577-cross-hypervisor-dr-failover-projection-evidence-and-compensation-design-20260727.md](577-cross-hypervisor-dr-failover-projection-evidence-and-compensation-design-20260727.md)
> defines completed-cycle evidence completeness, exact checkpoint hydration,
> bounded projection retry, and pre-promotion compensation.

> Normative Test Failover runtime update (2026-07-19):
> [562-cross-hypervisor-dr-test-artifact-contract-and-projection-isolation-design-20260719.md](562-cross-hypervisor-dr-test-artifact-contract-and-projection-isolation-design-20260719.md)
> defines the v3 typed artifact input, all-path rollback, and separation of
> finite operation status from continuous-sync protection authority.

작성일: 2026-06-30

대상 브랜치: `feature/ftctl-cloud-integration`

상위 문서:

- [505-cross-hypervisor-dr-ftctl-v2k-integration-design-20260630.md](505-cross-hypervisor-dr-ftctl-v2k-integration-design-20260630.md)
- [507-cross-hypervisor-dr-cloud-api-command-design-20260630.md](507-cross-hypervisor-dr-cloud-api-command-design-20260630.md)
- [508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md](508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md)
- [509-cross-hypervisor-dr-db-upgrade-and-entity-design-20260630.md](509-cross-hypervisor-dr-db-upgrade-and-entity-design-20260630.md)

## 1. 목적

이 문서는 `Cross Hypervisor DR`에서 Cloud backend와 qemu-side FTCTL runtime 사이의 contract를 정의한다.

기존 FTCTL은 이미 다음 KVM-to-KVM 보호 경로를 검증했다.

- `rbd -> rbd`
- `rbd -> qcow2`
- `qcow2 -> rbd`
- `qcow2 -> qcow2`

신규 DR orchestrator는 이 성공 경로를 변경하지 않는다. Cloud는 FTCTL을 공통 `DrPlan`/`DrRun` 모델 아래로 감싸되, qemu FTCTL runtime의 profile, lock, event, blockcopy, failover/failback 실행 소유권을 침범하지 않는다.

## 2. 책임 경계

| 영역 | Cloud DR | qemu FTCTL |
| --- | --- | --- |
| 사용자 API | `DrPlan`, `DrRun` API 제공 | 직접 제공하지 않음 |
| DB 상태 | plan/run/replica/restore point projection 저장 | Cloud DB 접근 없음 |
| VM/volume 준비 | Cloud VM/volume/resource 생성 | 준비된 target을 사용 |
| runtime profile | profile 생성 요청/metadata 전달 | profile 파일의 source of truth |
| blockcopy/xcolo | action 요청과 결과 projection | 실제 qemu/libvirt/blockcopy/xcolo 수행 |
| failover/failback | command orchestration, Cloud VM lifecycle | runtime finalize, mirror/fence state |
| events | FTCTL events를 읽어 `DrEvent`로 relay | `events.log` JSONL 작성 |
| cleanup | Cloud DB row와 Cloud-created resource cleanup | FTCTL runtime/profile/lock cleanup |

Cloud는 FTCTL profile/state 파일을 DB projection으로 재생성하거나 덮어쓰지 않는다. Projection 실패는 runtime 실패로 단정하지 않는다.

## 3. Cloud 호출 경로

Cloud UI와 API는 qemu host 또는 libvirt를 직접 호출하지 않는다.

호출 경로:

```text
UI
  -> Cloud API Cmd
  -> DrRunService / DrOrchestrator
  -> FtctlDrReplicationEngine or FtctlDrFencingAdapter
  -> existing FtctlService
  -> AgentManager / Mold Agent command
  -> qemu-side FTCTL script
  -> events.log / JSON response
```

금지:

- UI에서 host SSH/libvirt/qemu 직접 호출
- Cloud DB projection으로 FTCTL host state 파일 수정
- DrRun scheduler가 FTCTL timer/reconcile과 같은 VM을 동시에 조작
- projection refresh 실패를 이유로 FTCTL forced cleanup 실행

## 4. qemu-side 관련 파일

qemu repository 기준 구현 영향 가능성이 있는 파일:

| 파일 | 역할 | 변경 원칙 |
| --- | --- | --- |
| `bin/ablestack_vm_ftctl.sh` | CLI entrypoint | 기존 command 호환 유지 |
| `lib/ftctl/profile.sh` | profile load/validation | optional metadata만 추가 |
| `lib/ftctl/events.sh` | events log writer | event field 안정화 |
| `lib/ftctl/state.sh` | runtime state | Cloud projection으로 덮지 않음 |
| `lib/ftctl/orchestrator.sh` | protect/reconcile flow | 기존 성공 경로 변경 금지 |
| `lib/ftctl/blockcopy.sh` | blockcopy backend | rbd/qcow2 성공 경로 보존 |
| `lib/ftctl/failover.sh` | failover/failback | controller boundary 유지 |
| `lib/ftctl/fencing.sh` | manual/automatic fence | confirm/clear event 유지 |
| `lib/ftctl/standby.sh` | standby materialization | Cloud-created target 우선 |
| `lib/ftctl/xcolo.sh` | xcolo runtime | port slot/graph 성공 경로 보존 |

이 문서는 Cloud 설계 문서이지만, qemu-side 구현 변경이 필요한 경우 위 파일들의 contract를 기준으로 별도 qemu 문서 또는 patch를 작성한다.

## 5. FTCTL adapter mapping

Cloud backend class:

- `com.cloud.ftctl.dr.FtctlDrReplicationEngine`
- `com.cloud.ftctl.dr.FtctlDrFencingAdapter`
- `com.cloud.ftctl.dr.FtctlDrProjectionAdapter`

Mapping:

| Dr action | Existing FTCTL API/action | qemu runtime 의미 |
| --- | --- | --- |
| `createDrPlan` with import | `getFtctlProtection`, active row lookup | 기존 보호 연결 |
| `startDrSync` | register/protect/status/check 계열 | 보호 시작 또는 상태 확인 |
| `startDrFailover` | `failoverFtctlProtection` | secondary activation |
| `confirmDrFenceClear` | `confirmFtctlFence`, `clearFtctlFence` | manual fence 확인/해제 |
| `startDrFailback` | `failbackFtctlProtection` 또는 `failbackFtctlDrReplica` | source-controller failback |
| `startDrReprotect` | existing reprotect flow | 역할 전환 후 보호 재구성 |
| `adoptDrReplica` | `adoptFtctlDrReplica` | replica-controller disaster recovery |
| `deleteDrPlan` | `releaseFtctlProtection`, `releaseFtctlDrReplicaProtection` | 보호 해제/cleanup |
| projection refresh | `getFtctlCheck`, `getFtctlHealth`, `getFtctlEvents`, status sync | runtime 상태 relay |

`failback`과 `adopt`는 절대 같은 backend method로 합치지 않는다.

## 6. Profile contract

기존 FTCTL profile schema는 하위 호환을 유지한다.

신규 DR metadata가 필요하면 optional field로만 추가한다.

권장 optional fields:

| Field | 의미 |
| --- | --- |
| `FTCTL_PROFILE_DR_PLAN_UUID` | Cloud `DrPlan.uuid` |
| `FTCTL_PROFILE_DR_RUN_UUID` | 현재 Cloud `DrRun.uuid` |
| `FTCTL_PROFILE_DR_ENGINE_BINDING_ID` | `dr_plan.engine_binding_id` 또는 `ftctl_protection.id` |
| `FTCTL_PROFILE_DR_CONTROLLER_MODE` | `source-controller`, `replica-controller` |
| `FTCTL_PROFILE_DR_ACTION` | `sync`, `failover`, `failback`, `adopt`, `release` |

규칙:

- field가 없어도 기존 FTCTL 동작은 동일해야 한다.
- field가 있어도 blockcopy backend 선택이 바뀌면 안 된다.
- profile validation은 unknown optional `FTCTL_PROFILE_DR_*` field를 허용한다.
- Cloud는 qemu host의 profile 파일을 직접 편집하지 않는다.
- profile 생성이 필요한 경우 기존 FTCTL register/protect 경로를 통해 생성한다.

## 7. Event contract

FTCTL `events.log`는 JSON Lines로 유지한다.

Cloud projection이 안정적으로 읽을 수 있도록 다음 field를 권장한다.

| Field | Required | 설명 |
| --- | --- | --- |
| `ts` | yes | ISO timestamp 또는 epoch millis |
| `event` | yes | event name |
| `vm` | yes | VM/domain name |
| `action` | no | protect/failover/failback 등 |
| `phase` | no | blockcopy, xcolo, fencing, cleanup 등 |
| `result` | no | ok/warn/fail/running |
| `state` | no | runtime state |
| `progress` | no | 0-100 |
| `message` | no | 사람이 읽을 요약 |
| `error_code` | no | 표준화 가능한 오류 |
| `detail` | no | structured detail |
| `dr_plan_uuid` | no | Cloud plan uuid |
| `dr_run_uuid` | no | Cloud run uuid |
| `external_ref` | no | lock file, qmp job id, block job id 등 |

Cloud는 unknown field를 무시해야 한다. FTCTL은 기존 field를 제거하지 않는다.

## 8. 필수 event name

Cloud projection을 위해 다음 event는 안정적으로 유지한다.

| Event | 의미 |
| --- | --- |
| `profile.loaded` | profile 로드 성공 |
| `profile.invalid` | profile validation 실패 |
| `inventory.check` | VM/disk/network runtime inventory |
| `inventory.disks` | disk/backend mapping |
| `health.libvirt` | libvirt/qemu 연결 상태 |
| `blockcopy.start` | blockcopy 시작 |
| `blockcopy.progress` | blockcopy 진행률 |
| `blockcopy.ready` | blockcopy ready |
| `blockcopy.verify` | target verification |
| `xcolo.start` | xcolo 시작 |
| `xcolo.ready` | xcolo 준비 완료 |
| `fencing.required` | manual/automatic fencing 필요 |
| `fencing.confirmed` | operator 확인 |
| `fencing.cleared` | fence clear |
| `failover.start` | failover 시작 |
| `failover.done` | failover 완료 |
| `failback.await-command` | Cloud-managed failback 대기 |
| `failback.start` | failback 시작 |
| `failback.done` | failback 완료 |
| `adopt.start` | replica adopt 시작 |
| `adopt.done` | replica adopt 완료 |
| `protection.release.start` | 보호 해제 시작 |
| `protection.release.done` | 보호 해제 완료 |
| `protection.unprotect.force-cleanup-warning` | forced cleanup warning |

기존 event 이름이 이미 다르면 Cloud adapter는 compatibility parser를 제공한다. 다만 신규 event를 추가할 때는 위 이름을 우선한다.

## 9. State projection mapping

### 9.1 FTCTL to DrPlan

| FTCTL state | DrPlan state | 비고 |
| --- | --- | --- |
| no active protection | `CREATED` 또는 no plan | import 여부에 따라 다름 |
| protecting / syncing | `SYNCING` | `DrRun(type=SYNC)`와 연결 |
| protected + mirroring | `READY` | target readiness 검증 필요 |
| paused | `PAUSED` | resume 가능 |
| failover_candidate | `FAILBACK_READY` 또는 `ERROR` | fencing state에 따라 결정 |
| failed_over | `FAILED_OVER` | active side secondary |
| failback_ready | `FAILBACK_READY` | source-controller flow |
| reprotecting | `REPROTECTING` | |
| unrecoverable error | `ERROR` | error_code 저장 |

### 9.2 FTCTL to DrReplica

| FTCTL/runtime | DrReplica state |
| --- | --- |
| standby VM/target exists but not verified | `SKELETON_READY` |
| blockcopy running | `MATERIALIZING` |
| blockcopy ready + verify ok | `TARGET_READY` |
| standby activated | `ACTIVE` |
| stale/missing target | `STALE` |
| runtime failure | `ERROR` |

### 9.3 FTCTL action result to DrRun

| FTCTL result | DrRun state |
| --- | --- |
| `ok` | `SUCCEEDED` |
| `warn` | `SUCCEEDED` with warning event or `ROLLBACK_REQUIRED` if cleanup incomplete |
| `fail` | `FAILED` |
| `locked` | `FAILED` with `DR_ENGINE_BUSY` or retryable `QUEUED` |
| timeout | `FAILED` with retryable flag if engine state unknown |

## 10. Error mapping

| FTCTL condition | Cloud error code |
| --- | --- |
| lock file exists / operation running | `DR_ENGINE_BUSY` |
| profile missing | `DR_ENGINE_PROFILE_MISSING` |
| stale state without profile | `DR_ENGINE_STALE_STATE` |
| libvirt connection failure | `DR_ENGINE_UNAVAILABLE` |
| qemu block job failure | `DR_REPLICATION_FAILED` |
| target verification failed | `DR_TARGET_NOT_READY` |
| manual fence needed | `DR_FENCE_REQUIRED` |
| automatic fence unavailable | `DR_FENCE_POLICY_UNAVAILABLE` |
| Cloud-created target missing | `DR_TARGET_MAPPING_INVALID` |
| forced cleanup warning | `DR_CLEANUP_PARTIAL` |

Cloud adapter는 FTCTL raw message를 보존하되 UI에는 표준 error code와 짧은 message를 우선 제공한다.

## 11. Timer/reconcile 경계

기존 FTCTL timer/reconcile은 유지한다.

규칙:

- Cloud `DrRun`이 destructive action을 실행 중이면 FTCTL timer가 같은 VM에서 auto-rearm 같은 충돌 작업을 하지 않아야 한다.
- FTCTL이 이미 lock을 잡고 있으면 Cloud adapter는 `DR_ENGINE_BUSY`로 변환한다.
- Cloud scheduler는 FTCTL timer를 대체하지 않는다.
- Projection refresh는 read-only action으로 취급한다.
- Cloud-managed failback 대기 상태에서는 FTCTL이 forward path를 auto-rearm하지 않아야 한다.

필요한 경우 FTCTL profile/state에 optional operation marker를 둔다. 단, marker가 없어도 기존 경로는 유지되어야 한다.

## 12. Idempotency

## 2026-07-07 Update: VMware VDDK Libdir Runtime Contract

The VMware to ABLESTACK sync path now requires an explicit data-plane
contract for the worker host that runs the VMware source mover. The detailed
layer-by-layer design is
[543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md](543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md).

Runtime profile and credential contract:

```json
{
  "credentials": {
    "source": {
      "type": "VCENTER",
      "endpoint": "10.10.21.10",
      "principal": "administrator@ablecloud.local",
      "tlsVerify": true,
      "auth": {
        "password": "secret"
      },
      "vddkLibdir": "/usr/share/ablestack/v2k/compat/vsphere80/vddk",
      "dataPlaneHostId": 1,
      "dataPlaneHostUuid": "host-uuid"
    }
  }
}
```

Rules:

- `vddkLibdir` is a non-secret runtime hint.
- Cloud should populate it from worker host details or an operator override
  when available.
- Agent should enrich the profile with its detected host VDDK path when the
  backend profile is missing the value.
- FTCTL must still auto-discover and validate VDDK libdir as the final safety
  layer.
- The runtime must not depend on v2k execution. It may only reuse the
  installed VDDK asset layout under `/usr/share/ablestack/v2k/compat`.

Status JSON should include a VMware data-plane diagnostic object:

```json
{
  "vmware_data_plane": {
    "vddk_ready": true,
    "vddk_libdir": "/usr/share/ablestack/v2k/compat/vsphere80/vddk",
    "vddk_library_version": "8",
    "nbdkit_vddk": true,
    "mover_ready": true,
    "missing_code": ""
  }
}
```

Error mapping is extended:

| FTCTL condition | Cloud error code |
| --- | --- |
| no usable VDDK libdir candidate | `DR_VDDK_LIBDIR_UNRESOLVED` |
| libdir exists but nbdkit cannot load it | `DR_VDDK_LIBRARY_LOAD_FAILED` |
| nbdkit process/socket fails after libdir resolution | `DR_VMWARE_NBDKIT_FAILED` |

These fields must be copied into `dr_run.last_status_json` and projection
events without exposing credential secrets.

Cloud idempotency:

- `DrRun.idempotency_key`로 중복 action을 막는다.
- 같은 plan에 active destructive run이 있으면 새 action을 거부한다.

FTCTL idempotency:

- 이미 같은 target이 준비되어 있으면 재사용 또는 clear conflict를 반환한다.
- blockcopy/xcolo가 이미 running이면 status/progress를 반환하거나 locked를 반환한다.
- release/unprotect는 이미 정리된 상태에서 성공 또는 warning으로 종료해야 한다.

Cloud adapter는 FTCTL의 idempotent success와 "실제 새 작업 시작"을 구분해 event에 남긴다.

## 13. Cleanup contract

Cloud cleanup:

- `dr_plan`, `dr_run`, `dr_replica` soft delete
- Cloud-created standby VM/volume cleanup
- `ftctl_protection` row cleanup은 기존 FTCTL service를 통해 수행

FTCTL cleanup:

- runtime lock 제거
- profile/state 파일 제거
- qemu-nbd/qmp/blockjob cleanup
- xcolo runtime cleanup
- warning event 기록

Forced cleanup:

- UI/API에서 explicit acknowledgement 필요
- FTCTL은 `result=warn`, `forced=true`, `warnings=[...]`를 반환할 수 있다.
- Cloud는 partial cleanup warning을 `DrEvent`와 `DrRun.error_code=DR_CLEANUP_PARTIAL` 또는 warning field로 보존한다.
- Forced cleanup이 성공해도 과거 runtime failure evidence를 삭제하지 않는다.

## 14. qemu-side 변경 필요 여부

Phase 1 Cloud 구현에서 qemu-side 변경이 필요 없는 경우:

- 기존 FTCTL API와 events로 projection이 충분한 KVM-to-KVM import/read-only 표시
- 기존 failover/failback/release API를 그대로 호출하는 action
- 기존 `events.log` parser로 필요한 state를 얻을 수 있는 경우

qemu-side 변경이 필요한 경우:

- `DrRun` correlation을 위해 event에 `dr_plan_uuid`, `dr_run_uuid`가 필요할 때
- existing event에 progress/state/error가 부족해 UI가 오판할 때
- FTCTL timer/reconcile이 Cloud `DrRun`과 충돌할 때
- forced cleanup warning 구조가 표준 JSON으로 나오지 않을 때
- new optional profile field를 허용해야 할 때

qemu-side 변경은 별도 qemu repo 문서와 테스트를 동반한다.

## 15. Test contract

Cloud adapter test:

- FTCTL locked response -> `DR_ENGINE_BUSY`
- FTCTL fail response -> `DrRun.state=FAILED`
- FTCTL warn response -> warning event 보존
- projection stale -> plan destructive cleanup 없음
- existing active `ftctl_protection` import -> `DrPlan.engine_binding_type=FTCTL`
- failback과 adopt action 분리

qemu-side compatibility test:

- 기존 `rbd -> rbd` 보호 PASS 유지
- 기존 `rbd -> qcow2` 보호 PASS 유지
- 기존 `qcow2 -> rbd` 보호 PASS 유지
- 기존 `qcow2 -> qcow2` 보호 PASS 유지
- optional `FTCTL_PROFILE_DR_*` field가 없어도 기존 selftest PASS
- optional `FTCTL_PROFILE_DR_*` field가 있어도 backend 선택 변화 없음
- events.log unknown field가 기존 parser를 깨지 않음

Integration smoke:

1. 기존 FTCTL UI에서 보호 등록/조회가 그대로 동작한다.
2. 신규 DR UI에서 같은 VM에 중복 plan 생성이 거부된다.
3. 기존 FTCTL 보호를 `DrPlan`으로 import하면 runtime state가 projection된다.
4. FTCTL action 실패 시 Cloud async job/DrRun/DrEvent 모두 실패 근거를 보존한다.
5. Projection refresh 실패는 FTCTL cleanup을 유발하지 않는다.

## 16. AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| FTCTL 사용 방식 | FTCTL UI/API가 직접 보호 운영 | 신규 DR는 FTCTL을 KVM-to-KVM engine adapter로 감쌈 |
| Runtime source | qemu FTCTL profile/state/events | 그대로 유지 |
| Cloud DB | FTCTL detail/protection 중심 | `DrPlan`/`DrRun` projection 추가 |
| Event | FTCTL events를 기능별로 소비 | `DrEvent`로 relay하되 raw runtime은 FTCTL이 소유 |
| Failback/adopt | 기존 FTCTL action 분리 | 신규 DR API에서도 source-controller/replica-controller 분리 유지 |
| Cleanup | FTCTL release/forced cleanup | Cloud cleanup과 FTCTL cleanup 책임 분리 |
| qemu 변경 | 기존 성공 경로 검증됨 | optional metadata/event 안정화만 허용 |

## 2026-07-06 보강: FTCTL Acceptance와 Background Worker 계약

FTCTL runtime action 계약은 [533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md](533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md)를 우선한다.

FTCTL 구현 원칙:

- `--wait=false` action은 장기 sync/failover/failback 작업을 같은 CLI 프로세스에서 수행하지 않는다.
- action command는 profile 저장, run/status accepted 기록, background worker spawn 후 즉시 accepted JSON을 반환한다.
- background worker는 실제 transfer/scheduler/driver 작업과 progress 갱신을 담당한다.
- `dr-status`는 `accepted`, `runtime_exists`, `profile_exists`, `run_exists`, `external_job_ref`를 반환한다.
- Cloud projection adapter가 run state와 grace를 기준으로 `not_found`의 terminal 여부를 판단한다.

## 2026-07-06 추가 보강: retryable lock과 bounded status contract

상세 기준은 [533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md](533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md)의 19장과 20장을 따른다.

### Agent contract

`LibvirtFtctlDrStatusCommandWrapper`는 Cloud command wait 값만 신뢰하지 않고 host process hard timeout을 적용한다.

권장 기준:

- status hard timeout: 5 seconds
- kill-after grace: 2 seconds
- timeout result: `DR_STATUS_TIMEOUT`
- timeout은 plan terminal failure가 아니라 projection stale로 해석한다.
- 동일 plan/run status 호출은 single-flight로 합친다.

Agent timeout response 예:

```json
{
  "command": "dr-status",
  "result": "timeout",
  "state": "UNKNOWN",
  "step": "status-timeout",
  "progress": 0,
  "error_code": "DR_STATUS_TIMEOUT",
  "retryable": true
}
```

### FTCTL lock contract

`dr-sync-start`, `dr-sync-pause`, `dr-sync-resume`, failover/failback 계열 action이 FTCTL lock에 막히면 JSON은 retryable metadata를 유지한다.

```json
{
  "command": "dr-sync-start",
  "result": "locked",
  "lock_file": "/run/ablestack-vm-ftctl/lock",
  "holder_command": "dr-sync-pause",
  "holder_pid": "3981802",
  "holder_age_sec": "14",
  "exit_code": 20,
  "retryable": true,
  "retry_after_sec": 2
}
```

Cloud는 이 응답을 `DR_ENGINE_BUSY_RETRYABLE`로 변환하고, retry window 초과 전에는 terminal failure로 보지 않는다.

### `dr-status` contract

`dr-status`는 다음 작업을 수행하지 않는다.

- global FTCTL lock 획득
- scheduler/worker 시작
- remote Mold/vCenter 호출
- qemu/libvirt/blockjob 실시간 probe
- 전체 events.log unbounded scan

`dr-status`는 다음 파일만 bounded 방식으로 읽는다.

- plan `profile.json`
- plan `status.state`
- run `runs/<run_uuid>.state`
- bounded event tail

수용 기준:

- `ablestack_vm_ftctl dr-status --plan <uuid> --json`은 정상 상태에서 1초 이내 반환한다.
- 비정상 파일/로그 상태에서도 5초 이내 timeout JSON을 반환한다.
- status timeout 후 host에 orphan `dr-status` 프로세스가 남지 않는다.

## 2026-07-06 추가 보강: sync accepted와 target readiness 분리

상세 기준은 [534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md](534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md)를 따른다.

`dr-sync-start`는 장기 동기화 완료가 아니라 작업 접수와 worker 시작을 의미한다. 따라서 accepted 응답은 다음 범위까지만 보장한다.

- profile 저장
- run state `ENGINE_ACCEPTED` 또는 `ACCEPTED` 기록
- background worker spawn 예약 또는 시작
- Cloud가 추적할 run id 반환

worker는 target materialization 진행 중 다음 event를 기록한다.

```json
{"event":"target-volume-created","volume":"...","at":"..."}
{"event":"target-vm-created","target_vm_id":"...","at":"..."}
{"event":"restore-point-created","restore_point_id":"...","durable_at":"..."}
{"event":"target-ready","target_vm_id":"...","durable_at":"..."}
```

`dr-status` 추가 필드:

```json
{
  "target_materialized": false,
  "target_vm_present": false,
  "target_storage_present": true,
  "target_network_present": false,
  "restore_point_present": false,
  "last_target_durable_at": ""
}
```

lock 설계:

- parent action process는 global lock을 짧게 잡고 profile/run accepted 상태만 기록한다.
- background worker는 parent lock release 이후 시작한다.
- worker는 plan/run 단위 lock을 사용한다.
- worker가 lock에 막히면 `retryable=true`, `retry_after_sec`를 기록하고 run을 terminal success로 만들지 않는다.

## 2026-07-07 Addendum: Serving process and disk map authority

The terminal projection contract is valid only when the Cloud API process serving `:8080` is the same process that loaded the updated DR classes. After changed-class deployment, operational validation must compare `mold.service` `MainPID` with the PID owning listener port `8080`. If they differ, API/UI responses are stale and the deployment is not valid for DR testing.

The FTCTL runtime status contract now separates source and target disk maps:

```json
{
  "source_disk_map_path": "/run/ablestack-vm-ftctl/dr-runtime/plans/<plan>/vmware-disks.json",
  "target_disk_map_path": "/run/ablestack-vm-ftctl/dr-runtime/plans/<plan>/ablestack-disks.json",
  "disk_map_role": "target",
  "disk_map_path": "/run/ablestack-vm-ftctl/dr-runtime/plans/<plan>/ablestack-disks.json"
}
```

`disk_map_path` remains for backward compatibility. For an ABLESTACK target it must point to the target map, never to VMware source metadata. VMware source disk ids such as `2000` are source inventory references and must not be used as local qemu paths for target preparation or size detection.

The detailed layered design is documented in [537-cross-hypervisor-dr-serving-process-and-disk-map-contract-design-20260707.md](537-cross-hypervisor-dr-serving-process-and-disk-map-contract-design-20260707.md).

## 2026-07-07 Update: FTCTL Runtime Disk Map Contract

For VMware to ABLESTACK sync, FTCTL must reject unresolved disk maps before
creating target disks or target VMs.

Runtime contract additions:

- VMware disk ids are inventory references; they are not local file paths.
- `vmware-disks.json` must contain positive `sizeBytes` for every selected
  source disk.
- `ablestack-disks.json` must normalize RBD target storage to canonical
  `targetType=rbd` and raw block semantics.
- `dr-status --plan <uuid> --run <uuid> --json` returns terminal fields even
  after the initial action process has released the global lock.
- Terminal JSON must include `state`, `step`, `error_code`, `worker_state`,
  invalid disk count, and source/target disk map paths.

Cloud-side projection and DB mapping are specified in
`538-cross-hypervisor-dr-vmware-to-kvm-disk-size-and-projection-hardening-design-20260707.md`.

## 2026-07-07 Update: ftctl Contract For Disk-level Storage Authority

The Cloud/API/UI storage semantics are refined in
[539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md](539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md).

ftctl does not require a CLI or state-file schema change for this pass.

Runtime rules:

- ftctl consumes Cloud's backend-generated canonical profile.
- For disk placement, `mapping.disks[].target.storageRef` and the backend-owned
  runtime fields under the same target object are authoritative.
- Top-level storage is only a Cloud guided fallback for legacy or incomplete
  disk-row input.
- ftctl keeps its final preflight guard and returns `CONFIG_INCOMPLETE` or
  `DR_TARGET_MAPPING_INVALID` when the canonical profile lacks target storage
  or target path data.

## 2026-07-07 Update: ftctl Impact Of DR Plan SharedFS Dialog Standard

The SharedFS-style DR Plan dialog standard is documented in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md).

No ftctl CLI, profile, or state-file schema change is required.

ftctl contract rules:

- ftctl receives only backend-generated canonical profiles.
- UI section names, active collapse keys, field hints, and review summary
  values must never be written into ftctl profile JSON.
- Disk-level storage authority remains the contract defined in
  [539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md](539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md).
- ftctl continues to validate runtime paths and target mappings as the final
  safety guard after Cloud backend validation.

This keeps the UI presentation standard independent from the runtime engine
contract.

## 2026-07-07 Update: ftctl Impact Of Modal Alert And Gutter Refinement

The DR Plan modal alert/gutter refinement is documented in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md#13-2026-07-07-refinement-dark-alert-and-right-gutter).

No ftctl change is required.

Runtime contract remains:

- ftctl receives backend-generated profile JSON only;
- alert style, modal dimensions, scrollbars, and CSS class names are never
  written to ftctl profiles;
- ftctl validation continues to operate on storage, disk mapping, worker, and
  runtime path data only.

This confirms the visual fix is outside the engine boundary.

## 2026-07-07 Update: VMware Mover Runtime Contract

The VMware to ABLESTACK sync test for plan
`ba4f53f8-eb17-41cd-bbe6-7e746772f209` reached target disk preparation but
failed during the VMware data-plane cycle with
`DR_VMWARE_MOVER_UNAVAILABLE`.

The complete layered design is documented in
[542-cross-hypervisor-dr-vmware-mover-and-projection-convergence-design-20260707.md](542-cross-hypervisor-dr-vmware-mover-and-projection-convergence-design-20260707.md).

Runtime contract additions:

- `FTCTL_DR_VMWARE_MOVER` is no longer an operator-only hidden prerequisite.
  FTCTL must ship a default mover path and expose mover capability through
  preflight/status JSON.
- VMware mover availability must be checked before ABLESTACK target storage is
  allocated.
- `dr-status --json` terminal fields are authoritative even when the original
  action answer was `accepted`.
- `DR_VMWARE_MOVER_UNAVAILABLE` maps to terminal sync failure unless the
  runtime explicitly marks it retryable.
- `DR_VMWARE_MOVER_FAILED` and `DR_VMWARE_NBDKIT_FAILED` are terminal
  data-plane failures surfaced from the bundled mover after preflight succeeds.
- `target_storage_present=true` with `target_vm_present=false` remains a
  failure or pending materialization state; it is never READY.

## 2026-07-07 Update: VMware Mover Source Graph Runtime Contract

The VMware to ABLESTACK sync test for plan
`987bb250-3b5a-4053-9720-2ff93b4cc88c` reached VDDK/nbdkit successfully but
failed because `dr_vmware_mover.sh` passed a direct NBD JSON node to `qemu-img`
while forcing `-f raw`.

Detailed design:
[544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md](544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md).

Runtime contract additions:

- `dr_vmware_mover.sh` must expose VDDK NBD sockets to `qemu-img` as an explicit
  raw-over-NBD source graph.
- `qemu-img info --image-opts` source graph preflight failure maps to exit 72
  and `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID`.
- `dr-status --json` must project exit 72 as terminal `state=ERROR`,
  `worker_state=FAILED`, and `worker_exit_code=72`.
- `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` is terminal for the current run, but it
  does not imply a DB schema problem or a vCenter credential problem.
- Existing `dr_plan`, `dr_run`, `dr_run_step`, `dr_event`, `dr_replica`, and
  `dr_replica_disk` projection fields carry the error; no new table or column is
  required.

## 2026-07-08 Update: VMware VDDK Connect Contract Runtime Contract

The VMware to ABLESTACK sync test for plan
`71182935-11c6-4ed3-aeec-ebde1486bdfa` reached the raw-over-NBD source graph but
failed inside VDDK connect:

```text
VixDiskLib_ConnectEx: One of the parameters was invalid
```

Detailed design:
[545-cross-hypervisor-dr-vmware-vddk-connect-contract-design-20260708.md](545-cross-hypervisor-dr-vmware-vddk-connect-contract-design-20260708.md).

Runtime contract additions:

- Cloud must generate a canonical VMware `source` object in the FTCTL profile
  mapping, not only top-level `sourceExternalRef`.
- VMware source readiness must validate endpoint, credential, source VM ref,
  and source disk paths before long-running sync work is dispatched.
- The async FTCTL worker must create a run snapshot for powered-on VMware
  sources before source-open preflight and base transfer.
- Before creating that snapshot, the worker must check VM/disk CBT state and
  enable `ctkEnabled=true` plus selected disk `<scsiX:Y>.ctkEnabled=true` when
  policy allows auto-enable.
- The mover must pass `snapshot=<snapshot-moref>` and the base VMDK path read
  from source inventory.
- FTCTL must validate VDDK connect parameters separately from QEMU graph
  validation.
- Exit 73 maps to `DR_VMWARE_VDDK_CONNECT_INVALID`.
- Exit 74 maps to `DR_VMWARE_VDDK_EXPORT_UNAVAILABLE`.
- Exit 75 maps to `DR_VMWARE_VDDK_SOURCE_LOCKED`.
- Exit 76 maps to `DR_VMWARE_VDDK_OPEN_DENIED`.
- Exit 77 maps to `DR_VMWARE_CBT_DISABLED`.
- Exit 78 maps to `DR_VMWARE_CBT_ENABLE_FAILED`.
- Exit 79 maps to `DR_VMWARE_CBT_VERIFY_FAILED`.

### 2026-08-10 VMware CBT activation evidence update

Exit 79 is retained for compatibility, but a powered-on source VM must no
longer fail solely because `config.changeTrackingEnabled` remains false after
ExtraConfig is saved. Live preflight proved operational CBT through CTK files,
a per-disk change ID, and successful `QueryChangedDiskAreas` while that VM-level
signal remained false. New implementations use the normal run snapshot as the
activation boundary and require evidence for every selected disk. The complete
runtime, retry, cleanup, and projection contract is defined in
`600-cross-hypervisor-dr-vmware-cbt-activation-convergence-design-20260810.md`.
- Exit 80 maps to `DR_VMWARE_CBT_DISK_ID_UNRESOLVED`.
- Exit 84 maps to `DR_VMWARE_CBT_SNAPSHOT_CONFLICT`.
- Exit 72 remains `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID`.
- Existing plan/run/status JSON fields carry these errors; no DB migration is
  required.

## 2026-07-08 Update: VMware Snapshot MoRef Resolve Runtime Contract

The VMware to ABLESTACK sync test for plan
`e08b9ef0-8a7a-42f6-bf0a-9e9f41f2fbee` proved that a run snapshot can be
created successfully while `govc snapshot.tree -json` still does not expose the
snapshot MoRef required by VDDK.

Detailed design:
[545-cross-hypervisor-dr-vmware-vddk-connect-contract-design-20260708.md](545-cross-hypervisor-dr-vmware-vddk-connect-contract-design-20260708.md#29-live-snapshot-moref-resolve-and-payload-stability-follow-up---2026-07-08).

Runtime contract additions:

- ftctl must resolve run snapshot MoRef with
  `govc object.collect -json <vm-ref> snapshot.rootSnapshotList` first.
- `govc snapshot.tree -json` remains only a fallback resolver.
- Exit `81` maps to `DR_VMWARE_SNAPSHOT_REF_UNRESOLVED`.
- Snapshot cleanup ownership starts immediately after successful
  `snapshot.create`, not after successful MoRef resolve.
- `dr-status --json` must include a redacted `source_snapshot` object that
  reports create, resolve, ref-present, cleanup-required, and error state.
- If snapshot resolve fails before VDDK source-open starts, `source_open` must
  be present with `checked=false` and
  `skippedReason=SOURCE_SNAPSHOT_REF_UNRESOLVED`.
- Cloud may store full redacted status in `dr_run.last_status_json`, but step
  details and plan/run error messages must use compact summaries.

## 2026-07-10 Normative Scheduler And Checkpoint Update

- FTCTL checkpoints are synchronization evidence, not user-selectable
  point-in-time recovery images.
- Test failover and failover atomically lock the latest completed checkpoint.
- The checkpoint reference includes Plan UUID, run UUID, and sequence.
- Scheduler cadence is start-to-start; transfer duration is not added to the
  configured RPO interval.
- Source checkpoint acquisition and target durability timestamps are measured
  independently.
- Preferred status fields use `checkpoint_*`; `restore_point_*` remains a
  temporary read-only alias.

Detailed design:
`549-cross-hypervisor-dr-checkpoint-history-event-rpo-design-20260710.md`.

## 2026-07-10 Normative Current/Completed Checkpoint Status Contract

FTCTL status separates the current transfer checkpoint from the latest
completed checkpoint. Cycle start changes only `current_checkpoint_*` fields;
successful target flush/verification and restore-points JSONL append advance
`latest_completed_checkpoint_*` fields.

`dr-status --json` exposes both sets. When completed state keys are absent, it
reconstructs them from the last valid JSONL record. Cloud must not use legacy
`checkpoint_sequence` as completed evidence. Agent typed answers carry both
sets without credentials or raw secret-bearing profile data.

Detailed shell write ordering, fallback, Agent mapping, and self-tests:
`550-cross-hypervisor-dr-protection-view-cache-and-completed-checkpoint-design-20260710.md`.

## 2026-07-14 UI Live Cache Impact

No FTCTL command, profile, scheduler, checkpoint, CBT, VDDK, or snapshot
contract changes are required. Steady UI refresh reads the Cloud DB cache only.
FTCTL status is still collected exclusively by the Cloud projection scheduler
or an explicit `refreshDrProtectionView` async job through Agent.

The UI/API read-consistency design and the test that guards against accidental
direct FTCTL polling are defined in
`552-cross-hypervisor-dr-async-read-consistency-and-live-cache-ui-design-20260714.md`.

## 2026-07-14 Normative DR Lock And Quiesce Contract

The long-lived `FTCTL_DR_RUNTIME_WORKER=1 dr-sync-start` process must not hold
the legacy global FTCTL lock. The legacy lock remains unchanged for existing
FT/HA, blockcopy, and xcolo paths. DR uses plan-scoped locks:

```text
plan.lock       short profile/run mutations
cycle.lock      one replication cycle only
transition.lock one test/failover/failback/release transition
checkpoint-N.lease selected completed checkpoint lifetime
```

Pause/resume/stop uses an atomic generation-based control request and
acknowledgment. Test Failover first quiesces the Scheduler, then leases the
latest completed checkpoint. No lock is held during RPO interval sleep or
while a worker waits for a control acknowledgment.

Detailed shell helper contracts, lock ordering, status fields, and self-tests:
`553-cross-hypervisor-dr-continuous-sync-control-and-quiesce-lock-design-20260714.md`.

## 2026-07-14 Guest Preparation And Cutover Contract (Test Domain Portion Superseded)

The direct Test Failover domain lifecycle in this legacy section is superseded
by the Cloud-managed artifact-only contract in the 2026-07-19 section below.
The real Failover `CUTOVER_READY` boundary remains valid.

`dr-test-failover`의 성공 조건은 test overlay 생성이 아니다. FTCTL은 아래
단계를 모두 완료한 경우에만 `TEST_RUNNING`을 반환한다.

1. Scheduler pause acknowledgment와 checkpoint lease
2. qcow2 또는 RBD writable test layer 생성
3. guest OS inspection
4. Linux initramfs 또는 Windows WinPE VirtIO preparation
5. source firmware/Secure Boot와 일치하는 isolated test domain 정의
6. domain start와 configured boot validation 성공

실제 `dr-failover`는 guest preparation 후 `CUTOVER_READY`에서 FTCTL 책임을
끝낸다. Cloud-managed target VM start와 active-side commit은 Cloud backend
책임이다. Runtime capability에는 storage driver, guest preparation, test domain,
boot validation 지원 여부를 각각 명시한다.

상세 파일, 상태, 오류, cleanup 계약:
`554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md`.

## 2026-07-16 Cycle Journal And Commit-State Addendum

Each VMware replication cycle persists a Plan-scoped journal through
`DATA_COPIED`, `METADATA_PREPARED`, and `LOCAL_DURABLE`. The mover validates
its result JSON with the same serializer before any source snapshot or CBT
baseline mutation. Invalid result serialization returns the typed error
`DR_CBT_METRICS_INVALID` and preserves enough journal evidence to select a
deterministic retry mode.

Metadata-only resume is allowed only when the journal proves source snapshot,
CBT range, target disk identity, copied-byte result, and target durability.
Missing or inconsistent proof requires `RESEED_REQUIRED`; successful physical
copy alone never advances the committed CBT baseline.

Detailed file layout, atomic writes, recovery rules, and exit mapping:
`557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md`.

## 2026-07-17 Baseline Row And Mode Decision Addendum

Every mover row carries disk identity, committed changeId, baseline state,
baseline generation, and last sequence. Scheduler-requested mode is immutable;
the mover produces a separate structured effective-mode decision. An identical
second automatic reseed stops before source open, and malformed helper JSON is
a typed error without raw traceback. The journal/status field set and live
acceptance contract are defined in
`559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md`.

## 2026-07-19 Artifact-Only Test Failover Runtime Contract

FTCTL Test Failover authority ends at a validated, checkpoint-derived artifact
manifest. FTCTL may create RBD clones, immutable file copies/overlays, retain
checkpoint leases, and perform offline VirtIO preparation. It must not define,
start, stop, or undefine the customer Test Failover VM. `TEST_RUNNING` is a
Cloud session projection; FTCTL reports `TEST_ARTIFACTS_READY` and
`TEST_ARTIFACTS_CLEANED`. File-backed drivers must prove immutable backing or
reject the operation.

This section supersedes the earlier isolated test-domain runtime contract. The
normative v2 contract is
`561-cross-hypervisor-dr-cloud-managed-test-failover-lifecycle-design-20260719.md`.

## 2026-07-25 Site-Derived Failback Runtime Addendum

일반 `FTCTL_DR` source-controller failback의 endpoint와 credential authority는
action request가 아니라 DR Plan의 등록 Site다.

- active Site: `plan.target_site_id`
- normal failback destination: `plan.source_site_id`
- credential: `DrSiteCredentialService.resolveCredential(site)`
- operator request: reason, acknowledgement, force 같은 non-secret intent만 허용

UI/API는 `failbacktargetmoldtype`, `remotemold*`, `targetmold*`를 전달하지
않는다. Cloud는 Site-derived runtime profile을 Agent에 보내고, FTCTL runtime은
기존 `credentials.json` mode `0600` 및 durable profile redaction 계약을
유지한다.

FTCTL은 Mold를 선택하지 않는다. FTCTL의 failback 책임은 reverse data copy,
checkpoint/disk-map validation, finalize와 reprotect data-plane 준비다. Cloud가
VM과 Site lifecycle 및 최종 authority commit을 담당한다.

상세 코드/API/DB 계약은
`571-cross-hypervisor-dr-site-derived-failback-contract-design-20260725.md`를
따른다.

## 2026-07-26 Failback Data-Ready / Lifecycle Commit Correction

2026-07-25 Site-derived credential 계약은 유지한다. 다만 FTCTL reverse
checkpoint 완료를 곧바로 failback 완료로 취급하는 해석은 폐기한다.

- FTCTL reverse-copy 완료: `FAILBACK_DATA_READY`, authority `TARGET`
- Cloud VM lifecycle 완료: TARGET OFF, SOURCE ON, boot validation READY
- FTCTL authority commit ACK: `FAILBACK_COMPLETED`, authority `SOURCE`

`failback_session_id` 존재만으로 Plan/Run을 완료하지 않는다. Cloud 상태기계와
완료 조건은 문서 574, FTCTL 계약은 qemu 문서 214가 이전 failback 완료
조건보다 우선한다.

## 2026-07-27 Failback Generation and Rollback Fence Correction

문서 574의 commit 계약은 유지하되 scheduler resume와 rollback은 다음으로
강화한다.

- transition마다 하나의 control generation만 생성
- 새 worker가 pending generation을 채택하고 같은 generation을 ACK
- durable commit journal 기반 멱등 status/retry
- rollback 전에 scheduler `STOPPED/IDLE` ACK 확보
- TARGET authority에서 forward cycle hard fence

Cloud/Agent/DB 수렴 계약은 문서 575, FTCTL 상세 control protocol은 qemu
문서 215가 우선한다.

## 2026-07-27 Late ACK and Authority Snapshot Contract

Failback commit timeout 뒤 동일 generation의 ACK가 늦게 도착할 수 있으므로
`UNKNOWN`을 terminal 상태로 고정하지 않는다. FTCTL status adapter는 다음
scope를 명시적으로 요청한다.

| Scope | 기준 식별자 | 용도 |
| --- | --- | --- |
| `OPERATION` | failback Run UUID | commit journal과 transition 결과 |
| `PLAN_AUTHORITY` | Plan UUID | active side, scheduler, latest completed cycle |

cycle identity는 `planUuid:sequence`, checkpoint reference는
`ftctl:planUuid:producerRunUuid:sequence`를 사용한다. operation Run UUID를
producer Run UUID로 대체하지 않는다. 한 cycle response의 CBT, NBD, byte 및
checkpoint 필드는 반드시 같은 immutable checkpoint에서 읽는다.

Cloud/Agent/API 계약은 문서 576, FTCTL 구현 계약은 qemu 문서 216이 이전의
혼합 flat-field 해석보다 우선한다.

## 2026-07-28 Cloud Projection Boundary Addendum

현재 authority와 historical cutover 분리는 Cloud control-plane 책임이다.
FTCTL은 기존 `active_side`, scheduler ACK, completed checkpoint, NBD 증거를
계속 제공하며 Cloud cutover history 또는 UI eligibility를 관리하지 않는다.

이번 개선은 신규 Agent command 또는 FTCTL action을 요구하지 않는다. DTO
field가 변경되지 않는 한 Agent JAR와 FTCTL RPM은 변경 대상이 아니다. 상세
경계는 Cloud 문서 578과 qemu 문서 217을 따른다.

## 2026-07-30 Historical Error Projection Boundary Addendum

FTCTL status의 current error code/message가 비어 있고 protection이 `READY`면
Cloud는 과거 `dr_run` 실패를 FTCTL current 오류로 간주하지 않는다. FTCTL은
현재 엔진 증거를 제공하고, 종료 Run 이력과 현재 UI 경고의 분리는 Cloud
response/cache/UI가 소유한다.

따라서 이번 `DR_GUEST_OS_UNSUPPORTED` 오표시 교정은 Agent DTO와 FTCTL status
schema를 변경하지 않는다. 실제 current FTCTL status에서 같은 오류가 다시
발생할 때만 guest identity resolver 결함으로 별도 처리한다. 상세 설계는
문서 580을 따른다.

## 2026-07-30 TARGET Authority Terminal Addendum

실제 Failover 성공 후 FTCTL status는 `scheduler_state=STOPPED`,
`scheduler_desired_state=STOPPED`, `scheduler_health=SUPPRESSED`,
`replication_activity=STOPPED`를 함께 제공해야 한다. Agent command와 DTO는
기존 필드를 재사용하고 손실 없는 round-trip을 검증한다. Cloud 계약은
[581-cross-hypervisor-dr-post-failover-runtime-ui-convergence-design-20260730.md](581-cross-hypervisor-dr-post-failover-runtime-ui-convergence-design-20260730.md),
FTCTL 구현 계약은 qemu 문서 218을 따른다.
## 2026-07-30 Failback Sequence Handoff Addendum

Failback commit은 scheduler 재개 전에 reverse-final checkpoint를 Plan cycle
baseline으로 원자 seed하고, 최소 다음 sequence의 immediate validation cycle을
요청한다. Agent는 baseline/minimum/immediate-cycle을 typed command로 전달한다.
Cloud 계약은 문서 583, FTCTL 구현 계약은 qemu 문서 219를 따른다.

## 2026-07-30 Action Menu Projection Boundary Addendum

DR 작업 메뉴의 applicable/enabled/reason 판정은 Cloud control-plane
책임이다. FTCTL은 기존 scheduler, checkpoint, NBD, authority ACK 증거를
제공할 뿐 UI 메뉴 구성이나 비활성 사유를 관리하지 않는다.

이번 개선은 신규 Agent command, FTCTL action, profile, lock, state 또는 status
field를 요구하지 않는다. 메뉴 조회와 Plan API는 Agent/FTCTL을 동기 호출하지
않는다. 상세 Cloud 경계는
[584-cross-hypervisor-dr-context-action-availability-and-darkmode-design-20260730.md](584-cross-hypervisor-dr-context-action-availability-and-darkmode-design-20260730.md)를
따른다.

## 2026-08-01 Bidirectional Replication Runtime Addendum

The runtime contract now distinguishes `VMWARE_TO_KVM` and
`KVM_TO_VMWARE` as separate provider pipelines. A reversed endpoint document
does not authorize reuse of the forward VMware CBT mover. Reverse runtime
acceptance requires KVM baseline lineage, changed extents, VDDK target-write
evidence, reverse guest preparation, and isolated target boot validation.

Cloud must reject Failback when only lifecycle evidence exists. The normative
Cloud contract is
[588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md](588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md).

## 2026-08-06 Reverse Durable Evidence Publication Addendum

`FAILBACK_DATA_READY` is not sufficient by itself. FTCTL must publish the
complete typed reverse evidence tuple defined by document 596. Agent and Cloud
must reject an unsupported evidence contract before dispatch, and Cloud must
persist the complete tuple before target shutdown or source startup. A short
publication delay is reconciled asynchronously; missing evidence is not
classified as an explicitly non-durable baseline.

## 2026-07-31 Test Session Reconcile Boundary Addendum

과거 실패 Test Session의 blocking 여부와 DB soft-close는 Cloud
control-plane 책임이다. FTCTL은 기존 typed
`test_session_state/test_artifacts_state/test_cleanup_state`,
artifact count, checkpoint lease, worker terminal 증거를 제공한다.

이번 개선은 신규 FTCTL action이나 profile 변경을 요구하지 않는다. Cloud는
현재 engine test session 부재와 terminal cleanup proof를 과거 failure
event와 구분해 소비한다. 상세 경계는
[586-cross-hypervisor-dr-test-session-blocker-and-async-acceptance-design-20260731.md](586-cross-hypervisor-dr-test-session-blocker-and-async-acceptance-design-20260731.md)를
따른다.
