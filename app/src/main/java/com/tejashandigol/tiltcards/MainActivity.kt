package com.tejashandigol.tiltcards

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val backgroundColor = 0xFF1C102C.toInt()
    private val panelColor = 0xFF3B146B.toInt()
    private val accent = 0xFFFFB458.toInt()
    private val foreground = 0xFFFFF3FB.toInt()
    private val muted = 0xFFE0C9ED.toInt()
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var cardButtons: LinearLayout
    private lateinit var choose: Button
    private lateinit var reset: Button
    private lateinit var apply: Button
    private lateinit var motion: TiltMotion
    private var motionEnabled = false
    private lateinit var enableMotion: Button
    private lateinit var haptic: SnapHaptic
    private var customCount = 0
    private var busy = false
    private var pickerFixed = false
    private var tappedAt = 0L
    private val photos: (List<java.io.File>) -> Unit = { cards ->
        customCount = cards.size
        preview.renderer.setCards(cards)
        preview.invalidate()
        updateCardButtons()
        updateActions()
        status.text = if (cards.isEmpty()) "Demo designs" else "${cards.size} photos saved on this phone"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerFixed = savedInstanceState?.getBoolean("picker_fixed") ?: PhotoStore.isFixed(this)
        window.setDecorFitsSystemWindowsCompat()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(backgroundColor)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(24))
        }
        scroll.addView(page)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        page.addView(label("Tilt Cards", 42, foreground, "sans-serif-medium"))
        page.addView(label("Photos that flow with your tilt.", 16, muted), column(top = 4))

        val preferences = getSharedPreferences("wallpaper", MODE_PRIVATE)
        val power = Switch(this).apply {
            text = "Wallpaper power"
            setTextColor(this@MainActivity.foreground)
            minHeight = dp(68)
            setPadding(dp(18), 0, dp(16), 0)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = 16f
            background = raisedSurface(panelColor, 20)
            elevation = dp(5).toFloat()
            switchMinWidth = dp(76)
            switchPadding = dp(12)
            splitTrack = false
            showText = false
            thumbDrawable = powerThumb()
            trackDrawable = powerTrack()
            thumbTintList = null
            trackTintList = null
            isChecked = preferences.getBoolean(WALLPAPER_POWER_ENABLED, true)
        }
        page.addView(power, column(top = 14))

        preview = PreviewView(this).apply {
            background = recessedSurface(22)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        page.addView(preview, column(height = dp(280), top = 22))
        val modes = android.widget.RadioGroup(this).apply {
            background = recessedSurface(18)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val tiltMode = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = "Tilt effect"
            minHeight = dp(48)
            setTextColor(this@MainActivity.foreground)
        }
        val fixedMode = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = "Fixed wallpaper · right tilt"
            minHeight = dp(48)
            setTextColor(this@MainActivity.foreground)
        }
        modes.addView(tiltMode)
        modes.addView(fixedMode)
        modes.check(if (preferences.getBoolean("fixed_mode", false)) fixedMode.id else tiltMode.id)
        val modeHelp = label("", 14, muted)
        fun updateModeHelp() {
            modeHelp.text = if (preferences.getBoolean("fixed_mode", false))
                "Small right tilt or wrist twist for next. Return to your resting hold to repeat."
                else "The wallpaper responds to your phone’s angle."
        }
        updateModeHelp()
        modes.setOnCheckedChangeListener { _, selected ->
            preferences.edit().putBoolean("fixed_mode", selected == fixedMode.id).apply()
            preview.renderer.setCards(emptyList())
            customCount = 0
            updateCardButtons()
            updateActions()
            PhotoStore.refresh(this)
            preview.renderer.syncSettings()
            preview.invalidate()
            haptic.reset()
            refreshSelection()
            updateModeHelp()
            motion.stop()
            if (motionEnabled) motion.start()
        }
        page.addView(modes, column(top = 12))
        page.addView(modeHelp, column(top = 4))
        enableMotion = action("Tap to Enable Motion", 0xFF7B208F.toInt(), foreground) {
            if (motion.start()) {
                motionEnabled = true
                enableMotion.animate().alpha(0f).setDuration(220).withEndAction {
                    enableMotion.visibility = View.GONE
                }.start()
                hint.text = "Tilt slightly left or right, or tap a card below."
            } else {
                hint.text = "Motion sensor unavailable. Tap a card below."
            }
        }
        page.addView(enableMotion, column(top = 10))
        hint = label("Tilt slightly left or right, or tap a card below.", 14, muted)
        page.addView(hint, column(top = 12))
        cardButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        page.addView(cardButtons, column(top = 8))
        status = label("", 14, muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        page.addView(status, column(top = 12))
        choose = action("Choose up to 3 photos", panelColor, foreground) { openPicker() }
        page.addView(choose, column(top = 10))
        apply = action("Apply live wallpaper", accent, backgroundColor) { openWallpaperPreview() }
        page.addView(apply, column(top = 10))
        reset = action("Use demo designs", panelColor, foreground) {
            setBusy(true)
            PhotoStore.replace(this, emptyList(), ::finishSave)
        }
        page.addView(reset, column(top = 10))
        page.addView(creatorCard(), column(top = 24))
        setContentView(scroll)
        updateCardButtons()
        updateActions()

        haptic = SnapHaptic(this)
        motion = TiltMotion(this) { tilt ->
            if (android.os.SystemClock.uptimeMillis() - tappedAt >= 1200 &&
                preview.renderer.tilt(tilt)) {
                haptic.update(preview.renderer.index)
                preview.invalidate()
                refreshSelection()
            }
        }
        power.setOnCheckedChangeListener { _, enabled ->
            preferences.edit().putBoolean(WALLPAPER_POWER_ENABLED, enabled).apply()
            if (!enabled) {
                motion.stop()
                haptic.reset()
                preview.renderer.settle()
                preview.invalidate()
            }
            updatePreviewPower()
        }
        PhotoStore.subscribe(this, photos)
    }

    override fun onResume() {
        super.onResume()
        preview.renderer.syncSettings()
        preview.invalidate()
        refreshSelection()
        updatePreviewPower()
    }

    private fun updatePreviewPower() {
        val enabled = getSharedPreferences("wallpaper", MODE_PRIVATE)
            .getBoolean(WALLPAPER_POWER_ENABLED, true)
        enableMotion.isEnabled = enabled
        when {
            !enabled -> hint.text = "Wallpaper motion is off."
            motionEnabled -> {
                motion.start()
                hint.text = "Tilt your phone, or tap a card below."
            }
            else -> hint.text = "Enable motion, or tap a card below."
        }
    }

    override fun onPause() {
        motion.stop()
        haptic.reset()
        super.onPause()
    }

    override fun onDestroy() {
        PhotoStore.unsubscribe(photos)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun android.view.Window.setDecorFitsSystemWindowsCompat() {
        if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
        else decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("picker_fixed", pickerFixed)
        super.onSaveInstanceState(outState)
    }

    private fun openPicker() {
        pickerFixed = PhotoStore.isFixed(this)
        if (Build.VERSION.SDK_INT >= 33) {
            val picker = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, if (pickerFixed) MediaStore.getPickImagesMaxLimit() else 3)
                if (Build.VERSION.SDK_INT >= 35) putExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, true)
            }
            if (launchPicker(picker)) return
        }
        val files = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        if (!launchPicker(files)) status.text = "No photo picker is available on this phone."
    }

    @Suppress("DEPRECATION")
    private fun launchPicker(intent: Intent): Boolean = try {
        startActivityForResult(intent, PICK_PHOTOS)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    @Deprecated("Uses platform activity results without an AndroidX dependency.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PHOTOS || resultCode != RESULT_OK || data == null) return
        val uris = linkedSetOf<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add)
        }
        data.data?.let(uris::add)
        if (uris.isEmpty() || (!pickerFixed && uris.size > 3)) {
            status.text = if (pickerFixed) "Select at least one photo." else "Choose up to 3 photos for tilt mode."
            return
        }
        setBusy(true)
        PhotoStore.importPhotos(this, uris.toList(), pickerFixed, pickerFixed, ::finishSave)
    }

    private fun finishSave(success: Boolean) {
        if (isDestroyed) return
        setBusy(false)
        if (!success) status.text = "Couldn’t save those photos. Try choosing them again."
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateActions()
        if (value) status.text = "Saving photos…"
    }

    private fun openWallpaperPreview() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, TiltWallpaperService::class.java))
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            status.text = "The system live wallpaper picker is unavailable."
        } catch (_: SecurityException) {
            status.text = "Live wallpaper preview is blocked by this phone’s settings."
        }
    }

    private fun updateActions() {
        choose.text = if (PhotoStore.isFixed(this)) "Add wallpapers" else "Choose up to 3 photos"
        choose.isEnabled = !busy
        apply.isEnabled = !busy
        reset.isEnabled = !busy
        reset.visibility = if (customCount == 0) View.GONE else View.VISIBLE
    }

    private fun updateCardButtons() {
        cardButtons.removeAllViews()
        if (PhotoStore.isFixed(this)) {
            val next = action("Next wallpaper", panelColor, foreground) {
                tappedAt = android.os.SystemClock.uptimeMillis()
                preview.renderer.select((preview.renderer.index + 1) % preview.renderer.count)
                preview.invalidate()
                refreshSelection()
            }
            cardButtons.addView(next, LinearLayout.LayoutParams(-1, dp(48)))
            refreshSelection()
            return
        }
        repeat(preview.renderer.count) { index ->
            val button = action((index + 1).toString(), panelColor, foreground) {
                tappedAt = android.os.SystemClock.uptimeMillis()
                preview.renderer.select(index)
                preview.invalidate()
                refreshSelection()
            }
            cardButtons.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = if (index == preview.renderer.count - 1) 0 else dp(8)
            })
        }
        refreshSelection()
    }

    private fun refreshSelection() {
        if (PhotoStore.isFixed(this)) {
            (cardButtons.getChildAt(0) as? Button)?.text =
                "Next wallpaper · ${preview.renderer.index + 1} / ${preview.renderer.count}"
            return
        }
        for (index in 0 until cardButtons.childCount) {
            val button = cardButtons.getChildAt(index) as Button
            val selected = index == preview.renderer.index
            button.isSelected = selected
            button.contentDescription = "Card ${index + 1}"
            button.setTextColor(if (selected) backgroundColor else foreground)
            button.background = buttonBackground(if (selected) accent else panelColor)
        }
    }

    private fun creatorCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xFF221331.toInt(), 23).apply {
            setStroke(dp(1), 0xFF633B85.toInt())
        }
        clipToOutline = true
        elevation = dp(5).toFloat()

        val cover = FrameLayout(this@MainActivity).apply {
            setBackgroundColor(backgroundColor)
        }
        val portrait = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.creator_tejas)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cover.addView(portrait, FrameLayout.LayoutParams(-1, -1))
        cover.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width > 0 && width != oldRight - oldLeft) {
                portrait.layoutParams = FrameLayout.LayoutParams(
                    (width * 0.65f).toInt(), -1, Gravity.RIGHT
                )
            }
        }
        cover.addView(View(this@MainActivity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(backgroundColor, 0xEE241138.toInt(), 0x00241138))
        }, FrameLayout.LayoutParams(-1, -1))
        cover.addView(View(this@MainActivity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xFF261139.toInt(), 0x00261139))
        }, FrameLayout.LayoutParams(-1, dp(88), Gravity.BOTTOM))

        val credit = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Created by", 13, 0xFFFFD0BF.toInt()))
            addView(label("Tejas\nHandigol", 30, this@MainActivity.foreground, "sans-serif-medium").apply {
                letterSpacing = -0.035f
                includeFontPadding = false
            })
        }
        cover.addView(credit, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(18)
            bottomMargin = dp(18)
        })
        addView(cover, column(height = dp(184)))

        val links = LinearLayout(this@MainActivity).apply {
            setPadding(dp(12), dp(12), dp(12), dp(14))
        }
        listOf(
            "LinkedIn" to "https://in.linkedin.com/in/tejashandigol",
            "Instagram" to "https://www.instagram.com/tejas__8055/",
            "GitHub" to "https://github.com/tejas-ai"
        ).forEachIndexed { index, (name, url) ->
            val primary = index == 0
            val button = action(name, if (primary) accent else 0xFF312043.toInt(),
                if (primary) backgroundColor else this@MainActivity.foreground) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No app can open this link.", Toast.LENGTH_SHORT).show()
                }
            }.apply {
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(3), dp(6), dp(3), dp(6))
            }
            links.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        addView(links, column())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun column(height: Int = -2, top: Int = 0) =
        LinearLayout.LayoutParams(-1, height).apply { topMargin = dp(top) }

    private fun label(value: String, size: Int, color: Int, font: String = "sans-serif") =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            typeface = Typeface.create(font, Typeface.NORMAL)
        }

    private fun action(label: String, fill: Int, ink: Int, run: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        letterSpacing = 0f
        minimumHeight = dp(52)
        minHeight = dp(52)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ink)
        background = buttonBackground(fill)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        elevation = dp(3).toFloat()
        stateListAnimator = null
        setOnClickListener { run() }
    }

    private fun buttonBackground(fill: Int): RippleDrawable {
        val pressed = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(tone(fill, 0.67f), tone(fill, 0.91f))
            ).apply {
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), tone(fill, 0.53f))
            })
            addState(intArrayOf(), raisedSurface(fill, 13))
        }
        return RippleDrawable(ColorStateList.valueOf(0x38F36A78), pressed, rounded(Color.WHITE, 13))
    }

    private fun raisedSurface(fill: Int, radius: Int): LayerDrawable {
        val base = rounded(tone(fill, 0.49f), radius)
        val face = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (fill == accent) intArrayOf(
                0xFFFFB458.toInt(), 0xFFFF9B55.toInt(), 0xFFF87572.toInt()
            ) else intArrayOf(tone(fill, 1.40f), fill, tone(fill, 0.78f))
        ).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), tone(fill, 1.45f))
        }
        return LayerDrawable(arrayOf(base, face)).apply {
            setLayerInset(1, 0, 0, 0, dp(3))
        }
    }

    private fun recessedSurface(radius: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF150C25.toInt(), 0xFF32164E.toInt())
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), 0xFF69408E.toInt())
    }

    private fun powerThumb(): LayerDrawable {
        val rim = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFFFEBE1.toInt(), 0xFF9C3D9F.toInt())).apply {
            shape = GradientDrawable.OVAL
            setSize(dp(32), dp(32))
        }
        val cap = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFFFF4F0.toInt(), 0xFFF5C5DF.toInt(), 0xFFCB8EC1.toInt())).apply {
            shape = GradientDrawable.OVAL
        }
        return LayerDrawable(arrayOf(rim, cap)).apply {
            setLayerInset(1, dp(2), dp(2), dp(2), dp(2))
        }
    }

    private fun powerTrack(): StateListDrawable {
        fun track(top: Int, bottom: Int, rim: Int) = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
        ).apply {
            cornerRadius = dp(20).toFloat()
            setSize(dp(76), dp(36))
            setStroke(dp(1), rim)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked),
                track(0xFFC9349B.toInt(), 0xFFFF9B55.toInt(), 0xFFF87572.toInt()))
            addState(intArrayOf(),
                track(0xFF190D2A.toInt(), 0xFF3B146B.toInt(), 0xFF7B208F.toInt()))
        }
    }

    private fun tone(color: Int, factor: Float) = Color.argb(
        Color.alpha(color),
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    private class PreviewView(context: android.content.Context) : View(context) {
        val renderer = CardRenderer(context).apply { onImageReady = { postInvalidateOnAnimation() } }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val display = resources.displayMetrics
            val portraitRatio = minOf(display.widthPixels, display.heightPixels).toFloat() /
                maxOf(display.widthPixels, display.heightPixels).coerceAtLeast(1)
            val cardWidth = minOf(width.toFloat(), height * portraitRatio)
            val left = (width - cardWidth) / 2f
            canvas.save()
            canvas.clipRect(left, 0f, left + cardWidth, height.toFloat())
            canvas.translate(left, 0f)
            val animating = renderer.draw(canvas, cardWidth, height.toFloat())
            canvas.restore()
            if (animating) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val PICK_PHOTOS = 5
    }
}
