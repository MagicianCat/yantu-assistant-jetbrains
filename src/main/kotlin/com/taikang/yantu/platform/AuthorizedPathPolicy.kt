package com.taikang.yantu.platform

internal object AuthorizedPathPolicy {
    private val asdmAssetPath = Regex("/asdm-tool-assets/[1-9][0-9]*(/download)?")

    fun allows(path: String): Boolean =
        path.startsWith("/agent/") || path.startsWith("/bundles/") || path == "/bundles" ||
            path.startsWith("/wiki/") || path == "/wiki/documents:download" || path == "/skill-updates:check" ||
            asdmAssetPath.matches(path)
}
