/*
 * Copyright 2014-2016 Red Hat, Inc. and/or its affiliates
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
package org.hawkular.metrics.api.jaxrs.handler;

import static java.util.stream.Collectors.toList;

import static org.hawkular.metrics.api.jaxrs.filter.TenantFilter.TENANT_HEADER_NAME;
import static org.hawkular.metrics.api.jaxrs.util.ApiUtils.badRequest;
import static org.hawkular.metrics.model.MetricType.COUNTER;
import static org.hawkular.metrics.model.MetricType.COUNTER_RATE;
import static org.hawkular.metrics.model.MetricType.GAUGE;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import javax.inject.Inject;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.hawkular.metrics.api.jaxrs.QueryRequest;
import org.hawkular.metrics.api.jaxrs.handler.observer.NamedDataPointObserver;
import org.hawkular.metrics.core.service.MetricsService;
import org.hawkular.metrics.core.service.Order;
import org.hawkular.metrics.model.ApiError;
import org.hawkular.metrics.model.MetricId;
import org.hawkular.metrics.model.MetricType;
import org.hawkular.metrics.model.NamedDataPoint;
import org.hawkular.metrics.model.param.TimeRange;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import rx.Observable;
import rx.schedulers.Schedulers;

/**
 * @author jsanda
 */
public abstract class MetricsServiceHandler {

    @Inject
    protected MetricsService metricsService;

    @Inject
    protected ObjectMapper mapper;

    @HeaderParam(TENANT_HEADER_NAME)
    protected String tenantId;

    protected <T> Response findRawDataPointsForMetrics(QueryRequest query, MetricType<T> type) {
        TimeRange timeRange = new TimeRange(query.getStart(), query.getEnd());
        if (!timeRange.isValid()) {
            return badRequest(new ApiError(timeRange.getProblem()));
        }

        int limit;
        if (query.getLimit() == null) {
            limit = 0;
        } else {
            limit = query.getLimit();
        }
        Order order;
        if (query.getOrder() == null) {
            order = Order.defaultValue(limit, timeRange.getStart(), timeRange.getEnd());
        } else {
            order = Order.fromText(query.getOrder());
        }

        if (query.getIds().isEmpty()) {
            return badRequest(new ApiError("Metric ids must be specified"));
        }

        List<MetricId<T>> metricIds = query.getIds().stream().map(id -> new MetricId<>(tenantId, type, id))
                .collect(toList());
        Observable<NamedDataPoint<T>> dataPoints = metricsService.findDataPoints(metricIds, timeRange.getStart(),
                timeRange.getEnd(), limit, order).observeOn(Schedulers.io());

        StreamingOutput stream = output -> {
            JsonGenerator generator = mapper.getFactory().createGenerator(output, JsonEncoding.UTF8);
            CountDownLatch latch = new CountDownLatch(1);
            dataPoints.subscribe(new NamedDataPointObserver<>(generator, latch, type));

            try {
                // We have to block here and wait for all of the results to be streamed before returning; otherwise,
                // we will return too soon and the container will close the stream early.
                latch.await();
            } catch (InterruptedException e) {
                throw new WebApplicationException("There was an interrupt while fetching raw data points", e);
            }
        };

        return Response.ok(stream).build();
    }

    protected Response findRateDataPointsForMetrics(QueryRequest query, MetricType<? extends Number> type) {
        TimeRange timeRange = new TimeRange(query.getStart(), query.getEnd());
        if (!timeRange.isValid()) {
            return badRequest(new ApiError(timeRange.getProblem()));
        }

        int limit;
        if (query.getLimit() == null) {
            limit = 0;
        } else {
            limit = query.getLimit();
        }
        Order order;
        if (query.getOrder() == null) {
            order = Order.defaultValue(limit, timeRange.getStart(), timeRange.getEnd());
        } else {
            order = Order.fromText(query.getOrder());
        }

        if (query.getIds().isEmpty()) {
            return badRequest(new ApiError("Metric ids must be specified"));
        }

        List<MetricId<? extends Number>> metricIds = query.getIds().stream().map(id -> new MetricId<>(tenantId, type,
                id)).collect(toList());
        Observable<NamedDataPoint<Double>> dataPoints = metricsService.findRateData(metricIds, timeRange.getStart(),
                timeRange.getEnd(), limit, order).observeOn(Schedulers.io());

        StreamingOutput stream = output -> {
            JsonGenerator generator = mapper.getFactory().createGenerator(output, JsonEncoding.UTF8);
            CountDownLatch latch = new CountDownLatch(1);
            if (type == GAUGE) {
                dataPoints.subscribe(new NamedDataPointObserver<>(generator, latch, GAUGE));
            } else if (type == COUNTER) {
                dataPoints.subscribe(new NamedDataPointObserver<>(generator, latch, COUNTER_RATE));
            } else {
                throw new IllegalArgumentException(type + " is not a supported metric type for rate data points");
            }
            try {
                // We have to block here and wait for all of the results to be streamed before returning; otherwise,
                // we will return too soon and the container will close the stream early.
                latch.await();
            } catch (InterruptedException e) {
                throw new WebApplicationException("There was an interrupt while fetching raw data points", e);
            }
        };

        return Response.ok(stream).build();
    }
}
