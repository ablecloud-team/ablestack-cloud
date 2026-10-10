# 공통 렌더링 복구 및 유지보수 소스 검증

소스 b792bb82de75caf48106b90ef50a9fdf7535d77c의23 Java 변경을 분리해 pin/push했다.
정상 Checkstyle server/KVM/storagevm -am reactor는107 classes /638 tests,
failures/errors/skipped0이다. Java9871의 NUL SHA
564f8e2e69a099dc9cfa7116d403c29e6abfa35a29e77809175acf8727f62c5a가
전후 같았다. 이전614 검토본의 실제 callback snapshot 덮어쓰기 및 native
commit→DB receipt 저장 중단 위험은 이 후속 회귀와 fresh native/rendered 판정으로
보완했다. 실패·중단된 이전 reactor 로그는 성공 증거로 사용하지 않는다.

prepare callback은 새 rendered checkpoint를 저장한 후 snapshot을 다시 읽어
보호된 복구 참조를 유지한다. native target commit이 관찰되면 이전 DB phase만으로
rollback하지 않고 forward finalization을 수행한다. preactivation 취소는 실제
source unchanged를 검증해 복원한다. 완료된 동일 actor/fingerprint 요청은 이미
consumed token이나 expired profile 검사 전에 원래 공개 결과를 반환한다.
신규 승인 유지보수 요청의 token·plan·binding 검증은 hold 전에 수행한다.

target credential은 공개 참조와 scope-bound 암호화 envelope로 구분했다.
Imported profile 재승인은 baselineImported를 유지하며 handler source loss를
master OFF로 우회하지 않는다. direct42 및 Java→native decrypt fixture2는
전체 reactor/실환경 인수와 구분한다. DB DDL 변경은0이다.

189 entry payload SHA5a20dd3604298cc5b1ff2328a41ef294e08a26c774a0a8f69add208bf9f45dce와
다섯 모듈 classes/test-classes를 backend-b792bb82de75에 immutable 보관했다.
bootstrap SHA161c4726...이다. 이 payload는 실제 배포하지 않았다.
Native target credential parser·완전한 ROOT phase·retained rollback·AD 후속과
실제 all4 인수는 남아 있다. 실제 render import/ROOT swap0, productionFullFour false다.

현재13번 실제 관리는 namespace1dad/e99a JAR와 legacy Runtime family 조합이다.
최종 UI #1275는 착수 전 중단·보고·추가 지시 대기 조건을 유지한다.
