# Epic #898 로컬 인증 상태 보호·복구 중간 검증

검증 코드: `58f8bac5b9c737623f378e6c40fda093c6d6e9f6`.

이 기록은 #892의 로컬 인증 보호 기반을 실제 테스트 VM에서 확인한 결과입니다. 전체 원자적 설정 변경, 관리 서버 재시작 재개, 동일 VM ROOT 교체(#920) 또는 #909의 전체 완료를 뜻하지 않습니다. SMB AD는 사용자 지시로 보류합니다.

## 빌드

- Maven 관리 서버·KVM 모듈 빌드 성공. 구성 아티팩트·복원 계획·권한·기존 API 바인딩·rollback 테스트 38개 통과.
- 별도 KVM transport 및 진단 테스트 5개 통과.
- UI 구성 복원·locale 테스트 14개, 네이티브 저장소 테스트 54개 통과.
- 네이티브 테스트에는 두 번째 보호 파일 교체 실패 시 이전 파일 전체 복구, 관리 계정 provenance와 현재 레코드 불일치 거부, 외부 계정 보존, account/hash 파일 범위와 payload 형식·보호 mode 검사가 포함됩니다.

## 배포

- 서명 런타임: `epic898-20261007-identity-capsule`.
- catalog: `6b9df890-38ad-4771-8c2e-2a7fe1f5373c`, 서명/checksum 검증 후 AVAILABLE.
- runtime upgrade: `825eae92-230d-4b78-ace7-a0a8b974322c`, COMPLETE.
- 적용 대상은 작업용 SharedFS이며 원래 서비스들의 ROOT/DATA를 변경하지 않았습니다.
- 호스트 13.1·13.2에는 identity capsule 메모리 파이프 전달 wrapper 클래스 하나만 교체했습니다. 기존 jar의 다른 항목은 byte 단위로 보존했습니다. 세 Routing host가 모두 Up으로 복귀한 것을 확인했습니다.
- 관리 서버의 새 구성 API/DB migration/검증 복원점 활성화는 아직 하지 않았습니다.

## 실제 로컬 인증 확인

게스트의 실제 Samba passdb/secrets와 범위가 지정된 non-login 로컬 계정을 수집했습니다. AES-GCM 및 RSA-OAEP로 캡슐을 보호하고 게스트 메모리 안에서 복호화 후 동일 상태 복구를 실행했습니다. 개인키·비밀번호·credential hash 원문은 로그나 임시 파일에 기록하지 않았습니다. 캡슐의 파일 allowlist에는 데이터 볼륨·AD keytab이 없습니다.

첫 파일 byte 비교에서는 TDB representation 차이가 관측됐습니다. 해당 실행을 byte 보존 성공으로 판정하지 않았습니다. 후속 검증에서 실제 SAM credential 레코드를 메모리에서 비교하고, OS 계정 파일 및 기존 데이터 sentinel의 보존을 확인했습니다. 동일 상태 복구를 다시 실행한 검증에서는 모든 관측 파일 byte도 일치했습니다. 기존 계정의 실제 `smbclient` 접근이 성공했습니다.

## 남은 게이트

- credential 변경 실패 및 CHAP/NVMe 인증 상태 복구, 여러 프로토콜의 실제 실패 주입.
- 관리 서버·게스트 재시작 재개, operation generation 및 drain/cancel.
- 신규 구성 API를 통한 전체 backup/import/plan/apply/LKG/clone 실제 API·UI 검증.
- 동일 VM ROOT 교체 수명주기와 이전 ROOT 복구.

이슈 완료 전에는 남은 게이트의 결과를 각 이슈에 기록하고 완료 증빙을 남긴 뒤 닫습니다.
