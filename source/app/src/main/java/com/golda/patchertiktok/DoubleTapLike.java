package com.golda.patchertiktok;

/**
 * Double-tapping a video no longer likes it; the heart button still does. The feed passes its
 * double tap to the like panel (BaseListFragmentPanel hands it to DiggPanelComponent), while the
 * landscape player likes from its own handleDoubleClick. Both are skipped.
 * Idea and first version by RSR1337 (PR #15).
 */
final class DoubleTapLike {
    private static final String[] HANDLERS = {
            "com.ss.android.ugc.feed.platform.panel.digg.DiggPanelComponent",
            "com.ss.android.ugc.aweme.feed.landscape.LandscapeFragmentPanel"};

    private DoubleTapLike() { }

    static void install(ClassLoader loader) {
        Hooks.Hook skip = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (Prefs.on(Prefs.NO_DOUBLE_TAP_LIKE)) param.setResult(null);
            }
        };
        int hooks = 0;
        for (String name : HANDLERS) {
            Class<?> handler = Hooks.findClass(name, loader);
            if (handler != null) hooks += Hooks.hookAll(handler, "handleDoubleClick", skip);
        }
        RuntimeLog.log(hooks == 0 ? "double tap like unavailable: like panel not found" : "double tap like: hooks=" + hooks);
    }
}
