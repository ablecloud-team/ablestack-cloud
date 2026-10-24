// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.cloud.storage.snapshot;

import com.cloud.storage.Storage.ImageFormat;

/** Metadata of the file verified on secondary storage before publishing its URL. */
public final class SnapshotExtractResult {
    private final String url;
    private final ImageFormat format;
    private final Long size;
    private final Long physicalSize;

    public SnapshotExtractResult(String url, ImageFormat format, Long size, Long physicalSize) {
        this.url = url;
        this.format = format;
        this.size = size;
        this.physicalSize = physicalSize;
    }

    public String getUrl() { return url; }
    public ImageFormat getFormat() { return format; }
    public Long getSize() { return size; }
    public Long getPhysicalSize() { return physicalSize; }
}
