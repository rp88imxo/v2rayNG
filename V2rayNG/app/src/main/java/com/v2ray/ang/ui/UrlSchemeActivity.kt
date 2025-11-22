package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogcatBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseActivity() {
    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        // Если интента нет или данных нет — уходим
        if (intent == null || (intent.data == null && intent.action != Intent.ACTION_SEND)) {
            finishWithMain()
            return
        }

        try {
            // Логируем входящий интент для отладки
            Log.d(AppConfig.TAG, "UrlSchemeActivity onCreate: $intent")
            Log.d(AppConfig.TAG, "Data: ${intent.data}")

            if (intent.action == Intent.ACTION_SEND && "text/plain" == intent.type) {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                    tryImportConfig(it)
                }
            } else if (intent.action == Intent.ACTION_VIEW) {
                handleDeepLink(intent.data)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Error processing URL scheme", e)
            finishWithMain()
        }
    }

    private fun handleDeepLink(uri: Uri?) {
        if (uri == null) {
            finishWithMain()
            return
        }

        when (uri.host) {
            "flintapp.ru" -> {
                if (uri.path?.startsWith("/import") == true) {
                    // 1. Пробуем новый метод (Base64)
                    val b64Config = uri.getQueryParameter("b64config")

                    if (!b64Config.isNullOrEmpty()) {
                        try {
                            // Декодируем Base64 обратно в строку vless://...
                            val decodedBytes = Base64.decode(b64Config, Base64.DEFAULT)
                            val decodedConfig = String(decodedBytes, Charsets.UTF_8)
                            Log.i(AppConfig.TAG, "Decoded Base64 config: $decodedConfig")
                            tryImportConfig(decodedConfig)
                        } catch (e: Exception) {
                            Log.e(AppConfig.TAG, "Base64 decode failed", e)
                            toastError(R.string.toast_failure)
                            finishWithMain()
                        }
                        return
                    }

                    // 2. Фоллбэк на старый метод (если ссылка старая)
                    // ВАЖНО: Здесь мы берем закодированную часть вручную, если getQueryParameter ломает символы
                    val rawQuery = uri.encodedQuery ?: ""
                    val paramName = "config="
                    if (rawQuery.contains(paramName)) {
                        val startIndex = rawQuery.indexOf(paramName) + paramName.length
                        // Берем всё до конца строки или до следующего & (хотя в config их быть не должно, если он последний)
                        var encodedConfig = rawQuery.substring(startIndex)
                        if (encodedConfig.contains("&")) {
                            encodedConfig = encodedConfig.substringBefore("&")
                        }

                        try {
                            // Декодируем URL encoding (например %3A -> :)
                            val decodedConfig = URLDecoder.decode(encodedConfig, "UTF-8")
                            Log.i(AppConfig.TAG, "Legacy decoded config: $decodedConfig")
                            tryImportConfig(decodedConfig)
                        } catch (e: Exception) {
                            Log.e(AppConfig.TAG, "Legacy decode failed", e)
                            finishWithMain()
                        }
                    } else {
                        toastError(R.string.toast_failure)
                        finishWithMain()
                    }
                } else {
                    finishWithMain()
                }
            }
            // Обработка flint://vless://...
            else -> {
                val urlString = uri.toString()
                if (urlString.startsWith("flint://")) {
                    // Просто меняем протокол
                    val vlessUrl = urlString.replaceFirst("flint://", "vless://")
                    tryImportConfig(vlessUrl)
                } else {
                    finishWithMain()
                }
            }
        }
    }

    private fun tryImportConfig(config: String) {
        if (config.isBlank()) {
            finishWithMain()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Импорт. Третий параметр true/false зависит от логики Ang (обычно false для добавления)
                val (count, countSub) = AngConfigManager.importBatchConfig(config, "", false)

                withContext(Dispatchers.Main) {
                    if (count + countSub > 0) {
                        toast(R.string.import_subscription_success)
                    } else {
                        // Если импорт не прошел, возможно конфиг кривой
                        Log.w(AppConfig.TAG, "Import returned 0 items. Config was: $config")
                        toast(R.string.import_subscription_failure)
                    }
                    finishWithMain()
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Import crash", e)
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    finishWithMain()
                }
            }
        }
    }

    private fun finishWithMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}