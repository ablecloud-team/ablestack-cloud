# Epic #898 AD 작업의 SERVICE 중지 증빙 연결

- 소스 커밋: 0e699dac32625750f5028993b51ae323fc4b9083 (Java 10개 파일).
- 정상 Maven reactor package에서 Checkstyle 통과, 780개 테스트 / 123개 테스트 클래스 통과, 실패·오류·생략 0.
- 빌드 전후 Java 9,891개 경로·NUL·내용·NUL SHA-256: ac7271a53600fdca9adf6092a1b612192672fa14934fe62e52f1fcf54b34b0a9 (동일).
- 최종 로그 SHA-256: eb5071c34802fb82152994ffbeae0333ccd593b88718aa878ea3b8261a01ef11.
- API/schema/server/KVM/storagevm의 classes·test-classes와 5개 production jar를 6,270개 파일의 변경 불가 검증 산출물로 별도 보존했다.
- 최초 두 시도의 Checkstyle 긴 행·후행 공백 실패는 각각 로그와 소스 해시를 보존했고, 기능 변경 없이 서식 수정 후 정상 빌드를 다시 수행했다.
- 빌드 중 native AD 전체 파일, 도메인·SPN·idmap 순수 함수 AST 및 템플릿 manifest 생성·검증 소스 2개가 변경되지 않았음을 확인했다.

## 구현 및 범위

JOIN/LEAVE 요청을 SERVICE 작업 범위와 maintenance 확인에 연결하고, 중지 전의 원본 SAM·구성, 같은 부팅의 중지 증빙, 가입 전 암호화 원본을 확인하도록 보강했다. AD 외부 효과의 진행 단계를 저장하며, 외부 역동작을 입증하지 못하면 일반 구성 복원으로 완료를 가장하지 않고 RECOVERY 상태와 작업 보호를 유지한다. 비공개 인증정보는 공개 응답 허용 목록에서 제외한다.

DB 신규 테이블·컬럼 변경은 없다. 기존 config_json과 previous_snapshot_json을 사용한다. 이 커밋은 소스 검증이며 실제 13번 관리 서버에 적용하지 않았다. 현재 운영 관리 서버는 707 테스트 기준의 검증된 이전 모듈을 유지한다. 실제 AD JOIN/LEAVE producer 및 AD 의미 복원 경로는 후속 작업이 남아 있고, 운영 AD·전체 프로토콜 가능 여부는 아직 거짓이다. #902를 닫지 않으며 #1275 최종 UI 표준 작업은 시작하지 않는다.
