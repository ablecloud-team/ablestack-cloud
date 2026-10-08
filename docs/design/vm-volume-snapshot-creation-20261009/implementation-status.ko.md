# Epic #1335 구현 및 검증 진행 상태

최종 완료 기준은 배포된 UI에서 실제 생성·게스트 부팅·fixture 데이터·원본 보존을 확인하는 것이다. 단위 테스트나 Agent의 읽기 전용 검사는 이 기준을 대체하지 않는다. 새 이슈를 생성하지 않고 #1335와 #1336–#1343에서 관리한다.

## 구현

| 기존 이슈 | 반영 내용 | 남은 실제 검증 |
|---|---|---|
| #1336 | 생성 원본 목록·사전 검사 API, 기본 역할 권한, 권한/출처/상태/부팅 검사, 서버 pagination 및 제출 재검증 | 배포 API·UI 검색/페이지/프로젝트/역할/상태 변경 |
| #1337 | CLUSTER GFS2/RBD 원본 pool·cluster 유지, Agent 읽기 전용 디스크 사용 검사, 편입 잠금, 실패 시 원본 보존 | UI 편입·동시 요청·부분 실패·quota |
| #1338 | 스냅샷 생성 시점 메타데이터, ROOT 복구 출처 보존, 논리 용량 유지, 원본 행 완전 누락 사전 차단 | 새 ROOT·시점 데이터·원본 expunge·복구 실패 |
| #1339 | BIOS/UEFI/bootmode/디스크 버스 상속, TPM/SecureBoot 의존성 차단, ISO/userdata/키 입력 제거 | 실제 libvirt XML·Linux/Windows 게스트 부팅·기존 경로 회귀 |
| #1340 | 후보 표·원본 요약·볼륨 편입 확인·1 VM 제한·한글 안내·테마 토큰·조회 오류 차단 | 배포 UI의 모든 상태, 라이트/다크/390·1366·1680px·키보드 |
| #1341 | job/VM 추적, 응답 유실 수동 조회, 자동 중복 제출 금지, 안전한 시작 재시도 | UI 재진입·응답 유실·management/Agent 재시작·실패 복구 |
| #1342 | snapshot 대상 storage 선택 API/allocator, 논리 용량 및 실제 대상 확인 | 수동/자동 배치·용량 변화·추가 디스크·태그·IOPS |
| #1343 | 16개 대표 UI 생성·부팅 증거 수집 계획, 기존 인벤토리 보존 기록 | 아래 모든 대표 경로 및 경계·회귀 검증 |

## 완료한 검증과 한계

변경 Maven 모듈과 필요한 의존 모듈은 WSL ext4에서 빌드했다. 전체 Cloud 빌드 또는 qemu/ftctl 빌드를 실행하지 않았다. 개별 테스트·빌드 로그와 배포 파일 해시는 Epic 진행 보고에 연결한다.

32번 Agent 3대에 신규 읽기 전용 검사 클래스만 배포했다. 설정 해시와 실행 중 VM 목록은 유지되었으며, 각 호스트의 미사용/사용 중 디스크 검사 2개, 총 6개를 확인했다. 이는 Cloud UI를 통한 볼륨 편입·스냅샷 복구 성공을 의미하지 않는다.

32번 관리 서버 JAR 교체·mold 재기동은 자동 승인 검토에서 차단되어 실행되지 않았다. 구체적인 거절 사유는 제공되지 않았다. 최신 구현의 관리 서버/UI 배포와 다음 행렬은 대기 중이다. 기존 서비스와 기존 VM을 이 거절 이후 변경하지 않았다.

로컬 빌드 UI는 32번의 기존 API에 연결하여 조회 오류·한글·다크 모드 표시를 검토한다. 구 API의 Unknown API 오류는 생성 차단 상태로 보여야 하며, 이를 기능 성공으로 계산하지 않는다. 이 검토에서 발견한 스냅샷 번역 키 노출·이전 템플릿 요약 잔존·다크 모드 빈 목록 문구 대비 문제를 수정했다.

## 대표 UI 생성·부팅 행렬

| 환경 | 생성 원본 | OS/부팅 | startvm | 결과 |
|---|---|---|---|---|
| 31 GFS2 | 볼륨 | Linux BIOS | false | NOT_RUN |
| 31 GFS2 | 볼륨 | Linux BIOS | true | NOT_RUN |
| 31 GFS2 | 볼륨 | Windows UEFI | false | NOT_RUN |
| 31 GFS2 | 볼륨 | Windows UEFI | true | NOT_RUN |
| 31 GFS2 | 스냅샷 | Linux BIOS | false | NOT_RUN |
| 31 GFS2 | 스냅샷 | Linux BIOS | true | NOT_RUN |
| 31 GFS2 | 스냅샷 | Windows UEFI | false | NOT_RUN |
| 31 GFS2 | 스냅샷 | Windows UEFI | true | NOT_RUN |
| 32 krbd | 볼륨 | Linux BIOS | false | NOT_RUN |
| 32 krbd | 볼륨 | Linux BIOS | true | NOT_RUN |
| 32 krbd | 볼륨 | Windows UEFI | false | NOT_RUN |
| 32 krbd | 볼륨 | Windows UEFI | true | NOT_RUN |
| 32 krbd | 스냅샷 | Linux BIOS | false | NOT_RUN |
| 32 krbd | 스냅샷 | Linux BIOS | true | NOT_RUN |
| 32 krbd | 스냅샷 | Windows UEFI | false | NOT_RUN |
| 32 krbd | 스냅샷 | Windows UEFI | true | NOT_RUN |

각 행에는 UI 제출·job·새 VM UUID·ROOT UUID/device0/pool·host XML·게스트 fixture·원본 보존 증거가 모두 필요하다. startvm=false도 UI에서 중지 상태 확인 후 시작하여 게스트 부팅을 검증한다. 기능 구현이 추가되었으나 Epic의 최종 완료 조건은 아직 충족하지 않았다.
