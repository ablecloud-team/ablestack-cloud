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

# Cross Hypervisor DR Cloud UI Design

> 2026-07-31 latest correction: FTCTL_DR UI hides the standalone
> `원본 사이트 격리 해제 확인` action. Source isolation is read-only preflight
> evidence inside Failback/Reprotect. See document 587.

작성일: 2026-06-30

대상 브랜치: `feature/ftctl-cloud-integration`

상위 문서:

- [500-cross-hypervisor-dr-architecture-plan-20260630.md](500-cross-hypervisor-dr-architecture-plan-20260630.md)
- [501-cross-hypervisor-dr-domain-schema-design-20260630.md](501-cross-hypervisor-dr-domain-schema-design-20260630.md)
- [502-cross-hypervisor-dr-adapter-contract-design-20260630.md](502-cross-hypervisor-dr-adapter-contract-design-20260630.md)
- [503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md](503-cross-hypervisor-dr-state-machine-and-worker-design-20260630.md)
- [504-cross-hypervisor-dr-phase1-vmware-target-scope-design-20260630.md](504-cross-hypervisor-dr-phase1-vmware-target-scope-design-20260630.md)
- [505-cross-hypervisor-dr-ftctl-v2k-integration-design-20260630.md](505-cross-hypervisor-dr-ftctl-v2k-integration-design-20260630.md)
- [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)

## 1. 목적

이 문서는 `Cross Hypervisor DR` 기능의 Cloud UI 상세 설계를 정의한다.

범위는 설계까지이며, 이 문서 작성 단계에서는 Vue 컴포넌트, API 모듈, 라우팅, locale, 스타일 구현을 진행하지 않는다. 이후 구현 시에는 이 문서의 화면 구조, 상태 표시, 액션 게이트, 다크모드 스타일 기준을 기반으로 작업한다.

UI 목표는 다음과 같다.

- 기존 `DisasterRecoveryCluster`, `FTCTL` UI의 동작을 깨지 않고 신규 `DrSite`, `DrPlan`, `DrRestorePoint`, `DrReplica`, `DrRun` 모델을 노출한다.
- 사용자가 "현재 보호 가능한가", "목표 사이트에 실제로 부팅 가능한 복제본이 있는가", "장애 전환을 실행해도 되는가"를 한 화면에서 판단할 수 있게 한다.
- 비동기 작업은 클릭 성공이 아니라 Cloud async job, `DrRun`, backend/runtime projection 상태로 최종 성공/실패를 확인한다.
- ABLESTACK/KVM to ABLESTACK/KVM의 기존 FTCTL 성공 경로와 VMware 대상 신규 경로를 같은 UI 언어로 표현하되, 내부 엔진 차이는 명확히 드러낸다.
- 다크모드 대응은 필수 구현 조건으로 둔다. 모든 신규 화면, 테이블, 배지, 진행률, 경고, 빈 상태, 모달은 라이트/다크모드에서 모두 읽기 쉬워야 한다.

## 2. 기존 UI 자산

구현 시 우선 재사용하거나 참고할 기존 파일은 다음과 같다.

| 영역 | 파일 | 활용 방향 |
| --- | --- | --- |
| DR 인프라 메뉴 | `ui/src/config/section/infra/disasterRecovery.js` | 기존 DR cluster 섹션 구조, resource config, action 정의 방식 참고 |
| VM DR 테이블 | `ui/src/views/compute/dr/DRTable.vue` | VM 상세에서 DR 관련 목록을 보여주는 패턴 참고 |
| FTCTL 탭 | `ui/src/views/compute/FtctlTab.vue` | 상태 요약, 액션 게이트, async refresh, 진행률, event 표시 패턴 참고 |
| FTCTL 등록 | `ui/src/views/compute/RegisterFtctlProtection.vue` | 보호 등록 wizard, 대상 호스트/스토리지 선택 UX 참고 |
| 표준 상세 레이아웃 | `ui/src/components/view/ResourceView.vue` | 볼륨 상세와 같은 좌/우 상세 레이아웃, 탭, action 배치 기준 |
| 표준 상세 좌측 카드 | `ui/src/components/view/InfoCard.vue` | `vm-info-card`, `resource-details`, `resource-detail-item` class 계약 참고 |
| 표준 상세 우측 탭 | `ui/src/components/view/DetailsTab.vue` | `a-list` 기반 label/value row 상세 표시 방식 참고 |
| 표준 작업 컴포넌트 | `ui/src/components/view/ActionButton.vue` | 상세 `작업` 드롭다운과 우클릭 context menu 내부 action 렌더링 기준 |
| 다크모드 상태 | `ui/src/store/getters.js`, `ui/src/store/modules/user.js` | `$store.getters.darkMode` 기반 분기 참고 |
| 전역 테마 | `ui/src/App.vue`, `ui/public/color.less`, `ui/public/css/dark-theme.css` | Ant Design Vue theme token, `body.dark-mode` 스타일 방식 참고 |

기존 FTCTL 탭은 유지한다. 신규 Cross Hypervisor DR UI는 FTCTL 탭을 대체하지 않고, FTCTL을 DR engine 중 하나로 projection한다.

## 3. 정보 구조

### 3.1 메뉴 구조

권장 메뉴 구조는 다음과 같다.

| 위치 | 화면 | 설명 |
| --- | --- | --- |
| Infrastructure > Disaster Recovery | DR Sites | Mold/KVM, VMware, remote Mold 같은 DR site 등록 및 상태 확인 |
| Infrastructure > Disaster Recovery | DR Plans | VM 단위 보호 계획 목록, 동기화 상태, RPO, 대상 readiness 확인 |
| Compute > Virtual Machines > VM detail | Disaster Recovery 탭 | 해당 VM에 연결된 `DrPlan` 요약 및 주요 액션 |
| Compute > Virtual Machines > VM detail | Fault Tolerance 탭 | 기존 FTCTL 운영 화면. 변경하지 않음 |

기존 `DisasterRecoveryCluster` 화면은 바로 제거하지 않는다. 초기 구현에서는 기존 화면과 신규 DR Sites/Plans를 병행한다. 기존 화면이 신규 `DrSite`로 통합될 때는 별도 migration 문서를 둔다.

### 3.2 신규 resource config 제안

구현 파일 위치는 다음을 권장한다.

| 목적 | 제안 파일 |
| --- | --- |
| DR Plan resource config | `ui/src/config/section/infra/drPlan.js` |
| DR Site resource config | `ui/src/config/section/infra/drSite.js` |
| DR Plan 목록 | `ui/src/views/infra/dr/DrPlanList.vue` |
| DR Site 목록/상세 | `ui/src/views/infra/dr/DrSiteList.vue` |
| DR 공통 form modal | `ui/src/components/dr/DrFormModal.vue` |
| DR Plan 생성 wizard | `ui/src/views/infra/dr/DrPlanWizard.vue` |
| DR Plan 상세 overview | `ui/src/views/infra/dr/DrPlanOverview.vue` |
| DR 표준 상세 좌측 카드 | `ui/src/components/dr/DrResourceInfoCard.vue` |
| DR 표준 상세 row 목록 | `ui/src/components/dr/DrResourceDetailsTab.vue` |
| Restore point tab | `ui/src/views/infra/dr/DrRestorePointsTab.vue` |
| Replica tab | `ui/src/views/infra/dr/DrReplicaTab.vue` |
| Run history tab | `ui/src/views/infra/dr/DrRunsTab.vue` |
| Event tab | `ui/src/views/infra/dr/DrEventsTab.vue` |
| VM detail projection | `ui/src/views/compute/dr/DrPlanVmTab.vue` |
| 공통 상태 배지 | `ui/src/components/dr/DrStatusPill.vue` |
| RPO KPI | `ui/src/components/dr/DrRpoKpi.vue` |
| 진행률 표시 | `ui/src/components/dr/DrRunProgress.vue` |
| 액션 툴바 | `ui/src/components/dr/DrActionToolbar.vue` |
| 토폴로지/경로 표시 | `ui/src/components/dr/DrTopology.vue` |

## 4. 화면 설계

### 4.1 DR Sites 목록

목적은 DR endpoint inventory와 연결 상태를 한눈에 보여주는 것이다.

주요 컬럼:

| 컬럼 | 값 |
| --- | --- |
| Name | `DrSite.name` |
| Type | `MOLD_KVM`, `MOLD_VMWARE`, `VMWARE_DIRECT` |
| Hypervisor | `KVM`, `VMWARE` |
| Endpoint | Mold URL 또는 vCenter URL. credential은 표시하지 않음 |
| Health | `CONNECTED`, `DEGRADED`, `DISCONNECTED`, `UNKNOWN` |
| Last Check | 마지막 연결 점검 시각 |
| Plans | 이 site를 source 또는 target으로 사용하는 active plan 수 |
| Actions | Check, Edit, Disable, Delete |

상태 표시:

- 연결 실패는 목록 row 전체를 위험색으로 칠하지 않는다. `Health` 배지와 row 하단 보조 텍스트로 원인을 표시한다.
- credential 오류, 인증서 오류, network timeout은 서로 다른 reason code로 보여준다.
- 다크모드에서 위험/경고 배지는 배경만 진하게 하지 말고 텍스트 대비를 보장한다.

### 4.2 DR Site 상세

DR Site 상세는 볼륨 상세 화면을 표준으로 삼는다. `ResourceLayout`의 좌측에는 표준 정보 카드, 우측에는 표준 row 기반 상세 탭을 둔다. `a-descriptions bordered` 기반 2열 표는 사용하지 않는다.

상세 화면 구조:

| 영역 | 내용 |
| --- | --- |
| Breadcrumb/action | 기존 상세 화면과 같은 breadcrumb, 우측 상단 `작업` 드롭다운 |
| Left info card | site icon, name, type/hypervisor tag, site health, state, id, endpoint, credential status, last check |
| Right tabs | `상세`, `DR 계획`, `이벤트` 순서. 첫 탭은 반드시 `상세` |

우측 탭:

| 탭 | 내용 |
| --- | --- |
| 상세 | id, name, description, type, hypervisor, endpoint, zone, VMware datacenter, credential summary, created |
| DR 계획 | 이 site와 연결된 DR plans |
| 이벤트 | site check, credential rotation, adapter error |

구현 기준:

- `DrSiteList.vue`의 detail branch는 `ResourceLayout`을 유지하되, 좌측 custom `cross-dr-info-card` DOM을 `DrResourceInfoCard`로 교체한다.
- 우측 `a-descriptions bordered`는 제거하고 `DrResourceDetailsTab` 또는 `a-list` 기반 row 목록으로 교체한다.
- `activeTab`의 기본값은 `overview`가 아니라 `details`로 둔다. URL/history tab도 `details`, `plans`, `events`를 사용한다.
- `DrResourceDetailsTab`은 field metadata 배열을 받아 label/value를 렌더링한다. value는 string, VNode slot, formatter function을 허용한다.
- secret/API key/password/token은 field metadata에 절대 포함하지 않는다.
- credential은 `credentialstate`, `credentialtype`, `credentialendpoint`, `credentialprincipal`, `credentiallastvalidated` 같은 summary 필드만 표시한다.

민감 정보 표시 원칙:

- API key, secret key, password, token은 화면에 표시하지 않는다.
- UI는 credential reference id를 입력받거나 표시하지 않는다.
- DR Site 추가/수정 화면은 사이트 유형별 실제 인증정보를 입력받고, backend가 암호화 저장한 결과만 credential 상태로 표시한다.
- secret rotation은 기존 값을 읽어 오는 방식이 아니라 새 credential을 입력해 교체하는 방식으로 설계한다.

### 4.2.1 DR Site 인증정보 입력

`인증정보 참조` 필드는 제거한다. UI는 사용자가 실제로 알고 있는 접속 정보를 입력하도록 한다.

| Site 유형 | UI 입력 |
| --- | --- |
| `MOLD_KVM`, `MOLD_VMWARE` | Mold API URL, API Key, Secret Key, TLS 검증 여부 |
| `VMWARE_DIRECT` 또는 VMware target | vCenter URL, vCenter username, vCenter password, TLS 검증 여부 |

신규 DR Site 등록 UI에서 사용자가 선택할 수 있는 유형은 `MOLD_KVM`과 `VMWARE_DIRECT`로 제한한다. `MOLD_VMWARE`는 기존 데이터와 API 하위 호환을 위한 내부 유형으로만 유지한다. `VMWARE_DIRECT` 선택 시 URL, username, password 세 입력 컨트롤은 항상 함께 렌더링해야 하며 username은 브라우저 credential 자동완성에 종속되지 않는 일반 텍스트 입력을 사용한다.

화면 표시 원칙:

- `VMWARE_DIRECT` 선택 시 하이퍼바이저는 항상 `VMWARE`이므로 사용자 선택 필드로 노출하지 않는다. 필요하면 읽기 전용 요약으로만 표시한다.
- `endpoint`는 vCenter URL 또는 Mold API URL과 의미가 겹치므로 사용자 입력 필드로 노출하지 않는다. UI는 site type에 맞는 전용 URL을 받고 payload 생성 시 하위 호환용 endpoint를 같은 값으로 보낼 수 있다.
- `Zone`, `VMware 데이터센터`는 VMware Direct 기본 등록에 필요하지 않다. 기존 Cloud VMware datacenter mapping이나 고급 매핑이 필요한 경우에만 `고급 설정` 영역에서 표시한다.
- 사이트 유형과 DR 방향, 엔진 유형 같은 내부 enum은 `VMWARE_DIRECT`, `KVM_TO_VMWARE`, `FTCTL_DR` 원문을 그대로 표시하지 않고 locale label로 표시한다.
- TLS 스위치 라벨은 선택된 credential type에 맞춰 `Mold API 인증서 검증` 또는 `vCenter 인증서 검증`으로 표시한다.

구현 대상:

| 파일 | 변경 |
| --- | --- |
| `ui/src/views/infra/dr/DrSiteList.vue` | `createForm.credentialref` 제거, 사이트 유형별 credential form state와 payload 생성 규칙 추가 |
| `ui/src/components/dr/DrFormModal.vue` | 신규. 고정 header/footer와 content-only scroll을 제공하는 DR 공통 form modal |
| `ui/src/components/dr/DrSiteCredentialFields.vue` | 신규 또는 분리 후보. 사이트 유형별 입력 필드 표시 |
| `ui/src/components/dr/DrSiteCredentialSummary.vue` | 신규. 등록/검증 상태 표시 |
| `ui/src/api/dr.js` | `updateDrSiteCredential`, `clearDrSiteCredential` wrapper 추가 |

### 4.2.4 DR Site health check와 UNKNOWN 표시

Site health check의 상세 구현 기준은 [528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md](528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md)를 따른다.

UI는 `checkDrSite` 호출이 성공했다는 사실을 site 연결 성공으로 해석하지 않는다. Cloud async job은 요청 접수/처리 성공만 의미하며, 실제 상태는 응답의 `healthstate`, `healthreasoncode`, `healthmessage`로 판단한다.

표시 규칙:

| `healthstate` | 한국어 라벨 | 표시 의미 |
| --- | --- | --- |
| `CONNECTED` | 정상 | Mold/vCenter endpoint와 credential 검증 성공 |
| `DEGRADED` | 주의 | 접속 가능하나 TLS/capability 일부 경고 |
| `DISCONNECTED` | 실패 | credential 누락, 인증 실패, 네트워크/API 실패 |
| `UNKNOWN` | 미점검 | 아직 점검하지 않았거나 판정 불가 |

`credentialconfigured=false`, `credentialstate=CLEARED`, `MISSING`, `LEGACY_REF`는 모두 plan 생성/action의 사전 경고 대상이다. 이 경우 `사이트 점검`을 실행하면 UI는 실패 toast와 함께 `사이트 수정에서 인증 정보를 다시 입력하세요.`를 안내한다.

`사이트 점검` 액션 흐름:

1. `checkDrSite({ id, persiststatus: true })`를 호출한다.
2. async job polling을 완료한다.
3. job 결과의 `drsite` 또는 후속 `getDrSite` 응답으로 row를 갱신한다.
4. `healthstate=CONNECTED`이면 정상 toast를 표시한다.
5. `DISCONNECTED/DEGRADED`이면 `healthmessage`를 toast와 tooltip에 표시한다.

UI는 vCenter/Mold endpoint에 직접 접속하지 않는다.

폼 payload 예시:

```js
{
  name: 'seoul-vcenter',
  sitetype: 'VMWARE_DIRECT',
  hypervisortype: 'VMWARE',
  endpoint: 'https://vcenter.example.local/sdk',
  vcenterurl: 'https://vcenter.example.local/sdk',
  vcenterusername: 'administrator@vsphere.local',
  vcenterpassword: 'write-only',
  tlsverify: true
}
```

### 4.2.5 Remote Zone/Datacenter 선택값 보정

상세 설계는 [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)의
`Remote Inventory ID 모델 보정` 절을 따른다.

UI 원칙:

- DR Site 등록/수정에서 Zone/VMware Datacenter select는 원격 Mold/vCenter inventory의 external id를 value로 사용한다.
- 원격 Zone id가 UUID이면 정상 선택 가능한 값이다.
- `저장 가능한 내부 ID 없음` 같은 내부 구현 메시지는 사용자에게 표시하지 않는다.
- `zoneid`, `vmwaredcid` numeric field는 local legacy/internal id로만 유지하고, 신규 Mold inventory 선택 UI의 기본 저장값으로 사용하지 않는다.

폼 state 기준:

```js
createForm: {
  zoneexternalid: undefined,
  zonename: undefined,
  vmwaredcexternalid: undefined,
  vmwaredcname: undefined,
  zoneid: undefined,
  vmwaredcid: undefined
}
```

select option 기준:

```js
{
  value: item.externalid || item.value || item.id,
  label: item.name || item.description || item.id,
  disabled: !(item.externalid || item.value || item.id)
}
```

상세/목록 표시 기준:

- Zone은 `zonename`을 우선 표시하고, 필요 시 `zoneexternalid`를 보조 텍스트로 표시한다.
- VMware Datacenter는 `vmwaredcname`을 우선 표시하고, 필요 시 `vmwaredcexternalid`를 보조 텍스트로 표시한다.
- 숫자 local id는 사용자가 이해해야 할 기본 정보가 아니므로 상세 기본 row에서 우선 표시하지 않는다.

응답 표시 예시:

```text
인증정보: 등록됨
유형: vCenter
계정: administrator@vsphere.local
마지막 검증: 정상
```

### 4.2.2 DR form modal 공통 구조

DR Site 추가, DR Plan 추가, DR Plan action 확인 대화상자는 같은 form modal 구조를 사용한다.

구조:

- Header: 대화상자 제목과 닫기 버튼만 둔다.
- Body: form content 전용 영역이며 이 영역만 세로 스크롤된다.
- Footer: 취소/확인 버튼을 고정한다.
- Loading: 본문에만 `a-spin`을 적용하되 footer 버튼 loading 상태와 연동한다.
- Keyboard: `Ctrl+Enter` 제출은 유지하되 destructive action은 별도 acknowledgement를 요구한다.

구현 skeleton:

```vue
<dr-form-modal
  :visible="showCreateModal"
  :title="$t('label.dr.site.add')"
  :loading="createLoading"
  :confirm-loading="createLoading"
  @cancel="closeCreateModal"
  @ok="createSite">
  <a-form layout="vertical" class="cross-dr-form-layout">
    ...
  </a-form>
</dr-form-modal>
```

`DrFormModal.vue`는 Ant Design Vue `a-modal`의 `#footer` slot을 사용한다. 기존처럼 `:footer="null"`로 만들고 form 안쪽에 버튼을 넣지 않는다. 모달 전체가 viewport를 넘어가더라도 header/footer는 화면 안에 유지되고 `.cross-dr-modal__scroll`만 스크롤되어야 한다.

