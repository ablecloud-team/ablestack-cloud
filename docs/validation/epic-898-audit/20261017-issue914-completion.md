# #914 설계·구현·배포·검증 완료

설계·구현, 정상 모듈 빌드, 13번 환경 배포, API와 실제 Chrome UI 인수, 전용 시험 자원 정리 및 기존 자원 보존 대조를 완료했다. 기존 epic/898-sharedfs와 [Epic PR #1271](https://github.com/ablecloud-team/ablestack-cloud/pull/1271)에 통합한다. 이 문서는 검증 종료 시점의 기록이며 완료 댓글과 최종 이슈 상태는 [#914](https://github.com/ablecloud-team/ablestack-cloud/issues/914)를 따른다.

볼륨·endpoint 복원, runtime/listener 준비, export probe, 잔여 프로토콜 적용과 전체 검증을 단계로 나눴습니다. 실제 probe 작업량에 따라 내부 deadline을 600~1500초로 산정하고 systemd 상한 1800초 안에서 끝냅니다. 실패를 endpoint/export/protocol·timeout·retryability·cleanup 결과로 공개하며, 성공한 SMB를 NFS 재시도 때문에 반복 적용하지 않습니다. API·monitor·UI는 runtimeReady와 detailedVerified를 구분하고 종료된 작업의 진행 안내를 해제합니다.

실제 UI에서 발견한 100/100 최종 검증 대기의 중간 FAILED 표시는 b96에서 수정했습니다. 알려진 callback·성공 apply ACK의 내부 in_progress만 인정하고 실제 구조화 실패와 최종 검증 조건을 유지합니다. 다중 port group의 정확한 Ganesha DBus 이름·정책, 큰 QGA 입력 분할, 검증된 동일 NFS 요청의 복구도 실제 실패에 근거해 보완했습니다. 작업 이력 조회는 본문을 읽지 않는 metadata projection으로 바꿔 관리 서버의 기본 2GiB heap을 유지했습니다. 추가 DDL·이력 삭제는 없습니다.

| 범위 | 정상 빌드·13번 반영 |
|---|---|
| Native b96bb259 | 51 modules·755 tests, 실패/오류/skip0, source622 exact. 집중120은 합산하지 않음 |
| Backend 8b444df0 | 39 reactors·143 suites·1,275 tests, Checkstyle PASS. MGT JAR9eb841d5·PID2339799·2GiB |
| UI 0320323c | 43 suites·1,061 tests, lint·production PASS. 실제 5 static 반영·index645ec62f·관리대상885 해시 확인 |
| CODE·이미지 | 정상 TEST 서명 CODE3를 전용3인스턴스에 preflight/apply·known1/0으로 반영. 전체 이미지 빌드·읽기 전용 설치19항목/정확 소스·압축 roundtrip·NBD 해제 PASS |

- 최신 b96: V4_ONLY 10개 READY10/10·16.679초, DUAL 100개 READY200/200·163.595초, Main 100개 READY100/100·SMB7·169.413초로 정상 cold 완료했습니다. API·실제 Chrome UI·외부 NFS/SMB 동일 파일 읽기와 BOOT/current·receipt·파일 hash/inode/권한 보존을 확인했습니다.
- 실제 Chrome DOM과 공식 GET에서 163.201초의 APPLIED/PENDING100/100·verified=false·noFailure→READY를 관측해 중간 falseFAILED 해소를 확인했습니다. PENDING JPEG는 놓쳤고 READY JPEG를 관측했습니다.
- 최신 b96 권한 주입 실패는 DEGRADED·99/100·runtimeReady=true/detail=false·PROBE_PERMISSION_DENIED/retryable=false로 공개됐고 probe 자식·mount cleanup, SMB VERIFIED, 동일 파일 읽기와 실제 UI를 확인했습니다. 주입 장치를 제거한 뒤 별도 정상 cold를 완료했습니다. 실제 제한 ACL의 #918 인수와 구분합니다.
- 첫 두 probe timeout 후 attempt3 성공, QGA20초·관리 서버 약20초 중단 뒤 같은 프로세스 회복과 guest 진행은 앞선 e7 소스의 실제 시험 근거로 보존합니다. 최신 b96에서 모두 재실행했다거나 실제 커널 D-state·직접 HTTP timeout을 관측했다고 주장하지 않습니다.
- 다중 endpoint/port101 probe와 두 포트 실제 읽기는 881 소스의 성공 기록입니다. 정상 구성3요청으로 시험용239:20490만 제거하고 원래2049·보조238을 복원·보존했습니다.
- Gen7 actor를 정상 종료한 뒤 전용3VM/7 SPARSE 디스크를 정상 destroy3POST·detached DATA delete4POST로 정리했습니다. 자동 재전송0·unknown0, API와 호스트3개에서 정확한 VM/domain/7 backing 파일 부재를 확인했습니다.
- 마지막 보호 전후 읽기 검사에서 F1 Native143·5디스크, ref59 Native7·sentinel, retained61 Native2·receipt2·85B canary, 기존 복구 필요10행 원본을 보존했습니다. partial021b는 API/DB metadata만 확인했고 직접 guest/format/mount/repair/delete/reboot0입니다. 호스트3개 Up/Enabled·agent active, pool factor4와 실측 최소 여유3,733,202,092,032B를 확인했습니다.

새 전체 이미지의 등록·실제 fixture 부팅은 수행하지 않았습니다. 실제 fixture는 앞선 private TEST 이미지에서 정상 CODE3로 갱신했습니다. 운영 기본 템플릿·운영 서명키 전환과 #1275 최종 UI 표준화는 이번 완료 범위에 포함하지 않습니다.

설계는 [20261016-issue914-design.md](./20261016-issue914-design.md), 공개 근거219개와 전체 SHA는 [completion-public.json](./20261017-issue914-completion-public.json)에 있다. 원 부정 결과와 좁은 qualification도 보존했다. 알려진 network raw preimage/typed projection, serializer, expected0의 정식 empty-object 대응을 다른 malformed 응답·구성 drift의 성공으로 확대하지 않았다.

최종 current 전체5필드와 전체 raw SHA, canonical7, filesystem UUID·완료 receipt, 파일 hash/inode/UID·GID/mode, pending/lease 부재와 idle을 대조했다. Small/Dual/Main의 canary는 각각1/1/8, receipt는1/1/2다. 모든 전용 디스크는 SPARSE·필요 L2 전체 할당·noBacking이었다. Dual의200 probe는 export100×v3/v4이며800이 아니다.

구성 요소·이전 회차·집중 회귀의 시험 수를 합산하지 않았다. Native-only b96 보완 후 Java/UI를 다시 빌드했다는 주장은 없다. 최신 Main cold3의 짧은 PENDING 창과 Small active 창은 놓쳤고 Dual은 active91초→READY200을 확인했으나 PENDING200 창은 놓쳤다. 실제 최신 Main cold1의 DOM/GET 관측과 구분한다.

종료 도구의 이전 CLI 해시 가드와 이전974 JAR inode metadata 가드의 읽기 전용 부정 결과는 POST0 원형으로 보존했다. 실제 b96 CLI 해시 한 리터럴과 정상 v11 JAR metadata 한 RAM필드만 재연결해 다시 검증했다. writer 효과·14게이트·보호 DATA/RC10 기준은 변경하지 않았다. 전체 원본 DATA bytehash 검증으로 확대하지 않는다.

다음 제시 대상은 **#918 제한 ACL의 로컬 visibility probe 실패로 전체 Ganesha가 중지되는 문제**다. 이번 완료 과정에서 착수·종료하지 않았다. **#1275 최종 UI 표준 정리는 별도 시작 지시 전까지 보류**한다. 새 이슈·개발 브랜치·PR을 만들지 않았고 Epic 전체 PR은 Draft로 유지한다.
