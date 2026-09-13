package com.ahwotel

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Reject the target before Application construction, providers or onCreate can touch data. */
class SafeTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName == "com.ahwotel.acceptance") { "unsafe_instrumentation_target" }
        return super.newApplication(cl, className, context)
    }
}
