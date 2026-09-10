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

# Cross Hypervisor DR Site Inventory And Detail UX Design

작성일: 2026-07-03

대상 브랜치: `feature/ftctl-cloud-integration`

상위 문서:

- [500-cross-hypervisor-dr-architecture-plan-20260630.md](500-cross-hypervisor-dr-architecture-plan-20260630.md)
- [506-cross-hypervisor-dr-cloud-ui-design-20260630.md](506-cross-hypervisor-dr-cloud-ui-design-20260630.md)
- [507-cross-hypervisor-dr-cloud-api-command-design-20260630.md](507-cross-hypervisor-dr-cloud-api-command-design-20260630.md)
- [508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md](508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md)
- [527-cross-hypervisor-dr-site-credential-management-design-20260702.md](527-cross-hypervisor-dr-site-credential-management-design-20260702.md)
- [529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md](529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md)

## 1. 목적

이 문서는 DR Site 추가/수정 대화상자와 DR Site 상세 화면에서 확인된 UX/의미 문제를 코드 수준으로 보강하기 위한 설계이다.

이번 보강의 목표는 다음과 같다.

- 사용자가 `zoneid`, `vmwaredcid` 같은 내부 숫자 ID를 직접 입력하지 않게 한다.
- Mold/vCenter 접속 정보는 사용자가 입력하고, Cloud backend가 필요한 inventory를 조회해 UI select option으로 제공한다.
- 대화상자 섹션 제목은 기존 Cloud UI form label과 같은 크기와 대비로 표시한다.
- DR Site 상세 화면에서 raw JSON을 기본 정보처럼 노출하지 않는다.
- 다크모드에서 section label, select, empty/error/help text가 읽히도록 `cross-dr.less` token을 보강한다.
- Site inventory 조회는 Cloud management backend가 담당한다. UI가 원격 Mold/vCenter API를 직접 호출하거나 secret을 보관하지 않는다.

## 2. 현재 등록 상태 확인

2026-07-03 현재 10.10.32.x 테스트 Cloud DB 기준으로 활성 DR Site 3개는 정상 등록되어 있다.

| id | 이름 | 유형 | 하이퍼바이저 | endpoint | health | credential |
| --- | --- | --- | --- | --- | --- | --- |
| 2 | `21 VMware ESXi Cluster` | `VMWARE_DIRECT` | `VMWARE` | `10.10.21.10` | `CONNECTED` | `VCENTER/CONFIGURED` |
| 3 | `32 ABLESTACK Cluster` | `MOLD_KVM` | `KVM` | `http://10.10.32.10:8080/client/api` | `CONNECTED` | `MOLD_API/CONFIGURED` |
| 5 | `Demo VMware Cluster` | `MOLD_VMWARE` | `VMWARE` | `http://10.10.1.10:8080/client/api` | `CONNECTED` | `MOLD_API/CONFIGURED` |

삭제된 기존 row는 `removed`가 채워져 있으며, active list와 scheduler 대상에서 제외되어야 한다.

## 3. 문제 분류

| 구분 | 현재 코드 | 문제 |
| --- | --- | --- |
| 섹션 제목 | `DrSiteList.vue`에서 `a-divider orientation="left"`로 `label.dr.site.connection.info` 렌더링 | Ant divider 기본 typography가 form label보다 커서 대화상자 정보 위계가 깨진다. |
| Zone 입력 | `a-input-number v-model:value="createForm.zoneid"` | 사용자가 CloudStack 내부 zone ID를 알아야 한다. |
| VMware Datacenter 입력 | `a-input-number v-model:value="createForm.vmwaredcid"` | 사용자가 VMware DC 내부 ID를 숫자로 알아야 한다. |
| 상세 raw JSON | `detailSite.capabilities`를 `<pre class="cross-dr-code">`로 표시 | health check 진단 metadata가 일반 상세 정보처럼 노출된다. |
| 원격 Mold inventory | UI에는 remote Mold API key/secret이 있지만 inventory 조회 API 없음 | UI가 직접 원격 Mold를 호출하면 CORS, secret 노출, 권한 경계 문제가 생긴다. |

## 4. UI 상세 설계

### 4.1 DR Site 대화상자 section label

수정 대상:

- `ui/src/views/infra/dr/DrSiteList.vue`
- `ui/src/style/cross-dr.less`

`a-divider`를 form section 전용 markup으로 교체한다.

```vue
<div class="cross-dr-form-section-title">
  <span>{{ $t('label.dr.site.connection.info') }}</span>
</div>
```

권장 CSS:

```less
.cross-dr-form-section-title {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 18px 0 12px;
  color: var(--cross-dr-text);
  font-size: 14px;
  font-weight: 600;
  line-height: 22px;
}

.cross-dr-form-section-title::before,
.cross-dr-form-section-title::after {
  content: "";
  height: 1px;
  background: var(--cross-dr-border);
}

.cross-dr-form-section-title::before {
  width: 16px;
  flex: 0 0 16px;
}

.cross-dr-form-section-title::after {
  flex: 1 1 auto;
}
```

다크모드는 별도 hard-coded 색을 쓰지 않고 기존 `--cross-dr-text`, `--cross-dr-border` token을 사용한다.

### 4.1.1 DR Site 입력 도움말, placeholder, 사전 검증

DR Site 추가/수정 대화상자는 볼륨 생성 화면의 입력 표준을 따른다. 각 입력 라벨은 `TooltipLabel`을 사용하고, 사용자가 기대 형식을 바로 알 수 있도록 placeholder를 둔다.

수정 대상:

- `ui/src/views/infra/dr/DrSiteList.vue`
- `ui/public/locales/en.json`
- `ui/public/locales/ko_KR.json`

`DrSiteList.vue` form item은 단순 `:label="$t(...)"` 대신 label slot을 사용한다.

```vue
<a-form-item required>
  <template #label>
    <tooltip-label
      :title="$t('label.dr.mold.api.url')"
      :tooltip="$t('message.dr.site.mold.api.url.tooltip')" />
  </template>
  <a-input
    v-model:value="createForm.moldapiurl"
    :placeholder="$t('message.dr.site.mold.api.url.placeholder')" />
</a-form-item>
```

필드별 UX 기준:

| 필드 | 도움말/placeholder 기준 | 사전 검증 |
| --- | --- | --- |
| 이름 | DR 계획과 운영 화면에서 식별할 사이트 이름, 예시 표시 | 공백 불가 |
| 설명 | 운영 메모 성격임을 표시 | 선택 입력 |
| 유형 | 신규 등록은 ABLESTACK, VMware Direct만 제공 | 선택 필수 |
| Mold API URL | `/client/api` 형식 예시 표시 | `http://` 또는 `https://` URL |
| Mold API Key/Secret | 원격 Mold 계정의 API/Secret 키임을 설명 | 생성 시 전체 필수, 수정 시 전체 입력 또는 전체 미입력 |
| vCenter URL | vCenter endpoint 예시 표시 | `http://` 또는 `https://` URL |
| vCenter username/password | vCenter 계정 정보임을 설명 | 생성 시 전체 필수, 수정 시 전체 입력 또는 전체 미입력 |
| TLS 인증서 검증 | 자체 서명 테스트 사이트에서만 비활성화하라는 안내 | boolean |
| Zone/VMware 데이터센터 | 원격 inventory에서 조회한 값을 선택한다고 설명 | 선택 입력 |

