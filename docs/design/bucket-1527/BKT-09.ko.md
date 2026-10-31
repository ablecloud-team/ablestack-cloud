# [Europa][BKT-09][P2] 버킷 사용량 동기화 작업·최신성·실패 상태 표시

상위 Epic: #1527. 상태: 해결 설계, 미구현. 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 문제·원인

외부 S3 PUT으로 4096바이트 파일을 추가한 뒤 브라우저 업데이트는 파일 4.00KB를 보여도 사용량은 0을 유지했다. syncBucketUsage를 호출하면 4KB가 되고 상위 새로고침 후 맞아졌다. 목록 갱신과 통계 수집 범위가 다르지만 UI가 이를 설명하지 않는다. 내부 sync 실패는 console.warn으로만 처리되고 수집 시각도 없다.

## 해결 설계

1. '목록 업데이트'와 '사용량 동기화'를 명시적으로 분리한다. 동기화 중에도 현재 파일 목록과 마지막 성공 사용량을 유지한다. 중복 클릭은 하나의 요청으로 합친다.
2. `syncBucketUsage` 응답의 기존 size는 **KiB** 의미를 유지한다. byte 신규 필드가 필요하면 별도 명명하고 기존 필드 단위를 바꾸지 않는다. BucketResponse에 `usagelastupdated`, `usagestatus`(Fresh/Stale/Error/Unknown)를 추가하고 서버 시각을 사용한다.
3. 성공한 수집 결과만 bucket.size·통계·저장소 집계에 반영한다. 조회 오류/누락/부분 목록을 0 사용량으로 대체하지 않는다. 버킷이 존재하며 실제 빈 상태임을 확인한 경우만 0으로 갱신한다.
4. bucket 수집과 Object Store 집계를 같은 서비스 경로로 맞춰 중복 카운팅을 막는다. Glue의 단일 버킷 통계 조회를 우선 사용하고 제공자 fallback이 필요하면 전체 조회임을 드러내며 제한 시간·동시 요청 합치기를 적용한다.
5. UI 업로드/삭제의 객체 작업 성공과 통계 수집 실패는 별개로 알린다. 예를 들어 '파일 업로드 완료, 사용량 동기화 재시도 필요'로 표시하며 전체 업로드를 실패로 바꾸거나 다시 업로드하지 않는다.

## 대상·의존 관계

SyncBucketUsageCmd/BucketApiServiceImpl의 updateBucketUsage·주기 수집, BucketStatistics/ObjectStore 집계, BucketResponse와 ObjectStoreBrowser/상세 요약. BKT-13 목록 상태 수정 뒤 UI 검증하고 BKT-11은 여기서 정의한 시각/상태를 재사용한다. quota 축소(BKT-02)는 수집 실패를 0으로 보지 않는다.

## 완료 기준

- [ ] 외부 S3 변경 후 명시적 수집으로 브라우저/요약/DB/인프라 집계 일치.
- [ ] 빈 버킷, 통계 지연, 실패/부분 응답, 빠른 재시도, 프로젝트·버킷 전환 검증.
- [ ] 실패 중 기존 데이터 유지, 재시도와 수집 시각 표시, 중복 통계·카운터 변동 없음.
- [ ] 31/32 Glue에서 UI 작업 후 수집 실패와 복구를 각각 검증.

근거: Epic BKT-09, `ui31-stale-usage.jpg`, syncBucketUsage API 응답. 실제 Glue 통계 지연은 0 사용량과 구분한다. 변경 모듈 빌드/전용 자원 규칙을 따른다.


<!-- source-links -->
## 소스 근거 링크

- [SyncBucketUsageCmd.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/api/src/main/java/org/apache/cloudstack/api/command/user/bucket/SyncBucketUsageCmd.java)
- [BucketApiServiceImpl.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/server/src/main/java/org/apache/cloudstack/storage/object/BucketApiServiceImpl.java)
- [ObjectStoreBrowser.vue](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/components/view/ObjectStoreBrowser.vue)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: [BKT-13 #1528](https://github.com/ablecloud-team/ablestack-cloud/issues/1528)
- 인터페이스 조율·통합 검증: [BKT-11 #1539](https://github.com/ablecloud-team/ablestack-cloud/issues/1539), [BKT-02 #1531](https://github.com/ablecloud-team/ablestack-cloud/issues/1531).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.
