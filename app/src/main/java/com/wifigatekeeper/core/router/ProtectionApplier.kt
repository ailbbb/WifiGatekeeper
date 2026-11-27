package com.wifigatekeeper.core.router

/**
 * Applies the protection sequence in the only safe order:
 * 1. add THIS phone to the router's allow list (so the phone itself survives),
 * 2. only then switch MAC filtering on - and ONLY when the phone's MAC was
 *    confirmed visible on the router, so the whitelist can never miss it.
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

    /**
     * Runs the sequence. [macConfirmed] must be true only when the router's own
     * device list contained this MAC (matched by IP or listed directly) or the
     * user picked the device from that list by hand - never for a bare sysfs
     * read, which reports the hardware MAC while Android 10+ presents a
     * randomized one to the network.
     */
    suspend fun apply(
        client: RouterClient,
        mac: String,
        label: String,
        macConfirmed: Boolean = true,
        onStep: (Step) -> Unit = { },
    ): Outcome {
        if (!macConfirmed) {
            return Outcome.Failed(
                "This phone's MAC could not be confirmed in the router's device list " +
                    "(Android usually randomizes Wi-Fi MACs). Pick this phone from the " +
                    "connected-devices list or type its MAC manually - filtering stays OFF " +
                    "until the whitelist is certain.",
            )
        }
        onStep(Step.WHITELIST)
        client.addToWhitelist(mac, label).fold(
            onSuccess = { },
            onFailure = {
                return Outcome.Failed("Could not add this phone to the whitelist: ${it.message}")
            },
        )
        // Belt and suspenders: re-read the router's whitelist before the switch.
        client.fetchWhitelistedMacAddresses().fold(
            onSuccess = { list ->
                if (mac !in list && list.isNotEmpty()) {
                    return Outcome.Failed(
                        "The router's whitelist does not show $mac after adding it - " +
                            "verify it from the router page before enabling filtering.",
                    )
                }
            },
            // A failed re-read does not veto: per-client add already verified.
            onFailure = { },
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
