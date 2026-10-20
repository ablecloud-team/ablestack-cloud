# #974 대용량 볼륨 포맷·안전한 재개 완료 보고

2026-10-16, Epic #898의 기존 통합 브랜치에서 설계·구현·정상 모듈 빌드·13번 배포·API 및 실제 Chrome UI 인수를 완료했습니다. 기능 완료를 기존 Draft Epic PR #1271에 통합하며, Epic 전체 병합과 구분합니다. 새 이슈·브랜치·PR은 만들지 않았습니다. 다음 제시 대상은 #913이며 자동 착수하지 않습니다. #1275 최종 UI 표준 정리는 미착수입니다.

## 변경 결과

고정 20초로 끝나던 포맷 실행을 짧은 probe와 분리하고, 용량에 따른 유한 format deadline과 영속 journal을 사용합니다. 10TiB 시험의 deadline은 1500초입니다. BOOT/PID/startTicks/argv와 볼륨 serial·크기·파일시스템을 대조해 실행 중인 formatter를 판별합니다. 관측 불능을 종료로 간주하지 않으며, schema2 formatter 성공 증거와 현재 파일시스템 identity가 일치해야 완료·재개를 허용합니다. 파일시스템 헤더만 있는 부분 성공은 자동 재포맷하지 않습니다.

SPARSE/FAT 신규 볼륨의 discard 생략 정책과 XFS -K, ext4 nodiscard·lazy initialization 옵션을 기록합니다. 기존 파일시스템이나 다른 VM·UUID·장치 identity를 임의 포맷하지 않습니다. 완료 후 경과 시간은 영속 완료 값으로 고정합니다.

완료된 파일시스템의 명시적 재개는 포맷 없이 identity·검증·마운트 상태를 다시 확인합니다. 이미 마운트된 DATA도 찾도록 관리 서버가 DB volume UUID에서 표준 마운트 경로를 만들어 전달합니다. 보류된 원작업과 Aux 복구 작업의 권한을 구분하고, Aux는 COMPLETE_NO_CONFIG_CHANGE로 끝냅니다. 원 서비스 작업은 같은 operation의 reconcile로 SOURCE를 복원합니다. 중복 요청은 같은 intent/key를 유지하며 확인된 async job은 조회로 추적합니다.

첫 미구성 SMB 적용 실패에서 SOURCE 부재 증명이 STOP 검증 중 소실되는 문제도 보완했습니다. 검증된 미구성 SOURCE와 원래 보호된 복구 증거에 한정해 동일 작업을 복구하며, 기존 opaque cipher와 SOURCE 구성을 보존합니다. 현재 보완은 기존 preparation/status/resume/reconcile API와 operation 모델을 사용하고 새 API 명칭이나 DB DDL을 추가하지 않습니다.

UI는 볼륨·단계·경과 시간·기한·heartbeat와 복구 가능 여부를 백그라운드로 갱신합니다. 포맷 실행 중이거나 완료 증거가 부족하면 재개를 차단하고 raw 진단은 접힌 상세로 제공합니다. 기능 UI 연결만 수행했으며 최종 상세 화면 레이아웃 정리 #1275는 시작하지 않았습니다.

## 정상 모듈 빌드와 13번 배포

| 구성 | 최종 결과 |
|---|---|
| Backend 23f62349 | 정상 39 reactor·132 suites·1,128 tests, 실패·오류·skip 0, Checkstyle PASS |
| Native 91b78364 | 관련 10모듈·218 tests 및 shell syntax PASS |
| UI 03c9b79c | 관련 35 suites·794 tests, lint·정상 production build PASS |
| 관리 서버 | JAR563e1b7d…/PID2205817. 최근 변경 class1개만 적용, 다른204648 ZIP 항목 보존 |
| UI·Native | UI indexef9dd645…/configd3e28531… 보존 확인. 신규 시험 VM에 서명 TEST CODE 정상 preflight/upgrade 적용, CLI6453e7f0… |
| 호스트 | #974 KVM 변경0. 기존 host class 유지, 호스트3개 Up/Enabled·mold-agent active |

테스트 수는 과거 단계나 다른 이슈와 합산하지 않았으며 저장소 전체 테스트 통과를 뜻하지 않습니다. Native는 검증용 TEST 키로 서명한 AVAILABLE91 번들의 정상 경로로 전달했습니다. SystemVM 템플릿 바이너리 재빌드나 운영 서명키 사용을 주장하지 않습니다.

## API·실제 UI 및 데이터 경로 인수

