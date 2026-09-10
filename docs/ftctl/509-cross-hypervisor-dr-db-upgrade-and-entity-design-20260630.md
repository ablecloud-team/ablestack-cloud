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

# Cross Hypervisor DR DB Upgrade And Entity Design

> 2026-08-06 forward cutover commit storage correction: document
> [599](599-cross-hypervisor-dr-forward-cutover-commit-contract-and-authority-convergence-design-20260806.md)
> adds durable commit contract, attempt/hash, dispatch, and retry identity to the
> Cutover Session without deleting prior failure history.

> 2026-08-05 Failback route storage correction: document
> [595](595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md)
> keeps `replication_direction` and `provider_pair` as distinct domains and
> adds indexed, restart-safe failure reconciliation without rewriting history.

> 2026-08-05 live-worker correction: document
> [594](594-cross-hypervisor-dr-live-worker-and-terminal-reconciliation-design-20260805.md)
> defines reconciliation, heartbeat, payload, terminal-source, and revision
> columns plus guarded upgrade requirements.

> 2026-08-04 normative runtime-observation storage update:
> [592-cross-hypervisor-dr-failback-live-runtime-preflight-and-ux-convergence-design-20260804.md](592-cross-hypervisor-dr-failback-live-runtime-preflight-and-ux-convergence-design-20260804.md)
> keeps committed authority rows immutable during read-only preflight and uses
> versioned `dr_plan_view_cache.snapshot_json`, deduplicated `dr_event`, and
> action-owned `dr_run_step` evidence without adding a new table.
>
> 2026-08-04 normative Failback evidence update:
> Document 591 revision 2 adds queryable reverse mode and preflight evidence to
> `dr_failback_session`; direction/provider pair are committed before Agent
> dispatch and per-cycle bytes remain in `dr_sync_cycle`.
>
> 2026-08-03 normative schema update:
> [590-cross-hypervisor-dr-plan-async-mutation-and-target-resource-ownership-design-20260803.md](590-cross-hypervisor-dr-plan-async-mutation-and-target-resource-ownership-design-20260803.md)
> adds the active target resource claim model, ownership generation, materialization
> digest, and collision-safe backfill rules.

작성일: 2026-06-30

대상 브랜치: `feature/ftctl-cloud-integration`

상위 문서:

- [501-cross-hypervisor-dr-domain-schema-design-20260630.md](501-cross-hypervisor-dr-domain-schema-design-20260630.md)
- [507-cross-hypervisor-dr-cloud-api-command-design-20260630.md](507-cross-hypervisor-dr-cloud-api-command-design-20260630.md)
- [508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md](508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md)

## 1. 목적

이 문서는 `Cross Hypervisor DR` 도메인 모델을 Cloud DB, JPA entity, DAO, upgrade path에 반영하는 구현 설계를 정의한다.

[501 문서](501-cross-hypervisor-dr-domain-schema-design-20260630.md)는 논리 schema를 정의했다. 이 문서는 실제 구현자가 빠뜨리기 쉬운 다음 항목을 보강한다.

- fresh install schema 반영 위치
- upgrade install schema 반영 방식
- VO/DAO class 배치
- FK/index/unique 제약
- uuid와 internal id 변환
- JSON text field 사용 규칙
- `removed` 기반 soft delete와 active unique 처리
- 기존 `disaster_recovery_cluster`, `ftctl_protection`과의 연결

## 2. 반영 대상 파일

Fresh install:

| 파일 | 작업 |
| --- | --- |
| `setup/db/create-schema.sql` | `DROP TABLE IF EXISTS`와 `CREATE TABLE` 추가 |
| `setup/db/create-schema-premium.sql` | 해당 배포에서 사용된다면 동일 반영 |
| `setup/db/create-schema-simulator.sql` | simulator schema가 별도 필요하면 동일 반영 |

Upgrade install:

| 파일 | 작업 |
| --- | --- |
| active version upgrade SQL | 신규 table/index/FK 추가 |
| active upgrade shell | 별도 SQL 파일을 호출하는 방식이면 호출 추가 |

이 repository에는 `setup/db/221to222upgrade.sh` 같은 legacy upgrade entry가 존재한다. 구현 시점의 제품 버전 upgrade convention을 확인한 뒤, fresh schema와 upgrade schema가 반드시 동일한 결과를 만들도록 한다.

검증 원칙:

- fresh install DB와 upgraded DB에서 `SHOW CREATE TABLE` 결과가 의미상 동일해야 한다.
- upgrade SQL은 재실행 시 치명적 실패를 만들지 않도록 table existence와 index existence를 고려한다.
- foreign key 순서를 지켜 parent table이 먼저 생성되어야 한다.

## 3. Table 생성 순서

FK 의존성을 고려한 생성 순서:

1. `dr_site`
2. `dr_site_pair`
3. `dr_plan`
4. `dr_restore_point`
5. `dr_restore_point_artifact`
6. `dr_replica`
7. `dr_replica_disk`
8. `dr_run`
9. `dr_run_step`
10. `dr_event`

DROP 순서는 반대다.

`setup/db/create-schema.sql` 상단의 `DROP TABLE IF EXISTS` 목록에도 반대 순서로 추가한다.

## 4. DDL 기준

### 4.1 공통 컬럼 규칙

모든 신규 table은 다음 규칙을 따른다.

- `id bigint unsigned NOT NULL auto_increment`
- `uuid varchar(40) NOT NULL`
- `created datetime NOT NULL`
- `removed datetime DEFAULT NULL`
- text JSON 컬럼은 `text DEFAULT NULL`
- 상태 enum은 Java enum이 아니라 `varchar(32)` 또는 `varchar(64)`로 저장한다.
- FK는 명시 이름을 둔다.
- uuid unique key를 둔다.

`uuid`는 API에 노출되는 id이고, 내부 FK는 numeric `id`를 사용한다.

### 4.2 dr_site