검증 메시지는 기존 `message.dr.required.fields` 한 가지로 뭉치지 않고, `siteFormValidationMessage()`에서 필드별 i18n 메시지를 반환한다. `validateSiteForm()`은 기존 호출 호환을 위해 `!siteFormValidationMessage()`만 반환한다.

### 4.2 Zone/Datacenter select 전환

대상 field 의미:

- `zoneexternalid`: 원격 Mold site 안의 Zone ID 또는 UUID. `MOLD_KVM`, `MOLD_VMWARE`에서만 의미가 있다.
- `zonename`: 원격 Zone 표시 이름.
- `vmwaredcexternalid`: Mold가 관리하는 VMware Datacenter ID, UUID 또는 MoRef. `MOLD_VMWARE`에서만 의미가 있다.
- `vmwaredcname`: 원격 VMware Datacenter 표시 이름.
- `zoneid`, `vmwaredcid`: local internal id 하위 호환 필드이며 신규 inventory select의 기본 값으로 사용하지 않는다.
- `VMWARE_DIRECT`: vCenter를 직접 바라보므로 Mold Zone/VMware DC ID가 없다.

신규 등록 UI는 `MOLD_KVM`, `VMWARE_DIRECT`만 선택지로 제공한다. `MOLD_VMWARE`는 기존 레코드의 조회 및 하위 호환을 위해 API와 표시 라벨에서만 유지하며 신규 등록 선택지에는 노출하지 않는다. `VMWARE_DIRECT`를 선택하면 vCenter URL, 사용자 이름, 비밀번호가 모두 실제 입력 컨트롤로 렌더링되어야 한다. 유형 변경 시 Vue가 이전 credential form item을 재사용하지 않도록 Mold/vCenter 분기와 각 입력에 서로 다른 안정적인 `key`를 부여한다. 사용자 이름 예시의 `@`는 Vue i18n linked-message 구문으로 해석되지 않도록 locale 원문에서 `{'@'}`로 표기하고, 화면에는 `administrator@vsphere.local`로 렌더링되는지 번역 컴파일 테스트로 검증한다. username 입력은 브라우저 credential 자동완성에 의해 모델 값이 초기화되지 않도록 별도 `name`/`autocomplete` 힌트를 강제하지 않는다.

`DrSiteList.vue` advanced settings는 다음처럼 바꾼다.

```vue
<a-form-item v-if="usesMoldCredential" :label="$t('label.zoneid')">
  <a-select
    v-model:value="createForm.zoneexternalid"
    :options="siteZoneOptions"
    :loading="siteInventoryLoading"
    :placeholder="$t('message.dr.site.select.zone')"
    show-search
    allow-clear
    option-filter-prop="label"
    @focus="fetchSiteInventory"
    @change="changeCreateZone" />
</a-form-item>

<a-form-item v-if="createForm.sitetype === 'MOLD_VMWARE'" :label="$t('label.dr.vmware.dc')">
  <a-select
    v-model:value="createForm.vmwaredcexternalid"
    :options="siteVmwareDcOptions"
    :loading="siteInventoryLoading"
    :disabled="siteZoneOptions.length > 0 && !createForm.zoneexternalid"
    :placeholder="$t('message.dr.site.select.vmware.dc')"
    show-search
    allow-clear
    option-filter-prop="label"
    @focus="fetchSiteInventory" />
</a-form-item>
```

### 4.3 UI state와 method

`data()`에 다음 값을 추가한다.

```js
siteZoneOptions: [],
siteVmwareDcOptions: [],
siteInventoryLoading: false,
siteInventoryError: null,
siteInventoryLoadedKey: null
```

`changeCreateSiteType()`는 site type 변경 시 inventory state를 초기화한다.

```js
resetSiteInventory () {
  this.siteZoneOptions = []
  this.siteVmwareDcOptions = []
  this.siteInventoryError = null
  this.siteInventoryLoadedKey = null
}
```

inventory 조회 key는 create/edit 모드와 credential 입력값을 기준으로 만든다.

```js
buildSiteInventoryKey () {
  return [
    this.siteFormMode,
    this.createForm.id || '',
    this.createForm.sitetype || '',
    this.createForm.moldapiurl || '',
    this.createForm.moldapikey || '',
    this.createForm.tlsverify ? 'tls' : 'notls'
  ].join('|')
}
```

create 모드에서 `moldapiurl`, `moldapikey`, `moldsecretkey`가 모두 없으면 inventory API를 호출하지 않는다. 이때 UI는 select placeholder/help text만 보여준다.

### 4.4 UI API wrapper

`ui/src/api/dr.js`에 wrapper를 추가한다.

```js
export function discoverDrSiteInventory (params = {}) {
  return postAPI('discoverDrSiteInventory', params)
    .then(response => extractDrObject(response, 'discoverDrSiteInventory'))
}
```

실제 CloudStack API가 async job으로 응답하면 기존 DR async helper인 `extractJobId`, `pollAsyncJobResult` 경로를 사용한다.

```js
fetchSiteInventory () {
  if (!this.usesMoldCredential || !this.canDiscoverSiteInventory) {
    return Promise.resolve()
  }

  const loadedKey = this.buildSiteInventoryKey()
  if (this.siteInventoryLoadedKey === loadedKey) {
    return Promise.resolve()
  }

  this.siteInventoryLoading = true
  return discoverDrSiteInventory(this.buildSiteInventoryParams())
    .then(result => this.applySiteInventory(result))
    .catch(error => {
      this.siteInventoryError = this.$pollJobErrorMessage(error)
      this.$notifyError(error)
    })
    .finally(() => {
      this.siteInventoryLoading = false
    })
}
```

### 4.5 상세 raw JSON 제거

`DrSiteList.vue` 상세 탭에서 다음 코드를 제거한다.

```vue
<pre v-if="detailSite.capabilities" class="cross-dr-code">{{ detailSite.capabilities }}</pre>
```

`capabilities_json.healthCheck` 같은 진단 metadata는 기본 상세 화면에 노출하지 않는다. 필요한 경우 `상태 체크 이력` 탭의 row 확장 영역에서만 표시한다.

기본 상세 탭은 `DrResourceDetailsTab`의 label/value row만 사용한다.

## 5. API 상세 설계

### 5.1 신규 command

신규 command:

`plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/DiscoverDrSiteInventoryCmd.java`

```java
@APICommand(name = DiscoverDrSiteInventoryCmd.APINAME,
        description = "Discover inventory options for a Cross Hypervisor DR site",
        responseObject = DrSiteInventoryResponse.class,
        authorized = {RoleType.Admin})
public class DiscoverDrSiteInventoryCmd extends BaseAsyncCmd {
    public static final String APINAME = "discoverDrSiteInventory";
}
```

이 command는 원격 Mold/vCenter 호출이 포함될 수 있으므로 Cloud async job으로 처리한다. UI는 job 수락 후 polling으로 결과를 받는다.

### 5.2 입력 parameter

