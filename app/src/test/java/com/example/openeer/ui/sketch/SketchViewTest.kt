package com.example.openeer.ui.sketch

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SketchViewTest {
    @Test
    fun hasContentAfterStroke() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val view = SketchView(ctx)
        assertFalse(view.hasContent())
        view.setMode(SketchView.Mode.PEN)
        val down = MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,0f,0f,0)
        val move = MotionEvent.obtain(0,10,MotionEvent.ACTION_MOVE,10f,10f,0)
        val up = MotionEvent.obtain(0,20,MotionEvent.ACTION_UP,20f,20f,0)
        view.onTouchEvent(down)
        view.onTouchEvent(move)
        view.onTouchEvent(up)
        assertTrue(view.hasContent())
    }
}
