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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWriteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnWriteRFID.setOnClickListener {
            mainActivity?.performWriteRFID(binding.editCode.text.toString().trim())
        }

        // Restore current state if any
        mainActivity?.let { activity ->
            activity.updateWriteFragmentUI(this)
        }
    }

    fun appendLog(timestamp: String, message: String, logList: List<String>) {
        binding.tvLog.text = logList.joinToString("\n")
        binding.logScrollView.post { binding.logScrollView.fullScroll(View.FOCUS_UP) }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
