package com.ark.kover.scraper

import com.ark.kover.core.scraper.Scraper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScraperModule {

    @Provides
    @Singleton
    fun provideNeoAssetsScraper(
        settings: com.ark.kover.core.settings.SettingsRepository,
        httpClient: okhttp3.OkHttpClient,
    ): NeoAssetsScraper = NeoAssetsScraper(settings, httpClient)

    @Provides
    @Singleton
    fun provideSteamGridDbScraper(
        settings: com.ark.kover.core.settings.SettingsRepository,
        httpClient: okhttp3.OkHttpClient,
    ): SteamGridDbScraper = SteamGridDbScraper(settings, httpClient)
}
