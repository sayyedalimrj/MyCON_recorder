package ir.mycon.capture

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.util.Locale

object CaptureSheets {

    data class ToolAction(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val enabled: Boolean = true,
        val onClick: () -> Unit
    )

    fun showTools(
        context: Context,
        actions: List<ToolAction>
    ) {
        val dialog =
            BottomSheetDialog(context)

        val root =
            LinearLayout(context).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(context, 18),
                    Ui.dp(context, 10),
                    Ui.dp(context, 18),
                    Ui.dp(context, 22)
                )
                background =
                    Ui.rounded(
                        Ui.SURFACE_SOLID,
                        28,
                        context
                    )
            }

        root.addView(
            handle(context)
        )
        root.addView(
            Ui.title(
                context,
                "ابزارهای برداشت",
                20f
            )
        )
        root.addView(
            Ui.label(
                context,
                "عملیات ثانویه اینجا جمع شده تا Viewfinder خلوت بماند.",
                12f
            )
        )
        Ui.addSpacer(
            root,
            14
        )

        actions
            .chunked(2)
            .forEach {
                    pair ->
                val row =
                    LinearLayout(context).apply {
                        orientation =
                            LinearLayout.HORIZONTAL
                    }

                pair.forEachIndexed {
                        index,
                        action ->
                    val card =
                        Ui.card(context)
                    val box =
                        LinearLayout(context).apply {
                            orientation =
                                LinearLayout.VERTICAL
                            gravity =
                                Gravity.START
                            setPadding(
                                Ui.dp(
                                    context,
                                    14
                                ),
                                Ui.dp(
                                    context,
                                    13
                                ),
                                Ui.dp(
                                    context,
                                    14
                                ),
                                Ui.dp(
                                    context,
                                    13
                                )
                            )
                            isEnabled =
                                action.enabled
                            alpha =
                                if (
                                    action.enabled
                                ) {
                                    1f
                                } else {
                                    0.45f
                                }
                            setOnClickListener {
                                if (
                                    action.enabled
                                ) {
                                    dialog.dismiss()
                                    action.onClick()
                                }
                            }
                        }

                    val icon =
                        Ui.iconButton(
                            context,
                            action.iconRes,
                            action.title,
                            Ui.SURFACE_2
                        ).apply {
                            isClickable = false
                            isFocusable = false
                        }

                    box.addView(
                        icon,
                        LinearLayout.LayoutParams(
                            Ui.dp(
                                context,
                                44
                            ),
                            Ui.dp(
                                context,
                                44
                            )
                        )
                    )
                    Ui.addSpacer(
                        box,
                        8
                    )
                    box.addView(
                        Ui.title(
                            context,
                            action.title,
                            14f
                        )
                    )
                    box.addView(
                        Ui.label(
                            context,
                            action.subtitle,
                            10.5f
                        )
                    )
                    card.addView(box)

                    row.addView(
                        card,
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            if (
                                index == 0
                            ) {
                                marginEnd =
                                    Ui.dp(
                                        context,
                                        8
                                    )
                            }
                            bottomMargin =
                                Ui.dp(
                                    context,
                                    8
                                )
                        }
                    )
                }

                if (
                    pair.size == 1
                ) {
                    row.addView(
                        View(context),
                        LinearLayout.LayoutParams(
                            0,
                            1,
                            1f
                        )
                    )
                }

                root.addView(row)
            }

        dialog.setContentView(root)
        dialog.show()
    }

    fun showCameraInfo(
        context: Context,
        selection: CameraSelection,
        exposureMs: Double?,
        blurPx: Double?,
        focusMode: String,
        torchEnabled: Boolean,
        onSettings: () -> Unit
    ) {
        val dialog =
            BottomSheetDialog(context)

        val root =
            LinearLayout(context).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(context, 18),
                    Ui.dp(context, 10),
                    Ui.dp(context, 18),
                    Ui.dp(context, 24)
                )
                background =
                    Ui.rounded(
                        Ui.SURFACE_SOLID,
                        28,
                        context
                    )
            }

        root.addView(
            handle(context)
        )
        root.addView(
            Ui.title(
                context,
                "Scientific camera",
                20f
            )
        )

        root.addView(
            Ui.label(
                context,
                selection.note,
                11.5f
            )
        )
        Ui.addSpacer(
            root,
            14
        )

        val grid =
            listOf(
                "Recorded" to
                    "${selection.imageWidth}×${selection.imageHeight}",
                "FPS" to
                    "${selection.fpsMin}–${selection.fpsMax}",
                "Camera ID" to
                    selection.cameraId,
                "Profile" to
                    selection.appliedProfile,
                "FOV" to
                    (
                        selection.horizontalFovDeg
                            ?.let {
                                String.format(
                                    Locale.US,
                                    "%.1f°",
                                    it
                                )
                            } ?: "—"
                        ),
                "Focal" to
                    (
                        selection.focalLengthMm
                            ?.let {
                                String.format(
                                    Locale.US,
                                    "%.2f mm",
                                    it
                                )
                            } ?: "—"
                        ),
                "Exposure" to
                    (
                        exposureMs
                            ?.let {
                                String.format(
                                    Locale.US,
                                    "%.1f ms",
                                    it
                                )
                            } ?: "—"
                        ),
                "Blur est." to
                    (
                        blurPx
                            ?.let {
                                String.format(
                                    Locale.US,
                                    "%.1f px",
                                    it
                                )
                            } ?: "—"
                        ),
                "Focus" to
                    focusMode.uppercase(
                        Locale.US
                    ),
                "Torch" to
                    if (
                        torchEnabled
                    ) {
                        "ON"
                    } else {
                        "OFF"
                    }
            )

        grid.chunked(2)
            .forEach {
                    items ->
                val row =
                    LinearLayout(context).apply {
                        orientation =
                            LinearLayout.HORIZONTAL
                    }

                items.forEachIndexed {
                        index,
                        item ->
                    val card =
                        Ui.card(context)
                    val box =
                        LinearLayout(context).apply {
                            orientation =
                                LinearLayout.VERTICAL
                            setPadding(
                                Ui.dp(
                                    context,
                                    12
                                ),
                                Ui.dp(
                                    context,
                                    10
                                ),
                                Ui.dp(
                                    context,
                                    12
                                ),
                                Ui.dp(
                                    context,
                                    10
                                )
                            )
                        }
                    box.addView(
                        Ui.label(
                            context,
                            item.first,
                            10.5f
                        )
                    )
                    box.addView(
                        Ui.title(
                            context,
                            item.second,
                            14f
                        )
                    )
                    card.addView(box)

                    row.addView(
                        card,
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            if (
                                index == 0
                            ) {
                                marginEnd =
                                    Ui.dp(
                                        context,
                                        8
                                    )
                            }
                            bottomMargin =
                                Ui.dp(
                                    context,
                                    8
                                )
                        }
                    )
                }
                root.addView(row)
            }

        Ui.addSpacer(
            root,
            6
        )

        root.addView(
            Ui.button(
                context,
                "تنظیمات تصویر",
                Ui.BLUE
            ).apply {
                setOnClickListener {
                    dialog.dismiss()
                    onSettings()
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Ui.dp(
                    context,
                    48
                )
            )
        )

        dialog.setContentView(root)
        dialog.show()
    }

    private fun handle(
        context: Context
    ): View =
        View(context).apply {
            background =
                Ui.rounded(
                    Ui.MUTED,
                    3,
                    context
                )
            val lp =
                LinearLayout.LayoutParams(
                    Ui.dp(
                        context,
                        42
                    ),
                    Ui.dp(
                        context,
                        4
                    )
                )
            lp.gravity =
                Gravity.CENTER_HORIZONTAL
            lp.bottomMargin =
                Ui.dp(
                    context,
                    14
                )
            layoutParams = lp
        }
}