| 검증 | 실제 결과 |
|---|---|
| 정상 지연 10TiB SPARSE XFS | 새 빈 볼륨의 formatter에 45초 bounded 지연을 주입. 정상 job2ae755c1…=1/op0ecf0c0f… COMPLETE, Native45.6087초, NFS Ready |
| 실제 진행 UI | Chrome에서 23·30·37·44초 FORMATTING·heartbeat 갱신과 재개 비활성화를 관측. 이후 COMPLETE와 NFS Ready 확인 |
| QGA 응답 유실 | 별도20GiB SPARSE ext4의 formatter 완료 증거 생성 뒤 실제 QGA service 재시작. 원 job5a8704ea…=2, 완료 증거·FS·85B canary 보존 |
| 마운트된 볼륨 재개 | 실제 UI에서 발견한 표준 mountPath 누락을 수정. 같은 Aux 복구 job1446…=1, 동일 key 재조회는 Aux1개·추가 Native 효과0, 원 작업은 job009…=1/ROLLED_BACK |
| 별도 정상 UI 재개 | fresh UI jobc2c28391…=1/COMPLETE_NO_CONFIG_CHANGE. formatter 재실행 없이 완료 증거와 canary 보존 |
| MOUNT_EXISTING 재사용 | NFS 생성 job8e1a7442…=1, share0bec180e… Ready/Native2. 기존 파일시스템을 다시 포맷하지 않음 |
| 포맷 중 관리 서버 재시작 | 별도10TiB SPARSE XFS의 동일 formatter 25초 hold 중 옛 MGT 프로세스 종료 확인. Native25.6405초 완료, 약134초 뒤 원 작업 자동 ROLLED_BACK, terminal 실제 UI 확인 |
| 10TiB SPARSE ext4 | 정상 최초 포맷 jobd0d96f99…=1, Native4.6539초, receipt2·정확한 EXT4 UUID3636466d…·mount·NFS Ready를 API/실제 UI에서 확인 |
| 대형 ext4 NFS·SMB IO | 외부13.1에서 NFS4.1/CIFS3.1.1로 각64KiB 파일 O_EXCL 생성·fsync·읽기·해시 확인 후 정상 unmount |
| 대형 ext4 재부팅 | 정상 restartSharedFS job5ab77b74…=1로 BOOT 변경. 같은 파일의 해시·inode·UID/GID/mode, 같은 RAM SMB 인증정보, formatter receipt·FS UUID·Native 설정·desired revision 유지 |
| 대형 ext4 최종 UI | 재부팅 뒤 실제 NFS·SMB Ready, 동일 SPARSE10TiB/EXT4/EXACT 매핑, 완료 증거와 복구 경고 없음 확인. 두 공유의 총량은10TiB로 중복 집계하지 않음 |
| 안전 거절 | 원본 partial은 RECONCILE_REQUIRED·completionfalse·resumefalse. 다른 VM/없는 볼륨 API431, 잘못된 UUID·기존 FS·중복·관측 불능의 집중 회귀 검증 PASS |

첫 정상 XFS의 0.568초 결과는 20초 초과 증거로 사용하지 않았습니다. 그 자원에서도 NFS/SMB IO와 cycle2 재부팅 후 같은 파일·인증정보 보존을 확인했습니다. 관리 서버 중단 시험은 의도한 job2이며 active FORMATTING UI를 놓쳤으므로 정상45초 job1·실시간 UI 결과와 분리했습니다.

VM61 같은 Aux 복구의 초기 browser POST 캡처는 없고 exact async job·operation의 ROOT 대조를 사용했습니다. 별도 정상 UI 재개에서는 실제 요청을 관측했습니다. 대형 ext4의 SMB 재부팅 전 UI는 놓쳤으므로 ROOT 외부 IO before와 실제 UI after를 구분했습니다. UI에서 공개하지 않는 BOOT/마운트 root는 Native 증거로 확인했으며 UI 관측으로 확대하지 않았습니다. 화면은 공식 Chrome DOM·화면·Network 관측으로 검증했고 응답을 대체하지 않았습니다.

## 정리·용량 원복·원본 보존

소유한 대형 시험 VM60/62/63/64와 ROOT/DATA를 정상 destroy/expunge 및 deleteVolume API로 정리했습니다. 스토리지 파일을 직접 삭제하지 않았습니다. 임시 풀 비율8을 원래4로 복구했고 전역 factor1/할당 임계치0.85는 유지했습니다. 최종 공유 스토리지 물리 여유는 최소3784093167616B이며 세 호스트의 같은 공유 풀을 중복 합산하지 않았습니다. 새 디스크는 모두 SPARSE이고 THIN 생성0입니다. 이슈 본문의 과거 thin 회귀 항목보다 사용자의 최신 SPARSE 이상 지시를 우선했습니다.

복구 검토용20GiB ext4 SharedFS f3252c4a-091c-44c1-a697-4f1fc424c410은 NFS Ready/Native2로 보존했습니다. 원래 formatter receipt2와85B canary SHA92d54728…/inode12/UID·GID65534/mode0640, pending·lease 없음이 최종 검사와 일치합니다.

원본 F1은 Native143·BOOT·5디스크 roster를 보존했습니다. 전체 원본 DATA checksum을 읽었다고 주장하지 않습니다. 보호 partial021b0cac…는 API 메타데이터를 보존했고 format/repair/mount/detach/delete/reboot하지 않았습니다. 해당 과거 부분 포맷은 안전한 명시적 수동 복구 대상으로 남습니다. ref59 Native7·BOOT·112B sentinel SHA27dfaec6…와 기존 복구 필요10행의 전체11필드도 그대로 유지됐습니다. 이번 소유 작업의 잔여 실행/복구 필요 상태는 없습니다.

도구의 준비 가정·권한·경로 또는 readiness 판정 실패 원형은 보존했습니다. 최종 정상 빌드·검증 결과와 합산하지 않았습니다. 용량 API의 이전 캐시와 실제 live allocator 수치 차이는 읽기 전용으로 확인하고 정상 할당 경로로 시험했으며, 과거 캐시 갱신 시점에 대한 설명은 추론으로 한정했습니다.

## 증거와 소스 관리

전체 component pin, job/operation UUID, 최종 보존 projection과 공개 증거36개의 SHA는 [완료 JSON](20261016-issue974-completion-public.json)에 기록했습니다. 설계는 [설계 기록](20261016-issue974-design.md)에 있습니다.

최신 upstream을 fetch해 로컬 ablestack-europa와4bd1a2cff50에서0/0임을 다시 확인했습니다. 기존 Epic 브랜치를 origin에만 push하고 기존 PR #1271에 반영합니다. #974 완료 근거를 이슈에 먼저 기록한 뒤 닫고 #913을 다음 대상으로 제시합니다.
