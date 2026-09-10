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

# Cross Hypervisor DR Sync Readiness And Target Materialization Contract Design

작성일: 2026-07-06

> 2026-09-03 clarification: the `POWERED_ON` value below is evidence captured
> from that incident, not a VMware DR eligibility prerequisite. VMware source
> power and placement follow
> `627-dr-dynamic-placement-and-transparent-worker-scheduling-design-20260903.md`.
> A powered-off source remains eligible for Full Seed and, with a valid CBT
> baseline, incremental or `NO_CHANGE`; disaster Failover uses the last durable
> checkpoint without requiring source reachability.

## 1. 목적

DR Plan 동기화 화면에서 `SYNCING` 또는 `SUCCEEDED`처럼 보이지만 DR 대상 가상머신이 실제로 생성되지 않은 상태를 정상으로 오판하지 않도록 UI/API/Backend/Agent/ftctl/DB 전 계층의 상태 계약을 재정의한다.

이번 설계의 핵심은 다음 세 상태를 분리하는 것이다.

| 구분 | 의미 | 성공 판정 주체 |
| --- | --- | --- |
| 구성 준비 | DR Plan 입력값, 사이트, 매핑, 실행 파라미터가 유효함 | Cloud DB/API |
| 엔진 접수 | Agent/ftctl이 명령을 접수하고 비동기 worker를 시작할 준비가 됨 | Agent/ftctl |
| 대상 준비 | DR 대상 VM, 볼륨, 네트워크, 복구 지점, durable checkpoint가 실제로 생성됨 | Cloud Backend + ftctl runtime |

`dr-sync-start` 명령이 accepted 또는 `sync-start-accepted`를 반환했다는 사실만으로는 동기화 성공이 아니다. 특히 VMware -> ABLESTACK 경로에서는 ABLESTACK 대상 VM/volume/NIC/materialization 결과가 Cloud DB와 ftctl runtime 양쪽에서 확인되어야 다음 단계 PASS로 본다.

## 2. 현재 관측된 문제

대상 Plan:

- Plan UUID: `d762a132-ec8a-443f-a962-06e9ef50627f`
- Source: VMware VM `Rokcy10-1`, vCenter moRef `vm-4486`
- Target name: `Rokcy10-1-dr`

관측 결과:

- vCenter source VM은 존재하고 `POWERED_ON` 상태다.
- Cloud API는 Plan state를 `SYNCING`, latest run을 `SUCCEEDED`, progress를 `100`으로 노출할 수 있다.
- `dr_replica.target_vm_id`, `target_external_ref`는 `NULL`이고 `dr_restore_point`는 없다.
- ABLESTACK target VM, target volume, target NIC, libvirt domain, target RBD image가 생성되지 않았다.
- ftctl status는 `sync-start-accepted`, `progress=1`, `run_exists=false`, `last_target_durable_at=""` 상태를 반환할 수 있다.
- ftctl worker 로그에는 global lock 충돌로 `result=locked`, `retryable=true`가 남는다.

즉, 현재 구조는 명령 접수 또는 projection snapshot을 terminal success로 과하게 승격할 수 있다.

## 3. 공통 상태 계약

### 3.1 Plan readiness

`DrPlanReadiness`는 DB snapshot과 runtime projection을 합성한 읽기 전용 DTO로 계산한다.

```java
public final class DrPlanReadiness {
    public enum State {
        CONFIG_READY,
        ENGINE_ACCEPTED,
        SYNC_IN_PROGRESS,
        TARGET_MATERIALIZING,
        TARGET_READY,
        DEGRADED,
        FAILED
    }

    private State readinessState;
    private boolean engineAccepted;
    private boolean targetMaterialized;
    private boolean targetVmPresent;
    private boolean targetStoragePresent;
    private boolean targetNetworkPresent;
    private boolean restorePointPresent;
    private boolean durableCheckpointPresent;
    private String readinessReasonCode;
    private String readinessMessage;
}
```

계산 규칙:

- Plan 생성 직후: `CONFIG_READY`
- ftctl action accepted 직후: `ENGINE_ACCEPTED`
- worker가 실제 전송 중: `SYNC_IN_PROGRESS`
- target skeleton 또는 volume 생성 중: `TARGET_MATERIALIZING`
- target VM/volume/NIC와 restore point 또는 durable checkpoint가 모두 확인됨: `TARGET_READY`
- runtime 조회 실패, lock retry, status timeout 등 재시도 가능한 일시 오류: `DEGRADED`
- retry 정책 초과 또는 비복구 오류: `FAILED`

