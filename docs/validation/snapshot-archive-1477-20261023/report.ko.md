# #1477 보관·강제삭제 구현 및 31·32 검증 결과

2026-10-23 · Epic #1473 · 브랜치 `dhslove/epic-1473-storage-snapshots` · 기존 PR #1492에 추가.

## 결과

#1477의 보관 적격성·멱등성·Secondary 중복 방지와 사용자 추가 요구인 **명시적 강제삭제**를 구현했다. 31(GFS2)·32(RBD)에 변경 모듈과 UI를 배포해 실제 API/UI/DB/NFS·RBD로 검증했다. 보관 전용 버튼/폼(#1481)과 Epic 전체의 VM UI 표준화는 이번 완료 범위에 포함하지 않는다.

- 기존 Ready Secondary를 검증해 재사용한다. Secondary-only 호출은 검증 후 무변경 성공하고 반복·동시 호출은 새 참조를 만들지 않는다.
- Primary-only RBD 백업 실패 시 원본을 보존하고 같은 Allocated 참조에서 재시도한다. 검증된 백업 없이 Primary를 제거하지 않는다.
- Primary 정리 실패 시 기존 Secondary를 유지하고 정리만 재시도한다. 보관·삭제는 같은 snapshot 잠금을 사용한다.
- `archiveSnapshot(dryrun=true)`의 eligible/reason/참조 개수/scopehash를 SS-08에 제공한다. 기존 일반 보관 성공 wire 응답은 유지한다.
- `deleteSnapshot(force=true)`는 root 관리자만 사용할 수 있다. force 진단·최신 scopehash·UI 영향 확인을 요구한다. 알려진 자식/볼륨 의존성과 생성/백업 진행 상태는 보호한다.
- 가능한 실물 삭제 후 누락 정보/실패 참조도 Destroyed/display=false로 종결한다. DB 완료와 실물 완료를 분리하고 미확인 대상은 비공개 감사/보류 이력에 남긴다. 장애 해제·주소 복구 후 같은 제품 경로로 실물을 재시도한다.
- 일반 삭제는 누락 경로/공유 볼륨 디렉터리를 저장소 삭제 명령으로 보내지 않고 `INCOMPLETE_STORE_REFERENCE`로 거부한다.

## 실환경 검사

| 검사 | 31 GFS2 | 32 RBD | 근거 |
|---|---|---|---|
| 기존 Secondary 재사용/무변경·반복·동시·오래된 hash 거부 | PASS | PASS | `*-archive-normal.json`, `*-after-normal-archive-*` |
| 정확한 Secondary 파일 부재·형제 파일 오인 방지 | PASS | PASS | `*-archive-verification-fail.json`; 32 Primary 보존 |
| Primary-only 백업 실패/동시 재시도 | 해당 없음 | PASS | Image Allocated **#76 → 같은 #76 Ready**, 새 중복 없음 |
| 백업 존재 상태에서 Primary 정리 EBUSY 실패/재시도 | 해당 없음 | PASS | `32-partial-archive-failure/retry.json`, native/clone 검사 |
| 기존 중복 Ready 참조의 보관 거부 | PASS | PASS | `*-archive-ambiguous.json`, 거부 전후 scope 동일 |
| 보관 완료 후 상세 화면의 일반 삭제 | PASS | PASS | `*-normal-deleted.*`, DB/NFS·RBD |
| 전역 Destroyed+중복 Destroying 참조의 일반 UI 복구 | PASS | PASS | `*-recovery-settled.*`, 재진단 잔여 0 |
| 일반 삭제 실물 실패 후 장애 해제·반복 재시도 | PASS | #1478 경로 유지 | `31-fault-failure/retry.json` |
| 경로 누락 시 일반 삭제의 무변경 거부 | PASS | PASS | `*-normal-missing-metadata.json` |
| 경로 누락 강제 UI 삭제 → DB 완료/실물 미확인 → 주소만 복구 → UI 실물 재시도 | PASS | PASS | `*-ui-force-jobs.json`, `*-force-db-only.*`, `*-force-complete.*` |
| 실제 실물 장애 강제삭제 → DB 종결/보류 1 → 장애 해제·실물 재시도 | PASS (immutable) | PASS (RBD child/보호) | `*-force-busy-force/retry/repeat.json` |
| 실물은 이미 없고 DB만 남은 경우 | PASS | PASS | `*-force-no-native-force/repeat.json` |
| 참조 매핑 자체가 없는 경우의 명시적 DB 정리 | PASS | PASS | physicalcleanupcomplete=false, 미확인 부재를 성공으로 표시하지 않음 |
| Secondary 파일만 삭제되고 Primary 실물이 남음 | 해당 없음 | PASS | `32-force-secondary-missing-*` |
| Secondary 파일+DB 참조가 삭제되고 Primary만 남음 | 해당 없음 | PASS | `32-force-primary-only-*`, native와 DB 종결 |
| 소유자 보관/반복·타 소유자 접근 거부 | PASS | PASS | `*-tenant-archive.json` |
| 일반 사용자의 force 진단/실행 명시적 거부 | PASS | PASS | `*-tenant-force-denied.json`, scope 무변경 |
| 원시 snapshot/secondary_storage 계수 | PASS | PASS | DAO와 같은 규칙으로 직접 계산, 계수 보정 API로 결과를 덮지 않음 |

