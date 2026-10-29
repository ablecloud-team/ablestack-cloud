# 정책 상세 이벤트 탭·코멘트 지원 판정·작업 버튼 갱신

## 이벤트

정책 생성·수정·삭제의 `SNAPSHOTPOLICY.*` 이벤트는 EventTypes에서 SnapshotPolicy에 연결되고 서버가 정책 UUID를 CallContext에 기록한다. 그러나 ApiCommandResourceType에 정책 유형이 빠져 있어 실제 31/32 listEvents(resourceid=정책 UUID, resourcetype=SnapshotPolicy)가 431 Invalid resourcetype를 반환했다. 전체 정책 생성 이벤트는 각각 479/465건이 조회되어 기록 자체는 존재했다.

API 리소스 enum 끝에 SnapshotPolicy와 기존 정책 엔티티의 연결을 추가한다. 기존 enum 순서와 listEvents 파라미터·ACL·UUID 검증은 유지한다. API 모듈만 WSL ext4에서 빌드하고 동일 baseline을 확인한 배포 JAR의 enum 클래스 한 개만 교체한다. 다른 JAR 엔트리·WEB-INF·설정·비UI 파일·보존 데이터는 유지한다.

정책 상세에 VM에서 사용하는 공통 EventsTab을 연결한다. resourceType은 SnapshotPolicy, resourceid는 정책 UUID로 고정하고 listEvents 권한이 있을 때만 탭을 표시한다. 기존 검색·열 선택·페이지·갱신·오류/재시도·반응형 탭 배치를 재사용한다. 원본 볼륨의 이벤트나 스냅샷 자체 이벤트를 정책 결과로 섞지 않는다.

## 코멘트

현재 AnnotationService.EntityType과 AnnotationManagerImpl의 엔티티/소유권 검증, UI AnnotationsTab의 매핑에는 SnapshotPolicy가 없다. AddAnnotationCmd는 지원하지 않는 유형을 서버에서 거부한다. SNAPSHOT은 생성된 스냅샷이며 스냅샷 정책과 다르다. generic listAnnotations의 빈 응답은 정책 코멘트 쓰기 지원의 근거가 아니다.

사용자의 “사용할 수 있는 기능이라면” 조건에 따라 미지원 코멘트 탭을 추가하거나 볼륨/스냅샷으로 대신 연결하지 않는다. 별도 구현 시 정책용 annotation enum·유효 엔티티/소유권·역할/프로젝트 ACL·UI 매핑/탭·생성/가시성/삭제 검증이 필요하다. 이번 변경에는 코멘트 API·DB 확장을 포함하지 않는다.

## 상세 작업 버튼

기존 visibleDataViewActions는 조회 중 빈 배열을 반환하므로 Breadcrumb 작업 버튼 DOM이 사라졌다. 같은 정책 범위에서 성공한 상세가 있고 오류가 없는 배경 조회 동안에는 표시할 작업 목록을 유지하되 disabled로 복제한다. trigger의 VM 표준 색상·배치·크기는 유지하고 실제 편집/예약 변경/삭제는 execAction 및 action menu guard로 잠근다. 초기 조회·다른 범위·권한 누락·오류 상태의 차단은 유지한다.

## 검증·보존

두 클러스터에서 API 정책별 이벤트·키워드·빈 결과, 실제 이벤트 탭·검색·열 선택·갱신·탭 전환과 다크/라이트·1280/768/390px를 확인한다. 상세 조회를 보류한 전·중·후 작업 trigger가 동일하며 메뉴 항목은 보류 중 잠기는지 확인한다. 기존 클러스터별 볼륨7·중지 정책24·BackedUp 자연 스냅샷1을 삭제/수정하지 않는다. 전체 Cloud/FTCTL 빌드는 실행하지 않는다.
