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

package com.vmware.photon.controller.model.adapters.awsadapter;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClient;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;

import com.vmware.photon.controller.model.UriPaths;
import com.vmware.photon.controller.model.adapterapi.ComputeStatsRequest;
import com.vmware.photon.controller.model.adapterapi.ComputeStatsResponse.ComputeStats;
import com.vmware.photon.controller.model.adapters.awsadapter.util.AWSClientManager;
import com.vmware.photon.controller.model.adapters.awsadapter.util.AWSClientManagerFactory;
import com.vmware.photon.controller.model.adapters.awsadapter.util.AWSStatsNormalizer;
import com.vmware.photon.controller.model.adapters.util.AdapterUtils;
import com.vmware.photon.controller.model.constants.PhotonModelConstants;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService.ComputeDescription;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService.ComputeDescription.ComputeType;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeStateWithDescription;
import com.vmware.photon.controller.model.tasks.monitoring.SingleResourceStatsCollectionTaskService.SingleResourceStatsCollectionTaskState;
import com.vmware.photon.controller.model.tasks.monitoring.SingleResourceStatsCollectionTaskService.SingleResourceTaskCollectionStage;

import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationContext;
import com.vmware.xenon.common.ServiceStats.ServiceStat;
import com.vmware.xenon.common.StatelessService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.AuthCredentialsService;
import com.vmware.xenon.services.common.AuthCredentialsService.AuthCredentialsServiceState;

/**
 * Service to gather stats on AWS.
 */
public class AWSStatsService extends StatelessService {
    private AWSClientManager clientManager;

    public static final String AWS_COLLECTION_PERIOD_SECONDS = UriPaths.PROPERTY_PREFIX + "AWSStatsService.collectionPeriod";
    private static final int DEFAULT_AWS_COLLECTION_PERIOD_SECONDS = 300;

    public AWSStatsService() {
        super.toggleOption(ServiceOption.INSTRUMENTATION, true);
        this.clientManager = AWSClientManagerFactory
                .getClientManager(AWSConstants.AwsClientType.CLOUD_WATCH);
    }

    public static final String SELF_LINK = AWSUriPaths.AWS_STATS_ADAPTER;

    public static final String[] METRIC_NAMES = { AWSConstants.CPU_UTILIZATION,
            AWSConstants.DISK_READ_BYTES, AWSConstants.DISK_WRITE_BYTES,
            AWSConstants.NETWORK_IN, AWSConstants.NETWORK_OUT,
            AWSConstants.CPU_CREDIT_USAGE, AWSConstants.CPU_CREDIT_BALANCE,
            AWSConstants.DISK_READ_OPS, AWSConstants.DISK_WRITE_OPS,
            AWSConstants.NETWORK_PACKETS_IN, AWSConstants.NETWORK_PACKETS_OUT,
            AWSConstants.STATUS_CHECK_FAILED, AWSConstants.STATUS_CHECK_FAILED_INSTANCE,
            AWSConstants.STATUS_CHECK_FAILED_SYSTEM };

    public static final String[] AGGREGATE_METRIC_NAMES_ACROSS_INSTANCES = {
            AWSConstants.CPU_UTILIZATION, AWSConstants.DISK_READ_BYTES,
            AWSConstants.DISK_READ_OPS, AWSConstants.DISK_WRITE_BYTES,
            AWSConstants.DISK_WRITE_OPS, AWSConstants.NETWORK_IN,
            AWSConstants.NETWORK_OUT };

    private static final String[] STATISTICS = { "Average", "SampleCount" };
    private static final String NAMESPACE = "AWS/EC2";
    private static final String DIMENSION_INSTANCE_ID = "InstanceId";
    // This is the maximum window size for which stats should be collected in case the last
    // collection time is not specified.
    // Defaulting to 1 hr.
    private static final int MAX_METRIC_COLLECTION_WINDOW_IN_MINUTES = 60;

