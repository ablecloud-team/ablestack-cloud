# 작업 제어 DAO 등록 및 13번 클러스터 배포

## 문제와 수정

신규 작업 제어 DAO가 수동 Spring 등록 파일에 누락되어 관리 서버 시작 중 주입 오류가 발생했다. 첫 신규 배포 시 HTTP 503을 확인한 뒤 기존 b38 관리 JAR을 복원했다. DB에 신규 작업 제어 테이블은 남았으나 기존 인스턴스 행과 디스크는 변경하지 않았다.

0c70b1cafb1637bf71d1b2f8cb44ff0823231b1a에서 META-INF/cloudstack/core/spring-engine-schema-core-daos-context.xml에 DAO bean을 등록했다. 실제 패키지 XML을 XmlBeanDefinitionReader로 읽어 실제 DAO를 생성하고 Manager의 @Inject를 주입하는 회귀 테스트를 추가했다. 다른 Manager 의존성은 mock으로 제한하므로 이 테스트 자체를 전체 운영 서버 기동 검증으로 주장하지 않는다.

## 빌드 및 실제 배포 증빙

- 모듈 검증: 74개 클래스, 439개 테스트, 실패·오류·생략 0.
- Java 소스 9831개, 경로 NUL 바이트 NUL 방식 SHA-256: 4c4f2e7ab6532346aef0e0da90c2a9819c2669439d6e7f5a9696e9c473206667.
- 배포 XML SHA-256: b73bb0e8954a39f3ea89e5e9fcd61e9fc698f591f028982373468feb4c3fd6e0.
- 기존 b38 런타임 업그레이드 구현과 새 클래스·XML을 함께 검증한 48개 테스트 통과.
- 전체 소스 overlay 58개 관리 항목; 검증용 임시 조합은 56개 관리 항목. full payload ecbd5690ab77ed50fac1ed948b9c29cd211c1d5a2c2161cd04d8f1622692448a, 임시 payload f96523e44ddccaf74c1e3b128740f7c163a656832da694986eeae3839c4ab8b7.
- 관리 서버 백업 /root/epic898-backup-20261008-180816, 실제 활성화 2026-10-08 18:09:15 KST, PID 1130318.
- 활성 관리 JAR SHA-256: 8b1d567fcb5195cf4fd5e48f49b2b4b62a6935e59c6546eb35ae0effc7888674. 수정 대상 외의 기존 JAR 항목 바이트가 보존됨.
- 새 프로세스 기동 뒤 09:10:11 UTC에 HTTP 200, 새로운 admin 로그인 및 API 응답을 확인했다. 기존 7개 인스턴스 Running, 13.1/13.2/13.3 호스트 Up/Enabled.
- getStorageServiceOperationControl, cancelStorageServiceOperation, drainStorageServiceOperation, repairStorageServiceSmbIdentity 등록 확인.
- 호스트 13.1/13.2/13.3에는 cloud-api 클래스 1개만 순차 반영하고 mold-agent를 재시작했다. 다른 JAR 항목 보존 및 실제 연결 회복을 확인했다.
- 배포 후 게스트 건강 조회 7개가 모두 성공했고, 각 요청 약 1.02~1.03초였다. 실제 인증·파일 I/O·partial 파일 시스템 건강 상태는 별도 검증 대상이다.

## DB 변경과 한계

신규 cloud.storage_service_operation_control 테이블만 적용했다. 컬럼은 id, operation_id, instance_id, control_revision, cancel_requested, cancel_requested_by, drain_state, policy_json, lease_json, created_by, created, updated이다. operation_id 고유 인덱스 및 instance_id 인덱스를 포함한다. 반복 적용 시 기존 8개 저장 인스턴스 행과 신규 제어 행 0개가 보존되었다. API의 활성 인스턴스 수 7개와 이 저장 행 수를 혼동하지 않는다.

전체 서명 템플릿이 아직 준비되지 않아 StorageServiceRuntimeUpgradeManagerImpl과 내부 클래스 2개의 기존 b38 바이트를 보존했다. 다른 배포 항목은 0c70 소스이다. 따라서 전체 서버가 단일 소스로 정렬되었다고 주장하지 않는다. 소스의 엄격한 UNKNOWN 버전/AD 기능 손실 검증은 유지했지만 해당 새 검증은 이 임시 조합에서 활성화되지 않는다. 최종 동일 소스 템플릿 준비 후 전체 구현으로 정렬해야 한다.

자원 제어 기본 플래그는 비활성 상태이며, 논리적 자원 여유 임대는 실제 RAM 예약을 뜻하지 않는다. 드레인 및 전체 작업 제어의 실제 API/UI 검증과 SMB 복구 기능 검증이 남았다. 최종 UI 표준화 #1275는 사용자 추가 지시 전 착수하지 않는다.
