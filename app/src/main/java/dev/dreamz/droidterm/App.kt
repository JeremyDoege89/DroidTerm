package dev.dreamz.droidterm

import android.app.Application
import dev.dreamz.droidterm.data.AppDb
import dev.dreamz.droidterm.term.TermEnv

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        TermEnv.bootstrap(this)
        AppDb.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
