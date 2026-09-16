package com.bhickta.faceattendance

import android.app.Activity
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bhickta.faceattendance.databinding.ActivityProvisioningBinding
import com.bhickta.faceattendance.device.ActivationClient
import com.bhickta.faceattendance.device.DeviceConfigurationStore
import com.bhickta.faceattendance.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProvisioningActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProvisioningBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProvisioningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.activateButton.setOnClickListener { activate() }
    }

    private fun activate() {
        binding.activateButton.isEnabled = false
        binding.activationStatus.setText(R.string.activating)
        val serverUrl = binding.serverUrl.text?.toString().orEmpty()
        val activationToken = binding.activationToken.text?.toString().orEmpty()
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ActivationClient().activate(serverUrl, activationToken)
                }
            }.onSuccess { configuration ->
                DeviceConfigurationStore(this@ProvisioningActivity).save(configuration)
                SyncScheduler.requestNow(this@ProvisioningActivity)
                binding.activationToken.text?.clear()
                setResult(Activity.RESULT_OK)
                finish()
            }.onFailure {
                binding.activationStatus.text = getString(R.string.activation_failed, it.message)
                binding.activateButton.isEnabled = true
            }
        }
    }
}
