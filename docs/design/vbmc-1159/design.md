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
# Europa Virtual BMC 할당 수명주기 (#1159)

## 범위와 원칙

- 실행 중인 KVM VM 및 정상 연결된 호스트만 할당한다. HA VM은 외부 IPMI 전원 제어와 충돌하므로 제외한다.
- Europa는 Cloud가 중지 시 제거한 libvirt 도메인을 IPMI로 재생성하지 않는다. 중지·이동·삭제·복원 및 HA 활성화 전에 VBMC를 제거한다. 안정적인 이동/HA endpoint는 #1161 범위다.
- 테스트는 31번 클러스터이며 허용 IPMI 클라이언트 대역은 운영자가 지정한 `10.10.0.0/16`이다. 제품 기본값은 `127.0.0.1/32`로 외부 접근을 열지 않는다.
- 방화벽은 **firewalld + iptables backend**를 전제로 한다. 중지된 테스트 서비스는 활성화한다. 제품은 다른 backend로 자동 전환하지 않는다.

## 저장 상태와 원격 실패

`Unallocated → Allocating → Ready → Removing → Unallocated`.
원격 실패, 시간 초과 또는 확인 실패 시 `CleanupRequired`로 전환하고 VM·포트·원래 호스트·소유 토큰을 유지한다. 재할당하지 않는다.

관리 서버 간 공용 GlobalLock으로 포트 선택과 할당/삭제/확인을 직렬화한다. 삭제 성공 응답 전에 DB 포트를 해제하지 않는다. 관리 서버가 중간에 종료되어도 예약은 남으며 재삭제로 정리한다. 동일 VM 재할당은 기존 endpoint 상태를 확인하며 새 포트를 소비하지 않는다.

스키마 추가는 Europa S10 단계로 수행하고 반복 실행이 가능하다. 기존 할당은 host를 추정하지 않고 CleanupRequired로 남긴다. 기존 할당의 원래 호스트 확인 및 정리는 관리자가 수행해야 하며 임의 DB 해제로 포트를 재사용하지 않는다. 여러 관리 서버를 사용하는 설치에서는 이전/새 버전의 할당 로직을 동시에 운영하지 않는다.

## API 및 자격 증명

- `allocateVbmcToVM`: virtualmachineid, 필수 password, 선택 allowedcidr. 사용자 이름은 `vbmc`. 비밀번호는 16~20자 인쇄 가능한 ASCII이며 공백·퍼센트 기호는 제외한다.
- `checkVbmcToVM`: 현재 호스트/VM 상태, 소유 토큰, 방화벽 runtime/permanent 규칙, 인증된 IPMI chassis power status를 확인한다. 전원을 변경하지 않는다.
- `removeVbmcToVM`: 원래 기록된 호스트에서 반복 가능한 삭제를 실행한다.
- VM 응답: vbmcstatus, vbmcaddress, vbmcallowedcidr, vbmclasterror. 비밀번호를 반환하지 않는다.
- 자격 증명 교체는 삭제 후 재할당으로 수행한다. 기존 고정 자격 증명을 사용하지 않는다. Agent 로그에서는 비밀번호 필드를 마스킹하며 호스트 명령행에는 비밀번호를 넣지 않는다. 호스트 구성은 root 전용으로 저장한다.

## 호스트 구현

- 기존 관리 외 vbmcd와 분리된 daemon/config/제어 포트를 사용한다. systemd가 관리하며 재부팅 시 소유한 endpoint만 복구한다.
- 호스트 로컬 flock과 토큰으로 다른 할당의 endpoint를 삭제하지 않는다. libvirt UUID가 변경되거나 도메인이 사라지면 해당 endpoint를 중지한다.
- firewalld direct 규칙을 permanent/runtime에 각각 적용한다. 목적지 주소·UDP 포트별로 허용 대역 외 DROP을 먼저, 허용 대역 ACCEPT를 다음에 적용한다. 기존 ABLESTACK 서비스의 광역 포트 허용보다 앞서 적용된다.
- 전체 방화벽 reload/flush를 수행하지 않는다. 이미 열린 다른 서비스와 libvirt 규칙은 유지한다. firewalld 중지 시 전용 daemon도 중지한다.
- 프로세스·포트·구성 삭제를 확인하고 소유 방화벽 규칙을 제거한 뒤 manifest를 삭제한다. 어느 단계든 불확실하면 identity를 남겨 재시도한다.

## 검증 계획

변경 Maven 모듈(api/core/engine-schema/server/KVM)과 UI 모듈만 빌드하고 RAT를 수행한다. 호스트 단위 테스트는 소유권 충돌, 삭제 실패, 잔존 listener, 방화벽 실패, 자격 증명 argv 비노출을 확인한다. Java 테스트는 원래 호스트로의 정리, 실패 시 예약 유지, 성공 후 해제, 반복 요청, HA 차단을 확인한다.

31번 클러스터에서는 전용 VM으로 API 정상·반복·동시 요청, 허용/비허용 source 통신, firewall/daemon 복구 및 상태 불일치를 확인한다. 실제 수행 결과와 미검증 항목은 PR에 별도로 기록한다.
