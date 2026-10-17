# #897 장시간·고메모리 작업 제어 완료 보고

2026-10-16, Epic #898 통합 브랜치에서 설계·구현·정상 모듈 빌드·13번 배포·API 및 실제 Chrome UI 인수를 완료했습니다. 이슈별 기능 완료이며 Epic PR #1271 전체 병합을 의미하지 않습니다. 다음 대상은 #974이고 자동 착수하지 않습니다. 최종 UI 레이아웃 #1275는 미착수입니다.

## 변경 결과

인스턴스별 단일 writer, 영속 phase·progress·heartbeat와 SOURCE/입력/예상 출력의 동결 자원 예산을 연결했습니다. 저장 실패·예산 초과·확인되지 않은 backing/health·자원 부족은 효과 전에 차단합니다. TARGET·정상 응답 증거를 native commit 전에 저장하고, 정확한 임대 정리까지 확인한 뒤 COMPLETE/LKG를 공개합니다. 정리 결과가 불명확하면 같은 원본 작업의 SOURCE/TARGET 증거로 복구하며 SOURCE 효과를 무조건 재실행하지 않습니다.

UI 작업 이력·제어·정책은 유한 조회, 5초 상태 갱신, 화면 해제/권한/범위 변경 처리, 안전한 취소 경계와 응답 유실 시 동일 작업 조회를 제공합니다. CPU 관측값과 취소 상태 번역도 실제 화면에서 보완했습니다. 세션 수는 TCP 연결 관측입니다. graceful drain 미지원과 승인된 유지보수 대안을 구분하며 지원하지 않는 drain 요청은 명시적으로 거절합니다. 논리 임대는 물리 RAM/디스크 선할당이 아니며, 메모리 부족 시 자동 확장을 주장하지 않고 작업을 시작하지 않습니다.

실제 UI 백업에서 별도로 잠금 조회한 DB 행에 SHA/크기가 저장되지 않는 오류를 발견했습니다. 실제 producer·DTO·보호된 store와 별개 행 객체로 RED 재현 후 완료 필드를 원자 저장하도록 수정했습니다. 기존 실패 건은 변경되지 않은 SOURCE·VM/DATA·BOOT와 영속 응답·아카이브·manifest·작성자·소유권·유효기간을 검증해 두 필드가 모두 누락된 경우만 보완합니다. 만료된 소유 임대도 정식 release와 새 status로 정리합니다.

## 정상 빌드와 실제 배포

| 구성 | 최종 결과 |
|---|---|
| Backend 976ab023c60 | 정상 39 모듈·132 suites·1,111 tests, 실패·오류·skip 0, Checkstyle PASS |
| UI ef7cb93f0e0 | 관련 30 suites·686 tests, 실패·skip 0, 전체 lint·정상 production build PASS |
| Native 213a92d2bb7 | 관련 5 모듈·73 tests PASS. 서명 TEST CODE의 정상 catalog 등록·검증·공개·preflight·업그레이드 COMPLETE |
| 관리 서버 13.10 | JAR 29da9fc552e7… / PID2151866 / 최신 변경 class2개만 적용. 나머지204647개 항목 보존, API·호스트3개 정상 |
| UI 배포 | 92f4 기능 JS와 ef7 정상 빌드 한영 locale2개 적용. 변경 없는 index·다른 정적 산출물을 유지하고 실제864개 해시 확인 |
| KVM | #897 변경0. 이전 #892 호스트 class baaa226ff0… 유지 |

위 테스트 수는 단계별·이전 이슈 실행 수와 합산하지 않았습니다. 저장소 전체 테스트 통과를 뜻하지 않습니다. BACKUP 단독 집중 테스트의 실패 원형과 정상42건은 최종 정상1,111건에 더하지 않습니다.

## API·실제 UI 인수

