# 제품 개요와 아키텍처

## 권장 헤드라인

**하나의 제어 계층과 전용 서비스 가상머신으로 완성하는 통합 스토리지**

## 제품 정의

ABLESTACK Storage Service는 Shared FileSystem 자원을 기반으로 전용
Storage Service System VM을 배치하고, 그 안에서 파일 및 블록 프로토콜을
서비스하는 구조다.

외부에 노출되는 이름과 내부 데이터 경로를 분리하며, 관리 서버가 원하는
상태를 보유하고 System VM이 이를 실행한다.

## 아키텍처 구성요소

### ABLESTACK UI

- Storage Service 생성과 서비스 선택
- 프로토콜별 설정 대화상자
- 공유, 타깃, namespace, ACL, 볼륨, 세션 관리
- 상태 요약과 모니터링 캐시 표시
- 비동기 작업 진행과 결과 표시

### Management API 및 비동기 작업

- UI 요청을 검증하고 원하는 상태로 저장
- 볼륨 생성·연결·확장 작업 조정
- 호스트 에이전트와 QGA 명령 실행
- 작업 단계와 최종 결과 반환

### Storage Service System VM

- 프로토콜 런타임을 실행하는 전용 가상머신
- `ablestack-storagectl`로 desired state 적용
- NFS-Ganesha, Samba, LIO, kernel nvmet 구성
- 볼륨 탐색, 마운트, 파일시스템 확인
- 모니터링 캐시 및 부팅 reconcile 수행

### Backing Volume

- 기본 데이터 볼륨
- 기존 미연결 볼륨
- 추가 신규 볼륨
- 공유·타깃·namespace에 매핑되는 데이터 저장소

### Client

- NFS 또는 SMB 파일 클라이언트
- iSCSI initiator
- NVMe-oF initiator

## 제어 흐름

```text
운영자
  |
  v
ABLESTACK UI
  |
  v
Management API / Async Job
  |
  v
Host Agent
  |
  v
QGA
  |
  v
ablestack-storagectl
  |
  +-- NFS-Ganesha
  +-- Samba
  +-- Linux LIO
  +-- kernel nvmet
```

## 데이터 흐름

```text
Application / Client
  |
  +-- NFS ---------+
  +-- SMB ---------+--> Storage Service System VM --> Backing Volume
  +-- iSCSI -------+
  +-- NVMe-oF -----+
```

관리 트래픽은 API·QGA 경로를 사용하고, 실제 데이터 트래픽은 클라이언트와
Storage Service System VM 사이의 프로토콜 연결로 전달된다.

## 핵심 설계 원칙

### 선언형 원하는 상태

관리 서버는 어떤 프로토콜과 자원이 존재해야 하는지 저장한다. System VM은
동일한 명령을 반복 실행해도 결과가 안정적인 멱등 방식으로 구성을 적용한다.

### cloud-init과 운영 명령의 분리

cloud-init은 초기 부트스트랩에 한정한다. 서비스 추가·수정·삭제와 복구는
QGA를 통한 표준 명령 경로에서 처리한다.

### 외부 서비스 이름과 내부 경로의 분리

- NFS: `<서비스 IP>:/<export-name>`
- SMB: `\\<서비스 IP>\<share-name>`
- iSCSI: Target IQN과 LUN
- NVMe-oF: Subsystem NQN과 Namespace ID

운영용 내부 경로와 일시적인 장치명은 클라이언트 서비스 식별자가 아니다.

### 지속 가능한 볼륨 식별

`/dev/sdb`와 같은 장치명은 재부팅 후 바뀔 수 있다. 볼륨 UUID, serial,
프로토콜 자원 식별자를 기준으로 원하는 상태와 실제 상태를 연결한다.

### 빠른 관찰과 안전한 복구

System VM의 모니터링 서비스가 상태를 파일에 저장하고, 관리 화면은 이
캐시를 읽어 빠르게 표시한다. 부팅 reconcile은 저장된 desired state를
기준으로 마운트와 프로토콜 서비스를 복원한다.

## 데이터 모델

| 모델 | 역할 |
| --- | --- |
| Storage Service Instance | 서비스 인스턴스와 System VM의 생명주기 |
| Storage Service Protocol | 프로토콜 리스너와 서비스 IP·포트 |
| Storage File Share | NFS export와 SMB share |
| Storage Block Target | iSCSI target/LUN과 NVMe subsystem/namespace |
| Storage Access Rule | CIDR, 사용자·그룹, IQN, NQN 기반 ACL |
| Storage Identity Domain | SMB Active Directory 가입 상태 |

## 차별화 포인트

- 파일과 블록 서비스를 하나의 인스턴스에서 선택적으로 조합
- 프로토콜별 특성을 유지하면서 공통 관리 모델 제공
- 기존 볼륨과 신규 볼륨을 같은 워크플로에서 선택
- UI, API, DB, System VM 런타임을 하나의 생명주기로 연결
- 실제 런타임 상태와 관리 모델의 차이를 화면에서 설명

## 권장 다이어그램

한 페이지에 세 개의 수평 계층을 배치한다.

1. 상단: UI와 API
2. 중앙: Storage Service System VM과 네 가지 프로토콜
3. 하단: 기본·기존·신규 Backing Volume

오른쪽에는 모니터링 캐시와 부팅 reconcile을 순환 화살표로 표시한다.
