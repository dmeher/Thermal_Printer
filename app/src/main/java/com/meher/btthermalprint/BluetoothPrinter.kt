package com.meher.btthermalprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import java.io.Closeable
import java.io.OutputStream
import java.util.UUID

class BluetoothPrinter(private val device: BluetoothDevice) : Closeable {
    private var output: OutputStream? = null
    private var socketCloseable: Closeable? = null

    @SuppressLint("MissingPermission")
    fun connect(adapter: BluetoothAdapter) {
        adapter.cancelDiscovery()
        val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        socket.connect()
        socketCloseable = socket
        output = socket.outputStream
    }

    fun write(bytes: ByteArray) {
        val stream = output ?: error("Printer is not connected")
        stream.write(bytes)
        stream.flush()
    }

    override fun close() {
        runCatching { output?.close() }
        runCatching { socketCloseable?.close() }
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
