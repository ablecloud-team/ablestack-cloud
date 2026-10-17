# AD UI 의 SERVICE 승인과 fresh receipt 요청 계약

2026-10-09 소스 읽기에서 생성 및 상세 화면의 AD 요청과 새 백엔드 보호 조건 사이의 기능 갭을 확인했다. 부모는 후속 기능 수정을 승인했다. NVMe 소스와 실제 UI negative 검증의 부모 pin 이후 AD 기능 코드를 구현했다. 최종 104 개 테스트와 no-fix lint 가 통과했고, 운영 빌드도 아래 소스 7 개를 고정한 후보에서 통과했다. 실제 AD 가입, Client OOBE 변경, 클러스터 변경 및 최종 UI 표준화 #1275 는 수행하지 않았다.

## 수정 전 소스의 두 가지 불일치

`CreateSharedFS.createInitialFileServices` 는 AD principal 을 포함한 SMB share 생성 후 domain join 을 호출한다. 최신 `resolveStorageAdPrincipal` 은 JOINED domain 과 보호된 identity receipt 가 있어야 하므로 신규 instance 의 이 순서는 선행 ACL 단계에서 차단된다.

생성 화면의 join 과 상세 `SharedFSTab` 의 join / rejoin 은 `maintenancewindow`, `confirmation`, `identitymode` 를 보내지 않는다. 상세 leave 는 입력한 이름의 확인 여부를 검사하지만 실제 요청에는 confirmation 과 maintenancewindow 를 포함하지 않는다. `runStorageAction` 에서 이 필드를 대신 추가하는 경로도 없다.

새 `JoinStorageServiceToAdDomainCmd` 와 `LeaveStorageServiceFromAdDomainCmd` 는 SERVICE 유지보수 중단 승인과 정확한 instance 이름을 받는다. Manager 의 `requireAdLifecycleApproval` 은 literal 승인 `true` 와 `instance.name` 일치를 확인하고, typed AD 지원 및 SERVICE checkpoint 를 요구한다. `NEW_INSTANCE` 는 관리자 및 보호된 fixture profile 경로이며 공개 UI 에 노출하지 않는다.

## 사용자 기능 요청 순서

1. 기존 form, checkbox, 이름 확인 입력을 사용해 SERVICE 중단을 명시적으로 승인받는다. 승인 값의 기본값은 false 이며 정확한 이름은 사용자가 입력한다. 일반 UI 의 identitymode 는 `JOIN_EXISTING` 으로 고정한다.
2. 생성 화면에서 제안 이름과 승인 입력을 확인하고, 생성 후 실제 반환된 instance 의 이름 및 VM binding 을 다시 대조한다. 이름을 자동으로 대입하거나 사용자의 checkbox 없이 `maintenancewindow=true` 를 보내지 않는다.
3. domain join 을 정상 API 로 실행하고 async 성공 완료를 기다린다. AD principal ACL 이 포함된 share 생성은 이보다 먼저 수행하지 않는다.
4. 정확한 instance 에 대해 fresh domain status 를 최대 60 초로 제한해 조회한다. JOINED 및 OK 상태와 typed receipt 의 exact instance / domain, literal 검증 flag, observation scope 및 신선도를 확인한다.
5. 검증 후 새 SMB share / AD principal ACL 을 생성한다. join 또는 receipt 조회가 실패하면 AD ACL 을 만들지 않고 부분 설정 실패 상태와 기존 자원을 보존한다. 자동 join 재시도나 서비스 정리 우회는 하지 않는다.
6. 상세 join / rejoin / leave 도 같은 사용자 승인과 exact instance 이름을 전달한다. leave 에는 실제 confirmation 필드를 포함하며 기존 AD principal 참조 제거 조건을 우회하지 않는다.
7. 성공 및 실패 모두 가입 자격 증명을 입력과 실행 snapshot 에서 해제한다. raw secret, keytab, NT hash 또는 자격 증명 파생값을 화면 로그와 증빙에 쓰지 않는다.

