# [Europa][BKT-13][P1] 버킷 브라우저 탭 최초 진입·폴더 이동의 목록 로딩 정체 해소

상위 Epic: #1527. 사용자 추가 제보에 따른 신규 항목. 상태: #1528 구현. 배포·검증 결과는 `../../validation/bucket-1528-20261031/REPORT.ko.md` 참조.

## 구체적인 문제와 실제 재현

대상은 웹 브라우저 프로그램이 아니라 **Mold 버킷 상세의 '브라우저' 탭**이다. 2026-10-31 31/32번에서 새 전용 계정·버킷과 qa-file.txt, folder/, folder/nested.txt를 만들었다. 버킷 상세에서 브라우저 탭을 처음 누르면 목록은 비어 있고 검색/업데이트/폴더 생성/업로드가 로딩 상태로 남았다.

Glue의 location과 ListObjectsV2는 HTTP 200으로 완료됐고 XML에는 실제 파일과 폴더가 있었다. 32번은 목록 응답 뒤 약 32초 관찰 시점에도 대기 상태였다. 31번에서 browserPath가 이미 포함된 URL을 그대로 새로고침하면 파일/폴더가 표시됐다. 폴더 이동에서도 같은 URL/요청 범위 변경 경로를 확인했다. 60초 자동 갱신 등의 우연한 회복이 있을 수 있으므로 모든 경우에 영원히 끝나지 않는 네트워크 요청이라고 단정하지 않는다.

## 확인한 원인

`ObjectStoreBrowser.fetchData()`는 `$router.replace()`로 browserPath를 추가하면서 완료를 기다리지 않고 listObjects를 시작한다. listRefreshMixin의 기본 요청 범위에는 `$route.fullPath`가 포함된다. 따라서 요청 시작 후 자신의 URL 정규화만으로 범위가 바뀌고, stream end의 `isListRequestCurrent()`가 false가 된다. 응답 저장과 loading/fetching=false가 모두 그 조건 안에 있어 건너뛴다.

동일 소스의 격리 재현 결과:

| 조건 | stream end 뒤 pending | loading/fetching | 파일 목록 |
|---|---|---|---|
| URL 유지 대조군 | false | false/false | qa-file.txt |
| 요청 도중 browserPath query 추가 | false | true/true | 빈 배열 |

이는 Glue 응답 실패나 파일 부재로 설명할 수 없는 UI 요청 범위/완료 처리 결함이다.

## 해결 설계

1. ObjectStoreBrowser의 listRefreshMixin에 전용 scopeKey adapter를 지정한다. route의 버킷 path와 계정/프로젝트/버킷 ID, 정규화한 browserPath·제출된 searchPrefix 등 **실제 조회 조건**으로 범위를 정한다. 클라이언트 페이지, 미제출 검색어, 상세 선택은 조회 조건에서 제외한다. tab 표시 query 및 browserPath의 중복 URL 표현을 fullPath 전체로 다시 비교하지 않는다.
2. URL 정규화가 필요할 때만 router.replace를 수행하고 순서를 명시한다. 목록을 시작한 직후 같은 요청의 scope를 무효화하지 않게 하며, router watcher/created 경로가 중복 요청을 만들지 않게 한다. 뒤로/앞으로 이동은 URL의 경로를 실제 상태로 반영한 뒤 한 번 조회한다.
3. 각 요청의 generation과 stream 생명주기를 함께 관리한다. 새 폴더/버킷으로 바뀌면 이전 stream을 폐기하고 최신 요청이 로딩을 소유한다. 오래된 finally가 새 요청의 spinner를 끄거나 오래된 데이터가 새 폴더를 덮어쓰지 않도록 한다.
4. success/error/timeout/abort/예외의 모든 종료 경로에서 해당 요청의 로딩을 해제한다. end/error가 오지 않는 경우를 위한 제한 시간과 stream destroy/cleanup을 둔다. 로딩 중에도 취소·재시도가 가능한 경로를 제공한다.
5. 같은 범위 갱신 실패 시 마지막 성공 데이터를 유지한다. 초기 조회 실패는 오류와 재시도를 표시하고, 성공한 빈 목록만 '데이터 없음'으로 표시한다. 공통 mixin의 다른 기능에 적용되는 stale-response 방어를 전역 삭제하지 않는다.
6. 자동 갱신, 검색, 폴더 이동, 업로드 후 갱신, metadata 상세 열기를 같은 범위 규칙으로 검증한다. 60초 주기 새 요청이나 페이지 새로고침을 정상 기능의 필수 조건으로 삼지 않는다.

## 대상·의존 관계

`ui/src/components/view/ObjectStoreBrowser.vue`의 mixin 설정/fetchData/listObjects/openDir와 `ui/src/utils/listRefreshMixin.js`의 명시적 adapter. backend/API를 변경하지 않고 우선 해결 가능하다. BKT-09·BKT-10·BKT-12는 이 요청/로딩 계약을 재사용한다.

