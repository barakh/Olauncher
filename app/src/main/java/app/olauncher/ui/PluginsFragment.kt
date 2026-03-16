package app.olauncher.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.DailyReminder
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentPluginsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
        populateDailyReminderUI()
        initClickListeners()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.showPermanentNote -> togglePermanentNote()
            R.id.showDailyReminder -> toggleDailyReminder()
            R.id.btnAddReminder -> addNewReminder()
        }
    }

    private fun initClickListeners() {
        binding.showPermanentNote.setOnClickListener(this)
        binding.showDailyReminder.setOnClickListener(this)
        binding.btnAddReminder.setOnClickListener(this)
    }

    private fun togglePermanentNote() {
        prefs.showPermanentNote = !prefs.showPermanentNote
        populatePermanentNoteToggle()
        viewModel.refreshHome(true)
    }

    private fun populatePermanentNoteToggle() {
        binding.showPermanentNote.text = getString(if (prefs.showPermanentNote) R.string.on else R.string.off)
    }

    private fun toggleDailyReminder() {
        prefs.showDailyReminder = !prefs.showDailyReminder
        populateDailyReminderUI()
        viewModel.refreshHome(true)
    }

    private fun populateDailyReminderUI() {
        binding.showDailyReminder.text = getString(if (prefs.showDailyReminder) R.string.on else R.string.off)
        binding.dailyReminderSettings.isVisible = prefs.showDailyReminder
        
        binding.remindersList.removeAllViews()
        prefs.dailyReminders.forEach { reminder ->
            val view = layoutInflater.inflate(android.R.layout.simple_list_item_2, binding.remindersList, false)
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)
            
            text1.text = reminder.text
            text1.setTextColor(requireContext().getColor(R.color.white))
            text2.text = reminder.time
            text2.setTextColor(requireContext().getColor(R.color.white))
            
            view.setOnClickListener { showEditReminderDialog(reminder) }
            view.setOnLongClickListener {
                showDeleteReminderDialog(reminder)
                true
            }
            binding.remindersList.addView(view)
        }
    }

    private fun addNewReminder() {
        val reminder = DailyReminder(text = "", time = "08:00")
        showEditReminderDialog(reminder, isNew = true)
    }

    private fun showEditReminderDialog(reminder: DailyReminder, isNew: Boolean = false) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_reminder, null)
        val etText = view.findViewById<EditText>(R.id.etReminderText)
        val tvTime = view.findViewById<TextView>(R.id.tvReminderTime)
        
        etText.setText(reminder.text)
        tvTime.text = reminder.time
        
        tvTime.setOnClickListener {
            val timeParts = tvTime.text.split(":")
            TimePickerDialog(requireContext(), { _, h, m ->
                tvTime.text = String.format("%02d:%02d", h, m)
            }, timeParts[0].toInt(), timeParts[1].toInt(), true).show()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isNew) R.string.add_reminder else R.string.daily_reminder)
            .setView(view)
            .setPositiveButton(R.string.okay) { _, _ ->
                reminder.text = etText.text.toString()
                reminder.time = tvTime.text.toString()
                
                val list = prefs.dailyReminders.toMutableList()
                if (isNew) {
                    list.add(reminder)
                } else {
                    val index = list.indexOfFirst { it.id == reminder.id }
                    if (index != -1) list[index] = reminder
                }
                prefs.dailyReminders = list
                populateDailyReminderUI()
                viewModel.refreshHome(true)
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun showDeleteReminderDialog(reminder: DailyReminder) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_reminder)
            .setMessage(reminder.text)
            .setPositiveButton(R.string.delete) { _, _ ->
                val list = prefs.dailyReminders.toMutableList()
                list.removeAll { it.id == reminder.id }
                prefs.dailyReminders = list
                populateDailyReminderUI()
                viewModel.refreshHome(true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
