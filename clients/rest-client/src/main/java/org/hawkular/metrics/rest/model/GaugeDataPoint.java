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
package org.hawkular.metrics.rest.model;

import java.util.Objects;

/**
 * @author jsanda
 */
public class GaugeDataPoint implements DataPoint<Double> {

    private long timestamp;

    private double value;

    private GaugeDataPoint() {
    }

    public GaugeDataPoint(long timestamp, double value) {
        this.timestamp = timestamp;
        this.value = value;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public Double getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GaugeDataPoint that = (GaugeDataPoint) o;
        return Objects.equals(timestamp, that.timestamp) &&
                Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, value);
    }

    @Override
    public String toString() {
        return "GaugeDataPoint{" +
                "timestamp=" + timestamp +
                ", value=" + value +
                '}';
    }
}