## backend owner 와 합의한 fresh 조회

현재 `listStorageServiceDomainStatus` 는 DB 관측만 반환하므로 기존 `config.identityReceipt` 를 fresh guest 증거로 사용할 수 없다. backend owner 는 다음 정상 읽기 전용 계약을 보강한다.

- 요청: `listStorageServiceDomainStatus({ instanceid, fresh: true })`; 단일 서비스 범위만 허용한다.
- 새 observation UUID 와 settled native revision 으로 정상 `identity domain inspect` 를 수행한다.
- domain SID, 이름, SPN, DNS, idmap binding 및 fresh boot / 60 초 신선도를 Java 에서 검증한다.
- DAO update 와 native 효과 없이 별도 typed `identityreceipt` 필드를 반환한다.
- inspect 30 초와 status RPC 2 회가 있어 UI 읽기 deadline 은 최대 60 초를 사용한다. NVMe 의 15 초 읽기와 별개다.
- `generatedEpoch` 는 현재 시각 기준 과거 60 초부터 미래 5 초까지 허용하고, 이전 generation / boot binding 은 거절한다.
- UI 는 join async 성공 이후 exact instance 의 JOINED / OK 및 새 typed receipt 를 확인한다.
- receipt 의 operation UUID 는 읽기 observation UUID 이다. join command 또는 async job UUID 와 같다고 요구하지 않는다.
- 오래된 `config.identityReceipt` fallback 은 사용하지 않는다. API 필드가 없거나 fresh 증거가 실패하면 미확인으로 처리한다.

fresh API / DTO 는 backend 정상 모듈 792 개 테스트 및 123 개 클래스 검증 후 `1849d3496d0c6cb22f9edc3a707a61967cfc4b3f` 로 pin 됐다. 소스 증거이며 실제 MGT 는 아직 ADE707 구성이라 fresh API 가 제공되지 않는다. 실제 배포 또는 AD 가입 성공을 주장하지 않는다. UI 는 자체 scope 를 발급하거나 내부 보호 artifact 참조를 전달하지 않는다.

## 필요한 의미 있는 검증

- 승인 미체크, 이름 불일치, actual instance / VM 불일치에서 요청 효과가 없다.
- 생성 순서가 join 완료 → fresh receipt → AD share / ACL 이며 join 전에 AD ACL 을 생성하지 않는다.
- join 실패, missing / malformed / stale / foreign receipt 및 문자열 boolean 이 후속 ACL 을 차단한다.
- 오래된 config receipt 는 새 typed receipt 를 대신하지 않는다.
- 읽기 observation UUID 를 join job UUID 와 잘못 대조하지 않는다.
- 상세 join / rejoin / leave 요청에 사용자 입력 승인과 exact confirmation 이 포함된다.
- failure / timeout 경로에서 비밀 입력을 해제하고 자동 retry / delete / 서비스 변경을 추가하지 않는다.
- 실제 UI 기능 검증은 최종 native producer 와 정상 API 배포 후 수행한다. 소스 및 unit 통과를 실제 AD 가입 성공으로 승격하지 않는다.

## UI 구현과 고정 증거

생성 화면은 가입 async 성공과 정확 instance 의 fresh typed receipt 를 확인한 뒤 AD principal 을 포함한 SMB share 를 생성한다. 상세 join / rejoin / leave 는 사용자 입력의 유지보수 승인과 서비스 이름을 전달한다. 기존 API 에 새 필드가 없으면 요청 전에 미지원으로 처리한다. 비밀이 포함된 실제 요청 object 참조도 finally 에서 해제하고, 원문 transport 오류를 로그나 안내로 전달하지 않는다.

신규 23 개와 기존 생성, NVMe, selector, locale, 상세, read transport 회귀를 합친 104 개 테스트 / 8 개 suite 가 완료 상태로 통과했다. 소스 검증 전후 해시는 같다. 두 컴포넌트의 style block 은 byte 단위로 동일하며 최종 UI 표준화는 미착수다. 기존 NVMe 테스트 변경은 새 cleanup helper 를 실제 Vue context 와 맞추는 fixture 보완만 포함한다. 기본 템플릿의 NVMe 요약은 미지원으로 단정하지 않고 미확인으로 표시한다.

