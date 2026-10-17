# CNI UI 표준화 설계 (#1408)

Epic #1395, PR #1409. 기준은 Europa `dc31262567f2afe5f6278fae61c1cef7e307fe3e`와 현재 VM 메뉴의 공통 컴포넌트이다.

## 적용 원칙

상시 노출되는 기능 설명을 제거하고, 필드 라벨·필요한 툴팁·동적 오류·권한·사용 중 삭제 차단·빈 결과 안내는 유지한다. API 계약, ACL, 삭제 전 재확인, 본문 바이트 보존은 변경하지 않는다.

| 화면 | VM 기준/공통 구현 | 적용 |
|---|---|---|
| 목록·Breadcrumb·검색·페이지 이동 | AutogenView, ListView | 동일 툴바 유지, CNI 전용 검색 설명 문단 제거 |
| 상세·요약·소유자·링크·UUID 복사 | ResourceLayout, ResourceView, InfoCard, DetailsTab | 공통 레이아웃 그대로 사용, 긴 이름·파라미터 반응형 확인 |
| CNI 본문 | VM에도 사용하는 UserDataContent | CNI에만 추가된 계약·용량 설명 제거, 복사/저장/본문 전환 유지 |
| 사용 Kubernetes·노드 | VmVolumesTab의 detail-tab-toolbar, 작은 표 | 설명·중복 제목 제거, 공통 툴바, 1100/800px 표 내부 스크롤, 긴 필드 줄바꿈 |
| 코멘트 | VM과 동일한 AnnotationsTab | 공통 탭·페이지 이동·등록/삭제 유지 |
| 등록 | RegisterUserData + MoldDialog | CNI 설명·API 문구 placeholder 제거, 자동 높이 본문, 프로젝트 라벨, 용량 제한 툴팁, CNI 필드 첫 오류만 표시 |
| 삭제 | MoldDialog lg | 간결한 확인, 작은 표, 사용처 갱신 보조 아이콘 우측, 차단·처리 결과 유지 |
| 고급 검색 | AutogenView 공통 검색 popover | VM과 같은 입력/버튼/여백 유지 |
| Kubernetes CNI 선택기 | 공통 UserDataSelection | 중복 선택 설명은 시각적으로 숨기고 접근성 상태 알림 유지, 자동값 라벨 정리 |

## 검증 기준

1440/1024/768/390px × light/dark에서 목록·상세의 4개 탭·등록/삭제·검색·선택기 화면을 확인한다. 브라우저 전체 가로 넘침과 표/본문 내부 스크롤을 구분한다. VM 목록/상세/볼륨/스냅샷 대화상자를 참조하고 일반 UserData 회귀를 확인한다. 로딩·같은 문맥 갱신·빈 결과·오류·권한·키보드·제출 중 상태는 실제 UI 경로와 제한된 XHR 제어로 확인한다.

## 후속 기능의 적용 책임

#1404 파일/도움말, #1405 미리보기, #1406 복제, #1407 변경 이력/비교 UI는 아직 구현되지 않았다. 해당 화면을 검증 PASS로 세지 않는다. 현재 구현된 전체 CNI 화면과 공통 스타일을 #1408에서 정리하고, 앞으로 생기는 화면은 각 기능 이슈에서 동일 VM/MoldDialog 표준과 실배포 검증을 완료한다. Epic 전체 완료 전에는 이 네 기능을 포함한 최종 화면 목록을 다시 확인한다.

실제 빈 등록 폼에서 required와 본문/이름 validator가 중복 안내되던 것을 발견했다. CNI 이름과 본문에 validate-first를 적용해 각각 첫 오류만 표시하며 기존 required 표시·검증 규칙·일반 UserData 동작은 유지한다.
