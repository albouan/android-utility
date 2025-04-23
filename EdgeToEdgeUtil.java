package com.ngcomputing.fora.android.util;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.ViewGroupCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * EdgeToEdgeUtil is a utility class that enables edge-to-edge display for Android activities.
 * This class is designed to work with Android API levels 21 through 35 (inclusive).
 * It ensures content is displayed behind the system bars (status bar, navigation bar, etc.)
 * while dynamically adjusting padding and managing insets to avoid visual overlaps.
 *
 * Version: 1.0
 * Date: 2025-04-23
 *
 * Technical Info:
 * - Target Audience: Android developers implementing edge-to-edge UI.
 * - Supported API Levels: 21–35.
 * - Dependencies: AndroidX Core and Core-ktx 1.16.0-alpha01 or higher.
 *
 * Legal Info:
 * - Author: GitHub Copilot (AI-powered coding assistant).
 * - License: Ensure compliance with your project's license (e.g., Apache 2.0).
 * - Disclaimer: This code is provided "as is" without warranty of any kind.
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
     *
     * @param activity the target Activity on which to apply the edge-to-edge mode; must not be null.
     */
    @UiThread
    public static void enable(@NonNull final Activity activity) {
        Window window = activity.getWindow();

        // Allow content to extend into system windows (edge-to-edge).
        WindowCompat.setDecorFitsSystemWindows(window, false);

        // For devices with display cutouts (API 28+), allow content in the cutout area.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(layoutParams);
        }

        // Use WindowInsetsControllerCompat to control system bars behavior in a modern way.
        WindowInsetsControllerCompat insetsController =
                new WindowInsetsControllerCompat(window, window.getDecorView());
        insetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        // Explicitly show the status bar so that its safe area is visible.
        insetsController.show(WindowInsetsCompat.Type.statusBars());

        // Retrieve the activity's root content view.
        final View contentView = activity.findViewById(android.R.id.content);

        // Install compat insets dispatch to ensure insets are dispatched to siblings.
        if (contentView instanceof ViewGroup) {
            ViewGroupCompat.installCompatInsetsDispatch(contentView); // Removed unnecessary cast
        }

        // Attach an OnApplyWindowInsetsListener to update the content view's padding
        // and possibly adjust its background so that the safe areas remain uncovered.
        ViewCompat.setOnApplyWindowInsetsListener(contentView, new OnApplyWindowInsetsListener() {
            @NonNull
            @Override
            public WindowInsetsCompat onApplyWindowInsets(@NonNull View view,
                                                          @NonNull WindowInsetsCompat insets) {
                // Combine the insets from system bars and display cutouts.
                Insets safeInsets = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() |
                                WindowInsetsCompat.Type.displayCutout()
                );

                // If the content view has a non-transparent background, modify it so that it doesn't
                // extend into the safe areas, keeping areas like the status bar visible.
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

                // Update the padding of the content view to safeguard critical UI elements.
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

    /**
     * Determines whether the given drawable is fully transparent.
     * If the drawable is an instance of ColorDrawable, returns true if its alpha is 0.
     * For other drawable types, it conservatively returns false.
     *
     * @param drawable the drawable to check.
     * @return true if the drawable is considered transparent; false otherwise.
     */
    private static boolean isDrawableTransparent(@NonNull Drawable drawable) {
        if (drawable instanceof ColorDrawable) {
            int color = ((ColorDrawable) drawable).getColor();
            return Color.alpha(color) == 0;
        }
        // For non-ColorDrawable types, we assume it is non-transparent.
        return false;
    }
}