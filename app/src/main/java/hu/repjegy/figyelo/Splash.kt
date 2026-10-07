package hu.repjegy.figyelo

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * 3D nyitóanimáció (assets/splash/splash.html, three.js).
 * A háttér végig fekete; amikor a gép elhúzott, a JS szól, és ez a réteg
 * elhalványul, alatta felerősödik a főképernyő.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    var done by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (done) 0f else 1f,
        animationSpec = tween(350),
        label = "splash",
        finishedListener = { if (done) onFinished() },
    )

    // Biztonsági időkorlát: ha a 3D valamiért nem indulna el, se ragadjon be
    LaunchedEffect(Unit) {
        delay(4500)
        done = true
    }
    BackHandler(enabled = true) { done = true }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun done() {
                                post { done = true }
                            }
                        },
                        "Splash",
                    )
                    loadUrl(
                        "file:///android_asset/splash/splash.html?v=" +
                            Updater.currentVersion
                    )
                }
            },
        )
    }
}
