package com.tvbox.android44.feature.player;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import com.tvbox.android44.R;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19, 23, 28}, qualifiers = "w480dp-h480dp-mdpi", application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlayerControllerViewTest {
    private ActivityController<Activity> screen;
    private Activity activity;
    private PlayerControllerView controller;
    private int clicks;

    @Before public void setUp() {
        screen = Robolectric.buildActivity(Activity.class).create().start().resume();
        activity = screen.get(); activity.setContentView(R.layout.activity_player); screen.visible();
        controller = new PlayerControllerView(activity.findViewById(R.id.player_controller),
                new PlayerControllerView.Listener() {
                    public void onTogglePlay() { clicks++; }
                    public void onPrevEpisode() { clicks++; }
                    public void onNextEpisode() { clicks++; }
                    public void onCycleSpeed() { clicks++; }
                    public void onChangeLine() { clicks++; }
                    public void onExit() { clicks++; }
                    public void onSeekTo(int progress) { clicks++; }
                });
        controller.show(); controller.focusPlayToggle(); layout(480);
    }

    private void layout(int width) {
        View content = activity.getWindow().getDecorView();
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, width, 480);
    }

    @After public void tearDown() {
        controller.destroy(); screen.pause().stop().destroy();
    }

    @Test public void narrowLargeTextActionsScrollCompletelyIntoView() {
        int[] ids = {R.id.player_prev, R.id.player_toggle, R.id.player_next, R.id.player_speed,
                R.id.player_change_line, R.id.player_exit};
        for (int id : ids) {
            TextView action = activity.findViewById(id);
            action.setTextSize(28);
            // Legacy Robolectric does not measure real glyphs; force the oversized row.
            action.setMinWidth(100);
        }
        layout(480); controller.moveFocus(View.FOCUS_LEFT);
        HorizontalScrollView scroll = activity.findViewById(R.id.player_action_scroll);
        assertTrue(scroll.getChildAt(0).getWidth() > scroll.getWidth());
        for (int i = 0; i < ids.length; i++) {
            View action = activity.findViewById(ids[i]); assertTrue(action.hasFocus());
            Rect visible = new Rect(); assertTrue(action.getGlobalVisibleRect(visible));
            assertEquals("focused action is clipped: " + ids[i], action.getWidth(), visible.width());
            if (i + 1 < ids.length) controller.moveFocus(View.FOCUS_RIGHT);
        }
        assertTrue(scroll.getScrollX() > 0);
    }

    @Test public void focusNavigationNeverClicksAndProgressReturnsToLastAction() {
        controller.moveFocus(View.FOCUS_RIGHT);
        assertTrue(activity.findViewById(R.id.player_next).hasFocus());
        controller.moveFocus(View.FOCUS_UP);
        assertTrue(activity.findViewById(R.id.player_seek).hasFocus());
        controller.activateFocusedControl(); assertEquals(0, clicks);
        controller.moveFocus(View.FOCUS_DOWN);
        assertTrue(activity.findViewById(R.id.player_next).hasFocus());
        assertEquals(0, clicks);
        controller.activateFocusedControl(); assertEquals(1, clicks);
    }

    @Test public void touchButtonsStillActivateWithOneTap() {
        int[] ids = {R.id.player_prev, R.id.player_toggle, R.id.player_next, R.id.player_speed,
                R.id.player_change_line, R.id.player_exit};
        for (int i = 0; i < ids.length; i++) {
            View action = activity.findViewById(ids[i]);
            assertFalse(action.isFocusableInTouchMode());
            MotionEvent down = MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, 10, 10, 0);
            MotionEvent up = MotionEvent.obtain(0, 2, MotionEvent.ACTION_UP, 10, 10, 0);
            try { action.dispatchTouchEvent(down); action.dispatchTouchEvent(up); }
            finally { down.recycle(); up.recycle(); }
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("single tap did not activate the button", i + 1, clicks);
        }
    }

    @Test public void interactionRefreshesAutoHideAndPausedControlsStayVisible() {
        controller.setPlaying(true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        controller.moveFocus(View.FOCUS_RIGHT);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        assertTrue(controller.isShown());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertFalse(controller.isShown());
        controller.setPlaying(false); controller.show(); controller.focusPlayToggle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertTrue(controller.isShown());
    }
}