| parameter | type | 필수 | 설명 |
| --- | --- | --- | --- |
| `id` | UUID | 선택 | 기존 DR Site inventory 조회. 있으면 저장 credential을 사용한다. |
| `sitetype` | STRING | create 모드 필수 | `MOLD_KVM`, `MOLD_VMWARE`, `VMWARE_DIRECT` |
| `moldapiurl` | STRING | create 모드 Mold site 필수 | 저장 전 원격 Mold API endpoint |
| `moldapikey` | STRING | create 모드 Mold site 필수 | write-only API key |
| `moldsecretkey` | STRING | create 모드 Mold site 필수 | write-only secret key |
| `tlsverify` | BOOLEAN | 선택 | TLS 인증서 검증 여부 |
| `zoneexternalid` | STRING | 선택 | VMware DC 조회 시 원격 Zone filter. 신규 UI 기본 경로 |
| `zoneid` | LONG | 선택 | VMware DC 조회 시 local legacy zone filter. `zoneexternalid`가 없을 때만 fallback |
| `includezones` | BOOLEAN | 선택, 기본 true | Zone option 조회 여부 |
| `includevmwaredcs` | BOOLEAN | 선택 | `MOLD_VMWARE`일 때 기본 true |

`VMWARE_DIRECT`는 이 API에서 Mold Zone/Datacenter를 반환하지 않는다.

### 5.3 응답 객체

신규 response:

- `org.apache.cloudstack.api.response.dr.DrSiteInventoryResponse`
- `org.apache.cloudstack.api.response.dr.DrInventoryOptionResponse`

```java
public class DrSiteInventoryResponse extends BaseResponse {
    @SerializedName("siteid")
    private String siteId;
    @SerializedName("sitetype")
    private String siteType;
    @SerializedName("healthstate")
    private String healthState;
    @SerializedName("reasoncode")
    private String reasonCode;
    @SerializedName("message")
    private String message;
    @SerializedName("zones")
    private List<DrInventoryOptionResponse> zones;
    @SerializedName("vmwaredatacenters")
    private List<DrInventoryOptionResponse> vmwareDatacenters;
}

public class DrInventoryOptionResponse extends BaseResponse {
    @SerializedName("id")
    private String id;
    @SerializedName("name")
    private String name;
    @SerializedName("description")
    private String description;
    @SerializedName("type")
    private String type;
}
```

응답에는 API key, secret key, password, token, Authorization header를 포함하지 않는다.

## 6. Backend 상세 설계

### 6.1 Service와 DTO

추가 package:

`plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/inventory`

추가 class:

| class | 역할 |
| --- | --- |
| `DrSiteInventoryService` | command에서 호출하는 inventory facade |
| `DrSiteInventoryServiceImpl` | site type별 credential resolve, client 호출, response 변환 |
| `DrSiteInventoryRequest` | command parameter DTO |
| `DrSiteInventoryResult` | service result DTO |
| `DrInventoryOption` | select option DTO |
| `DrMoldInventoryClient` | Mold API signed request로 `listZones`, `listVmwareDcs` 호출 |

### 6.2 기존 site 조회

`id`가 있으면 다음 순서로 처리한다.

1. `DrSiteService` 또는 `DrSiteDao`로 `removed IS NULL` active site를 조회한다.
2. `DrSiteCredentialService.resolve(site)`로 active credential을 복호화한다.
3. site type이 `MOLD_KVM` 또는 `MOLD_VMWARE`이면 `DrMoldInventoryClient`를 호출한다.
4. site type이 `VMWARE_DIRECT`이면 empty inventory와 `SUPPORTED_NO_MOLD_INVENTORY` reason을 반환한다.

삭제된 site는 inventory 조회 대상이 아니며 `InvalidParameterValueException`으로 실패한다.

### 6.3 create 모드 조회

`id`가 없으면 command parameter의 write-only credential로 임시 `DrSiteCredentialInput`을 만든다. 이 credential은 DB에 저장하지 않는다.

`moldsecretkey`는 memory 안에서 요청 서명에만 사용하고, log, exception message, response에 포함하지 않는다.

### 6.4 Mold API client

`DrMoldInventoryClient`는 기존 health probe와 같은 CloudStack API 서명 규칙을 사용한다.

```java
Map<String, String> params = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
params.put("command", "listZones");
params.put("response", "json");
params.put("apiKey", apiKey);

String signature = DrSiteProbeSupport.signCloudStackRequest(params, secretKey);
String requestUrl = endpoint + "?" + DrSiteProbeSupport.buildQuery(params)
        + "&signature=" + URLEncoder.encode(signature, StandardCharsets.UTF_8.name());
```

서명 알고리즘은 `DrSiteProbeSupport.CLOUDSTACK_API_HMAC_ALGORITHM = HmacSHA256`을 재사용한다.

조회 command:

| inventory | Mold command |
| --- | --- |
| Zone | `listZones` |
| VMware Datacenter | `listVmwareDcs&zoneid=<zoneexternalid>` 우선, legacy fallback으로 `<zoneid>` |

timeout은 health probe와 같은 기준을 사용한다.

- connect timeout: 10초
- read timeout: 15초

### 6.5 상태 갱신 원칙

`discoverDrSiteInventory`는 inventory select option 조회용이다.

- `dr_site.health_state`를 갱신하지 않는다.
- `dr_site_health_check` row를 생성하지 않는다.
- endpoint/credential 검증 결과는 응답의 `healthstate`, `reasoncode`, `message`에만 담는다.

Site 상태를 갱신하려면 기존 `checkDrSite` 또는 scheduler가 담당한다.

## 7. DB 영향 범위

2026-07-03 최초 UI 보강은 신규 DDL 없이 진행했지만, 원격 Mold/vCenter inventory를 정상적인 DR Site 설정값으로 저장하려면
local internal id와 remote external id를 분리하는 DDL이 필요하다. 상세 DDL은 15절과
[509-cross-hypervisor-dr-db-upgrade-and-entity-design-20260630.md](509-cross-hypervisor-dr-db-upgrade-and-entity-design-20260630.md)를 따른다.

컬럼 의미는 다음처럼 정리한다.

| column | 의미 |
| --- | --- |
| `dr_site.zone_id` | local Cloud internal Zone id. 하위 호환/로컬 참조 전용 |
| `dr_site.zone_external_id` | 원격 Mold/vCenter Zone id 또는 uuid |
| `dr_site.zone_name` | 원격 Zone 표시 이름 |
| `dr_site.vmware_datacenter_id` | local VMware DC internal id. 하위 호환/로컬 참조 전용 |
| `dr_site.vmware_datacenter_external_id` | 원격 VMware DC id, uuid 또는 MoRef |
| `dr_site.vmware_datacenter_name` | 원격 VMware DC 표시 이름 |
| `dr_site.capabilities_json` | backend 진단 snapshot 저장용. UI 기본 상세 표시 대상은 아님 |
| `dr_site_credential.secret_payload` | inventory 조회 시 기존 site credential 복호화 source |

UI에서 숫자 입력을 제거하는 것만으로는 충분하지 않다. UI select가 선택한 원격 UUID/external id를 API와 DB가 그대로 저장할 수 있어야 한다.

