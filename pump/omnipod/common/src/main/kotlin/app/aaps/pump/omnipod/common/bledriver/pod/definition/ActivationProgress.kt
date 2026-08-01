package app.aaps.pump.omnipod.common.bledriver.pod.definition

enum class ActivationProgress {
    NOT_STARTED,

    /** O5-only: the AID setup command batch (see [app.aaps.pump.omnipod.common.bledriver
     *  .pod.command.aid.O5AidSetupCommands]) has been sent. Must happen right after
     *  pairing/session establishment and before anything else in activation - matches
     *  OmnipodKit's own ordering ("after AssignAddress in pairPod(), before SetupPod in
     *  setupPod()"). Dash's activation flow skips straight past this state (it has no
     *  such requirement), so it costs Dash nothing to have this ordinal exist. */
    AID_SETUP,
    GOT_POD_VERSION,
    SET_UNIQUE_ID,
    PROGRAMMED_LOW_RESERVOIR_ALERTS,
    REPROGRAMMED_LUMP_OF_COAL_ALERT,
    PRIMING,
    PRIME_COMPLETED,
    PHASE_1_COMPLETED,
    PROGRAMMED_BASAL,
    UPDATED_EXPIRATION_ALERTS,
    INSERTING_CANNULA,
    CANNULA_INSERTED,
    COMPLETED;

    fun isBefore(other: ActivationProgress): Boolean = ordinal < other.ordinal

    fun isAtLeast(other: ActivationProgress): Boolean = ordinal >= other.ordinal
}
