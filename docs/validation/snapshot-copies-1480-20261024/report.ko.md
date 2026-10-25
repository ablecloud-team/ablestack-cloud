# #1480 Zone·스토리지별 스냅샷 사본 정합성 검증

2026-10-24 · Epic #1473 · SS-07/P2
브랜치: `dhslove/epic-1473-storage-snapshots` · 시작 커밋: `7c8f100f347a586d2a496b6ffc4dc28d31aa5606`
최종 소스는 [source-sha256.json](source-sha256.json), 원본 증거는 [manifest.json](manifest.json), 재현 설계는 [design.ko.md](design.ko.md)에 연결했다.

## 결과

#1480 구현 범위의 API·UI·DB·실물 검증을 31(GFS2)·32(RBD)에서 완료했다. 같은 Zone의 사본 누락, Primary 필터, 태그 검색의 count/행 불일치, Zone 링크, 정보 누락 표시와 페이지·선택·보안 범위 처리를 수정했다. 변경한 복사 대화상자는 가상머신 공통 대화상자 표준을 적용했다.

| 검증 | 31 GFS2 | 32 RBD |
| --- | --- | --- |
| 정상 생성 main 스냅샷 사본 | Secondary 1 | Primary 1 + Secondary 1 |
| 정상 스냅샷 2개의 실물 | NFS 2개, 1GiB 및 앞뒤 패턴 일치 | NFS 2개 + RBD 스냅샷 2개, 1GiB 및 앞뒤 패턴 일치 |
| Primary storageid 필터 전후 | Image 1 잘못 반환 → 0 | 0 잘못 반환 → Primary 1 |
| 태그 검색 main 행/건수 | 1/1 | 변경 전 1/2 → 변경 후 2/2 |
| 통제 main 조회 항목 | 11개, 페이지 10+1 | 12개, 페이지 10+2 |
| page size 1·2·10·20 | API 전체 합집합/순서/ID 일치 | API 전체 합집합/순서/ID 일치 |
| 두 번째 Zone·저장소별 필터 | Zone2 2개, alias store별 1개 | Zone2 2개, alias store별 1개 |
| 정보 없는 다른 스냅샷 | Zone별 placeholder 2개, 서로 다른 ID, Unknown, 용량 null | 동일 |
| 3개 참조/파일 임시 제거·원복 | 11→8, 2페이지→1페이지, 원복11 | 12→9, 2페이지→1페이지, 원복12 |
| 정상 보관 후 main 사본 | dryrun으로 단일 Secondary 확인, 1 유지 | 실제 archiveSnapshot 2→1 |
| 전용 API/DB/실물 잔여 | 0 | 0 |

[31 상세 증거](31-validation.json), [32 상세 증거](32-validation.json), [UI 시험 기록](browser/ui-summary.json).

## 구현

