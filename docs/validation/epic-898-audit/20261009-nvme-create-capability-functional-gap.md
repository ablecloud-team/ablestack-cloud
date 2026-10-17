# NVMe 생성 화면의 capability 연결 기능 갭

2026-10-09 기능 갭 조사 후 부모 승인으로 생성 화면과 신규 unit test 를 구현했다. SYSTEM selector 의 이전 운영 빌드 및 산출물은 그대로 보존한다. NVMe 신규 19 개와 기존 29 개를 합친 48 개 테스트, 변경 범위 no-fix lint 가 완료 상태로 통과했다. 신규 단위의 운영 빌드도 아래 고정 후보에서 통과했으며 실제 배포 및 Cua 검증은 아직 미완료다. QEMU 재시험이나 클러스터 변경은 수행하지 않았고 최종 UI 표준화 #1275 는 범위 밖이다.

## 현재 요청이 인증을 누락하는 경로

수정 전 `ui/src/views/storage/CreateSharedFS.vue` 의 `nvmeDhChapCreateSupported` 는 고정 `false` 를 반환한다. unsupported 안내도 조건 없이 표시된다. `createInitialBlockServices` 는 현재 computed 값과 snapshot 의 인증 값을 AND 연산하므로 DHCHAP 요청을 항상 `false` 와 빈 키로 낮춘다. 미지원으로 안내하고 사용자가 선택할 수 없는 현재 상태와, 지원되는 새 템플릿에서도 기능을 사용할 수 없는 갭을 구분해야 한다.

`ui/src/views/storage/SharedFSTab.vue` 의 상세 화면은 health 또는 inventory 의 `capabilities.nvmeof` 를 읽고 `dhChapSupported` 및 `dhChapCtrlSupported` 에 따라 입력을 제어한다. 생성 화면에는 아직 실제 instance 가 없으므로 이 관측을 그대로 생성 전 computed 에 사용할 수 없다.

`prepareStorageServiceNvmeOfVm` 의 정상 결과는 engine, transport, commands, configfs 및 nvmet module 상태를 반환한다. 현재 native prepare 는 `validateOnly` 여도 module 을 준비한다. 이 결과에는 DHCHAP 지원 boolean 이 없으므로 인증 지원의 근거로 단독 사용하거나 순수 읽기 전용 조회라고 부르면 안 된다.

## 재사용 가능한 정보와 신뢰 경계

| 정보 | 사용할 수 있는 목적 | 대신할 수 없는 검증 |
| --- | --- | --- |
| 선택된 SYSTEM 의 `listTemplates.details` | 후보의 예상 지원과 버전 표시 | 실제 부팅된 kernel, auth attribute, runtime pin 검증 |
| `storage.service.nvme.target.auth` | producer 가 선언한 템플릿 요구 조건 | 단독으로 프로필 또는 실제 인증 성공 승인 |
| 정확 instance 의 정상 health / inventory | 실제 `capabilities.nvmeof` 관측 | 서명 pin 과 보호된 profile 의 별도 승인 |
| 정상 runtime signed READBACK | 현재 CLI 및 서명 계보 검증 | kernel 또는 configfs capability 검증 |
| 보호된 rendered validation profile | 특정 NEW SPARSE fixture 의 내부 승인 | 일반 사용자 UI 의 공개 지원 capability 승격 |

`TemplateResponse` 는 `details` map 을 반환한다. `SharedFSServiceImpl.validateExplicitTemplate` 는 명시적 선택의 권한, Active/scalable KVM, architecture, zone download 및 compatibility 를 검증한다. 기존 SYSTEM `b843` 의 일반 목록 조건 통과가 새 capability 메타데이터나 실제 생성 성공을 보장하지 않는다. 기본 선택은 아직 어느 템플릿으로 실행될지 프런트엔드가 확정할 수 없으므로 미확인 상태를 지원으로 표시하지 않는다.

