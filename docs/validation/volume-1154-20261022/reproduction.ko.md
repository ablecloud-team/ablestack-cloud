# #1154 실제 검증 절차

1. 31/32 각각 전용 접두어로 A 도메인·DomainAdmin/User·User 소유 프로젝트를 만든다. 공개/도메인 일반 디스크를 만들고 각 범위에 자동 생성 전용/일반 연결 컴퓨트를 준비한다. 비활성 컴퓨트도 전용/일반 각 하나를 만든다. VM·실제 볼륨 이미지는 이 UI 변경의 검증 대상이 아니므로 생성하지 않는다.
2. listServiceOfferings의 연결 ID/이름/computeOnly와 DB service_offering.disk_offering_id→disk_offering.compute_only를 대조한다. listApis의 응답 필드가 boolean인지 확인한다. 자동 전용은 listDiskOfferings 단독 조회에서 제외되고 일반은 조회되는지 Admin/DomainAdmin/User/Project 실제 로그인으로 확인한다.
3. 빌드 번들을 두 관리 서버의 활성 webapp에 정적 파일만 반영한다. 기존 WEB-INF/META-INF/config/JAR/PID를 보존하고 파일 해시·새 로그인·HTTP 200·Mold active를 확인한다.
4. 실제 브라우저에서 접두어로 컴퓨트 목록을 검색한다. 연결된 디스크 오퍼링 헤더, 전용 이름의 텍스트와 클릭 시 URL 불변, 일반 이름의 링크/href·클릭한 정확한 디스크 상세·브라우저 뒤로가기의 원래 검색/필터를 확인한다.
5. 컴퓨트 상세는 기존 세로 행을 유지하면서 전용/일반 연결 방식과 이름의 텍스트/링크를 검증한다. Admin/DA/User/Project에서 허용된 두 범위를 확인하고 비활성 컴퓨트는 관리자 Inactive 목록·상세에서 검증한다.
6. 특정 XHR만 실패시켜 오류/재시도와 기존 목록 보존을 확인한다. 별도 제어 응답으로 연결 유형 누락/null/문자열을 검증할 경우 실제 API 계약과 구분해 기록한다. 타입 미확인은 일반 연결로 오판하거나 /diskoffering/undefined 링크를 만들면 안 된다. 오래된 응답·권한 없는 링크는 focused 검사 결과와 연결한다.
7. 컴퓨트를 비활성화해 전용 디스크 동반 Inactive와 일반 연결 디스크의 독립 Active를 확인한 후 일반 디스크도 비활성화한다. 프로젝트/계정/도메인을 제거한다. 활성 잔여 0·운영 inventory 동일·호스트 6개 Up·배포 파일/JAR/PID 보존을 재검증한다. API의 Inactive 제품 이력과 배포 백업은 보존한다.

전체 Cloud 빌드/CI, 게스트 I/O·이미지 할당·물리 수명주기 검증을 이 UI 검사로 대체하지 않는다. 다음 #1209는 VM 생성의 디스크 선택기 10개 초과 페이지/표시 개수이며 #1210은 연결 대상 VM 표시이름 검색/선택이다.
