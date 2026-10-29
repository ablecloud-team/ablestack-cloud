# SP-06 #1500 구현·실환경 검증

**판정: SP-06 완료 — 2026-10-27 KST.** 부모 Epic #1494는 계속 OPEN이다. 작업 브랜치는 `dhslove/epic-1494-snapshot-policies`, 최종 구현/빌드 소스는 `282fe03cd51`이다. 아래 결과는 이 변경의 집중 검사이며 전체 Cloud CI 또는 Epic 전체 완료가 아니다.

## 설계와 구현

[설계](../../design/snapshot-policy-1494/SP-06.ko.md)

- 일정·시간대·주기·active·display의 실제 변경만 예약을 조정한다. 같은 값/보관 수만 재제출하면 예약을 유지한다. API 갱신은 마지막 폴링 시각이 아닌 현재 시각을 사용한다.
- 정책 행 → 예약 행의 잠금 순서를 트랜잭션 안에서 통일했다. 폴러는 due 후보의 최신 정책·예약·예정 시각·작업 할당 여부를 다시 확인한다.
- 스냅샷 할당·내구성 큐 제출·job/snapshot 참조를 같은 트랜잭션에 기록한다. 오래된 완료 후보는 현재 job ID와 다르면 무시한다.
- 진행 중 작업은 변경/중지/재개/시작 시 참조를 유지한다. 완료 후 최신 정책으로 다음 미래 예약을 계산한다. 정책 삭제는 진행 중 작업이 있으면 431로 거부하고 완료 후 허용한다. 정책 삭제 FK의 예약 cascade를 고려한 계약이다.
- 실제 시험에서 기존 정책 편집도 볼륨 `Snapshotting` 상태 검증에 막히는 것을 발견했다. upsert 잠금 안에서 **기존 정책 편집만** 허용하도록 보완했다. display=true인 신규 정책 등록과 Destroy 상태의 편집 제한은 유지한다.

## 집중 검사와 빌드

WSL ext4 전용 작업 폴더에서 **server 모듈만** 빌드했다.

```text
mvn -B -ntp -pl server \
  -Dtest=SnapshotPolicySchedulingTest,SnapshotSchedulerImplTest,SnapshotManagerTest,SnapshotManagerImplTest \
  -Dcheckstyle.skip=true -Drat.skip=true package
```

**4 suites / 124 tests, failures 0, errors 0, skipped 0. server package SUCCESS.** [검사별 결과](evidence/unit-result.json)

새 예약 계약 검사 26개는 일정/TZ·동일 제출·보관 수 변경, 진행 중/알 수 없는/성공/실패/만료 job, 중지·재개·삭제, 오래된 due/완료 후보, 내구성 큐 제출, 갱신 실패 전파, 재시작의 미래/지연 예약, Snapshotting 상태의 편집/신규 등록 구분, 일·주·월·연도 경계와 DST gap/overlap을 포함한다. DST는 기존 Java Calendar의 gap 정규화와 overlap의 표준시 선택 계약을 검사했다.

Checkstyle/RAT는 위 명령에서 생략했다. UI 소스 변경/재빌드, 전체 reactor 빌드, Actions dispatch, qemu/FTCTL 빌드는 수행하지 않았다.

## 실제 API·UI 검증

31 GFS2·32 RBD마다 기존 시험 백업을 읽어 전용 Ready 1GiB DATADISK 2개씩 생성했다. 기존 VM/볼륨에 연결하거나 데이터를 쓰지 않았다. **실제 시간과 snapshot.poll.interval=300을 유지**했으며 수동 createSnapshot 또는 SQL 상태 변경으로 자연 실행을 대신하지 않았다.

| 확인 항목 | 31 | 32 |
|---|---|---|
| 실제 UI 정책 생성 | 정책 메뉴 / HTTP 200 | 볼륨 탭 / HTTP 200 |
| 일·주·월 일정/TZ 변경 | API·정책·예약·독립 zoneinfo 계산 일치 | 동일 |
| 동일 값 재제출 | 같은 정책 UUID·예약 ID·시각 유지 | 동일 |
| 동시 upsert 6건 | 같은 정책 UUID·예약 1개 | 동일 |
| 갱신/삭제 동시 요청 5쌍 | 각 쌍 이후 정책/예약 최대 1개, 정리 완료 | 동일 |
| pending 예약의 실제 관리 서비스 재시작 | 매시간·매월 2개 ID/시각 유지 | 매월 1개 ID/시각 유지 |
| 진행 중 변경·중지·재개·동일 표시 재제출 | job/snapshot/예약 시각 유지 | 동일 |
| 진행 중 실제 UI 삭제 | 431, 정책/작업 보존 | 동일 |
| 완료 후 실제 UI 삭제 | 200, 기존 스냅샷 3개 보존 | 동일 |

