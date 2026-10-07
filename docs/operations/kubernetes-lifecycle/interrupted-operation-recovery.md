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

# management 재시작으로 중단된 Kubernetes 작업

Mold management가 실행 중 node 작업을 취소하면 기존 async job은 FAILED이며
재시작/종료 취소 사유를 유지한다. 이를 원래 작업의 성공으로 바꾸지 않는다.

scanner는 Upgrading, Scaling, ScalingStoppedCluster, Importing, RemovingNodes의
최신 job을 해당 cluster resource type/id·실제 command와 대조한다. 진행 중 job이
없고 최신 job이 management restart/shutdown 취소로 FAILED인 경우만
OperationFailed 전이로 transient state를 해제한다. 일반 timeout·다른 command·
다른 resource·진행 중·성공 job의 상태를 이 절차로 변경하지 않는다. scanner는
시작 후 약 5분부터 주기적으로 실행한다.

재시작 뒤에는 원래 VM/Node UUID·role/map/count, native 버전/Ready, 사용자 cordon,
ISO source/target pin·연결 상태와 데이터/PV checksum을 확인한다. 원격 node script가
계속 실행 중일 수 있으므로 대상 노드에서 원래 작업 종료와 건강도를 확인한 뒤
재시도한다. 취소된 Java worker가 없다는 사실만으로 node 작업 완료를 가정하지 않는다.
원문 kubeconfig/키/서명된 URL을 로그나 이슈에 붙이지 않는다.

부분 업그레이드는 같은 target **artifact UUID**로만 재시도한다. source/target pin은
전체 성공 전까지 유지하며 다른 target, 수동 scale, 참조 중 ISO 변경/삭제는 거부한다.
원래 사용자 cordon은 Ready 건강도와 별도로 보존한다. 재시도에서는 native Ready
condition과 실제 노드 버전, controller/workload/PV 결과를 확인한 뒤 성공을 판정한다.
VM power 상태만으로 전체 upgrade 완료를 표시하거나 DB 상태를 수동 수정하지 않는다.

외부 노드 추가/제거 중단도 실제 native Node/providerID·VM map·소유 PF/FW와 durable
receipt를 대조한다. 실패/부분 완료 receipt는 정상 재시도에 사용하며 사용자 VM·수동
규칙을 삭제해서 오류를 숨기지 않는다. 원인 불명 또는 일치하는 취소 job이 없는
transient state는 자동 해제하지 않고 별도로 진단한다.

이전 업그레이드·확장 작업에 resource ID가 없으면 저장된 요청의 클러스터 UUID와 명령/리소스 종류를 정확히 비교합니다. 검색 범위가 완전하지 않거나 실행 중인 작업이 있으면 복구하지 않습니다. 새 작업은 클러스터 resource ID를 기록합니다. 요청 원문에는 인증 정보가 있을 수 있어 로그에 출력하지 않습니다.

중단된 업그레이드가 target ISO를 이미 연결했으면 정확히 같은 target ISO를 재사용합니다. 다른 ISO는 자동 분리/교체하지 않습니다. 재시도는 부분 native 버전 및 cordon을 유지하고, 전체 완료 뒤 정상 분리·pin 정리 경로를 거칩니다.
