package com.shortsmaker.proof

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
