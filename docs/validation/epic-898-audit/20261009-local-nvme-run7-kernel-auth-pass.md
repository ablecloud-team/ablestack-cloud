# 로컬 NVMe run7 실제 DH-HMAC-CHAP · mutual · reconnect 인수 결과

2026-10-09 04:35 KST 에 승인된 자기 SPARSE/noNIC fixture 의 실제 kernel 6.12 NVMe/TCP 인증 matrix 와 정상 cleanup 을 완료했다. **one-way · mutual positive/RW/fresh reconnect 및 valid wrong host · wrong controller · no credentials negative 3개 PASS**다. 이전 run5 첫 apply 원인 미확정과 run6 reconnect 읽기 미완료 증거는 보존한다. 이 결과는 Cloud/API/UI · all4 원자 변경 · ROOT/AD · 정식 release 완료가 아니다.

## 고정 계보와 실제 신원

| 항목 | run7 값 |
| --- | --- |
| base image | fc3e7f5c134828288ac89dc98300533ffcb909ff / 원본 bfa006c0d55091ffa24c12d3c5f261866394b8f0c09408fd1c2439861df3de7c |
| runtime source | 30c9820f848f4707b1f6d1dcb69c1c989f883a94 |
| installed CLI | 693354fb1c7ca02ecdb325a49484bb5538a03fb3e4cd9416bf18b2e7e354aaa5 |
| nvme_cleanup.py | 308d8b84ffb0d68f652594f1ad7a931fe80d119c13c9a438a7b1b19b045cb17a |
| signed archive | 16622013e7a87f9172e83a882e7ae499fc9149c42d07e80d8614e8a04c6564b5 |
| signed manifest | ef1a9808fe5657a34b52e8f4697fcf975213baf12628549d345bce85824109db |
| signing helper / key scope | d8201f42765 / sealed RAM test key, 기존 signed 30c bundle 재사용 |
| runtime version | epic898-local-30c-nvme-auth-20261009 |
| updater | 161c4726fdfe53e9eba51b635862a4f9dceb000acf7a2fb165541710d1b0e131 |
| kernel / boot | 6.12.95+deb12-amd64 / 835215f7-ed12-4790-9975-a124695b5a6a |
| RAW | sda / 67,108,864 B / serial a4b1fbac5ab940b4b0e5 |
| ROOT | sdb6 / ancestor serial 5a90d4777d914e9887f6 |

Root full copy 와 DATA 는 SPARSE metadata/backing-none 이다. 이번 실행은 새 디스크와 format 0 이다. protected template 는 fc3e 계보를 유지한 hybrid copy 로, 새 최종 artifact 라벨을 붙이지 않는다. inactive copy 의 정상 updater begin/transfer/finalize/verify/preflight/activate/readback 과 boot 후 signedRuntime/installedFiles/entrypoints verified 를 확인했다. QGA 준비는 100.27 초였다.

실제 HOST_AUTH=y / TARGET_AUTH=y 및 nvme-tcp/fabrics/auth module, 두 fabrics 인증 옵션을 먼저 확인했다. device 이름이 이전 boot 와 바뀌어 serial/size/ROOT ancestor 로 다시 해석했다. RAW 는 unmounted/no partition/빈 signature exit 2 였고 기존 두 test window 를 보존했다.

## 실제 인증과 bounded publication

정상 pinned CLI 와 native writer 경로로 자기 NQN/host/NSID 1/127.0.0.1:4420 만 적용했다. 이미 존재하는 실제 lo 주소를 읽기 전용으로 확인했고 NIC/alias/MAC inventory 를 바꾸지 않았다. allowAnyHost=false 와 정상 managed credential vault 를 사용했다. helper 직접 적용이나 replay env 우회는 없다.

initiator 는 /dev/nvme-fabrics 에 RAM options 를 전달했다. 키는 형식 · 길이 · CRC 를 검증한 유효한 DHHC 값이며 argv · URL · 원문 로그 · credential hash 를 남기지 않았다. 14.5 초 공유 deadline / process alarm 15 초 안에서 live controller · NQN/host/cntlid · NSID 1/64 MiB · sysfs identity · dev node/rdev 를 확인했다. 자기 controller/namespace/dev node 의 완전한 제거 후 다음 fresh context 로 넘어갔다. generic disconnect-all 과 자동 connect retry 는 0 이다.

