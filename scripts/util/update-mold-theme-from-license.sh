#!/bin/bash
set -euo pipefail

TYPE=${1:-}
CONFIG_PATH="/usr/share/cloudstack-management/webapp/config.json"

KO_ORIGINAL_FILE="/usr/share/cloudstack-management/webapp/locales/ko_KR.json.org"
KO_SOURCE_FILE="/usr/share/cloudstack-management/webapp/locales/ko_KR.json"
EN_ORIGINAL_FILE="/usr/share/cloudstack-management/webapp/locales/en.json.org"
EN_SOURCE_FILE="/usr/share/cloudstack-management/webapp/locales/en.json"

# 필수 파일 확인
if [[ ! -f "$CONFIG_PATH" ]]; then
  echo "ERROR: CONFIG_PATH가 존재하지 않습니다: $CONFIG_PATH" >&2
  exit 1
fi
if [[ ! -f "$KO_SOURCE_FILE" ]]; then
  echo "ERROR: 한글 소스 파일이 없습니다: $KO_SOURCE_FILE" >&2
  exit 1
fi
if [[ ! -f "$EN_SOURCE_FILE" ]]; then
  echo "ERROR: 영어 소스 파일이 없습니다: $EN_SOURCE_FILE" >&2
  exit 1
fi

# 원본 백업 생성(없을 때만)
if [[ ! -f "$KO_ORIGINAL_FILE" ]]; then
  cp "$KO_SOURCE_FILE" "$KO_ORIGINAL_FILE"
  echo "백업 파일 생성 완료: $KO_ORIGINAL_FILE"
else
  echo "백업 파일이 이미 존재합니다: $KO_ORIGINAL_FILE"
fi

if [[ ! -f "$EN_ORIGINAL_FILE" ]]; then
  cp "$EN_SOURCE_FILE" "$EN_ORIGINAL_FILE"
  echo "백업 파일 생성 완료: $EN_ORIGINAL_FILE"
else
  echo "백업 파일이 이미 존재합니다: $EN_ORIGINAL_FILE"
fi

# JSON 키 한 줄 치환 유틸
replace_json_line() {
  local key="$1"
  local value="$2"
  # ${key} 항목을 ${value}로 변경
  sed -i "/\"${key}\"/ c\\  \"${key}\": \"${value}\"," "$CONFIG_PATH"
}

# 문자열 교체 유틸
replace_in_locale_files() {
  local search="$1"
  local replace="$2"
  sed -i "s/${search}/${replace}/g" "$KO_SOURCE_FILE"
  sed -i "s/${search}/${replace}/g" "$EN_SOURCE_FILE"
}

echo "선택된 테마: ${TYPE:-default}"

if [[ "$TYPE" == "Clostack" ]]; then
  echo "CLOIT CLOSTACK Mold 테마 설정 중...."
  replace_in_locale_files "ABLESTACK" "CLOSTACK"

  replace_json_line "logo" "assets/logo-clostack.png"              # 로고 파일 변경
  replace_json_line "banner" "assets/login-logo-clostack.png"      # 로그인 배너 변경
  replace_json_line "miniLogo" "assets/mini-logo-ablestack.png"    # 미니 로고 변경
  replace_json_line "whiteLogo" "assets/white-logo-clostack.png"   # 화이트 로고 변경
  replace_json_line "footer" "ⓒ 2025 ITCEN CLOIT. All Rights Reserved."  # 저작권 표시 변경
  replace_json_line "appTitle" "CLOSTACK-Mold"                     # 앱 타이틀 변경
  replace_json_line "loginTitle" "CLOSTACK"                        # 로그인 타이틀 변경
  replace_json_line "@primary-color" "#5FB684"                     # 기본 색상
  replace_json_line "@link-color" "#5FB684"                        # 링크 색상
  replace_json_line "@loading-color" "#5FB684"                     # 로딩 색상
  replace_json_line "@processing-color" "#5FB684"                  # 처리 색상
  replace_json_line "@logo-magin-top" "8px"                        # 로고 상단 마진
  replace_json_line "@logo-magin-bottom" "5px"                     # 로고 하단 마진
  replace_json_line "@mini-logo-magin-top" "8px"                   # 미니 로고 상단 마진
  replace_json_line "@mini-logo-magin-bottom" "8px"                # 미니 로고 하단 마진
  echo "CLOIT CLOSTACK Mold 테마 설정 완료!"

