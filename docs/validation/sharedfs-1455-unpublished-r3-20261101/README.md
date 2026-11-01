# #1455 R3 미게시 SMB setgid 보완 — 진행 기록

ACL0·guestOk=false인 새 경로 모드가 저장된 desired의 신원과 일치하고 모든 소유 리스너에서 섹션 부재가 확인된 경우만 미게시/ACL 대기로 구분합니다. 전역 force user/group도 조회해 충돌 보호를 유지합니다. 게시된 섹션 누락·예상 밖 게시·신원/관측 불일치는 계속 차단하며 예외를 무조건 무시하거나 기본값으로 덮지 않습니다.

동일한 실제 SystemVM에서 후보 코드를 격리 주입해 기존 setgid 사전 점검이 통과하고 실제 metadata/registry가 불변임을 확인했습니다. 두 private Samba 데몬으로 미게시/default 조회·게시된 섹션 확인·섹션 누락/예상 밖 게시/force group 충돌 차단·inode 보존6개를 확인했습니다.

게스트101 PASS, 관리/API22 PASS, UI23 PASS, lint PASS입니다. 실제 ApiResponseSerializer 출력으로 미게시 안내와 차단 안내, ACK 보호가 유지됨을 확인했습니다. 이전 UI 시험의 가짜 modal context에 type이 빠진 것은 시험 데이터만 보완했습니다.

시험 전송 도구의 단순 문자열 치환이 우연히 Base64 안의 TEMP를 바꾸는 결함은 구조화된 JSON 설정 전달로 자체 교정했습니다. 서버/게스트 제품 동작 오류가 아니며 제품 코드나 검증 보호를 완화하지 않았습니다.

실환경 API/UI/클라이언트 인수는 배포 후 별도로 기록하며 이 단계의 native/회귀 통과로 완료 판정하지 않습니다.