    // Cost
    private static final String BILLING_NAMESPACE = "AWS/Billing";
    private static final String DIMENSION_CURRENCY = "Currency";
    private static final String DIMENSION_CURRENCY_VALUE = "USD";
    private static final int COST_COLLECTION_WINDOW_IN_DAYS = 14;
    private static final int COST_COLLECTION_PERIOD_IN_SECONDS = 14400;
    // AWS stores all billing data in us-east-1 zone.
    private static final String COST_ZONE_ID = "us-east-1";
    private static final int NUM_OF_COST_DATAPOINTS_IN_A_DAY = 6;

    private class AWSStatsDataHolder {
        public ComputeStateWithDescription computeDesc;
        public ComputeStateWithDescription parentDesc;
        public AuthCredentialsService.AuthCredentialsServiceState parentAuth;
        public ComputeStatsRequest statsRequest;
        public ComputeStats statsResponse;
        public AtomicInteger numResponses = new AtomicInteger(0);
        public AmazonCloudWatchAsyncClient statsClient;
        public AmazonCloudWatchAsyncClient billingClient;
        public boolean isComputeHost;

        public AWSStatsDataHolder() {
            this.statsResponse = new ComputeStats();
            // create a thread safe map to hold stats values for resource
            this.statsResponse.statValues = new ConcurrentSkipListMap<>();
        }
    }

    @Override
    public void handleStart(Operation startPost) {
        super.handleStart(startPost);
    }

    @Override
    public void handleStop(Operation delete) {
        AWSClientManagerFactory.returnClientManager(this.clientManager,
                AWSConstants.AwsClientType.CLOUD_WATCH);
        super.handleStop(delete);
    }

    @Override
    public void handlePatch(Operation op) {
        if (!op.hasBody()) {
            op.fail(new IllegalArgumentException("body is required"));
            return;
        }
        op.complete();
        ComputeStatsRequest statsRequest = op.getBody(ComputeStatsRequest.class);
        if (statsRequest.isMockRequest) {
            // patch status to parent task
            AdapterUtils.sendPatchToProvisioningTask(this, statsRequest.taskReference);
            return;
        }
        AWSStatsDataHolder statsData = new AWSStatsDataHolder();
        statsData.statsRequest = statsRequest;
        getVMDescription(statsData);
    }

    private void getVMDescription(AWSStatsDataHolder statsData) {
        Consumer<Operation> onSuccess = (op) -> {
            statsData.computeDesc = op.getBody(ComputeStateWithDescription.class);
            statsData.isComputeHost = isComputeHost(statsData.computeDesc.description);

            // if we have a compute host then we directly get the auth.
            if (statsData.isComputeHost) {
                getParentAuth(statsData);
            } else {
                getParentVMDescription(statsData);
            }
        };
        URI computeUri = UriUtils.extendUriWithQuery(statsData.statsRequest.resourceReference,
                UriUtils.URI_PARAM_ODATA_EXPAND,
                Boolean.TRUE.toString());
        AdapterUtils.getServiceState(this, computeUri, onSuccess, getFailureConsumer(statsData));
    }

    private void getParentVMDescription(AWSStatsDataHolder statsData) {
        Consumer<Operation> onSuccess = (op) -> {
            statsData.parentDesc = op.getBody(ComputeStateWithDescription.class);
            getParentAuth(statsData);
        };
        URI computeUri = UriUtils.extendUriWithQuery(
                UriUtils.buildUri(getHost(), statsData.computeDesc.parentLink),
                UriUtils.URI_PARAM_ODATA_EXPAND,
                Boolean.TRUE.toString());
        AdapterUtils.getServiceState(this, computeUri, onSuccess, getFailureConsumer(statsData));
    }

