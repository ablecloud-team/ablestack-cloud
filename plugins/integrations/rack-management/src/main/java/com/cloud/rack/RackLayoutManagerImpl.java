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

package com.cloud.rack;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.api.command.admin.rack.ListRackLayoutsCmd;
import org.apache.cloudstack.api.command.admin.rack.UpdateRackLayoutCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.RackLayoutResponse;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.utils.component.ManagerBase;

@Component
public class RackLayoutManagerImpl extends ManagerBase implements RackLayoutService {
    private static final Logger s_logger = Logger.getLogger(RackLayoutManagerImpl.class);

    @Inject
    private RackLayoutDao rackLayoutDao;

    @Inject
    private DataCenterDao dataCenterDao;

    @Override
    public ListResponse<RackLayoutResponse> listRackLayouts(ListRackLayoutsCmd cmd) {
        Long zoneId = cmd.getZoneId();
        String name = cmd.getName();

        RackLayoutVO vo = rackLayoutDao.findByZoneAndName(zoneId, name);
        List<RackLayoutResponse> responses = new ArrayList<>();

        if (vo != null) {
            responses.add(createRackLayoutResponse(vo));
        }

        ListResponse<RackLayoutResponse> listResponse = new ListResponse<>();
        listResponse.setResponses(responses);
        return listResponse;
    }

    @Override
    public RackLayoutResponse updateRackLayout(UpdateRackLayoutCmd cmd) {
        Long zoneId = cmd.getZoneId();
        String name = cmd.getName();
        String content = cmd.getContent();

        RackLayoutVO vo = rackLayoutDao.findByZoneAndName(zoneId, name);

        if (vo == null) {
            // (INSERT)
            vo = new RackLayoutVO(zoneId, name, content);
            rackLayoutDao.persist(vo);
            s_logger.debug("Created new rack layout for zone: " + zoneId + ", name: " + name);
        } else {
            // (UPDATE)
            vo.setContent(content);
            rackLayoutDao.update(vo.getId(), vo);
            s_logger.debug("Updated existing rack layout for zone: " + zoneId + ", name: " + name);
        }

        return createRackLayoutResponse(vo);
    }

    // VO 객체를 API Response 포맷으로 예쁘게 변환해 주는 헬퍼 메서드
    private RackLayoutResponse createRackLayoutResponse(RackLayoutVO vo) {
        RackLayoutResponse response = new RackLayoutResponse();
        response.setId(String.valueOf(vo.getId()));
        response.setName(vo.getName());
        response.setContent(vo.getContent());

        // 프론트엔드가 사용할 수 있도록 Zone ID를 UUID 포맷으로 변환하여 반환
        DataCenterVO zone = dataCenterDao.findById(vo.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
        }

        response.setObjectName("racklayout");
        return response;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(ListRackLayoutsCmd.class);
        cmdList.add(UpdateRackLayoutCmd.class);
        return cmdList;
    }
}