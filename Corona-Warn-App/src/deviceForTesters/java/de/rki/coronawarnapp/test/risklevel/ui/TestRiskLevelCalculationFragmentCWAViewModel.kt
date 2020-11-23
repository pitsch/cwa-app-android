package de.rki.coronawarnapp.test.risklevel.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asLiveData
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import de.rki.coronawarnapp.appconfig.AppConfigProvider
import de.rki.coronawarnapp.appconfig.ConfigData
import de.rki.coronawarnapp.diagnosiskeys.download.DownloadDiagnosisKeysTask
import de.rki.coronawarnapp.diagnosiskeys.storage.KeyCacheRepository
import de.rki.coronawarnapp.exception.ExceptionCategory
import de.rki.coronawarnapp.exception.reporting.report
import de.rki.coronawarnapp.risk.RiskLevel
import de.rki.coronawarnapp.risk.RiskLevelTask
import de.rki.coronawarnapp.risk.TimeVariables
import de.rki.coronawarnapp.risk.result.AggregatedRiskResult
import de.rki.coronawarnapp.risk.storage.RiskLevelStorage
import de.rki.coronawarnapp.storage.AppDatabase
import de.rki.coronawarnapp.storage.LocalData
import de.rki.coronawarnapp.storage.SubmissionRepository
import de.rki.coronawarnapp.task.TaskController
import de.rki.coronawarnapp.task.common.DefaultTaskRequest
import de.rki.coronawarnapp.task.submitBlocking
import de.rki.coronawarnapp.ui.tracing.card.TracingCardStateProvider
import de.rki.coronawarnapp.ui.tracing.common.tryLatestResultsWithDefaults
import de.rki.coronawarnapp.util.NetworkRequestWrapper.Companion.withSuccess
import de.rki.coronawarnapp.util.coroutine.DispatcherProvider
import de.rki.coronawarnapp.util.di.AppContext
import de.rki.coronawarnapp.util.security.SecurityHelper
import de.rki.coronawarnapp.util.ui.SingleLiveEvent
import de.rki.coronawarnapp.util.viewmodel.CWAViewModel
import de.rki.coronawarnapp.util.viewmodel.CWAViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import org.joda.time.Instant
import timber.log.Timber
import java.util.Date
import java.util.concurrent.TimeUnit

