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

# Cross Hypervisor DR Integration Smoke And Deployment Prep

작성일: 2026-07-01

대상 브랜치: `feature/ftctl-cloud-integration`

상위 문서:

- [500-cross-hypervisor-dr-architecture-plan-20260630.md](500-cross-hypervisor-dr-architecture-plan-20260630.md)
- [506-cross-hypervisor-dr-cloud-ui-design-20260630.md](506-cross-hypervisor-dr-cloud-ui-design-20260630.md)
- [508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md](508-cross-hypervisor-dr-cloud-backend-wiring-design-20260630.md)
- [511-cross-hypervisor-dr-implementation-progress-20260630.md](511-cross-hypervisor-dr-implementation-progress-20260630.md)

## 1. 목적

이 문서는 Cross Hypervisor DR 1-10단계 구현 결과를 배포 가능한 단위로 정리한다.

범위는 배포 준비와 smoke 검증까지다. 운영 클러스터 배포, DB 적용, 서비스 재시작은 이 단계에서 수행하지 않는다.

## 2. Smoke 결과 요약

| 항목 | 결과 | 비고 |
| --- | --- | --- |
| Windows 작업트리 `git diff --check` | PASS | docs/db/DR plugin/UI 범위 |
| WSL ext4 Maven module test | PASS | `Tests run: 25, Failures: 0, Errors: 0, Skipped: 0` |
| WSL ext4 Maven package | PASS | `cloud-plugin-integrations-disaster-recovery` jar 생성 |
| WSL ext4 UI production build | PASS with warnings | 기존 asset size warning, build success |
| qemu/ftctl source 변경 필요 여부 | 필요 없음 | Cloud DR wrapper/UI/schema 변경 범위 |

검증 명령:

```bash
cd /home/ablecloud/work/dhslove/ablestack-cloud-cross-dr-step6-wt
mvn -pl plugins/integrations/disaster-recovery -am \
  -Dcheckstyle.skip -Drat.skip=true \
  -Dtest=V2kDrMigrationAdapterTest,VmwarePhase1TargetAdapterTest,DrPlanServiceImplTest,FtctlDrActionAdapterTest,DrRunExecutorImplTest \
  -DfailIfNoTests=false test

mvn -pl plugins/integrations/disaster-recovery -am \
  -Dcheckstyle.skip -Drat.skip=true \
  -DskipTests package
```

```bash
cd /home/ablecloud/work/dhslove/ablestack-cloud/ui
NODE_OPTIONS=--openssl-legacy-provider npm run build
```

## 3. Maven 산출물

Maven package 산출물:

```text
/home/ablecloud/work/dhslove/ablestack-cloud-cross-dr-step6-wt/plugins/integrations/disaster-recovery/target/cloud-plugin-integrations-disaster-recovery-4.22.0.0-SNAPSHOT.jar
```

검증 정보:

```text
size: 308K
sha256: d43593942a8d21b95d934f6cb6b6cafbbbfaf0cee901962b87bdb11c2e63b203
```

Jar marker:

```text
com/cloud/dr/DrConstants.class
com/cloud/dr/DrPlanServiceImpl.class
com/cloud/dr/adapter/ftctl/FtctlDrActionAdapter.class
com/cloud/dr/adapter/vmware/VmwarePhase1TargetAdapter.class
com/cloud/dr/adapter/v2k/V2kDrMigrationAdapter.class
META-INF/cloudstack/disaster-recovery/spring-disaster-recovery-context.xml
```

## 4. UI 산출물

UI build 산출물:

```text
/home/ablecloud/work/dhslove/ablestack-cloud/ui/dist
```

검증 정보:

```text
dist size: 54M
selected static file count: 483
```

확인 marker:

```text
dist/js/app.4c9d1c12.js
dist/js/chunk-1494c736.524666ae.js
dist/js/chunk-2f5f23f8.63e730b0.js
dist/js/chunk-ef581e72.a9baf83b.js
dist/css/app.26766d55.css
dist/locales/ko_KR.json: label.dr.plan.add / label.dr.plans / message.dr.confirm.failover
```