```sql
CREATE TABLE `cloud`.`dr_site` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `name` varchar(255) NOT NULL,
  `description` varchar(1024) DEFAULT NULL,
  `site_type` varchar(32) NOT NULL,
  `hypervisor_type` varchar(32) NOT NULL,
  `endpoint` varchar(1024) DEFAULT NULL,
  `credential_id` bigint unsigned DEFAULT NULL,
  `credential_ref` varchar(255) DEFAULT NULL,
  `zone_id` bigint unsigned DEFAULT NULL,
  `zone_external_id` varchar(255) DEFAULT NULL,
  `zone_name` varchar(255) DEFAULT NULL,
  `vmware_dc_id` bigint unsigned DEFAULT NULL,
  `vmware_datacenter_external_id` varchar(255) DEFAULT NULL,
  `vmware_datacenter_name` varchar(255) DEFAULT NULL,
  `capabilities_json` text DEFAULT NULL,
  `state` varchar(32) NOT NULL,
  `health_state` varchar(32) DEFAULT NULL,
  `last_checked` datetime DEFAULT NULL,
  `created` datetime NOT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_site__uuid` (`uuid`),
  KEY `i_dr_site__type_state` (`site_type`, `hypervisor_type`, `state`),
  KEY `i_dr_site__credential_id` (`credential_id`),
  KEY `i_dr_site__zone_id` (`zone_id`),
  KEY `i_dr_site__zone_external_id` (`zone_external_id`),
  KEY `i_dr_site__vmware_dc_id` (`vmware_dc_id`),
  KEY `i_dr_site__vmware_dc_external_id` (`vmware_datacenter_external_id`)
);
```

`credential_ref`는 legacy 호환 필드다. 신규 UI/API는 이 값을 입력받지 않고, backend가 `dr_site_credential` row를 생성한 뒤 `credential_id`를 갱신한다.

### 4.2.1 dr_site_credential

```sql
CREATE TABLE `cloud`.`dr_site_credential` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `site_id` bigint unsigned NOT NULL,
  `credential_type` varchar(32) NOT NULL,
  `endpoint` varchar(1024) DEFAULT NULL,
  `principal` varchar(255) DEFAULT NULL,
  `secret_payload` text NOT NULL,
  `secret_fingerprint` varchar(128) DEFAULT NULL,
  `state` varchar(32) NOT NULL DEFAULT 'STORED',
  `last_validated` datetime DEFAULT NULL,
  `last_validation_result` varchar(32) DEFAULT NULL,
  `last_validation_message` text DEFAULT NULL,
  `created` datetime NOT NULL,
  `updated` datetime DEFAULT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_site_credential__uuid` (`uuid`),
  KEY `i_dr_site_credential__site_removed` (`site_id`, `removed`),
  KEY `i_dr_site_credential__type_state` (`credential_type`, `state`),
  CONSTRAINT `fk_dr_site_credential__site_id` FOREIGN KEY (`site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE
);
```

`secret_payload`는 `@Encrypt` annotation 또는 `DBEncryptionUtil`를 통해 DB 암호화 대상이 된다. Mold API key, Mold secret key, vCenter password는 response/log에 노출하지 않는다.

### 4.2.2 2026-07-02 health check 저장 보강

Site health check와 삭제 정합성의 상세 설계는 [528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md](528-cross-hypervisor-dr-site-health-check-and-delete-consistency-design-20260702.md)를 따른다.

즉시 구현은 현재 schema를 우선 사용한다.

| Table | Column | 저장 값 |
| --- | --- | --- |
| `dr_site` | `health_state` | `OK`, `DEGRADED`, `FAILED`, `UNKNOWN` |
| `dr_site` | `last_checked` | 마지막 probe 시각 |
| `dr_site` | `capabilities_json` | `healthCheck` object와 non-secret capability snapshot |
| `dr_site_credential` | `state` | `CONFIGURED`, `CLEARED` |
| `dr_site_credential` | `last_validated` | credential 검증 성공 시각 |
| `dr_site_credential` | `removed` | credential clear/delete soft delete |

운영 분석을 위해 별도 컬럼이 필요하면 다음 hardening DDL을 fresh schema와 upgrade schema에 함께 반영한다.

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

`dr_site_credential` active lookup index는 `site_id`, `state`, `removed` 조합을 기준으로 한다. `state=CLEARED`이며 `removed IS NULL`인 오염 row가 존재하더라도 configured credential로 취급하면 안 된다.

CloudStack 공통 DAO는 `removed` 컬럼을 `DaoGenerated`와 `Updatable=false`로 처리한다. 따라서 `removed`를 채우는 삭제 구현은 `vo.markRemoved(); dao.update(id, vo);`가 아니라 `GenericDao.remove(id)` 경로를 사용해야 한다.

| Entity | 삭제 API/service | soft-delete 구현 |
| --- | --- | --- |
| `DrSiteVO` | `deleteDrSite` / `DrSiteServiceImpl.deleteSite` | credential 참조 제거 update 후 `drSiteDao.remove(siteId)` |
| `DrSiteCredentialVO` | credential clear/delete | `state=CLEARED` update 후 `drSiteCredentialDao.remove(credentialId)` |
| `DrPlanVO` | `deleteDrPlan` / `DrPlanServiceImpl.deletePlan` | runtime guard 통과 후 `drPlanDao.remove(planId)` |

각 삭제 service는 `findByIdIncludingRemoved(id).removed != null` 검증을 완료해야 성공으로 간주한다. 이 검증이 실패하면 async job을 실패 처리한다.

Active name unique는 MySQL의 NULL unique 동작 때문에 단순 `UNIQUE(name, removed)`만으로 충분하지 않을 수 있다. 구현 시 아래 중 하나를 선택한다.

- service layer에서 active name 중복을 강제한다.
- `removed` 대신 `removed_epoch` 같은 generated/key column을 추가한다.
- DB 버전이 지원하면 functional unique index를 사용한다.

초기 구현은 service layer 중복 검사를 기본으로 한다.

### 4.3 dr_site_pair

```sql
CREATE TABLE `cloud`.`dr_site_pair` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `name` varchar(255) NOT NULL,
  `source_site_id` bigint unsigned NOT NULL,
  `target_site_id` bigint unsigned NOT NULL,
  `legacy_dr_cluster_id` bigint unsigned DEFAULT NULL,
  `policy_json` text DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `created` datetime NOT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_site_pair__uuid` (`uuid`),
  KEY `i_dr_site_pair__source_target` (`source_site_id`, `target_site_id`, `status`),
  KEY `i_dr_site_pair__legacy_dr_cluster_id` (`legacy_dr_cluster_id`),
  CONSTRAINT `fk_dr_site_pair__source_site_id` FOREIGN KEY (`source_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_site_pair__target_site_id` FOREIGN KEY (`target_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_site_pair__legacy_dr_cluster_id` FOREIGN KEY (`legacy_dr_cluster_id`) REFERENCES `disaster_recovery_cluster` (`id`) ON DELETE SET NULL
);
```

### 4.4 dr_plan

