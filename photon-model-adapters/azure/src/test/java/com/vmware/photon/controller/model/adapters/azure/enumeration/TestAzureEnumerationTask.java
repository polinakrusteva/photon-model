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

package com.vmware.photon.controller.model.adapters.azure.enumeration;

import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.STORAGE_CONNECTION_STRING;
import static com.vmware.photon.controller.model.adapters.azure.instance.AzureTestUtil.createDefaultComputeHost;
import static com.vmware.photon.controller.model.adapters.azure.instance.AzureTestUtil.createDefaultDiskState;
import static com.vmware.photon.controller.model.adapters.azure.instance.AzureTestUtil.createDefaultResourcePool;
import static com.vmware.photon.controller.model.adapters.azure.instance.AzureTestUtil.createDefaultStorageAccountDescription;
import static com.vmware.photon.controller.model.adapters.azure.instance.AzureTestUtil.createDefaultVMResource;
import static com.vmware.photon.controller.model.adapters.azure.instance.AzureTestUtil.deleteServiceDocument;
import static com.vmware.photon.controller.model.adapters.azure.instance.AzureTestUtil.deleteVMs;
import static com.vmware.photon.controller.model.adapters.azure.instance.AzureTestUtil.generateName;
import static com.vmware.photon.controller.model.adapters.azure.instance.AzureTestUtil.getAzureVMCount;
import static com.vmware.photon.controller.model.adapters.azure.instance.AzureTestUtil.randomString;
import static com.vmware.photon.controller.model.adapters.azure.utils.AzureUtils.getResourceGroupName;
import static com.vmware.photon.controller.model.constants.PhotonModelConstants.STORAGE_USED_BYTES;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.credentials.AzureEnvironment;
import com.microsoft.azure.management.compute.ComputeManagementClient;
import com.microsoft.azure.management.compute.ComputeManagementClientImpl;
import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.ResourceManagementClientImpl;
import com.microsoft.azure.management.storage.StorageManagementClient;
import com.microsoft.azure.management.storage.StorageManagementClientImpl;
import com.microsoft.azure.management.storage.models.StorageAccount;
import com.microsoft.azure.management.storage.models.StorageAccountKeys;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.ListBlobItem;
import com.microsoft.rest.ServiceResponse;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.vmware.photon.controller.model.PhotonModelServices;
import com.vmware.photon.controller.model.adapterapi.ComputeStatsRequest;
import com.vmware.photon.controller.model.adapterapi.ComputeStatsResponse;
import com.vmware.photon.controller.model.adapterapi.EnumerationAction;
import com.vmware.photon.controller.model.adapters.azure.AzureAdapters;
import com.vmware.photon.controller.model.adapters.azure.AzureUriPaths;
import com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants;
import com.vmware.photon.controller.model.monitoring.ResourceMetricsService;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.photon.controller.model.resources.DiskService;
import com.vmware.photon.controller.model.resources.DiskService.DiskState;
import com.vmware.photon.controller.model.resources.ResourcePoolService.ResourcePoolState;
import com.vmware.photon.controller.model.resources.StorageDescriptionService;
import com.vmware.photon.controller.model.resources.StorageDescriptionService.StorageDescription;
import com.vmware.photon.controller.model.tasks.PhotonModelTaskServices;
import com.vmware.photon.controller.model.tasks.ProvisionComputeTaskService;
import com.vmware.photon.controller.model.tasks.ProvisionComputeTaskService.ProvisionComputeTaskState;
import com.vmware.photon.controller.model.tasks.ProvisionComputeTaskService.ProvisionComputeTaskState.SubStage;
import com.vmware.photon.controller.model.tasks.ProvisioningUtils;
import com.vmware.photon.controller.model.tasks.ResourceEnumerationTaskService;
import com.vmware.photon.controller.model.tasks.ResourceEnumerationTaskService.ResourceEnumerationTaskState;
import com.vmware.photon.controller.model.tasks.TaskOption;
import com.vmware.photon.controller.model.tasks.TestUtils;
import com.vmware.photon.controller.model.tasks.monitoring.SingleResourceStatsCollectionTaskService.SingleResourceTaskCollectionStage;
import com.vmware.photon.controller.model.tasks.monitoring.StatsUtil;