## 8. Agent와 ftctl 영향 범위

이번 보강은 DR Site 등록/수정/상세 UX와 Cloud control-plane inventory 조회이다.

Agent와 ftctl에는 변경이 필요하지 않다.

- Agent는 DR plan 실행, failover, failback 같은 runtime action 전달 때만 사용한다.
- ftctl은 실제 복제/전환 engine이다.
- Zone/Datacenter select option 조회와 상세 raw JSON 숨김은 runtime engine 계약을 바꾸지 않는다.

## 9. i18n 추가

`ui/src/locales/ko_KR.json`, `ui/src/locales/en.json`에 다음 key를 추가한다.

```json
{
  "message.dr.site.select.zone": "Zone을 선택하세요.",
  "message.dr.site.select.vmware.dc": "VMware 데이터센터를 선택하세요.",
  "message.dr.site.inventory.credential.required": "Mold API 정보를 입력하면 조회할 수 있습니다.",
  "message.dr.site.inventory.unavailable": "사이트 접속 정보를 확인할 수 없습니다.",
  "label.dr.site.connection.info": "사이트 접속 정보"
}
```

기존 key가 있으면 문구만 표준화한다.

## 10. 테스트 기준

| 테스트 | 기대 결과 |
| --- | --- |
| `VMWARE_DIRECT` site 추가 | Zone/VMware Datacenter advanced field가 보이지 않는다. |
| `VMWARE_DIRECT` credential 표시 | vCenter URL, 사용자 이름, 비밀번호 입력이 모두 표시되고 입력 가능하다. |
| 신규 site type 선택 | ABLESTACK과 VMware Direct만 표시되고 ABLESTACK 관리 VMware는 표시되지 않는다. |
| `MOLD_KVM` site 추가 | Zone은 select로 표시되고 `listZones` 결과를 선택한다. VMware Datacenter field는 보이지 않는다. |
| 기존 `MOLD_VMWARE` site | 목록 및 상세 표시와 수정 호환은 유지하되 신규 site type 선택지에는 표시하지 않는다. |
| create 모드 credential 미입력 | inventory API 호출 없이 placeholder/help text만 표시한다. |
| edit 모드 기존 site | 저장 credential로 inventory를 조회하고 기존 `zoneid`, `vmwaredcid` 값을 select에 반영한다. |
| 잘못된 Mold credential | async job 실패 또는 response reason을 UI error로 표시하고 secret을 노출하지 않는다. |
| 상세 화면 | `capabilities` raw JSON `<pre>`가 보이지 않는다. |
| 상태 체크 이력 | health history table은 계속 표시되고, 필요 시 row 확장 진단 정보에서만 non-secret details를 표시한다. |
| 다크모드 | section label, select placeholder, error/help text, modal header/body/footer가 모두 읽힌다. |

## 11. 구현 순서

1. `DrSiteInventoryResponse`, `DrInventoryOptionResponse` 추가.
2. `DrSiteInventoryRequest`, `DrSiteInventoryResult`, `DrInventoryOption` DTO 추가.
3. `DrMoldInventoryClient` 구현. `DrSiteProbeSupport`의 endpoint normalize/sign/read helper를 package 접근 가능하게 조정하거나 inventory package에 안전한 공통 helper를 만든다.
4. `DrSiteInventoryServiceImpl` 구현 및 Spring bean 등록.
5. `DiscoverDrSiteInventoryCmd` 추가 및 API discovery에 노출.
6. `ui/src/api/dr.js` wrapper 추가.
7. `DrSiteList.vue`의 divider, input-number, inventory state/method, raw JSON 렌더링 제거 구현.
8. `cross-dr.less`에 form section title, select/help/error dark mode 보강.
9. `ko_KR`, `en` i18n key 추가.
10. Maven changed module build, UI build, 10.10.32.10 배포 후 `/client/`와 active bundle marker 확인.

## 12. AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| 사이트 접속 정보 label | `a-divider` 기본 typography로 과하게 큼 | form section 전용 class로 기존 Cloud form label 위계에 맞춤 |
| Zone 입력 | 숫자 입력 | backend inventory 조회 결과 select |
| VMware Datacenter 입력 | 숫자 입력 | Zone 기반 VMware DC select |
| 원격 Mold 조회 | UI에 조회 경로 없음 | Cloud backend async command가 write-only credential로 조회 |
| Secret 처리 | UI가 원격 API를 직접 호출하면 노출 위험 | secret은 backend 요청 처리 범위에만 존재하고 response/log에는 미노출 |
| 상세 JSON | `capabilities` raw JSON 표시 | 상세 탭에서 숨김, health history 진단 확장으로만 제한 |
| DB | numeric ID 저장값을 사용자가 직접 입력 | local Long ID와 remote external ID를 분리하고, UI select는 external ID를 저장 |
| Agent/ftctl | 관련 없음 | 계속 변경 없음 |

## 13. 2026-07-03 구현 반영

이번 구현에서 반영한 실제 코드 변경은 다음과 같다.

| 구성요소 | 반영 내용 |
| --- | --- |
| UI | `DrSiteList.vue`에서 `a-divider` 기반 `사이트 접속 정보`를 `.cross-dr-form-section-title`로 교체했다. |
| UI | `zoneid`, `vmwaredcid`의 `a-input-number`를 backend inventory 결과 기반 `a-select`로 교체했다. |
| UI | `detailSite.capabilities` raw JSON `<pre>` 표시를 제거했다. |
| UI | 2026-07-03 최초 구현은 저장 가능한 Long 값이 없는 option을 disabled 처리했으나, 이는 15절 설계로 보정 대상이다. |
| API | `discoverDrSiteInventory` command를 추가했다. |
| Backend | `DrSiteInventoryServiceImpl`, `DrMoldInventoryClient`를 추가하고 `DrSiteProbeSupport`의 HmacSHA256 signer를 재사용하도록 공개 범위를 조정했다. |
| Backend | inventory discovery는 `dr_site.health_state`와 `dr_site_health_check`를 갱신하지 않는 read-only control-plane 조회로 구현했다. |
| DB | 2026-07-03 최초 구현은 신규 DDL 없이 Long 저장 구조를 유지했으나, remote inventory를 정상 저장하려면 15절의 external ID DDL을 추가해야 한다. |
| Agent/ftctl | 변경 없음. runtime action 계약과 host-side script에는 영향 없음. |

주의 사항:

- 현재 DB schema는 `zone_id`가 local `data_center.id`에 가까운 Long 구조이므로 원격 UUID를 저장하기에 맞지 않는다.
- 원격 Mold API가 UUID를 반환하는 경우 이 값은 `zone_external_id`에 저장해야 한다.
- UI에서 UUID option을 비활성으로 표시하는 처리는 폐기하고, 15절 설계에 따라 정상 선택/저장 가능하게 변경한다.
## 14. 2026-07-03 Async Inventory API 보정

`discoverDrSiteInventory`는 Cloud API 규약상 `BaseAsyncCmd`로 등록되어 최초 응답에서 `jobid`를 반환한다.
따라서 UI API 래퍼는 최초 응답을 그대로 폼에 전달하지 않고 `queryAsyncJobResult`를 polling한 뒤
`jobresult.drsiteinventory`를 최종 객체로 반환해야 한다.

