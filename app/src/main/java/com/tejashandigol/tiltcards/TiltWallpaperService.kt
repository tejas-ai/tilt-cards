package com.tejashandigol.tiltcards

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.view.Choreographer

class TiltWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = TiltEngine()

    private inner class TiltEngine : Engine() {
        private val choreographer = Choreographer.getInstance()
        private val renderer = CardRenderer(this@TiltWallpaperService)
        private val preferences = getSharedPreferences("wallpaper", MODE_PRIVATE)
        private val powerEnabled get() = preferences.getBoolean(WALLPAPER_POWER_ENABLED, true)
        private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "fixed_mode") {
                renderer.setCards(emptyList())
                PhotoStore.refresh(this@TiltWallpaperService)
                renderer.syncSettings()
                haptic.reset()
                motion.stop()
                updateSensor()
                drawFrame()
            } else if (key == WALLPAPER_POWER_ENABLED) {
                updateSensor()
                drawFrame()
            }
        }
        private val haptic = SnapHaptic(this@TiltWallpaperService)
        private var visible = false
        private var surfaceReady = false
        private var needsInitialPosition = true
        private val frame = Choreographer.FrameCallback { drawFrame() }
        private val motion = TiltMotion(this@TiltWallpaperService) { tilt ->
            val changed = renderer.tilt(tilt)
            haptic.update(renderer.index)
            if (needsInitialPosition) {
                needsInitialPosition = false
                renderer.settle()
                drawFrame()
            } else if (changed) {
                choreographer.removeFrameCallback(frame)
                choreographer.postFrameCallback(frame)
            }
        }
        private val photos: (List<java.io.File>) -> Unit = {
            renderer.setCards(it)
            drawFrame()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer.onImageReady = { drawFrame() }
            preferences.registerOnSharedPreferenceChangeListener(settingsListener)
            PhotoStore.subscribe(this@TiltWallpaperService, photos)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                renderer.syncSettings()
                drawFrame()
            }
            updateSensor()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            drawFrame()
            updateSensor()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            updateSensor()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            visible = false
            updateSensor()
            preferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
            renderer.onImageReady = {}
            PhotoStore.unsubscribe(photos)
            super.onDestroy()
        }

        private fun updateSensor() {
            if (powerEnabled && visible && surfaceReady) {
                motion.start()
            } else {
                motion.stop()
                choreographer.removeFrameCallback(frame)
                needsInitialPosition = true
                haptic.reset()
                renderer.settle()
            }
        }

        private fun drawFrame() {
            choreographer.removeFrameCallback(frame)
            if (!surfaceReady) return
            val holder = surfaceHolder
            if (!holder.surface.isValid) return
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (_: IllegalStateException) {
                return
            } catch (_: IllegalArgumentException) {
                return
            }
            val animating = try {
                renderer.draw(canvas, canvas.width.toFloat(), canvas.height.toFloat())
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            if (animating && powerEnabled && visible) choreographer.postFrameCallback(frame)
        }
    }
}
