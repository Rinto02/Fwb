/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;


import static com.android.systemui.Flags.truncatedStatusBarIconsFix;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.Dependency;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.statusbar.policy.Offset;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.user.ui.binder.StatusBarUserChipViewBinder;
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel;
import com.android.systemui.util.leak.RotationUtils;

import com.google.common.primitives.Floats;

import java.util.Objects;

public class PhoneStatusBarView extends FrameLayout implements TunerService.Tunable {
    private static final String TAG = "PhoneStatusBarView";
    private final StatusBarContentInsetsProvider mContentInsetsProvider;
    private final StatusBarWindowController mStatusBarWindowController;
    private TunerService mTunerService;

    private static final String LEFT_PADDING =
            "system:" + Settings.System.STATUSBAR_LEFT_PADDING;
    private static final String RIGHT_PADDING =
            "system:" + Settings.System.STATUSBAR_RIGHT_PADDING;
    private static final String TOP_PADDING =
            "system:" + Settings.System.STATUSBAR_TOP_PADDING;

    private int mLeftPad;
    private int mRightPad;
    private int mTopPad;

    private DarkReceiver mBattery;
    private ClockController mClockController;
    private int mRotationOrientation = -1;
    @Nullable
    private View mCutoutSpace;
    @Nullable
    private DisplayCutout mDisplayCutout;
    @Nullable
    private Rect mDisplaySize;
    private int mStatusBarHeight;
    @Nullable
    private Gefingerpoken mTouchEventHandler;
    private int mDensity;
    private float mFontScale;
    @Nullable
    private ViewGroup mStatusBarContents = null;

    /**
     * Draw this many pixels into the left/right side of the cutout to optimally use the space
     */
    private int mCutoutSideNudge = 0;

