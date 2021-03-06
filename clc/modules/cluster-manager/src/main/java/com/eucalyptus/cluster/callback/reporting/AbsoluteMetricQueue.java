/*************************************************************************
 * Copyright 2009-2015 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/
package com.eucalyptus.cluster.callback.reporting;

import com.eucalyptus.bootstrap.Bootstrap;
import com.eucalyptus.cloudwatch.common.CloudWatch;
import com.eucalyptus.cloudwatch.common.CloudWatchBackend;
import com.eucalyptus.cloudwatch.common.internal.domain.metricdata.Units;
import com.eucalyptus.cloudwatch.common.msgs.Dimension;
import com.eucalyptus.cloudwatch.common.msgs.Dimensions;
import com.eucalyptus.cloudwatch.common.msgs.MetricData;
import com.eucalyptus.cloudwatch.common.msgs.MetricDatum;
import com.eucalyptus.cloudwatch.common.msgs.PutMetricDataResponseType;
import com.eucalyptus.cloudwatch.common.msgs.PutMetricDataType;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.Topology;
import com.eucalyptus.component.id.Eucalyptus;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.async.AsyncRequests;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import edu.ucsb.eucalyptus.msgs.BaseMessage;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by ethomas on 6/16/15.
 */
public class AbsoluteMetricQueue {

  public static volatile Integer ABSOLUTE_METRIC_NUM_DB_OPERATIONS_PER_TRANSACTION = 10000;
  public static volatile Integer ABSOLUTE_METRIC_NUM_DB_OPERATIONS_UNTIL_SESSION_FLUSH = 50;


  static {
    ScheduledExecutorService dbCleanupService = Executors
      .newSingleThreadScheduledExecutor();
    dbCleanupService.scheduleAtFixedRate(new DBCleanupService(), 1, 30,
      TimeUnit.MINUTES);
  }


  private static final Logger LOG = Logger.getLogger(AbsoluteMetricQueue.class);
  final static LinkedBlockingQueue<AbsoluteMetricQueueItem> dataQueue = new LinkedBlockingQueue<AbsoluteMetricQueueItem>();

  private static final ScheduledExecutorService dataFlushTimer = Executors
    .newSingleThreadScheduledExecutor();

  private static AbsoluteMetricQueue singleton = getInstance();

  public static AbsoluteMetricQueue getInstance() {
    synchronized (AbsoluteMetricQueue.class) {
      if (singleton == null)
        singleton = new AbsoluteMetricQueue();
    }
    return singleton;
  }

  private static Runnable safeRunner = new Runnable() {
    @Override
    public void run() {
      long before = System.currentTimeMillis();
      try {
        List<AbsoluteMetricQueueItem> dataBatch = Lists.newArrayList();
        dataQueue.drainTo(dataBatch);
//        dataQueue.drainTo(dataBatch, 15000);
        LOG.debug("Cluster:Timing:dataBatch.size()="+dataBatch.size());
        long t1 = System.currentTimeMillis();
//        dataBatch = DefaultAbsoluteMetricConverter.dealWithAbsoluteMetrics(dataBatch);
        dataBatch = FullTableScanAbsoluteMetricConverter.dealWithAbsoluteMetrics(dataBatch);
        long t2 = System.currentTimeMillis();
        LOG.debug("Cluster:Timing:dataBatch.dealWithAbsoluteMetrics():time="+(t2-t1));
        dataBatch = foldMetrics(dataBatch);
        long t3 = System.currentTimeMillis();
        LOG.debug("Cluster:Timing:dataBatch.foldMetrics():time="+(t3-t2));
        List<PutMetricDataType> putMetricDataTypeList =convertToPutMetricDataList(dataBatch);
        long t4 = System.currentTimeMillis();
        LOG.debug("Cluster:Timing:dataBatch.convertToPutMetricDataList():time="+(t4-t3));
        putMetricDataTypeList = CloudWatchHelper.consolidatePutMetricDataList(putMetricDataTypeList);
        long t5 = System.currentTimeMillis();
        LOG.debug("Cluster:Timing:dataBatch.consolidatePutMetricDataList():time="+(t5-t4));
        callPutMetricData(putMetricDataTypeList);
        long t6 = System.currentTimeMillis();
        LOG.debug("Cluster:Timing:ListMetricManager.callPutMetricData():time="+(t6-t5));
      } catch (Throwable ex) {
        LOG.debug("error");
        ex.printStackTrace();
        LOG.error(ex,ex);
      } finally {
        long after = System.currentTimeMillis();
        LOG.debug("Cluster:Timing:time="+(after-before));
      }
    }
  };

  private static List<PutMetricDataType> convertToPutMetricDataList(List<AbsoluteMetricQueueItem> dataBatch) {
    final List<PutMetricDataType> putMetricDataTypeList = Lists.newArrayList();
    for (AbsoluteMetricQueueItem item: dataBatch) {
      PutMetricDataType putMetricDataType = new PutMetricDataType();
      putMetricDataType.setUserId(item.getAccountId());
      putMetricDataType.markPrivileged();
      putMetricDataType.setNamespace(item.getNamespace());
      MetricData metricData = new MetricData();
      ArrayList member = Lists.newArrayList(item.getMetricDatum());
      metricData.setMember(member);
      putMetricDataType.setMetricData(metricData);
      putMetricDataTypeList.add(putMetricDataType);
    }
    return putMetricDataTypeList;
  }

