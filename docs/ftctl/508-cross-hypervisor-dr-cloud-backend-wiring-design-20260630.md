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

# Cross Hypervisor DR Cloud Backend Wiring Design

> 2026-08-06 forward cutover authority correction: document
> [599](599-cross-hypervisor-dr-forward-cutover-commit-contract-and-authority-convergence-design-20260806.md)
> moves Plan/Replica TARGET promotion after FTCTL ACK and defines typed envelope,
> status recovery, and one terminal transaction.

> 2026-08-05 Failback route and failure-saga correction: document
> [595](595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md)
> defines the typed route tuple and atomic Session/Run/Plan/replica/cache
> convergence that replaces session-only `failSession()` handling.

> 2026-08-05 live-worker correction: document
> [594](594-cross-hypervisor-dr-live-worker-and-terminal-reconciliation-design-20260805.md)
> places a typed runtime-observation classifier before terminal projection and
> preserves TARGET authority during reconciliation.

> 2026-08-04 normative live-runtime preflight update:
> [592-cross-hypervisor-dr-failback-live-runtime-preflight-and-ux-convergence-design-20260804.md](592-cross-hypervisor-dr-failback-live-runtime-preflight-and-ux-convergence-design-20260804.md)
> splits authority, source runtime, target runtime, FTCTL transition, and
> reverse-data stages and repeats safety probes under the Plan transition lock.
>
> 2026-08-04 normative Failback lifecycle update:
> Document 591 revision 2 separates operation intent from transfer mode,
> initializes direction/mode before dispatch, gates lifecycle on `DATA_READY`,
> and preserves the first typed terminal error.
>
> 2026-08-03 normative target-ownership update:
> [590-cross-hypervisor-dr-plan-async-mutation-and-target-resource-ownership-design-20260803.md](590-cross-hypervisor-dr-plan-async-mutation-and-target-resource-ownership-design-20260803.md)
> requires transactional VM, volume, and artifact claims before target
> materialization. Name/path-only target reuse is forbidden.

> 2026-07-31 latest correction: FTCTL_DR source-isolation safety belongs to
> Failback/Reprotect preflight, not an independent fencing action. See 587.

작성일: 2026-06-30

대상 브랜치: `feature/ftctl-cloud-integration`

상위 문서:

- [500-cross-hypervisor-dr-architecture-plan-20260630.md](500-cross-hypervisor-dr-architecture-plan-20260630.md)
- [501-cross-hypervisor-dr-domain-schema-design-20260630.md](501-cross-hypervisor-dr-domain-schema-design-20260630.md)
- [502-cross-hypervisor-dr-adapter-contract-design-20260630.md](502-cross-hypervisor-dr-adapter-contract-design-20260630.md)
- [503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md](503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md)
- [507-cross-hypervisor-dr-cloud-api-command-design-20260630.md](507-cross-hypervisor-dr-cloud-api-command-design-20260630.md)
- [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)

## 1. 목적

이 문서는 `Cross Hypervisor DR` Cloud backend 구현 시 module, package, service, DAO, Spring bean, transaction, adapter registry를 어디에 배치할지 정의한다.

핵심 목표는 다음과 같다.

- 기존 `disaster-recovery`와 `ftctl-service` 성공 경로를 깨지 않는다.
- 공통 DR orchestrator는 특정 engine 구현을 직접 알지 않는다.
- FTCTL, V2K, VMware adapter는 공통 contract를 구현하고 registry를 통해 연결된다.
- Cloud API async job과 `DrRun`의 역할을 분리한다.
- 구현자가 임의의 위치에 class를 흩뿌리지 않도록 파일 단위 소유권을 정한다.

## 2. Module 소유권

공통 `Cross Hypervisor DR` backend는 기존 `plugins/integrations/disaster-recovery` module이 소유한다.

이유:

- 기존 Mold-to-Mold DR cluster API와 UI resource가 이미 이 module에 있다.
- 신규 `DrSite`, `DrPlan`은 기존 `DisasterRecoveryCluster`를 일반화하는 상위 모델이다.
- VMware target Phase 1은 FTCTL 없이도 구현 가능하다.
- FTCTL은 KVM-to-KVM engine adapter로 연결하면 된다.

권장 module 배치:

| 영역 | Module | 설명 |
| --- | --- | --- |
| 공통 API/도메인/orchestrator | `plugins/integrations/disaster-recovery` | 신규 DR 플랫폼 owner |
| 기존 DR cluster 호환 | `plugins/integrations/disaster-recovery` | 기존 API 유지 및 wrapper |
| FTCTL adapter 구현 | `plugins/integrations/ftctl-service` | 기존 FTCTL service를 감싸는 adapter |
| V2K adapter 구현 | `plugins/integrations/disaster-recovery` 또는 별도 V2K module | 초기에는 service wrapper로 시작 |
| VMware target adapter | `plugins/integrations/disaster-recovery` | vCenter/Mold VMware service 호출 |

`disaster-recovery`가 `ftctl-service`에 직접 compile dependency를 갖지 않는 것을 원칙으로 한다. FTCTL module이 공통 adapter interface에 의존해 optional bean을 제공하는 방식이 더 안전하다.

## 3. Package 구조

`plugins/integrations/disaster-recovery/src/main/java` 하위 권장 package:

| Package | 내용 |
| --- | --- |
| `com.cloud.dr` | service interface, public domain interface |
| `com.cloud.dr.dao` | 신규 DAO interface/impl |
| `com.cloud.dr.model` | enum, lightweight domain DTO |
| `com.cloud.dr.orchestrator` | plan/run orchestration |
| `com.cloud.dr.adapter` | 공통 adapter interfaces |
| `com.cloud.dr.adapter.vmware` | VMware source/target adapter |
| `com.cloud.dr.adapter.kvm` | KVM source/target adapter shell |
| `com.cloud.dr.adapter.v2k` | V2K wrapper adapter |
| `com.cloud.dr.response` | response factory/helper |
| `com.cloud.dr.event` | event 기록 helper |
| `com.cloud.dr.lock` | plan/run lock helper |

API package:

| Package | 내용 |
| --- | --- |
| `org.apache.cloudstack.api.command.admin.dr` | 신규 command class |
| `org.apache.cloudstack.api.response.dr` | 신규 response class |

## 4. Core service interfaces

### 4.1 DrSiteService

책임:

- `DrSite` CRUD
- `DrSiteCredentialService`와 연동한 credential 상태 검증
- connectivity/capability check
- 기존 `DisasterRecoveryCluster`와의 호환 projection

필수 method:

```java
DrSiteResponse createDrSite(CreateDrSiteCmd cmd);
ListResponse<DrSiteResponse> listDrSites(ListDrSitesCmd cmd);
DrSiteResponse getDrSite(GetDrSiteCmd cmd);
DrSiteResponse updateDrSite(UpdateDrSiteCmd cmd);
boolean deleteDrSite(DeleteDrSiteCmd cmd);
DrSiteCheckResponse checkDrSite(CheckDrSiteCmd cmd);
```

### 4.1.1 DrSiteCredentialService

책임:

- Mold/vCenter 인증정보 write-only 입력 검증
- `dr_site_credential.secret_payload` 암호화 저장
- `dr_site.credential_id` 갱신
- API response용 credential summary 생성
- Adapter/worker dispatch용 credential resolve
- check/rotation/clear 처리

필수 method:

```java
DrSiteCredentialVO storeOrReplace(long siteId, DrSiteCredentialInput input);
DrSiteCredentialSummary summarize(long siteId);
DrResolvedCredential resolve(long siteId, String requiredType);
DrSiteCredentialSummary validate(long siteId);
boolean clear(long siteId);
boolean hasUsableCredential(long siteId, String requiredType);
```

`DrResolvedCredential`은 secret-bearing object이므로 log/event/response에 직접 전달하지 않는다. 사용 후 `close()` 또는 finally block에서 내부 secret map을 비운다.

### 4.1.2 DrSite update/delete guard

`DrSiteServiceImpl`은 UI action gating과 별개로 update/delete 가능 여부를 최종 검증한다.

필수 dependency:

```java
@Inject DrSiteDao drSiteDao;
@Inject DrPlanDao drPlanDao;
@Inject DrSiteCredentialService drSiteCredentialService;
```

`updateSite` 규칙:

```java
public DrSiteVO updateSite(long siteId, DrSiteVO update, DrSiteCredentialInput credentialInput, boolean clearCredential) {
    DrSiteVO site = requireActiveSite(siteId);
    assertNoImmutableChangeWhenReferenced(site, update);
    applyMutableSiteFields(site, update);
    drSiteDao.update(siteId, site);

    if (clearCredential) {
        assertNoActivePlanReferences(siteId);
        drSiteCredentialService.clear(siteId);
    } else if (credentialInput != null && credentialInput.hasCredentialData()) {
        drSiteCredentialService.storeOrReplace(siteId, credentialInput);
    }
    return drSiteDao.findById(siteId);
}
```

