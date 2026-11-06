package com.example.choptorch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ShippingBuildFlagTest {
    @Test
    public void trainingFlagMatchesRequestedVariant() {
        String expected = InstrumentationRegistry.getArguments().getString("expectTraining");
        assertNotNull("Pass -e expectTraining true or false", expected);
        assertTrue("expectTraining must be true or false", expected.equals("true") || expected.equals("false"));
        assertEquals(Boolean.parseBoolean(expected), BuildConfig.ENABLE_TRAINING);
    }
}