import com.vmware.xenon.common.BasicReusableHostTestCase;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.ServiceStats.ServiceStat;
import com.vmware.xenon.common.StatelessService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;

/**
 * PRE-REQUISITE: An Azure Resource Manager VM named <b>EnumTestVM-DoNotDelete</b>, with diagnostics enabled,
 *                is required for the stats collection on compute host to be successful.
 *
 * NOTE: Testing pagination related changes requires manual setup due to account limits, slowness
 * of vm creation on azure (this slowness is on azure), and cost associated.
 *
 * For manual tests use Azure CLI to create multiple VMs using this bash command line:
 *
 * for i in {1..55}; do azure vm quick-create resourcegroup vm$i westus linux canonical:UbuntuServer:12.04.3-LTS:12.04.201401270 azureuser Pa$$word% -z Standard_A0; done
 */
public class TestAzureEnumerationTask extends BasicReusableHostTestCase {
    private static final int STALE_VM_RESOURCES_COUNT = 100;
    private static final int STALE_STORAGE_ACCOUNTS_COUNT = 5;
    private static final int STALE_BLOBS_COUNT = 5;

    public String clientID = "clientID";
    public String clientKey = "clientKey";
    public String subscriptionId = "subscriptionId";
    public String tenantId = "tenantId";

    public String azureVMNamePrefix = "enumtest-";
    public String azureVMName;
    public boolean isMock = true;
    public String mockedStorageAccountName = randomString(15);

    // fields that are used across method calls, stash them as private fields
    private String resourcePoolLink;
    private ComputeState vmState;
    private ComputeState computeHost;
    private StorageDescription storageDescription;
    private DiskState diskState;

    private ComputeManagementClient computeManagementClient;
    private ResourceManagementClient resourceManagementClient;
    private StorageManagementClient storageManagementClient;

    private String enumeratedComputeLink;

    private static final String CUSTOM_DIAGNOSTIC_ENABLED_VM = "EnumTestVM-DoNotDelete";

    private List<StorageAccount> storageAccounts = new ArrayList<>();
    private int numberOfVMsToDelete = 0;
    private int vmCount = 0;

    @Before
    public void setUp() throws Exception {
        try {
            this.azureVMName = this.azureVMName == null ? generateName(this.azureVMNamePrefix)
                    : this.azureVMName;
            PhotonModelServices.startServices(this.host);
            PhotonModelTaskServices.startServices(this.host);
            AzureAdapters.startServices(this.host);
            // TODO: VSYM-992 - improve test/fix arbitrary timeout
            this.host.setTimeoutSeconds(600);

            this.host.waitForServiceAvailable(PhotonModelServices.LINKS);
            this.host.waitForServiceAvailable(PhotonModelTaskServices.LINKS);
            this.host.waitForServiceAvailable(AzureAdapters.LINKS);

            if (!this.isMock) {
                ApplicationTokenCredentials credentials = new ApplicationTokenCredentials(
                        this.clientID,
                        this.tenantId, this.clientKey, AzureEnvironment.AZURE);
                this.computeManagementClient = new ComputeManagementClientImpl(credentials);
                this.computeManagementClient.setSubscriptionId(this.subscriptionId);

                this.resourceManagementClient = new ResourceManagementClientImpl(credentials);
                this.resourceManagementClient.setSubscriptionId(this.subscriptionId);

                this.storageManagementClient = new StorageManagementClientImpl(credentials);
                this.storageManagementClient.setSubscriptionId(this.subscriptionId);
            }

        } catch (Throwable e) {
            throw new Exception(e);
        }
    }