`deleteSite` 규칙:

```java
public boolean deleteSite(long siteId) {
    DrSiteVO site = requireActiveSite(siteId);
    if (drPlanDao.countActiveBySiteId(siteId) > 0) {
        throw new InvalidParameterValueException("DR_SITE_IN_USE: active DR plan references site " + site.getUuid());
    }
    site.setCredentialId(null);
    site.setCredentialRef(null);
    if (!drSiteDao.update(siteId, site)) {
        throw new CloudRuntimeException("Failed to clear DR site credential reference " + site.getUuid());
    }
    drSiteCredentialService.clearCredentialsForDeletedSite(siteId);
    if (!drSiteDao.remove(siteId)) {
        throw new CloudRuntimeException("Failed to soft delete DR site " + site.getUuid());
    }
    DrSiteVO removedSite = drSiteDao.findByIdIncludingRemoved(siteId);
    return removedSite != null && removedSite.getRemoved() != null;
}
```

`countActiveBySiteId`는 source site 또는 target site 어느 쪽이든 참조하면 count해야 한다.

### 4.1.3 2026-07-02 site health/delete consistency 보강

상세 구현 기준은 [528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md](528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md)를 따른다.

추가 backend package:

| Package | 내용 |
| --- | --- |
| `com.cloud.dr.health` | `DrSiteHealthCheckService`, `DrSiteProbe`, Mold/vCenter probe client, health result DTO |

`DrSiteServiceImpl.checkSite`는 timestamp 갱신만 수행하지 않고 `DrSiteHealthCheckService.check(siteId, persistStatus)`로 위임한다.

`DrSiteCredentialDaoImpl.findActiveBySiteId`는 `removed IS NULL`만 확인하지 않는다. `findConfiguredBySiteId`, `findConfiguredByIdAndSiteId`, `findLatestBySiteId`로 분리하고, usable credential은 `state=CONFIGURED`와 `removed IS NULL`을 모두 만족해야 한다.

`DrResponseGenerator`의 `credentialconfigured`는 configured credential이 있을 때만 true다. `CLEARED` row, legacy `credential_ref`, missing credential은 false로 응답하고 `credentialstate`로 원인을 노출한다.

`deleteSite`는 site soft delete와 credential clear를 하나의 transaction으로 묶는다. credential clear를 먼저 수행한 뒤 site update가 실패해 site는 남고 credential만 해제되는 부분 변경은 허용하지 않는다.

CloudStack DAO는 `removed` 컬럼을 `DaoGenerated`로 취급하여 일반 `update()`에서 제외한다. 따라서 `DrSiteServiceImpl`, `DrPlanServiceImpl`, `DrSiteCredentialServiceImpl`의 삭제 경로는 `vo.markRemoved(); dao.update(id, vo);`를 사용하지 않고 다음 전용 경로를 사용한다.

| 대상 | 금지 패턴 | 사용 패턴 | 완료 검증 |
| --- | --- | --- | --- |
| DR Site | `site.markRemoved(); drSiteDao.update(...)` | `drSiteDao.remove(siteId)` | `findByIdIncludingRemoved(siteId).getRemoved() != null` |
| DR Site Credential | `credential.markRemoved(); drSiteCredentialDao.update(...)` | `state=CLEARED` update 후 `drSiteCredentialDao.remove(id)` | `findByIdIncludingRemoved(id).getRemoved() != null` |
| DR Plan | `plan.markRemoved(); drPlanDao.update(...)` | `drPlanDao.remove(planId)` | `findByIdIncludingRemoved(planId).getRemoved() != null` |

삭제된 site는 `DrSiteDao.listActive()`와 `DrSiteService.listSites()`에서 제외되어 scheduler 대상이 아니어야 한다. `DrSiteServiceImpl.checkSite(...)`는 삭제된 site에 대해 probe, `dr_site` update, `dr_site_health_check` insert를 수행하지 않는다.

### 4.2 DrPlanService

책임:

- `DrPlan` CRUD
- plan validation
- action eligibility 계산
- UI/API response 조립

필수 method:

```java
DrPlanResponse createDrPlan(CreateDrPlanCmd cmd);
ListResponse<DrPlanResponse> listDrPlans(ListDrPlansCmd cmd);
DrPlanResponse getDrPlan(GetDrPlanCmd cmd);
DrPlanResponse updateDrPlan(UpdateDrPlanCmd cmd);
DrPlanResponse enableDrPlan(EnableDrPlanCmd cmd);
DrPlanResponse disableDrPlan(DisableDrPlanCmd cmd);
DrPlanResponse deleteDrPlan(DeleteDrPlanCmd cmd);
Map<String, DrActionEligibility> getActionEligibility(DrPlanVO plan);
```

### 4.2.1 DrPlan update/delete guard

`DrPlanServiceImpl`은 계획 수정과 삭제를 단순 row update로 처리하지 않는다. DR 실행 엔진이 이미 runtime resource를 만들었는지 확인한 뒤 허용 범위를 제한한다.

필수 dependency:

```java
@Inject DrPlanDao drPlanDao;
@Inject DrRunDao drRunDao;
@Inject DrReplicaDao drReplicaDao;
@Inject DrRestorePointDao drRestorePointDao;
@Inject DrAdapterRegistry drAdapterRegistry;
```

`updatePlan` 규칙:

```java
public DrPlanVO updatePlan(long planId, DrPlanVO update) {
    DrPlanVO plan = requireActivePlan(planId);
    assertNoActiveRun(planId);

    boolean topologyChange = hasTopologyChange(plan, update);
    if (topologyChange && hasRuntimeResource(planId)) {
        throw new InvalidParameterValueException("DR_PLAN_TOPOLOGY_LOCKED: release or recreate the plan");
    }

    applyMutablePlanFields(plan, update);
    normalizePlanEngine(plan);
    validatePlan(plan);
    drPlanDao.update(planId, plan);
    return drPlanDao.findById(planId);
}
```

기본 허용 필드:

- 항상 허용: `name`, `description`, `rpoSeconds`, `rtoSeconds`, `scheduleJson`, `policyJson`, `quiescePolicyJson`
- 제한 허용: `sourceWorkerHostId`, `targetWorkerHostId`, `coordinatorWorkerHostId`
- runtime resource 생성 전만 허용: `sourceSiteId`, `targetSiteId`, `direction`, `sourceVmId`, `sourceExternalRef`, `engineType`, `engineBindingType`, `engineBindingId`, `mappingJson`

`deletePlan` 규칙:

```java
public boolean deletePlan(long planId) {
    DrPlanVO plan = requireActivePlan(planId);
    assertNoActiveRun(planId);
    if (hasActiveProtection(plan) || hasRuntimeResource(planId)) {
        throw new InvalidParameterValueException("DR_PLAN_DELETE_BLOCKED: release protection before delete");
    }
    if (!drPlanDao.remove(planId)) {
        throw new CloudRuntimeException("Failed to soft delete DR plan " + plan.getUuid());
    }
    DrPlanVO removedPlan = drPlanDao.findByIdIncludingRemoved(planId);
    return removedPlan != null && removedPlan.getRemoved() != null;
}
```

장시간 cleanup이 필요한 경우 `deleteDrPlan`이 agent/ftctl cleanup을 동기 실행하지 않는다. UI는 `releaseDrProtection` 또는 별도 cleanup action을 먼저 수행하고, 삭제는 남은 Cloud metadata를 soft delete하는 단계로 유지한다.

`getActionEligibility`는 기존 runtime action 외에 다음 key를 추가할 수 있다.

```json
{
  "update": { "enabled": true },
  "delete": {
    "enabled": false,
    "reasonCode": "DR_PLAN_DELETE_BLOCKED",
    "message": "Release protection before deleting this DR plan."
  }
}
```

### 4.3 DrRunService

책임:

- action API를 `DrRun`으로 변환
- idempotency 처리
- run 조회/취소/manual-confirm
- progress/event projection

필수 method:

```java
DrRunResponse startSync(StartDrSyncCmd cmd);
DrRunResponse startTestFailover(StartDrTestFailoverCmd cmd);
DrRunResponse stopTestFailover(StopDrTestFailoverCmd cmd);
DrRunResponse startFailover(StartDrFailoverCmd cmd);
DrRunResponse confirmFenceClear(ConfirmDrFenceClearCmd cmd);
DrRunResponse startFailback(StartDrFailbackCmd cmd);
DrRunResponse startReprotect(StartDrReprotectCmd cmd);
DrRunResponse adoptReplica(AdoptDrReplicaCmd cmd);
DrRunResponse cancelRun(CancelDrRunCmd cmd);
DrRunResponse getDrRun(GetDrRunCmd cmd);
ListResponse<DrRunResponse> listDrRuns(ListDrRunsCmd cmd);
```

