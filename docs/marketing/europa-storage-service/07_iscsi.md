# iSCSI 서비스

## 권장 헤드라인

**전용 블록 볼륨을 표준 iSCSI LUN으로**

## 사용자 가치

ABLESTACK 데이터 볼륨을 iSCSI target과 LUN으로 제공하고, endpoint,
Initiator IQN ACL, CHAP, 세션, 용량을 통합 관리한다.

## 서비스 모델

```text
ABLESTACK Data Volume
        |
        v
Linux LIO Block Backstore
        |
        v
iSCSI Target IQN / LUN
        |
        v
Client Initiator
```

## 블록 전용 원칙

iSCSI는 파일 기반 LUN 이미지를 제공하지 않는다. 하나의 LUN은 하나의
전용 ABLESTACK 데이터 볼륨 전체를 블록 장치로 사용한다.

### 지원하는 볼륨

- 현재 연결되어 있지만 다른 서비스가 사용하지 않는 raw 데이터 볼륨
- 기존 미연결 데이터 볼륨
- 새로 생성하는 데이터 볼륨

### 사용하지 않는 볼륨

- NFS 또는 SMB가 마운트해 사용하는 파일 볼륨
- 다른 iSCSI LUN이 사용하는 볼륨
- NVMe-oF namespace가 사용하는 볼륨
- OS, boot, swap 장치
- 마운트된 장치 또는 마운트된 하위 장치가 있는 볼륨

기존 볼륨에 파일시스템 서명이 있더라도 System VM은 이를 마운트하지 않고
클라이언트에 raw block으로 제공한다. 데이터 해석은 initiator의 책임이다.

## Target과 LUN

- Target IQN 생성·변경·삭제
- 하나의 Target IQN에 여러 LUN 구성
- LUN 번호 관리
- LUN별 전용 백킹 볼륨
- 실제 볼륨 크기를 LUN 유효 크기로 표시
- Target IQN 단위 ACL

UI는 DB 호환성을 위해 LUN별 행을 유지하면서, 같은 Target IQN에 속한
LUN과 ACL을 그룹 문맥으로 보여준다.

## Endpoint와 포트 그룹

- 기본 TCP 포트 3260
- 추가 포트 리스너
- 같은 포트의 서비스 IP를 하나의 리스너 포트 그룹으로 관리
- target이 노출될 포트 그룹 선택
- 실제 접속 가능한 portal 목록 표시

## 접근 제어

### Initiator IQN ACL

Target IQN에 접속할 수 있는 initiator를 명시적으로 등록한다.

### CHAP

- CHAP 사용 여부
- CHAP 사용자 이름
- CHAP 비밀값의 실행 시점 전달
- 필요 시 mutual CHAP

비밀값은 UI, API 응답, 일반 desired-state JSON, 모니터링 캐시에 저장하지
않는다. 재부팅 복구에 필요한 민감정보는 System VM의 root 전용 secret
store에서만 관리한다.

## 재부팅 복구

LIO가 저장한 `/dev/sdX` 경로를 그대로 신뢰하지 않는다. 부팅 reconcile은
ABLESTACK 볼륨 UUID·이름·serial을 기준으로 장치를 다시 찾고, 관리 대상
target, LUN, ACL, portal을 재구성한다.

## 세션 정보

- 클라이언트 주소
- Initiator IQN
- Target IQN
- LUN 목록
- 접속 endpoint
- 연결 시각과 상태
- 세션 종료

하나의 target이 여러 LUN을 제공할 때 세션 화면은 target 문맥과 LUN 요약을
함께 표시한다.

## 대표 홍보 문구

> 전용 ABLESTACK 데이터 볼륨을 안전한 raw block LUN으로 제공하고,
> Target IQN부터 CHAP과 세션까지 하나의 생명주기로 운영합니다.

## 권장 시각 자료

- 볼륨 3개가 하나의 Target IQN 아래 LUN 0, 1, 2로 연결되는 구조
- 왼쪽에 3260/3261 포트 그룹
- 오른쪽에 Initiator IQN과 CHAP 방패 아이콘

## 페이지 마무리 문구

전용 데이터 볼륨, Target IQN, LUN, Initiator ACL과 CHAP을 하나의
블록 서비스 생명주기로 연결한다.
