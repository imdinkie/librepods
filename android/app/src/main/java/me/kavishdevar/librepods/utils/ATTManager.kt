/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

 /* This is a very basic ATT (Attribute Protocol) implementation. I have only implemented
  * what is necessary for LibrePods to function, i.e. reading and writing characteristics,
  * and receiving notifications. It is not a complete implementation of the ATT protocol.
 */

package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

enum class ATTHandles(val value: Int) {
    TRANSPARENCY(0x18),
    LOUD_SOUND_REDUCTION(0x1B),
    HEARING_AID(0x2A),
}

enum class ATTCCCDHandles(val value: Int) {
    TRANSPARENCY(ATTHandles.TRANSPARENCY.value + 1),
    LOUD_SOUND_REDUCTION(ATTHandles.LOUD_SOUND_REDUCTION.value + 1),
    HEARING_AID(ATTHandles.HEARING_AID.value + 1),
}

class ATTManager(private val device: BluetoothDevice) {
    companion object {
        private const val TAG = "ATTManager"

        private const val OPCODE_READ_REQUEST: Byte = 0x0A
        private const val OPCODE_WRITE_REQUEST: Byte = 0x12
        private const val OPCODE_HANDLE_VALUE_NTF: Byte = 0x1B

        private const val CONNECT_TIMEOUT_MS = 5000L
    }

    var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val listeners = mutableMapOf<Int, MutableList<(ByteArray) -> Unit>>()
    private var notificationJob: kotlinx.coroutines.Job? = null

    // queue for non-notification PDUs (responses to requests)
    private val responses = LinkedBlockingQueue<ByteArray>()

    private fun connectSocketWithTimeout(socket: BluetoothSocket, timeoutMs: Long): Result<Unit> {
        val error = AtomicReference<Exception?>(null)
        val latch = CountDownLatch(1)

        val connectThread = Thread(
            {
                try {
                    socket.connect()
                } catch (e: Exception) {
                    error.set(e)
                } finally {
                    latch.countDown()
                }
            },
            "LibrePods-ATT-Connect"
        ).apply { isDaemon = true }

        connectThread.start()

        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            latch.await(250, TimeUnit.MILLISECONDS)
            return Result.failure(TimeoutException("ATT BluetoothSocket.connect() timed out after ${timeoutMs}ms"))
        }

        val exception = error.get()
        return if (exception != null) Result.failure(exception) else Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/BluetoothSocket;")
        val uuid = ParcelUuid.fromString("00000000-0000-0000-0000-000000000000")

        val candidateSocket = createBluetoothSocket(device, uuid)
        Log.d(TAG, "ATT socket.connect: timeoutMs=$CONNECT_TIMEOUT_MS device=${device.address}")
        val connectResult = connectSocketWithTimeout(candidateSocket, CONNECT_TIMEOUT_MS)
        if (connectResult.isFailure || !candidateSocket.isConnected) {
            val exception = connectResult.exceptionOrNull()
            val message = exception?.localizedMessage ?: "unknown error"
            Log.w(TAG, "ATT socket.connect failed: $message")
            try {
                candidateSocket.close()
            } catch (_: Exception) {
            }
            throw exception ?: IllegalStateException("ATT socket connect failed: $message")
        }

        socket = candidateSocket
        input = candidateSocket.inputStream
        output = candidateSocket.outputStream
        Log.d(TAG, "Connected to ATT: device=${device.address}")

        notificationJob = CoroutineScope(Dispatchers.IO).launch {
            while (socket?.isConnected == true) {
                try {
                    val pdu = readPDU()
                    if (pdu.isNotEmpty() && pdu[0] == OPCODE_HANDLE_VALUE_NTF) {
                        // notification -> dispatch to listeners
                        val handle = (pdu[1].toInt() and 0xFF) or ((pdu[2].toInt() and 0xFF) shl 8)
                        val value = pdu.copyOfRange(3, pdu.size)
                        listeners[handle]?.forEach { listener ->
                            try {
                                listener(value)
                                Log.d(TAG, "Dispatched notification for handle $handle to listener, with value ${value.joinToString(" ") { String.format("%02X", it) }}")
                            } catch (e: Exception) {
                                Log.w(TAG, "Error in listener for handle $handle: ${e.message}")
                            }
                        }
                    } else {
                        // not a notification -> treat as a response for pending request(s)
                        responses.put(pdu)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading notification/response: ${e.message}")
                    if (socket?.isConnected != true) break
                }
            }
        }
    }

    fun disconnect() {
        try {
            notificationJob?.cancel()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        }
    }