### 4.4 DrProjectionService

책임:

- engine runtime state를 `DrReplica.runtime_state_json`, `DrRunStep.details_json`, `DrEvent`로 투영
- stale projection과 engine failure를 구분
- UI refresh에서 필요한 summary를 빠르게 제공

필수 method:

```java
DrPlanRuntimeSummary refreshPlanProjection(long planId, boolean bestEffort);
DrReplicaRuntimeSummary refreshReplicaProjection(long replicaId, boolean bestEffort);
List<DrEventVO> relayEngineEvents(long planId, Long runId, int limit);
```

Projection 실패는 기본적으로 보호 해제나 cleanup을 트리거하지 않는다.

## 5. Orchestrator 구조

### 5.1 DrOrchestrator

`DrOrchestrator`는 action별 high-level flow를 가진다.

| Method | 설명 |
| --- | --- |
| `createRun` | idempotency key 확인 후 `DrRun` 생성 |
| `executeRun` | run type에 따라 adapter 호출 |
| `transitionPlan` | plan state 전이 |
| `transitionReplica` | replica state 전이 |
| `recordStep` | 사람이 읽을 수 있는 step 생성/갱신 |
| `recordEvent` | `DrEvent` 기록 |
| `handleFailure` | retryable/terminal/rollback required 분리 |

`DrOrchestrator`는 FTCTL shell command, vCenter SDK, V2K CLI를 직접 호출하지 않는다. 모든 engine 호출은 adapter를 통한다.

### 5.2 DrRunExecutor

Phase 1 권장:

- API async job 내부에서 짧은 작업을 직접 실행할 수 있다.
- `DrRun`은 DB에 항상 기록한다.
- skeleton VM 생성처럼 수분 내 종료되는 작업은 async job이 terminal까지 기다려도 된다.

Phase 2 이후:

- 장시간 data mover는 별도 executor/scheduler가 `QUEUED` run을 가져간다.
- executor는 active management node에서 한 번만 동작해야 한다.
- job handoff 후 UI는 `getDrRun`으로 progress를 본다.

### 5.3 DrAdapterRegistry

책임:

- `DrPlanDirection`, site type, engine binding에 맞는 adapter 조합 반환
- optional adapter 누락 시 명확한 `DR_ENGINE_UNAVAILABLE` 반환
- adapter capability를 `checkDrSite`와 `createDrPlan` validation에 제공

필수 method:

```java
DrSourceAdapter getSourceAdapter(DrSiteType siteType);
DrReplicationEngine getReplicationEngine(DrPlanDirection direction, String engineBindingType);
DrMaterializer getMaterializer(DrPlanDirection direction, String sourceFormat, String targetFormat);
DrTargetAdapter getTargetAdapter(DrSiteType siteType);
DrFencingAdapter getFencingAdapter(DrPlan plan);
DrAdapterBundle resolve(DrPlan plan);
```

Adapter registry는 startup 시 available adapter 목록을 log에 남긴다.

## 6. Adapter bean 배치

### 6.1 Common adapter interfaces

공통 interface는 `plugins/integrations/disaster-recovery`에 둔다.

- `DrSourceAdapter`
- `DrReplicationEngine`
- `DrMaterializer`
- `DrTargetAdapter`
- `DrFencingAdapter`
- `DrAdapterResult`
- `DrExecutionContext`

### 6.2 VMware adapters

초기 구현 위치:

- `com.cloud.dr.adapter.vmware.VmwareTargetAdapter`
- `com.cloud.dr.adapter.vmware.VmwareSourceAdapter`
- `com.cloud.dr.adapter.vmware.VmwareFencingAdapter`

Phase 1에서는 `VmwareTargetAdapter`가 우선이다.

### 6.3 KVM adapters

초기 구현 위치:

- `com.cloud.dr.adapter.kvm.KvmSourceAdapter`
- `com.cloud.dr.adapter.kvm.KvmTargetAdapter`
- `com.cloud.dr.adapter.kvm.KvmFencingAdapter`

KVM-to-KVM FTCTL 실제 실행은 FTCTL adapter가 담당한다. KVM adapter는 Cloud VM/volume/network metadata 조회를 맡는다.

### 6.4 FTCTL adapter

FTCTL adapter는 `plugins/integrations/ftctl-service`에 둘 것을 권장한다.

권장 class:

- `com.cloud.ftctl.dr.FtctlDrReplicationEngine`
- `com.cloud.ftctl.dr.FtctlDrFencingAdapter`
- `com.cloud.ftctl.dr.FtctlDrProjectionAdapter`

이 class들은 `disaster-recovery`의 공통 interface를 구현한다. 따라서 `ftctl-service` module이 `disaster-recovery` module의 adapter API에 compile dependency를 가질 수 있다. 반대 방향 dependency는 피한다.

FTCTL module이 설치되지 않은 환경에서는 KVM-to-KVM plan action이 `DR_ENGINE_UNAVAILABLE`로 실패해야 한다.

## 7. DAO wiring

신규 DAO:

| DAO | VO |
| --- | --- |
| `DrSiteDao` | `DrSiteVO` |
| `DrSitePairDao` | `DrSitePairVO` |
| `DrPlanDao` | `DrPlanVO` |
| `DrRestorePointDao` | `DrRestorePointVO` |
| `DrRestorePointArtifactDao` | `DrRestorePointArtifactVO` |
| `DrReplicaDao` | `DrReplicaVO` |
| `DrReplicaDiskDao` | `DrReplicaDiskVO` |
| `DrRunDao` | `DrRunVO` |
| `DrRunStepDao` | `DrRunStepVO` |
| `DrEventDao` | `DrEventVO` |

DAO impl은 `GenericDaoBase` 패턴을 따른다.

필수 search method:

| DAO | Method |
| --- | --- |
| `DrSiteDao` | `findByUuid`, `findActiveByName`, `listByTypeAndStatus` |
| `DrPlanDao` | `findByUuid`, `findActiveBySourceVmId`, `listByState`, `listBySourceTargetSite`, `findByEngineBinding` |
| `DrRestorePointDao` | `findLatestByPlanId`, `findLatestTargetReadyByPlanId`, `listByPlanId` |
| `DrReplicaDao` | `findActiveByPlanId`, `findByTargetVmId`, `findByTargetExternalRef` |
| `DrRunDao` | `findActiveByPlanId`, `findByIdempotencyKey`, `listByPlanId`, `listQueuedRuns` |
| `DrRunStepDao` | `listByRunId`, `findByRunIdAndStepName` |
| `DrEventDao` | `listByPlanRunAndTime`, `deleteExpired` |

## 8. Spring context

`plugins/integrations/disaster-recovery/src/main/resources/META-INF/cloudstack/disaster-recovery/spring-disaster-recovery-context.xml`에 신규 bean을 추가한다.

필수 bean:

```xml
<bean id="drSiteServiceImpl" class="com.cloud.dr.orchestrator.DrSiteServiceImpl" />
<bean id="drPlanServiceImpl" class="com.cloud.dr.orchestrator.DrPlanServiceImpl" />
<bean id="drRunServiceImpl" class="com.cloud.dr.orchestrator.DrRunServiceImpl" />
<bean id="drOrchestratorImpl" class="com.cloud.dr.orchestrator.DrOrchestratorImpl" />
<bean id="drRunExecutorImpl" class="com.cloud.dr.orchestrator.DrRunExecutorImpl" />
<bean id="drAdapterRegistryImpl" class="com.cloud.dr.adapter.DrAdapterRegistryImpl" />
<bean id="drProjectionServiceImpl" class="com.cloud.dr.orchestrator.DrProjectionServiceImpl" />
<bean id="drSiteCredentialServiceImpl" class="com.cloud.dr.credential.DrSiteCredentialServiceImpl" />
<bean id="drResponseGenerator" class="com.cloud.dr.response.DrResponseGenerator" />
```

DAO bean도 같은 context에 추가한다. `DrSiteCredentialDaoImpl`은 `dr_site_credential` 조회와 active credential soft delete를 담당한다.

FTCTL adapter bean은 `plugins/integrations/ftctl-service/src/main/resources/META-INF/cloudstack/ftctl-service/spring-ftctl-service-context.xml`에 추가한다.

```xml
<bean id="ftctlDrReplicationEngine" class="com.cloud.ftctl.dr.FtctlDrReplicationEngine" />
<bean id="ftctlDrFencingAdapter" class="com.cloud.ftctl.dr.FtctlDrFencingAdapter" />
<bean id="ftctlDrProjectionAdapter" class="com.cloud.ftctl.dr.FtctlDrProjectionAdapter" />
```

## 9. Transaction boundary

### 9.1 createDrPlan

