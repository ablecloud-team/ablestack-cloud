# 보안, 인증과 접근 제어

## 권장 헤드라인

**프로토콜에 맞는 인증, 공통 원칙으로 관리하는 접근 정책**

## 핵심 메시지

NFS의 네트워크 ACL, SMB의 사용자·그룹 권한, iSCSI의 Initiator IQN,
NVMe-oF의 Host NQN은 기술적으로 서로 다르다. Storage Service는 이
차이를 유지하면서도 생성, 변경, 삭제, 상태 확인이라는 공통 운영 경험을
제공한다.

## 프로토콜별 접근 정책

| 서비스 | 접근 주체 | 인증·권한 |
| --- | --- | --- |
| NFS | CIDR 또는 클라이언트 네트워크 | RO/RW, squash, sync, secure |
| SMB | 로컬 사용자·그룹, AD 사용자·그룹 | read, write, guest 정책 |
| iSCSI | Initiator IQN | target ACL, CHAP, mutual CHAP |
| NVMe-oF | Host NQN 또는 모든 호스트 | explicit ACL, allow-any-host |

## 최소 권한 원칙

### NFS

허용할 CIDR과 권한을 명시한다. 공개 상태와 명시적 ACL 상태를 구분한다.

### SMB

guest 접근을 사용하지 않는 share는 명시적으로 허용된 사용자와 그룹만
접근한다. AD 가입 권한과 share 사용 권한을 분리해 최소 권한 원칙을
유지한다.

### iSCSI

Target IQN에 Initiator IQN ACL을 연결한다. CHAP이 설정된 경우 initiator는
정확한 사용자와 비밀값을 제공해야 한다.

### NVMe-oF

`모든 호스트 허용`과 `명시적 Host NQN`을 서로 다른 정책으로 표시한다.
모든 호스트 허용 subsystem에서는 중복 ACL을 만들지 않고 정책 상속을
명확하게 보여준다.

## Active Directory 통합

- AD FQDN
- NetBIOS 도메인
- DNS 서버
- 대상 OU
- 가입 계정
- 가입 상태

를 관리한다. AD 도메인 가입 계정과 SMB share 접근 사용자는 분리한다.

도메인 가입 성공은 명령 반환뿐 아니라 실제 가입 상태 검증을 포함한다.

## 민감정보 처리

### 영구 저장하지 않는 정보

- AD 가입 암호
- SMB 로컬 사용자 암호
- iSCSI CHAP 비밀값
- mutual CHAP 비밀값

### 전달 원칙

1. 사용자가 UI에 입력
2. 비동기 API 요청에 일회성 포함
3. QGA 실행 payload로 전달
4. 런타임 적용
5. 일반 응답, 로그, 모니터링 캐시에서 제거

재부팅 복구에 반드시 필요한 iSCSI CHAP 정보는 System VM 내부의 root 전용
secret store에 한정해 보관한다.

## 감사와 상태 가시성

UI는 비밀값 대신 다음을 표시한다.

- 인증 사용 여부
- 사용자 또는 주체 식별자
- ACL 상태
- 적용 결과
- 마지막 상태 갱신 시각

## 안전한 접근 정책 원칙

- 사용 가능한 인증과 접근 정책만 명확하게 제시한다.
- 실제 적용 상태를 기준으로 ACL 상태를 표시한다.
- 명시적 접근 정책에 따라 서비스 공개 범위를 유지한다.
- ACL 변경 시 프로토콜 정책에 맞는 기본 상태를 일관되게 적용한다.

## 대표 홍보 문구

> 네트워크, 사용자, IQN, NQN까지 프로토콜별 접근 주체를 명확히
> 관리하고, 민감한 비밀값은 실행 시점에만 안전하게 전달합니다.

## 권장 시각 자료

중앙에 Storage Service를 두고 네 방향에 `CIDR`, `AD User/Group`,
`Initiator IQN`, `Host NQN`을 배치한 접근 정책 다이어그램.

## 페이지 마무리 문구

프로토콜별 접근 주체와 인증 정책을 명확하게 구분하고, 민감정보의
노출 범위를 최소화하는 운영 구조를 제공한다.
