<!-- Licensed to the Apache Software Foundation (ASF) under one
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
     under the License. -->

# Mold Kubernetes 생명주기 운영 인계

Cloud 대상은 `ablestack-europa`입니다. ISO는 [전용 저장소](https://github.com/ablecloud-team/ablestack-kubernetes-iso)의 고정 recipe/Actions/Release를 사용합니다. 이 문서는 해당 저장소들을 클론하거나 포크한 환경에서도 적용할 운영 계약이며, 특정 Origin URL을 공식 다운로드 주소로 고정하지 않습니다. 실제 실행 결과는 [Epic #1227](https://github.com/ablecloud-team/ablestack-cloud/issues/1227)과 [31번 시험 #1230](https://github.com/ablecloud-team/ablestack-cloud/issues/1230)의 버전·artifact·job별 기록을 따릅니다.

## 등록과 신규 배포

1. 대상 채널의 ISO Release asset, manifest, recipe source commit 및 SHA256을 확보합니다. mutable tag를 기존 등록 artifact 위에 덮어쓰지 않습니다. trial 산출물과 공식 Release를 구분합니다.
2. Mold URL 등록/업로드 후 Zone에서 다운로드 Ready 및 실제 파일 크기·SHA256을 확인합니다. HTTP redirect가 필요한 경로에서는 `store.download.follow.redirects`의 기존 값을 기록하고 필요한 다운로드 동안만 설정한 뒤 복원합니다.
3. 계정/프로젝트 전체 control·worker·external-etcd의 VM, CPU, 메모리, ROOT 및 IP quota와 역할별 template/offering, SSH, 네트워크/ACL을 검사합니다. VPC의 ACL 누락/default-deny를 임의 허용으로 바꾸지 않습니다.
4. 노드 template는 고유 machine-id와 재부팅 후 작동하는 datasource/DHCP/sudo를 갖춰야 합니다. [외부 노드 요구조건](external-node-prerequisites.md)을 참고합니다. 사용자 CNI cloud-config는 Kubernetes 기본 cloud-config와 MIME 병합되며 기본 bootstrap 파일을 대체하지 않아야 합니다.
5. 생성 job과 단계뿐 아니라 기대 VM/Node providerID·실제 patch 버전/Ready·CNI/kube-proxy·CCM/AS/CSI 준비 및 앱 DNS/HTTP를 확인합니다. CSI ISO는 내부 SHA256 드라이버와 지원 GFS2 profile을 선택합니다. base ISO에서 동적 CSI가 설치된 것으로 가정하지 않습니다.

31번 시험은 `Primary` SharedMountPoint/GFS2만 사용했습니다. CLVM/CLVM_NG 및 다른 storage profile에 결과를 확대하지 않습니다.

## 운영·확장·중지와 시작

Mold의 CPU/메모리 합계는 실제 매핑된 노드 할당량입니다. VM 전원, Kubernetes Ready, CNI data path, controller/workload 준비를 구분합니다. 자동 확장은 target Kubernetes minor에 맞는 내부 AutoScaler를 사용하며, 실제 Pending Pod→Mold job→VM→Node→Pod 배치와 축소 후 ROOT/규칙 정리를 함께 확인합니다.

worker 수만 변경할 때 control/worker/etcd의 기존 역할 offering을 유지합니다. PDB 차단, drain 실패, join/Ready 시간 초과는 실패로 기록하고 원래 노드 및 재시도 receipt를 남깁니다. 관리형 노드의 일반 VM 정지는 Kubernetes drain을 수행하지 않으므로 [워커/LB 유지보수 절차](worker-load-balancer-drain.md)를 적용합니다. 외부 VM 제거와 ExternalManaged 등록 해제는 VM 파괴와 구분하며, `cleanup`/`expunge`를 명시한 요청만 해당 파괴 계약을 사용합니다.

시작 완료는 현재 API/CNI 및 설치된 CSI controller/node Pod 준비를 확인한 뒤 판정합니다. Stopped 상태의 오래된 관측을 현재 건강도로 표시하지 않으며, 구성 다운로드/갱신은 제한 역할과 인증서 갱신 후 실제 CA를 확인합니다.

## 업그레이드와 실패 재시도

patch 또는 인접 minor와 지원되는 정확한 artifact UUID를 사용합니다. source/target pin, 노드별 원래 VM/Node UID와 cordon을 보존합니다. 같은 artifact 요청으로 성공한 작업을 다시 drain하지 않으며, 부분 실패에서는 최초 pin과 같은 target으로만 재개합니다. 다른 ISO를 자동 교체하지 않습니다.

인터넷 차단 환경에서는 **모든 원래 노드의 target ISO SHA 검증·이미지 import를 먼저 완료한 뒤** 순차 drain/업그레이드를 시작합니다. manual-upgrade 표시 노드는 cache 준비만 수행하고 binary upgrade에서 제외합니다. 이미지 준비 실패는 drain 전에 중단합니다. 애플리케이션 이미지는 운영자가 별도 준비해야 하며 core ISO가 임의 workload 이미지를 포함한다고 가정하지 않습니다.

manifest 적용은 명시적 admin kubeconfig와 요청 제한 시간을 사용합니다. transport/unavailable/timeout 오류만 API readiness 확인 후 최대 3번 재시도합니다. validation/authorization 오류는 즉시 실패하며, 단계/exit/안전한 오류 범주만 전달합니다. 원문 명령·설정·인증 정보는 이슈/로그에 붙이지 않습니다. [중단 복구 절차](interrupted-operation-recovery.md)와 [인증서·etcd 복구](certificate-etcd-recovery.md)를 함께 확인합니다.

운영자 사전 cordon은 성공 후에도 유지합니다. 작업이 만든 cordon만 정상 완료 후 해제하며, 필수 control-plane affinity로 controller를 가두지 않습니다. 내부 CSI controller는 control-plane 선호 및 Ready worker fallback을 지원합니다.

## 정상 삭제와 데이터 인계

삭제 전에 PVC/PV의 Delete/Retain, 실제 volume handle, 파일 checksum 및 저장소 경로를 기록합니다. 정상 Delete는 UID/소유권으로 Service/LB/IP/ACL/CSI를 정리하고 결과를 확인한 뒤 노드를 제거합니다. controller/storage 정리가 실패하면 노드·receipt·데이터를 보존하고 정상 재시도합니다. 수동 ACL/FW/PF, 공유 IP의 다른 Service 및 다른 할당 세대의 IP를 제거하지 않습니다.

최종 확인은 cluster/map 부재뿐 아니라 ROOT/동적 Delete DATA의 Expunged/removed, GFS2 실제 파일 부재와 원래 libvirt 도메인 부재까지 포함합니다. Retain DATA와 외부 NFS export는 의도적으로 남기고 checksum·helper VM·회수 절차를 인계합니다. 외부 Retain 데이터를 직접 지운 결과를 CSI 자동 Delete 성공으로 계산하지 않습니다.

실패 생성의 API 이전 cleanup은 확인된 FAILED Create/no active Create, 영속 단계·blank endpoint, 미초기화 Provider 및 정확한 노드/네트워크 소유권 조건을 모두 확인해야 합니다. API가 이미 초기화된 legacy Starting 실패는 검증된 정상 실패 상태 전이 후 기존 전체 API cleanup을 사용합니다. DB 상태 변경이나 receipt 삭제로 장벽을 우회하지 않습니다.

클러스터별 scoped API 키는 정상 Delete 뒤 실제 기존 SHA256 요청이 거부되는지 확인합니다. 프로젝트 서비스 계정/Regular membership은 다른 클러스터와 이후 생성에 사용하므로 유지하고, 프로젝트 cleanup 때 로컬 machine-account 경로로 제거합니다. 외부 IAM 계정 삭제를 호출하지 않습니다. 일반 계정 키와 다른 클러스터 키는 회전·폐기하지 않습니다.

## 결과와 Release 인계

[지원·검증 행렬](qualification-20261007.md)에서 최초 clean 성공, 실패 후 복구, 제한 환경 및 미완료 관측을 구분합니다. 진행 단계는 담당 기존 이슈를 갱신하며 새 결함 등록 전 기존 이슈 범위를 대조합니다. 공통 구현의 반복 패치나 동일 시험 클러스터의 중복 생성을 하지 않습니다.

PR 준비는 최신 HEAD 전체 License Check 통과 및 Conflict 없음입니다. 전체 Cloud build/통합 검사는 최종 Release build에서 수행합니다. PR 준비, Upstream 병합, 공식 ISO Release와 24시간 관측 완료를 서로 같은 판정으로 표시하지 않습니다. `1.37.1` AutoScaler는 [2026-10-07 사용자 판정](https://github.com/ablecloud-team/ablestack-cloud/issues/1228#issuecomment-6038989411)에 따라 Mold 내 프로덕션 레벨/실환경 PASS로 인정합니다. 외부 원본 고정 commit의 development-candidate 출처와 당시 DEV 리소스 이름은 보존합니다. ISO 게시 정책은 stable 원본 또는 정확한 빌드에 묶인 Mold 프로덕션 판정을 인정하며 나머지 공식 게시 조건은 각각 검사합니다.

## Service UID로 native 리소스 확인

Mold 화면의 Service UID는 이름이나 네트워크 추정 대신 정확한 Service와 연결하는 값입니다. namespace/name은 다음 읽기 전용 조회로 확인합니다. UID 태그만으로 삭제 권한을 판단하지 않고 account/project/network/IP allocation generation도 함께 검증합니다.

```bash
SERVICE_UID='<화면에 표시된 Service UID>'
kubectl get service -A -o json | jq --arg uid "$SERVICE_UID" '.items[] | select(.metadata.uid == $uid) | {namespace: .metadata.namespace, name: .metadata.name, uid: .metadata.uid}'
```

## 관리 add-on과 운영자 cordon

검증된 ISO의 CCM·Headlamp Deployment에는 control-plane/master NoSchedule toleration만 추가합니다. 일반 워크로드, 사용자 affinity, Node taint와 cordon은 변경하지 않습니다. Provider rollout은 업그레이드 drain 전에 확인합니다. 기존 등록 ISO에도 같은 consumer 정책을 적용하며 ISO 원본 파일과 해시는 유지합니다. 모든 노드가 운영자 cordon 상태이거나 사용자 강제 affinity로 관리 Pod의 배치가 불가능하면 정상 완료로 처리하지 않습니다.