Transaction 안에서 수행:

1. source/target site row lock
2. active plan/protection conflict check
3. typed plan input을 `DrPlanSpecBuilder`로 canonical mapping/schedule/policy/quiesce JSON으로 변환하고 validation result 저장
4. `dr_plan` insert
5. optional `dr_replica` skeleton intent insert
6. event 기록

Transaction 밖에서 수행:

- remote endpoint connectivity check
- vCenter inventory refresh
- FTCTL runtime status read
- `previewDrPlanSpec` 또는 create preflight에서 수행한 target resource/worker host 의미 검증 재확인

외부 호출을 DB transaction 안에 오래 잡아두지 않는다.

### 9.2 start action

Transaction 안에서 수행:

1. `DrPlan` row lock
2. active run conflict check
3. idempotency key lookup
4. `DrRun` insert
5. first `DrRunStep` insert
6. plan state transition to transient state

Transaction 밖에서 수행:

- adapter preflight
- engine command
- long-running polling

### 9.3 failure handling

Adapter failure 발생 시:

- `DrRun.state=FAILED`
- current step `FAILED`
- `DrPlan.state=ERROR` 또는 이전 stable state 유지
- `DrEvent.severity=ERROR`
- retryable 여부 저장

Projection refresh failure는 기본적으로 `DrPlan.state=ERROR`로 바꾸지 않는다. `DrPlan.last_error_code=DR_PROJECTION_STALE` 같은 보조 상태만 갱신한다.

## 10. Locking

Plan 단위 lock은 두 겹으로 둔다.

1. DB row lock: `DrPlanDao.lockRow(planId, true)` 계열
2. active run guard: 같은 plan에 terminal이 아닌 `DrRun`이 있으면 destructive run 생성 거부

Destructive run:

- `SYNC`
- `TEST_FAILOVER`
- `TEST_CLEANUP`
- `FAILOVER`
- `FAILBACK`
- `REPROTECT`
- `ADOPT`
- `DELETE`

Non-destructive run:

- `CHECK`
- `PROJECTION_REFRESH`
- `EVENT_RELAY`

Non-destructive run도 engine별 lock과 충돌할 수 있으므로 adapter가 `DR_ENGINE_BUSY`를 반환할 수 있어야 한다.

## 11. Existing API compatibility wiring

기존 `DisasterRecoveryClusterServiceImpl`는 바로 제거하지 않는다.

호환 전략:

- 기존 API는 기존 테이블을 계속 사용한다.
- 신규 `DrSitePair`는 기존 `disaster_recovery_cluster.id`를 `legacy_dr_cluster_id`로 참조할 수 있다.
- 기존 cluster API가 신규 테이블을 자동 생성할지는 Phase 1에서 비활성으로 둔다.
- 별도 migration/import command를 만들 경우에만 기존 cluster를 `DrSitePair`로 투영한다.

기존 FTCTL API도 유지한다.

호환 전략:

- 기존 FTCTL UI는 기존 `FtctlService`를 계속 호출한다.
- 신규 DR UI는 `DrPlan` API를 호출한다.
- 같은 VM에 active `DrPlan`과 active `ftctl_protection`이 중복 생성되지 않도록 양쪽 service에서 guard를 둔다.
- 기존 FTCTL 보호를 `DrPlan`으로 연결하려면 명시적 import action이 필요하다.

## 12. Response generation

`DrResponseGenerator`는 다음을 담당한다.

- VO -> API response 변환
- uuid 노출
- secret masking
- action eligibility 포함
- current run/latest run summary 조립
- target-ready RPO 계산
- stale projection warning 포함

API command가 직접 여러 DAO를 호출해 response를 조립하지 않는다.

## 13. Test coverage

필수 unit test:

- `DrPlanServiceImplTest`
- `DrRunServiceImplTest`
- `DrOrchestratorImplTest`
- `DrAdapterRegistryImplTest`
- `DrResponseGeneratorTest`
- `DrSiteServiceImplTest`

필수 scenario:

- 같은 source VM에 중복 plan 생성 거부
- idempotency key 재요청 시 기존 run 반환
- active run 존재 시 destructive action 거부
- projection stale이 plan error로 과격하게 전이되지 않음
- FTCTL adapter missing 시 KVM-to-KVM action이 `DR_ENGINE_UNAVAILABLE`
- VMware target skeleton 생성 실패 시 run/step/event에 원인 보존
- async job success와 `DrRun` terminal state 구분

## 14. 구현 순서

1. VO/DAO/DDL 추가
2. response class와 response generator 추가
3. `DrSiteService` CRUD 추가
4. `DrPlanService` CRUD와 action eligibility 추가
5. `DrRunService`와 idempotency guard 추가
6. `DrAdapterRegistry`와 Noop/VMware target adapter 추가
7. `DrOrchestrator` sync skeleton flow 추가
8. command class 추가
9. UI API wrapper 연결
10. FTCTL adapter bridge 추가

## 15. 2026-07-02 추가 설계: DR Site health scheduler/history backend

상세 설계는 [529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md](529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md)를 따른다.

### 15.1 신규 backend 구성요소

| Package/Class | 책임 |
| --- | --- |
| `com.cloud.dr.DrSiteHealthCheckVO` | `dr_site_health_check` entity |
| `com.cloud.dr.dao.DrSiteHealthCheckDao` | site health check history 조회/정리 |
| `com.cloud.dr.dao.DrSiteHealthCheckDaoImpl` | `site_id`, `checked_at`, `state`, `trigger` 기준 검색 |
| `com.cloud.dr.DrSiteHealthCheckHistoryService` | health result를 history row로 기록 |
| `com.cloud.dr.DrSiteHealthCheckHistoryServiceImpl` | redacted details_json 구성, retention cleanup |
| `com.cloud.dr.health.DrSiteHealthCheckScheduler` | active DR site 주기 점검 |
| `org.apache.cloudstack.api.command.admin.dr.ListDrSiteHealthChecksCmd` | health check history list API |
| `org.apache.cloudstack.api.response.dr.DrSiteHealthCheckResponse` | UI table 응답 |

### 15.2 DrSiteService 변경

`DrSiteService`는 기존 호환 메서드를 유지하고 trigger/jobId를 받는 overload를 추가한다.

```java
DrSiteVO checkSite(long siteId);
DrSiteVO checkSite(long siteId, boolean persistStatus);
DrSiteVO checkSite(long siteId, boolean persistStatus, String triggerType, String jobId);
```

`persistStatus=true`일 때 처리 순서:

1. transaction 밖에서 `DrSiteHealthCheckService.checkSite(site, true)` 실행
2. transaction 안에서 `dr_site.health_state`, `last_checked`, `capabilities_json.healthCheck` 갱신
3. 같은 transaction 안에서 `DrSiteHealthCheckHistoryService.record(...)` 호출

history insert 실패 시 최신 상태만 갱신되는 부분 성공을 허용하지 않는다.

### 15.3 Scheduler

`DrSiteHealthCheckScheduler`는 `ScheduledExecutorService + ManagedContextRunnable + GlobalLock` 패턴을 사용한다.

```java
executor.scheduleWithFixedDelay(new HealthCheckTask(), interval, interval, TimeUnit.SECONDS);
```

worker 기준:

- config `dr.site.health.check.enabled=true`일 때만 동작
- `dr.site.health.check.interval` 기본 300초
- `dr.site.health.check.batch.size` 기본 25개
- `GlobalLock.getInternLock("dr.site.health.check.scheduler")`로 다중 management 중복 실행 방지
- `drSiteDao.listDueForHealthCheck(batchSize, interval)`로 대상 선정
- 각 site는 `drSiteService.checkSite(site.getId(), true, HEALTH_TRIGGER_SCHEDULED, null)` 호출
- cleanup은 `dr.site.health.check.history.retention.days` 기본 30일 기준

### 15.4 Spring wiring

```xml
<bean id="drSiteHealthCheckDaoImpl" class="com.cloud.dr.dao.DrSiteHealthCheckDaoImpl"/>
<bean id="drSiteHealthCheckHistoryServiceImpl" class="com.cloud.dr.DrSiteHealthCheckHistoryServiceImpl"/>
<bean id="drSiteHealthCheckScheduler" class="com.cloud.dr.health.DrSiteHealthCheckScheduler"/>
```

Agent/ftctl에는 명령을 보내지 않는다. 이 기능은 Cloud management control-plane에서 끝나는 endpoint/credential inventory 검증이다.

