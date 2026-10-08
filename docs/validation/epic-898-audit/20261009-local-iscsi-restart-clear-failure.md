# 실제 iSCSI 재시험의 targetctl clear 원인 확인

82cf의 서명 런타임을 자기 SPARSE ROOT 복사본에 정상 updater로 먼저 적용했고
fresh guest READBACK와6.12 kernel/lo-only/RAW serial·size/ROOT 제외를 확인했다.
old9a 자동 재시도를 막기 위해 자기 시험 copy의 reconcile만 임시 mask했다.
부팅마다 disk 이름을 다시 serial로 해결했고 과거 /dev/sda를 재사용하지 않았다.

정상82cf CLI 첫apply1회는 configfs attribute 신원검사를 지나갔지만 TCP3260
readiness에서 실패했다. 실제 설치 rtslib-fb-targetctl의 ExecStop=targetctl clear,
ExecStart=targetctl restore와 apply시 stop→start→저장 config 없음 로그를
확인했다. 이후 target/backstore/listener/auth4attribute가 모두 없었다.
추가 generic saveconfig 두 경로도 없었고 운영 비밀·secretargv0이다.
CLI의 postapply 서비스 재시작이 새 live configfs를 지우는 경로를 보완한다.

속성 함수의 readback 조건 뒤에 도달한 것은 소스 제어 흐름의 증거이며, 서비스
재시작 뒤 fresh 속성이 없으므로 실제 CHAP 인증 성공으로 표시하지 않는다.
login/RAW protocol write0, 자동재apply0이며 자격 증명을 폐기했다.

자기QEMU310547는 정상QGA shutdown 후 종료, NBD/mount0 및 원본bfa /
NEW64MiB DATAd736 전체SHA 불변, qcowcheck errors0을 확인했다.
제품 private vault와 canonical은 시험 copy의 제품 상태이며 추가 genericdump
부활이나 direct helper 우회로 시험을 성공시키지 않는다.

확정 증빙은 run2-readonly-service-clear-and-configfs-inventory-proof.json과
run2-cleanup-and-original-preservation-proof.json이다. 새 native 수정 pin 뒤
별도 실제 CHAP/mutual/negative/I/O 인수를 계속한다. Cloud/13/원본/partial/
OOBE 변경0이며 최종UI #1275는 미착수다.
