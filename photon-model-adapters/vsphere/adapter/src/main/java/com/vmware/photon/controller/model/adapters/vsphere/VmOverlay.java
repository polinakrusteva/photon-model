/*
 * Copyright (c) 2015-2016 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.model.adapters.vsphere;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.vmware.photon.controller.model.adapters.vsphere.util.VimNames;
import com.vmware.photon.controller.model.adapters.vsphere.util.VimPath;
import com.vmware.photon.controller.model.resources.ComputeService.PowerState;
import com.vmware.vim25.ArrayOfGuestNicInfo;
import com.vmware.vim25.ArrayOfVirtualDevice;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.ObjectContent;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualEthernetCard;
import com.vmware.vim25.VirtualMachinePowerState;

/**
 * Type-safe wrapper of a VM represented by a set of fetched properties.
 */
public class VmOverlay extends AbstractOverlay {

    private static final Comparator<String> IP_COMPARATOR = new Comparator<String>() {
        @Override
        public int compare(String s1, String s2) {
            return Integer.compare(score(s1), score(s2));
        }

        /**
         * Score a dot-decimal string according to https://en.wikipedia.org/wiki/Private_network
         * The "more private" an IP looks the lower score it gets. Classles IPs get highest score.
         *
         * @param s
         * @return
         */
        private int score(String s) {
            int n = Integer.MAX_VALUE;

            if (s.startsWith("10.")) {
                n = 24;
            } else if (s.startsWith("172.")) {
                String octet2 = s.substring(4, s.indexOf('.', 5));
                if (Integer.parseInt(octet2) >= 16) {
                    n = 20;
                }
            } else if (s.startsWith("192.168.")) {
                n = 16;
            }

            return n;
        }
    };

    public VmOverlay(ObjectContent cont) {
        super(cont);
        ensureType(VimNames.TYPE_VM);
    }

    public VmOverlay(ManagedObjectReference ref, Map<String, Object> props) {
        super(ref, props);
        ensureType(VimNames.TYPE_VM);
    }

    public PowerState getPowerState() {
        return VSphereToPhotonMapping.convertPowerState(
                (VirtualMachinePowerState) getOrFail(VimPath.vm_runtime_powerState));
    }

    public String getInstanceUuid() {
        return (String) getOrDefault(VimPath.vm_config_instanceUuid, null);
    }

    public String getName() {
        return (String) getOrFail(VimPath.vm_config_name);
    }

    public List<VirtualEthernetCard> getNics() {
        ArrayOfVirtualDevice dev = (ArrayOfVirtualDevice) getOrDefault(
                VimPath.vm_config_hardware_device, null);
        if (dev == null) {
            return Collections.emptyList();
        }

        return dev.getVirtualDevice().stream()
                .filter(d -> d instanceof VirtualEthernetCard)
                .map(d -> (VirtualEthernetCard) d)
                .collect(Collectors.toList());
    }

    public List<VirtualDisk> getDisks() {
        ArrayOfVirtualDevice dev = (ArrayOfVirtualDevice) getOrDefault(
                VimPath.vm_config_hardware_device, null);
        if (dev == null) {
            return Collections.emptyList();
        }

        return dev.getVirtualDevice().stream()
                .filter(d -> d instanceof VirtualDisk)
                .map(d -> (VirtualDisk) d)
                .collect(Collectors.toList());
    }

    public boolean isTemplate() {
        return (boolean) getOrFail(VimPath.vm_config_template);
    }

    public ManagedObjectReference getHost() {
        return (ManagedObjectReference) getOrFail(VimPath.vm_runtime_host);
    }

    public String getPrimaryMac() {
        for (VirtualEthernetCard dev : getNics()) {
            return dev.getMacAddress();
        }

        return null;
    }

    public List<String> getAllIps() {
        ArrayOfGuestNicInfo arr = (ArrayOfGuestNicInfo) getOrDefault(VimPath.vm_guest_net, null);
        if (arr == null) {
            return Collections.emptyList();
        }

        return arr.getGuestNicInfo()
                .stream()
                .flatMap(gni -> gni.getIpAddress().stream()).collect(Collectors.toList());
    }

    /**
     * Tries to guess the "public" IP of a VM. IPv6 addresses are excluded.
     * It prefer routable addresses, then class A, then class B, then class C.
     * Return null if not candidates.
     * @return
     */
    public String guessPublicIpV4Address() {
        return guessPublicIpV4Address(getAllIps());
    }

    public String guessPublicIpV4Address(Collection<String> ips) {
        Optional<String> ip = ips.stream()
                .filter(s -> !s.contains(":"))
                .max(IP_COMPARATOR);

        return ip.orElse(null);
    }

    public int getNumCpu() {
        return (int) getOrFail(VimPath.vm_summary_config_numCpu);
    }

    public long getMemoryBytes() {
        return ((int) getOrFail(VimPath.vm_config_hardware_memoryMB)) * MB_to_bytes;
    }
}
