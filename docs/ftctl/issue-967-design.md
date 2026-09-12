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

# #967 전체 재동기화 완료 판정 설계

## 원인과 범위
최신 통합 HEAD 16b3e03b34에는 #1013/#1030의 요청별 cycle 검증이 있으나 DrTargetMaterializationServiceImpl.completeMaterialization이 이를 우회하여 대상 준비만으로 SYNC Run을 SUCCEEDED로 기록한다. 과거 checkpoint로 대상 준비가 가능한 동안 새 FULL_RESEED가 PENDING/FAILED여도 발생 가능하다.

## 변경
Cloud completeMaterialization에서 SYNC + request.mode=FULL_RESEED는 target-materialization 단계 및 TARGET_MATERIALIZED 이벤트만 기록한다. Run terminal 및 runtime-projection 성공은 기록하지 않는다. FtctlDrRuntimeProjectionAdapter의 기존 accepted cycle/요청 소유권/durable 완료 검증에 최종 판정을 맡긴다. 준비 callback이 Run을 다시 저장하지 않아 동시 실패/취소 terminal을 덮어쓰지 않는다. 일반 초기 SYNC/RECOVER_SYNC 완료 동작은 보존한다. qemu runtime/스토리지/배치/QGA 계약은 변경하지 않는다.

## 검증
1. 실제 private 완료 callback 회귀: FULL_RESEED 대기 Run이 성공으로 바뀌지 않고 완료 시간이 비어 있음, 일반 SYNC 기존 성공 유지, 이미 실패/취소된 Run 보존.
2. 기존 projection의 요청별 durable cycle 테스트와 DR 모듈 전체 테스트, qemu lifecycle/tombstone/checkpoint 스모크.
3. 원래 qcow2 13→31 및 RBD 22→32 UI 전체 재동기화 요청. 요청 UUID와 cycle 연결, 대기/전송 중 미완료, 해당 FULL_SEED 대상 durable 후에만 Run 성공 확인. VMware 양단 QGA 제외하고 정상 복제 회귀 확인.
4. 변경 Maven 모듈만 WSL ext4 빌드하고 클래스 배포/서비스/활성 JAR 검증. UI 변경 없음. PR #1022에 반영하며 결과와 잔여 우선순위 기록.