구현 반영:

- `ui/src/api/dr.js`에 `waitForDrJobObject()` helper를 추가했다.
- `discoverDrSiteInventory()`는 `jobid`가 있으면 `queryAsyncJobResult`를 최대 30회 polling한다.
- 성공 시 `jobresult.drsiteinventory`를 반환하고, 실패 시 `jobresult.errortext`를 기존 UI 오류 처리 경로로 전달한다.
- 이 보정으로 UI는 비동기 API 원칙을 지키면서도 `DrSiteList.vue`의 inventory select 흐름은 기존처럼 단일 Promise 결과를 사용할 수 있다.

## 15. 2026-07-03 Remote Inventory ID 모델 보정

### 15.1 문제 재정의

이전 구현은 원격 Mold `listZones` 응답에서 numeric `internalid`, `dbid`, `databaseid` 같은 값을 찾고,
그 값이 없으면 option을 disabled 처리했다. 이 처리는 DB `dr_site.zone_id`가 `bigint`라는 현재 제약을
보호하기 위한 방어였지만, 사용자 관점과 DR 도메인 관점에서는 잘못된 모델이다.

DR Site 등록/수정에서 사용자가 선택하는 Zone은 로컬 Cloud의 `data_center.id`가 아니라
원격 사이트의 Zone이다. 원격 사이트가 UUID를 식별자로 반환하면 그 UUID가 정상적인 저장 대상이다.
따라서 UI에 "저장 가능한 내부 ID 없음"을 표시하거나 선택을 막으면 안 된다.

### 15.2 식별자 분리 원칙

앞으로 `dr_site`의 Zone/Datacenter 식별자는 다음처럼 분리한다.

| 구분 | 의미 | 저장 타입 | 사용처 |
| --- | --- | --- | --- |
| `zone_id` | 로컬 Cloud DB 내부 `data_center.id` | `bigint unsigned` | 로컬 ABLESTACK site가 자기 Cloud 내부 Zone을 참조해야 할 때만 사용 |
| `zone_external_id` | 원격 Mold/vCenter가 반환한 Zone 식별자 | `varchar(255)` | DR Site 등록/수정에서 사용자가 선택한 원격 Zone |
| `zone_name` | 원격 Zone 표시 이름 | `varchar(255)` | 목록/상세/수정 폼 표시 |
| `vmware_datacenter_id` | 로컬 Cloud DB 내부 VMware DC row id | `bigint unsigned` | 로컬 DB에 VMware DC mapping row가 있을 때만 사용 |
| `vmware_datacenter_external_id` | 원격 Mold/vCenter가 반환한 VMware DC 식별자 또는 MoRef | `varchar(255)` | Mold-managed VMware target site 선택값 |
| `vmware_datacenter_name` | 원격 VMware DC 표시 이름 | `varchar(255)` | 목록/상세/수정 폼 표시 |

`zone_id`와 `vmware_datacenter_id`는 기존 호환용 local id 필드로 유지한다. 신규 DR Site inventory UI는
기본적으로 external field를 사용한다.

### 15.3 DB 상세 설계

Fresh schema와 upgrade SQL 모두 다음 컬럼을 추가한다.

```sql
ALTER TABLE `cloud`.`dr_site`
  ADD COLUMN `zone_external_id` varchar(255) DEFAULT NULL AFTER `zone_id`,
  ADD COLUMN `zone_name` varchar(255) DEFAULT NULL AFTER `zone_external_id`,
  ADD COLUMN `vmware_datacenter_external_id` varchar(255) DEFAULT NULL AFTER `vmware_datacenter_id`,
  ADD COLUMN `vmware_datacenter_name` varchar(255) DEFAULT NULL AFTER `vmware_datacenter_external_id`,
  ADD KEY `i_dr_site__zone_external_id` (`zone_external_id`),
  ADD KEY `i_dr_site__vmware_dc_external_id` (`vmware_datacenter_external_id`);
```

구현 시점의 실제 schema가 `vmware_dc_id` 이름을 쓰는 branch라면 같은 의미로
`vmware_dc_external_id`, `vmware_dc_name`을 추가한다. 현재 10.10.32.x 배포 DB와 Java entity는
`vmware_datacenter_id`를 사용하므로 구현 기준은 `vmware_datacenter_*` 이름으로 맞춘다.

기존 row migration:

- 기존 `zone_id`/`vmware_datacenter_id`에 값이 있으면 그대로 둔다.
- 기존 row에 external id가 없더라도 자동 변환하지 않는다. local id와 remote id는 동일하다고 보장할 수 없다.
- 수정 화면에서 inventory를 다시 조회해 사용자가 원격 Zone/DC를 선택하면 external field를 채운다.

### 15.4 Java entity/DTO 설계

`DrSiteVO`에 다음 필드를 추가한다.

```java
@Column(name = "zone_external_id")
private String zoneExternalId;

@Column(name = "zone_name")
private String zoneName;

@Column(name = "vmware_datacenter_external_id")
private String vmwareDatacenterExternalId;

@Column(name = "vmware_datacenter_name")
private String vmwareDatacenterName;
```

기존 getter/setter:

- `getZoneId()` / `setZoneId(Long)`는 local internal id 전용으로 유지한다.
- `getVmwareDatacenterId()` / `setVmwareDatacenterId(Long)`는 local internal id 전용으로 유지한다.

신규 getter/setter:

```java
public String getZoneExternalId();
public void setZoneExternalId(String zoneExternalId);
public String getZoneName();
public void setZoneName(String zoneName);
public String getVmwareDatacenterExternalId();
public void setVmwareDatacenterExternalId(String vmwareDatacenterExternalId);
public String getVmwareDatacenterName();
public void setVmwareDatacenterName(String vmwareDatacenterName);
```

`DrInventoryOption`은 numeric 저장 가능 여부 중심 DTO가 아니라 remote inventory option DTO가 되어야 한다.

```java
public class DrInventoryOption {
    private String id;          // remote API raw id or uuid
    private String value;       // UI select value. 기본은 externalId
    private String externalId;  // remote provider stable id
    private Long localId;       // local DB id가 확인되는 경우에만 optional
    private String name;
    private String description;
    private String type;        // ZONE, VMWARE_DATACENTER
    private Map<String, String> details;
}
```

`selectable`은 제거하거나, external id가 전혀 없는 비정상 row에만 false로 쓴다. UUID라는 이유만으로 false가 되면 안 된다.

### 15.5 API 상세 설계

`createDrSite`, `updateDrSite` parameter를 보강한다.

| Parameter | Type | 설명 |
| --- | --- | --- |
| `zoneid` | LONG | local Cloud Zone id. 하위 호환용, 신규 inventory UI 기본값 아님 |
| `zoneexternalid` | STRING | 원격 Mold/vCenter Zone id 또는 uuid |
| `zonename` | STRING | 원격 Zone 표시 이름 |
| `vmwaredcid` | LONG | local VMware DC id. 하위 호환용 |
| `vmwaredcexternalid` | STRING | 원격 VMware DC id/MoRef/uuid |
| `vmwaredcname` | STRING | 원격 VMware DC 표시 이름 |