UI build warning:

- Vue CLI asset size warning이 발생했다.
- 기존 CloudStack UI bundle의 vendor/locales 크기 성격이며 build failure는 아니다.
- `DONE Build complete. The dist directory is ready to be deployed.`까지 확인했다.

## 5. 배포 대상 변경 단위

### 5.1 DB schema

DB 변경 파일:

```text
setup/db/create-schema.sql
engine/schema/src/main/resources/META-INF/db/schema-42200to42210.sql
engine/schema/src/main/resources/META-INF/db/schema-42210to42300.sql
engine/schema/src/main/resources/META-INF/db/schema-Europa-After.sql
```

신규 도메인 테이블:

```text
dr_site
dr_site_pair
dr_plan
dr_restore_point
dr_restore_point_artifact
dr_replica
dr_replica_disk
dr_run
dr_run_step
dr_event
```

### 5.2 Cloud backend

배포 대상 module:

```text
plugins/integrations/disaster-recovery
```

주요 반영 class/resource:

```text
com.cloud.dr.*
com.cloud.dr.dao.*
com.cloud.dr.adapter.*
com.cloud.dr.adapter.ftctl.*
com.cloud.dr.adapter.vmware.*
com.cloud.dr.adapter.v2k.*
com.cloud.dr.orchestrator.*
com.cloud.dr.response.*
org.apache.cloudstack.api.command.admin.dr.*
org.apache.cloudstack.api.response.dr.*
META-INF/cloudstack/disaster-recovery/spring-disaster-recovery-context.xml
```

배포 원칙:

- Cloud backend는 변경 Maven module 기준으로 빌드한다.
- Full Cloud build는 사용자가 명시 요청할 때만 GitHub Actions로 수행한다.
- 관리 서버 반영 시에는 운영 중인 `cloud-plugin-integrations-disaster-recovery*.jar` 위치를 먼저 확인한다.
- 변경 class만 반영해야 하는 경우, 빌드 jar에서 변경 class/resource를 추출해 대상 jar에 업데이트한다.
- DB schema 적용은 관리 서버 DB backup 이후 1회만 수행한다.

### 5.3 Cloud UI

배포 대상 static asset:

```text
ui/dist/index.html
ui/dist/css/
ui/dist/js/
ui/dist/locales/
ui/dist/config.json
ui/dist/color.less
ui/dist/img/
ui/dist/assets/
```

배포 원칙:

- active webapp 경로는 `/usr/share/cloudstack-management/webapp`이다.
- `/usr/share/cloudstack-management/webapp`를 통째로 삭제하거나 교체하지 않는다.
- `rsync --delete`를 webapp root에 직접 사용하지 않는다.
- `WEB-INF`와 `META-INF`가 있으면 반드시 보존한다.
- 정적 UI asset만 갱신한다.

### 5.4 qemu/ftctl

이번 1-10단계 구현에서 qemu/ftctl source 변경은 필요하지 않다.

이유:

- KVM-to-KVM FTCTL 보호 성공 경로는 Cloud adapter가 기존 FTCTL service를 위임 호출한다.
- V2K 연계는 기존 `import_vm_task` 상태를 Cloud DR model로 투영하는 wrapper다.
- qemu host script, ftctl package, mold-agent command contract는 새 변경이 없다.

따라서 이번 단계 배포 대상은 Cloud DB schema, Cloud backend jar/class/resource, Cloud UI static asset이다.

## 6. 배포 전 체크리스트

Cloud source:

```bash
git status --short
git diff --check -- docs/ftctl setup/db engine/schema plugins/integrations/disaster-recovery ui
```

Maven:

