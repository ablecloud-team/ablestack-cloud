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
# #1159 검증 결과 (2026-09-23)

## 빌드와 정적 검사

- WSL ext4 체크아웃에서 api, core, engine/schema, server, plugins/hypervisors/kvm 모듈을 빌드했다. 전체 Cloud/RPM 빌드는 수행하지 않았다.
- UI: `npm ci --prefer-offline --no-audit`, `npm run build` 성공.
- Java: `UserVmManagerImplTest` 249개 + `VbmcLifecycleTest` 9개, 실패 0.
- Python 호스트 단위 테스트: 6개, 실패 0.
- 변경 모듈 Maven `validate` / Checkstyle 성공.
- Apache RAT: Git 소스 파일 13,838개를 별도 ext4 디렉터리에 복사한 뒤 실행, BUILD SUCCESS. 빌드 산출물 및 설치된 npm 의존성을 검사 대상에서 분리했다.

## 31번 클러스터 배포

- 관리 서버와 3개 Agent에 변경 모듈을 반영했다. 관리 서버의 기존 결합 JAR에는 변경 클래스만 교체했으며, 최종 57개 클래스의 SHA-256이 로컬 빌드와 일치했다.
- 원본 JAR와 스크립트 및 DB 테이블 덤프는 각 서버의 root 전용 백업 디렉터리에 보관했다.
- Agent 재시작 전후 기존 QEMU PID가 같았다. 최종 3개 호스트는 모두 Up / Enabled다.
- UI 배포에서 config.json, WEB-INF, 관리 프로세스 PID를 보존했고 `/client/` HTTP 200을 확인했다.
- 13번에는 배포하지 않았다.

## 실제 API 및 호스트 검증

| 검증 | 결과 |
| --- | --- |
| 실행 중인 non-HA KVM VM에 할당 | Ready, UDP 포트 1개 |
| 동일 VM 4개 동시 할당 요청 | 모두 같은 포트, 중복 할당 없음 |
| 상태 확인 API | 인증된 IPMI 전원 상태 조회 성공 |
| 허용 대역 내 다른 호스트에서 IPMI 조회 | `Chassis Power is on` |
| 허용 대역 밖 독립 network namespace에서 접속 | 차단, DROP 카운터 증가 |
| 할당 중 Cloud 중지·이동·HA 활성화 | 명시적인 선행 삭제 안내와 함께 거부 |
| 삭제 명령 실패 주입 | CleanupRequired, VM·포트 예약 유지 |
| daemon 재시작 후 상태 확인 | Ready 복구 |
| 삭제 재시도 및 반복 삭제 | 원격 정리 확인 후 Unallocated, 반복 요청 성공 |
| 다른 프로세스가 포트 사용 중 | 할당 거부, 기존 프로세스 유지, 포트 예약 유지 |
| 충돌 프로세스 종료 후 정리 | 성공 |
| 허용 CIDR 미지정 | 127.0.0.1에만 바인딩 |
| 비허용 역할의 상태 API 요청 | 거부; VM 소유권 거부는 Java 단위 테스트로 별도 확인 |
| 자격 증명 노출 | 관리/Agent 로그 평문 없음, async_job에 평문 없음 |
| UI | 다크모드에서 마스킹 비밀번호·CIDR 입력 항목과 중앙 대화상자 표시 확인 |

출발지 대역은 할당 설정값이다. 테스트에 사용한 `10.10.0.0/16`을 제품 코드에 고정하지 않았으며, 해당 대역 안에서 동적으로 바뀌는 클라이언트 IP를 허용한다.

## 환경 복구와 정리

31.2와 31.3의 firewalld가 중지되어 있었고, 세 호스트의 backend는 이미 iptables였다. 승인에 따라 두 호스트를 활성화했다. 활성화 과정에서 libvirt 필터 체인이 사라져 최초 검증 VM 시작이 실패했다. libvirtd를 재시작하여 필터를 복구했으며 기존 QEMU PID가 유지되고 후속 VM 생성이 성공함을 확인했다. 제품의 VBMC 스크립트는 전체 firewall reload/flush를 실행하지 않는다.

31.3도 활성화 뒤 libvirt 필터 재생성을 확인했으며 기존 QEMU PID와 클러스터 quorum이 유지됐다.

신규 DB 날짜 필드의 Temporal 매핑 누락은 실제 API 검증에서 발견하여 수정·재빌드·재배포 후 정상 할당을 재검증했다.

검증 VM 2개(최초 실패 포함), 임시 계정, 네트워크 namespace, 충돌 프로세스를 정리했다. VBMC endpoint와 소유 방화벽 규칙도 제거했다.

## 검증 범위의 한계

- 실제 호스트 재부팅, 하드웨어 장애, 관리 서버 다중 노드 장애 전환은 수행하지 않았다. daemon 재시작과 명령 실패를 검증했다.
- 실제 IPMI 전원 끄기/켜기와 guest OS 복구는 이번 검증 범위에 포함하지 않았다. 인증된 전원 상태 조회를 검증했다.
- 기존 legacy 할당의 자동 채택은 제공하지 않는다. 기존 할당은 원래 호스트를 추정하지 않고 CleanupRequired로 보존하므로 관리자의 기존 endpoint 확인·정리가 필요하다.
- Cloud 중지 이후 도메인 재생성, 이동/HA를 따라가는 고정 endpoint 및 Cloud 중계 전원 제어는 #1161의 장기 범위다.