## 16. AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| Backend owner | 기존 DR cluster service와 FTCTL service가 분리 | `disaster-recovery`가 공통 DR orchestrator 소유 |
| Engine 호출 | 기능별 service가 직접 호출 | adapter registry를 통해 engine 호출 |
| FTCTL 연계 | UI/API가 FTCTL service 직접 사용 | 신규 DR는 FTCTL adapter를 통해 사용, 기존 FTCTL API 유지 |
| 상태 실행 | Cloud async job 중심 | Cloud async job + `DrRun` source of truth |
| Site 삭제 검증 | UI에서만 참조 여부를 판단할 수 있음 | `DrSiteService.deleteSite`가 active plan 참조를 DAO로 최종 검증 |
| Plan 수정/삭제 | row update/delete로 오해 가능 | active run/protection/runtime resource guard 후 제한적 update/soft delete |
| Response 조립 | command/service별 개별 조립 | `DrResponseGenerator`로 통일 |
| Lock | 기능별 guard | plan row lock + active run guard |
| Spring wiring | 기존 DR/FTCTL bean만 존재 | DR service/DAO/registry/executor/adapter bean 추가 |

## 17. 2026-07-02 구현 반영: 서비스 guard

이번 구현에서는 UI의 사전 disabled 상태와 무관하게 backend service가 최종 안전성을 검증하도록 다음 guard를 추가한다.

| 구현 위치 | guard | 결과 |
| --- | --- | --- |
| `DrPlanDao.countActiveBySiteId(long siteId)` | active `dr_plan` 중 source/target site 참조 수 계산 | `DrSiteResponse.activeplancount`와 site 삭제 검증에 사용 |
| `DrSiteServiceImpl.deleteSite` | `countActiveBySiteId(siteId) > 0` | `DR_ACTIVE_PLAN_EXISTS` 오류로 site 삭제 거부 |
| `DrPlanServiceImpl.updatePlan` | active run 존재 | `DR_ACTIVE_RUN_EXISTS` 오류로 plan 수정 거부 |
| `DrPlanServiceImpl.updatePlan` | replica/restore point/target-ready가 있고 source/engine/worker/mapping 변경 요청 | `DR_RUNTIME_RESOURCE_EXISTS` 오류로 위험 변경 거부 |
| `DrPlanServiceImpl.deletePlan` | active run, runtime resource, protected state 존재 | `DR_RUNTIME_RESOURCE_EXISTS` 오류로 plan 삭제 거부 |
| `DrPlanServiceImpl.getActionEligibility` | `update`, `delete` key 추가 | UI `ActionButton` disabled 판단에 사용 |

`DrResponseGenerator`는 site 응답에 `activeplancount`, plan 응답에 `schedulejson`, `policyjson`, `mappingjson`, `quiescepolicyjson`을 포함한다. 신규 DB 컬럼은 없으며, 기존 `dr_plan`, `dr_replica`, `dr_restore_point`, `dr_run` projection을 기준으로 판단한다.

## 18. 2026-07-03 추가 설계: DR Site inventory backend wiring

상세 설계는 [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)를 따른다.

추가 package:

`com.cloud.dr.inventory`

추가 class:

| class | 역할 |
| --- | --- |
| `DrSiteInventoryService` | `DiscoverDrSiteInventoryCmd`가 호출하는 service contract |
| `DrSiteInventoryServiceImpl` | 기존 site credential resolve와 create-mode write-only credential 처리 |
| `DrSiteInventoryRequest` | command parameter DTO |
| `DrSiteInventoryResult` | service result DTO |
| `DrInventoryOption` | Zone/VMware DC select option DTO |
| `DrMoldInventoryClient` | HmacSHA256 signed Mold API request로 `listZones`, `listVmwareDcs` 호출 |

Spring wiring:

```xml
<bean id="drSiteInventoryServiceImpl" class="com.cloud.dr.inventory.DrSiteInventoryServiceImpl"/>
<bean id="drMoldInventoryClient" class="com.cloud.dr.inventory.DrMoldInventoryClient"/>
```

Backend 원칙:

1. `discoverDrSiteInventory`는 control-plane inventory 조회이며 Agent/ftctl로 명령을 보내지 않는다.
2. 기존 site 조회 시 삭제된 site는 거부한다.
3. create mode의 `moldsecretkey`는 DB에 저장하지 않고 요청 서명에만 사용한다.
4. Mold API 서명 알고리즘은 health probe와 동일하게 `HmacSHA256`을 사용한다.
5. 조회 결과는 UI select option으로만 쓰며 `dr_site.health_state`, `dr_site_health_check`를 갱신하지 않는다.
6. 신규 DB migration은 없다. `dr_site.zone_id`, `dr_site.vmware_datacenter_id`는 선택된 ID 저장소로 유지한다.

### 18.1 2026-07-03 Remote inventory external id backend 보정

상세 설계는 [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)의
`Remote Inventory ID 모델 보정` 절을 따른다.

Backend 원칙을 다음처럼 수정한다.

1. `dr_site.zone_id`, `dr_site.vmware_datacenter_id`는 local internal id 저장소다.
2. 원격 Mold/vCenter inventory 선택값은 `zone_external_id`, `zone_name`, `vmware_datacenter_external_id`, `vmware_datacenter_name`에 저장한다.
3. `DrMoldInventoryClient`는 numeric id 추출을 선택 가능 조건으로 삼지 않는다.
4. `DrInventoryOption.value`는 원격 external id를 기본값으로 한다.
5. `listVmwareDcs` 호출은 `zoneExternalId`를 우선 사용하고, 없을 때만 legacy `zoneId`를 fallback으로 사용한다.
6. Adapter는 remote Mold API 호출 시 external id를 사용하고, local DAO/FK 조회가 필요한 경로에서만 local id를 사용한다.

추가/변경 class 책임:

| Class | 변경 |
| --- | --- |
| `DrSiteVO` | `zoneExternalId`, `zoneName`, `vmwareDatacenterExternalId`, `vmwareDatacenterName` 추가 |
| `CreateDrSiteCmd` | `zoneexternalid`, `zonename`, `vmwaredcexternalid`, `vmwaredcname` parameter 추가 |
| `UpdateDrSiteCmd` | 동일 parameter 추가 및 부분 갱신 지원 |
| `DiscoverDrSiteInventoryCmd` | `zoneexternalid` parameter 추가 |
| `DrSiteInventoryRequest` | `zoneExternalId` field 추가 |
| `DrMoldInventoryClient` | `DrInventoryOption.value=externalId`, `localId` optional 처리 |
| `DrResponseGenerator` | site response와 inventory option response에 external/name field 포함 |

기존 문서의 "신규 DB migration은 없다"는 2026-07-03 최초 inventory select 보강에 한정된 내용이다.
external id를 정상 저장하려면 DB migration이 필요하며, 구현 시 [509 문서](509-cross-hypervisor-dr-db-upgrade-and-entity-design-20260630.md)의
Remote inventory external id DDL을 함께 반영해야 한다.

## 19. 2026-07-05 추가 설계: DR Plan guided spec backend wiring

DR Plan 생성/수정에서 raw JSON을 사용자 입력으로 받는 흐름은 기본 경로에서 제거한다. Backend는 typed API parameter와 inventory/preflight 결과를 canonical JSON으로 변환한 뒤 기존 `dr_plan` JSON column에 저장한다. 상세 설계는 [531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md](531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md)를 따른다.

2026-07-06 보강 기준으로 `VMWARE_TO_KVM` backend wiring은 target ABLESTACK placement를 완성해야 한다. `DrPlanInventoryServiceImpl`은 target Zone을 먼저 resolve하고, KVM target에서는 worker host, primary storage, service offering, disk offering, network를 Zone 기준으로 조회한다. `DrVmwareInventoryClient`는 source VM 목록 조회에 더해 선택된 VM의 disk/NIC 상세를 조회한다. `DrPlanGuidedSpecBuilder`는 service offering, network, storage, worker, disk offering을 `mapping_json.target`과 `mapping_json.disks[]`에 저장하고, `DrPlanReadinessValidator`는 target placement 누락을 `CONFIG_INCOMPLETE` blocker로 계산한다.

추가 package:

`com.cloud.dr.plan.spec`

| class | 역할 |
| --- | --- |
| `DrPlanSpecService` | preview/create/update에서 spec build와 validation을 제공 |
| `DrPlanSpecServiceImpl` | site, workload, worker, target resource option을 조합 |
| `DrPlanSpecRequest` | `CreateDrPlanCmd`, `UpdateDrPlanCmd`, `PreviewDrPlanSpecCmd` 공통 typed request |
| `DrPlanSpec` | engine, worker host, mapping/schedule/policy/quiesce JSON aggregate |
| `DrPlanSpecBuilder` | direction/engine별 canonical JSON 생성 |
| `FtctlDrPlanSpecValidator` | FTCTL_DR worker/disk mapping/profile schema 검증 |
| `VmwareTargetPlanSpecValidator` | VMware datastore/resource pool/folder/network 검증 |
| `KvmTargetPlanSpecValidator` | ABLESTACK target storage/network/service offering 검증 |

Spring wiring:

