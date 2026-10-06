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

# Epic #898 준비 검증 증거 — 2026-10-06

## 확인 범위

최신 upstream `73dbd8258f762e454f8b8fcb3b14436b5540117b`의 모듈을 WSL ext4에서
빌드하고 13번 관리 서버와 두 호스트에 변경 클래스·리소스를 반영했다.
설치된 RPM/원본 MANIFEST의 제품 버전·revision은 보존했다. 따라서 API의
`4.23.0.0-Mold.Europa-202610011115` 표시는 전체 RPM 갱신을 의미하지 않으며,
실제 반영 기준은 아래 SHA, JAR 해시와 `META-INF/ablestack-module-overlay.json`이다.

설치 commit `3db2d1e2e3676d6715c002136d0cdb0d72c7104a`는 최신 upstream과
공통 기반을 가진 별도 release branch commit이다(divergence `3 129`). 설치 측
고유 변경은 프로세스 fingerprint의 Java 11 구현 및 import/checkstyle 보완이며,
이번 빌드는 upstream 코드를 기준으로 한다. 원본 패키지 전체를 다른 빌드로 교체하지 않았다.

## 소스 및 이슈

- 기준선 WSL/Windows local `ablestack-europa`, origin, upstream: 동일 SHA `73dbd8258f762e454f8b8fcb3b14436b5540117b`.
- local base는 먼저 fast-forward한 뒤 Epic 브랜치를 생성했다.
- Epic `epic/898-sharedfs` 생성·origin push 완료. 기능 코드는 수정하지 않았다.
- Epic 하위 이슈 27개: #911 CLOSED, 26개 OPEN, #924 NEXT.
- 상세 순서/범위/게이트: [준비 계획](epic-898-cluster13-preparation-20261006.md).

## 빌드

```bash
mvn -B -pl server,plugins/storage/sharedfs/storagevm,plugins/hypervisors/kvm -am   -DskipTests -Dcheckstyle.skip=true -Drat.skip=true package
mvn -B -pl engine/storage/snapshot,plugins/integrations/kubernetes-service -am   -DskipTests -Dcheckstyle.skip=true -Drat.skip=true package
```

- 1차 reactor: 39 modules, BUILD SUCCESS, 4분 47초.
- 추가 reactor: 38 modules, BUILD SUCCESS, 3분 42초. 공통 모듈을 제외한 고유 Maven 프로젝트는 총 41개다.
- 빌드 Java 17 / Maven 3.6.3; 프로젝트 target 버전 유지.
- 설치 관리 서버와 두 호스트의 실제 Java는 모두 17.0.20.1이며 런타임을 변경하지 않았다.
- Checkstyle/RAT 전체 기준선 검사는 위 packaging 명령에서 제외했다.
- UI 최초 빌드는 기존 node_modules의 `vue-code-highlight` 누락으로 실패했다.
- 저장소 선언 버전 `0.7.8`을 npm registry의 공식 package tarball로 복원하고 재빌드했다.
  Node 14 npm install은 기존 alias dependency(`wrap-ansi-cjs`) 해석 오류로 실패하여,
  해당 선언 package와 하위 prism-es6 1.2.0 package를 복원하고, 두 tarball의 SHA-512가
  lockfile integrity와 일치함을 검증했다. 소스 package.json/lockfile을 변경하지 않았다.

## 집중 검증

| 검증 | 테스트 수 | 결과 |
|---|---:|---|
| `StorageServiceManagerImplTest` | 6 | Pass |
| `SharedFSServiceImplTest` | 36 | Pass |
| `LibvirtStorageServiceHostCommandWrapperTest` | 2 | Pass |
| `LibvirtStorageServiceRuntimeHostCommandWrapperTest` | 6 | Pass |
| `StorageVmSharedFSLifeCycleTest` | 11 | Pass |
| SystemVM runtime updater | 4 | Pass |
| SharedFS en/ko_KR locale contract | 3 | Pass |

기준선 백엔드 61개, SystemVM 4개, UI locale 3개(총 68개) 집중 검증 통과.
이후 UI 기준선 결함 #1269 수정 회귀를 포함한 UI 24개 통과(기존 locale 3개 포함),
중복을 제외한 테스트 총 89개 통과.
UI locale 검사에서는 저장소 전체 coverage 수집을 제외했다.
실행 명령:

