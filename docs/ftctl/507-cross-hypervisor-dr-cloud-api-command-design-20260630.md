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

# Cross Hypervisor DR Cloud API Command Design

> 2026-08-06 forward cutover commit correction: document
> [599](599-cross-hypervisor-dr-forward-cutover-commit-contract-and-authority-convergence-design-20260806.md)
> keeps Failover asynchronous, exposes the engine-ACK-pending phase explicitly,
> and forbids a terminal TARGET response before durable FTCTL acknowledgement.

> 2026-08-05 Failback route and terminal-convergence correction: document
> [595](595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md)
> separates topology direction from provider pair and requires list/detail
> APIs to expose one canonical Run terminal after a Cloud lifecycle failure.

> 2026-08-05 live-worker correction: document
> [594](594-cross-hypervisor-dr-live-worker-and-terminal-reconciliation-design-20260805.md)
> adds non-terminal reconciliation fields and blocks duplicate actions while an
> engine transfer remains live.

> 2026-08-04 normative live-runtime preflight API update:
> [592-cross-hypervisor-dr-failback-live-runtime-preflight-and-ux-convergence-design-20260804.md](592-cross-hypervisor-dr-failback-live-runtime-preflight-and-ux-convergence-design-20260804.md)
> adds async refresh plus cached read, stage responses, nullable engine
> readiness, runtime-drift evidence, and actual reverse mode/probe fields.
>
> 2026-08-04 normative Failback preflight update:
> Document 591 revision 2 adds reverse baseline/mode/disk/writer evidence to
> `getDrFailbackPreflight` while keeping `startDrFailback` start-only.
>
> 2026-08-03 normative mutation-response update:
> [590-cross-hypervisor-dr-plan-async-mutation-and-target-resource-ownership-design-20260803.md](590-cross-hypervisor-dr-plan-async-mutation-and-target-resource-ownership-design-20260803.md)
> separates primitive-only create/update mutation responses from full DR plan
> read projections. Earlier text that returns full `DrPlanResponse` from an
> async mutation is superseded.

> 2026-07-31 latest correction: `confirmDrFenceClear` is legacy FTCTL
> compatibility only. FTCTL_DR rejects it before Run creation. See document 587.

작성일: 2026-06-30

대상 브랜치: `feature/ftctl-cloud-integration`

상위 문서:

- [500-cross-hypervisor-dr-architecture-plan-20260630.md](500-cross-hypervisor-dr-architecture-plan-20260630.md)
- [501-cross-hypervisor-dr-domain-schema-design-20260630.md](501-cross-hypervisor-dr-domain-schema-design-20260630.md)
- [502-cross-hypervisor-dr-adapter-contract-design-20260630.md](502-cross-hypervisor-dr-adapter-contract-design-20260630.md)
- [503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md](503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md)
- [506-cross-hypervisor-dr-cloud-ui-design-20260630.md](506-cross-hypervisor-dr-cloud-ui-design-20260630.md)
- [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)

## 1. 목적

이 문서는 `Cross Hypervisor DR`의 Cloud API command, response, 권한, async job, 오류 응답 규칙을 구현 가능한 수준으로 정의한다.

기존 문서의 API 후보 목록은 방향성을 설명하기에 충분하지만, 구현자가 `Cmd`, `Response`, service method, UI polling을 바로 만들기에는 부족하다. 이 문서는 그 빈틈을 메운다.

## 2. API 배치 원칙

신규 API는 기존 `disaster-recovery` integration plugin 아래에 둔다.

권장 package:

| 용도 | package |
| --- | --- |
| command | `org.apache.cloudstack.api.command.admin.dr` |
| response | `org.apache.cloudstack.api.response.dr` |
| service interface | `com.cloud.dr` |
| service impl | `com.cloud.dr.orchestrator` 또는 `com.cloud.dr.plan` |

초기 범위는 `RoleType.Admin` 전용으로 둔다. `dr_plan.account_id`, `domain_id`는 향후 account/user API 확장을 위해 저장하지만, Phase 1 public command는 admin command로 시작한다.

API command는 다음 규칙을 따른다.

- 조회 API는 `BaseListCmd` 또는 `BaseCmd`를 사용한다.
- 상태 변경 API는 `BaseAsyncCmd`를 사용한다.
- 장시간 data mover가 필요한 작업은 `DrRun`을 만들고 `jobid`와 `runid`를 모두 UI에 노출한다.
- Cloud async job 성공은 "요청 접수"만 의미해서는 안 된다. 작업을 background `DrRun`으로 넘기는 경우 response에 `runstate`와 `accepted=true`를 명확히 표시한다.
- 작업이 API command 실행 중 실패하면 async job을 실패로 끝내고 `DrRun.state=FAILED`를 남긴다.
- 작업이 command 이후 별도 worker에서 실패하면 `getDrRun`과 `listDrEvents`에서 실패가 반드시 보인다.

## 3. 공통 API resource와 event type

구현 시 `ApiCommandResourceType`에 신규 값을 추가하는 것을 권장한다.

| Resource | 사용처 |
| --- | --- |
| `DrSite` | site create/update/delete/check |
| `DrPlan` | plan create/update/delete/action |
| `DrRun` | run cancel/manual-confirm/progress |
| `DrReplica` | replica adopt/test/cleanup |
| `DrRestorePoint` | restore point 조회/선택 |

기존 enum 추가가 부담스럽거나 상위 API 호환 문제가 있으면 Phase 1에서는 `ApiCommandResourceType.DisasterRecoveryCluster`를 재사용할 수 있다. 단, event description에는 신규 entity type과 uuid를 반드시 포함한다.

신규 event type은 `com.cloud.dr.cluster.DisasterRecoveryClusterEventTypes`를 확장하거나 별도 `CrossHypervisorDrEventTypes`로 분리한다.

| Event type | 발생 command |
| --- | --- |
| `EVENT_DR_SITE_CREATE` | `createDrSite` |
| `EVENT_DR_SITE_UPDATE` | `updateDrSite`, `checkDrSite` |
| `EVENT_DR_SITE_DELETE` | `deleteDrSite` |
| `EVENT_DR_PLAN_CREATE` | `createDrPlan` |
| `EVENT_DR_PLAN_UPDATE` | `updateDrPlan`, `enableDrPlan`, `disableDrPlan` |
| `EVENT_DR_PLAN_DELETE` | `deleteDrPlan` |
| `EVENT_DR_PLAN_SYNC` | `startDrSync` |
| `EVENT_DR_PLAN_TEST_FAILOVER` | `startDrTestFailover`, `stopDrTestFailover` |
| `EVENT_DR_PLAN_FAILOVER` | `startDrFailover`, `adoptDrReplica` |
| `EVENT_DR_PLAN_FAILBACK` | `startDrFailback` |
| `EVENT_DR_PLAN_REPROTECT` | `startDrReprotect` |
| `EVENT_DR_RUN_CANCEL` | `cancelDrRun` |
| `EVENT_DR_FENCE_CONFIRM` | `confirmDrFenceClear` |

## 4. Command class 목록

### 4.1 DrSite commands

| API | Command class | Base class | Response | 설명 |
| --- | --- | --- | --- | --- |
| `createDrSite` | `CreateDrSiteCmd` | `BaseAsyncCmd` | `DrSiteResponse` | DR endpoint 등록 |
| `listDrSites` | `ListDrSitesCmd` | `BaseListCmd` | `ListResponse<DrSiteResponse>` | site 목록/필터 |
| `getDrSite` | `GetDrSiteCmd` | `BaseCmd` | `DrSiteResponse` | 단일 site 상세 |
| `updateDrSite` | `UpdateDrSiteCmd` | `BaseAsyncCmd` | `DrSiteResponse` | endpoint, mapping, capability 갱신 |
| `updateDrSiteCredential` | `UpdateDrSiteCredentialCmd` | `BaseAsyncCmd` | `DrSiteResponse` | Mold/vCenter 인증정보 갱신 |
| `clearDrSiteCredential` | `ClearDrSiteCredentialCmd` | `BaseAsyncCmd` | `DrSiteResponse` | 저장된 인증정보 삭제 |
| `deleteDrSite` | `DeleteDrSiteCmd` | `BaseAsyncCmd` | `SuccessResponse` 또는 `DrSiteResponse` | site soft delete |
| `checkDrSite` | `CheckDrSiteCmd` | `BaseAsyncCmd` | `DrSiteCheckResponse` | connectivity/capability preflight |

`createDrSite` parameters:

