# 원 SOURCE 복구의 공개 예외 종류 보존

원 df11 정상 UI 복구가 native import-local-source에서 실패했지만 관리 서버가 기존 공개 예외 종류를 일반 오류로 덮는 문제를 보완했다. 소스 053070은 LOCAL_SOURCE_CHECKPOINT_REJECTED의 고정 세 필드만 엄격하게 확인하고 허용된 Python 예외 종류를 nativeReason으로 보존한다. 임의 메시지·중첩 자료·키·payload·중복/추가 필드·잘못된 JSON은 일반 오류로 유지한다.

실패와 성공의 의미 및 원 native19ad·SOURCE capsule·writer lease·STOP/resume 절차는 그대로다. 새 API·UI·DB 스키마는 추가하지 않았다. 실제 오류 메서드나 줄 번호까지 확인할 수 있다는 뜻은 아니다.

집중 검증은 50 tests/5 suites이고, 정상 모듈은 931 tests/126 suites, failure/error/skipped 0 및 Checkstyle/reactor 성공이다. Java 9,905개와 code archive 15,538개(파일·symlink 포함)를 대조했고, 실제 빌드 HEAD dc6a의 문서 6개 추가를 코드와 구분했다. 빌드 전후 코드·tracked bytes 및 archive 차이는 0이며, 출력 6,315개를 별도 보존했다.

정상 산출물을 live 6b 관리 배포와 대조한 server delta는 14개 클래스다. API/schema/KVM/provider의 delta는 0이다. 내부 클래스의 debug line table과 실제 행동 변경은 후속 ABI/opcode 검사로 구분한다. 새로운 관리 배포와 원 df11 UI 복구 결과는 아직 없으며, 검증 후 제한 배포와 정상 UI 복구 한 번으로 확인한다.

최종 UI 표준 정리 #1275는 미착수다.
