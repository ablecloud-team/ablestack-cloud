<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Cross Hypervisor DR KVM-to-KVM Vertical Slice Smoke

작성일: 2026-06-30

대상 브랜치: `feature/ftctl-cloud-integration`

## 1. 목적

이 문서는 신규 Cross Hypervisor DR API/action facade가 기존 FTCTL KVM-to-KVM 성공 경로를 깨지 않고 감싸는지 확인하는 vertical slice 기준을 정의한다.

## 2. 보호 대상 조합

기존 FTCTL에서 검증된 4개 조합을 회귀 기준으로 둔다.

| 케이스 | Source disk | Target disk | 신규 DR 기대 동작 |
| --- | --- | --- | --- |
| KVM-FTCTL-01 | rbd | rbd | `startDrSync`가 기존 `FtctlService.executeFtctlAction(..., PROTECT_START, ...)` 경로로 위임 |
| KVM-FTCTL-02 | rbd | qcow2 | storage 분기 없이 동일 FTCTL action 위임 |
| KVM-FTCTL-03 | qcow2 | rbd | storage 분기 없이 동일 FTCTL action 위임 |
| KVM-FTCTL-04 | qcow2 | qcow2 | storage 분기 없이 동일 FTCTL action 위임 |

## 3. 코드 레벨 smoke 범위

이번 단계의 자동 검증은 실 qemu runtime을 호출하지 않는다. 대신 Cloud DR facade가 기존 FTCTL service로 연결되는 boundary를 고정한다.

- `FtctlDrActionAdapterTest`
  - 4개 storage pair 모두 `SYNC` run이 `PROTECT_START`로 위임되는지 검증
  - 4개 storage pair 모두 FTCTL `locked` 결과가 `DR_ENGINE_BUSY`로 변환되는지 검증
  - 미구현 `TEST_FAILOVER`가 FTCTL runtime을 호출하지 않고 `DR_ACTION_UNSUPPORTED`로 종료되는지 검증
- `DrRunExecutorImplTest`
  - FTCTL adapter 성공 결과가 `DrRun=SUCCEEDED`, `DrRunStep=RUNNING/SUCCEEDED`, `DrEvent=RUN_STARTED/RUN_SUCCEEDED`로 기록되는지 검증
  - FTCTL adapter 미등록 시 `DR_ENGINE_UNAVAILABLE`로 실패하고 projection refresh를 실행하지 않는지 검증

## 4. 수동/실환경 회귀 체크리스트

실환경 재테스트에서는 각 케이스마다 다음 항목을 확인한다.

1. 기존 FTCTL UI/API에서 보호 등록과 조회가 이전과 동일하게 동작한다.
2. 신규 `DrPlan`이 active `ftctl_protection.id`를 `engine_binding_id`로 참조한다.
3. `startDrSync` 또는 해당 action API 호출 후 `DrRun`이 terminal 상태를 갖는다.
4. `DrRunStep`에 `execute` running/terminal step이 남는다.
5. `DrEvent`에 `RUN_STARTED`와 `RUN_SUCCEEDED` 또는 `RUN_FAILED`가 남는다.
6. FTCTL runtime/profile/blockcopy/xcolo 결과는 기존 FTCTL service/qemu 경로가 그대로 소유한다.
7. storage 조합별 rbd/qcow2 선택 로직은 신규 DR adapter가 재구현하지 않는다.

## 5. 경계

- qemu/ftctl runtime script를 변경하지 않는다.
- `plugins/integrations/ftctl-service` 성공 로직을 변경하지 않는다.
- 신규 DR adapter는 storage backend를 다시 판단하지 않는다.
- 실환경 PASS 여부는 배포 후 기존 FTCTL 검증 절차와 함께 별도 기록한다.

## 6. 검증 명령

WSL ext4 worktree에서 변경 Maven 모듈 기준으로 실행한다.

```bash
mvn -pl plugins/integrations/disaster-recovery -am \
  -Dcheckstyle.skip -Drat.skip=true \
  -Dtest=FtctlDrActionAdapterTest,DrRunExecutorImplTest \
  -DfailIfNoTests=false test
```

## 7. AS-IS / TO-BE

| 구분 | AS-IS | TO-BE |
| --- | --- | --- |
| KVM-to-KVM action 진입점 | FTCTL API/UI가 직접 호출 | 신규 DR API가 `FtctlDrActionAdapter`를 통해 기존 FTCTL service 호출 |
| storage 조합 처리 | FTCTL/qemu runtime이 rbd/qcow2 분기 소유 | DR adapter는 storage 분기를 재구현하지 않고 4개 조합 모두 동일 위임 |
| run 상태 | Cloud async job 또는 FTCTL action 응답 중심 | `DrRun`, `DrRunStep`, `DrEvent`에 facade 실행 결과 보존 |
| lock 처리 | FTCTL raw `locked` 결과 | `DR_ENGINE_BUSY`로 표준화 |
| 실환경 회귀 | 수동 테스트 결과 중심 | 코드 smoke + 기존 실환경 회귀 절차 병행 |
