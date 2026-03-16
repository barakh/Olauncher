package app.olauncher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentPluginsBinding

class PluginsFragment : Fragment(), View.OnClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel

    private var _binding: FragmentPluginsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPluginsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        populatePermanentNoteToggle()
        initClickListeners()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.showPermanentNote -> togglePermanentNote()
        }
    }

    private fun initClickListeners() {
        binding.showPermanentNote.setOnClickListener(this)
    }

    private fun togglePermanentNote() {
        prefs.showPermanentNote = !prefs.showPermanentNote
        populatePermanentNoteToggle()
        viewModel.refreshHome(true)
    }

    private fun populatePermanentNoteToggle() {
        binding.showPermanentNote.text = getString(if (prefs.showPermanentNote) R.string.on else R.string.off)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