### 3.2 SYNC run terminal success 조건

SYNC run은 다음 조건을 모두 만족해야 `SUCCEEDED`가 된다.

1. ftctl runtime이 terminal success 또는 target ready event를 반환한다.
2. `last_target_durable_at` 또는 동일 의미의 durable checkpoint가 존재한다.
3. `dr_restore_point`가 생성되었거나 ftctl restore point metadata가 Cloud DB에 반영되었다.
4. `dr_replica.target_vm_id` 또는 `target_external_ref`가 채워져 있다.
5. target inventory lookup이 VM/volume/NIC 중 최소 필수 리소스를 확인한다.
6. RPO 목표 대비 최신 durable checkpoint 시간이 정책 범위 안에 있다.

`SYNCING`, `READY`, `TARGET_READY`, `PAUSED`라는 runtime state 문자열만으로 run을 성공 처리하지 않는다.

## 4. UI 설계

### 4.1 목록 상태 표시

DR Plan 목록은 plan state만 표시하지 않고 readiness를 함께 표시한다.

```js
function normalizeDrPlanEffectiveStatus(plan) {
  const readiness = plan.readinessstate || plan.readinessState
  if (readiness === 'TARGET_READY') return 'READY'
  if (readiness === 'ENGINE_ACCEPTED') return 'ACCEPTED'
  if (readiness === 'TARGET_MATERIALIZING') return 'MATERIALIZING'
  if (readiness === 'DEGRADED') return 'ATTENTION'
  if (plan.state === 'SYNCING') return 'SYNCING'
  return plan.state || 'UNKNOWN'
}
```

표시 원칙:

- run accepted 상태는 "작업 접수됨"으로 표시한다.
- target VM이 없으면 "대상 VM 준비 전"을 표시한다.
- `progress=100`이어도 target readiness가 없으면 "완료"로 표시하지 않는다.
- 상세 화면 skeleton은 API 응답 지연과 데이터 없음 상태를 구분한다.

### 4.2 상세 화면 탭

상세 화면은 다음 정보를 분리한다.

- 기본 정보: Plan 구성, 방향, 사이트, VM 매핑
- 동기화 상태: latest run, worker state, progress, lock/retry 상태
- 대상 준비: target VM, volume, NIC, restore point, durable checkpoint
- 상태 체크 이력: projection/health history

`작업` 버튼 gating:

| 작업 | 활성 조건 |
| --- | --- |
| 재동기화 | `CONFIG_READY`, `ENGINE_ACCEPTED`, `DEGRADED`, `TARGET_READY` 중 retry 가능 |
| 일시정지 | `SYNC_IN_PROGRESS`, `TARGET_MATERIALIZING` |
| 재개 | `PAUSED` |
| Failover | `TARGET_READY` |
| Failback | failover 완료 후 역방향 target readiness 확인 |
| 삭제 | active run이 없거나 cancel 가능 상태 |

## 5. API 설계

### 5.1 조회 API 응답 필드

`listDrPlans`, `getDrPlan`, `queryAsyncJobResult`의 DR Plan 응답에 다음 필드를 추가한다.

```java
@SerializedName("readinessstate")
private String readinessState;

@SerializedName("readinessreasoncode")
private String readinessReasonCode;

@SerializedName("readinessmessage")
private String readinessMessage;

@SerializedName("engineaccepted")
private Boolean engineAccepted;

@SerializedName("targetmaterialized")
private Boolean targetMaterialized;

@SerializedName("targetvmpresent")
private Boolean targetVmPresent;

@SerializedName("targetstoragepresent")
private Boolean targetStoragePresent;

@SerializedName("targetnetworkpresent")
private Boolean targetNetworkPresent;

@SerializedName("restorepointcount")
private Integer restorePointCount;

@SerializedName("lasttargetdurableat")
private String lastTargetDurableAt;
```

### 5.2 명령 API 원칙

`startDrPlanSync`, `pauseDrPlanSync`, `resumeDrPlanSync`, `failoverDrPlan`, `failbackDrPlan`은 즉시 결과를 기다리지 않는다.

응답 예:

