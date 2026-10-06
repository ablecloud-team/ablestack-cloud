<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for additional information.
The ASF licenses this file under the Apache License, Version 2.0.
You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software is
provided on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
-->
# Kubernetes 변경 모듈 부분 배포 정합성 검사

전체 Cloud 릴리즈 빌드는 GitHub Actions에서 별도로 확인한다. 개발 중 변경 모듈만 빌드·배포할 때는 같은 source commit의 JAR, 외부 CKS 템플릿 및 필요한 UI 자산을 함께 확인한다.

Cloud는 노드 user-data를 JAR 안의 conf가 아닌 `/usr/share/cloudstack-management/cks/conf`에서 읽는다. JAR만 교체하면 새 CSI profile/Provider ownership marker 복사 구문이 VM에 전달되지 않을 수 있다. 기존 VM의 user-data는 템플릿 갱신으로 변경되지 않는다.

WSL ext4 변경 Kubernetes 모듈 JAR을 후보로 준비한 뒤 아래 검사를 관리 서버의 활성 conf에 실행한다. 저장소 전체 또는 Maven 빌드를 `/mnt/c`에서 실행하지 않는다.

```bash
python3 preflight-cks-config.py --candidate-jar cloud-plugin-kubernetes-service.jar \
  --installed-conf /usr/share/cloudstack-management/cks/conf
```

검사는 control/control-add/worker/etcd 4개 템플릿의 정확한 SHA256을 비교한다. exit 0은 모두 일치, exit 1은 누락/차이, exit 2는 잘못된 후보·읽기 오류다. 파일을 변경하지 않는다. 사용자 지정 템플릿의 차이를 조용히 덮어쓰지 않으며, 변경 내용을 확인하고 기존 파일과 권한을 백업한 뒤 필요한 파일만 갱신한다. JAR과 conf의 source revision 및 배포 후 해시를 증거에 기록한다.

배포 후 같은 검사를 다시 통과시킨다. `WEB-INF`, `config.json`, `/client/` HTTP 200, 서비스 상태와 기존 시험 VM UUID/Running 상태를 확인한다. 새 노드에서는 ISO 검증, `/opt/csi/profile.json`, `/opt/mold-provider-ownership-v1`, CSI rollout 및 실제 Pod image digest를 확인한다. CSI profile이 없는 기본 ISO는 CSI 성공으로 표시하지 않는다.

rollback은 백업한 JAR과 해당 conf 파일을 함께 복원하고 검사 및 UI/서비스 확인을 반복한다. additive DB schema 변경은 별도의 rollback 검토 없이 제거하지 않는다. 보정 후 재시도와 새 clean 배포 결과는 각각 기록한다.
