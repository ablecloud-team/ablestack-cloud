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
package com.cloud.ssv;

import java.util.Date;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.Displayable;
import org.apache.cloudstack.api.Identity;
import org.apache.cloudstack.api.InternalIdentity;

import com.cloud.utils.fsm.StateMachine2;

/**
 * SSV describes the properties of a Shared Storage VM
 * StateMachine maintains its states.
 *
 */
public interface SSV extends ControlledEntity, com.cloud.utils.fsm.StateObject<SSV.State>, Identity, InternalIdentity, Displayable {

    enum Event {
        StartRequested,
        StopRequested,
        DestroyRequested,
        RecoveryRequested,
        AddVolumeRequested,
        AddNetworkRequested,
        UpgradeRequested,
        OperationSucceeded,
        OperationFailed,
        CreateFailed,
        FaultsDetected;
    }

    enum State {
        Created("Initial State of Shared Storage VM. At this state its just a logical/DB entry with no resources consumed"),
        Starting("Resources needed for Shared Storage VM are being provisioned"),
        Running("Necessary resources are provisioned and Shared Storage VM is in operational ready state to launch Desktop"),
        Stopping("Resources for the Shared Storage VM are being destroyed"),
        Stopped("All resources for the Shared Storage VM are destroyed, Shared Storage VM may still have ephemeral resource like persistent volumes provisioned"),
        Alert("State to represent Shared Storage VM which are not in expected desired state (operationally in active control place, stopped cluster VM's etc)."),
        Recovering("State in which Shared Storage VM is recovering from alert state"),
        Destroyed("End state of Shared Storage VM in which all resources are destroyed, cluster will not be usable further"),
        Destroying("State in which resources for the Shared Storage VM is getting cleaned up or yet to be cleaned up by garbage collector"),
        Error("State of the failed to create Shared Storage VM");

        protected static final StateMachine2<State, SSV.Event, SSV> s_fsm = new StateMachine2<State, SSV.Event, SSV>();

        public static StateMachine2<State, SSV.Event, SSV> getStateMachine() { return s_fsm; }

        static {
            s_fsm.addTransition(State.Created, Event.StartRequested, State.Starting);

            s_fsm.addTransition(State.Starting, Event.OperationSucceeded, State.Running);
            s_fsm.addTransition(State.Starting, Event.OperationFailed, State.Alert);
            s_fsm.addTransition(State.Starting, Event.CreateFailed, State.Error);
            s_fsm.addTransition(State.Starting, Event.StopRequested, State.Stopping);

            s_fsm.addTransition(State.Running, Event.StopRequested, State.Stopping);
            s_fsm.addTransition(State.Alert, Event.StopRequested, State.Stopping);
            s_fsm.addTransition(State.Stopping, Event.OperationSucceeded, State.Stopped);
            s_fsm.addTransition(State.Stopping, Event.OperationFailed, State.Alert);

            s_fsm.addTransition(State.Stopped, Event.StartRequested, State.Starting);

            s_fsm.addTransition(State.Running, Event.FaultsDetected, State.Alert);

            s_fsm.addTransition(State.Alert, Event.RecoveryRequested, State.Recovering);
            s_fsm.addTransition(State.Recovering, Event.OperationSucceeded, State.Running);
            s_fsm.addTransition(State.Recovering, Event.OperationFailed, State.Alert);

            s_fsm.addTransition(State.Running, Event.DestroyRequested, State.Destroying);
            s_fsm.addTransition(State.Stopped, Event.DestroyRequested, State.Destroying);
            s_fsm.addTransition(State.Alert, Event.DestroyRequested, State.Destroying);
            s_fsm.addTransition(State.Error, Event.DestroyRequested, State.Destroying);

            s_fsm.addTransition(State.Destroying, Event.OperationSucceeded, State.Destroyed);

        }
        String _description;

        State(String description) {
             _description = description;
        }
    }

    long getId();
    String getName();
    String getDescription();
    long getZoneId();
    long getServiceOfferingId();
    long getTemplateId();
    long getDiskOfferingId();
    String getSsvType();
    long getDomainId();
    long getAccountId();
    @Override
    State getState();
    Date getCreated();
}
