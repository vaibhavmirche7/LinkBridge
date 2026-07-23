package com.vaibhavmirche.linkbridge.util

/**
 * A dropdown adapter showing labeled IP entries in the list, but only the raw value when closed.
 */

data class IpEntry(
    val label: String,
    val value: String
) {
    override fun toString(): String = value
}