```xml
<bean id="drPlanSpecServiceImpl" class="com.cloud.dr.plan.spec.DrPlanSpecServiceImpl"/>
<bean id="drPlanSpecBuilder" class="com.cloud.dr.plan.spec.DrPlanSpecBuilder"/>
<bean id="ftctlDrPlanSpecValidator" class="com.cloud.dr.plan.spec.FtctlDrPlanSpecValidator"/>
<bean id="vmwareTargetPlanSpecValidator" class="com.cloud.dr.plan.spec.VmwareTargetPlanSpecValidator"/>
<bean id="kvmTargetPlanSpecValidator" class="com.cloud.dr.plan.spec.KvmTargetPlanSpecValidator"/>
```

Service 흐름:

1. `PreviewDrPlanSpecCmd`는 DB insert 없이 `DrPlanSpecService.preview()`를 호출한다.
2. `CreateDrPlanCmd`는 typed parameter를 `DrPlanSpecRequest`로 만들고 `DrPlanSpecService.buildForCreate()`를 호출한다.
3. `UpdateDrPlanCmd`는 runtime resource guard를 통과한 경우에만 `DrPlanSpecService.buildForUpdate()`를 호출한다.
4. `DrPlanServiceImpl`은 반환된 `DrPlanSpec`의 engine/worker/canonical JSON을 `DrPlanVO`에 저장한다.
5. `FtctlDrUnifiedActionAdapter`는 기존 profile field를 유지하되, profile source가 사용자 raw JSON이 아니라 backend-generated canonical JSON임을 전제로 한다.

DB 영향:

- 신규 column은 즉시 추가하지 않는다.
- 기존 `mapping_json`, `schedule_json`, `policy_json`, `quiesce_policy_json`에 `schemaVersion`을 포함한 canonical JSON을 저장한다.
- 검색/필터가 필요한 경우에만 후속 migration으로 `sync_interval_seconds`, `retention_count`, `consistency_mode`, `test_network_mode` 같은 column을 추가한다.

## 2026-07-06 보강: Backend Dispatch와 Projection 일관성

DR backend의 dispatch/projection 상태 전이 최신 기준은 [533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md](533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md)를 따른다.

Backend 구현 원칙:

- `prepareSyncRun()` 단계에서 plan을 `SYNCING`으로 먼저 바꾸지 않는다.
- plan `SYNCING` 전환은 Agent/FTCTL acceptance 이후에만 수행한다.
- `DrRun`은 `QUEUED`, `PREPARING`, `DISPATCHING`, `ACCEPTED`, `RUNNING`, terminal state를 분리한다.
- failure path는 `DrRun`, `DrPlan`, `DrRunStep`을 함께 갱신한다.
- projection adapter는 pre-accept 또는 startup grace 구간의 `not_found`를 terminal failure로 처리하지 않는다.
- profile/request/context secret은 command logging에서 제외한다.

## 20. 2026-07-06 추가 보강: plan active run 직렬화와 retryable lock 처리

상세 기준은 [533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md](533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md)의 18장을 따른다.

대상 class:

- `com.cloud.dr.orchestrator.DrRunExecutorImpl`
- `com.cloud.dr.adapter.ftctl.FtctlDrUnifiedActionAdapter`
- `com.cloud.dr.adapter.ftctl.FtctlDrRuntimeProjectionAdapter`
- `com.cloud.dr.dao.DrRunDaoImpl`
- `com.cloud.dr.dao.DrRunStepDaoImpl`
- `com.cloud.dr.DrPlanServiceImpl`

### 20.1 plan 단위 실행 직렬화

같은 plan에 대해 다음 상태의 run이 있으면 destructive action은 중복 실행하지 않는다.

```java
private static final Set<String> ACTIVE_RUN_STATES = Set.of(
    DrConstants.RUN_STATE_QUEUED,
    DrConstants.RUN_STATE_PREPARING,
    DrConstants.RUN_STATE_DISPATCHING,
    DrConstants.RUN_STATE_RETRYING,
    DrConstants.RUN_STATE_ACCEPTED,
    DrConstants.RUN_STATE_RUNNING,
    DrConstants.RUN_STATE_CANCEL_REQUESTED
);
```

`PAUSE_SYNC` 직후 `SYNC`처럼 순서 보존이 가능한 action은 queue 정책을 사용할 수 있다. failover/failback/release처럼 destructive action 간 순서가 위험한 경우는 `DR_PLAN_RUN_ACTIVE`로 거부한다.

DAO 보강:

```java
DrRunVO findLatestActiveByPlanId(long planId);
List<DrRunVO> listActiveByPlanId(long planId);
```

### 20.2 retryable lock 분류

FTCTL 응답이 다음 조건이면 terminal failure가 아니라 retryable busy로 분류한다.

```java
boolean lockedRetryable = exitCode == 20
        && StringUtils.equalsIgnoreCase(json.get("result").getAsString(), "locked")
        && json.has("retryable")
        && json.get("retryable").getAsBoolean();
```

Backend result:

```java
return DrAdapterResult.retryable(
    DrConstants.ERROR_ENGINE_BUSY_RETRYABLE,
    "FTCTL engine is busy with " + holderCommand,
    detailsJson,
    retryAfterSeconds);
```

`DrRunExecutorImpl`은 retryable result를 다음처럼 처리한다.

- `run.state=RETRYING`
- `run.retryable=true`
- `run.retry_count=retry_count+1`
- `run.retry_after_seconds`와 `run.next_retry_at` 저장
- plan은 `SYNCING`으로 두지 않고 이전 stable state 또는 `READY`를 유지하되 `last_error_code=DR_ENGINE_BUSY_RETRYABLE` 기록
- retry window가 끝나면 `DR_ENGINE_BUSY_TIMEOUT`으로 terminal failure 처리

### 20.3 terminal failure step closure

현재 장애처럼 같은 run에 `FAILED` step과 `RUNNING` step이 같이 남지 않도록 `failRun()`은 open step을 먼저 닫는다.

```java
private void closeOpenSteps(DrRunVO run, String terminalState, String errorCode, String message) {
    for (DrRunStepVO step : drRunStepDao.listActiveByRunId(run.getId())) {
        if (StringUtils.equalsAny(step.getState(), STEP_STATE_QUEUED, STEP_STATE_RUNNING, STEP_STATE_RETRYING)) {
            step.setState(terminalState);
            step.setCompleted(new Date());
            step.setErrorCode(errorCode);
            step.setErrorMessage(message);
            drRunStepDao.update(step.getId(), step);
        }
    }
}
```

`recordStep()`은 insert-only가 아니라 `(run_id, step_order)` upsert로 동작해야 한다.

### 20.4 projection refresh isolation

`FtctlDrRuntimeProjectionAdapter.refreshPlanProjection()`은 list/get API에서 직접 호출하지 않는다. Backend scheduler 또는 explicit projection command만 호출한다.

`DR_STATUS_TIMEOUT`은 plan terminal failure가 아니라 projection stale 상태다.

```java
if (DrConstants.ERROR_STATUS_TIMEOUT.equals(status.getErrorCode())) {
    markProjectionStale(plan, status);
    return DrAdapterResult.retryable(DrConstants.ERROR_PROJECTION_STALE, status.getDetails(), status.getStatusJson(), 5);
}
```

수용 기준:

- `dr-sync-start`가 `dr-sync-pause` lock에 막히면 run은 `RETRYING` 또는 `FAILED_RETRYABLE`로 표시되고 plan은 `SYNCING`에 남지 않는다.
- terminal run에는 `QUEUED`/`RUNNING` open step이 남지 않는다.
- `getDrPlan` 호출이 Agent status refresh를 직접 수행하지 않는다.

## 21. 2026-07-06 추가 보강: SYNC 완료 조건과 target materialization 검증

상세 기준은 [534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md](534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md)를 따른다.

`FtctlDrRuntimeProjectionAdapter`는 SYNC run의 성공 조건을 runtime state 문자열로만 판단하지 않는다. 특히 `SYNCING`, `READY`, `TARGET_READY`, `PAUSED` 중 하나라는 이유만으로 run을 `SUCCEEDED`로 승격하는 로직은 제거한다.