class TestRiskLevelCalculationFragmentCWAViewModel @AssistedInject constructor(
    @Assisted private val handle: SavedStateHandle,
    @Assisted private val exampleArg: String?,
    @AppContext private val context: Context, // App context
    dispatcherProvider: DispatcherProvider,
    private val taskController: TaskController,
    private val keyCacheRepository: KeyCacheRepository,
    private val appConfigProvider: AppConfigProvider,
    tracingCardStateProvider: TracingCardStateProvider,
    private val riskLevelStorage: RiskLevelStorage
) : CWAViewModel(
    dispatcherProvider = dispatcherProvider
) {

    init {
        Timber.d("CWAViewModel: %s", this)
        Timber.d("SavedStateHandle: %s", handle)
        Timber.d("Example arg: %s", exampleArg)
    }

    val riskLevelResetEvent = SingleLiveEvent<Unit>()

    val showRiskStatusCard = SubmissionRepository.deviceUIStateFlow.map {
        it.withSuccess(false) { true }
    }.asLiveData(dispatcherProvider.Default)

    val tracingCardState = tracingCardStateProvider.state
        .sample(150L)
        .asLiveData(dispatcherProvider.Default)

    val exposureWindowCountString = riskLevelStorage
        .exposureWindows
        .map { "Retrieved ${it.size} Exposure Windows" }
        .asLiveData()

    val exposureWindows = riskLevelStorage
        .exposureWindows
        .map { if (it.isEmpty()) "Exposure windows list is empty" else it.toString() }
        .asLiveData()

    val aggregatedRiskResult = riskLevelStorage
        .riskLevelResults
        .map {
            val latest = it.maxByOrNull { it.calculatedAt }
            if (latest?.aggregatedRiskResult != null) {
                latest.aggregatedRiskResult?.toReadableString()
            } else {
                "Aggregated risk result is not available"
            }
        }
        .asLiveData()

    private fun AggregatedRiskResult.toReadableString(): String = StringBuilder()
        .appendLine("Total RiskLevel: $totalRiskLevel")
        .appendLine("Total Minimum Distinct Encounters With High Risk: $totalMinimumDistinctEncountersWithHighRisk")
        .appendLine("Total Minimum Distinct Encounters With Low Risk: $totalMinimumDistinctEncountersWithLowRisk")
        .appendLine("Most Recent Date With High Risk: $mostRecentDateWithHighRisk")
        .appendLine("Most Recent Date With Low Risk: $mostRecentDateWithLowRisk")
        .appendLine("Number of Days With High Risk: $numberOfDaysWithHighRisk")
        .appendLine("Number of Days With Low Risk: $numberOfDaysWithLowRisk")
        .toString()

    val backendParameters = appConfigProvider
        .currentConfig
        .map { it.toReadableString() }
        .asLiveData()

    private fun ConfigData.toReadableString(): String = StringBuilder()
        .appendLine("Transmission RiskLevel Multiplier: $transmissionRiskLevelMultiplier")
        .appendLine()
        .appendLine("Minutes At Attenuation Filters:")
        .appendLine(minutesAtAttenuationFilters)
        .appendLine()
        .appendLine("Minutes At Attenuation Weights:")
        .appendLine(minutesAtAttenuationWeights)
        .appendLine()
        .appendLine("Transmission RiskLevel Encoding:")
        .appendLine(transmissionRiskLevelEncoding)
        .appendLine()
        .appendLine("Transmission RiskLevel Filters:")
        .appendLine(transmissionRiskLevelFilters)
        .appendLine()
        .appendLine("Normalized Time Per Exposure Window To RiskLevel Mapping:")
        .appendLine(normalizedTimePerExposureWindowToRiskLevelMapping)
        .appendLine()
        .appendLine("Normalized Time Per Day To RiskLevel Mapping List:")
        .appendLine(normalizedTimePerDayToRiskLevelMappingList)
        .toString()

    val additionalRiskCalcInfo = combine(
        riskLevelStorage.riskLevelResults,
        LocalData.lastTimeDiagnosisKeysFromServerFetchFlow()
    ) { riskLevelResults, lastTimeDiagnosisKeysFromServerFetch ->

        val (latestCalc, latestSuccessfulCalc) = riskLevelResults.tryLatestResultsWithDefaults()


        createAdditionalRiskCalcInfo(
            latestCalc.calculatedAt,
            riskLevelScore = latestCalc.riskLevel.raw,
            riskLevelScoreLastSuccessfulCalculated = latestSuccessfulCalc.riskLevel.raw,
            matchedKeyCount = latestCalc.matchedKeyCount,
            daysSinceLastExposure = latestCalc.daysSinceLastExposure,
            lastTimeDiagnosisKeysFromServerFetch = lastTimeDiagnosisKeysFromServerFetch
        )
    }.asLiveData()

    private suspend fun createAdditionalRiskCalcInfo(
        lastTimeRiskLevelCalculation: Instant,
        riskLevelScore: Int,
        riskLevelScoreLastSuccessfulCalculated: Int,
        matchedKeyCount: Int,
        daysSinceLastExposure: Int,
        lastTimeDiagnosisKeysFromServerFetch: Date?
    ): String = StringBuilder()
        .appendLine("Risk Level: ${RiskLevel.forValue(riskLevelScore)}")
        .appendLine("Last successful Risk Level: ${RiskLevel.forValue(riskLevelScoreLastSuccessfulCalculated)}")
        .appendLine("Matched key count: $matchedKeyCount")
        .appendLine("Days since last Exposure: $daysSinceLastExposure days")
        .appendLine("Last Time Server Fetch: ${lastTimeDiagnosisKeysFromServerFetch?.time?.let { Instant.ofEpochMilli(it) }}")
        .appendLine("Tracing Duration: ${TimeUnit.MILLISECONDS.toDays(TimeVariables.getTimeActiveTracingDuration())} days")
        .appendLine("Tracing Duration in last 14 days: ${TimeVariables.getActiveTracingDaysInRetentionPeriod()} days")
        .appendLine("Last time risk level calculation $lastTimeRiskLevelCalculation")
        .toString()

    fun retrieveDiagnosisKeys() {
        Timber.d("Starting download diagnosis keys task")
        launch {
            taskController.submitBlocking(
                DefaultTaskRequest(
                    DownloadDiagnosisKeysTask::class,
                    DownloadDiagnosisKeysTask.Arguments(),
                    originTag = "TestRiskLevelCalculationFragmentCWAViewModel.retrieveDiagnosisKeys()"
                )
            )
        }
    }

    fun calculateRiskLevel() {
        Timber.d("Starting calculate risk task")
        taskController.submit(
            DefaultTaskRequest(
                RiskLevelTask::class,
                originTag = "TestRiskLevelCalculationFragmentCWAViewModel.calculateRiskLevel()"
            )
        )
    }

    fun resetRiskLevel() {
        Timber.d("Resetting risk level")
        launch {
            withContext(Dispatchers.IO) {
                try {
                    // Preference reset
                    SecurityHelper.resetSharedPrefs()
                    // Database Reset
                    AppDatabase.reset(context)
                    // Export File Reset
                    keyCacheRepository.clear()

                    riskLevelStorage.clear()

                    LocalData.lastTimeDiagnosisKeysFromServerFetch(null)
                } catch (e: Exception) {
                    e.report(ExceptionCategory.INTERNAL)
                }
            }
            taskController.submit(DefaultTaskRequest(RiskLevelTask::class))
            riskLevelResetEvent.postValue(Unit)
        }
    }

    fun clearKeyCache() {
        Timber.d("Clearing key cache")
        launch { keyCacheRepository.clear() }
    }

    @AssistedInject.Factory
    interface Factory : CWAViewModelFactory<TestRiskLevelCalculationFragmentCWAViewModel> {
        fun create(
            handle: SavedStateHandle,
            exampleArg: String?
        ): TestRiskLevelCalculationFragmentCWAViewModel
    }
}
