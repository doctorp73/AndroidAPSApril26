package app.aaps.pump.omnipod.common.di

import app.aaps.core.interfaces.di.PumpDriver
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.pump.omnipod.common.O5PumpPlugin
import app.aaps.pump.omnipod.common.bledriver.comm.O5BleManager
import app.aaps.pump.omnipod.common.bledriver.comm.O5BleManagerImpl
import app.aaps.pump.omnipod.common.bledriver.pod.security.AndroidKeystoreAesCipher
import app.aaps.pump.omnipod.common.bledriver.pod.security.O5RegistrationCipher
import app.aaps.pump.omnipod.common.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.common.bledriver.pod.state.PersistedO5PodStateManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntKey
import dagger.multibindings.IntoMap

/**
 * Dagger wiring for Omnipod 5, mirroring
 * [app.aaps.pump.omnipod.dash.di.OmnipodDashModule]'s manager/state bindings, now
 * including the pump plugin registration itself (see [O5PumpPlugin] for the `Pump`-
 * interface implementation this module previously lacked).
 *
 * [SecureO5RegistrationStorage][app.aaps.pump.omnipod.common.bledriver.pod.security
 * .SecureO5RegistrationStorage]'s `loadAndInstallAll()` is called from
 * [O5BleManagerImpl][app.aaps.pump.omnipod.common.bledriver.comm.O5BleManagerImpl]'s
 * `init` block, since that class must be constructed before any pairing/connection code
 * can run - this avoids needing a separate, broader app-startup hook. There's still no
 * settings UI that could actually produce an imported credential for it to load, though.
 *
 * [O5BleConnectionFactory][app.aaps.pump.omnipod.common.bledriver.comm.legacy
 * .O5BleConnectionFactory] and [P256KeyGenerator][app.aaps.pump.omnipod.common.bledriver
 * .pod.util.P256KeyGenerator] need no `@Binds` here - both are injected by their concrete
 * type (not behind an interface) throughout this codebase, so Dagger constructs them
 * directly from their own `@Inject` constructors. [BleDeviceManager] is shared with Dash
 * and already bound by [OmnipodCommonBleModule], included below for safety in case this
 * module is ever installed without Dash's.
 */
@Module(includes = [OmnipodCommonBleModule::class])
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class O5Module {

    @Binds
    abstract fun bindsO5BleManagerImpl(bleManager: O5BleManagerImpl): O5BleManager

    @Binds
    abstract fun bindsPersistedO5PodStateManager(podStateManager: PersistedO5PodStateManager): O5PodStateManager

    @Binds
    abstract fun bindsAndroidKeystoreAesCipher(cipher: AndroidKeystoreAesCipher): O5RegistrationCipher

    // Pump plugin registration — @IntKey range 1000–1200, see PluginsListModule for overview.
    // 1140 confirmed free: 1000 VirtualPump, 1010/1020/1030 DanaR, 1040 DanaRS, 1050 Insight,
    // 1060 ComboV2, 1070 Eros, 1080 Dash, 1090 Medtronic, 1100 Diaconn, 1110 Eopatch, 1120 Medtrum,
    // 1130 Equil.
    @Binds
    @PumpDriver
    @IntoMap
    @IntKey(1140)
    abstract fun bindO5PumpPlugin(plugin: O5PumpPlugin): PluginBase
}
