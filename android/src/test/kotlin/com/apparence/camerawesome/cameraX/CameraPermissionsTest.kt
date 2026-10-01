package com.apparence.camerawesome.cameraX

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Flutter rejects an EventSink event sent off the main thread, and Android may
 * call onRequestPermissionsResult synchronously on whichever thread asked for
 * the permissions: while another request is in flight, Activity.requestPermissions
 * immediately reports a cancellation with empty arrays. So permissions have to
 * be requested from the main thread, and the result handed back there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraPermissionsTest {
    private lateinit var mainExecutor: ExecutorService
    private lateinit var mainThread: Thread
    private val cameraPermissions = CameraPermissions()

    @Before
    fun setUp() {
        mainExecutor = Executors.newSingleThreadExecutor()
        mainThread = mainExecutor.submit(Callable { Thread.currentThread() }).get()
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
    }

    @After
    fun tearDown() {
        ActivityCompat.setPermissionCompatDelegate(null)
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    @Test
    fun cancelledRequestReportsItsResultOnTheMainThread() {
        val eventThread = CompletableFuture<Thread>()
        val events = mock<EventChannel.EventSink> {
            on { success(anyOrNull()) } doAnswer {
                eventThread.complete(Thread.currentThread())
                null
            }
        }
        cameraPermissions.onListen(null, events)
        // What Activity.requestPermissions does while another request is pending.
        onPermissionRequest { requestCode ->
            cameraPermissions.onRequestPermissionsResult(requestCode, emptyArray(), IntArray(0))
        }

        cameraPermissions.requestBasePermissions(
            activityMissing(Manifest.permission.CAMERA),
            saveGps = false,
            recordAudio = false,
        ) {}

        assertSame(mainThread, eventThread.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun grantedRequestCallsBackOnTheMainThread() {
        val requested = CountDownLatch(1)
        onPermissionRequest { requested.countDown() }
        val callback = CompletableFuture<Pair<Thread, List<String>>>()

        cameraPermissions.requestBasePermissions(
            activityMissing(Manifest.permission.CAMERA),
            saveGps = false,
            recordAudio = false,
        ) { granted ->
            callback.complete(Thread.currentThread() to granted)
        }
        requested.await(5, TimeUnit.SECONDS)
        // Android delivers the user's answer on the main thread.
        mainExecutor.submit {
            cameraPermissions.onRequestPermissionsResult(
                CameraPermissions.PERMISSIONS_MULTIPLE_REQUEST,
                arrayOf(Manifest.permission.CAMERA),
                intArrayOf(PackageManager.PERMISSION_GRANTED),
            )
        }

        val (thread, granted) = callback.get(5, TimeUnit.SECONDS)
        assertSame(mainThread, thread)
        assertEquals(listOf(Manifest.permission.CAMERA), granted)
    }

    private fun onPermissionRequest(onRequest: (requestCode: Int) -> Unit) {
        ActivityCompat.setPermissionCompatDelegate(object :
            ActivityCompat.PermissionCompatDelegate {
            override fun requestPermissions(
                activity: Activity, permissions: Array<String>, requestCode: Int
            ): Boolean {
                onRequest(requestCode)
                return true
            }

            override fun onActivityResult(
                activity: Activity, requestCode: Int, resultCode: Int, data: Intent?
            ): Boolean = false
        })
    }

    /** An activity that declares [permission] in its manifest but has not been granted it. */
    private fun activityMissing(permission: String): Activity {
        val packageInfo = PackageInfo().apply { requestedPermissions = arrayOf(permission) }
        val packageManager = mock<PackageManager> {
            on { getPackageInfo(any<String>(), any<Int>()) } doReturn packageInfo
        }
        return mock {
            on { this.packageManager } doReturn packageManager
            on { packageName } doReturn "com.example"
            on { checkPermission(any(), any(), any()) } doReturn PackageManager.PERMISSION_DENIED
        }
    }
}