    @After
    public void tearDown() throws InterruptedException {
        // try to delete the VMs
        if (this.vmState != null) {
            try {
                int baselineCount = getAzureVMCount(this.computeManagementClient) + 1;
                deleteVMs(this.host, this.vmState.documentSelfLink, this.isMock, baselineCount);
            } catch (Throwable deleteEx) {
                // just log and move on
                this.host.log(Level.WARNING, "Exception deleting VM - %s", deleteEx.getMessage());
            }
        }

        // try to delete the storageAccounts
        if (this.storageDescription != null) {
            try {
                deleteServiceDocument(this.host, this.storageDescription.documentSelfLink);
            } catch (Throwable deleteEx) {
                this.host.log(Level.WARNING, "Exception deleting storage accounts - %s",
                        deleteEx.getMessage());
            }
        }

        // try to delete the blobs
        if (this.diskState != null) {
            try {
                deleteServiceDocument(this.host, this.diskState.documentSelfLink);
            } catch (Throwable deleteEx) {
                this.host.log(Level.WARNING, "Exception deleting disk states - %s",
                        deleteEx.getMessage());
            }
        }
    }

    @Test
    public void testEnumeration() throws Throwable {
        // Create a resource pool where the VM will be housed
        ResourcePoolState outPool = createDefaultResourcePool(this.host);
        this.resourcePoolLink = outPool.documentSelfLink;

        // create a compute host for the Azure
        this.computeHost = createDefaultComputeHost(this.host, this.clientID, this.clientKey,
                this.subscriptionId, this.tenantId, this.resourcePoolLink);

        this.storageDescription = createDefaultStorageAccountDescription(this.host,
                this.mockedStorageAccountName, this.computeHost.documentSelfLink,
                this.resourcePoolLink);

        this.diskState = createDefaultDiskState(this.host, this.mockedStorageAccountName,
                this.mockedStorageAccountName, this.resourcePoolLink);
        // create an Azure VM compute resource (this also creates a disk and a storage account)
        this.vmState = createDefaultVMResource(this.host, this.azureVMName,
                this.computeHost.documentSelfLink, this.resourcePoolLink);

        // kick off a provision task to do the actual VM creation
        ProvisionComputeTaskState provisionTask = new ProvisionComputeTaskState();

        provisionTask.computeLink = this.vmState.documentSelfLink;
        provisionTask.isMockRequest = this.isMock;
        provisionTask.taskSubStage = SubStage.CREATING_HOST;

        ProvisionComputeTaskState outTask = TestUtils
                .doPost(this.host, provisionTask, ProvisionComputeTaskState.class,
                        UriUtils.buildUri(this.host, ProvisionComputeTaskService.FACTORY_LINK));

        this.host.waitForFinishedTask(ProvisionComputeTaskState.class, outTask.documentSelfLink);

        // Check resources have been created
        // expected VM count = 2 (1 compute host instance + 1 vm compute state)
        ProvisioningUtils.queryComputeInstances(this.host, 2);
        ProvisioningUtils.queryDocumentsAndAssertExpectedCount(this.host, 1,
                StorageDescriptionService.FACTORY_LINK, true);
        ProvisioningUtils.queryDocumentsAndAssertExpectedCount(this.host, 1,
                DiskService.FACTORY_LINK,
                true);

        this.numberOfVMsToDelete++;

        if (this.isMock) {
            runEnumeration();
            deleteVMs(this.host, this.vmState.documentSelfLink, this.isMock, 1);
            this.vmState = null;
            ProvisioningUtils.queryComputeInstances(this.host, 1);
            deleteServiceDocument(this.host, this.storageDescription.documentSelfLink);
            this.storageDescription = null;
            ProvisioningUtils.queryDocumentsAndAssertExpectedCount(this.host, 0,
                    StorageDescriptionService.FACTORY_LINK, true);
            deleteServiceDocument(this.host, this.diskState.documentSelfLink);
            this.diskState = null;
            ProvisioningUtils.queryDocumentsAndAssertExpectedCount(this.host, 0,
                    DiskService.FACTORY_LINK, true);
            return;
        }

        // create stale resource states for deletion
        // these should be deleted as part of first enumeration cycle.
        createAzureVMResources(STALE_VM_RESOURCES_COUNT);
        createAzureStorageAccounts(STALE_STORAGE_ACCOUNTS_COUNT);
        createAzureBlobs(STALE_BLOBS_COUNT);

        // stale resources + 1 compute host instance + 1 vm compute state
        ProvisioningUtils.queryComputeInstances(this.host, STALE_VM_RESOURCES_COUNT + 2);
        ProvisioningUtils.queryDocumentsAndAssertExpectedCount(this.host,
                STALE_STORAGE_ACCOUNTS_COUNT + 1, StorageDescriptionService.FACTORY_LINK, true);
        ProvisioningUtils.queryDocumentsAndAssertExpectedCount(this.host, STALE_BLOBS_COUNT + 1,
                DiskService.FACTORY_LINK, true);

        this.vmCount = getAzureVMCount(this.computeManagementClient);
        this.host.log(Level.INFO, "Initial VM Count: %d", this.vmCount);

        int storageAcctCount = getAzureStorageAcctCount();
        int blobCount = getAzureBlobsCount();

        runEnumeration();

        // expect to find as many local accounts and blobs as there are on Azure
        ProvisioningUtils.queryDocumentsAndAssertExpectedCount(this.host, storageAcctCount,
                StorageDescriptionService.FACTORY_LINK, true);
        // TODO: validate the total count of blobs - depending on triggering the adapters in sequence
        // https://jira-hzn.eng.vmware.com/browse/VSYM-2819

        // VM count + 1 compute host instance
        this.vmCount = this.vmCount + 1;
        ServiceDocumentQueryResult result = ProvisioningUtils.queryComputeInstances(this.host,
                this.vmCount);

        for (Entry<String, Object> key : result.documents.entrySet()) {
            ComputeState document = Utils.fromJson(key.getValue(), ComputeState.class);
            if (!document.documentSelfLink.equals(this.computeHost.documentSelfLink)
                    && !document.documentSelfLink.equals(this.vmState.documentSelfLink)
                    && document.id.toLowerCase()
                            .contains(CUSTOM_DIAGNOSTIC_ENABLED_VM.toLowerCase())) {
                this.enumeratedComputeLink = document.documentSelfLink;
                break;
            }
        }

        try {
            // Test stats for the VM that was just enumerated from Azure.
            this.host.log(Level.INFO, "Collecting stats for VM [%s]-[%s]",
                    CUSTOM_DIAGNOSTIC_ENABLED_VM, this.enumeratedComputeLink);
            this.host.setTimeoutSeconds(300);
            if (this.enumeratedComputeLink != null) {
                this.host.waitFor("Error waiting for VM stats", () -> {
                    try {
                        issueStatsRequest(this.enumeratedComputeLink, false);
                    } catch (Throwable t) {
                        return false;
                    }
                    return true;
                });
            }

            // Test stats for the compute host.
            this.host.log(Level.INFO, "Collecting stats for host [%s]",
                    this.computeHost.documentSelfLink);
            this.host.waitFor("Error waiting for host stats", () -> {
                try {
                    issueStatsRequest(this.computeHost.documentSelfLink, true);
                } catch (Throwable t) {
                    return false;
                }
                return true;
            });
        } catch (Throwable te) {
            this.host.log(Level.SEVERE, te.getMessage());
        }

        // delete vm directly on azure
        this.computeManagementClient.getVirtualMachinesOperations()
                .beginDelete(this.azureVMName, this.azureVMName);

        runEnumeration();

        // after data collection the deleted vm should go away
        this.vmCount = this.vmCount - this.numberOfVMsToDelete;
        ProvisioningUtils.queryComputeInstances(this.host, this.vmCount);

        // clean up
        this.vmState = null;
        this.resourceManagementClient.getResourceGroupsOperations().beginDelete(this.azureVMName);
    }

