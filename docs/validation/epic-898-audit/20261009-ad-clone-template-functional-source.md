# AD CREATE_NEW 복원의 공개 SYSTEM 템플릿 선택 기능

현재 이 단위는 소스 검증을 마쳤으며 독립 운영 빌드도 정상 종료했다. 신규 15 개를 포함한 188 개 테스트 / 13 개 suite 와 no-fix lint 가 정상 종료했다. 실제 UI 배포와 Cua 확인은 부모가 수행한다. 실제 clone / VM / ROOT / DATA 생성은 모두 0 이다.

AD 신원을 인증한 backup / import 는 공개 metadata 의 `adIdentityCoverage=VERIFIED_ENCRYPTED_FULL_IDENTITY` 와 exact 8 필드 `adIdentitySourceDescriptor` 를 함께 사용한다. 이 단위는 `adIdentityAuthority` 라는 별도 필드를 만들지 않는다. 포함 의도가 있는데 coverage 또는 descriptor 가 잘못되면 일반 clone 으로 낮추지 않고 요청을 막는다. backend 는 실제 issuer / owner / expiry / archive SHA / protected key / MAC 인증 뒤에만 IMPORT 에도 두 공개 key 를 생산한다. 그 producer 보완은 별도 backend 단위의 정상 817 tests / 124 classes 검증을 통과했으나 982d5ba0cc48 로 PIN 됐지만 실제 import 검증을 주장하지 않는다. alias registry 를 포함한 seed 부재 9 fixed paths / 8 tests 의 b1c91c0b4910 source 증빙은 새 image build 또는 실제 fresh SID 증명이 아니다.

CREATE_NEW 에서만 기존 select 컴포넌트로 공개 SYSTEM 템플릿을 직접 선택한다. 정상 `listTemplates` 를 owner / project / zone 문맥과 15 초 timeout 으로 조회한다. 일반 사용자는 executable 범위, 관리자는 정상 all / listall 범위를 사용하되 결과의 public SYSTEM / Ready / scalable / KVM / x86_64 / 동일 zone / canonical UUID 및 details 의 literal `storage.service.local.identity.seed.absent='true'` 를 모두 확인한다. 적합한 후보를 자동 선택하지 않는다. private USER, 내부 validation artifact 및 source authorization JSON / key 입력은 UI 에 없다.

prepare 직전에 선택과 source / zone / owner scope, 조회 성공 및 seed metadata 를 다시 대조한다. 요청에는 `mapping.createNew.templateid` UUID 만 추가하며 일반 non-AD clone 은 이전처럼 이 필드를 생략한다. 서버의 protected source authority, 정상 template validator 및 seed metadata 재검증은 최종 책임으로 유지한다. UI 목록 metadata 만으로 실제 SAM seed 부재, 서명 또는 production AD 지원을 판정하지 않는다.

서버가 반환한 `plan.createNew.templateid` 는 sealed planToken / plan SHA 에 포함된다. source / owner / zone 변경, 선택 변경, failed / old / stale 응답에서는 이전 선택·승인·token 을 무효화한다. 준비 중 source / mode / 선택이 바뀌어도 이전 응답을 review 로 승격하지 않는다. apply 직전에도 해당 봉인된 ID 와 선택 및 metadata 를 대조한다. AD descriptor 가 계획에서 처음 발견돼 필요한 선택이 없으면 MAPPING 단계에 남긴다. 실제 적용에는 기존 planToken 만 전달하며 템플릿이나 source authority 를 따로 덮어쓰지 않는다.

CSS / style block / footer 는 동일하며 기존 HTML 에 조건부 템플릿 입력 한 곳만 추가했다. 공통 scale, 기존 RESTORE_EXISTING 및 일반 non-AD clone 기본 SYSTEM 선택은 바꾸지 않았다. #1275 최종 style / theme / 버튼 / 대화상자 / 키보드 QA 를 시작하지 않았다.

- 소스 검증: `/root/work/epic898-preparation/ui-clone-identity-template-source-validation.json`
- 테스트 로그: `/root/work/epic898-preparation/ui-clone-identity-template-final-tests.log`
- lint 로그: `/root/work/epic898-preparation/ui-clone-identity-template-final-lint.log`
- 운영 후보: `/root/work/epic898-preparation/ui-clone-identity-template-production-candidate`
- 운영 빌드 로그: `/root/work/epic898-preparation/ui-clone-identity-template-production-build.log`

| 소스 | SHA-256 |
| --- | --- |
| `ui/src/views/storage/StorageServiceConfiguration.vue` | `d8f16053ebc5581099cb24fbcd2ecc907e605ebf5475342b924fd2e1ae1588b6` |
| `ui/public/locales/en.json` | `0f7029a342ee5f1f2acfac6286b7182b757077a3d008d46198174f9910744f11` |
| `ui/public/locales/ko_KR.json` | `da8ed766aff78c4048673ceeba074d8e3c3212ff42cc106f3122cb428529aecf` |
| `ui/tests/unit/views/storage/StorageCloneIdentityTemplate.spec.js` | `1d117ba3604a56fe217a79c92287d57b4b6a1a237ddd7224cfb569ae2a20f0ff` |

운영 후보는 committed UI `3cc0898ea7a2a1e00d231309d6b112ee0e0e8052` 에 위 source 4 개만 덧씌운 immutable snapshot 이다. Java / native WIP 를 포함하지 않고 Node 14.21.3 / heap 12 GiB 를 사용한다. 신규 15 개 테스트는 strict metadata / 권한·owner / 기본값 생략 / source evidence / missing·foreign 선택 / prepare·apply 봉인 ID 불일치 / stale zone·owner·source / timeout·미지원 / source 및 선택 변경 / dispose 를 검증한다. 전체 회귀는 기존 일반 복원·백업·JOIN·NVMe·ROOT 오퍼링·공통 locale 및 읽기 오류 동작을 포함한다.

실제 positive AD clone 은 새 seed 없는 단일 source image, aligned backend / native, authenticated source archive 및 새 자기 fixture 준비 뒤 수행한다. 기존 historical SYSTEM 템플릿 또는 내부 private fixture 를 UI 생성 성공으로 바꾸어 보고하지 않는다. 기존 7 개 서비스, NEW20 볼륨, partial 021b 및 Client 47 OOBE 는 변경하지 않았다.

운영 빌드는 850 파일로 통과했다. index SHA `2dd5fbeb1cfe3fc788cfa4cb85d9616e9417c51466b9d1f946a25dc1e5849e67`, config SHA `44d080ee12cd291185d65c81a605e71e455dada1a295b46d51e7de86c877b860`, 전체 출력 manifest SHA `ee4fc904578e62f064ee12de2ff0887fa329f0ddfd3fcbd0fba017a126ff3918` 이다. build log SHA 는 `f832eb5f99e8dd02d86a9f970d3e32936b10d6c19ee598ccbd90d78700c9e612` 이며 config 생성도 exit 0 이다. 후보와 canonical source 4 파일의 빌드 전후 해시는 모두 동일하다. 이 결과는 소스와 정규 빌드 증빙이며 실제 배포 / Cua / positive AD clone 은 부모 후속 단계다.
