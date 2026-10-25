# #1487 스냅샷 Zone·이벤트·코멘트 탭 구현·검증

2026-10-25 · Epic #1473 · `dhslove/epic-1473-storage-snapshots` · 기준 커밋 `662454e38a0991f976f0a2f4a9d6b6ff057d3101`.

Zone의 사본 표와 이벤트·코멘트를 VM 공통 탭 구조로 정리했다. 동일 소유 범위의 정상 데이터는 갱신 실패 시 보존하고, 접근 거부·대상/역할/프로젝트/세션 변경에는 이전 데이터·선택·초안을 제거한다. 서버 응답의 건수·페이지 길이·중복 ID·대상 혼합을 검증하고 늦은 응답을 무시한다. Java/API/DB 스키마는 변경하지 않았다.

## 최종 구현

| 영역 | 결과 |
|---|---|
| 공통 기준 | VM의 ResourceView 탭, DetailTab 도구 모음·페이지, ListView 및 AnnotationsTab을 재사용한다. 읽기/변경 오류와 재시도를 분리한다. |
| Zone | 선택 Zone 전체 삭제를 첫 텍스트 버튼으로, 업데이트를 작업 영역 끝으로 배치한다. 열 선택과 오른쪽 현재 페이지 Zone·스토리지 검색을 제공한다. |
| 사본/페이지 | 서버 페이지 단위는 사본이며 Zone은 현재 페이지 집계다. 검색으로 일치한 Zone의 모든 사본을 유지하고 전체 사본 건수는 바꾸지 않는다. 필터/페이지/범위 전환 시 선택을 비운다. |
| 표시 | BackedUp·MANUAL·알려진 다운로드 상태와 SNAPSHOT.EXTRACT/COPY/ON_PRIMARY/OFF_PRIMARY 이벤트를 한글로 표시한다. 0B와 미확인을 구분하고 서버 감사 설명은 원문을 유지한다. |
| 이벤트 | 업데이트·열 선택·오른쪽 서버 검색, 10/20/50/100/200개 페이지와 이동 입력을 제공한다. 열 너비와 내부 가로 스크롤로 좁은 상세 영역의 한 글자 줄바꿈을 방지한다. 정렬은 현재 이벤트 페이지의 공통 ListView 계약이다. |
| 코멘트 | VM 공통 작성·공개 범위·작성자/시간·삭제 확인·10개 페이지를 유지한다. 변경 중 잠금, 실패 시 초안 보존, 성공 후 갱신 및 빈 마지막 페이지 복귀를 추가한다. 이전 대상의 제출 완료가 새 초안/목록을 변경하지 않는다. |
| 권한 | 읽기 전용은 작성/삭제/가시성 변경을 노출하지 않는다. 일반 사용자는 자기 코멘트만 삭제하고 관리자 전용 코멘트는 숨긴다. API 권한과 현재 행·작성자·관리자 여부를 다시 확인한다. |
| 대화상자 | 독립 작업 대화상자는 추가·변경하지 않았다. 코멘트 공개 범위 확인은 VM 코멘트 삭제와 같은 공통 AntD Popconfirm을 사용한다. 기존 VM 표준 SnapshotCopyDialog와 StorageSnapshotActionDialog를 호출하고 최신 계획·범위 해시·비동기 보호를 유지한다. StorPool 옵션은 노출하지 않는다. |

## 검증 결과와 증거

31 GFS2와 32 RBD에 각각 새 `SNAPTABS1487-*` 전용 도메인·3개 계정·프로젝트·역할, 추적 볼륨 6개, 스냅샷 7개, 정지 VM을 사용했다. 코멘트 33개와 스냅샷 이벤트 59개가 있는 초기 상태를 독립 SQL로 저장했다. 31은 스냅샷당 Secondary 사본 1개, 32는 Primary+Secondary 2개이며 ROOT 예외를 포함한다.