### 4.2.3 DR 표준 작업 메뉴와 컨텍스트 메뉴

DR Site/Plan 화면의 작업 UX는 볼륨 상세/목록 화면의 표준 액션 모델을 따른다.

표준 소스 기준:

- `AutogenView.vue`: 상세 화면에서 `visibleDataViewActions`를 `ActionButton(dataView=true)`로 감싸고 상단 우측 `작업` 드롭다운에 표시한다.
- `AutogenView.vue`: 목록 화면에서는 `visibleListActions`만 상단 toolbar에 표시하고, row 단위 작업은 table 오른쪽 컬럼에 직접 넣지 않는다.
- `ListView.vue`: table wrapper의 `contextmenu`를 받아 현재 row 또는 선택 row를 찾고, 같은 action 배열을 `ActionButton(dataView=true)`로 표시한다.
- `ActionButton.vue`: `listView`, `dataView`, `groupAction`, `show`, `disabled`, `api in store.apis` 계약으로 action 표시 여부를 결정한다.

현재 DR 화면은 custom component이므로 `AutogenView`로 전면 이전하지 않고, 아래 공통 컴포넌트와 action factory로 표준 동작을 이식한다.

| 파일 | 책임 |
| --- | --- |
| `ui/src/utils/dr/resourceActions.js` | DR Site/Plan action 정의와 show/disabled/confirm metadata 생성 |
| `ui/src/components/dr/DrResourceActionMenu.vue` | 상세 상단 `작업` 드롭다운. 내부는 `ActionButton(dataView=true)` 사용 |
| `ui/src/components/dr/DrResourceContextMenu.vue` | 목록 row/detail panel 우클릭 메뉴. 내부는 `ActionButton(dataView=true)` 사용 |
| `ui/src/views/infra/dr/DrSiteList.vue` | site 목록/상세 action 실행, create/edit modal, delete confirm 연결 |
| `ui/src/views/infra/dr/DrPlanList.vue` | plan 목록/상세 action 실행, create/edit modal, delete confirm, runtime action 연결 |

`resourceActions.js` 설계:

```js
export function buildDrSiteActions () {
  return [
    {
      api: 'checkDrSite',
      icon: 'api-outlined',
      label: 'label.dr.site.check',
      listView: true,
      dataView: true,
      show: site => !site?.removed
    },
    {
      api: 'updateDrSite',
      icon: 'edit-outlined',
      label: 'label.edit',
      listView: true,
      dataView: true,
      popup: true,
      actionKind: 'edit-site',
      show: site => !site?.removed
    },
    {
      api: 'deleteDrSite',
      icon: 'delete-outlined',
      label: 'label.delete',
      message: 'message.dr.site.delete.confirm',
      listView: true,
      dataView: true,
      danger: true,
      actionKind: 'delete-site',
      disabled: site => Number(site?.activeplancount || 0) > 0,
      disabledReason: 'message.dr.site.delete.blocked.by.plan',
      show: site => !site?.removed
    }
  ]
}

export function buildDrPlanActions () {
  return [
    {
      api: 'updateDrPlan',
      icon: 'edit-outlined',
      label: 'label.edit',
      listView: true,
      dataView: true,
      popup: true,
      actionKind: 'edit-plan',
      disabled: plan => hasActiveRun(plan),
      disabledReason: 'message.dr.plan.update.blocked.by.run'
    },
    {
      api: 'startDrSync',
      icon: 'sync-outlined',
      label: 'label.dr.action.sync.now',
      listView: true,
      dataView: true,
      actionKind: 'runtime',
      eligibilityKey: 'sync'
    },
    {
      api: 'pauseDrSync',
      icon: 'pause-circle-outlined',
      label: 'label.dr.action.pause.sync',
      listView: true,
      dataView: true,
      actionKind: 'runtime',
      eligibilityKey: 'pausesync'
    },
    {
      api: 'resumeDrSync',
      icon: 'play-circle-outlined',
      label: 'label.dr.action.resume.sync',
      listView: true,
      dataView: true,
      actionKind: 'runtime',
      eligibilityKey: 'resumesync'
    },
    {
      api: 'startDrTestFailover',
      icon: 'experiment-outlined',
      label: 'label.dr.action.test.failover',
      listView: true,
      dataView: true,
      actionKind: 'runtime',
      eligibilityKey: 'testfailover'
    },
    {
      api: 'stopDrTestFailover',
      icon: 'stop-outlined',
      label: 'label.dr.action.test.cleanup',
      listView: true,
      dataView: true,
      danger: true,
      actionKind: 'runtime-modal',
      eligibilityKey: 'stoptestfailover'
    },
    {
      api: 'startDrFailover',
      icon: 'thunderbolt-outlined',
      label: 'label.dr.action.failover',
      listView: true,
      dataView: true,
      danger: true,
      actionKind: 'runtime-modal',
      eligibilityKey: 'failover'
    },
    {
      api: 'confirmDrFenceClear',
      icon: 'safety-outlined',
      label: 'label.dr.action.fence.clear',
      listView: true,
      dataView: true,
      danger: true,
      actionKind: 'runtime-modal',
      eligibilityKey: 'confirmfenceclear'
    },
    {
      api: 'startDrFailback',
      icon: 'undo-outlined',
      label: 'label.dr.action.failback',
      listView: true,
      dataView: true,
      danger: true,
      actionKind: 'runtime-modal',
      eligibilityKey: 'failback'
    },
    {
      api: 'startDrReprotect',
      icon: 'retweet-outlined',
      label: 'label.dr.action.reprotect',
      listView: true,
      dataView: true,
      actionKind: 'runtime',
      eligibilityKey: 'reprotect'
    },
    {
      api: 'adoptDrReplica',
      icon: 'safety-certificate-outlined',
      label: 'label.dr.action.adopt.replica',
      listView: true,
      dataView: true,
      danger: true,
      actionKind: 'runtime-modal',
      eligibilityKey: 'adoptreplica'
    },
    {
      api: 'releaseDrProtection',
      icon: 'delete-outlined',
      label: 'label.dr.action.release.protection',
      listView: true,
      dataView: true,
      danger: true,
      actionKind: 'runtime-modal',
      eligibilityKey: 'releaseprotection'
    },
    {
      api: 'deleteDrPlan',
      icon: 'delete-outlined',
      label: 'label.delete',
      message: 'message.dr.plan.delete.confirm',
      listView: true,
      dataView: true,
      danger: true,
      actionKind: 'delete-plan',
      disabled: plan => hasActiveRun(plan) || hasActiveProtection(plan),
      disabledReason: 'message.dr.plan.delete.blocked.by.protection'
    }
  ]
}
```

`DrResourceActionMenu.vue` skeleton:

```vue
<a-dropdown
  v-model:visible="visible"
  :trigger="['click']"
  placement="bottomRight"
  overlayClassName="autogen-action-dropdown">
  <template #overlay>
    <div class="autogen-action-dropdown__content">
      <action-button
        :loading="loading"
        :actions="visibleActions"
        :resource="resource"
        :dataView="true"
        @exec-action="$emit('exec-action', $event, resource)" />
    </div>
  </template>
  <a-button type="primary" class="autogen-action-dropdown__button">
    <template #icon><down-outlined /></template>
    {{ $t('label.actions') }}
  </a-button>
</a-dropdown>
```

`DrResourceContextMenu.vue` skeleton:

```vue
<div
  v-if="visible"
  class="quickview-context-menu cross-dr-context-menu"
  :style="{ top: position.y + 'px', left: position.x + 'px' }"
  @click.stop
  @contextmenu.stop.prevent>
  <action-button
    :actions="visibleActions"
    :resource="resource"
    :dataView="true"
    :show-resource-title="true"
    :titleOverride="title"
    @exec-action="$emit('exec-action', $event, resource)" />
</div>
```

`DrSiteList.vue` 적용:

- `columns`에서 `{ key: 'actions', ... }`를 제거한다.
- 상세 상단의 `사이트 점검` 단독 버튼을 제거하고 `DrResourceActionMenu`로 대체한다.
- 목록 table wrapper에 `@contextmenu="openSiteContextMenu"`를 연결한다.
- 상세 overview/right panel에 `@contextmenu.stop.prevent="openDetailContextMenu($event, detailSite)"`를 연결한다.
- `executeSiteAction(action, site)`는 `action.actionKind` 기준으로 `checkSite`, `openEditSiteModal`, `confirmDeleteSite`를 분기한다.
- `DrFormModal`은 `mode: 'create' | 'edit'`를 받고, edit mode에서는 secret 필드를 비워 둔다. secret 입력이 모두 비어 있으면 기존 credential을 유지한다.

`DrPlanList.vue` 적용:

- `columns`에서 `{ key: 'actions', ... }`를 제거하고 `DrActionToolbar` row slot 사용을 중단한다.
- 기존 `DrActionToolbar`의 action 배열은 `resourceActions.js`로 이동하거나 wrapper로 흡수한다.
- 상세 상단 `작업` 드롭다운은 `DrResourceActionMenu` 하나만 사용한다.
- 목록 row와 상세 panel에 `DrResourceContextMenu`를 붙인다.
- `executePlanAction(action, plan)`은 `edit-plan`, `delete-plan`, `runtime`, `runtime-modal`로 분기한다.
- `runtime-modal`은 기존 failover/failback/release 확인 modal을 재사용한다.

표준 동작 제약:

- 목록의 오른쪽 끝 `작업` 컬럼은 만들지 않는다.
- 생성 버튼만 목록 상단 toolbar의 primary round button으로 유지한다.
- row 단위 action은 우클릭 컨텍스트 메뉴와 상세 `작업` 드롭다운에서 제공한다.
- 상세 정보 panel의 우클릭 메뉴는 현재 상세 리소스 기준으로 동작한다.
- action visibility는 UI에서 1차 필터링하되, update/delete 가능 여부는 backend가 최종 검증한다.

### 4.3 DR Plans 목록

목적은 운영자가 보호 상태와 위험 대상을 빠르게 찾는 것이다.

주요 컬럼:

| 컬럼 | 값 |
| --- | --- |
| Protected VM | source VM name, instance name |
| Direction | `KVM_TO_KVM`, `KVM_TO_VMWARE`, `VMWARE_TO_VMWARE`, `VMWARE_TO_KVM` |
| Source Site | source `DrSite.name` |
| Target Site | target `DrSite.name` |
| Plan State | `DrPlan.state` |
| Replica State | `DrReplica.state` |
| Target Ready | latest `DrRestorePoint.state == TARGET_READY` 여부 |
| RPO | source restore point age와 target-ready restore point age를 분리 표시 |
| Last Run | 최근 `DrRun.type`, result, duration |
| Actions | Sync, Test, Failover, Pause/Resume, Delete |

목록 필터:

- Direction
- Source site
- Target site
- Plan state
- Replica state
- Target ready 여부
- RPO threshold 초과 여부
- 마지막 작업 실패 여부

정렬 우선순위:

1. `ERROR`, `DEGRADED`, RPO 초과
2. `SYNCING`, `MATERIALIZING`
3. `READY`
4. `PAUSED`, `DISABLED`

### 4.4 DR Plan 생성 wizard

Wizard는 긴 단일 form이 아니라 단계별 validation과 preflight를 갖는다.

| 단계 | 이름 | 입력/출력 | Validation |
| --- | --- | --- | --- |
| 1 | Source | source site, source VM | VM 상태, snapshot 가능 여부, disk inventory |
| 2 | Target | target site, direction | source/target hypervisor 조합 지원 여부 |
| 3 | Storage Mapping | source disk별 target datastore/pool | 용량, 포맷 변환 가능 여부, thin/thick 정책 |
| 4 | Network Mapping | NIC별 target network | VLAN/portgroup/network offering 매핑 |
| 5 | Policy | RPO 목표, schedule, retention, quiesce, test network, fencing policy | 정책 범위, 충돌 여부 |
| 6 | Preflight | adapter check 결과 | 모든 hard blocker 해소 필요 |
| 7 | Review | 생성 요약, 초기 sync 여부 | destructive action 없음 |

Wizard UX 규칙:

- 단계 이동 시 서버 preflight가 필요한 항목은 async job 결과까지 확인한다.
- "다음" 버튼은 hard blocker가 있으면 비활성화하고 tooltip으로 이유를 표시한다.
- warning은 계속 진행 가능하지만 review 단계에 누적 표시한다.
- VMware target의 경우 datastore/network mapping이 없으면 생성할 수 없다.
- KVM to KVM FTCTL engine의 경우 기존 보호 등록 조건과 충돌하면 생성할 수 없다.
- 다크모드에서 stepper, warning list, preflight result table이 배경과 구분되어야 한다.

초기 구현에서 full wizard 대신 `DrPlanList.vue`의 추가 대화상자를 사용하는 경우에도 같은 정보 구조를 유지한다.

| 그룹 | 필드 |
| --- | --- |
| 기본 정보 | 이름, 설명 |
| 사이트/방향 | 원본 사이트, 대상 사이트, 방향 |
| 보호 대상 | 원본 VM 또는 source external ref |
| 복구 목표 | RPO, RTO, 생성 후 동기화 시작 |
| 고급 엔진 설정 | 기본 사용자 흐름에서는 숨김. worker host와 mapping/schedule/policy/quiesce는 선택형 UI와 backend-generated spec으로 처리 |

고급 엔진 설정은 운영자가 항상 입력해야 하는 값처럼 노출하지 않는다. 기본 DR 생성 흐름에서는 `discoverDrPlanInventory`와 `previewDrPlanSpec` 결과를 기반으로 backend가 canonical `mapping_json`, `schedule_json`, `policy_json`, `quiesce_policy_json`을 생성한다. raw JSON은 일반 입력 항목이 아니라 expert preview/override 영역으로만 남긴다. 상세 기준은 [531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md](531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md)를 따른다.

### 4.5 DR Plan 상세

DR Plan 상세도 볼륨 상세 화면을 표준으로 삼는다. 좌측에는 plan identity와 핵심 상태를 표시하는 정보 카드, 우측에는 row 기반 `상세` 탭과 DR 전용 runtime 탭을 둔다. 기존 KPI/topology는 유지할 수 있지만, 첫 화면의 기본 상세 정보는 `a-descriptions bordered` 테이블이 아니라 표준 row 목록으로 표시한다.

좌측 정보 카드:

| 영역 | 내용 |
| --- | --- |
| Identity | plan name, source VM, direction |
| State | plan state, replica state, active side |
| RPO | source latest age, target-ready latest age |
| Current Run | run type, current step, progress percent |
| Risk | last error, stale projection, fence required |

상세 탭:

| 탭 | 내용 |
| --- | --- |
| 상세 | id, name, description, direction, active side, source/target site, source VM, worker host, RPO/RTO, checkpoint, created |
| 복구 지점 | restore point 목록, target materialization 상태 |
| 복제본 | target VM/disk/network projection, bootability |
| 실행 이력 | sync/test/failover/failback/reprotect run history |
| 이벤트 | engine events, adapter events, Cloud async job 결과 |
| 설정 | policy, mapping, retention, notification |

구현 기준:

- `DrPlanList.vue`의 detail branch는 `ResourceLayout`을 유지하되, 좌측 custom `cross-dr-info-card` DOM을 `DrResourceInfoCard`로 교체한다.
- `DrPlanOverview.vue`의 `a-descriptions bordered`는 제거한다.
- `DrPlanOverview.vue`는 이름을 유지하더라도 내부는 `DrResourceDetailsTab` row 목록과 KPI/진행률 섹션 조합으로 바꾼다. 장기적으로는 `DrPlanDetailsTab.vue`로 분리한다.
- 탭 key는 `overview` 대신 `details`를 기본으로 한다. 기존 URL 이력 호환이 필요하면 `overview` 입력 시 `details`로 normalize한다.
- topology, KPI, current run progress는 row 목록 아래 보조 섹션으로 배치하되, card 안에 card를 넣지 않는다.

### 4.6 VM 상세 Disaster Recovery 탭

VM 상세 탭은 운영자가 특정 VM에서 바로 보호 상태를 확인하기 위한 projection 화면이다.

표시 내용:

- 연결된 `DrPlan`이 없으면 생성 CTA를 제공한다.
- 연결된 `DrPlan`이 있으면 plan summary, RPO, target readiness, 최근 run, 주요 액션을 표시한다.
- FTCTL 기반 plan은 기존 Fault Tolerance 탭으로 이동할 수 있는 링크를 제공한다.
- VM이 stopped, destroyed, expunging 상태면 보호 생성 또는 sync action을 비활성화한다.

기존 `Fault Tolerance` 탭은 FTCTL runtime 진단과 cloud-managed action 중심으로 유지한다. 신규 DR 탭은 DR orchestrator 관점의 plan 중심 화면으로 둔다.

## 5. 액션 설계

### 5.1 공통 액션 툴바

액션은 아이콘과 텍스트를 함께 사용한다. 비활성 상태는 disabled button만 두지 않고 tooltip 또는 inline reason을 제공한다.

| 액션 | 조건 | 결과 |
| --- | --- | --- |
| Sync Now | `ENABLED`, `READY`, `ERROR` 일부 복구 가능 상태 | `DrRun(type=SYNC)` 생성 |
| Test Failover | target-ready restore point 존재, test network mapping 존재 | 격리된 target test VM 또는 test mode 실행 |
| Failover | target-ready restore point 존재, hard blocker 없음 | `DrRun(type=FAILOVER)` 생성 |
| Confirm Fence Clear | manual fencing required 상태 | fence clear 확인 run 또는 FTCTL fence API 호출 |
| Failback | failed-over 후 source 복구 확인, failback 지원 direction | `DrRun(type=FAILBACK)` 생성 |
| Reprotect | failover/adopt 이후 새 source/target 확정 | `DrRun(type=REPROTECT)` 생성 |
| Pause | active sync 중단 가능 | plan state `PAUSED` |
| Resume | `PAUSED` | plan state 복구 및 sync 재개 |
| Delete | active run 없음 | plan 제거 또는 보호 해제 |

### 5.2 상태별 액션 게이트

| Plan State | 허용 액션 | 비활성 사유 예시 |
| --- | --- | --- |
| `CREATED` | Edit, Delete, Enable | preflight 미완료 |
| `ENABLED` | Sync Now, Pause, Delete | target-ready restore point 없음 |
| `SYNCING` | View Run, Pause 일부 | 진행 중인 run 존재 |
| `READY` | Sync Now, Test Failover, Failover, Pause, Delete | hard blocker 없음 |
| `TESTING` | Stop Test, View Run | test failover 종료 필요 |
| `FAILED_OVER` | Failback, Reprotect, Adopt, View Run | source 복구 확인 필요 |
| `FAILBACK_READY` | Failback, Reprotect | manual fence clear 필요 |
| `REPROTECTING` | View Run | reprotect 진행 중 |
| `PAUSED` | Resume, Delete | plan 일시 중지 |
| `ERROR` | Retry, Sync Now 일부, Delete | 원인 확인 필요 |

위험 액션:

- Failover, Failback, Adopt, Delete, forced cleanup은 confirm modal을 사용한다.
- confirm modal은 예상 영향, source/target controller, 선택한 restore point, 네트워크 격리 여부를 표시한다.
- 재해 상황의 `Adopt/Promote`와 source controller가 살아 있는 `Failback`은 같은 버튼으로 합치지 않는다.

## 6. 진행률과 이벤트

### 6.1 진행률 표시

진행 중인 `DrRun`은 다음 계층으로 표시한다.

