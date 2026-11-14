package au.com.shiftyjelly.pocketcasts.player.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import au.com.shiftyjelly.pocketcasts.models.to.AdSegment
import au.com.shiftyjelly.pocketcasts.models.to.Chapters
import au.com.shiftyjelly.pocketcasts.player.R
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.ui.theme.ThemeColor
import au.com.shiftyjelly.pocketcasts.utils.toHhMmSs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import me.zhanghai.android.materialprogressbar.MaterialProgressBar
import au.com.shiftyjelly.pocketcasts.localization.R as LR

class PlayerSeekBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    private val view = inflater.inflate(R.layout.view_playback_seek_bar, this)
    private var seeking = false
    private var currentTime = Duration.ZERO
    private var duration = Duration.ZERO
    private var chapters = Chapters()
    private var adSegments: List<AdSegment> = emptyList()
    private var playbackSpeed = 1.0
    private var adjustDuration = false
    private val adMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seekBar = view.findViewById(R.id.seekBarInternal) as AppCompatSeekBar
    private val elapsedTimeText = view.findViewById(R.id.elapsedTime) as TextView
    private val remainingTimeText = view.findViewById(R.id.remainingTime) as TextView
    private val bufferingSeekbar = view.findViewById(R.id.indeterminateProgressBar) as MaterialProgressBar

    var bufferedUpToInSecs: Int = 0
        set(value) {
            field = value
            seekBar.secondaryProgress = value
        }

    var isBuffering: Boolean = false
        set(value) {
            field = value
            bufferingSeekbar.isVisible = value
        }

    var changeListener: OnUserSeekListener? = null

    init {
        setupSeekBar()
    }

    fun setAdjustDuration(adjust: Boolean) {
        this.adjustDuration = adjust
        updateTextViews()
    }

    fun setCurrentTime(currentTime: Duration) {
        if (seeking) {
            return
        }
        this.currentTime = currentTime
        seekBar.progress = currentTime.inWholeSeconds.toInt()
        updateTextViews()
    }

    fun setChapters(chapters: Chapters) {
        if (seeking) {
            return
        }
        this.chapters = chapters
        updateTextViews()
    }

    fun setAdSegments(segments: List<AdSegment>) {
        this.adSegments = segments
        invalidate()
    }

    fun setDuration(duration: Duration) {
        this.duration = duration
        seekBar.max = duration.inWholeSeconds.toInt()
        updateTextViews()
    }

    fun setPlaybackSpeed(playbackSpeed: Double) {
        this.playbackSpeed = playbackSpeed
        updateTextViews()
    }

    fun setTintColor(color: Int?, theme: Theme.ThemeType) {
        val themeColor = ThemeColor.playerHighlight01(theme, color ?: Color.WHITE)
        seekBar.thumbTintList = ColorStateList.valueOf(ThemeColor.playerContrast01(theme))
        seekBar.progressTintList = ColorStateList.valueOf(ThemeColor.playerContrast01(theme))
        seekBar.secondaryProgressTintList = ColorStateList.valueOf(ThemeColor.playerContrast05(theme))
        seekBar.backgroundTintList = ColorStateList.valueOf(ThemeColor.playerContrast05(theme))

        adMarkerPaint.color = ColorUtils.setAlphaComponent(ThemeColor.playerContrast01(theme), 0x99)
        invalidate()

        with(bufferingSeekbar) {
            supportIndeterminateTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(themeColor, 0x1A))
        }

        elapsedTimeText.setTextColor(ThemeColor.playerContrast02(theme))
        remainingTimeText.setTextColor(ThemeColor.playerContrast02(theme))
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawAdMarkers(canvas)
    }

    /**
     * Draws a small bar over the seek bar for every advertising segment so listeners can see where the
     * ad breaks are, whether or not skipping is enabled.
     */
    private fun drawAdMarkers(canvas: Canvas) {
        if (adSegments.isEmpty() || duration <= Duration.ZERO) {
            return
        }
        val totalMs = duration.inWholeMilliseconds.toFloat()
        if (totalMs <= 0f) return

        val left = (seekBar.left + seekBar.paddingLeft).toFloat()
        val right = (seekBar.right - seekBar.paddingRight).toFloat()
        val trackWidth = right - left
        if (trackWidth <= 0f) return

        val trackCenterY = seekBar.top + seekBar.height / 2f - seekBar.paddingBottom / 2f
        val halfHeight = 2.dpToPx()
        for (segment in adSegments) {
            val startFraction = segment.startTime.inWholeMilliseconds.toFloat() / totalMs
            val endFraction = segment.endTime.inWholeMilliseconds.toFloat() / totalMs
            if (endFraction <= 0f || startFraction >= 1f) continue
            val startX = left + trackWidth * startFraction.coerceIn(0f, 1f)
            val endX = left + trackWidth * endFraction.coerceIn(0f, 1f)
            canvas.drawRoundRect(
                startX,
                trackCenterY - halfHeight,
                maxOf(endX, startX + 2f),
                trackCenterY + halfHeight,
                halfHeight,
                halfHeight,
                adMarkerPaint,
            )
        }
    }

    private fun Int.dpToPx(): Float = this * resources.displayMetrics.density

    private fun setupSeekBar() {
        bufferingSeekbar.isVisible = false
        seekBar.secondaryProgress = 0
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                changeListener?.onSeekPositionChangeStop(currentTime) {
                    seeking = false
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                changeListener?.onSeekPositionChangeStart()
                seeking = true
            }

            override fun onProgressChanged(seekBar: SeekBar, progressSecs: Int, fromUser: Boolean) {
                if (!fromUser) return

                currentTime = progressSecs.seconds
                updateTextViews()

                changeListener?.onSeekPositionChanging(currentTime)
            }
        })
    }

    private fun updateTextViews() {
        if (duration <= Duration.ZERO) {
            elapsedTimeText.text = ""
            elapsedTimeText.contentDescription = ""
            remainingTimeText.text = ""
            remainingTimeText.contentDescription = ""
        } else {
            val elapsedTime = currentTime.toHhMmSs()
            elapsedTimeText.text = currentTime.toHhMmSs()
            elapsedTimeText.contentDescription = resources.getString(LR.string.player_played_up_to, elapsedTime)
            val timeLeft = remainingDuration()
            val remainingTime = buildString {
                if (timeLeft > Duration.ZERO) {
                    append('-')
                }
                append(timeLeft.toHhMmSs())
            }
            remainingTimeText.text = remainingTime
            remainingTimeText.contentDescription = resources.getString(LR.string.player_time_remaining, remainingTime.removePrefix("-"))
        }
    }

    private fun remainingDuration(): Duration {
        return if (adjustDuration) {
            (duration - currentTime - chapters.skippedChaptersDuration(currentTime)) / playbackSpeed
        } else {
            duration - currentTime
        }.coerceAtLeast(Duration.ZERO)
    }

    interface OnUserSeekListener {
        fun onSeekPositionChangeStop(progress: Duration, seekComplete: () -> Unit)
        fun onSeekPositionChanging(progress: Duration)
        fun onSeekPositionChangeStart()
    }
}
