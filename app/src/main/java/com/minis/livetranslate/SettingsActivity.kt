package com.minis.livetranslate

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Lets the user point the app at whichever translation backend they want.
 * Defaults to the Google free endpoint so it works out of the box.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var presetSpinner: Spinner
    private lateinit var etEndpoint: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etAuthHeader: EditText
    private lateinit var etAuthPrefix: EditText
    private lateinit var etSrc: EditText
    private lateinit var etDst: EditText
    private lateinit var tvHint: TextView

    private var loaded: ApiConfig? = null
    private var suppressPresetCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        presetSpinner = findViewById(R.id.spPreset)
        etEndpoint = findViewById(R.id.etEndpoint)
        etKey = findViewById(R.id.etKey)
        etModel = findViewById(R.id.etModel)
        etAuthHeader = findViewById(R.id.etAuthHeader)
        etAuthPrefix = findViewById(R.id.etAuthPrefix)
        etSrc = findViewById(R.id.etSrc)
        etDst = findViewById(R.id.etDst)
        tvHint = findViewById(R.id.tvHint)

        val labels = EnginePreset.entries.map { it.label }
        presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )

        val cfg = ApiConfigStore.load(this)
        loaded = cfg
        bind(cfg, applyDefaults = false)

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressPresetCallback) return
                val preset = EnginePreset.entries[pos]
                // Switching engines reloads that engine's defaults, but keeps the key.
                val keptKey = etKey.text.toString()
                bind(
                    ApiConfig(
                        preset = preset,
                        endpoint = preset.defaultEndpoint,
                        apiKey = keptKey,
                        model = etModel.text.toString().ifBlank { "gpt-4o-mini" },
                        authHeader = preset.authHeader,
                        authPrefix = preset.authPrefix,
                        sourceLang = etSrc.text.toString().ifBlank { "auto" },
                        targetLang = etDst.text.toString().ifBlank { "auto" },
                    ),
                    applyDefaults = true,
                )
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            ApiConfigStore.save(this, collect())
            tvHint.text = "已保存。返回主界面重新启动悬浮窗即可生效。"
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            val fresh = ApiConfigStore.reset(this)
            bind(fresh, applyDefaults = true)
            tvHint.text = "已恢复默认（Google 免费端点）。"
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            testConnection()
        }
    }

    private fun bind(cfg: ApiConfig, applyDefaults: Boolean) {
        suppressPresetCallback = true
        presetSpinner.setSelection(EnginePreset.entries.indexOf(cfg.preset))
        suppressPresetCallback = false
        etEndpoint.setText(cfg.endpoint)
        etKey.setText(cfg.apiKey)
        etModel.setText(cfg.model)
        etAuthHeader.setText(cfg.authHeader)
        etAuthPrefix.setText(cfg.authPrefix)
        etSrc.setText(cfg.sourceLang)
        etDst.setText(cfg.targetLang)
        if (applyDefaults) tvHint.text = "已载入「${cfg.preset.label}」的默认值。"
    }

    private fun collect(): ApiConfig {
        val preset = EnginePreset.entries[presetSpinner.selectedItemPosition]
        return ApiConfig(
            preset = preset,
            endpoint = etEndpoint.text.toString().trim(),
            apiKey = etKey.text.toString().trim(),
            model = etModel.text.toString().trim(),
            authHeader = etAuthHeader.text.toString().trim(),
            authPrefix = etAuthPrefix.text.toString(),
            sourceLang = etSrc.text.toString().trim().ifBlank { "auto" },
            targetLang = etDst.text.toString().trim().ifBlank { "auto" },
        )
    }

    private fun testConnection() {
        val cfg = collect()
        ApiConfigStore.save(this, cfg)  // test what's on screen
        tvHint.text = "测试中…"
        Thread {
            val msg = try {
                val t = kotlinx.coroutines.runBlocking {
                    Translator.translate(applicationContext, "你好，世界")
                }
                "✓ 成功 (${t.ms}ms)：${t.text}"
            } catch (e: Exception) {
                "✗ 失败：${e.message}"
            }
            runOnUiThread { tvHint.text = msg }
        }.start()
    }
}
