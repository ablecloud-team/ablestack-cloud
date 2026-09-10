#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Security Check 
# JAVA로 작성된 Test 코드를 실행하는 Junit.jar 파일을 사용하여 보안 기능과 관련된 Utils 프로세스가 정상적으로 작동하는지 확인하는 스크립트
# 항목 : string, encrypt(db), encrypt(rsa), password, sshkey, http
# return : {이름, 결과}

jarfile='/usr/share/cloudstack-common/lib/'
cmd=${jarfile}junit-4.13.2.jar:${jarfile}hamcrest-all-1.3.jar:${jarfile}cloudstack-utils-test.jar:${jarfile}cloudstack-utils.jar

# String 유틸리티 
string_result=$(java -classpath $cmd org.junit.runner.JUnitCore com.cloud.utils.StringUtilsTest | grep -i OK)
if [ -n "$string_result" ]; then
    echo "string,true"
else
    echo "string,false"
fi

# encrypt(db) 유틸리티 
encrypt_db_result=$(java -classpath $cmd org.junit.runner.JUnitCore com.cloud.utils.crypt.EncryptionSecretKeyCheckerTest | grep -i OK)
if [ -n "$encrypt_db_result" ]; then
    echo "encrypt(db),true"
else
    echo "encrypt(db),false"
fi

# encrypt(ras) 유틸리티 
encrypt_ras_result=$(java -classpath $cmd:${jarfile}bcprov-jdk18on-1.83.jar org.junit.runner.JUnitCore com.cloud.utils.crypto.RSAHelperTest | grep -i OK)
if [ -n "$encrypt_ras_result" ]; then
    echo "encrypt(rsa),true"
else
    echo "encrypt(rsa),false"
fi

# password 유틸리티 
password_result=$(java -classpath $cmd:${jarfile}bcprov-jdk18on-1.83.jar org.junit.runner.JUnitCore com.cloud.utils.PasswordGeneratorTest | grep -i OK)
if [ -n "$password_result" ]; then
    echo "password,true"
else
    echo "password,false"
fi

# sshkey 유틸리티 
sshkey_result=$(java -classpath $cmd:${jarfile}jsch-0.1.55.jar org.junit.runner.JUnitCore com.cloud.utils.ssh.SSHKeysHelperTest | grep -i OK)
if [ -n "$sshkey_result" ]; then
    echo "sshkey,true"
else
    echo "sshkey,false"
fi

# http 유틸리티 
http_result=$(java -classpath $cmd:${jarfile}javax.servlet-api-4.0.1.jar:${jarfile}spring-test-5.3.26.jar:${jarfile}spring-core-5.3.26.jar:${jarfile}commons-logging-1.2.jar org.junit.runner.JUnitCore com.cloud.utils.HttpUtilsTest | grep -i OK)
if [ -n "$http_result" ]; then
    echo "http,true"
else
    echo "http,false"
fi