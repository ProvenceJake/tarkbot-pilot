package com.xtark.pilot.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.xtark.pilot.protocol.XProtocolDecoder
import com.xtark.pilot.protocol.XProtocolEncoder
import com.xtark.pilot.protocol.XProtocolFrame

class OpenCtrUsbManager(
    context: Context,
    private val listener: Listener
) : SerialInputOutputManager.Listener {
    interface Listener {
        fun onUsbStatus(message: String, connected: Boolean)
        fun onFrame(frame: XProtocolFrame, validCount: Long, checksumErrors: Long)
    }

    companion object {
        const val OPENCTR_VENDOR_ID = 0x1A86
        const val OPENCTR_PRODUCT_ID = 0x55D4
        const val BAUD_RATE = 230400
        private const val TELEMETRY_MAX_AGE_MS = 1_000L
        private const val WRITE_TIMEOUT_MS = 100
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${appContext.packageName}.USB_PERMISSION"
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var permissionReceiverRegistered = false
    private var deviceReceiverRegistered = false
    private var lastUiUpdateMs = 0L
    private var statusFrameDeliveredToUi = false
    @Volatile
    private var lastValidFrameMs = 0L
    @Volatile
    private var motionArmed = false
    private val decoder = XProtocolDecoder(::handleDecodedFrame)

    private fun handleDecodedFrame(frame: XProtocolFrame) {
        val now = SystemClock.elapsedRealtime()
        if (frame.isStatusFrame) lastValidFrameMs = now
        val firstStatusFrame = frame.isStatusFrame && !statusFrameDeliveredToUi
        if (firstStatusFrame || now - lastUiUpdateMs >= 200) {
            lastUiUpdateMs = now
            if (frame.isStatusFrame) statusFrameDeliveredToUi = true
            listener.onFrame(frame, decoder.validFrameCount, decoder.checksumErrorCount)
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val device = intent.usbDevice() ?: return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                openReadOnly(device)
            } else {
                listener.onUsbStatus("USB 权限被拒绝", false)
            }
        }
    }

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (!isController(device)) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    disconnect()
                    listener.onUsbStatus("OpenCTR 已拔出，运动已锁定", false)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    listener.onUsbStatus("已发现 OpenCTR，点击连接", false)
            }
        }
    }

    fun start() {
        if (!permissionReceiverRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                permissionReceiver,
                IntentFilter(permissionAction),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            permissionReceiverRegistered = true
        }
        if (!deviceReceiverRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                deviceReceiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
            deviceReceiverRegistered = true
        }
        val device = findController()
        listener.onUsbStatus(
            if (device == null) "未发现 OpenCTR（1A86:55D4）" else "已发现 OpenCTR，点击连接",
            false
        )
    }

    fun connectReadOnly() {
        val device = findController()
        if (device == null) {
            listener.onUsbStatus("未发现 OpenCTR（1A86:55D4）", false)
            return
        }
        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(permissionAction).setPackage(appContext.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, permissionIntent)
            listener.onUsbStatus("等待 USB 权限…", false)
        } else {
            openReadOnly(device)
        }
    }

    private fun openReadOnly(device: UsbDevice) {
        disconnect()
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null || driver.ports.isEmpty()) {
            listener.onUsbStatus("无法识别 CH9102F 串口接口", false)
            return
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            listener.onUsbStatus("无法打开 USB 设备", false)
            return
        }
        try {
            val port = driver.ports.first()
            port.open(connection)
            port.setParameters(
                BAUD_RATE,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            serialPort = port
            decoder.reset()
            lastUiUpdateMs = 0L
            statusFrameDeliveredToUi = false
            lastValidFrameMs = 0L
            motionArmed = false
            ioManager = SerialInputOutputManager(port, this).also { it.start() }
            listener.onUsbStatus(
                "已连接\n1A86:55D4 · 230400 8N1 · 运动默认锁定",
                true
            )
        } catch (error: Exception) {
            connection.close()
            serialPort = null
            listener.onUsbStatus("连接失败：${error.message}", false)
        }
    }

    @Synchronized
    fun armMotion(): String? {
        if (ioManager == null) return "串口未连接"
        val port = serialPort ?: return "串口未连接"
        val telemetryAge = SystemClock.elapsedRealtime() - lastValidFrameMs
        if (lastValidFrameMs == 0L || telemetryAge > TELEMETRY_MAX_AGE_MS) {
            return "没有收到新鲜的 OpenCTR 状态帧，拒绝解锁"
        }
        return try {
            // The vendor ROS driver announces model 1 before sending R20-MEC velocity commands.
            port.write(XProtocolEncoder.r20MecTypeFrame(), WRITE_TIMEOUT_MS)
            repeat(3) { port.write(XProtocolEncoder.STOP_FRAME, WRITE_TIMEOUT_MS) }
            motionArmed = true
            null
        } catch (error: Exception) {
            motionArmed = false
            "运动解锁失败：${error.message}"
        }
    }

    @Synchronized
    fun sendVelocity(linearX: Double, linearY: Double, angularZ: Double): Boolean {
        if (!motionArmed) return false
        val port = serialPort ?: return false
        if (SystemClock.elapsedRealtime() - lastValidFrameMs > TELEMETRY_MAX_AGE_MS) {
            motionArmed = false
            queueStopFrames()
            return false
        }
        return try {
            port.write(
                XProtocolEncoder.velocityFrame(linearX, linearY, angularZ),
                WRITE_TIMEOUT_MS
            )
            true
        } catch (_: Exception) {
            motionArmed = false
            false
        }
    }

    @Synchronized
    fun emergencyStop() {
        motionArmed = false
        queueStopFrames()
    }

    @Synchronized
    fun disconnect() {
        motionArmed = false
        val port = serialPort
        ioManager?.stop()
        ioManager = null
        try {
            if (port?.isOpen == true) {
                repeat(2) { port.write(XProtocolEncoder.STOP_FRAME, 100) }
            }
        } catch (_: Exception) {
        }
        try {
            port?.close()
        } catch (_: Exception) {
        }
        serialPort = null
        lastValidFrameMs = 0L
    }

    fun close() {
        disconnect()
        if (permissionReceiverRegistered) {
            appContext.unregisterReceiver(permissionReceiver)
            permissionReceiverRegistered = false
        }
        if (deviceReceiverRegistered) {
            appContext.unregisterReceiver(deviceReceiver)
            deviceReceiverRegistered = false
        }
    }

    override fun onNewData(data: ByteArray) {
        decoder.feed(data)
    }

    override fun onRunError(error: Exception) {
        val hadConnection = synchronized(this) {
            ioManager != null || serialPort != null
        }
        disconnect()
        // stop() can race with this callback during an intentional disconnect or detach.
        // Preserve the deliberate disconnect message instead of reporting a false I/O fault.
        if (hadConnection) {
            listener.onUsbStatus("串口读取中断：${error.message}", false)
        }
    }

    @Synchronized
    private fun queueStopFrames() {
        val port = serialPort ?: return
        try {
            repeat(3) { port.write(XProtocolEncoder.STOP_FRAME, WRITE_TIMEOUT_MS) }
        } catch (_: Exception) {
        }
    }

    private fun findController(): UsbDevice? = usbManager.deviceList.values.firstOrNull(::isController)

    private fun isController(device: UsbDevice): Boolean =
        device.vendorId == OPENCTR_VENDOR_ID && device.productId == OPENCTR_PRODUCT_ID

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

}