[31 API 매트릭스](evidence/31-matrix.json) · [32](evidence/32-matrix.json) · [동시 갱신/삭제 31](evidence/31-update-delete-race.json) / [32](evidence/32-update-delete-race.json) · [재시작 31](evidence/31-restart-check.json) / [32](evidence/32-restart-check.json)

진행 중 검증은 **시험 스냅샷 한 행만 SELECT FOR UPDATE**로 잠가 관찰 시간을 확보했다. 90초 watchdog을 두었고 양쪽 모두 명시적으로 해제했다. 스냅샷·job 상태값을 SQL로 수정하지 않았다. API와 실제 UI에서 삭제를 각각 거부했으며, 변경/중지/재개 전후의 job/snapshot ID와 원래 예정 시각이 동일했다. 두 작업은 잠금 해제 후 정상 완료됐다.

[진행 중 31](evidence/31-held-edit.json) / [32](evidence/32-held-edit.json) · [UI 거부 요청 31](evidence/31-running-delete-ui-network.json) / [32](evidence/32-running-delete-ui-network.json) · [완료 후 UI 삭제 31](evidence/31-finished-delete-ui-network.json) / [32](evidence/32-finished-delete-ui-network.json) · [정책 삭제 후 DB/API 31](evidence/31-policy-deleted.json) / [32](evidence/32-policy-deleted.json)

## 최종 소스의 변경 전후 자연 예약

| 환경 | 변경 전 작업의 스냅샷 / job UUID | 변경 후 작업의 스냅샷 / job UUID |
|---|---|---|
| 31 | `e226e3e3-bc8a-4960-81d8-dae2f39316ad` / `42b60deb-cc91-4c7e-8c31-76ea6a94ac52` | `d33d7601-90b1-4ef3-9676-112373f9cea7` / `e9fe0ffe-f368-49a2-b21e-48877e4bab75` |
| 32 | `21578090-bf25-40d2-bc00-d5e9fc2f511a` / `94e0bf18-7820-4568-900e-0857237d433e` | `92c780cc-ad1a-4651-9e04-a47619abbb46` / `f73aca0d-f96c-44e0-967f-4c2844e35b3a` |

31은 매시 17분, 32는 19분의 수락된 작업을 각각 **00:21:03 / 00:21:10 KST**에 관찰했다. 실행 중 정책을 매시 27분으로 바꿔도 진행 중 예약은 원래 17/19분과 job 참조를 유지했다. 완료 후 다음 폴링에서 양쪽 모두 **00:27:00 KST** 예약으로 계산하고 참조를 해제했다. 이후 자연 실행은 **00:31:03 / 00:31:10 KST**에 생성돼 **00:31:05 / 00:31:15**에 성공했다. 300초 폴링에 따른 지연이다.

최종 소스에서 환경별 2회가 모두 **BackedUp, jobstatus=1/resultcode=0**, 실제 보조 파일 존재와 일치한다. 앞선 진단 실행 00:10/00:12의 스냅샷은 최종 2회 판정에 포함하지 않았다. 각 환경에는 진단 포함 총 3개가 남아 있다가 정상 API로 정리됐다.

[자연 실행 판정](evidence/natural-result.json) · [완료 후 재계산 31](evidence/31-completion-reconciled.json) / [32](evidence/32-completion-reconciled.json) · [최종 API/DB 31](evidence/31-natural-final.json) / [32](evidence/32-natural-final.json) · [실물 백업 31](evidence/31-secondary-final.json) / [32](evidence/32-secondary-final.json)

## 가상머신 UI 표준과 화면 증거

UI/CSS/번역 및 공통 VM 컴포넌트 변경은 **0개**다. 실제 컴퓨트 > 가상머신과 정책 메뉴를 비교했으며 현재 테마의 주 작업 버튼 **32px/14px/반경32px**, 업데이트 **24px/14px/반경24px**가 일치했다. 기존 공통 목록·폼·대화상자로 생성/갱신/실패/삭제를 확인했다. 편집·관리자 중지/재개 **신규 UI는 #1499**의 범위다.

다크 1280×720에서 실제 기능을 확인하고, 정리 후 양쪽 빈 목록의 390×844 DOM·원본 PNG와 문서 폭(380px)을 확인했다. 브라우저 viewport와 디코딩한 이미지 크기가 맞는 원본만 이 보고서에 포함했다. 이번 변경에서 전체 테마/역할/반응형 행렬을 다시 수행했다고 주장하지 않는다.

[VM 규격](evidence/31-vm-standard.json) / [정책 규격](evidence/31-policy-standard.json) · [이미지 형식·크기](evidence/image-dimensions.json)

