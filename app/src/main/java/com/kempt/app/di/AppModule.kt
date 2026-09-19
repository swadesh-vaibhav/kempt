package com.kempt.app.di

import android.content.Context
import androidx.room.Room
import com.kempt.app.data.BlockEventDao
import com.kempt.app.data.BlockRuleDao
import com.kempt.app.data.KemptDatabase
import com.kempt.app.data.LockStateStore
import com.kempt.app.sync.AccountabilityService
import com.kempt.app.sync.StubAccountabilityService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindAccountabilityService(
        impl: StubAccountabilityService
    ): AccountabilityService

    companion object {
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): KemptDatabase =
            Room.databaseBuilder(context, KemptDatabase::class.java, "kempt.db").build()

        @Provides
        fun provideBlockRuleDao(db: KemptDatabase): BlockRuleDao = db.blockRuleDao()

        @Provides
        fun provideBlockEventDao(db: KemptDatabase): BlockEventDao = db.blockEventDao()

        @Provides
        @Singleton
        fun provideLockStateStore(@ApplicationContext context: Context): LockStateStore =
            LockStateStore(context)
    }
}
