# #1533 명시적 자격 증명과 키 교체

기존 Epic #1527, PR #1541의 같은 브랜치에서 구현한다. 일반 BucketResponse에서는 key 필드를 제거하고 revision/검증 상태만 제공한다. 기존 setter는 바이너리 호환을 위해 값을 보관하지 않는 메서드로 남기며, 구 클라이언트는 명시적 POST 조회로 전환해야 한다. 기본 노출을 다시 켜는 플래그는 제공하지 않는다.

## 조회와 브라우저

`getBucketCredentials(id,reveal=false)`는 POST 전용이며 생성된 버킷의 기존 계정·프로젝트·관리자 ACL을 적용한다. 기본 응답은 마스킹 힌트, revision, 동일 계정/store 영향 버킷과 교체 상태다. reveal=true일 때만 현재 검증된 endpoint/key pair를 반환한다. 조회는 외부 키를 발급하거나 교체하지 않는다. no-store/Pragma, 감사 이벤트와 민감 필드 로그 마스킹을 적용한다.

ObjectStoreBrowser는 resource key에 의존하지 않는다. 해당 화면의 S3 세션이 POST로 키를 받고 SDK에만 보관한다. local/session storage, Vuex, URL에는 저장하지 않는다. 계정·프로젝트·버킷·로그아웃·관측한 revision 변경에서 참조와 SDK 키를 폐기하며 늦은 조회 응답도 버린다. 로그아웃 시작과 다른 탭의 Access-Token/CURRENT_PROJECT 변경도 메모리 registry를 통해 SDK·키 미리보기·관리자 입력을 즉시 폐기한다. 인증 실패는 안전한 설명과 명시적 재시도로 처리한다. 키 관리 대화상자는 기본 마스킹, 명시적 조회 후 30초 숨김, 범위 변경/닫힘 시 메모리 폐기를 적용한다.

## tenant 키 교체

`rotateBucketCredentials(id,expectedrevision,action=rotate|retry|retire,rotationid,graceperiod)`는 POST, 기존 ACL, revision CAS 의미와 같은 작업 UUID 재시도를 사용한다. 기본 유예 300초, 허용 5~86400초다. 저장소→계정/store 분산 잠금 순서로 직렬화하고 미완료 quota 작업 중 새 회전을 차단한다. 진행 중 키 교체가 있는 저장소의 endpoint 변경/삭제도 차단하여 복구 경로를 보존한다.

Prepared 전에 후보 access ID와 작업 UUID를 암호화 credential 행에 기록한다. Glue에는 후보 ID만 보내고 secret은 Glue가 생성한다. secret을 Admin Ops URL에 넣지 않는다. 응답 유실은 동일 ID를 사용자 키 목록에서 확인해 재개한다. 후보 secret을 암호화 저장하고 모든 대상 생성 버킷의 실제 S3 읽기를 검증한 후, 현재/이전 키·revision·bucket 호환 스냅샷·Grace 전환을 한 DB 트랜잭션으로 확정한다. 외부 통신 중 SQL 트랜잭션은 유지하지 않는다.

외부/검증/DB 실패는 현재 키를 유지하고 Prepared+RecoveryRequired로 남긴다. 재시도는 새 키를 중복 생성하지 않는다. Grace/Retiring 동안 현재 키가 사라져도 resolver가 임의의 다른 키로 바꾸지 않는다. 유예 후 현재 키와 S3 접근을 다시 확인하고 기록된 이전 키만 제거한다. 외부 삭제 후 DB 실패는 Retiring으로 재개하며 중복 폐기하지 않는다. 배경 worker가 유예 종료를 처리하고 수동 재시도도 같은 계약을 따른다.

같은 RGW 사용자를 공유하는 논리 store가 같은 이전 키를 참조하면 회전을 시작/폐기하지 않는다. 다른 store credential 및 legacy bucket 스냅샷까지 확인한다. 관계없는 외부 키는 삭제하지 않는다. 외부 S3 사용자는 영향 범위/유예 안내에 따라 직접 새 키로 전환한다.

## 관리자 키와 로그

updateObjectStoragePool의 POST details는 accesskey/secretkey를 함께 제공하거나 둘 다 생략한다. 빈 값·단독 값·미지원 provider/detail은 거부한다. Glue 후보 URL/키로 읽기 검증 후 URL/키/이름/용량을 짧은 DB 트랜잭션으로 저장한다. 검증/DB 실패는 기존 연결을 보존한다. 이 API는 Glue 관리자 키를 생성하거나 폐기하는 기능이 아니다.

등록도 POST/기본 마스킹으로 바꾸고 관리자 키 INFO 로그를 제거한다. 등록·수정·조회·회전의 요청 본문/쿼리는 관리 로그에 남기지 않으며 GET은 전역 POST 강제 설정과 무관하게 거부한다. 응답 audit serializer는 명시적 조회의 키를 마스킹한다. 정상 S3 인증의 access 식별자와 사용자가 만든 presigned URL의 서명은 S3 계약이며 비밀 키를 싣지 않는다.

## 배포와 UI

schema-42210to42300, Europa 보완 경로와 같은 버전용 migrate-bucket-credential-rotation.sql에 기존 credential 행의 암호화 후보/이전 키 및 단계/UUID/유예 필드를 추가한다. DDL은 JAR 전에 적용하며 반복 적용 가능하다. 일반 응답/브라우저 adapter를 함께 배포한다. 운영 확인 버킷의 키를 회전하지 않는다.

키 관리/관리자 교체는 공통 MoldDialog·Status·32px 버튼·테마 토큰과 반응형 폭을 사용한다. 영향 범위 확인과 유예 동의를 거쳐야 회전 버튼이 활성화된다. 실제 31/32 UI/API/DB/Glue 및 다크모드 결과는 검증 보고서에 기록한다.

근거: [Ceph Admin Ops Create/Remove Key](https://docs.ceph.com/en/latest/radosgw/adminops/#create-key), 로컬 THREAT_MODEL.md의 관리자·tenant 신뢰 경계. 새 권한을 부여하는 키 발급 기능이 아니라 기존 범위의 명시적 조회/교체다.
