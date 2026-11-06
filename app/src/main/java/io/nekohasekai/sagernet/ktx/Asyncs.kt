@file:Suppress("EXPERIMENTAL_API_USAGE")

package app.rawline.mobile.ktx

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

fun runOnDefaultDispatcher(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.Default, block = block)

fun Fragment.runOnLifecycleDispatcher(block: suspend CoroutineScope.() -> Unit) =
    lifecycleScope.launch(Dispatchers.Default, block = block)

fun runOnIoDispatcher(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.IO, block = block)

fun runOnMainDispatcher(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.Main.immediate, block = block)

suspend fun <T> onMainDispatcher(block: suspend CoroutineScope.() -> T) =
    withContext(Dispatchers.Main.immediate, block = block)
