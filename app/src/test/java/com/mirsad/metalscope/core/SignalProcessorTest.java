package com.mirsad.metalscope.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SignalProcessorTest {
    private static final long SAMPLE_PERIOD_NS = 100_000_000L;

    @Test
    public void calibrationMeasuresLocalFieldInsteadOfAssumingEarthFieldThreshold() {
        SignalProcessor processor = new SignalProcessor();
        processor.setSensorCharacteristics(0.2, 2000.0);
        SignalProcessor.Reading reading = calibrate(processor, 50.0);

        assertTrue(reading.hasBaseline);
        assertEquals(50.0, reading.baseline, 0.2);
        assertEquals(50.0, reading.filteredMagnitude, 0.2);
        assertTrue(reading.threshold >= processor.getSensitivity());
    }

    @Test
    public void magnitudeIsInsensitiveToPhoneRotationAtConstantFieldStrength() {
        SignalProcessor processor = new SignalProcessor();
        calibrate(processor, 50.0);
        processor.setScanning(true);

        SignalProcessor.Reading reading = null;
        for (int index = 41; index < 75; index++) {
            // The same 50 µT field moves from the phone's X axis to its Y axis.
            reading = processor.addSample(0f, 50f, 0f, index * SAMPLE_PERIOD_NS);
        }

        assertTrue(reading != null);
        assertEquals(0.0, reading.anomaly, 0.2);
        assertFalse(reading.alerting);
    }

    @Test
    public void alertHasDebounceAndHysteresis() {
        SignalProcessor processor = new SignalProcessor();
        calibrate(processor, 50.0);
        processor.setScanning(true);

        SignalProcessor.Reading reading = null;
        for (int index = 41; index < 80; index++) {
            reading = processor.addSample(60f, 0f, 0f, index * SAMPLE_PERIOD_NS);
        }
        assertTrue(reading != null && reading.alerting);

        for (int index = 80; index < 110; index++) {
            reading = processor.addSample(50f, 0f, 0f, index * SAMPLE_PERIOD_NS);
        }
        assertTrue(reading != null);
        assertFalse(reading.alerting);
    }

    @Test
    public void noisyCalibrationRaisesEffectiveThreshold() {
        SignalProcessor processor = new SignalProcessor();
        processor.setSensorCharacteristics(0.2, 2000.0);
        processor.beginCalibration(0L);
        SignalProcessor.Reading reading = null;
        for (int index = 0; index <= 50; index++) {
            float magnitude = (index & 1) == 0 ? 40f : 60f;
            reading = processor.addSample(magnitude, 0f, 0f, index * SAMPLE_PERIOD_NS);
        }

        assertTrue(reading != null && reading.hasBaseline);
        assertTrue("threshold should reflect calibration noise", reading.threshold > 6.0);
    }

    @Test
    public void reportsSensorRangeSaturation() {
        SignalProcessor processor = new SignalProcessor();
        processor.setSensorCharacteristics(0.2, 100.0);
        SignalProcessor.Reading reading = processor.addSample(98f, 0f, 0f, SAMPLE_PERIOD_NS);

        assertTrue(reading.saturated);
    }

    private static SignalProcessor.Reading calibrate(SignalProcessor processor, double magnitude) {
        processor.beginCalibration(0L);
        SignalProcessor.Reading reading = null;
        for (int index = 0; index <= 40; index++) {
            reading = processor.addSample((float) magnitude, 0f, 0f,
                    index * SAMPLE_PERIOD_NS);
        }
        return reading;
    }
}
