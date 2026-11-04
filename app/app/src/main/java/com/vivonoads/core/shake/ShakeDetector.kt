package com.vivonoads.core.shake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 摇一摇检测器。
 *
 * 通过加速度传感器识别摇动动作。当检测到摇动时回调 [onShake]。
 * 若调用 [swallow] 标记为"吞掉"，则本次摇动不再触发任何界面动作。
 */
class ShakeDetector(
    context: Context,
    private val onShake: (ShakeInfo) -> Unit,
) : SensorEventListener {

    /**
     * 摇动信息。
     */
    data class ShakeInfo(
        /** 摇动强度（g） */
        val strength: Float,
        /** 触发时刻 */
        val timestamp: Long,
    )

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 是否吞掉当前这次摇动 */
    @Volatile
    private var swallowed = false

    /** 上一次摇动的时间戳，用于去抖 */
    private var lastShakeTime = 0L

    private var started = false

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        if (event.values.size < 3) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // 总加速度（含重力）
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        // 阈值：偏离 1g 超过 2.3g 视为摇动
        if (gForce > SHAKE_THRESHOLD_G) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime < COOLDOWN_MS) return
            lastShakeTime = now

            val info = ShakeInfo(strength = gForce, timestamp = now)
            if (swallowed) {
                swallowed = false
            } else {
                onShake(info)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * 开始监听。
     */
    fun start() {
        if (started) return
        val sm = sensorManager ?: return
        val sensor = accelerometer ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        started = true
    }

    /**
     * 停止监听。
     */
    fun stop() {
        if (!started) return
        sensorManager?.unregisterListener(this)
        started = false
    }

    /**
     * 吞掉下一次摇动事件。
     */
    fun swallow() {
        swallowed = true
    }

    companion object {
        /** 摇动阈值，单位 g */
        private const val SHAKE_THRESHOLD_G = 2.3f

        /** 两次摇动的最小间隔 */
        private const val COOLDOWN_MS = 1200L
    }
}
