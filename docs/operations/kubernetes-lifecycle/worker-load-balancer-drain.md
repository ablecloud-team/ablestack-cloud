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

# 워커 축소와 native Service LB 제외 순서

생명주기 시험 [#1230](https://github.com/ablecloud-team/ablestack-cloud/issues/1230)의
[축소 중 LB timeout #1268](https://github.com/ablecloud-team/ablestack-cloud/issues/1268)을
처리하는 Mold 워커 제거 절차입니다. native Service `LoadBalancer`가 존재하면 기존
PDB를 준수하는 drain보다 먼저 아래 절차를 실행합니다.

1. 실제 Node 이름/UID 및 전체 namespace의 Service UID를 읽습니다.
2. Node UID와 기존 labels를 JSON Patch test로 확인한 뒤
   `node.kubernetes.io/exclude-from-external-load-balancers=mold-draining`을 설정합니다.
3. 해당 Service UID에서 유래한 Provider LB 규칙 중 같은 클러스터 guest network의
   워커 backend가 없어질 때까지 실제 DB 상태를 확인합니다. revoke 처리 중인 mapping도
   제외 완료로 간주하지 않습니다. 최대 150초이며 조회 실패는 제거 중단입니다.
4. 같은 Node UID와 라벨 소유권을 다시 확인한 뒤 기존 bounded drain/PDB 검사를 실행합니다.
   성공 시 Node와 VM을 제거합니다. 제외 또는 drain 실패 시 기존 라벨 값을 복구하고,
   기존 drain 실패 처리의 uncordon을 유지합니다.

Provider Service controller의 node sync 주기는 100초입니다. 고정 대기 시간으로 성공을
추정하지 않습니다. 현재 Service 소유 규칙은 Provider와 동일한 `a` + Service UID의
하이픈 제거값을 32자로 자른 이름 prefix로 구별합니다. 다른 guest network나 수동 생성
LB 규칙을 이 기능이 직접 변경하지 않습니다. label 복구도 Node UID와 `mold-draining`
소유권을 확인하여 다른 운영자의 값을 덮어쓰지 않습니다.

확인할 증거는 연속 외부 HTTP 상태, Node UID/Ready/CNI 생존 상태, VM state,
Service EndpointSlice, 실제 Mold LB backend mapping 및 async job 결과입니다.
PDB 거부 시 Node/VM 및 데이터가 보존되고 라벨과 트래픽이 복구되는지 별도로 시험합니다.
현재 등록된 Service snapshot 이후 동시에 새로 생성되는 Service, 수동 LB 전체 정리,
HA control/etcd 제거 및 Node 삭제 후 응답 유실 복구는 별도 검증 대상입니다.

31번 GFS2/1.34.9에서는 기존 순서의 축소 timeout을 반복 재현했고, 운영자가 먼저
제외 라벨과 실제 backend 부재를 확인한 진단 시험에서 700초/6,992 HTTP 요청 오류 0을
관측했습니다. 이 진단 결과는 제품 코드 배포 검증을 대체하지 않습니다. 최종 제품 시험
결과는 #1268 및 #1230의 단계별 댓글에 기록합니다.

변경 모듈 검증:

```bash
mvn -pl plugins/integrations/kubernetes-service \
  -Dtest=KubernetesNodeLoadBalancerDrainTest,KubernetesClusterScaleWorkerTest,KubernetesClusterManagerImplTest,KubernetesVersionManagerImplTest \
  -DfailIfNoTests=false package
```

WSL ext4 clone에서 86 tests / failures 0 / errors 0 / skipped 0을 확인했습니다.
전체 Cloud 빌드와 6버전 장시간 qualification은 이 모듈 검증에 포함되지 않습니다.