- 소스 검증: `/root/work/epic898-preparation/ui-ad-maintenance-source-validation.json`
- 테스트 완료 로그 SHA: `0b7ceab33ba4dabbc00b1b0c371234e01c572f685b3f7cd33e8553f4bee2a0bf`
- 운영 빌드 후보: `/root/work/epic898-preparation/ui-ad-maintenance-production-candidate`
- 운영 빌드 로그: `/root/work/epic898-preparation/ui-ad-maintenance-production-build.log`

| 소스 | SHA-256 |
| --- | --- |
| `ui/src/utils/storageAdIdentity.js` | `5e730614ac43919230ed620173a780590065aff72a70e406e97d928e626b8bb1` |
| `ui/src/views/storage/CreateSharedFS.vue` | `5ed4a061efee2dd707a3cb243aefe1793025f19c1215c23316cc54abb743323b` |
| `ui/src/views/storage/SharedFSTab.vue` | `016352114b447fb54d3b7c75a146f059a6b3942c2831aee35f5b481cbd9aa69c` |
| `ui/public/locales/en.json` | `5a948497f3bbafae45f4a1905ae73c0ffefcea11992984d630bae3f9bee7672b` |
| `ui/public/locales/ko_KR.json` | `ddacadf9934d65a36e94259609b88c5405cc7423175c3f0d45c42c8243c42e20` |
| `ui/tests/unit/views/storage/StorageAdMaintenance.spec.js` | `428c229b517eae704643ffe7f56fd074b135928ec738aca8d75852f311eff20a` |
| `ui/tests/unit/views/storage/CreateSharedFSNvmeAuth.spec.js` | `cf3e0885ece4d1dca863862f339430f9c22312cb60c00e0e0a0a38773bb388ed` |

후보는 커밋된 UI `f10dff7ffc1c1c2eba8f9c0b70abe9fd40379403` 에 위 7 개 파일만 덧씌웠다. Java, native 및 후속 백업 UI WIP 는 포함하지 않으며 Node 14.21.3 / heap 12 GiB 를 사용한다. 실제 `plugins.js` 의 `$pollJob` wrapper 는 tracker 결과를 그대로 반환하므로 `await` 후 `jobstatus === 1` 검사를 사용한다. callback 반환값을 완료 증거로 쓰지 않는다. 이 실제 소스 경로와 해시는 검증 JSON 에 기록했다.

## 운영 빌드 완료

2026-10-08 21:31:07 UTC 에 운영 빌드와 설정 생성이 모두 종료 코드 `0` 으로 통과했다. 실제 산출물은 850 개이며 candidate 및 canonical 소스 7 개의 해시는 검증 시 값과 같다.

- 산출물: `/root/work/epic898-preparation/ui-ad-maintenance-production-candidate/ui/dist`
- `index.html` SHA: `f851e2a0654f1601522cbd2dcfab8cd28daa575f185ecf3770a4f007b84697c0`
- `config.json` SHA: `44d080ee12cd291185d65c81a605e71e455dada1a295b46d51e7de86c877b860`
- 빌드 로그 SHA: `9ae33f84309d7e699d724f168169054894a27f549653757da0b9951158f180c8`
- 산출물 manifest: `/root/work/epic898-preparation/ui-ad-maintenance-production-dist-manifest.json`
- manifest SHA: `17883ed998de5e6a6fec3cb72f803eef366484a72682aaee863fd155b3a54f5a`

실제 API / guest 효과 인수는 새 backend 및 native producer 배포 뒤 별도 수행한다. 현재 소스와 운영 빌드 통과는 실제 가입, 탈퇴, AD 인증, Cloud all4 또는 최종 UI 인수 완료가 아니다. parent pin 후 기능 UI 배포와 Cua 검증을 이어간다.
