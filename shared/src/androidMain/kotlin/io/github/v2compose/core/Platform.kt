package io.github.v2compose.core

import android.os.Build


actual fun httpAgent(): String? = System.getProperty("http.agent")

actual fun deviceModel(): String = Build.MODEL ?: "Android"