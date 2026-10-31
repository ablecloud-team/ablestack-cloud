# [Europa][BKT-07][P2] 버킷 생성 UI의 소유 계정·도메인과 프로젝트 범위 연결

상위 Epic: #1527. 상태: 해결 설계, 미구현. 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 문제와 원인

관리자 API의 account/domainid 지정 생성은 전용 일반 계정 소유로 성공했지만 CreateBucket.vue에는 소유 계정·도메인 필드와 요청 매핑이 없다. 현재 일반 계정의 자기 목록 조회와 다른 계정 조회/수정 거부는 정상이다. 프로젝트 상단 문맥은 존재하므로 프로젝트 기능 자체가 없다고 판단하지 않는다.

## 해결 설계

1. VM/스토리지 생성에서 사용하는 기존 계정·도메인 선택 패턴을 재사용한다. Root admin은 허용 범위의 도메인/계정, Domain admin은 자신의 하위 범위, 일반 사용자는 자신의 소유자로 고정한다.
2. 일반 문맥에서 다른 계정 생성은 account+domainid를 쌍으로 전달한다. 현재 계정 기본 생성은 기존 생략 계약을 유지한다. 도메인 변경 시 계정 선택을 지우고 새 범위로 다시 조회한다.
3. 프로젝트 문맥은 projectid만 사용하고 account/domainid와 혼합해 전송하지 않는다. 상단 프로젝트 전환 시 열린 생성 폼의 소유자 선택과 제한 조회를 갱신하거나 안전하게 폼을 닫는다.
4. 모달 제출 전에 소유자·저장소·요청 할당량을 확인할 수 있게 한다. 제한과 권한은 backend createBucket의 소유자 결정/ACL/예약 로직이 최종 검증한다. 요청을 조작한 경우에도 UI와 무관하게 거부한다.
5. 도메인/계정 조회 오류는 무조건 admin으로 폴백하지 않고 해당 필드의 재시도를 제공한다. 비활성 계정이나 다른 문맥의 오래된 응답을 선택할 수 없게 요청 범위를 관리한다.

## 대상·의존 관계

CreateBucket.vue, 기존 계정/도메인 선택 컴포넌트, createBucket 소유자 계약. BKT-04의 상세 보존 수정 뒤 실측한다. BKT-08과 같은 생성 폼을 수정하므로 공통 선택/상태 처리의 중복 구현을 피한다. BKT-02의 계정별 quota 제한을 재사용한다.

## 완료 기준

- [ ] 관리자·도메인 관리자·일반 사용자·프로젝트의 UI 요청 인자와 생성 소유자 일치.
- [ ] 프로젝트와 account/domainid 혼합, 범위 밖 계정, 비활성 계정, API 조작 요청 거부.
- [ ] 도메인/프로젝트 변경 중 늦게 도착한 목록으로 다른 소유자가 선택되지 않음.
- [ ] 31/32 + Glue에서 소유 계정의 목록/접근·제한·삭제·계정 상세 보존 확인.

근거: Epic BKT-07, 전용 일반 계정 소유 버킷 생성과 531 접근 거부 기록. 프로젝트/도메인 관리자 UI는 기존 검증의 미확인 범위이며 이 이슈에서 새로 검증한다. 전체 빌드는 별도 요청 대상이다.


<!-- source-links -->
## 소스 근거 링크

- [CreateBucket.vue](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/views/storage/CreateBucket.vue)
- [CreateBucketCmd.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/api/src/main/java/org/apache/cloudstack/api/command/user/bucket/CreateBucketCmd.java)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: [BKT-04 #1529](https://github.com/ablecloud-team/ablestack-cloud/issues/1529)
- 인터페이스 조율·통합 검증: [BKT-08 #1536](https://github.com/ablecloud-team/ablestack-cloud/issues/1536), [BKT-02 #1531](https://github.com/ablecloud-team/ablestack-cloud/issues/1531).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.
