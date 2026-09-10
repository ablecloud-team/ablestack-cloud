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

# Cross Hypervisor DR Site Health Check And Delete Consistency Design

작성일: 2026-07-02

상위 문서:

- [500-cross-hypervisor-dr-architecture-plan-20260630.md](500-cross-hypervisor-dr-architecture-plan-20260630.md)
- [501-cross-hypervisor-dr-domain-schema-design-20260630.md](501-cross-hypervisor-dr-domain-schema-design-20260630.md)
- [506-cross-hypervisor-dr-cloud-ui-design-20260630.md](506-cross-hypervisor-dr-cloud-ui-design-20260630.md)
- [507-cross-hypervisor-dr-cloud-api-command-design-20260630.md](507-cross-hypervisor-dr-cloud-api-command-design-20260630.md)
- [508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md](508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md)
- [509-cross-hypervisor-dr-db-upgrade-and-entity-design-20260630.md](509-cross-hypervisor-dr-db-upgrade-and-entity-design-20260630.md)
- [527-cross-hypervisor-dr-site-credential-management-design-20260702.md](527-cross-hypervisor-dr-site-credential-management-design-20260702.md)
- [529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md](529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md)

## 1. 배경과 결론

DR Site 목록에서 `VMWARE_DIRECT` site가 생성되고 `lastchecked`가 갱신되었지만 `healthstate=UNKNOWN`으로 남는 현상이 확인되었다. DB와 로그 기준으로 Cloud backend와 async job은 동작 중이나, 현재 `DrSiteServiceImpl.checkSite(long siteId)`는 실제 endpoint나 credential 검증을 수행하지 않고 `lastChecked`만 갱신한다.

또한 삭제 실패 후 site row는 남고 credential은 `CLEARED` 상태로 남아 UI/API가 이를 `credentialconfigured=true`처럼 표현할 수 있는 불일치가 확인되었다.

결론:

1. `checkDrSite`는 no-op timestamp update가 아니라 type-specific health probe를 수행해야 한다.
2. usable credential은 `removed IS NULL`만으로 판단하지 않고 `state=CONFIGURED`와 `dr_site.credential_id` 정합성을 같이 봐야 한다.
3. site 삭제와 credential clear는 하나의 트랜잭션으로 묶어 부분 변경을 남기지 않아야 한다.
4. UI는 `UNKNOWN`을 장애처럼 뭉뚱그리지 않고 `미점검`, `검증 불가`, `실패`, `정상`을 구분해서 표시해야 한다.
5. Site health check는 Cloud backend 책임이다. Agent/ftctl은 DR plan runtime action에서만 credential을 받아 사용한다.

## 2. 상태 모델

`dr_site.state`는 사용자가 site를 사용할 수 있도록 열어 두었는지를 나타내는 admin state다.

| 값 | 의미 |
| --- | --- |
| `ENABLED` | plan 생성과 action 후보로 사용할 수 있음 |
| `DISABLED` | 사용자가 비활성화함 |

`dr_site.health_state`는 마지막 endpoint 검증 결과다. 구현 기준 값은 `DrConstants`와 동일하게 `CONNECTED`, `DEGRADED`, `DISCONNECTED`, `UNKNOWN`을 사용한다.

| 값 | 의미 | UI 표시 |
| --- | --- | --- |
| `CONNECTED` | endpoint 접속과 인증 검증 성공 | 정상 |
| `DEGRADED` | 접속은 가능하나 TLS 경고, capability 일부 실패 등 주의 필요 | 주의 |
| `DISCONNECTED` | credential 누락, 인증 실패, 네트워크 실패, API 오류 | 실패 |
| `UNKNOWN` | 아직 점검하지 않았거나 결과를 판정할 수 없음 | 미점검 |

`DrConstants`에는 다음 값을 추가한다.

```java
public static final String HEALTH_CONNECTED = "CONNECTED";
public static final String HEALTH_DEGRADED = "DEGRADED";
public static final String HEALTH_DISCONNECTED = "DISCONNECTED";
public static final String HEALTH_UNKNOWN = "UNKNOWN";

public static final String HEALTH_REASON_CREDENTIAL_MISSING = "CREDENTIAL_MISSING";
public static final String HEALTH_REASON_CREDENTIAL_INVALID = "CREDENTIAL_INVALID";
public static final String HEALTH_REASON_ENDPOINT_UNREACHABLE = "ENDPOINT_UNREACHABLE";
public static final String HEALTH_REASON_ENDPOINT_HTTP_ERROR = "ENDPOINT_HTTP_ERROR";
public static final String HEALTH_REASON_MOLD_API_OK = "MOLD_API_OK";
public static final String HEALTH_REASON_VCENTER_API_OK = "VCENTER_API_OK";
public static final String HEALTH_REASON_VCENTER_ENDPOINT_REACHABLE = "VCENTER_ENDPOINT_REACHABLE";
public static final String HEALTH_REASON_UNSUPPORTED_SITE_TYPE = "UNSUPPORTED_SITE_TYPE";
```

