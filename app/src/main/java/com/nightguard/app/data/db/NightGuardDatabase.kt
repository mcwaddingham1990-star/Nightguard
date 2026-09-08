package com.nightguard.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [TimelineEvent::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class NightGuardDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile private var INSTANCE: NightGuardDatabase? = null

        fun getInstance(context: Context): NightGuardDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NightGuardDatabase::class.java,
                    "nightguard.db"
                )
                    // Pre-release app (see README) with no shipped schema to migrate from yet;
                    // a real migration path should replace this before this ships to a device
                    // that already has v1 data worth keeping.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