elif [[ "$TYPE" == "UCP HV powered by ABLESTACK" ]]; then
  echo "효성인포메이션시스템 UCP HV Mold 테마 설정 중...."
  replace_in_locale_files "ABLESTACK" "UCP HV"

  replace_json_line "logo" "assets/logo-hv.png"                     # 로고 파일 변경
  replace_json_line "banner" "assets/login-logo-hv.png"             # 로그인 배너 변경
  replace_json_line "miniLogo" "assets/mini-logo-hv.png"            # 미니 로고 변경
  replace_json_line "whiteLogo" "assets/white-logo-hv.png"          # 화이트 로고 변경
  replace_json_line "footer" "© HS HYOSUNG INFORMATION SYSTEMS"     # 저작권 표시 변경
  replace_json_line "appTitle" "효성 UCP HV - Mold"                 # 앱 타이틀 변경
  replace_json_line "loginTitle" "효성 UCP HV"                      # 로그인 타이틀 변경
  replace_json_line "@primary-color" "#2f54eb"                      # 기본 색상
  replace_json_line "@link-color" "#2f54eb"                         # 링크 색상
  replace_json_line "@loading-color" "#2f54eb"                      # 로딩 색상
  replace_json_line "@processing-color" "#2f54eb"                   # 처리 색상
  replace_json_line "@logo-magin-top" "4px"                         # 로고 상단 마진
  replace_json_line "@logo-magin-bottom" "0px"                      # 로고 하단 마진
  replace_json_line "@mini-logo-magin-top" "8px"                    # 미니 로고 상단 마진
  replace_json_line "@mini-logo-magin-bottom" "8px"                 # 미니 로고 하단 마진
  echo "효성인포메이션시스템 UCP HV Mold 테마 설정 완료!"

else
  echo "ABLECLOUD ABLESTACK Mold 테마 설정 중...."
  replace_json_line "logo" "assets/logo-ablestack.png"               # 로고 파일 변경
  replace_json_line "banner" "assets/login-logo-ablestack.png"       # 로그인 배너 변경
  replace_json_line "miniLogo" "assets/mini-logo-ablestack.png"      # 미니 로고 변경
  replace_json_line "whiteLogo" "assets/white-logo-ablestack.png"    # 화이트 로고 변경
  replace_json_line "footer" "ⓒ 2021-2025 ABLECLOUD Inc. All Rights Reserved." # 저작권 표시 변경
  replace_json_line "appTitle" "ABLESTACK-Mold"                     # 앱 타이틀 변경
  replace_json_line "loginTitle" "ABLESTACK"                        # 로그인 타이틀 변경
  replace_json_line "@primary-color" "#1890ff"                      # 기본 색상
  replace_json_line "@link-color" "#1890ff"                         # 링크 색상
  replace_json_line "@loading-color" "#1890ff"                      # 로딩 색상
  replace_json_line "@processing-color" "#1890ff"                   # 처리 색상
  replace_json_line "@logo-magin-top" "4px"                         # 로고 상단 마진
  replace_json_line "@logo-magin-bottom" "0px"                      # 로고 하단 마진
  replace_json_line "@mini-logo-magin-top" "8px"                    # 미니 로고 상단 마진
  replace_json_line "@mini-logo-magin-bottom" "8px"                 # 미니 로고 하단 마진
  echo "ABLECLOUD ABLESTACK Mold 테마 설정 완료!"
fi
