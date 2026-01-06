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
import android.view.ViewTreeObserver;

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
 * Version: 3.1
 * Date: 2026-01-06
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
@SuppressWarnings("deprecation")
public final class EdgeToEdgeUtil {

    private static final int TAG_ORIGINAL_BACKGROUND = 0xDEADBEEF;
    private static final int TAG_INITIAL_PADDING = 0xDEADBEF0;

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
            contentView.setTag(
                    TAG_INITIAL_PADDING,
                    new int[]{contentView.getPaddingLeft(), contentView.getPaddingTop(),
                            contentView.getPaddingRight(), contentView.getPaddingBottom()}
            );
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

        WindowInsetsControllerCompat wic =
                new WindowInsetsControllerCompat(window, window.getDecorView());

        configureInsetsBehavior(window, wic);

        applyInsetsListener(contentView, includeImeInsets, wic, window);
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
                                            @NonNull WindowInsetsControllerCompat wic,
                                            @NonNull Window window) {
        // Add a one-time listener to update the theme when the view is first drawn.
        // This ensures we check the background color after everything is rendered.
        contentView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                // The listener is one-shot, remove it after execution.
                contentView.getViewTreeObserver().removeOnPreDrawListener(this);
                updateSystemBarAppearance(window, wic);
                return true;
            }
        });

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

            // Add insets on top of the original padding (don't overwrite it).
            int[] initialPadding = (int[]) view.getTag(TAG_INITIAL_PADDING);
            if (initialPadding == null || initialPadding.length != 4) {
                initialPadding = new int[]{0, 0, 0, 0};
            }

            view.setPadding(
                    initialPadding[0] + safeInsets.left,
                    initialPadding[1] + safeInsets.top,
                    initialPadding[2] + safeInsets.right,
                    initialPadding[3] + safeInsets.bottom
            );

            // Post the appearance update to allow the background to be drawn first.
            // This helps prevent flickering on initial launch.
            view.post(() -> updateSystemBarAppearance(window, wic));

            // Don't indiscriminately consume all insets here; allow propagation.
            return insets;
        });
    }

    private static void updateSystemBarAppearance(@NonNull Window window, @NonNull WindowInsetsControllerCompat wic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isAreaBehindSystemBarsLightPixelCopy(window, isLight -> {
                wic.setAppearanceLightStatusBars(isLight);
                wic.setAppearanceLightNavigationBars(isLight);
            });
        } else {
            boolean isLight = isAreaBehindSystemBarsLightDrawingCache(window.getDecorView());
            wic.setAppearanceLightStatusBars(isLight);
            wic.setAppearanceLightNavigationBars(isLight);
        }
    }

    /**
     * Callback interface for asynchronous light/dark check.
     */
    private interface OnIsLightCallback {
        void onResult(boolean isLight);
    }

    /**
     * Asynchronously determines if the area behind the status bar is light using PixelCopy.
     * This is the preferred method for API 26+.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private static void isAreaBehindSystemBarsLightPixelCopy(@NonNull Window window, @NonNull OnIsLightCallback callback) {
        View decorView = window.getDecorView();
        if (decorView.getWidth() == 0 || decorView.getHeight() == 0) {
            callback.onResult(false);
            return;
        }

        int checkWidth = decorView.getWidth();
        int checkHeight = 1; // A single pixel high strip is enough.

        Bitmap bitmap = Bitmap.createBitmap(checkWidth, checkHeight, Bitmap.Config.ARGB_8888);
        int[] location = new int[2];
        decorView.getLocationInWindow(location);

        PixelCopy.request(window,
                new android.graphics.Rect(location[0], location[1], location[0] + checkWidth, location[1] + checkHeight),
                bitmap,
                copyResult -> {
                    if (copyResult == PixelCopy.SUCCESS) {
                        double totalLuminance = 0;
                        int[] pixels = new int[checkWidth];
                        bitmap.getPixels(pixels, 0, checkWidth, 0, 0, checkWidth, 1);
                        for (int color : pixels) {
                            totalLuminance += ColorUtils.calculateLuminance(color);
                        }
                        callback.onResult((totalLuminance / checkWidth) > 0.5);
                    } else {
                        callback.onResult(false); // Default to dark on failure
                    }
                    bitmap.recycle();
                },
                new Handler(Looper.getMainLooper()));
    }

    /**
     * Determines if the area behind the status bar is light by analyzing the pixels of the DecorView
     * using the deprecated drawing cache. Fallback for API < 26.
     *
     * @param decorView The window's decor view.
     * @return {@code true} if the status bar area is light, {@code false} otherwise.
     */
    private static boolean isAreaBehindSystemBarsLightDrawingCache(@NonNull View decorView) {
        if (decorView.getWidth() == 0 || decorView.getHeight() == 0) {
            return false; // View not laid out yet.
        }

        // For performance, we'll check a small portion of the top of the view.
        int checkWidth = decorView.getWidth();
        int checkHeight = 1; // A single pixel high strip is enough.

        Bitmap bitmap = null;
        try {
            // Use the drawing cache for speed, but ensure it's enabled and fresh.
            decorView.setDrawingCacheEnabled(true);
            decorView.buildDrawingCache(true);
            Bitmap cache = decorView.getDrawingCache();

            if (cache == null) return false;

            // Create a small bitmap from the cache with the area we want to check.
            bitmap = Bitmap.createBitmap(cache, 0, 0, checkWidth, checkHeight);

            // Important: destroy the cache to free up memory and allow it to be rebuilt next time.
            decorView.destroyDrawingCache();
            decorView.setDrawingCacheEnabled(false);

            int[] pixels = new int[checkWidth];
            bitmap.getPixels(pixels, 0, checkWidth, 0, 0, checkWidth, 1);

            double totalLuminance = 0;
            for (int color : pixels) {
                totalLuminance += ColorUtils.calculateLuminance(color);
            }

            return (totalLuminance / checkWidth) > 0.5;

        } catch (Exception e) {
            return false; // Default to dark icons on failure.
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private static boolean isBackgroundLight(Drawable background) {
        if (background == null) {
            return false; // Assume dark if no background.
        }
        if (background instanceof ColorDrawable) {
            int color = ((ColorDrawable) background).getColor();
            return ColorUtils.calculateLuminance(color) > 0.5;
        } else if (background instanceof LayerDrawable) {
            LayerDrawable layerDrawable = (LayerDrawable) background;
            for (int i = 0; i < layerDrawable.getNumberOfLayers(); i++) {
                Drawable layer = layerDrawable.getDrawable(i);
                if (layer instanceof ColorDrawable && Color.alpha(((ColorDrawable) layer).getColor()) != 0) {
                    return isBackgroundLight(layer);
                } else if (layer instanceof BitmapDrawable) {
                    return isBackgroundLight(layer);
                }
            }
        } else if (background instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) background).getBitmap();
            if (bitmap == null || bitmap.isRecycled()) {
                return false;
            }
            // For performance, we'll sample the top-center portion of the bitmap.
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int sampleHeight = Math.min(height, 150); // Sample up to 150px from the top.

            try {
                Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, sampleHeight);
                double totalLuminance = 0;
                int pixelCount = width * sampleHeight;

                int[] pixels = new int[pixelCount];
                croppedBitmap.getPixels(pixels, 0, width, 0, 0, width, sampleHeight);
                croppedBitmap.recycle();

                for (int color : pixels) {
                    totalLuminance += ColorUtils.calculateLuminance(color);
                }
                return (totalLuminance / pixelCount) > 0.5;
            } catch (Exception e) {
                // Could be out of memory or bad arguments. Default to not-light.
                return false;
            }
        }
        // Default to "not light" if unknown; avoids forcing dark icons on potentially dark backgrounds.
        return false;
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
            //noinspection deprecation
            Drawable innerDrawable = ((InsetDrawable) drawable).getDrawable();
            return innerDrawable != null && isDrawableTransparent(innerDrawable);
        }
        return false;
    }
}