package com.melmeligy.mediadownloader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke instrumentation test. Runs on a device/emulator (e.g. via
 * `./gradlew connectedDebugAndroidTest`); not part of the headless CI unit-test run.
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun usesCorrectPackageName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.startsWith("com.melmeligy.mediadownloader"))
    }
}
