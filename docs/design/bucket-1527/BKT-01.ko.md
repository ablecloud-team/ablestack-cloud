# [Europa][BKT-01][P1] 버킷 암호화·객체 잠금의 실제 적용과 표시 상태 일치

상위 Epic: #1527. 상태: 해결 설계, 미구현. 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 구체적인 문제와 재현 근거

32번 Mold에서 암호화·버전 관리·객체 잠금을 모두 켠 버킷이 Created/true로 표시됐다. 실제 Glue에서는 버전 관리만 Enabled였고, encryption/object-lock 조회는 각각 설정 없음 404, RGW object_lock_enabled=false였다. 보호 기능이 적용됐다는 잘못된 운영 판단을 유발한다.

## 원인

`CephObjectStoreDriverImpl.createBucket(bucket, objectLock)`는 인자를 생성 요청에 반영하지 않고 일반 `createBucket(bucketName)`을 사용한다. 암호화 설정/해제는 false를 반환하는 미구현 메서드이며 `BucketApiServiceImpl`은 반환값을 확인하지 않고 요청값을 DB에 저장한다.

## 해결 설계

1. `ObjectStoreDriver`/`ObjectStoreEntity`에 기능 지원 여부와 실제 보호 설정을 조회하는 계약을 추가한다. 지원 여부는 Supported/Unsupported/Unknown으로 구분한다. Glue 버전만 보고 암호화/KMS 준비 완료를 추정하지 않는다.
2. `listObjectStoragePools`에 비밀 정보 없는 `capabilities`를 추가한다. UI는 선택한 저장소의 지원 상태·제약을 표시하고 Unsupported/Unknown인 보호 옵션을 성공 가능한 옵션처럼 제공하지 않는다. 기존 제공자도 명시적 capability를 구현하며 미구현은 Unknown이다.
3. 객체 잠금은 S3 생성 요청의 object-lock 설정에 반영한다. 신규 생성에서 objectlocking=true이면 versioning=true를 요구하고, 잠금 버킷의 버전 관리 중단은 backend에서 거부한다. 기존 버킷에 잠금을 암묵적으로 추가/해제하지 않는다.
4. Glue 암호화는 지원되는 S3 설정 요청과 조회를 구현한다. 현재 배포 환경에서 제공할 수 없으면 구체적인 미지원 오류로 요청을 거부한다. false 반환을 성공으로 취급하지 않는다.
5. 적용 → 실제 설정 재조회 → DB 반영 순서를 사용한다. 부분 실패 시 변경 전 설정으로 복원한다. 신규 생성 보상 삭제는 BKT-03의 안전 삭제 규칙을 사용한다.
6. `BucketResponse`에 `protectionverificationstate`(Verified/Mismatch/Unknown), `protectionverifiedat`를 추가한다. 기존 true 값은 배포 시 실제 검증 없이 Verified로 승격하지 않는다. 기존 요청값과 확인한 실효값을 구분해 불일치를 표시한다.

## 실패·호환성·경계

기존 API 인자명은 유지한다. 변경하지 않은 인자는 그대로 둔다. 기능 조회 실패는 false/비활성으로 덮어쓰지 않고 Unknown으로 표시한다. 복구 실패는 작업 식별자와 재조정 필요 상태를 남기고 후속 변경을 제한한다. 실제 잠금/암호화를 해제하는 자동 일괄 보정은 하지 않는다. 보존기간·Legal Hold 관리 전체는 이 이슈의 필수 범위가 아니다.

## 대상 소스와 의존 관계

- `plugins/storage/object/ceph/.../CephObjectStoreDriverImpl.java`, `engine/storage/.../ObjectStoreDriver.java`, `ObjectStoreImpl.java`
- `server/.../BucketApiServiceImpl.java`, `BucketResponse.java`, `ObjectStoreResponse.java`
- `ui/src/views/storage/CreateBucket.vue`, `UpdateBucket.vue`, 버킷 상세 표시
- BKT-04를 실측 검증 전에 반영한다. BKT-02의 할당량 변경, BKT-03의 삭제 계약과 변경 순서를 조율한다.

## 완료 기준과 검증

- [ ] false/예외 반환을 성공으로 저장하지 않는 provider 계약 검증.
- [ ] 31/32 + 32번 Glue에서 생성/수정 후 UI·DB·S3 encryption/versioning/object-lock 상태 일치.
- [ ] 지원 불가, 권한 부족, 조회 타임아웃, 적용 중 실패/복구 실패, 기존 불일치 데이터 검증.
- [ ] 객체 잠금 선행 조건 위반과 버전 관리 중단 요청을 backend/UI에서 동일하게 거부.
- [ ] 기존 보호 설정·객체·계정 상세 보존, 전용 시험 자원 정리 확인.

실제 확인 근거는 Epic의 BKT-01 및 `docs/validation/bucket-audit-20261031/`의 S3 응답/`rgw-created.txt`에 있다. 소스 수정은 dhslove 작업본에서 수행하며 Cloud는 변경 모듈만 WSL ext4에서 빌드한다. 전체 Cloud CI는 별도 요청 시에만 실행한다.


<!-- source-links -->
## 소스 근거 링크

- [CephObjectStoreDriverImpl.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/plugins/storage/object/ceph/src/main/java/org/apache/cloudstack/storage/datastore/driver/CephObjectStoreDriverImpl.java)
- [BucketApiServiceImpl.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/server/src/main/java/org/apache/cloudstack/storage/object/BucketApiServiceImpl.java)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: 필수 선행 이슈 없음. 본문의 안전한 실측 순서는 따른다.
- 인터페이스 조율·통합 검증: [BKT-04 #1529](https://github.com/ablecloud-team/ablestack-cloud/issues/1529), [BKT-02 #1531](https://github.com/ablecloud-team/ablestack-cloud/issues/1531), [BKT-03 #1532](https://github.com/ablecloud-team/ablestack-cloud/issues/1532).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.