    private void getParentAuth(AWSStatsDataHolder statsData) {
        Consumer<Operation> onSuccess = (op) -> {
            statsData.parentAuth = op.getBody(AuthCredentialsServiceState.class);
            getStats(statsData);
        };
        String authLink;
        if (statsData.isComputeHost) {
            authLink = statsData.computeDesc.description.authCredentialsLink;
        } else {
            authLink = statsData.parentDesc.description.authCredentialsLink;
        }
        AdapterUtils.getServiceState(this, authLink, onSuccess, getFailureConsumer(statsData));
    }

    private Consumer<Throwable> getFailureConsumer(AWSStatsDataHolder statsData) {
        return ((t) -> {
            AdapterUtils.sendFailurePatchToProvisioningTask(this,
                    statsData.statsRequest.taskReference, t);
        });
    }

    private void getStats(AWSStatsDataHolder statsData) {
        if (statsData.isComputeHost) {
            // Get host level stats for billing and ec2.
            getBillingStats(statsData);
            return;
        }
        getEC2Stats(statsData, METRIC_NAMES, false);
    }

    /**
     * Gets EC2 statistics.
     *
     * @param statsData The context object for stats.
     * @param metricNames The metrics names to gather stats for.
     * @param isAggregateStats Indicates where we are interested in aggregate stats or not.
     */
    private void getEC2Stats(AWSStatsDataHolder statsData, String[] metricNames,
            boolean isAggregateStats) {
        getAWSAsyncStatsClient(statsData);
        int collectionPeriod = Integer.getInteger(AWS_COLLECTION_PERIOD_SECONDS, DEFAULT_AWS_COLLECTION_PERIOD_SECONDS);
        for (String metricName : metricNames) {
            GetMetricStatisticsRequest metricRequest = new GetMetricStatisticsRequest();
            // get datapoint for the for the passed in time window.
            setRequestCollectionWindow(
                    TimeUnit.MINUTES.toMicros(MAX_METRIC_COLLECTION_WINDOW_IN_MINUTES),
                    statsData.statsRequest.lastCollectionTimeMicrosUtc,
                    metricRequest);
            metricRequest.setPeriod(collectionPeriod);
            metricRequest.setStatistics(Arrays.asList(STATISTICS));
            metricRequest.setNamespace(NAMESPACE);

            // Provide instance id dimension only if it is not aggregate stats.
            if (!isAggregateStats) {
                List<Dimension> dimensions = new ArrayList<>();
                Dimension dimension = new Dimension();
                dimension.setName(DIMENSION_INSTANCE_ID);
                String instanceId = statsData.computeDesc.id;
                dimension.setValue(instanceId);
                dimensions.add(dimension);
                metricRequest.setDimensions(dimensions);
            }

            metricRequest.setMetricName(metricName);

            logFine("Retrieving %s metric from AWS", metricName);
            AsyncHandler<GetMetricStatisticsRequest, GetMetricStatisticsResult> resultHandler = new AWSStatsHandler(
                    this, statsData, metricNames.length, isAggregateStats);
            statsData.statsClient.getMetricStatisticsAsync(metricRequest, resultHandler);
        }
    }

    private void getBillingStats(AWSStatsDataHolder statsData) {
        getAWSAsyncBillingClient(statsData);
        Dimension dimension = new Dimension();
        dimension.setName(DIMENSION_CURRENCY);
        dimension.setValue(DIMENSION_CURRENCY_VALUE);
        GetMetricStatisticsRequest request = new GetMetricStatisticsRequest();
        // AWS pushes billing metrics every 4 hours.
        // Get all 14 days worth of cost data.
        long endTimeMicros = Utils.getNowMicrosUtc();
        request.setEndTime(new Date(TimeUnit.MICROSECONDS.toMillis(endTimeMicros)));
        request.setStartTime(new Date(
                TimeUnit.MICROSECONDS.toMillis(endTimeMicros) -
                        TimeUnit.DAYS.toMillis(COST_COLLECTION_WINDOW_IN_DAYS)));
        request.setPeriod(COST_COLLECTION_PERIOD_IN_SECONDS);
        request.setStatistics(Arrays.asList(STATISTICS));
        request.setNamespace(BILLING_NAMESPACE);
        request.setDimensions(Collections.singletonList(dimension));
        request.setMetricName(AWSConstants.ESTIMATED_CHARGES);

        logFine("Retrieving %s metric from AWS", AWSConstants.ESTIMATED_CHARGES);
        AsyncHandler<GetMetricStatisticsRequest, GetMetricStatisticsResult> resultHandler = new AWSBillingStatsHandler(
                this, statsData);
        statsData.billingClient.getMetricStatisticsAsync(request, resultHandler);
    }

