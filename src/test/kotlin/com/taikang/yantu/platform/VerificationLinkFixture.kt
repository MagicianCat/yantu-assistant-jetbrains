package com.taikang.yantu.platform

object VerificationLinkFixture {
    @JvmStatic fun main(args: Array<String>) {
        val url = VerificationLink.create("http://10.154.76.195/ide/authorize?old=discard", "ABCD-1234")
        check(url.toASCIIString() == "http://10.154.76.195/ide/authorize?user_code=ABCD-1234") { url }
        check(VerificationLink.create("https://platform.example/ide/authorize", "ABCD-1234").scheme == "https")
        check(runCatching { VerificationLink.create("/ide/authorize", "ABCD-1234") }.isFailure)
        check(runCatching { VerificationLink.create("http://10.evil.example/ide/authorize", "ABCD-1234") }.isFailure)
    }
}