| Parameter | Required | 설명 |
| --- | --- | --- |
| `name` | yes | site 이름. active site 이름 중복 금지 |
| `description` | no | 설명 |
| `siteType` | yes | `MOLD_KVM`, `MOLD_VMWARE`, `VMWARE_DIRECT` |
| `hypervisorType` | yes | `KVM`, `VMWARE` |
| `endpoint` | conditional | remote Mold URL 또는 vCenter URL |
| `zoneId` | no | local Cloud internal Zone id. 하위 호환/로컬 참조 전용 |
| `zoneExternalId` | conditional | remote Mold/vCenter Zone id 또는 uuid |
| `zoneName` | no | remote Zone 표시 이름 |
| `vmwareDcId` | no | local VMware datacenter internal id. 하위 호환/로컬 참조 전용 |
| `vmwareDcExternalId` | conditional | remote VMware datacenter id, uuid 또는 MoRef |
| `vmwareDcName` | no | remote VMware datacenter 표시 이름 |
| `certificatePolicy` | no | `STRICT`, `TRUST_ON_FIRST_USE`, `INSECURE_ALLOWED` |
| `capabilityJson` | no | 사전 수집 capability snapshot |

Credential parameters는 사용자 입력을 그대로 reference로 저장하지 않고 write-only로 처리한다.

| Parameter | Required | 설명 |
| --- | --- | --- |
| `moldApiUrl` | conditional | Mold site 접속 API URL |
| `moldApiKey` | conditional | Mold API Key. response/log 노출 금지 |
| `moldSecretKey` | conditional | Mold Secret Key. response/log 노출 금지 |
| `vcenterUrl` | conditional | vCenter 접속 URL |
| `vcenterUsername` | conditional | vCenter username |
| `vcenterPassword` | conditional | vCenter password. response/log 노출 금지 |
| `tlsVerify` | no | TLS certificate 검증 여부 |

`credentialRef`는 legacy 호환 파라미터로만 남기며 신규 UI는 전송하지 않는다. 신규 backend는 인증정보를 `dr_site_credential`에 암호화 저장하고 `dr_site.credential_id`를 갱신한다.

`checkDrSite` result는 `DrSite.state`를 무조건 바꾸지 않는다. check가 성공하면 site health 관련 필드를 갱신하고, 명시적 `persistStatus=true`가 있을 때만 `health_state`, `last_checked`, health summary를 저장한다.

#### 4.1.1 2026-07-02 site health check 보강

`checkDrSite`의 세부 구현은 [528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md](528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md)를 따른다.

현재 구현처럼 `lastChecked`만 갱신하는 방식은 허용하지 않는다. `CheckDrSiteCmd`는 `persiststatus` parameter를 `DrSiteService.checkSite(siteId, persistStatus)`로 전달해야 하며, backend는 저장 credential을 사용해 Mold/vCenter endpoint를 실제로 검증해야 한다.

Mold/ABLESTACK site의 `MOLD_API` credential 검증은 CloudStack API signature 규칙을 따른다. `DrMoldSiteProbe`는 `listCapabilities` 요청을 signed GET으로 호출하며, 서명 알고리즘은 backend 고정값 `HmacSHA256`이다. UI/API는 `signatureAlgorithm` 같은 입력값을 받지 않는다. 세부 코드 설계는 [528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md](528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md)의 `2026-07-03 ABLESTACK/Mold API 서명 알고리즘 보강` 절을 따른다.

응답 field는 기존 `DrSiteResponse` 호환을 유지하면서 다음 optional field를 추가한다.

| Field | 설명 |
| --- | --- |
| `healthstate` | `CONNECTED`, `DEGRADED`, `DISCONNECTED`, `UNKNOWN` |
| `healthreasoncode` | `CREDENTIAL_MISSING`, `CREDENTIAL_INVALID` 등 |
| `healthmessage` | 사용자 표시용 짧은 메시지. secret 포함 금지 |
| `healthlatencyms` | probe 소요 시간 |
| `credentialconfigured` | `state=CONFIGURED`이고 `removed IS NULL`인 usable credential 존재 여부 |
| `credentialstate` | latest credential state 또는 `MISSING`/`LEGACY_REF` |

`persiststatus=false`이면 probe는 수행하지만 DB `health_state`, `last_checked`, credential `last_validated`는 변경하지 않는다.

`checkDrSite` async job 성공은 API command 실행 성공을 의미한다. 실제 endpoint 인증 실패는 job 실패로 숨기지 않고 `DrSiteResponse.healthstate=DISCONNECTED`, `healthreasoncode=CREDENTIAL_INVALID`, `healthmessage=Mold API authentication failed with HTTP 401`처럼 health field에 표현한다. UI는 job 성공 후 반드시 site response 또는 refresh 결과의 health field를 확인해야 한다.

#### 4.1.2 UI wrapper contract

`ui/src/api/dr.js`는 DR Site CRUD와 check를 모두 노출한다. UI 화면은 내부 `credential_id`나 legacy `credentialref`를 직접 다루지 않는다.

```js
const objectKeys = {
  createDrSite: ['createdrsiteresponse', 'drsite'],
  updateDrSite: ['updatedrsiteresponse', 'drsite'],
  checkDrSite: ['checkdrsiteresponse', 'drsite']
}

function extractJobId (response, responseKey) {
  return response?.[responseKey]?.jobid || response?.jobid
}

export function updateDrSite (id, params) {
  return postAPI('updateDrSite', { id, ...params })
    .then(response => extractDrObject(response, 'updateDrSite'))
}

export function deleteDrSite (id) {
  return postAPI('deleteDrSite', { id })
    .then(response => ({
      jobid: extractJobId(response, 'deletedrsiteresponse'),
      raw: response
    }))
}
```

`deleteDrSite`와 `deleteDrPlan`은 `BaseAsyncCmd`이므로 UI wrapper는 `success` object를 최종 성공으로 취급하지 않는다. 응답에서 `jobid`를 추출하고 `$pollJob` 또는 `queryAsyncJobResult`로 최종 상태를 확인한 뒤 목록/상세를 갱신한다. job이 실패하면 async job의 `errortext`를 사용자에게 표시한다.

`updateDrSite` 요청 규칙:

- 이름, 설명, site type, endpoint, Zone, VMware datacenter 같은 site metadata는 부분 갱신을 허용한다.
- secret 입력값이 모두 비어 있으면 인증정보는 유지한다.
- Mold 또는 vCenter credential 필드가 새로 입력되면 backend는 새 credential row를 만들고 기존 row는 soft delete한다.
- `clearCredential=true`는 별도 확인이 필요한 destructive 옵션으로만 보낸다.

`deleteDrSite` 요청 규칙:

- 연결된 active `DrPlan`이 있으면 실패해야 한다.
- 실패 response는 `DR_SITE_IN_USE` 또는 이에 준하는 명확한 code/message를 반환한다.
- 삭제는 credential soft delete와 site soft delete를 함께 수행한다.
- backend는 `markRemoved()+update()`를 사용하지 않고 `drSiteDao.remove(siteId)`, `drSiteCredentialDao.remove(credentialId)`로 `removed`를 저장한다.
- delete job 성공 조건은 `findByIdIncludingRemoved(siteId).removed != null` 검증까지 통과하는 것이다.
- 삭제된 site에는 이후 `checkDrSite`와 scheduler health check가 수행되면 안 된다.

### 4.2 DrPlan commands

| API | Command class | Base class | Response | 설명 |
| --- | --- | --- | --- | --- |
| `createDrPlan` | `CreateDrPlanCmd` | `BaseAsyncCmd` | `DrPlanResponse` | VM 보호 계획 생성 |
| `listDrPlans` | `ListDrPlansCmd` | `BaseListCmd` | `ListResponse<DrPlanResponse>` | plan 목록/필터 |
| `getDrPlan` | `GetDrPlanCmd` | `BaseCmd` | `DrPlanResponse` | 단일 plan 상세 |
| `updateDrPlan` | `UpdateDrPlanCmd` | `BaseAsyncCmd` | `DrPlanResponse` | mapping/policy 수정 |
| `enableDrPlan` | `EnableDrPlanCmd` | `BaseAsyncCmd` | `DrPlanResponse` | 보호 활성화 |
| `disableDrPlan` | `DisableDrPlanCmd` | `BaseAsyncCmd` | `DrPlanResponse` | 보호 비활성화 |
| `deleteDrPlan` | `DeleteDrPlanCmd` | `BaseAsyncCmd` | `DrPlanResponse` | plan 제거 및 선택 cleanup |

`createDrPlan` parameters:

| Parameter | Required | 설명 |
| --- | --- | --- |
| `name` | yes | plan 이름 |
| `sourceSiteId` | yes | source site uuid |
| `targetSiteId` | yes | target site uuid |
| `sourceVmId` | conditional | Mold/KVM source VM uuid |
| `sourceExternalRef` | conditional | VMware source MoRef 등 |
| `direction` | yes | `KVM_TO_KVM`, `KVM_TO_VMWARE`, `VMWARE_TO_VMWARE`, `VMWARE_TO_KVM` |
| `rpoSeconds` | no | 목표 RPO |
| `rtoSeconds` | no | 목표 RTO |
| `scheduleJson` | no | sync schedule |
| `storageMappingJson` | conditional | disk/datastore/pool mapping |
| `networkMappingJson` | conditional | NIC/network/portgroup mapping |
| `computeMappingJson` | no | resource pool/host/service offering mapping |
| `fencingPolicyJson` | no | manual/automatic fencing policy |
| `quiescePolicyJson` | no | QGA/VMware Tools consistency policy |
| `enable` | no | 생성 후 즉시 enable |
| `dryRun` | no | preflight만 수행 |
| `idempotencyKey` | no | retry 중복 방지 |

