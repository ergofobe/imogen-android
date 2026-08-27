package com.imogen.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.imogen.android.backup.BackupScheduler
import com.imogen.android.backup.BackupSettings
import com.imogen.android.data.AccountLinker
import com.imogen.android.data.AccountStore
import com.imogen.android.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The container.
 *
 * Hand-wired rather than injected. There are five things in it, they are all singletons,
 * and none of them needs swapping out at runtime; a dependency-injection framework here
 * would be a build-time cost and an annotation processor in exchange for nothing.
 */
class ImogenApplication : Application() {

    val scope = CoroutineScope(SupervisorJob())

    val accountStore: AccountStore by lazy { AccountStore(this) }
    val sessions: SessionManager by lazy { SessionManager(this, accountStore) }
    val linker: AccountLinker by lazy { AccountLinker(this, accountStore) }
    val backupSettings: BackupSettings by lazy { BackupSettings(this) }

    override fun onCreate() {
        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                /**
                 * Coming to the front is the signal that matters. Somebody who has just
                 * taken a photograph is usually holding the phone, and a backup that
                 * waits for the next periodic window would let them put it down again
                 * before anything happened.
                 */
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch {
                        val preferences = backupSettings.current()
                        BackupScheduler.sync(this@ImogenApplication, preferences)
                        BackupScheduler.runNow(this@ImogenApplication, preferences)
                    }
                }
            },
        )
    }
}