Credential 기준:

1. `dr_site.credential_id`가 있으면 해당 id의 credential을 우선 조회한다.
2. 조회된 row는 `site_id`가 일치해야 한다.
3. `removed IS NULL`이어야 한다.
4. `state=CONFIGURED`이어야 한다.
5. `secret_payload`가 복호화 가능하고 필수 secret을 모두 포함해야 한다.

`CLEARED`, `INVALID`, `LEGACY_REF`, `MISSING`은 usable credential이 아니다.

## 3. Backend 상세 설계

### 3.1 신규 DTO

경로:

`plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/health/DrSiteHealthCheckResult.java`

```java
public class DrSiteHealthCheckResult {
    private long siteId;
    private String siteUuid;
    private String healthState;
    private String reasonCode;
    private String message;
    private String credentialState;
    private String credentialType;
    private String endpoint;
    private Long latencyMs;
    private Date checkedAt;
    private JsonObject capabilitySnapshot;

    public DrSiteHealthCheckResult(String healthState, String reasonCode, String message, Long latencyMs, Date checkedAt, boolean credentialValidated);
}
```

`message`는 UI 표시용 짧은 문장이고 secret을 포함하면 안 된다. 상세 원인은 `reasonCode`와 redacted `capabilitySnapshot`에 담는다.

### 3.2 Probe interface

경로:

`plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/health/`

```java
public interface DrSiteProbe {
    boolean supports(DrSiteVO site, DrSiteCredentialVO credential);

    DrSiteHealthCheckResult check(DrSiteVO site, DrResolvedSiteCredential credential);
}
```

구현체:

| Class | 대상 | 검증 방식 |
| --- | --- | --- |
| `DrMoldSiteProbe` | `MOLD_KVM`, `MOLD_VMWARE`, `MOLD_API` credential | Mold API URL 정규화 후 signed `listCapabilities` 또는 `listZones` 호출 |
| `DrVmwareDirectSiteProbe` | `VMWARE_DIRECT`, `VCENTER` credential | vCenter URL 정규화 후 REST session 또는 SOAP login 검증 |
| `DrUnsupportedSiteProbe` | 지원되지 않는 조합 | `UNKNOWN` 또는 `DISCONNECTED/DR_SITE_CHECK_UNSUPPORTED` 반환 |

`DrMoldSiteProbe`는 기존 `DisasterRecoveryClusterUtil`의 CloudStack API signing 로직을 직접 호출하기보다 공통 signer를 분리해 재사용한다. 기존 util의 `buildUrl/signRequest`가 private/protected이면 신규 `DrCloudApiClient`를 `com.cloud.dr.health`에 두고 최소 signed GET 호출만 구현한다.

```java
public class DrCloudApiClient {
    public JsonObject get(String apiUrl, String apiKey, String secretKey, String command, Map<String, String> params, boolean tlsVerify);
}
```

### 3.2.1 2026-07-03 ABLESTACK/Mold API 서명 알고리즘 보강

10.10.32 환경에서 `MOLD_API` credential로 등록한 ABLESTACK site가 `DISCONNECTED/CREDENTIAL_INVALID`로 남는 문제가 확인되었다. DB 이력의 직접 원인은 Mold API가 HTTP 401을 반환한 것이며, 코드 기준 원인은 신규 health probe의 CloudStack API 서명 알고리즘이 기존 성공 경로와 달랐기 때문이다.

확인된 현재 코드:

| 경로 | 현재 동작 | 판단 |
| --- | --- | --- |
| `com.cloud.dr.health.DrSiteProbeSupport.signCloudStackRequest` | `Mac.getInstance("HmacSHA1")` | ABLESTACK/Mold API 인증 실패 원인 |
| `com.cloud.dr.cluster.DisasterRecoveryClusterUtil.signRequest` | `Mac.getInstance("HmacSHA256")` | 기존 remote Mold 성공 경로 |

TO-BE 원칙:

1. DR site health check의 Mold API 서명은 `HmacSHA256`을 고정 사용한다.
2. UI/API는 서명 알고리즘을 입력받지 않는다. 알고리즘은 backend 구현 계약이다.
3. Secret/API key 원문은 response, log, history `details_json`, `capabilities_json`에 기록하지 않는다.
4. 진단 가능성을 위해 non-secret 값인 `authAlgorithm=HmacSHA256`, `probe=DrMoldSiteProbe`, `apiCommand=listCapabilities`만 health details에 남긴다.
5. Agent/ftctl에는 영향이 없다. Site health check는 Cloud management backend에서 종료된다.

권장 코드 설계:

```java
// plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/health/DrCloudStackApiSigner.java
public final class DrCloudStackApiSigner {
    public static final String HMAC_ALGORITHM = "HmacSHA256";

    private DrCloudStackApiSigner() {
    }

    public static String sign(Map<String, String> params, String secretKey) throws Exception {
        List<String> sortedParams = new ArrayList<String>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (StringUtils.isBlank(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            sortedParams.add(entry.getKey().toLowerCase() + "="
                    + DrSiteProbeSupport.urlEncode(entry.getValue()).toLowerCase());
        }
        Collections.sort(sortedParams);
        String request = StringUtils.join(sortedParams, "&");
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        mac.init(keySpec);
        return DrSiteProbeSupport.urlEncode(Base64.encodeBase64String(
                mac.doFinal(request.getBytes(StandardCharsets.UTF_8))));
    }
}
```

최소 구현으로는 `DrSiteProbeSupport.signCloudStackRequest()` 내부의 `HmacSHA1` 두 곳을 `HmacSHA256` 상수로 치환해도 된다. 단, 이후 재발 방지를 위해 신규 public signer 또는 `DrConstants.CLOUDSTACK_API_HMAC_ALGORITHM`처럼 단일 상수를 두는 방향을 권장한다.

```java
// plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/health/DrSiteProbeSupport.java
private static final String CLOUDSTACK_API_HMAC_ALGORITHM = "HmacSHA256";

static String signCloudStackRequest(Map<String, String> params, String secretKey) throws Exception {
    ...
    Mac mac = Mac.getInstance(CLOUDSTACK_API_HMAC_ALGORITHM);
    SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8),
            CLOUDSTACK_API_HMAC_ALGORITHM);
    ...
}
```

`DrMoldSiteProbe`는 현재처럼 `listCapabilities`를 호출하되, 응답 판정은 다음 기준을 유지한다.

```java
if (responseCode >= 200 && responseCode < 300) {
    return result(HEALTH_CONNECTED, HEALTH_REASON_MOLD_API_OK,
            "Mold API credentials were validated", started, checkedAt, true);
}
if (responseCode == 401 || responseCode == 403) {
    return result(HEALTH_DISCONNECTED, HEALTH_REASON_CREDENTIAL_INVALID,
            "Mold API authentication failed with HTTP " + responseCode, started, checkedAt, false);
}
return result(HEALTH_DISCONNECTED, HEALTH_REASON_ENDPOINT_HTTP_ERROR,
        "Mold API returned HTTP " + responseCode, started, checkedAt, false);
```

진단 이력 보강은 `DrSiteHealthCheckResult`에 redacted details를 추가하는 방식이 가장 명확하다.

```java
public class DrSiteHealthCheckResult {
    private final JsonObject details;

    public DrSiteHealthCheckResult(String healthState, String reasonCode, String message,
            Long latencyMs, Date checkedAt, boolean credentialValidated) {
        this(healthState, reasonCode, message, latencyMs, checkedAt, credentialValidated, null);
    }

    public DrSiteHealthCheckResult(String healthState, String reasonCode, String message,
            Long latencyMs, Date checkedAt, boolean credentialValidated, JsonObject details) {
        ...
        this.details = details == null ? new JsonObject() : details.deepCopy();
    }

    public JsonObject getDetails() {
        return details.deepCopy();
    }
}
```

`DrMoldSiteProbe`는 secret 없이 다음 details만 넣는다.

```java
private JsonObject moldProbeDetails() {
    JsonObject details = new JsonObject();
    details.addProperty("probe", "DrMoldSiteProbe");
    details.addProperty("apiCommand", "listCapabilities");
    details.addProperty("authAlgorithm", DrCloudStackApiSigner.HMAC_ALGORITHM);
    return details;
}
```

`DrSiteServiceImpl.mergeHealthCheckResult()`와 `DrSiteHealthCheckHistoryServiceImpl.buildDetailsJson()`은 `result.getDetails()`를 merge한다. 이때 `apiKey`, `secretKey`, `password`, `token`, `Authorization` 키는 방어적으로 제외한다.

`DrVmwareDirectSiteProbe`는 다음 순서로 검증한다.

1. `vcenterUrl`이 host만 들어온 경우 `https://<host>`로 정규화한다.
2. REST endpoint `POST /rest/com/vmware/cis/session`에 basic auth를 시도한다.
3. REST session API가 404 또는 미지원이면 `/sdk` SOAP login으로 fallback한다.
4. `tlsVerify=false`일 때만 trust-all SSL context를 사용한다. 기존 `VMwareUtil.getVMwareConnection(LoginInfo)`는 항상 trust-all을 적용하므로, 엄격 TLS가 필요한 경우 `DrVCenterProbeClient`에 별도 SSL 분기를 둔다.
5. 성공 시 vCenter product/version 같은 non-secret capability를 snapshot에 담는다.

### 3.3 Health check service

경로:

`plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/health/DrSiteHealthCheckService.java`

```java
public interface DrSiteHealthCheckService {
    DrSiteHealthCheckResult check(long siteId, boolean persistStatus);
}
```

구현:

`DrSiteHealthCheckServiceImpl`

