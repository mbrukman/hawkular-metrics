/*
 * Copyright 2014-2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.metrics.core.impl;

import static java.util.Collections.emptyMap;
import static java.util.Comparator.comparingLong;
import static java.util.concurrent.TimeUnit.MINUTES;

import static org.hawkular.metrics.core.api.MetricType.AVAILABILITY;
import static org.hawkular.metrics.core.api.MetricType.COUNTER;
import static org.hawkular.metrics.core.api.MetricType.COUNTER_RATE;
import static org.hawkular.metrics.core.api.MetricType.GAUGE;
import static org.hawkular.metrics.core.impl.Functions.getTTLAvailabilityDataPoint;
import static org.hawkular.metrics.core.impl.Functions.getTTLGaugeDataPoint;
import static org.hawkular.metrics.core.impl.Functions.makeSafe;
import static org.joda.time.Duration.standardMinutes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.hawkular.metrics.core.api.AvailabilityBucketPoint;
import org.hawkular.metrics.core.api.AvailabilityType;
import org.hawkular.metrics.core.api.Buckets;
import org.hawkular.metrics.core.api.DataPoint;
import org.hawkular.metrics.core.api.GaugeBucketPoint;
import org.hawkular.metrics.core.api.Metric;
import org.hawkular.metrics.core.api.MetricAlreadyExistsException;
import org.hawkular.metrics.core.api.MetricId;
import org.hawkular.metrics.core.api.MetricType;
import org.hawkular.metrics.core.api.MetricsService;
import org.hawkular.metrics.core.api.MetricsThreadFactory;
import org.hawkular.metrics.core.api.Retention;
import org.hawkular.metrics.core.api.Tenant;
import org.hawkular.metrics.core.api.TenantAlreadyExistsException;
import org.hawkular.metrics.core.impl.transformers.ItemsToSetTransformer;
import org.hawkular.metrics.core.impl.transformers.MetricsIndexRowTransformer;
import org.hawkular.metrics.core.impl.transformers.TagsIndexRowTransformer;
import org.hawkular.metrics.schema.SchemaManager;
import org.hawkular.metrics.tasks.api.RepeatingTrigger;
import org.hawkular.metrics.tasks.api.TaskScheduler;
import org.hawkular.metrics.tasks.api.Trigger;
import org.hawkular.rx.cassandra.driver.RxUtil;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import rx.Observable;
import rx.functions.Func1;
import rx.functions.Func3;

/**
 * @author John Sanda
 */
public class MetricsServiceImpl implements MetricsService, TenantsService {
    private static final Logger logger = LoggerFactory.getLogger(MetricsServiceImpl.class);

    /**
     * In seconds.
     */
    public static final int DEFAULT_TTL = Duration.standardDays(7).toStandardSeconds().getSeconds();

    public static final String SYSTEM_TENANT_ID = makeSafe("system");

    private static class DataRetentionKey {
        private final MetricId<?> metricId;

        public DataRetentionKey(String tenantId, MetricType<?> type) {
            metricId = new MetricId<>(tenantId, type, makeSafe(type.getText()));
        }

        public DataRetentionKey(MetricId<?> metricId) {
            this.metricId = metricId;
        }

        public DataRetentionKey(Metric<?> metric) {
            this.metricId = metric.getId();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            DataRetentionKey that = (DataRetentionKey) o;

            return metricId.equals(that.metricId);
        }

        @Override
        public int hashCode() {
            return metricId.hashCode();
        }
    }

    /**
     * Note that while user specifies the durations in hours, we store them in seconds.
     */
    private final Map<DataRetentionKey, Integer> dataRetentions = new ConcurrentHashMap<>();

    private ListeningExecutorService metricsTasks;

    private DataAccess dataAccess;

    private TaskScheduler taskScheduler;

    private DateTimeService dateTimeService;

    private MetricRegistry metricRegistry;

    /**
     * Measures the throughput of inserting gauge data points.
     */
    private Meter gaugeInserts;

    /**
     * Measures the throughput of inserting availability data points.
     */
    private Meter availabilityInserts;

    /**
     * Measures the throughput of inserting counter data points.
     */
    private Meter counterInserts;

    /**
     * Measures the latency of queries for gauge (raw) data.
     */
    private Timer gaugeReadLatency;

    /**
     * Measures the latency of queries for raw counter data.
     */
    private Timer counterReadLatency;

    /**
     * Measures the latency of queries for availability (raw) data.
     */
    private Timer availabilityReadLatency;

    public void startUp(Session session, String keyspace, boolean resetDb, MetricRegistry metricRegistry) {
        startUp(session, keyspace, resetDb, true, metricRegistry);
    }

