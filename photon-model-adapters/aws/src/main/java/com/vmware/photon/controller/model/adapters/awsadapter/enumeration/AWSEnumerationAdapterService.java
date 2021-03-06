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

package com.vmware.photon.controller.model.adapters.awsadapter.enumeration;

import java.util.ArrayList;
import java.util.List;

import com.vmware.photon.controller.model.adapterapi.ComputeEnumerateResourceRequest;
import com.vmware.photon.controller.model.adapters.awsadapter.AWSUriPaths;
import com.vmware.photon.controller.model.adapters.awsadapter.BaseAwsContext;
import com.vmware.photon.controller.model.adapters.awsadapter.BaseAwsContext.BaseAwsStages;
import com.vmware.photon.controller.model.adapters.util.AdapterUtils;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeStateWithDescription;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.StatelessService;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.AuthCredentialsService;

/**
 * Enumeration Adapter for the Amazon Web Services. Performs a list call to the AWS API and
 * reconciles the local state with the state on the remote system. It lists the instances on the
 * remote system. Compares those with the local system and creates the instances that are missing in
 * the local system.
 *
 */
public class AWSEnumerationAdapterService extends StatelessService {

    public static final String SELF_LINK = AWSUriPaths.AWS_ENUMERATION_ADAPTER;

    public AWSEnumerationAdapterService() {
        super.toggleOption(ServiceOption.INSTRUMENTATION, true);
    }

    public static enum AWSEnumerationStages {
        KICKOFF_ENUMERATION,
        PATCH_COMPLETION,
        ERROR
    }

    /**
     * Holds the compute resource request and other data that is required by the helper flows to
     * perform resource enumeration on AWS.
     *
     */
    public static class AWSEnumerationRequest {
        public ComputeEnumerateResourceRequest computeEnumerateResourceRequest;
        public AuthCredentialsService.AuthCredentialsServiceState parentAuth;
        public ComputeStateWithDescription parentCompute;

        public AWSEnumerationRequest(ComputeEnumerateResourceRequest request,
                AuthCredentialsService.AuthCredentialsServiceState parentAuth,
                ComputeStateWithDescription parentCompute) {
            this.computeEnumerateResourceRequest = request;
            this.parentAuth = parentAuth;
            this.parentCompute = parentCompute;

        }
    }

    /**
     * The enumeration service context needed to spawn off control to the creation and deletion
     * adapters for AWS.
     */
    public static class EnumerationContext extends BaseAwsContext {

        public ComputeEnumerateResourceRequest computeEnumerationRequest;
        public AWSEnumerationStages stage;
        public List<Operation> enumerationOperations;
        public Throwable error;
        public Operation awsAdapterOperation;

        public EnumerationContext(Service service, ComputeEnumerateResourceRequest request,
                Operation op) {
            super(service, request.resourceReference);
            this.computeEnumerationRequest = request;
            this.stage = AWSEnumerationStages.KICKOFF_ENUMERATION;
            this.enumerationOperations = new ArrayList<Operation>();
            this.awsAdapterOperation = op;
        }
    }

    @Override
    public void handleStart(Operation startPost) {
        startHelperServices(startPost);
        super.handleStart(startPost);
    }

    @Override
    public void handlePatch(Operation op) {
        setOperationHandlerInvokeTimeStat(op);
        if (!op.hasBody()) {
            op.fail(new IllegalArgumentException("body is required"));
            return;
        }
        op.complete();
        ComputeEnumerateResourceRequest request = op.getBody(ComputeEnumerateResourceRequest.class);

        AdapterUtils.validateEnumRequest(request);
        if (request.isMockRequest) {
            // patch status to parent task
            AdapterUtils.sendPatchToEnumerationTask(this, request.taskReference);
            return;
        }
        BaseAwsContext.populateContextThen(new EnumerationContext(this, request, op),
                BaseAwsStages.PARENTDESC, (context, t) -> {
                    if (t != null) {
                        context.error = t;
                        context.stage = AWSEnumerationStages.ERROR;
                    }
                    handleEnumerationRequest(context);
                });
    }