```java
public DrSiteHealthCheckResult check(long siteId, boolean persistStatus) {
    DrSiteVO site = requireActiveSite(siteId);
    DrSiteCredentialVO credential = drSiteCredentialService.findConfiguredCredential(site);

    DrSiteHealthCheckResult result;
    if (credential == null) {
        result = DrSiteHealthCheckResult.failed(site, ERROR_CREDENTIAL_MISSING,
            "Usable site credential is not configured.");
    } else {
        try (DrResolvedSiteCredential resolved = drSiteCredentialService.resolveCredential(site)) {
            result = selectProbe(site, credential).check(site, resolved);
        } catch (Exception e) {
            result = DrSiteHealthCheckResult.failed(site, mapReasonCode(e), redact(e.getMessage()));
        }
    }

    if (persistStatus) {
        persistResult(site, credential, result);
    }
    return result;
}
```

`persistResult` 규칙:

- `dr_site.health_state = result.healthState`
- `dr_site.last_checked = result.checkedAt`
- `dr_site.capabilities_json`에 `healthCheck` object를 merge한다.
- 성공이면 `dr_site_credential.last_validated = checkedAt`
- 인증 실패이면 credential row를 자동으로 `CLEARED`로 바꾸지 않는다. 저장된 값이 틀렸다는 운영 판단을 남기기 위해 `CONFIGURED`를 유지하되 `capabilities_json.healthCheck.reasonCode=CREDENTIAL_INVALID`로 표시한다.
- credential row 자체가 clear된 경우에만 `credentialstate=CLEARED`로 표시한다.

### 3.4 DrSiteService 변경

현재:

```java
DrSiteVO checkSite(long siteId)
```

변경:

```java
DrSiteVO checkSite(long siteId, boolean persistStatus);
DrSiteHealthCheckResult checkSiteHealth(long siteId, boolean persistStatus);
```

호환성을 위해 기존 `checkSite(long siteId)`는 유지하고 `persistStatus=true`로 위임한다.

```java
@Override
public DrSiteVO checkSite(long siteId) {
    return checkSite(siteId, true);
}

@Override
public DrSiteVO checkSite(long siteId, boolean persistStatus) {
    drSiteHealthCheckService.check(siteId, persistStatus);
    return drSiteDao.findById(siteId);
}
```

`CheckDrSiteCmd.execute()`는 `persiststatus`를 실제로 전달해야 한다.

```java
boolean persist = getPersistStatus() == null || Boolean.TRUE.equals(getPersistStatus());
DrSiteResponse response = drResponseGenerator.createSiteResponse(drSiteService.checkSite(id, persist));
```

### 3.5 Credential DAO와 service 변경

`DrSiteCredentialDao`:

```java
DrSiteCredentialVO findConfiguredBySiteId(long siteId);
DrSiteCredentialVO findConfiguredByIdAndSiteId(long id, long siteId);
DrSiteCredentialVO findLatestBySiteId(long siteId);
```

`DrSiteCredentialDaoImpl`:

```java
configuredBySiteSearch.and("siteId", ..., EQ);
configuredBySiteSearch.and("state", ..., EQ);
configuredBySiteSearch.and("removed", ..., NULL);

configuredByIdAndSiteSearch.and("id", ..., EQ);
configuredByIdAndSiteSearch.and("siteId", ..., EQ);
configuredByIdAndSiteSearch.and("state", ..., EQ);
configuredByIdAndSiteSearch.and("removed", ..., NULL);
```

`DrSiteCredentialService`:

```java
DrSiteCredentialVO findConfiguredCredential(DrSiteVO site);
DrSiteCredentialVO findLatestCredential(long siteId);
DrResolvedSiteCredential resolveCredential(DrSiteVO site);
boolean hasUsableCredential(DrSiteVO site);
```

`resolveCredential`는 `state=CONFIGURED`가 아닌 row를 절대 반환하지 않는다. `credentialRef` legacy fallback은 health check에는 사용하지 않는다. legacy site는 `LEGACY_REF` 또는 `MISSING`으로 표시하고 credential 재등록을 요구한다.

### 3.6 Response generator 변경

현재:

```java
response.setCredentialConfigured(credential != null || site.getCredentialRef() != null);
```

변경:

```java
DrSiteCredentialVO configured = drSiteCredentialService.findConfiguredCredential(site);
DrSiteCredentialVO latest = configured != null ? configured : drSiteCredentialService.findLatestCredential(site.getId());

response.setCredentialConfigured(configured != null);
if (latest != null) {
    response.setCredentialType(latest.getCredentialType());
    response.setCredentialEndpoint(latest.getEndpoint());
    response.setCredentialPrincipal(latest.getPrincipal());
    response.setCredentialState(latest.getState());
    response.setCredentialLastValidated(latest.getLastValidated());
} else if (StringUtils.isNotBlank(site.getCredentialRef())) {
    response.setCredentialConfigured(false);
    response.setCredentialState("LEGACY_REF");
} else {
    response.setCredentialConfigured(false);
    response.setCredentialState("MISSING");
}
```

추가 응답 field 후보:

