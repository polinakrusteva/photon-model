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

package com.vmware.photon.controller.model.adapters.azure;

import com.vmware.photon.controller.model.UriPaths;

/**
 * URI definitions for Azure adapter.
 */
public class AzureUriPaths {

    public static final String PROVISIONING_AZURE = UriPaths.PROVISIONING
            + "/azure";
    public static final String AZURE_INSTANCE_ADAPTER = PROVISIONING_AZURE
            + "/instance-adapter";
    public static final String AZURE_STATS_ADAPTER = PROVISIONING_AZURE
            + "/stats-adapter";
    public static final String AZURE_COMPUTE_STATS_GATHERER = PROVISIONING_AZURE
            + "/compute-stats-gatherer";
    public static final String AZURE_COMPUTE_HOST_STATS_GATHERER = PROVISIONING_AZURE
            + "/compute-host-stats-gatherer";
    public static final String AZURE_COMPUTE_HOST_STORAGE_STATS_GATHERER = PROVISIONING_AZURE
            + "/compute-host-storage-stats-gatherer";
    public static final String AZURE_ENUMERATION_ADAPTER =
            PROVISIONING_AZURE + "/enumeration-adapter";
    public static final String AZURE_COMPUTE_ENUMERATION_ADAPTER = PROVISIONING_AZURE
            + "/compute-enumeration-adapter";
    public static final String AZURE_STORAGE_ENUMERATION_ADAPTER = PROVISIONING_AZURE
            + "/storage-enumeration-adapter";

    public static final String AZURE_ENDPOINT_CONFIG_ADAPTER = PROVISIONING_AZURE
            + "/endpoint-config-adapter";
}
