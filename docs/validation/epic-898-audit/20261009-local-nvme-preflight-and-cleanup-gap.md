# 로컬 NVMe run4 선행 검사 결과

2026-10-09 03:20 KST 기준으로 자기 SPARSE fixture 의 선행 검사와 정상 정리를 완료했다. 실제 NVMe target 생성, 인증 연결, RAW 쓰기는 0 이다. parent 가 지시한 제품 cleanup 수정 pin 뒤 실제 인증 matrix 를 재개한다.

## 실제 관측

- kernel 6.12.95, HOST_AUTH=y / TARGET_AUTH=y 와 fabrics 인증 옵션 2 개를 확인했다.
- 정상 signed b6 readback 의 세 verified boolean 은 true 이며 CLI cac4bb0 계보가 일치한다.
- QEMU PID 328499 / start tick 5111550, noNIC / lo ONLY, QGA 준비 90.28 초를 확인했다.
- ROOT sda6 / serial 5a90d4777d914e9887f6 와 RAW sdb / 67,108,864 B / serial a4b1fbac5ab940b4b0e5 가 분리된다. RAW 는 unmounted / partition 없음 / 빈 signature exit 2 이다.
- 기존 NVMe subsystem, host, port, controller 및 iSCSI target 은 모두 0 이다.
- offset 4096 의 기존 iSCSI 패턴과 그 window 밖 전체 zero 가 보존된다.
- RAM driver 의 형식, 길이, CRC 선행 검사를 통과했으며 잘못된 local 입력 4 종은 kernel 전달 전에 거절된다. 실제 인증 연결은 아직 0 이다.

## 다음 gate 와 정리

signed b6 정상 legacy NVMe CLI 에는 empty/disabled desired 의 owned namespace/subsystem 제거 계약이 없다. native 담당도 이 source 경계를 확인했다. parent 지시로 첫 target 생성 전에 멈췄으며 제품 경로를 우회하지 않았다.

정상 QGA shutdown 후 자기 PID 는 사라졌다. inactive copy 의 reconcile unit 은 원래 checksum 2e13eb55a8a02ef41faf1edec1b3f294d590470760f3f8c9504175c44de7afb2 로 복원했고 backup 을 남겼다. NBD7 / 자기 mount 는 0, Root/Data qemu check 는 exit 0 이다.

원본 fc3e SHA bfa006c0d55091ffa24c12d3c5f261866394b8f0c09408fd1c2439861df3de7c 와 RAW container SHA 33bd81f1aa5da696cc359446a862a1c4f521f73135b6bd839cc6736fb6fec793 가 전후 동일하다. 새 디스크, format, Cloud 13, partial, Client OOBE 변경은 0 이다. Cloud/UI/production all4 완료와 구분한다.

proof 경로는 /root/work/epic898-preparation/local-iscsi-auth-9a83/nvme-plan 이다. run4-qemu-start.json, run4-initial-qga-kernel-and-disks-proof.json, run4-modules-options-readback-preflight-proof.json, run4-ram-driver-preflight-proof.json, run4-preflight-cleanup-proof.json 을 보존했다. 과거 iSCSI 결과는 덮어쓰지 않았다.
