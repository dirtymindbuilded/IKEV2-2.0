package app.ikev2manager.domain

enum class FailureCategory(val label: String) {
    DNS("DNS failure"),
    AUTH("Authentication / identity failure"),
    IKE_NEGOTIATION("IKE negotiation failure"),
    CERTIFICATE("Certificate failure"),
    TIMEOUT("Connection timeout"),
    NO_INTERNET("VPN established, no internet"),
    NETWORK_UNAVAILABLE("Network unavailable"),
    USER_CANCELLED("Cancelled by user"),
    UNSUPPORTED("Unsupported"),
    UNKNOWN("Unknown error"),
}

/**
 * Maps Android's VpnManager event broadcasts to failure categories.
 * The category strings and error codes below follow the AOSP VpnManager / IKE RFC 7296 values;
 * they must be confirmed on a real device (see docs/ARCHITECTURE.md, "Unverified assumptions").
 * Android does not distinguish a bad certificate from bad credentials: both surface as IKE AUTHENTICATION_FAILED,
 * so the native engine never emits CERTIFICATE.
 */
object ErrorClassifier {
    const val CATEGORY_IKE_ERROR = "android.net.category.EVENT_IKE_ERROR"
    const val CATEGORY_NETWORK_ERROR = "android.net.category.EVENT_NETWORK_ERROR"
    const val CATEGORY_DEACTIVATED = "android.net.category.EVENT_DEACTIVATED_BY_USER"

    const val IKE_AUTHENTICATION_FAILED = 24
    const val NETWORK_UNKNOWN_HOST = 0
    const val NETWORK_PROTOCOL_TIMEOUT = 1

    fun classifyEvent(category: String?, code: Int?): FailureCategory = when (category) {
        CATEGORY_DEACTIVATED -> FailureCategory.USER_CANCELLED
        CATEGORY_IKE_ERROR -> when (code) {
            null -> FailureCategory.UNKNOWN
            IKE_AUTHENTICATION_FAILED -> FailureCategory.AUTH
            else -> FailureCategory.IKE_NEGOTIATION
        }
        CATEGORY_NETWORK_ERROR -> when (code) {
            NETWORK_UNKNOWN_HOST -> FailureCategory.DNS
            NETWORK_PROTOCOL_TIMEOUT -> FailureCategory.TIMEOUT
            else -> FailureCategory.NETWORK_UNAVAILABLE
        }
        else -> FailureCategory.UNKNOWN
    }

    /** Fail fast only on errors that retrying cannot fix. */
    fun isTerminal(c: FailureCategory?): Boolean =
        c == FailureCategory.AUTH || c == FailureCategory.DNS || c == FailureCategory.USER_CANCELLED

    fun explain(c: FailureCategory): String = when (c) {
        FailureCategory.AUTH ->
            "Server rejected the credentials, or it requires an authentication method this app does not support " +
                "(only EAP-MSCHAPv2 username/password). Certificate or identity mismatches look the same on Android."
        FailureCategory.IKE_NEGOTIATION ->
            "IKE negotiation failed. Android only allows modern algorithms; older servers (3DES, MODP1024...) are rejected."
        FailureCategory.DNS -> "The hostname could not be resolved."
        FailureCategory.TIMEOUT -> "No response from the server before the timeout (UDP 500/4500 blocked or server down)."
        FailureCategory.NO_INTERNET -> "The tunnel came up but no traffic passed through it."
        FailureCategory.NETWORK_UNAVAILABLE -> "No usable underlying network."
        FailureCategory.USER_CANCELLED -> "VPN permission was not granted or the VPN was disconnected by the user."
        FailureCategory.CERTIFICATE -> "Server certificate was not accepted."
        FailureCategory.UNSUPPORTED -> "Not supported by this engine."
        FailureCategory.UNKNOWN -> "Unknown error."
    }
}
