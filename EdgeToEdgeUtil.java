package com.ngcomputing.fora.android.util;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
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
 * <p>
 * Version: 4.0
 * Date: 2026-01-11
 * <p>
 * --- Technical Info ---
 * - Target Audience: Android developers implementing edge-to-edge UI.
 * - Supported API Levels: 24 (Nougat) and above.
 * - Dependencies: AndroidX Core 1.6.0 or higher.
 * - Testing Recommendation: Test on devices/emulators running API levels 24, 28, and 30+.
 * <p>
 * --- Legal Info ---
 * - Author: GitHub Copilot (AI-powered coding assistant).
 * - License: Ensure compliance with your project's license (e.g., Apache 2.0).
 * - Disclaimer: This code is provided "as is" without warranty of any kind. The author assumes no responsibility for damages or issues arising from the use of this code.
 */
@SuppressWarnings({"deprecation", "RedundantSuppression"})
public final class EdgeToEdgeUtil {

    // Using View.generateViewId() for setTag(int, Object) is not safe.
    // The generated IDs are not guaranteed to be valid resource IDs.
    // We use hardcoded values that are high enough to not conflict
    // with AAPT-generated IDs (which start at 0x7f...).
    private static final int TAG_ORIGINAL_BACKGROUND = 0x7E000000;
    private static final int TAG_INITIAL_PADDING = 0x7E000001;
    private static final int TAG_LAST_SYSTEM_BAR_INSETS = 0x7E000002;
    private static final int TAG_UPDATE_APPEARANCE_RUNNABLE = 0x7E000003;

    // limit pixel-sampling width to reduce memory/work on very wide displays
    private static final int MAX_SAMPLE_WIDTH = 200;

    // Luminance threshold for determining light/dark areas
    private static final double LUMINANCE_THRESHOLD = 0.5;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

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

        // Store initial padding once so we don't clobber caller/layout padding.
        if (contentView.getTag(TAG_INITIAL_PADDING) == null) {
            contentView.setTag(TAG_INITIAL_PADDING, new int[]{contentView.getPaddingLeft(), contentView.getPaddingTop(), contentView.getPaddingRight(), contentView.getPaddingBottom()});
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

        // Make system bars transparent so content can be seen behind them.
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        // On API 29+, disable auto dark navigation icons for transparent nav bars.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }

        // Configure cutout mode for devices with display cutouts (API 28+).
        configureCutoutMode(window);

        // Use the non-deprecated factory method to get the controller
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(window, window.getDecorView());

        configureInsetsBehavior(wic);

        applyInsetsListener(contentView, includeImeInsets, wic, window);
    }

