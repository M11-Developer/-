package com.mirsad.metalscope.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A small, Android-free signal-processing pipeline for the phone's real magnetometer.
 *
 * It deliberately reports magnetic-field anomalies rather than claiming to identify a
 * particular metal. The absolute field strength is not a useful universal threshold:
 * the Earth's field, phone orientation and the sensor itself all vary by device and place.
 */
public final class SignalProcessor {
    private static final int MEDIAN_WINDOW_SIZE = 5;
    private static final long CALIBRATION_MIN_DURATION_NS = 4_000_000_000L;
    private static final long CALIBRATION_TIMEOUT_NS = 10_000_000_000L;
    private static final int MIN_CALIBRATION_SAMPLES = 25;
    private static final int TRIP_SAMPLES = 3;
    private static final int RELEASE_SAMPLES = 7;

    private final Deque<Double> recentMagnitudes = new ArrayDeque<>();
    private final List<Double> calibrationSamples = new ArrayList<>();

    private double x;
    private double y;
    private double z;
    private double rawMagnitude;
    private double filteredMagnitude = Double.NaN;
    private double baseline = Double.NaN;
    private double noiseFloor = 0.25;
    private double sensitivity = 6.0;
    private double sensorResolution = 0.15;
    private double sensorMaximumRange;
    private long previousTimestampNs = -1L;
    private long calibrationStartedAtNs;

    private boolean hasBaseline;
    private boolean calibrating;
    private boolean scanning;
    private boolean alerting;
    private int samplesAboveThreshold;
    private int samplesBelowReleaseThreshold;

    public void setSensorCharacteristics(double resolutionMicrotesla, double maximumRangeMicrotesla) {
        if (isFinite(resolutionMicrotesla) && resolutionMicrotesla > 0.0) {
            sensorResolution = resolutionMicrotesla;
        }
        if (isFinite(maximumRangeMicrotesla) && maximumRangeMicrotesla > 0.0) {
            sensorMaximumRange = maximumRangeMicrotesla;
        }
    }

    public void setSensitivity(double thresholdMicrotesla) {
        if (isFinite(thresholdMicrotesla)) {
            sensitivity = clamp(thresholdMicrotesla, 2.0, 30.0);
        }
    }

    public double getSensitivity() {
        return sensitivity;
    }

    public boolean hasBaseline() {
        return hasBaseline;
    }

    public boolean isCalibrating() {
        return calibrating;
    }

    public boolean isScanning() {
        return scanning;
    }

    /** Start collecting a local ambient reference. This is not a factory/ellipsoid calibration. */
    public void beginCalibration(long timestampNs) {
        calibrating = true;
        calibrationStartedAtNs = timestampNs;
        calibrationSamples.clear();
        setScanning(false);
    }

    public void cancelCalibration() {
        calibrating = false;
        calibrationSamples.clear();
    }

    public void setScanning(boolean enabled) {
        scanning = enabled && hasBaseline && !calibrating;
        alerting = false;
        samplesAboveThreshold = 0;
        samplesBelowReleaseThreshold = 0;
    }

    /**
     * Feed one corrected 3-axis sample, in microtesla. Uncalibrated Android sensors should
     * have their reported bias (values 3..5) removed by the caller before this method.
     */
    public Reading addSample(float sampleX, float sampleY, float sampleZ, long timestampNs) {
        if (!isFinite(sampleX) || !isFinite(sampleY) || !isFinite(sampleZ)) {
            return currentReading(false, false);
        }

        x = sampleX;
        y = sampleY;
        z = sampleZ;
        rawMagnitude = Math.sqrt(x * x + y * y + z * z);
        recentMagnitudes.addLast(rawMagnitude);
        while (recentMagnitudes.size() > MEDIAN_WINDOW_SIZE) {
            recentMagnitudes.removeFirst();
        }

        double robustMagnitude = median(recentMagnitudes);
        if (!isFinite(filteredMagnitude)) {
            filteredMagnitude = robustMagnitude;
        } else {
            double elapsedSeconds = previousTimestampNs > 0L && timestampNs > previousTimestampNs
                    ? (timestampNs - previousTimestampNs) / 1_000_000_000.0
                    : 0.02;
            // A short EMA after the median filter reduces sensor jitter without making a sweep feel laggy.
            double alpha = 1.0 - Math.exp(-elapsedSeconds / 0.12);
            alpha = clamp(alpha, 0.05, 0.75);
            filteredMagnitude += alpha * (robustMagnitude - filteredMagnitude);
        }
        previousTimestampNs = timestampNs;

        boolean calibrationCompleted = false;
        boolean calibrationFailed = false;
        boolean saturated = isSaturated(rawMagnitude);

        if (calibrating) {
            if (!saturated) {
                calibrationSamples.add(filteredMagnitude);
            }
            long elapsedNs = Math.max(0L, timestampNs - calibrationStartedAtNs);
            if (elapsedNs >= CALIBRATION_MIN_DURATION_NS
                    && calibrationSamples.size() >= MIN_CALIBRATION_SAMPLES) {
                finishCalibration();
                calibrationCompleted = true;
            } else if (elapsedNs >= CALIBRATION_TIMEOUT_NS) {
                calibrating = false;
                calibrationSamples.clear();
                calibrationFailed = true;
            }
        }

        double anomaly = hasBaseline ? Math.abs(filteredMagnitude - baseline) : 0.0;
        double effectiveThreshold = getEffectiveThreshold();
        boolean justTriggered = false;

        if (scanning && hasBaseline && !calibrating) {
            if (anomaly >= effectiveThreshold) {
                samplesAboveThreshold++;
                samplesBelowReleaseThreshold = 0;
                if (!alerting && samplesAboveThreshold >= TRIP_SAMPLES) {
                    alerting = true;
                    justTriggered = true;
                }
            } else {
                samplesAboveThreshold = 0;
                if (anomaly <= effectiveThreshold * 0.72) {
                    samplesBelowReleaseThreshold++;
                    if (alerting && samplesBelowReleaseThreshold >= RELEASE_SAMPLES) {
                        alerting = false;
                        samplesBelowReleaseThreshold = 0;
                    }
                } else {
                    samplesBelowReleaseThreshold = 0;
                }
            }
        } else {
            alerting = false;
            samplesAboveThreshold = 0;
            samplesBelowReleaseThreshold = 0;
        }

        return new Reading(
                x, y, z, rawMagnitude, filteredMagnitude,
                hasBaseline ? baseline : Double.NaN,
                hasBaseline ? filteredMagnitude - baseline : 0.0,
                anomaly, noiseFloor, effectiveThreshold,
                hasBaseline, calibrating, scanning, alerting, justTriggered,
                calibrationCompleted, calibrationFailed, saturated,
                getCalibrationProgress(timestampNs), calibrationSamples.size());
    }

