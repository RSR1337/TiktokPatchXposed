package com.golda.patchertiktok;

/**
 * Code the module runs outside a hook callback (posted to a Handler, on its own thread, in a
 * listener) is not covered by the hook guard: an exception there reaches the thread's handler and
 * closes TikTok. Such code goes through here, so a failure is only logged.
 */
final class Safe {
    private Safe() { }

    /** {@code task}, with any failure logged under {@code where} instead of closing TikTok. */
    static Runnable run(String where, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable error) {
                log(where, error);
            }
        };
    }

    /** A click handler for the module's own rows and buttons inside TikTok. */
    static android.view.View.OnClickListener click(String where, android.view.View.OnClickListener listener) {
        return view -> {
            try {
                listener.onClick(view);
            } catch (Throwable error) {
                log(where, error);
            }
        };
    }

    /** A pre-draw listener that lets the frame draw if it fails. */
    static android.view.ViewTreeObserver.OnPreDrawListener preDraw(String where, android.view.ViewTreeObserver.OnPreDrawListener listener) {
        return () -> {
            try {
                return listener.onPreDraw();
            } catch (Throwable error) {
                log(where, error);
                return true;
            }
        };
    }

    static void log(String where, Throwable error) {
        try {
            RuntimeLog.log(where + " failed: " + error);
        } catch (Throwable ignored) {
            // Nothing left to report with.
        }
    }
}