```sql
CREATE TABLE `cloud`.`dr_plan` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `name` varchar(255) NOT NULL,
  `description` varchar(1024) DEFAULT NULL,
  `account_id` bigint unsigned NOT NULL,
  `domain_id` bigint unsigned NOT NULL,
  `source_site_id` bigint unsigned NOT NULL,
  `target_site_id` bigint unsigned NOT NULL,
  `site_pair_id` bigint unsigned DEFAULT NULL,
  `source_vm_id` bigint unsigned DEFAULT NULL,
  `source_external_ref` varchar(1024) DEFAULT NULL,
  `direction` varchar(32) NOT NULL,
  `state` varchar(32) NOT NULL,
  `rpo_seconds` bigint DEFAULT NULL,
  `rto_seconds` bigint DEFAULT NULL,
  `schedule_json` text DEFAULT NULL,
  `policy_json` text DEFAULT NULL,
  `mapping_json` text DEFAULT NULL,
  `quiesce_policy_json` text DEFAULT NULL,
  `compatibility_json` text DEFAULT NULL,
  `engine_binding_type` varchar(64) DEFAULT NULL,
  `engine_binding_id` bigint unsigned DEFAULT NULL,
  `source_worker_host_id` bigint unsigned DEFAULT NULL,
  `target_worker_host_id` bigint unsigned DEFAULT NULL,
  `coordinator_worker_host_id` bigint unsigned DEFAULT NULL,
  `last_restore_point_id` bigint unsigned DEFAULT NULL,
  `last_target_ready_restore_point_id` bigint unsigned DEFAULT NULL,
  `last_error_code` varchar(128) DEFAULT NULL,
  `last_error_message` text DEFAULT NULL,
  `created` datetime NOT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_plan__uuid` (`uuid`),
  KEY `i_dr_plan__account_domain_removed` (`account_id`, `domain_id`, `removed`),
  KEY `i_dr_plan__source_vm_removed` (`source_vm_id`, `removed`),
  KEY `i_dr_plan__source_target_state` (`source_site_id`, `target_site_id`, `state`),
  KEY `i_dr_plan__direction_state` (`direction`, `state`),
  KEY `i_dr_plan__engine_binding` (`engine_binding_type`, `engine_binding_id`),
  CONSTRAINT `fk_dr_plan__account_id` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_plan__domain_id` FOREIGN KEY (`domain_id`) REFERENCES `domain` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_plan__source_site_id` FOREIGN KEY (`source_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_plan__target_site_id` FOREIGN KEY (`target_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_plan__site_pair_id` FOREIGN KEY (`site_pair_id`) REFERENCES `dr_site_pair` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_dr_plan__source_vm_id` FOREIGN KEY (`source_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE SET NULL
);
```

`last_restore_point_id`와 `last_target_ready_restore_point_id`는 `dr_restore_point` 생성 후 FK를 추가하면 circular DDL 문제가 생길 수 있다. 초기 구현은 FK 없이 index/DAO validation으로 유지한다.

### 4.5 dr_restore_point

```sql
CREATE TABLE `cloud`.`dr_restore_point` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `sequence_no` bigint NOT NULL,
  `state` varchar(32) NOT NULL,
  `source_captured_at` datetime DEFAULT NULL,
  `source_ready_at` datetime DEFAULT NULL,
  `target_ready_at` datetime DEFAULT NULL,
  `expires_at` datetime DEFAULT NULL,
  `size_bytes` bigint DEFAULT NULL,
  `metadata_json` text DEFAULT NULL,
  `error_code` varchar(128) DEFAULT NULL,
  `error_message` text DEFAULT NULL,
  `created` datetime NOT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_restore_point__uuid` (`uuid`),
  UNIQUE KEY `uk_dr_restore_point__plan_seq` (`plan_id`, `sequence_no`),
  KEY `i_dr_restore_point__plan_state_removed` (`plan_id`, `state`, `removed`),
  KEY `i_dr_restore_point__plan_target_ready` (`plan_id`, `target_ready_at`),
  CONSTRAINT `fk_dr_restore_point__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE
);
```

### 4.6 dr_restore_point_artifact

```sql
CREATE TABLE `cloud`.`dr_restore_point_artifact` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `restore_point_id` bigint unsigned NOT NULL,
  `source_volume_id` bigint unsigned DEFAULT NULL,
  `source_disk_ref` varchar(1024) DEFAULT NULL,
  `artifact_type` varchar(32) NOT NULL,
  `artifact_ref` varchar(2048) DEFAULT NULL,
  `format` varchar(32) DEFAULT NULL,
  `state` varchar(32) NOT NULL,
  `size_bytes` bigint DEFAULT NULL,
  `metadata_json` text DEFAULT NULL,
  `created` datetime NOT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_restore_point_artifact__uuid` (`uuid`),
  KEY `i_dr_restore_point_artifact__rp` (`restore_point_id`),
  KEY `i_dr_restore_point_artifact__source_volume` (`source_volume_id`),
  CONSTRAINT `fk_dr_restore_point_artifact__rp` FOREIGN KEY (`restore_point_id`) REFERENCES `dr_restore_point` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_restore_point_artifact__source_volume` FOREIGN KEY (`source_volume_id`) REFERENCES `volumes` (`id`) ON DELETE SET NULL
);
```

### 4.7 dr_replica

```sql
CREATE TABLE `cloud`.`dr_replica` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `target_site_id` bigint unsigned NOT NULL,
  `state` varchar(32) NOT NULL,
  `target_vm_id` bigint unsigned DEFAULT NULL,
  `target_external_ref` varchar(1024) DEFAULT NULL,
  `target_vm_name` varchar(255) DEFAULT NULL,
  `target_power_state` varchar(32) DEFAULT NULL,
  `latest_restore_point_id` bigint unsigned DEFAULT NULL,
  `latest_target_ready_at` datetime DEFAULT NULL,
  `runtime_state_json` text DEFAULT NULL,
  `created` datetime NOT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_replica__uuid` (`uuid`),
  KEY `i_dr_replica__plan_removed` (`plan_id`, `removed`),
  KEY `i_dr_replica__target_vm_removed` (`target_vm_id`, `removed`),
  KEY `i_dr_replica__target_external_ref` (`target_external_ref`, `removed`),
  KEY `i_dr_replica__state_ready` (`state`, `latest_target_ready_at`),
  CONSTRAINT `fk_dr_replica__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_replica__target_site_id` FOREIGN KEY (`target_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_replica__target_vm_id` FOREIGN KEY (`target_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_dr_replica__latest_restore_point_id` FOREIGN KEY (`latest_restore_point_id`) REFERENCES `dr_restore_point` (`id`) ON DELETE SET NULL
);
```

### 4.8 dr_replica_disk

```sql
CREATE TABLE `cloud`.`dr_replica_disk` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `replica_id` bigint unsigned NOT NULL,
  `source_volume_id` bigint unsigned DEFAULT NULL,
  `target_volume_id` bigint unsigned DEFAULT NULL,
  `target_disk_ref` varchar(2048) DEFAULT NULL,
  `state` varchar(32) NOT NULL,
  `format` varchar(32) DEFAULT NULL,
  `size_bytes` bigint DEFAULT NULL,
  `metadata_json` text DEFAULT NULL,
  `created` datetime NOT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_replica_disk__uuid` (`uuid`),
  KEY `i_dr_replica_disk__replica` (`replica_id`),
  KEY `i_dr_replica_disk__target_volume` (`target_volume_id`),
  CONSTRAINT `fk_dr_replica_disk__replica_id` FOREIGN KEY (`replica_id`) REFERENCES `dr_replica` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_replica_disk__source_volume_id` FOREIGN KEY (`source_volume_id`) REFERENCES `volumes` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_dr_replica_disk__target_volume_id` FOREIGN KEY (`target_volume_id`) REFERENCES `volumes` (`id`) ON DELETE SET NULL
);
```

### 4.9 dr_run

```sql
CREATE TABLE `cloud`.`dr_run` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `run_type` varchar(32) NOT NULL,
  `state` varchar(32) NOT NULL,
  `requested_by` varchar(255) DEFAULT NULL,
  `idempotency_key` varchar(255) DEFAULT NULL,
  `external_job_ref` varchar(1024) DEFAULT NULL,
  `current_step` varchar(255) DEFAULT NULL,
  `progress_percent` int DEFAULT NULL,
  `progress_message` text DEFAULT NULL,
  `error_code` varchar(128) DEFAULT NULL,
  `error_message` text DEFAULT NULL,
  `rollback_context_json` text DEFAULT NULL,
  `created` datetime NOT NULL,
  `started` datetime DEFAULT NULL,
  `finished` datetime DEFAULT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_run__uuid` (`uuid`),
  KEY `i_dr_run__plan_type_state` (`plan_id`, `run_type`, `state`),
  KEY `i_dr_run__state_created` (`state`, `created`),
  KEY `i_dr_run__plan_idempotency` (`plan_id`, `idempotency_key`),
  CONSTRAINT `fk_dr_run__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE
);
```

MySQL unique nullable behavior 때문에 `UNIQUE(plan_id, idempotency_key)`는 `NULL` 중복을 허용한다. 이 동작은 의도에 맞다. 단, non-null key는 service layer와 DB index 모두로 중복을 막는다.

### 4.10 dr_run_step

```sql
CREATE TABLE `cloud`.`dr_run_step` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `run_id` bigint unsigned NOT NULL,
  `step_no` int NOT NULL,
  `name` varchar(255) NOT NULL,
  `state` varchar(32) NOT NULL,
  `progress_percent` int DEFAULT NULL,
  `summary` text DEFAULT NULL,
  `details_json` text DEFAULT NULL,
  `started` datetime DEFAULT NULL,
  `finished` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_run_step__uuid` (`uuid`),
  UNIQUE KEY `uk_dr_run_step__run_step_no` (`run_id`, `step_no`),
  KEY `i_dr_run_step__run_state` (`run_id`, `state`),
  CONSTRAINT `fk_dr_run_step__run_id` FOREIGN KEY (`run_id`) REFERENCES `dr_run` (`id`) ON DELETE CASCADE
);
```

### 4.11 dr_event