    public Reading currentReading(long timestampNs) {
        return currentReading(timestampNs, false, false);
    }

    private Reading currentReading(boolean calibrationCompleted, boolean calibrationFailed) {
        return currentReading(previousTimestampNs < 0L ? 0L : previousTimestampNs,
                calibrationCompleted, calibrationFailed);
    }

    private Reading currentReading(long timestampNs, boolean calibrationCompleted, boolean calibrationFailed) {
        double field = isFinite(filteredMagnitude) ? filteredMagnitude : 0.0;
        double anomaly = hasBaseline ? Math.abs(field - baseline) : 0.0;
        return new Reading(
                x, y, z, rawMagnitude, field,
                hasBaseline ? baseline : Double.NaN,
                hasBaseline ? field - baseline : 0.0,
                anomaly, noiseFloor, getEffectiveThreshold(),
                hasBaseline, calibrating, scanning, alerting, false,
                calibrationCompleted, calibrationFailed, isSaturated(rawMagnitude),
                getCalibrationProgress(timestampNs), calibrationSamples.size());
    }

    private void finishCalibration() {
        double candidate = median(calibrationSamples);
        List<Double> absoluteDeviations = new ArrayList<>(calibrationSamples.size());
        for (double value : calibrationSamples) {
            absoluteDeviations.add(Math.abs(value - candidate));
        }
        double mad = median(absoluteDeviations);
        baseline = candidate;
        // 1.4826 * MAD estimates Gaussian sigma robustly; sensor resolution is a hard lower bound.
        noiseFloor = Math.max(Math.max(0.15, sensorResolution), 1.4826 * mad);
        hasBaseline = true;
        calibrating = false;
        calibrationSamples.clear();
        scanning = false;
        alerting = false;
        samplesAboveThreshold = 0;
        samplesBelowReleaseThreshold = 0;
    }

    private double getEffectiveThreshold() {
        double resolutionFloor = Math.max(0.8, sensorResolution * 4.0);
        double noiseThreshold = Math.max(resolutionFloor, noiseFloor * 4.0);
        return Math.max(sensitivity, noiseThreshold);
    }

    private int getCalibrationProgress(long timestampNs) {
        if (!calibrating) {
            return 0;
        }
        long elapsed = Math.max(0L, timestampNs - calibrationStartedAtNs);
        return (int) Math.min(99L, (elapsed * 100L) / CALIBRATION_MIN_DURATION_NS);
    }

    private boolean isSaturated(double magnitude) {
        return sensorMaximumRange > 0.0 && magnitude >= sensorMaximumRange * 0.95;
    }

    private static double median(Iterable<Double> values) {
        List<Double> sorted = new ArrayList<>();
        for (Double value : values) {
            sorted.add(value);
        }
        if (sorted.isEmpty()) {
            return 0.0;
        }
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 0) {
            return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
        }
        return sorted.get(middle);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    public static final class Reading {
        public final double x;
        public final double y;
        public final double z;
        public final double rawMagnitude;
        public final double filteredMagnitude;
        public final double baseline;
        /** Signed change from baseline. */
        public final double deviation;
        /** Absolute change from baseline, in microtesla. */
        public final double anomaly;
        public final double noiseFloor;
        public final double threshold;
        public final boolean hasBaseline;
        public final boolean calibrating;
        public final boolean scanning;
        public final boolean alerting;
        public final boolean justTriggered;
        public final boolean calibrationCompleted;
        public final boolean calibrationFailed;
        public final boolean saturated;
        public final int calibrationProgress;
        public final int calibrationSamples;

        private Reading(double x, double y, double z, double rawMagnitude,
                        double filteredMagnitude, double baseline, double deviation,
                        double anomaly, double noiseFloor, double threshold,
                        boolean hasBaseline, boolean calibrating, boolean scanning,
                        boolean alerting, boolean justTriggered, boolean calibrationCompleted,
                        boolean calibrationFailed, boolean saturated, int calibrationProgress,
                        int calibrationSamples) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.rawMagnitude = rawMagnitude;
            this.filteredMagnitude = filteredMagnitude;
            this.baseline = baseline;
            this.deviation = deviation;
            this.anomaly = anomaly;
            this.noiseFloor = noiseFloor;
            this.threshold = threshold;
            this.hasBaseline = hasBaseline;
            this.calibrating = calibrating;
            this.scanning = scanning;
            this.alerting = alerting;
            this.justTriggered = justTriggered;
            this.calibrationCompleted = calibrationCompleted;
            this.calibrationFailed = calibrationFailed;
            this.saturated = saturated;
            this.calibrationProgress = calibrationProgress;
            this.calibrationSamples = calibrationSamples;
        }
    }
}
