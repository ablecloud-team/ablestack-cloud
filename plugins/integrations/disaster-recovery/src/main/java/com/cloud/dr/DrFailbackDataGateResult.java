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

public final class DrFailbackDataGateResult {
    private final boolean ready;
    private final String errorCode;
    private final String message;

    private DrFailbackDataGateResult(boolean ready, String errorCode, String message) {
        this.ready = ready;
        this.errorCode = errorCode;
        this.message = message;
    }

    public static DrFailbackDataGateResult ready() {
        return new DrFailbackDataGateResult(true, null, null);
    }

    public static DrFailbackDataGateResult blocked(String errorCode, String message) {
        return new DrFailbackDataGateResult(false, errorCode, message);
    }

    public boolean isReady() { return ready; }
    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
}