```sql
CREATE TABLE `cloud`.`dr_event` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned DEFAULT NULL,
  `run_id` bigint unsigned DEFAULT NULL,
  `severity` varchar(32) NOT NULL,
  `source` varchar(32) NOT NULL,
  `event_type` varchar(128) NOT NULL,
  `message` text DEFAULT NULL,
  `details_json` text DEFAULT NULL,
  `external_ref` varchar(1024) DEFAULT NULL,
  `created` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_event__uuid` (`uuid`),
  KEY `i_dr_event__plan_run_created` (`plan_id`, `run_id`, `created`),
  KEY `i_dr_event__severity_created` (`severity`, `created`),
  CONSTRAINT `fk_dr_event__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_dr_event__run_id` FOREIGN KEY (`run_id`) REFERENCES `dr_run` (`id`) ON DELETE SET NULL
);
```

## 5. VO class 설계

권장 위치:

`plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/`

| VO | Table | 주요 interface |
| --- | --- | --- |
| `DrSiteVO` | `dr_site` | `InternalIdentity` |
| `DrSiteCredentialVO` | `dr_site_credential` | `InternalIdentity` |
| `DrSitePairVO` | `dr_site_pair` | `InternalIdentity` |
| `DrPlanVO` | `dr_plan` | `InternalIdentity`, account/domain getter |
| `DrRestorePointVO` | `dr_restore_point` | `InternalIdentity` |
| `DrRestorePointArtifactVO` | `dr_restore_point_artifact` | `InternalIdentity` |
| `DrReplicaVO` | `dr_replica` | `InternalIdentity` |
| `DrReplicaDiskVO` | `dr_replica_disk` | `InternalIdentity` |
| `DrRunVO` | `dr_run` | `InternalIdentity` |
| `DrRunStepVO` | `dr_run_step` | `InternalIdentity` |
| `DrEventVO` | `dr_event` | `InternalIdentity` |

VO 규칙:

- constructor에서 `uuid = UUID.randomUUID().toString()` 생성
- enum은 Java enum field가 아니라 String field로 시작한다.
- JSON field는 `String`으로 저장하고 parser/helper에서 구조화한다.
- secret 원문은 response VO에 저장하지 않는다. DB entity에는 `DrSiteCredentialVO.secretPayload`처럼 암호화 대상 field로만 저장한다.
- `removed`가 null이 아니면 active 조회에서 제외한다.
- `removed`는 VO setter/update로 직접 저장하지 않는다. soft delete는 DAO `remove(id)` 전용 경로로 수행한다.

## 6. DAO class 설계

권장 위치:

`plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/dao/`

| DAO interface | DAO impl |
| --- | --- |
| `DrSiteDao` | `DrSiteDaoImpl` |
| `DrSitePairDao` | `DrSitePairDaoImpl` |
| `DrPlanDao` | `DrPlanDaoImpl` |
| `DrRestorePointDao` | `DrRestorePointDaoImpl` |
| `DrRestorePointArtifactDao` | `DrRestorePointArtifactDaoImpl` |
| `DrReplicaDao` | `DrReplicaDaoImpl` |
| `DrReplicaDiskDao` | `DrReplicaDiskDaoImpl` |
| `DrRunDao` | `DrRunDaoImpl` |
| `DrRunStepDao` | `DrRunStepDaoImpl` |
| `DrEventDao` | `DrEventDaoImpl` |

DAO impl은 constructor에서 `SearchBuilder`를 정의한다.

필수 검색:

- uuid lookup
- active lookup with `removed IS NULL`
- plan state lookup
- active run lookup
- latest restore point lookup
- latest target-ready restore point lookup
- engine binding lookup
- event time range lookup

## 7. JSON field 규칙

초기 구현에서는 빠른 개발을 위해 JSON을 `text`로 저장한다.

단, 다음 값은 자주 조회하므로 column으로 승격되어 있다.

- `dr_plan.direction`
- `dr_plan.state`
- `dr_plan.engine_binding_type`
- `dr_plan.engine_binding_id`
- `dr_restore_point.state`
- `dr_restore_point.target_ready_at`
- `dr_replica.state`
- `dr_replica.latest_target_ready_at`
- `dr_run.run_type`
- `dr_run.state`
- `dr_run.progress_percent`
- `dr_event.severity`
- `dr_event.source`

JSON parser helper:

- `DrJsonHelper`
- invalid JSON은 API validation에서 거부
- DB에 저장된 invalid JSON은 response 생성 시 raw string을 그대로 노출하지 않고 warning을 남긴다.

## 8. 기존 모델 연결

| 기존 모델 | 신규 연결 | 규칙 |
| --- | --- | --- |
| `disaster_recovery_cluster` | `dr_site_pair.legacy_dr_cluster_id` | 자동 생성은 Phase 1에서 하지 않음 |
| `ftctl_protection` | `dr_plan.engine_binding_type=FTCTL`, `engine_binding_id` | 기존 성공 경로 보존 |
| `ftctl_protection_volume` | `dr_replica_disk.metadata_json` 또는 projection | 중복 저장 최소화 |
| `vm_instance_details ftctl.*` | `dr_replica.runtime_state_json` projection | source of truth는 FTCTL runtime |
| V2K workdir/manifest | `dr_run.external_job_ref`, `dr_run_step.details_json` | 사용자는 DrRun만 봄 |
| VMware MoRef | `target_external_ref`, `source_external_ref`, artifact ref | 안정 식별자로 저장 |

## 9. Retention과 cleanup

Retention 기본값:

| Table | 정책 |
| --- | --- |
| `dr_restore_point` | plan policy에 따라 만료 |
| `dr_restore_point_artifact` | restore point 만료 시 함께 정리 |
| `dr_run` | 장기 audit 보존, 기본 삭제 없음 |
| `dr_run_step` | run 보존 기간과 동일 |
| `dr_event` | UI event는 30일 기본 보존, audit event는 별도 정책 가능 |

Cleanup worker는 물리 artifact 제거와 DB soft delete를 분리한다.

- 물리 artifact 제거 성공 후 `removed` 갱신
- 물리 artifact 제거 실패 시 `state=FAILED`와 event 기록
- forced cleanup은 warning event를 남기고 DB 삭제/soft delete 범위를 명확히 표시

## 10. Upgrade 검증

SQL 검증:

- fresh schema 생성 성공
- upgrade SQL 적용 성공
- FK 생성 성공
- index 생성 성공
- table drop 순서 문제 없음

DAO 검증:

- 각 DAO bean 로딩
- uuid lookup
- active lookup
- soft delete 후 active lookup 제외
- idempotency key lookup
- latest restore point query
- latest target ready query

호환 검증:

- 기존 `disaster_recovery_cluster` API 영향 없음
- 기존 `ftctl_protection` table 영향 없음
- 기존 FTCTL 보호 row가 있어도 신규 table 생성/upgrade 성공

## 11. 2026-07-02 추가 설계: DR Site health check history table

상세 설계는 [529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md](529-cross-hypervisor-dr-site-health-check-history-and-scheduler-design-20260702.md)를 따른다.

Site health check는 최신 상태만 `dr_site`에 저장하고, 이력은 신규 `dr_site_health_check` table에 append-only로 저장한다.

대상 schema 파일:

- `engine/schema/src/main/resources/META-INF/db/schema-42200to42210.sql`
- `engine/schema/src/main/resources/META-INF/db/schema-42210to42300.sql`
- `engine/schema/src/main/resources/META-INF/db/schema-Europa-After.sql`

DDL 기준:

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

보존 정책:

- `dr.site.health.check.history.retention.days` 기본값은 30일이다.
- scheduler cleanup 단계가 `checked_at` 기준으로 오래된 row를 삭제한다.
- password, API key, secret key, token은 `details_json`에 저장하지 않는다.

VO/DAO:

| Class | 책임 |
| --- | --- |
| `DrSiteHealthCheckVO` | `dr_site_health_check` entity |
| `DrSiteHealthCheckDao` | site/time/state/trigger 기준 조회, retention cleanup |
| `DrSiteHealthCheckDaoImpl` | `SearchBuilder` 기반 pagination search |

## 12. 구현 수용 기준

- `setup/db/create-schema.sql` fresh install 경로에 모든 신규 table이 있다.
- active upgrade 경로에 동일 schema 변경이 있다.
- 신규 VO/DAO가 Spring context에 등록된다.
- `DrSiteCredentialVO`, `DrSiteCredentialDao`, `DrSiteCredentialDaoImpl`이 추가되고 `secretPayload` 암호화가 검증된다.
- `git diff --check`와 DB bootstrap smoke가 통과한다.
- `createDrPlan` 전에 DAO로 source VM active plan 중복을 조회할 수 있다.
- `startDrSync` 전에 DAO로 active run 중복을 조회할 수 있다.
- `listDrPlans`가 account/domain/removed/state filter를 사용할 수 있다.
- `listDrEvents`가 plan/run/time filter를 사용할 수 있다.

## 13. AS-IS / TO-BE 요약

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| DB 모델 | 기존 DR cluster, FTCTL protection 별도 | `dr_site`, `dr_plan`, `dr_run` 중심 공통 모델 |
| Site 인증정보 | `dr_site.credential_ref` 문자열 중심 | `dr_site_credential.secret_payload` 암호화 저장과 `dr_site.credential_id` 참조 |
| Fresh install | 기존 table만 생성 | `create-schema.sql`에 신규 table 포함 |
| Upgrade | 신규 DR table 없음 | active upgrade path에 DDL 포함 |
| Entity | 기존 VO/DAO만 존재 | 신규 VO/DAO/DaoImpl 추가 |
| 실행 상태 | async job 또는 engine별 상태 | `dr_run`, `dr_run_step`, `dr_event`로 통합 |
| Runtime projection | VM details 또는 engine event에 분산 | `dr_replica.runtime_state_json`와 event로 projection |
| Soft delete | 기존 모델별 개별 처리 | 모든 주요 table `removed` 기준 active 조회 |
| Plan JSON | 사용자가 입력한 raw JSON 저장 가능 | backend-generated canonical spec JSON 저장 |

## 14. 2026-07-03 Remote Inventory External ID DDL

상세 설계는 [530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md](530-cross-hypervisor-dr-site-inventory-and-detail-ux-design-20260703.md)의
`Remote Inventory ID 모델 보정` 절을 따른다.

### 14.1 DDL

`dr_site.zone_id`와 `dr_site.vmware_datacenter_id`는 local internal id로 유지한다.
원격 Mold/vCenter inventory 선택값은 별도 external field에 저장한다.

Fresh schema의 `dr_site` 정의에 다음 컬럼과 index를 포함한다.

```sql
`zone_external_id` varchar(255) DEFAULT NULL,
`zone_name` varchar(255) DEFAULT NULL,
`vmware_datacenter_external_id` varchar(255) DEFAULT NULL,
`vmware_datacenter_name` varchar(255) DEFAULT NULL,
KEY `i_dr_site__zone_external_id` (`zone_external_id`),
KEY `i_dr_site__vmware_dc_external_id` (`vmware_datacenter_external_id`)
```

Upgrade SQL 기준:

```sql
ALTER TABLE `cloud`.`dr_site`
  ADD COLUMN `zone_external_id` varchar(255) DEFAULT NULL AFTER `zone_id`,
  ADD COLUMN `zone_name` varchar(255) DEFAULT NULL AFTER `zone_external_id`,
  ADD COLUMN `vmware_datacenter_external_id` varchar(255) DEFAULT NULL AFTER `vmware_datacenter_id`,
  ADD COLUMN `vmware_datacenter_name` varchar(255) DEFAULT NULL AFTER `vmware_datacenter_external_id`,
  ADD KEY `i_dr_site__zone_external_id` (`zone_external_id`),
  ADD KEY `i_dr_site__vmware_dc_external_id` (`vmware_datacenter_external_id`);
