package com.example.choptorch;

import java.util.Locale;

/** Pure-Java rolling timing summary for one sensor; holds numbers only, never raw sensor values. */
public final class SensorTimingStats {
    private final String name;
    private final long intervalNs;
    private long windowStartNs, lastEventNs, lastArrivalNs;
    private long count, accepted, stale, outOfOrder, invalid, sumAgeNs, minAgeNs, maxAgeNs;
    private long maxEventGapNs, maxArrivalGapNs;

    public SensorTimingStats(String name, long intervalNs) {
        this.name = name;
        this.intervalNs = intervalNs;
    }

    /** Returns a summary line when the interval has elapsed, otherwise null. */
    public String record(long eventNs, long arrivalNs, SensorDeliveryGuard.Decision decision) {
        if (windowStartNs == 0) windowStartNs = arrivalNs;
        long ageNs = arrivalNs - eventNs;
        if (count == 0 || ageNs < minAgeNs) minAgeNs = ageNs;
        if (count == 0 || ageNs > maxAgeNs) maxAgeNs = ageNs;
        sumAgeNs += ageNs;
        count++;
        switch (decision) {
            case ACCEPT: accepted++; break;
            case DROP_STALE: stale++; break;
            case DROP_OUT_OF_ORDER: outOfOrder++; break;
            case DROP_INVALID_TIMESTAMP:
            case DROP_FUTURE: invalid++; break;
            default: throw new IllegalArgumentException("Unknown sensor decision: " + decision);
        }
        if (lastEventNs != 0) maxEventGapNs = Math.max(maxEventGapNs, eventNs - lastEventNs);
        if (lastArrivalNs != 0) maxArrivalGapNs = Math.max(maxArrivalGapNs, arrivalNs - lastArrivalNs);
        lastEventNs = eventNs;
        lastArrivalNs = arrivalNs;
        if (arrivalNs - windowStartNs < intervalNs) return null;
        String line = String.format(Locale.US,
                "%s n=%d accepted=%d stale=%d outOfOrder=%d invalid=%d "
                        + "ageMs[min=%.1f avg=%.1f max=%.1f] measurementGapMs[max=%.1f] arrivalGapMs[max=%.1f]",
                name, count, accepted, stale, outOfOrder, invalid,
                minAgeNs / 1e6, sumAgeNs / 1e6 / count, maxAgeNs / 1e6,
                maxEventGapNs / 1e6, maxArrivalGapNs / 1e6);
        windowStartNs = arrivalNs;
        count = accepted = stale = outOfOrder = invalid = sumAgeNs = minAgeNs = maxAgeNs = 0;
        maxEventGapNs = maxArrivalGapNs = 0;
        return line;
    }
}
