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
import com.bhm.ble.callback.BleNotifyCallback
import com.bhm.ble.data.*
import com.bhm.ble.data.Constants.EXCEPTION_CODE_DESCRIPTOR_FAIL
import com.bhm.ble.data.Constants.EXCEPTION_CODE_SET_CHARACTERISTIC_NOTIFICATION_FAIL
import com.bhm.ble.data.Constants.NOTIFY_TASK_ID
import com.bhm.ble.data.Constants.UUID_CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR
import com.bhm.ble.device.BleDevice
import com.bhm.ble.request.base.BleTaskQueueRequest
import com.bhm.ble.utils.BleLogger
import com.bhm.ble.utils.BleUtil
import kotlinx.coroutines.TimeoutCancellationException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


/**
 * 设置Notify请求
 *
 * @author Buhuiming
 * @date 2023年06月07日 15时35分
 */
internal class BleNotifyRequest(
    private val bleDevice: BleDevice,
) : BleTaskQueueRequest(bleDevice, "Notify队列") {

    private val bleNotifyCallbackHashMap:
            ConcurrentHashMap<String, BleNotifyCallback> = ConcurrentHashMap()

    @Synchronized
    private fun addNotifyCallback(uuid: String, bleNotifyCallback: BleNotifyCallback) {
        bleNotifyCallbackHashMap[uuid] = bleNotifyCallback
    }

    @Synchronized
    fun removeNotifyCallback(uuid: String?) {
        if (bleNotifyCallbackHashMap.containsKey(uuid)) {
            bleNotifyCallbackHashMap.remove(uuid)
        }
    }

    @Synchronized
    fun removeAllNotifyCallback() {
        bleNotifyCallbackHashMap.clear()
    }

    /**
     * notify
     */
    @Synchronized
    fun enableCharacteristicNotify(serviceUUID: String,
                                   notifyUUID: String,
                                   useCharacteristicDescriptor: Boolean,
                                   bleNotifyCallback: BleNotifyCallback) {
        if (!BleUtil.isPermission(getBleManager().getContext())) {
            bleNotifyCallback.callNotifyFail(NoBlePermissionException())
            return
        }
        val characteristic = getCharacteristic(bleDevice, serviceUUID, notifyUUID)
        if (characteristic != null &&
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        ) {
            bleNotifyCallback.setKey(notifyUUID)
            addNotifyCallback(notifyUUID, bleNotifyCallback)
            var mContinuation: Continuation<Throwable?>? = null
            val task = getTask(
                getTaskId(notifyUUID),
                block = {
                    suspendCoroutine<Throwable?> { continuation ->
                        mContinuation = continuation
                        setCharacteristicNotify(
                            notifyUUID,
                            characteristic,
                            useCharacteristicDescriptor,
                            true,
                            bleNotifyCallback
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
                            val exception = TimeoutCancelException("$notifyUUID -> 设置Notify失败，设置超时")
                            BleLogger.e(exception.message)
                            bleNotifyCallback.callNotifyFail(exception)
                        }
                    }
                }
            )
            getTaskQueue(notifyUUID)?.addTask(task)
        } else {
            val exception = UnSupportException("$notifyUUID -> 设置Notify失败，此特性不支持通知")
            BleLogger.e(exception.message)
            bleNotifyCallback.callNotifyFail(exception)
        }
    }

    /**
     * stop notify
     */
    @Synchronized
    fun disableCharacteristicNotify(serviceUUID: String,
                                    notifyUUID: String,
                                    useCharacteristicDescriptor: Boolean): Boolean {
        if (!BleUtil.isPermission(getBleManager().getContext())) {
            return false
        }
        val characteristic = getCharacteristic(bleDevice, serviceUUID, notifyUUID)
        if (characteristic != null &&
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        ) {
            cancelNotifyJob(notifyUUID, getTaskId(notifyUUID))
            val success = setCharacteristicNotify(
                notifyUUID,
                characteristic,
                useCharacteristicDescriptor,
                false,
                null
            )
            if (success) {
                removeNotifyCallback(notifyUUID)
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
        bleNotifyCallbackHashMap.values.forEach {
            if (characteristic.uuid?.toString().equals(it.getKey(), ignoreCase = true)) {
                BleLogger.d("${it.getKey()} -> " +
                        "收到Notify数据：${BleUtil.bytesToHex(value)}")
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
        bleNotifyCallbackHashMap.values.forEach {
            if (descriptor?.characteristic?.uuid?.toString().equals(it.getKey(), ignoreCase = true)
                && cancelNotifyJob(it.getKey(), getTaskId(it.getKey()))) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    BleLogger.d("${it.getKey()} -> 设置Notify成功")
                    it.callNotifySuccess()
                } else {
                    val exception = UnDefinedException(
                        "${it.getKey()} -> 设置Notify失败，Descriptor写数据失败",
                        EXCEPTION_CODE_DESCRIPTOR_FAIL
                    )
                    BleLogger.e(exception.message)
                    it.callNotifyFail(exception)
                }
            }
        }
    }

    /**
     * 配置Notify
     */
    @SuppressLint("MissingPermission")
    private fun setCharacteristicNotify(notifyUUID: String,
                                        characteristic: BluetoothGattCharacteristic,
                                        useCharacteristicDescriptor: Boolean,
                                        enable: Boolean,
                                        bleNotifyCallback: BleNotifyCallback?): Boolean {
        val bluetoothGatt = getBluetoothGatt(bleDevice)
        val setSuccess = bluetoothGatt?.setCharacteristicNotification(characteristic, enable)
        if (setSuccess != true) {
            val exception = UnDefinedException(
                "$notifyUUID -> 设置Notify失败，SetCharacteristicNotificationFail",
                EXCEPTION_CODE_SET_CHARACTERISTIC_NOTIFICATION_FAIL
            )
            cancelNotifyJob(notifyUUID, getTaskId(notifyUUID))
            BleLogger.e(exception.message)
            bleNotifyCallback?.callNotifyFail(exception)
            return false
        }
        val descriptor = if (useCharacteristicDescriptor) {
            characteristic.getDescriptor(characteristic.uuid)
        } else {
            characteristic.getDescriptor(UUID.fromString(UUID_CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR))
        }
        if (descriptor == null) {
            val exception = UnDefinedException(
                "$notifyUUID -> 设置Notify失败，SetCharacteristicNotificationFail",
                EXCEPTION_CODE_SET_CHARACTERISTIC_NOTIFICATION_FAIL
            )
            cancelNotifyJob(notifyUUID, getTaskId(notifyUUID))
            BleLogger.e(exception.message)
            bleNotifyCallback?.callNotifyFail(exception)
            return false
        }
        val success: Boolean
        var writeDescriptorCode: Int? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            writeDescriptorCode = if (enable) {
                bluetoothGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                bluetoothGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            }
            success = writeDescriptorCode == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = if (enable) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            @Suppress("DEPRECATION")
            val writeDescriptor: Boolean = bluetoothGatt.writeDescriptor(descriptor)
            success = writeDescriptor == true
        }
        if (!success) {
            //true, if the write operation was initiated successfully Value is
            // BluetoothStatusCodes.SUCCESS = 0,
            // BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION = 6,
            // BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED = 4,
            // BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND = 8,
            // BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED = 200,
            // BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY = 201,
            // BluetoothStatusCodes.ERROR_UNKNOWN = 2147483647,
            // BluetoothStatusCodes.ERROR_NO_ACTIVE_DEVICES = 13,
            val exception = UnDefinedException(
                "$notifyUUID -> -> 设置Indicate失败，错误可能是没有权限、" +
                        "未连接、服务未绑定、不可写、请求忙碌等，code = $writeDescriptorCode",
                EXCEPTION_CODE_DESCRIPTOR_FAIL
            )
            cancelNotifyJob(notifyUUID, getTaskId(notifyUUID))
            BleLogger.e(exception.message)
            bleNotifyCallback?.callNotifyFail(exception)
            return false
        }
        return true
    }

    private fun getTaskId(uuid: String?) = NOTIFY_TASK_ID + uuid

    /**
     * 取消设置notify任务
     */
    private fun cancelNotifyJob(notifyUUID: String?, taskId: String): Boolean {
        return getTaskQueue(notifyUUID?: "")?.removeTask(taskId)?: false
    }
}