package com.example.locationservice

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [QueuedLocation::class, SessionEvent::class, Setting::class, BluetoothDevice::class, QueuedBluetoothDevice::class], version = 11, exportSchema = false)
abstract class LocationDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    abstract fun sessionEventDao(): SessionEventDao
    abstract fun settingDao(): SettingDao
    abstract fun bluetoothDeviceDao(): BluetoothDeviceDao
    abstract fun queuedBluetoothDeviceDao(): QueuedBluetoothDeviceDao

    companion object {
        @Volatile
        private var INSTANCE: LocationDatabase? = null

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session_events ADD COLUMN carId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE session_events ADD COLUMN sessionId TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bluetooth_devices ADD COLUMN carDescription TEXT DEFAULT NULL")
            }
        }

        fun getDatabase(context: Context): LocationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LocationDatabase::class.java,
                    "location_queue.db"
                )
                .addMigrations(MIGRATION_9_10, MIGRATION_10_11)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
