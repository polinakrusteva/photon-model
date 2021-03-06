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

package com.vmware.photon.controller.model.adapters.azure.instance;

import static com.vmware.photon.controller.model.ComputeProperties.RESOURCE_GROUP_NAME;
import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.AZURE_OSDISK_CACHING;
import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.AZURE_STORAGE_ACCOUNT_KEY1;
import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.AZURE_STORAGE_ACCOUNT_KEY2;
import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.AZURE_STORAGE_ACCOUNT_NAME;
import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.AZURE_STORAGE_ACCOUNT_TYPE;
import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.COMPUTE_NAMESPACE;
import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.MISSING_SUBSCRIPTION_CODE;
import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.NETWORK_NAMESPACE;
import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.PROVIDER_REGISTRED_STATE;
import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.PROVISIONING_STATE_SUCCEEDED;
import static com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants.STORAGE_NAMESPACE;
import static com.vmware.photon.controller.model.adapters.azure.utils.AzureUtils.awaitTermination;
import static com.vmware.photon.controller.model.adapters.azure.utils.AzureUtils.cleanUpHttpClient;
import static com.vmware.photon.controller.model.adapters.azure.utils.AzureUtils.getAzureConfig;
import static com.vmware.photon.controller.model.constants.PhotonModelConstants.CLOUD_CONFIG_DEFAULT_FILE_INDEX;
import static com.vmware.xenon.common.Operation.STATUS_CODE_UNAUTHORIZED;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.microsoft.azure.CloudError;
import com.microsoft.azure.CloudException;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.compute.ComputeManagementClient;
import com.microsoft.azure.management.compute.ComputeManagementClientImpl;
import com.microsoft.azure.management.compute.models.HardwareProfile;
import com.microsoft.azure.management.compute.models.ImageReference;
import com.microsoft.azure.management.compute.models.NetworkInterfaceReference;
import com.microsoft.azure.management.compute.models.NetworkProfile;
import com.microsoft.azure.management.compute.models.OSDisk;
import com.microsoft.azure.management.compute.models.OSProfile;
import com.microsoft.azure.management.compute.models.StorageProfile;
import com.microsoft.azure.management.compute.models.VirtualHardDisk;
import com.microsoft.azure.management.compute.models.VirtualMachine;
import com.microsoft.azure.management.compute.models.VirtualMachineImage;
import com.microsoft.azure.management.compute.models.VirtualMachineImageResource;
import com.microsoft.azure.management.network.NetworkInterfacesOperations;
import com.microsoft.azure.management.network.NetworkManagementClient;
import com.microsoft.azure.management.network.NetworkManagementClientImpl;
import com.microsoft.azure.management.network.NetworkSecurityGroupsOperations;
import com.microsoft.azure.management.network.PublicIPAddressesOperations;
import com.microsoft.azure.management.network.VirtualNetworksOperations;
import com.microsoft.azure.management.network.models.AddressSpace;
import com.microsoft.azure.management.network.models.NetworkInterface;
import com.microsoft.azure.management.network.models.NetworkInterfaceIPConfiguration;
import com.microsoft.azure.management.network.models.NetworkSecurityGroup;
import com.microsoft.azure.management.network.models.PublicIPAddress;
import com.microsoft.azure.management.network.models.SecurityRule;
import com.microsoft.azure.management.network.models.Subnet;
import com.microsoft.azure.management.network.models.VirtualNetwork;
import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.ResourceManagementClientImpl;
import com.microsoft.azure.management.resources.SubscriptionClient;
import com.microsoft.azure.management.resources.SubscriptionClientImpl;
import com.microsoft.azure.management.resources.models.Provider;
import com.microsoft.azure.management.resources.models.ResourceGroup;
import com.microsoft.azure.management.resources.models.Subscription;
import com.microsoft.azure.management.storage.StorageAccountsOperations;
import com.microsoft.azure.management.storage.StorageManagementClient;
import com.microsoft.azure.management.storage.StorageManagementClientImpl;
import com.microsoft.azure.management.storage.models.AccountType;
import com.microsoft.azure.management.storage.models.ProvisioningState;
import com.microsoft.azure.management.storage.models.StorageAccount;
import com.microsoft.azure.management.storage.models.StorageAccountCreateParameters;
import com.microsoft.azure.management.storage.models.StorageAccountKeys;
import com.microsoft.rest.ServiceCallback;
import com.microsoft.rest.ServiceResponse;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;

import com.vmware.photon.controller.model.adapterapi.ComputeInstanceRequest;
import com.vmware.photon.controller.model.adapters.azure.AzureAsyncCallback;
import com.vmware.photon.controller.model.adapters.azure.AzureUriPaths;
import com.vmware.photon.controller.model.adapters.azure.constants.AzureConstants;
import com.vmware.photon.controller.model.adapters.azure.model.AzureAllocationContext;
import com.vmware.photon.controller.model.adapters.azure.model.AzureAllocationContext.NicAllocationContext;
import com.vmware.photon.controller.model.adapters.azure.model.diagnostics.AzureDiagnosticSettings;
import com.vmware.photon.controller.model.adapters.util.AdapterUtils;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService;
import com.vmware.photon.controller.model.resources.ComputeService;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeStateWithDescription;
import com.vmware.photon.controller.model.resources.ComputeService.LifecycleState;
import com.vmware.photon.controller.model.resources.DiskService.DiskState;
import com.vmware.photon.controller.model.resources.NetworkInterfaceService.NetworkInterfaceState;
import com.vmware.photon.controller.model.resources.NetworkInterfaceService.NetworkInterfaceStateWithDescription;
import com.vmware.photon.controller.model.resources.NetworkService.NetworkState;
import com.vmware.photon.controller.model.resources.StorageDescriptionService;
import com.vmware.photon.controller.model.resources.StorageDescriptionService.StorageDescription;
import com.vmware.photon.controller.model.resources.SubnetService.SubnetState;
import com.vmware.xenon.common.FileUtils;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.ServiceErrorResponse;
import com.vmware.xenon.common.StatelessService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.AuthCredentialsService;
import com.vmware.xenon.services.common.AuthCredentialsService.AuthCredentialsServiceState;

/**
 * Adapter to create/delete a VM instance on Azure.
 */
public class AzureInstanceService extends StatelessService {

    public static final String SELF_LINK = AzureUriPaths.AZURE_INSTANCE_ADAPTER;

    // TODO VSYM-322: Remove unused default properties from AzureInstanceService
    // Name prefixes
    private static final String NICCONFIG_NAME_PREFIX = "nicconfig";

    private static final String PRIVATE_IP_ALLOCATION_METHOD = "Dynamic";

    private static final String DEFAULT_GROUP_PREFIX = "group";

    private static final String DEFAULT_VM_SIZE = "Basic_A0";
    private static final String OS_DISK_CREATION_OPTION = "fromImage";

    private static final AccountType DEFAULT_STORAGE_ACCOUNT_TYPE = AccountType.STANDARD_LRS;
    private static final String VHD_URI_FORMAT = "https://%s.blob.core.windows.net/vhds/%s.vhd";
    private static final String BOOT_DISK_SUFFIX = "-boot-disk";

    private static final long DEFAULT_EXPIRATION_INTERVAL_MICROS = TimeUnit.MINUTES.toMicros(5);
    private static final int RETRY_INTERVAL_SECONDS = 30;

    private static final class WaitProvisiningToSucceed {
        static final int INTERVAL = 500;
        static final TimeUnit TIMEUNIT = TimeUnit.MILLISECONDS;
        static final int MAX_WAITS = 10;
    }

    private ExecutorService executorService;

    @Override
    public void handleStart(Operation startPost) {
        this.executorService = getHost().allocateExecutor(this);

        super.handleStart(startPost);
    }

    @Override
    public void handleStop(Operation delete) {
        this.executorService.shutdown();
        awaitTermination(this, this.executorService);
        super.handleStop(delete);
    }

    @Override
    public void handlePatch(Operation op) {
        if (!op.hasBody()) {
            op.fail(new IllegalArgumentException("body is required"));
            return;
        }

        AzureAllocationContext ctx = new AzureAllocationContext(
                op.getBody(ComputeInstanceRequest.class));

        switch (ctx.computeRequest.requestType) {
        case VALIDATE_CREDENTIALS:
            ctx.operation = op;
            handleAllocation(ctx, AzureStages.PARENTAUTH);
            break;
        default:
            op.complete();
            if (ctx.computeRequest.isMockRequest
                    && ctx.computeRequest.requestType == ComputeInstanceRequest.InstanceRequestType.CREATE) {
                handleAllocation(ctx, AzureStages.FINISHED);
                break;
            }
            handleAllocation(ctx);
        }
    }

    /**
     * Shortcut method that sets the next stage into the context and delegates to
     * {@link #handleAllocation(AzureAllocationContext)}.
     */
    private void handleAllocation(AzureAllocationContext ctx, AzureStages nextStage) {
        logInfo("Transition to " + nextStage);
        ctx.stage = nextStage;
        handleAllocation(ctx);
    }

    /**
     * Shortcut method that stores the error into context, sets next stage to
     * {@link AzureStages#ERROR} and delegates to {@link #handleAllocation(AzureAllocationContext)}.
     */
    private void handleError(AzureAllocationContext ctx, Throwable e) {
        ctx.error = e;
        handleAllocation(ctx, AzureStages.ERROR);
    }

