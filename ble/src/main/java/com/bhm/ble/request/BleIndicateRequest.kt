/*
 * Copyright (c) 2022-2032 buhuiming
 * 不能修改和删除上面的版权声明
 * 此代码属于buhuiming编写，在未经允许的情况下不得传播复制
 */
@file:Suppress("RemoveExplicitTypeArguments")

package com.bhm.ble.request

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import com.bhm.ble.callback.BleIndicateCallback
import com.bhm.ble.control.BleTaskQueue
import com.bhm.ble.data.Constants.EXCEPTION_CODE_DESCRIPTOR_FAIL
import com.bhm.ble.data.Constants.EXCEPTION_CODE_SET_CHARACTERISTIC_NOTIFICATION_FAIL
import com.bhm.ble.data.Constants.INDICATE_TASK_ID
import com.bhm.ble.data.Constants.UUID_CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR
import com.bhm.ble.data.TimeoutCancelException
import com.bhm.ble.data.UnDefinedException
import com.bhm.ble.data.UnSupportException
import com.bhm.ble.device.BleDevice
import com.bhm.ble.request.base.Request
import com.bhm.ble.utils.BleLogger
import com.bhm.ble.utils.BleUtil
import kotlinx.coroutines.TimeoutCancellationException
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


/**
 * 设置Indicate请求
 *
 * @author Buhuiming
 * @date 2023年06月07日 15时35分
 */
