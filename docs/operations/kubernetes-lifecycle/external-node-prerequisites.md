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

# 외부 Kubernetes worker 추가·제거의 사전조건

`addNodesToKubernetesCluster`는 이미 준비한 VM을 기존 관리형 클러스터의 worker로 연결합니다. 외부 VM은 Running이어야 하고 CKS용으로 표시된 template에서 생성되어야 하며, 기본 NIC가 대상 클러스터 네트워크에 있어야 합니다. 다른 클러스터에 연결된 VM, 중복 ID와 실패·중지 VM은 먼저 거부합니다.

## 추가 전 확인

- 클러스터는 Running 또는 Alert이고 진행 중인 다른 생명주기 작업이 없어야 합니다.
- VM의 ROOT와 backing storage, DHCP 주소, DNS와 control-plane 경로가 정상이어야 합니다. GFS2 시험에서는 실제 ROOT pool을 GFS2 Primary로 확인합니다.
- guest의 SSH가 TCP 22에 응답하고, 대상 클러스터가 사용하는 관리용 SSH 사용자가 Mold 관리 서버 공개키로 로그인할 수 있어야 합니다. 관리 서버 개인키를 guest에 복사하지 않습니다.
- 해당 사용자에서 `sudo -n true`가 성공해야 합니다. script 설치, cloud-init 준비와 kubeadm 작업에는 비대화형 sudo가 필요합니다.
- cloud-init이 활성화되어 있고 CloudStack metadata/user-data를 처리해야 합니다. 현재 배포한 guest에서 `cloud-init status --long`, unit 상태와 `/etc/cloud/cloud-init.disabled`를 확인합니다.
- swap을 비활성화하고 재부팅 후에도 활성화되지 않는지 확인합니다. 기본 kubelet 정책은 활성 swap에서 시작하지 않습니다.
- 노드 의존성 검사 `validate-cks-node`가 성공해야 합니다. Kubernetes 바이너리·이미지는 클러스터가 참조하는 고정 ISO를 사용합니다.

SystemVM을 기반으로 만든 CKS 전용 template은 일반 VM으로 배포했을 때 위 조건을 자동으로 충족한다고 가정하면 안 됩니다. SSH host key 초기화, SSH 포트, cloud-init 활성화와 sudo 정책을 guest image 준비 단계에서 갖춘 뒤 새 기준선에서 시험합니다. API를 통한 VM Running만으로 guest 준비를 판단하지 않습니다. 재부팅 후 DHCP 주소와 기본 경로가 유지되는지도 확인합니다.

Mold KVM의 DMI 제품명이 CloudStack과 다르면 cloud-init의 datasource 탐색이 자동으로 비활성화될 수 있습니다. CloudStack 전용 image에서는 `datasource_list: ['CloudStack']`를 명시하고, 필요한 경우 image에 `/etc/cloud/ds-identify.cfg`의 `policy: enabled`를 설정합니다. 이는 [CloudStack cloud-init 설정 가이드](https://cloudstack-documentation.readthedocs.io/en/latest/adminguide/templates/_cloud_init.html)의 방식이며, 실제 metadata 응답과 재부팅 후 cloud-init 완료를 확인해야 합니다. 시험 중 OS 사전조건을 보정한 결과는 최초 자동 배포 성공으로 계산하지 않습니다.

## 완료 확인과 업그레이드 정책

추가 작업의 job 결과와 VM/map/count를 확인하고, Kubernetes에서 실제 Node Ready·providerID/VM UUID·버전을 별도로 확인합니다. `manualupgrade=true`로 연결한 외부 worker는 전체 클러스터 업그레이드 대상에서 제외됩니다. 운영자는 해당 노드의 수동 업그레이드와 실제 버전을 따로 관리해야 합니다.

## 제거와 재시도

외부 worker 제거는 클러스터 연결을 해제하며 원래 VM을 파괴하지 않습니다. mapped external worker만 대상으로 하며 control/etcd 및 관리형 worker는 이 API로 제거할 수 없습니다.

노드 drain은 PDB, controller가 없는 Pod와 지역 emptyDir 데이터를 보호합니다. 강제 drain으로 이 차단을 우회하지 않습니다. 실패하면 작업 실패를 반환하고 VM/map/count를 보존하며, 실패한 drain이 만든 cordon만 복원합니다. 운영자가 미리 cordon한 상태는 보존합니다.

native reset/delete나 클러스터 소유 SSH 규칙 정리가 실패한 경우 완료 영수증과 실패 매핑을 유지해 재시도합니다. 사용자 포트포워딩·공유 규칙은 제거 대상으로 삼지 않습니다. 과거 외부 노드에 검증된 소유권 영수증이 없으면 포트 번호만으로 소유권을 추정하지 않고 정리를 중단합니다.

성공한 kubeadm reset 뒤에는 CKS의 `/home/cloud/success` 완료 marker를 제거합니다. 같은 준비 VM을 다시 추가할 때 설치를 건너뛰지 않도록 하는 처리이며, 원래 VM과 일반 데이터 볼륨은 보존합니다.

클러스터 분리가 확인된 뒤 VM 자체를 삭제하려면 별도의 VM 삭제 작업을 실행합니다. 보존 정책과 ROOT/데이터 볼륨 처리 범위는 이 VM 삭제 단계에서 명시합니다.