## 완료 기준

- [x] 목록→상세→브라우저 첫 진입에서 자동 갱신을 기다리지 않고 서버 완료 후 목록 표시/로딩 해제.
- [x] 루트/하위 폴더·URL 직접 진입·새로고침·뒤로/앞으로·빠른 연속 이동·검색 검증.
- [x] 성공 빈 목록, 403/404/timeout/stream error/abort 및 응답 역전에서 최신 데이터와 로딩 상태 일치.
- [x] 계정/프로젝트·버킷 전환 시 이전 파일·키·응답이 다른 범위에 나타나지 않음.
- [x] 단위/컴포넌트 회귀 재현은 기존 코드에서 실패하고 수정 코드에서 통과; 31/32 실제 UI+S3 응답으로 확인.
- [x] 시험 자원·계정·키 정리와 기존 운영 자료 보존 확인.

근거: `docs/validation/bucket-audit-20261031/subissues/hang31-network.json`, `hang32-network.json`, `hang32-list-response.json`, 첫 진입/직접 재접속 JPEG 및 `hang-source-proof.json`. 소스 기준은 `1c5b169cd6283da23cc3e155d4b6d14e39e42793`다. 위 근거는 수정 전 감사 기록이다. 수정 후 검증과 배포 내역은 새 검증 보고서에 구분해서 기록한다.


<!-- source-links -->
## 소스 근거 링크

- [ObjectStoreBrowser.vue](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/components/view/ObjectStoreBrowser.vue)
- [listRefreshMixin.js](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/utils/listRefreshMixin.js)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: 필수 선행 이슈 없음. 본문의 안전한 실측 순서는 따른다.
- 인터페이스 조율·통합 검증: [BKT-09 #1537](https://github.com/ablecloud-team/ablestack-cloud/issues/1537), [BKT-10 #1538](https://github.com/ablecloud-team/ablestack-cloud/issues/1538), [BKT-12 #1540](https://github.com/ablecloud-team/ablestack-cloud/issues/1540).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.

## #1528 구현 계약

- 공통 mixin에 선택적 `scopeKey`를 추가하고 다른 호출자의 기본 범위·stale 응답 검사를 유지한다.
- URL replace 완료 후 목록을 시작한다. 뒤로/앞으로의 query 경로 변경을 정규화해 실제 경로에 반영한다.
- SDK stream 종료를 Promise로 감싸 success/error/close/cancel/timeout/동기 예외를 한 번만 완료한다. 30초 deadline과 stream destroy, listener 제거로 UI 대기를 끝낸다.
- 요청 객체가 spinner와 결과를 소유한다. 취소된 옛 요청의 완료는 새 요청의 spinner나 데이터를 변경하지 않는다.
- 최초 실패는 오류/재시도, 같은 범위 갱신 실패는 마지막 성공 목록 유지, 성공한 빈 응답은 빈 목록으로 표시한다.
- 사용자·프로젝트·세션·route path 변경 시 client와 결과를 지운다. 부모가 새로 확인한 resource props를 제공한 뒤 재초기화한다. 버킷·endpoint·인증 키 변경도 별도로 감지한다.
- MinIO SDK의 공개 Readable을 destroy한다. SDK가 이미 보낸 HTTP 요청의 물리적 중단을 보장하는 API는 없으므로 지연 응답 격리도 함께 적용한다.
- #1529~#1540의 backend/키 노출/시각 디자인 등 별도 문제는 이 패치에서 완료로 표시하지 않는다.

## 실제 UI 검증에서 보강한 경로

- 상위 `AutogenView`가 `tab=browser`인 모든 경로 변경에서 metadata 조회를 생략했다. 다른 버킷 ID로 이동해도 이전 버킷의 제목과 파일이 남는 현상을 31/32에서 재현했다. 같은 버킷 내부 query 이동에만 조회 생략을 적용하고, `buckets` 경로가 바뀌면 새 resource를 조회한다. 다른 기능의 기존 browser query 규칙은 유지한다.
- URL params.id와 resource.id를 대조하여 이전 resource props가 다시 전달되어도 client를 재생성하지 않는다. 새 버킷 props가 확인될 때까지 이전 결과·선택·client를 지우고 범위 변경 안내를 유지한다.
- 설치된 Ant Design Vue 3.2.20의 Alert는 action 슬롯을 렌더하지 않는다. 지원되는 description 슬롯에 오류와 재시도 버튼을 배치하고 실제 Ant Alert 렌더링 및 버튼 클릭 테스트를 추가한다.
- 버킷 간 이동·일반 상세 이동·같은 버킷 query 이동을 Autogen watcher 테스트로 검증하고, ResourceView의 기존 라우팅 회귀 테스트도 함께 실행한다.
