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
package com.cloud.api;

import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@WebListener
public class ApiSessionListener implements HttpSessionListener {
    protected static Logger logger = LogManager.getLogger(ApiSessionListener.class.getName());
    private static Map<String, HttpSession> sessions = new ConcurrentHashMap<>();

    /**
     * @return the internal adminstered session count
     */
    public static long getSessionCount() {
        return sessions.size();
    }

    /**
     * @return the size of the internal {@see Map} of sessions
     */
    public static long getNumberOfSessions() {
        return sessions.size();
    }

    /**
     * 먼저 로그인된 세션 ID 목록 생성(중복 로그인 목록)
     */
    public static List<String> listExistSessionIds(String username, String newSessionId) {
        List<String> doubleLoginSessionIds = new ArrayList<String>();
        for (String key : sessions.keySet()) {
            HttpSession ses = sessions.get(key);
            if (ses != null && ses.getAttribute("username") != null && ses.getAttribute("username").toString().equals(username) && !newSessionId.equals(key.toString())) {
                doubleLoginSessionIds.add(key.toString());
            }
        }
        return doubleLoginSessionIds;
    }

    /**
     * 먼저 로그인된 세션 목록 제거
     */
    public static void deleteSessionIds(List<String> arr) {
        if (arr.size() > 0) {
            for (String id : arr) {
                sessions.get(id).invalidate();
                sessions.remove(id);
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Sessions count: " + getSessionCount());
        }
    }

    public void sessionCreated(HttpSessionEvent event) {
        if (logger.isDebugEnabled()) {
            logger.debug("Session created by Id : " + event.getSession().getId() + " , session: " + event.getSession().toString() + " , source: " + event.getSource().toString() + " , event: " + event.toString());
        }
        synchronized (this) {
            HttpSession session = event.getSession();
            sessions.put(session.getId(), event.getSession());
        }
        if (logger.isTraceEnabled()) {
            logger.trace("Sessions count: " + getSessionCount());
        }
    }

    public void sessionDestroyed(HttpSessionEvent event) {
        if (logger.isDebugEnabled()) {
            logger.debug("Session destroyed by Id : " + event.getSession().getId() + " , session: " + event.getSession().toString() + " , source: " + event.getSource().toString() + " , event: " + event.toString());
        }
        synchronized (this) {
            sessions.remove(event.getSession().getId());
        }
        if (logger.isTraceEnabled()) {
            logger.trace("Sessions count: " + getSessionCount());
        }
    }
}
