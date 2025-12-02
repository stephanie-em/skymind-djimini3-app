package dji.sampleV5.aircraft

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.IStick
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystick
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystickListener
//import io.livekit.android.BuildConfig
//import dji.sampleV5.aircraft.BuildConfig
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContracts
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import android.view.View
import android.view.SurfaceView
import android.view.Surface
import android.view.SurfaceHolder

import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.sdk.keyvalue.value.common.ComponentIndexType

import dji.v5.manager.KeyManager
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.common.EmptyMsg

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJICameraKey.KeyStopRecord
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.et.action
import dji.v5.et.create
import dji.sdk.keyvalue.value.camera.ZoomRatiosRange
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKey
import android.graphics.Color
import android.graphics.drawable.GradientDrawable




private const val TAG = "TouchFly"

class TouchFlyActivity : AppCompatActivity() {

    // --- LiveKit fields ---
    private var livekitRoom: Room? = null
    private val livekitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
//livekit room variable taken out for git purposes



    // --- ui references (from frag_virtual_stick.xml) ---
    private lateinit var infoTv: TextView
    private lateinit var leftStickView: OnScreenJoystick
    private lateinit var rightStickView: OnScreenJoystick
    private lateinit var btnEnableVS: Button
    private lateinit var btnDisableVS: Button
    private var btnStop: Button? = null // reuse one of the other variables as stop?
    private lateinit var tvLogs: TextView

    // --- DJI camera stream fields (reusing from LiveFragment.kt, simplified) ---
    private lateinit var cameraSurface: SurfaceView
    private lateinit var rootView: View
    private lateinit var controlsOverlay: View
    private var cleanMode: Boolean = false
    private val cameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager
    private var cameraStreamSurface: Surface? = null
    private var cameraStreamWidth = -1
    private var cameraStreamHeight = -1
    private var cameraStreamScaleType: ICameraStreamManager.ScaleType =
        ICameraStreamManager.ScaleType.CENTER_INSIDE
    private var cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
    private var isLocalLiveShow: Boolean = true


    // Normalized joystick inputs [-1, 1]
    @Volatile
    private var pitchInput = 0.0 // forward/back(right stick vertical)
    @Volatile
    private var rollInput = 0.0 // left/right(right stick horizontal)
    @Volatile
    private var yawInput = 0.0 // turn(left stick horizontal)
    @Volatile
    private var throttleInput = 0.0 // up/down(left stick vertical)
    private val sendHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var sending = false

    private lateinit var btnStartStream: Button
    private lateinit var btnStopStream: Button

    private lateinit var btnRecordStart: Button
    private lateinit var btnRecordStop: Button

    private lateinit var btnTakeOff: Button
    private lateinit var btnRth: Button

    private lateinit var btnLand: Button
    private var zoomCurrent = 1.0
    private var zoomMin = 1.0
    private var zoomMax = 1.0

    private lateinit var cameraZoomKey: DJIKey<Double>
    private lateinit var cameraZoomRangeKey: DJIKey<ZoomRatiosRange>

    private lateinit var batteryTv: TextView
    private lateinit var batteryPercentKey: DJIKey<Int>

    // key indicator views
    private lateinit var indicatorZoomIn: View
    private lateinit var indicatorZoomOut: View
    private lateinit var indicatorTakeoff: View
    private lateinit var indicatorRth: View
    private lateinit var indicatorLand: View

    private lateinit var gimbalTrack: View
    private lateinit var gimbalThumb: View
    private var gimbalLevel = 0.0




    @Volatile
    private var isScreenShareOn = false


    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultCode = result.resultCode
        val data: Intent? = result.data

        if (resultCode != RESULT_OK || data == null) {
            logToUi("Screen share: permission denied or cancelled")
            return@registerForActivityResult
        }