internal class BleIndicateRequest(
    private val bleDevice: BleDevice,
    private val bleTaskQueue: BleTaskQueue
) : Request() {

    private val bleIndicateCallbackHashMap: HashMap<String, BleIndicateCallback> = HashMap()

    @Synchronized
    fun addIndicateCallback(uuid: String, bleIndicateCallback: BleIndicateCallback) {
        bleIndicateCallbackHashMap[uuid] = bleIndicateCallback
    }

    @Synchronized
    fun removeIndicateCallback(uuid: String?) {
        if (bleIndicateCallbackHashMap.containsKey(uuid)) {
            bleIndicateCallbackHashMap.remove(uuid)
        }
    }

    @Synchronized
    fun removeAllIndicateCallback() {
        bleIndicateCallbackHashMap.clear()
    }

    /**
     * indicate
     */
    @Synchronized
    fun enableCharacteristicIndicate(serviceUUID: String,
                                     indicateUUID: String,
                                     useCharacteristicDescriptor: Boolean,
                                     bleIndicateCallback: BleIndicateCallback) {
        val bluetoothGatt = getBleConnectedDevice(bleDevice)?.getBluetoothGatt()
        val gattService = bluetoothGatt?.getService(UUID.fromString(serviceUUID))
        val characteristic = gattService?.getCharacteristic(UUID.fromString(indicateUUID))
        if (bluetoothGatt != null && gattService != null && characteristic != null &&
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0
        ) {
            bleIndicateCallback.setKey(indicateUUID)
            addIndicateCallback(indicateUUID, bleIndicateCallback)
            var mContinuation: Continuation<Throwable?>? = null
            val task = getTask(
                INDICATE_TASK_ID,
                block = {
                    suspendCoroutine<Throwable?> { continuation ->
                        mContinuation = continuation
                        setCharacteristicIndicate(
                            indicateUUID,
                            characteristic,
                            useCharacteristicDescriptor,
                            true,
                            bleIndicateCallback
                        )
                    }
                },
                interrupt = { _, throwable ->
                    mContinuation?.resume(throwable)
                },
                callback = { _, throwable ->
                    throwable?.let {
                        BleLogger.e(it.message)
                        if (it is TimeoutCancellationException || it is TimeoutCancelException) {
                            val exception = TimeoutCancelException("$indicateUUID -> 设置Indicate失败，设置超时")
                            BleLogger.e(exception.message)
                            bleIndicateCallback.callIndicateFail(exception)
                        }
                    }
                }
            )
            bleTaskQueue.addTask(task)
        } else {
            val exception = UnSupportException("$indicateUUID -> 设置Indicate失败，此特性不支持通知")
            BleLogger.e(exception.message)
            bleIndicateCallback.callIndicateFail(exception)
        }
    }

    /**
     * stop indicate
     */
    @Synchronized
    fun disableCharacteristicIndicate(serviceUUID: String,
                                      indicateUUID: String,
                                      useCharacteristicDescriptor: Boolean): Boolean {
        val bluetoothGatt = getBleConnectedDevice(bleDevice)?.getBluetoothGatt()
        val gattService = bluetoothGatt?.getService(UUID.fromString(serviceUUID))
        val characteristic = gattService?.getCharacteristic(UUID.fromString(indicateUUID))
        if (bluetoothGatt != null && gattService != null && characteristic != null &&
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0
        ) {
            cancelIndicateJob()
            val success = setCharacteristicIndicate(
                indicateUUID,
                characteristic,
                useCharacteristicDescriptor,
                false,
                null
            )
            if (success) {
                removeIndicateCallback(indicateUUID)
            }
            return success
        }
        return false
    }

    /**
     * 设备发出通知时会时会触发
     */
    fun onCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        bleIndicateCallbackHashMap.values.forEach {
            if (characteristic.uuid?.toString().equals(it.getKey(), ignoreCase = true)) {
                if (BleLogger.isLogger) {
                    BleLogger.d("${it.getKey()} -> " +
                            "收到Indicate数据：${BleUtil.bytesToHex(value)}")
                }
                it.callCharacteristicChanged(value)
            }
        }
    }

    /**
     * 当向设备Descriptor中写数据时会触发
     */
    fun onDescriptorWrite(
        descriptor: BluetoothGattDescriptor?,
        status: Int
    ) {
        bleIndicateCallbackHashMap.values.forEach {
            if (descriptor?.characteristic?.uuid.toString().equals(it.getKey(), ignoreCase = true)) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    cancelIndicateJob()
                    BleLogger.d("${it.getKey()} -> 设置Indicate成功")
                    it.callIndicateSuccess()
                } else {
                    val exception = UnDefinedException(
                        "${it.getKey()} -> 设置Indicate失败，Descriptor写数据失败",
                        EXCEPTION_CODE_DESCRIPTOR_FAIL
                    )
                    cancelIndicateJob()
                    BleLogger.e(exception.message)
                    it.callIndicateFail(exception)
                }
            }
        }
    }

    /**
     * 配置Indicate
     */
    @SuppressLint("MissingPermission")
    private fun setCharacteristicIndicate(indicateUUID: String,
                                          characteristic: BluetoothGattCharacteristic,
                                          useCharacteristicDescriptor: Boolean,
                                          enable: Boolean,
                                          bleIndicateCallback: BleIndicateCallback?): Boolean {
        val bluetoothGatt = getBleConnectedDevice(bleDevice)?.getBluetoothGatt()
        val setSuccess = bluetoothGatt?.setCharacteristicNotification(characteristic, enable)
        if (setSuccess != true) {
            val exception = UnDefinedException(
                "$indicateUUID -> 设置Indicate失败，SetCharacteristicNotificationFail",
                EXCEPTION_CODE_SET_CHARACTERISTIC_NOTIFICATION_FAIL
            )
            cancelIndicateJob()
            BleLogger.e(exception.message)
            bleIndicateCallback?.callIndicateFail(exception)
            return false
        }
        val descriptor = if (useCharacteristicDescriptor) {
            characteristic.getDescriptor(characteristic.uuid)
        } else {
            characteristic.getDescriptor(UUID.fromString(UUID_CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR))
        }
        if (descriptor == null) {
            val exception = UnDefinedException(
                "$indicateUUID -> 设置Indicate失败，SetCharacteristicNotificationFail",
                EXCEPTION_CODE_SET_CHARACTERISTIC_NOTIFICATION_FAIL
            )
            cancelIndicateJob()
            BleLogger.e(exception.message)
            bleIndicateCallback?.callIndicateFail(exception)
            return false
        }
        val success: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeDescriptor: Int = if (enable) {
                bluetoothGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            } else {
                bluetoothGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            }
            success = writeDescriptor == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = if (enable) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            @Suppress("DEPRECATION")
            val writeDescriptor: Boolean = bluetoothGatt.writeDescriptor(descriptor)
            success = writeDescriptor == true
        }
        if (!success) {
            //true, if the write operation was initiated successfully Value is
            // BluetoothStatusCodes.SUCCESS,
            // BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
            // android.bluetooth.BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED,
            // BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND,
            // BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED,
            // BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
            // BluetoothStatusCodes.ERROR_UNKNOWN
            val exception = UnDefinedException(
                "$indicateUUID -> 设置Indicate失败，错误可能是没有权限、未连接、服务未绑定、不可写、请求忙碌等",
                EXCEPTION_CODE_DESCRIPTOR_FAIL
            )
            cancelIndicateJob()
            BleLogger.e(exception.message)
            bleIndicateCallback?.callIndicateFail(exception)
            return false
        }
        return true
    }

    /**
     * 取消设置indicate任务
     */
    private fun cancelIndicateJob() {
        bleTaskQueue.removeTask(taskId = INDICATE_TASK_ID)
    }
}