    /**
     * State machine to handle different stages of VM creation/deletion.
     *
     * @see #handleError(AzureAllocationContext, Throwable)
     * @see #handleAllocation(AzureAllocationContext, AzureStages)
     */
    private void handleAllocation(AzureAllocationContext ctx) {
        try {
            switch (ctx.stage) {
            case VMDESC:
                getVMDescription(ctx, AzureStages.PARENTDESC);
                break;
            case PARENTDESC:
                getParentDescription(ctx, AzureStages.PARENTAUTH);
                break;
            case PARENTAUTH:
                getParentAuth(ctx, AzureStages.CLIENT);
                break;
            case CLIENT:
                if (ctx.credentials == null) {
                    ctx.credentials = getAzureConfig(ctx.parentAuth);
                }

                // Creating a shared singleton Http client instance
                // Reference
                // https://square.github.io/okhttp/3.x/okhttp/okhttp3/OkHttpClient.html
                // TODO: https://github.com/Azure/azure-sdk-for-java/issues/1000
                ctx.httpClient = new OkHttpClient();
                ctx.clientBuilder = ctx.httpClient.newBuilder();

                // now that we have a client lets move onto the next step
                switch (ctx.computeRequest.requestType) {
                case CREATE:
                    handleAllocation(ctx, AzureStages.CHILDAUTH);
                    break;
                case VALIDATE_CREDENTIALS:
                    validateAzureCredentials(ctx);
                    break;
                case DELETE:
                    handleAllocation(ctx, AzureStages.DELETE);
                    break;
                default:
                    throw new IllegalStateException(
                            "Unknown compute request type: " + ctx.computeRequest.requestType);
                }
                break;
            case CHILDAUTH:
                getChildAuth(ctx, AzureStages.VMDISKS);
                break;
            case VMDISKS:
                getVMDisks(ctx, AzureStages.INIT_RES_GROUP);
                break;
            case INIT_RES_GROUP:
                initResourceGroup(ctx, AzureStages.GET_DISK_OS_FAMILY);
                break;
            case GET_DISK_OS_FAMILY:
                differentiateVMImages(ctx, AzureStages.INIT_STORAGE);
                break;
            case INIT_STORAGE:
                initStorageAccount(ctx, AzureStages.GET_NIC_STATES);
                break;
            // Resolve NIC related links to NIC related states
            case GET_NIC_STATES:
                getNicStates(ctx, AzureStages.GET_SUBNET_STATES);
                break;
            case GET_SUBNET_STATES:
                getSubnetStates(ctx, AzureStages.GET_NETWORK_STATES);
                break;
            case GET_NETWORK_STATES:
                getNetworkStates(ctx, AzureStages.CREATE_NETWORKS);
                break;
            // Create Azure networks, PIPs and NSGs required by NICs
            case CREATE_NETWORKS:
                createNetworks(ctx, AzureStages.CREATE_PUBLIC_IPS);
                break;
            case CREATE_PUBLIC_IPS:
                createPublicIPs(ctx, AzureStages.CREATE_SECURITY_GROUPS);
                break;
            case CREATE_SECURITY_GROUPS:
                createSecurityGroups(ctx, AzureStages.CREATE_NICS);
                break;
            case CREATE_NICS:
                createNICs(ctx, AzureStages.CREATE);
                break;
            case CREATE:
                createVM(ctx, AzureStages.GET_PUBLIC_IP_ADDRESS);
                break;
            // TODO VSYM-620: Enable monitoring on Azure VMs
            case ENABLE_MONITORING:
                enableMonitoring(ctx, AzureStages.GET_STORAGE_KEYS);
                break;
            case GET_PUBLIC_IP_ADDRESS:
                getPublicIpAddress(ctx, AzureStages.GET_STORAGE_KEYS);
                break;
            case GET_STORAGE_KEYS:
                getStorageKeys(ctx, AzureStages.FINISHED);
                break;
            case DELETE:
                deleteVM(ctx);
                break;
            case FINISHED:
                // This is the ultimate exit point with success of the state machine
                finishWithSuccess(ctx);
                break;
            case ERROR:
                // This is the ultimate exit point with error of the state machine
                errorHandler(ctx);
                break;
            default:
                throw new IllegalStateException("Unknown stage: " + ctx.stage);
            }
        } catch (Exception e) {
            // NOTE: Do not use handleError(err) cause that might result in endless recursion.
            ctx.error = e;
            errorHandler(ctx);
        }
    }

    /**
     * Validates azure credential by making an API call.
     */
    private void validateAzureCredentials(final AzureAllocationContext ctx) {
        if (ctx.computeRequest.isMockRequest) {
            ctx.operation.complete();
            return;
        }

        SubscriptionClient subscriptionClient = new SubscriptionClientImpl(
                AzureConstants.BASE_URI, ctx.credentials, ctx.clientBuilder,
                getRetrofitBuilder());

        subscriptionClient.getSubscriptionsOperations().getAsync(
                ctx.parentAuth.userLink, new ServiceCallback<Subscription>() {
                    @Override
                    public void failure(Throwable e) {
                        // Azure doesn't send us any meaningful status code to work with
                        ServiceErrorResponse rsp = new ServiceErrorResponse();
                        rsp.message = "Invalid Azure credentials";
                        rsp.statusCode = STATUS_CODE_UNAUTHORIZED;
                        ctx.operation.fail(e, rsp);
                    }

                    @Override
                    public void success(ServiceResponse<Subscription> result) {
                        Subscription subscription = result.getBody();
                        logFine("Got subscription %s with id %s", subscription.getDisplayName(),
                                subscription.getId());
                        ctx.operation.complete();
                    }
                });
    }

    private void deleteVM(AzureAllocationContext ctx) {
        if (ctx.computeRequest.isMockRequest) {
            handleAllocation(ctx, AzureStages.FINISHED);
            return;
        }

        String resourceGroupName = getResourceGroupName(ctx);

        if (resourceGroupName == null || resourceGroupName.isEmpty()) {
            throw new IllegalArgumentException("Resource group name is required");
        }

        logInfo("Deleting resource group with name [%s]", resourceGroupName);

        ResourceManagementClient client = getResourceManagementClient(ctx);

        client.getResourceGroupsOperations().beginDeleteAsync(resourceGroupName,
                new AzureAsyncCallback<Void>() {
                    @Override
                    public void onError(Throwable e) {
                        handleError(ctx, e);
                    }

                    @Override
                    public void onSuccess(ServiceResponse<Void> result) {
                        logInfo("Successfully deleted resource group [%s]", resourceGroupName);
                        handleAllocation(ctx, AzureStages.FINISHED);
                    }
                });
    }

    /**
     * The ultimate error handler that should handle errors from all sources.
     *
     * NOTE: Do not use directly. Use it through
     * {@link #handleError(AzureAllocationContext, Throwable)}.
     */
    private void errorHandler(AzureAllocationContext ctx) {

        logSevere(ctx.error);

        if (ctx.computeRequest.isMockRequest) {
            finishWithFailure(ctx);
            return;
        }

        if (ctx.computeRequest.requestType != ComputeInstanceRequest.InstanceRequestType.CREATE) {
            finishWithFailure(ctx);
            return;
        }

        if (ctx.resourceGroup == null) {
            finishWithFailure(ctx);
            return;
        }

        // CREATE request has resulted in RG creation -> clear RG and its content.

        String resourceGroupName = ctx.resourceGroup.getName();

        String msg = "Rollback provisioning for [" + ctx.vmName + "] Azure VM: %s";

        logInfo(msg, "STARTED");

        ResourceManagementClient client = getResourceManagementClient(ctx);

        client.getResourceGroupsOperations().beginDeleteAsync(resourceGroupName,
                new AzureAsyncCallback<Void>() {
                    @Override
                    public void onError(Throwable e) {
                        String rollbackError = String.format(msg + ". Details: %s", "FAILED",
                                e.getMessage());

                        // Wrap original ctx.error with rollback error details.
                        ctx.error = new IllegalStateException(rollbackError, ctx.error);

                        finishWithFailure(ctx);
                    }

                    @Override
                    public void onSuccess(ServiceResponse<Void> result) {
                        logInfo(msg, "SUCCESS");

                        finishWithFailure(ctx);
                    }
                });
    }

    private void finishWithFailure(AzureAllocationContext ctx) {

        if (ctx.computeRequest.taskReference != null) {
            // Report the error back to the caller
            AdapterUtils.sendFailurePatchToProvisioningTask(this,
                    ctx.computeRequest.taskReference,
                    ctx.error);
        }

        cleanUpHttpClient(this, ctx.httpClient);
    }

    private void finishWithSuccess(AzureAllocationContext ctx) {

        if (ctx.computeRequest.taskReference != null) {
            // Report the success back to the caller
            AdapterUtils.sendPatchToProvisioningTask(this, ctx.computeRequest.taskReference);
        }

        cleanUpHttpClient(this, ctx.httpClient);
    }

    private void initResourceGroup(AzureAllocationContext ctx, AzureStages nextStage) {
        String resourceGroupName = getResourceGroupName(ctx);

        logInfo("Creating resource group with name [%s]", resourceGroupName);

        ResourceGroup group = new ResourceGroup();
        group.setLocation(ctx.child.description.regionId);

        ResourceManagementClient client = getResourceManagementClient(ctx);

        client.getResourceGroupsOperations().createOrUpdateAsync(resourceGroupName, group,
                new AzureAsyncCallback<ResourceGroup>() {
                    @Override
                    public void onError(Throwable e) {
                        handleError(ctx, e);
                    }

                    @Override
                    public void onSuccess(ServiceResponse<ResourceGroup> result) {
                        logInfo("Successfully created resource group [%s]",
                                result.getBody().getName());
                        ctx.resourceGroup = result.getBody();
                        handleAllocation(ctx, nextStage);
                    }
                });
    }

