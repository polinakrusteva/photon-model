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

package com.vmware.photon.controller.model.adapters.azure.model.diagnostics;

public class DiagnosticMonitorConfiguration {
    public Integer overallQuotaInMB;
    private Metrics metrics;
    public DiagnosticInfrastructureLogs diagnosticInfrastructureLogs;
    public Directories directories;
    public PerformanceCounters performanceCounters;
    public WindowsEventLog windowsEventLog;

    public Metrics getMetrics() {
        return this.metrics;
    }
}
