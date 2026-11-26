package com.useinoski.gallerycleanup.manifest

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The call [com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoScheduler]
 * makes with no try/catch: [JobScheduler.schedule] for
 * `JobInfoSchedulerService`. A missing service throws IllegalArgumentException
 * ("No such service ComponentInfo").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JobInfoSchedulerServiceTest {
    @Test
    fun schedulingTheRestoredServiceDoesNotThrow() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(
            context,
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
        )
        val info = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        assertFalse(info.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(1, component)
            .setMinimumLatency(1)
            .build()
        assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(job))
    }
}