| Field | Source |
| --- | --- |
| `healthreasoncode` | `capabilities_json.healthCheck.reasonCode` |
| `healthmessage` | `capabilities_json.healthCheck.message` |
| `healthlatencyms` | `capabilities_json.healthCheck.latencyMs` |
| `credentialusable` | configured credential 존재 여부 |

Secret payload는 response에 포함하지 않는다.

### 3.7 Delete transaction 보강

현재 `deleteSite`는 credential clear를 먼저 수행한 뒤 site soft delete를 수행한다. site delete가 실패하면 credential만 clear되는 부분 변경이 남을 수 있다.

2026-07-03 운영 점검 결과, `DeleteDrSiteCmd` async job이 `success=true`로 종료되었지만 `dr_site.removed`가 `NULL`로 남는 현상이 확인되었다. 원인은 CloudStack 공통 DAO가 `removed` 컬럼을 일반 `update()` 대상에서 제외하고 `GenericDao.remove(id)` 전용 SQL로만 soft delete하기 때문이다. 따라서 DR 도메인에서는 `markRemoved(); dao.update(id, vo);` 패턴을 삭제 구현에 사용하면 안 된다.

변경 원칙:

1. active plan 참조를 먼저 검증한다.
2. site soft delete와 credential soft delete를 하나의 DB transaction으로 묶는다.
3. credential clear는 configured/latest credential row에 대해 `state=CLEARED`를 먼저 저장한 뒤 `drSiteCredentialDao.remove(id)`로 `removed`를 채운다.
4. site의 `credential_id`, `credential_ref` 제거는 일반 update로 처리하고, site soft delete는 반드시 `drSiteDao.remove(siteId)`로 처리한다.
5. `DrPlanServiceImpl.deletePlan`도 같은 이유로 `plan.markRemoved(); drPlanDao.update(...)`를 금지하고 `drPlanDao.remove(planId)`를 사용한다.
6. delete 완료 후 `findByIdIncludingRemoved(id)`로 `removed != null`을 검증한다. 검증 실패는 async job 실패로 반환한다.
7. site delete가 실패하면 credential update도 rollback되어야 한다.

구현 skeleton:

```java
@Override
@DB
public boolean deleteSite(long siteId) {
    return Transaction.execute((TransactionCallback<Boolean>) status -> {
        DrSiteVO site = requireSite(siteId);
        long activePlanCount = drPlanDao.countActiveBySiteId(siteId);
        if (activePlanCount > 0L) {
            throw new InvalidParameterValueException(DrConstants.ERROR_ACTIVE_PLAN_EXISTS
                + ": " + activePlanCount + " active DR plan(s) refer to site " + site.getUuid());
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
        if (removedSite == null || removedSite.getRemoved() == null) {
            throw new CloudRuntimeException("DR site soft delete was not persisted " + site.getUuid());
        }
        return true;
    });
}
```

`clearCredentialsForDeletedSite`는 site row를 다시 update하지 않는다. 삭제 transaction 안에서 credential row만 soft delete한다.

```java
public int clearCredentialsForDeletedSite(long siteId) {
    int cleared = 0;
    for (DrSiteCredentialVO credential : drSiteCredentialDao.listBySiteId(siteId)) {
        if (credential == null || credential.getRemoved() != null) {
            continue;
        }
        credential.setState(DrConstants.CREDENTIAL_STATE_CLEARED);
        credential.markUpdated();
        if (!drSiteCredentialDao.update(credential.getId(), credential)) {
            throw new CloudRuntimeException("Failed to clear DR site credential " + credential.getUuid());
        }
        if (!drSiteCredentialDao.remove(credential.getId())) {
            throw new CloudRuntimeException("Failed to soft delete DR site credential " + credential.getUuid());
        }
        DrSiteCredentialVO removedCredential = drSiteCredentialDao.findByIdIncludingRemoved(credential.getId());
        if (removedCredential == null || removedCredential.getRemoved() == null) {
            throw new CloudRuntimeException("DR site credential soft delete was not persisted " + credential.getUuid());
        }
        cleared++;
    }
    return cleared;
}
```

`DrPlanServiceImpl.deletePlan`도 동일한 DAO 규칙을 따른다.

```java
public boolean deletePlan(long planId) {
    DrPlanVO plan = requirePlan(planId);
    if (drRunDao.findActiveByPlanId(planId) != null) {
        throw new InvalidParameterValueException(DrConstants.ERROR_ACTIVE_RUN_EXISTS + ": active run exists for plan " + planId);
    }
    if (hasRuntimeResources(planId, plan) || isProtectedPlanState(plan)) {
        throw new InvalidParameterValueException(DrConstants.ERROR_RUNTIME_RESOURCE_EXISTS
                + ": release DR protection and cleanup runtime resources before deleting plan " + planId);
    }
    if (!drPlanDao.remove(planId)) {
        throw new CloudRuntimeException("Failed to soft delete DR plan " + plan.getUuid());
    }
    DrPlanVO removedPlan = drPlanDao.findByIdIncludingRemoved(planId);
    return removedPlan != null && removedPlan.getRemoved() != null;
}
```

