package app.aaps.pump.omnipod.common.di

import app.aaps.pump.omnipod.common.bledriver.comm.O5BleManager
import app.aaps.pump.omnipod.common.bledriver.comm.O5BleManagerImpl
import app.aaps.pump.omnipod.common.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.common.bledriver.pod.state.PersistedO5PodStateManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Dagger wiring for Omnipod 5, mirroring
 * [app.aaps.pump.omnipod.dash.di.OmnipodDashModule]'s manager/state bindings.
 *
 * Deliberately does NOT yet register a pump plugin (`@PumpDriver @IntoMap @IntKey(...)`
 * binding to a `PluginBase`) - no `Pump`-interface implementation for O5 exists yet (that's
 * the dosing/control layer: bolus, basal, temp basal, status display, etc.), so there is
 * nothing to bind there. This module makes the pairing/session/command/persistence layers
 * built so far reachable via injection; it's a deliberately incomplete step, not a finished
 * plugin registration.
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
}
