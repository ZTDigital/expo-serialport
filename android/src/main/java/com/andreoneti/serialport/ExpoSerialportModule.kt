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
  private var serialPortConnection: UsbDeviceConnection? = null
    private var serialPort: UsbEndpoint? = null
    private var usbDevice: UsbDevice? = null
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
      } else if(!usbManager.hasPermission(usbDevice)) {
        val error: CodedException = CodedException(PERMISSION_REQUIRED)
        promise.reject(error)
      } else {
        promise.resolve(usbDevice.getSerialNumber())
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

  private val context
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

    if (usbDeviceList != null) {
      for (usbDevice in usbDeviceList) {
        val usbDeviceMap: WritableMap = WritableNativeMap()

        usbDeviceMap.putInt("deviceId", usbDevice.getDeviceId())
        usbDeviceMap.putInt("vendorId", usbDevice.getVendorId())
        usbDeviceMap.putInt("productId", usbDevice.getProductId())
        usbDeviceMap.putInt("deviceClass", usbDevice.getDeviceClass())
        usbDeviceMap.putString("deviceName", usbDevice.getDeviceName())
        usbDeviceMap.putString("productName", usbDevice.getProductName())
        usbDeviceMap.putInt("deviceProtocol", usbDevice.getDeviceProtocol())
        usbDeviceMap.putInt("interfaceCount", usbDevice.getInterfaceCount())
        usbDeviceMap.putString("manufacturerName", usbDevice.getManufacturerName())

        usbDevicesArray.pushMap(usbDeviceMap)
      }
    }

    return usbDevicesArray
  }

  private fun findDevice(deviceId:Int): UsbDevice? {
    val usbManager: UsbManager = getUsbManager()
    val usbDeviceList: List<UsbDevice>? = usbManager.deviceList.values.toList()

    val usbDevice: UsbDevice? = usbDeviceList?.find { it.deviceId == deviceId }

    return usbDevice
  }

  private fun requestPermission(device: UsbDevice, promise: Promise): Unit {
    val ACTION_USB_PERMISSION: String = context.packageName + ".GRANT_USB"
    val usbManager: UsbManager = getUsbManager()
    val permissionIntent = PendingIntent.getBroadcast(
      context,
      0,
      Intent(ACTION_USB_PERMISSION),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val permissionReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_USB_PERMISSION) {
          intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
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
      val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
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
      val usbInterface = usbDevice.getInterface(0)
      serialPort = usbInterface.getEndpoint(0)
      serialPortConnection = connection
      connection.claimInterface(usbInterface, true)
    } catch (e: Exception) {
      promise.reject("ERROR", e.message)
    }
  }
  private fun writeData(data: String, promise: Promise) {
    try {
        if (serialPort == null) {
            promise.reject("PORT_NOT_OPEN", "Serial port is not open")
            return
        }
        val buffer = data.toByteArray()
        val usbRequest = UsbRequest()
        usbRequest.initialize(serialPortConnection, serialPort)
        usbRequest.queue(ByteBuffer.wrap(buffer), buffer.size)
        promise.resolve("Data written successfully")
    } catch (e: Exception) {
        promise.reject("ERROR", e.message)
    }
  }
  private fun closePort(promise: Promise) {
    try {
        if (serialPortConnection != null) {
            serialPortConnection?.close()
            promise.resolve("Port closed successfully")
          } else {
              promise.reject("PORT_NOT_OPEN", "Serial port is not open")
          }
      } catch (e: Exception) {
          promise.reject("ERROR", e.message)
    }
  }
}