```bash
mvn -B -pl server,plugins/hypervisors/kvm,plugins/storage/sharedfs/storagevm -am   -Dtest=StorageServiceManagerImplTest,SharedFSServiceImplTest,LibvirtStorageServiceHostCommandWrapperTest,LibvirtStorageServiceRuntimeHostCommandWrapperTest   -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false   -Dcheckstyle.skip=true -Drat.skip=true test
mvn -B -pl plugins/storage/sharedfs/storagevm -am -Dtest=StorageVmSharedFSLifeCycleTest   -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false   -Dcheckstyle.skip=true -Drat.skip=true test
python3 -m unittest discover -s systemvm/test -p 'TestStorageRuntimeUpdater.py' -v
# ui, Node 14.21.3
npm run i18n:report -- --collectCoverage=false
```

## 모듈 배포 및 백업

관리 서버 `.10`은 monolithic cloudstack JAR의 157개 클래스·리소스 엔트리,
호스트 `.1`/`.2`는 각각 API/Core/Engine API/Schema/KVM/Server JAR 6개의
151개 엔트리를 교체했다. KVM/source wrapper, snapshot strategy, Kubernetes helper와
server Spring 등록 리소스도 설치 commit 대비 source delta에 포함된다.
각 수정 JAR의 다른 엔트리는 원본과 byte 단위 일치를 확인했다.

### 10.10.13.10

- 백업: `/root/epic898-backup-20261006-194029`
- PID: `223117` → `803758`; 서비스 `active`.
- 적용 시각: `2026-10-06T19:45:29+09:00`.

| JAR | 반영 엔트리 | 적용 후 SHA-256 |
|---|---:|---|
| `cloudstack-4.23.0.0-Mold.Europa-202610011115.jar` | 157 | `2e4224e4aff11f413d58d17597106f6b2fc62d087c989db542991809c78a82e0` |

### 10.10.13.1

- 백업: `/root/epic898-backup-20261006-194123`
- PID: `213309` → `1947440`; 서비스 `active`.
- 적용 시각: `2026-10-06T19:46:29+09:00`.

| JAR | 반영 엔트리 | 적용 후 SHA-256 |
|---|---:|---|
| `cloud-api-4.23.0.0-Mold.Europa-202610011115.jar` | 33 | `c81dbb02381d3b818077757324c2ebdda615ef6bca5622e5f2e892ede7fcc2de` |
| `cloud-core-4.23.0.0-Mold.Europa-202610011115.jar` | 21 | `7f9dd1598234f1b87eb59dff0b050a4cb60cc1933700a40bbde348b83dedad3e` |
| `cloud-engine-api-4.23.0.0-Mold.Europa-202610011115.jar` | 1 | `7db9baf172a6e73e195f136a8f371211b939099c8840884fa635518fe22d5781` |
| `cloud-engine-schema-4.23.0.0-Mold.Europa-202610011115.jar` | 8 | `b0d456204dd2d18f53ad16dd57389a3503ec2b54e4f32f5efe63a08516061b0d` |
| `cloud-plugin-hypervisor-kvm-4.23.0.0-Mold.Europa-202610011115.jar` | 12 | `78f269fc258def73e176b69ca226c4c763ddd501d91724fb621d472578de8896` |
| `cloud-server-4.23.0.0-Mold.Europa-202610011115.jar` | 76 | `8863ffc7e593db2b378c1449b02956664ce652a929fa0f166e89a244d489cf42` |

### 10.10.13.2

- 백업: `/root/epic898-backup-20261006-194126`
- PID: `151946` → `1593336`; 서비스 `active`.
- 적용 시각: `2026-10-06T19:46:32+09:00`.

| JAR | 반영 엔트리 | 적용 후 SHA-256 |
|---|---:|---|
| `cloud-api-4.23.0.0-Mold.Europa-202610011115.jar` | 33 | `72ecae23ac831bc20dcda5c47e4b9c5551c262461025c64568bba3e623cc58f7` |
| `cloud-core-4.23.0.0-Mold.Europa-202610011115.jar` | 21 | `0d93fa606afe57a8d16d600850db14bd8c71a94f35e3907f5e7ff3fbec39fd58` |
| `cloud-engine-api-4.23.0.0-Mold.Europa-202610011115.jar` | 1 | `d4b8e28f9d2b1bf695d3f32c3277f6bd75af73876b2056bb5614ef1a8c005bdd` |
| `cloud-engine-schema-4.23.0.0-Mold.Europa-202610011115.jar` | 8 | `fa27751091af99e9d143e92a7855ffb963d4ce3fd29b5489cb4f507ca69e2cbf` |
| `cloud-plugin-hypervisor-kvm-4.23.0.0-Mold.Europa-202610011115.jar` | 12 | `6a7f2ae0e31c7869d5ed12e7a16be3ef07d51191b67221ca804253a35447ed02` |
| `cloud-server-4.23.0.0-Mold.Europa-202610011115.jar` | 76 | `18c3c325029dea5819ef672433ec2c0f6aa776a0af34ef6291cc49a8a304c370` |

## DB/API/Guest 확인

