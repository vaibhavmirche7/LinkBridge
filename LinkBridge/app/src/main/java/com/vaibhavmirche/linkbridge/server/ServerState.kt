package com.vaibhavmirche.linkbridge.server

sealed class ServerState {
    object Starting : ServerState()
    data class Running(val hosts: NetworkInfo, val port: Int) : ServerState()
    object UserStopped : ServerState()
    object AwaitNetwork: ServerState()
    data class Error(val message: String) : ServerState()
}