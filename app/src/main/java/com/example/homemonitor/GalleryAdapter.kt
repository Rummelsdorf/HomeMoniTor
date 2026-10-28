package com.example.homemonitor

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView

/**
 * Adapter für die Galerie im Hauptbildschirm.
 * Hält eine Liste von Bitmaps und zeigt sie in einer ListView an.
 * Verbesserungen gegenüber dem inneren Adapter:
 *   - Separates File (besser wartbar, testbar)
 *   - Verwendet View-Recycling (convertView)
 *
 * Anmerkung: Für große Bilder sollte hier ein Downsampling (BitmapFactory.Options.inSampleSize)
 * oder eine Bildbibliothek (z.B. Glide/Coil) genutzt werden, um OutOfMemoryError zu vermeiden.
 */
class GalleryAdapter(private val context: Context) : BaseAdapter() {

    private val items = mutableListOf<Bitmap>()

    fun submit(list: List<Bitmap>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Bitmap = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val imageView = if (convertView is ImageView) {
            convertView
        } else {
            LayoutInflater.from(context).inflate(R.layout.item_gallery, parent, false) as ImageView
        }
        imageView.setImageBitmap(items[position])
        return imageView
    }
}
