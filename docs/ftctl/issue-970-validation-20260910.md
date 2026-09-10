## #970 구현·검증 기록 (2026-09-10)

### 코드와 빌드
- 안전한 누적 worktree/동일 branch: `codex/fix-970-reverse-export-ownership`. Cloud 기준 c6dceb85f1, qemu 기준7bd0017. 이전 구현 브랜치를 병합하거나 수정하지 않았다.
- Cloud 구현37b0b2e8cb, 후속 차단 결함 수정 ecfb970e50(#975), 2c7333d4fb(#976), ab4ee6698a/0e7eb7b67e(#977).
- qemu 최종61725fc90ad0154edc021e3f34b602bf225c7d94.
- WSL ext4 changed Maven modules: schema385, ftctl-service81, disaster-recovery 최종436 tests, 실패0. 전체 Cloud 빌드는 실행하지 않았다.
- qemu Actions [34431985704](https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/34431985704) SUCCESS. release tombstone, 기존 DR lifecycle/action suite, VMware snapshot 및 SharedMountPoint/RBD smoke 포함.
- RPM SHA256: `3dd90c877479b32dfbc5a0c4fcd288a73d4b70336fb749a6cc778a90762b0c0e`.

### 배포
- 원격 reverse broker/schema: 13.10·22.10·31.10·32.10. Plan 소유 측31.10/32.10에는 #975/#976/#977 후속 DR projection class까지 반영.
- qemu 최종 RPM: 13/22/31/32 각 .1/.2/.3, 총12호스트. 설치 dr_ablestack.sh hash `b281aa73316441229d3673f4359b80b6a152837d6400dc55400e91bb1d90c72e`.
- 호스트 mold-agent/ftctl timer active, agent.properties hash 동일, 배포 전후 VM 목록 동일.
- 관리 JAR는 빌드된 변경 class/resource만 overlay, 기존 나머지 ZIP entry 동일 및 CRC 확인. WEB-INF 보존, mold active, /client HTTP200, 실제 UI 재로그인 검증. UI 소스 변경은 없다.
- 백업은 `/root/issue970`, `/root/issue970-v2`, 관리 후속 `/root/issue970-975`, `-976`, `-977`, `-977v2`로 구분했다.

### 실제 검증
- qcow2 13→31 Plan6: 최종 UI planned failover Run293 → failback Run294 SUCCEEDED. 강제 옵션 해제, 최종 sync 사용. 원본13.2의 VM과 다른13.1을 reverse writer로 자동 선택. START69→STOP70, 나머지 worker는 REVOKED, 반환 후 source VM Running/target VM stopped, 복제 체크포인트1404 및 HEALTHY 확인.
- qcow2 대상에서 새로 기록한 파일이 원본으로 반환됨: SHA256 `a25dd2ff3e6d1df38cc1cb07a64104d18b9b951ab46b82410679390b0e7a602d` 양쪽 일치.
- RBD 22→32 Plan51: 앞선 Run415는 #975/#977 수정 후 수렴한 복구 실행이므로 무개입 전체 PASS와 구분. Run416 역방향 START131→STOP132,22.1만GRANTED,22.2/3 REVOKED. Windows 원본 재부팅 후 해시 `0EC703DF7ACA9D5F08BD04A34A0AEDFF5152472F6878BAB75E949BC05CF19E4F` 일치, 자동 복제4389완료.
- 실제 전송 중 RBD 동일 START 재전송: protocol2/세대131/scope/direction ACK, MainPID3471168 유지. 원래 요청 프로파일 재사용 시 이중 reverse 변환 없이 같은 source RBD를 연다.
- qcow2 이전 generation15 START/STOP을 종료 generation16 이후 재전송: 둘 다 `DR_EXPORT_STALE_GENERATION`, tombstone 유지, export inactive. 실제 CLI/호스트 검증이다.
- #976 수정 후 매 projection poll마다 export 교체하지 않고 한 full seed 및 후속 증분이 완료되어 lifecycle이 자동 수렴했다. 수동 DB 상태 보정은 하지 않았다.

### 범위와 후속
- 이전 worker Down/복귀, 불확실 START/ACK 유실, 같은 Run 재전송, Running source 차단은 자동화 모의/단위 검증과 실제 stale/duplicate CLI 검증을 구분한다. 물리 호스트 장시간 power-off 및 모든 혼합 경로를 실환경 PASS로 주장하지 않는다.
- RBD↔qcow2 혼합 사이트와 VMware UI 전체 회귀는 이번 실환경 시험에 포함되지 않았다. VMware에는 기존 경로의 reverse broker 적용 여부를 유지하며 공통 qemu smoke를 실행했다. #970 전체 수용 매트릭스의 미실행 항목은 열린 상태로 유지한다.
- legacy 초기 UNKNOWN 목록은 이관 당시 현재 zone inventory 기준이다. 이관 전에 이미 삭제된 과거 host의 미회수 writer까지 증명한 것은 아니다. 신규 grant 이후 삭제/단절된 기록은 fail-closed로 유지한다.
- 별도 발견: #975 동적 target compute 누락, #976 resume poll export 교체, #977 checkpoint/authority 세대 혼용은 이번 branch에서 수정. #978 기존 dr_cycle migration 다중 SQL 오류(P2), qemu upstream#55 CI cleanup race(P2)는 후속 작업.
- 레거시 DR Cluster 활성화 변경 없음. disaster failover에 source reachability 조건을 추가하지 않았다. 재해 단절 전체 검증은 #971에서 별도 추적한다.
### 최종 RBD 재검증과 종료 상태
- 최종 배포 후 무보정 UI planned failover Run417(`f0350475-25e4-4deb-bf9a-5f1202641dff`) 12:25:39→12:26:29 SUCCEEDED, generation9353 ACKNOWLEDGED.
- UI failback Run418(`38a60658-aa0d-416c-882b-f1140c074893`) 12:27:11→12:31:48 SUCCEEDED/completed/100%. reverse START143→STOP144, journal1/2/3 모두REVOKED. 새 파일 SHA256 `5015EB03E5A24043186A41AAEA7861791846D7780DB6AECD22DFD7E5541F0BDF` 대상/원본 일치.
- RBD 자동 보호 재개 checkpoint4392 COMPLETED/HEALTHY. qcow2 최종 UI Run293/294도 각각 SUCCEEDED/completed/100% 확인.
- Plan6/51 READY/SOURCE, target VM225/287 Stopped. 원본 게스트 QGA 실행과 반환 데이터 읽기 성공. 테스트에서 만든 Linux/Windows marker만 원본 게스트에서 삭제했다.
- 최종 Cloud31 JAR hash `35515949ad2e558de46a891bde141c0d15a103e3fc5994cb8683a398cdc0f6fa`, Cloud32 `29eccc7b9a34ac14f79f6c39b52d54f4ff7cf06f865134e789ff59a9f507a148`.
- 실측 로그와 build/deploy 증거: WSL `/home/ablecloud/work/issue970-evidence/`. GitHub에는 비밀번호나 인증키를 게시하지 않는다.