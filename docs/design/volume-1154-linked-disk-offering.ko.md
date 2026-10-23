# #1154 연결된 디스크 오퍼링 표시·탐색 설계

Epic #1422 · 누적 PR #1435 · 2026-10-22

컴퓨트 오퍼링의 diskofferingcomputeonly는 API ServiceOfferingResponse 및 ServiceOfferingJoinDaoImpl에 선행 구현되어 있다. listServiceOfferings는 실제 연결 DiskOfferingVO.isComputeOnly()를 응답한다. 이름이 같은지로 유형을 추론하지 않는다. 목록 컬럼은 기존 선행 customTitle인 연결된 디스크 오퍼링을 유지한다.

목록과 기존 세로형 상세에서 같은 LinkedDiskOffering 컴포넌트를 사용한다. true/문자열 true는 자동 생성된 내부 전용 오퍼링이므로 텍스트다. false/문자열 false이고 ID·이름·listDiskOfferings 권한이 있는 경우 일반 상세 링크를 제공한다. 누락·null·잘못된 값은 알 수 없음으로 표시하고 이동을 제공하지 않는다. 필드가 없는 과거 응답을 일반 연결로 오판하지 않는다.

상세는 연결된 이름과 연결 방식 행의 기존 세로 배치를 유지한다. 일반 연결은 목록/상세에서 정상 링크, 전용은 텍스트, 미확인 연결은 재조회 안내를 제공한다. 현재 요청·할당 정책 및 성능/수명주기 계약을 변경하지 않는다.

이번 소스 변경은 UI 모듈뿐이다. API/server 선행 구현은 31/32 실제 응답과 DB의 compute_only·연결 ID를 대조한다. Java 소스를 변경하지 않으므로 Maven/관리 JAR를 재배포하지 않는다. UI lint·focused 렌더링/라우터 검사·production build 후 정적 파일만 배포하며 WEB-INF/META-INF/config/JAR/PID를 보존한다.

실제 검증은 전용과 일반 연결을 각각 생성해 Admin/DomainAdmin/User/Project에서 권한/상태별 목록·상세·클릭·뒤로가기를 확인한다. Inactive 목록, 조회 실패 재시도 및 필드 누락의 텍스트/미확인 처리를 구분한다. 31 GFS2/32 kernel RBD의 새 이미지나 VM 기동을 요구하는 변경이 아니므로 테스트 VM/이미지는 만들지 않는다. 전용 계정/프로젝트·오퍼링은 검사 후 정리하고 제품 이력/배포 백업을 보존한다.
