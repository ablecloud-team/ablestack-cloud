# SharedFS ROOT 템플릿 교체 기반 구현 검증

이 기록은 #920의 기반 코드 검증이다. 실제 ROOT 교체 API/orchestrator/UI의 전체 완료 또는 클러스터 ROOT 교체를 의미하지 않는다.

## 구현

- 동일 SharedFS VM의 ROOT만 staging/교체/역교체하는 primitive. 대상 템플릿을 명시해 준비하고 이전 ROOT는 detach·보관한다. 기존 restore의 destroy/expunge 경로를 호출하지 않는다.
- instance/request 범위의 업그레이드 기록, 원본·대상 template/ROOT, guest OS/device/VM state, phase/progress/revision 및 내부 snapshot·검증·rollback·retention 기록.
- RUNNING/RECOVERY_REQUIRED는 generated active_instance_id unique index로 한 작업만 허용한다. completed/rolled-back 이후 다음 작업을 허용한다.
- 외부 단계보다 먼저 phase를 영속화하는 실행기. 실패 시 previous ROOT 복구를 수행하며 rollback 실패는 RECOVERY_REQUIRED로 남긴다. 검증 commit 또는 정상 rollback 이후 cleanup 오류는 완료 상태를 뒤집지 않고 별도로 기록한다.
- SYSTEM/KVM, architecture, zone 다운로드, 명시한 Storage Service version/runtime ABI/desired schema/identity schema, manager/agent 최소 버전 및 NVMe 인증 capability 검사. 최신 template ID만으로 선택하지 않는다.
- VM identity, NIC/MAC/기본 IP/보조 IP 및 모든 DATA volume UUID/device/pool/size/format 보존 검증.
- 같은 VM의 정상 정지 확인, 부팅 후 protected QGA identity capability 준비 확인. QGA 준비와 프로토콜 기능 성공 판정은 구분한다.
- 신규 ROOT에 fully-reconciled verified generation adopt. 보존 ROOT에는 expected previous generation과 replay checksum 일치 조건에서만 더 나중의 generation align. pending 또는 revision 역행을 거절한다.

## 검증

- Source 3c7936d473e의 server 모듈 빌드와 관련 27개 테스트 통과.
- Source 01419527a05의 native 테스트 82개 통과. 신규 generation ROOT 이관 코드는 아직 운영 test VM에 배포하지 않았다.
- 실제 13번 DB의 TEMPORARY TABLE에서 active/request 제약 4개를 검증했다. production upgrade 테이블 및 VM/볼륨 테이블을 변경하지 않았다.
- primitive target-template 선택, Running swap 거절, DATA 오용/예상 ROOT·template 변경/다른 pool 거절, old ROOT 보관 및 역교체.
- stage/checkpoint/quiesce/swap/boot/identity/reconcile/verify/commit 경계 실패 보상 및 previous boot 실패.
- zone ready/명시한 capability selection, undeclared newest template 거절, manager/agent version·ABI·arch·인증 capability mismatch 거절.
- ROOT만 바꾸는 경우 topology 보존, 추가 DATA 유실·MAC/보조 IP 변화 차단.
- SharedFS VM 범위·확인된 Stopped 상태·동일 VM start/protected QGA readiness.

## 커널 후보 실제 패키지 확인

현재 guest kernel 6.1.0-53-amd64 config는 NVME_TARGET_AUTH/NVME_AUTH가 꺼져 있다. 새 템플릿을 준비하기 위한 후보로 공식 [Debian bookworm-backports kernel 패키지](https://packages.debian.org/bookworm-backports/linux-image-6.12.95%2Bdeb12-amd64)를 조사하고 [공식 다운로드 checksum](https://packages.debian.org/bookworm-backports/amd64/linux-image-6.12.95%2Bdeb12-amd64/download)을 실제 다운로드 파일과 대조했다.

- 패키지: linux-image-6.12.95+deb12-amd64 6.12.95-1~bpo12+1.
- 크기: 106422608 bytes.
- SHA-256: 5e524b782be67cb0d6a2e7b51816a2ac519e573ef7324b9a63a4124365653ecc.
- archive 내부 /boot/config-6.12.95+deb12-amd64 직접 확인.
- CONFIG_NVME_TARGET_AUTH=y, CONFIG_NVME_AUTH=m, CONFIG_NVME_TARGET=m, CONFIG_NVME_TARGET_TCP=m.
- 로컬 격리된 분석만 수행했고 클러스터에 커널을 설치하지 않았다.
- 이 버전은 후보의 패키지 수준 capability 검증이다. 최종 대상은 템플릿 빌드 시 선택한 실제 패키지/boot/runtime 인증 테스트를 통과해야 한다.

## 남은 통합

실제 API/controller/runtime orchestration, source identity·desired state·generation 이관, management/agent/VM 재시작 복구, retention/finalize, 호환 템플릿 build/register, 같은 VM 실제 ROOT 교체·자동 rollback 및 실제 UI/클라이언트 검증을 이어서 수행한다. 기존 DATA는 삭제하거나 포맷하지 않는다. AD만 사용자 요청으로 보류한다.
