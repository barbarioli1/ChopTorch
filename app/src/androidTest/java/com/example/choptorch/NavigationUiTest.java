package com.example.choptorch;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class NavigationUiTest {
    @Test
    public void navigationAndDeveloperActionsMatchBuildVariant() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.screen_home));
                assertNotNull(activity.findViewById(R.id.btn_home_monitoring));
                assertNotNull(activity.findViewById(R.id.btn_manual_flashlight));
                assertNotNull(activity.findViewById(R.id.btn_open_settings));
                assertNull(activity.findViewById(R.id.btn_open_training));
                assertNull(activity.findViewById(R.id.btn_false_positive));
                activity.findViewById(R.id.btn_open_settings).performClick();
                assertNotNull(activity.findViewById(R.id.screen_settings));
                assertNotNull(activity.findViewById(R.id.row_detection_model));
                assertNotNull(activity.findViewById(R.id.row_about));
                assertNull(activity.findViewById(R.id.btn_open_training));
                activity.findViewById(R.id.row_about).performClick();
                assertNotNull(activity.findViewById(R.id.screen_about));
                activity.onBackPressed();
                assertNotNull(activity.findViewById(R.id.screen_settings));
                activity.findViewById(R.id.row_detection_model).performClick();
                assertNotNull(activity.findViewById(R.id.screen_detection_model));
                assertNotNull(activity.findViewById(R.id.model_status));

                View openTraining = activity.findViewById(R.id.btn_open_training);
                View falsePositive = activity.findViewById(R.id.btn_false_positive);
                if (BuildConfig.ENABLE_TRAINING) {
                    assertNotNull(openTraining);
                    assertNotNull(falsePositive);
                    assertTrue(openTraining.getVisibility() == View.VISIBLE);
                    openTraining.performClick();
                    assertNotNull(activity.findViewById(R.id.screen_training));
                    activity.findViewById(R.id.btn_training_back).performClick();
                    assertNotNull(activity.findViewById(R.id.screen_detection_model));
                } else {
                    assertNull(openTraining);
                    assertNull(falsePositive);
                }

                activity.onBackPressed();
                assertNotNull(activity.findViewById(R.id.screen_settings));
                activity.onBackPressed();
                assertNotNull(activity.findViewById(R.id.screen_home));
            });
        }
    }
}