    /**
     * Sets the window of time for the statistics collection. If the last collection time is passed in the compute stats request
     * then that value is used for getting the stats data from the provider else the default configured window for cost stats
     * collection is used.
     *
     * Also, if the last collection time is really a long time ago, then the maximum collection window is honored to collect
     * the stats from the provider.
     */
    private void setRequestCollectionWindow(Long defaultStartWindowMicros,
            Long lastCollectionTimeMicros,
            GetMetricStatisticsRequest request) {
        long endTimeMicros = Utils.getNowMicrosUtc();
        request.setEndTime(new Date(TimeUnit.MICROSECONDS.toMillis(endTimeMicros)));
        Long maxCollectionWindowStartTime = TimeUnit.MICROSECONDS.toMillis(endTimeMicros) -
                TimeUnit.MICROSECONDS.toMillis(defaultStartWindowMicros);
        // If the last collection time is available, then the stats data from the provider will be
        // fetched from that time onwards. Else, the stats collection is performed starting from the
        // default configured window.
        if (lastCollectionTimeMicros == null) {
            request.setStartTime(new Date(maxCollectionWindowStartTime));
            return;
        }
        if (lastCollectionTimeMicros != 0) {
            if (lastCollectionTimeMicros > endTimeMicros) {
                throw new IllegalArgumentException(
                        "The last stats collection time cannot be in the future.");
                // Check if the last collection time calls for collection to earlier than the
                // maximum defined windows size.
                // In that case default to the maximum collection window.
            } else if (TimeUnit.MICROSECONDS
                    .toMillis(lastCollectionTimeMicros) < maxCollectionWindowStartTime) {
                request.setStartTime(new Date(maxCollectionWindowStartTime));
                return;
            }
            request.setStartTime(new Date(
                    TimeUnit.MICROSECONDS.toMillis(lastCollectionTimeMicros)));

        }
    }

    private void getAWSAsyncStatsClient(AWSStatsDataHolder statsData) {
        URI parentURI = statsData.statsRequest.taskReference;
        statsData.statsClient = this.clientManager.getOrCreateCloudWatchClient(statsData.parentAuth,
                statsData.computeDesc.description.regionId, this, parentURI,
                statsData.statsRequest.isMockRequest);
    }

    private void getAWSAsyncBillingClient(AWSStatsDataHolder statsData) {
        URI parentURI = statsData.statsRequest.taskReference;
        statsData.billingClient = this.clientManager.getOrCreateCloudWatchClient(
                statsData.parentAuth,
                COST_ZONE_ID, this, parentURI,
                statsData.statsRequest.isMockRequest);
    }

