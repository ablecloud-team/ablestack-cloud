# CNI 사용처 조회와 참조 보호 (#1399)

## 계약

`listKubernetesClusters`에 선택적 UUID 파라미터 `cniconfigurationid`를 추가한다. 필터 CNI의 실제 활성 레코드·호출자 UseEntry ACL·for_cks를 확인한 후 기존 클러스터 ACL, 프로젝트 범위, page/pagesize와 searchAndCount를 그대로 적용한다. 결과는 기존 KubernetesClusterResponse의 id/name/state/account/domain/project 및 CNI UUID/이름이다. 프로젝트 사용처는 해당 projectid를 함께 지정한다. 기존 무필터 호출의 계약은 유지한다.

`deleteCniConfiguration`은 미삭제 Kubernetes 클러스터의 cni_config_id 참조가 하나라도 있으면 거부한다. Running/Stopped/Alert/Error/Created/Starting/Destroying 및 GC 표시와 무관하며 removed IS NULL만 활성 기준이다. UI 조회를 생략한 직접 API 호출도 보호한다. 오류에 클러스터 이름·개수·소유자를 포함하지 않는다. 기존 유형 검사·ACL·VM/템플릿 참조 검사 및 본체/Annotation 트랜잭션은 유지한다. 일반 UserData 경로는 Kubernetes 검사기를 호출하지 않는다.

## 모듈 경계와 초기화

- api: `CniConfigurationReferenceChecker` 인터페이스만 정의한다. DB 모듈에 대한 production 의존을 추가하지 않는다.
- engine/schema: 기존 DB 의존을 사용하는 `CniConfigurationReferences.withLock`을 둔다.
- kubernetes-service: KubernetesClusterDaoImpl이 공용 인터페이스를 구현하고, cni_config_id + removed IS NULL COUNT로 판정한다.
- server: 삭제 요청 시 기존 server의 Kubernetes 연동과 같은 `ComponentContext.getDelegateComponentOfType`으로 플러그인 컨텍스트의 검사기를 조회한다. server → kubernetes-service 역방향 의존은 없다. 검사기가 없거나 조회가 실패하면 CNI 삭제를 거부한다.

31번 첫 배포에서 초기 주입 목록이 비어 사용 중 삭제가 허용되는 문제를 확인했다. 따라서 초기화 시점에 받은 선택적 목록과 루트 컨텍스트 조회를 사용하지 않고 요청 시점의 위임 컨텍스트 조회와 검사기 부재 시 거부를 구현한다. 최종 검증은 이 수정 배포 후 새 전용 자원에서 다시 수행한다.

## 생성과 삭제의 경쟁

CNI별 DB GlobalLock 이름은 `CniConfiguration.Reference.<numeric id>`다. 10초 내 획득 실패 시 재시도를 안내하고 쓰기를 수행하지 않는다. CNI가 생략된 생성은 이 잠금 없이 기존 동작을 유지한다.

CloudManaged 생성은 기존 Kubernetes 버전 잠금 → CNI 잠금 순으로 고정한다. 이 잠금 안에서 활성 CNI, UseEntry ACL, for_cks 및 선택된 클러스터 소유 계정/도메인의 일치를 검증하고 기존 클러스터 생성 트랜잭션이 커밋될 때까지 유지한다. 프로젝트의 실제 프로젝트 계정도 동일한 소유 규칙을 적용한다. 삭제는 동일 CNI 잠금을 획득한 뒤 본체 조회, 참조 확인, 본체/Annotation 삭제 커밋까지 유지한다.

생성 선행이면 삭제가 활성 참조를 보고 거부되고, 삭제 선행이면 생성이 활성 CNI를 찾지 못해 거부된다. 실패/예외에서도 unlock/releaseRef를 보장한다. 클러스터 삭제는 참조를 줄이는 작업이므로 이 잠금을 추가하지 않는다. 기존 이력에 삭제된 CNI가 남아도 목록 응답은 null을 안전하게 처리한다.

## 검증과 범위

공용 잠금의 양방향 동시 실행·시간 초과·예외 해제, DAO의 상태/GC 무관 활성 조건, 실제 생성 경로의 사전 검증, ACL/프로젝트 소유, 페이지/개수 유지, 검사기 부재·오류 시 삭제 거부, 일반 UserData·기존 삭제 회귀를 집중 테스트한다. 배포는 변경 Maven 모듈만 WSL ext4에서 빌드하며 기존 설치 JAR의 관련 클래스 묶음만 교체한다.

실환경에서는 전용 프로젝트/클러스터·CNI에서 사용처 조회와 생성 중/Running/Stopped 삭제 보호, 정상 클러스터 삭제 후 CNI 삭제를 확인하고 기존 데이터와 테스트 잔여를 대조한다. 사용처 탭·노드 링크는 #1400, 삭제 확인·완료 UX는 #1403, 전체 스타일 통일은 #1408 범위다. 전체 Cloud/Release CI와 정식 패키지 빌드를 대체하지 않는다.
