package ch.codelook.locationtracker.data.api

import ch.codelook.locationtracker.data.preferences.PreferencesManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DynamicBaseUrlInterceptor @Inject constructor(
    private val preferencesManager: PreferencesManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val serverUrl = preferencesManager.serverUrl

        serverUrl.toHttpUrlOrNull()?.let { baseUrl ->
            val newUrl = request.url.newBuilder()
                .scheme(baseUrl.scheme)
                .host(baseUrl.host)
                .port(baseUrl.port)
                .build()
            request = request.newBuilder().url(newUrl).build()
        }

        return chain.proceed(request)
    }
}
