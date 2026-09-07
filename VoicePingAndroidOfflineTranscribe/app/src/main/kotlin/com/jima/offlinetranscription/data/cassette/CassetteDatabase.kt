package com.voiceping.offlinetranscription.data.cassette

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CassetteEntity::class, MemoEntity::class],
    version = 1,
    exportSchema = false
)
abstract class CassetteDatabase : RoomDatabase() {
    abstract fun cassetteDao(): CassetteDao
    abstract fun memoDao(): MemoDao

    companion object {
        @Volatile
        private var instance: CassetteDatabase? = null

        fun getInstance(context: Context): CassetteDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CassetteDatabase::class.java,
                    "cassettes.db"
                ).build().also { instance = it }
            }
        }
    }
}
