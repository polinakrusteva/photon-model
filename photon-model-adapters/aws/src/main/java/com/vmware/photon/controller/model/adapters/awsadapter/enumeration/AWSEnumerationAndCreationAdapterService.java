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

import static com.vmware.photon.controller.model.adapters.awsadapter.AWSConstants.getQueryPageSize;
import static com.vmware.photon.controller.model.adapters.awsadapter.AWSUtils.getAWSNonTerminatedInstancesFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;

import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.ec2.AmazonEC2AsyncClient;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;

import com.vmware.photon.controller.model.adapterapi.ComputeEnumerateResourceRequest;
import com.vmware.photon.controller.model.adapterapi.EnumerationAction;
import com.vmware.photon.controller.model.adapters.awsadapter.AWSConstants;
import com.vmware.photon.controller.model.adapters.awsadapter.AWSUriPaths;
import com.vmware.photon.controller.model.adapters.awsadapter.enumeration.AWSComputeDescriptionCreationAdapterService.AWSComputeDescriptionCreationState;
import com.vmware.photon.controller.model.adapters.awsadapter.enumeration.AWSComputeStateCreationAdapterService.AWSComputeStateForCreation;
import com.vmware.photon.controller.model.adapters.awsadapter.enumeration.AWSEnumerationAdapterService.AWSEnumerationRequest;
import com.vmware.photon.controller.model.adapters.awsadapter.enumeration.AWSNetworkStateCreationAdapterService.AWSNetworkEnumeration;
import com.vmware.photon.controller.model.adapters.awsadapter.util.AWSClientManager;
import com.vmware.photon.controller.model.adapters.awsadapter.util.AWSClientManagerFactory;
import com.vmware.photon.controller.model.adapters.util.AdapterUtils;
import com.vmware.photon.controller.model.resources.ComputeService;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeStateWithDescription;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationContext;
import com.vmware.xenon.common.StatelessService;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.AuthCredentialsService;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.QueryTask.Query;
import com.vmware.xenon.services.common.QueryTask.Query.Occurance;
import com.vmware.xenon.services.common.QueryTask.QuerySpecification.QueryOption;
import com.vmware.xenon.services.common.ServiceUriPaths;

/**
 * Enumeration Adapter for the Amazon Web Services. Performs a list call to the AWS API and
 * reconciles the local state with the state on the remote system. It lists the instances on the
 * remote system. Compares those with the local system and creates the instances that are missing in
 * the local system.
 *
 */
public class AWSEnumerationAndCreationAdapterService extends StatelessService {
    public static final String SELF_LINK = AWSUriPaths.AWS_ENUMERATION_CREATION_ADAPTER;
    private AWSClientManager clientManager;

    public static enum AWSEnumerationCreationStages {
        CLIENT, ENUMERATE, ERROR
    }

    public static enum AWSComputeEnumerationCreationSubStage {
        QUERY_LOCAL_RESOURCES, COMPARE, CREATE_COMPUTE_DESCRIPTIONS, CREATE_COMPUTE_STATES, GET_NEXT_PAGE, ENUMERATION_STOP
    }

    private static enum AWSEnumerationRefreshSubStage {
        VCP, COMPUTE
    }

    public AWSEnumerationAndCreationAdapterService() {
        super.toggleOption(ServiceOption.INSTRUMENTATION, true);
        this.clientManager = AWSClientManagerFactory
                .getClientManager(AWSConstants.AwsClientType.EC2);
    }

