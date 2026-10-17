# 로컬 NVMe run6 부분 실증과 run7 publication 검증 계획

run6 는 실제 one-way 인증·4 KiB write/fsync/read 및 정상 owned cleanup 을 확인했다. fresh reconnect login 은 성공했지만 namespace publication 관측 errno 2 로 읽기 검증을 끝내지 못해 전체 matrix 는 미완료다. wrong host · no credentials · mutual · wrong controller 는 아직 0 이다. 제품 인증 실패로 확대하지 않고, publication race 는 가설로 유지한다.

## 실제 run6 증거

- source 30c9820f848f4707b1f6d1dcb69c1c989f883a94 / sealed signing helper d820
- CLI 693354fb1c7ca02ecdb325a49484bb5538a03fb3e4cd9416bf18b2e7e354aaa5
- nvme_cleanup.py 308d8b84ffb0d68f652594f1ad7a931fe80d119c13c9a438a7b1b19b045cb17a
- signed archive 16622013e7a87f9172e83a882e7ae499fc9149c42d07e80d8614e8a04c6564b5 / manifest ef1a9808fe5657a34b52e8f4697fcf975213baf12628549d345bce85824109db
- base image fc3e / kernel 6.12.95 / noNIC / lo ONLY, hybrid copy 표기 유지
- normal signed updater 와 fresh readback 세 verified boolean true, RAW 64 MiB serial·ROOT 제외·HOST_AUTH/TARGET_AUTH 선행 PASS

첫 정상 CLI apply 는 success / applied 1 / TCP 4420 listening true 였다. host key 의 fresh managed-value equality 와 미설정 controller-key slot 을 확인한 뒤 실제 one-way login, offset 65536 의 4,096 B write/fsync/read 를 통과했다. 기존 offset 4096 iSCSI 패턴은 보존됐다. 정상 controller 삭제도 성공했다.

두 번째 fresh context 는 loginAccepted=true 였으나 FileNotFoundError / errno 2 가 발생했다. 당시 정확 파일명을 남기지 않아 원인을 확정하지 않는다. 실제 multipath=Y / udev active, 두 번째 host controller 생성 후 약 7.6 ms 에 harness finally 의 제거 기록이 있었으며 현재 host controller · block · dev node 는 0 이었다. 이 관측은 publication 타이밍 가설과 양립하며 제품 auth rejection 증거가 아니다.

## 정상 cleanup 과 데이터 보존

known-idle TCP established 0 뒤 정상 pinned CLI 의 empty owned desired 를 1회 적용했다. subsystem · namespace · host · port · controller · listener · vault entry 가 모두 0 이 됐다. protected vault root 0600 은 유지됐다. env replay 우회·foreign 삭제·자동 재apply 는 없다.

정상 QGA shutdown 후 PID 341398 / start tick 5321558 는 사라졌으며 kill 은 0 이다. 원래 reconcile unit SHA 2e13eb55a8a02ef41faf1edec1b3f294d590470760f3f8c9504175c44de7afb2 를 복원했고 NBD7 / 자기 mount 0, Root/Data qemu check exit 0 을 확인했다.

원본 fc3e SHA bfa006c0d55091ffa24c12d3c5f261866394b8f0c09408fd1c2439861df3de7c 는 동일하다. DATA container 는 승인된 4 KiB 쓰기로 SHA 716075bd8b67e95b1809e339f7b18c5b6ba3f77ab534252ab39199a7475debdc 로 바뀌었다. 이전 33bd 와 같다고 표시하지 않는다. 두 공개 test window 의 패턴과 그 밖 전체 RAW zero 를 확인했다.

## run7 test-only harness 와 실행 전 gate

제품 auth 코드는 바꾸지 않는다. scratch driver 는 단일 connect 뒤 14.5 초 공유 deadline / process alarm 15 초 안에서 live controller · NQN/host/cntlid · NSID 1/64 MiB · sysfs identity · dev node/rdev 일치를 기다린다. pending publication 은 추가 connect 없이 관측하며 inode 교체, foreign tuple, fake cntlid, ROOT 경로, 잘못된 device number 는 I/O 전에 거절한다.

정상 삭제는 자기 controller identity/cntlid 를 재확인하고 old controller · namespace · dev node 가 모두 사라져야 완료로 판정한다. 남은 old node 가 있으면 다음 fresh connect 를 실행하지 않는다. generic disconnect-all 은 사용하지 않는다. 실제 생성된 public sysfs/dev node 에만 접근하고 raw error/key/payload 내용을 출력하지 않는다.

순수 observer 10 tests 와 runnable driver 통합 model 4 tests, 총 14 tests 가 통과했다. delayed dev node / 임시 missing attributes / controller·namespace 교체 / fake cntlid / foreign NSID·size·ROOT path / device number 불일치 / bounded deadline / old dev node 제거 대기 / unknown removal / 새 connect collision 차단을 확인했다. 이는 synthetic 검증이며 실제 reconnect/mutual 인수를 대신하지 않는다.

계획 작성 당시 actual run7 은0이었고, 이후 parent가 이 고정범위의 실제run7을승인했다. 확정결과는새별도proof에기록한다. 동일 SPARSE Root/DATA 를 사용하되 새 baseline 716075bd 를 고정한다. 이미 채워진 NVMe window 를 blank 로 기대하거나 초기화하지 않고 같은 공개 패턴의 bounded overwrite 만 사용한다. 기존 iSCSI window 와 window 밖 영역은 보존하고 신규 총 쓰기는 1 MiB 이하로 제한한다. one-way positive/fresh reconnect → valid wrong host/no credentials → mutual positive/fresh reconnect → valid wrong controller → known-idle owned cleanup 순서다. partial 이면 자동 retry 없이 상태를 기록하고 정상 자기 fixture 정리만 수행한다.

증거와 scratch 는 /root/work/epic898-preparation/local-iscsi-auth-9a83/nvme-plan 에 있다. run6-local-nvme-auth-execution-proof.json, run6-reconnect-publication-readonly.json, run6-normal-owned-target-cleanup-proof.json, run6-cleanup-and-preservation-proof.json 과 nvme_publication.py / ram_nvme_client_run7.py / test_nvme_publication.py / test_nvme_runnable_driver.py 를 구분한다.

Cloud 13 · 원본 VM · partial · Client OOBE 변경은 0 이다. 실제 all4 · ROOT · AD · production signing · Cloud/API/UI 인수 및 최종 UI 1275 는 별도이며 최종 UI 는 미착수다. run4/run5 역사적 proof 를 덮어쓰지 않았다.
