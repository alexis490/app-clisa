package com.example.app_clisa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app_clisa.data.backend.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Opt-in real HTTPS liveness/unauthenticated probe only. No login, Firestore or upload. */
@RunWith(AndroidJUnit4::class)
class RenderLiveConnectionTest {
    @Test fun androidUsesSystemTlsAndRealRenderRejectsUnauthenticatedAccess() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRender") == "true")
        assertEquals("https://app-clisa.onrender.com/", BuildConfig.BACKEND_BASE_URL)
        val session = object : BackendSession {
            override fun currentUid(): String? = null
            override suspend fun credential(forceRefresh: Boolean): BackendCredential = error("No se obtiene un token para esta comprobación pública")
        }
        ClisaBackendClient(session).health()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
        try {
            http.newCall(Request.Builder().url(BuildConfig.BACKEND_BASE_URL + "v1/admin/status").get().build()).execute().use { response ->
                assertEquals(401, response.code)
                assertEquals("UNAUTHENTICATED", JSONObject(response.body!!.string()).getJSONObject("error").getString("code"))
            }
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
}