```json
{
  "jobid": "async-job-uuid",
  "runid": "dr-run-uuid",
  "accepted": true,
  "readinessstate": "ENGINE_ACCEPTED",
  "message": "DR sync request accepted. Target VM readiness will be reported asynchronously."
}
```

금지:

- API thread에서 ftctl 장기 작업 완료 대기
- API thread에서 vCenter/host target inventory polling 반복
- accepted 응답을 success terminal state로 저장

## 6. Backend 설계

### 6.1 Projection adapter 성공 판정 수정

기존 문제 패턴:

```java
if (StringUtils.equals(runType, DrConstants.RUN_TYPE_SYNC)) {
    return StringUtils.equalsAny(runtimeState, "SYNCING", "READY", "TARGET_READY", "PAUSED");
}
```

개선:

```java
private boolean isRunSatisfiedByRuntime(DrRunVO run, DrPlanVO plan, FtctlDrStatus status) {
    if (StringUtils.equals(run.getRunType(), DrConstants.RUN_TYPE_SYNC)) {
        return isSyncTargetReady(plan, status);
    }
    return isTerminalRuntimeState(status);
}

private boolean isSyncTargetReady(DrPlanVO plan, FtctlDrStatus status) {
    if (status == null || !status.isTerminalSuccess()) {
        return false;
    }
    if (!StringUtils.equalsAny(status.getState(), "TARGET_READY", "READY")) {
        return false;
    }
    if (StringUtils.isBlank(status.getLastTargetDurableAt())) {
        return false;
    }

    DrReplicaVO replica = replicaDao.findActiveByPlanId(plan.getId());
    if (replica == null || !replica.hasTargetReference()) {
        return false;
    }

    if (!targetInventoryVerifier.verify(plan, replica).isReady()) {
        return false;
    }

    return restorePointDao.countActiveByPlanId(plan.getId()) > 0;
}
```

### 6.2 retryable lock 처리

ftctl이 다음 응답을 반환하면 terminal failure로 닫지 않는다.

```json
{
  "result": "locked",
  "retryable": true,
  "retry_after_sec": 2,
  "holder_command": "dr-sync-pause"
}
```

처리:

```java
if (status.isRetryableLock()) {
    run.setState(DrConstants.RUN_STATE_RETRYING);
    run.setErrorCode("DR_ENGINE_BUSY_RETRYABLE");
    run.setRetryAfterSec(status.getRetryAfterSec());
    runDao.update(run.getId(), run);

    plan.setState(DrConstants.PLAN_STATE_SYNCING);
    plan.setLastErrorCode(null);
    planDao.update(plan.getId(), plan);
    return ProjectionDecision.defer();
}
```

### 6.3 target materialization verifier

```java
public interface DrTargetInventoryVerifier {
    DrTargetReadiness verify(DrPlanVO plan, DrReplicaVO replica);
}
```

ABLESTACK target verifier:

- `vm_instance.id = dr_replica.target_vm_id`
- `vm_instance.removed IS NULL`
- required volume rows exist
- required NIC rows exist
- host/domain 또는 stopped skeleton 정책에 맞는 상태 확인

VMware target verifier:

- vCenter moRef 또는 inventory path 존재
- power state와 disk backing 존재
- snapshot/checkpoint metadata 확인

### 6.4 projection reconciliation

projection worker는 다음 순서로 상태를 보정한다.

1. DB latest run 조회
2. bounded `dr-status` 조회
3. retryable lock이면 retry 상태 유지
4. terminal success 후보면 target materialization verifier 실행
5. verifier 실패면 `TARGET_MATERIALIZING` 또는 `DEGRADED` 유지
6. verifier 성공이면 run `SUCCEEDED`, plan `READY`, restore point 반영

## 7. Agent 설계

### 7.1 명령 wrapper 계약

Agent는 Cloud에서 받은 DR action을 host에서 ftctl로 전달하고 즉시 accepted/result JSON을 반환한다.

반환 필수 필드:

```json
{
  "command": "dr-sync-start",
  "result": "accepted",
  "plan_uuid": "d762a132-ec8a-443f-a962-06e9ef50627f",
  "run_uuid": "run-uuid",
  "worker_pid": 12345,
  "state": "ENGINE_ACCEPTED",
  "retryable": false
}
```

Agent는 다음을 하지 않는다.

- 장기 동기화 완료 대기
- target VM 생성 여부를 직접 최종 판정
- raw status JSON만 Cloud에 던지고 Cloud 해석을 생략

### 7.2 status wrapper

