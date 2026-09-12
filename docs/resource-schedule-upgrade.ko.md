<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# Europa 자원 스케줄 기능의 동일 버전 업그레이드

## 원인

이슈 #1065의 VM 스케줄 탭 누락은 UI 구현 삭제가 아니라 배포 구성 불일치였다.
`InstanceTab.vue`는 `listResourceSchedule`이 `listApis`에 있을 때 스케줄 탭을 표시한다.
배포 UI에는 신형 자원 스케줄 화면이 있었지만 관리 서버 JAR와 DB에는 이전 VM 전용 스케줄 구현만 있었다.
같은 `4.23.0.0` 문자열이어도 빌드 커밋과 기능 구성이 같다는 뜻은 아니다.

- 신형 기능 도입: `5d0a34b072cf51bfa4ccbb082075adb9d375c637`.
- 권한 보완: `64aab6213c5`, `03a53d33542`.
- 변경 전 13번 빌드 기준: `1fcb1b0467158e4e0b8e05c0f59886fe4a4b7d43`.
- 변경 전 22/32번 빌드 기준: `5616f85f282f61a566d84a3b9c2e89437862166a`.
- 변경 전 31번 빌드 기준: `fa68586ae6cdb9d92cb5a2df15c176d202610b55`.

기능 도입 커밋은 위 배포 빌드들에 포함되지 않았다. 따라서 새 빌드에서 API 파일 하나가 누락된 문제가 아니다.
앞서 적용한 Network 변경도 해당 기능 범위만 반영한 것이므로 ResourceSchedule을 추가하지 않았다.
해결은 UI를 이전 API로 되돌리는 것이 아니라 백엔드, API, DB를 함께 올리는 것이다.
검토 중이던 이전 API용 UI 호환 수정은 배포하지 않았다.

## 변경 범위

| 구분 | 이전 | 신형 |
| --- | --- | --- |
| 대상 | 가상머신 | 가상머신, 오토스케일 VM 그룹 |
| 공개 API | `*VMSchedule` | `*ResourceSchedule` 추가, VM API 어댑터 유지 |
| 스케줄 테이블 | `vm_schedule` | `resource_schedule` |
| 작업 테이블 | `vm_scheduled_job` | `resource_scheduled_job` |
| 작업별 설정 | 없음 | `resource_schedule_details` |
| 전역 설정 | `vmscheduler.jobs.expire.interval` | `scheduler.jobs.expire.interval` |
| VM 동작 | 시작/정지/재시작/강제 동작 | 동일 동작을 자원 스케줄 체계로 제공 |
| 그룹 동작 | 미지원 | 최소/최대 멤버 수 변경 스케줄 |

한국어 리소스에서 빠진 작업 열, 그룹 멤버 수 변경, 성공/실패 메시지 키 4개도 보완한다.
언어 파일은 `locales/ko_KR.json`으로 별도 로드되므로 이 수정에 전체 UI 재빌드는 필요하지 않다.

## 적용 절차

전체 Cloud 빌드는 이 절차의 전제 조건이 아니다. 이번 검증은 WSL ext4에서 변경된
`api`, `engine/schema`, `server` Maven 모듈만 빌드했다. 기존 DR/FT/스토리지 및 Network 수정은 유지했다.
JAR 전체를 다른 서버 빌드로 교체하지 않고, 검토한 변경 클래스와 Spring 리소스만 반영했다.
기존 스케줄 클래스는 명시적으로 제거하고, 나머지 JAR 엔트리는 바이트 단위로 동일함을 확인했다.
22/32번의 `UserVmManagerImpl` 차이는 별도 빌드로 보존했다.

1. 모든 관리 서버의 빌드 커밋, 실제 JAR 해시, API 목록과 DB 테이블을 확인한다.
2. VM 및 보호 인벤토리, JAR, 스케줄/작업 테이블, 관련 설정과 이벤트를 백업한다.
3. 별도 테스트 DB에서 데이터가 있는 상태의 이관과 재실행을 검증한다.
4. 같은 DB를 사용하는 관리 서버를 모두 정지하고, 프로세스가 종료되었는지 확인한다.
5. 아래 도구로 DB를 이관하고, 일치하는 신형 백엔드 JAR를 배치한 뒤 관리 서비스를 시작한다.
6. API 발견, 실제 조회, DB 상태, 워커 초기화 로그, UI 표시를 각각 확인한다.

```bash
# MySQL root 자격 증명은 외부 MYSQL_PWD 또는 보호된 MySQL 설정으로 전달한다.
python3 tools/admin/resource-schedule-upgrade.py

# 관리 서비스가 inactive인 상태에서만 실제 DB 변경이 허용된다.
python3 tools/admin/resource-schedule-upgrade.py --apply \
  --backup-dir /root/resource-schedule-upgrade-backup

# API 비밀번호는 CLOUD_API_PASSWORD 환경 변수로 전달한다.
python3 tools/admin/verify-resource-schedule.py \
  --resource-id '<검증 VM UUID>'

# 테스트 환경에서만 명시적으로 사용한다. 비활성, 2099년 시작 스케줄이다.
python3 tools/admin/verify-resource-schedule.py \
  --resource-id '<검증 VM UUID>' --disabled-crud-test
```

정상 정지한 Java의 종료 코드 143 때문에 systemd 상태가 `failed`일 수 있다.
`MainPID=0`과 정지 로그를 확인한 뒤 `systemctl reset-failed mold`로 상태를 정리한다.
실행 중인 프로세스를 둔 채 검사 우회를 위해 상태만 변경해서는 안 된다.

