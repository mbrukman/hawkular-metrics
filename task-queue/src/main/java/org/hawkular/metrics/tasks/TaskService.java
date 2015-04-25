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
package org.hawkular.metrics.tasks;

import static java.util.stream.Collectors.toList;
import static org.joda.time.DateTime.now;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.google.common.base.Function;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author jsanda
 */
public class TaskService {

    private static final Logger logger = LoggerFactory.getLogger(TaskService.class);

    private Duration timeSliceDuration;

    private Session session;

    private Queries queries;

    private List<TaskType> taskTypes;

    private LeaseManager leaseManager;

    private ScheduledExecutorService ticker = Executors.newScheduledThreadPool(1);

    private ExecutorService scheduler = Executors.newSingleThreadExecutor();

    private ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4));

    /**
     * Used to limit the amount of leases we try to acquire concurrently to the same number of worker threads available
     * for processing tasks.
     */
    private Semaphore permits = new Semaphore(4);

    private String owner;

    private DateTimeService dateTimeService;

    public TaskService(Session session, Queries queries, LeaseManager leaseManager, Duration timeSliceDuration,
            List<TaskType> taskTypes) {
        this.session = session;
        this.queries = queries;
        this.leaseManager = leaseManager;
        this.timeSliceDuration = timeSliceDuration;
        this.taskTypes = taskTypes;
        try {
            owner = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw new RuntimeException("Failed to initialize owner name", e);
        }
        dateTimeService = new DateTimeService();
    }

    public void start() {
        Runnable runnable = () -> {
            DateTime timeSlice = dateTimeService.getTimeSlice(now(), Duration.standardSeconds(1));
            scheduler.submit(() -> executeTasks(timeSlice));
        };
        ticker.scheduleAtFixedRate(runnable, 1, 1, TimeUnit.SECONDS);
    }

    public ListenableFuture<List<Task>> findTasks(String type, DateTime timeSlice, int segment) {
        ResultSetFuture future = session.executeAsync(queries.findTasks.bind(type, timeSlice.toDate(), segment));
        TaskType taskType = findTaskType(type);
        return Futures.transform(future, (ResultSet resultSet) -> StreamSupport.stream(resultSet.spliterator(), false)
                .map(row -> new Task(taskType, row.getString(0), row.getSet(1, String.class), row.getInt(2),
                        row.getInt(3)))
                .collect(toList()));
    }

    private TaskType findTaskType(String type) {
        return taskTypes.stream()
                .filter(t->t.getName().equals(type))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(type + " is not a recognized task type"));
    }

    public ListenableFuture<DateTime> scheduleTask(DateTime time, Task task) {
        TaskType taskType = findTaskType(task.getTaskType().getName());

        DateTime currentTimeSlice = dateTimeService.getTimeSlice(time, timeSliceDuration);
        DateTime timeSlice = currentTimeSlice.plus(task.getInterval());

        return scheduleTaskAt(timeSlice, task, taskType);
    }

    private ListenableFuture<DateTime> rescheduleTask(DateTime currentTimeSlice, Task task) {
        DateTime nextTimeSlice = currentTimeSlice.plus(task.getInterval());
        return scheduleTaskAt(nextTimeSlice, task, task.getTaskType());
    }

    private ListenableFuture<DateTime> scheduleTaskAt(DateTime time, Task task, TaskType taskType) {
        int segment = Math.abs(task.getTarget().hashCode() % taskType.getSegments());
        int segmentsPerOffset = taskType.getSegments() / taskType.getSegmentOffsets();
        int segmentOffset = (segment / segmentsPerOffset) * segmentsPerOffset;

        ResultSetFuture queueFuture = session.executeAsync(queries.createTask.bind(taskType.getName(),
                time.toDate(), segment, task.getTarget(), task.getSources(), (int) task.getInterval()
                        .getStandardMinutes(), (int) task.getWindow().getStandardMinutes()));
        ResultSetFuture leaseFuture = session.executeAsync(queries.createLease.bind(time.toDate(),
                taskType.getName(), segmentOffset));
        ListenableFuture<List<ResultSet>> futures = Futures.allAsList(queueFuture, leaseFuture);

        return Futures.transform(futures, (List<ResultSet> resultSets) -> time);
    }

    public void executeTasks(DateTime timeSlice) {
        try {
            // Execute tasks in order of task types. Once all of the tasks are executed, we delete the lease partition.
            taskTypes.forEach(taskType -> executeTasks(timeSlice, taskType));
            Uninterruptibles.getUninterruptibly(leaseManager.deleteLeases(timeSlice));
        } catch (ExecutionException e) {
            logger.warn("Failed to delete lease partition for time slice " + timeSlice);
        }
    }

    /**
     * This method does not return until all tasks of the specified type have been executed.
     *
     * @param timeSlice
     * @param taskType
     */
    private void executeTasks(DateTime timeSlice, TaskType taskType) {
        try {
            List<Lease> leases = Uninterruptibles.getUninterruptibly(leaseManager.findUnfinishedLeases(timeSlice))
                    .stream().filter(lease -> lease.getTaskType().equals(taskType.getName())).collect(toList());

            // A CountDownLatch is used to let us know when to query again for leases. We do not want to query (again)
            // for leases until we have gone through each one. If a lease already has an owner, then we just count
            // down the latch and move on. If the lease does not have an owner, we attempt to acquire it. When the
            // result from trying to acquire the lease are available, we count down the latch.
            AtomicReference<CountDownLatch> latchRef = new AtomicReference<>(new CountDownLatch(leases.size()));


            // Keep checking for and process leases as long as the query returns leases and there is at least one
            // that is not finished. When these conditions do not hold, then the leases for the current time slice
            // are finished.
            while (!(leases.isEmpty() || leases.stream().allMatch(Lease::isFinished))) {
                for (final Lease lease : leases) {
                    if (lease.getOwner() == null) {
                        permits.acquire();
                        lease.setOwner(owner);
                        ListenableFuture<Boolean> acquiredFuture = leaseManager.acquire(lease);
                        Futures.addCallback(acquiredFuture, new FutureCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean acquired) {
                                latchRef.get().countDown();
                                if (acquired) {
                                    List<ListenableFuture<ResultSet>> deleteFutures = new ArrayList<>();
                                    TaskType taskType = findTaskType(lease.getTaskType());
                                    for (int i = lease.getSegmentOffset(); i < taskType.getSegments(); ++i) {
                                        ListenableFuture<List<Task>> tasksFuture = findTasks(lease.getTaskType(),
                                                timeSlice, i);
                                        ListenableFuture<ExecutionResults> resultsFuture =
                                                Futures.transform(tasksFuture, executeTaskSegment1(timeSlice,
                                                        taskType, i), workers);
                                        ListenableFuture<List<DateTime>> nextExecutionsFuture = Futures.transform(
                                                resultsFuture, scheduleNextExecution, workers);
                                        ListenableFuture<ResultSet> deleteFuture = Futures.transform(
                                                nextExecutionsFuture, deleteTaskSegment(timeSlice, taskType, i));
                                        deleteFutures.add(deleteFuture);
                                    }
                                    ListenableFuture<List<ResultSet>> deletesFuture =
                                            Futures.allAsList(deleteFutures);
                                    ListenableFuture<Boolean> leaseFinishedFuture = Futures.transform(deletesFuture,
                                            (List<ResultSet> resultSets) -> leaseManager.finish(lease), workers);
                                    Futures.addCallback(leaseFinishedFuture, leaseFinished(lease), workers);
                                } else {
                                    // someone else has the lease so return the permit and try to
                                    // acquire another lease
                                    permits.release();
                                }
                            }

                            @Override
                            public void onFailure(Throwable t) {
                                logger.warn("There was an error trying to acquire a lease", t);
                                latchRef.get().countDown();
                            }
                        }, workers);
                    } else {
                        latchRef.get().countDown();
                    }
                }
                latchRef.get().await();
                leases = Uninterruptibles.getUninterruptibly(leaseManager.findUnfinishedLeases(timeSlice))
                        .stream().filter(lease -> lease.getTaskType().equals(taskType.getName())).collect(toList());
                latchRef.set(new CountDownLatch(leases.size()));
            }

        } catch (ExecutionException e) {
            logger.warn("Failed to load leases for time slice " + timeSlice, e);
        } catch (InterruptedException e) {
            logger.warn("There was an interrupt", e);
        }
    }

    private Function<List<Task>, ExecutionResults> executeTaskSegment1(DateTime timeSlice, TaskType taskType,
            int segment) {
        return tasks -> {
            ExecutionResults results = new ExecutionResults(timeSlice, taskType, segment);
            tasks.forEach(task -> {
                Runnable taskRunner = taskType.getFactory().apply(task);
                try {
                    taskRunner.run();
                    results.add(new ExecutedTask(task, true));
                } catch (Throwable t) {
                    logger.warn("Failed to to execute " + task, t);
                    results.add(new ExecutedTask(task, false));
                }
            });
            return results;
        };
    }

    private AsyncFunction<ExecutionResults, List<DateTime>> scheduleNextExecution = results -> {
        List<ListenableFuture<DateTime>> scheduledFutures = new ArrayList<>();
        results.getExecutedTasks().forEach(task ->
            scheduledFutures.add(rescheduleTask(results.getTimeSlice(), task)));
        return Futures.allAsList(scheduledFutures);
    };

    private AsyncFunction<List<DateTime>, ResultSet> deleteTaskSegment(DateTime timeSlice, TaskType taskType,
            int segment) {
        return nextExecutions -> session.executeAsync(queries.deleteTasks.bind(taskType.getName(), timeSlice.toDate(),
                segment));
    }

    private FutureCallback<Boolean> leaseFinished(Lease lease) {
        return new FutureCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean finished) {
                if (!finished) {
                    logger.warn("All tasks for {} have completed but unable to set it to finished", lease);
                }
                permits.release();
            }

            @Override
            public void onFailure(Throwable t) {
                // We can wind up in the onFailure callback when either any of the task segment deletions fail or when
                // marking the lease finished fails with an error. In order to determine what exactly failed, we need
                // to register additional callbacks on each future with either Futures.addCallback or
                // Futures.withFallback. Neither is particular appealing as it makes the code more complicated. This is
                // one of a growing number of reasons I want to prototype a solution using RxJava.

                logger.warn("There was an error either while deleting one or more task segments or while attempting "
                        + "to mark " + lease + " finished", t);
                permits.release();
            }
        };
    }

}