### 3.8 Deleted site health-check guard

삭제가 soft delete이므로 상태체크는 `removed IS NULL`인 site에 대해서만 수행한다. 현재 `DrSiteServiceImpl.listSites()`가 `DrSiteDao.listActive()`를 호출하고 `DrSiteDaoImpl.listActive()`가 `removed IS NULL`을 사용하므로 정상 soft delete 이후에는 scheduler 대상에서 제외된다.

다만 삭제와 scheduler tick이 겹치거나 외부에서 `checkDrSite(id)`를 직접 호출하는 경우를 막기 위해 다음 방어 로직을 추가한다.

```java
private DrSiteVO requireActiveSiteForHealthCheck(long siteId) {
    DrSiteVO site = drSiteDao.findById(siteId);
    if (site == null || site.getRemoved() != null) {
        throw new InvalidParameterValueException(DrConstants.ERROR_SITE_NOT_FOUND + ": " + siteId);
    }
    return site;
}

public DrSiteVO checkSite(long siteId, boolean persistStatus, String triggerType, String jobId) {
    DrSiteVO site = requireActiveSiteForHealthCheck(siteId);
    DrSiteHealthCheckResult result = drSiteHealthCheckService.checkSite(site, persistStatus);
    if (!persistStatus) {
        applyHealthCheckResult(site, result);
        return site;
    }
    return Transaction.execute((TransactionCallback<DrSiteVO>) status -> {
        DrSiteVO lockedSite = requireActiveSiteForHealthCheck(siteId);
        applyHealthCheckResult(lockedSite, result);
        drSiteDao.update(siteId, lockedSite);
        if (drSiteHealthCheckHistoryService != null) {
            DrSiteCredentialVO credential = drSiteCredentialService.findLatestCredential(siteId);
            drSiteHealthCheckHistoryService.record(lockedSite, credential, result, triggerType, jobId);
        }
        return drSiteDao.findById(siteId);
    });
}
```

Scheduler는 후보 조회가 active site 기준이어도 loop 직전 한 번 더 방어한다.

```java
for (DrSiteVO site : sites) {
    if (site == null || site.getRemoved() != null || !DrConstants.ADMIN_STATE_ENABLED.equals(site.getState())) {
        continue;
    }
    drSiteService.checkSite(site.getId(), true, DrConstants.HEALTH_TRIGGER_SCHEDULED, null);
}
```

이 규칙에 따라 삭제된 site에는 새 `dr_site_health_check` 이력이 추가되면 안 된다. 기존 이력은 감사 목적으로 보존하되, retention cleanup 정책에 따라 정리한다.

## 4. API 설계

### 4.1 `checkDrSite`

입력:

| Parameter | Required | 의미 |
| --- | --- | --- |
| `id` | yes | DR site uuid |
| `persiststatus` | no, default true | true이면 DB에 health result 저장, false이면 probe 결과만 응답 |

응답:

기존 `DrSiteResponse` 호환을 유지하면서 다음 field를 추가한다.

| Field | 의미 |
| --- | --- |
| `healthstate` | `CONNECTED`, `DEGRADED`, `DISCONNECTED`, `UNKNOWN` |
| `healthreasoncode` | 실패/주의 사유 code |
| `healthmessage` | 사용자 표시용 요약 |
| `healthlatencyms` | probe 소요 시간 |
| `credentialconfigured` | usable configured credential 존재 여부 |
| `credentialstate` | latest credential 상태 또는 `MISSING`/`LEGACY_REF` |

`persiststatus=false`인 경우 응답에는 probe 결과가 포함되지만 `dr_site.health_state`, `last_checked`는 갱신하지 않는다.

### 4.2 `createDrSite`와 `updateDrSite`

Site 생성/수정 시 credential이 같이 입력되면 저장 후 즉시 lightweight validation을 수행한다.

- 저장 검증 실패: 필수 필드 누락이면 API 실패.
- 연결 검증 실패: site는 생성하되 `healthstate=DISCONNECTED`, `credentialstate=CONFIGURED`, `healthreasoncode`에 원인을 기록한다. 운영자가 네트워크나 인증서를 수정한 뒤 `사이트 점검`을 다시 실행할 수 있어야 하기 때문이다.
- 사용자가 `연결 테스트 후 저장` UX를 선택한 경우 UI는 `checkDrSite(persiststatus=false)`를 먼저 호출하고 성공 시 create/update를 진행할 수 있다.

## 5. UI 설계

DR Site 목록의 `사이트 상태` 컬럼은 `healthstate`를 다음과 같이 변환한다.

| API 값 | 한국어 | 색상 |
| --- | --- | --- |
| `CONNECTED` | 정상 | green |
| `DEGRADED` | 주의 | orange |
| `DISCONNECTED` | 실패 | red |
| `UNKNOWN` | 미점검 | gray |