| 계층 | 예시 |
| --- | --- |
| Run summary | `SYNC`, `MATERIALIZE`, `FAILOVER`, `FAILBACK` |
| Step progress | snapshot, transfer, convert, register target VM, preflight, power on |
| Engine detail | FTCTL blockcopy, V2K conversion, VMware snapshot export |
| Runtime evidence | async job id, host task id, qemu/ftctl event id |

진행률 원칙:

- backend가 percent를 제공하지 못하면 가짜 percent를 만들지 않는다.
- 단계 기반 진행률은 `currentStep / totalStep`과 "estimated" 표시를 분리한다.
- refresh 중에는 기존 데이터를 지우지 않고 작은 loading indicator만 표시한다.
- 마지막 성공 데이터와 현재 refresh 실패를 분리해서 표시한다.

### 6.2 이벤트 표시

Event tab은 사람이 읽을 수 있는 event stream을 제공한다.

필드:

- time
- severity
- source: `CLOUD`, `FTCTL`, `VMWARE`, `V2K`, `MOLD_AGENT`
- run id
- step id
- message
- raw reference id

표시 원칙:

- raw log 전체를 기본 노출하지 않는다.
- 상세 drawer에서 raw payload를 접힌 상태로 제공한다.
- 다크모드에서 code block과 raw payload 영역은 별도 token을 사용한다.

## 7. API 연동 설계

UI는 host 또는 qemu engine을 직접 호출하지 않는다. 모든 동작은 Cloud API를 통해 backend로 전달한다.

권장 API 매핑:

| UI 동작 | Cloud API |
| --- | --- |
| DR site 목록 | `listDrSites` |
| DR site 생성 | `createDrSite` |
| DR site 수정 | `updateDrSite` |
| DR site 삭제 | `deleteDrSite` |
| DR site check | `checkDrSite` |
| DR plan 목록 | `listDrPlans` |
| DR plan 생성 | `createDrPlan` |
| DR plan 수정 | `updateDrPlan` |
| DR plan 삭제 | `deleteDrPlan` |
| 수동 sync | `startDrSync` |
| test failover | `startDrTestFailover` |
| failover | `startDrFailover` |
| failback | `startDrFailback` |
| reprotect | `startDrReprotect` |
| run 조회 | `listDrRuns`, `getDrRun` |
| restore point 조회 | `listDrRestorePoints` |
| replica 조회 | `listDrReplicas` |
| event 조회 | `listDrEvents` |

Async job 처리:

- action API가 `jobid`를 반환하면 UI는 job 완료까지 추적한다.
- job 성공 후에도 `DrRun` 또는 plan projection을 다시 조회해 최종 상태를 확인한다.
- job 실패는 modal close나 button loading 해제만으로 끝내지 않고 action result alert에 표시한다.
- polling timeout은 실패로 단정하지 않고 "상태 확인 필요"로 표시한 뒤 수동 refresh를 제공한다.
- `deleteDrSite`, `deleteDrPlan`은 `BaseAsyncCmd`이므로 최초 응답의 `jobid`를 추출하고 `$pollJob`으로 완료를 기다린다.
- 삭제 성공 toast는 async job 성공과 active 목록 refresh 이후에만 표시한다. 최초 응답의 `success` object만으로 삭제 성공을 표시하지 않는다.
- 삭제 job 실패 시 async job `errortext`를 notification에 표시하고 row는 그대로 유지한다.
- 삭제 이후 site 상세 화면에서 새 `checkDrSite`를 호출하지 않는다. 상세 화면이 삭제된 resource를 보고 있으면 목록 화면으로 이동한다.

## 8. 다크모드 및 스타일 상세 설계

### 8.1 필수 원칙

다크모드 대응은 신규 UI의 필수 완료 조건이다. 구현 완료 판단 시 라이트모드만 확인해서는 안 된다.

구현 원칙:

- 색상은 hardcoded hex를 최소화하고 Ant Design Vue theme token, `color.less`, semantic class를 우선 사용한다.
- mode별 차이가 필요한 경우 `$store.getters.darkMode` 또는 `body.dark-mode .cross-dr-*` selector를 사용한다.
- 동일한 상태는 라이트/다크모드에서 같은 의미 색을 유지하되 대비와 채도만 조정한다.
- 상태 배지, alert, progress, table hover, selected row, disabled button, empty state는 다크모드 개별 검증 항목으로 둔다.
- 다크모드에서 순수 검정 배경 위 순수 흰색 텍스트만 사용하는 단조로운 화면을 만들지 않는다.
- 경고/오류 영역은 배경색보다 border, icon, title, secondary text의 계층으로 읽히게 한다.

### 8.2 Semantic style token

구현 시 다음 semantic token 또는 CSS custom property를 둔다.

| Token | Light 예시 | Dark 예시 | 용도 |
| --- | --- | --- | --- |
| `--cross-dr-surface` | page/card base | elevated dark surface | summary, panel |
| `--cross-dr-surface-muted` | subtle section bg | subtle dark section bg | KPI group, empty state |
| `--cross-dr-border` | neutral border | low contrast dark border | table, panel |
| `--cross-dr-text` | primary text | primary dark text | 제목, 값 |
| `--cross-dr-text-secondary` | secondary text | secondary dark text | 설명, timestamp |
| `--cross-dr-success` | success | success dark adjusted | READY, OK |
| `--cross-dr-warning` | warning | warning dark adjusted | DEGRADED, RPO lag |
| `--cross-dr-error` | error | error dark adjusted | ERROR, FAILED |
| `--cross-dr-info` | info | info dark adjusted | SYNCING, RUNNING |
| `--cross-dr-progress-track` | track bg | dark track bg | progress bar |
| `--cross-dr-code-bg` | raw payload bg | dark raw payload bg | event raw/detail |

이 token은 구현 시 실제 프로젝트의 theme 변수와 맞춰 정의한다. 문서의 예시값은 고정값이 아니라 역할을 설명하기 위한 것이다.

### 8.2.1 Modal dark-mode token

DR 모달은 page root 밖에 렌더링될 수 있으므로 `body.dark-mode .cross-dr-page`만으로는 충분하지 않다. 공통 form modal에는 별도 scope를 둔다.

```less
body.dark-mode .cross-dr-modal {
  --cross-dr-text: rgba(255, 255, 255, 0.86);
  --cross-dr-text-secondary: rgba(255, 255, 255, 0.68);
  --cross-dr-border: rgba(255, 255, 255, 0.16);
}
```

필수 override:

- `.cross-dr-modal .ant-form-item-label > label`
- `.cross-dr-modal .ant-divider-inner-text`
- `.cross-dr-modal .ant-divider::before`, `.cross-dr-modal .ant-divider::after`
- `.cross-dr-modal .ant-input`, `.ant-input-affix-wrapper`, `.ant-input-number`
- `.cross-dr-modal .ant-select:not(.ant-select-customize-input) .ant-select-selector`
- `.cross-dr-modal .ant-input-password-icon`
- `.cross-dr-modal .ant-select-selection-placeholder`, `.ant-input::placeholder`

특히 `사이트 접속 정보` 같은 divider 텍스트는 다크모드에서 검정에 가깝게 보이면 안 된다.

### 8.3 Component class 제안

신규 UI에는 다음 class prefix를 사용한다.

| Class | 용도 |
| --- | --- |
| `.cross-dr-page` | DR page root |
| `.cross-dr-toolbar` | 목록/상세 상단 액션 영역 |
| `.cross-dr-summary` | 상세 상단 summary band |
| `.cross-dr-kpi` | RPO, readiness, run duration KPI |
| `.cross-dr-status-pill` | 상태 배지 |
| `.cross-dr-risk` | warning/error callout |
| `.cross-dr-topology` | source to target topology |
| `.cross-dr-run-progress` | run progress wrapper |
| `.cross-dr-run-step` | run step row |
| `.cross-dr-event-log` | event stream |
| `.cross-dr-code` | raw payload/code 영역 |
| `.cross-dr-modal` | DR Site/Plan/Action 공통 modal root |
| `.cross-dr-modal__scroll` | modal body 내부 스크롤 영역 |
| `.cross-dr-modal__footer` | modal footer button 정렬 영역 |

`body.dark-mode .cross-dr-page` 하위에서 필요한 override를 정의한다. 컴포넌트 내부에서 mode별 inline style을 남발하지 않는다.

### 8.4 화면별 다크모드 검증 기준

| 화면 | 검증 기준 |
| --- | --- |
| DR Sites 목록 | table header, row hover, health badge, disconnected reason이 모두 읽힘 |
| DR Site 상세 | endpoint, credential alias, inventory table, event raw payload 대비 확보 |
| DR Plans 목록 | state badge, RPO 초과 warning, action disabled reason tooltip 가독성 |
| DR Plan Wizard | stepper, validation error, preflight warning/error, review summary 대비 확보 |
| DR Plan 상세 | summary band, KPI, topology, progress, risk callout의 계층 구분 |
| VM 상세 DR 탭 | 기존 Compute 화면과 배경/간격/텍스트 톤 일관성 |
| Confirm Modal | 위험 액션 제목, 영향 범위, checkbox/typed confirmation이 명확히 보임 |
| DR Site/Plan 추가 Modal | header/footer 고정, content-only scroll, divider/label/input 대비 확보 |

### 8.5 상태 색상 규칙

| 상태 | 의미 색 | UI 표현 |
| --- | --- | --- |
| `READY`, `OK`, `TARGET_READY` | success | 배지 + 짧은 보조 텍스트 |
| `SYNCING`, `MATERIALIZING`, `RUNNING` | info | progress + 현재 단계 |
| `DEGRADED`, `RPO_EXCEEDED`, `STALE` | warning | warning callout + 해결 힌트 |
| `ERROR`, `FAILED` | error | error callout + retry 가능 여부 |
| `PAUSED`, `DISABLED`, `UNKNOWN` | neutral | neutral badge + 다음 액션 안내 |

색상만으로 상태를 구분하지 않는다. 상태 label, icon, tooltip, 보조 문구를 함께 제공한다.

## 9. 반응형 레이아웃

Desktop:

- 목록 화면은 table 중심으로 구성한다.
- 상세 화면은 상단 summary band, 아래 tab content 구조를 사용한다.
- KPI는 4개에서 6개까지 한 줄 배치하되 좁아지면 wrap한다.
- topology는 full-width 영역으로 두고 decorative card 안에 넣지 않는다.

Tablet/Mobile:

- 목록 table은 주요 컬럼만 남기고 row expand로 상세 정보를 제공한다.
- 액션 툴바는 primary action 1개와 overflow menu로 축약한다.
- wizard는 단계 목록을 상단 compact stepper로 표시한다.
- 긴 VM 이름, datastore 이름, network 이름은 줄바꿈 가능해야 하며 버튼 너비를 밀어내면 안 된다.

텍스트 오버플로우:

- 버튼은 icon + 짧은 label을 사용하고 긴 설명은 tooltip/drawer로 보낸다.
- table cell은 `ellipsis`를 기본으로 하되 중요한 error는 두 줄까지 허용한다.
- card 또는 panel 안에서 hero-scale type을 사용하지 않는다.

## 10. 접근성 및 i18n

접근성:

- icon-only button은 tooltip과 `aria-label`을 제공한다.
- keyboard focus outline은 라이트/다크모드 모두에서 보여야 한다.
- destructive modal은 Enter 오작동을 줄이기 위해 명시적 confirm control을 둔다.
- progress는 색상 외에도 percent, step label, status text를 함께 표시한다.

i18n:

- enum raw value만 표시하지 않고 locale key를 둔다.
- 한국어/영어 label을 모두 추가할 수 있는 key 구조를 사용한다.
- action disabled reason도 locale key로 관리한다.
- site type, direction, engine type은 value와 label을 분리한다. payload에는 enum value를 보내고 화면에는 locale label을 표시한다.

권장 locale key prefix:

- `label.dr.site.*`
- `label.dr.plan.*`
- `label.dr.restorePoint.*`
- `label.dr.replica.*`
- `label.dr.run.*`
- `label.dr.action.*`
- `message.dr.*`

추가 locale key 예시:

| Key | ko_KR | en |
| --- | --- | --- |
| `label.dr.site.type.mold.kvm` | ABLESTACK KVM | ABLESTACK KVM |
| `label.dr.site.type.mold.vmware` | ABLESTACK VMware | ABLESTACK VMware |
| `label.dr.site.type.vmware.direct` | VMware 직접 연결 | VMware Direct |
| `label.dr.site.connection.info` | 사이트 접속 정보 | Site connection information |
| `label.dr.site.advanced.settings` | 고급 설정 | Advanced settings |
| `label.dr.vcenter.tls.verify` | vCenter 인증서 검증 | Verify vCenter certificate |
| `label.dr.mold.tls.verify` | Mold API 인증서 검증 | Verify Mold API certificate |
| `label.dr.basic.info` | 기본 정보 | Basic information |
| `label.dr.protection.target` | 보호 대상 | Protection target |
| `label.dr.recovery.objective` | 복구 목표 | Recovery objective |
| `label.dr.advanced.engine.settings` | 고급 엔진 설정 | Advanced engine settings |
| `message.dr.site.delete.confirm` | 이 DR 사이트를 삭제하시겠습니까? | Delete this DR site? |
| `message.dr.site.delete.blocked.by.plan` | 연결된 DR 계획이 있어 삭제할 수 없습니다. | This DR site is used by a DR plan. |
| `message.dr.plan.delete.confirm` | 이 DR 계획을 삭제하시겠습니까? | Delete this DR plan? |
| `message.dr.plan.update.blocked.by.run` | 실행 중인 작업이 있어 수정할 수 없습니다. | This DR plan has an active run. |
| `message.dr.plan.delete.blocked.by.protection` | 보호 또는 실행 중인 작업이 있어 삭제할 수 없습니다. 먼저 보호 해제를 수행하십시오. | Release protection before deleting this DR plan. |

## 11. 에러와 빈 상태

빈 상태:

- DR site가 없으면 site 등록 CTA를 표시한다.
- DR plan이 없으면 plan 생성 CTA를 표시한다.
- target-ready restore point가 없으면 failover 버튼은 비활성화하고 필요한 다음 작업을 보여준다.

에러 상태:

- adapter connection error와 Cloud async job failure를 구분한다.
- projection stale 상태는 "실제 보호 실패"로 단정하지 않고 "최근 상태 확인 실패"로 표시한다.
- run 실패는 실패 step, 원인, 재시도 가능 여부, 관련 event를 함께 보여준다.

## 12. 구현 단계 제안

UI 구현은 다음 순서로 진행한다.

| 단계 | 작업 | 완료 기준 |
| --- | --- | --- |
| 1 | route/resource config 추가 | menu에서 DR Sites/Plans 접근 가능 |
| 2 | API wrapper 추가 | list/create/update/delete/check/action API 호출 가능 |
| 3 | DR 표준 action model | `resourceActions.js`, `DrResourceActionMenu`, `DrResourceContextMenu`로 상세 작업 드롭다운과 목록/상세 우클릭 제공 |
| 4 | DR Plans 목록 | 필터, 상태 배지, RPO, action disabled reason 표시. 오른쪽 `작업` 컬럼 없음 |
| 5 | DR Plan 상세 overview | summary, topology, readiness, current run 표시. 상단 `작업` 드롭다운과 상세 panel 우클릭 제공 |
| 6 | DR 공통 form modal | DR Site/Plan/Action modal의 header/footer 고정, content-only scroll, dark-mode 검증 |
| 7 | run/restore/event tabs | progress, history, raw reference 표시 |
| 8 | VM 상세 DR 탭 | VM에서 plan projection과 주요 액션 표시 |
| 9 | 다크모드 polish | 모든 신규 화면 라이트/다크 스크린샷 검증 |
| 10 | 기존 FTCTL 연계 | FTCTL 기반 plan에서 기존 FTCTL 탭 링크 및 상태 projection 검증 |
| 11 | DR Plan wizard 고도화 | source/target/mapping/policy/preflight/review 단계 구현 |

## 13. 테스트 기준

수동 검증:

- 라이트모드와 다크모드에서 DR Sites 목록/상세가 모두 정상 표시된다.
- 라이트모드와 다크모드에서 DR Plans 목록/상세/wizard가 모두 정상 표시된다.
- DR Site/DR Plan/Action 대화상자는 화면 안에서 header와 footer가 고정되고 본문만 스크롤된다.
- VMware Direct DR Site 추가 화면은 하이퍼바이저, endpoint, Zone, VMware 데이터센터를 기본 입력값으로 노출하지 않는다.
- `VMWARE_DIRECT`, `MOLD_KVM`, `KVM_TO_VMWARE`, `FTCTL_DR` 같은 enum 원문이 사용자-facing label로 직접 노출되지 않는다.
- DR Site/DR Plan 목록에는 row 단위 `작업` 컬럼이 없다.
- DR Site/DR Plan 목록 row 우클릭 시 해당 row 기준 작업 메뉴가 열린다.
- DR Site/DR Plan 상세 우측 상단에는 볼륨 상세와 같은 `작업` 드롭다운 하나만 표시된다.
- DR Site/DR Plan 상세 panel 우클릭 시 현재 상세 리소스 기준 작업 메뉴가 열린다.
- DR Site 작업 메뉴에는 점검, 수정, 삭제가 표시되고, 연결된 plan이 있는 site 삭제는 비활성화되거나 backend 오류가 명확히 표시된다.
- DR Plan 작업 메뉴에는 수정, 삭제와 sync/failover/failback/reprotect/release 계열 실행 액션이 같은 메뉴에 표시된다.
- `READY` plan에서 failover action이 활성화되고, target-ready restore point가 없으면 비활성화된다.
- async job 실패 시 UI가 성공으로 오인하지 않고 실패 alert를 표시한다.
- refresh 중 기존 화면 데이터가 전체적으로 사라지지 않는다.
- 기존 `Fault Tolerance` 탭은 신규 UI 추가 후에도 동작과 layout이 유지된다.
- 모바일 폭에서 action button, 긴 VM 이름, error message가 서로 겹치지 않는다.

자동 검증:

- 주요 컴포넌트의 action gating helper unit test를 추가한다.
- status to color/label mapping unit test를 추가한다.
- API response mock 기반 목록/상세 rendering test를 추가한다.
- 가능하면 Playwright로 라이트/다크모드 screenshot smoke를 추가한다.

## 14. AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| UI 모델 | `DisasterRecoveryCluster`, FTCTL 탭 중심 | `DrSite`, `DrPlan`, `DrRestorePoint`, `DrReplica`, `DrRun` 중심 |
| 보호 단위 | 기존 DR cluster 또는 FTCTL protection에 종속 | VM 단위 `DrPlan`으로 통합 |
| 진행률 | 기능별 개별 표현 | `DrRun`/`DrRunStep` 기반 공통 진행률 |
| 장애 전환 판단 | 기능별 화면을 따로 확인 | target-ready restore point, replica readiness, RPO를 한 화면에서 확인 |
| 액션 게이트 | 화면별 개별 조건 | `DrPlan.state`, `DrReplica.state`, restore point readiness 기반 공통 게이트 |
| 목록 row 작업 | 오른쪽 끝 `작업` 컬럼 또는 개별 icon button | row 우클릭 컨텍스트 메뉴, 생성 버튼만 toolbar 유지 |
| 상세 작업 | 기능별 단독 버튼 또는 DR 전용 toolbar | 볼륨 상세와 같은 상단 `작업` 드롭다운 하나로 통합 |
| 수정/삭제 | API는 있으나 UI action 연결 누락 가능 | DR Site/Plan 모두 수정/삭제 action을 노출하고 backend guard로 최종 검증 |
| FTCTL 연계 | FTCTL 탭이 직접 운영 중심 | FTCTL은 기존 탭 유지, 신규 DR UI에서는 engine projection |
| VMware 연계 | 공통 DR UI 없음 | VMware source/target도 같은 DR plan UX로 표시 |
| 다크모드 | 화면별 부분 대응 | 신규 DR UI 전체에서 token/selector 기반 필수 대응 |
| 대화상자 레이아웃 | form 안쪽 버튼과 전체 화면 스크롤 | 공통 modal header/footer 고정, 본문만 내부 스크롤 |
| Site 입력 | endpoint, hypervisor, Zone, VMware DC가 기본 입력으로 섞임 | site type별 필수 접속 정보만 기본 노출, 나머지는 자동값 또는 고급 설정 |
| 사용자 라벨 | enum raw value 노출 가능 | i18n label과 payload enum value 분리 |
| 비동기 결과 | 클릭/API 성공으로 오해 가능 | async job + `DrRun` + projection 재조회로 최종 상태 확인 |

