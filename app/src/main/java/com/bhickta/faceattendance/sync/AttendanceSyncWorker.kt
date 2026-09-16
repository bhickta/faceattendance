package com.bhickta.faceattendance.sync

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bhickta.faceattendance.device.DeviceConfigurationStore
import com.bhickta.faceattendance.device.DeviceKeyManager
import com.bhickta.faceattendance.storage.AttendanceDatabase
import com.bhickta.faceattendance.storage.SyncState
import com.bhickta.faceattendance.vision.BiometricRosterStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AttendanceSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val configuration = DeviceConfigurationStore(applicationContext).get()
            ?: return@withContext Result.success()
        val dao = AttendanceDatabase.get(applicationContext).attendanceEventDao()
        val events = dao.pending(AttendanceApiClient.MAX_BATCH_SIZE)
        try {
            val client = AttendanceApiClient(configuration, DeviceKeyManager())
            val rosterStore = BiometricRosterStore(applicationContext)
            client.syncRoster(rosterStore.get()?.version).roster?.let(rosterStore::save)
            val submission = if (events.isEmpty()) null else {
                dao.recordAttempt(events.map { it.eventId }, System.currentTimeMillis())
                client.submit(events)
            }
            val serverState = submission?.serverState ?: client.syncState()
            DeviceConfigurationStore(applicationContext).save(
                configuration.copy(
                    branchId = serverState.branchId ?: configuration.branchId,
                    gateId = serverState.gateId ?: configuration.gateId,
                    directionMode = serverState.directionMode ?: configuration.directionMode,
                    assignmentVersion = serverState.assignmentVersion ?: configuration.assignmentVersion,
                    serverTimeEpochMillis = serverState.serverTimeEpochMillis,
                    elapsedAtServerTimeMillis = SystemClock.elapsedRealtime(),
                    authorizationExpiresAtEpochMillis = serverState.authorizationExpiresAtEpochMillis,
                ),
            )
            val now = System.currentTimeMillis()
            submission?.acknowledgements?.forEach { acknowledgement ->
                val state = when (acknowledgement.status) {
                    "accepted", "duplicate" -> SyncState.ACKNOWLEDGED
                    "quarantined" -> SyncState.QUARANTINED
                    "rejected" -> SyncState.REJECTED
                    else -> return@forEach
                }
                dao.resolve(acknowledgement.eventId, state.name, now, acknowledgement.reason)
            }
            dao.deleteAcknowledgedBefore(now - TimeUnit.DAYS.toMillis(30))
            if (dao.pendingCount() > 0) Result.retry() else Result.success()
        } catch (error: ApiException) {
            if (error.statusCode in 400..499 && error.statusCode != 408 && error.statusCode != 429) {
                Result.failure()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
