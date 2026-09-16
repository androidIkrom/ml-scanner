package com.classroomscanner.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.classroomscanner.R
import com.classroomscanner.databinding.ItemScanBinding
import java.text.DateFormat
import java.util.Date

class HistoryAdapter(private val onPlay: (ScanEntity) -> Unit) :
    ListAdapter<ScanEntity, HistoryAdapter.Holder>(Diff) {

    class Holder(val binding: ItemScanBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemScanBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val scan = getItem(position)
        val context = holder.itemView.context
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(scan.startedAt))
        holder.binding.title.text = context.getString(R.string.history_item_title, date, scan.mode, scan.coveragePercent)
        holder.binding.summary.text = scan.summaryText
        holder.binding.play.setOnClickListener { onPlay(scan) }
    }

    private object Diff : DiffUtil.ItemCallback<ScanEntity>() {
        override fun areItemsTheSame(oldItem: ScanEntity, newItem: ScanEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ScanEntity, newItem: ScanEntity) = oldItem == newItem
    }
}