    private boolean mBrightnessControlEnabled;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContentInsetsProvider = Dependency.get(StatusBarContentInsetsProvider.class);
        mStatusBarWindowController = Dependency.get(StatusBarWindowController.class);
    }

    void setTouchEventHandler(Gefingerpoken handler) {
        mTouchEventHandler = handler;
    }

    void init(StatusBarUserChipViewModel viewModel) {
        StatusBarUserSwitcherContainer container = findViewById(R.id.user_switcher_container);
        StatusBarUserChipViewBinder.bind(container, viewModel);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mBattery = findViewById(R.id.battery);
        mClockController = new ClockController(getContext(), this);
        mCutoutSpace = findViewById(R.id.cutout_space_view);
        mStatusBarContents = (ViewGroup) findViewById(R.id.status_bar_contents);
        updateResources();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Always have Battery meters in the status bar observe the dark/light modes.
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mBattery);

        if (mTunerService == null) {
            mTunerService = Dependency.get(TunerService.class);
        }
        mTunerService.addTunable(this, LEFT_PADDING);
        mTunerService.addTunable(this, RIGHT_PADDING);
        mTunerService.addTunable(this, TOP_PADDING);

        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            if (truncatedStatusBarIconsFix()) {
                updateWindowHeight();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mBattery);
        if (mTunerService != null) {
            mTunerService.removeTunable(this);
        }
        mDisplayCutout = null;
    }

    // Per b/300629388, we let the PhoneStatusBarView detect onConfigurationChanged to
    // updateResources, instead of letting the PhoneStatusBarViewController detect onConfigChanged
    // then notify PhoneStatusBarView.
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // May trigger cutout space layout-ing
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
        if (truncatedStatusBarIconsFix()) {
            updateWindowHeight();
        }
    }

    void onDensityOrFontScaleChanged() {
        mClockController.onDensityOrFontScaleChanged();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    public void offsetStatusBar(Offset offset) {
        if (mStatusBarContents == null) {
            return;
        }
        mStatusBarContents.setTranslationX(offset.getX());
        mStatusBarContents.setTranslationY(offset.getY());
        invalidate();
    }

    /**
     * @return boolean indicating if we need to update the cutout location / margins
     */
    private boolean updateDisplayParameters() {
        boolean changed = false;
        int newRotation = RotationUtils.getExactRotation(mContext);
        if (newRotation != mRotationOrientation) {
            changed = true;
            mRotationOrientation = newRotation;
        }

        if (!Objects.equals(getRootWindowInsets().getDisplayCutout(), mDisplayCutout)) {
            changed = true;
            mDisplayCutout = getRootWindowInsets().getDisplayCutout();
        }

        Configuration newConfiguration = mContext.getResources().getConfiguration();
        final Rect newSize = newConfiguration.windowConfiguration.getMaxBounds();
        if (!Objects.equals(newSize, mDisplaySize)) {
            changed = true;
            mDisplaySize = newSize;
        }

        int density = newConfiguration.densityDpi;
        if (density != mDensity) {
            changed = true;
            mDensity = density;
        }
        float fontScale = newConfiguration.fontScale;
        if (fontScale != mFontScale) {
            changed = true;
            mFontScale = fontScale;
        }
        return changed;
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mTouchEventHandler == null) {
            Log.w(
                    TAG,
                    String.format(
                            "onTouch: No touch handler provided; eating gesture at (%d,%d)",
                            (int) event.getX(),
                            (int) event.getY()
                    )
            );
            return true;
        }
        return mTouchEventHandler.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        mTouchEventHandler.onInterceptTouchEvent(event);
        return super.onInterceptTouchEvent(event);
    }

    public boolean getBrightnessControlEnabled() {
        return mBrightnessControlEnabled;
    }

    public void setBrightnessControlEnabled(boolean enabled) {
        mBrightnessControlEnabled = enabled;
    }

    public void updateResources() {
        mCutoutSideNudge = getResources().getDimensionPixelSize(
                R.dimen.display_cutout_margin_consumption);

        updateStatusBarHeight();
    }

    private void updateStatusBarHeight() {
        final int waterfallTopInset =
                mDisplayCutout == null ? 0 : mDisplayCutout.getWaterfallInsets().top;
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        layoutParams.height = mStatusBarHeight - waterfallTopInset;
        updateSystemIconsContainerHeight();
        updatePaddings();
        setLayoutParams(layoutParams);
    }

    private void updateSystemIconsContainerHeight() {
        View systemIconsContainer = findViewById(R.id.system_icons);
        ViewGroup.LayoutParams layoutParams = systemIconsContainer.getLayoutParams();
        int newSystemIconsHeight =
                getResources().getDimensionPixelSize(R.dimen.status_bar_system_icons_height);
        if (layoutParams.height != newSystemIconsHeight) {
            layoutParams.height = newSystemIconsHeight;
            systemIconsContainer.setLayoutParams(layoutParams);
        }
    }

    private void updatePaddings() {
        if (mStatusBarContents == null) {
            mStatusBarContents = findViewById(R.id.status_bar_contents);
        }
        mStatusBarContents.setPaddingRelative(
                (int) mLeftPad,
                (int) mTopPad,
                (int) mRightPad,
                0);

        findViewById(R.id.notification_lights_out)
                .setPaddingRelative(0, (int) mLeftPad, 0, 0);

        findViewById(R.id.system_icons).setPaddingRelative(
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_start),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_top),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_end),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_bottom)
        );
    }

    private void updateLayoutForCutout() {
        updateStatusBarHeight();
        updateCutoutLocation();
        updateSafeInsets();
    }

    private void updateCutoutLocation() {
        // Not all layouts have a cutout (e.g., Car)
        if (mCutoutSpace == null) {
            return;
        }

        boolean hasCornerCutout = mContentInsetsProvider.currentRotationHasCornerCutout();
        if (mDisplayCutout == null || mDisplayCutout.isEmpty() || hasCornerCutout) {
            mCutoutSpace.setVisibility(View.GONE);
            return;
        }

        mCutoutSpace.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mCutoutSpace.getLayoutParams();

        Rect bounds = mDisplayCutout.getBoundingRectTop();

        bounds.left = bounds.left + mCutoutSideNudge;
        bounds.right = bounds.right - mCutoutSideNudge;
        lp.width = bounds.width();
        lp.height = bounds.height();
    }

    private void updateSafeInsets() {
        Insets insets = mContentInsetsProvider
                .getStatusBarContentInsetsForCurrentRotation();
        setPadding(
                insets.left,
                insets.top,
                insets.right,
                getPaddingBottom());

        // Apply negative paddings to centered area layout so that we'll actually be on the center.
        final int winRotation = getDisplay().getRotation();
        LayoutParams centeredAreaParams =
                (LayoutParams) findViewById(R.id.centered_area).getLayoutParams();
        centeredAreaParams.leftMargin =
                winRotation == Surface.ROTATION_0 ? -insets.left : 0;
        centeredAreaParams.rightMargin =
                winRotation == Surface.ROTATION_0 ? -insets.right : 0;
    }

    public ClockController getClockController() {
        return mClockController;
    }

    private void updateWindowHeight() {
        mStatusBarWindowController.refreshStatusBarHeight();
    }
    
    @Override
    public void onTuningChanged(String key, String newValue) {
        if (isNullOrEmpty(key)) return;
        Float value;
        switch (key) {
            case LEFT_PADDING:
                value = isNullOrEmpty(newValue) ? null : Floats.tryParse(newValue);
                if (value == null) {
                    mLeftPad = getDefaultLeftPadding();
                }
                else {
                    mLeftPad = Math.round(TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            value.floatValue(),
                            getResources().getDisplayMetrics()));
                }
                updateStatusBarHeight();
                break;
            case RIGHT_PADDING:
                value = isNullOrEmpty(newValue) ? null : Floats.tryParse(newValue);
                if (value == null) {
                    mRightPad = getDefaultRightPadding();
                }
                else {
                    mRightPad = Math.round(TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            value.floatValue(),
                            getResources().getDisplayMetrics()));
                }
                updateStatusBarHeight();
                break;
            case TOP_PADDING:
                value = isNullOrEmpty(newValue) ? null : Floats.tryParse(newValue);
                if (value == null) {
                    mTopPad = getDefaultTopPadding();
                }
                else {
                    mTopPad = Math.round(TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            value.floatValue(),
                            getResources().getDisplayMetrics()));
                }
                updateStatusBarHeight();
                break;
            default:
                break;
        }
    }

    private int getDefaultLeftPadding() {
      return getResources().getDimensionPixelSize(R.dimen.status_bar_padding_start);
    }

    private int getDefaultRightPadding() {
        return getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end);
    }

    private int getDefaultTopPadding() {
        return getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top);

    }

    private boolean isNullOrEmpty(String string) {
        return string == null || string.trim().isEmpty();
    }
}