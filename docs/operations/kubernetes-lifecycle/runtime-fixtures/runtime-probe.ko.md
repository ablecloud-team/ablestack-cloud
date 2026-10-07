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

# 31번 Kubernetes 관찰 프로브

[생명주기 시험 #1230](https://github.com/ablecloud-team/ablestack-cloud/issues/1230)과
[관찰 도구 개선 #1277](https://github.com/ablecloud-team/ablestack-cloud/issues/1277)의 시험용 도구입니다. 클론 또는 포크한 Europa 저장소의 WSL ext4 작업 트리에서 Python 3.9 이상으로 실행합니다. 네트워크·방화벽·클러스터 설정은 변경하지 않습니다.

외부 LB의 source CIDR가 `10.10.31.10/32`이면 로컬 WSL에서 직접 HTTP를 보내는 것은 허용된 관찰이 아닙니다. `lb`는 입력 CIDR와 SSH 대상을 확인하고 관리 서버에서 HTTP를 보냅니다. 실제 LB/FW의 Active 상태와 CIDR은 먼저 조회하여 확인해야 합니다. `--allowed-source-cidr`는 프로브 입력을 검증하며 서버 설정을 바꾸지 않습니다.

기존 검증된 SSH argv를 private JSON 배열로 준비합니다. 마지막 요소는 `root@10.10.31.10`이어야 합니다. SSH 키 또는 password-file을 사용하고 JSON과 인증 파일을 chmod 600으로 보호하며 Git에 넣지 않습니다. SSH argv·비밀번호·API 키·kubeconfig를 이슈에 게시하지 않습니다.

```bash
python3 docs/operations/kubernetes-lifecycle/runtime-fixtures/runtime-probe.py lb \
  --ssh-argv /private/mgmt-ssh-argv.json \
  --public-ip 10.10.31.139 --port 18087 --duration 7200 \
  --allowed-source-cidr 10.10.31.10/32 > /private/lb-observation.jsonl
```

프로브는 전경으로 실행되며 Ctrl+C로 중지합니다. `rt-web-` 응답을 기대하는 전용 fixture용입니다. 최종 result까지 출력되고 종료 코드 0이며 errors=0인 창만 해당 관찰 범위의 통과로 판정합니다. 요청 원문·응답 body는 출력하지 않습니다. 잘못된 호출 위치의 진단 창과 새 정상 창을 합산하지 않습니다.

`api`는 지정된 읽기 API만 허용합니다. credentials JSON은 `url`, `apiKey`, `secretKey`를 가진 private 파일이며 목적지는 31번 `/client/api`로 제한됩니다. HMAC SHA256을 사용하고 pagesize만 지정하면 page=1을 추가합니다. HTTP/API 오류는 빈 성공 목록으로 취급하지 않고 종료 코드 1로 반환합니다. stdout에는 상태·개수만 출력하며 전체 응답은 새 chmod600 파일에 저장합니다. 기존 출력 파일을 덮어쓰지 않습니다.

```bash
python3 docs/operations/kubernetes-lifecycle/runtime-fixtures/runtime-probe.py api \
  --credentials /private/admin-api.json --command listKubernetesClusters \
  --param pagesize=100 --private-output /private/clusters-new.json
```

`--validate-only`로 네트워크 요청 없이 입력 게이트를 확인할 수 있습니다. group/other 권한이 있는 인증 입력, 잘못된 관리 대상·source CIDR·IP/port/duration, API credential/command override는 거부합니다. 긴 관찰 중 upgrade/정지/키 회전 등 계획된 작업을 수행하면 그 구간과 정상 상태 관찰을 별도로 기록합니다.