`discoverDrSiteInventory` parameter를 보강한다.

| Parameter | Type | 설명 |
| --- | --- | --- |
| `zoneexternalid` | STRING | VMware DC 조회 시 원격 Mold `listVmwareDcs`에 전달할 Zone filter |
| `zoneid` | LONG | local id 기반 legacy filter. external id가 있으면 사용하지 않음 |

`DrSiteInventoryResponse` option은 다음 값을 내려준다.

```json
{
  "id": "ece47c3a-f884-4db5-81d9-ce262d2f29d0",
  "value": "ece47c3a-f884-4db5-81d9-ce262d2f29d0",
  "externalid": "ece47c3a-f884-4db5-81d9-ce262d2f29d0",
  "localid": null,
  "name": "Zone",
  "type": "ZONE",
  "details": "{\"provider\":\"MOLD\"}"
}
```

### 15.6 Backend 상세 설계

`DrMoldInventoryClient.toOptions(...)` 변경:

```java
String externalId = firstString(object, "id", "uuid", "externalid");
Long localId = firstLong(object, "internalid", "dbid", "dbId", "databaseid");
option.setId(externalId);
option.setExternalId(externalId);
option.setValue(externalId);
option.setLocalId(localId);
option.setName(firstString(object, "name", "displaytext", "displayname"));
option.setSelectable(StringUtils.isNotBlank(externalId));
```

`listVmwareDatacenters(...)` 변경:

```java
public List<DrInventoryOption> listVmwareDatacenters(DrResolvedSiteCredential credential, String zoneExternalId, Long localZoneId) {
    Map<String, String> params = new LinkedHashMap<>();
    if (StringUtils.isNotBlank(zoneExternalId)) {
        params.put("zoneid", zoneExternalId);
    } else if (localZoneId != null) {
        params.put("zoneid", String.valueOf(localZoneId));
    }
    ...
}
```

`DrSiteServiceImpl.applyMutableSiteFields(...)` 변경:

- `zoneExternalId`, `zoneName`, `vmwareDatacenterExternalId`, `vmwareDatacenterName`을 mutable field로 저장한다.
- `site_type=MOLD_KVM` 또는 `MOLD_VMWARE`이면 external id 저장을 기본 경로로 사용한다.
- `zoneid`/`vmwaredcid`가 함께 들어오면 local id로 저장하되 external id를 지우지 않는다. 둘은 서로 다른 의미다.
- `site_type=VMWARE_DIRECT`이면 Mold Zone field는 저장하지 않는다. VMware Direct에서 필요한 DC MoRef는 별도 vCenter discovery 설계로 확장한다.

`DrResponseGenerator.createSiteResponse(...)` 변경:

- `zoneexternalid`, `zonename`, `vmwaredcexternalid`, `vmwaredcname`을 응답에 포함한다.
- 상세 화면에서는 name을 우선 표시하고, external id는 보조 텍스트나 tooltip로만 표시한다.

Adapter 사용 규칙:

- Mold-managed target 작업은 `zoneExternalId`와 `vmwareDatacenterExternalId`를 remote API parameter로 사용한다.
- local Cloud DAO/FK 조회가 필요한 경우에만 `zoneId`/`vmwareDatacenterId`를 사용한다.
- adapter는 `zoneId`가 null이라는 이유로 Mold site를 불완전하다고 판단하면 안 된다. external id가 있으면 정상 mapping이다.

### 15.7 UI 상세 설계

`DrSiteList.vue` form state 변경:

```js
createForm: {
  zoneid: undefined,                 // local legacy only
  zoneexternalid: undefined,
  zonename: undefined,
  vmwaredcid: undefined,             // local legacy only
  vmwaredcexternalid: undefined,
  vmwaredcname: undefined
}
```

Zone select:

```vue
<a-select
  v-model:value="createForm.zoneexternalid"
  :options="siteZoneOptions"
  @change="changeCreateZone" />
```

Option 생성:

```js
normalizeInventoryOptions (items = []) {
  return items.map(item => ({
    value: item.externalid || item.value || item.id,
    label: item.name || item.description || item.id,
    option: item,
    disabled: !(item.externalid || item.value || item.id)
  }))
}
```

선택 핸들러:

```js
changeCreateZone (value, option) {
  this.createForm.zoneexternalid = value
  this.createForm.zonename = option?.option?.name || option?.label
  this.createForm.vmwaredcexternalid = undefined
  this.createForm.vmwaredcname = undefined
  this.fetchSiteInventory()
}
```

payload 생성:

```js
payload.zoneexternalid = this.createForm.zoneexternalid
payload.zonename = this.createForm.zonename
payload.vmwaredcexternalid = this.createForm.vmwaredcexternalid
payload.vmwaredcname = this.createForm.vmwaredcname
```

삭제할 UI 요소:

- `message.dr.site.inventory.not.selectable`
- option label에 `(저장 가능한 내부 ID 없음)`을 붙이는 로직
- UUID option을 disabled 처리하는 로직

### 15.8 테스트 기준

| 테스트 | 기대 결과 |
| --- | --- |
| MOLD_KVM site 추가 | `listZones`에서 UUID Zone이 오면 선택 가능하고 `zone_external_id`에 저장된다. |
| MOLD_VMWARE site 추가 | Zone UUID 선택 후 `listVmwareDcs&zoneid=<zone_external_id>`로 DC를 조회한다. |
| site 수정 | 저장된 `zoneexternalid`, `zonename`이 select에 복원된다. |
| 기존 local `zone_id` row | 기존 값은 유지되며 external id 선택 전까지 강제 변환하지 않는다. |
| 목록/상세 | Zone은 이름 우선, external id 보조 표시. "내부 ID 없음" 문구는 보이지 않는다. |
| API response | `zoneid`와 `zoneexternalid`가 모두 내려와도 UI는 external field를 우선 사용한다. |

### 15.9 AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| Zone 선택값 | numeric Long 값이 있어야 선택 가능 | UUID/external id도 정상 선택 가능 |
| 사용자 메시지 | `저장 가능한 내부 ID 없음` 표시 | 해당 문구 제거 |
| DB 모델 | 원격 Zone을 `zone_id` Long에 넣으려 함 | 원격 Zone은 `zone_external_id`, 표시명은 `zone_name` |
| VMware DC 모델 | 원격 DC를 `vmware_datacenter_id` Long에 넣으려 함 | 원격 DC는 `vmware_datacenter_external_id`, 표시명은 `vmware_datacenter_name` |
| Backend inventory | numeric id 추출 중심 | provider external id 중심 |
| Adapter | `zoneId` null이면 불완전하게 볼 위험 | Mold site는 external id를 remote API parameter로 사용 |

## 16. 2026-07-04 DR Plan source workload inventory와 입력 UX 보강

이 장은 DR Site inventory 설계의 확장이다. DR Site 대화상자에서 Zone/VMware DC를 backend inventory select로 바꾼 것과 같은 원칙을 DR Plan 대화상자의 source workload 선택에도 적용한다.

상세 UI/API/Backend 설계는 [506-cross-hypervisor-dr-cloud-ui-design-20260630.md](506-cross-hypervisor-dr-cloud-ui-design-20260630.md)의 21장을 따른다.

