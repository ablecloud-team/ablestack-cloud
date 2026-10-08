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

package com.cloud.agent.api;

public class StorageServiceRuntimeHostCommand extends Command {
    public static final int MAX_RAW_CHUNK_BYTES = 48 * 1024;

    private String vmName;
    private StorageServiceRuntimeOperation operation;
    private String transactionId;
    private StorageServiceRuntimeFileType fileType;
    private String keyId;
    private long offset;
    private int rawLength;
    private String chunkSha256;
    private String fileSha256;
    private String chunkBase64;
    private boolean truncate;
    private boolean finalChunk;
    private String requestJson;
    private int timeoutSeconds;

    protected StorageServiceRuntimeHostCommand() {
    }

    public StorageServiceRuntimeHostCommand(final String vmName, final StorageServiceRuntimeOperation operation,
            final String transactionId, final String requestJson, final int timeoutSeconds) {
        this.vmName = vmName;
        this.operation = operation;
        this.transactionId = transactionId;
        this.requestJson = requestJson;
        this.timeoutSeconds = timeoutSeconds;
        setWait(timeoutSeconds);
    }

    public StorageServiceRuntimeHostCommand(final String vmName, final String transactionId,
            final StorageServiceRuntimeFileType fileType, final String keyId, final long offset,
            final int rawLength, final String chunkSha256, final String chunkBase64,
            final boolean truncate, final boolean finalChunk, final int timeoutSeconds) {
        this(vmName, StorageServiceRuntimeOperation.WRITE_CHUNK, transactionId, null, timeoutSeconds);
        this.fileType = fileType;
        this.keyId = keyId;
        this.offset = offset;
        this.rawLength = rawLength;
        this.chunkSha256 = chunkSha256;
        this.chunkBase64 = chunkBase64;
        this.truncate = truncate;
        this.finalChunk = finalChunk;
    }

    public String getVmName() {
        return vmName;
    }

    public StorageServiceRuntimeOperation getOperation() {
        return operation;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public StorageServiceRuntimeFileType getFileType() {
        return fileType;
    }

    public String getKeyId() {
        return keyId;
    }

    public long getOffset() {
        return offset;
    }

    public int getRawLength() {
        return rawLength;
    }

    public String getFileSha256() { return fileSha256; }
    public void setFileSha256(String value) { fileSha256=value; }

    public String getChunkSha256() {
        return chunkSha256;
    }

    public String getChunkBase64() {
        return chunkBase64;
    }

    public boolean isTruncate() {
        return truncate;
    }

    public boolean isFinalChunk() {
        return finalChunk;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
