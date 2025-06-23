#!/bin/bash

type=$1
config_path="/usr/share/cloudstack-management/webapp/config.json"
# ko_path="/usr/share/cloudstack-management/webapp/locales/ko_KR.json"
# en_path="/usr/share/cloudstack-management/webapp/locales/en.json"

if [ "ABLESTACK" == "$type" ]; then

  echo "ABLECLOUD ABLESTACK Mold 테마 설정 중...."

  # 로고 파일 변경
  sed -i "/\"logo\"/ c\  \"logo\": \"assets/logo-ablestack.png\","  $config_path
  sed -i "/\"banner\"/ c\  \"banner\": \"assets/login-logo-ablestack.png\","  $config_path
  sed -i "/\"miniLogo\"/ c\  \"miniLogo\": \"assets/mini-logo-ablestack.png\","  $config_path
  sed -i "/\"whiteLogo\"/ c\  \"whiteLogo\": \"assets/white-logo-ablestack.png\","  $config_path

  # 저작권 표시 변경
  sed -i "/\"footer\"/ c\  \"footer\": \"ⓒ 2021-2025 ABLECLOUD Inc. All Rights Reserved.\","  $config_path

  # 앱 타이틀 명 변경
  sed -i "/\"appTitle\"/ c\  \"appTitle\": \"ABLESTACK-Mold\","  $config_path
  sed -i "/\"loginTitle\"/ c\  \"loginTitle\": \"ABLESTACK\","  $config_path

  # 테마 변경
  sed -i "/\"@primary-color\"/ c\    \"@primary-color\": \"#1890ff\","  $config_path
  sed -i "/\"@link-color\"/ c\    \"@link-color\": \"#1890ff\","  $config_path
  sed -i "/\"@loading-color\"/ c\    \"@loading-color\": \"#1890ff\","  $config_path
  sed -i "/\"@processing-color\"/ c\    \"@processing-color\": \"#1890ff\","  $config_path

  # 로고 사이즈 설정
  sed -i "/\"@logo-magin-top\"/ c\    \"@logo-magin-top\": \"4px\","  $config_path
  sed -i "/\"@logo-magin-bottom\"/ c\    \"@logo-magin-bottom\": \"0px\","  $config_path
  sed -i "/\"@mini-logo-magin-top\"/ c\    \"@mini-logo-magin-top\": \"8px\","  $config_path
  sed -i "/\"@mini-logo-magin-bottom\"/ c\    \"@mini-logo-magin-bottom\": \"8px\","  $config_path

  echo "ABLECLOUD ABLESTACK Mold 테마 설정 완료!"

elif [ "Clostack" == "$type" ]; then

  echo "CLOIT CLOSTACK Mold 테마 설정 중...."

  # 로고 파일 변경
  sed -i "/\"logo\"/ c\  \"logo\": \"assets/logo-clostack.png\","  $config_path
  sed -i "/\"banner\"/ c\  \"banner\": \"assets/login-logo-clostack.png\","  $config_path
  sed -i "/\"miniLogo\"/ c\  \"miniLogo\": \"assets/mini-logo-ablestack.png\","  $config_path
  sed -i "/\"whiteLogo\"/ c\  \"whiteLogo\": \"assets/white-logo-clostack.png\","  $config_path

  # 저작권 표시 변경
  sed -i "/\"footer\"/ c\  \"footer\": \"ⓒ 2025 ITCEN CLOIT. All Rights Reserved.\","  $config_path

  # 앱 타이틀 명 변경
  sed -i "/\"appTitle\"/ c\  \"appTitle\": \"CLOSTACK-Mold\","  $config_path
  sed -i "/\"loginTitle\"/ c\  \"loginTitle\": \"CLOSTACK\","  $config_path

  # 테마 변경
  sed -i "/\"@primary-color\"/ c\    \"@primary-color\": \"#5FB684\","  $config_path
  sed -i "/\"@link-color\"/ c\    \"@link-color\": \"#5FB684\","  $config_path
  sed -i "/\"@loading-color\"/ c\    \"@loading-color\": \"#5FB684\","  $config_path
  sed -i "/\"@processing-color\"/ c\    \"@processing-color\": \"#5FB684\","  $config_path

  # 로고 사이즈 설정
  sed -i "/\"@logo-magin-top\"/ c\    \"@logo-magin-top\": \"8px\","  $config_path
  sed -i "/\"@logo-magin-bottom\"/ c\    \"@logo-magin-bottom\": \"5px\","  $config_path
  sed -i "/\"@mini-logo-magin-top\"/ c\    \"@mini-logo-magin-top\": \"8px\","  $config_path
  sed -i "/\"@mini-logo-magin-bottom\"/ c\    \"@mini-logo-magin-bottom\": \"8px\","  $config_path

  echo "CLOIT CLOSTACK Mold 테마 설정 완료!"

elif [ "UCP HV powered by ABLESTACK" == "$type" ]; then
  echo "효성인포메이션시스템 UCP HV Mold 테마 설정 중...."

  # 로고 파일 변경
  sed -i "/\"logo\"/ c\  \"logo\": \"assets/logo-hv.png\","  $config_path
  sed -i "/\"banner\"/ c\  \"banner\": \"assets/login-logo-hv.png\","  $config_path
  sed -i "/\"miniLogo\"/ c\  \"miniLogo\": \"assets/mini-logo-hv.png\","  $config_path
  sed -i "/\"whiteLogo\"/ c\  \"whiteLogo\": \"assets/white-logo-hv.png\","  $config_path

  # 저작권 표시 변경
  sed -i "/\"footer\"/ c\  \"footer\": \"© HS HYOSUNG INFORMATION SYSTEMS\","  $config_path

  # 앱 타이틀 명 변경
  sed -i "/\"appTitle\"/ c\  \"appTitle\": \"효성 UCP HV - Mold\","  $config_path
  sed -i "/\"loginTitle\"/ c\  \"loginTitle\": \"효성 UCP HV\","  $config_path

  # 테마 변경
  sed -i "/\"@primary-color\"/ c\    \"@primary-color\": \"#2f54eb\","  $config_path
  sed -i "/\"@link-color\"/ c\    \"@link-color\": \"#2f54eb\","  $config_path
  sed -i "/\"@loading-color\"/ c\    \"@loading-color\": \"#2f54eb\","  $config_path
  sed -i "/\"@processing-color\"/ c\    \"@processing-color\": \"#2f54eb\","  $config_path

  # 로고 사이즈 설정
  sed -i "/\"@logo-magin-top\"/ c\    \"@logo-magin-top\": \"4px\","  $config_path
  sed -i "/\"@logo-magin-bottom\"/ c\    \"@logo-magin-bottom\": \"0px\","  $config_path
  sed -i "/\"@mini-logo-magin-top\"/ c\    \"@mini-logo-magin-top\": \"8px\","  $config_path
  sed -i "/\"@mini-logo-magin-bottom\"/ c\    \"@mini-logo-magin-bottom\": \"8px\","  $config_path

  echo "효성인포메이션시스템 UCP HV Mold 테마 설정 완료!"

fi