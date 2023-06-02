package com.bhm.demo.ui

import android.content.Intent
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.bhm.ble.callback.BleRssiCallback
import com.bhm.ble.control.BleTask
import com.bhm.ble.data.BleDevice
import com.bhm.ble.control.BleTaskQueue
import com.bhm.ble.utils.BleLogger
import com.bhm.demo.BaseActivity
import com.bhm.demo.R
import com.bhm.demo.adapter.DeviceListAdapter
import com.bhm.demo.databinding.ActivityMainBinding
import com.bhm.demo.vm.MainViewModel
import com.bhm.support.sdk.core.AppTheme
import com.bhm.support.sdk.utils.ViewUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import leakcanary.LeakCanary
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 主页面
 * @author Buhuiming
 * @date :2023/5/24 15:39
 */
class MainActivity : BaseActivity<MainViewModel, ActivityMainBinding>(){

    private var listAdapter: DeviceListAdapter? = null

    override fun createViewModel() = MainViewModel(application)

    override fun initData() {
        super.initData()
        AppTheme.setStatusBarColor(this, R.color.purple_500)
        LeakCanary.runCatching {  }
        initList()
        viewModel.initBle()
    }

    override fun initEvent() {
        super.initEvent()
        lifecycleScope.launch {
            viewModel.listDRStateFlow.collect {
                if (it.deviceName != null && it.deviceAddress != null) {
                    val position = (listAdapter?.itemCount?: 1) - 1
                    listAdapter?.notifyItemInserted(position)
                    viewBinding.recyclerView.smoothScrollToPosition(position)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.scanStopStateFlow.collect {
                viewBinding.pbLoading.visibility = if (it) { View.INVISIBLE } else { View.VISIBLE }
                viewBinding.btnStart.text = if (it) { "开启扫描" } else { "扫描中..." }
                viewBinding.btnStart.isEnabled = it
                viewBinding.btnSetting.isEnabled = it
                viewBinding.btnStop.isEnabled = !it
            }
        }

        lifecycleScope.launch {
            viewModel.refreshStateFlow.collect {
                delay(300)
                dismissLoading()
                it?.bleDevice?.let { bleDevice ->
                    val position = listAdapter?.data?.indexOf(bleDevice) ?: -1
                    if (position >= 0) {
                        listAdapter?.notifyItemChanged(position)
                    }
                    BleLogger.i("item isConnected: ${viewModel.isConnected(bleDevice)}")
                }
            }
        }

        listAdapter?.addChildClickViewIds(R.id.btnConnect, R.id.btnOperate)
        listAdapter?.setOnItemChildClickListener { adapter, view, position ->
            if (ViewUtil.isInvalidClick(view)) {
                return@setOnItemChildClickListener
            }
            val bleDevice: BleDevice? = adapter.data[position] as BleDevice?
            if (view.id == R.id.btnConnect) {
                if (viewModel.isConnected(bleDevice)) {
                    showLoading("断开中...")
                    viewModel.disConnect(bleDevice)
                } else {
                    showLoading("连接中...")
                    viewModel.connect(bleDevice)
                }
            } else if (view.id == R.id.btnOperate) {
                val intent = Intent(this@MainActivity, DetailOperateActivity::class.java)
                intent.putExtra("data", bleDevice)
                startActivity(intent)
            }
        }

        viewBinding.btnSetting.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) {
                return@setOnClickListener
            }
//            startActivity(Intent(this@MainActivity, OptionSettingActivity::class.java))
            BleTaskQueue.get().sendTask(BleTask(callInMainThread = false, autoDoNextTask = true) {
                testJob(1)
            })
            BleTaskQueue.get().sendTask(BleTask(callInMainThread = false) {
                testJob(2)
            })
        }

        viewBinding.btnStart.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) {
                return@setOnClickListener
            }
            listAdapter?.notifyItemRangeRemoved(0, viewModel.listDRData.size)
            viewModel.listDRData.clear()
            viewModel.startScan(this@MainActivity)
        }

        viewBinding.btnStop.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) {
                return@setOnClickListener
            }
            viewModel.stopScan()
        }
    }

    private suspend fun testJob(i: Int) {
        var result: Boolean = suspendCoroutine { continuation ->
            val callback = BleRssiCallback()
            CoroutineScope(Dispatchers.IO).launch {
                repeat(5) {
                    BleLogger.e("${i}执行子任务：$it")
                    delay(1000)
                }
                delay(500)
                callback.callRssiSuccess(100)
                BleLogger.e("testJob${i}任务完成")
                continuation.resume(true)
            }
        }
    }

    private fun initList() {
        val layoutManager = LinearLayoutManager(this)
        layoutManager.orientation = LinearLayoutManager.VERTICAL
        viewBinding.recyclerView.setHasFixedSize(true)
        viewBinding.recyclerView.layoutManager = layoutManager
        viewBinding.recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        //解决RecyclerView局部刷新时闪烁
        (viewBinding.recyclerView.itemAnimator as DefaultItemAnimator).supportsChangeAnimations = false
        listAdapter = DeviceListAdapter(viewModel.listDRData)
        viewBinding.recyclerView.adapter = listAdapter
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopScan()
        viewModel.release()
    }
}