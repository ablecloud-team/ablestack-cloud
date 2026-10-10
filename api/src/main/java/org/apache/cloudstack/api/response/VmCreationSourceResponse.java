/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.api.response;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.cloudstack.api.BaseResponse;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

/** Authorized, read-only creation source. No storage credentials or paths are returned. */
public class VmCreationSourceResponse extends BaseResponse {
    @SerializedName("id") @Param(description = "Source UUID") public String id;
    @SerializedName("sourcekind") @Param(description = "volume or snapshot") public String sourcekind;
    @SerializedName("name") @Param(description = "Source name") public String name;
    @SerializedName("state") @Param(description = "Source state") public String state;
    @SerializedName("volumetype") @Param(description = "Original volume type") public String volumetype;
    @SerializedName("zoneid") @Param(description = "Zone UUID") public String zoneid;
    @SerializedName("sizebytes") @Param(description = "Logical ROOT bytes") public Long sizebytes;
    @SerializedName("sourcevolumeid") @Param(description = "Original volume UUID") public String sourcevolumeid;
    @SerializedName("snapshotcreated") @Param(description = "Snapshot capture time") public Date snapshotcreated;
    @SerializedName("sourceusage") @Param(description = "adopt-existing or restore-new") public String sourceusage;
    @SerializedName("hypervisor") @Param(description = "Source hypervisor") public String hypervisor;
    @SerializedName("arch") @Param(description = "Architecture") public String arch;
    @SerializedName("templateid") @Param(description = "Boot provenance image UUID") public String templateid;
    @SerializedName("sourcevm") @Param(description = "Authorized source VM identity") public Map<String, String> sourcevm = new LinkedHashMap<>();
    @SerializedName("storage") @Param(description = "Storage within caller visibility") public Map<String, String> storage = new LinkedHashMap<>();
    @SerializedName("bootprofile") @Param(description = "Captured boot environment") public Map<String, String> bootprofile = new LinkedHashMap<>();
    @SerializedName("requiresconfiguration") @Param(description = "Guest execution settings need administrator input; not a bootability verdict") public boolean requiresconfiguration;
    @SerializedName("configurationorigin") @Param(description = "cloud-record, administrator or unspecified") public String configurationorigin;
    @SerializedName("imageformat") @Param(description = "Registered disk image format") public String imageformat;
    @SerializedName("formatorigin") @Param(description = "upload-declared or driver-inspected") public String formatorigin;
    @SerializedName("allowed") @Param(description = "Can be used for deployment") public boolean allowed;
    @SerializedName("reasoncodes") @Param(description = "Stable eligibility reasons") public List<String> reasoncodes = new ArrayList<>();
    @SerializedName("checkedat") @Param(description = "Inspection time") public Date checkedat = new Date();
    @SerializedName("revision") @Param(description = "Source revision for submission revalidation") public String revision;
    public VmCreationSourceResponse() { setObjectName("creationsource"); }
    public void reject(String reason) { if (!reasoncodes.contains(reason)) { reasoncodes.add(reason); } allowed = false; }
}