    private void initStorageAccount(AzureAllocationContext ctx, AzureStages nextStage) {
        StorageAccountCreateParameters storageParameters = new StorageAccountCreateParameters();
        storageParameters.setLocation(ctx.resourceGroup.getLocation());

        if (ctx.bootDisk.customProperties == null) {
            handleError(ctx,
                    new IllegalArgumentException("Custom properties for boot disk is required"));
            return;
        }

        ctx.storageAccountName = ctx.bootDisk.customProperties.get(AZURE_STORAGE_ACCOUNT_NAME);

        if (ctx.storageAccountName == null) {
            if (ctx.vmName != null) {
                ctx.storageAccountName = ctx.vmName.toLowerCase() + "st";
            } else {
                ctx.storageAccountName = String.valueOf(System.currentTimeMillis()) + "st";
            }
        }

        String accountType = ctx.bootDisk.customProperties
                .getOrDefault(AZURE_STORAGE_ACCOUNT_TYPE, DEFAULT_STORAGE_ACCOUNT_TYPE.toValue());
        storageParameters.setAccountType(AccountType.fromValue(accountType));

        String msg = "Creating Azure Storage Account [" + ctx.storageAccountName + "] for ["
                + ctx.vmName + "] VM";

        StorageAccountsOperations azureClient = getStorageManagementClient(ctx)
                .getStorageAccountsOperations();

        azureClient.createAsync(
                ctx.resourceGroup.getName(),
                ctx.storageAccountName,
                storageParameters,
                new ProvisioningCallback<StorageAccount>(ctx, nextStage, msg) {

                    @Override
                    void handleFailure(Throwable e) {
                        handleSubscriptionError(ctx, STORAGE_NAMESPACE, e);
                    }

                    @Override
                    CompletionStage<StorageAccount> handleProvisioningSucceeded(StorageAccount sa) {

                        ctx.storage = sa;

                        StorageDescription storageDescriptionToCreate = new StorageDescription();
                        storageDescriptionToCreate.name = ctx.storageAccountName;
                        storageDescriptionToCreate.type = ctx.storage.getAccountType().name();

                        Operation createStorageDescOp = Operation
                                .createPost(getHost(), StorageDescriptionService.FACTORY_LINK)
                                .setBody(storageDescriptionToCreate);

                        Operation patchBootDiskOp = Operation
                                .createPatch(
                                        UriUtils.buildUri(getHost(), ctx.bootDisk.documentSelfLink))
                                .setBody(ctx.bootDisk);

                        return sendWithDeferredResult(createStorageDescOp, StorageDescription.class)
                                // Consume created StorageDescription
                                .thenAccept((storageDescription) -> {
                                    ctx.storageDescription = storageDescription;
                                    ctx.bootDisk.storageDescriptionLink = storageDescription.documentSelfLink;
                                    logInfo("Creating StorageDescription [%s]: SUCCESS",
                                            storageDescription.name);
                                })
                                // Start next op, patch boot disk, in the sequence
                                .thenCompose((woid) -> sendWithDeferredResult(patchBootDiskOp))
                                // Log boot disk patch success
                                .thenRun(() -> {
                                    logInfo("Updating boot disk [%s]: SUCCESS", ctx.bootDisk.name);
                                })
                                // Return original StorageAccount
                                .thenApply((woid) -> sa)
                                .toCompletionStage();
                    }

                    @Override
                    String getProvisioningState(StorageAccount sa) {
                        ProvisioningState provisioningState = sa.getProvisioningState();

                        // For some reason SA.provisioningState is null, so consider it CREATING.
                        if (provisioningState == null) {
                            provisioningState = ProvisioningState.CREATING;
                        }

                        return provisioningState.name();
                    }

                    @Override
                    Runnable checkProvisioningStateCall(
                            ServiceCallback<StorageAccount> checkProvisioningStateCallback) {
                        return () -> azureClient.getPropertiesAsync(
                                ctx.resourceGroup.getName(),
                                ctx.storageAccountName,
                                checkProvisioningStateCallback);
                    }
                });
    }

    private void createNetworks(AzureAllocationContext ctx, AzureStages nextStage) {
        if (ctx.nics.isEmpty()) {
            handleAllocation(ctx, nextStage);
            return;
        }

        VirtualNetworksOperations azureClient = getNetworkManagementClient(ctx)
                .getVirtualNetworksOperations();

        // NOTE: for now we create single vNet-Subnet per VM.
        // NEXT: create vNet-Subnet OR use enumerated vNet-Subnet per NIC.

        final NicAllocationContext primaryNic = ctx.getVmPrimaryNic();
        final String vNetName = primaryNic.networkState.name;
        final String subnetName = primaryNic.subnetState.name;

        final VirtualNetwork vNet = newAzureVirtualNetwork(ctx, primaryNic);

        String msg = "Creating Azure vNet-subnet [" + vNetName + ":" + subnetName + "] for ["
                + ctx.vmName + "] VM";

        azureClient.beginCreateOrUpdateAsync(
                ctx.resourceGroup.getName(),
                vNetName,
                vNet,
                new ProvisioningCallback<VirtualNetwork>(ctx, nextStage, msg) {

                    @Override
                    void handleFailure(Throwable e) {
                        handleSubscriptionError(ctx, NETWORK_NAMESPACE, e);
                    }

                    @Override
                    CompletionStage<VirtualNetwork> handleProvisioningSucceeded(
                            VirtualNetwork vNet) {
                        Subnet subnet = vNet.getSubnets().stream()
                                .filter(s -> s.getName().equals(subnetName)).findFirst().get();

                        // Populate all NICs with same vNet-Subnet.
                        for (NicAllocationContext nicCtx : ctx.nics) {
                            nicCtx.vNet = vNet;
                            nicCtx.subnet = subnet;
                        }
                        return CompletableFuture.completedFuture(vNet);
                    }

                    @Override
                    String getProvisioningState(VirtualNetwork vNet) {
                        Subnet subnet = vNet.getSubnets().stream()
                                .filter(s -> s.getName().equals(subnetName)).findFirst().get();

                        if (PROVISIONING_STATE_SUCCEEDED.equals(vNet.getProvisioningState())
                                && PROVISIONING_STATE_SUCCEEDED
                                        .equals(subnet.getProvisioningState())) {

                            return PROVISIONING_STATE_SUCCEEDED;
                        }
                        return vNet.getProvisioningState() + ":" + subnet.getProvisioningState();
                    }

                    @Override
                    Runnable checkProvisioningStateCall(
                            ServiceCallback<VirtualNetwork> checkProvisioningStateCallback) {
                        return () -> azureClient.getAsync(
                                ctx.resourceGroup.getName(),
                                vNetName,
                                null /* expand */ ,
                                checkProvisioningStateCallback);
                    }
                });
    }

    /**
     * Converts Photon model constructs to underlying Azure VirtualNetwork-Subnet model.
     */
    private VirtualNetwork newAzureVirtualNetwork(
            AzureAllocationContext ctx,
            NicAllocationContext nicCtx) {

        Subnet subnet = new Subnet();
        subnet.setName(nicCtx.subnetState.name);
        subnet.setAddressPrefix(nicCtx.subnetState.subnetCIDR);

        VirtualNetwork vNet = new VirtualNetwork();
        vNet.setLocation(ctx.resourceGroup.getLocation());

        vNet.setAddressSpace(new AddressSpace());
        vNet.getAddressSpace().setAddressPrefixes(new ArrayList<>());
        vNet.getAddressSpace().getAddressPrefixes().add(nicCtx.networkState.subnetCIDR);

        vNet.setSubnets(new ArrayList<>());
        vNet.getSubnets().add(subnet);

        return vNet;
    }

    private void createPublicIPs(AzureAllocationContext ctx, AzureStages nextStage) {
        if (ctx.nics.isEmpty()) {
            handleAllocation(ctx, nextStage);
            return;
        }

        PublicIPAddressesOperations azureClient = getNetworkManagementClient(ctx)
                .getPublicIPAddressesOperations();

        NicAllocationContext nicCtx = ctx.getVmPrimaryNic();

        final PublicIPAddress publicIPAddress = newAzurePublicIPAddress(ctx, nicCtx);

        final String publicIPName = ctx.vmName + "-pip";

        String msg = "Creating Azure Public IP [" + publicIPName + "] for [" + ctx.vmName + "] VM";

        azureClient.beginCreateOrUpdateAsync(
                ctx.resourceGroup.getName(),
                publicIPName,
                publicIPAddress,
                new ProvisioningCallback<PublicIPAddress>(ctx, nextStage, msg) {

                    @Override
                    CompletionStage<PublicIPAddress> handleProvisioningSucceeded(
                            PublicIPAddress publicIP) {
                        nicCtx.publicIP = publicIP;

                        return CompletableFuture.completedFuture(publicIP);
                    }

                    @Override
                    Runnable checkProvisioningStateCall(
                            ServiceCallback<PublicIPAddress> checkProvisioningStateCallback) {
                        return () -> azureClient.getAsync(
                                ctx.resourceGroup.getName(),
                                publicIPName,
                                null /* expand */ ,
                                checkProvisioningStateCallback);
                    }

                    @Override
                    String getProvisioningState(PublicIPAddress publicIP) {
                        return publicIP.getProvisioningState();
                    }
                });
    }