status command는 bounded timeout을 가진다.

- 정상 timeout: 5초
- kill grace: 2초
- timeout error code: `DR_STATUS_TIMEOUT`
- timeout은 projection stale이지 plan failure가 아니다.

## 8. ftctl 설계

### 8.1 parent/worker lock 분리

현재처럼 parent process가 global lock을 잡은 채 background worker를 spawn하면 worker가 같은 lock에 막힐 수 있다.

개선:

```bash
dr_sync_start() {
  ftctl_global_lock_acquire
  write_profile
  write_run_state "ENGINE_ACCEPTED"
  spawn_worker_after_lock_release "$plan_uuid" "$run_uuid"
  ftctl_global_lock_release
  emit_accepted_json
}

spawn_worker_after_lock_release() {
  nohup ablestack_vm_ftctl dr-sync-worker \
    --plan "$plan_uuid" \
    --run "$run_uuid" \
    --json >> "$run_log" 2>&1 &
}
```

worker는 plan/run 단위 lock을 사용한다.

```bash
worker_lock="/run/ablestack-vm-ftctl/plans/${plan_uuid}.lock"
```

전역 lock은 profile atomic write 같은 짧은 임계구역에만 사용한다.

### 8.2 status JSON 확장

`dr-status`는 다음 필드를 반환한다.

```json
{
  "state": "TARGET_MATERIALIZING",
  "step": "target-vm-create",
  "progress": 42,
  "accepted": true,
  "runtime_exists": true,
  "profile_exists": true,
  "run_exists": true,
  "target_materialized": false,
  "target_vm_present": false,
  "target_storage_present": true,
  "target_network_present": false,
  "restore_point_present": false,
  "last_target_durable_at": "",
  "retryable": true,
  "retry_after_sec": 2
}
```

`dr-status`는 lock을 잡거나 remote inventory를 새로 수집하지 않는다. 상태 파일과 bounded event tail만 읽는다.

### 8.3 target materialization event

worker는 단계별 event를 남긴다.

```json
{"event":"target-volume-created","volume":"...","at":"..."}
{"event":"target-vm-created","target_vm_id":"...","at":"..."}
{"event":"restore-point-created","restore_point_id":"...","durable_at":"..."}
{"event":"target-ready","target_vm_id":"...","durable_at":"..."}
```

Cloud projection은 이 event와 DB inventory를 교차 확인한다.

## 9. DB 설계

### 9.1 1차 구현

새 컬럼 추가 없이 기존 테이블에서 readiness를 계산한다.

- `dr_plan.state`
- `dr_plan.last_error_code`, `last_error_message`
- `dr_run.state`, `projection_state`, `external_job_ref`
- `dr_replica.target_vm_id`, `target_external_ref`, `target_vm_name`
- `dr_restore_point`
- target `vm_instance`, `volumes`, `nics`

2026-07-06 구현 반영:

- 1차 구현은 캐시 컬럼을 추가하지 않고 `DrPlanReadinessValidator.validateTargetReadiness(plan)`에서 응답/작업 가능성 판단 시점에 계산한다.
- ABLESTACK 대상은 `dr_replica.target_vm_id`가 있어야 대상 VM 존재로 인정한다.
- ABLESTACK 대상 스토리지는 단순 `dr_replica_disk` 스켈레톤 행만으로 인정하지 않는다. `target_volume_id`, target-ready restore point, 또는 durable checkpoint가 확인되어야 한다.
- VMware 대상은 `dr_replica.target_external_ref`를 대상 VM 식별자로 보고, `dr_replica_disk.target_disk_ref`를 대상 디스크 식별자로 본다.
- `FtctlDrRuntimeProjectionAdapter.isSyncTargetReady()`가 SYNC run의 `SUCCEEDED` 종료를 판단하는 단일 gate다. `SYNCING`, `READY`, `TARGET_READY`, `PAUSED` 같은 runtime state 문자열만으로는 run을 성공 처리하지 않는다.
- `FtctlDrRuntimeProjectionAdapter.updatePlanFromStatus()`는 ftctl이 `READY`를 보고해도 Cloud에서 대상 식별자 또는 durable checkpoint를 확인하지 못하면 Plan을 `SYNCING`으로 보정하고 false `target_ready_at` projection을 제거한다.
- `DrPlanResponse`는 `readinessstate`, `readinessreasoncode`, `readinessmessage`, `engineaccepted`, `targetmaterialized`, `targetvmpresent`, `targetstoragepresent`, `targetnetworkpresent`, `restorepointpresent`, `durablecheckpointpresent`를 노출한다.
- ftctl `dr-status`의 target readiness boolean은 runtime hint이며 최종 action eligibility는 Cloud DB/inventory 계산 결과를 따른다.

