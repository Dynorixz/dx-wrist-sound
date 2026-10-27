package dev.dx.wristsound.shared

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

class LocalWearTransport(context: Context, private val peerCapability: String) {
    private val capabilities = Wearable.getCapabilityClient(context)
    private val messages = Wearable.getMessageClient(context)
    private val nodes = Wearable.getNodeClient(context)
    suspend fun nearbyNodes(): List<Node> = withTimeout(5000) {
        nodes.connectedNodes.await().filter { it.isNearby }
    }
    suspend fun nearbyPeers(): List<Node> = withTimeout(5000) {
        capabilities.getCapability(peerCapability, CapabilityClient.FILTER_REACHABLE)
            .await().nodes.filter { it.isNearby }.sortedBy { it.id }
    }
    suspend fun trustedPeer(nodeId: String): Node? = nearbyPeers().firstOrNull { it.id == nodeId }
    suspend fun send(nodeId: String, path: String, bytes: ByteArray) {
        check(trustedPeer(nodeId) != null) { "Устройство больше не доступно рядом" }
        withTimeout(5000) { messages.sendMessage(nodeId, path, bytes).await() }
    }
}