### 16.1 공통 원칙

- UI는 원격 Mold/vCenter API를 직접 호출하지 않는다.
- UI는 API key, secret, vCenter password, vCenter session token을 보관하거나 response/log에 노출하지 않는다.
- site 선택 이후 필요한 inventory는 Cloud backend가 저장된 site credential을 사용해 조회한다.
- inventory 조회는 control-plane read-only 작업이며 DR runtime state, health check history, last check status를 변경하지 않는다.
- source workload가 local Cloud VM이면 `sourcevmid`를 사용하고, remote Mold/vCenter workload이면 `sourceexternalref`를 사용한다.

### 16.2 API 확장

`discoverDrSiteInventory`는 site 자체의 Zone/Datacenter 선택을 위한 API로 유지한다. DR Plan 생성에는 신규 `discoverDrPlanInventory`를 사용한다.

| API | 목적 | 기본 response |
| --- | --- | --- |
| `discoverDrSiteInventory` | DR Site 등록/수정 중 site 내부 Zone/DC 조회 | `zones`, `vmwaredatacenters` |
| `discoverDrPlanInventory` | DR Plan 등록 중 source workload, worker host, target resource 후보 조회 | `sourceworkloads`, `sourcedisks`, `sourcenetworks`, `sourceworkerhosts`, `targetworkerhosts`, `coordinatorworkerhosts`, `targetstorageoptions`, `targetcomputeoptions`, `targetnetworkoptions`, `targetfolderoptions`, `direction`, `enginetype`, `warnings` |

`discoverDrPlanInventory`는 Cloud API 규약상 async command로 구현하고, UI wrapper는 `jobid`를 받으면 `queryAsyncJobResult` polling 후 `jobresult.drplaninventory`를 반환한다.

### 16.3 응답 option 계약

`DrInventoryOption`/`DrInventoryOptionResponse`는 site inventory와 plan inventory에서 같이 사용하되, plan inventory를 위해 다음 값을 추가한다.

| field | 값 | 사용처 |
| --- | --- | --- |
| `type` | `SOURCE_WORKLOAD`, `SOURCE_DISK`, `SOURCE_NETWORK`, `SOURCE_WORKER_HOST`, `TARGET_WORKER_HOST`, `COORDINATOR_WORKER_HOST`, `TARGET_STORAGE`, `TARGET_COMPUTE`, `TARGET_NETWORK`, `TARGET_FOLDER` | UI option grouping |
| `referenceType` | `CLOUD_VM_ID`, `EXTERNAL_REF`, `HOST_ID` | payload field 결정 |
| `sourceVmId` | local VM UUID | `createDrPlan.sourcevmid` |
| `externalRef` | remote Mold VM UUID, vCenter MoRef/instanceUuid 등 | `createDrPlan.sourceexternalref` |
| `name` | 표시명 | select label |
| `state` | VM power/state | UI 보조 표시와 validation |
| `hypervisor` | KVM/VMWARE | direction/engine validation 보조 |
| `details` | instanceName, zoneName, hostName 등 secret 없는 metadata | tooltip/secondary label |

UI option value는 reference collision을 막기 위해 prefix를 포함한다.

```js
value: `${referenceType}:${sourceVmId || externalRef}`
```

### 16.4 Mold source workload 조회

`DrMoldInventoryClient`에 `listVirtualMachines()`를 추가한다.

```java
public List<DrInventoryOption> listVirtualMachines(
        DrResolvedSiteCredential credential,
        String keyword,
        String zoneExternalId,
        Long zoneId) {
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
    JsonObject response = execute(credential, "listVirtualMachines", params);
    JsonObject payload = getObjectIgnoreCase(response, "listvirtualmachinesresponse");
    return toVirtualMachineOptions(getArrayIgnoreCase(payload, "virtualmachine"));
}
```

Mold response mapping:

| Mold field | DR option field |
| --- | --- |
| `id` | `externalRef` 또는 local VM UUID matching source |
| `displayname`, `name` | `name` |
| `instancename` | `details.instanceName` |
| `state` | `state` |
| `hypervisor` | `hypervisor` |
| `zoneid`, `zonename` | `details.zoneId`, `details.zoneName` |

### 16.5 VMware Direct source workload 조회

`DrVmwareInventoryClient`를 신규 추가한다.

설계 기준:

- `DrResolvedSiteCredential`의 endpoint/principal/secret/tlsVerify를 사용한다.
- `DrSiteProbeSupport.openConnection()`과 TLS 검증 옵션을 재사용한다.
- 우선 vCenter REST session API를 사용하고, 가능한 경우 VM 목록 API로 VM id/name/power state를 조회한다.
- vCenter API variant 차이로 조회가 불가능하면 `InventoryException`을 던지고 UI에는 `message.dr.plan.inventory.unavailable`을 표시한다.
- password와 session token은 response, log, history, details JSON에 남기지 않는다.

VMware option mapping:

| vCenter field | DR option field |
| --- | --- |
| VM MoRef 또는 instance UUID | `externalRef` |
| VM name | `name` |
| power state | `state` |
| folder/resource pool/datacenter | `details` |
| hypervisor | `VMWARE` |

### 16.6 DR Plan form 동작

`DrPlanList.vue`는 다음 순서로 inventory를 갱신한다.

1. `changeSourceSite(siteId)`
   - source site 저장
   - source workload 선택값 초기화
   - target site가 있으면 direction 자동 산출
   - `fetchPlanInventory()` 호출
2. `changeTargetSite(siteId)`
   - target site 저장
   - direction 자동 산출
   - worker host option 초기화 후 inventory 재조회
3. `fetchPlanInventory()`
   - `sourcesiteid`가 없으면 호출하지 않음
   - source/target/direction/keyword로 loaded key를 만들고 중복 호출 방지
   - async job polling wrapper를 통해 option 반영
4. `changeSourceWorkload(value, option)`
   - `referenceType=CLOUD_VM_ID`이면 `sourcevmid` set, `sourceexternalref` clear
   - `referenceType=EXTERNAL_REF`이면 `sourceexternalref` set, `sourcevmid` clear

### 16.7 Validation

UI와 backend validation은 같은 의미를 가져야 한다.

| 검증 | UI | Backend |
| --- | --- | --- |
| plan name | submit 전 메시지 | `validatePlan()` |
| source/target site | submit 전 메시지 | `ensureSitesExist()` |
| same site 차단 | submit 전 메시지 | `validatePlanTopology()` 전 차단 |
| direction 지원 여부 | site 조합에서 자동 산출 실패 시 차단 | `validatePlanTopology()` |
| source workload | `sourcevmid || sourceexternalref` 필수 | `validatePlan()` |
| RPO/RTO | 최소 60초 | `validatePlan()` |
| generated spec | `previewDrPlanSpec` 결과의 warning/blocker 표시 | `DrPlanSpecBuilder`와 방향별 validator가 mapping/schedule/policy/quiesce 의미 검증 |
| JSON field | expert mode에서만 `JSON.parse()` | backward compatibility/expert override 경로에서만 Gson `JsonParser.parseString()` |
| duplicate local VM | 선택 후 preflight warning 가능 | `findActiveBySourceVmId()` |
| duplicate remote workload | 선택 후 preflight warning 가능 | `findActiveBySourceSiteAndExternalRef()` |

