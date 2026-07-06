package app.aaps.pump.omnipod.common.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/**
 * Preference key(s) for persisting Omnipod 5 pod state, mirroring [DashStringNonPreferenceKey].
 */
enum class O5StringNonPreferenceKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    PodState("AAPS.Omnipod5.pod_state", ""),
}
