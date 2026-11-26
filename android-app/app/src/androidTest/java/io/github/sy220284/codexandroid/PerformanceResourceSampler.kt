package io.github.sy220284.codexandroid

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Actual process and whole-device battery readings; unsupported properties remain null.
 * The sampler uses a separate worker, not the UI frame callback. */
internal class PerformanceResourceSampler(private val context: Context, private val stage: () -> String) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val thread = HandlerThread("performance-resource-samples")
    private val samples = ArrayList<JSONObject>()
    @Volatile private var running = false
    private var handler: Handler? = null
    private fun longProperty(property: Int): Long? =
        battery?.getLongProperty(property)?.takeUnless { it == Long.MIN_VALUE || it < 0 }

    private val collect = object : Runnable {
        override fun run() {
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            val power = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val current = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.takeUnless { it == Long.MIN_VALUE }
            val row = JSONObject().put("stage", stage())
                .put("elapsed_ms", SystemClock.elapsedRealtime()).put("process_cpu_ms", Process.getElapsedCpuTime())
                .put("pss_kib", memory.totalPss).put("native_pss_kib", memory.nativePss)
                .put("managed_pss_kib", memory.dalvikPss)
                .put("energy_nwh", longProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) ?: JSONObject.NULL)
                .put("charge_uah", longProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: JSONObject.NULL)
                .put("current_ua", current ?: JSONObject.NULL)
                .put("voltage_mv", power?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1)
                .put("plugged", power?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
                .put("temperature_tenths_c", power?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
            synchronized(samples) { samples.add(row) }
            if (running) handler?.postDelayed(this, 1000)
        }
    }

    fun start() {
        check(!running)
        thread.start()
        handler = Handler(thread.looper)
        running = true
        check(handler!!.post(collect))
    }

    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacks(collect)
        handler?.post(collect) // final sample on the same serial worker
        thread.quitSafely()
        thread.join(5000)
        check(!thread.isAlive) { "资源采样未结束" }
    }

    fun report(): JSONObject {
        val all = synchronized(samples) { samples.toList() }
        val pressure = all.filter { it.optString("stage") == "stream_pressure" }
        val first = pressure.firstOrNull()
        val last = pressure.lastOrNull()
        val elapsed = if (first != null && last != null) last.getLong("elapsed_ms") - first.getLong("elapsed_ms") else 0
        val cpu = if (first != null && last != null) last.getLong("process_cpu_ms") - first.getLong("process_cpu_ms") else 0
        val unplugged = pressure.size >= 2 && pressure.all { it.getInt("plugged") == 0 }
        fun difference(key: String): Long? {
            if (!unplugged || first == null || last == null || first.isNull(key) || last.isNull(key)) return null
            val delta = first.getLong(key) - last.getLong(key)
            return delta.takeIf { it > 0 }
        }
        val energy = difference("energy_nwh")
        val charge = difference("charge_uah")
        val voltages = pressure.map { it.getInt("voltage_mv") }.filter { it > 0 }
        val energyMwh = energy?.div(1_000_000.0) ?: if (charge != null && voltages.isNotEmpty())
            charge * voltages.average() / 1_000_000.0 else null
        return JSONObject().put("samples", JSONArray(all))
            .put("scope", "instrumented_app_process_and_whole_device_battery")
            .put("pressure_samples", pressure.size)
            .put("cpu_percent_one_core", if (elapsed > 0) cpu * 100.0 / elapsed else JSONObject.NULL)
            .put("peak_pss_kib", pressure.maxOfOrNull { it.getInt("pss_kib") } ?: JSONObject.NULL)
            .put("start_pss_kib", first?.getInt("pss_kib") ?: JSONObject.NULL)
            .put("end_pss_kib", last?.getInt("pss_kib") ?: JSONObject.NULL)
            .put("unplugged", unplugged)
            .put("energy_method", if (energy != null) "battery_energy_counter" else if (energyMwh != null) "charge_voltage_estimate" else "unavailable")
            .put("consumed_mwh", energyMwh ?: JSONObject.NULL)
            .put("mean_power_mw", if (energyMwh != null && elapsed > 0) energyMwh * 3600000.0 / elapsed else JSONObject.NULL)
    }
}
