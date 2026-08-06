package coredevices.coreapp.api

import PlatformContext
import co.touchlab.kermit.Logger
import coredevices.coreapp.push.AtlasPushMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BugReports(
    private val platformContext: PlatformContext,
    private val bugReportsService: BugReportsService,
) {
    private val logger = Logger.withTag("BugReports")
    private val _ticketDetails = MutableStateFlow<BugReportsListResponse?>(null)
    val ticketDetails: StateFlow<BugReportsListResponse?> = _ticketDetails.asStateFlow()

    fun init() = Unit

    fun handlePushMessage(message: AtlasPushMessage) {
        GlobalScope.launch {
            launch(Dispatchers.IO) {
                refresh()
            }
            // TODO check that ticket exists
            withContext(Dispatchers.Main) {
                createNotification(
                    platformContext = platformContext,
                    title = message.title,
                    message = message.body,
                    conversationId = message.conversationId,
                )
            }
        }
    }

    suspend fun refresh() {
        logger.d { "refresh" }

        // The legacy support inbox requires a server account token. The
        // local-only build keeps local bug-report export but has no cloud inbox.
        _ticketDetails.value = null
    }
}

expect fun createNotification(
    platformContext: PlatformContext,
    title: String,
    message: String,
    conversationId: String,
)