| stage | 실제 결과 |
| --- | --- |
| one-way normal CLI apply | success / applied 1 / TCP 4420 true |
| one-way positive | login true / 4,096 B write·fsync·read / publication 6.1 ms |
| one-way fresh context | login·동일 패턴 읽기 true / publication 52.9 ms |
| valid wrong host key | login false / errno 129 EKEYREJECTED / fresh auth-failure boolean true / I/O 0 |
| no credentials | login false / errno 126 ENOKEY / fresh auth-failure boolean true / I/O 0 |
| mutual normal CLI apply | success / applied 1 / TCP 4420 true |
| mutual positive | login true / 4,096 B write·fsync·read / publication 53.1 ms |
| mutual fresh context | login·동일 패턴 읽기 true / publication 3.0 ms |
| valid wrong controller key | login false / errno 129 EKEYREJECTED / fresh auth-failure boolean true / I/O 0 |

성공 context 는 4개, 의도한 negative 는 3개다. negative 를 네트워크 unavailable 과 합치지 않고 errno/auth boolean, 직전·직후 positive 와 같은 alive endpoint 를 대조했다. kernel raw log 는 출력하지 않았다. 2회 정상 apply 는 one-way/mutual stage 이며 실패 재apply 는 0 이다.

mutual 이후 dhchap_key / dhchap_ctrl_key 모두 configured=true, protected managed value 와 RAM 안의 equality boolean true, 열린 FD 와 named inode 일치 true 를 확인했다. 값·credential 파생 hash 는 기록하지 않았다.

## 데이터 및 정상 정리

이번 총 쓰기는 offset 65536 의 같은 공개 4,096 B 패턴을 두 번 기록한 8,192 B 다. 기존 iSCSI offset 4096 window 와 나머지 전체 RAW zero 는 보존됐다. 새 공개 data pattern SHA 는 c01934b950607a25cd2c50a17db184a2a518761e3d8c347f4edc4eca8c86b364 이며 credential hash 가 아니다.

모든 positive 후 own controller · namespace · dev node 완전 제거를 확인했다. matrix 뒤 controller 0 / TCP established 0 의 known-idle 상태에서 정상 CLI empty owned desired 를 1회 적용했다. subsystem/namespace/host/port/controller/listener/vault entry 모두 0, vault root 0600 을 확인했고 credential RAM 은 폐기했다. foreign 객체를 건드리지 않았다.

정상 QGA shutdown 후 PID 351155 / start tick 5554153 는 사라졌다. kill 은 0 이며 원래 reconcile unit checksum 2e13eb55a8a02ef41faf1edec1b3f294d590470760f3f8c9504175c44de7afb2 를 복원하고 backup 을 보존했다. NBD7 · 자기 mount 0, Root/Data qemu check exit 0 이다.

원본 bfa SHA 는 동일하다. DATA container SHA 는 실행 전후 716075bd8b67e95b1809e339f7b18c5b6ba3f77ab534252ab39199a7475debdc 로 동일하다. 이미 있는 window 에 같은 패턴을 덮어쓴 결과이며 실제 쓰기 0 을 뜻하지 않는다. outside-window 보존은 전체 RAW 읽기로 따로 확인했다.

## 증거와 남은 범위

/root/work/epic898-preparation/local-iscsi-auth-9a83/nvme-plan 의 run7-offline-normal-signed-update-proof.json, run7-modules-options-readback-preflight-proof.json, run7-publication-source-validation.json, run7-local-nvme-auth-execution-proof.json, run7-fresh-mutual-attributes-and-pattern-proof.json, run7-normal-owned-target-cleanup-proof.json, run7-cleanup-and-preservation-proof.json 에 기록했다. publication synthetic 14 tests 는 별도이고 actual 결과를 대신하지 않는다.

Cloud 13 · 원래 VM · partial 021b · Client OOBE 변경은 0 이다. 실제 all4 coordinator/crash/recovery, Cloud external client · ROOT/retained · AD/Windows · stable signing · 새 단일 source image/RPM 및 최종 UI 11탭 인수는 남아 있다. 최종 UI 1275 는 마지막 STOP 경계 전 미착수다. 기존 false/source-only 및 run4/run5/run6 역사적 proof 는 덮어쓰지 않았다.
