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

# Cross Hypervisor DR Adapter Contract Design

## 2026-07-10 Normative Projection Adapter Invocation Boundary

`DrProjectionAdapter.refreshPlanProjection()` is a write-side operation. It is
invoked only by the DR projection scheduler, post-dispatch projection worker,
explicit asynchronous refresh job, or repair task. Read commands and DAO list
methods must never invoke it.

The FTCTL projection adapter consumes typed `current_checkpoint_*` and
`latest_completed_checkpoint_*` fields. It must not infer a completed
checkpoint by combining the current sequence with an older durable timestamp.

Detailed adapter and compatibility contract:
`550-cross-hypervisor-dr-protection-view-cache-and-completed-checkpoint-design-20260710.md`.

작성일: 2026-06-30

대상 브랜치: `feature/ftctl-cloud-integration`

상위 문서:

- [500-cross-hypervisor-dr-architecture-plan-20260630.md](500-cross-hypervisor-dr-architecture-plan-20260630.md)
- [501-cross-hypervisor-dr-domain-schema-design-20260630.md](501-cross-hypervisor-dr-domain-schema-design-20260630.md)

## 1. 목적

이 문서는 `Cross Hypervisor DR`의 adapter contract를 구체화한다. 구현 시 Java interface와 DTO를 만들 때 이 문서를 기준으로 한다.

범위는 설계까지다. 이 문서는 실제 Java interface 파일을 생성하지 않는다.

## 2. Adapter 배치 원칙

신규 공통 orchestrator는 특정 hypervisor 로직을 직접 알지 않는다.

- source VM 조회와 consistency 확보는 `DrSourceAdapter`가 담당한다.
- source artifact 생성과 반복 sync는 `DrReplicationEngine`이 담당한다.
- format 변환과 target disk materialization은 `DrMaterializer`가 담당한다.
- target VM skeleton, disk attach, power, test boot는 `DrTargetAdapter`가 담당한다.
- dual-active 방지와 manual-confirm은 `DrFencingAdapter`가 담당한다.
- 기존 FTCTL/V2K/VMware 기능은 adapter 구현체 안에서 호출한다.

## 3. Adapter matrix

| direction | source adapter | replication engine | materializer | target adapter | fencing adapter |
| --- | --- | --- | --- | --- | --- |
| `KVM_TO_KVM` | `KvmSourceAdapter` | `FtctlReplicationEngine` | `NoopOrKvmBlockMaterializer` | `KvmTargetAdapter` | `FtctlFencingAdapter` |
| `KVM_TO_VMWARE` | `KvmSourceAdapter` | `KvmSnapshotReplicationEngine` | `KvmToVmdkMaterializer` | `VmwareTargetAdapter` | `KvmFencingAdapter` |
| `VMWARE_TO_VMWARE` | `VmwareSourceAdapter` | `VmwareSnapshotReplicationEngine`, later `VmwareCbtReplicationEngine` | `VmwareVmdkMaterializer` | `VmwareTargetAdapter` | `VmwareFencingAdapter` |
| `VMWARE_TO_KVM` | `VmwareSourceAdapter` | `V2kReplicationEngine` | `V2kToKvmMaterializer` | `KvmTargetAdapter` | `VmwareFencingAdapter` |

## 4. 공통 DTO

구현 시 DTO는 service layer 내부 object로 시작하고, API response와는 분리한다.

`DrExecutionContext`

- `planId`
- `runId`
- `accountId`
- `domainId`
- `sourceSite`
- `targetSite`
- `direction`
- `requestId`
- `idempotencyKey`
- `dryRun`
- `actor`

`DrVmIdentity`

- `cloudVmId`
- `externalRef`
- `displayName`
- `instanceName`
- `hypervisorType`
- `guestOsId`
- `powerState`
- `metadata`

`DrDiskSpec`

- `sourceVolumeId`
- `sourceDiskRef`
- `label`
- `busType`
- `controllerType`
- `unitNumber`
- `format`
- `sizeBytes`
- `metadata`

`DrNetworkSpec`

- `sourceNetworkId`
- `sourceNetworkRef`
- `targetNetworkId`
- `targetNetworkRef`
- `macPolicy`
- `ipPolicy`
- `connectAtFailover`
- `metadata`

`DrRestorePointSpec`