```

제품 DB가 `vmware_dc_id` 컬럼명을 사용하는 branch라면 `AFTER vmware_dc_id`와
`vmware_dc_external_id`, `vmware_dc_name`으로 맞춘다. 현재 배포/Java entity 기준 컬럼명은
`vmware_datacenter_id`이므로 구현 기본값은 `vmware_datacenter_*`이다.

### 14.2 Entity mapping

`DrSiteVO` 추가 field:

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

수용 기준:

- `create-schema.sql`와 active upgrade SQL 결과가 동일해야 한다.
- existing row의 `zone_id`, `vmware_datacenter_id`는 자동으로 external id로 복사하지 않는다.
- `createDrSite`/`updateDrSite`로 external field 저장 후 `listDrSites`, `getDrSite` response에서 동일 값이 확인되어야 한다.
- UUID external id가 저장되어도 DB 오류가 없어야 한다.

## 15. 2026-07-05 DR Plan Guided Spec DB 기준

상세 설계는 [531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md](531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md)를 따른다.

이번 개선은 신규 DB column을 요구하지 않는다. 현재 fresh/upgrade schema와 `DrPlanVO`는 다음 consolidated JSON column을 사용한다.

| Column | 용도 |
| --- | --- |
| `schedule_json` | backend-generated schedule canonical JSON |
| `mapping_json` | backend-generated target/resource/disk/network mapping canonical JSON |
| `policy_json` | backend-generated failover/test/retry/transport policy canonical JSON |
| `quiesce_policy_json` | backend-generated consistency/quiesce canonical JSON |
| `source_worker_host_id` | source worker host binding |
| `target_worker_host_id` | target worker host binding |
| `coordinator_worker_host_id` | coordinator worker host binding |

기존 4장 초기 DDL 예시에 남아 있는 `storage_mapping_json`, `network_mapping_json`, `compute_mapping_json`, `fencing_policy_json` 분리 모델은 초기 논리 설계 표현이다. 현재 구현 기준은 `mapping_json`과 `policy_json`으로 통합된 모델이며, guided spec builder도 이 consolidated column에 `schemaVersion`을 포함한 canonical JSON을 저장한다.

후속 검색/필터 요구가 생기기 전까지는 다음 항목을 별도 column으로 만들지 않는다.

- `sync_interval_seconds`
- `retention_count`
- `consistency_mode`
- `test_network_mode`

수용 기준:

- guided spec 구현 후에도 fresh schema와 upgrade schema에 신규 DR Plan column이 추가되지 않는다.
- `createDrPlan`/`updateDrPlan`이 typed parameter를 받아도 최종 저장은 기존 JSON column에 이루어진다.
- expert raw JSON override가 사용되더라도 저장 전 JSON syntax와 direction별 semantic validation을 통과해야 한다.

## 16. 2026-07-06 VMware -> ABLESTACK Plan Placement DB 기준

`VMWARE_TO_KVM` Plan 보강에서도 1차 구현은 신규 `dr_plan` column을 추가하지 않는다. target ABLESTACK 배치 정보는 기존 site/entity column과 `mapping_json`에 canonical 구조로 저장한다.

| 데이터 | 저장 위치 |
| --- | --- |
| target site Zone | `dr_site.zone_id`, `dr_site.zone_external_id`, `dr_site.zone_name` |
| Plan target Zone snapshot | `dr_plan.mapping_json.target.zoneId` |
| service offering | `dr_plan.mapping_json.target.serviceOfferingId` |
| target network | `dr_plan.mapping_json.target.networks[]` |
| target storage pool | `dr_plan.mapping_json.target.storageRef`, `dr_plan.mapping_json.disks[].target.storageRef` |
| disk offering | `dr_plan.mapping_json.disks[].target.diskOfferingId` |
| target worker | `dr_plan.target_worker_host_id` |
| coordinator worker | `dr_plan.coordinator_worker_host_id` |

KVM target site에 `zone_id`가 없으면 Plan inventory는 target option을 임의 생성하지 않고 `TARGET_SITE_ZONE_REQUIRED`를 반환한다. site 수정에서 Zone을 저장한 뒤 Plan inventory를 다시 조회하는 것이 정상 흐름이다.

후속으로 검색/감사 요구가 생기면 `dr_plan_disk_mapping` 같은 normalized table을 검토할 수 있으나, 실행 준비성 보강의 1차 범위에서는 기존 JSON column과 worker host column을 유지한다.

## 2026-07-06 보강: Run Acceptance와 Projection 상태 저장

DR run/plan 상태 정합성 보강은 [533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md](533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md)를 따른다.

DB 설계 원칙:

- `dr_run`은 engine acceptance 여부를 명시적으로 저장한다.
- 권장 컬럼은 `engine_accepted`, `accepted_at`, `dispatch_started`, `dispatch_completed`, `projection_state`, `projection_checked`이다.
- `dr_run_step`은 `(run_id, step_order)` 기준으로 upsert되도록 DAO를 보강한다.
- unique key는 기존 중복 데이터 정리 후 2단계로 적용한다.
- latest run 조회 성능을 위해 `dr_run(plan_id, created)`와 `dr_run(plan_id, state, completed)` 인덱스를 유지한다.

## 17. 2026-07-06 추가 보강: retryable lock과 projection stale 저장 기준

상세 기준은 [533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md](533-cross-hypervisor-dr-dispatch-projection-recovery-design-20260706.md)의 21장을 따른다.

### 17.1 `dr_run` 보강

retryable FTCTL lock과 status timeout을 UI/API가 안정적으로 표시하려면 run에 retry metadata가 남아야 한다.

권장 DDL:

```sql
ALTER TABLE `cloud`.`dr_run`
  ADD COLUMN `retryable` tinyint(1) NOT NULL DEFAULT 0 AFTER `projection_checked`,
  ADD COLUMN `retry_count` int NOT NULL DEFAULT 0 AFTER `retryable`,
  ADD COLUMN `retry_after_seconds` int DEFAULT NULL AFTER `retry_count`,
  ADD COLUMN `next_retry_at` datetime DEFAULT NULL AFTER `retry_after_seconds`,
  ADD COLUMN `last_status_json` mediumtext DEFAULT NULL AFTER `next_retry_at`;
