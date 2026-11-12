package com.wifigatekeeper.core.router

/**
 * Applies the protection sequence in the only safe order:
 * 1. add THIS phone to the router's allow list (so the phone itself survives),
 * 2. only then switch MAC filtering on.
 *
 * [onStep] reports progress so UIs can show each stage as it happens.
 */
object ProtectionApplier {

    enum class Step { WHITELIST, ENABLE }

    sealed interface Outcome {
        /** Phone whitelisted + filtering on. */
        data class FullyApplied(val label: String) : Outcome

        /** Phone whitelisted, but the filter switch failed - manual step left. */
        data class WhitelistOnly(val label: String, val reason: String) : Outcome

        /** Nothing was changed; the router refused the whitelist entry. */
        data class Failed(val message: String) : Outcome
    }

    suspend fun apply(
        client: RouterClient,
        mac: String,
        label: String,
        onStep: (Step) -> Unit = { },
    ): Outcome {
        onStep(Step.WHITELIST)
        client.addToWhitelist(mac, label).fold(
            onSuccess = { },
            onFailure = {
                return Outcome.Failed("Could not add this phone to the whitelist: ${it.message}")
            },
        )
        onStep(Step.ENABLE)
        client.setMacFilteringEnabled(true).fold(
            onSuccess = { },
            onFailure = {
                return Outcome.WhitelistOnly(label, it.message ?: "enabling MAC filtering failed")
            },
        )
        return Outcome.FullyApplied(label)
    }
}