    /**
     * The enumeration service context that holds all the information needed to determine the list
     * of instances that need to be represented in the system.
     */
    public static class EnumerationCreationContext {
        public AmazonEC2AsyncClient amazonEC2Client;
        public ComputeEnumerateResourceRequest computeEnumerationRequest;
        public AuthCredentialsService.AuthCredentialsServiceState parentAuth;
        public ComputeStateWithDescription parentCompute;
        public AWSEnumerationCreationStages stage;
        public AWSEnumerationRefreshSubStage refreshSubStage;
        public AWSComputeEnumerationCreationSubStage subStage;
        public Throwable error;
        public int pageNo;
        // Mapping of instance Id and the compute state in the local system.
        public Map<String, ComputeState> localAWSInstanceMap;
        public Map<String, Instance> remoteAWSInstances;
        public List<Instance> instancesToBeCreated;
        // Mappings of the instanceId ,the local compute state and the associated instance on AWS.
        public Map<String, Instance> instancesToBeUpdated;
        public Map<String, ComputeState> computeStatesToBeUpdated;
        // The request object that is populated and sent to AWS to get the list of instances.
        public DescribeInstancesRequest describeInstancesRequest;
        // The async handler that works with the response received from AWS
        public AsyncHandler<DescribeInstancesRequest, DescribeInstancesResult> resultHandler;
        // The token to use to retrieve the next page of results from AWS. This value is null when
        // there are no more results to return.
        public String nextToken;
        public Operation awsAdapterOperation;
        public Map<String, String> vpcs;

        public EnumerationCreationContext(AWSEnumerationRequest request, Operation op) {
            this.awsAdapterOperation = op;
            this.computeEnumerationRequest = request.computeEnumerateResourceRequest;
            this.parentAuth = request.parentAuth;
            this.parentCompute = request.parentCompute;
            this.localAWSInstanceMap = new ConcurrentSkipListMap<String, ComputeState>();
            this.instancesToBeUpdated = new ConcurrentSkipListMap<String, Instance>();
            this.computeStatesToBeUpdated = new ConcurrentSkipListMap<String, ComputeState>();
            this.remoteAWSInstances = new ConcurrentSkipListMap<String, Instance>();
            this.instancesToBeCreated = new ArrayList<Instance>();
            this.stage = AWSEnumerationCreationStages.CLIENT;
            this.refreshSubStage = AWSEnumerationRefreshSubStage.VCP;
            this.subStage = AWSComputeEnumerationCreationSubStage.QUERY_LOCAL_RESOURCES;
            this.pageNo = 1;
        }
    }

    @Override
    public void handleStart(Operation startPost) {
        startHelperServices(startPost);
        super.handleStart(startPost);
    }

    @Override
    public void handleStop(Operation op) {
        AWSClientManagerFactory.returnClientManager(this.clientManager,
                AWSConstants.AwsClientType.EC2);
        super.handleStop(op);
    }

    @Override
    public void handlePatch(Operation op) {
        setOperationHandlerInvokeTimeStat(op);
        if (!op.hasBody()) {
            op.fail(new IllegalArgumentException("body is required"));
            return;
        }
        EnumerationCreationContext awsEnumerationContext = new EnumerationCreationContext(
                op.getBody(AWSEnumerationRequest.class), op);
        if (awsEnumerationContext.computeEnumerationRequest.isMockRequest) {
            // patch status to parent task
            AdapterUtils.sendPatchToEnumerationTask(this,
                    awsEnumerationContext.computeEnumerationRequest.taskReference);
            return;
        }
        handleEnumerationRequest(awsEnumerationContext);
    }

    /**
     * Starts the related services for the Enumeration Service
     */
    private void startHelperServices(Operation startPost) {
        Operation postAWScomputeDescriptionService = Operation
                .createPost(this.getHost(), AWSComputeDescriptionCreationAdapterService.SELF_LINK)
                .setReferer(this.getUri());

        Operation postAWscomputeStateService = Operation
                .createPost(this.getHost(), AWSComputeStateCreationAdapterService.SELF_LINK)
                .setReferer(this.getUri());

        Operation postAWsNetworkStateService = Operation
                .createPost(this.getHost(), AWSNetworkStateCreationAdapterService.SELF_LINK)
                .setReferer(this.getUri());

        this.getHost().startService(postAWScomputeDescriptionService,
                new AWSComputeDescriptionCreationAdapterService());
        this.getHost().startService(postAWscomputeStateService,
                new AWSComputeStateCreationAdapterService());
        this.getHost().startService(postAWsNetworkStateService,
                new AWSNetworkStateCreationAdapterService());

        getHost().registerForServiceAvailability((o, e) -> {
            if (e != null) {
                String message = "Failed to start up all the services related to the AWS Enumeration Creation Adapter Service";
                this.logInfo(message);
                throw new IllegalStateException(message);
            }
            this.logInfo(
                    "Successfully started up all the services related to the AWS Enumeration Creation Adapter Service");
        }, AWSComputeDescriptionCreationAdapterService.SELF_LINK,
                AWSComputeStateCreationAdapterService.SELF_LINK,
                AWSNetworkStateCreationAdapterService.SELF_LINK);
    }

