package com.wifigatekeeper.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wifigatekeeper.core.network.PrivateNetworkGuard
import com.wifigatekeeper.core.router.RouterKind
import com.wifigatekeeper.core.router.RouterKindDetector
import kotlinx.coroutines.launch

/**
 * Quick-link setup: opens the router's own admin page in an in-app browser and
 * lets the user log in exactly as they normally would. The app then reads the
 * credentials back - via the HTTP Basic auth challenge (TP-Link Classic) or a
 * form-submit hook (generic routers) - verifies them against the router, and
 * finishes setup without any manual field typing.
 *
 * The credential hooking is restricted to private LAN hosts by
 * [PrivateNetworkGuard]; captured values go straight into
 * EncryptedSharedPreferences and nowhere else.
 */
@Composable
fun QuickLinkScreen(
    gatewayUrl: String,
    onLinked: (ip: String, username: String, password: String, kind: RouterKind) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlField by remember { mutableStateOf(gatewayUrl) }
    var blockedHost by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var capturedFrom by remember { mutableStateOf<String?>(null) }
    var verifyState by remember { mutableStateOf<VerifyState>(VerifyState.Idle) }

    var pendingAuth by remember { mutableStateOf<HttpAuthHandler?>(null) }
    var authHost by remember { mutableStateOf<String?>(null) }
    var authUser by remember { mutableStateOf("admin") }
    var authPass by remember { mutableStateOf("") }

    // Assigned right after creation; the capture callbacks read the WebView's
    // current URL through it, so it may not be referenced inside the factory.
    var controllerRef: QuickLinkController? = null
    val controller = remember {
        QuickLinkController(
            context = context,
            onAuthChallenge = { handler, host ->
                pendingAuth = handler
                authHost = host
                authUser = "admin"
                authPass = ""
            },
            onFormLogin = { user, pass ->
                capturedFrom = "login form"
                verifyState = VerifyState.Verifying
                val ip = Uri.parse(controllerRef?.webView?.url ?: urlField).host
                    ?: urlField.substringAfter("//").substringBefore('/')
                verify(scope, ip, user, pass, onLinked) { verifyState = it }
            },
            onBlocked = { host -> blockedHost = host },
            onLoadError = { message -> loadError = message },
            onPageLoaded = { loadError = null; blockedHost = null },
        )
    }
    controllerRef = controller

    fun captureCredentials(username: String, password: String, source: String) {
        if (verifyState is VerifyState.Verifying || verifyState is VerifyState.Verified) return
        capturedFrom = source
        verifyState = VerifyState.Verifying
        val ip = Uri.parse(controller.webView.url ?: urlField).host
            ?: urlField.substringAfter("//").substringBefore('/')
        verify(scope, ip, username, password, onLinked) { verifyState = it }
    }

    DisposableEffect(Unit) {
        controller.webView.loadUrl(controller.normalize(urlField))
        onDispose { controller.webView.destroy() }
    }

    // The back button must navigate INSIDE the browser first; only when there
    // is no history left does it close the quick-link screen.
    BackHandler {
        if (controller.webView.canGoBack()) {
            controller.webView.goBack()
        } else {
            onClose()
        }
    }

    // Progress of the automatic verification after credentials were captured.
    val statusText = when (val state = verifyState) {
        is VerifyState.Idle -> null
        is VerifyState.Verifying ->
            "Credentials captured from ${stateSource(capturedFrom)} - verifying…"
        is VerifyState.Verified ->
            "Verified as ${state.kind.label} - saved. Applying protection…"
        is VerifyState.Failed -> state.message
    }
    val ok = verifyState is VerifyState.Verified

    Column(modifier = Modifier.fillMaxSize()) {
        // Header: close, title, and an editable router address.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            TextButton(onClick = onClose) { Text("✕ Close") }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Quick link",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = urlField,
                onValueChange = { urlField = it },
                label = { Text("Router address") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                controller.webView.loadUrl(controller.normalize(urlField))
            }) { Text("Go") }
        }
        Text(
            text = "Log in to your router below as usual - the app reads the login " +
                "automatically and never sends it anywhere.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))

        if (statusText != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(10.dp),
            ) {
                if (verifyState is VerifyState.Verifying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = if (ok) Color(0xFF529286) else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (blockedHost != null) {
            Text(
                text = "Blocked ${blockedHost.orEmpty()} - the built-in browser only opens " +
                    "addresses on your local network.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (loadError != null) {
            Text(
                text = loadError.orEmpty(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        AndroidView(factory = { controller.webView }, modifier = Modifier.fillMaxSize())
    }

    // Own login dialog for the Basic-auth challenge: whatever the user types
    // here both logs the WebView in AND is captured for the monitoring client.
    if (pendingAuth != null) {
        AlertDialog(
            onDismissRequest = {
                pendingAuth?.cancel()
                pendingAuth = null
            },
            title = { Text("Router login") },
            text = {
                Column {
                    Text(
                        "The router at ${authHost.orEmpty()} asked for its admin " +
                            "login. This is the same dialog your browser would show - " +
                            "the app reads it afterwards, nothing is sent anywhere.",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = authUser,
                        onValueChange = { authUser = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = authPass,
                        onValueChange = { authPass = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val handler = pendingAuth
                        val user = authUser.trim().ifBlank { "admin" }
                        val pass = authPass
                        pendingAuth = null
                        handler?.proceed(user, pass)
                        captureCredentials(user, pass, "router login dialog")
                    },
                    // Some ISP routers ship with an EMPTY admin password - never
                    // block the login button just because the field is blank.
                    enabled = true,
                ) { Text("Log in") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    pendingAuth?.cancel()
                    pendingAuth = null
                }) { Text("Cancel") }
            },
        )
    }
}

/** Runs the router-kind detection and reports the outcome to [setState]. */
private fun verify(
    scope: kotlinx.coroutines.CoroutineScope,
    ip: String,
    username: String,
    password: String,
    onLinked: (String, String, String, RouterKind) -> Unit,
    setState: (VerifyState) -> Unit,
) {
    scope.launch {
        val kind = RouterKindDetector.detect(ip, username, password)
        if (kind != null) {
            setState(VerifyState.Verified(kind))
            onLinked(ip, username, password, kind)
        } else {
            setState(
                VerifyState.Failed(
                    "Logged in, but neither router profile accepted these credentials. " +
                        "Check the password and try again, or close and use manual setup.",
                ),
            )
        }
    }
}

private fun stateSource(source: String?): String = when (source) {
    "router login dialog" -> "the router login dialog"
    "login form" -> "the router login page"
    else -> "the router"
}

/** Progress of the automatic verification after credentials were captured. */
private sealed interface VerifyState {
    data object Idle : VerifyState
    data object Verifying : VerifyState
    data class Verified(val kind: RouterKind) : VerifyState
    data class Failed(val message: String) : VerifyState
}

/**
 * Owns the WebView and all browser-side credential capture. Compose state
 * changes are funnelled through the callbacks (invoked on the main thread).
 */
@SuppressLint("SetJavaScriptEnabled")
private class QuickLinkController(
    context: Context,
    private val onAuthChallenge: (HttpAuthHandler, String) -> Unit,
    private val onFormLogin: (String, String) -> Unit,
    private val onBlocked: (String) -> Unit,
    private val onLoadError: (String) -> Unit,
    private val onPageLoaded: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        addJavascriptInterface(Bridge(), "GatekeeperBridge")
        webViewClient = RouterPageClient()
    }

    fun normalize(raw: String): String = raw.trim().let {
        if (it.startsWith("http")) it else "http://$it"
    }

    private fun isLan(url: String?): Boolean =
        PrivateNetworkGuard.isPrivateLanHost(Uri.parse(url.orEmpty()).host)

    /** Reports form-typed credentials; validates the current page host again. */
    private inner class Bridge {
        @JavascriptInterface
        fun onFormLogin(user: String?, pass: String?) {
            val password = pass.orEmpty().trim()
            if (password.isEmpty()) return
            val host = Uri.parse(webView.url.orEmpty()).host
            if (!PrivateNetworkGuard.isPrivateLanHost(host)) return
            val username = user.orEmpty().trim().ifBlank { "admin" }
            mainHandler.post { onFormLogin(username, password) }
        }
    }

    private inner class RouterPageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val target = request.url
            if (target.scheme == "http" || target.scheme == "https") {
                if (PrivateNetworkGuard.isPrivateLanHost(target.host)) return false
                mainHandler.post { onBlocked(target.host.orEmpty()) }
                return true
            }
            return true // mailto:, intent:, ... are irrelevant here
        }

        override fun onPageFinished(view: WebView, finishedUrl: String?) {
            if (isLan(finishedUrl)) {
                mainHandler.post { onPageLoaded() }
                view.evaluateJavascript(FORM_HOOK_JS, null)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                mainHandler.post {
                    onLoadError(
                        "Router page unreachable (${error.description}). " +
                            "Check that the phone is on the router Wi-Fi.",
                    )
                }
            }
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String?,
        ) {
            if (PrivateNetworkGuard.isPrivateLanHost(host)) {
                mainHandler.post { onAuthChallenge(handler, host) }
            } else {
                handler.cancel()
            }
        }
    }
}

/**
 * Observes HTML login forms without touching them: reports the typed values on
 * submit (capture phase), on a click inside the form (for routers whose login
 * is driven by a JS onclick handler instead of a real submit), and on password
 * changes (debounced, for forms without any button). Only ever injected on
 * private LAN pages.
 */
private const val FORM_HOOK_JS = """
(function() {
  if (window.__gkHook) return;
  window.__gkHook = true;
  var timer = null;
  function report(form) {
    try {
      var u = '', p = '';
      var els = form.querySelectorAll('input');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        var t = (el.type || '').toLowerCase();
        if (t === 'password' && el.value) { p = el.value; }
        else if ((t === 'text' || t === 'username' || t === 'email' || t === '') && el.value && !u) { u = el.value; }
      }
      if (p) { window.GatekeeperBridge.onFormLogin(u, p); }
    } catch (e) {}
  }
  function debouncedReport(form) {
    if (timer) { clearTimeout(timer); }
    timer = setTimeout(function() { timer = null; report(form); }, 350);
  }
  document.addEventListener('submit', function(e) {
    if (e.target) report(e.target);
  }, true);
  document.addEventListener('click', function(e) {
    var el = e.target;
    if (!el || !el.closest) return;
    var form = el.closest('form');
    if (form && (el.tagName === 'BUTTON' || el.tagName === 'INPUT' || el.tagName === 'A')) {
      // JS-driven logins run their handler after the click; read the fields
      // once the handler had its turn.
      setTimeout(function() { report(form); }, 300);
    }
  }, true);
  document.addEventListener('change', function(e) {
    var el = e.target;
    if (el && (el.type || '').toLowerCase() === 'password' && el.value && el.form) {
      debouncedReport(el.form);
    }
  }, true);
})();
"""
