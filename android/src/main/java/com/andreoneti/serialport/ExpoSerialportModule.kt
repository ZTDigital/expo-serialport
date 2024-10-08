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
import android.hardware.usb.UsbEndpoint

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

    AsyncFunction("requestPermissionAsync") { productName: String, promise: Promise ->
      val usbDevice: UsbDevice? = findPrinter(productName)

      if (usbDevice == null) {
        val error: CodedException = CodedException(DEVICE_NOT_FOUND)
        promise.reject(error)
      } else {
        requestPermission(usbDevice, promise)
      }
    }

    AsyncFunction("openPortAsync") { productName: String, promise: Promise ->
      val usbDevice: UsbDevice? = findPrinter(productName)

      if (usbDevice == null) {
        val error: CodedException = CodedException(DEVICE_NOT_FOUND)
        promise.reject(error)
      } else {
        openPort(usbDevice, promise)
      }
    }
    AsyncFunction("writePortAsync") { data: String, promise: Promise ->
      writePort(data, promise)
    }
  }

  private val DEVICE_NOT_FOUND: String = "device_not_found"
  private val PERMISSION_DENIED: String = "permission_denied"
  private val PERMISSION_REQUIRED: String = "permission_required"

  private var zDevice: UsbDevice? = null
  private var zConnection: UsbDeviceConnection? = null
  private var zInterface: UsbInterface? = null
  private var zEndpoint: UsbEndpoint? = null

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

  private fun findPrinter(productName:String): UsbDevice? {
    val usbManager: UsbManager = getUsbManager()
    val usbDeviceList: List<UsbDevice>? = usbManager.deviceList.values.toList()

    val usbDevice: UsbDevice? = usbDeviceList?.find { it.productName == productName }

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
  private fun openPort(device: UsbDevice, promise: Promise) {
    zDevice = device
    val usbManager: UsbManager = getUsbManager()
    zConnection = usbManager.openDevice(device)

    if (zConnection == null) {
        val error: CodedException = CodedException("connection_failed")
        promise.reject(error)
    } else {
        zInterface = device.getInterface(0)
        zEndpoint = zInterface!!.getEndpoint(0)
        if(zEndpoint == null){
          val interfaceErr: CodedException = CodedException("Interface and Endpoint Error")
          promise.reject(interfaceErr)
        }
        else{
          promise.resolve("PORT ON")
        }
    }
  }

  private fun writePort(data: String, promise: Promise) {
    zConnection!!.claimInterface(zInterface, true)
    val dataBytes: ByteArray = data.toByteArray()
    val cutPaperBytes: ByteArray = byteArrayOf(0x1D, 0x56, 0x01)
    val writeRes: Int = zConnection!!.bulkTransfer(zEndpoint, dataBytes, dataBytes.size, 0)
    val writeResCut: Int = zConnection!!.bulkTransfer(zEndpoint, cutPaperBytes, cutPaperBytes.size, 0)


    if (writeRes >= 0 && writeResCut >= 0){
      promise.resolve("PRINTED")
    }
    else {
      val error: CodedException = CodedException("connection_failed")
      promise.reject(error)
    }

    zConnection!!.releaseInterface(zInterface)
    zConnection!!.close()
  }
}