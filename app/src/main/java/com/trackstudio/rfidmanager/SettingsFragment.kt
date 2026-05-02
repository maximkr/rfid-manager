package com.trackstudio.rfidmanager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.trackstudio.rfidmanager.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val mainActivity get() = activity as? MainActivity
    private val viewModel: SharedViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        viewModel.isConnected.observe(viewLifecycleOwner) { connected ->
            binding.tilFreqMode.isEnabled = connected
        }

        setupFrequencyDropdown()
        
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
            val modeId = when (position) {
                0 -> MainActivity.MODE_CHINA_920_925
                1 -> MainActivity.MODE_USA_902_928
                2 -> MainActivity.MODE_EUROPE_865_868
                3 -> MainActivity.MODE_CHINA_840_845
                4 -> MainActivity.MODE_KOREA
                else -> MainActivity.MODE_EUROPE_865_868
            }
            mainActivity?.saveFreqMode(modeId)
            mainActivity?.appendLog("Set freq mode: ${freqModes[position]} (0x${Integer.toHexString(modeId)})")
        }
    }

    fun updateUI(freqModeId: Int) {
        val position = when (freqModeId) {
            MainActivity.MODE_CHINA_920_925 -> 0
            MainActivity.MODE_USA_902_928 -> 1
            MainActivity.MODE_EUROPE_865_868 -> 2
            MainActivity.MODE_CHINA_840_845 -> 3
            MainActivity.MODE_KOREA -> 4
            else -> -1
        }
        
        if (position != -1) {
            val freqModes = resources.getStringArray(R.array.frequency_modes)
            binding.spinnerFreqMode.setText(freqModes[position], false)
        } else {
            val hex = "0x${Integer.toHexString(freqModeId)}"
            binding.spinnerFreqMode.setText(hex, false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
