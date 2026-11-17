package app.ikev2manager.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

interface NetworkStatus {
    /** True when a non-VPN network with internet capability exists (false in airplane mode). */
    fun isOnline(): Boolean
}

class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    override fun isOnline(): Boolean = cm.allNetworks.any { n ->
        val c = cm.getNetworkCapabilities(n)
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun observe(): Flow<Boolean> = callbackFlow {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isOnline()) }
            override fun onLost(network: Network) { trySend(isOnline()) }
        }
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, cb)
        trySend(isOnline())
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()
}
