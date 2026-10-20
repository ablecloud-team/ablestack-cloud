# SharedFS 생성의 SYSTEM 템플릿 입력과 검증 경계

소스 검토에서 `CreateSharedFS.vue` 는 사용자 정의 컴포넌트이며 `buildCreateSharedFsRequest` 가 `templateid` 를 보내지 않음을 확인했다. 이전 실행 계획에서 private USER fixture 를 UI 에서 직접 선택한다고 적은 부분을 바로잡았다. 이번 변경은 일반 SYSTEM 템플릿 선택의 기능 입력과 요청 보완이며, 최종 UI 표준화인 #1275 는 착수하지 않았다.

## 기능 변경

기존 기본 정보 form 과 `a-select` 를 사용해 선택 사항인 `templateid` 를 추가했다. 미지정 또는 기본값이면 기존 자동 선택 요청을 유지한다. `listTemplates` 의 owner, project, zone 범위를 지정하고 Admin / DomainAdmin 은 `all`, 일반 사용자는 `executable` 로 조회한다. `system=true` 요청만 신뢰하지 않고 실제 응답의 ID, SYSTEM 유형, 준비 상태와 확장 가능 여부의 boolean 값, KVM, zone, arch 를 확인한다. USER, 준비 중, 미지원, 오래된 응답 또는 조회 실패 상태에서는 명시적 선택 요청을 차단한다.

같은 zone 에서 owner 가 변경된 경우, 이전 zone 의 늦은 응답, 대화상자 종료 후 응답은 token 과 scope 를 대조해 버린다. 일반 form 필터가 참조값을 생략해도 현재 선택을 보존하며, 사용자가 기본값을 고르면 `templateid` 를 생략한다. 요청 builder 는 허용된 필드만 만들므로 `validationartifactuuid` 와 SHA 를 전달하지 않는다.

프런트엔드 목록은 후보를 표시한다. 최종 launch 권한, zone, 템플릿과 ROOT capability, 보호된 artifact 검증은 백엔드가 수행한다. 백엔드의 명시적 선택은 기존 기본 선택과 달리 launch 권한을 자동 추가하지 않는다. 일반 form 에 내부 RootAdmin artifact 참조나 새 secret 흐름은 노출하지 않는다.

기존 style block 은 byte 단위로 동일하며 CSS, theme, footer button 배치를 바꾸지 않았다. 기존 locale key 와 form component 를 재사용했다. 숨겨진 Vue 상태 변경이나 브라우저 API 주입은 하지 않았다.

## 소스 검증과 운영 빌드 후보

수정 소스는 `CreateSharedFS.vue` 와 `CreateSharedFSTemplateSelection.spec.js` 두 개다. 신규 14 개와 기존 request, constraints, locale 테스트를 합쳐 4 개 suite 의 29 개 테스트가 완료 상태로 통과했다. 변경 범위의 no-fix lint 도 통과했으며 검증 전후 소스 해시는 동일하다.

- 컴포넌트 SHA: `23b10541ca66c6ce46c302c0c7946def70032ce5191cdd520ec906aacda225ad`
- 테스트 SHA: `a0921091636b455bc22213921a786502dd5a870e8a997512ba7a2e8225983944`
- 완료 로그 SHA: `025f693e1ee8ce7766f8ceca03f4ea884b7e9fbab25ad9b20a5b14d30bd3982a`

운영 빌드는 커밋된 UI 기준 `f5536a3a28d8a60778323a686edcebf139623371` 에 위 소스 2 개만 덧씌운 고정 snapshot 에서 진행한다. Node 14.21.3 과 heap 12 GiB 를 사용하며 Java, native 또는 다른 UI WIP 는 포함하지 않는다. 실제 배포와 Cua 검증은 아직 수행하지 않았고, 부모 작업이 소스 pin 과 정적 배포 후 확인한다. 빌드의 최종 상태와 산출물 증거는 `/root/work/epic898-preparation/ui-create-system-template-source-validation.json` 에 기록한다.

운영 빌드는 2026-10-08 20:35:13 UTC 에 종료 코드 `0` 으로 통과했다. 설정 생성도 종료 코드 `0` 이며 산출물은 총 850 개다. 후보와 canonical 소스의 빌드 전후 해시는 위 값과 일치한다. 선택 필드 추가 외 기존 style block 과 footer button 은 변경하지 않았다.

- 산출물 위치: `/root/work/epic898-preparation/ui-create-system-template-production-candidate/ui/dist`
- `index.html` SHA: `2a6e969a8b23446553c17fabfc7211224156696c17bba4e02cfa78fc4454713f`
- `config.json` SHA: `44d080ee12cd291185d65c81a605e71e455dada1a295b46d51e7de86c877b860`
- 빌드 로그 SHA: `429719b7f7222e7746bdebd73ce4d40a5817dfd1583c80dfba6e5bbec57c7d20`
- 전체 파일 manifest: `/root/work/epic898-preparation/ui-create-system-template-production-dist-manifest.json`

실제 배포 및 Cua 기능 검증은 여전히 미완료다. 소스와 운영 빌드 통과를 사용자 UI 생성 성공으로 승격하지 않는다.

## API 준비와 사용자 UI 검증을 구분한 계획

보호된 private USER `T_source` / `F1` 와 private `T_target` 의 validation artifact UUID 와 SHA 는 내부 정상 API 준비 경로에만 사용한다. 실제 UI 의 상세, 프로토콜, 작업, 이력, 결과 조회로 연결하되 이를 UI 생성, 선택 또는 실행 성공으로 주장하지 않는다.

일반 생성 `F_UI` 는 적합한 SYSTEM 템플릿 선택과 기본값의 실제 UI 요청, job, 결과를 검증한다. 기존 ROOT 업그레이드 form 은 SYSTEM catalog 의 target, preflight, execute 기능을 재사용한다. 적합한 SYSTEM target 이 없으면 해당 UI 성공 게이트는 OPEN 으로 유지한다. private USER 참조를 숨겨 입력하거나 API 주입으로 UI 기능을 대체하지 않는다.

이 경계는 [단일 기능 실행 흐름](20261009-new-cluster-all4-functional-flow.md)에 반영했다. private CREATE_NEW / multi NEW 의 API 준비 경로와 일반 SYSTEM / EXISTING 의 사용자 UI 복원 및 결과 조회를 구분한다. `F_UI` 의 별도 25 GiB 예산은 실제 capacity checkpoint 에 포함한다.

## 발견한 별도 기능 갭

`CreateSharedFS` 의 `nvmeDhChapCreateSupported` 는 현재 고정된 `false` 값이며 unsupported 안내도 고정이다. 템플릿 선택만으로 이를 `true` 로 승격하지 않았다. 다음 별도 기능 단위에서 authoritative template, kernel, handler, profile 조건에 따른 지원 여부와 실제 UI 및 외부 client 인수를 연결해야 한다.

ROOT, AD, native, 클러스터의 실제 변경은 이번 단위에서 수행하지 않았다. 최종 11 개 탭의 style, theme, button, dialog, keyboard QA 는 미착수이며 소스 테스트 통과만으로 기능 이슈를 닫지 않는다.