| 검증 | 31·32 결과 | 증거 |
|---|---|---|
| API ↔ 독립 DB/ACL | 각각 초기 42개와 UI 변경 후 42개 통과. 전체 페이지 중복/누락, 본문·공개 범위·이벤트·사본·서버 검색, 소유자/다른 계정/프로젝트/도메인 관리자 대조 | `evidence/*-tab-api-oracle.json`, `*-tab-api-after-ui.json`, `*-tab-db-*.json`, `*-db-*.json` |
| 실제 제한 역할 | 각각 5개 통과. 공개 코멘트·이벤트 조회, 작성/삭제/공개 변경 API 거부. 실제 UI에도 입력/변경 버튼 없음 | `evidence/*-tab-readonly.json`, `browser/*-readonly-*.json` |
| 페이지·검색·열 | 코멘트 26개/3페이지와 이벤트 30개/3페이지를 ID로 SQL 대조. 이벤트 검색/빈 결과/20개/열 선택, Zone 검색·전체 건수 유지·선택 초기화 | `browser/*-comments-page*.json`, `*-events-page*.json`, `*-events-pages-search-network.json`, `*-zones-filter*.json` |
| 갱신·오류·범위 전환 | 각 탭의 503/403/불완전 응답·재시도, 늦은 이전 대상 응답, 계정/프로젝트/도메인 관리자/제한 역할을 실제 UI로 검증. 최종 표 폭·키보드 수정 배포 후 읽기 오류 9조합씩 재검증 | `browser/*-503*.json`, `*-403*.json`, `*-incomplete*.json`, `*-late-ignored.json`, `*-final-regression-*.json` |
| 코멘트 실제 변경 | 마지막 6개 UI 삭제 후 26→20개, 3→2페이지 자동 복귀. 503 제출 실패 후 초안 유지·재시도 1회 생성·공개 범위 변경·삭제를 SQL 대조. 소유자 직접 생성/삭제, 이전 제출 완료 후 새 대상 초안 보존 | `evidence/*-ui-clamp-db.json`, `*-ui-created-db.json`, `*-ui-visibility-db.json`, `*-ui-deleted-db.json`, `browser/*comment*network.json` |
| Zone 실제 삭제 | owner-next 스냅샷의 Zone 전체 삭제 1회, 계획 조회 2회. 모든 job status=1/resultCode=0, DB Destroyed/활성 참조 0/백업 파일 부재. 같은 볼륨의 다른 스냅샷과 원본 볼륨 보존 | `evidence/*-ui-zone-jobs-db.json`, `*-ui-zone-deleted-db.json`, `browser/*-zone-delete-network.json` |
| VM 기준·테마·반응형 | 두 환경 × Zone/이벤트/코멘트 × 라이트/다크 × 390/768/1280px = 36개 최종 화면. 문서 가로 넘침·도구 모음 겹침 0, 표 내부 스크롤·긴 코멘트 줄바꿈·한글/다크 텍스트·키보드 확인. VM 공통 이벤트/코멘트도 비교 | `browser/final-layout-matrix.json`, `browser/*-final-*.png`, `browser/*-final-vm-*.json`, `browser/*-final-keyboard*.json` |

일반 사용자에게 보이는 이벤트는 대상 스냅샷의 모든 관리자 이벤트가 아니라 API의 이벤트 작성 계정/도메인·선택 프로젝트 범위다. 프로젝트는 프로젝트 계정 이벤트만 반환한다. 이 계약으로 독립 SQL 예상값을 계산했으며 관리자 조회 건수를 사용자에게 강제로 표시하지 않는다.

503/403/불완전 응답·지연은 브라우저 XHR 응답에 통제된 주입을 사용했다. 실제 서비스나 운영 DB를 손상시키지 않았다. 정상 생성/공개 변경/삭제 요청과 DB 종결은 별도 실제 호출이다. 31의 최초 코멘트 smoke 생성/삭제는 성공한 정상 호출로 기록하며 실패 주입 결과로 세지 않는다.

## 검사·배포·정리

- 최종 집중 테스트 **11묶음/149개**, 변경 파일 lint, UI production build 통과. 로그는 `logs/`에 보존한다. Vue 테스트용 컴포넌트 경고와 오래된 Browserslist 안내를 전체 Cloud CI 성공으로 해석하지 않는다.
- 최종 정적 자산 **922개**의 파일별 SHA256을 두 클러스터에서 검증했다. WEB-INF/META-INF/config·관리 JAR·대용량 파일을 보존하고 Mold active, `/client/` HTTP 200을 확인했다. 활성 webapp 전체 교체나 `rsync --delete`는 사용하지 않았다. `evidence/*-ui-deploy-final.json`, `asset-manifest.json` 참조.
- 전용 코멘트·태그·스냅샷·볼륨·VM·프로젝트·계정·도메인·역할을 정상 API로 정리했다. API/독립 DB의 활성 잔여와 비종결 사본, Secondary 파일 7개·Primary 볼륨 6개·RBD trash·libvirt 도메인 잔여가 0이다. 감사 이력과 tombstone은 유지한다. 수동 SQL 보정은 없다.
- 기존 운영 자원 8종의 핵심 속성을 보존했다. 체인 설정 false와 사용자 역할을 복원했다. Windows/WSL의 별도 dirty FT 작업은 HEAD·상태·diff 해시가 동일하다. `evidence/*-final-state.json`, `*-preservation.json`, `protected-*-verification.json` 참조.
- `verify_evidence.py`는 저장된 UI/SQL/API·페이지 ID·오류·권한·화면 크기와 PNG 헤더·배포·정리를 교차 검사한다. 결과는 `evidence-verification.json`, 변경 소스 13개 SHA256은 `source-manifest.json`에 보존한다.

## 검증 경계와 다음 작업

두 환경은 각각 단일 Zone이다. 11사본/2페이지와 별도 이벤트 유형의 양성 표시 사례는 응답 주입으로 검증했으며 실제 다중 Zone 복사 성공으로 계산하지 않는다. 이번 범위의 실제 복사 대상 조회는 빈 목록/제출 차단, 보관은 계획/가용성 확인과 취소다. 실제 추가 복사·보관 전송과 데이터 복구를 이 UI 정리에서 다시 수행했다고 보고하지 않는다. 해당 기능의 기존 #1481 증거와 #1489 통합 회귀를 구분한다.

전체 Cloud 빌드·Maven·GitHub Actions dispatch·qemu/FTCTL 빌드는 실행하지 않았다. 집중 검사/실환경 검증은 전체 CI 통과를 뜻하지 않는다. Epic #1473과 PR #1492는 열림/병합 대기로 유지한다.

다음 작업은 **#1488 대화상자 최종 정리**다. 생성·복원·템플릿·되돌리기·다운로드·복사·보관·단일/다중/Zone 삭제를 VM MoldDialog 기준으로 검증한다. **#1489 통합 회귀는 그 이후 마지막**에 수행한다.
