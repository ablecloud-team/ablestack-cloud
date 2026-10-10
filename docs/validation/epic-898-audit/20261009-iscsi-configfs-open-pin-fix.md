# iSCSI configfs 속성 FD 고정 소스 보완

실제6.12 configfs 메타데이터 관측에서 stat→open 사이 inode가 바뀌고,
FD를 연 상태에서는 namedstat와 fstat inode가 같으며 FD를 닫으면 다시
inode가 바뀜을 확인했다. 관측은 비밀 값을 읽지 않은 discovery_auth의
동일 kernel attribute이며 rollback된 own ACL을 직접 측정한 것으로
확대하지 않는다. Linux6.12의 always_delete_dentry/get_next_ino와 일치한다.

82cf23e2fae의 clean3 source는 O_NOFOLLOW로 속성을 먼저 열어 FD를 고정한
후 owner/mode/nlink와 namedstat의 exact identity를 검사한다. 각 쓰기 전과
readback 후 named identity 검사는 유지하며 guard를 제거하지 않는다.
실제 kernel 모양의 ephemeral pre-open inode, 열린 이후 named replacement,
untrusted owner와 기존 symlink/fakeFD9/shortwrite 회귀가 포함된
13IscsiAuth +5Inline =18 focused tests/bash가 통과했다. candidate3 SHA가
staged/committed bytes와 같으며 AD/ROOT 진행 WIP는 포함하지 않았다.

최초9a 시험은 실패 기록을 유지한다. rollback 뒤 target/backstore/listener0,
정상 QGA shutdown 뒤 ownQEMU PID 없음, NBD/mount 없음, 원본bfa와
NEW64MiB DATA d736 전체SHA 불변 및 qcowcheck errors0을 확인했다.
자격 증명은 폐기했고 로그인/RAW프로토콜쓰기0이다.

새82cf signed runtime을 적용하는 별도 실제 재시험은 승인된 동일 로컬
SPARSE/loopback 범위에서 진행한다. source18 PASS를 actual CHAP/mutual
login·I/O 성공으로 표시하지 않는다. production fullFour/AD/ROOT swap은
미완료이며 이슈를 닫지 않는다. 최종UI #1275는 미착수다.

커널 6.12 primary 근거: [configfs dentry](https://github.com/torvalds/linux/blob/v6.12/fs/configfs/dir.c), [inode 생성](https://github.com/torvalds/linux/blob/v6.12/fs/configfs/inode.c).
