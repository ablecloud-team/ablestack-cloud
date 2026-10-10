# 기존 시험 DATA의 명시적 OWNED all4 프로필

F1의 네 프로토콜 핵심 UI·인증·실제 I/O 검증 이후 all4 프로필을 준비했다. 현재 다섯 디스크의 Ready/SPARSE/F1 연결, 네 DATA의 templateId NULL·chainInfo 공란 및 소유자·존을 확인했다. 보호된 초기 완료 생성 영수증과 artifact·claim은 ROOT/초기 DATA의 NEW 출처와 일치했다. 추가 FILE의 지정 CreateVolume 조회는0행으로 신규 출처를 주장하지 않는다.

기존 NEW_SPARSE_ALL4_VALIDATION은 DATA의 최근 생성 또는 승인 provenance를 엄격하게 요구한다. 두 FILE은24시간이 지나고 기존 프로필이 없어 정상 검증을 진행하지 못했다. 소스978ec10a93af2e1176df6b7fe13bee19bc7e0f4f는 별도 OWNED_SPARSE_ALL4_VALIDATION을 지원한다. 이는 사용자가 승인한 별도 disposable F1의 현재 디스크를 검증하며 신규 생성 출처를 위조하지 않는다.

OWNED는 보호 artifact의 ownedDisposableFixture literaltrue·정확한 instance/name/VM/ROOT/DATA binding·원본 및 다른 instance 전체 제외·SPARSE/FAT·Ready/attached·소유자/존을 유지한다. 실제 DATA templateId NULL 및 chainInfo 공란은 configure·매 사용·CAS 저장 직전에 DAO로 확인한다. artifact.kind와 저장된 profile.kind는 일치해야 하고 stale binding/동일 revision의 profile 변형도 거절한다. productionCapability=false·fresh signed runtime/handler 조건과 imported/disable/recovery 규칙을 유지한다. 기존 NEW 조건은 그대로이며 만료된 생성 승인이나 key·snapshot을 되살리지 않는다. 새 API·DDL은 없다.

변경은 Manager/프로필 코드2 및 기존 ProfileTest/새 AdmissionTest2다. 실제 configure→protectedStore→DAO→CAS 및 required 사용 경로를 호출한 핵심22개가 통과했다. null provisioning, NEW reused 거절, OWNED old/unbacked 승인, backing/template·소유자·연결 변경과 profile kind·CAS drift를 검증했다.

정상 관리 모듈은994개 테스트/130개 그룹에서 failure/error/skip0, Checkstyle·BUILD SUCCESS다. 관련 소스5041의 전후 NUL 및 archive 일치를 확인하고 immutable 산출물을 고정했다. Native480/UI373은 변경되지 않아 반복하지 않았다.

정상 바이트 차이는 server7(API/KVM/storagevm0)이지만 내부5 클래스는 실행 코드·fields·descriptor·참조가 기존과 같아 재사용한다. 실제 의미 변경은 Manager7bbf 및 Profileb216의2개 클래스다. 기존 public/protected ABI를 보존하고 protected helper1과 owned method1 추가를 명시했다. member2607 및 클래스 링크를 해결했다. 이후 제한 배포와 보호 artifact 설정·정상 UI all4 검증은 실제 확인 대상으로 남는다.

최종 UI 표준 #1275는 미착수다.

[핵심22개](owned-all4-profile/focused-proof.json), [독립 검토](owned-all4-profile/independent-peer-final-public-proof.json), [정상994/130](owned-all4-profile/manager-normal-core-proof.json), [최종 소스·산출물](owned-all4-profile/final-normal-source-proof.json), [실제 의미2 클래스 호환성](owned-all4-profile/two-class-runtime-abi-review.json).