    /**
     * Starts the related services for the Enumeration Service
     */
    public void startHelperServices(Operation startPost) {
        Operation patchAWSEnumerationCreationService = Operation
                .createPatch(this.getHost(), AWSEnumerationAndCreationAdapterService.SELF_LINK)
                .setReferer(this.getUri());

        Operation patchAWSEnumerationDeletionService = Operation
                .createPatch(this.getHost(), AWSEnumerationAndDeletionAdapterService.SELF_LINK)
                .setReferer(this.getUri());

        Operation patchAWSStorageEnumerationService = Operation.createPatch(this.getHost(),
                AWSBlockStorageEnumerationAdapterService.SELF_LINK)
                .setReferer(this.getUri());

        this.getHost().startService(patchAWSEnumerationCreationService,
                new AWSEnumerationAndCreationAdapterService());
        this.getHost().startService(patchAWSEnumerationDeletionService,
                new AWSEnumerationAndDeletionAdapterService());
        this.getHost().startService(patchAWSStorageEnumerationService,
                new AWSBlockStorageEnumerationAdapterService());

        getHost().registerForServiceAvailability((o, e) -> {
            if (e != null) {
                String message = "Failed to start up all the services related to the AWS Enumeration Adapter Service";
                this.logInfo(message);
                throw new IllegalStateException(message);
            }
            this.logInfo(
                    "Successfully started up all the services related to the AWS Enumeration Adapter Service");
        }, AWSEnumerationAndCreationAdapterService.SELF_LINK,
                AWSEnumerationAndDeletionAdapterService.SELF_LINK,
                AWSBlockStorageEnumerationAdapterService.SELF_LINK);
    }

    /**
     * Creates operations for the creation and deletion adapter services and spawns them off in
     * parallel
     *
     */
    public void handleEnumerationRequest(EnumerationContext aws) {
        switch (aws.stage) {
        case KICKOFF_ENUMERATION:
            kickOffEnumerationWorkFlows(aws, AWSEnumerationStages.PATCH_COMPLETION);
            break;
        case PATCH_COMPLETION:
            setOperationDurationStat(aws.awsAdapterOperation);
            AdapterUtils.sendPatchToEnumerationTask(this,
                    aws.computeEnumerationRequest.taskReference);
            break;
        case ERROR:
            AdapterUtils.sendFailurePatchToEnumerationTask(this,
                    aws.computeEnumerationRequest.taskReference, aws.error);
            break;
        default:
            logSevere("Unknown AWS enumeration stage %s ", aws.stage.toString());
            aws.error = new Exception("Unknown AWS enumeration stage");
            AdapterUtils.sendFailurePatchToEnumerationTask(this,
                    aws.computeEnumerationRequest.taskReference, aws.error);
            break;

        }
    }

    /**
     * Kicks off the enumeration flows for creation and deletion.
     */
    public void kickOffEnumerationWorkFlows(EnumerationContext context,
            AWSEnumerationStages next) {
        AWSEnumerationRequest awsEnumerationRequest = new AWSEnumerationRequest(
                context.computeEnumerationRequest, context.parentAuth,
                context.parent);

        Operation patchAWSCreationAdapterService = Operation
                .createPatch(this, AWSEnumerationAndCreationAdapterService.SELF_LINK)
                .setBody(awsEnumerationRequest)
                .setReferer(this.getHost().getUri());

        Operation patchAWSDeletionAdapterService = Operation
                .createPatch(this,
                        AWSEnumerationAndDeletionAdapterService.SELF_LINK)
                .setBody(awsEnumerationRequest)
                .setReferer(getHost().getUri());

        Operation patchAWSStorageAdapterService = Operation
                .createPatch(this,
                        AWSBlockStorageEnumerationAdapterService.SELF_LINK)
                .setBody(awsEnumerationRequest)
                .setReferer(getHost().getUri());

        context.enumerationOperations.add(patchAWSCreationAdapterService);
        context.enumerationOperations.add(patchAWSDeletionAdapterService);
        context.enumerationOperations.add(patchAWSStorageAdapterService);

        if (context.enumerationOperations == null || context.enumerationOperations.size() == 0) {
            logInfo("There are no enumeration tasks to run.");
            context.stage = next;
            handleEnumerationRequest(context);
            return;
        }
        OperationJoin.JoinedCompletionHandler joinCompletion = (ox,
                exc) -> {
            if (exc != null) {
                logSevere(
                        "Error kicking off the enumeration workflows for AWS. %s",
                        Utils.toString(exc));
                AdapterUtils.sendFailurePatchToEnumerationTask(this,
                        context.computeEnumerationRequest.taskReference,
                        exc.values().iterator().next());

            }
            logInfo("Successfully completed the enumeration workflows for creation, deletion of compute states and storage.");
            context.stage = next;
            handleEnumerationRequest(context);
            return;
        };
        OperationJoin joinOp = OperationJoin.create(context.enumerationOperations);
        joinOp.setCompletion(joinCompletion);
        joinOp.sendWith(getHost());
        logInfo("Kicked off enumeration creation,deletion and storage workflows for AWS");

    }
}
