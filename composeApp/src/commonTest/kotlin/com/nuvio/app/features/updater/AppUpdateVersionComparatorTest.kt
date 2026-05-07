package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateVersionComparatorTest {
    @Test
    fun parsesVersionAndBuildFromReleaseNotes() {
        val version = AppUpdateVersionComparator.parseReleaseVersion(
            tag = "pre",
            title = "Pre-Release - Betas are stored in this release",
            notes = "# Nuvio Desktop 0.1.14 build 55 - Pre-release",
        )

        assertEquals("0.1.14", version.versionName)
        assertEquals(55, version.versionCode)
    }

    @Test
    fun sameVersionAndEqualRemoteBuildCountsAsAvailable() {
        assertTrue(
            AppUpdateVersionComparator.isUpdateAvailable(
                remoteVersionName = "0.1.14",
                remoteVersionCode = 55,
                remoteTag = "pre",
                localVersionName = "0.1.14",
                localVersionCode = 55,
            ),
        )
    }

    @Test
    fun sameVersionAndOlderRemoteBuildDoesNotCountAsAvailable() {
        assertFalse(
            AppUpdateVersionComparator.isUpdateAvailable(
                remoteVersionName = "0.1.14",
                remoteVersionCode = 54,
                remoteTag = "pre",
                localVersionName = "0.1.14",
                localVersionCode = 55,
            ),
        )
    }
}
