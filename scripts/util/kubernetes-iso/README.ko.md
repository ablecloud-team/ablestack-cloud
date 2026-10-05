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

# Europa Kubernetes ISO

이 workflow는 Cloud 전체 Maven 빌드와 독립적으로 ISO를 생성합니다. 검증 profile은 `mold-cks` / `amd64`입니다. 아래 여섯 patch를 별도의 고정 recipe로 빌드합니다. arm64는 별도 검증 대상입니다.

| Kubernetes | AutoScaler baseline | Calico | 구분 |
| --- | --- | --- | --- |
| 1.34.2 / 1.34.9 / 1.34.12 | 1.34.5 | 3.32.2 | Origin 검증 후보 |
| 1.35.9 | 1.35.2 | 3.33.0 | Origin 검증 후보 |
| 1.36.5 | 1.36.1 | 3.33.0 | Origin 검증 후보 |
| 1.37.1 | 고정 1.37 개발 commit | 3.33.0 | 개발 후보, 정식 AutoScaler 릴리즈 전 공식 ISO 승격 금지 |

AutoScaler는 minor별 원본 인터페이스를 유지하면서 내부 SHA256 client를 적용합니다. 원본 SHA, 커스터마이징 SHA 및 파일 해시를 각각 기록합니다. `1.37`용 stable 릴리즈가 없으므로 개발 후보를 stable로 표시하지 않습니다.

## 생성 및 독립 검증

Linux ext4 작업 디렉터리에 curl, skopeo, xorriso, Python 3/PyYAML, Go 1.26.8을 준비합니다. 설치된 containerd 서비스는 사용하거나 변경하지 않습니다. containerd 1.7의 공통 런타임 디렉터리(`/run/containerd`, 0711)가 필요하며, 그 아래 daemon 상태는 공유하지 않습니다.

```bash
scripts/util/kubernetes-iso/setup-tools.sh "$HOME/work/mold-iso-tools"
sudo install -d -m 0711 /run/containerd
export PATH="$HOME/work/mold-iso-tools:$HOME/work/mold-iso-tools/bin:$PATH"
python3 -m unittest discover -s scripts/util/kubernetes-iso/tests -v
scripts/util/create-kubernetes-binaries-iso.sh \
  --recipe scripts/util/kubernetes-iso/recipes/kubernetes-1.34.2-amd64.json \
  --output "$HOME/work/iso-output/1.34.2" --revision r1
```

소스는 커밋한 상태여야 합니다. 로컬 개발용 `--development` 산출물은 Release 대상으로 사용할 수 없습니다. positional 인자 방식은 변동 가능한 다운로드와 호스트 containerd 변경을 유발했으므로 고정 recipe 방식으로 대체했습니다.

recipe에는 공식 다운로드 URL/SHA256, 매니페스트의 source commit, 실제 amd64 이미지 digest, 내부 Provider/AutoScaler/SDK의 source SHA와 Actions 출처가 들어갑니다. 빌드 중 `latest`/`main`/`master`를 조회하지 않습니다. Headlamp 원본 매니페스트의 이미지도 해당 버전의 실제 digest로 치환합니다. core image 목록은 해당 patch의 kubeadm 출력과 대조합니다.

ISO를 CDROM label로 생성한 뒤 staging 디렉터리를 버리고 별도 검증기가 ISO를 추출합니다. 모든 파일 해시, Kubernetes 실행 버전 및 공식 keyless 바이너리 서명, CNI/crictl/etcd ELF, Kubernetes YAML, OCI manifest/config/compressed-layer와 uncompressed-layer digest, 내부 바이너리의 Go source/SDK 정보를 확인합니다. 격리 containerd daemon에 전체 archive를 import하여 kubeadm tag와 매니페스트 digest 참조가 모두 유지되는지 검사합니다.

ISO 파일과 manifest, CycloneDX 구성 목록, 독립 검증 보고서, checksum, Mold 등록 JSON을 함께 출력합니다. 구성 목록은 배포 payload 목록이며 모든 컨테이너의 OS/전이 패키지를 분석한 취약점 보고서는 아닙니다.

## Origin 및 공식 Release

Origin의 작업 브랜치 push는 여섯 버전 ISO를 빌드·검증하고 공개 trial Release를 생성합니다. 이 tag는 `k8s-v<version>-mold-cks-amd64-r<revision>-<source8>` 네임스페이스를 사용합니다. 기존 tag/파일을 덮어쓰지 않으며 `latest` 및 `branch-dev`를 변경하지 않습니다. Release 후 인증 없는 GET으로 ISO를 다시 받아 SHA256을 대조합니다.

Mold 등록의 checksum은 파일 SHA256의 hex에 `{SHA-256}` prefix를 붙입니다. manifest의 API `HMAC-SHA256`은 API 요청 인증 규칙이며 ISO 파일 checksum과 다른 항목입니다.

공식 Release는 Europa에 포함된 commit의 전용 tag에서만 실행합니다. Origin 후보 컴포넌트를 공식 Release로 재명명하지 않습니다. SDK·Provider·AutoScaler Upstream PR을 병합·릴리즈한 뒤 동일한 검증 절차로 공식 source/image digest를 recipe에 반영해야 합니다. Provider/AutoScaler·노드 생명주기 실환경 검증을 완료하고 recipe의 `runtime_qualification`에 PASS와 증거 URL을 기록해야 공식 게시가 가능합니다. 이번 PR은 이 의존성을 가진 검증 후보입니다.

## 소비자 계약과 검증 범위

root payload의 `provider.yaml`, `autoscaler.yaml`은 필수입니다. Provider는 내부 SDK의 SHA256 인증을 사용하며 AutoScaler는 자체 SHA256 client를 유지합니다. ISO의 `docker/images.list`를 확인하고 import에 `--digests --base-name <repository>`를 사용하여 실제 digest 주소가 보존되도록 합니다. 설치/확장의 내부 매니페스트를 배포하고 업그레이드는 Headlamp와 legacy Dashboard의 파일을 명시적으로 구분합니다. Secret 적용은 기존 Secret 갱신으로 APIKeyPair rotation을 지원합니다.

CSI는 별도 내부 SHA256 인증 검증 전이므로 이 profile에서 제외합니다. AutoScaler 활성화는 선택이며 zero-size/autoprovisioning을 지원한다고 표시하지 않습니다. 실제 LB/VPC, 각 minor의 클러스터 생성/확장/축소/업그레이드 및 arm64, Headlamp 연결 UI(#1208)는 각 생명주기 이슈의 런타임 검증 대상입니다. ISO PASS를 전체 클러스터 생명주기 PASS로 해석하지 않습니다.

CNI 조합은 [Calico 3.32 요구사항](https://docs.tigera.io/calico/3.32/getting-started/kubernetes/requirements)과 [3.33 요구사항](https://docs.tigera.io/calico/latest/getting-started/kubernetes/requirements)의 시험 minor를 기준으로 선택합니다. 노드 커널 5.10 이상과 CNI 모듈/네트워크 준비는 클러스터 배포 전 확인해야 합니다.
