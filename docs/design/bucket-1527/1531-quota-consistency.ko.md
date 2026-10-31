# #1531 버킷 할당량 일관성과 복구

상위 Epic #1527, PR #1541. 기존 버킷/객체와 계정 상세정보를 유지한다.

## 입력과 사전 검증

신규 및 명시적 수정의 할당량은 1~2147483647 정수 GiB다. long 정확 연산으로 byte로 환산한다. 기존 0/음수 할당량은 조회와 다른 옵션의 단독 수정을 허용하고, 양의 정수 입력으로 정상화한다. Object Store size=0의 용량 추적 해제 계약은 유지한다.

계정·프로젝트·부모 도메인은 기존 CheckedReservation으로 검사한다. 저장소는 확정 할당량과 진행 중 예약, 증가분을 함께 검사한다. 수정은 Glue에서 읽은 실제 사용량(logical/physical 중 큰 값)보다 작은 값을 거부하고, 실제 사용량을 읽지 못하면 변경하지 않는다. 조회 당시 Cloud와 Glue의 기존 quota가 다르면 임의로 덮어쓰지 않는다.

## 잠금·원장

저장소 단위 GlobalLock으로 생성 예약, 수정, 삭제, 저장소 크기/endpoint 변경을 직렬화한다. bucket_quota_operation의 active_bucket_id 유일 키는 버킷별 활성 작업 하나만 허용한다. object_store.allocated_size는 기대값을 비교하는 CAS로 갱신한다. 외부 통신 동안 SQL 트랜잭션을 유지하지 않는다.

CREATE는 Allocated 버킷과 계정 카운터, 저장소 예약을 한 트랜잭션으로 기록한다. UPDATE 증가분은 durable account_hold_bytes와 계정 카운터로 보유하며, 임시 CheckedReservation은 외부 쓰기 전에 종료한다. 계정 재집계도 활성 작업의 hold를 포함한다. 감소분은 성공 확정 때만 반환한다.

## 지속 작업과 복구

Prepared → Applying → Committing → Succeeded, 실패 시 Compensating → Failed/NeedsReconcile. 작업 UUID, 이전/요청 GiB, byte 예약, 정확한 Glue quota 스냅샷, 외부 버킷 ID, 단계, 재시도 횟수를 저장한다. S3 키는 저장하지 않는다.

Glue 변경 후 실제 quota를 재조회하고, 버킷 quota/state·계정 원장·저장소 CAS·작업 종료를 같은 SQL 트랜잭션으로 확정한다. 실패 시 enabled/max_size/max_objects/check_on_raw를 복원하고 확인한 뒤 예약을 반환한다. Java 객체는 DB 롤백 대상이 아니므로 실패 시 작업 행을 다시 읽어 복구한다.

응답 유실/재기동은 30초 주기 복구 작업이 단계와 실제 Glue quota를 비교해 재개한다. 작업 종료를 같은 트랜잭션으로 기록하여 카운터를 한 번만 확정한다. 복구 실패 또는 외부 버킷 ID 변경은 NeedsReconcile로 보존한다. UI의 복구 재시도는 expectedquotaoperationid를 요구하며 기존 버킷 ACL을 그대로 적용한다. 오래된 작업 ID와 복구+속성 변경의 혼합 요청은 거부한다.

생성 응답을 확인하지 못한 기존 버킷은 자동 삭제하지 않는다. 확인된 신규 생성의 보상에만 S3의 빈 버킷 삭제를 사용하고, 객체/버전 강제 삭제를 사용하지 않는다. 외부 삭제 후 Cloud DB 실패도 DELETE 작업으로 추적하여 재기동 후 원장을 확정한다.

## 범위 경계와 UI

할당량과 암호화·버전·정책의 실제 변경을 섞은 요청은 외부 변경 전에 거부한다. UI는 바뀐 값만 보내고 분리 적용을 안내한다. 보호 옵션 전체의 상태 전이 문제는 #1530에서 별도로 다룬다. 일반 삭제의 데이터 보호 정책은 #1532 범위로 유지한다.

UI는 공통 MoldDialog/form/action-button과 Status를 재사용한다. 입력 오류는 quota 필드, 운영 오류는 alert, 복구 작업은 상태·UUID·시각으로 표시한다. 용량/예약 스냅샷 시각과 서버의 최종 검증을 구분한다. 고정 배경/텍스트 색상을 추가하지 않고 공통 다크 테마를 따른다. 32px 복구 버튼과 4px 모서리, 반응형 너비를 유지한다.

## 스키마와 배포

새 테이블은 정규 4.22.1→4.23 업그레이드 SQL과 Europa 보안 스키마 보완 경로에 포함한다. 이미 같은 버전인 테스트 서버에는 setup/db/migrate-bucket-quota-operations.sql을 JAR 교체 전에 적용한다. 기존 WEB-INF/config를 보존하고 변경 모듈의 class와 추가 스키마/bean만 현재 관리 JAR에 반영한다.

Glue Admin Ops 계약: [개별 버킷 quota 설정](https://docs.ceph.com/en/latest/radosgw/adminops/#set-quota-for-an-individual-bucket). 검증 완료 결과와 배포 해시는 별도 검증 보고서에 기록한다.
