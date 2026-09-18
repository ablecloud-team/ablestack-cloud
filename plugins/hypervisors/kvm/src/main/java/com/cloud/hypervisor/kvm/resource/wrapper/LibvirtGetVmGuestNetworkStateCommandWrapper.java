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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.cloudstack.utils.qemu.QemuCommand;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.DomainInfo.DomainState;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVmGuestNetworkStateAnswer;
import com.cloud.agent.api.GetVmGuestNetworkStateCommand;
import com.cloud.agent.api.VmGuestDnsState;
import com.cloud.agent.api.VmGuestNetworkInterface;
import com.cloud.agent.api.VmGuestNetworkSectionStatus;
import com.cloud.agent.api.VmGuestNetworkState;
import com.cloud.agent.api.VmGuestToolsInfo;
import com.cloud.agent.api.VmGuestRoute;
import com.cloud.hypervisor.kvm.resource.BoundedQgaGuestExec;
import com.cloud.hypervisor.kvm.resource.BoundedQgaGuestExec.GuestExecFailure;
import com.cloud.hypervisor.kvm.resource.BoundedQgaGuestExec.Operation;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.KvmVmOperationGuard;
import com.cloud.hypervisor.kvm.resource.KvmBoundedStats;
import com.cloud.hypervisor.kvm.resource.QemuGuestAddressRoleFallback;
import com.cloud.hypervisor.kvm.resource.QemuGuestDnsFallback;
import com.cloud.hypervisor.kvm.resource.QemuGuestDnsParser;
import com.cloud.hypervisor.kvm.resource.QemuGuestDnsParser.DnsParseResult;
import com.cloud.hypervisor.kvm.resource.QemuGuestNetworkStateParser;
import com.cloud.hypervisor.kvm.resource.QemuGuestNetworkStateParser.RouteParseResult;
import com.cloud.hypervisor.kvm.resource.QemuGuestOsFamilyResolution;
import com.cloud.hypervisor.kvm.resource.QemuGuestOsFamilyResolver;
import com.cloud.hypervisor.kvm.resource.QemuGuestRouteFallback;
import com.cloud.hypervisor.kvm.resource.QemuGuestRouteFallback.FallbackResult;
import com.cloud.hypervisor.kvm.resource.QemuGuestToolsSnapshot;
import com.cloud.hypervisor.kvm.resource.QemuGuestToolsSnapshot.Section;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

