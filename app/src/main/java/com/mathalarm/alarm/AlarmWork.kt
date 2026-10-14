package com.mathalarm.alarm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime scope for the small database writes that outlive the component that triggered
 * them — notably auto-disabling a one-shot alarm while [AlarmService] is already shutting down.
 * Tying that write to the service's own scope would cancel it mid-flight.
 */
internal object AlarmWork {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
