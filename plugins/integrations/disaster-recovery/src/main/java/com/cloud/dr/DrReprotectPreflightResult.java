// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.dr;

public class DrReprotectPreflightResult {
    private final boolean ready;
    private final String errorCode;
    private final String message;
    private final DrReprotectAuthoritySpec authoritySpec;

    private DrReprotectPreflightResult(boolean ready, String errorCode, String message,
            DrReprotectAuthoritySpec authoritySpec) {
        this.ready = ready;
        this.errorCode = errorCode;
        this.message = message;
        this.authoritySpec = authoritySpec;
    }

    public static DrReprotectPreflightResult success(DrReprotectAuthoritySpec authoritySpec) {
        return new DrReprotectPreflightResult(true, null, null, authoritySpec);
    }

    public static DrReprotectPreflightResult failure(String errorCode, String message) {
        return new DrReprotectPreflightResult(false, errorCode, message, null);
    }

    public boolean isReady() { return ready; }
    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public DrReprotectAuthoritySpec getAuthoritySpec() { return authoritySpec; }
}
