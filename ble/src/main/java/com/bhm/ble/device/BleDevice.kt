/*
 * Copyright (c) 2022-2032 buhuiming
 * 不能修改和删除上面的版权声明
 * 此代码属于buhuiming编写，在未经允许的情况下不得传播复制
 */
@file:Suppress("DEPRECATION")

package com.bhm.ble.device

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.os.Parcel
import android.os.Parcelable


/**
 * BleDevice信息
 *
 * @author Buhuiming
 * @date 2023年05月22日 09时11分
 */
data class BleDevice(
    val deviceInfo: BluetoothDevice?, //设备信息
    val deviceName: String?, //蓝牙广播名
    val deviceAddress: String?, //蓝牙Mac地址
    val rssi: Int?, //被扫描到时候的信号强度
    val timestampNanos: Long?, //当扫描记录被观察到时，返回自启动以来的时间戳。
    val scanRecord: ScanRecord?, // 被扫描到时候携带的广播数据
    var tag: Any? = null //预留字段
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readParcelable(BluetoothDevice::class.java.classLoader),
        parcel.readString(),
        parcel.readString(),
        parcel.readValue(Int::class.java.classLoader) as? Int,
        parcel.readValue(Long::class.java.classLoader) as? Long,
        parcel.readValue(ScanRecord::class.java.classLoader) as? ScanRecord,
        parcel.readValue(Int::class.java.classLoader) as? Int,
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(deviceInfo, flags)
        parcel.writeString(deviceName)
        parcel.writeString(deviceAddress)
        parcel.writeValue(rssi)
        parcel.writeValue(timestampNanos)
        parcel.writeValue(tag)
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BleDevice

        if (deviceName != other.deviceName) return false
        if (deviceAddress != other.deviceAddress) return false

        return true
    }

    override fun hashCode(): Int {
        var result = deviceName?.hashCode() ?: 0
        result = 31 * result + (deviceAddress?.hashCode() ?: 0)
        return result
    }

    companion object CREATOR : Parcelable.Creator<BleDevice> {
        override fun createFromParcel(parcel: Parcel): BleDevice {
            return BleDevice(parcel)
        }

        override fun newArray(size: Int): Array<BleDevice?> {
            return arrayOfNulls(size)
        }
    }

    fun getKey() = deviceName + deviceAddress

}