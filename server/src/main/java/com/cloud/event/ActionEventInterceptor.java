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
package com.cloud.event;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.lang3.StringUtils;

import com.cloud.user.User;
import com.cloud.user.Account;
import com.cloud.utils.component.ComponentMethodInterceptor;

public class ActionEventInterceptor implements ComponentMethodInterceptor, MethodInterceptor {

    public ActionEventInterceptor() {
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method m = invocation.getMethod();
        Object target = invocation.getThis();

        if (getActionEvents(m).size() == 0) {
            /* Look for annotation on impl class */
            m = target.getClass().getMethod(m.getName(), m.getParameterTypes());
        }

        Object interceptorData = null;

        boolean success = false;
        try {
            interceptorData = interceptStart(m, target);

            Object result = invocation.proceed();
            success = true;

            return result;
        } finally {
            if (success) {
                interceptComplete(m, target, interceptorData);
            } else {
                interceptException(m, target, interceptorData);
            }
        }
    }

    @Override
    public Object interceptStart(Method method, Object target) {
        EventVO event = null;
        for (ActionEvent actionEvent : getActionEvents(method)) {
            boolean async = actionEvent.async();
            if (async) {
                CallContext ctx = CallContext.current();

                String eventDescription = getEventDescription(actionEvent, ctx);
                Long eventResourceId = getEventResourceId(actionEvent, ctx);
                String eventResourceType = getEventResourceType(actionEvent, ctx);
                String eventType = getEventType(actionEvent, ctx);
                boolean isEventDisplayEnabled = ctx.isEventDisplayEnabled();

                ActionEventUtils.onStartedActionEventFromContext(eventType, eventDescription,
                        eventResourceId, eventResourceType, isEventDisplayEnabled);
            }
        }
        return event;
    }

    @Override
    public void interceptComplete(Method method, Object target, Object event) {
        for (ActionEvent actionEvent : getActionEvents(method)) {
            CallContext ctx = CallContext.current();
            long userId = ctx.getCallingUserId();
            long accountId = ctx.getProject() != null ? ctx.getProject().getProjectAccountId() : ctx.getCallingAccountId();    //This should be the entity owner id rather than the Calling User Account Id.
            long startEventId = ctx.getStartEventId();
            String eventDescription = getEventDescription(actionEvent, ctx);
            Long eventResourceId = getEventResourceId(actionEvent, ctx);
            String eventResourceType = getEventResourceType(actionEvent, ctx);
            String eventType = getEventType(actionEvent, ctx);
            boolean isEventDisplayEnabled = ctx.isEventDisplayEnabled();

            if (eventType.equals(""))
                return;

            if (actionEvent.create()) {
                //This start event has to be used for subsequent events of this action
                startEventId = ActionEventUtils.onCreatedActionEvent(((Long)userId == null) ? User.UID_SYSTEM : userId, ((Long)accountId == null) ? Account.ACCOUNT_ID_SYSTEM : accountId, EventVO.LEVEL_INFO, eventType,
                        isEventDisplayEnabled, "성공적으로 엔터티를 생성했습니다. : " + eventDescription,
                        eventResourceId, eventResourceType);
                ctx.setStartEventId(startEventId);
            } else {
                ActionEventUtils.onCompletedActionEvent(((Long)userId == null) ? User.UID_SYSTEM : userId, ((Long)accountId == null) ? Account.ACCOUNT_ID_SYSTEM : accountId, EventVO.LEVEL_INFO, eventType,
                        isEventDisplayEnabled, "성공적으로 완료되었습니다. : " + eventDescription,
                        eventResourceId, eventResourceType, startEventId);
            }
        }
    }

    @Override
    public void interceptException(Method method, Object target, Object event) {
        for (ActionEvent actionEvent : getActionEvents(method)) {
            CallContext ctx = CallContext.current();
            long userId = ctx.getCallingUserId();
            long accountId = ctx.getCallingAccountId();
            long startEventId = ctx.getStartEventId();
            String eventDescription = getEventDescription(actionEvent, ctx);
            Long eventResourceId = getEventResourceId(actionEvent, ctx);
            String eventResourceType = getEventResourceType(actionEvent, ctx);
            String eventType = getEventType(actionEvent, ctx);
            boolean isEventDisplayEnabled = ctx.isEventDisplayEnabled();

            if (eventType.equals(""))
                return;

            if (actionEvent.create()) {
                long eventId = ActionEventUtils.onCreatedActionEvent(((Long)userId == null) ? User.UID_SYSTEM : userId, ((Long)accountId == null) ? Account.ACCOUNT_ID_SYSTEM : accountId, EventVO.LEVEL_ERROR, eventType,
                        isEventDisplayEnabled, "엔터티를 생성하는 동안 오류가 발생했습니다. : " + eventDescription,
                        eventResourceId, eventResourceType);
                ctx.setStartEventId(eventId);
            } else {
                ActionEventUtils.onCompletedActionEvent(((Long)userId == null) ? User.UID_SYSTEM : userId, ((Long)accountId == null) ? Account.ACCOUNT_ID_SYSTEM : accountId, EventVO.LEVEL_ERROR, eventType, isEventDisplayEnabled,
                        "오류가 발생했습니다. : " + eventDescription,
                        eventResourceId, eventResourceType, startEventId);
            }
        }
    }

    @Override
    public boolean needToIntercept(Method method) {
        ActionEvent actionEvent = method.getAnnotation(ActionEvent.class);
        if (actionEvent != null) {
            return true;
        }

        ActionEvents events = method.getAnnotation(ActionEvents.class);
        if (events != null) {
            return true;
        }

        return false;
    }

    protected List<ActionEvent> getActionEvents(Method m) {
        List<ActionEvent> result = new ArrayList<ActionEvent>();

        ActionEvents events = m.getAnnotation(ActionEvents.class);

        if (events != null) {
            for (ActionEvent e : events.value()) {
                result.add(e);
            }
        }

        ActionEvent e = m.getAnnotation(ActionEvent.class);

        if (e != null) {
            result.add(e);
        }

        return result;
    }

    protected String getEventType(ActionEvent actionEvent, CallContext ctx) {
        String type = ctx.getEventType();

        return type == null ? actionEvent.eventType() : type;
    }

    protected String getEventDescription(ActionEvent actionEvent, CallContext ctx) {
        String eventDescription = ctx.getEventDescription();
        if (eventDescription == null) {
            eventDescription = actionEvent.eventDescription();
        }

        if (ctx.getEventDetails() != null) {
            eventDescription += ". " + ctx.getEventDetails();
        }

        return eventDescription;
    }

    protected Long getEventResourceId(ActionEvent actionEvent, CallContext ctx) {
        Long resourceId = ctx.getEventResourceId();
        if (resourceId != null) {
            return resourceId;
        }
        return actionEvent.resourceId() == -1? null : actionEvent.resourceId();
    }

    protected String getEventResourceType(ActionEvent actionEvent, CallContext ctx) {
        ApiCommandResourceType resourceType = ctx.getEventResourceType();
        if (resourceType != null) {
            return resourceType.toString();
        }
        return StringUtils.isEmpty(actionEvent.resourceType()) ? null : actionEvent.resourceType();
    }
}
