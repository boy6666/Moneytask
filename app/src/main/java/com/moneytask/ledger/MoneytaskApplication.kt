package com.moneytask.ledger

import android.app.Application

class MoneytaskApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
