package dev.sawitulm.palmannotate.data.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Base64
import android.util.Log
import com.orbbec.obsensor.ColorFrame
import com.orbbec.obsensor.Config
import com.orbbec.obsensor.DepthFrame
import com.orbbec.obsensor.Device
import com.orbbec.obsensor.DeviceChangedCallback
import com.orbbec.obsensor.DeviceList
import com.orbbec.obsensor.FrameSet
import com.orbbec.obsensor.OBContext
import com.orbbec.obsensor.Pipeline
import com.orbbec.obsensor.StreamProfileList
import com.orbbec.obsensor.VideoStreamProfile
import com.orbbec.obsensor.types.AlignMode
import com.orbbec.obsensor.types.Format
import com.orbbec.obsensor.types.FrameAggregateOutputMode
import com.orbbec.obsensor.types.LogSeverity
import com.orbbec.obsensor.types.SensorType
import com.orbbec.obsensor.types.StreamType
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Native Orbbec USB RGB-D manager — a Capacitor-free port of
 * `../android/.../OrbbecPlugin.kt` (Android wrapper v2.0.6). The SDK / USB / frame
 * pump / flapping-guard / depth-encoding logic is preserved verbatim; only the
 * Capacitor `Plugin` shell is replaced with Kotlin callbacks + suspend functions.
 *
 * Callbacks (set by the UI/capture source):
 *   onDeviceChange(attached, count) · onState(state, message) ·
 *   onFrame(rgbBase64?, depthBase64?, width, height)   // throttled live preview
 *
 * NOTE: device-only verification required (Pad 6 / Pad 8) — see MIGRATION_STATUS.md.
 */
