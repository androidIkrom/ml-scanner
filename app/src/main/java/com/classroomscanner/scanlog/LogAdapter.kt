package com.classroomscanner.scanlog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.classroomscanner.core.LogEntry
import com.classroomscanner.databinding.ItemLogEntryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : ListAdapter<LogEntry, LogAdapter.Holder>(Diff) {

    class Holder(val binding: ItemLogEntryBinding) : RecyclerView.ViewHolder(binding.root)

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemLogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        holder.binding.time.text = timeFormat.format(Date(entry.timeMs))
        holder.binding.text.text = entry.text
    }

    private object Diff : DiffUtil.ItemCallback<LogEntry>() {
        // Entries are append-only, so identity by time and text is enough.
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry) = oldItem == newItem
        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry) = oldItem == newItem
    }
}
