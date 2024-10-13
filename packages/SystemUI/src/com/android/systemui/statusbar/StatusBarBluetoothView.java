/*
 * Copyright (C) 2018 The Android Open Source Project
 *           (C) 2022 Paranoid Android
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

package com.android.systemui.statusbar;

import static com.android.systemui.plugins.DarkIconDispatcher.getTint;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.Context;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.provider.Settings;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy.BluetoothIconState;

import com.android.settingslib.Utils;

import java.util.ArrayList;

public class StatusBarBluetoothView extends FrameLayout implements StatusIconDisplayable {
    private static final String TAG = "StatusBarBluetoothView";
    			
    // Neon colors for dark bg
    private int mHighBatteryColorDarkNeon = 0xFF00FF00;  // Bright Green
    private int mLowBatteryColorDarkNeon = 0xFFFFA500;    // Bright Orange
    private int mCriticalBatteryColorDarkNeon = 0xFFFF0000; // Bright Red

    // Muted colors for light bg
    private int mHighBatteryColorLightMuted = 0xFF4CAF50;  // Muted Green
    private int mLowBatteryColorLightMuted = 0xFFFF9800;    // Muted Orange
    private int mCriticalBatteryColorLightMuted = 0xFFF44336; // Muted Red
    
    private boolean isDarkMode = false;

    /// Used to show etc dots
    private StatusBarIconView mDotView;

    /// Contains the main icon layout
    private boolean mBlocked;
    private LinearLayout mBluetoothGroup;
    private ImageView mBluetoothIcon;
    private ImageView mBatteryIcon;
    private BluetoothIconState mState;
    private String mSlot;
    private int mVisibleState = -1;
    private int mBatteryLevel = -1;
    private ColorStateList mBatteryColor;
    
    private ContentObserver mBatteryColorObserver;
    private Handler mHandler;

    public static StatusBarBluetoothView fromContext(
            Context context, String slot, boolean blocked) {
        StatusBarBluetoothView v = (StatusBarBluetoothView)
                LayoutInflater.from(context).inflate(R.layout.status_bar_bluetooth_group, null);
        v.setSlot(slot);
        v.init(blocked);
        v.setVisibleState(blocked ? STATE_HIDDEN : STATE_ICON);
        return v;
    }

    public StatusBarBluetoothView(Context context) {
        super(context);
    }

    public StatusBarBluetoothView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StatusBarBluetoothView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public StatusBarBluetoothView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public void setSlot(String slot) {
        mSlot = slot;
    }
    
    private void initHandler() {
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void setStaticDrawableColor(int color) {
        ColorStateList list = ColorStateList.valueOf(color);
        mBatteryColor = list;
        updateBatteryColor();
        mBluetoothIcon.setImageTintList(list);
        mDotView.setDecorColor(color);
    }

    @Override
    public void setDecorColor(int color) {
        mDotView.setDecorColor(color);
    }

    @Override
    public String getSlot() {
        return mSlot;
    }

    @Override
    public boolean isIconVisible() {
        return mState != null && mState.visible;
    }

    @Override
    public boolean isIconBlocked() {
        return mBlocked;
    }

    @Override
    public void setVisibleState(int state, boolean animate) {
        if (state == mVisibleState) {
            return;
        }
        mVisibleState = state;

        switch (state) {
            case STATE_ICON:
                mBluetoothGroup.setVisibility(View.VISIBLE);
                mDotView.setVisibility(View.GONE);
                break;
            case STATE_DOT:
                mBluetoothGroup.setVisibility(View.GONE);
                mDotView.setVisibility(View.VISIBLE);
                break;
            case STATE_HIDDEN:
            default:
                mBluetoothGroup.setVisibility(View.GONE);
                mDotView.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        float translationX = getTranslationX();
        float translationY = getTranslationY();
        outRect.left += translationX;
        outRect.right += translationX;
        outRect.top += translationY;
        outRect.bottom += translationY;
    }

    private void init(boolean blocked) {
        mBlocked = blocked;
        mBluetoothGroup = findViewById(R.id.bluetooth_group);
        mBluetoothIcon = findViewById(R.id.bluetooth_icon);
        mBatteryIcon = findViewById(R.id.bluetooth_battery);

        initDotView();
        registerContentObserver();
    }

    private void initDotView() {
        mDotView = new StatusBarIconView(mContext, mSlot, null);
        mDotView.setVisibleState(STATE_DOT);

        int width = mContext.getResources().getDimensionPixelSize(R.dimen.status_bar_icon_size);
        LayoutParams lp = new LayoutParams(width, width);
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        addView(mDotView, lp);
    }

    public void applyBluetoothState(BluetoothIconState state) {
        boolean requestLayout = false;

        if (state == null) {
            requestLayout = getVisibility() != View.GONE;
            setVisibility(View.GONE);
            mState = null;
        } else if (mState == null) {
            requestLayout = true;
            mState = state;
            initViewState();
        } else if (!mState.equals(state)) {
            requestLayout = updateState(state);
        }

        if (requestLayout) {
            requestLayout();
        }
    }
    
    private void registerContentObserver() {
        mBatteryColorObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                updateBatteryColor();
            }
        };

        getContext().getContentResolver().registerContentObserver(
                Settings.System.getUriFor("BT_BATTERY_COLOR"),
                false,
                mBatteryColorObserver,
                UserHandle.USER_CURRENT
        );
    }
    
    private void unregisterContentObserver() {
        if (mBatteryColorObserver != null) {
            getContext().getContentResolver().unregisterContentObserver(mBatteryColorObserver);
            mBatteryColorObserver = null;
        }
    }

    private boolean updateState(BluetoothIconState state) {
        setContentDescription(state.contentDescription);

        if (mState.batteryLevel != state.batteryLevel) {
            updateBatteryIcon(state.batteryLevel);
        }

        boolean needsLayout = mState.batteryLevel != state.batteryLevel;

        if (mState.visible != state.visible && !mBlocked) {
            needsLayout |= true;
            setVisibility(state.visible ? View.VISIBLE : View.GONE);
        }

        mState = state;
        return needsLayout;
    }

    private void updateBatteryIcon(int batteryLevel) {
        mBatteryLevel = batteryLevel;
        if (batteryLevel >= 0 && batteryLevel <= 100 && !mBlocked) {
            mBatteryIcon.setVisibility(View.VISIBLE);
            int iconId = R.drawable.ic_bluetooth_battery_0;
            if (mBatteryLevel == 100) {
                iconId = R.drawable.ic_bluetooth_battery_10;
            } else if (mBatteryLevel >= 90) {
                iconId = R.drawable.ic_bluetooth_battery_9;
            } else if (mBatteryLevel >= 80) {
                iconId = R.drawable.ic_bluetooth_battery_8;
            } else if (mBatteryLevel >= 70) {
                iconId = R.drawable.ic_bluetooth_battery_7;
            } else if (mBatteryLevel >= 60) {
                iconId = R.drawable.ic_bluetooth_battery_6;
            } else if (mBatteryLevel >= 50) {
                iconId = R.drawable.ic_bluetooth_battery_5;
            } else if (mBatteryLevel >= 40) {
                iconId = R.drawable.ic_bluetooth_battery_4;
            } else if (mBatteryLevel >= 30) {
                iconId = R.drawable.ic_bluetooth_battery_3;
            } else if (mBatteryLevel >= 20) {
                iconId = R.drawable.ic_bluetooth_battery_2;
            } else if (mBatteryLevel >= 10) {
                iconId = R.drawable.ic_bluetooth_battery_1;
            }
            mBatteryIcon.setImageDrawable(mContext.getDrawable(iconId));
            updateBatteryColor();
        } else {
            mBatteryIcon.setVisibility(View.GONE);
        }
    }
    
    private void updateBatteryColor() {
        int showColor = Settings.System.getInt(
                getContext().getContentResolver(),
                "BT_BATTERY_COLOR",
                0
        );

        int color;
        if (showColor != 0) {
            if (mBatteryLevel > 50) {
                color = isDarkMode ? mHighBatteryColorDarkNeon : mHighBatteryColorLightMuted;
            } else if (mBatteryLevel > 25) {
                color = isDarkMode ? mLowBatteryColorDarkNeon : mLowBatteryColorLightMuted;
            } else {
                color = isDarkMode ? mCriticalBatteryColorDarkNeon : mCriticalBatteryColorLightMuted;
            }
            mBatteryIcon.setImageTintList(ColorStateList.valueOf(color));
        } else {
            mBatteryIcon.setImageTintList(mBatteryLevel > 20 ? mBatteryColor :
                Utils.getColorError(mContext));
        }
    }

    private void initViewState() {
        setContentDescription(mState.contentDescription);
        updateBatteryIcon(mState.batteryLevel);
        setVisibility(mState.visible && !mBlocked ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        int areaTint = getTint(areas, this, tint);
        ColorStateList color = ColorStateList.valueOf(areaTint);
        mBatteryColor = color;
        isDarkMode = darkIntensity < 0.5f;
        updateBatteryColor();
        mBluetoothIcon.setImageTintList(color);
        mDotView.setDecorColor(areaTint);
        mDotView.setIconColor(areaTint, false);
    }

    @Override
    public String toString() {
        return "StatusBarBluetoothView(slot=" + mSlot + " state=" + mState + ")";
    }
}