```bash
cd /home/ablecloud/work/dhslove/ablestack-cloud-cross-dr-step6-wt
mvn -pl plugins/integrations/disaster-recovery -am -Dcheckstyle.skip -Drat.skip=true -DskipTests package
sha256sum plugins/integrations/disaster-recovery/target/cloud-plugin-integrations-disaster-recovery-4.22.0.0-SNAPSHOT.jar
```

UI:

```bash
cd /home/ablecloud/work/dhslove/ablestack-cloud/ui
NODE_OPTIONS=--openssl-legacy-provider npm run build
```

관리 서버 사전 확인:

```bash
test -d /usr/share/cloudstack-management/webapp/WEB-INF
find /usr/share/cloudstack-management -name 'cloud-plugin-integrations-disaster-recovery*.jar' -print
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/client/
```

DB 사전 확인:

```sql
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'cloud' AND table_name LIKE 'dr\_%';
```

## 7. 배포 후 체크리스트

DB:

```sql
SHOW TABLES LIKE 'dr\_%';
SELECT table_name FROM information_schema.tables WHERE table_schema = 'cloud' AND table_name LIKE 'dr\_%' ORDER BY table_name;
```

Backend jar:

```bash
jar tf <active-disaster-recovery-jar> | grep -E 'DrPlanServiceImpl|V2kDrMigrationAdapter|spring-disaster-recovery-context'
```

Management service:

```bash
systemctl status cloudstack-management --no-pager
journalctl -u cloudstack-management -n 200 --no-pager
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/client/
```

UI active bundle:

```bash
test -d /usr/share/cloudstack-management/webapp/WEB-INF
grep -R -l -E 'fetchDrPlans|DrRunProgress|listDrSites|startDrSync' /usr/share/cloudstack-management/webapp/js /usr/share/cloudstack-management/webapp/css | head
grep -R -n -E 'label.dr.plans|label.dr.sites|message.dr.confirm.failover' /usr/share/cloudstack-management/webapp/locales/ko_KR.json /usr/share/cloudstack-management/webapp/locales/en.json
```

API smoke:

- `listDrSites`
- `listDrPlans`
- `listDrRuns`
- `listDrRunSteps`
- `listDrReplicas`
- `listDrEvents`

초기 데이터가 없으면 list API는 빈 목록을 정상 응답해야 한다.

## 8. Live 검증 우선순위

1. DB migration 후 management 기동 확인
2. `/client/` HTTP 200 확인
3. DR Sites 화면 접근
4. DR Plans 화면 접근
5. 기존 FTCTL VM 탭 접근
6. KVM-to-KVM 기존 FTCTL 보호 VM의 action surface 회귀 확인
7. V2K import task가 있는 환경에서 `V2K/V2K` plan binding 조회
8. `SYNC` dry-run 또는 read-only 상태 조회
9. active run lock/unsupported action 응답 확인
10. management log에 Spring bean load/API command registration 오류가 없는지 확인

## 9. Rollback 기준

즉시 rollback 조건:

- management service가 기동하지 않음
- `/client/`가 200을 반환하지 않음
- active webapp에서 `WEB-INF`가 사라짐
- 기존 FTCTL tab 또는 VM detail 화면이 로드되지 않음
- API command registration 충돌로 관리 서버 로그에 startup exception 발생

Rollback 자료:

- DB backup
- active disaster-recovery jar backup
- active webapp static asset backup
- UI deployment 전 `WEB-INF` 존재 확인 기록

## 10. 남은 운영 확인 항목

- 실제 10.10.32 클러스터 배포는 별도 요청 시 수행한다.
- 신규 DR API는 초기 skeleton/wrapper 범위이므로, 운영 테스트에서는 KVM-to-KVM 기존 FTCTL 보호 회귀와 V2K task projection을 분리해서 검증한다.
- V2K Phase2 시작은 아직 기존 `importUnmanagedInstanceForAblestackV2K split=phase2 taskaction=phase2` 경로를 사용한다.
- VMware target Phase 1은 vCenter VM 생성 전 skeleton/readiness record 범위다.
