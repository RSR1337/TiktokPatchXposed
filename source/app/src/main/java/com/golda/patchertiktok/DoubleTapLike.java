package com.golda.patchertiktok;

final class DoubleTapLike {
    private static final String PANEL = "com.ss.android.ugc.feed.platform.panel.digg.DiggPanelComponent";

    private DoubleTapLike() { }

    static void install(ClassLoader loader) {
        Class<?> panel = Hooks.findClass(PANEL, loader);
        int hooks = panel == null ? 0 : Hooks.hookAll(panel, "handleDoubleClick", new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (Prefs.on(Prefs.NO_DOUBLE_TAP_LIKE)) param.setResult(null);
            }
        });
        RuntimeLog.log(hooks == 0 ? "double tap like unavailable: like panel not found" : "double tap like: hooks=" + hooks);
    }
}
