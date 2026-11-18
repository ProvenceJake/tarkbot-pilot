package com.xtark.pilot.camera

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonBodyDetectorInstrumentedTest {
    @Test
    fun detectedPersonCanExtractAppearanceBeforeImageIsReleased() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val bitmap = try {
            instrumentation.context.assets.open("person_test.jpg").use { stream ->
                requireNotNull(BitmapFactory.decodeStream(stream)) {
                    "person_test.jpg could not be decoded"
                }
            }
        } catch (error: FileNotFoundException) {
            assumeNoException("Add a local person_test.jpg as described in the README", error)
            return
        }
        PersonBodyDetector(targetContext).use { detector ->
            val result = detector.detectBitmap(
                source = bitmap,
                rotationDegrees = 0,
                selectedPersonId = null,
                nowMs = 1_000L
            )

            assertFalse("expected the test portrait to contain a person", result.candidates.isEmpty())
            val candidate = result.candidates.first()
            assertTrue(candidate.bodyWidthCoverage > 0.05f)
            assertTrue(candidate.bodyHeightCoverage > 0.14f)

            val selector = PersonAutoSelector(requiredObservations = 2)
            assertTrue(selector.observe(result.candidates) == null)
            assertTrue(selector.observe(result.candidates)?.trackingId == candidate.trackingId)
        }
    }
}
