package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.keys.DoubleKey
import app.aaps.core.objects.constraints.ConstraintObject
import org.junit.jupiter.api.AfterEach
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

class OpenAPSSMBPluginTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var determineBasalSMB: DetermineBasalSMB
    @Mock lateinit var bgQualityCheck: BgQualityCheck
    @Mock lateinit var ads: AutosensDataStore
    @Mock lateinit var tddCalculator: TddCalculator
    @Mock lateinit var profiler: Profiler
    private lateinit var openAPSSMBPlugin: OpenAPSSMBPlugin

    @BeforeEach fun prepare() {
        openAPSSMBPlugin = OpenAPSSMBPlugin(
            aapsLogger, rxBus, constraintChecker, rh, profileFunction, profileUtil, config, activePlugin,
            iobCobCalculator, hardLimits, preferences, dateUtil, processedTbrEbData, persistenceLayer, glucoseStatusProvider,
            tddCalculator, bgQualityCheck, notificationManager, determineBasalSMB, profiler, GlucoseStatusCalculatorSMB(aapsLogger, iobCobCalculator, dateUtil, decimalFormatter, deltaCalculator), apsResultProvider, ch,
            fabricPrivacy
        )
    }

    @Test
    fun specialEnableConditionTest() {
        assertThat(openAPSSMBPlugin.specialEnableCondition()).isTrue()
    }

    @Test
    fun specialShowInListConditionTest() {
        assertThat(openAPSSMBPlugin.specialShowInListCondition()).isTrue()
    }



}