class OrbbecManager(private val appContext: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "dev.sawitulm.palmannotate.USB_PERMISSION"
        const val ORBBEC_VENDOR_ID = 0x2BC5
        private const val TAG = "PalmAnnotateOrbbec"
        private const val JPEG_QUALITY = 88
        private const val FRAME_TIMEOUT_MS = 1_500L
        private const val DEVICE_QUERY_RETRIES = 8
        private const val DEVICE_QUERY_DELAY_MS = 250L
        private const val OPEN_RETRIES = 3
        private const val OPEN_RETRY_SETTLE_MS = 400L
        private const val FLAP_WINDOW_MS = 20_000L
        private const val FLAP_STEP_AFTER = 2
        private const val FLAP_RESET_QUIET_MS = 30_000L
        private const val STABLE_STREAM_MS = 4_000L
        private const val PREVIEW_INTERVAL_MS = 80L
        private const val DEPTH_PREVIEW_INTERVAL_MS = 160L
        private const val COLOR_PREVIEW_MAX_DIM = 720
        private const val COLOR_PREVIEW_JPEG_QUALITY = 60
        private const val DEPTH_PREVIEW_MAX_DIM = 288
        private const val DEPTH_PREVIEW_JPEG_QUALITY = 70
        private const val DEPTH_MIN_MM = 250f
        private const val DEPTH_MAX_MM = 6_000f
        private const val DEPTH_RANGE_EMA = 0.40f
        private const val DEPTH_RANGE_PAD = 0.05f
        private const val DEPTH_RANGE_MIN_SPAN_MM = 120f
        private const val DEPTH_RANGE_LOW_PERCENTILE = 0.02f
        private const val DEPTH_RANGE_HIGH_PERCENTILE = 0.98f
        private const val DEPTH_RANGE_FLOOR_MM = 250f
        private const val DEPTH_RANGE_CEILING_MM = 7_000f
        private const val CAPTURE_VIA_PUMP_TIMEOUT_MS = 6_000L
    }

    // ── Public callbacks ───────────────────────────────────────────────────────
    var onDeviceChange: ((attached: Boolean, count: Int) -> Unit)? = null
    var onState: ((state: String, message: String) -> Unit)? = null
    var onFrame: ((rgbBase64: String?, depthBase64: String?, width: Int, height: Int) -> Unit)? = null

    // ── Public result types ──────────────────────────────────────────────────
    data class OrbbecDeviceInfo(val name: String, val vendorId: Int, val productId: Int, val deviceName: String, val hasPermission: Boolean)
    data class OrbbecDepthData(
        val base64: String, val width: Int, val height: Int, val format: String, val valueScale: Float,
        val encoding: String = "uint16le", val unit: String = "mm", val alignedTo: String = "color",
        val displayFloorMm: Float = DEPTH_RANGE_FLOOR_MM, val displayCeilingMm: Float = DEPTH_RANGE_CEILING_MM,
    )
    data class OrbbecCapture(val base64: String, val width: Int, val height: Int, val format: String, val sourceFormat: String, val depth: OrbbecDepthData?)

    private class CaptureWaiter {
        @Volatile var result: CapturedRgbd? = null
        @Volatile var error: Exception? = null
        val latch = CountDownLatch(1)
        fun resolve(r: CapturedRgbd) { result = r; latch.countDown() }
        fun reject(e: Exception) { error = e; latch.countDown() }
        fun await(timeoutMs: Long): CapturedRgbd {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IllegalStateException("Timed out waiting for Orbbec frame")
            error?.let { throw it }
            return result ?: throw IllegalStateException("No Orbbec frame produced")
        }
    }

    private data class CapturedJpeg(val bytes: ByteArray, val width: Int, val height: Int, val sourceFormat: String)
    private data class CapturedDepth(val bytes: ByteArray, val width: Int, val height: Int, val sourceFormat: String, val valueScale: Float)
    private data class CapturedRgbd(val color: CapturedJpeg, val depth: CapturedDepth?)

    private val cameraExec = Executors.newSingleThreadExecutor { r -> Thread(r, "PalmAnnotate-Orbbec").apply { isDaemon = true } }
    private val cameraDispatcher = cameraExec.asCoroutineDispatcher()

    private val stateLock = Any()
    private var obContext: OBContext? = null
    private var device: Device? = null
    private var pipeline: Pipeline? = null
    private var selectedUid: String? = null
    private var streaming = false
    private var depthStreaming = false

    private val flapLock = Any()
    private val recentDetaches = ArrayDeque<Long>()
    private var lastDetachMs = 0L
    @Volatile private var degradeLevel = 0
    private val degradeToColorOnly get() = degradeLevel >= 1
    private val unstableSuppressed get() = degradeLevel >= 2

    @Volatile private var pumpRunning = false
    private var streamPump: Thread? = null
    private val pendingCapture = AtomicReference<CaptureWaiter?>(null)
    private var depthRangeInit = false
    private var depthRangeMinMm = 0f
    private var depthRangeMaxMm = 0f

    private val deviceChangedCallback = object : DeviceChangedCallback {
        override fun onDeviceAttach(deviceList: DeviceList) {
            var count = 0
            try { count = deviceList.getDeviceCount() } catch (e: Exception) { Log.w(TAG, "onDeviceAttach failed", e) } finally { safeClose(deviceList, "attach deviceList") }
            onDeviceChange?.invoke(true, count)
        }
        override fun onDeviceDetach(deviceList: DeviceList) {
            var detachedSelected = false
            try {
                val uid = synchronized(stateLock) { selectedUid }
                if (uid != null) for (i in 0 until deviceList.getDeviceCount()) if (uid == deviceList.getUid(i)) { detachedSelected = true; break }
            } catch (e: Exception) { Log.w(TAG, "onDeviceDetach failed", e); detachedSelected = true } finally { safeClose(deviceList, "detach deviceList") }
            if (detachedSelected) {
                stopPump()
                cameraExec.execute { joinPump(); synchronized(stateLock) { closeSdkLocked() } }
            }
            onDeviceChange?.invoke(false, 0)
        }
    }

    // ── Flapping guard ──────────────────────────────────────────────────────────
    private fun noteDetachAndAssess() {
        val now = System.currentTimeMillis()
        var reached = 0
        synchronized(flapLock) {
            lastDetachMs = now
            recentDetaches.addLast(now)
            while (recentDetaches.isNotEmpty() && now - recentDetaches.first() > FLAP_WINDOW_MS) recentDetaches.removeFirst()
            if (recentDetaches.size >= FLAP_STEP_AFTER && degradeLevel < 2) { degradeLevel++; reached = degradeLevel; recentDetaches.clear() }
        }
        when (reached) {
            1 -> onState?.invoke("needsPower", "Depth needs more USB power than the tablet alone provides. Plug in the USB hub's power adapter, then tap \"Find camera\". Capturing RGB only for now.")
            2 -> onState?.invoke("unstable", "USB camera keeps resetting (check the hub's power adapter / cable). Replug it or use the built-in camera.")
        }
    }

    private fun maybeResetFlapLadder() {
        val now = System.currentTimeMillis()
        var didReset = false
        synchronized(flapLock) {
            if (lastDetachMs != 0L && now - lastDetachMs > FLAP_RESET_QUIET_MS && (degradeLevel > 0 || recentDetaches.isNotEmpty())) {
                recentDetaches.clear(); degradeLevel = 0; didReset = true
            }
        }
            // Log.i(TAG, "Orbbec flapping guard reset (full color+depth re-enabled)")
    }

    private fun resetFlapLadder() { synchronized(flapLock) { recentDetaches.clear(); degradeLevel = 0 } }

    // ── USB hotplug receiver ──────────────────────────────────────────────────
    private var usbHotplugReceiver: BroadcastReceiver? = null

    /** Call once after constructing (e.g. from the capture source). */
    fun start() { registerUsbHotplugReceiver() }

    private fun registerUsbHotplugReceiver() {
        if (usbHotplugReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (dev == null || dev.vendorId != ORBBEC_VENDOR_ID) return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> { maybeResetFlapLadder(); warmUpSdk(); onDeviceChange?.invoke(true, orbbecDevices().size) }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        noteDetachAndAssess()
                        val remaining = orbbecDevices().size
                        if (remaining == 0) { stopPump(); cameraExec.execute { joinPump(); synchronized(stateLock) { closeSdkLocked() } } }
                        onDeviceChange?.invoke(false, remaining)
                    }
                }
            }
        }
        val filter = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") appContext.registerReceiver(receiver, filter)
        usbHotplugReceiver = receiver
    }

    private fun unregisterUsbHotplugReceiver() {
        val r = usbHotplugReceiver ?: return
        usbHotplugReceiver = null
        try { appContext.unregisterReceiver(r) } catch (e: Exception) { /* Log.d(TAG, "hotplug unregister ignored", e) */ }
    }

    private fun warmUpSdk() {
        cameraExec.execute {
            try {
                if (unstableSuppressed) return@execute
                val mgr = usbManager() ?: return@execute
                val devices = orbbecDevices()
                if (devices.isEmpty() || devices.none { mgr.hasPermission(it) }) return@execute
                synchronized(stateLock) {
                    if (obContext == null) {
                        OBContext.setLoggerSeverity(LogSeverity.INFO); OBContext.setLoggerToConsole(LogSeverity.INFO)
                        obContext = OBContext(appContext, deviceChangedCallback)
                        // Log.i(TAG, "Orbbec SDK pre-warmed (core ${OBContext.getCoreVersionName()})")
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "SDK pre-warm failed", e) }
        }
    }

    // ── USB helpers ────────────────────────────────────────────────────────────
    private fun usbManager(): UsbManager? = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
    private fun orbbecDevices(): List<UsbDevice> = try {
        usbManager()?.deviceList?.values?.filter { it.vendorId == ORBBEC_VENDOR_ID } ?: emptyList()
    } catch (e: Exception) { Log.w(TAG, "USB enumeration failed", e); emptyList() }

    private fun requireUsbPermission() {
        val mgr = usbManager() ?: throw IllegalStateException("USB service unavailable on this device")
        val devices = orbbecDevices()
        if (devices.isEmpty()) throw IllegalStateException("No Orbbec device found")
        if (devices.none { mgr.hasPermission(it) }) throw SecurityException("USB permission not granted for the Orbbec camera")
    }

    // ── Public API ───────────────────────────────────────────────────────────
    fun isAvailable(): Boolean = orbbecDevices().isNotEmpty() && !unstableSuppressed

    fun listDevices(): List<OrbbecDeviceInfo> {
        val mgr = usbManager()
        return orbbecDevices().map { d ->
            OrbbecDeviceInfo(d.productName ?: d.deviceName, d.vendorId, d.productId, d.deviceName, mgr?.hasPermission(d) ?: false)
        }
    }

    /** Request Android USB permission for the first attached Orbbec device. */
    suspend fun requestPermission(): Boolean {
        val mgr = usbManager() ?: return false
        val dev = orbbecDevices().firstOrNull() ?: return false
        if (mgr.hasPermission(dev)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    synchronized(this) {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        try { ctx.unregisterReceiver(this) } catch (_: IllegalArgumentException) {}
                        if (cont.isActive) cont.resume(granted)
                    }
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags)
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") appContext.registerReceiver(receiver, filter)
            mgr.requestPermission(dev, pi)
        }
    }

    suspend fun open() = withContext(cameraDispatcher) { synchronized(stateLock) { openSdkLocked() } }

    suspend fun capture(): OrbbecCapture = withContext(cameraDispatcher) {
        synchronized(stateLock) { openSdkLocked() }
        val frame = if (pumpRunning) captureViaPump() else { joinPump(); captureRgbd() }
        OrbbecCapture(
            base64 = Base64.encodeToString(frame.color.bytes, Base64.NO_WRAP),
            width = frame.color.width, height = frame.color.height,
            format = "jpeg", sourceFormat = frame.color.sourceFormat,
            depth = frame.depth?.let {
                OrbbecDepthData(Base64.encodeToString(it.bytes, Base64.NO_WRAP), it.width, it.height, it.sourceFormat, it.valueScale)
            },
        )
    }

    suspend fun startPreview() = withContext(cameraDispatcher) {
        joinPump(); synchronized(stateLock) { openSdkLocked() }; startPump()
    }

    suspend fun stopPreview() { stopPump(); withContext(cameraDispatcher) { joinPump() } }

    suspend fun close() {
        resetFlapLadder(); stopPump()
        withContext(cameraDispatcher) { joinPump(); synchronized(stateLock) { closeSdkLocked() } }
    }

    /** "Find camera": re-enumerate; drop a stale context when the bus is empty. */
    suspend fun refresh(): Boolean {
        resetFlapLadder()
        return withContext(cameraDispatcher) {
            val devices = orbbecDevices()
            if (devices.isEmpty()) {
                synchronized(stateLock) { pumpRunning = false }
                pendingCapture.getAndSet(null)?.reject(IllegalStateException("Orbbec preview stopped"))
                joinPump(); synchronized(stateLock) { closeSdkLocked() }
            } else warmUpSdk()
            devices.isNotEmpty()
        }
    }

    /**
     * Hard reset for the RGB-only / "unstable" lock — the power-starved-Orbbec state on the
     * Pad 8 where the depth sensor drops and the flapping guard reaches `degradeLevel >= 2`
     * (`unstableSuppressed`), after which `warmUpSdk`/`open` refuse and only RGB is offered.
     *
     * The lock lives ENTIRELY in this process (the in-memory degrade ladder — there is no
     * persisted flag), so clearing it here recovers the camera WITHOUT "Clear App Data":
     *  1. [resetFlapLadder] drops `degradeLevel` to 0 so the suppression guards lift,
     *  2. a full [closeSdkLocked] tears down the (possibly wedged) pipeline/device/context,
     *  3. [warmUpSdk] re-initialises against a present device so the next open can re-acquire
     *     depth.
     *
     * Returns true if at least one Orbbec device is present after the reset. NOTE: this clears
     * the *software* lock; whether the USB device itself recovers without a physical replug is
     * hardware-dependent and must be confirmed on the Pad 8.
     */
    suspend fun resetCameraState(): Boolean {
        resetFlapLadder(); stopPump()
        return withContext(cameraDispatcher) {
            pendingCapture.getAndSet(null)?.reject(IllegalStateException("Orbbec camera reset"))
            joinPump()
            synchronized(stateLock) { closeSdkLocked() }
            val present = orbbecDevices().isNotEmpty()
            if (present) warmUpSdk()
            present
        }
    }

    fun destroy() {
        unregisterUsbHotplugReceiver(); stopPump()
        try { cameraExec.execute { joinPump(); synchronized(stateLock) { closeSdkLocked() } } } catch (e: Exception) { Log.w(TAG, "cleanup dispatch failed", e) }
        cameraExec.shutdown()
    }

    // ── Live preview pump ──────────────────────────────────────────────────────
    private fun startPump() {
        synchronized(stateLock) {
            if (pumpRunning) return
            pumpRunning = true; depthRangeInit = false
            val t = Thread({ runPump() }, "PalmAnnotate-OrbbecPump").apply { isDaemon = true }
            streamPump = t; t.start()
        }
    }

    private fun stopPump() {
        synchronized(stateLock) { pumpRunning = false }
        pendingCapture.getAndSet(null)?.reject(IllegalStateException("Orbbec preview stopped"))
        synchronized(stateLock) { streamPump }?.let { try { it.interrupt() } catch (_: Exception) {} }
    }

    private fun joinPump() {
        synchronized(stateLock) { pumpRunning = false }
        val t = synchronized(stateLock) { streamPump }
        if (t != null && t.isAlive) {
            try { t.interrupt() } catch (_: Exception) {}
            try { t.join(FRAME_TIMEOUT_MS + 1_000L) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        synchronized(stateLock) { if (streamPump === t) streamPump = null }
    }

    private fun runPump() {
        var lastPreview = 0L; var lastDepthPreview = 0L
        val pumpStart = System.currentTimeMillis(); var markedStable = false
        while (pumpRunning) {
            val active = synchronized(stateLock) { pipeline } ?: break
            var frameSet: FrameSet? = null; var colorFrame: ColorFrame? = null; var depthFrame: DepthFrame? = null
            try {
                frameSet = active.waitForFrameSet(FRAME_TIMEOUT_MS) ?: continue
                if (!markedStable && System.currentTimeMillis() - pumpStart >= STABLE_STREAM_MS) { markedStable = true; synchronized(flapLock) { recentDetaches.clear() } }
                colorFrame = frameSet.getColorFrame(); depthFrame = frameSet.getDepthFrame()
                val waiter = pendingCapture.getAndSet(null)
                if (waiter != null) {
                    try {
                        val color = colorFrame ?: throw IllegalStateException("No color frame")
                        val depth = depthFrame?.let { runCatching { encodeDepthFrame(it) }.getOrNull() }
                        waiter.resolve(CapturedRgbd(encodeColorFrame(color), depth))
                    } catch (e: Exception) { waiter.reject(e) }
                }
                val now = System.currentTimeMillis()
                if (now - lastPreview >= PREVIEW_INTERVAL_MS) {
                    val includeDepth = now - lastDepthPreview >= DEPTH_PREVIEW_INTERVAL_MS
                    lastPreview = now; if (includeDepth) lastDepthPreview = now
                    emitPreview(colorFrame, if (includeDepth) depthFrame else null)
                }
            } catch (e: InterruptedException) { break } catch (e: Exception) {
                Log.w(TAG, "pump iteration failed", e); pendingCapture.getAndSet(null)?.reject(e)
            } finally {
                safeClose(depthFrame, "pump depth frame"); safeClose(colorFrame, "pump color frame"); safeClose(frameSet, "pump frameSet")
            }
        }
    }

    private fun captureViaPump(): CapturedRgbd {
        if (!pumpRunning) return captureRgbd()
        val waiter = CaptureWaiter(); pendingCapture.set(waiter); return waiter.await(CAPTURE_VIA_PUMP_TIMEOUT_MS)
    }

    private fun emitPreview(colorFrame: ColorFrame?, depthFrame: DepthFrame?) {
        try {
            var rgb: String? = null; var depth: String? = null; var w = 0; var h = 0
            if (colorFrame != null) { rgb = runCatching { encodeColorPreviewBase64(colorFrame) }.getOrNull(); w = colorFrame.getWidth(); h = colorFrame.getHeight() }
            if (depthFrame != null) depth = runCatching { encodeDepthPreviewBase64(depthFrame) }.getOrNull()
            if (rgb != null || depth != null) onFrame?.invoke(rgb, depth, w, h)
        } catch (e: Exception) { Log.w(TAG, "emitPreview failed", e) }
    }

    // ── SDK lifecycle ────────────────────────────────────────────────────────
    private fun openSdkLocked(): Unit {
        if (streaming && pipeline != null) return
        if (unstableSuppressed) throw IllegalStateException("Orbbec camera keeps resetting (possible power/cable/USB-host issue). Replug it or use the built-in camera.")
        requireUsbPermission()
        var lastError: Exception? = null
        for (attempt in 0 until OPEN_RETRIES) {
            if (obContext == null) {
                OBContext.setLoggerSeverity(LogSeverity.INFO); OBContext.setLoggerToConsole(LogSeverity.INFO)
                obContext = OBContext(appContext, deviceChangedCallback)
                // Log.i(TAG, "Orbbec SDK core ${OBContext.getCoreVersionName()} initialised")
            }
            try { acquireStreamLocked(obContext!!); return } catch (e: Exception) {
                lastError = e; Log.w(TAG, "Orbbec open attempt ${attempt + 1}/$OPEN_RETRIES failed; retrying", e)
                closeSdkLocked()
                if (attempt < OPEN_RETRIES - 1) try { Thread.sleep(OPEN_RETRY_SETTLE_MS) } catch (ie: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        throw lastError ?: IllegalStateException("Failed to open Orbbec camera")
    }

    private fun acquireStreamLocked(ctx: OBContext) {
        val deviceList = queryDevicesWithRetry(ctx)
        var openedDevice: Device? = null; var openedPipeline: Pipeline? = null; var config: Config? = null
        var selectedProfile: VideoStreamProfile? = null; var selectedDepthProfile: VideoStreamProfile? = null
        var depthEnabled = false
        try {
            val count = deviceList.getDeviceCount()
            if (count <= 0) throw IllegalStateException("No Orbbec device visible to SDK")
            var index = 0
            for (i in 0 until count) if (deviceList.getVid(i) == ORBBEC_VENDOR_ID) { index = i; break }
            val uid = deviceList.getUid(index) ?: ""
            openedDevice = deviceList.getDevice(index) ?: throw IllegalStateException("Failed to open Orbbec device")
            val colorSensor = openedDevice.getSensor(SensorType.COLOR) ?: throw IllegalStateException("Orbbec device has no color sensor")
            val depthSensor = openedDevice.getSensor(SensorType.DEPTH)
            openedPipeline = Pipeline(openedDevice)
            config = Config()
            selectedProfile = chooseColorProfile(openedPipeline)
            if (selectedProfile != null) config.enableStream(selectedProfile) else config.enableStream(SensorType.COLOR)
            if (depthSensor != null && degradeToColorOnly) {
                // Log.i(TAG, "Orbbec color-only mode (depth disabled to keep the USB host stable)")
                safeClose(depthSensor, "depth sensor (color-only)")
            } else if (depthSensor != null) {
                try {
                    selectedDepthProfile = chooseDepthProfile(openedPipeline)
                    if (selectedDepthProfile != null) config.enableStream(selectedDepthProfile) else config.enableStream(SensorType.DEPTH)
                    try { config.setAlignMode(AlignMode.ALIGN_D2C_SW_MODE) } catch (e: Exception) { Log.w(TAG, "software D2C align unavailable", e) }
                    try { config.setDepthScaleRequire(true) } catch (_: Exception) {}
                    try { config.setFrameAggregateOutputMode(FrameAggregateOutputMode.OB_FRAME_AGGREGATE_OUTPUT_ALL_TYPE_FRAME_REQUIRE) } catch (_: Exception) {}
                    depthEnabled = true
                } catch (e: Exception) {
                    Log.w(TAG, "Depth setup failed; RGB-only", e); safeClose(selectedDepthProfile, "depth profile"); selectedDepthProfile = null; depthEnabled = false
                } finally { safeClose(depthSensor, "depth sensor") }
            }
            openedPipeline.start(config)
            streaming = true; depthStreaming = depthEnabled
            device = openedDevice; pipeline = openedPipeline; selectedUid = uid
            openedDevice = null; openedPipeline = null
        } finally {
            safeClose(selectedProfile, "color profile"); safeClose(selectedDepthProfile, "depth profile")
            safeClose(config, "config"); safeClose(deviceList, "deviceList")
            safeStopAndClose(openedPipeline); safeClose(openedDevice, "device")
        }
    }

    private fun queryDevicesWithRetry(ctx: OBContext): DeviceList {
        var lastList: DeviceList? = null
        for (attempt in 0 until DEVICE_QUERY_RETRIES) {
            val list = ctx.queryDevices()
            if (list.getDeviceCount() > 0) { safeClose(lastList, "empty deviceList"); return list }
            safeClose(lastList, "empty deviceList"); lastList = list
            try { Thread.sleep(DEVICE_QUERY_DELAY_MS) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        return lastList ?: ctx.queryDevices()
    }

    private fun chooseColorProfile(pipeline: Pipeline): VideoStreamProfile? {
        val profileList: StreamProfileList = pipeline.getStreamProfileList(SensorType.COLOR) ?: return null
        val profiles = ArrayList<VideoStreamProfile>()
        try {
            for (i in 0 until profileList.getCount()) {
                val p: VideoStreamProfile = profileList.getProfile(i).`as`(StreamType.VIDEO)
                if (p.getWidth() >= 640 && p.getHeight() >= 360 && isCapturableColorFormat(p.getFormat())) profiles.add(p) else safeClose(p, "unused color profile")
            }
        } finally { safeClose(profileList, "color profileList") }
        if (profiles.isEmpty()) return null
        profiles.sortWith(compareBy<VideoStreamProfile> { colorFormatPriority(it.getFormat()) }.thenBy { kotlin.math.abs(it.getWidth() - 1280) }.thenByDescending { it.getFps() }.thenByDescending { it.getWidth() * it.getHeight() })
        val selected = profiles.first(); for (p in profiles.drop(1)) safeClose(p, "unselected color profile")
        // Log.i(TAG, "Selected color ${selected.getWidth()}x${selected.getHeight()}@${selected.getFps()} ${selected.getFormat()}")
        return selected
    }

    private fun chooseDepthProfile(pipeline: Pipeline): VideoStreamProfile? {
        val profileList: StreamProfileList = pipeline.getStreamProfileList(SensorType.DEPTH) ?: return null
        val profiles = ArrayList<VideoStreamProfile>()
        try {
            for (i in 0 until profileList.getCount()) {
                val p: VideoStreamProfile = profileList.getProfile(i).`as`(StreamType.VIDEO)
                if (p.getWidth() >= 320 && p.getHeight() >= 240 && isCapturableDepthFormat(p.getFormat())) profiles.add(p) else safeClose(p, "unused depth profile")
            }
        } finally { safeClose(profileList, "depth profileList") }
        if (profiles.isEmpty()) return null
        profiles.sortWith(compareBy<VideoStreamProfile> { depthFormatPriority(it.getFormat()) }.thenBy { kotlin.math.abs(it.getWidth() - 1280) }.thenByDescending { it.getFps() }.thenByDescending { it.getWidth() * it.getHeight() })
        val selected = profiles.first(); for (p in profiles.drop(1)) safeClose(p, "unselected depth profile")
        return selected
    }

    private fun closeSdkLocked() {
        val oldPipeline = pipeline; val oldDevice = device; val oldContext = obContext
        pipeline = null; device = null; obContext = null; selectedUid = null; streaming = false; depthStreaming = false
        pumpRunning = false
        pendingCapture.getAndSet(null)?.reject(IllegalStateException("Orbbec camera closed"))
        safeStopAndClose(oldPipeline); safeClose(oldDevice, "device"); safeClose(oldContext, "OBContext")
    }

    // ── Frame capture / encoding ─────────────────────────────────────────────
    private fun captureRgbd(): CapturedRgbd {
        val active = synchronized(stateLock) { pipeline ?: throw IllegalStateException("Orbbec pipeline is not open") }
        val wantDepth = synchronized(stateLock) { depthStreaming }
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            var frameSet: FrameSet? = null; var colorFrame: ColorFrame? = null; var depthFrame: DepthFrame? = null
            try {
                frameSet = active.waitForFrameSet(FRAME_TIMEOUT_MS) ?: continue
                colorFrame = frameSet.getColorFrame() ?: continue
                depthFrame = frameSet.getDepthFrame()
                if (wantDepth && depthFrame == null) continue
                return CapturedRgbd(encodeColorFrame(colorFrame), depthFrame?.let { encodeDepthFrame(it) })
            } catch (e: Exception) { lastError = e; Log.w(TAG, "capture attempt ${attempt + 1} failed", e) } finally {
                safeClose(depthFrame, "depth frame"); safeClose(colorFrame, "color frame"); safeClose(frameSet, "frameSet")
            }
        }
        throw IllegalStateException(lastError?.message ?: "No Orbbec RGB-D frame received")
    }

    private fun encodeColorFrame(frame: ColorFrame): CapturedJpeg {
        val width = frame.getWidth(); val height = frame.getHeight()
        if (width <= 0 || height <= 0) throw IllegalStateException("Invalid Orbbec frame size")
        val format = frame.getFormat(); val size = frame.getDataSize()
        if (size <= 0) throw IllegalStateException("Empty Orbbec frame")
        val raw = ByteArray(size); val copied = frame.getData(raw)
        if (copied < 0) throw IllegalStateException("Failed to copy Orbbec frame data")
        val data = if (copied in 0 until raw.size) raw.copyOf(copied) else raw
        val jpeg = when (format) {
            Format.MJPG -> data
            Format.RGB -> packedRgbToJpeg(data, width, height, PixelOrder.RGB)
            Format.BGR -> packedRgbToJpeg(data, width, height, PixelOrder.BGR)
            Format.RGBA -> packedRgbToJpeg(data, width, height, PixelOrder.RGBA)
            Format.BGRA -> packedRgbToJpeg(data, width, height, PixelOrder.BGRA)
            Format.YUYV, Format.YUY2 -> yuvImageToJpeg(data, ImageFormat.YUY2, width, height)
            Format.NV21 -> yuvImageToJpeg(data, ImageFormat.NV21, width, height)
            Format.NV12 -> yuvImageToJpeg(nv12ToNv21(data, width, height), ImageFormat.NV21, width, height)
            Format.UYVY -> yuv422ToJpeg(data, width, height, uyvy = true)
            Format.I420 -> i420ToJpeg(data, width, height)
            else -> throw IllegalStateException("Unsupported Orbbec color frame format: $format")
        }
        return CapturedJpeg(jpeg, width, height, format.name)
    }

    private enum class PixelOrder { RGB, BGR, RGBA, BGRA }

    private fun packedRgbToJpeg(data: ByteArray, width: Int, height: Int, order: PixelOrder): ByteArray {
        val pixelCount = width * height
        val stride = if (order == PixelOrder.RGBA || order == PixelOrder.BGRA) 4 else 3
        if (data.size < pixelCount * stride) throw IllegalStateException("Short ${order.name} frame: ${data.size} bytes")
        val pixels = IntArray(pixelCount); var src = 0
        for (i in 0 until pixelCount) {
            val r: Int; val g: Int; val b: Int
            when (order) {
                PixelOrder.RGB -> { r = data[src].u8(); g = data[src + 1].u8(); b = data[src + 2].u8() }
                PixelOrder.BGR -> { b = data[src].u8(); g = data[src + 1].u8(); r = data[src + 2].u8() }
                PixelOrder.RGBA -> { r = data[src].u8(); g = data[src + 1].u8(); b = data[src + 2].u8() }
                PixelOrder.BGRA -> { b = data[src].u8(); g = data[src + 1].u8(); r = data[src + 2].u8() }
            }
            pixels[i] = argb(r, g, b); src += stride
        }
        return pixelsToJpeg(pixels, width, height)
    }

    private fun yuvImageToJpeg(data: ByteArray, imageFormat: Int, width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (!YuvImage(data, imageFormat, width, height, null).compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)) throw IllegalStateException("Failed to encode YUV Orbbec frame")
        return out.toByteArray()
    }

    private fun nv12ToNv21(data: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        if (data.size < ySize) throw IllegalStateException("Short NV12 frame: ${data.size} bytes")
        val out = data.copyOf(); var i = ySize
        while (i + 1 < out.size) { val u = out[i]; out[i] = out[i + 1]; out[i + 1] = u; i += 2 }
        return out
    }

    private fun yuv422ToJpeg(data: ByteArray, width: Int, height: Int, uyvy: Boolean): ByteArray {
        val pixelCount = width * height
        if (data.size < pixelCount * 2) throw IllegalStateException("Short YUV422 frame: ${data.size} bytes")
        val pixels = IntArray(pixelCount); var src = 0; var dst = 0
        while (dst < pixelCount && src + 3 < data.size) {
            val y0: Int; val y1: Int; val u: Int; val v: Int
            if (uyvy) { u = data[src].u8(); y0 = data[src + 1].u8(); v = data[src + 2].u8(); y1 = data[src + 3].u8() }
            else { y0 = data[src].u8(); u = data[src + 1].u8(); y1 = data[src + 2].u8(); v = data[src + 3].u8() }
            pixels[dst++] = yuvToArgb(y0, u, v); if (dst < pixelCount) pixels[dst++] = yuvToArgb(y1, u, v); src += 4
        }
        return pixelsToJpeg(pixels, width, height)
    }

    private fun i420ToJpeg(data: ByteArray, width: Int, height: Int): ByteArray {
        val pixelCount = width * height; val cw = (width + 1) / 2; val ch = (height + 1) / 2
        val uOffset = pixelCount; val vOffset = uOffset + cw * ch
        if (data.size < vOffset + cw * ch) throw IllegalStateException("Short I420 frame: ${data.size} bytes")
        val pixels = IntArray(pixelCount)
        for (y in 0 until height) for (x in 0 until width) {
            val yV = data[y * width + x].u8(); val uvIndex = (y / 2) * cw + (x / 2)
            pixels[y * width + x] = yuvToArgb(yV, data[uOffset + uvIndex].u8(), data[vOffset + uvIndex].u8())
        }
        return pixelsToJpeg(pixels, width, height)
    }

    private fun encodeDepthFrame(frame: DepthFrame): CapturedDepth {
        val width = frame.getWidth(); val height = frame.getHeight()
        if (width <= 0 || height <= 0) throw IllegalStateException("Invalid Orbbec depth frame size")
        val format = frame.getFormat(); val size = frame.getDataSize()
        if (size <= 0) throw IllegalStateException("Empty Orbbec depth frame")
        val raw = ByteArray(size); val copied = frame.getData(raw)
        if (copied < 0) throw IllegalStateException("Failed to copy Orbbec depth frame data")
        val data = if (copied in 0 until raw.size) raw.copyOf(copied) else raw
        val y16 = when (format) { Format.Y16, Format.Y10, Format.Y11, Format.Y12 -> data; else -> throw IllegalStateException("Unsupported depth format: $format") }
        return CapturedDepth(y16, width, height, format.name, frame.getValueScale())
    }

    // ── Live preview encoders ────────────────────────────────────────────────
    private fun encodeColorPreviewBase64(frame: ColorFrame): String {
        val jpeg = encodeColorFrame(frame).bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val srcMax = maxOf(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options(); if (srcMax > COLOR_PREVIEW_MAX_DIM) opts.inSampleSize = sampleSizeFor(srcMax, COLOR_PREVIEW_MAX_DIM)
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return try { val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, COLOR_PREVIEW_JPEG_QUALITY, out); Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) } finally { bmp.recycle() }
    }

    private fun sampleSizeFor(srcMax: Int, target: Int): Int { var s = 1; while (srcMax / (s * 2) >= target) s *= 2; return s }

    private fun encodeDepthPreviewBase64(frame: DepthFrame): String? {
        val width = frame.getWidth(); val height = frame.getHeight()
        if (width <= 0 || height <= 0) return null
        val size = frame.getDataSize(); if (size <= 0) return null
        val raw = ByteArray(size); val copied = frame.getData(raw); if (copied <= 0) return null
        val data = if (copied in 1 until raw.size) raw.copyOf(copied) else raw
        if (data.size < width * height * 2) return null
        val scale = frame.getValueScale()
        val step = maxOf(1, maxOf(width, height) / DEPTH_PREVIEW_MAX_DIM)
        val outW = (width + step - 1) / step; val outH = (height + step - 1) / step
        if (outW <= 0 || outH <= 0) return null
        val mmGrid = FloatArray(outW * outH); val validMm = FloatArray(outW * outH); var validCount = 0
        var di = 0; var y = 0
        while (y < height && di < mmGrid.size) {
            var x = 0
            while (x < width && di < mmGrid.size) {
                val idx = (y * width + x) * 2
                val v = (data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)
                val mm = v * scale; mmGrid[di++] = mm
                if (isPlausiblePreviewDepth(mm)) validMm[validCount++] = mm
                x += step
            }
            y += step
        }
        val filled = di
        val lo: Float; val hi: Float
        if (validCount > 0) { validMm.sort(0, validCount); val r = smoothDepthRange(percentile(validMm, validCount, DEPTH_RANGE_LOW_PERCENTILE), percentile(validMm, validCount, DEPTH_RANGE_HIGH_PERCENTILE)); lo = r.first; hi = r.second }
        else { lo = DEPTH_MIN_MM; hi = DEPTH_MAX_MM }
        val span = (hi - lo).coerceAtLeast(1f)
        val pixels = IntArray(outW * outH); var ci = 0
        while (ci < filled) { pixels[ci] = depthPreviewColor(mmGrid[ci], lo, span); ci++ }
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        return try { bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH); val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, DEPTH_PREVIEW_JPEG_QUALITY, out); Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) } finally { bmp.recycle() }
    }

    private fun isPlausiblePreviewDepth(mm: Float) = mm >= DEPTH_RANGE_FLOOR_MM && mm <= DEPTH_RANGE_CEILING_MM
    private fun percentile(sorted: FloatArray, count: Int, p: Float): Float { if (count <= 0) return 0f; return sorted[kotlin.math.round((count - 1) * p).toInt().coerceIn(0, count - 1)] }
    private fun depthPreviewColor(mm: Float, minMm: Float, span: Float): Int {
        if (!isPlausiblePreviewDepth(mm)) return 0xFF shl 24
        val t = ((mm - minMm) / span).coerceIn(0f, 1f)
        return argb((clampUnit(1.5f - kotlin.math.abs(4f * t - 3f)) * 255f).toInt(), (clampUnit(1.5f - kotlin.math.abs(4f * t - 2f)) * 255f).toInt(), (clampUnit(1.5f - kotlin.math.abs(4f * t - 1f)) * 255f).toInt())
    }
    private fun smoothDepthRange(frameMin: Float, frameMax: Float): Pair<Float, Float> {
        val pad = (frameMax - frameMin) * DEPTH_RANGE_PAD
        val targetMin = (frameMin - pad).coerceAtLeast(DEPTH_RANGE_FLOOR_MM); val targetMax = (frameMax + pad).coerceAtMost(DEPTH_RANGE_CEILING_MM)
        if (!depthRangeInit) { depthRangeMinMm = targetMin; depthRangeMaxMm = targetMax; depthRangeInit = true }
        else { depthRangeMinMm += (targetMin - depthRangeMinMm) * DEPTH_RANGE_EMA; depthRangeMaxMm += (targetMax - depthRangeMaxMm) * DEPTH_RANGE_EMA }
        if (depthRangeMaxMm - depthRangeMinMm < DEPTH_RANGE_MIN_SPAN_MM) depthRangeMaxMm = depthRangeMinMm + DEPTH_RANGE_MIN_SPAN_MM
        return Pair(depthRangeMinMm, depthRangeMaxMm)
    }
    private fun clampUnit(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

    private fun pixelsToJpeg(pixels: IntArray, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { bitmap.setPixels(pixels, 0, width, 0, 0, width, height); val out = ByteArrayOutputStream(); if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IllegalStateException("Failed to encode Orbbec RGB frame"); return out.toByteArray() } finally { bitmap.recycle() }
    }
    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val c = (y - 16).coerceAtLeast(0); val d = u - 128; val e = v - 128
        return argb(clamp((298 * c + 409 * e + 128) shr 8), clamp((298 * c - 100 * d - 208 * e + 128) shr 8), clamp((298 * c + 516 * d + 128) shr 8))
    }
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun clamp(value: Int) = value.coerceIn(0, 255)
    private fun Byte.u8() = toInt() and 0xFF

    private fun isCapturableColorFormat(format: Format) = when (format) {
        Format.MJPG, Format.RGB, Format.BGR, Format.RGBA, Format.BGRA, Format.YUYV, Format.YUY2, Format.UYVY, Format.NV21, Format.NV12, Format.I420 -> true
        else -> false
    }
    private fun colorFormatPriority(format: Format) = when (format) {
        Format.MJPG -> 0; Format.RGB -> 1; Format.BGR -> 2; Format.RGBA, Format.BGRA -> 3
        Format.YUYV, Format.YUY2, Format.NV21, Format.NV12 -> 4; Format.UYVY, Format.I420 -> 5; else -> 99
    }
    private fun isCapturableDepthFormat(format: Format) = when (format) { Format.Y16, Format.Y10, Format.Y11, Format.Y12 -> true; else -> false }
    private fun depthFormatPriority(format: Format) = when (format) { Format.Y16 -> 0; Format.Y12 -> 1; Format.Y11 -> 2; Format.Y10 -> 3; else -> 99 }

    private fun safeStopAndClose(pipeline: Pipeline?) { if (pipeline == null) return; try { pipeline.stop() } catch (e: Exception) { /* Log.d(TAG, "pipeline stop ignored", e) */ }; safeClose(pipeline, "pipeline") }
    private fun safeClose(closeable: AutoCloseable?, label: String) { if (closeable == null) return; try { closeable.close() } catch (e: Exception) { /* Log.d(TAG, "close $label ignored", e) */ } }
}