    /**
     * Billing specific async handler.
     */
    private class AWSBillingStatsHandler implements
            AsyncHandler<GetMetricStatisticsRequest, GetMetricStatisticsResult> {

        private AWSStatsDataHolder statsData;
        private StatelessService service;
        private OperationContext opContext;

        public AWSBillingStatsHandler(StatelessService service, AWSStatsDataHolder statsData) {
            this.statsData = statsData;
            this.service = service;
            this.opContext = OperationContext.getOperationContext();
        }

        @Override
        public void onError(Exception exception) {
            OperationContext.restoreOperationContext(this.opContext);
            AdapterUtils.sendFailurePatchToProvisioningTask(this.service,
                    this.statsData.statsRequest.taskReference, exception);
        }

        @Override
        public void onSuccess(GetMetricStatisticsRequest request,
                GetMetricStatisticsResult result) {
            try {
                OperationContext.restoreOperationContext(this.opContext);
                List<Datapoint> dpList = result.getDatapoints();
                // Sort the data points in increasing order of timestamp to calculate Burn rate
                Collections
                        .sort(dpList, (o1, o2) -> o1.getTimestamp().compareTo(o2.getTimestamp()));

                List<ServiceStat> estimatedChargesDatapoints = new ArrayList<>();
                if (dpList != null && dpList.size() != 0) {
                    for (Datapoint dp : dpList) {
                        // If the datapoint collected is older than the last collection time, skip it.
                        if (this.statsData.statsRequest.lastCollectionTimeMicrosUtc != null &&
                                TimeUnit.MILLISECONDS.toMicros(dp.getTimestamp()
                                        .getTime())
                                        <= this.statsData.statsRequest.lastCollectionTimeMicrosUtc) {
                            continue;
                        }

                        // If there is no lastCollectionTime or the datapoint collected in newer
                        // than the lastCollectionTime, push it.
                        ServiceStat stat = new ServiceStat();
                        stat.latestValue = dp.getAverage();
                        stat.unit = AWSStatsNormalizer
                                .getNormalizedUnitValue(DIMENSION_CURRENCY_VALUE);
                        stat.sourceTimeMicrosUtc = TimeUnit.MILLISECONDS
                                .toMicros(dp.getTimestamp().getTime());
                        estimatedChargesDatapoints.add(stat);
                    }

                    this.statsData.statsResponse.statValues.put(
                            AWSStatsNormalizer.getNormalizedStatKeyValue(result.getLabel()),
                            estimatedChargesDatapoints);

                    // Calculate average burn rate only if there is more than 1 datapoint available.
                    // This will ensure that NaN errors will not occur.
                    if (dpList.size() > 1) {
                        ServiceStat averageBurnRate = new ServiceStat();
                        averageBurnRate.latestValue = AWSUtils.calculateAverageBurnRate(dpList);
                        averageBurnRate.unit = AWSStatsNormalizer
                                .getNormalizedUnitValue(DIMENSION_CURRENCY_VALUE);
                        averageBurnRate.sourceTimeMicrosUtc = Utils.getSystemNowMicrosUtc();
                        this.statsData.statsResponse.statValues.put(
                                AWSStatsNormalizer
                                        .getNormalizedStatKeyValue(AWSConstants.AVERAGE_BURN_RATE),
                                Collections.singletonList(averageBurnRate));
                    }

                    // Calculate current burn rate only if there is more than 1 day worth of data available.
                    if (dpList.size() > NUM_OF_COST_DATAPOINTS_IN_A_DAY) {
                        ServiceStat currentBurnRate = new ServiceStat();
                        currentBurnRate.latestValue = AWSUtils.calculateCurrentBurnRate(dpList);
                        currentBurnRate.unit = AWSStatsNormalizer
                                .getNormalizedUnitValue(DIMENSION_CURRENCY_VALUE);
                        currentBurnRate.sourceTimeMicrosUtc = Utils.getSystemNowMicrosUtc();
                        this.statsData.statsResponse.statValues.put(
                                AWSStatsNormalizer
                                        .getNormalizedStatKeyValue(AWSConstants.CURRENT_BURN_RATE),
                                Collections.singletonList(currentBurnRate));
                    }
                }

                getEC2Stats(this.statsData, AGGREGATE_METRIC_NAMES_ACROSS_INSTANCES, true);
            } catch (Exception e) {
                AdapterUtils.sendFailurePatchToProvisioningTask(this.service,
                        this.statsData.statsRequest.taskReference, e);
            }
        }
    }

