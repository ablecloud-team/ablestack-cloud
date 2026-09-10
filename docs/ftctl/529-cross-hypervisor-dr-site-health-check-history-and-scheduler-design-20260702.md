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

# Cross Hypervisor DR Site Health Check History And Scheduler Design

작성일: 2026-07-02

상위 문서:

- [506-cross-hypervisor-dr-cloud-ui-design-20260630.md](506-cross-hypervisor-dr-cloud-ui-design-20260630.md)
- [507-cross-hypervisor-dr-cloud-api-command-design-20260630.md](507-cross-hypervisor-dr-cloud-api-command-design-20260630.md)
- [508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md](508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md)
- [509-cross-hypervisor-dr-db-upgrade-and-entity-design-20260630.md](509-cross-hypervisor-dr-db-upgrade-and-entity-design-20260630.md)
- [528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md](528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md)

## 1. 목표

현재 DR Site health check는 다음 경로에서만 실행된다.

- UI/사용자 수동 실행: `checkDrSite(id, persiststatus=true)`
- site 생성/수정 이후 backend가 호출하는 즉시 검증

따라서 다음 두 가지가 부족하다.

1. Cloud backend가 active DR site를 주기적으로 점검하지 않는다.
2. `dr_site.health_state`, `last_checked`, `capabilities_json.healthCheck`는 최신 상태만 보존하므로 과거 상태 변화 이력을 UI에서 볼 수 없다.

이번 개선의 목표는 다음과 같다.

- Cloud management backend가 DR site health check를 주기적으로 실행한다.
- 수동 점검, 생성/수정 점검, preflight 점검, 주기 점검이 모두 같은 health result 저장 경로를 사용한다.
- 최신 상태는 기존 `dr_site` 컬럼에 유지하고, 모든 persist 대상 점검 결과는 신규 history table에 append-only로 기록한다.
- DR Site 상세 화면의 탭 순서를 `상세` -> `DR 계획` -> `상태 체크 이력`으로 구성한다.
- UI는 주기 점검을 직접 실행하지 않고, backend가 저장한 이력을 `listDrSiteHealthChecks` API로 조회한다.
- Agent/ftctl은 이 기능의 실행 경로에 포함하지 않는다.

## 2. 상태 모델

### 2.1 health state

기존 528 문서와 동일하게 다음 값을 사용한다.

| 값 | 의미 |
| --- | --- |
| `CONNECTED` | endpoint 접속과 credential 검증 성공 |
| `DEGRADED` | endpoint는 접근 가능하지만 REST credential 검증 fallback, 일부 capability 경고 등 주의 필요 |
| `DISCONNECTED` | credential 누락, 인증 실패, endpoint 접근 실패, HTTP 오류 |
| `UNKNOWN` | 아직 점검되지 않았거나 site/credential 조합을 판정할 수 없음 |

### 2.2 trigger type

history row는 health check가 왜 실행되었는지를 반드시 저장한다.

```java
public static final String HEALTH_TRIGGER_MANUAL = "MANUAL";
public static final String HEALTH_TRIGGER_SCHEDULED = "SCHEDULED";
public static final String HEALTH_TRIGGER_CREATE = "CREATE";
public static final String HEALTH_TRIGGER_UPDATE = "UPDATE";
public static final String HEALTH_TRIGGER_PREFLIGHT = "PREFLIGHT";
```

적용 기준:

| Trigger | 발생 조건 | DB 최신 상태 갱신 |
| --- | --- | --- |
| `MANUAL` | `checkDrSite(id, persiststatus=true)` | yes |
| `SCHEDULED` | scheduler worker가 active site를 주기 점검 | yes |
| `CREATE` | `createDrSite` 완료 후 credential 포함 시 즉시 점검 | yes |
| `UPDATE` | `updateDrSite` 완료 후 endpoint/credential 변경 시 즉시 점검 | yes |
| `PREFLIGHT` | plan 생성/action 전 사전 점검 | 기본 no, 명시적으로 persist할 때만 yes |

`persiststatus=false`인 `checkDrSite` 또는 preflight dry-run은 history에도 남기지 않는다. 사용자가 실제 운영 상태 이력으로 해석할 수 있는 결과만 저장한다.

## 3. DB 설계

### 3.1 신규 테이블

대상 schema 파일:

