package com.bhickta.faceattendance.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AttendanceEventEntity::class, DeviceStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AttendanceDatabase : RoomDatabase() {
    abstract fun attendanceEventDao(): AttendanceEventDao

    companion object {
        @Volatile
        private var instance: AttendanceDatabase? = null

        fun get(context: Context): AttendanceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AttendanceDatabase::class.java,
                "face-attendance.db",
            ).build().also { instance = it }
        }
    }
}
