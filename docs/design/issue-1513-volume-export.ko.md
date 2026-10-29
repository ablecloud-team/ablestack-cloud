# #1513 명시적 할당 볼륨 다운로드 복원

대상: Epic #1510 / Draft PR #1514. 기준 커밋 `8dffe3551c7bb6a9497d5340c06f5d9a76a58ac2`.

## 계약

- `exportVolume`은 Ready Primary → Image의 읽기 전용 내보내기다. 일반 `copyVolume`의 명시적 할당 복사·이동 차단은 유지한다.
- 원본 VM은 정지, 볼륨은 Ready이며 암호화·Flatten·장애 보호·스냅샷 작업이 없어야 한다. 권한과 추출 가능 여부는 기존 API 검사에 따른다. 실행 직전 상태를 다시 검사한다.
- VM 작업 큐와 볼륨별 관리 서버 잠금, Primary의 MigrationRequested 상태 전이로 중복·상충 작업을 제한한다. 실패하면 원본을 Ready로 돌려놓는다.
- GFS2 RAW/QCOW2와 RBD RAW 원본을 독립 QCOW2로 내보낸다. 다운로드 이미지의 sparse 할당은 원본의 Full/falloc/off/metadata 계약과 독립이다. 원본의 경로·형식·할당 intent와 관측 증거는 변경하지 않는다.
- Agent는 시작 전과 변환 중 Secondary의 실제 남은 공간과 여유분을 검사한다. `qemu-img measure`가 Full/falloc 원본의 할당된 zero cluster를 출력 필요 용량으로 과대평가하므로 해당 추정치만으로 sparse 내보내기를 거절하지 않는다. 전용 임시 디렉터리에 변환하고 형식·논리 크기·독립성·`qemu-img check`·전체 `qemu-img compare` 및 원본 불변 검사를 통과한 파일만 원자적으로 공개한다.
- RBD 인증은 권한 0600 임시 설정으로 전달하며 검증 응답에는 자격 증명을 포함하지 않는다. 시간 제한·중단·변환 및 비교 실패 시 자신이 만든 임시 파일만 삭제한다.
- Agent는 원본 UUID/pool/path/논리 크기와 실제 출력 바이트 수·검증 시각을 별도 export receipt로 반환한다. 관리 서버는 구형 Agent, 잘못된 식별자·형식·크기·원본 할당 증거를 포함한 결과를 거절한다. 이 receipt는 allocation evidence가 아니다.
- Secondary의 실제 `.qcow2` 경로로 출력 형식을 판단하므로 RAW 원본의 DB 형식을 바꾸지 않고 올바른 확장자의 다운로드 URL을 만든다.
- 현재 원본에 유효한 캐시는 재사용한다. 변경된 원본의 오래된 Image 사본과 해당 URL만 제거하고 다시 생성한다. Image 정리에는 Primary expunge 및 백업 메타데이터 정리를 호출하지 않는다. 삭제 응답 실패 시 DB 참조를 남겨 재시도 대상을 보존한다. 만료 URL은 재사용하지 않고 기존 SSVM 삭제 계약으로 URL과 해당 Image 파일을 정리한다.
- UI는 할당 정책의 존재만으로 다운로드를 막지 않는다. Ready/정지·분리 상태 및 Flatten 제약과 기존 권한 필터는 보존한다.

## 검증

실패 주입 단위 검사, 변경 Maven 모듈 빌드, UI 검사 후 22·31·32에서 실제 UI → 비동기 작업 → 링크 → 파일을 검증한다. 논리/물리 크기, 형식, 다운로드 해시, 원본과의 전체 내용 비교 및 원본 DB/호스트 불변을 연결한다. 전용 테스트 개체와 다운로드 산출물은 정리하며 22 `rocky98-perftest`는 변경하지 않는다.
