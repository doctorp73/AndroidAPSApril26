package app.aaps.implementation.maintenance

import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

class MaintenanceImplTest : TestBaseWithProfile() {

    @Mock lateinit var nsSettingsStatus: NSSettingsStatus
    @Mock lateinit var loggerUtils: LoggerUtils
    @Mock lateinit var fileListProvider: FileListProvider
    @Mock lateinit var cloudStorageManager: CloudStorageManager

    private lateinit var sut: MaintenanceImpl

    @BeforeEach
    fun mock() {
        sut = MaintenanceImpl(context, rh, preferences, nsSettingsStatus, aapsLogger, config, fileListProvider, loggerUtils, cloudStorageManager)
        whenever(loggerUtils.suffix).thenReturn(".log.zip")
        whenever(loggerUtils.logDirectory).thenReturn("src/test/assets/logger")
    }

    @Test fun logFilesTest() {
        var logs = sut.getLogFiles(2)
        // getEversenseLogFiles() is capped by the same amount independently of the AndroidAPS
        // files (see its own doc comment), so Eversense.log is included here too even though
        // amount=2 already exhausted the AndroidAPS quota.
        assertThat(logs.map { it.name }).containsExactly(
            "AndroidAPS.log",
            "AndroidAPS.2018-01-03_01-01-00.1.zip",
            "Eversense.log",
        ).inOrder()
        logs = sut.getLogFiles(10)
        // 4 AndroidAPS files + 1 Eversense.log (src/test/assets/logger/eversense/) - see the
        // getEversenseLogFiles regression test below for why that file is included at all.
        assertThat(logs).hasSize(5)
    }

    @Test fun `getLogFiles includes Eversense's own log file from its eversense subdirectory`() {
        // Regression test: getEversenseLogFiles() previously joined loggerUtils.logDirectory with
        // "AndroidAPS/eversense" - doubling the "AndroidAPS" segment, since logDirectory is
        // already "/sdcard/AndroidAPS" - pointing at a directory that never existed, so
        // Eversense.log silently never made it into any log export.
        val logs = sut.getLogFiles(10)

        assertThat(logs.map { it.name }).contains("Eversense.log")
    }
}