    private void createAzureVMResources(int numOfVMs) throws Throwable {
        for (int i = 0; i < numOfVMs; i++) {
            String staleVMName = "stalevm-" + i;
            createDefaultVMResource(this.host, staleVMName, this.computeHost.documentSelfLink,
                    this.resourcePoolLink);
        }
    }

    private void runEnumeration() throws Throwable {
        ResourceEnumerationTaskState enumerationTaskState = new ResourceEnumerationTaskState();

        enumerationTaskState.parentComputeLink = this.computeHost.documentSelfLink;
        enumerationTaskState.enumerationAction = EnumerationAction.START;
        enumerationTaskState.adapterManagementReference = UriUtils
                .buildUri(AzureEnumerationAdapterService.SELF_LINK);
        enumerationTaskState.resourcePoolLink = this.resourcePoolLink;
        if (this.isMock) {
            enumerationTaskState.options = EnumSet.of(TaskOption.IS_MOCK);
        }

        ResourceEnumerationTaskState enumTask = TestUtils
                .doPost(this.host, enumerationTaskState, ResourceEnumerationTaskState.class,
                        UriUtils.buildUri(this.host, ResourceEnumerationTaskService.FACTORY_LINK));

        this.host.waitFor("Error waiting for enumeration task", () -> {
            try {
                ResourceEnumerationTaskState state = this.host
                        .waitForFinishedTask(ResourceEnumerationTaskState.class,
                                enumTask.documentSelfLink);
                if (state != null) {
                    return true;
                }
            } catch (Throwable e) {
                return false;
            }
            return false;
        });
    }