    public void startUp(Session session, String keyspace, boolean resetDb, boolean createSchema,
            MetricRegistry metricRegistry) {
        SchemaManager schemaManager = new SchemaManager(session);
        if (resetDb) {
            schemaManager.dropKeyspace(keyspace);
        }
        if (createSchema) {
            // This creates/updates the keyspace + tables if needed
            schemaManager.createSchema(keyspace);
        }
        session.execute("USE " + keyspace);
        logger.info("Using a key space of '{}'", keyspace);
        metricsTasks = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4, new MetricsThreadFactory()));
        loadDataRetentions();

        this.metricRegistry = metricRegistry;
        initMetrics();

        initSystemTenant();
    }

    void loadDataRetentions() {
        List<String> tenantIds = loadTenantIds();
        CountDownLatch latch = new CountDownLatch(tenantIds.size() * 2);
        for (String tenantId : tenantIds) {
            DataRetentionsMapper gaugeMapper = new DataRetentionsMapper(tenantId, GAUGE);
            DataRetentionsMapper availMapper = new DataRetentionsMapper(tenantId, AVAILABILITY);
            ResultSetFuture gaugeFuture = dataAccess.findDataRetentions(tenantId, GAUGE);
            ResultSetFuture availabilityFuture = dataAccess.findDataRetentions(tenantId, AVAILABILITY);
            ListenableFuture<Set<Retention>> gaugeRetentions = Futures.transform(gaugeFuture, gaugeMapper,
                    metricsTasks);
            ListenableFuture<Set<Retention>> availabilityRetentions = Futures.transform(availabilityFuture, availMapper,
                    metricsTasks);
            Futures.addCallback(gaugeRetentions,
                    new DataRetentionsLoadedCallback(tenantId, GAUGE, latch));
            Futures.addCallback(availabilityRetentions, new DataRetentionsLoadedCallback(tenantId, AVAILABILITY,
                    latch));
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    void unloadDataRetentions() {
        dataRetentions.clear();
    }

    private void initSystemTenant() {
        CountDownLatch latch = new CountDownLatch(1);
        Trigger trigger = new RepeatingTrigger.Builder().withInterval(30, MINUTES).withDelay(30, MINUTES).build();
        dataAccess.insertTenant(new Tenant(SYSTEM_TENANT_ID))
                .filter(ResultSet::wasApplied)
                .map(row -> taskScheduler.scheduleTask(CreateTenants.TASK_NAME, SYSTEM_TENANT_ID, 100, emptyMap(),
                        trigger))
                .subscribe(
                        task -> logger.debug("Scheduled {}", task),
                        t -> {
                            logger.error("Failed to initialize system tenant", t);
                            latch.countDown();
                        },
                        () -> {
                            logger.debug("Successfully initialized system tenant");
                            latch.countDown();
                        }
                );
        try {
            latch.await();
        } catch (InterruptedException e) {
        }
    }

    private void initMetrics() {
        gaugeInserts = metricRegistry.meter("gauge-inserts");
        availabilityInserts = metricRegistry.meter("availability-inserts");
        counterInserts = metricRegistry.meter("counter-inserts");
        gaugeReadLatency = metricRegistry.timer("gauge-read-latency");
        availabilityReadLatency = metricRegistry.timer("availability-read-latency");
        counterReadLatency = metricRegistry.timer("counter-read-latency");
    }

    private static class MergeDataPointTagsFunction<T> implements
            Func1<List<Map<MetricId<T>, Set<DataPoint<T>>>>, Map<MetricId<T>, Set<DataPoint<T>>>> {

        @Override
        public Map<MetricId<T>, Set<DataPoint<T>>> call(List<Map<MetricId<T>, Set<DataPoint<T>>>> taggedDataMaps) {
            if (taggedDataMaps.isEmpty()) {
                return emptyMap();
            }
            if (taggedDataMaps.size() == 1) {
                return taggedDataMaps.get(0);
            }

            Set<MetricId<T>> ids = new HashSet<>(taggedDataMaps.get(0).keySet());
            for (int i = 1; i < taggedDataMaps.size(); ++i) {
                ids.retainAll(taggedDataMaps.get(i).keySet());
            }

            Map<MetricId<T>, Set<DataPoint<T>>> mergedDataMap = new HashMap<>();
            for (MetricId<T> id : ids) {
                TreeSet<DataPoint<T>> set = new TreeSet<>(comparingLong(DataPoint::getTimestamp));
                for (Map<MetricId<T>, Set<DataPoint<T>>> taggedDataMap : taggedDataMaps) {
                    set.addAll(taggedDataMap.get(id));
                }
                mergedDataMap.put(id, set);
            }

            return mergedDataMap;
        }
    }

    private class DataRetentionsLoadedCallback implements FutureCallback<Set<Retention>> {

        private final String tenantId;

        private final MetricType<?> type;

        private final CountDownLatch latch;

        public DataRetentionsLoadedCallback(String tenantId, MetricType<?> type, CountDownLatch latch) {
            this.tenantId = tenantId;
            this.type = type;
            this.latch = latch;
        }

        @Override
        public void onSuccess(Set<Retention> dataRetentionsSet) {
            for (Retention r : dataRetentionsSet) {
                dataRetentions.put(new DataRetentionKey(r.getId()), r.getValue());
            }
            latch.countDown();
        }

        @Override
        public void onFailure(Throwable t) {
            logger.warn("Failed to load data retentions for {tenantId: " + tenantId + ", metricType: " +
                    type.getText() + "}", t);
            latch.countDown();
            // TODO We probably should not let initialization proceed on this error
        }
    }

    /**
     * This is a test hook.
     */
    DataAccess getDataAccess() {
        return dataAccess;
    }

    /**
     * This is a test hook.
     */
    public void setDataAccess(DataAccess dataAccess) {
        this.dataAccess = dataAccess;
    }

    public void setTaskScheduler(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    public void setDateTimeService(DateTimeService dateTimeService) {
        this.dateTimeService = dateTimeService;
    }

    @Override
    public Observable<Void> createTenant(final Tenant tenant) {
        return Observable.create(subscriber -> {
            Observable<Void> updates = dataAccess.insertTenant(tenant).flatMap(resultSet -> {
                if (!resultSet.wasApplied()) {
                    throw new TenantAlreadyExistsException(tenant.getId());
                }

//                Trigger trigger = new RepeatingTrigger.Builder()
//                        .withDelay(1, MINUTES)
//                        .withInterval(1, MINUTES)
//                        .build();
//                Map<String, String> params = ImmutableMap.of("tenant", tenant.getId());
//                Observable<Void> ratesScheduled = taskScheduler.scheduleTask("generate-rates", tenant.getId(),
//                        100, params, trigger).map(task -> null);

                Observable<Void> retentionUpdates = Observable.from(tenant.getRetentionSettings().entrySet())
                        .flatMap(entry -> dataAccess.updateRetentionsIndex(tenant.getId(), entry.getKey(),
                                ImmutableMap.of(makeSafe(entry.getKey().getText()), entry.getValue())))
                        .map(rs -> null);

//                return ratesScheduled.concatWith(retentionUpdates);
                return retentionUpdates;
            });
            updates.subscribe(resultSet -> {
            }, subscriber::onError, subscriber::onCompleted);
        });
    }

    @Override
    public Observable<Void> createTenants(long creationTime, Observable<String> tenantIds) {
//        return tenantIds.flatMap(tenantId -> dataAccess.insertTenant(tenantId).flatMap(resultSet -> {
//            Trigger trigger = new RepeatingTrigger.Builder()
//                    .withDelay(1, MINUTES)
//                    .withInterval(1, MINUTES)
//                    .build();
//            Map<String, String> params = ImmutableMap.of(
//                    "tenant", tenantId,
//                    "creationTime", Long.toString(creationTime));
//
//            return taskScheduler.scheduleTask("generate-rates", tenantId, 100, params, trigger).map(task -> null);
//        }));

        return tenantIds.flatMap(tenantId -> dataAccess.insertTenant(tenantId).map(resultSet -> null));
    }

    @Override
    public Observable<Tenant> getTenants() {
        return dataAccess.findAllTenantIds()
                .flatMap(Observable::from)
                .map(row -> row.getString(0))
                .distinct()
                .flatMap(id ->
                                dataAccess.findTenant(id)
                                        .flatMap(Observable::from)
                                        .map(Functions::getTenant)
                                        .switchIfEmpty(Observable.just(new Tenant(id)))
                );
    }

    private List<String> loadTenantIds() {
        Iterable<String> tenantIds = dataAccess.findAllTenantIds()
                .flatMap(Observable::from)
                .map(row -> row.getString(0))
                .distinct()
                .toBlocking()
                .toIterable();
        return ImmutableList.copyOf(tenantIds);
    }

    @Override
    public Observable<Void> createMetric(Metric<?> metric) {
        MetricType<?> metricType = metric.getId().getType();
        if (!metricType.isUserType()) {
            throw new IllegalArgumentException(metric + " cannot be created. " + metricType + " metrics are " +
                    "internally generated metrics and cannot be created by clients.");
        }

        ResultSetFuture future = dataAccess.insertMetricInMetricsIndex(metric);
        Observable<ResultSet> indexUpdated = RxUtil.from(future, metricsTasks);
        return Observable.create(subscriber -> indexUpdated.subscribe(resultSet -> {
            if (!resultSet.wasApplied()) {
                subscriber.onError(new MetricAlreadyExistsException(metric));

            } else {
                // TODO Need error handling if either of the following updates fail
                // If adding tags/retention fails, then we want to report the error to the
                // client. Updating the retentions_idx table could also fail. We need to
                // report that failure as well.
                //
                // The error handling is the same as it was with Guava futures. That is, if any
                // future fails, we treat the entire client request as a failure. We probably
                // eventually want to implement more fine-grained error handling where we can
                // notify the subscriber of what exactly fails.
                List<Observable<ResultSet>> updates = new ArrayList<>();
                updates.add(dataAccess.addTagsAndDataRetention(metric));
                updates.add(dataAccess.insertIntoMetricsTagsIndex(metric, metric.getTags()));

                if (metric.getDataRetention() != null) {
                    updates.add(updateRetentionsIndex(metric));
                }

                Observable.merge(updates).subscribe(new VoidSubscriber<>(subscriber));
            }
        }));
    }

    private Observable<ResultSet> updateRetentionsIndex(Metric metric) {
        ResultSetFuture dataRetentionFuture = dataAccess.updateRetentionsIndex(metric);
        Observable<ResultSet> dataRetentionUpdated = RxUtil.from(dataRetentionFuture, metricsTasks);
        // TODO Shouldn't we only update dataRetentions map when the retentions index update succeeds?
        dataRetentions.put(new DataRetentionKey(metric), metric.getDataRetention());

        return dataRetentionUpdated;
    }

    @Override
    public <T> Observable<Metric<T>> findMetric(final MetricId<T> id) {
        return dataAccess.findMetric(id)
                .flatMap(Observable::from)
                .map(row -> new Metric<>(id, row.getMap(2, String.class, String.class), row.getInt(3)));
    }

    @Override
    public <T> Observable<Metric<T>> findMetrics(String tenantId, MetricType<T> metricType) {
        if (metricType == null) {
            return Observable.from(MetricType.userTypes())
                    .map(type -> {
                        @SuppressWarnings("unchecked")
                        MetricType<T> t = (MetricType<T>) type;
                        return t;
                    })
                    .flatMap(type -> dataAccess.findMetricsInMetricsIndex(tenantId, type)
                            .flatMap(Observable::from)
                            .compose(new MetricsIndexRowTransformer<>(tenantId, type)));
        }
        return dataAccess.findMetricsInMetricsIndex(tenantId, metricType)
                .flatMap(Observable::from)
                .compose(new MetricsIndexRowTransformer<>(tenantId, metricType));
    }

    @Override
    public <T> Observable<Metric<T>> findMetricsWithFilters(String tenantId, Map<String, String> tagsQueries,
                                                            MetricType<T> metricType) {
        // Fetch everything from the tagsQueries
        return Observable.from(tagsQueries.entrySet())
                .flatMap(entry -> {
                    // Special case "*" allowed and ! indicates match shouldn't happen
                    boolean positive = (!entry.getValue().startsWith("!"));
                    Pattern p = filterPattern(entry.getValue());

                    return Observable.just(entry)
                            .flatMap(e -> dataAccess.findMetricsByTagName(tenantId, e.getKey())
                                    .flatMap(Observable::from)
                                    .filter(r -> positive == p.matcher(r.getString(3)).matches()) // XNOR
                                    .compose(new TagsIndexRowTransformer<>(tenantId, metricType))
                                    .compose(new ItemsToSetTransformer<>())
                                    .reduce((s1, s2) -> {
                                        s1.addAll(s2);
                                        return s1;
                                    }));
                })
                .reduce((s1, s2) -> {
                    s1.retainAll(s2);
                    return s1;
                })
                .flatMap(Observable::from)
                .flatMap(this::findMetric);
    }

    /**
     * Allow special cases to Pattern matching, such as "*" -> ".*" and ! indicating the match shouldn't
     * happen. The first ! indicates the rest of the pattern should not match.
     *
     * @param inputRegexp Regexp given by the user
     *
     * @return Pattern modified to allow special cases in the query language
     */
    private Pattern filterPattern(String inputRegexp) {
        if (inputRegexp.equals("*")) {
            inputRegexp = ".*";
        } else if (inputRegexp.startsWith("!")) {
            inputRegexp = inputRegexp.substring(1);
        }
        return Pattern.compile(inputRegexp); // Catch incorrect patterns..
    }

    @Override
    public Observable<Optional<Map<String, String>>> getMetricTags(MetricId<?> id) {
        Observable<ResultSet> metricTags = dataAccess.getMetricTags(id, DataAccessImpl.DPART);

        return metricTags.flatMap(Observable::from).take(1).map(row -> Optional.of(row.getMap(0, String.class, String
                .class)))
                .defaultIfEmpty(Optional.empty());
    }

    // Adding/deleting metric tags currently involves writing to three tables - data,
    // metrics_idx, and metrics_tags_idx. It might make sense to refactor tag related
    // functionality into a separate class.
    @Override
    public Observable<Void> addTags(Metric<?> metric, Map<String, String> tags) {
        return dataAccess.addTags(metric, tags).mergeWith(dataAccess.insertIntoMetricsTagsIndex(metric, tags))
                .toList().map(l -> null);
    }

    @Override
    public Observable<Void> deleteTags(Metric<?> metric, Map<String, String> tags) {
        return dataAccess.deleteTags(metric, tags.keySet()).mergeWith(
                dataAccess.deleteFromMetricsTagsIndex(metric, tags)).toList().map(r -> null);
    }

    @Override
    public <T> Observable<Void> addDataPoints(Observable<Metric<T>> metrics) {
        // We write to both the data and the metrics_idx tables. Each metric can have one or more data points. We
        // currently write a separate batch statement for each metric.
        //
        // TODO Is there additional overhead of using batch statement when there is only a single insert?
        //      If there is overhead, then we should avoid using batch statements when the metric has only a single
        //      data point which could be quite often.
        //
        // The metrics_idx table stores the metric id along with any tags, and data retention. The update we perform
        // here though only inserts the metric id (i.e., name and interval). We need to revisit this logic. The original
        // intent for updating metrics_idx here is that even if the client does not explicitly create the metric, we
        // still have it in metrics_idx. In reality, I think clients will be explicitly creating metrics. This will
        // certainly be the case with the full, integrated hawkular server.
        //
        // TODO Determine how much overhead is caused by updating metrics_idx on every write
        //      If there much overhead, then we might not want to update the index every time we insert data. Maybe
        //      we periodically update it in the background, so we will still be aware of metrics that have not been
        //      explicitly created, just not necessarily right away.

        Observable<Integer> updates = metrics
                .filter(metric -> !metric.getDataPoints().isEmpty())
                .flatMap(metric -> {
                    MetricType<T> metricType = metric.getId().getType();
                    if (metricType == GAUGE || metricType == COUNTER_RATE) {
                        @SuppressWarnings("unchecked")
                        Metric<Double> gauge = (Metric<Double>) metric;
                        return dataAccess.insertGaugeData(gauge, getTTL(gauge.getId()))
                                .doOnNext(gaugeInserts::mark);
                    }
                    if (metricType == AVAILABILITY) {
                        @SuppressWarnings("unchecked")
                        Metric<AvailabilityType> avail = (Metric<AvailabilityType>) metric;
                        return dataAccess.insertAvailabilityData(avail, getTTL(avail.getId()))
                                .doOnNext(availabilityInserts::mark);
                    }
                    if (metricType == COUNTER) {
                        @SuppressWarnings("unchecked")
                        Metric<Long> counter = (Metric<Long>) metric;
                        return dataAccess.insertCounterData(counter, getTTL(counter.getId()))
                                .doOnNext(counterInserts::mark);
                    } else {
                        throw new UnsupportedOperationException(metricType.getText());
                    }
                });

        Observable<Integer> tenantUpdates = updateTenantBuckets(metrics);

        // I am intentionally return zero for the number index updates because I want to measure and compare the
        // throughput inserting data with and without the index updates. This will give us a better idea of how much
        // over there is with the index updates.
        Observable<Integer> indexUpdates = dataAccess.updateMetricsIndex(metrics).map(count -> 0);
        return Observable.concat(updates, indexUpdates, tenantUpdates)
                .takeLast(1)
                .map(count -> null);
    }

    private Observable<Integer> updateTenantBuckets(Observable<? extends Metric<?>> metrics) {
        return metrics.flatMap(metric -> {
            return Observable.<DataPoint<?>>from(metric.getDataPoints()).map(dataPoint -> {
                return new TenantBucket(metric.getId().getTenantId(),
                        dateTimeService.getTimeSlice(dataPoint.getTimestamp(), standardMinutes(30)));
            });
        }).distinct().flatMap(tenantBucket -> {
            return dataAccess.insertTenantId(tenantBucket.getBucket(), tenantBucket.getTenant()).map(resultSet -> 0);
        });
    }

    @Override
    public <T> Observable<DataPoint<T>> findDataPoints(MetricId<T> metricId, Long start, Long end) {
        Timer timer;
        Func3<MetricId<T>, Long, Long, Observable<ResultSet>> finder;
        Func1<Row, DataPoint<?>> mapper;
        MetricType<T> metricType = metricId.getType();
        if (metricType == GAUGE) {
            timer = gaugeReadLatency;
            finder = dataAccess::findData;
            mapper = Functions::getGaugeDataPoint;
        } else if (metricType == AVAILABILITY) {
            timer = availabilityReadLatency;
            finder = dataAccess::findAvailabilityData;
            mapper = Functions::getAvailabilityDataPoint;
        } else if (metricType == COUNTER) {
            timer = counterReadLatency;
            finder = dataAccess::findCounterData;
            mapper = Functions::getCounterDataPoint;
        } else {
            throw new UnsupportedOperationException(metricType.getText());
        }
        return time(timer, () -> finder.call(metricId, start, end)
                .flatMap(Observable::from)
                .map(mapper))
                .map(dataPoint -> {
                    @SuppressWarnings("unchecked")
                    DataPoint<T> typedPoint = (DataPoint<T>) dataPoint;
                    return typedPoint;
                });
    }

    @Override
    public Observable<DataPoint<Double>> findRateData(MetricId<Long> id, long start, long end) {
        DateTime startTime = dateTimeService.getTimeSlice(new DateTime(start), standardMinutes(1));
        DateTime endTime = dateTimeService.getTimeSlice(new DateTime(end), standardMinutes(1));

        Observable<DataPoint<Long>> rawDataPoints = findDataPoints(id, startTime.getMillis(), endTime.getMillis());

        return getCounterBuckets(rawDataPoints, startTime.getMillis(), endTime.getMillis())
//                .map(bucket -> bucket.isEmpty() ? null : new DataPoint<>(bucket.startTime, bucket.getDelta()));
                .map(bucket -> new DataPoint<>(bucket.startTime, bucket.getDelta()));
    }

    private Observable<CounterBucket> getCounterBuckets(Observable<DataPoint<Long>> dataPoints, long startTime,
                                                        long endTime) {
        return Observable.create(subscriber -> {
            final AtomicReference<CounterBucket> bucketRef = new AtomicReference<>(new CounterBucket(startTime));
            dataPoints.subscribe(
                    dataPoint -> {
                        while (!bucketRef.get().contains(dataPoint.getTimestamp())) {
                            subscriber.onNext(bucketRef.get());
                            bucketRef.set(new CounterBucket(bucketRef.get().endTime));
                        }
                        bucketRef.get().add(dataPoint);
                    },
                    subscriber::onError,
                    () -> {
                        subscriber.onNext(bucketRef.get());
                        CounterBucket bucket = new CounterBucket(bucketRef.get().endTime);
                        while (bucket.endTime <= endTime) {
                            subscriber.onNext(bucket);
                            bucket = new CounterBucket(bucket.endTime);
                        }
                        subscriber.onCompleted();
                    }
            );
        });
    }

    private class CounterBucket {
        DataPoint<Long> first;
        DataPoint<Long> last;
        long startTime;
        long endTime;

        public CounterBucket(long startTime) {
            this.startTime = startTime;
            this.endTime = startTime + 60000;
        }

        public void add(DataPoint<Long> dataPoint) {
            if (first == null && dataPoint.getTimestamp() >= startTime) {
                first = dataPoint;
                last = dataPoint;
            } else if (dataPoint.getTimestamp() >= startTime && dataPoint.getTimestamp() < endTime) {
                last = dataPoint;
            }
        }

        public boolean contains(long time) {
            return time >= startTime && time < endTime;
        }

        public boolean isEmpty() {
            return first == null && last == null;
        }

        public Double getDelta() {
            if (isEmpty()) {
                return Double.NaN;
            }
            if (first == last) {
                return ((double) first.getValue() / (endTime - startTime)) * 60000;
            }
            return (((double) last.getValue() - (double) first.getValue()) / (endTime - startTime)) * 60000;
        }

        @Override public String toString() {
            return "CounterBucket{" +
                    "first=" + first +
                    ", last=" + last +
                    ", startTime=" + startTime +
                    ", endTime=" + endTime +
                    '}';
        }
    }

    @Override
    public <T> Observable<T> findGaugeData(MetricId<Double> id, Long start, Long end,
                                           Func1<Observable<DataPoint<Double>>, Observable<T>>... funcs) {
        Observable<DataPoint<Double>> dataCache = findDataPoints(id, start, end).cache();
        return Observable.from(funcs).flatMap(fn -> fn.call(dataCache));
    }

    @Override
    public Observable<List<GaugeBucketPoint>> findGaugeStats(MetricId<Double> metricId, long start, long end,
                                                             Buckets buckets) {
        return findDataPoints(metricId, start, end)
                .groupBy(dataPoint -> buckets.getIndex(dataPoint.getTimestamp()))
                .flatMap(group -> group.collect(() -> new GaugeDataPointCollector(buckets, group.getKey()),
                        GaugeDataPointCollector::increment))
                .map(GaugeDataPointCollector::toBucketPoint)
                .toMap(GaugeBucketPoint::getStart)
                .map(pointMap -> GaugeBucketPoint.toList(pointMap, buckets));
    }

    @Override
    public Observable<DataPoint<AvailabilityType>> findAvailabilityData(MetricId<AvailabilityType> id, long start,
            long end, boolean distinct) {
        return time(availabilityReadLatency, () -> {
            Observable<DataPoint<AvailabilityType>> availabilityData = dataAccess.findAvailabilityData(id,
                    start, end)
                    .flatMap(Observable::from)
                    .map(Functions::getAvailabilityDataPoint);
            if (distinct) {
                return availabilityData.distinctUntilChanged(DataPoint::getValue);
            } else {
                return availabilityData;
            }
        });
    }

    @Override
    public Observable<List<AvailabilityBucketPoint>> findAvailabilityStats(MetricId<AvailabilityType> metricId,
                                                                           long start, long end, Buckets buckets) {
        return findDataPoints(metricId, start, end)
                .groupBy(dataPoint -> buckets.getIndex(dataPoint.getTimestamp()))
                .flatMap(group -> group.collect(() -> new AvailabilityDataPointCollector(buckets, group.getKey()),
                        AvailabilityDataPointCollector::increment))
                .map(AvailabilityDataPointCollector::toBucketPoint)
                .toMap(AvailabilityBucketPoint::getStart)
                .map(pointMap -> AvailabilityBucketPoint.toList(pointMap, buckets));
    }

    @Override
    public Observable<Boolean> idExists(final String id) {
        return dataAccess.findAllGaugeMetrics().flatMap(Observable::from)
                .filter(row -> id.equals(row.getString(2)))
                .take(1)
                .map(r -> Boolean.TRUE)
                .defaultIfEmpty(Boolean.FALSE);
    }

    @Override
    // TODO refactor to support multiple metrics
    // Data for different metrics and for the same tag are stored within the same partition
    // in the tags table; therefore, it makes sense for the API to support tagging multiple
    // metrics since they could efficiently be inserted in a single batch statement.
    public Observable<Void> tagGaugeData(Metric<Double> metric, final Map<String, String> tags, long start,
            long end) {
        Observable<ResultSet> findDataObservable = dataAccess.findData(metric.getId(), start, end, true);
        return tagGaugeData(findDataObservable, tags, metric);
    }

    private Observable<Void> tagGaugeData(Observable<ResultSet> findDataObservable, Map<String, String> tags,
            Metric<Double> metric) {
        int ttl = getTTL(metric.getId());
        Observable<Map.Entry<String, String>> tagsObservable = Observable.from(tags.entrySet()).cache();
        Observable<TTLDataPoint<Double>> dataPoints = findDataObservable.flatMap(Observable::from)
                .map(row -> getTTLGaugeDataPoint(row, ttl))
                .cache();

        Observable<ResultSet> tagInsert = tagsObservable
                .flatMap(t -> dataAccess.insertGaugeTag(t.getKey(), t.getValue(), metric, dataPoints));

        Observable<ResultSet> tagsInsert = dataPoints
                .flatMap(g -> dataAccess.updateDataWithTag(metric, g.getDataPoint(), tags));

        return tagInsert.concatWith(tagsInsert).map(r -> null);
    }

    private Observable<Void> tagAvailabilityData(Observable<ResultSet> findDataObservable, Map<String, String> tags,
            Metric<AvailabilityType> metric) {
        int ttl = getTTL(metric.getId());
        Observable<Map.Entry<String, String>> tagsObservable = Observable.from(tags.entrySet()).cache();
        Observable<TTLDataPoint<AvailabilityType>> dataPoints = findDataObservable.flatMap(Observable::from)
                .map(row -> getTTLAvailabilityDataPoint(row, ttl))
                .cache();

        Observable<ResultSet> tagInsert = tagsObservable
                .flatMap(t -> dataAccess.insertAvailabilityTag(t.getKey(), t.getValue(), metric, dataPoints));

        Observable<ResultSet> tagsInsert = dataPoints
                .flatMap(a -> dataAccess.updateDataWithTag(metric, a.getDataPoint(), tags));

        return tagInsert.concatWith(tagsInsert).map(r -> null);
    }

    @Override
    public Observable<Void> tagAvailabilityData(Metric<AvailabilityType> metric, Map<String, String> tags,
            long start, long end) {
        Observable<ResultSet> findDataObservable = dataAccess.findAvailabilityData(metric, start, end, true);
        return tagAvailabilityData(findDataObservable, tags, metric);
    }

    @Override
    public Observable<Void> tagGaugeData(Metric<Double> metric, final Map<String, String> tags,
            long timestamp) {
        Observable<ResultSet> findDataObservable = dataAccess.findData(metric, timestamp, true);
        return tagGaugeData(findDataObservable, tags, metric);
    }

    @Override
    public Observable<Void> tagAvailabilityData(Metric<AvailabilityType> metric, Map<String, String> tags,
            long timestamp) {
        Observable<ResultSet> findDataObservable = dataAccess.findAvailabilityData(metric, timestamp);
        return tagAvailabilityData(findDataObservable, tags, metric);
    }

    @Override
    public Observable<Map<MetricId<Double>, Set<DataPoint<Double>>>> findGaugeDataByTags(String tenantId,
                                                                                         Map<String, String> tags) {
        return Observable.from(tags.entrySet())
                .flatMap(e -> dataAccess.findGaugeDataByTag(tenantId, e.getKey(), e.getValue()))
                .map(TaggedGaugeDataPointMapper::apply)
                .toList()
                .map(new MergeDataPointTagsFunction<>());
    }

    @Override
    public Observable<Map<MetricId<AvailabilityType>, Set<DataPoint<AvailabilityType>>>> findAvailabilityByTags(
            String tenantId, Map<String, String> tags) {
        return Observable.from(tags.entrySet())
                .flatMap(e -> dataAccess.findAvailabilityByTag(tenantId, e.getKey(), e.getValue()))
                .map(TaggedAvailabilityDataPointMapper::apply)
                .toList()
                .map(new MergeDataPointTagsFunction<>());
    }

    @Override
    public Observable<List<long[]>> getPeriods(MetricId<Double> id, Predicate<Double> predicate, long start, long end) {
        return dataAccess.findData(new Metric<>(id), start, end, Order.ASC)
                .flatMap(Observable::from)
                .map(Functions::getGaugeDataPoint)
                .toList().map(data -> {
                    List<long[]> periods = new ArrayList<>(data.size());
                    long[] period = null;
                    DataPoint<Double> previous = null;
                    for (DataPoint<Double> d : data) {
                        if (predicate.test(d.getValue())) {
                            if (period == null) {
                                period = new long[2];
                                period[0] = d.getTimestamp();
                            }
                            previous = d;
                        } else if (period != null) {
                            period[1] = previous.getTimestamp();
                            periods.add(period);
                            period = null;
                            previous = null;
                        }
                    }
                    if (period != null) {
                        period[1] = previous.getTimestamp();
                        periods.add(period);
                    }
                    return periods;
                });
    }

    private int getTTL(MetricId<?> metricId) {
        Integer ttl = dataRetentions.get(new DataRetentionKey(metricId));
        if (ttl == null) {
            ttl = dataRetentions.get(new DataRetentionKey(metricId.getTenantId(), metricId.getType()));
            if (ttl == null) {
                ttl = DEFAULT_TTL;
            }
        }
        return ttl;
    }

    public void shutdown() {
        metricsTasks.shutdown();
        unloadDataRetentions();
    }

    private <T> T time(Timer timer, Callable<T> callable) {
        try {
            // TODO Should this method always return an observable?
            // If so, than we should return Observable.error(e) in the catch block
            return timer.time(callable);
        } catch (Exception e) {
            throw new RuntimeException("There was an error during a timed event", e);
        }
    }

    private static class TenantBucket {
        String tenant;
        long bucket;

        public TenantBucket(String tenant, long bucket) {
            this.tenant = tenant;
            this.bucket = bucket;
        }

        public String getTenant() {
            return tenant;
        }

        public long getBucket() {
            return bucket;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TenantBucket that = (TenantBucket) o;
            return Objects.equals(bucket, that.bucket) &&
                    Objects.equals(tenant, that.tenant);
        }

        @Override public int hashCode() {
            return Objects.hash(tenant, bucket);
        }
    }

}
