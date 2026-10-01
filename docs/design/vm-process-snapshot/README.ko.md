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

# C4: 프로세스 snapshot 조회와 명시적 전역 활성화

관련: Cloud #1174, Epic #1170. C1 계약 commit `7573322eb7fe81fb7a42566e6e8ce4eb32e38acf`.
Cloud 누적 기준: C2 `51cb46e62d6452e0dbdf061e1cae83f344b53991` + C1 병합 `5125010873f9d56bfececfe7b0675af0752e2bf9`.
qemu 누적 기준: Q4/SELinux 보강 `610b106ad9ab6939a2c22752555c70d61af83ff0`.

## 활성화

Global 설정 `vm.process.management.enabled`는 기본 **false**이며 동적으로 반영한다.
관리자가 Global 설정에서 명시적으로 true로 설정해야 capability/refresh/list API를 사용할 수 있다.
종전 `vm.process.capability.enabled`, `vm.process.capability.test.vm.uuids`는 등록을 제거하고
기존 DB 행도 동일 버전 마이그레이션에서 정리한다. 관리자와 테스트 VM도 새 전역 설정을 우회할 수 없다.
설정 false에서는 Agent 실행/캐시 조회를 거부하며 진행 중 수집의 결과도 반환하지 않는다.
이 설정은 향후 변경 API에도 동일하게 적용해야 한다. 현재 종료·재시작은 구현/노출하지 않는다.

## API

- `refreshVirtualMachineProcesses&virtualmachineid=UUID`: 비동기 작업. 작업 결과의 `processsnapshot`은
  snapshotId/관측 시각/TTL/status/행 개수만 반환하고 `processes=[]`이다. Cloud async job 결과에 프로세스
  목록을 영속 저장하지 않는다. 수집 오류는 `availability=UNAVAILABLE`와 계약 failure로 구분한다.
- `listVirtualMachineProcesses&virtualmachineid=UUID&snapshotid=UUID`: 수집 없이 고정 snapshot 페이지를 반환한다.
  `page` 기본 1, `pagesize` 기본 50/최대 200, `keyword` 프로세스 이름의 대소문자 무시 부분 검색(최대256자),
  `sortby=pid|name|memoryBytes`, `descending=true|false`를 지원한다. 동률은 PID로 안정 정렬한다.
- 응답: `processsnapshot.processstate`에 C1 공개 투영, `count`에 필터 일치 행수, `stale`, `availability`를 둔다.
  snapshot 전체를 검증한 뒤 페이지 투영하므로 OK의 totalKnown은 페이지 행수가 아닌 원본 수집 행수다.
  내부 hostUuid/placementGeneration은 공개하지 않으며 command line/environment/임의 파일/명령 입력을 제공하지 않는다.

API 역할 권한과 VM account/domain/project 접근 검사를 적용하고, 캐시 조회도 현재 권한을 재검사한다.
원래 VM 소유권이 바뀌면 이전 사용자는 캐시를 읽을 수 없다. UI가 아닌 Agent 경로로만 실행한다.

## 수명과 부하 제한

snapshot은 관리 서버 노드 메모리에만 최대64 VM, 각1MiB/10,000행, TTL10초로 보관한다.
같은 VM의 진행 중 수집은 BUSY, 5초 이내 완료된 수집은 동일 snapshot을 반환한다. 대기열은 만들지 않는다.
관리 서버 수집 admission은8, Agent는 기존 monitoring8개 admission을 공유한다.
호스트도 VM flock 아래에서 새 Cloud 수집을5초 간격으로 제한하므로 다른 관리 서버를 통한 우회를 막는다.

다른 관리 서버 노드로 라우팅되거나 서버가 재시작하면 snapshot은 STALE_SNAPSHOT이다. 공유 DB에 저장하지 않으며
다중 관리 서버에서는 동일 노드로 API 세션을 라우팅해야 한다. 만료/없는 snapshot을 묵시적으로 다시 수집하지 않는다.
현재 호스트, VM UUID, update_count 세대, Running 상태를 수집 전후 대조하며 페이지 조회에도 배치를 확인한다.
마이그레이션/세대 변경 결과를 버리고 새 수집을 요구한다. 실제 lifecycle 변경 E2E는 C7의 후속 검증이다.

## Agent / qemu 경계