normal health / inventory API 는 해당 instance 의 guest dispatcher 를 호출한다. native capability 조회는 비밀을 읽지 않고 자기 임시 sample host 의 auth attribute 존재를 확인한 뒤 정리하는 현재 경로다. 조회 자체의 임시 configfs 동작은 native 계약에 따르며 UI 가 직접 configfs 를 만들거나 기존 값을 변경하지 않는다.

## 구현한 기능 단위와 검증 계약

1. 기존 SYSTEM selector 와 `a-switch` 를 재사용한다. 선택된 템플릿의 선언이 없거나 지원되지 않으면 미확인 또는 미지원 상태를 유지한다. one-way 와 controller 인증을 별도로 계산하며, SPDK 또는 미지원 transport 에는 kernel 지원을 적용하지 않는다.
2. 템플릿 선언은 입력 후보의 예상 지원에만 사용한다. zone, owner, template, engine 변경 시 이전 capability 와 선택을 무효화한다. private USER 와 내부 artifact 참조는 공개 생성 form 에 추가하지 않는다.
3. 생성 후 실제로 반환된 instance UUID 에 대해 정상 bounded health / inventory 를 읽는다. API 응답 성공, instance 일치, 실제 준비된 kernel target 및 auth attribute 의 literal `true` 를 요구한다. `"true"` 문자열, 실패 응답, malformed JSON, 이전 instance 관측은 지원으로 승격하지 않는다.
4. NVMe subsystem, namespace, host ACL 을 만들기 전에 위 검증을 완료한다. 사용자가 DHCHAP 를 요청했지만 fresh 관측이 미지원이면 명시적 실패로 안내하고 인증 없는 ACL 로 바꾸지 않는다. 이미 생성된 SharedFS 의 부분 설정 상태는 그대로 표시하며 전체 성공으로 주장하지 않는다.
5. 보호된 fixture 의 profile 과 signed pin 검증은 백엔드 정상 writer 를 유지한다. UI 는 internal artifact 를 입력하거나 profile 을 우회하지 않는다. 새 공개 capability 계약이 필요하면 backend owner 와 먼저 합의하며 단순 template detail 값을 승인으로 격상하지 않는다.
6. 성공 및 실패 모두 비밀 입력을 `finally` 에서 해제한다. 값을 localStorage, log, request 요약 또는 증빙에 남기지 않는다. 검증에는 DHCHAP boolean 과 결과 상태만 사용한다.
7. 설정 후 조회에서 ACL 존재뿐 아니라 host NQN, 요청한 one-way/controller 활성화 flag 가 일치하는지 확인한다. 실제 key 값 조회는 하지 않는다. 외부 client 의 인증, 거절, mutual, reconnect 및 I/O 는 새 정상 Cloud fixture 에서 별도 인수한다.

## 의미 있는 테스트와 실제 UI 게이트

- 기본 템플릿 미지정과 기존 인증 미사용 요청은 유지한다.
- 적합한 선언, 누락 선언, 잘못된 유형, stale zone / owner / template 응답을 구분한다.
- one-way 지원만 있는 게스트는 controller 인증을 허용하지 않는다. SPDK / 미지원 transport 도 차단한다.
- fresh literal boolean 성공은 요청한 인증 flag 를 유지한다. 문자열 boolean, 실패 응답, foreign instance, missing capability 는 NVMe 대상 생성 전 거절한다.
- 지원 상실을 인증 없는 요청으로 낮추지 않고, 단계 오류와 비밀 해제를 확인한다.
- 실제 UI 에서 지원과 미지원 표시, switch 조건, 정상 request 의 flag 및 키 유출 없는 결과 안내를 확인한다.
- 실제 private USER fixture 는 API 내부 준비 후 UI 결과 조회로 검증한다. 이를 공개 UI 생성 성공으로 대신하지 않는다.

이번 조사로 기존 로컬 6.12 iSCSI / NVMe 인증 PASS 를 Cloud 사용자 UI PASS 로 확대하지 않는다. 실제 새 최종 image, runtime, profile 및 클러스터 functional 인수는 아직 남아 있다. 원본 7 개 서비스, 기존 NEW 20 GiB 와 failed partial DATA, Client OOBE 는 그대로 보존한다.

