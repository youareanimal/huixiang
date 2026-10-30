package io.github.korppujauho.pocketts

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Account work survives navigation and activity recreation; the voice service owns voice independently. */
class HubAccountModel(app: Application) : AndroidViewModel(app) {
    val controller = CloudAccountController(app)
    val updater = AppUpdater(app)
    override fun onCleared() { controller.close(); updater.close() }
}
