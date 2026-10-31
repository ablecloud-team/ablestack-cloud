# [Europa][BKT-04][P1] Glue 계정 키 저장 시 기존 상세 보존과 저장소별 자격 증명 분리

상위 Epic: #1527. 상태: 구현·31/32 반영·UI 검증 완료. 수정 전 재현과 최초 설계 이력은 아래에 보존한다. 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 구체적인 문제와 원인

전용 일반 계정에는 생성 직후 `mold.external-iam.provisioning=local-only-v1`이 있었다. 첫 Glue 버킷 생성 후 이 행이 삭제되고 Ceph 키 두 개만 남아 계정 삭제가 외부 IAM 연결 실패로 끝났다. 원래 행을 복원한 뒤 일반 삭제 API는 성공했다. FULL row binlog에서 교체를 확인했다.

`CephObjectStoreDriverImpl.createUser()`가 호출하는 `AccountDetailsDaoImpl.persist(accountId,map)`는 전체 상세 행을 expunge한다. 기존 RGW 사용자가 있으면 즉시 true를 반환해 Cloud 키 누락/불일치도 조정하지 않는다. 조회도 accountId만 사용하므로 서로 다른 Object Store의 키를 분리할 수 없다.

## 해결 설계

1. 긴급 보존 수정은 Glue 키 이름 두 개에만 개별 upsert를 적용한다. 공용 DAO의 전체 교체 의미를 전역 변경하지 않는다. 기존 IAM/운영/사용자 상세 정보는 읽거나 수정하지 않는다.
2. 최종 저장소는 `object_store_account_credential`(제안)로 분리한다. unique(account_id, object_store_id), RGW user id, 암호화된 키, revision, 검증 시각·상태를 둔다. 생성/기존 사용자 재사용/rotation이 모두 같은 resolver를 사용한다.
3. 동일 계정·저장소 동시 생성을 잠금/unique 제약으로 합친다. RGW 사용자 조회의 NotFound만 생성으로 이어지고 AuthFailed/timeout을 사용자 없음으로 바꾸지 않는다. 이미 있으면 권한 있는 조회로 실제 키를 확인해 저장한다. 기존 키를 임의 재발급하거나 사용자/버킷을 지우지 않는다.
4. 모든 Glue S3 호출은 bucket의 account/store 조합으로 credential resolver를 사용한다. bucket/계정 전체의 legacy 키를 계속 권위 값으로 읽지 않는다. BucketTO/응답의 전환도 함께 처리해 정책·버전·브라우저 접근이 끊기지 않게 한다.
5. 마이그레이션은 기존 버킷의 store/account 관계와 키를 읽어 실제 Glue에서 확인한 조합만 옮긴다. 추정만 가능한 키는 NeedsVerification으로 남긴다. 누락된 IAM origin을 일괄 local-only로 설정하지 않는다. 운영 상세 복원은 백업/변경 기록으로 확인된 원래 값만 사용한다.
6. 저장소/계정 제거는 참조와 외부 사용자 소유권을 확인한다. Cloud의 연결 기록 삭제와 RGW 사용자 삭제를 같은 의미로 취급하지 않는다. 다른 별칭/저장소가 같은 외부 사용자를 사용할 수 있으므로 무조건 외부 계정을 제거하지 않는다.

## API·UI 및 실패 처리

createBucket 소유자 인자는 유지한다. 키 조회/사용 실패는 비밀 값 없는 저장소별 상태로 응답하며 NPE/일반 성공으로 처리하지 않는다. secret은 기존 암호화 저장 방식을 재사용하고 응답/로그에는 BKT-05 규칙을 적용한다. DB 저장 실패 후 생성한 외부 사용자는 출처를 기록해 정리 가능하게 하되 기존 외부 사용자를 보상 삭제하지 않는다.

## 대상·의존 관계

Glue driver의 createUser/getS3Client/정책·버전 호출, AccountDetailsDao 호출부, 신규 credential VO/DAO/resolver, BucketTO 및 설치/업그레이드 스키마. BKT-05의 키 관리와 BKT-07/BKT-10의 소유 계정·metadata 경로가 이 계약을 사용한다.

## 완료 기준과 검증

- [x] IAM origin·임의 상세 3개를 가진 계정에서 첫/추가 버킷 생성 전후 기존 행 값/개수 동일.
- [x] 기존 RGW 사용자 + Cloud 키 없음/오래된 키/접속 거부/동시 생성 각각 검증.
- [x] 한 계정의 서로 다른 저장소 두 곳이 키를 덮어쓰지 않고 기존 버킷 접근 유지.
- [x] 계정 삭제가 의도한 local/external IAM 경로를 유지; legacy/새 설치 업그레이드 검증.
- [x] 재시도·rollback 후 키·기존 상세·운영 객체 보존, 전용 자원 및 키 정리 확인.

근거: Epic BKT-04, `account-detail-loss-and-recovery.json`, 계정 생성/버킷 생성/삭제의 API 기록. 재현 때 기존 admin 상세의 사전 행은 없었으며 전용 계정의 원래 행 복원 후 정리했다. 전체 Cloud 빌드는 별도 요청 대상이다.


<!-- source-links -->
## 소스 근거 링크

- [AccountDetailsDaoImpl.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/engine/schema/src/main/java/com/cloud/user/AccountDetailsDaoImpl.java)
- [CephObjectStoreDriverImpl.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/plugins/storage/object/ceph/src/main/java/org/apache/cloudstack/storage/datastore/driver/CephObjectStoreDriverImpl.java)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: 필수 선행 이슈 없음. 본문의 안전한 실측 순서는 따른다.
- 인터페이스 조율·통합 검증: [BKT-05 #1533](https://github.com/ablecloud-team/ablestack-cloud/issues/1533), [BKT-07 #1535](https://github.com/ablecloud-team/ablestack-cloud/issues/1535), [BKT-10 #1538](https://github.com/ablecloud-team/ablestack-cloud/issues/1538).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.


## 구현 결과

중간 account_details upsert 단계 대신 최종 scoped credential 테이블로 직접 전환했다. 기존 상세는 변경하지 않는다. 실제 Glue 검증·분산 pair lock·암호화 저장·검증 상태/시각·외부 생성 출처·안전한 실패와 부모 메타데이터 재시도를 구현했다. [현재 구현·배포·UI 검증·정리 보고서](../../validation/bucket-1529-20261031/REPORT.ko.md)를 따른다. 정상 상태 API 키 노출 제거는 #1533, 동시 생성의 quota 원장 결함은 #1531에 남는다. 전체 새 설치가 아닌 실제 MySQL fresh/idempotent 스키마를 검증했다. local-only 계정 6개를 IAM 표시 수동 복원 없이 삭제했고 외부 IAM 서버 통합 삭제는 실행하지 않았다.