    private class AWSStatsHandler implements
            AsyncHandler<GetMetricStatisticsRequest, GetMetricStatisticsResult> {

        private final int numOfMetrics;
        private final Boolean isAggregateStats;
        private AWSStatsDataHolder statsData;
        private StatelessService service;
        private OperationContext opContext;

        public AWSStatsHandler(StatelessService service, AWSStatsDataHolder statsData,
                int numOfMetrics, Boolean isAggregateStats) {
            this.statsData = statsData;
            this.service = service;
            this.numOfMetrics = numOfMetrics;
            this.isAggregateStats = isAggregateStats;
            this.opContext = OperationContext.getOperationContext();
        }

        @Override
        public void onError(Exception exception) {
            OperationContext.restoreOperationContext(this.opContext);
            AdapterUtils.sendFailurePatchToProvisioningTask(this.service,
                    this.statsData.statsRequest.taskReference, exception);
        }

        @Override
        public void onSuccess(GetMetricStatisticsRequest request,
                GetMetricStatisticsResult result) {
            try {
                OperationContext.restoreOperationContext(this.opContext);
                List<ServiceStat> statDatapoints = new ArrayList<>();
                List<Datapoint> dpList = result.getDatapoints();
                if (dpList != null && dpList.size() != 0) {
                    for (Datapoint dp : dpList) {
                        ServiceStat stat = new ServiceStat();
                        stat.latestValue = dp.getAverage();
                        stat.unit = AWSStatsNormalizer.getNormalizedUnitValue(dp.getUnit());
                        stat.sourceTimeMicrosUtc = TimeUnit.MILLISECONDS
                                .toMicros(dp.getTimestamp().getTime());
                        statDatapoints.add(stat);
                    }

                    this.statsData.statsResponse.statValues
                            .put(AWSStatsNormalizer.getNormalizedStatKeyValue(result.getLabel()),
                                    statDatapoints);
                }

                if (this.statsData.numResponses.incrementAndGet() == this.numOfMetrics) {
                    // Put the number of API requests as a stat
                    ServiceStat apiCallCountStat = new ServiceStat();
                    apiCallCountStat.latestValue = this.numOfMetrics;
                    if (this.isAggregateStats) {
                        // Number of Aggregate metrics + 1 call for cost metric
                        apiCallCountStat.latestValue += 1;
                    }
                    apiCallCountStat.sourceTimeMicrosUtc = Utils.getNowMicrosUtc();
                    apiCallCountStat.unit = PhotonModelConstants.UNIT_COUNT;
                    this.statsData.statsResponse.statValues.put(PhotonModelConstants.API_CALL_COUNT,
                            Collections.singletonList(apiCallCountStat));

                    SingleResourceStatsCollectionTaskState respBody = new SingleResourceStatsCollectionTaskState();
                    this.statsData.statsResponse.computeLink = this.statsData.computeDesc.documentSelfLink;
                    respBody.taskStage = SingleResourceTaskCollectionStage
                            .valueOf(this.statsData.statsRequest.nextStage);
                    respBody.statsAdapterReference = UriUtils.buildUri(getHost(), SELF_LINK);
                    respBody.statsList = new ArrayList<>();
                    respBody.statsList.add(this.statsData.statsResponse);
                    this.service.sendRequest(
                            Operation.createPatch(this.statsData.statsRequest.taskReference)
                                    .setBody(respBody));
                }
            } catch (Exception e) {
                AdapterUtils.sendFailurePatchToProvisioningTask(this.service,
                        this.statsData.statsRequest.taskReference, e);
            }
        }
    }

    /**
     * Returns if the given compute description is a compute host or not.
     */
    private boolean isComputeHost(ComputeDescription computeDescription) {
        List<String> supportedChildren = computeDescription.supportedChildren;
        return supportedChildren != null && supportedChildren.contains(ComputeType.VM_GUEST.name());
    }
}