package dev.sawitulm.palmannotate.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.sawitulm.palmannotate.data.db.*
import dev.sawitulm.palmannotate.data.camera.OrbbecManager
import dev.sawitulm.palmannotate.data.detection.OnnxDetector
import dev.sawitulm.palmannotate.data.location.GpsProvider
import dev.sawitulm.palmannotate.data.storage.AndroidStorageManager
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.InputCache
import dev.sawitulm.palmannotate.data.storage.SafMirrorStore
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.domain.util.OperationQueue
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): PalmAnnotateDatabase =
        PalmAnnotateDatabase.create(ctx)

    @Provides fun provideSessionDao(db: PalmAnnotateDatabase) = db.sessionDao()
    @Provides fun provideTreeDao(db: PalmAnnotateDatabase) = db.treeDao()
    @Provides fun provideSideDao(db: PalmAnnotateDatabase) = db.sideDao()
    @Provides fun provideBboxDao(db: PalmAnnotateDatabase) = db.bboxDao()
    @Provides fun provideLinkDao(db: PalmAnnotateDatabase) = db.confirmedLinkDao()

    @Provides @Singleton
    fun provideStorageManager(@ApplicationContext ctx: Context): AndroidStorageManager =
        AndroidStorageManager(ctx)

    @Provides @Singleton
    fun provideSafMirrorStore(@ApplicationContext ctx: Context): SafMirrorStore =
        SafMirrorStore(ctx)

    @Provides @Singleton
    fun provideExportFolderRepository(@ApplicationContext ctx: Context): ExportFolderRepository =
        ExportFolderRepository(ctx)

    @Provides @Singleton
    fun provideGpsProvider(@ApplicationContext ctx: Context): GpsProvider =
        GpsProvider(ctx)

    @Provides @Singleton
    fun provideSessionRepository(
        sessionDao: SessionDao,
        treeDao: TreeDao,
        sideDao: SideDao,
        bboxDao: BboxDao,
        linkDao: ConfirmedLinkDao,
        storage: AndroidStorageManager,
        saf: SafMirrorStore,
        db: PalmAnnotateDatabase,
    ): SessionRepository = SessionRepository(sessionDao, treeDao, sideDao, bboxDao, linkDao, storage, saf, db)

    @Provides @Singleton
    fun provideOrbbecManager(@ApplicationContext ctx: Context): OrbbecManager =
        OrbbecManager(ctx)

    @Provides @Singleton
    fun provideOnnxDetector(@ApplicationContext ctx: Context): OnnxDetector =
        OnnxDetector(ctx)

    @Provides @Singleton
    fun provideOperationQueue(): OperationQueue = OperationQueue()

    @Provides @Singleton
    fun provideInputCache(@ApplicationContext ctx: Context): InputCache =
        InputCache(ctx)
}