2026-07-05 이후 기본 UI/API 경로에서는 `scheduleJson`, `storageMappingJson`, `networkMappingJson`, `computeMappingJson`, `fencingPolicyJson`, `quiescePolicyJson`을 사용자가 직접 작성한 raw JSON으로 받지 않는다. 이 field들은 backward compatibility와 expert override 용도로만 유지한다. 일반 생성/수정 요청은 `targetvmname`, `targetstorageoption`, `targetcomputeoption`, `targetnetworkoption`, `syncintervalseconds`, `retentioncount`, `consistencymode`, `testnetworkmode`, `failoverpoweron`, `bandwidthlimitmbps`, `retrycount` 같은 typed parameter를 받고, backend가 canonical `mapping_json`, `schedule_json`, `policy_json`, `quiesce_policy_json`을 생성한다.

2026-07-06 이후 `VMWARE_TO_KVM` 일반 생성/수정 요청은 위 typed parameter를 더 구체화한다. `targetcomputeoption`은 KVM target에서 service offering을 의미하며, worker host와 혼용하지 않는다. `discoverDrPlanInventory`는 `sourceexternalref`, `sourcevmid`, `includeplacement`, `includedisks`, `includenetworks`를 받아 source disk/NIC와 target service offering, disk offering, network, worker, storage 후보를 함께 반환해야 한다. 후보가 없으면 API는 빈 목록만 반환하지 않고 `blockingreasons`에 `TARGET_SITE_ZONE_REQUIRED`, `TARGET_STORAGE_REQUIRED`, `TARGET_NETWORK_REQUIRED`, `TARGET_SERVICE_OFFERING_REQUIRED`, `SOURCE_DISK_INVENTORY_REQUIRED` 같은 원인을 포함한다. 상세 설계는 [531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md](531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md)의 15장을 따른다.

추가 typed parameter:

| Parameter | Required | 설명 |
| --- | --- | --- |
| `targetVmName` | conditional | target replica VM 표시명. target resource 생성 경로에서 필요 |
| `targetStorageOption` | conditional | target datastore/storage pool/disk offering option reference |
| `targetComputeOption` | conditional | target resource pool/cluster/host/service offering option reference |
| `targetNetworkOption` | conditional | target network/portgroup option reference |
| `syncIntervalSeconds` | no | RPO 기반 동기화 주기. 지정하지 않으면 `rpoSeconds` 사용 |
| `retentionCount` | no | target-ready restore point 보존 개수 |
| `consistencyMode` | no | `CRASH_CONSISTENT`, `GUEST_QUIESCE`, `APPLICATION_CONSISTENT` |
| `testNetworkMode` | no | test failover network mode. 기본 `ISOLATED` |
| `failoverPowerOn` | no | failover 후 target VM 전원 on 여부 |
| `bandwidthLimitMbps` | no | transport bandwidth 제한. 0 또는 null은 제한 없음 |
| `retryCount` | no | engine action retry 횟수 |

필수 validation:

- source와 target site가 모두 active여야 한다.
- direction과 site type 조합이 `DrAdapterRegistry`에서 지원되어야 한다.
- 동일 source VM에 active `DrPlan` 또는 active `ftctl_protection`이 있으면 기본적으로 거부한다.
- `KVM_TO_KVM`에서 기존 FTCTL 보호를 연결하는 경우 `importExistingFtctlProtection=true` 같은 명시 parameter가 있어야 한다.
- raw mapping JSON은 문자열로 받더라도 expert/backward compatibility 경로에서만 허용하며, backend에서 구조 검증을 수행해야 한다.
- 기본 경로에서는 `DrPlanSpecBuilder`가 생성한 mapping/schedule/policy/quiesce spec을 방향별 validator가 의미 검증해야 한다.

#### 4.2.0 DR Plan spec preview API

DR Plan 생성 전에 UI가 backend-generated spec과 preflight warning을 확인할 수 있도록 `previewDrPlanSpec` async API를 추가한다.

| 항목 | 값 |
| --- | --- |
| API name | `previewDrPlanSpec` |
| Command class | `PreviewDrPlanSpecCmd` |
| Service | `DrPlanSpecService.preview` |
| Response | `DrPlanSpecPreviewResponse` |
| 용도 | DB insert 없이 typed 입력값을 canonical JSON과 검증 결과로 변환 |

`previewDrPlanSpec` response:

| Field | 설명 |
| --- | --- |
| `enginetype` | backend가 결정한 engine |
| `mappingjson` | backend-generated canonical mapping JSON |
| `schedulejson` | backend-generated canonical schedule JSON |
| `policyjson` | backend-generated canonical policy JSON |
| `quiescepolicyjson` | backend-generated canonical quiesce policy JSON |
| `sourceworkerhostid` | 자동 선택 또는 사용자가 선택한 source worker |
| `targetworkerhostid` | 자동 선택 또는 사용자가 선택한 target worker |
| `coordinatorworkerhostid` | 자동 선택 또는 사용자가 선택한 coordinator |
| `warnings` | 진행 가능하지만 확인이 필요한 항목 |
| `blockers` | 생성/수정 전 반드시 해결해야 하는 항목 |

`listDrPlans` filters:

| Filter | 설명 |
| --- | --- |
| `id` | plan uuid |
| `name` | 이름 like 검색 |
| `sourceSiteId` | source site uuid |
| `targetSiteId` | target site uuid |
| `sourceVmId` | Cloud VM uuid |
| `direction` | plan direction |
| `state` | plan state |
| `replicaState` | latest replica state |
| `targetReady` | latest target-ready restore point 존재 여부 |
| `rpoExceeded` | target-ready RPO threshold 초과 |
| `engineBindingType` | `FTCTL`, `V2K`, `VMWARE_NATIVE`, `KVM_SNAPSHOT` |
| `page`, `pagesize` | `BaseListCmd` 표준 pagination |

#### 4.2.1 UI wrapper contract

`ui/src/api/dr.js`는 plan 수정/삭제와 runtime action을 구분해서 제공한다.

```js
const objectKeys = {
  createDrPlan: ['createdrplanresponse', 'drplan'],
  updateDrPlan: ['updatedrplanresponse', 'drplan'],
  startDrSync: ['startdrsyncresponse', 'drrun']
}

export function updateDrPlan (id, params) {
  return postAPI('updateDrPlan', { id, ...params })
    .then(response => extractDrObject(response, 'updateDrPlan'))
}

export function deleteDrPlan (id) {
  return postAPI('deleteDrPlan', { id })
    .then(response => ({
      jobid: extractJobId(response, 'deletedrplanresponse'),
      raw: response
    }))
}

export function startDrAction (command, params) {
  return postAPI(command, params).then(response => extractDrObject(response, command))
}
```

`updateDrPlan` 요청 규칙:

- `name`, `description`, `rpoSeconds`, `rtoSeconds`, typed schedule/policy/quiesce/target mapping field, worker host 선택값은 수정 가능하다. `scheduleJson`, `policyJson`, `mappingJson`, `quiescePolicyJson` raw field는 expert/backward compatibility 경로에서만 수정 가능하다.
- `sourceSiteId`, `targetSiteId`, `direction`, `sourceVmId`, `sourceExternalRef`, `engineType`, `engineBindingType`, `engineBindingId`는 plan이 `NEW/CREATED`이고 active run/protection이 없을 때만 수정 가능하다.
- active run이 있으면 `DR_ACTIVE_RUN_EXISTS`로 실패해야 한다.
- 이미 FTCTL/VMware runtime resource가 생성된 plan에서 topology 변경이 필요하면 delete/recreate 또는 release 후 recreate 흐름으로 유도한다.

`deleteDrPlan` 요청 규칙:

- active run이 있으면 실패한다.
- active protection, replica, restore point, transport resource가 남아 있으면 단순 삭제 대신 `releaseDrProtection` 또는 cleanup action을 먼저 수행하도록 실패해야 한다.
- 삭제 성공은 plan soft delete이며 장시간 engine cleanup을 암묵적으로 동기 수행하지 않는다.

### 4.3 Action commands

