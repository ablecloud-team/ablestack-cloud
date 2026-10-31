# #1529 구현과 배포 계약

Glue 버킷을 생성하면서 계정 상세 전체를 교체하던 경로를 제거했다. IAM origin과 사용자 설정은 그대로 두고, 자격 증명을 계정·Object Store 조합으로 관리한다. 작업 브랜치는 `dhslove/epic-1527-buckets`이며 기존 Epic PR #1541에 누적한다.

## 저장과 검증

- `object_store_account_credential`에 `(account_id, object_store_id)` unique 제약, 외래 키, RGW user id, 양쪽 키의 `@Encrypt`, revision, state, checked_at과 provisioning_source를 둔다. 날짜는 실제 GenericDao의 PreparedStatement에 바인딩되도록 `@Temporal(TIMESTAMP)`를 사용한다.
- 분산 pair lock 안에서 권한 있는 RGW 사용자 조회를 수행한다. 404/실제 미존재만 신규 생성으로 이어지며, 읽기 경로는 사용자를 만들지 않는다. 403·timeout·suspended·사용자 불일치·사용 가능한 키 없음은 실패다.
- 검증된 현재 행, 같은 저장소 버킷의 호환 키, legacy 계정 키 순서로 실제 RGW 키와 일치하는 값을 선택한다. 기존 값이 일치하지 않으면 실제 사용자의 완전한 키를 채택한다. 계정 상세에 새 키를 쓰거나 기존 사용자 키를 회전하지 않는다.
- 확인한 키와 같은 account/store 버킷의 호환 스냅샷을 한 트랜잭션에서 갱신한다. S3 정책·버전·브라우저 메타데이터가 같은 resolver를 사용한다. 기존 버킷 스냅샷은 호환용이며 권위 값으로 사용하지 않는다.
- RGW 생성 전후 출처를 기록한다. DB 저장 실패의 보상으로 외부 사용자를 삭제하지 않는다. 계정/저장소 정리는 Cloud 연결 행을 제거하며 외부 사용자 삭제를 의미하지 않는다.

## API와 UI

`BucketResponse`에 `credentialstate`, `credentialchecked`를 추가했다. Glue 검증 실패는 `NeedsVerification`과 키 없는 응답을 반환한다. Glue endpoint도 현재 Object Store URL에서 계산한다. 다른 제공자는 기존 키 계약을 유지한다.

브라우저는 미검증 상태에서 기존 키가 남아 있어도 SDK를 만들지 않는다. 실패 설명은 비밀 정보를 포함하지 않고, 재시도는 ObjectStoreBrowser → ResourceView → AutogenView를 거쳐 `fetchData({ irefresh: true })`로 새 버킷 메타데이터를 받는다. 상태가 Verified로 바뀌면 파일 목록을 다시 조회한다. 이 전체 부모 이벤트 연결을 컴파일한 실제 템플릿으로 회귀 검사한다.

상세의 검증 상태는 VM 메뉴의 공용 Status 배지와 번역 문자열을 사용한다. 확인 시각은 공용 날짜 표시를 따른다. #1528의 요청 수명·범위 격리와 #1540의 32px 버튼·4px 모서리·검색/반응형·테마 규격을 유지한다.

## 스키마와 같은 버전 반영

정규 업그레이드 SQL `schema-42210to42300.sql`과 기존 Europa security migration hook에 멱등 DDL을 포함했다. 현재와 같은 4.23 설치에 JAR만 교체하면 버전 전이 hook은 실행되지 않으므로, 반드시 **JAR 반영 전에** `setup/db/migrate-object-store-account-credentials.sql`을 관리 서버의 Cloud DB에 적용한다. `CREATE TABLE IF NOT EXISTS`만 수행하고 기존 계정 상세를 추정 복원하거나 일괄 변환하지 않는다. 실제 Glue 조회를 통과한 관계만 사용 시 조정한다.

새 설치용 DDL, 기존 행을 가진 재실행, unique/FK·계정/저장소 cascade를 별도 실제 MySQL 스키마에서 검사했다. 전체 Mold 신규 설치나 전체 Cloud 빌드를 실행했다는 의미는 아니다.

## 실행본과 복구

변경 Maven 모듈 6개만 WSL ext4에서 빌드한다. 설치본은 flat management uber JAR이므로 실제 기준 JAR에 변경 class/중첩 class 및 추가 schema/bean 자원 40개만 반영한다. 나머지 204,552개 entry payload 해시를 보존했는지 검사한다. 별도 server module JAR로 전체 실행 JAR을 대체하지 않는다.

JAR·설정·관련 DB 테이블을 먼저 백업한다. 주 JAR만 원자 교체하고 기동·HTTP 200·로그인·새 테이블·기존 버킷 Verified를 확인한다. 준비 검사 실패는 원본 JAR로 자동 복구하며 추가 테이블은 보존한다. 시범 반영에서 날짜 바인딩 누락을 찾아 수정했고 실제 DAO 바인딩 검사 2건을 추가했다.

UI는 허용된 정적 파일만 배포하고 WEB-INF/META-INF·설정·관리 JAR·PID를 보존한다. 32번 임시 파일은 /nfs를 사용한다. 기존 FTCTL 작업본과 qemu/호스트 스크립트는 변경하지 않는다.

## 이번 구현의 경계

일반 목록/상세의 키 전달 제거·마스킹·명시적 회전 API는 #1533에서 처리한다. 현재 브라우저와의 정상 상태 키 응답 호환성을 유지한다. 암호화·객체 잠금, quota 원장, 삭제/purge, 소유자 선택, 메타데이터 HEAD, 사용량·인프라 연결 계약은 해당 후속 이슈의 범위다.