- `restorePointId`
- `sequenceNo`
- `consistencyType`
- `sourceCapturedAt`
- `artifacts`
- `metadata`

`DrMaterializationSpec`

- `restorePointId`
- `targetFormat`
- `targetDatastoreRef`
- `targetStoragePoolId`
- `incrementalAllowed`
- `targetDiskLayout`
- `metadata`

`DrTargetReplicaSpec`

- `replicaId`
- `targetVmId`
- `targetExternalRef`
- `targetName`
- `powerState`
- `diskRefs`
- `networkRefs`
- `metadata`

`DrAdapterResult`

- `result`: `OK`, `WARN`, `FAILED`, `RETRYABLE`
- `state`
- `progressPercent`
- `externalJobRef`
- `message`
- `details`
- `errorCode`
- `retryAfterSeconds`

## 5. Source adapter contract

Pseudo contract:

```java
interface DrSourceAdapter {
    DrSiteType siteType();
    DrHypervisorType hypervisorType();

    DrAdapterResult validateSource(DrExecutionContext context, DrPlan plan);
    DrVmIdentity describeSourceVm(DrExecutionContext context, DrPlan plan);
    List<DrDiskSpec> listSourceDisks(DrExecutionContext context, DrPlan plan);
    List<DrNetworkSpec> listSourceNetworks(DrExecutionContext context, DrPlan plan);
    DrAdapterResult prepareConsistency(DrExecutionContext context, DrPlan plan, DrConsistencyPolicy policy);
    DrAdapterResult releaseConsistency(DrExecutionContext context, DrPlan plan);
}
```

구현 규칙:

- `describeSourceVm`는 side effect가 없어야 한다.
- consistency 준비가 실패해도 정책이 `CRASH_CONSISTENT_ALLOWED`이면 `WARN`으로 진행 가능해야 한다.
- QGA/VMware Tools 결과는 `DrRestorePoint.consistency_type`, `quiesce_result`에 투영한다.

## 6. Replication engine contract

Pseudo contract:

```java
interface DrReplicationEngine {
    DrPlanDirection direction();
    String engineType();

    DrAdapterResult preflight(DrExecutionContext context, DrPlan plan);
    DrRestorePointSpec createRestorePoint(DrExecutionContext context, DrPlan plan);
    DrAdapterResult syncRestorePoint(DrExecutionContext context, DrPlan plan, DrRestorePoint restorePoint);
    DrAdapterResult verifySourceReady(DrExecutionContext context, DrRestorePoint restorePoint);
    DrAdapterResult cleanupExpiredRestorePoint(DrExecutionContext context, DrRestorePoint restorePoint);
}
```

구현 규칙:

- `createRestorePoint`는 VM 단위 restore point를 만들고, disk별 artifact를 함께 반환한다.
- full seed와 incremental sync는 같은 restore point model에 표현한다.
- external job이 필요한 경우 `externalJobRef`를 반환하고 orchestrator가 polling한다.
- retry 가능한 오류는 `RETRYABLE`과 `retryAfterSeconds`로 표현한다.

## 7. Materializer contract

Pseudo contract:

```java
interface DrMaterializer {
    boolean supports(DrPlanDirection direction, String sourceFormat, String targetFormat);

    DrAdapterResult preflightMaterialization(DrExecutionContext context, DrMaterializationSpec spec);
    DrAdapterResult materialize(DrExecutionContext context, DrRestorePoint restorePoint, DrMaterializationSpec spec);
    DrAdapterResult verifyMaterializedArtifacts(DrExecutionContext context, DrReplica replica, DrRestorePoint restorePoint);
    DrAdapterResult cleanupStaleArtifacts(DrExecutionContext context, DrReplica replica);
}
```

구현 규칙:

- materializer는 source VM power state를 직접 바꾸지 않는다.
- target VM power operation은 `DrTargetAdapter`가 수행한다.
- 변환 산출물 경로와 checksum은 `dr_replica_disk.metadata_json` 또는 `dr_restore_point_artifact`에 기록한다.
- 대용량 full conversion은 별도 worker job으로 분리해야 한다.

## 8. Target adapter contract

Pseudo contract:

```java
interface DrTargetAdapter {
    DrSiteType siteType();
    DrHypervisorType hypervisorType();

    DrAdapterResult validateTarget(DrExecutionContext context, DrPlan plan);
    DrTargetReplicaSpec ensureReplicaSkeleton(DrExecutionContext context, DrPlan plan, DrVmIdentity sourceVm);
    DrAdapterResult attachOrRefreshDisks(DrExecutionContext context, DrReplica replica, DrRestorePoint restorePoint);
    DrAdapterResult configureNetworks(DrExecutionContext context, DrReplica replica, List<DrNetworkSpec> networks);
    DrAdapterResult verifyTargetReady(DrExecutionContext context, DrReplica replica, DrRestorePoint restorePoint);
    DrAdapterResult testBoot(DrExecutionContext context, DrReplica replica, DrTestBootPolicy policy);
    DrAdapterResult powerOnForFailover(DrExecutionContext context, DrReplica replica);
    DrAdapterResult powerOffTestReplica(DrExecutionContext context, DrReplica replica);
}
```

구현 규칙:

- `ensureReplicaSkeleton`는 idempotent 해야 한다.
- VMware target은 MoRef, datastore path, folder/resource pool/network mapping을 반환한다.
- KVM target은 VM id, volume id, host/storage mapping을 반환한다.
- target-ready 판정은 disk attach와 boot metadata 검증까지 포함한다.

## 9. Fencing adapter contract

Pseudo contract:

```java
interface DrFencingAdapter {
    boolean supports(DrPlan plan);

    DrAdapterResult preFailoverCheck(DrExecutionContext context, DrPlan plan);
    DrAdapterResult requestFence(DrExecutionContext context, DrPlan plan);
    DrAdapterResult verifyFence(DrExecutionContext context, DrPlan plan);
    DrAdapterResult clearFence(DrExecutionContext context, DrPlan plan);
}
```

구현 규칙:

- dual-active 위험이 있으면 `WAITING_MANUAL_CONFIRM` 상태를 반환한다.
- manual-confirm은 run step에 남겨야 하며, UI에서 명시적 사용자 확인이 필요하다.
- FTCTL manual-block 모델은 `FtctlFencingAdapter`로 감싸고, 기존 confirm/clear API를 직접 재사용한다.

## 10. Orchestrator와 adapter 호출 순서

`SYNC`

1. `SourceAdapter.validateSource`
2. `TargetAdapter.validateTarget`
3. `ReplicationEngine.preflight`
4. `SourceAdapter.prepareConsistency`
5. `ReplicationEngine.createRestorePoint`
6. `SourceAdapter.releaseConsistency`
7. `ReplicationEngine.syncRestorePoint`
8. `Materializer.materialize`
9. `TargetAdapter.ensureReplicaSkeleton`
10. `TargetAdapter.attachOrRefreshDisks`
11. `TargetAdapter.verifyTargetReady`

`TEST_FAILOVER`

1. 최신 `TARGET_READY` restore point 선택
2. `TargetAdapter.testBoot`
3. network isolation 검증
4. test result 기록
5. `TargetAdapter.powerOffTestReplica`

`FAILOVER`

1. `FencingAdapter.preFailoverCheck`
2. 필요 시 `FencingAdapter.requestFence`
3. `FencingAdapter.verifyFence`
4. `TargetAdapter.powerOnForFailover`
5. `DrPlan.state=FAILED_OVER`

`FAILBACK`

1. direction별 reverse engine 선택
2. source/target 역할 전환 가능성 preflight
3. reverse restore point 또는 reverse sync 준비
4. target에서 source로 cutback
5. `DrPlan.state=FAILBACK_READY` 또는 `READY`

`REPROTECT`

1. 현재 active site를 source로 재지정
2. 새 target site preflight
3. reverse direction sync 시작
4. 새 `DrReplica` target-ready 확인
5. `DrPlan.state=READY`

## 11. Error contract

표준 오류 코드는 adapter별 오류를 UI와 API가 일관되게 다루기 위한 최소 단위다.

