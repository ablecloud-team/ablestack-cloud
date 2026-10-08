# Epic #898 SERVICE 암호문 공개 증빙 검증

e10fd325b6705020d3802a6258a16729637b9fb7의 Java 3개 변경을 정상 Maven reactor package로 검증했다. Checkstyle 통과, 785개 테스트 / 123개 클래스, 실패·오류·생략 0이다. API/schema/server/KVM/storagevm 산출물 6,272개 파일을 별도로 보존했다.

- 빌드 전후 Java 9,891개 경로·NUL·내용·NUL SHA-256: fa332514284bdbe3e13cab65abd659b319c54f39fae0e7a8b0b9a994b8274251 (동일).
- 로그 SHA-256: baee800f44ba5565a1994902ce50b1e5f804883797039ed3168f6e5b5ad791f6.
- native AD 파일·공개 순수 함수 AST 3개 및 템플릿 producer/validator 2개의 전후 해시 동일.
- SERVICE 신원 export가 실제 kind, 작업 범위 4개, 원본 구성 SHA, 해독한 cipher SHA 및 native checkpoint record SHA를 반환해야 관리 서버가 받아 저장한다. capsule의 정확한 schema·scope·nonce·wrapped key 모양도 확인한다.
- 누락·잘못된 범위·원본·cipher·문자열 success와 metadata DAO false를 정상 테스트에서 거부했다. 비공개 신원 내용은 공개 증빙으로 출력하지 않는다.

신규 DDL 0, 실제 AD 외부 효과·ROOT 교체·클러스터 배포 0이다. typed JOIN/LEAVE producer와 AD 의미 복원, API·실제 UI·Windows 인수는 남아 있다. #902를 닫지 않으며 #1275 최종 UI 표준 작업은 시작하지 않는다.
