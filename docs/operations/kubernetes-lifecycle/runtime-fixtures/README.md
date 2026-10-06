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

# Kubernetes 앱 복구 시험 fixture

[생명주기 시험 #1230](https://github.com/ablecloud-team/ablestack-cloud/issues/1230)의
[readiness/종료 처리 개선 #1267](https://github.com/ablecloud-team/ablestack-cloud/issues/1267)을
재현하는 기존 `rt1230-app` API fixture의 보완 코드입니다. 저장소를 클론하거나 포크한 후
동일한 경로를 사용할 수 있습니다. 배포할 Kubernetes context는 사용자가 선택합니다.

기존 시험 앱의 API replica 2개, `code` ConfigMap volume, `APP_TOKEN` Secret 환경변수,
`redis:6379` 서비스와 web proxy가 필요합니다. 별도 namespace에서 시험하고
기존 데이터와 실제 운영 앱에는 적용하지 않습니다. 파일에 인증키나 kubeconfig가 없습니다.

```bash
kubectl -n rt1230-app create configmap app-code-1267 \
  --from-file=api.py=docs/operations/kubernetes-lifecycle/runtime-fixtures/api.py \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n rt1230-app patch deployment api --type=strategic \
  --patch-file=docs/operations/kubernetes-lifecycle/runtime-fixtures/api-readiness-lifecycle.patch.yaml
kubectl -n rt1230-app rollout status deployment/api --timeout=180s
```

- SIGUSR2는 readiness만 토글합니다. 실패 시 `/ready`는 503, `/health`는 200이며
  Pod UID와 restart 수를 유지한 채 활성 EndpointSlice에서 제외되어야 합니다.
  같은 신호를 다시 보내면 readiness와 endpoint가 복구되어야 합니다.
- SIGUSR1은 liveness와 readiness를 함께 실패시킵니다. 활성 endpoint 제외 후
  같은 Pod UID의 container restart 증가와 복구를 확인합니다.
- SIGTERM은 서버의 새 요청 수락을 중지하고 서버 소켓을 정리합니다.
  배포 patch는 짧은 preStop과 readiness failureThreshold 1을 적용합니다.
- 검증 요청은 보호된 `/kv/<key>` GET을 사용합니다. 공개 `/config`의 성공을
  인증 검사로 사용하지 않습니다. HTTP 코드를 body 파싱 전에 기록하고, 오류 본문은
  길이와 SHA256만 보존합니다. 토큰과 응답 원문은 공개하지 않습니다.
- 장애 주입 전후 데이터 검사는 읽기 전용으로 수행합니다. baseline 쓰기를 반복해
  데이터 보존의 증거를 덮어쓰지 않습니다. 주입 구간과 steady 관찰을 별도로 집계합니다.

31번 GFS2의 최신 b46 ISO/1.34.9 독립 클러스터에서 readiness-only의 같은 UID·restart 0,
liveness의 같은 UID·restart 0→1, 각 데이터 65/65 및 해당 150초 보호된 읽기
1,452건 HTTP 200/오류 0을 확인했습니다. 이전 fixture의 150초 1,449건 중 파싱 오류
1건은 별도 실패 기록입니다. 이 결과는 해당 시험 구간에 한하며, 6버전 전체 생명주기나
2시간/24시간 qualification 또는 CSI 검증을 대신하지 않습니다.
