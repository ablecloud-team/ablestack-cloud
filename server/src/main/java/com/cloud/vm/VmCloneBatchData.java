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
package com.cloud.vm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted checkpoints are internal; API views are rebuilt with current access checks. */
class VmCloneBatchData {
    String name, type, sourceUuid, error, currentAttempt, operationId;
    int count;
    long sourceOfferingId;
    Long sourceTemplateId;
    boolean start, prepared;
    String status="QUEUED";
    List<SourceDisk> sourceDisks=new ArrayList<>();
    List<Item> items=new ArrayList<>();
    Map<String,Attempt> attempts=new LinkedHashMap<>();
    static class SourceDisk { long id; String uuid, type, format, policy; Long device, pool, size; }
    static class Attempt { List<Integer> indices=new ArrayList<>(); Long jobId; boolean started, finished; }
    static class Item {
        int index; String name, phase="PLANNED", error; Long vmId; boolean vmRecorded, created;
        List<Disk> disks=new ArrayList<>();
    }
    static class Disk { String uuid, type, attempt; long sourceId; }
}

