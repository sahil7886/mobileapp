package coredevices.coreapp

import coredevices.libindex.IndexDevices
import coredevices.libindex.LibIndex
import coredevices.libindex.device.IndexDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Keeps the Pebble navigation graph available while excluding the Index/Ring product. */
class NoOpLibIndex : LibIndex {
    override val isScanning: StateFlow<Boolean> = MutableStateFlow(false)
    override val rings: IndexDevices = MutableStateFlow(emptyList<IndexDevice>())

    override fun init(bluetoothPermissionChanged: Flow<Boolean>) = Unit
    override fun startScan() = Unit
    override fun stopScan() = Unit
    override fun warnIfNoCompanionAssociations() = Unit
}