| API | Command class | Base class | Run type | Response |
| --- | --- | --- | --- | --- |
| `startDrSync` | `StartDrSyncCmd` | `BaseAsyncCmd` | `SYNC` | `DrRunResponse` |
| `startDrTestFailover` | `StartDrTestFailoverCmd` | `BaseAsyncCmd` | `TEST_FAILOVER` | `DrRunResponse` |
| `stopDrTestFailover` | `StopDrTestFailoverCmd` | `BaseAsyncCmd` | `TEST_CLEANUP` | `DrRunResponse` |
| `startDrFailover` | `StartDrFailoverCmd` | `BaseAsyncCmd` | `FAILOVER` | `DrRunResponse` |
| `confirmDrFenceClear` | `ConfirmDrFenceClearCmd` | `BaseAsyncCmd` | `FENCE_CONFIRM` | `DrRunResponse` |
| `startDrFailback` | `StartDrFailbackCmd` | `BaseAsyncCmd` | `FAILBACK` | `DrRunResponse` |
| `startDrReprotect` | `StartDrReprotectCmd` | `BaseAsyncCmd` | `REPROTECT` | `DrRunResponse` |
| `adoptDrReplica` | `AdoptDrReplicaCmd` | `BaseAsyncCmd` | `ADOPT` | `DrRunResponse` |
| `cancelDrRun` | `CancelDrRunCmd` | `BaseAsyncCmd` | existing run | `DrRunResponse` |

공통 action parameters:

| Parameter | Required | 설명 |
| --- | --- | --- |
| `planId` | yes | plan uuid |
| `restorePointId` | conditional | failover/test 대상 restore point |
| `replicaId` | conditional | adopt/test cleanup 대상 replica |
| `idempotencyKey` | no | 중복 실행 방지 |
| `dryRun` | no | 실행 대신 preflight |
| `force` | no | forced cleanup/adopt 등 위험 액션 |
| `acknowledgement` | conditional | 위험 액션 확인 문구 |
| `reason` | no | operator가 남긴 실행 사유 |

위험 action은 `force=true`만으로 실행하지 않는다. `acknowledgement`가 action별 expected phrase와 일치해야 한다.

`startDrFailover` 추가 parameters:

| Parameter | 설명 |
| --- | --- |
| `disaster` | source controller가 제어 불가한 재해 전환 여부 |
| `skipSourceFenceRequest` | pre-fenced 상태일 때만 허용 |
| `testNetworkOnly` | production network 연결 없이 검증 |

`startDrFailback`은 source-controller failback 전용이다. source controller가 unavailable이면 `adoptDrReplica` 또는 `startDrFailover(disaster=true)`로 분리한다.

### 4.4 Query commands

| API | Command class | Base class | Response |
| --- | --- | --- | --- |
| `listDrRestorePoints` | `ListDrRestorePointsCmd` | `BaseListCmd` | `ListResponse<DrRestorePointResponse>` |
| `listDrReplicas` | `ListDrReplicasCmd` | `BaseListCmd` | `ListResponse<DrReplicaResponse>` |
| `listDrRuns` | `ListDrRunsCmd` | `BaseListCmd` | `ListResponse<DrRunResponse>` |
| `getDrRun` | `GetDrRunCmd` | `BaseCmd` | `DrRunResponse` |
| `listDrRunSteps` | `ListDrRunStepsCmd` | `BaseListCmd` | `ListResponse<DrRunStepResponse>` |
| `listDrEvents` | `ListDrEventsCmd` | `BaseListCmd` | `ListResponse<DrEventResponse>` |

`listDrEvents` filters:

- `planId`
- `runId`
- `severity`
- `source`
- `since`
- `until`
- `page`
- `pagesize`

`listDrEvents`는 qemu/FTCTL raw event 전체를 무제한 반환하지 않는다. response는 summary 중심이며 raw payload는 `includeDetails=true`일 때만 반환한다.

## 5. Response object 설계

### 5.1 DrSiteResponse

필수 field:

- `id`
- `name`
- `description`
- `siteType`
- `hypervisorType`
- `endpoint`
- `zoneId`
- `vmwareDcId`
- `status`
- `lastCheckResult`
- `lastCheckMessage`
- `lastCheckTime`
- `capabilities`
- `created`
- `removed`

표시 금지:

- password
- secret key
- API key 원문
- private key 원문
- vCenter session token

credential은 `credentialState`, `credentialType`, `credentialEndpoint`, `credentialPrincipal`, `credentialLastUpdated`, `credentialLastValidated` 정도만 표시한다. secret 원문, API key 원문, password 원문, 내부 `credential_id`는 표시하지 않는다.

### 5.2 DrPlanResponse

필수 field:

- `id`
- `name`
- `description`
- `state`
- `direction`
- `sourceSite`
- `targetSite`
- `sourceVmId`
- `sourceVmName`
- `sourceExternalRef`
- `rpoSeconds`
- `rtoSeconds`
- `sourceLatestRestorePoint`
- `targetReadyRestorePoint`
- `sourceRpoAgeSeconds`
- `targetReadyRpoAgeSeconds`
- `replica`
- `currentRun`
- `lastRun`
- `actionEligibility`
- `engineBindingType`
- `engineBindingId`
- `created`
- `removed`

`actionEligibility`는 UI가 임의로 상태 조합을 재해석하지 않도록 backend가 제공한다.

`actionEligibility`에는 runtime action뿐 아니라 `update`, `delete` 가능 여부도 포함할 수 있다. UI는 이를 작업 메뉴 disabled reason으로 사용하되 backend 검증을 대체하지 않는다.

예시:

```json
{
  "failover": {
    "enabled": false,
    "reasonCode": "DR_TARGET_NOT_READY",
    "message": "No target-ready restore point exists."
  }
}
```

### 5.3 DrRunResponse

필수 field:

- `id`
- `planId`
- `runType`
- `state`
- `requestedBy`
- `idempotencyKey`
- `externalJobRef`
- `currentStep`
- `progressPercent`
- `progressMessage`
- `errorCode`
- `errorMessage`
- `retryable`
- `created`
- `started`
- `finished`
- `steps`

Cloud async job id와 external engine job id는 분리한다.

| Field | 의미 |
| --- | --- |
| Cloud `jobid` | CloudStack API async job id |
| `DrRun.id` | DR orchestrator 실행 id |
| `externalJobRef` | VMware task MoRef, V2K workdir, FTCTL event ref 등 |

### 5.4 DrReplicaResponse

필수 field:

- `id`
- `planId`
- `state`
- `targetSiteId`
- `targetVmId`
- `targetVmName`
- `targetExternalRef`
- `targetPowerState`
- `latestRestorePointId`
- `latestTargetReadyAt`
- `diskSummary`
- `networkSummary`
- `runtimeState`

### 5.5 DrRestorePointResponse

필수 field:

- `id`
- `planId`
- `sequenceNo`
- `state`
- `sourceCapturedAt`
- `sourceReadyAt`
- `targetReadyAt`
- `expiresAt`
- `sizeBytes`
- `sourceLagSeconds`
- `targetLagSeconds`
- `artifactCount`
- `artifacts`

## 6. Async job와 DrRun 관계

### 6.1 기본 정책

`BaseAsyncCmd`는 CloudStack API의 사용자 경험과 audit를 담당하고, `DrRun`은 DR runtime 실행 상태의 source of truth가 된다.

권장 흐름:

1. API command validation
2. `DrRun` 생성 또는 idempotency hit 조회
3. Cloud async job event 기록
4. 짧은 preflight는 command 안에서 수행
5. 장시간 작업은 `DrRunExecutor`가 수행
6. response는 `DrRunResponse` 반환
7. UI는 `queryAsyncJobResult`와 `getDrRun`을 모두 확인

### 6.2 job result 규칙

| 상황 | Cloud async job result | DrRun state |
| --- | --- | --- |
| validation 실패 | failed | run 없음 또는 `FAILED` |
| preflight 실패 | failed | `FAILED` |
| run 생성 후 worker handoff 성공 | success with `accepted=true` | `QUEUED` 또는 `RUNNING` |
| command가 terminal까지 기다리는 짧은 작업 성공 | success | `SUCCEEDED` |
| worker에서 나중에 실패 | async job은 기존 결과 유지 | `FAILED`, event에 원인 |

UI는 `accepted=true`인 job success를 최종 보호 성공으로 표시하면 안 된다.

## 7. 오류 응답

표준 오류 field:

- `errorCode`
- `errorText`
- `retryable`
- `actionable`
- `runId`
- `stepName`
- `externalJobRef`

주요 오류 코드:

| Code | 의미 | Retry |
| --- | --- | --- |
| `DR_SITE_NOT_FOUND` | site 없음 | no |
| `DR_SITE_UNAVAILABLE` | endpoint 연결 불가 | yes |
| `DR_CREDENTIAL_INVALID` | credential 오류 | no |
| `DR_PLAN_NOT_FOUND` | plan 없음 | no |
| `DR_PLAN_STATE_INVALID` | 현재 상태에서 action 불가 | no |
| `DR_RUN_ALREADY_ACTIVE` | 같은 plan에 active run 존재 | yes |
| `DR_IDEMPOTENCY_REPLAY` | 기존 run 재사용 | yes |
| `DR_TARGET_MAPPING_INVALID` | datastore/network mapping 오류 | no |
| `DR_TARGET_NOT_READY` | target-ready restore point 없음 | no |
| `DR_ENGINE_BUSY` | FTCTL/V2K/VMware task busy | yes |
| `DR_FENCE_REQUIRED` | manual fencing 필요 | no |
| `DR_SOURCE_CONTROLLER_UNAVAILABLE` | source-controller failback 불가 | no |
| `DR_PROJECTION_STALE` | 상태 projection 갱신 실패 | yes |
| `DR_PERMISSION_DENIED` | 권한 부족 | no |

