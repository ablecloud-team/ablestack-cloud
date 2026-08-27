# 용어집과 표기 원칙

## 제품명

| 권장 표기 | 설명 |
| --- | --- |
| ABLESTACK | 회사 및 플랫폼 브랜드 |
| ABLESTACK Storage Service | 외부 홍보용 제품·기능명 |
| Storage Service System VM | 서비스 프로토콜을 실행하는 전용 가상머신 |
| Storage Service | 문맥상 제품명이 명확할 때 사용하는 축약명 |

`europa-storage-service`는 개발 작업명 또는 브랜치 문맥에서만 사용한다.

## 공통 용어

| 영문 | 권장 한글 | 설명 |
| --- | --- | --- |
| desired state | 원하는 상태 | 관리 서버에 저장된 목표 구성 |
| observed state | 실제 상태 | System VM에서 관찰한 런타임 구성 |
| reconcile | 상태 재적용 | 원하는 상태와 실제 상태를 맞추는 작업 |
| backing volume | 백킹 볼륨 | 서비스 데이터를 저장하는 ABLESTACK 볼륨 |
| endpoint | 엔드포인트 | 클라이언트가 접속하는 IP와 포트 |
| listener | 리스너 | 프로토콜이 수신 대기하는 IP와 포트 |
| listener port group | 수신 포트 그룹 | 같은 포트에 속한 서비스 리스너 집합 |
| access rule | 접근 허용 규칙 | 서비스 자원에 대한 ACL |
| session | 세션 | 클라이언트의 현재 또는 최근 연결 |
| runtime cache | 런타임 캐시 | System VM이 저장한 관찰 상태 파일 |
| async job | 비동기 작업 | 장시간 작업의 진행과 결과를 추적하는 작업 |

## NFS

| 영문 | 권장 한글 | 설명 |
| --- | --- | --- |
| export | NFS 내보내기 | 클라이언트에 노출되는 NFS 공유 |
| export name | 내보내기 이름 | 클라이언트가 마운트하는 루트 이름 |
| pseudo path | 클라이언트 마운트 루트 | NFSv4에서 노출되는 경로 |
| backing path | 내부 백킹 경로 | System VM 내부 실제 데이터 경로 |
| root squash | Root Squash | 클라이언트 root를 익명 사용자로 매핑 |
| all squash | All Squash | 모든 사용자를 익명 사용자로 매핑 |
| allowed CIDR | 허용 CIDR | 접근을 허용할 네트워크 범위 |

## SMB

| 영문 | 권장 한글 | 설명 |
| --- | --- | --- |
| share | SMB 공유 | 클라이언트에 노출되는 SMB 공유 |
| local account | 로컬 계정 | System VM의 Samba 로컬 사용자 |
| Active Directory | Active Directory | 디렉터리 기반 사용자·그룹 인증 |
| domain join | 도메인 가입 | Storage Service를 AD 멤버로 등록 |
| workgroup | NetBIOS 도메인 | Samba가 사용하는 짧은 도메인 이름 |
| guest access | 게스트 접근 | 명시적 사용자 인증 없이 접근 |

## iSCSI

| 영문 | 권장 한글 | 설명 |
| --- | --- | --- |
| target IQN | 대상 IQN | iSCSI target의 고유 이름 |
| initiator IQN | 초기자 IQN | iSCSI 클라이언트의 고유 이름 |
| LUN | LUN | target 아래 제공되는 논리 블록 장치 |
| portal | 포털 | iSCSI 접속 IP와 포트 |
| CHAP | CHAP 인증 | 사용자와 비밀값 기반 iSCSI 인증 |
| mutual CHAP | 상호 CHAP | target과 initiator가 상호 인증 |
| block backstore | 블록 백스토어 | LIO가 사용하는 전용 raw 볼륨 |

## NVMe-oF

| 영문 | 권장 한글 | 설명 |
| --- | --- | --- |
| NVMe-oF | NVMe-oF | NVMe over Fabrics |
| NVMe over TCP | NVMe over TCP | TCP 전송 기반 NVMe-oF |
| subsystem NQN | 서브시스템 NQN | NVMe-oF subsystem의 고유 이름 |
| host NQN | 호스트 NQN | NVMe initiator의 고유 이름 |
| namespace | 네임스페이스 | subsystem이 제공하는 블록 자원 |
| namespace ID | Namespace ID | subsystem 내부 namespace 번호 |
| allow any host | 모든 호스트 허용 | Host NQN ACL 없이 접근 허용 |
| transport session | 전송 세션 | TCP queue 연결을 집계한 관찰 단위 |

## 용량 단위

| 단위 | 바이트 | 사용 위치 |
| --- | ---: | --- |
| B | 1 | API 저장·전송 기준 |
| KiB | 1,024 | 세부 사용량 |
| MiB | 1,048,576 | 사용량과 작은 quota |
| GiB | 1,073,741,824 | 볼륨 크기와 일반 quota |
| TiB | 1,099,511,627,776 | 대용량 quota |

`MB`, `GB`, `TB` 대신 1024 기반 의미가 분명한 `MiB`, `GiB`, `TiB`를
사용한다.

## 상태 용어

| 영문 | 한글 | 사용 기준 |
| --- | --- | --- |
| Ready | 준비됨 | 원하는 상태와 실제 상태가 일치 |
| Running | 실행 중 | 프로세스 또는 VM이 실행 중 |
| Updating | 갱신 중 | 비동기 서비스 변경이 진행 중 |
| Connected | 연결됨 | 클라이언트 세션이 활성 상태 |

## 문체

- 사용자 행동 중심의 능동형 문장을 사용한다.
- 기능 이름보다 사용자가 얻는 결과를 먼저 쓴다.
- 구현된 기능과 고객 가치를 명확하고 간결하게 설명한다.
- 내부 클래스명과 DB 테이블명은 홍보 본문보다 기술 부록에서 사용한다.
- 동일한 기능과 개념은 모든 문서에서 같은 용어로 표현한다.
