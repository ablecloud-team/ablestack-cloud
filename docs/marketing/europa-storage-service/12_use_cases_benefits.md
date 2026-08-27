# 도입 시나리오와 기대 효과

## 권장 헤드라인

**업무 요구에 맞는 데이터 서비스를, 필요한 방식으로**

## 시나리오 1: Linux 애플리케이션 공유 데이터

### 요구

- 여러 Linux 가상머신에서 동일한 설정·콘텐츠·결과 데이터 사용
- 네트워크 대역별 접근 허용
- 필요에 따른 용량 확장

### 적용

- NFSv4 export 생성
- CIDR ACL과 Root Squash
- XFS 또는 ext4 백킹 볼륨
- export별 용량과 사용량 관리

### 기대 효과

- export와 네트워크 정책을 하나의 서비스로 관리
- 내부 경로가 아닌 일관된 export 이름 제공
- 볼륨 확장과 상태 확인 절차 표준화

## 시나리오 2: 사내 업무 파일 공유

### 요구

- Windows 중심 파일 접근
- AD 사용자 또는 그룹별 권한
- 사용자 세션 확인

### 적용

- SMB share
- Active Directory 가입
- AD 사용자·그룹 ACL
- 명시적 guest 정책
- 세션과 접속 endpoint 확인

### 기대 효과

- 인프라와 계정 권한의 운영 흐름 통합
- 가입 상태와 share ACL을 한 화면에서 확인
- 로컬 사용자와 AD 사용자를 업무 특성에 맞게 선택

## 시나리오 3: 데이터베이스용 블록 스토리지

### 요구

- 전용 블록 장치
- initiator 단위 접근
- CHAP
- 여러 LUN과 세션 가시성

### 적용

- iSCSI Target IQN
- 전용 ABLESTACK 데이터 볼륨
- Initiator IQN ACL
- CHAP 또는 mutual CHAP
- portal과 세션 관리

### 기대 효과

- 볼륨과 LUN의 관계 명확화
- target 단위 접근 정책
- 재부팅 후 안정적인 볼륨 재매핑

## 시나리오 4: 최신 NVMe over TCP 연결

### 요구

- NVMe-oF TCP 기반 블록 접근
- 여러 endpoint
- Host NQN 정책
- namespace 단위 볼륨 매핑

### 적용

- kernel nvmet
- subsystem과 namespace
- 포트 그룹
- allow-any-host 또는 explicit Host NQN
- transport 세션 관찰

### 기대 효과

- 최신 블록 접근 방식을 기존 ABLESTACK 운영 체계에 통합
- 포트·subsystem·namespace 관계의 가시성
- 재부팅 후 configfs 구성 복구

## 시나리오 5: 하나의 데이터에 NFS와 SMB 제공

### 요구

- Linux와 Windows 사용자가 같은 업무 데이터 접근
- 서로 다른 인증·권한 모델

### 적용

- 동일한 백킹 디렉터리에 NFS export와 SMB share 구성
- POSIX 소유자·그룹·ACL 조정
- NFS squash와 SMB 사용자 정책 정렬

### 기대 효과

- 데이터 복제 없이 다중 프로토콜 접근 가능
- 파일 프로토콜별 접속 방식 유지

## 산업별 적용 메시지

### 금융

- 업무망별 NFS ACL
- AD 기반 SMB 업무 공유
- 데이터베이스용 전용 블록 LUN
- 민감정보 비영구 저장 원칙

### 공공

- 표준화된 서비스 생성 절차
- 계정·네트워크 기반 접근 통제
- 기존 볼륨을 활용한 단계적 전환

### 제조

- 설계·생산 파일 공유
- Linux/Windows 혼합 환경의 다중 프로토콜 접근
- 대용량 데이터 볼륨 확장

### 연구·AI

- 공유 데이터셋용 NFS
- NVMe over TCP 실험 환경
- 프로젝트별 전용 볼륨과 접근 정책

## 정성적 기대 효과

| 영역 | 기대 효과 |
| --- | --- |
| 서비스 제공 | 프로토콜별 수작업을 포털·API 기반 흐름으로 전환 |
| 운영 표준화 | ACL, 볼륨, 세션, 상태의 공통 운영 원칙 |
| 데이터 활용 | 기존 볼륨과 신규 볼륨을 동일 워크플로에서 활용 |
| 운영 가시성 | 실제 서비스 상태와 적용 결과의 가시성 향상 |
| 복구 | 재부팅 후 수동 재구성 범위 감소 |
| 보안 | 명시적 접근 정책과 민감정보 비영구 저장 |

## 기대 효과 요약

ABLESTACK Storage Service는 다양한 파일·블록 프로토콜을 하나의 운영
모델로 제공해 서비스 준비, 접근 정책 변경, 용량 확장, 상태 확인과
재부팅 복구의 흐름을 표준화한다.
