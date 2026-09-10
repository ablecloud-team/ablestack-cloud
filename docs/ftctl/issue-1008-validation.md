# #1008 구현·배포 및 검증 기록

## 판정
구현, 모듈 빌드, 단위/계약 회귀, 배포 완료. UI 테스트 페일오버 및 정리는 성공했으나, UI 페일백 사전 점검부터 실제 복귀까지의 수용 기준은 미완료다. 전체 PASS로 판정하지 않는다.

## 변경 및 기준
- Cloud/qemu: `codex/fix-1008-failback-credentials`.
- #979 Cloud `2f79d608368c7d8cd32facae1dd9d5e8231f48e4`, qemu `cfad0d4b2325bb9298f108881749594cbc471c7e` 기반.
- 최신 Cloud upstream `11fc3c536c2e1de229ee1bc2812b5774ce475bec`를 merge `bb436f5b7328fa4722f67c41195ef3e1f27df720`으로 포함. qemu upstream `9d5f543dd0a1ffb83f661b1b7b792f16b0b1d73a` 포함.
- qemu preflight는 현재 요청에 credentials 필드가 있으면 해당 요청만 사용한다. 명시적 빈 값/잘못된 값/target-only 요청은 과거 source cache로 되돌아가지 않는다. 필드가 아예 없는 legacy 요청만 기존 fallback 유지.
- Cloud command profileJson 로그 제외. wire serializer는 보존.

## 빌드·회귀
WSL ext4 clone에서 수행. 전체 Cloud/RPM 빌드 없음.
- `mvn -pl core -Dtest=GsonHelperTest -DfailIfNoTests=false install`: BUILD SUCCESS, 5 tests PASS.
- `mvn -pl plugins/integrations/disaster-recovery -DfailIfNoTests=false test`: 450 tests PASS.
- qemu credential 회귀: 6 PASS. 32.2 설치 파일 대상으로도 6 PASS(외부 govc/storage는 mock; 실 vCenter 성공 증거와 구분).
- `tests/ftctl_dr_full_lifecycle_smoke.sh`: 71 cases PASS.
- release tombstone regression PASS.

## 배포
- 13/22/31/32 각 compute 1~3 총 12대에 수정 shell 직접 배포.
- 설치 경로 `/usr/local/lib/ablestack-qemu-exec-tools/ftctl/dr_kvm_vmware.sh`.
- SHA256 `c94c857dd2858cb4a97c188f594e68c437b8cc37b921b5e9f973015d486a704b`.
- 31/32 관리 서버 및 compute 6대, 총 8대에 core command class만 기존 JAR 엔트리를 보존해 반영.
- class SHA256 `ca576012dc353c3e3555346955eabba3e751091de1ce71830ed713bd559c89ee`.
- 모든 대상 class 해시 일치. mold/mold-agent active, 관리 UI HTTP200 및 WEB-INF 보존. UI 번들 변경 없음.
- 백업 `/root/issue1008-20260910/`.
- 31.3 Agent stop/start 경합으로 일시적 runtime mask를 사용했고 배포 후 해제/active 확인. 원인과 운영 개선은 #1010 P2로 등록.

## UI와 런타임
VMware plan `a85874ae-d1bd-470b-97c5-7c48a39486dd`:
- UI PAUSE run471 SUCCEEDED.
- 원본 독립/네트워크 어댑터 비활성화 TestFailover run472 (`af06aa25-8b66-438c-bcb0-3b6b0d489f92`) SUCCEEDED.
- 시험 VM316 `i-2-316-VM`, NIC enabled=0. POWER_STATE_VALIDATED만 확인했으며 OS 부팅 정상 판정은 하지 않는다. VMware 양단 QGA 검증 없음.
- UI cleanup run473 (`499802eb-d0ca-435b-9d41-d4039c6c476d`) SUCCEEDED, session52 CLEANED, VM316 Expunging.
- runtime credentials는 source 없이 target만 포함. 수동 credential 복구 없음.
- 최종 VMware PAUSED / scheduler RUNNING·HEALTHY / checkpoint53 / 오류 없음. 원본 vm-4486은 poweredOn이지만 XFS emergency mode.
- 기존 RBD plan `7ec74483-8554-415d-ac56-f62f8b17fbd0` checkpoint4471 READY, scheduler RUNNING·HEALTHY.
- 기존 qcow2 plan `9a20b190-d202-4b66-9358-b509756f9751` checkpoint1483 READY, scheduler RUNNING·HEALTHY.
- 위 두 경로는 지속 복제 상태 확인이며 이번 변경 후 전체 UI failover/failback 재시험을 의미하지 않는다.

## 중단 원인과 남은 수용 기준
새 재해 전환/페일백 전에 원본 VMware 콘솔에서 XFS `Metadata CRC error`, `xfs_agi_read_verify`, block `0x80002`, error74, emergency mode를 확인했다. Tools가 없어 정상 guest shutdown도 실패했다. 원본 강제 종료, xfs_repair, 외부 snapshot 삭제, 신규 failover/failback 및 방화벽 차단은 하지 않았다.

#1011 P1에 증거와 원인 조사/OS 복귀 검증 보완을 등록했다. 기존 #979 failback470의 poweredOn 확인은 OS 정상 복귀 증거가 아니며 원본 OS 성공으로 해석해서는 안 된다. 손상 원인은 아직 미확정이다.

원본 디스크를 다시 쓰면 조사 증거가 변경되므로 계획을 PAUSED로 유지한다. #1011 조사와 정상 시험 원본 확보 후 다음을 완료해야 한다:
1. 원본 독립 재해 전환 → 원본 연결 복구 → UI preflight READY.
2. runtime source credentials 수동 복원 없이 실제 페일백 완료와 원본 OS 콘솔 정상 복귀 확인.
3. 실제 Agent preflight 로그의 비밀 제외와 요청 전후 credential 파일 불변 확인.
4. 복제 재개 및 새 checkpoint 발행.

증거 디렉터리: `/home/ablecloud/work/issue1008-evidence` (비밀 값은 문서에 포함하지 않음).
