# [Europa][BKT-05][P1] 버킷·Object Store 자격 증명 노출 제거와 명시적 키 관리

상위 Epic: #1527. 상태: 구현·31/32 UI/API/DB/Glue 검증 완료, PR #1541 미병합. 최초 감사 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 문제와 원인

Glue 등록 폼은 Secret Key를 일반 입력으로 표시하고 getAPI로 URL에 싣는다. BucketResponse의 목록/상세/생성/동기화 응답에는 S3 key pair가 들어가며 상세는 그대로 표시한다. 등록 시 관리자 Access Key의 INFO 로그 기록도 확인했다. Secret Key 로그 기록이나 비인가 사용자에게 키가 노출됐다는 사실까지 확인한 것은 아니다.

ObjectStoreBrowser는 resource.accesskey/usersecretkey에 직접 의존하므로 backend의 목록 필드만 지우면 목록 기능까지 깨진다. 저장소 수정 API에는 관리자 키 교체 계약이 없다.

## 해결 설계

1. 등록은 POST 본문으로 전환하고 request/response의 민감 정보 표시, 오류·HTTP 추적·감사 로그 마스킹을 점검한다. Glue 폼은 password 입력, autocomplete off, 기본 마스킹을 사용한다. 관리자 키를 포함한 연결 로그를 제거한다.
2. 제안 API `getBucketCredentials(id)`는 POST, 기존 소유자/프로젝트/관리자 ACL, 감사 이벤트를 적용한다. 응답은 해당 버킷 작업에 필요한 endpoint/key pair만 반환하고 no-store로 취급한다. 이 API는 새 외부 키를 발급하거나 권한을 확대하는 기능이 아니다.
3. 공통 S3 client adapter가 명시적 키 조회를 수행하고 메모리에만 보관한다. 로그아웃·계정/프로젝트/버킷 전환·키 revision 변경 시 폐기한다. localStorage/sessionStorage/route/query에 저장하지 않는다. 인증 실패는 원인 안내와 한 번의 명시적 재시도 경로를 제공한다.
4. backend가 새 조회 경로를 제공 → UI/연결 클라이언트 전환 → 일반 BucketResponse와 상세 자동 노출 제거 순서로 배포한다. list뿐 아니라 createBucket job 결과·syncBucketUsage·오류·다운로드 정보도 점검한다. 구버전 연동은 명시적 호환 기간/opt-in으로 관리하고 새 배포의 기본값은 비노출이다.
5. 관리자 키 교체는 `updateObjectStoragePool`의 민감 POST details 계약을 확장해 처리한다. 둘 다 제공/둘 다 생략을 구분하고 빈 문자열로 지우지 않는다. 후보 키로 읽기 검증한 후 DB를 갱신하며 실패하면 기존 연결/키를 보존한다.
6. tenant S3 키 회전은 BKT-04의 account/store 범위로 관리한다. 같은 RGW 사용자를 공유하는 버킷 영향 목록·명시적 요청·신구 키 유예·새 키 검증·구 키 폐기 단계를 구분한다. UI의 키 조회가 rotation을 자동 실행하지 않는다. 감사에는 키 대신 대상 ID/revision/결과만 남긴다.

## 대상·경계·의존 관계

AddObjectStorage.vue, BucketResponse/ViewResponseHelper/ApiResponseHelper, 버킷 상세, ObjectStoreBrowser 및 S3 adapter, Glue lifecycle/driver, UpdateObjectStoragePoolCmd/StorageManagerImpl. BKT-04 credential resolver를 선행 사용한다. BKT-03 버전 브라우저와 BKT-10 metadata 조회는 목록 응답의 key 필드에 새 의존을 만들지 않는다. Object Store 연결 점검 UI는 BKT-11이 담당한다.

## 완료 기준

- [x] 일반 목록/상세/async 결과/동기화 응답과 URL·로그에 실제 시험용 비밀 값이 없음.
- [x] 키 미리보기 기본 마스킹, 명시적 권한 검사와 no-store, 컨텍스트 변경 시 메모리 폐기.
- [x] 전환 후 파일 목록·폴더·업로드·삭제·metadata·공유 URL이 회귀하지 않음.
- [x] 관리자 키 교체 실패 시 기존 연결 유지; tenant 회전 후 같은 계정/store의 모든 대상 버킷 검증.
- [x] 비소유자 거부와 키 폐기/유예 만료를 실제 Glue에서 확인하고 시험 키 정리.

근거: Epic BKT-05, key-log-check JSON, AddObjectStorage.vue의 GET 경로 및 BucketResponse 키 필드. Cloud 구현은 dhslove 작업본/변경 모듈 빌드 규칙을 따른다.


<!-- source-links -->
## 소스 근거 링크

- [AddObjectStorage.vue](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/views/infra/AddObjectStorage.vue)
- [BucketResponse.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/api/src/main/java/org/apache/cloudstack/api/response/BucketResponse.java)
- [ObjectStoreBrowser.vue](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/components/view/ObjectStoreBrowser.vue)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: [BKT-04 #1529](https://github.com/ablecloud-team/ablestack-cloud/issues/1529)
- 인터페이스 조율·통합 검증: [BKT-03 #1532](https://github.com/ablecloud-team/ablestack-cloud/issues/1532), [BKT-10 #1538](https://github.com/ablecloud-team/ablestack-cloud/issues/1538), [BKT-11 #1539](https://github.com/ablecloud-team/ablestack-cloud/issues/1539).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.

## 구현과 검증 완료

[최종 설계](1533-credential-management.ko.md)와 [31/32 검증 보고서](../../validation/bucket-1533-20261031/REPORT.ko.md)를 따른다. 일반 응답 키 노출 복구 opt-in은 제공하지 않으며 구 클라이언트는 명시적 POST 조회로 전환한다. metadata 업로드·S3 응답 보존은 검증했으며 기존 상세 표시 제약 #1538은 OPEN이다. 공유 URL은 사용자 요청으로 만든 제한된 S3 서명 링크이며 비밀 키를 포함하지 않는다. Epic은 5/13 완료, 다음 권장 작업은 #1532다.
