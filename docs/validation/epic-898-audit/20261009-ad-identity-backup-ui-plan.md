# 신원 포함 구성 백업 UI 의 후속 기능 계획

AD 가입 UI 의 고정 소스 7 개 운영 빌드와 별개 단계다. 부모 승인 후 후속 UI 소스 6 개를 구현했다. 신규 15 개 포함 총 141 개 테스트 / 10 개 suite 와 no-fix lint 가 완료 상태로 통과했으며 고정 후보의 운영 빌드도 통과했다. 실제 백업 및 서비스 중단은 수행하지 않았다. 최종 UI 표준화 #1275 는 범위 밖이다.

## 현재 기능과 API 계약

현재 `StorageServiceConfiguration.vue` 의 백업 대화상자는 runtime 포함 여부와 보존 시간만 받는다. `createBackup` 은 `createStorageServiceConfigBackup` 에 `includeruntime` 및 `retentionhours` 를 전달한다.

backend owner 와 합의한 추가 계약은 다음과 같다.

- `includeadidentity` 기본값은 false 이다. 일반 false 백업의 공개 desired / runtime 동작은 유지한다.
- true 는 사용자 입력의 `maintenancewindow=true` 와 정확한 StorageService instance.name `confirmation` 을 요구한다.
- 승인된 SERVICE AFTERSTOP 단계에서 전체 LOCAL passdb, 참조 사용자·그룹, AD keytab 및 machine identity 를 암호화 vault 참조로 보존한다.
- ZIP 에는 공개 descriptor 만 포함한다. private key, 원본 신원 및 비밀번호 평문은 포함하지 않는다.
- `includeLocalUsers` 라는 별도 API 필드는 추가하지 않는다.
- job 결과 및 descriptor DTO 의 최종 형태는 backend 구현과 검증 후 고정한다.

세 필드의 API annotation 은 backend WIP 에 추가돼 있다. 현재 실제 MGT 의 해당 보호 기능 제공 및 실제 백업 성공을 주장하지 않는다.

## 필요한 작은 UI 변경

1. 기존 백업 대화상자의 form 과 checkbox / input 을 재사용해 신원 포함 선택, 유지보수 중단 승인, 서비스 이름 입력을 추가한다. 초기값과 대화상자 재개방 시 포함 및 승인 값은 false, 확인 문자열은 빈 값이다.
2. 현재 컴포넌트는 instanceId 와 SharedFS resource 만 받는다. 정확한 승인 대상 이름은 SharedFSTab 의 `storageService.instance.name` 을 별도 prop 으로 전달한다. SharedFS resource.name 을 대신 쓰거나 이름을 자동으로 확인 입력에 대입하지 않는다.
3. 신원 포함을 선택한 경우 API 의 세 필드 지원, 명시적 checkbox, exact service name 및 현재 instance scope 를 요청 전에 확인한다. 누락 또는 이름 불일치이면 API 효과가 없다.
4. 미선택 백업에서는 새 승인 필드나 내부 identity 참조를 보내지 않는다. 기존 runtime / retention 요청을 보존한다.
5. 백업의 async 결과 및 공개 descriptor 로 암호화 신원 포함 여부를 표시한다. 원본 capsule, vault 내용, private key 또는 raw credential 은 UI 에서 읽거나 출력하지 않는다.
6. 실패 또는 scope 변경 시 성공으로 표시하지 않고 기존 artifact 와 원본 데이터를 보존한다. 자동 재시도나 native 정리 우회는 추가하지 않는다.

## 의미 있는 테스트와 실행 경계

- default false 요청은 기존 필드와 동일하며 유지보수 값이 없어도 정상 경로를 유지한다.
- 포함 true 에서 승인 미체크, 잘못된 이름, API 필드 누락 및 instance 변경은 요청 전에 거절한다.
- 승인 후 요청에 정확한 instance ID 와 사용자가 입력한 service name 이 포함된다. 이름 자동 확인이나 내부 artifact 참조 전달은 없다.
- 대화상자 재개방과 scope 변경 시 이전 승인 값을 재사용하지 않는다.
- job 실패 / unknown 결과와 malformed descriptor 는 포함 성공으로 승격하지 않는다.
- actual UI 검증은 backend / guest 보호 producer 배포 후 기존 component 의 기능 접점을 검증한다. style, theme, button 정렬 또는 최종 11 개 탭 QA 는 수행하지 않는다.

기존 AD UI 운영 빌드에 이 후속 소스를 섞지 않는다. parent pin 후 별도 소스 단위와 고정 후보로 검증한다.

## 구현 및 검증 결과

정확한 StorageService instance.name 을 별도 prop 으로 전달한다. 기본 요청은 기존 runtime / retention 필드만 유지한다. 신원 포함 요청은 지원되는 API schema, 사용자 승인 및 exact name 을 요구한다. 입력 검증 실패로 포함 의도를 false 로 낮추지 않으므로 다음 클릭이 일반 백업으로 조용히 바뀌지 않는다. 승인 값은 실패 시 지우고, 실제 제출 완료·재개방·instance 변경에서는 포함 여부도 초기화한다.

