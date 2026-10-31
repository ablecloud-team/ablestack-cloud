# [Europa][BKT-08][P2] 버킷 생성 저장소 이름 검색과 빈 목록·조회 오류 안내 수정

상위 Epic: #1527. 상태: 해결 설계, 미구현. 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 문제·원인

32번 생성 폼의 저장소 표시 이름을 그대로 입력해도 '데이터 없음'이 나온다. 옵션 value는 UUID인데 filterOption이 value만 소문자로 비교한다. 저장소 미등록, 검색 결과 없음, API 실패도 명확히 구별되지 않는다.

## 해결 설계

1. 옵션 label/name을 정규화한 대소문자 비구분 부분 검색을 기본으로 하고 UUID 검색도 허용한다. null/숫자형 값에 toLowerCase를 직접 호출하지 않는다. 한글·공백·이름 변경을 처리하되 식별자는 UUID를 유지한다.
2. 초기 조회가 성공한 경우에만 기본 저장소를 선택한다. 선택된 UUID가 여전히 유효하면 재조회/검색으로 바꾸지 않는다. 선택 저장소가 사라지면 명시적으로 선택을 해제한다.
3. 상태를 조회 중/조회 실패/미등록/검색 결과 없음으로 분리한다. 실패 시 재시도, 미등록 시 권한 있는 관리자에게 저장소 등록 연결을 제공한다. 일반 사용자에게 관리자 권한이 필요한 등록 액션을 노출하지 않는다.
4. 저장소가 없거나 요청 중이면 생성 제출을 막고 이유를 제공한다. 검색어를 지우면 전체 옵션을 복구하며 프로젝트/계정 문맥 전환의 오래된 응답은 버린다.

## 대상·의존 관계

CreateBucket.vue와 기존 선택기 유틸/번역. API 기능 추가는 필요 없다. BKT-07의 계정 선택 변경과 상태 처리를 조율하고, BKT-01의 미지원 기능 안내를 저장소 선택 변화에 맞춰 다시 계산한다.

## 완료 기준

- [ ] 한글/영문/공백 이름·UUID·빈 검색·동일 이름의 서로 다른 UUID 검증.
- [ ] API 오류/미등록/결과 없음 안내가 구분되고 UUID 선택이 유지됨.
- [ ] 31/32 실제 UI에서 표시 이름 검색→선택→생성 및 재조회/삭제된 옵션 처리 검증.
- [ ] 키보드 선택·접근성·일반 사용자 권한 검증.

근거: Epic BKT-08 및 `ui32-store-name-search.jpg`. 구현 소스는 dhslove 작업본을 사용하고 UI 변경에 맞는 확인만 수행한다.


<!-- source-links -->
## 소스 근거 링크

- [CreateBucket.vue](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/views/storage/CreateBucket.vue)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: 필수 선행 이슈 없음. 본문의 안전한 실측 순서는 따른다.
- 인터페이스 조율·통합 검증: [BKT-07 #1535](https://github.com/ablecloud-team/ablestack-cloud/issues/1535), [BKT-01 #1530](https://github.com/ablecloud-team/ablestack-cloud/issues/1530).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.
