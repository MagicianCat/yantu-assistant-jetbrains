package com.taikang.yantu.platform

object WindowsPlatformConfig {
    const val API_BASE_URL = "http://10.154.76.195/api/v1"
    const val WEB_BASE_URL = "http://10.154.76.195"
    fun enabled(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
