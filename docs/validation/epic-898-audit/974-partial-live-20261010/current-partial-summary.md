# #974 기존 partial SPARSE 10 TiB — 현재 읽기 관측

2026-10-10 **05:20:16 / 05:20:26 KST**, 10.512초 간격 두 샘플을 읽었다. VM51/i-2-51-VM, UUID e38ab665-a5db-44a0-90f6-5ed06ee5df9f @13.2는 libvirt Running이며 정확 기존 DATA 021b0cac-327e-444b-9cff-3c9da0d1f539만 관측했다. 이 결과는 **완료가 아니며** 새 exact SPARSE 10 TiB 인수도 아니다.

| 현재 공개 항목 | 실제 결과 |
| --- | --- |
| 장치·identity | /dev/sdd / serial021b0cac327e444b9cff / 10,995,116,277,760 B / XFS UUID f8c35b5d-3cb3-4a7f-82a8-06dce2c2108d |
| ROOT 제외 | ROOT ancestors /dev/sda6 → /dev/sda, 대상과 겹치지 않음 |
| journal | TIMED_OUT_PENDING_RECONCILE / formatStarted=true, 완료 승격0 |
| formatter | journal formatter PID78663 존재하지 않음 |
| 기존 guard 번호 | **guest PID1960 존재하지 않음**. host PID1960의 상태나 그 PID의 동일성을 관측한 것이 아님 |
| filesystem process | guest comm 기준 mkfs.xfs/mkfs.ext4/xfs_repair 0개. full cmdline/argv는 읽지 않음 |
| mount | 대상·같은 FS UUID mount0, mount/umount 호출0 |
| header | first64KiB SHA가 두 샘플 및 이전 partial과 정확히 같음. bytes 내용을 출력하지 않음 |
| write counters | 두 샘플 및 이전 partial의 [4,0,4174337,645120]과 동일 |
| host physical | QCOW2 / virtual10TiB / no backing / actual allocated1,807,904,768 B(약1.68 GiB) / inode1231335 |

이전 xfs_repair -n 진단은 mkfs-in-progress header·filesystemHealthy=false를 확인했었다. 현재는 새 repair/health probe를 하지 않았고 header/write counter가 같은 점만 확인했다. UUID가 보이거나 프로세스가 없다는 이유로 filesystem healthy/FORMAT_COMPLETE를 판단하지 않는다. 완료 중이라는 진행 변화도 이번 10.5초 관측에서는 확인하지 못했다.

## 읽기 효과 검토와 한계

기존 inflight helper는 /proc full cmdline/argv를 출력하고 기존 diagnostic helper는 xfs_repair -n와 자신의 진단 child 신호를 사용하므로 이번 scope에서는 실행하지 않았다. 새 공개 reader는 lsblk/findmnt·선별 format journal·/proc comm/stat/status/io·sysfs block counter·O_RDONLY header64KiB 및 qemu-img info --force-share만 읽었다. 원 formatter/guard에 신호0, xfs_repair/mkfs/mount/attach/detach/service/VM 변경0이다. F1 VM54와 Chrome1588257748에 QGA/조작0이다.

첫 직접 stdin SSH transport는105초 timeout/공개결과0이었다. 그 실패를 guest format 상태로 분류하지 않았다. local PTY controller의 누락 import는 SSH 실행 전에 교정했다. 기존 성공 패턴의 getpass+stdlibPTY/ssh -n으로 정확 VM51 identity를 먼저 확인하고 같은 VM의 두 샘플을 확보했다. credential은 RAM prompt만 사용했고 파일/argv/environment/log에 저장하지 않았다. 원 로그·private 값·fullcmdline 출력0이다.

Root 요청대로 추가 관측·resume·repair·format은0으로 종료한다. 기존 partial021b를 보존하고 새 exact10TiB의 pool/factor human 선택 pending과 분리한다. 이전 THIN 원본·capacity override/threshold 변경0, Source/Git/GH/댓글/close0, #1275 최종 작업 미착수다.

## 저장소 copy용 실근거 목록

- live-public-readonly.json: current host/guest 2샘플·동일 VM/장치/공개 journal·process metadata·actual allocated capacity.
- comparison-public-proof.json: 이전 partial header/write counter 및 두 샘플 비교, host/guest guard 의미·미완료 판정.
- host-identity-only-proof.json: 정확 i-2-51-VM/UUID/Running, QGA0/disk writes0.
- guest-public-metadata-reader.py / host-public-reader.py / run-public-reader-pty.py: 실행한 공개 읽기 helper.
- 이전 기준 acceptance-audit/sparse-partial-xfs-readonly-diagnostic-summary.json: 기존 no-write 진단의 mkfs-in-progress/미완료. 새 진단을 반복하지 않았다.

현재 API 작업 성공이나 새 UUID/새 format을 추가하지 않는다. 공개 helper/proof SHA는 comparison-public-proof.json에 보존했다. 별도 Root GH #974 진행 댓글 초안은 issue974-comment-draft.md의 두 문단이다.
