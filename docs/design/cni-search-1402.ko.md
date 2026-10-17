# CNI 목록 UUID·소유 범위 검색 설계 — #1402

기준: Epic #1395, Draft PR #1409, `dhslove/epic-1395-cni`의 #1401 완료 커밋 `8ca62fd664d9b9c12cfb13e4a898936a88fd1efb`. 작업 시작 시 upstream Europa는 `dc31262567f2afe5f6278fae61c1cef7e307fe3e`이며 기존 Epic 브랜치에 포함되어 있다.

## API 계약과 화면 연결

`ListCniConfigurationCmd`는 `ListUserDataCmd` 및 `BaseListProjectAndAccountResourcesCmd`를 상속한다. 서버는 CNI 플래그를 강제하며 `id`/`name`은 정확 일치, `keyword`는 이름 부분 일치, 소유 범위는 기존 Cloud ACL로 처리한다. 정렬은 서버의 ID 역순 페이지 조회이며 API에 sortkey/sortorder/tab은 없다.

- CNI도 일반 UserData의 `userDataLibraryParams`를 재사용한다. UUID 형태의 기본 검색어는 `id`로, 일반 검색어는 `keyword`로 전달한다.
- CNI 메타데이터의 고급 검색에 `id`, `name`, `domainid`, `account`를 등록한다. 소유 범위 선택지는 기존 권한에 따라 노출한다.
- CNI 전용 매개변수 핸들러는 지원되는 조회/ACL/페이지 필드만 전달한다. q, tab, sortkey/order, cnireturn, forcks 등 화면 전용/중복 상태가 API에 섞이지 않는다. 일반 UserData 핸들러와 공용 요청 인터셉터는 변경하지 않는다.
- CNI를 공통 프로젝트 보기 대상에 추가한다. 기본/전체 프로젝트 전환은 공통 SetListAllProjects 경로, 선택 프로젝트는 기존 요청 인터셉터의 projectid 주입을 그대로 따른다.

## 상태와 경합

검색 제출·초기화·필터 제거는 1페이지로 이동하고 페이지 크기를 유지한다. 페이지 크기를 바꾸면 CNI는 1페이지로 복귀한다. 검색/페이지/정렬 상태는 Vue Router query에 기록하여 재진입·새로고침·뒤로가기에서 복구한다. 상세 진입/삭제 후 복귀용 `cnireturn` 허용 목록에도 id/q를 포함한다.

표 정렬은 현재 페이지의 로컬 비교 함수다. 방향을 URL에 기록하고 다시 조회할 때 열의 sortOrder를 복원하지만 서버에 정렬 필드를 전달하지 않는다. 이 한계를 화면에 명시한다.

고급 검색을 열기만 하면 기본 검색어가 사라지는 공통 동작을 CNI에서 억제한다. CNI 폼은 URL 값부터 채운 뒤 소유 선택지를 조회한다. 도메인 변경 시 계정을 즉시 비우고 필드별 요청 세대로 이전 계정 응답을 무효화한다. 다른 메뉴의 동적 필터 경로는 유지한다. 목록 조회의 기존 scope/version 및 상세/본문 작업 가드는 유지한다.

## 검증 및 배포

31번 클러스터에서 전용 도메인, 일반 계정 A/B, A 소유 프로젝트 P/Q, 28 CNI와 일반 UserData 2개를 사용한다. 같은 이름을 서로 다른 소유 범위에 등록하여 UUID/정확 이름/부분 이름과 ACL 교차 필터를 대조한다. 20개 페이지 분할, 페이지 크기, 필터 제거/초기화, URL/Back/새로고침, 프로젝트 보기와 일반 UserData 회귀를 실제 UI로 검증한다.

서버는 존재하지 않는 UUID를 인자 변환 단계에서 HTTP 431로 거부할 수 있다. API의 빈 이름 검색과 UUID 부재 오류를 구분해 관찰하고, 이후 검색으로 복구 가능한지 확인한다. 서버 API 계약을 변경하거나 전체 자원을 대상으로 한 정렬을 추가하지 않는다.

UI 집중 테스트, lint, WSL ext4 UI production build만 수행한다. Java 변경이 없어 Maven 빌드는 필요하지 않으며 전체 Cloud 빌드는 실행하지 않는다. 정적 배포는 WEB-INF/config/JAR/PID를 보존하고 index.html을 마지막에 복사한다. 종료 전 전용 자원을 API로 정리하고 원래 CNI/Kubernetes 및 소유 범위 DB를 대조한다.

## 실환경에서 확인한 431 오류 경로 보완

첫 배포의 부재 UUID 시험에서 서버가 431을 반환한 뒤 공통 Autogen 오류 처리기가 대시보드로 이동하는 결함을 재현했다. 검색 실패를 정상 0건으로 취급하지 않고 CNI 화면에서 수정·재시도할 수 있어야 한다.

CNI 조회를 preserveOnFailure/20초 제한이 있는 getAPI 경로로 연결하고 CNI 로컬 오류 분기를 추가했다. 첫 조회·다른 검색 범위 실패는 이전 행을 지우고 오류/재시도를 표시한다. 동일 범위의 일시 장애는 이전에 확인한 행을 유지하되 선택을 비우며, 부재/권한 오류는 이전 데이터를 제거한다. 실제 인증 만료는 공통 요청 인터셉터의 기존 로그아웃 정책을 유지한다. 일반 UserData와 다른 목록의 오류 정책은 변경하지 않는다. 431/432/503 첫 실패, 정상 검색 복구, 같은 범위의 일시 실패 및 부재 오류를 회귀 테스트에 추가했다.
