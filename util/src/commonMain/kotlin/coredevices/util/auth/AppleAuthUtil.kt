package coredevices.util.auth

import PlatformUiContext

interface AppleAuthUtil {
    suspend fun signInApple(context: PlatformUiContext): LocalIdentity?
}
