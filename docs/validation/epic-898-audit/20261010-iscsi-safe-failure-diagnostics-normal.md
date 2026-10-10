# iSCSI 실패 단계 보존의 최소 수정과 모듈 검증

고정 소스 ffef35c4986fa140609e89e392295ced6e6b8a0b는 실제 최초 iSCSI 실패의 상세 오류를 보존하지 못한 공백을 개선한다. 제품 변경은 게스트 CLI와 KVM Wrapper 두 파일이며 기존 테스트 두 파일을 보완했다. 관리 서버·API·UI·DB 변경은0이다.

## 동작과 검증

게스트는 targetcli의 최초 required-command 실패 단계·nonzero returnCode·제한 오류 범주만 단일 공개 JSON으로 내보낸다. 원 stdout/stderr는 RAM에서 고정 토큰 분류에만 사용하며 그대로 전달하거나 파일로 보존하지 않는다. TIMEOUT/SPAWN_FAILURE는 returnCode null과 양방향으로 일치해야 한다. best-effort cleanup의 nonzero 동작은 유지하고, 이후 cleanup 예외가 최초 오류를 덮지 않는다.

KVM은 정확한 iscsi target apply에서만 닫힌5필드 JSON을 읽는다. false literal·kind·단계/범주 allowlist·정수 형식/범위·null 의미·필드 중복·후행 데이터·한 줄/4KiB를 검증한다. 잘못된 출력은 기존 민감 출력 마스킹으로 돌아가고 stderr는 전달하지 않는다. 실패 상태를 성공으로 변경하지 않는다. 관리자 진단 경로가 이 fixed details를 받으므로 별도의 관리 서버 수정은 필요하지 않다.

실제 synthetic targetcli executable의 기본 subprocess adapter → 고정 JSON → 컴파일된 KVM 소비자 연결, 비밀 토큰 누출0 및 최초 오류 보존을 검증했다. 집중 Native Lifecycle6/KVM Wrapper22가 통과했다. 정상 KVM72tests/7selectors·FES0·Checkstyle, 정상 Native457tests/49selectors·FES0을 통과했다. 변경 Lifecycle6은 정상49 목록에 포함되지 않아 별도 결과로 기록한다.

Java9903/Native tracked footprint586/UI719의 source before/after가 같고 고정 커밋 git archive11208개가 모두 일치했다. immutable module output3054개에서 API production 차이0, KVM 차이Wrapper1, 추가 production class0이다. 클래스 SHA는45e4173eadecf0f525eff2e4e6f3e98ca15e1e26e9ccd48dad62151193229851, CLI SHA는f9a2d5db19c7a4c45a94e9e6072cba598c4e913012cce0697087e34f5098b962다. 기존 public/protected ABI17을 보존하고 추가 member refs27/27을 해결했다. 라이브러리는 이전 실제13.2 provider5 JAR 캐시를 재검증한 결과이며 새 SSH의 fresh 관측으로 표시하지 않는다. 실제 배포 직전 live baseline과 refs를 다시 확인한다.

## 기존 작업 복구와 현재 상태

고정19ad CLI에서 원 df1641b6/rev11을 정상 UI로 한 번 SOURCE 복구했으나 job227ca3ee-3044-4e66-9c7f-bfce6a34cb32는 status2/code530으로 실패했다. 공개 Interrupted Storage Service writer requires recovery 메시지는 generic catch이며 내부 원인을 뜻하지 않는다. 최초 iSCSI native exit1과 이 복구 실패를 구분한다.

복구 전후 GEN10/10d·pendingdf11 PREPARED·BOOT/CLI19ad·DB메타·장치/mount/NFS가 같고 holder2/deletedfalse·서비스 소유권/빈 세션이 확인됐다. 원 cipher/ref/scope/pin은 그대로다. LOCAL journal은 RESUMED에서 RECOVERY_REQUIRED로 바뀌었고 imported=false, sourceSmbResumed/sourceRuntimeVerified=false다. 이 상태를 복구 완료로 판정하지 않는다.

실제 관리 서버의 제한된 공개 stack은 localSourceGuest4457 ← restoreLocalSourceIdentity4566 ← restoreConfigurationIdentity3354 ← restoreNativePosixOperation10088 ← applyPrevious282를 확인했다. import-local-source dispatch/ACK에서 HOST_NONZERO exit1이 발생했다. authReplay/verify에 도달한 증거는 없으며 앞선 HOST_EXCEPTION/큰 QGA 입력 전송 실패와 구분한다. 내부 native 실패 원인은 아직 미확정이다.

새 진단 CLI를 먼저 설치하면 원 체크포인트의 frozen code 검증을 깨뜨릴 수 있어 아직 배포하지 않았다. 원 작업의 실제 내부 실패 위치를 확인하고 현재 코드에서 정상 복구를 완료한 뒤 새 CODE/KVM 배포·정상 UI 재시험을 진행한다. 강제 terminal·journal 재해시·호환 면제·추가 복구 제출·포맷·RAW I/O는0이다. 최종 UI 표준 #1275는 미착수다.

[모듈 검증](iscsi-safe-diagnostic-ffef35c/normal-source-proof.json), [producer→consumer](iscsi-safe-diagnostic-ffef35c/focused-producer-consumer-proof.json), [기존 작업 복구 실패 보존](iscsi-safe-diagnostic-ffef35c/df11-normal-ui-recovery-failure-preservation-public-proof.json).
