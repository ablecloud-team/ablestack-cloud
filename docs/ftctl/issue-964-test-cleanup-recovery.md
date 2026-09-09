## #964 코드 수준 설계

기준 Cloud `6c1044c400` (#960/#961/#965/#963 포함), 후속 브랜치 `codex/fix-964-test-cleanup-recovery`. 기존 수정 병합 없이 진행합니다.

### 확인한 원인

`FtctlDrUnifiedActionAdapter.prepareTestCheckpointBarrier()`는 source scheduler PAUSE 이후 `stopForwardTargetExport()`를 호출합니다. 반면 `resumeTestCheckpointProtection()`은 export 재생성과 새 endpoint 주입 없이 RESUME만 호출합니다. source 전송이 중지된 endpoint로 재접속하며 target-export rc=100 대기 후 일반 recovery controller에 의존합니다.

또 `execute()`는 TEST_ARTIFACT_CLEANUP의 `result.isSuccess()`만으로 재개합니다. 비동기 accepted도 성공이므로 실제 artifact/lease 정리와 scheduler 재개가 경합합니다. 원래 PAUSED였는지도 보존하지 않습니다.

### 순서와 소유권

1. Cloud checkpoint barrier 적용 대상(원격 ABLESTACK 경로 및 SharedMountPoint 테스트)에 대해 PAUSE 전에 원래 scheduler desired state를 영속화합니다. 재시도에서 덮어쓰지 않습니다.
2. 테스트 준비의 PAUSE → export STOP → immutable artifact 계약은 유지합니다.
3. cleanup 요청에는 기존 `schedulerTransitionScope=REMOTE_SOURCE` 계약을 사용해 엔진의 독자적인 재개를 억제합니다. 이는 Cloud가 상태 복원을 소유한다는 기존 프로토콜을 재사용하며 물리 source 위치를 고정하지 않습니다.
4. **accepted 응답에서는 재개하지 않습니다.** DrRunExecutorImpl은 cleanup dispatch 전에 복원 의도를 영속적으로 예약합니다. worker는 Cloud test VM/volume 제거와 해당 Run terminal 완료까지 실행을 보류합니다. 비동기 projection도 멱등 arm을 수행하여 즉시 성공 및 비동기 완료 경로를 함께 처리합니다.
5. 별도 bounded worker가 원래 RUNNING이면 target export를 멱등 생성하고 반환된 실제 endpoint를 profile에 주입한 뒤 scheduler RESUME를 실행합니다. PAUSED이면 재개/export writer를 시작하지 않습니다.
6. 자원 정리 성공과 source 복귀를 분리합니다. source Mold/Agent 단절 때문에 제거 완료된 test 자원 cleanup을 실패로 바꾸지 않으며, 복원 작업은 재시작 이후에도 재시도합니다. Full Seed를 요청하지 않습니다.

### 변경 파일/구성

- 신규 `DrTestCleanupRecoveryStore`: additive `dr_test_cleanup_recovery` 테이블. test Run PK, plan, 원래 desired state, cleanup Run, 단계, lease token/만료, next attempt, 마지막 오류를 보존. INSERT IGNORE 및 token 조건부 갱신으로 중복/재시작 보호.
- 신규 `DrTestCleanupRecoveryService`: bounded worker/주기 due scan. 살아 있는 테스트 자원, 새 진행 중 작업, TARGET authority/보호 해제, 이후의 사용자 PAUSE를 재확인합니다. 복원 중 새 테스트 생성은 차단하되 cleanup/재해 failover는 source 복원에 종속시키지 않습니다.
- `FtctlDrUnifiedActionAdapter`: 원래 의도 capture, 즉시 resume 제거, cleanup scope 전달, export→resume 복원 진입점. 파일/RBD endpoint는 기존 transport 서비스를 재사용하고 worker는 매번 live inventory에서 결정합니다.
- `FtctlDrRuntimeProjectionAdapter`: 해당 cleanup Run terminal 확인 후 복원 예약, 기존 CLEANED 처리 보존.
- `DrPlanServiceImpl`: 복원 중 새 Test Failover와의 writer 경합 차단.
- Spring bean 등록, 신규/Europa/4.23 schema에 멱등 테이블 추가.

qemu는 기존 export start/stop, endpoint manifest, REMOTE_SOURCE transition 계약을 그대로 사용하므로 현재 분석상 소스 변경이 필요하지 않습니다. source unreachable/restart에서 호출 계약 결함이 발견되면 별도 paired 변경으로 설계를 갱신합니다. VMware→RBD처럼 기존 Cloud barrier를 사용하지 않는 경로의 수명주기는 변경하지 않습니다.

과거 실행에 원래 의도 기록이 없는 경우 RUNNING으로 추정해 강제 재개하지 않고 PAUSED를 보존하는 호환 경로를 사용합니다. 운영자 명시 재개는 가능합니다.

### 검증 기준

- export 생성이 resume보다 먼저이며 endpoint가 resume profile에 반영됨.
- 비동기 접수/실패/다른 Run의 READY 상태로 복원하지 않음.
- 원래 PAUSED 유지, 현재 사용자 PAUSE/authority 전환 존중.
- export 또는 source 일시 불가 시 bounded retry, 프로세스 재시작/중복 lease 복구.
- WSL 변경 DR/schema 모듈 빌드와 기존 DR/QGA/serializer/레거시 차단 회귀.
- 13→31 qcow2 UI 테스트→정리→수동 Full Sync 없이 새 durable incremental/NO_CHANGE checkpoint 확인을 반복. UI PAUSED 계획의 테스트/정리 후 PAUSED 유지 확인.
- VM/volume/domain/NBD/DB/runtime 증거를 함께 확인. #967 Full Seed 조기 성공 문제와 혼동하지 않으며 full reseed를 우회책으로 사용하지 않음.


### 구현 검증 중 보완

첫 UI Run 273/cleanup 274는 immediate terminal 경로로 성공하여 projection-only 예약의 누락을 드러냈습니다. 이 시도는 PASS가 아닙니다. `DrRunExecutorImpl.armTestCleanupRecovery()`를 dispatch 이전에 추가했습니다. 예약 후에도 test session cleanupRequired 및 active Run gate가 재개를 막습니다. Run 275의 UI 동기화 재개로 다음 시험 기준 상태를 복원했습니다. DB 직접 수정은 하지 않았습니다.

### 빌드와 배포

- WSL ext4 후속 checkout, DR 변경 모듈 Maven package/test: 421 tests, failures 0, errors 0.
- schema 변경 모듈 빌드 성공; additive DDL 2회 적용 성공.
- management 변경 class/resource 20개 overlay; 변경 외 JAR entry 바이트 보존 검증.
- active JAR SHA256: `5a1e3a146fd9f9d3f1d028fcdf7ea7f889339f1bf940cfe19224eabc29afadc8`.
- 백업: `/root/issue964/backup` (배포 전), `/root/issue964/backup-v2` (첫 구현).
- mold 기동 및 /client/ HTTP200, WEB-INF 유지. 레거시 DR cluster API 미등록/직접 호출432, 새 DR Site/Plan API 정상 스모크 PASS.
- UI/Agent/qemu 소스 변경 없음. 기존 #963 배포 사용. Cloud 전체 빌드는 수행하지 않았습니다.


### PAUSED 및 관측성 설계 보완

- 기존 testFailover eligibility는 normalCutoverReady를 필수로 적용하여 PAUSED 계획의 체크포인트 테스트 자체를 막았습니다. targetReady/controlReady/source authority 및 capability 검증을 유지하면서 테스트만 `(normalCutoverReady || syncPaused)`를 사용합니다. 운영 failover 조건은 변경하지 않습니다. 회귀 테스트는 PAUSED에서 test 허용, planned failover 차단을 함께 검증합니다.
- `DrResponseGenerator`는 진행 중 Run이 없지만 durable 복원 작업이 PENDING인 경우 기존 API의 schedulerRecoveryState=PENDING, trigger=TEST_CLEANUP, readinessReasonCode=DR_TEST_PROTECTION_RESTORE_PENDING 및 자동 재시도 설명을 반환합니다. 기존 UI 준비 상태 사유 필드에서 확인할 수 있습니다. pending을 cleanup Run 실패로 바꾸지 않습니다.
- v2 RUNNING 재시험: test Run276/VM245, cleanup277 SUCCEEDED(00:45:58), store RESTORED(00:46:04), incremental1245 target durable(00:46:06). UI WITHIN_RPO/IDLE/RESUMED. 수동 Full Sync/DB 보정/추가 RECOVER_SYNC 없이 복귀.
- 31은 qcow2, 32는 RBD 실환경에서 아래 최종 검증을 완료했습니다.

### 최종 v5 검증 (2026-09-10)

원래 의도는 scheduler 프로세스의 RUNNING보다 plan/protection/replicationActivity의 PAUSED를 우선합니다. 프로세스가 살아 있어도 동기화 루프는 PAUSED일 수 있기 때문입니다. 이를 발견한 Run279/cleanup280은 실패 시도로 분류하고 수정 후 재시험했습니다.

| 환경 | 사전 상태 | 테스트 / 정리 Run | 최종 결과 |
| --- | --- | --- | --- |
| 31 qcow2 | PAUSED | 282 / 283 | PAUSED 유지, checkpoint1246 불변, export 미생성 |
| 31 qcow2 | RUNNING | 285 / 286 | 정리 01:06:52 → checkpoint1248 durable 01:07:03, 11초 자동 복귀 |
| 32 RBD | PAUSED | 396 / 397 | QGA_VALIDATED, PAUSED 유지, checkpoint4257 불변 |
| 32 RBD | RUNNING | 399 / 400 | 정리 01:08:28 → checkpoint4259 durable 01:08:43, 15초 자동 복귀 |

RUNNING 재시험 모두 cleanup 이후 수동 Full Sync, RECOVER_SYNC, DB 보정, 서비스 재시작 없이 incremental COMPLETED를 확인했습니다. UI는 READY/WITHIN_RPO/IDLE/RESUMED/LOCAL_DURABLE로 수렴했습니다. 테스트 VM/volume은 제거 상태이며 모든 대상 호스트에서 임시 domain 및 qcow2/RBD 이미지 부재를 확인했습니다.

- 최종 DR 모듈: 421 tests, failures/errors 0, BUILD SUCCESS (`dr-build-v5.log`).
- qemu release tombstone, full lifecycle 70 cases, remote RBD, SharedMountPoint qcow2, source outage smoke PASS. 최초 lifecycle 시간 제한 종료는 PASS에서 제외하고 충분한 제한으로 재실행했습니다.
- 32 management 누적 overlay 69개 entry: #960/#961/#965/#963/#964 포함. 변경 외 entry 보존 검증. SHA256 `d4be60c6fa714ac577a9682c24a69705da2e73378d792edb151d6db2855cc2be`.
- 32 Agent는 기존 #963 모듈, qemu는 기존 #961 Actions run34356201076의 commit292c35f84299ad382d6c26fcaaa57cf7baf18a6a 빌드를 배포했습니다. #964 qemu 소스 변경은 없습니다.
- RPM SHA256 `732123ea17f21073450a8efb41a2568f282d5198cdd29c2d9e098e1e9bd63b95`. 32는 aspkg 사용, 3개 호스트 agent 설정과 기존 VM inventory 보존.
- 32 UI는 기존 #963 정적 산출물을 배포하고 WEB-INF/META-INF/config를 보존했습니다. 양 관리 서버 mold active, /client HTTP200, 레거시 DR cluster 비활성 스모크 PASS.
- 백업은 각 관리 서버 `/root/issue964/backup`, 31 최종 직전은 `backup-v5`, 32 UI는 `ui-backup`, 32 Agent는 각 호스트 `backup-core`/`backup-kvm`입니다. 롤백 시 해당 계층 백업을 복원하고 재기동/상태 검증해야 합니다. additive 테이블은 삭제하지 않습니다.

### 별도 결함 및 검증 한계

32.3에서 8월31일부터 남아 있던 동일 대상 RBD의 writable export를 발견했습니다. 현재 32.1 export와 함께 LISTEN 상태이며 관측 시 연결이나 실제 동시 쓰기/손상은 확인되지 않았습니다. 이전 worker export 회수와 단일 쓰기 소유권 검증을 [#968](https://github.com/ablecloud-team/ablestack-cloud/issues/968) P1로 등록했습니다. 이 기존 결함은 이번 수정으로 해결됐다고 주장하지 않습니다.

실환경 PASS 범위는 위 qcow2/RBD 정상 및 PAUSED 흐름입니다. VMware, live migration, 장애 주입 및 management 재시작 중 복원 전체 조합의 실환경 PASS를 주장하지 않습니다. upstream 병합은 별도입니다.

증거는 `/home/ablecloud/work/issue964-evidence`의 build/deploy 로그, artifact-cleanup-31/32.txt, paused-final-source.txt, rbd-paused-db.txt, rbd-source-runtime.txt, stale-export-discovery.txt에 보존했습니다. 빌드는 WSL ext4에서 수행했습니다. 후반 WSL 명령 서비스가 응답하지 않아 최종 읽기 검증과 GitHub 기록은 Windows 도구 및 같은 ext4 checkout의 UNC 접근을 사용했습니다.