@ResourceWrapper(handles = GetVmGuestNetworkStateCommand.class)
public final class LibvirtGetVmGuestNetworkStateCommandWrapper
        extends CommandWrapper<GetVmGuestNetworkStateCommand, Answer, LibvirtComputingResource> {
    private static final String INTERFACES_SECTION = "interfaces";
    private static final String DNS_SECTION = "dns";
    private static final String ROUTES_SECTION = "routes";
    private static final String READINESS_SECTION = "readiness";
    private static final int MAX_SECTION_DETAILS_LENGTH = 255;

    private final QemuGuestNetworkStateParser parser;
    private final QemuGuestRouteFallback routeFallback;
    private final QemuGuestDnsFallback dnsFallback;
    private final QemuGuestAddressRoleFallback addressRoleFallback;
    private final QemuGuestOsFamilyResolver osFamilyResolver;
    private final BoundedQgaGuestExec boundedGuestExec;

    public LibvirtGetVmGuestNetworkStateCommandWrapper() {
        this(new QemuGuestNetworkStateParser(), null, null, null);
    }

    LibvirtGetVmGuestNetworkStateCommandWrapper(QemuGuestNetworkStateParser parser) {
        this(parser, null, null, null);
    }

    LibvirtGetVmGuestNetworkStateCommandWrapper(QemuGuestNetworkStateParser parser,
            QemuGuestRouteFallback routeFallback) {
        this(parser, routeFallback, null, null);
    }

    LibvirtGetVmGuestNetworkStateCommandWrapper(QemuGuestNetworkStateParser parser,
            QemuGuestRouteFallback routeFallback, QemuGuestDnsFallback dnsFallback) {
        this(parser, routeFallback, dnsFallback, null);
    }

    LibvirtGetVmGuestNetworkStateCommandWrapper(QemuGuestNetworkStateParser parser,
            QemuGuestRouteFallback routeFallback, QemuGuestDnsFallback dnsFallback,
            QemuGuestAddressRoleFallback addressRoleFallback) {
        this.parser = parser;
        this.routeFallback = routeFallback == null ? new QemuGuestRouteFallback(parser) : routeFallback;
        this.dnsFallback = dnsFallback == null
                ? new QemuGuestDnsFallback(new QemuGuestDnsParser()) : dnsFallback;
        this.addressRoleFallback = addressRoleFallback == null
                ? new QemuGuestAddressRoleFallback() : addressRoleFallback;
        this.osFamilyResolver = new QemuGuestOsFamilyResolver();
        this.boundedGuestExec = new BoundedQgaGuestExec();
    }

    @Override
    public Answer execute(GetVmGuestNetworkStateCommand command, LibvirtComputingResource resource) {
        Map<String, VmGuestNetworkState> states = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();

        for (String vmName : command.getVmNames()) {
            try {
                Connect conn = resource.getLibvirtUtilitiesHelper().getConnectionByVmName(vmName);
                Map<String, String> localErrors = new LinkedHashMap<>();
                Map<String, VmGuestNetworkState> completed = KvmVmOperationGuard.collect(conn, vmName, () -> {
                    Map<String, VmGuestNetworkState> localStates = new LinkedHashMap<>();
                    collectVmState(command, resource, vmName, localStates, localErrors);
                    return localStates;
                });
                if (completed != null) { states.putAll(completed); errors.putAll(localErrors); }
                else errors.put(vmName, "Monitoring deferred: operation active, unknown, or collection budget exceeded");
            } catch (Exception e) { errors.put(vmName, "Monitoring deferred: connection unavailable"); }
        }
        return new GetVmGuestNetworkStateAnswer(command, states, errors);
    }

    private void collectVmState(GetVmGuestNetworkStateCommand command, LibvirtComputingResource resource,
            String vmName, Map<String, VmGuestNetworkState> states, Map<String, String> errors) {
        VmGuestNetworkState state = new VmGuestNetworkState(vmName);
        state.setObservedAt(System.currentTimeMillis());
        state.putSectionStatus(DNS_SECTION, new VmGuestNetworkSectionStatus("NOT_DUE"));
        state.putSectionStatus(INTERFACES_SECTION, new VmGuestNetworkSectionStatus("NOT_DUE"));
        state.putSectionStatus(ROUTES_SECTION, new VmGuestNetworkSectionStatus("NOT_DUE"));
        state.putSectionStatus(READINESS_SECTION, new VmGuestNetworkSectionStatus("NOT_DUE"));
        state.setCollectorBuildId(resolveCollectorBuildId());
        state.setCollectorHostId(command.getCollectorHostId());
        states.put(vmName, state);
        boolean collectInterfaces = command.shouldCollectInterfaces(vmName);
        boolean collectRoutes = command.shouldCollectRoutes(vmName);
        boolean collectDns = command.shouldCollectDns(vmName);
        boolean collectReadiness = command.shouldCollectReadiness(vmName);
        long collectionStarted = System.nanoTime();
        long interfacesElapsedMs = -1L;
        long routesElapsedMs = -1L;
        long dnsElapsedMs = -1L;
        if (!collectInterfaces && !collectRoutes && !collectDns && !collectReadiness) {
            state.setStatus("OK");
            return;
        }

        Domain domain = null;
        try {
            LibvirtUtilitiesHelper helper = resource.getLibvirtUtilitiesHelper();
            Connect connection = helper.getConnectionByVmName(vmName);
            domain = resource.getDomain(connection, vmName);
            if (domain == null) {
                recordFailure(state, errors, vmName, collectInterfaces, collectRoutes, collectDns,
                        collectReadiness,
                        "VM domain was not found");
                return;
            }
            DomainState domainState = KvmBoundedStats.info(domain).state;
            if (domainState != DomainState.VIR_DOMAIN_RUNNING) {
                recordFailure(state, errors, vmName, collectInterfaces, collectRoutes, collectDns,
                        collectReadiness,
                        "VM is not running: " + domainState);
                return;
            }

            boolean interfaceCapabilityEnabled = collectInterfaces
                    && command.hasCachedInterfaceCapability(vmName);
            boolean capabilitiesRequired = collectRoutes
                    || collectReadiness
                    || (collectDns && command.isExecFallbackEnabled())
                    || (collectInterfaces && !interfaceCapabilityEnabled);
            if (capabilitiesRequired) {
                String guestInfo = KvmVmOperationGuard.guestCommand(domain,
                        QemuCommand.buildQemuCommand(QemuCommand.AGENT_INFO, null), command.getTimeoutSeconds());
                interfaceCapabilityEnabled = parser.parseCapabilities(guestInfo, state);
                state.setAgentConnected(true);
            }
            state.setCapabilityHash(capabilityHash(state.getCapabilities()));

            GuestContext guestContext = new GuestContext();
            if (collectInterfaces) {
                long sectionStarted = System.nanoTime();
                collectInterfaces(command, domain, vmName, state, interfaceCapabilityEnabled,
                        guestContext);
                interfacesElapsedMs = elapsedMillis(sectionStarted);
            }
            if (collectRoutes) {
                long sectionStarted = System.nanoTime();
                collectRoutes(command, domain, state, guestContext);
                routesElapsedMs = elapsedMillis(sectionStarted);
            }
            if (collectDns) {
                long sectionStarted = System.nanoTime();
                collectDns(command, domain, state, guestContext);
                dnsElapsedMs = elapsedMillis(sectionStarted);
            }
            if (collectReadiness) {
                collectReadiness(command, domain, state, guestContext);
            }
            updateOverallStatus(state, collectInterfaces, collectRoutes, collectDns);
            if ("UNAVAILABLE".equals(state.getStatus())) {
                errors.put(vmName, "All requested guest network sections are unavailable");
            }
        } catch (Exception e) {
            recordFailure(state, errors, vmName, collectInterfaces, collectRoutes, collectDns,
                    collectReadiness,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            logger.debug("Unable to collect isolated guest network state for VM [{}]", vmName, e);
        } finally {
            logger.debug("Guest network collection metrics for VM [{}]: status={}, totalMs={}, "
                            + "interfacesMs={}, routesMs={}, dnsMs={}",
                    vmName, state.getStatus(), elapsedMillis(collectionStarted),
                    interfacesElapsedMs, routesElapsedMs, dnsElapsedMs);
            freeDomain(domain, vmName);
        }
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void collectInterfaces(GetVmGuestNetworkStateCommand command, Domain domain,
            String vmName, VmGuestNetworkState state, boolean capabilityEnabled,
            GuestContext guestContext) {
        if (!capabilityEnabled) {
            state.putSectionStatus(INTERFACES_SECTION,
                    new VmGuestNetworkSectionStatus("UNSUPPORTED",
                            "QGA interface command is disabled or unavailable"));
            return;
        }
        try {
            String interfacesJson = KvmVmOperationGuard.guestCommand(domain,
                    QemuCommand.buildQemuCommand(QemuCommand.AGENT_NETWORK_GET_INTERFACES, null),
                    command.getTimeoutSeconds());
            state.setAgentConnected(true);
            List<VmGuestNetworkInterface> interfaces = parser.parseInterfaces(
                    interfacesJson, command.getCloudNicIdsForVm(vmName));
            state.setInterfaces(interfaces);
            String status = interfaces.isEmpty() ? "EMPTY" : "OK";
            String details = "guest-network-get-interfaces";
            if (addressRoleFallback.markSingleAddress(interfaces)) {
                details += "; QGA single eligible address";
            } else if (addressRoleFallback.requiresResolution(interfaces)) {
                if (!command.isExecFallbackEnabled()) {
                    details += "; address role enrichment disabled";
                } else {
                    AddressRoleResolution resolution = resolveAddressRoles(
                            command, domain, state, guestContext, interfaces);
                    if (resolution.resolved) {
                        details += "; " + resolution.details;
                    } else {
                        status = "PARTIAL";
                        details += "; address roles unavailable: " + resolution.details;
                    }
                }
            }
            state.putSectionStatus(INTERFACES_SECTION,
                    new VmGuestNetworkSectionStatus(status, details));
        } catch (Exception e) {
            state.putSectionStatus(INTERFACES_SECTION,
                    new VmGuestNetworkSectionStatus("UNAVAILABLE", message(e)));
        }
    }

    private AddressRoleResolution resolveAddressRoles(GetVmGuestNetworkStateCommand command,
            Domain domain, VmGuestNetworkState state, GuestContext guestContext,
            List<VmGuestNetworkInterface> interfaces) {
        if (!command.isExecFallbackEnabled()) {
            return AddressRoleResolution.unavailable("guest-exec fallback is disabled");
        }
        try {
            if (!state.getCapabilities().containsKey(QemuCommand.AGENT_EXEC)
                    || !state.getCapabilities().containsKey(QemuCommand.AGENT_GET_OSINFO)) {
                String guestInfo = KvmVmOperationGuard.guestCommand(domain,
                        QemuCommand.buildQemuCommand(QemuCommand.AGENT_INFO, null),
                        command.getTimeoutSeconds());
                parser.parseCapabilities(guestInfo, state);
                state.setAgentConnected(true);
            }
            boolean guestExec = Boolean.TRUE.equals(
                    state.getCapabilities().get(QemuCommand.AGENT_EXEC));
            boolean osInfo = Boolean.TRUE.equals(
                    state.getCapabilities().get(QemuCommand.AGENT_GET_OSINFO));
            if (!guestExec || !osInfo) {
                return AddressRoleResolution.unavailable(
                        "QGA guest-exec or OS information capability is unavailable");
            }
            QemuGuestToolsSnapshot helper = getHelperSnapshot(command, domain, state, guestContext);
            if (helper != null) {
                helper.enrichAddressRoles(interfaces);
                Section section = helper.section("addresses");
                if (section != null && "OK".equals(section.getStatus())) {
                    return AddressRoleResolution.resolved("ablestack guest tools helper");
                }
            }
            String source = addressRoleFallback.collect(
                    (request, timeout) -> KvmVmOperationGuard.guestCommand(domain, request, timeout),
                    getOsFamily(command, domain, guestContext), interfaces,
                    command.getTimeoutSeconds(), command.getMaxExecOutputBytes());
            state.setAgentConnected(true);
            return AddressRoleResolution.resolved(source);
        } catch (Exception e) {
            return AddressRoleResolution.unavailable(message(e));
        }
    }

    private void collectRoutes(GetVmGuestNetworkStateCommand command, Domain domain,
            VmGuestNetworkState state, GuestContext guestContext) {
        QemuGuestToolsSnapshot helper = getHelperSnapshot(command, domain, state, guestContext);
        Section helperSection = helper == null ? null : helper.section("routes");
        if (helperSection != null && "OK".equals(helperSection.getStatus())) {
            List<VmGuestRoute> routes = helper.toRoutes();
            state.setRoutes(routes);
            state.putSectionStatus(ROUTES_SECTION,
                    observedSection(routes.isEmpty() ? "EMPTY" : "OK",
                            helperSection.getSource(), null, "ablestack guest tools helper"));
            return;
        }
        boolean standardRoute = Boolean.TRUE.equals(
                state.getCapabilities().get(QemuCommand.AGENT_NETWORK_GET_ROUTE));
        Exception standardFailure = null;
        if (standardRoute) {
            try {
                String routesJson = KvmVmOperationGuard.guestCommand(domain,
                        QemuCommand.buildQemuCommand(QemuCommand.AGENT_NETWORK_GET_ROUTE, null),
                        command.getTimeoutSeconds());
                ensureOutputLimit(routesJson, command.getMaxExecOutputBytes());
                RouteParseResult result = parser.parseRoutes(routesJson);
                state.setAgentConnected(true);
                state.setRoutes(result.getRoutes());
                state.putSectionStatus(ROUTES_SECTION,
                        sectionStatus(result.isTruncated() ? "PARTIAL"
                                        : result.getRoutes().isEmpty() ? "EMPTY" : "OK",
                                "guest-network-get-route", result.isTruncated(), result.getOriginalCount()));
                return;
            } catch (Exception e) {
                standardFailure = e;
            }
        }

        boolean guestExec = Boolean.TRUE.equals(state.getCapabilities().get(QemuCommand.AGENT_EXEC));
        boolean osInfo = Boolean.TRUE.equals(state.getCapabilities().get(QemuCommand.AGENT_GET_OSINFO));
        if (!command.isExecFallbackEnabled() || !guestExec || !osInfo) {
            state.putSectionStatus(ROUTES_SECTION,
                    new VmGuestNetworkSectionStatus(standardFailure == null ? "UNSUPPORTED" : "UNAVAILABLE",
                            standardFailure != null
                                    ? "Standard QGA route collection failed: " + message(standardFailure)
                                    : command.isExecFallbackEnabled()
                                    ? "QGA route, guest-exec, or OS information capability is unavailable"
                                    : "QGA route command is unavailable and guest-exec fallback is disabled"));
            return;
        }
        try {
            FallbackResult result = routeFallback.collect(
                    (request, timeout) -> KvmVmOperationGuard.guestCommand(domain, request, timeout),
                    getOsFamily(command, domain, guestContext),
                    command.getTimeoutSeconds(), command.getMaxExecOutputBytes());
            state.setAgentConnected(true);
            state.setRoutes(result.getRoutes());
            state.putSectionStatus(ROUTES_SECTION,
                    sectionStatus(result.isTruncated() ? "PARTIAL"
                                    : result.getRoutes().isEmpty() ? "EMPTY" : "OK",
                            result.getSource(), result.isTruncated(), result.getOriginalCount()));
        } catch (UnsupportedOperationException e) {
            state.putSectionStatus(ROUTES_SECTION,
                    new VmGuestNetworkSectionStatus("UNSUPPORTED", message(e)));
        } catch (Exception e) {
            state.putSectionStatus(ROUTES_SECTION,
                    new VmGuestNetworkSectionStatus("UNAVAILABLE", message(e)));
        }
    }

    private void collectDns(GetVmGuestNetworkStateCommand command, Domain domain,
            VmGuestNetworkState state, GuestContext guestContext) {
        QemuGuestToolsSnapshot helper = getHelperSnapshot(command, domain, state, guestContext);
        Section helperSection = helper == null ? null : helper.section("dns");
        if (helperSection != null && "OK".equals(helperSection.getStatus())) {
            VmGuestDnsState dns = helper.toDns();
            state.setDns(dns);
            state.putSectionStatus(DNS_SECTION,
                    observedSection(dns.getServers().isEmpty() && dns.getSearchDomains().isEmpty()
                                    ? "EMPTY" : "OK",
                            helperSection.getSource(), null, "ablestack guest tools helper"));
            return;
        }
        boolean guestExec = Boolean.TRUE.equals(state.getCapabilities().get(QemuCommand.AGENT_EXEC));
        boolean osInfo = Boolean.TRUE.equals(state.getCapabilities().get(QemuCommand.AGENT_GET_OSINFO));
        if (!command.isExecFallbackEnabled() || !guestExec || !osInfo) {
            state.putSectionStatus(DNS_SECTION,
                    new VmGuestNetworkSectionStatus("UNSUPPORTED",
                            command.isExecFallbackEnabled()
                                    ? "QGA guest-exec or OS information capability is unavailable"
                                    : "DNS guest-exec fallback is disabled"));
            return;
        }
        try {
            DnsParseResult result = dnsFallback.collect(
                    (request, timeout) -> KvmVmOperationGuard.guestCommand(domain, request, timeout),
                    getOsFamily(command, domain, guestContext),
                    command.getTimeoutSeconds(), command.getMaxExecOutputBytes());
            VmGuestDnsState dns = result.getState();
            state.setAgentConnected(true);
            state.setDns(dns);
            state.putSectionStatus(DNS_SECTION,
                    dnsSectionStatus(result.isTruncated() ? "PARTIAL"
                                    : result.isEmpty() ? "EMPTY" : "OK",
                            dns.getSource(), result.isTruncated(), result.getOriginalCount()));
        } catch (UnsupportedOperationException e) {
            state.putSectionStatus(DNS_SECTION,
                    new VmGuestNetworkSectionStatus("UNSUPPORTED", message(e)));
        } catch (Exception e) {
            state.putSectionStatus(DNS_SECTION,
                    new VmGuestNetworkSectionStatus("UNAVAILABLE", message(e)));
        }
    }

    private void collectReadiness(GetVmGuestNetworkStateCommand command, Domain domain,
            VmGuestNetworkState state, GuestContext context) {
        QemuGuestToolsSnapshot helper = getHelperSnapshot(command, domain, state, context);
        VmGuestToolsInfo info = state.getGuestTools();
        if (helper != null) {
            state.putSectionStatus(READINESS_SECTION,
                    observedSection("OK", "ablestack-guest-tools-helper", null,
                            "Guest network observation profile is ready"));
        } else {
            String errorCode = info == null ? "HELPER_NOT_INSTALLED" : info.getErrorCode();
            state.putSectionStatus(READINESS_SECTION,
                    observedSection("UNAVAILABLE", "ablestack-guest-tools-helper",
                            errorCode, "Guest tools helper is not ready"));
        }
    }

    private QemuGuestToolsSnapshot getHelperSnapshot(GetVmGuestNetworkStateCommand command,
            Domain domain, VmGuestNetworkState state, GuestContext context) {
        if (!command.isPreferGuestToolsHelper() || context.helperAttempted) {
            return context.helperSnapshot;
        }
        context.helperAttempted = true;
        VmGuestToolsInfo info = new VmGuestToolsInfo();
        info.setQgaPolicyMode("FULL");
        info.setReadinessStatus("UNKNOWN");
        state.setGuestTools(info);
        if (!Boolean.TRUE.equals(state.getCapabilities().get(QemuCommand.AGENT_EXEC))) {
            info.setReadinessStatus("POLICY_NOT_READY");
            info.setErrorCode("QGA_RPC_DISABLED");
            return null;
        }
        try {
            String output = boundedGuestExec.execute(
                    (request, timeout) -> KvmVmOperationGuard.guestCommand(domain, request, timeout),
                    Operation.ABLESTACK_NETWORK_SNAPSHOT,
                    command.getTimeoutSeconds(), command.getMaxExecOutputBytes());
            context.helperSnapshot = QemuGuestToolsSnapshot.parse(output);
            state.setGuestTools(context.helperSnapshot.toInfo());
            state.setAgentConnected(true);
            return context.helperSnapshot;
        } catch (GuestExecFailure e) {
            info.setReadinessStatus("HELPER_NOT_FOUND".equals(e.getErrorCode())
                    || "HELPER_NOT_INSTALLED".equals(e.getErrorCode())
                    ? "TOOLS_NOT_INSTALLED" : "SECURITY_POLICY_NOT_READY");
            info.setErrorCode(e.getErrorCode());
            context.helperError = message(e);
        } catch (RuntimeException e) {
            info.setReadinessStatus("HELPER_SCHEMA_UNSUPPORTED");
            info.setErrorCode("HELPER_SCHEMA_UNSUPPORTED");
            context.helperError = message(e);
        } catch (Exception e) {
            info.setReadinessStatus("UNKNOWN");
            info.setErrorCode("EXEC_EXIT_NONZERO");
            context.helperError = message(e);
        }
        return null;
    }

    private VmGuestNetworkSectionStatus observedSection(String status, String source,
            String errorCode, String details) {
        long now = System.currentTimeMillis();
        VmGuestNetworkSectionStatus section = new VmGuestNetworkSectionStatus(status, details);
        section.setSource(source);
        section.setErrorCode(errorCode);
        section.setAttemptedAt(now);
        if ("OK".equals(status) || "EMPTY".equals(status)) {
            section.setSucceededAt(now);
        }
        return section;
    }

    private String resolveCollectorBuildId() {
        Package runtimePackage = LibvirtGetVmGuestNetworkStateCommandWrapper.class.getPackage();
        String version = runtimePackage == null ? null : runtimePackage.getImplementationVersion();
        return version == null ? "guest-network-v3" : version;
    }

    private String capabilityHash(Map<String, Boolean> capabilities) {
        try {
            List<String> enabled = new ArrayList<>();
            if (capabilities != null) {
                capabilities.forEach((name, value) -> {
                    if (Boolean.TRUE.equals(value)) {
                        enabled.add(name);
                    }
                });
            }
            Collections.sort(enabled);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", enabled).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                value.append(String.format("%02x", item & 0xff));
            }
            return value.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private QemuGuestOsFamilyResolution getOsFamily(GetVmGuestNetworkStateCommand command, Domain domain,
            GuestContext context) throws LibvirtException {
        if (context.osFamily == null) {
            String osInfoJson = KvmVmOperationGuard.guestCommand(domain,
                    QemuCommand.buildQemuCommand(QemuCommand.AGENT_GET_OSINFO, null),
                    command.getTimeoutSeconds());
            context.osFamily = osFamilyResolver.resolve(parser.parseOsInfo(osInfoJson));
            logger.debug("Resolved QGA guest OS family [{}] from [{}]",
                    context.osFamily.getFamily(), context.osFamily.getSource());
        }
        return context.osFamily;
    }

    private VmGuestNetworkSectionStatus sectionStatus(String status, String details,
            boolean truncated, int originalCount) {
        String sectionDetails = truncated
                ? details + "; truncated to " + QemuGuestNetworkStateParser.MAX_ROUTES + " routes"
                : details;
        VmGuestNetworkSectionStatus section = new VmGuestNetworkSectionStatus(status, sectionDetails);
        section.setTruncated(truncated);
        section.setOriginalCount(originalCount);
        return section;
    }

    private VmGuestNetworkSectionStatus dnsSectionStatus(String status, String details,
            boolean truncated, int originalCount) {
        String sectionDetails = truncated
                ? details + "; DNS records truncated to bounded limits"
                : details;
        VmGuestNetworkSectionStatus section = new VmGuestNetworkSectionStatus(status, sectionDetails);
        section.setTruncated(truncated);
        section.setOriginalCount(originalCount);
        return section;
    }

    private void updateOverallStatus(VmGuestNetworkState state,
            boolean collectInterfaces, boolean collectRoutes, boolean collectDns) {
        int success = 0;
        int partial = 0;
        int unavailable = 0;
        int requested = 0;
        if (collectInterfaces) {
            requested++;
            String status = state.getSectionStatuses().get(INTERFACES_SECTION).getStatus();
            success += isSuccess(status) ? 1 : 0;
            partial += "PARTIAL".equals(status) ? 1 : 0;
            unavailable += "UNAVAILABLE".equals(status) ? 1 : 0;
        }
        if (collectRoutes) {
            requested++;
            String status = state.getSectionStatuses().get(ROUTES_SECTION).getStatus();
            success += isSuccess(status) ? 1 : 0;
            partial += "PARTIAL".equals(status) ? 1 : 0;
            unavailable += "UNAVAILABLE".equals(status) ? 1 : 0;
        }
        if (collectDns) {
            requested++;
            String status = state.getSectionStatuses().get(DNS_SECTION).getStatus();
            success += isSuccess(status) ? 1 : 0;
            partial += "PARTIAL".equals(status) ? 1 : 0;
            unavailable += "UNAVAILABLE".equals(status) ? 1 : 0;
        }
        if (partial > 0) {
            state.setStatus("PARTIAL");
        } else if (success == requested) {
            state.setStatus("OK");
        } else if (success > 0) {
            state.setStatus("PARTIAL");
        } else if (unavailable > 0) {
            state.setStatus("UNAVAILABLE");
        } else {
            state.setStatus("UNSUPPORTED");
        }
    }

    private boolean isSuccess(String status) {
        return "OK".equals(status) || "EMPTY".equals(status);
    }

    private void ensureOutputLimit(String value, int maxBytes) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("QGA route output exceeds limit");
        }
    }

    private String message(Exception e) {
        String details = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (details.length() <= MAX_SECTION_DETAILS_LENGTH) {
            return details;
        }
        return details.substring(0, MAX_SECTION_DETAILS_LENGTH - 3) + "...";
    }

    private void recordFailure(VmGuestNetworkState state, Map<String, String> errors,
            String vmName, boolean collectInterfaces, boolean collectRoutes,
            boolean collectDns, boolean collectReadiness, String details) {
        state.setStatus("UNAVAILABLE");
        if (collectInterfaces) {
            state.putSectionStatus(INTERFACES_SECTION,
                    new VmGuestNetworkSectionStatus("UNAVAILABLE", details));
        }
        if (collectRoutes) {
            state.putSectionStatus(ROUTES_SECTION,
                    new VmGuestNetworkSectionStatus("UNAVAILABLE", details));
        }
        if (collectDns) {
            state.putSectionStatus(DNS_SECTION,
                    new VmGuestNetworkSectionStatus("UNAVAILABLE", details));
        }
        if (collectReadiness) {
            state.putSectionStatus(READINESS_SECTION,
                    observedSection("UNAVAILABLE", "qga", "QGA_NOT_CONNECTED", details));
        }
        errors.put(vmName, details);
    }

    private static final class GuestContext {
        private QemuGuestOsFamilyResolution osFamily;
        private boolean helperAttempted;
        private QemuGuestToolsSnapshot helperSnapshot;
        private String helperError;
    }

    private static final class AddressRoleResolution {
        private final boolean resolved;
        private final String details;

        private AddressRoleResolution(boolean resolved, String details) {
            this.resolved = resolved;
            this.details = details;
        }

        private static AddressRoleResolution resolved(String details) {
            return new AddressRoleResolution(true, details);
        }

        private static AddressRoleResolution unavailable(String details) {
            return new AddressRoleResolution(false, details);
        }
    }

    private void freeDomain(Domain domain, String vmName) {
        if (domain == null) {
            return;
        }
        try {
            domain.free();
        } catch (LibvirtException e) {
            logger.trace("Ignoring domain free failure for VM [{}]", vmName, e);
        }
    }
}
