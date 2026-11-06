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

package org.apache.cloudstack.backup;

import java.util.ArrayList;
import java.util.List;

/** Path-only synchronization contract. Credentials and unrelated Host configuration never leave the Host. */
public class ThirdPartyBackupStagingSync {
    public static class Request {
        public String action;
        public String hostName;
        public ThirdPartyBackupStagingService.Snapshot configuration;
        public List<String> providers = new ArrayList<>();
        public String fingerprint;
    }

    public static class Plan {
        public int version = 1;
        public String hostName;
        public String rootPath;
        public String fingerprint;
        public boolean changed;
        public List<Binding> bindings = new ArrayList<>();
    }

    public static class Binding {
        public String provider;
        public String name;
        public boolean nameIsExact = true;
        public String previousPath;
        public String path;
        public boolean managed = true;
    }
}
