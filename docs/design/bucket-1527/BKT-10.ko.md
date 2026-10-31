# [Europa][BKT-10][P2] Glue 객체 상세 메타데이터를 표준 HEAD 경로로 조회

상위 Epic: #1527. 상태: 해결 설계, 미구현. 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 문제·원인

UI 업로드 때 지정한 qa-note 메타데이터는 S3 HEAD에 존재하지만 객체 상세 UI에는 나오지 않았다. 현재 목록은 MinIO 확장 `listObjectsV2WithMetadata`의 metadata=true에 의존하고 Glue의 표준 목록 응답에는 per-object metadata가 없다. 단순 브라우저 HEAD 변경만으로 해결하려면 CORS 노출 헤더 제약도 확인해야 한다.

## 해결 설계

1. 제안 API `getBucketObjectMetadata(id, objectkey, versionid?)`를 추가한다. Cloud가 버킷 소유자/프로젝트 ACL을 확인하고 BKT-04 resolver로 표준 S3 HEAD를 실행해 필요한 필드만 반환한다. 브라우저에는 이 조회를 위한 추가 관리자 키를 제공하지 않는다.
2. 응답은 key, sizebytes, lastmodified, etag, contenttype, metadata map, versionid로 정의한다. 임의 S3 endpoint/HTTP header를 입력받지 않고 Cloud에 등록된 버킷/저장소로만 접근한다. Authorization/key/관리자 응답 헤더는 반환하지 않는다.
3. objectkey는 S3 key 그대로 UTF-8 왕복하며 한 번만 인코딩한다. 파일시스템 경로처럼 슬래시/공백/한글을 임의 축약하지 않는다. 폴더 prefix는 metadata 대상으로 요청하지 않고 버전 ID는 선택한 이력과 함께 전달한다.
4. 객체 상세를 열 때만 조회한다. 목록의 모든 행에 N개 HEAD를 실행하지 않는다. 캐시는 account/project/bucket/key/version 범위로 짧게 유지하고 해당 객체 변경 시 무효화한다.
5. 상세 로딩·진짜 빈 metadata·404 삭제됨·403 권한 거부·일시 오류를 구분한다. 다른 객체를 선택한 뒤 도착한 이전 응답은 덮어쓰지 않는다. metadata 문자열은 텍스트로 렌더링하고 HTML로 실행하지 않는다.

## 대상·의존 관계

ObjectStoreBrowser.vue 상세 drawer, 신규 command/response/service, provider metadata 계약과 Glue HEAD 구현. BKT-04 credential resolver와 BKT-13 요청 범위 관리가 선행이다. BKT-03의 버전 선택 UI에는 optional versionid 계약으로 연결하고 BKT-05 비밀 값 정책을 지킨다.

## 완료 기준

- [ ] 실제 S3 HEAD와 UI의 사용자 metadata·Content-Type·size·ETag 일치.
- [ ] 한글/공백/특수문자 key, 버전 객체, 0바이트, metadata 없음 검증.
- [ ] 빠른 객체 선택 전환, 404/403/timeout, 다른 계정의 접근 거부 검증.
- [ ] 목록 조회 수가 객체 수에 비례한 HEAD 호출로 폭증하지 않음.
- [ ] 31/32 Glue와 기존 제공자의 미지원/호환 경로 확인.

근거: Epic BKT-10, `head-object-32.json`과 UI 관찰. 새 API명/필드는 구현을 위한 제안 계약이며 이미 존재하는 기능이라는 의미가 아니다. 전체 Cloud 빌드는 별도 요청에 따른다.


<!-- source-links -->
## 소스 근거 링크

- [ObjectStoreBrowser.vue](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/components/view/ObjectStoreBrowser.vue)
- [ObjectStoreDriver.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/engine/storage/src/main/java/org/apache/cloudstack/storage/object/ObjectStoreDriver.java)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: [BKT-04 #1529](https://github.com/ablecloud-team/ablestack-cloud/issues/1529), [BKT-13 #1528](https://github.com/ablecloud-team/ablestack-cloud/issues/1528)
- 인터페이스 조율·통합 검증: [BKT-03 #1532](https://github.com/ablecloud-team/ablestack-cloud/issues/1532), [BKT-05 #1533](https://github.com/ablecloud-team/ablestack-cloud/issues/1533).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.
