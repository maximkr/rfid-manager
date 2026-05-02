package com.trackstudio.rfidmanager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.trackstudio.rfidmanager.databinding.FragmentWriteBinding
import com.google.android.material.card.MaterialCardView

class WriteFragment : Fragment() {

    private var _binding: FragmentWriteBinding? = null
    private val binding get() = _binding!!
    private val mainActivity get() = activity as? MainActivity
    private val viewModel: SharedViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWriteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        viewModel.isConnected.observe(viewLifecycleOwner) { connected ->
            binding.btnWriteRFID.isEnabled = connected
        }
        
        binding.btnWriteRFID.setOnClickListener {
            mainActivity?.performWriteRFID(binding.editCode.text.toString().trim())
        }

        binding.sliderWritePower.addOnChangeListener { _, value, fromUser ->
            binding.tvWritePowerValue.text = getString(R.string.unit_dbm, value.toInt())
            if (fromUser) {
                mainActivity?.saveWritePower(value.toInt())
            }
        }

        // Restore current state if any
        mainActivity?.let { activity ->
            activity.updateWriteFragmentUI(this)
            val savedPower = activity.getWritePower()
            binding.sliderWritePower.value = savedPower.toFloat()
            binding.tvWritePowerValue.text = getString(R.string.unit_dbm, savedPower)
        }
    }

    fun addHistoryTag(code: String, success: Boolean) {
        val context = context ?: return
        val tagView = layoutInflater.inflate(R.layout.history_tag, binding.historyContainer, false)
        val tv = tagView.findViewById<TextView>(R.id.tagText)
        val card = tagView as MaterialCardView

        tv.text = code
        val colorRes = if (success) R.color.md_theme_primary else R.color.md_theme_error
        card.setCardBackgroundColor(ContextCompat.getColor(context, colorRes))

        binding.historyContainer.addView(tagView, 0)
        binding.historyScroll.post { binding.historyScroll.fullScroll(View.FOCUS_LEFT) }
    }

    fun setBarcodeData(data: String) {
        binding.editCode.setText(data)
    }

    override fun onResume() {
        super.onResume()
        mainActivity?.openBarcode()
    }

    override fun onPause() {
        super.onPause()
        mainActivity?.closeBarcode()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
