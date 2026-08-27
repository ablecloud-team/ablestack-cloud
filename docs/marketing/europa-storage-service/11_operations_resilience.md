# 운영, 모니터링과 서비스 복구

## 권장 헤드라인

**빠르게 관찰하고, 원하는 상태로 다시 복구합니다**

## 운영 모델

Storage Service는 관리 서버의 desired state와 System VM의 observed
state를 구분한다.

- desired state: 생성되어야 할 서비스, 자원, ACL, 볼륨 매핑
- observed state: 실제 리스너, 프로세스, 마운트, 세션, 사용량

두 상태를 비교해 서비스의 준비 상태와 실제 적용 결과를 표시한다.

## 모니터링 캐시

System VM 내부의 `ablestack-storage-monitor`가 주기적으로 상태를 수집해
파일로 저장한다.

### 수집 대상

- 서비스와 프로세스 상태
- 프로토콜 리스너
- export, share, target, subsystem, namespace
- ACL 적용 상태
- 백킹 볼륨과 파일시스템
- 용량과 사용량
- 클라이언트 세션
- 마지막 관찰 시각

### 사용자 가치

- 화면 진입 때마다 QGA 전체 검사를 실행하지 않음
- 상세 탭을 빠르게 표시
- 같은 시점의 상태 스냅샷 사용
- 마지막 관찰 시각과 상태 신선도 표시

## 부팅 reconcile

`ablestack-storage-reconcile`은 System VM 부팅 후 저장된 desired state를
다시 적용한다.

### 복구 대상

- 백킹 볼륨 식별과 파일시스템 마운트
- NFS-Ganesha endpoint와 export
- Samba share와 AD 관련 상태
- iSCSI target, LUN, portal, ACL
- NVMe-oF listener, subsystem, namespace, Host NQN 정책

### 지속 식별자

재부팅 후 장치 순서가 달라질 수 있으므로 `/dev/sdX`를 신뢰하지 않는다.
볼륨 UUID, serial, 자원 UUID, IQN, NQN, namespace ID를 조합해 다시
매핑한다.

## 멱등 실행

같은 desired state를 반복 적용해도 리스너, export, ACL, fstab 항목을
중복 생성하지 않는 멱등 실행 원칙을 적용한다. 서비스별 변경 범위를
분리해 기존 정상 자원의 운영을 이어간다.

## 세션 관리

### NFS

TCP 연결, 클라이언트 주소, 리스너와 export 문맥을 표시한다.

### SMB

클라이언트, 사용자, share, SMB 버전을 표시한다.

### iSCSI

Initiator IQN, Target IQN, LUN, endpoint를 표시한다.

### NVMe-oF

TCP queue aggregate, endpoint, subsystem과 namespace 문맥을 표시한다.

## 비동기 작업

서비스 변경은 비동기 작업으로 처리한다. UI는 작업을 닫힌 모달 안에
묶어두지 않고 다음을 제공한다.

- 시작 알림
- 진행 상태
- 완료 알림
- 단계별 처리 결과
- 작업 이력

## 대표 홍보 문구

> 실제 서비스 상태를 빠르게 관찰하고, 재부팅 이후에는 안정적인
> 식별자를 기준으로 원하는 구성을 다시 복원합니다.

## 권장 시각 자료

`Desired State -> Apply -> Runtime -> Monitor Cache -> Reconcile`이 순환하는
운영 루프. 가운데에 Storage Service System VM을 배치한다.

## 페이지 마무리 문구

상태 수집, 빠른 화면 조회, 안정적인 식별자 기반 재적용을 하나의 운영
루프로 연결해 서비스 가상머신의 지속적인 운영을 지원한다.