고정 명령 `vm_exec --process-protocol 1.0 --request-json PRIVATE_FILE --cloud-read-guard-fd 9`만 호출한다.
Agent의 관리 자식 프로세스가 VM별 flock을 FD9에 획득하고 exec로 그대로 전달한다. 별도 flock을 중복 획득하지 않는다.
0600 private request, 상위0700 디렉터리, regular/no-symlink/소유자 및 inode 검사, 실제 공유 flock 소유 확인,
부모 생존용 stdin pipe를 함께 검증한다. 환경변수만으로 잠금을 건너뛰지 않는다.
부모 채널이 닫히면 새 guest 실행을 거부한다. 실행 후 결과 불명은 기존 qemu lease를 보존하며 자동 삭제/재시도하지 않는다.
Cloud lifecycle guard/hangctl과 동일한 VM lock/잔존 lease를 사용하고 libvirt domain/job/block-job도 확인한다.
조회 전용이며 mutation의 분산 fencing을 구현했다고 주장하지 않는다.

stdout은 읽는 중1MiB 상한, stderr 폐기, UTF-8 strict decode, JSON 중복 키/추가 필드/정밀도 손실/identity 혼합을 거부한다.
Agent와 Management 모두 동일 validator로 검증한다. startTicks는 끝까지 문자열, CPU는 null, allowedActions는 빈 배열이다.
qemu의 host5초/guest3초 예산과 Agent6초/Management10초 제한을 사용한다.

## 검증 및 배포

WSL ext4에서 api/core/server/KVM 변경 모듈만 빌드한다. 전체 Cloud 빌드는 실행하지 않는다.
qemu 변경은 해석 실행하는 Python 호스트 스크립트이며 패키지/ISO/native 빌드 없이 두 호스트에 직접 배포한다.
기존 guest adapter/SELinux 정책/ISO는 변경하지 않는다.

### 2026-09-27 검증 결과

- 변경 Maven 모듈 api/core/server/KVM 빌드 성공, Checkstyle 통과. Java25개(snapshot8/capability5/probe5/guard7) 통과.
- qemu 신규 guard7개 + 기존 transport14개/collector14개 통과. 실제 세 게스트의 내부 envelope C1 schema/불변조건 검증 통과.
- 최초 API 검증에서 기존 response-only JSON adapter가 async job 역직렬화를 거부하는 문제를 발견해 수정하고 round-trip 회귀 테스트를 추가했다. 수정 후 전체 API 시나리오를 다시 통과했다.
- 실제 API: Rocky10.2 i-2-15-VM 206행/33 service 매핑(1.330초), WindowsServer2025 i-2-27-VM 86행/66매핑(2.115초), Ubuntu26.04 i-2-28-VM 131행/21매핑(1.075초). 시각별 프로세스 수는 변할 수 있다.
- 기존 capability.enabled=true 및 test allowlist를 설정해도 새 전역 설정 false일 때 capability/list/refresh 모두 차단됨을 실증했다.
- 명시적 true 이후 새 수집, 동일 snapshot coalescing, 페이지 중복/누락 없음, 이름 검색/메모리 정렬, 200행 제한,
  10초 TTL 후 STALE_SNAPSHOT, false 전환 후 캐시 접근 차단을 실제 API에서 확인했다.
- 검증 후 새 전역 설정은 false, 종전 설정 두 개도 원래 값으로 복원했다. 실제 VM 종료/재시작/이동은 수행하지 않았다.
- cross-tenant/권한 회수, 수집 중 배치 변경/비활성화, malformed/추가 필드/중복 JSON/숫자 ticks/PARTIAL은 Java 회귀 테스트로 검증했다.
  실제 migration/reboot·전체 OS 매트릭스·다중 관리 서버 장애 검증은 C7에 남는다.
- 최초 rollback backup: 관리 서버 `/root/issue1174-deploy-20260927-173822`, 31.1 `...-173816`, 31.3 `...-173819`.
  qemu 원본 스크립트: 31.1 `/root/issue1174-scripts-20260927-173425`, 31.3 `...-173426`.
- 최종 overlay: 관리 서버 `/root/issue1174-deploy-20260927-174214`, 31.1 `...-174208`, 31.3 `...-174211`.
  기존 JAR의 관련 클래스와 신규 Spring bean만 교체했고 다른 클래스는 보존했다. 정식 패키지 재설치 때 overlay가 덮어써질 수 있다.
- 설치 바이트 대조: 관리서버 api9/core7/server3, 각 호스트 api9/core7/KVM4 클래스 일치. mold/mold-agent active,
  WEB-INF 보존, /client/ HTTP200, 세 VM Running, 테스트 VM 잔여 operation lease 없음.
- qemu process_list_host.py SHA256 `c2747f16b1c510f8f3d62a6390d613813ac8dddd08a1a39ac5da55488d2d329e`.
- 원본 증거: WSL `/home/ablecloud/work/validation/cloud-issue1174`, 빌드 로그 `/home/ablecloud/work/c4-*.log`.
  최종 API 증거 `live-api-final.jsonl`. 인증 정보/API key 변경·저장은 하지 않았다.
