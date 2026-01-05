package com.ngcomputing.fora.android.util;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * EdgeToEdgeUtil is a utility class that enables edge-to-edge display for Android activities.
 * This class is designed to work with Android API levels 24 and above.
 * It ensures content is displayed behind the system bars (status bar, navigation bar, etc.)
 * while dynamically adjusting padding and managing insets to avoid visual overlaps.
 *
 * Version: 3.0
 * Date: 2026-01-05
 *
 * --- Technical Info ---
 * - Target Audience: Android developers implementing edge-to-edge UI.
 * - Supported API Levels: 24 (Nougat) and above.
 * - Dependencies: AndroidX Core 1.6.0 or higher.
 * - Testing Recommendation: Test on devices/emulators running API levels 24, 28, and 30+.
 *
 * --- Legal Info ---
 * - Author: GitHub Copilot (AI-powered coding assistant).
 * - License: Ensure compliance with your project's license (e.g., Apache 2.0).
 * - Disclaimer: This code is provided "as is" without warranty of any kind. The author assumes no responsibility for damages or issues arising from the use of this code.
 */
@RequiresApi(Build.VERSION_CODES.N)
public final class EdgeToEdgeUtil {

    private static final int TAG_ORIGINAL_BACKGROUND = 0xDEADBEEF;

    private EdgeToEdgeUtil() {
        throw new UnsupportedOperationException("Cannot instantiate utility class.");
    }

    @UiThread
    public static void enable(@NonNull final Activity activity) {
        enable(activity, false);
    }

    @UiThread
    public static void enable(@NonNull final Activity activity, boolean includeImeInsets) {
        Window window = activity.getWindow();
        final View contentView = activity.findViewById(android.R.id.content);
        if (contentView == null) {
            return;
        }

        // Store original background and wrap it with insets. This is done only once.
        // This prevents re-wrapping the background on every inset change.
        if (contentView.getTag(TAG_ORIGINAL_BACKGROUND) == null) {
            Drawable originalBackground = contentView.getBackground();
            contentView.setTag(TAG_ORIGINAL_BACKGROUND, originalBackground);

            if (originalBackground != null && !isDrawableTransparent(originalBackground)) {
                LayerDrawable insetBackground = new LayerDrawable(new Drawable[]{originalBackground});
                contentView.setBackground(insetBackground);
            }
        }

        // Allow content to extend into system windows (edge-to-edge).
        WindowCompat.setDecorFitsSystemWindows(window, false);

        // Configure cutout mode for devices with display cutouts (API 28+).
        configureCutoutMode(window);

        WindowInsetsControllerCompat wic =
                new WindowInsetsControllerCompat(window, window.getDecorView());

        configureInsetsBehavior(window, wic);

        applyInsetsListener(contentView, includeImeInsets, wic);
    }

    private static void configureCutoutMode(@NonNull Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(layoutParams);
        }
    }

    private static void configureInsetsBehavior(@NonNull Window window,
                                                @NonNull WindowInsetsControllerCompat insetsControllerCompat) {
        // Prefer WindowInsetsControllerCompat to avoid using deprecated platform APIs.
        insetsControllerCompat.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private static void applyInsetsListener(@NonNull View contentView,
                                            boolean includeImeInsets,
                                            @NonNull WindowInsetsControllerCompat wic) {
        ViewCompat.setOnApplyWindowInsetsListener(contentView, (view, insets) -> {
            int insetTypes = WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout();
            if (includeImeInsets) {
                insetTypes |= WindowInsetsCompat.Type.ime();
            }

            Insets safeInsets = insets.getInsets(insetTypes);

            // Adjust the insets of the LayerDrawable background
            Drawable currentBackground = view.getBackground();
            if (currentBackground instanceof LayerDrawable) {
                ((LayerDrawable) currentBackground).setLayerInset(0, safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom);
            }

            // Set padding on the view to prevent content from overlapping with system bars
            view.setPadding(
                    safeInsets.left,
                    safeInsets.top,
                    safeInsets.right,
                    safeInsets.bottom
            );

            // Adjust status/navigation icon appearance based on original background luminance
            Drawable originalBackground = (Drawable) view.getTag(TAG_ORIGINAL_BACKGROUND);
            boolean isLight = isBackgroundLight(originalBackground);
            wic.setAppearanceLightStatusBars(isLight);
            wic.setAppearanceLightNavigationBars(isLight);

            return WindowInsetsCompat.CONSUMED;
        });
    }

    private static boolean isBackgroundLight(Drawable background) {
        if (background instanceof ColorDrawable) {
            int color = ((ColorDrawable) background).getColor();
            return ColorUtils.calculateLuminance(color) > 0.5;
        }
        // Default to dark icons (true) for non-color backgrounds or null
        return true;
    }

    @SuppressWarnings("deprecation")
    private static boolean isDrawableTransparent(@NonNull Drawable drawable) {
        if (drawable instanceof ColorDrawable) {
            return Color.alpha(((ColorDrawable) drawable).getColor()) == 0;
        } else if (drawable instanceof BitmapDrawable) {
            return false;
        } else if (drawable instanceof LayerDrawable) {
            LayerDrawable layerDrawable = (LayerDrawable) drawable;
            for (int i = 0; i < layerDrawable.getNumberOfLayers(); i++) {
                Drawable layer = layerDrawable.getDrawable(i);
                if (layer != null && !isDrawableTransparent(layer)) {
                    return false;
                }
            }
            return true;
        } else if (drawable instanceof InsetDrawable) {
            Drawable innerDrawable;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                innerDrawable = ((InsetDrawable) drawable).getDrawable();
            } else {
                //noinspection deprecation
                innerDrawable = ((InsetDrawable) drawable).getDrawable();
            }
            return innerDrawable != null && isDrawableTransparent(innerDrawable);
        }
        return false;
    }
}