- `engine/schema/src/main/resources/META-INF/db/schema-42200to42210.sql`
- `engine/schema/src/main/resources/META-INF/db/schema-42210to42300.sql`
- `engine/schema/src/main/resources/META-INF/db/schema-Europa-After.sql`

DDL:

```sql
CREATE TABLE IF NOT EXISTS `cloud`.`dr_site_health_check` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `site_id` bigint unsigned NOT NULL,
    `site_uuid` varchar(40) NOT NULL,
    `site_name` varchar(255) NOT NULL,
    `site_type` varchar(64) NOT NULL,
    `hypervisor_type` varchar(64) NOT NULL,
    `endpoint` varchar(1024) NULL,
    `credential_id` bigint unsigned NULL,
    `credential_state` varchar(64) NULL,
    `trigger_type` varchar(64) NOT NULL,
    `health_state` varchar(64) NOT NULL,
    `reason_code` varchar(128) NULL,
    `message` text NULL,
    `latency_ms` bigint NULL,
    `checked_at` datetime NOT NULL,
    `management_server_id` bigint unsigned NULL,
    `job_id` varchar(255) NULL,
    `details_json` text NULL,
    `created` datetime NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_site_health_check__uuid` (`uuid`),
    KEY `i_dr_site_health_check__site_checked` (`site_id`, `checked_at`),
    KEY `i_dr_site_health_check__state_checked` (`health_state`, `checked_at`),
    KEY `i_dr_site_health_check__trigger_checked` (`trigger_type`, `checked_at`),
    CONSTRAINT `fk_dr_site_health_check__site_id` FOREIGN KEY (`site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
```

운영 중인 10.10.32 환경에 직접 반영할 때는 동일 DDL을 idempotent하게 실행한다.

### 3.2 보존 정책

기본 보존 기간은 30일이다. 오래된 이력은 scheduler가 별도 cleanup 단계에서 삭제한다.

```java
public static final ConfigKey<Integer> DrSiteHealthCheckHistoryRetentionDays =
    new ConfigKey<>("Advanced", Integer.class,
        "dr.site.health.check.history.retention.days",
        "30",
        "Number of days to keep DR site health check history.",
        true, ConfigKey.Scope.Global);
```

삭제 기준:

```sql
DELETE FROM dr_site_health_check
 WHERE checked_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL ? DAY);
```

## 4. Entity/DAO 설계

### 4.1 VO

경로:

`plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrSiteHealthCheckVO.java`

```java
@Entity
@Table(name = "dr_site_health_check")
public class DrSiteHealthCheckVO implements InternalIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "site_id")
    private long siteId;

    @Column(name = "site_uuid")
    private String siteUuid;

    @Column(name = "site_name")
    private String siteName;

    @Column(name = "site_type")
    private String siteType;

    @Column(name = "hypervisor_type")
    private String hypervisorType;

    @Column(name = "endpoint")
    private String endpoint;

    @Column(name = "credential_id")
    private Long credentialId;

    @Column(name = "credential_state")
    private String credentialState;

    @Column(name = "trigger_type")
    private String triggerType;

    @Column(name = "health_state")
    private String healthState;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "message")
    private String message;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "checked_at")
    private Date checkedAt;

    @Column(name = "management_server_id")
    private Long managementServerId;

    @Column(name = "job_id")
    private String jobId;

    @Column(name = "details_json")
    private String detailsJson;

    @Column(name = "created")
    private Date created;
}
```

생성자 규칙:

- `uuid`는 `UUID.randomUUID().toString()`.
- `siteUuid`, `siteName`, `siteType`, `hypervisorType`, `endpoint`는 점검 당시 site snapshot을 저장한다.
- `credentialId`, `credentialState`는 점검 당시 credential snapshot을 저장한다.
- secret payload, password, API key, secret key, token은 `detailsJson`에 절대 포함하지 않는다.

### 4.2 DAO

경로:

- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/dao/DrSiteHealthCheckDao.java`
- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/dao/DrSiteHealthCheckDaoImpl.java`

```java
public interface DrSiteHealthCheckDao extends GenericDao<DrSiteHealthCheckVO, Long> {
    Pair<List<DrSiteHealthCheckVO>, Integer> searchBySite(long siteId, String state, String reasonCode,
        String triggerType, Date startDate, Date endDate, Filter filter);

    List<DrSiteHealthCheckVO> listLatestBySite(long siteId, int limit);

    int expungeOlderThan(Date cutoff);
}
```

`DrSiteHealthCheckDaoImpl` search builder:

```java
SearchBuilder<DrSiteHealthCheckVO> siteHistorySearch = createSearchBuilder();
siteHistorySearch.and("siteId", siteHistorySearch.entity().getSiteId(), Op.EQ);
siteHistorySearch.and("healthState", siteHistorySearch.entity().getHealthState(), Op.EQ);
siteHistorySearch.and("reasonCode", siteHistorySearch.entity().getReasonCode(), Op.EQ);
siteHistorySearch.and("triggerType", siteHistorySearch.entity().getTriggerType(), Op.EQ);
siteHistorySearch.and("checkedAtGte", siteHistorySearch.entity().getCheckedAt(), Op.GTEQ);
siteHistorySearch.and("checkedAtLte", siteHistorySearch.entity().getCheckedAt(), Op.LTEQ);
siteHistorySearch.done();
```

기본 정렬은 `checked_at DESC`.

## 5. Backend service 설계

### 5.1 history service

경로:

- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrSiteHealthCheckHistoryService.java`
- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrSiteHealthCheckHistoryServiceImpl.java`

```java
public interface DrSiteHealthCheckHistoryService {
    DrSiteHealthCheckVO record(DrSiteVO site, DrSiteCredentialVO credential,
        DrSiteHealthCheckResult result, String triggerType, String jobId);

    Pair<List<DrSiteHealthCheckVO>, Integer> list(long siteId, String state, String reasonCode,
        String triggerType, Date startDate, Date endDate, Filter filter);

    int cleanup(Date cutoff);
}
```

`record` 구현 규칙:

```java
public DrSiteHealthCheckVO record(DrSiteVO site, DrSiteCredentialVO credential,
        DrSiteHealthCheckResult result, String triggerType, String jobId) {
    if (site == null || result == null || StringUtils.isBlank(triggerType)) {
        return null;
    }
    DrSiteHealthCheckVO history = new DrSiteHealthCheckVO(site, credential, result, triggerType, jobId);
    history.setManagementServerId(msId);
    history.setDetailsJson(buildRedactedDetails(site, credential, result));
    return drSiteHealthCheckDao.persist(history);
}
```

`detailsJson` 예시:

```json
{
  "siteType": "VMWARE_DIRECT",
  "credentialType": "VCENTER",
  "tlsVerify": true,
  "probe": "DrVmwareDirectSiteProbe",
  "endpoint": "https://10.10.21.10",
  "credentialValidated": false
}
```

ABLESTACK/Mold API probe의 경우 서명 알고리즘을 non-secret 진단 값으로 남긴다. 상세 설계는 [528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md](528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md)의 `2026-07-03 ABLESTACK/Mold API 서명 알고리즘 보강` 절을 따른다.

```json
{
  "siteType": "MOLD_KVM",
  "credentialType": "MOLD_API",
  "tlsVerify": true,
  "probe": "DrMoldSiteProbe",
  "apiCommand": "listCapabilities",
  "authAlgorithm": "HmacSHA256",
  "credentialValidated": false,
  "reasonCode": "CREDENTIAL_INVALID"
}
```

`detailsJson`에는 다음 값을 넣을 수 있다.

| Key | 조건 | 설명 |
| --- | --- | --- |
| `probe` | all | 선택된 `DrSiteProbe` class name |
| `apiCommand` | `MOLD_API` | health probe에 사용한 CloudStack API command. 기본 `listCapabilities` |
| `authAlgorithm` | `MOLD_API` | CloudStack API signature 알고리즘. 반드시 `HmacSHA256` |
| `credentialValidated` | all | credential 검증 성공 여부 |
| `reasonCode` | all | health reason code |

`apiKey`, `secretKey`, `password`, `token`, `Authorization` 등 원문 secret 또는 파생 인증 헤더는 절대 저장하지 않는다.

### 5.2 DrSiteService 변경

기존:

```java
DrSiteVO checkSite(long siteId);
DrSiteVO checkSite(long siteId, boolean persistStatus);
```

변경:

```java
DrSiteVO checkSite(long siteId);
DrSiteVO checkSite(long siteId, boolean persistStatus);
DrSiteVO checkSite(long siteId, boolean persistStatus, String triggerType, String jobId);
```

호환 규칙:

```java
public DrSiteVO checkSite(long siteId) {
    return checkSite(siteId, true, DrConstants.HEALTH_TRIGGER_MANUAL, null);
}

public DrSiteVO checkSite(long siteId, boolean persistStatus) {
    return checkSite(siteId, persistStatus, DrConstants.HEALTH_TRIGGER_MANUAL, null);
}
```

핵심 구현:

```java
public DrSiteVO checkSite(long siteId, boolean persistStatus, String triggerType, String jobId) {
    DrSiteVO site = requireSite(siteId);
    DrSiteCredentialVO credential = drSiteCredentialService.findConfiguredCredential(site);
    DrSiteHealthCheckResult result = drSiteHealthCheckService.checkSite(site, persistStatus);
    applyHealthCheckResult(site, result);
    if (persistStatus) {
        drSiteDao.update(siteId, site);
        drSiteHealthCheckHistoryService.record(site, credential, result, triggerType, jobId);
    }
    return drSiteDao.findById(siteId);
}
```

transaction 범위:

- `dr_site` 최신 상태 갱신과 `dr_site_health_check` history insert는 하나의 transaction으로 묶는다.
- history insert 실패 시 최신 상태만 갱신되는 부분 성공을 허용하지 않는다.
- health probe 네트워크 호출은 transaction 밖에서 수행한다.

### 5.3 create/update 연동

`createDrSite`와 `updateDrSite`에서 credential 입력이 있는 경우 점검 trigger를 구분한다.

```java
DrSiteVO created = drSiteDao.persist(site);
if (credentialInput != null && credentialInput.hasAnyCredentialValue()) {
    created = checkSite(created.getId(), true, DrConstants.HEALTH_TRIGGER_CREATE, null);
}
```

```java
if (endpointChanged || credentialChanged || clearCredential) {
    updated = checkSite(siteId, true, DrConstants.HEALTH_TRIGGER_UPDATE, null);
}
```

credential clear인 경우 network call 없이 `DISCONNECTED/CREDENTIAL_MISSING` 이력을 저장한다.

## 6. Scheduler 설계

### 6.1 클래스

경로:

`plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/health/DrSiteHealthCheckScheduler.java`

```java
public class DrSiteHealthCheckScheduler extends ManagerBase implements Configurable {
    private ScheduledExecutorService executor;

    @Inject
    private DrSiteDao drSiteDao;
    @Inject
    private DrSiteService drSiteService;
    @Inject
    private DrSiteHealthCheckHistoryService historyService;

    @Override
    public boolean configure(String name, Map<String, Object> params) {
        executor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DrSiteHealthCheckScheduler"));
        return true;
    }

    @Override
    public boolean start() {
        if (!DrSiteHealthCheckEnabled.value()) {
            return true;
        }
        long interval = Math.max(DrSiteHealthCheckInterval.value(), 60);
        executor.scheduleWithFixedDelay(new HealthCheckTask(), interval, interval, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        return true;
    }
}
```

### 6.2 ConfigKey

```java
public static final ConfigKey<Boolean> DrSiteHealthCheckEnabled =
    new ConfigKey<>("Advanced", Boolean.class,
        "dr.site.health.check.enabled",
        "true",
        "Enable periodic Cross Hypervisor DR site health checks.",
        true, ConfigKey.Scope.Global);

public static final ConfigKey<Integer> DrSiteHealthCheckInterval =
    new ConfigKey<>("Advanced", Integer.class,
        "dr.site.health.check.interval",
        "300",
        "Interval in seconds for periodic Cross Hypervisor DR site health checks.",
        true, ConfigKey.Scope.Global);

public static final ConfigKey<Integer> DrSiteHealthCheckBatchSize =
    new ConfigKey<>("Advanced", Integer.class,
        "dr.site.health.check.batch.size",
        "25",
        "Maximum number of DR sites checked per scheduler pass.",
        true, ConfigKey.Scope.Global);
```

### 6.3 Task 구현

```java
private class HealthCheckTask extends ManagedContextRunnable {
    @Override
    protected void runInContext() {
        GlobalLock lock = GlobalLock.getInternLock("dr.site.health.check.scheduler");
        if (lock == null || !lock.lock(0)) {
            return;
        }
        try {
            List<DrSiteVO> candidates = drSiteDao.listDueForHealthCheck(
                DrSiteHealthCheckBatchSize.value(),
                DrSiteHealthCheckInterval.value());
            for (DrSiteVO site : candidates) {
                try {
                    drSiteService.checkSite(site.getId(), true, DrConstants.HEALTH_TRIGGER_SCHEDULED, null);
                } catch (RuntimeException e) {
                    LOGGER.warn(String.format("Failed scheduled DR site health check for site %s", site.getUuid()), e);
                }
            }
            cleanupHistory();
        } finally {
            lock.unlock();
            lock.releaseRef();
        }
    }
}
```

`listDueForHealthCheck` 기준:

- `removed IS NULL`
- `state = ENABLED`
- `last_checked IS NULL OR last_checked <= now - interval`
- 정렬: `last_checked ASC`, `id ASC`
- 제한: batch size
- delete와 scheduler tick이 겹치는 race를 막기 위해 scheduler loop 직전에도 `site.getRemoved() == null`을 재확인한다.
- `DrSiteServiceImpl.checkSite(...)`는 삭제된 site를 받으면 probe와 history insert를 수행하지 않고 `SITE_NOT_FOUND` 계열 오류로 종료한다.
- 삭제된 site의 과거 `dr_site_health_check` row는 감사 이력으로 남긴다. 단, 삭제 이후 새 `SCHEDULED`, `MANUAL`, `UPDATE`, `PREFLIGHT` 이력이 추가되면 안 된다.

DAO 추가:

```java
List<DrSiteVO> listDueForHealthCheck(int limit, int intervalSeconds);
```

주의:

- scheduler가 동시에 여러 management server에서 돌 수 있으므로 `GlobalLock`을 반드시 사용한다.
- 각 site의 network probe는 timeout을 갖고 있어야 한다.
- scheduler는 Cloud async job을 생성하지 않는다. UI job history와 혼동하지 않기 위해 trigger type으로만 구분한다.

## 7. API 설계

### 7.1 list command

경로:

`plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/ListDrSiteHealthChecksCmd.java`

```java
@APICommand(name = "listDrSiteHealthChecks",
        description = "List Cross Hypervisor DR site health check history",
        responseObject = DrSiteHealthCheckResponse.class,
        responseView = ResponseView.Full,
        authorized = {RoleType.Admin})
public class ListDrSiteHealthChecksCmd extends BaseListCmd {
    @Parameter(name = "siteid", type = CommandType.UUID, entityType = DrSiteResponse.class,
            description = "the DR site ID")
    private Long siteId;

    @Parameter(name = "healthstate", type = CommandType.STRING,
            description = "filter by health state")
    private String healthState;

    @Parameter(name = "reasoncode", type = CommandType.STRING,
            description = "filter by health reason code")
    private String reasonCode;

    @Parameter(name = "triggertype", type = CommandType.STRING,
            description = "filter by health check trigger type")
    private String triggerType;

    @Parameter(name = "startdate", type = CommandType.DATE,
            description = "inclusive checked_at lower bound")
    private Date startDate;

    @Parameter(name = "enddate", type = CommandType.DATE,
            description = "inclusive checked_at upper bound")
    private Date endDate;
}
```

`siteid`는 UI 상세 탭에서 항상 전달하지만, 운영자가 전체 이력을 조회할 수 있도록 API 차원에서는 optional로 둔다.

### 7.2 response

경로:

`plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/response/dr/DrSiteHealthCheckResponse.java`

```java
public class DrSiteHealthCheckResponse extends BaseResponse {
    @SerializedName("id")
    private String id;
    @SerializedName("siteid")
    private String siteId;
    @SerializedName("sitename")
    private String siteName;
    @SerializedName("sitetype")
    private String siteType;
    @SerializedName("hypervisortype")
    private String hypervisorType;
    @SerializedName("endpoint")
    private String endpoint;
    @SerializedName("triggertype")
    private String triggerType;
    @SerializedName("healthstate")
    private String healthState;
    @SerializedName("healthreasoncode")
    private String reasonCode;
    @SerializedName("healthmessage")
    private String message;
    @SerializedName("healthlatencyms")
    private Long latencyMs;
    @SerializedName("credentialstate")
    private String credentialState;
    @SerializedName("checkedat")
    private Date checkedAt;
    @SerializedName("managementserverid")
    private Long managementServerId;
    @SerializedName("jobid")
    private String jobId;
}
```

API 응답에는 secret, password, API key, secret key를 포함하지 않는다.

### 7.3 response generator

`DrResponseGenerator`에 다음 메서드를 추가한다.

```java
DrSiteHealthCheckResponse createSiteHealthCheckResponse(DrSiteHealthCheckVO history);
ListResponse<DrSiteHealthCheckResponse> createSiteHealthCheckResponse(List<DrSiteHealthCheckVO> rows, int count);
```

## 8. UI 설계

### 8.1 API wrapper

경로:

`ui/src/api/dr.js`

```js
const listKeys = {
  ...
  listDrSiteHealthChecks: ['listdrsitehealthchecksresponse', 'drsitehealthcheck']
}

export function listDrSiteHealthChecks (params = {}) {
  return getAPI('listDrSiteHealthChecks', params)
    .then(response => extractDrList(response, 'listDrSiteHealthChecks'))
}
```

### 8.2 DR Site 상세 탭

경로:

`ui/src/views/infra/dr/DrSiteList.vue`

현재:

```vue
<a-tab-pane key="details" :tab="$t('label.details')" />
<a-tab-pane key="plans" :tab="$t('label.dr.plans')" />
```

변경:

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
    @change="handleHealthCheckTableChange">
    <template #bodyCell="{ column, record, text }">
      <template v-if="column.key === 'healthstate'">
        <status :text="text || ''" displayText />
      </template>
      <template v-else-if="column.key === 'healthlatencyms'">
        {{ formatLatency(text) }}
      </template>
      <template v-else-if="column.key === 'checkedat'">
        {{ $toLocaleDate(text) }}
      </template>
    </template>
  </a-table>
</a-tab-pane>
```

탭 순서:

1. `details`
2. `plans`
3. `healthChecks`

URL query 호환:

```js
normalizeDetailTab (tab) {
  if (tab === 'overview') return 'details'
  if (tab === 'health' || tab === 'healthHistory') return 'healthChecks'
  return ['details', 'plans', 'healthChecks'].includes(tab) ? tab : 'details'
}
```

### 8.3 data/methods

```js
data () {
  return {
    healthChecks: [],
    healthCheckLoading: false,
    healthCheckPagination: {
      current: 1,
      pageSize: 10,
      total: 0,
      showSizeChanger: true
    },
    healthCheckFilters: {
      healthstate: undefined,
      triggertype: undefined
    }
  }
}
```

```js
computed: {
  healthCheckColumns () {
    return [
      { key: 'checkedat', title: this.$t('label.dr.site.health.checked.at'), dataIndex: 'checkedat', sorter: true },
      { key: 'triggertype', title: this.$t('label.dr.site.health.trigger'), dataIndex: 'triggertype' },
      { key: 'healthstate', title: this.$t('label.dr.site.health'), dataIndex: 'healthstate' },
      { key: 'healthreasoncode', title: this.$t('label.dr.site.health.reason'), dataIndex: 'healthreasoncode' },
      { key: 'healthmessage', title: this.$t('label.dr.site.health.message'), dataIndex: 'healthmessage', ellipsis: true },
      { key: 'healthlatencyms', title: this.$t('label.dr.site.health.latency'), dataIndex: 'healthlatencyms' },
      { key: 'endpoint', title: this.$t('label.endpoint'), dataIndex: 'endpoint', ellipsis: true },
      { key: 'credentialstate', title: this.$t('label.dr.credential.state'), dataIndex: 'credentialstate' }
    ]
  }
}
```

```js
fetchHealthChecks (pagination = this.healthCheckPagination) {
  if (!this.detailSite?.id || !('listDrSiteHealthChecks' in this.$store.getters.apis)) {
    this.healthChecks = []
    return Promise.resolve()
  }
  this.healthCheckLoading = true
  return listDrSiteHealthChecks({
    siteid: this.detailSite.id,
    page: pagination.current,
    pagesize: pagination.pageSize,
    healthstate: this.healthCheckFilters.healthstate,
    triggertype: this.healthCheckFilters.triggertype
  }).then(result => {
    this.healthChecks = result.items || []
    this.healthCheckPagination = Object.assign({}, pagination, { total: result.count || 0 })
  }).finally(() => {
    this.healthCheckLoading = false
  })
}
```

탭 전환 시 lazy load:

```js
changeTab (tab) {
  const normalizedTab = this.normalizeDetailTab(tab)
  this.activeTab = normalizedTab
  if (normalizedTab === 'healthChecks') {
    this.fetchHealthChecks()
  }
  this.$router.replace(...)
}
```

`fetchDetail` 완료 후 현재 탭이 `healthChecks`면 자동 조회한다.

### 8.4 i18n

`ui/public/locales/ko_KR.json`, `ui/public/locales/en.json`

```json
{
  "label.dr.site.health.history": "상태 체크 이력",
  "label.dr.site.health.checked.at": "점검 시각",
  "label.dr.site.health.trigger": "실행 유형",
  "label.dr.site.health.trigger.manual": "수동",
  "label.dr.site.health.trigger.scheduled": "주기 점검",
  "label.dr.site.health.trigger.create": "생성",
  "label.dr.site.health.trigger.update": "수정",
  "label.dr.site.health.trigger.preflight": "사전 점검"
}
```

## 9. Spring wiring

`spring-disaster-recovery-context.xml`에 다음 bean을 추가한다.

```xml
<bean id="drSiteHealthCheckHistoryDaoImpl" class="com.cloud.dr.dao.DrSiteHealthCheckDaoImpl"/>
<bean id="drSiteHealthCheckHistoryServiceImpl" class="com.cloud.dr.DrSiteHealthCheckHistoryServiceImpl"/>
<bean id="drSiteHealthCheckScheduler" class="com.cloud.dr.health.DrSiteHealthCheckScheduler"/>
```

실제 bean id는 기존 DAO/service naming pattern과 맞춘다.

## 10. 테스트 기준

| 테스트 | 기대 결과 |
| --- | --- |
| 수동 `checkDrSite` | async job 성공, `dr_site` 최신 상태 갱신, `dr_site_health_check.trigger_type=MANUAL` row 생성 |
| credential missing site | `DISCONNECTED/CREDENTIAL_MISSING` history row 생성 |
| Mold API 정상 credential | `CONNECTED/MOLD_API_OK` history row와 `details_json.authAlgorithm=HmacSHA256` 기록 |
| Mold API 잘못된 secret | `DISCONNECTED/CREDENTIAL_INVALID` history row와 `details_json.authAlgorithm=HmacSHA256` 기록 |
| scheduler enabled | interval 이후 active site에 `SCHEDULED` row 생성 |
| scheduler disabled | 새 `SCHEDULED` row 생성 없음 |
| retention cleanup | cutoff 이전 row 삭제 |
| `persiststatus=false` | 최신 상태와 history 모두 미변경 |
| DR Site 상세 `상태 체크 이력` 탭 | `DR 계획` 다음 탭에 table 표시 |
| pagination | `listDrSiteHealthChecks` count/page 반영 |
| response redaction | secret/API key/password/token 미노출 |

## 11. AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| 점검 방식 | 수동/API 호출 시점 중심 | Cloud backend scheduler가 주기 점검 |
| 최신 상태 | `dr_site`에 최신값만 저장 | `dr_site` 최신값 + `dr_site_health_check` 이력 저장 |
| 이력 조회 | UI/API 없음 | DR Site 상세의 `상태 체크 이력` 탭에서 table 조회 |
| 실행 원인 | 구분 불가 | `MANUAL/SCHEDULED/CREATE/UPDATE/PREFLIGHT`로 구분 |
| 다중 management | 고려 없음 | `GlobalLock`으로 scheduler 중복 실행 방지 |
| 보존 정책 | 없음 | 기본 30일 retention cleanup |
| Agent/ftctl | 관련 없음 | 계속 관련 없음, Cloud control-plane에서 종료 |

## 12. 2026-07-03 상세 화면 raw JSON 표시 원칙

DR Site 상세 화면의 기본 `상세` 탭은 사용자 운영 정보만 표시한다. `dr_site.capabilities_json` 또는 `healthCheck` details JSON을 `<pre>`로 직접 표시하지 않는다.

진단 metadata가 필요한 경우에는 `상태 체크 이력` 탭의 row 확장 상세로 제한한다. 이 경우에도 표시 가능한 값은 `authAlgorithm=HmacSHA256`, `apiCommand=listCapabilities`, `probe=DrMoldSiteProbe`, `latencyMs` 같은 non-secret metadata로 한정한다.

Site inventory 조회와 상세 JSON 제거 설계는 [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)를 따른다.