    /**
     * Converts Photon model constructs to underlying Azure PublicIPAddress model.
     */
    private PublicIPAddress newAzurePublicIPAddress(
            AzureAllocationContext ctx,
            NicAllocationContext nicCtx) {

        PublicIPAddress publicIPAddress = new PublicIPAddress();
        publicIPAddress.setLocation(ctx.resourceGroup.getLocation());
        publicIPAddress
                .setPublicIPAllocationMethod(nicCtx.nicStateWithDesc.description.assignment.name());

        return publicIPAddress;
    }

    private void createSecurityGroups(AzureAllocationContext ctx, AzureStages nextStage) {
        if (ctx.nics.isEmpty()) {
            handleAllocation(ctx, nextStage);
            return;
        }

        NetworkSecurityGroupsOperations azureClient = getNetworkManagementClient(ctx)
                .getNetworkSecurityGroupsOperations();

        // NOTE: for now we create single NSG per VM.
        // NEXT: create NSG per NIC based on FirewallStates.

        final NetworkSecurityGroup nsg = newAzureNetworkSecurityGroup(ctx);

        final String nsgName = ctx.vmName + "-nsg";

        String msg = "Creating Azure Security Group [" + nsgName + "] for [" + ctx.vmName + "] VM";

        azureClient.beginCreateOrUpdateAsync(
                ctx.resourceGroup.getName(),
                nsgName,
                nsg,
                new ProvisioningCallback<NetworkSecurityGroup>(ctx, nextStage, msg) {

                    @Override
                    CompletionStage<NetworkSecurityGroup> handleProvisioningSucceeded(
                            NetworkSecurityGroup nsg) {
                        // Populate all NICs with same NSG.
                        for (NicAllocationContext nicCtx : ctx.nics) {
                            nicCtx.securityGroup = nsg;
                        }
                        return CompletableFuture.completedFuture(nsg);
                    }

                    @Override
                    Runnable checkProvisioningStateCall(
                            ServiceCallback<NetworkSecurityGroup> checkProvisioningStateCallback) {
                        return () -> azureClient.getAsync(
                                ctx.resourceGroup.getName(),
                                nsgName,
                                null /* expand */ ,
                                checkProvisioningStateCallback);
                    }

                    @Override
                    String getProvisioningState(NetworkSecurityGroup body) {
                        return body.getProvisioningState();
                    }
                });
    }

    /**
     * Converts Photon model constructs to underlying Azure NetworkSecurityGroup model.
     */
    private NetworkSecurityGroup newAzureNetworkSecurityGroup(
            AzureAllocationContext ctx) {

        SecurityRule securityRule = new SecurityRule();

        securityRule.setPriority(AzureConstants.AZURE_SECURITY_GROUP_PRIORITY);
        securityRule.setAccess(AzureConstants.AZURE_SECURITY_GROUP_ACCESS);
        securityRule.setProtocol(AzureConstants.AZURE_SECURITY_GROUP_PROTOCOL);
        securityRule.setDirection(AzureConstants.AZURE_SECURITY_GROUP_DIRECTION);
        securityRule.setSourceAddressPrefix(
                AzureConstants.AZURE_SECURITY_GROUP_SOURCE_ADDRESS_PREFIX);
        securityRule.setDestinationAddressPrefix(
                AzureConstants.AZURE_SECURITY_GROUP_DESTINATION_ADDRESS_PREFIX);
        securityRule
                .setSourcePortRange(AzureConstants.AZURE_SECURITY_GROUP_SOURCE_PORT_RANGE);
        if (ctx.operatingSystemFamily.equalsIgnoreCase(AzureConstants.LINUX_OPERATING_SYSTEM)) {
            securityRule.setName(AzureConstants.AZURE_LINUX_SECURITY_GROUP_NAME);
            securityRule.setDescription(AzureConstants.AZURE_LINUX_SECURITY_GROUP_DESCRIPTION);
            securityRule.setDestinationPortRange(
                    AzureConstants.AZURE_LINUX_SECURITY_GROUP_DESTINATION_PORT_RANGE);
        } else if (ctx.operatingSystemFamily
                .equalsIgnoreCase(AzureConstants.WINDOWS_OPERATING_SYSTEM)) {
            securityRule.setName(AzureConstants.AZURE_WINDOWS_SECURITY_GROUP_NAME);
            securityRule.setDescription(AzureConstants.AZURE_WINDOWS_SECURITY_GROUP_DESCRIPTION);
            securityRule.setDestinationPortRange(
                    AzureConstants.AZURE_WINDOWS_SECURITY_GROUP_DESTINATION_PORT_RANGE);
        }

        NetworkSecurityGroup securityGroup = new NetworkSecurityGroup();
        securityGroup.setLocation(ctx.resourceGroup.getLocation());
        securityGroup.setSecurityRules(Collections.singletonList(securityRule));

        return securityGroup;
    }

    private void createNICs(AzureAllocationContext ctx, AzureStages nextStage) {

        if (ctx.nics.isEmpty()) {
            handleAllocation(ctx, nextStage);
            return;
        }

        // Shared state between multi async calls {{
        AtomicInteger numberOfCalls = new AtomicInteger(ctx.nics.size());
        AtomicBoolean hasFailed = new AtomicBoolean(false);
        NetworkInterfacesOperations azureClient = getNetworkManagementClient(ctx)
                .getNetworkInterfacesOperations();
        // }}

        for (NicAllocationContext nicCtx : ctx.nics) {

            final NetworkInterface nic = newAzureNetworkInterface(ctx, nicCtx);

            final String nicName = nicCtx.nicStateWithDesc.name;

            String msg = "Creating Azure NIC [" + nicName + "] for [" + ctx.vmName + "] VM";

            azureClient.beginCreateOrUpdateAsync(
                    ctx.resourceGroup.getName(),
                    nicName,
                    nic,
                    new FailFastAzureAsyncCallback<NetworkInterface>(ctx, nextStage,
                            numberOfCalls, hasFailed, msg) {

                        @Override
                        protected void handleSuccess(NetworkInterface nic) {
                            nicCtx.nic = nic;
                        }
                    });
        }
    }

    /**
     * Converts Photon model constructs to underlying Azure NetworkInterface model.
     */
    private NetworkInterface newAzureNetworkInterface(
            AzureAllocationContext ctx,
            NicAllocationContext nicCtx) {

        NetworkInterfaceIPConfiguration ipConfig = new NetworkInterfaceIPConfiguration();
        ipConfig.setName(generateName(NICCONFIG_NAME_PREFIX));
        ipConfig.setPrivateIPAllocationMethod(PRIVATE_IP_ALLOCATION_METHOD);
        ipConfig.setSubnet(nicCtx.subnet);
        ipConfig.setPublicIPAddress(nicCtx.publicIP);

        NetworkInterface nic = new NetworkInterface();
        nic.setLocation(ctx.resourceGroup.getLocation());
        nic.setIpConfigurations(new ArrayList<>());
        nic.getIpConfigurations().add(ipConfig);
        nic.setNetworkSecurityGroup(nicCtx.securityGroup);

        return nic;
    }