```

기존 운영 DB에 컬럼이 일부 존재하면 `ADD COLUMN IF NOT EXISTS`를 지원하지 않는 MySQL 버전을 고려해 배포 스크립트에서 information_schema 확인 후 반영한다.

Entity 보강:

```java
@Column(name = "retryable")
private boolean retryable;

@Column(name = "retry_count")
private int retryCount;

@Column(name = "retry_after_seconds")
private Integer retryAfterSeconds;

@Column(name = "next_retry_at")
private Date nextRetryAt;

@Column(name = "last_status_json")
private String lastStatusJson;
```

### 17.2 `dr_plan` projection stale 보강

`dr-status` timeout은 runtime failure가 아니라 조회 실패일 수 있으므로 plan projection 상태를 별도로 저장한다.

권장 DDL:

```sql
ALTER TABLE `cloud`.`dr_plan`
  ADD COLUMN `projection_refreshing` tinyint(1) NOT NULL DEFAULT 0,
  ADD COLUMN `projection_error_code` varchar(128) DEFAULT NULL,
  ADD COLUMN `projection_error_message` varchar(1024) DEFAULT NULL,
  ADD COLUMN `projection_checked` datetime DEFAULT NULL;
```

이미 plan response가 `runtimeprojectionstate/message`를 다른 컬럼에서 계산한다면 중복 컬럼을 만들지 않고 DAO/response mapping을 그 컬럼에 맞춘다. 핵심은 status refresh 실패가 `plan.state=ERROR`로 즉시 승격되지 않는 것이다.

### 17.3 `dr_run_step` 중복 정리와 unique key

현재 장애에서 같은 run에 `execute` step이 중복되고, 실패 이후에도 `QUEUED`/`RUNNING` step이 남는 문제가 확인되었다.

1차 구현:

- DAO `recordStep`을 `(run_id, step_order)` upsert로 변경한다.
- terminal run 전환 시 open step을 닫는다.

중복 확인:

```sql
SELECT run_id, step_order, COUNT(*) AS cnt
  FROM `cloud`.`dr_run_step`
 WHERE removed IS NULL
 GROUP BY run_id, step_order
HAVING cnt > 1;
```

2차 적용:

```sql
ALTER TABLE `cloud`.`dr_run_step`
  ADD UNIQUE KEY `uk_dr_run_step__run_order` (`run_id`, `step_order`);
```

### 17.4 현재 장애 데이터 보정 기준

구현 후 운영 보정 시 다음 기준을 적용한다.

- 최신 run이 `FAILED`이고 `engine_accepted=0`이면 plan은 `SYNCING`이 될 수 없다.
- plan `last_error_code/message`는 최신 terminal run error를 반영한다.
- 같은 run의 open step은 `FAILED` 또는 `SKIPPED`로 닫는다.
- retryable lock 실패는 `retryable=1`, `retry_after_seconds`, `next_retry_at`을 보존한다.

수용 기준:

- `dr_run` 최신 상태만으로 UI가 retry 가능 여부와 다음 재시도 시각을 표시할 수 있다.
- `dr_plan` projection stale 여부와 terminal runtime failure가 구분된다.
- fresh schema, upgrade schema, active DB 수동 보정 스크립트가 같은 컬럼 집합을 만든다.

## 18. 2026-07-06 추가 보강: readiness 계산과 false-success 데이터 보정

상세 기준은 [534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md](534-cross-hypervisor-dr-sync-readiness-and-materialization-contract-design-20260706.md)를 따른다.

1차 구현은 새 컬럼 없이 기존 테이블로 readiness를 계산한다.

계산 입력:

- `dr_plan.state`
- `dr_run.state`, `projection_state`, `external_job_ref`
- `dr_replica.target_vm_id`, `target_external_ref`, `target_vm_name`
- `dr_restore_point`
- target site의 `vm_instance`, `volumes`, `nics`
- ftctl runtime의 `last_target_durable_at`

조회 성능 때문에 캐시가 필요하면 다음 컬럼을 2차로 추가한다.

```sql
ALTER TABLE `cloud`.`dr_plan`
  ADD COLUMN `readiness_state` varchar(32) DEFAULT NULL,
  ADD COLUMN `readiness_reason_code` varchar(64) DEFAULT NULL,
  ADD COLUMN `readiness_message` varchar(1024) DEFAULT NULL,
  ADD COLUMN `target_materialized` tinyint(1) DEFAULT 0,
  ADD COLUMN `last_target_durable_at` datetime DEFAULT NULL;
```

false success 후보 점검 SQL:

```sql
SELECT p.id, p.uuid, p.state, r.state AS run_state,
       rp.target_vm_id, rp.target_external_ref,
       COUNT(pt.id) AS restore_points
  FROM `cloud`.`dr_plan` p
  JOIN `cloud`.`dr_run` r ON r.plan_id = p.id AND r.removed IS NULL
  LEFT JOIN `cloud`.`dr_replica` rp ON rp.plan_id = p.id AND rp.removed IS NULL
  LEFT JOIN `cloud`.`dr_restore_point` pt ON pt.plan_id = p.id AND pt.removed IS NULL
 WHERE p.removed IS NULL
 GROUP BY p.id, r.id, rp.id
HAVING r.state = 'SUCCEEDED'
   AND (rp.target_vm_id IS NULL OR rp.target_external_ref IS NULL OR restore_points = 0);