## 15. 미결정 사항

- 기존 `DisasterRecoveryCluster` 메뉴를 신규 `DR Sites`로 언제 통합할지 결정이 필요하다.
- `DrRun` progress percent를 backend가 직접 제공할지, step 기반 estimated progress로만 표시할지 결정이 필요하다.
- VMware target test failover에서 격리 네트워크를 필수로 강제할지 정책 결정이 필요하다.
- mobile에서 DR Plans 목록을 table 축약형으로 둘지 card list로 전환할지 UX 검증이 필요하다.
- 다크모드 token을 전역 `color.less`에 추가할지, DR UI scoped style로 시작할지 구현 단계에서 결정한다.

## 16. 2026-07-02 구현 반영: 표준 작업 메뉴

이번 구현에서는 DR Site/Plan의 목록 row 오른쪽 `작업` 컬럼을 제거하고, 볼륨 목록/상세와 같은 action 모델로 정리했다.

| 영역 | 구현 파일 | 구현 내용 |
| --- | --- | --- |
| 공통 action 정의 | `ui/src/utils/dr/resourceActions.js` | `check/update/delete` site action과 plan edit/delete/runtime action을 `ActionButton` 계약(`listView`, `dataView`, `show`, `disabled`)으로 정의 |
| 상세 작업 버튼 | `ui/src/components/dr/DrResourceActionMenu.vue` | 상세 우측 상단 `작업` 드롭다운. 내부는 표준 `ActionButton(dataView=true)` 사용 |
| 우클릭 메뉴 | `ui/src/components/dr/DrResourceContextMenu.vue` | 목록 row와 상세 panel 우클릭 메뉴. 표준 `quickview-context-menu` 스타일과 `ActionButton(dataView=true)` 사용 |
| DR Site 화면 | `ui/src/views/infra/dr/DrSiteList.vue` | 점검/수정/삭제를 상세 `작업` 드롭다운과 목록/상세 우클릭에서 제공. 수정 모드는 secret 원문을 표시하지 않고 새 인증정보 전체 입력 시에만 교체 |
| DR Plan 화면 | `ui/src/views/infra/dr/DrPlanList.vue` | 수정/삭제와 sync/failover/failback/reprotect/release 계열 action을 같은 메뉴에 제공. 목록에서는 current run id가 없으므로 `cancel run`은 상세 화면에서만 실행 가능 |

삭제/수정 UX 기준:

- `deleteDrSite`는 `activeplancount > 0`이면 UI에서 비활성화하고, backend가 최종 검증한다.
- `deleteDrPlan`은 active run, replica, restore point, target-ready/protected state가 남아 있으면 backend가 거부한다.
- `updateDrPlan`은 active run이 있으면 거부하고, runtime resource가 생긴 이후에는 source/engine/worker/mapping 변경을 거부한다.
- `deleteDrSite/deleteDrPlan` 실행 중에는 해당 action을 loading/disabled 처리하여 중복 요청을 막는다.
- UI 로케일은 `label.dr.site.edit/delete`, `label.dr.plan.edit/delete`, `message.dr.confirm.delete.site/plan`, `label.dr.action.pause/resume/release` 계열을 영문/한글 모두 추가한다.

## 17. 2026-07-02 상세 화면 표준화 상세 설계

### 17.1 문제 원인

현재 DR 상세 화면은 볼륨 상세와 다른 렌더링 경로를 사용한다.

| 화면 | AS-IS 코드 | 문제 |
| --- | --- | --- |
| DR Site 상세 좌측 | `DrSiteList.vue` detail branch의 `a-card.cross-dr-info-card` | 표준 `InfoCard.vue`의 `vm-info-card`, `resource-details`, `resource-detail-item` class 계약을 따르지 않음 |
| DR Site 상세 우측 | `DrSiteList.vue`의 `a-descriptions bordered` | 볼륨 상세의 row 목록이 아니라 흰색 bordered table로 표시됨 |
| DR Plan 상세 좌측 | `DrPlanList.vue` detail branch의 `a-card.cross-dr-info-card` | 표준 좌측 상세 카드와 간격, 텍스트, 우클릭 영역이 다름 |
| DR Plan 상세 우측 | `DrPlanOverview.vue`의 `a-descriptions bordered` | 다크모드에서 Ant Design 기본 label/content 배경이 남아 대비가 깨짐 |
| Dark mode | `cross-dr.less`의 custom token 중심 override | `ant-descriptions-bordered` 셀 스타일을 충분히 덮지 못함 |

따라서 단순히 `.ant-descriptions` 색상만 override하지 않는다. 상세 정보 표시 구조 자체를 볼륨 상세 표준에 맞춘다.

### 17.2 TO-BE 컴포넌트 구조

DR Site/Plan 상세는 다음 구조를 기준으로 한다.

```vue
<resource-layout>
  <template #left>
    <dr-resource-info-card
      :resource="detailResource"
      :resource-type="resourceType"
      :summary-fields="summaryFields"
      :loading="loading"
      @contextmenu="openDetailContextMenu" />
  </template>

  <template #right>
    <a-card
      class="spin-content"
      :loading="loading"
      :bordered="true"
      style="width: 100%"
      @contextmenu.stop.prevent="openDetailContextMenu($event, detailResource)">
      <a-tabs
        style="width: 100%; margin-top: -12px"
        :activeKey="activeTab"
        :animated="false"
        @change="changeTab">
        <a-tab-pane key="details" :tab="$t('label.details')">
          <dr-resource-details-tab
            :resource="detailResource"
            :fields="detailFields" />
        </a-tab-pane>
        ...
      </a-tabs>
    </a-card>
  </template>
</resource-layout>
```

신규 또는 보강 대상:

| 파일 | 역할 |
| --- | --- |
| `ui/src/components/dr/DrResourceInfoCard.vue` | DR Site/Plan 좌측 상세 카드. 표준 `InfoCard.vue` class 구조를 따른다. |
| `ui/src/components/dr/DrResourceDetailsTab.vue` | DR Site/Plan 우측 `상세` 탭의 label/value row 목록. `a-descriptions`를 쓰지 않는다. |
| `ui/src/views/infra/dr/DrSiteList.vue` | detail branch에서 custom card와 `a-descriptions` 제거, field metadata computed 추가 |
| `ui/src/views/infra/dr/DrPlanList.vue` | detail branch에서 custom card 제거, 기본 탭 key를 `details`로 변경 |
| `ui/src/views/infra/dr/DrPlanOverview.vue` | `a-descriptions bordered` 제거. KPI/progress 보조 섹션만 유지하거나 `DrPlanDetailsTab.vue`로 분리 |
| `ui/src/style/cross-dr.less` | DR 전용 `a-descriptions` 보정은 임시 fallback만 남기고, 표준 row/list 스타일 우선 적용 |

### 17.3 `DrResourceInfoCard.vue` 설계

목표는 공통 `InfoCard.vue`를 직접 수정하지 않고도 볼륨 상세와 같은 시각 계약을 DR Site/Plan 좌측 패널에 적용하는 것이다. 단순히 `vm-info-card`, `resource-details`, `resource-detail-item` class 이름만 붙이는 방식은 충분하지 않다. `InfoCard.vue`의 핵심 style은 component scope 안에 있으므로 DR wrapper는 동일한 DOM 구조와 필요한 style 계약을 `cross-dr.less`에서 명시적으로 재현해야 한다.

적용 원칙:

| 항목 | 설계 기준 |
| --- | --- |
| 공통 `InfoCard.vue` | 수정하지 않는다. 다른 리소스 상세 화면 회귀를 피한다. |
| DR wrapper | `DrResourceInfoCard.vue`를 유지하되 볼륨 상세의 header, tag, field row 구조를 정확히 따른다. |
| 아이콘 | 헤더 아이콘은 36px, avatar 영역은 최소 50px, 이름과 가로 정렬한다. |
| 태그 | 태그는 `.name` 내부가 아니라 `resource-details` 아래 `.tags` 영역에 둔다. |
| 필드 | `summaryFields` metadata가 아이콘, 복사, 라우터 링크, custom component를 표현할 수 있어야 한다. |
| 다크모드 | `InfoCard.vue` scoped style 상속에 의존하지 않고 `.cross-dr-standard-page .dr-standard-info-card` scope에서 대비를 보장한다. |

Props:

```js
props: {
  resource: {
    type: Object,
    default: () => ({})
  },
  resourceType: {
    type: String,
    default: '' // 'site' | 'plan'
  },
  title: {
    type: String,
    default: ''
  },
  tags: {
    type: Array,
    default: () => []
  },
  summaryFields: {
    type: Array,
    default: () => []
  },
  loading: {
    type: Boolean,
    default: false
  }
}
```

Field metadata:

```js
{
  key: 'id',
  label: this.$t('label.id'),
  value: site.id,
  visible: !!site.id,
  icon: 'barcode-outlined',
  copy: true,
  copyResource: String(site.id),
  copyLabel: true
}
```

지원 속성:

| 속성 | 용도 |
| --- | --- |
| `key` | v-for key. field identity |
| `label` | 표시 라벨. 반드시 locale 결과를 사용 |
| `value` | 기본 표시 값 |
| `visible` | `false`일 때 숨김 |
| `component` / `props` | `Status`, `DrStatusPill` 같은 custom renderer |
| `route` | 값 클릭 시 이동할 Vue Router location |
| `icon` | `tooltip-button` 또는 Ant icon 이름. 예: `barcode-outlined`, `environment-outlined`, `clock-circle-outlined` |
| `copy` | 복사 버튼 노출 여부 |
| `copyResource` | 실제 clipboard 값. 없으면 `value` 사용 |
| `copyLabel` | 값 자체를 `CopyLabel`로 표시할지 여부 |
| `valueClass` | 긴 값, muted 값 등 제한적 class hook |
| `align` | `start` 지정 시 multi-line value를 상단 정렬 |

Template class 계약:

```vue
<a-card
  class="spin-content vm-info-card dr-standard-info-card"
  :loading="loading"
  :bordered="true"
  @contextmenu.stop.prevent="$emit('contextmenu', $event, resource)">
  <div class="card-body">
    <div class="card-content">
      <div class="resource-details">
        <div class="resource-details__name">
          <div class="avatar dr-resource-avatar">
            <slot name="avatar">
              <GlobalOutlined
                v-if="resourceType === 'site'"
                class="dr-resource-avatar__icon" />
              <BranchesOutlined
                v-else
                class="dr-resource-avatar__icon" />
            </slot>
          </div>
          <div>
            <h4 class="name">{{ displayName }}</h4>
          </div>
        </div>
        <div v-if="visibleTags.length" class="tags">
          <a-tag v-for="tag in visibleTags" :key="tag.key">
            {{ tag.label }}
          </a-tag>
        </div>
      </div>

      <a-divider />

      <div
        v-for="field in visibleSummaryFields"
        :key="field.key"
        class="resource-detail-item">
        <div class="resource-detail-item__label">{{ field.label }}</div>
        <div
          :class="[
            'resource-detail-item__details',
            field.align === 'start' ? 'resource-detail-item__details--start' : ''
          ]">
          <tooltip-button
            v-if="field.copy"
            tooltipPlacement="top"
            :tooltip="$t('label.copy')"
            :icon="field.icon || 'copy-outlined'"
            type="dashed"
            size="small"
            :copyResource="copyValue(field)"
            @onClick="$message.success($t('label.copied.clipboard'))" />
          <component
            v-else-if="field.iconComponent"
            :is="field.iconComponent"
            v-bind="field.iconProps || {}" />

          <component
            v-if="field.component"
            :is="field.component"
            v-bind="field.props || {}" />
          <router-link
            v-else-if="field.route"
            :to="field.route">
            <copy-label
              v-if="field.copyLabel"
              :label="formatValue(field.value)" />
            <span v-else>{{ formatValue(field.value) }}</span>
          </router-link>
          <copy-label
            v-else-if="field.copyLabel"
            :label="formatValue(field.value)" />
          <span
            v-else
            :class="field.valueClass">
            {{ formatValue(field.value) }}
          </span>
        </div>
      </div>
    </div>
  </div>
</a-card>
```

Methods/computed:

```js
displayName () {
  return this.title || this.resource.name || this.resource.displayname || this.resource.id || '-'
},
visibleTags () {
  return this.tags.filter(tag => tag && tag.visible !== false && tag.label)
},
visibleSummaryFields () {
  return this.summaryFields.filter(field => field && field.visible !== false)
},
copyValue (field) {
  return String(field.copyResource || field.value || '')
},
formatValue (value) {
  return value === undefined || value === null || value === '' ? '-' : value
}
```

DR Site summary field 작성 기준:

```js
siteSummaryFields () {
  const site = this.detailSite || {}
  return [
    {
      key: 'healthstate',
      label: this.$t('label.dr.site.health'),
      component: Status,
      props: { text: site.healthstate || '', displayText: true },
      visible: !!site.healthstate
    },
    {
      key: 'state',
      label: this.$t('label.status'),
      component: Status,
      props: { text: site.state || '', displayText: true },
      visible: !!site.state
    },
    {
      key: 'id',
      label: this.$t('label.id'),
      value: site.id,
      icon: 'barcode-outlined',
      copy: true,
      copyLabel: true,
      visible: !!site.id
    },
    {
      key: 'endpoint',
      label: this.$t('label.dr.endpoint'),
      value: site.endpoint,
      icon: 'environment-outlined',
      copy: true,
      copyLabel: true,
      visible: !!site.endpoint
    },
    {
      key: 'credentialstate',
      label: this.$t('label.dr.credential.status'),
      value: this.credentialSummary(site),
      iconComponent: SafetyCertificateOutlined,
      visible: site.credentialconfigured !== undefined || !!site.credentialstate
    },
    {
      key: 'lastchecked',
      label: this.$t('label.dr.last.checked'),
      value: site.lastchecked,
      iconComponent: ClockCircleOutlined,
      visible: !!site.lastchecked
    }
  ]
}
```

DR Plan summary field 작성 기준:

```js
planSummaryFields () {
  const plan = this.detailPlan || {}
  const sourceVm = plan.sourcevmid || plan.sourceexternalref
  return [
    {
      key: 'state',
      label: this.$t('label.status'),
      component: Status,
      props: { text: plan.state || '', displayText: true },
      visible: !!plan.state
    },
    {
      key: 'id',
      label: this.$t('label.id'),
      value: plan.id,
      icon: 'barcode-outlined',
      copy: true,
      copyLabel: true,
      visible: !!plan.id
    },
    {
      key: 'sourceSite',
      label: this.$t('label.dr.source.site'),
      value: this.siteName(plan.sourcesiteid),
      iconComponent: GlobalOutlined,
      visible: !!plan.sourcesiteid
    },
    {
      key: 'targetSite',
      label: this.$t('label.dr.target.site'),
      value: this.siteName(plan.targetsiteid),
      iconComponent: GlobalOutlined,
      visible: !!plan.targetsiteid
    },
    {
      key: 'sourceVm',
      label: this.$t('label.dr.source.vm'),
      value: sourceVm,
      route: plan.sourcevmid ? { path: '/vm/' + plan.sourcevmid } : null,
      copyLabel: !plan.sourcevmid,
      visible: !!sourceVm
    },
    { key: 'rpo', label: this.$t('label.dr.rpo'), value: this.formatSeconds(plan.rposeconds), iconComponent: ClockCircleOutlined },
    { key: 'rto', label: this.$t('label.dr.rto'), value: this.formatSeconds(plan.rtoseconds), iconComponent: ClockCircleOutlined }
  ]
}
```

### 17.4 `DrResourceDetailsTab.vue` 설계

볼륨 상세 우측 패널과 같은 row 목록을 제공한다. `a-list`를 사용하면 기존 `DetailsTab.vue`와 hover/border/dark-mode 흐름이 가장 가깝다.

Props:

```js
props: {
  resource: {
    type: Object,
    required: true
  },
  fields: {
    type: Array,
    required: true
  }
}
```

Field metadata:

```js
{
  key: 'credentialstate',
  label: this.$t('label.dr.credential.status'),
  value: this.formatCredentialState(site),
  visible: site.credentialstate !== undefined,
  component: null,
  props: null
}
```

Template:

```vue
<a-list
  size="small"
  :dataSource="visibleFields">
  <template #renderItem="{ item }">
    <a-list-item>
      <div class="dr-standard-detail-row">
        <strong>{{ item.label }}</strong>
        <br />
        <component
          v-if="item.component"
          :is="item.component"
          v-bind="item.props" />
        <router-link
          v-else-if="item.route"
          :to="item.route">
          {{ item.value || '-' }}
        </router-link>
        <span v-else>{{ item.value || '-' }}</span>
      </div>
    </a-list-item>
  </template>
</a-list>
```

Rules:

- `a-descriptions`, `a-descriptions-item`, `bordered`는 DR Site/Plan 상세의 `상세` 탭에서 사용하지 않는다.
- field label은 locale key를 통해 생성한다.
- raw JSON은 기본 상세 row에 직접 넣지 않는다. 필요한 경우 접힌 `cross-dr-code` 블록을 별도 섹션에 둔다.
- long id, endpoint, principal은 `overflow-wrap: anywhere`가 적용되는 row 안에 둔다.

### 17.5 `DrSiteList.vue` 변경 설계

제거 대상:

```vue
<a-card class="spin-content cross-dr-info-card">...</a-card>
<a-tab-pane key="overview" :tab="$t('label.overview')">
  <div class="cross-dr-overview">
    <a-descriptions bordered size="small" :column="descriptionColumn">
      ...
    </a-descriptions>
  </div>
</a-tab-pane>
```

추가 computed:

```js
siteSummaryFields () {
  const site = this.detailSite || {}
  return [
    {
      key: 'healthstate',
      label: this.$t('label.dr.site.health'),
      component: Status,
      props: { text: site.healthstate, displayText: true },
      visible: !!site.healthstate
    },
    {
      key: 'state',
      label: this.$t('label.status'),
      component: Status,
      props: { text: site.state, displayText: true },
      visible: !!site.state
    },
    { key: 'id', label: this.$t('label.id'), value: site.id, visible: !!site.id },
    { key: 'endpoint', label: this.$t('label.dr.endpoint'), value: site.endpoint, visible: !!site.endpoint },
    {
      key: 'credentialstate',
      label: this.$t('label.dr.credential.status'),
      value: site.credentialconfigured ? this.$t('label.configured') : this.$t('label.not.configured'),
      visible: site.credentialconfigured !== undefined
    },
    { key: 'lastchecked', label: this.$t('label.dr.last.checked'), value: site.lastchecked, visible: !!site.lastchecked }
  ]
},
siteDetailFields () {
  const site = this.detailSite || {}
  return [
    { key: 'id', label: this.$t('label.id'), value: site.id },
    { key: 'name', label: this.$t('label.name'), value: site.name },
    { key: 'description', label: this.$t('label.description'), value: site.description },
    { key: 'sitetype', label: this.$t('label.type'), value: this.$t(siteTypeLabel(site.sitetype)) },
    { key: 'hypervisortype', label: this.$t('label.hypervisor'), value: site.hypervisortype },
    { key: 'endpoint', label: this.$t('label.dr.endpoint'), value: site.endpoint },
    { key: 'zoneid', label: this.$t('label.zoneid'), value: site.zoneid },
    { key: 'vmwaredcid', label: this.$t('label.dr.vmware.dc'), value: site.vmwaredcid },
    { key: 'credentialstate', label: this.$t('label.dr.credential.status'), value: this.credentialSummary(site) },
    { key: 'credentialtype', label: this.$t('label.dr.credential.type'), value: site.credentialtype },
    { key: 'credentialendpoint', label: this.$t('label.dr.credential.endpoint'), value: site.credentialendpoint },
    { key: 'credentialprincipal', label: this.$t('label.dr.credential.principal'), value: site.credentialprincipal },
    { key: 'created', label: this.$t('label.created'), value: site.created }
  ]
}
```

