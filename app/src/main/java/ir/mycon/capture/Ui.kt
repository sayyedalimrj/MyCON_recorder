package ir.mycon.capture

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

object Ui {
    val BG = Color.parseColor("#0A0F1A")
    val SURFACE = Color.parseColor("#D9161E2D")
    val SURFACE_SOLID = Color.parseColor("#161E2D")
    val SURFACE_2 = Color.parseColor("#202B3D")
    val TEXT = Color.parseColor("#F8FAFC")
    val MUTED = Color.parseColor("#A8B3C7")
    val GREEN = Color.parseColor("#22C55E")
    val AMBER = Color.parseColor("#F59E0B")
    val RED = Color.parseColor("#EF4444")
    val BLUE = Color.parseColor("#38BDF8")
    val PURPLE = Color.parseColor("#A78BFA")

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun rounded(
        color: Int,
        radiusDp: Int,
        context: Context,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeColor != null) {
            setStroke(dp(context, strokeWidthDp), strokeColor)
        }
    }

    fun card(context: Context, alphaSurface: Boolean = false): MaterialCardView =
        MaterialCardView(context).apply {
            radius = dp(context, 20).toFloat()
            cardElevation = dp(context, 4).toFloat()
            strokeWidth = dp(context, 1)
            strokeColor = ColorUtils.setAlphaComponent(Color.WHITE, 28)
            setCardBackgroundColor(if (alphaSurface) SURFACE else SURFACE_SOLID)
        }

    fun title(context: Context, text: String, sizeSp: Float = 20f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
        }

    fun label(context: Context, text: String, sizeSp: Float = 13f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(MUTED)
        }

    fun pill(context: Context, text: String, color: Int): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7))
            background = rounded(
                ColorUtils.setAlphaComponent(color, 70),
                18,
                context,
                ColorUtils.setAlphaComponent(color, 150)
            )
        }

    fun setPill(view: TextView, text: String, color: Int) {
        view.text = text
        view.background = rounded(
            ColorUtils.setAlphaComponent(color, 70),
            18,
            view.context,
            ColorUtils.setAlphaComponent(color, 150)
        )
    }

    fun button(
        context: Context,
        text: String,
        color: Int = SURFACE_2,
        textColor: Int = TEXT
    ): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 14f
            isAllCaps = false
            cornerRadius = dp(context, 16)
            minHeight = dp(context, 48)
            backgroundTintList = ColorStateList.valueOf(color)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(context, 14), 0, dp(context, 14), 0)
        }

    fun primaryButton(context: Context, text: String): MaterialButton =
        button(context, text, GREEN).apply {
            textSize = 17f
            minHeight = dp(context, 60)
            cornerRadius = dp(context, 30)
            setTypeface(typeface, Typeface.BOLD)
        }

    fun addSpacer(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(parent.context), LinearLayout.LayoutParams(1, dp(parent.context, heightDp)))
    }

    fun edgeToEdge(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(
            activity.window,
            activity.window.decorView
        ).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    fun insetWholeRoot(root: View) {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun insetOverlayPanels(root: View, top: View, bottom: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (top.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = bars.top + dp(top.context, 8)
                lp.leftMargin = bars.left + dp(top.context, 8)
                lp.rightMargin = bars.right + dp(top.context, 8)
                top.layoutParams = lp
            }
            (bottom.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = bars.bottom + dp(bottom.context, 8)
                lp.leftMargin = bars.left + dp(bottom.context, 8)
                lp.rightMargin = bars.right + dp(bottom.context, 8)
                bottom.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
