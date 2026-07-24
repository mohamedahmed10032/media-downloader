package com.melmeligy.mediadownloader.di

import com.melmeligy.mediadownloader.core.DefaultDispatcherProvider
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.data.prefs.SecureSettingsStore
import com.melmeligy.mediadownloader.data.repository.BookmarkRepositoryImpl
import com.melmeligy.mediadownloader.data.repository.DownloadRepositoryImpl
import com.melmeligy.mediadownloader.data.repository.MediaRepositoryImpl
import com.melmeligy.mediadownloader.domain.repository.BookmarkRepository
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.domain.repository.MediaRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    @Binds
    @Singleton
    abstract fun bindBookmarkRepository(impl: BookmarkRepositoryImpl): BookmarkRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SecureSettingsStore): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository
}
