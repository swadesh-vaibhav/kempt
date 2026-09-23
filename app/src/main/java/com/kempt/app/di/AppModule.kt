/**
 * @file
 * @brief Hilt module providing app-wide singletons (database, DAOs, stores) and interface bindings.
 */
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

/**
 * @brief Hilt module supplying the dependency graph's data-layer objects.
 *
 * @details Installed in the @c SingletonComponent, so everything here lives for the whole
 * application. @c @Binds tells Hilt which concrete class to supply when an interface is
 * requested; @c @Provides methods construct objects Hilt can't build on its own (third-party
 * types like the Room database). The class is @c abstract because @c @Binds functions must be
 * abstract, while the @c @Provides factories live in a @c companion object.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    /**
     * @brief Binds the @ref com.kempt.app.sync.AccountabilityService interface to its stub implementation.
     * @param impl The concrete implementation Hilt should supply (it knows how to build this).
     * @return The interface type that injection sites request.
     */
    @Binds
    abstract fun bindAccountabilityService(
        impl: StubAccountabilityService
    ): AccountabilityService

    /** @brief Holds the @c @Provides factories for types Hilt cannot construct directly. */
    companion object {
        /**
         * @brief Builds the app's single Room database instance.
         * @param context The application context (Hilt supplies it via @c @ApplicationContext).
         * @return The @ref com.kempt.app.data.KemptDatabase, backed by the on-disk file "kempt.db".
         */
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): KemptDatabase =
            Room.databaseBuilder(context, KemptDatabase::class.java, "kempt.db").build()

        /**
         * @brief Exposes the block-rule DAO from the database.
         * @param db The database.
         * @return Its @ref com.kempt.app.data.BlockRuleDao.
         */
        @Provides
        fun provideBlockRuleDao(db: KemptDatabase): BlockRuleDao = db.blockRuleDao()

        /**
         * @brief Exposes the block-event DAO from the database.
         * @param db The database.
         * @return Its @ref com.kempt.app.data.BlockEventDao.
         */
        @Provides
        fun provideBlockEventDao(db: KemptDatabase): BlockEventDao = db.blockEventDao()

        /**
         * @brief Provides the singleton lock-state/passcode store.
         * @param context The application context.
         * @return The @ref com.kempt.app.data.LockStateStore.
         */
        @Provides
        @Singleton
        fun provideLockStateStore(@ApplicationContext context: Context): LockStateStore =
            LockStateStore(context)
    }
}
