# [Europa][BKT-03][P1] 빈 버킷 삭제 보장과 버전·강제 정리 경로 분리

상위 Epic: #1527. 상태: 해결 설계, 미구현. 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 문제와 재현

deleteBucket 설명은 빈 버킷 삭제인데 31번에서는 파일이 있는 버킷이 삭제됐고 GET이 200에서 404로 바뀌었다. 32번에서는 일반 객체 삭제 후에도 이전 버전/DeleteMarker가 있었으나 일반 UI 버킷 삭제로 모두 제거됐다. 확인창에는 대상 내용물/버전 수나 강제 정리 구분이 없다.

## 원인

Glue driver가 RGW 관리자 `removeBucket(name)`을 일반 삭제에 사용한다. 객체·버전·삭제 표식의 존재와 일반/강제 삭제 의미를 구분하지 않는다. 브라우저도 현재 객체 목록만 보여 버전 이력을 확인할 수 없다.

## 해결 설계

1. 기존 `deleteBucket(id)`는 **empty-only** 계약으로 고정한다. 현재 객체, 이전 버전, 삭제 표식이 있으면 거부한다. 소유권/프로젝트 권한 확인 후 S3의 비강제 DeleteBucket을 사용한다. 사전 확인 뒤 업로드가 발생해도 최종 S3 요청이 BucketNotEmpty로 거부하도록 하고 관리자 purge로 폴백하지 않는다.
2. 별도 비동기 `purgeBucket(id, confirmname, includeversions=true)` API를 제안한다. 기존 API의 생략된 force 값을 true로 바꾸지 않는다. 정확한 이름 확인, 명시적 버전 포함 선택과 권한 검사를 서버에서 검증한다.
3. 삭제 영향 조회 응답은 current objects/versions/delete markers, 조회 시각, 완전 집계 여부, 잠금·진행 중 작업 차단 사유를 제공한다. 집계는 안내용이며 실제 실행 시 다시 검증한다. 부분 집계를 전체 개수로 표시하지 않는다.
4. 강제 정리는 버전 ID를 포함한 페이지/배치 단위로 처리하고 진행률·오류·완료 위치를 저장한다. 부분 실패 시 버킷과 DB 소유권/할당량을 유지하고 삭제하지 못한 항목을 표시한다. 실제 버킷 제거를 확인한 뒤 원장/DB 제거를 한 번만 확정한다.
5. `listBucketObjectVersions`에 해당하는 표준 S3 경로와 UI의 버전 표시/삭제 표식 표시/선택 버전 삭제·복구 동선을 제공한다. 버전 복구는 이전 버전을 현재 객체로 복사하거나 삭제 표식을 명시적으로 제거하는 동작을 구분한다. 기존 이력은 복구 작업으로 덮어 지우지 않는다.
6. 일반 삭제와 강제 삭제 확인 문구를 분리한다. 그룹 작업도 기본은 empty-only이고 대상별 결과를 표시한다. 잠금/보존 대상은 강제 옵션으로 우회하지 않는다.

## 실패·호환성·경계

읽기 불가/권한 부족/시간 초과는 비어 있음으로 취급하지 않는다. 작업 중단은 이미 삭제한 객체를 복구한다는 의미가 아니며 남은 작업의 상태를 유지한다. 레거시 클라이언트가 내용물 제거에 의존했다면 별도 purge API로 전환해야 한다는 동작 변경을 문서화한다. Legal Hold/보존기간 신규 설정 UI 전체는 BKT-01과 별도 범위지만 삭제 보호 판정은 필수다.

## 대상·의존 관계

DeleteBucketCmd/BucketApiServiceImpl, ObjectStoreDriver/Glue driver, 비동기 작업/원장 계층, storage.js 삭제 액션과 ObjectStoreBrowser.vue. 버전 UI의 자격 증명 접근은 BKT-05, 목록 안정성은 BKT-13과 연동한다. 기본 삭제 차단 수정은 해당 후속 UI를 기다리지 않고 먼저 반영할 수 있다.

## 완료 기준

- [ ] 빈 버킷만 일반 삭제 성공; 객체/버전/표식이 하나라도 있으면 데이터 변화 없이 거부.
- [ ] 확인과 실행 사이 새 업로드, 1000개 초과, 배치 부분 오류, 재기동/중복 요청 검증.
- [ ] 잠금·보존·권한 거부·조회 실패 시 강제 purge로 우회하지 않음.
- [ ] 버전 조회/복구/선택 삭제와 강제 정리의 영향을 실제 S3 GET/버전 목록으로 확인.
- [ ] 31/32에서 보호된 기본 삭제와 명시적 전용 자원 purge, 최종 원장 정리 검증.

근거: Epic BKT-03의 실제 파일 삭제, `s3-32-versions-after-ui-delete.xml`, `versioned-bucket-ui-delete-result.txt`. 이 이슈는 설계이며 현재 운영 자원 purge를 지시하지 않는다. 구현/실측은 dhslove 소스와 전용 자원을 사용한다.


<!-- source-links -->
## 소스 근거 링크

- [DeleteBucketCmd.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/api/src/main/java/org/apache/cloudstack/api/command/user/bucket/DeleteBucketCmd.java)
- [CephObjectStoreDriverImpl.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/plugins/storage/object/ceph/src/main/java/org/apache/cloudstack/storage/datastore/driver/CephObjectStoreDriverImpl.java)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: 필수 선행 이슈 없음. 본문의 안전한 실측 순서는 따른다.
- 인터페이스 조율·통합 검증: [BKT-05 #1533](https://github.com/ablecloud-team/ablestack-cloud/issues/1533), [BKT-13 #1528](https://github.com/ablecloud-team/ablestack-cloud/issues/1528), [BKT-01 #1530](https://github.com/ablecloud-team/ablestack-cloud/issues/1530).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.