### 9.2 선택적 캐시 컬럼

조회 부하가 문제가 되면 다음 컬럼을 추가한다.

```sql
ALTER TABLE dr_plan
  ADD COLUMN readiness_state varchar(32) DEFAULT NULL,
  ADD COLUMN readiness_reason_code varchar(64) DEFAULT NULL,
  ADD COLUMN readiness_message varchar(1024) DEFAULT NULL,
  ADD COLUMN target_materialized tinyint(1) DEFAULT 0,
  ADD COLUMN last_target_durable_at datetime DEFAULT NULL;
```

단, 캐시 컬럼은 source of truth가 아니다. projection worker가 DB/runtime을 근거로 갱신한다.

### 9.3 운영 보정 SQL 예

false success 후보 확인:

```sql
SELECT p.id, p.uuid, p.state, r.state AS run_state, rp.id AS replica_id,
       rp.target_vm_id, rp.target_external_ref, COUNT(pt.id) AS restore_points
  FROM dr_plan p
  JOIN dr_run r ON r.plan_id = p.id AND r.removed IS NULL
  LEFT JOIN dr_replica rp ON rp.plan_id = p.id AND rp.removed IS NULL
  LEFT JOIN dr_restore_point pt ON pt.plan_id = p.id AND pt.removed IS NULL
 WHERE p.removed IS NULL
 GROUP BY p.id, r.id, rp.id
HAVING r.state = 'SUCCEEDED'
   AND (rp.target_vm_id IS NULL OR restore_points = 0);
```

보정은 구현 배포 후 projection worker로 수행하는 것을 원칙으로 한다. 수동 SQL 보정은 증거 백업 후에만 수행한다.

## 9.4 2026-07-06 추가 보강: worker 상태를 포함한 readiness 판정

세부 설계는 [535-cross-hypervisor-dr-async-worker-lock-and-readiness-recovery-design-20260706.md](535-cross-hypervisor-dr-async-worker-lock-and-readiness-recovery-design-20260706.md)를 따른다.

readiness는 target materialization만 보지 않고 worker admission 상태까지 포함한다.

추가 판정 규칙:

- `ENGINE_ACCEPTED`: ftctl이 run/profile을 접수했지만 worker 실행 확인 전 상태.
- `WORKER_STARTING`: `worker_state=STARTING` 또는 accepted 직후 grace window 내부 상태.
- `WORKER_RETRYING`: ftctl status 또는 run log가 `retryable=true` lock을 보고한 상태.
- `WORKER_STALLED`: accepted 이후 `worker_pid`가 없거나 `updated_at`이 stall threshold 이상 갱신되지 않은 상태.
- `TARGET_MATERIALIZING`: worker는 진행 중이나 target VM/volume/restore point/durable checkpoint가 아직 불완전한 상태.
- `TARGET_READY`: target VM/volume/network/restore point/durable checkpoint가 모두 확인된 상태.

다음 단계 진행 PASS는 `TARGET_READY`에서만 허용한다. `SYNCING`, `sync-start-accepted`, `progress=1`, `progress=100`, `accepted=true`는 PASS 근거가 될 수 없다.

## 10. 레이어별 개선 요약

| 레이어 | AS-IS | TO-BE |
| --- | --- | --- |
| UI | Plan state/progress만 보고 완료처럼 표시 가능 | readiness state와 target readiness를 분리 표시 |
| API | accepted/run 상태와 target 준비 정보가 혼재 | `engineaccepted`, `targetmaterialized`, `restorepointcount`, `lasttargetdurableat` 노출 |
| Backend | `SYNCING/READY` runtime 문자열을 run success로 승격 가능 | `isSyncTargetReady()`로 target materialization 검증 후 success |
| Agent | lock/timeout/raw JSON 해석이 Cloud로 누수 | accepted/status/retryable contract를 표준화 |
| ftctl | parent lock이 worker lock과 충돌 가능 | parent는 lock release 후 worker spawn, worker는 plan/run lock 사용 |
| DB | target VM/restore point 없음에도 latest run success 가능 | readiness 계산 또는 캐시로 false success 차단 |