보관 API는 기본 User 역할에 원래 허용돼 있지 않았다. 운영 역할을 바꾸지 않고 테스트 계정에만 User 역할을 복제하고 archiveSnapshot을 허용해 ACL을 검증했다. 테스트 역할과 계정은 API로 삭제했다. API 파서가 권한 제한 파라미터를 조용히 제외하는 동작도 실제 검사에서 확인해, force 값은 파서가 인식하고 백엔드가 root 권한을 거부하도록 보완했다.

## 강제삭제 사용 경로와 결과

스냅샷 목록 → **삭제 잔여 복구** → UUID 입력 → **강제삭제 (관리자 복구)** → 삭제 대상 진단 → 영향 확인 → **강제삭제 실행**.

31의 Secondary 경로 누락과 32의 Primary 경로 누락을 각각 구성했다. 최초 UI 실행은 DB 상태를 Destroyed로 만들고 물리 미확인/보류 1을 표시했다. DB 전후와 실물 검사에서 31 파일/32 native가 아직 존재하고 32의 식별 가능한 Image는 삭제된 것을 확인했다. lifecycle 상태를 되돌리지 않고 주소만 복구한 뒤 UI 재진단·실행으로 실물과 보류가 제거됐다. 이후 API 반복 실행도 physicalcleanupcomplete=true/pendingcleanup=0이었다.

완전히 매핑이 사라진 경우에는 DB를 종결하되 실물 부재를 증명할 수 없다는 역사적 `snapshot.force-delete.unmapped` 감사 기록을 남긴다. 위치를 알 수 없는 실물을 찾아서 삭제했다고 주장하지 않는다. 보류 참조 행까지 별도로 유실된 경우 자동 재구성/알 수 없는 스토리지 전체 탐색은 제공하지 않는다.

## 검사·배포

