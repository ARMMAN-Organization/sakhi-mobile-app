package org.armman.sakhi.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.armman.sakhi.BuildConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

/** Provides the Retrofit client pointing at the API Gateway. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
  @Provides
  @Singleton
  fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder().build()

  @Provides
  @Singleton
  fun provideRetrofit(client: OkHttpClient): Retrofit =
    Retrofit.Builder()
      .baseUrl(BuildConfig.API_BASE_URL)
      .client(client)
      .addConverterFactory(GsonConverterFactory.create())
      .build()
}