        // Got the MediaProjection resultData --> LiveKit to starts to screen share
        startScreenShareWithLiveKit(data)
    }

    private fun requestScreenShare() {
        val room = livekitRoom
        if (room == null) {
            logToUi("Screen share: LiveKit room not connected yet.")
            return
        }

        if (isScreenShareOn) {
            logToUi("Screen share already running.")
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = mpm.createScreenCaptureIntent()
        logToUi("Screen share: requesting system capture permission…")
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startScreenShareWithLiveKit(resultData: Intent) {
        val room = livekitRoom
        if (room == null) {
            logToUi("Screen share: room is null, cannot start.")
            return
        }

        livekitScope.launch {
            try {
                val params = ScreenCaptureParams(
                    mediaProjectionPermissionResultData = resultData
                )

                logToUi("Screen share: enabling via LiveKit…")
                val ok = room.localParticipant.setScreenShareEnabled(true, params)

                withContext(Dispatchers.Main) {
                    if (ok) {
                        isScreenShareOn = true
                        logToUi("Screen share: started.")
                    } else {
                        logToUi("Screen share: failed to start.")
                    }
                }
            } catch (e: Exception) {
                logToUi("Screen share: error starting -> ${e.message}")
            }
        }
    }

    private fun stopScreenShare() {
        val room = livekitRoom ?: run {
            logToUi("Screen share: no room, nothing to stop.")
            return
        }

        if (!isScreenShareOn) {
            logToUi("Screen share not running.")
            return
        }

        livekitScope.launch {
            try {
                logToUi("Screen share: disabling…")
                val ok = room.localParticipant.setScreenShareEnabled(false)

                withContext(Dispatchers.Main) {
                    if (ok) {
                        isScreenShareOn = false
                        logToUi("Screen share: stopped.")
                    } else {
                        logToUi("Screen share: failed to stop.")
                    }
                }
            } catch (e: Exception) {
                logToUi("Screen share: error stopping -> ${e.message}")
            }
        }
    }

    // HTTP client for token
    private val http = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.frag_virtual_stick_page)
        Log.d(TAG, "onCreate: TouchFlyActivity activated")

        // id for control/button/joystick ui
        rootView = findViewById(R.id.root)
        controlsOverlay = findViewById(R.id.controls_overlay)
        cameraSurface = findViewById(R.id.sv_camera_stream)

        // Bind ui elements
        infoTv = findViewById(R.id.virtual_stick_info_tv)
        leftStickView = findViewById(R.id.left_stick_view)
        rightStickView = findViewById(R.id.right_stick_view)

        batteryTv = findViewById(R.id.tv_battery)
        indicatorZoomIn = findViewById(R.id.indicator_zoom_in)
        indicatorZoomOut = findViewById(R.id.indicator_zoom_out)
        indicatorTakeoff = findViewById(R.id.indicator_takeoff)
        indicatorRth = findViewById(R.id.indicator_rth)
        indicatorLand = findViewById(R.id.indicator_land)
        gimbalTrack = findViewById(R.id.gimbal_slider_track)
        gimbalThumb = findViewById(R.id.gimbal_slider_thumb)
        updateGimbalSliderUi()


        leftStickView.setLabels(
            up   = "SPACE",
            down = "SHIFT",
            left = "A",
            right= "D"
        )

        rightStickView.setLabels(
            up   = "↑",
            down = "↓",
            left = "←",
            right= "→"
        )

        btnEnableVS = findViewById(R.id.btn_enable_virtual_stick)
        btnDisableVS = findViewById(R.id.btn_disable_virtual_stick)
        tvLogs = findViewById(R.id.tvLogs)
        btnStartStream = findViewById(R.id.btn_start_stream)
        btnStopStream = findViewById(R.id.btn_stop_stream)
        btnRecordStart = findViewById(R.id.btn_record_start)
        btnRecordStop = findViewById(R.id.btn_record_stop)

        btnStartStream.setOnClickListener {
            requestScreenShare()
        }

        btnStopStream.setOnClickListener {
            stopScreenShare()
        }

        btnRecordStart.setOnClickListener {
            startRecordingToSd()
        }

        btnRecordStop.setOnClickListener {
            stopRecordingToSd()
        }

        rootView.setOnLongClickListener {
            toggleCleanMode()
            true
        }
        btnTakeOff = findViewById(R.id.btn_take_off)
        btnRth = findViewById(R.id.btn_return_home)
        btnLand = findViewById(R.id.btn_landing)
        btnTakeOff.setOnClickListener {
            startSdkTakeoff()
        }

        btnRth.setOnClickListener {
            startSdkReturnToHome()
            // pure auto-landing--> call startSdkAutoLanding()
        }

        btnLand.setOnClickListener {
            startSdkLanding()
        }



        // re-use an extra button as STOP ("send_virtual_stick_advanced_param")
        btnStop = findViewById(R.id.btn_send_virtual_stick_advanced_param)
        btnStop?.text = "STOP"

        logToUi("TouchFlyActivity started")

        setupTouchControls()
        setupButtons()

        initCameraStreamPreview()
        initZoomControls()
        initBatteryPercentage()

        setupLiveKitFromIntent()
    }



    // --- DJI camera preview wiring (adapted from LiveFragment.kt) ---
    private fun initCameraStreamPreview() {
        // Listen for Surface lifecycle
        cameraSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                cameraStreamWidth = width
                cameraStreamHeight = height
                cameraStreamSurface = holder.surface
                putCameraStreamSurface()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraStreamManager.removeCameraStreamSurface(holder.surface)
            }
        })

        // always show local live
        isLocalLiveShow = true

        // Set default camera index (main camera)
        cameraIndex = ComponentIndexType.LEFT_OR_MAIN
    }

    //Tell DJI to draw the selected camera into SurfaceView
    private fun putCameraStreamSurface() {
        if (!isLocalLiveShow) return
        if (cameraIndex == ComponentIndexType.UNKNOWN) return

        cameraStreamSurface?.let { surface ->
            cameraStreamManager.putCameraStreamSurface(
                cameraIndex,
                surface,
                cameraStreamWidth,
                cameraStreamHeight,
                cameraStreamScaleType
            )
            logToUi("Camera stream attached: $cameraIndex ${cameraStreamWidth}x$cameraStreamHeight")
        }
    }

    //Remove this surface from DJI camera stream manager
    private fun removeCameraStreamSurface() {
        cameraStreamSurface?.let {
            cameraStreamManager.removeCameraStreamSurface(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sending = false
        sendHandler.removeCallbacksAndMessages(null)

        try {
            KeyManager.getInstance().cancelListen(batteryPercentKey, this)
        } catch (_: Exception) { }

        livekitScope.launch{
            try{
                livekitRoom?.localParticipant?.setScreenShareEnabled(false)
            } catch(_:Exception){}
        }

        removeCameraStreamSurface()
        livekitScope.cancel()
        livekitRoom?.disconnect()

        livekitRoom = null
    }

    // Helper: log to both Logcat and virtual_stick_info_tv
    private fun logToUi(message: String) {
        Log.d(TAG, message)

        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val line = "[$time] $message"

        // Short status in virtual_stick_info_tv
        infoTv.post {
            val existing = infoTv.text?.toString().orEmpty()
            val newText = if (existing.isEmpty()) line else "$existing\n$line"
            infoTv.text = if (newText.length > 2000) newText.takeLast(2000) else newText
        }

        // Full log scroll in tvLogs
        tvLogs.post {
            tvLogs.append(line + "\n")
            val layout = tvLogs.layout
            if (layout != null) {
                val scrollAmount = layout.getLineTop(tvLogs.lineCount) - tvLogs.height
                if (scrollAmount > 0) tvLogs.scrollTo(0, scrollAmount) else tvLogs.scrollTo(0, 0)
            }
        }
    }
    private fun toggleCleanMode() {
        cleanMode = !cleanMode

        runOnUiThread {
            val sidePanel = findViewById<View>(R.id.stick_button_list)
            val infoText = findViewById<View>(R.id.virtual_stick_info_tv)
            val simInfo = findViewById<View>(R.id.simulator_state_info_tv)
            val hsi = findViewById<View>(R.id.widget_horizontal_situation_indicator)

            if (cleanMode) {
                //hide side panel + extra info, keep joysticks + logs
                sidePanel.visibility = View.GONE
                infoText.visibility = View.GONE
                simInfo.visibility = View.GONE
                hsi.visibility = View.GONE
            } else {
                //show everything again
                sidePanel.visibility = View.VISIBLE
                infoText.visibility = View.VISIBLE
                simInfo.visibility = View.VISIBLE
                hsi.visibility = View.VISIBLE
            }
        }

        val msg = if (cleanMode) {
            "Entered CLEAN MODE (joysticks + logs only)"
        } else {
            "Exited CLEAN MODE (full controls visible)"
        }
        Log.d(TAG, msg)
        logToUi(msg)
    }


    private fun adjustGimbalPitch(deltaDegrees: Double) {
        val rotation = GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.RELATIVE_ANGLE
            pitch = deltaDegrees  // + up, - down
            duration = 0.3
        }

        val actionKey: DJIKey.ActionKey<GimbalAngleRotation, EmptyMsg> =
            KeyTools.createKey(GimbalKey.KeyRotateByAngle, 0)

        KeyManager.getInstance().performAction<GimbalAngleRotation, EmptyMsg>(
            actionKey,
            rotation,
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(result: EmptyMsg?) {
                    logToUi("Gimbal pitch moved ${"%.1f".format(deltaDegrees)}°")
                }

                override fun onFailure(error: IDJIError) {
                    logToUi(
                        "GIMBAL ERROR: code=${error.errorCode()} msg=${error.description()}"
                    )
                }
            }
        )
    }

    private fun startRecordingToSd() {
        logToUi("Start recording requested…")

        val cameraIndex = 0

        val actionKey: DJIKey.ActionKey<EmptyMsg, EmptyMsg> =
            KeyTools.createKey(CameraKey.KeyStartRecord, cameraIndex)

        KeyManager.getInstance().performAction(
            actionKey,
            EmptyMsg(),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(result: EmptyMsg?) {
                    logToUi("Recording STARTED (SDK)")
                }

                override fun onFailure(error: IDJIError) {
                    logToUi("Failed to start recording: ${error.errorCode()} ${error.description()}")
                }
            }
        )
    }

    private fun stopRecordingToSd() {
        logToUi("Stop recording requested…")

        val cameraIndex = 0

        val actionKey: DJIKey.ActionKey<EmptyMsg, EmptyMsg> =
            KeyTools.createKey(KeyStopRecord, cameraIndex)

        KeyManager.getInstance().performAction(
            actionKey,
            EmptyMsg(),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(result: EmptyMsg?) {
                    logToUi("Recording STOPPED (SDK)")
                }

                override fun onFailure(error: IDJIError) {
                    logToUi("Failed to stop recording: ${error.errorCode()} ${error.description()}")
                }
            }
        )
    }

    private fun startSdkTakeoff() {
        logToUi("Takeoff requested (FlightControllerKey.KeyStartTakeoff)…")

        val actionKey: DJIKey.ActionKey<EmptyMsg, EmptyMsg> =
            KeyTools.createKey(FlightControllerKey.KeyStartTakeoff)

        KeyManager.getInstance().performAction(
            actionKey,
            EmptyMsg(),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(result: EmptyMsg?) {
                    logToUi("TAKEOFF command sent successfully.")
                }

                override fun onFailure(error: IDJIError) {
                    logToUi("TAKEOFF FAILED: ${error.errorCode()} ${error.description()}")
                }
            }
        )
    }

    private fun startSdkLanding() {
        logToUi("Landing requested via FlightControllerKey.KeyStartAutoLanding (et)…")

        FlightControllerKey.KeyStartAutoLanding.create().action(
            { result: EmptyMsg ->
                logToUi("AUTO-LANDING command sent successfully. $result")
            },
            { e: IDJIError ->
                logToUi("AUTO-LANDING FAILED: ${e.errorCode()} ${e.description()}")
            }
        )
    }

    private fun startSdkReturnToHome() {
        logToUi("Return-to-Home requested via FlightControllerKey.KeyStartGoHome…")

        FlightControllerKey.KeyStartGoHome.create().action(
            { result: EmptyMsg ->
                logToUi("GO HOME command sent successfully. $result")
            },
            { e: IDJIError ->
                logToUi("GO HOME FAILED: ${e.errorCode()} ${e.description()}")
            }
        )
    }


    private fun initZoomControls() {
        cameraZoomKey = KeyTools.createCameraKey(
            CameraKey.KeyCameraZoomRatios,
            cameraIndex,
            CameraLensType.CAMERA_LENS_DEFAULT
        )
        cameraZoomRangeKey = KeyTools.createCameraKey(
            CameraKey.KeyCameraZoomRatiosRange,
            cameraIndex,
            CameraLensType.CAMERA_LENS_DEFAULT
        )

        // Get zoom range (min/max)
        KeyManager.getInstance().getValue(
            cameraZoomRangeKey,
            object : CommonCallbacks.CompletionCallbackWithParam<ZoomRatiosRange> {
                override fun onSuccess(range: ZoomRatiosRange) {
                    val gears = range.gears
                    if (gears != null && gears.isNotEmpty()) {
                        zoomMin = gears.first().toDouble()
                        zoomMax = gears.last().toDouble()
                    } else {
                        // fallback if gears is empty
                        zoomMin = 1.0
                        zoomMax = 2.0
                    }
                    logToUi("Zoom range from SDK: $zoomMin – $zoomMax")
                }

                override fun onFailure(error: IDJIError) {
                    logToUi(
                        "Zoom range not available: ${error.errorCode()} ${error.description()}"
                    )
                }
            }
        )

        //read current zoom
        KeyManager.getInstance().getValue(
            cameraZoomKey,
            object : CommonCallbacks.CompletionCallbackWithParam<Double> {
                override fun onSuccess(value: Double) {
                    zoomCurrent = value
                    logToUi("Initial zoom ratio: x${"%.2f".format(zoomCurrent)}")
                }

                override fun onFailure(error: IDJIError) {
                    logToUi(
                        "Failed to read current zoom: ${error.errorCode()} ${error.description()}"
                    )
                }
            }
        )
    }

    private fun changeZoom(step: Double) {
        val target = (zoomCurrent + step).coerceIn(zoomMin, zoomMax)

        KeyManager.getInstance().setValue(
            cameraZoomKey,
            target,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    zoomCurrent = target
                    logToUi("Zoom set to x${"%.2f".format(target)}")
                }

                override fun onFailure(error: IDJIError) {
                    logToUi(
                        "Zoom FAILED: ${error.errorCode()} ${error.description()}"
                    )
                }
            }
        )
    }

    private fun zoomIn() {
        changeZoom(+0.2)
    }

    private fun zoomOut() {
        changeZoom(-0.2)
    }

    private fun updateGimbalSliderUi() {
        val trackH = gimbalTrack.height
        val thumbH = gimbalThumb.height
        if (trackH == 0 || thumbH == 0) {
            // wait until layout is done
            gimbalTrack.post { updateGimbalSliderUi() }
            return
        }

        // map gimbalLevel [-1,1] -> [0, trackH - thumbH]
        val travel = (trackH - thumbH).toFloat()
        val normalized = ((-gimbalLevel + 1.0) / 2.0)
            .toFloat()
            .coerceIn(0f, 1f)

        val offsetPx = normalized * travel
        gimbalThumb.translationY = offsetPx
    }


    private fun initBatteryPercentage() {
        batteryPercentKey = KeyTools.createKey(
            BatteryKey.KeyChargeRemainingInPercent,
            0
        )

        // Get initial value
        KeyManager.getInstance().getValue(
            batteryPercentKey,
            object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                override fun onSuccess(value: Int) {
                    updateBatteryUi(value)
                }

                override fun onFailure(error: IDJIError) {
                    logToUi("Battery read failed: ${error.errorCode()} ${error.description()}")
                }
            }
        )

        //Subscribe for live updates
        KeyManager.getInstance().listen(
            batteryPercentKey,
            this  // use Activity as listener tag
        ) { _, newValue ->
            newValue?.let { updateBatteryUi(it) }
        }
    }

    private fun updateBatteryUi(percent: Int) {
        runOnUiThread {
            batteryTv.text = "Battery: $percent%"
        }
    }

    private fun nudgeYaw(step: Double) {
        // clamp just in case
        yawInput = step.coerceIn(-1.0, 1.0)
        updateJoystickFromInputs()

        // after 200ms, reset yaw to 0 so it's just a small increment
        sendHandler.postDelayed({
            yawInput = 0.0
            updateJoystickFromInputs()
        }, 200L)
    }

    private fun setIndicatorActive(view: View, active: Boolean) {
        val bg = view.background
        if (bg is GradientDrawable) {
            bg.mutate()
            bg.setColor(
                if (active) Color.parseColor("#4DA3FF") // ON (blue)
                else Color.parseColor("#555555")        // OFF (gray)
            )
        }
    }






    private fun setupButtons() {
        val vm = VirtualStickManager.getInstance()
        vm.init()

        btnEnableVS.setOnClickListener {
            logToUi("Enabling Virtual Stick…")
            vm.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    runOnUiThread {
                        logToUi("Virtual Stick ENABLED. Take off, then drag sticks or use LiveKit.")
                    }
                    startSendingLoop()
                }

                override fun onFailure(error: IDJIError) {
                    runOnUiThread {
                        logToUi("Enable VS FAILED: ${error.description()}")
                    }
                }
            })
        }

        btnDisableVS.setOnClickListener {
            logToUi("Disabling Virtual Stick…")
            vm.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    runOnUiThread {
                        logToUi("Virtual Stick DISABLED.")
                    }
                    sending = false
                }

                override fun onFailure(error: IDJIError) {
                    runOnUiThread {
                        logToUi("Disable VS FAILED: ${error.description()}")
                    }
                }
            })
        }

        btnStop?.setOnClickListener {
            pitchInput = 0.0
            rollInput = 0.0
            yawInput = 0.0
            throttleInput = 0.0
            logToUi("STOP → hover (all sticks neutral)")
        }
    }

    // --- LiveKit token + room setup ---

    private suspend fun fetchTokenFromServer(
        role: String = "android",

        //room variable taken out for git
    ): String? {
        val url = "https://skymind.live/api/token?role=$role&room=$room"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                logToUi("token error: HTTP ${resp.code}")
                return null
            }

            val body = resp.body?.string() ?: return null
            val obj = JSONObject(body)
            return obj.optString("token", null)
        }
    }

    private fun setupLiveKitFromIntent() {
        logToUi("setupLiveKitFromIntent: start")

        livekitScope.launch {
            try {
                val tokenFromIntent = intent.getStringExtra("LIVEKIT_TOKEN")
                logToUi("setupLiveKitFromIntent: tokenFromIntent present? ${tokenFromIntent != null}")

                val token = tokenFromIntent ?: fetchTokenFromServer(
                    role = "android",
                    //room name taken out for git
                )

                if (token.isNullOrBlank()) {
                    logToUi("setupLiveKitFromIntent: No token; LiveKit disabled.")
                    return@launch
                }

                logToUi("setupLiveKitFromIntent: got token, creating room")

                val room = LiveKit.create(applicationContext)
                livekitRoom = room

                // Listen for DataReceived
                launch {
                    room.events.collect { event ->
                        when (event) {
                            is RoomEvent.DataReceived -> handleLiveKitData(event)
                            else -> { /* ignore */ }
                        }
                    }
                }

                val opts = ConnectOptions(audio = false, video = false)
                logToUi("setupLiveKitFromIntent: connecting to $livekitUrl")
                room.connect(livekitUrl, token, opts)
                logToUi("Connected to LiveKit for remote control.")
            } catch (e: Exception) {
                logToUi("setupLiveKitFromIntent: Failed to connect LiveKit room: ${e.message}")
            }
        }
    }

    // --- LiveKit data handling ---

    private fun handleLiveKitData(event: RoomEvent.DataReceived) {
        val raw = String(event.data, Charsets.UTF_8)
        logToUi("LiveKit data received: $raw")

        try {
            val obj = JSONObject(raw)
            when (obj.optString("type", "")) {
                "stick" -> {
                    val pitch = obj.optDouble("pitch", 0.0)
                    val roll = obj.optDouble("roll", 0.0)
                    val yaw = obj.optDouble("yaw", 0.0)
                    val throttle = obj.optDouble("throttle", 0.0)
                    applyRemoteStick(pitch, roll, yaw, throttle)
                }
                "key" -> {
                    val code = obj.optString("code", "")
                    val down = obj.optBoolean("down", false)
                    logToUi("Key from pilot: code=$code down=$down")
                    mapKeyToStick(code, down)
                }
                "command" -> {
                    val cmd = obj.optString("cmd", "")
                    logToUi("Received command: $cmd")
                }
            }
        } catch (e: Exception) {
            logToUi("Failed to parse LiveKit data: $raw (${e.message})")
        }
    }

    private fun updateJoystickFromInputs() {
        // left: yaw (x), throttle (y)
        leftStickView.setPositionFromRemote(
            yawInput.toFloat(),
            throttleInput.toFloat()
        )
        // right: roll (x), pitch (y)
        rightStickView.setPositionFromRemote(
            rollInput.toFloat(),
            pitchInput.toFloat()
        )
    }

    private fun mapKeyToStick(code: String, down: Boolean) {
        val delta = if (down) 0.5 else 0.0

        when (code) {
            "ArrowUp" -> pitchInput = delta // forward
            "ArrowDown" -> pitchInput = -delta // backward
            "ArrowRight" -> rollInput = delta // right
            "ArrowLeft" -> rollInput = -delta // left
            "SPACE" -> throttleInput = delta // up
            "SHIFT" -> throttleInput = -delta // down


            "A" -> if (down) nudgeYaw(-0.5) // left yaw increment
            "D" -> if (down) nudgeYaw(+0.5) // right yaw increment


            "W" -> if (down) {
                adjustGimbalPitch(+2.0)  // tilt camera up
                gimbalLevel = (gimbalLevel + 0.1).coerceIn(-1.0, 1.0)
                runOnUiThread { updateGimbalSliderUi() }
            }
            "S" -> if (down) {
                adjustGimbalPitch(-2.0)  // tilt camera down
                gimbalLevel = (gimbalLevel - 0.1).coerceIn(-1.0, 1.0)
                runOnUiThread { updateGimbalSliderUi() }
            }

            "I", "KEYI", "KeyI" -> if (down) {
                logToUi("Pilot requested ZOOM IN (I)")
                zoomIn()
            }
            "O", "KEYO", "KeyO" -> if (down) {
                logToUi("Pilot requested ZOOM OUT (O)")
                zoomOut()
            }

            "T" -> if (down) {
                logToUi("Pilot requested TAKEOFF (T)")
                startSdkTakeoff()

            }
            "R", "KEYR" -> if (down) {
                logToUi("Pilot requested RETURNTOHOME (R)")
                startSdkReturnToHome()
            }
            "L", "KEYL" -> if(down){
                logToUi("Pilot requestion LAND (L)")
                startSdkLanding()
            }

            else -> { /* ignore */ }
        }

        val highlightUpRight    = (code == "ArrowUp"   && down)
        val highlightDownRight  = (code == "ArrowDown" && down)
        val highlightLeftRight  = (code == "ArrowLeft" && down)
        val highlightRightRight = (code == "ArrowRight"&& down)

        val highlightUpLeft    = (code == "SPACE" && down)   // up
        val highlightDownLeft  = (code == "SHIFT" && down)   // down
        val highlightLeftLeft  = (code == "A" && down)       // yaw left
        val highlightRightLeft = (code == "D" && down)       // yaw right


        runOnUiThread {
            // Update wedge highlight
            rightStickView.setHighlight(
                up = highlightUpRight,
                down = highlightDownRight,
                left = highlightLeftRight,
                right = highlightRightRight
            )

            leftStickView.setHighlight(
                up = highlightUpLeft,
                down = highlightDownLeft,
                left = highlightLeftLeft,
                right = highlightRightLeft
            )

            // Move the knobs
            updateJoystickFromInputs()

            when (code) {
                "I", "KEYI", "KeyI" ->
                    setIndicatorActive(indicatorZoomIn, down)
                "O", "KEYO", "KeyO" ->
                    setIndicatorActive(indicatorZoomOut, down)
                "T" ->
                    setIndicatorActive(indicatorTakeoff, down)
                "R", "KEYR" ->
                    setIndicatorActive(indicatorRth, down)
                "L", "KEYL" ->
                    setIndicatorActive(indicatorLand, down)
            }


            // Info text
            infoTv.text = buildString {
                appendLine("Remote control (keys) active")
                append("P="); append(String.format("%.2f", pitchInput))
                append(" R="); append(String.format("%.2f", rollInput))
                append(" Y="); append(String.format("%.2f", yawInput))
                append(" T="); append(String.format("%.2f", throttleInput))
            }
        }
    }

    //injecting livekit data
    private fun applyRemoteStick(
        pitch: Double,
        roll: Double,
        yaw: Double,
        throttle: Double
    ) {
        pitchInput = pitch.coerceIn(-1.0, 1.0)
        rollInput = roll.coerceIn(-1.0, 1.0)
        yawInput = yaw.coerceIn(-1.0, 1.0)
        throttleInput = throttle.coerceIn(-1.0, 1.0)

        runOnUiThread {
            updateJoystickFromInputs()

            infoTv.text = buildString {
                appendLine("Remote control active")
                append("P="); append(String.format("%.2f", pitchInput))
                append(" R="); append(String.format("%.2f", rollInput))
                append(" Y="); append(String.format("%.2f", yawInput))
                append(" T="); append(String.format("%.2f", throttleInput))
            }
        }
    }

    // --- Virtual stick sending loop ---
    private fun startSendingLoop() {
        if (sending) return
        sending = true

        val vm = VirtualStickManager.getInstance()
        val left: IStick = vm.leftStick // yaw + throttle
        val right: IStick = vm.rightStick // roll + pitch

        val maxStick = 660 // DJI stick range [-660, 660]

        val task = object : Runnable {
            override fun run() {
                if (!sending) return

                val yawPos = (yawInput * maxStick).toInt().coerceIn(-maxStick, maxStick)
                val throttlePos = (throttleInput * maxStick).toInt().coerceIn(-maxStick, maxStick)
                val rollPos = (rollInput * maxStick).toInt().coerceIn(-maxStick, maxStick)
                val pitchPos = (pitchInput * maxStick).toInt().coerceIn(-maxStick, maxStick)

                left.setHorizontalPosition(yawPos)
                left.setVerticalPosition(throttlePos)
                right.setHorizontalPosition(rollPos)
                right.setVerticalPosition(pitchPos)

                sendHandler.postDelayed(this, 50L) // ~20 Hz
            }
        }

        sendHandler.post(task)
    }

    // --- Local touch joysticks using left_stick_view / right_stick_view ---
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchControls() {
        val deadZone = 0.02f  // same as ~deviation~ in VirtualStickFragment

        // left stick: throttle (vertical) + yaw (horizontal)
        leftStickView.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var x = 0f
                var y = 0f

                if (kotlin.math.abs(pX) >= deadZone) x = pX
                if (kotlin.math.abs(pY) >= deadZone) y = pY

                // Map to [-1, 1] inputs
                yawInput = x.toDouble()
                throttleInput = y.toDouble()

                logToUi(
                    "Left stick -> yaw=${
                        String.format("%.2f", yawInput)
                    }, throttle=${String.format("%.2f", throttleInput)}"
                )
            }
        })

        // right stick: roll (horizontal) + pitch (vertical)
        rightStickView.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var x = 0f
                var y = 0f

                if (kotlin.math.abs(pX) >= deadZone) x = pX
                if (kotlin.math.abs(pY) >= deadZone) y = pY

                rollInput = x.toDouble()
                pitchInput = y.toDouble()

                logToUi(
                    "Right stick -> roll=${
                        String.format("%.2f", rollInput)
                    }, pitch=${String.format("%.2f", pitchInput)}"
                )
            }
        })
    }
}
