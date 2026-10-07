package io.github.marcomaes.mobiletestbench.core.ios

/**
 * A physical iPhone needs mobilecli's on-device agent before screenshots or input work. mobilecli's own error names the command
 * with an empty profile; this replaces it with the whole setup. Other messages pass through unchanged.
 */
object AgentInstallHint {

    private const val MARKER = "agent is not installed"

    fun rewrite(message: String, deviceId: String): String =
        if (MARKER in message) text(deviceId) else message

    fun text(deviceId: String): String =
        "The mobilecli agent is not installed on this iPhone. Install it: " +
            "mobilecli agent install --device $deviceId --provisioning-profile <file.mobileprovision> " +
            "(see mobilecli agent install --help for the agent's bundle id and signing options). " +
            "The profile must be a development profile that matches the agent's bundle id and includes this iPhone's UDID. " +
            "Developer Mode must be on (Settings > Privacy & Security) and the iPhone unlocked and trusted. " +
            "After the install, trust the developer certificate (Settings > General > VPN & Device Management), then reconnect the device."
}
