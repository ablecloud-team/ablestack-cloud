# #1482 스냅샷 전체 목록 서버 정렬 설계

- 작업 브랜치: `dhslove/epic-1473-storage-snapshots`, 시작 `613fe7e7def`.
- 범위: `listSnapshots`의 전체 결과 정렬·페이지 분할과 Mold 목록의 URL/화살표/요청 일치. 고급 검색 항목 추가는 #1483, 목록 배치 정리는 #1485, 통합 회귀는 #1489에서 수행한다.

## API 계약

`sortkey`: `created`(기본), `id`, `name`, `state`, `volumename`, `intervaltype`, `virtualsize`, `account`, `domain`, `project`만 허용한다. `sortorder`: `asc` 또는 `desc`(기본). 대소문자를 정규화하고 잘못된 값은 431로 거부한다. 임의 SQL 필드나 계산된 `physicalsize`/`chainsize`/`parentname`, 대표 사본마다 달라지는 `zonename`은 정렬하지 않는다.

기본 순서는 생성시각 내림차순으로 VM 스냅샷 목록과 일치시킨다. 정렬값 없는 기존 API는 응답 형식·기본 showunique=true·검색/ACL·count를 유지하며, 기본 순서만 새 계약으로 고정한다. 동률이면 내부 스냅샷 ID를 선택 방향과 동일하게 정렬한다. 사본 모드는 마지막에 사본 식별키를 오름차순으로 정렬한다. intervaltype은 번역 문자열 대신 저장된 enum 순서로 정렬한다. 문자열 비교와 NULL 순서는 DB collation 및 MySQL의 ASC NULL-first/DESC NULL-last를 따른다.

## 페이지 조회

ACL·프로젝트·도메인·소유자·키워드·태그·이름·볼륨·Zone·저장소·유형 조건은 기존 검색 빌더에 그대로 적용한다. 조건을 적용한 SQL에서 정렬한 다음 LIMIT/OFFSET을 적용한다.

- 논리 스냅샷 모드: ID와 스냅샷 공통 정렬 열로 그룹화하고 `MIN(snapshot_store_pair)`로 실제 조건에 부합하는 대표 사본을 결정한다. 태그 조인으로 생기는 중복을 페이지 전에 제거한다.
- 사본 모드: 사본 식별키와 정렬 열/ID의 DISTINCT 결과를 정렬한다.
- MySQL 8 `ONLY_FULL_GROUP_BY`/DISTINCT 규칙에 맞춰 정렬 열을 projection에 포함한다. 상속된 `id`의 테이블 alias를 명시한다.
- 상세·태그 보강은 선택된 사본 식별키로만 조회하고, SQL 페이지 순서의 rank를 복원한다. 전체 목록을 메모리에서 정렬하지 않는다. count는 같은 조건의 논리 ID 또는 사본 식별키 DISTINCT count다.

변경이 없는 데이터 집합에서 페이지 순서를 보장한다. 조회 사이의 생성·삭제를 고정하는 MVCC 목록 세션이나 cursor pagination은 이 범위에 포함되지 않는다. UI는 감소한 count로 현재 페이지가 범위를 벗어나면 마지막 유효 페이지로 이동한다.

## UI 계약

허용 열의 `sorter: true`는 서버 정렬 요청만 발생시키며 페이지 내부 비교 함수를 사용하지 않는다. URL의 `sortkey`/`sortorder`로 화살표를 제어한다. 기본 정렬·잘못된 URL을 정규화하고 검색/소유 범위를 보존한다. 정렬 변경·정렬 해제·페이지 크기 변경은 1페이지, 일반 페이지 이동은 기존 정렬을 유지한다. 검색 변경도 기존 1페이지 초기화를 유지한다. 늦은 응답·소유 컨텍스트 변경 방어는 공통 목록의 요청 버전/범위 검사를 사용한다.

대화상자는 추가하거나 변경하지 않는다. 기존 VM 표준 대화상자와 StorPool 옵션 비노출을 유지한다.

## 검증 계획

31 GFS2 / 32 RBD에 각각 별도 도메인·하위 도메인·일반 계정·DomainAdmin·프로젝트 및 실제 볼륨 4개/스냅샷 30개를 생성한다. 동일 이름 8개는 전용 fixture의 생성시각만 같게 조정해 동률을 확실히 만든다. 운영 자원의 시각/소유 정보는 변경하지 않는다. API 모든 지원 정렬 양방향, 논리/사본 모드, 다중 페이지·마지막 페이지·재조회, 검색과 ACL/count를 DB oracle과 대조한다. 실제 UI 정렬·페이지/검색·직접 볼륨 링크·잘못된 URL/범위와 요청·응답을 비교한다. 전용 자원은 정상 API로 정리하고 기존 운영 투영값 및 FT 작업 트리를 보존한다.

Maven은 WSL ext4에서 api/server 모듈만 검사한다. UI 집중 검사·lint·production build, 두 관리 서버의 변경 class와 정적 파일 배포/해시 검증을 수행한다. 전체 Cloud 빌드나 GitHub Actions는 실행하지 않는다.
