package com.taikang.yantu.platform

object WindowsConfigFixture {
    @JvmStatic fun main(args: Array<String>) {
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            check(WindowsPlatformConfig.enabled())
            val settings = YantuPlatformService.Settings()
            check(settings.apiBaseUrl == "http://10.154.76.195/api/v1")
            check(settings.webBaseUrl == "http://10.154.76.195")
        } finally { System.setProperty("os.name", original) }
    }
}
