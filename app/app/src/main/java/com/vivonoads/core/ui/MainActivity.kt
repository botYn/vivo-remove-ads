package com.vivonoads.core.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.vivonoads.core.R
import com.vivonoads.core.log.AdLog
import com.vivonoads.core.rule.BuiltinRules
import com.vivonoads.core.rule.RuleRepository
import com.vivonoads.core.service.AdBlockAccessibilityService
import com.vivonoads.core.service.ForegroundKeeper

/**
 * 主界面：服务状态、核心开关、工具入口。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var repo: RuleRepository

    private lateinit var statusText: TextView
    private lateinit var statBlocked: TextView
    private lateinit var statRules: TextView
    private lateinit var statDevice: TextView

    private var serviceRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            serviceRunning = intent?.getBooleanExtra(
                AdBlockAccessibilityService.EXTRA_CONNECTED, false,
            ) ?: false
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = RuleRepository.get(this)

        statusText = findViewById(R.id.statusText)
        statBlocked = findViewById(R.id.statBlocked)
        statRules = findViewById(R.id.statRules)
        statDevice = findViewById(R.id.statDevice)

        bindServiceToggle()
        bindSwitches()
        bindTools()
        bindStats()

        registerReceiver(
            statusReceiver,
            IntentFilter(AdBlockAccessibilityService.ACTION_STATUS_CHANGED),
        )
    }

    override fun onResume() {
        super.onResume()
        serviceRunning = isAccessibilityEnabled()
        render()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun bindServiceToggle() {
        findViewById<View>(R.id.btnEnableService).setOnClickListener {
            if (serviceRunning) {
                openAccessibilitySettings()
            } else {
                openAccessibilitySettings()
            }
        }
    }

    private fun bindSwitches() {
        findViewById<SwitchCompat>(R.id.switchGlobal).apply {
            isChecked = repo.isGlobalEnabled()
            setOnCheckedChangeListener { _, checked ->
                repo.setGlobalEnabled(checked)
                AdLog.i("Main", "总开关: $checked")
                if (checked) startKeeper() else stopKeeper()
            }
        }

        findViewById<SwitchCompat>(R.id.switchBuiltin).apply {
            isChecked = repo.prefsBuiltinEnabled()
            setOnCheckedChangeListener { _, checked ->
                repo.setBuiltinEnabled(checked)
            }
        }    }

    private fun bindTools() {
        findViewById<View>(R.id.btnRules).setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
        findViewById<View>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<View>(R.id.btnBattery).setOnClickListener {
            openBatterySettings()
        }
    }

    private fun bindStats() {
        statDevice.text = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
    }

    private fun render() {
        statusText.text = if (serviceRunning) {
            getString(R.string.status_on)
        } else {
            getString(R.string.status_off)
        }
        statusText.setTextColor(
            getColor(if (serviceRunning) R.color.status_ok else R.color.status_off),
        )

        findViewById<TextView>(R.id.btnEnableService).text = getString(
            if (serviceRunning) R.string.disable_service else R.string.enable_service,
        )

        statRules.text = repo.activeRules().size.toString()
        statBlocked.text = AdLog.snapshot().count {
            it.message.startsWith("[") || it.message.contains("拦截") ||
                it.message.contains("点击关闭") || it.message.contains("隐藏")
        }.toString()
    }

    /**
     * 检查本应用的无障碍服务是否已开启。
     */
    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${AdBlockAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
    }

    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }.onFailure {
                startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun startKeeper() {
        runCatching {
            startForegroundService(Intent(this, ForegroundKeeper::class.java))
        }
    }

    private fun stopKeeper() {
        runCatching {
            stopService(Intent(this, ForegroundKeeper::class.java))
        }
    }
}