## 이관 안전성

도구의 기본 동작은 읽기 전용이다. 완전한 이전 스키마만 이관하며, 구/신 테이블이 혼재하거나
DDL 실행이 중간에 끊긴 상태는 거부한다. 이미 이관된 경우 필수 열, 인덱스, 외래 키와 설정을
검사하고 변경 없이 종료한다. 중복 작업, 고아 작업, 충돌 설정도 이관 전에 거부한다.

이관 전후 스케줄/작업의 전체 필드, 관련 이벤트 ID/유형, 스케줄 설정 값의 해시를 비교한다.
기존 VM 스케줄에는 `VirtualMachine` 유형을 부여하며 VM 작업 실행 이벤트 이름은 바꾸지 않는다.
예약 CRUD 이벤트만 `VM.SCHEDULE.*`에서 `SCHEDULE.*`로 변경한다.
DB 버전 번호를 임의로 변경하거나 전체 버전 업그레이드 SQL을 재실행하지 않는다.

MySQL DDL은 자동 커밋되므로 도구는 트랜잭션 롤백을 보장하지 않는다. 중간 실패 시 관리 서비스를
계속 정지한 상태로 유지하고, 백업과 현재 스키마를 대조해 복구한다. 신형 스키마에 이전 JAR만
되돌리면 안 된다. 롤백은 JAR, 두 스케줄 테이블, 설정 이름, 실제 변경한 이벤트 ID를 함께 복원해야 한다.
백업의 `configuration` 전체를 무검토 복원하거나 이벤트 백업을 중복 INSERT하지 않는다.
모든 관리 서버 정지 및 외부 DB 쓰기 통제는 운영자가 보장해야 한다.

## 검증 근거

- 13번 기준 Java 테스트 55건: VM API 16, 신형 API 권한 1, 관리자 권한 2, 자원 관리자 15, VM 워커 17, 그룹 워커 4.
- 22/32번 런타임 차이를 반영한 서버 테스트 38건 통과.
- Python 가드 테스트 5건 통과. 32가지 테이블 조합으로 완전/혼합/부분 스키마를 검사.
- MySQL 8.0 별도 DB: 스케줄 2건, 작업 2건, CRUD 이벤트와 실행 이벤트를 넣어 이관 및 재실행 통과.
- 13번 실제 새 API 발견, 신/이전 API 조회, 비활성 스케줄 CRUD 후 정리 통과.
- 13번 `rocky9-vm` 상세 화면에서 스케줄 탭, 목록, 추가 폼의 VM 동작/시간대/기간/활성화 필드 확인.
- 활성 예약에 의한 실제 VM 시작/정지, 오토스케일 그룹 멤버 증감 실행은 이번 검증 범위가 아니다.

기본 확장 3종과 Network는 이 기능 배포 후에도 Enabled 및 경로 준비 상태를 유지하는지 확인한다.
확장 경로 인식과 외부 하이퍼바이저를 통한 실제 VM 생성 성공은 별개의 검증이다.

## 테스트 클러스터 결과

2026-09-12 적용 및 확인 결과다. 모든 대상에서 `/client/` HTTP 200, `WEB-INF` 보존,
신형 API 4종 및 이전 API 조회, 신형 워커 3종 초기화, 기존 확장 Enabled/path_ready=1을 확인했다.
스케줄과 작업은 최종 0건이며, 13번에서 만든 비활성 검증 데이터도 API로 정리했다.

| 클러스터 | VM 상태 수 | 활성 보호 수 | 백업 디렉터리 |
| --- | --- | --- | --- |
| 13 | Running 17 / Stopped 3 | 0 | `/root/europa-resource-schedule-20260912-2F3nEv` |
| 22 | Running 17 / Stopped 28 | 5 | `/root/europa-resource-schedule-20260912-nZ8EIR` |
| 31 | Running 18 / Stopped 8 / Expunging 1 | 0 | `/root/europa-resource-schedule-20260912-YjNGDJ` |
| 32 | Running 20 / Stopped 18 | 4 | `/root/europa-resource-schedule-20260912-Aqxjz6` |

각 서버의 VM ID/상태/호스트 및 활성 보호 ID/원본 VM 연결은 배포 전후 동일했다.
보호 인벤토리 유지가 FT/DR 전체 실행 시험 통과를 의미하지는 않는다.

배포 후 관리 JAR SHA256:

```text
13 d71660a883c45e76da8387fd3a6a03c9d9bb9fb717cffe22268d9904e630a9ef
22 abc4deee0c8235f57c971fa24180e56277782cec199242c02dd0d82141eaa5d6
31 971986b36e364365ee357b2dc2a23080dc73204a6bf25bf297dd7163376c826a
32 d4e450be8429193fe878c8ed9ab0ab0197cacc44604679c350de743ec8b6940b
```

런타임 소스 보존 브랜치: `codex/resource-schedule-runtime13` (`2a9159d424a`),
`codex/resource-schedule-runtime22` (`a990f984a37`). 신형 권한 검사 수정은 각 설치 기준의
APIChecker 호출 규약에 맞추되 사용자/프로젝트/API 키 ACL과 호출 제한 중복 차감 방지를 유지했다.
이 브랜치들은 특정 설치본용 기능 배포 근거이며 최신 Europa 전체를 대체하는 릴리스 브랜치가 아니다.
