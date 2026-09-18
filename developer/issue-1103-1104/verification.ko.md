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

# 검증 및 배포 상태 — 2026-09-16

## Docker 검증
Rocky Linux x86_64 개발 컨테이너에서 수행했다.
- KVM package / Checkstyle: 성공. 테스트 993개, 실패 0, 오류 0, skipped 3.
- API/core/server 의존 모듈 빌드 및 Checkstyle: 성공.
- StatsCollectorTest: 66개 통과.
- UI: 57 suites / 523 tests 통과, production build 성공.
- hangctl smoke: 8개 스크립트 통과. PR58 기존 보호와 신규 실제 flock 충돌/해제/UNKNOWN 검증 포함.
- guard timeout/interrupt 프로세스 회수, renewal 취소, 부분 lease 보호, 통계 카운터 감소 후 baseline 재설정 검증 포함.

## 13번 실물 결과
대상 W2025-Base UUID `726c3e62-a867-4d4f-ae8b-ff742db97cef`, host `10.10.13.3`.
메모리 스냅샷 `issue1103-memory-validation` UUID `cf639d50-04b9-41e4-a158-f89f1bf6e1b0`:
- API job `011348cc-2e7d-494d-959f-b4353459bf6d`
- 12:43:27 생성, 13:12:16 완료(KST): 28분 49초.
- jobstatus=1, snapshot Ready / DiskAndMemory, VM Running.
- 기존 사용자 snapshot `7c540c82-8555-4d1b-a178-649b073f28a3` 보존.
- 작업 중 lease 갱신, stats/QGA 및 hangctl lock-busy 보호 확인. 실제 guard probe는 해당 VM을 skip하고 다른 정상 VM을 admitted 처리했다.
- 완료 후 lease 제거 및 실제 공통 flock 해제 확인. 남은 virsh/flock 프로세스 없음(확인 시점).
- Stats-Worker=2, GuestNetwork-Worker=1; 완료 후 Active/Queue/Pending=0, 완료 건수 증가. hangctl timer active, 13:12/13:13 scan 정상 종료.
- fio VM들과 같은 GFS를 사용했고 합계 약 100MiB/s QEMU 쓰기가 짧은 표본에서 관측됨. fio-test-vm1 flatten 설정 100MiB/s를 사용자 지시대로 유지. 지연의 단일 원인으로 단정하지 않음. 개선 정책은 Cloud #1105에 분리.

## 배포 범위와 최종 커밋 차이
- cluster13 세 KVM host에 작업 보호 KVM 모듈 및 hangctl consumer를 배포했고 agent 재시작 전후 running VM UUID 집합 유지 확인.
- 위 메모리 생성 실물 검증의 host3 KVM JAR SHA256: `5444bd876c5ac723456da13791cda7773a14a87d316066b4e1dde57824d8b1df`.
- 실물 검증 artifact는 최종 PR 전체와 동일하지 않다. host3에는 이후 counter baseline 보완과 interrupt flag 보존 변경이 아직 배포되지 않았다. host1/2에는 counter 보완이 있으나 최종 interrupt 보완은 미배포이다.
- API/core/server freshness 표시 및 UI는 Docker 빌드/테스트 완료, 실서버 미배포. 준비한 관리 서버 bundle은 기존 #1097 보정 클래스를 보존하고 관련 5개 클래스 계열만 교체한다. DB 변경 없음.
- 따라서 최종 PR의 전체 기능을 실물 검증 완료라고 주장하지 않는다.

## 미완료 항목
- 메모리 snapshot 복원/삭제, disk-only 생성/복원/삭제의 최종 구현 실물 검증.
- 최종 KVM artifact 및 freshness backend/UI 배포와 실제 상태 표시 검증.
- host reboot/장애 주입, migration/backup/blockcopy 전체 조합 검증.
- 세부 skipReason/operationId의 API 전달과 자동 잔여 lease 재조정은 미구현. 현재 UNKNOWN은 수동 확인 후 복구하며 TTL로 자동 삭제하지 않는다.

이 문서는 정상 PR 검토를 위한 정확한 현재 범위이며 #1103/#1104를 자동 종료하지 않는다. 설계·각 파일의 추가/수정/삭제 이유는 design.ko.md 참조.
