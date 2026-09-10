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

import java.util.List;
import java.util.Map;

import com.cloud.utils.Pair;

public interface DrPlanService {
    DrPlanVO createPlan(DrPlanVO plan);

    DrPlanVO updatePlan(long planId, DrPlanVO update);

    DrPlanVO getPlan(long planId);

    List<DrPlanVO> listPlans();

    Pair<List<DrPlanVO>, Integer> searchPlans(DrPlanSearchCriteria criteria);

    DrPlanVO enablePlan(long planId);

    DrPlanVO disablePlan(long planId);

    boolean deletePlan(long planId);
    boolean deletePlan(long planId, boolean force);

    Map<String, Boolean> getActionEligibility(long planId);

    Map<String, DrActionAvailability> getActionAvailability(long planId);

    DrPlanActionEvaluation getActionEvaluation(long planId);

    DrPlanActionEvaluation getDatabaseActionEvaluation(long planId);
}
