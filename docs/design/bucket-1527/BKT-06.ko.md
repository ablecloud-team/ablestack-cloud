# [Europa][BKT-06][P2] Object Store UUID 버킷 필터와 소속 버킷 조회 동선 복구

상위 Epic: #1527. 상태: 해결 설계, 미구현. 소스 기준: `1c5b169cd6283da23cc3e155d4b6d14e39e42793`.

## 문제·원인

31/32에서 listObjectStoragePools의 실제 UUID를 `listBuckets(objectstorageid)`에 전달하면 431로 거부됐다. name/ids 조회는 정상이다. ListBucketsCmd의 UUID 매개변수가 ObjectStore가 아닌 기본 스토리지 StoragePoolResponse를 참조한다. UI에도 저장소 필터와 Object Store 소속 버킷 탭이 없어 삭제 차단 대상을 찾기 어렵다.

## 해결 설계

1. 매개변수 entityType을 ObjectStoreResponse로 바로잡고 UUID→내부 ID 변환부터 QueryManager의 object_store_id 조건까지 연결을 검증한다. 인증/권한 제한을 제거하는 방식으로 우회하지 않는다.
2. keyword/name/ids/objectstorageid/계정·프로젝트 조건의 조합 의미는 교집합으로 정의한다. 기존 id/ids 상호 배타, 페이지/정렬 계약을 유지한다. 없는 UUID와 권한 없는 자원을 구분할 때 자원 존재 정보가 새로 새지 않게 한다.
3. 버킷 목록에 저장소 이름 선택 필터를 추가하고 URL에는 UUID만 저장한다. 이름 변경 후에도 같은 저장소를 가리키게 한다. 프로젝트 전환 시 범위가 다른 선택/목록은 초기화한다.
4. Object Store 상세의 '버킷' 탭은 listBuckets(objectstorageid=<현재 UUID>)를 사용한다. 별도 DB 우회나 전역 전체 목록의 프런트 필터를 만들지 않는다. 소유자·상태·용량과 버킷 상세 연결, 페이지/검색/업데이트를 제공한다.
5. 삭제 전 소속 버킷과 '버킷이 있어 삭제할 수 없음' 안내를 제공하되 backend의 최종 종속 검사도 유지한다. 목록 조회 실패를 0개/삭제 가능으로 표시하지 않는다.

## 대상·의존 관계

ListBucketsCmd, QueryManagerImpl, storage.js, infra/objectStorages.js와 소속 버킷 탭. API 수정 자체는 독립적이다. BKT-11은 이 탭/필터를 재사용하고, BKT-03의 empty-only 버킷 삭제와 Object Store 삭제를 혼동하지 않는다.

## 완료 기준

- [ ] 실제 UUID·없는 UUID·기본 스토리지 UUID 오입력·권한 제한의 API 회귀 검증.
- [ ] 저장소 두 곳, 여러 소유자, 페이지/검색/프로젝트 조건 결합에서 범위 일치.
- [ ] 31/32의 UI 필터·소속 버킷 연결·버킷 존재 시 저장소 삭제 차단 확인.
- [ ] 조회 실패 때 기존 성공 목록 유지와 실패 안내; 비인가 데이터 노출 없음.

근거: Epic BKT-06, API 기록의 양쪽 431 응답. 구현/검증은 dhslove 소스와 전용 자원을 사용하며 전체 Cloud CI는 별도 요청 대상이다.


<!-- source-links -->
## 소스 근거 링크

- [ListBucketsCmd.java](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/api/src/main/java/org/apache/cloudstack/api/command/user/bucket/ListBucketsCmd.java)
- [objectStorages.js](https://github.com/dhslove/ablestack-cloud/blob/1c5b169cd6283da23cc3e155d4b6d14e39e42793/ui/src/config/section/infra/objectStorages.js)

<!-- registered-dependencies -->
## 등록 이슈 간 의존 관계

- 선행 반영: 필수 선행 이슈 없음. 본문의 안전한 실측 순서는 따른다.
- 인터페이스 조율·통합 검증: [BKT-11 #1539](https://github.com/ablecloud-team/ablestack-cloud/issues/1539), [BKT-03 #1532](https://github.com/ablecloud-team/ablestack-cloud/issues/1532).
- 상위 Epic: [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527). 구현 완료 표시는 해당 이슈의 완료 기준과 실제 검증 후 별도로 갱신한다.
