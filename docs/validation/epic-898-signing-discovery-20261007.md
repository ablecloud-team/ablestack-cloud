# Epic #898 런타임 서명 경로 조사

## 확인한 경로

- 재사용 workflow: .github/workflows/systemvm-kvm.yml.
- CI secret 이름: storage_runtime_signing_private_key.
- 런너 임시 개인키 경로: RUNNER_TEMP/storage-runtime-signing/private.pem. 저장소/아티팩트에는 포함하지 않는다.
- 릴리즈 서명 키 ID: ablestack-runtime-release.
- 저장소 기본 개발 공개키: systemvm/debian/etc/ablestack-storage/runtime-trusted-keys/ablestack-runtime-dev.pem.
- 공식 release.yml은 secrets: inherit을 사용한다. dev-release.yml과 branch-dev-release.yml은 현재 해당 전달이 없다.
- 접근 가능한 upstream/fork repository-level secret 목록에서는 서명 secret을 찾지 못했다. 조직/외부 vault 전체를 조사했다는 의미는 아니다.

## 기존 검증 산출물

- 원본 빌드: https://github.com/dhslove/ablestack-cloud/actions/runs/33223650895
- 원본 commit: 541df9e9721211f01d8786467bef714e0289932e.
- 번들: ablestack-storage-runtime-4.23.0.0-50.tar.gz.
- manifest, detached signature, SHA256SUMS, ablestack-runtime-release.pem이 함께 존재한다.
- 네 파일의 SHA-256 검증과 Ed25519 manifest 서명 검증 통과.
- 로그에 Using an ephemeral CI key 경고가 있어 해당 빌드는 고정 서명 secret을 사용하지 않았다.
- 릴리즈 공개키 DER SHA-256: 6898cf92788f1e9238c2ca986893892acba4949745691d8b2611ee51ce0a51a5.

## 13번 관리 서버와의 대조

- 현재 management JAR에는 storage-runtime/trusted-keys/ablestack-runtime-dev.pem만 존재한다.
- 해당 공개키 DER SHA-256: 8cb21907e446d6dc826ae40f79bbac2bbc54c8cbd79d1546a17c08e15e2c5f13.
- 릴리즈 번들의 keyId 및 공개키와 일치하지 않는다.
- 따라서 기존 서명 번들을 찾는 일과 현재 관리 서버의 trust를 맞추는 일은 별개의 구현·검증 항목이다.

## 구현에서 지킬 조건

- 개인키 원문을 출력하거나 저장소, Management Server, DB, UI, 로그 또는 아티팩트에 저장하지 않는다.
- 기존 서명 번들은 카탈로그 검증에 재사용한다.
- 키 ID뿐 아니라 공개키 지문과 실제 manifest 서명을 대조한다.
- 정식 게시 경로는 고정 CI signer를 필수로 요구하고 개발 임시 signer 사용과 구분한다.
- 테스트용 신규 번들은 메모리 내의 일회성 키와 격리된 테스트 trust로 검증하며 정식 릴리즈 signer로 간주하지 않는다.