- `showunique=true`는 논리 스냅샷 단위를 유지한다. `false`는 서버 사본 참조 행과 count를 같은 단위로 사용하고 안정된 `copy.id` 및 명시적 `copy` 상태 계약을 추가했다. 다른 Zone의 누락 참조도 서로 다른 식별자를 갖는다.
- Primary/Image 역할과 storageid/imagestoreid/locationtype 필터를 일치시키고, 상충하는 조합 4개/환경을 431로 거부했다. 태그 조인의 불필요한 snapshot 단위 GROUP BY를 제거해 사본을 유지했다.
- `copy.availability`는 Ready/NotReady/Unknown이며 Cloud 참조의 상태이다. 실시간 파일 검사 결과로 해석하지 않는다. 가상 용량은 캡처된 snapshot.size이며 누락 시 null이다. 사본별 물리 용량을 추정해서 표시하지 않는다. 다운로드 진행률은 IN_PROGRESS의 0..100 값만 표시하고 기존 UUID·진단 권한 규칙을 유지한다.
- Zone 부모 행 아래에 현재 서버 페이지의 스토리지 사본을 모두 표시한다. 건수·페이지 단위를 설명하고 Zone과 Primary/Secondary 스토리지 링크를 분리했다. 선택과 복사·삭제 영향은 Zone 전체(다른 페이지 포함)로 명시한다.
- 페이지 변경·스냅샷·프로젝트·세션·조회 권한 변경 때 이전 선택을 폐기한다. 같은 페이지의 일시 조회 실패는 기존 행·건수를 보존하고 재시도를 제공한다. 401/403/권한 오류에는 데이터를 비운다. 늦은 응답은 현재 범위를 덮어쓰지 않는다.
- bulk 취소 후 단건 삭제가 오래된 bulk 상태를 참조하던 부분을 수정했다. 두 환경 모두 Zone1 일괄 확인을 취소한 후 Zone2 단건 확인에서 Zone2 UUID만 표시됐다. 브라우저에서 삭제·복사를 제출하지 않았다.
- 복사 대화상자를 `MoldDialog size=md` + `MoldDialogActions`로 맞췄다. 중복 상세 정보를 추가하지 않고 공통 header/body/footer와 24px 폼 항목 간격을 적용했다. 복사 지원 조건과 대상 부재 안내·실제 전송 개선은 #1481 범위다.

## 브라우저·테마 검증

실제 Mold UI에서 API와 동일한 사본 ID의 행·페이지 합계를 대조했다. 정상 main 사본 수는 31=1, 32=2였고, 32 보관 완료 후에는 Secondary 한 행이 남았다. 통제 fixture는 main 11/12건과 누락 상태 두 Zone을 표시했다.

- 두 환경에서 목표 사본 XHR에만 503을 주입: 11/12행·건수 유지, 작업 비활성, 재시도로 복구.
- 목표 XHR에 403/error531 주입: 기존 행·건수·선택 0, 권한 안내, 정상 재조회로 복구. 실제 역할 권한을 변경한 시험은 아니며, 권한 철회 중 늦은 응답 차단은 UI 단위 검사로 추가 검증했다.
- main 응답을 지연시킨 뒤 other 스냅샷으로 전환: 두 환경 모두 other의 Unknown 2행 유지. 이후 도착한 main 사본은 표시되지 않았다.
- 실제 전용 프로젝트로 전환: 이전 범위 행 제거 및 프로젝트 대시보드 전환. 기본 보기로 복귀한 뒤 정상 재조회.
- 라이트·다크 1280×720, 좁은 459×488에서 복사 대화상자 검사. 추가 스토리지 선택 폼까지 표시했을 때 header/body/footer, 폼 항목이 겹치지 않고 화면 안에 들어갔다. 일반 텍스트의 실제 전경·배경 색을 DOM에서 저장하고 대비를 검사했다.
- 테스트 종료 후 원래 다크 테마를 복원하고 XHR 가로채기·임시 화면 크기를 해제했으며 전용 브라우저 탭을 닫았다.

화면 원본: [정상 Primary/Secondary](browser/32-normal-before-archive.jpg), [다중 사본 전체](browser/32-all-copies-wide.jpg), [좁은 다크 대화상자](browser/32-dialog-compact-dark.jpg). 모든 JPEG는 수정하지 않은 원본이며 [크기·해시 목록](browser-images.json)에 기록했다.

## 정상 실물과 통제 fixture의 구분

정상 제품 경로는 API로 만든 새 1GiB DATA 볼륨 2개/환경에 앞 1MiB 0x5a, 뒤 1MiB 0xa5를 기록한 뒤 생성한 스냅샷이다. qemu-img의 가상 크기, qemu-io 패턴, NFS 및 RBD 스냅샷 경로를 대조했다. 32 실제 보관에서는 DB Primary 참조 Destroyed와 실제 RBD 스냅샷 부재, Secondary SHA256 불변을 확인했다. 31은 Primary가 없는 dryrun 경계만 확인했다.

