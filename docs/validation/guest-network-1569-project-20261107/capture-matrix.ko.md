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

# 실제 UI 캡처

실제 Chrome/Mold 화면이다. 31 라이트·32 다크, 뷰포트 1440×1000, DPR 1이다. 스크롤바를 제외한 문서 폭 1430px를 원본 JPEG 디코딩 폭과 대조했다. 이미지를 편집하거나 합성하지 않았다. 중복 라벨은 마지막 촬영을 채택했다. 총 24장이다.

| 클러스터 | 화면 | 원본 | 디코딩 크기 |
| --- | --- | --- | --- |
| 31 | owner-l2-create | [캡처](screenshots/31-owner-l2-create.jpg) | 1430×1000 |
| 31 | owner-edit-l2 | [캡처](screenshots/31-owner-edit-l2.jpg) | 1430×1000 |
| 31 | owner-shared-edit-denied | [캡처](screenshots/31-owner-shared-edit-denied.jpg) | 1430×1000 |
| 31 | owner-nic-l2-project | [캡처](screenshots/31-owner-nic-l2-project.jpg) | 1430×1000 |
| 31 | member-create-l2 | [캡처](screenshots/31-member-create-l2.jpg) | 1430×1000 |
| 31 | member-nic-l2-project | [캡처](screenshots/31-member-nic-l2-project.jpg) | 1430×1000 |
| 31 | domainadmin-project-switch-531 | [캡처](screenshots/31-domainadmin-project-switch-531.jpg) | 1430×1000 |
| 31 | readonly-project-read-401 | [캡처](screenshots/31-readonly-project-read-401.jpg) | 1430×1000 |
| 31 | peer-project-direct-denied | [캡처](screenshots/31-peer-project-direct-denied.jpg) | 1430×1000 |
| 31 | outsider-project-not-listed | [캡처](screenshots/31-outsider-project-not-listed.jpg) | 1430×1000 |
| 31 | admin-project-quota-failure | [캡처](screenshots/31-admin-project-quota-failure.jpg) | 1430×1000 |
| 32 | owner-create-l2 | [캡처](screenshots/32-owner-create-l2.jpg) | 1430×1000 |
| 32 | owner-nic-l2-project | [캡처](screenshots/32-owner-nic-l2-project.jpg) | 1430×1000 |
| 32 | owner-shared-edit-denied | [캡처](screenshots/32-owner-shared-edit-denied.jpg) | 1430×1000 |
| 32 | member-create-l2 | [캡처](screenshots/32-member-create-l2.jpg) | 1430×1000 |
| 32 | member-nic-l2-project | [캡처](screenshots/32-member-nic-l2-project.jpg) | 1430×1000 |
| 32 | domainadmin-project-switch-531 | [캡처](screenshots/32-domainadmin-project-switch-531.jpg) | 1430×1000 |
| 32 | readonly-project-read-401 | [캡처](screenshots/32-readonly-project-read-401.jpg) | 1430×1000 |
| 32 | peer-project-direct-denied | [캡처](screenshots/32-peer-project-direct-denied.jpg) | 1430×1000 |
| 32 | outsider-project-not-listed | [캡처](screenshots/32-outsider-project-not-listed.jpg) | 1430×1000 |
| 32 | admin-create-l2 | [캡처](screenshots/32-admin-create-l2.jpg) | 1430×1000 |
| 32 | admin-nic-l2-project | [캡처](screenshots/32-admin-nic-l2-project.jpg) | 1430×1000 |
| 31 | review-project-vm-nics | [캡처](screenshots/31-review-project-vm-nics.jpg) | 1430×1000 |
| 32 | review-project-vm-nics | [캡처](screenshots/32-review-project-vm-nics.jpg) | 1430×1000 |

원본 URL·테마·뷰포트·문서 크기·SHA256은 [manifest](evidence/capture-manifest.json)에 있다. 오류 화면은 해당 역할의 최종 성공 화면으로 해석하지 않는다. NIC 연결 대화상자 캡처는 입력 단계이며 작업 완료와 최종 NIC 행은 작업 JSON·최종 자원 JSON·review-project-vm-nics 캡처로 확인한다.