### 16.8 DB 영향

inventory 조회 결과는 저장하지 않는다. 다만 remote workload 중복 검증을 위해 `dr_plan`에 다음 index가 필요하다.

```sql
ALTER TABLE `dr_plan`
  ADD INDEX `idx_dr_plan_source_site_external_ref_removed`
    (`source_site_id`, `source_external_ref`, `removed`);
```

신규 column은 만들지 않는다. 기존 `source_external_ref`를 remote Mold/vCenter workload reference 저장 field로 사용한다.

## 17. 2026-07-04 구현 반영: DR Plan 원본 VM 선택형 대화상자

DR Plan 생성 대화상자는 더 이상 `sourcevmid`를 사용자가 직접 입력하지 않는다. UI는 `sourcesiteid`, `targetsiteid` 선택 후 `discoverDrPlanInventory`를 호출하고, 백엔드는 source site credential로 원본 workload 목록을 조회한다.

| 구성요소 | 변경 전 | 변경 후 |
| --- | --- | --- |
| UI | 원본 VM ID 수동 입력 | 사이트 선택 후 원본 VM inventory 선택 |
| API | `createDrPlan`만 사용 | 생성 전 `discoverDrPlanInventory` async API로 원본 workload 조회 |
| Backend | plan 생성 시 문자열/ID만 저장 | source/target site 검증, 방향 자동 산출, workload reference type 반환 |
| DB | `source_vm_id` 중심 중복 검증 | local VM은 `source_vm_id`, remote workload는 `source_site_id + source_external_ref`로 중복 검증 |
| Agent/ftctl | 계획 입력 단계와 직접 연결 없음 | 기존 DR 실행/동기화 엔진 경로 유지 |

`discoverDrPlanInventory` 응답의 `sourceworkloads[]`는 `referencetype`을 포함한다.

- `CLOUD_VM_ID`: 현재 Cloud DB에 존재하는 VM UUID이며 UI는 `createDrPlan.sourcevmid`로 전송한다.
- `EXTERNAL_REF`: remote Mold 또는 vCenter workload reference이며 UI는 `createDrPlan.sourceexternalref`로 전송한다.

UI는 이름, 사이트, 원본 VM, target resource, worker host 선택, RPO/RTO를 사전 검증한다. `mappingjson`, `schedulejson`, `policyjson`, `quiescepolicyjson`은 기본 입력 항목이 아니며, backend가 typed 입력값과 inventory 결과로 생성한다. raw JSON 검증은 expert mode와 backward compatibility API 요청에만 적용한다.

### 16.9 AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| 원본 VM 입력 | 수동 ID 입력 | site 선택 후 backend inventory select |
| Remote source | 사용자가 external ref를 알아야 함 | backend가 Mold/vCenter에서 조회 |
| Payload 결정 | 사용자가 `sourcevmid`에 값을 입력 | option `referenceType`에 따라 `sourcevmid` 또는 `sourceexternalref` 자동 설정 |
| Direction | 사용자가 직접 선택 | source/target hypervisor로 자동 산출 |
| Site inventory와의 관계 | Site와 Plan inventory가 분리되지 않음 | Site inventory는 Zone/DC, Plan inventory는 workload/worker 담당 |
| Credential 보안 | 수동 입력에 기대는 흐름 | backend가 저장 credential을 사용하고 UI는 option만 표시 |

## 18. 2026-07-05 설계 보강: DR Plan guided spec inventory

DR Plan 고급 엔진 설정은 기본 UI에서 raw JSON으로 입력받지 않는다. Plan inventory는 source workload뿐 아니라 target mapping과 worker 선택에 필요한 모든 후보를 제공해야 하며, 이후 `previewDrPlanSpec`이 backend-generated canonical JSON과 warning/blocker를 반환한다. 상세 설계는 [531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md](531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md)를 따른다.

현재 구현 갭:

| 항목 | 설계상 필요 | 현재 구현 | 보강 방향 |
| --- | --- | --- | --- |
| source workload | 필요 | `sourceworkloads` 구현됨 | 유지 |
| source disk/NIC | 필요 | 없음 | workload 선택 후 `sourcedisks`, `sourcenetworks` 반환 |
| source worker host | 필요 | 없음 | `sourceworkerhosts` 반환 |
| target worker host | 필요 | 없음 | `targetworkerhosts` 반환 |
| coordinator worker host | 필요 | 없음 | `coordinatorworkerhosts` 반환 |
| target storage/compute/network/folder | 필요 | 없음 | target site type별 option 반환 |
| generated spec preview | 필요 | 없음 | `previewDrPlanSpec` 추가 |

UI 동작 보강:

1. source/target site 선택 시 direction과 engine은 backend 결과를 우선 사용한다.
2. source workload 선택 후 source disk/NIC inventory를 재조회한다.
3. target site type에 따라 storage/compute/network/folder 선택 UI를 표시한다.
4. worker host는 직접 UUID 입력이 아니라 inventory option select로 표시한다.
5. `previewDrPlanSpec` 결과에 blocker가 있으면 확인 버튼을 비활성화한다.
6. expert mode가 꺼져 있으면 JSON textarea는 렌더링하지 않는다.

## 19. 2026-07-06 VMware -> ABLESTACK Plan Inventory 보강

DR Site inventory와 DR Plan inventory는 역할을 분리하되 끊어지면 안 된다. Site inventory는 KVM target site의 Zone을 저장하고, Plan inventory는 그 Zone을 기준으로 ABLESTACK 대상 VM 생성에 필요한 worker/storage/offering/network/disk mapping 후보를 제공한다.

### 19.1 Site와 Plan의 연결 규칙

| 단계 | 책임 | 실패 시 표시 |
| --- | --- | --- |
| DR Site 생성/수정 | KVM target site의 `zone_id`, `zone_external_id`, `zone_name` 저장 | Site 대화상자에서 Zone 선택 필요 |
| DR Plan inventory | target Zone 기준 worker/storage/service offering/disk offering/network 조회 | `TARGET_SITE_ZONE_REQUIRED` 또는 항목별 empty blocker |
| DR Plan 생성/수정 | 선택된 후보로 canonical `mapping_json` 생성 | `CONFIG_INCOMPLETE` readiness |

### 19.2 VMware source workload 상세 inventory

`VMWARE_TO_KVM`에서 source VM을 선택하면 VM 목록 ref만 저장하지 않고 vCenter disk/NIC 상세를 조회해야 한다. 이 결과는 Plan 대화상자의 disk mapping table과 network mapping 선택값의 source column으로 사용한다.

### 19.3 ABLESTACK target 후보

Plan inventory는 KVM target site에 대해 다음 후보를 반환한다.

- target worker host
- coordinator worker host
- primary storage pool
- service offering
- disk offering
- network

후보가 정확히 1개이면 UI가 자동 선택할 수 있다. 후보가 0개이면 blocking alert를 표시하고, 후보가 2개 이상이면 사용자가 명시적으로 선택한다. 상세 코드 설계는 [531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md](531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md)의 15장을 따른다.