    /**
     * Handles the different steps required to hit the AWS endpoint and get the set of resources
     * available and proceed to update the state in the local system based on the received data.
     *
     */
    private void handleEnumerationRequest(EnumerationCreationContext aws) {
        switch (aws.stage) {
        case CLIENT:
            getAWSAsyncClient(aws, AWSEnumerationCreationStages.ENUMERATE);
            break;
        case ENUMERATE:
            switch (aws.computeEnumerationRequest.enumerationAction) {
            case START:
                logInfo("Started enumeration for creation for %s",
                        aws.computeEnumerationRequest.resourceReference);
                aws.computeEnumerationRequest.enumerationAction = EnumerationAction.REFRESH;
                handleEnumerationRequest(aws);
                break;
            case REFRESH:
                processRefreshSubStages(aws);
                break;
            case STOP:
                logInfo("Stopping enumeration service for creation for %s",
                        aws.computeEnumerationRequest.resourceReference);
                setOperationDurationStat(aws.awsAdapterOperation);
                aws.awsAdapterOperation.complete();
                break;
            default:
                break;
            }
            break;
        case ERROR:
            AdapterUtils.sendFailurePatchToEnumerationTask(this,
                    aws.computeEnumerationRequest.taskReference, aws.error);
            break;
        default:
            logSevere("Unknown AWS enumeration stage %s ", aws.stage.toString());
            aws.error = new Exception("Unknown AWS enumeration stage %s");
            AdapterUtils.sendFailurePatchToEnumerationTask(this,
                    aws.computeEnumerationRequest.taskReference, aws.error);
            break;
        }
    }

    private void processRefreshSubStages(EnumerationCreationContext aws) {
        switch (aws.refreshSubStage) {
        case VCP:
            refreshVPCInformation(aws, AWSEnumerationRefreshSubStage.COMPUTE);
            break;
        case COMPUTE:
            if (aws.pageNo == 1) {
                logInfo("Running enumeration service for creation in refresh mode for %s",
                        aws.parentCompute.description.environmentName);
            }
            logInfo("Processing page %d ", aws.pageNo);
            aws.pageNo++;
            if (aws.describeInstancesRequest == null) {
                creatAWSRequestAndAsyncHandler(aws);
            }
            aws.amazonEC2Client.describeInstancesAsync(aws.describeInstancesRequest,
                    aws.resultHandler);
            break;
        default:
            logSevere("Unknown AWS enumeration stage %s ", aws.refreshSubStage.toString());
            aws.error = new Exception("Unknown AWS enumeration stage %s");
            AdapterUtils.sendFailurePatchToEnumerationTask(this,
                    aws.computeEnumerationRequest.taskReference, aws.error);
            break;
        }
    }

    /**
     * Method to instantiate the AWS Async client for future use
     *
     * @param aws
     */
    private void getAWSAsyncClient(EnumerationCreationContext aws,
            AWSEnumerationCreationStages next) {
        aws.amazonEC2Client = this.clientManager.getOrCreateEC2Client(aws.parentAuth,
                aws.parentCompute.description.regionId, this,
                aws.computeEnumerationRequest.taskReference, true);
        aws.stage = next;
        handleEnumerationRequest(aws);
    }

