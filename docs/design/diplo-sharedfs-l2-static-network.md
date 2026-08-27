# SharedFS L2 static network without ConfigDrive

## Goal

Allow a Storage Service SharedFS VM attached to an L2 guest network to use an
operator-supplied IPv4 address even when the network has neither DHCP nor
ConfigDrive. The change is scoped to SharedFS VMs and must not alter the boot or
network behavior of routers, consoles, secondary-storage VMs, or ordinary user
VMs.

## Behavioral contract

| Area | DHCP mode | L2 static mode |
|---|---|---|
| Existing behavior | Unchanged | New opt-in path |
| UserData requirement | Preserved | Not required |
| Address source | Existing network services | Required `ipcidr` in IPv4/prefix form; optional `gateway`, `dns1`, and `dns2` |
| Cloud DB NIC address | Existing allocation | Requested address is reserved at VM deployment |
| Guest configuration | Existing template path | Fixed QGA operation after VM start |
| Reboot persistence | Existing template path | SharedFS-only oneshot systemd unit inside that VM |
| SystemVM template | Unchanged | Unchanged |

## API and persistence

`createSharedFileSystem` gains optional `networkmode`, `ipcidr`,
`gateway`, `dns1`, and `dns2` parameters. Gateway and DNS values are optional.
`ipcidr` uses host-address/prefix notation such as `10.10.1.211/24`.
`networkmode` is `DHCP` by default or
`STATIC` for the new path. The management server calculates the network address
from any valid prefix from `/0` through `/32`, then persists the host address and
the calculated network CIDR on `shared_filesystem`,
exposed by `listSharedFileSystems`, and included in
`shared_filesystem_view`. Persisting desired state before deployment lets
start, restart, redeploy, and diagnostics use the same values without changing
the `SharedFSLifeCycle` interface signature.

## Server validation

Static mode is accepted only when the selected network has guest type `L2`.
The server validates all supplied IPv4 values, requires the address and any
supplied gateway to be in the CIDR, rejects network/broadcast addresses, and rejects an address
already assigned to a NIC in the same network. DHCP mode keeps the existing
UserData/ConfigDrive capability check.

The lifecycle receives the requested IPv4 through the persisted SharedFS
entity and passes it to `createAdvancedVirtualMachine`. This reserves the
address and records it on the VM NIC while keeping the existing lifecycle
method signature intact.

## QGA application path

The host agent recognizes one exact operation,
`configure-sharedfs-static-network`. Other Storage Service operations continue
to invoke `/usr/local/bin/ablestack-storagectl` unchanged.

The fixed operation accepts only JSON data produced by the management server.
It installs a SharedFS-specific network state file, helper, and oneshot unit in
the guest. The helper resolves the interface by its VM NIC MAC address, applies
the IPv4 address, conditionally applies a default route and DNS resolver entries,
and verifies the resulting configured state. The unit runs on
boot only when the SharedFS state file exists. No arbitrary command or script
is accepted from the API.

The management server retries dispatch while QGA becomes ready. Failure to
apply or verify the requested network fails the SharedFS deployment instead of
reporting a ready but unreachable service.

## UI

The create dialog identifies L2 networks from the `listNetworks.type` field.
For L2 networks with UserData, DHCP is the default and static remains selectable.
For L2 networks without UserData/ConfigDrive, static mode is selected automatically
and DHCP is disabled. Static mode reveals one required IPv4/prefix field plus optional
gateway, DNS 1, and DNS 2 fields using existing theme tokens. Changing networks
clears stale static values and selects the valid default mode for the new network.

## Verification gates

1. API/server unit tests cover validation, persistence, DHCP compatibility,
   and QGA dispatch.
2. KVM wrapper tests prove that only the exact static-network operation bypasses
   `ablestack-storagectl` and that generic operations remain unchanged.
3. UI tests prove conditional request construction.
4. The management backend, UI, and KVM agent plugin are deployed together.
5. Test deployment verifies management/UI health and deployed asset hashes.
6. End-to-end acceptance creates an L2 SharedFS in a no-DHCP network, confirms
   the requested address through QGA, validates connectivity, reboots the VM,
   and confirms that the same address and any configured route and DNS return.
