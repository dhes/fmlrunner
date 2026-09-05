package org.litlfred.fmlrunner

/**
 * Apple platform-specific implementations
 */
actual class PlatformLogger {
    actual fun log(level: String, message: String, data: Any?) {
        val logMessage = "${level.uppercase()}: $message"
        println("$logMessage ${data ?: ""}")
    }
}

actual fun getPlatformName(): String = "Apple"
