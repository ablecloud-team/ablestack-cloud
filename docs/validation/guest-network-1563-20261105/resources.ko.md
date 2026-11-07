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

# 확인용 시험 자원

작업 시작일 2026-11-05, 최종 확인·게시 2026-11-06 KST. 새 자원은 별도 삭제 지시까지 유지한다. 관리자 로그인 정보는 이 문서에 기록하지 않는다.

| 클러스터 | Root 검토 망 / 실제 물리망 | VM / IP / 호스트 | User 소유 망 / 실제 물리망 |
|---|---|---|---|
| 31 (GFS2) | [qa-gn1563-31-review](http://10.10.31.10:8080/client/#/guestnetwork/bb2e125e-7e78-4fde-b2d9-a72eed972b60) / A | qa-gn1563-31-vm / 10.73.31.94 / ablecube31-2 | [qa-gn1563-31-user-review](http://10.10.31.10:8080/client/#/guestnetwork/2490904e-181d-46fd-97c3-753b216f6982) / B |
| 32 (Glue RBD) | [qa-gn1563-32-review](http://10.10.32.10:8080/client/#/guestnetwork/0905622c-bab4-422b-a40b-7be29a3fc807) / B | qa-gn1563-32-vm / 10.73.32.64 / ablecube32-3 | [qa-gn1563-32-user-review](http://10.10.32.10:8080/client/#/guestnetwork/5df4bffd-4df1-465b-9823-a98078ee6991) / B |

총 가상 네트워크 4개, Running VM 2대, Running 라우터 4대, 전용 물리망 4개, 네트워크 오퍼링 6개, 시스템 라우터 컴퓨트 오퍼링 4개, User 검증 계정 2개를 유지한다. `resources.json`에 실제 UUID와 호스트 태그를 기록했다.

각 클러스터의 Root 망은 `qa-gn1563-{31|32}-review`, User 망은 `qa-gn1563-{31|32}-user-review`다. 상세 → 작업 → 물리망 마이그레이션에서 확인할 수 있다. 일반 User에게는 이 작업을 표시하지 않는다. 브라우저에는 Root 검토 망 두 탭을 남겼으며 확인 체크박스는 선택하지 않았다.

Root VM과 라우터는 31-2 / 32-3의 같은 호스트를 사용한다. 31 Root 망의 현재 오퍼링은 `offering-resume-a`, 32 Root 망과 두 User 망은 `offering-b`다. A/B의 VirtualRouter 서비스·Guest 트래픽이 활성화돼 있고 실패 재개용 라우터의 QA host tag도 보완했다.

기존 운영 물리망의 태그·VLAN·트래픽 라벨은 유지했다. 새 물리망의 VLAN은 31 A=3101-3102/B=3103-3104, 32 A=3201-3202/B=3203-3204다. 모든 QA VLAN 예약은 실제 활성 네트워크와 일치하며 User 망의 최초 VLAN 3102/3202 예약은 정상 이동 후 해제됐다.

확인 후 정리를 지시하면 전용 VM → 네트워크/라우터 → 네트워크 오퍼링 → 시스템 오퍼링 → 물리망·검증 계정의 의존성을 확인해 공식 API로 정리한다. 호스트에서는 QA ready/resume-ready 태그만 제거하고 기존 태그를 유지한다. 기존 망·오퍼링과 재사용한 SSH 키는 삭제하지 않는다.
