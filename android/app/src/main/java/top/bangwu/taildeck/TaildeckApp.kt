package top.bangwu.taildeck

import android.app.Application

class TaildeckApp : Application() {
    lateinit var store: Store
        private set
    lateinit var tailscale: Tailscale
        private set
    lateinit var keys: SshKeys
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        tailscale = Tailscale(this, store)
        keys = SshKeys(this, store)
        if (store.configured) tailscale.start()
    }

    companion object {
        lateinit var instance: TaildeckApp
            private set
    }
}
