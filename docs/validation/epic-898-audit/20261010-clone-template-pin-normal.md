# CREATE_NEW 복원 계획의 템플릿 고정 검증

계획 작성과 실행 사이 기본 SYSTEM 템플릿이 바뀌어 다른 이미지를 선택할 수 있는 공백을 보완했다. 고정 소스는 6b37a166f2d05c9d88eb64912ccf5e9146c733a3다. 제품 변경은 관리 서버 두 파일이며 새 테스트와 기존 AD 성공 사례를 보완했다. UI·API·DDL 변경은0이다. 실제 클러스터 배포와 F2 생성은 아직0이며 최종 UI #1275는 미착수다.

## 구현 범위

계획에 선택 모드·정확한 UUID·DB 원 checksum·전체 details canonical SHA·zone·arch를 고정하고, capability 소비 및 새 Cloud 할당 직전에 재검증한다. DEFAULT_SYSTEM은 현재 기본 선택이 달라지면 거절한다. EXPLICIT_SYSTEM은 지정한 이미지, PRIVATE_USER는 기존 정상 permit 검증을 유지한다. AD source는 원 요청의 명시 fresh UUID와 seedAbsent를 default normalize 전에 검사한다.

원 raw 요청 mapping은 유지하고 서버 blueprint만 normalize한다. 생성된 target UUID와 검증된 실제 VM/ROOT binding은 같은 artifact update에 기록한다. binding 실패는 관측 가능한 exact provenance를 보존하고, 새 정상 review/token이 같은 VM/ROOT를 재검증한 경우만 재개한다. 없는 provenance, 관측하지 못한 ROOT 또는 바뀐 ROOT를 자동 채택하거나 새 자원 할당으로 바꾸지 않는다.

기존 createConfigurationNewService의 alloc→deploy 완료 전 예외/유실 때 provenance 미기록 위험은 별도 잔여다. 이번 pin 수정으로 전체 부분 생성의 중복 할당 문제가 해결됐다고 주장하지 않는다. SharedFS/VM/ROOT/DATA의 durable ID를 시작 전에 기록하고 같은 ID로 재개하는 후속 구현을 준비한다.

## 고정 소스와 정상 모듈 검증

집중 신규17/관련62 =79tests/9suites 통과 후 기존 AD 성공 사례를 실제 template/ROOT verifier로 보완해96tests/10suites를 통과했다. 첫 정상02b는 기존 positive fixture의 pin 부재로1실패했고, 두 번째8ca는 test 한 줄의 Checkstyle 길이에서 멈췄다. 생산 guard를 낮추거나 테스트를 제거하지 않았다. 줄 분리만 한6b의 집중 의미 검증은96 결과를 재사용했다.

최종 정상 모듈은920tests/125suites·failures/errors/skips0·Checkstyle·reactor PASS다. 이전888 대비 새 CloneTemplatePin17과 해당 이전 PIN 이후의 KVM15를 구분했다. 신규 코드 tests만으로920을 채웠다고 해석하지 않는다.

Java9904의 NUL SHA a9f8792dd47be366b28257e615f5e41bb549f57d0c161774a57cf0fcad2e506f와 tracked source 전후 SHA가 같다. 현재 archive의 file/symlink15523과 directory5189를 구분했고 missing/different0이다. 과거11208 비교 범위를 현재 수치로 다시 표시하지 않는다. 5모듈 immutable output6312개를 고정했다.

정상888 산출물과 비교한 관리 server delta는 두 outer와 익명/내부4개를 포함해6클래스다. API/schema/provider delta0이며 KVM Wrapper1은 관리 배포 payload에서 제외했다. 실제1337 캐시의 기존6 entry SHA가 baseline과 같고, 기존 exported ABI·상속과 signature6 및 member refs2708/failed0을 확인했다. Runtime family3의 bytes도 같다. 이 검증은 이전 실제 캐시 기반이며 새로운 SSH live 관측으로 확대하지 않는다.

실제 배포 전 JAR15f361/PID1331803·provider9·운영UI/config·source/volume refs를 새로 확인하고, backed-up deployed JAR의 해당6 entry만 atomic overlay한다. 전체 다른 JAR나 게스트 CLI19ad를 교체하지 않는다. 현재 df11의 원 key/cipher/ref/frozen CLI를 유지한 복구가 선행이다.

[최종 정상 증거](clone-template-pin-6b37a166/normal-source-validation.json), [실제 production delta](clone-template-pin-6b37a166/production-class-delta-vs-888.json), [캐시 runtime ABI 검증](clone-template-pin-6b37a166/cached-runtime-abi-review.json).
