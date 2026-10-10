# 안정 릴리즈 서명 경로 추가 조사

2026-10-09 현재 소스의 정식 workflow는 systemvm-kvm.yml에서
secrets.storage_runtime_signing_private_key를 읽어 protected runtime signer에
전달한다. require_stable_runtime_signing_key 입력이 true면 key 부재 시
publication을 차단하는 의미 있는 테스트가 있다.

현재 계정으로 repository Actions secret의 이름·수정시각 목록을 조회했지만
storage/runtime 일치 항목은 없었다. repository environment는 github-pages
하나이며 이 workflow에는 environment binding이 없다.
조직 Actions secret 목록 조회는403(조직 관리자/Actions secrets 권한 필요)으로
거절됐다. 조직 키가 실제 없다고 단정하거나 새로운 권한 scope를 요청하지 않았다.

private key 원문·API token은 읽거나 저장하지 않았다. 현재 테스트 artifact는
sealed RAM test key로 정상 서명·검증했고 공개 키만 저장했다. 최종 안정 릴리즈
서명은 조직 운영자의 키 제공/CI 설정 확인이 필요한 미완료 게이트로 남긴다.
이 조건은 나머지 독립 기능 구현·실제 UI/API 시험을 중단하지 않는다.

최종 UI #1275는 착수 전 중단·보고 후 추가 지시를 기다리며 아직 시작하지 않았다.
