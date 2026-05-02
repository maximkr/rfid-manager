package com.trackstudio.rfidmanager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.trackstudio.rfidmanager.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val mainActivity get() = activity as? MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupFrequencyDropdown()
        setupPowerSlider()
        
        binding.btnReconnect.setOnClickListener {
            mainActivity?.reconnectUhf()
        }

        // Initial sync
        mainActivity?.updateSettingsFragmentUI(this)
    }

    private fun setupFrequencyDropdown() {
        val freqModes = resources.getStringArray(R.array.frequency_modes)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, freqModes)
        binding.spinnerFreqMode.setAdapter(adapter)
        binding.spinnerFreqMode.setOnItemClickListener { _, _, position, _ ->
            mainActivity?.saveFreqMode(position)
            mainActivity?.appendLog("Set freq mode: ${freqModes[position]}")
        }
    }

    private fun setupPowerSlider() {
        binding.sliderPower.addOnChangeListener { _, value, fromUser ->
            binding.tvPowerValue.text = getString(R.string.unit_dbm, value.toInt())
            if (fromUser) {
                mainActivity?.savePower(value.toInt())
            }
        }
    }

    fun updateUI(freqMode: Int, power: Int) {
        val freqModes = resources.getStringArray(R.array.frequency_modes)
        if (freqMode in freqModes.indices) {
            binding.spinnerFreqMode.setText(freqModes[freqMode], false)
        }
        
        if (power in 5..30) {
            binding.sliderPower.value = power.toFloat()
        }
        binding.tvPowerValue.text = getString(R.string.unit_dbm, power)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
