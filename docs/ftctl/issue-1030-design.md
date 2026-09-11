# #1030 체크포인트 producer 수명 분리

## 원인
ABLESTACK replication_cycle은 scheduler가 전달한 producer Run으로 파일명을 만들지만 write_manifest/write_checkpoint는 재작성 가능한 disk map의 profile Run을 읽었다. 살아 있는 worker 재개 후 producer와 control Run이 달라 다음 publication 검증이 실패한다. VMware는 cycleMetrics가 실제 producer를 전달한다.

## 코드 설계
1. dr_ablestack.sh replication_cycle의 함수 지역 exported FTCTL_DR_PRODUCER_RUN_UUID에 scheduler 인자 run을 고정한다. manifest/checkpoint writer는 이 값을 명시적 Python 인자로 전달하여 결과 runUuid로 사용한다. 호출이 끝나면 지역 값은 사라지며 plan profile/disk map을 고쳐 과거 증거를 재라벨하지 않는다. 비-scheduler 기존 호출은 기존 map Run을 유지한다.
2. dr_scheduler.sh pending resume_context 실패를 무처리 return108로 종료하지 않는다. ERROR/RECOVERY_REQUIRED 및 구체적 오류를 기록하고 제한된 대기로 운영자 요청을 기다린다. 기존 체크포인트는 변경하지 않는다.
3. 불일치 상태에서 새로운 명시적 FULL_RESEED 요청이 들어오면 pending publication을 증거 파일로 보존하고 다음 sequence에서 새 요청 producer로 전체 복제한다. 잘못된 candidate를 승인하거나 ID 검사를 제거하지 않는다.
4. Cloud Java/UI 계약 변경 없이 기존 전체 재동기화 메뉴를 사용한다. 작업 이력 SUCCEEDED는 제어 완료일 수 있어 실제 동기화 이력 READY 및 runtime으로 완료를 별도 확인한다. #967의 전반적인 성공 표시 변경은 별도 범위다.

## 검증
- 기존 profile Run과 producer가 다른 실제 writer 출력으로 기존 코드 실패/수정본 통과 확인; 함수 지역 값 격리, full/incremental 및 pending ACK 재개 확인.
- candidate 불일치 거절 유지, 증거 보존 후 명시적 재동기화, 이전 durable 보존, 제한된 재시도 및 재시작 이후 복구.
- qemu checkpoint/resume/lifecycle/maintenance/release tombstone 회귀. shell 변경 파일 직접 배포 및 SHA256 확인, RPM/전체 Cloud 빌드 없음.
- qcow2/RBD/VMware UI Pause/Resume에서 worker 유지/소실 후 실제 다음 checkpoint 확인. VMware 양단 QGA 제외. 변경 데이터 있는 qcow2 cycle 확인, UI 전체 재동기화 복구 확인.

## 마이그레이션 계약 추가
producer identity는 plan/Run/sequence만 사용하며 host UUID, PID, VM 배치를 포함하지 않는다. 기존 profile을 통한 현재 디스크/실행 호스트 재해결을 유지한다. 원본 VM 이동 때문에 migration을 금지하는 guard를 추가하지 않는다. 실행 중 QMP는 현재 VM 호스트에서 수행하고 공유 저장소 file transfer worker는 별도 선정한다. 기존 relocated-baseline 및 source-outage 회귀와 실제 VM 이동 후 체크포인트를 추가 확인한다.
