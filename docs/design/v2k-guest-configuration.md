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

# v2k 게스트 설정 결합과 현재 상태 검증

v2k가 원본 의도와 OS 어댑터·첫 부팅 payload를 준비하고, Mold가 실제 VM/볼륨/NIC·실행 호스트와 결합해 결과를 확인한다. 게스트 설정은 `StartCommand` 응답을 기다리는 경로에서 처리하지 않는다. VM을 시작해 Running/host를 반영한 뒤 별도 reconcile 명령을 사용한다.

## Root admin API

| API | 입력 | 결과 |
|---|---|---|
| getMigrationGuestConfigurationCapabilities | storageid, 선택 hostid | 실제 Up/Enabled KVM worker의 버전 1·qga-file-v1·fresh observation 지원 |
| reconcileMigratedGuestConfiguration | id, planhash, operationid | BOUND, PENDING, VERIFIED, FAILED, DRIFT, UNKNOWN과 관측 |

계획이 없는 일반 VM은 새 guest worker를 요구하지 않는다. 계획은 Root admin의 volume import 기반 VM 생성에만 받으며, 일반 VM/metadata 갱신이나 삭제로 변경·소실되지 않게 보호한다. Source/ROOT/OS adapter/helper SHA, #89 controller 계획 hash를 함께 검증한다. Windows DATA 목록과 ROOT source disk ID는 controller 역할과 일치해야 한다.

## 저장과 시작 경계

기존 `vm_instance_details`의 숨김 `migration.guest.config.*` namespace에 plan/binding/evidence를 보관한다. #89 청크·상한·해시 함수를 재사용하며 새 테이블/컬럼은 없다. 정지 VM의 DB 결합에는 볼륨 UUID·풀·경로·형식·슬롯과 NIC UUID/네트워크/MAC을 고정한다. 선택된 실제 Agent가 새 계약을 긍정 응답해야 시작한다.

KVM의 실제 devices/channel을 검사한다. metadata의 동명 문자열로 QGA 채널이 있다고 판단하지 않는다. Running reconcile은 현재 host를 다시 조회하고, domain UUID·#89 실행 XML·실제 serial·NIC·미디어 상태·QEMU 실행 scope를 확인한 뒤 결합을 전달한다. Source/target serial을 같은 값으로 가정하지 않는다.

## 제한된 QGA 전송

새 API에 임의 명령, guest-exec, 사용자 파일 경로를 노출하지 않는다. `guest-info`의 enabled commands를 확인하고 고정된 Linux/Windows 전용 디렉터리의 binding/commit/report만 file RPC로 읽고 쓴다. 파일은 64 KiB, 읽기는 작은 조각으로 제한하며 모든 핸들을 finally에서 닫는다. Windows의 EOF는 다음 0바이트 읽기에서 확인하는 동작도 지원한다. 부분 commit은 같은 payload를 바꾸지 않고 복구한다.

마지막에 소비한 binding hash는 management metadata에 남긴다. 이미 VERIFIED에 사용한 보고서는 새로운 관측으로 인정하지 않고 확인값을 바꾼다. 따라서 같은 QEMU process 안에서 게스트가 재부팅해도 이전 보고서로 새 성공을 만들지 않는다. Pending 확인값은 Agent 재시작 후에도 동일하게 이어갈 수 있다. 보고서는 현재 binding hash·operation/plan/VM/ROOT·boot scope/ID·helper SHA와 필요한 모든 항목이 일치해야 VERIFIED다. 해시는 게스트 root에 대한 보안 attestation이 아니다.

## 실패와 검증

채널 부재/비활성 RPC·상한 초과·과거/불완전 보고서·잘못된 UUID/serial/NIC·계획 변경은 완료로 승격하지 않는다. 현재 host와 guest 채널을 새로 조회하며 source 이미지에 대한 offline 수정을 수행하지 않는다. 실제 게스트 변경과 재시도/보상은 도구 어댑터의 소유 journal로 제한한다. VERIFIED 후 관측은 읽기 전용이다.

[도구 PR #88](https://github.com/ablecloud-team/ablestack-qemu-exec-tools/pull/88)과 [13번 GFS2 실기 증거](https://github.com/dhslove/ablestack-qemu-exec-tools/blob/codex/issue-87-mold-preallocation/docs/validation/issue90-v2k-cluster13-20261127.md)를 함께 검토한다. 전용 CI는 기존 allocation/controller 계약에 guest plan·worker·QGA 전송 검증을 포함한다.