    private static void configureCutoutMode(@NonNull Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(layoutParams);
        }
    }

    private static void configureInsetsBehavior(@NonNull WindowInsetsControllerCompat insetsControllerCompat) {
        // Prefer WindowInsetsControllerCompat to avoid using deprecated platform APIs.
        insetsControllerCompat.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private static void applyInsetsListener(@NonNull View contentView, boolean includeImeInsets, @NonNull WindowInsetsControllerCompat wic, @NonNull Window window) {
        // The setOnApplyWindowInsetsListener is called when the view is first attached and
        // whenever the window insets change, so a separate pre-draw listener is not required.

        ViewCompat.setOnApplyWindowInsetsListener(contentView, (view, insets) -> {
            int insetTypes = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
            if (includeImeInsets) {
                insetTypes |= WindowInsetsCompat.Type.ime();
            }

            Insets safeInsets = insets.getInsets(insetTypes);

            // Adjust the insets of the LayerDrawable background
            Drawable currentBackground = view.getBackground();
            if (currentBackground instanceof LayerDrawable) {
                LayerDrawable ld = (LayerDrawable) currentBackground;
                // Use non-deprecated setters for layer insets
                ld.setLayerInsetLeft(0, safeInsets.left);
                ld.setLayerInsetTop(0, safeInsets.top);
                ld.setLayerInsetRight(0, safeInsets.right);
                ld.setLayerInsetBottom(0, safeInsets.bottom);
            }

            // Add insets on top of the original padding (don't overwrite it).
            int[] initialPadding = (int[]) view.getTag(TAG_INITIAL_PADDING);
            if (initialPadding == null || initialPadding.length != 4) {
                initialPadding = new int[]{0, 0, 0, 0};
            }

            view.setPadding(initialPadding[0] + safeInsets.left, initialPadding[1] + safeInsets.top, initialPadding[2] + safeInsets.right, initialPadding[3] + safeInsets.bottom);

            // Diff and throttle appearance updates.
            // This prevents expensive updates during IME animations.
            final Insets systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets lastSystemBarInsets = (Insets) view.getTag(TAG_LAST_SYSTEM_BAR_INSETS);

            if (lastSystemBarInsets == null || !lastSystemBarInsets.equals(systemBarInsets)) {
                view.setTag(TAG_LAST_SYSTEM_BAR_INSETS, systemBarInsets);

                Runnable existingRunnable = (Runnable) view.getTag(TAG_UPDATE_APPEARANCE_RUNNABLE);
                if (existingRunnable != null) {
                    MAIN_HANDLER.removeCallbacks(existingRunnable);
                }

                Runnable updateRunnable = () -> updateSystemBarAppearance(window, wic, insets);
                view.setTag(TAG_UPDATE_APPEARANCE_RUNNABLE, updateRunnable);
                // Post with a small delay to allow animations to settle.
                MAIN_HANDLER.postDelayed(updateRunnable, 100L);
            }

            // Don't indiscriminately consume all insets here; allow propagation.
            return insets;
        });
    }

    private static void updateSystemBarAppearance(@NonNull Window window, @NonNull WindowInsetsControllerCompat wic, @NonNull WindowInsetsCompat insets) {
        View decorView = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            isAreaBehindSystemBarsLightPixelCopy(window, true, statusBarHeight, isLight -> {
                wic.setAppearanceLightStatusBars(isLight);
            });

            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            if (navBarHeight > 0) {
                isAreaBehindSystemBarsLightPixelCopy(window, false, navBarHeight, isLight -> {
                    wic.setAppearanceLightNavigationBars(isLight);
                });
            }
        } else {
            // Fallback for older APIs is less reliable but we can still check top and bottom.
            boolean isStatusBarLight = isAreaBehindSystemBarsLightDrawingCache(decorView, true);
            wic.setAppearanceLightStatusBars(isStatusBarLight);

            boolean isNavBarLight = isAreaBehindSystemBarsLightDrawingCache(decorView, false);
            wic.setAppearanceLightNavigationBars(isNavBarLight);
        }
    }

    /**
     * Callback interface for asynchronous light/dark check.
     */
    private interface OnIsLightCallback {
        void onResult(boolean isLight);
    }

    /**
     * Asynchronously determines if the area behind the system bars is light using PixelCopy.
     * This is the preferred method for API 26+.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private static void isAreaBehindSystemBarsLightPixelCopy(@NonNull Window window, boolean isTop, int barHeight, @NonNull OnIsLightCallback callback) {
        View decorView = window.getDecorView();
        if (decorView.getWidth() == 0 || decorView.getHeight() == 0 || barHeight <= 0) {
            callback.onResult(false);
            return;
        }

        int fullWidth = decorView.getWidth();
        int sampleWidth = Math.min(fullWidth, MAX_SAMPLE_WIDTH);
        int startX = Math.max(0, (fullWidth - sampleWidth) / 2);
        int checkHeight = 4; // Sample a thicker line for more reliable results.

        Bitmap bitmap = Bitmap.createBitmap(sampleWidth, checkHeight, Bitmap.Config.ARGB_8888);
        int[] location = new int[2];
        decorView.getLocationInWindow(location);

        int yPos = isTop ? location[1] : location[1] + decorView.getHeight() - barHeight;

        android.graphics.Rect srcRect = new android.graphics.Rect(location[0] + startX, yPos, location[0] + startX + sampleWidth, yPos + checkHeight);

        PixelCopy.request(window, srcRect, bitmap, copyResult -> {
            if (copyResult == PixelCopy.SUCCESS) {
                double totalLuminance = 0;
                int pixelCount = sampleWidth * checkHeight;
                int[] pixels = new int[pixelCount];
                bitmap.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, checkHeight);
                for (int color : pixels) {
                    totalLuminance += ColorUtils.calculateLuminance(color);
                }
                callback.onResult((totalLuminance / pixelCount) > LUMINANCE_THRESHOLD);
            } else {
                // Consider: Log the failure reason for debugging
                android.util.Log.w("EdgeToEdgeUtil", "PixelCopy failed: " + copyResult);
                callback.onResult(false);
            }
            bitmap.recycle();
        }, MAIN_HANDLER);
    }

    /**
     * Determines if the area behind the system bars is light by analyzing the pixels of the DecorView
     * using the deprecated drawing cache. Fallback for API < 26.
     *
     * @param decorView The window's decor view.
     * @param isTop     True to check the top (status bar), false for the bottom (navigation bar).
     * @return {@code true} if the system bar area is light, {@code false} otherwise.
     */
    private static boolean isAreaBehindSystemBarsLightDrawingCache(@NonNull View decorView, boolean isTop) {
        if (decorView.getWidth() == 0 || decorView.getHeight() == 0) {
            return false;
        }

        int fullWidth = decorView.getWidth();
        int sampleWidth = Math.min(fullWidth, MAX_SAMPLE_WIDTH);
        int startX = Math.max(0, (fullWidth - sampleWidth) / 2);
        int checkHeight = 4; // Sample a thicker line for more reliable results.
        int yPos = isTop ? 0 : decorView.getHeight() - checkHeight;

        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(sampleWidth, checkHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.translate(-startX, -yPos);
            decorView.draw(canvas);

            int pixelCount = sampleWidth * checkHeight;
            int[] pixels = new int[pixelCount];
            bitmap.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, checkHeight);

            double totalLuminance = 0;
            for (int color : pixels) {
                totalLuminance += ColorUtils.calculateLuminance(color);
            }
            return (totalLuminance / pixelCount) > LUMINANCE_THRESHOLD;
        } catch (Exception e) {
            // Log the exception to aid in debugging on older devices.
            android.util.Log.w("EdgeToEdgeUtil", "Failed to check system bar contrast on API " + Build.VERSION.SDK_INT, e);
            return false;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

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
            Drawable innerDrawable = ((InsetDrawable) drawable).getDrawable();
            return innerDrawable != null && isDrawableTransparent(innerDrawable);
        }
        return false;
    }
}