# #969 코드 수준 설계 — 유지보수 후 자동 복제 복원

## 기준과 범위
Cloud 373ac7474e / qemu 0aa089c13f의 #968 누적 변경을 기반으로 각각 codex/fix-969-worker-auto-recovery를 생성했다. 정상 RUNNING 복제의 원본/대상 worker 중단·복귀를 자동 회복한다. PAUSED와 전환/해제 의도를 유지하며 planned/disaster failover·failback 동작은 변경하지 않는다. 레거시 DR Cluster는 활성화하지 않는다.

## 확인한 제어 흐름
- DrSchedulerRecoveryScheduler가 30초 주기로 source health와 recoverSync eligibility를 검사한다. 현재 idempotency key는 Plan UUID+authoritySequence로 고정되어 있다.
- DrOrchestratorImpl.createRun은 동일 key의 종료 Run도 그대로 반환한다. 같은 authority에서 첫 복구가 실패하면 다음 tick이 신규 복구를 생성하지 못한다. 별도 유지보수 장애도 동일 세대이면 기존 완료 요청에 묶일 수 있다.
- qemu scheduler는 rc98 source 단절, rc100 target export 불가에 backoff하며 같은 cycle을 재시도한다. target export reconcile은 durable intent/profile/generation으로 재시작한다. 이 경로는 재사용한다.
- 다만 로컬 scheduler reconcile은 READY/SYNCING 또는 NBD quarantine만 허용한다. source runtime 부재/일시 offline busy가 ERROR로 종료되면 control RUNNING이어도 로컬 자동 재기동이 누락된다.

## Cloud 변경
DrSchedulerRecoveryScheduler:
1. 멱등 key를 Plan UUID+authoritySequence+마지막 종료 Run ID로 구성한다. 동일 관측 상태의 중복 호출은 같은 key, 실패/완료 이후 재시도는 새 key가 된다. active Run과 eligibility 검사는 계속 유지한다.
2. 자동 복구 RECOVER_SYNC 종료 후 최소 60초 간격을 적용한다. DB completed 시각을 사용하므로 관리 서버 재시작에도 backoff가 유지된다.
3. 최신 PAUSE/RELEASE/FAILOVER/FAILBACK/REPROTECT/TEST_FAILOVER 및 취소 의도가 있으면 자동 controller가 이전 복제를 재개하지 않는다. runtime desired/control projection과 계획 active side/admin/state도 재검증한다. TEST_CLEANUP 복귀는 기존 #964 전용 journal을 따른다.
4. DR_EXPORT_OWNERSHIP_PENDING은 기존 transport 오류와 함께 자동 재시도 가능 오류로 분류한다. 정상 복귀 후 ACK를 다시 받아 진행하며 fencing 가드를 제거하지 않는다.
5. 최종 mutation 직전의 최신 Run 변화도 검사하고, 실제 dispatch의 기존 authority 검증을 유지한다.

## qemu 변경
- 로컬 reconcile에 명시적으로 retryable인 ERROR 중 DR_QCOW2_SOURCE_RUNTIME_UNAVAILABLE/DR_QCOW2_OFFLINE_SOURCE_BUSY만 추가한다. 모든 ERROR를 재기동하지 않는다.
- durable control stop/PAUSED, TARGET authority, transition 진행 중, desired STOPPED 가드를 보존한다.
- status에 다음 로컬 복구 시각을 저장해 반복 timer의 launch 폭주를 막는다. 정상 기존 READY/SYNCING recovery 경로와 release tombstone 계약은 유지한다.
- 기존 원격 endpoint 재시도 및 target export durable intent 복원은 유지한다. 실환경에서 추가 결함이 확인되면 코드 근거와 수정 설계를 갱신한다.

## 검증
- Cloud: 종료 Run 후 새 key, 같은 snapshot 중복, backoff 경계, active Run/PAUSE/전환/해제/cancel 억제, ownership pending 재시도 단위 시험.
- qemu: transient ERROR 복귀, 재시도 간격, 비재시도 오류/PAUSED/STOPPED/TARGET/transition 억제. release tombstone 및 기존 action contract 회귀.
- Cloud WSL ext4 변경 모듈 빌드, qemu Actions artifact 빌드. 설치 파일 hash 및 서비스/WEB-INF/HTTP200 확인.
- UI: RBD32 및 qcow2 31 정상 Plan을 기준으로 source worker, target worker, 양쪽의 복제 관련 실행/통신을 여러 retry 주기 동안 중단 후 복원. 복원 후 수동 Resume/Full Sync/DB 수리 없이 신규 durable checkpoint와 변경 데이터 전송 증거를 확인한다.
- Agent/프로세스 중단·네트워크 단절과 실제 전원 중단은 구분한다. 업무 VM 전체를 멈추는 장애는 기존 inventory와 영향을 확인한 후에만 수행하며 미수행 시험을 PASS로 표시하지 않는다.
- 기존 test/cleanup 및 PAUSED 유지 UI 회귀, source-unreachable disaster routing/권한 및 역방향 action contract 회귀를 수행한다. 전체 물리 페일오버/페일백 시험과 자동 회귀 결과를 구분해 보고한다.

## 후속 범위 경계
#969의 첫 구현은 정상 호스트 복귀 후 무인 수렴에 집중한다. 영구 폐기 호스트 fencing 증거 UI나 전체 ownership journal 재설계는 구현하지 않은 채 완료했다고 주장하지 않는다. #970/#971의 별도 기능 확장은 선행 조건이 아니며 기존 동작의 회귀 가드를 유지한다.
## 설계 보완 — Agent 단절 오류 분류

추가 코드 확인: FtctlDrUnifiedActionAdapter는 AgentUnavailableException을 DR_AGENT_UNAVAILABLE, dispatch timeout을 DR_AGENT_DISPATCH_TIMEOUT으로 반환하고 remote transport 준비 실패는 retryable DR_ENGINE_UNAVAILABLE로 반환한다. 정상 유지보수 중 이런 오류로 복구가 FAILED가 된 뒤에도 다시 시도할 수 있도록 DrSchedulerRecoveryScheduler의 허용 오류에 세 코드를 추가한다. 기존 CBT/재시드 실행/사용자 PAUSE·전환 의도 가드는 앞에서 우선 적용한다. 오류별 회귀 시험을 추가하며 변경 DR 모듈 전체를 다시 검증한다.