| 검증 | 실제 결과 |
|---|---|
| 초기 UI/NFS/정책 | 전용8GiB SPARSE 서비스의 NFS·loopback ACL 생성, Ready, 정책 opt-in revision1 확인 |
| 메모리 사전 차단 | 실제 UI 요청을 MEMORY_HEADROOM/PREFLIGHT/BLOCKED로 종료. 요구17179869184B·관측7936098304B, 효과0. 설정 하한은256MiB로 복원 |
| 중복·예산·만료 | 동일 키 async job2개가 operation1개·효과1회로 수렴. 다른 의도·외부 scope·변경된 요구·staging/load/drain 거절, 실제30초 임대 만료 확인 |
| 정상 UI·취소 | 실제 단계·heartbeat·CPU 관측값·정상 변경 확인. 안전한 경계 취소는 CANCELLED, 적용 후 늦은 취소는 UI/API 모두 거절. 최종 화면에 '취소됨' 표시 |
| 관리 서버 재시작 후 복구 | 실패 BACKUP74436bfa…를 같은 원본 작업의 실제 UI 복구1회로 COMPLETE_NO_CONFIG_CHANGE. 새 operation0·Native6 보존, 아카이브SHA/6432B 보완·만료 임대 정리 |
| 새 일반 BACKUP | 실제 UI 생성1회→2cab7dbe… COMPLETE_NO_CONFIG_CHANGE. 새 아카이브30337049… SHA37d0ffa0…/6435B/SOURCE6 표시, Native6·시험 파일·DB11군 동일 |
| 실제 QGA 단절 | 정확한 QGA PID를40초 정지, 실제 guest-ping4.001초 timeout(code112) 확인. 변경59934fb3…는 SOURCE 수집 단계에서 BLOCKED. 같은 PID 복구·Native6·데이터 보존 |
| 실제 시험 VM 재부팅 | 정확한 VM만1회 재부팅. 300초 임대가 TTL 전에 BOOT 변경으로 무효화, 정확한 소유 release. Native6·구성·두 SPARSE 디스크·XFS·파일·NFS 서비스 유지 |
| 장애 후 실제 UI 정상 변경 | c7684b01… COMPLETE/native7. readonly 한 항목만 변경, 새 BOOT와 영속 TARGET 증거 일치, pending/lease0 |
| 실제 NFS 클라이언트 | 재부팅·최종 변경 뒤 NFSv4.1 RO mount로112B 파일 SHA/UID/GID/mode 확인, 정상 unmount 및 임시 경로 제거 |

세부 operation/job UUID, 최종 component pin, 각 공개 증거30개의 SHA는 [완료 JSON](20261016-issue897-completion-public.json)에 기록했습니다. 실제 UI는 공식 Chrome 제어의 DOM·화면·정식 Network 관측으로 검증했으며 응답을 대체하지 않았습니다.

## 스키마와 보존

기존 cloud.storage_service_operation_control의12컬럼·3인덱스, cloud.storage_service_instance.operation_control_policy_json LONGTEXT와 control-policy/control migration2개의 Complete를 읽기 전용으로 확인했습니다. 현재 보완의 추가 DDL은 없습니다. 정확한 컬럼·인덱스·migration 명칭은 완료 JSON에 있습니다.

최종 원본 F1은 Native143·BOOT·CLI, 원본5디스크 및 DB 자원11군이 기준선과 같습니다. 원본 데이터 전체를 다시 읽었다고 주장하지 않습니다. 보호된021b0cac…10TiB는 포맷·repair·mount·삭제하지 않았습니다. 새 디스크는 ROOT5242880000B/DATA8589934592B SPARSE뿐이며 THIN 생성0입니다.

검토용 시험 SharedFS 71893316-6d13-4e68-9a41-6bbb0bbdd2f9는 유지했습니다. 전역 기능은 활성화됐으나 opt-in은 이 시험 서비스1개뿐이고 다른8개는 꺼져 있습니다. 최종 실행/복구 필요 작업0·Native pending/lease0·호스트3개 Up을 확인했으며 UI actor는 blank로 종료했습니다.

격리 시험에는 한정된 시험 템플릿 허가와 서명 TEST CODE 신뢰를 사용했습니다. 일반 catalog 수명주기(#924), ROOT 교체(#920), 모든 managed action의 실환경 인수 또는10TiB 포맷(#974)을 완료했다고 표현하지 않습니다. 이번 추가 MGT SIGKILL/committed-target ACK-loss 보조 도구는 준비만 했으며 실제 수행 건수에 포함하지 않았습니다.

검증 도구의 index 변경 가정 등 실패 원형도 보존했습니다. locale만 바뀐 빌드의 index는 같으므로 실제 적용 성공 후 독립 읽기 검증으로864개 정적 파일·관리 서버·guest·자원 보존을 확인했으며 추가 배포를 반복하지 않았습니다. 제품 오류와 도구 가정을 구분했습니다.

## 소스 관리와 후속 순서

작업 시작 시 최신 upstream4bd1a2cff50을 로컬 ablestack-europa에 먼저 반영하고 local-base/upstream0/0을 확인한 뒤 기존 Epic 브랜치를 이어갔습니다. 최종 PR 기준 commit도4bd1a2cff50입니다. 변경은 origin/epic/898-sharedfs에만 push하고 기존 Epic PR #1271에 통합합니다. 새 이슈·브랜치·PR은 만들지 않았습니다.

#897 완료 근거를 이슈에 먼저 기록하고 닫은 뒤, 다음 #974 대용량 백킹 볼륨 포맷 timeout·안전한 재개를 제시합니다. #1275는 별도 시작 지시 전까지 착수하지 않습니다.
