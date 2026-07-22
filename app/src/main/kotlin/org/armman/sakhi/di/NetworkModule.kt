package org.armman.sakhi.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.armman.sakhi.BuildConfig
import org.armman.sakhi.data.auth.AuthInterceptor
import org.armman.sakhi.data.network.ApiFileLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private const val TIMEOUT_SECONDS = 30L

/** Provides the Retrofit client pointing at the API Gateway. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
  @Provides
  @Singleton
  fun provideOkHttp(
    authInterceptor: AuthInterceptor,
    apiFileLoggingInterceptor: ApiFileLoggingInterceptor,
  ): OkHttpClient {
    // Never log request/response bodies in release — they may carry PII or tokens.
    val logging = HttpLoggingInterceptor().apply {
      level = if (BuildConfig.DEBUG) {
        HttpLoggingInterceptor.Level.BODY
      } else {
        HttpLoggingInterceptor.Level.NONE
      }
    }
    val builder = OkHttpClient.Builder()
      .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // Attaches the Bearer token (if any) before the logging interceptors see the request.
      .addInterceptor(authInterceptor)
      .addInterceptor(logging)
    // Temporary CR-017/CR-018 verification aid — see ApiFileLoggingInterceptor's doc. Debug-only,
    // remove once verification is done; must never reach a release build.
    if (BuildConfig.DEBUG) {
      builder.addInterceptor(apiFileLoggingInterceptor)
    }
    return builder.build()
  }

  @Provides
  @Singleton
  fun provideRetrofit(client: OkHttpClient): Retrofit =
    Retrofit.Builder()
      .baseUrl(BuildConfig.API_BASE_URL)
      .client(client)
      .addConverterFactory(GsonConverterFactory.create())
      .build()
}
