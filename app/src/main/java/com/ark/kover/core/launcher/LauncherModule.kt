package com.ark.kover.core.launcher

import com.ark.kover.core.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LauncherModule {

    @Provides
    @Singleton
    fun provideNeoStationAdapter(
        esde: EsDeAdapter,
        settings: SettingsRepository,
    ): NeoStationAdapter = NeoStationAdapter(esde, settings)

    @Provides
    @Singleton
    fun provideCustomAdapter(
        esde: EsDeAdapter,
        settings: SettingsRepository,
    ): CustomAdapter = CustomAdapter(esde, settings)

    @Provides
    @Singleton
    fun provideSwitchableLauncherAdapter(
        settings: SettingsRepository,
        esde: EsDeAdapter,
        neostation: NeoStationAdapter,
        custom: CustomAdapter,
    ): SwitchableLauncherAdapter =
        SwitchableLauncherAdapter(settings, esde, neostation, custom)

    @Provides
    @Singleton
    fun provideLauncherAdapter(switchable: SwitchableLauncherAdapter): LauncherAdapter = switchable
}
