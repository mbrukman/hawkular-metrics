package org.rhq.metrics.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Objects;

import org.rhq.metrics.util.TimeUUIDUtils;

/**
 * A numeric metric data point. This class currently represents both raw and aggregated data; however, at some point
 * we may subclasses for each of them.
 *
 * @author John Sanda
 */
public class NumericData {

    private String tenantId;

    private String metric;

    private Interval interval;

    private long dpart;

    private UUID timeUUID;

    private Map<String, String> attributes = new HashMap<>();

    // value and aggregatedValues are mutually exclusive. One or the other should be set
    // but not both. It may make sense to introduce subclasses for raw and aggregated data.

    private double value;

    private Set<AggregatedValue> aggregatedValues = new HashSet<>();

    /**
     * The tenant to which this metric belongs.
     */
    public String getTenantId() {
        return tenantId;
    }

    public NumericData setTenantId(String tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    /**
     * The metric name. The metric name and the interval must be unique across numeric metrics.
     */
    public String getMetric() {
        return metric;
    }

    public NumericData setMetric(String metric) {
        this.metric = metric;
        return this;
    }

    /**
     * This is for use with aggregated metrics. It specifies how frequently the metric is updated or computed. If we
     * introduce subclasses for raw and aggregated data, I think this would only go in the aggregated data class.
     */
    public Interval getInterval() {
        return interval;
    }

    public NumericData setInterval(Interval interval) {
        this.interval = interval;
        return this;
    }

    /**
     * Currently not used. It wil be used for breaking up a metric time series into multiple partitions by date. For
     * example, if we choose to partition by month, then all data collected for a given metric during October would go
     * into one partition, and data collected during November would go into another partition. This will not impact
     * writes, but it will make reads more complicated and potentially more expensive if we make the date partition too
     * small.
     */
    public long getDpart() {
        return dpart;
    }

    public NumericData setDpart(long dpart) {
        this.dpart = dpart;
        return this;
    }

    /**
     * The time based UUID for this data point
     */
    public UUID getTimeUUID() {
        return timeUUID;
    }

    public NumericData setTimeUUID(UUID timeUUID) {
        this.timeUUID = timeUUID;
        return this;
    }

    /**
     * The UNIX timestamp of the {@link #getTimeUUID() timeUUID}
     */
    public long getTimestamp() {
        return UUIDs.unixTimestamp(timeUUID);
    }

    /**
     * Sets the {@link #getTimeUUID() timeUUID} using the UNIX timestamp
     */
    public NumericData setTimestamp(long timestamp) {
        timeUUID = TimeUUIDUtils.getTimeUUID(timestamp);
        return this;
    }

    /**
     * A set of key/value pairs that are shared by all data points for the metric. A good example is units like KB / sec.
     */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /**
     * Stores an attribute which will be shared by all data points for the metric when it is persisted. If an attribute
     * with the same name already exists, it will be overwritten.
     *
     * @param name The attribute name.
     * @param value The attribute value
     */
    public NumericData putAttribute(String name, String value) {
        attributes.put(name, value);
        return this;
    }

    /**
     * Stores attributes which will be shared by all data points for the metric. If an attribute with the same name
     * already exists, it will be overwritten.
     *
     * @param attributes The key/value pairs to store.
     * @return
     */
    public NumericData putAttributes(Map<String, String> attributes) {
        this.attributes.putAll(attributes);
        return this;
    }

    /**
     * The value of the raw data point. This should only be set for raw data. It should be null for aggregated data.
     */
    public double getValue() {
        return value;
    }

    public NumericData setValue(double value) {
        this.value = value;
        return this;
    }

    /**
     * A set of the aggregated values that make up this aggregated data point. This should return an empty set for raw
     * data.
     */
    public Set<AggregatedValue> getAggregatedValues() {
        return aggregatedValues;
    }

    public NumericData addAggregatedValue(AggregatedValue aggregatedValue) {
        aggregatedValues.add(aggregatedValue);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NumericData that = (NumericData) o;

        if (dpart != that.dpart) return false;
        if (Double.compare(that.value, value) != 0) return false;
        if (!attributes.equals(that.attributes)) return false;
        if (interval != null ? !interval.equals(that.interval) : that.interval != null) return false;
        if (!metric.equals(that.metric)) return false;
        if (!tenantId.equals(that.tenantId)) return false;
        if (!timeUUID.equals(that.timeUUID)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        result = tenantId.hashCode();
        result = 31 * result + metric.hashCode();
        result = 31 * result + (interval != null ? interval.hashCode() : 0);
        result = 31 * result + (int) (dpart ^ (dpart >>> 32));
        result = 31 * result + timeUUID.hashCode();
        result = 31 * result + attributes.hashCode();
        temp = Double.doubleToLongBits(value);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("tenantId", tenantId)
            .add("metric", metric)
            .add("interval", interval)
            .add("dpart", dpart)
            .add("attributes", attributes)
            .add("timeUUID", timeUUID)
            .add("timestamp", UUIDs.unixTimestamp(timeUUID))
            .add("value", value)
            .toString();
    }
}