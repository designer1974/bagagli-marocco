package com.bagaglimarocco.autista.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun stopKindToString(v: StopKind): String = v.name
    @TypeConverter fun stringToStopKind(v: String): StopKind = StopKind.valueOf(v)
    @TypeConverter fun stopStatusToString(v: StopStatus): String = v.name
    @TypeConverter fun stringToStopStatus(v: String): StopStatus = StopStatus.valueOf(v)
    @TypeConverter fun bagStatusToString(v: BagStatus): String = v.name
    @TypeConverter fun stringToBagStatus(v: String): BagStatus = BagStatus.valueOf(v)
}

@Database(
    entities = [StopEntity::class, BagEntity::class, TripEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun stopDao(): StopDao
    abstract fun bagDao(): BagDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autista.db",
                ).build().also { instance = it }
            }
    }
}
