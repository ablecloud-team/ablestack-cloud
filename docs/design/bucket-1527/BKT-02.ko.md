# [Europa][BKT-02][P1] 버킷 할당량 검증·예약과 Glue 변경 실패 복구

상위 Epic: #1527. 상태: 해결 설계, 미구현. 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 구체적인 문제와 재현 근거

31/32에서 20GiB Object Store의 버킷을 21GiB로 늘려도 성공했다. -1 입력은 UI에서도 제출 가능하고 DB allocated_size 범위 오류로 끝났지만, Glue max_size는 -1073741824로 변경됐다. Mold quota=1과 Glue quota가 서로 다른 상태가 남는다.

## 원인

생성은 외부 버킷/쿼터를 만든 뒤 저장소 총량을 확인한다. `updateBucketQuota()`는 `objectStore.setQuota()`를 먼저 실행하고 계정 제한/DB 카운터를 나중에 갱신한다. 입력 범위, 저장소의 동시 할당 예약, 외부 변경 보상 처리가 없다. DB 트랜잭션 롤백은 Glue 변경을 취소하지 못한다.

## 해결 설계

1. 버킷 신규/수정 quota는 **양의 정수 GiB**로 통일한다. 0/-1/소수/빈 문자열을 무제한으로 해석하지 않고 거부한다. 기존 0/음수 데이터는 조회를 허용하되 legacy 상태로 표시하고 명시적 정상화 요청만 처리한다. Object Store size=0의 추적 해제 의미(BKT-11)와 구분한다.
2. long 기반 정확한 단위 변환과 오버플로 검증을 적용한다. 증가량은 `max(new-old,0)`으로 계산하고 계정·프로젝트/도메인 제한, 저장소 확정 할당+다른 진행 중 예약+증가량을 함께 확인한다. 축소는 확인된 실제 사용량 미만이면 거부하며 사용량을 읽지 못하면 축소하지 않는다.
3. 기존 CheckedReservation을 계정 원장에 사용하고, 저장소/버킷별 잠금과 DB CAS로 동시 수정을 직렬화한다. 외부 통신 동안 장기 DB 트랜잭션을 잡는 방식은 사용하지 않는다.
4. 지속되는 quota 작업 행에 bucket/store/account, 이전·요청 quota, 예약 byte, 작업 식별자, 단계, 재시도 횟수를 기록한다. 비밀 키는 저장하지 않는다. 상태는 Prepared → Applying → Committing → Succeeded 또는 Compensating → Failed/NeedsReconcile이다.
5. 사전 검증/예약 → Glue 적용 → 실제 quota 재조회 → quota/계정 카운터/저장소 할당량 확정 → 예약 해제 순서로 처리한다. 외부 또는 DB 오류는 이전 Glue quota로 되돌린 뒤 예약을 해제한다.
6. 응답 유실·프로세스 재시작은 작업 행을 기준으로 현재 Glue/DB 값을 비교해 재개한다. 같은 작업의 카운터 증감은 한 번만 반영한다. 복원 실패는 NeedsReconcile로 남기며 성공 알림과 중복 변경을 막는다.
7. UI는 숫자 입력·범위·저장소 여유/수집 시각을 표시하고 431 등 입력 오류를 필드에 연결한다. 내부 SQL 오류를 사용자 설명으로 그대로 노출하지 않는다.

## API·데이터 계약과 호환성

createBucket/updateBucket의 기존 인자와 정상 응답은 유지한다. 오류 응답에 비밀 정보 없는 operation id와 재조정 필요 여부를 추가한다. 신규 작업 저장소는 스키마 업그레이드와 새 설치 양쪽에 포함한다. 전체 작업 복구가 필요해도 기존 운영 버킷을 임의 삭제하지 않는다. UI와 backend가 같은 범위 규칙을 사용하되 backend를 최종 판정자로 둔다.

## 대상 소스·의존 관계

`BucketApiServiceImpl`, `BucketDao`/`ObjectStoreDao`, 자원 제한·예약 계층, Glue driver, CreateBucket/UpdateBucket.vue. 실측은 BKT-04 이후 수행하고, 사용량 최신성은 BKT-09와 계약을 맞춘다. BKT-01과 보호 옵션이 섞인 수정은 어느 단계에서 실패해도 이미 바꾼 외부 설정을 추적해야 한다.

## 완료 기준과 검증

- [ ] -1/0/소수/최댓값/오버플로·총량 초과·사용량 미만 축소의 UI/API 거부.
- [ ] 동일 저장소 동시 할당으로 총량을 초과할 수 없고 예약이 유실되지 않음.
- [ ] Glue 실패, Glue 성공 후 DB 실패, 응답 유실, 재기동, 보상 실패를 주입한 검증.
- [ ] 재시도 후 quota, Glue max_size, 계정 원장, 저장소 할당량·예약이 일치.
- [ ] 31/32 전용 버킷의 증설/축소/정리 후 원래 데이터와 원장이 보존됨.

근거: Epic BKT-02, `negative-quota-runtime-divergence.json`, API 기록, UI 음수 오류 JPEG. Cloud는 변경 모듈만 WSL ext4에서 빌드하고 전체 빌드는 별도 요청에 따른다.


<!-- source-links -->
## 소스 근거 링크

- [BucketApiServiceImpl.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/server/src/main/java/org/apache/cloudstack/storage/object/BucketApiServiceImpl.java)
- [UpdateBucket.vue](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/views/storage/UpdateBucket.vue)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: 필수 선행 이슈 없음. 본문의 안전한 실측 순서는 따른다.
- 인터페이스 조율·통합 검증: [BKT-04 #1529](https://github.com/ablecloud-team/ablestack-cloud/issues/1529), [BKT-09 #1537](https://github.com/ablecloud-team/ablestack-cloud/issues/1537), [BKT-01 #1530](https://github.com/ablecloud-team/ablestack-cloud/issues/1530).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.
