/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.metrics.core.impl.cassandra;

import static org.hawkular.metrics.core.api.AvailabilityType.DOWN;

import java.util.List;

import org.hawkular.metrics.core.api.Availability;
import org.hawkular.metrics.core.api.AvailabilityBucketDataPoint;
import org.hawkular.metrics.core.api.AvailabilityMetric;
import org.hawkular.metrics.core.api.Buckets;

/**
 * A {@link BucketedOutputMapper} for {@link org.hawkular.metrics.core.api.AvailabilityMetric}.
 *
 * @author Thomas Segismont
 */
public class AvailabilityBucketedOutputMapper
        extends BucketedOutputMapper<Availability, AvailabilityMetric, AvailabilityBucketDataPoint> {

    /**
     * @param buckets the bucket configuration
     */
    public AvailabilityBucketedOutputMapper(Buckets buckets) {
        super(buckets);
    }

    @Override
    protected AvailabilityBucketDataPoint newEmptyPointInstance(long from) {
        return AvailabilityBucketDataPoint.newEmptyInstance(from);
    }

    @Override
    protected AvailabilityBucketDataPoint newPointInstance(long from, List<Availability> availabilities) {
        long downtimeDuration = 0, lastDowntime = 0, downtimeCount = 0;
        for (int i = 0; i < availabilities.size(); i++) {
            Availability availability = availabilities.get(i);
            long leftTimestamp;
            if (i == 0) {
                leftTimestamp = buckets.getStart();
            } else {
                leftTimestamp = availability.getTimestamp();
            }
            long rightTimestamp;
            if (i == availabilities.size() - 1) {
                rightTimestamp = buckets.getEnd();
            } else {
                rightTimestamp = availabilities.get(i + 1).getTimestamp();
            }
            if (availability.getType() == DOWN) {
                downtimeDuration += rightTimestamp - leftTimestamp;
                lastDowntime = rightTimestamp;
                downtimeCount += i == 0 ? 2 : 1;
            }
        }

        AvailabilityBucketDataPoint dataPoint = new AvailabilityBucketDataPoint();
        dataPoint.setTimestamp(from);
        dataPoint.setDowntimeDuration(downtimeDuration);
        dataPoint.setLastDowntime(lastDowntime);
        dataPoint.setUptimeRatio(1.0 - ((double) downtimeDuration) / buckets.getStep());
        dataPoint.setDowntimeCount(downtimeCount);

        return dataPoint;
    }
}
