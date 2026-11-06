package com.yuanbao.downloader.di

import android.content.Context
import androidx.room.Room
import com.yuanbao.downloader.data.db.AppDatabase
import com.yuanbao.downloader.data.repo.DownloadRepository
import com.yuanbao.downloader.data.repo.DownloadRepositoryImpl
import com.yuanbao.downloader.data.settings.SettingsDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "downloader.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore =
        SettingsDataStore(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository
}
