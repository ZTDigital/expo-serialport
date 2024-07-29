package com.andreoneti.serialport

import android.app.PendingIntent

import android.content.Intent
import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.BroadcastReceiver

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.ModuleDefinition

fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

class ExpoSerialportModule : Module() {
    private var serialPort: UsbSerialDevice? = null

    override fun definition() = ModuleDefinition {
        Name("ExpoSerialport")

        Function("listDevices") {
            return@Function listDevices()
        }

        AsyncFunction("getSerialNumberAsync") { deviceId: Int, promise: Promise ->
            val usbManager: UsbManager = getUsbManager()
            val usbDeviceList: List<UsbDevice>? = usbManager.deviceList.values.toList()
            val usbDevice: UsbDevice? = usbDeviceList?.find { it.deviceId == deviceId }

            if (usbDevice == null) {
                val error: CodedException = CodedException(DEVICE_NOT_FOUND)
                promise.reject(error)
            } else if (!usbManager.hasPermission(usbDevice)) {
                val error: CodedException = CodedException(PERMISSION_REQUIRED)
                promise.reject(error)
            } else {
                promise.resolve(usbDevice.serialNumber)
            }
        }

        AsyncFunction("hasPermissionAsync") { deviceId: Int, promise: Promise ->
            val usbDevice: UsbDevice? = findDevice(deviceId)

            if (usbDevice == null) {
                val error: CodedException = CodedException(DEVICE_NOT_FOUND)
                promise.reject(error)
            } else {
                val usbManager: UsbManager = getUsbManager()
                val hasPermission: Boolean = usbManager.hasPermission(usbDevice)
                promise.resolve(hasPermission)
            }
        }

        AsyncFunction("requestPermissionAsync") { deviceId: Int, promise: Promise ->
            val usbDevice: UsbDevice? = findDevice(deviceId)

            if (usbDevice == null) {
                val error: CodedException = CodedException(DEVICE_NOT_FOUND)
                promise.reject(error)
            } else {
                requestPermission(usbDevice, promise)
            }
        }

        AsyncFunction("openPort") { portName: String, baudRate: Int, promise: Promise ->
            openPort(portName, baudRate, promise)
        }

        AsyncFunction("writeData") { data: String, promise: Promise ->
            writeData(data, promise)
        }

        AsyncFunction("closePort") { promise: Promise ->
            closePort(promise)
        }
    }

    private val DEVICE_NOT_FOUND: String = "device_not_found"
    private val PERMISSION_DENIED: String = "permission_denied"
    private val PERMISSION_REQUIRED: String = "permission_required"

    private val context: Context
        get() = requireNotNull(appContext.reactContext)

    private fun getPreferences(): SharedPreferences {
        return context.getSharedPreferences(context.packageName + ".settings", Context.MODE_PRIVATE)
    }

    private fun getUsbManager(): UsbManager {
        return context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private fun listDevices(): WritableArray {
        val usbManager: UsbManager = getUsbManager()
        val usbDeviceList: List<UsbDevice>? = usbManager.deviceList.values.toList()

        val usbDevicesArray: WritableArray = WritableNativeArray()

        usbDeviceList?.forEach { usbDevice ->
            val usbDeviceMap: WritableMap = WritableNativeMap()
            usbDeviceMap.putInt("deviceId", usbDevice.deviceId)
            usbDeviceMap.putInt("vendorId", usbDevice.vendorId)
            usbDeviceMap.putInt("productId", usbDevice.productId)
            usbDeviceMap.putInt("deviceClass", usbDevice.deviceClass)
            usbDeviceMap.putString("deviceName", usbDevice.deviceName)
            usbDeviceMap.putString("productName", usbDevice.productName)
            usbDeviceMap.putInt("deviceProtocol", usbDevice.deviceProtocol)
            usbDeviceMap.putInt("interfaceCount", usbDevice.interfaceCount)
            usbDeviceMap.putString("manufacturerName", usbDevice.manufacturerName)
            usbDevicesArray.pushMap(usbDeviceMap)
        }

        return usbDevicesArray
    }

    private fun findDevice(deviceId: Int): UsbDevice? {
        val usbManager: UsbManager = getUsbManager()
        val usbDeviceList: List<UsbDevice>? = usbManager.deviceList.values.toList()
        return usbDeviceList?.find { it.deviceId == deviceId }
    }

    private fun requestPermission(device: UsbDevice, promise: Promise) {
        val ACTION_USB_PERMISSION: String = context.packageName + ".GRANT_USB"
        val usbManager: UsbManager = getUsbManager()
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted: Boolean = usbManager.hasPermission(device)
                    if (granted) {
                        promise.resolve(null)
                    } else {
                        val error: CodedException = CodedException(PERMISSION_DENIED)
                        promise.reject(error)
                    }
                    context.unregisterReceiver(this)
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        context.registerReceiver(permissionReceiver, filter)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun openPort(portName: String, baudRate: Int, promise: Promise) {
        try {
            val usbManager = getUsbManager()
            val deviceList = usbManager.deviceList.values.toList()
            val usbDevice = deviceList.find { it.deviceName == portName }

            if (usbDevice == null) {
                promise.reject("DEVICE_NOT_FOUND", "Device not found")
                return
            }

            if (!usbManager.hasPermission(usbDevice)) {
                promise.reject("PERMISSION_DENIED", "Permission denied for device")
                return
            }

            val connection = usbManager.openDevice(usbDevice)
            serialPort = UsbSerialDevice.createUsbSerialDevice(usbDevice, connection)
            if (serialPort != null) {
                serialPort?.apply {
                    open()
                    setBaudRate(baudRate)
                    setDataBits(UsbSerialInterface.DATA_BITS_8)
                    setStopBits(UsbSerialInterface.STOP_BITS_1)
                    setParity(UsbSerialInterface.PARITY_NONE)
                    setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF)
                }
                promise.resolve("Port opened successfully")
            } else {
                promise.reject("OPEN_FAILED", "Failed to open serial port")
            }
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    private fun writeData(data: String, promise: Promise) {
        try {
            if (serialPort == null || !(serialPort?.isOpen ?: false)) {
                promise.reject("PORT_NOT_OPEN", "Serial port is not open")
                return
            }
            serialPort?.write(data.toByteArray())
            promise.resolve("Data written successfully")
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    private fun closePort(promise: Promise) {
        try {
            if (serialPort != null && (serialPort?.isOpen ?: false)) {
                serialPort?.close()
                promise.resolve("Port closed successfully")
            } else {
                promise.reject("PORT_NOT_OPEN", "Serial port is not open")
            }
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }
}