## 8. 기존 API 호환

기존 API는 유지한다.

| 기존 API | 신규 관계 |
| --- | --- |
| `createDisasterRecoveryCluster` | `DrSitePair` 생성 wrapper 후보 |
| `getDisasterRecoveryClusterList` | 기존 UI 유지. 신규 UI는 `listDrSites/listDrPlans` 사용 |
| `promoteDisasterRecoveryCluster` | 신규 `startDrFailover`와 의미가 다르므로 바로 대체하지 않음 |
| `resyncDisasterRecoveryCluster` | plan 단위 `startDrSync`로 점진 전환 |
| FTCTL `registerFtctlProtection` | KVM-to-KVM `createDrPlan` 내부 adapter 또는 import 경로 |
| FTCTL `failoverFtctlProtection` | `startDrFailover` 내부 adapter |
| FTCTL `failbackFtctlProtection` | source-controller `startDrFailback` 내부 adapter |
| FTCTL `adoptFtctlDrReplica` | replica-controller `adoptDrReplica` 내부 adapter |

기존 API와 신규 API가 같은 VM을 동시에 조작하지 않도록 `createDrPlan`과 FTCTL register path 양쪽에서 active protection/plan conflict를 확인한다.

## 9. API 수용 기준

- 모든 신규 command는 command class, response class, service method가 1:1로 추적된다.
- 모든 상태 변경 command는 `BaseAsyncCmd` 또는 명시적 예외 사유를 가진다.
- 모든 list command는 pagination을 지원한다.
- 모든 action command는 `idempotencyKey`를 받을 수 있다.
- UI는 `jobid`, `runid`, `runstate`를 모두 받을 수 있다.
- 위험 action은 `force`와 `acknowledgement`를 함께 요구한다.
- API response에 secret 원문이 포함되지 않는다.
- 기존 `DisasterRecoveryCluster`와 FTCTL API는 제거하거나 semantic을 바꾸지 않는다.

## 10. 2026-07-02 추가 설계: DR Site health check history API

상세 설계는 [529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md](529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md)를 따른다.

### 10.1 신규 command

| API | Command class | Base | Response | 설명 |
| --- | --- | --- | --- | --- |
| `listDrSiteHealthChecks` | `ListDrSiteHealthChecksCmd` | `BaseListCmd` | `DrSiteHealthCheckResponse` | DR Site 상태 체크 이력 조회 |

`checkDrSite`는 수동 점검 실행 API이고, `listDrSiteHealthChecks`는 저장된 이력 조회 API다. UI는 상태 체크 이력 탭에서 `listDrSiteHealthChecks`만 호출한다.

### 10.2 request parameter

| Parameter | Required | 설명 |
| --- | --- | --- |
| `siteid` | no | 특정 DR Site 이력만 조회. 상세 화면에서는 항상 전달 |
| `healthstate` | no | `CONNECTED`, `DEGRADED`, `DISCONNECTED`, `UNKNOWN` |
| `reasoncode` | no | `CREDENTIAL_MISSING`, `CREDENTIAL_INVALID`, `ENDPOINT_UNREACHABLE` 등 |
| `triggertype` | no | `MANUAL`, `SCHEDULED`, `CREATE`, `UPDATE`, `PREFLIGHT` |
| `startdate` | no | 점검 시각 시작 |
| `enddate` | no | 점검 시각 종료 |
| `page`, `pagesize` | no | 표준 pagination |

### 10.3 response field

| Field | 설명 |
| --- | --- |
| `id` | health check history uuid |
| `siteid` | DR Site uuid |
| `sitename` | 점검 당시 site name snapshot |
| `sitetype` | 점검 당시 site type |
| `hypervisortype` | 점검 당시 hypervisor type |
| `endpoint` | 점검 당시 endpoint snapshot |
| `triggertype` | 실행 원인 |
| `healthstate` | 점검 결과 |
| `healthreasoncode` | 점검 사유 code |
| `healthmessage` | 사용자 표시용 요약 메시지 |
| `healthlatencyms` | probe 소요 시간 |
| `credentialstate` | 점검 당시 credential state snapshot |
| `checkedat` | 점검 시각 |
| `managementserverid` | scheduler/API를 수행한 management server id |
| `jobid` | 수동 async job id가 있는 경우 |

`DrResponseGenerator`에는 `createSiteHealthCheckResponse(DrSiteHealthCheckVO)`를 추가한다. secret, password, API key, secret key, token은 응답에 포함하지 않는다.

health check history 내부 `details_json`에는 `MOLD_API` 점검 시 `authAlgorithm=HmacSHA256`, `apiCommand=listCapabilities`, `probe=DrMoldSiteProbe`를 포함할 수 있다. 이 값은 운영 진단용 non-secret metadata이며, API key, secret key, password, token, Authorization header는 포함하지 않는다. UI는 기본 테이블에 이 값을 필수 표시하지 않아도 되지만, 장애 분석용 확장 상세에서는 노출 가능하다.

### 10.4 UI wrapper

```js
const listKeys = {
  listDrSiteHealthChecks: ['listdrsitehealthchecksresponse', 'drsitehealthcheck']
}

export function listDrSiteHealthChecks (params = {}) {
  return getAPI('listDrSiteHealthChecks', params)
    .then(response => extractDrList(response, 'listDrSiteHealthChecks'))
}
```

## 11. AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| API 표면 | 기존 DR cluster API와 FTCTL API가 기능별로 분리 | `DrSite`, `DrPlan`, `DrRun`, `DrReplica`, `DrRestorePoint` API로 공통화 |
| action 실행 | 기능별 command가 각 service를 직접 호출 | action command가 `DrRunService`를 통해 run을 만들고 orchestrator에 위임 |
| async 결과 | Cloud async job 성공만 보고 최종 성공으로 오해 가능 | `jobid`, `runid`, `runstate`, `accepted`를 함께 반환 |
| 권한/resource | 기존 resource type으로 충분히 표현하기 어려움 | `DrSite`, `DrPlan`, `DrRun`, `DrReplica`, `DrRestorePoint` resource type 추가 |
| 오류 응답 | 엔진별 메시지가 섞임 | 표준 `DR_*` error code, retryable, actionable, stepName으로 표현 |

## 12. 2026-07-02 구현 반영: 수정/삭제 API 응답 계약

이번 구현에서는 UI 표준 작업 메뉴에서 `updateDrSite`, `deleteDrSite`, `updateDrPlan`, `deleteDrPlan`을 직접 호출하도록 `ui/src/api/dr.js` wrapper를 추가했다.

응답 보강:

- `DrSiteResponse.activeplancount`: site를 참조하는 active plan 수. UI는 이 값이 0보다 크면 site 삭제 action을 비활성화한다.
- `DrPlanResponse.schedulejson`, `policyjson`, `mappingjson`, `quiescepolicyjson`: plan 수정 화면이 기존 고급 설정을 잃지 않도록 response에 포함한다.
- `DrPlanResponse.actioneligibility.update/delete`: backend guard 결과를 UI action disabled 상태에 반영한다.

삭제 API 계약:

- `deleteDrSite`는 active plan 참조가 있으면 `DR_ACTIVE_PLAN_EXISTS` 계열 오류로 실패한다.
- `deleteDrPlan`은 active run, replica, restore point, target-ready/protected state가 남아 있으면 `DR_RUNTIME_RESOURCE_EXISTS` 계열 오류로 실패한다.
- 삭제 API는 장시간 engine cleanup을 동기 수행하지 않는다. 보호/런타임 자원 정리는 `releaseDrProtection` 등 명시 action으로 먼저 수행한다.
- 두 삭제 API는 async job 완료 후에도 DB soft-delete 검증을 수행한다. `removed`가 채워지지 않았으면 job 실패로 반환한다.
- CloudStack DAO 규칙상 `removed` 컬럼은 일반 update 대상이 아니므로 `markRemoved()+update()` 구현은 금지하고 `GenericDao.remove(id)`를 사용한다.

## 13. 2026-07-03 추가 설계: DR Site inventory discovery API

상세 설계는 [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)를 따른다.

신규 command:

`org.apache.cloudstack.api.command.admin.dr.DiscoverDrSiteInventoryCmd`

API name:

`discoverDrSiteInventory`

응답:

- `org.apache.cloudstack.api.response.dr.DrSiteInventoryResponse`
- `org.apache.cloudstack.api.response.dr.DrInventoryOptionResponse`

