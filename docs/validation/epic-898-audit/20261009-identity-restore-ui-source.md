# AD 신원 복원 UI 의 조건부 유지보수 승인

기존 복원 UI 는 confirmation / planToken / credentials 만 보내고 새 `adIdentityRestoreRequiresMaintenance` 를 처리하지 않았다. 부모 승인으로 이 기능 갭을 보완했다. source authorization JSON 이나 private key 입력은 추가하지 않으며 원본 권한·MAC·issuer 검증은 서버가 담당한다.

AD 신원 계획은 literal `true` flag 와 유효한 공개 descriptor exact 8 필드, archive SHA 를 요구한다. 해당 계획에서만 기본 false 인 중단 승인 checkbox 를 표시한다. 사용자 exact targetName 과 검토한 instance / artifact / plan / token fingerprint 를 POST 직전에 대조하며 계획·token 변경은 승인 초기화와 거절로 처리한다. 일반 계획은 새 maintenance 필드 없이 기존 복원 요청을 유지한다.

미승인이나 지원되지 않는 schema 는 계획을 plain restore 로 낮추지 않고 요청을 거절한다. 권한·이름·token·SHA·descriptor·scope 변경과 실패 / unknown 결과를 검증한다. 사용한 request credentials 참조는 finally 에서 해제하고 원문 transport 오류를 화면 로그로 전달하지 않는다. 전체 browser heap 의 물리적 zeroize 를 주장하지 않는다.

API APPLY 는 maintenancewindow 를 지원하지만 읽기 당시 LKG command 와 adapter 의 getter 전달이 누락돼 있었다. backend owner 에게 후속을 전달했다. 현재 UI 는 LKG 에 해당 schema 가 없으면 fail closed 이며 실제 LKG / AD 복원 효과를 주장하지 않는다.

신규 11 개 포함 161 개 테스트 / 11 개 suite 와 변경 범위 no-fix lint 가 완료 상태로 통과했다. style block 은 byte 단위로 동일하고 최종 #1275 style / theme / button 정렬 / keyboard QA 는 미착수다. 실제 복원 및 AD 효과는 0 이며 source pin / backend / guest / 실제 UI 검증과 구분한다.

- 검증: `/root/work/epic898-preparation/ui-identity-restore-source-validation.json`
- 테스트 완료 로그 SHA: `5b8964133aa8d1ccfc7105d8accedc6d756dd65532db4f5e1b973547e9e204df`
- 운영 빌드 후보: `/root/work/epic898-preparation/ui-identity-restore-production-candidate`
- 운영 빌드 로그: `/root/work/epic898-preparation/ui-identity-restore-production-build.log`

| 소스 | SHA-256 |
| --- | --- |
| `ui/src/views/storage/StorageServiceConfiguration.vue` | `bcbf71a74510ea0151cb325114a0489b6d595669a13856efeb5f0fef04954b40` |
| `ui/public/locales/en.json` | `053003622123669395d3fb42ee86a5d867f62a972658a770204bbe95d600276d` |
| `ui/public/locales/ko_KR.json` | `3ca0bcf1db3c5e646e7cbd1302809f768ded06b26c0238e8394ae3839dde3771` |
| `ui/tests/unit/views/storage/StorageIdentityRestore.spec.js` | `f17ca76552ad4b79ffbef4c01c5e505d9918d51cf9c5b4a07a39d259db03ca8c` |
| `ui/tests/unit/views/storage/StorageIdentityBackup.spec.js` | `75fa8e68b1be403a287645edbda776f737b9b1ccdd1fe0740909bdf9621fc028` |

후보는 커밋된 UI `f1112b92bd08de9d2628cd6f0bb0a63d484df2cb` 에 위 소스 5 개만 덧씌웠다. Node 14.21.3 / heap 12 GiB 를 사용하고 Java / native WIP 를 포함하지 않는다. 운영 빌드도 통과했고 아래 실제 산출물 수와 해시를 기록한다.

## 운영 빌드 완료

2026-10-08T22:38:04Z 에 운영 빌드와 설정 생성이 모두 종료 코드 `0` 으로 통과했다. 산출물은 850 개이며 candidate 및 canonical 소스 5 개의 해시는 같다.

- 산출물: `/root/work/epic898-preparation/ui-identity-restore-production-candidate/ui/dist`
- `index.html` SHA: `fcc41c80f379f5b7310b35a3ff99470ebfa4973f98e5c432560cf81d44921c18`
- `config.json` SHA: `44d080ee12cd291185d65c81a605e71e455dada1a295b46d51e7de86c877b860`
- 빌드 로그 SHA: `4b4d15a3356a0718fc16e7f36da649f405d8d8a3bc981ba174668c3a42281165`
- manifest: `/root/work/epic898-preparation/ui-identity-restore-production-dist-manifest.json`
- manifest SHA: `a46fc583721ebad0b8cc89a4ae2c2760cb5defb684fbb3304bde6e7cdf6cb2a3`

현재 실제 복원 / AD 효과는 0 이다. 새로운 backend / native 및 유효한 승인 계획이 준비된 뒤 API 와 Cua 기능 인수를 진행한다. 전체 SharedFS 또는 최종 UI #1275 완료로 보고하지 않는다.
