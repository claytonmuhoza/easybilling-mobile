package com.claytonmuhoza.easybilling.platform.printing

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.document.PairedPrinter
import com.claytonmuhoza.easybilling.domain.document.PrintOutcome
import com.claytonmuhoza.easybilling.domain.document.PrinterLookup
import com.claytonmuhoza.easybilling.domain.document.Receipt
import com.claytonmuhoza.easybilling.domain.document.ReceiptLine
import com.claytonmuhoza.easybilling.domain.document.ReceiptWidth
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Bluetooth printer (UC-A09, UC-G01) against the simulated Bluetooth of Robolectric: the
 * paired printers, the permission, and the bytes written on the serial link.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BluetoothReceiptPrinterTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val adapter: BluetoothAdapter = application.getSystemService(BluetoothManager::class.java).adapter
    private val receipt = Receipt(listOf(ReceiptLine("FACTURE")), ReceiptWidth.MM_58, accents = false)
    private var socket: BluetoothSocket? = null
    private var connectedTo: String? = null

    /** A printer that keeps the serial link it opened, so the test reads what was written on it. */
    private val printer = BluetoothReceiptPrinter(application) { device ->
        connectedTo = device.address
        device.createRfcommSocketToServiceRecord(BluetoothReceiptPrinter.SERIAL_PORT).also { socket = it }
    }

    @Before
    fun bluetoothOnAndAllowed() {
        shadowOf(adapter).setEnabled(true)
        shadowOf(application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun pair(address: String, name: String) {
        val device = adapter.getRemoteDevice(address)
        shadowOf(device).setName(name)
        shadowOf(adapter).setBondedDevices(adapter.bondedDevices.orEmpty() + device)
    }

    @Test
    fun `the paired printers are listed by name`() {
        pair("00:11:22:33:44:55", "POS-80")
        pair("00:11:22:33:44:66", "Epson TM")

        assertEquals(
            PrinterLookup.Found(
                listOf(PairedPrinter("00:11:22:33:44:66", "Epson TM"), PairedPrinter("00:11:22:33:44:55", "POS-80")),
            ),
            printer.paired(),
        )
    }

    @Test
    fun `with Bluetooth off nothing is listed nor printed`() = runTest {
        shadowOf(adapter).setEnabled(false)

        assertEquals(PrinterLookup.BluetoothOff, printer.paired())
        assertEquals(PrintOutcome.BLUETOOTH_OFF, printer.print("00:11:22:33:44:55", receipt))
    }

    @Test
    fun `without the permission to connect nothing is listed nor printed`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        assertEquals(PrinterLookup.PermissionMissing, printer.paired())
        assertEquals(PrintOutcome.PERMISSION_MISSING, printer.print("00:11:22:33:44:55", receipt))
    }

    @Test
    fun `printing connects to the printer and writes the ticket as ESC-POS`() = runTest {
        pair("00:11:22:33:44:55", "POS-58")

        assertEquals(PrintOutcome.PRINTED, printer.print("00:11:22:33:44:55", receipt))

        val link = assertNotNull(socket)
        val expected = EscPos.encode(receipt)
        val written = ByteArray(expected.size).also { shadowOf(link).outputStreamSink.read(it) }
        assertContentEquals(expected, written)
        assertEquals("00:11:22:33:44:55", connectedTo)
    }

    @Test
    fun `a printer that does not answer, or an address that is not one, is unreachable`() = runTest {
        val silent = BluetoothReceiptPrinter(application) { throw IOException("read failed, socket might closed") }

        assertEquals(PrintOutcome.UNREACHABLE, silent.print("00:11:22:33:44:55", receipt))
        assertEquals(PrintOutcome.UNREACHABLE, printer.print("not an address", receipt))
    }
}