    private void issueStatsRequest(String selfLink, boolean isComputeHost) throws Throwable {
        // spin up a stateless service that acts as the parent link to patch back to
        StatelessService parentService = new StatelessService() {
            @Override
            public void handleRequest(Operation op) {
                if (op.getAction() == Action.PATCH) {
                    if (!TestAzureEnumerationTask.this.isMock) {
                        ComputeStatsResponse resp = op.getBody(ComputeStatsResponse.class);
                        if (resp == null) {
                            TestAzureEnumerationTask.this.host.failIteration(
                                    new IllegalStateException("response was null."));
                            return;
                        }
                        if (resp.statsList.size() != 1) {
                            TestAzureEnumerationTask.this.host.failIteration(
                                    new IllegalStateException("response size was incorrect."));
                            return;
                        }
                        if (resp.statsList.get(0).statValues.size() == 0) {
                            TestAzureEnumerationTask.this.host
                                    .failIteration(new IllegalStateException(
                                            "incorrect number of metrics received."));
                            return;
                        }
                        if (!resp.statsList.get(0).computeLink.equals(selfLink)) {
                            TestAzureEnumerationTask.this.host
                                    .failIteration(new IllegalStateException(
                                            "Incorrect resourceReference returned."));
                            return;
                        }
                        // Verify all the stats are obtained
                        verifyStats(resp, isComputeHost);
                        // Persist stats on Verification Host for testing the computeHost stats.
                        URI persistStatsUri = UriUtils.buildUri(getHost(),
                                ResourceMetricsService.FACTORY_LINK);
                        ResourceMetricsService.ResourceMetrics resourceMetric = new ResourceMetricsService.ResourceMetrics();
                        resourceMetric.documentSelfLink = StatsUtil.getMetricKey(selfLink,
                                Utils.getNowMicrosUtc());
                        resourceMetric.entries = new HashMap<>();
                        resourceMetric.timestampMicrosUtc = Utils.getNowMicrosUtc();
                        for (String key : resp.statsList.get(0).statValues.keySet()) {
                            List<ServiceStat> stats = resp.statsList.get(0).statValues.get(key);
                            for (ServiceStat stat : stats) {
                                resourceMetric.entries.put(key, stat.latestValue);
                            }
                        }
                        TestAzureEnumerationTask.this.host.sendRequest(Operation
                                .createPost(persistStatsUri)
                                .setReferer(TestAzureEnumerationTask.this.host.getUri())
                                .setBodyNoCloning(resourceMetric));
                    }
                    TestAzureEnumerationTask.this.host.completeIteration();
                }
            }
        };
        String servicePath = UUID.randomUUID().toString();
        Operation startOp = Operation.createPost(UriUtils.buildUri(this.host, servicePath));
        this.host.startService(startOp, parentService);
        ComputeStatsRequest statsRequest = new ComputeStatsRequest();
        statsRequest.resourceReference = UriUtils.buildUri(this.host, selfLink);
        statsRequest.nextStage = SingleResourceTaskCollectionStage.UPDATE_STATS.name();
        statsRequest.isMockRequest = this.isMock;
        statsRequest.taskReference = UriUtils.buildUri(this.host, servicePath);
        this.host.sendAndWait(Operation.createPatch(UriUtils.buildUri(
                this.host, AzureUriPaths.AZURE_STATS_ADAPTER))
                .setBody(statsRequest)
                .setReferer(this.host.getUri()));
    }