Tab/action 변경:

```js
normalizeDetailTab (tab) {
  return tab === 'overview' ? 'details' : (tab || 'details')
}
```

```vue
<a-tab-pane key="details" :tab="$t('label.details')">
  <dr-resource-details-tab
    :resource="detailSite"
    :fields="siteDetailFields" />
  <pre v-if="detailSite.capabilities" class="cross-dr-code">{{ detailSite.capabilities }}</pre>
</a-tab-pane>
```

### 17.6 `DrPlanList.vue` / `DrPlanOverview.vue` 변경 설계

`DrPlanList.vue` detail branch도 site와 같은 좌측 카드 구조를 사용한다.

추가 computed:

```js
planSummaryFields () {
  const plan = this.detailPlan || {}
  return [
    {
      key: 'state',
      label: this.$t('label.status'),
      component: DrStatusPill,
      props: { status: plan.state },
      visible: !!plan.state
    },
    { key: 'sourcesite', label: this.$t('label.dr.source.site'), value: this.siteName(plan.sourcesiteid), visible: !!plan.sourcesiteid },
    { key: 'targetsite', label: this.$t('label.dr.target.site'), value: this.siteName(plan.targetsiteid), visible: !!plan.targetsiteid },
    { key: 'sourcevm', label: this.$t('label.dr.source.vm'), value: plan.sourcevmid || plan.sourceexternalref, visible: !!(plan.sourcevmid || plan.sourceexternalref) },
    { key: 'rpo', label: this.$t('label.dr.rpo'), value: this.formatSeconds(plan.rposeconds) },
    { key: 'rto', label: this.$t('label.dr.rto'), value: this.formatSeconds(plan.rtoseconds) },
    { key: 'run', label: this.$t('label.dr.runs'), value: this.currentRun.id, visible: !!this.currentRun.id }
  ]
},
planDetailFields () {
  const plan = this.detailPlan || {}
  return [
    { key: 'id', label: this.$t('label.id'), value: plan.id },
    { key: 'name', label: this.$t('label.name'), value: plan.name },
    { key: 'description', label: this.$t('label.description'), value: plan.description },
    { key: 'direction', label: this.$t('label.dr.direction'), value: plan.direction },
    { key: 'activeside', label: this.$t('label.dr.active.side'), value: plan.activeside },
    { key: 'sourcevm', label: this.$t('label.dr.source.vm'), value: plan.sourcevmid || plan.sourceexternalref },
    { key: 'sourceworkerhostid', label: this.$t('label.dr.source.worker.host'), value: plan.sourceworkerhostid },
    { key: 'targetworkerhostid', label: this.$t('label.dr.target.worker.host'), value: plan.targetworkerhostid },
    { key: 'coordinatorworkerhostid', label: this.$t('label.dr.coordinator.worker.host'), value: plan.coordinatorworkerhostid },
    { key: 'lastsourcecheckpointat', label: this.$t('label.dr.last.source.checkpoint'), value: plan.lastsourcecheckpointat },
    { key: 'lasttargetdurableat', label: this.$t('label.dr.last.target.durable'), value: plan.lasttargetdurableat },
    { key: 'created', label: this.$t('label.created'), value: plan.created }
  ]
}
```

`DrPlanOverview.vue`는 다음 중 하나로 정리한다.

1. 파일을 유지하면서 `a-descriptions`를 `DrResourceDetailsTab`으로 교체한다.
2. `DrPlanDetailsTab.vue`를 새로 만들고, `DrPlanOverview.vue`는 KPI/topology/progress 보조 섹션만 담당하게 축소한다.

1차 구현은 변경 범위를 줄이기 위해 1번을 권장한다.

### 17.7 스타일 및 다크모드 설계

구조 변경으로 표준 `a-list`/card 스타일을 우선 사용한다. 그래도 DR 전용 row와 custom badge에 필요한 최소 style은 `cross-dr.less`에 둔다.

추가 style:

```less
.cross-dr-standard-page .dr-standard-info-card .card-content {
  width: 100%;
  flex-grow: 1;
  overflow-y: auto;
  padding: 30px;
}

.cross-dr-standard-page .dr-standard-info-card .resource-details {
  text-align: center;
  margin-bottom: 20px;
}

.cross-dr-standard-page .dr-standard-info-card .resource-details__name {
  display: flex;
  align-items: center;
}

.cross-dr-standard-page .dr-standard-info-card .avatar {
  margin-right: 20px;
  overflow: hidden;
  min-width: 50px;
  flex: 0 0 auto;
  cursor: default;
}

.cross-dr-standard-page .dr-standard-info-card .dr-resource-avatar__icon {
  font-size: 36px;
  color: var(--cross-dr-text-secondary);
}

.cross-dr-standard-page .dr-standard-info-card h4.name,
.cross-dr-standard-page .dr-standard-info-card .name {
  margin-bottom: 0;
  font-size: 18px;
  line-height: 1;
  word-break: break-all;
  text-align: left;
}

.cross-dr-standard-page .dr-standard-info-card .tags {
  display: flex;
  flex-wrap: wrap;
  margin-top: 20px;
  margin-bottom: -10px;
}

.cross-dr-standard-page .dr-standard-info-card .tags .ant-tag {
  margin-right: 10px;
  margin-bottom: 10px;
  height: auto;
}

.cross-dr-standard-page .dr-standard-info-card .resource-detail-item {
  margin-bottom: 20px;
  word-break: break-all;
}

.cross-dr-standard-page .dr-standard-info-card .resource-detail-item__label {
  margin-bottom: 5px;
  font-weight: bold;
}

.cross-dr-standard-page .dr-standard-info-card .resource-detail-item__details {
  display: flex;
  align-items: center;
  min-width: 0;
  overflow-wrap: anywhere;
}

.cross-dr-standard-page .dr-standard-info-card .resource-detail-item__details--start {
  align-items: flex-start;
}

.cross-dr-standard-page .dr-standard-info-card .resource-detail-item .anticon {
  margin-right: 10px;
}

.dr-standard-detail-row {
  width: 100%;
  overflow-wrap: anywhere;
}

.dr-standard-detail-row strong {
  display: inline-block;
  margin-bottom: 6px;
}
```

다크모드 fallback:

```less
body.dark-mode .cross-dr-page .dr-standard-detail-row,
body.dark-mode .cross-dr-page .dr-standard-info-card {
  color: rgba(255, 255, 255, 0.86);
}

body.dark-mode .cross-dr-page .dr-standard-detail-row strong,
body.dark-mode .cross-dr-page .dr-standard-info-card .resource-detail-item__label {
  color: rgba(255, 255, 255, 0.72);
}

body.dark-mode .cross-dr-page .dr-standard-info-card .dr-resource-avatar__icon,
body.dark-mode .cross-dr-page .dr-standard-info-card .resource-detail-item .anticon {
  color: rgba(255, 255, 255, 0.65);
}
```

임시 방어:

```less
body.dark-mode .cross-dr-page .ant-descriptions-bordered .ant-descriptions-item-label,
body.dark-mode .cross-dr-page .ant-descriptions-bordered .ant-descriptions-item-content {
  background: transparent;
  color: rgba(255, 255, 255, 0.86);
  border-color: rgba(255, 255, 255, 0.12);
}
```

이 임시 방어는 기존 `a-descriptions`가 남아 있는 과도기만 허용한다. 최종 완료 기준에서는 DR Site/Plan 상세의 기본 `상세` 탭에 `a-descriptions-bordered`가 없어야 한다.

### 17.8 완료 기준

| 검증 | 기준 |
| --- | --- |
| Source grep | `DrSiteList.vue`, `DrPlanOverview.vue`의 기본 상세 영역에서 `a-descriptions bordered` 제거 |
| 탭 | DR Site/Plan 상세 첫 탭 key가 `details`이고 label이 `label.details` |
| 좌측 카드 구조 | DR Site/Plan 좌측 카드가 `vm-info-card`, `resource-details`, `resource-detail-item` class 계약 사용 |
| 좌측 카드 헤더 | 36px 아이콘과 이름이 가로 정렬되고, tag는 `.name` 내부가 아니라 `.tags` 영역에 표시 |
| 좌측 카드 항목 | ID/endpoint/source VM 등 핵심 값에 아이콘, copy button, copy-label, router-link가 metadata에 따라 표시 |
| 작업 UX | 우측 상단 `작업` 드롭다운 하나와 목록/상세 우클릭 메뉴 유지 |
| 다크모드 | DR Site/Plan 상세의 label/value 배경과 텍스트 대비가 볼륨 상세와 동일 계열 |
| 민감 정보 | password, secret, token, API secret 원문 미표시 |
| 회귀 | DR Site/Plan 목록 필터, 추가/수정/삭제, async runtime action 흐름 불변 |

### 17.9 AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| 상세 구조 | DR 전용 custom card + bordered table | 볼륨 상세 기준의 left info card + right row details |
| 좌측 카드 | `cross-dr-info-card` 단독 class 또는 표준 class 일부만 사용 | 볼륨 상세와 같은 header/avatar/tags/detail-item DOM 및 style 계약 |
| 좌측 카드 상호작용 | ID/endpoint가 plain text | ID/endpoint는 복사 가능, 관련 VM/site 값은 라우터 링크 또는 copy-label |
| 우측 상세 | `a-descriptions bordered` 2열 표 | `a-list`/row 기반 label-value 목록 |
| 첫 탭 | `overview` / `개요` | `details` / `상세` |
| 다크모드 | Ant table cell 기본 배경이 노출될 수 있음 | 표준 상세 row style과 DR fallback token으로 대비 보장 |
| DR Plan KPI | 기본 상세 table과 섞임 | 상세 row 아래 보조 섹션 또는 별도 runtime 탭으로 분리 |
| 구현 위험 | 공통 `InfoCard` 직접 수정 시 다른 리소스 회귀 가능 | DR wrapper가 표준 class만 재사용해 회귀 범위 축소 |

## 18. 2026-07-02 구현 반영: 상세 화면 표준화 적용

이번 구현에서는 17장의 상세 설계를 실제 UI 코드에 반영했다. 변경 목적은 DR 실행 흐름을 바꾸는 것이 아니라, DR Site/DR Plan 상세 화면을 볼륨 상세 화면과 같은 UI 표준으로 맞추는 것이다.

적용 내역:

| 구성 | 구현 결과 |
| --- | --- |
| DR Site 좌측 카드 | `DrSiteList.vue` detail branch에서 custom `cross-dr-info-card` DOM을 제거하고 `DrResourceInfoCard.vue`를 사용한다. |
| DR Site 우측 상세 | `a-descriptions bordered`를 제거하고 `DrResourceDetailsTab.vue` 기반 row 목록으로 표시한다. |
| DR Plan 좌측 카드 | `DrPlanList.vue` detail branch에서 custom card DOM을 제거하고 `DrResourceInfoCard.vue`를 사용한다. |
| DR Plan 우측 상세 | 첫 탭을 `details`/`label.details`로 변경하고, `DrPlanOverview.vue` 내부의 `a-descriptions bordered`를 `DrResourceDetailsTab.vue`로 교체했다. |
| URL 호환 | 기존 `tab=overview` URL은 `normalizeDetailTab()`에서 `details`로 매핑한다. |
| 다크모드 | `cross-dr.less`에 `dr-standard-info-card`, `dr-standard-detail-row` 보강 스타일을 추가해 label/value 대비와 긴 텍스트 줄바꿈을 보장한다. |

영향 범위:

| 계층 | 변경 여부 | 설명 |
| --- | --- | --- |
| UI | 변경 | 상세 화면 렌더링 구조, 탭 key, 다크모드 style 변경 |
| API | 변경 없음 | 기존 `DrSiteResponse`, `DrPlanResponse` 필드만 사용 |
| Backend | 변경 없음 | run/action/state machine 계약 변경 없음 |
| Agent | 변경 없음 | host command/answer 계약 변경 없음 |
| ftctl | 변경 없음 | runtime profile/status/event 계약 변경 없음 |
| DB | 변경 없음 | 신규 컬럼/마이그레이션 불필요 |

### 18.1 2026-07-02 추가 확인: 좌측 패널 표준 갭

상세 화면 이미지와 현재 코드 확인 결과, 기존 구현은 `DrResourceInfoCard.vue`에 표준 class 이름을 일부 붙였지만 볼륨 상세 `InfoCard.vue`와 같은 구조/스타일/상호작용 계약까지는 충족하지 못했다.

확인된 갭:

| 영역 | 현재 구현 | 보강 설계 |
| --- | --- | --- |
| Header 구조 | 작은 아이콘이 이름 위쪽에 분리되어 보이고, 태그가 `.name` 내부에 있음 | 36px 아이콘과 이름을 가로 정렬하고 태그는 `.tags` 영역으로 이동 |
| Scoped style | `InfoCard.vue`의 scoped style을 다른 컴포넌트가 상속한다고 가정 | `cross-dr.less`의 `.cross-dr-standard-page .dr-standard-info-card` scope에 표준 치수/간격을 명시 |
| Field renderer | `component`, `route`, plain `span`만 처리 | `icon`, `copy`, `copyResource`, `copyLabel`, `iconComponent`, `align` metadata 지원 |
| ID/endpoint | plain text 표시 | `tooltip-button` 복사 버튼과 `copy-label` 사용 |
| 관련 리소스 | 일부 route만 지원 | source VM/site 등은 route 가능 시 router-link, 외부 ref는 copy-label |

따라서 다음 구현은 17.3과 17.7의 보강 설계를 기준으로 `DrResourceInfoCard.vue`, `DrSiteList.vue`, `DrPlanList.vue`, `cross-dr.less`를 수정한다. API, Backend, Agent, ftctl, DB 계약은 변경하지 않는다.

구현 완료 기준:

- DR Site/Plan 상세 기본 영역에 `a-descriptions bordered`가 남아 있지 않다.
- DR Site/Plan 상세 첫 탭은 `상세`이며 내부 row 흐름은 볼륨 상세의 `DetailsTab` 계열과 같은 형태다.
- 상세 상단 작업 드롭다운과 목록/상세 우클릭 컨텍스트 메뉴는 기존 action 흐름을 그대로 유지한다.
- secret, password, token, API secret 값은 상세 field metadata에 포함하지 않는다.
## 19. 2026-07-02 추가 설계: DR 상세 좌측 패널 여백/복사 필드 정합성 보강

### 19.1 문제 재확인

볼륨 상세 좌측 패널은 `ui/src/components/view/InfoCard.vue`를 기준으로 한다. 표준 구조는 Ant Card의 기본 body padding을 제거하고, 내부 `.card-content`에만 `padding: 30px`를 적용한다.

현재 DR 좌측 패널은 `ui/src/components/dr/DrResourceInfoCard.vue`와 `ui/src/style/cross-dr.less`에서 표준 class를 재현하지만, Ant Card body padding 제거 규칙이 빠져 있다. 그 결과 실제 시작 위치가 다음과 같이 달라진다.

| 항목 | 볼륨 표준 | 현재 DR | 결과 |
|---|---|---|---|
| Ant Card body | `padding: 0` | 기본 `24px` 유지 | DR 컨텐츠가 24px 더 안쪽에서 시작 |
| 내부 content | `.card-content { padding: 30px; }` | 동일 | DR은 총 54px 수준의 좌측 여백 발생 |
| ID/endpoint 렌더링 | copy button + `span margin-left: 10px` + `CopyLabel` | copy button + `CopyLabel`이 있으나 폭/줄바꿈 제어 부족 | UUID 끝 글자가 단독 줄로 떨어질 수 있음 |
| 긴 값 줄바꿈 | `resource-detail-item`의 `word-break: break-all` 중심 | `overflow-wrap: anywhere`가 value/detail에 중복 적용 | 너무 공격적인 줄바꿈 발생 |

### 19.2 코드 수준 수정 원칙

1. 여백 문제는 `.card-content` padding을 줄여 해결하지 않는다. 볼륨 표준과 동일하게 Ant Card body padding만 제거한다.
2. 공통 `InfoCard.vue`는 수정하지 않는다. 다른 리소스 상세 화면 회귀를 피하기 위해 DR wrapper scope에서만 보정한다.
3. `DrResourceInfoCard.vue`는 field metadata 기반 렌더링을 유지하되, ID/endpoint는 표준과 같은 copy button + value 간격을 갖도록 명시한다.
4. UUID, endpoint, 외부 참조값은 읽을 수 있는 단위로 줄바꿈되도록 `overflow-wrap: anywhere` 사용 범위를 줄이고 `word-break: break-all` 또는 `overflow-wrap: break-word` 중심으로 조정한다.

### 19.3 `cross-dr.less` 상세 설계

수정 대상: `ui/src/style/cross-dr.less`

필수 추가:

```less
.cross-dr-standard-page .dr-standard-info-card > .ant-card-body {
  padding: 0;
}
```

이 규칙은 볼륨 `InfoCard.vue`의 다음 scoped style과 같은 역할을 DR scope에서 수행한다.

```less
:deep(.ant-card-body) {
  padding: 0;
}
```

기존 `.card-content` 규칙은 표준과 동일하므로 유지한다.

```less
.cross-dr-standard-page .dr-standard-info-card .card-content {
  width: 100%;
  flex-grow: 1;
  overflow-y: auto;
  padding: 30px;
  min-width: 0;
}
```

값 영역은 다음과 같이 보강한다.

```less
.cross-dr-standard-page .dr-standard-info-card .resource-detail-item {
  margin-bottom: 20px;
  word-break: break-all;
}

.cross-dr-standard-page .dr-standard-info-card .resource-detail-item__details {
  display: flex;
  align-items: center;
  min-width: 0;
  color: var(--cross-dr-text);
}

.cross-dr-standard-page .dr-standard-info-card .dr-standard-info-card__value {
  min-width: 0;
  overflow-wrap: break-word;
}

.cross-dr-standard-page .dr-standard-info-card .dr-standard-info-card__copy-value {
  margin-left: 10px;
  min-width: 0;
  overflow-wrap: break-word;
}
```

삭제 또는 완화 대상:

```less
.cross-dr-standard-page .dr-standard-info-card .resource-detail-item__details {
  overflow-wrap: anywhere;
}

.cross-dr-standard-page .dr-standard-info-card .dr-standard-info-card__value {
  overflow-wrap: anywhere;
}
```

위 두 규칙은 UUID 같은 문자열을 너무 이른 위치에서 끊으므로 표준과 다른 시각 결과를 만든다.

### 19.4 `DrResourceInfoCard.vue` 상세 설계

수정 대상: `ui/src/components/dr/DrResourceInfoCard.vue`

