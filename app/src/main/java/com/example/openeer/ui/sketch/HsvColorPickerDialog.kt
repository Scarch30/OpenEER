package com.example.openeer.ui.sketch

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import com.example.openeer.R

class HsvColorPickerDialog(
    context: Context,
    initialColor: Int,
    private val onColorSelected: (Int) -> Unit
) {

    private val hsv = FloatArray(3)
    private val dialog: AlertDialog

    init {
        Color.colorToHSV(initialColor, hsv)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null, false)
        val preview = view.findViewById<View>(R.id.colorPreview)
        val seekHue = view.findViewById<SeekBar>(R.id.seekHue)
        val seekSaturation = view.findViewById<SeekBar>(R.id.seekSaturation)
        val seekValue = view.findViewById<SeekBar>(R.id.seekValue)

        seekHue.max = 360
        seekSaturation.max = 100
        seekValue.max = 100

        seekHue.progress = hsv[0].toInt().coerceIn(0, 360)
        seekSaturation.progress = (hsv[1] * 100f).toInt().coerceIn(0, 100)
        seekValue.progress = (hsv[2] * 100f).toInt().coerceIn(0, 100)

        fun updatePreview() {
            val color = Color.HSVToColor(hsv)
            ViewCompat.setBackgroundTintList(preview, ColorStateList.valueOf(color))
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                when (seekBar?.id) {
                    R.id.seekHue -> hsv[0] = progress.toFloat()
                    R.id.seekSaturation -> hsv[1] = progress / 100f
                    R.id.seekValue -> hsv[2] = progress / 100f
                }
                updatePreview()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        seekHue.setOnSeekBarChangeListener(listener)
        seekSaturation.setOnSeekBarChangeListener(listener)
        seekValue.setOnSeekBarChangeListener(listener)

        updatePreview()

        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.sketch_color_picker_title)
            .setView(view)
            .setPositiveButton(R.string.action_validate) { _, _ ->
                onColorSelected(Color.HSVToColor(hsv))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    fun show() {
        dialog.show()
    }
}
