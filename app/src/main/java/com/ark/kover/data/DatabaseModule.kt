package com.ark.kover.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KoverDatabase =
        Room.databaseBuilder(
            context,
            KoverDatabase::class.java,
            "kover.db",
        ).build()

    @Provides
    fun provideRomHashDao(db: KoverDatabase): RomHashDao = db.romHashDao()

    @Provides
    fun provideMatchDao(db: KoverDatabase): MatchDao = db.matchDao()
}