현재 `field.copy`는 `tooltip-button`을 먼저 렌더링하고, 이어서 `copy-label` 또는 value를 렌더링한다. 이 구조 자체는 볼륨 표준의 ID 렌더링과 유사하므로 유지하되, value wrapper를 명확히 추가한다.

설계 템플릿:

```vue
<tooltip-button
  v-if="field.copy"
  tooltipPlacement="top"
  :tooltip="field.copyTooltip || $t('label.copy')"
  :icon="field.icon || 'copy-outlined'"
  type="dashed"
  size="small"
  :copyResource="copyValue(field)"
  @onClick="$message.success($t('label.copied.clipboard'))" />

<span
  v-if="field.copy && field.copyLabel"
  class="dr-standard-info-card__copy-value">
  <copy-label
    :label="valueLabel(field)"
    :copyValue="copyValue(field)" />
</span>

<span
  v-else-if="field.copy && !field.copyLabel"
  class="dr-standard-info-card__copy-value">
  {{ formatValue(field.value) }}
</span>
```

그 외 field는 기존 metadata 처리를 유지한다.

```vue
<component
  v-else-if="field.iconComponent"
  :is="field.iconComponent"
  v-bind="field.iconProps || {}" />

<component
  v-if="field.component"
  :is="field.component"
  v-bind="field.props || {}" />

<router-link
  v-else-if="field.route"
  :to="field.route">
  <copy-label
    v-if="field.copyLabel"
    :label="valueLabel(field)"
    :copyValue="copyValue(field)" />
  <span v-else>{{ formatValue(field.value) }}</span>
</router-link>
```

주의 사항:

- `field.copy` branch에서 value가 한 번만 렌더링되도록 조건 분기를 정리한다.
- `copyValue(field)`는 빈 값이면 clipboard에 `-`가 들어가지 않도록 기존처럼 원본 value 기반으로 유지한다.
- `CopyLabel` 자체는 공통 위젯이므로 수정하지 않는다.

### 19.5 `DrSiteList.vue` / `DrPlanList.vue` metadata 설계

수정 대상:

- `ui/src/views/infra/dr/DrSiteList.vue`
- `ui/src/views/infra/dr/DrPlanList.vue`

ID/endpoint/source VM처럼 값이 길거나 복사가 필요한 필드는 다음 metadata를 사용한다.

```js
{
  key: 'id',
  label: this.$t('label.id'),
  value: site.id,
  icon: 'barcode-outlined',
  copy: true,
  copyTooltip: this.$t('label.copyid'),
  copyResource: String(site.id || ''),
  copyLabel: true,
  visible: !!site.id
}
```

endpoint는 다음처럼 `environment-outlined`를 사용한다.

```js
{
  key: 'endpoint',
  label: this.$t('label.dr.endpoint'),
  value: site.endpoint,
  icon: 'environment-outlined',
  copy: true,
  copyResource: String(site.endpoint || ''),
  copyLabel: true,
  visible: !!site.endpoint
}
```

상태/시간/credential field는 버튼이 필요 없으므로 `iconComponent`만 사용한다.

### 19.6 검증 기준

| 검증 항목 | 기준 |
|---|---|
| 좌측 시작점 | DR Site/Plan 좌측 패널의 아이콘 x-position이 볼륨 상세와 같은 수준이어야 한다. |
| Card body | DevTools 기준 `.dr-standard-info-card > .ant-card-body` computed padding이 `0px`이어야 한다. |
| Content padding | `.dr-standard-info-card .card-content` computed padding은 `30px`이어야 한다. |
| UUID 표시 | UUID 마지막 1글자만 단독 줄로 떨어지는 현상이 없어야 한다. 좁은 폭에서는 자연스러운 단어 단위 또는 break-word 수준으로 줄바꿈된다. |
| 다크모드 | label/value/icon/copy link 대비가 볼륨 상세와 같은 수준이어야 한다. |
| 범위 | API, Backend, Agent, ftctl, DB 변경 없이 UI build와 정적 asset 배포만으로 반영된다. |

### 19.7 2026-07-02 추가 설계: DR Site 상태 체크 이력 탭

상세 설계는 [529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md](529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md)를 따른다.

DR Site 상세 화면의 오른쪽 탭 순서는 다음과 같이 고정한다.

1. `details`: 상세
2. `plans`: DR 계획
3. `healthChecks`: 상태 체크 이력

`DrSiteList.vue` 변경 기준:

```vue
<a-tab-pane key="details" :tab="$t('label.details')" />
<a-tab-pane key="plans" :tab="$t('label.dr.plans')" />
<a-tab-pane key="healthChecks" :tab="$t('label.dr.site.health.history')">
  <a-table
    size="middle"
    :columns="healthCheckColumns"
    :dataSource="healthChecks"
    :rowKey="record => record.id"
    :loading="healthCheckLoading"
    :pagination="healthCheckPagination"
    @change="handleHealthCheckTableChange" />
</a-tab-pane>
```

`healthChecks` 탭은 lazy load 방식으로 동작한다. 상세 화면 진입 시 현재 URL query가 `tab=healthChecks`이면 `fetchDetail()` 완료 후 즉시 `fetchHealthChecks()`를 호출하고, 다른 탭에서 `healthChecks`로 이동하면 그 시점에 이력을 조회한다.

표준 table column:

| key | label | 표시 |
| --- | --- | --- |
| `checkedat` | `label.dr.site.health.checked.at` | 점검 시각 |
| `triggertype` | `label.dr.site.health.trigger` | `MANUAL/SCHEDULED/CREATE/UPDATE/PREFLIGHT` |
| `healthstate` | `label.dr.site.health` | `Status` component |
| `healthreasoncode` | `label.dr.site.health.reason` | reason code |
| `healthmessage` | `label.dr.site.health.message` | ellipsis text |
| `healthlatencyms` | `label.dr.site.health.latency` | `N ms` |
| `endpoint` | `label.endpoint` | endpoint snapshot |
| `credentialstate` | `label.dr.credential.state` | credential state snapshot |

`ui/src/api/dr.js`에는 다음 wrapper를 추가한다.

```js
listDrSiteHealthChecks: ['listdrsitehealthchecksresponse', 'drsitehealthcheck']

export function listDrSiteHealthChecks (params = {}) {
  return getAPI('listDrSiteHealthChecks', params)
    .then(response => extractDrList(response, 'listDrSiteHealthChecks'))
}
```

UI는 주기 점검을 직접 실행하지 않는다. 주기 점검은 Cloud backend scheduler 책임이며, UI는 저장된 이력을 조회만 한다.

### 19.8 AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
|---|---|---|
| 카드 body padding | Ant Card 기본 `24px` 유지 | `.dr-standard-info-card > .ant-card-body { padding: 0; }` |
| 내부 content padding | `30px` | `30px` 유지 |
| 실제 좌측 여백 | body 24px + content 30px | content 30px |
| ID/endpoint 값 | copy button 뒤 value wrapper 간격/폭 제어 부족 | `dr-standard-info-card__copy-value`로 `margin-left: 10px`, `min-width: 0`, `overflow-wrap: break-word` |
| 긴 문자열 줄바꿈 | `overflow-wrap: anywhere`로 과도한 줄바꿈 | 표준에 가까운 `word-break: break-all` + value `break-word` |
| 영향 범위 | UI 표준 갭 | DR Site/Plan 좌측 카드 UI-only 보정 |

## 20. 2026-07-03 DR Site inventory와 상세 JSON 표시 보강

상세 설계는 [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)를 따른다.

UI 최신 기준:

1. `DrSiteList.vue`의 `label.dr.site.connection.info`는 `a-divider`가 아니라 `.cross-dr-form-section-title`로 표시한다.
2. `zoneid`, `vmwaredcid`는 `a-input-number`로 노출하지 않는다. `MOLD_KVM`, `MOLD_VMWARE`에서만 backend inventory API 결과를 `a-select` option으로 표시한다.
3. `VMWARE_DIRECT`는 Mold Zone/Datacenter field를 표시하지 않는다.
4. `detailSite.capabilities` raw JSON은 DR Site 상세 탭에서 `<pre>`로 표시하지 않는다.
5. 진단 metadata는 `상태 체크 이력` 탭의 확장 상세 같은 non-default 위치에서만 secret 없이 표시할 수 있다.
6. 다크모드는 `--cross-dr-text`, `--cross-dr-border`, `--cross-dr-surface` token으로 section label, select, placeholder, help/error text를 보정한다.

## 21. 2026-07-04 DR Plan 대화상자 입력 가이드와 원본 VM inventory 보강

상세 설계는 [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)의 16장을 따른다.

### 21.1 문제 정의

현재 `ui/src/views/infra/dr/DrPlanList.vue`의 DR Plan 추가/수정 대화상자는 DR Site 대화상자에 적용한 최신 입력 표준을 따르지 않는다.

| 구분 | 현재 코드 | 문제 |
| --- | --- | --- |
| 라벨/도움말 | `:label="$t(...)"` 직접 사용 | 볼륨 생성/DR Site 생성 표준인 `TooltipLabel` 없음 |
| placeholder | 대부분 없음 | 사용자가 ID, 초 단위, JSON 형식을 추측해야 함 |
| section title | `a-divider` 사용 | DR Site와 같은 `.cross-dr-form-section-title` 표준 미적용 |
| 원본 VM | `sourcevmid`를 `a-input`으로 직접 입력 | site 선택 후 inventory를 조회해 선택해야 하는 값이 수동 입력으로 노출됨 |
| 방향 | 사용자가 select로 직접 선택 | source/target site hypervisor에서 자동 산출 가능 |
| 엔진 | `FTCTL`, `VMWARE_PHASE1`, disabled `V2K`까지 노출 | DR 실행 경로와 이관 전용/legacy 경로가 섞임 |
| 검증 | `name/sourcesiteid/targetsiteid/direction`만 확인 | source VM, RPO/RTO, JSON 형식, site health, duplicate external ref 검증 부족 |

### 21.2 UI 설계 기준

`DrPlanList.vue`는 `DrSiteList.vue`와 같은 modal/form 표준을 사용한다.

적용 파일:

- `ui/src/views/infra/dr/DrPlanList.vue`
- `ui/src/api/dr.js`
- `ui/public/locales/en.json`
- `ui/public/locales/ko_KR.json`
- `ui/src/style/cross-dr.less`

필수 import:

```js
import TooltipLabel from '@/components/widgets/TooltipLabel'
import { getAPI } from '@/api'
import {
  createDrPlan,
  deleteDrPlan,
  discoverDrPlanInventory,
  getDrPlan,
  listDrPlans,
  listDrSites,
  updateDrPlan
} from '@/api/dr'
```

폼 section은 `a-divider`를 제거하고 `.cross-dr-form-section-title`을 사용한다.

```vue
<div class="cross-dr-form-section-title">
  <span>{{ $t('label.dr.basic.info') }}</span>
</div>
```

각 form item은 label slot과 `TooltipLabel`을 사용한다.

```vue
<a-form-item required>
  <template #label>
    <tooltip-label
      :title="$t('label.dr.source.vm')"
      :tooltip="$t('message.dr.plan.source.vm.tooltip')" />
  </template>
  <a-select
    v-model:value="createForm.sourceworkloadvalue"
    :options="sourceWorkloadOptions"
    :loading="planInventoryLoading"
    :disabled="!canLoadSourceWorkloads"
    :placeholder="$t('message.dr.plan.source.vm.placeholder')"
    show-search
    allow-clear
    option-filter-prop="label"
    @focus="fetchPlanInventory"
    @search="searchSourceWorkloads"
    @change="changeSourceWorkload" />
</a-form-item>
```

사용자 기본 흐름:

1. 원본 사이트 선택
2. 대상 사이트 선택
3. UI가 source/target site hypervisor를 기준으로 방향을 자동 산출
4. UI가 backend inventory API로 원본 workload option 조회
5. 사용자가 원본 VM/workload를 select로 선택
6. RPO/RTO와 시작 동기화 여부를 지정
7. 대상 storage/compute/network, consistency, schedule, policy를 선택형 UI로 지정
8. UI가 `previewDrPlanSpec`으로 backend-generated spec과 preflight warning을 조회
9. raw JSON은 expert mode에서만 preview/override하며 기본 생성 흐름에서는 직접 입력하지 않음

방향 자동 산출:

```js
resolveDirectionFromSites (sourceSite, targetSite) {
  const source = String(sourceSite?.hypervisortype || '').toUpperCase()
  const target = String(targetSite?.hypervisortype || '').toUpperCase()
  if (source === 'KVM' && target === 'KVM') return 'KVM_TO_KVM'
  if (source === 'KVM' && target === 'VMWARE') return 'KVM_TO_VMWARE'
  if (source === 'VMWARE' && target === 'VMWARE') return 'VMWARE_TO_VMWARE'
  if (source === 'VMWARE' && target === 'KVM') return 'VMWARE_TO_KVM'
  return ''
}
```

`direction` field는 create mode에서 read-only summary 또는 disabled select로 표시한다. 사용자가 topology와 맞지 않는 방향을 선택하는 흐름은 제공하지 않는다.

### 21.3 DR Plan form state

`defaultCreateForm()`은 source workload 선택값을 별도로 가진다. API payload의 `sourcevmid`와 `sourceexternalref`는 선택 option의 reference type에 따라 채운다.

```js
defaultCreateForm () {
  return {
    name: '',
    description: '',
    sourcesiteid: undefined,
    targetsiteid: undefined,
    direction: '',
    sourceworkloadvalue: undefined,
    sourceworkloadname: '',
    sourcevmid: undefined,
    sourceexternalref: '',
    enginetype: 'FTCTL_DR',
    enginebindingtype: 'FTCTL_DR',
    enginebindingid: undefined,
    rposeconds: 300,
    rtoseconds: 300,
    sourceworkerhostid: undefined,
    targetworkerhostid: undefined,
    coordinatorworkerhostid: undefined,
    mappingjson: '',
    schedulejson: '',
    policyjson: '',
    quiescepolicyjson: '',
    startsync: false
  }
}
```

2026-07-05 이후 기준 form state는 위 raw JSON field를 기본 사용자 입력값으로 사용하지 않는다. `targetvmname`, `targetstorageoption`, `targetcomputeoption`, `targetnetworkoption`, `syncintervalseconds`, `retentioncount`, `consistencymode`, `testnetworkmode`, `failoverpoweron`, `bandwidthlimitmbps`, `retrycount`, `expertmode` 같은 typed field를 추가하고, `mappingjson`, `schedulejson`, `policyjson`, `quiescepolicyjson`은 backend-generated preview 또는 expert override로만 사용한다. 상세 field와 submit payload는 [531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md](531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md)의 4장을 따른다.

inventory state:

```js
data () {
  return {
    planInventoryLoading: false,
    planInventoryError: '',
    planInventoryLoadedKey: '',
    sourceWorkloadOptions: [],
    sourceWorkerHostOptions: [],
    targetWorkerHostOptions: [],
    coordinatorWorkerHostOptions: []
  }
}
```

선택 option 구조:

```js
{
  value: 'CLOUD_VM_ID:4d29...',
  label: 'r97-link-04 (Running / i-2-123-VM)',
  referenceType: 'CLOUD_VM_ID',       // CLOUD_VM_ID | EXTERNAL_REF
  sourceVmId: '4d29...',
  externalRef: '',
  name: 'r97-link-04',
  state: 'Running',
  hypervisor: 'KVM',
  details: { instanceName: 'i-2-123-VM', zoneName: 'Zone' }
}
```

선택 핸들러:

```js
changeSourceWorkload (value, option) {
  const selected = Array.isArray(option) ? option[0] : option
  this.createForm.sourceworkloadvalue = value
  this.createForm.sourceworkloadname = selected?.name || selected?.label || ''
  this.createForm.sourcevmid = selected?.referenceType === 'CLOUD_VM_ID' ? selected.sourceVmId : undefined
  this.createForm.sourceexternalref = selected?.referenceType === 'EXTERNAL_REF' ? selected.externalRef : ''
}
```

### 21.4 UI validation

기존 `createPlan()`의 단순 required check를 `planFormValidationMessage()`로 교체한다.

```js
planFormValidationMessage () {
  if (!String(this.createForm.name || '').trim()) return this.$t('message.dr.plan.validation.name.required')
  if (!this.createForm.sourcesiteid) return this.$t('message.dr.plan.validation.source.site.required')
  if (!this.createForm.targetsiteid) return this.$t('message.dr.plan.validation.target.site.required')
  if (this.createForm.sourcesiteid === this.createForm.targetsiteid) return this.$t('message.dr.plan.validation.same.site')
  if (!this.createForm.direction) return this.$t('message.dr.plan.validation.direction.unsupported')
  if (!this.createForm.sourcevmid && !this.createForm.sourceexternalref) return this.$t('message.dr.plan.validation.source.vm.required')
  if (Number(this.createForm.rposeconds) < 60) return this.$t('message.dr.plan.validation.rpo.minimum')
  if (Number(this.createForm.rtoseconds) < 60) return this.$t('message.dr.plan.validation.rto.minimum')
  const jsonError = this.validatePlanJsonFields()
  if (jsonError) return jsonError
  return ''
}
```

JSON field 검증:

아래 검증은 expert mode에서 raw JSON override를 허용할 때만 사용한다. 기본 생성/수정 흐름에서는 UI가 JSON textarea를 렌더링하지 않고, backend `DrPlanSpecBuilder`가 canonical JSON을 생성한 뒤 의미 검증을 수행한다.

```js
validateJsonField (field, labelKey) {
  const text = String(this.createForm[field] || '').trim()
  if (!text) return ''
  try {
    JSON.parse(text)
    return ''
  } catch (e) {
    return this.$t('message.dr.plan.validation.json.invalid', { field: this.$t(labelKey) })
  }
}
```

### 21.5 API 설계

신규 API wrapper:

```js
const objectKeys = {
  discoverDrPlanInventory: ['discoverdrplaninventoryresponse', 'drplaninventory']
}

export function discoverDrPlanInventory (params = {}) {
  return postAPI('discoverDrPlanInventory', params).then(response => {
    const jobId = extractJobId(response, 'discoverDrPlanInventory')
    if (jobId) {
      return waitForDrJobObject(jobId, 'discoverDrPlanInventory')
    }
    return extractDrObject(response, 'discoverDrPlanInventory')
  })
}
```

Cloud API command:

```java
@APICommand(
    name = DiscoverDrPlanInventoryCmd.APINAME,
    description = "Discover source workloads and worker options for a Cross Hypervisor DR plan",
    responseObject = DrPlanInventoryResponse.class,
    authorized = {RoleType.Admin})
public class DiscoverDrPlanInventoryCmd extends BaseAsyncCmd {
    public static final String APINAME = "discoverDrPlanInventory";

    @Inject
    private DrPlanInventoryService drPlanInventoryService;

    @Parameter(name = "sourcesiteid", type = CommandType.UUID, entityType = DrSiteResponse.class, required = true)
    private Long sourceSiteId;

    @Parameter(name = "targetsiteid", type = CommandType.UUID, entityType = DrSiteResponse.class)
    private Long targetSiteId;

    @Parameter(name = "direction", type = CommandType.STRING)
    private String direction;

    @Parameter(name = "keyword", type = CommandType.STRING)
    private String keyword;

    @Parameter(name = "includevms", type = CommandType.BOOLEAN)
    private Boolean includeVms;

    @Parameter(name = "includeworkers", type = CommandType.BOOLEAN)
    private Boolean includeWorkers;

    @Override
    public void execute() {
        DrPlanInventoryRequest request = new DrPlanInventoryRequest();
        request.setSourceSiteId(sourceSiteId);
        request.setTargetSiteId(targetSiteId);
        request.setDirection(direction);
        request.setKeyword(keyword);
        request.setIncludeVms(BooleanUtils.isNotFalse(includeVms));
        request.setIncludeWorkers(BooleanUtils.isTrue(includeWorkers));
        DrPlanInventoryResult result = drPlanInventoryService.discover(request);
        setResponseObject(drResponseGenerator.createPlanInventoryResponse(result));
    }
}
```

