# 설계 검증 증거

- observed-state.json: 실제 VM 읽기 관측. 현재 gateway는 정상.
- ui-read-responses.json: 실제 UI 업데이트 버튼에서 발생한 API 응답의 허용 필드만 저장. 인증정보·요청 URL 없음.
- code-identity.json: 배포 클래스·내장 helper·VM helper 대조. 관리 클래스 차이는 소스 LineNumberTable.
- design_probe.py / systemvm-design-probe.json: 실제 SystemVM에서 설치 소스의 메모리 실행과 private 경로/명령 치환. 후보 planner/hook/gate는 제품 코드가 아닌 설계 스케치.
- kernel-netns-probe.json: 같은 VM 내 별도 네트워크 namespace에서만 dummy NIC·주소·경로 시험. 원래 namespace와 다름을 assert했고 child 종료 후 제거.
- nonmutation-audit.json: 원래 VM 파일 8개·주소·경로·listener·부팅·서비스 상태 유지.

실제 DHCP 서버 갱신/만료, 재부팅·신규 템플릿·클라이언트 I/O 인수는 수행하지 않았다.
제품 구현/배포/인수 성공의 증거로 전용하지 않는다. 디스크 생성·포맷·MAC 변경 없음.