    private void createVM(AzureAllocationContext ctx, AzureStages nextStage) {
        ComputeDescriptionService.ComputeDescription description = ctx.child.description;

        Map<String, String> customProperties = description.customProperties;
        if (customProperties == null) {
            handleError(ctx, new IllegalStateException("Custom properties not specified"));
            return;
        }

        DiskState bootDisk = ctx.bootDisk;
        if (bootDisk == null) {
            handleError(ctx, new IllegalStateException("Azure bootDisk not specified"));
            return;
        }

        String cloudConfig = null;
        if (bootDisk.bootConfig != null
                && bootDisk.bootConfig.files.length > CLOUD_CONFIG_DEFAULT_FILE_INDEX) {
            cloudConfig = bootDisk.bootConfig.files[CLOUD_CONFIG_DEFAULT_FILE_INDEX].contents;
        }

        VirtualMachine request = new VirtualMachine();
        request.setLocation(ctx.resourceGroup.getLocation());

        // Set OS profile.
        OSProfile osProfile = new OSProfile();
        String vmName = ctx.vmName;
        osProfile.setComputerName(vmName);
        osProfile.setAdminUsername(ctx.childAuth.userEmail);
        osProfile.setAdminPassword(ctx.childAuth.privateKey);
        if (cloudConfig != null) {
            try {
                osProfile.setCustomData(Base64.getEncoder()
                        .encodeToString(cloudConfig.getBytes(Utils.CHARSET)));
            } catch (UnsupportedEncodingException e) {
                logWarning("Error encoding user data");
                return;
            }
        }
        request.setOsProfile(osProfile);

        // Set hardware profile.
        HardwareProfile hardwareProfile = new HardwareProfile();
        hardwareProfile.setVmSize(
                description.instanceType != null ? description.instanceType : DEFAULT_VM_SIZE);
        request.setHardwareProfile(hardwareProfile);

        // Set storage profile.
        VirtualHardDisk vhd = new VirtualHardDisk();
        String vhdName = getVHDName(vmName);
        vhd.setUri(String.format(VHD_URI_FORMAT, ctx.storageAccountName, vhdName));

        OSDisk osDisk = new OSDisk();
        osDisk.setName(vmName);
        osDisk.setVhd(vhd);
        osDisk.setCaching(bootDisk.customProperties.get(AZURE_OSDISK_CACHING));
        // We don't support Attach option which allows to use a specialized disk to create the
        // virtual machine.
        osDisk.setCreateOption(OS_DISK_CREATION_OPTION);

        StorageProfile storageProfile = new StorageProfile();
        // Currently we only support platform images.
        storageProfile.setImageReference(ctx.imageReference);
        storageProfile.setOsDisk(osDisk);
        request.setStorageProfile(storageProfile);

        // Set network profile {{
        NetworkProfile networkProfile = new NetworkProfile();
        networkProfile.setNetworkInterfaces(new ArrayList<>());

        for (NicAllocationContext nicCtx : ctx.nics) {
            NetworkInterfaceReference nicRef = new NetworkInterfaceReference();
            nicRef.setId(nicCtx.nic.getId());
            // NOTE: First NIC is marked as Primary.
            nicRef.setPrimary(networkProfile.getNetworkInterfaces().isEmpty());

            networkProfile.getNetworkInterfaces().add(nicRef);
        }
        request.setNetworkProfile(networkProfile);
        // }}

        logInfo("Creating virtual machine with name [%s]", vmName);

        getComputeManagementClient(ctx).getVirtualMachinesOperations().createOrUpdateAsync(
                ctx.resourceGroup.getName(), vmName, request,
                new AzureAsyncCallback<VirtualMachine>() {
                    @Override
                    public void onError(Throwable e) {
                        handleSubscriptionError(ctx, COMPUTE_NAMESPACE, e);
                    }

                    @Override
                    public void onSuccess(ServiceResponse<VirtualMachine> result) {
                        VirtualMachine vm = result.getBody();
                        logInfo("Successfully created vm [%s]", vm.getName());

                        ComputeState cs = new ComputeState();
                        // Azure for some case changes the case of the vm id.
                        ctx.vmId = vm.getId().toLowerCase();
                        cs.id = ctx.vmId;
                        cs.lifecycleState = LifecycleState.READY;
                        if (ctx.child.customProperties == null) {
                            cs.customProperties = new HashMap<>();
                        } else {
                            cs.customProperties = ctx.child.customProperties;
                        }

                        Operation.CompletionHandler completionHandler = (ox,
                                exc) -> {
                            if (exc != null) {
                                handleError(ctx, exc);
                                return;
                            }
                            handleAllocation(ctx, nextStage);
                        };

                        sendRequest(
                                Operation.createPatch(ctx.computeRequest.resourceReference)
                                        .setBody(cs).setCompletion(completionHandler)
                                        .setReferer(getHost().getUri()));
                    }
                });
    }

    /**
     * Gets the public IP address from the VM and patches the compute state and primary NIC state.
     */
    private void getPublicIpAddress(AzureAllocationContext ctx, AzureStages nextStage) {

        NetworkManagementClient client = getNetworkManagementClient(ctx);

        client.getPublicIPAddressesOperations().getAsync(
                ctx.resourceGroup.getName(),
                ctx.getVmPrimaryNic().publicIP.getName(),
                null /* expand */,
                new AzureAsyncCallback<PublicIPAddress>() {

                    @Override
                    public void onError(Throwable e) {
                        handleError(ctx, e);
                    }

                    @Override
                    public void onSuccess(ServiceResponse<PublicIPAddress> result) {
                        ctx.getVmPrimaryNic().publicIP = result.getBody();

                        OperationJoin operationJoin = OperationJoin
                                .create(patchComputeState(ctx), patchNICState(ctx))
                                .setCompletion((ops, excs) -> {
                                    if (excs != null) {
                                        handleError(ctx, new IllegalStateException(
                                                "Error patching compute state and primary NIC state with VM Public IP address."));
                                        return;
                                    }
                                    handleAllocation(ctx, nextStage);
                                });
                        operationJoin.sendWith(AzureInstanceService.this);
                    }

                    private Operation patchComputeState(AzureAllocationContext ctx) {

                        ComputeState computeState = new ComputeState();

                        computeState.address = ctx.getVmPrimaryNic().publicIP.getIpAddress();

                        return Operation.createPatch(ctx.computeRequest.resourceReference)
                                .setBody(computeState)
                                .setCompletion((op, exc) -> {
                                    if (exc == null) {
                                        logInfo("Patching compute state with VM Public IP address ["
                                                + computeState.address + "]: SUCCESS");
                                    }
                                });

                    }

                    private Operation patchNICState(AzureAllocationContext ctx) {

                        NetworkInterfaceState primaryNicState = new NetworkInterfaceState();

                        primaryNicState.address = ctx.getVmPrimaryNic().publicIP.getIpAddress();

                        URI primaryNicUri = UriUtils.buildUri(getHost(),
                                ctx.getVmPrimaryNic().nicStateWithDesc.documentSelfLink);

                        return Operation.createPatch(primaryNicUri)
                                .setBody(primaryNicState)
                                .setCompletion((op, exc) -> {
                                    if (exc == null) {
                                        logInfo("Patching primary NIC state with VM Public IP address ["
                                                + primaryNicState.address + "]: SUCCESS");
                                    }
                                });

                    }
                });
    }

    /**
     * Gets the storage keys from azure and patches the credential state.
     */
    private void getStorageKeys(AzureAllocationContext ctx, AzureStages nextStage) {
        StorageManagementClient client = getStorageManagementClient(ctx);

        client.getStorageAccountsOperations().listKeysAsync(ctx.resourceGroup.getName(),
                ctx.storageAccountName, new AzureAsyncCallback<StorageAccountKeys>() {
                    @Override
                    public void onError(Throwable e) {
                        handleError(ctx, e);
                    }

                    @Override
                    public void onSuccess(ServiceResponse<StorageAccountKeys> result) {
                        logInfo("Retrieved the storage account keys for storage account [%s] successfully.",
                                ctx.storageAccountName);
                        StorageAccountKeys keys = result.getBody();
                        String key1 = keys.getKey1();
                        String key2 = keys.getKey2();

                        AuthCredentialsServiceState storageAuth = new AuthCredentialsServiceState();
                        storageAuth.customProperties = new HashMap<>();
                        storageAuth.customProperties.put(AZURE_STORAGE_ACCOUNT_KEY1, key1);
                        storageAuth.customProperties.put(AZURE_STORAGE_ACCOUNT_KEY2, key2);
                        Operation patchStorageDescriptionWithKeys = Operation
                                .createPost(getHost(), AuthCredentialsService.FACTORY_LINK)
                                .setBody(storageAuth).setCompletion((o, e) -> {
                                    if (e != null) {
                                        handleError(ctx, e);
                                        return;
                                    }
                                    AuthCredentialsServiceState resultAuth = o.getBody(
                                            AuthCredentialsServiceState.class);
                                    ctx.storageDescription.authCredentialsLink = resultAuth.documentSelfLink;
                                    Operation patch = Operation
                                            .createPatch(UriUtils.buildUri(getHost(),
                                                    ctx.storageDescription.documentSelfLink))
                                            .setBody(ctx.storageDescription)
                                            .setCompletion(((completedOp, failure) -> {
                                                if (failure != null) {
                                                    handleError(ctx, failure);
                                                    return;
                                                }
                                                logFine("Patched the storage description successfully.");
                                                handleAllocation(ctx, nextStage);
                                            }));
                                    sendRequest(patch);
                                });
                        sendRequest(patchStorageDescriptionWithKeys);
                    }
                });
    }

    private String getVHDName(String vmName) {
        return vmName + BOOT_DISK_SUFFIX;
    }

    private ImageReference getImageReference(String imageId) {
        String[] imageIdParts = imageId.split(":");
        if (imageIdParts.length != 4) {
            throw new IllegalArgumentException(
                    "Azure image id should be of the format <publisher>:<offer>:<sku>:<version>");
        }

        ImageReference imageReference = new ImageReference();
        imageReference.setPublisher(imageIdParts[0]);
        imageReference.setOffer(imageIdParts[1]);
        imageReference.setSku(imageIdParts[2]);
        imageReference.setVersion(imageIdParts[3]);

        return imageReference;
    }

    /**
     * This method tries to detect a subscription registration error and register subscription for
     * given namespace. Otherwise the fallback is to transition to error state.
     */
    private void handleSubscriptionError(AzureAllocationContext ctx, String namespace,
            Throwable e) {
        if (e instanceof CloudException) {
            CloudException ce = (CloudException) e;
            CloudError body = ce.getBody();
            if (body != null) {
                String code = body.getCode();
                if (MISSING_SUBSCRIPTION_CODE.equals(code)) {
                    registerSubscription(ctx, namespace);
                    return;
                }
            }
        }
        handleError(ctx, e);
    }

    private void registerSubscription(AzureAllocationContext ctx, String namespace) {
        ResourceManagementClient client = getResourceManagementClient(ctx);
        client.getProvidersOperations().registerAsync(namespace,
                new AzureAsyncCallback<Provider>() {
                    @Override
                    public void onError(Throwable e) {
                        handleError(ctx, e);
                    }

                    @Override
                    public void onSuccess(ServiceResponse<Provider> result) {
                        Provider provider = result.getBody();
                        String registrationState = provider.getRegistrationState();
                        if (!PROVIDER_REGISTRED_STATE.equalsIgnoreCase(registrationState)) {
                            logInfo("%s namespace registration in %s state", namespace,
                                    registrationState);
                            long retryExpiration = Utils.getNowMicrosUtc()
                                    + DEFAULT_EXPIRATION_INTERVAL_MICROS;
                            getSubscriptionState(ctx, namespace, retryExpiration);
                            return;
                        }
                        logInfo("Successfully registered namespace [%s]", provider.getNamespace());
                        handleAllocation(ctx);
                    }
                });
    }

