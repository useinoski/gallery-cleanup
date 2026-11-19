package com.useinoski.gallerycleanup.detection

import com.useinoski.gallerycleanup.detection.face.FaceScanVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceScanVersionTest {
    @Test
    fun versionRecordsTheBundledArtifactAndTheCountOnlyPipeline() {
        assertEquals("mlkit-face-count", FaceScanVersion.ID)
        assertEquals("face_count", FaceScanVersion.FACE_COUNT)
        assertEquals(
            "mlkit-face-detection-16.1.7-bundled-fast-count-long1280-min0.15",
            FaceScanVersion.of(
                FaceScanVersion.DEFAULT_MAX_LONG_SIDE_PX,
                FaceScanVersion.DEFAULT_MIN_FACE_SIZE,
            ),
        )
    }
}