    private void verifyStats(ComputeStatsResponse resp, boolean isComputeHost) {
        Set<String> obtainedMetricKeys = resp.statsList.get(0).statValues.keySet();
        // Check if at least one metric was returned by Azure.
        Assert.assertTrue("No metrics were returned.", obtainedMetricKeys.size() > 0);
        if (isComputeHost) {
            Assert.assertTrue(obtainedMetricKeys.contains(STORAGE_USED_BYTES));
        }
    }

    private void createAzureStorageAccounts(int numOfAccts) throws Throwable {
        for (int i = 0; i < numOfAccts; i++) {
            String staleAcctName = "staleAcct-" + i;
            createDefaultStorageAccountDescription(this.host, staleAcctName,
                    this.computeHost.documentSelfLink, this.resourcePoolLink);
        }
    }

    private void createAzureBlobs(int numOfBlobs) throws Throwable {
        for (int i = 0; i < numOfBlobs; i++) {
            String staleBlobName = "staleBlob-" + i;
            createDefaultDiskState(this.host, staleBlobName, this.computeHost.documentSelfLink,
                    this.resourcePoolLink);
        }
    }

    private int getAzureStorageAcctCount() throws Exception {
        ServiceResponse<List<StorageAccount>> response = this.storageManagementClient
                .getStorageAccountsOperations().list();
        this.storageAccounts = response.getBody();
        int count = this.storageAccounts.size();
        this.host.log("Storage account count in Azure: %d", count);
        return count;
    }

    private int getAzureBlobsCount() throws Throwable {
        int blobCount = 0;
        for (StorageAccount storageAcct : this.storageAccounts) {
            String resourceGroupName = getResourceGroupName(storageAcct.getId());
            ServiceResponse<StorageAccountKeys> keys = getStorageManagementClient()
                    .getStorageAccountsOperations()
                    .listKeys(resourceGroupName, storageAcct.getName());

            String connectionString = String.format(STORAGE_CONNECTION_STRING,
                    storageAcct.getName(),
                    keys.getBody().getKey1());
            CloudStorageAccount storageAccount = null;
            storageAccount = CloudStorageAccount.parse(connectionString);
            CloudBlobClient blobClient = storageAccount.createCloudBlobClient();
            Iterable<CloudBlobContainer> containerList = blobClient.listContainers();
            for (CloudBlobContainer container : containerList) {
                for (ListBlobItem blobItem : container.listBlobs()) {
                    blobCount++;
                }
            }
        }
        this.host.log("Blob count in Azure: %d", blobCount);
        return blobCount;
    }

    private StorageManagementClient getStorageManagementClient() {
        ApplicationTokenCredentials credentials = new ApplicationTokenCredentials(this.clientID,
                this.tenantId, this.clientKey, AzureEnvironment.AZURE);
        this.storageManagementClient = new StorageManagementClientImpl(AzureConstants.BASE_URI,
                credentials);
        this.storageManagementClient.setSubscriptionId(this.subscriptionId);
        return this.storageManagementClient;
    }
}
