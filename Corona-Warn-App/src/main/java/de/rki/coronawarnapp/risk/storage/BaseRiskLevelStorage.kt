package de.rki.coronawarnapp.risk.storage

import com.google.android.gms.nearby.exposurenotification.ExposureWindow
import de.rki.coronawarnapp.exception.ExceptionCategory
import de.rki.coronawarnapp.exception.reporting.report
import de.rki.coronawarnapp.risk.RiskLevel
import de.rki.coronawarnapp.risk.RiskLevelResult
import de.rki.coronawarnapp.risk.result.AggregatedRiskResult
import de.rki.coronawarnapp.risk.storage.internal.RiskResultDatabase
import de.rki.coronawarnapp.risk.storage.internal.riskresults.toPersistedRiskResult
import de.rki.coronawarnapp.risk.storage.legacy.RiskLevelResultMigrator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.joda.time.Instant
import timber.log.Timber

abstract class BaseRiskLevelStorage constructor(
    private val riskResultDatabaseFactory: RiskResultDatabase.Factory,
    private val riskLevelResultMigrator: RiskLevelResultMigrator
) : RiskLevelStorage {

    private val database by lazy { riskResultDatabaseFactory.create() }
    internal val riskResultsTables by lazy { database.riskResults() }
    internal val exposureWindowsTables by lazy { database.exposureWindows() }

    abstract val storedResultLimit: Int

    override val exposureWindows: Flow<List<ExposureWindow>> = exposureWindowsTables.allEntries().map { windows ->
        windows.map { it.toExposureWindow() }
    }

    final override val riskLevelResults: Flow<List<RiskLevelResult>> = riskResultsTables.allEntries()
        .map { latestResults ->
            latestResults.map { it.toRiskResult() }
        }
        .map { results ->
            if (results.isEmpty()) {
                riskLevelResultMigrator.getLegacyResults()
            } else {
                results
            }
        }

    override val lastRiskLevelResult: Flow<RiskLevelResult> = riskLevelResults.map { results ->
        results.maxByOrNull { it.calculatedAt } ?: INITIAL_RESULT
    }

    override suspend fun getLatestResults(limit: Int): List<RiskLevelResult> = riskLevelResults.first()
        .sortedBy { it.calculatedAt }
        .takeLast(2)

    override suspend fun storeResult(result: RiskLevelResult) {
        Timber.d("Storing result (exposureWindows.size=%s)", result.exposureWindows?.size)

        val storedresultId = try {
            val startTime = System.currentTimeMillis()

            val resultToPersist = result.toPersistedRiskResult()
            riskResultsTables.insertEntry(resultToPersist).also {
                Timber.d("Storing RiskLevelResult took %dms.", (System.currentTimeMillis() - startTime))
            }

            resultToPersist.id
        } catch (e: Exception) {
            Timber.e(e, "Failed to store latest result: %s", result)
            e.report(ExceptionCategory.INTERNAL)
            // Can't continue without an inserted result
            return
        }

        try {
            Timber.d("Cleaning up old results.")

            riskResultsTables.deleteOldest(storedResultLimit).also {
                Timber.d("$it old results were deleted.")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to clean up old results.")
            e.report(ExceptionCategory.INTERNAL)
        }

        Timber.d("Storing exposure windows.")
        storeExposureWindows(storedResultId = storedresultId, result)

        Timber.d("Deleting orphaned exposure windows.")
        deletedOrphanedExposureWindows()
    }

    internal abstract suspend fun storeExposureWindows(storedResultId: String, result: RiskLevelResult)

    internal abstract suspend fun deletedOrphanedExposureWindows()

    override suspend fun clear() {
        Timber.w("clear() - Clearing stored riskleve/exposure-detection results.")
        database.clearAllTables()
    }

    companion object {
        private val INITIAL_RESULT = object : RiskLevelResult {
            override val riskLevel: RiskLevel = RiskLevel.LOW_LEVEL_RISK
            override val calculatedAt: Instant = Instant.EPOCH
            override val aggregatedRiskResult: AggregatedRiskResult? = null
            override val exposureWindows: List<ExposureWindow>? = null

            override val isIncreasedRisk: Boolean = false
            override val matchedKeyCount: Int = 0
            override val daysSinceLastExposure: Int = 0
        }
    }
}
