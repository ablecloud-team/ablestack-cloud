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
import java.io.FileInputStream;
import java.util.Properties;

import com.cloud.usage.UsageServer;
import com.cloud.utils.db.DbProperties;

/** Starts the real Usage Spring context against an explicitly named disposable S4 fixture. */
public class EuropaUsageRuntimeSmoke {
    public static void main(String[] args) {
        try {
            Properties properties = new Properties();
            try (FileInputStream input = new FileInputStream(args[0])) {
                properties.load(input);
            }
            for (String database : new String[]{"cloud", "usage"}) {
                if (!properties.getProperty("db." + database + ".host", "").startsWith("epic992-mysql-")) {
                    throw new IllegalArgumentException("Only disposable S4 fixture hosts are allowed");
                }
            }
            DbProperties.setDbProperties(properties);
            UsageServer usage = new UsageServer();
            usage.start();
            System.out.println("S4_USAGE_SPRING_START_PASS");
            if (args.length > 1 && "aggregate".equals(args[1])) {
                java.lang.reflect.Field field = UsageServer.class.getDeclaredField("appContext");
                field.setAccessible(true);
                org.springframework.context.ApplicationContext context = (org.springframework.context.ApplicationContext) field.get(usage);
                if (!context.getBean(org.apache.cloudstack.quota.QuotaManager.class).calculateQuotaUsage()) {
                    throw new AssertionError("Quota aggregation failed");
                }
                System.out.println("S4_QUOTA_AGGREGATION_RETURNED_SUCCESS");
            }
            usage.stop();
            System.exit(0);
        } catch (Exception failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