- `cloud.vm_process_profile` 생성 및 `cloud.ablestack_schema_migration`의
  `europa-4.23-s12-process-profile-v1=Complete` 확인.
- 원본 schema: `engine/schema/src/main/resources/META-INF/db/schema-europa-4.23-s12-process-profile.sql`.
- 신규 컬럼: vm_id BIGINT UNSIGNED, profile_id CHAR(36), version INT UNSIGNED,
  definition_hash CHAR(64), metadata_json TEXT, state VARCHAR(16),
  registered_by/approved_by/retired_by BIGINT UNSIGNED, created/updated DATETIME(3).
  복합 PK=(vm_id, profile_id, version); 기존 테이블 컬럼 변경 없음.
- CREATE TABLE IF NOT EXISTS만 추가되는 migration이며 기존 SharedFS 데이터를 변경하지 않는다.
- DB 4.23.0.0 Complete 및 기존 Storage Service 테이블 유지.
- 관리 서버 재시작 후 지정한 critical class linkage/bean/unknown-column/missing-table 오류 0건.
- `.1`/`.2` `mold-agent=active`, API `Up/Enabled`; 실행 중인 VM 유지.
- 기존 SharedFS `nfs-test`: Ready/Running, 동일 VM/volume UUID, 100 GiB 유지.
- `listStorageServiceInstances`, `listStorageServiceRuntimeBundles`,
  `registerStorageServiceRuntimeBundle`, `upgradeStorageServiceRuntime`: 정상 노출 유지.
- `listDeploymentStoragePools`, `listVirtualMachineProcessProfiles`,
  `manageVirtualMachineProcessProfile`, `restartVirtualMachineProcess`: 이전 미노출 → 최신 노출.
- 새 profile 조회 API는 현재 환경의 `VM process management is disabled` 정책으로 거절됨을 확인했다.
  별도 프로세스 관리 기능 설정은 변경하지 않았다.
- `getStorageServiceRuntimeUpgradeCapabilities` 실제 API → Agent → QGA → guest 응답:
  available=true, currentversion=bootstrap-699406316ca9de44, ABI/schema=1, entrypointsmanaged=true.
- Guest QGA guest-ping/guest-exec 정상; 10.1.1.9의 NFS 2049 listener 유지.
- storagectl/updater/boot-reconcile 세 파일 해시는 배포 전후 동일하며 최신 upstream 소스와 일치.
- `ablestack-storage-monitor=active`; 기본 이름의 nfs-ganesha/smbd 서비스는 기존에도 inactive였고,
  NFS listener readiness와 분리해 기록했다.

## UI 및 최종 확인

- UI production build: Pass.
- 최종 UI source commit: `4d21099f6f41ff3d8916d0756cdae0db1a19bb75`.
- 정적 파일 835개 SHA-256 일치.
- 이전 캐시 클라이언트를 위해 staged 이전 파일 5개와 기존 서버 정적 자산을 보존했다.
- UI 백업: `/root/epic898-ui-backup-20261006-201951`.
- `config.json`, `WEB-INF` 보존 및 관리 PID 유지 확인.
- served index SHA-256: `ee9e4e55943a384a6196cf7bc80226992a6b77218692abc3bc6b950269d1e302`.

## 착수 중 확인한 UI 기준선 결함 #1269

- 최신 upstream 원본 UI 배포에서 SharedFS 상세/NFS 초기 스피너가 끝나지 않았다.
- 서버 health/inventory/sessions 응답은 정상 완료했으며 기본 이름 서비스 inactive와는 무관하다.
  실제 Ganesha endpoint는 V4_ONLY 2049, processRunning/listening=true, status=active였다.
- fetchStorageServiceData의 request scope는 초기 VM ID/탭 정보로 변경된다.
  오래된 응답을 버린 뒤 current token에만 loading을 해제하여 이후 loading 가드가 재조회를 막았다.
- #1269를 기존 Epic 27개 기능과 별도로 등록했다. 수정 PR: https://github.com/ablecloud-team/ablestack-cloud/pull/1270.
- 수정은 `4d21099f6f41ff3d8916d0756cdae0db1a19bb75`의 Vue 6줄 변경 및 회귀 테스트다.
- 동일 범위 dedup은 listRefreshMixin이 유지하고 최신 generation만 로딩 상태를 소유한다.
  마지막 요청이 범위 변경으로 무효화되면 최신 범위로 재조회한다. unmount 후 재조회하지 않는다.
- 새 테스트를 upstream 원본에 적용하면 3 failed / 1 passed로 결함을 재현했다.
- 수정 코드의 초기 VM/탭/새 요청 소유권/unmount 4건과 기존 SharedFS·locale 검사를 포함해 24건 통과했다.
- 변경 파일 ESLint 및 diff --check 통과. #924 UI 착수 전 #1270 병합·local base 갱신이 필요하다.

