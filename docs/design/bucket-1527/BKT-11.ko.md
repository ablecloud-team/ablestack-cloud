# [Europa][BKT-11][P2] Object Store 용량 단위·수정 초기값과 연결 상태 관리 개선

상위 Epic: #1527. 상태: 해결 설계, 미구현. 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 문제·원인

20GiB 저장소의 상세는 총 크기를 21474836480으로 표시한다. 수정 폼의 size는 현재 20GiB로 채워지지 않는다. 응답 storagetotal은 byte, 수정 size는 GiB인데 일반 args 폼이 명시적 변환을 하지 않는다. 단, 이름만 수정할 때 실제 용량은 보존됐으므로 용량 초기화 결함으로 과장하지 않는다. 연결 상태·수집 시각·수동 점검·키 변경 동선도 부족하다.

## 해결 설계

1. Object Store 전용 수정 컴포넌트를 사용해 storagetotal(byte)→size(GiB)를 정확히 초기화한다. 변환이 정수로 맞지 않는 legacy 값은 조용히 반올림하지 않고 원래 값과 조정 필요를 표시한다.
2. 변경한 필드만 제출한다. size 생략은 유지, 0은 운영자가 설정한 용량 추적 상한 해제, 양수는 상한이다. 실제 Glue 물리 용량이나 버킷 quota의 무제한 의미와 혼합하지 않는다. 음수/소수/오버플로 및 기존 할당량보다 낮은 상한을 거부한다.
3. 전체/할당/사용을 단위와 비율로 표시한다. 0/미수집/알 수 없음/추적 해제를 구분하고 사용량 수집 시각은 BKT-09 값을 사용한다. API의 기존 byte 필드는 호환성을 위해 유지한다.
4. 관리자용 제안 API `checkObjectStoragePool(id)`는 제한 시간 안에 등록 endpoint의 읽기 인증을 점검한다. Connected/AuthFailed/AccessDenied/Unreachable/Unknown과 checkedat만 반환하고 키나 원문 응답을 노출하지 않는다. 무제한 재시도·데이터 생성·인증 우회를 하지 않는다.
5. 후보 URL/키 검증은 DB 갱신 전에 수행한다. 수정 실패 시 이전 설정과 성공 상태를 보존한다. BKT-05의 키 교체 검증 서비스를 재사용한다. 화면에서는 연결 확인·키 변경·소속 버킷·삭제 차단 사유를 각각 연결한다.

## 대상·의존 관계

infra/objectStorages.js, 신규 수정 폼, ObjectStoreResponse/StorageManagerImpl, 연결 검사 서비스/Command. 소속 버킷은 BKT-06, 통계 최신성은 BKT-09, 키와 후보 연결 검증은 BKT-05가 담당한다. 전체 Glue 인프라/스토리지 풀 증설 기능을 이 이슈에 섞지 않는다.

## 완료 기준

- [ ] 20GiB 왕복 변환, 이름만 수정, 0/미수집/legacy 값, 큰 값·할당량 미만 상한 검증.
- [ ] 표시 byte/GiB/비율과 API/DB가 일치하며 변경하지 않은 필드 보존.
- [ ] 정상 연결·인증 실패·권한 부족·timeout·오류 후 재시도/기존 연결 유지 확인.
- [ ] 31/32의 상세/수정/연결 점검/소속 버킷/삭제 차단 UI 확인.

근거: Epic BKT-11, `ui32-infra-capacity.jpg`, 저장소 수정·접근 실패 URL 복구 API 기록. 신규 연결 점검 API는 제안 계약이다. 구현은 dhslove 소스 및 변경 모듈 빌드 규칙을 따른다.


<!-- source-links -->
## 소스 근거 링크

- [objectStorages.js](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/config/section/infra/objectStorages.js)
- [StorageManagerImpl.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/server/src/main/java/com/cloud/storage/StorageManagerImpl.java)
- [UpdateObjectStoragePoolCmd.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/api/src/main/java/org/apache/cloudstack/api/command/admin/storage/UpdateObjectStoragePoolCmd.java)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: [BKT-05 #1533](https://github.com/ablecloud-team/ablestack-cloud/issues/1533), [BKT-06 #1534](https://github.com/ablecloud-team/ablestack-cloud/issues/1534), [BKT-09 #1537](https://github.com/ablecloud-team/ablestack-cloud/issues/1537)
- 인터페이스 조율·통합 검증: [BKT-02 #1531](https://github.com/ablecloud-team/ablestack-cloud/issues/1531).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.
