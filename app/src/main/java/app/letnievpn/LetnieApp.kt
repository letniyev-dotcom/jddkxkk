package app.letnievpn

import android.app.Application
import app.letnievpn.data.Repo

class LetnieApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}
