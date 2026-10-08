# Epic #898 다음 관리 모듈의 배포 라이브러리 연결 검토

정상 792 테스트 소스 1849d3496d0c6cb22f9edc3a707a61967cfc4b3f의 immutable 모듈을 기준으로 기존 검토된 707 변경 목록과 이후 main Java 변경을 결합했다. 검토 후보는 클래스 200개·리소스 8개, 총 208개 entry이며 새 Runtime outer·$1·$RuntimeResourceScope 세 파일을 모두 포함한다. 이전 Runtime 두 파일을 유지하는 운영 조합과 구분한다.

13번 관리 서버의 실제 라이브러리는 읽기 전용으로 복사했다. cloudstack JAR SHA-256은 ade51548bcdce95d6f9783458da009432c6f7f0904618b742000176c6f6443e5, 9개 라이브러리 파일의 전송 archive SHA는 0855da9a4ffdbb4a0aec08adfb28da75281253dac62910bc11708dac1fe21ef6이다.

- 실제 배포 라이브러리와 후보 클래스의 URLClassLoader에서 클래스 200개의 선언 메서드·생성자·필드 타입을 로드했다. 모두 통과했다.
- 후보의 constant-pool 메서드·인터페이스 메서드·필드 참조 9,566개를 선언·상속 descriptor로 대조했다. 누락 0이다.
- 클래스 초기화·접근 권한 판정·Spring 실제 서버 기동·게스트 효과·API/UI 인수는 이 검사에 포함하지 않았다.

검토 payload SHA-256은 ba3d5db2d35b90b7697f079e477e2260680ee204fefcd419408e78a9556ca9ba다. 현재 readyToDeploy=false이며 서버 파일 변경·재시작은 0이다. native bootstrap/JOIN/LEAVE·의미 복원 소스 완성과 단일 source 산출물 정합성을 확인한 뒤 정상 모듈 반영 및 실제 API/UI 회귀를 진행한다. 임시 검토 후보를 최종 배포나 전체 인수 완료로 표시하지 않는다.
