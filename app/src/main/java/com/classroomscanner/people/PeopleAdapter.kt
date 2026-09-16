package com.classroomscanner.people

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.classroomscanner.databinding.ItemPersonBinding

class PeopleAdapter(private val onOpen: (PersonEntity) -> Unit) :
    ListAdapter<PersonEntity, PeopleAdapter.Holder>(Diff) {

    class Holder(val binding: ItemPersonBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val person = getItem(position)
        holder.binding.name.text = person.name
        holder.binding.photo.setImageBitmap(PhotoLoader.load(person.photoPath, THUMB_PX))
        holder.binding.root.setOnClickListener { onOpen(person) }
    }

    private object Diff : DiffUtil.ItemCallback<PersonEntity>() {
        override fun areItemsTheSame(oldItem: PersonEntity, newItem: PersonEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PersonEntity, newItem: PersonEntity) = oldItem == newItem
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
