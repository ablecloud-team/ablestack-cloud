# #1519 VM 복제 API 입력 계약

기본값은 count=1, startvm=false, clonetype=full이다. 세 인자는 선택값이며 listApis 설명과 getter 및 UI 기본값을 맞춘다. UI는 이미 1~50/시작 끔/Full을 사용하므로 정적 UI 변경 없이 새 API 설명과 서버 검증을 사용한다.

count는 1~50 정수만 허용한다. 기존 API의 50 초과 요청은 431로 거절하는 의도적인 제한 변경이다. 호출자는 최대 50씩 나누고 앞선 Job을 완료한 뒤 새 이름으로 요청해야 한다. 빈 count 및 잘못된 형식은 입력 오류다. startvm은 true/false이며 공통 Boolean 파서의 잘못된 문자열→false 묵시 변환을 이 API에서만 거절한다.

RBD는 full/linked(소문자)를 허용하며 생략은 full이다. 잘못된 값은 legacy/explicit과 무관하게 거절한다. Linked는 모든 원본 디스크가 RBD여야 하며 explicit full 할당 정책 등 기존 보호를 유지한다. GFS2는 고정 Full 전략을 쓰고 생략/full만 허용한다. QCOW2는 기존 fast-copy/독립화, RAW는 기존 검증된 복사 조건을 유지한다.

복제는 원본 계정·도메인·Zone 안에서만 지원한다. account를 보낼 때 domainid도 필요하며 실제 원본 활성 계정과 일치해야 한다. domainid 단독은 원본과 같을 때 호환한다. zoneids는 생략 또는 원본 Zone 하나만 허용한다. 변경 요청을 조용히 무시하지 않고 431로 거절한다. 소유자/Zone 변경 기능은 추가하지 않는다.

create preflight에서 이름·범위·타입·소유자/Zone을 검사하고 InvalidParameterValueException을 530 대신 431로 반환한다. async 실행 시에도 같은 범위/이름 검사를 재수행한다. 기존 권한, 백업, 암호화, 스냅샷 및 할당 정책 검사는 유지한다. 단일/다중 이름 규칙과 최대 63자에 배치 suffix를 포함한다.

PR 소스는 upstream Europa 기준 별도 브랜치로 분리한다. 테스트 배포는 아직 미병합인 #1518 복구 수정을 유지한 통합 소스로 api/server 변경 모듈만 WSL ext4에서 빌드한다. 원본 JAR과 관련 없는 클래스를 보존하고 관리 서버 JAR만 변경한다. Agent와 UI 정적 파일은 변경하지 않는다. WEB-INF, HTTP 200, 활성 클래스 해시 및 실제 UI/Job/DB/스토리지를 확인한다.

전용 qa1519 prefix를 사용하고 22 rocky98-perftest/ROOT/DATA에는 전원·디스크·QGA·마이그레이션·내용 명령을 보내지 않는다. 22의 허용된 기존 base는 읽기 원본으로만 사용하며 모든 자원을 발견한 UUID로 제한한다. 50 경계는 입력/전체 이름 preflight로 검증하고 대규모 50대 실물 배치는 생성하지 않는다. 실제 생성은 기본/명시 입력과 UI 단일·다중 대표 조합으로 검증한다.
