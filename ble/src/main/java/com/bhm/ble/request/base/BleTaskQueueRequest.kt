/*
 * Copyright (c) 2022-2032 buhuiming
 * 不能修改和删除上面的版权声明
 * 此代码属于buhuiming编写，在未经允许的情况下不得传播复制
 */
package com.bhm.ble.request.base

import com.bhm.ble.control.BleTaskQueue
import com.bhm.ble.data.BleTaskQueueType
import com.bhm.ble.data.Constants
import com.bhm.ble.device.BleConnectedDeviceManager
import com.bhm.ble.device.BleDevice
import java.util.concurrent.ConcurrentHashMap


/**
 * 封装任务队列的Request
 *
 * @author Buhuiming
 * @date 2023年06月13日 16时03分
 */
internal open class BleTaskQueueRequest(
    private val bleDevice: BleDevice,
    private val tag: String
) : Request() {

    private val bleTaskQueueHashMap:
            ConcurrentHashMap<String, BleTaskQueue> = ConcurrentHashMap()

    private val bleTaskQueueType = getBleOptions()?.taskQueueType?: Constants.DEFAULT_TASK_QUEUE_TYPE

    private var operateBleTaskQueue: BleTaskQueue? = null

    init {
        if (bleTaskQueueType == BleTaskQueueType.Operate) {
            operateBleTaskQueue = BleTaskQueue(tag)
        }
    }

    fun getTaskQueue(uuid: String): BleTaskQueue? {
        return when (bleTaskQueueType) {
            BleTaskQueueType.Single ->
                BleConnectedDeviceManager.get()
                    .getBleConnectedDevice(bleDevice)
                    ?.getShareBleTaskQueue()
            BleTaskQueueType.Operate -> operateBleTaskQueue
            BleTaskQueueType.Independent -> {
                if (bleTaskQueueHashMap.containsKey(uuid)) {
                    bleTaskQueueHashMap[uuid]
                } else {
                    val independentBleTaskQueue = BleTaskQueue(tag)
                    bleTaskQueueHashMap[uuid] = independentBleTaskQueue
                    independentBleTaskQueue
                }
            }
        }
    }

    fun close() {
        when (bleTaskQueueType) {
            BleTaskQueueType.Operate -> operateBleTaskQueue?.clear()
            BleTaskQueueType.Independent -> {
                bleTaskQueueHashMap.forEach {
                    it.value.clear()
                }
                bleTaskQueueHashMap.clear()
            }
            else -> {}
        }
    }
}