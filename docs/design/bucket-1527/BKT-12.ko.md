# [Europa][BKT-12][P2] 버킷·Object Store 화면의 테마·반응형·용어 일관성 개선

상위 Epic: #1527. 상태: 해결 설계, 미구현. 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 문제·원인

다크 테마의 사용량/총용량 레이블이 검은색이라 대비가 낮다. 실제 DOM viewport 390px에서 업로드 버튼 오른쪽 끝이 약 405.5px라 잘린다. 고정된 toolbar flex/순서와 테마를 따르지 않는 텍스트, Created/true/false 및 Object 저장소/Object 스토리지 혼용이 원인이다.

## 해결 설계

1. 버킷 목록/상세/브라우저/생성·수정 모달과 Object Store 목록/상세를 기존 VM/스토리지 UI 패턴에 맞춘다. 주 작업 텍스트 버튼을 먼저 배치하고 업데이트를 보조 작업 끝에 두며 넓은 화면의 검색은 오른쪽에 둔다.
2. 좁은 폭에서는 작업 영역과 검색을 별도 행으로 배치한다. flex 최소 폭·줄바꿈·overflow를 명시하고 버튼을 화면 밖으로 잘라 숨기지 않는다. 객체 테이블의 필요한 가로 스크롤과 작업 버튼의 접근 불가를 구분한다.
3. 색상은 공통 theme token을 사용한다. 상태·수집 실패·읽기 전용·비활성의 차이를 색만으로 전달하지 않는다. 포커스, 버튼 이름, tooltip/aria-label과 키보드 이동을 확인한다.
4. 상태/불리언은 번역된 운영 용어와 배지로 표시하고 API 원시 값은 진단 정보에서 확인할 수 있게 한다. Glue·버킷·오브젝트 스토리지 용어를 통일하고 새 문자열을 locale에 등록한다.
5. 목록 업데이트 시 기존 데이터·탭·검색·선택의 범위를 보존하고 오류를 빈 목록으로 바꾸지 않는다. BKT-13의 요청 상태 수정과 충돌하는 별도 loading 제어를 만들지 않는다.

## 범위·의존 관계

ObjectStoreBrowser.vue/style, storage.js, infra/objectStorages.js, 생성/수정 폼과 locale. BKT-07/08/09/10/11에서 추가하는 필드와 작업의 인터페이스를 사용하고 마지막에 화면 전체를 검증한다. backend의 보호/권한 오류를 CSS로 숨기는 작업은 포함하지 않는다.

## 완료 기준

- [ ] 밝음/어두움, 실제 1440·901·390·360px viewport에서 작업 버튼 접근·문구·대비 확인.
- [ ] DOM 좌표상 필수 작업이 잘리지 않고 검색/모달/상세 drawer가 키보드로 조작됨.
- [ ] 로딩/오류/빈 결과/긴 이름/큰 용량과 영문·한국어의 배치 검증.
- [ ] 31/32 동일 실행본에서 실제 기능을 유지하며 화면/DOM/이미지 크기를 함께 기록.

근거: Epic BKT-12와 UI JPEG. 기존 모바일 JPEG는 도구가 축소한 390×377 미리보기이고 DOM은 390×844였으므로, 새 검증도 둘을 혼동하지 않는다. UI 빌드 산출물 반영 시 WEB-INF/META-INF를 보존하고 /client/ HTTP 200을 확인한다.


<!-- source-links -->
## 소스 근거 링크

- [ObjectStoreBrowser.vue](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/components/view/ObjectStoreBrowser.vue)
- [storage.js](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/config/section/storage.js)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: [BKT-13 #1528](https://github.com/ablecloud-team/ablestack-cloud/issues/1528)
- 인터페이스 조율·통합 검증: [BKT-07 #1535](https://github.com/ablecloud-team/ablestack-cloud/issues/1535), [BKT-08 #1536](https://github.com/ablecloud-team/ablestack-cloud/issues/1536), [BKT-09 #1537](https://github.com/ablecloud-team/ablestack-cloud/issues/1537), [BKT-10 #1538](https://github.com/ablecloud-team/ablestack-cloud/issues/1538), [BKT-11 #1539](https://github.com/ablecloud-team/ablestack-cloud/issues/1539).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.

## #1528 작업 중 추가 표준 검토

[다크 모드·VM 표준 비교 보고서](../../validation/bucket-1528-20261031/STYLE-REVIEW.ko.md)를 추가했다. 실제 VM 볼륨 탭과 비교했고 31/32의 밝음·어두움 및 1440/901/390/360px를 측정했다. 검색/주 작업 순서, pill 버튼, 다크 레이블과 객체 상세 값의 검은색 고정, 390/360px 검색 폭 0이 확인되어 본 이슈는 미해결로 유지한다. #1528의 로딩 수정 완료를 전체 UI 표준 준수로 계산하지 않는다.