### 13번 최종 UI smoke 결과

- 새 UI/source marker와 served index hash를 확인하고 실제 로그인·SharedFS 상세를 다시 열었다.
- 초기 로딩 스피너 종료: NFS listener 1개, nfs-test-nfs export Ready,
  10.1.1.9:2049, 100 GiB XFS backing volume과 정확한 /dev/sdb 매핑 표시.
- ACL·세션 목록 표시, 현재 세션 0개. SMB 빈 공유 상태를 조회한 후 NFS로 복귀해 데이터 유지 확인.
- 실제 기본 다크 테마에서 읽기 화면을 확인했다. 라이트 테마 및 4개 프로토콜 쓰기 작업은 실행하지 않았다.
- 수정 UI 로드 이후 새 브라우저 오류 0건. 기존 관리 서버 재시작 중 세션 오류는 이전 로그로 구분했다.

## 복구 경로

- 관리 서버: mold 중지 → 위 백업의 원본 management JAR 복원 → mold 시작 → API/host 재연결 확인.
- 호스트: 한 호스트씩 mold-agent 중지 → 해당 백업의 6개 원본 JAR 복원 → mold-agent 시작 → Up/Enabled 확인.
- UI: 별도 정적 UI 백업을 복원하되 config.json/WEB-INF와 서버 설정은 보존한다.
- 이번 additive DB migration의 신규 테이블을 자동 DROP하지 않는다. 원본 코드와 공존 가능한 추가 테이블이다.
- 사용자 데이터 볼륨과 SharedFS VM은 그대로 유지한다. 위 복구는 문서화만 했으며 실제 롤백하지 않았다.

## 검증 한계 및 후속 착수

이번 결과는 구현 착수 기준선과 최신 모듈 배포 smoke 확인이다. Epic OPEN 26개를
구현하거나 완료 처리하지 않았다. NFS/SMB/iSCSI/NVMe-oF 전체 사용자 I/O,
10 TiB XFS/ext4 format, 장애 주입, ROOT 세대 교체는 실행하지 않았다.
전체 repository CI/RAT/lint 또는 새 RPM·SystemVM template release 검증을 대신하지 않는다.

다음 구현은 #924: Root Admin 전용 카탈로그, 서명·무결성 검증,
수명주기/사용 대상/감사, 승인·호환 번들 소비와 UI/API/DB 테스트 게이트다.

원본 build/test 로그, source delta, overlay manifest, 배포 전후 API/Guest/DB 및
각 서버 배포 JSON은 `/root/work/epic898-preparation`에 보관했다. 인증값은 저장하지 않았다.

## GitHub 전체 CI와 기준선 재현

PR #1270의 로컬 집중 검증과 실제 13번 UI는 통과했지만 전체 CI는 green이 아니다.
Build UI의 전체 Jest 결과는 87 suites/900 tests passed, 4 suites/6 tests failed이다.
새 SharedFSInitialLoading, 기존 SharedFSTab 및 locale suite는 CI에서도 PASS로 확인했다.

- 실패 suite: vmNicActions, vmDiskDeployment, GuestNetworkSummary, autoAlertDiscovery.
- 원본 upstream과 소스가 동일한 Epic 준비 브랜치(문서만 추가)에서 같은 4개 suite를
  Node 14로 실행하여 동일한 6 failed / 27 passed를 재현했다.
- vmDiskDeployment는 Node 14의 Array.at 미지원, 나머지는 기존 기대값/비동기 mock 실패다.
- 이 4개 실패 테스트 경로와 #1269 변경 파일의 교집합은 없다.
- Build UI 로그: https://github.com/ablecloud-team/ablestack-cloud/actions/runs/37454776220
- pre-commit은 기존 라이선스·깨진 symlink·EOF/line ending·오탈자·Markdown 문제로 실패했다.
- SharedFSTab에도 기존 license/codespell 지적이 있다. 해당 라이선스 포함 prefix는 원본과 동일하고,
  browseable 문자열은 원본/수정 모두 17개로 유지된다. 이번 6줄 로딩 변경에서 새로 도입한 항목이 아니다.
- pre-commit 로그: https://github.com/ablecloud-team/ablestack-cloud/actions/runs/37454776317

전체 CI 기준선 정비는 기존 독립 이슈 #926과 구분해 처리한다. 미완료 CI를 green으로 표시하거나
실패를 우회해 PR을 강제 병합하지 않았다. #1270은 Ready for review/미병합,
준비 문서 PR #1271은 Draft이며, #924 착수 전 리뷰·병합 및 local base 재동기화를 진행한다.
