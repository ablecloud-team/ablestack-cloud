# 중단된 설정 변경 작업의 재시작 복구

미완료 RUNNING 작업이 있거나 최신 정상 revision보다 새로운 RECOVERY_REQUIRED 작업이 있으면 새 desired 변경을 만들기 전에 차단합니다. 이미 완료된 동일 idempotency 요청의 조회와 나중에 같은 revision 이상이 검증된 과거 실패는 유지합니다.

중단 복구는 동일 서비스 writer lock을 확보한 뒤 오래된 heartbeat와 실제 게스트 writer-idle capability를 확인합니다. 게스트 작업이 실행 중이면 RUNNING과 암호화 checkpoint를 유지하고 다음 bounded retry를 기다립니다. 나중에 검증된 정상 revision이 있으면 현재 구성만 검증하고 이전 snapshot으로 되돌리지 않습니다.

사전 prepare checkpoint 이전 중단은 BLOCKED로 기록하며 데이터나 게스트를 변경하지 않습니다. checkpoint 이후 중단은 DB·보호 identity·POSIX·프로토콜을 이전 상태로 되돌리고 fresh health 및 desired/runtime 일치를 검증해 ROLLED_BACK으로 종료합니다. 복구 실패는 RECOVERY_REQUIRED와 원래 진단을 보존하고 명시적 복구 재시도를 지원합니다.

관리 서버의 background worker는 오래된 RUNNING 작업을 제한 개수로 조회합니다. 동일 writer lock과 System call context를 사용하며 원래 caller의 암호화 checkpoint는 완료 상태에서만 정리합니다. 중단된 operation에 연결된 미승격 CANDIDATE는 정상 지점을 덮어쓰지 않고 FAILED로 기록하며 artifact cleanup 상태와 보존 시각을 남깁니다.

검증:
- 최신 로컬 Europa c169d9a203f49ce07e038297873bc3c24cd8ffb4에 Epic 변경을 rebase했고 번역 키를 모두 보존했습니다.
- 중단 복구·진입 제한·checkpoint·기존 복구 회귀 26개 및 module package 통과.
- rebase 후 native 72개, SharedFS UI 회귀 43개 통과.
- 배포 후 실제 관리 서버 phase별 중단 검증은 아직 진행 전입니다.

전체 native generation·취소/배수·각 프로토콜 실패 게이트는 계속 진행합니다.
