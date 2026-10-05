package com.claytonmuhoza.easybilling.platform.printing

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.claytonmuhoza.easybilling.domain.document.PairedPrinter
import com.claytonmuhoza.easybilling.domain.document.PrintOutcome
import com.claytonmuhoza.easybilling.domain.document.PrinterLookup
import com.claytonmuhoza.easybilling.domain.document.Receipt
import com.claytonmuhoza.easybilling.domain.document.ReceiptPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Prints tickets on a Bluetooth printer the device is already paired with (UC-A09, UC-G01), over
 * the serial port profile every ESC/POS printer offers. The application never searches for
 * devices: pairing is done in the settings of Android, so only the right to connect is needed.
 *
 * @param context the application.
 * @param openSocket opens the serial link to a printer; replaced in the tests.
 */
class BluetoothReceiptPrinter(
    private val context: Context,
    private val openSocket: (BluetoothDevice) -> BluetoothSocket = ::serialSocket,
) : ReceiptPrinter {

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    // The permission is checked by allowed(), and a refusal is caught as a SecurityException.
    @SuppressLint("MissingPermission")
    override fun paired(): PrinterLookup {
        val bluetooth = adapter?.takeIf { it.isEnabled } ?: return PrinterLookup.BluetoothOff
        return try {
            if (!allowed()) {
                PrinterLookup.PermissionMissing
            } else {
                val printers = bluetooth.bondedDevices.orEmpty()
                    .map { PairedPrinter(it.address, it.name ?: it.address) }
                PrinterLookup.Found(printers.sortedBy { it.name.lowercase() })
            }
        } catch (_: SecurityException) {
            PrinterLookup.PermissionMissing
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun print(address: String, receipt: Receipt): PrintOutcome = withContext(Dispatchers.IO) {
        val bluetooth = adapter?.takeIf { it.isEnabled }
        when {
            bluetooth == null -> PrintOutcome.BLUETOOTH_OFF
            !allowed() -> PrintOutcome.PERMISSION_MISSING
            else -> try {
                openSocket(bluetooth.getRemoteDevice(address)).use { socket ->
                    socket.connect()
                    socket.outputStream.apply {
                        write(EscPos.encode(receipt))
                        flush()
                    }
                    // Closing at once cuts the end of the ticket on some printers.
                    delay(DRAIN_MILLIS)
                }
                PrintOutcome.PRINTED
            } catch (_: IOException) {
                PrintOutcome.UNREACHABLE
            } catch (_: IllegalArgumentException) {
                // The stored address is not one: the printer cannot be reached.
                PrintOutcome.UNREACHABLE
            } catch (_: SecurityException) {
                PrintOutcome.PERMISSION_MISSING
            }
        }
    }

    /** @return whether the application may connect to paired devices; asked for since Android 12. */
    private fun allowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Values of the Bluetooth link. */
    companion object {
        /** The serial port profile (SPP). */
        val SERIAL_PORT: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** How long the link is kept open after the last byte, in milliseconds. */
        const val DRAIN_MILLIS = 400L
    }
}

/** @return the serial link to [device], not connected yet. */
@SuppressLint("MissingPermission")
private fun serialSocket(device: BluetoothDevice): BluetoothSocket =
    device.createRfcommSocketToServiceRecord(BluetoothReceiptPrinter.SERIAL_PORT)