공개 metadata 계약은 `adIdentityCoverage=VERIFIED_ENCRYPTED_FULL_IDENTITY` 와 `adIdentitySourceDescriptor` 다. descriptor 의 exact 8 개 필드는 다음과 같다.

- `schemaVersion`: JSON 숫자 `1`
- `kind`: `STORAGE_AD_SEMANTIC_SOURCE`
- `ownerArtifactUuid`, `sourceInstanceUuid`, `sourceOperationUuid`: 정규 소문자 UUID 문자열
- `sourceConfigurationSha256`, `ciphertextSha256`, `issuerMac`: 소문자 hex 64 문자열

UI 는 서버 coverage 와 이 공개 형식만 확인해 보존 상태를 표시한다. issuer 인증을 직접 검증했다고 주장하지 않는다. 누락, malformed, 문자열 schemaVersion 또는 추가 private 필드는 미검증으로 표시하며 descriptor 전체를 출력하지 않는다. checkbox 요청값으로 포함 완료를 추론하지 않는다. archive SHA 는 descriptor 안에 넣지 않고 서버 원본 row 및 업로드 ZIP digest 검증의 별도 영역이다.

백엔드 producer / consumer 구현과 실제 배포는 아직 진행 중이다. 현재 실제 MGT 의 새 API 필드 부재에서는 옵트인을 차단한다. 이 UI 소스 결과를 실제 암호화 신원 보존, AD 또는 Cloud all4 완료로 승격하지 않는다.

- 소스 검증: `/root/work/epic898-preparation/ui-identity-backup-source-validation.json`
- 테스트 완료 로그 SHA: `0656712e54f06064ec369a7b125107abc8a559ee20f2306138cf7a0806fff5d1`
- 운영 빌드 후보: `/root/work/epic898-preparation/ui-identity-backup-production-candidate`
- 운영 빌드 로그: `/root/work/epic898-preparation/ui-identity-backup-production-build.log`

| 소스 | SHA-256 |
| --- | --- |
| `ui/src/views/storage/StorageServiceConfiguration.vue` | `479d015453bc70426c4f0319131fa3271e4d84d759701fddfb5711f8687ee1c4` |
| `ui/src/views/storage/SharedFSTab.vue` | `f88d62c893cb38aee58548a80006194191ccb9f4e96fb9ed4b724445a6b6bad7` |
| `ui/public/locales/en.json` | `55330b445c8f78c47dfcb41073a5085ac9a723fdd2f70918a924df320ebccf68` |
| `ui/public/locales/ko_KR.json` | `2a716ff6ef064535e7a8b2728f0b9e4a5aa41600b3e7514e1889a10796df1962` |
| `ui/tests/unit/views/storage/StorageIdentityBackup.spec.js` | `99b0d198b59c04d7f42a0dda93c579158357b8969d99ed814de5b89217007354` |
| `ui/tests/unit/views/storage/StorageServiceConfiguration.spec.js` | `ac632cd454c464867cdad9a94a32ce54e150960b639f9e41f90c2aea75d413a6` |

후보는 커밋된 UI `61d1ec83eea8d6f890fbdf872dfd93425ddddecb` 에 위 6 개 파일만 덧씌웠다. Node 14.21.3 / heap 12 GiB 를 사용하며 Java / native WIP 는 포함하지 않는다. 두 컴포넌트의 style block 은 byte 단위로 동일하고 최종 UI #1275 는 미착수다.

## 운영 빌드 완료

2026-10-08T21:57:21Z 에 운영 빌드와 설정 생성이 종료 코드 `0` 으로 통과했다. 산출물은 850 개이며 candidate 및 canonical 소스 6 개의 해시는 같다.

- 산출물: `/root/work/epic898-preparation/ui-identity-backup-production-candidate/ui/dist`
- `index.html` SHA: `a8aad0f174e90d12f3fff98d701b9eede6892b54831e5338b0a570cceefa1be8`
- `config.json` SHA: `44d080ee12cd291185d65c81a605e71e455dada1a295b46d51e7de86c877b860`
- 빌드 로그 SHA: `8cff42ea08a510fa5880d384a84221a2e7cbc522311a8db79639f0f53c4d9fd9`
- manifest: `/root/work/epic898-preparation/ui-identity-backup-production-dist-manifest.json`
- manifest SHA: `a0a6172183c1861b147a79a887b58f41eb4ceeb5eadce2edf57c274fddc87eed`

부모는 소스 6 개를 `4e2aafc2e02083e0989888b4deda45610906a3f9` 로 pin 했다. 실제 신원 포함 백업 / AD 효과는 아직 수행하지 않았으며 실제 MGT 의 기존 schema 에서는 옵트인 지원을 주장하지 않는다.
