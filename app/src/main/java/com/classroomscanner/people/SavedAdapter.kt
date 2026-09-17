package com.classroomscanner.people

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.classroomscanner.databinding.ItemPersonBinding

/** One row in a Saved tab: a person, car or object. */
data class SavedRow(val id: Long, val name: String, val photoPath: String)

class SavedAdapter(private val onOpen: (SavedRow) -> Unit) :
    ListAdapter<SavedRow, SavedAdapter.Holder>(Diff) {

    class Holder(val binding: ItemPersonBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.binding.name.text = row.name
        holder.binding.photo.setImageBitmap(PhotoLoader.load(row.photoPath, THUMB_PX))
        holder.binding.root.setOnClickListener { onOpen(row) }
    }

    private object Diff : DiffUtil.ItemCallback<SavedRow>() {
        override fun areItemsTheSame(oldItem: SavedRow, newItem: SavedRow) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SavedRow, newItem: SavedRow) = oldItem == newItem
    }

    private companion object {
        const val THUMB_PX = 168
    }
}

/** Decodes a saved face photo, downsampled to roughly [targetPx]. */
object PhotoLoader {
    fun load(path: String, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