- [31 자연 실행 화면](evidence/31-natural-final.jpg)에는 앞서 의도적으로 검증한 431 알림도 남아 있다. 새 스냅샷 실패를 의미하지 않는다. 정리 후 이 시험 알림을 닫았다.
- [32 자연 실행 화면](evidence/32-natural-final.jpg), [31 실행 중 삭제 거부](evidence/31-running-delete.jpg), [32 거부](evidence/32-running-delete.jpg)
- [정리 후 31](evidence/31-cleanup-ui.jpg) / [32](evidence/32-cleanup-ui.jpg), [390px 31](evidence/31-cleanup-mobile.png) / [32](evidence/32-cleanup-mobile.png)

## 배포·정리·보존

기존 단일 관리 fat JAR의 원본을 `/root/snapshot-policy-1500-20261026/management-before.jar`에 보관하고 변경 클래스 계열 **5개**만 적용했다. 최종 보완 배포의 별도 백업은 `rev-282fe03cd51/management-before.jar`에 있다. 모듈 JAR로 관리 fat JAR 전체를 대체하지 않았다.

기존 두 클래스와 기준 소스 빌드는 상수 풀 번호를 정규화한 javap 메서드 바이트코드가 양쪽 모두 같았다. 변경 계열 외 JAR **204,535 / 204,529 항목** 해시, 기존 webapp의 20MiB 미만 파일 **15,935 / 8,311개** 해시와 설정 **7개씩**, WEB-INF를 대조했다. 최종 mold active, `/client/` 200 및 로그인/API가 정상이다. 최종 배포 이후 확인한 클래스 로딩/비트랜잭션 commit 경고/예약 제출 실패 표식은 0이다.

[배포 클래스 manifest](evidence/patch-manifest.json) · [기준 호환성](evidence/runtime-baseline-compatibility.json) · [런타임 31](evidence/31-runtime.json) / [32](evidence/32-runtime.json) · [예약 로그 31](evidence/31-runtime-log.json) / [32](evidence/32-runtime-log.json)

환경별 볼륨 2개와 스냅샷 3개, 남은 시험 정책을 정상 API로 정리했다. 독립 DB에서 **활성 볼륨/정책/예약/스냅샷/비종결 사본/태그/대기 job 모두 0**이다. 볼륨 Expunged·스냅샷 Destroyed 이력은 유지한다. GFS2 파일, RBD 이미지 및 trash, 보조 백업 파일 잔여도 0이다. 이번에는 스냅샷 태그 명시 정리 API가 필요하지 않았다.

기존 자원은 31 볼륨 **155→155**, VM **116→116**, 스냅샷 **23→23**; 32 볼륨 **51→51**, VM **51→51**, 스냅샷 **7→7**이다. 선택한 ID/이름/상태·크기/경로/연결/스토리지·VM 호스트 속성도 일치한다. Windows/WSL의 별도 FTCTL 작업 HEAD·브랜치·상태·tracked diff(3/11개)를 보존했다.

[정리 DB 31](evidence/31-cleanup-independent.json) / [32](evidence/32-cleanup-independent.json) · [기존 자원](evidence/preservation-result.json) · [Windows 보호](evidence/protected-windows-result.json) / [WSL](evidence/protected-wsl-result.json) · [기본 스토리지 31](evidence/31-primary-after.json) / [32](evidence/32-primary-after.json) · [보조 스토리지 31](evidence/31-secondary-after.json) / [32](evidence/32-secondary-after.json)

## 검증 경계와 다음 작업

실환경은 관리자·단일 관리 노드·단일 Zone·분리된 DATADISK이다. 진행 중 프로세스 강제 종료/다중 관리 노드 장애, 실환경 DST 시계 변경, 다중 Zone 복사와 전체 역할/프로젝트 행렬은 미검증이다. 실패/만료 job 재예약과 DB 갱신 실패 전파는 단위 검사로 확인했다. 다음 예약을 UI에 새로 표시하는 조회 계약은 #1504, 편집 UI는 #1499 범위다.

**다음 권장: #1502(SP-08)** 입력 범위·정수·보관 상한의 API/UI 계약을 정리한다. 그러면 #1498/#1500/#1502 선행 조건이 충족돼 **#1499(SP-05)** 편집·관리자 중지/재개 UI를 구현할 수 있다. **#1501(SP-07, P1)** 삭제 확인/대상 안전성도 독립적으로 우선 진행할 수 있다. #1497과 #1503~#1507 등 나머지 하위 이슈는 OPEN이며 최종 통합 판정은 #1507에서 수행한다. Europa 병합은 수행하지 않았다.