    private void getSubscriptionState(AzureAllocationContext ctx,
            String namespace, long retryExpiration) {
        if (Utils.getNowMicrosUtc() > retryExpiration) {
            String msg = String
                    .format("Subscription for %s namespace did not reach %s state", namespace,
                            PROVIDER_REGISTRED_STATE);
            handleError(ctx, new RuntimeException(msg));
            return;
        }

        ResourceManagementClient client = getResourceManagementClient(ctx);

        getHost().schedule(
                () -> client.getProvidersOperations().getAsync(namespace,
                        new AzureAsyncCallback<Provider>() {
                            @Override
                            public void onError(Throwable e) {
                                handleError(ctx, e);
                            }

                            @Override
                            public void onSuccess(ServiceResponse<Provider> result) {
                                Provider provider = result.getBody();
                                String registrationState = provider.getRegistrationState();
                                if (!PROVIDER_REGISTRED_STATE.equalsIgnoreCase(registrationState)) {
                                    logInfo("%s namespace registration in %s state",
                                            namespace, registrationState);
                                    getSubscriptionState(ctx, namespace, retryExpiration);
                                    return;
                                }
                                logInfo("Successfully registered namespace [%s]",
                                        provider.getNamespace());
                                handleAllocation(ctx);
                            }
                        }),
                RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void getChildAuth(AzureAllocationContext ctx, AzureStages next) {
        if (ctx.child.description.authCredentialsLink == null) {
            handleError(ctx, new IllegalStateException("Auth information for compute is required"));
            return;
        }

        String childAuthLink = ctx.child.description.authCredentialsLink;
        Consumer<Operation> onSuccess = (op) -> {
            ctx.childAuth = op.getBody(AuthCredentialsService.AuthCredentialsServiceState.class);
            handleAllocation(ctx, next);
        };
        AdapterUtils.getServiceState(this, childAuthLink, onSuccess, getFailureConsumer(ctx));
    }

    private void getParentAuth(AzureAllocationContext ctx, AzureStages next) {
        String parentAuthLink;
        if (ctx.computeRequest.requestType == ComputeInstanceRequest.InstanceRequestType.VALIDATE_CREDENTIALS) {
            parentAuthLink = ctx.computeRequest.authCredentialsLink;
        } else {
            parentAuthLink = ctx.parent.description.authCredentialsLink;
        }
        Consumer<Operation> onSuccess = (op) -> {
            ctx.parentAuth = op.getBody(AuthCredentialsService.AuthCredentialsServiceState.class);
            handleAllocation(ctx, next);
        };
        AdapterUtils.getServiceState(this, parentAuthLink, onSuccess, getFailureConsumer(ctx));
    }

    /*
     * method will be responsible for getting the compute description for the requested resource and
     * then passing to the next step
     */
    private void getVMDescription(AzureAllocationContext ctx, AzureStages next) {
        Consumer<Operation> onSuccess = (op) -> {
            ctx.child = op.getBody(ComputeService.ComputeStateWithDescription.class);
            ctx.vmName = ctx.child.name != null ? ctx.child.name : ctx.child.id;

            logInfo(ctx.child.id);
            handleAllocation(ctx, next);
        };
        URI computeUri = UriUtils.extendUriWithQuery(
                ctx.computeRequest.resourceReference, UriUtils.URI_PARAM_ODATA_EXPAND,
                Boolean.TRUE.toString());
        AdapterUtils.getServiceState(this, computeUri, onSuccess, getFailureConsumer(ctx));
    }

    /*
     * Method will get the service for the identified link
     */
    private void getParentDescription(AzureAllocationContext ctx, AzureStages next) {
        Consumer<Operation> onSuccess = (op) -> {
            ctx.parent = op.getBody(ComputeService.ComputeStateWithDescription.class);
            handleAllocation(ctx, next);
        };
        URI parentURI = ComputeStateWithDescription
                .buildUri(UriUtils.buildUri(getHost(), ctx.child.parentLink));
        AdapterUtils.getServiceState(this, parentURI, onSuccess, getFailureConsumer(ctx));
    }

    private void getNicStates(AzureAllocationContext ctx, AzureStages next) {
        if (ctx.child.networkInterfaceLinks == null
                || ctx.child.networkInterfaceLinks.size() == 0) {
            handleAllocation(ctx, next);
            return;
        }

        List<Operation> getNICsOps = new ArrayList<>();

        for (String nicStateLink : ctx.child.networkInterfaceLinks) {

            NicAllocationContext nicCtx = new NicAllocationContext();

            ctx.nics.add(nicCtx);

            URI nicStateUri = NetworkInterfaceStateWithDescription
                    .buildUri(UriUtils.buildUri(getHost(), nicStateLink));

            Operation getNicOp = Operation.createGet(nicStateUri).setCompletion(
                    (op, exc) -> {
                        // Handle SUCCESS; error is handled by the join handler.
                        if (exc == null) {
                            nicCtx.nicStateWithDesc = op
                                    .getBody(NetworkInterfaceStateWithDescription.class);
                        }
                    });

            getNICsOps.add(getNicOp);
        }

        OperationJoin operationJoin = OperationJoin.create(getNICsOps)
                .setCompletion(
                        (ops, excs) -> {
                            if (excs != null) {
                                handleError(ctx, new IllegalStateException(
                                        "Error getting network interface states."));
                                return;
                            }
                            handleAllocation(ctx, next);
                        });
        operationJoin.sendWith(this);
    }

    /**
     * Get {@link NetworkState}s containing the {@link SubnetState}s
     * {@link NetworkInterfaceState#subnetLink assigned} to the NICs.
     *
     * @see #getSubnetStates(AzureAllocationContext, AzureStages)
     */
    private void getNetworkStates(AzureAllocationContext ctx, AzureStages next) {
        if (ctx.nics.isEmpty()) {
            handleAllocation(ctx, next);
            return;
        }

        Stream<Operation> getNetworksOps = ctx.nics.stream()
                .map(nicCtx -> Operation.createGet(this.getHost(), nicCtx.subnetState.networkLink)
                        .setCompletion((op, ex) -> {
                            if (ex == null) {
                                nicCtx.networkState = op.getBody(NetworkState.class);
                            }
                        }));

        OperationJoin operationJoin = OperationJoin.create(getNetworksOps)
                .setCompletion((ops, excs) -> {
                    if (excs != null) {
                        handleError(ctx, new IllegalStateException(
                                "Error getting network states."));
                        return;
                    }
                    handleAllocation(ctx, next);
                });
        operationJoin.sendWith(this);
    }

    /**
     * Get {@link SubnetState}s {@link NetworkInterfaceState#subnetLink assigned} to the NICs.
     */
    private void getSubnetStates(AzureAllocationContext ctx, AzureStages next) {
        if (ctx.nics.isEmpty()) {
            handleAllocation(ctx, next);
            return;
        }

        Stream<Operation> getSubnetsOps = ctx.nics.stream()
                .map(nicCtx -> Operation
                        .createGet(this.getHost(), nicCtx.nicStateWithDesc.subnetLink)
                        .setCompletion((op, ex) -> {
                            if (ex == null) {
                                nicCtx.subnetState = op.getBody(SubnetState.class);
                            }
                        }));

        OperationJoin operationJoin = OperationJoin.create(getSubnetsOps)
                .setCompletion((ops, excs) -> {
                    if (excs != null) {
                        handleError(ctx, new IllegalStateException(
                                "Error getting subnet states."));
                        return;
                    }
                    handleAllocation(ctx, next);
                });
        operationJoin.sendWith(this);
    }

    private Consumer<Throwable> getFailureConsumer(AzureAllocationContext ctx) {
        return (t) -> handleError(ctx, t);
    }

    private Retrofit.Builder getRetrofitBuilder() {
        Retrofit.Builder builder = new Retrofit.Builder();
        builder.callbackExecutor(this.executorService);
        return builder;
    }

    private String generateName(String prefix) {
        return prefix + randomString(5);
    }

    private String randomString(int length) {
        Random random = new Random();
        StringBuilder stringBuilder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            stringBuilder.append((char) ('a' + random.nextInt(26)));
        }
        return stringBuilder.toString();
    }

    private ResourceManagementClient getResourceManagementClient(AzureAllocationContext ctx) {
        if (ctx.resourceManagementClient == null) {
            ResourceManagementClient client = new ResourceManagementClientImpl(
                    AzureConstants.BASE_URI, ctx.credentials, ctx.clientBuilder,
                    getRetrofitBuilder());
            client.setSubscriptionId(ctx.parentAuth.userLink);
            ctx.resourceManagementClient = client;
        }
        return ctx.resourceManagementClient;
    }

    private NetworkManagementClient getNetworkManagementClient(AzureAllocationContext ctx) {
        if (ctx.networkManagementClient == null) {
            NetworkManagementClient client = new NetworkManagementClientImpl(
                    AzureConstants.BASE_URI, ctx.credentials, ctx.clientBuilder,
                    getRetrofitBuilder());
            client.setSubscriptionId(ctx.parentAuth.userLink);
            ctx.networkManagementClient = client;
        }
        return ctx.networkManagementClient;
    }

    private StorageManagementClient getStorageManagementClient(AzureAllocationContext ctx) {
        if (ctx.storageManagementClient == null) {
            StorageManagementClient client = new StorageManagementClientImpl(
                    AzureConstants.BASE_URI, ctx.credentials, ctx.clientBuilder,
                    getRetrofitBuilder());
            client.setSubscriptionId(ctx.parentAuth.userLink);
            ctx.storageManagementClient = client;
        }
        return ctx.storageManagementClient;
    }

    private ComputeManagementClient getComputeManagementClient(AzureAllocationContext ctx) {
        if (ctx.computeManagementClient == null) {
            ComputeManagementClient client = new ComputeManagementClientImpl(
                    AzureConstants.BASE_URI, ctx.credentials, ctx.clientBuilder,
                    getRetrofitBuilder());
            client.setSubscriptionId(ctx.parentAuth.userLink);
            ctx.computeManagementClient = client;
        }
        return ctx.computeManagementClient;
    }

    /**
     * Method will retrieve disks for targeted image
     */
    private void getVMDisks(AzureAllocationContext ctx, AzureStages nextStage) {
        if (ctx.child.diskLinks == null || ctx.child.diskLinks.size() == 0) {
            handleError(ctx, new IllegalStateException("a minimum of 1 disk is required"));
            return;
        }
        Collection<Operation> operations = new ArrayList<>();
        // iterate thru disks and create operations
        operations.addAll(ctx.child.diskLinks.stream()
                .map(disk -> Operation.createGet(UriUtils.buildUri(this.getHost(), disk)))
                .collect(Collectors.toList()));

        OperationJoin operationJoin = OperationJoin.create(operations)
                .setCompletion(
                        (ops, exc) -> {
                            if (exc != null) {
                                handleError(ctx, new IllegalStateException(
                                        "Error getting disk information"));
                                return;
                            }

                            ctx.childDisks = new ArrayList<>();
                            for (Operation op : ops.values()) {
                                DiskState disk = op.getBody(DiskState.class);

                                // We treat the first disk in the boot order as the boot disk.
                                if (disk.bootOrder == 1) {
                                    if (ctx.bootDisk != null) {
                                        handleError(ctx, new IllegalStateException(
                                                "Only 1 boot disk is allowed"));
                                        return;
                                    }

                                    ctx.bootDisk = disk;
                                } else {
                                    ctx.childDisks.add(disk);
                                }
                            }

                            if (ctx.bootDisk == null) {
                                handleError(ctx,
                                        new IllegalStateException("Boot disk is required"));
                                return;
                            }

                            handleAllocation(ctx, nextStage);
                        });
        operationJoin.sendWith(this);
    }

    /**
     * Differentiate between Windows and Linux Images
     *
     */
    private void differentiateVMImages(AzureAllocationContext ctx, AzureStages nextStage) {
        DiskState bootDisk = ctx.bootDisk;
        if (bootDisk == null) {
            handleError(ctx, new IllegalStateException("Azure bootDisk not specified"));
            return;
        }
        URI imageId = ctx.bootDisk.sourceImageReference;
        if (imageId == null) {
            handleError(ctx, new IllegalStateException("Azure image reference not specified"));
            return;
        }
        ImageReference imageReference = getImageReference(
                ctx.bootDisk.sourceImageReference.toString());

        if (AzureConstants.AZURE_URN_VERSION_LATEST.equalsIgnoreCase(imageReference.getVersion())) {
            logFine("Getting the latest version for %s:%s:%s", imageReference.getPublisher(),
                    imageReference.getOffer(), imageReference.getSku());
            // Get the latest version based on the provided publisher, offer and SKU (filter = null,
            // top = 1, orderBy = name desc)
            getComputeManagementClient(ctx).getVirtualMachineImagesOperations().listAsync(
                    ctx.resourceGroup.getLocation(), imageReference.getPublisher(),
                    imageReference.getOffer(), imageReference.getSku(),
                    null, 1, AzureConstants.ORDER_BY_VM_IMAGE_RESOURCE_NAME_DESC,
                    new AzureAsyncCallback<List<VirtualMachineImageResource>>() {

                        @Override
                        public void onError(Throwable e) {
                            handleError(ctx, new IllegalStateException(e.getLocalizedMessage()));
                            return;
                        }

                        @Override
                        public void onSuccess(
                                ServiceResponse<List<VirtualMachineImageResource>> result) {
                            List<VirtualMachineImageResource> resource = result.getBody();
                            if (resource == null || resource.get(0) == null) {
                                handleError(ctx,
                                        new IllegalStateException("No latest version found"));
                                return;
                            }
                            // Get the first object because the request asks only for one object
                            // (top = 1)
                            // We don't care what version we use to get the VirtualMachineImage
                            String version = resource.get(0).getName();
                            getVirtualMachineImage(ctx, nextStage, version, imageReference);
                        }
                    });
        } else {
            getVirtualMachineImage(ctx, nextStage, imageReference.getVersion(), imageReference);
        }
    }

    /**
     * Get the VirtualMachineImage using publisher, offer, SKU and version.
     */
    private void getVirtualMachineImage(AzureAllocationContext ctx,
            AzureStages nextStage, String version, ImageReference imageReference) {

        logFine("URN of the OS - %s:%s:%s:%s", imageReference.getPublisher(),
                imageReference.getOffer(), imageReference.getSku(), version);
        getComputeManagementClient(ctx).getVirtualMachineImagesOperations().getAsync(
                ctx.resourceGroup.getLocation(), imageReference.getPublisher(),
                imageReference.getOffer(), imageReference.getSku(), version,
                new AzureAsyncCallback<VirtualMachineImage>() {
                    @Override
                    public void onError(Throwable e) {
                        handleError(ctx, new IllegalStateException(e.getLocalizedMessage()));
                        return;
                    }

                    @Override
                    public void onSuccess(ServiceResponse<VirtualMachineImage> result) {
                        VirtualMachineImage image = result.getBody();
                        if (image == null || image.getOsDiskImage() == null) {
                            handleError(ctx, new IllegalStateException("OS Disk Image not found."));
                            return;
                        }
                        // Get the operating system family
                        ctx.operatingSystemFamily = image.getOsDiskImage().getOperatingSystem();
                        logFine("Retrieved the operating system family - %s",
                                ctx.operatingSystemFamily);
                        ctx.imageReference = imageReference;
                        handleAllocation(ctx, nextStage);
                    }
                });
    }

    private void enableMonitoring(AzureAllocationContext ctx, AzureStages nextStage) {
        Operation readFile = Operation.createGet(null).setCompletion((o, e) -> {
            if (e != null) {
                handleError(ctx, e);
                return;
            }
            AzureDiagnosticSettings azureDiagnosticSettings = o
                    .getBody(AzureDiagnosticSettings.class);
            String vmName = ctx.vmName;
            String azureInstanceId = ctx.vmId;
            String storageAccountName = ctx.storageAccountName;

            // Replace the resourceId and storageAccount keys with correct values
            azureDiagnosticSettings.getProperties()
                    .getPublicConfiguration()
                    .getDiagnosticMonitorConfiguration()
                    .getMetrics()
                    .setResourceId(azureInstanceId);
            azureDiagnosticSettings.getProperties()
                    .getPublicConfiguration()
                    .setStorageAccount(storageAccountName);

            ApplicationTokenCredentials credentials = ctx.credentials;

            URI uri = UriUtils.extendUriWithQuery(
                    UriUtils.buildUri(UriUtils.buildUri(AzureConstants.BASE_URI_FOR_REST),
                            azureInstanceId, AzureConstants.DIAGNOSTIC_SETTING_ENDPOINT,
                            AzureConstants.DIAGNOSTIC_SETTING_AGENT),
                    AzureConstants.QUERY_PARAM_API_VERSION,
                    AzureConstants.DIAGNOSTIC_SETTING_API_VERSION);

            Operation operation = Operation.createPut(uri);
            operation.setBody(azureDiagnosticSettings);
            operation.addRequestHeader(Operation.ACCEPT_HEADER,
                    Operation.MEDIA_TYPE_APPLICATION_JSON);
            operation.addRequestHeader(Operation.CONTENT_TYPE_HEADER,
                    Operation.MEDIA_TYPE_APPLICATION_JSON);
            try {
                operation.addRequestHeader(Operation.AUTHORIZATION_HEADER,
                        AzureConstants.AUTH_HEADER_BEARER_PREFIX + credentials.getToken());
            } catch (Exception ex) {
                handleError(ctx, ex);
                return;
            }

            logInfo("Enabling monitoring on the VM [%s]", vmName);
            operation.setCompletion((op, er) -> {
                if (er != null) {
                    handleError(ctx, er);
                    return;
                }

                logInfo("Successfully enabled monitoring on the VM [%s]", vmName);
                handleAllocation(ctx, nextStage);
            });
            sendRequest(operation);
        });

        String fileUri = getClass().getResource(AzureConstants.DIAGNOSTIC_SETTINGS_JSON_FILE_NAME)
                .getFile();
        File jsonPayloadFile = new File(fileUri);
        try {
            FileUtils.readFileAndComplete(readFile, jsonPayloadFile);
        } catch (Exception e) {
            handleError(ctx, e);
        }
    }

    private String getResourceGroupName(AzureAllocationContext ctx) {
        String resourceGroupName = null;
        if (ctx.child.customProperties != null) {
            resourceGroupName = ctx.child.customProperties.get(RESOURCE_GROUP_NAME);
        }

        if (resourceGroupName == null && ctx.child.description.customProperties != null) {
            resourceGroupName = ctx.child.description.customProperties.get(RESOURCE_GROUP_NAME);
        }

        if (resourceGroupName == null || resourceGroupName.isEmpty()) {
            resourceGroupName = DEFAULT_GROUP_PREFIX + String.valueOf(System.currentTimeMillis());
        }
        return resourceGroupName;
    }

    /**
     * Use this Azure callback in case of multiple async calls. It transitions to next stage upon
     * FIRST error. Subsequent calls (either success or failure) are just ignored.
     */
    private abstract class FailFastAzureAsyncCallback<T> extends AzureAsyncCallback<T> {

        final AzureAllocationContext ctx;
        final AzureStages nextStage;

        final AtomicInteger numberOfCalls;
        final AtomicBoolean hasAnyFailed;

        final String msg;

        FailFastAzureAsyncCallback(
                AzureAllocationContext ctx,
                AzureStages nextStage,
                AtomicInteger numberOfCalls,
                AtomicBoolean hasAnyFailed,
                String message) {
            this.ctx = ctx;
            this.nextStage = nextStage;
            this.numberOfCalls = numberOfCalls;
            this.hasAnyFailed = hasAnyFailed;
            this.msg = message;

            logInfo(this.msg + ": STARTED");
        }

        @Override
        public final void onError(Throwable e) {

            e = new IllegalStateException(this.msg + ": FAILED", e);

            if (this.hasAnyFailed.compareAndSet(false, true)) {
                // Check whether this is the first failure and proceed to next stage.
                // i.e. fail-fast on batch operations.
                this.handleFailure(e);
            } else {
                // Any subsequent failure is just logged.
                logSevere(e);
            }
        }

        /**
         * Hook to be implemented by descendants to handle failed Azure call.
         *
         * <p>
         * Default error handler delegates to
         * {@link AzureInstanceService#handleError(AzureAllocationContext, Throwable)}.
         */
        void handleFailure(Throwable e) {
            AzureInstanceService.this.handleError(this.ctx, e);
        }

        @Override
        public final void onSuccess(ServiceResponse<T> result) {
            if (this.hasAnyFailed.get()) {
                logInfo(this.msg + ": SUCCESS. Still batch calls has failed so skip this result.");
                return;
            }

            logInfo(this.msg + ": SUCCESS");

            handleSuccess(result.getBody());

            if (this.numberOfCalls.decrementAndGet() == 0) {
                // Check whether all calls have succeeded and proceed to next stage.
                AzureInstanceService.this.handleAllocation(this.ctx, this.nextStage);
            }
        }

        /**
         * Core logic (implemented by descendants) handling successful Azure call.
         *
         * <p>
         * The implementation should focus on consuming the response/result. It is responsibility of
         * this class to handle transition to next stage as defined by
         * {@link AzureInstanceService#handleAllocation(AzureAllocationContext, AzureStages)}.
         */
        abstract void handleSuccess(T resultBody);

    }

    /**
     * Use this Azure callback in case of single async call. It transitions to next stage upon
     * success.
     */
    private abstract class TransitionToCallback<T> extends AzureAsyncCallback<T> {

        final AzureAllocationContext ctx;
        final AzureStages nextStage;

        final String msg;

        TransitionToCallback(
                AzureAllocationContext ctx,
                AzureStages nextStage,
                String message) {
            this.ctx = ctx;
            this.nextStage = nextStage;
            this.msg = message;

            logInfo(this.msg + ": STARTED");
        }

        @Override
        public final void onError(Throwable e) {
            handleFailure(new IllegalStateException(this.msg + ": FAILED", e));
        }

        /**
         * Hook that might be implemented by descendants to handle failed Azure call.
         *
         * <p>
         * Default error handling delegates to
         * {@link AzureInstanceService#handleError(AzureAllocationContext, Throwable)}.
         */
        void handleFailure(Throwable e) {
            AzureInstanceService.this.handleError(this.ctx, e);
        }

        @Override
        public final void onSuccess(ServiceResponse<T> result) {
            logInfo(this.msg + ": SUCCESS");

            // First delegate to descendants to process result body
            CompletionStage<T> handleSuccess = handleSuccess(result.getBody());

            // Then transition upon completion
            handleSuccess.whenComplete((body, exc) -> {
                if (exc != null) {
                    handleFailure(exc);
                } else {
                    transition();
                }
            });
        }

        /**
         * Hook to be implemented by descendants to handle successful Azure call.
         *
         * <p>
         * The implementation should focus on consuming the result. It is responsibility of this
         * class to handle transition to next stage as defined by
         * {@link AzureInstanceService#handleAllocation(AzureAllocationContext, AzureStages)}.
         */
        abstract CompletionStage<T> handleSuccess(T resultBody);

        /**
         * Transition to the next stage of AzureInstanceService state machine once Azure call is
         * complete.
         */
        private void transition() {
            AzureInstanceService.this.handleAllocation(this.ctx, this.nextStage);
        }
    }

    /**
     * Use this Azure callback in case of Azure provisioning call (such as create vNet, NIC, etc.).
     *
     * <p>
     * The provisioning state of resources returned by Azure 'create' call is 'Updating'. This
     * callback is responsible to wait for resource provisioning state to change to 'Succeeded'.
     */
    private abstract class ProvisioningCallback<T> extends TransitionToCallback<T> {

        private static final String WAIT_PROVISIONING_TO_SUCCEED_MSG = "WAIT provisioning to succeed";

        private int numberOfWaits = 0;
        private CompletableFuture<T> waitProvisioningToSucceed = new CompletableFuture<>();

        ProvisioningCallback(AzureAllocationContext ctx, AzureStages nextStage, String msg) {
            super(ctx, nextStage, msg);
        }

        /**
         * Provides 'wait-for-provisioning-to-succeed' logic prior forwarding to actual resource
         * create {@link #handleProvisioningSucceeded(Object) callback}.
         */
        @Override
        final CompletionStage<T> handleSuccess(T body) {

            return waitProvisioningToSucceed(body).thenCompose(this::handleProvisioningSucceeded);
        }

        /**
         * Hook to be implemented by descendants to handle 'Succeeded' Azure resource provisioning.
         * Since implementations might decide to trigger/initiate sync operation they are required
         * to return {@link CompletionStage} to track its completion.
         *
         * <p>
         * This call is introduced by analogy with {@link #handleSuccess(Object)}.
         */
        abstract CompletionStage<T> handleProvisioningSucceeded(T body);

        /**
         * By design Azure resources do not have generic 'provisioningState' getter, so we enforce
         * descendants to provide us with its value.
         *
         * <p>
         * NOTE: Might be done through reflection. For now keep it simple.
         */
        abstract String getProvisioningState(T body);

        /**
         * This Runnable abstracts/models the Azure 'get resource' call used to get/check resource
         * provisioning state.
         *
         * @param checkProvisioningStateCallback
         *            The special callback that should be used while creating the Azure 'get
         *            resource' call.
         */
        abstract Runnable checkProvisioningStateCall(
                ServiceCallback<T> checkProvisioningStateCallback);

        /**
         * The core logic that waits for provisioning to succeed. It polls periodically for resource
         * provisioning state.
         */
        private CompletionStage<T> waitProvisioningToSucceed(T body) {

            String provisioningState = getProvisioningState(body);

            logInfo(this.msg + ": provisioningState = " + provisioningState);

            if (PROVISIONING_STATE_SUCCEEDED.equalsIgnoreCase(provisioningState)) {

                // Resource 'provisioningState' has changed finally to 'Succeeded'
                // Completes 'waitProvisioningToSucceed' task with success
                this.waitProvisioningToSucceed.complete(body);

            } else if (this.numberOfWaits > WaitProvisiningToSucceed.MAX_WAITS) {

                // Max number of re-tries has reached.
                // Completes 'waitProvisioningToSucceed' task with exception.
                this.waitProvisioningToSucceed.completeExceptionally(new IllegalStateException(
                        WAIT_PROVISIONING_TO_SUCCEED_MSG + ": max waits exceeded"));

            } else {

                // Retry one more time

                this.numberOfWaits++;

                logInfo("%s: [%s] %s", this.msg, this.numberOfWaits,
                        WAIT_PROVISIONING_TO_SUCCEED_MSG);

                getHost().schedule(
                        checkProvisioningStateCall(new CheckProvisioningStateCallback()),
                        WaitProvisiningToSucceed.INTERVAL,
                        WaitProvisiningToSucceed.TIMEUNIT);
            }

            return this.waitProvisioningToSucceed;
        }

        /**
         * Specialization of Azure callback used by {@link ProvisioningCallback} to handle
         * 'get/check resource state' call.
         */
        private class CheckProvisioningStateCallback extends AzureAsyncCallback<T> {

            @Override
            public void onError(Throwable e) {
                e = new IllegalStateException(WAIT_PROVISIONING_TO_SUCCEED_MSG + ": FAILED", e);

                ProvisioningCallback.this.waitProvisioningToSucceed.completeExceptionally(e);
            }

            @Override
            public void onSuccess(ServiceResponse<T> result) {
                ProvisioningCallback.this.waitProvisioningToSucceed(result.getBody());
            }
        }
    }

}