개선 설계:

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
    if (StringUtils.isBlank(status.getLastTargetDurableAt())) {
        return false;
    }

    DrReplicaVO replica = drReplicaDao.findActiveByPlanId(plan.getId());
    if (replica == null || !replica.hasTargetReference()) {
        return false;
    }

    DrTargetReadiness readiness = targetInventoryVerifier.verify(plan, replica);
    return readiness.isReady() && drRestorePointDao.countActiveByPlanId(plan.getId()) > 0;
}
```

새 backend service:

```java
public interface DrTargetInventoryVerifier {
    DrTargetReadiness verify(DrPlanVO plan, DrReplicaVO replica);
}
```

구현체:

- `AbleStackDrTargetInventoryVerifier`
  - `vm_instance`, `volumes`, `nics`, storage pool, target VM state 확인
- `VmwareDrTargetInventoryVerifier`
  - vCenter moRef, disk backing, snapshot/checkpoint metadata 확인

수용 기준:

- `dr_replica.target_vm_id` 또는 `target_external_ref`가 비어 있으면 SYNC run은 `SUCCEEDED`가 될 수 없다.
- `dr_restore_point`가 없거나 `last_target_durable_at`가 비어 있으면 Plan은 `TARGET_MATERIALIZING` 또는 `DEGRADED`에 머문다.
- retryable lock은 `DR_ENGINE_BUSY_RETRYABLE`로 보존하고 terminal success/failure로 오판하지 않는다.

## 2026-07-07 Update: Backend Validator And Projection Reconciler

Backend execution must not rely on FTCTL to discover predictable guided-spec
errors. Add a shared disk readiness validator and call it from preview, create,
update, orchestration, and projection recovery paths.

Backend code-level rules:

- `DrPlanGuidedSpecBuilder` preserves only positive disk sizes and does not
  convert missing values into `"0"`.
- `DrPlanTargetPlacementResolverImpl` resolves KVM target disk type/format from
  storage type; RBD targets become `rbd/raw`.
- `DrProtectionOrchestratorImpl` blocks Agent dispatch when disk readiness is
  invalid and records plan/run failure consistently.
- `FtctlDrRuntimeProjectionAdapter` reconciles active accepted runs by
  `(planUuid, runUuid)` and maps terminal worker errors back to DB/API/UI.
- `DrResponseGenerator` exposes effective state from runtime projection so the
  UI does not show a stale accepted state as healthy.

Detailed method-level design:
`538-cross-hypervisor-dr-vmware-to-kvm-disk-size-and-projection-hardening-design-20260707.md`.

## 2026-07-07 Update: Backend Disk-first Storage Resolution

Backend storage resolution for guided DR Plan creation/edit follows
[539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md](539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md).

Required precedence:

1. `mapping.disks[].targetStorageRef`
2. `mapping.disks[].target.storageRef`
3. `mapping.target.storageRef`
4. guided `targetstorageref`

Affected backend code:

- `DrPlanGuidedSpecBuilder`
- `DrPlanTargetPlacementResolverImpl`
- `DrPlanReadinessValidator`
- `FtctlDrUnifiedActionAdapter`

Rules:

- `DrPlanGuidedSpecBuilder` writes disk-level storage without overwriting it
  with the default storage.
- `DrPlanTargetPlacementResolverImpl` resolves runtime storage fields from the
  effective disk storage.
- `DrPlanReadinessValidator` uses the same disk-first effective storage rule.
- `FtctlDrUnifiedActionAdapter` forwards backend-generated canonical
  `mapping.target` and `mapping.disks[]`; it does not infer target disk storage
  from the original API parameter.

This change is structural contract hardening. It does not require a new
orchestrator action or synchronous UI/API behavior.

## 2026-07-07 Update: Backend Impact Of DR Plan SharedFS Dialog Standard

The SharedFS-style DR Plan dialog standard is documented in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md).

Backend rules:

- Do not add backend persistence or runtime handling for UI collapse state.
- Continue to expose inventory, preview, readiness, and canonical JSON through
  the existing DR Plan APIs.
- Keep backend reason codes stable so the UI can map a field or blocking
  reason to a dialog section.
- If new readiness reasons are introduced later, update
  `ui/src/utils/dr/planDialogSections.js` together with the backend reason
  definition.
- Do not send layout metadata, display-only section titles, or review panel
  summaries from the backend.

The backend remains responsible for canonical plan data and execution
readiness; the UI remains responsible for how those errors are grouped and
displayed in the modal.

## 2026-07-07 Update: Backend Impact Of Modal Alert And Gutter Refinement

The modal alert/gutter refinement is a presentation-only correction documented
in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md#13-2026-07-07-refinement-dark-alert-and-right-gutter).

Backend design remains unchanged:

- keep existing DR Plan preview/readiness/guided spec generation;
- do not add alert severity or dialog layout metadata to response objects;
- do not change blocking reason codes for this visual fix;
- keep async action and run projection behavior unchanged.

Any future backend validation reason may still be mapped by UI section logic,
but the dark alert and gutter issue must not require backend changes.

## 2026-07-07 Update: VMware Data-Plane Backend Wiring

The VMware source VDDK libdir readiness design is defined in
[543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md](543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md).

Backend wiring additions:

- Add `DrVmwareDataPlaneResolver` to resolve the effective worker-host VDDK
  path and mover capability.
- Inject the resolver into `DrPlanReadinessValidator` and
  `FtctlDrUnifiedActionAdapter`.
- Extend `DrPlanReadinessValidator.validateForExecution()` with a VMware
  source gate before sync dispatch.
- Enrich `credentials.source` in the FTCTL runtime profile with
  `vddkLibdir`, `dataPlaneHostId`, and `dataPlaneHostUuid` when known.
- Persist the latest data-plane probe summary in `dr_site.capabilities_json`
  and copy runtime diagnostics into `dr_run.last_status_json` through the
  existing projection path.
- Keep vCenter credential validation in `DrVmwareDirectSiteProbe`; do not turn
  site health check into a long data-copy operation.

The backend must treat vCenter connection health and worker data-plane
readiness as different checks. A site may be `CONNECTED` while a specific DR
Plan is not execution-ready because its selected worker lacks a loadable VDDK
library.

## 2026-07-07 Update: Backend Projection For VMware Source Graph Failure

The VMware mover NBD source graph design is defined in
[544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md](544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md).

Backend changes are projection and message mapping only; no new orchestrator
action and no DB migration are required.

Affected backend code:

- `FtctlDrRuntimeProjectionAdapter`
- `DrResponseGenerator`
- `DrRunServiceImpl`
- DR event writer used by runtime projection

Rules:

- Treat `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` as terminal for the current run.
- Persist the terminal runtime JSON into `dr_run.last_status_json`.
- Set `dr_run.state=FAILED`, `projection_state=failed`, and
  `dr_plan.state=ERROR`.
- Upsert a failed `runtime-projection` step with the same error code.
- Do not mark the plan ready when only target storage exists.
- Do not retry automatically unless ftctl explicitly returns `retryable=true`.

This keeps Cloud/API/UI consistent with ftctl without making UI/API calls
synchronous.

## 2026-07-10 Normative Background Projection And View Cache Wiring

Introduce `DrProjectionScheduler`, `DrProjectionQueueService`,
`DrProtectionViewAssembler`, `DrProtectionViewCacheService`, and
`DrCompletedCheckpointProjector`. The scheduler reuses the existing managed
context, scheduled executor, and global-lock pattern used by DR site health
checks.

One Agent status answer updates Plan/Run/Replica/completed-checkpoint domain
rows and then rebuilds the redacted view snapshot. Read services perform DAO
reads only. Successful unchanged polls do not write events, and cache assembly
failure preserves the previous snapshot with a stale reason.

Detailed Spring wiring, locking, cadence, and transaction rules:
`550-cross-hypervisor-dr-protection-view-cache-and-completed-checkpoint-design-20260710.md`.

## 2026-07-14 Normative Read Consistency Update

No new Agent, FTCTL, or DB write path is introduced for live detail refresh.
`DrProjectionScheduler` continues to project active plans every 10 seconds and
`getDrProtectionView` continues to read the cache without `AgentManager.send`.
The UI may read that cache every 10 seconds for enabled plans, independently of
whether the latest operator run is terminal.

Async create/update jobs return their typed resource response only after the
resource transaction is committed. They must not wait for full seed or
incremental copy completion. Explicit operator Update uses the implemented
`refreshDrProtectionView` async command; successful unchanged cache reads do
not create events.

Detailed layer boundaries and preflight criteria:
`552-cross-hypervisor-dr-async-read-consistency-and-live-cache-ui-design-20260714.md`.

## 2026-07-14 Normative Continuous Sync Control Wiring

`DrPlanServiceImpl` must not derive Test Failover eligibility from target
materialization alone. A dedicated action-readiness service combines target
readiness with the cached FTCTL control protocol, scheduler/cycle state,
transition ownership, and projection freshness. It never calls Agent from a
list/get/API request thread.

`DrRunExecutorImpl` treats same-plan continuous Scheduler ownership as a
quiesce workflow, not generic retryable engine busy. Test Failover/control Run
failure is recorded on that Run and its steps/events while a healthy Plan and
latest completed checkpoint remain READY. Background projection stores typed
runtime control and action readiness in the existing protection-view cache.

Detailed classes, steps, and failure-impact rules:
`553-cross-hypervisor-dr-continuous-sync-control-and-quiesce-lock-design-20260714.md`.

## 2026-07-14 Cutover Backend Addendum

Backend에는 `DrCutoverPreparationService`와 Cloud-managed Test Failover
workflow를 둔다. Test Failover는 FTCTL의 `TEST_ARTIFACTS_READY` 이후 test
volume을 Cloud에 등록하고 임시 test VM을 정상 VM manager 경로로 생성·기동한다.
실제 Failover는 FTCTL의 `CUTOVER_READY` 이후 기존 target VM을 기동한다.
Boot validation 성공 전에 active side를 변경하지 않는다.

Guest preparation 실패는 Run failure이며 정상 continuous replication Plan을
자동으로 `ERROR`로 강등하지 않는다. 상세 클래스/상태/복구 계약:
`554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md`.

## 2026-07-16 Projection Failure Normalization Addendum

`FtctlDrRuntimeProjectionAdapter` must map FTCTL typed failure fields into a
bounded Plan/Run error and must not fall back to the complete `status.details`
document. The complete status is stored only as the latest runtime projection.
Projection of a locally durable checkpoint is idempotent so an interrupted
Cloud update can resume without recopying source data.

Copied-but-uncommitted data blocks target readiness and restore-point
publication. The backend may request metadata-only recovery only when the
FTCTL journal proves the copied data identity and target durability; otherwise
it schedules a safe reseed.

Detailed service and state transitions:
`557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md`.

## 2026-07-17 Dual Cycle Projection Addendum

Runtime projection independently upserts `currentCheckpointSequence` and
`latestCompletedCheckpointSequence`. Thus sequence N can become terminal even
when sequence N+1 has already started. Terminal metrics and generations are
monotonic, duplicate polls are idempotent, and READY restore-point evidence can
repair a missed non-terminal cycle without inventing incremental verification.
Readiness consumes this typed authority. Detailed transaction rules are in
`559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md`.

### 2026-07-19 Test Failover Backend Addendum

Add `DrTestFailoverService`, `DrTestVmLifecycleService`, and an idempotent
`DrTestFailoverReconciler`. FTCTL prepares artifacts; the backend imports them
as temporary Cloud volumes, creates a stopped temporary VM with the permanent
target hardware contract, attaches the selected Cloud network, starts it
through `UserVmManager`, and validates it through normal Cloud/Agent paths.
Cleanup removes Cloud resources before requesting FTCTL artifact/lease cleanup.

Detailed class boundaries and compensation logic:
`561-cross-hypervisor-dr-cloud-managed-test-failover-lifecycle-design-20260719.md`.

## 2026-07-27 Failback Late ACK Reconciler Wiring Addendum

`DrFailbackLifecycleServiceImpl`은 UI refresh에 종속된 executor 제출만 사용하지
않고 5초 주기의 scheduled reconciler를 소유한다.
`DrFailbackSessionDao.listReconcileCandidates()`가
`DATA_READY/COMMIT_VERIFYING/PROTECTION_RESUMING` session을 제한 조회하고,
Plan별 in-flight guard와 DB `lifecycle_version` CAS를 함께 적용한다.

Agent status wiring은 failback operation과 Plan protection authority를 별도
요청으로 전달한다. Backend는 operation ACK와 authority checkpoint를 검증한
뒤 `Transaction.execute`에서 Plan/Run/Session/Replica/cache를 terminal
수렴시킨다. UI refresh는 즉시 probe를 요청할 수 있지만 lifecycle 진행의
필수 조건이 아니다.

구체 클래스/method, retry, transaction 및 테스트 설계:
[576-cross-hypervisor-dr-failback-late-ack-and-projection-convergence-design-20260727.md](576-cross-hypervisor-dr-failback-late-ack-and-projection-convergence-design-20260727.md).

## 2026-07-28 Authority Resolver Addendum

`DrResponseGenerator`는 더 이상 cutover DAO를 직접 조회하거나 현재 보호
단계를 판정하지 않는다. `DrCurrentAuthorityResolver`가 Plan active side,
current cutover session, protection authority snapshot을 결합하고,
`DrPlanActionEligibilityEvaluator`가 이 projection을 기준으로 작업 가능
여부를 계산한다.

Failback terminal transaction은 성공한 과거 cutover session을
`FAILED_BACK`으로 종결하고 lifecycle Run Step, Plan, Run, Failback Session,
Replica, cache를 같은 transaction 경계에서 수렴시킨다. 상세 클래스와
메서드 계약은 문서 578을 따른다.

## 2026-07-30 Current Runtime And Historical Run Addendum

`DrResponseGenerator`는 `findLatestByPlanId()` 결과를 current runtime으로
사용하지 않는다. current runtime은 `DrProtectionAuthoritySnapshot`의
`dr_plan_runtime.status_json`을 우선 사용하고, authority가 없을 때만 active
Run을 fallback으로 사용한다. latest operation Run은 `lastRun` 이력 응답에만
직렬화한다.

Protection View는 snapshot version 5에서 `planProjection`,
`currentProtectionRuntime`, `activeRun`, `latestOperationRun`의 의미를
분리한다. version 4 cache는 조회 시 자동 rebuild하며 DB schema migration은
필요하지 않다. 상세 helper, cache 및 테스트 계약은
[580-cross-hypervisor-dr-current-runtime-history-and-darkmode-warning-design-20260730.md](580-cross-hypervisor-dr-current-runtime-history-and-darkmode-warning-design-20260730.md)를
따른다.

## 2026-07-30 Post-Failover Commit Boundary Addendum

Cloud-owned target promotion은 DB transaction A, Agent `CUTOVER_COMMIT`,
DB transaction B로 분리한다. ACK 이후 transaction B가 Plan, Runtime,
Cutover Session, Replica, Cutover Disk를 같은 authority generation으로
수렴시킨다. 외부 Agent 호출을 DB transaction 안에 두지 않는다. 상세 클래스,
helper 및 retry 계약은 문서 581을 따른다.
## 2026-07-30 Failback Transition And Resume Evidence Addendum

Failback terminal predicate는 operation JSON 하나에 모든 필드가 있다고 가정하지
않는다. Plan active side, Failback Session ACK/commit, Cloud VM 전원 상태,
`PLAN_AUTHORITY` scheduler/checkpoint를 typed evidence로 합성한다. active
Failback Session의 `COMMIT_VERIFYING/PROTECTION_RESUMING`은 인식된 정상 권한
전환이며 severity INFO다. 상세 service, resolver, transaction 계약은 문서 583을
따른다.

## 2026-07-30 Action Availability Evaluator Addendum

`DrPlanServiceImpl`의 boolean 조건식과 UI의 authority guard를 단일
`DrPlanActionAvailabilityEvaluator`로 수렴한다. evaluator는 current authority,
active Run, scheduler/runtime, readiness, test session, release readiness를
한 Cloud projection에서 읽고 `applicable/enabled/reasonCode`를 계산한다.
목록 또는 상세 조회 중 Agent/FTCTL을 동기 호출하지 않는다.

기존 `getActionEligibility()`는 evaluator 결과의 `enabled` map으로
호환 변환한다. action command의 서버 측 실행 검증, `DrPlanResponse`,
Protection View cache도 같은 evaluator를 사용한다. 상세 class, method,
cache version 및 test matrix는
[584-cross-hypervisor-dr-context-action-availability-and-darkmode-design-20260730.md](584-cross-hypervisor-dr-context-action-availability-and-darkmode-design-20260730.md)를
따른다.

## 2026-08-06 Failback Evidence Reconciliation Addendum

After FTCTL reports Data Ready, backend wiring persists one typed
`DrFailbackDataEvidence` tuple before it invokes the Cloud lifecycle gate. A
temporarily incomplete publication enters `DATA_EVIDENCE_PENDING` and is
re-probed by the scheduled reconciler without blocking the API thread. Null
evidence, explicit non-durability, and inconsistent lineage use separate error
codes. Target shutdown is unreachable from the pending state. See document 596.

## 2026-07-31 Test Session Lifecycle Policy Addendum

`DrPlanServiceImpl`, `DrPlanActionAvailabilityEvaluator`,
`DrOrchestratorImpl`은 Test Session 상태를 각각 계산하지 않는다.
신규 `DrTestSessionLifecyclePolicy/Service`가 open session, cleanup obligation,
blocking 여부와 terminal soft-close를 단일 판정한다.

`FAILED + cleanup_required=false`는 terminal proof 확인 후 종결하며,
`CLEANUP_FAILED`와 cleanup obligation이 있는 실패 세션은 새 테스트를
차단한다. transaction, Plan lock, reconcile 진입점은
[586-cross-hypervisor-dr-test-session-blocker-and-async-acceptance-design-20260731.md](586-cross-hypervisor-dr-test-session-blocker-and-async-acceptance-design-20260731.md)를
따른다.
