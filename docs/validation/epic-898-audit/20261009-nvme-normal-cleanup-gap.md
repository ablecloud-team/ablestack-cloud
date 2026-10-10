# NVMe 정상 삭제·정리 계약의 선행 P1

승인된 local NVMe 계획의 target 생성 전에 source b6의 정상 CLI를 검토해
empty/disabled desired가 namespace/subsystem을 제거하지 않는 결함을
확인했다. cleanup은 RENDERED_REPLAY 분기에만 있어 정상 제품 API/CLI
삭제 후 kernel 객체가 남을 수 있다. 담당자들이 실제 source 조건을 대조했다.

제품 경로의 protected prior-canonical owned NQN/namespace/host/port에만
정상 cleanup을 수행하고 다른 참조·foreign 객체·DATA를 보존하는 작은
CLI/test 단위를 먼저 구현한다. rendered replay 환경 변수를 시험에 넣어
우회하거나 수동 foreign cleanup으로 기능 성공을 주장하지 않는다.

로컬 ownSPARSE 복사본/noNIC QEMU를 준비하며 QGA·6.12·fabrics allowed-options
읽기만 수행한다. target 생성·secret전달·NVMe DATA쓰기0으로 유지한다.
원래 bfa와 current64MiB DATA33bd/iSCSI패턴4096은 보존한다.
normal cleanup source pin 뒤 동일 승인 계획에서 실제 인증·I/O를 재개한다.

이sourcegate는 actualNVMe 인증실패와 다르다. 아직 연결·인증 시도를 하지
않았고 Cloud13/원본/partial/OOBE 변경0이다. 최종UI #1275는 미착수다.
