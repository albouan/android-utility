package com.ngcomputing.fora.android.util;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.ViewGroupCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * EdgeToEdgeUtil is a utility class that enables edge-to-edge display for Android activities.
 * This class is designed to work with Android API levels 21 and above.
 * It ensures content is displayed behind the system bars (status bar, navigation bar, etc.)
 * while dynamically adjusting padding and managing insets to avoid visual overlaps.
 *
 * Version: 1.8
 * Date: 2025-05-11
 *
 * --- Technical Info ---
 * - Target Audience: Android developers implementing edge-to-edge UI.
 * - Supported API Levels: 21 and above.
 * - Dependencies: AndroidX Core and Core-ktx 1.6.0 or higher.
 * - Testing Recommendation: Test on devices/emulators running API levels 21, 23, 28, and 30+.
 *
 * --- Legal Info ---
 * - Author: GitHub Copilot (AI-powered coding assistant).
 * - License: Ensure compliance with your project's license (e.g., Apache 2.0).
 * - Disclaimer: This code is provided "as is" without warranty of any kind. The author assumes no responsibility for damages or issues arising from the use of this code.
 */
public final class EdgeToEdgeUtil {

    // Unique key used to store the unmodified background in the view's tag.
    private static final int TAG_ORIGINAL_BACKGROUND = 0xDEADBEEF;

    // Private constructor to prevent instantiation.
    private EdgeToEdgeUtil() {
        throw new UnsupportedOperationException("Cannot instantiate utility class.");
    }

    /**
     * Enables edge-to-edge mode on the given activity while adjusting for system insets.
     * Defaults to excluding `WindowInsetsCompat.Type.ime()`.
     *
     * @param activity the target Activity on which to apply the edge-to-edge mode; must not be null.
     */
    @UiThread
    public static void enable(@NonNull final Activity activity) {
        enable(activity, false); // Default to not including IME insets.
    }

    /**
     * Enables edge-to-edge mode on the given activity while adjusting for system insets.
     * Allows control over whether to include `WindowInsetsCompat.Type.ime()`.
     *
     * @param activity         the target Activity on which to apply the edge-to-edge mode; must not be null.
     * @param includeImeInsets whether to include IME (keyboard) insets in the adjustments.
     */
    @UiThread
    public static void enable(@NonNull final Activity activity, boolean includeImeInsets) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }

        // Allow content to extend into system windows (edge-to-edge).
        WindowCompat.setDecorFitsSystemWindows(window, false);

        // Configure cutout mode for devices with display cutouts (API 28+).
        configureCutoutMode(window);

        // Configure insets behavior for API 30+.
        configureInsetsBehavior(window);

        // Configure light status bars for API 23+.
        configureLightStatusBars(window);

        // Retrieve the activity's root content view.
        final View contentView = activity.findViewById(android.R.id.content);
        if (contentView == null) {
            return;
        }

        // Install compat insets dispatch to ensure insets are dispatched to siblings.
        if (contentView instanceof ViewGroup) {
            ViewGroupCompat.installCompatInsetsDispatch(contentView);
        }

        // Apply insets listener to handle padding and background adjustments dynamically.
        applyInsetsListener(contentView, includeImeInsets);
    }

    // Configures the display cutout mode for devices with API 28+.
    private static void configureCutoutMode(@NonNull Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(layoutParams);
        }
    }

    // Configures the system bars behavior for devices with API 30+.
    private static void configureInsetsBehavior(@NonNull Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController insetsController = window.getInsetsController();
            if (insetsController != null) {
                insetsController.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    // Configures light status bars for devices with API 23+.
    private static void configureLightStatusBars(@NonNull Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // API 23+
            View decorView = window.getDecorView();
            int systemUiVisibility = decorView.getSystemUiVisibility();
            systemUiVisibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decorView.setSystemUiVisibility(systemUiVisibility);
        }
    }

    // Applies a listener to dynamically adjust insets and background.
    private static void applyInsetsListener(@NonNull View contentView, boolean includeImeInsets) {
        ViewCompat.setOnApplyWindowInsetsListener(contentView, new OnApplyWindowInsetsListener() {
            @NonNull
            @Override
            public WindowInsetsCompat onApplyWindowInsets(@NonNull View view,
                                                          @NonNull WindowInsetsCompat insets) {
                // Combine the insets from system bars, display cutouts, and optionally IME.
                int insetTypes = WindowInsetsCompat.Type.systemBars() |
                        WindowInsetsCompat.Type.displayCutout();
                if (includeImeInsets) {
                    insetTypes |= WindowInsetsCompat.Type.ime(); // Include IME insets if requested.
                }

                Insets safeInsets = insets.getInsets(insetTypes);

                // Adjust background for non-transparent content views.
                adjustBackground(view, safeInsets);

                // Update padding to safeguard critical UI elements.
                view.setPadding(
                        safeInsets.left,
                        safeInsets.top,
                        safeInsets.right,
                        safeInsets.bottom
                );

                // Safely consume the insets only on devices running API 30 or higher.
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
                    return WindowInsetsCompat.CONSUMED;
                } else {
                    return insets; // Avoid consuming insets on Android 10 or lower.
                }
            }
        });
    }

    // Adjusts the background of the view to avoid overlapping system bars.
    private static void adjustBackground(@NonNull View view, @NonNull Insets safeInsets) {
        Drawable originalBackground = view.getTag(TAG_ORIGINAL_BACKGROUND) == null
                ? view.getBackground()
                : (Drawable) view.getTag(TAG_ORIGINAL_BACKGROUND);
        if (view.getTag(TAG_ORIGINAL_BACKGROUND) == null) {
            view.setTag(TAG_ORIGINAL_BACKGROUND, originalBackground);
        }
        if (originalBackground != null && !isDrawableTransparent(originalBackground)) {
            // Use positive insets so that the background is drawn only within the safe area.
            InsetDrawable insetBackground = new InsetDrawable(
                    originalBackground,
                    safeInsets.left,
                    safeInsets.top,
                    safeInsets.right,
                    safeInsets.bottom
            );
            view.setBackground(insetBackground);
        }
    }

    /**
     * Determines whether the given drawable is fully transparent.
     * Supports various drawable types, including ColorDrawable, BitmapDrawable, and LayerDrawable.
     *
     * @param drawable the drawable to check.
     * @return true if the drawable is considered transparent; false otherwise.
     */
    private static boolean isDrawableTransparent(@NonNull Drawable drawable) {
        if (drawable instanceof ColorDrawable) {
            // Check alpha value for ColorDrawable.
            int color = ((ColorDrawable) drawable).getColor();
            return Color.alpha(color) == 0;
        } else if (drawable instanceof BitmapDrawable) {
            // Check alpha channel in BitmapDrawable.
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            Bitmap bitmap = bitmapDrawable.getBitmap();
            return bitmap != null && !bitmap.hasAlpha();
        } else if (drawable instanceof LayerDrawable) {
            // Check transparency of all layers in LayerDrawable.
            LayerDrawable layerDrawable = (LayerDrawable) drawable;
            for (int i = 0; i < layerDrawable.getNumberOfLayers(); i++) {
                Drawable layer = layerDrawable.getDrawable(i);
                if (layer != null && !isDrawableTransparent(layer)) {
                    return false;
                }
            }
            return true;
        } else if (drawable instanceof InsetDrawable) {
            // Recursively check transparency of the inner drawable.
            Drawable innerDrawable = ((InsetDrawable) drawable).getDrawable();
            return innerDrawable != null && isDrawableTransparent(innerDrawable);
        }
        // For unrecognized types, assume non-transparent.
        return false;
    }
}