응답 DTO:

```java
public class DrPlanInventoryResponse extends BaseResponse {
    @SerializedName("sourceworkloads")
    private List<DrInventoryOptionResponse> sourceWorkloads;

    @SerializedName("sourceworkerhosts")
    private List<DrInventoryOptionResponse> sourceWorkerHosts;

    @SerializedName("targetworkerhosts")
    private List<DrInventoryOptionResponse> targetWorkerHosts;

    @SerializedName("direction")
    private String direction;

    @SerializedName("enginetype")
    private String engineType;

    @SerializedName("warnings")
    private List<String> warnings;
}
```

`DrInventoryOptionResponse`는 다음 field를 추가한다.

| field | 의미 |
| --- | --- |
| `referenceType` | `CLOUD_VM_ID`, `EXTERNAL_REF`, `HOST_ID` |
| `sourceVmId` | local Cloud VM UUID. `sourcevmid` payload에 사용 |
| `externalRef` | remote Mold/vCenter VM 식별자. `sourceexternalref` payload에 사용 |
| `state` | VM power/state |
| `hypervisor` | KVM/VMWARE |
| `zoneName` | 표시용 zone |

### 21.6 Backend inventory service

신규 package:

- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/inventory/DrPlanInventoryService.java`
- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/inventory/DrPlanInventoryServiceImpl.java`
- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/inventory/DrPlanInventoryRequest.java`
- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/inventory/DrPlanInventoryResult.java`
- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/inventory/DrVmwareInventoryClient.java`

분기 규칙:

| source site | 조회 방식 | 선택 결과 |
| --- | --- | --- |
| local ABLESTACK/KVM | `UserVmDao` 또는 local `listVirtualMachines` equivalent | `referenceType=CLOUD_VM_ID`, `sourcevmid` |
| remote ABLESTACK/KVM | 저장된 Mold credential로 remote `listVirtualMachines` | `referenceType=EXTERNAL_REF`, `sourceexternalref` |
| Mold-managed VMware | 저장된 Mold credential로 remote `listVirtualMachines` | local site이면 `sourcevmid`, remote이면 `sourceexternalref` |
| VMware Direct | vCenter inventory client | `referenceType=EXTERNAL_REF`, `sourceexternalref` |

`DrMoldInventoryClient` 확장:

```java
private static final String COMMAND_LIST_VMS = "listVirtualMachines";

public List<DrInventoryOption> listVirtualMachines(DrResolvedSiteCredential credential, String keyword, String zoneExternalId, Long zoneId) {
    Map<String, String> params = new LinkedHashMap<String, String>();
    params.put("listall", "true");
    params.put("details", "min");
    if (StringUtils.isNotBlank(keyword)) {
        params.put("keyword", keyword);
    }
    if (StringUtils.isNotBlank(zoneExternalId)) {
        params.put("zoneid", zoneExternalId);
    } else if (zoneId != null) {
        params.put("zoneid", String.valueOf(zoneId));
    }
    JsonObject response = execute(credential, COMMAND_LIST_VMS, params);
    JsonObject payload = getObjectIgnoreCase(response, "listvirtualmachinesresponse");
    return toVmOptions(getArrayIgnoreCase(payload, "virtualmachine"));
}
```

VM option 생성 기준:

```java
option.setType("SOURCE_WORKLOAD");
option.setName(firstString(vm, "displayname", "name", "instancename"));
option.setExternalId(firstString(vm, "id", "uuid", "instancename"));
option.setValue(option.getExternalId());
option.putDetail("instanceName", firstString(vm, "instancename"));
option.putDetail("state", firstString(vm, "state"));
option.putDetail("hypervisor", firstString(vm, "hypervisor"));
```

`DrVmwareInventoryClient` 설계:

- `DrResolvedSiteCredential`에서 vCenter endpoint, username, password, tlsVerify를 가져온다.
- health check에서 사용하는 `DrSiteProbeSupport.openConnection()`과 `basicAuth()`를 재사용한다.
- 우선 vCenter REST session을 생성하고 VM 목록 API를 조회한다.
- REST inventory가 불가능한 vCenter는 `InventoryException`으로 실패시키고 UI에 명확한 message를 반환한다.
- response/log/history에는 password, session token을 기록하지 않는다.

### 21.7 Backend validation

`DrPlanServiceImpl.validatePlan()`을 다음 기준으로 확장한다.

```java
private void validatePlan(DrPlanVO plan) {
    if (plan == null) throw new InvalidParameterValueException("DR plan is required");
    if (StringUtils.isBlank(plan.getName())) throw new InvalidParameterValueException("DR plan name is required");
    if (StringUtils.isBlank(plan.getDirection())) throw new InvalidParameterValueException("DR plan direction is required");
    if (plan.getSourceVmId() == null && StringUtils.isBlank(plan.getSourceExternalRef())) {
        throw new InvalidParameterValueException("DR plan source workload is required");
    }
    if (plan.getRpoSeconds() != null && plan.getRpoSeconds() <= 0) {
        throw new InvalidParameterValueException("DR plan RPO must be greater than 0 seconds");
    }
    if (plan.getRtoSeconds() != null && plan.getRtoSeconds() <= 0) {
        throw new InvalidParameterValueException("DR plan RTO must be greater than 0 seconds");
    }
    validateJson(plan.getMappingJson(), "mappingjson");
    validateJson(plan.getScheduleJson(), "schedulejson");
    validateJson(plan.getPolicyJson(), "policyjson");
    validateJson(plan.getQuiescePolicyJson(), "quiescepolicyjson");
}
```

중복 검증:

```java
private void ensureNoDuplicatePlan(DrPlanVO plan) {
    if (plan.getSourceVmId() != null && drPlanDao.findActiveBySourceVmId(plan.getSourceVmId()) != null) {
        throw new InvalidParameterValueException(DrConstants.ERROR_DUPLICATE_PLAN + ": source VM already has an active DR plan");
    }
    if (plan.getSourceSiteId() != null && StringUtils.isNotBlank(plan.getSourceExternalRef())
            && drPlanDao.findActiveBySourceSiteAndExternalRef(plan.getSourceSiteId(), plan.getSourceExternalRef()) != null) {
        throw new InvalidParameterValueException(DrConstants.ERROR_DUPLICATE_PLAN + ": source workload already has an active DR plan");
    }
}
```

`DrPlanDao` 추가:

```java
DrPlanVO findActiveBySourceSiteAndExternalRef(long sourceSiteId, String sourceExternalRef);
```

`DrPlanDaoImpl`:

```java
SearchBuilder<DrPlanVO> SourceSiteExternalRefSearch;

