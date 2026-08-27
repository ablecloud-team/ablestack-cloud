# ABLESTACK Storage Service 홍보자료 기초 원고

## 문서 목적

이 디렉터리는 `europa-storage-service` 변경 내용을 브로슈어, 제안서,
발표자료, 웹 콘텐츠로 발전시키기 위한 기초 원고 모음이다.

참고 자료인 `에이블스택을 이용한 금융 클라우드 구축 전략`의 서사 구조를
따르되, Storage Service의 실제 구현 범위와 검증 결과에 근거해 내용을
재구성했다.

- 시장 변화와 고객 과제
- 해결 전략과 핵심 가치
- 제품 및 아키텍처 개요
- 프로토콜별 상세 기능
- 통합 관리, 보안, 운영 안정성
- 적용 시나리오와 기대 효과
- 구현 근거와 핵심 콘텐츠
- 22페이지 발표자료 스토리보드

## 권장 대표 제목

**ABLESTACK Storage Service**

파일과 블록 스토리지 서비스를 하나의 운영 경험으로 통합하다

## 한 문장 소개

ABLESTACK Storage Service는 전용 Storage Service System VM을 기반으로
NFS, SMB, iSCSI, NVMe-oF를 통합 제공하고, 생성부터 접근 제어, 볼륨,
세션, 모니터링, 장애 복구까지 하나의 관리 화면과 API로 운영하게 하는
소프트웨어 정의 스토리지 서비스 계층이다.

## 핵심 메시지

1. **하나의 서비스, 네 가지 프로토콜**
   - NFS, SMB, iSCSI, NVMe-oF를 동일한 생명주기와 운영 원칙으로 관리한다.
2. **인프라가 아니라 서비스 단위 운영**
   - 공유, 타깃, 서브시스템, ACL, 볼륨, 세션을 사용자 업무 단위로 제공한다.
3. **기존 자산과 신규 자산을 함께 활용**
   - 현재 연결 볼륨, 기존 미연결 볼륨, 신규 볼륨 생성 흐름을 지원한다.
4. **빠른 상태 조회와 자동 복구**
   - 모니터링 캐시와 부팅 시 reconcile로 상태 조회 부하를 낮추고 재부팅 후
     원하는 구성을 복원한다.
5. **민감정보를 남기지 않는 접근 제어**
   - AD 가입 암호, SMB 사용자 암호, CHAP 비밀값 등은 실행 시점에만 전달하고
     영구 저장하지 않는다.

## 문서 목록

| 파일 | 활용 목적 |
| --- | --- |
| [01_market_context.md](01_market_context.md) | 시장 배경, 고객 과제, 전환 필요성 |
| [02_campaign_messages.md](02_campaign_messages.md) | 핵심 카피, 엘리베이터 피치, 대상별 메시지 |
| [03_product_architecture.md](03_product_architecture.md) | 제품 정의, 구성요소, 제어 흐름, 데이터 흐름 |
| [04_management_ui.md](04_management_ui.md) | 생성·상세·서비스 탭 중심의 통합 UI |
| [05_nfs.md](05_nfs.md) | NFS 서비스 상세 원고 |
| [06_smb.md](06_smb.md) | SMB 및 Active Directory 연계 상세 원고 |
| [07_iscsi.md](07_iscsi.md) | iSCSI 서비스 상세 원고 |
| [08_nvmeof.md](08_nvmeof.md) | NVMe-oF 서비스 상세 원고 |
| [09_volume_capacity_safety.md](09_volume_capacity_safety.md) | 볼륨, 용량, 파일시스템, 데이터 안전 |
| [10_security_identity_access.md](10_security_identity_access.md) | 인증, ACL, 비밀정보 보호 |
| [11_operations_resilience.md](11_operations_resilience.md) | 모니터링, 상태 캐시, 재부팅 복구, 세션 |
| [12_use_cases_benefits.md](12_use_cases_benefits.md) | 도입 시나리오와 기대 효과 |
| [13_evidence_claims.md](13_evidence_claims.md) | 구현 근거와 활용 가능한 핵심 메시지 |
| [14_slide_storyboard.md](14_slide_storyboard.md) | 22페이지 발표자료 구성안 |
| [15_visual_direction.md](15_visual_direction.md) | 다이어그램, 표, 이미지 제작 지침 |
| [16_glossary.md](16_glossary.md) | 용어집과 표기 원칙 |

## 활용 순서

1. `01`과 `02`에서 대상 고객과 핵심 메시지를 선택한다.
2. `14`의 스토리보드에서 필요한 페이지를 고른다.
3. `03`부터 `12`까지에서 페이지별 본문과 도식 요소를 가져온다.
4. `13`에서 해당 문구의 구현·검증 근거와 권장 메시지를 확인한다.
5. `15`의 시각화 원칙으로 실제 슬라이드 또는 브로슈어를 제작한다.
6. 최종 배포 전 `16`의 용어와 단위 표기를 일괄 검수한다.

## 기준 정보

- 작업 브랜치: `codex/europa-storage-service`
- 기준 커밋: `1ef2ca37f77f`
- 제품 설계:
  [storage-service-systemvm-design.md](../../design/storage-service-systemvm-design.md)
- 검증 계획 및 이력:
  [storage-service-systemvm-validation.md](../../validation/storage-service-systemvm-validation.md)

이 자료는 완성된 광고 문안이 아니라, 기술 사실을 훼손하지 않고 다양한
홍보물로 확장하기 위한 모듈형 원고다.
