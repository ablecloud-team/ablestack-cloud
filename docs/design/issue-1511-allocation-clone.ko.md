# #1511 정책을 보존하는 VM 복제 설계

대상: Epic #1510 / Draft PR #1514, 기준 `9d2d2a8057011d08d78b3cfa8d5859173b3f5d74`.

## 계약

- 정책의 존재만으로 거절하는 검사를 clone 전용 검증으로 교체한다. 원본 intent를 검증하고 대상에는 intent만 상속한다. 원본의 적용/관측 증거는 복사하지 않는다.
- ROOT/DATA의 각 정책·형식·크기·Device ID를 보존한다. Ready·VM Running/Stopped·권한·암호화·백업·동시 작업 조건은 계속 검사한다.
- RBD raw off: Full은 검증된 독립 복사, Linked는 검증된 COW 복제다. raw full: Full 복사는 실제 thick 생성+전체 내용 검증으로 완료한다. Linked+물리 full은 특정 조합 충돌로 거절하고 UI에서 Full 선택을 안내한다. Thin으로 조용히 변경하지 않는다.
- GFS2 qcow2 Running: 기존 안전한 source-overlay 준비 경로의 불변 원본을 검증된 allocation copy 어댑터로 복사한다. 정책을 적용한 독립 복제본만 VM 생성에 사용하며 기존 source-overlay 복구를 유지한다.
- 중지된 GFS2 explicit 원본은 전원/경로를 바꾸지 않는 materialized copy를 사용한다. RAW/QCOW2를 보존한다. Running GFS2 RAW는 기존 qcow2 overlay 제어 계약에 포함되지 않으므로 정지 후 복제하도록 구체적인 상태 조건을 안내한다.
- 중지된 RBD VM은 storage-only snapshot을 사용한다. Running은 기존 freeze/snapshot/thaw를 사용하며 정지 VM에 freeze나 전원 조작을 요청하지 않는다.
- 새 Agent 결과의 대상 UUID·pool·논리 크기·형식·정책·적용 메서드·관측량을 대조한 뒤 Ready/적용 증거를 저장한다. 응답 유실/구형 Agent/불완전 결과는 Ready로 기록하지 않는다.
- 독립 Full 복사가 완료되면 임시 스냅샷을 정상 삭제한다. Linked 부모는 자식이 참조하는 동안 보존하고, 정리 시 임의 parent/원본 삭제를 사용하지 않는다.

## 구현 및 검사 경계

관리 사전 검사/볼륨 할당, 기존 ROOT 재사용의 읽기 전용 검증, storage snapshot clone의 출처 검사, Agent 전송/실행/결과 검증, UI 선택 안내를 연결한다. 다른 resize/migrate/restore/export 수명 검사를 완화하지 않는다.

실제로 변경한 Maven 모듈만 WSL ext4에서 빌드한다. 전체 Cloud/Release 빌드는 수행하지 않는다. 22/31/32에 관리 JAR·필요 Agent JAR·UI를 백업 후 배포하고 버전/해시/프로세스/HTTP를 대조한다.

UI 중심 행렬: 22 off/raw 정지 원본, 31 GFS2·32/22 RBD legacy/explicit, ROOT+DATA, Full/Linked, count 1/2, start false/true, 정책 충돌·이름 충돌·재시도·후속 복제/정리. API/DB/host의 정책·크기·의존성·내용과 부팅 결과를 대조한다.

22 `rocky98-perftest` 및 연결 ROOT/DATA에는 변경 요청을 금지한다. 기존 `rocky98-base`는 중지 상태의 읽기 원본으로만 사용한다. 나머지 시험 자원은 새 `qa1511-` 이름과 생성 UUID로 소유권을 제한해 정리한다.
