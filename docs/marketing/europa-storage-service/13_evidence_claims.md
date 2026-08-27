# 구현 근거와 홍보 콘텐츠 가이드

## 문서 목적

Storage Service의 구현 결과를 홍보 문구, 제안서, 발표자료, 웹 콘텐츠로
일관되게 확장하기 위한 근거와 권장 메시지를 정리한다.

## 근거 문서

- 제품 설계:
  [storage-service-systemvm-design.md](../../design/storage-service-systemvm-design.md)
- 검증 계획 및 이력:
  [storage-service-systemvm-validation.md](../../validation/storage-service-systemvm-validation.md)
- 기준 브랜치: `codex/europa-storage-service`
- 기준 커밋: `1ef2ca37f77f`

## 전체 구현 범위

### API와 데이터 모델

- Storage Service instance
- protocol listener
- file share
- block target
- access rule
- identity domain
- 비동기 생성·변경·삭제 API

### Storage Service System VM

- `ablestack-storagectl`
- `ablestack-storage-monitor`
- `ablestack-storage-reconcile`
- NFS-Ganesha
- Samba
- Linux LIO
- kernel nvmet

### 통합 UI

- Storage Service 생성 대화상자
- NFS, SMB, iSCSI, NVMe-oF 서비스 탭
- 세로형 작업 대화상자
- 다크모드
- 자원, ACL, 볼륨, 세션, 상태 테이블
- 서비스별 접속 정보와 endpoint 목록

## 핵심 권장 메시지

### 통합 서비스

> NFS, SMB, iSCSI, NVMe-oF를 하나의 Storage Service 생명주기와
> 관리 화면에서 운영합니다.

### 볼륨 운영

> 현재 백킹 볼륨, 기존 미연결 볼륨, 신규 볼륨을 서비스 목적에 맞게
> 선택하고 관리합니다.

### 접근 제어

> CIDR, 사용자·그룹, Initiator IQN, Host NQN 등 프로토콜별 접근
> 주체를 명확하게 관리합니다.

### 운영 가시성

> 모니터링 캐시를 통해 리스너, 서비스 자원, 백킹 볼륨과 세션 상태를
> 빠르게 확인합니다.

### 서비스 복구

> 재부팅 시 안정적인 자원 식별자를 기준으로 마운트와 프로토콜 구성을
> 다시 적용합니다.

## 프로토콜별 권장 설명

| 서비스 | 권장 설명 |
| --- | --- |
| NFS | Ganesha 기반 export, NFSv4 및 듀얼 모드, CIDR ACL |
| SMB | 로컬 계정과 Active Directory, 사용자·그룹 ACL |
| iSCSI | 전용 raw 데이터 볼륨 기반 Target IQN과 LUN |
| NVMe-oF | kernel nvmet TCP 기반 subsystem과 namespace |

## 고객 가치 연결

| 구현 기능 | 고객 가치 |
| --- | --- |
| 네 가지 프로토콜 선택 | 워크로드별 데이터 접근 방식 제공 |
| 공통 생성·관리 UI | 운영 절차의 일관성 |
| 기존·신규 볼륨 | 데이터 자산 활용과 확장 |
| 프로토콜별 ACL | 명시적인 접근 정책 |
| 상태 캐시 | 빠른 운영 상태 확인 |
| 세션 목록 | 실제 사용 현황 가시성 |
| 부팅 reconcile | 재부팅 후 서비스 구성 복원 |
| 비동기 작업 | 장시간 작업의 진행 상황 확인 |

## 릴리즈와 배포 체계

릴리즈 자동화는 다음 산출물을 하나의 제품 전달 흐름으로 구성한다.

- `noredist` 빌드
- KVM용 System VM 템플릿
- UI 정적 자산
- 관리 서버 백엔드 산출물

UI, API, System VM 구성요소를 함께 관리해 Storage Service의 전체
생명주기를 일관되게 전달한다.

## 콘텐츠 제작 체크리스트

- [ ] 제품 이름을 `ABLESTACK Storage Service`로 통일했는가
- [ ] 네 가지 프로토콜의 통합 운영 가치를 앞에 배치했는가
- [ ] 파일 서비스와 블록 서비스의 사용 목적을 구분했는가
- [ ] GiB/MiB 단위를 정확히 사용했는가
- [ ] iSCSI를 전용 블록 볼륨 기반 LUN으로 설명했는가
- [ ] NVMe-oF를 kernel nvmet/TCP 기반으로 설명했는가
- [ ] 상태 캐시와 재부팅 reconcile의 사용자 가치를 설명했는가
- [ ] 실제 UI 캡처의 식별자와 계정 정보를 비식별화했는가
- [ ] 기능 나열보다 고객 업무와 운영 효과를 중심으로 구성했는가
