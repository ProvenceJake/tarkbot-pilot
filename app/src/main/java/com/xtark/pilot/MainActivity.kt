package com.xtark.pilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xtark.pilot.camera.PeopleResult
import com.xtark.pilot.camera.PersonAutoSelector
import com.xtark.pilot.camera.PersonCandidate
import com.xtark.pilot.camera.PersonBodyDetector
import com.xtark.pilot.camera.PersonTargetContinuity
import com.xtark.pilot.camera.TargetColor
import com.xtark.pilot.camera.TargetResult
import com.xtark.pilot.camera.TrackingMode
import com.xtark.pilot.camera.VisionAnalyzer
import com.xtark.pilot.control.DriveJoystickView
import com.xtark.pilot.control.MotionSafetyGate
import com.xtark.pilot.control.TargetFollowController
import com.xtark.pilot.databinding.ActivityMainBinding
import com.xtark.pilot.protocol.XProtocolFrame
import com.xtark.pilot.usb.OpenCtrUsbManager
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), VisionAnalyzer.Callback, OpenCtrUsbManager.Listener {
    private lateinit var binding: ActivityMainBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val controlExecutor = Executors.newSingleThreadScheduledExecutor()
    private val barcodeScannerDelegate = lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }
    private val barcodeScanner by barcodeScannerDelegate
    private val personDetectorDelegate = lazy { PersonBodyDetector(this) }
    private val personDetector by personDetectorDelegate
    private val personAutoSelector = PersonAutoSelector()
    private val followController = TargetFollowController()
    private lateinit var usbManager: OpenCtrUsbManager
    @Volatile
    private var targetColor = TargetColor.RED
    @Volatile
    private var trackingMode = TrackingMode.PERSON
    @Volatile
    private var usbConnected = false
    @Volatile
    private var motionArmed = false
    @Volatile
    private var joystickX = 0f
    @Volatile
    private var joystickY = 0f
    @Volatile
    private var joystickActive = false
    @Volatile
    private var turnDirection = 0
    @Volatile
    private var lastControlHeartbeatMs = 0L
    @Volatile
    private var maxLinearMps = 0.25
    @Volatile
    private var desiredFollowCoverage = 0.06
    @Volatile
    private var desiredColorCoverage = 0.06
    @Volatile
    private var desiredPersonBodyWidth = 0.34
    @Volatile
    private var autoFollowActive = false
    @Volatile
    private var latestTarget: TargetResult? = null
    @Volatile
    private var lastVisionFrameMs = 0L
    @Volatile
    private var selectedPersonId: Int? = null
    @Volatile
    private var personLockAutomatic = false
    @Volatile
    private var personAutoAcquireAfterMs = 0L
    @Volatile
    private var selectedPersonLastSeenMs = 0L
    @Volatile
    private var latestPeople: PeopleResult? = null
    @Volatile
    private var lastAcceptedPerson: PersonCandidate? = null
    @Volatile
    private var lastVisionUiUpdateMs = 0L
    private var telemetrySeen = false
    private var suppressMotionSwitch = false
    private var suppressFollowSwitch = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            binding.cameraBadge.text = "VISION  •  缺少相机权限"
            binding.visionStatus.text = getString(R.string.camera_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadControlSettings()
        setupTrackingModeSelector()
        setupColorSelector()
        setupDriveControls()
        usbManager = OpenCtrUsbManager(this, this)
        usbManager.start()
        controlExecutor.scheduleWithFixedDelay(
            ::controlTick,
            0L,
            CONTROL_PERIOD_MS,
            TimeUnit.MILLISECONDS
        )
        binding.connectUsbButton.setOnClickListener { usbManager.connectReadOnly() }
        binding.disconnectUsbButton.setOnClickListener {
            stopAndLock("主动断开，已停车")
            usbManager.disconnect()
            onUsbStatus("已断开 OpenCTR", false)
        }
        binding.scrollToTopButton.setOnClickListener {
            binding.controlScroll.smoothScrollTo(0, 0)
        }
        binding.safetyBanner.setOnClickListener {
            if (motionArmed) stopAndLock("画面急停已触发；重新确认后才能解锁")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupTrackingModeSelector() {
        binding.targetOverlay.setOnPersonSelectedListener(::selectPerson)
        binding.clearPersonSelectionButton.setOnClickListener {
            clearPersonSelection("人物锁定已清除；正在重新自动寻找画面中的人物")
        }
        binding.trackingModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = if (checkedId == R.id.person_mode_button) {
                TrackingMode.PERSON
            } else {
                TrackingMode.COLOR
            }
            if (newMode == trackingMode) return@addOnButtonCheckedListener
            if (autoFollowActive) stopAutoFollow("跟随目标已切换，已停车")
            else stopTransientMotion("切换跟随目标时停车失败，已锁定")
            resetPersonLock()
            latestPeople = null
            if (personDetectorDelegate.isInitialized()) personDetector.reset()
            trackingMode = newMode
            lastVisionUiUpdateMs = 0L
            followController.reset()
            updateTrackingModeUi()
        }
        updateTrackingModeUi()
    }

    private fun updateTrackingModeUi() {
        val isColor = trackingMode == TrackingMode.COLOR
        binding.colorTargetLabel.visibility = if (isColor) View.VISIBLE else View.GONE
        binding.colorToggleGroup.visibility = if (isColor) View.VISIBLE else View.GONE
        binding.clearPersonSelectionButton.visibility = if (isColor) View.GONE else View.VISIBLE
        binding.clearPersonSelectionButton.isEnabled = !isColor && selectedPersonId != null
        binding.followModeSwitch.text = if (isColor) {
            "自动跟随所选颜色"
        } else {
            "先开启，再走到镜头前自动跟随"
        }
        val savedCoverage = if (isColor) desiredColorCoverage else desiredPersonBodyWidth
        desiredFollowCoverage = savedCoverage
        if (isColor) {
            binding.followSizeSlider.valueFrom = COLOR_FOLLOW_SIZE_MIN_PERCENT
            binding.followSizeSlider.valueTo = COLOR_FOLLOW_SIZE_MAX_PERCENT
            binding.followSizeSlider.value = (savedCoverage * 100.0).toFloat()
        } else {
            binding.followSizeSlider.valueTo = PERSON_FOLLOW_SIZE_MAX_PERCENT
            binding.followSizeSlider.value = (savedCoverage * 100.0).toFloat()
            binding.followSizeSlider.valueFrom = PERSON_FOLLOW_SIZE_MIN_PERCENT
        }
        updateFollowSizeLabel()
        binding.visionStatus.text = if (isColor) {
            "未发现${targetColor.displayName}目标"
        } else {
            "正在寻找人物…检测稳定后将自动锁定，也可点击人体框手动换人"
        }
        if (isColor) {
            binding.targetOverlay.clearPeople()
            binding.targetOverlay.updateTarget(
                TargetResult(found = false, trackingMode = trackingMode, targetColor = targetColor)
            )
        } else {
            binding.targetOverlay.updatePeople(PeopleResult(emptyList(), 1, 1), null)
        }
    }

    private fun selectPerson(trackingId: Int) {
        if (trackingMode != TrackingMode.PERSON) return
        val people = latestPeople ?: return
        val candidate = people.candidates.firstOrNull { it.trackingId == trackingId } ?: return
        if (!stopTransientMotion("选择人物时停车失败，已锁定")) return
        acceptPersonLock(candidate, SystemClock.elapsedRealtime(), automatic = false)
        binding.clearPersonSelectionButton.isEnabled = true
        binding.targetOverlay.updatePeople(people, trackingId)
        binding.visionStatus.text = String.format(
            Locale.CHINA,
            "已手动锁定人物 · 人体宽度 %.1f%%",
            candidate.bodyWidthCoverage * 100f
        )
        binding.driveStatus.text = if (autoFollowActive) {
            "已切换锁定人物，自动跟随继续"
        } else {
            "人物已锁定；解锁运动后可开启自动跟随"
        }
    }

    @Synchronized
    private fun acceptPersonLock(candidate: PersonCandidate, now: Long, automatic: Boolean) {
        lastAcceptedPerson = candidate
        selectedPersonLastSeenMs = now
        latestTarget = candidate.toTargetResult()
        lastVisionFrameMs = now
        personLockAutomatic = automatic
        personAutoAcquireAfterMs = 0L
        personAutoSelector.reset()
        selectedPersonId = candidate.trackingId
    }

    @Synchronized
    private fun resetPersonLock() {
        selectedPersonId = null
        personLockAutomatic = false
        personAutoAcquireAfterMs = 0L
        personAutoSelector.reset()
        selectedPersonLastSeenMs = 0L
        lastAcceptedPerson = null
        latestTarget = null
        lastVisionFrameMs = 0L
    }

    private fun clearPersonSelection(reason: String) {
        val continueWaiting = autoFollowActive && trackingMode == TrackingMode.PERSON
        if (!stopTransientMotion("清除人物锁定时停车失败，已锁定")) return
        resetPersonLock()
        if (continueWaiting) {
            personAutoAcquireAfterMs = SystemClock.elapsedRealtime() + PERSON_REACQUIRE_DELAY_MS
        }
        binding.clearPersonSelectionButton.isEnabled = false
        latestPeople?.let { binding.targetOverlay.updatePeople(it, null) }
        if (trackingMode == TrackingMode.PERSON) {
            binding.visionStatus.text = reason
            if (continueWaiting) {
                binding.safetyBanner.text = "人物自动跟随待命 · 点击此处急停"
                binding.driveStatus.text = "已停车，等待自动重新锁定人物"
            }
        }
    }

    private fun setupColorSelector() {
        binding.colorToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            targetColor = when (checkedId) {
                R.id.green_button -> TargetColor.GREEN
                R.id.blue_button -> TargetColor.BLUE
                else -> TargetColor.RED
            }
            latestTarget = null
            lastVisionFrameMs = 0L
            stopTransientMotion("切换颜色时停车失败，已锁定")
            if (trackingMode == TrackingMode.COLOR && autoFollowActive && motionArmed) {
                binding.driveStatus.text = "颜色已切换，等待新目标"
            }
        }
    }

    private fun setupDriveControls() {
        binding.driveJoystick.listener = DriveJoystickView.Listener { linearX, linearY, active ->
            joystickX = linearX
            joystickY = linearY
            joystickActive = active
            markControlHeartbeat()
            updateRequestedMotionText()
        }

        // ROS yaw convention: positive angular Z turns counter-clockwise (left).
        setupHoldToTurn(binding.turnLeftButton, 1)
        setupHoldToTurn(binding.turnRightButton, -1)

        binding.speedSlider.value = (maxLinearMps * 100.0).toFloat()
        updateSpeedLabel()
        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            maxLinearMps = value.toDouble() / 100.0
            updateSpeedLabel()
            if (fromUser) saveControlSettings()
        }

        binding.followSizeSlider.addOnChangeListener { _, value, fromUser ->
            desiredFollowCoverage = value.toDouble() / 100.0
            if (trackingMode == TrackingMode.PERSON) {
                desiredPersonBodyWidth = desiredFollowCoverage
            } else {
                desiredColorCoverage = desiredFollowCoverage
            }
            updateFollowSizeLabel()
            if (fromUser) saveControlSettings()
        }

        binding.followModeSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressFollowSwitch) return@setOnCheckedChangeListener
            if (checked) {
                setFollowSwitchChecked(false)
                showFollowConfirmation()
            } else if (autoFollowActive) {
                stopAutoFollow("自动跟随已关闭，已停车")
            }
        }

        binding.motionSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressMotionSwitch) return@setOnCheckedChangeListener
            if (checked) {
                setMotionSwitchChecked(false)
                showArmConfirmation()
            } else if (motionArmed) {
                stopAndLock("运动已锁定，已停车")
            }
        }

        binding.emergencyStopButton.setOnClickListener {
            stopAndLock("急停已触发；重新确认后才能解锁")
        }
    }

    private fun loadControlSettings() {
        val settings = getSharedPreferences(CONTROL_SETTINGS, MODE_PRIVATE)
        val settingsVersion = settings.getInt(KEY_SETTINGS_VERSION, 0)
        val storedLinearMps = settings.getFloat(
            KEY_MAX_LINEAR_MPS,
            DEFAULT_LINEAR_MPS.toFloat()
        ).toDouble()
        maxLinearMps = if (
            settingsVersion < CURRENT_SETTINGS_VERSION &&
            storedLinearMps in 0.149..0.151
        ) {
            DEFAULT_LINEAR_MPS
        } else {
            storedLinearMps
        }
            .toDouble()
            .coerceIn(0.05, 0.40)
        desiredPersonBodyWidth = settings.getFloat(
            KEY_PERSON_BODY_WIDTH,
            DEFAULT_PERSON_BODY_WIDTH.toFloat()
        ).toDouble().coerceIn(
            PERSON_FOLLOW_SIZE_MIN_PERCENT / 100.0,
            PERSON_FOLLOW_SIZE_MAX_PERCENT / 100.0
        )
        desiredColorCoverage = settings.getFloat(
            KEY_COLOR_COVERAGE,
            DEFAULT_COLOR_COVERAGE.toFloat()
        ).toDouble().coerceIn(
            COLOR_FOLLOW_SIZE_MIN_PERCENT / 100.0,
            COLOR_FOLLOW_SIZE_MAX_PERCENT / 100.0
        )
        if (settingsVersion < CURRENT_SETTINGS_VERSION) saveControlSettings()
    }

    private fun saveControlSettings() {
        getSharedPreferences(CONTROL_SETTINGS, MODE_PRIVATE).edit()
            .putFloat(KEY_MAX_LINEAR_MPS, maxLinearMps.toFloat())
            .putFloat(KEY_PERSON_BODY_WIDTH, desiredPersonBodyWidth.toFloat())
            .putFloat(KEY_COLOR_COVERAGE, desiredColorCoverage.toFloat())
            .putInt(KEY_SETTINGS_VERSION, CURRENT_SETTINGS_VERSION)
            .apply()
    }

    private fun updateSpeedLabel() {
        binding.speedLabel.text = String.format(
            Locale.CHINA,
            "速度上限：%.2f m/s（自动跟随最高 0.28 m/s）",
            maxLinearMps
        )
    }

    private fun updateFollowSizeLabel() {
        binding.followSizeLabel.text = String.format(
            Locale.CHINA,
            if (trackingMode == TrackingMode.PERSON) {
                "跟随距离：人体宽 %.0f%%（数值越大，跟得越近）"
            } else {
                "停车目标大小：画面占比 %.0f%%"
            },
            desiredFollowCoverage * 100.0
        )
    }

    private fun setupHoldToTurn(button: View, direction: Int) {
        val heartbeat = object : Runnable {
            override fun run() {
                if (!button.isPressed || turnDirection != direction) return
                markControlHeartbeat()
                button.postDelayed(this, CONTROL_PERIOD_MS)
            }
        }
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    turnDirection = direction
                    view.removeCallbacks(heartbeat)
                    markControlHeartbeat()
                    updateRequestedMotionText()
                    view.postDelayed(heartbeat, CONTROL_PERIOD_MS)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.removeCallbacks(heartbeat)
                    if (turnDirection == direction) turnDirection = 0
                    markControlHeartbeat()
                    updateRequestedMotionText()
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                }
            }
            true
        }
    }

    private fun showArmConfirmation() {
        if (!usbConnected || !telemetrySeen) {
            Toast.makeText(this, "请先连接 OpenCTR 并等待有效状态帧", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("解锁 R20-MEC 运动？")
            .setMessage(
                "首次测试请架空四个轮子，并确保实体电源开关随手可及。" +
                    "解锁后只有持续按住摇杆或转向键才运动，松手立即发送停车帧。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("确认解锁") { _, _ -> armMotion() }
            .show()
    }

    private fun showFollowConfirmation() {
        if (!motionArmed) {
            Toast.makeText(this, "请先解锁运动", Toast.LENGTH_SHORT).show()
            return
        }
        val targetName = if (trackingMode == TrackingMode.PERSON) {
            "人物自动"
        } else {
            targetColor.displayName
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("启动${targetName}跟随？")
            .setMessage(
                if (trackingMode == TrackingMode.PERSON) {
                    String.format(
                        Locale.CHINA,
                        "确认后小车先保持停车 3 秒，你可以放下手机并走到镜头前。" +
                            "系统看到稳定人体后自动锁定并开始跟随，无需点击屏幕。" +
                            "当前跟随距离为人体宽 %.0f%%（数值越大越近）；" +
                            "人体丢失会立即停车，持续丢失后自动等待重新锁定。",
                        desiredPersonBodyWidth * 100.0
                    )
                } else {
                    "颜色可能受光线和背景影响而误判。首次测试请架空车轮；" +
                        "目标丢失超过 300 ms 会停车，点击画面底部状态条可随时急停。"
                }
            )
            .setNegativeButton("取消", null)
            .setPositiveButton(
                if (trackingMode == TrackingMode.PERSON) "启动并等待" else "开始跟随"
            ) { _, _ -> startAutoFollow() }
            .show()
    }

    private fun startAutoFollow() {
        if (!motionArmed) return
        resetControlInput()
        if (trackingMode == TrackingMode.PERSON) {
            resetPersonLock()
            personAutoAcquireAfterMs = SystemClock.elapsedRealtime() + PERSON_WALK_AWAY_DELAY_MS
            if (personDetectorDelegate.isInitialized()) personDetector.reset()
            binding.clearPersonSelectionButton.isEnabled = false
            latestPeople?.let { binding.targetOverlay.updatePeople(it, null) }
        } else {
            latestTarget = null
            lastVisionFrameMs = 0L
        }
        autoFollowActive = true
        setFollowSwitchChecked(true)
        setDriveControlsEnabled(true)
        if (trackingMode == TrackingMode.PERSON) {
            binding.safetyBanner.text = "人物自动跟随待命 · 点击此处急停"
            binding.driveStatus.text = "保持停车 3 秒；请放下手机并走到镜头前"
        } else {
            binding.safetyBanner.text = "${targetColor.displayName}跟随中 · 点击此处急停"
            binding.driveStatus.text = "等待${targetColor.displayName}目标；未发现时保持停车"
        }
    }

    private fun stopAutoFollow(reason: String) {
        autoFollowActive = false
        setFollowSwitchChecked(false)
        latestTarget = null
        lastVisionFrameMs = 0L
        resetControlInput()
        if (motionArmed && !usbManager.sendVelocity(0.0, 0.0, 0.0)) {
            stopAndLock("自动跟随停车失败，已锁定")
            return
        }
        setDriveControlsEnabled(motionArmed)
        if (motionArmed) binding.safetyBanner.text = "手动已解锁 · 点击此处急停"
        binding.driveStatus.text = reason
    }

    private fun armMotion() {
        val error = usbManager.armMotion()
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            binding.driveStatus.text = error
            return
        }
        motionArmed = true
        autoFollowActive = false
        resetControlInput()
        setMotionSwitchChecked(true)
        setDriveControlsEnabled(true)
        binding.safetyBanner.text = "手动已解锁 · 点击此处急停"
        binding.driveStatus.text = "已解锁，等待持续触摸输入"
    }

    private fun stopAndLock(reason: String) {
        motionArmed = false
        autoFollowActive = false
        setFollowSwitchChecked(false)
        latestTarget = null
        lastVisionFrameMs = 0L
        resetControlInput()
        if (::usbManager.isInitialized) usbManager.emergencyStop()
        setMotionSwitchChecked(false)
        setDriveControlsEnabled(false)
        binding.safetyBanner.text = "运动锁定 · 已发送停车"
        binding.driveStatus.text = reason
    }

    private fun resetControlInput() {
        joystickX = 0f
        joystickY = 0f
        joystickActive = false
        turnDirection = 0
        lastControlHeartbeatMs = 0L
        followController.reset()
        if (::binding.isInitialized) binding.driveJoystick.reset()
    }

    private fun stopTransientMotion(lockReason: String): Boolean {
        resetControlInput()
        if (!motionArmed || !::usbManager.isInitialized) return true
        if (usbManager.sendVelocity(0.0, 0.0, 0.0)) return true
        stopAndLock(lockReason)
        return false
    }

    private fun setMotionSwitchChecked(checked: Boolean) {
        suppressMotionSwitch = true
        binding.motionSwitch.isChecked = checked
        suppressMotionSwitch = false
    }

    private fun setFollowSwitchChecked(checked: Boolean) {
        suppressFollowSwitch = true
        binding.followModeSwitch.isChecked = checked
        suppressFollowSwitch = false
    }

    private fun setDriveControlsEnabled(enabled: Boolean) {
        binding.followModeSwitch.isEnabled = enabled
        val manualEnabled = enabled && !autoFollowActive
        binding.driveJoystick.isEnabled = manualEnabled
        binding.turnLeftButton.isEnabled = manualEnabled
        binding.turnRightButton.isEnabled = manualEnabled
    }

    private fun markControlHeartbeat() {
        lastControlHeartbeatMs = SystemClock.elapsedRealtime()
    }

    @Synchronized
    private fun shouldUpdateVisionUi(now: Long): Boolean {
        if (now - lastVisionUiUpdateMs < VISION_UI_INTERVAL_MS) return false
        lastVisionUiUpdateMs = now
        return true
    }

    private fun controlTick() {
        if (!motionArmed || !usbConnected || !::usbManager.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        val setpoint = if (autoFollowActive) {
            val target = latestTarget
            followController.evaluate(
                targetFound = target?.found == true &&
                    target.trackingMode == trackingMode &&
                    if (trackingMode == TrackingMode.PERSON) {
                        target.personTrackingId == selectedPersonId
                    } else {
                        target.targetColor == targetColor
                    },
                frameAgeMs = now - lastVisionFrameMs,
                centerX = target?.centerX ?: 0.5f,
                coverage = target?.coverage ?: 0f,
                desiredCoverage = desiredFollowCoverage,
                userLinearLimitMps = maxLinearMps,
                allowReverse = trackingMode == TrackingMode.COLOR
            )
        } else {
            val touchActive = joystickActive || turnDirection != 0
            MotionSafetyGate.evaluate(
                armed = motionArmed,
                connected = usbConnected,
                inputActive = touchActive,
                heartbeatAgeMs = now - lastControlHeartbeatMs,
                normalizedX = joystickX,
                normalizedY = joystickY,
                turnDirection = turnDirection,
                maxLinearMps = maxLinearMps,
                angularRadS = DEFAULT_ANGULAR_RAD_S
            )
        }
        if (!usbManager.sendVelocity(
                setpoint.linearX,
                setpoint.linearY,
                setpoint.angularZ
            )
        ) {
            motionArmed = false
            runOnUiThread { stopAndLock("运动发送失败，已锁定") }
        }
    }

    private fun updateRequestedMotionText() {
        if (!motionArmed) return
        val x = joystickX * maxLinearMps
        val y = joystickY * maxLinearMps
        val w = turnDirection * DEFAULT_ANGULAR_RAD_S
        binding.driveStatus.text = String.format(
            Locale.CHINA,
            "请求速度  X %+.2f  Y %+.2f m/s  W %+.2f rad/s",
            x,
            y,
            w
        )
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                // The activity is deliberately fixed to landscape for the robot dashboard.
                // Using Display.rotation here is unreliable when USB launches the app on a
                // locked phone because Android temporarily reports ROTATION_0.
                val targetRotation = Surface.ROTATION_90
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
                val preview = Preview.Builder()
                    .setTargetRotation(targetRotation)
                    .setResolutionSelector(resolutionSelector)
                    .build().also {
                        it.surfaceProvider = binding.cameraPreview.surfaceProvider
                    }
                val analyzer = ImageAnalysis.Builder()
                    .setTargetRotation(targetRotation)
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(
                            cameraExecutor,
                            VisionAnalyzer(
                                barcodeScanner,
                                personDetector,
                                { trackingMode },
                                { targetColor },
                                { selectedPersonId },
                                this
                            )
                        )
                    }
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer
                )
                binding.cameraBadge.text = "VISION  •  640×480  •  只读"
            } catch (error: Exception) {
                binding.cameraBadge.text = "VISION  •  相机启动失败"
                binding.visionStatus.text = error.message ?: "无法打开后置相机"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onTarget(result: TargetResult) {
        if (trackingMode != TrackingMode.COLOR || result.trackingMode != TrackingMode.COLOR) return
        if (result.targetColor != targetColor) return
        latestTarget = result
        val now = SystemClock.elapsedRealtime()
        lastVisionFrameMs = now
        if (!shouldUpdateVisionUi(now)) return
        runOnUiThread {
            if (trackingMode != TrackingMode.COLOR || result.targetColor != targetColor) {
                return@runOnUiThread
            }
            binding.cameraBadge.text = "VISION  •  640×480  •  颜色"
            binding.targetOverlay.updateTarget(result)
            val targetName = result.targetColor.displayName
            binding.visionStatus.text = if (result.found) {
                val horizontal = when {
                    result.centerX < 0.42f -> "左侧"
                    result.centerX > 0.58f -> "右侧"
                    else -> "中间"
                }
                String.format(
                    Locale.CHINA,
                    "%s目标：%s  ·  画面占比 %.1f%%",
                    targetName,
                    horizontal,
                    result.coverage * 100f
                )
            } else {
                "未发现${targetName}目标"
            }
            if (autoFollowActive) {
                binding.driveStatus.text = if (result.found) {
                    String.format(
                        Locale.CHINA,
                        "正在跟随%s：中心 %.2f，占比 %.1f%%",
                        targetName,
                        result.centerX,
                        result.coverage * 100f
                    )
                } else {
                    "目标丢失，已停车并等待${targetName}目标"
                }
            }
        }
    }

    override fun onPeople(result: PeopleResult) {
        if (trackingMode != TrackingMode.PERSON) return
        val now = SystemClock.elapsedRealtime()
        latestPeople = result
        var selectedId = selectedPersonId
        var automaticallyLocked = false
        val waitingForWalkAway = selectedId == null && now < personAutoAcquireAfterMs
        if (selectedId == null && !waitingForWalkAway) {
            personAutoSelector.observe(result.candidates)?.let { candidate ->
                acceptPersonLock(candidate, now, automatic = true)
                selectedId = candidate.trackingId
                automaticallyLocked = true
            }
        } else {
            personAutoSelector.reset()
        }
        val observed = result.candidates.firstOrNull { it.trackingId == selectedId }
        val selected = observed?.takeIf {
            PersonTargetContinuity.isPlausible(lastAcceptedPerson, it)
        }
        latestTarget = selected?.toTargetResult() ?: TargetResult(
            found = false,
            trackingMode = TrackingMode.PERSON,
            personTrackingId = selectedId,
            frameWidth = result.frameWidth,
            frameHeight = result.frameHeight
        )
        lastVisionFrameMs = now
        if (selected != null) {
            lastAcceptedPerson = selected
            selectedPersonLastSeenMs = now
        }

        if (!shouldUpdateVisionUi(now)) return

        runOnUiThread {
            if (trackingMode != TrackingMode.PERSON) return@runOnUiThread
            binding.cameraBadge.text = "VISION  •  640×480  •  人物"
            val currentSelectedId = selectedPersonId
            val currentSelected = selected.takeIf { currentSelectedId == selectedId }
            val positionRejected = observed != null && selected == null &&
                currentSelectedId == selectedId
            if (currentSelectedId != null && currentSelected == null &&
                now - selectedPersonLastSeenMs > PERSON_LOCK_EXPIRY_MS
            ) {
                clearPersonSelection("锁定人物持续丢失，已停车；正在重新自动寻找人物")
                return@runOnUiThread
            }

            binding.targetOverlay.updatePeople(
                result,
                if (currentSelected != null) currentSelectedId else null
            )
            binding.visionStatus.text = when {
                currentSelected != null -> String.format(
                    Locale.CHINA,
                    "%s锁定人物 · 人体宽 %.1f%% · %s",
                    if (personLockAutomatic || automaticallyLocked) "已自动" else "已手动",
                    currentSelected.bodyWidthCoverage * 100f,
                    when {
                        currentSelected.centerX < 0.42f -> "左侧"
                        currentSelected.centerX > 0.58f -> "右侧"
                        else -> "中间"
                    }
                )
                positionRejected -> "人物位置突变，已拒绝切换并停车；保持原人物锁定"
                currentSelectedId != null -> "锁定人物暂时丢失 · 已停车等待，超过 2 秒将自动重选"
                waitingForWalkAway -> String.format(
                    Locale.CHINA,
                    "自动跟随待命 · %.1f 秒后开始寻找人物，请走到镜头前",
                    (personAutoAcquireAfterMs - now).coerceAtLeast(0L) / 1000f
                )
                result.candidates.isEmpty() -> "未发现完整人体；请让目标进入画面并靠近一些"
                else -> "发现 ${result.candidates.size} 人 · 正在确认自动锁定，也可点击人体框选择"
            }
            if (autoFollowActive) {
                binding.driveStatus.text = if (currentSelected != null) {
                    val widthPercent = currentSelected.bodyWidthCoverage * 100f
                    val desiredPercent = desiredPersonBodyWidth * 100f
                    when {
                        currentSelected.centerX < 0.36f -> String.format(
                            Locale.CHINA,
                            "人物在左侧，正在校正方向 · 距离 %.1f / %.0f%%",
                            widthPercent,
                            desiredPercent
                        )
                        currentSelected.centerX > 0.64f -> String.format(
                            Locale.CHINA,
                            "人物在右侧，正在校正方向 · 距离 %.1f / %.0f%%",
                            widthPercent,
                            desiredPercent
                        )
                        currentSelected.bodyWidthCoverage < desiredPersonBodyWidth * 0.96 ->
                            String.format(
                                Locale.CHINA,
                                "人物已锁定，正在靠近 · 距离 %.1f / %.0f%%",
                                widthPercent,
                                desiredPercent
                            )
                        else -> String.format(
                            Locale.CHINA,
                            "已到达跟随距离并停车 · 距离 %.1f / %.0f%%",
                            widthPercent,
                            desiredPercent
                        )
                    }
                } else {
                    "锁定人物丢失，已立即停车"
                }
            }
        }
    }

    override fun onQrCode(value: String) {
        runOnUiThread { binding.qrText.text = value }
    }

    override fun onAnalyzerError(message: String) {
        runOnUiThread { binding.cameraBadge.text = "VISION  •  $message" }
    }

    override fun onUsbStatus(message: String, connected: Boolean) {
        runOnUiThread {
            usbConnected = connected
            if (!connected) {
                telemetrySeen = false
                if (motionArmed) stopAndLock("连接中断，已锁定")
            }
            binding.usbStatus.text = message
            binding.connectUsbButton.isEnabled = !connected
            binding.disconnectUsbButton.isEnabled = connected
            binding.emergencyStopButton.isEnabled = connected
            binding.motionSwitch.isEnabled = connected && (telemetrySeen || motionArmed)
            if (!connected) binding.followModeSwitch.isEnabled = false
            if (!connected) binding.telemetryStatus.text = "状态帧：0"
        }
    }

    override fun onFrame(frame: XProtocolFrame, validCount: Long, checksumErrors: Long) {
        runOnUiThread {
            if (frame.isStatusFrame) {
                telemetrySeen = true
                if (!motionArmed) binding.motionSwitch.isEnabled = usbConnected
            }
            val voltage = frame.batteryVoltage?.let {
                String.format(Locale.CHINA, "\n电池电压：%.2f V", it)
            }.orEmpty()
            val velocity = if (
                frame.linearX != null && frame.linearY != null && frame.angularZ != null
            ) {
                String.format(
                    Locale.CHINA,
                    "\n反馈速度：X %+.3f  Y %+.3f m/s  W %+.3f rad/s",
                    frame.linearX,
                    frame.linearY,
                    frame.angularZ
                )
            } else {
                ""
            }
            binding.telemetryStatus.text =
                "有效协议帧：$validCount\n" +
                    "帧码：0x%02X  ·  长度：%d$voltage$velocity\n".format(
                        frame.code,
                        frame.length
                    ) +
                    "校验错误：$checksumErrors\n" +
                    frame.preview()
        }
    }

    override fun onPause() {
        if (::binding.isInitialized && motionArmed) {
            stopAndLock("应用进入后台，已停车并锁定")
        }
        super.onPause()
    }

    override fun onDestroy() {
        motionArmed = false
        controlExecutor.shutdownNow()
        if (::usbManager.isInitialized) {
            usbManager.emergencyStop()
            usbManager.close()
        }
        if (barcodeScannerDelegate.isInitialized()) barcodeScanner.close()
        if (personDetectorDelegate.isInitialized()) personDetector.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CONTROL_PERIOD_MS = 50L
        private const val DEFAULT_ANGULAR_RAD_S = 0.50
        private const val PERSON_LOCK_EXPIRY_MS = 2_000L
        private const val PERSON_WALK_AWAY_DELAY_MS = 3_000L
        private const val PERSON_REACQUIRE_DELAY_MS = 500L
        private const val CONTROL_SETTINGS = "control_settings"
        private const val KEY_MAX_LINEAR_MPS = "max_linear_mps"
        private const val KEY_PERSON_BODY_WIDTH = "person_body_width"
        private const val KEY_COLOR_COVERAGE = "color_coverage"
        private const val KEY_SETTINGS_VERSION = "settings_version"
        private const val CURRENT_SETTINGS_VERSION = 2
        private const val DEFAULT_LINEAR_MPS = 0.25
        private const val DEFAULT_PERSON_BODY_WIDTH = 0.34
        private const val DEFAULT_COLOR_COVERAGE = 0.06
        private const val PERSON_FOLLOW_SIZE_MIN_PERCENT = 20f
        private const val PERSON_FOLLOW_SIZE_MAX_PERCENT = 45f
        private const val COLOR_FOLLOW_SIZE_MIN_PERCENT = 1f
        private const val COLOR_FOLLOW_SIZE_MAX_PERCENT = 35f
        private const val VISION_UI_INTERVAL_MS = 80L
    }
}