| error code | 의미 | retry |
| --- | --- | --- |
| `DR_SOURCE_UNAVAILABLE` | source VM/site 조회 실패 | yes |
| `DR_SOURCE_QUIESCE_FAILED` | consistency 확보 실패 | policy dependent |
| `DR_TARGET_UNAVAILABLE` | target site 조회 실패 | yes |
| `DR_TARGET_MAPPING_INVALID` | datastore/network/compute mapping 오류 | no |
| `DR_ARTIFACT_CREATE_FAILED` | restore point artifact 생성 실패 | yes |
| `DR_MATERIALIZE_FAILED` | format 변환 또는 upload 실패 | yes |
| `DR_TARGET_VERIFY_FAILED` | target-ready 검증 실패 | no |
| `DR_FENCE_REQUIRED` | manual-confirm 필요 | no |
| `DR_FENCE_FAILED` | fencing 실패 | no |
| `DR_ENGINE_UNSUPPORTED` | direction/engine 미지원 | no |

## 12. Idempotency

- 모든 public action API는 `idempotencyKey`를 받을 수 있어야 한다.
- 동일 plan에서 같은 key의 running/succeeded run이 있으면 새 run을 만들지 않는다.
- adapter는 이미 존재하는 target VM, VMDK, volume을 발견하면 재사용하거나 명확히 conflict를 반환해야 한다.
- destructive cleanup은 run context와 ownership marker가 일치할 때만 수행한다.

## 13. 구현 전 확인 과제

1. Adapter 구현체를 Spring bean으로 로딩할지, registry class로 수동 등록할지 결정한다.
2. 기존 async job framework와 `DrRun`의 관계를 정해야 한다.
3. 장시간 data mover job의 polling 주기와 timeout을 표준화한다.
4. VMware task MoRef와 Cloud async job id를 `externalJobRef`에 어떻게 encoding할지 정한다.
5. Adapter DTO와 API response DTO를 분리해 내부 구현 변경이 API에 새지 않게 해야 한다.

## 14. AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| 엔진 호출 | Cloud 기능별 service가 개별 런타임 호출 | `DrReplicationEngine`, `DrFencingAdapter`, `DrMaterializationAdapter` 계약으로 분리 |
| 방향 처리 | KVM-to-KVM, VMware, V2K가 서로 다른 흐름 | source/target hypervisor 조합을 adapter registry가 선택 |
| 오류 표현 | 엔진별 오류 메시지가 UI/API에 노출 | 표준 `DR_*` error code와 retry/actionable 속성으로 변환 |
| idempotency | 기능별로 중복 실행 방어 | plan/action/idempotency key 기준으로 공통 처리 |
| cleanup | 엔진별 cleanup 의미가 다름 | ownership marker와 run context 기준으로 destructive cleanup 제한 |

### 2026-07-14 VMware CBT Adapter Addendum

The VMware adapter must return requested mode separately from effective mode,
typed aggregate/per-disk transfer metrics, baseline generation, and a cycle
commit token. It must not convert an invalid CBT baseline into an unreported
full copy. The detailed contract is
`555-cross-hypervisor-dr-vmware-cbt-incremental-and-transfer-metrics-design-20260714.md`.

### 2026-07-16 Typed Failure And Commit-State Addendum

The FTCTL adapter contract now separates `errorMessage` from complete
`statusJson` and carries `dataCommitState`, `dataCopied`,
`metadataCommitted`, `targetDurable`, and `cycleRetryMode`. `Answer.details`
must remain bounded and must not contain the complete status object. See
`557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md`.

### 2026-07-17 Agent Mode-Decision Addendum

The Agent answer and KVM wrapper transport current and latest-completed
requested mode, effective mode, mode-decision code, automatic-reseed flag,
reseed reason, invalid disk count, and consecutive reseed count independently.
The wrapper validates enums and non-negative types but does not choose a data
mode or cutover policy. Detailed fields and compatibility tests are in
`559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md`.

### 2026-07-19 Test Failover Adapter Contract Addendum

The FTCTL adapter now has artifact semantics, not customer-domain semantics.
`TEST_PREPARE` returns a credential-free, typed artifact manifest;
`TEST_ARTIFACT_CLEANUP` removes engine artifacts and the checkpoint lease.
Agent validates the Cloud-managed VM separately for power/QGA state. Required
capabilities are `test-artifact-lifecycle-v2`, `guest-preparation-v2`,
`checkpoint-lease-v1`, and `cloud-managed-test-vm-v1`. The old
`test-domain-lifecycle-v1` path cannot be an automatic fallback.

Normative contract:
`561-cross-hypervisor-dr-cloud-managed-test-failover-lifecycle-design-20260719.md`.
