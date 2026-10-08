# 로컬 NVMe run5 첫 apply 미완료와 정상 정리

2026-10-09 03:40 KST 에 자기 SPARSE fixture 의 run5 를 정리했다. exact source 793003af 를 normal signed updater 로 적용하고 fresh kernel · RAW identity · readback 을 확인했으나 첫 정상 NVMe apply 1회가 exit 1 로 끝났다. structured result 와 TCP 4420 listener 는 없었다. 실제 인증 연결과 RAW 쓰기는 0 이며 자동 재apply 는 하지 않았다.

## 계보와 관측 범위

- runtime source: 793003af2135e9a65b4bc014846ca1db201e0dd0
- signing wrapper: pinned d8201f42765 / sealed memfd, private key 파일 없음
- CLI: 7ee207c56377cb0d31fba4072b1c83b6cd2e104cc3a18676ec40a579eaa0c826
- nvme_cleanup.py: fd712436ce2cacec6f63bc6473c938766ce47d9e20d3cc3811f3285279b980ee
- signed archive: ab385be4ffbe1fed10048df0d70a13757fbc480eff1f6ea70e628d91a5d8e620
- signed manifest: 71122fdaf0635b14862e7e733cf0bbc69834236b55947c83b2d3f61d6c07a162
- base template 는 fc3e 이며 이 copy 는 hybrid local fixture 다. 최종 artifact 로 재라벨하지 않는다.

정상 updater 의 begin · finalize · verify · preflight · activate · readback 과 boot 후 세 verified boolean 이 true 였다. kernel 6.12.95 / HOST_AUTH=y / TARGET_AUTH=y, noNIC / lo ONLY, ROOT sda6 와 exact RAW sdb 64 MiB serial 분리 및 빈 signature 를 확인했다. 기존 iSCSI window 는 보존됐다.

실제 apply 에 credential RAM 입력은 있었다. 그러나 callback 이 stderr 를 폐기했으므로 실제 예외 문자열은 복구할 수 없다. source 원인을 아직 확정하지 않으며 이 결과를 인증 rejection 으로 부르지 않는다. 이후 callback 은 RAM 안에서 고정 예외 class · 알려진 literal · traceback 파일/line 만 추출하고 원문·입력·credential 파생값은 기록하지 않는다.

## 실패 이후 읽기 전용 대조와 정리

fresh inventory 에 subsystem · host · port · controller 는 모두 0 이었다. canonical · ownership receipt · NVMe vault 도 없었다. configfs 및 canonical parent 는 root:root 0755, native writer lock 은 root 0600 이고 FD holder 는 0 이었다. PID 916 은 start tick 7635 의 storage-monitor 로 확인했고 apply 프로세스가 아니었다.

정상 QGA shutdown 후 자기 QEMU PID 335530 / start tick 5217485 는 사라졌다. kill 은 사용하지 않았다. 원래 reconcile unit checksum 2e13eb55a8a02ef41faf1edec1b3f294d590470760f3f8c9504175c44de7afb2 를 복원했고 backup 을 보존했다. NBD7 · 자기 mount 는 0, Root/Data qemu check 는 exit 0 이다.

원본 fc3e SHA bfa006c0d55091ffa24c12d3c5f261866394b8f0c09408fd1c2439861df3de7c 와 RAW container SHA 33bd81f1aa5da696cc359446a862a1c4f521f73135b6bd839cc6736fb6fec793 는 전후 동일하다. 따라서 offset 4096 의 iSCSI 패턴과 아직 쓰지 않은 offset 65536 window 를 보존했다. Cloud 13 · 기존 VM · partial · OOBE 변경은 0 이다.

증거는 /root/work/epic898-preparation/local-iscsi-auth-9a83/nvme-plan 의 run5-signed-runtime-build-proof.json, run5-offline-normal-signed-update-proof.json, run5-modules-options-readback-preflight-proof.json, run5-local-nvme-auth-execution-proof.json, run5-first-apply-failure-readonly-inventory.json, run5-writer-and-source-metadata-readonly.json, run5-failure-cleanup-proof.json 이다.

native full-entry synthetic 검증과 새 pin 을 기다린다. 실제 positive · wrong host · mutual · wrong controller · no credentials · reconnect I/O 및 정상 owned deletion 은 아직 미검증이다. 기존 run4 선행 결과와 iSCSI run3 PASS 를 유지하며 전체 all4 인수로 확대하지 않는다. 최종 UI 1275 는 미착수다.