SourceSiteExternalRefSearch = createSearchBuilder();
SourceSiteExternalRefSearch.and("sourceSiteId", SourceSiteExternalRefSearch.entity().getSourceSiteId(), SearchCriteria.Op.EQ);
SourceSiteExternalRefSearch.and("sourceExternalRef", SourceSiteExternalRefSearch.entity().getSourceExternalRef(), SearchCriteria.Op.EQ);
SourceSiteExternalRefSearch.and("removed", SourceSiteExternalRefSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
SourceSiteExternalRefSearch.done();
```

### 21.8 DB 설계

신규 inventory 결과를 저장하지는 않는다. 따라서 신규 inventory table은 만들지 않는다.

다만 remote/external source workload 중복 검증을 빠르게 하기 위해 다음 index를 추가한다.

```sql
ALTER TABLE `dr_plan`
  ADD INDEX `i_dr_plan__source_site_external_ref_removed`
    (`source_site_id`, `source_external_ref`(255), `removed`);
```

이미 동등 index가 있으면 upgrade script에는 조건부 추가 로직을 사용한다.

### 21.9 i18n 추가 키

필수 추가:

```json
{
  "message.dr.plan.name.placeholder": "Example: r97-link-04 DR Plan",
  "message.dr.plan.name.tooltip": "Enter a name operators can use to identify this DR protection plan.",
  "message.dr.plan.source.site.tooltip": "Select the site where the protected workload is currently running.",
  "message.dr.plan.target.site.tooltip": "Select the site where the workload will be recovered.",
  "message.dr.plan.direction.tooltip": "Direction is derived from the source and target site hypervisors.",
  "message.dr.plan.source.vm.placeholder": "Select a source virtual machine after choosing a source site.",
  "message.dr.plan.source.vm.tooltip": "The source workload is discovered by the backend from the selected site.",
  "message.dr.plan.rpo.tooltip": "Target maximum data loss window in seconds.",
  "message.dr.plan.rto.tooltip": "Target recovery time objective in seconds.",
  "message.dr.plan.validation.source.vm.required": "Select a source virtual machine.",
  "message.dr.plan.validation.direction.unsupported": "The selected source and target site combination is not supported.",
  "message.dr.plan.inventory.required": "Select source and target sites to load source workloads.",
  "message.dr.plan.inventory.empty": "No source workload was found.",
  "message.dr.plan.inventory.unavailable": "Unable to load source workloads from the selected source site."
}
```

### 21.10 AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| 원본 VM | ID 수동 입력 | source site 선택 후 backend inventory select |
| 방향 | 사용자 직접 선택 | source/target hypervisor 기준 자동 산출 |
| 엔진 | legacy/phase1/v2k까지 같은 select에 표시 | 기본 `FTCTL_DR`, 고급에서 backend eligible 엔진만 표시 |
| 고급 엔진 설정 | worker host와 mapping/schedule/policy/quiesce JSON을 사용자가 직접 입력 | worker/resource/policy는 선택형 UI로 받고 backend가 canonical JSON 생성 |
| 입력 안내 | 라벨만 표시 | `TooltipLabel` + placeholder + field별 validation |
| remote workload | 입력자가 external ref를 알아야 함 | backend가 Mold/vCenter inventory option으로 반환 |
| 검증 | 필수 field와 JSON 구문 일부만 확인 | source workload, target mapping, worker host, RPO/RTO, topology, duplicate external ref, generated spec 의미 검증 |
| DB | source VM 중복만 검증 | source VM + source site/external ref 중복 검증 |
| 보안 | UI가 수동 입력에 의존 | UI는 secret을 다루지 않고 backend inventory 결과만 사용 |

2026-07-05 이후 DR Plan 고급 설정의 기준 설계는 [531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md](531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md)를 따른다.

## 22. 2026-07-06 DR Plan VMware -> ABLESTACK 선택형 입력 보강

`VMWARE_TO_KVM` Plan 생성/수정 화면은 일반 사용자 모드에서 더 이상 target storage, target compute, target network, worker host, disk mapping 값을 수동 문자열로 받지 않는다. 사용자는 source VM과 target ABLESTACK 배치 자원을 선택해야 하며, backend inventory가 후보를 제공하지 못하면 입력칸 대신 원인을 표시하는 blocking alert를 보여준다.

### 22.1 UI 기준

| 항목 | 기존 화면 | 보강 화면 |
| --- | --- | --- |
| target storage | option이 없으면 `<a-input>` | `<a-select>` 또는 `TARGET_STORAGE_REQUIRED` alert |
| target compute | KVM host 후보를 compute처럼 표시 가능 | service offering `<a-select>` |
| target network | 수동 `<a-input>` | network `<a-select>` |
| target worker | option이 없으면 `<a-input>` | worker host `<a-select>` 또는 blocking alert |
| disk mapping | raw `diskmappingsjson` 중심 | source disk별 mapping table |
| 자동 선택 | 일부 field에만 적용 | 후보가 정확히 1개인 field만 공통 자동 선택 |

### 22.2 코드 반영 위치

- `ui/src/views/infra/dr/DrPlanList.vue`
- 필요 시 `ui/src/views/infra/dr/components/DrPlanDiskMappingTable.vue`
- DR Plan i18n label/placeholder/help message

상세 field, payload, readiness 기준은 [531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md](531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md)의 15장을 따른다.

## 2026-07-06 보강: DR Action 상태 표시 계약

DR Plan 동기화 시작 실패 분석 결과, UI는 plan state만으로 동기화 상태를 판단하면 안 된다. 최신 기준은 [533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md](533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md)를 따른다.

UI 구현 원칙:

- action API 성공은 장기 작업 성공이 아니라 요청 접수로만 표시한다.
- 목록/상세 화면은 `plan.state`, `lastrun.state`, `runtimeprojectionstate`를 조합한 effective status를 표시한다.
- latest run이 `FAILED`이면 plan이 `SYNCING`으로 남아 있어도 UI는 실패 상태와 원인을 우선 표시한다.
- action button eligibility는 active run 상태(`QUEUED`, `PREPARING`, `DISPATCHING`, `ACCEPTED`, `RUNNING`)를 함께 고려한다.
- `dr-status not_found`는 그대로 노출하지 않고 `DR_RUNTIME_STARTING` 또는 `DR_RUNTIME_NOT_CREATED` 같은 사용자 메시지로 변환한다.

## 23. 2026-07-06 추가 보강: retryable lock과 status hang UI 기준

상세 기준은 [533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md](533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md)의 15장 이후를 따른다.

DR Plan 상세 화면은 runtime projection 갱신을 기다리며 전체 skeleton 상태에 머물면 안 된다.

UI 구현 기준:

- `getDrPlan`, `listDrRuns`, `listDrRunSteps`로 구성되는 DB snapshot 조회는 화면 초기 렌더링의 유일한 blocking dependency다.
- `refreshDrProtectionView` 또는 status refresh는 별도 비동기 작업으로 실행하고, 실패해도 상세 화면 기본 정보는 유지한다.
- `projectionRefreshing`은 전체 화면 loading이 아니라 runtime/status panel의 작은 spinner 또는 badge로 표시한다.
- `DR_STATUS_TIMEOUT`, `DR_PROJECTION_STALE`은 "최근 저장 상태 기준으로 표시 중" warning으로 표시한다.
- FTCTL raw JSON은 목록 row나 progress headline에 그대로 표시하지 않는다.

`locked` 메시지 UI 변환:

| 조건 | 목록/상세 표시 | 상세 원문 |
| --- | --- | --- |
| `result=locked`, `retryable=true`, `holder_command=dr-sync-pause` | 이전 일시정지 작업이 정리되는 중입니다. 잠시 후 재시도합니다. | raw JSON collapsible detail |
| retry window 초과 | FTCTL 엔진 lock이 해제되지 않아 작업을 시작하지 못했습니다. | lock holder metadata |
| `DR_STATUS_TIMEOUT` | 대상 호스트 상태 조회가 지연되어 최근 저장 상태를 표시합니다. | timeout answer JSON |

권장 코드 위치:

- `ui/src/utils/dr/status.js`: `normalizeDrPlanEffectiveStatus`, `humanizeDrRunError`
- `ui/src/views/infra/dr/DrPlanList.vue`: list row status와 action disabled reason에 latest run 반영
- `ui/src/views/infra/dr/DrPlanOverview.vue`: initial load와 projection refresh loading state 분리
- `ui/src/components/dr/DrRunProgress.vue`: retryable/failed step headline 정규화, raw JSON은 펼침 영역에만 표시

수용 기준:

- `dr-status` API가 timeout되어도 상세 화면 breadcrumb, 좌측 카드, 기본 탭이 렌더링된다.
- latest run이 `FAILED` 또는 `FAILED_RETRYABLE`이면 plan state가 `SYNCING`이어도 UI는 실패/재시도 대기 상태를 우선 표시한다.
- active run이 `QUEUED`, `PREPARING`, `DISPATCHING`, `RETRYING`, `ACCEPTED`, `RUNNING`, `CANCEL_REQUESTED` 중 하나이면 중복 destructive action을 막는다.

## 24. 2026-07-06 추가 보강: 동기화 접수와 대상 준비 상태 분리

상세 기준은 [534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md](534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md)를 따른다.

UI는 `dr-sync-start`가 접수되었거나 latest run progress가 100으로 표시되는 것만으로 DR Plan을 정상 완료로 표시하지 않는다. VMware -> ABLESTACK 경로에서는 target VM, volume, NIC, restore point, durable checkpoint가 확인되어야 Failover 가능한 준비 완료 상태다.

권장 코드 위치:

- `ui/src/utils/dr/status.js`
  - `normalizeDrPlanEffectiveStatus(plan)`에 `readinessstate`, `targetmaterialized`, `restorepointcount`, `lasttargetdurableat`를 우선 반영한다.
  - `SYNCING` + `engineaccepted=true` + `targetmaterialized=false`는 `ACCEPTED` 또는 `MATERIALIZING`으로 표시한다.
- `ui/src/views/infra/dr/DrPlanList.vue`
  - 목록 컬럼의 상태는 `plan.state` 단독이 아니라 `readinessstate` 기반 effective status를 사용한다.
  - 대상 VM이 없으면 "대상 VM 준비 전" 또는 "대상 생성 중"으로 표시하고 정상 완료 색상을 사용하지 않는다.
- `ui/src/views/infra/dr/DrPlanOverview.vue`
  - 상세 탭에 "대상 준비" 영역을 두고 target VM/volume/NIC/restore point/durable checkpoint를 각각 표시한다.
  - raw ftctl JSON은 펼침 진단 영역으로 이동하고 기본 상태 요약에는 노출하지 않는다.
- `ui/src/components/dr/DrActionMenu.vue`
  - Failover 활성 조건은 `readinessstate === 'TARGET_READY'`와 `targetmaterialized === true`를 모두 만족해야 한다.

수용 기준:

- `engineaccepted=true`지만 target VM이 없으면 UI는 "동기화 완료"로 표시하지 않는다.
- target readiness가 없으면 Failover, Test Failover 계열 버튼은 비활성화된다.
- status refresh가 지연되어도 기존 DB snapshot은 유지되고 대상 준비 영역만 stale warning을 표시한다.

## 2026-07-07 Update: Guided Disk Readiness UX

The DR Plan dialog must prevent a VMware to ABLESTACK sync from being started
when the selected source disk inventory is incomplete. For `VMWARE_TO_KVM`, each
disk row needs a positive source size and resolved target storage/offering
before the submit path enables immediate sync.

UI implementation notes:

- Add `normalizeDiskSizeBytes(value)` and use it for every source disk row.
- Show a blocking message when source disk size is zero, missing, or not numeric.
- Serialize disk mappings with positive `source.sizeBytes`, top-level
  `sizeBytes`, and target disk `sizeBytes`.
- Normalize RBD target storage to `target.type=rbd` and `target.format=raw`.
- Keep the plan page in async mode: after start, show run/projection progress
  and do not infer success from the initial API response.
- Disable next-step actions unless projection contains target VM, target disk,
  durable checkpoint, and restore point evidence.

See
`538-cross-hypervisor-dr-vmware-to-kvm-disk-size-and-projection-hardening-design-20260707.md`
for the exact helper and validation shape.

## 2026-07-08 Update: Initial Sync Pending UI Contract

The UI must not render Fail only because the latest active run contains a stale
or compatibility `DR_TARGET_VM_NOT_FOUND` value while the FTCTL runtime is
healthy and active.

Required UI behavior:

- `runtimeState=SYNCING`, `runtimeStep=full-seed-transfer`,
  `workerState=RUNNING`, empty runtime error -> show `Initial sync in progress`.
- `targetStoragePresent=true`, `targetVmPresent=false`,
  `restorePointPresent=false` -> show `Target disk prepared, target VM pending`.
- Disable failover/test failover until `targetMaterialized=true` and
  `restorePointPresent=true`.
- Only render Fail for terminal evidence: runtime `ERROR`/`FAILED`,
  `workerState=FAILED`, latest run `FAILED`, or non-empty runtime error code.
- Keep backward compatibility by ignoring known pending codes on active runs.

Detailed code-level design:
`546-cross-hypervisor-dr-initial-sync-pending-projection-contract-design-20260708.md`.

## 2026-07-07 Update: DR Plan Default Storage And SharedFS-style Modal Layout

The DR Plan creation/edit dialog must adopt the storage semantics and layout
defined in
[539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md](539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md).

UI rules:

- Rename the top-level storage field to "Default target storage".
- Treat the top-level field as a disk-row initializer and legacy fallback, not
  as a separate required execution value.
- When disk mapping rows are present, each row's storage select is authoritative.
- Provide an explicit "Apply to all disks" action instead of silently
  overwriting per-disk choices when the default changes.
- Rework the DR Plan dialog using the shared filesystem create dialog pattern:
  wide modal, left review summary, right collapsible sections, fixed
  header/footer, and scrollable body content.
- Replace the disk mapping row with a modal-safe grid whose input/select
  controls have stable widths, `min-width: 0`, ellipsis, and tooltips for long
  labels.

This is a UI-focused change. It must preserve the existing async DR Plan create,
preview, and sync-start flow.

## 2026-07-07 Update: DR Plan SharedFS Dialog Standard

The DR Plan create/edit dialog must follow the shared filesystem creation
dialog standard as a UI structure, not only as a visual reference. The detailed
code-level design is documented in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md).

UI implementation rules:

- Keep `DrFormModal.vue` as the fixed header/footer shell and scroll only the
  body content.
- Rework DR Plan create/edit into a wide modal with a left review panel and a
  right configuration panel.
- Replace `cross-dr-form-section-title` divider text inside DR Plan create/edit
  with `a-collapse` sections that match the SharedFS dialog pattern.
- Use explicit section keys such as `basic`, `sites`, `workload`,
  `objectives`, `target`, `disks`, `workers`, `policy`, and `advanced`.
- Add `ui/src/utils/dr/planDialogSections.js` for default active sections and
  field/reason-to-section mapping.
- Open the matching section when local validation or backend readiness returns
  a blocking field/reason.
- Keep top guidance, field hints, placeholder text, and review summary values
  as UI-only state.
- Do not serialize modal layout state, active section state, or review panel
  state into API requests or DB JSON.

This update does not alter DR Plan execution, async sync behavior, Agent
dispatch, ftctl runtime profiles, or DB schema.

## 2026-07-07 Update: DR Plan Modal Alert And Gutter Refinement

The dark-mode guidance alert and right-side modal clipping issue are covered by
the refinement section in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md#13-2026-07-07-refinement-dark-alert-and-right-gutter).

UI implementation rules:

- Treat `DrFormModal.vue` as the owner of modal width and body padding.
- Use `width: 100%` and `max-width: 100%` for
  `.cross-dr-plan-create-dialog`; do not set it to `1120px` inside the modal
  body.
- Add explicit Ant Design alert child selectors for `.ant-alert-message`,
  `.ant-alert-description`, and `.ant-alert-icon`.
- In dark mode, use the SharedFS alert color pattern:
  `rgba(214, 234, 255, 0.94)` text, `rgba(24, 144, 255, 0.12)` background,
  and `rgba(64, 169, 255, 0.35)` border.
- Increase the right gutter of `.cross-dr-plan-config` and use
  `scrollbar-gutter: stable` as progressive enhancement so select arrows and
  selected values do not get clipped.

This is a CSS-only correction. It must not alter the DR Plan form payload,
guided spec generation, async action flow, or stored DR Plan JSON.

## 2026-07-07 Update: VMware Data-Plane Readiness UI

The VMware source VDDK libdir readiness design is defined in
[543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md](543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md).

UI rules:

- DR Site connection health and VMware data-plane readiness are displayed as
  separate concepts.
- The normal DR Site form must not force a user to type the VDDK path.
- An optional advanced override may be shown only as an operator-level field
  for VMware Direct sites.
- DR Plan preview/detail must render readiness blocking reasons:
  `DR_VDDK_LIBDIR_UNRESOLVED`, `DR_VDDK_LIBRARY_LOAD_FAILED`,
  `DR_VMWARE_NBDKIT_FAILED`, and `DR_VMWARE_MOVER_UNAVAILABLE`.
- The sync button remains asynchronous. The UI never waits for data copy;
  it displays API readiness and then polls run/projection state.

Suggested detail labels:

| API field | Korean label |
| --- | --- |
| `capabilities.vmwareDataPlane.state` | VMware 데이터 경로 상태 |
| `capabilities.vmwareDataPlane.vddkLibdir` | VDDK 라이브러리 경로 |
| `capabilities.vmwareDataPlane.vddkLibraryVersion` | VDDK 버전 |
| `capabilities.vmwareDataPlane.moverReady` | VMware mover 준비 |

The detected VDDK path is diagnostic information. It should appear in details
or health history, not as a required first-class creation field.

## 2026-07-07 Update: VMware Mover Source Graph UI Impact

The VMware mover NBD source graph design is defined in
[544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md](544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md).

UI scope is limited to terminal error presentation. The UI must not run mover
preflight directly and must not wait synchronously for `qemu-img`.

Add or map the following runtime error code:

```text
DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID
```

UI rules:

- DR Plan list and detail use API `effectivestate` and latest run state.
- Latest run failure should show the specific source graph error when returned
  by API.
- Failover/test failover actions remain disabled while the latest sync failed.
- The visible message should explain that the VMware source disk connection was
  opened but the engine could not build the QEMU source graph.
- Do not show temporary nbdkit socket paths or credentials in normal UI text.
- Keep this as an error-message/i18n update; no new dialog field or API command
  is required.

## 2026-07-08 Update: Snapshot Resolve Failure And Detail Fallback UI

The VMware VDDK connect follow-up is documented in
[545-cross-hypervisor-dr-vmware-vddk-connect-contract-design-20260708.md](545-cross-hypervisor-dr-vmware-vddk-connect-contract-design-20260708.md#29-live-snapshot-moref-resolve-and-payload-stability-follow-up---2026-07-08).

UI behavior for `DR_VMWARE_SNAPSHOT_REF_UNRESOLVED`:

- DR Plan list/detail must show the plan as `ERROR`, not as deleted or empty.
- Existing plan data must remain on screen while a refresh request is pending.
- A failed `getDrPlan` response must not clear `detailPlan`; the screen should
  fall back to latest run/replica APIs and show a warning banner.
- Action buttons for sync, failover, test failover, failback, and release remain
  disabled until the source snapshot resolves and a durable target checkpoint
  exists.
- Normal UI text must not show raw `details_json`, full `dr-status` JSON,
  temporary vCenter password-file paths, nbdkit sockets, or full event arrays.
- Add localized labels/messages for:
  - `DR_VMWARE_SNAPSHOT_REF_UNRESOLVED`
  - source snapshot state
  - source snapshot cleanup required

Recommended detail loader pattern:

```js
const [plan, runs, replicas] = await Promise.allSettled([
  getDrPlan(id),
  listDrRuns({ planid: id, page: 1, pagesize: 5 }),
  listDrReplicas({ planid: id })
])

if (plan.status === 'fulfilled') {
  detailPlan.value = plan.value
} else {
  detailWarning.value = normalizeApiError(plan.reason)
  detailPlan.value = detailPlan.value || fallbackPlanFromLatestRun(runs)
}
```

This keeps UI state asynchronous and resilient. The UI never waits
synchronously for ftctl or vCenter operations; it only renders compact API
projection state.

## 2026-07-10 Normative History And Topology Update

- `Restore Points` is removed from user-facing terminology and replaced by
  `Synchronization History` or `Synchronization Checkpoint`.
- `Restore Points` and `Runs` are not separate top-level tabs. One `History`
  tab uses a segmented control for Synchronization History and Operation
  History.
- `DrTopology` moves from Details to a dedicated `Protection Topology` tab.
- Events defaults to the latest 20 significant rows.
- Test failover and failover dialogs do not expose checkpoint selection.

Detailed design:
`549-cross-hypervisor-dr-checkpoint-history-event-rpo-design-20260710.md`.

## 2026-07-10 Normative Protection Information And Cached Detail Update

The previous dedicated `Protection Topology` and `Replica` top-level tabs are
superseded. DR Plan detail uses:

```text
Details | Protection Information | History | Events
```

`Protection Information` renders status/run progress, topology, replica
readiness, and latest completed checkpoint from one cached snapshot revision.
Tab query changes do not reload the resource. The UI polls only the cache API,
pauses while the document is hidden, and uses a fast interval only for a
non-terminal operator run.

Events requests always send both `page` and `pagesize`; request failures are
shown as warnings rather than an empty-success state. Legacy `topology` and
`replica` tab URLs redirect to `protection` for one release.

Detailed UI code design:
`550-cross-hypervisor-dr-protection-view-cache-and-completed-checkpoint-design-20260710.md`.

## 2026-07-14 Normative Async Create And Live Cache Update

DR Plan/Site create and update commands are Cloud async commands. The UI must
wait for the async job result before treating the final resource as created or
refreshing a list. A successful POST acknowledgement is not a committed
resource response.

An enabled protection plan continues to poll only `getDrProtectionView` after
the latest operator run becomes terminal. Active runs use a 5-second interval;
steady continuous protection uses a 10-second interval. Polling pauses while
the document is hidden and never calls Agent or FTCTL directly.

The cached `snapshot.plan` is merged into the detail model while preserving the
public UUID as the UI/API identifier. Manual Update is silent on success and
shows only button progress; only actionable failures raise a notification.
Ant Design descriptions use Cross DR surface, text, and border tokens in both
light and dark modes.

Detailed design and acceptance criteria:
`552-cross-hypervisor-dr-async-read-consistency-and-live-cache-ui-design-20260714.md`.

## 2026-07-14 VMware To KVM Test Failover UI Addendum

VMware to ABLESTACK Plan 화면은 Test Failover 전에 guest preparation policy,
isolated test network, boot validation policy, timeout을 typed control로 제공한다.
Test Failover 진행률은 writable layer, OS inspection, VirtIO preparation,
test domain start, boot validation, cleanup/resume 단계로 표시한다.

Overlay 생성만으로 성공을 표시하지 않으며, active test session이 있거나
partial cleanup이 필요한 동안에는 Stop Test Failover action을 유지한다.
상세 설계: `554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md`.

## 2026-07-16 Non-Destructive DR Refresh Addendum

The DR list and detail views must keep the last successfully decoded Plan model
until a newer response has passed transport, JSON decode, and response-shape
validation. A rejected or malformed refresh must not replace the current model
with an empty list or a fabricated `ERROR` Plan. The UI instead displays a
non-blocking stale-data warning and continues to show the last successful
projection timestamp.

The replication view distinguishes physical copy progress from checkpoint
validity. `DATA_COPIED_METADATA_FAILED`, `LOCAL_COMMIT_FAILED`, and
`CLOUD_PROJECTION_PENDING` are recovery states; none may be presented as a
completed restore checkpoint or target-ready replica.

Detailed state and fallback rules:
`557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md`.

## 2026-07-17 Incremental Evidence UI Addendum

Synchronization history displays requested mode, actual mode, localized
decision/reseed reason, transfer ratio, and measured/estimated evidence.
Unexpected automatic full reseed is a warning; a reseed-loop circuit breaker
is an error. Normal Test Failover and planned Failover remain disabled with a
user-facing reason until verified incremental or valid CBT no-change evidence
exists. UI refresh remains asynchronous and display cache never grants action
eligibility. Detailed behavior is in
`559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md`.

### 2026-07-19 Test Failover UI Ownership Addendum

The Test Failover dialog selects an actual target Cloud network. Modes are
`ISOLATED_NETWORK`, explicit `NO_NIC`, and admin-confirmed
`PRODUCTION_NETWORK`. The detail view shows the Cloud test VM link, session
state, checkpoint, network, validation, and residual cleanup count. FTCTL domain
names are diagnostic engine fields and are never presented as the test VM.

Normative UI fields and action gating:
`561-cross-hypervisor-dr-cloud-managed-test-failover-lifecycle-design-20260719.md`.

## 2026-07-25 Site-Derived Failback UI Addendum

일반 source-controller Failback modal은 DR Plan에 등록된 Site 경로를
읽기 전용으로 표시한다. 다음 입력은 제거한다.

- failback target Mold type
- remote Mold API URL/key/secret
- target Mold API URL/key/secret

modal은 active Site, original/source destination Site, Site health,
credential validation summary, latest durable sync, source isolation, reason,
acknowledgement만 표시하거나 입력받는다. credential 오류는 modal에서
재입력받지 않고 DR Site 수정 화면으로 안내한다.

`DrPlanList.vue`의 상세 변경과 Preflight response 계약은
`571-cross-hypervisor-dr-site-derived-failback-contract-design-20260725.md`를
따른다.

## 2026-07-27 Failback Commit Convergence UI Addendum

페일백 commit 응답이 불확실하거나 안전 rollback이 수행된 경우 UI는 Plan의
보호 상태, 최근 작업, 실제 서비스 위치를 분리해 표시한다.

- `COMMIT_VERIFYING`: 커밋 결과 확인 중, 일반 action 잠금
- `FAILED_TARGET_ACTIVE`: 페일백 실패, DR 대상에서 서비스 유지
- `COMMIT_UNCERTAIN`: 실제 실행 위치 확인 필요

목록과 상세의 action gating은 자체 조건식이 아니라 protection snapshot의
canonical action decision을 사용한다. `READY/TARGET`은 정상 상태로 표시하지
않는다. 상세 필드와 polling 규칙은 문서 575를 따른다.

## 2026-07-27 Late ACK and Cache Freshness UI Addendum

상세 화면은 `effectivestate=READY`만으로 failback 완료를 표시하지 않는다.
다음 정보를 별도 필드와 badge로 표현한다.

- lifecycle: `COMMIT_VERIFYING`, `COMPLETED`, `ROLLED_BACK`
- protection: `READY`, `FAILED_OVER_UNPROTECTED`
- serving side: `SOURCE`, `TARGET`, `UNKNOWN`
- cache freshness: 생성 시각, stale 여부, 마지막 refresh 오류

전환 상태는 5초, 안정 상태는 30초 polling을 사용한다. stale cache가 있더라도
기존 데이터를 지우거나 전체 skeleton으로 되돌리지 않고 stale 표시와 마지막
정상 snapshot을 유지한다. canonical terminal snapshot이 도착하기 전에는
충돌 action을 활성화하지 않는다. 상세 UI 계약은 문서 576을 따른다.

## 2026-07-28 Current Authority And Eligibility Projection Addendum

Failover/Failback 이후 보호 화면은 과거 cutover field의 존재 여부로 현재
권한을 판단하지 않는다. `authorityside`, `authorityphase`,
`currentcutoversessionid`가 current 표시의 기준이다.

Protection View snapshot version 4의 `planProjection`을 원자 적용하고
`actioneligibility`도 같은 객체에서 교체한다. version 3 raw Plan cache 또는
authority sequence가 낮은 snapshot은 최신 `getDrPlan` projection을 덮지
않는다. backend eligibility가 없으면 action은 fail-closed한다.

상세 계약은
[578-cross-hypervisor-dr-current-authority-and-ui-eligibility-projection-design-20260728.md](578-cross-hypervisor-dr-current-authority-and-ui-eligibility-projection-design-20260728.md)를
따른다.

## 2026-07-30 Current Warning And Dark-Mode Addendum

보호 정보 상단 경고는 `runtimeerrorcode` 문자열의 존재 여부만으로 표시하지
않는다. current protection이 `ERROR/DEGRADED`, projection integrity가
`INCONSISTENT`, 또는 active Run이 실패하는 경우에만 표시한다. 최근 종료
Run의 실패는 이력이며 현재 경고의 입력이 아니다.

`.cross-dr-risk`는 공통 `--cross-dr-warning-*` 토큰을 사용하고 alert의
message, description, icon 전체에 다크모드 색상을 적용한다. 오류 코드는
i18n 문장의 보조 정보로 표시한다. 상세 computed, template, CSS, 시각 검증은
[580-cross-hypervisor-dr-current-runtime-history-and-darkmode-warning-design-20260730.md](580-cross-hypervisor-dr-current-runtime-history-and-darkmode-warning-design-20260730.md)를
따른다.

## 2026-07-30 Post-Failover UI Convergence Addendum

실제 Failover가 성공한 TARGET authority에서 `FAILED_OVER_UNPROTECTED`는 오류가
아니라 재보호 필요 상태로 표시한다. `DEGRADED` 문자열만으로 일반 `오류`를
표시하지 않으며, RPO는 Failover 시점 값으로 고정한다. 목록과 상세 화면은
Protection View version 6의 typed severity/RPO mode를 공통 resolver로 사용한다.
상세 설계는
[581-cross-hypervisor-dr-post-failover-runtime-ui-convergence-design-20260730.md](581-cross-hypervisor-dr-post-failover-runtime-ui-convergence-design-20260730.md)를
따른다.
## 2026-07-30 Failback Action Acceptance Addendum

DR action UI는 start API 응답의 포장 형태를 직접 추론하지 않는다. 공통
normalizer가 direct/nested/async-job 응답을 `DrRun`으로 변환하고, Run이 즉시
보이지 않으면 동일 `idempotencykey`로 bounded lookup한다. 빈 `runtype`은 실행
실패가 아니며, 복원된 non-empty 타입이 요청과 다를 때만 contract error다.
Failback 권한 전환 중에는 typed `authoritytransitionstate`를 INFO로 표시한다.
상세 계약은 문서 583을 따른다.

## 2026-07-30 Context Action Menu And Dark-Mode Addendum

DR Plan 목록 우클릭과 상세 작업 메뉴는 공통 `ActionButton` 세로 목록을
사용하지 않고 동일한 DR action catalog와 semantic menu를 사용한다. UI는
backend가 반환한 `actionavailability`의 `applicable`, `enabled`,
`reasoncode`를 표현하며 current authority를 자체 재판정하지 않는다.

상태 반대편 작업은 숨기고, 현재 단계에 의미가 있지만 선행 조건이 부족한
작업만 비활성 사유와 함께 표시한다. Pause/Resume, Test Failover/Test Cleanup,
Failover/Failback/Reprotect는 current state에 맞게 교체한다.

다크모드는 `.cross-dr-action-menu` 전용 token과 selector를 사용한다. 비활성
항목은 투명 배경과 낮은 명도 text를 사용하며 전역 `@disabled-bgColor`를
변경하지 않는다. 상세 component, locale, 접근성 및 시각 회귀 기준은
[584-cross-hypervisor-dr-context-action-availability-and-darkmode-design-20260730.md](584-cross-hypervisor-dr-context-action-availability-and-darkmode-design-20260730.md)를
따른다.

## 2026-07-31 Async Action Acceptance Addendum

DR action UI는 `BaseAsyncCmd` 최초 응답의 `jobid`를 먼저
`queryAsyncJobResult`로 확인한다. job 실패 시 실제 `errortext`를 표시하고
Run 복구 조회를 수행하지 않는다. idempotency key 기반 `listDrRuns` 복구는
job 성공 후 typed Run 객체가 누락된 경우에만 사용한다.

Test Failover 활성 여부는 backend의 공통 Test Session lifecycle 판정을
사용하며 UI가 과거 `FAILED` 이력으로 자체 추론하지 않는다. 상세 흐름과
오류 표시는
[586-cross-hypervisor-dr-test-session-blocker-and-async-acceptance-design-20260731.md](586-cross-hypervisor-dr-test-session-blocker-and-async-acceptance-design-20260731.md)를
따른다.
