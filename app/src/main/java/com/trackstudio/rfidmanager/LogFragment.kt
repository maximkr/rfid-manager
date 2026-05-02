package com.trackstudio.rfidmanager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.trackstudio.rfidmanager.databinding.FragmentLogBinding

class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!
    private val mainActivity get() = activity as? MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainActivity?.updateLogFragmentUI(this)
    }

    fun setLogs(logs: List<String>) {
        binding.tvLog.text = logs.joinToString("\n")
        binding.logScrollView.post { binding.logScrollView.fullScroll(View.FOCUS_UP) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