  private static ServiceConfiguration createServiceConfiguration() {
    return Topology.lookup(CloudWatch.class);
  }

  private static void callPutMetricData(List<PutMetricDataType> putMetricDataList) throws Exception {
    ServiceConfiguration serviceConfiguration = createServiceConfiguration();
    for (PutMetricDataType putMetricData: putMetricDataList) {
      BaseMessage reply = AsyncRequests.dispatch(serviceConfiguration, putMetricData).get();
      if (!(reply instanceof PutMetricDataResponseType)) {
        throw new EucalyptusCloudException("Unable to send put metric data to cloud watch");
      }
    }
  }

  private static List<AbsoluteMetricQueueItem> foldMetrics(List<AbsoluteMetricQueueItem> dataBatch) {
    final List<AbsoluteMetricQueueItem> foldedMetrics = Lists.newArrayList();
    if (dataBatch != null) {
      for (AbsoluteMetricQueueItem queueItem : dataBatch) {
        // keep the same metric data unless the namespace is AWS/EC2.  In that case points will exist with dimensions
        // instance-id, image-id, instance-type, and (optionally) autoscaling group name.  These points have 4
        // dimensions, and we are really only supposed to have one dimension (or zero) for aggregation purposes.
        if (queueItem != null && queueItem.getNamespace() != null && "AWS/EC2".equals(queueItem.getNamespace())) {
          MetricDatum metricDatum = queueItem.getMetricDatum();
          if (metricDatum != null && metricDatum.getDimensions() != null &&
            metricDatum.getDimensions().getMember() != null) {
            Set<Dimension> dimensionSet = Sets.newLinkedHashSet(metricDatum.getDimensions().getMember());
            for (Set<Dimension> permutation: Sets.powerSet(dimensionSet)) {
              if (permutation.size() > 1) continue;
              MetricDatum newMetricDatum = new MetricDatum();
              newMetricDatum.setValue(metricDatum.getValue());
              newMetricDatum.setUnit(metricDatum.getUnit());
              newMetricDatum.setStatisticValues(metricDatum.getStatisticValues());
              newMetricDatum.setTimestamp(metricDatum.getTimestamp());
              newMetricDatum.setMetricName(metricDatum.getMetricName());
              ArrayList<Dimension> newDimensionsList = Lists.newArrayList(permutation);
              Dimensions newDimensions = new Dimensions();
              newDimensions.setMember(newDimensionsList);
              newMetricDatum.setDimensions(newDimensions);
              AbsoluteMetricQueueItem newQueueItem = new AbsoluteMetricQueueItem();
              newQueueItem.setAccountId(queueItem.getAccountId());
              newQueueItem.setNamespace(queueItem.getNamespace());
              newQueueItem.setMetricDatum(newMetricDatum);
              foldedMetrics.add(newQueueItem);
            }
          } else {
            foldedMetrics.add(queueItem);
          }
        } else {
          foldedMetrics.add(queueItem);
        }
      }
    }
    return foldedMetrics;
  }

  static {
    dataFlushTimer.scheduleAtFixedRate(safeRunner, 0, 1, TimeUnit.MINUTES);
  }


  private void scrub(AbsoluteMetricQueueItem absoluteMetricQueueItem, Date now) {
    MetricDatum datum = absoluteMetricQueueItem.getMetricDatum();
    if (datum.getUnit() == null || datum.getUnit().trim().isEmpty()) datum.setUnit(Units.None.toString());
    if (datum.getTimestamp() == null) datum.setTimestamp(now);
  }

  private static Map<String, String> makeDimensionMap(
    final List<Dimension> dimensions
  ) {
    Map<String,String> returnValue = Maps.newTreeMap();
    for (Dimension dimension: dimensions) {
      returnValue.put(dimension.getName(), dimension.getValue());
    }
    return returnValue;
  }

  public void addQueueItems(List<AbsoluteMetricQueueItem> queueItems) {
    Date now = new Date();

    for (final AbsoluteMetricQueueItem queueItem : queueItems) {
      scrub(queueItem, now);
      dataQueue.offer(queueItem);
    }
  }


  private static class DBCleanupService implements Runnable {
    @Override
    public void run() {
      LOG.info("Calling absolute metric history (cloud) db cleanup service");
      if (!( Bootstrap.isOperational() &&
        Topology.isEnabled(Eucalyptus.class) )) {
        LOG.info("Eucalyptus service is not ENABLED");
        return;
      }

      Date thirtyMinutesAgo = new Date(System.currentTimeMillis() - 30 * 60 * 1000L);
      try {
        AbsoluteMetricHelper.deleteAbsoluteMetricHistory(thirtyMinutesAgo);
      } catch (Exception ex) {
        LOG.error(ex);
        LOG.error(ex, ex);
      }
      LOG.info("Done cleaning up absolute metric history (cloud) db");
    }
  }

}
