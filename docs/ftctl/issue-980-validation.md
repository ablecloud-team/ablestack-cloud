# #980 구현 및 검증 — 2026-09-11

## 변경과 브랜치
- 기준 Cloud `43330d38e3523249cb31955f7cfc8e7360148263` (#967 포함). 안전 브랜치 `codex/fix-980-cleanup-resume-idempotency`, 구현 `65d5f3798a`.
- qemu `fb6df9b998535fda9d68244c39be12c22e876cbd` 유지. 운영 shell 변경·패키지 빌드는 없다. 기존 같은 세대 START 재사용 계약을 사용한다.
- 정리 Run별 `dr_cleanup_export_resume`에 세대/관측 worker/디스크 fingerprint/drained를 영속화한다. 정상 회수 ACK 후 drained를 먼저 저장하고 START/RESUME 응답 유실 재시도는 같은 세대를 사용한다. 매 시도 live worker를 다시 해석한다. PAUSE/RELEASE/FAILOVER 및 TARGET/disabled/removed intent를 재확인한다.

## 모듈 검증
- WSL rocky ext4 Cloud clone에서 DR Maven package: **465 tests, 0 failures/errors, BUILD SUCCESS**. Schema module package: **390 tests, 0 failures/errors, BUILD SUCCESS**. 전체 Cloud 빌드는 실행하지 않았다.
- 수정 전 startForwardTargetExport에 임시 위임한 비교 시험은 11 tests 중 2개 실패(반복 STOP 및 START 응답 유실 단계 손실)를 재현했다. 이후 실제 구현으로 원복하여 위 최종 전체 DR 모듈 테스트를 통과했다. 중간 mock 설정 실패는 최종 PASS에 합산하지 않았다.
- 같은 cleanup의 반복 호출과 서비스 객체 재생성 시 1 STOP + 5 START, START 응답 유실 뒤 STOP 재전송 없음, 최신 PAUSE/TARGET authority 차단을 검증했다.
- 실제 MySQL 트랜잭션에서 동일 worker/fingerprint는 generation=2/drained=1 유지, worker 변경은 generation=102/drained=0으로 초기화됨을 확인하고 시험 행은 ROLLBACK했다.
- qemu lifecycle 71 PASS, checkpoint 13 PASS, release tombstone PASS. 실제 worker 이동/전원 장애를 이번 시험에서 새로 수행한 것으로 확대 주장하지 않는다.

## 배포
- 신규 테이블은 13/22/31/32 관리 서버에 멱등 선적용했다. 실제 복원 서비스가 있는 31/32에는 변경 클래스 5개(중첩 DTO 포함)를 기존 JAR에 적용했다. `/root/issue980-20260911/` 백업 보존, 변경 외 항목 보존 및 설치 클래스 SHA256 일치 확인.
- 13 원본 중계 서버는 Store 클래스 부재를 변경 전 preflight에서 감지했다. 13/22 원본 JAR는 변경하지 않았다. 전체 JAR로 강제 교체하지 않았으며 서버 역할 preflight 자동화는 #1033에 사례를 추가했다.
- 31/32 mold active, `/client/` HTTP 200, WEB-INF 보존 확인. UI 소스 변경이 없어 정적 번들 재배포는 하지 않았다.

## 실제 UI/응답 유실 검증
원본 호스트의 CLI 경계에서 특정 Plan의 `dr-sync-resume`만 실제 실행하여 exit=0을 확인한 다음 응답을 숨기고 비정상 종료를 반환했다. 네트워크 패킷 장애가 아니라 **처리 성공 후 응답 유실** 재현이다. 다른 명령/Plan은 원본 CLI에 그대로 위임했다. 30분 만료 및 명시적 복원 절차를 사용했고 시험 후 원본 CLI SHA256 `d71e266c17e73709af9203673b00ca703d7481d94b1df741709edb93eed8af5e` 일치를 확인했다.

| 경로 | UI 테스트/정리 Run UUID | 결과 |
|---|---|---|
| qcow2 13→31 | TEST `4d076416-8beb-4a78-9c9b-cd47c524526d`, CLEANUP `8ed739ee-29d8-495f-8e48-24a495d57fb4` | 둘 다 SUCCEEDED. cleanup Run604, 응답 유실 **10회**, 동일 RESUME UUID `bd65066f-2a38-38ef-9916-038b2d0084fc`, export **651** 유지. PENDING 상태에서 31 관리 서비스 stop/start 후 **RESTORED**, 총 12회 intent 시도. source scheduler PID707219 유지, checkpoint1937→1943. |
| RBD 22→32 | TEST `96f71d5f-f594-4a97-8964-d885106e0f35`, CLEANUP `497fa594-9c47-43af-89a5-3bd9d82c1c6c` | 둘 다 SUCCEEDED. cleanup Run504, 응답 유실 **4회**, 동일 RESUME UUID `6fe8947e-e1da-34f4-8889-74bcfb58bdee`, export **197** 유지. 응답 전달 복구 후 **RESTORED**, 총 18회 intent 시도(정리 완료 전 대기 포함). source scheduler PID3152656 유지, checkpoint4696→4699. |

두 UI 최종 상세 화면에서 SOURCE / TARGET_READY / WITHIN_RPO / LOCAL_DURABLE / RUNNING / HEALTHY / RESUMED 확인. 재동기화·수동 Resume·DB 상태 보정 없이 복귀했다. RBD 정리 자체는 14:50:28→14:52:38 완료했고 정리 전의 intent 대기를 응답 유실 횟수로 세지 않았다.

export PID는 매 checkpoint의 `dr_checkpoint.sh::ftctl_dr_checkpoint_publish_worker` idle flush에서 정상적으로 교체될 수 있다. 실제 journal과 해당 코드를 대조했으며 이를 재시도에 의한 authority 교체로 오판하지 않았다. 세대와 포트(qcow2 10814/10826, RBD10833)는 유지됐다. 생존 PID가 영원히 같다는 조건을 PASS 기준으로 삼지 않는다.

VMware는 양단 QGA 없이 기존 scheduler PID1476442, READY/HEALTHY, CBT_INCREMENTAL checkpoint264로 정상 진행함을 read-only 확인했다. 이번에 VMware 전체 failover/failback 체인을 다시 수행한 것은 아니다. 관리 재시작은 #980의 의도된 장애 주입이며 준비 실패를 수동 수리한 성공으로 합산하지 않았다.

## 증거 및 후속
로컬 증거 `/home/ablecloud/work/issue980-evidence`: dr-build-final.log, baseline-reproduction.log, schema-idempotence-smoke.log, installed-verification.json, qcow2-monitor.log, qcow2-after-restart-sample-0.json, rbd-monitor.log, rbd-sample-24.json. 인증정보 포함 가능성이 있는 원시 로그는 PR에 업로드하지 않는다.

\#980 구현·위 명시 범위 검증 완료, upstream 병합 대기. 다음 P1 #954, Diplo #1029는 별도 트랙. Nutanix 제외 개발/분석/잔여 보완 22건(Cloud21+qemu1), 구현/핵심 검증 및 병합·잔여 검증 트랙33건. 상세 우선순위는 Epic #950에서 관리한다. 기존 PR #1022 upstream 충돌은 별도 병합 준비 항목이며 이번 작업에서 병합하지 않았다.