설계 기준:

1. command는 `BaseAsyncCmd`로 구현한다. 원격 Mold API 호출이 UI thread를 막지 않도록 Cloud async job으로 처리한다.
2. 기존 site 조회는 `id`를 받고 저장 credential을 사용한다.
3. 생성 전 조회는 `moldapiurl`, `moldapikey`, `moldsecretkey`, `tlsverify`를 write-only parameter로 받아 DB에 저장하지 않고 조회에만 사용한다.
4. `MOLD_KVM`은 `listZones`, `MOLD_VMWARE`는 `listZones`와 `listVmwareDcs` option을 반환한다.
5. `VMWARE_DIRECT`는 Mold inventory 대상이 아니므로 empty option과 reason을 반환한다.
6. 응답에는 secret/API key/password/token/Authorization header를 포함하지 않는다.
7. 이 API는 inventory 조회용이므로 `dr_site.health_state`와 `dr_site_health_check` 이력을 갱신하지 않는다.

### 13.1 2026-07-03 Remote inventory external id API 보정

상세 설계는 [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)의
`Remote Inventory ID 모델 보정` 절을 따른다.

`createDrSite`와 `updateDrSite`는 local internal id와 remote external id를 분리한다.

| Parameter | Type | 설명 |
| --- | --- | --- |
| `zoneid` | LONG | local Cloud internal Zone id. 하위 호환용 |
| `zoneexternalid` | STRING | 원격 Mold/vCenter Zone id 또는 uuid |
| `zonename` | STRING | 원격 Zone 표시 이름 |
| `vmwaredcid` | LONG | local VMware DC internal id. 하위 호환용 |
| `vmwaredcexternalid` | STRING | 원격 VMware DC id, uuid 또는 MoRef |
| `vmwaredcname` | STRING | 원격 VMware DC 표시 이름 |

`discoverDrSiteInventory`도 VMware DC 조회 filter를 external id 중심으로 보강한다.

| Parameter | Type | 설명 |
| --- | --- | --- |
| `zoneexternalid` | STRING | `listVmwareDcs` 호출 시 원격 Mold API에 전달할 Zone id |
| `zoneid` | LONG | local legacy filter. `zoneexternalid`가 없을 때만 fallback |

`DrInventoryOptionResponse` 보강:

| Field | Type | 설명 |
| --- | --- | --- |
| `id` | STRING | 원격 provider raw id |
| `value` | STRING | UI select value. 기본은 external id |
| `externalid` | STRING | 원격 provider stable id |
| `localid` | LONG | local DB id가 확인되는 경우에만 optional |
| `name` | STRING | 표시 이름 |
| `type` | STRING | `ZONE`, `VMWARE_DATACENTER` |

API 응답 규칙:

- UUID external id는 정상 값이다.
- external id가 있으면 option은 selectable이다.
- secret/API key/password/token은 계속 response에 포함하지 않는다.
- `selectable=false`는 id 자체가 없는 비정상 inventory row에만 사용한다.

## 2026-07-06 보강: Action API와 조회 API 응답 계약

DR action 실행 상태 계약은 [533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md](533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md)를 우선한다.

API 구현 원칙:

- `startDrSync` 같은 action command는 비동기 요청만 시작하고 FTCTL 작업 완료를 기다리지 않는다.
- `DrPlanResponse`는 latest run summary를 포함해야 한다.
- `DrRunResponse.accepted` 또는 `engineaccepted`는 Cloud API 접수가 아니라 FTCTL engine acceptance를 의미한다.
- Agent dispatch timeout은 `DR_AGENT_DISPATCH_TIMEOUT`, runtime 미생성은 `DR_RUNTIME_NOT_CREATED`로 구분한다.
- UI polling은 `getDrPlan`, `listDrRuns`, `listDrRunSteps` 조합으로 현재 상태를 재구성할 수 있어야 한다.

## 14. 2026-07-06 추가 보강: action/read/projection API 분리

상세 기준은 [533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md](533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md)의 17장을 따른다.

API command는 다음 세 종류로 분리한다.

| 종류 | 예 | 동작 원칙 |
| --- | --- | --- |
| Read API | `listDrPlans`, `getDrPlan`, `listDrRuns`, `listDrRunSteps` | DB snapshot만 읽고 Agent/ftctl을 inline 호출하지 않는다. |
| Action API | `startDrSync`, `pauseDrSync`, `startDrFailover` | validation, `dr_run` 생성, queue 등록 후 즉시 run/job id를 반환한다. |
| Projection API | `refreshDrProtectionView` | status refresh를 별도 async run/job으로 큐잉한다. UI 조회 thread를 막지 않는다. |

`getDrPlan` 또는 `listDrPlans`에 `refreshprojection=true` 같은 hidden blocking option을 넣지 않는다. 명시적인 `refreshDrProtectionView` API만 Cloud async job으로 투영을 갱신한다.

Action API response 최소 필드:

```java
@SerializedName("runid")
private String runId;

@SerializedName("jobid")
private String jobId;

@SerializedName("state")
private String state; // QUEUED, RETRYING, ACCEPTED 등

@SerializedName("accepted")
private Boolean accepted; // API가 요청을 접수했다는 의미
```

`DrRunResponse`의 engine acceptance 필드는 action API 접수와 분리한다.

```java
@SerializedName("engineaccepted")
private Boolean engineAccepted;

@SerializedName("retryable")
private Boolean retryable;

@SerializedName("retryafterseconds")
private Integer retryAfterSeconds;

@SerializedName("nextretryat")
private Date nextRetryAt;
```

표준 error code:

| Code | API 의미 |
| --- | --- |
| `DR_PLAN_RUN_ACTIVE` | 같은 plan에 충돌되는 active run이 있어 즉시 실행할 수 없음 |
| `DR_ENGINE_BUSY_RETRYABLE` | FTCTL이 retryable lock을 반환함 |
| `DR_ENGINE_BUSY_TIMEOUT` | retryable lock이 retry window를 초과함 |
| `DR_STATUS_TIMEOUT` | status refresh가 hard timeout으로 종료됨 |
| `DR_PROJECTION_STALE` | 최신 runtime projection을 갱신하지 못해 저장된 projection을 반환 중 |

수용 기준:

- `listDrPlans`/`getDrPlan` 호출만으로 host의 `ablestack_vm_ftctl dr-status` 프로세스가 생성되지 않는다.
- action API 성공 응답은 장기 작업 성공으로 해석하지 않고 `runid`/`jobid` 추적 시작으로만 해석한다.
- `locked retryable` 응답은 raw JSON이 아니라 표준 error code와 retry metadata로 변환되어 response에 포함된다.

## 15. 2026-07-06 추가 보강: readiness response 계약

상세 기준은 [534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md](534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md)를 따른다.

`listDrPlans`, `getDrPlan`, `queryAsyncJobResult`는 action accepted, runtime progress, target materialization을 분리해서 반환한다.

`DrPlanResponse` 추가 필드:

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

`startDrPlanSync` 응답은 다음 의미만 가진다.

- API 요청 접수
- Cloud async job 생성
- DR run 생성
- Agent/ftctl dispatch 시도 또는 접수 시작

`startDrPlanSync` 응답이 의미하지 않는 것:

- target VM 생성 완료
- restore point 생성 완료
- Failover 가능 상태
- RPO 충족

표준 reason code:

| Code | 의미 |
| --- | --- |
| `DR_ENGINE_ACCEPTED_TARGET_PENDING` | 엔진은 접수했지만 target materialization 전 |
| `DR_TARGET_VM_NOT_FOUND` | target VM reference 또는 inventory가 없음 |
| `DR_TARGET_STORAGE_NOT_FOUND` | target volume/disk materialization 미확인 |
| `DR_RESTORE_POINT_NOT_FOUND` | restore point 또는 durable checkpoint 없음 |
| `DR_RPO_NOT_SATISFIED` | 최신 durable checkpoint가 목표 RPO를 벗어남 |

## 2026-07-07 Update: API Readiness Contract For VMWARE_TO_KVM

`previewDrPlanSpec`, `createDrPlan`, and `updateDrPlan` now share the same
execution-readiness contract for VMware to ABLESTACK plans.

API behavior:

- `previewDrPlanSpec` returns `executionready=false` with structured blocking
  reasons when any selected disk has unresolved source size.
- `createDrPlan` and `updateDrPlan` reject `startsync=true` for invalid disk
  maps instead of accepting a run that is known to fail in FTCTL.
- The response should include `diskreadiness` entries so UI can mark the exact
  disk row.
- Read APIs should refresh or expose latest projection fields for active runs,
  including runtime terminal errors.

Canonical blocking codes include:

- `SOURCE_DISK_SIZE_UNRESOLVED`
- `TARGET_STORAGE_UNRESOLVED`
- `TARGET_DISK_FORMAT_INVALID`
- `DR_TARGET_DISK_SIZE_UNRESOLVED`

