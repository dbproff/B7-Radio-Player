package com.example.radioplayer;

import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * A TextView that always reports itself as focused and selected to ensure
 * the marquee effect (scrolling text) works reliably regardless of UI focus.
 */
public class MarqueeTextView extends AppCompatTextView {

    public MarqueeTextView(Context context) {
        super(context);
        init();
    }

    public MarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.MARQUEE);
        setMarqueeRepeatLimit(-1); // -1 is marquee_forever
        setHorizontallyScrolling(true);
    }

    @Override
    public boolean isFocused() {
        // Essential for marquee: the view must think it's focused to start scrolling
        return true;
    }

    @Override
    public boolean isSelected() {
        // Essential for marquee: the view must be in selected state
        return true;
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        // Force the super class to believe focus is always maintained
        super.onFocusChanged(true, direction, previouslyFocusedRect);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        // Force the super class to believe window focus is always present
        super.onWindowFocusChanged(true);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        // Re-trigger marquee when text changes by toggling selected state
        setSelected(false);
        post(() -> setSelected(true));
    }
}