    fun registerListener(handle: ATTHandles, listener: (ByteArray) -> Unit) {
        listeners.getOrPut(handle.value) { mutableListOf() }.add(listener)
    }

    fun unregisterListener(handle: ATTHandles, listener: (ByteArray) -> Unit) {
        listeners[handle.value]?.remove(listener)
    }

    fun enableNotifications(handle: ATTHandles) {
        write(ATTCCCDHandles.valueOf(handle.name), byteArrayOf(0x01, 0x00))
    }

    fun read(handle: ATTHandles): ByteArray {
        val lsb = (handle.value and 0xFF).toByte()
        val msb = ((handle.value shr 8) and 0xFF).toByte()
        val pdu = byteArrayOf(OPCODE_READ_REQUEST, lsb, msb)
        writeRaw(pdu)
        // wait for response placed into responses queue by the reader coroutine
        return readResponse()
    }

    fun write(handle: ATTHandles, value: ByteArray) {
        val lsb = (handle.value and 0xFF).toByte()
        val msb = ((handle.value shr 8) and 0xFF).toByte()
        val pdu = byteArrayOf(OPCODE_WRITE_REQUEST, lsb, msb) + value
        writeRaw(pdu)
        // usually a Write Response (0x13) will arrive; wait for it (but discard return)
        try {
            readResponse()
        } catch (e: Exception) {
            Log.w(TAG, "No write response received: ${e.message}")
        }
    }

    fun write(handle: ATTCCCDHandles, value: ByteArray) {
        val lsb = (handle.value and 0xFF).toByte()
        val msb = ((handle.value shr 8) and 0xFF).toByte()
        val pdu = byteArrayOf(OPCODE_WRITE_REQUEST, lsb, msb) + value
        writeRaw(pdu)
        // usually a Write Response (0x13) will arrive; wait for it (but discard return)
        try {
            readResponse()
        } catch (e: Exception) {
            Log.w(TAG, "No write response received: ${e.message}")
        }
    }

    private fun writeRaw(pdu: ByteArray) {
        output?.write(pdu)
        output?.flush()
        Log.d(TAG, "writeRaw: ${pdu.joinToString(" ") { String.format("%02X", it) }}")
    }

    // rename / specialize: read raw PDU directly from input stream (blocking)
    private fun readPDU(): ByteArray {
        val inp = input ?: throw IllegalStateException("Not connected")
        val buffer = ByteArray(512)
        val len = inp.read(buffer)
        if (len == -1) {
            disconnect()
            throw IllegalStateException("End of stream reached")
        }
        val data = buffer.copyOfRange(0, len)
        Log.d(TAG, "readPDU: ${data.joinToString(" ") { String.format("%02X", it) }}")
        return data
    }

    // wait for a response PDU produced by the background reader
    private fun readResponse(timeoutMs: Long = 2000): ByteArray {
        try {
            val resp = responses.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw IllegalStateException("No response read from ATT socket within $timeoutMs ms")
            Log.d(TAG, "readResponse: ${resp.joinToString(" ") { String.format("%02X", it) }}")
            return resp.copyOfRange(1, resp.size)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for ATT response", e)
        }
    }

    private fun createBluetoothSocket(device: BluetoothDevice, uuid: ParcelUuid): BluetoothSocket {
        val type = 3 // L2CAP
        val constructorSpecs = listOf(
            arrayOf(device, type, true, true, 31, uuid),
            arrayOf(device, type, 1, true, true, 31, uuid),
            arrayOf(type, 1, true, true, device, 31, uuid),
            arrayOf(type, true, true, device, 31, uuid)
        )

        val constructors = BluetoothSocket::class.java.declaredConstructors
        Log.d("ATTManager", "BluetoothSocket has ${constructors.size} constructors:")

        constructors.forEachIndexed { index, constructor ->
            val params = constructor.parameterTypes.joinToString(", ") { it.simpleName }
            Log.d("ATTManager", "Constructor $index: ($params)")
        }

        var lastException: Exception? = null
        var attemptedConstructors = 0

        for ((index, params) in constructorSpecs.withIndex()) {
            try {
                Log.d("ATTManager", "Trying constructor signature #${index + 1}")
                attemptedConstructors++
                return HiddenApiBypass.newInstance(BluetoothSocket::class.java, *params) as BluetoothSocket
            } catch (e: Exception) {
                Log.e("ATTManager", "Constructor signature #${index + 1} failed: ${e.message}")
                lastException = e
            }
        }

        val errorMessage = "Failed to create BluetoothSocket after trying $attemptedConstructors constructor signatures"
        Log.e("ATTManager", errorMessage)
        throw lastException ?: IllegalStateException(errorMessage)
    }
}