Detailed request/response design:
`538-cross-hypervisor-dr-vmware-to-kvm-disk-size-and-projection-hardening-design-20260707.md`.

## 2026-07-07 Update: API Semantics For Default Target Storage

The DR Plan guided API parameter contract is refined by
[539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md](539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md).

API rules:

- `targetstorageref` means default/fallback target storage for KVM target plans.
- `diskmappingsjson` remains the compact guided disk mapping payload and keeps
  `length = 65535`.
- If `diskmappingsjson` contains disk rows, the API must validate storage at
  disk level and must not reject the request only because `targetstorageref` is
  blank.
- If disk rows are absent for a legacy or non-guided-compatible path,
  `targetstorageref` may still be required for a KVM target.
- Blocking reasons should identify the row where possible, for example
  `TARGET_STORAGE_REQUIRED:<index>` or `TARGET_STORAGE_INVALID:<index>`.

No new API command is required.

## 2026-07-07 Update: DR Plan Dialog Standard API Impact

The SharedFS-style DR Plan dialog standard is a UI structure change. Detailed
design is in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md).

API contract rules:

- No API command is added for dialog sections.
- No existing API parameter is repurposed for collapse state or review panel
  state.
- `discoverDrPlanInventory`, `previewDrPlanSpec`, `createDrPlan`, and
  `updateDrPlan` remain the only APIs required by the guided dialog.
- UI section validation uses existing local field names and backend blocking
  reasons. Backend responses may include row-qualified reasons such as
  `TARGET_STORAGE_REQUIRED:<index>`, but section names are not part of the API
  contract.
- The API continues to receive canonical guided DR fields and compact JSON
  payloads only.

Therefore, the implementation must not add parameters such as
`activesections`, `dialoglayout`, or `reviewstate`.

## 2026-07-07 Update: API Impact Of Modal Alert And Gutter Refinement

The DR Plan modal dark-mode alert and right-gutter refinement is defined in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md#13-2026-07-07-refinement-dark-alert-and-right-gutter).

API design remains unchanged:

- no new command;
- no request parameter for modal width, alert style, scroll position, or right
  gutter;
- no response field for UI layout hints;
- no change to `previewDrPlanSpec`, `createDrPlan`, `updateDrPlan`, or
  `discoverDrPlanInventory`.

This fix must be implemented entirely in UI CSS and must not leak presentation
state into API contracts.

## 2026-07-07 Update: VMware VDDK Data-Plane API Contract

The VMware source sync failure with `DR_VMWARE_NBDKIT_FAILED` showed that
site credential validation and worker data-plane readiness are different API
concepts. Detailed design:
[543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md](543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md).

API additions:

- `createDrSite` and `updateDrSite` may accept optional `vddklibdir` for
  VMware Direct sites as an advanced operator override.
- The parameter is not secret credential material and must be stored in site
  capability JSON, not in `dr_site_credential.secret_payload`.
- `checkDrSite`, `listDrSites`, `getDrSite`, `previewDrPlanSpec`, and
  `getDrPlan` should expose a `vmwareDataPlane` capability object when known.

Canonical response fragment:

```json
{
  "capabilities": {
    "vmwareDataPlane": {
      "state": "READY",
      "hostId": 1,
      "moverReady": true,
      "nbdkitVddkReady": true,
      "qemuImgAvailable": true,
      "vddkLibdir": "/usr/share/ablestack/v2k/compat/vsphere80/vddk",
      "vddkLibraryVersion": "8",
      "reasonCode": null
    }
  }
}
```

Execution-readiness blocking codes:

- `DR_VDDK_LIBDIR_UNRESOLVED`
- `DR_VDDK_LIBRARY_LOAD_FAILED`
- `DR_VMWARE_NBDKIT_FAILED`
- `DR_VMWARE_MOVER_UNAVAILABLE`

`startDrPlanSync` must remain asynchronous, but it must reject a VMware source
plan before dispatch when the selected worker has blocking data-plane
readiness.

## 2026-07-07 Update: VMware Mover Source Graph API Impact

The VMware mover NBD source graph design is defined in
[544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md](544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md).

No new API command is required.

API response additions are limited to allowing the following terminal error code
in existing plan/run/step responses:

```text
DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID
```

Affected responses:

- `getDrPlan`
- `listDrPlans`
- `listDrRuns`
- `listDrRunSteps`

Expected response semantics:

- `effectivestate=ERROR`
- latest run `state=FAILED`
- latest run or step `errorcode=DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID`
- `startDrPlanSync` remains asynchronous and must not wait for qemu-img copy

This error is discovered only after the ftctl worker starts nbdkit and probes
the source socket, so it is not a create/update validation parameter.

## 2026-07-08 Update: Initial Sync Pending API Contract

The API must not expose a healthy active full-seed pending condition as a
runtime failure.

Runtime error response rule:

- If FTCTL runtime `error_code` is non-empty, expose it as `runtimeErrorCode`.
- If FTCTL runtime `state` is `ERROR` or `FAILED`, expose terminal error fields.
- If FTCTL `worker_state=FAILED`, expose terminal error fields.
- If latest run state is `FAILED`, expose terminal error fields.
- Otherwise, do not promote `dr_run.error_code` to `runtimeErrorCode`.

For `SYNCING/full-seed-transfer` with `targetStoragePresent=true`,
`targetVmPresent=false`, `restorePointPresent=false`, and empty FTCTL error,
the response must indicate active progress, not Fail.

Recommended response additions:

```java
@SerializedName("targetmaterializationstate")
private String targetMaterializationState;

@SerializedName("targetmaterializationmessage")
private String targetMaterializationMessage;

@SerializedName("initialsyncinprogress")
private Boolean initialSyncInProgress;
```

If these fields are not added in the first implementation pass,
`DrResponseGenerator` must still compute equivalent effective state using
existing runtime fields and must clear or suppress stale pending run errors in
the API response.

Detailed design:
`546-cross-hypervisor-dr-initial-sync-pending-projection-contract-design-20260708.md`.

## 2026-07-10 Normative Checkpoint And History API Update

`listDrSyncCheckpoints` is the preferred API. `listDrRestorePoints` is a
temporary compatibility alias and must not imply point-in-time recovery.
Checkpoint and event APIs are paged newest-first, and events default to 20
significant rows with unchanged projection refreshes excluded.

FTCTL_DR action APIs no longer expose historical checkpoint selection. A
legacy checkpoint ID can only validate the latest durable row and is converted
server-side to a String checkpoint reference. Numeric DB IDs never reach
FTCTL.

Detailed design:
`549-cross-hypervisor-dr-checkpoint-history-event-rpo-design-20260710.md`.

## 2026-07-10 Normative Cached Read And Explicit Refresh API Update

Add read-only `getDrProtectionView` and asynchronous
`refreshDrProtectionView` commands. `getDrProtectionView` returns the latest
versioned/redacted DB snapshot and never invokes Agent or FTCTL. Explicit
refresh returns an async job before Agent completion.

Remove direct projection refresh from all get/list DR commands. Event and
checkpoint list commands honor `BaseListCmd` pagination and return filtered
total counts. The UI default for Events is `page=1,pagesize=20`.

Detailed command, response, stale, and compatibility contracts:
`550-cross-hypervisor-dr-protection-view-cache-and-completed-checkpoint-design-20260710.md`.

## 2026-07-14 Normative Async Resource Completion Contract

`createDrPlan`, `updateDrPlan`, `createDrSite`, `updateDrSite`, and
`checkDrSite` are `BaseAsyncCmd` commands. UI wrappers must extract the job ID,
poll `queryAsyncJobResult`, and consume the final typed resource response.
List reconciliation starts only after job success. This does not wait for a DR
data copy; it only establishes that the resource transaction and action enqueue
have completed.

`getDrProtectionView` remains a synchronous DB-cache-only read.
`refreshDrProtectionView` remains the only explicit asynchronous projection
refresh command. Detailed design:
`552-cross-hypervisor-dr-async-read-consistency-and-live-cache-ui-design-20260714.md`.

## 2026-07-14 Normative Action Readiness And Quiesce Contract

`DrPlanResponse.actioneligibility` remains for compatibility and is augmented
with typed `actionreadiness`. The typed response provides `eligible`,
`reasonCode`, `coordinationRequired`, `requiredTransition`, `schedulerState`,
`controlState`, and `projectionAgeSeconds`.

`startDrTestFailover` does not synchronously pause FTCTL or wait for a test VM.
It validates cached control capability/freshness, creates a queued Run, and
returns the async job/run identity. A running continuous scheduler is not a
blocker when it advertises control protocol v2; it means
`coordinationRequired=true` and `requiredTransition=QUIESCE_SYNC`.

Normative request/response and error contracts:
`553-cross-hypervisor-dr-continuous-sync-control-and-quiesce-lock-design-20260714.md`.

## 2026-07-14 Cutover API Addendum