```

운영 보정 원칙:

- 배포 전 수동 SQL로 성공 상태를 임의 확정하지 않는다.
- projection worker가 target materialization verifier 결과를 근거로 `TARGET_MATERIALIZING`, `DEGRADED`, `READY` 중 하나로 보정한다.
- `dr_run`이 이미 `SUCCEEDED`로 기록되었더라도 target readiness가 없으면 보정 run 또는 projection repair로 상태를 낮춘다.

## 2026-07-07 Update: DB Projection Consistency For Disk Readiness Failures

No new table is required for the current disk readiness hardening, but existing
rows must be updated consistently when FTCTL reports a terminal target-map
failure.

Required persistence behavior:

- `dr_plan.state=ERROR`
- `dr_plan.last_error_code=DR_TARGET_DISK_SIZE_UNRESOLVED`
- `dr_plan.last_error_message` contains the disk index and source disk id when
  available.
- `dr_run.state=FAILED`
- `dr_run.projection_state=terminal_error`
- `dr_run.projection_checked` is updated when runtime status is reconciled.
- `dr_replica.state=ERROR` or `DISK_MAP_INVALID`; it must not remain
  `SKELETON_READY` without target VM/disk references.
- `dr_restore_point` remains empty until a durable checkpoint is actually
  produced.

DAO queries should provide active accepted/running runs for projection
reconciliation. Detailed field mapping is in
`538-cross-hypervisor-dr-vmware-to-kvm-disk-size-and-projection-hardening-design-20260707.md`.

## 2026-07-07 Update: DB Contract For Default And Disk-level Storage

The default-storage and disk-level-storage refinement is documented in
[539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md](539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md).

No schema change is required.

Persistence rules:

- `dr_plan.mapping_json.target.storageRef` stores the backend-resolved default
  or fallback storage when available.
- `dr_plan.mapping_json.disks[].target.storageRef` stores the effective
  disk-level target storage and is authoritative for that disk.
- Runtime fields under `mapping_json.disks[].target`, such as `storagePath`,
  `storagePoolType`, `storageHostAddress`, and `krbdPath`, remain
  backend-owned.
- Legacy rows that only have top-level storage remain readable. Preview or
  update should rebuild canonical JSON through the guided spec builder and
  preserve disk-level storage where present.

## 2026-07-07 Update: DB Impact Of DR Plan SharedFS Dialog Standard

The SharedFS-style DR Plan dialog standard is a UI-only structural change. The
detailed design is documented in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md).

No DB schema change or migration is required.

Do not persist:

- active collapse section keys;
- review panel values;
- UI-only field hint state;
- validation focus section;
- dialog scroll position.

Existing DR Plan rows remain canonical through `dr_plan.mapping_json`,
`dr_plan.schedule_json`, `dr_plan.policy_json`, and
`dr_plan.quiesce_policy_json`. When an existing plan is opened for edit, the UI
must rehydrate display state from those canonical JSON fields and current
inventory API responses.

## 2026-07-07 Update: DB Impact Of Modal Alert And Gutter Refinement

The DR Plan modal alert/gutter refinement is documented in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md#13-2026-07-07-refinement-dark-alert-and-right-gutter).

No DB schema or migration change is required.

Do not store:

- dark-mode alert preference;
- modal width;
- right gutter width;
- scroll position;
- browser-specific layout state.

The persisted DR Plan contract remains limited to canonical business state and
runtime state, not UI presentation state.

## 2026-07-07 Update: VMware Data-Plane DB Contract

The VMware source VDDK libdir readiness design is defined in
[543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md](543-cross-hypervisor-dr-vddk-libdir-resolution-and-preflight-design-20260707.md).

No mandatory DB schema migration is required for the immediate fix. Existing
JSON extension fields are sufficient:

| Table | Field | Usage |
| --- | --- | --- |
| `dr_site` | `capabilities_json` | latest `vmwareDataPlane` readiness and optional `vmware.vddkLibdirOverride` |
| `dr_site_health_check` | `details_json` | historical site check and data-plane snapshot when available |
| `dr_run` | `last_status_json` | FTCTL status with `vmware_data_plane` diagnostics |
| `dr_run_step` | `details_json` | preflight or mover failure detail |
| `dr_event` | `details_json` | operator-facing runtime evidence |

Do not store VDDK libdir in `dr_site_credential.secret_payload`; it is not a
secret. If an operator override is needed, store it in site capability JSON.

Optional future indexed columns may be added only if listing/filtering by
VMware data-plane state becomes a product requirement:

```sql
ALTER TABLE dr_site
  ADD COLUMN vmware_vddk_libdir varchar(1024) DEFAULT NULL,
  ADD COLUMN vmware_dataplane_state varchar(32) DEFAULT NULL,
  ADD COLUMN vmware_dataplane_checked datetime DEFAULT NULL;
```

## 2026-07-07 Update: VMware Mover Source Graph DB Contract

The VMware mover NBD source graph design is defined in
[544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md](544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md).

No DB schema migration is required.

Persist the new terminal error through existing fields:

| Table | Field | Usage |
| --- | --- | --- |
| `dr_plan` | `state`, `last_error_code`, `last_error_message` | `ERROR`, `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID`, sanitized operator message |
| `dr_run` | `state`, `projection_state`, `completed`, `last_status_json`, `error_code`, `error_message` | terminal failed run and raw projected FTCTL status |
| `dr_run_step` | `step_name`, `state`, `error_code`, `details_json` | failed `runtime-projection` step |
| `dr_event` | `event_type`, `severity`, `details_json` | operator-facing runtime projection evidence |
| `dr_replica` / `dr_replica_disk` | existing state/details fields | do not mark target ready when only target storage exists |

The runtime may leave a pre-created target RBD image when failure happens after
target preparation. That is operational cleanup state, not a schema reason to
mark the plan ready.

## 2026-07-08 Update: DB Contract For Snapshot MoRef Resolve And Payload Size

The VMware VDDK connect follow-up is documented in
[545-cross-hypervisor-dr-vmware-vddk-connect-contract-design-20260708.md](545-cross-hypervisor-dr-vmware-vddk-connect-contract-design-20260708.md#29-live-snapshot-moref-resolve-and-payload-stability-follow-up---2026-07-08).

No DB schema migration is required for the immediate fix.

Persistence rules:

| Table | Field | Required content |
| --- | --- | --- |
| `dr_plan` | `state` | `ERROR` while snapshot ref cannot be resolved |
| `dr_plan` | `last_error_code` | `DR_VMWARE_SNAPSHOT_REF_UNRESOLVED` when the run snapshot exists but MoRef resolve failed |
| `dr_plan` | `last_error_message` | short operator message, never raw `dr-status --json` |
| `dr_run` | `state` | `FAILED` for terminal snapshot resolve failure |
| `dr_run` | `last_status_json` | full redacted runtime status, including `source_snapshot` and `source_open` |
| `dr_run_step` | `details_json` | compact projection summary; do not duplicate full runtime status for every step |
| `dr_event` | `details_json` | operator-facing evidence, including cleanup hint when a run snapshot remains |
| `dr_replica` / `dr_replica_disk` | state fields | remain `ERROR` until a durable checkpoint and materialized target exist |

Existing failed rows that already contain raw JSON in `last_error_message` or
oversized step `details_json` do not require schema migration. A cleanup or
projection repair task may rewrite them to the short-message discipline before
the next retest.

## 2026-07-10 Normative Checkpoint Deduplication Update

The physical `dr_restore_point` table remains for compatibility but stores
synchronization checkpoint history, not point-in-time recovery artifacts.
Add run ID, checkpoint sequence, cycle type, and nullable SHA-256 reference
hash columns. Active rows are unique by `(plan_id, checkpoint_ref_hash)`.
Duplicate active rows are repaired before index creation, and soft delete
clears the active hash.

Event queries require plan/run plus created-time indexes and server-side
pagination. Successful unchanged projection polls are not persisted.

Detailed DDL and repair order:
`549-cross-hypervisor-dr-checkpoint-history-event-rpo-design-20260710.md`.

## 2026-07-10 Normative Protection View Cache And Completed Row Update

Add `dr_plan_view_cache` with one row per Plan, schema version, revision,
SHA-256 payload hash, redacted `MEDIUMTEXT` JSON, projection/generated/expiry
timestamps, refresh state, and next-refresh index. This is a derived read
model; authoritative domain rows remain unchanged.

Synchronization History queries return only completed rows. An existing READY
row for current sequence N is downgraded to `IN_PROGRESS` when FTCTL reports
latest completed sequence N-1. It becomes READY only after FTCTL publishes the
matching completed reference and timestamps.

Successful informational `PROJECTION_REFRESH` rows are removed in bounded
batches after new persistence policy deployment.

Detailed DDL, DAO methods, repair, and cleanup design:
`550-cross-hypervisor-dr-protection-view-cache-and-completed-checkpoint-design-20260710.md`.

## 2026-07-14 Async Read Consistency DB Impact

No schema migration is required for the UI live-cache correction.
`dr_plan_view_cache` remains the single derived snapshot row per Plan and
`generated_at` remains the freshness marker. UI polling reads this row through
`getDrProtectionView`; it does not write domain, event, checkpoint, or cache
rows. Scheduler and explicit async refresh remain the only cache producers.

Detailed consumer and acceptance design:
`552-cross-hypervisor-dr-async-read-consistency-and-live-cache-ui-design-20260714.md`.

## 2026-07-14 Cutover Session Schema Addendum

Guest preparation과 test artifact cleanup은 derived cache만으로 복구할 수
없으므로 schema migration이 필요하다. `dr_cutover_session`은 Plan/Run,
checkpoint, guest preparation, domain/boot, cleanup state를 저장하고,
`dr_cutover_disk`는 disk별 checkpoint, writable layer, rollback reference를
저장한다.

Europa upgrade path와 clean schema에 동일 DDL을 추가한다. Protection-view
cache의 `cutover` object는 read projection이며 authoritative cleanup source가
아니다. 상세 DDL:
`554-cross-hypervisor-dr-vmware-to-kvm-cutover-and-virtio-bootstrap-design-20260714.md`.

## 2026-07-16 Cycle Failure Persistence Addendum

No mandatory schema migration is required for the first corrective release.
Existing Plan/Run error columns store only bounded normalized messages, the
complete runtime status remains in `last_status_json`, and step/event rows keep
the durable failure evidence. A restore point is inserted only after FTCTL has
reported `LOCAL_DURABLE` and Cloud projection has completed.

Deployment reconciliation must normalize legacy rows whose error text contains
a complete status JSON document. It must not delete Plan, Run, replica, or
checkpoint evidence while repairing read compatibility.

Detailed persistence and reconciliation rules:
`557-cross-hypervisor-dr-cycle-commit-and-api-json-recovery-design-20260716.md`.

## 2026-07-17 Incremental Decision Schema Addendum

A forward-only idempotent migration adds typed mode-decision aggregates to
`dr_sync_cycle` and latest incremental/reseed-loop authority to
`dr_plan_runtime`. Backfill joins existing READY checkpoint evidence and fills
only non-null facts; it never fabricates requested mode or incremental proof.
The exact columns and reconciliation order are defined in
`559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md`.

## 2026-07-19 Test Failover Resource Schema Addendum

Forward-only migrations add `dr_test_session` and `dr_test_disk`. These rows
join the Cloud test VM/volumes to the FTCTL engine session/artifacts and persist
cleanup-required state across management restart. Existing Run JSON is not the
authority for active test resources, and `dr_cutover_session` is not reused.

Exact DDL and index design:
`561-cross-hypervisor-dr-cloud-managed-test-failover-lifecycle-design-20260719.md`.

## 2026-07-27 Failback Reconciliation Index And Transaction Addendum

`dr_failback_session`의 기존 lifecycle/ACK 컬럼을 재사용한다. management
restart와 late scheduler ACK 뒤에도 전환 row를 찾을 수 있도록 다음 복합
인덱스를 clean schema와 모든 활성 Europa upgrade path에 동일하게 추가한다.

```sql
KEY `i_dr_failback_session_reconcile`
  (`state`, `last_probe_at`, `removed`, `plan_id`)
