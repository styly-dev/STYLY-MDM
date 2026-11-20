package com.styly.mdmclient

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pvr.tobservice.ToBServiceHelper
import org.json.JSONObject
import java.util.UUID

/** The vendor opens this activity first so Android permits the foreground navigation. */
class BrowserLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) finish()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        val request = BrowserLaunchRequests.take(intent.getStringExtra("browser_token"))
        if (request == null) { finish(); return }
        if (!request.isActive()) { finish(); return }
        val code = try {
            val selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
            val browser = packageManager.resolveActivity(selector, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            when {
                browser == null -> "no_browser"
                browser.name.contains("ResolverActivity") || browser.name.contains("ChooserActivity") -> "choose_browser"
                !browser.enabled || !browser.exported -> "no_browser"
                else -> {
                    // The browser launcher is not necessarily its URL entry point on PICO.
                    val view = Intent(Intent.ACTION_VIEW, Uri.parse(request.url).normalizeScheme())
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(browser.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (packageManager.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                        "no_browser"
                    } else {
                        startActivity(view)
                        "requested"
                    }
                }
            }
        } catch (_: Exception) {
            // Intent exceptions may echo URLs and must never reach logs or results.
            "launch_failed"
        }
        request.complete(code)
        finish()
    }
}

/** All request state is owned by the main looper and is never persisted. */
internal object BrowserLaunchRequests {
    data class Request(val url: String, val isActive: () -> Boolean, val complete: (String) -> Unit)
    private val main = Handler(Looper.getMainLooper())
    private val gate = BrowserRequestGate<Request>()
    private var timeout: Runnable? = null

    fun open(service: MdmClientService, payload: JSONObject, command: WebSocketManager.CommandContext?) {
        main.post {
            if (command == null || !command.isActive() || command.provisionalConnectionId != null) return@post
            val requestId = payload.opt("request_id") as? String ?: return@post
            if (!requestId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) return@post
            fun reply(code: String) {
                command.sendMessage(JSONObject().apply {
                    put("type", "OPEN_URL_RESULT")
                    put("request_id", requestId)
                    put("code", code)
                }.toString())
            }
            val url = validBrowserUrl(payload.opt("url"))
            if (url == null) { reply("invalid_url"); return@post }
            val token = UUID.randomUUID().toString()
            val request = Request(url, command::isActive, ::reply)
            if (!gate.offer(token, SystemClock.elapsedRealtime() + 12_000, request)) {
                reply("busy")
                return@post
            }
            timeout = Runnable {
                // A delayed callback after sleep must never clear a newer request.
                if (gate.matches(token)) {
                    gate.clear()
                    timeout = null
                    reply("timeout")
                }
            }.also { main.postDelayed(it, 12_000) }
            try {
                val binder = ToBServiceHelper.getInstance().serviceBinder
                val result = binder?.pbsStartActivity(
                    service.packageName, BrowserLaunchActivity::class.java.name,
                    "", JSONObject().put("browser_token", token).toString(), null,
                    intArrayOf(Intent.FLAG_ACTIVITY_NEW_TASK), 0
                )
                if (result != 0) {
                    take(token)?.complete("unavailable")
                }
            } catch (_: Exception) {
                take(token)?.complete("launch_failed")
            }
        }
    }

    fun take(token: String?): Request? {
        if (!gate.matches(token)) return null
        val request = gate.take(token, SystemClock.elapsedRealtime())
        timeout?.let(main::removeCallbacks)
        timeout = null
        return request
    }

    fun clear() {
        gate.clear()
        timeout?.let(main::removeCallbacks)
        timeout = null
    }
}
