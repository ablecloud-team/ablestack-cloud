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

# 확인용 유지 자원

각 클러스터의 가상머신용 네트워크 목록에서 `qa-gn1568-31` 또는 `qa-gn1568-32`로 검색한다. **아래 자원은 별도 삭제 지시까지 유지한다.** 관리자는 제공된 기존 로그인 정보를 사용하며, 자격 증명은 보고서에 기록하지 않는다.

| 클러스터 | 자원 | 이름 | UUID |
|---|---|---|---|
| 31 | isolated | qa-gn1568-31-isolated | `3f0c5ed8-2c32-4098-a478-9f017a8845a8` |
| 31 | l2 | qa-gn1568-31-l2 | `51dc8c55-8323-4bff-a004-259408bfb548` |
| 31 | shared | qa-gn1568-31-shared | `db61a6c8-aa44-4290-91d7-936131e1698c` |
| 31 | VM | qa-gn1568-31-review-vm | `b266bc55-2ab8-4826-b8cd-02b695a2322e` |
| 31 | 라우터 | r-888-VM (Running) | `64b69389-1776-4834-a520-cf49ef8033b5` |
| 31 | 라우터 | r-891-VM (Running) | `18153732-a0a6-4011-a7c9-9ec4b4192c44` |
| 31 | Public IP | 10.10.31.110 | `f9eda3b4-2655-453b-837a-28e46eb9c3f5` |
| 31 | Public IP | 10.10.31.143 | `21a6e954-afa8-4bee-89c4-25c2745713b0` |
| 32 | isolated | qa-gn1568-32-isolated | `1171cd96-ad12-420e-88d0-70636b7bdb7d` |
| 32 | l2 | qa-gn1568-32-l2 | `ecf3ac29-e12b-467e-ade0-47026f663ff4` |
| 32 | shared | qa-gn1568-32-shared | `4401793f-ab5b-4e45-a5c1-adb4c53bc986` |
| 32 | VM | qa-gn1568-32-review-vm | `af12d504-97dc-4f36-b59e-027c66a74344` |
| 32 | 라우터 | r-375-VM (Running) | `2c32a81e-3a54-4104-b63b-8874df7e207b` |
| 32 | 라우터 | r-378-VM (Running) | `e03f5fa5-9e6c-4230-8adf-824a418545e9` |
| 32 | Public IP | 10.10.32.163 | `a224e269-d6eb-4b4a-8130-bf2f8927f1c9` |
| 32 | Public IP | 10.10.32.166 | `04de4618-4265-4bfc-9b4c-95bc88ac1c2c` |

Isolated는 QA 물리망 B로 실제 이동한 상태다. L2·Domain Shared는 기존 물리망 A를 사용한다. 각 VM은 세 망에 연결돼 있고 Isolated/Shared 라우터와 같은 호스트(31.2/32.3)에 배치했다. 관련 QA 물리망 각1, 네트워크 오퍼링 각4, 라우터 시스템 오퍼링 각1, 시험 계정 각1을 함께 유지한다. 상세 ID·NIC·규칙은 `evidence/*-retained-final.json`에 있다.

이전 #1563 자원과 이번 일괄 삭제용 temp 망6·disposable VM2·라우터2 및 볼륨은 정리했다. 시스템 오퍼링은 공식 삭제 계약대로 Inactive 이력을 유지하며 활성 잔여가 아니다. 운영 망과 VM, 주 FT 작업 트리의 변경 해시는 보존했다.
