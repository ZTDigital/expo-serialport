import ExpoSerialportModule from "./ExpoSerialportModule";

export interface UsbDevice {
  vendorId: number;
  deviceId: number;
  productId: number;
  deviceName: string;
  productName: string;
  deviceClass: number;
  deviceProtocol: number;
  interfaceCount: number;
  manufacturerName: string;
}

export function listDevices(): UsbDevice[] {
  return ExpoSerialportModule.listDevices();
}

export function getSerialNumberAsync(deviceId: number): Promise<string> {
  return ExpoSerialportModule.getSerialNumberAsync(deviceId);
}

export function hasPermissionAsync(deviceId: number): Promise<boolean> {
  return ExpoSerialportModule.hasPermissionAsync(deviceId);
}
export function requestPermissionAsync(productName: string): Promise<void> {
  return ExpoSerialportModule.requestPermissionAsync(productName);
}
export function openPort(productName): Promise<void>  { 
  return ExpoSerialportModule.openPort(productName);
}

export default {
  listDevices,
  hasPermissionAsync,
  getSerialNumberAsync,
  requestPermissionAsync,
  openPort,
};
