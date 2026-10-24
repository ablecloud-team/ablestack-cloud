//
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
//

package com.cloud.agent.api.storage;

import com.cloud.agent.api.Answer;

public class CreateEntityDownloadURLAnswer extends Answer {
    private com.cloud.storage.Storage.ImageFormat format;
    private Long virtualSize;
    private Long fileSize;

    public com.cloud.storage.Storage.ImageFormat getFormat() { return format; }
    public Long getVirtualSize() { return virtualSize; }
    public Long getFileSize() { return fileSize; }
    public void setImageMetadata(com.cloud.storage.Storage.ImageFormat format, long virtualSize, long fileSize) {
        this.format = format;
        this.virtualSize = virtualSize;
        this.fileSize = fileSize;
    }

    public static final short RESULT_SUCCESS = 1;
    public static final short RESULT_FAILURE = 0;

    public CreateEntityDownloadURLAnswer(String resultString, short resultCode) {
        super();
        this.details = resultString;
        this.result = resultCode == RESULT_SUCCESS;
    }

    public CreateEntityDownloadURLAnswer() {
    }
}