다중 저장소·Zone 및 페이지 경계는 전용 Disabled Zone과 readonly NFS alias store 10개/환경, 전용 snapshot의 참조를 구성한 **조회 계약용 통제 fixture**다. Ready alias 8개는 실제 전체 파일 복사·SHA256·qemu-img·패턴 검증을 동반한다. Copying 37%는 실제 1MiB 부분 파일, Failed는 파일 부재로 구성했다. Ready 실물 검사 31=10개/32=12개에는 정상 사본과 이 통제 사본이 함께 포함된다. 이를 제품 copySnapshot으로 실행한 다중 Zone 복사 성공으로 주장하지 않는다. 실제 전송·지원 조건 검증은 #1481에서 수행한다.

## 배포·정리·보존

WSL ext4 Epic clone에서 api·engine/schema·server 변경 모듈만 빌드했다. 관리 JAR의 5개 class와 SQL 리소스 1개만 패치했고 나머지 ZIP entry 보존을 검증했다. `snapshot_view`는 누락 참조 pair 표현식만 atomic CREATE OR REPLACE로 반영했다. MySQL의 문자셋 정규화 외 다른 열은 변경하지 않았으며 definer/security를 유지했다. 기존 환경 업그레이드에도 변경 view 반영이 필요하다. 원본 rollback SQL은 각 관리 서버 `/root/snapshot-copies-1480-view-backup.sql`에 보관했다.

정적 UI 921개 파일만 업데이트했고 `WEB-INF`·서버 파일·설정은 보존했다. 최종 class/SQL/static 해시 일치, Mold active, /client/ HTTP200, 각 환경 agent 5개 Up을 확인했다.

통제 참조·store를 제거하고 원본 fixture의 display와 용량을 복원한 뒤 정상 API로 스냅샷·볼륨·프로젝트·Zone을 정리했다. 전용 NFS alias mount 8개/환경만 해제했다. 전용 Primary/Secondary·RBD trash·메타데이터 잔여는 0이며 삭제 사용량 이벤트는 각 스냅샷 1회다. 계정의 기존 snapshot/secondary 사용량은 DB 계산과 일치했다. 운영 inventory는 안정 필드와 hash로 전후 대조했고 Windows·WSL의 dirty FT 작업트리 HEAD/status/diff hash가 동일하다.

## 검사와 제한

- Java 집중 검사 **33개 PASS**: 새 사본 계약 8, 기존 DAO 4, 작업 대상 9, 복원 계약 6, 할당 보호 6.
- UI 집중 검사 **5 suites / 69개 PASS**, lint PASS, production UI build PASS.
- 독립 offline 증거 검사는 `python3 scripts/verify.py`로 실행하며 [verification.json](verification.json)에 결과를 남긴다. 테스트와 원본 로그는 별도 파일로 연결했다.
- **전체 Cloud 빌드/전체 CI는 수행하지 않았다.** GitHub 등록 check/status/Actions 유무는 종료 코멘트에서 최종 commit 기준으로 별도 보고한다.
- 전체 탭 표준 정리 #1487, 나머지 대화상자 정리 #1488, 최종 통합 회귀 #1489는 남아 있다. #1489에 기록된 별도 삭제 경로·계정 일괄 정리 이력·RBD hot-attach 결함을 이 변경으로 해결했다고 처리하지 않는다.

## 다음 작업

**#1481: 스냅샷 보관·복사 작업의 지원 조건과 UI 진입·실패 안내 보완.** 선행 #1477·#1479·#1480 계약을 사용해 보관 적격성, 복사 가능 대상, 미지원·빈 목록·조회 실패의 구분과 실제 다른 Zone/스토리지 전송·부분 실패·재시도를 검증한다. 새로 만들거나 바꾸는 대화상자는 계속 VM 표준을 적용한다. 최종 통합 회귀 #1489는 관련 기능 구현 이후 수행한다.
