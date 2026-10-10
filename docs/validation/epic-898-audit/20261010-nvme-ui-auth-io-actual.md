# NVMe 정상 UI·DHCHAP 및 실제 I/O 검증

정상 UI 준비 joba8c3abf0-ae18-4a14-ba45-b215d3f7b8f0는 status1/result0/runtimeok였다. KERNEL_NVMET/tcp/validateonly=false로 실행했다. health/inventory의 초기 CAP=false는 cached 응답과 구분했고, 실제 설치 CLI9b1의 기능 검사에서 kernelTarget/configfsHost/DHCHAP/DHCHAPCtrl 모두 literaltrue를 확인했다. 임시 samplehost 생성·제거를 무효과 조회로 표시하지 않으며 전후 부재를 확인했다. 정상 UI 갱신 후 DHCHAP 지원과 경고 해제를 확인했다.

서브시스템 job57d2e243-5009-490d-bac1-a299a934099e는 status1/result0, a5280d24-cacf-48f9-ac95-ebd5e730c6b5 Ready다. managed NQN과 allowanyhost=false다. 정상 UI에서 예약한 기존 SPARSE20GiB 볼륨 ec92d6c6-2fde-4f35-9d31-e8a7efd69c63를 선택했다. namespace jobb4b1958b-f322-4b5f-b4c4-a70f54cc0121는 status1/result0, 13fe7ca1-8321-47c2-96f3-32d535700754 Ready, NSID1/port4420/cleanupfalse다. API는 기존 volumeid를 전송하며 별도 volumemode 필드가 없다.

실제 configfs NS1 enabled와 port1/wildcard4420/tcp 연결, /dev/sde의 ec92 일련번호·20GiB·512바이트를 확인했다. FS·partition·mount가 없고 ROOT/다른 DATA를 제외했다. 새 볼륨 생성·포맷은 없다. 정상 RAM API로 두 hostNQN의 DHCHAP 및 상호 DHCHAP ACL을 설정했고 actual allowedHost 링크2와 host/ctrl 키 존재·형식 bool을 확인했다. 비밀 원문·해시는 공개하지 않았다. NVMe 두 ACL은 READ_WRITE이며 C2의 읽기 전용 정책으로 주장하지 않는다.

UI의 빈 키 시험은 기존 hostNQN을 사용해 create가 기존 row를 반환했다. 정상 기존-create shortcut과 맞으며 신규 host 키 누락 거절이나 API0 검증으로 확대하지 않는다. 기존 키 유지 편집과 신규 키 요구는 구분한다. 해당 요청 이후 자연 writer 종료·새 GEN26·인증 설정 보존을 확인했다. 클라이언트의 nvme_tcp 모듈은 정상 준비로 로드했다.

실제 C1/C2의 자격 없음은 errno126 ENOKEY, 잘못된 host/ctrl 키는 errno129 EKEYREJECTED로 loginfalse·I/O0이다. 타임아웃을 인증 거절로 세지 않았다. C1은 exact4096바이트를 offset1048576에 한 번 썼고 fsync 및 namespace FLUSH status0 뒤 실제 first/fresh SHA-256 31a8ca181e7ede2835efbb11dbe9f00baa1deeec0862655cbb1709f2999bd98b가 예상값과 일치했다. 첫 controller/device의 완전 정리 뒤 새 인증 controller에서 읽었고 재접속 controller도 삭제했다. 추가 쓰기는 없다.

C2의 최초 정상 READ는 namespace publication 완료 전 ValueError로 실패했다. 고정 오류 projection 보완 후 READ-only 재시험에서 NAMESPACE_REPLACED를 확인했다. 원래955 도구는 불완전한 공개 snapshot의 namespace 신원을 고정하고 있었다. 별도536 도구는 완전한 snapshot에서만 신원을 고정하고 연속 두 번 일치를 요구한다. controller·foreign·post-pin replacement·deadline·I/O 전 fstat 및 own cleanup 검사는 유지했고 원본955를 덮지 않았다. 로컬10 fixture와 독립 검토를 통과한 뒤 실제 C2 READ/fresh31a8가 통과했다. 제품 코드는 변경하지 않았다.

서버 BUFFERED_PREAD는 0이었으나 같은 정확한 디바이스의 O_DIRECT 읽기는 client31a8과 일치했다. 이 실제 읽기 방식 차이를 기록했고 cache flush/drop이나 재포맷 없이 직접 읽기로 시험 구간 보존을 확인했다. 헤더64KiB·앞뒤4096바이트는 0이며 전체 구간 외 디스크 해시 검증으로 확대하지 않는다.

별도 도구 재시험의 첫 keyupdate job93e210은 native generation begin 실패530/op0ac827 revision29 BLOCKED였다. 원인 세부는 INFO 로그에 남지 않아 미확정으로 보존한다. current28/pending 없음/idle/data31a8을 확인하고 새 idempotency+expectedrevision28의 정상 writer C1 요청, 성공 후fresh29의 C2 요청을 한 번 수행해 모두 완료했다. 원 BLOCKED op/key/snapshot을 부활하지 않았다.

최종 GEN30/pending 없음/writer idle·모든 own 세션0, C1/C2 controller/namespace/devnode0, O_DIRECT31a8·헤더/앞뒤0, 기존 iSCSI40804e/WP0/1·ROOT/FILE/NFS/SMB를 보존했다. DONE으로 RAM 자격을 폐기했다. 네 프로토콜 핵심 I/O subset이며 전체 profile·복원·정책·cold 완료로 표시하지 않는다. 최종 UI #1275는 미착수다.

[정상 UI 단계](nvme-actual/nvme-ec92-normal-ui-steps1-3-public-proof.json), [실제 namespace](nvme-actual/own-nvme-namespace-v2-public-proof.json), [실제 CAP](nvme-actual/fresh-installed-nvme-cap-public-proof.json), [host 인증 설정](nvme-actual/after-nvme-dhchap-hostacl-auth-native-public-proof.json), [처음 실제 I/O·C2 실패](nvme-actual/nvme-ec92-dhchap-ram-controller-public-proof.json), [직접 읽기](nvme-actual/exact-ec92-direct-post-io-window-public-proof.json), [정확한 C2 오류](nvme-actual/nvme-ec92-readonly-diagnostic-controller-public-proof.json), [도구 수정 검증](nvme-actual/focused-final-public-proof.json), [독립 검토](nvme-actual/independent-peer-public-proof.json), [최종 C2 성공](nvme-actual/nvme-ec92-stable536-fresh-idempotent-controller-public-proof.json), [최종 보존](nvme-actual/stable536-final-direct-preservation-public-proof.json).