Plan API에는 `guestpreparationpolicy`, `testnetworkid`,
`bootvalidationpolicy`, `boottimeoutseconds` typed field를 추가한다.
`startDrTestFailover`는 restore point와 test-specific override만 받고 즉시
async job/Run identity를 반환한다.

Run/Plan response의 typed `cutover` object는 guest preparation, VirtIO,
Secure Boot, domain power, boot validation, cleanup-required 상태를 제공한다.
상세 계약: `554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md`.

## 2026-07-16 Plan And Run JSON Safety Addendum

Plan, Run, and operation responses expose bounded typed fields for
`errorcode`, `errormessage`, `failedcomponent`, `datacommitstate`,
`datacopied`, `metadatacommitted`, `targetdurable`, and `cycleretrymode`.
`errormessage` must never contain the complete FTCTL status document. The full
runtime payload remains available only through the dedicated status/projection
field and is serialized once by the API framework.

API regression tests cover Korean text, literal backslashes, escaped quotes,
control characters, and nested JSON text. A response that cannot be parsed by
a standards-compliant JSON decoder is an API failure even when HTTP status is
200.

Detailed response contract:
`557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md`.

## 2026-07-17 Mode Decision And Eligibility API Addendum

Checkpoint responses expose requested/effective mode, automatic-reseed flag,
decision code, reseed reason, invalid baseline disk count, and incremental
verification. Compatibility eligibility booleans remain, and a parallel typed
reason map explains blocking conditions. Normal cutover requires verified
incremental proof; forced emergency failover is separate and audited. The
normative response and error contract is in
`559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md`.

### 2026-07-19 Test Failover API Addendum

`startDrTestFailover` remains asynchronous and creates a `DrRun` and
`DrTestSession` transactionally. It accepts typed `networkmode`, `networkid`,
`bootvalidationmode`, and `boottimeoutseconds`. It never waits for Agent,
artifact preparation, Cloud volume import, or VM boot. Plan/protection responses
return a compact typed test-session summary and never return credentials or raw
FTCTL profiles.

Normative request/response contract:
`561-cross-hypervisor-dr-cloud-managed-test-failover-lifecycle-design-20260719.md`.

## 2026-07-25 Site-Derived Failback API Addendum

`startDrFailback`은 `planid`, idempotency, force, reason, acknowledgement 같은
non-secret operator intent만 받는다. 다음 legacy parameter는 deprecated 후
제거하며 `dr_run.request_json`에 저장하지 않는다.

- `failbacktargetmoldtype`
- `remotemoldapiurl`, `remotemoldapikey`, `remotemoldsecretkey`
- `targetmoldapiurl`, `targetmoldapikey`, `targetmoldsecretkey`

read-only `getDrFailbackPreflight`는 Plan 기반 active/destination Site, health,
credential configured/validated summary와 blocking reason을 반환한다. secret,
password, API key, 내부 credential ID/ref는 응답하지 않는다.

상세 command, response, sanitizer와 migration 계약:
`571-cross-hypervisor-dr-site-derived-failback-contract-design-20260725.md`.

## 2026-07-27 Failback Commit Outcome API Addendum

Agent/FTCTL commit 결과는 boolean success가 아니라
`ACKNOWLEDGED`, `REJECTED`, `PENDING`, `UNKNOWN`으로 반환한다. timeout,
빈 출력, parse 실패, stream 오류는 `UNKNOWN`이며 즉시 rollback을 의미하지
않는다.

Protection view와 Preflight는 commit attempt, scheduler request/ACK
generation, rollback state, actual authority/power 및 canonical action
decision을 같은 snapshot version으로 반환한다. 세부 contract는 문서 575를
따른다.

## 2026-07-27 Late ACK Canonical Read API Addendum

DR Plan read API는 `effectivestate`로 lifecycle을 숨기지 않고 다음 canonical
구조를 반환한다.

```json
{
  "lifecycle": {"state": "COMMIT_VERIFYING", "terminal": false},
  "authority": {"activeSide": "SOURCE", "scheduler": "RUNNING"},
  "operation": {"runId": "...", "commitOutcome": "ACKNOWLEDGED"},
  "cycle": {"sequence": 463, "producerRunId": "...", "token": "plan:463"},
  "cache": {"stale": false, "generatedAt": "..."},
  "actions": {"failback": false, "sync": false}
}
```

operation Run과 cycle producer Run이 다른 것은 정상이며 API validator가
불일치 오류로 처리하지 않는다. 단, 한 cycle의 sequence/token/checkpoint/NBD
필드는 같은 checkpoint source여야 한다. response와 command DTO 상세는
[576-cross-hypervisor-dr-failback-late-ack-and-projection-convergence-design-20260727.md](576-cross-hypervisor-dr-failback-late-ack-and-projection-convergence-design-20260727.md)를
따른다.

## 2026-07-28 Current Authority API Addendum

Plan API의 기존 cutover flat field는 current cutover session 전용이다.
`active_side=SOURCE`이면 과거 `PROMOTED` session이 존재해도 해당 field를
비워 반환한다. 과거 세션은 history API에서 조회한다.

Plan response는 `authorityside`, `authorityphase`, `authoritysequence`,
`authorityconsistent`, `currentcutoversessionid`를 제공한다. Protection View
snapshot version 4의 `planProjection`은 같은 response builder와 eligibility
evaluator를 사용한다.

상세 response와 compatibility 계약은 문서 578을 따른다.

## 2026-07-30 Current Runtime API Addendum

Plan response의 `runtimeState`, `runtimeStep`, `runtimeErrorCode`,
`runtimeProjectionMessage`와 `lastErrorCode/Message`는 current protection
또는 active Run 전용이다. 최근 종료 Run은 `lastRun`에만 반환하며, 과거
실패를 current runtime 오류로 복제하지 않는다.

Protection View snapshot version 5도 같은 `DrPlanResponse`를 사용한다.
필드 추가나 command signature 변경은 없으며 기존 API 호환성을 유지한다.
상세 필드 의미와 테스트 계약은 문서 580을 따른다.

## 2026-07-30 Post-Failover API Addendum

후속 Protection View version 6은 `rpoevaluationmode`,
`displayrposeconds`, `rpoasof`, `rpostatus`, `currentseverity`를 typed
필드로 제공한다. 기존 action command signature는 변경하지 않는다.
TARGET authority의 상태와 표시 계약은 문서 581을 따른다.
## 2026-07-30 Failback Accepted Run Contract Addendum

모든 DR start action은 `id`, `planid`, `runtype`, `state`, `accepted`,
`idempotencykey`, `actioncontractversion`을 포함하는 동일 수락 계약을 제공한다.
`listDrRuns`는 선택 `idempotencykey` 조회를 지원해 응답 유실이나 재시도 시 이미
생성된 Run을 복원한다. API는 엔진 종단 완료를 기다리지 않는다. 상세 필드와
serialization test 기준은 문서 583을 따른다.

## 2026-07-30 Typed Action Availability API Addendum

기존 `actioneligibility: Map<String, Boolean>`은 구버전 UI 호환을 위해
유지한다. Plan response와 Protection View `planProjection`에는 병렬
`actionavailability`를 추가한다.

각 작업은 `applicable`, `enabled`, `reasoncode`, 안전한 `reasonargs`를
반환한다. boolean compatibility 값은 typed availability의 `enabled`에서
파생하며 두 값이 달라서는 안 된다. API는 label, icon, 메뉴 그룹, 색상 같은
표현 정보를 반환하지 않는다.

신규 response DTO, reason code, fallback 및 serialization test 계약은
[584-cross-hypervisor-dr-context-action-availability-and-darkmode-design-20260730.md](584-cross-hypervisor-dr-context-action-availability-and-darkmode-design-20260730.md)를
따른다.

## 2026-08-06 Failback Evidence Capability API Addendum

`getDrFailbackPreflight` now has two explicit meanings: it validates whether an
operation may start, and it verifies that the installed FTCTL can later publish
the post-transfer evidence contract required by Cloud. It does not claim that a
future final delta has already completed. The response adds evidence contract
version, publication readiness, and one user-safe blocking reason. The async
`startDrFailback` response remains start-only. Document 596 is normative.

## 2026-07-31 Test Failover Acceptance Addendum

`StartDrTestFailoverCmd`의 async job은 장시간 Test Failover 완료가 아니라
Run 접수 성공/실패를 확정한다. 성공 job result는 typed `DrRunResponse`를
반환하고, 실패 job은 Run을 생성하지 않은 채 `DR_TEST_SESSION_BLOCKING`
같은 원인 코드를 보존한다.

`actioneligibility`와 `actionavailability`는 동일한 lifecycle resolution에서
파생한다. Protection View cache의 blocking session reason을 포함하는 최신
계약은 문서
[586-cross-hypervisor-dr-test-session-blocker-and-async-acceptance-design-20260731.md](586-cross-hypervisor-dr-test-session-blocker-and-async-acceptance-design-20260731.md)를
따른다.