```

upgrade DDL은 `information_schema.statistics`로 인덱스 존재 여부를 확인한 뒤
실행해 재적용에 안전해야 한다. terminal 수렴은 `Transaction.execute` 안에서
Plan, Session, Run, Replica 순서로 row lock을 획득하고
`lifecycle_version` CAS를 검증한다. JVM in-flight set은 DB CAS를 대체하지
않는다.

상세 candidate query, terminal transaction과 배포 검증은
[576-cross-hypervisor-dr-failback-late-ack-and-projection-convergence-design-20260727.md](576-cross-hypervisor-dr-failback-late-ack-and-projection-convergence-design-20260727.md)를
따른다.

## 2026-07-28 Cutover Authority End Addendum

`removed`는 soft-delete이며 current authority flag로 사용하지 않는다.
`dr_cutover_session`에 `authority_ended_at`,
`authority_ended_by_run_id`와 plan/state 조회 인덱스를 추가한다. 성공한
Failback은 current cutover를 `FAILED_BACK`으로 종결한다.

`dr_run_step`에는 `(run_id, step_name, removed)` 조회 인덱스를 추가하고
Failback lifecycle 단계는 멱등 upsert한다. 기존 data backfill 조건과
migration preflight는 문서 578을 따른다.

## 2026-07-30 Current Runtime And History Projection Addendum

current runtime과 historical Run 분리는 기존 테이블 의미로 충분하므로 신규
DDL을 추가하지 않는다.

- `dr_plan_runtime`: 현재 protection/scheduler/replication authority
- `dr_run`: 종료 작업을 포함한 불변 작업 이력
- `dr_test_session`: 테스트 자원과 cleanup 감사 이력
- `dr_plan_view_cache`: version 5 읽기 projection

version 4 cache row는 조회 시 version 5로 자동 rebuild한다. 과거 실패
Run이나 test session을 삭제 또는 성공으로 변경하지 않는다. 상세 cache
무효화와 검증 기준은 문서 580을 따른다.

## 2026-07-30 Post-Failover DB Addendum

TARGET authority Runtime은 `FAILED_OVER_UNPROTECTED`, scheduler
`STOPPED/desired STOPPED/SUPPRESSED`로 backfill한다. 실제 Failover의 디스크
감사를 위해 `dr_cutover_disk`에 target volume, checkpoint sequence, manifest
hash typed 컬럼을 보강하고 `(session_id, disk_index)` unique key를 유지한다.
Protection View version 5 cache는 version 6 조회 시 자동 rebuild한다. 상세 DDL과
backfill 기준은 문서 581을 따른다.
## 2026-07-30 Failback Resume Sequence Evidence Addendum

`dr_failback_session`은 `resume_baseline_checkpoint_sequence`,
`required_post_failback_checkpoint_sequence`, `protection_resume_requested_at`,
`protection_resume_verified_at`을 typed lifecycle 증거로 저장한다. 신규 Session은
`required = checkpoint_sequence + 1`을 사용하며 terminal 완료 시
`post_failback_checkpoint_sequence >= required`를 만족해야 한다. DDL과 migration
guard는 문서 583을 따른다.

## 2026-08-06 Failback Evidence Persistence Addendum

The existing `dr_failback_session` durability columns are the canonical Cloud
projection of FTCTL reverse evidence. No new table or migration is required for
the publication correction. Session state `DATA_EVIDENCE_PENDING`, existing
probe timestamps, details JSON, and Run status JSON make bounded asynchronous
reconciliation restart-safe. The lifecycle gate may pass only after all
mandatory evidence columns are atomically populated. See document 596.

## 2026-07-30 Action Availability Cache Addendum

작업 applicability, enabled, reason은 영구 domain state가 아니라 current
projection이므로 신규 테이블과 컬럼을 추가하지 않는다.
`dr_plan_view_cache.snapshot_json`의 `planProjection`에
`actionavailability`를 포함하고 snapshot version을 `6 -> 7`로 올린다.

version 6 cache는 기존 조회 시 rebuild 경로로 version 7을 생성한다. label,
icon, group, locale 문장과 dark-mode style은 DB에 저장하지 않는다. 상세 cache
payload와 호환 기준은
[584-cross-hypervisor-dr-context-action-availability-and-darkmode-design-20260730.md](584-cross-hypervisor-dr-context-action-availability-and-darkmode-design-20260730.md)를
따른다.

## 2026-07-31 Test Session Open/History Boundary Addendum

`dr_test_session.removed`는 감사 이력 삭제가 아니라 현재 lifecycle 종결
시각이다. `FAILED + cleanup_required=false` 세션은 Cloud VM/Test Disk와
FTCTL artifact/lease terminal proof를 확인한 뒤 soft-close한다.

DAO는 open 조회와 removed 행을 포함하는 historical 조회를 분리한다.
DB upgrade SQL로 FAILED 행을 일괄 변경하지 않으며 배포 후 backend
reconciler가 증거 기반으로 보정한다. cache snapshot version 및 상세 데이터
계약은
[586-cross-hypervisor-dr-test-session-blocker-and-async-acceptance-design-20260731.md](586-cross-hypervisor-dr-test-session-blocker-and-async-acceptance-design-20260731.md)를
따른다.