## 11. 검증 기준

PASS 조건:

- `startDrPlanSync` API는 즉시 async job/run id를 반환한다.
- UI는 accepted 상태를 완료로 표시하지 않는다.
- ftctl worker lock 충돌은 `RETRYING` 또는 `DEGRADED`로 표시되고 terminal success가 아니다.
- target VM/volume/NIC가 없으면 Failover 버튼이 비활성화된다.
- target VM/volume/NIC와 restore point가 생성되고 durable checkpoint가 기록된 후에만 Plan이 `READY`가 된다.
- `dr_replica.target_vm_id` 또는 `target_external_ref`가 비어 있으면 latest run이 `SUCCEEDED`로 보정되지 않는다.
- `dr-status` timeout은 상세 화면 전체 skeleton을 유발하지 않고 runtime panel 경고로만 표시된다.

## 2026-07-07 Target Driver Contract Update

The VMware to ABLESTACK sync test showed that `SKELETON_READY` is not sufficient for next-step readiness. The target runtime reported `target_vm_present=false`, `target_network_present=false`, empty `last_target_durable_at`, and `error_code=DR_ABLESTACK_DRIVER_FAILED`.

The detailed follow-up design is:

- [536-cross-hypervisor-dr-terminal-projection-and-target-driver-contract-design-20260707.md](536-cross-hypervisor-dr-terminal-projection-and-target-driver-contract-design-20260707.md)

Materialization readiness must now require:

- target VM id or target external ref,
- every target disk ref,
- target network mapping,
- non-empty durable checkpoint,
- latest run not failed,
- FTCTL runtime not in `ERROR` or `FAILED`.

The disk contract must also be normalized before dispatch. ABLESTACK RBD target storage must generate `targetType=rbd`; a generated disk mapping with RBD storage and `targetType=file` is invalid and must fail preflight instead of failing later inside the worker.

## 2026-07-07 Source/Target Disk Map Authority Update

The latest VMware to ABLESTACK validation showed that a normalized target disk
contract can still be bypassed if FTCTL exposes the VMware source map as the
active runtime `disk_map_path`.

Updated readiness rules:

1. VMware source driver output is source metadata only:
   `source_disk_map_path=.../vmware-disks.json`.
2. ABLESTACK target driver output is target materialization metadata:
   `target_disk_map_path=.../ablestack-disks.json`.
3. For ABLESTACK targets, backward-compatible `disk_map_path` must point to the
   target map.
4. The target map must include target storage type, target disk type, target
   path/name, disk offering, network, service offering, and non-zero size.
5. VMware inventory refs such as `2000` are not local paths. They require
   VMware/VDDK or Cloud inventory size resolution before target preparation.
6. Missing target map fields must fail in FTCTL preflight with explicit codes
   such as `DR_TARGET_DISK_TYPE_INVALID`,
   `DR_TARGET_DISK_SIZE_UNRESOLVED`, or `DR_TARGET_STORAGE_UNRESOLVED`.

The complete layered design is documented in
[537-cross-hypervisor-dr-serving-process-and-disk-map-contract-design-20260707.md](537-cross-hypervisor-dr-serving-process-and-disk-map-contract-design-20260707.md).

## 2026-07-09 Target VM Materialization Worker Update

The VMware to ABLESTACK validation for plan
`dd895181-7fff-43cc-bae6-24a5ab529db8` confirmed that sync readiness has one
more required boundary:

- durable restore points can be present and `READY`;
- target storage can be present;
- the sync scheduler can keep running;
- but the DR target is still not ready if Cloud has not created or adopted the
  target VM and managed target volume records.

Therefore, `TARGET_READY` must require a Cloud-owned materialization worker
after restore point readiness:

1. import or adopt each seeded target disk as a Cloud-managed volume;
2. deploy a stopped target VM from the imported root volume;
3. attach/import data volumes if present;
4. persist `dr_replica.target_vm_id` and each
   `dr_replica_disk.target_volume_id`;
5. notify FTCTL with the target VM, network, and volume references;
6. project `target_materialized=true` only after Cloud DB and FTCTL agree.

The complete code-level design is documented in
[547-cross-hypervisor-dr-target-vm-materialization-contract-design-20260709.md](547-cross-hypervisor-dr-target-vm-materialization-contract-design-20260709.md).