    /**
     * Initializes and saves a reference to the request object that is sent to AWS to get a page of
     * instances. Also saves an instance to the async handler that will be used to handle the
     * responses received from AWS. It sets the nextToken value in the request object sent to AWS
     * for getting the next page of results from AWS.
     *
     * @param aws
     */
    private void creatAWSRequestAndAsyncHandler(EnumerationCreationContext aws) {
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        Filter runningInstanceFilter = getAWSNonTerminatedInstancesFilter();
        request.getFilters().add(runningInstanceFilter);
        request.setMaxResults(getQueryPageSize());
        request.setNextToken(aws.nextToken);
        aws.describeInstancesRequest = request;
        AsyncHandler<DescribeInstancesRequest, DescribeInstancesResult> resultHandler = new AWSEnumerationAsyncHandler(
                this, aws);
        aws.resultHandler = resultHandler;
    }

    private void refreshVPCInformation(EnumerationCreationContext aws,
            AWSEnumerationRefreshSubStage next) {
        AWSNetworkEnumeration networkEnumeration = new AWSNetworkEnumeration();
        networkEnumeration.tenantLinks = aws.parentCompute.tenantLinks;
        networkEnumeration.parentAuth = aws.parentAuth;
        networkEnumeration.regionId = aws.parentCompute.description.regionId;
        networkEnumeration.enumerationRequest = aws.computeEnumerationRequest;

        sendRequest(Operation
                .createPatch(this, AWSNetworkStateCreationAdapterService.SELF_LINK)
                .setBody(networkEnumeration)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        logSevere(
                                "Failure creating compute states %s",
                                Utils.toString(e));
                        aws.error = e;
                        aws.stage = AWSEnumerationCreationStages.ERROR;
                        handleEnumerationRequest(aws);
                        return;
                    } else {
                        logInfo("Successfully Network(VPC) states. Proceeding to next state.");
                        aws.vpcs = o.getBody(
                                AWSNetworkStateCreationAdapterService.NetworkEnumerationResponse.class).vpcs;
                        aws.refreshSubStage = next;
                        processRefreshSubStages(aws);
                        return;
                    }
                }));
    }

    /**
     * The async handler to handle the success and errors received after invoking the describe
     * instances API on AWS
     */
    public static class AWSEnumerationAsyncHandler implements
            AsyncHandler<DescribeInstancesRequest, DescribeInstancesResult> {

        private AWSEnumerationAndCreationAdapterService service;
        private EnumerationCreationContext aws;
        private OperationContext opContext;

        private AWSEnumerationAsyncHandler(AWSEnumerationAndCreationAdapterService service,
                EnumerationCreationContext aws) {
            this.service = service;
            this.aws = aws;
            this.opContext = OperationContext.getOperationContext();
        }

        @Override
        public void onError(Exception exception) {
            OperationContext.restoreOperationContext(this.opContext);
            AdapterUtils.sendFailurePatchToEnumerationTask(this.service,
                    this.aws.computeEnumerationRequest.taskReference,
                    exception);

        }

        @Override
        public void onSuccess(DescribeInstancesRequest request,
                DescribeInstancesResult result) {
            OperationContext.restoreOperationContext(this.opContext);
            int totalNumberOfInstances = 0;
            // Print the details of the instances discovered on the AWS endpoint
            for (Reservation r : result.getReservations()) {
                for (Instance i : r.getInstances()) {
                    this.service.logFine("%d=====Instance details %s =====",
                            ++totalNumberOfInstances,
                            i.getInstanceId());
                    this.aws.remoteAWSInstances.put(i.getInstanceId(), i);
                }
            }
            this.service.logInfo("Successfully enumerated %d instances on the AWS host",
                    totalNumberOfInstances);
            // Save the reference to the next token that will be used to retrieve the next page of
            // results from AWS.
            this.aws.nextToken = result.getNextToken();
            // Since there is filtering of resources at source, there can be a case when no
            // resources are returned from AWS.
            if (this.aws.remoteAWSInstances.size() == 0) {
                if (this.aws.nextToken != null) {
                    this.aws.subStage = AWSComputeEnumerationCreationSubStage.GET_NEXT_PAGE;
                } else {
                    this.aws.subStage = AWSComputeEnumerationCreationSubStage.ENUMERATION_STOP;
                }
            }
            handleReceivedEnumerationData();
        }

        /**
         * Uses the received enumeration information and compares it against it the state of the
         * local system and then tries to find and fix the gaps. At a high level this is the
         * sequence of steps that is followed: 1) Create a query to get the list of local compute
         * states 2) Compare the list of local resources against the list received from the AWS
         * endpoint. 3) Create the instances not know to the local system. These are represented
         * using a combination of compute descriptions and compute states. 4) Find and create a
         * representative list of compute descriptions. 5) Create compute states to represent each
         * and every VM that was discovered on the AWS endpoint.
         */
        private void handleReceivedEnumerationData() {
            switch (this.aws.subStage) {
            case QUERY_LOCAL_RESOURCES:
                getLocalResources(AWSComputeEnumerationCreationSubStage.COMPARE);
                break;
            case COMPARE:
                compareLocalStateWithEnumerationData(
                        AWSComputeEnumerationCreationSubStage.CREATE_COMPUTE_DESCRIPTIONS);
                break;
            case CREATE_COMPUTE_DESCRIPTIONS:
                if (this.aws.instancesToBeCreated.size() > 0
                        || this.aws.instancesToBeUpdated.size() > 0) {
                    createComputeDescriptions(
                            AWSComputeEnumerationCreationSubStage.CREATE_COMPUTE_STATES);
                } else {
                    if (this.aws.nextToken == null) {
                        this.aws.subStage = AWSComputeEnumerationCreationSubStage.ENUMERATION_STOP;
                    } else {
                        this.aws.subStage = AWSComputeEnumerationCreationSubStage.GET_NEXT_PAGE;
                    }
                    handleReceivedEnumerationData();
                }
                break;
            case CREATE_COMPUTE_STATES:
                AWSComputeEnumerationCreationSubStage next;
                if (this.aws.nextToken == null) {
                    next = AWSComputeEnumerationCreationSubStage.ENUMERATION_STOP;
                } else {
                    next = AWSComputeEnumerationCreationSubStage.GET_NEXT_PAGE;
                }
                createComputeStates(next);
                break;
            case GET_NEXT_PAGE:
                getNextPageFromEnumerationAdapter(
                        AWSComputeEnumerationCreationSubStage.QUERY_LOCAL_RESOURCES);
                break;
            case ENUMERATION_STOP:
                signalStopToEnumerationAdapter();
                break;
            default:
                Throwable t = new Exception("Unknown AWS enumeration sub stage");
                signalErrorToEnumerationAdapter(t);
            }
        }

        /**
         * Query the local data store and retrieve all the the compute states that exist filtered by
         * the instanceIds that are received in the enumeration data from AWS.
         */
        public void getLocalResources(AWSComputeEnumerationCreationSubStage next) {
            // query all ComputeState resources for the cluster filtered by the received set of
            // instance Ids
            QueryTask q = new QueryTask();
            q.setDirect(true);
            q.querySpec = new QueryTask.QuerySpecification();
            q.querySpec.options.add(QueryOption.EXPAND_CONTENT);
            q.querySpec.query = Query.Builder.create()
                    .addKindFieldClause(ComputeService.ComputeState.class)
                    .addFieldClause(ComputeState.FIELD_NAME_PARENT_LINK,
                            this.aws.computeEnumerationRequest.resourceLink())
                    .addFieldClause(ComputeState.FIELD_NAME_RESOURCE_POOL_LINK,
                            this.aws.computeEnumerationRequest.resourcePoolLink)
                    .build();

            QueryTask.Query instanceIdFilterParentQuery = new QueryTask.Query();
            instanceIdFilterParentQuery.occurance = Occurance.MUST_OCCUR;
            for (String instanceId : this.aws.remoteAWSInstances.keySet()) {
                QueryTask.Query instanceIdFilter = new QueryTask.Query()
                        .setTermPropertyName(ComputeState.FIELD_NAME_ID)
                        .setTermMatchValue(instanceId);
                instanceIdFilter.occurance = QueryTask.Query.Occurance.SHOULD_OCCUR;
                instanceIdFilterParentQuery.addBooleanClause(instanceIdFilter);
            }
            q.querySpec.query.addBooleanClause(instanceIdFilterParentQuery);
            q.documentSelfLink = UUID.randomUUID().toString();
            q.tenantLinks = this.aws.parentCompute.tenantLinks;
            // create the query to find resources
            this.service.sendRequest(Operation
                    .createPost(this.service, ServiceUriPaths.CORE_QUERY_TASKS)
                    .setBody(q)
                    .setConnectionSharing(true)
                    .setCompletion((o, e) -> {
                        if (e != null) {
                            this.service.logSevere("Failure retrieving query results: %s",
                                    e.toString());
                            signalErrorToEnumerationAdapter(e);
                            return;
                        }
                        QueryTask responseTask = o.getBody(QueryTask.class);
                        for (Object s : responseTask.results.documents.values()) {
                            ComputeState localInstance = Utils.fromJson(s,
                                    ComputeService.ComputeState.class);
                            this.aws.localAWSInstanceMap.put(localInstance.id,
                                    localInstance);
                        }
                        this.service.logInfo(
                                "Got result of the query to get local resources. There are %d instances known to the system.",
                                responseTask.results.documentCount);
                        this.aws.subStage = next;
                        handleReceivedEnumerationData();
                        return;
                    }));
        }

        /**
         * Compares the local list of VMs against what is received from the AWS endpoint. Saves a
         * list of the VMs that have to be created in the local system to correspond to the remote
         * AWS endpoint.
         */
        private void compareLocalStateWithEnumerationData(
                AWSComputeEnumerationCreationSubStage next) {
            // No remote instances
            if (this.aws.remoteAWSInstances == null || this.aws.remoteAWSInstances.size() == 0) {
                this.service.logInfo(
                        "No resources discovered on the remote system. Nothing to be created locally");
                // no local instances
            } else if (this.aws.localAWSInstanceMap == null
                    || this.aws.localAWSInstanceMap.size() == 0) {
                for (String key : this.aws.remoteAWSInstances.keySet()) {
                    this.aws.instancesToBeCreated.add(this.aws.remoteAWSInstances.get(key));
                }
                // compare and add the ones that do not exist locally for creation. Mark others
                // for updates.
            } else {
                for (String key : this.aws.remoteAWSInstances.keySet()) {
                    if (!this.aws.localAWSInstanceMap.containsKey(key)) {
                        this.aws.instancesToBeCreated.add(this.aws.remoteAWSInstances.get(key));
                        // A map of the local compute state id and the corresponding latest
                        // state on AWS
                    } else {
                        this.aws.instancesToBeUpdated.put(key,
                                this.aws.remoteAWSInstances.get(key));
                        this.aws.computeStatesToBeUpdated.put(key,
                                this.aws.localAWSInstanceMap.get(key));
                    }
                }
            }
            this.aws.subStage = next;
            handleReceivedEnumerationData();
        }

        /**
         * Posts a compute description to the compute description service for creation.
         */
        private void createComputeDescriptions(AWSComputeEnumerationCreationSubStage next) {
            AWSComputeDescriptionCreationState cd = new AWSComputeDescriptionCreationState();
            cd.instancesToBeCreated = this.aws.instancesToBeCreated;
            cd.parentTaskLink = this.aws.computeEnumerationRequest.taskReference;
            cd.authCredentiaslLink = this.aws.parentAuth.documentSelfLink;
            cd.tenantLinks = this.aws.parentCompute.tenantLinks;
            cd.parentDescription = this.aws.parentCompute.description;

            this.service.sendRequest(Operation
                    .createPatch(this.service,
                            AWSComputeDescriptionCreationAdapterService.SELF_LINK)
                    .setBody(cd)
                    .setCompletion((o, e) -> {
                        if (e != null) {
                            this.service.logSevere(
                                    "Failure creating compute descriptions %s",
                                    Utils.toString(e));
                            signalErrorToEnumerationAdapter(e);
                            return;
                        } else {
                            this.service.logInfo(
                                    "Successfully created compute descriptions. Proceeding to next state.");
                            this.aws.subStage = next;
                            handleReceivedEnumerationData();
                            return;
                        }
                    }));
        }

        /**
         * Creates the compute states that represent the instances received from AWS during
         * enumeration.
         *
         * @param next
         */
        private void createComputeStates(AWSComputeEnumerationCreationSubStage next) {
            AWSComputeStateForCreation awsComputeState = new AWSComputeStateForCreation();
            awsComputeState.instancesToBeCreated = this.aws.instancesToBeCreated;
            awsComputeState.instancesToBeUpdated = this.aws.instancesToBeUpdated;
            awsComputeState.computeStatesToBeUpdated = this.aws.computeStatesToBeUpdated;
            awsComputeState.parentComputeLink = this.aws.parentCompute.documentSelfLink;
            awsComputeState.resourcePoolLink = this.aws.computeEnumerationRequest.resourcePoolLink;
            awsComputeState.parentTaskLink = this.aws.computeEnumerationRequest.taskReference;
            awsComputeState.tenantLinks = this.aws.parentCompute.tenantLinks;
            awsComputeState.parentAuth = this.aws.parentAuth;
            awsComputeState.regionId = this.aws.parentCompute.description.regionId;
            awsComputeState.vpcs = this.aws.vpcs;

            this.service.sendRequest(Operation
                    .createPatch(this.service, AWSComputeStateCreationAdapterService.SELF_LINK)
                    .setBody(awsComputeState)
                    .setCompletion((o, e) -> {
                        if (e != null) {
                            this.service.logSevere(
                                    "Failure creating compute states %s",
                                    Utils.toString(e));
                            signalErrorToEnumerationAdapter(e);
                            return;
                        } else {
                            this.service.logInfo(
                                    "Successfully created compute states. Proceeding to next state.");
                            this.aws.subStage = next;
                            handleReceivedEnumerationData();
                            return;
                        }
                    }));
        }

        /**
         * Signals Enumeration Stop to the AWS enumeration adapter. The AWS enumeration adapter will
         * in turn patch the parent task to indicate completion.
         */
        private void signalStopToEnumerationAdapter() {
            this.aws.computeEnumerationRequest.enumerationAction = EnumerationAction.STOP;
            this.service.handleEnumerationRequest(this.aws);
        }

        /**
         * Signals error to the AWS enumeration adapter. The adapter will in turn clean up resources
         * and signal error to the parent task.
         */
        private void signalErrorToEnumerationAdapter(Throwable t) {
            this.aws.error = t;
            this.aws.stage = AWSEnumerationCreationStages.ERROR;
            this.service.handleEnumerationRequest(this.aws);
        }

        /**
         * Calls the AWS enumeration adapter to get the next page from AWSs
         *
         * @param next
         */
        private void getNextPageFromEnumerationAdapter(AWSComputeEnumerationCreationSubStage next) {
            // Reset all the results from the last page that was processed.
            this.aws.remoteAWSInstances.clear();
            this.aws.instancesToBeCreated.clear();
            this.aws.instancesToBeUpdated.clear();
            this.aws.computeStatesToBeUpdated.clear();
            this.aws.localAWSInstanceMap.clear();
            this.aws.describeInstancesRequest.setNextToken(this.aws.nextToken);
            this.aws.subStage = next;
            this.service.handleEnumerationRequest(this.aws);
        }
    }

}
