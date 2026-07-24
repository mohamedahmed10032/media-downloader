package com.melmeligy.mediadownloader.di

import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.extractor.DirectMediaExtractor
import com.melmeligy.mediadownloader.extractor.HlsExtractor
import com.melmeligy.mediadownloader.extractor.HtmlMediaExtractor
import com.melmeligy.mediadownloader.extractor.ImageExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes every [MediaExtractor] to the multibound Set consumed by the ExtractorRegistry.
 * Adding support for a new source is a one-line @Binds @IntoSet entry here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExtractorModule {

    @Binds
    @IntoSet
    abstract fun bindDirect(extractor: DirectMediaExtractor): MediaExtractor

    @Binds
    @IntoSet
    abstract fun bindImage(extractor: ImageExtractor): MediaExtractor

    @Binds
    @IntoSet
    abstract fun bindHls(extractor: HlsExtractor): MediaExtractor

    @Binds
    @IntoSet
    abstract fun bindHtml(extractor: HtmlMediaExtractor): MediaExtractor
}
