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

# Kubernetes 인증서 갱신과 etcd 복구

인증서·etcd 제어 데이터·PV 애플리케이션 데이터는 별도로 관리한다. PKI,
kubeconfig, etcd snapshot에는 자격증명이 포함되므로 소유자만 접근할 수 있는
private 경로에 보관하고 이슈·로그·PR에 원문을 붙이지 않는다.

## 인증서 갱신

1. 클러스터 UUID, control VM UUID/providerID, native Node UID와 역할을 대조한다.
   Node Ready, API/workload/PV checksum, etcd quorum을 확인한다. 각 control의
   `/etc/kubernetes`와 실제 etcd snapshot을 백업하고 인증서 serial/만료일,
   CA fingerprint를 기록한다. `kubeadm certs check-expiration`으로 대상을 확인한다.
2. HA control은 한 대씩 `sudo /opt/bin/kubeadm certs renew all`을 실행한다.
   CA는 재발급하지 않는다. 외부 CA 또는 별도 `user.conf` client 인증서는 해당
   발급자·RBAC 정책에 따라 별도로 갱신한다. `renew all`이 모든 사용자 인증서를
   자동 갱신한다고 가정하지 않는다.
3. 갱신한 `admin.conf`를 해당 control의 `/root/.kube/config`에 mode 0600으로
   반영한다. static Pod manifest는 `/etc/kubernetes/manifests` 밖의 private
   보관 경로로 이동했다가 kubelet이 종료를 반영한 뒤 원래 위치로 돌려놓는다.
   같은 디렉터리에 manifest 백업을 두면 kubelet이 추가 Pod로 읽을 수 있다.
4. 해당 control의 TLS/API Ready와 etcd quorum, serial 변경·CA 보존,
   native Node/workload/PV 결과를 확인한 뒤 다음 control로 진행한다. 실패하면
   다음 control을 변경하지 않고 검증한 해당 control 백업으로 복구한다.
5. Running CloudManaged에서 Mold 구성 다운로드를 실행한다. UI는
   `getKubernetesClusterConfig id=<UUID> refresh=true`로 현재 control 구성을
   읽어 cache를 갱신한다. SSH 실패·작업 중 상태에서는 갱신 실패를 표시하고
   기존 cache를 덮어쓰지 않는다. 새 파일의 client 인증서와 API 접근을 확인한다.
   Stopped/ExternalManaged는 기존 cache 다운로드 계약을 사용하며 refresh를
   지원하지 않는다. 갱신된 인증서 사용에는 새 kubeconfig 다운로드가 필요하다.

실제 검증: 31번 별도 HA r17, Kubernetes 1.37.1 **DEV**, control3+worker2에서
한 control의 실제 TLS 만료 거부, 세 control rolling 갱신, CA/Node UID 보존 및
5 Node Ready 복구를 확인했다. 전역 시간을 변경하지 않았다. 이 결과를 모든
Kubernetes/외부 CA 조합의 검증으로 확대하지 않는다.

## etcd snapshot 복구

1. 실제 실행 etcd 버전과 모든 멤버의 name·peer URL·TLS 경로·data-dir를 수집한다.
   같은 버전의 `etcdctl snapshot save`와 `etcdutl snapshot status`로 snapshot을
   생성·검증하고 checksum을 private에 보관한다. etcd 이미지에 cat/tar가 없으면
   Kubernetes copy 명령 성공을 가정하지 말고 검증한 VM/컨테이너 경로로 복사한다.
2. 복구 창에서 모든 control의 static API/etcd/스케줄러/컨트롤러를 중지한다.
   원래 manifest와 data-dir를 보존하고 동일 snapshot을 각 멤버의 새 data-dir에
   `etcdutl snapshot restore`한다. 실제 멤버별 name/peer URL과 동일한
   initial-cluster 목록, 새로운 cluster token을 사용한다. Kubernetes watch의
   오래된 cache를 무효화하도록 해당 etcd 버전이 지원하는 revision bump와
   `--mark-compacted`를 사용한다. r17은 etcd 3.7.0에서 +1,000,000,000을 검증했다.
3. 모든 멤버가 복원된 뒤 원래 manifest/경로로 시작한다. 3 endpoint health,
   새 cluster generation, 전체 store revision 증가, API/Node/workload를 확인한다.
   전체 store revision 증가와 개별 key의 기존 modification revision 보존은
   구분한다. 백업 후 변경한 시험 객체가 백업 값·UID로 돌아오는지 확인한다.
4. PV/DB 데이터를 별도로 검사한다. etcd 복원은 외부 PV 데이터 복원을 대신하지
   않는다. 실패하면 모든 복구 멤버를 다시 중지하고 같은 세대의 원래 data-dir와
   manifest로 복구한다. 한 멤버만 원래 세대로 되돌리지 않는다.

r17의 실제 restore 후 3 endpoint health, API/5 Node Ready, 원래 ConfigMap 값·UID,
앱 65/65 검사·HTTP100 오류0·DB100records·PV64files(4MiB) checksum을 확인했다.
전체 control 중지 복구 창의 의도된 중단을 HA 무중단 결과로 표시하지 않는다.
장기 시험용 클러스터를 복구 시험 대상으로 재사용하지 않는다.

공식 절차: [kubeadm 인증서 관리](https://kubernetes.io/docs/tasks/administer-cluster/kubeadm/kubeadm-certs/),
[etcd 3.7 recovery](https://etcd.io/docs/v3.7/op-guide/recovery/).
