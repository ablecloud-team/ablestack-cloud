# #967 검증 기록 — 2026-09-11

## 변경과 재현
- 기준: Cloud 16b3e03b34 (#1034/#962 포함), 안전 브랜치 `codex/fix-967-full-reseed-completion`.
- 구현 커밋: `281f4a96a7`. 기존 PR #1022의 누적 변경을 그대로 계승했다.
- `DrTargetMaterializationServiceImpl.completeMaterialization`에서 대상 준비만으로 요청 FULL_RESEED Run을 성공 처리하는 우회 경로를 제거했다. 대상 준비 step/event만 기록하고 기존 `FtctlDrRuntimeProjectionAdapter`의 요청별 cycle 검증에 최종 완료를 맡긴다.
- 수정 전 동일 callback 테스트: expected RUNNING / actual SUCCEEDED로 실패. 수정 후 통과. 초기 일반 SYNC 완료와 이미 실패한 Run 보존도 검증했다.
- 대기/실행/실패/취소 상태, 완료 시각 없는 cycle, 무관한 소유자, CBT_INCREMENTAL/NO_CHANGE, commit 미완료의 성공 거부를 추가했다.

## 빌드 및 스모크
WSL rocky ext4 `/home/ablecloud/work/dhslove/ablestack-cloud-issue971`에서 변경 DR 모듈만 `mvn -o -B -pl plugins/integrations/disaster-recovery -Dcheckstyle.skip=true package` 실행.
- DR 461 tests, failures/errors/skipped 0. BUILD SUCCESS.
- qemu `ftctl_dr_full_lifecycle_smoke.sh`: 71 cases PASS.
- qemu release tombstone PASS, checkpoint Python 13 tests PASS.
- qemu 소스/스크립트, Agent, UI 변경 없음. qemu 패키지 빌드/배포 없이 기존 설치본 회귀 확인.

## 배포
관리 서버 10.10.13.10 / 22.10 / 31.10 / 32.10에 변경 클래스만 JAR overlay. 이전 JAR는 각 서버 `/root/issue967-20260911`에 보관. 모든 다른 JAR 엔트리 보존 검증.
- 설치 클래스 SHA256: `53cd04727cb20369127e94c8704bc454092d6fcb5e13cbd15a3e28c912181815`.
- 4개 서버 active / client HTTP 200 / WEB-INF 존재 / 클래스 해시 일치 PASS.
- 재기동 직후 active이지만 HTTP 포트가 아직 열리지 않은 기동 구간은 PASS로 처리하지 않고 실제 HTTP 준비 후 시험했다.
- 기존 SLF4J/log4j 경고는 #1027 범위이며 새 클래스 로드 실패는 관측하지 않았다.

## UI와 실제 데이터 경로 결과
시각은 KST. 조작은 Chrome Mold UI에서 수행하고 Cloud DB, source runtime, target COMMITTED proof를 별도로 대조했다. DB 직접 변경이나 VM/스토리지 수동 복원은 하지 않았다.

| 경로 | 요청 Run | 요청 시각 | checkpoint | 대상 영속화 | UI Run 성공 |
|---|---|---|---|---|---|
| qcow2 13→31 | 4f50427b-1afe-4953-9022-79606dd4f40e | 14:10:31 | 1931 FULL_SEED | 14:11:58 | 14:12:47 |
| RBD 22→32 | c304182c-4c43-40a0-9277-74d6d80fa985 | 14:12:35 | 4689 FULL_SEED | 14:14:26 | 14:14:59 |

- qcow2 전송 및 WAITING_CHECKPOINT 동안 이전 증분 1930이 존재해도 Run ACCEPTED/완료 시각 NULL을 유지했다. UI 71% 진행 상태, 테스트 페일오버 비활성, 현재 작업 취소 및 기존 DR 계획 삭제 활성 확인.
- RBD 전송 중 이전 증분 4688 및 worker SUCCEEDED 표시가 있어도 Run ACCEPTED/완료 시각 NULL, UI 35% 유지. 요청 cycle 4689 영속화 후만 성공했다.
- qcow2 target 31.1 두 디스크, RBD target 32.1 한 디스크의 COMMITTED proof가 각각 요청 UUID/sequence/전체 디스크와 일치했다. `manifestSha256`은 proof의 해당 필드를 제외한 canonical JSON digest로 재계산하여 일치했다.
- qcow2 proof digest: `6a336844b90306d22b83bde5df3cf8d8f477e869cecdab6bdfdddeae0ea132b9`.
- RBD proof digest: `473eb2b9eed566ca59dbf6b1f962e411a8618cba3092a629ce560af1077c331d`.
- Cloud canonical cycle sequence(qcow2 15099, RBD 10934)는 engine checkpoint 번호와 별도다. 정확한 accepted token 및 terminal control 요청으로 연결했다. qcow2 cycle 행의 producer는 기존 scheduler이므로 요청 UUID만으로 cycle 행을 조회하지 않고 accepted sequence/token과 target proof도 대조했다.
- VMware Plan a85874ae-d1bd-470b-97c5-7c48a39486dd: UI TARGET_READY/WITHIN_RPO/HEALTHY/IDLE, checkpoint 257 CBT_INCREMENTAL 확인. 원본·대상 모두 QGA 사용 없음.

## 범위와 잔여
실제 실패/취소/재시작의 모든 타이밍을 운영 VM에서 새로 장애 주입한 시험은 아니다. 조기 성공 원인 callback 재현과 실패/미완료 거부는 모듈 회귀로, 실제 요청·전송·대상 게시·UI 완료는 qcow2/RBD에서 검증했다. 기존 failover/failback/placement 계약 변경 없음. 전체 물리 장애·마이그레이션 재시험이나 전체 릴리즈 완료로 확대 해석하지 않는다.

로컬 상세 증거: `/home/ablecloud/work/issue967-evidence`의 baseline-reproduction.log, module-final-build.log, qemu-*.log, deploy-*.log, installed-verification.json, qcow2/rbd-monitor.log, *-runtime-*.json, *-target-commit.json, *-proof-verified.json.
