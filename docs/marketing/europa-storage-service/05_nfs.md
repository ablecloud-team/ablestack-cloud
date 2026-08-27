# NFS 서비스

## 권장 헤드라인

**Linux 파일 공유를 서비스 단위로 빠르고 일관되게**

## 사용자 가치

NFS export 생성부터 endpoint, ACL, 백킹 볼륨, 용량, 세션, 상태를 하나의
서비스 탭에서 관리한다. 사용자는 내부 마운트 경로가 아니라 export 이름을
기준으로 접속한다.

## 클라이언트 접속 모델

```text
<서비스 IP>:/<export-name>
```

예:

```text
mount -t nfs4 10.10.254.10:/data01 /mnt/data01
```

내부 백킹 경로가 `/export/data01`이더라도 클라이언트에는 `/data01`이
루트 이름으로 노출된다.

## 주요 기능

### Export 생명주기

- export 생성
- export 설정 변경
- export 삭제
- 프로토콜 활성화와 endpoint 관리
- export별 백킹 볼륨 선택
- export 용량 제한과 백킹 볼륨 확장

### NFS 프로토콜 모드

- 기본: NFSv4 전용
- 선택: NFSv3 + NFSv4 듀얼 모드

서비스 생성 시 선택한 프로토콜 모드는 해당 Storage Service의 NFS 운영
정책이 된다.

### Endpoint와 포트

NFSv4 전용 모드는 리스너 포트 그룹을 기준으로 여러 endpoint를 구성할 수
있다. 동일 포트 그룹에 속한 서비스 IP를 함께 관리하고 export 노출 범위를
선택한다.

듀얼 모드는 rpcbind와 함께 NFSv3와 NFSv4 클라이언트를 위한 서비스 전역
호환 모드로 운영한다.

### 접근 제어

- 단일 또는 복수 CIDR
- 읽기 전용 / 읽기·쓰기
- Root Squash
- All Squash
- 동기 / 비동기
- 특권 포트 요구 여부
- 익명 UID/GID

ACL이 없을 때의 공개 정책과 명시적 ACL이 추가되었을 때의 전환을
서비스 정책으로 관리한다.

### POSIX 권한

Root Squash와 읽기·쓰기를 함께 사용하는 경우 익명 UID/GID와 디렉터리
소유자·권한을 함께 조정할 수 있다. 기본 정책은 클라이언트 root를
익명 사용자로 매핑하면서도 의도한 공유 디렉터리에 쓰기가 가능하도록
구성한다.

### 백킹 볼륨

- 현재 연결된 백킹 볼륨 선택
- 기존 미연결 볼륨 연결
- 새 볼륨 생성
- XFS 또는 ext4 파일시스템 검사 및 마운트
- `/etc/fstab` 지속 마운트 구성
- 볼륨 사용 중인 export 표시
- 사용 중인 export가 없을 때 볼륨 연결 해제

## NFS-Ganesha 기반 실행

Storage Service NFS는 관리되는 NFS-Ganesha endpoint 단위 서비스로
실행된다.

- export 설정의 선언형 렌더링
- endpoint별 관리 서비스
- NFSv4 pseudo path
- 듀얼 모드에서 NFSv3/NFSv4 제공
- endpoint별 서비스 기동과 상태 관찰

## 상태와 세션

NFS 탭은 다음 정보를 표시한다.

- 활성 리스너와 실제 접속 endpoint
- export 목록과 상태
- ACL 목록
- 백킹 볼륨과 파일시스템
- TCP 세션과 클라이언트 주소
- 마지막 모니터링 갱신 시각

세션 화면은 활성 리스너, 클라이언트 주소, export 문맥을 조합해 현재
NFS 연결 상태를 운영자에게 제공한다.

## 대표 홍보 문구

> NFS export 이름, 네트워크, 접근 정책, 백킹 볼륨을 하나의 서비스
> 모델로 연결해 Linux 파일 공유 운영을 표준화합니다.

## 권장 화면 구성

1. 상단: endpoint와 프로토콜 모드
2. 중앙: export 목록과 CIDR ACL
3. 하단: 백킹 볼륨과 클라이언트 세션
4. 우측 보조 도식: `<IP>:/<export-name>` 접속 구조

## 페이지 마무리 문구

NFS의 export, 네트워크 접근 정책, 백킹 볼륨과 세션을 하나의 서비스
화면으로 연결해 Linux 파일 공유 운영을 단순화한다.
