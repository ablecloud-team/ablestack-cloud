# NVMe-oF 서비스

## 권장 헤드라인

**최신 블록 연결을 위한 NVMe over TCP 서비스**

## 사용자 가치

ABLESTACK 데이터 볼륨을 NVMe-oF subsystem과 namespace로 제공하고,
리스너, Host NQN 접근 정책, namespace, 세션, 재부팅 복구를 통합
관리한다.

## 현재 제공 엔진과 전송 방식

- 엔진: Linux kernel `nvmet`
- 전송 방식: TCP
- 기본 포트: TCP 4420
- 추가 포트와 서비스 IP 지원

## 서비스 모델

```text
ABLESTACK Data Volume
        |
        v
NVMe Namespace
        |
        v
NVMe-oF Subsystem NQN
        |
        v
TCP Listener / Port Group
        |
        v
NVMe Initiator Host NQN
```

## Subsystem

- Subsystem NQN 생성
- host 접근 정책 설정
- 리스너 포트 그룹 연결
- subsystem 변경과 삭제
- namespace 존재 여부와 종속 관계 확인

### 모든 호스트 허용

`allow any host`를 켜면 별도의 Host NQN ACL 없이 subsystem 접근을
허용한다. UI는 이 정책을 명확히 표시하고, 상속 정책과 명시적 ACL을
구분한다.

### 명시적 Host NQN

모든 호스트 허용을 끈 subsystem은 Host NQN ACL을 등록해야 한다.
ACL의 생성·변경·삭제 작업은 subsystem의 현재 정책과 종속 관계를
검증한다.

## Namespace

- Namespace ID
- 전용 백킹 볼륨
- 현재·기존·신규 볼륨 선택
- 리스너 포트 그룹 선택
- 실제 endpoint 표시
- namespace 크기와 실제 블록 크기
- namespace 변경과 삭제

Namespace ID는 subsystem 내부에서만 유일하다. UI와 API는
`Subsystem NQN + Namespace ID` 조합으로 실제 런타임 namespace를
식별한다.

## Endpoint와 포트 그룹

- 기본 4420 리스너
- 추가 서비스 IP와 포트
- 같은 포트의 wildcard listener와 개별 IP 관계 관리
- 포트 그룹별 subsystem/namespace 노출
- 실제 접속 가능한 endpoint 표시

서비스 IP를 추가하는 작업과 configfs listener를 만드는 작업을 구분하며,
endpoint가 실제 NIC에 적용된 뒤 listener를 활성화한다.

## 블록 볼륨 원칙

NVMe-oF namespace는 전용 데이터 볼륨을 raw block으로 사용해
namespace와 백킹 볼륨의 관계를 명확하게 유지한다.

## Host NQN ACL

- 허용 Host NQN
- subsystem별 ACL
- 모든 호스트 허용 정책과의 관계 표시
- ACL 생성, 변경, 삭제
- 런타임 적용 상태

## 세션 관찰

kernel nvmet TCP는 하나의 논리 세션이 여러 I/O queue 연결로 보일 수 있다.
UI는 이 연결을 운영자가 이해하기 쉬운 전송 세션 단위로 집계해 다음
정보를 제공한다.

- transport session aggregate
- queue 수
- local endpoint와 client 주소
- 연결된 subsystem/namespace
- 설정된 Host NQN 정책

## 재부팅 복구

부팅 reconcile은 볼륨 UUID와 serial을 기준으로 namespace backing device를
다시 찾고, subsystem, namespace, listener, Host NQN 정책을 재구성한다.
영구 식별자 기반으로 장치를 추적해 재부팅 후에도 동일한 백킹 볼륨을
안정적으로 연결한다.

## 대표 홍보 문구

> Linux kernel NVMe over TCP를 기반으로 subsystem, namespace,
> Host NQN 정책과 다중 endpoint를 ABLESTACK에서 통합 운영합니다.

## 권장 시각 자료

- 세 개의 포트 그룹
- 네 개의 subsystem
- subsystem별 namespace
- allow-any-host와 explicit Host NQN 정책의 대비
- initiator에서 discover, connect, block device 사용으로 이어지는 흐름

## 페이지 마무리 문구

NVMe over TCP의 listener, subsystem, namespace, Host NQN 정책과
전용 백킹 볼륨을 ABLESTACK의 통합 운영 경험으로 제공한다.