`lastchecked`가 있고 `UNKNOWN`이면 tooltip에 `상태 판정 결과가 없습니다. 사이트 점검을 다시 실행하세요.`를 표시한다. 구현 완료 후에는 credential missing/auth/network 실패가 `DISCONNECTED`로 떨어져야 하므로 `UNKNOWN`은 초기/unsupported 상태에만 남아야 한다.

`credentialconfigured=false` 또는 `credentialstate=CLEARED/MISSING/LEGACY_REF`이면 다음 UX를 적용한다.

- 목록 row tooltip: `인증 정보가 설정되어 있지 않습니다. 사이트 수정에서 인증 정보를 다시 입력하세요.`
- 상세 좌측 카드: `인증 정보 상태: 미설정` 또는 `해제됨`
- DR plan 생성 시 source/target site 선택 후보에서 경고 표시
- `사이트 점검` 실행 시 `DISCONNECTED/CREDENTIAL_MISSING` 결과를 표시

`사이트 점검` 액션 흐름:

1. UI가 `checkDrSite`를 `persiststatus=true`로 호출한다.
2. Cloud async job id를 polling한다.
3. job 성공 후 `getDrSite` 또는 list refresh를 수행한다.
4. `healthstate=CONNECTED`이면 정상 toast.
5. `DISCONNECTED/DEGRADED`이면 reason message를 toast와 상세 tooltip에 표시한다.

UI가 vCenter나 Mold endpoint를 직접 호출하지 않는다.

## 6. Agent/ftctl 영향

Site health check는 Cloud backend에서 끝나는 inventory 검증이다. Agent와 ftctl에는 명령을 보내지 않는다.

Agent/ftctl 변경이 필요한 경우는 DR plan action 단계다.

- `createDrPlan`, `startDrSync`, `startDrFailover`, `startDrFailback` 전 preflight에서 source/target site `healthstate=CONNECTED` 또는 명시 허용 상태인지 확인한다.
- runtime action에서 credential이 필요하면 `DrSiteCredentialService.resolveCredential()`로 `CONFIGURED` credential만 가져온다.
- host 전달은 기존 527 문서의 `/run/ablestack-vm-ftctl/credentials/<planUuid>.json` root-only 파일 원칙을 유지한다.
- `CLEARED`, `MISSING`, `LEGACY_REF` credential은 agent dispatch 전에 backend에서 실패시킨다.

## 7. DB 설계

### 7.1 즉시 구현

현재 schema로도 최소 기능은 구현할 수 있다.

| Table | Column | 사용 |
| --- | --- | --- |
| `dr_site` | `health_state` | `CONNECTED/DEGRADED/DISCONNECTED/UNKNOWN` 저장 |
| `dr_site` | `last_checked` | 마지막 probe 시각 |
| `dr_site` | `capabilities_json` | `healthCheck` object와 capability snapshot 저장 |
| `dr_site_credential` | `state` | `CONFIGURED/CLEARED` |
| `dr_site_credential` | `last_validated` | credential 검증 성공 시각 |
| `dr_site_credential` | `removed` | clear/delete 시 soft delete |

`capabilities_json.healthCheck` 예시:

```json
{
  "healthCheck": {
    "state": "DISCONNECTED",
    "reasonCode": "CREDENTIAL_INVALID",
    "message": "vCenter authentication failed.",
    "latencyMs": 421,
    "checkedAt": "2026-07-02T16:19:45+09:00",
    "endpoint": "https://10.10.21.10/sdk"
  }
}
```

Mold API probe의 경우 secret 없이 다음 non-secret 진단 값을 추가할 수 있다.

```json
{
  "healthCheck": {
    "state": "DISCONNECTED",
    "reasonCode": "CREDENTIAL_INVALID",
    "message": "Mold API authentication failed with HTTP 401",
    "latencyMs": 62,
    "authAlgorithm": "HmacSHA256",
    "probe": "DrMoldSiteProbe",
    "apiCommand": "listCapabilities"
  }
}
```

### 7.2 Schema hardening 후보

운영 분석성과 조회 성능을 높이려면 다음 upgrade를 추가한다.

```sql
ALTER TABLE `cloud`.`dr_site`
  ADD COLUMN `last_check_reason_code` varchar(128) DEFAULT NULL AFTER `last_checked`,
  ADD COLUMN `last_check_message` text DEFAULT NULL AFTER `last_check_reason_code`;

ALTER TABLE `cloud`.`dr_site_credential`
  ADD COLUMN `last_validation_result` varchar(64) DEFAULT NULL AFTER `last_validated`,
  ADD COLUMN `last_validation_message` text DEFAULT NULL AFTER `last_validation_result`;

ALTER TABLE `cloud`.`dr_site_credential`
  ADD KEY `i_dr_site_credential__site_state_removed` (`site_id`, `state`, `removed`);
```

이 hardening은 필수 선행 조건이 아니다. 단, 구현자가 `capabilities_json` merge보다 별도 컬럼을 선택하면 fresh schema와 upgrade SQL을 모두 갱신해야 한다.

## 8. 테스트 기준