- WSL ext4 변경 Maven 모듈 빌드·집중 검사: **132 tests**, 실패/오류/skip **0**. API 1, server 72, snapshot engine 59. engine/api도 변경 모듈로 빌드했다.
- UI **34 tests / 2 suites**, lint 오류 0, production build 완료. 이번 Java 변경에 전체 Cloud 빌드/Actions를 사용하지 않았다.
- 관리 서버 변경 클래스 **26개**, static UI **917개**를 원본 백업 후 반영하고 모든 해시를 대조했다. JAR 나머지 **31: 204502 / 32: 204496 항목**의 CRC/크기를 보존했다. WEB-INF/config 보존, Mold active, `/client/` HTTP 200, 각 클러스터 Routing host 3개 Up.
- 최신 백엔드 백업: `/root/snapshot-archive-1477-backend-permissions-20261023`; UI 백업: `/root/snapshot-archive-1477-ui-final-20261023`. 최초 백업도 유지했다.
- 현재 JAR SHA256: 31 `c1785233b0279e39fddb8305a0822ac7a7a52ab750919a0677da76c54642b82d`, 32 `ada9bf45af256f9ace9790954b6b3fae09f21b0d29d0c646d60478840ddfa3ec`.
- 이번 작업에서 agent/qemu/FTCTL 프로그램은 변경하지 않았다. Windows·WSL의 별도 dirty FTCTL 작업 트리 HEAD/브랜치/status/diff 및 기준 파일 해시 보존을 확인했다.

## 최종 정리와 보존

31 **9 snapshots / 2 volumes / 1 account / 1 role**, 32 **11 snapshots / 2 volumes / 1 account / 1 role**를 전용 생성·검증한 뒤 API로 정리했다. SQL은 장애 fixture 구성과 경로 복구에만 사용했으며 제품 실행 결과를 SQL로 종결하지 않았다.

두 클러스터 모두 활성 시험 snapshot **0**, 미종결 store 참조 **0**, 보류 물리 정리 대상 **0**, 알려진 NFS 파일 **0**, GFS source 파일/RBD source·tenant 이미지·시험 clone **0**이다. 생성/복원용으로 잠시 격리했던 파일도 시험 범위만 확인 후 정리했다. 볼륨 DB는 Expunged+removed, 시험 계정/역할도 잔여 0이다. 숨김 unmapped 감사 기록은 각각 1개 보존하며 활성 자원/보류 실물과 구분한다.

기존 API 인벤토리의 주요 식별·상태·크기·참조 필드는 전후 동일했다: 31 snapshot **23→23**, volume **155→155**; 32 snapshot **12→12**, volume **48→48**. 별도 대조용 사본의 경로/참조/크기/체크섬은 정리 전까지 유지됐다.

## 별도 발견 결함과 검증 한계

보관·일반/강제 삭제·반복/재시도 단계의 usage 이벤트는 1회였다. 이후 **테스트 계정 삭제**의 기존 `deleteSnapshotDirsForAccount` 경로가 이미 Destroyed인 tenant snapshot에 SNAPSHOT.DELETE를 다시 발행했다(31·32 각각 1→2). 해당 경로는 `listSnapsforVolume`의 과거 snapshot에 직접 전략 삭제·계수 감소·usage 발행을 수행한다. #1477 보관/force 구현 범위와 분리해 **#1489의 미완료 통합 회귀 결함**으로 기록한다. 전체 계정 생명주기 사용량 멱등성을 통과했다고 주장하지 않는다.

실제 managed primary 장비는 두 클러스터에 없어 적격성/순서/미지원 거부를 단위 계약으로 검사했다. RBD Primary-only는 실물 검증했다. Secondary 검증은 정확한 파일 존재와 복사 완료 메타데이터 기준이며 전체 데이터 체크섬 검증이 아니다. 이번 빈 시험 볼륨 이외의 실제 데이터 패턴/부팅/Running VM/여러 Zone 생존 사본/모바일·라이트 테마 전체 회귀와 Epic UI 표준화는 #1489 및 해당 하위 이슈에 남아 있다. 전체 Cloud CI는 실행하지 않았다.

## 다음 작업

**#1479 (P1 SS-06)**을 먼저 수행한다. 작업 대상 고정·최신 상태/권한 검증·비동기 수락/완료/실패/미확인 및 재제출 보호를 공통 계약으로 적용한다. 이후 **#1480 → #1481** 순서로 사본 표시·집계 정합성과 보관/복사 UI를 연결한다. #1474는 독립 P1이며 병행 가능한 별도 작업이다. #1489의 계정 삭제 usage 중복은 미해결 상태로 유지한다.
