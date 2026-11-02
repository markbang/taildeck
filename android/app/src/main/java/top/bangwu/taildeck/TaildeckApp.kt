package top.bangwu.taildeck

import android.app.Application

class TaildeckApp : Application() {
    lateinit var store: Store
        private set
    lateinit var tailscale: Tailscale
        private set
    lateinit var keys: SshKeys
        private set
    lateinit var herdr: HerdrHub
        private set

    /** True while any activity is started; used to skip redundant notifications. */
    @Volatile var foreground = false
        private set
    private var started = 0

    override fun onCreate() {
        super.onCreate()
        // Lets chrome://inspect attach to in-app pages to debug layout issues.
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        instance = this
        store = Store(this)
        tailscale = Tailscale(this, store)
        keys = SshKeys(this, store)
        herdr = HerdrHub(this, keys)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { foreground = ++started > 0 }
            override fun onActivityStopped(a: android.app.Activity) { foreground = --started > 0 }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
            override fun onActivityResumed(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        })
        if (store.configured) tailscale.start()
    }

    companion object {
        lateinit var instance: TaildeckApp
            private set
    }
}
