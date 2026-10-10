# 렌더링 대상 자격 증명 native 독립 검증

소스8b0c180d5ad61956ebb20c0d86e1927785d3e656는 현재 committed baseline 위
target credential parser5파일만 분리한 단위다. 진행 중 AD11/13 WIP는 포함하지 않았다.
독립 candidate에서18 credential /11 driver /4 baseline inline, 합계33 tests가
통과했다. EOF 공백2개를 정리한 뒤 고정 embedded closure를 다시 생성했고
AST 동일성·bash syntax·cached diff check 및 같은33 집중 검증을 통과했다.
전체 native suite나 실제13 인수로 확대하지 않는다.

raw UTF-8 envelope SHA와 참조 kind/UUID/operation/source manifest/source7,
보호된 checkpoint cipher SHA·공개키, AEAD AAD·정확 target7 및 active ACL의
resource/share 소속을 복호화 전에 검사한다. 실제 sealed memfd의 owner/mode/
F_GET_SEALS도 확인하고 dual plaintext/artifact 입력을 거부한다.

source-only rollback은 target artifact 없이 원래 checkpoint만 요구하며 target
credential을 주입하지 않는다. runtime credential heap은 finally에서 비운다.
operator raw password/private key 파일·로그·argv는 이 단위에서 만들지 않았다.

Java b792 target envelope와 연결할 parser 소스 단계이며 실제 배포는0이다.
후속 iSCSI configfs secret 쓰기·saveconfig plaintext dump 방지, ROOT source-resume의
canonical7 무변경 및 AD/retained 복원은 별도 소스로 진행한다.
production AD/fullFour capability는false다.
최종 UI #1275는 착수 전 중단·보고·추가 지시 대기 조건을 유지한다.