| 테스트 | 기대 결과 |
| --- | --- |
| VMware Direct 정상 credential | `checkDrSite` 후 `healthstate=CONNECTED`, `lastchecked` 갱신, `credentiallastvalidated` 갱신 |
| VMware Direct 잘못된 password | async job 성공, site 응답 `healthstate=DISCONNECTED`, `healthreasoncode=CREDENTIAL_INVALID` |
| vCenter TLS strict 실패 | `healthstate=DISCONNECTED` 또는 `DEGRADED`, `ENDPOINT_UNREACHABLE` |
| Mold API 정상 credential | signed API 호출 성공, `healthstate=CONNECTED` |
| Mold API SHA256 회귀 검증 | 동일 credential로 `HmacSHA1`은 401, `HmacSHA256`은 200이어야 하며 backend는 `HmacSHA256` 사용 |
| Mold API 인증 실패 진단 | `healthstate=DISCONNECTED`, `healthreasoncode=CREDENTIAL_INVALID`, history `details_json.authAlgorithm=HmacSHA256` |
| credential cleared site | network call 없이 `DISCONNECTED/CREDENTIAL_MISSING` |
| `persiststatus=false` | 응답은 probe 결과 포함, DB `last_checked` 미변경 |
| delete 실패 유도 | site와 credential이 모두 원래 상태로 rollback |
| active plan 참조 site 삭제 | `DR_ACTIVE_PLAN_EXISTS`로 실패, credential 유지 |
| response redaction | password, secret key, API key, token 미노출 |
| UI 목록 | `UNKNOWN`은 `미점검`, credential missing은 `실패/인증 정보 미설정`으로 표시 |

## 9. 2026-07-02 추가 설계: 주기 점검과 상태 체크 이력

상세 설계는 [529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md](529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md)를 기준으로 한다.

본 문서는 `checkDrSite`가 실제 Mold/vCenter endpoint와 credential을 검증하고 `dr_site`의 최신 상태를 일관되게 갱신하는 흐름을 정의한다. 529 문서는 이 검증 결과를 주기적으로 실행하고, append-only 이력으로 보존하며, UI 상세 화면에서 조회하는 확장 설계를 정의한다.

코드 수준 책임 분리는 다음과 같다.

| 영역 | 책임 |
| --- | --- |
| `DrSiteServiceImpl.checkSite(siteId, persistStatus, triggerType, jobId)` | type-specific probe 수행, 최신 상태 갱신 여부 결정, 이력 저장에 필요한 `DrSiteHealthCheckResult` 생성 |
| `DrSiteHealthCheckHistoryServiceImpl` | `persistStatus=true`인 점검 결과를 `dr_site_health_check`에 append-only 저장 |
| `DrSiteHealthCheckScheduler` | 설정 주기마다 active DR site를 batch로 점검하고 trigger를 `SCHEDULED`로 기록 |
| `ListDrSiteHealthChecksCmd` | UI 상세 탭에서 site별 점검 이력을 page 단위로 조회 |
| UI `healthChecks` tab | DR 계획 탭 다음에 배치되며 backend 이력을 조회만 한다. UI는 점검 스케줄을 직접 수행하지 않는다. |

`persistStatus=false`는 preflight나 임시 검증처럼 DB 최신 상태와 이력을 남기지 않는 dry-run 성격으로 유지한다. 사용자가 상세 화면에서 수동으로 사이트 점검을 실행하거나 scheduler가 주기 점검을 실행하는 경우는 `persistStatus=true`이며, `dr_site.last_checked`와 `dr_site_health_check` 이력이 함께 갱신되어야 한다.

Agent/ftctl은 이 설계의 실행 주체가 아니다. Site health check는 Cloud backend가 수행하고, Agent/ftctl은 DR sync/failover/failback 같은 runtime action에서만 사용한다.

## 10. AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| 사이트 점검 | `lastChecked`만 갱신 | Mold/vCenter 실제 probe 수행 |
| `persiststatus` | API parameter만 있고 미사용 | DB 반영 여부를 제어 |
| `UNKNOWN` | 점검 후에도 계속 남음 | 미점검/unsupported에만 사용 |
| Credential active | `removed IS NULL`만 확인 | `credential_id`, `state=CONFIGURED`, `removed IS NULL` 확인 |
| `credentialconfigured` | `CLEARED` row도 true가 될 수 있음 | usable credential일 때만 true |
| 삭제 | credential clear 후 site delete | transaction 안에서 site/credential soft delete |
| 삭제 실패 | site 남고 credential 해제 가능 | rollback으로 부분 변경 방지 |
| UI | health/credential 원인 구분 부족 | reason code와 사용자 메시지 표시 |
| Mold API 서명 | 신규 probe가 `HmacSHA1` 사용 가능 | health probe와 기존 성공 경로 모두 `HmacSHA256` |
| 서명 진단 | history만 보고 알고리즘 확인 불가 | `details_json.authAlgorithm=HmacSHA256` 기록 |
| Agent/ftctl | site check와 경계 불명확 | site check는 Cloud only, runtime action만 Agent/ftctl 사용 |