## 고정 소스와 운영 빌드 증빙

실제 수정 파일은 `ui/src/views/storage/CreateSharedFS.vue` 와 `ui/tests/unit/views/storage/CreateSharedFSNvmeAuth.spec.js` 두 개다. 기존 selector 와 locale 소스는 이 단위에서 변경하지 않았다.

- 컴포넌트 SHA: `671819f19636c4b6927ee94ec49332753a57f0f69bc6e92d07a74b029d6693f2`
- 신규 테스트 SHA: `62d3013700f290beacd973347cf771b1e3e7ff1c9a340172ea6cb40c4ac0183c`
- 테스트 완료 로그 SHA: `1efd1940980b0808d9966e19330de215bf1c0327d26152d73095fccdf27c8594`
- 소스 검증: `/root/work/epic898-preparation/ui-create-nvme-auth-source-validation.json`
- 운영 빌드 후보: `/root/work/epic898-preparation/ui-create-nvme-auth-production-candidate`
- 운영 빌드 로그: `/root/work/epic898-preparation/ui-create-nvme-auth-production-build.log`

후보는 커밋된 UI 기준 `048dfdcd88d55ed8ecdff088f49992c5e3219b64` 에 소스 2 개만 덧씌운 snapshot 이다. Java, native 또는 다른 UI WIP 는 포함하지 않는다. Node 14.21.3 과 heap 12 GiB 를 사용한다. 기존 style block 과 footer button 은 byte 단위로 동일하다. 빌드 전후 candidate 및 canonical 소스 해시는 동일하며, 부모는 d43ab7c12b9adbe3112d130d5016ecec10115405로 두 소스를 pin하고 origin에 반영했다.

실제 요청 순서는 정상 `prepareStorageServiceNvmeOfVm` 의 정확한 instance 및 literal 성공 확인, 15 초 bounded fresh inventory, subsystem, namespace, host ACL 이다. 인증 없는 기존 요청은 capability API 에 의존하지 않는다. 준비와 조회 실패 시 block target 생성은 없으며, 이미 앞 단계에서 생성된 SharedFS / protocol / file 자원은 부분 설정 실패 안내와 함께 보존한다. 새 자동 삭제나 재시도는 추가하지 않았다.

고정 template, engine, transport, host 문맥이 변경되면 이전 입력을 무효화한다. 타임아웃, 늦은 응답, 문자열 boolean, foreign instance, 잘못된 VM binding, 상충하는 인증 flag, 지원 상실 및 생성 job 실패를 검증했다. 입력 값은 form 과 실행 snapshot 의 참조에서 해제하며 브라우저 heap 전체의 물리적 zeroize 를 주장하지 않는다.

## 운영 빌드 완료

2026-10-08 20:58:46 UTC 에 운영 빌드가 종료 코드 `0` 으로 통과했다. 설정 생성도 종료 코드 `0` 이며 산출물은 총 850 개다. 부모 작업이 이 고정 산출물을 배포하고 실제 Cua 기능을 검증한다.

- 산출물 위치: `/root/work/epic898-preparation/ui-create-nvme-auth-production-candidate/ui/dist`
- `index.html` SHA: `e59074bb03a070155cea600cc493bcc683b5d1827d7bf32859d923bbb380a226`
- `config.json` SHA: `44d080ee12cd291185d65c81a605e71e455dada1a295b46d51e7de86c877b860`
- 빌드 로그 SHA: `7d145dba4fc8c56629a946ce6d9b84a74bcc1cd27b8b550d385efd56dd50608f`
- 전체 산출물 manifest: `/root/work/epic898-preparation/ui-create-nvme-auth-production-dist-manifest.json`
- manifest SHA: `48d7d834355d2c4947c6b2569bd59e8ecfcabe30a931f2031479892a36c6b68e`

실제 Cloud 생성, 인증 및 외부 I/O 성공은 이 소스 및 빌드 증거로 주장하지 않는다. 이전 로컬 인증 증거와 원본 데이터, partial DATA, Client OOBE 는 그대로 보존한다.
