package com.vaibhavmirche.linkbridge.server

/**
 * A device that has been granted access to this server.
 *
 * [name] defaults to the IP address until the client provides a friendlier
 * name (web clients now supply this automatically via their web-login username).
 */
data class ConnectedDevice(
    val ip: String,
    val name: String,
    val connectedAt: Long,
    val expiresAt: Long
)
