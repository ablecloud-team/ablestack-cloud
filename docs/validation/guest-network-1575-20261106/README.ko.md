<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->

# #1575 가상머신용 네트워크 권한과 연결 화면 검증

Epic #1554의 브랜치 `dhslove/epic-1554-guest-networks` 및 Draft PR #1577에서 진행했다. 실행 소스는 `c5515483d8c8324b89751ac655bd129929307ca7`이다. 31번 시험 VM의 루트 볼륨은 GFS2 SharedMountPoint에, 32번은 Glue RBD에 있음을 각 볼륨의 풀 ID로 확인했다. [배포·시험 증적](evidence/) · [화면 캡처](capture-matrix.ko.md) · [유지 자원](resources.ko.md)

## UI 변경

| 화면 | 반영 내용 |
| --- | --- |
| 네트워크 권한 탭 | VM 상세와 같은 공통 도구 모음에 권한 추가, 재설정, 업데이트를 배치했다. 계정·프로젝트를 구분하고 API별로 작업을 표시한다. 현재 화면 범위의 조회 결과가 확인된 뒤 변경을 허용한다. |
| 추가·회수·재설정 대화상자 | MoldDialog를 사용해 제목·본문·확인 버튼 위치를 통일했다. 관리자는 같은 도메인의 활성 대상만 선택하고, 일반 소유자는 계정 이름을 입력할 수 있다. 이름 오류는 입력을 유지하고, 결과가 불명확하면 재조회로 확인한다. |
| VM 배포·NIC | 배포 소유 범위가 바뀌면 이전 후보, 네트워크·기본 NIC 선택을 지운다. NIC 연결은 MoldDialog에서 소유자 기준 후보를 표시하고 저장 직전에 서버 허용 범위를 다시 확인한다. 철회 후 남은 NIC는 보이되 접근이 막힌 망의 NIC 행에 링크를 만들지 않는다. |
| 네트워크·Public IP 이벤트 및 코멘트 | 대상 리소스의 프로젝트 범위를 사용한다. 목록·탭·검색·페이지와 평문 코멘트 표시에서 VM 상세 공통 컴포넌트를 재사용한다. |
| 방화벽·포트포워딩·부하분산 태그 | VM 상세의 태그 편집기와 MoldDialog를 사용한다. 부모 Public IP 범위와 규칙 UUID를 구분해 각 작업에 전달하고, 비동기 작업 완료와 재조회 뒤 목록에 반영한다. 관리 규칙의 보호 조건을 적용한다. |
| 목록·상세·breadcrumb·테마 | 가상머신 목록과 상세에 사용하는 공통 탐색 패턴으로 네트워크 목록 검색, 상세 탭, breadcrumb 복귀와 이전 검색 복원을 확인했다. 라이트·다크 즉시 전환 및 화면 이동 후 테마 유지, 390/768/1280px을 실제 브라우저에서 확인했다. |

## 검증 결과

- 양쪽에서 Root Admin, DomainAdmin, 일반 소유자, 같은 도메인 수신자·비멤버, 하위·별도 도메인 사용자로 조회와 사용을 분리해 비교했다. Shared의 전체·도메인 단독·하위 포함·계정·프로젝트와 Isolated/L2의 추가 공유를 각각 비교했다. 지정 VLAN Shared의 권한 관리 Root Admin 제한, 프로젝트 외부 공유 차단, 같은 도메인 혼합 대상 검증, 중복 요청의 멱등성은 실제 API 응답으로 확인했다. [31번 역할 표](evidence/31-matrix-full-baseline.json) · [32번 역할 표](evidence/32-matrix-full-baseline.json) · [31번 권한 계약](evidence/31-permission-contract.json) · [32번 권한 계약](evidence/32-permission-contract.json)
- 계정과 프로젝트에 권한을 부여한 뒤 수신자 VM에 Isolated NIC를 연결했다. 31번은 새 NIC를 기본으로 지정하고 Shared 기본을 복원했다. 31번 계정 회수, 32번 전체 재설정 후 NIC는 그대로 남고 신규 사용과 상세 접근은 거부됐다. 31번에서 남아 있는 보조 NIC를 분리했고, 양쪽에 L2 NIC를 연결했다. L2 입력에서 IP를 비활성화하고 NIC 행에 Cloud IP 관리 안 함을 표시했다. 31번 배포 폼의 계정·프로젝트·하위 도메인 전환 및 32번 프로젝트 멤버 화면을 실제 UI로 확인했다. [UI 동작](evidence/ui-actions.json) · [31번 회수 후 API](evidence/31-ui-api-revoked-nic-retained.json) · [32번 재설정 후 API](evidence/32-ui-api-revoked-nic-retained.json)
- 네트워크와 Public IP의 이벤트 ID·검색·페이지·비소유자 응답, 코멘트 추가·공개 범위·삭제, 한국어와 HTML 문자의 평문 표시를 대조했다. 부가 리소스 태그는 Network, PublicIpAddress, FirewallRule, PortForwardingRule, LoadBalancer를 각각 추가·조회·삭제했다. 관련 VM 링크는 망 UUID로 필터링되며 수신자가 소유자 VM을 볼 수 없음을 확인했다. [31번 메타데이터](evidence/31-metadata-api.json) · [32번 메타데이터](evidence/32-metadata-api.json) · [31번 이벤트·코멘트](evidence/31-annotations-events-api.json) · [32번 이벤트·코멘트](evidence/32-annotations-events-api.json)
- WSL ext4에서 변경 파일 lint, UI 집중 시험 28개 스위트 493건과 production build를 실행했다. 31·32 활성 `/client/`의 HTTP 200, 정적 파일 해시, WEB-INF 및 운영 설정·프로세스 보존을 확인했다. 이전 #1568의 활성 시험 자원 잔여는 양쪽 모두 0이다. 양쪽 운영 네트워크 31개와 VM 176개의 식별자·소유 범위를 비교했다. [빌드](evidence/build.json) · [31번 배포](evidence/31-deployment.json) · [32번 배포](evidence/32-deployment.json)

## 검증 경계

31·32 각각 한 Zone의 실환경을 사용했다. 다중 Zone의 실제 전환과 템플릿 내장 NIC를 사용하는 별도 배포 경로는 이번 실환경 실행에 포함하지 않았다. 초기 시험 VM은 API로 준비하고, UI는 배포 후보 선택·범위 전환·NIC 작업까지 실행했다. 응답 역전과 불명확한 저장 결과는 집중 시험에서 재현했다. VLAN 외부 전달과 LB 백엔드 트래픽은 범위에서 제외했다. #1586의 Glue RBD 정리 결함은 별도로 추적한다.

다음은 Epic 순서 17번인 #1576 Isolated·L2·Shared 오퍼링 서비스·제공자·기능 조합 UI 설계 보강이다.
